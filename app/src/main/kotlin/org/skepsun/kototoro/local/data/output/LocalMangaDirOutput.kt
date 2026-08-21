package org.skepsun.kototoro.local.data.output

import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.closeQuietly
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.MimeType
import org.skepsun.kototoro.core.util.ext.deleteAwait
import org.skepsun.kototoro.core.util.ext.isImage
import org.skepsun.kototoro.core.util.ext.toFileNameSafe
import org.skepsun.kototoro.core.zip.ZipOutput
import org.skepsun.kototoro.local.data.ContentIndex
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import java.io.File

class LocalContentDirOutput(
    rootFile: UniFile,
    manga: Content,
    cacheDir: File,
) : LocalContentOutput(rootFile, cacheDir) {

    private data class ChapterOutput(val zip: ZipOutput, val targetName: String)

    private val chaptersOutput = HashMap<ContentChapter, ChapterOutput>()
    val index = ContentIndex(rootFile.findFile(ENTRY_NAME_INDEX)?.openInputStream()?.bufferedReader()?.use { it.readText() })
    private val mutex = Mutex()

    init {
        if (!manga.isLocal) {
            index.setContentInfo(manga)
        }
    }

    override suspend fun mergeWithExisting() = Unit

    override suspend fun addCover(file: File, type: MimeType?) = mutex.withLock {
        val name = buildString {
            append("cover")
            MimeTypes.getExtension(type)?.let { ext ->
                append('.')
                append(ext)
            }
        }
        runInterruptible(Dispatchers.IO) {
            val target = rootFile.findFile(name) ?: checkNotNull(rootFile.createFile(name))
            file.inputStream().use { input -> target.openOutputStream().use(input::copyTo) }
        }
        index.setCoverEntry(name)
        flushIndex()
    }

    override suspend fun addPage(chapter: IndexedValue<ContentChapter>, file: File, pageNumber: Int, type: MimeType?) =
        mutex.withLock {
            val output = chaptersOutput.getOrPut(chapter.value) {
                val targetName = chapterFileName(chapter)
                ChapterOutput(
                    zip = ZipOutput(File.createTempFile("chapter_", SUFFIX_TMP, cacheDir)),
                    targetName = targetName,
                )
            }
            val name = buildString {
                append(FILENAME_PATTERN.format(chapter.value.branch.hashCode(), chapter.index + 1, pageNumber))
                MimeTypes.getExtension(type)?.let { ext ->
                    append('.')
                    append(ext)
                }
            }
            runInterruptible(Dispatchers.IO) {
                output.zip.put(name, file)
            }
            index.addChapter(chapter, chapterFileName(chapter), null)
        }

    override suspend fun putChapterImages(chapterId: Long, remoteImages: Map<String, String>) =
        mutex.withLock {
            index.putChapterImages(chapterId, remoteImages)
            flushIndex()
        }

    override suspend fun flushChapter(chapter: ContentChapter): Boolean = mutex.withLock {
        val output = chaptersOutput.remove(chapter) ?: return@withLock false
        output.flushAndFinish()
        flushIndex()
        true
    }

    override suspend fun finish() = mutex.withLock {
        flushIndex()
        for (output in chaptersOutput.values) {
            output.flushAndFinish()
        }
        chaptersOutput.clear()
    }

    override suspend fun cleanup() = mutex.withLock {
        for (output in chaptersOutput.values) {
            output.zip.file.deleteAwait()
        }
    }

    override fun close() {
        for (output in chaptersOutput.values) {
            output.zip.closeQuietly()
        }
    }

    suspend fun deleteChapters(ids: Set<Long>) = mutex.withLock {
        val chapters = checkNotNull(
            (index.getContentInfo() ?: LocalContentParser(rootFile, cacheDir).getContent(withDetails = true).manga).chapters,
        ) {
            "No chapters found"
        }
        val chaptersById = chapters.associateBy(ContentChapter::id)
        val missingIds = ids - chaptersById.keys
        check(missingIds.isEmpty()) { "${missingIds.size} of ${ids.size} chapters was not removed: not found" }

        val selectedByFile = ids.groupBy { id -> index.getChapterFileName(id) }
        for ((fileName, selectedIds) in selectedByFile) {
            if (fileName != null) {
                val hasRemainingReferences = chapters.any { chapter ->
                    chapter.id !in ids && index.getChapterFileName(chapter.id) == fileName
                }
                if (!hasRemainingReferences) {
                    if (fileName.isEmpty()) {
                        rootFile.listFiles().orEmpty()
                            .filter { child ->
                                child.isFile &&
                                    MimeTypes.getMimeTypeFromExtension(child.name.orEmpty())?.isImage == true
                            }
                            .forEach { image -> check(image.delete()) { "Failed to delete chapter image: $image" } }
                    } else {
                        val chapterFile = rootFile.resolveRelative(fileName)
                        check(chapterFile?.exists() == true) { "Chapter file is missing: $fileName" }
                        check(chapterFile.delete()) { "Failed to delete chapter file: $chapterFile" }
                    }
                }
            }
            selectedIds.forEach(index::removeChapter)
        }
    }

    private fun UniFile.resolveRelative(relativePath: String): UniFile? {
        val segments = relativePath.split('/').filter(String::isNotEmpty)
        if (segments.any { it == "." || it == ".." }) return null
        return segments.fold(this as UniFile?) { parent, name -> parent?.findFile(name) }
    }

    fun setIndex(newIndex: ContentIndex) {
        index.setFrom(newIndex)
    }

    private suspend fun ChapterOutput.flushAndFinish() = runInterruptible(Dispatchers.IO) {
        val e: Throwable? = try {
            zip.finish()
            null
        } catch (e: Throwable) {
            e
        } finally {
            zip.close()
        }
        if (e == null) {
            val target = rootFile.findFile(targetName) ?: checkNotNull(rootFile.createFile(targetName))
            zip.file.inputStream().use { input -> target.openOutputStream().use(input::copyTo) }
            zip.file.delete()
        } else {
            zip.file.delete()
            throw e
        }
    }

    private fun chapterFileName(chapter: IndexedValue<ContentChapter>): String {
        index.getChapterFileName(chapter.value.id)?.let {
            return it
        }
        val baseName = buildString {
            append(chapter.index)
            chapter.value.title?.nullIfEmpty()?.let {
                append('_')
                append(it.toFileNameSafe())
            }
            if (length > 32) {
                deleteRange(31, lastIndex)
            }
        }
        var i = 0
        while (true) {
            val name = (if (i == 0) baseName else baseName + "_$i") + ".cbz"
            if (rootFile.findFile(name) == null) {
                return name
            }
            i++
        }
    }

    private suspend fun flushIndex() = runInterruptible(Dispatchers.IO) {
        val target = rootFile.findFile(ENTRY_NAME_INDEX) ?: checkNotNull(rootFile.createFile(ENTRY_NAME_INDEX))
        target.openOutputStream().bufferedWriter().use { it.write(index.toString()) }
    }

    /**
     * 添加完整的EPUB章节文件
     *
     * EPUB本身就是ZIP格式，保存为.epub文件以符合标准
     * 这个方法跳过ZipOutput，直接保存文件并更新index
     *
     * 同时解析EPUB内容，为每个EPUB内部章节创建ContentChapter
     *
     * 重要：删除原始章节，避免章节重复
     *
     * Requirements: 1.1, 1.2, 1.3, 2.1, 2.6, 5.1, 5.3
     * - 1.1: Preserve .epub extension
     * - 1.2: Do not convert EPUB to CBZ
     * - 1.3: Store in appropriate directory structure
     * - 2.1: Extract all spine references as individual chapters
     * - 2.6: Assign stable unique IDs to each internal chapter
     * - 5.1: Use formula for ID generation
     * - 5.3: Persist chapter mappings to database
     */
    suspend fun addEpubChapter(
        chapter: IndexedValue<ContentChapter>,
        epubFile: File,
        epubChapterMappingDao: org.skepsun.kototoro.core.db.dao.EpubChapterMappingDao? = null
    ) = mutex.withLock {
        android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Starting, epubFile=${epubFile.absolutePath}, exists=${epubFile.exists()}, size=${epubFile.length()}")
        android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Original chapter ID=${chapter.value.id}, title=${chapter.value.title}")
        android.util.Log.i("LocalContentDirOutput", "addEpubChapter: File extension=${epubFile.extension}")

        // Verify the file has .epub extension (Requirement 1.1)
        if (epubFile.extension != "epub") {
            android.util.Log.w("LocalContentDirOutput", "addEpubChapter: WARNING - File does not have .epub extension: ${epubFile.extension}")
        }

        // 第一步：记录需要隐藏的原始章节ID（用于过滤在线章节）
        try {
            android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Step 1 - Hiding original chapter")

            // 删除本地章节文件（如果存在）
            val originalChapterFileName = index.getChapterFileName(chapter.value.id)
            android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Original chapter file name: $originalChapterFileName")

            val originalChapterFile = originalChapterFileName?.let(rootFile::findFile)
            if (originalChapterFile != null && originalChapterFile.exists()) {
                android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Deleting original chapter file: ${originalChapterFile.uri}")
                check(originalChapterFile.delete()) { "Failed to delete $originalChapterFile" }
                android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Original chapter file deleted")
            } else {
                android.util.Log.i("LocalContentDirOutput", "addEpubChapter: No original chapter file to delete (file=$originalChapterFile, exists=${originalChapterFile?.exists()})")
            }

            // 从index中删除章节记录
            android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Removing chapter from index")
            val removed = index.removeChapter(chapter.value.id)
            android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Chapter removed from index: $removed")

            // 添加到隐藏列表（用于过滤在线章节）
            android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Adding chapter to hidden list")
            index.addHiddenChapterId(chapter.value.id)
            android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Chapter added to hidden list, hidden IDs: ${index.getHiddenChapterIds()}")

            android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Step 1 completed - original chapter hidden")
        } catch (e: Exception) {
            android.util.Log.e("LocalContentDirOutput", "addEpubChapter: ERROR in step 1: ${e.message}", e)
            // 继续执行，不影响EPUB添加
        }

        // 第二步：保存EPUB文件（保持.epub扩展名，Requirement 1.1 & 1.2）
        // Generate filename that preserves .epub extension
        val chapterFileName = generateEpubChapterFileName(chapter, epubFile)
        val targetFile = rootFile.findFile(chapterFileName) ?: checkNotNull(rootFile.createFile(chapterFileName))

        android.util.Log.i("LocalContentDirOutput", "addEpubChapter: Target file=${targetFile.uri}")

        runInterruptible(Dispatchers.IO) {
            epubFile.inputStream().use { input -> targetFile.openOutputStream().use(input::copyTo) }
        }

        println("LocalContentDirOutput.addEpubChapter: File saved with .epub extension, size=${targetFile.length()}")

        // 第三步：解析EPUB并添加内部章节
        // Requirements 2.1, 2.6, 5.1, 5.3
        try {
            val epubParser = org.skepsun.kototoro.local.epub.LocalEpubParser(epubFile)
            val epubContent = epubParser.parseContent()
            val epubChapters = epubContent?.chapters

            if (epubChapters != null && epubChapters.isNotEmpty()) {
                println("LocalContentDirOutput.addEpubChapter: Found ${epubChapters.size} chapters in EPUB")

                // Initialize ChapterIdGenerator for stable ID generation (Requirement 5.1)
                val chapterIdGenerator = org.skepsun.kototoro.local.epub.ChapterIdGeneratorImpl()

                // Prepare list of chapter mappings for database storage (Requirement 5.3)
                val chapterMappings = mutableListOf<org.skepsun.kototoro.core.db.entity.EpubChapterMappingEntity>()

                // 为每个EPUB内部章节创建ContentChapter
                // 使用原始章节的index作为基础，确保章节顺序正确
                // Extract all spine references as individual chapters (Requirement 2.1)
                epubChapters.forEachIndexed { epubChapterIndex, epubChapter ->
                    // Generate stable unique ID for internal chapter (Requirement 2.6, 5.1)
                    val internalChapterId = chapterIdGenerator.generateEpubChapterId(
                        parentChapterId = chapter.value.id,
                        chapterIndex = epubChapterIndex
                    )

                    val subChapter = IndexedValue(
                        index = chapter.index, // 所有EPUB内部章节使用相同的index（原始卷章节的index）
                        value = epubChapter.copy(
                            id = internalChapterId, // Use generated stable ID
                            url = "${targetFile.uri}#chapter/$epubChapterIndex",
                            branch = chapter.value.branch,
                            source = org.skepsun.kototoro.core.model.LocalNovelSource,
                        )
                    )
                    index.addChapter(subChapter, chapterFileName)
                    println("LocalContentDirOutput.addEpubChapter: Added EPUB chapter ${epubChapterIndex}: ${epubChapter.title}, ID=${internalChapterId}")

                    // Create mapping entity for database storage (Requirement 5.3)
                    val mapping = org.skepsun.kototoro.core.db.entity.EpubChapterMappingEntity(
                        internalChapterId = internalChapterId,
                        parentChapterId = chapter.value.id,
                        epubFilePath = targetFile.uri.toString(),
                        epubFileName = targetFile.name ?: chapterFileName,
                        chapterIndex = epubChapterIndex,
                        chapterTitle = epubChapter.title ?: "Chapter ${epubChapterIndex + 1}",
                        createdAt = System.currentTimeMillis()
                    )
                    chapterMappings.add(mapping)
                }

                // Store chapter mappings in database (Requirement 5.3)
                if (epubChapterMappingDao != null && chapterMappings.isNotEmpty()) {
                    try {
                        epubChapterMappingDao.insertAll(chapterMappings)
                        println("LocalContentDirOutput.addEpubChapter: Stored ${chapterMappings.size} chapter mappings in database")
                    } catch (e: Exception) {
                        println("LocalContentDirOutput.addEpubChapter: Failed to store chapter mappings: ${e.message}")
                        e.printStackTrace()
                        // Continue even if database storage fails - the chapters are still in the index
                    }
                } else {
                    println("LocalContentDirOutput.addEpubChapter: No DAO provided or no mappings to store")
                }

                println("LocalContentDirOutput.addEpubChapter: Successfully added ${epubChapters.size} EPUB chapters")
            } else {
                println("LocalContentDirOutput.addEpubChapter: Failed to parse EPUB or no chapters found, adding as single chapter")
                // 如果解析失败，添加为单个章节
                index.addChapter(chapter, chapterFileName)
            }
        } catch (e: Exception) {
            println("LocalContentDirOutput.addEpubChapter: Error parsing EPUB: ${e.message}")
            e.printStackTrace()
            // 出错时添加为单个章节
            index.addChapter(chapter, chapterFileName)
        }

        flushIndex()

        println("LocalContentDirOutput.addEpubChapter: Index updated successfully")
    }

    /**
     * Generates a filename for EPUB chapter that preserves the .epub extension.
     *
     * Requirement 1.1: Preserve .epub extension
     * Requirement 1.2: Do not convert to .cbz
     */
    private fun generateEpubChapterFileName(chapter: IndexedValue<ContentChapter>, epubFile: File): String {
        // Check if the file already has .epub extension
        val baseFileName = if (epubFile.extension == "epub") {
            epubFile.name
        } else {
            "${epubFile.nameWithoutExtension}.epub"
        }

        // If file already exists, generate unique name
        var targetFileName = baseFileName
        var counter = 1
        while (rootFile.findFile(targetFileName) != null) {
            val nameWithoutExt = baseFileName.removeSuffix(".epub")
            targetFileName = "${nameWithoutExt}_${counter}.epub"
            counter++
        }

        return targetFileName
    }

    companion object {

        private const val FILENAME_PATTERN = "%08d_%04d%04d"
    }
}
