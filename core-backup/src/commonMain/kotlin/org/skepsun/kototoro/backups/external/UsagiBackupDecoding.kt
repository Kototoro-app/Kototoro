package org.skepsun.kototoro.backups.external

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.skepsun.kototoro.parsers.model.ContentType
import kotlin.math.max

/** Upper bound for one decompressed Usagi JSON section, matching #747's per-entry archive limit (64 MiB). */
const val DEFAULT_MAX_USAGI_BACKUP_ENTRY_BYTES: Long = 64L * 1024 * 1024

/** `app_id` a Usagi backup's `index` section carries. */
const val USAGI_BACKUP_APP_ID: String = "org.draken.usagi"

/**
 * Usagi writes `app_id = BuildConfig.APPLICATION_ID`, so a debug build (`applicationIdSuffix = ".debug"`)
 * or a fork with its own application id still produces a Usagi backup. The dot is required so that a
 * look-alike id (`org.draken.usagix`) is not accepted.
 *
 * This is the single place the archive's identity is decided: Android's decoder and the iOS pre-check
 * both call it. `"org.draken.usagi."` (trailing dot, nothing after it) is accepted on purpose — the
 * rule is the literal prefix, not a well-formed package name.
 */
fun isUsagiBackupAppId(appId: String?): Boolean =
    appId != null && (appId == USAGI_BACKUP_APP_ID || appId.startsWith("$USAGI_BACKUP_APP_ID."))

/**
 * The `app_id` carried by an `index` section, or null when the text is not Usagi's index shape or is
 * not JSON at all. Lenient on purpose: the iOS pre-check must treat an unreadable index as "not a Usagi
 * backup" and fall through to the other formats instead of failing. Pair it with [isUsagiBackupAppId].
 */
fun parseUsagiBackupAppId(indexJson: String): String? = try {
    val array = usagiJson.parseToJsonElement(indexJson) as? JsonArray
    val index = array?.firstOrNull() as? JsonObject
    (index?.get("app_id") as? JsonPrimitive)?.content
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** The ZIP sections the import reads, exactly as `UsagiBackupExportRepository` names them. */
val USAGI_BACKUP_IMPORTED_ENTRIES: Set<String> = setOf("index", "categories", "favourites", "history")

/** Bookmark/scrobbling/statistic sections Usagi also writes but this import ignores. */
val USAGI_BACKUP_IGNORED_ENTRIES: Set<String> = setOf("bookmarks", "sources", "scrobbling", "statistics")

/**
 * The raw, already-decompressed JSON text of a Usagi ZIP's sections. The platform layer unzips
 * (it owns `java.util.zip` / Kotlin/Native zlib) and passes the text here, so this stays pure logic.
 */
data class UsagiRawBackupEntries(
    val indexJson: String? = null,
    val categoriesJson: String? = null,
    val favouritesJson: String? = null,
    val historyJson: String? = null,
)

/** The section a ZIP entry name stands for, by file name (Usagi writes flat names, folders are tolerated). */
fun usagiBackupEntryName(entryName: String): String? =
    entryName.substringAfterLast('/').takeIf { it in USAGI_BACKUP_IMPORTED_ENTRIES }

/**
 * Convenience for platform decoders: [sections] maps a ZIP entry name to its JSON text; anything
 * that is not one of [USAGI_BACKUP_IMPORTED_ENTRIES] is ignored.
 */
fun decodeUsagiBackupSections(
    sections: Map<String, String>,
    maxEntryBytes: Long = DEFAULT_MAX_USAGI_BACKUP_ENTRY_BYTES,
): ExternalBackupPayload = decodeUsagiBackupEntries(
    UsagiRawBackupEntries(
        indexJson = sections["index"],
        categoriesJson = sections["categories"],
        favouritesJson = sections["favourites"],
        historyJson = sections["history"],
    ),
    maxEntryBytes = maxEntryBytes,
)

/**
 * Turns a Usagi backup into what [ExternalLibraryImport] consumes: favourite categories, and one
 * record per `mangaId` merging its `favourites` and `history` rows.
 *
 * Only categories, favourites and history are imported; bookmarks, sources, scrobbling and
 * statistics are dropped (see the PR for why).
 *
 * Fails fast instead of returning an empty payload: a missing `index`, an `index` that is not
 * Usagi's, malformed JSON, or a section over [maxEntryBytes] each throw a distinct exception.
 */
fun decodeUsagiBackupEntries(
    entries: UsagiRawBackupEntries,
    maxEntryBytes: Long = DEFAULT_MAX_USAGI_BACKUP_ENTRY_BYTES,
): ExternalBackupPayload {
    val indexJson = entries.indexJson
        ?: throw UnsupportedExternalBackupException("Not a Usagi backup: the archive has no index section")
    indexJson.requireUsagiSectionSize("index", maxEntryBytes)
    entries.categoriesJson?.requireUsagiSectionSize("categories", maxEntryBytes)
    entries.favouritesJson?.requireUsagiSectionSize("favourites", maxEntryBytes)
    entries.historyJson?.requireUsagiSectionSize("history", maxEntryBytes)

    requireUsagiIndex(indexJson)

    val categories = decodeUsagiArray(entries.categoriesJson, "categories", UsagiCategoryBackup.serializer())
    val favourites = decodeUsagiArray(entries.favouritesJson, "favourites", UsagiFavouriteBackup.serializer())
    val history = decodeUsagiArray(entries.historyJson, "history", UsagiHistoryBackup.serializer())

    return ExternalBackupPayload(
        records = mergeByMangaId(favourites, history),
        favoriteCategories = categories.map { category ->
            ExternalBackupFavoriteCategoryRecord(
                name = category.title,
                // Usagi favourites point at the category's own id, so the id doubles as the order key.
                order = category.categoryId.toLong(),
                id = category.categoryId.toLong(),
            )
        },
    )
}

private val usagiJson = Json {
    allowSpecialFloatingPointValues = true
    coerceInputValues = true
    ignoreUnknownKeys = true
}

private fun String.requireUsagiSectionSize(name: String, maxEntryBytes: Long) {
    val size = encodeToByteArray().size.toLong()
    if (size > maxEntryBytes) {
        throw ExternalBackupTooLargeException(
            "Usagi backup section '$name' is $size bytes (limit $maxEntryBytes bytes)",
        )
    }
}

/** The `index` section identifies the archive: a single-element array carrying Usagi's `app_id`. */
private fun requireUsagiIndex(indexJson: String) {
    val array = try {
        usagiJson.parseToJsonElement(indexJson) as? JsonArray
    } catch (e: SerializationException) {
        throw ExternalBackupCorruptException("Malformed Usagi index section: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw ExternalBackupCorruptException("Malformed Usagi index section: ${e.message}", e)
    } ?: throw ExternalBackupCorruptException("Malformed Usagi index section: expected a JSON array")
    val index = array.firstOrNull() as? JsonObject
        ?: throw UnsupportedExternalBackupException("Not a Usagi backup: the index section is empty")
    val appId = (index["app_id"] as? JsonPrimitive)?.content
    if (!isUsagiBackupAppId(appId)) {
        throw UnsupportedExternalBackupException("Not a Usagi backup: index app_id is ${appId ?: "missing"}")
    }
}

private fun <T> decodeUsagiArray(json: String?, name: String, serializer: KSerializer<T>): List<T> {
    if (json == null) return emptyList()
    return try {
        usagiJson.decodeFromString(ListSerializer(serializer), json)
    } catch (e: SerializationException) {
        throw ExternalBackupCorruptException("Malformed Usagi '$name' section: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw ExternalBackupCorruptException("Malformed Usagi '$name' section: ${e.message}", e)
    }
}

/**
 * One record per `mangaId`: the favourite rows decide the library entry and its categories, the
 * newest history row supplies progress. Duplicates across sections are merged, never dropped.
 */
private fun mergeByMangaId(
    favourites: List<UsagiFavouriteBackup>,
    history: List<UsagiHistoryBackup>,
): List<ExternalBackupContentRecord> {
    val merged = LinkedHashMap<Long, UsagiMergedRecord>()
    favourites.forEach { favourite ->
        val record = merged.getOrPut(favourite.mangaId) { UsagiMergedRecord(favourite.manga) }
        record.isFavorite = true
        record.favoriteTimestamp = record.favoriteTimestamp
            ?.let { minOf(it, favourite.createdAt) }
            ?: favourite.createdAt
        record.categoryIds = (record.categoryIds + favourite.categoryId.toLong()).distinct()
    }
    history.forEach { entry ->
        val record = merged.getOrPut(entry.mangaId) { UsagiMergedRecord(entry.manga) }
        if (entry.updatedAt >= (record.historyTimestamp ?: Long.MIN_VALUE)) {
            record.historyTimestamp = entry.updatedAt
            record.historyChapterUrl = "usagi:${entry.chapterId}"
            record.historyPage = max(entry.page, 0)
            record.progressPercent = entry.percent
            record.chaptersCount = max(record.chaptersCount, entry.chaptersCount)
        }
    }
    return merged.values.map(UsagiMergedRecord::toContentRecord)
}

private class UsagiMergedRecord(private val manga: UsagiMangaBackup) {

    var isFavorite: Boolean = false
    var favoriteTimestamp: Long? = null
    var categoryIds: List<Long> = emptyList()
    var historyTimestamp: Long? = null
    var historyChapterUrl: String? = null
    var historyPage: Int = 0
    var progressPercent: Float? = null
    var chaptersCount: Int = 0

    fun toContentRecord(): ExternalBackupContentRecord {
        val url = manga.url
        return ExternalBackupContentRecord(
            app = ExternalBackupApp.USAGI,
            // Usagi stores Kototoro's own source names, so records bind to them directly.
            sourceName = manga.source,
            contentType = ContentType.MANGA,
            url = url,
            title = manga.title,
            authors = manga.authors?.takeIf { it.isNotBlank() },
            description = null,
            tags = manga.tags.mapNotNull { it.title.takeIf(String::isNotBlank) }.distinct(),
            coverUrl = manga.coverUrl.takeIf { it.isNotBlank() } ?: manga.largeCoverUrl,
            publicUrl = manga.publicUrl.takeIf { it.isNotBlank() } ?: url,
            state = manga.state?.takeIf { it.isNotBlank() },
            isFavorite = isFavorite,
            favoriteTimestamp = favoriteTimestamp,
            favoriteCategoryOrders = categoryIds,
            chaptersCount = chaptersCount,
            readEntriesCount = 0,
            progressPercent = progressPercent,
            historyChapterUrl = historyChapterUrl.takeIf { historyTimestamp != null },
            historyTimestamp = historyTimestamp,
            historyPage = historyPage,
        )
    }
}
