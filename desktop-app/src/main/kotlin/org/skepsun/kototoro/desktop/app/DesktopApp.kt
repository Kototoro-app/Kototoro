package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    val browserScope = rememberCoroutineScope()
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
                            DesktopScreen.EXPLORE, DesktopScreen.HOME, DesktopScreen.FEED)) Header(listState)
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
                    if (state.busy || closing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Notice(it, true) }
                    state.message?.let { Notice(it, false) }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (listState.screen) {
                            DesktopScreen.HOME -> DesktopHomePanel(controller, listState, !closing)
                            DesktopScreen.FEED -> DesktopFeedPanel(controller, listState, !closing)
                            DesktopScreen.EXPLORE -> Browse(controller, listState, !closing)
                            DesktopScreen.LIBRARY, DesktopScreen.HISTORY -> DesktopLibraryPanel(controller, listState, !closing)
                            DesktopScreen.DETAILS -> Details(controller, state)
                            DesktopScreen.READER -> DesktopReader(controller, state, closing, fullscreen, onToggleFullscreen)
                            DesktopScreen.NOVEL -> DesktopNovelReader(controller, state, closing, fullscreen, onToggleFullscreen)
                            DesktopScreen.VIDEO -> DesktopVideoPlayer(controller, state, closing, fullscreen, onToggleFullscreen)
                            DesktopScreen.PREFERENCES -> DesktopSourcePreferences(controller, state)
                            DesktopScreen.BROWSER -> BrowserPanel(controller.session)
                            DesktopScreen.DOWNLOADS -> DesktopDownloadsPanel(controller, closing)
                            DesktopScreen.BACKUPS -> DesktopBackupsPanel(controller, closing)
                            DesktopScreen.RECOMMENDATIONS -> DesktopRecommendations(controller, listState)
                            DesktopScreen.EXTENSIONS -> DesktopExtensionsPanel(controller, closing)
                            DesktopScreen.READER_SETTINGS -> DesktopReaderSettingsPage(controller, listState)
                            DesktopScreen.SOURCE_SETTINGS -> DesktopSourceSettingsPage(controller, listState)
                            DesktopScreen.TAG_BLACKLIST -> DesktopTagBlacklistPage(controller, listState)
                            DesktopScreen.READING_STATS -> DesktopReadingStatsPage(controller, listState)
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
    val sources = settings.sorted(state.sources.filter {
        (it.displayName.contains(filter, true) || it.source.locale.contains(filter, true)) &&
            state.exploreFilter.accepts(it) &&
            // Android's SOURCES_AND_BROWSE target: the rail is the only place Windows can hide an adult source.
            !(settings.hidesNsfw(DesktopNsfwTarget.SOURCES) && it.isNsfwSource())
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
        DesktopSourceFilterControls(state.exploreFilter, state.sources, controller::exploreFilter, enabled, "rail")
        LazyColumn(Modifier.weight(1f).testTag("source-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.source.name }) { source ->
                SourceRailTile(source, state, enabled, controller, Modifier.fillMaxWidth())
            }
            if (sources.isEmpty()) item { Text(desktopText("browse.sourcesEmpty"), fontSize = 12.sp, color = Muted) }
        }
    }
}

/** One rail entry, used by both layouts so a source tile behaves the same in list and grid mode. */
@Composable
private fun SourceRailTile(source: SourceListing, state: DesktopAppState, enabled: Boolean,
    controller: DesktopController, modifier: Modifier) {
    val selected = state.selectedSource?.source?.name == source.source.name
    Surface(color = if (selected) Accent.copy(alpha = .10f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Column(Modifier.fillMaxWidth().testTag("source:${source.source.name}")
            .clickable(enabled = enabled) { controller.selectSource(source) }.padding(12.dp)) {
            Text(source.displayName, maxLines = 2, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text("${DesktopSourceLabels.ecosystem(source.ecosystem)} · ${source.source.locale.ifBlank { desktopText("common.multilingual") }}" +
                " · ${DesktopSourceLabels.contentType(source.source.contentType)}", fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun Header(state: DesktopAppState) {
    val title = when (state.screen) {
        DesktopScreen.HOME -> desktopText("nav.home")
        DesktopScreen.FEED -> desktopText("nav.subscriptions")
        DesktopScreen.EXPLORE -> state.selectedSource?.displayName ?: desktopText("screens.browseSource")
        DesktopScreen.LIBRARY -> desktopText("nav.favourites")
        DesktopScreen.HISTORY -> desktopText("screens.history")
        DesktopScreen.DOWNLOADS -> desktopText("screens.downloads")
        DesktopScreen.BACKUPS -> desktopText("screens.backups")
        DesktopScreen.RECOMMENDATIONS -> desktopText("screens.recommendations")
        DesktopScreen.READER_SETTINGS -> desktopText("screens.readerSettings")
        DesktopScreen.SOURCE_SETTINGS -> desktopText("screens.sourceSettings")
        DesktopScreen.TAG_BLACKLIST -> desktopText("screens.tagBlacklist")
        DesktopScreen.READING_STATS -> AndroidStrings["reading_stats"]
        DesktopScreen.EXTENSIONS -> desktopText("screens.extensions")
        DesktopScreen.DETAILS -> desktopText("screens.details")
        DesktopScreen.READER, DesktopScreen.NOVEL, DesktopScreen.VIDEO -> state.chapter?.title ?: desktopText("screens.reader")
        DesktopScreen.PREFERENCES -> desktopText("screens.sourcePreferences")
        DesktopScreen.BROWSER -> desktopText("screens.browser")
        DesktopScreen.MORE -> desktopText("common.more")
    }
    Text(title, style = MaterialTheme.typography.headlineMedium, color = Ink,
        modifier = Modifier.heightIn(min = 40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ controller.exitSource() }, enabled = enabled, modifier = Modifier.testTag("source-back")) {
                // Android's forward arrow, mirrored.
                Icon(painterResource("icons/ic_arrow_forward.svg"), contentDescription = desktopText("browse.backToSources"), tint = Ink,
                    modifier = Modifier.size(22.dp).graphicsLayer(scaleX = -1f))
            }
            Text(state.selectedSource.displayName, style = MaterialTheme.typography.headlineMedium, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DesktopSearchField(query, { query = it }, desktopText("browse.searchWorks"), enabled = enabled,
                modifier = Modifier.weight(1f), tag = "source-query")
            Button({ controller.browse(0, query) }, enabled = enabled) { Text(desktopText("browse.search")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val orders = state.descriptor?.sortOrders.orEmpty()
            if (state.descriptor == null || "POPULARITY" in orders) {
                OutlinedButton({ query = ""; controller.browseUnfiltered() }, enabled = enabled) { Text(desktopText("browse.popular")) }
            }
            if ("UPDATED" in orders) {
                OutlinedButton({ query = ""; controller.browseUnfiltered(latest = true) }, enabled = enabled) { Text(desktopText("browse.latest")) }
            }
            // Sources with more than the two Mihon orders (all parser plugins) get the full list.
            if (orders.any { it != "POPULARITY" && it != "UPDATED" }) {
                var expanded by remember(state.selectedSource.source.name) { mutableStateOf(false) }
                Box {
                    OutlinedButton({ expanded = true }, enabled = enabled, modifier = Modifier.testTag("source-sort")) {
                        Text(desktopText("browse.sort",
                            DesktopSourceLabels.sortOrder(state.browseOrder ?: state.descriptor?.defaultSortOrder.orEmpty())))
                    }
                    DropdownMenu(expanded && enabled, { expanded = false }) {
                        orders.forEach { order ->
                            DropdownMenuItem(onClick = { expanded = false; controller.browseOrdered(order) },
                                modifier = Modifier.testTag("source-sort:$order"),
                                text = { Text(DesktopSourceLabels.sortOrder(order)) })
                        }
                    }
                }
            }
            if (state.descriptor?.isDynamicFilteringSupported == true) {
                OutlinedButton({ controller.filters() }, enabled = enabled,
                    modifier = Modifier.testTag("source-filters")) {
                    Text(if (state.appliedFilters.isEmpty()) desktopText("browse.filters") else desktopText("browse.filtersApplied"))
                }
            }
            if (state.descriptor?.isPreferencesSupported == true) {
                OutlinedButton({ controller.preferences() }, enabled = enabled) { Text(desktopText("browse.sourceSettings")) }
            }
        }
        // Android's ExploreRepository filters every browse page through the global tag blacklist; the page in the
        // state stays untouched so paging and the "next page" button still follow what the source returned.
        val blacklist = remember(state.sourceSettings.tagBlacklist) { state.sourceSettings.tagBlacklistRules() }
        val visibleItems = remember(state.items, blacklist) {
            state.items.filter { content -> content.tags.none { blacklist.containsTagTitle(it.title) } }
        }
        Box(Modifier.weight(1f)) { ContentGrid(visibleItems, enabled, controller.session.covers, controller::details) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ controller.browse((state.offset - 1).coerceAtLeast(0)) },
                enabled = enabled && state.offset > 0) { Text(desktopText("browse.previousPage")) }
            Text(desktopText("browse.pageNumber", state.offset + 1), color = Muted)
            OutlinedButton({ controller.browse(state.offset + 1) },
                enabled = enabled && state.items.isNotEmpty()) { Text(desktopText("browse.nextPage")) }
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
    listMode: org.skepsun.kototoro.desktop.runtime.DesktopListMode =
        org.skepsun.kototoro.desktop.runtime.DesktopListMode.GRID,
    metaOf: (SourceContent) -> String = { it.authors.joinToString().ifBlank { it.source.name } }) {
    if (contents.isEmpty()) { EmptyPanel(desktopText("browse.noWorks"), desktopText("browse.noWorksDetail")); return }
    if (listMode == org.skepsun.kototoro.desktop.runtime.DesktopListMode.LIST) {
        ContentRowList(contents, enabled, covers, onOpen, progressOf, newChaptersOf, pinnedOf,
            selectedIds, onToggleSelection, metaOf)
        return
    }
    val isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS
    val rimBorderBrush = rememberCoverRimBorderBrush(isIosStyle, isDark = LocalDesktopDark.current)
    LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize().testTag("content-grid")) {
        items(contents, key = { it.id }) { content ->
            val selecting = selectedIds != null
            val isSelected = selectedIds?.contains(content.id) == true
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("content:${content.id}")
                .combinedClickable(enabled = enabled,
                    onClick = { if (selecting) onToggleSelection(content) else onOpen(content) },
                    onLongClick = { onToggleSelection(content) })) {
                val style = CompactPosterCardStyle(maxWidth, maxWidth * (136f / 96f), 12.dp)
                val metrics = remember(maxWidth) { contentCardBadgeMetricsFor(maxWidth) }
                val percent = progressOf(content)?.takeIf { ReadingProgress.isValid(it) }
                // Android's compact grid lifts the bottom badges clear of the title on the scrim.
                val bottomLift = (style.posterHeight.value * 0.28f).dp.coerceIn(32.dp, 44.dp)
                TabletPosterCover(content.title, style, rimBorderBrush = rimBorderBrush,
                    modifier = Modifier.fillMaxWidth().aspectRatio(96f / 136f),
                    cover = { DesktopCover(content, covers, Modifier.matchParentSize()) },
                    overlays = {
                        if (isSelected) Surface(Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 5.dp)
                            .size(22.dp).testTag("content-selected:${content.id}"),
                            shape = RoundedCornerShape(percent = 50), color = MaterialTheme.colorScheme.primary) {
                            Icon(painterResource("icons/ic_check.svg"), null,
                                tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(3.dp))
                        }
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

/**
 * Android's LIST mode: a thumbnail, the title, one supporting line and the progress bar. Rows keep the card's
 * `content:<id>` tag and the same click/long-press semantics, so selection and navigation stay unchanged.
 */
@Composable
private fun ContentRowList(contents: List<SourceContent>, enabled: Boolean, covers: DesktopCovers,
    onOpen: (SourceContent) -> Unit, progressOf: (SourceContent) -> Float?, newChaptersOf: (SourceContent) -> Int,
    pinnedOf: (SourceContent) -> Boolean,
    selectedIds: Set<Long>?, onToggleSelection: (SourceContent) -> Unit, metaOf: (SourceContent) -> String) {
    val isIosStyle = LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS
    val rimBorderBrush = rememberCoverRimBorderBrush(isIosStyle, isDark = LocalDesktopDark.current)
    LazyColumn(Modifier.fillMaxSize().testTag("content-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(contents, key = { it.id }) { content ->
            val selecting = selectedIds != null
            val isSelected = selectedIds?.contains(content.id) == true
            val percent = progressOf(content)?.takeIf { ReadingProgress.isValid(it) }
            val newChapters = newChaptersOf(content)
            Surface(modifier = Modifier.fillMaxWidth().testTag("content:${content.id}")
                .combinedClickable(enabled = enabled,
                    onClick = { if (selecting) onToggleSelection(content) else onOpen(content) },
                    onLongClick = { onToggleSelection(content) }),
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TabletPosterCover(content.title, CompactPosterCardStyle(64.dp, 88.dp, 10.dp),
                        rimBorderBrush = rimBorderBrush, modifier = Modifier.width(64.dp).height(88.dp),
                        cover = { DesktopCover(content, covers, Modifier.matchParentSize()) })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isSelected) Icon(painterResource("icons/ic_check.svg"), null, tint = Accent,
                                modifier = Modifier.size(18.dp).testTag("content-selected:${content.id}"))
                            if (pinnedOf(content)) Icon(painterResource("icons/ic_pin.svg"),
                                desktopText("library.pinned"), tint = Accent,
                                modifier = Modifier.size(16.dp).testTag("content-pinned:${content.id}"))
                            Text(content.title, fontWeight = FontWeight.SemiBold, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (newChapters > 0) Text(desktopText("library.newChapters", newChapters),
                                fontSize = 12.sp, color = Accent,
                                modifier = Modifier.testTag("content-new-chapters:${content.id}"))
                        }
                        val meta = metaOf(content)
                        if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = Muted, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (percent != null) ContentCardBottomProgressBar(percent, ReadingProgress.isCompleted(percent),
                            Modifier.fillMaxWidth().testTag("content-progress:${content.id}"))
                    }
                }
            }
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
