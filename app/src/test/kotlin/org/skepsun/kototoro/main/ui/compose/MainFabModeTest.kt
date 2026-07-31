package org.skepsun.kototoro.main.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

class MainFabModeTest {

    @Test
    fun `continue reading is used when available`() {
        assertEquals(
            MainFabMode.CONTINUE_READING,
            resolveMainFabMode(resumeEnabled = true),
        )
    }

    @Test
    fun `fab is hidden when neither action is available`() {
        assertEquals(
            MainFabMode.HIDDEN,
            resolveMainFabMode(resumeEnabled = false),
        )
    }

    @Test
    fun `video resume uses play action`() {
        assertEquals(
            MainResumeAction.PLAY,
            resolveMainResumeAction(
                contentType = ContentType.VIDEO,
                looksLikeVideoContent = false,
            ),
        )
        assertEquals(
            MainResumeAction.PLAY,
            resolveMainResumeAction(
                contentType = ContentType.HENTAI_VIDEO,
                looksLikeVideoContent = false,
            ),
        )
    }

    @Test
    fun `local video heuristic uses play action when source type is stale`() {
        assertEquals(
            MainResumeAction.PLAY,
            resolveMainResumeAction(
                contentType = ContentType.MANGA,
                looksLikeVideoContent = true,
            ),
        )
    }

    @Test
    fun `non video resume uses read action`() {
        assertEquals(
            MainResumeAction.READ,
            resolveMainResumeAction(
                contentType = ContentType.NOVEL,
                looksLikeVideoContent = false,
            ),
        )
    }
}
