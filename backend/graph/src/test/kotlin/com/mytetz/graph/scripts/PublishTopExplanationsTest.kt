package com.mytetz.graph.scripts

import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.MAX_PUBLISHED_EXPLANATIONS
import com.mytetz.graph.MongoTestSupport
import com.mytetz.graph.Verb
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every test here runs against a real Mongo container through [MongoTestSupport], the project's
 * own Testcontainers fixture — never against a live database. This file is the proof this issue's
 * safety rule asks for: the review path is built and tested against Testcontainers only.
 *
 * Round 2 replaces the bulk-publish tests this file held before. The owner reads one explanation
 * at a time (issue #49: "Read each explanation. Mark an accurate one as published. Leave a wrong
 * one unpublished."), so the write path must act on an explicit list of keys the owner chose, and
 * never on "the top N" as a group.
 */
class PublishTopExplanationsTest {

    private val database = MongoTestSupport.database("publish_top_explanations")
    private val repository = ExplanationRepository(database)

    private fun explanation(
        key: String,
        requestCount: Long,
        published: Boolean = false,
        verb: Verb = Verb.EXPLAIN,
    ) = Explanation(
        key = key, topicSlug = "quantum-physics", parentKey = "p", span = "span-$key", spanSentence = "s",
        verb = verb, variant = 0, depth = 1, body = "A body for $key.", grounded = false, sources = emptyList(),
        promptVersion = "v1", modelFamily = "fake-model", modelId = "fake-model",
        inputTokens = 1, outputTokens = 1, costMicros = 0, requestCount = requestCount,
        createdAtEpochMillis = 0, published = published,
    )

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Explanation>("explanations").drop()
        repository.ensureIndexes()
    }

    // ------------------------------------------------------------------ listTopCandidates

    @Test
    fun `listTopCandidates lists unpublished EXPLAIN nodes ordered by requestCount, highest first`() = runTest {
        repository.insertIfAbsent(explanation("low", requestCount = 1))
        repository.insertIfAbsent(explanation("high", requestCount = 9))

        val candidates = listTopCandidates(repository, limit = 10)

        assertEquals(listOf("high", "low"), candidates.map { it.key })
    }

    @Test
    fun `listTopCandidates excludes an already-published node`() = runTest {
        repository.insertIfAbsent(explanation("already", requestCount = 9, published = true))
        repository.insertIfAbsent(explanation("new", requestCount = 5))

        val candidates = listTopCandidates(repository, limit = 10)

        assertEquals(listOf("new"), candidates.map { it.key })
    }

    @Test
    fun `listTopCandidates applies the limit`() = runTest {
        repeat(5) { i -> repository.insertIfAbsent(explanation("k-$i", requestCount = i.toLong())) }

        assertEquals(2, listTopCandidates(repository, limit = 2).size)
    }

    @Test
    fun `listTopCandidates excludes a SEED and a VISUALIZE node`() = runTest {
        repository.insertIfAbsent(explanation("seed", requestCount = 100, verb = Verb.SEED))
        repository.insertIfAbsent(explanation("visualize", requestCount = 100, verb = Verb.VISUALIZE))
        repository.insertIfAbsent(explanation("explain", requestCount = 1))

        val candidates = listTopCandidates(repository, limit = 10)

        assertEquals(listOf("explain"), candidates.map { it.key })
    }

    // ------------------------------------------------------------------ renderCandidateReport

    @Test
    fun `renderCandidateReport shows the full key, the short key, the path, the count and the body`() {
        val candidate = explanation("abcdef0123456789" + "0".repeat(48), requestCount = 7)
            .copy(span = "wave function")

        val report = renderCandidateReport(listOf(candidate))

        assertTrue(candidate.key in report, "the full key must appear, so the owner can copy it")
        assertTrue("abcdef012345" in report, "the short key must appear")
        assertTrue("/topics/quantum-physics/explain/abcdef012345" in report, "the public path must appear")
        assertTrue("wave function" in report)
        assertTrue("7" in report, "the request count must appear")
        assertTrue(candidate.body in report, "the full body text must appear, so the owner can read it")
    }

    // ------------------------------------------------------------------ publishByKeys

    @Test
    fun `publishByKeys publishes exactly the given keys`() = runTest {
        repository.insertIfAbsent(explanation("a", requestCount = 1))
        repository.insertIfAbsent(explanation("b", requestCount = 1))
        repository.insertIfAbsent(explanation("c", requestCount = 1))

        val outcome = publishByKeys(repository, listOf("a", "b"))

        assertEquals(PublishOutcome.Applied(listOf("a", "b")), outcome)
        assertTrue(repository.findByKey("a")!!.published)
        assertTrue(repository.findByKey("b")!!.published)
        assertFalse(repository.findByKey("c")!!.published, "a key not given must not be touched")
    }

    @Test
    fun `publishByKeys is all-or-nothing — one bad key among good keys writes nothing`() = runTest {
        repository.insertIfAbsent(explanation("good-1", requestCount = 1))
        repository.insertIfAbsent(explanation("good-2", requestCount = 1))

        val outcome = publishByKeys(repository, listOf("good-1", "no-such-key", "good-2"))

        assertTrue(outcome is PublishOutcome.Rejected)
        assertTrue((outcome as PublishOutcome.Rejected).problems.any { "no-such-key" in it })
        assertFalse(repository.findByKey("good-1")!!.published, "a good key must not be published when another key fails")
        assertFalse(repository.findByKey("good-2")!!.published, "a good key must not be published when another key fails")
    }

    @Test
    fun `publishByKeys refuses a VISUALIZE key`() = runTest {
        repository.insertIfAbsent(explanation("diagram", requestCount = 1, verb = Verb.VISUALIZE))

        val outcome = publishByKeys(repository, listOf("diagram"))

        assertTrue(outcome is PublishOutcome.Rejected)
        assertTrue((outcome as PublishOutcome.Rejected).problems.any { "diagram" in it && "VISUALIZE" in it })
        assertFalse(repository.findByKey("diagram")!!.published)
    }

    @Test
    fun `publishByKeys refuses a SEED key`() = runTest {
        repository.insertIfAbsent(explanation("seed-key", requestCount = 1, verb = Verb.SEED))

        val outcome = publishByKeys(repository, listOf("seed-key"))

        assertTrue(outcome is PublishOutcome.Rejected)
        assertTrue((outcome as PublishOutcome.Rejected).problems.any { "seed-key" in it && "SEED" in it })
        assertFalse(repository.findByKey("seed-key")!!.published)
    }

    @Test
    fun `publishByKeys refuses an already-published key`() = runTest {
        repository.insertIfAbsent(explanation("already", requestCount = 1, published = true))

        val outcome = publishByKeys(repository, listOf("already"))

        assertTrue(outcome is PublishOutcome.Rejected)
        assertTrue((outcome as PublishOutcome.Rejected).problems.any { "already" in it })
    }

    @Test
    fun `publishByKeys refuses an unknown key`() = runTest {
        val outcome = publishByKeys(repository, listOf("no-such-key"))

        assertTrue(outcome is PublishOutcome.Rejected)
        assertTrue((outcome as PublishOutcome.Rejected).problems.any { "no-such-key" in it })
    }

    @Test
    fun `publishByKeys with no keys is rejected, and writes nothing`() = runTest {
        val outcome = publishByKeys(repository, emptyList())

        assertTrue(outcome is PublishOutcome.Rejected)
    }

    @Test
    fun `publishByKeys at the cap boundary succeeds when the new total lands exactly at the cap`() = runTest {
        repeat(MAX_PUBLISHED_EXPLANATIONS - 1) { i ->
            repository.insertIfAbsent(explanation("already-$i", requestCount = 1, published = true))
        }
        repository.insertIfAbsent(explanation("one-more", requestCount = 1))

        val outcome = publishByKeys(repository, listOf("one-more"))

        assertEquals(PublishOutcome.Applied(listOf("one-more")), outcome)
        assertEquals(MAX_PUBLISHED_EXPLANATIONS, repository.findPublished().size)
    }

    @Test
    fun `publishByKeys refuses a key list that would cross the cap, and writes nothing`() = runTest {
        repeat(MAX_PUBLISHED_EXPLANATIONS) { i ->
            repository.insertIfAbsent(explanation("already-$i", requestCount = 1, published = true))
        }
        repository.insertIfAbsent(explanation("over-the-cap", requestCount = 1))

        val outcome = publishByKeys(repository, listOf("over-the-cap"))

        assertTrue(outcome is PublishOutcome.Rejected)
        assertTrue((outcome as PublishOutcome.Rejected).problems.any { "cap" in it })
        assertFalse(repository.findByKey("over-the-cap")!!.published)
        assertEquals(MAX_PUBLISHED_EXPLANATIONS, repository.findPublished().size)
    }

    // ------------------------------------------------------------------ unpublishByKeys

    @Test
    fun `unpublishByKeys unpublishes exactly the given keys`() = runTest {
        repository.insertIfAbsent(explanation("a", requestCount = 1, published = true))
        repository.insertIfAbsent(explanation("b", requestCount = 1, published = true))

        val outcome = unpublishByKeys(repository, listOf("a"))

        assertEquals(PublishOutcome.Applied(listOf("a")), outcome)
        assertFalse(repository.findByKey("a")!!.published)
        assertTrue(repository.findByKey("b")!!.published, "a key not given must not be touched")
    }

    @Test
    fun `unpublishByKeys refuses a key that is not published`() = runTest {
        repository.insertIfAbsent(explanation("not-published", requestCount = 1, published = false))

        val outcome = unpublishByKeys(repository, listOf("not-published"))

        assertTrue(outcome is PublishOutcome.Rejected)
        assertTrue((outcome as PublishOutcome.Rejected).problems.any { "not-published" in it })
    }

    @Test
    fun `unpublishByKeys is all-or-nothing — an unknown key among good keys writes nothing`() = runTest {
        repository.insertIfAbsent(explanation("good", requestCount = 1, published = true))

        val outcome = unpublishByKeys(repository, listOf("good", "no-such-key"))

        assertTrue(outcome is PublishOutcome.Rejected)
        assertTrue(repository.findByKey("good")!!.published, "a good key must not be unpublished when another key fails")
    }

    @Test
    fun `unpublishByKeys with no keys is rejected`() = runTest {
        val outcome = unpublishByKeys(repository, emptyList())

        assertTrue(outcome is PublishOutcome.Rejected)
    }

    // ------------------------------------------------------------------ small parsers

    @Test
    fun `parseKeysArg splits on a comma and trims each key`() {
        assertEquals(listOf("a", "b", "c"), parseKeysArg(" a, b ,c"))
    }

    @Test
    fun `parseKeysArg drops an empty entry`() {
        assertEquals(listOf("a", "b"), parseKeysArg("a,,b,"))
    }

    @Test
    fun `parseKeysFileText reads one key per line and drops a blank line`() {
        assertEquals(listOf("a", "b"), parseKeysFileText("a\n\n b \n"))
    }

    // ------------------------------------------------------------------ the real process (issue #175)

    /**
     * Every test below runs `main` as a real, separate `java` process, the level issue #175's bug
     * lived at. A non-daemon thread of the driver does not stop an in-process test: the test's own
     * JVM keeps running regardless. That same thread does stop a real process from ending.
     */
    private fun runMainProcess(
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ): ScriptProcessResult = runScriptProcess(
        mainClass = "com.mytetz.graph.scripts.PublishTopExplanationsKt",
        args = args,
        env = mapOf(
            "MONGODB_URI" to MongoTestSupport.connectionString,
            "MONGODB_DATABASE" to "test_publish_top_explanations_process",
        ) + env,
    )

    @Test
    fun `main ends the process with exit code 0 on success`() {
        val result = runMainProcess()

        assertEquals(0, result.exitCode)
    }

    @Test
    fun `main ends the process with a non-zero exit code and an Error line on a bad --out path`() {
        val badFolder = File(System.getProperty("java.io.tmpdir"), "no-such-folder-issue175").absolutePath
        val outFile = "$badFolder/candidates.md"

        val result = runMainProcess(args = listOf("--out", outFile))

        assertFalse(result.exitCode == 0, "a bad --out path must not exit 0")
        assertTrue("Error:" in result.output)
        assertTrue(badFolder in result.output, "the refusal must name the missing folder")
    }

    @Test
    fun `main refuses a bad --out path before it reads the database`() {
        val badFolder = File(System.getProperty("java.io.tmpdir"), "no-such-folder-issue175-b").absolutePath
        val outFile = "$badFolder/candidates.md"

        // A database read here would time out on the unreachable host, and fail this test.
        val result = runMainProcess(
            args = listOf("--out", outFile),
            env = mapOf(
                "MONGODB_URI" to "mongodb://unreachable-host-issue175.invalid:27017",
                "MYTETZ_MONGO_SERVER_SELECTION_TIMEOUT_MILLIS" to "200",
            ),
        )

        assertFalse(result.exitCode == 0)
        assertTrue("the folder of --out does not exist" in result.output)
    }

    @Test
    fun `main never prints the host name of an unreachable database`() {
        val unreachableHost = "unreachable-host-issue175.invalid"

        val result = runMainProcess(
            env = mapOf(
                "MONGODB_URI" to "mongodb://$unreachableHost:27017",
                "MYTETZ_MONGO_SERVER_SELECTION_TIMEOUT_MILLIS" to "200",
            ),
        )

        assertFalse(result.exitCode == 0)
        assertTrue("Error:" in result.output)
        assertFalse(unreachableHost in result.output, "the host name must never be printed")
        assertFalse("MONGODB_URI" in result.output, "the environment variable's name must never be printed")
    }
}
