package org.skepsun.kototoro.favourites.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.favourites.ui.container.FavouritesContainerViewModel
import org.skepsun.kototoro.settings.compose.SettingsSectionScaffold
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.favourites.ui.compose.KototoroFavoritesHostRoute

@AndroidEntryPoint
class FavouritesActivity : BaseComposeActivity() {

    private val viewModel by viewModels<FavouritesContainerViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val initialCategoryId = intent?.getLongExtra(AppRouter.KEY_ID, NO_ID) ?: NO_ID
        val initialCategoryTitle = intent?.getStringExtra(AppRouter.KEY_TITLE)

        super.onCreate(savedInstanceState)
        viewModel.setSearchMatchingIds(intent.getLongArrayExtra(EXTRA_MATCHING_IDS)?.toSet())

        setComposeContent {
            SettingsSectionScaffold(
                title = listOfNotNull(initialCategoryTitle, intent.getStringExtra(AppRouter.KEY_QUERY)?.takeIf(String::isNotBlank))
                    .joinToString(" · ").ifBlank { getString(org.skepsun.kototoro.R.string.favourites) },
                onNavigateUp = ::finish,
            ) {
                KototoroFavoritesHostRoute(
                    appRouter = router,
                    contentPadding = PaddingValues(top = org.skepsun.kototoro.settings.compose.settingsContentTopInset()),
                    initialCategoryId = initialCategoryId,
                    initialCategoryTitle = initialCategoryTitle,
                    viewModel = viewModel,
                )
            }
        }
    }

    companion object {
        const val EXTRA_MATCHING_IDS = "favourite_matching_ids"
    }
}
