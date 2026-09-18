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

    @Test
    fun `navigation bottom bar and pill controls track glass press`() {
        shouldTrackGlassPress(GlassComponentRole.BottomBar, pressFeedbackEnabled = true) shouldBe true
        shouldTrackGlassPress(GlassComponentRole.PillControl, pressFeedbackEnabled = true) shouldBe true
        shouldTrackGlassPress(GlassComponentRole.BottomPanel, pressFeedbackEnabled = true) shouldBe true
    }

    @Test
    fun `top bar or surfaces with press feedback disabled do not track glass press`() {
        shouldTrackGlassPress(GlassComponentRole.TopBar, pressFeedbackEnabled = true) shouldBe false
        shouldTrackGlassPress(GlassComponentRole.BottomBar, pressFeedbackEnabled = false) shouldBe false
        shouldTrackGlassPress(GlassComponentRole.PillControl, pressFeedbackEnabled = false) shouldBe false
    }

    @Test
    fun `translucent container drops the fallback shadow`() {
        // A translucent fill lets the surface's own shadow read through as a dark
        // inner rim, which is what produced the nested light plate inside every
        // top-bar pill over an artwork background.
        resolveFallbackShadowElevation(
            styleShadowElevation = 4.dp,
            containerAlpha = 0.65f,
            flat = false,
        ) shouldBe 0.dp
    }

    @Test
    fun `opaque container keeps the fallback shadow`() {
        resolveFallbackShadowElevation(
            styleShadowElevation = 4.dp,
            containerAlpha = 1f,
            flat = false,
        ) shouldBe 4.dp
    }

    @Test
    fun `flat surfaces never draw a fallback shadow`() {
        resolveFallbackShadowElevation(
            styleShadowElevation = 6.dp,
            containerAlpha = 1f,
            flat = true,
        ) shouldBe 0.dp
    }

    @Test
    fun `zero elevation style stays flat regardless of container alpha`() {
        resolveFallbackShadowElevation(
            styleShadowElevation = 0.dp,
            containerAlpha = 1f,
            flat = false,
        ) shouldBe 0.dp
    }
}
