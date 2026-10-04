package org.skepsun.kototoro.desktop.runtime

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import org.skepsun.kototoro.core.db.MangaDatabase
import java.io.IOException
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The desktop builder uses the existing Room schema; unsupported versions fail without destructive fallback. */
internal class DesktopDatabase private constructor(
    val database: MangaDatabase,
    private val driver: DesktopSQLiteDriver,
) : Closeable {
    override fun close() {
        var failure: Throwable? = null
        try { database.close() } catch (error: Throwable) { failure = error; throw error } finally {
            try { driver.close() } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
        }
    }

    companion object {
        fun create(path: Path): DesktopDatabase {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Desktop database must be a regular file")
            }
            val driver = DesktopSQLiteDriver()
            try {
                val database = Room.databaseBuilder<MangaDatabase>(name = path.toString())
                    .setDriver(driver)
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
                return DesktopDatabase(database, driver)
            } catch (error: Throwable) {
                try { driver.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}
