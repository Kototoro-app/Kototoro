package org.skepsun.kototoro.core.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState

/**
 * Fine-grained, orthogonally combinable glass-finish parameters.
 *
 * Every tunable knob in the "Glass Finish Tuner" settings page is one
 * [GlassTuningParam]. Parameters form six [GlassTuningScope]s: a Global
 * baseline plus five chrome roles (TopBar, BottomBar, PillControl,
 * BottomPanel, Menu). Each scope is stored as one JSON string pref
 * ([GlassScopeConfig]); a role parameter either "follows Global" or carries an
 * explicit override value.
 *
 * Every scope config is initialized on first read with a concrete default:
 * uniform parameters default to "follow Global", per-role-varying parameters
 * (surface alpha, rim, hairline, shadow tint) default to explicit per-role
 * overrides whose values reproduce the exact pre-refactor rendering — so an
 * untouched install looks pixel-identical (see docs/adr/0001).
 */
enum class ParamKind { SWITCH, SLIDER }

enum class GlassTuningParam(
    val key: String,
    val kind: ParamKind,
    val min: Float,
    val max: Float,
    val step: Float,
    /** Baseline default (Global scope / generic). */
    val fallback: Float,
) {
    GLASS_ENABLED("glass_enabled", ParamKind.SWITCH, 0f, 1f, 1f, 1f),
    VIBRANCY("vibrancy", ParamKind.SWITCH, 0f, 1f, 1f, 1f),
    BLUR_RADIUS_DP("blur_radius_dp", ParamKind.SLIDER, 0f, 32f, 1f, 8f),
    LENS_HEIGHT_DP("lens_height_dp", ParamKind.SLIDER, 0f, 48f, 1f, 16f),
    LENS_AMOUNT_DP("lens_amount_dp", ParamKind.SLIDER, 0f, 96f, 1f, 24f),
    DEPTH_EFFECT("depth_effect", ParamKind.SWITCH, 0f, 1f, 1f, 0f),
    CHROMATIC_ABERRATION("chromatic_aberration", ParamKind.SWITCH, 0f, 1f, 1f, 0f),
    SURFACE_ALPHA("surface_alpha", ParamKind.SLIDER, 0.01f, 1f, 0.01f, 0.50f),
    RIM_ENABLED("rim_enabled", ParamKind.SWITCH, 0f, 1f, 1f, 0f),
    RIM_ALPHA("rim_alpha", ParamKind.SLIDER, 0.01f, 1f, 0.01f, 0.65f),
    HAIRLINE_ENABLED("hairline_enabled", ParamKind.SWITCH, 0f, 1f, 1f, 1f),
    HAIRLINE_ALPHA("hairline_alpha", ParamKind.SLIDER, 0f, 1f, 0.01f, 0.24f),
    SHADOW_ENABLED("shadow_enabled", ParamKind.SWITCH, 0f, 1f, 1f, 1f),
    SHADOW_RADIUS_DP("shadow_radius_dp", ParamKind.SLIDER, 0f, 24f, 1f, 4f),
    SHADOW_OFFSET_DP("shadow_offset_dp", ParamKind.SLIDER, 0f, 12f, 1f, 2f),
    SHADOW_ALPHA("shadow_alpha", ParamKind.SLIDER, 0f, 1f, 0.01f, 0.10f),
    // Press feedback — only meaningful for pressable roles.
    PRESS_HIGHLIGHT_ALPHA("press_highlight_alpha", ParamKind.SLIDER, 0f, 1f, 0.01f, 1f),
    PRESS_INNER_SHADOW_RADIUS_DP("press_inner_shadow_radius_dp", ParamKind.SLIDER, 0f, 16f, 1f, 8f),
    PRESS_INNER_SHADOW_ALPHA("press_inner_shadow_alpha", ParamKind.SLIDER, 0f, 1f, 0.01f, 1f),
    PRESS_CHROMATIC_ABERRATION("press_chromatic_aberration", ParamKind.SWITCH, 0f, 1f, 1f, 1f),
    PRESS_SCALE_PERCENT("press_scale_percent", ParamKind.SLIDER, 0f, 30f, 1f, 8f),
    PRESS_LENS_STRENGTH("press_lens_strength", ParamKind.SLIDER, 0f, 1f, 0.01f, 1f),
    ;

    fun asBoolean(value: Float): Boolean = value >= 0.5f

    companion object {
        fun fromKey(key: String): GlassTuningParam? = entries.firstOrNull { it.key == key }
    }
}

enum class GlassTuningScope(
    val prefKey: String,
    val role: GlassComponentRole?,
) {
    GLOBAL("global", null),
    TOP_BAR("top_bar", GlassComponentRole.TopBar),
    BOTTOM_BAR("bottom_bar", GlassComponentRole.BottomBar),
    PILL_CONTROL("pill_control", GlassComponentRole.PillControl),
    BOTTOM_PANEL("bottom_panel", GlassComponentRole.BottomPanel),
    MENU("menu", GlassComponentRole.Menu),
    ;

    val storageKey: String = "glass_tuning_$prefKey"

    companion object {
        fun fromRole(role: GlassComponentRole): GlassTuningScope =
            entries.firstOrNull { it.role == role } ?: GLOBAL
    }
}

@Serializable
data class GlassScopeConfig(
    /** Explicit override values, keyed by [GlassTuningParam.key]. */
    val values: Map<String, Float> = emptyMap(),
    /** Parameters that follow the Global scope (keys in [GlassTuningParam.key]). */
    val followGlobal: Set<String> = emptySet(),
    /** Whether the default config has been materialized for this scope. */
    val initialized: Boolean = false,
) {

    fun value(param: GlassTuningParam): Float? = values[param.key]

    fun isFollowing(param: GlassTuningParam): Boolean = param.key in followGlobal

    fun setValue(scope: GlassTuningScope, param: GlassTuningParam, value: Float): GlassScopeConfig =
        GlassScopeConfig(
            values = values + (param.key to value.coerceIn(param.min, param.max)),
            followGlobal = followGlobal - param.key,
            initialized = true,
        )

    fun setFollowGlobal(scope: GlassTuningScope, param: GlassTuningParam, follow: Boolean): GlassScopeConfig =
        if (follow) {
            GlassScopeConfig(
                values = values - param.key,
                followGlobal = followGlobal + param.key,
                initialized = true,
            )
        } else {
            // Un-following snaps to the legacy per-role default for that param.
            GlassScopeConfig(
                values = values + (param.key to GlassTuning.legacyFallback(scope, param)),
                followGlobal = followGlobal - param.key,
                initialized = true,
            )
        }

    /** A config where every parameter follows the Global scope (used by presets). */
    fun followAll(): GlassScopeConfig = GlassScopeConfig(
        followGlobal = GlassTuningParam.entries.mapTo(mutableSetOf()) { it.key },
        initialized = true,
    )
}

object GlassTuning {

    /**
     * Parameters whose rendering is uniform across roles and therefore default
     * to "follow Global". The remaining per-role-varying set (surface alpha,
     * rim on/off, hairline on/off + alpha, shadow tint) defaults to an explicit
     * per-role override so an untouched install keeps the exact legacy look.
     */
    val uniformParams: Set<GlassTuningParam> = setOf(
        GlassTuningParam.GLASS_ENABLED,
        GlassTuningParam.VIBRANCY,
        GlassTuningParam.BLUR_RADIUS_DP,
        GlassTuningParam.LENS_HEIGHT_DP,
        GlassTuningParam.LENS_AMOUNT_DP,
        GlassTuningParam.DEPTH_EFFECT,
        GlassTuningParam.CHROMATIC_ABERRATION,
        GlassTuningParam.RIM_ALPHA,
        GlassTuningParam.SHADOW_ENABLED,
        GlassTuningParam.SHADOW_RADIUS_DP,
        GlassTuningParam.SHADOW_OFFSET_DP,
        GlassTuningParam.PRESS_HIGHLIGHT_ALPHA,
        GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP,
        GlassTuningParam.PRESS_INNER_SHADOW_ALPHA,
        GlassTuningParam.PRESS_CHROMATIC_ABERRATION,
        GlassTuningParam.PRESS_SCALE_PERCENT,
        GlassTuningParam.PRESS_LENS_STRENGTH,
    )

    /** Roles that actually expose press feedback in their tuning UI. */
    val pressableRoles: Set<GlassTuningScope> = setOf(
        GlassTuningScope.BOTTOM_BAR,
        GlassTuningScope.PILL_CONTROL,
        GlassTuningScope.BOTTOM_PANEL,
    )

    val pressFeedbackParams: Set<GlassTuningParam> = setOf(
        GlassTuningParam.PRESS_HIGHLIGHT_ALPHA,
        GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP,
        GlassTuningParam.PRESS_INNER_SHADOW_ALPHA,
        GlassTuningParam.PRESS_CHROMATIC_ABERRATION,
        GlassTuningParam.PRESS_SCALE_PERCENT,
        GlassTuningParam.PRESS_LENS_STRENGTH,
    )

    fun paramsForScope(scope: GlassTuningScope): List<GlassTuningParam> =
        if (scope == GlassTuningScope.GLOBAL || scope in pressableRoles) {
            GlassTuningParam.entries
        } else {
            GlassTuningParam.entries.filterNot { it in pressFeedbackParams }
        }

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(config: GlassScopeConfig): String = json.encodeToString(config)

    fun decode(raw: String?): GlassScopeConfig =
        if (raw.isNullOrBlank()) GlassScopeConfig()
        else runCatching { json.decodeFromString<GlassScopeConfig>(raw) }.getOrDefault(GlassScopeConfig())

    /** Default config for a scope — materialized lazily on first read. */
    fun defaultConfig(scope: GlassTuningScope): GlassScopeConfig {
        if (scope == GlassTuningScope.GLOBAL) {
            return GlassScopeConfig(
                initialized = true,
                values = GlassTuningParam.entries.associate { it.key to it.fallback },
            )
        }
        val follow = uniformParams.mapTo(mutableSetOf()) { it.key }
        val overrides = mutableMapOf<String, Float>()
        overrides[GlassTuningParam.SURFACE_ALPHA.key] = legacyFallback(scope, GlassTuningParam.SURFACE_ALPHA)
        overrides[GlassTuningParam.DEPTH_EFFECT.key] = legacyFallback(scope, GlassTuningParam.DEPTH_EFFECT)
        overrides[GlassTuningParam.RIM_ENABLED.key] = legacyFallback(scope, GlassTuningParam.RIM_ENABLED)
        overrides[GlassTuningParam.HAIRLINE_ENABLED.key] = legacyFallback(
            scope,
            GlassTuningParam.HAIRLINE_ENABLED,
        )
        overrides[GlassTuningParam.HAIRLINE_ALPHA.key] = legacyFallback(scope, GlassTuningParam.HAIRLINE_ALPHA)
        overrides[GlassTuningParam.SHADOW_ALPHA.key] = legacyFallback(scope, GlassTuningParam.SHADOW_ALPHA)
        return GlassScopeConfig(
            values = overrides,
            followGlobal = follow,
            initialized = true,
        )
    }

    /**
     * Exact pre-refactor values, so untouched installs render identically.
     * Mirrors the old hardcoded LiquidGlassSurface / KototoroBottomNav math.
     */
    fun legacyFallback(scope: GlassTuningScope, param: GlassTuningParam): Float {
        return when (param) {
            GlassTuningParam.SURFACE_ALPHA -> when (scope) {
                GlassTuningScope.TOP_BAR -> 0.53f
                GlassTuningScope.BOTTOM_BAR -> 0.50f
                GlassTuningScope.PILL_CONTROL -> 0.49f
                GlassTuningScope.BOTTOM_PANEL -> 0.41f
                GlassTuningScope.MENU -> 0.20f
                GlassTuningScope.GLOBAL -> 0.50f
            }
            GlassTuningParam.DEPTH_EFFECT -> if (scope == GlassTuningScope.GLOBAL) 1f else 0f
            GlassTuningParam.RIM_ENABLED -> if (scope == GlassTuningScope.GLOBAL || scope == GlassTuningScope.MENU) 1f else 0f
            GlassTuningParam.HAIRLINE_ENABLED -> if (scope == GlassTuningScope.TOP_BAR) 0f else 1f
            GlassTuningParam.HAIRLINE_ALPHA -> when (scope) {
                GlassTuningScope.TOP_BAR -> 0f
                GlassTuningScope.BOTTOM_BAR -> 0.10f
                else -> 0.24f
            }
            GlassTuningParam.SHADOW_ALPHA -> if (scope == GlassTuningScope.BOTTOM_PANEL ||
                scope == GlassTuningScope.MENU
            ) {
                0.06f
            } else {
                0.10f
            }
            else -> param.fallback
        }
    }
}

/** Snapshots of all scope configs plus resolution. See [LocalGlassTuning]. */
@Immutable
class GlassTuningState(
    val configs: Map<GlassTuningScope, GlassScopeConfig>,
) {

    fun config(scope: GlassTuningScope): GlassScopeConfig =
        configs[scope] ?: GlassTuning.defaultConfig(scope)

    /** Boolean effective value for a switch parameter. */
    fun isOn(scope: GlassTuningScope, param: GlassTuningParam): Boolean =
        param.asBoolean(value(scope, param))

    /** Float effective value for a parameter in a scope. */
    fun value(scope: GlassTuningScope, param: GlassTuningParam): Float {
        val cfg = config(scope)
        if (scope != GlassTuningScope.GLOBAL && cfg.isFollowing(param)) {
            return value(GlassTuningScope.GLOBAL, param)
        }
        cfg.value(param)?.let { return it }
        return GlassTuning.legacyFallback(scope, param)
    }

    fun isFollowingGlobal(scope: GlassTuningScope, param: GlassTuningParam): Boolean =
        scope != GlassTuningScope.GLOBAL && config(scope).isFollowing(param)

    fun withValues(overrides: Map<Pair<GlassTuningScope, GlassTuningParam>, Float>): GlassTuningState {
        if (overrides.isEmpty()) return this
        val previewConfigs = configs.toMutableMap()
        overrides.forEach { (key, value) ->
            val (scope, param) = key
            previewConfigs[scope] = config(scope).setValue(scope, param, value)
        }
        return GlassTuningState(previewConfigs)
    }
}

/**
 * Live holder: lazily initializes and mutates the six tuning prefs.
 * Prefer going through [rememberGlassTuning] / [LocalGlassTuning].
 */
class GlassTuningController(private val appSettings: AppSettings) {

    fun snapshot(scope: GlassTuningScope): GlassScopeConfig {
        var config = GlassTuning.decode(appSettings.glassTuningRaw(scope))
        if (!config.initialized) {
            config = GlassTuning.defaultConfig(scope)
            appSettings.setGlassTuningRaw(scope, GlassTuning.encode(config))
        }
        return config
    }

    fun setValue(scope: GlassTuningScope, param: GlassTuningParam, value: Float) {
        val next = snapshot(scope).setValue(scope, param, value)
        appSettings.setGlassTuningRaw(scope, GlassTuning.encode(next))
    }

    fun setFollowGlobal(scope: GlassTuningScope, param: GlassTuningParam, follow: Boolean) {
        val next = snapshot(scope).setFollowGlobal(scope, param, follow)
        appSettings.setGlassTuningRaw(scope, GlassTuning.encode(next))
    }

    /**
     * Replaces the Global scope with a preset config and makes every role follow
     * it entirely — a preset is authoritative over the per-role legacy overrides.
     */
    fun applyPreset(config: GlassScopeConfig) {
        appSettings.setGlassTuningRaw(
            GlassTuningScope.GLOBAL,
            GlassTuning.encode(config.copy(initialized = true)),
        )
        GlassTuningScope.entries
            .filter { it != GlassTuningScope.GLOBAL }
            .forEach { role ->
                appSettings.setGlassTuningRaw(role, GlassTuning.encode(GlassScopeConfig().followAll()))
            }
    }

    /** Restores legacy behavior for a single scope. */
    fun resetScope(scope: GlassTuningScope) {
        appSettings.removeGlassTuning(scope)
    }

    fun resetAll() {
        GlassTuningScope.entries.forEach {
            appSettings.setGlassTuningRaw(it, GlassTuning.encode(GlassTuning.defaultConfig(it)))
        }
    }
}

val LocalGlassTuning = staticCompositionLocalOf<GlassTuningState?> { null }

/**
 * Observes all six tuning prefs (lazily materializing defaults) and returns a
 * [GlassTuningState] snapshot.
 */
@Composable
fun rememberGlassTuning(appSettings: AppSettings): GlassTuningState {
    val keys = GlassTuningScope.entries.map { it.storageKey }.toTypedArray()
    val state by appSettings.observeAsState(*keys) {
        val controller = GlassTuningController(this)
        GlassTuningState(GlassTuningScope.entries.associateWith { controller.snapshot(it) })
    }
    return state
}

/** An empty tuning state that resolves every parameter to its legacy default. */
fun emptyGlassTuningState(): GlassTuningState = GlassTuningState(emptyMap())
