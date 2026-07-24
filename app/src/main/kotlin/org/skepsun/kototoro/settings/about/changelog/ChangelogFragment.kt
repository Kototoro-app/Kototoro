package org.skepsun.kototoro.settings.about.changelog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.noties.markwon.Markwon
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.widgets.SelectableTextView
import org.skepsun.kototoro.settings.SettingsActivity

@Composable
fun ChangelogRoute(
	viewModel: ChangelogViewModel,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val markwon = remember(context) { Markwon.create(context) }
	val changelog by viewModel.changelog.collectAsStateWithLifecycle()
	val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

	LaunchedEffect(viewModel) {
		viewModel.loadIfNeeded()
	}

	Surface(
		modifier = modifier,
		color = MaterialTheme.colorScheme.background,
	) {
		Box(modifier = Modifier.fillMaxSize()) {
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
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.padding(
						PaddingValues(
							start = 20.dp,
							top = 20.dp,
							end = 20.dp,
							bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
						),
					),
				update = { textView ->
					markwon.setMarkdown(textView, changelog.orEmpty())
				},
			)
			if (isLoading) {
				LinearProgressIndicator(
					modifier = Modifier
						.fillMaxWidth()
						.align(Alignment.TopCenter),
				)
			}
		}
	}
}
