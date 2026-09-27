package org.skepsun.kototoro.core.db.migrations

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_CHAPTERS
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_RELATION
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_HISTORY
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_READING_SESSIONS
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.core.db.TABLE_WORK_MIGRATION_LEDGER
import org.skepsun.kototoro.core.db.TABLE_WORK_STATS
import kotlin.math.max
import kotlin.math.min

/**
 * Executes the full cutover and data migration from the Entity/Work system
 * back to the clean, projection-first (Manga-first) architecture in Migration 83 -> 84.
 *
 * It extracts, disambiguates, and restores:
 * 1. Favourites (work_favourites -> favourites) with deterministic conflict merging.
 * 2. History (work_history -> history) with chapter-ownership and reading-session disambiguation.
 * 3. Preferences (entity_preferences -> preferences) restoring title, cover, and status overrides.
 * 4. Stats (work_stats -> stats) reconnecting FK to manga instead of history.
 * 5. Tracks & Scrobblings rebuilding without owner_id and entity_id.
 * 6. TrackingSiteLinks rebuilding without entity_id.
 * 7. Physical deletion of all 8 Entity and Work tables.
 */
object ProjectionOwnershipMigrationResolver {

    // --- Pure Data Structures & Logic for Unit Testing ---

    data class RawWorkFavourite(
        val entityId: Long,
        val categoryId: Long,
        val anchorMangaId: Long?,
        val sortKey: Int,
        val isPinned: Boolean,
        val createdAt: Long,
        val deletedAt: Long,
        val updatedAt: Long,
    )

    data class ResolvedFavourite(
        val mangaId: Long,
        val categoryId: Long,
        val sortKey: Int,
        val isPinned: Boolean,
        val createdAt: Long,
        val deletedAt: Long,
        val updatedAt: Long,
    )

    data class RawWorkHistory(
        val entityId: Long,
        val anchorMangaId: Long,
        val createdAt: Long,
        val updatedAt: Long,
        val chapterId: Long,
        val page: Int,
        val scroll: Float,
        val percent: Float,
        val deletedAt: Long,
        val chaptersCount: Int,
        val parentChapterId: Long?,
    )

    data class ResolvedHistory(
        val mangaId: Long,
        val createdAt: Long,
        val updatedAt: Long,
        val chapterId: Long,
        val page: Int,
        val scroll: Float,
        val percent: Float,
        val deletedAt: Long,
        val chaptersCount: Int,
        val parentChapterId: Long?,
    )

    data class RawEntityPrefs(
        val entityId: Long,
        val preferredLocalMangaId: Long?,
        val titleOverride: String?,
        val coverUrlOverride: String?,
        val contentRatingOverride: String?,
        val readingStatus: String?,
        val metadataSourceKind: String?,
        val metadataSourceService: Int?,
        val metadataSourceRemoteId: Long?,
    )

    data class ResolvedPrefs(
        val mangaId: Long,
        val titleOverride: String?,
        val coverUrlOverride: String?,
        val contentRatingOverride: String?,
        val readingStatus: String?,
        val metadataSourceKind: String?,
        val metadataSourceService: Int?,
        val metadataSourceRemoteId: Long?,
    )

    /**
     * Resolves the true projection owner for a history row.
     * Prevents anchor=A + chapter=B mismatch where chapter B belongs to another projection.
     */
    fun resolveHistoryOwnerMangaId(
        history: RawWorkHistory,
        anchorOwnsChapter: Boolean,
        candidates: List<Long>,
        chapterOwnerLookup: (Long, Long) -> Boolean,
        epubOwnerLookup: ((List<Long>, Long) -> Long?)? = null,
        readingSessionLookup: ((List<Long>, Long) -> Long?)? = null,
    ): Long {
        // Priority 1: If anchor manga actually contains this chapter in chapters table, anchor is authentic.
        if (anchorOwnsChapter) {
            return history.anchorMangaId
        }

        // Priority 2: EPUB internal chapter mapping
        if (history.parentChapterId != null && history.parentChapterId != history.chapterId && epubOwnerLookup != null) {
            val epubOwner = epubOwnerLookup(candidates, history.chapterId)
            if (epubOwner != null) {
                return epubOwner
            }
        }

        // Priority 3: Check which candidate projection owns the chapterId
        val matchingCandidates = candidates.filter { candidateId ->
            chapterOwnerLookup(candidateId, history.chapterId)
        }
        if (matchingCandidates.size == 1) {
            return matchingCandidates.first()
        }
        if (matchingCandidates.size > 1) {
            // If multiple candidates have this chapterId, pick the anchor if among them, else first
            return if (history.anchorMangaId in matchingCandidates) history.anchorMangaId else matchingCandidates.first()
        }

        // Priority 4: Check recent reading sessions
        if (readingSessionLookup != null) {
            val sessionOwner = readingSessionLookup(candidates, history.chapterId)
            if (sessionOwner != null) {
                return sessionOwner
            }
        }

        // Priority 5: Fallback to anchor manga
        return history.anchorMangaId
    }

    /**
     * Deterministically merges two favourite records pointing to the same (manga_id, category_id).
     */
    fun mergeFavourites(a: ResolvedFavourite, b: ResolvedFavourite): ResolvedFavourite {
        val newer = if (a.updatedAt >= b.updatedAt) a else b
        val older = if (a.updatedAt < b.updatedAt) a else b

        // If either is active (deletedAt == 0), the active status wins unless the newer one was deleted later.
        val finalDeletedAt = if (newer.deletedAt != 0L && older.deletedAt == 0L && newer.updatedAt > older.updatedAt) {
            newer.deletedAt
        } else if (older.deletedAt == 0L) {
            0L
        } else {
            newer.deletedAt
        }

        return ResolvedFavourite(
            mangaId = a.mangaId,
            categoryId = a.categoryId,
            sortKey = newer.sortKey,
            isPinned = a.isPinned || b.isPinned,
            createdAt = min(a.createdAt, b.createdAt),
            deletedAt = finalDeletedAt,
            updatedAt = max(a.updatedAt, b.updatedAt),
        )
    }

    /**
     * Merges two history records for the same manga_id, keeping the newest reading progress.
     */
    fun mergeHistories(a: ResolvedHistory, b: ResolvedHistory): ResolvedHistory {
        val newer = if (a.updatedAt >= b.updatedAt) a else b
        val older = if (a.updatedAt < b.updatedAt) a else b
        return newer.copy(
            createdAt = min(older.createdAt, newer.createdAt),
            updatedAt = max(older.updatedAt, newer.updatedAt),
        )
    }

    // --- SQLite Database Execution ---

    /**
     * SQL expression resolving the projection owner of a v83 row aliased as [alias]: its own
     * `manga_id` when that manga still exists, otherwise the entity's best-ranked bound local
     * manga (same order as [getBoundMangaIds]). Evaluates to NULL when nothing resolves.
     */
    private fun ownerMangaIdSql(alias: String) = """
        CASE WHEN $alias.`manga_id` IN (SELECT `manga_id` FROM `$TABLE_MANGA`) THEN $alias.`manga_id` ELSE (
            SELECT CAST(b.`external_id` AS INTEGER)
            FROM `$TABLE_ENTITY_GRAPH_BINDING` b
            WHERE b.`entity_id` = $alias.`entity_id`
                AND b.`source` IN ('0', 'local_manga')
                AND CAST(b.`external_id` AS INTEGER) IN (SELECT `manga_id` FROM `$TABLE_MANGA`)
            ORDER BY b.`is_primary` DESC, b.`confidence` DESC, b.`updated_at` DESC
            LIMIT 1
        ) END
    """.trimIndent()

    fun migrate(db: SupportSQLiteDatabase) {
        migrateFavourites(db)
        migrateHistory(db)
        migrateEntityPreferences(db)
        migrateStats(db)
        migrateTracks(db)
        migrateTrackLogs(db)
        migrateScrobblings(db)
        migrateTrackingSiteLinks(db)
        dropEntityAndWorkTables(db)
    }

    private fun migrateFavourites(db: SupportSQLiteDatabase) {
        val rawFavourites = mutableListOf<RawWorkFavourite>()
        db.query(
            """
            SELECT entity_id, category_id, anchor_manga_id, sort_key, pinned, created_at, deleted_at, updated_at 
            FROM $TABLE_WORK_FAVOURITES
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rawFavourites.add(
                    RawWorkFavourite(
                        entityId = cursor.getLong(0),
                        categoryId = cursor.getLong(1),
                        anchorMangaId = if (cursor.isNull(2)) null else cursor.getLong(2),
                        sortKey = cursor.getInt(3),
                        isPinned = cursor.getInt(4) != 0,
                        createdAt = cursor.getLong(5),
                        deletedAt = cursor.getLong(6),
                        updatedAt = cursor.getLong(7),
                    ),
                )
            }
        }

        if (rawFavourites.isEmpty()) return

        // v83 may still hold legacy projection rows (e.g. a restore whose normalization never
        // ran). Seed with them so the newest record wins instead of being blindly replaced.
        val resolvedMap = mutableMapOf<Pair<Long, Long>, ResolvedFavourite>()
        db.query(
            "SELECT manga_id, category_id, sort_key, pinned, created_at, deleted_at, updated_at FROM $TABLE_FAVOURITES",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val legacy = ResolvedFavourite(
                    mangaId = cursor.getLong(0),
                    categoryId = cursor.getLong(1),
                    sortKey = cursor.getInt(2),
                    isPinned = cursor.getInt(3) != 0,
                    createdAt = cursor.getLong(4),
                    deletedAt = cursor.getLong(5),
                    updatedAt = cursor.getLong(6),
                )
                resolvedMap[legacy.mangaId to legacy.categoryId] = legacy
            }
        }

        for (raw in rawFavourites) {
            val resolvedMangaId = resolveMangaIdForEntity(
                db = db,
                entityId = raw.entityId,
                preferredMangaId = raw.anchorMangaId,
            ) ?: continue

            val resolved = ResolvedFavourite(
                mangaId = resolvedMangaId,
                categoryId = raw.categoryId,
                sortKey = raw.sortKey,
                isPinned = raw.isPinned,
                createdAt = raw.createdAt,
                deletedAt = raw.deletedAt,
                updatedAt = raw.updatedAt,
            )

            val key = Pair(resolvedMangaId, raw.categoryId)
            val existing = resolvedMap[key]
            if (existing != null) {
                resolvedMap[key] = mergeFavourites(existing, resolved)
            } else {
                resolvedMap[key] = resolved
            }
        }

        for (fav in resolvedMap.values) {
            db.execSQL(
                """
                INSERT OR REPLACE INTO $TABLE_FAVOURITES (
                    manga_id, category_id, sort_key, pinned, created_at, deleted_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    fav.mangaId,
                    fav.categoryId,
                    fav.sortKey,
                    if (fav.isPinned) 1 else 0,
                    fav.createdAt,
                    fav.deletedAt,
                    fav.updatedAt,
                ),
            )
        }
    }

    private fun migrateHistory(db: SupportSQLiteDatabase) {
        val rawHistories = mutableListOf<RawWorkHistory>()
        db.query(
            """
            SELECT entity_id, anchor_manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters, parent_chapter_id
            FROM $TABLE_WORK_HISTORY
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rawHistories.add(
                    RawWorkHistory(
                        entityId = cursor.getLong(0),
                        anchorMangaId = cursor.getLong(1),
                        createdAt = cursor.getLong(2),
                        updatedAt = cursor.getLong(3),
                        chapterId = cursor.getLong(4),
                        page = cursor.getInt(5),
                        scroll = cursor.getFloat(6),
                        percent = cursor.getFloat(7),
                        deletedAt = cursor.getLong(8),
                        chaptersCount = cursor.getInt(9),
                        parentChapterId = if (cursor.isNull(10)) null else cursor.getLong(10),
                    ),
                )
            }
        }

        if (rawHistories.isEmpty()) return

        // Seed with legacy projection rows so the newest progress wins (see migrateFavourites).
        val resolvedMap = mutableMapOf<Long, ResolvedHistory>()
        db.query(
            """
            SELECT manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters, parent_chapter_id
            FROM $TABLE_HISTORY
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val legacy = ResolvedHistory(
                    mangaId = cursor.getLong(0),
                    createdAt = cursor.getLong(1),
                    updatedAt = cursor.getLong(2),
                    chapterId = cursor.getLong(3),
                    page = cursor.getInt(4),
                    scroll = cursor.getFloat(5),
                    percent = cursor.getFloat(6),
                    deletedAt = cursor.getLong(7),
                    chaptersCount = cursor.getInt(8),
                    parentChapterId = if (cursor.isNull(9)) null else cursor.getLong(9),
                )
                resolvedMap[legacy.mangaId] = legacy
            }
        }

        for (raw in rawHistories) {
            val anchorOwns = db.query(
                "SELECT 1 FROM $TABLE_CHAPTERS WHERE manga_id = ? AND chapter_id = ? LIMIT 1",
                arrayOf(raw.anchorMangaId, raw.chapterId),
            ).use { it.moveToFirst() }

            val candidates = getBoundMangaIds(db, raw.entityId)

            val trueOwnerMangaId = resolveHistoryOwnerMangaId(
                history = raw,
                anchorOwnsChapter = anchorOwns,
                candidates = candidates,
                chapterOwnerLookup = { mId, cId ->
                    db.query(
                        "SELECT 1 FROM $TABLE_CHAPTERS WHERE manga_id = ? AND chapter_id = ? LIMIT 1",
                        arrayOf(mId, cId),
                    ).use { it.moveToFirst() }
                },
                epubOwnerLookup = { candidateList, cId ->
                    findEpubOwnerMangaId(db, candidateList, cId, raw.parentChapterId)
                },
                readingSessionLookup = { candidateList, cId ->
                    findReadingSessionOwnerMangaId(db, candidateList, cId)
                },
            )

            // Ensure the target manga exists in manga table before inserting into history
            if (!mangaExists(db, trueOwnerMangaId)) continue

            val resolved = ResolvedHistory(
                mangaId = trueOwnerMangaId,
                createdAt = raw.createdAt,
                updatedAt = raw.updatedAt,
                chapterId = raw.chapterId,
                page = raw.page,
                scroll = raw.scroll,
                percent = raw.percent,
                deletedAt = raw.deletedAt,
                chaptersCount = raw.chaptersCount,
                parentChapterId = raw.parentChapterId,
            )

            val existing = resolvedMap[trueOwnerMangaId]
            if (existing != null) {
                resolvedMap[trueOwnerMangaId] = mergeHistories(existing, resolved)
            } else {
                resolvedMap[trueOwnerMangaId] = resolved
            }
        }

        for (history in resolvedMap.values) {
            db.execSQL(
                """
                INSERT OR REPLACE INTO $TABLE_HISTORY (
                    manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters, parent_chapter_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    history.mangaId,
                    history.createdAt,
                    history.updatedAt,
                    history.chapterId,
                    history.page,
                    history.scroll,
                    history.percent,
                    history.deletedAt,
                    history.chaptersCount,
                    history.parentChapterId,
                ),
            )
        }
    }

    private fun migrateEntityPreferences(db: SupportSQLiteDatabase) {
        val rawPrefs = mutableListOf<RawEntityPrefs>()
        db.query(
            """
            SELECT entity_id, preferred_local_manga_id, title_override, cover_override, content_rating_override,
                   reading_status, metadata_source_kind, metadata_source_service, metadata_source_remote_id
            FROM $TABLE_ENTITY_PREFERENCES
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rawPrefs.add(
                    RawEntityPrefs(
                        entityId = cursor.getLong(0),
                        preferredLocalMangaId = if (cursor.isNull(1)) null else cursor.getLong(1),
                        titleOverride = cursor.getStringOrNull(2),
                        coverUrlOverride = cursor.getStringOrNull(3),
                        contentRatingOverride = cursor.getStringOrNull(4),
                        readingStatus = cursor.getStringOrNull(5),
                        metadataSourceKind = cursor.getStringOrNull(6),
                        metadataSourceService = if (cursor.isNull(7)) null else cursor.getInt(7),
                        metadataSourceRemoteId = if (cursor.isNull(8)) null else cursor.getLong(8),
                    ),
                )
            }
        }

        for (pref in rawPrefs) {
            // Only migrate if at least one meaningful override exists
            val hasOverrides = pref.titleOverride != null ||
                pref.coverUrlOverride != null ||
                pref.contentRatingOverride != null ||
                pref.readingStatus != null ||
                pref.metadataSourceKind != null ||
                pref.metadataSourceService != null ||
                pref.metadataSourceRemoteId != null

            if (!hasOverrides) continue

            val targetMangaId = resolveMangaIdForEntity(
                db = db,
                entityId = pref.entityId,
                preferredMangaId = pref.preferredLocalMangaId,
            ) ?: continue

            val exists = db.query(
                "SELECT 1 FROM $TABLE_PREFERENCES WHERE manga_id = ?",
                arrayOf(targetMangaId),
            ).use { it.moveToFirst() }

            if (exists) {
                db.execSQL(
                    """
                    UPDATE $TABLE_PREFERENCES SET
                        title_override = COALESCE(?, title_override),
                        cover_override = COALESCE(?, cover_override),
                        content_rating_override = COALESCE(?, content_rating_override),
                        reading_status = COALESCE(?, reading_status),
                        metadata_source_kind = COALESCE(?, metadata_source_kind),
                        metadata_source_service = COALESCE(?, metadata_source_service),
                        metadata_source_remote_id = COALESCE(?, metadata_source_remote_id)
                    WHERE manga_id = ?
                    """.trimIndent(),
                    arrayOf<Any?>(
                        pref.titleOverride,
                        pref.coverUrlOverride,
                        pref.contentRatingOverride,
                        pref.readingStatus,
                        pref.metadataSourceKind,
                        pref.metadataSourceService,
                        pref.metadataSourceRemoteId,
                        targetMangaId,
                    ),
                )
            } else {
                db.execSQL(
                    """
                    INSERT INTO $TABLE_PREFERENCES (
                        manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale, cf_book,
                        title_override, cover_override, content_rating_override,
                        metadata_source_kind, metadata_source_service, metadata_source_remote_id,
                        reading_status, ignored_tracking_suggestion_service, ignored_tracking_suggestion_remote_id
                    ) VALUES (?, 0, 0.0, 0.0, 0, 0, 0, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        targetMangaId,
                        pref.titleOverride,
                        pref.coverUrlOverride,
                        pref.contentRatingOverride,
                        pref.metadataSourceKind,
                        pref.metadataSourceService,
                        pref.metadataSourceRemoteId,
                        pref.readingStatus,
                    ),
                )
            }
        }
    }

    private fun migrateStats(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `stats_new` (
                `manga_id` INTEGER NOT NULL,
                `started_at` INTEGER NOT NULL,
                `duration` INTEGER NOT NULL,
                `pages` INTEGER NOT NULL,
                PRIMARY KEY(`manga_id`, `started_at`),
                FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT OR REPLACE INTO `stats_new` (`manga_id`, `started_at`, `duration`, `pages`)
            SELECT `manga_id`, `started_at`, MAX(`duration`), MAX(`pages`)
            FROM (
                SELECT `anchor_manga_id` AS `manga_id`, `started_at`, `duration`, `pages` FROM `$TABLE_WORK_STATS`
                UNION ALL
                SELECT `manga_id`, `started_at`, `duration`, `pages` FROM `stats`
            )
            WHERE `manga_id` IS NOT NULL AND `manga_id` IN (SELECT `manga_id` FROM `$TABLE_MANGA`)
            GROUP BY `manga_id`, `started_at`
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE IF EXISTS `stats`")
        db.execSQL("ALTER TABLE `stats_new` RENAME TO `stats`")
    }

    private fun migrateTracks(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tracks_new` (
                `manga_id` INTEGER NOT NULL,
                `last_chapter_id` INTEGER NOT NULL,
                `chapters_new` INTEGER NOT NULL,
                `last_check_time` INTEGER NOT NULL,
                `last_chapter_date` INTEGER NOT NULL,
                `last_result` INTEGER NOT NULL,
                `last_error` TEXT,
                PRIMARY KEY(`manga_id`),
                FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT OR REPLACE INTO `tracks_new` (
                `manga_id`, `last_chapter_id`, `chapters_new`, `last_check_time`, `last_chapter_date`, `last_result`, `last_error`
            )
            SELECT resolved_manga_id, `last_chapter_id`, `chapters_new`, `last_check_time`, `last_chapter_date`,
                `last_result`, `last_error`
            FROM (
                SELECT ${ownerMangaIdSql("t")} AS resolved_manga_id, t.*
                FROM `tracks` t
            )
            WHERE resolved_manga_id IS NOT NULL
            -- Several v83 owners can collapse onto one manga; INSERT OR REPLACE keeps the last
            -- row, so order oldest-first to keep the most recently checked track.
            ORDER BY `last_check_time` ASC, `last_chapter_date` ASC
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE IF EXISTS `tracks`")
        db.execSQL("ALTER TABLE `tracks_new` RENAME TO `tracks`")
    }

    private fun migrateTrackLogs(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `track_logs_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `manga_id` INTEGER NOT NULL,
                `chapters` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `unread` INTEGER NOT NULL,
                FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT OR REPLACE INTO `track_logs_new` (`id`, `manga_id`, `chapters`, `created_at`, `unread`)
            SELECT `id`, resolved_manga_id, `chapters`, `created_at`, `unread`
            FROM (
                SELECT ${ownerMangaIdSql("l")} AS resolved_manga_id, l.*
                FROM `track_logs` l
            )
            WHERE resolved_manga_id IS NOT NULL
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE IF EXISTS `track_logs`")
        db.execSQL("ALTER TABLE `track_logs_new` RENAME TO `track_logs`")
        // Index names are schema-global and v83 already has some of these on the old table, so
        // they can only be (re)created once the old table and its indices are gone.
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_logs_manga_id` ON `track_logs` (`manga_id`)")
    }

    private fun migrateScrobblings(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scrobblings_new` (
                `scrobbler` INTEGER NOT NULL,
                `id` INTEGER NOT NULL,
                `manga_id` INTEGER NOT NULL,
                `target_id` INTEGER NOT NULL,
                `status` TEXT,
                `chapter` INTEGER NOT NULL,
                `comment` TEXT,
                `rating` REAL NOT NULL,
                `media_type` TEXT NOT NULL,
                `remote_title` TEXT,
                `remote_cover_url` TEXT,
                `remote_url` TEXT,
                PRIMARY KEY(`scrobbler`, `id`, `manga_id`, `media_type`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT OR REPLACE INTO `scrobblings_new` (
                `scrobbler`, `id`, `manga_id`, `target_id`, `status`, `chapter`, `comment`, `rating`,
                `media_type`, `remote_title`, `remote_cover_url`, `remote_url`
            )
            SELECT `scrobbler`, `id`, resolved_manga_id, `target_id`, `status`, `chapter`, `comment`, `rating`,
                `media_type`, `remote_title`, `remote_cover_url`, `remote_url`
            FROM (
                SELECT ${ownerMangaIdSql("s")} AS resolved_manga_id, s.*
                FROM `scrobblings` s
            )
            WHERE resolved_manga_id IS NOT NULL
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE IF EXISTS `scrobblings`")
        db.execSQL("ALTER TABLE `scrobblings_new` RENAME TO `scrobblings`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scrobblings_manga_id` ON `scrobblings` (`manga_id`)")
    }

    private fun migrateTrackingSiteLinks(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tracking_site_links_new` (
                `service` INTEGER NOT NULL,
                `remote_id` INTEGER NOT NULL,
                `manga_id` INTEGER NOT NULL,
                `source_name` TEXT,
                `confidence` REAL NOT NULL,
                `is_manual` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`service`, `remote_id`, `manga_id`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT OR REPLACE INTO `tracking_site_links_new` (
                `service`, `remote_id`, `manga_id`, `source_name`, `confidence`, `is_manual`, `created_at`, `updated_at`
            )
            SELECT `service`, `remote_id`, resolved_manga_id, `source_name`, `confidence`, `is_manual`, `created_at`, `updated_at`
            FROM (
                SELECT ${ownerMangaIdSql("k")} AS resolved_manga_id, k.*
                FROM `tracking_site_links` k
            )
            WHERE resolved_manga_id IS NOT NULL
            -- Manual confirmations must survive an owner collapse: apply them last.
            ORDER BY `is_manual` ASC, `updated_at` ASC
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE IF EXISTS `tracking_site_links`")
        db.execSQL("ALTER TABLE `tracking_site_links_new` RENAME TO `tracking_site_links`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracking_site_links_manga_id` ON `tracking_site_links` (`manga_id`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tracking_site_links_service_remote_id` " +
                "ON `tracking_site_links` (`service`, `remote_id`)",
        )
    }

    private fun dropEntityAndWorkTables(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `$TABLE_WORK_FAVOURITES`")
        db.execSQL("DROP TABLE IF EXISTS `$TABLE_WORK_HISTORY`")
        db.execSQL("DROP TABLE IF EXISTS `$TABLE_WORK_STATS`")
        db.execSQL("DROP TABLE IF EXISTS `$TABLE_ENTITY_PREFERENCES`")
        db.execSQL("DROP TABLE IF EXISTS `$TABLE_ENTITY_GRAPH_RELATION`")
        db.execSQL("DROP TABLE IF EXISTS `$TABLE_ENTITY_GRAPH_BINDING`")
        db.execSQL("DROP TABLE IF EXISTS `$TABLE_ENTITY_GRAPH_ENTITY`")
        db.execSQL("DROP TABLE IF EXISTS `$TABLE_WORK_MIGRATION_LEDGER`")
    }

    // --- Helpers ---

    private fun resolveMangaIdForEntity(
        db: SupportSQLiteDatabase,
        entityId: Long,
        preferredMangaId: Long?,
    ): Long? {
        if (preferredMangaId != null && mangaExists(db, preferredMangaId)) {
            return preferredMangaId
        }

        // Try entity_preferences preferredLocalMangaId
        db.query(
            "SELECT preferred_local_manga_id FROM $TABLE_ENTITY_PREFERENCES WHERE entity_id = ? LIMIT 1",
            arrayOf(entityId),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                val pId = cursor.getLong(0)
                if (mangaExists(db, pId)) return pId
            }
        }

        // Try bound local manga
        val boundIds = getBoundMangaIds(db, entityId)
        for (mId in boundIds) {
            if (mangaExists(db, mId)) return mId
        }

        return null
    }

    private fun getBoundMangaIds(db: SupportSQLiteDatabase, entityId: Long): List<Long> {
        val list = mutableListOf<Long>()
        db.query(
            """
            SELECT CAST(external_id AS INTEGER) 
            FROM $TABLE_ENTITY_GRAPH_BINDING 
            WHERE entity_id = ? AND source IN ('0', 'local_manga') 
            ORDER BY is_primary DESC, confidence DESC, updated_at DESC
            """.trimIndent(),
            arrayOf(entityId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                if (id != 0L) list.add(id)
            }
        }
        return list
    }

    private fun mangaExists(db: SupportSQLiteDatabase, mangaId: Long): Boolean {
        return db.query(
            "SELECT 1 FROM $TABLE_MANGA WHERE manga_id = ? LIMIT 1",
            arrayOf(mangaId),
        ).use { it.moveToFirst() }
    }

    /**
     * `epub_chapter_mapping` has no manga column: it maps an internal EPUB chapter to its
     * parent chapter. The owner is the candidate whose `chapters` table holds that parent.
     */
    private fun findEpubOwnerMangaId(
        db: SupportSQLiteDatabase,
        candidates: List<Long>,
        internalChapterId: Long,
        fallbackParentChapterId: Long?,
    ): Long? {
        if (candidates.isEmpty()) return null
        val parentChapterId = db.query(
            "SELECT parentChapterId FROM epub_chapter_mapping WHERE internalChapterId = ? LIMIT 1",
            arrayOf<Any?>(internalChapterId),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        } ?: fallbackParentChapterId ?: return null
        val inClause = candidates.joinToString(",") { "?" }
        val args = (candidates + parentChapterId).toTypedArray<Any?>()
        return db.query(
            "SELECT manga_id FROM $TABLE_CHAPTERS WHERE manga_id IN ($inClause) AND chapter_id = ? LIMIT 1",
            args,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun findReadingSessionOwnerMangaId(
        db: SupportSQLiteDatabase,
        candidates: List<Long>,
        chapterId: Long,
    ): Long? {
        if (candidates.isEmpty()) return null
        val inClause = candidates.joinToString(",") { "?" }
        val args = (candidates.map { it.toString() } + chapterId.toString() + chapterId.toString()).toTypedArray()
        return db.query(
            """
            SELECT manga_id FROM $TABLE_READING_SESSIONS 
            WHERE manga_id IN ($inClause) AND (start_chapter_id = ? OR end_chapter_id = ?) 
            ORDER BY end_at DESC LIMIT 1
            """.trimIndent(),
            args,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? {
        return if (isNull(index)) null else getString(index)
    }
}
