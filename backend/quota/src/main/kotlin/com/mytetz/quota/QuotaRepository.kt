package com.mytetz.quota

import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Every write here is a single-document update, so Mongo serialises it against every other write to
 * that document. Nothing in this class reads a value and then writes a value derived from it —
 * that shape loses concurrent updates, and on a spend counter a lost update is a generation nobody
 * paid for.
 */
open class QuotaRepository(database: MongoDatabase) {

    private val principals = database.getCollection<PrincipalCounter>("principals")
    private val ledger = database.getCollection<CostLedgerEntry>("costLedger")

    /**
     * `expireAfterSeconds: 0` means "expire at the instant stored in the field", so a counter is
     * reaped exactly when its window ends. The field must hold a BSON Date for the monitor to act
     * at all — see [EpochMillisAsBsonDateTime].
     *
     * **Do not add a unique index to either collection here.** [incrementCounter] and
     * [incrementLedger] both rely on MongoDB resolving a concurrent upsert race by updating the
     * freshly-inserted document instead of raising a duplicate key error, and that only holds when
     * the update's equality predicate covers the key pattern of the unique index that would have
     * been violated. Today that index is `_id` and the predicate is `_id`. A second unique index on
     * any other field forfeits the guarantee and turns the race into dropped generations — spent
     * money that never reaches the ledger. See the note on [incrementCounter].
     */
    suspend fun ensureIndexes() {
        principals.createIndex(
            Indexes.ascending(WINDOW_EXPIRES_AT),
            IndexOptions().name("window_ttl").expireAfter(0, TimeUnit.SECONDS),
        )
    }

    suspend fun findCounter(principalId: String): PrincipalCounter? =
        principals.find(Filters.eq("_id", principalId)).firstOrNull()

    /**
     * Starts a fresh window if the stored one has ended, and reports whether this call was the one
     * that did it. The window predicate is part of the update's own filter, so the decision and the
     * reset are one atomic act: of N concurrent callers exactly one resets, and the losers cannot
     * erase a count a winner has already begun accumulating.
     *
     * Call this before [incrementCounter] and never after. Once any caller's reset has completed
     * the stored window is in the future, so every later reset attempt fails its filter — which is
     * what stops a straggling reset from landing on top of an increment.
     */
    suspend fun rollWindowIfExpired(principalId: String, now: Long, windowMillis: Long): Boolean {
        val result = principals.updateOne(
            Filters.and(
                Filters.eq("_id", principalId),
                Filters.lte(WINDOW_EXPIRES_AT, Date(now)),
            ),
            Updates.combine(
                Updates.set("windowStartEpochMillis", now),
                Updates.set(WINDOW_EXPIRES_AT, Date(now + windowMillis)),
                Updates.set("explainCount", 0),
                Updates.set("costMicros", 0L),
            ),
        )
        return result.modifiedCount > 0
    }

    /**
     * Adds one generation and its cost, creating the counter if this is the principal's first.
     *
     * The filter is a bare `_id` equality — deliberately, and it is why the window predicate lives
     * in [rollWindowIfExpired] instead of here. MongoDB only lets concurrent upserts fall through
     * to updating the freshly-inserted document when the match condition is an equality predicate
     * over the unique index's own key; a range predicate on the window would forfeit that and turn
     * the insert race into duplicate-key errors.
     * https://www.mongodb.com/docs/manual/reference/method/db.collection.update/
     *
     * The window fields use `$setOnInsert`, so they are written when the counter is created and
     * never touched again. A `$set` would re-anchor the window on every generation, turning a daily
     * allowance into a sliding one that a steady learner could never wait out.
     *
     * `open` only so a test can inject a mid-sequence failure; there is no production subclass.
     */
    open suspend fun incrementCounter(principalId: String, now: Long, windowMillis: Long, costMicros: Long) {
        principals.updateOne(
            Filters.eq("_id", principalId),
            Updates.combine(
                Updates.inc("explainCount", 1),
                Updates.inc("costMicros", costMicros),
                Updates.setOnInsert("windowStartEpochMillis", now),
                Updates.setOnInsert(WINDOW_EXPIRES_AT, Date(now + windowMillis)),
            ),
            UpdateOptions().upsert(true),
        )
    }

    /**
     * The global spend ledger, one document per UTC calendar day. Relies on exactly the same five
     * conditions as [incrementCounter] — bare `_id` equality, single-document upsert, no unique
     * index beyond `_id` — because this is the write that has to survive a concurrent first
     * generation of the day. Losing it loses money the breaker would otherwise have seen.
     *
     * `open` only so a test can inject a mid-sequence failure; there is no production subclass.
     */
    open suspend fun incrementLedger(day: String, costMicros: Long) {
        ledger.updateOne(
            Filters.eq("_id", day),
            Updates.combine(
                Updates.inc("costMicros", costMicros),
                Updates.inc("generations", 1L),
            ),
            UpdateOptions().upsert(true),
        )
    }

    suspend fun ledgerFor(day: String): CostLedgerEntry? =
        ledger.find(Filters.eq("_id", day)).firstOrNull()

    private companion object {
        /** Named without an `EpochMillis` suffix because the stored value is a Date, not a number. */
        const val WINDOW_EXPIRES_AT = "windowExpiresAt"
    }
}
