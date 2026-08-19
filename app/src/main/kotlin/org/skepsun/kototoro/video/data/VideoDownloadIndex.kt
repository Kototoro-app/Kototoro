package org.skepsun.kototoro.video.data

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

@Singleton
class VideoDownloadIndex @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val changesFlow = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    val changes: SharedFlow<Long>
        get() = changesFlow

    /**
     * 记录某章节已下载的视频位置。
     * @param value 可能是真实文件路径（file-writable 场景）、file:// URI 或 content:// URI（SAF 场景）。
     */
    fun put(mangaId: Long, chapterId: Long, path: String) {
        prefs.edit().putString(key(mangaId, chapterId), path).apply()
        changesFlow.tryEmit(mangaId)
    }

    fun remove(mangaId: Long, chapterId: Long) {
        prefs.edit().remove(key(mangaId, chapterId)).apply()
        changesFlow.tryEmit(mangaId)
    }

    /**
     * 返回已下载视频的 Uri（file:// 或 content://），文件不存在时返回 null。
     * 兼容遗留的纯路径存储。
     */
    fun getUri(mangaId: Long, chapterId: Long): Uri? {
        val value = prefs.getString(key(mangaId, chapterId), null) ?: return null
        val uri = toUriOrNull(value) ?: return null
        return uri.takeIf { exists(uri) }
    }

    /**
     * 仅当下载位置是真实本地文件时返回 File，否则返回 null（SAF content:// 场景请用 [getUri]）。
     */
    fun getFile(mangaId: Long, chapterId: Long): File? {
        val uri = getUri(mangaId, chapterId) ?: return null
        return if (uri.scheme.equals("file", ignoreCase = true)) {
            uri.path?.let(::File)?.takeIf { it.exists() && it.isFile }
        } else {
            null
        }
    }

    fun getDownloadedChapterIds(mangaId: Long): Set<Long> {
        val prefix = "$mangaId:"
        val result = LinkedHashSet<Long>()
        prefs.all.forEach { (k, v) ->
            if (!k.startsWith(prefix)) return@forEach
            val chapterId = k.substringAfter(prefix).toLongOrNull() ?: return@forEach
            val value = v as? String ?: return@forEach
            val uri = toUriOrNull(value) ?: return@forEach
            if (exists(uri)) {
                result.add(chapterId)
            } else {
                // 清理已失效的索引，避免误判为已下载
                prefs.edit().remove(k).apply()
            }
        }
        return result
    }

    private fun exists(uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "content" -> runCatching { UniFile.fromUri(context, uri)?.exists() == true }.getOrDefault(false)
        else -> {
            val file = if (uri.scheme.equals("file", ignoreCase = true)) {
                uri.path?.let(::File)
            } else {
                null
            }
            file?.let { it.exists() && it.isFile } == true
        }
    }

    /**
     * 把存储值解析为 Uri：带 scheme 的直接解析；遗留的纯文件路径用 [Uri.fromFile] 转义。
     */
    private fun toUriOrNull(value: String): Uri? = runCatching {
        if (value.contains("://")) {
            Uri.parse(value)
        } else {
            Uri.fromFile(File(value))
        }
    }.getOrNull()

    private fun key(mangaId: Long, chapterId: Long) = "$mangaId:$chapterId"

    private companion object {
        private const val PREFS_NAME = "video_download_index"
    }
}
