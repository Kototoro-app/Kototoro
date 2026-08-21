package org.skepsun.kototoro.core.ui.util

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

fun interface ReversibleHandle {

    suspend fun reverse()
}

fun ReversibleHandle.reverseAsync() = processLifecycleScope.launch(Dispatchers.Default, CoroutineStart.ATOMIC) {
    runCatchingCancellable {
        withContext(NonCancellable) {
            reverse()
        }
    }.onFailure {
        it.printStackTraceDebug()
    }
}
