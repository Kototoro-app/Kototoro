package org.skepsun.kototoro.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Strict source-key registry for content sources that are not (or no longer) reachable through
 * their original extension, e.g. offline self-hosted Tsundoku repositories.
 *
 * Each row is keyed by a strict source key such as `TSUNDOKU_9001` and carries the minimum
 * stable metadata needed to keep the source addressable after the original extension is gone.
 * All nullable fields are derived facts when known and `null` when not; unknown values are
 * preserved as-is and never guessed by recovery code.
 */
@Entity(
    tableName = "source_origins",
    indices = [
        Index(value = ["kind"]),
        Index(value = ["repository_url"]),
    ],
)
data class SourceOriginEntity(
    /** Strict source key, e.g. `TSUNDOKU_9001` for an offline Tsundoku fixture. */
    @PrimaryKey
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    /** Stable source-kind discriminator, e.g. `MIHON` / `ANIYOMI` / `IREADER` / `TSUNDOKU` / `UNKNOWN`. */
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,
    @ColumnInfo(name = "content_type")
    val contentType: String? = null,
    @ColumnInfo(name = "package_name")
    val packageName: String? = null,
    /** Source id within the extension (Tsundoku ids are numeric, but kept as text to stay uniform). */
    @ColumnInfo(name = "source_id")
    val sourceId: String? = null,
    @ColumnInfo(name = "repository_url")
    val repositoryUrl: String? = null,
    @ColumnInfo(name = "repository_name")
    val repositoryName: String? = null,
    /** URL / file / import locator for non-extension sources. */
    @ColumnInfo(name = "locator")
    val locator: String? = null,
    @ColumnInfo(name = "version_name")
    val versionName: String? = null,
    @ColumnInfo(name = "version_code")
    val versionCode: Long? = null,
    @ColumnInfo(name = "signing_digest")
    val signingDigest: String? = null,
    /** Epoch millis of the last time this origin was observed. */
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long? = null,
    /** Epoch millis of the last row update. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
