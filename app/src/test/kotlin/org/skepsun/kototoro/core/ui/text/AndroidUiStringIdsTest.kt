package org.skepsun.kototoro.core.ui.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.R

class AndroidUiStringIdsTest {

    @Test
    fun `maps shared wizard and settings string keys to R string resources`() {
        assertEquals(R.string.welcome_intro_app_title, AndroidUiStringIds.stringId("welcome_intro_app_title"))
        assertEquals(R.string.welcome_step_counter, AndroidUiStringIds.stringId("welcome_step_counter"))
        assertEquals(R.string.sync_last, AndroidUiStringIds.stringId("sync_last"))
        assertEquals(R.string.source_lock_locked, AndroidUiStringIds.stringId("source_lock_locked"))
        assertEquals(R.string.replace_rule_mode_regex, AndroidUiStringIds.stringId("replace_rule_mode_regex"))
        assertEquals(R.string.address, AndroidUiStringIds.stringId("address"))
        assertEquals(R.string.source_test_status_pass, AndroidUiStringIds.stringId("source_test_status_pass"))
        assertEquals(R.string.stats_duration_minutes, AndroidUiStringIds.stringId("stats_duration_minutes"))
        assertEquals(R.string.content_type_manga, AndroidUiStringIds.stringId("content_type_manga"))
        assertNotEquals(0, AndroidUiStringIds.stringId("welcome_intro_disclaimer"))
    }

    @Test
    fun `returns zero for framework or unknown keys`() {
        assertEquals(0, AndroidUiStringIds.stringId("ok"))
        assertEquals(0, AndroidUiStringIds.stringId("missing_shared_ui_key"))
        assertEquals(0, AndroidUiStringIds.arrayId("missing_shared_ui_array"))
    }
}
