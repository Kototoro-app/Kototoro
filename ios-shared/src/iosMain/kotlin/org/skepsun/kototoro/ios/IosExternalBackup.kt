package org.skepsun.kototoro.ios

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.Buffer
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.backups.external.AniyomiBackup
import org.skepsun.kototoro.backups.external.ExternalBackupApp
import org.skepsun.kototoro.backups.external.ExternalBackupPayload
import org.skepsun.kototoro.backups.external.ExternalImportTransaction
import org.skepsun.kototoro.backups.external.ExternalLibraryImport
import org.skepsun.kototoro.backups.external.ExternalSourceCandidate
import org.skepsun.kototoro.backups.external.ExternalSourceKind
import org.skepsun.kototoro.backups.external.VeneraBackupDecoding
import org.skepsun.kototoro.backups.external.VeneraDatabase
import org.skepsun.kototoro.backups.external.decodeMihonOrAniyomiBackup
import org.skepsun.kototoro.backups.external.readExternalBackupBytes
import kotlin.random.Random

/** Another app's backup, decoded and counted; nothing is written until [IosLibrary.importExternalBackup]. */
class IosExternalBackupPreview internal constructor(
    internal val payload: ExternalBackupPayload,
    /** Mihon, Aniyomi or Venera. */
    val app: String,
    val filename: String,
) {
    val favourites: Int = payload.records.count { it.isFavorite }
    val history: Int = payload.records.count { it.historyTimestamp != null && !it.historyChapterUrl.isNullOrBlank() }
    val categories: Int = payload.favoriteCategories.size
    val sources: Int = payload.records.map { it.sourceName }.distinct().size
}

class IosExternalImportResult internal constructor(
    val favourites: Int,
    val history: Int,
    /** Works whose source could not be matched (Venera); they were not imported. */
    val failedTitles: List<String>,
    /** Kototoro sources a Venera source maps to but which are not installed. */
    val missingSources: List<String>,
    /** Extensions whose works were imported but which are not installed yet, with their record counts. */
    val uninstalledSources: List<String>,
)

internal class IosExternalBackupService(private val library: IosLibrary) {

    /** True for Kototoro / Kotatsu archives, which go through the regular restore. */
    fun isKototoroBackup(path: String): Boolean = try {
        BackupZipReader.parse(readFile(path, BackupZipReader.MAX_ARCHIVE_BYTES), strict = false).entries
            .any { BackupSection.of(it.name) == BackupSection.INDEX }
    } catch (_: IllegalArgumentException) { false }

    suspend fun preview(path: String): IosExternalBackupPreview = withContext(Dispatchers.IO) {
        val raw = readFile(path, BackupZipReader.MAX_ARCHIVE_BYTES)
        val filename = path.substringAfterLast('/')
        val bytes = readExternalBackupBytes(Buffer().write(raw))
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            IosExternalBackupPreview(decodeVenera(bytes), "Venera", filename)
        } else {
            // Aniyomi writes its anime under its own fields; a backup without them is a Mihon-family one.
            val aniyomi = runCatching { ProtoBuf.decodeFromByteArray(AniyomiBackup.serializer(), bytes) }.getOrNull()
            val app = if (aniyomi != null && (aniyomi.backupAnime.isNotEmpty() || aniyomi.backupAnimeSources.isNotEmpty()))
                ExternalBackupApp.ANIYOMI else ExternalBackupApp.MIHON
            val payload = decodeMihonOrAniyomiBackup(bytes, app)
                ?: throw IllegalArgumentException(localized("无法识别的备份文件：支持 Kototoro/Kotatsu、Mihon/Tachiyomi、Aniyomi 和 Venera 备份"))
            IosExternalBackupPreview(payload, if (app == ExternalBackupApp.ANIYOMI) "Aniyomi" else "Mihon", filename)
        }
    }

    suspend fun import(preview: IosExternalBackupPreview, sources: List<IosSource>): IosExternalImportResult =
        withContext(Dispatchers.IO) {
            val database = library.database
            val summary = ExternalLibraryImport(
                database = database,
                catalog = { sources.flatMap(::candidates) },
                transaction = object : ExternalImportTransaction {
                    override suspend fun <R> run(block: suspend () -> R): R =
                        database.useWriterConnection { connection -> connection.immediateTransaction { block() } }
                },
                defaultCategoryTitle = localized("收藏"),
                isNsfwSource = { name -> sources.firstOrNull { it.name == name }?.isAdult == true },
            ).import(preview.payload)
            IosExternalImportResult(
                favourites = summary.favoritesImported,
                history = summary.historyImported,
                failedTitles = summary.failedTitles,
                missingSources = summary.missingSourceNames,
                uninstalledSources = summary.uninstalledSources.map { source ->
                    "${source.displayName ?: source.sourceKey}（${source.recordCount}）"
                },
            )
        }

    private fun candidates(source: IosSource): List<ExternalSourceCandidate> {
        val kind = when (source.ecosystem) {
            "MIHON", "TSUNDOKU" -> ExternalSourceKind.MIHON
            "ANIYOMI" -> ExternalSourceKind.ANIYOMI
            "CMS" -> ExternalSourceKind.JSON
            else -> ExternalSourceKind.NATIVE
        }
        return listOf(ExternalSourceCandidate(source.name, source.name, kind),
            ExternalSourceCandidate(source.name, source.title, kind))
    }

    /** Venera's databases are copied out of the ZIP and read with the bundled SQLite, then removed again. */
    private fun decodeVenera(bytes: ByteArray): ExternalBackupPayload {
        val zip = BackupZipReader.parse(bytes, strict = false)
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "venera-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(directory)
        val connections = mutableMapOf<String, SQLiteConnection>()
        try {
            for (entry in zip.entries) {
                val name = VeneraBackupDecoding.databaseFileOf(entry.name) ?: continue
                if (name in connections) continue
                val file = directory / name
                FileSystem.SYSTEM.write(file) { write(zip.read(entry)) }
                connections[name] = BundledSQLiteDriver().open(file.toString(), SQLITE_OPEN_READONLY)
            }
            if (connections.isEmpty()) throw IllegalArgumentException(localized("所选 ZIP 既不是 Kototoro 备份，也不是 Venera 备份"))
            return VeneraBackupDecoding.decode(connections[VeneraBackupDecoding.FAVORITES_FILE]?.let(::SqliteVenera),
                connections[VeneraBackupDecoding.HISTORY_FILE]?.let(::SqliteVenera))
        } finally {
            connections.values.forEach { runCatching { it.close() } }
            runCatching { FileSystem.SYSTEM.deleteRecursively(directory) }
        }
    }

    private class SqliteVenera(private val connection: SQLiteConnection) : VeneraDatabase {
        override fun query(sql: String, args: List<String>): List<Map<String, Any?>> =
            connection.prepare(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.bindText(index + 1, value) }
                buildList {
                    while (statement.step()) {
                        add((0 until statement.getColumnCount()).associate { column ->
                            statement.getColumnName(column) to when (statement.getColumnType(column)) {
                                SQLITE_DATA_INTEGER -> statement.getLong(column)
                                SQLITE_DATA_FLOAT -> statement.getDouble(column)
                                SQLITE_DATA_TEXT -> statement.getText(column)
                                SQLITE_DATA_BLOB -> statement.getBlob(column)
                                else -> null
                            }
                        })
                    }
                }
            }
    }
}
