package org.skepsun.kototoro.main.ui.compose

import org.skepsun.kototoro.R

internal enum class MainFabMode {
    HIDDEN,
    CONTINUE_READING,
}

internal enum class MainResumeAction(
    val iconRes: Int,
    val contentDescriptionRes: Int,
) {
    READ(R.drawable.ic_read, R.string._continue),
}

internal fun resolveMainFabMode(
    resumeEnabled: Boolean,
): MainFabMode = when {
    resumeEnabled -> MainFabMode.CONTINUE_READING
    else -> MainFabMode.HIDDEN
}
