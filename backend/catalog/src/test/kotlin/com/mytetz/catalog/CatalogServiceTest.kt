package com.mytetz.catalog

import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.test.runTest
import org.testcontainers.containers.MongoDBContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogServiceTest {

    companion object {
        private val container = MongoDBContainer("mongo:7").apply { start() }
        private val client = MongoClient.create(container.connectionString)
    }

    private val database = client.getDatabase("test_catalog")
    private val repository = TopicRepository(database)
    private val service = CatalogService(repository)

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Topic>("topics").drop()
        repository.ensureIndexes()
    }

    @Test
    fun `seeding loads at least twenty published topics`() = runTest {
        service.seedFromResource()

        val topics = service.listPublished(category = null, query = null)

        assertTrue(topics.size >= 20, "expected >= 20 seeded topics, got ${topics.size}")
        assertTrue(topics.all { it.status == TopicStatus.PUBLISHED })
    }

    @Test
    fun `seeding twice does not duplicate`() = runTest {
        service.seedFromResource()
        val first = service.listPublished(null, null)

        service.seedFromResource()

        val second = service.listPublished(null, null)
        assertEquals(first.size, second.size)
        // Idempotence is more than "the row count did not grow". A re-seed must leave the
        // catalogue in an identical state, so an upsert that dropped a field, reordered
        // aliases or resurrected a DRAFT row would be caught here and not by a count alone.
        assertEquals(first, second)
    }

    @Test
    fun `quantum physics is present and findable by slug`() = runTest {
        service.seedFromResource()

        val topic = service.findBySlug("quantum-physics")

        assertEquals("Quantum Physics", topic?.title)
    }

    @Test
    fun `search matches title case-insensitively`() = runTest {
        service.seedFromResource()

        val results = service.listPublished(category = null, query = "quantum")

        assertTrue(results.any { it.slug == "quantum-physics" })
    }

    @Test
    fun `search treats regex metacharacters literally`() = runTest {
        service.seedFromResource()

        // The query reaches this filter straight from a URL parameter. Interpolated raw, ".*"
        // would match every topic in the catalogue and a nested quantifier could pin a CPU.
        // TopicRepository quotes it, so it can only match a topic containing the literal ".*".
        val results = service.listPublished(category = null, query = ".*")

        assertTrue(results.isEmpty(), "regex metacharacters leaked into the filter: matched ${results.size}")
    }

    @Test
    fun `draft topics are hidden from browsing and from search`() = runTest {
        service.seedFromResource()
        val draft = Topic(
            slug = "unreviewed-draft-topic",
            title = "Unreviewed Draft Topic",
            category = "Physics",
            summary = "Not yet reviewed, and must never reach a reader.",
            aliases = listOf("unreviewed alias"),
            // sortWeight 1 puts it ahead of every seeded topic, so a leak shows up first, not last.
            sortWeight = 1,
            status = TopicStatus.DRAFT,
        )
        repository.upsert(draft)

        // The row really is stored — findBySlug deliberately does not filter on status — so any
        // absence below is the status filter doing its job and not an upsert that silently failed.
        assertEquals(TopicStatus.DRAFT, repository.findBySlug(draft.slug)?.status)

        val browsed = service.listPublished(category = null, query = null)
        assertTrue(browsed.none { it.slug == draft.slug }, "a DRAFT topic leaked into browsing")
        assertTrue(browsed.all { it.status == TopicStatus.PUBLISHED })

        // Search must not be a back door: "unreviewed" appears in no published topic, so without
        // the status filter this query returns the draft and nothing else.
        val searched = service.listPublished(category = null, query = "unreviewed")
        assertTrue(searched.none { it.slug == draft.slug }, "a DRAFT topic leaked into search results")
    }

    @Test
    fun `unknown slug returns null`() = runTest {
        service.seedFromResource()

        assertNull(service.findBySlug("not-a-real-topic"))
    }
}
