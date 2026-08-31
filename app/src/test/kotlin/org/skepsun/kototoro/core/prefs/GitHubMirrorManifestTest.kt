package org.skepsun.kototoro.core.prefs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class GitHubMirrorManifestTest {

    private val sampleJson = """
        {
          "version": "2.0.0",
          "updatedAt": "2026-09-01",
          "mirrors": [
            {
              "id": "kkgithub",
              "name": "KKGithub (2.0)",
              "strategy": "HOST_REPLACE",
              "host": "kkgithub.com",
              "rawHost": "raw.kkgithub.com"
            },
            {
              "id": "brand_new",
              "name": "Brand New Proxy",
              "strategy": "PREFIX",
              "host": "proxy.example.net",
              "unknownField": "ignored"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `manifest parses and ignores unknown keys`() {
        val manifest = GitHubMirrorCatalog.json.decodeFromString<GitHubMirrorManifest>(sampleJson)
        manifest.version shouldBe "2.0.0"
        manifest.updatedAt shouldBe "2026-09-01"
        manifest.mirrors.map { it.id } shouldBe listOf("kkgithub", "brand_new")
        manifest.mirrors[1].strategy shouldBe GitHubMirrorStrategy.PREFIX
    }

    @Test
    fun `normalize preserves remote order and collapses duplicate ids`() {
        val manifest = GitHubMirrorCatalog.json.decodeFromString<GitHubMirrorManifest>(sampleJson)
        val normalized = GitHubMirrorCatalog.normalizeMirrors(manifest.mirrors)
        val ids = normalized.map { it.id }

        // NATIVE is always first; remote entries keep their order right behind it.
        ids.first() shouldBe "native"
        ids.subList(1, 3) shouldBe listOf("kkgithub", "brand_new")
        // First-seen duplicate wins: kkgithub from the manifest keeps its 2.0 name.
        normalized.first { it.id == "kkgithub" }.name shouldBe "KKGithub (2.0)"
        // Built-ins missing from the manifest are re-appended at the end, in builtin order.
        // Derived from the catalog instead of hardcoded: the host list is operational data
        // that changes whenever a public mirror dies, and this rule must not.
        ids.subList(3, ids.size) shouldBe GitHubMirrorCatalog.builtin
            .map { it.id }
            .filterNot { it == "native" || it == "kkgithub" }
    }

    @Test
    fun `normalize falls back to builtin for empty input`() {
        GitHubMirrorCatalog.normalizeMirrors(emptyList()) shouldBe GitHubMirrorCatalog.builtin
        GitHubMirrorCatalog.normalizeMirrors(null) shouldBe GitHubMirrorCatalog.builtin
    }

    @Test
    fun `normalize drops blank ids and forces native`() {
        val normalized = GitHubMirrorCatalog.normalizeMirrors(
            listOf(
                GitHubMirrorEntry(
                    id = "",
                    name = "Bad",
                    strategy = GitHubMirrorStrategy.PREFIX,
                    host = "x.example",
                ),
                GitHubMirrorEntry(
                    id = "native",
                    name = "Native override",
                    strategy = GitHubMirrorStrategy.NATIVE,
                ),
            ),
        )
        normalized.first().id shouldBe "native"
        // The bogus empty entry is filtered out; builtins re-appended.
        normalized.any { it.id.isEmpty() } shouldBe false
    }

    @Test
    fun `unknown strategy fails parsing`() {
        val bad = """{"version":"1.0.0","mirrors":[{"id":"a","name":"A","strategy":"MAGIC"}]}"""
        shouldThrow<kotlinx.serialization.SerializationException> {
            GitHubMirrorCatalog.json.decodeFromString<GitHubMirrorManifest>(bad)
        }
    }

    @Test
    fun `every persisted preference value still resolves to a catalog entry`() {
        // The catalog has outgrown the legacy enum (hosts get added and retired), so the
        // invariant that matters is not list equality but that a stored preference can
        // never resolve to nothing — that would silently fall back to a direct connection.
        val unresolved = AppSettings.GitHubMirror.entries
            .filter { GitHubMirrorCatalog.builtinById(it.value) == null }
            .map { it.name }
        unresolved shouldBe emptyList()
    }

    @Test
    fun `the published manifest file parses and passes the save validation`() {
        // docs/github-mirrors.json is what the refresh endpoint serves, so a manifest the
        // repository would reject is a refresh that fails forever. Guard the real file.
        val file = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .map { java.io.File(it, "docs/github-mirrors.json") }
            .firstOrNull { it.isFile }
        file shouldNotBe null
        val manifest = GitHubMirrorCatalog.json.decodeFromString<GitHubMirrorManifest>(file!!.readText())
        manifest.version.isBlank() shouldBe false
        manifest.mirrors.isEmpty() shouldBe false
        (manifest.mirrors.size > 64) shouldBe false
        // Ids must be slug-like or saveManifest rejects the whole list.
        val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        manifest.mirrors.filterNot { it.id.matches(idPattern) }.map { it.id } shouldBe emptyList()
        // Every entry must carry the host its strategy needs, otherwise it rewrites nothing.
        manifest.mirrors.filter { it.strategy != GitHubMirrorStrategy.NATIVE && it.host.isNullOrBlank() }
            .map { it.id } shouldBe emptyList()
        // Merging it must keep native first and never lose an entry.
        val merged = GitHubMirrorCatalog.normalizeMirrors(manifest.mirrors)
        merged.first().id shouldBe "native"
        merged.size shouldBe manifest.mirrors.size + 1
    }

    @Test
    fun `sync candidate urls prefer non-GitHub cdns and target the docs file on the default branch`() {
        val urls = GitHubMirrorCatalog.syncCandidateUrls("Kototoro-app/Kototoro")
        urls.size shouldBe 5
        // Non-GitHub CDNs come first so the refresh never depends on GitHub itself.
        urls[0] shouldBe "https://cdn.jsdmirror.com/gh/Kototoro-app/Kototoro@devel/docs/github-mirrors.json"
        urls.any { it.startsWith("https://cdn.jsdmirror.com/") } shouldBe true
        urls.any { it.startsWith("https://cdn.jsdelivr.net/") } shouldBe true
        urls.any { it.startsWith("https://fastly.jsdelivr.net/") } shouldBe true
        urls.any { it.startsWith("https://gcore.jsdelivr.net/") } shouldBe true
        // Raw GitHub is only the fallback, still pinned to the same docs path.
        urls.last() shouldBe "https://raw.githubusercontent.com/Kototoro-app/Kototoro/devel/docs/github-mirrors.json"
    }

    @Test
    fun `sync candidate urls follow the configured branch instead of assuming main`() {
        // The repo default branch is "devel"; hardcoding "main" made every refresh 404.
        val urls = GitHubMirrorCatalog.syncCandidateUrls("Kototoro-app/Kototoro", "release/1.9")
        urls.all { "@main" !in it && "/main/" !in it } shouldBe true
        urls.first() shouldBe "https://cdn.jsdmirror.com/gh/Kototoro-app/Kototoro@release/1.9/docs/github-mirrors.json"
        urls.last() shouldBe "https://raw.githubusercontent.com/Kototoro-app/Kototoro/release/1.9/docs/github-mirrors.json"
    }

    @Test
    fun `blank branch falls back to the default ref`() {
        GitHubMirrorCatalog.syncCandidateUrls("Kototoro-app/Kototoro", " ") shouldBe
            GitHubMirrorCatalog.syncCandidateUrls("Kototoro-app/Kototoro")
    }
}
