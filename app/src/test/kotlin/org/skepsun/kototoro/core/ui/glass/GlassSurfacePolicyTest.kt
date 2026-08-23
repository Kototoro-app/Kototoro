package org.skepsun.kototoro.core.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import io.kotest.matchers.floats.shouldBeGreaterThan
import org.junit.jupiter.api.Test

class GlassSurfacePolicyTest {

    @Test
    fun `navigation chrome keeps backdrop on amoled canvas`() {
        GlassComponentRole.TopBar.allowsAmoledBackdrop() shouldBe true
        GlassComponentRole.BottomBar.allowsAmoledBackdrop() shouldBe true
    }

    @Test
    fun `content overlays keep backdrop on amoled canvas`() {
        GlassComponentRole.ContentOverlay.allowsAmoledBackdrop() shouldBe true
    }

    @Test
    fun `bottom panels keep backdrop on amoled canvas`() {
        GlassComponentRole.BottomPanel.allowsAmoledBackdrop() shouldBe true
    }

    @Test
    fun `other glass surfaces keep amoled fallback`() {
        GlassComponentRole.Surface.allowsAmoledBackdrop() shouldBe false
        GlassComponentRole.Menu.allowsAmoledBackdrop() shouldBe false
        GlassComponentRole.Dialog.allowsAmoledBackdrop() shouldBe false
        GlassComponentRole.Sheet.allowsAmoledBackdrop() shouldBe false
    }

    @Test
    fun `amoled navigation chrome uses a denser translucent tint`() {
        val style = GlassStyle(
            containerAlpha = 0.88f,
            borderAlpha = 0.20f,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
        )

        val regularAlpha = style.backdropSurfaceAlpha(
            componentRole = GlassComponentRole.TopBar,
            amoledCanvas = false,
        )
        val amoledAlpha = style.backdropSurfaceAlpha(
            componentRole = GlassComponentRole.TopBar,
            amoledCanvas = true,
        )
        val bottomBarAlpha = style.backdropSurfaceAlpha(
            componentRole = GlassComponentRole.BottomBar,
            amoledCanvas = true,
        )

        amoledAlpha shouldBeGreaterThan regularAlpha
        regularAlpha shouldBe 0.528f
        amoledAlpha shouldBe 0.6336f
        bottomBarAlpha shouldBe amoledAlpha
    }

    @Test
    fun `navigation chrome tint is within the raised readable alpha band`() {
        val regularStyle = GlassStyle(0.88f, 0.20f, 0.dp, 4.dp)
        val bottomBarStyle = GlassStyle(0.84f, 0.10f, 0.dp, 4.dp)

        val regularAlpha = regularStyle.backdropSurfaceAlpha(
            componentRole = GlassComponentRole.TopBar,
            amoledCanvas = false,
        )
        val bottomBarAlpha = bottomBarStyle.backdropSurfaceAlpha(
            componentRole = GlassComponentRole.BottomBar,
            amoledCanvas = false,
        )

        regularAlpha shouldBeGreaterThan 0.45f
        regularAlpha shouldBeGreaterThan 0.5f
        bottomBarAlpha shouldBeGreaterThan 0.45f
    }

    @Test
    fun `chrome tint uses official high contrast surface colors`() {
        chromeBackdropTint(isDark = false) shouldBe Color(0xFFFAFAFA)
        chromeBackdropTint(isDark = true) shouldBe Color(0xFF121212)
    }

    @Test
    fun `amoled tint does not change non navigation surfaces`() {
        val style = GlassStyle(0.82f, 0.24f, 0.dp, 6.dp)

        style.backdropSurfaceAlpha(GlassComponentRole.Surface, amoledCanvas = true) shouldBe
            style.backdropSurfaceAlpha(GlassComponentRole.Surface, amoledCanvas = false)
    }

    @Test
    fun `bottom panels use a dense translucent tint`() {
        val style = GlassStyle(0.88f, 0.20f, 0.dp, 4.dp)

        style.backdropSurfaceAlpha(GlassComponentRole.BottomPanel, amoledCanvas = true) shouldBe 0.44f
        style.backdropSurfaceAlpha(GlassComponentRole.BottomPanel, amoledCanvas = false) shouldBe 0.44f
    }
}
