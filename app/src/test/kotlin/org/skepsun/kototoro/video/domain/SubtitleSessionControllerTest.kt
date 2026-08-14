package org.skepsun.kototoro.video.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SubtitleSessionControllerTest {
    @Test
    fun `embedded and external tracks remain distinct`() {
        val controller = DefaultSubtitleSessionController { SubtitleSelectionResult.Selected }
        val embedded = subtitle("embedded", SubtitleOrigin.EMBEDDED)
        val external = subtitle("external", SubtitleOrigin.CLOUDSTREAM_EXTERNAL)

        controller.updateEmbeddedTracks(listOf(embedded))
        controller.updateExternalTracks(listOf(external, external))

        assertEquals(listOf(embedded, external), controller.state.value.tracks)
        assertEquals(SubtitleOrigin.EMBEDDED, controller.state.value.tracks.first().origin)
    }

    @Test
    fun `selection publishes pending state before playback finishes`() = runTest {
        val allowSelection = CompletableDeferred<Unit>()
        val controller = DefaultSubtitleSessionController {
            allowSelection.await()
            SubtitleSelectionResult.Selected
        }
        val external = subtitle("late", SubtitleOrigin.CLOUDSTREAM_EXTERNAL)
        controller.updateExternalTracks(listOf(external))

        val selection = async { controller.select(external.id) }
        runCurrent()

        assertEquals(external.id, controller.state.value.pendingTrackId)
        assertNull(controller.state.value.selectedTrackId)
        allowSelection.complete(Unit)
        selection.await()
        assertEquals(external.id, controller.state.value.selectedTrackId)
        assertNull(controller.state.value.pendingTrackId)
    }

    @Test
    fun `late catalog update does not select or reload by itself`() {
        var selectionCount = 0
        val controller = DefaultSubtitleSessionController {
            selectionCount++
            SubtitleSelectionResult.Selected
        }

        controller.updateExternalTracks(listOf(subtitle("late", SubtitleOrigin.CLOUDSTREAM_EXTERNAL)))

        assertEquals(0, selectionCount)
        assertNull(controller.state.value.selectedTrackId)
    }

    @Test
    fun `player track refresh does not clear a different pending selection`() = runTest {
        val allowSelection = CompletableDeferred<Unit>()
        val controller = DefaultSubtitleSessionController {
            allowSelection.await()
            SubtitleSelectionResult.Selected
        }
        val current = subtitle("current", SubtitleOrigin.EMBEDDED)
        val late = subtitle("late", SubtitleOrigin.CLOUDSTREAM_EXTERNAL)
        controller.updateEmbeddedTracks(listOf(current))
        controller.updateExternalTracks(listOf(late))

        val selection = async { controller.select(late.id) }
        runCurrent()
        controller.updateSelection(current.id)

        assertEquals(late.id, controller.state.value.pendingTrackId)
        assertEquals(current.id, controller.state.value.selectedTrackId)
        allowSelection.complete(Unit)
        selection.await()
    }

    @Test
    fun `failed selection clears pending and exposes error`() = runTest {
        val controller = DefaultSubtitleSessionController {
            SubtitleSelectionResult.Failed("reload failed")
        }
        val external = subtitle("failed", SubtitleOrigin.ANIYOMI_EXTERNAL)
        controller.updateExternalTracks(listOf(external))

        controller.select(external.id)

        assertNull(controller.state.value.pendingTrackId)
        assertNull(controller.state.value.selectedTrackId)
        assertEquals("reload failed", controller.state.value.error)
    }

    private fun subtitle(id: String, origin: SubtitleOrigin) = PlaybackSubtitle(
        id = id,
        uri = null,
        label = id,
        languageTag = "en",
        origin = origin,
        mimeType = null,
        headers = emptyMap(),
    )
}
