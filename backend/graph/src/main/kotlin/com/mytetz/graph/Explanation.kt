package com.mytetz.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LlmSource(val url: String, val title: String)

@Serializable
data class Explanation(
    @SerialName("_id") val key: String,
    val topicSlug: String,
    val parentKey: String?,
    val span: String?,
    val spanSentence: String?,
    val verb: Verb,
    val variant: Int,
    val depth: Int,
    val body: String,
    val grounded: Boolean,
    val sources: List<LlmSource>,
    val promptVersion: String,
    val modelFamily: String,
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costMicros: Long,
    val requestCount: Long,
    val createdAtEpochMillis: Long,
    /**
     * Set only for a `VISUALIZE` document. Every other verb leaves this null. A document stored
     * before this field existed also decodes with `media == null`, because the field carries a
     * default and the driver does not require a stored key to be present.
     */
    val media: Media? = null,
    /**
     * True only once a person has read this text and approved it for a public page. The default
     * is `false`, for a new document and for a document a model wrote before this field existed:
     * a stored document with no `published` key decodes with `published == false`, the same way
     * [media] already decodes `null` for an old document. Only [ExplanationRepository.setPublished]
     * ever sets this to `true` — see issue #48's own safety rule.
     */
    val published: Boolean = false,
)
