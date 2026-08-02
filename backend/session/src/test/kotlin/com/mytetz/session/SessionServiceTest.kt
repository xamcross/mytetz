package com.mytetz.session

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.ExplanationGraph
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.ExplanationValidator
import com.mytetz.graph.GraphChunk
import com.mytetz.graph.GraphConfig
import com.mytetz.graph.GraphRequest
import com.mytetz.graph.Verb
import com.mytetz.llm.FakeLlmClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionServiceTest {

    private val database = MongoTestSupport.database("session_service")
    private val sessions = SessionRepository(database)
    private val explanations = ExplanationRepository(database)
    private val topics = TopicRepository(database)
    private val catalog = CatalogService(topics)
    private val llm = FakeLlmClient()

    private val seedBody = "Quantum mechanics is the fundamental physical theory that describes " +
        "the behavior of matter and of light at and below the scale of atoms."

    /**
     * The brief's own fixture, and it is the reason `sentenceAround` cannot treat every `.` as a
     * terminator: `0.1` sits in the middle of the only sentence there is.
     */
    private val childBody = "The microscopic realm studied by quantum theory is the subatomic scale, " +
        "a universe smaller than 0.1 nanometers where the traditional laws of physics collapse."

    /**
     * Two sentences, because sentence extraction is only meaningfully testable against text that has
     * a boundary in it — and the fixtures above have none. The second sentence carries both traps in
     * front of a span: an abbreviation whose dot really is followed by a space, and a decimal whose
     * dot is not.
     */
    private val twoSentenceBody =
        "Quantum tunnelling lets a particle pass through a barrier it has too little energy to climb. " +
            "The odds fall away sharply with width, e.g. by roughly a factor of ten for each 0.1 nanometers added."

    private val firstSentence = "Quantum tunnelling lets a particle pass through a barrier it has too little energy to climb."
    private val secondSentence = "The odds fall away sharply with width, e.g. by roughly a factor of ten for each 0.1 nanometers added."

    /**
     * Three sentences ending, in turn, on a bare single letter, on a two-letter token shaped like a
     * title, and on an ordinary word. The first two are the shapes a rule built only to prevent
     * early splits will happily MERGE into the sentence after them — the error direction the
     * implementation declares worse, so it needs its own fixture.
     */
    private val initialBody =
        "A magnetic field has a strength at every point, and physicists write that strength as B. " +
            "A pulse of it can rise and fall in under 20 ms. " +
            "The letter is conventional and carries no meaning of its own."

    private val msSentence = "A pulse of it can rise and fall in under 20 ms."
    private val conventionSentence = "The letter is conventional and carries no meaning of its own."

    private var ids = 0

    private val graph = ExplanationGraph(explanations, llm, ExplanationValidator(), GraphConfig(promptVersion = "v1"))

    private fun serviceWith(
        limits: SessionLimits = SessionLimits(),
        clock: () -> Long = { FIXED_NOW },
    ) = SessionService(
        sessions = sessions,
        catalog = catalog,
        graph = graph,
        explanations = explanations,
        limits = limits,
        idFactory = { "id${ids++}" },
        clock = clock,
    )

    private val service = serviceWith()

    @BeforeTest
    fun reset() = runTest {
        listOf("sessions", "explanations", "topics")
            .forEach { database.getCollection<org.bson.Document>(it).drop() }
        sessions.ensureIndexes(); explanations.ensureIndexes(); topics.ensureIndexes()
        catalog.seedFromResource()
        ids = 0
        llm.calls.clear()
        llm.bodyByPromptSubstring.clear()
        llm.bodyByPromptSubstring["opening paragraph"] = seedBody
        llm.nextBody = childBody
        llm.nextStopReason = "end_turn"
    }

    private suspend fun newSession() = service.create("anon:alice", "quantum-physics")

    /** A selection that really does sit where it says it does, so the gate lets it through. */
    private fun selectionOf(body: String, text: String): SpanSelection {
        val start = body.indexOf(text)
        require(start >= 0) { "fixture error: \"$text\" is not in the body" }
        return SpanSelection(text, start, start + text.length)
    }

    // ------------------------------------------------------------------ create

    @Test
    fun `create returns a session with a root node holding the seed`() = runTest {
        val (session, seed) = newSession()

        assertEquals("quantum-physics", session.topicSlug)
        assertEquals("anon:alice", session.principalId)
        assertEquals(seedBody, seed.body)

        val root = session.nodes.single()
        // The whole node, not a spot check on the verb: the root is the anchor of every context
        // chain built from this session, and a root with the wrong parent, depth or key produces a
        // plausible-looking ancestry rather than a visible failure.
        assertEquals(
            SessionNode(
                nodeId = session.rootNodeId,
                parentNodeId = null,
                explanationKey = seed.key,
                span = "",
                verb = Verb.SEED,
                variant = 0,
                depth = 0,
                createdAtEpochMillis = FIXED_NOW,
            ),
            root,
        )
        assertEquals(session.rootNodeId, session.currentNodeId)
        assertEquals(FIXED_NOW, session.startedAtEpochMillis)
        assertEquals(FIXED_NOW, session.lastActiveAtEpochMillis)

        // Returned AND durable: the caller gets the session it can go on to explain against.
        assertEquals(session, sessions.findById(session.id))
        assertEquals(seed, explanations.findByKey(seed.key))
    }

    @Test
    fun `create fails for an unknown topic`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.create("anon:alice", "no-such-topic") }

        // Nothing half-built: a session must not exist for a topic that does not.
        assertEquals(0L, database.getCollection<org.bson.Document>("sessions").countDocuments())
        assertEquals(0, llm.calls.size, "an unknown topic must be refused before the model is asked")
    }

    @Test
    fun `create refuses a topic that exists but is not published`() = runTest {
        // CatalogService.findBySlug deliberately does NOT filter on status — CatalogServiceTest pins
        // that, so an admin lookup can still see a draft. This is therefore the place where
        // publication is enforced for readers, and without a check here a DRAFT topic is startable
        // by anyone who knows its slug. Worse than a leak: it would mint an immutable,
        // content-addressed explanation of unreviewed material that there is no path to delete.
        val draft = Topic(
            slug = "unreviewed-draft-topic",
            title = "Unreviewed Draft Topic",
            category = "Physics",
            summary = "Not yet reviewed, and must never reach a reader.",
            status = TopicStatus.DRAFT,
        )
        topics.upsert(draft)
        // The row really is there, so the refusal below is the status check and not a failed upsert.
        assertEquals(TopicStatus.DRAFT, catalog.findBySlug(draft.slug)?.status)

        assertFailsWith<IllegalArgumentException> { service.create("anon:alice", draft.slug) }

        assertEquals(0, llm.calls.size, "an unpublished topic must be refused before the model is asked")
        assertEquals(0L, database.getCollection<org.bson.Document>("sessions").countDocuments())
    }

    // ------------------------------------------------------------------ explain

    @Test
    fun `explain appends a child node pointing at the generated explanation`() = runTest {
        val (session, _) = newSession()

        val chunks = service.explain(
            sessionId = session.id,
            parentNodeId = session.rootNodeId,
            selection = selectionOf(seedBody, "behavior of matter"),
            verb = Verb.EXPLAIN,
            requestedVariant = null,
        ).toList()

        val done = chunks.filterIsInstance<GraphChunk.Done>().single()
        assertEquals(childBody, done.explanation.body)

        val reloaded = sessions.findById(session.id)!!
        assertEquals(2, reloaded.nodes.size)

        val child = reloaded.nodes.last()
        // Every field, for the same reason as the root above: a count and a key would survive a
        // child stored under the wrong parent, verb, variant or depth, and each of those quietly
        // changes what a later ContextChain.pathTo assembles for the prompt.
        assertEquals(session.rootNodeId, child.parentNodeId)
        assertEquals(done.explanation.key, child.explanationKey)
        assertEquals("behavior of matter", child.span)
        assertEquals(Verb.EXPLAIN, child.verb)
        assertEquals(0, child.variant)
        assertEquals(1, child.depth)
        assertEquals(FIXED_NOW, child.createdAtEpochMillis)

        assertEquals(child.nodeId, reloaded.currentNodeId, "the learner is now standing on the new node")
        assertEquals(session.rootNodeId, reloaded.nodes.first().nodeId, "the root must stay first")
    }

    @Test
    fun `the model is given the topic, every step of the ancestry in order, and the span's own sentence`() = runTest {
        // The product's one promise, at the only depth where it can actually be tested: with two
        // links in the chain, "the ancestry reached the model" is distinguishable from "the parent
        // body reached the model".
        llm.bodyByPromptSubstring["fundamental physical theory"] = twoSentenceBody
        val (session, _) = newSession()

        service.explain(
            session.id, session.rootNodeId,
            selectionOf(seedBody, "fundamental physical theory"), Verb.EXPLAIN, null,
        ).toList()
        val child = sessions.findById(session.id)!!.nodes.last()

        service.explain(
            session.id, child.nodeId,
            selectionOf(twoSentenceBody, "too little energy"), Verb.EXPLAIN, null,
        ).toList()

        val prompt = llm.calls.last().userPrompt

        assertTrue(prompt.contains("Quantum Physics"), "the topic TITLE from the catalogue must reach the model")
        assertTrue(prompt.contains(seedBody), "the seed the learner read first must reach the model")
        assertTrue(prompt.contains(twoSentenceBody), "the parent the span was highlighted in must reach the model")
        assertTrue(
            prompt.contains("They have now highlighted: \"too little energy\""),
            "the highlighted span must reach the model",
        )
        assertTrue(
            prompt.contains("It appeared in this sentence: \"$firstSentence\""),
            "the sentence the span came from must reach the model, and must be the span's own",
        )

        // Order, not just presence. "the topic introduction" is how the seed's empty span is
        // rendered; an ancestry assembled leaf-first, or one that dropped the root, reads to the
        // model as a different learner's path.
        val seedAt = prompt.indexOf("1. They highlighted \"the topic introduction\"")
        val childAt = prompt.indexOf("2. They highlighted \"fundamental physical theory\"")
        assertTrue(seedAt >= 0, "the seed must be the FIRST ancestor, rendered as the topic introduction")
        assertTrue(childAt > seedAt, "the ancestry must be root-first: the seed before the span drilled from it")
    }

    @Test
    fun `appending a node takes a single reading of the clock`() = runTest {
        // Two readings can straddle a millisecond boundary and leave a node whose creation time is
        // not the moment the session was last active. A constant clock cannot see that, so this one
        // advances on every call.
        var tick = 0L
        val svc = serviceWith(clock = { FIXED_NOW + tick++ })
        val (session, _) = svc.create("anon:alice", "quantum-physics")

        svc.explain(
            session.id, session.rootNodeId,
            selectionOf(seedBody, "behavior of matter"), Verb.EXPLAIN, null,
        ).toList()

        val reloaded = sessions.findById(session.id)!!
        assertEquals(
            reloaded.nodes.last().createdAtEpochMillis,
            reloaded.lastActiveAtEpochMillis,
            "a node cannot have been created at a different moment from the activity that created it",
        )
    }

    @Test
    fun `explain refuses Verb SEED, which is the create path and not a learner action`() = runTest {
        val (session, seed) = newSession()
        val selection = selectionOf(seedBody, "behavior of matter")
        val callsBefore = llm.calls.size

        assertFailsWith<IllegalArgumentException> {
            service.explain(session.id, session.rootNodeId, selection, Verb.SEED, null).toList()
        }

        assertEquals(callsBefore, llm.calls.size)
        assertEquals(1, sessions.findById(session.id)!!.nodes.size)

        // Why it must be refused outright rather than merely discouraged: keyFor BRANCHES on SEED
        // and ignores parentKey, span and variant entirely. The learner's request would therefore
        // have resolved to the topic's opening paragraph — already in the store, because create put
        // it there — and been handed back, free and instant, as the answer to their highlight.
        assertEquals(
            seed.key,
            graph.keyFor(
                GraphRequest(
                    topicSlug = "quantum-physics",
                    topicTitle = "Quantum Physics",
                    parentKey = session.nodes.single().explanationKey,
                    ancestors = emptyList(),
                    span = "behavior of matter",
                    spanSentence = seedBody,
                    verb = Verb.SEED,
                    variant = 2,
                    depth = 7,
                )
            ),
            "a SEED request collapses onto the topic's seed key whatever else it carries",
        )
    }

    @Test
    fun `the ancestry is labelled by root-ness, not by verb, so a SEED node at depth cannot erase a real span`() = runTest {
        llm.bodyByPromptSubstring["fundamental physical theory"] = twoSentenceBody
        val (session, _) = newSession()
        service.explain(
            session.id, session.rootNodeId,
            selectionOf(seedBody, "fundamental physical theory"), Verb.EXPLAIN, null,
        ).toList()
        val child = sessions.findById(session.id)!!.nodes.last()

        // `explain` now refuses to create one of these, but that closes only one of the two doors.
        // `appendNode` is the write primitive and Task 1.9 documented that it validates nothing, and
        // any session written before that refusal existed looks exactly like this.
        val mislabelled = SessionNode(
            nodeId = "seed-verb-at-depth",
            parentNodeId = session.rootNodeId,
            explanationKey = child.explanationKey,
            span = "behavior of matter",
            verb = Verb.SEED,
            variant = 0,
            depth = 1,
            createdAtEpochMillis = FIXED_NOW,
        )
        sessions.appendNode(session.id, mislabelled, FIXED_NOW)

        service.explain(
            session.id, mislabelled.nodeId,
            selectionOf(twoSentenceBody, "too little energy"), Verb.EXPLAIN, null,
        ).toList()

        val prompt = llm.calls.last().userPrompt
        assertTrue(
            prompt.contains("2. They highlighted \"behavior of matter\""),
            "the learner's real span must survive: keying on the verb replaces it with the seed's label",
        )
        assertEquals(
            1,
            Regex("the topic introduction").findAll(prompt).count(),
            "exactly one node in any chain is the topic introduction, and it is the one with no parent",
        )
    }

    // ------------------------------------------------------------------ the injection gate

    @Test
    fun `a span that does not sit at the given offsets is rejected`() = runTest {
        val (session, _) = newSession()

        // The product promise, stated as an attack: a learner reading about quantum physics must not
        // be able to get the model talking about bacteria by labelling a real range with text of
        // their own.
        assertFailsWith<SpanMismatchException> {
            service.explain(
                session.id, session.rootNodeId,
                SpanSelection("bacteria and cells", 0, 18), Verb.EXPLAIN, null,
            ).toList()
        }

        assertEquals(1, llm.calls.size, "only the seed may have been generated; the gate is before the model")
        assertEquals(1, sessions.findById(session.id)!!.nodes.size)
    }

    @Test
    fun `a span that is present elsewhere in the parent body but not at the given offsets is rejected`() = runTest {
        val (session, _) = newSession()

        val text = "the scale of atoms"
        val elsewhere = seedBody.indexOf("the fundamental ph")
        // The premises, asserted rather than assumed — this test is worthless if either is false.
        assertTrue(seedBody.contains(text), "the text must really occur in the parent body")
        assertNotEquals(
            seedBody.indexOf(text), elsewhere,
            "the offsets must point somewhere other than where the text actually is",
        )
        assertEquals(text.length, "the fundamental ph".length, "the decoy window must be the same length")

        assertFailsWith<SpanMismatchException> {
            service.explain(
                session.id, session.rootNodeId,
                SpanSelection(text, elsewhere, elsewhere + text.length), Verb.EXPLAIN, null,
            ).toList()
        }
    }

    @Test
    fun `a span differing from the parent body only in capitalisation is rejected`() = runTest {
        val (session, _) = newSession()

        // seedBody[0..7] is "Quantum". Lowercase "quantum" occurs nowhere in it, so this pins the
        // narrow property its name claims — an exact, case-sensitive comparison — and nothing more.
        assertEquals("Quantum", seedBody.substring(0, 7))
        assertFalse(seedBody.contains("quantum"))

        assertFailsWith<SpanMismatchException> {
            service.explain(
                session.id, session.rootNodeId,
                SpanSelection("quantum", 0, 7), Verb.EXPLAIN, null,
            ).toList()
        }
    }

    @Test
    fun `span offsets outside the parent body are rejected rather than crashing`() = runTest {
        val (session, _) = newSession()

        // Without the range check these are StringIndexOutOfBoundsException — a 500 for what is
        // plainly a bad request, and one that no error mapping keyed on a domain type can catch.
        val outOfRange = listOf(
            SpanSelection("Quantum", -1, 7),
            SpanSelection("atoms", seedBody.length - 5, seedBody.length + 1),
            SpanSelection("", 5, 5),
            SpanSelection("sdrawkcab", 20, 10),
        )

        outOfRange.forEach { selection ->
            assertFailsWith<SpanMismatchException>("$selection must be refused") {
                service.explain(session.id, session.rootNodeId, selection, Verb.EXPLAIN, null).toList()
            }
        }
    }

    @Test
    fun `a span of nothing but whitespace is rejected`() = runTest {
        val (session, _) = newSession()

        // This one really does sit where it says it does, so the offset and text checks both pass it
        // — and the model is then asked to explain `" "`, under a content key minted forever.
        val space = seedBody.indexOf(' ')
        assertEquals(" ", seedBody.substring(space, space + 1), "the fixture must genuinely match")

        assertFailsWith<SpanMismatchException> {
            service.explain(
                session.id, session.rootNodeId,
                SpanSelection(" ", space, space + 1), Verb.EXPLAIN, null,
            ).toList()
        }
    }

    // ------------------------------------------------------------------ the span sentence

    @Test
    fun `the span sentence is not cut short by a decimal point in the parent body`() = runTest {
        val (session, _) = newSession()
        service.explain(
            session.id, session.rootNodeId,
            selectionOf(seedBody, "behavior of matter"), Verb.EXPLAIN, null,
        ).toList()
        val child = sessions.findById(session.id)!!.nodes.last()

        // "traditional laws of physics" sits AFTER "0.1" in the parent body. Treating every '.' as a
        // terminator hands the model "1 nanometers where the traditional laws of physics collapse."
        // as the sentence the learner highlighted in.
        val done = service.explain(
            session.id, child.nodeId,
            selectionOf(childBody, "traditional laws of physics"), Verb.EXPLAIN, null,
        ).toList().filterIsInstance<GraphChunk.Done>().single()

        assertEquals(
            childBody,
            done.explanation.spanSentence,
            "the parent body is one sentence; a decimal point does not make it two",
        )
    }

    @Test
    fun `the span sentence contains the whole span even when the span contains a full stop`() = runTest {
        val (session, _) = newSession()
        service.explain(
            session.id, session.rootNodeId,
            selectionOf(seedBody, "behavior of matter"), Verb.EXPLAIN, null,
        ).toList()
        val child = sessions.findById(session.id)!!.nodes.last()

        val done = service.explain(
            session.id, child.nodeId,
            selectionOf(childBody, "0.1 nanometers"), Verb.EXPLAIN, null,
        ).toList().filterIsInstance<GraphChunk.Done>().single()

        // Scanning forward from the span's FIRST character finds the dot inside the span itself and
        // ends the sentence in the middle of what the learner highlighted.
        assertTrue(
            done.explanation.spanSentence!!.contains("0.1 nanometers"),
            "the sentence must contain the span it exists to give context to, got: ${done.explanation.spanSentence}",
        )
        assertEquals(childBody, done.explanation.spanSentence)
    }

    @Test
    fun `the span sentence is the sentence the span sits in, and abbreviations do not start a new one`() = runTest {
        llm.bodyByPromptSubstring["fundamental physical theory"] = twoSentenceBody
        val (session, _) = newSession()
        service.explain(
            session.id, session.rootNodeId,
            selectionOf(seedBody, "fundamental physical theory"), Verb.EXPLAIN, null,
        ).toList()
        val child = sessions.findById(session.id)!!.nodes.last()

        suspend fun sentenceFor(span: String): String? = service.explain(
            session.id, child.nodeId,
            selectionOf(twoSentenceBody, span), Verb.EXPLAIN, null,
        ).toList().filterIsInstance<GraphChunk.Done>().single().explanation.spanSentence

        // The counterweight, and it must stay green: a boundary that really is there must still be
        // found, or "never split" would pass every other assertion in this test by doing nothing.
        assertEquals(firstSentence, sentenceFor("too little energy"))

        // After "e.g." — whose dot IS followed by a space, so rule 1 cannot save it.
        assertEquals(secondSentence, sentenceFor("a factor of ten"))

        // After "0.1", inside the second sentence: the decimal must not be a boundary AND the real
        // boundary before it must still be.
        assertEquals(secondSentence, sentenceFor("nanometers added"))

        // Dragging a highlight across a sentence boundary is an ordinary gesture, and the sentence
        // handed to the model must still contain everything the learner selected. Scanning forward
        // from the span's FIRST character ends the sentence in the middle of their own highlight.
        assertEquals(twoSentenceBody, sentenceFor("to climb. The odds"))
    }

    @Test
    fun `a sentence ending on a bare initial or an abbreviation-shaped word is not merged into the next`() = runTest {
        // The other error direction, and the one the implementation calls worse: a rule 2 that is
        // too eager does not shorten the context, it runs two sentences together and at the limit
        // hands back the whole body — the very failure rule 1 exists to prevent, arriving through
        // the door meant to fix it.
        llm.bodyByPromptSubstring["fundamental physical theory"] = initialBody
        val (session, _) = newSession()
        service.explain(
            session.id, session.rootNodeId,
            selectionOf(seedBody, "fundamental physical theory"), Verb.EXPLAIN, null,
        ).toList()
        val child = sessions.findById(session.id)!!.nodes.last()

        suspend fun sentenceFor(span: String): String? = service.explain(
            session.id, child.nodeId,
            selectionOf(initialBody, span), Verb.EXPLAIN, null,
        ).toList().filterIsInstance<GraphChunk.Done>().single().explanation.spanSentence

        // "…write that strength as B." — a bare single letter treated as an initial swallows the
        // whole sentence that follows it. Physics and biology prose reaches for this shape freely.
        assertEquals(msSentence, sentenceFor("rise and fall"))

        // "…in under 20 ms." — here "ms" is milliseconds, not a title, and it ends the sentence.
        // Same for "no.", "fig.", "ref." and "the 21st.": every one is an ordinary word, so none of
        // them may be on the abbreviation list.
        assertEquals(conventionSentence, sentenceFor("conventional"))
    }

    // ------------------------------------------------------------------ the ceilings

    @Test
    fun `a variant beyond the ceiling is rejected before anything is generated`() = runTest {
        val (session, _) = newSession()
        val selection = selectionOf(seedBody, "behavior of matter")
        val ceiling = SessionLimits().maxVariants
        val callsBefore = llm.calls.size

        assertFailsWith<VariantLimitException> {
            service.explain(session.id, session.rootNodeId, selection, Verb.SIDE_VIEW, ceiling + 1).toList()
        }
        assertEquals(callsBefore, llm.calls.size, "the ceiling must be checked before the model is paid")
        assertEquals(1, sessions.findById(session.id)!!.nodes.size)

        // The ceiling itself is allowed. Without this the check could be `>=` — or `> 0` — and the
        // assertion above would not notice.
        service.explain(session.id, session.rootNodeId, selection, Verb.SIDE_VIEW, ceiling).toList()
        assertEquals(ceiling, sessions.findById(session.id)!!.nodes.last().variant)
    }

    @Test
    fun `a negative variant is rejected, so the ceiling cannot be walked around from below`() = runTest {
        val (session, _) = newSession()
        val selection = selectionOf(seedBody, "behavior of matter")
        val callsBefore = llm.calls.size

        listOf(-1, -2, Int.MIN_VALUE).forEach { variant ->
            assertFailsWith<VariantLimitException>("variant $variant must be refused") {
                service.explain(session.id, session.rootNodeId, selection, Verb.SIDE_VIEW, variant).toList()
            }
        }

        // A ceiling tested only from above is not a ceiling. Every negative is <= maxVariants and
        // every one is a distinct ContentKey.derive(…, variant.toString(), …), so each is a fresh
        // PAID generation of the same span under the same verb, without bound. A stored negative
        // would also break ContextChain.highestVariant, whose contract is that 0 back means
        // "untouched".
        assertEquals(callsBefore, llm.calls.size)
        assertEquals(1, sessions.findById(session.id)!!.nodes.size)

        // 0 is still permitted: this is a bound, not a ban on the original answer.
        service.explain(session.id, session.rootNodeId, selection, Verb.EXPLAIN, 0).toList()
        assertEquals(0, sessions.findById(session.id)!!.nodes.last().variant)
    }

    @Test
    fun `a session that has reached its node ceiling is refused before anything is generated`() = runTest {
        val svc = serviceWith(SessionLimits(maxDepth = 12, maxNodes = 2, maxVariants = 3))
        val (session, _) = svc.create("anon:alice", "quantum-physics")
        val selection = selectionOf(seedBody, "behavior of matter")

        // One node so far, so the second is admitted.
        svc.explain(session.id, session.rootNodeId, selection, Verb.EXPLAIN, null).toList()
        assertEquals(2, sessions.findById(session.id)!!.nodes.size)

        val callsBefore = llm.calls.size
        assertFailsWith<SessionFullException> {
            svc.explain(
                session.id, session.rootNodeId,
                selectionOf(seedBody, "the scale of atoms"), Verb.EXPLAIN, null,
            ).toList()
        }

        assertEquals(callsBefore, llm.calls.size, "a full session must be refused before the model is paid")
        assertEquals(2, sessions.findById(session.id)!!.nodes.size)
    }

    @Test
    fun `a chain that has reached its depth ceiling is refused before anything is generated`() = runTest {
        val svc = serviceWith(SessionLimits(maxDepth = 1, maxNodes = 200, maxVariants = 3))
        val (session, _) = svc.create("anon:alice", "quantum-physics")

        // Depth 1 is inside a ceiling of 1: the limit counts links below the root.
        svc.explain(
            session.id, session.rootNodeId,
            selectionOf(seedBody, "behavior of matter"), Verb.EXPLAIN, null,
        ).toList()
        val child = sessions.findById(session.id)!!.nodes.last()
        assertEquals(1, child.depth)

        val callsBefore = llm.calls.size
        assertFailsWith<DepthLimitException> {
            svc.explain(
                session.id, child.nodeId,
                selectionOf(childBody, "traditional laws of physics"), Verb.EXPLAIN, null,
            ).toList()
        }

        assertEquals(callsBefore, llm.calls.size, "a chain at its ceiling must be refused before the model is paid")
        assertEquals(2, sessions.findById(session.id)!!.nodes.size)
    }

    @Test
    fun `a side view with no requested variant is numbered from one, so it never reuses the original`() = runTest {
        val (session, _) = newSession()
        val selection = selectionOf(seedBody, "behavior of matter")

        val explain = service.explain(session.id, session.rootNodeId, selection, Verb.EXPLAIN, null)
            .toList().filterIsInstance<GraphChunk.Done>().single().explanation
        val firstSideView = service.explain(session.id, session.rootNodeId, selection, Verb.SIDE_VIEW, null)
            .toList().filterIsInstance<GraphChunk.Done>().single().explanation
        val secondSideView = service.explain(session.id, session.rootNodeId, selection, Verb.SIDE_VIEW, null)
            .toList().filterIsInstance<GraphChunk.Done>().single().explanation

        val variants = sessions.findById(session.id)!!.nodes.drop(1).map { it.verb to it.variant }
        assertEquals(
            listOf(Verb.EXPLAIN to 0, Verb.SIDE_VIEW to 1, Verb.SIDE_VIEW to 2),
            variants,
            "a first answer is variant 0 and a regeneration is numbered from 1 — see ContextChain.highestVariant",
        )

        // The consequence, which is the reason the numbering matters: a regeneration numbered 0
        // would collide with the original's content key and the store would serve the learner the
        // very text they asked to be told differently.
        assertEquals(
            3,
            setOf(explain.key, firstSideView.key, secondSideView.key).size,
            "three different asks must be three different content keys",
        )
    }

    // ------------------------------------------------------------------ the quota seam

    @Test
    fun `a plan says whether executing it will generate, so the API layer can gate on quota`() = runTest {
        val (session, _) = newSession()
        val selection = selectionOf(seedBody, "behavior of matter")

        val first = service.prepare(session.id, session.rootNodeId, selection, Verb.EXPLAIN, null)
        assertFalse(first.cached, "nothing has been generated for this span yet, so executing it will spend")
        assertEquals(1, llm.calls.size, "preparing must not call the model — only the seed has been generated")

        val chunks = service.explain(first).toList()
        assertEquals(
            first.contentKey,
            chunks.filterIsInstance<GraphChunk.Meta>().single().contentKey,
            "the key the plan quoted must be the key the graph actually works on, or the gate " +
                "is deciding about a different request from the one that runs",
        )

        val second = service.prepare(session.id, session.rootNodeId, selection, Verb.EXPLAIN, null)
        assertTrue(second.cached, "the answer is in the store now, so serving it again costs nothing")

        // And it really is free — this is what lets the API layer keep serving hits while the spend
        // breaker is tripped, which is the whole reason the flag exists.
        llm.calls.clear()
        service.explain(second).toList()
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `a plan is single use, so replaying one cannot append past the node ceiling`() = runTest {
        val (session, _) = newSession()
        val plan = service.prepare(
            session.id, session.rootNodeId,
            selectionOf(seedBody, "behavior of matter"), Verb.EXPLAIN, null,
        )

        service.explain(plan).toList()
        assertEquals(2, sessions.findById(session.id)!!.nodes.size)

        // A plan carries the ceiling decisions prepare() took against the session as it was then,
        // and explain(plan) re-checks none of them. Executed N times it appends N nodes and walks
        // straight past maxNodes, on a cache hit, for free.
        val replay = assertFailsWith<IllegalStateException> { service.explain(plan).toList() }
        assertTrue(
            "already been executed" in replay.message.orEmpty(),
            "the failure must say what the caller did wrong: ${replay.message}",
        )
        assertEquals(2, sessions.findById(session.id)!!.nodes.size, "a replayed plan must append nothing")

        // And the documented remedy works: prepare again.
        service.explain(
            service.prepare(
                session.id, session.rootNodeId,
                selectionOf(seedBody, "behavior of matter"), Verb.EXPLAIN, null,
            )
        ).toList()
        assertEquals(3, sessions.findById(session.id)!!.nodes.size)
    }

    @Test
    fun `create reports whether the topic's seed still has to be generated`() = runTest {
        assertTrue(service.createWillGenerate("quantum-physics"))

        newSession()

        assertFalse(service.createWillGenerate("quantum-physics"), "the seed is in the store now")
        assertTrue(
            service.createWillGenerate("microbiology"),
            "a seed is keyed by its topic, so another topic is still a generation",
        )
        // The gate must not answer questions create itself would refuse.
        assertFailsWith<IllegalArgumentException> { service.createWillGenerate("no-such-topic") }
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun `a learner who leaves the moment the answer lands leaves no node behind`() = runTest {
        val (session, _) = newSession()
        val selection = selectionOf(seedBody, "behavior of matter")

        // `first { }` aborts the flow from inside the emit that delivers Done — a CancellationException
        // on exactly the same path a learner navigating away produces, timed at the one moment where
        // the explanation exists but the node does not.
        val done = service.explain(session.id, session.rootNodeId, selection, Verb.EXPLAIN, null)
            .first { it is GraphChunk.Done } as GraphChunk.Done

        assertEquals(
            done.explanation, explanations.findByKey(done.explanation.key),
            "the generation completed and was paid for: the document is in the store",
        )
        assertEquals(
            1, sessions.findById(session.id)!!.nodes.size,
            "and the session does NOT record a step the learner abandoned — see SessionService.explain",
        )

        // Which is affordable precisely because identity is content addressed: asking again is a
        // hit, and the node arrives then.
        llm.calls.clear()
        val chunks = service.explain(session.id, session.rootNodeId, selection, Verb.EXPLAIN, null).toList()

        assertEquals(0, llm.calls.size, "re-asking after an abandoned answer must not pay for it twice")
        assertTrue(chunks.filterIsInstance<GraphChunk.Meta>().single().cached)
        assertEquals(2, sessions.findById(session.id)!!.nodes.size)
    }

    // ------------------------------------------------------------------ the error types

    @Test
    fun `a node pointing at an explanation the store does not have is corruption, not caller error`() = runTest {
        val (session, _) = newSession()
        database.getCollection<org.bson.Document>("explanations").drop()

        val fromExplain: Throwable = assertFailsWith<CorruptSessionException> {
            service.explain(
                session.id, session.rootNodeId,
                selectionOf(seedBody, "behavior of matter"), Verb.EXPLAIN, null,
            ).toList()
        }
        // The whole reason Task 1.9 made this a distinct type: `catch (e: IllegalArgumentException)
        // -> 400` must not answer a data-corruption incident with "your request was invalid".
        // Asserted before the cast below, or the smart cast makes it statically true and free.
        assertFalse(
            fromExplain is IllegalArgumentException,
            "a dangling stored reference is not the caller's mistake",
        )
        assertEquals(session.id, (fromExplain as CorruptSessionException).sessionId)

        // load reads the same store and must not disagree about it by quietly returning a map with
        // a hole in it.
        assertFailsWith<CorruptSessionException> { service.load(session.id) }
    }

    @Test
    fun `an unknown session and an unknown node raise the established, different types`() = runTest {
        val (session, _) = newSession()
        val selection = selectionOf(seedBody, "behavior of matter")

        // Gone or never existed -> 404. The same type SessionRepository.appendNode already raises
        // for the same condition, so one stale client cannot get a 404 and another a 400.
        assertFailsWith<SessionNotFoundException> {
            service.explain("no-such-session", session.rootNodeId, selection, Verb.EXPLAIN, null).toList()
        }

        // A node id the caller made up -> 400, from ContextChain.pathTo, and it must not have been
        // relabelled on the way through.
        val badNode: Throwable = assertFailsWith<IllegalArgumentException> {
            service.explain(session.id, "no-such-node", selection, Verb.EXPLAIN, null).toList()
        }
        assertFalse(badNode is CorruptSessionException, "an invented node id is not corruption")
    }

    // ------------------------------------------------------------------ load

    @Test
    fun `load returns the session with every referenced explanation resolved`() = runTest {
        val (session, seed) = newSession()
        service.explain(
            session.id, session.rootNodeId,
            selectionOf(seedBody, "behavior of matter"), Verb.EXPLAIN, null,
        ).toList()

        val (reloaded, bodies) = service.load(session.id)!!

        assertEquals(sessions.findById(session.id), reloaded)
        assertEquals(2, reloaded.nodes.size)
        assertTrue(reloaded.nodes.all { bodies.containsKey(it.explanationKey) })
        // The documents, not merely the keys: a map that answered with the wrong explanation for a
        // key would satisfy containsKey and render somebody else's answer on the learner's tree.
        assertEquals(seed, bodies[seed.key])
        assertEquals(childBody, bodies.getValue(reloaded.nodes.last().explanationKey).body)
        assertEquals(2, bodies.size, "one entry per distinct key, and nothing else")
    }

    @Test
    fun `load returns null for an unknown session`() = runTest {
        assertNull(service.load("no-such-session"))
    }

    private companion object {
        const val FIXED_NOW = 1_764_000_000_000L
    }
}
