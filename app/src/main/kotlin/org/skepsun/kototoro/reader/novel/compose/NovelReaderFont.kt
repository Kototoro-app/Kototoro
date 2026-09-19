package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import org.skepsun.kototoro.core.ui.theme.OnlineFontPreset
import org.skepsun.kototoro.reader.novel.NovelReaderFont

internal val NovelReaderFont.fallbackFamily: FontFamily
    get() = when (this) {
        NovelReaderFont.SOURCE_HAN_SERIF,
        NovelReaderFont.LXGW_WENKAI,
        NovelReaderFont.SYSTEM_SERIF,
        -> FontFamily.Serif
        NovelReaderFont.NOTO_SANS,
        NovelReaderFont.SYSTEM_SANS,
        -> FontFamily.SansSerif
        NovelReaderFont.SYSTEM_CURSIVE -> FontFamily.Cursive
        NovelReaderFont.SYSTEM_MONOSPACE -> FontFamily.Monospace
    }

internal val NovelReaderFont.onlinePreset: OnlineFontPreset?
    get() = when (this) {
        NovelReaderFont.SOURCE_HAN_SERIF -> OnlineFontPreset.SOURCE_HAN_SERIF_SC
        NovelReaderFont.LXGW_WENKAI -> OnlineFontPreset.LXGW_WENKAI
        NovelReaderFont.NOTO_SANS -> OnlineFontPreset.NOTO_SANS_CJK_SC
        NovelReaderFont.SYSTEM_SERIF,
        NovelReaderFont.SYSTEM_SANS,
        NovelReaderFont.SYSTEM_CURSIVE,
        NovelReaderFont.SYSTEM_MONOSPACE,
        -> null
    }

@Composable
internal fun rememberNovelReaderFontFamily(font: NovelReaderFont): FontFamily {
    val appContext = LocalContext.current.applicationContext
    val onlineFontLoader = remember(appContext) {
        EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(appContext).onlineFontLoader
    }
    val loadedFamily by produceState<FontFamily?>(initialValue = null, font, onlineFontLoader) {
        value = font.onlinePreset?.let { onlineFontLoader.load(it) }
    }
    return loadedFamily ?: font.fallbackFamily
}

@Composable
internal fun NovelReaderFontOptionRow(
    selected: NovelReaderFont,
    onSelected: (NovelReaderFont) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.novel_font),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NovelReaderFont.entries.forEach { font ->
                val isSelected = font == selected
                Surface(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onSelected(font) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    border = if (isSelected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                ) {
                    Text(
                        text = stringResource(font.displayNameRes),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = font.fallbackFamily),
                    )
                }
            }
        }
    }
}
