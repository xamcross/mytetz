package com.mytetz.session

import com.mongodb.client.model.Filters
import com.mytetz.graph.Verb
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.bson.Document
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SessionRepositoryTest {

    private val database = MongoTestSupport.database("session_repository")
    private val repository = SessionRepository(database)
    private val raw = database.getCollection<Document>("sessions")

    private val root = SessionNode("n0", null, "seed-key", "", Verb.SEED, 0, 0, 1)

    /**
     * Deliberately not a second `Verb.SEED` at variant 0 and depth 0: a codec that dropped the
     * whole nested list, or that stored an enum by ordinal, would round-trip a fixture of
     * all-default values without anyone noticing.
     */
    private val child = SessionNode("n1", "n0", "child-key", "microscopic realm", Verb.SIDE_VIEW, 2, 1, 5)

    private val session = LearningSession(
        id = "s1",
        principalId = "anon:alice",
        topicSlug = "quantum-physics",
        rootNodeId = "n0",
        currentNodeId = "n1",
        nodes = listOf(root, child),
        startedAtEpochMillis = 1,
        lastActiveAtEpochMillis = 5,
    )

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<LearningSession>("sessions").drop()
        // ensureIndexes() is deliberately NOT called here. Nothing else in this suite needs an
        // index, so creating two before every test is a round trip that buys nothing, and keeping
        // creation inside the one test that asserts on it makes what that test covers explicit.
        // (Task 1.8's review found a suite that created a live TTL index in setup over documents
        // stamped in the past; there is no TTL index here, but the habit of putting index creation
        // in @BeforeTest by reflex is what produced it.)
    }

    @Test
    fun `insert then findById round-trips every field including the nested nodes`() = runTest {
        repository.insert(session)

        // Whole-object equality rather than a spot check on topicSlug: the interesting part of this
        // document is `nodes`, a List of a nested @Serializable type carrying a Verb enum and a
        // nullable parent id, and a codec that mishandled any of those would survive any assertion
        // aimed at the top-level scalars.
        assertEquals(session, repository.findById("s1"))
    }

    @Test
    fun `a verb is stored under its name, not its ordinal`() = runTest {
        repository.insert(session)

        val stored = raw.find(Filters.eq("_id", "s1")).firstOrNull()!!
        val nodes = stored.getList("nodes", Document::class.java)

        // Independent of the round trip above, which would pass just as happily against an ordinal
        // encoding. Verb is an ordered enum that will gain members — VISUALIZE is already last —
        // and an ordinal on the wire silently re-points every stored session the day one is
        // inserted anywhere but the end. Names do not move.
        assertEquals(listOf("SEED", "SIDE_VIEW"), nodes.map { it.getString("verb") })
    }

    @Test
    fun `session status is stored under its name, and a defaulted status is stored at all`() = runTest {
        val completed = session.copy(id = "s2", status = SessionStatus.COMPLETED)
        repository.insert(session)
        repository.insert(completed)

        // ACTIVE is this property's default value, and kotlinx omits defaults unless the codec asks
        // for them. The driver's BsonConfiguration sets encodeDefaults = true, so it is written —
        // but nothing enforced that, and a "my active sessions" listing is a Filters.eq on exactly
        // this field, which would silently match nothing if it ever stopped being written.
        assertEquals("ACTIVE", raw.find(Filters.eq("_id", "s1")).firstOrNull()!!.getString("status"))

        // COMPLETED is the one that can actually discriminate. ACTIVE is simultaneously the default
        // value AND ordinal 0, so every interesting regression — an ordinal encoding, or a decoder
        // that shrugs and returns the default — round-trips ACTIVE to ACTIVE and stays invisible.
        assertEquals("COMPLETED", raw.find(Filters.eq("_id", "s2")).firstOrNull()!!.getString("status"))
        // Encode and decode are separate failures: the line above passes against a decoder that
        // always answers ACTIVE, and this one is what catches it.
        assertEquals(SessionStatus.COMPLETED, repository.findById("s2")?.status)
    }

    @Test
    fun `findById returns null for an unknown id`() = runTest {
        assertNull(repository.findById("nope"))
    }

    @Test
    fun `appendNode appends after the existing nodes and advances the cursor`() = runTest {
        repository.insert(session)
        val appended = SessionNode("n2", "n1", "grandchild-key", "wave function", Verb.DIG_DEEPER, 0, 2, 7)

        repository.appendNode("s1", appended, nowEpochMillis = 99)

        val reloaded = repository.findById("s1")!!
        // Content and position, not just the count: a write that replaced the array, reordered it,
        // or pushed a node with the wrong verb or parent would keep the size right.
        assertEquals(listOf(root, child, appended), reloaded.nodes)
        assertEquals("n2", reloaded.currentNodeId)
        assertEquals(99, reloaded.lastActiveAtEpochMillis)
        // The session's age must not be re-anchored by activity on it.
        assertEquals(1, reloaded.startedAtEpochMillis)
    }

    @Test
    fun `appendNode writes only the session it names`() = runTest {
        val other = session.copy(id = "s2")
        repository.insert(session)
        repository.insert(other)

        repository.appendNode("s1", SessionNode("n2", "n1", "k", "s", Verb.EXPLAIN, 0, 2, 7), nowEpochMillis = 99)

        assertEquals(3, repository.findById("s1")!!.nodes.size)
        assertEquals(other, repository.findById("s2"))
    }

    @Test
    fun `appendNode on an unknown session raises rather than silently doing nothing`() = runTest {
        // An updateOne that matches nothing is reported as success. The caller — SessionService,
        // which has just paid the model for the explanation this node points at — then believes
        // the step was recorded, hands the learner an answer, and loses the branch they were on.
        val orphan = SessionNode("n1", "n0", "orphan-key", "microscopic realm", Verb.EXPLAIN, 0, 1, 7)

        assertFailsWith<SessionNotFoundException> { repository.appendNode("nope", orphan, nowEpochMillis = 99) }

        // And it must not have invented one on the way past: an upsert here would create a session
        // with no principal, no topic and no root, which findById would happily return.
        assertNull(repository.findById("nope"))
    }

    @Test
    fun `deleteForPrincipal removes every session for that principal and reports the count`() = runTest {
        val other = session.copy(id = "s2", principalId = "user:carol")
        val second = session.copy(id = "s3")
        repository.insert(session)
        repository.insert(second)
        repository.insert(other)

        val removed = repository.deleteForPrincipal("anon:alice")

        assertEquals(2, removed)
        assertNull(repository.findById("s1"))
        assertNull(repository.findById("s3"))
        assertEquals(other, repository.findById("s2"), "a different principal's session must survive")
    }

    @Test
    fun `deleteForPrincipal for an unknown principal removes nothing and reports zero`() = runTest {
        repository.insert(session)

        val removed = repository.deleteForPrincipal("anon:nobody-ever-used-this-id")

        assertEquals(0, removed)
        assertEquals(session, repository.findById("s1"))
    }

    @Test
    fun `ensureIndexes creates the documented indexes and can be run again`() = runTest {
        repository.ensureIndexes()
        // Startup calls this on every boot against a database that already has the indexes.
        repository.ensureIndexes()

        val byName = raw.listIndexes().toList().associateBy { it.getString("name") }

        assertEquals(
            Document("principalId", 1).append("lastActiveAtEpochMillis", -1),
            byName["principal_recent"]?.get("key"),
        )
        assertEquals(Document("topicSlug", 1), byName["by_topic"]?.get("key"))
    }
}
