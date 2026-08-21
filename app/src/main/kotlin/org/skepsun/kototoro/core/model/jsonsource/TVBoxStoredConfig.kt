package org.skepsun.kototoro.core.model.jsonsource

import org.json.JSONArray
import org.json.JSONObject

data class TVBoxStoredConfig(
    val schemaVersion: Int,
    val importType: String?,
    val site: TVBoxStoredSite,
    val root: TVBoxStoredRoot,
    val meta: TVBoxStoredMeta,
) {

    companion object {
        fun parse(rawConfig: String): TVBoxStoredConfig {
            val rootObject = JSONObject(rawConfig)
            return TVBoxStoredConfig(
                schemaVersion = rootObject.optInt("schemaVersion", 1),
                importType = rootObject.optStringOrNull("importType"),
                site = TVBoxStoredSite.from(rootObject.optJSONObject("site") ?: JSONObject()),
                root = TVBoxStoredRoot.from(rootObject.optJSONObject("root") ?: JSONObject()),
                meta = TVBoxStoredMeta.from(rootObject.optJSONObject("meta") ?: JSONObject()),
            )
        }
    }
}

data class TVBoxStoredSite(
    val key: String,
    val name: String,
    val type: Int,
    val api: String,
    val ext: Any?,
    val jar: String?,
    val playUrl: String?,
    val categories: List<String>,
    val searchable: Boolean,
    val quickSearch: Boolean,
    val filterable: Boolean,
    val staticHeaders: Map<String, String>,
    val raw: JSONObject,
) {

    companion object {
        fun from(jsonObject: JSONObject): TVBoxStoredSite {
            return TVBoxStoredSite(
                key = jsonObject.optString("key").trim(),
                name = jsonObject.optString("name").trim(),
                type = jsonObject.optInt("type", -1),
                api = jsonObject.optString("api").trim(),
                ext = jsonObject.optNullable("ext"),
                jar = jsonObject.optStringOrNull("jar"),
                playUrl = jsonObject.optStringOrNull("playUrl"),
                categories = jsonObject.optStringList("categories"),
                searchable = jsonObject.optInt("searchable", 1) != 0,
                quickSearch = jsonObject.optInt("quickSearch", 0) != 0,
                filterable = jsonObject.optInt("filterable", 0) != 0,
                staticHeaders = jsonObject.optHeaderMap("headers")
                    .ifEmpty { jsonObject.optHeaderMap("header") },
                raw = JSONObject(jsonObject.toString()),
            )
        }
    }
}

data class TVBoxStoredRoot(
    val spider: String?,
    val logo: String?,
    val wallpaper: String?,
    val headerRules: List<TVBoxStoredHeaderRule>,
    val raw: JSONObject,
) {

    companion object {
        fun from(jsonObject: JSONObject): TVBoxStoredRoot {
            val headers = jsonObject.optJSONArray("headers")
            val rules = buildList {
                if (headers != null) {
                    for (index in 0 until headers.length()) {
                        val item = headers.optJSONObject(index) ?: continue
                        val host = item.optString("host").trim()
                        val headerMap = item.optHeaderMap("header")
                        if (host.isBlank() || headerMap.isEmpty()) {
                            continue
                        }
                        add(TVBoxStoredHeaderRule(host, headerMap))
                    }
                }
            }
            return TVBoxStoredRoot(
                spider = jsonObject.optStringOrNull("spider"),
                logo = jsonObject.optStringOrNull("logo"),
                wallpaper = jsonObject.optStringOrNull("wallpaper"),
                headerRules = rules,
                raw = JSONObject(jsonObject.toString()),
            )
        }
    }
}

data class TVBoxStoredHeaderRule(
    val host: String,
    val headers: Map<String, String>,
)

data class TVBoxStoredMeta(
    val sourceLocator: String?,
    val sourceTitle: String?,
    val siteIndex: Int?,
    val siteKey: String?,
    val siteApi: String?,
) {

    companion object {
        fun from(jsonObject: JSONObject): TVBoxStoredMeta {
            return TVBoxStoredMeta(
                sourceLocator = jsonObject.optStringOrNull("sourceLocator"),
                sourceTitle = jsonObject.optStringOrNull("sourceTitle"),
                siteIndex = jsonObject.optNullableInt("siteIndex"),
                siteKey = jsonObject.optStringOrNull("siteKey"),
                siteApi = jsonObject.optStringOrNull("siteApi"),
            )
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    val value = optNullable(key)?.toString()?.trim().orEmpty()
    return value.ifBlank { null }
}

private fun JSONObject.optNullable(key: String): Any? {
    if (!has(key)) {
        return null
    }
    val value = opt(key)
    return if (value == null || value === JSONObject.NULL) {
        null
    } else {
        value
    }
}

private fun JSONObject.optNullableInt(key: String): Int? {
    val value = optNullable(key) ?: return null
    return when (value) {
        is Number -> value.toInt()
        else -> value.toString().toIntOrNull()
    }
}

private fun JSONObject.optStringList(key: String): List<String> {
    val value = optNullable(key) ?: return emptyList()
    return when (value) {
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                value.opt(index)?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        is Collection<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
        else -> emptyList()
    }
}

private fun JSONObject.optHeaderMap(key: String): Map<String, String> {
    val value = optNullable(key) ?: return emptyMap()
    return when (value) {
        is JSONObject -> value.toStringMap()
        else -> emptyMap()
    }
}

private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        val value = optNullable(key)?.toString()?.trim().orEmpty()
        if (value.isNotBlank()) {
            put(key, value)
        }
    }
}
