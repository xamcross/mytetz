package com.mytetz.catalog

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogServiceTest {

    private val database = MongoTestSupport.database("catalog")
    private val repository = TopicRepository(database)
    private val service = CatalogService(repository)
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Topic>("topics").drop()
        repository.ensureIndexes()
    }

    /**
     * Reads `/topics.json` straight from the classpath, the same resource [CatalogService
     * .seedFromResource] reads. A structure test asks this file directly, and not the seeded
     * database, so a rule such as "no blank title" fails on the source of truth and not on a
     * side effect of the upsert.
     */
    private fun readTopicsResource(): List<Topic> {
        val text = requireNotNull(javaClass.getResourceAsStream("/topics.json")) {
            "catalogue seed resource /topics.json not found"
        }.bufferedReader().use { it.readText() }
        return json.decodeFromString(text)
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

    @Test
    fun `re-seeding does not republish a topic an operator unpublished`() = runTest {
        service.seedFromResource()
        val slug = "quantum-physics"
        // The only way to withdraw a topic in this slice: there is no admin API, so an operator
        // edits the row. Until Task 1.11 wired `seedFromResource` into `Components.bootstrap`,
        // seeding had no production caller at all and this could not happen.
        repository.upsert(requireNotNull(service.findBySlug(slug)).copy(status = TopicStatus.DRAFT))

        service.seedFromResource()

        // A plain replace-by-slug would take `status` straight back from topics.json, so the next
        // deploy — or the next scale-from-zero boot, which happens on any request after an idle
        // period — would silently put the withdrawn topic back in front of learners.
        assertEquals(TopicStatus.DRAFT, service.findBySlug(slug)?.status, "seeding republished a withdrawn topic")
        assertTrue(service.listPublished(null, null).none { it.slug == slug })
    }

    @Test
    fun `re-seeding still refreshes the content of a topic`() = runTest {
        service.seedFromResource()
        val slug = "quantum-physics"
        repository.upsert(requireNotNull(service.findBySlug(slug)).copy(title = "Stale Title", summary = "stale"))

        service.seedFromResource()

        // The other half of the trade. topics.json remains the source of truth for CONTENT; the
        // stored row is the source of truth only for PUBLICATION. Preserving everything would make
        // the seed file unable to fix a typo.
        assertEquals("Quantum Physics", service.findBySlug(slug)?.title)
        assertEquals(TopicStatus.PUBLISHED, service.findBySlug(slug)?.status)
    }

    /**
     * A structure test on `topics.json` itself, ahead of the catalogue growing past 29 topics.
     *
     * `seedFromResource` upserts by `_id`, so a duplicate slug replaces the first entry and raises
     * no error — see `seedFromResource creates one published topic for every entry`, below, for the
     * test that catches that specific case. This test covers every other structural rule the
     * catalogue must hold once the list grows: a url-safe slug, two to four aliases, no blank
     * field, and no category over the cap of 15.
     */
    @Test
    fun `topics dot json holds well-formed entries - unique slugs, url-safe ids, aliases, no blanks, category cap`() {
        val topics = readTopicsResource()
        val slugPattern = Regex("^[a-z0-9]+(-[a-z0-9]+)*\$")

        topics.forEach { topic ->
            assertTrue(slugPattern.matches(topic.slug), "'${topic.slug}' is not a url-safe slug")
            assertTrue(
                topic.aliases.size in 2..4,
                "'${topic.slug}' has ${topic.aliases.size} alias(es), the rule is 2 to 4",
            )
            assertTrue(topic.title.isNotBlank(), "'${topic.slug}' has a blank title")
            assertTrue(topic.category.isNotBlank(), "'${topic.slug}' has a blank category")
            assertTrue(topic.summary.isNotBlank(), "'${topic.slug}' has a blank summary")
        }

        val slugs = topics.map { it.slug }
        assertEquals(slugs.distinct().size, slugs.size, "a slug repeats in topics.json")

        topics.groupingBy { it.category }.eachCount().forEach { (category, count) ->
            assertTrue(count <= 15, "category '$category' holds $count topics, the cap is 15")
        }
    }

    /**
     * The one check `seedFromResource creates one published topic for every entry` above cannot
     * make on its own: that a duplicate `_id` in `topics.json` does not silently vanish behind the
     * upsert. `TopicRepository.upsertPreservingStatus` writes by `_id`, so two entries with the
     * same slug leave one row in the store, and a count alone would not show which half is missing.
     */
    @Test
    fun `seedFromResource creates one published topic for every entry in topics dot json, with no duplicate slug`() =
        runTest {
            val fileEntryCount = readTopicsResource().size

            service.seedFromResource()

            val published = service.listPublished(category = null, query = null)
            assertEquals(
                fileEntryCount,
                published.size,
                "the published count does not match the file; a duplicate slug may have replaced an entry",
            )
        }
}
