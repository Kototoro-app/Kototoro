package org.skepsun.kototoro.core.javascript

import android.content.Context
import android.util.Log
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar
import java.net.CookieManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JavaScript 引擎池
 *
 * 管理 JavaScript 引擎实例的创建、获取和释放，提高性能
 * 通过复用引擎实例避免重复初始化的开销
 *
 * 参考 Legado 的 RhinoScriptEngine.kt 中的引擎复用策略
 *
 * 特性：
 * - 最大池大小：5 个引擎
 * - 自动创建：池为空时自动创建新引擎
 * - 线程安全：使用 ConcurrentLinkedQueue 保证线程安全
 * - 资源管理：提供清理方法释放所有引擎
 */
@Singleton
class JavaScriptEnginePool @Inject constructor(
    private val httpClient: LegadoHttpClient,
    private val cookieManager: CookieManager,
    private val cookieJar: PersistentCookieJar,
    @dagger.hilt.android.qualifiers.ApplicationContext private val androidContext: Context
) {

    // 可用引擎队列
    private val availableEngines = ConcurrentLinkedQueue<RhinoJavaScriptEngine>()

    // 当前创建的引擎数量
    private val createdEngines = AtomicInteger(0)

    // 统计信息
    private val acquireCount = AtomicInteger(0)
    private val createCount = AtomicInteger(0)
    private val reuseCount = AtomicInteger(0)

    /**
     * 获取一个 JavaScript 引擎
     *
     * 如果池中有可用引擎，则复用；否则创建新引擎
     *
     * @return JavaScript 引擎实例
     */
    fun acquire(): JavaScriptEngine {
        acquireCount.incrementAndGet()

        // 尝试从池中获取
        val engine = availableEngines.poll()

        return if (engine != null) {
            // 复用现有引擎
            reuseCount.incrementAndGet()
            Log.d(TAG, "Reused engine from pool (pool size: ${availableEngines.size}, total: ${createdEngines.get()})")
            engine
        } else {
            // 创建新引擎
            val newEngine = createEngine()
            createCount.incrementAndGet()
            createdEngines.incrementAndGet()
            Log.d(TAG, "Created new engine (total: ${createdEngines.get()})")
            newEngine
        }
    }

    /**
     * 释放一个 JavaScript 引擎回池中
     *
     * 如果池未满，则将引擎放回池中；否则销毁引擎
     *
     * @param engine 要释放的引擎
     */
    fun release(engine: JavaScriptEngine) {
        if (engine !is RhinoJavaScriptEngine) {
            Log.w(TAG, "Cannot release non-Rhino engine")
            return
        }

        // 如果池未满，放回池中
        if (availableEngines.size < MAX_POOL_SIZE) {
            availableEngines.offer(engine)
            Log.d(TAG, "Released engine to pool (pool size: ${availableEngines.size})")
        } else {
            // 池已满，销毁引擎
            engine.dispose()
            createdEngines.decrementAndGet()
            Log.d(TAG, "Disposed engine (pool full, total: ${createdEngines.get()})")
        }
    }

    /**
     * 执行带自动释放的操作
     *
     * 自动获取引擎、执行操作、释放引擎
     *
     * @param block 要执行的操作
     * @return 操作结果
     */
    fun <T> use(block: (JavaScriptEngine) -> T): T {
        val engine = acquire()
        return try {
            block(engine)
        } finally {
            release(engine)
        }
    }

    /**
     * 清理所有引擎
     *
     * 销毁池中的所有引擎，释放资源
     */
    fun clear() {
        Log.d(TAG, "Clearing engine pool (size: ${availableEngines.size})")

        while (true) {
            val engine = availableEngines.poll() ?: break
            engine.dispose()
            createdEngines.decrementAndGet()
        }

        Log.d(TAG, "Engine pool cleared")
    }

    /**
     * 获取池统计信息
     *
     * @return 统计信息字符串
     */
    fun getStats(): String {
        return """
            JavaScript Engine Pool Stats:
            - Available engines: ${availableEngines.size}
            - Total created: ${createdEngines.get()}
            - Acquire count: ${acquireCount.get()}
            - Create count: ${createCount.get()}
            - Reuse count: ${reuseCount.get()}
            - Reuse rate: ${if (acquireCount.get() > 0) "%.2f%%".format(reuseCount.get() * 100.0 / acquireCount.get()) else "N/A"}
        """.trimIndent()
    }

    /**
     * 创建新的 JavaScript 引擎
     */
    private fun createEngine(): RhinoJavaScriptEngine {
        return RhinoJavaScriptEngine(
            httpClient = httpClient,
            cookieManager = cookieManager,
            cookieJar = cookieJar,
            androidContext = androidContext
        )
    }

    companion object {
        private const val TAG = "JavaScriptEnginePool"

        /**
         * 最大池大小
         *
         * 参考 Legado 的设置，保持 5 个引擎实例
         * 这个数量在性能和内存使用之间取得平衡
         */
        private const val MAX_POOL_SIZE = 5
    }
}
