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
)
