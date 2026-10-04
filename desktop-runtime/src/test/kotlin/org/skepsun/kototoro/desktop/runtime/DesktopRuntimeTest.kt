package org.skepsun.kototoro.desktop.runtime

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.db.DATABASE_VERSION
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity
import org.skepsun.kototoro.core.source.SourceProtocolJson
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import org.skepsun.kototoro.explore.data.SourcesSortOrder
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext

class DesktopRuntimeTest {
    @TempDir lateinit var directory: Path
    private val source = MangaSourceEntity("MIHON_9007199254740993", true, 7, 1, 0, true, 0)

    @Test
    fun `desktop owns persistent preferences alongside database and releases ownership on shutdown`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            assertEquals(runtime.paths.preferences, runtime.preferences.directory)
            assertEquals(runtime.paths.preferences.toString(), runtime.storageInfo().preferenceDirectory)
            assertTrue(runtime.preferences.open("source_9007199254740993").edit(SourcePreferenceEdit(changes =
                mapOf("domain" to SourcePreferenceValue.Text("saved.invalid")))))
        }
        DesktopRuntime.open(directory).use { runtime ->
            assertEquals(SourcePreferenceValue.Text("saved.invalid"),
                runtime.preferences.open("source_9007199254740993").snapshot()["domain"])
        }
        FileSourcePreferenceStore(directory.resolve("preferences")).close()
        val moved = directory.resolveSibling("preferences runtime closed")
        Files.move(directory, moved)
        Files.move(moved, directory)
    }

    @Test
    fun `occupied preference owner fails startup and still releases database handles`() {
        FileSourcePreferenceStore(directory.resolve("preferences")).use {
            assertThrows(Exception::class.java) { runBlocking { DesktopRuntime.open(directory) } }
            val database = directory.resolve("kototoro.db")
            val moved = directory.resolve("released.db")
            Files.move(database, moved)
            Files.move(moved, database)
        }
        runBlocking { DesktopRuntime.open(directory).close() }
    }

    @Test
    fun `disk database preserves source manga favourites and reading progress after close and reopen`() = runBlocking {
        val root = directory.resolve("桌面 library space")
        val manga = MangaEntity(Long.MAX_VALUE, "漫画 📚", "Alias", "/manga", "https://fixture.invalid/manga",
            0.8f, false, "SAFE", "/cover", null, "ONGOING", "作者", source.source,
            contentType = "MANGA", sourceData = "{\"memo\":\"保留\"}")
        val category = FavouriteCategoryEntity(1, 1, 0, "收藏", "NEWEST", true, true, 0)
        val favourite = FavouriteEntity(manga.id, 1, 3, true, 1, 0, 2)
        val history = HistoryEntity(manga.id, 1, 1720000000000L, Long.MIN_VALUE, 12, 0.25f, 0.5f, 0, 50)
        DesktopRuntime.open(root).use { runtime ->
            runtime.database.getSourcesDao().upsert(source)
            runtime.database.getMangaDao().upsert(manga)
            runtime.database.getFavouriteCategoriesDao().insert(category)
            runtime.database.getFavouritesDao().upsert(favourite)
            runtime.database.getHistoryDao().upsert(history)
            assertEquals(1, runtime.storageInfo().sourceCount)
        }
        DesktopRuntime.open(root).use { runtime ->
            assertEquals(source, runtime.database.getSourcesDao().find(source.source))
            assertEquals(manga, runtime.database.getMangaDao().find(manga.id)?.manga)
            assertEquals(category, runtime.database.getFavouriteCategoriesDao().find(1))
            assertEquals(favourite, runtime.database.getFavouritesDao().find(manga.id, 1))
            assertEquals(history, runtime.database.getHistoryDao().find(manga.id))
            assertEquals(manga, runtime.database.getHistoryDao().findRecent(10).single().manga)
            assertEquals(manga, runtime.database.getFavouritesDao().findAllWithActiveCategory(0, 10).single().manga)
            val observed = runtime.database.getSourcesDao().observeAll(false, SourcesSortOrder.MANUAL).first()
            assertEquals(listOf(source), observed)
        }
    }

    @Test
    fun `disk schema matches Android v84 and images share the private root`() = runBlocking {
        val runtime = DesktopRuntime.open(directory)
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXuoAAAAASUVORK5CYII=",
        )
        val image = runtime.images.materialize(SourceRef(source.source, "zh", "MANGA"), Long.MIN_VALUE,
            png.inputStream(), -1) {}
        val info = runtime.storageInfo()
        assertEquals(DATABASE_VERSION, info.schemaVersion)
        assertEquals(0, info.sourceCount)
        assertEquals(runtime.paths.root.resolve("images"), Path.of(info.imageDirectory))
        assertArrayEquals(png, Files.readAllBytes(runtime.paths.images.resolve(image.relativePath)))
        runtime.close()
        val schema = SourceProtocolJson.parseToJsonElement(
            Files.readString(Path.of(System.getProperty("kototoro.schema84"))),
        ).jsonObject.getValue("database").jsonObject
        BundledSQLiteDriver().open(info.database).use { connection ->
            connection.prepare("PRAGMA user_version").use { statement ->
                assertTrue(statement.step())
                assertEquals(DATABASE_VERSION.toLong(), statement.getLong(0))
            }
            connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42").use { statement ->
                assertTrue(statement.step())
                assertEquals(schema.getValue("identityHash").jsonPrimitive.content, statement.getText(0))
            }
        }
    }

    @Test
    fun `unsupported legacy version fails without destructive migration or changing existing data`() {
        val file = directory.resolve("kototoro.db")
        BundledSQLiteDriver().open(file.toString()).use { connection ->
            for (sql in listOf("CREATE TABLE preserved (value TEXT NOT NULL)",
                    "INSERT INTO preserved VALUES ('fixture-data')", "PRAGMA user_version = 83")) {
                connection.prepare(sql).use { it.step() }
            }
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { DesktopRuntime.open(directory) } }
        assertFalse(Files.exists(directory.resolve("images")))
        BundledSQLiteDriver().open(file.toString()).use { connection ->
            connection.prepare("PRAGMA user_version").use { statement ->
                assertTrue(statement.step()); assertEquals(83L, statement.getLong(0))
            }
            connection.prepare("SELECT value FROM preserved").use { statement ->
                assertTrue(statement.step()); assertEquals("fixture-data", statement.getText(0))
            }
        }
    }

    @Test
    fun `corrupt database bytes and existing directory are not replaced`() {
        val file = directory.resolve("kototoro.db")
        val original = "not a SQLite database".toByteArray()
        Files.write(file, original)
        assertThrows(Exception::class.java) { runBlocking { DesktopRuntime.open(directory) } }
        assertArrayEquals(original, Files.readAllBytes(file))
        val occupiedRoot = Files.createDirectories(directory.resolve("occupied"))
        Files.createDirectory(occupiedRoot.resolve("kototoro.db"))
        assertThrows(IOException::class.java) { runBlocking { DesktopRuntime.open(occupiedRoot) } }
        assertTrue(Files.isDirectory(occupiedRoot.resolve("kototoro.db")))
    }

    @Test
    fun `failed cache startup closes database handles and later startup remains usable`() = runBlocking {
        val blocking = directory.resolve("images")
        Files.writeString(blocking, "existing file")
        assertThrows(IOException::class.java) { runBlocking { DesktopRuntime.open(directory) } }
        Files.move(blocking, directory.resolve("preserved-cache-file"))
        val original = directory.resolve("kototoro.db")
        val moved = directory.resolve("moved.db")
        // Windows rejects this if failed startup left an open SQLite file handle.
        Files.move(original, moved)
        Files.move(moved, original)
        DesktopRuntime.open(directory).use { assertEquals(DATABASE_VERSION, it.storageInfo().schemaVersion) }
        assertEquals("existing file", Files.readString(directory.resolve("preserved-cache-file")))
    }

    @Test
    fun `close is idempotent and releases Windows database handles`() = runBlocking {
        val runtime = DesktopRuntime.open(directory)
        runtime.database.getSourcesDao().upsert(source)
        runtime.close()
        runtime.close()
        assertThrows(IllegalStateException::class.java) { runBlocking { runtime.storageInfo() } }
        val moved = directory.resolve("closed.db")
        Files.move(runtime.paths.database, moved)
        Files.move(moved, runtime.paths.database)
        DesktopRuntime.open(directory).use {
            assertEquals(source, it.database.getSourcesDao().find(source.source))
        }
    }

    @Test
    fun `prompt cancellation on dispatcher return closes an acquired runtime without delivering it`() = runBlocking {
        val queued = object : CoroutineDispatcher() {
            val tasks = LinkedBlockingQueue<Runnable>()
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        var delivered = false
        val call = async(queued) { DesktopRuntime.open(directory).use { delivered = true } }
        try {
            requireNotNull(queued.tasks.poll(15, TimeUnit.SECONDS)).run() // Enter open(), then switch to IO.
            val completedOpen = requireNotNull(queued.tasks.poll(15, TimeUnit.SECONDS))
            call.cancel() // Acquisition finished, but its return has not yet been dispatched to the caller.
            completedOpen.run()
            call.join()
            assertFalse(delivered)
            val original = directory.resolve("kototoro.db")
            val moved = directory.resolve("cancelled.db")
            Files.move(original, moved)
            Files.move(moved, original)
            DesktopRuntime.open(directory).use { assertEquals(DATABASE_VERSION, it.storageInfo().schemaVersion) }
        } finally { call.cancel(); while (true) (queued.tasks.poll() ?: break).run() }
    }

    @Test
    fun `packaged Windows CLI runs bundled SQLite and prints UTF8 storage JSON`() {
        val distribution = Path.of(System.getProperty("kototoro.desktopDistribution"))
        val jars = Files.list(distribution.resolve("lib")).use { entries ->
            entries.filter { it.fileName.toString().endsWith(".jar") }.map(Path::toString).toList()
        }
        val executable = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val root = directory.resolve("打包 CLI data")
        val process = ProcessBuilder(executable.toString(), "-cp", jars.joinToString(File.pathSeparator),
            "org.skepsun.kototoro.desktop.runtime.DesktopCliKt", "storage", root.toString()).start()
        try {
            process.outputStream.close()
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Storage CLI did not close its runtime")
            val stdout = process.inputStream.reader(Charsets.UTF_8).readText()
            val stderr = process.errorStream.reader(Charsets.UTF_8).readText()
            assertEquals(0, process.exitValue(), stderr)
            val info = SourceProtocolJson.decodeFromString<DesktopStorageInfo>(stdout.trim())
            assertEquals(DATABASE_VERSION, info.schemaVersion)
            assertEquals(0, info.sourceCount)
            assertEquals(root.toRealPath().toString(), info.dataDirectory)
            assertTrue(Files.isRegularFile(Path.of(info.database)))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
