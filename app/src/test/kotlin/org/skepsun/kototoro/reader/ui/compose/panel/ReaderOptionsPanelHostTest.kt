package org.skepsun.kototoro.reader.ui.compose.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderOptionsPanelHostTest {

    @Test
    fun `e-ink always gets the plain surface`() {
        assertEquals(
            ReaderPanelSurfaceMode.EInk,
            readerPanelSurfaceMode(isIosStyle = true, eInk = true, hasBackdrop = true),
        )
    }

    @Test
    fun `glass needs the iOS style and a reader backdrop`() {
        assertEquals(ReaderPanelSurfaceMode.Glass, readerPanelSurfaceMode(true, eInk = false, hasBackdrop = true))
        assertEquals(ReaderPanelSurfaceMode.Opaque, readerPanelSurfaceMode(true, eInk = false, hasBackdrop = false))
        assertEquals(ReaderPanelSurfaceMode.Opaque, readerPanelSurfaceMode(false, eInk = false, hasBackdrop = true))
    }

    @Test
    fun `scrim is lighter over glass and absent on e-ink`() {
        assertEquals(0.32f, readerPanelScrimAlpha(ReaderPanelSurfaceMode.Glass))
        assertEquals(0.42f, readerPanelScrimAlpha(ReaderPanelSurfaceMode.Opaque))
        assertEquals(0f, readerPanelScrimAlpha(ReaderPanelSurfaceMode.EInk))
    }
}
