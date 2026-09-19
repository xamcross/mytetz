package com.mytetz.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class TopicStatus { DRAFT, PUBLISHED }

@Serializable
data class Topic(
    @SerialName("_id") val slug: String,
    val title: String,
    val category: String,
    val summary: String,
    val aliases: List<String> = emptyList(),
    val status: TopicStatus = TopicStatus.PUBLISHED,
    val sortWeight: Int = 0,
    /**
     * When a person last confirmed this topic's text, in epoch milliseconds. Null until a
     * curator sets it.
     *
     * A new field, and not a reuse of a seed explanation's own timestamp: a model migration
     * regenerates every seed on the same day, whether or not a person looked at the new text.
     * See `docs/superpowers/specs/2026-09-19-public-surface-design.md` section 8.
     */
    val reviewedAt: Long? = null,
)
