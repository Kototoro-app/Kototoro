package org.skepsun.kototoro.download.ui.worker

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import org.skepsun.kototoro.download.domain.DownloadByteProgress

internal suspend fun copyVideoWithProgress(
    source: BufferedSource,
    sink: BufferedSink,
    initialBytes: Long,
    totalBytes: Long?,
    onProgress: suspend (DownloadByteProgress) -> Unit,
) {
    var written = initialBytes
    var lastNotify = initialBytes
    onProgress(DownloadByteProgress(written, totalBytes))
    val buffer = Buffer()
    while (true) {
        val read = source.read(buffer, 64 * 1024)
        if (read == -1L) break
        sink.write(buffer, read)
        written += read
        if (written - lastNotify >= 256 * 1024) {
            lastNotify = written
            onProgress(DownloadByteProgress(written, totalBytes))
        }
    }
    sink.flush()
    onProgress(DownloadByteProgress(written, totalBytes ?: written))
}
