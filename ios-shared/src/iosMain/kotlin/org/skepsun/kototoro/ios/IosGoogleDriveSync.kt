package org.skepsun.kototoro.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.library.LibraryDriveSync
import org.skepsun.kototoro.core.library.GoogleDriveSyncCoordinator
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncApi
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncApiException
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncCodec

class IosDriveSyncReport(val success: Boolean, val authorizationRequired: Boolean, val message: String)

/** OAuth stays in Swift/Keychain. No credential is persisted in shared code or the database. */
class IosGoogleDriveSync internal constructor(library: IosLibrary) {
    private val store = LibraryDriveSync(library.database)
    private val coordinator = GoogleDriveSyncCoordinator(store::merge)
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
                    401 -> IosDriveSyncReport(false, true, localized("Google 授权已失效，请重新连接账号"))
                    403 -> IosDriveSyncReport(false, false, localized("Google Drive 拒绝访问，请检查授权范围及 Drive API 配置"))
                    else -> throw IllegalStateException(localized("Google Drive 暂时无法同步（HTTP %@）", error.code))
                }
            } finally { client.close() }
        }
    }

    internal suspend fun synchronize(api: GoogleDriveSyncApi, token: String, deviceId: String): IosDriveSyncReport {
        coordinator.sync(api, token, deviceId)
        return IosDriveSyncReport(true, false, localized("收藏、分类、阅读进度和阅读统计已同步"))
    }

    internal companion object {
        val json = GoogleDriveSyncCodec.json

        fun decode(bytes: ByteArray): GoogleDriveSyncSnapshot = GoogleDriveSyncCodec.decode(bytes)
    }
}
