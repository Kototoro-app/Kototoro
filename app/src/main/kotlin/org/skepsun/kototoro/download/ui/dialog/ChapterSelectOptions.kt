package org.skepsun.kototoro.download.ui.dialog

data class ChapterSelectOptions(
    val wholeContent: ChaptersSelectMacro.WholeContent,
    val wholeBranch: ChaptersSelectMacro.WholeBranch?,
    val firstChapters: ChaptersSelectMacro.FirstChapters?,
    val unreadChapters: ChaptersSelectMacro.UnreadChapters?,
)
