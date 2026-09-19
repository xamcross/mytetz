package com.mytetz.catalog

import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers [Topic.reviewedAt] and the two places it must survive: a document stored before the
 * field existed, and a catalogue re-seed through [TopicRepository.upsertPreservingStatus].
 *
 * `CatalogServiceTest` already covers `upsertPreservingStatus`'s `status`-preserving behaviour, so
 * this file adds only the `reviewedAt` case, on the same [MongoTestSupport] container.
 */
class TopicRepositoryTest {

    private val database = MongoTestSupport.database("topic_repository")
    private val repository = TopicRepository(database)

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Topic>("topics").drop()
        repository.ensureIndexes()
    }

    @Test
    fun `a topic stored before reviewedAt existed decodes with reviewedAt null`() = runTest {
        database.getCollection<org.bson.Document>("topics").insertOne(
            org.bson.Document(
                mapOf(
                    "_id" to "legacy-topic", "title" to "Legacy", "category" to "Physics",
                    "summary" to "s", "aliases" to emptyList<String>(),
                    "status" to "PUBLISHED", "sortWeight" to 0,
                )
            )
        )

        val found = repository.findBySlug("legacy-topic")

        assertNotNull(found)
        assertNull(found.reviewedAt)
    }

    @Test
    fun `setReviewedAt stores the epoch value and findBySlug reads it back`() = runTest {
        repository.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))

        repository.setReviewedAt("t1", 1_700_000_000_000L)

        assertEquals(1_700_000_000_000L, repository.findBySlug("t1")?.reviewedAt)
    }

    @Test
    fun `upsertPreservingStatus does not erase an existing reviewedAt`() = runTest {
        // The regression this test guards against: CatalogService.seedFromResource calls
        // upsertPreservingStatus on every boot, with a Topic freshly decoded from topics.json,
        // whose reviewedAt is always null. A boot must not erase a curator's review date.
        repository.upsert(Topic(slug = "t2", title = "T2", category = "Physics", summary = "s"))
        repository.setReviewedAt("t2", 1_700_000_000_000L)

        val fromSeedFile = Topic(slug = "t2", title = "T2 (refreshed summary)", category = "Physics", summary = "s2")
        repository.upsertPreservingStatus(fromSeedFile)

        val stored = repository.findBySlug("t2")
        assertEquals("T2 (refreshed summary)", stored?.title, "the content refresh itself must still apply")
        assertEquals(1_700_000_000_000L, stored?.reviewedAt, "a boot erased an existing review date")
    }
}
