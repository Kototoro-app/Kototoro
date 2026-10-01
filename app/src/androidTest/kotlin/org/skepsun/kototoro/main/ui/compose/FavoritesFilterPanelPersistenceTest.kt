package org.skepsun.kototoro.main.ui.compose

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.TopBarStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.glass.GlassPrefs
import org.skepsun.kototoro.core.ui.glass.LocalGlassPrefs
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.favourites.ui.compose.FavoritesFilterPanelContent
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.list.ui.model.QuickFilterGroup
import org.skepsun.kototoro.main.ui.MainChromeController
import org.skepsun.kototoro.main.ui.SearchBarFilterCallback
import org.skepsun.kototoro.search.domain.ContentSearchRepository
import org.skepsun.kototoro.search.ui.suggestion.SearchSuggestionViewModel
import org.skepsun.kototoro.tracking.discovery.domain.PreferredTrackingSiteProvider
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService

/** Exercises real nested filter menus while list-derived options change, without changing the user's library. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class FavoritesFilterPanelPersistenceTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var searchRepository: ContentSearchRepository

    @Inject
    lateinit var sourcesRepository: ContentSourcesRepository

    @Inject
    lateinit var presetsRepository: SourcePresetsRepository

    @Inject
    lateinit var sourceTypeIdentifier: SourceTypeIdentifier

    @Inject
    lateinit var discoveryService: TrackingSiteDiscoveryService

    @Inject
    lateinit var preferredSiteProvider: PreferredTrackingSiteProvider

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun iosFilterStaysOpenWhenSourceSelectionChangesListOptions() = verifyPanel(InterfaceStyle.IOS)

    @Test
    fun materialFilterStaysOpenWhenSourceSelectionChangesListOptions() =
        verifyPanel(InterfaceStyle.MATERIAL_3_EXPRESSIVE)

    private fun verifyPanel(style: InterfaceStyle) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selection = AtomicReference<Set<SourceTag>>(emptySet())
        val entries = SourceTag.quickFilterEntries
        val populatedFilter = QuickFilter(
            items = emptyList(),
            groups = listOf(QuickFilterGroup(
                key = "publication", titleResId = R.string.status_completed, iconResId = R.drawable.ic_state_finished,
                items = listOf(ChipModel(
                    titleResId = R.string.status_completed,
                    icon = R.drawable.ic_state_finished,
                    data = ListFilterOption.Macro.COMPLETED,
                )),
            )),
        )
        ActivityScenario.launch<IdleProbeActivity>(
            Intent(context, IdleProbeActivity::class.java).putExtra("scene_recovery", true),
        ).use { scenario ->
            scenario.onActivity { activity ->
                val preferencesName = "filter-panel-fixture-${UUID.randomUUID()}"
                val settings = AppSettings(object : ContextWrapper(context) {
                    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                        super.getSharedPreferences("$preferencesName-$name", Context.MODE_PRIVATE)
                })
                val suggestions = SearchSuggestionViewModel(
                    searchRepository, settings, sourcesRepository, presetsRepository,
                    sourceTypeIdentifier, GlobalFavoritesState(settings), discoveryService, preferredSiteProvider,
                )
                activity.viewModelStore.put("filter-panel-fixture", suggestions)
                val chrome = MainChromeController(settings, suggestions) { activity.window.decorView }
                activity.setContent {
                    MaterialTheme {
                        val host = remember { RootGlassMenuHost() }
                        val backdrop = rememberLayerBackdrop { drawContent() }
                        var selected by remember { mutableStateOf(emptySet<SourceTag>()) }
                        val select: (SourceTag?) -> Unit = { tag ->
                            selected = if (tag == null) emptySet() else selected.toMutableSet().apply {
                                if (!add(tag)) remove(tag)
                            }
                            selection.set(selected)
                        }
                        val panel: @Composable (close: () -> Unit) -> Unit = { close ->
                            FavoritesFilterPanelContent(
                                quickFilter = if (selected.isEmpty()) populatedFilter else QuickFilter(emptyList()),
                                onQuickFilterOptionClick = {}, onResetFilters = { select(null) },
                                selectedSourceTags = selected,
                                sourceTagEntries = entries, enabledSourceTags = entries.toSet(),
                                onSourceTagSelected = select,
                                isInlineQuickFilterEnabled = false, onInlineQuickFilterEnabledChange = {},
                                isShelfEnabled = false, onShelfEnabledChange = {}, close = close,
                            )
                        }
                        // Match the shell's dispose/re-register cycle on every selected-source change.
                        DisposableEffect(selected) {
                            val selectedSnapshot = selected
                            val callback = object : SearchBarFilterCallback {
                                override fun getSelectedContentType() = BrowseGroupTab.All
                                override fun onContentTypeSelected(tab: BrowseGroupTab) = Unit
                                override fun getSelectedSourceTags() = selectedSnapshot
                                override fun onSourceTagSelected(tag: SourceTag?) = select(tag)
                                override fun getSourceTagEntries() = entries
                                override fun getFilterPanelContent(): (@Composable (close: () -> Unit) -> Unit) =
                                    { close -> panel(close) }
                            }
                            chrome.setActiveFilterCallback(callback)
                            onDispose { chrome.clearActiveFilterCallback(callback) }
                        }
                        CompositionLocalProvider(
                            LocalInterfaceStyle provides style,
                            LocalRootGlassMenuHost provides host,
                            LocalLiquidGlassBackdrop provides backdrop,
                            LocalGlassPrefs provides GlassPrefs(true, false, 65),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier.fillMaxSize().layerBackdrop(backdrop)
                                        .background(MaterialTheme.colorScheme.background),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(if (selected.isEmpty()) "Saved work" else "No matching work")
                                }
                                KototoroTopBar(
                                    query = "", titleText = "Library", topBarStyle = TopBarStyle.COMPACT,
                                    selectedSourceTags = chrome.activeFilterSourceTags,
                                    sourceTagEntries = chrome.availableSourceTags,
                                    enabledSourceTags = chrome.enabledSourceTags,
                                    isSourceTagFilterVisible = chrome.isSourceTagFilterVisible,
                                    onSourceTagFilterClick = chrome::onSourceTagFilterClick,
                                    onSourceTagSelected = { chrome.currentFilterCallback?.onSourceTagSelected(it) },
                                    sourceTagCustomMenuContent = chrome.filterPanelContent,
                                )
                                RootGlassMenuOverlay(host)
                            }
                        }
                    }
                }
            }
            tap(waitForBounds(context.getString(R.string.filter), description = true))
            waitForBounds(context.getString(R.string.reset_filter))
            // The first source makes the list empty and removes its quick-filter group.
            // The next source recomposes both the top-bar callback and the open panel again.
            for (tag in listOf(SourceTag.ANIYOMI, SourceTag.CLOUDSTREAM)) {
                val current = selection.get().singleOrNull()
                tap(waitForBounds(context.getString(current?.titleRes ?: R.string.source_type), last = true))
                tap(waitForBounds(context.getString(tag.titleRes)))
                waitForBounds(context.getString(R.string.reset_filter))
                val expected = selection.get()
                assertEquals("The selected source must be applied", true, tag in expected)
            }
            tap(waitForBounds(context.getString(R.string.reset_filter)))
            waitForBounds(context.getString(R.string.status_completed))
            assertEquals(emptySet<SourceTag>(), selection.get())
            waitForBounds(context.getString(R.string.reset_filter))
        }
    }

    private fun tap(bounds: Rect) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "input tap ${bounds.centerX()} ${bounds.centerY()}",
        ).use { it.close() }
    }

    private fun waitForBounds(text: String, description: Boolean = false, last: Boolean = false): Rect {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 5_000
        var previous: Rect? = null
        var unchanged = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val found = automation.rootInActiveWindow?.let { find(it, text, description, last) }
                ?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
            unchanged = if (found != null && found == previous) unchanged + 1 else 0
            if (unchanged >= 6) return checkNotNull(found)
            previous = found
            SystemClock.sleep(50)
        }
        error("Filter control did not remain visible: $text")
    }

    private fun find(
        node: AccessibilityNodeInfo,
        text: String,
        description: Boolean,
        last: Boolean,
    ): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        if (node.isVisibleToUser && (if (description) node.contentDescription else node.text)?.toString() == text) {
            result = node
            if (!last) return result
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                find(child, text, description, last)?.let {
                    result = it
                    if (!last) return result
                }
            }
        }
        return result
    }
}
