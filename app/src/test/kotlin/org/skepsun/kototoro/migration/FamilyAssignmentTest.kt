package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.ContentTypeFamily.MANGA
import org.skepsun.kototoro.core.model.ContentTypeFamily.NOVEL
import org.skepsun.kototoro.core.model.ContentTypeFamily.OTHER
import org.skepsun.kototoro.core.model.ContentTypeFamily.VIDEO
import org.skepsun.kototoro.migration.domain.assignFamilies

class FamilyAssignmentTest {
    @Test
    fun `unknown entries join the largest known family`() {
        val assigned = assignFamilies(mapOf(1L to MANGA, 2L to MANGA, 3L to VIDEO, 4L to OTHER))
        assertEquals(mapOf(1L to MANGA, 2L to MANGA, 3L to VIDEO, 4L to MANGA), assigned)
    }

    @Test
    fun `only unknown entries stay other`() {
        assertEquals(mapOf(1L to OTHER), assignFamilies(mapOf(1L to OTHER)))
    }

    @Test
    fun `known families are untouched`() {
        val input = mapOf(1L to NOVEL, 2L to VIDEO)
        assertEquals(input, assignFamilies(input))
    }
}
