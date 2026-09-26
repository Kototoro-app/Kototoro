package org.skepsun.kototoro.favourites.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Raw-SQL seeding helpers for the library read-model suites.
 *
 * The queries deliberately mirror the production write paths (favourites / history /
 * preferences / tracks columns, all owned by `manga_id`) without going through
 * repositories, so the read-side tests stay independent of the write-side behaviour
 * under test elsewhere.
 */
internal object FavouriteLibrarySeed {

    fun insertCategory(sql: SupportSQLiteDatabase, id: Int, title: String, deletedAt: Long = 0) {
        sql.execSQL(
            "INSERT INTO favourite_categories VALUES (?, 0, 0, ?, 'NEWEST', 0, 1, ?)",
            arrayOf<Any?>(id, title, deletedAt),
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
        mangaId: Long,
        categoryId: Long,
        pinned: Boolean = false,
        createdAt: Long = 0,
        updatedAt: Long = 0,
        deletedAt: Long = 0,
    ) {
        sql.execSQL(
            "INSERT INTO favourites (manga_id, category_id, sort_key, pinned, created_at, deleted_at, updated_at) " +
                "VALUES (?, ?, 0, ?, ?, ?, ?)",
            arrayOf<Any?>(mangaId, categoryId, pinned, createdAt, deletedAt, updatedAt),
        )
    }

    fun insertPrefs(
        sql: SupportSQLiteDatabase,
        mangaId: Long,
        readingStatus: String? = null,
        titleOverride: String? = null,
        coverOverride: String? = null,
        metadataSourceKind: String? = null,
        metadataService: Int? = null,
        metadataRemoteId: Long? = null,
    ) {
        sql.execSQL(
            """
            INSERT INTO preferences (
                manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale, cf_book,
                title_override, cover_override, metadata_source_kind, metadata_source_service,
                metadata_source_remote_id, reading_status
            ) VALUES (?, 0, 0, 0, 0, 0, 0, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                mangaId,
                titleOverride,
                coverOverride,
                metadataSourceKind,
                metadataService,
                metadataRemoteId,
                readingStatus,
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
        mangaId: Long,
        percent: Float,
        updatedAt: Long,
        chaptersCount: Int = 0,
        deletedAt: Long = 0,
    ) {
        sql.execSQL(
            "INSERT INTO history (manga_id, created_at, updated_at, chapter_id, page, scroll, percent, " +
                "deleted_at, chapters, parent_chapter_id) VALUES (?, ?, ?, 0, 0, 0, ?, ?, ?, NULL)",
            arrayOf<Any?>(mangaId, updatedAt, updatedAt, percent, deletedAt, chaptersCount),
        )
    }

    fun insertTrack(
        sql: SupportSQLiteDatabase,
        mangaId: Long,
        newChapters: Int,
        lastChapterDate: Long,
        lastCheckTime: Long,
    ) {
        sql.execSQL(
            "INSERT INTO tracks (manga_id, last_chapter_id, chapters_new, last_check_time, " +
                "last_chapter_date, last_result, last_error) VALUES (?, 0, ?, ?, ?, 1, NULL)",
            arrayOf<Any?>(mangaId, newChapters, lastCheckTime, lastChapterDate),
        )
    }

    fun insertTrackLog(
        sql: SupportSQLiteDatabase,
        mangaId: Long,
        chapters: String,
        createdAt: Long,
        unread: Boolean,
    ) {
        sql.execSQL(
            "INSERT INTO track_logs (manga_id, chapters, created_at, unread) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(mangaId, chapters, createdAt, unread),
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

    /** Seeds the 6.5k-item synthetic library used by the large-library benchmarks. */
    fun seedLargeLibrary(sql: SupportSQLiteDatabase, count: Long = 6_500) {
        insertCategory(sql, 1, "Default")
        insertCategory(sql, 2, "Second")
        sql.beginTransaction()
        try {
            (1L..count).forEach { index ->
                val mangaId = index + 10_000L
                insertManga(sql, mangaId, "Projection $mangaId")
                insertFavourite(sql, mangaId, 1, createdAt = index, updatedAt = index)
                if (index % 10L == 0L) {
                    insertFavourite(sql, mangaId, 2, createdAt = index, updatedAt = index)
                }
                if (index <= 3_200L) {
                    insertHistory(sql, mangaId, percent = 0.5f, updatedAt = index)
                }
                if (index % 5L == 0L) {
                    insertTrack(sql, mangaId, newChapters = 2, lastChapterDate = index, lastCheckTime = index)
                }
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }
}
