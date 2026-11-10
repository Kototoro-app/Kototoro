package org.skepsun.kototoro.core.parser.legado

/**
 * 与 legado-with-MD3 对齐：用于脚本侧可预期、且不需要冗长堆栈的运行时异常。
 */
open class NoStackTraceException(message: String) : Exception(message) {

    override fun fillInStackTrace(): Throwable {
        stackTrace = EMPTY_STACK_TRACE
        return this
    }

    companion object {
        private val EMPTY_STACK_TRACE = emptyArray<StackTraceElement>()
    }
}
