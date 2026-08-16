package org.skepsun.kototoro.core.ui.glass

import io.kotest.matchers.shouldBe
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
}
