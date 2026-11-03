package org.skepsun.kototoro.core.js

import com.dokar.quickjs.QuickJs
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QuickJS wrapper with Promise support via executePendingJob.
 *
 * Scripts evaluated here come from imported sources and get no host access. Rhino is used only when the
 * QuickJS native runtime cannot be created; a script that fails inside QuickJS is never re-run in Rhino,
 * and the Rhino fallback has no Java interop and the same time limit.
 */
@Singleton
class JSEngine @Inject constructor() : Closeable {

    fun <T> evaluate(code: String, name: String = "<js>", clazz: Class<T>? = null): T? {
        val qjs = runCatching {
            QuickJs.create(jobDispatcher = Dispatchers.Default)
        }.onFailure { it.printStackTraceDebug() }.getOrNull()
            ?: return evaluateWithRhino(code, name, clazz)
        return runCatching {
            runBlocking(Dispatchers.Default) {
                qjs.use {
                    it.maxStackSize = 1L shl 20 // 1MB
                    it.memoryLimit = 64L shl 20  // 64MB soft limit
                    it.evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS
                    executeWithQuickJs(it, code, name, clazz)
                }
            }
        }.onFailure { it.printStackTraceDebug() }.getOrNull()
    }

    private fun <T> evaluateWithRhino(code: String, name: String, clazz: Class<T>?): T? = runCatching {
        val ctx = SandboxContextFactory.enterContext()
        try {
            ctx.putThreadLocal(DEADLINE_KEY, System.nanoTime() + EVALUATION_TIMEOUT_MS * 1_000_000L)
            val scope = ctx.initSafeStandardObjects()
            val raw = ctx.evaluateString(scope, code, name, 1, null)
            @Suppress("UNCHECKED_CAST")
            when (clazz) {
                null -> raw as? T
                String::class.java -> clazz.cast(Context.toString(raw))
                else -> {
                    // Int::class.java and friends are primitive classes, which Class.cast rejects.
                    val boxed = (clazz as Class<Any>).kotlin.javaObjectType
                    boxed.cast(Context.jsToJava(raw, boxed)) as T
                }
            }
        } finally {
            Context.exit()
        }
    }.onFailure { it.printStackTraceDebug() }.getOrNull()

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

    /** Interpreted Rhino contexts with no visible Java classes and a wall-clock budget per evaluation. */
    private object SandboxContextFactory : ContextFactory() {

        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            instructionObserverThreshold = INSTRUCTION_OBSERVER_THRESHOLD
            setClassShutter { false }
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val deadline = cx.getThreadLocal(DEADLINE_KEY) as? Long ?: return
            if (System.nanoTime() > deadline) {
                throw EvaluationTimeoutError()
            }
        }
    }

    private class EvaluationTimeoutError : Error("JS evaluation exceeded $EVALUATION_TIMEOUT_MS ms")

    internal companion object {
        const val EVALUATION_TIMEOUT_MS = 10_000L
        private const val INSTRUCTION_OBSERVER_THRESHOLD = 10_000
        private const val DEADLINE_KEY = "kototoro.js.deadline"
    }
}
