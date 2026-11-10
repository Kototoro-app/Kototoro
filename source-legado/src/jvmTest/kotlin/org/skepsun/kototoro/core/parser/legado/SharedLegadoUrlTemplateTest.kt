package org.skepsun.kototoro.core.parser.legado

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoJsEvaluator

class SharedLegadoUrlTemplateTest {

    @Test
    fun `evaluates template expressions and page choices with Android semantics`() {
        val evaluator = LegadoUrlTemplateEvaluator(
            key = "novel",
            page = 2,
            baseUrl = "https://example.test",
            jsEvaluator = LegadoJsEvaluator { script, _ ->
                when (script.trim()) {
                    "key" -> "novel"
                    "page" -> 2.0
                    else -> error("Unexpected expression: $script")
                }
            },
        )

        assertEquals(
            "/search?q=novel&page=2&sort=latest",
            evaluator.evaluate("/search?q={{key}}&page={{page}}&sort=<popular,latest>"),
        )
    }

    @Test
    fun `request plan preserves Android encoding and post options`() {
        val builder = LegadoRequestPlanBuilder(
            baseUrl = "https://example.test/",
            enabledCookieJarDefault = true,
            useWebViewDefault = false,
            webJsDefault = null,
            webViewDelayDefault = 0,
            defaultHeaders = mapOf("User-Agent" to "test"),
            jsEvaluator = LegadoJsEvaluator { _, result -> result },
        )
        val get = builder.build("/search?q=中文 词&from=1,{'charset':'utf-8'}")
        val post = builder.build("/api,{'method':'POST','body':'page=1','bodyJs':'result'}")

        assertEquals("https://example.test/search?q=%E4%B8%AD%E6%96%87%20%E8%AF%8D&from=1", get.url)
        assertEquals("POST", post.method)
        assertEquals("page=1", post.body)
        assertTrue(post.bodyIsForm)
        assertEquals("result", post.bodyJs)
    }

    @Test
    fun `planner evaluates page expression before encoding the request`() {
        val planner = LegadoRequestPlanner(
            key = null,
            page = 2,
            baseUrl = "https://example.test/",
            jsEvaluator = LegadoJsEvaluator { _, _ -> "中文 词" },
            enabledCookieJarDefault = true,
            useWebViewDefault = false,
            webJsDefault = null,
            webViewDelayDefault = 0,
            defaultHeaders = emptyMap(),
        )
        val prepared = planner.prepare("/search?q={{key}}&page=<1,2>,{'charset':'utf-8'}")

        assertEquals("/search?q=中文 词&page=2,{'charset':'utf-8'}", prepared.evaluatedRuleUrl)
        assertEquals("https://example.test/search?q=%E4%B8%AD%E6%96%87%20%E8%AF%8D&page=2", prepared.plan.url)
    }
}
