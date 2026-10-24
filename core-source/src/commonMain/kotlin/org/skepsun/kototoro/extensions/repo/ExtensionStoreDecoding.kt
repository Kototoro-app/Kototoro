@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.skepsun.kototoro.extensions.repo

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.GzipSource
import okio.buffer

private val StoreJson = Json { ignoreUnknownKeys = true }

fun gunzipExtensionStoreBytes(bytes: ByteArray): ByteArray {
    if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
    val source = GzipSource(Buffer().write(bytes)).buffer()
    return try {
        source.readByteArray()
    } finally {
        source.close()
    }
}

/** Protobuf JSON uses prefixed enum names and quoted int64 values; never route IDs through Double. */
fun decodeExtensionStoreIndex(bytes: ByteArray, protobuf: Boolean): ExtensionStoreIndex {
    val payload = gunzipExtensionStoreBytes(bytes)
    return if (protobuf) {
        decodeStoreProtobuf(
            primary = { ProtoBuf.decodeFromByteArray(ExtensionStoreIndex.serializer(), payload) },
            aniyomi = {
                ProtoBuf.decodeFromByteArray(AniyomiExtensionStoreIndex.serializer(), payload).toStoreIndex()
            },
            hasSources = { index -> index.extensionList?.extensions.orEmpty().any { it.sources.isNotEmpty() } },
        )
    } else {
        val root = StoreJson.parseToJsonElement(payload.decodeToString()).jsonObject
        val meta = root["meta"]?.takeUnless { it is JsonNull }?.jsonObject
        if (meta != null && root["name"] == null) {
            val name = meta["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val shortName = meta["shortName"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val website = meta["website"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val signingKey = meta["signingKeyFingerprint"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val indexV2 = root["index_v2"]?.jsonPrimitive?.contentOrNull ?: "index.min.json"
            return ExtensionStoreIndex(
                name = name,
                badgeLabel = shortName,
                signingKey = signingKey,
                contact = ExtensionStoreIndex.Contact(website = website),
                extensionListUrl = indexV2,
            )
        }
        val list = root["extensionList"]?.takeUnless { it is JsonNull }?.let { normalizeList(it.jsonObject) }
        StoreJson.decodeFromJsonElement(ExtensionStoreIndex.serializer(), JsonObject(root.toMutableMap().apply {
            if (list != null) put("extensionList", list)
        }))
    }
}

fun decodeExtensionStoreList(bytes: ByteArray, protobuf: Boolean): ExtensionStoreIndex.ExtensionList {
    val payload = gunzipExtensionStoreBytes(bytes)
    return if (protobuf) {
        decodeStoreProtobuf(
            primary = { ProtoBuf.decodeFromByteArray(ExtensionStoreIndex.ExtensionList.serializer(), payload) },
            aniyomi = {
                ProtoBuf.decodeFromByteArray(AniyomiExtensionStoreIndex.ExtensionList.serializer(), payload).toStoreList()
            },
            hasSources = { list -> list.extensions.any { it.sources.isNotEmpty() } },
        )
    } else {
        StoreJson.decodeFromJsonElement(ExtensionStoreIndex.ExtensionList.serializer(),
            normalizeList(StoreJson.parseToJsonElement(payload.decodeToString()).jsonObject))
    }
}

/** Without a torrent flag, the field-8 decoder succeeds on Aniyomi but silently drops its field-9 sources. */
private inline fun <T> decodeStoreProtobuf(
    primary: () -> T,
    aniyomi: () -> T,
    hasSources: (T) -> Boolean,
): T {
    val original = try {
        primary()
    } catch (_: SerializationException) {
        return aniyomi()
    }
    if (hasSources(original)) return original
    val alternative = try {
        aniyomi()
    } catch (_: SerializationException) {
        return original
    }
    return if (hasSources(alternative)) alternative else original
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
    val payload = gunzipExtensionStoreBytes(bytes)
    val rows = StoreJson.parseToJsonElement(payload.decodeToString()).jsonArray
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
