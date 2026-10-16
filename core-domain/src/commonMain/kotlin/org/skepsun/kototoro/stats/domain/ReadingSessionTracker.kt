package org.skepsun.kototoro.stats.domain

data class ReadingSessionPosition(
    val chapterId: Long,
    val page: Int,
    val scroll: Int,
    val percent: Float,
)

data class ReadingSessionSample(
    val startedAt: Long,
    val endedAt: Long,
    val start: ReadingSessionPosition,
    val end: ReadingSessionPosition,
    val pages: Int,
)

/** Platform owners supply visibility/playback state and time; snapshots retain a stable persistence identity. */
class ReadingSessionTracker {
    private var startedAt = 0L
    private var start: ReadingSessionPosition? = null
    private var latest: ReadingSessionPosition? = null
    private var lastEndedAt = 0L
    private val seenPages = mutableSetOf<Int>()

    fun update(position: ReadingSessionPosition, now: Long, active: Boolean, lastVisiblePage: Int = position.page): ReadingSessionSample? {
        require(position.page >= 0 && lastVisiblePage >= position.page && lastVisiblePage - position.page < 10_000)
        val closed = if (!active || latest?.chapterId?.let { it != position.chapterId } == true) finish(now) else null
        if (active) {
            if (start == null) {
                startedAt = maxOf(now, lastEndedAt + 1)
                start = position
            }
            latest = position
            (position.page..lastVisiblePage).forEach(seenPages::add)
        }
        return closed
    }

    fun snapshot(now: Long): ReadingSessionSample? {
        val first = start ?: return null
        val last = latest ?: return null
        return ReadingSessionSample(startedAt, maxOf(startedAt, now), first, last, seenPages.size)
    }

    fun finish(now: Long): ReadingSessionSample? {
        val sample = snapshot(now)
        lastEndedAt = maxOf(lastEndedAt, sample?.endedAt ?: 0)
        start = null
        latest = null
        seenPages.clear()
        return sample
    }
}
