package org.skepsun.kototoro.migration

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.ContentTypeFamily
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.migration.ui.config.FamilySources
import org.skepsun.kototoro.migration.ui.config.MigrationConfigPages
import org.skepsun.kototoro.migration.ui.config.withPreset
import org.skepsun.kototoro.R
import org.skepsun.kototoro.migration.ui.migrationTitle
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

/** Uses the real page transition and source rows; selection is isolated from the user's migration settings. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class MigrationSourcePickerScrollTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var sourcesRepository: ContentSourcesRepository

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun togglingSourceKeepsTheScrolledRowInPlace() {
        val sources = List(48) { FixtureSource("Source ${it.toString().padStart(2, '0')}") }
        withPicker(sources) { titles, selection ->
            waitForBounds(titles.getValue(sources.first().name))
            repeat(2) {
                val visible = waitForVisibleRows(titles)
                val first = visible.first().second
                val last = visible.last().second
                shell("input swipe ${first.centerX()} ${last.centerY()} ${first.centerX()} ${first.centerY()} 600")
            }
            val visible = waitForVisibleRows(titles)
            val (chosen, _) = visible[visible.size / 2]
            assertTrue("The regression must start away from the first rows",
                sources.indexOfFirst { it.name == chosen } > 8)
            val before = waitForBounds(titles.getValue(chosen))
            repeat(2) { attempt ->
                shell("input tap ${before.centerX()} ${before.centerY()}")
                val expected = if (attempt == 0) listOf(chosen) else emptyList()
                val deadline = SystemClock.uptimeMillis() + 3_000
                while (selection.get() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                assertEquals(expected, selection.get())
                val after = waitForBounds(titles.getValue(chosen))
                assertEquals("Selecting a source must keep its row at the same vertical position",
                    before.top, after.top)
                assertEquals("Selecting a source must not change its row height", before.bottom, after.bottom)
            }
        }
    }

    @Test
    fun filteredBulkActionsKeepHiddenSelectionsAndLanguageFiltersWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sources = groupedSources()
        withPicker(sources, listOf("Alpha 00")) { titles, selection ->
            tap(context.getString(R.string.search_sources))
            shell("input text Beta")
            shell("input keyevent 4")
            waitForBounds(titles.getValue("Beta 00"))
            tap(context.getString(R.string.migration_select_visible))
            waitForSelection(selection,
                listOf("Alpha 00") + sources.filter { it.name.startsWith("Beta") }.map { it.name })
            tap(context.getString(R.string.migration_clear_visible))
            waitForSelection(selection, listOf("Alpha 00"))
            tap(context.getString(R.string.reset_filter))
            tap(context.getString(R.string.more_filters))
            tap(context.getString(R.string.language) + ": " + context.getString(R.string.all_languages))
            tap(java.util.Locale.forLanguageTag("zh").getDisplayName(java.util.Locale.getDefault()))
            waitForBounds(titles.getValue("漫画 00"))
            assertEquals(listOf("Alpha 00"), selection.get())
            capture("migration-picker-language.png")
        }
    }

    @Test
    fun alphabetNavigationAndScrollbarMoveThroughALongList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withPicker(groupedSources()) { titles, _ ->
            waitForBounds(titles.getValue("Alpha 00"))
            capture("migration-picker-default.png")
            tap(context.getString(R.string.sort_by_name_label))
            tap("G")
            waitForBounds(titles.getValue("Gamma 00"))
            val track = waitForBounds(context.getString(R.string.migration_sources_fast_scroll))
            val topInset = context.resources.getDimensionPixelSize(R.dimen.fastscroll_scrollbar_margin_top)
            val bottomInset = context.resources.getDimensionPixelSize(R.dimen.fastscroll_scrollbar_margin_bottom)
            val handleHeight = context.resources.getDimensionPixelSize(R.dimen.fastscroll_handle_height)
            val start = track.top + topInset + handleHeight / 2
            val end = track.bottom - bottomInset - 8
            shell("input swipe ${track.centerX()} $start ${track.centerX()} $end 600")
            val visible = waitForVisibleRows(titles)
            capture("migration-picker-alphabet.png")
            assertTrue("Dragging $track must reach the final name group; visible=$visible",
                visible.any { it.first.startsWith("漫画") })
        }
    }

    private fun groupedSources() = listOf("Alpha", "Beta", "Gamma", "Delta", "漫画").flatMap { group ->
        List(10) { FixtureSource("$group ${it.toString().padStart(2, '0')}", if (group == "漫画") "zh" else "en") }
    }

    private fun withPicker(
        sources: List<FixtureSource>,
        initiallySelected: List<String> = emptyList(),
        block: (Map<String, String>, AtomicReference<List<String>>) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val titles = sources.associate { it.name to it.migrationTitle(context) }
        val selection = AtomicReference(initiallySelected)
        ActivityScenario.launch<IdleProbeActivity>(
            Intent(context, IdleProbeActivity::class.java).putExtra("scene_recovery", true),
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        var family by remember {
                            mutableStateOf(FamilySources(ContentTypeFamily.MANGA, sources,
                                setOf("Alpha 00", "Beta 00"), initiallySelected))
                        }
                        CompositionLocalProvider(LocalInterfaceStyle provides InterfaceStyle.MATERIAL_3_EXPRESSIVE) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                MigrationConfigPages(
                                    family = family,
                                    onToggle = { _, name ->
                                        family = family.copy(selected = if (name in family.selected) {
                                            family.selected - name
                                        } else family.selected + name)
                                        selection.set(family.selected)
                                    },
                                    onPreset = { _, preset, names ->
                                        family = family.withPreset(preset, names)
                                        selection.set(family.selected)
                                    }, onDone = {},
                                ) { Text("Configuration") }
                            }
                        }
                    }
                }
            }
            block(titles, selection)
        }
    }

    private fun waitForSelection(selection: AtomicReference<List<String>>, expected: List<String>) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (selection.get() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, selection.get())
    }

    private fun tap(text: String) {
        val bounds = waitForBounds(text)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    private fun capture(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(directory, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun visibleRows(titles: Map<String, String>): List<Pair<String, Rect>> {
        val root = checkNotNull(freshRoot())
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        // Keep gestures clear of the edge-to-edge system navigation region.
        val window = Rect(0, 100, metrics.widthPixels, metrics.heightPixels - 200)
        return titles.mapNotNull { (name, title) ->
            find(root, title)?.takeIf { it.width() > 0 && it.height() > 0 && window.contains(it) }?.let { name to it }
        }
    }

    private fun waitForVisibleRows(titles: Map<String, String>): List<Pair<String, Rect>> {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var previous = emptyList<Pair<String, Rect>>()
        var unchanged = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val rows = visibleRows(titles)
            unchanged = if (rows.size >= 3 && rows == previous) unchanged + 1 else 0
            if (unchanged >= 10) return rows
            previous = rows
            SystemClock.sleep(50)
        }
        capture("migration-picker-failure.png")
        error("Source rows did not settle after scrolling: $previous")
    }

    private fun waitForBounds(text: String): Rect {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var previous: Rect? = null
        var unchanged = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val found = freshRoot()?.let { find(it, text) }
            unchanged = if (found != null && found == previous) unchanged + 1 else 0
            if (unchanged >= 10) return checkNotNull(found)
            previous = found
            SystemClock.sleep(50)
        }
        error("Scrolled source disappeared after selection: $text")
    }

    private fun find(node: AccessibilityNodeInfo, text: String): Rect? {
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> find(child, text)?.let { return it } }
        }
        if (node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(text) == true ||
                node.contentDescription?.toString() == text)) {
            return Rect().also { node.getBoundsInScreen(it) }
        }
        return null
    }

    private fun freshRoot(): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        return automation.rootInActiveWindow
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { it.readBytes() }
    }
}

private data class FixtureSource(override val name: String, override val locale: String = "en") : ContentSource {
    override val contentType = ContentType.MANGA
}
