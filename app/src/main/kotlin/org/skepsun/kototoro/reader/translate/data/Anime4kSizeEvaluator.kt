package org.skepsun.kototoro.reader.translate.data

import kotlin.math.roundToInt

object Anime4kSizeEvaluator {
    fun evaluate(expression: String, textureSizes: Map<String, Pair<Int, Int>>): Int {
        val tokens = expression.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val stack = mutableListOf<Float>()

        for (token in tokens) {
            when (token) {
                "+" -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(a + b)
                }
                "-" -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(a - b)
                }
                "*" -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(a * b)
                }
                "/" -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(a / b)
                }
                else -> {
                    if (token.endsWith(".w") || token.endsWith(".width")) {
                        val base = token.substringBefore(".")
                        stack.add((textureSizes[base]?.first ?: 0).toFloat())
                    } else if (token.endsWith(".h") || token.endsWith(".height")) {
                        val base = token.substringBefore(".")
                        stack.add((textureSizes[base]?.second ?: 0).toFloat())
                    } else {
                        token.toFloatOrNull()?.let { stack.add(it) }
                    }
                }
            }
        }
        return if (stack.isNotEmpty()) stack.last().roundToInt() else 0
    }
}
