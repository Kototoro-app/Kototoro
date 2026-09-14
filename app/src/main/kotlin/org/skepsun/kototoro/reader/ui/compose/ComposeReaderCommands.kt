package org.skepsun.kototoro.reader.ui.compose

/** One-shot UI commands issued by the legacy activity while rendering stays Compose-owned. */
data class ComposeReaderScrollRequest(
    val id: Long,
    val delta: Int,
    val cumulativeDelta: Long,
    val smooth: Boolean,
)

/** One-shot page-turn commands whose distance is resolved from the webtoon viewport. */
data class ComposeWebtoonPageTurnRequest(
    val id: Long,
    val delta: Int,
    val cumulativeDelta: Long,
)

internal fun resolveScrollRequestDelta(
    previous: ComposeReaderScrollRequest?,
    current: ComposeReaderScrollRequest,
): Long {
    return previous?.let { current.cumulativeDelta - it.cumulativeDelta } ?: current.delta.toLong()
}

internal fun resolveWebtoonPageTurnRequestDelta(
    previous: ComposeWebtoonPageTurnRequest?,
    current: ComposeWebtoonPageTurnRequest,
): Long {
    return previous?.let { current.cumulativeDelta - it.cumulativeDelta } ?: current.delta.toLong()
}

/** Targets a page key so a later navigation cannot replay an earlier zoom command. */
data class ComposeReaderZoomCommand(
    val id: Long,
    val pageKey: Long,
    val factor: Float,
)

data class ComposeWebtoonZoomCommand(
    val id: Long,
    val factor: Float,
)
