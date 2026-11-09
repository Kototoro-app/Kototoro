package org.skepsun.kototoro.ios

/**
 * Progress of a long library operation, for the app's own progress and the system's background-task progress.
 * Called on a background thread. [stage] is a stable key the app turns into text: a backup section's entry name
 * while a backup is written or merged, `upload` / `download` while a file is transferred. [total] is 0 when the size
 * is not known in advance; [completed] then only grows.
 */
interface IosProgressListener {
    fun onProgress(stage: String, completed: Long, total: Long)
}

/** The favourite update check's running counts, as Android's checking notification shows them. */
data class IosUpdateProgress(
    val checked: Int,
    val total: Int,
    val withUpdates: Int,
    val newChapters: Int,
    val failed: Int,
    val skipped: Int,
)

/** Called on a background thread: once with the number of works to check, then after each one. */
interface IosUpdateProgressListener {
    fun onUpdateProgress(progress: IosUpdateProgress)
}

/**
 * Forwards a report on every stage change and otherwise at most once per thousandth of the total, so per-row and
 * per-buffer loops do not flood the app's main thread.
 */
internal class ProgressThrottle(private val listener: IosProgressListener?) {
    private var stage: String? = null
    private var step = -1L

    fun report(stage: String, completed: Long, total: Long) {
        val listener = listener ?: return
        val step = if (total > 0) completed * 1000 / total else completed / UNKNOWN_STEP
        if (stage == this.stage && step == this.step) return
        this.stage = stage
        this.step = step
        listener.onProgress(stage, completed, total)
    }

    private companion object {
        /** Without a total, a stage reports on entry and then once per 256 KiB of transferred bytes. */
        const val UNKNOWN_STEP = 256L * 1024
    }
}
