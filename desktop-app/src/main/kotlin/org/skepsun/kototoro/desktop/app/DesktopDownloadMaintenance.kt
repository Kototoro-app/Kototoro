package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore

/**
 * Sweeps the unreachable page blocks without asking. Every incremental save publishes a new block and
 * leaves the previous one behind, so the metadata half of the download storage only grows while the
 * queue runs; a manual "reclaim" click is easy to forget and does nothing visible in the meantime.
 * Image blobs are never deleted here: they are content-addressed and shared with the reader index.
 */
internal class DesktopDownloadMaintenance(
    private val store: DesktopDownloadStore,
    private val storage: FileSourcePreferenceStore,
    private val preferences: SourcePreferences,
) {
    var automatic: Boolean
        get() = (preferences.snapshot()[AUTOMATIC] as? SourcePreferenceValue.Toggle)?.value ?: true
        set(value) {
            preferences.edit(SourcePreferenceEdit(
                changes = mapOf(AUTOMATIC to SourcePreferenceValue.Toggle(value)),
            ))
        }

    /** Null when the sweep is off or there was nothing unreachable; the plan is re-checked by [DesktopDownloadStore.cleanup]. */
    suspend fun reclaim(): DesktopDownloadCleanupResult? {
        if (!automatic) return null
        val context = currentCoroutineContext()
        val plan = store.previewCleanup(storage) { context.ensureActive() }
        if (plan.candidates.isEmpty()) return null
        context.ensureActive()
        return store.cleanup(plan, storage) { context.ensureActive() }
    }

    private companion object {
        const val AUTOMATIC = "automatic_cleanup"
    }
}
