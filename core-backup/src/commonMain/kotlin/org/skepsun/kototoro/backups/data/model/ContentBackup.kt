package org.skepsun.kototoro.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN

@Serializable
class ContentBackup(
    @SerialName("id") val id: Long,
    @SerialName("title") val title: String,
    @SerialName("alt_title") val altTitles: String? = null,
    @SerialName("url") val url: String,
    @SerialName("public_url") val publicUrl: String,
    @SerialName("rating") val rating: Float = RATING_UNKNOWN,
    @SerialName("nsfw") val isNsfw: Boolean = false,
    @SerialName("content_rating") val contentRating: String? = null,
    @SerialName("cover_url") val coverUrl: String,
    @SerialName("large_cover_url") val largeCoverUrl: String? = null,
    @SerialName("state") val state: String? = null,
    @SerialName("author") val authors: String? = null,
    @SerialName("source") val source: String,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("tags") val tags: Set<TagBackup> = emptySet(),
    @SerialName("title_override") val titleOverride: String? = null,
    @SerialName("cover_override") val coverUrlOverride: String? = null,
    @SerialName("content_rating_override") val contentRatingOverride: String? = null,
    @SerialName("reading_status") val readingStatus: String? = null,
    @SerialName("metadata_source_kind") val metadataSourceKind: String? = null,
    @SerialName("metadata_source_service") val metadataSourceService: Int? = null,
    @SerialName("metadata_source_remote_id") val metadataSourceRemoteId: Long? = null,
) {

    constructor(entity: MangaWithTags) : this(
        id = entity.manga.id,
        title = entity.manga.title,
        altTitles = entity.manga.altTitles,
        url = entity.manga.url,
        publicUrl = entity.manga.publicUrl,
        rating = entity.manga.rating,
        isNsfw = entity.manga.isNsfw,
        contentRating = entity.manga.contentRating,
        coverUrl = sanitizeCoverUrl(entity.manga.coverUrl),
        largeCoverUrl = sanitizeLargeCoverUrl(entity.manga.largeCoverUrl),
        state = entity.manga.state,
        authors = entity.manga.authors,
        source = entity.manga.source,
        contentType = entity.manga.contentType,
        tags = entity.tags.mapTo(LinkedHashSet(entity.tags.size)) { TagBackup(it) },
    )

    fun toEntity() = MangaEntity(
        id = id,
        title = title,
        altTitles = altTitles,
        url = url,
        publicUrl = publicUrl,
        rating = rating,
        isNsfw = isNsfw,
        contentRating = contentRating,
        coverUrl = coverUrl,
        largeCoverUrl = largeCoverUrl,
        state = state,
        authors = authors,
        source = source,
        contentType = contentType,
    )

    // Embedded preferences are legacy wire fields. Current user state belongs to manga_id;
    // these fields do not recreate entity/work ownership.
    fun hasLegacyPrefsPayload(): Boolean {
        return titleOverride != null ||
            coverUrlOverride != null ||
            contentRatingOverride != null ||
            readingStatus != null ||
            metadataSourceKind != null ||
            metadataSourceService != null ||
            metadataSourceRemoteId != null
    }

    companion object {
        private fun sanitizeCoverUrl(url: String): String {
            if (url.startsWith("data:", ignoreCase = true)) {
                return ""
            }
            return url
        }

        private fun sanitizeLargeCoverUrl(url: String?): String? {
            if (url.isNullOrBlank()) return null
            if (url.startsWith("data:", ignoreCase = true)) {
                return null
            }
            return url
        }
    }
}
