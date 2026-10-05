package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.skepsun.kototoro.core.prefs.observeAsState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.FoldableUtils

/** Android policy stays here; width classes and overlay geometry are shared in :core-ui. */
@Composable
fun rememberAndroidTabletLayoutClass(settings: AppSettings): TabletLayoutClass {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val tabletUiMode by settings.observeAsState(AppSettings.KEY_TABLET_UI_MODE) { tabletUiMode }
    return remember(context, configuration, tabletUiMode) {
        tabletLayoutClass(configuration.screenWidthDp,
            FoldableUtils.shouldUseTabletLayout(context, settings, configuration))
    }
}
