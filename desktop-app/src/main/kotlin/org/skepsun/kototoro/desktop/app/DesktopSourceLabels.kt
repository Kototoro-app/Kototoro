package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourceEcosystem

/** User-facing names for source metadata; the identifiers themselves stay in the protocol. */
internal object DesktopSourceLabels {
    fun ecosystem(ecosystem: SourceEcosystem) = when (ecosystem) {
        SourceEcosystem.MIHON -> "Mihon"
        SourceEcosystem.KOTOTORO -> "Kototoro"
        SourceEcosystem.KOTATSU -> "Kotatsu"
        SourceEcosystem.UMA -> "UMA"
        SourceEcosystem.ANIYOMI -> "Aniyomi"
        SourceEcosystem.TSUNDOKU -> "Tsundoku"
    }

    fun contentType(name: String) = when (name) {
        "MANGA" -> "漫画"
        "MANHWA" -> "韩漫"
        "MANHUA" -> "国漫"
        "HENTAI_MANGA" -> "成人漫画"
        "HENTAI_NOVEL" -> "成人小说"
        "HENTAI_VIDEO" -> "成人视频"
        "COMICS" -> "欧美漫画"
        "VIDEO" -> "视频"
        "NOVEL" -> "小说"
        "ONE_SHOT" -> "短篇"
        "DOUJINSHI" -> "同人志"
        "IMAGE_SET" -> "图集"
        "ARTIST_CG" -> "画师 CG"
        "GAME_CG" -> "游戏 CG"
        else -> "其他"
    }

    fun sortOrder(name: String) = when (name) {
        "UPDATED" -> "最近更新"
        "UPDATED_ASC" -> "最早更新"
        "POPULARITY" -> "人气"
        "POPULARITY_ASC" -> "人气（升序）"
        "POPULARITY_HOUR" -> "人气 · 1 小时"
        "POPULARITY_TODAY" -> "人气 · 今日"
        "POPULARITY_WEEK" -> "人气 · 本周"
        "POPULARITY_MONTH" -> "人气 · 本月"
        "POPULARITY_YEAR" -> "人气 · 本年"
        "RATING" -> "评分"
        "RATING_ASC" -> "评分（升序）"
        "NEWEST" -> "最新发布"
        "NEWEST_ASC" -> "最早发布"
        "ALPHABETICAL" -> "名称 A–Z"
        "ALPHABETICAL_DESC" -> "名称 Z–A"
        "ADDED" -> "最近添加"
        "ADDED_ASC" -> "最早添加"
        "RELEVANCE" -> "相关度"
        else -> name
    }
}
