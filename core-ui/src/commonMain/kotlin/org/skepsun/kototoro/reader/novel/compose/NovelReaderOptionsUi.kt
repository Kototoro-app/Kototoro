package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChoiceChips
import org.skepsun.kototoro.reader.ui.compose.ReaderOptionsPageList
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSection
import org.skepsun.kototoro.reader.ui.compose.design.ReaderStepperRow
import org.skepsun.kototoro.reader.ui.compose.design.currentReaderPanelColors

/** Hosts supply labels, ranges and persistence; the controls own only their presentation. */
data class NovelReaderValueOption(
    val label: String,
    val valueLabel: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val onValueChange: (Float) -> Unit,
    val tag: String = "",
)

@Immutable
data class NovelReaderThemeSwatch(
    val id: String,
    val label: String,
    val background: Color,
    val text: Color,
)

/** Android and desktop share the peek layout, with platform capabilities supplied as slots. */
@Composable
fun NovelReaderQuickLayer(
    fontSize: NovelReaderValueOption,
    themes: List<NovelReaderThemeSwatch>,
    selectedTheme: String,
    onThemeSelected: (String) -> Unit,
    brightness: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        brightness()
        NovelReaderValueStepper(fontSize, contentPadding = PaddingValues(0.dp))
        NovelReaderThemeSwatches(themes, selectedTheme, onThemeSelected)
        actions()
    }
}

@Composable
fun NovelReaderValueStepper(
    option: NovelReaderValueOption,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
) {
    ReaderStepperRow(
        label = option.label,
        valueLabel = option.valueLabel,
        value = option.value,
        valueRange = option.range,
        step = option.step,
        onValueChange = option.onValueChange,
        modifier = if (option.tag.isEmpty()) Modifier else Modifier.testTag(option.tag),
        contentPadding = contentPadding,
    )
}

@Composable
fun NovelReaderThemeSwatches(
    themes: List<NovelReaderThemeSwatch>,
    selectedTheme: String,
    onSelected: (String) -> Unit,
) {
    val colors = currentReaderPanelColors()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        themes.forEach { theme ->
            val selected = theme.id == selectedTheme
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).height(40.dp).clip(Capsule())
                    .background(theme.background)
                    .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.divider, Capsule())
                    .tvFocusable(shape = Capsule(), addFocusTarget = false)
                    .testTag("novel-theme:${theme.id}")
                    .selectable(selected, role = Role.RadioButton, onClick = { onSelected(theme.id) }),
            ) {
                Text(
                    text = theme.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Common typography card; fonts and remaining engine-specific options stay with their hosts. */
@Composable
fun NovelTypographyOptionsPage(
    fontSize: NovelReaderValueOption,
    lineSpacing: NovelReaderValueOption,
    fontOption: @Composable () -> Unit,
    additionalOptions: @Composable () -> Unit = {},
) = ReaderOptionsPageList {
    item {
        ReaderOptionGroup {
            NovelTypographyControls(fontSize, lineSpacing, fontOption = fontOption,
                additionalOptions = additionalOptions)
        }
    }
}

/** The same typography controls inside a reader panel or the main Android settings group. */
@Composable
fun NovelTypographyControls(
    fontSize: NovelReaderValueOption,
    lineSpacing: NovelReaderValueOption,
    modifier: Modifier = Modifier,
    fontOption: (@Composable () -> Unit)? = null,
    additionalOptions: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        if (fontOption != null) {
            fontOption()
            ReaderOptionDivider()
        }
        NovelReaderValueStepper(fontSize)
        ReaderOptionDivider()
        NovelReaderValueStepper(lineSpacing)
        additionalOptions()
    }
}

/** Hosts map this two-way choice to their existing enum or boolean, without changing persistence. */
@Composable
fun NovelReadingModeOptions(
    paged: Boolean,
    onPagedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (Int) -> Unit)? = null,
) {
    ReaderOptionSection(uiString("novel_reading_mode"), modifier = modifier) {
        ReaderChoiceChips(
            options = listOf(uiString("novel_mode_paged"), uiString("novel_mode_scroll")),
            selectedIndex = if (paged) 0 else 1,
            onSelected = { onPagedChange(it == 0) },
            icon = icon,
        )
    }
}
