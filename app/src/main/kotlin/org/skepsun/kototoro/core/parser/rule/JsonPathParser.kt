package org.skepsun.kototoro.core.parser.rule

import android.util.Log
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.PathNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JSONPath 解析器
 *
 * 支持使用 JSONPath 表达式从 JSON 数据中提取内容
 * 参考 Legado 的 AnalyzeByJSonPath.kt 实现
 *
 * 语法示例:
 * - $.store.book[0].title - 获取第一本书的标题
 * - $.store.book[*].author - 获取所有书的作者
 * - $..price - 递归查找所有 price 字段
 * - $.store.book[?(@.price < 10)] - 过滤价格小于10的书
 */
@Singleton
class JsonPathParser @Inject constructor() {

    private val configuration: Configuration = Configuration.builder()
        .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
        .options(Option.SUPPRESS_EXCEPTIONS)
        .build()

    /**
     * 使用 JSONPath 表达式解析 JSON 字符串
     *
     * @param json JSON 字符串
     * @param path JSONPath 表达式
     * @return 提取的值（可能是字符串、列表或 null）
     */
    fun parse(json: String, path: String): Any? {
        if (json.isBlank() || path.isBlank()) {
            return null
        }

        return try {
            val jsonPath = JsonPath.compile(path)
            val result = jsonPath.read<Any>(json, configuration)

            // 处理不同类型的结果
            when (result) {
                null -> null
                is String -> result
                is Number -> result.toString()
                is Boolean -> result.toString()
                is List<*> -> {
                    // 如果是列表，返回第一个元素或整个列表的字符串表示
                    if (result.isEmpty()) {
                        null
                    } else if (result.size == 1) {
                        result[0]?.toString()
                    } else {
                        result.joinToString("\n") { it?.toString() ?: "" }
                    }
                }
                is Map<*, *> -> {
                    // 如果是对象，返回 JSON 字符串表示
                    result.toString()
                }
                else -> result.toString()
            }
        } catch (e: PathNotFoundException) {
            Log.d(TAG, "JSONPath not found: $path in JSON")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSONPath: $path", e)
            null
        }
    }

    /**
     * 使用 JSONPath 表达式解析 JSON 字符串，返回列表
     *
     * @param json JSON 字符串
     * @param path JSONPath 表达式
     * @return 提取的值列表
     */
    fun parseList(json: String, path: String): List<String> {
        if (json.isBlank() || path.isBlank()) {
            return emptyList()
        }

        return try {
            val jsonPath = JsonPath.compile(path)
            val result = jsonPath.read<Any>(json, configuration)

            when (result) {
                null -> emptyList()
                is List<*> -> result.mapNotNull { it?.toString() }
                else -> listOf(result.toString())
            }
        } catch (e: PathNotFoundException) {
            Log.d(TAG, "JSONPath not found: $path in JSON")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSONPath list: $path", e)
            emptyList()
        }
    }

    /**
     * 检测规则是否为 JSONPath 表达式
     *
     * @param rule 规则字符串
     * @return true 如果是 JSONPath 表达式
     */
    fun isJsonPath(rule: String): Boolean {
        return rule.startsWith("$.") || rule.startsWith("$[")
    }

    companion object {
        private const val TAG = "JsonPathParser"
    }
}
