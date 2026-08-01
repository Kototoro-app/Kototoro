package org.skepsun.kototoro.settings.support

import android.content.Context
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionShader
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionMode
import org.skepsun.kototoro.reader.translate.domain.TranslationApiProviderCatalog

object AISettingsSummarySupport {

	fun getTranslationModeLabel(context: Context, mode: ReaderTranslationMode): String = context.getString(
		when (mode) {
			ReaderTranslationMode.LOCAL_ONLY -> R.string.reader_translation_mode_local_only
			ReaderTranslationMode.LOCAL_FIRST -> R.string.reader_translation_mode_local_first
			ReaderTranslationMode.API_ONLY -> R.string.reader_translation_mode_api_only
		},
	)

	fun getApiProviderLabel(context: Context, preset: String): String {
		return TranslationApiProviderCatalog.find(preset)?.name
			?: context.getString(R.string.ai_api_provider_custom)
	}

	fun getVideoModeLabel(context: Context, mode: VideoSuperResolutionMode): String = context.getString(
		when (mode) {
			VideoSuperResolutionMode.OFF -> R.string.video_super_resolution_off
			VideoSuperResolutionMode.QUALITY -> R.string.video_super_resolution_quality
			VideoSuperResolutionMode.BALANCED -> R.string.video_super_resolution_balanced
			VideoSuperResolutionMode.PERFORMANCE -> R.string.video_super_resolution_performance
			VideoSuperResolutionMode.ADVANCED -> R.string.video_super_resolution_advanced
		},
	)

	fun getSourceLanguageLabel(context: Context, code: String): String {
		val labels = context.resources.getStringArray(R.array.reader_translation_source_languages)
		val values = context.resources.getStringArray(R.array.values_reader_translation_source_languages)
		return values.indexOf(code).takeIf { it >= 0 }?.let(labels::get)
			?: code.ifBlank { context.getString(R.string.unknown) }
	}

	fun getTargetLanguageLabel(context: Context, code: String): String {
		val labels = context.resources.getStringArray(R.array.reader_translation_target_languages)
		val values = context.resources.getStringArray(R.array.values_reader_translation_target_languages)
		return values.indexOf(code).takeIf { it >= 0 }?.let(labels::get)
			?: code.ifBlank { context.getString(R.string.unknown) }
	}

	fun getReaderSuperResolutionEngineLabel(engine: String, model: String): String {
		return if (engine == "ANIME4K" || model.startsWith("ANIME4K_")) {
			"Anime4K"
		} else {
			if (model.contains("realesrgan", ignoreCase = true)) {
				"RealESRGAN"
			} else {
				"RealCUGAN"
			}
		}
	}




	fun getVideoShaderLabel(context: Context, shader: VideoSuperResolutionShader): String = context.getString(
		when (shader) {
			VideoSuperResolutionShader.MODE_A -> R.string.video_super_resolution_mode_a
			VideoSuperResolutionShader.MODE_B -> R.string.video_super_resolution_mode_b
			VideoSuperResolutionShader.MODE_C -> R.string.video_super_resolution_mode_c
			VideoSuperResolutionShader.MODE_AA -> R.string.video_super_resolution_mode_aa
			VideoSuperResolutionShader.MODE_BB -> R.string.video_super_resolution_mode_bb
			VideoSuperResolutionShader.MODE_CA -> R.string.video_super_resolution_mode_ca
			VideoSuperResolutionShader.CUSTOM -> R.string.video_super_resolution_mode_custom
		},
	)
}
