package org.skepsun.kototoro.settings.sources.lock

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.sync.google.domain.MemorySyncPreferences

class SourceLockStoreTest {

	private val lockPrefs = MemorySyncPreferences()
	private val appliedPrefs = MemorySyncPreferences()
	private lateinit var store: SourceLockStore

	@BeforeEach
	fun setUp() {
		val context = mockk<Context>()
		every { context.getSharedPreferences("source_locks", any()) } returns lockPrefs
		every { context.getSharedPreferences("group_applied_locks", any()) } returns appliedPrefs
		store = SourceLockStore::class.java.getDeclaredConstructor(Context::class.java)
			.apply { isAccessible = true }
			.newInstance(context)
	}

	@Test
	fun `setAll publishes the final map in a single update`() {
		store.setAll(mapOf("keep" to true))
		val emissions = mutableListOf<Map<String, Boolean>>()
		val scope = CoroutineScope(Dispatchers.Unconfined)
		val collector = scope.launch { store.locks.collect { emissions += it } }
		assertEquals(1, emissions.size)

		store.setAll(mapOf("a" to true, "b" to false, "keep" to null))
		collector.cancel()

		assertEquals(2, emissions.size)
		assertEquals(mapOf("a" to true, "b" to false), emissions.last())
		assertEquals(null, store.get("keep"))
		assertEquals(true, lockPrefs.getBoolean("a", false))
		assertEquals("none", lockPrefs.getString("keep", null))
	}

	@Test
	fun `setAll keeps group applied marks while a manual set releases them`() {
		store.setAll(mapOf("a" to true, "b" to true))
		store.setGroupApplied(setOf("a", "b"))
		store.setAll(mapOf("a" to false))
		assertEquals(setOf("a", "b"), store.groupAppliedNames())

		store.set("a", true)
		assertEquals(setOf("b"), store.groupAppliedNames())
		assertEquals(true, store.get("a"))
	}

	@Test
	fun `remove drops lock and group applied mark`() {
		store.setAll(mapOf("a" to true))
		store.setGroupApplied(setOf("a"))
		store.remove(listOf("a"))
		assertEquals(emptyMap<String, Boolean>(), store.locks.value)
		assertEquals(emptySet<String>(), store.groupAppliedNames())
	}

	@Test
	fun `setAll bumps the revision once and a later external write is still mirrored`() {
		val before = store.externalRevision.get()
		store.setAll(mapOf("a" to true, "b" to false))
		assertEquals(before + 1, store.externalRevision.get())

		lockPrefs.edit().putBoolean("a", false).apply()
		assertEquals(false, store.get("a"))
		assertEquals(before + 2, store.externalRevision.get())
	}
}
