package org.skepsun.kototoro.local.domain.model

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.skepsun.kototoro.core.util.ext.contains
import org.skepsun.kototoro.core.util.ext.creationTime
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentTag
import java.io.File

internal fun String.isLocalChapterUrl(): Boolean = when (substringBefore(':', "").lowercase()) {
    "file", "zip", "cbz", "file+zip", "content", "content+zip",
    "file+pdf", "content+pdf", "epub", "localepub" -> true
    else -> false
}

data class LocalContent(
    val manga: Content,
    val file: File = manga.url.toUri().let { uri ->
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path) { "File uri path is null: $uri" })
        } else {
            File(uri.schemeSpecificPart)
        }
    },
    val storageUri: Uri? = null,
) {

    /**
     * 由索引层用 SAF(UniFile) 解析出的真实修改时间，-1 表示尚未解析。
     *
     * `manga.url` 在目录通过 SAF 授权时是 `content://`，此时 [file] 是一条无效路径，
     * 直接读文件时间只会得到 0——这也是"添加时间"排序看起来完全没反应的原因。
     */
    var modifiedAt: Long = -1L
        internal set

    var createdAt: Long = -1L
        internal set
        get() {
            if (field == -1L) {
                field = if (modifiedAt > 0L) {
                    modifiedAt
                } else {
                    // 普通 file:// 路径：lastModified 在所有存储卷上都可用，且与文件管理器
                    // 显示的时间一致；只有它为 0 的极少数只读卷才回落去读创建时间。
                    runCatching { file.lastModified() }.getOrDefault(0L)
                        .takeIf { it > 0L }
                        ?: runCatching { file.creationTime }.getOrDefault(0L)
                }
            }
            return field
        }

    fun toUri(): Uri = storageUri ?: manga.url.toUri()

    fun isMatchesQuery(query: String): Boolean {
        return manga.title.contains(query, ignoreCase = true) ||
            manga.altTitles.contains(query, ignoreCase = true) ||
            manga.authors.contains(query, ignoreCase = true)
    }

    fun containsTags(tags: Collection<String>): Boolean {
        return tags.all { tag -> tag in manga.tags }
    }

    fun containsAnyTag(tags: Collection<String>): Boolean {
        return tags.any { tag -> tag in manga.tags }
    }

    private operator fun Collection<ContentTag>.contains(title: String): Boolean {
        return any { it.title.equals(title, ignoreCase = true) }
    }

    override fun toString(): String {
        return "LocalContent(${toUri()}: ${manga.title})"
    }
}

suspend fun LocalContent.computeStoredSize(context: Context): Long = runInterruptible(Dispatchers.IO) {
    val uri = toUri()
    val root = if (uri.scheme == "file") {
        UniFile.fromFile(uri.toFile())
    } else {
        UniFile.fromUri(context, uri)
    }
    checkNotNull(root) { "Cannot resolve local content URI: $uri" }.computeTreeSize()
}

internal fun UniFile.computeTreeSize(): Long {
    var size = 0L
    val pending = ArrayDeque<UniFile>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val item = pending.removeFirst()
        if (item.isDirectory) {
            item.listFiles().orEmpty().forEach(pending::addLast)
        } else {
            size += item.length().coerceAtLeast(0L)
        }
    }
    return size
}
