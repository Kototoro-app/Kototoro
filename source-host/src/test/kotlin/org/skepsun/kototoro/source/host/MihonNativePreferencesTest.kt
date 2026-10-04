package org.skepsun.kototoro.source.host

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.io.StringReader
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MihonNativePreferencesTest {
    @TempDir lateinit var root: Path
    private val source = "MIHON_9007199254740993"

    private inner class Harness : Closeable {
        val fixture = PreferenceRuntimeFixture(root)
        val loader = fixture.jars.compatibilityLoader()
        val store = FileSourcePreferenceStore(root.resolve("prefs"))
        val bridge = MihonPreferenceBridge(loader, store) { it() }
        val context = loader.loadClass("android.content.Context").getConstructor().newInstance()
        val registry = MihonJarRegistry(loader)
        val runtime = MihonSourceRuntime(registry, preferenceContext = context)
        val client = SourceProtocolClient(SourceEndpoint(runtime)) { "prefs-test" }
        init {
            loader.loadClass("fixture.PreferenceEnvironment").getField("preferences")
                .set(null, bridge.getSharedPreferences("source_9007199254740993"))
            registry.load(fixture.jar, fixture.jars.identity(fixture.jar))
        }
        override fun close() { registry.close(); bridge.close(); store.close(); loader.close() }
        fun values() = store.open("source_9007199254740993").snapshot()
        fun calls(): Int = registry.withSource(source) { it.javaClass.getField("calls").getInt(null) }
    }

    @Test
    fun `JSON exposes native controls values choices hierarchy and explicit unsupported behavior`() = runBlocking {
        Harness().use { h ->
            assertTrue(h.client.describe(source).isPreferencesSupported)
            val screen = h.client.getPreferences(source)
            assertEquals(SourceRef(source, "zh", "MANGA"), screen.source)
            assertEquals(SourcePreferenceKind.INFO, screen.nodes[0].kind)
            assertEquals(SourcePreferenceValue.Toggle(true), screen.nodes[1].value)
            assertEquals(SourcePreferenceValue.Text("初始"), screen.nodes[2].defaultValue)
            assertEquals(listOf("a", "b", "reject", "throws", "block"), screen.nodes[3].choices.map { it.value })
            assertEquals(SourcePreferenceKind.MULTI_CHOICE, screen.nodes[4].kind)
            assertEquals(SourcePreferenceKind.UNSUPPORTED, screen.nodes[5].kind)
            assertEquals(SourcePreferenceKind.UNSUPPORTED, screen.nodes[6].kind)
            assertFalse(screen.nodes[7].children.single().visible)
            assertEquals(listOf(SourcePreferenceKind.UNSUPPORTED, SourcePreferenceKind.UNSUPPORTED),
                screen.nodes.takeLast(2).map { it.kind })
        }
    }

    @Test
    fun `listener rejection acceptance and changed control state survive protocol and persistence`() = runBlocking {
        Harness().use { h ->
            val screen = h.client.getPreferences(source)
            val node = screen.nodes[3]
            val rejected = h.client.updatePreference(source, screen.revision, node.id, SourcePreferenceValue.Text("reject"))
            assertEquals(SourcePreferenceUpdateStatus.REJECTED, rejected.status)
            assertFalse(h.values().containsKey("domain"))
            assertNotEquals(screen.revision, rejected.screen.revision)
            val accepted = h.client.updatePreference(source, rejected.screen.revision, node.id, SourcePreferenceValue.Text("b"))
            assertEquals(SourcePreferenceUpdateStatus.ACCEPTED, accepted.status)
            assertEquals(SourcePreferenceValue.Text("b"), accepted.screen.nodes[3].value)
            assertFalse(accepted.screen.nodes[2].enabled)
            assertEquals(SourcePreferenceValue.Text("b"), h.values()["domain"])
            assertEquals(2, h.calls())
        }
        FileSourcePreferenceStore(root.resolve("prefs")).use { store ->
            assertEquals(SourcePreferenceValue.Text("b"), store.open("source_9007199254740993").snapshot()["domain"])
        }
    }

    @Test
    fun `invalid stale and unsupported changes are rejected before executable callbacks or writes`() = runBlocking {
        Harness().use { h ->
            val screen = h.client.getPreferences(source)
            suspend fun rejected(id: String, value: SourcePreferenceValue, code: SourceErrorCode,
                revision: String = screen.revision) {
                val failure = assertThrows(SourceRemoteException::class.java) {
                    runBlocking { h.client.updatePreference(source, revision, id, value) }
                }
                assertEquals(code, failure.error.code)
            }
            rejected(screen.nodes[3].id, SourcePreferenceValue.Text("not a choice"), SourceErrorCode.INVALID_ARGUMENT)
            rejected(screen.nodes[3].id, SourcePreferenceValue.Toggle(true), SourceErrorCode.INVALID_ARGUMENT)
            rejected(screen.nodes[4].id, SourcePreferenceValue.TextSet(setOf("bad")), SourceErrorCode.INVALID_ARGUMENT)
            rejected(screen.nodes[5].id, SourcePreferenceValue.Text("click"), SourceErrorCode.UNSUPPORTED_OPERATION)
            rejected(screen.nodes[6].id, SourcePreferenceValue.Text("input"), SourceErrorCode.UNSUPPORTED_OPERATION)
            rejected(screen.nodes[7].children.single().id, SourcePreferenceValue.Text("hidden"), SourceErrorCode.INVALID_ARGUMENT)
            rejected(screen.nodes[8].id, SourcePreferenceValue.Text("duplicate"), SourceErrorCode.UNSUPPORTED_OPERATION)
            rejected("unknown", SourcePreferenceValue.Text("unknown"), SourceErrorCode.INVALID_ARGUMENT)
            rejected(screen.nodes[3].id, SourcePreferenceValue.Text("a"), SourceErrorCode.INVALID_ARGUMENT, "other source revision")
            assertEquals(0, h.calls())
            assertTrue(h.values().isEmpty())
            val fresh = h.client.getPreferences(source)
            rejected(screen.nodes[3].id, SourcePreferenceValue.Text("a"), SourceErrorCode.INVALID_ARGUMENT)
            assertNotEquals(screen.revision, fresh.revision)
        }
    }

    @Test
    fun `toggle multi choice and text use actual typed native saves`() = runBlocking {
        Harness().use { h ->
            var screen = h.client.getPreferences(source)
            val values = listOf(1 to SourcePreferenceValue.Toggle(false), 2 to SourcePreferenceValue.Text("文字 + &"),
                4 to SourcePreferenceValue.TextSet(setOf("one", "two")))
            for ((index, value) in values) {
                val update = h.client.updatePreference(source, screen.revision, screen.nodes[index].id, value)
                assertEquals(SourcePreferenceUpdateStatus.ACCEPTED, update.status)
                screen = update.screen
                assertEquals(value, screen.nodes[index].value)
            }
            assertEquals(mapOf("enabled" to values[0].second, "comment" to values[1].second, "genres" to values[2].second), h.values())
        }
    }

    @Test
    fun `throwing listener retains its effects redacts errors and invalidates old revision`() = runBlocking {
        Harness().use { h ->
            val screen = h.client.getPreferences(source)
            val failure = assertThrows(SourceRemoteException::class.java) { runBlocking {
                h.client.updatePreference(source, screen.revision, screen.nodes[3].id, SourcePreferenceValue.Text("throws"))
            } }
            assertEquals(SourceErrorCode.RUNTIME_FAILURE, failure.error.code)
            assertFalse(failure.error.message.contains("token"))
            assertEquals(SourcePreferenceValue.Text("retained"), h.values()["sideEffect"])
            assertFalse(h.values().containsKey("domain"))
            val stale = assertThrows(SourceRemoteException::class.java) { runBlocking {
                h.client.updatePreference(source, screen.revision, screen.nodes[3].id, SourcePreferenceValue.Text("a"))
            } }
            assertEquals(SourceErrorCode.INVALID_ARGUMENT, stale.error.code)
        }
    }

    @Test
    fun `failed disk save reports persistence failure with published value and retries via native commit`() = runBlocking {
        Harness().use { h ->
            h.store.open("source_9007199254740993").edit(SourcePreferenceEdit(changes =
                mapOf("seed" to SourcePreferenceValue.Text("old"))))
            val screen = h.client.getPreferences(source)
            val path = Files.list(root.resolve("prefs")).use { it.filter { p -> p.toString().endsWith(".json") }.findFirst().get() }
            val preserved = path.resolveSibling("preserved.json")
            Files.move(path, preserved)
            Files.createDirectory(path)
            val update = h.client.updatePreference(source, screen.revision, screen.nodes[3].id, SourcePreferenceValue.Text("b"))
            assertEquals(SourcePreferenceUpdateStatus.PERSISTENCE_FAILED, update.status)
            assertEquals(SourcePreferenceValue.Text("b"), update.screen.nodes[3].value)
            Files.move(path, path.resolveSibling("obstruction"))
            Files.move(preserved, path)
            assertEquals(SourcePreferenceUpdateStatus.ACCEPTED, h.client.updatePreference(source,
                update.screen.revision, screen.nodes[3].id, SourcePreferenceValue.Text("b")).status)
        }
    }

    @Test
    fun `cancelled blocking listener keeps source gate across runtimes until its actual effects finish`() = runBlocking {
        Harness().use { h ->
            val screen = h.client.getPreferences(source)
            val type = h.registry.withSource(source) { it.javaClass }
            val entered = type.getField("entered").get(null) as CountDownLatch
            val release = type.getField("release").get(null) as CountDownLatch
            val first = launch {
                h.client.updatePreference(source, screen.revision, screen.nodes[3].id, SourcePreferenceValue.Text("block"))
                fail<Unit>("Cancelled preference update returned")
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                first.cancel()
                val other = MihonSourceRuntime(h.registry, preferenceContext = h.context)
                val waiting = async { other.getPreferences(source) }
                assertNull(withTimeoutOrNull(100) { waiting.await() })
                assertFalse(first.isCompleted)
                release.countDown()
                withTimeout(5000) { first.join() }
                assertEquals(SourcePreferenceValue.Text("block"), withTimeout(5000) { waiting.await() }.nodes[3].value)
                assertTrue(first.isCancelled)
            } finally { release.countDown() }
        }
    }

    @Test
    fun `stdio preference capability uses platform context after persistence binding`() = runBlocking {
        val fixture = PreferenceRuntimeFixture(root)
        val loader = fixture.jars.compatibilityLoader()
        var bridge: MihonPreferenceBridge? = null
        var initialized = false
        val platform = object : SourceHostPreferenceUiPlatform {
            override fun initialize(): ClassLoader = error("persistent initialization required")
            override fun initialize(preferences: SourcePreferenceStore): ClassLoader {
                val binding = MihonPreferenceBridge(loader, preferences) { it() }
                bridge = binding
                loader.loadClass("fixture.PreferenceEnvironment").getField("preferences")
                    .set(null, binding.getSharedPreferences("source_9007199254740993"))
                initialized = true
                return loader
            }
            override fun preferenceContext(): Any {
                check(initialized)
                return loader.loadClass("android.content.Context").getConstructor().newInstance()
            }
            override fun close() { bridge?.close(); loader.close() }
        }
        val calls = listOf(SourceCall.Describe(source), SourceCall.Preferences(source))
        val input = calls.mapIndexed { index, call -> SourceProtocolJson.encodeToString(
            SourceRequest(1, "stdio-prefs-$index", call)) }.joinToString("\n")
        val output = StringWriter()
        SourceHostJsonSession.run(SourceHostConfig("fixture", listOf(SourceHostJar(fixture.jar.toString(),
            fixture.jars.identity(fixture.jar))), preferenceDirectory = "prefs"), root,
            StringReader(input), output, platform)
        val responses = output.toString().lineSequence().filter(String::isNotBlank).map {
            SourceProtocolJson.decodeFromString<SourceResponse>(it)
        }.toList()
        assertTrue((responses[0].result as SourceResult.Descriptor).descriptor.isPreferencesSupported)
        assertEquals(10, (responses[1].result as SourceResult.Preferences).screen.nodes.size)
        FileSourcePreferenceStore(root.resolve("prefs")).close()
    }

    @Test
    fun `missing context and source reload do not accept old native revisions`() = runBlocking {
        Harness().use { h ->
            assertFalse(MihonSourceRuntime(h.registry).describe(source).isPreferencesSupported)
            assertThrows(SourceOperationUnsupportedException::class.java) { runBlocking {
                MihonSourceRuntime(h.registry).getPreferences(source)
            } }
            val screen = h.client.getPreferences(source)
            h.registry.unload(h.fixture.jars.identity(h.fixture.jar).packageName)
            h.registry.load(h.fixture.jar, h.fixture.jars.identity(h.fixture.jar))
            val failure = assertThrows(SourceRemoteException::class.java) { runBlocking {
                h.client.updatePreference(source, screen.revision, screen.nodes[3].id, SourcePreferenceValue.Text("a"))
            } }
            assertEquals(SourceErrorCode.INVALID_ARGUMENT, failure.error.code)
        }
    }
}
