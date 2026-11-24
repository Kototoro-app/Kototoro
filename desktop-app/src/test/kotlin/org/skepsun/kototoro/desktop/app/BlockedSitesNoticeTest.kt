package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class BlockedSitesNoticeTest {
    @Test
    fun `corner pill opens dialog to verify dismiss one or ignore all blocked sites`() =
        runDesktopComposeUiTest(width = 720, height = 560) {
            var sites by mutableStateOf(
                listOf(
                    "https://alpha.example.org/",
                    "https://beta.example.org/",
                    "https://gamma.example.org/",
                    "https://delta.example.org/",
                ),
            )
            var dialogOpen by mutableStateOf(false)
            val verified = mutableListOf<String>()
            setContent {
                DesktopTheme {
                    Box(Modifier.fillMaxSize()) {
                        if (sites.isNotEmpty()) {
                            BlockedSitesCornerPill(
                                count = sites.size,
                                onClick = { dialogOpen = true },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                            )
                        }
                        if (dialogOpen && sites.isNotEmpty()) {
                            BlockedSitesDialog(
                                sites = sites,
                                enabled = true,
                                onVerify = { site ->
                                    dialogOpen = false
                                    verified.add(site)
                                },
                                onDismissSite = { site -> sites = sites - site },
                                onDismissAll = {
                                    dialogOpen = false
                                    sites = emptyList()
                                },
                                onClose = { dialogOpen = false },
                            )
                        }
                    }
                }
            }

            onNodeWithTag("challenge-blocked").assertIsDisplayed()
            onNodeWithText("4").assertIsDisplayed()
            onNodeWithTag("challenge-blocked-dialog").assertDoesNotExist()

            onNodeWithTag("challenge-blocked").performClick()
            onNodeWithTag("challenge-blocked-dialog").assertIsDisplayed()
            onNodeWithText("alpha.example.org").assertIsDisplayed()
            onNodeWithText("delta.example.org").assertIsDisplayed()

            onNodeWithTag("challenge-blocked-dismiss:https://alpha.example.org/").performClick()
            onNodeWithText("alpha.example.org").assertDoesNotExist()
            onNodeWithText("3").assertIsDisplayed()

            onNodeWithTag("challenge-blocked-verify:https://beta.example.org/").performClick()
            onNodeWithTag("challenge-blocked-dialog").assertDoesNotExist()
            runOnIdle { assertEquals(listOf("https://beta.example.org/"), verified) }

            onNodeWithTag("challenge-blocked").performClick()
            onNodeWithTag("challenge-blocked-dismiss-all").performClick()
            onNodeWithTag("challenge-blocked-dialog").assertDoesNotExist()
            onNodeWithTag("challenge-blocked").assertDoesNotExist()
        }
}
