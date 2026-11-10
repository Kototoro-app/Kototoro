package org.skepsun.kototoro.core.parser.legado

import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoRuleRuntimeContext

/**
 * Parses a book source's `exploreUrl` into its discovery categories.
 * Supports:
 * 1. JSON array format: [{'title':'...', 'url':'...'}, ...]
 * 2. Text format: title1::url1\ntitle2::url2 (or separated by &&)
 */
object LegadoExploreKinds {
    private const val TAG = "LegadoExploreKinds"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    fun parse(exploreUrl: String?, runtimeContext: LegadoRuleRuntimeContext, baseUrl: String): List<ExploreKind> {
        if (exploreUrl.isNullOrBlank()) return emptyList()

        val effectiveText = resolveExploreUrlText(exploreUrl.trim(), runtimeContext, baseUrl).trim()
        if (effectiveText.isEmpty()) return emptyList()

        return try {
            if (effectiveText.startsWith("[")) {
                // JSON array format
                val normalized = effectiveText.replace("'", "\"")
                runCatching { json.decodeFromString<List<ExploreKind>>(normalized) }
                    .recoverCatching {
                        // 部分三方脚本会把 `{{...Get(\"url\")...}}` 直接写进字符串，
                        // 若内容中出现未转义的 `"` 会导致严格 JSON 解析失败。
                        val fixed = fixExploreJsonString(normalized)
                        json.decodeFromString<List<ExploreKind>>(fixed)
                    }
                    .getOrThrow()
            } else {
                // Text format: title::url separated by && or newline
                effectiveText.split("(&&|\n)+".toRegex()).mapNotNull { kindStr ->
                    val parts = kindStr.trim().split("::")
                    if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                        ExploreKind(parts[0].trim(), parts.getOrNull(1)?.trim())
                    } else null
                }
            }
        } catch (e: Exception) {
            LegadoRuleLog.warn(TAG, "Failed to parse exploreUrl", e)
            emptyList()
        }
    }

    private fun fixExploreJsonString(raw: String): String {
        // 仅修复 JSON 字符串内部的 `{{...}}` 模板段落：
        // - 当模板表达式包含双引号（例如 Get("url")）时，将其转义为 `\"`，保证 JSON 合法。
        if (!raw.contains("{{") || !raw.contains("\"")) return raw

        val sb = StringBuilder(raw.length + 16)
        var i = 0
        while (i < raw.length) {
            // 进入模板段落
            if (i + 1 < raw.length && raw[i] == '{' && raw[i + 1] == '{') {
                sb.append("{{")
                i += 2
                while (i < raw.length) {
                    if (i + 1 < raw.length && raw[i] == '}' && raw[i + 1] == '}') {
                        sb.append("}}")
                        i += 2
                        break
                    }
                    val ch = raw[i]
                    if (ch == '"' && (sb.isEmpty() || sb.last() != '\\')) {
                        sb.append("\\\"")
                    } else {
                        sb.append(ch)
                    }
                    i++
                }
                continue
            }
            sb.append(raw[i])
            i++
        }
        return sb.toString()
    }

    /**
     * legado-with-MD3 兼容：
     * - exploreUrl 允许使用 `<js>...</js>`/`@js:` 动态生成分类列表（title::url 多行文本，或 JSON 数组）。
     *
     * 注意：这里仅执行 exploreUrl 自身的脚本，不做 `{{page}}` 等模板变量替换；
     * `{{page}}` 会在后续实际请求时按 offset->page 计算替换。
     */
    private fun resolveExploreUrlText(exploreUrl: String, runtimeContext: LegadoRuleRuntimeContext, baseUrl: String): String {
        if (!looksLikeJsRule(exploreUrl)) return exploreUrl

        val analyzer = AnalyzeRule(
            content = "",
            runtimeContext = runtimeContext,
            baseUrl = baseUrl,
        )
        val lines = analyzer.getStringList(exploreUrl, mContent = "", isUrl = false).orEmpty()
        return lines.joinToString("\n")
    }

    private fun looksLikeJsRule(rule: String): Boolean {
        val trimmed = rule.trim()
        return trimmed.startsWith("@js:", ignoreCase = true) ||
            trimmed.startsWith("<js>", ignoreCase = true) ||
            trimmed.contains("</js>", ignoreCase = true)
    }
}
