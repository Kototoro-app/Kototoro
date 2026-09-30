package org.skepsun.kototoro.core.lnreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LNReaderFetchConcurrencyTest {

    @Test
    fun promiseAllStartsBothRequestsBeforeEitherResponseCompletes() = runBlocking {
        val requestsStarted = CountDownLatch(2)
        val overlap = ConcurrentLinkedQueue<Boolean>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestsStarted.countDown()
            val concurrent = requestsStarted.await(5, TimeUnit.SECONDS)
            overlap.add(concurrent)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(concurrent.toString().toResponseBody())
                .build()
        }.build()
        val engine = LNReaderEngine(fetchBridge = LNReaderFetchBridge(client, "parallel"))
        val qjs = engine.createPluginContext("", "parallel")
        try {
            qjs.evaluate<Any?>(
                """
                (async function() {
                    const responses = await Promise.all([
                        fetchApi('https://example.com/first'),
                        fetchApi('https://example.com/second')
                    ]);
                    return JSON.stringify(await Promise.all(responses.map(function(r) { return r.json(); })));
                })()
                """.trimIndent(),
            )
            assertEquals(listOf(true, true), overlap.toList())
        } finally {
            qjs.close()
        }
    }
}
