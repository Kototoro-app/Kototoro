package org.skepsun.kototoro.core.lnreader

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.security.MessageDigest
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.github.GitHubMirrorCatalogRepository
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings

/**
 * Opt-in live diagnostic: pass -e lnreaderLiveSources novelbuddy,lnori,readfrom to am instrument.
 * Downloads current public plugins and exercises the production repository without installing
 * sources or changing the library. Normal instrumentation runs skip this network-dependent test.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class LNReaderLiveSourceSmokeTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    @ContentHttpClient
    lateinit var httpClient: OkHttpClient

    @Inject
    lateinit var sourceManager: JsonSourceManager

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var mirrors: GitHubMirrorCatalogRepository

    @Test
    fun currentPluginsProvideListDetailsAndChapterText() = runBlocking {
        val ids = InstrumentationRegistry.getArguments().getString("lnreaderLiveSources")
            .orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
        assumeTrue("Live sources must be explicitly requested", ids.isNotEmpty())
        hiltRule.inject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundleDirectory = InstrumentationRegistry.getArguments().getString("lnreaderLiveBundleDirectory")
        val catalog = if (bundleDirectory == null) {
            LNReaderRepository(httpClient, sourceManager, settings, mirrors)
                .fetchPluginIndex(LNReaderRepository.OFFICIAL_REPO_URL).getOrThrow()
        } else {
            // Replay already downloaded, hash-logged official bundles while keeping site requests live.
            emptyList()
        }
        Log.i(TAG, "INDEX count=${catalog.size} bundleReplay=${bundleDirectory != null}")
        val failures = mutableListOf<String>()
        for (id in ids) {
            var stage = "bundle"
            try {
                val plugin = if (bundleDirectory == null) catalog.single { it.id == id } else null
                val code = if (bundleDirectory != null) {
                    File(bundleDirectory, "$id.js").readText()
                } else {
                    httpClient.newCall(Request.Builder().url(checkNotNull(plugin).url).build()).execute().use {
                        check(it.isSuccessful) { "Bundle HTTP ${it.code}" }
                        checkNotNull(it.body).string()
                    }
                }
                val metadata = checkNotNull(LNReaderPluginMetadata.extractFromCode(code, id))
                val sha = MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                Log.i(TAG, "BUNDLE id=$id version=${plugin?.version ?: metadata.version} sha256=$sha")
                val source = JsonContentSource(
                    JsonSourceEntity(id, plugin?.name ?: metadata.name, JsonSourceType.LNREADER, code,
                        createdAt = 0, updatedAt = 0),
                )
                val repository = LNReaderContentRepository(source, context, httpClient)
                stage = "list"
                val novels = repository.getList(0, null, null)
                check(novels.isNotEmpty()) { "Empty first page" }
                Log.i(TAG, "LIST id=$id count=${novels.size}")
                stage = "details"
                val details = repository.getDetails(novels.first())
                val chapters = details.chapters.orEmpty()
                check(chapters.isNotEmpty()) { "Empty chapters for ${novels.first().url}" }
                Log.i(TAG, "DETAILS id=$id chapters=${chapters.size} path=${details.url}")
                stage = "chapter"
                var foundText = false
                for (chapter in chapters.take(5)) {
                    val content = repository.getChapterContent(chapter, null)
                    val document = Jsoup.parse(content?.html.orEmpty())
                    val textLength = document.text().length
                    val images = document.select("img[src]").size
                    check(textLength > 0 || images > 0) { "Empty chapter: ${chapter.title}" }
                    Log.i(TAG, "CHAPTER id=$id title=${chapter.title} textCharacters=$textLength images=$images")
                    if (textLength > 100) {
                        foundText = true
                        break
                    }
                }
                check(foundText) { "No substantial novel text in the first five entries" }
                Log.i(TAG, "SOURCE id=$id PASS")
            } catch (error: Exception) {
                val failure = "$id stage=$stage ${error.javaClass.simpleName}: ${error.message}"
                failures += failure
                Log.e(TAG, failure, error)
            }
        }
        assertTrue("Live source failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private companion object {
        const val TAG = "LNReaderLiveSmoke"
    }
}
