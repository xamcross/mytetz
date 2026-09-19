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
    fun `a use href with a local fragment is kept, an external href is dropped`() {
        val result = SvgSanitizer.sanitize(
            """<svg><defs><circle id="c1" r="1"/></defs><use href="#c1"/><use href="https://evil.example/x.svg"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("href=\"#c1\"" in clean.svg)
        assertTrue("evil.example" !in clean.svg)
    }

    @Test
    fun `an xlink colon href is checked by the same value rule as href`() {
        val result = SvgSanitizer.sanitize(
            """<svg xmlns:xlink="http://www.w3.org/1999/xlink"><use xlink:href="https://evil.example/x.svg"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("evil.example" !in clean.svg)
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

    @Test
    fun `nesting past the depth bound is refused`() {
        val nested = "<g>".repeat(41) + "<circle cx=\"1\" cy=\"1\" r=\"1\"/>" + "</g>".repeat(41)
        val result = SvgSanitizer.sanitize("<svg>$nested</svg>")
        assertIs<SvgSanitizeResult.Refused>(result)
    }
}
