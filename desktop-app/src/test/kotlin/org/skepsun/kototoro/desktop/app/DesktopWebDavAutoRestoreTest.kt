package org.skepsun.kototoro.desktop.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.backups.webdav.parseWebDavResources
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.desktop.runtime.DesktopLibrary
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryBackup
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android's "auto restore on startup": one merged restore per calendar day, from the newest backup on the server, with
 * the merged snapshot pushed back afterwards.
 *
 * The interesting part is not the happy path but the bookkeeping — a day that found nothing counts as checked, a day
 * that failed does not — so most of these tests look at what the *next* start does rather than at one call.
 */
class DesktopWebDavAutoRestoreTest {
    @TempDir lateinit var root: Path

    private val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
    private val content = SourceContent(
        id = 7_000L,
        title = "Automatic restore",
        altTitles = emptySet(),
        url = "https://fixture.invalid/auto",
        publicUrl = "https://fixture.invalid/auto",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
        largeCoverUrl = "/auto",
    )

    /** A fixed afternoon, in seconds; the calendar-day guard is what decides "already checked". */
    private val noon = AtomicInteger(1_793_000_000)

    private val settings = DesktopWebDavSettings(
        serverUrl = "https://fixture.test/dav",
        remotePath = "backups",
        username = "me",
        password = "s3cret",
    )

    private val requests = AtomicInteger()
    private val puts = AtomicInteger()

    /** What the server holds: a `null` name means an empty directory, and [broken] answers every request with a 500. */
    private var archived: ByteArray? = null
    private var archivedName: String? = null
    private var broken = false

    private fun operations(runtime: DesktopRuntime, engine: MockEngine): DesktopWebDavOperations {
        val http = HttpClient(engine) { expectSuccess = true; followRedirects = false }
        val client = DesktopWebDavClient(WebDavBackupClient(http, http, ::parseWebDavResources))
        val operations = DesktopWebDavOperations(runtime, DesktopLibraryBackup(runtime.database), client) {
            noon.get() * 1000L
        }
        // The controller reads the saved destination before it starts the timer and this check.
        operations.restore()
        return operations
    }

    private fun engine(): MockEngine = MockEngine { request ->
        requests.incrementAndGet()
        if (broken) {
            respond("down", HttpStatusCode.InternalServerError)
        } else {
            when (request.method) {
                HttpMethod("PROPFIND") -> respond(listing(), HttpStatusCode.MultiStatus)
                HttpMethod.Get -> respond(requireNotNull(archived) { "download of an empty directory" })
                HttpMethod.Put -> {
                    puts.incrementAndGet()
                    archived = request.body.toByteArray()
                    archivedName = request.url.encodedPath.substringAfterLast('/')
                    respond("", HttpStatusCode.Created)
                }
                HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                else -> error("unexpected ${request.method}")
            }
        }
    }

    private fun listing(): String = archivedName?.let { name ->
        """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response>
            <D:href>/dav/backups/$name</D:href><D:propstat><D:prop>
            <D:displayname>$name</D:displayname>
            <D:getlastmodified>Tue, 20 Oct 2026 10:00:00 GMT</D:getlastmodified>
            <D:getcontentlength>${archived?.size ?: 0}</D:getcontentlength>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"""
    } ?: """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"/>"""

    /** Serves a real archive this module wrote, so the restore goes through the production merge path. */
    private suspend fun serveBackup(from: DesktopRuntime) {
        val path = root.resolve("served.bk.zip")
        DesktopLibraryBackup(from.database).export(path)
        archived = Files.readAllBytes(path)
        archivedName = "kototoro-v3-work-v1-20261020-100000.zip"
    }

    private fun DesktopAutoRestore.restored(): DesktopAutoRestore.Restored =
        this as? DesktopAutoRestore.Restored ?: fail<DesktopAutoRestore.Restored>("not a restore: $this")

    @Test
    fun `the newest server backup is merged once and pushed back`() = runBlocking {
        val remote = DesktopRuntime.open(root.resolve("remote"))
        val local = DesktopRuntime.open(root.resolve("local"))
        val engine = engine()
        try {
            DesktopLibrary(remote.database) { source }.addFavourite(content)
            serveBackup(remote)
            val operations = operations(local, engine)
            operations.settings(settings.copy(autoRestore = true))

            val result = operations.autoRestore().restored()
            assertEquals("kototoro-v3-work-v1-20261020-100000.zip", result.name)
            assertTrue(result.merged > 0)
            assertNotNull(result.uploaded, "the merged snapshot goes back to the server")
            assertEquals(1, puts.get())
            assertEquals(
                listOf(content.id),
                DesktopLibrary(local.database) { null }.favourites().map { it.id },
            )
            assertEquals(noon.get() * 1000L, operations.ui.value.lastAutoRestoreCheckMillis)

            // The same day does not ask the server again, which is the guard Android's runner keeps.
            val before = requests.get()
            assertEquals(DesktopAutoRestore.NotDue, operations.autoRestore())
            assertEquals(before, requests.get())

            // Tomorrow it merges again and pushes again: Android re-uploads after every successful restore so the
            // next device sees the union, even when nothing new arrived here.
            noon.addAndGet(24 * 60 * 60)
            val next = operations.autoRestore().restored()
            assertTrue(next.merged > 0)
            assertEquals(2, puts.get())
        } finally {
            engine.close()
            remote.close()
            local.close()
        }
    }

    @Test
    fun `an empty server still spends the day`() = runBlocking {
        val local = DesktopRuntime.open(root.resolve("empty"))
        val engine = engine()
        try {
            val operations = operations(local, engine)
            operations.settings(settings.copy(autoRestore = true))

            assertEquals(DesktopAutoRestore.Nothing, operations.autoRestore())
            assertEquals(noon.get() * 1000L, operations.ui.value.lastAutoRestoreCheckMillis)
            val before = requests.get()
            assertEquals(DesktopAutoRestore.NotDue, operations.autoRestore())
            assertEquals(before, requests.get())
        } finally {
            engine.close()
            local.close()
        }
    }

    @Test
    fun `a failed check is not counted as today`() = runBlocking {
        val remote = DesktopRuntime.open(root.resolve("retry-remote"))
        val local = DesktopRuntime.open(root.resolve("retry-local"))
        val engine = engine()
        try {
            DesktopLibrary(remote.database) { source }.addFavourite(content)
            serveBackup(remote)
            broken = true
            val operations = operations(local, engine)
            operations.settings(settings.copy(autoRestore = true))

            assertEquals(DesktopAutoRestore.Failed, operations.autoRestore())
            assertEquals(0L, operations.ui.value.lastAutoRestoreCheckMillis, "a failure must be retried")
            assertNotNull(operations.ui.value.error)

            // The next start of the same day tries again, because a failure never became a check.
            broken = false
            assertTrue(operations.autoRestore().restored().merged > 0)
        } finally {
            engine.close()
            remote.close()
            local.close()
        }
    }

    @Test
    fun `the switch needs a complete destination and its own consent`() = runBlocking {
        val local = DesktopRuntime.open(root.resolve("off"))
        val engine = engine()
        try {
            serveBackup(local)
            val operations = operations(local, engine)

            assertEquals(DesktopAutoRestore.Disabled, operations.autoRestore())
            operations.settings(settings.copy(serverUrl = "", autoRestore = true))
            assertEquals(DesktopAutoRestore.Disabled, operations.autoRestore())
            assertEquals(0, requests.get(), "a half-configured page must not touch the network")
            assertEquals(0L, operations.ui.value.lastAutoRestoreCheckMillis)
        } finally {
            engine.close()
            local.close()
        }
    }

    @Test
    fun `the check survives a restart`() = runBlocking {
        val path = root.resolve("restart")
        val first = DesktopRuntime.open(path)
        val engine = engine()
        try {
            val operations = operations(first, engine)
            operations.settings(settings.copy(autoRestore = true))
            assertEquals(DesktopAutoRestore.Nothing, operations.autoRestore())
            operations.close()
        } finally {
            first.close()
        }
        val second = DesktopRuntime.open(path)
        try {
            val reopened = operations(second, engine)
            val before = requests.get()
            assertEquals(DesktopAutoRestore.NotDue, reopened.autoRestore())
            assertEquals(before, requests.get())
            assertEquals(noon.get() * 1000L, reopened.ui.value.lastAutoRestoreCheckMillis)
            reopened.close()
        } finally {
            engine.close()
            second.close()
        }
    }
}
