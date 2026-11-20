package org.skepsun.kototoro.community.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File

class CommunityWorkKeysTest {

    private val fold = CommunityEvalBackup.fold

    private fun ref(service: CommunityExternalService, id: Long) = CommunityExternalRef(service, id)

    @Test
    fun `strong keys are the sites' canonical urls for the medium`() {
        val comic = CommunityMedium.COMIC
        assertEquals("https://anilist.co/manga/30013", CommunityWorkKeys.strongKey(ref(CommunityExternalService.ANILIST, 30013), comic))
        assertEquals("https://myanimelist.net/anime/1", CommunityWorkKeys.strongKey(ref(CommunityExternalService.MAL, 1), CommunityMedium.VIDEO))
        assertEquals("https://shikimori.one/ranobe/7", CommunityWorkKeys.strongKey(ref(CommunityExternalService.SHIKIMORI, 7), CommunityMedium.NOVEL))
        assertEquals("https://bgm.tv/subject/9", CommunityWorkKeys.strongKey(ref(CommunityExternalService.BANGUMI, 9), CommunityMedium.VIDEO))
        assertNull(CommunityWorkKeys.strongKey(ref(CommunityExternalService.MANGAUPDATES, 5), CommunityMedium.VIDEO))
        assertNull(CommunityWorkKeys.strongKey(ref(CommunityExternalService.ANILIST, 5), CommunityMedium.OTHER))
        assertNull(CommunityWorkKeys.strongKey(ref(CommunityExternalService.ANILIST, 0), comic))
        assertEquals("https://anilist.co/manga/", CommunityWorkKeys.namespace("https://anilist.co/manga/30013"))
        assertFalse(CommunityWorkKeys.isStrong("kototoro:t:comic:onepiece"))
    }

    @Test
    fun `media group the content types a work is filed under`() {
        assertEquals(CommunityMedium.COMIC, CommunityMedium.of("MANHWA"))
        assertEquals(CommunityMedium.COMIC, CommunityMedium.of("HENTAI_MANGA"))
        assertEquals(CommunityMedium.NOVEL, CommunityMedium.of("HENTAI_NOVEL"))
        assertEquals(CommunityMedium.VIDEO, CommunityMedium.of("VIDEO"))
        assertEquals(CommunityMedium.OTHER, CommunityMedium.of(null))
        assertEquals(CommunityMedium.OTHER, CommunityMedium.of("GAME_CG"))
    }

    @Test
    fun `title keys ignore spacing, punctuation, width and a repeated source name`() {
        fun key(title: String) = CommunityWorkKeys.titleKey(title, CommunityMedium.COMIC, fold, listOf("Kototoro"))
        assertEquals("kototoro:t:comic:onepiece", key("One Piece"))
        assertEquals(key("One Piece"), key("ONE-PIECE!!"))
        assertEquals(key("One Piece"), key("ＯＮＥ　ＰＩＥＣＥ"))
        assertEquals("kototoro:t:comic:某作品", key("【某 作品】（Kototoro）"))
        assertEquals("kototoro:t:comic:re02", key("Re:0 2"))
        assertNull(key("2"))
        assertNull(key("A"))
        assertNull(key("!!"))
        assertNull(key("1999"))
        assertEquals(
            CommunityWorkKeys.TITLE_PREFIX.length + "comic:".length + CommunityWorkKeys.MAX_TITLE_LENGTH,
            key("x".repeat(500))!!.length,
        )
        val emoji = "a" + "😀".repeat(100)
        assertFalse(key(emoji)!!.last().isHighSurrogate(), "truncation never splits a surrogate pair")
    }

    @Test
    fun `list fields split into names and only real separators split`() {
        assertEquals(
            listOf("Douji Shoukan sareta Saikyou", "Bomber", "The Mercenary Bomber", "爆弾魔", "Me comí a un árbol", "세계수를 따먹다", "Mesutoiro", "雌女10色"),
            CommunityWorkKeys.searchTitles(listOf(
                "Douji Shoukan sareta Saikyou,Bomber,The Mercenary Bomber,爆弾魔",
                "Me comí a un árbol / 세계수를 따먹다",
                "Mesutoiro | 雌女10色",
            )),
        )
        assertEquals(
            listOf("Isekai ni Shoukan Saretan da kedo, Nandemo", "爆弾魔な傭兵、同時召喚", "本想报复，另一场战斗", "Fate/Zero"),
            CommunityWorkKeys.searchTitles(listOf("Isekai ni Shoukan Saretan da kedo, Nandemo", "爆弾魔な傭兵、同時召喚", "本想报复，另一场战斗", "Fate/Zero")),
        )
    }

    @Test
    fun `labels, group tags and raw markers are not part of a name`() {
        assertEquals("Fun Territory Defense by the Optimistic Lord", CommunityWorkKeys.stripNoise("Fun Territory Defense by the Optimistic Lord [Greed Scans]"))
        assertEquals("凡骨新兵のモンスターライフ", CommunityWorkKeys.stripNoise("Other name (s) : 凡骨新兵のモンスターライフ＠ＣＯＭＩＣ"))
        assertEquals("Tension MAX", CommunityWorkKeys.stripNoise("[COSX-041] [力量] Tension MAX"))
        assertEquals("Chained Soldier", CommunityWorkKeys.stripNoise("Chained Soldier (Official)"))
        assertEquals("Guild Leader", CommunityWorkKeys.stripNoise("Guild Leader raw"))
        assertEquals("【某 作品】", CommunityWorkKeys.stripNoise("【某 作品】"), "a title that is only a tag keeps it")
        assertEquals("Drawing", CommunityWorkKeys.stripNoise("Drawing"), "raw inside a word stays")
    }

    @Test
    fun `the romaji particle keys the same written wo or o`() {
        fun key(title: String) = CommunityWorkKeys.titleKey(title, CommunityMedium.COMIC, fold)
        assertEquals(key("Tenkuu no Shiro o Moratta"), key("Tenkuu no Shiro wo Moratta"))
        assertEquals(key("Keiken o Skill ni suru"), key("Keiken WO Skill ni suru"))
        assertEquals("kototoro:t:comic:wolfwoman", key("Wolf Woman"), "only the word wo changes")
        assertEquals("kototoro:t:comic:otitle", key("Wo, Title"))
    }

    @Test
    fun `an event's keys are strong first, then its titles, without repeats`() {
        val keys = CommunityWorkKeys.keys(
            CommunityMedium.COMIC,
            titles = listOf("One Piece", "one piece", "ワンピース") + (1..20).map { "Alt $it" },
            refs = listOf(ref(CommunityExternalService.MAL, 13), ref(CommunityExternalService.ANILIST, 30013), ref(CommunityExternalService.MAL, 13)),
            fold = fold,
        )
        assertEquals(listOf("https://anilist.co/manga/30013", "https://myanimelist.net/manga/13"), keys.take(2))
        assertEquals("kototoro:t:comic:onepiece", keys[2])
        assertEquals("kototoro:t:comic:ワンピース", keys[3])
        assertEquals(2 + CommunityWorkKeys.MAX_TITLE_KEYS, keys.size)
    }

    private val anilist = "https://anilist.co/manga/1"
    private val anilistOther = "https://anilist.co/manga/2"
    private val mal = "https://myanimelist.net/manga/10"
    private val malOther = "https://myanimelist.net/manga/20"
    private val bangumi = "https://bgm.tv/subject/100"
    private fun title(value: String) = "kototoro:t:comic:$value"

    @Test
    fun `strong keys seen together are one work and titles follow them`() {
        val groups = CommunityWorkGroups.build(
            listOf(
                listOf(anilist, mal, title("onepiece")),
                listOf(mal, bangumi),
                listOf(title("onepiece"), title("ワンピース")),
                listOf(title("ワンピース")),
            ),
        )
        val id = groups.groupOf(anilist)
        assertEquals(anilist, id, "the smallest strong key names the group")
        listOf(mal, bangumi, title("onepiece"), title("ワンピース")).forEach { assertEquals(id, groups.groupOf(it), it) }
        assertEquals(0, groups.conflicts)
        assertNull(groups.groupOf(title("unseen")))
    }

    @Test
    fun `a binding that gives a work two ids on one site loses to the majority`() {
        val groups = CommunityWorkGroups.build(
            listOf(listOf(anilist, mal), listOf(anilist, mal), listOf(anilist, malOther)),
        )
        assertEquals(groups.groupOf(anilist), groups.groupOf(mal))
        assertEquals(malOther, groups.groupOf(malOther))
        assertEquals(1, groups.conflicts)
    }

    @Test
    fun `a shared title never merges two bound works and a tie leaves it alone`() {
        val groups = CommunityWorkGroups.build(
            listOf(
                listOf(anilist, title("love")),
                listOf(anilistOther, title("love")),
                listOf(anilistOther, title("othertitle")),
                listOf(title("love"), title("othertitle")),
            ),
        )
        assertTrue(groups.groupOf(anilist) != groups.groupOf(anilistOther))
        assertEquals(1, groups.ambiguousTitleKeys)
        assertEquals(anilistOther, groups.groupOf(title("othertitle")))
        assertTrue(groups.groupOf(title("love")) !in setOf(anilist, anilistOther), "the tied title stays out of both")
    }

    @Test
    fun `a title joins the bound work it is seen with most`() {
        val groups = CommunityWorkGroups.build(
            listOf(
                listOf(anilist, title("love")),
                listOf(anilist, title("love")),
                listOf(anilistOther, title("love")),
            ),
        )
        assertEquals(anilist, groups.groupOf(title("love")))
        assertEquals(0, groups.ambiguousTitleKeys)
    }

    @Test
    fun `groups do not depend on the order events arrive in`() {
        val events = listOf(
            listOf(anilist, mal),
            listOf(anilist, malOther),
            listOf(bangumi, title("b")),
            listOf(title("a"), title("b")),
            listOf(title("c")),
        )
        val expected = CommunityWorkGroups.build(events).groups
        repeat(20) { seed ->
            assertEquals(expected, CommunityWorkGroups.build(events.shuffled(kotlin.random.Random(seed)).map { it.shuffled() }).groups)
        }
    }

    /**
     * Run with `-PcommunityEvalBackup=/path/to/kototoro.bk.zip`. Reports how much of a real library can be named at all
     * and which works its own titles would put together, to read for wrong merges.
     */
    @Test
    @EnabledIfSystemProperty(named = "communityEvalBackup", matches = ".+")
    fun `key coverage of a real backup`() {
        val works = CommunityEvalBackup.read(File(System.getProperty("communityEvalBackup")))

        println("works: ${works.size}  by medium: ${works.groupingBy { it.medium }.eachCount()}  adult: ${works.count { it.adult }}")
        println("with a strong key: ${works.count { w -> w.keys.any(CommunityWorkKeys::isStrong) }}")
        println("with no key at all: ${works.count { it.keys.isEmpty() }}")
        println("title keys per work: %.2f".format(works.map { w -> w.keys.count { !CommunityWorkKeys.isStrong(it) } }.average()))

        val groups = CommunityWorkGroups.build(works.map { it.keys })
        val byGroup = works.filter { it.keys.isNotEmpty() }.groupBy { groups.groupOf(it.keys.first()) }
        val shared = byGroup.filterValues { it.size > 1 }
        val crossSource = shared.filterValues { members -> members.map { it.source }.distinct().size > 1 }
        val sameSource = shared.filterValues { members -> members.map { it.source }.distinct().size == 1 }
        println("groups: ${byGroup.size}  with several works: ${shared.size}  across sources: ${crossSource.size}  " +
            "within one source (suspect): ${sameSource.size}  conflicts: ${groups.conflicts}  ambiguous titles: ${groups.ambiguousTitleKeys}")
        for ((label, sample) in listOf("across sources" to crossSource, "within one source" to sameSource)) {
            println("-- $label")
            sample.entries.take(25).forEach { (id, members) ->
                println("  $id <- " + members.joinToString { "${it.source}${if (it.adult) "*" else ""}" })
            }
        }
    }
}
