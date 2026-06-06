package org.skepsun.kototoro.details.ui.model

import android.os.Parcelable
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.parsers.model.Content
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

sealed interface DetailsOrigin : Parcelable {
    @Parcelize
    data class LocalMangaId(val mangaId: Long) : DetailsOrigin

    @Parcelize
    data class LocalMangaContent(val parcelableContent: ParcelableContent) : DetailsOrigin {
        @IgnoredOnParcel
        val manga: Content get() = parcelableContent.manga
    }

    @Parcelize
    data class EntityGraph(
        val entityId: Long,
        val preferredLocalMangaId: Long? = null,
        val initialProjectionLocalMangaId: Long? = null,
        val serviceId: String? = null,
        val remoteId: Long? = null,
        val url: String? = null,
    ) : DetailsOrigin

    @Parcelize
    data class TrackingEntity(
        val serviceId: String,
        val entityTypeName: String,
        val remoteId: Long,
        val name: String,
        val altName: String? = null,
        val coverUrl: String? = null,
        val url: String? = null,
    ) : DetailsOrigin

    @Parcelize
    data class TrackingItem(
        val serviceId: String,
        val remoteId: Long,
        val url: String? = null
    ) : DetailsOrigin
}
