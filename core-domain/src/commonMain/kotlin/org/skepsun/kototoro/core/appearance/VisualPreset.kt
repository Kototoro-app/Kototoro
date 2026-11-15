package org.skepsun.kototoro.core.appearance

/**
 * The preferences a visual preset is allowed to touch.
 *
 * The list is closed on purpose: a preset exists so one press produces a coherent look, not so it quietly rewrites
 * reading, download or navigation choices. [VISUAL_KEYS] is the allowlist the tests assert against.
 */
object VisualPreferenceKeys {
    const val THEME = "theme"
    const val INTERFACE_STYLE = "interface_style"
    const val LANGUAGE = "language"

    val VISUAL_KEYS: Set<String> = setOf(THEME, INTERFACE_STYLE)
}

/**
 * A named combination of visual preferences, expressed with the raw values a platform already stores.
 *
 * [titleKey]/[summaryKey] are string-catalog keys, kept outside [values] so a description can never widen the set of
 * preferences a preset writes.
 */
data class VisualPreset(
    val id: String,
    val titleKey: String,
    val summaryKey: String,
    val values: Map<String, String>,
)

/**
 * What pressing [VisualPreset] does: the values to write now and what stood there before.
 *
 * [undo] holds only the keys the preset actually changes, so undoing cannot resurrect a preference the preset never
 * touched — and cannot roll back something the user changed afterwards.
 */
data class VisualPresetApplication(
    val presetId: String,
    val changes: Map<String, String>,
    val undo: Map<String, String>,
) {
    val isEmpty: Boolean get() = changes.isEmpty()
}

/**
 * Computes what [preset] would change in [current].
 *
 * Keys already holding the preset's value are left out entirely: pressing a preset the reader is effectively already
 * running must not create an undo entry that would "restore" the same value.
 */
fun visualPresetApplication(preset: VisualPreset, current: Map<String, String>): VisualPresetApplication {
    val changes = LinkedHashMap<String, String>()
    val undo = LinkedHashMap<String, String>()
    for ((key, value) in preset.values) {
        require(key in VisualPreferenceKeys.VISUAL_KEYS) { "Preset ${preset.id} writes non-visual key $key" }
        val previous = current[key]
        if (previous == value) continue
        changes[key] = value
        if (previous != null) undo[key] = previous
    }
    return VisualPresetApplication(presetId = preset.id, changes = changes, undo = undo)
}

/** The undo of an application, as the changes that put the previous values back. */
fun visualPresetUndoChanges(application: VisualPresetApplication): Map<String, String> = application.undo

/**
 * The bundles both hosts can offer today. Each one names the two visual keys it sets, so the dialog can say exactly
 * what will change before anything is written.
 */
object VisualPresets {
    const val MATERIAL_COMPACT = "MATERIAL_COMPACT"
    const val DARK_FOCUS = "DARK_FOCUS"
    const val IOS_GROUPED = "IOS_GROUPED"

    val all: List<VisualPreset> = listOf(
        VisualPreset(
            id = MATERIAL_COMPACT,
            titleKey = "visualPreset.material",
            summaryKey = "visualPreset.materialSummary",
            values = linkedMapOf(VisualPreferenceKeys.THEME to "SYSTEM", VisualPreferenceKeys.INTERFACE_STYLE to "MATERIAL"),
        ),
        VisualPreset(
            id = DARK_FOCUS,
            titleKey = "visualPreset.dark",
            summaryKey = "visualPreset.darkSummary",
            values = linkedMapOf(VisualPreferenceKeys.THEME to "DARK", VisualPreferenceKeys.INTERFACE_STYLE to "MATERIAL"),
        ),
        VisualPreset(
            id = IOS_GROUPED,
            titleKey = "visualPreset.ios",
            summaryKey = "visualPreset.iosSummary",
            values = linkedMapOf(VisualPreferenceKeys.THEME to "SYSTEM", VisualPreferenceKeys.INTERFACE_STYLE to "IOS"),
        ),
    )

    fun byId(id: String?): VisualPreset? = all.firstOrNull { it.id == id }
}
