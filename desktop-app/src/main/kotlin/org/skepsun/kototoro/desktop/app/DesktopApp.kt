package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.ui.topbar.SourceResultsTopBar
import org.skepsun.kototoro.list.ui.compose.ContentCollectionError
import org.skepsun.kototoro.list.ui.compose.ContentGridLoadingSkeleton
import org.skepsun.kototoro.core.ui.topbar.TopBarActionItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.desktop.runtime.DesktopNsfwTarget
import org.skepsun.kototoro.desktop.runtime.isNsfwSource
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgePill
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgeText
import org.skepsun.kototoro.list.ui.compose.ContentCardBadgeTone
import org.skepsun.kototoro.list.ui.compose.ContentCardBottomProgressBar
import org.skepsun.kototoro.list.ui.compose.ContentCardPresentation
import org.skepsun.kototoro.list.ui.compose.ContentCompactCard
import org.skepsun.kototoro.list.ui.compose.ContentPosterCard
import org.skepsun.kototoro.list.ui.compose.ContentCollectionGrid
import org.skepsun.kototoro.list.ui.compose.ContentCollectionColumn
import org.skepsun.kototoro.list.ui.compose.ContentCollectionState
import org.skepsun.kototoro.list.ui.compose.rememberContentCollectionState
import org.skepsun.kototoro.list.ui.compose.TabletPosterCover
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.list.ui.compose.rememberCoverRimBorderBrush
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun DesktopStartup(error: String?) = DesktopTheme {
    Box(Modifier.fillMaxSize().background(Canvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Kototoro", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            if (error == null) {
                CircularProgressIndicator()
                Text(desktopText("app.opening"), color = Muted)
            } else Text(desktopText("app.startFailed", error), color = MaterialTheme.colorScheme.error, modifier = Modifier.widthIn(max = 700.dp))
        }
    }
}

@Composable
fun DesktopApp(controller: DesktopController, closing: Boolean = false, fullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null, onExitRequest: () -> Unit = {}) = DesktopTheme(controller.state.collectAsState().value.appearance,
        controller.state.collectAsState().value.interfaceStyle) {
    val state by controller.state.collectAsState()
    val challenges = controller.session.browserChallenges
    val challenge = challenges?.pending?.collectAsState()?.value
    val blockedSites = challenges?.blocked?.collectAsState()?.value.orEmpty()
    val browserScope = rememberCoroutineScope()
    val favouritesCollection = rememberContentCollectionState()
    val historyCollection = rememberContentCollectionState()
    val reading = state.screen == DesktopScreen.READER || state.screen == DesktopScreen.NOVEL ||
        state.screen == DesktopScreen.VIDEO
    val listState = if (state.screen == DesktopScreen.DETAILS && state.detailsOrigin != null) {
        state.copy(screen = requireNotNull(state.detailsOrigin))
    } else state
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val backdrop = rememberLayerBackdrop()
        // Record the canvas before controls draw, so a glass surface never samples itself.
        Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(Canvas).background(desktopCanvasBrush()))
        CompositionLocalProvider(LocalDesktopWindowWidth provides maxWidth, LocalDesktopBackdrop provides backdrop) {
            Row(Modifier.fillMaxSize()) {
                if (!reading) DesktopNavigationRail(controller, listState, !closing)
                Column(Modifier.weight(1f).fillMaxHeight()
                    .padding(if (reading || listState.screen == DesktopScreen.HOME) 0.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (reading) 0.dp else 14.dp)) {
                    if (!reading && listState.screen !in setOf(DesktopScreen.LIBRARY, DesktopScreen.HISTORY,
                            DesktopScreen.EXPLORE, DesktopScreen.HOME, DesktopScreen.FEED)) Header(listState, controller, !closing)
                    challenge?.let { pending ->
                        var windowNotice by remember(pending.id) { mutableStateOf<String?>(null) }
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(desktopText("challenge.hint"))
                                Text(pending.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { challenges.complete(pending.id, true) }, enabled = !closing,
                                        modifier = Modifier.testTag("challenge-continue")) { Text(desktopText("challenge.continue")) }
                                    OutlinedButton(onClick = { challenges.complete(pending.id, false) }, enabled = !closing,
                                        modifier = Modifier.testTag("challenge-cancel")) { Text(desktopText("challenge.cancel")) }
                                    OutlinedButton(onClick = {
                                        browserScope.launch {
                                            try {
                                                windowNotice = if (challenges.showWindow(pending.id)?.visible == true) {
                                                    desktopText("challenge.shown")
                                                } else desktopText("challenge.ended")
                                            } catch (error: CancellationException) { throw error }
                                            catch (_: Exception) { windowNotice = desktopText("challenge.showFailed") }
                                        }
                                    }, enabled = !closing, modifier = Modifier.testTag("challenge-show")) { Text(desktopText("challenge.show")) }
                                }
                                windowNotice?.let { Text(it) }
                            }
                        }
                    }
                    // Android's "captcha required" notification: background loads never open the window themselves.
                    if (blockedSites.isNotEmpty() && challenge == null && !reading) {
                        BlockedSitesNotice(blockedSites, enabled = !closing && !state.busy,
                            onVerify = controller::verifyBlockedSite, onDismiss = { challenges?.dismissBlocked(it) })
                    }
                    if (state.busy || closing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Notice(it, true) }
                    state.message?.let { Notice(it, false) }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (listState.screen) {
                            DesktopScreen.HOME -> DesktopHomePanel(controller, listState, !closing)
                            DesktopScreen.FEED -> DesktopFeedPanel(controller, listState, !closing)
                            DesktopScreen.UPDATED -> DesktopUpdatesPage(controller, state, !closing)
                            DesktopScreen.SUGGESTIONS -> DesktopSuggestionsPage(controller, state, !closing)
                            DesktopScreen.EXPLORE -> Browse(controller, listState, !closing)
                            DesktopScreen.LIBRARY, DesktopScreen.HISTORY -> DesktopLibraryPanel(controller, listState, !closing,
                                if (listState.screen == DesktopScreen.HISTORY) historyCollection else favouritesCollection)
                            DesktopScreen.DETAILS -> Details(controller, state)
                            DesktopScreen.READER -> DesktopReader(controller, state, closing, fullscreen, onToggleFullscreen)
                            DesktopScreen.NOVEL -> DesktopNovelReader(controller, state, closing, fullscreen, onToggleFullscreen)
                            DesktopScreen.VIDEO -> DesktopVideoPlayer(controller, state, closing, fullscreen, onToggleFullscreen)
                            DesktopScreen.PREFERENCES -> DesktopSourcePreferences(controller, state)
                            DesktopScreen.BROWSER -> BrowserPanel(controller.session)
                            DesktopScreen.DOWNLOADS -> DesktopDownloadsPanel(controller, closing)
                            DesktopScreen.BACKUPS -> DesktopBackupsPanel(controller, closing)
                            DesktopScreen.RECOMMENDATIONS -> DesktopRecommendations(controller, listState)
                            DesktopScreen.SERVICES -> DesktopServicesPage(controller, state)
                            DesktopScreen.EXTENSIONS -> DesktopExtensionsPanel(controller, closing)
                            DesktopScreen.READER_SETTINGS -> DesktopReaderSettingsPage(controller, listState)
                            DesktopScreen.SOURCE_SETTINGS -> DesktopSourceSettingsPage(controller, listState)
                            DesktopScreen.SETUP_WIZARD -> DesktopSetupWizard(controller, state)
                            DesktopScreen.SOURCE_TEST -> DesktopSourceTestPage(controller, state)
                            DesktopScreen.TAG_BLACKLIST -> DesktopTagBlacklistPage(controller, listState)
                            DesktopScreen.READING_STATS -> DesktopReadingStatsPage(controller, listState)
                            DesktopScreen.STORAGE -> DesktopStorageSettingsPage(controller, state)
                            DesktopScreen.DATA_CLEANUP -> DesktopDataCleanupPage(controller, state)
                            DesktopScreen.PLAYBACK_SETTINGS -> DesktopPlaybackSettingsPage(controller, state)
                            DesktopScreen.PROXY_SETTINGS -> DesktopProxySettingsPage(controller, state)
                            DesktopScreen.CHANGELOG -> DesktopChangelogPage(controller, state)
                            DesktopScreen.CRASH_LOGS -> DesktopCrashLogsPage(controller, state)
                            DesktopScreen.BOOKMARKS -> DesktopBookmarksPage(controller, listState, !closing)
                            DesktopScreen.REPLACE_RULES -> DesktopReplaceRulesPage(controller, state)
                            DesktopScreen.DICTIONARY_RULES -> DesktopDictionaryRulesPage(controller, state)
                            DesktopScreen.DOWNLOAD_SETTINGS -> DesktopDownloadSettingsPage(controller, state)
                            DesktopScreen.NAV_CONFIG -> DesktopNavigationConfigPage(controller, state)
                            DesktopScreen.MORE -> DesktopMorePanel(controller, !closing, onExitRequest)
                        }
                        if (state.screen == DesktopScreen.DETAILS && state.detailsOrigin != null) {
                            DesktopDetailsOverlay(controller, state, !state.busy && !closing)
                        }
                    }
                }
            }
            // Android asks inside its reader; the question belongs to the window, not to one screen, so it overlays
            // whichever list or details page started the read.
            state.incognitoPrompt?.let { prompt ->
                DesktopIncognitoDialog(prompt, !closing, controller::resolveIncognitoPrompt,
                    controller::setIncognitoPromptDontAskAgain, controller::dismissIncognitoPrompt)
            }
        }
    }
}

@Composable
internal fun DesktopSourcePane(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    var filter by remember { mutableStateOf("") }
    // The rail lists every installed source, so it carries Android's two top-bar filters itself: the same filter also
    // narrows the works of whichever source is picked, which is how the Android tablet keeps one scope for both.
    val settings = state.sourceSettings
    // Switching a source off on the extensions page removes it here, as Android's enabled-only source list does.
    val sources = settings.sorted(state.enabledSources.filter {
        (it.displayName.contains(filter, true) || it.source.locale.contains(filter, true)) &&
            state.exploreFilter.accepts(it) &&
            // Android's SOURCES_AND_BROWSE target: the rail is the only place Windows can hide an adult source.
            !(settings.hidesNsfw(DesktopNsfwTarget.SOURCES) && it.isNsfwSource(state.sourceNsfwOverrides))
    })
    Column(Modifier.width(240.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface,
        RoundedCornerShape(20.dp)).padding(16.dp).testTag("source-pane"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(desktopText("browse.mySources"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Button(onClick = {
            val chooser = JFileChooser().apply {
                fileFilter = FileNameExtensionFilter(desktopText("browse.importFilter"), "jar", "apk", "cs3")
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) controller.importJar(chooser.selectedFile.toPath())
        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(desktopText("browse.importJar")) }
        HorizontalDivider()
        Text(desktopText("browse.sources", sources.size), fontWeight = FontWeight.SemiBold)
        DesktopSearchField(filter, { filter = it }, desktopText("browse.findSource"), modifier = Modifier.fillMaxWidth())
        DesktopSourceFilterControls(
            state.exploreFilter, state.enabledSources, controller::exploreFilter, enabled, "rail",
        )
        LazyColumn(Modifier.weight(1f).testTag("source-list")) {
            items(sources, key = { it.source.name }) { source ->
                // Android's source list row; the picked source wears the selection container.
                DesktopSourceRow(controller, source, enabled,
                    selected = state.selectedSource?.source?.name == source.source.name)
            }
            if (sources.isEmpty()) item { Text(desktopText("browse.sourcesEmpty"), fontSize = 12.sp, color = Muted) }
        }
    }
}

@Composable
private fun Header(state: DesktopAppState, controller: DesktopController, enabled: Boolean) {
    val title = when (state.screen) {
        DesktopScreen.HOME -> desktopText("nav.home")
        DesktopScreen.FEED -> desktopText("nav.subscriptions")
        DesktopScreen.UPDATED -> AndroidStrings["updated"]
        DesktopScreen.SUGGESTIONS -> AndroidStrings["suggestions"]
        DesktopScreen.EXPLORE -> state.selectedSource?.displayName ?: desktopText("screens.browseSource")
        DesktopScreen.LIBRARY -> desktopText("nav.favourites")
        DesktopScreen.HISTORY -> desktopText("screens.history")
        DesktopScreen.DOWNLOADS -> desktopText("screens.downloads")
        DesktopScreen.BACKUPS -> desktopText("screens.backups")
        DesktopScreen.RECOMMENDATIONS -> desktopText("screens.recommendations")
        DesktopScreen.SERVICES -> AndroidStrings["services"]
        DesktopScreen.READER_SETTINGS -> desktopText("screens.readerSettings")
        DesktopScreen.SOURCE_SETTINGS -> desktopText("screens.sourceSettings")
        DesktopScreen.SETUP_WIZARD -> AndroidStrings["setup_wizard"]
        DesktopScreen.SOURCE_TEST -> AndroidStrings["source_test_title"]
        DesktopScreen.TAG_BLACKLIST -> desktopText("screens.tagBlacklist")
        DesktopScreen.READING_STATS -> AndroidStrings["reading_stats"]
        DesktopScreen.STORAGE -> AndroidStrings["storage_and_network"]
        DesktopScreen.DATA_CLEANUP -> AndroidStrings["data_removal"]
        DesktopScreen.PLAYBACK_SETTINGS -> AndroidStrings["playback_settings"]
        DesktopScreen.PROXY_SETTINGS -> AndroidStrings["proxy"]
        DesktopScreen.CHANGELOG -> AndroidStrings["changelog"]
        DesktopScreen.CRASH_LOGS -> AndroidStrings["crash_logs"]
        DesktopScreen.REPLACE_RULES -> AndroidStrings["replace_rules"]
        DesktopScreen.DICTIONARY_RULES -> AndroidStrings["dictionary_rules"]
        DesktopScreen.DOWNLOAD_SETTINGS -> AndroidStrings["downloads"]
        DesktopScreen.BOOKMARKS -> AndroidStrings["bookmarks"]
        DesktopScreen.NAV_CONFIG -> AndroidStrings["main_screen_sections"]
        DesktopScreen.EXTENSIONS -> desktopText("screens.extensions")
        DesktopScreen.DETAILS -> desktopText("screens.details")
        DesktopScreen.READER, DesktopScreen.NOVEL, DesktopScreen.VIDEO -> state.chapter?.title ?: desktopText("screens.reader")
        DesktopScreen.PREFERENCES -> desktopText("screens.sourcePreferences")
        DesktopScreen.BROWSER -> desktopText("screens.browser")
        DesktopScreen.MORE -> desktopText("common.more")
    }
    // Android's settings top bar: every sub-page goes back from the same place, before its title.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        state.screen.settingsBackTag?.let { tag ->
            IconButton({ controller.settingsBack() }, enabled = enabled, modifier = Modifier.testTag(tag)) {
                Icon(painterResource("icons/ic_arrow_forward.svg"), desktopText("common.back"), tint = Ink,
                    modifier = Modifier.size(22.dp).graphicsLayer(scaleX = -1f))
            }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Ink,
            modifier = Modifier.heightIn(min = 40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BlockedSitesNotice(sites: List<String>, enabled: Boolean, onVerify: (String) -> Unit,
    onDismiss: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.testTag("challenge-blocked")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(desktopText("challenge.blockedTitle", sites.size), style = MaterialTheme.typography.titleSmall)
            sites.take(3).forEach { site ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(site.removePrefix("https://").removePrefix("http://").trimEnd('/'), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton({ onVerify(site) }, enabled = enabled,
                        modifier = Modifier.testTag("challenge-blocked-verify:$site")) { Text(desktopText("challenge.verify")) }
                    TextButton({ onDismiss(site) }, modifier = Modifier.testTag("challenge-blocked-dismiss:$site")) {
                        Text(desktopText("challenge.dismiss"))
                    }
                }
            }
            if (sites.size > 3) Text(desktopText("challenge.blockedMore", sites.size - 3),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Notice(message: String, error: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        // Errors are selectable so a source failure can be copied into a report.
        SelectionContainer {
            Text(message, color = if (error) MaterialTheme.colorScheme.error else Accent,
                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag(if (error) "notice-error" else "notice-message"),
                maxLines = if (error) 12 else 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Browse(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    // Browsing stays usable while a source loads: a newer request replaces the pending one.
    if (state.selectedSource == null) {
        DesktopSourceGrid(controller, state, enabled)
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        DesktopSourcePane(controller, state, enabled)
        Box(Modifier.weight(1f).fillMaxHeight()) { SourceResults(controller, state, enabled) }
    }
}

@Composable
private fun SourceResults(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    if (state.selectedSource == null) {
        EmptyPanel(desktopText("browse.worksEmptyTitle"), desktopText("browse.worksEmptyDetail"))
        return
    }
    var query by remember(state.selectedSource.source.name, state.query) { mutableStateOf(state.query) }
    if (state.filterDialogOpen) DesktopFilters(controller, state, query)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Android's source-results top bar: back, the source's name and the action capsule (sort, filters, settings).
        val orders = state.descriptor?.sortOrders.orEmpty()
        val popular = state.descriptor == null || "POPULARITY" in orders
        val latest = "UPDATED" in orders
        val currentOrder = state.browseOrder ?: state.descriptor?.defaultSortOrder.orEmpty()
        SourceResultsTopBar(
            title = state.selectedSource.displayName,
            onBack = { if (enabled) controller.exitSource() },
            // Android's forward arrow, mirrored.
            backIcon = {
                Icon(painterResource("icons/ic_arrow_forward.svg"), contentDescription = null, tint = Ink,
                    modifier = Modifier.size(22.dp).graphicsLayer(scaleX = -1f))
            },
            backLabel = desktopText("browse.backToSources"),
            actions = listOfNotNull(
                TopBarActionItem(
                    key = "sort",
                    icon = painterResource("icons/ic_sort.svg"),
                    label = desktopText("browse.sort", DesktopSourceLabels.sortOrder(currentOrder)),
                    enabled = enabled,
                    modifier = Modifier.testTag("source-sort"),
                    menu = { dismiss ->
                        // Mihon's two listings first, then every order a parser plugin declares.
                        if (popular) CompactDropdownMenuItem({ CompactDropdownMenuText(desktopText("browse.popular")) },
                            { dismiss(); query = ""; controller.browseUnfiltered() },
                            modifier = Modifier.testTag("source-listing:popular"))
                        if (latest) CompactDropdownMenuItem({ CompactDropdownMenuText(desktopText("browse.latest")) },
                            { dismiss(); query = ""; controller.browseUnfiltered(latest = true) },
                            modifier = Modifier.testTag("source-listing:latest"))
                        val others = orders.filter { it != "POPULARITY" && it != "UPDATED" }
                        if (others.isNotEmpty()) {
                            if (popular || latest) HorizontalDivider()
                            orders.forEach { order ->
                                CompactDropdownMenuItem({ CompactDropdownMenuText(DesktopSourceLabels.sortOrder(order)) },
                                    { dismiss(); controller.browseOrdered(order) },
                                    modifier = Modifier.testTag("source-sort:$order"),
                                    trailingIcon = if (order == state.browseOrder) {
                                        { Icon(painterResource("icons/ic_check.svg"), null,
                                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                    } else null)
                            }
                        }
                    },
                ),
                TopBarActionItem(
                    key = "filters",
                    icon = painterResource("icons/ic_filter_menu.svg"),
                    label = if (state.appliedFilters.isEmpty()) desktopText("browse.filters")
                        else desktopText("browse.filtersApplied"),
                    onClick = { controller.filters() },
                    enabled = enabled,
                    badge = state.appliedFilters.isNotEmpty(),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("source-filters"),
                ).takeIf { state.descriptor?.isDynamicFilteringSupported == true },
                TopBarActionItem(
                    key = "settings",
                    icon = painterResource("icons/ic_settings.svg"),
                    label = desktopText("browse.sourceSettings"),
                    onClick = { controller.preferences() },
                    enabled = enabled,
                    modifier = Modifier.testTag("source-settings"),
                ).takeIf { state.descriptor?.isPreferencesSupported == true },
            ),
            moreIcon = painterResource("icons/ic_more_vert.svg"),
            moreLabel = desktopText("common.more"),
            surface = { modifier, content -> DesktopControlSurface(modifier, RoundedCornerShape(percent = 50), content) },
            menuHost = { expanded, onDismiss, _, content -> DropdownMenu(expanded, onDismiss, content = content) },
            backModifier = Modifier.testTag("source-back"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DesktopSearchField(query, { query = it }, desktopText("browse.searchWorks"), enabled = enabled,
                modifier = Modifier.weight(1f), tag = "source-query")
            Button({ controller.browse(0, query) }, enabled = enabled) { Text(desktopText("browse.search")) }
        }
        // Android's ExploreRepository filters every browse page through the global tag blacklist; the page in the
        // state stays untouched so paging and the endless list still follow what the source returned.
        val blacklist = remember(state.sourceSettings.tagBlacklist) { state.sourceSettings.tagBlacklistRules() }
        val visibleItems = remember(state.items, blacklist) {
            state.items.filter { content -> content.tags.none { blacklist.containsTagTitle(it.title) } }
        }
        // Android's paging list keeps fetching as you scroll: the grid's own footer composes only once its last rows are
        // reached, and each composition asks for the next batch — no manual 下一批 and no polling of the layout.
        Box(Modifier.weight(1f)) {
            when {
                // Android's list states for a page with nothing to show yet: skeletons while the first batch loads,
                // the error card with retry when it failed; the shared empty state only once the source answered.
                state.items.isEmpty() && state.busy -> BoxWithConstraints(Modifier.fillMaxWidth()
                    .testTag("browse-loading")) {
                    // The grid's own Adaptive(112dp) cells and 8dp gaps, so the skeleton sits where the cards will.
                    ContentGridLoadingSkeleton(columns = ((maxWidth.value + 8f) / 120f).toInt(),
                        posterAspectRatio = 96f / 136f, contentPadding = PaddingValues(0.dp))
                }
                state.items.isEmpty() && state.error != null -> ContentCollectionError(
                    title = AndroidStrings["error_occurred"],
                    message = state.error,
                    icon = {
                        Icon(painterResource("icons/ic_error_large.svg"), contentDescription = null,
                            tint = MaterialTheme.colorScheme.error)
                    },
                    retryLabel = AndroidStrings["retry"],
                    onRetry = { controller.browse(state.offset, state.query) },
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.Center).testTag("browse-error"),
                    retryModifier = Modifier.testTag("browse-retry"),
                )
                else -> ContentGrid(visibleItems, enabled, controller.session.covers, controller::details,
                    onReachEnd = { controller.browseNextPage() }, endReached = state.browseExhausted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ controller.browse((state.offset - 1).coerceAtLeast(0)) },
                enabled = enabled && state.offset > 0) { Text(desktopText("browse.previousPage")) }
            Text(desktopText("browse.pageNumber", state.offset + 1), color = Muted)
            OutlinedButton({ controller.browseMore() },
                enabled = enabled && !state.browseExhausted && state.items.isNotEmpty(),
                modifier = Modifier.testTag("browse-next-page")) { Text(desktopText("browse.nextPage")) }
            if (state.browseExhausted) Text(desktopText("browse.noMorePages"), color = Muted,
                modifier = Modifier.testTag("browse-no-more"))
        }
    }
}

@Composable
internal fun ContentGrid(contents: List<SourceContent>, enabled: Boolean, covers: DesktopCovers,
    onOpen: (SourceContent) -> Unit, progressOf: (SourceContent) -> Float? = { null },
    newChaptersOf: (SourceContent) -> Int = { 0 },
    /** Android's pin badge: only the favourites shelf carries it, so every other grid keeps the default. */
    pinnedOf: (SourceContent) -> Boolean = { false },
    /** Null is plain browsing; a set turns each card into a selection target, as Android's list does on long press. */
    selectedIds: Set<Long>? = null, onToggleSelection: (SourceContent) -> Unit = {},
    /** Android's list mode: the shelves can render one work per row instead of the poster grid. */
    listMode: org.skepsun.kototoro.core.prefs.ListMode =
        org.skepsun.kototoro.core.prefs.ListMode.COMPACT_GRID,
    /** Android's grid size, percent of the default card width (the shelves' display options). */
    gridSize: Int = 100,
    metaOf: (SourceContent) -> String = { it.authors.joinToString().ifBlank { it.source.name } },
    /** Shared near-end loading requests one more batch per observed item count. */
    onReachEnd: (() -> Unit)? = null,
    /** Set when the source has no more batches: the footer then stops asking and the row below says so. */
    endReached: Boolean = false, collectionState: ContentCollectionState? = null,
    /** Android's grouped lists (history by date or progress): a header row whenever the label changes. */
    groupOf: ((SourceContent) -> String?)? = null) {
    if (contents.isEmpty()) { EmptyPanel(desktopText("browse.noWorks"), desktopText("browse.noWorksDetail")); return }
    val rows = remember(contents, groupOf) { contentRows(contents, groupOf) }
    if (!listMode.isGrid) {
        ContentRowList(contents, rows, enabled, covers, onOpen, progressOf, newChaptersOf, pinnedOf,
            selectedIds, onToggleSelection, metaOf, collectionState?.list,
            detailed = listMode == org.skepsun.kototoro.core.prefs.ListMode.DETAILED_LIST)
        return
    }
    val titleOverlay = listMode.hasTitleOverlay
    val gridScale = gridSize / 100f
    val isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS
    val rimBorderBrush = rememberCoverRimBorderBrush(isIosStyle, isDark = LocalDesktopDark.current)
    ContentCollectionGrid(count = rows.size, key = { rows[it].key(contents) },
        fullSpan = { rows[it] is ContentRow.Header }, contentType = { rows[it].javaClass },
        columns = GridCells.Adaptive((112 * gridScale).dp), state = collectionState?.grid ?: rememberLazyGridState(),
        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().testTag("content-grid"),
        canLoadMore = onReachEnd != null && !endReached,
        onLoadMore = { onReachEnd?.invoke() },
        footer = if (onReachEnd != null && !endReached) ({
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp).testTag("content-loading"), strokeWidth = 2.dp)
            }
        }) else null,
    ) { index ->
        val row = rows[index]
        if (row is ContentRow.Header) ContentGroupHeader(row.title) else {
        val content = contents[(row as ContentRow.Work).index]
        val selecting = selectedIds != null
        val isSelected = selectedIds?.contains(content.id) == true
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val style = CompactPosterCardStyle(maxWidth, maxWidth * (136f / 96f), 12.dp)
            val metrics = remember(maxWidth) { contentCardBadgeMetricsFor(maxWidth) }
            val percent = progressOf(content)?.takeIf { ReadingProgress.isValid(it) }
            // Android's compact grid lifts the bottom badges clear of the title on the scrim.
            val bottomLift = if (titleOverlay) (style.posterHeight.value * 0.28f).dp.coerceIn(32.dp, 44.dp) else 0.dp
            ContentPosterCard(ContentCardPresentation(content.id, content.title), style,
                rimBorderBrush = rimBorderBrush,
                onClick = { if (selecting) onToggleSelection(content) else onOpen(content) },
                onLongClick = { onToggleSelection(content) },
                enabled = enabled, isSelected = isSelected, compactOverlay = titleOverlay, gridScale = gridScale,
                cellContentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("content:${content.id}"),
                cover = { DesktopCover(content, covers, Modifier.matchParentSize()) },
                overlays = {
                    // Android pins the unread counter to the top-right corner, a lone number in the
                    // accent pill (KototoroContentCard), and the language pill to the bottom-left.
                    val newChapters = newChaptersOf(content)
                    if (newChapters > 0) {
                        ContentCardBadgePill(ContentCardBadgeTone.COUNTER, isIosStyle, metrics,
                            Modifier.align(Alignment.TopEnd).padding(end = 5.dp, top = 5.dp)
                                .testTag("content-new-chapters:${content.id}")
                                .semantics {
                                    contentDescription = desktopText("library.newChapters", newChapters)
                                }) { colors ->
                            ContentCardBadgeText(newChapters.toString(), colors.content, metrics)
                        }
                    }
                    val locale = content.source.locale.substringBefore('-').substringBefore('_').uppercase()
                    if (locale.isNotBlank()) ContentCardBadgePill(ContentCardBadgeTone.NEUTRAL, isIosStyle, metrics,
                        Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = bottomLift + 5.dp)
                            .testTag("content-language:${content.id}")) { colors ->
                        ContentCardBadgeText(locale, colors.content, metrics)
                    }
                    // Android draws a pin icon among the card badges (KototoroContentCard's "pin" badge);
                    // the bottom-right corner is the only slot the other pills leave free.
                    if (pinnedOf(content)) Icon(painterResource("icons/ic_pin.svg"),
                        desktopText("library.pinned"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = 5.dp, bottom = bottomLift + 5.dp).size(18.dp)
                            .testTag("content-pinned:${content.id}"))
                },
                progress = {
                    if (percent != null) ContentCardBottomProgressBar(percent, ReadingProgress.isCompleted(percent),
                        Modifier.align(Alignment.BottomCenter).testTag("content-progress:${content.id}"))
                },
            )
        }
        }
    }
}

/** A grouped list's row: a section title or the work at [Work.index] of the contents. */
internal sealed interface ContentRow {
    data class Header(val title: String, val position: Int) : ContentRow
    data class Work(val index: Int) : ContentRow

    fun key(contents: List<SourceContent>): Any = when (this) {
        is Header -> "header:$position:$title"
        is Work -> contents[index].id
    }
}

internal fun contentRows(contents: List<SourceContent>, groupOf: ((SourceContent) -> String?)?): List<ContentRow> {
    if (groupOf == null) return contents.indices.map(ContentRow::Work)
    val rows = ArrayList<ContentRow>(contents.size + 8)
    var previous: String? = null
    contents.forEachIndexed { index, content ->
        val group = groupOf(content)
        if (group != null && group != previous) rows += ContentRow.Header(group, rows.size)
        previous = group
        rows += ContentRow.Work(index)
    }
    return rows
}

@Composable
private fun ContentGroupHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Ink,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).testTag("content-group:$title"))
}

/**
 * Android's LIST mode: a thumbnail, the title, one supporting line and the progress bar. Rows keep the card's
 * `content:<id>` tag and the same click/long-press semantics, so selection and navigation stay unchanged.
 */
@Composable
private fun ContentRowList(contents: List<SourceContent>, rows: List<ContentRow>, enabled: Boolean, covers: DesktopCovers,
    onOpen: (SourceContent) -> Unit, progressOf: (SourceContent) -> Float?, newChaptersOf: (SourceContent) -> Int,
    pinnedOf: (SourceContent) -> Boolean,
    selectedIds: Set<Long>?, onToggleSelection: (SourceContent) -> Unit, metaOf: (SourceContent) -> String,
    listState: LazyListState? = null,
    /** Android's DETAILED_LIST: a larger cover and the work's tags under the supporting line. */
    detailed: Boolean = false) {
    val coverWidth = if (detailed) 72.dp else 48.dp
    val coverHeight = if (detailed) 108.dp else 72.dp
    val isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS
    val rimBorderBrush = rememberCoverRimBorderBrush(isIosStyle, isDark = LocalDesktopDark.current)
    ContentCollectionColumn(count = rows.size, key = { rows[it].key(contents) },
        contentType = { rows[it].javaClass },
        state = listState ?: rememberLazyListState(), modifier = Modifier.fillMaxSize().testTag("content-list"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) { index ->
        val row = rows[index]
        if (row is ContentRow.Header) ContentGroupHeader(row.title) else {
        val content = contents[(row as ContentRow.Work).index]
        val selecting = selectedIds != null
        val isSelected = selectedIds?.contains(content.id) == true
        val percent = progressOf(content)?.takeIf { ReadingProgress.isValid(it) }
        val newChapters = newChaptersOf(content)
        ContentCompactCard(
            ContentCardPresentation(content.id, content.title, subtitle = metaOf(content),
                supportingText = if (detailed) content.tags.joinToString(" · ") { it.title }.ifBlank { null } else null),
            onClick = { if (selecting) onToggleSelection(content) else onOpen(content) },
            onLongClick = { onToggleSelection(content) },
            enabled = enabled, isSelected = isSelected,
            modifier = Modifier.testTag("content:${content.id}"),
            cover = {
                TabletPosterCover(content.title, CompactPosterCardStyle(coverWidth, coverHeight, 10.dp),
                    rimBorderBrush = rimBorderBrush, modifier = Modifier.width(coverWidth).height(coverHeight),
                    cover = { DesktopCover(content, covers, Modifier.matchParentSize()) }, compactOverlay = false)
            },
            titleLeading = {
                if (pinnedOf(content)) Icon(painterResource("icons/ic_pin.svg"),
                    desktopText("library.pinned"), tint = Accent,
                    modifier = Modifier.size(16.dp).testTag("content-pinned:${content.id}"))
            },
            status = {
                if (newChapters > 0) Text(desktopText("library.newChapters", newChapters),
                    fontSize = 12.sp, color = Accent,
                    modifier = Modifier.padding(top = 5.dp).testTag("content-new-chapters:${content.id}"))
                if (percent != null) ContentCardBottomProgressBar(percent, ReadingProgress.isCompleted(percent),
                    Modifier.fillMaxWidth().padding(top = 5.dp).testTag("content-progress:${content.id}"))
            },
        )
        }
    }
}

@Composable
private fun Details(controller: DesktopController, state: DesktopAppState) {
    DesktopTabletDetails(controller, state)
}
@Composable
private fun EmptyPanel(title: String, detail: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(detail, color = Muted)
        }
    }
}
