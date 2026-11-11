@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.library.LibraryDriveSync
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncApi
import org.skepsun.kototoro.sync.google.data.model.*
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncMerger
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.*

class IosGoogleDriveSyncTest {
    @Test
    fun staleUnrecordedCountersDoNotReturnAfterMarkAllReadAndSync() = runBlocking {
        withLibrary { library ->
            val original = IosGoogleDriveSync.decode(ANDROID.encodeToByteArray())
            fun remote(checkedOffset: Long) = GoogleDriveSyncSnapshot(deviceId = "android",
                content = original.content, categories = original.categories, favourites = original.favourites,
                history = original.history, feed = SyncFeedState(tracks = original.feed.tracks.map {
                    SyncTrack(mangaId = it.mangaId, lastChapterId = it.lastChapterId, newChapters = 900,
                        lastCheckTime = it.lastCheckTime + checkedOffset, lastChapterDate = it.lastChapterDate,
                        lastResult = it.lastResult, lastError = it.lastError)
                }))
            val store = LibraryDriveSync(library.database)
            store.merge(remote(0), "ios", 1000)
            library.markAllUpdatesRead()
            // Another device checks later and re-uploads its pre-identity counter without update records.
            val upload = store.merge(remote(1000), "ios", 2000)
            assertEquals(0, upload.feed.tracks.single().newChapters)
            assertTrue(library.updates().isEmpty())
        }
    }

    @Test
    fun completedUpdateChaptersStayReadAfterStaleSyncAndNewDiscoveriesStillCount() = runBlocking {
        withLibrary { library ->
            val original = IosGoogleDriveSync.decode(ANDROID.encodeToByteArray())
            val id = original.content.single().id
            fun chapter(number: Long) = org.skepsun.kototoro.tracker.domain.UpdateChapter(
                "chapter/$number", number, "Chapter $number", null, 100, 200)
            fun remote(newChapter: Boolean = false): GoogleDriveSyncSnapshot {
                val chapters = if (newChapter) listOf(chapter(1), chapter(2)) else listOf(chapter(1))
                return GoogleDriveSyncSnapshot(deviceId = "android", content = original.content,
                    categories = original.categories, favourites = original.favourites,
                    feed = SyncFeedState(tracks = listOf(SyncTrack(mangaId = id, newChapters = chapters.size,
                        lastCheckTime = if (newChapter) 400 else 250)),
                        logs = listOf(SyncTrackLog(mangaId = id, chapters = chapters.joinToString("\n") { it.title },
                            createdAt = if (newChapter) 400 else 250, isUnread = true,
                            chapterMetadata = org.skepsun.kototoro.tracker.domain.encodeUpdateChapters(chapters)))))
            }
            val store = LibraryDriveSync(library.database)
            store.merge(remote(), "ios", 1000)
            assertEquals(1, library.updates().single().newChapters)
            library.markAllUpdatesRead()
            repeat(2) {
                val upload = store.merge(remote(), "ios", 2000)
                assertEquals(0, upload.feed.tracks.single().newChapters)
                assertTrue(upload.feed.logs.none { it.isUnread })
            }
            val upload = store.merge(remote(newChapter = true), "ios", 3000)
            assertEquals(1, upload.feed.tracks.single().newChapters)
        }
    }

    @Test
    fun recordsBadgeCountsUnseenWorksInsteadOfPendingChapters() = runBlocking {
        withLibrary { library ->
            val original = IosGoogleDriveSync.decode(ANDROID.encodeToByteArray())
            val id = original.content.single().id
            fun remote(unseen: Boolean) = GoogleDriveSyncSnapshot(deviceId = "android", content = original.content,
                categories = original.categories, favourites = original.favourites,
                feed = SyncFeedState(tracks = listOf(SyncTrack(mangaId = id, newChapters = 900)),
                    logs = listOf(SyncTrackLog(mangaId = id, chapters = "Legacy chapter", createdAt = 100,
                        isUnread = unseen))))
            val store = LibraryDriveSync(library.database)
            store.merge(remote(unseen = true), "ios", 900)
            assertEquals(1, library.unseenUpdates().size)
            store.merge(remote(unseen = false), "ios", 1000)
            assertEquals(900, library.updates().single().newChapters, "Seeing a reminder is not reading its chapters")
            assertTrue(library.unseenUpdates().isEmpty())
            library.markAllUpdatesRead()
            assertTrue(library.updates().isEmpty())
            assertTrue(library.unseenUpdates().isEmpty())
        }
    }

    @Test
    fun iosCreatedFavouritesAndReadingPositionUseAndroidProtocol() = runBlocking {
        withLibrary { library ->
            val source = SourceRef("MIHON_123", "zh", "MANGA")
            val chapter = SourceChapter(Long.MAX_VALUE, "第一话", 1f, 1, "/chapter", null, 1, "中文", source)
            val content = IosSourceContent(SourceContent(Long.MIN_VALUE, "iOS 新作品", emptySet(), "/ios",
                "https://source.invalid/ios", 0f, null, null, emptySet(), null, emptySet(), source,
                chapters = listOf(chapter)))
            library.addCategory("iOS 收藏")
            library.setCategories(content, listOf(library.categories().single().id))
            library.recordPosition(content, IosSourceChapter(chapter), 3, 8, .5f)
            val upload = LibraryDriveSync(library.database).merge(null, "ios", 1000)
            val wire = IosGoogleDriveSync.json.encodeToString(GoogleDriveSyncSnapshot.serializer(), upload)
            val android = GoogleDriveSyncMerger.combine(listOf(IosGoogleDriveSync.decode(wire.encodeToByteArray())))!!
            assertEquals(Long.MIN_VALUE, android.favourites.single().mangaId)
            assertEquals(Long.MAX_VALUE, android.history.single().chapterId)
            assertEquals(3, android.history.single().page)
            assertEquals(.5f, android.history.single().scroll)
            assertEquals("iOS 收藏", android.categories.single().title)
        }
    }

    @Test
    fun androidWireSnapshotRoundTripsThroughIosDatabaseWithoutLosingAndroidSections() = runBlocking {
        withLibrary { library ->
            val remote = IosGoogleDriveSync.decode(ANDROID.encodeToByteArray())
            val store = LibraryDriveSync(library.database)
            val upload = store.merge(remote, "ios", 1000)
            assertEquals(1, library.favourites().size)
            assertEquals("9007199254740993", library.favourites().single().content.id)
            assertEquals("来自安卓", library.categories().single().title)
            val encoded = IosGoogleDriveSync.json.encodeToString(GoogleDriveSyncSnapshot.serializer(), upload)
            // Android uses this exact serializer and merger, including Long ids beyond JavaScript precision.
            val androidRead = GoogleDriveSyncMerger.combine(listOf(IosGoogleDriveSync.decode(encoded.encodeToByteArray())))!!
            assertEquals("kototoro.content.v3", androidRead.namespace)
            assertEquals(9007199254740995L, androidRead.history.single().chapterId)
            assertEquals(7, androidRead.history.single().page)
            assertEquals(.25f, androidRead.history.single().scroll)
            assertEquals(42L, androidRead.config!!.revision)
            assertEquals("apk-data", androidRead.extensions.single().payloadBase64)
            assertEquals("https://repo.invalid", androidRead.repositories.single().baseUrl)
            assertEquals(123L, androidRead.sourceStates.single().modifiedAt)
            assertEquals("{}", androidRead.jsonSources.single().config)
            assertEquals(400L, androidRead.stats.single().duration)
            assertEquals(1, androidRead.feed.tracks.size)
            assertEquals(1, androidRead.feed.logs.size)
            // Repeating a sync never multiplies records.
            val second = store.merge(androidRead, "ios", 1001)
            assertEquals(1, second.favourites.size)
            assertEquals(1, second.stats.size)
            assertEquals(1, second.feed.logs.size)
        }
    }

    @Test
    fun sourcePinsSyncBothWaysWithTheNewestEditWinning() = runBlocking {
        withLibrary { library ->
            val store = LibraryDriveSync(library.database, library.sourceEdits)
            fun remote(vararg states: SyncSourceState) = GoogleDriveSyncSnapshot(deviceId = "android", sourceStates = states.toList())
            // An Android pin reaches a source iOS has never stored, then one it has.
            library.setSourceOrder(listOf("MIHON_2"), listOf(true))
            store.merge(remote(SyncSourceState("MIHON_1", true, isPinned = true, sortKey = 3, modifiedAt = 100),
                SyncSourceState("MIHON_2", true, isPinned = true, modifiedAt = Long.MAX_VALUE / 2)), "ios", 1000)
            val settings = library.sourceSettings().associateBy { it.name }
            assertTrue(settings.getValue("MIHON_1").pinned)
            assertEquals(3, settings.getValue("MIHON_1").sortKey)
            assertTrue(settings.getValue("MIHON_2").pinned)
            // An iOS unpin made after Android's edit survives the stale remote copy and is uploaded with its time.
            library.setSourcePinned("MIHON_1", false)
            val upload = store.merge(remote(SyncSourceState("MIHON_1", true, isPinned = true, sortKey = 3,
                modifiedAt = 100)), "ios", 2000)
            assertFalse(library.sourceSettings().single { it.name == "MIHON_1" }.pinned)
            val uploaded = upload.sourceStates.single { it.source == "MIHON_1" }
            assertFalse(uploaded.isPinned)
            assertTrue(uploaded.modifiedAt > 100)
            // Android's merger prefers that newer state over the old remote one.
            val android = GoogleDriveSyncMerger.mergeSnapshots(upload,
                remote(SyncSourceState("MIHON_1", true, isPinned = true, sortKey = 3, modifiedAt = 100)))
            assertFalse(android.sourceStates.single { it.source == "MIHON_1" }.isPinned)
            // A later Android pin wins again, and the edit times survive reopening the library.
            library.close()
            val reopened = IosLibrary(path)
            try {
                LibraryDriveSync(reopened.database, reopened.sourceEdits).merge(remote(SyncSourceState("MIHON_1", true,
                    isPinned = true, modifiedAt = uploaded.modifiedAt + 1)), "ios", 3000)
                assertTrue(reopened.sourceSettings().single { it.name == "MIHON_1" }.pinned)
                reopened.setSourcePinned("MIHON_2", false)
                val kept = LibraryDriveSync(reopened.database, reopened.sourceEdits).merge(remote(SyncSourceState(
                    "MIHON_2", true, isPinned = true, modifiedAt = 100)), "ios", 4000)
                assertFalse(kept.sourceStates.single { it.source == "MIHON_2" }.isPinned)
            } finally { reopened.close() }
        }
    }

    @Test
    fun deletingCategoryWithWorksTombstonesEveryMembershipAndSurvivesStaleSnapshot() = runBlocking {
        withLibrary { library ->
            val source = SourceRef("MIHON_123", "zh", "MANGA")
            fun work(id: Long) = IosSourceContent(SourceContent(id, "作品$id", emptySet(), "/w$id",
                "https://source.invalid/w$id", 0f, null, null, emptySet(), null, emptySet(), source))
            val shared = work(-10)
            val only = work(-11)
            library.addCategory("A")
            library.addCategory("B")
            val (a, b) = library.categories().map { it.id }
            library.setCategories(shared, listOf(a, b))
            library.setCategories(only, listOf(a))
            val sync = LibraryDriveSync(library.database)
            val stale = sync.merge(null, "ios", 1000)
            library.removeCategoryWithWorks(a)
            assertTrue(library.favourites().isEmpty())
            val upload = sync.merge(stale, "ios", 2000)
            assertEquals(3, upload.favourites.size)
            assertTrue(upload.favourites.all { it.deletedAt > 0 })
            assertTrue(upload.categories.single { it.id == a.toLong() }.deletedAt > 0)
            assertEquals(0L, upload.categories.single { it.id == b.toLong() }.deletedAt)
            // Android reading the upload sees every membership as deleted.
            val android = GoogleDriveSyncMerger.combine(listOf(upload))!!
            assertTrue(android.favourites.all { it.deletedAt > 0 })
        }
    }

    @Test
    fun iosDeletionsAndNewerProgressSurviveStaleAndroidSnapshotAndDatabaseReopen() = runBlocking {
        withLibrary { library ->
            val store = LibraryDriveSync(library.database)
            val android = IosGoogleDriveSync.decode(ANDROID.encodeToByteArray())
            store.merge(android, "ios", 1)
            val db = library.database
            val favourite = db.getFavouritesDao().findAllEntriesIncludingDeleted().single()
            db.getFavouritesDao().upsert(favourite.copy(deletedAt = 500, updatedAt = 500))
            val history = db.getHistoryDao().findAllEntriesIncludingDeleted().single()
            db.getHistoryDao().upsertSync(history.copy(updatedAt = 600, page = 30))
            val upload = store.merge(android, "ios", 700)
            assertEquals(500L, upload.favourites.single().deletedAt)
            assertEquals(30, upload.history.single().page)
            val roundTrip = GoogleDriveSyncMerger.mergeSnapshots(android, upload)
            assertEquals(500L, roundTrip.favourites.single().deletedAt)
            assertEquals(30, roundTrip.history.single().page)
            db.getHistoryDao().upsertSync(history.copy(updatedAt = 800, deletedAt = 800))
            assertEquals(800L, store.merge(android, "ios", 900).history.single().deletedAt)
            val reopened = IosLibrary(path)
            try {
                assertTrue(reopened.favourites().isEmpty())
                assertTrue(reopened.history().isEmpty())
            } finally { reopened.close() }
        }
    }

    @Test
    fun identityCollisionRemapsRemoteStateWithoutOverwritingLocalWork() = runBlocking {
        withLibrary { library ->
            val android = IosGoogleDriveSync.decode(ANDROID.encodeToByteArray())
            val content = android.content.single()
            library.database.getMangaDao().upsert(content.toEntity().copy(url = "/different", publicUrl = "/different"))
            val upload = LibraryDriveSync(library.database).merge(android, "ios", 1000)
            val imported = upload.content.single()
            assertTrue(imported.id < 0)
            assertEquals(imported.id, upload.history.single().mangaId)
            assertEquals(imported.id, upload.favourites.single().mangaId)
            assertEquals("/different", library.database.getMangaDao().find(content.id)!!.manga.url)
        }
    }

    @Test
    fun invalidCategoryRollsBackWholeImportAndFutureProtocolIsRejected() = runBlocking {
        withLibrary { library ->
            val invalid = IosGoogleDriveSync.decode(ANDROID.replace("\"category_id\": 4", "\"category_id\": 99").encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { LibraryDriveSync(library.database).merge(invalid, "ios", 1) }
            assertTrue(library.categories().isEmpty())
            assertFalse(library.database.getMangaDao().contains(9007199254740993L))
            for (text in listOf("", "{}", "{broken", ANDROID.replace("\"schema\": 2", "\"schema\": 99"))) {
                assertFails { IosGoogleDriveSync.decode(text.encodeToByteArray()) }
            }
        }
    }

    @Test
    fun driveUsesAndroidFilenameAndOnlyWritesAfterSuccessfulReadAndVersionCheck() = runBlocking {
        withLibrary { library ->
            var lists = 0
            var writes = 0
            val client = HttpClient(MockEngine { request ->
                assertEquals("Bearer fixture-token", request.headers["Authorization"])
                when {
                    request.url.parameters["spaces"] == "appDataFolder" -> {
                        lists++
                        assertEquals("name = 'kototoro_sync_content_v3.json' and trashed = false", request.url.parameters["q"])
                        respond("""{"files":[{"id":"canonical","version":"1"}]}""")
                    }
                    request.url.parameters["alt"] == "media" -> respond(ANDROID)
                    request.method.value == "PATCH" -> {
                        writes++
                        assertEquals(2, lists)
                        val body = (request.body as OutgoingContent.ByteArrayContent).bytes()
                        val upload = IosGoogleDriveSync.decode(body)
                        assertEquals(9007199254740993L, upload.content.single().id)
                        assertEquals("apk-data", upload.extensions.single().payloadBase64)
                        respond("""{"id":"canonical"}""")
                    }
                    else -> error("Unexpected request: ${request.method} ${request.url.encodedPath}")
                }
            })
            try {
                assertTrue(library.googleDriveSync.synchronize(GoogleDriveSyncApi(client), "fixture-token", "ios").success)
                assertEquals(1, writes)
            } finally { client.close() }
        }
    }

    @Test
    fun repeatedRemoteConflictsAndUnreadableSnapshotsNeverUpload() = runBlocking {
        withLibrary { library ->
            for (payload in listOf(ANDROID, "{broken", "")) {
                var version = 0
                var writes = 0
                val client = HttpClient(MockEngine { request ->
                    when {
                        request.url.parameters["spaces"] == "appDataFolder" ->
                            respond("""{"files":[{"id":"canonical","version":"${++version}"}]}""")
                        request.url.parameters["alt"] == "media" -> respond(payload)
                        else -> { writes++; respond("{}", HttpStatusCode.BadRequest) }
                    }
                })
                try {
                    assertFails { library.googleDriveSync.synchronize(GoogleDriveSyncApi(client), "token", "ios") }
                    assertEquals(0, writes, "Must not mutate Drive on conflict or decode failure")
                } finally { client.close() }
            }
        }
    }

    @Test
    fun emptyDriveCreatesV3AndWorkV2IsOnlyUsedAsReadOnlySeed() = runBlocking {
        withLibrary { library ->
            for (legacy in listOf(false, true)) {
                var created = false
                var uploaded = false
                val client = HttpClient(MockEngine { request ->
                    when {
                        request.url.parameters["spaces"] == "appDataFolder" -> {
                            val old = request.url.parameters["q"]!!.contains("work_v2")
                            respond(if (legacy && old) """{"files":[{"id":"old","version":"1"}]}""" else """{"files":[]}""")
                        }
                        request.url.parameters["alt"] == "media" -> respond(ANDROID
                            .replace("kototoro.content.v3", "kototoro.work.v2").replace("\"schema\": 2", "\"schema\": 1"))
                        request.method.value == "POST" -> {
                            created = true
                            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                            assertContains(body, "kototoro_sync_content_v3.json")
                            assertContains(body, "appDataFolder")
                            respond("", headers = io.ktor.http.headersOf("Location",
                                "https://www.googleapis.com/upload/drive/v3/files?upload_id=fixture"))
                        }
                        request.method.value == "PUT" -> {
                            assertEquals("fixture", request.url.parameters["upload_id"])
                            val upload = IosGoogleDriveSync.decode((request.body as OutgoingContent.ByteArrayContent).bytes())
                            assertEquals(if (legacy) 1 else 0, upload.favourites.size)
                            uploaded = true
                            respond("""{"id":"new"}""")
                        }
                        else -> error("Unexpected mutation of legacy file")
                    }
                })
                try {
                    assertTrue(library.googleDriveSync.synchronize(GoogleDriveSyncApi(client), "token", "ios").success)
                    assertTrue(created && uploaded)
                } finally { client.close() }
            }
        }
    }

    private lateinit var path: String
    private suspend fun withLibrary(block: suspend (IosLibrary) -> Unit) {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        path = "$directory/library.db"
        val library = IosLibrary(path)
        try { block(library) }
        finally { library.close(); files.removeItemAtPath(directory, null) }
    }

    private companion object {
        // Android's on-wire names, required protocol markers, tombstones and large signed Long identifiers.
        const val ANDROID = """{
          "schema": 2, "namespace": "kototoro.content.v3", "semantic_schema": 1,
          "device_id":"android", "synced_at":300,
          "content":[{"id":9007199254740993,"title":"安卓作品","url":"/work/1","public_url":"https://source.invalid/work/1",
            "rating":0.0,"nsfw":false,"cover_url":"","source":"MIHON_123","content_type":"MANGA"}],
          "categories":[{"id":4,"created_at":1,"sort_key":0,"title":"来自安卓","order":"NEWEST","track":true,"show_in_lib":true}],
          "favourites":[{"manga_id":9007199254740993,"category_id": 4,"created_at":10,"updated_at":20}],
          "history":[{"manga_id":9007199254740993,"created_at":10,"updated_at":200,"chapter_id":9007199254740995,
            "page":7,"scroll":0.25,"percent":0.3,"chapters":12}],
          "stats":[{"manga_id":9007199254740993,"started_at":100,"duration":400,"pages":5}],
          "feed":{"tracks":[{"manga_id":9007199254740993,"last_check_time":250}],
            "logs":[{"manga_id":9007199254740993,"chapters":"chapter","created_at":250,"unread":true}]},
          "config":{"revision":42,"settings":{"theme":"dark"}},
          "repositories":[{"type":"MIHON","base_url":"https://repo.invalid","name":"Repo"}],
          "source_states":[{"source":"MIHON_123","enabled":true,"modified_at":123}],
          "json_sources":[{"id":"fixture","name":"CMS","type":"TVBOX","config":"{}"}],
          "extensions":[{"package_id":"fixture","name":"Extension","kind":"MIHON",
            "payload_base64":"apk-data","is_payload_included":true}]
        }"""
    }
}
