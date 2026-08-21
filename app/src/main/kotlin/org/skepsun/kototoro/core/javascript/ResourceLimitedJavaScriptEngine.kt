package org.skepsun.kototoro.core.javascript

import android.util.Log

/**
 * 带资源限制的 JavaScript 引擎装饰器
 *
 * 监控内存使用，限制最大内存使用量
 *
 * **参考 Legado**: 查看 Legado 的资源限制策略
 *
 * @param delegate 被装饰的 JavaScript 引擎
 * @param maxMemoryMB 最大内存使用量（MB），默认 50MB
 */
class ResourceLimitedJavaScriptEngine(
    private val delegate: JavaScriptEngine,
    private val maxMemoryMB: Long = DEFAULT_MAX_MEMORY_MB
) : JavaScriptEngine {

    override fun execute(script: String, context: JavaScriptContext): Any? {
        checkMemoryUsage("execute", script)
        return delegate.execute(script, context)
    }

    override fun evaluate(expression: String, context: JavaScriptContext): Any? {
        checkMemoryUsage("evaluate", expression)
        return delegate.evaluate(expression, context)
    }

    override fun registerGlobalObject(name: String, obj: Any) {
        delegate.registerGlobalObject(name, obj)
    }

    override fun dispose() {
        delegate.dispose()
    }

    /**
     * 检查内存使用情况
     *
     * @param operation 操作名称
     * @param code JavaScript 代码
     * @throws JavaScriptMemoryLimitException 如果内存使用超过限制
     */
    private fun checkMemoryUsage(operation: String, code: String) {
        val runtime = Runtime.getRuntime()
        val usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemoryMB = runtime.maxMemory() / 1024 / 1024
        val availableMemoryMB = maxMemoryMB - usedMemoryMB

        // 记录内存使用情况
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Memory usage: ${usedMemoryMB}MB / ${maxMemoryMB}MB (available: ${availableMemoryMB}MB)")
        }

        // 检查是否超过限制
        if (usedMemoryMB > this.maxMemoryMB) {
            val message = "JavaScript execution exceeded memory limit: ${usedMemoryMB}MB > ${this.maxMemoryMB}MB"
            Log.e(TAG, "$message for $operation")

            // 尝试触发垃圾回收
            System.gc()

            // 再次检查
            val usedMemoryAfterGC = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            if (usedMemoryAfterGC > this.maxMemoryMB) {
                throw JavaScriptMemoryLimitException(message)
            } else {
                Log.i(TAG, "Memory recovered after GC: ${usedMemoryAfterGC}MB")
            }
        }

        // 警告：内存使用接近限制
        if (usedMemoryMB > this.maxMemoryMB * 0.8) {
            Log.w(TAG, "Memory usage approaching limit: ${usedMemoryMB}MB / ${this.maxMemoryMB}MB")
        }
    }

    /**
     * 获取当前内存使用情况
     *
     * @return 内存使用信息
     */
    fun getMemoryUsage(): MemoryUsage {
        val runtime = Runtime.getRuntime()
        val usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemoryMB = runtime.maxMemory() / 1024 / 1024
        val totalMemoryMB = runtime.totalMemory() / 1024 / 1024
        val freeMemoryMB = runtime.freeMemory() / 1024 / 1024

        return MemoryUsage(
            usedMemoryMB = usedMemoryMB,
            maxMemoryMB = maxMemoryMB,
            totalMemoryMB = totalMemoryMB,
            freeMemoryMB = freeMemoryMB,
            limitMB = this.maxMemoryMB
        )
    }

    companion object {
        private const val TAG = "ResourceLimitedJavaScriptEngine"

        /**
         * 默认最大内存使用量：50MB
         */
        const val DEFAULT_MAX_MEMORY_MB = 50L
    }
}

/**
 * JavaScript 内存限制异常
 */
class JavaScriptMemoryLimitException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 内存使用信息
 */
data class MemoryUsage(
    val usedMemoryMB: Long,
    val maxMemoryMB: Long,
    val totalMemoryMB: Long,
    val freeMemoryMB: Long,
    val limitMB: Long
) {
    /**
     * 内存使用百分比
     */
    val usagePercentage: Double
        get() = (usedMemoryMB.toDouble() / limitMB.toDouble()) * 100.0

    /**
     * 是否接近限制（超过 80%）
     */
    val isApproachingLimit: Boolean
        get() = usagePercentage > 80.0

    /**
     * 是否超过限制
     */
    val isOverLimit: Boolean
        get() = usedMemoryMB > limitMB
}

/**
 * 扩展函数：创建带资源限制的 JavaScript 引擎
 */
fun JavaScriptEngine.withResourceLimit(maxMemoryMB: Long = ResourceLimitedJavaScriptEngine.DEFAULT_MAX_MEMORY_MB): ResourceLimitedJavaScriptEngine {
    return ResourceLimitedJavaScriptEngine(this, maxMemoryMB)
}
