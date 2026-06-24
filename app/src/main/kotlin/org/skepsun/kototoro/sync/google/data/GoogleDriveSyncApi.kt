package org.skepsun.kototoro.sync.google.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncApiException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.inject.Inject

@Singleton
class GoogleDriveSyncApi @Inject constructor() {

	private val httpClient = OkHttpClient.Builder().apply {
		connectTimeout(15, TimeUnit.SECONDS)
		readTimeout(30, TimeUnit.SECONDS)
		writeTimeout(30, TimeUnit.SECONDS)
		callTimeout(60, TimeUnit.SECONDS)
		retryOnConnectionFailure(true)
	}.build()

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

	suspend fun findLegacySyncFiles(token: String): List<DriveFile> = findSyncFiles(token, LEGACY_FILE_NAME)

	private suspend fun findSyncFiles(token: String, fileName: String): List<DriveFile> = withContext(Dispatchers.IO) {
		val url = "$DRIVE_BASE/files".toHttpUrl().newBuilder()
			.addQueryParameter("spaces", "appDataFolder")
			.addQueryParameter("q", "name = '$fileName' and trashed = false")
			.addQueryParameter("fields", "files(id,name,createdTime,modifiedTime,version)")
			.addQueryParameter("orderBy", "createdTime")
			.addQueryParameter("pageSize", "100")
			.build()
		httpClient.executeWithRetry("list Drive sync files", Request.Builder().url(url).get().authorize(token).build())
			.parse<FileList>()
			?.files
			.orEmpty()
	}

	suspend fun getFileVersion(token: String, fileId: String): String? = withContext(Dispatchers.IO) {
		val url = "$DRIVE_BASE/files/$fileId".toHttpUrl().newBuilder()
			.addQueryParameter("fields", "version")
			.build()
		httpClient.executeWithRetry("get Drive sync file version", Request.Builder().url(url).get().authorize(token).build())
			.parse<FileVersion>()
			?.version
	}

	suspend fun download(token: String, fileId: String): ByteArray = withContext(Dispatchers.IO) {
		val url = "$DRIVE_BASE/files/$fileId".toHttpUrl().newBuilder()
			.addQueryParameter("alt", "media")
			.build()
		httpClient.executeWithRetry("download Drive sync file", Request.Builder().url(url).get().authorize(token).build())
			.use { response ->
				if (!response.isSuccessful) throw response.toError()
				response.body.bytes()
			}
	}

	suspend fun upload(token: String, content: ByteArray, fileId: String?): String = withContext(Dispatchers.IO) {
		val targetId = fileId ?: createEmptyFile(token)
		val url = "$UPLOAD_BASE/files/$targetId".toHttpUrl().newBuilder()
			.addQueryParameter("uploadType", "media")
			.addQueryParameter("fields", "id")
			.build()
		val request = Request.Builder()
			.url(url)
			.patch(content.toRequestBody(JSON_MEDIA_TYPE))
			.authorize(token)
			.build()
		httpClient.executeWithRetry("upload Drive sync file", request).parse<IdResponse>()?.id ?: targetId
	}

	suspend fun delete(token: String, fileId: String) = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url("$DRIVE_BASE/files/$fileId")
			.delete()
			.authorize(token)
			.build()
		httpClient.executeWithRetry("delete Drive sync file", request).use { response ->
			if (!response.isSuccessful && response.code != 404) throw response.toError()
		}
	}

	private fun createEmptyFile(token: String): String {
		val metadata = """{"name":"$CURRENT_FILE_NAME","parents":["appDataFolder"]}"""
		val url = "$DRIVE_BASE/files".toHttpUrl().newBuilder()
			.addQueryParameter("fields", "id")
			.build()
		val request = Request.Builder()
			.url(url)
			.post(metadata.toRequestBody(JSON_MEDIA_TYPE))
			.authorize(token)
			.build()
		return httpClient.executeWithRetry("create Drive sync file", request).parse<IdResponse>()?.id
			?: throw GoogleDriveSyncApiException(0, "Failed to create Google Drive sync file")
	}

	private fun Request.Builder.authorize(token: String) = header("Authorization", "Bearer $token")

	private fun OkHttpClient.executeWithRetry(operation: String, request: Request): Response {
		var lastError: IOException? = null
		repeat(MAX_NETWORK_ATTEMPTS) { attempt ->
			try {
				return newCall(request).execute()
			} catch (e: IOException) {
				lastError = e
				if (attempt == MAX_NETWORK_ATTEMPTS - 1) {
					throw IOException("Failed to $operation: ${e.message}", e)
				}
			}
		}
		throw IOException("Failed to $operation", lastError)
	}

	private inline fun <reified T> Response.parse(): T? = use { response ->
		if (!response.isSuccessful) throw response.toError()
		val text = response.body.string()
		if (text.isBlank()) null else json.decodeFromString<T>(text)
	}

	private fun Response.toError(): GoogleDriveSyncApiException {
		val bodyText = runCatching { body.string() }.getOrNull()?.takeIf { it.isNotBlank() }
		return GoogleDriveSyncApiException(code, "Drive API error $code: ${bodyText ?: message}")
	}

	private companion object {

		const val CURRENT_FILE_NAME = "kototoro_sync_work_v2.json"
		const val LEGACY_FILE_NAME = "kototoro_sync.json"
		const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
		const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
		const val MAX_NETWORK_ATTEMPTS = 2
		val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
	}
}
