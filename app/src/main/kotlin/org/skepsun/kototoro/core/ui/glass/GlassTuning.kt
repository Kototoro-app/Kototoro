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
enum class ParamKind { SWITCH, SLIDER, OPTION }

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
    SATURATION("saturation", ParamKind.SLIDER, 0.5f, 2.0f, 0.05f, 1f),
    BRIGHTNESS("brightness", ParamKind.SLIDER, -0.5f, 0.5f, 0.05f, 0f),
    BLUR_RADIUS_DP("blur_radius_dp", ParamKind.SLIDER, 0f, 32f, 1f, 8f),
    LENS_HEIGHT_DP("lens_height_dp", ParamKind.SLIDER, 0f, 48f, 1f, 16f),
    LENS_AMOUNT_DP("lens_amount_dp", ParamKind.SLIDER, 0f, 96f, 1f, 24f),
    DEPTH_EFFECT("depth_effect", ParamKind.SWITCH, 0f, 1f, 1f, 0f),
    CHROMATIC_ABERRATION("chromatic_aberration", ParamKind.SWITCH, 0f, 1f, 1f, 0f),
    SURFACE_ALPHA("surface_alpha", ParamKind.SLIDER, 0.01f, 1f, 0.01f, 0.50f),
    RIM_ENABLED("rim_enabled", ParamKind.SWITCH, 0f, 1f, 1f, 0f),
    RIM_ALPHA("rim_alpha", ParamKind.SLIDER, 0.01f, 1f, 0.01f, 0.65f),
    HIGHLIGHT_STYLE("highlight_style", ParamKind.OPTION, 0f, 2f, 1f, 0f),
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
        GlassTuningParam.SATURATION,
        GlassTuningParam.BRIGHTNESS,
        GlassTuningParam.BLUR_RADIUS_DP,
        GlassTuningParam.LENS_HEIGHT_DP,
        GlassTuningParam.LENS_AMOUNT_DP,
        GlassTuningParam.DEPTH_EFFECT,
        GlassTuningParam.CHROMATIC_ABERRATION,
        GlassTuningParam.RIM_ALPHA,
        GlassTuningParam.HIGHLIGHT_STYLE,
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

    fun encodeCustomPresets(presets: List<GlassCustomPreset>): String =
        json.encodeToString(presets)

    fun decodeCustomPresets(raw: String?): List<GlassCustomPreset> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<GlassCustomPreset>>(raw) }.getOrDefault(emptyList())

    /**
     * The "折射 / Refraction" glass preset — the default finish for fresh
     * installs: a crisp low-blur surface with a strong refractive lens.
     * Kept in core so the default materialization, the reset target and the
     * preset pickers (Settings + setup wizard) all share one source of truth.
     */
    val refractionPresetValues: Map<String, Float> = mapOf(
        GlassTuningParam.GLASS_ENABLED.key to 1f,
        GlassTuningParam.VIBRANCY.key to 1f,
        GlassTuningParam.SATURATION.key to 1f,
        GlassTuningParam.BRIGHTNESS.key to 0f,
        GlassTuningParam.BLUR_RADIUS_DP.key to 2f,
        GlassTuningParam.LENS_HEIGHT_DP.key to 16f,
        GlassTuningParam.LENS_AMOUNT_DP.key to 44f,
        GlassTuningParam.DEPTH_EFFECT.key to 0f,
        GlassTuningParam.CHROMATIC_ABERRATION.key to 0f,
        GlassTuningParam.SURFACE_ALPHA.key to 0.40f,
        GlassTuningParam.RIM_ENABLED.key to 0f,
        GlassTuningParam.RIM_ALPHA.key to 0.5f,
        GlassTuningParam.HIGHLIGHT_STYLE.key to 0f,
        GlassTuningParam.HAIRLINE_ENABLED.key to 1f,
        GlassTuningParam.HAIRLINE_ALPHA.key to 0.25f,
        GlassTuningParam.SHADOW_ENABLED.key to 1f,
        GlassTuningParam.SHADOW_RADIUS_DP.key to 4f,
        GlassTuningParam.SHADOW_OFFSET_DP.key to 2f,
        GlassTuningParam.SHADOW_ALPHA.key to 0.10f,
        GlassTuningParam.PRESS_HIGHLIGHT_ALPHA.key to 1f,
        GlassTuningParam.PRESS_INNER_SHADOW_RADIUS_DP.key to 8f,
        GlassTuningParam.PRESS_INNER_SHADOW_ALPHA.key to 0.8f,
        GlassTuningParam.PRESS_CHROMATIC_ABERRATION.key to 0f,
        GlassTuningParam.PRESS_SCALE_PERCENT.key to 6f,
        GlassTuningParam.PRESS_LENS_STRENGTH.key to 1f,
    )

    /**
     * Default config for a scope — materialized lazily on first read.
     * Defaults to the "折射 / Refraction" preset: the Global scope carries the
     * refraction values and every role follows it (the preset is flat).
     */
    fun defaultConfig(scope: GlassTuningScope): GlassScopeConfig {
        if (scope == GlassTuningScope.GLOBAL) {
            return GlassScopeConfig(
                initialized = true,
                values = refractionPresetValues,
            )
        }
        return GlassScopeConfig().followAll()
    }

    /**
     * Role config for a preset with role-specific overrides: the overridden
     * keys resolve to the given values, every other parameter keeps following
     * the preset's Global scope. This is the "Global + per-role delta" preset
     * model — a preset is a global baseline plus optional role tweaks (e.g.
     * menus without shadows, pills with stronger rims).
     */
    fun presetScopeConfig(overrides: Map<String, Float>): GlassScopeConfig {
        val follow = GlassTuningParam.entries.mapTo(mutableSetOf()) { it.key } - overrides.keys
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

    /**
     * Whether a tuning state exactly matches a preset described by a Global
     * value map plus optional per-role deltas. Undeclared roles must follow
     * Global entirely; roles with deltas must resolve their overridden keys to
     * the delta values locally and keep everything else following Global.
     */
    fun matches(
        tuning: GlassTuningState,
        globalValues: Map<String, Float>,
        roleOverrides: Map<GlassTuningScope, Map<String, Float>>,
    ): Boolean {
        val global = tuning.config(GlassTuningScope.GLOBAL)
        val globalMatches = globalValues.all { (key, value) ->
            val param = GlassTuningParam.fromKey(key) ?: return@all false
            // Resolve missing keys exactly like GlassTuningState does — legacy
            // GLOBAL fallback, not the bare param fallback (they differ for
            // RIM_ENABLED / DEPTH_EFFECT at the Global scope).
            (global.value(param) ?: legacyFallback(GlassTuningScope.GLOBAL, param)) == value
        }
        val allParams = GlassTuningParam.entries.mapTo(mutableSetOf()) { it.key }
        val rolesFollowPreset = GlassTuningScope.entries
            .filter { it != GlassTuningScope.GLOBAL }
            .all { scope ->
                val overrides = roleOverrides[scope]
                val roleConfig = tuning.config(scope)
                if (overrides == null) {
                    roleConfig.followGlobal.containsAll(allParams)
                } else {
                    overrides.all { (key, value) ->
                        val param = GlassTuningParam.fromKey(key) ?: return@all false
                        key !in roleConfig.followGlobal &&
                            (roleConfig.value(param) ?: legacyFallback(scope, param)) == value
                    }
                }
            }
        return globalMatches && rolesFollowPreset
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
     * Replaces the Global scope with a preset config, then makes every role
     * follow it — a preset is authoritative over the per-role legacy overrides.
     * Roles listed in [roleOverrides] get a "Global + delta" config instead:
     * their overridden keys resolve to the given values while everything else
     * keeps following the preset's Global scope.
     */
    fun applyPreset(
        config: GlassScopeConfig,
        roleOverrides: Map<GlassTuningScope, Map<String, Float>> = emptyMap(),
    ) {
        appSettings.setGlassTuningRaw(
            GlassTuningScope.GLOBAL,
            GlassTuning.encode(config.copy(initialized = true)),
        )
        GlassTuningScope.entries
            .filter { it != GlassTuningScope.GLOBAL }
            .forEach { role ->
                val overrides = roleOverrides[role]
                val roleConfig =
                    if (overrides == null) {
                        GlassScopeConfig().followAll()
                    } else {
                        GlassTuning.presetScopeConfig(overrides)
                    }
                appSettings.setGlassTuningRaw(role, GlassTuning.encode(roleConfig))
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

/**
 * A user-saved preset: a Global baseline (all parameter keys) plus optional
 * per-role deltas keyed by [GlassTuningScope.prefKey]. Stored as one JSON list
 * under [AppSettings.KEY_CUSTOM_GLASS_PRESETS]; serialization lives in
 * [GlassTuning.encodeCustomPresets] / [GlassTuning.decodeCustomPresets].
 */
@Serializable
data class GlassCustomPreset(
    val id: String,
    val name: String,
    val global: Map<String, Float>,
    val roleOverrides: Map<String, Map<String, Float>> = emptyMap(),
) {
    val config: GlassScopeConfig
        get() = GlassScopeConfig(values = global, initialized = true)

    fun scopeOverrides(): Map<GlassTuningScope, Map<String, Float>> =
        roleOverrides.mapNotNull { (key, values) ->
            val scope = GlassTuningScope.entries.firstOrNull { it.prefKey == key }
            if (scope == null || scope == GlassTuningScope.GLOBAL) null else scope to values
        }.toMap()

    fun matches(tuning: GlassTuningState): Boolean =
        GlassTuning.matches(tuning, global, scopeOverrides())
}

/**
 * Snapshots the current tuning (effective Global values + the per-role deltas
 * the user has un-followed) into a [GlassCustomPreset] ready to persist.
 */
fun GlassTuningState.toCustomPreset(id: String, name: String): GlassCustomPreset {
    val globalValues = GlassTuningParam.entries.associate { param ->
        param.key to value(GlassTuningScope.GLOBAL, param)
    }
    val roleOverrides = GlassTuningScope.entries
        .filter { it != GlassTuningScope.GLOBAL }
        .mapNotNull { scope ->
            val scopeConfig = config(scope)
            val overrides = GlassTuningParam.entries
                .filter { !scopeConfig.isFollowing(it) }
                .associate { it.key to value(scope, it) }
            if (overrides.isEmpty()) null else scope.prefKey to overrides
        }
        .toMap()
    return GlassCustomPreset(
        id = id,
        name = name,
        global = globalValues,
        roleOverrides = roleOverrides,
    )
}

/**
 * Observes the user-saved glass presets list.
 */
@Composable
fun rememberGlassCustomPresets(appSettings: AppSettings): List<GlassCustomPreset> {
    val raw by appSettings.observeAsState(AppSettings.KEY_CUSTOM_GLASS_PRESETS) {
        appSettings.customGlassPresetsRaw()
    }
    return GlassTuning.decodeCustomPresets(raw)
}
