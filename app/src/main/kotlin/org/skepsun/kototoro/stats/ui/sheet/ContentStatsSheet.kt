package org.skepsun.kototoro.stats.ui.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.sheet.BaseAdaptiveSheet
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.databinding.SheetStatsContentBinding
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.stats.ui.sheet.compose.ContentStatsSheetContent

@AndroidEntryPoint
class ContentStatsSheet : BaseAdaptiveSheet<SheetStatsContentBinding>() {

    private val viewModel: ContentStatsViewModel by viewModels()

    override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetStatsContentBinding {
        return SheetStatsContentBinding.inflate(inflater, container, false)
    }

    override fun onViewBindingCreated(binding: SheetStatsContentBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.composeView.setContent {
            KototoroTheme {
                ContentStatsSheetContent(
                    manga = viewModel.manga,
                    viewModel = viewModel,
                    onOpenDetails = {
                        val mainActivity = activity as? MainActivity
                        mainActivity?.resolveDetailsOriginForContent(viewModel.manga) { origin ->
                            when (origin) {
                                is org.skepsun.kototoro.details.ui.model.DetailsOrigin.EntityGraph -> {
                                    router.openEntityDetails(
                                        entityId = origin.entityId,
                                        initialProjectionLocalMangaId = origin.initialProjectionLocalMangaId,
                                    )
                                }
                                else -> router.openResolvedDetails(viewModel.manga)
                            }
                        } ?: router.openResolvedDetails(viewModel.manga)
                    },
                    modifier = Modifier,
                )
            }
        }
    }

    override fun onApplyWindowInsets(v: android.view.View, insets: WindowInsetsCompat): WindowInsetsCompat = insets
}
