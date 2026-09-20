package org.skepsun.kototoro.dictionary

import android.content.Context
import android.content.Intent
import android.text.Html
import android.webkit.WebView
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class DictionaryActivity : BaseComposeActivity() {

    private val viewModel by viewModels<DictionaryViewModel>()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val initialWord = intent.getStringExtra(EXTRA_WORD).orEmpty()
        if (initialWord.isNotBlank()) viewModel.search(initialWord)
        setComposeContent {
            DictionaryScreen(
                viewModel = viewModel,
                onBack = ::finish,
                onManageRules = {
                    startActivity(Intent(this, DictionaryRulesActivity::class.java))
                },
            )
        }
    }

    companion object {
        private const val EXTRA_WORD = "word"

        fun newIntent(context: Context, word: String): Intent =
            Intent(context, DictionaryActivity::class.java).putExtra(EXTRA_WORD, word)
    }
}

@Composable
private fun DictionaryScreen(
    viewModel: DictionaryViewModel,
    onBack: () -> Unit,
    onManageRules: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember(state.word) { mutableStateOf(state.word) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val selectedPage = state.pages.getOrNull(selectedIndex.coerceIn(0, (state.pages.size - 1).coerceAtLeast(0)))

    LaunchedEffect(state.pages.size) {
        selectedIndex = selectedIndex.coerceIn(0, (state.pages.size - 1).coerceAtLeast(0))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dictionary_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onManageRules) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.dictionary_manage_rules))
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.dictionary_query_label)) },
                )
                Button(
                    onClick = { viewModel.search(query) },
                    enabled = query.isNotBlank() && !state.isSearching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.dictionary_query_action))
                }
            }

            if (state.pages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.word.isBlank()) {
                            stringResource(R.string.dictionary_empty_query)
                        } else {
                            stringResource(R.string.dictionary_no_enabled_rules)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ScrollableTabRow(selectedTabIndex = selectedIndex) {
                    state.pages.forEachIndexed { index, page ->
                        Tab(
                            selected = selectedIndex == index,
                            onClick = { selectedIndex = index },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(page.rule.name)
                                    if (page.isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                selectedPage?.let { page ->
                    DictionaryPage(
                        page = page,
                        onOpenBrowser = { url ->
                            context.startActivity(AppRouter.browserIntent(context, url, null, page.rule.name))
                        },
                        onRetry = {
                            viewModel.retryRule(page.rule)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DictionaryPage(
    page: DictionaryPageState,
    onOpenBrowser: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        page.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        page.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = page.error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }
        }

        page.result != null -> {
            val result = page.result
            Column(modifier = Modifier.fillMaxSize()) {
                TextButton(
                    onClick = { onOpenBrowser(result.url) },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Icon(Icons.Outlined.OpenInBrowser, contentDescription = null)
                    Text(stringResource(R.string.open_in_browser), modifier = Modifier.padding(start = 8.dp))
                }
                DictionaryWebView(
                    html = result.html,
                    baseUrl = result.url,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun DictionaryWebView(
    html: String,
    baseUrl: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                isVerticalScrollBarEnabled = true
            }
        },
        update = { webView ->
            val content = if (html.contains('<')) {
                html
            } else {
                "<html><body><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">" +
                    Html.escapeHtml(html) +
                    "</pre></body></html>"
            }
            webView.loadDataWithBaseURL(baseUrl, content, "text/html", "UTF-8", baseUrl)
        },
    )
}
