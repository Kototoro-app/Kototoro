package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.ui.source.contentTypeTitle

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
        SourceEcosystem.TVBOX -> "TVBox"
    }

    /** Android's own content-type title, so both hosts read one translation instead of two copies of the text. */
    fun contentType(name: String) = DesktopUiStrings.contentTypeTitle(name)

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
