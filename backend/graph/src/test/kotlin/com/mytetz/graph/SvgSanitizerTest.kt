package com.mytetz.graph

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SvgSanitizerTest {

    @Test
    fun `a well-formed diagram passes clean`() {
        val result = SvgSanitizer.sanitize(
            """<svg viewBox="0 0 100 100"><title>A circle</title><circle cx="50" cy="50" r="40"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("<circle" in clean.svg)
    }

    @Test
    fun `a script element is refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><script>alert('x')</script><circle cx="1" cy="1" r="1"/></svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `a script element with a namespace prefix is refused by its local name`() {
        val result = SvgSanitizer.sanitize(
            """<svg xmlns:x="http://example.com"><x:script>alert('x')</x:script></svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `an uppercase SCRIPT element is refused the same as lowercase`() {
        val result = SvgSanitizer.sanitize("""<svg><SCRIPT>alert('x')</SCRIPT></svg>""")
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `a style element is refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><style>circle { fill: url(https://evil.example/x); }</style><circle cx="1" cy="1" r="1"/></svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `a style attribute is dropped, not refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><circle cx="1" cy="1" r="1" style="fill:url(https://evil.example/x)"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("style" !in clean.svg)
    }

    @Test
    fun `an onload event handler is dropped, not refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg onload="alert('x')"><circle cx="1" cy="1" r="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("onload" !in clean.svg)
    }

    @Test
    fun `an external image href is dropped with its element, not refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><image href="https://evil.example/tracker.png"/><circle cx="1" cy="1" r="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("evil.example" !in clean.svg)
        assertTrue("<circle" in clean.svg)
    }

    @Test
    fun `a foreignObject is refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><foreignObject><body xmlns="http://www.w3.org/1999/xhtml"><script>alert('x')</script></body></foreignObject></svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `a fill value with an external url is dropped, a local url reference is kept`() {
        val result = SvgSanitizer.sanitize(
            """<svg><defs><linearGradient id="g1"/></defs>
               <rect fill="url(https://evil.example/x)" width="1" height="1"/>
               <rect fill="url(#g1)" width="1" height="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("evil.example" !in clean.svg)
        assertTrue("url(#g1)" in clean.svg)
    }

    @Test
    fun `a marker-end referencing a local marker is kept`() {
        val result = SvgSanitizer.sanitize(
            """<svg><defs><marker id="arrow"/></defs><path d="M0 0" marker-end="url(#arrow)"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("marker-end=\"url(#arrow)\"" in clean.svg)
    }

    @Test
    fun `a use element is dropped, its children survive`() {
        // use is no longer on the element allowlist: every allowed shape, marker, gradient and
        // clip path refers to another element only through a url(#id) value, and a <use> that
        // refers to a <g> holding further <use> elements can make a browser render an exponential
        // number of shapes from a small, well-formed source.
        val result = SvgSanitizer.sanitize(
            """<svg><use href="#c1"><circle cx="1" cy="1" r="1"/></use></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("<use" !in clean.svg)
        assertTrue("<circle" in clean.svg)
    }

    @Test
    fun `an href attribute on an allowed element is dropped`() {
        // href and xlink:href are no longer on the attribute allowlist -- no allowed element
        // needs one, now that use is gone.
        val result = SvgSanitizer.sanitize(
            """<svg><circle href="#c1" cx="1" cy="1" r="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("href" !in clean.svg)
        assertTrue("<circle" in clean.svg)
    }

    @Test
    fun `a comment is dropped`() {
        val result = SvgSanitizer.sanitize("""<svg><!-- a comment --><circle cx="1" cy="1" r="1"/></svg>""")
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("comment" !in clean.svg)
    }

    @Test
    fun `a CDATA section is dropped`() {
        val result = SvgSanitizer.sanitize(
            """<svg><title><![CDATA[hello]]></title><circle cx="1" cy="1" r="1"/></svg>"""
        )
        assertIs<SvgSanitizeResult.Clean>(result)
    }

    @Test
    fun `a processing instruction is dropped`() {
        val result = SvgSanitizer.sanitize(
            """<?xml-stylesheet href="evil.css"?><svg><circle cx="1" cy="1" r="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("evil.css" !in clean.svg)
    }

    @Test
    fun `malformed XML is refused, not partially sanitised`() {
        val result = SvgSanitizer.sanitize("""<svg><circle cx="1" cy="1" r="1"></svg>""")
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `a DOCTYPE declaration is refused outright`() {
        val result = SvgSanitizer.sanitize(
            """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><svg>&xxe;</svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `an element not on the allowlist is dropped, its safe children survive`() {
        val result = SvgSanitizer.sanitize(
            """<svg><a href="https://example.com"><circle cx="1" cy="1" r="1"/></a></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("<circle" in clean.svg)
        assertTrue("href" !in clean.svg)
    }

    @Test
    fun `a script hidden inside an unlisted wrapper is still refused, not promoted through`() {
        // An unlisted wrapper is dropped and its children take its place — but a promoted child
        // must still pass every rule this file states. A naive "drop the wrapper, keep its
        // children unchecked" rule would let a script ride to safety behind one unlisted tag.
        val result = SvgSanitizer.sanitize(
            """<svg><a href="https://example.com"><script>alert('x')</script></a></svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    // ------------------------------------------------------------------ the value rule (positive, case-insensitive)

    @Test
    fun `the fill value rule is case-insensitive to a bad shape, not just a lowercase literal url(`() {
        val badValues = listOf(
            "URL(https://evil.example/x)",
            "Url( https://evil.example/x )",
        )
        badValues.forEach { bad ->
            val result = SvgSanitizer.sanitize("""<svg><circle cx="1" cy="1" r="1" fill="$bad"/></svg>""")
            val clean = assertIs<SvgSanitizeResult.Clean>(result, "for fill=\"$bad\"")
            assertTrue("evil.example" !in clean.svg, "for fill=\"$bad\": ${clean.svg}")
            assertTrue("fill=" !in clean.svg, "the whole attribute must be dropped for fill=\"$bad\": ${clean.svg}")
        }
    }

    @Test
    fun `a fill value with a backslash never passes, even a CSS escape of url(`() {
        // u\72l( is the CSS escape for the letter r; a real CSS engine reads this as url(. A
        // search for the literal text "url(" never finds it, so a value carrying a backslash at
        // all is refused instead of trying to decode every possible escape.
        val result = SvgSanitizer.sanitize(
            """<svg><circle cx="1" cy="1" r="1" fill="u\72l(https://evil.example/x)"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("fill=" !in clean.svg, clean.svg)
    }

    @Test
    fun `the fill value rule keeps every allowed positive shape, in the case the model wrote it`() {
        val goodValues = listOf("url(#a)", "URL(#a)", "#fff", "rgb(1, 2, 3)", "currentColor", "red")
        goodValues.forEach { good ->
            val result = SvgSanitizer.sanitize(
                """<svg><defs><linearGradient id="a"/></defs><circle cx="1" cy="1" r="1" fill="$good"/></svg>"""
            )
            val clean = assertIs<SvgSanitizeResult.Clean>(result, "for fill=\"$good\"")
            assertTrue(
                "fill=\"$good\"" in clean.svg,
                "expected fill=\"$good\" to survive unchanged in: ${clean.svg}",
            )
        }
    }

    @Test
    fun `an attribute with a namespace prefix is dropped -- xmlns colon, xml colon and a made-up colon alike`() {
        // "evil" must be a declared prefix, or the document is not well-formed namespace XML at
        // all -- a different failure than the one this test means to pin.
        val result = SvgSanitizer.sanitize(
            """<svg xmlns:x="http://example.com" xml:base="https://evil.example/"
               xmlns:evil="http://example.com/evil" evil:fill="red">
               <circle cx="1" cy="1" r="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("xmlns:x" !in clean.svg, clean.svg)
        assertTrue("xmlns:evil" !in clean.svg, clean.svg)
        assertTrue("xml:base" !in clean.svg, clean.svg)
        assertTrue("evil:fill" !in clean.svg, clean.svg)
        assertTrue("evil.example" !in clean.svg, clean.svg)
    }

    // ------------------------------------------------------------------ the SVG namespace

    @Test
    fun `an input with no xmlns gives an output whose root has the SVG namespace`() {
        val result = SvgSanitizer.sanitize(
            """<svg viewBox="0 0 10 10"><circle cx="1" cy="1" r="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue(
            Regex("""<svg\b[^>]*\bxmlns="http://www\.w3\.org/2000/svg"""").containsMatchIn(clean.svg),
            "expected an explicit SVG namespace on the root: ${clean.svg}",
        )
    }

    @Test
    fun `a root element that is not svg is refused`() {
        val result = SvgSanitizer.sanitize("""<g><circle cx="1" cy="1" r="1"/></g>""")
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `an element with an allowed local name in another namespace ends in the SVG namespace`() {
        // Selected: convert, never drop -- see toSvgNamespace's own KDoc for the reason. The
        // circle is real diagram content; the foreign namespace string it arrived under must not
        // survive into the output, and the element itself must.
        val result = SvgSanitizer.sanitize(
            """<svg><circle xmlns="http://example.com/other" cx="1" cy="1" r="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("<circle" in clean.svg)
        assertTrue("example.com" !in clean.svg, "the foreign namespace must not survive: ${clean.svg}")
    }

    @Test
    fun `nesting past the depth bound is refused`() {
        val nested = "<g>".repeat(41) + "<circle cx=\"1\" cy=\"1\" r=\"1\"/>" + "</g>".repeat(41)
        val result = SvgSanitizer.sanitize("<svg>$nested</svg>")
        assertIs<SvgSanitizeResult.Refused>(result)
    }
}
