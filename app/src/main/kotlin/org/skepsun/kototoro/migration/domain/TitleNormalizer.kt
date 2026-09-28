package org.skepsun.kototoro.migration.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Canonical form used to compare titles across sources: NFKC folds full-width forms,
 * lowercasing removes case, and only letters and digits survive, so spacing and
 * punctuation differences between sources do not matter.
 */
object TitleNormalizer {

    fun normalize(title: String): String {
        val folded = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return buildString(folded.length) {
            for (char in folded) {
                if (char.isLetterOrDigit()) append(char)
            }
        }
    }
}
