package com.mytetz.graph

/**
 * Bounds the size of a diagram's source text.
 *
 * A diagram this project asks the model for is a handful of simple shapes, not an illustration —
 * a legitimate answer is a few thousand characters at most. [maxSourceChars] defaults to
 * [DEFAULT_MAX_SOURCE_CHARS], and is overridable from the environment with a safe fallback, the
 * rule every config value in this project follows. This bound also limits total element count to a
 * low number of thousands, since a bare element's own markup costs at least a handful of
 * characters. It does not bound nesting depth on its own — [SvgSanitizer]'s own depth bound covers
 * that gap, because a document built from one short, repeated element can nest far deeper than this
 * character budget alone would suggest.
 *
 * This class reuses [ValidationResult], the sealed type [ExplanationValidator] already returns,
 * rather than a second type of the same shape.
 */
class MediaValidator(
    private val maxSourceChars: Int = DEFAULT_MAX_SOURCE_CHARS,
) {

    fun validate(source: String): ValidationResult =
        if (source.length > maxSourceChars) {
            ValidationResult.Invalid("diagram source longer than $maxSourceChars characters")
        } else {
            ValidationResult.Valid(source)
        }

    companion object {

        const val MAX_SOURCE_CHARS_ENV: String = "MYTETZ_MEDIA_MAX_SOURCE_CHARS"
        const val DEFAULT_MAX_SOURCE_CHARS: Int = 20_000

        /**
         * A missing, unparseable or non-positive override falls back to the default rather than
         * throwing. This is read while the process is starting; a typo in a deployment
         * environment variable must not take the server down, and the default is the safe value —
         * the same reasoning, and the same shape, as `GraphConfig.resolveMaxOutputTokens`.
         */
        internal fun resolveMaxSourceChars(raw: String?): Int =
            raw?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_SOURCE_CHARS
    }
}
