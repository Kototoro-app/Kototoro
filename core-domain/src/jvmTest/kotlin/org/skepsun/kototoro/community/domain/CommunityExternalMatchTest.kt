package org.skepsun.kototoro.community.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Url
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.skepsun.kototoro.community.external.CommunityExternalHit
import org.skepsun.kototoro.community.external.CommunityExternalSearchApi
import org.skepsun.kototoro.community.external.CommunityExternalSearchException
import org.skepsun.kototoro.community.external.CommunityExternalSearchKind
import org.skepsun.kototoro.community.external.CommunityExternalSite
import java.io.File
import kotlin.random.Random

class CommunityExternalMatchTest {

    private val fold = CommunityEvalBackup.fold

    private data class Hit(val service: CommunityExternalService, val id: Long, val format: String?, val titles: List<String>)

    private fun accept(medium: CommunityMedium, localTitles: List<String>, vararg hits: Hit) = CommunityExternalMatch.accept(
        medium,
        CommunityWorkKeys.keys(medium, localTitles, fold = fold),
        hits.asList(),
        { it.service }, { it.id }, { it.format }, { it.titles },
        fold,
    )

    @Test
    fun `site formats map to media and leave out what nobody reads`() {
        val anilist = CommunityExternalService.ANILIST
        val bangumi = CommunityExternalService.BANGUMI
        assertEquals(CommunityMedium.COMIC, CommunityExternalMatch.mediumOf(anilist, "ONE_SHOT"))
        assertEquals(CommunityMedium.NOVEL, CommunityExternalMatch.mediumOf(anilist, "NOVEL"))
        assertEquals(CommunityMedium.VIDEO, CommunityExternalMatch.mediumOf(anilist, "ONA"))
        assertNull(CommunityExternalMatch.mediumOf(anilist, "MUSIC"))
        assertEquals(CommunityMedium.COMIC, CommunityExternalMatch.mediumOf(bangumi, "1/漫画"))
        assertEquals(CommunityMedium.NOVEL, CommunityExternalMatch.mediumOf(bangumi, "1/小说"))
        assertNull(CommunityExternalMatch.mediumOf(bangumi, "1/画集"))
        assertEquals(CommunityMedium.VIDEO, CommunityExternalMatch.mediumOf(bangumi, "6/"))
        assertNull(CommunityExternalMatch.mediumOf(bangumi, "4/PC"))
    }

    @Test
    fun `a hit is bound when one of its names is the work's title and it is the only such hit`() {
        val tougen = Hit(CommunityExternalService.ANILIST, 119968, "MANGA", listOf("Tougen Anki", "桃源暗鬼"))
        val gaiden = Hit(CommunityExternalService.ANILIST, 194341, "MANGA", listOf("Tougen Anki Gaiden", "桃源暗鬼外伝"))
        assertEquals(CommunityExternalRef(CommunityExternalService.ANILIST, 119968), accept(CommunityMedium.COMIC, listOf("桃源暗鬼"), tougen, gaiden))
        assertEquals(CommunityExternalRef(CommunityExternalService.ANILIST, 119968), accept(CommunityMedium.COMIC, listOf("Tougen-Anki!"), gaiden, tougen))
        assertNull(accept(CommunityMedium.COMIC, listOf("桃源暗鬼 外传"), tougen), "a near title is not the title")
        assertNull(accept(CommunityMedium.VIDEO, listOf("桃源暗鬼"), tougen), "the manga is not the anime")
    }

    @Test
    fun `two hits answering to the title bind neither`() {
        val first = Hit(CommunityExternalService.BANGUMI, 1, "1/漫画", listOf("同名作品"))
        val second = Hit(CommunityExternalService.BANGUMI, 2, "1/漫画", listOf("别的名字", "同名作品"))
        assertNull(accept(CommunityMedium.COMIC, listOf("同名作品"), first, second))
        assertEquals(2, CommunityExternalMatch.matches(CommunityMedium.COMIC, CommunityWorkKeys.keys(CommunityMedium.COMIC, listOf("同名作品"), fold = fold),
            listOf(first, second, first), { it.service }, { it.id }, { it.format }, { it.titles }, fold).size)
    }

    @Test
    fun `a short latin title is a word and binds nothing`() {
        assertFalse(CommunityExternalMatch.isDistinctive("kototoro:t:comic:love"))
        assertTrue(CommunityExternalMatch.isDistinctive("kototoro:t:comic:berserk"))
        assertTrue(CommunityExternalMatch.isDistinctive("kototoro:t:comic:潤愛"))
        assertNull(accept(CommunityMedium.COMIC, listOf("Love"), Hit(CommunityExternalService.ANILIST, 3, "MANGA", listOf("LOVE"))))
    }

    /**
     * Live: `-PcommunityResolveBackup=/path/to/kototoro.bk.zip [-PcommunityResolveSample=80]`. Searches AniList and
     * Bangumi for a seeded sample of the backup's unbound works, at each site's public rate, and writes every binding
     * with the names on both sides to build/community-resolve.txt to be read for wrong matches.
     */
    @Test
    @EnabledIfSystemProperty(named = "communityResolveBackup", matches = ".+")
    fun `external id coverage of a real backup`() = runBlocking {
        val works = CommunityEvalBackup.read(File(System.getProperty("communityResolveBackup")))
        val size = System.getProperty("communityResolveSample")?.toIntOrNull() ?: 80
        val sample = works
            .filter { it.medium != CommunityMedium.OTHER && it.refs.isEmpty() && it.titleKeys.isNotEmpty() }
            .shuffled(Random(7))
            .take(size)
        val proxy = (System.getenv("https_proxy") ?: System.getenv("HTTPS_PROXY"))?.takeIf { it.startsWith("http") }
        val client = HttpClient(Java) {
            install(HttpTimeout) { requestTimeoutMillis = 20_000 }
            engine { if (proxy != null) this.proxy = ProxyBuilder.http(Url(proxy)) }
        }
        val api = CommunityExternalSearchApi(client)

        data class Outcome(val ref: CommunityExternalRef?, val ambiguous: Boolean, val failure: String?, val names: List<String>) {
            val failed get() = failure != null
        }

        suspend fun resolve(site: CommunityExternalSite, spacingMs: Long): List<Outcome> = sample.map { work ->
            val service = CommunityExternalService.valueOf(site.name)
            val kind = if (work.medium == CommunityMedium.VIDEO) CommunityExternalSearchKind.VIDEO else CommunityExternalSearchKind.BOOK
            var outcome = Outcome(null, ambiguous = false, failure = null, names = emptyList())
            for (query in CommunityWorkKeys.searchTitles(work.titles).take(2)) {
                delay(spacingMs)
                fun describe(e: Throwable) = (e as? CommunityExternalSearchException)?.let { "HTTP ${it.status}" } ?: e::class.simpleName
                val hits = try {
                    api.search(site, query, kind)
                } catch (e: CommunityExternalSearchException) {
                    delay((e.retryAfterSeconds ?: 30) * 1000)
                    runCatching { api.search(site, query, kind) }.onFailure { outcome = outcome.copy(failure = describe(it)) }.getOrNull()
                } catch (e: Exception) {
                    outcome = outcome.copy(failure = describe(e))
                    null
                } ?: continue
                val matched = CommunityExternalMatch.matches(work.medium, work.titleKeys, hits,
                    { service }, CommunityExternalHit::id, CommunityExternalHit::format, CommunityExternalHit::titles, fold)
                if (matched.size == 1) {
                    val names = hits.first { it.id == matched.single().remoteId }.titles
                    return@map Outcome(matched.single(), ambiguous = false, failure = null, names = names)
                }
                if (matched.size > 1) outcome = outcome.copy(ambiguous = true)
            }
            outcome
        }

        try {
            val anilist = async { resolve(CommunityExternalSite.ANILIST, spacingMs = 2_100) }
            val bangumi = async { resolve(CommunityExternalSite.BANGUMI, spacingMs = 1_000) }
            val results = mapOf(CommunityExternalSite.ANILIST to anilist.await(), CommunityExternalSite.BANGUMI to bangumi.await())

            println("sample: ${sample.size} of ${works.size} works  by medium: ${sample.groupingBy { it.medium }.eachCount()}  adult: ${sample.count { it.adult }}")
            for ((site, outcomes) in results) {
                println("%-8s bound %d  ambiguous %d  failed %d %s".format(site, outcomes.count { it.ref != null },
                    outcomes.count { it.ref == null && it.ambiguous }, outcomes.count { it.failed && it.ref == null },
                    outcomes.filter { it.ref == null }.mapNotNull { it.failure }.groupingBy { it }.eachCount()))
            }
            val any = sample.indices.filter { i -> results.values.any { it[i].ref != null } }
            println("bound on either site: ${any.size} / ${sample.size}")
            for (adult in listOf(false, true)) {
                val part = sample.indices.filter { sample[it].adult == adult }
                println("  ${if (adult) "adult" else "general"}: ${part.count { it in any }} / ${part.size}")
            }
            for (medium in CommunityMedium.entries) {
                val part = sample.indices.filter { sample[it].medium == medium }
                if (part.isNotEmpty()) println("  $medium: ${part.count { it in any }} / ${part.size}")
            }
            val report = File("build/community-resolve.txt")
            report.printWriter().use { out ->
                sample.forEachIndexed { i, work ->
                    val bound = results.mapNotNull { (site, outcomes) -> outcomes[i].ref?.let { "$site ${it.remoteId} ${outcomes[i].names}" } }
                    out.println("${if (bound.isEmpty()) "-" else "+"} [${work.medium}${if (work.adult) "*" else ""}] ${work.titles} (${work.source})")
                    bound.forEach { out.println("      $it") }
                }
            }
            println("bindings written to ${report.absolutePath}")
        } finally {
            client.close()
        }
    }
}
