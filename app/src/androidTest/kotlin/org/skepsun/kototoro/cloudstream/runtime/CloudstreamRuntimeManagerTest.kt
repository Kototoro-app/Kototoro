package org.skepsun.kototoro.cloudstream.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.SortOrder
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CloudstreamRuntimeManagerTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var runtimeManager: CloudstreamRuntimeManager

	@Inject
	lateinit var memoryContentCache: MemoryContentCache

	@Inject
	lateinit var webViewExecutor: WebViewExecutor

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var cookieJar: MutableCookieJar

	@Before
	fun setUp() {
		hiltRule.inject()
		preparePluginFile()
	}

	@Test
	fun initialize_loadsSideloadedCs3Plugin() = runBlocking {
		runtimeManager.initialize()

		val sources = runtimeManager.sources.value
		assertTrue(sources.isNotEmpty())

		val idlixSource = sources.firstOrNull { it.pluginPackageName == "IdlixProvider" }
		assertTrue("IdlixProvider should be discovered", idlixSource != null)
		assertEquals("Idlix", idlixSource?.displayName)
	}

	@Test
	fun idlixMainPage_returnsItemsWithoutMissingRuntimeClasses() = runBlocking {
		runtimeManager.initialize()

		val idlixSource = runtimeManager.sources.value.firstOrNull { it.displayName == "Idlix" }
		assertNotNull("Idlix source should be discovered", idlixSource)

		val repository = CloudstreamContentRepository(
			source = requireNotNull(idlixSource),
			cache = memoryContentCache,
			webViewExecutor = webViewExecutor,
			cookieJar = cookieJar,
			settings = settings,
		)

		val result = repository.getList(
			offset = 0,
			order = SortOrder.RELEVANCE,
			filter = ContentListFilter(query = null),
		)

		assertFalse("Idlix main page should not be empty after runtime wiring", result.isEmpty())
	}

	private fun preparePluginFile() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val pluginsDir = File(File(context.filesDir, "cloudstream"), "plugins").apply { mkdirs() }
		pluginsDir.listFiles()?.forEach { file ->
			if (file.isFile) {
				file.delete()
			}
		}

		val targetFile = File(pluginsDir, "IdlixProvider.cs3")
		InstrumentationRegistry.getInstrumentation().context.assets
			.open("IdlixProvider.cs3")
			.use { input ->
				targetFile.outputStream().use { output ->
					input.copyTo(output)
				}
			}

		context.getSharedPreferences("cloudstream_plugin_versions", android.content.Context.MODE_PRIVATE)
			.edit()
			.clear()
			.putLong("IdlixProvider", 10L)
			.putString("IdlixProvider:name", "IdlixProvider")
			.putString("IdlixProvider:lang", "id")
			.putString("IdlixProvider:archive", "IdlixProvider.cs3")
			.apply()
	}
}
