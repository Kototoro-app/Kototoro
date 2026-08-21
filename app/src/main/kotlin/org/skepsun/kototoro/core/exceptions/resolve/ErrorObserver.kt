package org.skepsun.kototoro.core.exceptions.resolve

import android.view.View
import androidx.core.util.Consumer
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.util.ext.findActivity

abstract class ErrorObserver(
    protected val host: View,
    protected val resolver: ExceptionResolver?,
    private val onResolved: Consumer<Boolean>?,
) : FlowCollector<Throwable> {

    protected fun getResolveStringId(error: Throwable): Int {
        return resolver?.getResolveStringId(error) ?: ExceptionResolver.getResolveStringId(error)
    }

    protected open val activity = host.context.findActivity()

    private val lifecycleScope: LifecycleCoroutineScope
        get() = checkNotNull((activity as? LifecycleOwner)?.lifecycle?.coroutineScope)

    protected fun canResolve(error: Throwable): Boolean {
        return resolver != null && ExceptionResolver.canResolve(error)
    }

    protected fun router() = (activity as? FragmentActivity)?.router

    private fun isAlive(): Boolean {
        return activity?.isDestroyed == false
    }

    protected fun resolve(error: Throwable) {
        if (isAlive()) {
            lifecycleScope.launch {
                val isResolved = resolver?.resolve(error, tryAutoResolve = false) == true
                if (isActive) {
                    onResolved?.accept(isResolved)
                }
            }
        }
    }

    protected suspend fun resolveNow(error: Throwable, tryAutoResolve: Boolean): Boolean {
        return resolver?.resolve(error, tryAutoResolve = tryAutoResolve) == true
    }
}
