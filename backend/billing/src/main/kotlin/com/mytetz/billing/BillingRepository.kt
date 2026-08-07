package com.mytetz.billing

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import java.util.concurrent.TimeUnit

/** The MongoDB error code for a duplicate key on a unique index. */
private const val DUPLICATE_KEY = 11000

/** How long a processed billing event guards against a resend. */
private const val EVENT_TTL_DAYS = 90L

/**
 * The store for a learner's billing row and for the webhook events that change it.
 *
 * `subscriptions` needs no index beyond the default one MongoDB keeps on `_id`. The document id
 * is the user id, so [find] is already a point read on the primary key.
 */
class BillingRepository(database: MongoDatabase) {

    private val subscriptions = database.getCollection<Subscription>("subscriptions")
    private val billingEvents = database.getCollection<BillingEvent>("billingEvents")

    /**
     * Creates every index this repository needs.
     *
     * `billingEvents` gets a TTL index on `receivedAt`, at 90 days, so a processed webhook event
     * does not grow the collection for ever. The field must hold a BSON Date for the TTL monitor
     * to act on it — see [EpochMillisAsBsonDateTime].
     *
     * `subscriptions` gets an index on `status`, because [listNonTerminal] reads by that field.
     */
    suspend fun ensureIndexes() {
        billingEvents.createIndex(
            Indexes.ascending("receivedAt"),
            IndexOptions().name("event_ttl").expireAfter(EVENT_TTL_DAYS, TimeUnit.DAYS),
        )
        subscriptions.createIndex(Indexes.ascending("status"), IndexOptions().name("by_status"))
    }

    suspend fun find(userId: String): Subscription? =
        subscriptions.find(Filters.eq("_id", userId)).firstOrNull()

    /** Writes [subscription] whole. Inserts a fresh row, or replaces the stored one for its user. */
    suspend fun upsert(subscription: Subscription) {
        subscriptions.replaceOne(
            Filters.eq("_id", subscription.userId),
            subscription,
            ReplaceOptions().upsert(true),
        )
    }

    /**
     * Records that [eventId] arrived at [nowEpochMillis], and reports whether this call was the
     * one that recorded it.
     *
     * Returns `false` on a duplicate `eventId` and `true` on a fresh one. A webhook sender can
     * resend the same event more than once. This method lets a caller act on the event only on
     * its first arrival, using the duplicate-key pattern of `ExplanationRepository.insertIfAbsent`
     * in `:backend:graph`.
     */
    suspend fun insertEventIfAbsent(eventId: String, nowEpochMillis: Long): Boolean =
        try {
            billingEvents.insertOne(BillingEvent(eventId = eventId, receivedAtEpochMillis = nowEpochMillis))
            true
        } catch (e: MongoWriteException) {
            if (e.error.code != DUPLICATE_KEY) throw e
            false
        }

    /**
     * Lists up to [limit] subscriptions whose status is not [SubscriptionStatus.EXPIRED].
     *
     * The filter requires the `status` field to exist. A bare `$ne` also matches a document with
     * no `status` field at all, and such a document fails to decode into [Subscription] and
     * throws. No writer produces a row like that today, but the guard costs nothing and closes the
     * gap for good.
     */
    suspend fun listNonTerminal(limit: Int): List<Subscription> {
        val notExpired = Filters.and(Filters.exists("status"), Filters.ne("status", SubscriptionStatus.EXPIRED.name))
        return subscriptions.find(notExpired).limit(limit).toList()
    }
}
