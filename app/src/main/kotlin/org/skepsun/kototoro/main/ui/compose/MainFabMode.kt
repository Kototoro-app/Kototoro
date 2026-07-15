package org.skepsun.kototoro.main.ui.compose

internal enum class MainFabMode {
    HIDDEN,
    SPACE_SWITCHER,
    CONTINUE_READING,
}

internal fun resolveMainFabMode(
    spaceSwitcherEnabled: Boolean,
    resumeEnabled: Boolean,
): MainFabMode = when {
    spaceSwitcherEnabled -> MainFabMode.SPACE_SWITCHER
    resumeEnabled -> MainFabMode.CONTINUE_READING
    else -> MainFabMode.HIDDEN
}
