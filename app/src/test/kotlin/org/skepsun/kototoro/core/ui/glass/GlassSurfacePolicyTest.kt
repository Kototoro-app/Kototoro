package org.skepsun.kototoro.core.ui.glass

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
        amoledAlpha shouldBe 0.5808f
        bottomBarAlpha shouldBe amoledAlpha
    }

    @Test
    fun `amoled tint does not change non navigation surfaces`() {
        val style = GlassStyle(0.82f, 0.24f, 0.dp, 6.dp)

        style.backdropSurfaceAlpha(GlassComponentRole.Surface, amoledCanvas = true) shouldBe
            style.backdropSurfaceAlpha(GlassComponentRole.Surface, amoledCanvas = false)
    }
}
