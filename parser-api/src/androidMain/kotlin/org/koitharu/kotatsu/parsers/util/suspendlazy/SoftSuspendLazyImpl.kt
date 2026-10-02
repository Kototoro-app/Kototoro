package org.koitharu.kotatsu.parsers.util.suspendlazy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.SoftReference
import kotlin.coroutines.CoroutineContext

/**
 * Like a [SuspendLazy] but with [SoftReference] under the hood
 */
internal class SoftSuspendLazyImpl<T : Any>(
	private val coroutineContext: CoroutineContext,
	private val initializer: SuspendLazyInitializer<T>,
) : SuspendLazy<T> {

	private val mutex: Mutex = Mutex()
	private var cachedValue: SoftReference<T>? = null

	override val isInitialized: Boolean
		get() = cachedValue?.get() != null

	override suspend fun get(): T {
		// fast way
		cachedValue?.get()?.let {
			return it
		}
		return mutex.withLock {
			cachedValue?.get()?.let {
				return it
			}
			val result = withContext(coroutineContext) {
				initializer()
			}
			cachedValue = SoftReference(result)
			result
		}
	}

	override fun peek(): T? = cachedValue?.get()
}
