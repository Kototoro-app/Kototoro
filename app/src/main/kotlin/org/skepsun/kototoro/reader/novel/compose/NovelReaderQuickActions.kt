package org.skepsun.kototoro.reader.novel.compose

import org.skepsun.kototoro.R
import org.skepsun.kototoro.reader.ui.compose.design.ReaderQuickAction

internal enum class NovelQuickActionId(val iconResId: Int, val labelResId: Int) {
    TTS(R.drawable.ic_voice_input, R.string.tts_settings_title),
    BOOKMARK(R.drawable.ic_bookmark_added, R.string.bookmark_add),
    MARKINGS(R.drawable.ic_bookmark, R.string.novel_reader_bookmarks_notes),
    TRANSLATE(R.drawable.ic_translate, R.string.novel_reader_translation_enabled),
}

internal fun novelQuickActions(translationEnabled: Boolean): List<ReaderQuickAction> =
    NovelQuickActionId.entries.map { id ->
        ReaderQuickAction(
            id = id.name,
            iconResId = id.iconResId,
            labelResId = id.labelResId,
            toggled = if (id == NovelQuickActionId.TRANSLATE) translationEnabled else null,
        )
    }
