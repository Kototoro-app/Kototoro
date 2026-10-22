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
        SourceEcosystem.CLOUDSTREAM -> "Cloudstream"
    }

    fun contentType(name: String) = when (name) {
        "MANGA" -> desktopText("label.contentType.MANGA")
        "MANHWA" -> desktopText("label.contentType.MANHWA")
        "MANHUA" -> desktopText("label.contentType.MANHUA")
        "HENTAI_MANGA" -> desktopText("label.contentType.HENTAI_MANGA")
        "HENTAI_NOVEL" -> desktopText("label.contentType.HENTAI_NOVEL")
        "HENTAI_VIDEO" -> desktopText("label.contentType.HENTAI_VIDEO")
        "COMICS" -> desktopText("label.contentType.COMICS")
        "VIDEO" -> desktopText("label.contentType.VIDEO")
        "NOVEL" -> desktopText("label.contentType.NOVEL")
        "ONE_SHOT" -> desktopText("label.contentType.ONE_SHOT")
        "DOUJINSHI" -> desktopText("label.contentType.DOUJINSHI")
        "IMAGE_SET" -> desktopText("label.contentType.IMAGE_SET")
        "ARTIST_CG" -> desktopText("label.contentType.ARTIST_CG")
        "GAME_CG" -> desktopText("label.contentType.GAME_CG")
        else -> desktopText("label.contentType.OTHER")
    }

    fun sortOrder(name: String) = when (name) {
        "UPDATED" -> desktopText("label.sort.UPDATED")
        "UPDATED_ASC" -> desktopText("label.sort.UPDATED_ASC")
        "POPULARITY" -> desktopText("label.sort.POPULARITY")
        "POPULARITY_ASC" -> desktopText("label.sort.POPULARITY_ASC")
        "POPULARITY_HOUR" -> desktopText("label.sort.POPULARITY_HOUR")
        "POPULARITY_TODAY" -> desktopText("label.sort.POPULARITY_TODAY")
        "POPULARITY_WEEK" -> desktopText("label.sort.POPULARITY_WEEK")
        "POPULARITY_MONTH" -> desktopText("label.sort.POPULARITY_MONTH")
        "POPULARITY_YEAR" -> desktopText("label.sort.POPULARITY_YEAR")
        "RATING" -> desktopText("label.sort.RATING")
        "RATING_ASC" -> desktopText("label.sort.RATING_ASC")
        "NEWEST" -> desktopText("label.sort.NEWEST")
        "NEWEST_ASC" -> desktopText("label.sort.NEWEST_ASC")
        "ALPHABETICAL" -> desktopText("label.sort.ALPHABETICAL")
        "ALPHABETICAL_DESC" -> desktopText("label.sort.ALPHABETICAL_DESC")
        "ADDED" -> desktopText("label.sort.ADDED")
        "ADDED_ASC" -> desktopText("label.sort.ADDED_ASC")
        "RELEVANCE" -> desktopText("label.sort.RELEVANCE")
        else -> name
    }
}
