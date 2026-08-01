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
)
