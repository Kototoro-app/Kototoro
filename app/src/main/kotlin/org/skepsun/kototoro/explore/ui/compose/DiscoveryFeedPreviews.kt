package org.skepsun.kototoro.explore.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ExplorePage
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.compactPosterRailCardStyle
import org.skepsun.kototoro.core.ui.glass.GlassPrefs
import org.skepsun.kototoro.core.ui.glass.LocalGlassPrefs
import org.skepsun.kototoro.core.ui.text.LocalUiStrings
import org.skepsun.kototoro.core.ui.text.rememberAndroidUiStrings
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.tokens
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.explore.ui.DiscoveryFeedRowState
import org.skepsun.kototoro.explore.ui.DiscoveryFeedUiState
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.compose.ContentCardUiPrefs
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.main.ui.compose.ExploreBelowBarSwitcherInset
import org.skepsun.kototoro.main.ui.compose.KototoroTopBar
import org.skepsun.kototoro.main.ui.compose.TopBarSegmentedAction
import org.skepsun.kototoro.main.ui.compose.TopBarSegmentedSwitcherItem
import org.skepsun.kototoro.main.ui.compose.TopBarSegmentedSwitcherState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import java.io.IOException

private data class PreviewSource(
    override val name: String,
    override val locale: String = "en",
    override val contentType: ContentType = ContentType.MANGA,
) : ContentSource

private fun previewContent(
    id: Long,
    title: String,
    source: ContentSource,
    rating: Float = 0.88f,
): Content = Content(
    id = id,
    title = title,
    altTitles = emptySet(),
    url = "/manga/$id",
    publicUrl = "https://example.org/manga/$id",
    rating = rating,
    contentRating = ContentRating.SAFE,
    coverUrl = null,
    tags = emptySet(),
    state = null,
    authors = emptySet(),
    source = source,
)

private val previewCardUiPrefs = ContentCardUiPrefs(
    badgesTopLeft = setOf("favorite", "saved"),
    badgesTopRight = setOf("counter", "score"),
    badgesBottomLeft = setOf("reading_status"),
    badgesBottomRight = setOf("nsfw"),
)

private val previewLightColorScheme = lightColorScheme(
    primary = Color(0xFF4F5BD5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E4FF),
    onPrimaryContainer = Color(0xFF0E1B6A),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFE1F9),
    onSecondaryContainer = Color(0xFF171B2C),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFECF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
private fun DiscoveryFeedPreviewTheme(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val style = InterfaceStyle.MATERIAL_3_EXPRESSIVE
    val uiStrings = rememberAndroidUiStrings()
    CompositionLocalProvider(
        LocalInterfaceStyle provides style,
        LocalInterfaceStyleTokens provides style.tokens(),
        LocalMaterialExpressiveComponentsEnabled provides true,
        LocalUiStrings provides uiStrings,
        LocalGlassPrefs provides GlassPrefs(
            isGlassEffectEnabled = true,
            isReducedVisualEffectsEnabled = false,
            immersiveStrengthPercent = 65,
        ),
    ) {
        MaterialTheme(
            colorScheme = if (dark) darkColorScheme() else previewLightColorScheme,
            content = content,
        )
    }
}

@Composable
private fun PreviewExploreTopBarOverlay(
    selectedPage: ExplorePage,
    modifier: Modifier = Modifier,
) {
    val switcherState = TopBarSegmentedSwitcherState(
        items = listOf(
            TopBarSegmentedSwitcherItem(
                id = ExplorePage.SOURCES.name,
                title = stringResource(R.string.explore_page_sources),
                iconRes = R.drawable.ic_manga_source,
            ),
            TopBarSegmentedSwitcherItem(
                id = ExplorePage.DISCOVERY.name,
                title = stringResource(R.string.explore_page_discovery),
                iconRes = R.drawable.ic_explore_normal,
            ),
        ),
        selectedItemId = selectedPage.name,
        onItemSelected = {},
        hideSourceFilters = selectedPage == ExplorePage.DISCOVERY,
        leadingExtraActions = if (selectedPage == ExplorePage.DISCOVERY) {
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
    )
    KototoroTopBar(
        query = "",
        titleRes = R.string.explore,
        segmentedSwitcherState = switcherState,
        isContentTypeFilterVisible = true,
        isSourceTagFilterVisible = true,
        modifier = modifier,
    )
}

@Preview(
    name = "Explore Sources Page + Floating Switcher",
    showBackground = true,
    widthDp = 412,
    heightDp = 820,
    locale = "zh-rCN",
)
@Composable
internal fun ExploreSourcesFloatingSwitcherPreview() = ExploreSourcesPreviewContent()

@Preview(name = "Explore Sources · dark", widthDp = 412, heightDp = 820, locale = "zh-rCN")
@Composable
internal fun ExploreSourcesDarkPreview() = ExploreSourcesPreviewContent(dark = true)

@Preview(name = "Explore Sources · scrolled", widthDp = 412, heightDp = 820, locale = "zh-rCN")
@Composable
internal fun ExploreSourcesScrolledPreview() = ExploreSourcesPreviewContent(scrolledBy = 96.dp)

@Composable
private fun ExploreSourcesPreviewContent(dark: Boolean = false, scrolledBy: Dp = 0.dp) {
    DiscoveryFeedPreviewTheme(dark = dark) {
        val topInset = LocalInterfaceStyleTokens.current.mainTopBarHeight + ExploreBelowBarSwitcherInset
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = -scrolledBy)
                    .padding(top = topInset + 12.dp, start = CompactTopBarHorizontalPadding, end = CompactTopBarHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.explore_page_sources),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val demoSources = listOf(
                    listOf("MangaDex", "拷贝漫画", "哔哩哔哩漫画"),
                    listOf("动漫之家", "轻小说文库", "AniList"),
                    listOf("Bangumi", "NHentai", "Pixiv"),
                    listOf("Komga", "Kavita", "Suwayomi"),
                    listOf("MangaPlus", "Webtoons", "Tapas"),
                )
                demoSources.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { name ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(92.dp),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Surface(
                                        modifier = Modifier.size(34.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = name.take(1),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    }
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            PreviewExploreTopBarOverlay(
                selectedPage = ExplorePage.SOURCES,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Preview(
    name = "Explore Discovery Empty State",
    showBackground = true,
    widthDp = 412,
    heightDp = 820,
    locale = "zh-rCN",
)
@Composable
internal fun ExploreDiscoveryEmptyPreview() {
    DiscoveryFeedPreviewTheme {
        val topInset = LocalInterfaceStyleTokens.current.mainTopBarHeight + ExploreBelowBarSwitcherInset
        Box(modifier = Modifier.fillMaxSize()) {
            DiscoveryFeedPage(
                uiState = DiscoveryFeedUiState(),
                sourcesByName = emptyMap(),
                contentPadding = PaddingValues(top = topInset),
                posterStyle = compactPosterRailCardStyle(1f),
                gridScale = 1f,
                cardUiPrefs = previewCardUiPrefs,
                onRowVisible = {},
                onRetryRow = {},
                onRefreshAll = {},
                onContentClick = { _, _ -> },
                onRowHeaderClick = {},
                onEditRowClick = {},
                onAddRowClick = {},
                onManageRowsClick = {},
            )
            PreviewExploreTopBarOverlay(
                selectedPage = ExplorePage.DISCOVERY,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Preview(
    name = "Explore Discovery Populated Rows",
    showBackground = true,
    widthDp = 412,
    heightDp = 860,
    locale = "zh-rCN",
)
@Composable
internal fun ExploreDiscoveryPopulatedPreview() = ExploreDiscoveryPopulatedContent()

@Preview(name = "Explore Discovery · dark", widthDp = 412, heightDp = 860, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoveryPopulatedDarkPreview() = ExploreDiscoveryPopulatedContent(dark = true)

@Composable
private fun ExploreDiscoveryPopulatedContent(dark: Boolean = false) {
    DiscoveryFeedPreviewTheme(dark = dark) {
        val topInset = LocalInterfaceStyleTokens.current.mainTopBarHeight + ExploreBelowBarSwitcherInset
        val srcMangaDex = PreviewSource(name = "MangaDex", locale = "en")
        val srcCopyManga = PreviewSource(name = "拷贝漫画", locale = "zh")
        val srcLightNovel = PreviewSource(name = "轻小说文库", locale = "zh", contentType = ContentType.NOVEL)

        val rowLoaded = DiscoveryFeedRow(
            id = "row-loaded",
            sourceName = srcMangaDex.name,
            savedFilterId = 42,
            displayName = "MangaDex · 热门连载",
            sortOrder = 0,
        )
        val rowError = DiscoveryFeedRow(
            id = "row-error",
            sourceName = srcCopyManga.name,
            query = "异世界",
            displayName = "拷贝漫画 · 异世界",
            sortOrder = 1,
        )
        val rowLoading = DiscoveryFeedRow(
            id = "row-loading",
            sourceName = srcLightNovel.name,
            displayName = "轻小说文库 · 最新更新",
            sortOrder = 2,
        )

        val sampleItems = listOf(
            previewContent(1L, "葬送的芙莉莲", srcMangaDex, rating = 0.96f),
            previewContent(2L, "迷宫饭", srcMangaDex, rating = 0.92f),
            previewContent(3L, "药屋少女的呢喃", srcMangaDex, rating = 0.90f),
            previewContent(4L, "尖帽子的魔法工坊", srcMangaDex, rating = 0.89f),
        )
        val sampleCards = listOf(
            ContentGridModel(
                manga = sampleItems[0],
                override = null,
                subtitle = null,
                counter = 3,
                progress = ReadingProgress(
                    percent = 0.65f,
                    totalChapters = 120,
                    mode = org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.PERCENT_READ,
                ),
                isFavorite = true,
                isSaved = false,
                readingStatus = ScrobblingStatus.READING,
            ),
            ContentGridModel(
                manga = sampleItems[1],
                override = null,
                subtitle = null,
                counter = 0,
                progress = ReadingProgress(
                    percent = 1f,
                    totalChapters = 97,
                    mode = org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.PERCENT_READ,
                ),
                isFavorite = true,
                isSaved = true,
                readingStatus = ScrobblingStatus.COMPLETED,
            ),
            ContentGridModel(
                manga = sampleItems[2],
                override = null,
                subtitle = null,
                counter = 0,
                progress = null,
                isFavorite = false,
                isSaved = false,
                readingStatus = null,
            ),
            ContentGridModel(
                manga = sampleItems[3],
                override = null,
                subtitle = null,
                counter = 1,
                progress = null,
                isFavorite = false,
                isSaved = false,
                readingStatus = ScrobblingStatus.PLANNED,
            ),
        )

        val uiState = DiscoveryFeedUiState(
            allRows = listOf(rowLoaded, rowError, rowLoading),
            visibleRows = listOf(rowLoaded, rowError, rowLoading),
            rowStates = mapOf(
                rowLoaded.id to DiscoveryFeedRowState.Loaded(sampleItems, fetchedAtMs = 1_000L),
                rowError.id to DiscoveryFeedRowState.Error(
                    throwable = IOException("HTTP 503 Service Unavailable"),
                    message = "HTTP 503 Service Unavailable",
                ),
                rowLoading.id to DiscoveryFeedRowState.Loading,
            ),
            rowCardModels = mapOf(
                rowLoaded.id to sampleCards,
            ),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            DiscoveryFeedPage(
                uiState = uiState,
                sourcesByName = mapOf(
                    srcMangaDex.name to srcMangaDex,
                    srcCopyManga.name to srcCopyManga,
                    srcLightNovel.name to srcLightNovel,
                ),
                contentPadding = PaddingValues(top = topInset),
                posterStyle = compactPosterRailCardStyle(1f),
                gridScale = 1f,
                cardUiPrefs = previewCardUiPrefs,
                onRowVisible = {},
                onRetryRow = {},
                onRefreshAll = {},
                onContentClick = { _, _ -> },
                onRowHeaderClick = {},
                onEditRowClick = {},
                onAddRowClick = {},
                onManageRowsClick = {},
            )
            PreviewExploreTopBarOverlay(
                selectedPage = ExplorePage.DISCOVERY,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

private enum class FeedActionsPreviewMode { SELECTING, CATEGORY_DIALOG, LIBRARY_SHOWN, LIBRARY_HIDDEN }

@Preview(name = "Discovery · multi-select", widthDp = 412, heightDp = 720, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoverySelectingPreview() = ExploreDiscoveryActionsContent(FeedActionsPreviewMode.SELECTING)

@Preview(name = "Discovery · multi-select · dark", widthDp = 412, heightDp = 720, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoverySelectingDarkPreview() =
    ExploreDiscoveryActionsContent(FeedActionsPreviewMode.SELECTING, dark = true)

@Preview(name = "Discovery · category dialog", widthDp = 412, heightDp = 720, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoveryCategoryDialogPreview() =
    ExploreDiscoveryActionsContent(FeedActionsPreviewMode.CATEGORY_DIALOG)

@Preview(name = "Discovery · category dialog · dark", widthDp = 412, heightDp = 720, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoveryCategoryDialogDarkPreview() =
    ExploreDiscoveryActionsContent(FeedActionsPreviewMode.CATEGORY_DIALOG, dark = true)

@Preview(name = "Discovery · library shown", widthDp = 412, heightDp = 720, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoveryLibraryShownPreview() =
    ExploreDiscoveryActionsContent(FeedActionsPreviewMode.LIBRARY_SHOWN)

@Preview(name = "Discovery · library hidden", widthDp = 412, heightDp = 720, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoveryLibraryHiddenPreview() =
    ExploreDiscoveryActionsContent(FeedActionsPreviewMode.LIBRARY_HIDDEN)

@Preview(name = "Discovery · library shown · dark", widthDp = 412, heightDp = 720, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoveryLibraryShownDarkPreview() =
    ExploreDiscoveryActionsContent(FeedActionsPreviewMode.LIBRARY_SHOWN, dark = true)

@Preview(name = "Discovery · library hidden · dark", widthDp = 412, heightDp = 720, locale = "zh-rCN")
@Composable
internal fun ExploreDiscoveryLibraryHiddenDarkPreview() =
    ExploreDiscoveryActionsContent(FeedActionsPreviewMode.LIBRARY_HIDDEN, dark = true)

@Composable
private fun ExploreDiscoveryActionsContent(mode: FeedActionsPreviewMode, dark: Boolean = false) {
    DiscoveryFeedPreviewTheme(dark = dark) {
        val topInset = LocalInterfaceStyleTokens.current.mainTopBarHeight + ExploreBelowBarSwitcherInset
        val srcMangaDex = PreviewSource(name = "MangaDex", locale = "en")
        val srcCopyManga = PreviewSource(name = "拷贝漫画", locale = "zh")
        val rowA = DiscoveryFeedRow(id = "a", sourceName = srcMangaDex.name, displayName = "MangaDex · 热门连载", sortOrder = 0)
        val rowB = DiscoveryFeedRow(
            id = "b",
            sourceName = srcCopyManga.name,
            query = "异世界",
            displayName = "拷贝漫画 · 异世界",
            sortOrder = 1,
        )
        val itemsA = listOf("葬送的芙莉莲", "迷宫饭", "药屋少女的呢喃", "尖帽子的魔法工坊")
            .mapIndexed { index, title -> previewContent(index + 1L, title, srcMangaDex) }
        val itemsB = listOf("无职转生", "关于我转生变成史莱姆这档事", "Re:从零开始的异世界生活", "为美好的世界献上祝福")
            .mapIndexed { index, title -> previewContent(index + 11L, title, srcCopyManga) }
        // Works 1, 2 and 12 are in the library.
        val libraryIds = setOf(1L, 2L, 12L)
        fun cards(items: List<Content>) = items.map { item ->
            ContentGridModel(
                manga = item,
                override = null,
                subtitle = null,
                counter = 0,
                progress = null,
                isFavorite = item.id in libraryIds,
                isSaved = false,
            )
        }
        val hide = mode == FeedActionsPreviewMode.LIBRARY_HIDDEN
        fun shown(items: List<Content>) = cards(items).filterNot { hide && it.manga.id in libraryIds }
        val selecting = mode == FeedActionsPreviewMode.SELECTING || mode == FeedActionsPreviewMode.CATEGORY_DIALOG
        val uiState = DiscoveryFeedUiState(
            allRows = listOf(rowA, rowB),
            visibleRows = listOf(rowA, rowB),
            rowStates = mapOf(
                rowA.id to DiscoveryFeedRowState.Loaded(itemsA, fetchedAtMs = 1_000L),
                rowB.id to DiscoveryFeedRowState.Loaded(itemsB, fetchedAtMs = 1_000L),
            ),
            rowCardModels = mapOf(rowA.id to shown(itemsA), rowB.id to shown(itemsB)),
            hideInLibrary = hide,
            selectedIds = if (selecting) setOf(3L, 4L, 11L) else emptySet(),
            selectionRowId = if (selecting) rowB.id else null,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            DiscoveryFeedPage(
                uiState = uiState,
                sourcesByName = mapOf(srcMangaDex.name to srcMangaDex, srcCopyManga.name to srcCopyManga),
                contentPadding = PaddingValues(top = topInset),
                posterStyle = compactPosterRailCardStyle(1f),
                gridScale = 1f,
                cardUiPrefs = previewCardUiPrefs,
                onRowVisible = {},
                onRetryRow = {},
                onRefreshAll = {},
                onContentClick = { _, _ -> },
                onRowHeaderClick = {},
                onEditRowClick = {},
                onAddRowClick = {},
                onManageRowsClick = {},
            )
            if (selecting) {
                val bar = discoveryFeedSelectionTopBarState(uiState, {}, {})!!
                org.skepsun.kototoro.list.ui.compose.KototoroSelectionTopBar(
                    selectedCount = bar.selectedCount,
                    isAllNonLocal = bar.isAllNonLocal,
                    isSingleSelection = bar.isSingleSelection,
                    supportedActions = bar.supportedActions,
                    preferredInlineActions = bar.preferredInlineActions,
                    includeContextualActions = bar.includeContextualActions,
                    includeStatusBarPadding = false,
                    onClearSelection = {},
                    onActionClick = {},
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else {
                PreviewExploreTopBarOverlay(
                    selectedPage = ExplorePage.DISCOVERY,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            if (mode == FeedActionsPreviewMode.CATEGORY_DIALOG) {
                fun category(id: Long, title: String) = org.skepsun.kototoro.core.model.FavouriteCategory(
                    id = id,
                    title = title,
                    sortKey = id.toInt(),
                    order = org.skepsun.kototoro.list.domain.ListSortOrder.NEWEST,
                    createdAt = java.time.Instant.EPOCH,
                    isTrackingEnabled = true,
                    isVisibleInLibrary = true,
                )
                org.skepsun.kototoro.favourites.ui.categories.select.compose.FavoriteCategoryDialog(
                    contentTitle = "药屋少女的呢喃, 尖帽子的魔法工坊, 无职转生",
                    items = listOf(
                        org.skepsun.kototoro.favourites.ui.categories.select.model.ContentCategoryItem(
                            category = category(1L, "在读"),
                            checkedState = com.google.android.material.checkbox.MaterialCheckBox.STATE_CHECKED,
                            isTrackerEnabled = true,
                        ),
                        org.skepsun.kototoro.favourites.ui.categories.select.model.ContentCategoryItem(
                            category = category(2L, "想读"),
                            checkedState = com.google.android.material.checkbox.MaterialCheckBox.STATE_UNCHECKED,
                            isTrackerEnabled = true,
                        ),
                    ),
                    onCategoryToggle = { _, _ -> },
                    onManageCategories = {},
                    onDismiss = {},
                )
            }
        }
    }
}
