package org.skepsun.kototoro.settings.support

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.reader.translate.domain.TranslationApiProviderCatalog

object TranslationApiSettingsSupport {

	fun applyApiProviderPreset(
		sharedPreferences: SharedPreferences,
		presetInput: String,
		forceOverride: Boolean = false,
		endpointKey: String = AppSettings.KEY_READER_TRANSLATION_API_ENDPOINT,
		modelKey: String = AppSettings.KEY_READER_TRANSLATION_API_MODEL,
	) {
		val preset = presetInput.trim().uppercase()
		if (preset.isBlank() || preset == "CUSTOM") return
		val provider = TranslationApiProviderCatalog.find(preset) ?: return

		val currentEndpoint = sharedPreferences.getString(endpointKey, "").orEmpty().trim()
		val currentModel = sharedPreferences.getString(modelKey, "").orEmpty().trim()
		sharedPreferences.edit {
			if (forceOverride || currentEndpoint.isBlank()) {
				putString(endpointKey, provider.chatEndpoint)
			}
			if (forceOverride || currentModel.isBlank()) {
				putString(modelKey, provider.defaultModel)
			}
		}
	}

	fun buildModelsUrl(endpoint: String, providerId: String? = null): String {
		TranslationApiProviderCatalog.find(providerId)?.let { return it.modelsEndpoint }
		val trimmed = endpoint.trim().trimEnd('/')
		return when {
			trimmed.endsWith("/v1/chat/completions", ignoreCase = true) -> trimmed.removeSuffix("/v1/chat/completions") + "/v1/models"
			trimmed.endsWith("/chat/completions", ignoreCase = true) -> trimmed.removeSuffix("/chat/completions") + "/models"
			trimmed.endsWith("/v1", ignoreCase = true) -> "$trimmed/models"
			trimmed.endsWith("/models", ignoreCase = true) -> trimmed
			else -> "$trimmed/models"
		}
	}

	fun parseModelIds(body: String): List<String> {
		if (body.isBlank()) return emptyList()
		val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
		val data = root.optJSONArray("data") ?: return emptyList()
		val ids = linkedSetOf<String>()
		for (i in 0 until data.length()) {
			val id = data.optJSONObject(i)?.optString("id").orEmpty().trim()
			if (id.isNotBlank()) ids.add(id)
		}
		return ids.toList().sorted()
	}
}
