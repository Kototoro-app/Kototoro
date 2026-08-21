package org.skepsun.kototoro.core.javascript

import android.util.Log

/**
 * 安全的 JavaScript 引擎装饰器
 *
 * 捕获所有异常并记录日志，返回 null 而不是崩溃
 *
 * **参考 Legado**: 查看 Legado 的 JavaScript 异常处理方式
 *
 * @param delegate 被装饰的 JavaScript 引擎
 */
class SafeJavaScriptEngine(
    private val delegate: JavaScriptEngine
) : JavaScriptEngine {

    override fun execute(script: String, context: JavaScriptContext): Any? {
        return safeExecute(script, context) {
            delegate.execute(script, context)
        }
    }

    override fun evaluate(expression: String, context: JavaScriptContext): Any? {
        return safeExecute(expression, context) {
            delegate.evaluate(expression, context)
        }
    }

    override fun registerGlobalObject(name: String, obj: Any) {
        try {
            delegate.registerGlobalObject(name, obj)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register global object: $name", e)
        }
    }

    override fun dispose() {
        try {
            delegate.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispose engine", e)
        }
    }

    /**
     * 安全执行 JavaScript 代码
     *
     * 捕获所有异常并记录日志，返回 null 而不是崩溃
     *
     * @param code JavaScript 代码或表达式
     * @param context 执行上下文
     * @param block 执行块
     * @return 执行结果，失败时返回 null
     */
    private fun safeExecute(
        code: String,
        context: JavaScriptContext,
        block: () -> Any?
    ): Any? {
        return try {
            block()
        } catch (e: JavaScriptTimeoutException) {
            // 超时异常单独处理
            Log.e(TAG, "JavaScript execution timeout: ${code.take(100)}...", e)
            logContextInfo(context)
            null
        } catch (e: OutOfMemoryError) {
            // 内存溢出异常
            Log.e(TAG, "JavaScript execution out of memory: ${code.take(100)}...", e)
            logContextInfo(context)
            null
        } catch (e: StackOverflowError) {
            // 栈溢出异常（通常是无限递归）
            Log.e(TAG, "JavaScript execution stack overflow: ${code.take(100)}...", e)
            logContextInfo(context)
            null
        } catch (e: Exception) {
            // 其他所有异常
            Log.e(TAG, "JavaScript execution failed: ${code.take(100)}...", e)
            logContextInfo(context)
            null
        }
    }

    /**
     * 记录上下文信息以便调试
     */
    private fun logContextInfo(context: JavaScriptContext) {
        try {
            val variables = context.getAllVariables()
            Log.d(TAG, "Context variables: ${variables.keys.joinToString(", ")}")

            // 记录关键变量的值
            context.baseUrl?.let { Log.d(TAG, "baseUrl: $it") }
            context.key?.let { Log.d(TAG, "key: $it") }
            context.page?.let { Log.d(TAG, "page: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log context info", e)
        }
    }

    companion object {
        private const val TAG = "SafeJavaScriptEngine"
    }
}

/**
 * 扩展函数：创建安全的 JavaScript 引擎
 */
fun JavaScriptEngine.safe(): SafeJavaScriptEngine {
    return SafeJavaScriptEngine(this)
}

/**
 * 扩展函数：创建带超时和安全保护的 JavaScript 引擎
 */
fun JavaScriptEngine.withTimeout(timeoutMillis: Long = TimeoutJavaScriptEngine.DEFAULT_TIMEOUT_MILLIS): JavaScriptEngine {
    return SafeJavaScriptEngine(TimeoutJavaScriptEngine(this, timeoutMillis))
}
