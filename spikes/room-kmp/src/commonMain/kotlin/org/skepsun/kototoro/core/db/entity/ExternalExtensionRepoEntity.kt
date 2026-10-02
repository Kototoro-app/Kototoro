package org.skepsun.kototoro.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import org.skepsun.kototoro.core.db.TABLE_EXTENSION_REPOS
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType

@Entity(
    tableName = TABLE_EXTENSION_REPOS,
    primaryKeys = ["type", "base_url"],
    indices = [
        Index(value = ["type"]),
        Index(value = ["type", "signing_key_fingerprint"], unique = true),
    ],
)
data class ExternalExtensionRepoEntity(
    @ColumnInfo(name = "type")
    val type: ExternalExtensionType,
    @ColumnInfo(name = "base_url")
    val baseUrl: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "short_name")
    val shortName: String?,
    @ColumnInfo(name = "website")
    val website: String,
    @ColumnInfo(name = "signing_key_fingerprint")
    val signingKeyFingerprint: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_success_at")
    val lastSuccessAt: Long,
    @ColumnInfo(name = "last_error")
    val lastError: String?,
    @ColumnInfo(name = "version")
    val version: String?,
)
