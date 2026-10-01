package org.skepsun.kototoro.core.lnreader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in replay of the official Baca bundle; pass -e lnreaderMetadataBundle <device path>. */
@RunWith(AndroidJUnit4::class)
class LNReaderMetadataBundleTest {

    @Test
    fun catalogMetadataPreservesTheExecutablePlugin() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("lnreaderMetadataBundle")
        assumeTrue("An official bundle must be explicitly supplied", path != null)
        val code = File(requireNotNull(path)).readText()
        val metadata = LNReaderPluginMetadata(
            id = "bacalightnovel",
            name = "Baca Light Novel",
            site = "https://bacalightnovel.co/",
            version = "1.1.10",
            lang = "id",
        )
        val wrapped = metadata.withMetadataHeader(code)
        assertEquals("1.1.", LNReaderPluginMetadata.extractFromCode(code, metadata.id)?.version)
        assertEquals(metadata, LNReaderPluginMetadata.extractFromCode(wrapped, metadata.id))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = LNReaderEngine(context, LNReaderFetchBridge(OkHttpClient(), metadata.id))
        val qjs = engine.createPluginContext(wrapped, metadata.id)
        try {
            assertEquals("1.1.10", qjs.evaluate<String>("globalThis.__plugin_bacalightnovel.version"))
            assertEquals(metadata.id, qjs.evaluate<String>("globalThis.__plugin_bacalightnovel.id"))
            assertTrue(qjs.evaluate<Boolean>("typeof globalThis.__plugin_bacalightnovel.parseNovel === 'function'"))
        } finally {
            qjs.close()
        }
    }
}
