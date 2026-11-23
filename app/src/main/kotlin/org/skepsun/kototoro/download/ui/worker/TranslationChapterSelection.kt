package org.skepsun.kototoro.download.ui.worker

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

/**
 * The chapters a translate job works on, and the content its pages/paragraphs must be read from.
 *
 * [workContent] is the stored record, or the local copy when the stored record itself has no local chapter left
 * (novels read their paragraphs through [workContent]'s source).
 */
internal class TranslationChapterSelection(
	val workContent: Content,
	val chapters: List<IndexedValue<ContentChapter>>,
)

/**
 * The local counterpart of [chapter]: the same id first, then the same number and branch, then the same title.
 *
 * A local copy keeps the source chapter ids, but a chapter list refetched after the download can allocate different
 * ids for the same chapter, which is what the number/title fallbacks are for.
 */
internal fun matchLocalChapter(
	chapter: ContentChapter,
	localChapters: List<ContentChapter>,
): ContentChapter? {
	return localChapters.find { it.id == chapter.id }
		?: localChapters.find { chapter.number > 0f && it.number == chapter.number && it.branch == chapter.branch }
		?: localChapters.find { !chapter.title.isNullOrBlank() && it.title == chapter.title }
}

/**
 * Picks the chapters of a translate job, so a selection that still has a chapter on disk never fails as a whole.
 *
 * [requestedChapters] are the stored record's own chapters for the requested ids, [preparedChapters] resolves the
 * same ids against the local copy the way the prepare flow does, [loadLocalCopy] is the local copy itself and
 * [isEligible] tells whether a chapter has pages to translate.
 */
internal suspend fun selectTranslationChapters(
	storedContent: Content,
	requestedChapters: List<IndexedValue<ContentChapter>>,
	preparedChapters: suspend () -> List<IndexedValue<ContentChapter>>,
	loadLocalCopy: suspend () -> Content?,
	isEligible: (ContentChapter) -> Boolean,
): TranslationChapterSelection {
	var chapters = requestedChapters.filter { isEligible(it.value) }
	var workContent = storedContent
	if (chapters.isEmpty()) {
		// The stored record of a source title keeps the source's own chapter links; once downloaded, the same
		// chapters (same ids) live in the local copy, which is what has pages to translate.
		val local = loadLocalCopy()
		val requestedIds = requestedChapters.mapTo(HashSet()) { it.value.id }
		val localChapters = local?.chapters.orEmpty().filter { it.id in requestedIds }
		// getChapters() rejects the whole request when a single requested chapter is not part of the content.
		if (requestedIds.isNotEmpty() && localChapters.size == requestedIds.size) {
			val localEligible = localChapters.filter(isEligible)
				.mapIndexed { index, chapter -> IndexedValue(index, chapter) }
			if (localEligible.isNotEmpty()) {
				workContent = checkNotNull(local)
				chapters = localEligible
			}
		}
	}
	check(chapters.isNotEmpty()) { "No downloaded chapter to translate for ${storedContent.title}" }
	return TranslationChapterSelection(workContent, chapters)
}
