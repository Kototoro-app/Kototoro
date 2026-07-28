package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.ZoomMode

class ReaderZoomStateTest {

	@Test
	fun `reader scale modes keep fit modes at one`() {
		assertEquals(
			1f,
			initialReaderScale(ZoomMode.FIT_CENTER, 1000, 2000, 2000, 1000),
		)
		assertEquals(
			1f,
			initialReaderScale(ZoomMode.FIT_HEIGHT, 1000, 2000, 2000, 1000),
		)
		assertEquals(
			1f,
			initialReaderScale(ZoomMode.FIT_WIDTH, 1000, 2000, 2000, 1000),
		)
	}

	@Test
	fun `keep start matches legacy scale relative to fit`() {
		assertEquals(
			8f,
			initialReaderScale(ZoomMode.KEEP_START, 1000, 2000, 2000, 1000),
		)
	}

	@Test
	fun `unscaled image does not consume pan`() {
		val state = ReaderZoomState().apply {
			updateGeometry(viewportWidth = 1000, viewportHeight = 2000, imageWidth = 1000, imageHeight = 2000)
		}

		val consumption = state.transform(panX = 200f, panY = 100f, zoom = 1f)

		assertFalse(consumption.consumed)
		assertEquals(200f, consumption.remainingPanX)
		assertEquals(100f, consumption.remainingPanY)
		assertEquals(0f, state.offsetX)
		assertEquals(0f, state.offsetY)
	}

	@Test
	fun `pan is clamped to scaled image bounds`() {
		val state = ReaderZoomState().apply {
			updateGeometry(viewportWidth = 1000, viewportHeight = 2000, imageWidth = 1000, imageHeight = 2000)
			transform(panX = 0f, panY = 0f, zoom = 2f)
		}

		val consumption = state.transform(panX = 800f, panY = -1600f, zoom = 1f)

		assertEquals(300f, consumption.remainingPanX)
		assertEquals(-600f, consumption.remainingPanY)
		assertEquals(500f, state.offsetX)
		assertEquals(-1000f, state.offsetY)
	}

	@Test
	fun `outward pan at image edge is left for pager`() {
		val state = ReaderZoomState().apply {
			updateGeometry(viewportWidth = 1000, viewportHeight = 2000, imageWidth = 1000, imageHeight = 2000)
			transform(panX = 0f, panY = 0f, zoom = 2f)
			transform(panX = 500f, panY = 0f, zoom = 1f)
		}

		val consumption = state.transform(panX = 100f, panY = 0f, zoom = 1f)

		assertEquals(100f, consumption.remainingPanX)
		assertEquals(0f, consumption.remainingPanY)
		assertEquals(500f, state.offsetX)
	}

	@Test
	fun `double tap reset clears translation`() {
		val state = ReaderZoomState().apply {
			updateGeometry(viewportWidth = 1000, viewportHeight = 2000, imageWidth = 1000, imageHeight = 2000)
			toggleDoubleTapZoom()
			transform(panX = 200f, panY = 300f, zoom = 1f)
		}

		state.toggleDoubleTapZoom()

		assertEquals(1f, state.scale)
		assertEquals(0f, state.offsetX)
		assertEquals(0f, state.offsetY)
	}

	@Test
	fun `external zoom commands respect zoom bounds`() {
		val state = ReaderZoomState(maxScale = 2f).apply {
			updateGeometry(viewportWidth = 1000, viewportHeight = 2000, imageWidth = 1000, imageHeight = 2000)
		}

		state.zoomBy(3f)
		assertEquals(2f, state.scale)

		state.zoomBy(0.1f)
		assertEquals(1f, state.scale)
	}

	@Test
	fun `snapshot restores each page transform independently`() {
		val firstPage = ReaderZoomState().apply {
			updateGeometry(viewportWidth = 1000, viewportHeight = 2000, imageWidth = 1000, imageHeight = 2000)
			transform(panX = 250f, panY = -300f, zoom = 2f)
		}
		val secondPage = ReaderZoomState().apply {
			updateGeometry(viewportWidth = 1000, viewportHeight = 2000, imageWidth = 2000, imageHeight = 1000)
			transform(panX = -400f, panY = 0f, zoom = 3f)
		}

		assertEquals(ReaderZoomState.Snapshot(2f, 250f, -300f), firstPage.snapshot())
		assertEquals(ReaderZoomState.Snapshot(3f, -400f, 0f), secondPage.snapshot())
	}

	@Test
	fun `animated zoom targets respect minimum and maximum scale`() {
		val state = ReaderZoomState(maxScale = 3f)

		assertEquals(3f, state.targetScaleForFactor(10f))
		state.zoomTo(2f)
		assertEquals(1f, state.targetScaleForFactor(0.1f))
	}

	@Test
	fun `double tap animation alternates between fitted and enlarged targets`() {
		val state = ReaderZoomState()

		assertEquals(2f, state.doubleTapTargetScale())
		state.zoomTo(2f)
		assertEquals(1f, state.doubleTapTargetScale())
	}
}
