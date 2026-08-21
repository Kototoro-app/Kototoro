package org.skepsun.kototoro.core.js

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses Venera-style JavaScript sources using QuickJS.
 *
 * Only metadata extraction is implemented here; runtime bindings (Network/UI)
 * are intentionally stubbed to keep evaluation side-effect free.
 */
@Singleton
class JSSourceParser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jsEngine: JSEngine,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val classNameRegex = Regex("""class\s+([A-Za-z0-9_]+)\s+extends\s+ComicSource""")

    private val metadataBootstrap = """
		;(() => {
			// Minimal host stubs to allow class definition without side effects.
			const __store = {};
			globalThis.sendMessage = function(msg) {
				const m = msg?.method;
				if (m === 'save_data') {
					const k = msg.key ?? '__default';
					__store[k] = __store[k] || {};
					__store[k][msg.data_key] = msg.data;
					return true;
				}
				if (m === 'load_data') {
					const k = msg.key ?? '__default';
					return (__store[k] || {})[msg.data_key];
				}
				if (m === 'delete_data') {
					const k = msg.key ?? '__default';
					if (__store[k]) delete __store[k][msg.data_key];
					return true;
				}
				if (m === 'save_setting') {
					const k = '__settings__';
					__store[k] = __store[k] || {};
					__store[k][msg.setting_key ?? msg.data_key] = msg.data;
					return true;
				}
				if (m === 'load_setting') {
					const k = '__settings__';
					return (__store[k] || {})[msg.setting_key ?? msg.data_key];
				}
				if (m === 'isLogged') {
					return false;
				}
				return {};
			};
			globalThis.randomInt = function(min, max) { return min ?? 0; };
			globalThis.Network = {
				get: (url, headers) => ({ status: 0, body: "", url, headers }),
				post: (url, headers, body) => ({ status: 0, body: "", url, headers, payload: body })
			};
			globalThis.UI = {
				showToast: () => {},
				showInputDialog: () => "",
				showConfirmDialog: () => true,
			};
			globalThis.Convert = {
				encodeUtf8: (v) => v,
				decodeUtf8: (v) => v,
				encodeBase64: () => "",
				decodeBase64: () => "",
			};
			globalThis.Comic = function(meta) { return meta; };
			class __StubComicSource {
				constructor() { this.data = {}; }
				saveData(k, v) { this.data[k] = v; }
				loadData(k) { return this.data[k]; }
				deleteData(k) { delete this.data[k]; }
				get settings() { return this.data.settings ?? {}; }
				saveSetting(k, v) { this.saveData(k, v); }
				loadSetting(k) { return this.loadData(k); }
			}
			globalThis.ComicSource = __StubComicSource;
		})();
    """.trimIndent()

    private fun buildMetadataExtractor(preferredClass: String?): String {
        val preferredExpr = preferredClass?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
            ?.let { "(typeof $it !== 'undefined' ? $it : null)" }
            ?: "null"
        return """
		;(() => {
			const candidates = [];
			const fromName = $preferredExpr;
			if (fromName) candidates.push(fromName);
			for (const v of Object.values(globalThis)) {
				if (typeof v === "function" && v.prototype && v.prototype instanceof ComicSource && v !== ComicSource) {
					candidates.push(v);
				}
			}
			const Candidate = candidates.find(v => v) || null;
			if (!Candidate) {
				return JSON.stringify({ error: "ComicSource subclass not found" });
			}
			const instance = new Candidate();
			const homepage = instance.apiUrl
				|| instance.api_base
				|| instance.baseUrl
				|| instance.base_url
				|| instance.defaultBaseUrl
				|| instance.baseURL
				|| instance.url
				|| "";
			const meta = {
				key: instance.key || instance.name || "js_" + Math.random().toString(16).slice(2),
				name: instance.name || instance.key || "JS Source",
				version: instance.version || "",
				minAppVersion: instance.minAppVersion || "",
				lang: instance.lang || instance.language || "",
				homepage: homepage,
				description: instance.description || "",
			};
			return JSON.stringify(meta);
		})();
        """.trimIndent()
    }

    @Serializable
    data class JsSourceMetadata(
        val key: String,
        val name: String,
        val version: String? = null,
        val minAppVersion: String? = null,
        val lang: String? = null,
        val homepage: String? = null,
        val description: String? = null,
    )

    /**
     * Extract metadata from a JS source.
     */
    fun parseMetadata(js: String): Result<JsSourceMetadata> = runCatching {
        val preferredClass = classNameRegex.find(js)?.groupValues?.getOrNull(1)
        val script = buildString {
            append(metadataBootstrap)
            append("\n")
            append(js)
            append("\n")
            append(buildMetadataExtractor(preferredClass))
        }

        val result = jsEngine.evaluate(script, "js-source-import", String::class.java)
            ?: throw IllegalStateException("JS returned null metadata")

        val element = json.parseToJsonElement(result)
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("Metadata is not an object")

        obj["error"]?.jsonPrimitive?.contentOrNull?.let { err ->
            throw IllegalArgumentException("JS metadata error: $err")
        }

        if (!obj.containsKey("key") || !obj.containsKey("name")) {
            throw IllegalArgumentException("Metadata missing key/name")
        }

        val meta = json.decodeFromJsonElement(JsSourceMetadata.serializer(), obj)
        if (meta.name.isBlank()) throw IllegalArgumentException("name is empty")
        if (meta.key.isBlank()) throw IllegalArgumentException("key is empty")
        meta
    }

    /**
     * Persist JS script for debugging or later reload.
     */
    fun saveSource(js: String, fileName: String): File {
        val dir = File(context.filesDir, "js_sources")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName).also { it.writeText(js) }
    }
}
