package org.skepsun.kototoro.reader.ui.config

import org.skepsun.kototoro.core.prefs.ReaderMode

/**
 * Which family of reading layout is on screen, and therefore which scene-renderer switch decides
 * whether the scene host renders it.
 */
enum class SceneReaderFamily {
	/** Webtoon: one long vertical strip. */
	CONTINUOUS,

	/** Single page, reversed and double page: discrete pages. */
	PAGED,
}

/**
 * The scene-renderer switch that owns a given reading configuration.
 *
 * Double page is a property of the paged layout even when the mode itself is continuous, so the
 * layout decides, not the mode's name.
 *
 * `CONTINUOUS_HORIZONTAL` is deliberately *not* routed here: it has no legacy renderer to fall back
 * to (`ComposeReaderScreenRoot` composes `ComposeSceneHorizontalReader` unconditionally), so a
 * switch could only ever turn that mode into a blank screen. It is the one continuous mode that is
 * scene-only; only `WEBTOON` has a legacy alternative for the switch to select.
 */
fun sceneReaderFamily(mode: ReaderMode, isDoublePage: Boolean): SceneReaderFamily = when {
	isDoublePage -> SceneReaderFamily.PAGED
	mode == ReaderMode.WEBTOON -> SceneReaderFamily.CONTINUOUS
	else -> SceneReaderFamily.PAGED
}

/**
 * Whether the scene host renders this configuration.
 *
 * The two switches are independent: [webtoonSceneReader] covers the webtoon mode and
 * [pagedSceneReader] covers single/double page. They used to be a conjunction
 * (`webtoon && paged`), which made the webtoon switch a hidden master for the paged host - turning
 * the webtoon renderer off also turned off the paged one, although the settings screen presents them
 * as two separate features.
 */
fun resolveSceneReaderEnabled(
	mode: ReaderMode,
	isDoublePage: Boolean,
	webtoonSceneReader: Boolean,
	pagedSceneReader: Boolean,
): Boolean = when (sceneReaderFamily(mode, isDoublePage)) {
	SceneReaderFamily.CONTINUOUS -> webtoonSceneReader
	SceneReaderFamily.PAGED -> pagedSceneReader
}
