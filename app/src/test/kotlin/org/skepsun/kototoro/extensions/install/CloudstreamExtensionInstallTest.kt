package org.skepsun.kototoro.extensions.install

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager
import org.skepsun.kototoro.core.github.GitHubMirrorCatalogRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension

class CloudstreamExtensionInstallTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `runtime rejection cannot report success or update installed version metadata`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(pluginArchive())))
            val runtime = mockk<CloudstreamRuntimeManager> {
                every { installPlugin(any(), any(), any()) } throws IllegalStateException("load failed")
            }
            val preferences = mockk<SharedPreferences>(relaxed = true)
            val error = runCatching { service(runtime, preferences).install(extension(server.url("/plugin.cs3").toString())) }
                .exceptionOrNull()
            assertTrue(error is IllegalStateException)
            verify(exactly = 0) { preferences.edit() }
        }
    }

    @Test
    fun `version metadata is written only after activation succeeds`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(pluginArchive())))
            val preferences = mockk<SharedPreferences>(relaxed = true)
            val runtime = mockk<CloudstreamRuntimeManager> {
                every { installPlugin(any(), "plugin.cs3", "test.plugin") } answers {
                    assertTrue(firstArg<File>().isFile)
                    verify(exactly = 0) { preferences.edit() }
                }
            }
            assertEquals(
                ExtensionInstallResult.Completed,
                service(runtime, preferences).install(extension(server.url("/plugin.cs3").toString())),
            )
            verify(exactly = 1) { preferences.edit() }
        }
    }

    private fun service(runtime: CloudstreamRuntimeManager, preferences: SharedPreferences): ExtensionInstallService {
        val context = mockk<Context> {
            every { cacheDir } returns directory
            every { classLoader } returns CloudstreamExtensionInstallTest::class.java.classLoader!!
            every { getSharedPreferences("cloudstream_plugin_versions", Context.MODE_PRIVATE) } returns preferences
        }
        val settings = mockk<AppSettings> { every { gitHubMirrorId } returns GitHubMirrorCatalog.NATIVE.id }
        val mirrors = mockk<GitHubMirrorCatalogRepository> {
            every { entry(any()) } returns GitHubMirrorCatalog.NATIVE
            every { entries } returns MutableStateFlow(listOf(GitHubMirrorCatalog.NATIVE))
        }
        return ExtensionInstallService(context, OkHttpClient(), settings, mirrors, runtime, mockk(), mockk(relaxed = true))
    }

    private fun extension(url: String) = RepoAvailableExtension(
        type = ExternalExtensionType.CLOUDSTREAM, name = "Test", pkgName = "test.plugin",
        versionName = "2", versionCode = 2, libVersion = 1.0, lang = "en", isNsfw = false,
        sourceNames = emptyList(), archiveName = "plugin.cs3", archiveUrl = url,
        iconUrl = "", repoUrl = "https://example.test", repoName = "Test", signatureHash = "", isCompatible = true,
    )

    private fun pluginArchive(): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(112).apply { "dex".toByteArray().copyInto(this) })
            zip.closeEntry()
        }
    }.toByteArray()
}
