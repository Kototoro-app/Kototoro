package org.skepsun.kototoro.core.ui.glass

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.Capsule
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

        assertTrue(original.value(GlassTuningScope.GLOBAL, GlassTuningParam.BLUR_RADIUS_DP) == 2f)
        assertTrue(preview.value(GlassTuningScope.GLOBAL, GlassTuningParam.BLUR_RADIUS_DP) == 20f)
    }

    @Test
    fun `fresh install defaults to the refraction preset`() {
        val configs = GlassTuningScope.entries.associateWith { GlassTuning.defaultConfig(it) }
        val state = GlassTuningState(configs)

        assertTrue(GlassPreset.REFRACTION.matches(state))
        assertTrue(GlassTuning.defaultConfig(GlassTuningScope.GLOBAL).values == GlassTuning.refractionPresetValues)
        // Every role follows the flat refraction baseline.
        GlassTuningScope.entries.filter { it != GlassTuningScope.GLOBAL }.forEach { scope ->
            assertTrue(state.config(scope).followGlobal.containsAll(GlassTuningParam.entries.map { it.key }))
        }
    }

    @Test
    fun `control center preset keeps its tuned material values`() {
        val values = GlassPreset.CONTROL_CENTER.config.values

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

    @Test
    fun `all presets carry the full glass parameter set`() {
        val allKeys = GlassTuningParam.entries.map { it.key }.toSet()
        GlassPreset.entries.forEach { preset ->
            val missing = allKeys - preset.config.values.keys
            assertTrue(missing.isEmpty(), "${preset.id} misses keys: $missing")
        }
    }

    @Test
    fun `control center preset ships role deltas`() {
        val menu = GlassPreset.CONTROL_CENTER.roleOverrides[GlassTuningScope.MENU]
        assertTrue(menu != null)
        assertTrue(menu!![GlassTuningParam.SHADOW_ENABLED.key] == 0f)

        // Pill controls get a gentler refraction so the stadium-shaped compact
        // tab rail / group pills don't paint internal arc artifacts.
        val pill = GlassPreset.CONTROL_CENTER.roleOverrides[GlassTuningScope.PILL_CONTROL]
        assertTrue(pill != null)
        assertTrue(pill!![GlassTuningParam.LENS_HEIGHT_DP.key] == 8f)
        assertTrue(pill[GlassTuningParam.LENS_AMOUNT_DP.key] == 12f)

        // Bars and panels keep the strong Control Center lens untouched.
        assertTrue(GlassPreset.CONTROL_CENTER.roleOverrides[GlassTuningScope.TOP_BAR] == null)
        assertTrue(GlassPreset.CONTROL_CENTER.roleOverrides[GlassTuningScope.BOTTOM_BAR] == null)
        assertTrue(GlassPreset.CONTROL_CENTER.roleOverrides[GlassTuningScope.BOTTOM_PANEL] == null)

        // Other presets stay flat (no role deltas).
        assertTrue(GlassPreset.LIQUID.roleOverrides.isEmpty())
    }

    @Test
    fun `preset scope config follows global except overridden keys`() {
        val cfg = GlassTuning.presetScopeConfig(mapOf(GlassTuningParam.SHADOW_ENABLED.key to 0f))
        assertTrue(GlassTuningParam.SHADOW_ENABLED.key !in cfg.followGlobal)
        assertTrue(GlassTuningParam.BLUR_RADIUS_DP.key in cfg.followGlobal)
        assertTrue(cfg.value(GlassTuningParam.SHADOW_ENABLED) == 0f)
    }

    @Test
    fun `preset role deltas override global while other params follow`() {
        val global = GlassScopeConfig(
            values = mapOf(
                GlassTuningParam.BLUR_RADIUS_DP.key to 4f,
                GlassTuningParam.SHADOW_ENABLED.key to 1f,
            ),
        )
        val menu = GlassTuning.presetScopeConfig(mapOf(GlassTuningParam.SHADOW_ENABLED.key to 0f))
        val state = GlassTuningState(
            mapOf(
                GlassTuningScope.GLOBAL to global,
                GlassTuningScope.MENU to menu,
            ),
        )

        // Overridden key resolves locally.
        assertTrue(state.value(GlassTuningScope.MENU, GlassTuningParam.SHADOW_ENABLED) == 0f)
        // Non-overridden keys keep following the preset's Global scope.
        assertTrue(state.value(GlassTuningScope.MENU, GlassTuningParam.BLUR_RADIUS_DP) == 4f)
    }

    @Test
    fun `custom preset snapshots global and role deltas and round-trips`() {
        val global = GlassScopeConfig(
            values = mapOf(GlassTuningParam.BLUR_RADIUS_DP.key to 8f),
        )
        val unFollowed = GlassTuningParam.entries.map { it.key }.toMutableSet()
            .apply { remove(GlassTuningParam.SHADOW_ENABLED.key) }
        val menu = GlassScopeConfig(
            values = mapOf(GlassTuningParam.SHADOW_ENABLED.key to 0f),
            followGlobal = unFollowed,
            initialized = true,
        )
        val configs = mutableMapOf(
            GlassTuningScope.GLOBAL to global,
            GlassTuningScope.MENU to menu,
        )
        GlassTuningScope.entries
            .filter { it != GlassTuningScope.GLOBAL && it != GlassTuningScope.MENU }
            .forEach { configs[it] = GlassScopeConfig().followAll() }
        val state = GlassTuningState(configs)

        val preset = state.toCustomPreset(id = "custom_1", name = "Custom 1")
        // Full global snapshot.
        assertTrue(preset.global.keys.containsAll(GlassTuningParam.entries.map { it.key }))
        // Only the actual MENU delta is captured.
        assertTrue(preset.roleOverrides.keys == setOf(GlassTuningScope.MENU.prefKey))
        assertTrue(
            preset.roleOverrides[GlassTuningScope.MENU.prefKey]!![GlassTuningParam.SHADOW_ENABLED.key] == 0f,
        )
        assertTrue(preset.scopeOverrides().keys == setOf(GlassTuningScope.MENU))

        // Encode -> decode -> the preset matches the source tuning state.
        val decoded = GlassTuning.decodeCustomPresets(GlassTuning.encodeCustomPresets(listOf(preset)))
        assertTrue(decoded.size == 1)
        assertTrue(decoded[0].matches(state))
    }

    @Test
    fun `control center preset matches when role deltas applied`() {
        val globalValues = GlassPreset.CONTROL_CENTER.config.values
        val configs = mutableMapOf(
            GlassTuningScope.GLOBAL to GlassScopeConfig(values = globalValues, initialized = true),
        )
        GlassPreset.CONTROL_CENTER.roleOverrides.forEach { (scope, overrides) ->
            configs[scope] = GlassTuning.presetScopeConfig(overrides)
        }
        GlassTuningScope.entries
            .filter { it != GlassTuningScope.GLOBAL && it !in GlassPreset.CONTROL_CENTER.roleOverrides }
            .forEach { configs[it] = GlassScopeConfig().followAll() }

        assertTrue(GlassPreset.CONTROL_CENTER.matches(GlassTuningState(configs)))
        // A flat (no role delta) state no longer matches Control Center.
        configs[GlassTuningScope.MENU] = GlassScopeConfig().followAll()
        assertFalse(GlassPreset.CONTROL_CENTER.matches(GlassTuningState(configs)))
        // Different global values do not match.
        assertFalse(GlassPreset.VIBRANT.matches(GlassTuningState(configs)))
    }

    @Test
    fun `highlight style option maps to kyant styles`() {
        resolveGlassHighlightStyle(0, 45f) shouldBe HighlightStyle.Default(angle = 45f, falloff = 2f)
        resolveGlassHighlightStyle(1, 45f) shouldBe HighlightStyle.Ambient()
        resolveGlassHighlightStyle(2, 45f) shouldBe HighlightStyle.Plain()
    }

    @Test
    fun `lens parameters clamp to corner radius and shortest side`() {
        // Density 1: 240x40dp capsule -> corner radius = minDimension / 2 = 20dp,
        // shortest side = 40dp. Control Center-style 24/24 lens must not over-reach.
        val params = resolveGlassLensParameters(
            shape = Capsule(),
            size = Size(width = 240f, height = 40f),
            layoutDirection = LayoutDirection.Ltr,
            density = Density(density = 1f, fontScale = 1f),
            requestedHeight = 24f,
            requestedAmount = 60f,
        ) ?: error("capsule should resolve lens parameters")

        params.refractionHeight shouldBe 20f
        params.refractionAmount shouldBe 40f
    }

    @Test
    fun `lens parameters are null on invalid inputs`() {
        val density = Density(density = 1f, fontScale = 1f)
        resolveGlassLensParameters(
            shape = Capsule(),
            size = Size(width = 0f, height = 40f),
            layoutDirection = LayoutDirection.Ltr,
            density = density,
            requestedHeight = 24f,
            requestedAmount = 12f,
        ) shouldBe null

        resolveGlassLensParameters(
            shape = Capsule(),
            size = Size(width = 100f, height = 40f),
            layoutDirection = LayoutDirection.Ltr,
            density = density,
            requestedHeight = 0f,
            requestedAmount = 12f,
        ) shouldBe null
    }

    @Test
    fun `vibrant preset unlocks the color grading recipe`() {
        val values = GlassPreset.VIBRANT.config.values

        assertTrue(values[GlassTuningParam.SATURATION.key] == 1.5f)
        assertTrue(values[GlassTuningParam.BRIGHTNESS.key] == 0.05f)
        assertTrue(values[GlassTuningParam.CHROMATIC_ABERRATION.key] == 1f)
        assertTrue(values[GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key] == 1f)
    }

    @Test
    fun `depth preset enables ambient highlight and depth effect`() {
        val values = GlassPreset.DEPTH.config.values

        assertTrue(values[GlassTuningParam.DEPTH_EFFECT.key] == 1f)
        assertTrue(values[GlassTuningParam.HIGHLIGHT_STYLE.key] == 1f)
        assertTrue(values[GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key] == 1f)
    }
}
