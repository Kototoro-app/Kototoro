package org.skepsun.kototoro.favourites.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.favourites.ui.compose.KototoroFavoritesHostRoute

@AndroidEntryPoint
class FavouritesActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialCategoryId = intent?.getLongExtra(AppRouter.KEY_ID, NO_ID) ?: NO_ID
        val initialCategoryTitle = intent?.getStringExtra(AppRouter.KEY_TITLE)

        setContent {
            KototoroTheme {
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
}
