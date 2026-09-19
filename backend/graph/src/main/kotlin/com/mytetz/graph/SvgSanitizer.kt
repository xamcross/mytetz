package com.mytetz.graph

import org.w3c.dom.CDATASection
import org.w3c.dom.Comment
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.ProcessingInstruction
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/** The verdict of one sanitisation run. */
sealed interface SvgSanitizeResult {
    /** [svg] is safe to store and safe to render. */
    data class Clean(val svg: String) : SvgSanitizeResult

    /** The whole document is unsafe or malformed. Nothing from it survives. */
    data class Refused(val reason: String) : SvgSanitizeResult
}

/**
 * The allowlist parser and sanitiser for a model-drawn inline SVG diagram.
 *
 * This is an allowlist, not a blocklist: an element or an attribute this file does not name is
 * dropped, never kept on the assumption that it is harmless. The parser is namespace-aware, and
 * every name check below reads a node's local name, case-folded — never its qualified name and
 * never its raw string. `<svg:script>`, `<x:script xmlns:x="…">` and `<SCRIPT>` all carry the local
 * name `script`, whatever prefix or case a model or an adversary writes it in, so a check against
 * the local name alone closes the bypass a plain string check would miss.
 *
 * ## What this file refuses outright
 *
 * A `<script>`, a `<foreignObject>` or a `<style>` element ends the whole sanitisation with
 * [SvgSanitizeResult.Refused], wherever it appears. Each one can carry executable or arbitrary
 * content as children, so dropping the wrapper alone would let that content re-enter as a bare
 * text node or a re-parented element under an allowed ancestor. A `<style>` element gets the same
 * treatment for a different reason: a diagram in this project uses presentation attributes, never
 * an embedded stylesheet, and CSS carries its own history of injection tricks.
 *
 * A `<!DOCTYPE` declaration is refused outright, before the walk ever runs, together with every
 * external entity and every external DTD. A parser that allows either carries the classic XXE
 * weakness: a crafted `<!DOCTYPE>` can read a file from the server, or make the server open a
 * network connection of an attacker's choosing.
 *
 * A document nested more than [MAX_DEPTH] levels deep is refused. The size bound in
 * [MediaValidator] already limits total element count to a low number of thousands, but it does
 * not bound nesting depth on its own — a document built from one short, repeated, self-closing
 * element can nest thousands of levels deep well inside that same character budget, and a naive
 * recursive walk of a tree that deep can overflow the JVM's own call stack before the character
 * bound is ever reached.
 *
 * Malformed XML is refused outright, never partly sanitised.
 *
 * ## What this file drops rather than refuses
 *
 * An element not on [ALLOWED_ELEMENTS] is removed, and its own children take its place, checked
 * again from that same position — an unlisted wrapper is not a threat on its own, but a promoted
 * child must still pass every rule this file states. Nothing skips a check by riding in on a
 * dropped wrapper's back, a `<script>` one level down included.
 *
 * An attribute whose local name starts with `on`, case-folded, is dropped on any element — every
 * event handler, named or not, by the start of its name rather than a fixed list of known bad
 * names, so a future handler name this list does not yet know is still caught.
 *
 * An attribute not on [ALLOWED_ATTRIBUTES] is dropped. An attribute that is on the list, but whose
 * value fails the value rule that applies to it, is also dropped — the element that carried it
 * survives either way. A comment, a CDATA section and a processing instruction are each removed
 * outright, wherever they sit — inside the diagram or beside its root — because a processing
 * instruction can name an external resource on its own, for example
 * `<?xml-stylesheet href="evil.css"?>`, outside any element or attribute this file otherwise checks.
 *
 * ## The value rule
 *
 * A name check alone cannot remove "every external reference": an allowed attribute's own value can
 * still carry one. The rule below is a positive one — a value passes only when it matches one of a
 * small set of known-safe shapes — rather than a search for a bad one: an earlier version of this
 * file searched the value for the literal text `url(`, and that search is both case-sensitive
 * (`fill="URL(https://evil.example/x)"` never contains lowercase `url(`) and blind to a CSS escape
 * (`fill="u\72l(https://evil.example/x)"` decodes to `url(` in a real CSS engine but contains no
 * such substring at all). Every check below folds case first, and a value carrying a backslash never
 * passes any rule — a legitimate diagram's `fill` and `stroke` values never need one.
 *
 * For `fill` and `stroke`, a value passes only when the whole trimmed value, without case, is one
 * of: `none`; `currentcolor`; a colour name of letters only; a hexadecimal colour (`#` and 3 to 8
 * hexadecimal digits); an `rgb()`, `rgba()`, `hsl()` or `hsla()` function whose parentheses hold only
 * digits, dots, commas, percent signs and spaces; or [LOCAL_URL_REFERENCE] — a `url(#id)` reference
 * to an element inside the same document, by its `id`, and nothing else.
 *
 * For `marker-start`, `marker-mid`, `marker-end` and `clip-path`, a value passes only as `none` or
 * as [LOCAL_URL_REFERENCE].
 *
 * For `href` and `xlink:href`, a value passes only when it starts with `#` and carries no other
 * scheme marker. A `<use>` element that loses its `href` this way renders nothing, which is the
 * safe outcome, not a document-wide refusal — the same choice this file already makes for a
 * dropped, unlisted element.
 */
object SvgSanitizer {

    private val ALLOWED_ELEMENTS = setOf(
        "svg", "title", "desc", "defs", "g", "path", "rect", "circle", "ellipse",
        "line", "polyline", "polygon", "text", "tspan", "marker", "lineargradient",
        "radialgradient", "stop", "clippath", "use",
    )

    /** Refused outright, by local name, wherever the element appears. See this file's own KDoc. */
    private val REFUSED_ELEMENTS = setOf("script", "foreignobject", "style")

    private val ALLOWED_ATTRIBUTES = setOf(
        "id", "class", "viewbox", "xmlns", "width", "height", "x", "y", "x1", "y1", "x2", "y2",
        "cx", "cy", "r", "rx", "ry", "d", "points", "transform", "fill", "fill-opacity", "stroke",
        "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-opacity", "opacity",
        "font-family", "font-size", "font-weight", "text-anchor", "offset", "stop-color",
        "stop-opacity", "gradientunits", "gradienttransform", "markerwidth", "markerheight",
        "refx", "refy", "orient", "role", "aria-label", "aria-hidden", "marker-start",
        "marker-mid", "marker-end", "clip-path", "href", "xlink:href",
    )

    /** `fill` and `stroke`: colour shapes, or a same-document reference. See [isSafeFillOrStrokeValue]. */
    private val FILL_STROKE_ATTRIBUTES = setOf("fill", "stroke")

    /** A same-document reference only, or `none`. See [isSafeReferenceValue]. */
    private val REFERENCE_ONLY_ATTRIBUTES = setOf("marker-start", "marker-mid", "marker-end", "clip-path")

    /** Attributes whose value must be a same-document fragment, checked by [isLocalFragment]. */
    private val FRAGMENT_ATTRIBUTES = setOf("href", "xlink:href")

    private val COLOR_NAME = Regex("^[A-Za-z]+$")
    private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{3,8}$")
    private val COLOR_FUNCTION = Regex("^(rgb|rgba|hsl|hsla)\\([0-9.,%\\s]*\\)$", RegexOption.IGNORE_CASE)

    /** A `url(#id)` reference to an element inside the same document, and nothing else. */
    private val LOCAL_URL_REFERENCE = Regex("^url\\(#[A-Za-z0-9_-]+\\)$", RegexOption.IGNORE_CASE)

    private const val MAX_DEPTH = 40

    fun sanitize(rawSource: String): SvgSanitizeResult = try {
        val document = parse(rawSource)
        when (val verdict = sanitizeChildren(document, depth = -1)) {
            is Verdict.Refused -> SvgSanitizeResult.Refused(verdict.reason)
            Verdict.Kept ->
                if (document.documentElement == null) {
                    SvgSanitizeResult.Refused("no root element survived sanitisation")
                } else {
                    SvgSanitizeResult.Clean(serialize(document))
                }
        }
    } catch (e: SAXException) {
        SvgSanitizeResult.Refused(e.message ?: "malformed XML")
    } catch (e: Exception) {
        // Any other parser or DOM fault — an unexpected document shape included — is a refusal,
        // never a partial result. See this file's own KDoc: malformed XML is refused outright.
        SvgSanitizeResult.Refused(e.message ?: "could not sanitise the document")
    }

    private sealed interface Verdict {
        data object Kept : Verdict
        data class Refused(val reason: String) : Verdict
    }

    /**
     * Walks [parent]'s own children, mutating the live tree in place, and returns once every
     * surviving child has been checked — recursively, for an element that is kept.
     *
     * This uses a live [Node.getChildNodes] view and an index that does **not** advance past a
     * node this function just removed or just replaced with its own children: the next call to
     * `children.item(i)` then reads whatever now sits at that position, so a comment disappears
     * without skipping the sibling that shifts into its place, and — the case that matters most —
     * a child promoted up from a just-unwrapped, unlisted wrapper is examined at that same
     * position, by this same loop, under this same set of rules. Nothing promoted from a dropped
     * wrapper ever leaves this function unchecked.
     *
     * [depth] is the depth of [parent] itself (the document's own children sit at depth 0, once
     * [parent] is the document). A child element kept at depth `d` recurses at depth `d + 1`.
     */
    private fun sanitizeChildren(parent: Node, depth: Int): Verdict {
        val children = parent.childNodes
        var i = 0
        while (i < children.length) {
            when (val child = children.item(i)) {
                is Comment, is CDATASection, is ProcessingInstruction -> {
                    parent.removeChild(child)
                    // Do not advance: the next sibling has shifted into index i.
                }

                is Element -> {
                    val localName = (child.localName ?: child.tagName).lowercase()
                    when {
                        localName in REFUSED_ELEMENTS ->
                            return Verdict.Refused("refused element '$localName'")

                        localName in ALLOWED_ELEMENTS -> {
                            val childDepth = depth + 1
                            if (childDepth > MAX_DEPTH) {
                                return Verdict.Refused("nesting deeper than $MAX_DEPTH levels")
                            }
                            filterAttributes(child)
                            when (val verdict = sanitizeChildren(child, childDepth)) {
                                is Verdict.Refused -> return verdict
                                Verdict.Kept -> Unit
                            }
                            i++
                        }

                        else -> {
                            // Not on the allowlist, and not a refused name either: not a threat on
                            // its own. Move every one of its own children to occupy its place, in
                            // order, then drop the now-empty wrapper. `i` stays put on purpose —
                            // see this function's own KDoc for why.
                            while (child.firstChild != null) {
                                parent.insertBefore(child.firstChild, child)
                            }
                            parent.removeChild(child)
                        }
                    }
                }

                else -> i++ // a Text node: kept as is.
            }
        }
        return Verdict.Kept
    }

    private fun filterAttributes(element: Element) {
        val attributes = element.attributes
        val toRemove = mutableListOf<String>()

        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            val qualifiedName = attribute.nodeName
            val localName = (attribute.localName ?: qualifiedName).lowercase()

            val keep = when {
                localName.startsWith("on") -> false
                localName !in ALLOWED_ATTRIBUTES && qualifiedName.lowercase() !in ALLOWED_ATTRIBUTES -> false
                localName in FILL_STROKE_ATTRIBUTES -> isSafeFillOrStrokeValue(attribute.nodeValue)
                localName in REFERENCE_ONLY_ATTRIBUTES -> isSafeReferenceValue(attribute.nodeValue)
                localName in FRAGMENT_ATTRIBUTES -> isLocalFragment(attribute.nodeValue)
                else -> true
            }

            if (!keep) toRemove += qualifiedName
        }

        toRemove.forEach { element.removeAttribute(it) }
    }

    /** See this file's own "The value rule" section for `fill` and `stroke`. */
    private fun isSafeFillOrStrokeValue(value: String): Boolean {
        if ("\\" in value) return false
        val trimmed = value.trim()
        return trimmed.equals("none", ignoreCase = true) ||
            trimmed.equals("currentcolor", ignoreCase = true) ||
            COLOR_NAME.matches(trimmed) ||
            HEX_COLOR.matches(trimmed) ||
            COLOR_FUNCTION.matches(trimmed) ||
            LOCAL_URL_REFERENCE.matches(trimmed)
    }

    /** See this file's own "The value rule" section for `marker-*` and `clip-path`. */
    private fun isSafeReferenceValue(value: String): Boolean {
        if ("\\" in value) return false
        val trimmed = value.trim()
        return trimmed.equals("none", ignoreCase = true) || LOCAL_URL_REFERENCE.matches(trimmed)
    }

    /** `#id`, and nothing else: no scheme marker, and no `//` that a scheme-relative URL would carry. */
    private fun isLocalFragment(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("#") && ":" !in trimmed && "//" !in trimmed
    }

    private fun parse(rawSource: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
            isXIncludeAware = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(rawSource)))
    }

    private fun serialize(document: Document): String {
        val transformer = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }.newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val writer = StringWriter()
        // The document, not just its root element: sanitizeChildren already dropped every
        // top-level comment and processing instruction beside the root, so nothing unwanted rides
        // along, and serializing the Document keeps the root's own namespace declarations intact.
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }
}
