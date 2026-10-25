package org.skepsun.kototoro.core.extensions

/**
 * What the one NSFW button of Android's unified-source selection bar
 * (`UnifiedSourcesScreenBody.UnifiedSourceSelectionBar`) does for the current selection.
 */
enum class SelectedSourcesNsfwAction {
    NONE,
    SET_NSFW,
    SET_SFW,
    CHOOSE,
}

/**
 * Decides what the NSFW chip should do: set NSFW when everything selected is non-NSFW, set SFW when everything is
 * NSFW, and ask the user when the selection mixes both. The rule lives here rather than in Android's screen file
 * because Windows draws the same chip and must not invent a second, subtly different behaviour.
 */
fun resolveSelectedSourcesNsfwAction(
    nsfwCount: Int,
    selectedCount: Int,
): SelectedSourcesNsfwAction = when {
    selectedCount <= 0 -> SelectedSourcesNsfwAction.NONE
    nsfwCount <= 0 -> SelectedSourcesNsfwAction.SET_NSFW
    nsfwCount >= selectedCount -> SelectedSourcesNsfwAction.SET_SFW
    else -> SelectedSourcesNsfwAction.CHOOSE
}

/**
 * The two stored sets behind Android's `source_nsfw_overrides` / `source_sfw_overrides`: a source's adult flag normally
 * comes from its extension metadata or content type, and the user may force it either way from the source settings.
 *
 * Both hosts keep the sets apart instead of storing a `Boolean` per source, so "no opinion" stays expressive: a name
 * in neither set follows the metadata, and a name in both is retired by the next write (see [withOverride]).
 */
data class SourceNsfwOverrideSet(
    val nsfw: Set<String> = emptySet(),
    val sfw: Set<String> = emptySet(),
) {

    /** True when forced adult, false when forced SFW, null when the metadata decides. SFW wins, like Android. */
    fun resolve(sourceName: String): Boolean? = when (sourceName) {
        in sfw -> false
        in nsfw -> true
        else -> null
    }

    /**
     * Android's `AppSettings.setSourceNsfwOverride(names, isNsfw)`: `true` forces the sources adult, `false` forces
     * them SFW, and `null` retires the override in both directions so the metadata decides again. Blank names are
     * dropped and the two sets never both hold the same source afterwards.
     */
    fun withOverride(sourceNames: Collection<String>, isNsfw: Boolean?): SourceNsfwOverrideSet {
        val names = sourceNames.filterTo(LinkedHashSet(), String::isNotBlank)
        if (names.isEmpty()) return this
        return when (isNsfw) {
            true -> copy(nsfw = nsfw + names, sfw = sfw - names)
            false -> copy(nsfw = nsfw - names, sfw = sfw + names)
            null -> copy(nsfw = nsfw - names, sfw = sfw - names)
        }
    }
}

/**
 * The subset of [sourceNames] whose current flag differs from [isNsfw] — Android persists overrides only for the
 * sources that actually change, so marking a mixed selection never rewrites the ones that already agree.
 */
fun changedNsfwSources(
    sourceNames: Collection<String>,
    isNsfw: Boolean,
    isNsfwNow: (String) -> Boolean,
): Set<String> = sourceNames.filterTo(LinkedHashSet()) { isNsfwNow(it) != isNsfw }
