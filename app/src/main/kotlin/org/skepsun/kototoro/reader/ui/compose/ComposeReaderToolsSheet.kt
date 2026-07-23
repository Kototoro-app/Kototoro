package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposeReaderToolsSheet(
	visible: Boolean,
	translateActive: Boolean,
	callbacks: ComposeReaderOptionsCallbacks,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	if (!visible) return
	Surface(
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 360.dp),
	) {
		Text(
			text = stringResource(R.string.ai),
			style = MaterialTheme.typography.titleLarge,
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
		)
		Card(
			colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
		) {
			FlowRow(
				maxItemsInEachRow = 2,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			) {
				Tool(R.drawable.ic_translate, R.string.reader_translation_action, translateActive, callbacks.onTranslation)
				Tool(R.drawable.ic_retry, R.string.reader_translation_retranslate_current_page, false, callbacks.onRetranslatePage)
				Tool(R.drawable.ic_retry, R.string.reader_translation_retry_failed_pages, false, callbacks.onRetryFailedTranslations)
				Tool(R.drawable.ic_info_outline, R.string.reader_translation_task_panel_title, false, callbacks.onTranslationLog)
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
		modifier = Modifier.weight(1f),
	) {
		Icon(painterResource(icon), contentDescription = null)
		Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
	}
}
