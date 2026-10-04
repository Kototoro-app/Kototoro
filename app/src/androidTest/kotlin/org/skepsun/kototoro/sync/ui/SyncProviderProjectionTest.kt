package org.skepsun.kototoro.sync.ui

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SyncStats
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.provider.ProviderTestRule
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.sync.data.SyncSettings
import org.skepsun.kototoro.sync.data.model.SyncDto
import org.skepsun.kototoro.sync.domain.SyncHelper
import okio.Buffer
import java.util.concurrent.TimeUnit

/** Tests the provider contract against an isolated v84 database, never the application database. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class SyncProviderProjectionTest {

    private lateinit var context: Context
    private lateinit var db: MangaDatabase
    private lateinit var resolver: ContentResolver
    private lateinit var client: ContentProviderClient
    private lateinit var historyClient: ContentProviderClient
    private val ruleContext = ApplicationProvider.getApplicationContext<Context>()
    private val favouritesAuthority = ruleContext.getString(R.string.sync_authority_favourites)
    private val historyAuthority = ruleContext.getString(R.string.sync_authority_history)

    @get:Rule
    val providerRule: ProviderTestRule = ProviderTestRule.Builder(TestProvider::class.java, favouritesAuthority)
        .addProvider(TestProvider::class.java, historyAuthority)
        .build()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        resolver = providerRule.resolver
        client = requireNotNull(resolver.acquireContentProviderClient(favouritesAuthority))
        historyClient = requireNotNull(resolver.acquireContentProviderClient(historyAuthority))
        (client.localContentProvider as TestProvider).database = db
        (historyClient.localContentProvider as TestProvider).database = db
        val sql = db.openHelper.writableDatabase
        for (id in 1..4) {
            sql.execSQL(
                """
                INSERT INTO manga (manga_id, title, url, public_url, rating, nsfw, cover_url, source)
                VALUES (?, 'Fixture', '/fixture', 'https://fixture.invalid', 0, 0, '', 'fixture')
                """.trimIndent(),
                arrayOf(id),
            )
        }
        for (id in 10..12) {
            sql.execSQL(
                """
                INSERT INTO favourite_categories
                    (category_id, created_at, sort_key, title, `order`, track, show_in_lib, deleted_at)
                VALUES (?, 1, 0, 'Fixture category', 'NEWEST', 0, 1, 0)
                """.trimIndent(),
                arrayOf(id),
            )
        }
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) client.close()
        if (::historyClient.isInitialized) historyClient.close()
        if (::db.isInitialized) db.close()
    }

    @Test
    fun duplicateInsertsUpdateOnlyTheMatchingMangaAndCategory() {
        val favourites = uri(favouritesAuthority, "favourites")
        assertNotNull(resolver.insert(favourites, favourite(1, 10, sortKey = 1)))
        assertNotNull(resolver.insert(favourites, favourite(1, 11, sortKey = 2)))
        assertNotNull(resolver.insert(favourites, favourite(1, 10, sortKey = 9)))
        resolver.query(favourites, arrayOf("category_id", "sort_key"), null, null, "category_id")!!.use {
            assertEquals(2, it.count)
            it.moveToFirst()
            assertEquals(10, it.getInt(0))
            assertEquals(9, it.getInt(1))
            it.moveToNext()
            assertEquals(11, it.getInt(0))
            assertEquals(2, it.getInt(1))
        }

        val history = uri(historyAuthority, "history")
        resolver.insert(history, history(1, page = 1))
        resolver.insert(history, history(2, page = 2))
        resolver.insert(history, history(1, page = 7))
        resolver.query(history, arrayOf("manga_id", "page"), null, null, "manga_id")!!.use {
            assertEquals(2, it.count)
            it.moveToFirst()
            assertEquals(1L, it.getLong(0))
            assertEquals(7, it.getInt(1))
            it.moveToNext()
            assertEquals(2L, it.getLong(0))
            assertEquals(2, it.getInt(1))
        }
    }

    @Test
    fun invalidMembershipRollsBackTheWholeBatch() {
        val favourites = uri(favouritesAuthority, "favourites")
        resolver.insert(favourites, favourite(1, 10, sortKey = 1))
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(favourites).withValues(favourite(1, 10, sortKey = 9)).build(),
            ContentProviderOperation.newInsert(favourites).withValues(favourite(1, 999)).build(),
        )
        assertThrows(Exception::class.java) { resolver.applyBatch(favouritesAuthority, operations) }
        resolver.query(favourites, arrayOf("sort_key"), null, null, null)!!.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun removedWorkTablesAreNotExposedOrRecreated() {
        for (table in listOf("work_favourites", "work_history")) {
            val uri = uri(favouritesAuthority, table)
            assertNull(resolver.query(uri, null, null, null, null))
            assertNull(resolver.insert(uri, favourite(1, 10)))
            assertEquals(0, resolver.delete(uri, null, null))
            assertEquals(0, resolver.update(uri, ContentValues(), null, null))
            db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun successfulSyncExportsTombstonesThenCollectsOnlyExpiredDeletions() {
        val now = System.currentTimeMillis()
        val recent = now - TimeUnit.HOURS.toMillis(1)
        val expired = now - TimeUnit.DAYS.toMillis(10)
        val favourites = uri(favouritesAuthority, "favourites")
        val history = uri(historyAuthority, "history")
        for ((index, deletion) in listOf(0L, recent, expired).withIndex()) {
            resolver.insert(favourites, favourite(index + 1, 10, deletedAt = deletion))
            resolver.insert(history, history(index + 1, deletedAt = deletion))
        }
        db.openHelper.writableDatabase.execSQL(
            "UPDATE favourite_categories SET deleted_at = ? WHERE category_id = 12",
            arrayOf(expired),
        )
        val exported = mutableListOf<SyncDto>()
        // Respond before the transport/auth interceptor: no socket or account mutation is needed.
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            requireNotNull(chain.request().body).writeTo(buffer)
            exported += Json.decodeFromString<SyncDto>(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(204).message("Fixture").body("".toResponseBody()).build()
        }.build()
        fun helper(provider: ContentProviderClient) = SyncHelper(
            context,
            http,
            Account("fixture", context.getString(R.string.account_type_sync)),
            provider,
            SyncSettings(context, null),
            db,
        )
        helper(client).syncFavourites(SyncStats())
        helper(historyClient).syncHistory(SyncStats())

        val expectedDeletions = mapOf(1L to 0L, 2L to recent, 3L to expired)
        assertEquals(expectedDeletions, exported[0].favourites!!.associate { it.mangaId to it.deletedAt })
        assertEquals(expectedDeletions, exported[1].history!!.associate { it.mangaId to it.deletedAt })
        for (uri in listOf(favourites, history)) {
            resolver.query(uri, arrayOf("manga_id", "deleted_at"), null, null, "manga_id")!!.use {
                assertEquals(2, it.count)
                it.moveToFirst()
                assertEquals(1L, it.getLong(0))
                assertEquals(0L, it.getLong(1))
                it.moveToNext()
                assertEquals(2L, it.getLong(0))
                assertEquals(recent, it.getLong(1))
            }
        }
        resolver.query(uri(favouritesAuthority, "favourite_categories"), null, null, null, null)!!.use {
            assertEquals(2, it.count)
        }
    }

    private fun favourite(mangaId: Int, categoryId: Int, sortKey: Int = 0, deletedAt: Long = 0) =
        ContentValues().apply {
            put("manga_id", mangaId)
            put("category_id", categoryId)
            put("sort_key", sortKey)
            put("pinned", false)
            put("created_at", 1L)
            put("updated_at", 2L)
            put("deleted_at", deletedAt)
        }

    private fun history(mangaId: Int, page: Int = 0, deletedAt: Long = 0) = ContentValues().apply {
        put("manga_id", mangaId)
        put("created_at", 1L)
        put("updated_at", 2L)
        put("chapter_id", 1L)
        put("page", page)
        put("scroll", 0f)
        put("percent", 0f)
        put("deleted_at", deletedAt)
        put("chapters", 1)
    }

    private fun uri(authority: String, table: String): Uri = Uri.parse("content://$authority/$table")

    class TestProvider : SyncProvider() {
        public override lateinit var database: MangaDatabase
    }
}
