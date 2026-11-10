package org.skepsun.kototoro.core.parser.legado

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element to pass request priority down to LegadoRepository.
 */
class RequestPriority(val priority: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestPriority> {
        const val FOREGROUND = 0
        const val BACKGROUND = 10
    }
}
