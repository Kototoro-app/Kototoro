package org.skepsun.kototoro.core.model

import org.skepsun.kototoro.parsers.model.ContentSource

data class ContentSourceInfo(
	val mangaSource: ContentSource,
	val isEnabled: Boolean,
	val isPinned: Boolean,
	val availability: ContentSourceAvailability = ContentSourceAvailability.UNKNOWN,
) : ContentSource by mangaSource
