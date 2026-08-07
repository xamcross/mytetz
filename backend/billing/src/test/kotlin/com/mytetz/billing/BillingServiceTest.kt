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
        now = T0
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
}
