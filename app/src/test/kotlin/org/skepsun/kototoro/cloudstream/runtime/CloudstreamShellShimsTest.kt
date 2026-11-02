package org.skepsun.kototoro.cloudstream.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AppContextUtils
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Cloudstream app-shell members plugins link against, by the exact JVM signatures their DEX references:
 * StreamPlay and StremioX (plugin manager, `afterPluginsLoadedEvent`), CineStream (`reloadHomeEvent`, Simkl), 3isk
 * (OpenSubtitles headers), AnimeWorldIndia and four more (`Globals.isLayout`), ViStreamProvider
 * (`DataStoreHelper.currentAccount`), SectionOrganizer (`setDefaultFocus`) and FshareProvider (`RepositoryData`).
 * A drifted signature makes the compatibility check quarantine those plugins again.
 */
class CloudstreamShellShimsTest {

    @Test
    fun `plugin-facing members keep the official signatures`() {
        assertReturns(MainActivity.Companion::class.java, "getAfterPluginsLoadedEvent", Event::class.java)
        assertReturns(MainActivity.Companion::class.java, "getReloadHomeEvent", Event::class.java)
        assertReturns(PluginManager::class.java, "getPluginsOnline", Array<PluginData>::class.java)
        assertEquals(
            Void.TYPE,
            PluginManager::class.java.getMethod("unloadPlugin", String::class.java).returnType,
        )
        assertReturns(PluginData::class.java, "getInternalName", String::class.java)
        assertReturns(PluginData::class.java, "getFilePath", String::class.java)
        assertReturns(AccountManager.Companion::class.java, "getSimklApi", SimklApi::class.java)
        assertReturns(OpenSubtitlesApi.Companion::class.java, "getHeaders", Map::class.java)
        assertEquals(Void.TYPE, Event::class.java.getMethod("invoke", Any::class.java).returnType)
        assertEquals(
            java.lang.Boolean.TYPE,
            Globals::class.java.getMethod("isLayout", Integer.TYPE).returnType,
        )
        assertReturns(DataStoreHelper::class.java, "getCurrentAccount", String::class.java)
        assertReturns(RepositoryData::class.java, "getUrl", String::class.java)
        // The default-argument bridge of `AlertDialog.setDefaultFocus(buttonFocus = …)` is what callers invoke.
        AppContextUtils::class.java.getMethod(
            "setDefaultFocus\$default",
            AppContextUtils::class.java,
            androidx.appcompat.app.AlertDialog::class.java,
            Integer.TYPE,
            Integer.TYPE,
            Any::class.java,
        )
    }

    @Test
    fun `layout flags follow the host's answer`() {
        val previous = Globals.layoutProvider
        try {
            assertTrue(Globals.isLayout(Globals.PHONE))
            Globals.layoutProvider = { Globals.TV }
            assertTrue(Globals.isLayout(Globals.TV or Globals.EMULATOR))
            assertEquals(false, Globals.isLayout(Globals.PHONE))
        } finally {
            Globals.layoutProvider = previous
        }
    }

    @Test
    fun `repositories read back from the official JSON shape`() {
        val stored = "[{\"iconUrl\":null,\"name\":\"Repo\",\"url\":\"https://example.test/repo.json\"}]"

        val repositories = jacksonObjectMapper().readValue<Array<RepositoryData>>(stored)

        assertEquals(RepositoryData("Repo", "https://example.test/repo.json"), repositories.single())
    }

    @Test
    fun `events reach their observers and firing one without observers is harmless`() {
        val received = mutableListOf<Boolean>()
        val event = Event<Boolean>()
        val observer: (Boolean) -> Unit = { received += it }

        event += observer
        event(true)
        event -= observer
        event(false)
        MainActivity.afterPluginsLoadedEvent(true)

        assertEquals(listOf(true), received)
    }

    @Test
    fun `shell services answer as a host without accounts or plugin management`() {
        assertEquals(0, PluginManager.getPluginsOnline().size)
        PluginManager.unloadPlugin("/data/plugins/Other.cs3")
        assertTrue(AccountManager.simklApi is SyncAPI)
        assertEquals(mapOf("user-agent" to "Cloudstream3 v0.2"), OpenSubtitlesApi.headers)
        assertEquals("0", DataStoreHelper.currentAccount)
    }

    private fun assertReturns(owner: Class<*>, name: String, type: Class<*>) {
        assertEquals(type, owner.getMethod(name).returnType, "${owner.name}.$name")
    }
}
