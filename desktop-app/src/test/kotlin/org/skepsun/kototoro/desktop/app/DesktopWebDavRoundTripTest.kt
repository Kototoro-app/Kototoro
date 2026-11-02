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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.backups.webdav.parseWebDavResources
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.desktop.runtime.DesktopLibrary
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryBackup
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import java.nio.file.Files
import java.nio.file.Path

/**
 * The whole WebDAV route through the app's own code: the real library export, the real upload streaming, a download
 * back to disk and a restore into a second profile. Only the server is scripted, so a mistake in the staging files,
 * the remote name or the retention count shows up here instead of in a user's library.
 */
class DesktopWebDavRoundTripTest {
    @TempDir lateinit var root: Path

    private val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
    private val content = SourceContent(
        id = Long.MAX_VALUE,
        title = "Windows WebDAV backup",
        altTitles = emptySet(),
        url = "https://fixture.invalid/work",
        publicUrl = "https://fixture.invalid/work",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
        largeCoverUrl = "/work",
        chapters = listOf(SourceChapter(Long.MIN_VALUE, "chapter", 1f, 0, "/chapter", null, 0, null, source)),
    )

    @Test
    fun `a library uploads and comes back as an archive the restore flow accepts`() = runBlocking {
        val requestLog = mutableListOf<String>()
        var uploaded: ByteArray? = null
        var uploadedName: String? = null
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Put -> {
                    val body = request.body.toByteArray()
                    uploaded = body
                    uploadedName = request.url.encodedPath.substringAfterLast('/')
                    requestLog += "PUT ${request.url.encodedPath}"
                    respond("", HttpStatusCode.Created)
                }
                HttpMethod("PROPFIND") -> {
                    val name = uploadedName
                    requestLog += "PROPFIND ${request.url.encodedPath}"
                    val listing = if (name == null) {
                        """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"/>"""
                    } else {
                        """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response>
                            <D:href>/dav/backups/$name</D:href><D:propstat><D:prop>
                            <D:displayname>$name</D:displayname>
                            <D:getlastmodified>Thu, 22 Oct 2026 09:00:00 GMT</D:getlastmodified>
                            <D:getcontentlength>${uploaded?.size ?: 0}</D:getcontentlength>
                            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"""
                    }
                    respond(listing, HttpStatusCode.MultiStatus)
                }
                HttpMethod.Get -> {
                    requestLog += "GET ${request.url.encodedPath}"
                    respond(requireNotNull(uploaded) { "download before upload" })
                }
                else -> error("unexpected ${request.method}")
            }
        }
        val http = HttpClient(engine) { expectSuccess = true; followRedirects = false }
        val client = DesktopWebDavClient(WebDavBackupClient(http, http, ::parseWebDavResources))
        val first = DesktopRuntime.open(root.resolve("first"))
        val second = DesktopRuntime.open(root.resolve("second"))
        try {
            DesktopLibrary(first.database) { source }.addFavourite(content)
            val operations = DesktopWebDavOperations(first, DesktopLibraryBackup(first.database), client)
            try {
                operations.settings(
                    DesktopWebDavSettings(serverUrl = "https://fixture.test/dav", remotePath = "backups", maxCount = 2),
                )
                val result = operations.upload()

                assertEquals(uploadedName, result.name)
                assertTrue(result.name.startsWith("kototoro-v3-work-v1-"), result.name)
                assertTrue(result.removed.isEmpty())
                val body = requireNotNull(uploaded)
                assertEquals(body.size.toLong(), result.size)

                // The listing the upload left behind is what the page offers to restore.
                val listed = operations.ui.value
                assertTrue(listed.listed)
                assertEquals(listOf(result.name), listed.files.map { it.name })
                assertEquals(result.name, listed.latest?.name)

                val file = requireNotNull(listed.latest)
                val downloaded = operations.download(file)
                try {
                    assertTrue(Files.exists(downloaded))
                    val preview = DesktopLibraryBackup(second.database).preview(downloaded)
                    val processed = preview.use { archive ->
                        assertEquals(1, archive.counts[BackupSection.FAVOURITES])
                        assertNotNull(archive.index)
                        // Restoring is the existing merge, not a second implementation.
                        DesktopLibraryBackup(second.database).restore(archive)
                    }
                    assertTrue(processed > 0)
                } finally {
                    Files.deleteIfExists(downloaded)
                }
                assertEquals(
                    listOf(content.id),
                    DesktopLibrary(second.database) { null }.favourites().map { it.id },
                )
                // Staging holds nothing after the call that created it.
                assertEquals(emptyList<String>(), Files.list(first.paths.root.resolve("webdav")).use { stream ->
                    stream.map { it.fileName.toString() }.toList()
                })
                assertTrue(requestLog.first() == "PUT /dav/backups/${result.name}", requestLog.toString())
            } finally {
                operations.close()
            }
        } finally {
            first.close()
            second.close()
            http.close()
            engine.close()
        }
    }
}
