package org.skepsun.kototoro.community.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/** The reader's own rating of a work, as last published (community plan §7.1). */
@Entity(tableName = "community_ratings", primaryKeys = ["manga_id"])
data class CommunityRatingEntity(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "stars") val stars: Int,
    /** The rating event's `d`; a later rating of the same work under another address withdraws this one. */
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** A signed event that enough relays have not yet confirmed; retried until they do. */
@Entity(tableName = "community_outbox", primaryKeys = ["event_id"])
data class CommunityOutboxEntity(
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "event_json") val eventJson: String,
    @ColumnInfo(name = "kind") val kind: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

/** What the last AniList or Bangumi search found for a work (core-domain CommunityExternalResolution). */
@Entity(tableName = "community_resolutions", primaryKeys = ["manga_id", "service"])
data class CommunityResolutionEntity(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "service") val service: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "remote_id") val remoteId: Long?,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
    @ColumnInfo(name = "failures") val failures: Int,
)
