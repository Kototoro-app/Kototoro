package org.skepsun.kototoro.settings.sources.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SourceGroupApplyRulesTest {

	@Test
	fun `empty group jar priority leaves the user order untouched`() {
		assertNull(groupJarPriorityToApply(emptyList(), setOf("a.jar")))
		assertNull(groupJarPriorityToApply(listOf("missing.jar"), setOf("a.jar")))
	}

	@Test
	fun `group jar priority keeps installed entries in group order`() {
		assertEquals("b.jar,a.jar", groupJarPriorityToApply(listOf("b.jar", "gone.jar", " A.jar "), setOf("a.jar", "b.jar")))
	}

	@Test
	fun `null lock does not clear a lock the user set by hand`() {
		val plan = groupLockPlan(groupLocks = mapOf("manual" to null, "listed" to true), previouslyApplied = emptySet())
		assertEquals(mapOf<String, Boolean?>("listed" to true), plan)
	}

	@Test
	fun `null lock clears a lock written by an earlier group apply`() {
		val plan = groupLockPlan(groupLocks = mapOf("applied" to null), previouslyApplied = setOf("applied"))
		assertEquals(mapOf<String, Boolean?>("applied" to null), plan)
	}

	@Test
	fun `group applied lock of a source the group no longer lists is released`() {
		val plan = groupLockPlan(groupLocks = mapOf("listed" to false), previouslyApplied = setOf("old"))
		assertEquals(mapOf<String, Boolean?>("old" to null, "listed" to false), plan)
	}
}
