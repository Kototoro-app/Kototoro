package org.skepsun.kototoro.core.javascript

import android.util.Log
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.EvaluatorException

/**
 * JavaScript 语法验证器
 *
 * 在执行前检查 JavaScript 语法，报告语法错误
 *
 * **参考 Legado**: 查看 Legado 的 JavaScript 语法验证
 */
object JavaScriptSyntaxValidator {

    private const val TAG = "JavaScriptSyntaxValidator"

    /**
     * 验证 JavaScript 代码语法
     *
     * @param script JavaScript 代码
     * @return 验证结果
     */
    fun validate(script: String): ValidationResult {
        if (script.isBlank()) {
            return ValidationResult.success()
        }

        var context: RhinoContext? = null
        return try {
            context = RhinoContext.enter()
            context.optimizationLevel = -1

            // 尝试编译脚本以检查语法
            context.compileString(script, "syntax-check", 1, null)

            ValidationResult.success()
        } catch (e: EvaluatorException) {
            // 语法错误
            val message = "Syntax error at line ${e.lineNumber()}, column ${e.columnNumber()}: ${e.message}"
            Log.e(TAG, message, e)
            ValidationResult.failure(
                message = message,
                lineNumber = e.lineNumber(),
                columnNumber = e.columnNumber(),
                details = e.lineSource()
            )
        } catch (e: Exception) {
            // 其他错误
            val message = "Validation failed: ${e.message}"
            Log.e(TAG, message, e)
            ValidationResult.failure(message = message)
        } finally {
            try {
                context?.let { RhinoContext.exit() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exit Rhino context", e)
            }
        }
    }

    /**
     * 验证 JavaScript 表达式语法
     *
     * @param expression JavaScript 表达式
     * @return 验证结果
     */
    fun validateExpression(expression: String): ValidationResult {
        // 表达式验证与脚本验证相同
        return validate(expression)
    }

    /**
     * 验证结果
     */
    data class ValidationResult(
        val isValid: Boolean,
        val message: String? = null,
        val lineNumber: Int? = null,
        val columnNumber: Int? = null,
        val details: String? = null
    ) {
        companion object {
            fun success(): ValidationResult {
                return ValidationResult(isValid = true)
            }

            fun failure(
                message: String,
                lineNumber: Int? = null,
                columnNumber: Int? = null,
                details: String? = null
            ): ValidationResult {
                return ValidationResult(
                    isValid = false,
                    message = message,
                    lineNumber = lineNumber,
                    columnNumber = columnNumber,
                    details = details
                )
            }
        }

        /**
         * 获取格式化的错误消息
         */
        fun getFormattedMessage(): String {
            if (isValid) {
                return "Valid"
            }

            val sb = StringBuilder()
            sb.append(message ?: "Validation failed")

            if (lineNumber != null && columnNumber != null) {
                sb.append(" (line $lineNumber, column $columnNumber)")
            }

            if (details != null) {
                sb.append("\n  $details")
            }

            return sb.toString()
        }
    }
}

/**
 * 带语法检查的 JavaScript 引擎装饰器
 *
 * 在执行前检查 JavaScript 语法
 *
 * @param delegate 被装饰的 JavaScript 引擎
 * @param strictMode 是否启用严格模式（语法错误时抛出异常）
 */
class SyntaxCheckingJavaScriptEngine(
    private val delegate: JavaScriptEngine,
    private val strictMode: Boolean = true
) : JavaScriptEngine {

    override fun execute(script: String, context: JavaScriptContext): Any? {
        // 验证语法
        val validationResult = JavaScriptSyntaxValidator.validate(script)

        if (!validationResult.isValid) {
            val message = validationResult.getFormattedMessage()
            Log.e(TAG, "JavaScript syntax error: $message")

            if (strictMode) {
                throw JavaScriptSyntaxException(message, validationResult)
            } else {
                // 非严格模式：记录错误但继续执行
                Log.w(TAG, "Continuing execution despite syntax error")
            }
        }

        return delegate.execute(script, context)
    }

    override fun evaluate(expression: String, context: JavaScriptContext): Any? {
        // 验证表达式语法
        val validationResult = JavaScriptSyntaxValidator.validateExpression(expression)

        if (!validationResult.isValid) {
            val message = validationResult.getFormattedMessage()
            Log.e(TAG, "JavaScript expression syntax error: $message")

            if (strictMode) {
                throw JavaScriptSyntaxException(message, validationResult)
            } else {
                // 非严格模式：记录错误但继续执行
                Log.w(TAG, "Continuing evaluation despite syntax error")
            }
        }

        return delegate.evaluate(expression, context)
    }

    override fun registerGlobalObject(name: String, obj: Any) {
        delegate.registerGlobalObject(name, obj)
    }

    override fun dispose() {
        delegate.dispose()
    }

    companion object {
        private const val TAG = "SyntaxCheckingJavaScriptEngine"
    }
}

/**
 * JavaScript 语法异常
 */
class JavaScriptSyntaxException(
    message: String,
    val validationResult: JavaScriptSyntaxValidator.ValidationResult,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 扩展函数：创建带语法检查的 JavaScript 引擎
 */
fun JavaScriptEngine.withSyntaxCheck(strictMode: Boolean = true): SyntaxCheckingJavaScriptEngine {
    return SyntaxCheckingJavaScriptEngine(this, strictMode)
}
