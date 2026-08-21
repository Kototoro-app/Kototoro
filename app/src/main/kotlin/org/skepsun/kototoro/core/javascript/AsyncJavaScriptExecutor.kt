package org.skepsun.kototoro.core.javascript

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 异步 JavaScript 执行器
 *
 * 在 IO 线程执行 JavaScript 代码，避免阻塞主线程
 * 使用协程管理异步操作，提供更好的性能和响应性
 *
 * 参考 Legado 的异步 JavaScript 执行方式
 *
 * 特性：
 * - 异步执行：在 IO 线程执行 JavaScript，不阻塞主线程
 * - 协程支持：使用 Kotlin 协程管理异步操作
 * - 引擎池集成：自动从引擎池获取和释放引擎
 * - 错误处理：捕获并记录执行错误
 */
@Singleton
class AsyncJavaScriptExecutor @Inject constructor(
    private val enginePool: JavaScriptEnginePool,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * 异步执行 JavaScript 代码
     *
     * 在 IO 线程执行 JavaScript，自动管理引擎的获取和释放
     *
     * @param script JavaScript 代码
     * @param context 执行上下文
     * @return 执行结果，如果执行失败则返回 null
     */
    suspend fun executeAsync(script: String, context: JavaScriptContext): Any? {
        return withContext(dispatcher) {
            executeInternal(script, context)
        }
    }

    /**
     * 异步执行 JavaScript 表达式
     *
     * @param expression JavaScript 表达式
     * @param context 执行上下文
     * @return 表达式结果，如果执行失败则返回 null
     */
    suspend fun evaluateAsync(expression: String, context: JavaScriptContext): Any? {
        return withContext(dispatcher) {
            evaluateInternal(expression, context)
        }
    }

    /**
     * 批量异步执行 JavaScript 代码
     *
     * 并发执行多个 JavaScript 脚本，提高性能
     *
     * @param scripts JavaScript 代码列表
     * @param contexts 对应的执行上下文列表
     * @return 执行结果列表
     */
    suspend fun executeBatchAsync(
        scripts: List<String>,
        contexts: List<JavaScriptContext>
    ): List<Any?> {
        require(scripts.size == contexts.size) {
            "Scripts and contexts must have the same size"
        }

        return withContext(dispatcher) {
            scripts.zip(contexts).map { (script, context) ->
                executeInternal(script, context)
            }
        }
    }

    /**
     * 内部执行方法
     *
     * 从引擎池获取引擎，执行脚本，然后释放引擎
     */
    private fun executeInternal(script: String, context: JavaScriptContext): Any? {
        if (script.isBlank()) {
            return null
        }

        return try {
            enginePool.use { engine ->
                Log.d(TAG, "Executing JavaScript asynchronously: ${script.take(50)}...")
                val result = engine.execute(script, context)
                Log.d(TAG, "JavaScript execution completed: $result")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Async JavaScript execution failed: $script", e)
            null
        }
    }

    /**
     * 内部评估方法
     */
    private fun evaluateInternal(expression: String, context: JavaScriptContext): Any? {
        if (expression.isBlank()) {
            return null
        }

        return try {
            enginePool.use { engine ->
                Log.d(TAG, "Evaluating JavaScript expression asynchronously: ${expression.take(50)}...")
                val result = engine.evaluate(expression, context)
                Log.d(TAG, "JavaScript evaluation completed: $result")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Async JavaScript evaluation failed: $expression", e)
            null
        }
    }

    companion object {
        private const val TAG = "AsyncJavaScriptExecutor"
    }
}

/**
 * 异步 JavaScript 规则解析器
 *
 * 在 JavaScriptRuleParser 的基础上添加异步执行支持
 */
class AsyncJavaScriptRuleParser(
    private val syncParser: JavaScriptRuleParser,
    private val asyncExecutor: AsyncJavaScriptExecutor
) {

    /**
     * 检测规则是否包含 JavaScript
     *
     * 这是一个同步操作，不需要异步
     */
    fun containsJavaScript(rule: String): Boolean {
        return syncParser.containsJavaScript(rule)
    }

    /**
     * 提取 JavaScript 代码
     *
     * 这是一个同步操作，不需要异步
     */
    fun extractJavaScript(rule: String): String? {
        return syncParser.extractJavaScript(rule)
    }

    /**
     * 异步执行 JavaScript 规则
     *
     * @param rule 规则字符串
     * @param input 输入数据
     * @param context JavaScript 执行上下文
     * @return 执行结果，如果执行失败则返回 null
     */
    suspend fun executeRuleAsync(rule: String, input: Any?, context: JavaScriptContext): Any? {
        // 检测是否包含 JavaScript
        if (!containsJavaScript(rule)) {
            Log.w(TAG, "Rule does not contain JavaScript: $rule")
            return null
        }

        // 提取 JavaScript 代码
        val jsCode = extractJavaScript(rule)
        if (jsCode.isNullOrBlank()) {
            Log.w(TAG, "Failed to extract JavaScript from rule: $rule")
            return null
        }

        // 设置 result 变量
        context.setVariable("result", input)

        // 异步执行 JavaScript
        return try {
            val result = asyncExecutor.executeAsync(jsCode, context)
            Log.d(TAG, "Async JavaScript execution successful: $jsCode -> $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Async JavaScript execution failed: $jsCode", e)
            null
        }
    }

    companion object {
        private const val TAG = "AsyncJavaScriptRuleParser"
    }
}
