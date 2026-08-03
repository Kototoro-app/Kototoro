package org.skepsun.kototoro.settings.sources.blacklist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

@Composable
fun GlobalTagBlacklistStatus(
	blacklistedTagCount: Int,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.medium,
		color = MaterialTheme.colorScheme.surfaceContainerLow,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.blacklisted_tags),
					style = MaterialTheme.typography.titleSmall,
				)
				Text(
					text = if (blacklistedTagCount > 0) {
						stringResource(R.string.global_tag_blacklist_active, blacklistedTagCount)
					} else {
						stringResource(R.string.global_tag_blacklist_inactive)
					},
					style = MaterialTheme.typography.bodySmall,
					color = if (blacklistedTagCount > 0) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
				)
			}
			TextButton(
				onClick = onClick,
				modifier = Modifier.padding(start = 8.dp),
			) {
				Text(stringResource(R.string.manage))
			}
		}
	}
}
