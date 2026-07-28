package org.skepsun.kototoro.reader.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.IdentityHashMap
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

internal class PriorityPermitPool(permits: Int) {

	init {
		require(permits > 0) { "Permits must be positive" }
	}

	private val lock = Any()
	private val waiters = PriorityQueue<Waiter>()
	private val ticketWaiters = IdentityHashMap<Ticket, Waiter>()
	private var availablePermits = permits

	fun ticket(priority: Int): Ticket = Ticket(priority)

	fun promote(ticket: Ticket, priority: Int) {
		synchronized(lock) {
			if (priority <= ticket.priority) return
			val waiter = ticketWaiters[ticket]
			if (waiter != null) {
				waiters.remove(waiter)
			}
			ticket.priority = priority
			if (waiter != null) {
				waiters.offer(waiter)
			}
		}
	}

	suspend fun <T> withPermit(ticket: Ticket, block: suspend () -> T): T {
		acquire(ticket)
		try {
			return block()
		} finally {
			release()
		}
	}

	private suspend fun acquire(ticket: Ticket) {
		val waiter = synchronized(lock) {
			if (availablePermits > 0) {
				availablePermits--
				null
			} else {
				Waiter(ticket).also {
					ticketWaiters[ticket] = it
					waiters.offer(it)
				}
			}
		} ?: return

		try {
			waiter.signal.await()
		} catch (error: CancellationException) {
			val shouldRelease = synchronized(lock) {
				if (waiters.remove(waiter)) {
					ticketWaiters.remove(waiter.ticket)
					false
				} else {
					waiter.granted
				}
			}
			if (shouldRelease) release()
			throw error
		}
	}

	private fun release() {
		synchronized(lock) {
			while (true) {
				val waiter = waiters.poll()
				if (waiter == null) {
					availablePermits++
					return
				}
				ticketWaiters.remove(waiter.ticket)
				if (waiter.signal.complete(Unit)) {
					waiter.granted = true
					return
				}
			}
		}
	}

	internal class Ticket internal constructor(
		internal var priority: Int,
	)

	private class Waiter(
		val ticket: Ticket,
	) : Comparable<Waiter> {

		val signal = CompletableDeferred<Unit>()
		val sequence = sequenceGenerator.incrementAndGet()
		var granted = false

		override fun compareTo(other: Waiter): Int {
			val priorityComparison = other.ticket.priority.compareTo(ticket.priority)
			return if (priorityComparison != 0) priorityComparison else sequence.compareTo(other.sequence)
		}
	}

	private companion object {
		val sequenceGenerator = AtomicLong()
	}
}
