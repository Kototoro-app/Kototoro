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

    var createdAt: Long = -1L
        private set
        get() {
            if (field == -1L) {
                field = runCatching { file.creationTime }.getOrElse {
                    // 文件可能已被移动/删除，或 url 是无 scheme 的相对路径（历史坏数据）
                    // 出现 NoSuchFileException 等读写错误时退化为 0，避免崩溃整个列表。
                    0L
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
