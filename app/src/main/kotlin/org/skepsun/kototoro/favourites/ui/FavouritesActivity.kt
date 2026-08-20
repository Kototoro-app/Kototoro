package org.skepsun.kototoro.favourites.ui

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val initialCategoryId = intent?.getLongExtra(AppRouter.KEY_ID, NO_ID) ?: NO_ID
        val initialCategoryTitle = intent?.getStringExtra(AppRouter.KEY_TITLE)

        super.onCreate(savedInstanceState)

        setComposeContent {
            KototoroFavoritesHostRoute(
                appRouter = router,
                contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
                initialCategoryId = initialCategoryId,
                initialCategoryTitle = initialCategoryTitle,
                onOpenEntityOrganize = { selectedIds ->
                    router.openEntityOrganizeSettings(selectedIds)
                },
            )
        }
    }
}
