package org.skepsun.kototoro.home.ui.compose.sections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.compactPosterCardStyle

class HomeRailStyleTest {

    private fun style(listMode: ListMode, rows: Int = 2) = HomeRailStyle(
        listMode = listMode,
        posterStyle = compactPosterCardStyle(1f),
        railRowsPerPage = rows,
    )

    @Test
    fun `default rail rows is two`() {
        assertEquals(2, style(ListMode.GRID).railRowsPerPage)
    }

    @Test
    fun `rail rows accept the configured one to three range`() {
        assertEquals(1, style(ListMode.LIST, rows = 1).railRowsPerPage)
        assertEquals(2, style(ListMode.LIST, rows = 2).railRowsPerPage)
        assertEquals(3, style(ListMode.DETAILED_LIST, rows = 3).railRowsPerPage)
    }

    @Test
    fun `rail rows reject out of range values`() {
        assertThrows(IllegalArgumentException::class.java) { style(ListMode.LIST, rows = 0) }
        assertThrows(IllegalArgumentException::class.java) { style(ListMode.LIST, rows = 4) }
    }

    @Test
    fun `list rail page size helper stays in sync with the rows bounds`() {
        // The sheet builds its selector from the same bounds the rail uses.
        assertEquals(1, org.skepsun.kototoro.list.ui.compose.HOME_LIST_RAIL_ROWS_MIN)
        assertEquals(3, org.skepsun.kototoro.list.ui.compose.HOME_LIST_RAIL_ROWS_MAX)
    }
}
