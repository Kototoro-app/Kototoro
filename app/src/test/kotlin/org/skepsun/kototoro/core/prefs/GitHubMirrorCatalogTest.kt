package org.skepsun.kototoro.core.prefs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Invariants of the built-in mirror list. The list itself is operational data
 * (public "GitHub 加速" frontends come and go), so these tests pin the rules the
 * list must obey rather than its exact contents.
 */
class GitHubMirrorCatalogTest {

    private val hostPattern = Regex("^[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}$")

    @Test
    fun `ids are unique and native comes first`() {
        GitHubMirrorCatalog.builtin.first() shouldBe GitHubMirrorCatalog.NATIVE
        val ids = GitHubMirrorCatalog.builtin.map { it.id }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 } shouldBe emptyMap()
        GitHubMirrorCatalog.builtin.count { it.strategy == GitHubMirrorStrategy.NATIVE } shouldBe 1
    }

    @Test
    fun `every rewriting entry carries a bare hostname`() {
        val malformed = GitHubMirrorCatalog.builtin
            .filter { it.strategy != GitHubMirrorStrategy.NATIVE }
            .filterNot { it.host?.matches(hostPattern) == true }
            .map { "${it.id} -> ${it.host}" }
        malformed shouldBe emptyList()
    }

    @Test
    fun `preferences pointing at removed hosts still resolve to a live mirror`() {
        // A stale preference must not silently degrade into a direct - blocked - connection.
        val resolved = listOf("kkgithub", "ghproxy", "ghproxy_net", "jsdmirror").map { legacy ->
            legacy to GitHubMirrorCatalog.builtinById(legacy)
        }
        resolved.map { (legacy, entry) ->
            "$legacy=${entry?.id}/${entry?.strategy}"
        } shouldBe listOf(
            "kkgithub=gh_proxy_com/PREFIX",
            "ghproxy=gh_proxy_com/PREFIX",
            "ghproxy_net=gh_proxy_com/PREFIX",
            "jsdmirror=gh_proxy_com/PREFIX",
        )
    }

    @Test
    fun `unknown ids stay unresolved so manifests can provide them`() {
        GitHubMirrorCatalog.builtinById("manifest_only_host") shouldBe null
    }
}
