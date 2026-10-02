package org.skepsun.kototoro.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import org.skepsun.kototoro.core.db.TABLE_TRACKING_SITE_ITEMS

@Entity(
    tableName = TABLE_TRACKING_SITE_ITEMS,
    primaryKeys = ["service", "remote_id"],
    indices = [
        Index(value = ["service", "updated_at"], orders = [Index.Order.ASC, Index.Order.DESC]),
    ],
)
data class TrackingSiteItemEntity(
    @ColumnInfo(name = "service") val service: Int,
    @ColumnInfo(name = "remote_id") val remoteId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "alt_titles") val altTitles: String?,
    @ColumnInfo(name = "primary_title") val primaryTitle: String?,
    @ColumnInfo(name = "secondary_title") val secondaryTitle: String?,
    @ColumnInfo(name = "rating") val rating: Float?,
    @ColumnInfo(name = "score_max") val scoreMax: Float?,
    @ColumnInfo(name = "rank") val rank: Int?,
    @ColumnInfo(name = "summary") val summary: String?,
    @ColumnInfo(name = "progress_text") val progressText: String?,
    @ColumnInfo(name = "updated_at_text") val updatedAtText: String?,
    @ColumnInfo(name = "tags") val tags: String?,
    @ColumnInfo(name = "year") val year: Int?,
    @ColumnInfo(name = "authors") val authors: String?,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "total_episodes") val totalEpisodes: Int?,
    @ColumnInfo(name = "publish_date") val publishDate: String?,
    @ColumnInfo(name = "site_url") val siteUrl: String?,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
