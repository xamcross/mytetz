package com.mytetz.billing

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import java.util.Date
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
open class BillingRepository(database: MongoDatabase) {

    private val subscriptions = database.getCollection<Subscription>("subscriptions")
    private val billingEvents = database.getCollection<BillingEvent>("billingEvents")
    private val trialStarts = database.getCollection<TrialStartCount>("trialStarts")

    /**
     * Creates every index this repository needs.
     *
     * `billingEvents` gets a TTL index on `receivedAt`, at 90 days, so a processed webhook event
     * does not grow the collection for ever. The field must hold a BSON Date for the TTL monitor
     * to act on it — see [EpochMillisAsBsonDateTime].
     *
     * `subscriptions` gets an index on `status`, because [listNonTerminal] reads by that field.
     *
     * `trialStarts` gets a TTL index on `windowExpiresAt`, so an IP bucket's trial-start count
     * does not grow the collection for ever once its 24-hour window has passed.
     */
    suspend fun ensureIndexes() {
        billingEvents.createIndex(
            Indexes.ascending("receivedAt"),
            IndexOptions().name("event_ttl").expireAfter(EVENT_TTL_DAYS, TimeUnit.DAYS),
        )
        subscriptions.createIndex(Indexes.ascending("status"), IndexOptions().name("by_status"))
        trialStarts.createIndex(
            Indexes.ascending("windowExpiresAt"),
            IndexOptions().name("trial_start_ttl").expireAfter(0, TimeUnit.SECONDS),
        )
    }

    /**
     * `open` only so a test can drive the lost-race branch in
     * [BillingService.startTrialIfAbsent]. There is no production subclass.
     */
    open suspend fun find(userId: String): Subscription? =
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
     * Inserts [subscription], and reports whether this call was the one that created the row.
     *
     * Returns `true` on a fresh insert and `false` on a duplicate `_id`, using the same
     * duplicate-key idiom as [insertEventIfAbsent]. [BillingService.startTrialIfAbsent] needs this
     * shape and not [upsert]: two callers can both read no row for one user and both build a fresh
     * [Subscription], and only one insert can win here. The loser must learn that it lost, so it
     * can hand back the winner's own row instead of silently replacing it — which is what
     * [upsert]'s `replaceOne` would do, and which can downgrade a subscription a webhook has
     * already activated back to [SubscriptionStatus.TRIALING].
     *
     * `open` only so a test can drive the lost-race branch in
     * [BillingService.startTrialIfAbsent]. There is no production subclass.
     */
    open suspend fun insertIfAbsent(subscription: Subscription): Boolean =
        try {
            subscriptions.insertOne(subscription)
            true
        } catch (e: MongoWriteException) {
            if (e.error.code != DUPLICATE_KEY) throw e
            false
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
     * The filter requires the `status` field to exist. A bare `$ne` filter also matches a document
     * with no `status` field. Such a document fails to decode into [Subscription]. The decode then
     * throws. No writer creates such a row today. This guard removes that risk. It costs nothing
     * to add.
     */
    suspend fun listNonTerminal(limit: Int): List<Subscription> {
        val notExpired = Filters.and(Filters.exists("status"), Filters.ne("status", SubscriptionStatus.EXPIRED.name))
        return subscriptions.find(notExpired).limit(limit).toList()
    }

    // ------------------------------------------------------------------ the trial cap

    /**
     * Starts a fresh window for [ipBucket] when the stored one has already ended.
     *
     * Same shape as `com.mytetz.quota.QuotaRepository.rollWindowIfExpired`, and for the same
     * reason: [BillingService.startTrialIfAbsent] calls this before [tryRecordTrialStart] and
     * never after, so a straggling roll cannot land on top of a count a winner has already begun
     * accumulating. Existing-document only — see [tryRecordTrialStart] for how a counter that does
     * not exist yet is created.
     */
    suspend fun rollTrialWindowIfExpired(ipBucket: String, nowEpochMillis: Long, windowMillis: Long) {
        trialStarts.updateOne(
            Filters.and(Filters.eq("_id", ipBucket), Filters.lte("windowExpiresAt", Date(nowEpochMillis))),
            Updates.combine(
                Updates.set("windowStartEpochMillis", nowEpochMillis),
                Updates.set("windowExpiresAt", Date(nowEpochMillis + windowMillis)),
                Updates.set("count", 0),
            ),
        )
    }

    /**
     * Checks [ipBucket]'s trial-start count against [limit] and records one more trial start when
     * the bucket is under it. Returns true when this call may proceed with a fresh trial, false
     * when the bucket's current window is already at [limit]. Call [rollTrialWindowIfExpired]
     * first, so the count this method reads is the current window's and not a stale one.
     *
     * Two Mongo operations, not one, and deliberately not combined into a single upsert.
     *
     * The check and the increment must be one atomic step, or two concurrent callers behind one
     * bucket can both read a count under [limit] and both increment past it — the exact
     * check-then-act race a separate read and a separate `$inc` leaves open. `findOneAndUpdate`
     * with a `count` filter is that one atomic step, in the second call below.
     *
     * That filter cannot also carry `upsert = true`, though, or it reintroduces a different
     * failure: an upsert is only safe to combine with a non-equality condition when nothing can
     * ever match the equality part *and* fail the rest — the moment a document with this `_id`
     * already exists but fails the `count` condition, MongoDB finds no match, attempts to insert a
     * second document under the same `_id` to satisfy the upsert, and raises a duplicate-key error
     * instead of the graceful refusal this method promises. `com.mytetz.quota.QuotaRepository`'s
     * own KDoc on `incrementCounter` states the same rule at length: an upsert is safe only when
     * its filter is bare equality over the unique index's own key.
     *
     * So the first call below is that safe, bare-equality upsert. It only ever creates the
     * counter, with `count` starting at zero, and never increments it — `Updates.setOnInsert`
     * writes nothing at all when the document already exists. The second call then never upserts,
     * so it cannot hit the hazard above: a document it does not touch is a document that already
     * existed and failed the `count` condition, never one this call tried and failed to insert.
     */
    suspend fun tryRecordTrialStart(ipBucket: String, nowEpochMillis: Long, windowMillis: Long, limit: Int): Boolean {
        trialStarts.updateOne(
            Filters.eq("_id", ipBucket),
            Updates.combine(
                Updates.setOnInsert("windowStartEpochMillis", nowEpochMillis),
                Updates.setOnInsert("windowExpiresAt", Date(nowEpochMillis + windowMillis)),
                Updates.setOnInsert("count", 0),
            ),
            UpdateOptions().upsert(true),
        )

        val updated = trialStarts.findOneAndUpdate(
            Filters.and(Filters.eq("_id", ipBucket), Filters.lt("count", limit)),
            Updates.inc("count", 1),
        )
        return updated != null
    }
}
