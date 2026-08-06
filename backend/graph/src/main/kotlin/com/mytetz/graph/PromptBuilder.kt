package com.mytetz.graph

/** One link in the chain the learner followed to reach the current span, root-first. */
data class Ancestor(val span: String, val body: String)

/**
 * Everything the model is told about a request. Deliberately contains no user, principal or
 * session: the same topic, ancestry, span and verb must produce the same explanation for
 * everyone, which is simultaneously the cache key, the public-content pipeline and the
 * testability story.
 */
data class PromptContext(
    val topicTitle: String,
    val ancestors: List<Ancestor>,
    val span: String,
    val spanSentence: String,
    val verb: Verb,
)

/**
 * Renders the two halves of an LLM request: a topic-agnostic system prompt and a per-request
 * user prompt carrying the learner's context chain.
 *
 * ## Prompt caching
 *
 * [system] is byte-identical across every request, which is what makes it a cacheable prefix.
 * It is currently 676 characters / 117 words — on the order of 200 tokens, and Claude Opus 5's
 * minimum cacheable prefix is 512 tokens. Caching is therefore INACTIVE: the `cache_control`
 * breakpoint the Anthropic adapter sets on this prompt is inert, because a prefix under the
 * minimum silently does not cache (`cache_creation_input_tokens` stays 0). Nothing is padded to
 * reach it. Revisit if the prompt roughly triples; until then the breakpoint costs nothing and
 * starts working on its own the moment the prompt is long enough.
 */
object PromptBuilder {

    /**
     * Bump on ANY change to the system prompt or a verb instruction. It is part of the
     * content key, so bumping invalidates every downstream explanation: old documents
     * orphan harmlessly and new ones regenerate lazily. Reverting the string rolls back.
     */
    const val VERSION: String = "v3"

    /**
     * Puts a stored value on one line.
     *
     * Every interpolated value below reaches this prompt from a stored explanation body. A body is
     * model output. `ExplanationValidator` caps its length and rejects internal tags, and it does
     * not reject a newline.
     *
     * The prompt has a line structure, and that structure carries meaning. An ancestor block is a
     * numbered line and then one indented line. A newline inside a body ends the indented line, so
     * the text after it starts at column 0 and reads as a new block. The model then sees ancestry
     * that the learner does not have.
     *
     * This collapses each run of whitespace to one space, so no stored value can make a line.
     */
    private fun flattened(value: String): String = value.replace(WHITESPACE_RUN, " ").trim()

    /**
     * Puts a stored value inside quotation marks that it cannot leave.
     *
     * The span and the sentence are delimited by `"` in the lines below. A `"` inside the value
     * closes the delimiter early, and the rest of the value then sits in the prompt as though this
     * file had written it. A quotation mark in an explanation is ordinary — "the so-called "wave
     * function"" is the shape the model produces without any prompting.
     *
     * The escape is the JSON one, so the words are unchanged and a reader can see where the value
     * starts and stops. The backslash is escaped first, or escaping the quote would produce a
     * second unescaped backslash before it.
     */
    private fun quoted(value: String): String =
        "\"" + flattened(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val WHITESPACE_RUN = Regex("\\s+")

    fun system(): String = """
        You are an expert teacher writing an interactive learning workbook.

        Rules, in order of priority:
        1. Answer in 1 to 3 sentences. Never longer. This is a hard limit.
        2. Explain the highlighted span WITHIN the context you are given, never as a
           standalone dictionary lookup. If the same words mean something different in a
           different field, that other meaning is wrong here.
        3. Write plain prose for a curious beginner. No headings, no bullet points, no
           markdown, no preamble such as "Here is" or "Sure".
        4. Introduce at most one new technical term, and define it in the same sentence.
        5. If you genuinely do not know, say so in one sentence rather than inventing detail.
    """.trimIndent()

    /**
     * Every value below goes through [flattened] or [quoted]. Both are bounds on stored model
     * output, and neither changes the words. See the two helpers for what each one prevents.
     */
    fun user(context: PromptContext): String = buildString {
        appendLine("Topic: ${flattened(context.topicTitle)}")

        if (context.ancestors.isNotEmpty()) {
            appendLine()
            appendLine("What the learner has read so far, from the top down:")
            context.ancestors.forEachIndexed { index, ancestor ->
                appendLine("${index + 1}. They highlighted ${quoted(ancestor.span)} and read:")
                appendLine("   ${flattened(ancestor.body)}")
            }
        }

        appendLine()
        if (context.verb != Verb.SEED) {
            // The sentence goes to every span-bearing verb, not just the ones where it reads
            // naturally: "microscopic realm" resolves differently depending on whether its
            // sentence concerned scale or measurement, and that is true when zooming out or
            // drawing a diagram as much as when explaining.
            appendLine("They have now highlighted: ${quoted(context.span)}")
            appendLine("It appeared in this sentence: ${quoted(context.spanSentence)}")
            appendLine()
        }
        appendLine(instructionFor(context))
    }.trim()

    private fun instructionFor(context: PromptContext): String = when (context.verb) {
        // "1 to 3 sentences" repeats rule 1 on purpose. The earlier text said "the opening
        // paragraph", and the model obeyed the task and not the rule: every seed came back longer
        // than the validator's 600-character limit, so no session could start. A task instruction
        // that contradicts a rule wins. Keep every instruction inside the length that rule 1 sets.
        Verb.SEED ->
            "Write 1 to 3 sentences that introduce this topic to someone new to it."

        Verb.EXPLAIN ->
            "Explain that highlighted phrase as it is used in this context."

        Verb.DIG_DEEPER ->
            "Go one level deeper on this same subject: more specific, more technical, " +
                "the detail a curious learner would ask for next."

        Verb.BROADER_PICTURE ->
            "Zoom out. Place this in a wider framework: what larger idea it belongs to, " +
                "and what sits alongside or competes with it."

        Verb.SIDE_VIEW ->
            "Explain the same thing again from a different angle — a different analogy " +
                "or a different entry point. Do not repeat the wording they have already read."

        Verb.VISUALIZE ->
            "Describe what a diagram of ${quoted(context.span)} would show."
    }
}
