package org.skepsun.kototoro.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.time.Instant

@Parcelize
data class ContentHistory(
    val createdAt: Instant,
    val updatedAt: Instant,
    val chapterId: Long,
    val page: Int,
    val scroll: Int,
    val percent: Float,
    val chaptersCount: Int,
    val parentChapterId: Long? = null,  // EPUB父章节ID，用于支持内部章节
) : Parcelable
