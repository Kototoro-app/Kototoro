package org.skepsun.kototoro.aniyomi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.time.Duration.Companion.seconds

/**
 * Aniyomi extensions link against `eu.kanade.tachiyomi.network.RequestsKt` by JVM
 * descriptor, so the host must expose every overload of the extensions-lib 16 file,
 * including the synthetic `$default` bridges that Kotlin emits for omitted arguments.
 *
 * Issue #538: Anikoto resolves hosters through `OkHttpClient.get(url: HttpUrl, ...)`.
 * Kototoro only shipped the `String` overload, so the extension died with:
 *
 * ```
 * java.lang.NoSuchMethodError: No static method get$default(
 *   Lokhttp3/OkHttpClient;Lokhttp3/HttpUrl;Lokhttp3/Headers;Lokhttp3/CacheControl;
 *   Lkotlin/coroutines/Continuation;ILjava/lang/Object;
 * ) in class Leu/kanade/tachiyomi/network/RequestsKt;
 * ```
 *
 * These assertions must stay reflective: calling the Kotlin API directly would be
 * resolved at compile time and could never notice a missing overload.
 */
class AniyomiRequestsCompatibilityTest {

    @Test
    fun `extensions-lib 16 RequestsKt default-argument bridges stay linkable`() {
        val missing = REQUESTS_KT_ABI.filter { symbol ->
            REQUESTS_KT.findMethod(symbol.name, symbol.parameters) == null
        }.map { it.describe() }

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `http url get bridge runs the request the way anikoto calls it`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"hoster":"https://cdn.example/ep1.m3u8"}"""))
            server.start()

            // AnikotoTheme.getHosterList() calls:
            //   client.get(videoListRequest(episode).url, videoListRequest(episode).headers)
            // i.e. an HttpUrl plus explicit headers, with `cache` defaulted (mask bit 2).
            val url = server.url("/ajax/server/list").toString().toHttpUrl()
            val headers = Headers.Builder().add("X-Requested-With", "XMLHttpRequest").build()
            val method = requireNotNull(
                REQUESTS_KT.findMethod("get\$default", GET_HTTP_URL_DEFAULT.parameters),
            ) { "RequestsKt.get\$default(HttpUrl, ...) is missing — extensions calling it crash (#538)" }

            val outcome = CompletableDeferred<Any?>()
            val returned = method.invoke(
                null,
                OkHttpClient(),
                url,
                headers,
                null,
                ResumingContinuation(outcome),
                OMIT_CACHE,
                null,
            )
            if (returned !== COROUTINE_SUSPENDED) {
                outcome.complete(returned)
            }

            val response = withTimeout(30.seconds) { outcome.await() } as Response
            response.use {
                assertEquals(200, it.code)
                assertTrue(it.body.string().contains("ep1.m3u8"))
            }

            val recorded = server.takeRequest()
            assertEquals("/ajax/server/list", recorded.path)
            assertEquals("XMLHttpRequest", recorded.getHeader("X-Requested-With"))
        }
    }

    @Test
    fun `string get bridge still resolves and executes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.start()

            val method = requireNotNull(
                REQUESTS_KT.findMethod("get\$default", GET_STRING_DEFAULT.parameters),
            ) { "RequestsKt.get\$default(String, ...) is missing — Stremio-style callers crash (#52)" }

            val outcome = CompletableDeferred<Any?>()
            val returned = method.invoke(
                null,
                OkHttpClient(),
                server.url("/page").toString(),
                null,
                null,
                ResumingContinuation(outcome),
                OMIT_HEADERS_AND_CACHE,
                null,
            )
            if (returned !== COROUTINE_SUSPENDED) {
                outcome.complete(returned)
            }

            val response = withTimeout(30.seconds) { outcome.await() } as Response
            response.use { assertEquals(200, it.code) }
        }
    }

    /** Bridges a raw [Continuation] (the shape the generated `$default` bridge resumes) to a deferred. */
    private class ResumingContinuation(private val outcome: CompletableDeferred<Any?>) : Continuation<Any?> {

        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<Any?>) {
            result.fold(outcome::complete, outcome::completeExceptionally)
        }
    }

    private data class AbiSymbol(
        val name: String,
        val parameters: List<Class<*>>,
    ) {
        fun describe(): String = "$name(${parameters.joinToString { it.simpleName }})"
    }

    private companion object {
        private val INT = requireNotNull(Int::class.javaPrimitiveType)
        private val MARKER = Any::class.java
        private val REQUESTS_KT = Class.forName("eu.kanade.tachiyomi.network.RequestsKt")

        // In a generated `$default` bridge the mask bit for the n-th value parameter is
        // `1 shl n`, with the extension receiver excluded. For `get(url, headers = …, cache = …)`
        // that is url=0, headers=1, cache=2 — omitting a parameter means setting its bit.
        // Measured, not assumed: a wrong bit leaves the placeholder null and the call dies
        // inside GET() with "Parameter specified as non-null is null".
        /** `client.get(url)` — `headers` and `cache` both defaulted. */
        private const val OMIT_HEADERS_AND_CACHE = 0b0110

        /** `client.get(url, headers)` — Anikoto's hoster call, only `cache` defaulted. */
        private const val OMIT_CACHE = 0b0100

        private fun symbol(name: String, vararg parameters: Class<*>): AbiSymbol =
            AbiSymbol(name, parameters.toList())

        private fun Class<*>.findMethod(name: String, parameters: List<Class<*>>): Method? =
            runCatching { getDeclaredMethod(name, *parameters.toTypedArray()) }.getOrNull()

        private val GET_STRING_DEFAULT = symbol(
            "get\$default",
            OkHttpClient::class.java,
            String::class.java,
            Headers::class.java,
            CacheControl::class.java,
            Continuation::class.java,
            INT,
            MARKER,
        )

        private val GET_HTTP_URL_DEFAULT = symbol(
            "get\$default",
            OkHttpClient::class.java,
            HttpUrl::class.java,
            Headers::class.java,
            CacheControl::class.java,
            Continuation::class.java,
            INT,
            MARKER,
        )

        /**
         * Every default-argument bridge of Aniyomi extensions-lib 16 `Requests.kt`.
         * The request builders are shared by Mihon and Aniyomi extensions; the suspend
         * client helpers are the overloads extensions actually execute.
         */
        private val REQUESTS_KT_ABI = listOf(
            symbol(
                "GET\$default",
                String::class.java, Headers::class.java, CacheControl::class.java, INT, MARKER,
            ),
            symbol(
                "GET\$default",
                HttpUrl::class.java, Headers::class.java, CacheControl::class.java, INT, MARKER,
            ),
            symbol(
                "POST\$default",
                String::class.java, Headers::class.java, RequestBody::class.java, CacheControl::class.java, INT, MARKER,
            ),
            symbol(
                "PUT\$default",
                String::class.java, Headers::class.java, RequestBody::class.java, CacheControl::class.java, INT, MARKER,
            ),
            symbol(
                "DELETE\$default",
                String::class.java, Headers::class.java, RequestBody::class.java, CacheControl::class.java, INT, MARKER,
            ),
            GET_STRING_DEFAULT,
            GET_HTTP_URL_DEFAULT,
            symbol(
                "post\$default",
                OkHttpClient::class.java, String::class.java, Headers::class.java, RequestBody::class.java,
                CacheControl::class.java, Continuation::class.java, INT, MARKER,
            ),
        )
    }
}
