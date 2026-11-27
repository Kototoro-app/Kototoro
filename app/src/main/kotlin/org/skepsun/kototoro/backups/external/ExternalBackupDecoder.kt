package org.skepsun.kototoro.backups.external

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalBackupDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun decode(uri: Uri, app: ExternalBackupApp): ExternalBackupPayload {
        return when (app) {
            ExternalBackupApp.MIHON, ExternalBackupApp.ANIYOMI -> openBackupSource(uri) { source ->
                decodeMihonOrAniyomiBackup(source, app)
            }
            ExternalBackupApp.VENERA -> {
                val bytes = openBackupSource(uri) { source ->
                    readExternalBackupBytes(source)
                }
                runCatching {
                    decodeVeneraBackup(bytes)
                }.getOrNull()
            }
        } ?: throw UnsupportedExternalBackupException("Unsupported ${app.name} backup format")
    }

    private inline fun <T> openBackupSource(uri: Uri, block: (BufferedSource) -> T): T {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open backup file")
        return inputStream.source().buffer().use(block)
    }

    /** Extracts the SQLite files and hands them to the shared [VeneraBackupDecoding] rules. */
    private fun decodeVeneraBackup(bytes: ByteArray): ExternalBackupPayload {
        val backupFile = File.createTempFile("venera_backup", ".venera", context.cacheDir)
        val databaseFiles = LinkedHashMap<String, File>()
        return try {
            backupFile.writeBytes(bytes)
            databaseFiles += extractVeneraDatabases(backupFile)
            if (databaseFiles.isEmpty()) {
                throw UnsupportedExternalBackupException("Unsupported Venera backup format")
            }
            openReadOnly(databaseFiles[VeneraBackupDecoding.FAVORITES_FILE]).use { favorites ->
                openReadOnly(databaseFiles[VeneraBackupDecoding.HISTORY_FILE]).use { history ->
                    VeneraBackupDecoding.decode(favorites?.let(::CursorVeneraDatabase), history?.let(::CursorVeneraDatabase))
                }
            }
        } finally {
            backupFile.delete()
            databaseFiles.values.forEach { it.delete() }
        }
    }

    private fun openReadOnly(file: File?): SQLiteDatabase? =
        file?.let { SQLiteDatabase.openDatabase(it.absolutePath, null, SQLiteDatabase.OPEN_READONLY) }

    private fun extractVeneraDatabases(file: File): Map<String, File> {
        val result = LinkedHashMap<String, File>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = VeneraBackupDecoding.databaseFileOf(entry.name)
                if (!entry.isDirectory && name != null) {
                    val file = File.createTempFile("venera_$name", ".db", context.cacheDir)
                    zip.getInputStream(entry).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    result[name] = file
                }
            }
        }
        return result
    }

    private class CursorVeneraDatabase(private val db: SQLiteDatabase) : VeneraDatabase {
        override fun query(sql: String, args: List<String>): List<Map<String, Any?>> =
            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.row())
                }
            }

        private fun Cursor.row(): Map<String, Any?> = columnNames.withIndex().associate { (index, name) ->
            name to when (getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_STRING -> getString(index)
                Cursor.FIELD_TYPE_BLOB -> getBlob(index)
                else -> null
            }
        }
    }
}
