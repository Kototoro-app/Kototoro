package org.skepsun.kototoro.core.nav

import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.reader.ui.ReaderState

internal data class VideoLaunchTarget(
	val url: String,
	val state: ReaderState?,
)

internal fun resolveVideoLaunchTarget(
	manga: Content,
	requestedState: ReaderState?,
): VideoLaunchTarget {
	val chapters = manga.chapters.orEmpty()
	if (chapters.isEmpty()) {
		return VideoLaunchTarget(
			url = manga.publicUrl,
			state = requestedState,
		)
	}
	val state = requestedState
		?.takeIf { requested -> chapters.any { it.id == requested.chapterId } }
		?: ReaderState(manga, branch = null)
	val chapterUrl = chapters.firstOrNull { it.id == state.chapterId }
		?.url
		?.takeIf { it.isNotBlank() }
	return VideoLaunchTarget(
		url = chapterUrl ?: manga.publicUrl,
		state = state,
	)
}
