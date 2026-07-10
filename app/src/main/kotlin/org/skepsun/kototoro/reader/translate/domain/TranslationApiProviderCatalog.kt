package org.skepsun.kototoro.reader.translate.domain

import okhttp3.Request

enum class TranslationApiAuthScheme {
	BEARER,
	BEARER_AND_X_API_KEY,
}

data class TranslationApiProvider(
	val id: String,
	val modelsDevId: String,
	val chatEndpoint: String,
	val modelsEndpoint: String,
	val defaultModel: String,
	val apiKeyUrl: String,
	val documentationUrl: String,
	val authScheme: TranslationApiAuthScheme = TranslationApiAuthScheme.BEARER,
)

object TranslationApiProviderCatalog {

	val providers = listOf(
		TranslationApiProvider(
			id = "OPENAI",
			modelsDevId = "openai",
			chatEndpoint = "https://api.openai.com/v1/chat/completions",
			modelsEndpoint = "https://api.openai.com/v1/models",
			defaultModel = "gpt-5.4-mini",
			apiKeyUrl = "https://platform.openai.com/api-keys",
			documentationUrl = "https://developers.openai.com/api/reference/overview",
		),
		TranslationApiProvider(
			id = "DEEPSEEK",
			modelsDevId = "deepseek",
			chatEndpoint = "https://api.deepseek.com/chat/completions",
			modelsEndpoint = "https://api.deepseek.com/models",
			defaultModel = "deepseek-v4-flash",
			apiKeyUrl = "https://platform.deepseek.com/api_keys",
			documentationUrl = "https://api-docs.deepseek.com/",
		),
		TranslationApiProvider(
			id = "ZHIPU",
			modelsDevId = "zhipuai",
			chatEndpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
			modelsEndpoint = "https://open.bigmodel.cn/api/paas/v4/models",
			defaultModel = "glm-5-turbo",
			apiKeyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
			documentationUrl = "https://docs.bigmodel.cn/cn/guide/develop/http/introduction",
		),
		TranslationApiProvider(
			id = "ALIBABA",
			modelsDevId = "alibaba",
			chatEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
			modelsEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
			defaultModel = "qwen3.6-flash",
			apiKeyUrl = "https://bailian.console.aliyun.com/?apiKey=1#/api-key",
			documentationUrl = "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope",
		),
		TranslationApiProvider(
			id = "MOONSHOT",
			modelsDevId = "moonshotai",
			chatEndpoint = "https://api.moonshot.ai/v1/chat/completions",
			modelsEndpoint = "https://api.moonshot.ai/v1/models",
			defaultModel = "kimi-k2.5",
			apiKeyUrl = "https://platform.moonshot.ai/console/api-keys",
			documentationUrl = "https://platform.moonshot.ai/docs/api/chat",
		),
		TranslationApiProvider(
			id = "ANTHROPIC",
			modelsDevId = "anthropic",
			chatEndpoint = "https://api.anthropic.com/v1/chat/completions",
			modelsEndpoint = "https://api.anthropic.com/v1/models",
			defaultModel = "claude-sonnet-5",
			apiKeyUrl = "https://platform.claude.com/settings/keys",
			documentationUrl = "https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk",
		),
		TranslationApiProvider(
			id = "GEMINI",
			modelsDevId = "google",
			chatEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
			modelsEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/models",
			defaultModel = "gemini-3.5-flash",
			apiKeyUrl = "https://aistudio.google.com/apikey",
			documentationUrl = "https://ai.google.dev/gemini-api/docs/openai",
		),
		TranslationApiProvider(
			id = "OPENROUTER",
			modelsDevId = "openrouter",
			chatEndpoint = "https://openrouter.ai/api/v1/chat/completions",
			modelsEndpoint = "https://openrouter.ai/api/v1/models",
			defaultModel = "openai/gpt-5.4-mini",
			apiKeyUrl = "https://openrouter.ai/settings/keys",
			documentationUrl = "https://openrouter.ai/docs/api-reference/overview",
		),
	)

	fun find(id: String?): TranslationApiProvider? {
		return providers.firstOrNull { it.id == id?.trim()?.uppercase() }
	}

	fun applyAuthentication(builder: Request.Builder, providerId: String?, apiKey: String) {
		if (apiKey.isBlank()) return
		when (find(providerId)?.authScheme ?: TranslationApiAuthScheme.BEARER_AND_X_API_KEY) {
			TranslationApiAuthScheme.BEARER -> builder.header("Authorization", "Bearer $apiKey")
			TranslationApiAuthScheme.BEARER_AND_X_API_KEY -> {
				builder.header("Authorization", "Bearer $apiKey")
				builder.header("X-API-Key", apiKey)
			}
		}
	}
}
