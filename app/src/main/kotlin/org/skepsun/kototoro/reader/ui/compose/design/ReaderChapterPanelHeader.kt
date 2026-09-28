package org.skepsun.kototoro.reader.ui.compose.design

/** Joins the header subtitle parts (chapter, position, progress), skipping blanks and repeats. */
internal fun readerChapterPanelSubtitle(parts: List<String?>): String =
    parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct().joinToString(" · ")
