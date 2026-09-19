package com.mytetz.graph

import kotlinx.serialization.Serializable

/**
 * The diagram format a [DiagramMedia] value carries.
 *
 * `SVG` is the only format this slice generates. `MERMAID` is reserved for a later slice, only if
 * the SVG path proves too weak — see the plan's own Decision 1. A stored document keeps its own
 * `kind` for ever, so a later `MERMAID` addition needs no migration of an old `SVG` document.
 */
enum class DiagramKind { SVG, MERMAID }

/** One diagram: its format, and the already-sanitised source text. */
@Serializable
data class DiagramMedia(val kind: DiagramKind, val source: String)

/**
 * One licensed image from Wikimedia Commons, with the attribution a licence obliges the reader to
 * see. [attributionHtml] holds a small HTML string this server built from plain text and at most
 * one link — never a pass-through of Commons' own markup. See `CommonsClient`'s own KDoc for why.
 */
@Serializable
data class ImageMedia(
    val imageUrl: String,
    val title: String,
    val license: String,
    val attributionHtml: String,
    val commonsPageUrl: String,
)

/**
 * The media a `VISUALIZE` explanation carries: a diagram always, and a licensed image only when
 * Wikimedia Commons found one for the same span.
 *
 * [diagram] is not nullable: a `VISUALIZE` generation with no usable SVG is refused before
 * persistence, so every stored document that carries a [Media] value carries a real diagram.
 * [image] is nullable by design — a failed or an empty Commons lookup is degradation, not a
 * generation failure, and the document still stores the diagram alone. See `CommonsLookup`.
 */
@Serializable
data class Media(val diagram: DiagramMedia, val image: ImageMedia? = null)
