package org.skepsun.kototoro.core.model

/**
 * In-memory snapshot of per-source NSFW overrides.
 *
 * A source's NSFW state normally comes from its extension metadata or content type.
 * Users can override it per source from the extension management screen (see
 * [org.skepsun.kototoro.core.prefs.AppSettings.setSourceNsfwOverride]); this holder mirrors the
 * persisted overrides so that [isNsfw] can consult them without a Context or preferences dependency.
 *
 * The snapshot is updated by [org.skepsun.kototoro.core.prefs.AppSettings] on construction and on
 * every write, so reads from any thread observe the latest persisted state.
 */
object SourceNsfwOverrides {

    @Volatile
    private var nsfwNames: Set<String> = emptySet()

    @Volatile
    private var sfwNames: Set<String> = emptySet()

    /**
     * Replaces the current override snapshot.
     */
    fun update(nsfw: Set<String>, sfw: Set<String>) {
        nsfwNames = nsfw.toSet()
        sfwNames = sfw.toSet()
    }

    /**
     * Returns true when the source is forced NSFW, false when it is forced SFW,
     * or null when there is no override and the source metadata should be used.
     */
    fun resolve(sourceName: String): Boolean? = when {
        sourceName in sfwNames -> false
        sourceName in nsfwNames -> true
        else -> null
    }
}
