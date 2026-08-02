package com.mytetz.session

import com.mytetz.graph.Verb
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class SessionStatus { ACTIVE, COMPLETED }

/**
 * One step the learner took: which explanation they landed on, which span and verb got them there,
 * and which node they were on when they pressed it.
 *
 * [explanationKey] is a pointer into the content-addressed explanation store, not prose. Two
 * learners who reach the same span by the same path share one document; the session records only
 * that they went there.
 *
 * [verb] is `com.mytetz.graph.Verb` and it is part of this module's public API, which is why
 * `:backend:session` exposes `:backend:graph` as an `api` dependency rather than an
 * `implementation` one.
 */
@Serializable
data class SessionNode(
    val nodeId: String,
    val parentNodeId: String?,
    val explanationKey: String,
    val span: String,
    val verb: Verb,
    val variant: Int,
    val depth: Int,
    val createdAtEpochMillis: Long,
)

/**
 * Holds pointers and ordering only. Prose lives in the explanation store.
 *
 * [nodes] is a tree flattened into an array, linked by [SessionNode.parentNodeId], and
 * [SessionRepository.appendNode] only ever `$push`es — so on every document this module writes, a
 * parent precedes its children. That ordering is an accident of how it is written and nothing may
 * depend on it: [ContextChain.pathTo] walks the parent links.
 *
 * Nothing validates a document on the way back out of Mongo. A session whose nodes carry a dangling
 * parent, a parent cycle or a duplicate id is therefore representable here, and [ContextChain] is
 * where that is caught — loudly, because every one of those corruptions otherwise yields a
 * plausible-looking context chain that is not the learner's.
 */
@Serializable
data class LearningSession(
    @SerialName("_id") val id: String,
    val principalId: String,
    val topicSlug: String,
    val rootNodeId: String,
    val currentNodeId: String,
    val nodes: List<SessionNode>,
    val startedAtEpochMillis: Long,
    val lastActiveAtEpochMillis: Long,
    val status: SessionStatus = SessionStatus.ACTIVE,
)
