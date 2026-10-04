package org.skepsun.kototoro.source.host

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.nio.file.Path

class AniyomiPreferencesTest {
    @TempDir lateinit var root: Path

    @Test
    fun `anime sources expose their settings screen and persist changes in their own store`() = runBlocking {
        val fixture = PreferenceRuntimeFixture(root)
        val jar = fixture.animeJar
        val loader = fixture.jars.compatibilityLoader()
        FileSourcePreferenceStore(root.resolve("prefs")).use { store ->
            MihonPreferenceBridge(loader, store) { it() }.use { bridge ->
                loader.loadClass("fixture.PreferenceEnvironment").getField("animePreferences")
                    .set(null, bridge.getSharedPreferences("source_4243"))
                val context = loader.loadClass("android.content.Context").getConstructor().newInstance()
                MihonJarRegistry(loader).use { registry ->
                    registry.load(jar, fixture.jars.identity(jar))
                    val client = SourceProtocolClient(SourceEndpoint(AniyomiSourceRuntime(registry, preferenceContext = context))) { "p" }
                    val source = "ANIYOMI_4243"
                    assertTrue(client.describe(source).isPreferencesSupported)
                    val screen = client.getPreferences(source)
                    assertEquals(listOf("首选画质", "优先配音"), screen.nodes.map { it.title })
                    val quality = screen.nodes.first { it.key == "preferred_quality" }
                    assertEquals(SourcePreferenceKind.CHOICE, quality.kind)
                    assertEquals(SourcePreferenceValue.Text("1080"), quality.value)
                    val updated = client.updatePreference(source, screen.revision, quality.id, SourcePreferenceValue.Text("720"))
                    assertEquals(SourcePreferenceUpdateStatus.ACCEPTED, updated.status)
                    val dub = updated.screen.nodes.first { it.key == "prefer_dub" }
                    val toggled = client.updatePreference(source, updated.screen.revision, dub.id, SourcePreferenceValue.Toggle(true))
                    assertEquals(SourcePreferenceUpdateStatus.ACCEPTED, toggled.status)
                    val values = store.open("source_4243").snapshot()
                    assertEquals(SourcePreferenceValue.Text("720"), values["preferred_quality"])
                    assertEquals(SourcePreferenceValue.Toggle(true), values["prefer_dub"])
                }
                // Without a context the runtime does not claim settings.
                MihonJarRegistry(loader).use { registry ->
                    registry.load(jar, fixture.jars.identity(jar))
                    assertFalse(SourceProtocolClient(SourceEndpoint(AniyomiSourceRuntime(registry))) { "q" }
                        .describe("ANIYOMI_4243").isPreferencesSupported)
                }
            }
        }
        loader.close()
    }
}