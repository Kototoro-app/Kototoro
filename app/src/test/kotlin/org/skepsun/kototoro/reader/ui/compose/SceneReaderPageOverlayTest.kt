package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.image.ReaderImageLoadState

/**
 * What a scene host draws over a page.
 *
 * The regression these cover: the overlay used to be "anything that is not Ready" - including the
 * *unknown* state the pipeline leaves behind when a page leaves its resource window, and including a
 * page that already has an asset to draw. That made every warm page turn flash "加载中…" at the start
 * of the turn, even though the page was decoded and cached.
 */
class SceneReaderPageOverlayTest {

	@Test
	fun `a ready page has no overlay`() {
		assertEquals(
			SceneReaderPageOverlay.NONE,
			resolveSceneReaderPageOverlay(ReaderImageLoadState.Ready, hasRenderableAsset = true),
		)
		assertEquals(
			SceneReaderPageOverlay.NONE,
			resolveSceneReaderPageOverlay(ReaderImageLoadState.Ready, hasRenderableAsset = false),
		)
	}

	@Test
	fun `an unknown state is not loading`() {
		assertEquals(
			SceneReaderPageOverlay.NONE,
			resolveSceneReaderPageOverlay(state = null, hasRenderableAsset = false),
			"a page that just left the resource window must not be covered by a spinner",
		)
	}

	@Test
	fun `a loading page with nothing to draw shows the spinner`() {
		assertEquals(
			SceneReaderPageOverlay.LOADING,
			resolveSceneReaderPageOverlay(ReaderImageLoadState.Loading(), hasRenderableAsset = false),
		)
	}

	@Test
	fun `a loading page that can already be drawn shows nothing`() {
		assertEquals(
			SceneReaderPageOverlay.NONE,
			resolveSceneReaderPageOverlay(ReaderImageLoadState.Loading(), hasRenderableAsset = true),
			"a spinner must not cover content that is already on screen",
		)
	}

	@Test
	fun `a failure always reports itself`() {
		val failure = ReaderImageLoadState.Failed(IllegalStateException("boom"))
		assertEquals(SceneReaderPageOverlay.ERROR, resolveSceneReaderPageOverlay(failure, hasRenderableAsset = false))
		assertEquals(
			SceneReaderPageOverlay.ERROR,
			resolveSceneReaderPageOverlay(failure, hasRenderableAsset = true),
			"a failed page keeps its retry UI even if a stale asset is still retained",
		)
	}
}
