package org.skepsun.kototoro.core.js

import com.dokar.quickjs.QuickJs
import org.mozilla.javascript.Context
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QuickJS wrapper with Promise support via executePendingJob. Falls back to Rhino if native fails.
 */
@Singleton
class JSEngine @Inject constructor() : Closeable {

    fun <T> evaluate(code: String, name: String = "<js>", clazz: Class<T>? = null): T? {
        // Try QuickJS (supports promises; stack/memory adjustable)
        runCatching<T?> {
            runBlocking(Dispatchers.Default) {
                QuickJs.create(jobDispatcher = Dispatchers.Default).use { qjs ->
                    qjs.maxStackSize = 1L shl 20 // 1MB
                    qjs.memoryLimit = 64L shl 20  // 64MB soft limit
                    executeWithQuickJs(qjs, code, name, clazz)
                }
            }
        }.onFailure { it.printStackTraceDebug() }.getOrNull()?.let { return it }

        // Rhino fallback (pure Java, slower but no native dependency)
        return runCatching {
            val ctx = Context.enter().apply { optimizationLevel = -1 }
            try {
                val scope = ctx.initStandardObjects()
                val result = ctx.evaluateString(scope, code, name, 1, null)
                if (clazz != null) clazz.cast(result) else result as? T
            } finally {
                Context.exit()
            }
        }.onFailure { it.printStackTraceDebug() }.getOrNull()
    }

    private suspend fun <T> executeWithQuickJs(
        qjs: QuickJs,
        code: String,
        name: String,
        clazz: Class<T>?,
    ): T? {
        val result: Any? = if (clazz != null) {
            when (clazz) {
                String::class.java -> qjs.evaluate<String>(code, name)
                Int::class.java, java.lang.Integer::class.java -> qjs.evaluate<Int>(code, name)
                Double::class.java, java.lang.Double::class.java -> qjs.evaluate<Double>(code, name)
                Boolean::class.java, java.lang.Boolean::class.java -> qjs.evaluate<Boolean>(code, name)
                else -> qjs.evaluate<Any?>(code, name)
            }
        } else {
            qjs.evaluate<Any?>(code, name)
        }
        @Suppress("UNCHECKED_CAST")
        return result as? T
    }

    override fun close() {
        // runtime/ctx are closed in use blocks
    }
}
