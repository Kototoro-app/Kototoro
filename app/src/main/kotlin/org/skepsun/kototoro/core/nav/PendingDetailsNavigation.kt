package org.skepsun.kototoro.core.nav

import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.parsers.model.Content

object PendingDetailsNavigation {

    private var pendingOrigin: DetailsOrigin? = null
    private var lastContent: Content? = null
    private var lastSharedElementKey: String? = null

    fun set(origin: DetailsOrigin, sharedElementKey: String? = null) {
        pendingOrigin = origin
        lastContent = when (origin) {
            is DetailsOrigin.LocalMangaContent -> origin.parcelableContent.manga
            else -> null
        }
        lastSharedElementKey = sharedElementKey
    }

    fun set(content: Content, sharedElementKey: String? = null) {
        val origin = DetailsOrigin.LocalMangaContent(
            org.skepsun.kototoro.core.model.parcelable.ParcelableContent(content),
        )
        pendingOrigin = origin
        lastContent = content
        lastSharedElementKey = sharedElementKey
    }

    fun consume(): DetailsOrigin? = pendingOrigin.also { pendingOrigin = null }

    /** Non-destructive read: for re-seeding the payload from durable state when it went missing. */
    fun peek(): DetailsOrigin? = pendingOrigin

    fun lastContent(): Content? = lastContent

    fun lastSharedElementKey(): String? = lastSharedElementKey

    fun clearLastContent() {
        lastContent = null
        lastSharedElementKey = null
    }
}
