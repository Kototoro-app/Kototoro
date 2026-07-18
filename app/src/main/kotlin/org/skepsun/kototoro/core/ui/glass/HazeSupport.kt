package org.skepsun.kototoro.core.ui.glass

import android.os.Build
import androidx.compose.runtime.Composable
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle

internal fun supportsRuntimeHaze(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
internal fun isRuntimeHazeAvailable(): Boolean =
    LocalInterfaceStyle.current != InterfaceStyle.IOS && supportsRuntimeHaze()
