package org.skepsun.kototoro.main.ui.compose

internal enum class MainFabMode {
    HIDDEN,
    CONTINUE_READING,
}

internal fun resolveMainFabMode(
    resumeEnabled: Boolean,
): MainFabMode = when {
    resumeEnabled -> MainFabMode.CONTINUE_READING
    else -> MainFabMode.HIDDEN
}
