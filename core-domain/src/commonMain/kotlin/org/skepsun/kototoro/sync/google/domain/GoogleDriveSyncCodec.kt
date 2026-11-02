package org.skepsun.kototoro.sync.google.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot

class DriveSyncProtocolException(cause: Exception? = null) :
    IllegalArgumentException("Unsupported Google Drive sync snapshot", cause)

/** A failed read must never be interpreted as an empty library and followed by an upload. */
object GoogleDriveSyncCodec {
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
        coerceInputValues = true
    }

    fun decode(bytes: ByteArray): GoogleDriveSyncSnapshot = try {
        val text = bytes.decodeToString()
        val probe = json.parseToJsonElement(text).jsonObject
        if (!GoogleDriveSyncSnapshot.isSupportedProtocol(
                probe["schema"]?.jsonPrimitive?.intOrNull,
                probe["namespace"]?.jsonPrimitive?.content,
                probe["semantic_schema"]?.jsonPrimitive?.intOrNull,
            )) throw DriveSyncProtocolException()
        json.decodeFromString(GoogleDriveSyncSnapshot.serializer(), text)
    } catch (error: DriveSyncProtocolException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw DriveSyncProtocolException(error)
    }

    fun encode(snapshot: GoogleDriveSyncSnapshot): ByteArray =
        json.encodeToString(GoogleDriveSyncSnapshot.serializer(), snapshot).encodeToByteArray()
}
