package org.skepsun.kototoro.settings.sources

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.withArgs

@AndroidEntryPoint
class SourceComposeSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KototoroTheme {
                    SourceSettingsRoute(appRouter = router)
                }
            }
        }
    }

    companion object {
        fun newInstance(source: org.skepsun.kototoro.parsers.model.ContentSource) =
            SourceComposeSettingsFragment().withArgs(1) {
                putString(AppRouter.KEY_SOURCE, source.name)
            }
    }
}
