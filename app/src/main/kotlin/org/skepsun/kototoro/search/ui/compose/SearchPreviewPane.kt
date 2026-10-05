package org.skepsun.kototoro.search.ui.compose

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.text.HtmlCompat
import coil3.request.ImageRequest
import org.skepsun.kototoro.core.ui.preview.TabletPreviewChapter
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

@Composable
internal fun rememberPreviewCoverRequest(content: Content): ImageRequest {
    val context = LocalContext.current
    return remember(content.id, content.largeCoverUrl, content.coverUrl, content.source) {
        ImageRequest.Builder(context)
            .data(content.largeCoverUrl ?: content.coverUrl)
            .mangaSourceExtra(content.source)
            .build()
    }
}

internal fun Content.previewDescription(): String {
    return HtmlCompat.fromHtml(description.orEmpty(), HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .trim()
}

internal fun Content.previewAuthors(): String = authors.take(2).joinToString(" · ")

@Composable
internal fun previewChapterData(chapter: ContentChapter): TabletPreviewChapter {
    val title = chapter.title?.takeIf(String::isNotBlank)
        ?: chapter.numberString()?.let { "#$it" }
        ?: stringResource(R.string.unnamed_chapter)
    val metadata = remember(chapter.number, chapter.scanlator, chapter.branch, chapter.uploadDate) {
        buildList {
            chapter.numberString()?.let { add("#$it") }
            (chapter.scanlator?.takeIf(String::isNotBlank) ?: chapter.branch?.takeIf(String::isNotBlank))?.let(::add)
            if (chapter.uploadDate > 0L) {
                add(
                    DateUtils.getRelativeTimeSpanString(
                        chapter.uploadDate,
                        System.currentTimeMillis(),
                        DateUtils.DAY_IN_MILLIS,
                    ).toString(),
                )
            }
        }.joinToString(" · ")
    }

    return TabletPreviewChapter(chapter.id, title, metadata)
}

internal typealias PreviewChapterGroups = org.skepsun.kototoro.core.ui.preview.TabletPreviewChapterGroups<ContentChapter>

internal fun Content.resolvePreviewChapters(): PreviewChapterGroups =
    org.skepsun.kototoro.core.ui.preview.resolveTabletPreviewChapters(
        chapters.orEmpty(), { it.branch }, { it.number }, { it.uploadDate },
    )
