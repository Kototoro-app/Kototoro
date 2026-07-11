package org.skepsun.kototoro.reader.translate.domain

import java.util.Locale

private const val AUTO_LANGUAGE = "auto"
private const val DEFAULT_SOURCE_LANGUAGE = "ja"
internal const val MANGA_OCR_RECOGNIZER_MODEL_ID = "mangaocr_2025_onnx"
internal const val MANGA_48PX_CTC_RECOGNIZER_MODEL_ID = "manga_48px_ctc_onnx"

private val supportedTranslationLanguages = setOf(
	"ar",
	"bg",
	"bn",
	"ca",
	"cs",
	"da",
	"de",
	"el",
	"en",
	"es",
	"fi",
	"fr",
	"hi",
	"hr",
	"it",
	"ja",
	"ko",
	"nl",
	"pl",
	"pt",
	"ro",
	"ru",
	"sk",
	"sv",
	"th",
	"tl",
	"tr",
	"uk",
	"vi",
	"zh",
)

fun isAutoReaderTranslationLanguage(language: String?): Boolean {
	return language.normalizeReaderTranslationLanguageTag() == AUTO_LANGUAGE
}

fun resolveReaderTranslationSourceLanguage(
	preferredLanguage: String?,
	contentLanguage: String?,
): String {
	val normalizedPreference = preferredLanguage.normalizeReaderTranslationLanguageTag()
	return if (normalizedPreference == AUTO_LANGUAGE) {
		contentLanguage.normalizeReaderTranslationLanguageTag()
			?.takeIf { it in supportedTranslationLanguages }
			?: DEFAULT_SOURCE_LANGUAGE
	} else {
		normalizedPreference
			?.takeIf { it in supportedTranslationLanguages }
			?: DEFAULT_SOURCE_LANGUAGE
	}
}

fun String?.normalizeReaderTranslationLanguageTag(): String? {
	return this
		?.trim()
		?.lowercase()
		?.replace('_', '-')
		?.substringBefore('-')
		?.ifBlank { null }
}

fun resolveAutomaticReaderOcrLanguage(
	translatedLanguage: String?,
	sourceLanguage: String?,
	branch: String? = null,
): String? {
	return sequenceOf(resolveReaderBranchLanguage(branch), translatedLanguage, sourceLanguage)
		.mapNotNull { it.normalizeReaderTranslationLanguageTag() }
		.firstOrNull { it !in unknownContentLanguages }
}

internal fun resolveAutomaticReaderRecognizerModelId(language: String?): String {
	return if (language.normalizeReaderTranslationLanguageTag() == "ja") {
		MANGA_OCR_RECOGNIZER_MODEL_ID
	} else {
		resolveAutomaticPaddleRecognizerModelId(language)
	}
}

internal fun resolveReaderBranchLanguage(branch: String?): String? {
	val normalizedBranch = branch?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
	val matches = branchLanguageMarkers.mapNotNull { (language, markers) ->
		language.takeIf { markers.any { marker -> normalizedBranch.containsLanguageMarker(marker) } }
	}.distinct()
	return matches.singleOrNull()
}

private fun String.containsLanguageMarker(marker: String): Boolean {
	if (marker.any { !it.isLetterOrDigit() && it != '-' }) {
		return contains(marker)
	}
	return Regex("(?<![\\p{L}\\p{N}])${Regex.escape(marker)}(?![\\p{L}\\p{N}])")
		.containsMatchIn(this)
}

private val branchLanguageMarkers: Map<String, Set<String>> by lazy {
	supportedTranslationLanguages.associateWith { language ->
		val locale = Locale.forLanguageTag(language)
		buildSet {
			add(language)
			add(locale.getDisplayLanguage(Locale.ENGLISH))
			add(locale.getDisplayLanguage(locale))
			add(locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE))
			add(locale.getDisplayLanguage(Locale.TRADITIONAL_CHINESE))
		}.mapTo(linkedSetOf()) { it.trim().lowercase(Locale.ROOT) }
			.filterTo(linkedSetOf()) { it.isNotEmpty() }
	}
}

private val unknownContentLanguages = setOf(
	"all",
	"auto",
	"multi",
	"multilingual",
	"und",
	"unknown",
)
