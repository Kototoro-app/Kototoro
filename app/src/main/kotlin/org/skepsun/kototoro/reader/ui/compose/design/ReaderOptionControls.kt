package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

@Composable
fun ReaderOptionGroup(
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.() -> Unit,
) {
	Surface(
		shape = MaterialTheme.shapes.medium,
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		modifier = modifier.fillMaxWidth(),
	) {
		Column(content = content)
	}
}

@Composable
fun ReaderOptionDivider(modifier: Modifier = Modifier) {
	HorizontalDivider(
		color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
		modifier = modifier.padding(horizontal = 12.dp),
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
			.padding(horizontal = 12.dp, vertical = 6.dp),
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
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
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp)
			.clickable(role = Role.Button, onClick = onClick)
			.padding(horizontal = 12.dp, vertical = 6.dp),
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodyMedium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = value,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.primary,
			maxLines = 1,
		)
		Icon(
			painter = painterResource(R.drawable.ic_arrow_forward),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
) {
	Surface(
		shape = MaterialTheme.shapes.medium,
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		modifier = modifier.fillMaxWidth(),
	) {
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
			Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
				options.indices.forEach { index ->
					val selected = index == selectedIndex
					Surface(
						shape = RoundedCornerShape(8.dp),
						color = if (selected) {
							MaterialTheme.colorScheme.primaryContainer
						} else {
							androidx.compose.ui.graphics.Color.Transparent
						},
						contentColor = if (selected) {
							MaterialTheme.colorScheme.onPrimaryContainer
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier
							.heightIn(min = 36.dp)
							.selectable(
								selected = selected,
								role = Role.RadioButton,
								onClick = { onSelected(index) },
							)
							.semantics { contentDescription = options[index] },
					) {
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
}
