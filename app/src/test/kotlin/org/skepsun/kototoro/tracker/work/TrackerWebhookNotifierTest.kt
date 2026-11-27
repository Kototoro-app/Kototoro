package org.skepsun.kototoro.tracker.work

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * HTTP behaviour of the tracker webhook: every answer, a dead endpoint and a timeout are reported
 * as a result, never as an exception, because [TrackWorker] must never fail because of a webhook.
 *
 * MockWebServer is plain HTTP; the notifier only talks to `https://` (a webhook URL is a secret),
 * so the server runs with a self-signed certificate and the client trusts exactly that one.
 */
class TrackerWebhookNotifierTest {

    private lateinit var server: MockWebServer
    private lateinit var baseClient: OkHttpClient

    private val certificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .build()

    @BeforeEach
    fun setUp() {
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        baseClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a 2xx answer is a success and posts the merged payload`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = notifier().sendBatch(
            url = server.url("/hook").toString(),
            items = items(3),
            timestamp = 1_700_000_000_000L,
        )

        result.shouldBeInstanceOf<TrackerWebhookResult.Sent>().code shouldBe 204
        val request = server.takeRequest()
        request.method shouldBe "POST"
        request.path shouldBe "/hook"
        request.headers["Content-Type"]?.contains("application/json") shouldBe true
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        body["event"]?.jsonPrimitive?.content shouldBe "chapters_updated"
        body["timestamp"]?.jsonPrimitive?.content shouldBe "1700000000000"
        body["totalWorks"]?.jsonPrimitive?.content shouldBe "3"
        body["totalChapters"]?.jsonPrimitive?.content shouldBe "6"
        body["items"]?.jsonArray?.size shouldBe 3
        body["content"]?.jsonPrimitive?.content?.contains("Alpha") shouldBe true
    }

    @Test
    fun `a 4xx answer is reported as a failure`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = notifier().sendBatch(server.url("/hook").toString(), items(1))

        result.shouldBeInstanceOf<TrackerWebhookResult.Failed>().code shouldBe 400
    }

    @Test
    fun `a 5xx answer is reported as a failure`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = notifier().sendBatch(server.url("/hook").toString(), items(1))

        result.shouldBeInstanceOf<TrackerWebhookResult.Failed>().code shouldBe 500
    }

    @Test
    fun `an unreachable endpoint is reported without throwing`() = runBlocking<Unit> {
        val url = server.url("/hook").toString()
        server.close()

        val result = notifier().sendBatch(url, items(1))

        result.shouldBeInstanceOf<TrackerWebhookResult.Failed>()
    }

    @Test
    fun `a timeout is reported without throwing`() = runBlocking<Unit> {
        server.enqueue(MockResponse().apply { socketPolicy = SocketPolicy.NO_RESPONSE })

        val result = notifier(timeoutMs = 300L).sendBatch(server.url("/hook").toString(), items(1))

        result.shouldBeInstanceOf<TrackerWebhookResult.Failed>()
    }

    @Test
    fun `the test request uses the test event and lists no work`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = notifier().sendTest(server.url("/hook").toString(), timestamp = 9L)

        result.shouldBeInstanceOf<TrackerWebhookResult.Sent>().code shouldBe 200
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        body["event"]?.jsonPrimitive?.content shouldBe "test"
        body["timestamp"]?.jsonPrimitive?.content shouldBe "9"
        body["items"]?.jsonArray?.size shouldBe 0
    }

    @Test
    fun `a non-https url never reaches the network`() = runBlocking<Unit> {
        val plainUrl = server.url("/hook").toString().replaceFirst("https://", "http://")

        val result = notifier().sendBatch(plainUrl, items(1))

        result shouldBe TrackerWebhookResult.InvalidUrl
        server.requestCount shouldBe 0
    }

    @Test
    fun `a malformed url never reaches the network`() = runBlocking<Unit> {
        notifier().sendBatch("not a url", items(1)) shouldBe TrackerWebhookResult.InvalidUrl
        server.requestCount shouldBe 0
    }

    @Test
    fun `an empty batch sends nothing`() = runBlocking<Unit> {
        notifier().sendBatch(server.url("/hook").toString(), emptyList())
            .shouldBeInstanceOf<TrackerWebhookResult.Failed>()

        server.requestCount shouldBe 0
    }

    private fun notifier(timeoutMs: Long = 10_000L) = TrackerWebhookNotifier(
        baseClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build(),
    )

    private fun items(count: Int) = (1..count).map { index ->
        TrackerWebhookItem(
            title = if (index == 1) "Alpha" else "Work $index",
            source = "TEST",
            url = "https://example.org/work/$index",
            newChaptersCount = 2,
            latestChapterTitle = "Chapter $index",
        )
    }
}
