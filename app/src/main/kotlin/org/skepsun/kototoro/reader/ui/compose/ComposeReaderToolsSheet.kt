package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelSection

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposeReaderToolsSheet(
	visible: Boolean,
	translateActive: Boolean,
	callbacks: ComposeReaderOptionsCallbacks,
	onDismiss: () -> Unit,
	embedded: Boolean = false,
	modifier: Modifier = Modifier,
) {
	if (!visible) return
	Surface(
		shape = if (embedded) androidx.compose.foundation.shape.RoundedCornerShape(0.dp) else MaterialTheme.shapes.large,
		color = if (embedded) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 360.dp),
	) {
		Column {
			ReaderPanelSection(
				embedded = embedded,
				modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
			) {
				FlowRow(
					maxItemsInEachRow = 2,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
					verticalArrangement = Arrangement.spacedBy(4.dp),
					modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
				) {
					Tool(R.drawable.ic_translate, R.string.reader_translation_action, translateActive, callbacks.onTranslation)
					Tool(R.drawable.ic_retry, R.string.reader_translation_retranslate_current_page, false, callbacks.onRetranslatePage)
					Tool(R.drawable.ic_retry, R.string.reader_translation_retry_failed_pages, false, callbacks.onRetryFailedTranslations)
					Tool(R.drawable.ic_retry, R.string.reader_translation_retranslate_current_chapter, false, callbacks.onRetranslateChapter)
					Tool(R.drawable.ic_settings, R.string.reader_translation_action_settings, false, callbacks.onTranslationSettings)
				}
			}
		}
	}
}

@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.Tool(
	icon: Int,
	label: Int,
	active: Boolean,
	onClick: () -> Unit,
) {
	TextButton(
		onClick = onClick,
		modifier = Modifier
			.weight(1f)
			.heightIn(min = 48.dp)
			.background(
				color = if (active) {
					MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
				} else {
					androidx.compose.ui.graphics.Color.Transparent
				},
				shape = RoundedCornerShape(18.dp),
			),
	) {
		val contentColor = if (isSystemInDarkTheme()) {
			androidx.compose.ui.graphics.Color.White
		} else {
			MaterialTheme.colorScheme.onSurface
		}
		Icon(
			painter = painterResource(icon),
			contentDescription = null,
			tint = if (active) MaterialTheme.colorScheme.primary else contentColor,
			modifier = Modifier.size(20.dp),
		)
		Text(
			text = stringResource(label),
			color = if (active) MaterialTheme.colorScheme.primary else contentColor,
			style = MaterialTheme.typography.labelMedium,
			modifier = Modifier.padding(start = 6.dp),
		)
	}
}
