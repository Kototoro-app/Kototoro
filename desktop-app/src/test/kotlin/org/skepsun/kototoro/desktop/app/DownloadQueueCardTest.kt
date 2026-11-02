package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueAction
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueBatchItem
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueCard
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueChapterUiModel
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueItemUiModel
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueStatus
import org.skepsun.kototoro.core.ui.downloads.downloadQueueBatchState
import org.skepsun.kototoro.core.ui.downloads.downloadQueueBatchStateOf
import org.skepsun.kototoro.core.ui.downloads.isActive
import org.skepsun.kototoro.core.ui.downloads.toBatchItem
import java.nio.file.Files
import java.nio.file.Path

/**
 * The queue row both hosts draw now: Android's card layout (cover slot, chevron for the chapter list, progress bar,
 * action row) reachable from the desktop's queue, and the one batch rule behind both selection bars.
 */
@OptIn(ExperimentalTestApi::class)
class DownloadQueueCardTest {
    private val chapters = listOf(
        DownloadQueueChapterUiModel("1", "Chapter one", isDownloaded = true),
        DownloadQueueChapterUiModel("2", "Chapter two", isDownloaded = false),
    )

    private fun downloading(
        expanded: Boolean = false,
        selected: Boolean = false,
        percent: Float? = 0.25f,
    ) = DownloadQueueItemUiModel(
        key = "q1",
        title = "Work title",
        subtitle = "Chapter 2 · Branch A",
        status = DownloadQueueStatus.DOWNLOADING,
        statusText = "Downloading",
        infoText = "1.5 MB / 6.0 MB",
        detailText = "in 2 minutes",
        percent = percent,
        isSelected = selected,
        isExpanded = expanded,
        chapters = chapters,
        canPause = true,
        canCancel = true,
    )

    @Test
    fun compressedRowShowsAndroidLayoutAndHidesTheChapterListUntilExpanded() {
        for (width in listOf(380, 760)) runDesktopComposeUiTest(width = width, height = 900) {
            var expanded by mutableStateOf(false)
            var paused = 0
            setContent {
                DesktopTheme {
                    Surface {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            DownloadQueueCard(
                                item = downloading(expanded = expanded, selected = true),
                                onToggleExpanded = { expanded = !expanded },
                                actions = listOf(
                                    DownloadQueueAction("Pause", { paused++ }, tag = "download-pause:q1"),
                                    DownloadQueueAction("Cancel", {}, enabled = false, tag = "download-cancel:q1"),
                                ),
                            )
                        }
                    }
                }
            }
            onNodeWithText("Work title").assertExists()
            onNodeWithText("Chapter 2 · Branch A").assertExists()
            onNodeWithTag("download-status:q1").assertTextEquals("Downloading")
            onNodeWithText("1.5 MB / 6.0 MB").assertExists()
            onNodeWithText("in 2 minutes").assertExists()
            onNodeWithText("25%").assertExists()
            // The chapter list stays folded until the chevron is pressed, exactly like the Android row.
            onNodeWithText("Chapter one").assertDoesNotExist()
            onNodeWithTag("download-expand:q1").performClick()
            waitForIdle()
            assertTrue(expanded)
            onNodeWithText("Chapter one").assertExists()
            onNodeWithText("Chapter two").assertExists()
            // The action row keeps the host's tags and enabled state, so the probes can still find their buttons.
            onNodeWithTag("download-pause:q1").assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(1, paused)
            onNodeWithTag("download-cancel:q1").assertIsNotEnabled()
            val report = Files.createDirectories(Path.of("build/reports/desktop-smoke"))
                .resolve("shared-download-queue-" + width + ".png")
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
        }
    }

    @Test
    fun finishedRowDropsProgressAndChevronWhenTheQueueHasNothingToShow() {
        runDesktopComposeUiTest(width = 600, height = 800) {
            setContent {
                DesktopTheme {
                    Surface {
                        Column {
                            DownloadQueueCard(
                                item = DownloadQueueItemUiModel(
                                    key = "done",
                                    title = "Saved work",
                                    status = DownloadQueueStatus.COMPLETE,
                                    statusText = "Completed",
                                    infoText = "12 / 12 pages",
                                    percent = null,
                                    chapters = emptyList(),
                                ),
                                actions = listOf(DownloadQueueAction("Read offline", {}, filled = true)),
                            )
                        }
                    }
                }
            }
            onNodeWithTag("download-status:done").assertTextEquals("Completed")
            onNodeWithText("12 / 12 pages").assertExists()
            onNodeWithText("Read offline").assertIsEnabled()
            // No chapters and no percent: neither the chevron nor a percentage is drawn.
            onNodeWithTag("download-expand:done").assertDoesNotExist()
            onNodeWithText("0%").assertDoesNotExist()
        }
    }

    @Test
    fun batchStateCountsWhatEachReadingBarNeeds() {
        val mixed = listOf(
            downloading().copy(key = "a"),
            downloading().copy(key = "b", status = DownloadQueueStatus.PAUSED, canPause = false, canResume = true),
            DownloadQueueItemUiModel(
                key = "c", title = "Failed", status = DownloadQueueStatus.FAILED, statusText = "Error",
                canRemove = true,
            ),
        )
        val state = downloadQueueBatchStateOf(mixed)
        assertEquals(3, state.selected)
        // Desktop reads the counts: pause applies to the running row, resume to the paused one, retry to the failed
        // one, and removal to that failed row because the two running ones are not settled yet.
        assertEquals(1, state.pausable)
        assertEquals(1, state.resumable)
        assertEquals(1, state.retryable)
        assertEquals(1, state.removable)
        // Android shows an action only when every selected row supports it, so a mixed selection greys all of them out.
        assertFalse(state.canPause)
        assertFalse(state.canResume)
        assertFalse(state.canRetry)
        assertFalse(state.canRemove)
        assertEquals(mixed.map { it.toBatchItem() },
            listOf(
                DownloadQueueBatchItem(DownloadQueueStatus.DOWNLOADING, true, false, true, false),
                DownloadQueueBatchItem(DownloadQueueStatus.PAUSED, false, true, true, false),
                DownloadQueueBatchItem(DownloadQueueStatus.FAILED, false, false, false, true),
            ),
        )
        // One row: the same flags now agree with the counts.
        val single = downloadQueueBatchState(listOf(mixed.first().toBatchItem()))
        assertTrue(single.canPause)
        assertFalse(single.canResume)
        assertTrue(single.canCancel)
        assertFalse(single.canRemove)
        // Nothing selected: every action is unavailable instead of trivially true.
        assertEquals(0, downloadQueueBatchStateOf(emptyList()).selected)
        assertFalse(downloadQueueBatchStateOf(emptyList()).canPause)
        assertTrue(DownloadQueueStatus.QUEUED.isActive)
        assertTrue(DownloadQueueStatus.DOWNLOADING.isActive)
        assertFalse(DownloadQueueStatus.COMPLETE.isActive)
    }
}
