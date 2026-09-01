package org.skepsun.kototoro.favourites.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Raw-SQL seeding helpers for the favourites library characterization suites.
 *
 * The queries deliberately mirror the production write paths (work_favourites /
 * entity_binding / entity_preferences columns) without going through repositories,
 * so the characterization tests stay independent of the write-side behaviour under
 * test elsewhere.
 */
internal object FavouriteLibrarySeed {

    fun insertCategory(sql: SupportSQLiteDatabase, id: Int, title: String, deletedAt: Long = 0) {
        sql.execSQL(
            "INSERT INTO favourite_categories VALUES (?, 0, 0, ?, 'NEWEST', 0, 1, ?)",
            arrayOf<Any?>(id, title, deletedAt),
        )
    }

    fun insertEntity(sql: SupportSQLiteDatabase, id: Long, name: String, contentType: String? = "MANGA") {
        sql.execSQL(
            "INSERT INTO entity (id, type, content_type, sync_id, primary_name, name_hash, aliases, created_at, last_accessed, access_count) " +
                "VALUES (?, 'WORK', ?, ?, ?, ?, NULL, 0, 0, 0)",
            arrayOf<Any?>(id, contentType, "sync-$id", name, id),
        )
    }

    fun insertManga(
        sql: SupportSQLiteDatabase,
        id: Long,
        title: String,
        source: String = "TEST",
        contentType: String? = "MANGA",
        rating: Float = 0f,
        nsfw: Boolean = false,
        state: String? = null,
        altTitle: String? = null,
        coverUrl: String? = null,
    ) {
        sql.execSQL(
            """
            INSERT INTO manga (
                manga_id, title, alt_title, url, public_url, rating, nsfw, content_rating,
                cover_url, large_cover_url, state, author, source, description, content_type
            ) VALUES (?, ?, ?, '', '', ?, ?, NULL, ?, NULL, ?, NULL, ?, NULL, ?)
            """.trimIndent(),
            arrayOf<Any?>(id, title, altTitle, rating, nsfw, coverUrl ?: "", state, source, contentType),
        )
    }

    fun insertFavourite(
        sql: SupportSQLiteDatabase,
        entityId: Long,
        categoryId: Long,
        anchorMangaId: Long?,
        pinned: Boolean = false,
        createdAt: Long = 0,
        updatedAt: Long = 0,
        deletedAt: Long = 0,
    ) {
        sql.execSQL(
            "INSERT INTO work_favourites VALUES (?, ?, ?, 0, ?, ?, ?, ?)",
            arrayOf<Any?>(entityId, categoryId, anchorMangaId, pinned, createdAt, deletedAt, updatedAt),
        )
    }

    fun insertBinding(
        sql: SupportSQLiteDatabase,
        entityId: Long,
        mangaId: Long,
        state: String = "CONFIRMED",
        source: String = "local_manga",
    ) {
        sql.execSQL(
            "INSERT INTO entity_binding VALUES (?, ?, ?, 1, 0, 'UNKNOWN', ?, 'LEGACY', 0)",
            arrayOf<Any?>(entityId, source, mangaId.toString(), state),
        )
    }

    fun insertPrefs(
        sql: SupportSQLiteDatabase,
        entityId: Long,
        preferredLocalMangaId: Long? = null,
        readingStatus: String? = null,
        titleOverride: String? = null,
        coverOverride: String? = null,
        metadataSourceKind: String? = null,
        metadataService: Int? = null,
        metadataRemoteId: Long? = null,
    ) {
        // entity_preferences column order (see EntityPrefsRecord): the five metadata_*
        // columns sit between reading_status and updated_at. The production writer keeps
        // the binding strings and the numeric columns in step, so the seed does too.
        sql.execSQL(
            "INSERT INTO entity_preferences VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, 0)",
            arrayOf<Any?>(
                entityId,
                preferredLocalMangaId,
                titleOverride,
                coverOverride,
                readingStatus,
                metadataSourceKind,
                metadataService?.toString(),
                metadataRemoteId?.toString(),
                metadataService,
                metadataRemoteId,
            ),
        )
    }

    /** Cached tracking site item: the payload behind a 'tracking' display authority. */
    fun insertTrackingSiteItem(
        sql: SupportSQLiteDatabase,
        service: Int,
        remoteId: Long,
        title: String,
        coverUrl: String?,
    ) {
        sql.execSQL(
            "INSERT INTO tracking_site_items (service, remote_id, title, cover_url, cached_at, updated_at)" +
                " VALUES (?, ?, ?, ?, 0, 0)",
            arrayOf<Any?>(service, remoteId, title, coverUrl),
        )
    }

    fun insertHistory(
        sql: SupportSQLiteDatabase,
        entityId: Long,
        anchorMangaId: Long,
        percent: Float,
        updatedAt: Long,
    ) {
        sql.execSQL(
            "INSERT INTO work_history VALUES (?, ?, ?, ?, 0, 0, 0, ?, 0, 0, NULL)",
            arrayOf<Any?>(entityId, anchorMangaId, updatedAt, updatedAt, percent),
        )
    }

    fun insertTrack(
        sql: SupportSQLiteDatabase,
        entityId: Long,
        mangaId: Long,
        newChapters: Int,
        lastChapterDate: Long,
        lastCheckTime: Long,
        ownerId: Long = entityId,
    ) {
        sql.execSQL(
            "INSERT INTO tracks VALUES (?, ?, ?, 0, ?, ?, ?, 1, NULL)",
            arrayOf<Any?>(ownerId, mangaId, entityId, newChapters, lastCheckTime, lastChapterDate),
        )
    }

    fun insertTag(sql: SupportSQLiteDatabase, id: Long, title: String, source: String = "TEST") {
        sql.execSQL(
            "INSERT INTO tags VALUES (?, ?, ?, ?, 0)",
            arrayOf<Any?>(id, title, title.lowercase(), source),
        )
    }

    fun insertMangaTag(sql: SupportSQLiteDatabase, mangaId: Long, tagId: Long) {
        sql.execSQL("INSERT INTO manga_tags VALUES (?, ?)", arrayOf<Any?>(mangaId, tagId))
    }

    fun insertDownloaded(sql: SupportSQLiteDatabase, mangaId: Long, path: String = "/tmp/item") {
        sql.execSQL("INSERT INTO local_index VALUES (?, ?)", arrayOf<Any?>(mangaId, path))
    }

    /** Seeds the 6.5k-entity synthetic library used by the large-library benchmarks. */
    fun seedLargeLibrary(sql: SupportSQLiteDatabase, count: Long = 6_500) {
        insertCategory(sql, 1, "Default")
        insertCategory(sql, 2, "Second")
        sql.beginTransaction()
        try {
            (1L..count).forEach { entityId ->
                val mangaId = entityId + 10_000L
                insertEntity(sql, entityId, "Work $entityId")
                insertManga(sql, mangaId, "Projection $mangaId")
                insertFavourite(sql, entityId, 1, mangaId, createdAt = entityId, updatedAt = entityId)
                if (entityId % 10L == 0L) {
                    insertFavourite(sql, entityId, 2, mangaId, createdAt = entityId, updatedAt = entityId)
                }
                if (entityId <= 3_200L) {
                    insertHistory(sql, entityId, mangaId, percent = 0.5f, updatedAt = entityId)
                }
                if (entityId % 5L == 0L) {
                    insertTrack(sql, entityId, mangaId, newChapters = 2, lastChapterDate = entityId, lastCheckTime = entityId)
                }
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }
}
