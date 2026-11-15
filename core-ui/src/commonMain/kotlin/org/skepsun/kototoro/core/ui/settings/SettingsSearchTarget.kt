package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** A new id also reveals a repeated search for the same preference. */
data class SettingsSearchTarget(val key: String, val requestId: Int)

val LocalSettingsSearchTarget = staticCompositionLocalOf<SettingsSearchTarget?> { null }

@Composable
fun Modifier.revealSettingsSearchTarget(key: String): Modifier {
    val target = LocalSettingsSearchTarget.current
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(target) {
        if (target?.key == key) {
            // A disclosure may have just composed this row. Wait for its first measured frame.
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    return bringIntoViewRequester(requester).testTag("setting:$key")
}
