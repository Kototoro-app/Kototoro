package org.skepsun.kototoro.core.dictionary

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.javascript.JavaScriptEngine
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.parser.legado.AnalyzeRule
import org.skepsun.kototoro.core.parser.legado.AnalyzeUrl
import org.skepsun.kototoro.core.parser.legado.ConcurrentRateLimiter
import org.skepsun.kototoro.core.parser.legado.bridge.KototoroLegadoHttpExecutor
import org.skepsun.kototoro.core.parser.legado.bridge.androidLegadoRuleAjax
import org.skepsun.kototoro.core.parser.legado.runtime.StandaloneLegadoListRuntime
import javax.inject.Inject
import javax.inject.Singleton

data class DictionaryLookupResult(
    val rule: DictionaryRule,
    val url: String,
    val html: String,
)

@Singleton
class DictionaryLookupService @Inject constructor(
    private val httpClient: LegadoHttpClient,
    private val jsEngine: JavaScriptEngine,
) {

    suspend fun search(rule: DictionaryRule, word: String): DictionaryLookupResult = withContext(Dispatchers.IO) {
        val query = word.trim()
        require(query.isNotBlank()) { "Dictionary query must not be blank" }

        val baseUrl = resolveBaseUrl(rule.urlRule)
        val source = ContentSource(rule.name)
        val config = LegadoBookSource(
            bookSourceName = rule.name,
            bookSourceUrl = baseUrl,
        )
        val executor = KototoroLegadoHttpExecutor(
            source = source,
            config = config,
            httpClient = httpClient,
            rateLimiter = ConcurrentRateLimiter("dictionary:${rule.name}", null),
            configHeadersProvider = { emptyMap() },
            loginHeadersProvider = { emptyMap() },
            sourceUserAgentProvider = { DEFAULT_USER_AGENT },
        )
        val runtime = StandaloneLegadoListRuntime(
            jsEngine = jsEngine,
            source = config,
            parserSourceName = rule.name,
            httpExecutor = executor,
            ajaxHandler = ::androidLegadoRuleAjax,
        )
        val runtimeContext = runtime.createRuntimeContext(key = query)
        val analyzer = AnalyzeUrl(
            mUrl = rule.urlRule,
            key = query,
            baseUrl = baseUrl,
            ruleData = runtimeContext.ruleData(),
            runtimeContext = runtimeContext,
            jsEvaluator = runtimeContext.asJsEvaluator { baseUrl },
            httpExecutor = executor,
        )
        val response = analyzer.getStrResponseAwait(useWebView = false)
        val body = response.body.orEmpty()
        val html = if (rule.showRule.isBlank()) {
            body
        } else {
            AnalyzeRule(body, runtimeContext, response.raw.request.url.toString())
                .getString(rule.showRule, mContent = body)
        }
        DictionaryLookupResult(
            rule = rule,
            url = response.raw.request.url.toString(),
            html = html,
        )
    }

    private fun resolveBaseUrl(ruleUrl: String): String {
        val parsed = runCatching { Uri.parse(ruleUrl) }.getOrNull()
        val scheme = parsed?.scheme.orEmpty()
        val host = parsed?.host.orEmpty()
        return if (scheme.isNotBlank() && host.isNotBlank()) {
            buildString {
                append(scheme).append("://").append(host)
                parsed?.port?.takeIf { it >= 0 }?.let { append(':').append(it) }
            }
        } else {
            ruleUrl.substringBefore("?").substringBefore(",{")
        }
    }

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
