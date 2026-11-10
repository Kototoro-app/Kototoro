package org.skepsun.kototoro.core.parser.legado.model

/**
 * 规则执行期上下文。
 *
 * 使用通用 Map/Any 视图承载 book/chapter/item，避免核心运行时直接依赖应用实体。
 */
data class LegadoRuleContext(
    val source: LegadoSourceDescriptor? = null,
    val book: Map<String, Any?> = emptyMap(),
    val chapter: Map<String, Any?> = emptyMap(),
    val item: Any? = null,
    val extras: Map<String, Any?> = emptyMap(),
)
