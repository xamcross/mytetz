package com.mytetz.quota

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
 * Who is asking. The prefix is load-bearing: [anonymous] takes a client-supplied UUID and [user]
 * takes an id from the account store, and the counter is keyed on this string alone — without the
 * namespace a visitor could claim a signed-in learner's allowance by choosing their id as a "UUID".
 */
@JvmInline
value class PrincipalId(val value: String) {
    companion object {
        fun anonymous(uuid: String) = PrincipalId("anon:$uuid")
        fun user(id: String) = PrincipalId("user:$id")
    }
}

/**
 * Epoch millis in Kotlin, a BSON **Date** on the wire.
 *
 * MongoDB's TTL monitor acts only on a field holding a date value; against a number it does nothing
 * at all — no error is raised and nothing is ever reaped
 * (https://www.mongodb.com/docs/manual/core/index-ttl/, "If the indexed field in a document doesn't
 * contain one or more date values, the document will not expire"). Storing the expiry as a plain
 * `Long` therefore makes the TTL index a silent no-op, and `principals` grows by one document per
 * anonymous visitor forever.
 *
 * Encoding it as a Date rather than changing the Kotlin type keeps [QuotaService]'s window
 * comparison in epoch millis, where it belongs: TTL is a cleanup mechanism, not a correctness one,
 * because the monitor only runs about once a minute.
 *
 * Note which half runs in production. [QuotaRepository] writes exclusively through `Updates.set` /
 * `Updates.setOnInsert` with a raw `java.util.Date`, so only [deserialize] is on a live path today;
 * the Date-on-the-wire invariant is currently held by the repository, and [serialize] is here so
 * that a future typed write cannot quietly reintroduce the no-op. Both halves are tested.
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
 * One principal's allowance for one window.
 *
 * [windowExpiresAtEpochMillis] is stored under the shorter BSON name `windowExpiresAt` because the
 * stored value is a Date, not a number — see [EpochMillisAsBsonDateTime]. The Kotlin side stays in
 * epoch millis so every comparison in this module is plain arithmetic on a `Long`.
 */
@Serializable
data class PrincipalCounter(
    @SerialName("_id") val principalId: String,
    val windowStartEpochMillis: Long,
    @SerialName("windowExpiresAt")
    @Serializable(with = EpochMillisAsBsonDateTime::class)
    val windowExpiresAtEpochMillis: Long,
    val explainCount: Int,
    val costMicros: Long,
)

/** One UTC calendar day of global spend. [day] is `yyyy-MM-dd`, and it is the document's `_id`. */
@Serializable
data class CostLedgerEntry(
    @SerialName("_id") val day: String,
    val costMicros: Long,
    val generations: Long,
)
