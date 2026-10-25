package org.skepsun.kototoro.desktop.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
 * The timer that replaces Android's periodic backup worker: it uploads only when the saved interval has passed, keeps
 * the moment of the last successful upload, and leaves a failed attempt on the books so the retry happens later.
 *
 * The clock is injected, so "six hours later" costs nothing and the tests never sleep.
 */
class DesktopWebDavAutoBackupTest {
    @TempDir lateinit var root: Path

    private val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
    private val content = SourceContent(
        id = 42L,
        title = "Timer",
        altTitles = emptySet(),
        url = "https://fixture.invalid/timer",
        publicUrl = "https://fixture.invalid/timer",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
        largeCoverUrl = "/timer",
    )
    private val settings = DesktopWebDavSettings(
        serverUrl = "https://fixture.test/dav/",
        remotePath = "backups",
        username = "me",
        password = "s3cret",
        maxCount = 2,
        intervalHours = 6,
    )

    private val clock = AtomicInteger(1_800_000_000)
    private val puts = AtomicInteger()

    /** Every request the operations make, so a local-only tick can prove it never touched the network. */
    private val requests = AtomicInteger()
    private var failUploads = false

    private fun hour(multiplier: Int) = 60L * 60 * 1000 * multiplier

    /** A server that accepts uploads, answers listings and can be told to refuse the next PUT. */
    private fun operations(runtime: DesktopRuntime, engine: MockEngine): DesktopWebDavOperations {
        val http = HttpClient(engine) { expectSuccess = true; followRedirects = false }
        val client = DesktopWebDavClient(WebDavBackupClient(http, http, ::parseWebDavResources))
        return DesktopWebDavOperations(runtime, DesktopLibraryBackup(runtime.database), client) {
            clock.get() * 1000L
        }
    }

    private fun engine(): MockEngine = MockEngine { request ->
        requests.incrementAndGet()
        when (request.method) {
            HttpMethod.Put -> {
                puts.incrementAndGet()
                if (failUploads) respond("nope", HttpStatusCode.InternalServerError) else respond("", HttpStatusCode.Created)
            }
            HttpMethod("PROPFIND") -> respond(
                """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"></D:multistatus>""",
                HttpStatusCode.MultiStatus,
            )
            else -> error("unexpected ${request.method}")
        }
    }

    @Test
    fun `without a destination the timer is off and the server is never touched`() = runBlocking {
        val runtime = DesktopRuntime.open(root.resolve("off"))
        val engine = engine()
        val operations = operations(runtime, engine)
        try {
            // No server and no local copy: there is nothing the timer could write, so it stays off.
            operations.settings(settings.copy(intervalHours = 0, keepLocalCopy = false))
            assertNull(operations.autoBackupDelay())
            assertEquals(DesktopAutoBackup.Disabled, operations.autoBackup())
            assertEquals(0, puts.get())
            assertEquals(0L, operations.ui.value.lastUploadMillis)
        } finally {
            engine.close()
            runtime.close()
        }
    }

    @Test
    fun `a local-only destination backs up on the timer without touching the network`() = runBlocking {
        val runtime = DesktopRuntime.open(root.resolve("local-only"))
        val engine = engine()
        DesktopLibrary(runtime.database) { source }.addFavourite(content)
        try {
            val operations = operations(runtime, engine)
            // Android's periodical worker writes the local copy on the same schedule, with no server configured.
            operations.settings(DesktopWebDavSettings(intervalHours = 1, keepLocalCopy = true))

            assertEquals(0L, operations.autoBackupDelay())
            assertEquals(DesktopAutoBackup.Saved, operations.autoBackup())
            assertEquals(0, puts.get())
            assertEquals(0, requests.get())
            assertEquals(runtime.paths.backups, operations.ui.value.localDirectory)
            assertEquals(1, operations.ui.value.localFiles.size)
            assertEquals("PK", Files.readAllBytes(operations.ui.value.localFiles.single()).take(2)
                .toByteArray().toString(Charsets.US_ASCII))
            assertEquals(0L, operations.ui.value.lastUploadMillis)

            // The local copy is the interval's baseline, so the next tick has to wait.
            assertEquals(hour(1), operations.autoBackupDelay())
            assertEquals(DesktopAutoBackup.NotDue, operations.autoBackup())
            clock.addAndGet((hour(1) / 1000).toInt())
            assertEquals(DesktopAutoBackup.Saved, operations.autoBackup())
            assertEquals(2, operations.ui.value.localFiles.size)
            assertEquals(0, requests.get())
        } finally {
            engine.close()
            runtime.close()
        }
    }

    @Test
    fun `a first tick uploads immediately and its time survives a restart`() = runBlocking {
        val runtime = DesktopRuntime.open(root.resolve("first"))
        val engine = engine()
        DesktopLibrary(runtime.database) { source }.addFavourite(content)
        try {
            val operations = operations(runtime, engine)
            operations.settings(settings)
            // Nothing was uploaded yet, so there is no interval to wait for.
            assertEquals(0L, operations.autoBackupDelay())
            assertEquals(DesktopAutoBackup.Uploaded, operations.autoBackup())
            assertEquals(1, puts.get())
            assertEquals(clock.get() * 1000L, operations.ui.value.lastUploadMillis)
            assertNull(operations.ui.value.error)

            // A new session reads the same destination and the same moment back from the preference store.
            val reopened = operations(runtime, engine)
            reopened.restore()
            assertEquals(clock.get() * 1000L, reopened.ui.value.lastUploadMillis)
            assertEquals(settings.intervalHours, reopened.ui.value.settings.intervalHours)
        } finally {
            engine.close()
            runtime.close()
        }
    }

    @Test
    fun `a tick inside the interval waits and a tick after it uploads again`() = runBlocking {
        val runtime = DesktopRuntime.open(root.resolve("interval"))
        val engine = engine()
        DesktopLibrary(runtime.database) { source }.addFavourite(content)
        try {
            val operations = operations(runtime, engine)
            operations.settings(settings)
            assertEquals(DesktopAutoBackup.Uploaded, operations.autoBackup())

            clock.addAndGet((hour(2) / 1000).toInt())
            assertEquals(hour(4), operations.autoBackupDelay())
            assertEquals(DesktopAutoBackup.NotDue, operations.autoBackup())
            assertEquals(1, puts.get())

            clock.addAndGet((hour(4) / 1000).toInt())
            assertEquals(0L, operations.autoBackupDelay())
            assertEquals(DesktopAutoBackup.Uploaded, operations.autoBackup())
            assertEquals(2, puts.get())
        } finally {
            engine.close()
            runtime.close()
        }
    }

    @Test
    fun `a refused upload keeps the old baseline and reports the server's answer`() = runBlocking {
        val runtime = DesktopRuntime.open(root.resolve("failure"))
        val engine = engine()
        DesktopLibrary(runtime.database) { source }.addFavourite(content)
        try {
            val operations = operations(runtime, engine)
            operations.settings(settings)
            failUploads = true
            assertEquals(DesktopAutoBackup.Failed, operations.autoBackup())
            val error = operations.ui.value.error
            assertNotNull(error)
            // The baseline did not move, so the next tick is due again instead of waiting a whole interval.
            assertEquals(0L, operations.ui.value.lastUploadMillis)
            assertEquals(0L, operations.autoBackupDelay())

            failUploads = false
            assertEquals(DesktopAutoBackup.Uploaded, operations.autoBackup())
            // The answers of the failed attempt do not count: only the successful tick moves the baseline.
            assertEquals(clock.get() * 1000L, operations.ui.value.lastUploadMillis)
            assertNull(operations.ui.value.error)
        } finally {
            engine.close()
            runtime.close()
        }
    }

    @Test
    fun `a half-filled form with the local copy off keeps the timer off`() = runBlocking {
        val runtime = DesktopRuntime.open(root.resolve("unconfigured"))
        val engine = engine()
        val operations = operations(runtime, engine)
        try {
            operations.settings(DesktopWebDavSettings(intervalHours = 6, keepLocalCopy = false))
            assertNull(operations.autoBackupDelay())
            assertEquals(DesktopAutoBackup.Disabled, operations.autoBackup())
            assertEquals(0, puts.get())
            assertEquals(0, requests.get())
        } finally {
            engine.close()
            runtime.close()
        }
    }

    @Test
    fun `the backup is written locally before the upload, so a refused upload still leaves a copy`() = runBlocking {
        val runtime = DesktopRuntime.open(root.resolve("local"))
        val engine = engine()
        DesktopLibrary(runtime.database) { source }.addFavourite(content)
        try {
            val operations = operations(runtime, engine)
            operations.settings(settings.copy(localCount = 1))
            failUploads = true

            assertEquals(DesktopAutoBackup.Failed, operations.autoBackup())

            // Android writes the local copy first; a server answering 500 must not cost the user the backup.
            val kept = operations.ui.value.localFiles
            assertEquals(1, kept.size)
            val name = kept.single().fileName.toString()
            assertTrue(Regex("""kototoro_\d{8}-\d{4}\.bk\.zip""").matches(name), name)
            assertEquals(runtime.paths.backups, operations.ui.value.localDirectory)
            assertEquals(clock.get() * 1000L, operations.ui.value.lastLocalMillis)
            // The copy is the real export archive, not an empty staging file.
            assertEquals("PK", Files.readAllBytes(kept.single()).take(2).toByteArray().toString(Charsets.US_ASCII))

            failUploads = false
            clock.addAndGet((hour(6) / 1000).toInt())
            assertEquals(DesktopAutoBackup.Uploaded, operations.autoBackup())
            // The local retention count is what trimmed: the older copy retired, the newest stayed.
            assertEquals(1, operations.ui.value.localFiles.size)
            assertTrue(operations.ui.value.localFiles.single().fileName.toString() != name)

            // The listing survives a restart, exactly like the upload baseline does.
            val reopened = operations(runtime, engine)
            reopened.restore()
            assertEquals(1, reopened.ui.value.localFiles.size)
            assertEquals(operations.ui.value.lastLocalMillis, reopened.ui.value.lastLocalMillis)
        } finally {
            engine.close()
            runtime.close()
        }
    }

    @Test
    fun `turning the local copy off uploads only`() = runBlocking {
        val runtime = DesktopRuntime.open(root.resolve("local-off"))
        val engine = engine()
        DesktopLibrary(runtime.database) { source }.addFavourite(content)
        try {
            val operations = operations(runtime, engine)
            operations.settings(settings.copy(keepLocalCopy = false))

            assertEquals(DesktopAutoBackup.Uploaded, operations.autoBackup())
            assertEquals(1, puts.get())
            assertEquals(emptyList<java.nio.file.Path>(), operations.ui.value.localFiles)
            assertEquals(0L, operations.ui.value.lastLocalMillis)
        } finally {
            engine.close()
            runtime.close()
        }
    }
}
