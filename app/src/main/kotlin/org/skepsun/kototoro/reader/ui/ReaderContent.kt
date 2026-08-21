package org.skepsun.kototoro.reader.ui

import org.skepsun.kototoro.reader.ui.pager.ReaderPage

data class ReaderContent(
    val pages: List<ReaderPage>,
    val state: ReaderState?
)