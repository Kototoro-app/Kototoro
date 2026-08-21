package org.skepsun.kototoro.local.epub

import android.content.Context
import androidx.annotation.WorkerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.local.data.LocalStorageManager
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EPUB存储管理器
 *
 * 负责管理EPUB文件的存储和访问
 *
 * 存储结构：
 * files/epub/{manga_id}/book.epub
 * files/epub/{manga_id}/.metadata (可选，存储元数据)
 */
@Singleton
class EpubStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localStorageManager: LocalStorageManager,
) {

    /**
     * EPUB 根目录。
     * 优先使用「本地内容目录 - 小说」设置的默认目录下的 epub 子目录，
     * 未设置或不可写时回退到应用外部私有目录 files/epub。
     */
    @WorkerThread
    private fun resolveEpubRootDir(): File {
        val base = localStorageManager.getPreferredNovelDownloadDir()
        val root = if (base != null) {
            File(base, "epub")
        } else {
            File(context.getExternalFilesDir(null), "epub")
        }
        if (!root.exists()) {
            root.mkdirs()
            android.util.Log.d("EpubStorageManager", "Created EPUB root directory: ${root.absolutePath}")
        }
        return root
    }

    /**
     * 获取指定manga的EPUB目录
     * @param create 是否在目录不存在时创建它
     */
    fun getEpubDir(mangaId: Long, create: Boolean = false): File {
        return File(resolveEpubRootDir(), mangaId.toString()).also {
            if (create && !it.exists()) {
                it.mkdirs()
                android.util.Log.d("EpubStorageManager", "Created EPUB directory for manga $mangaId: ${it.absolutePath}")
            }
        }
    }

    /**
     * 获取EPUB文件路径
     * 如果文件不存在，返回null
     */
    fun getEpubFile(mangaId: Long): File? {
        val epubDir = File(resolveEpubRootDir(), mangaId.toString())
        if (!epubDir.exists()) {
            return null
        }

        val epubFile = File(epubDir, "book.epub")
        return if (epubFile.exists()) epubFile else null
    }

    /**
     * 保存EPUB文件
     *
     * @param mangaId 漫画ID
     * @param inputStream EPUB文件输入流
     * @return 保存后的文件
     */
    suspend fun saveEpubFile(mangaId: Long, inputStream: InputStream): File = withContext(Dispatchers.IO) {
        val epubDir = getEpubDir(mangaId, create = true)
        val epubFile = File(epubDir, "book.epub")

        android.util.Log.d("EpubStorageManager", "Saving EPUB file to: ${epubFile.absolutePath}")

        // 如果文件已存在，先删除
        if (epubFile.exists()) {
            android.util.Log.d("EpubStorageManager", "Deleting existing EPUB file")
            epubFile.delete()
        }

        // 保存文件
        epubFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }

        android.util.Log.d("EpubStorageManager", "EPUB file saved successfully, size: ${epubFile.length()} bytes")

        epubFile
    }

    /**
     * 保存EPUB文件（从File）
     *
     * @param mangaId 漫画ID
     * @param sourceFile 源EPUB文件
     * @param chapterId 章节ID（用于区分同一manga的多个EPUB文件）
     */
    suspend fun saveEpubFile(mangaId: Long, sourceFile: File, chapterId: Long? = null): File = withContext(Dispatchers.IO) {
        val epubDir = getEpubDir(mangaId, create = true)

        // 如果提供了chapterId，使用它来生成唯一的文件名
        // 否则使用默认的 book.epub（向后兼容）
        val fileName = if (chapterId != null) {
            "chapter_${chapterId}.epub"
        } else {
            "book.epub"
        }

        val epubFile = File(epubDir, fileName)

        android.util.Log.d("EpubStorageManager", "Copying EPUB file from ${sourceFile.absolutePath} to ${epubFile.absolutePath}")

        // 如果文件已存在，先删除
        if (epubFile.exists()) {
            android.util.Log.d("EpubStorageManager", "Deleting existing EPUB file")
            epubFile.delete()
        }

        // 复制文件
        sourceFile.copyTo(epubFile, overwrite = true)

        android.util.Log.d("EpubStorageManager", "EPUB file copied successfully, size: ${epubFile.length()} bytes")

        epubFile
    }

    /**
     * 删除EPUB文件
     */
    suspend fun deleteEpubFile(mangaId: Long): Boolean = withContext(Dispatchers.IO) {
        val epubDir = File(resolveEpubRootDir(), mangaId.toString())
        if (!epubDir.exists()) {
            android.util.Log.d("EpubStorageManager", "EPUB directory does not exist for manga $mangaId")
            return@withContext false
        }

        android.util.Log.d("EpubStorageManager", "Deleting EPUB directory: ${epubDir.absolutePath}")

        val deleted = epubDir.deleteRecursively()

        if (deleted) {
            android.util.Log.d("EpubStorageManager", "EPUB directory deleted successfully")
        } else {
            android.util.Log.e("EpubStorageManager", "Failed to delete EPUB directory")
        }

        deleted
    }

    /**
     * 检查是否有EPUB文件
     */
    fun hasEpubFile(mangaId: Long): Boolean {
        val epubFile = getEpubFile(mangaId)
        return epubFile != null && epubFile.exists()
    }

    /**
     * 获取EPUB文件大小
     */
    fun getEpubFileSize(mangaId: Long): Long {
        val epubFile = getEpubFile(mangaId) ?: return 0L
        return epubFile.length()
    }

    /**
     * 列出所有有EPUB文件的manga ID
     */
    fun listAllEpubContentIds(): List<Long> {
        val epubRoot = resolveEpubRootDir()
        if (!epubRoot.exists()) {
            return emptyList()
        }

        return epubRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                try {
                    val mangaId = dir.name.toLong()
                    val epubFile = File(dir, "book.epub")
                    if (epubFile.exists()) mangaId else null
                } catch (e: NumberFormatException) {
                    null
                }
            }
            ?: emptyList()
    }
}
