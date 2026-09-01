package org.skepsun.kototoro.download.ui.worker

import android.util.Log
import org.skepsun.kototoro.core.exceptions.StorageWriteException
import org.skepsun.kototoro.core.prefs.DownloadFormat
import org.skepsun.kototoro.local.data.LocalStorageRoot
import org.skepsun.kototoro.local.data.output.LocalContentOutput
import org.skepsun.kototoro.parsers.model.Content
import java.io.File

/**
 * 依次尝试候选根目录，直到某个根**真的**能创建出输出文件/目录。
 *
 * UniFile 的 createFile/createDirectory 失败时返回 null（SAF 授权被系统回收、只读卷、空间不足、
 * 名称非法），而 `LocalStorageRoot.isWriteable()` 对 SAF 树 URI 只查元数据标志位，授权失效后
 * 仍可能报 true —— 所以只能真的试建一次才算数（issue #511 的主因：用户换任何图源都失败，
 * 却只看到一句 "Required value was null."）。
 *
 * 全部候选都失败时抛出首个 [StorageWriteException]，其中带着根目录与目标文件名，
 * UI 侧经 `Throwable.getDisplayMessage` 显示成可操作的存储提示。
 */
internal suspend fun createOutputWithFallback(
    candidates: List<LocalStorageRoot>,
    manga: Content,
    format: DownloadFormat,
    cacheDir: File,
): LocalContentOutput {
    var firstError: StorageWriteException? = null
    for (root in candidates) {
        try {
            return LocalContentOutput.getOrCreate(
                root = root,
                manga = manga,
                format = format,
                cacheDir = cacheDir,
            )
        } catch (e: StorageWriteException) {
            if (firstError == null) {
                firstError = e
            }
            val path = runCatching { root.displayPath }.getOrNull()
            Log.w(TAG, "Download root \"$path\" is not usable, trying the next candidate", e)
        }
    }
    throw firstError ?: IllegalStateException("No download storage candidates available")
}

private const val TAG = "DownloadWorker"
