package org.skepsun.kototoro.details.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterPanelModeTest {

    @Test
    fun `opening before or after metadata loads selects the bound reading source`() {
        for (hasMetadata in listOf(false, true)) {
            assertEquals(
                ChapterPanelMode.READING,
                resolveChapterPanelMode(null, hasReadingSource = true, hasMetadataSource = hasMetadata),
            )
        }
    }

    @Test
    fun `tracking only work opens metadata after its sources arrive`() {
        assertEquals(
            ChapterPanelMode.READING,
            resolveChapterPanelMode(null, hasReadingSource = false, hasMetadataSource = false),
        )
        assertEquals(
            ChapterPanelMode.METADATA,
            resolveChapterPanelMode(null, hasReadingSource = false, hasMetadataSource = true),
        )
    }

    @Test
    fun `user can select metadata on a bound title or look for a reading source on a tracking title`() {
        assertEquals(
            ChapterPanelMode.METADATA,
            resolveChapterPanelMode(ChapterPanelMode.METADATA.name, hasReadingSource = true, hasMetadataSource = true),
        )
        assertEquals(
            ChapterPanelMode.READING,
            resolveChapterPanelMode(ChapterPanelMode.READING.name, hasReadingSource = false, hasMetadataSource = true),
        )
        assertEquals(
            ChapterPanelMode.READING,
            resolveChapterPanelMode(ChapterPanelMode.METADATA.name, hasReadingSource = true, hasMetadataSource = false),
        )
    }
}
