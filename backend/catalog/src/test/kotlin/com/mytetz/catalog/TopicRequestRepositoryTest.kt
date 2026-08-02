package com.mytetz.catalog

import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `topicRequests` is the only demand signal a curated-only catalogue has, and it is written to by an
 * endpoint anyone on the internet can call. Both of the bounds below are therefore enforced *here*,
 * in the repository, rather than in the route: a bound a route enforces is a bound the next route to
 * be added forgets.
 */
class TopicRequestRepositoryTest {

    private val database = MongoTestSupport.database("topic_requests")
    private val repository = TopicRequestRepository(database)

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<TopicRequest>("topicRequests").drop()
        repository.ensureIndexes()
    }

    // ------------------------------------------------------------------ normalisation

    @Test
    fun `spelling, spacing and case collapse onto one row`() = runTest {
        assertEquals(RecordOutcome.RECORDED, repository.record("  Organic   Chemistry "))
        assertEquals(RecordOutcome.RECORDED, repository.record("organic chemistry"))
        assertEquals(RecordOutcome.RECORDED, repository.record("ORGANIC\tCHEMISTRY"))

        assertEquals(3, repository.countFor("organic chemistry"))
    }

    @Test
    fun `countFor normalises its own argument, so raw text and normalised text agree`() = runTest {
        repository.record("Organic Chemistry")

        // The trap this closes: `countFor` used to take an ALREADY-normalised string while
        // `normalize` was public, so a caller holding the learner's raw text got a silent 0 — the
        // same answer as "nobody has ever asked for this". Both spellings must now answer 1.
        assertEquals(1, repository.countFor("  ORGANIC   chemistry  "))
        assertEquals(1, repository.countFor("organic chemistry"))
    }

    @Test
    fun `normalising an already normalised string changes nothing`() {
        // The property that makes normalising inside `countFor` safe rather than merely convenient.
        val once = TopicRequestRepository.normalize("  Organic \n Chemistry ")

        assertEquals(once, TopicRequestRepository.normalize(once))
        assertEquals("organic chemistry", once)
    }

    @Test
    fun `the most recent spelling a human typed is kept alongside the normalised id`() = runTest {
        repository.record("organic chemistry")
        repository.record("Organic Chemistry")

        val stored = repository.find("organic chemistry")

        assertEquals("organic chemistry", stored?.normalizedText, "the id must be the normalised form")
        assertEquals("Organic Chemistry", stored?.rawText, "the raw spelling was lost")
        assertEquals(2, stored?.count)
    }

    @Test
    fun `raw text is capped independently of the normalised form`() = runTest {
        // Collapsing a run of whitespace can take a very long submission down to a short, perfectly
        // valid `_id`. Storing the raw text unbounded would then be a way past MAX_TEXT_LENGTH.
        val sprawling = "a" + " ".repeat(5_000) + "b"

        assertEquals(RecordOutcome.RECORDED, repository.record(sprawling))

        val stored = repository.find("a b")
        assertEquals("a b", stored?.normalizedText)
        assertTrue(
            (stored?.rawText?.length ?: 0) <= TopicRequestRepository.MAX_TEXT_LENGTH,
            "raw text stored unbounded at ${stored?.rawText?.length} characters",
        )
    }

    // ------------------------------------------------------------------ input bounds

    @Test
    fun `blank and whitespace-only text is refused`() = runTest {
        assertEquals(RecordOutcome.INVALID_TEXT, repository.record(""))
        assertEquals(RecordOutcome.INVALID_TEXT, repository.record("   \t\n "))

        assertEquals(0, repository.countFor(""))
    }

    @Test
    fun `text longer than the limit is refused rather than truncated`() = runTest {
        val tooLong = "x".repeat(TopicRequestRepository.MAX_TEXT_LENGTH + 1)

        assertEquals(RecordOutcome.INVALID_TEXT, repository.record(tooLong))

        // Truncating would silently merge every long request onto one row and store a `_id` the
        // submitter never sent. Refusing says what happened.
        assertEquals(0, repository.countFor(tooLong))
    }

    @Test
    fun `text at exactly the limit is accepted`() = runTest {
        val atLimit = "y".repeat(TopicRequestRepository.MAX_TEXT_LENGTH)

        assertEquals(RecordOutcome.RECORDED, repository.record(atLimit))
        assertEquals(1, repository.countFor(atLimit))
    }

    @Test
    fun `the length limit is measured after normalisation, not before`() = runTest {
        // Otherwise a submitter pads with whitespace to fail the check on text that would have been
        // perfectly acceptable — and, worse, the reverse: a run of spaces inside the text collapses,
        // so a string that passed a pre-normalisation check could still be stored under a different
        // length than the one that was checked.
        val padded = "   " + "z".repeat(TopicRequestRepository.MAX_TEXT_LENGTH) + "   "

        assertEquals(RecordOutcome.RECORDED, repository.record(padded))
    }

    // ------------------------------------------------------------------ the growth bound

    @Test
    fun `the collection never grows past its cap`() = runTest {
        val bounded = TopicRequestRepository(database, maxDistinctRequests = 3)

        // The defect the endpoint had: every distinct normalised string minted a new document, so
        // the collection grew without limit under trivial spam.
        repeat(50) { bounded.record("topic $it") }

        assertEquals(3, bounded.countDistinct())
    }

    @Test
    fun `a new request is still accepted once the collection is full, by evicting the weakest row`() = runTest {
        val bounded = TopicRequestRepository(database, maxDistinctRequests = 2)
        bounded.record("first")
        bounded.record("second")

        // A permanent ceiling means a stranger sending 5000 junk phrases switches off the only
        // demand signal the product has, until a human triages the backlog by hand. Evicting the
        // least-demanded row instead makes the collection a bounded top-N by demand, which is what
        // a demand signal wanted to be anyway, and it self-heals the moment the flood stops.
        assertEquals(RecordOutcome.RECORDED, bounded.record("third"))

        assertEquals(2, bounded.countDistinct())
        assertEquals(1, bounded.countFor("third"), "the new request was refused rather than admitted")
    }

    @Test
    fun `a popular request survives a flood of one-off requests`() = runTest {
        val bounded = TopicRequestRepository(database, maxDistinctRequests = 3)
        repeat(5) { bounded.record("organic chemistry") }

        repeat(30) { bounded.record("junk $it") }

        // Eviction is by demand first, so the row everybody asked for outlives the flood. Without
        // that ordering the cap would be worse than useless: it would preferentially discard the
        // signal and keep the noise.
        assertEquals(5, bounded.countFor("organic chemistry"), "the most-requested row was evicted")
        assertEquals(3, bounded.countDistinct())
    }

    @Test
    fun `a full collection still counts repeats of what is already in it`() = runTest {
        val bounded = TopicRequestRepository(database, maxDistinctRequests = 2)
        bounded.record("organic chemistry")
        bounded.record("linear algebra")

        // The cap bounds GROWTH, not interest. Refusing repeats would freeze the demand signal at
        // the moment the cap was hit, which is exactly when the signal starts to matter — and it
        // would hand a spammer a way to stop everyone else's votes from being counted.
        assertEquals(RecordOutcome.RECORDED, bounded.record("Organic  Chemistry"))

        assertEquals(2, bounded.countFor("organic chemistry"))
    }

    @Test
    fun `an unparseable cap falls back to the default rather than removing the bound`() {
        assertEquals(
            TopicRequestRepository.DEFAULT_MAX_DISTINCT_REQUESTS,
            TopicRequestRepository.resolveMaxDistinctRequests(null),
        )
        assertEquals(
            TopicRequestRepository.DEFAULT_MAX_DISTINCT_REQUESTS,
            TopicRequestRepository.resolveMaxDistinctRequests("not a number"),
        )
        // Zero and negatives remove the bound in opposite directions if taken literally; both fall
        // back, the same shape as GraphConfig, QuotaConfig and SessionLimits.
        assertEquals(
            TopicRequestRepository.DEFAULT_MAX_DISTINCT_REQUESTS,
            TopicRequestRepository.resolveMaxDistinctRequests("0"),
        )
        assertEquals(500L, TopicRequestRepository.resolveMaxDistinctRequests(" 500 "))
    }

    // ------------------------------------------------------------------ bookkeeping

    @Test
    fun `first and last seen timestamps bracket the requests`() = runTest {
        var now = 1_000L
        val clocked = TopicRequestRepository(database, clock = { now })

        clocked.record("photosynthesis")
        now = 5_000L
        clocked.record("photosynthesis")

        val stored = clocked.find("photosynthesis")
        assertEquals(1_000L, stored?.firstSeenAtEpochMillis, "firstSeen moved")
        assertEquals(5_000L, stored?.lastSeenAtEpochMillis, "lastSeen did not move")
    }

    @Test
    fun `the demand and eviction indexes exist so both reads are cheap`() = runTest {
        val names = database.getCollection<TopicRequest>("topicRequests")
            .listIndexes()
            .let { flow -> mutableListOf<String>().also { names -> flow.collect { names += it.getString("name") } } }

        assertTrue("demand" in names, "expected a demand index, found $names")
        // Eviction sorts by (count asc, lastSeen asc) on every new row once full — under exactly the
        // flood that makes it run, an unindexed sort is a collection scan per request.
        assertTrue("weakest" in names, "expected an eviction index, found $names")
    }
}
