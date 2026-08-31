package org.skepsun.kototoro.core.prefs

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.R

/** How a mirror rewrites GitHub URLs. */
@Serializable
enum class GitHubMirrorStrategy {
    NATIVE,
    HOST_REPLACE,
    PREFIX,
    JSDELIVR,
}

/**
 * One selectable GitHub download mirror. Mirrors are no longer a compile-time
 * enum that needs an exhaustive `when`; remote manifests (see
 * [GitHubMirrorManifest]) can add entries at runtime.
 *
 * @property id Stable identifier, also the persisted preference value.
 * @property name Display name (English); built-in entries also keep their
 *   localized labels via [GitHubMirrorEntry.displayName].
 * @property strategy How to rewrite URLs.
 * @property host Strategy parameter: prefix host for [GitHubMirrorStrategy.PREFIX],
 *   `github.com` replacement for [GitHubMirrorStrategy.HOST_REPLACE], jsDelivr
 *   CDN host for [GitHubMirrorStrategy.JSDELIVR].
 * @property rawHost For [GitHubMirrorStrategy.HOST_REPLACE]: the
 *   `raw.githubusercontent.com` replacement.
 */
@Serializable
data class GitHubMirrorEntry(
    val id: String,
    val name: String,
    val strategy: GitHubMirrorStrategy,
    val host: String? = null,
    val rawHost: String? = null,
    val note: String? = null,
)

/**
 * Remote mirror manifest, e.g. `docs/github-mirrors.json` synced through a
 * non-GitHub CDN (jsDelivr) so refreshing never depends on GitHub itself.
 */
@Serializable
data class GitHubMirrorManifest(
    val version: String,
    val updatedAt: String? = null,
    val mirrors: List<GitHubMirrorEntry>,
)

/**
 * Static catalog: the built-in mirrors (also mirrored in
 * `pref_github_mirror_entries/values`) plus the pure URL rewriting rules.
 */
object GitHubMirrorCatalog {

    val NATIVE = GitHubMirrorEntry(
        id = "native",
        name = "Direct Native (Default)",
        strategy = GitHubMirrorStrategy.NATIVE,
    )

    val builtin: List<GitHubMirrorEntry> = listOf(
        NATIVE,
        // Survivors of the 2026-08 reachability sweep (a real raw .jar was fetched through
        // each host). Kept their original ids so existing preferences stay valid.
        GitHubMirrorEntry(
            id = "gh_proxy_com",
            name = "gh-proxy.com",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "gh-proxy.com",
        ),
        GitHubMirrorEntry(
            id = "ghfast_top",
            name = "ghfast.top",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "ghfast.top",
            note = "Unreachable outside CN in the 2026-08 sweep; kept as it may serve CN users.",
        ),
        GitHubMirrorEntry(
            id = "moeyy",
            name = "github.moeyy.xyz",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "github.moeyy.xyz",
            note = "Unreachable outside CN in the 2026-08 sweep; kept as it may serve CN users.",
        ),
        // Public "GitHub 加速" frontends from github.com/XIU2/UserScript, imported only
        // where the probe returned the file (206/200). Hosts answering 403/301-without-body
        // or with an expired TLS certificate were left out on purpose.
        GitHubMirrorEntry(
            id = "gh_proxy_org",
            name = "gh-proxy.org",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "gh-proxy.org",
            note = "XIU2 · US",
        ),
        GitHubMirrorEntry(
            id = "cdn_gh_proxy_org",
            name = "cdn.gh-proxy.org",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "cdn.gh-proxy.org",
            note = "XIU2 · Fastly",
        ),
        GitHubMirrorEntry(
            id = "edgeone_gh_proxy_org",
            name = "edgeone.gh-proxy.org",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "edgeone.gh-proxy.org",
            note = "XIU2 · EdgeOne",
        ),
        GitHubMirrorEntry(
            id = "github_boki_moe",
            name = "github.boki.moe",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "github.boki.moe",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "gh_jasonzeng_dev",
            name = "gh.jasonzeng.dev",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "gh.jasonzeng.dev",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "gh_monlor_com",
            name = "gh.monlor.com",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "gh.monlor.com",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "github_geekery_cn",
            name = "github.geekery.cn",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "github.geekery.cn",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "github_ednovas_xyz",
            name = "github.ednovas.xyz",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "github.ednovas.xyz",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "ghfile_geekertao_top",
            name = "ghfile.geekertao.top",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "ghfile.geekertao.top",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "ghp_keleyaa_com",
            name = "ghp.keleyaa.com",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "ghp.keleyaa.com",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "gh_chjina_com",
            name = "gh.chjina.com",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "gh.chjina.com",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "ghpxy_hwinzniej_top",
            name = "ghpxy.hwinzniej.top",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "ghpxy.hwinzniej.top",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "cdn_crashmc_com",
            name = "cdn.crashmc.com",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "cdn.crashmc.com",
            note = "XIU2 · US CF",
        ),
        GitHubMirrorEntry(
            id = "git_yylx_win",
            name = "git.yylx.win",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "git.yylx.win",
            note = "XIU2 · US CF",
        ),
        GitHubMirrorEntry(
            id = "gitproxy_mrhjx_cn",
            name = "gitproxy.mrhjx.cn",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "gitproxy.mrhjx.cn",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "ghproxy_cxkpro_top",
            name = "ghproxy.cxkpro.top",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "ghproxy.cxkpro.top",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "gh_xxooo_cf",
            name = "gh.xxooo.cf",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "gh.xxooo.cf",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "gh_idayer_com",
            name = "gh.idayer.com",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "gh.idayer.com",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "gh_zwy_one",
            name = "gh.zwy.one",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "gh.zwy.one",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "ghproxy_monkeyray_net",
            name = "ghproxy.monkeyray.net",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "ghproxy.monkeyray.net",
            note = "XIU2 · US Cloudflare",
        ),
        GitHubMirrorEntry(
            id = "wget_la",
            name = "wget.la",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "wget.la",
            note = "XIU2 · HK/JP/US CDN",
        ),
        GitHubMirrorEntry(
            id = "hk_gh_proxy_org",
            name = "hk.gh-proxy.org",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "hk.gh-proxy.org",
            note = "XIU2 · HK",
        ),
        GitHubMirrorEntry(
            id = "gh_catmak_name",
            name = "gh.catmak.name",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "gh.catmak.name",
            note = "XIU2 · KR",
        ),
        GitHubMirrorEntry(
            id = "g_blfrp_cn",
            name = "g.blfrp.cn",
            strategy = GitHubMirrorStrategy.PREFIX,
            host = "g.blfrp.cn",
            note = "XIU2 · JP",
        ),
    )

    /**
     * Preferences persisted before the 2026-08 cleanup may point at hosts that were
     * removed (dead project, expired certificate, or jsDelivr, which cannot serve
     * extension archives). Resolve them to a known-good mirror instead of silently
     * falling back to a direct — blocked — connection.
     */
    private val legacyAliases = mapOf(
        "kkgithub" to "gh_proxy_com",
        "ghproxy" to "gh_proxy_com",
        "ghproxy_net" to "gh_proxy_com",
        "jsdmirror" to "gh_proxy_com",
    )

    /** Fallback ref for the out-of-band config file when none is configured. */
    const val DEFAULT_BRANCH = "devel"

    private val builtinById = builtin.associateBy { it.id }

    /**
     * Resolve a built-in entry by id (following [legacyAliases]); null for ids that
     * only a remote manifest provides.
     */
    fun builtinById(id: String): GitHubMirrorEntry? =
        builtinById[id] ?: legacyAliases[id]?.let { builtinById[it] }

    /**
     * Manifest candidate URLs, tried in order: non-GitHub CDNs (jsDelivr
     * mirrors) first so refreshing never depends on GitHub itself, with the
     * GitHub raw file only as a last resort.
     */
    fun syncCandidateUrls(repository: String, branch: String = DEFAULT_BRANCH): List<String> {
        val ref = branch.ifBlank { DEFAULT_BRANCH }
        return listOf(
            "https://cdn.jsdmirror.com/gh/$repository@$ref/docs/github-mirrors.json",
            "https://cdn.jsdelivr.net/gh/$repository@$ref/docs/github-mirrors.json",
            "https://fastly.jsdelivr.net/gh/$repository@$ref/docs/github-mirrors.json",
            "https://gcore.jsdelivr.net/gh/$repository@$ref/docs/github-mirrors.json",
            "https://raw.githubusercontent.com/$repository/$ref/docs/github-mirrors.json",
        )
    }

    /**
     * Merge (and validate) a remote manifest into a display list:
     *  - NATIVE is always first and always present;
     *  - remote entries keep their order, duplicates (by id) collapse to first;
     *  - any built-in mirror missing from the manifest is re-appended at the
     *    end so known mirrors never disappear after a stale sync.
     */
    fun normalizeMirrors(mirrors: List<GitHubMirrorEntry>?): List<GitHubMirrorEntry> {
        val safe = mirrors.orEmpty().filter { it.id.isNotBlank() }
        if (safe.isEmpty()) return builtin

        val result = LinkedHashMap<String, GitHubMirrorEntry>(safe.size + builtin.size)
        result[NATIVE.id] = NATIVE
        for (entry in safe) {
            if (entry.strategy == GitHubMirrorStrategy.NATIVE) continue
            result.putIfAbsent(entry.id, entry)
        }
        for (entry in builtin) {
            if (entry.strategy == GitHubMirrorStrategy.NATIVE) continue
            result.putIfAbsent(entry.id, entry)
        }
        return result.values.toList()
    }

    /** Rewrite a GitHub URL through a resolved [GitHubMirrorEntry]. */
    fun apply(url: String, entry: GitHubMirrorEntry): String = when (entry.strategy) {
        GitHubMirrorStrategy.NATIVE -> url

        GitHubMirrorStrategy.HOST_REPLACE -> {
            val host = entry.host ?: return url
            val rawHost = entry.rawHost ?: host
            when {
                url.startsWith("https://raw.githubusercontent.com/") ->
                    url.replace("raw.githubusercontent.com", rawHost)
                url.startsWith("https://github.com/") ->
                    url.replace("github.com", host)
                else -> url
            }
        }

        GitHubMirrorStrategy.PREFIX -> {
            val host = entry.host ?: return url
            prefix(url, "https://$host/")
        }

        GitHubMirrorStrategy.JSDELIVR -> jsDelivr(url, entry.host ?: "cdn.jsdmirror.com")
    }

    private fun prefix(url: String, prefix: String): String = when {
        url.startsWith("https://raw.githubusercontent.com/") ||
            url.startsWith("https://github.com/") -> "$prefix$url"
        else -> url
    }

    /**
     * jsDelivr-aware rewrite: only raw-file-style GitHub URLs can be served by
     * jsDelivr; release / asset / API URLs are left untouched.
     */
    private fun jsDelivr(url: String, cdnHost: String): String {
        val rawPrefix = "https://raw.githubusercontent.com/"
        val githubPrefix = "https://github.com/"
        return when {
            url.startsWith(rawPrefix) -> {
                val segments = url.removePrefix(rawPrefix).split('/')
                toJsDelivrPath(segments, cdnHost)
            }
            url.startsWith(githubPrefix) -> {
                val segments = url.removePrefix(githubPrefix).split('/')
                // github.com/owner/repo/raw/<branch-or-ref>/<path...>
                if (segments.size >= 5 && segments[2] == "raw") {
                    toJsDelivrPath(
                        listOf(segments[0], segments[1]) + segments.drop(3),
                        cdnHost,
                    )
                } else {
                    url
                }
            }
            else -> url
        } ?: url
    }

    /**
     * `owner / repo / ref / path...` -> `https://<cdn>/gh/owner/repo@ref/path`.
     * `ref` may be a plain branch/tag or an explicit `refs/heads/...` /
     * `refs/tags/...` segment; the latter is collapsed so the jsDelivr `@ref`
     * syntax stays unambiguous.
     */
    private fun toJsDelivrPath(segments: List<String>, cdnHost: String): String? {
        if (segments.size < 4) return null
        val owner = segments[0]
        val repo = segments[1]
        if (owner.isBlank() || repo.isBlank()) return null
        val refParts = when {
            segments[2] == "refs" && segments.size >= 5 && segments[3] in setOf("heads", "tags") ->
                listOf(segments[4]) + segments.drop(5)
            else -> segments.drop(2)
        }
        if (refParts.isEmpty()) return null
        val ref = refParts.first()
        if (ref.isBlank()) return null
        val path = refParts.drop(1)
        return buildString {
            append("https://")
            append(cdnHost)
            append("/gh/")
            append(owner)
            append('/')
            append(repo)
            append('@')
            append(ref)
            if (path.isNotEmpty()) {
                append('/')
                append(path.joinToString("/"))
            }
        }
    }

    /** JSON codec used to parse remote manifests. */
    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
    }
}

/** Localized display label: built-ins keep array labels, remote entries fall back to [GitHubMirrorEntry.name]. */
/**
 * Label for the mirror.
 *
 * Only the direct-connection entry has a translatable label: every other mirror
 * *is* a hostname, which must not be translated or localised. The previous
 * implementation looked the label up by position in a `string-array`, which meant
 * the list could not grow or shrink without hand-editing every locale's array in
 * exactly the same order — with ~28 public mirrors that coupling is unmaintainable.
 */
fun GitHubMirrorEntry.displayName(context: Context): String =
    if (id == GitHubMirrorCatalog.NATIVE.id) {
        context.getString(R.string.github_mirror_native)
    } else {
        name
    }
