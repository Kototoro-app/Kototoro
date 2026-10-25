package org.skepsun.kototoro.reader.novel

import kotlin.math.abs

/**
 * Where a saved highlight (`novel_markings`) lies in a chapter's text. Offsets are kept with the selected text, so a
 * highlight follows its words when the text moves (replacement rules, a source update, another platform's text
 * conversion): the stored range wins while the text there still matches, else the nearest occurrence.
 * Shared by Android's and iOS's novel readers.
 */
fun relocateNovelMarking(text: String, selectedText: String, start: Int, end: Int): IntRange? {
    val clean = selectedText.trim()
    if (clean.isEmpty() || text.isEmpty()) return null
    if (start in 0 until end && end <= text.length && text.substring(start, end).trim() == clean) {
        val offset = text.indexOf(clean, start)
        if (offset in start until end) return offset until offset + clean.length
    }
    return findNovelTextRangeNear(text, clean, start.takeIf { it >= 0 }) ?: findNovelTextRange(text, clean, start)
}

/** The occurrence of [selected] at or after [preferredStart] (else anywhere), tolerating whitespace differences. */
fun findNovelTextRange(source: String, selected: String, preferredStart: Int = 0): IntRange? {
    val cleanSelection = selected.trim()
    if (cleanSelection.isEmpty()) return null
    val exactStart = source.indexOf(cleanSelection, preferredStart.coerceAtLeast(0))
        .takeIf { it >= 0 }
        ?: source.indexOf(cleanSelection)
    if (exactStart >= 0) return exactStart until exactStart + cleanSelection.length

    val normalizedSource = normalizeNovelText(source)
    val normalizedSelection = normalizeNovelText(cleanSelection).first
    val normalizedStart = normalizedSource.first.indexOf(normalizedSelection)
    if (normalizedStart < 0) return null
    val offsets = normalizedSource.second
    val start = offsets[normalizedStart]
    val end = offsets[normalizedStart + normalizedSelection.length - 1] + 1
    return start until end
}

/** The exact occurrence of [selectedText] closest to [expectedStart] (the first one without it). */
fun findNovelTextRangeNear(text: String, selectedText: String, expectedStart: Int?): IntRange? {
    if (text.isEmpty() || selectedText.isEmpty()) return null
    var searchStart = 0
    var closest: IntRange? = null
    var closestDistance = Int.MAX_VALUE
    while (searchStart < text.length) {
        val matchStart = text.indexOf(selectedText, searchStart)
        if (matchStart < 0) break
        val matchEnd = (matchStart + selectedText.length).coerceAtMost(text.length)
        val distance = expectedStart?.let { abs(matchStart - it) } ?: 0
        if (distance < closestDistance) {
            closest = matchStart until matchEnd
            closestDistance = distance
        }
        searchStart = matchStart + 1
    }
    return closest
}

/**
 * Converts a persisted, half-open source range into the local range rendered by one text block. A marking can cross
 * page or paragraph boundaries, so every block renders its intersection.
 */
fun resolveNovelMarkingLocalRange(sourceRange: IntRange?, markingStart: Int, markingEnd: Int, textLength: Int): IntRange? {
    if (textLength <= 0 || markingEnd <= markingStart || sourceRange == null) return null
    val sourceStart = sourceRange.first
    val overlapStart = maxOf(sourceStart, markingStart)
    val overlapEnd = minOf(sourceRange.last + 1, markingEnd)
    if (overlapEnd <= overlapStart) return null
    val localStart = (overlapStart - sourceStart).coerceIn(0, textLength)
    val localEnd = (overlapEnd - sourceStart).coerceIn(localStart, textLength)
    return if (localEnd > localStart) localStart until localEnd else null
}

private fun normalizeNovelText(text: String): Pair<String, IntArray> {
    val normalized = StringBuilder(text.length)
    val offsets = ArrayList<Int>(text.length)
    var pendingWhitespace = false
    for (index in text.indices) {
        val char = text[index]
        if (char.isWhitespace()) {
            pendingWhitespace = normalized.isNotEmpty()
            continue
        }
        if (pendingWhitespace) {
            normalized.append(' ')
            offsets += index - 1
            pendingWhitespace = false
        }
        normalized.append(char)
        offsets += index
    }
    return normalized.toString() to offsets.toIntArray()
}
