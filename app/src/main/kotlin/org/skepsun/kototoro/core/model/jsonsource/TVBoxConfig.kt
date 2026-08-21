package org.skepsun.kototoro.core.model.jsonsource

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * TVBox configuration model
 * Represents the root configuration for TVBox format
 */
@Serializable
data class TVBoxConfig(
    val sites: List<TVBoxSite> = emptyList(),
    val spider: String? = null,
    val wallpaper: String? = null,
    val lives: List<TVBoxLive>? = null,
    val parses: List<TVBoxParse>? = null,
    val flags: List<String>? = null,
    val ijk: List<TVBoxIjk>? = null,
    val ads: List<String>? = null,
)

/**
 * TVBox site configuration
 * Represents a single video site in TVBox format
 */
@Serializable
data class TVBoxSite(
    val key: String,
    val name: String,
    val type: Int,                     // 0=XML, 1=JSON, 3=Spider, 4=JS
    val api: String,
    val searchable: Int? = null,       // 0=不可搜索, 1=可搜索
    val quickSearch: Int? = null,      // 0=不支持快速搜索, 1=支持快速搜索
    val filterable: Int? = null,       // 0=不支持筛选, 1=支持筛选
    val ext: JsonElement? = null,      // 扩展配置，可以是字符串或对象
    val jar: String? = null,
    val playUrl: String? = null,
    val categories: List<String>? = null,
)

/**
 * TVBox live configuration
 */
@Serializable
data class TVBoxLive(
    val name: String,
    val type: Int,
    val url: String,
    val epg: String? = null,
    val logo: String? = null,
)

/**
 * TVBox parse configuration
 */
@Serializable
data class TVBoxParse(
    val name: String,
    val type: Int,
    val url: String,
    val ext: JsonElement? = null,
    val header: Map<String, String>? = null,
)

/**
 * TVBox IJK player configuration
 */
@Serializable
data class TVBoxIjk(
    val group: String,
    val options: List<TVBoxIjkOption>,
)

/**
 * TVBox IJK option
 */
@Serializable
data class TVBoxIjkOption(
    val category: Int,
    val name: String,
    val value: String,
)
