@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.skepsun.kototoro.extensions.repo

import kotlinx.serialization.json.*
import kotlinx.serialization.protobuf.ProtoBuf

private val StoreJson = Json { ignoreUnknownKeys = true }

/** Protobuf JSON uses prefixed enum names and quoted int64 values; never route IDs through Double. */
fun decodeExtensionStoreIndex(bytes: ByteArray, protobuf: Boolean): ExtensionStoreIndex = if (protobuf) {
    ProtoBuf.decodeFromByteArray(ExtensionStoreIndex.serializer(), bytes)
} else {
    val root = StoreJson.parseToJsonElement(bytes.decodeToString()).jsonObject
    val list = root["extensionList"]?.takeUnless { it is JsonNull }?.let { normalizeList(it.jsonObject) }
    StoreJson.decodeFromJsonElement(ExtensionStoreIndex.serializer(), JsonObject(root.toMutableMap().apply {
        if (list != null) put("extensionList", list)
    }))
}

fun decodeExtensionStoreList(bytes: ByteArray, protobuf: Boolean): ExtensionStoreIndex.ExtensionList = if (protobuf) {
    ProtoBuf.decodeFromByteArray(ExtensionStoreIndex.ExtensionList.serializer(), bytes)
} else {
    StoreJson.decodeFromJsonElement(ExtensionStoreIndex.ExtensionList.serializer(),
        normalizeList(StoreJson.parseToJsonElement(bytes.decodeToString()).jsonObject))
}

private fun normalizeList(list: JsonObject): JsonObject = JsonObject(list.toMutableMap().apply {
    val rows = list["extensions"]?.jsonArray ?: return@apply
    put("extensions", JsonArray(rows.map { element ->
        val row = element.jsonObject
        JsonObject(row.toMutableMap().apply {
            row["contentWarning"]?.jsonPrimitive?.content?.let { value ->
                val name = value.toIntOrNull()?.let { ExtensionStoreIndex.ContentWarning.entries.getOrNull(it)?.name }
                    ?: value.removePrefix("CONTENT_WARNING_")
                put("contentWarning", JsonPrimitive(name))
            }
        })
    }))
})

/**
 * The legacy `index.min.json` array (Keiyoushi before index.pb, Aniyomi and Tsundoku repositories): rows carry an APK
 * file name, a version name whose leading part is the extensions-lib, and string or number source ids. The result is the
 * same model as the modern index, APK-only, under [repositoryName] since the legacy file has no header.
 */
fun decodeLegacyExtensionIndex(bytes: ByteArray, repositoryName: String): ExtensionStoreIndex {
    val rows = StoreJson.parseToJsonElement(bytes.decodeToString()).jsonArray
    val extensions = rows.map { element ->
        val row = element.jsonObject
        fun text(key: String) = row[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        val packageName = text("pkg")
        val version = text("version")
        ExtensionStoreIndex.Extension(
            name = text("name").replace(Regex("^(Aniyomi|Tachiyomi|Tsundoku|Mihon): "), ""),
            packageName = packageName,
            resources = ExtensionStoreIndex.Resources(apkUrl = "apk/" + text("apk"), iconUrl = "icon/$packageName.png"),
            extensionLib = version.substringBeforeLast('.', version),
            versionCode = text("code").toLongOrNull() ?: 0,
            versionName = version,
            contentWarning = if (text("nsfw") == "1") ExtensionStoreIndex.ContentWarning.NSFW
                else ExtensionStoreIndex.ContentWarning.SAFE,
            sources = row["sources"]?.jsonArray.orEmpty().map { source ->
                val fields = source.jsonObject
                fun field(key: String) = fields[key]?.jsonPrimitive?.contentOrNull.orEmpty()
                ExtensionStoreIndex.Source(
                    id = field("id").toLongOrNull() ?: 0, name = field("name"), language = field("lang"),
                    homeUrl = field("baseUrl"),
                )
            },
        )
    }
    return ExtensionStoreIndex(
        name = repositoryName, badgeLabel = "", signingKey = "", contact = ExtensionStoreIndex.Contact(""),
        extensionList = ExtensionStoreIndex.ExtensionList(extensions),
    )
}