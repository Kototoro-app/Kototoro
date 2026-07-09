package org.skepsun.kototoro.settings.support

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import org.skepsun.kototoro.core.prefs.AppSettings

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
		val endpointAndModel = when (preset) {
			"OPENAI" -> "https://api.openai.com/v1/chat/completions" to "gpt-4o-mini"
			"DEEPSEEK" -> "https://api.deepseek.com/chat/completions" to "deepseek-v4-flash"
			"ZHIPU" -> "https://open.bigmodel.cn/api/paas/v4/chat/completions" to "glm-4-plus"
			"ALIBABA" -> "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" to "qwen-plus"
			"MOONSHOT" -> "https://api.moonshot.cn/v1/chat/completions" to "moonshot-v1-8k"
			"MINIMAX" -> "https://api.minimax.chat/v1/text/chatcompletion_v2" to "minimax-m2.5"
			"BAIDU" -> "https://qianfan.baidubce.com/v2/chat/completions" to "ernie-4.0-8k"
			"ANTHROPIC" -> "https://api.anthropic.com/v1/chat/completions" to "claude-sonnet-4-6"
			"GEMINI" -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions" to "gemini-3-flash-preview"
			"OPENROUTER" -> "https://openrouter.ai/api/v1/chat/completions" to "openai/gpt-4o-mini"
			else -> null
		}
		endpointAndModel ?: return

		val currentEndpoint = sharedPreferences.getString(endpointKey, "").orEmpty().trim()
		val currentModel = sharedPreferences.getString(modelKey, "").orEmpty().trim()
		sharedPreferences.edit {
			if (forceOverride || currentEndpoint.isBlank()) {
				putString(endpointKey, endpointAndModel.first)
			}
			if (forceOverride || currentModel.isBlank()) {
				putString(modelKey, endpointAndModel.second)
			}
		}
	}

	fun buildModelsUrl(endpoint: String): String {
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
