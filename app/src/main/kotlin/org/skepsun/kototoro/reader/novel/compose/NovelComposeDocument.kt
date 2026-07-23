package org.skepsun.kototoro.reader.novel.compose

import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.NovelParagraphSplitter
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode
import org.skepsun.kototoro.reader.novel.parseNovelImages

/**
 * Immutable, View-independent chapter projection consumed by the Compose novel reader.
 * Image loading and chapter navigation remain separate concerns.
 */
sealed interface NovelComposeBlock {
	data class Text(
		val key: String,
		val original: String,
		val translation: String?,
		val displayMode: NovelTranslationDisplayMode,
		val inlineImages: Map<String, String>,
		val sourceRange: IntRange?,
	) : NovelComposeBlock

	data class Image(
		val key: String,
		val path: String,
	) : NovelComposeBlock
}

fun buildNovelComposeDocument(
	content: String,
	translation: NovelChapterTranslation? = null,
): List<NovelComposeBlock> {
	val parsed = parseNovelImages(content)
	val translationsByOriginal = translation
		?.paragraphs
		?.associate { paragraph -> paragraph.originalText to translation.translations[paragraph.index] }
		.orEmpty()
	val displayMode = translation?.displayMode ?: NovelTranslationDisplayMode.TRANSLATION_ONLY

	var searchOffset = 0
	return NovelParagraphSplitter.split(parsed.text).flatMap { paragraph ->
		val sourceStart = parsed.text.indexOf(paragraph.originalText, searchOffset)
		val sourceRange = sourceStart.takeIf { it >= 0 }?.let { start ->
			searchOffset = start + paragraph.originalText.length
			start until searchOffset
		}
		val imageIndex = IMAGE_PLACEHOLDER.matchEntire(paragraph.originalText)
			?.groupValues
			?.getOrNull(1)
			?.toIntOrNull()
		if (imageIndex != null) {
			parsed.blockImagePaths.getOrNull(imageIndex)?.let { path ->
				listOf(NovelComposeBlock.Image(key = "image-$imageIndex", path = path))
			}.orEmpty()
		} else {
			listOf(
				NovelComposeBlock.Text(
					key = "text-${paragraph.index}",
					original = paragraph.originalText,
					translation = translationsByOriginal[paragraph.originalText],
					displayMode = displayMode,
					inlineImages = parsed.inlineImagePaths.mapIndexedNotNull { index, path ->
						val token = "[INLINE_IMAGE_$index]"
						if (paragraph.originalText.contains(token)) token to path else null
					}.toMap(),
					sourceRange = sourceRange,
				),
			)
		}
	}
}

private val IMAGE_PLACEHOLDER = Regex("\\[IMAGE_PLACEHOLDER_(\\d+)]")
