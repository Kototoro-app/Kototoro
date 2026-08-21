package org.skepsun.kototoro.local.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.zip.ZipFile

/**
 * EPUB图片提取器
 *
 * 功能：
 * - 从EPUB ZIP文件中提取图片
 * - 解析相对路径（../Images/cover.jpg）
 * - 缓存提取的图片
 *
 * 使用场景：
 * - 显示EPUB封面
 * - 在阅读器中显示插图
 * - 图片预览功能
 */
class EpubImageExtractor(private val epubFile: File) {

    /**
     * 解析图片相对路径
     *
     * @param chapterPath 章节文件路径（如 "OEBPS/Text/chapter1.xhtml"）
     * @param imageSrc 图片src属性（如 "../Images/cover.jpg"）
     * @return 解析后的绝对路径（如 "OEBPS/Images/cover.jpg"）
     */
    fun resolveImagePath(chapterPath: String, imageSrc: String): String {
        // 远程 URL 直接返回
        if (imageSrc.startsWith("http://") || imageSrc.startsWith("https://")) {
            return imageSrc
        }

        // 章节所在目录
        val chapterDir = File(chapterPath).parent ?: ""

        // 去掉 ./ 或 / 前缀，再按章节目录解析
        val cleaned = imageSrc
            .removePrefix("./")
            .removePrefix("/")

        val resolvedFile = File(chapterDir, cleaned).normalize()

        return resolvedFile.path.replace(File.separator, "/")
    }

    /**
     * 从EPUB中提取图片
     *
     * @param imagePath 图片在EPUB中的路径
     * @return 图片的字节数组，如果提取失败返回null
     */
    fun extractImage(imagePath: String): ByteArray? {
        if (!epubFile.exists()) {
            android.util.Log.e(TAG, "EPUB file not found: ${epubFile.absolutePath}")
            return null
        }

        return try {
            ZipFile(epubFile).use { zip ->
                // 尝试多种路径格式
                val possiblePaths = listOf(
                    imagePath,
                    imagePath.removePrefix("/"),
                    "OEBPS/$imagePath",
                    "OEBPS/${imagePath.removePrefix("/")}",
                    "item/$imagePath",
                    "item/${imagePath.removePrefix("/")}",
                    "item/image/$imagePath",
                    "item/image/${imagePath.removePrefix("/")}",
                )

                for (path in possiblePaths) {
                    val entry = zip.getEntry(path)
                    if (entry != null) {
                        android.util.Log.d(TAG, "Found image at: $path")
                        return@use zip.getInputStream(entry).readBytes()
                    }
                }

                // fallback: 搜索同名文件（优先最短路径）
                val filename = imagePath.substringAfterLast('/')
                val candidates = zip.entries().toList()
                    .filter { !it.isDirectory && it.name.substringAfterLast('/') == filename }
                    .sortedBy { it.name.length }
                val entry = candidates.firstOrNull()
                if (entry != null) {
                    android.util.Log.d(TAG, "Found image by filename fallback: ${entry.name}")
                    return@use zip.getInputStream(entry).readBytes()
                }

                android.util.Log.w(TAG, "Image not found in EPUB: $imagePath (tried: $possiblePaths)")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to extract image: $imagePath", e)
            null
        }
    }

    /**
     * 从EPUB中提取图片并解码为Bitmap
     *
     * @param imagePath 图片在EPUB中的路径
     * @return Bitmap对象，如果提取或解码失败返回null
     */
    fun extractImageAsBitmap(imagePath: String): Bitmap? {
        val imageBytes = extractImage(imagePath) ?: return null

        return try {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to decode image: $imagePath", e)
            null
        }
    }

    /**
     * 列出EPUB中的所有图片
     *
     * @return 图片路径列表
     */
    fun listImages(): List<String> {
        if (!epubFile.exists()) {
            return emptyList()
        }

        return try {
            ZipFile(epubFile).use { zip ->
                zip.entries().toList()
                    .filter { entry ->
                        !entry.isDirectory && isImageFile(entry.name)
                    }
                    .map { it.name }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to list images", e)
            emptyList()
        }
    }

    /**
     * 检查文件是否是图片
     */
    private fun isImageFile(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
    }

    /**
     * 提取封面图片
     *
     * 尝试从常见位置查找封面：
     * - cover.jpg/png
     * - Images/cover.jpg/png
     * - OEBPS/Images/cover.jpg/png
     *
     * @return 封面图片的字节数组，如果未找到返回null
     */
    fun extractCoverImage(): ByteArray? {
        val commonCoverNames = listOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "Cover.jpg", "Cover.jpeg", "Cover.png",
            "Images/cover.jpg", "Images/cover.png",
            "images/cover.jpg", "images/cover.png",
            "OEBPS/Images/cover.jpg", "OEBPS/Images/cover.png",
        )

        for (coverPath in commonCoverNames) {
            extractImage(coverPath)?.let { return it }
        }

        // 如果没找到，尝试找第一张图片
        val images = listImages()
        if (images.isNotEmpty()) {
            android.util.Log.d(TAG, "No standard cover found, using first image: ${images.first()}")
            return extractImage(images.first())
        }

        return null
    }

    companion object {
        private const val TAG = "EpubImageExtractor"
    }
}
