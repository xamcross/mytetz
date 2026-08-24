package com.mytetz.account

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
 * This class is a copy of `EpochMillisAsBsonDateTime` from `:backend:quota`. The `account` module
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

/**
 * A learner's account.
 *
 * [id] is the hex string of the document's `ObjectId`. [email] is the normalised address. The
 * unique index on `email` targets this field.
 *
 * [googleSub] holds the Google account's subject claim. The field is null at first. It gets a
 * value when the learner adds a Google account.
 */
@Serializable
data class User(
    @SerialName("_id") val id: String,
    val email: String,
    val googleSub: String? = null,
    val createdAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
)

/**
 * A one-time token for a magic-link sign-in.
 *
 * [tokenHash] is the SHA-256 hex digest of the raw token. It is the document's `_id`. The raw
 * token itself never reaches storage. A database read alone cannot yield a usable link.
 *
 * [expiresAtEpochMillis] drives the collection's TTL index. [AccountRepository.consumeToken] also
 * filters on this field.
 */
@Serializable
data class MagicLinkToken(
    @SerialName("_id") val tokenHash: String,
    val email: String,
    @SerialName("expiresAt") @Serializable(with = EpochMillisAsBsonDateTime::class)
    val expiresAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
)

/**
 * A signed-in learner's session.
 *
 * [sessionId] holds 16 random bytes. The encoding is base64url, without padding. It is the
 * document's `_id`.
 *
 * [expiresAtEpochMillis] drives the collection's TTL index.
 */
@Serializable
data class AuthSession(
    @SerialName("_id") val sessionId: String,
    val userId: String,
    val createdAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
    @SerialName("expiresAt") @Serializable(with = EpochMillisAsBsonDateTime::class)
    val expiresAtEpochMillis: Long,
)
