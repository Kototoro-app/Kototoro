package org.skepsun.kototoro.core.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

class SourceProtocolTest {
    private val source = SourceRef("MIHON_-9223372036854775808", "zh", "MANGA")
    private val chapter = SourceChapter(
        Long.MIN_VALUE, null, 1.5f, 2, "/chapter?x=1#context", "汉化", Long.MAX_VALUE, "繁體", source,
        sourceData = "{\"memo\":\"原样\\n保留\"}", ebookFormats = listOf("EPUB", "CBZ"),
    )
    private val content = SourceContent(
        Long.MAX_VALUE, "漫画 📚", linkedSetOf("別名", "Alias"), "/manga", "https://fixture.test/manga",
        -1f, null, null, setOf(SourceTag("类型", "key", source)), null, setOf("作者"), source,
        description = "<p>内容</p>", chapters = listOf(chapter), sourceData = "{\"opaque\":true}",
    )
    private val page = SourcePage(
        -9007199254740993L, "/page#unscramble", null, source, mapOf("Referer" to "https://fixture.test"),
        listOf(SourceExternalTrack("https://fixture.test/sub", "zh", emptyMap())), "线路", 1080,
        requestContext = SourcePageRequestContext(12, "/原始页面?key=+", "/image#unscramble"),
    )
    private val image = SourceImageArtifact(source, page.id, "${"a".repeat(64)}.img", "a".repeat(64),
        Long.MAX_VALUE, "image/png")
    private val descriptor = SourceDescriptor(
        source, linkedSetOf("POPULARITY", "RELEVANCE"), "POPULARITY",
        SourceFilterCapabilities(true, true, true, false, true, true, true, true), SourcePagingMode.PAGE_INDEX,
    )
    private val options = SourceFilterOptions(
        content.tags, listOf(SourceTagGroup("分类", content.tags, true)), setOf("ONGOING"),
        setOf("SAFE"), setOf("MANGA"), setOf("SEINEN"), setOf("zh-Hant-TW"),
    )
    private val calls = mutableListOf<SourceCall>()
    private val runtime = object : SourceRuntime {
        override suspend fun getSources(): List<SourceRef> {
            calls += SourceCall.Sources
            return listOf(source)
        }
        override suspend fun describe(sourceName: String): SourceDescriptor {
            calls += SourceCall.Describe(sourceName)
            return descriptor
        }
        override suspend fun getFilterOptions(sourceName: String): SourceFilterOptions {
            calls += SourceCall.Filters(sourceName)
            return options
        }
        override suspend fun getDynamicFilters(sourceName: String): SourceDynamicFilters {
            calls += SourceCall.DynamicFilters(sourceName)
            return SourceDynamicFilters(source, emptyList())
        }
        override suspend fun getPreferences(sourceName: String): SourcePreferenceScreen {
            calls += SourceCall.Preferences(sourceName)
            return SourcePreferenceScreen(source, "revision", emptyList())
        }
        override suspend fun updatePreference(
            sourceName: String, revision: String, nodeId: String, value: SourcePreferenceValue,
        ): SourcePreferenceUpdate {
            calls += SourceCall.UpdatePreference(sourceName, revision, nodeId, value)
            return SourcePreferenceUpdate(SourcePreferenceUpdateStatus.REJECTED,
                SourcePreferenceScreen(source, "next", emptyList()))
        }
        override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?)
            : List<SourceContent> {
            calls += SourceCall.ListContent(sourceName, offset, order, filter)
            return listOf(content)
        }
        override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode): SourceContent {
            calls += SourceCall.Details(content, fetchMode)
            return content
        }
        override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage> {
            calls += SourceCall.Pages(chapter, nextChapterUrl)
            return listOf(page)
        }
        override suspend fun getPageUrl(page: SourcePage): String {
            calls += SourceCall.PageUrl(page)
            return "https://fixture.test/image#unscramble"
        }
        override suspend fun getRelated(content: SourceContent): List<SourceContent> {
            calls += SourceCall.Related(content)
            return listOf(content)
        }
        override suspend fun fetchImage(page: SourcePage): SourceImageArtifact {
            calls += SourceCall.Image(page)
            return image
        }
        override suspend fun fetchCover(content: SourceContent, large: Boolean): SourceCoverArtifact {
            calls += SourceCall.Cover(content, large)
            return SourceCoverArtifact(source, content.id, image.relativePath, image.sha256,
                image.byteSize, image.contentType)
        }
    }
    private val endpoint = SourceEndpoint(runtime)
    private val client = SourceProtocolClient(endpoint) { "fixture-id" }

    @Test
    fun `cover artifacts preserve content identity and never expose page identity or absolute paths`() = runTest {
        val artifact = client.fetchCover(content, true)
        val encoded = SourceProtocolJson.encodeToString(artifact)
        assertTrue(encoded.contains("\"contentId\":\"${Long.MAX_VALUE}\""))
        assertTrue(encoded.contains("\"byteSize\":\"${Long.MAX_VALUE}\""))
        assertFalse(encoded.contains("pageId"))
        assertFalse(encoded.contains("https://"))
        assertEquals(artifact, SourceProtocolJson.decodeFromString<SourceCoverArtifact>(encoded))
        assertThrows(IllegalArgumentException::class.java) { artifact.copy(relativePath = "../cover.img") }
        assertThrows(IllegalArgumentException::class.java) { artifact.copy(byteSize = 0) }
    }

    @Test
    fun `hosts without cover execution return unsupported and legacy descriptors default capability to false`() = runTest {
        val legacy = SourceProtocolClient(SourceEndpoint(object : SourceRuntime by runtime {
            override suspend fun fetchCover(content: SourceContent, large: Boolean): SourceCoverArtifact =
                throw SourceOperationUnsupportedException()
        })) { "legacy-cover" }
        val failure = assertThrows(SourceRemoteException::class.java) { runBlocking { legacy.fetchCover(content) } }
        assertEquals(SourceErrorCode.UNSUPPORTED_OPERATION, failure.error.code)
        val encoded = SourceProtocolJson.encodeToString(descriptor)
            .replace(",\"isCoverFetchingSupported\":false", "")
        assertFalse(SourceProtocolJson.decodeFromString<SourceDescriptor>(encoded).isCoverFetchingSupported)
    }

    @Test
    fun `client endpoint round trip covers all operations and forwards arguments`() = runTest {
        val filter = SourceFilter(
            query = "中文 + &", tags = content.tags, locale = "zh-Hant-TW", originalLocale = "ja-JP",
            states = setOf("ONGOING"), yearFrom = 2020, yearTo = 2026, author = "作者",
        )
        assertEquals(listOf(source), client.getSources())
        assertEquals(descriptor, client.describe(source.name))
        assertEquals(options, client.getFilterOptions(source.name))
        assertEquals(SourceDynamicFilters(source, emptyList()), client.getDynamicFilters(source.name))
        assertEquals(SourcePreferenceScreen(source, "revision", emptyList()), client.getPreferences(source.name))
        assertEquals(SourcePreferenceUpdate(SourcePreferenceUpdateStatus.REJECTED,
            SourcePreferenceScreen(source, "next", emptyList())), client.updatePreference(
            source.name, "revision", "node", SourcePreferenceValue.Text("input")))
        assertEquals(listOf(content), client.getList(source.name, 37, "RELEVANCE", filter))
        assertEquals(content, client.getDetails(content, SourceDetailsFetchMode.FORCE_REFRESH))
        assertEquals(listOf(page), client.getPages(chapter, "/next?x=1"))
        assertEquals("https://fixture.test/image#unscramble", client.getPageUrl(page))
        assertEquals(image, client.fetchImage(page))
        assertEquals(content.id, client.fetchCover(content, large = true).contentId)
        assertEquals(listOf(content), client.getRelated(content))
        assertEquals(
            listOf(
                SourceCall.Sources, SourceCall.Describe(source.name), SourceCall.Filters(source.name),
                SourceCall.DynamicFilters(source.name),
                SourceCall.Preferences(source.name),
                SourceCall.UpdatePreference(source.name, "revision", "node", SourcePreferenceValue.Text("input")),
                SourceCall.ListContent(source.name, 37, "RELEVANCE", filter),
                SourceCall.Details(content, SourceDetailsFetchMode.FORCE_REFRESH),
                SourceCall.Pages(chapter, "/next?x=1"), SourceCall.PageUrl(page), SourceCall.Image(page),
                SourceCall.Cover(content, true),
                SourceCall.Related(content),
            ), calls,
        )
    }

    @Test
    fun `wire stores long extrema as decimal strings without losing precision`() {
        val json = SourceProtocolJson.encodeToString(content)
        val tree = SourceProtocolJson.parseToJsonElement(json).jsonObject
        assertEquals(JsonPrimitive(Long.MAX_VALUE.toString()), tree["id"])
        assertTrue(json.contains("\"id\":\"${Long.MIN_VALUE}\""))
        assertEquals(content, SourceProtocolJson.decodeFromString<SourceContent>(json))
        val encodedPage = SourceProtocolJson.encodeToString(page)
        assertTrue(encodedPage.contains("\"id\":\"-9007199254740993\""))
        assertEquals(page, SourceProtocolJson.decodeFromString<SourcePage>(encodedPage))
        val encodedImage = SourceProtocolJson.encodeToString(image)
        assertTrue(encodedImage.contains("\"byteSize\":\"${Long.MAX_VALUE}\""))
        assertEquals(image, SourceProtocolJson.decodeFromString<SourceImageArtifact>(encodedImage))
    }

    @Test
    fun `image metadata disallows arbitrary file paths and invalid sizes or native indexes`() {
        assertThrows(IllegalArgumentException::class.java) { image.copy(relativePath = "../image.png") }
        assertThrows(IllegalArgumentException::class.java) { image.copy(byteSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { image.copy(sha256 = "invalid") }
        assertThrows(IllegalArgumentException::class.java) { image.copy(contentType = "text/html") }
        assertThrows(IllegalArgumentException::class.java) { SourcePageRequestContext(-1, "url", null) }
    }

    @Test
    fun `absent chapters and headers remain distinct from loaded empty collections`() {
        listOf<List<SourceChapter>?>(null, emptyList()).forEach { chapters ->
            val original = content.copy(chapters = chapters, sourceData = "")
            assertEquals(original, SourceProtocolJson.decodeFromString<SourceContent>(
                SourceProtocolJson.encodeToString(original),
            ))
        }
        listOf<Map<String, String>?>(null, emptyMap()).forEach { headers ->
            val original = page.copy(headers = headers)
            assertEquals(original, SourceProtocolJson.decodeFromString<SourcePage>(
                SourceProtocolJson.encodeToString(original),
            ))
        }
    }

    @Test
    fun `same-version additive fields are accepted`() = runTest {
        val json = """{"version":1,"requestId":"future","extra":true,
            "call":{"operation":"sources","extra":42}}"""
        assertEquals(listOf(source), response(json).let { (it.result as SourceResult.Sources).sources })
    }

    @Test
    fun `unsupported version wins before unknown operation dispatch`() = runTest {
        val result = response("""{"version":2,"requestId":"v2","call":{"operation":"new"}}""")
        assertEquals("v2", result.requestId)
        assertEquals(SourceErrorCode.UNSUPPORTED_VERSION, result.error?.code)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `malformed envelopes return errors without executing runtime`() = runTest {
        listOf("{", "[]", "null", "{}", """{"version":"1","requestId":"x"}""",
            """{"version":1,"requestId":7}""", """{"version":1,"requestId":""}""").forEach { body ->
            assertEquals(SourceErrorCode.INVALID_REQUEST, response(body).error?.code)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `unknown operation preserves correlation in invalid request response`() = runTest {
        val result = response("""{"version":1,"requestId":"wrong-op","call":{"operation":"typo"}}""")
        assertEquals("wrong-op", result.requestId)
        assertEquals(SourceErrorCode.INVALID_REQUEST, result.error?.code)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `invalid typed payload does not call runtime`() = runTest {
        val json = SourceProtocolJson.encodeToString(SourceRequest(1, "invalid", SourceCall.Details(content)))
            .replace("ALLOW_CACHE", "INVALID")
        assertEquals(SourceErrorCode.INVALID_REQUEST, response(json).error?.code)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `runtime error codes survive transport and unexpected errors are redacted`() = runTest {
        listOf(
            SourceUnavailableException(source.name) to SourceErrorCode.SOURCE_UNAVAILABLE,
            SourceInvalidArgumentException() to SourceErrorCode.INVALID_ARGUMENT,
            IllegalStateException("secret-token https://private.test") to SourceErrorCode.RUNTIME_FAILURE,
        ).forEach { (error, code) ->
            val failing = object : SourceRuntime by runtime {
                override suspend fun getSources(): List<SourceRef> = throw error
            }
            val failure = expectFailure<SourceRemoteException> {
                SourceProtocolClient(SourceEndpoint(failing)) { "failed" }.getSources()
            }
            assertEquals(code, failure.error.code)
            assertFalse(failure.message.orEmpty().contains("secret-token"))
            assertFalse(failure.message.orEmpty().contains("private.test"))
        }
    }

    @Test
    fun `local failure observer receives the exact request and observer failure never leaks into the wire`() = runTest {
        val original = IllegalStateException("secret-token https://private.test")
        val failing = object : SourceRuntime by runtime {
            override suspend fun getSources(): List<SourceRef> = throw original
        }
        var reported: SourceRequest? = null
        var reportedError: Exception? = null
        val transport = SourceEndpoint(failing) { request, error ->
            reported = request
            reportedError = error
            throw IllegalStateException("observer-secret")
        }
        val failure = expectFailure<SourceRemoteException> {
            SourceProtocolClient(transport) { "diagnostic-request" }.getSources()
        }
        assertEquals("diagnostic-request", failure.requestId)
        assertTrue(reportedError === original)
        assertEquals(SourceRequest(1, "diagnostic-request", SourceCall.Sources), reported)
        assertEquals(SourceErrorCode.RUNTIME_FAILURE, failure.error.code)
        assertEquals("Source operation failed", failure.message)
    }

    @Test
    fun `runtime cancellation is never encoded as failure`() = runTest {
        val cancelled = CancellationException("fixture cancellation")
        val failing = object : SourceRuntime by runtime {
            override suspend fun getSources(): List<SourceRef> = throw cancelled
        }
        val result = expectFailure<CancellationException> {
            SourceProtocolClient(SourceEndpoint(failing)) { "cancelled" }.getSources()
        }
        assertTrue(result === cancelled)
    }

    @Test
    fun `cancelling caller cancels suspended transport`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val transport = SourceTransport { entered.complete(Unit); awaitCancellation() }
        val job = async { SourceProtocolClient(transport) { "cancel" }.getSources() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelled runtime cannot turn cancellation into success`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val swallowing = object : SourceRuntime by runtime {
            override suspend fun getSources(): List<SourceRef> {
                entered.complete(Unit)
                try { awaitCancellation() } catch (_: CancellationException) { return emptyList() }
            }
        }
        val job = async { SourceProtocolClient(SourceEndpoint(swallowing)) { "cancel" }.getSources() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `client rejects mismatched correlation version and result type`() = runTest {
        listOf(
            SourceResponse(requestId = "other", result = SourceResult.Sources(emptyList())),
            SourceResponse(version = 2, requestId = "fixture-id", result = SourceResult.Sources(emptyList())),
            SourceResponse(requestId = "fixture-id", result = SourceResult.PageUrl("https://fixture.test")),
        ).forEach { result ->
            val body = SourceProtocolJson.encodeToString(result)
            expectFailure<SourceProtocolException> {
                SourceProtocolClient(SourceTransport { body }) { "fixture-id" }.getSources()
            }
        }
    }

    @Test
    fun `client rejects missing or ambiguous response payload`() = runTest {
        listOf(
            """{"version":1,"requestId":"fixture-id"}""",
            """{"version":1,"requestId":"fixture-id","result":{"operation":"sources","sources":[]},
                "error":{"code":"RUNTIME_FAILURE","message":"failed"}}""",
            "not-json",
        ).forEach { body ->
            expectFailure<SourceProtocolException> {
                SourceProtocolClient(SourceTransport { body }) { "fixture-id" }.getSources()
            }
        }
    }

    @Test
    fun `empty request ID is rejected before transport`() = runTest {
        var exchanges = 0
        expectFailure<IllegalArgumentException> {
            SourceProtocolClient(SourceTransport { exchanges++; "" }) { "" }.getSources()
        }
        assertEquals(0, exchanges)
    }

    @Test
    fun `null order and filter are preserved rather than normalized`() = runTest {
        client.getList(source.name, -1, null, null)
        assertEquals(SourceCall.ListContent(source.name, -1), calls.single())
        client.getList(source.name, 0, "POPULARITY", SourceFilter())
        assertEquals(SourceFilter(), (calls.last() as SourceCall.ListContent).filter)
    }

    @Test
    fun `concurrent calls can complete in reverse order with correct IDs`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var nextId = 0
        val transport = SourceTransport { request ->
            val decoded = SourceProtocolJson.decodeFromString<SourceRequest>(request)
            if (decoded.requestId == "1") { firstEntered.complete(Unit); releaseFirst.await() }
            endpoint.exchange(request)
        }
        val concurrent = SourceProtocolClient(transport) { (++nextId).toString() }
        val first = async { concurrent.getSources() }
        firstEntered.await()
        assertEquals(descriptor, concurrent.describe(source.name))
        releaseFirst.complete(Unit)
        assertEquals(listOf(source), first.await())
    }

    @Test
    fun `invalid long payload fails before parser dispatch`() = runTest {
        val json = SourceProtocolJson.encodeToString(SourceRequest(1, "overflow", SourceCall.Details(content)))
            .replace("\"id\":\"${Long.MAX_VALUE}\"", "\"id\":\"9223372036854775808\"")
        assertEquals(SourceErrorCode.INVALID_REQUEST, response(json).error?.code)
        assertNull(response(json).result)
        assertTrue(calls.isEmpty())
    }

    private suspend fun response(body: String) =
        SourceProtocolJson.decodeFromString<SourceResponse>(endpoint.exchange(body))

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue(error is T, "Expected ${T::class.simpleName}, got $error")
            return error as T
        }
        return fail("Expected ${T::class.simpleName}")
    }
}
