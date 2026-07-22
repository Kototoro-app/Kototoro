package org.skepsun.kototoro.reader.ui.compose

/** One-shot UI commands issued by the legacy activity while rendering stays Compose-owned. */
data class ComposeReaderScrollRequest(
	val id: Long,
	val delta: Int,
	val smooth: Boolean,
)

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
