package org.skepsun.kototoro.download.ui.list

import android.os.Bundle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.download.ui.compose.AppDownloadsRoute

@AndroidEntryPoint
class DownloadsActivity : BaseComposeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setComposeContent {
            AppDownloadsRoute(
                appRouter = router,
                contentPadding = PaddingValues(0.dp),
            )
        }
    }
}
