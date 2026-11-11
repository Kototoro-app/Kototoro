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
		// Entries are matched case/space-insensitively but stored as the group wrote them.
		assertEquals("b.jar, A.jar ", groupJarPriorityToApply(listOf("b.jar", "gone.jar", " A.jar "), setOf("a.jar", "b.jar")))
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

	@Test
	fun `hand-set pins outside the group are kept`() {
		val removed = groupPinsToRemove(
			previouslyApplied = setOf("applied"),
			inGroup = emptySet(),
			currentlyPinned = setOf("applied", "manual"),
		)
		assertEquals(setOf("applied"), removed)
	}

	@Test
	fun `previously applied pin still listed by the group or already unpinned is not removed`() {
		val removed = groupPinsToRemove(
			previouslyApplied = setOf("listed", "gone", "applied"),
			inGroup = setOf("listed"),
			currentlyPinned = setOf("listed", "applied"),
		)
		assertEquals(setOf("applied"), removed)
	}

	@Test
	fun `source name must match pattern and be known`() {
		val known = setOf("MIHON_1", "a.b:c-d_e", "../x", "a/b")
		assertEquals(true, isValidGroupSourceName("MIHON_1", known))
		assertEquals(true, isValidGroupSourceName("a.b:c-d_e", known))
		assertEquals(false, isValidGroupSourceName("MIHON_2", known))
		assertEquals(false, isValidGroupSourceName("../x", known))
		assertEquals(false, isValidGroupSourceName("a/b", known))
		assertEquals(false, isValidGroupSourceName("a..b", setOf("a..b")))
		assertEquals(false, isValidGroupSourceName("", setOf("")))
		assertEquals(false, isValidGroupSourceName("a".repeat(129), setOf("a".repeat(129))))
	}

	@Test
	fun `domain accepts plain hosts and https only`() {
		assertEquals(true, isValidGroupDomain("example.com"))
		assertEquals(true, isValidGroupDomain("https://sub.example.com:8080"))
		assertEquals(false, isValidGroupDomain("http://example.com"))
		assertEquals(false, isValidGroupDomain("example.com/path"))
		assertEquals(false, isValidGroupDomain("user@example.com"))
		assertEquals(false, isValidGroupDomain("exa mple.com"))
		assertEquals(false, isValidGroupDomain("a..com"))
		assertEquals(false, isValidGroupDomain(""))
		assertEquals(false, isValidGroupDomain("a".repeat(254)))
		assertEquals(false, isValidGroupDomain("javascript:alert(1)"))
	}
}
