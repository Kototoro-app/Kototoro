package org.skepsun.kototoro.scrobbling.common.domain

import org.skepsun.kototoro.parsers.model.ContentChapter

internal fun resolveAbsoluteChapterNumber(
	chapters: List<ContentChapter>,
	targetChapter: ContentChapter
): Int {
	val branchChapters = chapters.filter { it.branch == targetChapter.branch }
	if (branchChapters.isEmpty()) return 1

	// 1. Determine list direction: ascending (oldest first) vs descending (newest first)
	var ascendingCount = 0
	var descendingCount = 0
	var prevNumForDir: Float? = null
	for (ch in branchChapters) {
		val num = ch.number
		if (num > 0f) {
			if (prevNumForDir != null) {
				if (num > prevNumForDir) {
					ascendingCount++
				} else if (num < prevNumForDir) {
					descendingCount++
				}
			}
			prevNumForDir = num
		}
	}
	val isNewestFirst = descendingCount > ascendingCount
	val chronologicalChapters = if (isNewestFirst) branchChapters.reversed() else branchChapters

	// 2. Compute absolute numbers using season reset detection
	var seasonOffset = 0f
	var prevValidNumber = 0f
	var targetAbsoluteNumber = -1

	fun extractSeason(title: String?, url: String?): Int? {
		val patterns = listOf(
			Regex("""\bS(?:eason)?\s*(\d+)\b""", RegexOption.IGNORE_CASE),
			Regex("""\bPart\s*(\d+)\b""", RegexOption.IGNORE_CASE),
			Regex("""/s(\d+)(?:[/-]|$)""", RegexOption.IGNORE_CASE),
			Regex("""[/-]s(\d+)(?:[/-]|$)""", RegexOption.IGNORE_CASE)
		)
		title?.let { t ->
			for (pattern in patterns) {
				pattern.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
			}
		}
		url?.let { u ->
			for (pattern in patterns) {
				pattern.find(u)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
			}
		}
		return null
	}

	for (i in chronologicalChapters.indices) {
		val ch = chronologicalChapters[i]
		val chNum = if (ch.number > 0f) ch.number else (prevValidNumber + 1f)

		if (i > 0) {
			val prevCh = chronologicalChapters[i - 1]
			val prevChNum = if (prevCh.number > 0f) prevCh.number else prevValidNumber

			val prevSeason = extractSeason(prevCh.title, prevCh.url)
			val currSeason = extractSeason(ch.title, ch.url)

			val hasExplicitSeasonChange = currSeason != null && prevSeason != null && currSeason > prevSeason && chNum <= prevChNum
			val hasNumberReset = chNum < prevChNum && chNum <= 5f && (
				i == chronologicalChapters.size - 1 ||
				(i + 1 < chronologicalChapters.size &&
					(chronologicalChapters[i + 1].number.takeIf { it > 0f } ?: (chNum + 1f)) <= prevChNum)
			)

			if (hasExplicitSeasonChange || hasNumberReset) {
				seasonOffset += prevChNum
			}
		}

		val absoluteNumber = chNum + seasonOffset
		prevValidNumber = if (ch.number > 0f) ch.number else chNum

		if (ch.id == targetChapter.id) {
			targetAbsoluteNumber = absoluteNumber.toInt()
			break
		}
	}

	return if (targetAbsoluteNumber > 0) {
		targetAbsoluteNumber
	} else {
		if (targetChapter.number > 0f) {
			targetChapter.number.toInt()
		} else {
			branchChapters.indexOf(targetChapter) + 1
		}
	}
}
