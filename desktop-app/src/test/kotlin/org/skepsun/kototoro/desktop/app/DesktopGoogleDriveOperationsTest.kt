package org.skepsun.kototoro.desktop.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncCodec
import java.nio.file.Files
import java.nio.file.Path

class DesktopGoogleDriveOperationsTest {
    @TempDir lateinit var directory: Path

    private class Credentials : DesktopDriveCredentialStore {
        var value: String? = Json.encodeToString(DesktopDriveCredential.serializer(), DesktopDriveCredential(
            DESKTOP_GOOGLE_CLIENT_ID, "account", "fixture@example.test", "fixture-refresh", "fixture-access", Long.MAX_VALUE))
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    @Test
    fun `incomplete initial login does not expose connected account state or request endless reauthorization`() = runBlocking {
        val http = HttpClient(MockEngine { error("Missing client secret must not contact Google") })
        try {
            DesktopRuntime.open(directory.resolve("missing-client-secret")).use { runtime ->
                val credentials = Credentials().apply { value = null }
                var browserOpened = false
                val operations = DesktopGoogleDriveOperations(runtime, http, credentials,
                    openBrowser = { browserOpened = true; true }, clientSecret = "")
                operations.restore()
                assertFalse(operations.signIn())
                assertFalse(browserOpened)
                assertFalse(operations.ui.value.signedIn)
                assertFalse(operations.ui.value.authorizationRequired)
                assertEquals(DesktopDriveFailure.CLIENT_SECRET, operations.ui.value.failure)
                assertNull(credentials.value)
            }
        } finally { http.close() }
    }

    @Test
    fun `missing credentials allow the saved backend to be disabled without authorizing another account`() = runBlocking {
        val http = HttpClient(MockEngine { error("Disabling a backend must not contact Google") })
        try {
            DesktopRuntime.open(directory.resolve("missing-credential")).use { runtime ->
                runtime.preferences.open("desktop_google_drive").edit(SourcePreferenceEdit(changes = mapOf(
                    "enabled" to SourcePreferenceValue.Toggle(true),
                )))
                val credentials = Credentials().apply { value = null }
                val operations = DesktopGoogleDriveOperations(runtime, http, credentials)
                assertTrue(operations.restore())
                assertTrue(operations.ui.value.authorizationRequired)
                assertFalse(operations.ui.value.signedIn)
                assertTrue(operations.settings(enabled = false))
                assertFalse(operations.ui.value.enabled)
                assertNull(operations.nextSyncDelay())
            }
        } finally { http.close() }
    }

    @Test
    fun `macOS native credential bridge can look up an absent isolated account without persisting anything`() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        assertNull(desktopDriveCredentialStore(directory.resolve("native-read-only")).read())
    }

    @Test
    fun `Android library round trips through desktop without losing sections or resurrecting deletions`() = runBlocking {
        val uploads = mutableListOf<GoogleDriveSyncSnapshot>()
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["spaces"] == "appDataFolder" ->
                    respond("""{"files":[{"id":"main","version":"1"}]}""")
                request.url.parameters["alt"] == "media" -> respond(ANDROID)
                request.method == HttpMethod.Patch -> {
                    uploads += GoogleDriveSyncCodec.decode((request.body as OutgoingContent.ByteArrayContent).bytes())
                    respond("""{"id":"main"}""")
                }
                else -> error("Desktop must not change settings, legacy files or extension packages")
            }
        })
        try {
            DesktopRuntime.open(directory.resolve("library")).use { runtime ->
                val credentials = Credentials()
                val operations = DesktopGoogleDriveOperations(runtime, http, credentials, clock = { 1_000_000 })
                assertTrue(operations.restore())
                assertTrue(operations.settings(enabled = true))
                assertTrue(operations.sync())
                val upload = uploads.single()
                assertEquals(9007199254740993L, upload.content.single().id)
                assertEquals(9007199254740995L, upload.history.single().chapterId)
                assertEquals(7, upload.history.single().page)
                assertEquals("apk-data", upload.extensions.single().payloadBase64)
                assertEquals("https://repo.invalid", upload.repositories.single().baseUrl)
                assertEquals(42L, upload.config?.revision)
                assertEquals("fixture", upload.jsonSources.single().id)
                assertEquals(123L, upload.sourceStates.single().modifiedAt)
                assertEquals(1, upload.stats.size)
                assertEquals(1, upload.feed.logs.size)

                runtime.database.getFavouritesDao().delete(9007199254740993L)
                runtime.database.getHistoryDao().delete(9007199254740993L)
                assertTrue(operations.sync())
                assertTrue(uploads.last().favourites.single().deletedAt > 0)
                assertTrue(uploads.last().history.single().deletedAt > 0)

                // Local preference files contain only scheduling metadata, never credentials.
                Files.walk(runtime.paths.preferences).use { files ->
                    files.filter(Files::isRegularFile).forEach { path ->
                        val text = Files.readString(path)
                        assertFalse(text.contains("fixture-access") || text.contains("fixture-refresh"))
                    }
                }
                assertTrue(operations.disconnect())
                assertNull(credentials.value)
                assertFalse(operations.ui.value.enabled)
                assertTrue(runtime.database.getMangaDao().contains(9007199254740993L))
                operations.close()
            }
        } finally { http.close() }
    }

    @Test
    fun `401 refreshes once and a rejected refresh requests reauthorization with backoff stopped`() = runBlocking {
        var requests = 0
        var refreshes = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.host == "oauth2.googleapis.com") {
                refreshes++
                respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest)
            } else {
                requests++
                respond("{}", HttpStatusCode.Unauthorized)
            }
        })
        try {
            DesktopRuntime.open(directory.resolve("unauthorized")).use { runtime ->
                val operations = DesktopGoogleDriveOperations(runtime, http, Credentials(), clock = { 1_000_000 },
                    clientSecret = "fixture-client-secret")
                operations.restore()
                operations.settings(enabled = true)
                assertFalse(operations.sync())
                assertEquals(1, requests)
                assertEquals(1, refreshes)
                assertTrue(operations.ui.value.authorizationRequired)
                assertNull(operations.nextSyncDelay())
                assertFalse(operations.ui.value.syncing)
                assertEquals(0L, operations.ui.value.lastSync)
                assertFalse(operations.sync())
                assertEquals(1, requests)
            }
        } finally { http.close() }
    }

    @Test
    fun `failed snapshot blocks uploads and scheduler uses a ten minute retry delay`() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["spaces"] == "appDataFolder" ->
                    respond("""{"files":[{"id":"main","version":"1"}]}""")
                request.url.parameters["alt"] == "media" -> respond("{broken")
                else -> error("Corrupt remote data must never be overwritten")
            }
        })
        try {
            DesktopRuntime.open(directory.resolve("broken")).use { runtime ->
                val operations = DesktopGoogleDriveOperations(runtime, http, Credentials(), clock = { 1_000_000 },
                    clientSecret = "fixture-client-secret")
                operations.restore()
                operations.settings(enabled = true)
                assertFalse(operations.sync())
                assertEquals(DesktopDriveFailure.PROTOCOL, operations.ui.value.failure)
                assertEquals(600_000L, operations.nextSyncDelay())
                assertEquals(0L, operations.ui.value.lastSync)
                assertTrue(runtime.database.getHistoryDao().findAllEntriesIncludingDeleted().isEmpty())
            }
        } finally { http.close() }
    }

    companion object {
        private const val ANDROID = """{
          "schema":2,"namespace":"kototoro.content.v3","semantic_schema":1,"device_id":"android","synced_at":300,
          "content":[{"id":9007199254740993,"title":"Android work","url":"/work/1","public_url":"https://source.invalid/work/1",
            "rating":0.0,"nsfw":false,"cover_url":"","source":"MIHON_123","content_type":"MANGA"}],
          "categories":[{"id":4,"created_at":1,"sort_key":0,"title":"From Android","order":"NEWEST","track":true,"show_in_lib":true}],
          "favourites":[{"manga_id":9007199254740993,"category_id":4,"created_at":10,"updated_at":20}],
          "history":[{"manga_id":9007199254740993,"created_at":10,"updated_at":200,"chapter_id":9007199254740995,
            "page":7,"scroll":0.25,"percent":0.3,"chapters":12}],
          "stats":[{"manga_id":9007199254740993,"started_at":100,"duration":400,"pages":5}],
          "feed":{"tracks":[{"manga_id":9007199254740993,"last_check_time":250}],
            "logs":[{"manga_id":9007199254740993,"chapters":"chapter","created_at":250,"unread":true}]},
          "config":{"revision":42,"settings":{"theme":"dark"}},
          "repositories":[{"type":"MIHON","base_url":"https://repo.invalid","name":"Repo"}],
          "source_states":[{"source":"MIHON_123","enabled":true,"modified_at":123}],
          "json_sources":[{"id":"fixture","name":"CMS","type":"TVBOX","config":"{}"}],
          "extensions":[{"package_id":"fixture","name":"Extension","kind":"MIHON","payload_base64":"apk-data","is_payload_included":true}]
        }"""
    }
}
