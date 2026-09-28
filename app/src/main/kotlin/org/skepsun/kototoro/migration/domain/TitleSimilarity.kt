package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.parsers.util.levenshteinDistance
import java.util.Locale

/**
 * Title matching helpers ported from Mihon's BaseSmartSearchEngine. Similarity is a
 * normalized Levenshtein score in 0..1 computed on [TitleNormalizer] output.
 */
object TitleSimilarity {

    const val MIN_ELIGIBLE = 0.4

    private val titleRegex = Regex("[^a-zA-Z0-9- ]")
    private val titleUnicodeRegex = Regex("[^\\p{L}0-9- ]")
    private val consecutiveSpacesRegex = Regex(" +")
    private val chapterRefCyrillicRegex = Regex("""((- часть|- глава) \d*)""")

    fun similarity(a: String, b: String): Double {
        val left = TitleNormalizer.normalize(a)
        val right = TitleNormalizer.normalize(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        val maxLength = maxOf(left.length, right.length)
        return 1.0 - left.levenshteinDistance(right).toDouble() / maxLength
    }

    fun bestSimilarity(left: Collection<String>, right: Collection<String>): Double {
        var best = 0.0
        for (a in left) {
            for (b in right) {
                val score = similarity(a, b)
                if (score > best) best = score
                if (best == 1.0) return best
            }
        }
        return best
    }

    fun cleanDeepSearchTitle(title: String): String {
        val preTitle = title.lowercase(Locale.getDefault())
        var cleaned = removeTextInBrackets(preTitle, readForward = true)
        if (cleaned.length <= 5) {
            cleaned = removeTextInBrackets(preTitle, readForward = false)
        }
        cleaned = cleaned.replace(chapterRefCyrillicRegex, " ").trim()
        val latinOnly = cleaned.replace(titleRegex, " ")
        cleaned = if (latinOnly.trim().length <= 5) {
            cleaned.replace(titleUnicodeRegex, " ")
        } else {
            latinOnly
        }
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
}
