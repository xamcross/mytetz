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
    const val VERSION: String = "v1"

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

    fun user(context: PromptContext): String = buildString {
        appendLine("Topic: ${context.topicTitle}")

        if (context.ancestors.isNotEmpty()) {
            appendLine()
            appendLine("What the learner has read so far, from the top down:")
            context.ancestors.forEachIndexed { index, ancestor ->
                appendLine("${index + 1}. They highlighted \"${ancestor.span}\" and read:")
                appendLine("   ${ancestor.body}")
            }
        }

        appendLine()
        if (context.verb != Verb.SEED) {
            // The sentence goes to every span-bearing verb, not just the ones where it reads
            // naturally: "microscopic realm" resolves differently depending on whether its
            // sentence concerned scale or measurement, and that is true when zooming out or
            // drawing a diagram as much as when explaining.
            appendLine("They have now highlighted: \"${context.span}\"")
            appendLine("It appeared in this sentence: \"${context.spanSentence}\"")
            appendLine()
        }
        appendLine(instructionFor(context))
    }.trim()

    private fun instructionFor(context: PromptContext): String = when (context.verb) {
        Verb.SEED ->
            "Write the opening paragraph introducing this topic to someone new to it."

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
            "Describe what a diagram of \"${context.span}\" would show."
    }
}
