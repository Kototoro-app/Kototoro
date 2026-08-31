package org.skepsun.kototoro.extensions.repo

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.GitHubMirrorCatalog
import org.skepsun.kototoro.core.prefs.GitHubMirrorEntry
import org.skepsun.kototoro.core.prefs.GitHubMirrorStrategy

class GitHubMirrorUrlTest {

    private val rawUrl = "https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/index.min.json"
    private val githubUrl = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
    private val releaseUrl = "https://github.com/keiyoushi/extensions/releases/download/0.1/example.apk"
    private val jarUrl = "https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/apk/plugin.jar"

    // Rewriting rules are exercised with inline entries: the built-in host list is operational
    // data that changes whenever a public mirror dies, and these rules must not change with it.
    private fun prefix(host: String) = GitHubMirrorEntry(
        id = "p_$host",
        name = host,
        strategy = GitHubMirrorStrategy.PREFIX,
        host = host,
    )

    private val jsdelivr = GitHubMirrorEntry(
        id = "jsdelivr_test",
        name = "jsDelivr (test)",
        strategy = GitHubMirrorStrategy.JSDELIVR,
        host = "cdn.jsdmirror.com",
    )

    @Test
    fun `native keeps every url untouched`() {
        applyGitHubMirror(rawUrl, AppSettings.GitHubMirror.NATIVE) shouldBe rawUrl
        applyGitHubMirror(githubUrl, AppSettings.GitHubMirror.NATIVE) shouldBe githubUrl
    }

    @Test
    fun `host replace rewrites raw and web hosts`() {
        val entry = GitHubMirrorEntry(
            id = "hr_test",
            name = "Host Replace (test)",
            strategy = GitHubMirrorStrategy.HOST_REPLACE,
            host = "gh.example.com",
            rawHost = "raw.example.com",
        )
        applyGitHubMirror(rawUrl, entry) shouldBe
            "https://raw.example.com/skepsun/kototoro-parsers/repo/index.min.json"
        applyGitHubMirror(githubUrl, entry) shouldBe
            "https://gh.example.com/keiyoushi/extensions/raw/repo/index.pb"
    }

    @Test
    fun `prefix mirrors prepend to raw and web urls`() {
        listOf("gh-proxy.com", "ghfast.top", "github.moeyy.xyz", "wget.la").forEach { host ->
            val entry = prefix(host)
            applyGitHubMirror(rawUrl, entry) shouldBe "https://$host/$rawUrl"
            applyGitHubMirror(githubUrl, entry) shouldBe "https://$host/$githubUrl"
        }
    }

    @Test
    fun `jsdelivr rewrites raw urls with a plain branch`() {
        applyGitHubMirror(rawUrl, jsdelivr) shouldBe
            "https://cdn.jsdmirror.com/gh/skepsun/kototoro-parsers@repo/index.min.json"
    }

    @Test
    fun `jsdelivr collapses refs heads prefixes`() {
        val refsUrl = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"
        applyGitHubMirror(refsUrl, jsdelivr) shouldBe
            "https://cdn.jsdmirror.com/gh/phisher98/cloudstream-extensions-phisher@builds/repo.json"
    }

    @Test
    fun `jsdelivr rewrites github raw web urls`() {
        applyGitHubMirror(githubUrl, jsdelivr) shouldBe
            "https://cdn.jsdmirror.com/gh/keiyoushi/extensions@repo/index.pb"
    }

    @Test
    fun `jsdelivr leaves release asset urls untouched`() {
        applyGitHubMirror(releaseUrl, jsdelivr) shouldBe releaseUrl
    }

    @Test
    fun `non github urls always pass through`() {
        val cdn = "https://cdn.jsdmirror.com/gh/XIU2/Yuedu/shuyuan"
        listOf(prefix("gh-proxy.com"), jsdelivr, GitHubMirrorCatalog.NATIVE).forEach { mirror ->
            applyGitHubMirror(cdn, mirror) shouldBe cdn
        }
    }

    @Test
    fun `prefix mirror with null host leaves url unchanged`() {
        val entry = GitHubMirrorEntry(
            id = "broken",
            name = "Broken",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = null,
        )
        applyGitHubMirror(rawUrl, entry) shouldBe rawUrl
    }

    @Test
    fun `builtin enum delegates to the same catalog entry`() {
        val entry = GitHubMirrorCatalog.builtinById("gh_proxy_com")!!
        applyGitHubMirror(rawUrl, entry) shouldBe
            applyGitHubMirror(rawUrl, AppSettings.GitHubMirror.GH_PROXY_COM)
    }

    @Test
    fun `release assets fall back to release-capable mirrors when the selected one cannot serve them`() {
        val candidates = gitHubArchiveCandidates(releaseUrl, jsdelivr, GitHubMirrorCatalog.builtin)
        // jsDelivr leaves release URLs untouched, so the direct URL must not be tried first.
        candidates.first() shouldNotBe releaseUrl
        candidates.last() shouldBe releaseUrl
        // Every queued rewrite is a real mirror prefix, never a pass-through.
        candidates.take(candidates.size - 1).forEach { candidate ->
            candidate.startsWith("https://github.com/") shouldBe false
        }
        candidates.any { it.startsWith("https://gh-proxy.com/") } shouldBe true
        candidates.distinct().size shouldBe candidates.size
    }

    @Test
    fun `release assets keep the selected rewrite first when it can proxy releases`() {
        val selected = prefix("gh-proxy.com")
        val candidates = gitHubArchiveCandidates(releaseUrl, selected, GitHubMirrorCatalog.builtin)
        candidates.first() shouldBe "https://gh-proxy.com/$releaseUrl"
        candidates.last() shouldBe releaseUrl
    }

    @Test
    fun `non-archive urls use the single selected rewrite`() {
        gitHubArchiveCandidates(rawUrl, jsdelivr, GitHubMirrorCatalog.builtin) shouldBe
            listOf(applyGitHubMirror(rawUrl, jsdelivr))
    }

    @Test
    fun `branch hosted jars skip jsdelivr because it answers 403 for that file type`() {
        // Verified against cdn.jsdelivr.net: index.min.json -> 200 but apk/plugin.jar -> 403,
        // with both a branch ref and a pinned commit SHA, on an unrelated popular repo too.
        // So it is a file-type policy, not ref syntax, CDN caching or the 20 MB size limit.
        val candidates = gitHubArchiveCandidates(jarUrl, jsdelivr, GitHubMirrorCatalog.builtin)
        candidates.any { it.contains("jsdelivr") || it.contains("jsdmirror") } shouldBe false
        candidates.any { it.startsWith("https://gh-proxy.com/") } shouldBe true
        candidates.last() shouldBe jarUrl
    }

    @Test
    fun `prefix mirrors serve branch hosted jars directly`() {
        val selected = prefix("gh-proxy.com")
        val candidates = gitHubArchiveCandidates(jarUrl, selected, GitHubMirrorCatalog.builtin)
        candidates.first() shouldBe "https://gh-proxy.com/$jarUrl"
        candidates.last() shouldBe jarUrl
    }

    @Test
    fun `js plugin bundles still go through the selected jsdelivr mirror`() {
        val jsUrl = "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.js/src/plugins/arabic/ArNovel[madara].js"
        gitHubArchiveCandidates(jsUrl, jsdelivr, GitHubMirrorCatalog.builtin) shouldBe
            listOf(applyGitHubMirror(jsUrl, jsdelivr))
    }

    @Test
    fun `in-tree apks try the selected mirror first but keep fallbacks`() {
        // Only .jar is verified as blocked by jsDelivr, so other archives are not blacklisted.
        val apkUrl = "https://raw.githubusercontent.com/some/repo/branch/apk/ext.apk"
        val candidates = gitHubArchiveCandidates(apkUrl, jsdelivr, GitHubMirrorCatalog.builtin)
        candidates.first() shouldBe applyGitHubMirror(apkUrl, jsdelivr)
        candidates.last() shouldBe apkUrl
        (candidates.size > 1) shouldBe true
    }
}
