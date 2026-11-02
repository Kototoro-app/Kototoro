package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.desktop.runtime.DesktopSourceSettings

/**
 * Android tints the chips of the tags its warn-list calls dangerous (`ContentListMapper.tagTint`). The details page
 * must mark exactly the same chips, and the switch has to stop it without dropping the tag from the list.
 */
class DesktopTagWarningsUiTest {

    private val source = SourceRef("MIHON_1", "en", "MANGA")

    private fun tag(title: String) = SourceTag(title, title.lowercase(), source)

    private val content = SourceContent(
        id = 7,
        title = "作品",
        altTitles = emptySet(),
        url = "/work",
        publicUrl = "/work",
        rating = 0f,
        contentRating = null,
        coverUrl = null,
        tags = setOf(tag("Rape"), tag("Гуро"), tag("Drama")),
        state = null,
        authors = emptySet(),
        source = source,
        chapters = null,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the shared warn-list tints its chips and leaves the rest alone`() = runDesktopComposeUiTest {
        setContent {
            DesktopTheme { DesktopDetailsInfo(content, enabled = true, warnings = DesktopSourceSettings()) }
        }
        onNodeWithTag("details-tag:Rape").assertIsDisplayed().assertWarned(true)
        // The Russian alias lives in the same shared list.
        onNodeWithTag("details-tag:Гуро").assertWarned(true)
        // A tag the list does not know keeps the accent chip.
        onNodeWithTag("details-tag:Drama").assertWarned(false)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `turning the switch off only hides the tint`() = runDesktopComposeUiTest {
        setContent {
            DesktopTheme {
                DesktopDetailsInfo(content, enabled = true, warnings = DesktopSourceSettings(tagsWarnings = false))
            }
        }
        onNodeWithTag("details-tag:Rape").assertIsDisplayed().assertWarned(false)
        onNodeWithTag("details-tag:Гуро").assertWarned(false)
    }

    private fun SemanticsNodeInteraction.assertWarned(expected: Boolean) {
        assertEquals(expected, fetchSemanticsNode().config.getOrNull(DesktopWarnedTagKey) ?: false, "warned flag")
    }
}
