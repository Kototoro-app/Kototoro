package org.skepsun.kototoro.reader.novel

import androidx.collection.LongSparseArray
import androidx.collection.contains

/**
 * 管理多个章节的页面
 * 类似于漫画阅读器的 ChapterPages
 *
 * 关键点：每个章节独立分页，但合并到一个连续的列表中
 */
class NovelChapterPages private constructor(
    private val pages: ArrayDeque<NovelPage>
) : List<NovelPage> by pages {

    // 章节ID -> 页面范围的映射
    private val indices = LongSparseArray<IntRange>()

    constructor() : this(ArrayDeque())

    val chaptersSize: Int
        get() = indices.size()

    /**
     * 移除第一个章节
     */
    @Synchronized
    fun removeFirst() {
        if (pages.isEmpty()) return

        val chapterId = pages.first().chapterId
        indices.remove(chapterId)

        var delta = 0
        while (pages.isNotEmpty() && pages.first().chapterId == chapterId) {
            pages.removeFirst()
            delta--
        }

        // 更新剩余章节的索引范围
        shiftIndices(delta)
        updateGlobalIndices()
    }

    /**
     * 移除最后一个章节
     */
    @Synchronized
    fun removeLast() {
        if (pages.isEmpty()) return

        val chapterId = pages.last().chapterId
        indices.remove(chapterId)

        while (pages.isNotEmpty() && pages.last().chapterId == chapterId) {
            pages.removeLast()
        }

        updateGlobalIndices()
    }

    /**
     * 在末尾添加章节的页面
     */
    @Synchronized
    fun addLast(chapterId: Long, newPages: List<NovelPage>): Boolean {
        if (chapterId in indices || newPages.isEmpty()) {
            return false
        }

        val startIndex = pages.size
        indices.put(chapterId, startIndex until (startIndex + newPages.size))
        pages.addAll(newPages)
        updateGlobalIndices()

        return true
    }

    /**
     * 在开头添加章节的页面
     */
    @Synchronized
    fun addFirst(chapterId: Long, newPages: List<NovelPage>): Boolean {
        if (chapterId in indices || newPages.isEmpty()) {
            return false
        }

        shiftIndices(newPages.size)
        indices.put(chapterId, newPages.indices)
        pages.addAll(0, newPages)
        updateGlobalIndices()

        return true
    }

    /**
     * 清空所有页面
     */
    @Synchronized
    fun clear() {
        indices.clear()
        pages.clear()
    }

    /**
     * 获取指定章节的页面数量
     */
    fun size(chapterId: Long): Int {
        return indices[chapterId]?.run {
            endInclusive - start + 1
        } ?: 0
    }

    /**
     * 获取指定章节的所有页面
     */
    fun subList(chapterId: Long): List<NovelPage> {
        val range = indices[chapterId] ?: return emptyList()
        return pages.subList(range.first, range.last + 1)
    }

    /**
     * 检查是否包含指定章节
     */
    operator fun contains(chapterId: Long): Boolean {
        return chapterId in indices
    }

    /**
     * 获取指定章节的第一页的全局索引
     */
    fun getFirstPageIndex(chapterId: Long): Int {
        return indices[chapterId]?.first ?: -1
    }

    /**
     * 获取指定章节的最后一页的全局索引
     */
    fun getLastPageIndex(chapterId: Long): Int {
        return indices[chapterId]?.last ?: -1
    }

    /**
     * 根据全局索引获取页面
     */
    fun getPageByGlobalIndex(index: Int): NovelPage? {
        return pages.getOrNull(index)
    }

    /**
     * 更新所有索引（向后移动）
     */
    private fun shiftIndices(delta: Int) {
        for (i in 0 until indices.size()) {
            val range = indices.valueAt(i)
            indices.setValueAt(i, range + delta)
        }
    }

    /**
     * 更新所有页面的全局索引
     */
    private fun updateGlobalIndices() {
        pages.forEachIndexed { index, page ->
            page.globalIndex = index
        }
    }

    /**
     * 创建快照（用于传递给 View）
     */
    fun snapshot(): List<NovelPage> {
        return pages.toList()
    }

    private operator fun IntRange.plus(delta: Int): IntRange {
        return (first + delta)..(last + delta)
    }
}
