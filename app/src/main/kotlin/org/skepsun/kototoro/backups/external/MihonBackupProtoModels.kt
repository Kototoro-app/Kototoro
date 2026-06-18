package org.skepsun.kototoro.backups.external

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class MihonBackup(
    @ProtoNumber(1) val backupManga: List<MihonBackupManga> = emptyList(),
    @ProtoNumber(2) val backupCategories: List<MihonBackupCategory> = emptyList(),
)

@Serializable
data class MihonBackupManga(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val title: String = "",
    @ProtoNumber(4) val artist: String? = null,
    @ProtoNumber(5) val author: String? = null,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val genre: List<String> = emptyList(),
    @ProtoNumber(8) val status: Int = 0,
    @ProtoNumber(9) val thumbnailUrl: String? = null,
    @ProtoNumber(13) val dateAdded: Long = 0,
    @ProtoNumber(100) val favorite: Boolean = true,
    @ProtoNumber(16) val chapters: List<MihonBackupChapter> = emptyList(),
    @ProtoNumber(17) val categories: List<Long> = emptyList(),
    @ProtoNumber(104) val history: List<MihonBackupHistory> = emptyList(),
    @ProtoNumber(106) val lastModifiedAt: Long = 0,
    @ProtoNumber(107) val favoriteModifiedAt: Long? = null,
)

@Serializable
data class MihonBackupCategory(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val order: Long = 0,
    @ProtoNumber(3) val id: Long = 0,
    @ProtoNumber(100) val flags: Long = 0,
)

@Serializable
data class MihonBackupChapter(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(4) val read: Boolean = false,
)

@Serializable
data class MihonBackupHistory(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val lastRead: Long,
)
