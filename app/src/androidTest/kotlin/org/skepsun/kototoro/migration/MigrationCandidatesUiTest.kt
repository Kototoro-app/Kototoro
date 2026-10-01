package org.skepsun.kototoro.migration

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.dev.IdleProbeActivity
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.migration.domain.MatchCandidate
import org.skepsun.kototoro.migration.ui.migrationTitle
import org.skepsun.kototoro.migration.ui.list.MigrationCandidatesSheet
import org.skepsun.kototoro.migration.ui.list.MigrationItemState
import org.skepsun.kototoro.migration.ui.list.MigrationItemStatus
import org.skepsun.kototoro.migration.ui.list.MigrationListScreen
import org.skepsun.kototoro.migration.ui.list.MigrationListState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

/** Uses production migration UI with isolated fixtures and callbacks; never migrates the user's library. */
@RunWith(AndroidJUnit4::class)
class MigrationCandidatesUiTest {

    @Test
    fun inlineCandidatesShowCountsAndOpenDetailsWithoutSelecting() = verifyCandidates(sheet = false)

    @Test
    fun fullCandidateSheetShowsCountsAndOpenDetailsWithoutSelecting() = verifyCandidates(sheet = true)

    private fun verifyCandidates(sheet: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val selected = AtomicLong(-1)
        val opened = AtomicLong(-1)
        val loads = AtomicInteger(0)
        val loadedId = AtomicLong(-1)
        val known = content(2, "Known candidate").copy(chapters = List(5) { index ->
            ContentChapter(
                id = 100L + index, title = "Chapter $index", number = index.toFloat(), volume = 0,
                url = "/chapter/$index", scanlator = null, uploadDate = 0,
                branch = if (index < 2) "A" else "B", source = TestContentSource,
            )
        })
        val item = MigrationItemState(
            origin = content(1, "Original work"), originChapters = 2,
            status = MigrationItemStatus.MATCHED, target = known, targetChapters = 3,
            candidates = listOf(
                MatchCandidate(known.copy(chapters = null), 1.0),
                MatchCandidate(content(3, "Unknown candidate"), 0.9),
                MatchCandidate(content(4, "Empty candidate").copy(chapters = emptyList()), 0.8),
            ),
        )
        val load: (MatchCandidate) -> Unit = { loads.incrementAndGet(); loadedId.set(it.content.id) }
        ActivityScenario.launch<IdleProbeActivity>(
            Intent(context, IdleProbeActivity::class.java).putExtra("scene_recovery", true),
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalInterfaceStyle provides InterfaceStyle.MATERIAL_3_EXPRESSIVE) {
                            if (sheet) {
                                MigrationCandidatesSheet(
                                    item = item, onSelect = { selected.set(it.content.id) }, onSearch = {},
                                    onLoadDetails = load, onOpen = { opened.set(it.id) }, onDismiss = {},
                                )
                            } else {
                                MigrationListScreen(
                                    state = MigrationListState(items = listOf(item), isLoading = false),
                                    onNavigateUp = {}, onFilter = {}, onSkip = {},
                                    onSelectCandidate = { _, candidate -> selected.set(candidate.content.id) },
                                    onManualSearch = { _, _ -> }, onMigrateNow = {}, onOpenOriginal = {},
                                    onOpenCandidate = { opened.set(it.id) },
                                    onLoadCandidateDetails = { _, id -> load(item.candidates.first { it.content.id == id }) },
                                    onRequestMigrate = {}, onConfirmMigrate = {}, onCancelMigrate = {},
                                    onDismissDialog = {}, onAbandon = {},
                                )
                            }
                        }
                    }
                }
            }
            val source = TestContentSource.migrationTitle(context)
            // Alternate translations are counted per branch, not as five distinct chapters.
            waitForBounds(context.getString(R.string.migration_chapters, source, 3))
            waitForBounds("$source · ${context.getString(R.string.unknown)}")
            waitForBounds(context.getString(R.string.migration_chapters, source, 0))
            assertEquals(1, loads.get())
            assertEquals(3L, loadedId.get())
            val info = waitForBounds(context.getString(R.string.details), description = true)
            if (InstrumentationRegistry.getArguments().getString("migrationEvidence") == "true") {
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(context.cacheDir, "migration-candidates-${if (sheet) "sheet" else "inline"}.png")
                        .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { screenshot.recycle() }
            }
            tap(info)
            awaitValue(opened, 2)
            assertEquals("Opening candidate details must not select it for migration", -1L, selected.get())
            tap(waitForBounds("Unknown candidate"))
            awaitValue(selected, 3)
        }
    }

    private fun tap(bounds: Rect) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "input tap ${bounds.centerX()} ${bounds.centerY()}",
        ).use { it.close() }
    }

    private fun awaitValue(value: AtomicLong, expected: Long) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (value.get() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, value.get())
    }

    private fun waitForBounds(text: String, description: Boolean = false): Rect {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        var previous: Rect? = null
        var unchanged = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val found = automation.rootInActiveWindow?.let { find(it, text, description) }
                ?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
            unchanged = if (found != null && found == previous) unchanged + 1 else 0
            if (unchanged >= 6) return checkNotNull(found)
            previous = found
            SystemClock.sleep(50)
        }
        error("Visible control did not settle: $text")
    }

    private fun find(node: AccessibilityNodeInfo, text: String, description: Boolean): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && (if (description) node.contentDescription else node.text)?.toString() == text) {
            return node
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> find(child, text, description)?.let { return it } }
        }
        return null
    }

    private fun content(id: Long, title: String) = Content(
        id = id, title = title, altTitles = emptySet(), url = "/$id", publicUrl = "", rating = -1f,
        contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
        source = TestContentSource,
    )
}
