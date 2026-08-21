package org.skepsun.kototoro.core.prefs

import androidx.annotation.Keep

@Keep
enum class ScreenshotsPolicy {

    // Do not rename this
    ALLOW, BLOCK_NSFW, BLOCK_INCOGNITO, BLOCK_ALL;
}
