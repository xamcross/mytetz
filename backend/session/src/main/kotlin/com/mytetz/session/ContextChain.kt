package com.mytetz.session

import com.mytetz.graph.Verb

/**
 * The stored session no longer describes a tree: a node points at a parent that is not there, the
 * parent links close into a loop, or two nodes share an id.
 *
 * Deliberately **not** an [IllegalArgumentException], and that is the whole point of the type. An
 * unknown node id is the caller's mistake and belongs in a 400; this is a document that got written
 * wrong and belongs in a 500 with somebody paged. Sharing one type with the caller error would
 * leave Task 1.11 matching on message substrings to tell them apart — and the natural handler,
 * `catch (e: IllegalArgumentException) -> 400`, would answer a data-corruption incident with "your
 * request was invalid" and nobody would ever be told.
 *
 * [sessionId] is a field rather than only a message fragment so an alert can carry the document to
 * go and look at.
 */
class CorruptSessionException(val sessionId: String, detail: String) :
    IllegalStateException("session $sessionId is corrupt: $detail")

/**
 * Assembles the ancestry a generation is answered against.
 *
 * This is the mechanism behind the product's one promise. A learner reading about *Quantum Physics*
 * who highlights "microscopic realm" must be told about the subatomic scale, never about bacteria
 * and cells — and the only reason that happens is that the prompt carries the topic first, then
 * every span the learner drilled through, then the current one. [pathTo] is what produces that
 * list; the prompt, the content key and the breadcrumb are all built from what it returns.
 *
 * So every failure in here is raised, never absorbed. A chain that is merely *shorter* than it
 * should be is indistinguishable, to every caller and to the learner, from a correct one — it is a
 * well-formed list of the right type, and the answer it produces reads perfectly normally. It is
 * simply about something else.
 */
object ContextChain {

    /**
     * Root-first path to the given node, inclusive.
     *
     * Raises [IllegalArgumentException] when [nodeId] is simply not in the session — the caller
     * asked for a node that does not exist. Raises [CorruptSessionException] when the session
     * itself is malformed: a dangling parent, a parent cycle, or a duplicate node id. The two are
     * separate types because Task 1.11 has to answer them differently; see [CorruptSessionException].
     */
    fun pathTo(session: LearningSession, nodeId: String): List<SessionNode> {
        val byId = session.nodes.associateBy { it.nodeId }

        // Two nodes under one id would make the ancestry a coin toss: `associateBy` keeps the last
        // one written, so the walk would climb whichever branch happened to be appended second and
        // report it as the learner's. Nothing enforces uniqueness on the way in — appendNode only
        // $pushes — so it is enforced here, where being wrong is expensive.
        //
        // Note the scope: this checks the WHOLE session, not just the branch being walked, so a
        // duplicate anywhere fails pathTo for every branch including healthy ones. That is
        // deliberate, not an oversight — a duplicate id means the document has stopped describing
        // one tree, and from here there is no way to know which of the two nodes any *other*
        // branch was meant to climb through. A session that cannot be read correctly should not be
        // read confidently. Anyone debugging "why did this unrelated call fail" has found the
        // right behaviour, and the message names the duplicate.
        if (byId.size != session.nodes.size) {
            val duplicates = session.nodes.groupingBy { it.nodeId }.eachCount().filterValues { it > 1 }.keys
            throw CorruptSessionException(session.id, "duplicate node ids: $duplicates")
        }

        val target = byId[nodeId]
            ?: throw IllegalArgumentException("node $nodeId not found in session ${session.id}")

        val path = ArrayDeque<SessionNode>()
        val visited = HashSet<String>()
        var current = target

        while (true) {
            // Sessions come back from Mongo unvalidated, so a document whose parent links form a
            // loop is representable. Unbounded, this walk spins and takes a request thread with it.
            if (!visited.add(current.nodeId)) {
                throw CorruptSessionException(session.id, "parent cycle through node ${current.nodeId}")
            }
            path.addFirst(current)

            val parentId = current.parentNodeId ?: break
            current = byId[parentId]
                ?: throw CorruptSessionException(
                    session.id,
                    "node ${current.nodeId} names parent $parentId, which is not in the session — " +
                        "the chain would be missing its root",
                )
        }

        return path.toList()
    }

    /**
     * Highest variant already taken for this (parent, span, verb) triple; 0 when none.
     *
     * Note what 0 does *not* distinguish: "nobody has asked this yet" and "someone asked, and it
     * was numbered 0" give the same answer. That is safe only because of the numbering convention
     * the caller keeps, and it is written here because this is where it would be broken. A first
     * answer is variant 0, and a *regeneration* — "explain that again, differently" — is numbered
     * from 1 via `highestVariant(…) + 1`. So no regenerated node ever carries variant 0, and 0 back
     * from this function means the triple is untouched. A caller that ever stores a regeneration at
     * variant 0 makes the two cases indistinguishable and will hand the learner the same content
     * key twice, which the store will serve from cache as though it were the new angle they asked
     * for.
     */
    fun highestVariant(
        session: LearningSession,
        parentNodeId: String,
        span: String,
        verb: Verb,
    ): Int = session.nodes
        .filter { it.parentNodeId == parentNodeId && it.span == span && it.verb == verb }
        .maxOfOrNull { it.variant }
        ?: 0
}
