package com.mytetz.session

import com.mytetz.graph.Verb
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bson.BsonDateTime
import org.bson.codecs.kotlinx.BsonDecoder
import org.bson.codecs.kotlinx.BsonEncoder

/**
 * A serializer for epoch millis. It stores the value as a BSON Date on the wire.
 *
 * MongoDB's TTL monitor acts only on a field that holds a date value. A plain `Long` field turns
 * a TTL index into a silent no-op. The collection then grows without limit.
 *
 * This class keeps the Kotlin type as a `Long`. Every comparison in this module then stays plain
 * arithmetic. This class keeps the stored type as a `Date`. The TTL monitor can then act on the
 * field.
 *
 * This class is a copy of `EpochMillisAsBsonDateTime` from `:backend:quota`. The `session` module
 * does not depend on `quota`. It holds its own copy of this class instead.
 */
internal object EpochMillisAsBsonDateTime : KSerializer<Long> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("EpochMillisAsBsonDateTime", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = when (encoder) {
        is BsonEncoder -> encoder.encodeBsonValue(BsonDateTime(value))
        else -> throw SerializationException("epoch millis need a BsonEncoder, got ${encoder::class}")
    }

    override fun deserialize(decoder: Decoder): Long = when (decoder) {
        is BsonDecoder -> decoder.decodeBsonValue().asDateTime().value
        else -> throw SerializationException("epoch millis need a BsonDecoder, got ${decoder::class}")
    }
}

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
/**
 * [expiresAtEpochMillis] is null for a signed-in learner's session, which is kept until account
 * deletion removes it — see `SessionRepository.deleteForPrincipal`. It holds a value only for an
 * anonymous session, set once by [SessionService.create] and cleared by
 * [SessionRepository.reassignPrincipal] the moment sign-in moves the session onto a `user:`
 * principal. [SessionRepository.ensureIndexes]'s TTL index acts on it. A raw `Long` here would make
 * that index a silent no-op — see [EpochMillisAsBsonDateTime] and, for the fuller argument,
 * `com.mytetz.quota.Principal.kt`'s own copy of this class.
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
    @SerialName("expiresAt") @Serializable(with = EpochMillisAsBsonDateTime::class)
    val expiresAtEpochMillis: Long? = null,
)
