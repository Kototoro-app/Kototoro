package org.skepsun.kototoro.migration.domain

/** Matching rules consume platform Unicode/locale operations without depending on the parser runtime. */
object TitleMatchingRules {

    const val MIN_ELIGIBLE = 0.4

    private val titleRegex = Regex("[^a-zA-Z0-9- ]")
    private val consecutiveSpacesRegex = Regex(" +")
    private val chapterRefCyrillicRegex = Regex("""((- часть|- глава) [0-9]*)""")

    /** The platform has already applied NFKC and invariant lowercasing, in that order. */
    fun normalizeFoldedTitle(foldedTitle: String): String = buildString(foldedTitle.length) {
        for (char in foldedTitle) {
            if (char.isLetterOrDigit()) append(char)
        }
    }

    fun similarity(a: String, b: String, normalizeTitle: (String) -> String): Double {
        val left = normalizeTitle(a)
        val right = normalizeTitle(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        return 1.0 - editDistance(left, right).toDouble() / maxOf(left.length, right.length)
    }

    fun bestSimilarity(
        left: Collection<String>,
        right: Collection<String>,
        normalizeTitle: (String) -> String,
    ): Double {
        var best = 0.0
        for (a in left) {
            for (b in right) {
                val score = similarity(a, b, normalizeTitle)
                if (score > best) best = score
                if (best == 1.0) return best
            }
        }
        return best
    }

    /** Lowercasing and the Unicode-letter filter retain the platform's existing locale/category semantics. */
    fun cleanDeepSearchTitle(lowercasedTitle: String, filterUnicodeTitle: (String) -> String): String {
        var cleaned = removeTextInBrackets(lowercasedTitle, readForward = true)
        if (cleaned.length <= 5) {
            cleaned = removeTextInBrackets(lowercasedTitle, readForward = false)
        }
        cleaned = cleaned.replace(chapterRefCyrillicRegex, " ").trim()
        val latinOnly = cleaned.replace(titleRegex, " ")
        cleaned = if (latinOnly.trim().length <= 5) filterUnicodeTitle(cleaned) else latinOnly
        return cleaned.trim().replace(" - ", " ").replace(consecutiveSpacesRegex, " ").trim()
    }

    fun deepSearchQueries(cleanedTitle: String): List<String> {
        val words = cleanedTitle.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val byLength = words.sortedByDescending { it.length }
        return listOf(
            listOf(cleanedTitle),
            byLength.take(2),
            byLength.take(1),
            words.take(2),
            words.take(1),
        ).map { it.joinToString(" ").trim() }.distinct()
    }

    private fun removeTextInBrackets(text: String, readForward: Boolean): String {
        val openingChars = if (readForward) "([<{" else ")]}>"
        val closingChars = if (readForward) ")]}>" else "([<{"
        var depth = 0
        val builder = StringBuilder()
        val chars = if (readForward) text else text.reversed()
        for (char in chars) {
            when (char) {
                in openingChars -> depth++
                in closingChars -> if (depth > 0) depth--
                else -> if (depth == 0) {
                    if (readForward) builder.append(char) else builder.insert(0, char)
                }
            }
        }
        return builder.toString()
    }

    /** UTF-16 edit distance, preserving the legacy parser utility's character semantics. */
    private fun editDistance(left: String, right: String): Int {
        var costs = IntArray(left.length + 1) { it }
        var nextCosts = IntArray(left.length + 1)
        for (i in 1..right.length) {
            nextCosts[0] = i
            for (j in 1..left.length) {
                val match = if (left[j - 1] == right[i - 1]) 0 else 1
                nextCosts[j] = minOf(costs[j] + 1, nextCosts[j - 1] + 1, costs[j - 1] + match)
            }
            val previousCosts = costs
            costs = nextCosts
            nextCosts = previousCosts
        }
        return costs[left.length]
    }
}
