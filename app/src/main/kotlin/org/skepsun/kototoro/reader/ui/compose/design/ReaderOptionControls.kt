package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
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
