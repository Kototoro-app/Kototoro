package org.skepsun.kototoro.tracker.domain

/**
 * Chapter numbers that can be trusted to name a chapter, after Mihon's `ChapterRecognition`. As in Mihon the number
 * the source gave wins when it is positive and no other chapter of the list has it. Chapters of a source that gave
 * none are numbered by position here (Mihon extensions are, see `MihonMangaRepository`): a list numbered exactly
 * 1..n in order is taken as such, and its numbers are recognized from the chapter titles instead, since a positional
 * number would make an unrelated chapter look like a re-upload of a removed one.
 *
 * The numbers are only used to tell a re-uploaded chapter from a new one and to find duplicates of read chapters,
 * never to decide which chapters are new.
 */
object ChapterNumberRules {

    private const val NUMBER_PATTERN = """([0-9]+)(\.[0-9]+)?(\.?[a-z]+)?"""

    /** "Mokushiroku Alice Vol.1 Ch. 4: Misrepresentation" -> 4 */
    private val basic = Regex("""(?<=ch\.) *$NUMBER_PATTERN""")

    /** "Bleach 567: Down With Snowwhite" -> 567 */
    private val number = Regex(NUMBER_PATTERN)

    /** "Prison School 12 v.1 vol004 version1243 volume64" -> "Prison School 12" */
    private val unwanted = Regex("""\b(?:v|ver|vol|version|volume|season|s)[^a-z]?[0-9]+""")

    /** "第3卷 第27话" -> " 第27话": volume, part and season markers in Chinese and Japanese titles. */
    private val unwantedCjk = Regex("""第?\s*[0-9]+\s*[卷巻冊册部季]""")

    /** "第27话" -> 27, the CJK counterpart of [basic]. */
    private val basicCjk = Regex("""(?<=第) *$NUMBER_PATTERN""")

    /** "One Piece 12 special" -> "One Piece 12special" */
    private val unwantedWhiteSpace = Regex("""\s(?=extra|special|omake)""")

    /** The number in [chapterTitle], with [workTitle] removed from it first, or null when there is none. */
    fun recognize(workTitle: String, chapterTitle: String?): Float? {
        if (chapterTitle.isNullOrBlank()) return null
        val clean = chapterTitle.lowercase()
            .let { if (workTitle.isBlank()) it else it.replace(workTitle.lowercase(), "") }
            .trim()
            .replace(',', '.')
            .replace('-', '.')
            .replace(unwantedWhiteSpace, "")
        val matches = number.findAll(clean).toList()
        if (matches.isEmpty()) return null
        if (matches.size > 1) {
            val name = unwantedCjk.replace(unwanted.replace(clean, ""), "")
            basic.find(name)?.let { return it.toNumber() }
            basicCjk.find(name)?.let { return it.toNumber() }
            number.find(name)?.let { return it.toNumber() }
        }
        return matches.first().toNumber()
    }

    /** The trusted number of every chapter in [chapters] (ascending), null where there is none. */
    fun <C> trusted(
        chapters: List<C>,
        workTitle: String,
        title: (C) -> String?,
        sourceNumber: (C) -> Float,
    ): List<Float?> {
        val positional = chapters.isNotEmpty() &&
            chapters.withIndex().all { (index, chapter) -> sourceNumber(chapter) == (index + 1).toFloat() }
        val sourceCounts = chapters.groupingBy(sourceNumber).eachCount()
        return chapters.map { chapter ->
            sourceNumber(chapter).takeIf { !positional && it > 0f && sourceCounts[it] == 1 }
                ?: recognize(workTitle, title(chapter))
        }
    }

    private fun MatchResult.toNumber(): Float? {
        val initial = groups[1]?.value?.toFloatOrNull() ?: return null
        return initial + fraction(groups[2]?.value, groups[3]?.value)
    }

    private fun fraction(decimal: String?, alpha: String?): Float {
        if (!decimal.isNullOrEmpty()) return decimal.toFloatOrNull() ?: 0f
        if (alpha.isNullOrEmpty()) return 0f
        return when {
            "extra" in alpha -> 0.99f
            "omake" in alpha -> 0.98f
            "special" in alpha -> 0.97f
            else -> {
                // x.a -> x.1, x.b -> x.2, ...
                val letter = alpha.trimStart('.')
                if (letter.length != 1) return 0f
                val position = letter[0] - 'a' + 1
                if (position in 1..9) position / 10f else 0f
            }
        }
    }
}
