package org.skepsun.kototoro.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.skepsun.kototoro.core.library.LibraryDriveSync
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncApi
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncApiException
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncMerger

class IosDriveSyncReport(val success: Boolean, val authorizationRequired: Boolean, val message: String)

/** OAuth stays in Swift/Keychain. No credential is persisted in shared code or the database. */
class IosGoogleDriveSync internal constructor(library: IosLibrary) {
    private val store = LibraryDriveSync(library.database)
    private val mutex = Mutex()

    @Throws(Exception::class)
    suspend fun sync(accessToken: String, deviceId: String): IosDriveSyncReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            val client = HttpClient(Darwin) {
                install(HttpTimeout) { connectTimeoutMillis = 30_000; requestTimeoutMillis = 120_000 }
            }
            try {
                synchronize(GoogleDriveSyncApi(client), accessToken, deviceId)
            } catch (error: GoogleDriveSyncApiException) {
                when (error.code) {
                    401 -> IosDriveSyncReport(false, true, "Google 授权已失效，请重新连接账号")
                    403 -> IosDriveSyncReport(false, false, "Google Drive 拒绝访问，请检查授权范围及 Drive API 配置")
                    else -> throw IllegalStateException("Google Drive 暂时无法同步（HTTP ${error.code}）")
                }
            } finally { client.close() }
        }
    }

    internal suspend fun synchronize(api: GoogleDriveSyncApi, token: String, deviceId: String): IosDriveSyncReport {
        require(token.isNotBlank() && deviceId.isNotBlank())
        repeat(3) {
            val files = api.findCurrentSyncFiles(token)
            val seeds = if (files.isEmpty()) api.findWorkV2SyncFiles(token) else emptyList()
            val remote = GoogleDriveSyncMerger.combine((files + seeds).map { decode(api.download(token, it.id)) })
            val upload = store.merge(remote, deviceId, kotlin.time.Clock.System.now().toEpochMilliseconds())
            // Re-list every input, including duplicates. Never overwrite after exhausting conflict retries.
            val latest = api.findCurrentSyncFiles(token)
            if (files.associate { it.id to it.version } != latest.associate { it.id to it.version }) return@repeat
            if (seeds.isNotEmpty() && seeds.associate { it.id to it.version } !=
                api.findWorkV2SyncFiles(token).associate { it.id to it.version }) return@repeat
            api.upload(token, json.encodeToString(GoogleDriveSyncSnapshot.serializer(), upload).encodeToByteArray(),
                files.firstOrNull()?.id)
            // Android owns settings/package installation. Do not delete its extra or legacy files.
            return IosDriveSyncReport(true, false, "收藏、分类、阅读进度和阅读统计已同步")
        }
        error("其他设备正在更新云端数据，请稍后重试")
    }

    internal companion object {
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            allowSpecialFloatingPointValues = true
            coerceInputValues = true
        }

        fun decode(bytes: ByteArray): GoogleDriveSyncSnapshot {
            val text = bytes.decodeToString()
            // Empty/truncated/future snapshots must block uploads, never be interpreted as an empty library.
            val probe = json.parseToJsonElement(text).jsonObject
            require(GoogleDriveSyncSnapshot.isSupportedProtocol(
                probe["schema"]?.jsonPrimitive?.intOrNull,
                probe["namespace"]?.jsonPrimitive?.content,
                probe["semantic_schema"]?.jsonPrimitive?.intOrNull,
            )) { "云端同步格式不受支持，请更新应用后重试" }
            return json.decodeFromString(GoogleDriveSyncSnapshot.serializer(), text)
        }
    }
}
