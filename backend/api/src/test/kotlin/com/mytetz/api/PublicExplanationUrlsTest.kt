package com.mytetz.api

import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.MAX_PUBLISHED_EXPLANATIONS
import com.mytetz.graph.Verb
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 4.6's own piece that does not need `SitemapRoutes.kt` (issue #46, not yet on `main` as this
 * task ships): the list of public explanation URLs, capped hard at [MAX_PUBLISHED_EXPLANATIONS],
 * so that a future `sitemapRoutes` can add one `<url>` per entry with no further cap logic of its
 * own. See `publicExplanationSitemapEntries`'s own KDoc for why the cap is enforced here too, and
 * not only in the review script.
 */
class PublicExplanationUrlsTest {

    private fun explanation(key: String, requestCount: Long) = Explanation(
        key = key, topicSlug = "quantum-physics", parentKey = "p", span = "span-$key", spanSentence = "s",
        verb = Verb.EXPLAIN, variant = 0, depth = 1, body = "b", grounded = false, sources = emptyList(),
        promptVersion = "v1", modelFamily = "fake-model", modelId = "fake-model",
        inputTokens = 1, outputTokens = 1, costMicros = 0, requestCount = requestCount,
        createdAtEpochMillis = 0, published = true,
    )

    @Test
    fun `an empty store gives an empty list`() = runTest {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_public_urls_empty_${System.nanoTime()}")).database,
        )

        assertEquals(emptyList(), publicExplanationSitemapEntries(explanations))
    }

    @Test
    fun `one published node gives one URL, under topics slash explain`() = runTest {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_public_urls_one_${System.nanoTime()}")).database,
        )
        explanations.insertIfAbsent(explanation("abcdef0123456789" + "0".repeat(48), requestCount = 1))

        val entries = publicExplanationSitemapEntries(explanations)

        assertEquals(1, entries.size)
        assertEquals("/topics/quantum-physics/explain/abcdef012345", entries.single().path)
    }

    @Test
    fun `exactly at the cap, every one of the 100 published nodes appears`() = runTest {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_public_urls_at_cap_${System.nanoTime()}")).database,
        )
        repeat(MAX_PUBLISHED_EXPLANATIONS) { i ->
            explanations.insertIfAbsent(explanation("k-$i", requestCount = i.toLong()))
        }

        val entries = publicExplanationSitemapEntries(explanations)

        assertEquals(MAX_PUBLISHED_EXPLANATIONS, entries.size)
    }

    @Test
    fun `one past the cap, the list still holds no more than the cap`() = runTest {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_public_urls_over_cap_${System.nanoTime()}")).database,
        )
        repeat(MAX_PUBLISHED_EXPLANATIONS + 1) { i ->
            explanations.insertIfAbsent(explanation("k-$i", requestCount = i.toLong()))
        }

        val entries = publicExplanationSitemapEntries(explanations)

        assertEquals(MAX_PUBLISHED_EXPLANATIONS, entries.size, "the 101st published node must not appear")
    }

    @Test
    fun `over the cap, the highest-demand nodes are kept, not an arbitrary subset`() = runTest {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_public_urls_priority_${System.nanoTime()}")).database,
        )
        repeat(MAX_PUBLISHED_EXPLANATIONS + 1) { i ->
            // i = 0 has the lowest requestCount, and must be the one entry left out.
            explanations.insertIfAbsent(explanation("k-$i", requestCount = i.toLong()))
        }

        val entries = publicExplanationSitemapEntries(explanations)

        assertTrue(entries.none { it.path.endsWith(explanation("k-0", 0).key.take(12)) })
    }

    @Test
    fun `a custom, smaller cap is honoured too`() = runTest {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_public_urls_custom_cap_${System.nanoTime()}")).database,
        )
        repeat(5) { i -> explanations.insertIfAbsent(explanation("k-$i", requestCount = i.toLong())) }

        assertEquals(2, publicExplanationSitemapEntries(explanations, cap = 2).size)
    }
}
