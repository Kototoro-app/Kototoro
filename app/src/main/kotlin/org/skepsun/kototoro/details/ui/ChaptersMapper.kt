package org.skepsun.kototoro.details.ui

import android.content.Context
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.details.ui.model.toListItem
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.local.epub.ChapterMetadata
import org.skepsun.kototoro.local.epub.ChapterType
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.reader.ui.FULLY_READ_CHAPTER_ID

/**
 * Checks if a chapter URL points to a local file (i.e. actually downloaded),
 * as opposed to still being a remote URL in index.json metadata.
 */
private fun String.isLocalChapterUrl(): Boolean =
	startsWith("file:") || startsWith("zip:") || startsWith("file+zip:") || 
	startsWith("content:") || startsWith("epub:") || startsWith("localepub:")

/**
 * Represents a group of chapters for UI display
 * 
 * @param name Display name of the group
 * @param chapters List of chapters in this group
 * @param isCollapsible Whether the group can be collapsed/expanded in the UI
 */
data class ChapterGroup(
    val name: String,
    val chapters: List<ContentChapter>,
    val isCollapsible: Boolean
)

fun ContentDetails.mapChapters(
	currentChapterId: Long,
	newCount: Int,
	branch: String?,
	bookmarks: List<Bookmark>,
	isGrid: Boolean,
	isDownloadedOnly: Boolean,
	shareProgressAcrossBranches: Boolean = false,
): List<ChapterListItem> {
	val resolvedBranch = when {
		branch == null -> null
		chapters[branch].isNullOrEmpty() && local?.manga?.getChapters(branch).isNullOrEmpty() -> {
			chapters.maxByOrNull { it.value.size }?.key
		}
		else -> branch
	}
	val remoteChapters = chapters[resolvedBranch].orEmpty()
	val localChapters = local?.manga?.getChapters(resolvedBranch).orEmpty()
	android.util.Log.d(
		"ChaptersMapper",
		"mapChapters: requestedBranch=$branch, resolvedBranch=$resolvedBranch, remoteCount=${remoteChapters.size}, localCount=${localChapters.size}, allBranches=${chapters.mapValues { it.value.size }}",
	)
	
	if (remoteChapters.isEmpty() && localChapters.isEmpty()) {
		android.util.Log.w(
			"ChaptersMapper",
			"mapChapters: returning empty for mangaId=$id, requestedBranch=$branch, resolvedBranch=$resolvedBranch",
		)
		return emptyList()
	}
	val bookmarked = bookmarks.mapToSet { it.chapterId }
	val newFrom = if (newCount == 0 || remoteChapters.isEmpty()) Int.MAX_VALUE else remoteChapters.size - newCount
	val ids = buildSet(maxOf(remoteChapters.size, localChapters.size)) {
		remoteChapters.mapTo(this) { it.id }
		localChapters.mapTo(this) { it.id }
	}
	val result = ArrayList<ChapterListItem>(ids.size)
	
	val localMapById = if (localChapters.isNotEmpty()) {
		localChapters.associateByTo(LinkedHashMap(localChapters.size)) { it.id }
	} else {
		null
	}
	val localMapByUrl = if (localChapters.isNotEmpty()) {
		localChapters.associateByTo(LinkedHashMap(localChapters.size)) { it.url }
	} else {
		null
	}
	
	val currentChapter = remoteChapters.find { it.id == currentChapterId }
		?: localChapters.find { it.id == currentChapterId }
		?: if (shareProgressAcrossBranches) allChapters.find { it.id == currentChapterId } else null
	
	if (!isDownloadedOnly || local?.manga?.chapters == null) {
		for ((index, chapter) in remoteChapters.withIndex()) {
			val localById = localMapById?.remove(chapter.id)
			val localByUrl = if (localById == null) {
				localMapByUrl?.remove(chapter.url)?.also {
					localMapById?.remove(it.id)
				}
			} else {
				null
			}
			val local = localById ?: localByUrl
			val finalChapter = local ?: chapter
			val isUnread = when {
				currentChapterId == FULLY_READ_CHAPTER_ID -> false
				currentChapter != null -> chapter.isAfter(currentChapter)
				else -> true
			}
			
			result += finalChapter.toListItem(
				isCurrent = chapter.id == currentChapterId || finalChapter.id == currentChapterId,
				isUnread = isUnread,
				isNew = isUnread && result.size >= newFrom,
				isDownloaded = local != null && local.url.isLocalChapterUrl(),
				isBookmarked = chapter.id in bookmarked || finalChapter.id in bookmarked,
				isGrid = isGrid,
			)
		}
	}
	if (!localMapById.isNullOrEmpty()) {
		for (chapter in localMapById.values) {
			result += chapter.toListItem(
				isCurrent = chapter.id == currentChapterId,
				isUnread = true,
				isNew = false,
				isDownloaded = chapter.source.isLocal || chapter.url.isLocalChapterUrl(),
				isBookmarked = chapter.id in bookmarked,
				isGrid = isGrid,
			)
		}
	}
	android.util.Log.d(
		"ChaptersMapper",
		"mapChapters: resultCount=${result.size}, downloadedOnly=$isDownloadedOnly, first=${result.take(3).map { "${it.chapter.id}|${it.chapter.branch}|${it.chapter.title}" }}",
	)
	
	return result
}

private fun ContentChapter.isAfter(current: ContentChapter): Boolean {
	return if (volume > 0 && current.volume > 0 && volume != current.volume) {
		volume > current.volume
	} else {
		number > current.number
	}
}

fun List<ChapterListItem>.withVolumeHeaders(context: Context): MutableList<ListModel> {
	// 检查是否有EPUB章节（通过URL判断）
	val hasEpubChapters = any { it.chapter.url.startsWith("epub://") || it.chapter.url.contains("#chapter/") }
	
	if (hasEpubChapters) {
		// EPUB章节：按父章节（卷）分组
		return withEpubVolumeGroups(context)
	} else {
		// 普通章节：使用原有的volume分组逻辑
		var prevVolume = -1 // Start with -1 to ensure first volume always gets a header
		var prevCustomHeader: String? = null
		val result = ArrayList<ListModel>((size * 1.4).toInt())
		for (item in this) {
			val chapter = item.chapter
			val customHeader = chapter.scanlator?.takeIf { it.isNotBlank() }
			
			// Show a header if the volume index changed OR if we have a new unique custom string header
			if (chapter.volume != prevVolume || (customHeader != null && customHeader != prevCustomHeader)) {
				val text = if (customHeader != null) {
					customHeader
				} else if (chapter.volume <= 0) {
					context.getString(R.string.volume_unknown)
				} else {
					context.getString(R.string.volume_, chapter.volume)
				}
				result.add(ListHeader(text))
				prevVolume = chapter.volume
				prevCustomHeader = customHeader
			}
			result.add(item)
		}
		return result
	}
}

/**
 * 为EPUB章节添加卷分组（使用CollapsibleListHeader）
 * 
 * EPUB章节的特点：
 * - URL格式：epub://{manga_id}/chapter/{index}
 * - 需要按EPUB文件（通过epubFileName）分组显示
 * 
 * 分组策略：
 * - 使用chapter.scanlator作为卷名（DownloadWorker保存时设置为父章节标题）
 * - 如果scanlator为空或为"EPUB下载"，使用chapter.branch作为卷名
 * - 最后fallback到"Volume {number}"
 */
private fun List<ChapterListItem>.withEpubVolumeGroups(context: Context): MutableList<ListModel> {
	android.util.Log.d("ChaptersMapper", "=== withEpubVolumeGroups START ===")
	android.util.Log.d("ChaptersMapper", "Total chapters: ${this.size}")
	
	val result = ArrayList<ListModel>((size * 1.5).toInt())
	
	// 按原始顺序遍历，保持章节顺序不变
	var currentVolumeName: String? = null
	var volumeCounter = 0  // 用于生成唯一的groupId
	
	for ((index, item) in this.withIndex()) {
		val chapter = item.chapter
		android.util.Log.d("ChaptersMapper", "Chapter[$index]: id=${chapter.id}, title=${chapter.name}, url=${chapter.url.takeLast(50)}")
		
		// 从URL提取章节索引来判断是否是内部章节
		val isInternalChapter = chapter.url.contains("#chapter/") || 
		                        (chapter.url.startsWith("epub://") && chapter.url.contains("/chapter/"))
		
		android.util.Log.d("ChaptersMapper", "  isInternalChapter=$isInternalChapter")
		
		if (isInternalChapter) {
			// 确定卷名：优先使用scanlator（LocalEpubSource设置的epubFileName）
			val volumeName: String = when {
				!chapter.scanlator.isNullOrBlank() && chapter.scanlator != "EPUB下载" -> chapter.scanlator!!
				!chapter.branch.isNullOrBlank() -> chapter.branch!!
				else -> {
					// Fallback: 从URL提取manga ID和chapter index
					val urlParts = chapter.url.split("/")
					val mangaId = urlParts.getOrNull(2) ?: "unknown"
					"Volume ${mangaId.takeLast(4)}"
				}
			}
			
			// 如果是新的卷，添加卷标题
			if (volumeName != currentVolumeName) {
				volumeCounter++
				result.add(
					org.skepsun.kototoro.list.ui.model.CollapsibleListHeader(
						text = volumeName,
						isCollapsible = true,
						isExpanded = true,
						groupId = "epub_volume_${volumeCounter}"  // 使用计数器确保唯一性
					)
				)
				currentVolumeName = volumeName
			}
			
			// 添加章节
			result.add(item)
		} else {
			// 非内部章节（可能是下载链接或普通章节）：直接添加
			// 不重置currentVolumeName，避免同一EPUB被分割
			android.util.Log.d("ChaptersMapper", "  Adding non-internal chapter: ${chapter.name}")
			result.add(item)
		}
	}
	
	android.util.Log.d("ChaptersMapper", "=== withEpubVolumeGroups END: ${result.size} items ===")
	return result
}

/**
 * Maps chapters to groups based on chapter type and EPUB filename
 * 
 * This function implements the chapter grouping logic according to requirements:
 * - EPUB_DOWNLOAD chapters are grouped under "下载链接" (Download Links)
 * - EPUB_INTERNAL chapters are grouped by their EPUB filename
 * - NORMAL chapters are grouped under "在线章节" (Online Chapters)
 * 
 * @param chapters List of all chapters
 * @param metadataMap Map of chapter ID to ChapterMetadata
 * @return List of chapter groups for UI display
 */
fun mapChaptersToGroups(
    chapters: List<ContentChapter>,
    metadataMap: Map<Long, ChapterMetadata>
): List<ChapterGroup> {
    if (chapters.isEmpty()) {
        return emptyList()
    }
    
    val result = mutableListOf<ChapterGroup>()
    
    // Group chapters by type
    val downloadChapters = mutableListOf<ContentChapter>()
    val normalChapters = mutableListOf<ContentChapter>()
    val epubInternalByFile = mutableMapOf<String, MutableList<ContentChapter>>()
    
    for (chapter in chapters) {
        val metadata = metadataMap[chapter.id]
        val chapterType = metadata?.chapterType ?: ChapterType.NORMAL
        
        when (chapterType) {
            ChapterType.EPUB_DOWNLOAD -> {
                downloadChapters.add(chapter)
            }
            ChapterType.EPUB_INTERNAL -> {
                val epubFileName = metadata?.epubFileName ?: "Unknown EPUB"
                epubInternalByFile.getOrPut(epubFileName) { mutableListOf() }.add(chapter)
            }
            ChapterType.NORMAL -> {
                normalChapters.add(chapter)
            }
        }
    }
    
    // Add download links group if present
    if (downloadChapters.isNotEmpty()) {
        result.add(
            ChapterGroup(
                name = "下载链接",
                chapters = downloadChapters,
                isCollapsible = true
            )
        )
    }
    
    // Add EPUB file groups if present (sorted by filename for consistency)
    for ((epubFileName, epubChapters) in epubInternalByFile.entries.sortedBy { it.key }) {
        result.add(
            ChapterGroup(
                name = epubFileName,
                chapters = epubChapters,
                isCollapsible = true
            )
        )
    }
    
    // Add normal chapters group if present
    if (normalChapters.isNotEmpty()) {
        result.add(
            ChapterGroup(
                name = "在线章节",
                chapters = normalChapters,
                isCollapsible = false
            )
        )
    }
    
    return result
}

/**
 * Converts chapter groups into a flat list with collapsible headers
 * 
 * @param groups List of chapter groups
 * @param currentChapterId ID of the currently reading chapter
 * @param newCount Number of new chapters
 * @param bookmarks List of bookmarked chapters
 * @param isGrid Whether to display in grid mode
 * @return Flat list of ListModel items including headers and chapters
 */
fun List<ChapterGroup>.toListModelsWithHeaders(
    currentChapterId: Long,
    newCount: Int,
    bookmarks: List<Bookmark>,
    isGrid: Boolean,
): List<ListModel> {
    if (isEmpty()) {
        return emptyList()
    }
    
    val result = mutableListOf<ListModel>()
    val bookmarked = bookmarks.mapToSet { it.chapterId }
    var isUnread = true
    var newFrom = Int.MAX_VALUE
    
    // Calculate newFrom for the first group with new chapters
    for (group in this) {
        if (newCount > 0 && group.chapters.isNotEmpty()) {
            newFrom = group.chapters.size - newCount
            break
        }
    }
    
    for (group in this) {
        // Add collapsible header
        result.add(
            org.skepsun.kototoro.list.ui.model.CollapsibleListHeader(
                text = group.name,
                isCollapsible = group.isCollapsible,
                isExpanded = true,
                groupId = group.name
            )
        )
        
        // Add chapters in this group
        for ((index, chapter) in group.chapters.withIndex()) {
            if (chapter.id == currentChapterId) {
                isUnread = true
            }
            result.add(
                chapter.toListItem(
                    isCurrent = chapter.id == currentChapterId,
                    isUnread = isUnread,
                    isNew = isUnread && index >= newFrom,
                    isDownloaded = false, // Will be determined by the caller
                    isBookmarked = chapter.id in bookmarked,
                    isGrid = isGrid,
                )
            )
        }
    }
    
    return result
}
