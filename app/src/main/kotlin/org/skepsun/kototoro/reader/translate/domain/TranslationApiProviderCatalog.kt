package org.skepsun.kototoro.reader.translate.domain

import okhttp3.Request

enum class TranslationApiAuthScheme {
	BEARER,
	BEARER_AND_X_API_KEY,
}

data class TranslationApiProvider(
	val id: String,
	val name: String,
	val modelsDevId: String,
	val chatEndpoint: String,
	val modelsEndpoint: String,
	val defaultModel: String,
	val apiKeyUrl: String,
	val documentationUrl: String,
	val authScheme: TranslationApiAuthScheme = TranslationApiAuthScheme.BEARER,
)

object TranslationApiProviderCatalog {

	/*
	 * OpenCode obtains its provider metadata from models.dev. Only providers with a stable
	 * OpenAI-compatible HTTP endpoint belong here because the reader uses chat/completions.
	 */
	val providers = listOf(
		provider("OPENAI", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-4o-mini", "https://platform.openai.com/api-keys", "https://platform.openai.com/docs/api-reference"),
		provider("DEEPSEEK", "DeepSeek", "deepseek", "https://api.deepseek.com", "deepseek-chat", "https://platform.deepseek.com/api_keys", "https://api-docs.deepseek.com/"),
		provider("ZHIPU", "Zhipu AI", "zhipuai", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "https://open.bigmodel.cn/usercenter/apikeys", "https://docs.z.ai/guides/overview/pricing"),
		provider("ZAI", "Z.AI", "zai", "https://api.z.ai/api/paas/v4", "glm-4.5-flash", "https://z.ai/manage-apikey/apikey-list", "https://docs.z.ai/guides/overview/pricing"),
		provider("ALIBABA", "Alibaba Cloud (China)", "alibaba-cn", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "https://bailian.console.aliyun.com/?apiKey=1#/api-key", "https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope"),
		provider("ALIBABA_GLOBAL", "Alibaba Cloud (Global)", "alibaba", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "qwen-plus", "https://modelstudio.console.alibabacloud.com/?tab=globalset#/efm/api_key", "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope"),
		provider("MOONSHOT", "Moonshot AI", "moonshotai", "https://api.moonshot.ai/v1", "kimi-k2-turbo-preview", "https://platform.moonshot.ai/console/api-keys", "https://platform.moonshot.ai/docs/api/chat"),
		provider("MOONSHOT_CN", "Moonshot AI (China)", "moonshotai-cn", "https://api.moonshot.cn/v1", "kimi-k2-turbo-preview", "https://platform.moonshot.cn/console/api-keys", "https://platform.moonshot.cn/docs/api/chat"),
		provider("ANTHROPIC", "Anthropic", "anthropic", "https://api.anthropic.com/v1", "claude-3-5-haiku-latest", "https://console.anthropic.com/settings/keys", "https://docs.anthropic.com/en/api/openai-sdk"),
		provider("GEMINI", "Google Gemini", "google", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", "https://aistudio.google.com/apikey", "https://ai.google.dev/gemini-api/docs/openai"),
		provider("OPENROUTER", "OpenRouter", "openrouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "https://openrouter.ai/settings/keys", "https://openrouter.ai/docs/api-reference/overview"),
		provider("OPENCODE", "OpenCode Zen", "opencode", "https://opencode.ai/zen/v1", "big-pickle", "https://opencode.ai/auth", "https://opencode.ai/docs/zen"),
		provider("OPENCODE_GO", "OpenCode Go", "opencode-go", "https://opencode.ai/zen/go/v1", "deepseek-v4-flash", "https://opencode.ai/auth", "https://opencode.ai/docs/zen"),
		provider("GROQ", "Groq", "groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "https://console.groq.com/keys", "https://console.groq.com/docs/openai"),
		provider("XAI", "xAI", "xai", "https://api.x.ai/v1", "grok-3-mini", "https://console.x.ai/", "https://docs.x.ai/docs/api-reference"),
		provider("MISTRAL", "Mistral", "mistral", "https://api.mistral.ai/v1", "mistral-small-latest", "https://console.mistral.ai/api-keys", "https://docs.mistral.ai/api/"),
		provider("TOGETHER", "Together AI", "togetherai", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo", "https://api.together.ai/settings/api-keys", "https://docs.together.ai/docs/openai-api-compatibility"),
		provider("FIREWORKS", "Fireworks AI", "fireworks-ai", "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/llama-v3p3-70b-instruct", "https://fireworks.ai/account/api-keys", "https://fireworks.ai/docs/tools-sdks/openai-compatibility"),
		provider("CEREBRAS", "Cerebras", "cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b", "https://cloud.cerebras.ai/platform", "https://inference-docs.cerebras.ai/api-reference/chat-completions"),
		provider("PERPLEXITY", "Perplexity", "perplexity", "https://api.perplexity.ai", "sonar", "https://www.perplexity.ai/settings/api", "https://docs.perplexity.ai/api-reference/chat-completions"),
		provider("SILICONFLOW", "SiliconFlow", "siliconflow", "https://api.siliconflow.com/v1", "Qwen/Qwen2.5-7B-Instruct", "https://cloud.siliconflow.com/account/ak", "https://docs.siliconflow.com/en/api-reference/chat-completions/chat-completions"),
		provider("SILICONFLOW_CN", "SiliconFlow (China)", "siliconflow-cn", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct", "https://cloud.siliconflow.cn/account/ak", "https://docs.siliconflow.cn/cn/api-reference/chat-completions/chat-completions"),
		provider("NVIDIA", "NVIDIA NIM", "nvidia", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct", "https://build.nvidia.com/", "https://docs.api.nvidia.com/nim/reference/llm-apis"),
		provider("NEBIUS", "Nebius Token Factory", "nebius", "https://api.tokenfactory.nebius.com/v1", "meta-llama/Llama-3.3-70B-Instruct", "https://studio.nebius.com/", "https://docs.tokenfactory.nebius.com/"),
		provider("MODELSCOPE", "ModelScope", "modelscope", "https://api-inference.modelscope.cn/v1", "Qwen/Qwen3-235B-A22B-Instruct-2507", "https://modelscope.cn/my/myaccesstoken", "https://modelscope.cn/docs/model-service/API-Inference/intro"),
		provider("HUGGINGFACE", "Hugging Face", "huggingface", "https://router.huggingface.co/v1", "Qwen/Qwen2.5-7B-Instruct", "https://huggingface.co/settings/tokens", "https://huggingface.co/docs/inference-providers"),
		provider("GITHUB_MODELS", "GitHub Models", "github-models", "https://models.github.ai/inference", "openai/gpt-4.1-mini", "https://github.com/settings/tokens", "https://docs.github.com/en/github-models"),
		provider("OLLAMA_CLOUD", "Ollama Cloud", "ollama-cloud", "https://ollama.com/v1", "deepseek-v3.1:671b-cloud", "https://ollama.com/settings/keys", "https://docs.ollama.com/cloud"),
		provider("NOVITA", "Novita AI", "novita-ai", "https://api.novita.ai/openai", "meta-llama/llama-3.3-70b-instruct", "https://novita.ai/settings/key-management", "https://novita.ai/docs/guides/introduction"),
		provider("STEPFUN", "StepFun (China)", "stepfun", "https://api.stepfun.com/v1", "step-1-32k", "https://platform.stepfun.com/interface-key", "https://platform.stepfun.com/docs/zh/overview/concept"),
		provider("STEPFUN_GLOBAL", "StepFun (Global)", "stepfun-ai", "https://api.stepfun.ai/v1", "step-1-32k", "https://platform.stepfun.ai/interface-key", "https://platform.stepfun.ai/docs/en/overview/concept"),
		provider("XIAOMI", "Xiaomi MiMo", "xiaomi", "https://api.xiaomimimo.com/v1", "mimo-v2-flash", "https://platform.xiaomimimo.com/", "https://platform.xiaomimimo.com/#/docs"),
	)

	fun find(id: String?): TranslationApiProvider? {
		return providers.firstOrNull { it.id == id?.trim()?.uppercase() }
	}

	fun resolveChatEndpoint(providerId: String?, customEndpoint: String): String {
		return find(providerId)?.chatEndpoint ?: customEndpoint.trim()
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

	private fun provider(
		id: String,
		name: String,
		modelsDevId: String,
		baseEndpoint: String,
		defaultModel: String,
		apiKeyUrl: String,
		documentationUrl: String,
	): TranslationApiProvider {
		val base = baseEndpoint.trimEnd('/')
		return TranslationApiProvider(
			id = id,
			name = name,
			modelsDevId = modelsDevId,
			chatEndpoint = "$base/chat/completions",
			modelsEndpoint = "$base/models",
			defaultModel = defaultModel,
			apiKeyUrl = apiKeyUrl,
			documentationUrl = documentationUrl,
		)
	}
}
