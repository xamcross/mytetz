package com.mytetz.billing

import kotlinx.coroutines.test.runTest
import org.bson.Document
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BillingServiceTest {

    companion object {
        /** 2023-11-14T22:13:20Z. Arbitrary; only the offsets from it matter. */
        private const val T0 = 1_700_000_000_000L
        private const val DAY_MILLIS = 86_400_000L
    }

    private val database = MongoTestSupport.database("billing_service")
    private val repository = BillingRepository(database)
    private var now = T0
    private val config = BillingConfig(
        trialGenerations = 40,
        trialDays = 7,
        graceDays = 3,
        subscriberDailyExplains = 25,
    )
    private val service = BillingService(repository, config) { now }

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Document>("subscriptions").drop()
        // Added for the `apply` tests below: they exercise BillingRepository.insertEventIfAbsent,
        // and different tests reuse short event ids such as "evt-1". Without this drop, a row a
        // previous test left behind would make an unrelated later test look like a replay.
        database.getCollection<Document>("billingEvents").drop()
        now = T0
    }

    /**
     * A [FreemiusEvent] with sensible defaults, so a test can name only the fields that matter to
     * it.
     */
    private fun freemiusEvent(
        id: String,
        type: String,
        userReference: String? = "u1",
        periodEndsAt: Long? = null,
        occurredAt: Long = now,
    ) = FreemiusEvent(
        id = id,
        type = type,
        userReference = userReference,
        freemiusUserId = "fs-user-1",
        freemiusSubscriptionId = "fs-sub-1",
        periodEndsAtEpochMillis = periodEndsAt,
        occurredAtEpochMillis = occurredAt,
    )

    // ------------------------------------------------------------------ startTrialIfAbsent

    @Test
    fun `startTrialIfAbsent inserts a TRIALING row for a fresh user`() = runTest {
        val subscription = service.startTrialIfAbsent("u1")

        assertEquals(SubscriptionStatus.TRIALING, subscription.status)
        assertEquals(now, subscription.createdAtEpochMillis)
        assertEquals(now + 7 * DAY_MILLIS, subscription.trialEndsAtEpochMillis)
        assertEquals(subscription, repository.find("u1"))
    }

    @Test
    fun `startTrialIfAbsent leaves an existing row untouched`() = runTest {
        val first = service.startTrialIfAbsent("u1")
        now += 3_600_000

        val second = service.startTrialIfAbsent("u1")

        assertEquals(first, second)
    }

    @Test
    fun `startTrialIfAbsent leaves a paid row untouched`() = runTest {
        val paid = Subscription(
            userId = "u1",
            status = SubscriptionStatus.ACTIVE,
            currentPeriodEndsAtEpochMillis = now + DAY_MILLIS,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        repository.upsert(paid)

        val result = service.startTrialIfAbsent("u1")

        assertEquals(paid, result)
    }

    @Test
    fun `a second trial for a user who already has one returns the stored row`() = runTest {
        val winner = Subscription(
            userId = "u1",
            status = SubscriptionStatus.TRIALING,
            trialEndsAtEpochMillis = now + 7 * DAY_MILLIS,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        // The winner's own insert, landed directly through the repository — a stand-in for a
        // second, genuinely concurrent sign-in that reached Mongo first. Driven through the
        // repository, and not through two coroutines, because a real race against one Mongo
        // connection is not a reliable thing to assert against.
        repository.insertIfAbsent(winner)
        now += 3_600_000

        // This call's own read sees the winner's row and returns early — the common case, and
        // the only path this test reaches. `a lost race returns the winner's stored row and not
        // the loser's own` below drives the insert-then-re-read branch that this test cannot.
        val result = service.startTrialIfAbsent("u1")

        assertEquals(winner, result, "the loser's own row must not replace the winner's")
        assertEquals(winner, repository.find("u1"), "the stored row must still be the winner's")
        assertEquals(winner.createdAtEpochMillis, repository.find("u1")?.createdAtEpochMillis)
    }

    @Test
    fun `a lost race returns the winner's stored row and not the loser's own`() = runTest {
        // The interleaving `a second trial for a user who already has one returns the stored row`
        // cannot reach: this user's first `find` answers null, so `startTrialIfAbsent` builds its
        // own row and tries to insert it — and loses. `winner` stands for the row a second,
        // genuinely concurrent sign-in already stored before that insert ran.
        val winner = Subscription(
            userId = "u1",
            status = SubscriptionStatus.TRIALING,
            trialEndsAtEpochMillis = now + 7 * DAY_MILLIS,
            createdAtEpochMillis = now - 1_000,
            updatedAtEpochMillis = now - 1_000,
        )
        var findCalls = 0
        val losingRace = object : BillingRepository(database) {
            override suspend fun find(userId: String): Subscription? {
                findCalls += 1
                // The first read: nothing stored yet. The second read: the winner's own row,
                // landed between this call's first read and its own insert.
                return if (findCalls == 1) null else winner
            }
            override suspend fun insertIfAbsent(subscription: Subscription): Boolean = false
        }

        val result = BillingService(losingRace, config) { now }.startTrialIfAbsent("u1")

        // `winner.createdAtEpochMillis` is `now - 1_000`; a `Subscription` built locally by
        // `startTrialIfAbsent` would carry `now` instead. Comparing this field is what tells the
        // stored row apart from the one the losing call built and discarded.
        assertEquals(winner, result, "the loser's own row must not be returned")
        assertEquals(winner.createdAtEpochMillis, result.createdAtEpochMillis)
    }

    // ------------------------------------------------------------------ insertIfAbsent

    @Test
    fun `insertIfAbsent inserts a fresh row and reports it as fresh`() = runTest {
        val trial = Subscription(
            userId = "u1",
            status = SubscriptionStatus.TRIALING,
            trialEndsAtEpochMillis = now + 7 * DAY_MILLIS,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )

        val inserted = repository.insertIfAbsent(trial)

        assertEquals(true, inserted)
        assertEquals(trial, repository.find("u1"))
    }

    @Test
    fun `insertIfAbsent reports a duplicate row as not fresh, and does not touch the stored one`() = runTest {
        val first = Subscription(
            userId = "u1",
            status = SubscriptionStatus.TRIALING,
            trialEndsAtEpochMillis = now + 7 * DAY_MILLIS,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        repository.insertIfAbsent(first)
        val second = first.copy(
            createdAtEpochMillis = now + 1_000,
            trialEndsAtEpochMillis = now + 1_000 + 7 * DAY_MILLIS,
        )

        val inserted = repository.insertIfAbsent(second)

        assertEquals(false, inserted)
        assertEquals(first, repository.find("u1"), "the loser's own row must not replace the winner's")
    }

    // ------------------------------------------------------------------ entitlementFor

    @Test
    fun `entitlementFor answers SubscriptionRequired for an unknown user`() = runTest {
        assertEquals(EntitlementDecision.SubscriptionRequired, service.entitlementFor("no-such-user"))
    }

    @Test
    fun `entitlementFor resolves a trial row through Entitlement`() = runTest {
        service.startTrialIfAbsent("u1")

        val decision = assertIs<EntitlementDecision.Allowed>(service.entitlementFor("u1"))

        assertEquals(SubscriptionStatus.TRIALING, decision.status)
        assertEquals(40, decision.allowance.generations)
        assertEquals(7 * DAY_MILLIS, decision.allowance.windowMillis)
    }

    @Test
    fun `entitlementFor answers SubscriptionRequired once the trial ends`() = runTest {
        service.startTrialIfAbsent("u1")
        now += 8 * DAY_MILLIS

        assertEquals(EntitlementDecision.SubscriptionRequired, service.entitlementFor("u1"))
    }

    @Test
    fun `entitlementFor resolves a subscriber row through Entitlement`() = runTest {
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.ACTIVE,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )

        val decision = assertIs<EntitlementDecision.Allowed>(service.entitlementFor("u1"))

        assertEquals(SubscriptionStatus.ACTIVE, decision.status)
        assertEquals(25, decision.allowance.generations)
        assertEquals(DAY_MILLIS, decision.allowance.windowMillis)
    }

    // ------------------------------------------------------------------ subscriptionFor

    @Test
    fun `subscriptionFor answers null for a user with no row, and inserts nothing`() = runTest {
        val result = service.subscriptionFor("no-such-user")

        assertEquals(null, result)
        assertEquals(null, repository.find("no-such-user"), "a read must never start a trial")
    }

    @Test
    fun `subscriptionFor answers the stored row untouched`() = runTest {
        val stored = service.startTrialIfAbsent("u1")

        assertEquals(stored, service.subscriptionFor("u1"))
    }

    // ------------------------------------------------------------------ apply: mapping

    @Test
    fun `a first payment moves the state to active`(): Unit = runTest {
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.TRIALING,
                trialEndsAtEpochMillis = now + 7 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val periodEnd = now + 30 * DAY_MILLIS
        val event = freemiusEvent("evt-created", "subscription.created", periodEndsAt = periodEnd, occurredAt = now + 1)

        val applied = service.apply(event)

        assertEquals(true, applied)
        val stored = repository.find("u1")
        assertEquals(SubscriptionStatus.ACTIVE, stored?.status)
        assertEquals(periodEnd, stored?.currentPeriodEndsAtEpochMillis)
        assertEquals(now + 1, stored?.updatedAtEpochMillis)
    }

    @Test
    fun `a renewal retry moves the state to active`(): Unit = runTest {
        // subscription.renewal.retry shares ACTIVE with subscription.created in the type map.
        // Nothing above exercises this second key on its own; without this test a typo in it
        // would pass every other test in this file.
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.PAST_DUE,
                graceEndsAtEpochMillis = now + 3 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val periodEnd = now + 30 * DAY_MILLIS
        val event = freemiusEvent("evt-retry", "subscription.renewal.retry", periodEndsAt = periodEnd, occurredAt = now + 1)

        val applied = service.apply(event)

        assertEquals(true, applied)
        val stored = repository.find("u1")
        assertEquals(SubscriptionStatus.ACTIVE, stored?.status)
        assertEquals(periodEnd, stored?.currentPeriodEndsAtEpochMillis)
    }

    @Test
    fun `a failed payment moves the state to past due with a grace`(): Unit = runTest {
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val failedAt = now + 1
        val event = freemiusEvent("evt-failed", "subscription.renewal.failed", occurredAt = failedAt)

        val applied = service.apply(event)

        assertEquals(true, applied)
        val stored = repository.find("u1")
        assertEquals(SubscriptionStatus.PAST_DUE, stored?.status)
        assertEquals(failedAt + config.graceDays * DAY_MILLIS, stored?.graceEndsAtEpochMillis)
    }

    @Test
    fun `a cancellation keeps the period end`(): Unit = runTest {
        val periodEnd = now + 30 * DAY_MILLIS
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEndsAtEpochMillis = periodEnd,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val event = freemiusEvent("evt-cancel", "subscription.cancelled", occurredAt = now + 1)

        val applied = service.apply(event)

        assertEquals(true, applied)
        val stored = repository.find("u1")
        assertEquals(SubscriptionStatus.CANCELLED, stored?.status)
        assertEquals(periodEnd, stored?.currentPeriodEndsAtEpochMillis, "a cancellation must keep the period end")
    }

    @Test
    fun `a refund expires the subscription`(): Unit = runTest {
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val event = freemiusEvent("evt-refund", "payment.refund", occurredAt = now + 1)

        val applied = service.apply(event)

        assertEquals(true, applied)
        assertEquals(SubscriptionStatus.EXPIRED, repository.find("u1")?.status)
    }

    @Test
    fun `a lost dispute expires the subscription`(): Unit = runTest {
        // payment.dispute.lost shares EXPIRED with payment.refund in the type map. Nothing above
        // exercises this second key on its own.
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val event = freemiusEvent("evt-dispute", "payment.dispute.lost", occurredAt = now + 1)

        val applied = service.apply(event)

        assertEquals(true, applied)
        assertEquals(SubscriptionStatus.EXPIRED, repository.find("u1")?.status)
    }

    // ------------------------------------------------------------------ apply: unknown type

    @Test
    fun `an unknown event type changes nothing`(): Unit = runTest {
        val before = Subscription(
            userId = "u1",
            status = SubscriptionStatus.ACTIVE,
            currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        repository.upsert(before)
        val event = freemiusEvent("evt-unknown", "subscription.something_new", occurredAt = now + 1)

        val applied = service.apply(event)

        assertEquals(false, applied)
        assertEquals(before, repository.find("u1"))
    }

    @Test
    fun `an unknown event type does not consume the event id`(): Unit = runTest {
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.TRIALING,
                trialEndsAtEpochMillis = now + 7 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val unknown = freemiusEvent("evt-shared-id", "subscription.something_new", occurredAt = now + 1)
        assertEquals(false, service.apply(unknown))

        // The same id, now under a type the map does know. If the call above had consumed the id,
        // insertEventIfAbsent would refuse this one as a duplicate and the row would stay
        // TRIALING.
        val known = freemiusEvent(
            "evt-shared-id",
            "subscription.created",
            periodEndsAt = now + 30 * DAY_MILLIS,
            occurredAt = now + 2,
        )

        val applied = service.apply(known)

        assertEquals(true, applied)
        assertEquals(SubscriptionStatus.ACTIVE, repository.find("u1")?.status)
    }

    // ------------------------------------------------------------------ apply: idempotency and ordering

    @Test
    fun `a replayed event id changes nothing`(): Unit = runTest {
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.TRIALING,
                trialEndsAtEpochMillis = now + 7 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val event =
            freemiusEvent("evt-replay", "subscription.created", periodEndsAt = now + 30 * DAY_MILLIS, occurredAt = now + 1)
        assertEquals(true, service.apply(event))
        val afterFirst = repository.find("u1")

        val repeated = service.apply(event)

        assertEquals(false, repeated)
        assertEquals(afterFirst, repository.find("u1"), "a replay must not change the stored row")
    }

    @Test
    fun `an event older than the stored state is dropped`(): Unit = runTest {
        val stored = Subscription(
            userId = "u1",
            status = SubscriptionStatus.ACTIVE,
            currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now + 100,
        )
        repository.upsert(stored)
        val stale = freemiusEvent("evt-stale", "subscription.renewal.failed", occurredAt = now + 50)

        val applied = service.apply(stale)

        assertEquals(false, applied)
        assertEquals(stored, repository.find("u1"), "a stale event must not overwrite a newer row")
    }

    @Test
    fun `a newer event overwrites`(): Unit = runTest {
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now + 100,
            )
        )
        val newPeriodEnd = now + 60 * DAY_MILLIS
        val fresh = freemiusEvent("evt-fresh", "subscription.created", periodEndsAt = newPeriodEnd, occurredAt = now + 200)

        val applied = service.apply(fresh)

        assertEquals(true, applied)
        assertEquals(newPeriodEnd, repository.find("u1")?.currentPeriodEndsAtEpochMillis)
    }

    // ------------------------------------------------------------------ apply: the user reference

    @Test
    fun `an event for a user with no row changes nothing`(): Unit = runTest {
        val event = freemiusEvent(
            "evt-no-user",
            "subscription.created",
            userReference = "no-such-user",
            periodEndsAt = now + 30 * DAY_MILLIS,
        )

        val applied = service.apply(event)

        assertEquals(false, applied)
        assertEquals(null, repository.find("no-such-user"))
    }

    @Test
    fun `an event with no userReference changes nothing`(): Unit = runTest {
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val before = repository.find("u1")
        val event = freemiusEvent(
            "evt-no-ref",
            "subscription.created",
            userReference = null,
            periodEndsAt = now + 60 * DAY_MILLIS,
        )

        val applied = service.apply(event)

        assertEquals(false, applied)
        assertEquals(before, repository.find("u1"))
    }
}
