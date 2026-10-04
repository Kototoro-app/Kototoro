package org.skepsun.kototoro.sync.google.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncApiException

/** Google Drive app-data protocol. The platform owns the supplied client's lifetime and engine configuration. */
class GoogleDriveSyncApi(private val httpClient: HttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Serializable
    class DriveFile(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String? = null,
        @SerialName("createdTime") val createdTime: String? = null,
        @SerialName("modifiedTime") val modifiedTime: String? = null,
        @SerialName("version") val version: String? = null,
    )

    @Serializable
    private class FileList(
        @SerialName("files") val files: List<DriveFile> = emptyList(),
    )

    @Serializable
    private class FileVersion(
        @SerialName("version") val version: String? = null,
    )

    @Serializable
    private class IdResponse(
        @SerialName("id") val id: String,
    )

    suspend fun findCurrentSyncFiles(token: String): List<DriveFile> = findSyncFiles(token, CURRENT_FILE_NAME)

    /** Snapshots written by versions before 2.2.0; read-only seeds so older devices keep syncing. */
    suspend fun findWorkV2SyncFiles(token: String): List<DriveFile> = findSyncFiles(token, WORK_V2_FILE_NAME)

    suspend fun findLegacySyncFiles(token: String): List<DriveFile> = findSyncFiles(token, LEGACY_FILE_NAME)

    private suspend fun findSyncFiles(token: String, fileName: String): List<DriveFile> {
        val request = request(token, "$DRIVE_BASE/files").apply {
            url.parameters.append("spaces", "appDataFolder")
            url.parameters.append("q", "name = '$fileName' and trashed = false")
            url.parameters.append("fields", "files(id,name,createdTime,modifiedTime,version)")
            url.parameters.append("orderBy", "createdTime")
            url.parameters.append("pageSize", "100")
        }
        return request.executeWithRetry("list Drive sync files") { it.parse<FileList>()?.files.orEmpty() }
    }

    suspend fun getFileVersion(token: String, fileId: String): String? {
        val request = request(token, "$DRIVE_BASE/files/$fileId").apply {
            url.parameters.append("fields", "version")
        }
        return request.executeWithRetry("get Drive sync file version") { it.parse<FileVersion>()?.version }
    }

    suspend fun download(token: String, fileId: String): ByteArray {
        val request = request(token, "$DRIVE_BASE/files/$fileId").apply {
            url.parameters.append("alt", "media")
        }
        return request.executeWithRetry("download Drive sync file") { response ->
            if (!response.status.isSuccess()) throw response.toError()
            response.body<ByteArray>()
        }
    }

    suspend fun upload(token: String, content: ByteArray, fileId: String?): String {
        val targetId = fileId ?: createEmptyFile(token)
        val request = request(token, "$UPLOAD_BASE/files/$targetId", HttpMethod.Patch).apply {
            url.parameters.append("uploadType", "media")
            url.parameters.append("fields", "id")
            contentType(JSON_MEDIA_TYPE)
            setBody(content)
        }
        return request.executeWithRetry("upload Drive sync file") { it.parse<IdResponse>()?.id ?: targetId }
    }

    suspend fun delete(token: String, fileId: String) {
        request(token, "$DRIVE_BASE/files/$fileId", HttpMethod.Delete)
            .executeWithRetry("delete Drive sync file") { response ->
                if (!response.status.isSuccess() && response.status.value != 404) throw response.toError()
            }
    }

    private suspend fun createEmptyFile(token: String): String {
        val metadata = """{"name":"$CURRENT_FILE_NAME","parents":["appDataFolder"]}"""
        val request = request(token, "$DRIVE_BASE/files", HttpMethod.Post).apply {
            url.parameters.append("fields", "id")
            contentType(JSON_MEDIA_TYPE)
            setBody(metadata.encodeToByteArray())
        }
        return request.executeWithRetry("create Drive sync file") { it.parse<IdResponse>()?.id }
            ?: throw GoogleDriveSyncApiException(0, "Failed to create Google Drive sync file")
    }

    private fun request(token: String, endpoint: String, method: HttpMethod = HttpMethod.Get): HttpRequestBuilder =
        HttpRequestBuilder().apply {
            url(endpoint)
            this.method = method
            header("Authorization", "Bearer $token")
            expectSuccess = false
        }

    private suspend fun <T> HttpRequestBuilder.executeWithRetry(
        operation: String,
        readResponse: suspend (HttpResponse) -> T,
    ): T {
        var lastError: IOException? = null
        repeat(MAX_NETWORK_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            var receivedResponse = false
            try {
                // Scoped execution releases the response even when reading or decoding fails.
                return httpClient.prepareRequest(this).execute { response ->
                    receivedResponse = true
                    readResponse(response)
                }
            } catch (e: IOException) {
                // Legacy retries cover sending/receiving headers, never body reads or HTTP errors.
                if (receivedResponse) throw e
                currentCoroutineContext().ensureActive()
                lastError = e
                if (attempt == MAX_NETWORK_ATTEMPTS - 1) {
                    throw IOException("Failed to $operation: ${e.message}", e)
                }
            }
        }
        throw IOException("Failed to $operation", lastError)
    }

    private suspend inline fun <reified T> HttpResponse.parse(): T? {
        if (!status.isSuccess()) throw toError()
        val text = bodyAsText()
        return if (text.isBlank()) null else json.decodeFromString<T>(text)
    }

    private suspend fun HttpResponse.toError(): GoogleDriveSyncApiException {
        val bodyText = try {
            bodyAsText().takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return GoogleDriveSyncApiException(
            status.value,
            "Drive API error ${status.value}: ${bodyText ?: status.description}",
        )
    }

    private companion object {
        const val CURRENT_FILE_NAME = "kototoro_sync_content_v3.json"
        const val WORK_V2_FILE_NAME = "kototoro_sync_work_v2.json"
        const val LEGACY_FILE_NAME = "kototoro_sync.json"
        const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        const val MAX_NETWORK_ATTEMPTS = 2
        val JSON_MEDIA_TYPE = ContentType.parse("application/json; charset=UTF-8")
    }
}
