package org.skepsun.kototoro.desktop.compat

import android.content.ContextWrapper
import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.PersistentCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import org.skepsun.kototoro.source.host.MihonPreferenceBridge
import java.nio.file.Path

class DesktopCookieRestoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `restoring a persisted domain snapshot excludes expired session malformed and foreign domain cookies`() = withStore { store ->
        val origin = "https://a.scope.invalid/other/page".toHttpUrl()
        val cookie = Cookie.Builder().name("kept").value("opaque_token").hostOnlyDomain(origin.host)
            .path("/other").httpOnly().secure().expiresAt(System.currentTimeMillis() + 60000).build()
        restoreDesktopCookies(store, mapOf(
            origin.host to setOf(cookie.toString(), "expired=gone; Max-Age=0", "session=temporary; Path=/",
                "foreign=wrong; Domain=elsewhere.invalid; Max-Age=600", "malformed"),
            "invalid domain" to setOf(cookie.toString()),
            "metadata" to true,
        ))
        val restored = store.getStoredCookies().single()
        assertEquals("kept", restored.name)
        assertEquals(cookie.value, restored.value)
        assertEquals(origin.host, restored.domain)
        assertEquals("/other", restored.path)
        assertTrue(restored.secure && restored.httpOnly && restored.hostOnly && restored.persistent)
        assertEquals(cookie.expiresAt / 1000, restored.expiresAt / 1000)
    }

    @Test
    fun `restoring missing records preserves cookies already owned by the SDK and is idempotent`() = withStore { store ->
        val origin = "https://scope.invalid/".toHttpUrl()
        val current = Cookie.Builder().name("token").value("newer").domain(origin.host)
            .path("/").secure().expiresAt(System.currentTimeMillis() + 60000).build()
        store.addAll(origin, listOf(current))
        val old = current.newBuilder().value("stale").build()
        val snapshot = mapOf(origin.host to setOf(old.toString()))
        restoreDesktopCookies(store, snapshot)
        restoreDesktopCookies(store, snapshot)
        assertEquals(listOf(current), store.getStoredCookies())
    }

    private fun withStore(test: (PersistentCookieStore) -> Unit) {
        FileSourcePreferenceStore(directory.resolve("preferences")).use { preferences ->
            MihonPreferenceBridge(javaClass.classLoader, preferences, dispatchListener = { it() }).use { bridge ->
                val context = object : ContextWrapper(null) {
                    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                        bridge.getSharedPreferences(name) as SharedPreferences
                }
                test(PersistentCookieStore(context))
            }
        }
    }
}
