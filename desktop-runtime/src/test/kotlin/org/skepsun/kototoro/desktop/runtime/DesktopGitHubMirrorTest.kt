package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.nio.file.Path

/**
 * Android's `github_mirror` preference reaches Windows through this value: the id the storage-and-network page writes
 * under Android's own key, and the rewriter every GitHub download asks on each request.
 */
class DesktopGitHubMirrorTest {
    @TempDir
    lateinit var directory: Path

    private val releaseAsset = "https://github.com/skepsun/Kototoro/releases/download/N20261021/app-windows-x64.zip"
    private val repositoryIndex = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"

    @Test
    fun `a fresh store downloads directly, like Android's default`() = withStore { preferences ->
        val mirror = DesktopGitHubMirror(preferences)
        assertEquals(GitHubMirrorCatalog.NATIVE, mirror.settings.value.entry)
        assertTrue(mirror.settings.value.isNative)
        // The identity, not a removal: every caller keeps the URL it built.
        assertEquals(releaseAsset, mirror.rewrite(releaseAsset))
        assertEquals(repositoryIndex, mirror.rewrite(repositoryIndex))
    }

    @Test
    fun `the chosen mirror is stored by Android's key and survives a restart`() = withStore { preferences ->
        val mirror = DesktopGitHubMirror(preferences)
        assertTrue(mirror.save(DesktopGitHubMirrorSettings("gh_proxy_com")))
        assertEquals(setOf(DesktopGitHubMirrorSettings.KEY), mirror.settings.value.toPreferences().keys)
        assertEquals("github_mirror", DesktopGitHubMirrorSettings.KEY)
        assertEquals(DesktopGitHubMirrorSettings("gh_proxy_com"), mirror.settings.value)

        // A new instance over the same store is a restart; the page's own reload reads the same keys.
        assertEquals(DesktopGitHubMirrorSettings("gh_proxy_com"), DesktopGitHubMirror(preferences).settings.value)
        mirror.reload()
        assertEquals("gh-proxy.com", mirror.settings.value.entry.name)
    }

    @Test
    fun `a chosen mirror rewrites release assets and raw repository files`() = withStore { preferences ->
        val mirror = DesktopGitHubMirror(preferences)
        mirror.save(DesktopGitHubMirrorSettings("gh_proxy_com"))
        assertEquals(
            "https://gh-proxy.com/https://github.com/skepsun/Kototoro/releases/download/N20261021/app-windows-x64.zip",
            mirror.rewrite(releaseAsset),
        )
        assertEquals(
            "https://gh-proxy.com/https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            mirror.rewrite(repositoryIndex),
        )
        // The releases API is no mirror's business: the version check keeps naming the files itself.
        assertEquals(
            "https://api.github.com/repos/skepsun/Kototoro/releases?per_page=10",
            mirror.rewrite("https://api.github.com/repos/skepsun/Kototoro/releases?per_page=10"),
        )
    }

    @Test
    fun `switching back to the direct connection takes effect without a restart`() = withStore { preferences ->
        val mirror = DesktopGitHubMirror(preferences)
        mirror.save(DesktopGitHubMirrorSettings("gh_proxy_com"))
        assertTrue(mirror.rewrite(releaseAsset).startsWith("https://gh-proxy.com/"))
        mirror.save(DesktopGitHubMirrorSettings())
        assertEquals(releaseAsset, mirror.rewrite(releaseAsset))
    }

    @Test
    fun `an id this build does not know keeps its value but downloads directly`() = withStore { preferences ->
        val mirror = DesktopGitHubMirror(preferences)
        // Written by a newer build or by Android after a manifest sync: the page must not silently rewrite the store.
        mirror.save(DesktopGitHubMirrorSettings("some_remote_mirror"))
        assertEquals("some_remote_mirror", mirror.settings.value.id)
        assertEquals(GitHubMirrorCatalog.NATIVE, mirror.settings.value.entry)
        assertEquals(releaseAsset, mirror.rewrite(releaseAsset))
        // Android's aliases for removed hosts still resolve, so an older store is not left on a dead host.
        mirror.save(DesktopGitHubMirrorSettings("kkgithub"))
        assertEquals("gh_proxy_com", mirror.settings.value.entry.id)
        assertFalse(mirror.settings.value.isNative)
    }

    @Test
    fun `the dialog lists the catalog with the direct connection first`() = withStore { preferences ->
        val options = DesktopGitHubMirror(preferences).options
        assertSame(GitHubMirrorCatalog.NATIVE, options.first())
        assertEquals(GitHubMirrorCatalog.builtin.size, options.size)
        assertEquals(options.size, options.map { it.id }.distinct().size)
        assertTrue(options.any { it.id == "gh_proxy_com" })
    }

    private fun withStore(block: (SourcePreferences) -> Unit) {
        FileSourcePreferenceStore(directory.resolve("preferences")).use { store ->
            block(store.open("desktop_network"))
        }
    }
}
