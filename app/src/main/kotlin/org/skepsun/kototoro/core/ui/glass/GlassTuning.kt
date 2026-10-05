package org.skepsun.kototoro.core.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState

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
