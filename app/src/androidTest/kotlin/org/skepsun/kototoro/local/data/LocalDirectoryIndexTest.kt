package org.skepsun.kototoro.local.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.tvbox.osc.base.App
import com.hippo.unifile.UniFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.StoredContentIdentityResolver
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.domain.ContentLock
import org.skepsun.kototoro.local.novel.LocalNovelRepository

/** Uses real settings, archive parsing and Room, with no access to the user's library or directories. */
@RunWith(AndroidJUnit4::class)
class LocalDirectoryIndexTest {

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: MangaDatabase
    private lateinit var settings: AppSettings
    private lateinit var index: LocalContentIndex
    private lateinit var repository: LocalMangaRepository
    private lateinit var storage: LocalStorageManager
    private val bridgeContext = App::class.java.getDeclaredField("bridgeContext").apply { isAccessible = true }
    private var previousBridgeContext: Any? = null

    @Before
    fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "local-directory-test-${System.nanoTime()}"
        root = File(base.cacheDir, namespace).apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getExternalFilesDirs(type: String?): Array<File> = emptyArray()
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
        }
        // HiltTestApplication does not initialize the production context bridge. Supply only
        // the isolated parser context, without bootstrapping the TVBox player/runtime.
        previousBridgeContext = bridgeContext.get(null)
        bridgeContext.set(null, context)
        settings = AppSettings(context)
        db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        storage = LocalStorageManager(context, settings)
        val content = ContentDataRepository(
            db, Provider { error("No remote links in this fixture") },
            Provider { error("No shortcuts in this fixture") }, StoredContentIdentityResolver(db),
        )
        index = LocalContentIndex(
            content, db, context, Provider { repository }, Provider { LocalNovelRepository(storage) }, storage,
        )
        repository = LocalMangaRepository(
            storage, db, index, MutableSharedFlow(), settings, ContentLock(),
            Provider { error("No remote source in this fixture") },
        )
    }

    @After
    fun tearDown() {
        db.close()
        bridgeContext.set(null, previousBridgeContext)
    }

    @Test
    fun contentUriArchiveUsesItsDisplayNameInsteadOfTheMaterializedCacheName() = runBlocking {
        val directory = File(root, "provider-directory").apply { mkdirs() }
        val archive = createArchive(directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
        val file = checkNotNull(UniFile.fromUri(context, uri))

        assertEquals("Directory sample.cbz", file.name)
        val content = LocalContentParser(file, context.cacheDir).getContent(withDetails = true)

        assertEquals("Directory sample", content.manga.title)
        assertEquals(uri.toString(), content.manga.url)
        assertTrue(content.manga.chapters.orEmpty().isNotEmpty())
    }

    @Test
    fun contentUriArchiveKeepsItsEmbeddedTitle() = runBlocking {
        val directory = File(root, "metadata-directory").apply { mkdirs() }
        val archive = createArchive(directory, "Embedded title")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
        val file = checkNotNull(UniFile.fromUri(context, uri))

        val content = LocalContentParser(file, context.cacheDir).getContent(withDetails = true)

        assertEquals("Embedded title", content.manga.title)
    }

    @Test
    fun storageUsageIncludesDirectoriesSelectedByUriAndCountsSharedRootsOnce() = runBlocking {
        val directory = File(root, "usage-directory").apply { mkdirs() }
        val archive = createArchive(directory)
        settings.userSpecifiedContentDirectoryUris = setOf(directory.toUri())
        settings.userSpecifiedNovelDirectoryUris = setOf(directory.toUri())

        assertEquals(archive.length(), storage.computeStorageSize(StorageContentKind.MANGA))
        assertEquals(archive.length(), storage.computeStorageSize(StorageContentKind.NOVEL))
        assertEquals(archive.length(), storage.computeStorageSize())
    }

    @Test
    fun addingDirectoryAfterAnEmptyIndexLoadsItsArchiveWithoutRestart() = runBlocking {
        assertTrue(repository.getList(0, null, null).isEmpty())
        val directory = File(root, "picked-directory").apply { mkdirs() }
        val archive = createArchive(directory)

        // Restored settings/new directory selection must invalidate an already-current index.
        settings.userSpecifiedContentDirectoryUris = setOf(directory.toUri())

        val loaded = repository.getList(0, null, null)
        assertEquals("A new directory must be scanned even when the index version is current", 1, loaded.size)
        assertEquals(archive.toUri().toString(), db.getLocalContentIndexDao().findPath(loaded.single().id))
        assertTrue(repository.getDetails(loaded.single()).chapters.orEmpty().isNotEmpty())
    }

    @Test
    fun removingDirectoryDropsItsIndexButUnchangedRootsDoNotRescanOnEveryRead() = runBlocking {
        val directory = File(root, "picked-directory").apply { mkdirs() }
        settings.userSpecifiedContentDirectoryUris = setOf(directory.toUri())
        assertTrue(repository.getList(0, null, null).isEmpty())
        createArchive(directory)

        assertTrue(
            "Reading an unchanged root must keep the database-only list path",
            repository.getList(0, null, null).isEmpty(),
        )
        index.update()
        assertEquals(1, repository.getList(0, null, null).size)

        settings.userSpecifiedContentDirectoryUris = emptySet()

        assertTrue("A removed directory must not leave stale entries", repository.getList(0, null, null).isEmpty())
    }

    private fun createArchive(directory: File, embeddedTitle: String? = null): File {
        val archive = File(directory, "Directory sample.cbz")
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("Chapter 01/01.png"))
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                zip.closeEntry()
                if (embeddedTitle != null) {
                    val metadata = org.json.JSONObject()
                        .put("id", 42)
                        .put("title", embeddedTitle)
                        .put("url", archive.toUri().toString())
                        .put("source", org.skepsun.kototoro.core.model.LocalMangaSource.name)
                        .put("tags", org.json.JSONArray())
                        .put("chapters", org.json.JSONObject())
                    zip.putNextEntry(ZipEntry("index.json"))
                    zip.write(metadata.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        } finally {
            bitmap.recycle()
        }
        return archive
    }
}
