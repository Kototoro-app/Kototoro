package org.skepsun.kototoro.source.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.net.URLClassLoader
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MihonSourceRuntimeTest {
    @TempDir lateinit var directory: Path

    private fun runHost(
        imageStore: SourceImageStore? = null,
        block: suspend CoroutineScope.(MihonJarRegistry, SourceProtocolClient) -> Unit,
    ) = runBlocking {
        val fixture = RuntimeJarFixture(directory)
        val path = fixture.sourceJar()
        fixture.jars.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                registry.load(path, fixture.jars.identity(path))
                var sequence = 0
                val client = SourceProtocolClient(SourceEndpoint(MihonSourceRuntime(registry, imageStore))) {
                    "r${sequence++}"
                }
                block(registry, client)
            }
        }
    }

    @Test
    fun `JSON executor exposes canonical sources query and stateless paging`() = runHost { _, client ->
        val source = client.getSources().single()
        assertEquals("MIHON_9007199254740993", source.name)
        assertEquals(SourcePagingMode.PAGE_INDEX, client.describe(source.name).pagingMode)
        val list = client.getList(source.name, 0, null, null).single()
        assertEquals("popular 1", list.title)
        assertEquals(MihonModelRules.contentId(list.url, source.name, list.title), list.id)
        assertEquals("https://fixture.invalid/cover.png", list.coverUrl)
        assertEquals("SAFE", list.contentRating)
        assertEquals(setOf("爱情", "safe", "nsfw"), list.tags.map { it.title }.toSet())
        assertEquals("latest 3", client.getList(source.name, 2, "UPDATED", null).single().title)
        assertEquals("甲 2", client.getList(source.name, 1, null, SourceFilter(query = "甲")).single().title)
        assertEquals("乙 1", client.getList(source.name, 0, null, SourceFilter(query = "乙")).single().title)
        assertEquals("甲 2", client.getList(source.name, 1, null, SourceFilter(query = "甲")).single().title)
    }

    @Test
    fun `partial details preserve identity cover chapter order and large timestamps`() = runHost { _, client ->
        val seed = client.getList(SOURCE, 0, null, null).single()
        val details = client.getDetails(seed, SourceDetailsFetchMode.FORCE_REFRESH)
        assertEquals(seed.id, details.id)
        assertEquals(seed.title, details.title)
        assertEquals(seed.url, details.url)
        assertEquals(seed.coverUrl, details.coverUrl)
        assertEquals(seed.sourceData, details.sourceData)
        assertEquals("<p>description</p>", details.description)
        val chapters = requireNotNull(details.chapters)
        assertEquals(listOf("old", "new"), chapters.map { it.title })
        assertEquals(listOf(1f, 2f), chapters.map { it.number })
        assertEquals(1720000000000L, chapters[0].uploadDate)
        assertEquals("team", chapters[0].branch)
        assertEquals(MihonModelRules.chapterId(chapters[0].url, SOURCE, seed.url), chapters[0].id)
        assertEquals(details, client.getDetails(seed, SourceDetailsFetchMode.ALLOW_CACHE))
    }

    @Test
    fun `memo survives DTO restoration and partial details with a new runtime instance`() = runHost { registry, client ->
        val seed = client.getList(SOURCE, 0, null, null).single().copy(
            sourceData = """{"token":"opaque","number":9007199254740993,"nested":{"key":"值"}}""",
        )
        val fresh = SourceProtocolClient(SourceEndpoint(MihonSourceRuntime(registry))) { "memo" }
        assertEquals(seed.sourceData, fresh.getDetails(seed, SourceDetailsFetchMode.FORCE_REFRESH).sourceData)
        val malformed = SourceProtocolClient(SourceEndpoint(MihonSourceRuntime(registry))) { "malformed" }
        assertNull(malformed.getDetails(seed.copy(sourceData = "[]"), SourceDetailsFetchMode.FORCE_REFRESH).sourceData)
    }

    @Test
    fun `pages retain origin index headers image fragment and deferred resolution context`() = runHost { _, client ->
        val details = client.getDetails(client.getList(SOURCE, 0, null, null).single(), SourceDetailsFetchMode.ALLOW_CACHE)
        val chapter = requireNotNull(details.chapters).first()
        val pages = client.getPages(chapter, null)
        assertEquals(MihonModelRules.pageId(chapter.id.toString(), 7), pages[0].id)
        assertTrue(pages[0].url.startsWith("mihon://resolve?"))
        assertEquals("https://fixture.invalid/image?index=7&page=https://fixture.invalid/chapter?title=漫画&key=+",
            client.getPageUrl(pages[0]))
        assertEquals("https://fixture.invalid/a.png#key=secret", client.getPageUrl(pages[1]))
        assertEquals(mapOf("Referer" to "https://fixture.invalid/origin"), pages[1].headers)
        assertEquals(SourcePageRequestContext(7, "https://fixture.invalid/chapter?title=漫画&key=+", null),
            pages[0].requestContext)
        assertEquals("https://fixture.invalid/raw.jpg", client.getPageUrl(pages[0].copy(
            url = "https://fixture.invalid/raw.jpg", requestContext = null)))
        val malformed = listOf("mihon://resolve?page_url=a&index=-1", "mihon://resolve?page_url=a b&index=1",
            "mihon://image?image_url=", "mihon://unknown?index=1")
        for (url in malformed) {
            val error = assertThrows(SourceRemoteException::class.java) {
                runBlocking { client.getPageUrl(pages[0].copy(url = url, requestContext = null)) }
            }
            assertEquals(SourceErrorCode.INVALID_ARGUMENT, error.error.code)
        }
    }

    @Test
    fun `suspended extension resumes mapping with its loader and independent requests correlate`() = runHost { _, client ->
        withTimeout(10000) {
            val suspended = async { client.getList(SOURCE, 87, null, null).single().title }
            val ordinary = async { client.getList(SOURCE, 1, null, null).single().title }
            assertEquals("async", suspended.await())
            assertEquals("popular 2", ordinary.await())
        }
    }

    @Test
    fun `unsupported operations arguments and extension failures remain structured and redacted`() = runHost { _, client ->
        fun failure(block: suspend () -> Unit): SourceRemoteException = assertThrows(SourceRemoteException::class.java) {
            runBlocking { block() }
        }
        assertTrue(client.getFilterOptions(SOURCE).availableTags.isNotEmpty())
        assertEquals(SourceErrorCode.UNSUPPORTED_OPERATION,
            failure { client.getList(SOURCE, 0, null, SourceFilter(year = 2026)) }.error.code)
        assertEquals(SourceErrorCode.INVALID_ARGUMENT, failure { client.getList(SOURCE, -1, null, null) }.error.code)
        assertEquals(SourceErrorCode.INVALID_ARGUMENT, failure { client.getList(SOURCE, 0, "UNKNOWN", null) }.error.code)
        assertEquals(SourceErrorCode.INVALID_ARGUMENT, failure { client.getList(SOURCE, Int.MAX_VALUE, null, null) }.error.code)
        val error = failure { client.getList(SOURCE, 98, null, null) }.error
        assertEquals(SourceErrorCode.RUNTIME_FAILURE, error.code)
        assertFalse(error.message.contains("private"))
        val seed = client.getList(SOURCE, 0, null, null).single()
        assertEquals(SourceErrorCode.UNSUPPORTED_OPERATION, failure { client.getRelated(seed) }.error.code)
        assertEquals(SourceErrorCode.SOURCE_UNAVAILABLE, failure { client.describe("MIHON_missing") }.error.code)
    }

    @Test
    fun `cancelled callback keeps retired JAR open until completion without returning content`() = runHost { registry, client ->
        var instance: Any? = null
        registry.withSource(SOURCE) { instance = it }
        val source = requireNotNull(instance)
        val loader = source.javaClass.classLoader as URLClassLoader
        val call = launch { client.getList(SOURCE, 76, null, null); fail<Unit>("Cancelled call returned content") }
        val started = MihonReflection.call(source, "getStarted") as CountDownLatch
        assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
        call.cancelAndJoin()
        assertTrue(registry.unload("fixture.extension"))
        assertTrue(registry.installed().isEmpty())
        MihonReflection.call(source, "complete") // Loads a class from the retired JAR before resuming the callback.
        assertThrows(ClassNotFoundException::class.java) { loader.loadClass("fixture.extension.AfterClose") }
    }

    @Test
    fun `close during live suspended call permits completion but prevents new calls`() = runHost { registry, client ->
        val source = registry.withSource(SOURCE) { it }
        val call = async { client.getList(SOURCE, 76, null, null).single().title }
        val started = MihonReflection.call(source, "getStarted") as CountDownLatch
        assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
        registry.close()
        MihonReflection.call(source, "complete")
        assertEquals("completed", withTimeout(10000) { call.await() })
        assertThrows(SourceJarException::class.java) { registry.installed() }
    }

    @Test
    fun `dynamic tree preserves all controls native indexes defaults nested groups and unsupported custom fields`() =
        runHost { _, client ->
            val tree = client.getDynamicFilters(SOURCE)
            assertTrue(client.describe(SOURCE).isDynamicFilteringSupported)
            assertTrue(client.describe(SOURCE).filterCapabilities.isTagsExclusionSupported)
            val nodes = MihonFilterRules.flatten(tree.nodes)
            assertEquals(SourceFilterKind.entries.toSet(), nodes.map { it.kind }.toSet())
            assertEquals(SourceFilterValue.Toggle(true), nodes.single { it.name == "Flag" }.state)
            assertEquals(listOf("ThemeInfo(name=爱情, key=a)", "key=fragment)", "Second"),
                nodes.single { it.name == "Choice" }.values)
            assertEquals("8.0.0|CHECKBOX|NestedFlag", nodes.single { it.name == "NestedFlag" }.id)
            assertEquals(nodes.size, nodes.map { it.id }.distinct().size)
        }

    @Test
    fun `legacy tag requests update real native controls then restore cached FilterList defaults`() = runHost { _, client ->
        val defaults = client.getDynamicFilters(SOURCE)
        val source = defaults.source
        fun tag(key: String) = SourceTag(key, key, source)
        val request = SourceFilter(query = "filters", tags = setOf(tag("top:Flag"), tag("top:Choice/Second"),
            tag("sort:top:Order/Date"), tag("text:top:AuthorExtra=中文=+&"), tag("Group/Nested/NestedFlag")),
            tagsExclude = setOf(tag("top:Genre")))
        assertEquals("true/2/2/1:false/default/中文=+&/true", client.getList(SOURCE, 0, null, request).single().title)
        assertEquals(defaults, client.getDynamicFilters(SOURCE))
        assertEquals("true/0/0/0:false/default/extra/false",
            client.getList(SOURCE, 0, null, SourceFilter(query = "filters")).single().title)
    }

    @Test
    fun `typed dynamic changes support fragment choices ascending sort Unicode text and checkbox defaults`() =
        runHost { _, client ->
            val defaults = client.getDynamicFilters(SOURCE)
            val byName = MihonFilterRules.flatten(defaults.nodes).associateBy { it.name }
            fun change(name: String, state: SourceFilterValue?) = SourceFilterChange(byName.getValue(name).id, state)
            val changes = listOf(change("Flag", SourceFilterValue.Toggle(false)),
                change("Genre", SourceFilterValue.TriState(SourceTriState.INCLUDE)),
                change("Choice", SourceFilterValue.Choice(1)), change("Order", SourceFilterValue.Sort(1, true)),
                change("Author", SourceFilterValue.Text("中文=+&")), change("NestedFlag", SourceFilterValue.Toggle(true)))
            assertEquals("false/1/1/1:true/中文=+&/extra/true", client.getList(SOURCE, 0, null,
                SourceFilter(query = "filters", dynamicFilters = changes)).single().title)
            assertEquals(defaults, client.getDynamicFilters(SOURCE))
            assertEquals("true/0/0/null/default/extra/false", client.getList(SOURCE, 0, null,
                SourceFilter(query = "filters", dynamicFilters = listOf(change("Order", null)))).single().title)
            assertEquals(defaults, client.getDynamicFilters(SOURCE))
        }

    @Test
    fun `filtered extension failures and invalid requests cannot leave partial native state`() = runHost { _, client ->
        val defaults = client.getDynamicFilters(SOURCE)
        val flag = MihonFilterRules.flatten(defaults.nodes).single { it.name == "Flag" }
        val choice = MihonFilterRules.flatten(defaults.nodes).single { it.name == "Choice" }
        val valid = SourceFilterChange(flag.id, SourceFilterValue.Toggle(false))
        val invalid = listOf(SourceFilterChange("stale", SourceFilterValue.Text("x")),
            SourceFilterChange(choice.id, SourceFilterValue.Choice(3)),
            SourceFilterChange(flag.id, SourceFilterValue.Text("x")))
        for (change in invalid) {
            val error = assertThrows(SourceRemoteException::class.java) { runBlocking {
                client.getList(SOURCE, 0, null, SourceFilter(query = "filters", dynamicFilters = listOf(valid, change)))
            } }
            assertEquals(SourceErrorCode.INVALID_ARGUMENT, error.error.code)
            assertEquals(defaults, client.getDynamicFilters(SOURCE))
        }
        val failure = assertThrows(SourceRemoteException::class.java) { runBlocking {
            client.getList(SOURCE, 0, null, SourceFilter(query = "failFilters", dynamicFilters = listOf(valid)))
        } }
        assertEquals(SourceErrorCode.RUNTIME_FAILURE, failure.error.code)
        assertEquals(defaults, client.getDynamicFilters(SOURCE))
        assertThrows(SourceRemoteException::class.java) { runBlocking {
            client.getList(SOURCE, 0, null, SourceFilter(tags = setOf(SourceTag("wrong", "top:Flag",
                defaults.source.copy(name = "MIHON_other")))))
        } }
    }

    @Test
    fun `cancelled search restores controls only on callback completion and other runtime instances wait`() =
        runHost { registry, client ->
            val defaults = client.getDynamicFilters(SOURCE)
            val source = registry.withSource(SOURCE) { it }
            val flag = MihonFilterRules.flatten(defaults.nodes).single { it.name == "Flag" }
            val first = launch { client.getList(SOURCE, 0, null, SourceFilter(query = "deferredFilters",
                dynamicFilters = listOf(SourceFilterChange(flag.id, SourceFilterValue.Toggle(false))))) }
            val started = MihonReflection.call(source, "getStarted") as CountDownLatch
            assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
            first.cancelAndJoin()
            assertEquals("false/0/0/0:false/default/extra/false", MihonReflection.call(source, "filterState"))
            val entered = CompletableDeferred<Unit>()
            val other = SourceEndpoint(MihonSourceRuntime(registry))
            val anotherClient = SourceProtocolClient(SourceTransport { entered.complete(Unit); other.exchange(it) }) { "another" }
            val next = async(start = CoroutineStart.UNDISPATCHED) { anotherClient.getDynamicFilters(SOURCE) }
            entered.await()
            assertNull(withTimeoutOrNull(100) { next.await() })
            MihonReflection.call(source, "completeFilters")
            assertEquals(defaults, withTimeout(10000) { next.await() })
        }

    @Test
    fun `unload and reload isolate old cancelled filter state from new extension generation`() = runHost { registry, client ->
        val defaults = client.getDynamicFilters(SOURCE)
        val source = registry.withSource(SOURCE) { it }
        val flag = MihonFilterRules.flatten(defaults.nodes).single { it.name == "Flag" }
        val call = launch { client.getList(SOURCE, 0, null, SourceFilter(query = "deferredFilters",
            dynamicFilters = listOf(SourceFilterChange(flag.id, SourceFilterValue.Toggle(false))))) }
        val started = MihonReflection.call(source, "getStarted") as CountDownLatch
        assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
        call.cancelAndJoin()
        registry.unload("fixture.extension")
        val path = directory.resolve("runtime.jar")
        val metadata = MihonJarInspector().inspect(path)
        registry.load(path, MihonJarIdentity(metadata.packageName, metadata.versionCode, metadata.sha256))
        assertEquals(defaults, client.getDynamicFilters(SOURCE))
        MihonReflection.call(source, "completeFilters")
        assertEquals(defaults, client.getDynamicFilters(SOURCE))
        assertEquals("true/0/0/0:false/default/extra/false", MihonReflection.call(source, "filterState"))
    }

    @Test
    fun `novel extension is a TSUNDOKU source whose chapter text is assembled from page text`() = runBlocking {
        val fixture = RuntimeJarFixture(directory)
        val path = fixture.novelJar()
        fixture.jars.compatibilityLoader().use { compatibility ->
            MihonJarRegistry(compatibility).use { registry ->
                registry.load(path, fixture.jars.identity(path))
                val client = SourceProtocolClient(SourceEndpoint(MihonSourceRuntime(registry, null))) { "n" }
                val source = client.getSources().single()
                assertEquals("TSUNDOKU_4242", source.name)
                assertEquals("NOVEL", source.contentType)
                val described = client.describe(source.name)
                assertTrue(described.isChapterContentSupported)

                val chapter = SourceChapter(
                    1, "第一章", 1f, 0, "/chapter/1", null, 0, null, source,
                )
                // Page 0 is plain text, 1 throws, 2 is blank, 3 is markup, 4 is an image: what is readable survives.
                // Plain text is escaped into a paragraph; markup an extension returns is left for the reader to sanitise.
                val content = requireNotNull(client.getChapterContent(chapter, null))
                assertEquals("<p>Line one &amp; &lt;two&gt;</p>\n<p>Marked <b>up</b> text</p>", content.html)
                assertEquals(listOf("https://fixture.invalid/figure.png"), content.images.map { it.url })
                // Every page failing is an error rather than an empty chapter.
                assertThrows(Exception::class.java) {
                    runBlocking { client.getChapterContent(chapter.copy(url = "/chapter/fail"), null) }
                }
            }
        }
    }

    @Test
    fun `manga source does not claim chapter content and refuses the call`() = runHost { _, client ->
        assertFalse(client.describe(SOURCE).isChapterContentSupported)
        val failure = assertThrows(SourceRemoteException::class.java) {
            runBlocking {
                client.getChapterContent(
                    SourceChapter(1, "c", 1f, 0, "/chapter/1", null, 0, null, client.getSources().single()), null,
                )
            }
        }
        assertEquals(SourceErrorCode.UNSUPPORTED_OPERATION, failure.error.code)
    }

    companion object { private const val SOURCE = "MIHON_9007199254740993" }

    private suspend fun SourceProtocolClient.fixturePages(): List<SourcePage> {
        val details = getDetails(getList(SOURCE, 0, null, null).single(), SourceDetailsFetchMode.ALLOW_CACHE)
        return getPages(requireNotNull(details.chapters).first(), null)
    }

    @Test
    fun `JSON image operation preserves original Page fields and materializes response bytes`() =
        runHost(FileSourceImageStore(directory.resolve("images"))) { registry, client ->
            assertTrue(client.describe(SOURCE).isImageFetchingSupported)
            val source = registry.withSource(SOURCE) { it }
            val pages = client.fixturePages()
            val unresolved = client.fetchImage(pages[0])
            assertEquals("7|https://fixture.invalid/chapter?title=漫画&key=+|" +
                "https://fixture.invalid/image?index=7&page=https://fixture.invalid/chapter?title=漫画&key=+",
                MihonReflection.call(source, "getImageContext"))
            val resolved = client.fetchImage(pages[1])
            assertEquals("12|https://fixture.invalid/origin|https://fixture.invalid/a.png#key=secret",
                MihonReflection.call(source, "getImageContext"))
            assertEquals(pages[1].id, resolved.pageId)
            assertEquals(pages[1].source, resolved.source)
            assertEquals("image/png", resolved.contentType)
            assertEquals(unresolved.relativePath, resolved.relativePath)
            assertArrayEquals(FileSourceImageStoreTest.png,
                Files.readAllBytes(directory.resolve("images").resolve(resolved.relativePath)))
            assertEquals(resolved, client.fetchImage(pages[1].copy(requestContext = null)))
            assertEquals(3, MihonReflection.call(source, "getImageCloses"))
        }

    @Test
    fun `image capability is explicit and native uri or missing page context is rejected`() = runHost { registry, client ->
        assertFalse(client.describe(SOURCE).isImageFetchingSupported)
        val page = client.fixturePages().first()
        fun failure(endpoint: SourceProtocolClient, original: SourcePage) =
            assertThrows(SourceRemoteException::class.java) { runBlocking { endpoint.fetchImage(original) } }.error.code
        assertEquals(SourceErrorCode.UNSUPPORTED_OPERATION, failure(client, page))
        val enabled = SourceProtocolClient(SourceEndpoint(MihonSourceRuntime(registry,
            FileSourceImageStore(directory.resolve("images"))))) { "images" }
        assertEquals(SourceErrorCode.INVALID_ARGUMENT,
            failure(enabled, page.copy(url = "https://fixture.invalid/image.png", requestContext = null)))
        val uriPage = page.copy(requestContext = requireNotNull(page.requestContext).copy(uri = "content://fixture/page"))
        assertEquals(SourceErrorCode.UNSUPPORTED_OPERATION, failure(enabled, uriPage))
        assertEquals(SourceErrorCode.INVALID_ARGUMENT,
            failure(enabled, page.copy(url = "mihon://image?page_url=a&image_url=b&index=-1", requestContext = null)))
    }

    @Test
    fun `failed image responses close and missing results release the source gate`() =
        runHost(FileSourceImageStore(directory.resolve("images"))) { registry, client ->
            val source = registry.withSource(SOURCE) { it }
            val page = client.fixturePages().last()
            for (mode in listOf("status", "html", "missing")) {
                MihonReflection.call(source, "setImageMode", mode)
                val failure = assertThrows(SourceRemoteException::class.java) { runBlocking { client.fetchImage(page) } }
                assertEquals(SourceErrorCode.RUNTIME_FAILURE, failure.error.code)
                withTimeout(10000) { client.describe(SOURCE) }
                assertEquals(0L, Files.list(directory.resolve("images")).use { it.count() })
            }
            assertEquals(2, MihonReflection.call(source, "getImageCloses"))
        }

    @Test
    fun `late image response after cancellation closes under retired extension loader`() =
        runHost(FileSourceImageStore(directory.resolve("images"))) { registry, client ->
            val source = registry.withSource(SOURCE) { it }
            val loader = source.javaClass.classLoader as URLClassLoader
            val page = client.fixturePages().last()
            MihonReflection.call(source, "setImageMode", "deferred")
            val call = launch { client.fetchImage(page); fail<Unit>("Cancelled image returned") }
            val started = MihonReflection.call(source, "getImageStarted") as CountDownLatch
            assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
            call.cancelAndJoin()
            assertTrue(registry.unload("fixture.extension"))
            MihonReflection.call(source, "completeImage")
            assertEquals(1, MihonReflection.call(source, "getImageCloses"))
            assertEquals(0L, Files.list(directory.resolve("images")).use { it.count() })
            assertThrows(ClassNotFoundException::class.java) { loader.loadClass("fixture.extension.AfterClose") }
        }

    @Test
    fun `cancellation closes a blocked body before releasing retired JAR and cleans staging files`() =
        runHost(FileSourceImageStore(directory.resolve("images"))) { registry, client ->
            val source = registry.withSource(SOURCE) { it }
            val loader = source.javaClass.classLoader as URLClassLoader
            val page = client.fixturePages().last()
            MihonReflection.call(source, "setImageMode", "blocking")
            val call = launch { client.fetchImage(page); fail<Unit>("Cancelled stream returned") }
            val started = MihonReflection.call(source, "getImageStarted") as CountDownLatch
            assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
            registry.unload("fixture.extension")
            withTimeout(10000) { call.cancelAndJoin() }
            assertEquals(1, MihonReflection.call(source, "getImageCloses"))
            assertEquals(0L, Files.list(directory.resolve("images")).use { it.count() })
            assertThrows(ClassNotFoundException::class.java) { loader.loadClass("fixture.extension.AfterClose") }
        }

    @Test
    fun `completion hook failure disposes the transformed response and preserves the primary failure`() =
        runHost { registry, client ->
            val original = requireNotNull(client.fixturePages().last().requestContext)
            val source = registry.withSource(SOURCE) { it }
            val primary = IllegalStateException("fixture completion failure")
            val failure = assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    registry.withSourceSuspending(SOURCE) { lease ->
                        val page = MihonReflection.create(lease.loader, "Page", original.index,
                            original.url, original.imageUrl, null)
                        MihonReflection.callSuspending(lease, "getImage", page,
                            onCompleted = { throw primary },
                            onResult = { MihonImageResponse(requireNotNull(it), lease) },
                            onDiscarded = {
                                (it as MihonImageResponse).close()
                                throw IOException("fixture cleanup failure")
                            })
                    }
                }
            }
            // Coroutine stack recovery may copy the exception, retaining the original as its cause.
            assertEquals(primary.message, failure.message)
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === primary })
            assertEquals("fixture cleanup failure", primary.suppressed.single().message)
            assertEquals(1, MihonReflection.call(source, "getImageCloses"))
        }
}
