package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

@Composable
fun ReaderOptionGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = currentReaderPanelColors()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        if (title != null) ReaderOptionSectionTitle(title)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.card,
            contentColor = colors.content,
            // E-ink cards share the panel colour; an outline keeps the group visible.
            border = if (colors.card == colors.container) BorderStroke(1.dp, colors.divider) else null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun ReaderOptionSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = currentReaderPanelColors().contentSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

/** A titled block without a card, for controls that draw their own surfaces (chips, swatches). */
@Composable
fun ReaderOptionSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        ReaderOptionSectionTitle(title)
        content()
    }
}

@Composable
fun ReaderOptionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = currentReaderPanelColors().divider,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

@Composable
fun ReaderOptionSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = currentReaderPanelColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.content,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.scale(0.85f),
        )
    }
}

@Composable
fun ReaderOptionValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentReaderPanelColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.accent,
            maxLines = 1,
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_forward),
            contentDescription = null,
            tint = colors.contentSecondary,
            modifier = Modifier.padding(start = 6.dp).size(16.dp),
        )
    }
}

@Composable
fun ReaderSegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: (@Composable (Int) -> Unit)? = null,
    iconOnly: Boolean = false,
    stackedTitle: Boolean = false,
    verticalOptions: Boolean = false,
) {
    Surface(
        // Stacked choices draw their own rounded option cards. Using the MD3 medium
        // shape here as well clips the lower corners of those cards when the theme
        // radius is larger than the card radius.
        shape = if (stackedTitle) RoundedCornerShape(0.dp) else MaterialTheme.shapes.medium,
        color = if (stackedTitle) {
            androidx.compose.ui.graphics.Color.Transparent
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        if (stackedTitle) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(4.dp),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                ReaderSegmentedChoiceOptions(
                    options = options,
                    selectedIndex = selectedIndex,
                    onSelected = onSelected,
                    icon = icon,
                    iconOnly = iconOnly,
                    vertical = verticalOptions,
                    equalWidth = true,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(3.dp),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
                    )
                }
                ReaderSegmentedChoiceOptions(
                    options = options,
                    selectedIndex = selectedIndex,
                    onSelected = onSelected,
                    icon = icon,
                    iconOnly = iconOnly,
                    vertical = verticalOptions,
                    equalWidth = false,
                )
            }
        }
    }
}

@Composable
private fun ReaderSegmentedChoiceOptions(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    icon: (@Composable (Int) -> Unit)?,
    iconOnly: Boolean,
    vertical: Boolean,
    equalWidth: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = if (equalWidth) Modifier.fillMaxWidth() else Modifier,
    ) {
        options.indices.forEach { index ->
            val selected = index == selectedIndex
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else if (equalWidth) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                border = if (equalWidth) {
                    BorderStroke(
                        1.dp,
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                        },
                    )
                } else {
                    null
                },
                modifier = Modifier
                    .then(if (equalWidth) Modifier.weight(1f) else Modifier)
                    .then(
                        if (vertical) {
                            Modifier.height(84.dp)
                        } else {
                            Modifier.heightIn(min = 36.dp)
                        },
                    )
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelected(index) },
                    )
                    .semantics { contentDescription = options[index] },
            ) {
                if (vertical) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        if (icon != null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(20.dp),
                            ) {
                                icon(index)
                            }
                        }
                        if (!iconOnly) {
                            Text(
                                text = options[index],
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(
                            horizontal = if (iconOnly) 9.dp else 8.dp,
                            vertical = 5.dp,
                        ),
                    ) {
                        if (icon != null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(20.dp),
                            ) {
                                icon(index)
                            }
                        }
                        if (!iconOnly) {
                            Text(
                                text = options[index],
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (icon == null) Modifier else Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
