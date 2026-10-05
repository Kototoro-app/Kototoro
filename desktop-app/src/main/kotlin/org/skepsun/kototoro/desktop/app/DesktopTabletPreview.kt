package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.ui.preview.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Platform model/image adapters only; the complete preview content is the Android tablet component. */
@Composable
internal fun DesktopTabletPreview(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val content = state.content ?: return
    val video = DesktopReaderKind.of(content) == DesktopReaderKind.VIDEO
    val groups = remember(content.chapters) {
        resolveTabletPreviewChapters(content.chapters.orEmpty(), { it.branch }, { it.number }, { it.uploadDate })
    }
    val chapters = if (groups.isSingleGroup) groups.earliest else groups.latest
    val count = content.chapters?.size ?: 0
    val data = remember(content, groups, state.sources) {
        TabletPreviewData(
            id = content.id, title = content.title, authors = content.authors.take(2).joinToString(" · "),
            sourceTitle = state.sources.firstOrNull { it.source.name == content.source.name }?.displayName
                ?: content.source.name,
            description = content.description.orEmpty().replace(Regex("<[^>]*>"), "").trim(),
            tags = content.tags.map { it.title },
            ratingText = content.rating.takeIf { it > 0f }?.let { String.format(Locale.getDefault(), "%.1f", it * 10f) },
            chapterCountText = if (count > 0) "$count 章节" else null,
            statusLabel = content.state?.let { when (it) {
                "ONGOING" -> "连载中"
                "FINISHED" -> "已完结"
                "ABANDONED" -> "已停更"
                "PAUSED" -> "暂停更新"
                else -> null
            } },
            chapterSections = buildList {
                add(TabletPreviewSection(if (groups.isSingleGroup) "章节" else "最新章节",
                    chapters.map { it.previewData() }, if (count > chapters.size) "共 $count 章节" else null))
                if (!groups.isSingleGroup) add(TabletPreviewSection("最初章节", groups.earliest.map { it.previewData() }))
            },
        )
    }
    TabletPreviewContent(
        data = data,
        labels = TabletPreviewLabels("关闭", if (video) "播放" else "阅读", "详情",
            if (state.isFavourite) "已收藏" else "加入收藏", "详情加载失败", "重试"),
        cover = { modifier -> DesktopCover(content, controller.session.covers, modifier, large = true) },
        icon = { icon -> when (icon) {
            TabletPreviewIcon.CLOSE -> painterResource("icons/ic_tts_close.svg")
            TabletPreviewIcon.PLAY -> painterResource("icons/ic_play.svg")
            TabletPreviewIcon.FAVOURITE -> painterResource("icons/${if (state.isFavourite) "ic_heart" else "ic_heart_outline"}.svg")
            TabletPreviewIcon.RATING -> painterResource("icons/ic_star_small.svg")
        } },
        onClose = { controller.dismissDetails() }, onRead = { controller.read() },
        onOpenDetails = controller::expandDetails, onAddToFavorites = { controller.addFavourite() },
        onOpenChapter = { id -> content.chapters?.firstOrNull { it.id == id }?.let { controller.read(it) } },
        controlsEnabled = enabled, favouriteEnabled = !state.isFavourite,
    )
}

private fun SourceChapter.previewData(): TabletPreviewChapter = TabletPreviewChapter(
    id = id, title = title?.takeIf(String::isNotBlank) ?: "第 $number 章",
    metadata = buildList {
        if (number > 0f) add("#$number")
        (scanlator?.takeIf(String::isNotBlank) ?: branch?.takeIf(String::isNotBlank))?.let(::add)
        if (uploadDate > 0L) add(DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
            Instant.ofEpochMilli(uploadDate).atZone(ZoneId.systemDefault())))
    }.joinToString(" · "),
)
