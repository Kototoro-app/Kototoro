package org.skepsun.kototoro.reader.translate.domain

internal enum class ReaderTranslationRenderStyle {
	REPLACE,
	COMPACT_OVERLAY,
	;

	companion object {
		fun fromPreference(value: String): ReaderTranslationRenderStyle {
			return entries.firstOrNull { it.name == value.trim().uppercase() } ?: COMPACT_OVERLAY
		}
	}
}
