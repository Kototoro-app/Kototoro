package org.skepsun.kototoro.core.parser.legado.model

/**
 * 运行时使用的精简 Legado 源描述。
 *
 * 该模型只保留规则执行所需字段，避免把 Room/Parcelable 等宿主细节带入 runtime 边界。
 */
data class LegadoSourceDescriptor(
    val key: String,
    val name: String,
    val baseUrl: String? = null,
    val headerRule: String? = null,
    val loginUrlRule: String? = null,
    val loginUiRule: String? = null,
    val jsLib: String? = null,
    val enabledCookieJar: Boolean = true,
    val concurrentRate: String? = null,
    val exploreUrl: String? = null,
    val searchUrl: String? = null,
)
