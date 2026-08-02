package com.mytetz.session

import com.mytetz.graph.Verb
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class ContextChainTest {

    private fun node(
        id: String,
        parent: String?,
        span: String,
        depth: Int,
        variant: Int = 0,
        verb: Verb = if (parent == null) Verb.SEED else Verb.EXPLAIN,
    ) = SessionNode(id, parent, "key-$id", span, verb, variant, depth, 0)

    private val session = LearningSession(
        id = "s1",
        principalId = "anon:alice",
        topicSlug = "quantum-physics",
        rootNodeId = "n0",
        currentNodeId = "n2",
        nodes = listOf(
            node("n0", null, "", 0),
            node("n1", "n0", "fundamental physical theory", 1),
            node("n2", "n1", "microscopic realm", 2),
            node("n3", "n0", "behavior of matter", 1),
        ),
        startedAtEpochMillis = 0,
        lastActiveAtEpochMillis = 0,
    )

    @Test
    fun `pathTo returns the whole ancestry root-first with the target last`() {
        val path = ContextChain.pathTo(session, "n2")

        assertEquals(listOf("n0", "n1", "n2"), path.map { it.nodeId })
        // The spans are what actually reach the prompt (PromptBuilder renders one Ancestor per
        // link), so pin them too: ids alone would pass for a path of the right shape carrying the
        // wrong nodes.
        assertEquals(
            listOf("", "fundamental physical theory", "microscopic realm"),
            path.map { it.span },
        )
    }

    @Test
    fun `pathTo on the root returns just the root`() {
        assertEquals(listOf("n0"), ContextChain.pathTo(session, "n0").map { it.nodeId })
    }

    @Test
    fun `a sibling branch does not leak into the path`() {
        assertEquals(listOf("n0", "n3"), ContextChain.pathTo(session, "n3").map { it.nodeId })
    }

    @Test
    fun `pathTo follows parent links rather than the stored order`() {
        // appendNode's $push happens to store nodes parent-before-child, so on every session this
        // module writes, "filter the ancestors and keep the list order" is indistinguishable from
        // walking the tree. It stops being indistinguishable the moment anything reorders the
        // array — a migration, a repair script, a hand-edited document. Root-first is a property
        // of the walk, not of the storage.
        val shuffled = session.copy(
            nodes = listOf(session.nodes[2], session.nodes[3], session.nodes[0], session.nodes[1]),
        )

        assertEquals(listOf("n0", "n1", "n2"), ContextChain.pathTo(shuffled, "n2").map { it.nodeId })
    }

    @Test
    fun `an unknown node id raises`() {
        val raised = assertFailsWith<IllegalArgumentException> { ContextChain.pathTo(session, "nope") }

        assertTrue("nope" in raised.message.orEmpty(), "message must name the node: ${raised.message}")
    }

    @Test
    fun `a parent that is missing from the session raises rather than truncating the chain`() {
        // The dangerous end of the chain is the ROOT end. A walk that simply stops when a parent
        // id resolves to nothing returns a path with the topic removed from it, and the caller
        // cannot tell: it is a well-formed list of the right type. Downstream that is a prompt
        // with no topic, which is precisely the context bleed this product exists to prevent —
        // arriving silently and reading like a normal answer.
        val dangling = session.copy(
            nodes = listOf(
                node("n1", "ghost", "fundamental physical theory", 1),
                node("n2", "n1", "microscopic realm", 2),
            ),
        )

        val raised = assertFailsWith<IllegalArgumentException> { ContextChain.pathTo(dangling, "n2") }

        assertTrue("ghost" in raised.message.orEmpty(), "message must name the missing parent: ${raised.message}")
        assertTrue("n1" in raised.message.orEmpty(), "message must name the node holding it: ${raised.message}")
    }

    @Test
    fun `a parent cycle raises rather than spinning forever`() {
        // Sessions are read back from Mongo and nothing validates them on the way in. A document
        // whose parent links close into a loop must not take a request thread with it.
        val cyclic = session.copy(
            rootNodeId = "c0",
            currentNodeId = "c2",
            nodes = listOf(
                node("c0", "c2", "a", 0),
                node("c1", "c0", "b", 1),
                node("c2", "c1", "c", 2),
            ),
        )

        // Run it off-thread so an unbounded walk fails this test instead of hanging the suite.
        val attempt = CompletableFuture.supplyAsync { runCatching { ContextChain.pathTo(cyclic, "c2") } }
        val outcome = try {
            attempt.get(5, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            fail("pathTo did not terminate on a session whose parent links form a cycle")
        }

        assertFailsWith<IllegalArgumentException> { outcome.getOrThrow() }
    }

    @Test
    fun `a duplicate node id raises rather than silently picking one`() {
        // appendNode's $push enforces no uniqueness, so a retried write can leave two nodes under
        // one id. Indexing them by id then keeps whichever came last, and the ancestry silently
        // becomes the other branch's: here pathTo("n2") would report n0 -> n3 -> n1 -> n2.
        val duplicated = session.copy(
            nodes = session.nodes + node("n1", "n3", "wave function", 2),
        )

        val raised = assertFailsWith<IllegalArgumentException> { ContextChain.pathTo(duplicated, "n2") }

        assertTrue("n1" in raised.message.orEmpty(), "message must name the duplicate: ${raised.message}")
    }

    /**
     * Every decoy differs from the triple under test in exactly one of the three dimensions, and
     * every decoy carries a variant strictly greater than the real answer. Dropping any one of the
     * parent, span or verb filters therefore changes the result rather than leaving it intact.
     */
    private val withVariants = session.copy(
        nodes = session.nodes + listOf(
            // The triple under test: parent n1, span "microscopic realm", SIDE_VIEW. Max 4, count 2 —
            // deliberately different numbers, so `count()` and `size` cannot masquerade as `max`.
            node("v1", "n1", "microscopic realm", 2, variant = 1, verb = Verb.SIDE_VIEW),
            node("v4", "n1", "microscopic realm", 2, variant = 4, verb = Verb.SIDE_VIEW),
            // Same parent and span, different verb.
            node("d9", "n1", "microscopic realm", 2, variant = 9, verb = Verb.DIG_DEEPER),
            // Same parent and verb, different span.
            node("d7", "n1", "wave function", 2, variant = 7, verb = Verb.SIDE_VIEW),
            // Same span and verb, different parent.
            node("d8", "n0", "microscopic realm", 1, variant = 8, verb = Verb.SIDE_VIEW),
            // Same parent and span as the zero-case query below, different verb.
            node("d6", "n0", "wave function", 1, variant = 6, verb = Verb.EXPLAIN),
        ),
    )

    @Test
    fun `highestVariant is zero when this parent, span and verb have never been used together`() {
        // (n0, "wave function", SIDE_VIEW) matches nothing, while each single-dimension relaxation
        // of it matches something non-zero: drop the parent and d7 answers 7, drop the span and d8
        // answers 8, drop the verb and d6 answers 6.
        assertEquals(0, ContextChain.highestVariant(withVariants, "n0", "wave function", Verb.SIDE_VIEW))
    }

    @Test
    fun `highestVariant returns the largest variant taken, not how many were taken`() {
        assertEquals(4, ContextChain.highestVariant(withVariants, "n1", "microscopic realm", Verb.SIDE_VIEW))
    }

    @Test
    fun `SessionLimits falls back to its defaults for an unusable override`() {
        assertEquals(12, SessionLimits.resolveMaxDepth(null))
        assertEquals(4, SessionLimits.resolveMaxDepth("4"))
        assertEquals(4, SessionLimits.resolveMaxDepth("  4  "))
        assertEquals(12, SessionLimits.resolveMaxDepth("twelve"))
        assertEquals(12, SessionLimits.resolveMaxDepth(""))
        assertEquals(12, SessionLimits.resolveMaxDepth("0"))
        assertEquals(12, SessionLimits.resolveMaxDepth("-1"))

        assertEquals(200, SessionLimits.resolveMaxNodes(null))
        assertEquals(50, SessionLimits.resolveMaxNodes("50"))
        assertEquals(50, SessionLimits.resolveMaxNodes(" 50 "))
        assertEquals(200, SessionLimits.resolveMaxNodes("lots"))
        assertEquals(200, SessionLimits.resolveMaxNodes(""))
        assertEquals(200, SessionLimits.resolveMaxNodes("0"))
        assertEquals(200, SessionLimits.resolveMaxNodes("-1"))

        assertEquals(3, SessionLimits.resolveMaxVariants(null))
        assertEquals(7, SessionLimits.resolveMaxVariants("7"))
        assertEquals(7, SessionLimits.resolveMaxVariants(" 7 "))
        assertEquals(3, SessionLimits.resolveMaxVariants("three"))
        assertEquals(3, SessionLimits.resolveMaxVariants(""))
        assertEquals(3, SessionLimits.resolveMaxVariants("0"))
        assertEquals(3, SessionLimits.resolveMaxVariants("-1"))
    }

    @Test
    fun `SessionLimits refuses a limit that would silently disable itself`() {
        // The resolvers guard the env-var path only; a caller constructing this directly bypasses
        // them. Each of the three at zero removes a bound rather than tightening it — maxDepth 0
        // and maxNodes 0 refuse every session including the root, and maxVariants 0 refuses the
        // first SIDE_VIEW, which reads as a broken feature rather than as a misconfiguration.
        assertFailsWith<IllegalArgumentException> { SessionLimits(maxDepth = 0, maxNodes = 200, maxVariants = 3) }
        assertFailsWith<IllegalArgumentException> { SessionLimits(maxDepth = -1, maxNodes = 200, maxVariants = 3) }
        assertFailsWith<IllegalArgumentException> { SessionLimits(maxDepth = 12, maxNodes = 0, maxVariants = 3) }
        assertFailsWith<IllegalArgumentException> { SessionLimits(maxDepth = 12, maxNodes = 200, maxVariants = 0) }
    }
}
