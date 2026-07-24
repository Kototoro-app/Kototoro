package org.skepsun.kototoro.settings.sources.catalog

import android.os.Bundle
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class SourcesCatalogActivity : BaseComposeActivity() {

    private val viewModel by viewModels<SourcesCatalogViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            SourcesCatalogRoute(
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onBack = ::finish,
                onOpenSource = { source -> router.openList(source, null, null) },
            )
        }
    }
}
