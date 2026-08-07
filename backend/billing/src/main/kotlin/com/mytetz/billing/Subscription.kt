package com.mytetz.billing

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
 * This class is a copy of `EpochMillisAsBsonDateTime` from `:backend:account`, itself a copy from
 * `:backend:quota`. The `billing` module does not depend on `account`. It holds its own copy of
 * this class instead.
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

/** A learner's payment state. [Entitlement.resolve] turns one of these into a decision. */
enum class SubscriptionStatus { TRIALING, ACTIVE, PAST_DUE, CANCELLED, EXPIRED }

/**
 * One learner's billing row.
 *
 * [userId] is the owning account's own id, and it is the document's `_id`. A lookup by user is
 * then a point read on the primary key, so [BillingRepository] needs no extra index for it.
 *
 * [trialEndsAtEpochMillis], [currentPeriodEndsAtEpochMillis] and [graceEndsAtEpochMillis] each
 * belong to one [status]. [Entitlement.resolve] reads only the field that matches the stored
 * status, and it treats a missing date as a reason to refuse, never as a reason to allow.
 *
 * [freemiusUserId] and [freemiusSubscriptionId] hold the identifiers Freemius uses for this
 * learner. A later task writes them.
 */
@Serializable
data class Subscription(
    @SerialName("_id") val userId: String,
    val status: SubscriptionStatus,
    val trialEndsAtEpochMillis: Long? = null,
    val currentPeriodEndsAtEpochMillis: Long? = null,
    val graceEndsAtEpochMillis: Long? = null,
    val freemiusUserId: String? = null,
    val freemiusSubscriptionId: String? = null,
    val updatedAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
)

/**
 * A record that marks one billing webhook event as processed.
 *
 * [eventId] is the sender's own id for the event, and it is the document's `_id`. The unique
 * index MongoDB keeps on `_id` is what lets [BillingRepository.insertEventIfAbsent] tell a fresh
 * event from a resend.
 *
 * [receivedAtEpochMillis] drives the collection's 90-day TTL index. A sender does not resend an
 * event for ever, so a row this old guards against nothing further.
 */
@Serializable
data class BillingEvent(
    @SerialName("_id") val eventId: String,
    @SerialName("receivedAt") @Serializable(with = EpochMillisAsBsonDateTime::class)
    val receivedAtEpochMillis: Long,
)
