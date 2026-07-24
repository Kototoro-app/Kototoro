package org.skepsun.kototoro.details.ui.related

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.list.ui.compose.AppContentListRoute

@AndroidEntryPoint
class RelatedContentActivity : BaseComposeActivity() {
    private val viewModel by viewModels<RelatedListViewModel>()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(getString(R.string.related_manga)) },
                        navigationIcon = {
                            IconButton(onClick = ::finishAfterTransition) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                            }
                        },
                    )
                },
            ) { padding ->
                AppContentListRoute(
                    viewModel = viewModel,
                    contentPadding = padding,
                    appRouter = router,
                    isContentTypeFilterVisible = false,
                    isSourceTagFilterVisible = false,
                    registerFilterCallback = false,
                    pullRefreshEnabled = false,
                    onNavigateToDetails = { _, content, _ -> router.openResolvedDetails(content) },
                )
            }
        }
    }
}
