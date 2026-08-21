package org.skepsun.kototoro.core.javascript

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 带超时控制的 JavaScript 引擎装饰器
 *
 * 为 JavaScript 执行添加超时控制，防止恶意或低效的脚本长时间运行
 *
 * **参考 Legado**: RhinoScriptEngine.kt 中的超时控制机制
 *
 * @param delegate 被装饰的 JavaScript 引擎
 * @param timeoutMillis 超时时间（毫秒），默认 30 秒
 */
class TimeoutJavaScriptEngine(
    private val delegate: JavaScriptEngine,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) : JavaScriptEngine {

    override fun execute(script: String, context: JavaScriptContext): Any? {
        return try {
            runBlocking {
                withTimeout(timeoutMillis) {
                    delegate.execute(script, context)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Script execution timeout after ${timeoutMillis}ms: ${script.take(100)}...", e)
            throw JavaScriptTimeoutException("Script execution timeout after ${timeoutMillis}ms", e)
        } catch (e: Exception) {
            Log.e(TAG, "Script execution failed: ${script.take(100)}...", e)
            throw e
        }
    }

    override fun evaluate(expression: String, context: JavaScriptContext): Any? {
        return try {
            runBlocking {
                withTimeout(timeoutMillis) {
                    delegate.evaluate(expression, context)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Expression evaluation timeout after ${timeoutMillis}ms: ${expression.take(100)}...", e)
            throw JavaScriptTimeoutException("Expression evaluation timeout after ${timeoutMillis}ms", e)
        } catch (e: Exception) {
            Log.e(TAG, "Expression evaluation failed: ${expression.take(100)}...", e)
            throw e
        }
    }

    override fun registerGlobalObject(name: String, obj: Any) {
        delegate.registerGlobalObject(name, obj)
    }

    override fun dispose() {
        delegate.dispose()
    }

    companion object {
        private const val TAG = "TimeoutJavaScriptEngine"

        /**
         * 默认超时时间：30 秒
         */
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * JavaScript 执行超时异常
 */
class JavaScriptTimeoutException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
