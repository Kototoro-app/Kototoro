package org.skepsun.kototoro.reader.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PriorityPermitPoolTest {

	@Test
	fun `visible request runs before queued prefetch`() = runTest {
		val pool = PriorityPermitPool(1)
		val releaseHolder = CompletableDeferred<Unit>()
		val order = mutableListOf<String>()
		val holder = async {
			pool.withPermit(pool.ticket(1)) { releaseHolder.await() }
		}
		runCurrent()
		val prefetch = async {
			pool.withPermit(pool.ticket(0)) { order += "prefetch" }
		}
		val visible = async {
			pool.withPermit(pool.ticket(1)) { order += "visible" }
		}
		runCurrent()

		releaseHolder.complete(Unit)
		awaitAll(holder, prefetch, visible)

		assertEquals(listOf("visible", "prefetch"), order)
	}

	@Test
	fun `promoted prefetch moves ahead of earlier prefetch`() = runTest {
		val pool = PriorityPermitPool(1)
		val releaseHolder = CompletableDeferred<Unit>()
		val order = mutableListOf<String>()
		val promotedTicket = pool.ticket(0)
		val holder = async {
			pool.withPermit(pool.ticket(1)) { releaseHolder.await() }
		}
		runCurrent()
		val first = async {
			pool.withPermit(pool.ticket(0)) { order += "first" }
		}
		val promoted = async {
			pool.withPermit(promotedTicket) { order += "promoted" }
		}
		runCurrent()
		pool.promote(promotedTicket, 1)

		releaseHolder.complete(Unit)
		awaitAll(holder, first, promoted)

		assertEquals(listOf("promoted", "first"), order)
	}
}
