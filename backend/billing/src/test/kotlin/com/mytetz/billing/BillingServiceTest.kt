package com.mytetz.billing

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.bson.Document
import org.slf4j.LoggerFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        freemiusUserId: String? = "fs-user-1",
        freemiusSubscriptionId: String? = "fs-sub-1",
    ) = FreemiusEvent(
        id = id,
        type = type,
        userReference = userReference,
        freemiusUserId = freemiusUserId,
        freemiusSubscriptionId = freemiusSubscriptionId,
        periodEndsAtEpochMillis = periodEndsAt,
        occurredAtEpochMillis = occurredAt,
    )

    /**
     * Captures what [BillingService] logs.
     *
     * [BillingService] names an operator alert token on every refusal. A rename of a token removes
     * that operator's alert, and no other assertion in this file sees the change. These two helpers
     * copy the technique of `ErrorMappingTest` in `:backend:api`.
     */
    private fun attachAppender(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger(BillingService::class.java) as ch.qos.logback.classic.Logger).addAppender(appender)
        return appender
    }

    private fun detachAppender(appender: ListAppender<ILoggingEvent>) {
        (LoggerFactory.getLogger(BillingService::class.java) as ch.qos.logback.classic.Logger)
            .detachAppender(appender)
    }

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
    fun `an active event with no period end keeps the stored one`(): Unit = runTest {
        // subscription.renewal.retry is a retry attempt. The vendor does not document whether it
        // carries a period end. An event with no date must never delete the date the row holds,
        // because Entitlement.resolveActive reads a null date on ACTIVE as a permanent allowance.
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
        val event = freemiusEvent("evt-retry-no-end", "subscription.renewal.retry", periodEndsAt = null, occurredAt = now + 1)

        val applied = service.apply(event)

        assertEquals(true, applied)
        assertEquals(
            periodEnd,
            repository.find("u1")?.currentPeriodEndsAtEpochMillis,
            "an event with no period end must keep the stored date",
        )
        // The stored date must still end the allowance. A null date here allows for ever.
        now = periodEnd + config.graceDays * DAY_MILLIS
        assertEquals(
            EntitlementDecision.SubscriptionRequired,
            service.entitlementFor("u1"),
            "the resolver must refuse once the stored period end and the grace have passed",
        )
    }

    @Test
    fun `an applied event stores the vendor ids`(): Unit = runTest {
        // Task 12's reconciliation asks Freemius about each row. Without a stored vendor id it has
        // nothing to ask about.
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.TRIALING,
                trialEndsAtEpochMillis = now + 7 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val event = freemiusEvent(
            "evt-vendor-ids",
            "subscription.created",
            periodEndsAt = now + 30 * DAY_MILLIS,
            occurredAt = now + 1,
        )

        val applied = service.apply(event)

        assertEquals(true, applied)
        val stored = repository.find("u1")
        assertEquals("fs-user-1", stored?.freemiusUserId)
        assertEquals("fs-sub-1", stored?.freemiusSubscriptionId)
    }

    @Test
    fun `an applied event keeps the stored vendor ids when it carries none`(): Unit = runTest {
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
                freemiusUserId = "fs-user-1",
                freemiusSubscriptionId = "fs-sub-1",
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val event = freemiusEvent(
            "evt-no-vendor-ids",
            "subscription.cancelled",
            occurredAt = now + 1,
            freemiusUserId = null,
            freemiusSubscriptionId = null,
        )

        val applied = service.apply(event)

        assertEquals(true, applied)
        val stored = repository.find("u1")
        assertEquals("fs-user-1", stored?.freemiusUserId, "an event with no vendor id must keep the stored one")
        assertEquals("fs-sub-1", stored?.freemiusSubscriptionId, "an event with no vendor id must keep the stored one")
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

        // The replay carries the same id and different content: another status mapping, and a
        // later stamp. A byte-identical replay writes a byte-identical row, so the row assertion
        // below would hold even if the id check did nothing at all.
        val repeat = freemiusEvent("evt-replay", "subscription.cancelled", occurredAt = now + 2)
        val repeated = service.apply(repeat)

        assertEquals(false, repeated)
        assertEquals(afterFirst, repository.find("u1"), "a replay must not change the stored row")
        assertEquals(
            SubscriptionStatus.ACTIVE,
            repository.find("u1")?.status,
            "a replayed id must not move the row to the replay's own status",
        )
    }

    @Test
    fun `an event older than the stored state is dropped`(): Unit = runTest {
        val stored = Subscription(
            userId = "u1",
            status = SubscriptionStatus.ACTIVE,
            currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now + 100,
            // The guard reads this field, and only apply writes it. A row whose last event landed
            // at now + 100 is what makes the event below a late one.
            lastEventAtEpochMillis = now + 100,
        )
        repository.upsert(stored)
        val stale = freemiusEvent("evt-stale", "subscription.renewal.failed", occurredAt = now + 50)

        val applied = service.apply(stale)

        assertEquals(false, applied)
        assertEquals(stored, repository.find("u1"), "a stale event must not overwrite a newer row")
    }

    @Test
    fun `a first event is never dropped for being older than the row`(): Unit = runTest {
        // The row's own timestamps come from the server clock. The event's stamp comes from the
        // vendor. A vendor stamp one second behind the row is ordinary clock skew, and a first
        // payment must still land. A wrong unit guess on the vendor's `created` field would
        // otherwise drop every event for this row, for ever.
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
        val event = freemiusEvent(
            "evt-skewed",
            "subscription.created",
            periodEndsAt = periodEnd,
            occurredAt = now - 1_000,
        )

        val applied = service.apply(event)

        assertEquals(true, applied, "a first event must never be dropped for being older than the row")
        val stored = repository.find("u1")
        assertEquals(SubscriptionStatus.ACTIVE, stored?.status)
        assertEquals(periodEnd, stored?.currentPeriodEndsAtEpochMillis)
        assertEquals(now - 1_000, stored?.lastEventAtEpochMillis)
    }

    @Test
    fun `an event at the same millisecond as the stored state is applied`(): Unit = runTest {
        // The rule is strict: an event is late only when it is older than the last event. A first
        // payment that shares a millisecond with the last event must land.
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now + 100,
                lastEventAtEpochMillis = now + 100,
            )
        )
        val event = freemiusEvent("evt-same-ms", "subscription.cancelled", occurredAt = now + 100)

        val applied = service.apply(event)

        assertEquals(true, applied, "an event at the same millisecond must be applied")
        assertEquals(SubscriptionStatus.CANCELLED, repository.find("u1")?.status)
    }

    @Test
    fun `a dropped event is logged`(): Unit = runTest {
        // A silent drop of a paid event is the failure this system can least afford to hide.
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEndsAtEpochMillis = now + 30 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now + 100,
                lastEventAtEpochMillis = now + 100,
            )
        )
        val stale = freemiusEvent("evt-stale-logged", "subscription.renewal.failed", occurredAt = now + 50)
        val appender = attachAppender()

        val applied = try {
            service.apply(stale)
        } finally {
            detachAppender(appender)
        }

        assertEquals(false, applied)
        val logged = assertNotNull(
            appender.list.firstOrNull { it.formattedMessage.contains("BILLING_STALE_EVENT") },
            "a dropped event was not logged: ${appender.list.map { it.formattedMessage }}",
        )
        assertTrue(logged.formattedMessage.contains("evt-stale-logged"), "the log line must name the event id")
        assertTrue(
            logged.formattedMessage.contains("subscription.renewal.failed"),
            "the log line must name the event type",
        )
        assertTrue(logged.formattedMessage.contains((now + 50).toString()), "the log line must carry the event stamp")
        assertTrue(logged.formattedMessage.contains((now + 100).toString()), "the log line must carry the stored stamp")
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
                lastEventAtEpochMillis = now + 100,
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

    @Test
    fun `an event for an unknown user does not consume the event id`(): Unit = runTest {
        // A missing row can be a transient state: the webhook can beat the learner's own sign-in.
        // The vendor must be able to resend the very same event, so neither user-reference check
        // may consume the id. This is the reasoning the unknown-type check already follows.
        repository.upsert(
            Subscription(
                userId = "u1",
                status = SubscriptionStatus.TRIALING,
                trialEndsAtEpochMillis = now + 7 * DAY_MILLIS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        val noRow = freemiusEvent(
            "evt-shared-id-row",
            "subscription.created",
            userReference = "no-such-user",
            periodEndsAt = now + 30 * DAY_MILLIS,
            occurredAt = now + 1,
        )
        assertEquals(false, service.apply(noRow))
        val noReference = freemiusEvent(
            "evt-shared-id-ref",
            "subscription.created",
            userReference = null,
            periodEndsAt = now + 30 * DAY_MILLIS,
            occurredAt = now + 1,
        )
        assertEquals(false, service.apply(noReference))

        // The same two ids, now under a user who has a row. A consumed id would make
        // insertEventIfAbsent refuse both of these as duplicates, and the row would stay TRIALING.
        val periodEnd = now + 60 * DAY_MILLIS
        val resendOfNoRow =
            freemiusEvent("evt-shared-id-row", "subscription.created", periodEndsAt = periodEnd, occurredAt = now + 2)
        val resendOfNoReference =
            freemiusEvent("evt-shared-id-ref", "subscription.cancelled", occurredAt = now + 3)

        assertEquals(true, service.apply(resendOfNoRow), "an event naming no stored row must not consume its id")
        assertEquals(SubscriptionStatus.ACTIVE, repository.find("u1")?.status)
        assertEquals(true, service.apply(resendOfNoReference), "an event with no userReference must not consume its id")
        assertEquals(SubscriptionStatus.CANCELLED, repository.find("u1")?.status)
    }

    // ------------------------------------------------------------------ apply: the operator alerts
    //
    // One test for each alert token this class logs. A rename of a token removes an operator's
    // alert, and no other assertion in this file reads the log at all.

    @Test
    fun `an unknown event type raises the operator alert`(): Unit = runTest {
        val event = freemiusEvent("evt-alert-unknown-type", "subscription.something_new", occurredAt = now + 1)
        val appender = attachAppender()

        val applied = try {
            service.apply(event)
        } finally {
            detachAppender(appender)
        }

        assertEquals(false, applied)
        val logged = assertNotNull(
            appender.list.firstOrNull { it.formattedMessage.contains("BILLING_UNKNOWN_EVENT") },
            "an unknown type was not logged: ${appender.list.map { it.formattedMessage }}",
        )
        assertTrue(logged.formattedMessage.contains("subscription.something_new"))
        assertTrue(logged.formattedMessage.contains("evt-alert-unknown-type"))
    }

    @Test
    fun `an event this service cannot place on a user raises the operator alert`(): Unit = runTest {
        val noReference =
            freemiusEvent("evt-alert-no-ref", "subscription.created", userReference = null, occurredAt = now + 1)
        val noRow = freemiusEvent(
            "evt-alert-no-row",
            "subscription.created",
            userReference = "no-such-user",
            occurredAt = now + 1,
        )
        val appender = attachAppender()

        try {
            assertEquals(false, service.apply(noReference))
            assertEquals(false, service.apply(noRow))
        } finally {
            detachAppender(appender)
        }

        val lines = appender.list.filter { it.formattedMessage.contains("BILLING_UNKNOWN_USER") }
        assertEquals(2, lines.size, "both user checks must log: ${appender.list.map { it.formattedMessage }}")
        assertTrue(lines.any { it.formattedMessage.contains("evt-alert-no-ref") })
        assertTrue(lines.any { it.formattedMessage.contains("evt-alert-no-row") })
    }
}
