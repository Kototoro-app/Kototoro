package com.lagradost.cloudstream3.utils

/** Cloudstream's observer list, as plugins fire `MainActivity` events through it; same ABI as the official app. */
class Event<T> {
	private val observers = mutableSetOf<(T) -> Unit>()

	val size: Int get() = observers.size

	operator fun plusAssign(observer: (T) -> Unit) {
		synchronized(observers) {
			observers.add(observer)
		}
	}

	operator fun minusAssign(observer: (T) -> Unit) {
		synchronized(observers) {
			observers.remove(observer)
		}
	}

	operator fun invoke(value: T) {
		synchronized(observers) {
			for (observer in observers) observer(value)
		}
	}
}
