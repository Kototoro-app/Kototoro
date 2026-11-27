package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.TopBarStyle
import org.skepsun.kototoro.core.ui.glass.GlassPrefs
import org.skepsun.kototoro.core.ui.glass.LocalGlassPrefs
import org.skepsun.kototoro.core.ui.text.LocalUiStrings
import org.skepsun.kototoro.core.ui.text.rememberAndroidUiStrings
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.tokens

@Composable
private fun TopBarPreviewTheme(content: @Composable () -> Unit) {
    val style = InterfaceStyle.MATERIAL_3_EXPRESSIVE
    CompositionLocalProvider(
        LocalInterfaceStyle provides style,
        LocalInterfaceStyleTokens provides style.tokens(),
        LocalMaterialExpressiveComponentsEnabled provides true,
        LocalUiStrings provides rememberAndroidUiStrings(),
        LocalGlassPrefs provides GlassPrefs(
            isGlassEffectEnabled = true,
            isReducedVisualEffectsEnabled = false,
            immersiveStrengthPercent = 65,
        ),
    ) {
        MaterialTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                content()
            }
        }
    }
}

@Composable
private fun ExploreSegmentedTopBarPreview(discovery: Boolean) {
    TopBarPreviewTheme {
        KototoroTopBar(
            query = "",
            titleRes = R.string.explore,
            // The switcher sits in the bar only with the compact style; the long search pill pushes
            // it below the bar (see DiscoveryFeedPreviews).
            topBarStyle = TopBarStyle.COMPACT,
            segmentedSwitcherState = TopBarSegmentedSwitcherState(
                items = listOf(
                    TopBarSegmentedSwitcherItem(
                        "SOURCES",
                        stringResource(R.string.explore_page_sources),
                        R.drawable.ic_manga_source,
                    ),
                    TopBarSegmentedSwitcherItem(
                        "DISCOVERY",
                        stringResource(R.string.explore_page_discovery),
                        R.drawable.ic_explore_normal,
                    ),
                ),
                selectedItemId = if (discovery) "DISCOVERY" else "SOURCES",
                onItemSelected = {},
                hideSourceFilters = discovery,
                leadingExtraActions = if (discovery) {
                    listOf(
                        TopBarSegmentedAction(
                            id = "add",
                            iconRes = R.drawable.ic_add,
                            contentDescriptionRes = R.string.discovery_feed_add_row,
                            onClick = {},
                        ),
                    )
                } else {
                    emptyList()
                },
            ),
        )
    }
}

@Preview(name = "Explore sources 412dp zh-rCN", widthDp = 412, heightDp = 120, locale = "zh-rCN")
@Composable
internal fun ExploreSourcesTopBar412ZhPreview() = ExploreSegmentedTopBarPreview(discovery = false)

@Preview(name = "Explore discovery 412dp zh-rCN", widthDp = 412, heightDp = 120, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoveryTopBar412ZhPreview() = ExploreSegmentedTopBarPreview(discovery = true)

@Preview(name = "Explore sources 412dp en", widthDp = 412, heightDp = 120, locale = "en")
@Composable
internal fun ExploreSourcesTopBar412EnPreview() = ExploreSegmentedTopBarPreview(discovery = false)

@Preview(name = "Explore discovery 412dp en", widthDp = 412, heightDp = 120, locale = "en")
@Composable
internal fun ExploreDiscoveryTopBar412EnPreview() = ExploreSegmentedTopBarPreview(discovery = true)

@Preview(name = "Explore sources 360dp zh-rCN", widthDp = 360, heightDp = 120, locale = "zh-rCN")
@Composable
internal fun ExploreSourcesTopBar360ZhPreview() = ExploreSegmentedTopBarPreview(discovery = false)

@Preview(name = "Explore discovery 360dp zh-rCN", widthDp = 360, heightDp = 120, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoveryTopBar360ZhPreview() = ExploreSegmentedTopBarPreview(discovery = true)

@Preview(name = "Explore sources 360dp en", widthDp = 360, heightDp = 120, locale = "en")
@Composable
internal fun ExploreSourcesTopBar360EnPreview() = ExploreSegmentedTopBarPreview(discovery = false)

@Preview(name = "Explore discovery 360dp en", widthDp = 360, heightDp = 120, locale = "en")
@Composable
internal fun ExploreDiscoveryTopBar360EnPreview() = ExploreSegmentedTopBarPreview(discovery = true)

@Preview(name = "Library top bar", widthDp = 412, heightDp = 120, locale = "zh-rCN")
@Composable
internal fun LibraryTopBarPreview() {
    TopBarPreviewTheme {
        KototoroTopBar(
            query = "",
            titleRes = R.string.favourites,
            supportsDisplayModeMenu = true,
        )
    }
}
