package org.skepsun.kototoro.core.javascript

import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

object SharedJsScope {

    private const val TAG = "SharedJsScope"
    private const val CACHE_FOLDER = "shareJs"
    private const val MAX_SCOPES = 16
    private val scopeMap = object : LinkedHashMap<String, WeakReference<Scriptable>>(MAX_SCOPES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WeakReference<Scriptable>>?): Boolean =
            size > MAX_SCOPES
    }

    fun getScope(
        jsLib: String?,
        rhinoContext: RhinoContext,
        platform: LegadoScriptPlatform,
    ): Scriptable? {
        if (jsLib.isNullOrBlank()) return null
        val key = md5(jsLib)
        synchronized(this) {
            scopeMap.get(key)?.get()?.let { return it }
            val scope = buildScope(jsLib, rhinoContext, platform)
            scopeMap.put(key, WeakReference(scope))
            return scope
        }
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) return
        synchronized(this) {
            scopeMap.remove(md5(jsLib))
        }
    }

    private fun buildScope(
        jsLib: String,
        rhinoContext: RhinoContext,
        platform: LegadoScriptPlatform,
    ): Scriptable {
        val scope = rhinoContext.initStandardObjects()
        if (isJsonObject(jsLib)) {
            val jsMap = runCatching { JSONObject(jsLib) }.getOrNull()
            if (jsMap != null) {
                val keys = jsMap.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsMap.optString(key).trim()
                    if (isAbsUrl(value)) {
                        val script = loadRemoteJs(value, platform)
                        rhinoContext.evaluateString(
                            scope,
                            RhinoJavaScriptEngine.wrapScript(script),
                            "jsLib:$value",
                            1,
                            null,
                        )
                    }
                }
            }
        } else {
            rhinoContext.evaluateString(
                scope,
                RhinoJavaScriptEngine.wrapScript(jsLib),
                "jsLib",
                1,
                null,
            )
        }
        if (scope is ScriptableObject) {
            scope.sealObject()
        }
        return scope
    }

    private fun loadRemoteJs(
        url: String,
        platform: LegadoScriptPlatform,
    ): String {
        val cacheDir = File(platform.cacheDir(), CACHE_FOLDER).apply { mkdirs() }
        val cacheFile = File(cacheDir, md5(url))
        if (cacheFile.exists()) {
            return cacheFile.readText()
        }
        val body = runBlocking {
            platform.http.get(url).use { response ->
                if (!response.isSuccessful) {
                    error("下载 jsLib 失败: $url code=${response.code}")
                }
                response.body.string()
            }
        }
        runCatching { cacheFile.writeText(body) }
            .onFailure { Log.w(TAG, "缓存 jsLib 失败: $url", it) }
        return body
    }

    private fun isJsonObject(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}")
    }

    private fun isAbsUrl(value: String): Boolean {
        return value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("//")
    }

    private fun md5(value: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
