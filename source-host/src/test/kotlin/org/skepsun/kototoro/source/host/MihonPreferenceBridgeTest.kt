package org.skepsun.kototoro.source.host

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.io.StringReader
import java.io.StringWriter

internal fun preferenceJarFixture(root: Path): JarFixture = JarFixture(root).also { fixture ->
        fixture.compile(root.resolve("prefs-api"), fixture.apiClasses, mapOf(
            "android/content/SharedPreferences.java" to """
                package android.content;
                import java.util.*;
                public interface SharedPreferences {
                    Map<String, ?> getAll(); String getString(String key, String fallback);
                    Set<String> getStringSet(String key, Set<String> fallback);
                    int getInt(String key, int fallback); long getLong(String key, long fallback);
                    float getFloat(String key, float fallback); boolean getBoolean(String key, boolean fallback);
                    boolean contains(String key); Editor edit();
                    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
                    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
                    interface Editor {
                        Editor putString(String key, String value); Editor putStringSet(String key, Set<String> value);
                        Editor putInt(String key, int value); Editor putLong(String key, long value);
                        Editor putFloat(String key, float value); Editor putBoolean(String key, boolean value);
                        Editor remove(String key); Editor clear(); boolean commit(); void apply();
                    }
                    interface OnSharedPreferenceChangeListener {
                        void onSharedPreferenceChanged(SharedPreferences preferences, String key);
                    }
                }
            """.trimIndent(),
            "fixture/PreferenceEnvironment.java" to """
                package fixture;
                public class PreferenceEnvironment { public static android.content.SharedPreferences preferences, animePreferences; }
            """.trimIndent(),
        ))
}

class MihonPreferenceBridgeTest {
    @TempDir lateinit var root: Path

    private fun fixture() = preferenceJarFixture(root)

    @Test
    fun `parent Android interfaces preserve every value type defaults and mismatched type failures`() {
        val fixture = fixture()
        fixture.compatibilityLoader().use { loader ->
            FileSourcePreferenceStore(root.resolve("prefs")).use { store ->
                MihonPreferenceBridge(loader, store) { it() }.use { bridge ->
                    val prefs = bridge.getSharedPreferences("source_1")
                    assertSame(prefs, bridge.getSharedPreferences("source_1"))
                    val edit = call(prefs, "edit")!!
                    assertSame(edit, call(edit, "putString", "s", "字"))
                    call(edit, "putBoolean", "b", true)
                    call(edit, "putInt", "i", Int.MIN_VALUE)
                    call(edit, "putLong", "l", Long.MAX_VALUE)
                    call(edit, "putFloat", "f", -0f)
                    val set = mutableSetOf("图", "书")
                    call(edit, "putStringSet", "set", set)
                    set.clear()
                    assertEquals(true, call(edit, "commit"))
                    assertEquals("字", call(prefs, "getString", "s", null))
                    assertEquals(true, call(prefs, "getBoolean", "b", false))
                    assertEquals(Int.MIN_VALUE, call(prefs, "getInt", "i", 0))
                    assertEquals(Long.MAX_VALUE, call(prefs, "getLong", "l", 0L))
                    assertEquals((-0f).toRawBits(), (call(prefs, "getFloat", "f", 1f) as Float).toRawBits())
                    assertEquals(setOf("图", "书"), call(prefs, "getStringSet", "set", null))
                    (call(prefs, "getStringSet", "set", null) as MutableSet<*>).clear()
                    assertEquals(setOf("图", "书"), call(prefs, "getStringSet", "set", null))
                    assertEquals(null, call(prefs, "getString", "missing", null))
                    assertEquals(7, call(prefs, "getInt", "missing", 7))
                    assertThrows(ClassCastException::class.java) { call(prefs, "getLong", "i", 0L) }
                    assertEquals(false, call(prefs, "contains", "missing"))
                    val all = call(prefs, "getAll") as MutableMap<*, *>
                    all.clear()
                    assertEquals(true, call(prefs, "contains", "s"))
                    assertEquals(true, call(prefs, "equals", prefs))
                    assertEquals(false, call(prefs, "equals", bridge.getSharedPreferences("source_2")))
                }
            }
        }
    }

    @Test
    fun `editor clear order null removal final key write and reuse follow native editing rules`() {
        fixture().compatibilityLoader().use { loader ->
            FileSourcePreferenceStore(root.resolve("prefs")).use { store ->
                MihonPreferenceBridge(loader, store) { it() }.use { bridge ->
                    val prefs = bridge.getSharedPreferences("source_1")
                    val edit = call(prefs, "edit")!!
                    call(edit, "putInt", "old", 1)
                    call(edit, "apply")
                    call(edit, "putString", "new", "first")
                    call(edit, "clear")
                    call(edit, "putString", "new", "last")
                    call(edit, "putStringSet", "removed", null)
                    assertEquals(true, call(edit, "commit"))
                    assertEquals(mapOf("new" to "last"), call(prefs, "getAll"))
                    call(edit, "putBoolean", "another", false)
                    call(edit, "apply")
                    assertEquals(mapOf("new" to "last", "another" to false), call(prefs, "getAll"))
                    call(edit, "putString", "new", null)
                    call(edit, "remove", "another")
                    call(edit, "commit")
                    assertEquals(emptyMap<String, Any>(), call(prefs, "getAll"))
                }
            }
        }
    }

    @Test
    fun `listeners use platform dispatch can edit again and unregister suppresses queued delivery`() {
        fixture().compatibilityLoader().use { loader ->
            FileSourcePreferenceStore(root.resolve("prefs")).use { store ->
                val queued = mutableListOf<() -> Unit>()
                val bridge = MihonPreferenceBridge(loader, store) { queued += it }
                val prefs = bridge.getSharedPreferences("source_1")
                val keys = mutableListOf<String?>()
                val listenerClass = loader.loadClass("android.content.SharedPreferences\$OnSharedPreferenceChangeListener")
                val listener = Proxy.newProxyInstance(loader, arrayOf(listenerClass)) { self, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(self)
                        "equals" -> self === args?.get(0)
                        "onSharedPreferenceChanged" -> {
                            assertSame(prefs, args!![0])
                            keys += args[1] as String?
                            if (args[1] == "first") {
                                val edit = call(prefs, "edit")!!
                                call(edit, "putInt", "second", 2)
                                call(edit, "apply")
                            }
                            null
                        }
                        else -> null
                    }
                }
                call(prefs, "registerOnSharedPreferenceChangeListener", listener)
                store.open("source_1").edit(SourcePreferenceEdit(changes = mapOf("first" to SourcePreferenceValue.Integer(1))))
                assertEquals(emptyList<String>(), keys)
                queued.removeAt(0)()
                queued.removeAt(0)()
                assertEquals(listOf("first", "second"), keys)
                store.open("source_1").edit(SourcePreferenceEdit(clear = true))
                queued.removeAt(0)()
                assertEquals(listOf("first", "second", null), keys)
                store.open("source_1").edit(SourcePreferenceEdit(changes = mapOf("third" to SourcePreferenceValue.Integer(3))))
                call(prefs, "unregisterOnSharedPreferenceChangeListener", listener)
                queued.removeAt(0)()
                assertEquals(listOf("first", "second", null), keys)
                bridge.close()
                assertThrows(IllegalStateException::class.java) { call(prefs, "edit") }
            }
        }
    }

    @Test
    fun `session binds saved preferences before extension constructor and keeps store through platform shutdown`() {
        val fixture = fixture()
        val jar = fixture.sourceJar(body = """
            public Single() {
                if (!fixture.PreferenceEnvironment.preferences.getString("domain", "default").equals("saved.invalid"))
                    throw new IllegalStateException("preferences were not bound before construction");
            }
        """.trimIndent())
        FileSourcePreferenceStore(root.resolve("prefs")).use { it.open("source_9007199254740993").edit(
            SourcePreferenceEdit(changes = mapOf("domain" to SourcePreferenceValue.Text("saved.invalid")))) }
        val loader = fixture.compatibilityLoader()
        var bridge: MihonPreferenceBridge? = null
        var boundPreferences: SourcePreferenceStore? = null
        var shutdown = false
        val platform = object : SourceHostPreferencePlatform {
            override fun initialize(): ClassLoader = error("unconfigured initialization must not be used")
            override fun initialize(preferences: SourcePreferenceStore): ClassLoader {
                boundPreferences = preferences
                val boundBridge = MihonPreferenceBridge(loader, preferences) { it() }
                bridge = boundBridge
                loader.loadClass("fixture.PreferenceEnvironment").getField("preferences")
                    .set(null, boundBridge.getSharedPreferences("source_9007199254740993"))
                return loader
            }
            override fun close() {
                assertTrue(boundPreferences!!.open("source_9007199254740993").edit(SourcePreferenceEdit(changes =
                    mapOf("shutdown" to SourcePreferenceValue.Toggle(true)))))
                bridge!!.close()
                loader.close()
                shutdown = true
            }
        }
        val config = SourceHostConfig("fixture", listOf(SourceHostJar(jar.toString(), fixture.identity(jar))),
            preferenceDirectory = "prefs")
        val output = StringWriter()
        runBlocking { SourceHostJsonSession.run(config, root, StringReader(
            """{"version":1,"requestId":"prefs","call":{"operation":"sources"}}"""), output, platform) }
        assertTrue(shutdown)
        val response = SourceProtocolJson.decodeFromString<SourceResponse>(output.toString().trim())
        assertNull(response.error)
        assertEquals(1, (response.result as SourceResult.Sources).sources.size)
        FileSourcePreferenceStore(root.resolve("prefs")).use { assertEquals(SourcePreferenceValue.Toggle(true),
            it.open("source_9007199254740993").snapshot()["shutdown"]) }
    }

    private fun call(receiver: Any, name: String, vararg arguments: Any?): Any? {
        val method = receiver.javaClass.methods.first { it.name == name && it.parameterCount == arguments.size }
        return try { method.invoke(receiver, *arguments) } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}
