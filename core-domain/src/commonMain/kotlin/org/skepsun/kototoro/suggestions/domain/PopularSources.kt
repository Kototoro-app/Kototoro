package org.skepsun.kototoro.suggestions.domain

/** What a host knows about an installed source; [host] is the site's host when the host has one. */
data class PopularSourceProbe(val name: String, val title: String, val host: String? = null)

/**
 * Sites from EverythingMoe's anime, manga, manhwa and novel rankings (snapshot of 2026-11-19, top 20 of each list).
 * A key ending in `=` must equal the source's normalized title or site label; the others may be contained in it.
 */
object PopularSources {
    private val keys: List<String> = listOf(
        // manga / manhwa
        "comix=", "mangadot", "atsumaru", "atsu=", "mangaball", "mangafire", "toonkotsu", "weebcentral", "yaoime",
        "mangago", "mkissa", "chikari", "onisaga", "mangataro", "mangak=", "xcomic", "swarm=", "mangakatana",
        "mangacloud", "kagane", "webtoonclub", "baetoon", "toon18x", "manhwatop", "toonily",
        // novels
        "novelping", "novelarchive", "novelphoenix", "novelbuddy", "noveldex", "freewebnovel", "novelito", "lnori",
        "cyrisia", "wuxiabox", "wuxiaclick", "xnovel", "novelcool", "lncrawler", "novelhall",
        // anime
        "reanime", "kotoplay", "miruro", "gotaku", "animepahe", "anipm", "anizone", "kickassanime", "anisnatch",
        "animenexus", "aniwaves", "animex", "anv=", "anilight", "animeonsen", "anify=", "animekhor", "animexin",
        "donghuastream",
        // Chinese-language sites (curated list, not a published ranking): comics
        "拷贝漫画", "copymanga", "包子漫画", "baozimh", "哔咔", "picacg", "动漫之家", "dmzj", "漫画柜", "manhuagui",
        "古风漫画", "gufengmh", "酷漫屋", "kumanwu", "腾讯动漫", "快看", "manhuadb", "漫画db", "禁漫", "jmcomic",
        "mangabz", "漫画台", "百合会", "嗨皮漫画", "happymh", "cocomanga", "godamanga", "colamanga", "mhgui",
        // novels
        "笔趣阁", "biquge", "69书吧", "69shu", "起点", "qidian", "晋江", "jjwxc", "轻小说文库", "wenku8", "哔哩轻小说",
        "铅笔小说", "番茄小说", "飘天文学", "顶点小说", "二层楼", "ixdzs", "爱下电子书", "uukanshu", "uu看书", "新笔趣阁",
        // anime / video
        "樱花动漫", "樱花", "稀饭动漫", "嘀哩嘀哩", "dilidili", "age动漫", "agefans", "age=", "风车动漫", "奈菲", "netflav",
        "哔哩哔哩", "bilibili", "动漫花园", "dmhy", "爱壹帆", "iyf", "在线之家", "厂长资源", "低端影视", "omofun", "girigiri",
        "aafun", "努努", "cycani", "mikan", "蜜柑", "bangumi",
    )

    private val contained = keys.filterNot { it.endsWith("=") }
    private val exact = keys.filter { it.endsWith("=") }.map { it.dropLast(1) }.toSet()

    /** Lower-case letters and digits only, so `Manga Fire`, `mangafire.to` and `MANGAFIRE` compare equal. */
    private fun normalize(value: String) = buildString(value.length) {
        for (c in value) if (c.isLetterOrDigit()) append(c.lowercaseChar())
    }

    private fun siteLabel(host: String?): String? = host?.trim()?.lowercase()
        ?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/')?.removePrefix("www.")
        ?.substringBefore('.')?.takeIf { it.isNotEmpty() }

    fun isPopular(probe: PopularSourceProbe): Boolean {
        val forms = listOfNotNull(probe.title, probe.name, siteLabel(probe.host)).map(::normalize).filter { it.isNotEmpty() }
        return forms.any { form -> form in exact || contained.any { it in form } }
    }

    /** Names of the sources among [sources] that sit on the rankings. */
    fun select(sources: Collection<PopularSourceProbe>): Set<String> =
        sources.filter(::isPopular).mapTo(LinkedHashSet()) { it.name }

    /** The [limit] sources holding the most of the reader's saved works, most saved first. */
    fun favouriteSources(library: Collection<LibraryWork>, limit: Int = FAVOURITE_SOURCES): Set<String> = library
        .filter { it.isFavourite }
        .mapNotNull { it.source }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .take(limit).mapTo(LinkedHashSet()) { it.key }

    /**
     * Sources to put first: the reader's own picks, the ones their saved works come from when [favourites] is given, and
     * the popular ones when [popularFirst] is on; excluded never.
     */
    fun preferred(
        popularFirst: Boolean,
        picked: Set<String>,
        excluded: Set<String>,
        sources: Collection<PopularSourceProbe>,
        favourites: Set<String> = emptySet(),
    ): Set<String> = (picked + favourites + if (popularFirst) select(sources) else emptySet()) - excluded

    private const val FAVOURITE_SOURCES = 8
}
