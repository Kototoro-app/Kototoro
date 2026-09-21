package org.skepsun.kototoro.reader.ui.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderMode

/**
 * The reader's two scene-renderer switches are per family: the webtoon switch covers `WEBTOON`, the
 * paged switch covers single/double page. Before they were exposed in the reader's options panel the
 * paged host additionally required the webtoon switch, so turning the webtoon renderer off silently
 * disabled the paged one as well - even though the settings screen presents them as two features.
 */
class SceneReaderGateTest {

	@Test
	fun `webtoon follows the webtoon switch`() {
		assertTrue(
			resolveSceneReaderEnabled(ReaderMode.WEBTOON, isDoublePage = false, webtoonSceneReader = true, pagedSceneReader = false),
		)
		assertFalse(
			resolveSceneReaderEnabled(ReaderMode.WEBTOON, isDoublePage = false, webtoonSceneReader = false, pagedSceneReader = true),
			"the webtoon mode must not follow the paged switch",
		)
	}

	@Test
	fun `paged modes follow the paged switch`() {
		for (mode in listOf(ReaderMode.STANDARD, ReaderMode.REVERSED, ReaderMode.VERTICAL)) {
			assertTrue(
				resolveSceneReaderEnabled(mode, isDoublePage = false, webtoonSceneReader = false, pagedSceneReader = true),
				"$mode should use the scene host while the paged switch is on",
			)
			assertFalse(
				resolveSceneReaderEnabled(mode, isDoublePage = false, webtoonSceneReader = true, pagedSceneReader = false),
				"$mode must not follow the webtoon switch",
			)
		}
	}

	@Test
	fun `double page belongs to the paged switch`() {
		assertTrue(
			resolveSceneReaderEnabled(ReaderMode.STANDARD, isDoublePage = true, webtoonSceneReader = false, pagedSceneReader = true),
		)
		assertFalse(
			resolveSceneReaderEnabled(ReaderMode.WEBTOON, isDoublePage = true, webtoonSceneReader = true, pagedSceneReader = false),
			"double page is a paged layout even when the mode is continuous",
		)
	}

	@Test
	fun `the switches are independent`() {
		assertFalse(
			resolveSceneReaderEnabled(ReaderMode.WEBTOON, isDoublePage = false, webtoonSceneReader = false, pagedSceneReader = true),
		)
		assertTrue(
			resolveSceneReaderEnabled(ReaderMode.STANDARD, isDoublePage = false, webtoonSceneReader = false, pagedSceneReader = true),
			"turning the webtoon renderer off must not disable the paged one",
		)
	}

	@Test
	fun `family mapping is explicit about which mode is switchable`() {
		assertEquals(SceneReaderFamily.CONTINUOUS, sceneReaderFamily(ReaderMode.WEBTOON, isDoublePage = false))
		assertEquals(SceneReaderFamily.PAGED, sceneReaderFamily(ReaderMode.STANDARD, isDoublePage = false))
		assertEquals(SceneReaderFamily.PAGED, sceneReaderFamily(ReaderMode.VERTICAL, isDoublePage = false))
		assertEquals(SceneReaderFamily.PAGED, sceneReaderFamily(ReaderMode.WEBTOON, isDoublePage = true))
	}
}
