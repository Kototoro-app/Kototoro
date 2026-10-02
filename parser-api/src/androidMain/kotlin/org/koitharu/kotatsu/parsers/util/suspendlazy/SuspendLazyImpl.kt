package org.koitharu.kotatsu.parsers.util.suspendlazy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal class SuspendLazyImpl<T>(
	private val coroutineContext: CoroutineContext,
	private val initializer: SuspendLazyInitializer<T>,
) : SuspendLazy<T> {

	private val mutex: Mutex = Mutex()
	private var cachedValue: Any? = Uninitialized

	override val isInitialized: Boolean
		get() = cachedValue !== Uninitialized

	@Suppress("UNCHECKED_CAST")
	override suspend fun get(): T {
		// fast way
		cachedValue.let {
			if (it !== Uninitialized) {
				return it as T
			}
		}
		return mutex.withLock {
			cachedValue.let {
				if (it !== Uninitialized) {
					return it as T
				}
			}
			val result = withContext(coroutineContext) {
				initializer()
			}
			cachedValue = result
			result
		}
	}

	@Suppress("UNCHECKED_CAST")
	override fun peek(): T? {
		return cachedValue?.takeUnless { it === Uninitialized } as T?
	}

	private object Uninitialized
}
