package org.skepsun.kototoro.core.prefs

import androidx.annotation.Keep

@Keep
enum class ReaderMode(val id: Int) {

    STANDARD(1),
    REVERSED(3),
    VERTICAL(4),
    WEBTOON(2),
    CONTINUOUS_HORIZONTAL(5),
    ;

    val isContinuous: Boolean get() = this == WEBTOON || this == CONTINUOUS_HORIZONTAL
    val isPaged: Boolean get() = !isContinuous

    companion object {

        fun valueOf(id: Int) = entries.firstOrNull { it.id == id }
    }
}
