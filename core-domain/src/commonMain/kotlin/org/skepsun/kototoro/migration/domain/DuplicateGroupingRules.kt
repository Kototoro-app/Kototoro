package org.skepsun.kototoro.migration.domain

import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.data.LibraryTrackingId

data class DuplicateGroupEntry(val row: LibraryRow, val sourceBroken: Boolean)

data class LibraryDuplicateGroup(
    /** Stable identity of the group (sorted entry ids), used to remember "not duplicates". */
    val key: String,
    val entries: List<DuplicateGroupEntry>,
    val recommendedId: Long,
    /** Some entry joined only through a fuzzy match, so the group deserves a closer look. */
    val fuzzy: Boolean = false,
)

/**
 * Finds favourites that are the same work, among entries of one content family, linked transitively (union-find):
 *
 * - exact: a normalized title or alt title is equal ("A" ~ "B" via an alt title, "B" ~ "C" via 繁/简 folding), or both
 *   are bound to the same tracking-site entry by the user (a confirmed link or an account binding);
 * - fuzzy, with [fuzzyThreshold]: two titles are at least that similar (see [titleSimilarity]), also after bracketed
 *   subtitles such as "（漫画版）" or "～副标题～" and a leading English article are dropped, or both carry the same
 *   automatic tracking-site match.
 *
 * Titles with different numbers (Arabic or Chinese) never link fuzzily, so "第2季" and "第三季" or a series' volumes
 * stay apart, nor does a title that only extends another ("Dragon Ball" / "Dragon Ball Super", "海贼王" / "海贼王者"):
 * sequels and spin-offs take that shape, while a same work's extra subtitle is bracketed and dropped already. Fuzzy links still chain transitively, so a low threshold can join related works; such groups are flagged.
 * The platform supplies the title keys (Unicode folding, 繁/简) and the content family.
 */
object DuplicateGroupingRules {

    fun group(
        rows: List<LibraryRow>,
        unhealthySources: Set<String>,
        ignoredKeys: Set<String>,
        keysOf: (title: String, altTitles: List<String>) -> Set<String>,
        familyOf: (contentType: String) -> String?,
        fuzzyThreshold: Double? = null,
        trackingIds: List<LibraryTrackingId> = emptyList(),
    ): List<LibraryDuplicateGroup> {
        require(fuzzyThreshold == null || fuzzyThreshold in 0.5..1.0) { "Fuzzy threshold must be within 0.5..1" }
        val parent = IntArray(rows.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        val families = rows.map { row -> row.contentType?.let(familyOf) }
        val keys = rows.mapIndexed { index, row ->
            if (families[index] == null) emptySet() else keysOf(row.title, row.altTitleList)
        }
        // Exact keys and user-confirmed tracking entries, scoped by family: MAL anime and manga ids are distinct.
        val owner = HashMap<String, Int>()
        fun link(index: Int, scoped: String) {
            val other = owner.getOrPut(scoped) { index }
            if (other != index) parent[find(index)] = find(other)
        }
        rows.indices.forEach { index ->
            val family = families[index] ?: return@forEach
            for (key in keys[index]) link(index, "$family|t|$key")
        }
        val rowIndex = rows.indices.associateBy { rows[it].id }
        val autoTracked = ArrayList<Pair<Int, String>>()
        for (id in trackingIds) {
            val index = rowIndex[id.mangaId] ?: continue
            val family = families[index] ?: continue
            val scoped = "$family|r|${id.service}:${id.remoteId}"
            if (id.manual) link(index, scoped) else autoTracked += index to scoped
        }

        // Entries that the exact rules already joined need no fuzzy comparison.
        val fuzzyLinked = HashSet<Int>()
        if (fuzzyThreshold != null && fuzzyThreshold < 1.0) {
            fun fuzzyLink(left: Int, right: Int) {
                if (find(left) == find(right)) return
                parent[find(left)] = find(right)
                fuzzyLinked += left
                fuzzyLinked += right
            }
            val autoOwner = HashMap<String, Int>()
            for ((index, scoped) in autoTracked) {
                val other = autoOwner.getOrPut(scoped) { index }
                if (other != index) fuzzyLink(index, other)
            }
            val fuzzyKeys = rows.mapIndexed { index, row ->
                if (families[index] == null) emptyList() else {
                    val stripped = keysOf(simplified(row.title), row.altTitleList.map(::simplified))
                    (keys[index] + stripped).map(::FuzzyKey)
                }
            }
            for ((family, indices) in rows.indices.groupBy { families[it] }) {
                if (family != null) linkSimilar(indices, fuzzyKeys, fuzzyThreshold, ::find, ::fuzzyLink)
            }
        }

        return rows.indices.groupBy(::find).values
            .filter { it.size > 1 }
            .map { indices ->
                val entries = indices.map { DuplicateGroupEntry(rows[it], rows[it].source in unhealthySources) }
                LibraryDuplicateGroup(
                    key = entries.map { it.row.id }.sorted().joinToString(","),
                    entries = entries.sortedWith(recommendation),
                    recommendedId = entries.minWith(recommendation).row.id,
                    fuzzy = indices.any { it in fuzzyLinked },
                )
            }
            .filterNot { it.key in ignoredKeys }
            .sortedWith(compareByDescending<LibraryDuplicateGroup> { g -> g.entries.any { it.sourceBroken } }
                .thenBy { it.entries.first().row.title })
    }

    /**
     * Similarity of two normalized title keys, 0…1: the better of the edit-distance ratio (typos, romanization
     * variants) and the Dice coefficient of their character-bigram sets (word order, CJK insertions); 0 when the keys
     * carry different numbers or one only extends the other.
     */
    fun titleSimilarity(left: String, right: String): Double {
        val a = FuzzyKey(left)
        val b = FuzzyKey(right)
        if (!comparable(a, b)) return 0.0
        return maxOf(editSimilarity(a, b), dice(a, b, a.bigrams.count { it in b.bigrams }))
    }

    private fun comparable(a: FuzzyKey, b: FuzzyKey): Boolean =
        a.numbers == b.numbers && (a.text == b.text || (a.text !in b.text && b.text !in a.text))

    private class FuzzyKey(val text: String) {
        val numbers: List<String> = numberRegex.findAll(text).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()
        val bigrams: Set<String> = (0 until text.length - 1).mapTo(HashSet()) { text.substring(it, it + 2) }
    }

    /**
     * Compares only key pairs that share enough bigrams to possibly reach [threshold] (an inverted bigram index),
     * instead of every pair in the family. Both bounds are exact: one edit changes at most two distinct bigrams, and
     * the Dice coefficient follows from the shared count. Keys sharing no bigram at all are never compared, which
     * only drops very short keys at the lowest thresholds.
     */
    private fun linkSimilar(
        indices: List<Int>,
        keys: List<List<FuzzyKey>>,
        threshold: Double,
        find: (Int) -> Int,
        link: (Int, Int) -> Unit,
    ) {
        val owners = ArrayList<Int>()
        val all = ArrayList<FuzzyKey>()
        for (index in indices) for (key in keys[index].distinctBy { it.text }) {
            // Single characters have no bigrams; their only match is the exact one.
            if (key.text.length < 2) continue
            owners += index
            all += key
        }
        val postings = HashMap<String, MutableList<Int>>()
        all.forEachIndexed { k, key -> key.bigrams.forEach { postings.getOrPut(it) { ArrayList() } += k } }
        val shared = HashMap<Int, Int>()
        for (k in all.indices) {
            shared.clear()
            for (bigram in all[k].bigrams) for (other in postings.getValue(bigram)) {
                if (other > k && owners[other] != owners[k]) shared[other] = (shared[other] ?: 0) + 1
            }
            val a = all[k]
            for ((other, common) in shared) {
                val left = owners[k]
                val right = owners[other]
                val b = all[other]
                if (find(left) == find(right) || !comparable(a, b)) continue
                if (dice(a, b, common) >= threshold - EPSILON) {
                    link(left, right)
                    continue
                }
                // (1 - 0.8) * 5 is 0.99…98 in floating point: without the tolerance one allowed edit truncates to none.
                val maxEdits = ((1 - threshold) * maxOf(a.text.length, b.text.length) + EPSILON).toInt()
                if (common >= maxOf(a.bigrams.size, b.bigrams.size) - 2 * maxEdits &&
                    editSimilarity(a, b) >= threshold - EPSILON
                ) {
                    link(left, right)
                }
            }
        }
    }

    // Thresholds come from slider steps (0.05 increments), so scores equal to them must not miss by rounding.
    private const val EPSILON = 1e-9

    private fun dice(a: FuzzyKey, b: FuzzyKey, common: Int): Double {
        val total = a.bigrams.size + b.bigrams.size
        return if (total == 0) 0.0 else 2.0 * common / total
    }

    private fun editSimilarity(a: FuzzyKey, b: FuzzyKey): Double = TitleMatchingRules.similarity(a.text, b.text) { it }

    private val numberRegex = Regex("[0-9]+|[零〇一二三四五六七八九十百千两]+")

    // Bracketed subtitles, editions and notes, and a leading English article; the title without them is one more
    // fuzzy key ("The Apothecary Diaries" / "Apothecary Diaries", "标题～副标题～" / "标题").
    private val bracketRegex = Regex("""[(\[（【「『〈《〔][^)\]）】」』〉》〕]*[)\]）】」』〉》〕]|[～~〜].*?(?:[～~〜]|$)""")
    private val articleRegex = Regex("""^(?:the|an?)\s+""", RegexOption.IGNORE_CASE)

    private fun simplified(title: String): String =
        title.replace(bracketRegex, " ").trim().replace(articleRegex, "").ifEmpty { title }

    /** True when [a] was read further than [b]. */
    fun isFurther(a: LibraryRow, b: LibraryRow): Boolean = progressOrder.compare(a, b) > 0

    /** Duplicates whose reading progress moves into the kept entry: only the furthest one beyond the kept entry. */
    fun progressDonor(keep: LibraryRow, duplicates: List<LibraryRow>): LibraryRow? {
        var furthest = keep
        var donor: LibraryRow? = null
        for (duplicate in duplicates) {
            if (isFurther(duplicate, furthest)) {
                furthest = duplicate
                donor = duplicate
            }
        }
        return donor
    }

    // A known chapter number beats a bare percentage; percentages only compare with each other.
    private val progressOrder = compareBy<LibraryRow> { it.historyChapterNumber ?: -1f }
        .thenBy { it.historyPercent ?: -1f }

    // Best first: working source, then furthest read, then most chapters.
    private val recommendation = compareBy<DuplicateGroupEntry> { it.sourceBroken }
        .thenDescending(compareBy(progressOrder) { it.row })
        .thenByDescending { it.row.knownChapters }
        .thenBy { it.row.id }
}
