package org.skepsun.kototoro.migration.domain

import java.util.Locale

/**
 * Android Unicode/locale adapter for the shared Mihon-derived title matching rules.
 */
object TitleSimilarity {

    const val MIN_ELIGIBLE = TitleMatchingRules.MIN_ELIGIBLE

    private val titleUnicodeRegex = Regex("[^\\p{L}0-9- ]")

    fun similarity(a: String, b: String): Double =
        TitleMatchingRules.similarity(a, b, TitleNormalizer::normalize)

    fun bestSimilarity(left: Collection<String>, right: Collection<String>): Double =
        TitleMatchingRules.bestSimilarity(left, right, TitleNormalizer::normalize)

    fun cleanDeepSearchTitle(title: String): String =
        TitleMatchingRules.cleanDeepSearchTitle(title.lowercase(Locale.getDefault())) {
            it.replace(titleUnicodeRegex, " ")
        }

    fun deepSearchQueries(cleanedTitle: String): List<String> =
        TitleMatchingRules.deepSearchQueries(cleanedTitle)
}
