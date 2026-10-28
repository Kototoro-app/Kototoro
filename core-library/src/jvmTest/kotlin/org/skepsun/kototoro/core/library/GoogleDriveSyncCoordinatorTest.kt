package org.skepsun.kototoro.core.library

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncApi
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.domain.DriveSyncProtocolException
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleDriveSyncCoordinatorTest {
    private val snapshot = GoogleDriveSyncCodec.encode(GoogleDriveSyncSnapshot(deviceId = "android", syncedAt = 5))

    @Test
    fun `all hosts read merge and version check before uploading`() = runBlocking {
        var lists = 0
        var uploads = 0
        val stages = mutableListOf<DriveSyncStage>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer token", request.headers["Authorization"])
            when {
                request.url.parameters["spaces"] == "appDataFolder" -> {
                    lists++
                    respond("""{"files":[{"id":"main","version":"1"},{"id":"duplicate","version":"2"}]}""")
                }
                request.url.parameters["alt"] == "media" -> respond(snapshot)
                request.method == HttpMethod.Patch -> {
                    assertEquals(2, lists)
                    val upload = GoogleDriveSyncCodec.decode((request.body as OutgoingContent.ByteArrayContent).bytes())
                    assertEquals("desktop", upload.deviceId)
                    uploads++
                    respond("""{"id":"main"}""")
                }
                else -> error("Unexpected mutation")
            }
        })
        try {
            val coordinator = GoogleDriveSyncCoordinator({ remote, device, now ->
                assertEquals("android", remote!!.deviceId)
                assertEquals(42L, now)
                GoogleDriveSyncSnapshot(deviceId = device, syncedAt = now)
            }, { 42 })
            val report = coordinator.sync(GoogleDriveSyncApi(http), "token", "desktop", stages::add)
            assertEquals(listOf("duplicate"), report.duplicateFileIds)
            assertEquals(1, uploads)
            assertEquals(DriveSyncStage.entries.toList(), stages)
        } finally { http.close() }
    }

    @Test
    fun `changing any duplicate exhausts retries without uploading`() = runBlocking {
        var lists = 0
        var merges = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["spaces"] == "appDataFolder" -> respond(
                    """{"files":[{"id":"main","version":"1"},{"id":"duplicate","version":"${++lists}"}]}""")
                request.url.parameters["alt"] == "media" -> respond(snapshot)
                else -> error("A conflicting snapshot must never be uploaded or deleted")
            }
        })
        try {
            val coordinator = GoogleDriveSyncCoordinator({ remote, _, _ -> merges++; remote!! })
            assertFailsWith<DriveSyncConflictException> { coordinator.sync(GoogleDriveSyncApi(http), "token", "desktop") }
            assertEquals(3, merges)
            assertEquals(6, lists)
        } finally { http.close() }
    }

    @Test
    fun `empty corrupt and future snapshots block local merge and upload`() = runBlocking {
        for (payload in listOf("", "{}", "{broken", snapshot.decodeToString().replace("\"schema\":2", "\"schema\":99"))) {
            val http = HttpClient(MockEngine { request ->
                when {
                    request.url.parameters["spaces"] == "appDataFolder" ->
                        respond("""{"files":[{"id":"main","version":"1"}]}""")
                    request.url.parameters["alt"] == "media" -> respond(payload)
                    else -> error("Invalid input must never be uploaded")
                }
            })
            try {
                val coordinator = GoogleDriveSyncCoordinator({ _, _, _ -> error("Invalid input must not be merged") })
                assertFailsWith<DriveSyncProtocolException> { coordinator.sync(GoogleDriveSyncApi(http), "token", "desktop") }
            } finally { http.close() }
        }
    }

    @Test
    fun `cancellation releases the shared lock and leaves remote data untouched`() = runBlocking {
        var reads = 0
        val http = HttpClient(MockEngine {
            reads++
            throw CancellationException("fixture")
        })
        try {
            val coordinator = GoogleDriveSyncCoordinator({ _, _, _ -> error("Cancelled") })
            repeat(2) {
                assertFailsWith<CancellationException> { coordinator.sync(GoogleDriveSyncApi(http), "token", "desktop") }
            }
            assertEquals(2, reads)
        } finally { http.close() }
    }
}
