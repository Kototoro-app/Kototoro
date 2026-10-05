package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import org.skepsun.kototoro.reader.novel.NovelReaderPalette

/**
 * The novel chrome's Material scheme under MD3, where the chrome falls back to opaque surfaces
 * that would otherwise take the app's cool surface colours on a warm reading theme.
 */
internal fun novelChromeColorScheme(base: ColorScheme, palette: NovelReaderPalette): ColorScheme =
    novelChromeColorScheme(base, palette.chromeColors())

internal fun NovelReaderPalette.chromeColors() = NovelReaderChromeColors(
    background = Color(chromeBackgroundColor),
    content = Color(chromeTextColor),
    secondary = Color(secondaryTextColor),
)

/** MD3 chrome follows the reading theme; iOS glass chrome keeps its own tint. */
@Composable
internal fun NovelChromeTheme(palette: NovelReaderPalette, enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, palette) { novelChromeColorScheme(base, palette) }
    MaterialTheme(colorScheme = scheme, content = content)
}
