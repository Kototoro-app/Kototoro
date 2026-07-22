package org.skepsun.kototoro.core.lnreader

import android.util.Log
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Bridge between Kotlin and LNReader JavaScript plugins.
 * Mirrors IReader's JSPluginBridge — calls JS methods via evaluateScript()
 * with IIFE + Promise polling pattern.
 * 
 * Each plugin method:
 * 1. Wraps the call in an async IIFE
 * 2. Stores result in globalThis.__result_{id}
 * 3. Polls with exponential backoff until done
 * 4. Parses JS result into Kotlin data classes
 */
class LNReaderPluginBridge(
	private val qjs: QuickJs,
	private val pluginId: String
) {
	companion object {
		private const val TAG = "LNReaderPluginBridge"
		private const val DEFAULT_TIMEOUT_MS = 30_000L
		private const val MAX_POLL_ATTEMPTS = 150
	}
	
	private val sanitizedId = pluginId.replace(Regex("[^a-zA-Z0-9_]"), "_")
	private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
	
// Empty line
	
	// ==================== Plugin Metadata ====================
	
	/**
	 * Extract plugin metadata from JS context.
	 */
	suspend fun getPluginMetadata(): LNReaderPluginMetadata {
		val metadataJson = qjs.evaluate<String>(
			"""
			(function() {
				var plugin = globalThis.__plugin_${sanitizedId};
				if (!plugin) return JSON.stringify({error: 'plugin not found'});
				return JSON.stringify({
					id: plugin.id || '',
					name: plugin.name || 'Unknown',
					site: plugin.site || '',
					version: plugin.version || '1.0.0',
					icon: plugin.icon || '',
					lang: plugin.lang || 'en'
				});
			})();
			""".trimIndent(),
			"<metadata>"
		) ?: "{}"
		
		val obj = json.parseToJsonElement(metadataJson).jsonObject
		return LNReaderPluginMetadata(
			id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: pluginId,
			name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: "Unknown",
			site = obj["site"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: "",
			version = obj["version"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: "1.0.0",
			lang = obj["lang"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: "en",
			icon = obj["icon"]?.jsonPrimitive?.contentOrNull?.trim('"') ?: ""
		)
	}
	
	/**
	 * Extracts the plugin's exported `filters` object statically.
	 * Executes synchronously since `plugin.filters` is a static JS object properties.
	 */
	suspend fun getFilters(): List<LNReaderFilter> {
		val script = """
			(function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin || !plugin.filters) return JSON.stringify({ success: true, data: [] });
					
					var result = [];
					for (var key in plugin.filters) {
						var filterObj = plugin.filters[key];
						if (!filterObj || typeof filterObj !== 'object') continue;
						
						var opts = [];
						if (Array.isArray(filterObj.options)) {
							opts = filterObj.options.map(function(o) { 
								return { label: String(o.label||''), value: String(o.value||'') };
							});
						}
						
						result.push({
							key: String(key),
							label: String(filterObj.label || key),
							type: String(filterObj.type || 'picker'),
							options: opts
						});
					}
					return JSON.stringify({ success: true, data: result });
				} catch (e) {
					return JSON.stringify({ success: false, error: String(e) });
				}
			})();
		""".trimIndent()
		
		val resultJson = runCatching { qjs.evaluate<String>(script, "<getFilters>") }.getOrNull() ?: return emptyList()
		return try {
			val obj = json.parseToJsonElement(resultJson).jsonObject
			if (obj["success"]?.jsonPrimitive?.booleanOrNull == true) {
				val dataArr = obj["data"]?.jsonArray ?: JsonArray(emptyList())
				dataArr.mapNotNull {
					val f = it.jsonObject
					val key = f["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
					val label = f["label"]?.jsonPrimitive?.contentOrNull ?: key
					val type = f["type"]?.jsonPrimitive?.contentOrNull ?: "picker"
					val opts = f["options"]?.jsonArray?.mapNotNull { optElement ->
						val optObj = optElement.jsonObject
						val oLabel = optObj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
						val oValue = optObj["value"]?.jsonPrimitive?.contentOrNull ?: ""
						LNReaderFilterOption(oLabel, oValue)
					} ?: emptyList()
					LNReaderFilter(key, label, type, opts)
				}
			} else emptyList()
		} catch (e: Exception) {
			Log.e(TAG, "Failed to parse filters from JS: ${e.message}")
			emptyList()
		}
	}
	
	// ==================== Content Methods ====================
	
	/**
	 * Call plugin.popularNovels(page, {filters}).
	 * Returns list of novel items.
	 */
	suspend fun popularNovels(page: Int, selectedFilters: Map<String, String>? = null): List<LNReaderNovelItem> {
		val resultVar = "__popularResult_${sanitizedId}"
		val filterOverrides = if (selectedFilters.isNullOrEmpty()) "" else {
			selectedFilters.entries.joinToString("\n") { (k, v) ->
				"if(defaultFilters['${escapeForJS(k)}']) defaultFilters['${escapeForJS(k)}'].value = '${escapeForJS(v)}';"
			}
		}
		val script = """
			(async function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin) throw new Error('Plugin not found');
					if (typeof plugin.popularNovels !== 'function') throw new Error('popularNovels not found');
					
					var defaultFilters = {};
				if (plugin.filters) {
					// Pass the full filter objects so plugins can access .value, .type, etc.
					for (var key in plugin.filters) {
						if (plugin.filters[key]) {
							defaultFilters[key] = JSON.parse(JSON.stringify(plugin.filters[key])); // deep copy
						} else {
							defaultFilters[key] = { value: '' };
						}
					}
				}
				$filterOverrides
				var result = await plugin.popularNovels($page, { showLatestNovels: false, filters: defaultFilters });
					globalThis.$resultVar = { success: true, data: JSON.stringify(result || []) };
				} catch (error) {
					console.error("PLUGIN EVAL ERROR: " + String(error) + "\nSTACK: " + (error ? error.stack : "null"));
					globalThis.$resultVar = { 
						success: false, 
						error: (function() {
							if (error === null) return 'Plugin rejected with null';
							if (error === undefined) return 'Plugin rejected with undefined';
							var message = error && error.message ? String(error.message) : String(error);
							return message + (error && error.stack ? "\n" + error.stack : "");
						})()
					};
				}
			})();
		""".trimIndent()
		
		val resultJson = executeAsyncAndPoll(script, resultVar, "popularNovels")
		return parseNovelList(resultJson)
	}
	
	/**
	 * Call plugin.searchNovels(query, page).
	 */
	suspend fun searchNovels(query: String, page: Int): List<LNReaderNovelItem> {
		val resultVar = "__searchResult_${sanitizedId}"
		val escapedQuery = escapeForJS(query)
		val script = """
			(async function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin) throw new Error('Plugin not found');
					if (typeof plugin.searchNovels !== 'function') throw new Error('searchNovels not found');
					var result = await plugin.searchNovels('$escapedQuery', $page);
					globalThis.$resultVar = { success: true, data: JSON.stringify(result || []) };
				} catch (error) {
					globalThis.$resultVar = { 
						success: false, 
						error: (function() {
							if (error === null) return 'Plugin rejected with null';
							if (error === undefined) return 'Plugin rejected with undefined';
							var message = error && error.message ? String(error.message) : String(error);
							return message + (error && error.stack ? "\n" + error.stack : "");
						})()
					};
				}
			})();
		""".trimIndent()
		
		val resultJson = executeAsyncAndPoll(script, resultVar, "searchNovels")
		return parseNovelList(resultJson)
	}
	
	/**
	 * Call plugin.parseNovel(url).
	 * Returns novel details with chapter list.
	 */
	suspend fun parseNovel(novelPath: String): LNReaderNovelDetails {
		val resultVar = "__parseNovelResult_${sanitizedId}"
		val escapedPath = escapeForJS(novelPath)
		val script = """
			(async function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin) throw new Error('Plugin not found');
					if (typeof plugin.parseNovel !== 'function') throw new Error('parseNovel not found');
					var result = await plugin.parseNovel('$escapedPath');
					globalThis.$resultVar = { success: true, data: JSON.stringify(result || {}) };
				} catch (error) {
					globalThis.$resultVar = { 
						success: false, 
						error: (function() {
							if (error === null) return 'Plugin rejected with null';
							if (error === undefined) return 'Plugin rejected with undefined';
							var message = error && error.message ? String(error.message) : String(error);
							return message + (error && error.stack ? "\n" + error.stack : "");
						})()
					};
				}
			})();
		""".trimIndent()
		
		val resultJson = executeAsyncAndPoll(script, resultVar, "parseNovel")
		return parseNovelDetails(resultJson)
	}

	/**
	 * Call plugin.parsePage(novelPath, page) to fetch chapters for a single page.
	 * Many LNReader plugins paginate their chapter lists and return them via this method.
	 */
	suspend fun parsePage(novelPath: String, page: Int): List<LNReaderChapter> {
		val resultVar = "__parsePageResult_${sanitizedId}_$page"
		val escapedPath = escapeForJS(novelPath)
		val script = """
			(async function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin) throw new Error('Plugin not found');
					if (typeof plugin.parsePage !== 'function') {
						globalThis.$resultVar = { success: true, data: '[]' };
						return;
					}
					var res = await plugin.parsePage('$escapedPath', $page);
					globalThis.$resultVar = { success: true, data: JSON.stringify(res || []) };
				} catch (error) {
					globalThis.$resultVar = { 
						success: false, 
						error: (function() {
							if (error === null) return 'Plugin rejected with null';
							if (error === undefined) return 'Plugin rejected with undefined';
							var message = error && error.message ? String(error.message) : String(error);
							return message + (error && error.stack ? "\n" + error.stack : "");
						})()
					};
				}
			})();
		""".trimIndent()
		
		val resultJson = executeAsyncAndPoll(script, resultVar, "parsePage[$page]")
		return try {
			val element = json.parseToJsonElement(resultJson)
			val array = if (element is JsonObject) {
				element["chapters"]?.jsonArray ?: JsonArray(emptyList())
			} else {
				element.jsonArray
			}
			
			array.mapNotNull { chElement ->
				val chObj = chElement.jsonObject
				val chName = chObj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				val chPath = chObj["path"]?.jsonPrimitive?.contentOrNull
					?: chObj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				val releaseTime = chObj["releaseTime"]?.jsonPrimitive?.contentOrNull
				val chapterNumber = chObj["chapterNumber"]?.jsonPrimitive?.contentOrNull
					?: chObj["chapterNumber"]?.jsonPrimitive?.intOrNull?.toString()
				LNReaderChapter(name = chName, path = chPath, releaseTime = releaseTime, chapterNumber = chapterNumber)
			}
		} catch (e: Exception) {
			Log.e(TAG, "Failed to parse parsePage[$page] json: ${e.message}\nRaw: ${resultJson.take(500)}")
			emptyList()
		}
	}
	
	/**
	 * Call plugin.parseChapter(chapterUrl).
	 * Returns chapter HTML text content.
	 */
	suspend fun parseChapter(chapterPath: String): String {
		val resultVar = "__parseChapterResult_${sanitizedId}"
		val escapedChapterPath = escapeForJS(chapterPath)
		val script = """
			(async function() {
				try {
					var plugin = globalThis.__plugin_${sanitizedId};
					if (!plugin) throw new Error('Plugin not found');
					if (typeof plugin.parseChapter !== 'function') throw new Error('parseChapter not found');
					var result = await plugin.parseChapter('$escapedChapterPath');
					var text = '';
					if (typeof result === 'string') {
						text = result;
					} else if (result && result.chapterText) {
						text = result.chapterText;
					} else if (result && result.text) {
						text = result.text;
					} else {
						text = JSON.stringify(result || '');
					}
					globalThis.$resultVar = { success: true, data: text };
				} catch (error) {
					globalThis.$resultVar = { 
						success: false, 
						error: (function() {
							if (error === null) return 'Plugin rejected with null';
							if (error === undefined) return 'Plugin rejected with undefined';
							var message = error && error.message ? String(error.message) : String(error);
							return message + (error && error.stack ? "\n" + error.stack : "");
						})()
					};
				}
			})();
		""".trimIndent()
		
		return withTimeout(DEFAULT_TIMEOUT_MS) {
			qjs.evaluate<Any?>(script, "<parseChapter>")
			
			var attempts = 0
			var waitTime = 10L
			
			while (attempts < MAX_POLL_ATTEMPTS) {
				delay(waitTime)
				
				processCheerioQueue()
				
				if (attempts % 5 == 0 || attempts < 10) {
					val checkResult = qjs.evaluate<String?>(
						"(function() { var r = globalThis.$resultVar; if (!r) return null; return JSON.stringify(r); })()",
						"<check>"
					)
					
					if (checkResult != null) {
						val obj = json.parseToJsonElement(checkResult).jsonObject
						val success = (obj["success"] as? JsonPrimitive)?.content?.toBoolean() ?: false
						if (success) {
							val data = obj["data"]?.jsonPrimitive?.contentOrNull ?: ""
							// Clean up
							runCatching { qjs.evaluate<Any?>("delete globalThis.$resultVar;", "<cleanup>") }
							return@withTimeout data
						} else {
							val error = obj["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
							throw LNReaderJSException("parseChapter failed: $error")
						}
					}
				}
				
				attempts++
				if (waitTime < 200) waitTime = (waitTime * 1.2).toLong().coerceAtMost(200)
			}
			
			throw LNReaderJSException("parseChapter timeout")
		}
	}
	
	// ==================== Internal Helpers ====================
	
	/**
	 * Execute an async JS script, poll for result in global variable, return JSON string.
	 * Mirrors IReader's JSPluginBridge polling pattern with exponential backoff.
	 */
	private suspend fun executeAsyncAndPoll(
		script: String,
		resultVar: String,
		methodName: String
	): String {
		return withTimeout(DEFAULT_TIMEOUT_MS) {
			qjs.evaluate<Any?>(script, "<$methodName>")
			
			var attempts = 0
			var waitTime = 10L
			
			while (attempts < MAX_POLL_ATTEMPTS) {
				delay(waitTime)
				
				// Actively process any cheerio node queries offloaded by NativeCheerioBridge
				processCheerioQueue()
				
				// Check every 5 attempts or first 10 to reduce engine calls
				if (attempts % 5 == 0 || attempts < 10) {
					val checkResult = qjs.evaluate<String?>(
						"(function() { var r = globalThis.$resultVar; if (!r) return null; return JSON.stringify(r); })()",
						"<check>"
					)
					
					if (checkResult != null) {
						val obj = json.parseToJsonElement(checkResult).jsonObject
						val success = (obj["success"] as? JsonPrimitive)?.content?.toBoolean() ?: false
						if (success) {
							val data = obj["data"]?.jsonPrimitive?.contentOrNull ?: "[]"
							// Clean up global variable
							runCatching { qjs.evaluate<Any?>("delete globalThis.$resultVar;", "<cleanup>") }
							val logData = if (data.length > 1500) data.substring(0, 1500) + "...(truncated)" else data
							Log.d(TAG, "$methodName completed for $pluginId, result data: $logData")
							return@withTimeout data
						} else {
							val error = obj["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
							Log.e(TAG, "$methodName failed for $pluginId: $error")
							throw LNReaderJSException("$methodName failed: $error")
						}
					}
				}
				
				attempts++
				// Exponential backoff up to 200ms
				if (waitTime < 200) waitTime = (waitTime * 1.2).toLong().coerceAtMost(200)
			}
			
			throw LNReaderJSException("$methodName timeout after ${MAX_POLL_ATTEMPTS} attempts")
		}
	}
	
	/**
	 * Parse JSON array of novel items.
	 * LNReader format: [{name, path, cover}, ...]
	 */
	private fun parseNovelList(jsonStr: String): List<LNReaderNovelItem> {
		return try {
			val array = json.parseToJsonElement(jsonStr).jsonArray
			array.mapNotNull { element ->
				val obj = element.jsonObject
				val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				val path = obj["path"]?.jsonPrimitive?.contentOrNull
					?: obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				val cover = obj["cover"]?.jsonPrimitive?.contentOrNull ?: ""
				LNReaderNovelItem(name = name, path = path, cover = cover)
			}
		} catch (e: Exception) {
			Log.e(TAG, "Failed to parse novel list: ${e.message}")
			emptyList()
		}
	}
	
	/**
	 * Parse novel details JSON.
	 * LNReader format: {name, path, cover, author, summary, genres, status, chapters: [{name, path, releaseTime}]}
	 */
	private fun parseNovelDetails(jsonStr: String): LNReaderNovelDetails {
		val obj = try {
			json.parseToJsonElement(jsonStr).jsonObject
		} catch (e: Exception) {
			Log.e(TAG, "Failed to parse novel details JSON: ${e.message}\nRaw JSON: ${jsonStr.take(1000)}")
			return LNReaderNovelDetails(name = "Error", path = "")
		}
		
		val chaptersArray = obj["chapters"] as? JsonArray
		Log.d(TAG, "parseNovelDetails extracted keys: ${obj.keys}, chapters is array = ${chaptersArray != null}, size = ${chaptersArray?.size}")
		
		val totalPages = obj["totalPages"]?.jsonPrimitive?.intOrNull ?: 0
		val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
		val path = obj["path"]?.jsonPrimitive?.contentOrNull
			?: obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
		val cover = obj["cover"]?.jsonPrimitive?.contentOrNull ?: ""
		val author = obj["author"]?.jsonPrimitive?.contentOrNull ?: ""
		val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
			?: obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
		val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: ""
		
		val genres = try {
			when (val g = obj["genres"]) {
				is JsonArray -> g.mapNotNull { it.jsonPrimitive.contentOrNull }
				is JsonPrimitive -> g.contentOrNull
					?.split(',')
					?.map { it.trim() }
					?.filter { it.isNotEmpty() }
					?: emptyList()
				else -> emptyList()
			}
		} catch (e: Exception) { emptyList() }
		
		val chapters = try {
			(obj["chapters"] as? JsonArray)?.mapNotNull { element ->
				val chObj = element.jsonObject
				val chName = chObj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				val chPath = chObj["path"]?.jsonPrimitive?.contentOrNull
					?: chObj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				val releaseTime = chObj["releaseTime"]?.jsonPrimitive?.contentOrNull
				val chapterNumber = chObj["chapterNumber"]?.jsonPrimitive?.contentOrNull
					?: chObj["chapterNumber"]?.jsonPrimitive?.intOrNull?.toString()
				LNReaderChapter(name = chName, path = chPath, releaseTime = releaseTime, chapterNumber = chapterNumber)
			} ?: emptyList()
		} catch (e: Exception) {
			Log.e(TAG, "Failed to parse chapters: ${e.message}")
			emptyList()
		}
		
		return LNReaderNovelDetails(
			name = name, path = path, cover = cover,
			author = author, summary = summary, genres = genres,
			status = status, chapters = chapters, totalPages = totalPages
		)
	}
	
	/**
	 * Scans the Javascript environment for NativeCheerioBridge DOM requests (`__cheerioQueue`).
	 * Computes the queries using JSoup on the Kotlin thread, and returns the serialised layout data
	 * back to `__cheerioResults` for the Javascript engine to continue parsing.
	 */
	private suspend fun processCheerioQueue() {
		// Obsolete empty function
	}
	
	private fun escapeForJS(str: String): String {
		return str
			.replace("\\", "\\\\")
			.replace("'", "\\'")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t")
	}
}

// ==================== Data Models ====================

data class LNReaderNovelItem(
	val name: String,
	val path: String,
	val cover: String = ""
)

data class LNReaderNovelDetails(
	val name: String,
	val path: String,
	val cover: String = "",
	val author: String = "",
	val summary: String = "",
	val genres: List<String> = emptyList(),
	val status: String = "",
	val chapters: List<LNReaderChapter> = emptyList(),
	val totalPages: Int = 0
)

data class LNReaderChapter(
	val name: String,
	val path: String,
	val releaseTime: String? = null,
	val chapterNumber: String? = null
)

data class LNReaderFilter(
	val key: String,
	val label: String,
	val type: String,
	val options: List<LNReaderFilterOption>
)

data class LNReaderFilterOption(
	val label: String,
	val value: String
)
