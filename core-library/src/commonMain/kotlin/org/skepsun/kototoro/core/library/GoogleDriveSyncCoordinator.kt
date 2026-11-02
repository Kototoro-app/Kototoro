package org.skepsun.kototoro.core.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncApi
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncCodec
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncMerger

enum class DriveSyncStage { DOWNLOAD, MERGE, UPLOAD }

class DriveSyncConflictException : IllegalStateException("Other devices are updating Google Drive; try again later")

/** Only successfully read duplicate files may be retired by a platform that owns remote cleanup. */
class DriveSyncUpload(val fileId: String, val duplicateFileIds: List<String>)

/**
 * One protocol flow for Android, desktop and iOS. Authorization, scheduling and HTTP engines belong to the hosts.
 * [mergeLocal] owns its local transaction; no database transaction is held during a network request.
 */
class GoogleDriveSyncCoordinator(
    private val mergeLocal: suspend (GoogleDriveSyncSnapshot?, String, Long) -> GoogleDriveSyncSnapshot,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()

    suspend fun sync(
        api: GoogleDriveSyncApi,
        token: String,
        deviceId: String,
        onStage: (DriveSyncStage) -> Unit = {},
    ): DriveSyncUpload = mutex.withLock {
        require(token.isNotBlank() && deviceId.isNotBlank())
        repeat(3) {
            onStage(DriveSyncStage.DOWNLOAD)
            val files = api.findCurrentSyncFiles(token)
            // Older work snapshots remain read-only so older installations can continue using them.
            val seeds = if (files.isEmpty()) api.findWorkV2SyncFiles(token) else emptyList()
            val remote = GoogleDriveSyncMerger.combine((files + seeds).map {
                GoogleDriveSyncCodec.decode(api.download(token, it.id))
            })
            onStage(DriveSyncStage.MERGE)
            val upload = mergeLocal(remote, deviceId, clock())
            onStage(DriveSyncStage.UPLOAD)
            // Check every input, including newly created duplicates. Exhaustion must never force an overwrite.
            if (versions(files) != versions(api.findCurrentSyncFiles(token))) return@repeat
            if (files.isEmpty() && versions(seeds) != versions(api.findWorkV2SyncFiles(token))) return@repeat
            val id = api.upload(token, GoogleDriveSyncCodec.encode(upload), files.firstOrNull()?.id)
            return@withLock DriveSyncUpload(id, files.map { it.id }.filter { it != id })
        }
        throw DriveSyncConflictException()
    }

    private fun versions(files: List<GoogleDriveSyncApi.DriveFile>) = files.associate { it.id to it.version }
}
