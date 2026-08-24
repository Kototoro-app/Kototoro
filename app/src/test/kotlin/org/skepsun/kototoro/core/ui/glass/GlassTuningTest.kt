package org.skepsun.kototoro.core.ui.glass

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.settings.compose.glass.GlassPreset

class GlassTuningTest {

    @Test
    fun `grouped control can suppress container press refraction`() {
        resolveGlassPressProgress(enabled = false, progress = 1f) shouldBe 0f
        resolveGlassPressProgress(enabled = true, progress = 0.6f) shouldBe 0.6f
        shouldApplyGlassLens(enabled = false, heightDp = 24f, amountDp = 24f) shouldBe false
    }

    @Test
    fun `non pressable roles hide press feedback parameters`() {
        val topBarParams = GlassTuning.paramsForScope(GlassTuningScope.TOP_BAR)
        val menuParams = GlassTuning.paramsForScope(GlassTuningScope.MENU)

        assertTrue(topBarParams.none { it in GlassTuning.pressFeedbackParams })
        assertTrue(menuParams.none { it in GlassTuning.pressFeedbackParams })
    }

    @Test
    fun `pressable roles expose press feedback parameters`() {
        GlassTuning.pressableRoles.forEach { scope ->
            val params = GlassTuning.paramsForScope(scope)

            assertFalse(GlassTuning.pressFeedbackParams.isEmpty())
            assertTrue(params.containsAll(GlassTuning.pressFeedbackParams))
        }
    }

    @Test
    fun `preview overrides do not mutate persisted tuning state`() {
        val original = GlassTuningState(
            mapOf(GlassTuningScope.GLOBAL to GlassTuning.defaultConfig(GlassTuningScope.GLOBAL)),
        )

        val preview = original.withValues(
            mapOf((GlassTuningScope.GLOBAL to GlassTuningParam.BLUR_RADIUS_DP) to 20f),
        )

        assertTrue(original.value(GlassTuningScope.GLOBAL, GlassTuningParam.BLUR_RADIUS_DP) == 8f)
        assertTrue(preview.value(GlassTuningScope.GLOBAL, GlassTuningParam.BLUR_RADIUS_DP) == 20f)
    }

    @Test
    fun `bilipai preset keeps its tuned material values`() {
        val values = GlassPreset.BILIPAI.config.values

        assertTrue(values[GlassTuningParam.BLUR_RADIUS_DP.key] == 4f)
        assertTrue(values[GlassTuningParam.LENS_HEIGHT_DP.key] == 24f)
        assertTrue(values[GlassTuningParam.LENS_AMOUNT_DP.key] == 24f)
        assertTrue(values[GlassTuningParam.SURFACE_ALPHA.key] == 0.38f)
        assertTrue(values[GlassTuningParam.CHROMATIC_ABERRATION.key] == 0f)
        assertTrue(values[GlassTuningParam.RIM_ENABLED.key] == 1f)
        assertTrue(values[GlassTuningParam.RIM_ALPHA.key] == 0.75f)
        assertTrue(values[GlassTuningParam.SHADOW_ENABLED.key] == 1f)
        assertTrue(values[GlassTuningParam.SHADOW_RADIUS_DP.key] == 24f)
        assertTrue(values[GlassTuningParam.SHADOW_OFFSET_DP.key] == 4f)
    }
}
