package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.community.domain.CommunityRatingRefusal
import org.skepsun.kototoro.community.domain.CommunityRatingSummary
import org.skepsun.kototoro.community.domain.CommunitySimilarWork
import org.skepsun.kototoro.community.domain.CommunityThread
import org.skepsun.kototoro.community.ui.CommunityCommentRow
import org.skepsun.kototoro.community.ui.CommunityCommentsContent
import org.skepsun.kototoro.community.ui.CommunityCommentsViewState
import org.skepsun.kototoro.community.ui.CommunityDialog
import org.skepsun.kototoro.community.ui.CommunityDialogActions
import org.skepsun.kototoro.community.ui.CommunityRatingCard
import org.skepsun.kototoro.community.ui.CommunityRatingUiState
import org.skepsun.kototoro.community.ui.CommunitySettingsDialogs
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@OptIn(ExperimentalTestApi::class)
class DesktopCommunityViewsTest {
    private val rated = CommunityRatingUiState(
        isLoading = false,
        rating = CommunityRatingSummary(score = 8.4, mean = 8.6, count = 37, weight = 30.0,
            distribution = listOf(0, 0, 1, 0, 2, 3, 6, 9, 8, 8)),
        myStars = null,
        comments = 5,
        similar = listOf(CommunitySimilarWork("g1", "葬送的芙莉莲", 9.1, coRaters = 12)),
        refusal = null,
        isPublishing = false,
    )

    @Test
    fun `the rating card picks a score in the dialog and opens comments`() = runDesktopComposeUiTest(width = 720, height = 900) {
        var rated by mutableStateOf<Int?>(null)
        var opened = false
        var searched: String? = null
        setContent {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        CommunityRatingCard(
                            state = this@DesktopCommunityViewsTest.rated.copy(myStars = rated),
                            onRate = { rated = it },
                            onWithdraw = { rated = null },
                            onOpenComments = { opened = true },
                            onSearchTitle = { searched = it },
                            container = { card ->
                                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) { card() }
                            },
                        )
                    }
                }
            }
        }
        onNodeWithTag("community-rating-score").assertExists()
        capture("rating-card")
        onNodeWithTag("community-rating-rate").performClick()
        // The right half of the fourth star is eight of ten; its left half would be seven.
        onNodeWithTag("community-rating-star-8").performTouchInput { click(Offset(width * .9f, centerY)) }
        capture("rating-dialog")
        onNodeWithTag("community-rating-confirm").performClick()
        runOnIdle { assertEquals(8, rated) }
        onNodeWithTag("community-comments-open").performClick()
        runOnIdle { assertEquals(true, opened) }
        onNodeWithTag("community-similar-item").performClick()
        runOnIdle { assertEquals("葬送的芙莉莲", searched) }
    }

    @Test
    fun `a refused reader sees why instead of the rate button`() = runDesktopComposeUiTest(width = 720, height = 600) {
        setContent {
            DesktopTheme {
                CommunityRatingCard(rated.copy(refusal = CommunityRatingRefusal.NO_IDENTITY), {}, {}, {}, {}, { it() })
            }
        }
        onNodeWithTag("community-rating-rate").assertDoesNotExist()
        onNodeWithText(AndroidStrings["community_rating_no_identity"]).assertExists()
    }

    @Test
    fun `comments list threads and post with the spoiler flag`() = runDesktopComposeUiTest(width = 640, height = 640) {
        val now = Instant.now().epochSecond
        val root = CommunityCommentRow("a", "11", "Reader", "第一卷很好看", now - 3_600, false, isMine = false, isPending = false)
        val reply = CommunityCommentRow("b", "22", "Me", "同意", now, true, isMine = true, isPending = true)
        var posted: Pair<String, Boolean>? = null
        var replyTo by mutableStateOf<CommunityCommentRow?>(null)
        setContent {
            DesktopTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    CommunityCommentsContent(
                        state = CommunityCommentsViewState(
                            isLoading = false,
                            threads = listOf(CommunityThread(root, listOf(reply))),
                            relaysAnswered = 2,
                            replyTo = replyTo,
                        ),
                        formatTime = { desktopRelativeTime(it) },
                        onRefresh = {},
                        onReplyTo = { replyTo = it },
                        onPost = { text, spoiler -> posted = text to spoiler },
                        onDelete = {},
                        onReport = { _, _ -> },
                        onBlock = {},
                        modifier = Modifier.fillMaxSize().padding(top = 20.dp),
                    )
                }
            }
        }
        onNodeWithText("第一卷很好看").assertExists()
        // A spoiler stays covered until it is asked for.
        onNodeWithText("同意").assertDoesNotExist()
        capture("comments")
        onAllNodesWithText(AndroidStrings["community_comment_reply"])[0].performClick()
        onNodeWithText(AndroidStrings.format("community_comment_reply_to", "Reader")).assertExists()
        onNodeWithTag("community-comment-input").performTextInput("说得对")
        onNodeWithText(AndroidStrings["community_comment_spoiler"]).performClick()
        onNodeWithTag("community-comment-send").performClick()
        runOnIdle { assertEquals("说得对" to true, posted) }
    }

    @Test
    fun `onboarding offers a new identity or a recovery key`() = runDesktopComposeUiTest(width = 720, height = 600) {
        var dialog by mutableStateOf<CommunityDialog?>(CommunityDialog.Onboarding)
        var created = false
        var restored: String? = null
        val actions = object : CommunityDialogActions {
            override fun dismiss() { dialog = null }
            override fun createIdentity() { created = true; dialog = CommunityDialog.RecoveryKey("nsec1example") }
            override fun startRestore() { dialog = CommunityDialog.Restore() }
            override fun restore(recoveryKey: String) { restored = recoveryKey; dialog = CommunityDialog.Restore(invalid = true) }
            override fun saveNickname(nickname: String) = Unit
            override fun copyRecoveryKey(key: String) = Unit
        }
        setContent { DesktopTheme { Column { Spacer(Modifier.height(1.dp)); CommunitySettingsDialogs(dialog, actions) } } }
        onNodeWithTag("community-onboarding-create").performClick()
        runOnIdle { assertEquals(true, created) }
        onNodeWithTag("community-recovery-key").assertTextEquals("nsec1example")
        runOnIdle { dialog = CommunityDialog.Onboarding }
        onNodeWithTag("community-onboarding-restore").performClick()
        onNodeWithTag("community-restore-input").performTextInput("nsec1wrong")
        onNodeWithTag("community-restore-confirm").performClick()
        runOnIdle { assertEquals("nsec1wrong", restored) }
        onNodeWithText(AndroidStrings["community_restore_invalid"]).assertExists()
    }

    @Test
    fun `relative times read like Android's`() {
        val now = Instant.parse("2026-11-21T12:00:00Z")
        assertEquals(desktopText("feed.timeNow"), desktopRelativeTime(now.epochSecond - 20, now))
        assertEquals(desktopText("feed.timeMinutesAgo", 5L), desktopRelativeTime(now.epochSecond - 300, now))
        assertEquals(desktopText("feed.timeHoursAgo", 3L), desktopRelativeTime(now.epochSecond - 3 * 3_600, now))
        assertEquals(desktopText("feed.timeDaysAgo", 2L), desktopRelativeTime(now.epochSecond - 2 * 86_400, now))
    }

    private fun ComposeUiTest.capture(name: String) {
        val report = Files.createDirectories(Path.of("build/reports/community")).resolve("$name.png")
        Image.makeFromBitmap(onAllNodes(isRoot()).onFirst().captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData()?.use { Files.write(report, it.bytes) }
        }
    }
}
