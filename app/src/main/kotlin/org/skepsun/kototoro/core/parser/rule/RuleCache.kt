package org.skepsun.kototoro.core.parser.rule

import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache for compiled rules to improve performance
 * 
 * Rules are compiled once and cached for reuse. This significantly improves
 * performance when the same rules are used repeatedly.
 * 
 * The cache size has been optimized based on typical usage patterns:
 * - Default size increased to 500 to accommodate more sources
 * - Includes cache hit rate monitoring for performance analysis
 * - Supports cache prewarming for frequently used rules
 * 
 * @param maxSize Maximum number of rules to cache (default: 500, optimized from 200)
 */
@Singleton
class RuleCache @Inject constructor() {
    private val cache = LruCache<String, CompiledRule>(500)
    
    /**
     * Get a compiled rule from cache, or compile and cache it if not present
     * 
     * @param rule The rule string to compile
     * @param compiler Function to compile the rule if not in cache
     * @return The compiled rule
     */
    fun getOrCompile(rule: String, compiler: (String) -> CompiledRule): CompiledRule {
        return cache.get(rule) ?: compiler(rule).also {
            cache.put(rule, it)
        }
    }
    
    /**
     * Prewarm the cache with commonly used rules
     * 
     * This method compiles and caches a list of rules before they are needed,
     * improving performance for the first use of these rules.
     * 
     * @param rules List of rule strings to prewarm
     * @param compiler Function to compile each rule
     */
    fun prewarm(rules: List<String>, compiler: (String) -> CompiledRule) {
        rules.forEach { rule ->
            if (cache.get(rule) == null) {
                try {
                    val compiled = compiler(rule)
                    cache.put(rule, compiled)
                } catch (e: Exception) {
                    // Ignore errors during prewarming - rules will be compiled on demand
                }
            }
        }
    }
    
    /**
     * Clear all cached rules
     */
    fun clear() {
        cache.evictAll()
    }
    
    /**
     * Get the current cache size
     */
    fun size(): Int = cache.size()
    
    /**
     * Get cache statistics (for monitoring)
     */
    fun getStats(): CacheStats {
        return CacheStats(
            size = cache.size(),
            maxSize = cache.maxSize(),
            hitCount = cache.hitCount(),
            missCount = cache.missCount(),
            putCount = cache.putCount(),
            evictionCount = cache.evictionCount(),
        )
    }
    
    /**
     * Log cache statistics for monitoring
     * 
     * This method logs the current cache hit rate and other statistics,
     * which can be used to tune cache size and identify performance issues.
     */
    fun logStats() {
        val stats = getStats()
        android.util.Log.d(
            "RuleCache",
            "Cache stats: size=${stats.size}/${stats.maxSize}, " +
                "hitRate=${String.format("%.2f%%", stats.hitRate * 100)}, " +
                "hits=${stats.hitCount}, misses=${stats.missCount}, " +
                "evictions=${stats.evictionCount}"
        )
    }
}

/**
 * Statistics about cache performance
 */
data class CacheStats(
    val size: Int,
    val maxSize: Int,
    val hitCount: Int,
    val missCount: Int,
    val putCount: Int,
    val evictionCount: Int,
) {
    /**
     * Calculate cache hit rate (0.0 to 1.0)
     */
    val hitRate: Double
        get() {
            val total = hitCount + missCount
            return if (total > 0) hitCount.toDouble() / total else 0.0
        }
}
