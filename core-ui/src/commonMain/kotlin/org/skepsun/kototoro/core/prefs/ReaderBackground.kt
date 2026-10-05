package org.skepsun.kototoro.core.prefs

import androidx.annotation.Keep

/** The reader page background; Android resolves it to theme drawables (`ReaderBackgroundResources.kt`). */
@Keep
enum class ReaderBackground {

    DEFAULT, LIGHT, DARK, WHITE, BLACK, AUTO;
}
