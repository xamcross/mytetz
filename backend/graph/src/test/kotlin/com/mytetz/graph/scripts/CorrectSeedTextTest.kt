package com.mytetz.graph.scripts

import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.ExplanationValidator
import com.mytetz.graph.MongoTestSupport
import com.mytetz.graph.Verb
import kotlinx.coroutines.test.runTest
import org.bson.Document
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every test here runs against a real Mongo container through [MongoTestSupport], the project's
 * own Testcontainers fixture — never against a live database. This is the safety proof issue
 * #162 asks for, the same rule `PublishTopExplanationsTest.kt` follows for issue #48.
 */
class CorrectSeedTextTest {

    private val database = MongoTestSupport.database("correct_seed_text")
    private val repository = ExplanationRepository(database)

    private fun seed(
        key: String = "seed-key",
        slug: String = "special-relativity",
        body: String = "Old seed text.",
        verb: Verb = Verb.SEED,
    ) = Explanation(
        key = key, topicSlug = slug, parentKey = null, span = null, spanSentence = null,
        verb = verb, variant = 0, depth = 0, body = body, grounded = false, sources = emptyList(),
        promptVersion = "v1", modelFamily = "fake-model", modelId = "fake-model",
        inputTokens = 1, outputTokens = 1, costMicros = 0, requestCount = 0,
        createdAtEpochMillis = 0,
    )

    private fun child(key: String, parentKey: String, spanSentence: String, slug: String = "special-relativity") =
        Explanation(
            key = key, topicSlug = slug, parentKey = parentKey, span = "a span", spanSentence = spanSentence,
            verb = Verb.EXPLAIN, variant = 0, depth = 1, body = "A child body.", grounded = false,
            sources = emptyList(), promptVersion = "v1", modelFamily = "fake-model", modelId = "fake-model",
            inputTokens = 1, outputTokens = 1, costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
        )

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Explanation>("explanations").drop()
        database.getCollection<Document>("sessions").drop()
        repository.ensureIndexes()
    }

    private suspend fun insertSession(sessionId: String, explanationKeys: List<String>) {
        val nodes = explanationKeys.mapIndexed { index, key ->
            Document(
                mapOf(
                    "nodeId" to "n$index",
                    "parentNodeId" to null,
                    "explanationKey" to key,
                    "span" to "",
                    "verb" to "EXPLAIN",
                    "variant" to 0,
                    "depth" to index,
                    "createdAtEpochMillis" to 0L,
                )
            )
        }
        database.getCollection<Document>("sessions").insertOne(
            Document(
                mapOf(
                    "_id" to sessionId,
                    "principalId" to "user:test",
                    "topicSlug" to "special-relativity",
                    "rootNodeId" to "n0",
                    "currentNodeId" to "n0",
                    "nodes" to nodes,
                    "startedAtEpochMillis" to 0L,
                    "lastActiveAtEpochMillis" to 0L,
                    "status" to "ACTIVE",
                )
            )
        )
    }

    // ------------------------------------------------------------------ parseCorrectionArgs

    @Test
    fun `parseCorrectionArgs with no slug and no key is invalid`() {
        val command = parseCorrectionArgs(listOf("--file", "x.txt"))

        assertTrue(command is SeedCorrectionCommand.InvalidArgs)
    }

    @Test
    fun `parseCorrectionArgs with both slug and key is invalid`() {
        val command = parseCorrectionArgs(listOf("--slug", "a", "--key", "b", "--file", "x.txt"))

        assertTrue(command is SeedCorrectionCommand.InvalidArgs)
    }

    @Test
    fun `parseCorrectionArgs with no --file and no --revert is invalid`() {
        val command = parseCorrectionArgs(listOf("--slug", "a"))

        assertTrue(command is SeedCorrectionCommand.InvalidArgs)
    }

    @Test
    fun `parseCorrectionArgs with --write and --revert together is invalid`() {
        val command = parseCorrectionArgs(listOf("--slug", "a", "--write", "--revert"))

        assertTrue(command is SeedCorrectionCommand.InvalidArgs)
    }

    @Test
    fun `parseCorrectionArgs with only --slug and --file is a dry run`() {
        val command = parseCorrectionArgs(listOf("--slug", "special-relativity", "--file", "x.txt"))

        assertTrue(command is SeedCorrectionCommand.DryRun)
        assertEquals(SeedSelector.BySlug("special-relativity"), (command as SeedCorrectionCommand.DryRun).selector)
        assertEquals("x.txt", command.newBodyFile)
    }

    @Test
    fun `parseCorrectionArgs with --write is a write`() {
        val command = parseCorrectionArgs(listOf("--slug", "special-relativity", "--file", "x.txt", "--write"))

        assertTrue(command is SeedCorrectionCommand.Write)
    }

    @Test
    fun `parseCorrectionArgs with --revert and --key needs no --file`() {
        val command = parseCorrectionArgs(listOf("--key", "abc", "--revert"))

        assertTrue(command is SeedCorrectionCommand.Revert)
        assertEquals(SeedSelector.ByKey("abc"), (command as SeedCorrectionCommand.Revert).selector)
    }

    // ------------------------------------------------------------------ resolveSeed

    @Test
    fun `resolveSeed by slug finds the one seed`() = runTest {
        repository.insertIfAbsent(seed(key = "sr-seed", slug = "special-relativity"))

        val lookup = resolveSeed(repository, SeedSelector.BySlug("special-relativity"))

        assertTrue(lookup is SeedLookup.Found)
        assertEquals("sr-seed", (lookup as SeedLookup.Found).explanation.key)
    }

    @Test
    fun `resolveSeed by an unknown slug is not found`() = runTest {
        val lookup = resolveSeed(repository, SeedSelector.BySlug("no-such-topic"))

        assertTrue(lookup is SeedLookup.NotFound)
    }

    @Test
    fun `resolveSeed by key on a document that is not a seed is not found`() = runTest {
        repository.insertIfAbsent(seed(key = "an-explain-node", verb = Verb.EXPLAIN))

        val lookup = resolveSeed(repository, SeedSelector.ByKey("an-explain-node"))

        assertTrue(lookup is SeedLookup.NotFound)
        assertTrue("not a seed" in (lookup as SeedLookup.NotFound).reason)
    }

    // ------------------------------------------------------------------ prepareReport (the dry run)

    @Test
    fun `prepareReport on an unknown slug refuses, and writes nothing`() = runTest {
        val outcome = prepareReport(repository, database, SeedSelector.BySlug("no-such-topic"), "New text.")

        assertTrue(outcome is ReportOutcome.Refused)
        assertTrue("unknown slug" in (outcome as ReportOutcome.Refused).reason)
    }

    @Test
    fun `prepareReport on a document that is not a seed refuses, and writes nothing`() = runTest {
        repository.insertIfAbsent(seed(key = "child-key", verb = Verb.EXPLAIN))

        val outcome = prepareReport(repository, database, SeedSelector.ByKey("child-key"), "New text.")

        assertTrue(outcome is ReportOutcome.Refused)
        assertTrue("not a seed" in (outcome as ReportOutcome.Refused).reason)
    }

    @Test
    fun `prepareReport refuses an empty new text, and writes nothing`() = runTest {
        repository.insertIfAbsent(seed())

        val outcome = prepareReport(repository, database, SeedSelector.BySlug("special-relativity"), "")

        assertTrue(outcome is ReportOutcome.Refused)
        assertTrue("empty" in (outcome as ReportOutcome.Refused).reason)
        assertEquals("Old seed text.", repository.findByKey("seed-key")?.body)
    }

    @Test
    fun `prepareReport refuses a new text with markup, and writes nothing`() = runTest {
        repository.insertIfAbsent(seed())

        val outcome = prepareReport(repository, database, SeedSelector.BySlug("special-relativity"), "Has a <tag> in it.")

        assertTrue(outcome is ReportOutcome.Refused)
        assertTrue("markup" in (outcome as ReportOutcome.Refused).reason)
        assertEquals("Old seed text.", repository.findByKey("seed-key")?.body)
    }

    @Test
    fun `prepareReport refuses a new text over the seed length limit, and writes nothing`() = runTest {
        repository.insertIfAbsent(seed())
        val tooLong = "a".repeat(ExplanationValidator.DEFAULT_MAX_CHARS + 1)

        val outcome = prepareReport(repository, database, SeedSelector.BySlug("special-relativity"), tooLong)

        assertTrue(outcome is ReportOutcome.Refused)
        assertTrue("limit" in (outcome as ReportOutcome.Refused).reason)
        assertEquals("Old seed text.", repository.findByKey("seed-key")?.body)
    }

    @Test
    fun `prepareReport refuses a new text equal to the present text, and writes nothing`() = runTest {
        repository.insertIfAbsent(seed(body = "Same text."))

        val outcome = prepareReport(repository, database, SeedSelector.BySlug("special-relativity"), "Same text.")

        assertTrue(outcome is ReportOutcome.Refused)
        assertTrue("unchanged" in (outcome as ReportOutcome.Refused).reason)
    }

    @Test
    fun `prepareReport writes nothing on a valid request — it only reads`() = runTest {
        repository.insertIfAbsent(seed(body = "Old text."))

        prepareReport(repository, database, SeedSelector.BySlug("special-relativity"), "New text.")

        assertEquals("Old text.", repository.findByKey("seed-key")?.body, "a dry run must never write")
    }

    @Test
    fun `prepareReport shows the present text and the new text`() = runTest {
        repository.insertIfAbsent(seed(body = "Old text."))

        val outcome = prepareReport(repository, database, SeedSelector.BySlug("special-relativity"), "New text.")

        assertTrue(outcome is ReportOutcome.Ready)
        val report = (outcome as ReportOutcome.Ready).report
        assertEquals("Old text.", report.oldText)
        assertEquals("New text.", report.newText)
    }

    @Test
    fun `prepareReport counts a child of a changed sentence`() = runTest {
        repository.insertIfAbsent(seed(body = "First sentence. Second sentence."))
        repository.insertIfAbsent(child("child-1", "seed-key", "First sentence."))
        repository.insertIfAbsent(child("child-2", "seed-key", "First sentence."))
        repository.insertIfAbsent(child("child-3", "seed-key", "Second sentence."))

        val outcome = prepareReport(
            repository, database, SeedSelector.BySlug("special-relativity"),
            "First sentence, corrected. Second sentence.",
        )

        val report = (outcome as ReportOutcome.Ready).report
        assertEquals(1, report.changedSentenceImpacts.size)
        assertEquals("First sentence.", report.changedSentenceImpacts.single().sentence)
        assertEquals(2, report.changedSentenceImpacts.single().childCount)
    }

    @Test
    fun `prepareReport counts zero sessions when no child is affected`() = runTest {
        repository.insertIfAbsent(seed(body = "First sentence. Second sentence."))
        repository.insertIfAbsent(child("child-1", "seed-key", "Second sentence."))
        insertSession("session-1", listOf("child-1"))

        val outcome = prepareReport(
            repository, database, SeedSelector.BySlug("special-relativity"),
            "First sentence, corrected. Second sentence.",
        )

        val report = (outcome as ReportOutcome.Ready).report
        assertEquals(0, report.affectedSessionCount)
    }

    @Test
    fun `prepareReport counts a session holding a node on a changed sentence`() = runTest {
        repository.insertIfAbsent(seed(body = "First sentence. Second sentence."))
        repository.insertIfAbsent(child("child-1", "seed-key", "First sentence."))
        insertSession("session-1", listOf("child-1"))
        insertSession("session-2", listOf("child-1"))
        // A session that never reached the affected child must not be counted.
        insertSession("session-3", listOf("some-other-key"))

        val outcome = prepareReport(
            repository, database, SeedSelector.BySlug("special-relativity"),
            "First sentence, corrected. Second sentence.",
        )

        val report = (outcome as ReportOutcome.Ready).report
        assertEquals(2, report.affectedSessionCount)
    }

    // ------------------------------------------------------------------ applyWrite

    @Test
    fun `applyWrite changes the text of the one seed and nothing else`() = runTest {
        repository.insertIfAbsent(seed(body = "Old text."))

        val outcome = applyWrite(repository, SeedSelector.BySlug("special-relativity"), "New text.", nowEpochMillis = 999L)

        assertTrue(outcome is WriteOutcome.Applied)
        val found = repository.findByKey("seed-key")
        assertEquals("New text.", found?.body)
        assertEquals("Old text.", found?.previousBody)
        assertEquals(999L, found?.correctedAtEpochMillis)
        assertEquals(Verb.SEED, found?.verb)
        assertEquals("special-relativity", found?.topicSlug)
    }

    @Test
    fun `applyWrite refuses each check of step 3, and writes nothing`() = runTest {
        repository.insertIfAbsent(seed(body = "Old text."))

        val cases = listOf(
            SeedSelector.BySlug("no-such-topic") to "Any text.",
            SeedSelector.BySlug("special-relativity") to "",
            SeedSelector.BySlug("special-relativity") to "Has <tag>.",
            SeedSelector.BySlug("special-relativity") to "a".repeat(ExplanationValidator.DEFAULT_MAX_CHARS + 1),
            SeedSelector.BySlug("special-relativity") to "Old text.",
        )

        cases.forEach { (selector, text) ->
            val outcome = applyWrite(repository, selector, text, nowEpochMillis = 1L)
            assertTrue(outcome is WriteOutcome.Refused, "expected a refusal for $selector / $text")
        }
        assertEquals("Old text.", repository.findByKey("seed-key")?.body, "no refusal may write anything")
    }

    @Test
    fun `applyWrite refuses a key that is not a seed, and writes nothing`() = runTest {
        repository.insertIfAbsent(seed(key = "child-key", body = "Old text.", verb = Verb.EXPLAIN))

        val outcome = applyWrite(repository, SeedSelector.ByKey("child-key"), "New text.", nowEpochMillis = 1L)

        assertTrue(outcome is WriteOutcome.Refused)
        assertEquals("Old text.", repository.findByKey("child-key")?.body)
    }

    // ------------------------------------------------------------------ applyRevert

    @Test
    fun `applyRevert restores the previous text after a write`() = runTest {
        repository.insertIfAbsent(seed(body = "Old text."))
        applyWrite(repository, SeedSelector.BySlug("special-relativity"), "New text.", nowEpochMillis = 1L)

        val outcome = applyRevert(repository, SeedSelector.BySlug("special-relativity"))

        assertTrue(outcome is RevertOutcome.Applied)
        val found = repository.findByKey("seed-key")
        assertEquals("Old text.", found?.body)
        assertNull(found?.previousBody)
        assertNull(found?.correctedAtEpochMillis)
    }

    @Test
    fun `applyRevert on a seed never corrected refuses, and writes nothing`() = runTest {
        repository.insertIfAbsent(seed(body = "Old text."))

        val outcome = applyRevert(repository, SeedSelector.BySlug("special-relativity"))

        assertTrue(outcome is RevertOutcome.Refused)
        assertEquals("Old text.", repository.findByKey("seed-key")?.body)
    }

    // ------------------------------------------------------------------ a child of an unchanged sentence

    @Test
    fun `a child of an unchanged sentence is still found through its key after the write`() = runTest {
        repository.insertIfAbsent(seed(body = "First sentence. Second sentence."))
        repository.insertIfAbsent(child("child-unchanged", "seed-key", "Second sentence."))

        applyWrite(
            repository, SeedSelector.BySlug("special-relativity"),
            "First sentence, corrected. Second sentence.", nowEpochMillis = 1L,
        )

        val found = repository.findByKey("child-unchanged")
        assertEquals("A child body.", found?.body, "the child document itself is never touched")
        assertEquals("Second sentence.", found?.spanSentence)
    }

    // ------------------------------------------------------------------ what the pages read (issue #162's own
    // claim: TopicPageRoutes.kt and SitemapRoutes.kt both call ExplanationRepository.findByKey live,
    // with no cache of their own — see docs/content/seed-corrections-2026-09-20/README.md)

    @Test
    fun `after a write, a live findByKey on the seed's own key sees the new text`() = runTest {
        repository.insertIfAbsent(seed(key = "sr-seed", slug = "special-relativity", body = "Old text."))

        applyWrite(repository, SeedSelector.BySlug("special-relativity"), "New text.", nowEpochMillis = 1L)

        assertEquals("New text.", repository.findByKey("sr-seed")?.body)
    }
}
