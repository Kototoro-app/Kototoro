package org.skepsun.kototoro.settings.about

import android.app.DownloadManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.TextViewCompat
import io.noties.markwon.Markwon
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.github.AppVersion
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.widgets.SelectableTextView
import org.skepsun.kototoro.core.util.FileSize

data class AppUpdateMirrorOption(
	val mirror: AppSettings.GitHubMirror,
	val label: String,
)

@Composable
fun AppUpdateScreen(
	version: AppVersion?,
	isLoading: Boolean,
	downloadProgress: Float,
	downloadState: Int,
	updateMessage: String?,
	operationErrorMessage: String?,
	mirrorOptions: List<AppUpdateMirrorOption>,
	selectedMirror: AppSettings.GitHubMirror,
	onMirrorSelected: (AppSettings.GitHubMirror) -> Unit,
	onCancel: () -> Unit,
	onUpdate: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val screenPadding = androidx.compose.ui.res.dimensionResource(R.dimen.screen_padding)
	val downloadError = when (downloadState) {
		DownloadManager.STATUS_FAILED -> stringResource(R.string.error_occurred)
		DownloadManager.STATUS_PAUSED -> stringResource(R.string.downloads_paused)
		else -> null
	}
	val errorMessage = operationErrorMessage ?: updateMessage ?: downloadError

	Column(
		modifier = modifier
			.fillMaxSize()
			.statusBarsPadding(),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = screenPadding)
				.padding(top = 24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Image(
				painter = painterResource(R.drawable.ic_app_update),
				contentDescription = null,
				colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
				modifier = Modifier.size(24.dp),
			)
			Text(
				text = stringResource(R.string.app_update_available),
				style = MaterialTheme.typography.headlineSmall,
				color = MaterialTheme.colorScheme.onBackground,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(top = 16.dp),
			)
		}

		if (isLoading) {
			if (downloadProgress > 0f) {
				LinearProgressIndicator(
					progress = { downloadProgress.coerceAtMost(1f) },
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = screenPadding)
						.padding(top = 16.dp),
				)
			} else {
				LinearProgressIndicator(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = screenPadding)
						.padding(top = 16.dp),
				)
			}
		}

		if (errorMessage != null) {
			Text(
				text = errorMessage,
				color = MaterialTheme.colorScheme.error,
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = screenPadding)
					.padding(top = 8.dp),
			)
		}

		MirrorSelector(
			options = mirrorOptions,
			selectedMirror = selectedMirror,
			onMirrorSelected = onMirrorSelected,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = screenPadding)
				.padding(top = 16.dp),
		)
		Text(
			text = stringResource(R.string.pref_github_mirror_summary),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = screenPadding)
				.padding(top = 4.dp),
		)

		UpdateDescription(
			version = version,
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth()
				.padding(top = 16.dp)
				.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
		)

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
				.navigationBarsPadding()
				.height(64.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Button(onClick = onCancel) {
				Text(stringResource(android.R.string.cancel))
			}
			FilledTonalButton(
				enabled = !isLoading && version != null,
				onClick = onUpdate,
			) {
				Text(stringResource(R.string.update))
			}
		}
	}
}

@Composable
private fun MirrorSelector(
	options: List<AppUpdateMirrorOption>,
	selectedMirror: AppSettings.GitHubMirror,
	onMirrorSelected: (AppSettings.GitHubMirror) -> Unit,
	modifier: Modifier = Modifier,
) {
	var expanded by remember { mutableStateOf(false) }
	val selectedLabel = options.firstOrNull { it.mirror == selectedMirror }?.label.orEmpty()

	Column(modifier = modifier) {
		OutlinedTextField(
			value = selectedLabel,
			onValueChange = {},
			readOnly = true,
			label = { Text(stringResource(R.string.pref_github_mirror)) },
			leadingIcon = {
				Icon(
					painter = painterResource(R.drawable.ic_web),
					contentDescription = null,
				)
			},
			modifier = Modifier
				.fillMaxWidth()
				.clickable { expanded = true },
		)
		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			shape = RoundedCornerShape(4.dp),
		) {
			options.forEach { option ->
				DropdownMenuItem(
					text = { Text(option.label) },
					onClick = {
						expanded = false
						onMirrorSelected(option.mirror)
					},
				)
			}
		}
	}
}

/** Keeps Markwon's existing Markdown, links, and span handling while the page itself is Compose. */
@Composable
private fun UpdateDescription(
	version: AppVersion?,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val markwon = remember(context) { Markwon.create(context) }
	val scrollState = rememberScrollState()
	val text = remember(version, context) {
		version?.let {
			buildString {
				append(context.getString(R.string.new_version_s, it.name))
				appendLine()
				append(
					context.getString(
						R.string.size_s,
						FileSize.BYTES.format(context, it.patchSize ?: it.apkSize),
					),
				)
				appendLine()
				appendLine()
				append(it.description)
			}
		}
	}

	AndroidView(
		factory = { viewContext ->
			SelectableTextView(viewContext).apply {
				TextViewCompat.setTextAppearance(
					this,
					com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
				)
				setTextIsSelectable(true)
			}
		},
		modifier = modifier
			.verticalScroll(scrollState)
			.padding(horizontal = androidx.compose.ui.res.dimensionResource(R.dimen.screen_padding))
			.padding(bottom = androidx.compose.ui.res.dimensionResource(R.dimen.screen_padding)),
		update = { textView ->
			if (text == null) {
				textView.setText(R.string.loading_)
			} else {
				markwon.setMarkdown(textView, text)
			}
		},
	)
}

@Preview(showBackground = true)
@Composable
private fun AppUpdateScreenPreview() {
	KototoroTheme {
		AppUpdateScreen(
			version = null,
			isLoading = true,
			downloadProgress = -1f,
			downloadState = DownloadManager.STATUS_PENDING,
			updateMessage = null,
			operationErrorMessage = null,
			mirrorOptions = emptyList(),
			selectedMirror = AppSettings.GitHubMirror.NATIVE,
			onMirrorSelected = {},
			onCancel = {},
			onUpdate = {},
		)
	}
}
