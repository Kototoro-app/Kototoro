package org.skepsun.kototoro.reader.novel.tts

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.reader.novel.tts.model.TtsHttpConfig

object LegadoTtsParser {

    private const val TAG = "LegadoTtsParser"

    fun parseList(jsonStr: String): List<TtsHttpConfig> {
        val results = mutableListOf<TtsHttpConfig>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i)
                if (obj != null) {
                    val config = parse(obj.toString())
                    if (config != null) results.add(config)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON array", e)
        }
        return results
    }

    fun parse(jsonStr: String): TtsHttpConfig? {
        return try {
            val json = JSONObject(jsonStr)

            // Make sure it's an HTTP type
            val type = json.optString("type", "http")
            if (type != "http") {
                Log.e(TAG, "Unsupported TTS type: $type. Expected 'http'")
                return null
            }

            val url = json.getString("url")
            val method = json.optString("method", "GET").uppercase()
            
            val emptyHeadersMap = mutableMapOf<String, String>()
            val headersMap = try {
                if (json.has("header") && json.getString("header").isNotBlank()) {
                    val headersObj = JSONObject(json.getString("header"))
                    val map = mutableMapOf<String, String>()
                    for (key in headersObj.keys()) {
                        map[key] = headersObj.getString(key)
                    }
                    map
                } else emptyHeadersMap
            } catch (e: Exception) {
                emptyHeadersMap
            }

            // A Legado source might store body template in "body" or just use GET params in URL
            val bodyTemplate = json.optString("body", "")

            // Since it's a generic JSON config, default speeds/voices are usually required 
            // inside the engine but can be omitted in the raw JSON layout if {{voice}} acts as proxy.
            val name = json.optString("name", "Unknown Source")
            Log.d(TAG, "Successfully parsed Legado config: $name")
            
            TtsHttpConfig(
                name = name,
                url = url,
                method = method,
                headers = headersMap,
                bodyTemplate = bodyTemplate,
                voice = json.optString("defaultVoice", "default_voice_id"),
                speed = 1.0f
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Legado TTS configuration: ${e.javaClass.simpleName}")
            null
        }
    }
}
