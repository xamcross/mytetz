package com.mytetz.graph.scripts

import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.ContentKey
import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.GraphConfig
import com.mytetz.graph.MongoTestSupport
import com.mytetz.graph.Verb
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MODEL_FAMILY = "fake-model"

/**
 * Issue #47's owner command. Every test runs against a real Mongo container, through
 * [MongoTestSupport]. No test connects to a live database. `PublishTopExplanationsTest` follows
 * the same safety rule in this module.
 */
class SetTopicReviewDateTest {

    private val database = MongoTestSupport.database("set_topic_review_date")
    private val topics = TopicRepository(database)
    private val explanations = ExplanationRepository(database)

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Topic>("topics").drop()
        database.getCollection<Explanation>("explanations").drop()
        topics.ensureIndexes()
        explanations.ensureIndexes()
    }

    private suspend fun seed(slug: String, createdAtEpochMillis: Long) {
        val key = ContentKey.seed(slug, GraphConfig().promptVersion, MODEL_FAMILY)
        explanations.insertIfAbsent(
            Explanation(
                key = key, topicSlug = slug, parentKey = null, span = null, spanSentence = null,
                verb = Verb.SEED, variant = 0, depth = 0, body = "seed body",
                grounded = false, sources = emptyList(), promptVersion = GraphConfig().promptVersion,
                modelFamily = MODEL_FAMILY, modelId = "fake-model", inputTokens = 1, outputTokens = 1,
                costMicros = 0, requestCount = 0, createdAtEpochMillis = createdAtEpochMillis,
            )
        )
    }

    // ------------------------------------------------------------------ setReviewedAt

    @Test
    fun `setReviewedAt writes the date for exactly the named topics, and changes no other field`() = runTest {
        topics.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s1"))
        topics.upsert(Topic(slug = "t2", title = "T2", category = "Physics", summary = "s2"))

        val outcome = setReviewedAt(topics, explanations, listOf("t1"), "2026-03-01", MODEL_FAMILY)

        assertIs<ReviewDateOutcome.Applied>(outcome)
        val stored = topics.findBySlug("t1")
        assertEquals("T1", stored?.title, "a write must not touch any other field")
        assertEquals("s1", stored?.summary)
        assertEquals(
            LocalDate.parse("2026-03-01").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            stored?.reviewedAt,
        )
        assertNull(topics.findBySlug("t2")?.reviewedAt, "a topic not named must not be touched")
    }

    @Test
    fun `an unknown slug writes nothing, for any named topic, and names the bad slug`() = runTest {
        topics.upsert(Topic(slug = "good", title = "Good", category = "Physics", summary = "s"))

        val outcome = setReviewedAt(topics, explanations, listOf("good", "no-such-topic"), "2026-03-01", MODEL_FAMILY)

        assertIs<ReviewDateOutcome.Rejected>(outcome)
        assertTrue((outcome as ReviewDateOutcome.Rejected).problems.any { "no-such-topic" in it })
        assertNull(topics.findBySlug("good")?.reviewedAt, "a good slug must not be written when another slug fails")
    }

    @Test
    fun `an unpublished topic writes nothing, and names the slug`() = runTest {
        topics.upsert(
            Topic(slug = "draft-topic", title = "Draft", category = "Physics", summary = "s", status = TopicStatus.DRAFT)
        )

        val outcome = setReviewedAt(topics, explanations, listOf("draft-topic"), "2026-03-01", MODEL_FAMILY)

        assertIs<ReviewDateOutcome.Rejected>(outcome)
        assertTrue((outcome as ReviewDateOutcome.Rejected).problems.any { "draft-topic" in it })
    }

    @Test
    fun `a badly formed date writes nothing, and names the bad value`() = runTest {
        topics.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))

        val outcome = setReviewedAt(topics, explanations, listOf("t1"), "01-03-2026", MODEL_FAMILY)

        assertIs<ReviewDateOutcome.Rejected>(outcome)
        assertTrue((outcome as ReviewDateOutcome.Rejected).problems.any { "01-03-2026" in it })
        assertNull(topics.findBySlug("t1")?.reviewedAt)
    }

    @Test
    fun `a future date writes nothing, and names the bad value`() = runTest {
        topics.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))

        val outcome = setReviewedAt(
            topics, explanations, listOf("t1"), "2026-09-21", MODEL_FAMILY,
            today = LocalDate.parse("2026-09-20"),
        )

        assertIs<ReviewDateOutcome.Rejected>(outcome)
        assertTrue((outcome as ReviewDateOutcome.Rejected).problems.any { "2026-09-21" in it })
        assertNull(topics.findBySlug("t1")?.reviewedAt)
    }

    @Test
    fun `today itself is not a future date`() = runTest {
        topics.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))

        val outcome = setReviewedAt(
            topics, explanations, listOf("t1"), "2026-09-20", MODEL_FAMILY,
            today = LocalDate.parse("2026-09-20"),
        )

        assertIs<ReviewDateOutcome.Applied>(outcome)
    }

    @Test
    fun `a date before the seed's own creation date writes nothing, and names the bad value`() = runTest {
        topics.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))
        seed("t1", createdAtEpochMillis = LocalDate.parse("2026-06-01").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())

        val outcome = setReviewedAt(topics, explanations, listOf("t1"), "2026-01-01", MODEL_FAMILY)

        assertIs<ReviewDateOutcome.Rejected>(outcome)
        assertTrue((outcome as ReviewDateOutcome.Rejected).problems.any { "t1" in it && "2026-06-01" in it })
        assertNull(topics.findBySlug("t1")?.reviewedAt)
    }

    @Test
    fun `a topic with no stored seed skips the seed-date check`() = runTest {
        topics.upsert(Topic(slug = "no-seed", title = "No Seed", category = "Physics", summary = "s"))

        val outcome = setReviewedAt(topics, explanations, listOf("no-seed"), "2020-01-01", MODEL_FAMILY)

        assertIs<ReviewDateOutcome.Applied>(outcome)
    }

    @Test
    fun `a second write with the same value is safe and applies again`() = runTest {
        topics.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))
        setReviewedAt(topics, explanations, listOf("t1"), "2026-03-01", MODEL_FAMILY)

        val outcome = setReviewedAt(topics, explanations, listOf("t1"), "2026-03-01", MODEL_FAMILY)

        assertIs<ReviewDateOutcome.Applied>(outcome)
        assertEquals("2026-03-01", topics.findBySlug("t1")?.reviewedAt?.let { com.mytetz.graph.scripts.isoDate(it) })
    }

    @Test
    fun `no slug given is rejected, and writes nothing`() = runTest {
        val outcome = setReviewedAt(topics, explanations, emptyList(), "2026-03-01", MODEL_FAMILY)

        assertIs<ReviewDateOutcome.Rejected>(outcome)
    }

    @Test
    fun `Applied names the old and the new date for each changed slug`() = runTest {
        topics.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))
        topics.setReviewedAt("t1", LocalDate.parse("2026-01-01").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())

        val outcome = setReviewedAt(topics, explanations, listOf("t1"), "2026-03-01", MODEL_FAMILY)

        assertIs<ReviewDateOutcome.Applied>(outcome)
        val change = (outcome as ReviewDateOutcome.Applied).changes.single()
        assertEquals("t1", change.slug)
        assertEquals("2026-01-01", change.oldDate)
        assertEquals("2026-03-01", change.newDate)
    }

    // ------------------------------------------------------------------ clearReviewedAt

    @Test
    fun `clearReviewedAt clears the date for exactly the named topics`() = runTest {
        topics.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))
        setReviewedAt(topics, explanations, listOf("t1"), "2026-03-01", MODEL_FAMILY)

        val outcome = clearReviewedAt(topics, listOf("t1"))

        assertIs<ReviewDateOutcome.Applied>(outcome)
        assertNull(topics.findBySlug("t1")?.reviewedAt)
    }

    @Test
    fun `clearReviewedAt with an unknown slug writes nothing`() = runTest {
        topics.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))
        setReviewedAt(topics, explanations, listOf("t1"), "2026-03-01", MODEL_FAMILY)

        val outcome = clearReviewedAt(topics, listOf("t1", "no-such-topic"))

        assertIs<ReviewDateOutcome.Rejected>(outcome)
        assertTrue(topics.findBySlug("t1")?.reviewedAt != null, "a good slug must not be cleared when another slug fails")
    }

    @Test
    fun `clearReviewedAt with no slug given is rejected`() = runTest {
        val outcome = clearReviewedAt(topics, emptyList())

        assertIs<ReviewDateOutcome.Rejected>(outcome)
    }

    // ------------------------------------------------------------------ listReviewRows (the dry run)

    @Test
    fun `listReviewRows with no slug lists every published topic, with its date or none`() = runTest {
        topics.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))
        topics.upsert(Topic(slug = "t2", title = "T2", category = "Physics", summary = "s"))
        topics.upsert(Topic(slug = "draft", title = "Draft", category = "Physics", summary = "s", status = TopicStatus.DRAFT))
        setReviewedAt(topics, explanations, listOf("t1"), "2026-03-01", MODEL_FAMILY)

        val rows = listReviewRows(topics, slugs = null)

        assertEquals(setOf("t1", "t2"), rows.map { it.slug }.toSet(), "a draft topic must not appear")
        assertEquals("2026-03-01", rows.single { it.slug == "t1" }.reviewedAt)
        assertEquals("none", rows.single { it.slug == "t2" }.reviewedAt ?: "none")
        assertEquals("/topics/t1", rows.single { it.slug == "t1" }.path)
    }

    @Test
    fun `listReviewRows names a slug that does not exist`() = runTest {
        val rows = listReviewRows(topics, slugs = listOf("no-such-topic"))

        assertEquals("no-such-topic", rows.single().slug)
        assertTrue(rows.single().note!!.contains("unknown"))
    }

    // ------------------------------------------------------------------ argument parsing

    @Test
    fun `no flags means list, with no slugs`() {
        val command = parseReviewDateArgs(emptyList()) { "" }

        assertIs<ReviewDateCommand.ListReviewed>(command)
        assertNull((command as ReviewDateCommand.ListReviewed).slugs)
    }

    @Test
    fun `--set-reviewed needs --slugs and --date`() {
        val command = parseReviewDateArgs(listOf("--set-reviewed"), { "" })

        assertIs<ReviewDateCommand.InvalidArgs>(command)
    }

    @Test
    fun `--set-reviewed with --slugs and --date parses`() {
        val command = parseReviewDateArgs(listOf("--set-reviewed", "--slugs", "a,b", "--date", "2026-03-01"), { "" })

        assertIs<ReviewDateCommand.SetReviewed>(command)
        assertEquals(listOf("a", "b"), (command as ReviewDateCommand.SetReviewed).slugs)
        assertEquals("2026-03-01", command.date)
    }

    @Test
    fun `--clear-reviewed with --slugs-file reads the file`() {
        val command = parseReviewDateArgs(listOf("--clear-reviewed", "--slugs-file", "slugs.txt")) { "a\nb\n" }

        assertIs<ReviewDateCommand.ClearReviewed>(command)
        assertEquals(listOf("a", "b"), (command as ReviewDateCommand.ClearReviewed).slugs)
    }

    @Test
    fun `--set-reviewed and --clear-reviewed together is an error`() {
        val command = parseReviewDateArgs(listOf("--set-reviewed", "--clear-reviewed", "--slugs", "a", "--date", "2026-03-01"), { "" })

        assertIs<ReviewDateCommand.InvalidArgs>(command)
    }

    @Test
    fun `parseSlugsArg splits on a comma and trims each slug`() {
        assertEquals(listOf("a", "b", "c"), parseSlugsArg(" a, b ,c"))
    }

    @Test
    fun `parseSlugsFileText reads one slug per line and drops a blank line`() {
        assertEquals(listOf("a", "b"), parseSlugsFileText("a\n\n b \n"))
    }
}
