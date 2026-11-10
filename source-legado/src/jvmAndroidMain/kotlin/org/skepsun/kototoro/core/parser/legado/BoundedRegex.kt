package org.skepsun.kototoro.core.parser.legado

/** Bounds regular expressions supplied by source rules. */
object BoundedRegex {
    /**
     * Runs [Regex.find] with a wall-clock budget, so a catastrophically backtracking pattern from a source rule
     * gives up instead of pinning a thread. A pattern blacklist cannot catch every such
     * pattern; this bound is what actually limits the cost.
     *
     * @return the match, or null when there is none or the budget ran out
     */
    fun findBounded(regex: Regex, input: CharSequence, timeoutMs: Long = REGEX_TIMEOUT_MS): MatchResult? {
        val bounded = DeadlineCharSequence(input, System.nanoTime() + timeoutMs * 1_000_000L)
        return try {
            regex.find(bounded)
        } catch (_: RegexTimeoutException) {
            null
        }
    }

    /**
     * Runs [Regex.replace] under the same wall-clock budget as [findBounded], so a source rule's replacement
     * pattern cannot pin a thread either.
     *
     * @return the replaced text, or null when the budget ran out
     */
    fun replaceBounded(
        regex: Regex,
        input: CharSequence,
        replacement: String,
        timeoutMs: Long = REGEX_TIMEOUT_MS,
    ): String? {
        val bounded = DeadlineCharSequence(input, System.nanoTime() + timeoutMs * 1_000_000L)
        return try {
            regex.replace(bounded, replacement)
        } catch (_: RegexTimeoutException) {
            null
        }
    }

    /**
     * Runs [Regex.replaceFirst] under the same wall-clock budget as [findBounded].
     *
     * @return the replaced text, or null when the budget ran out
     */
    fun replaceFirstBounded(
        regex: Regex,
        input: CharSequence,
        replacement: String,
        timeoutMs: Long = REGEX_TIMEOUT_MS,
    ): String? {
        val bounded = DeadlineCharSequence(input, System.nanoTime() + timeoutMs * 1_000_000L)
        return try {
            regex.replaceFirst(bounded, replacement)
        } catch (_: RegexTimeoutException) {
            null
        }
    }

    /** A deadline for [withRegexDeadline], [timeoutMs] from now; pass one deadline to every step of one rule. */
    fun regexDeadline(timeoutMs: Long = REGEX_TIMEOUT_MS): Long = System.nanoTime() + timeoutMs * 1_000_000L

    /**
     * Runs [block] on [input] wrapped so that any regex work the block does on it, however many
     * [java.util.regex.Matcher.find] calls that takes, stops at [deadline]. Rules that match repeatedly
     * or recurse share one deadline instead of getting the full budget per call.
     *
     * @return the block's result, or null when the deadline passed
     */
    fun <T> withRegexDeadline(input: CharSequence, deadline: Long, block: (CharSequence) -> T): T? = try {
        block(DeadlineCharSequence(input, deadline))
    } catch (_: RegexTimeoutException) {
        null
    }

    private const val REGEX_TIMEOUT_MS = 2_000L

    private class RegexTimeoutException : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Checks the deadline on every 1024th character read; the regex engine reads characters constantly. */
    private class DeadlineCharSequence(
        private val delegate: CharSequence,
        private val deadline: Long,
    ) : CharSequence {

        private var reads = 0

        override val length: Int
            get() = delegate.length

        override fun get(index: Int): Char {
            if (++reads and 0x3FF == 0 && System.nanoTime() > deadline) {
                throw RegexTimeoutException()
            }
            return delegate[index]
        }

        // Match groups are read through here after find returns, so they are not bounded.
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            delegate.subSequence(startIndex, endIndex)

        override fun toString(): String = delegate.toString()
    }

}
