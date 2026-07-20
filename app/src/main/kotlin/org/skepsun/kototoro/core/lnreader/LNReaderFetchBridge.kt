package org.skepsun.kototoro.core.lnreader

import android.util.Log
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.Base64

/**
 * Native fetch API bridge for LNReader JS plugins.
 * Mirrors IReader's JSFetchApi — registered as __nativeFetch in QuickJS context.
 * 
 * LNReader plugins call `fetchApi(url)` which resolves to this bridge.
 * Returns a map matching the Fetch Response interface: {ok, status, statusText, url, text, headers}
 */
class LNReaderFetchBridge(
	private val httpClient: OkHttpClient,
	private val pluginId: String
) {
	companion object {
		private const val TAG = "LNReaderFetchBridge"
		private const val DEFAULT_USER_AGENT =
			"Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
	}
	
	var pendingFatalException: Exception? = null

	/**
	 * Performs an HTTP request matching the Fetch API contract.
	 * Called from JavaScript via native bridge.
	 * 
	 * @param url The URL to fetch
	 * @param initStr Optional RequestInit JSON string (method, headers, body)
	 * @return Response JSON string {ok, status, statusText, url, text, headers}
	 */
	fun fetch(url: String, initStr: String? = null): String {
		return try {
			Log.d(TAG, "[$pluginId] Fetching: $url")
			
			// Validate URL
			if (!isValidUrl(url)) {
				return errorResponse(url, 0, "Security Error", "Invalid URL: $url")
			}
			
			val init = try {
				if (!initStr.isNullOrEmpty()) org.json.JSONObject(initStr) else null
			} catch (e: Exception) {
				null
			}
			
			val method = (init?.optString("method"))?.ifEmpty { "GET" }?.uppercase() ?: "GET"
			val headersMap = extractRequestHeaders(init, includeOrigin = method != "GET" && method != "HEAD")
			val body = extractBody(init)
			
			// Build OkHttp request
			val requestBuilder = Request.Builder()
				.url(url)
			
			// Add headers
			val headerBuilder = Headers.Builder()
			if (!headersMap.containsKey("User-Agent")) {
				headerBuilder.add("User-Agent", DEFAULT_USER_AGENT)
			}
			headersMap.forEach { (key, value) ->
				headerBuilder.add(key, value)
			}
			requestBuilder.headers(headerBuilder.build())
			
			// Set method and body
			when (method) {
				"GET" -> requestBuilder.get()
				"POST" -> {
					val contentType = headersMap["Content-Type"] ?: "application/x-www-form-urlencoded"
					val requestBody = (body ?: "").toRequestBody(contentType.toMediaType())
					requestBuilder.post(requestBody)
				}
				"PUT" -> {
					val contentType = headersMap["Content-Type"] ?: "application/x-www-form-urlencoded"
					val requestBody = (body ?: "").toRequestBody(contentType.toMediaType())
					requestBuilder.put(requestBody)
				}
				"DELETE" -> requestBuilder.delete()
				else -> requestBuilder.method(method, null)
			}
			
			// Execute request
			val response = httpClient.newCall(requestBuilder.build()).execute()
			val responseBody = response.body?.string() ?: ""
			val responseHeaders = mutableMapOf<String, String>()
			response.headers.forEach { (name, value) ->
				responseHeaders[name] = value
			}
			
			Log.d(TAG, "[$pluginId] Success: ${response.code} (${responseBody.length} bytes)")
			
			val responseJson = org.json.JSONObject()
			responseJson.put("ok", response.isSuccessful)
			responseJson.put("status", response.code)
			responseJson.put("statusText", response.message.ifEmpty { "OK" })
			responseJson.put("url", url)
			responseJson.put("text", responseBody)
			
			val jsHeaders = org.json.JSONObject()
			responseHeaders.forEach { (k, v) -> jsHeaders.put(k, v) }
			responseJson.put("headers", jsHeaders)
			
			responseJson.toString()
		} catch (e: Exception) {
			val causeList = generateSequence(e as Throwable) { it.cause }.toList()
			val interactiveEx = causeList.find { 
				it.javaClass.name.contains("CloudFlare") || 
				it.javaClass.name.contains("InteractiveAction") 
			} as? Exception

			if (interactiveEx != null) {
				Log.e(TAG, "[$pluginId] Native Protection engaged: ${interactiveEx.message}")
				pendingFatalException = interactiveEx
				errorResponse(url, 403, "Protected", interactiveEx.message ?: "Protected")
			} else if (e is java.io.IOException) {
				Log.e(TAG, "[$pluginId] Network error: ${e.message}")
				errorResponse(url, 0, "Network Error", e.message ?: "Network error")
			} else {
				Log.e(TAG, "[$pluginId] Fatal error: ${e.message}")
				errorResponse(url, 0, "Fatal Error", "Fatal error: ${e.message}")
			}
		}
	}

	fun fetchBinary(url: String, initStr: String? = null, bodyBase64: String): String {
		return try {
			Log.d(TAG, "[$pluginId] Fetching binary: $url")

			if (!isValidUrl(url)) {
				return errorResponse(url, 0, "Security Error", "Invalid URL: $url")
			}

			val init = try {
				if (!initStr.isNullOrEmpty()) org.json.JSONObject(initStr) else null
			} catch (e: Exception) {
				null
			}
			val headersMap = extractRequestHeaders(init, includeOrigin = true)
			val requestBodyBytes = Base64.getDecoder().decode(bodyBase64)
			val contentType = headersMap["Content-Type"] ?: "application/octet-stream"
			val requestBody = requestBodyBytes.toRequestBody(contentType.toMediaType())

			val headerBuilder = Headers.Builder()
			if (!headersMap.containsKey("User-Agent")) {
				headerBuilder.add("User-Agent", DEFAULT_USER_AGENT)
			}
			headersMap.forEach { (key, value) ->
				headerBuilder.add(key, value)
			}

			val request = Request.Builder()
				.url(url)
				.headers(headerBuilder.build())
				.post(requestBody)
				.build()

			val response = httpClient.newCall(request).execute()
			val responseBytes = response.body?.bytes() ?: ByteArray(0)
			val responseHeaders = mutableMapOf<String, String>()
			response.headers.forEach { (name, value) ->
				responseHeaders[name] = value
			}

			Log.d(TAG, "[$pluginId] Binary success: ${response.code} (${responseBytes.size} bytes)")

			val responseJson = org.json.JSONObject()
			responseJson.put("ok", response.isSuccessful)
			responseJson.put("status", response.code)
			responseJson.put("statusText", response.message.ifEmpty { "OK" })
			responseJson.put("url", url)
			responseJson.put("bodyBase64", Base64.getEncoder().encodeToString(responseBytes))

			val jsHeaders = org.json.JSONObject()
			responseHeaders.forEach { (k, v) -> jsHeaders.put(k, v) }
			responseJson.put("headers", jsHeaders)

			responseJson.toString()
		} catch (e: Exception) {
			if (e is IOException) {
				Log.e(TAG, "[$pluginId] Binary network error: ${e.message}")
				errorResponse(url, 0, "Network Error", e.message ?: "Network error")
			} else {
				Log.e(TAG, "[$pluginId] Binary fatal error: ${e.message}")
				errorResponse(url, 0, "Fatal Error", "Fatal error: ${e.message}")
			}
		}
	}
	
	/**
	 * Generate the JavaScript wrapper function for injection into QuickJS.
	 * Matches IReader's JSFetchApi.toJavaScriptFunction().
	 */
	fun toJavaScriptFunction(): String {
		return """
			class Headers {
				constructor(init) {
					this.map = {};
					if (init) {
						if (typeof init.forEach === 'function') {
							init.forEach((value, key) => this.append(key, value));
						} else {
							for (const key in init) {
								this.append(key, init[key]);
							}
						}
					}
				}
				append(key, value) {
					const k = key.toLowerCase();
					if (this.map[k]) {
						this.map[k] += ', ' + value;
					} else {
						this.map[k] = value;
					}
				}
				set(key, value) {
					this.map[key.toLowerCase()] = value;
				}
				get(key) {
					return this.map[key.toLowerCase()] || null;
				}
				has(key) {
					return this.map.hasOwnProperty(key.toLowerCase());
				}
				delete(key) {
					delete this.map[key.toLowerCase()];
				}
				forEach(callback) {
					for (const key in this.map) {
						callback(this.map[key], key, this);
					}
				}
				toJSON() {
					return this.map;
				}
			}
			globalThis.Headers = Headers;
			
			globalThis.fetchApi = function(url, init) {
				var initStr = init ? JSON.stringify(init) : "{}";
				var responseStr = __nativeFetch(url, initStr);
				var response = responseStr ? JSON.parse(responseStr) : {};
				
				if (response.error) {
					return Promise.reject(new Error(response.error));
				}
				
				var resObj = {
					ok: response.ok,
					status: response.status,
					statusText: response.statusText,
					url: response.url,
					headers: new Headers(response.headers || {})
				};
				
				// Standard fetch methods
				resObj.text = function() { return Promise.resolve(response.text || ''); };
				resObj.json = function() { 
					try {
						return Promise.resolve(JSON.parse(response.text || '{}'));
					} catch(e) {
						return Promise.reject(e);
					}
				};
				return Promise.resolve(resObj);
			};
			
			globalThis.fetch = function(url, init) {
				return globalThis.fetchApi(url, init);
			};

			${getProtoFetchScript()}
		""".trimIndent()
	}

	private fun getProtoFetchScript(): String {
		return """
			(function() {
				function bytesToBase64(bytes) {
					var binary = '';
					for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
					return btoa(binary);
				}

				function base64ToBytes(base64) {
					var binary = atob(base64 || '');
					var bytes = new Uint8Array(binary.length);
					for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i) & 255;
					return bytes;
				}

				function copyBytes(bytes, start, end) {
					start = Math.max(0, start || 0);
					end = Math.min(bytes.length, end === undefined ? bytes.length : end);
					var result = new Uint8Array(Math.max(0, end - start));
					for (var i = start; i < end; i++) result[i - start] = bytes[i];
					return result;
				}

				function parseProtoSchema(proto) {
					var messages = {};
					var cleaned = String(proto || '').replace(/\/\/.*$/gm, '');
					function stripNestedTypeBlocks(body) {
						var result = '';
						var pos = 0;
						var blockRegex = /\b(message|enum)\s+\w+\s*\{/g;
						var match;
						while ((match = blockRegex.exec(body)) !== null) {
							result += body.substring(pos, match.index);
							var depth = 1;
							var end = blockRegex.lastIndex;
							while (end < body.length && depth > 0) {
								var ch = body.charAt(end++);
								if (ch === '{') depth++;
								else if (ch === '}') depth--;
							}
							pos = end;
							blockRegex.lastIndex = end;
						}
						result += body.substring(pos);
						return result;
					}
					function collectMessages(source) {
						var messageRegex = /message\s+(\w+)\s*\{/g;
						var match;
						while ((match = messageRegex.exec(source)) !== null) {
							var name = match[1];
							var bodyStart = messageRegex.lastIndex;
							var depth = 1;
							var pos = bodyStart;
							while (pos < source.length && depth > 0) {
								var ch = source.charAt(pos++);
								if (ch === '{') depth++;
								else if (ch === '}') depth--;
							}
							var body = source.substring(bodyStart, pos - 1);
							parseMessage(name, body);
							collectMessages(body);
							messageRegex.lastIndex = pos;
						}
					}
					function parseMessage(name, body) {
						var fields = {};
						var ownBody = stripNestedTypeBlocks(body);
						var fieldRegex = /^\s*(optional|repeated)?\s*([.\w]+)\s+(\w+)\s*=\s*(\d+)/gm;
						var field;
						while ((field = fieldRegex.exec(ownBody)) !== null) {
							fields[Number(field[4])] = {
								rule: field[1] || '',
								type: field[2].split('.').pop(),
								name: field[3]
							};
						}
						messages[name] = fields;
					}
					collectMessages(cleaned);
					return messages;
				}

				function writeVarint(value, out) {
					var n = Number(value || 0);
					if (n < 0) n = 0xffffffff + n + 1;
					while (n > 127) {
						out.push((n & 127) | 128);
						n = Math.floor(n / 128);
					}
					out.push(n & 127);
				}

				function readVarint(bytes, state) {
					var shift = 0;
					var result = 0;
					while (state.pos < bytes.length) {
						var b = bytes[state.pos++];
						result += (b & 127) * Math.pow(2, shift);
						if ((b & 128) === 0) break;
						shift += 7;
					}
					return result;
				}

				function writeLengthDelimited(payload, out) {
					writeVarint(payload.length, out);
					for (var i = 0; i < payload.length; i++) out.push(payload[i]);
				}

				function utf8Encode(str) {
					var encoded = unescape(encodeURIComponent(String(str)));
					var bytes = [];
					for (var i = 0; i < encoded.length; i++) bytes.push(encoded.charCodeAt(i));
					return bytes;
				}

				function utf8Decode(bytes) {
					var str = '';
					for (var i = 0; i < bytes.length; i++) str += String.fromCharCode(bytes[i]);
					try {
						return decodeURIComponent(escape(str));
					} catch (e) {
						return str;
					}
				}

				function isMessageType(schema, type) {
					return !!schema[type];
				}

				function scalarWireType(type) {
					if (type === 'string' || type === 'bytes') return 2;
					if (type === 'sfixed32' || type === 'fixed32' || type === 'float') return 5;
					if (type === 'sfixed64' || type === 'fixed64' || type === 'double') return 1;
					return 0;
				}

				function encodeScalar(type, value, out) {
					if (type === 'string') {
						writeLengthDelimited(utf8Encode(value == null ? '' : value), out);
					} else if (type === 'bool') {
						writeVarint(value ? 1 : 0, out);
					} else if (type === 'sfixed32' || type === 'fixed32' || type === 'float') {
						var n = Number(value || 0);
						for (var i = 0; i < 4; i++) out.push((n >> (8 * i)) & 255);
					} else {
						writeVarint(value || 0, out);
					}
				}

				function encodeMessage(schema, typeName, data) {
					var fields = schema[typeName] || {};
					var out = [];
					Object.keys(fields).forEach(function(fieldNoStr) {
						var fieldNo = Number(fieldNoStr);
						var field = fields[fieldNo];
						var value = data ? data[field.name] : undefined;
						if (value === undefined || value === null) return;
						var values = field.rule === 'repeated' && Array.isArray(value) ? value : [value];
						values.forEach(function(item) {
							var wireType = isMessageType(schema, field.type) ? 2 : scalarWireType(field.type);
							writeVarint(fieldNo * 8 + wireType, out);
							if (isMessageType(schema, field.type)) {
								writeLengthDelimited(encodeMessage(schema, field.type, item || {}), out);
							} else {
								encodeScalar(field.type, item, out);
							}
						});
					});
					return out;
				}

				function readFixed32(bytes, state) {
					var value = 0;
					for (var i = 0; i < 4 && state.pos < bytes.length; i++) {
						value += bytes[state.pos++] << (8 * i);
					}
					return value;
				}

				function skipField(bytes, state, wireType) {
					if (wireType === 0) readVarint(bytes, state);
					else if (wireType === 1) state.pos += 8;
					else if (wireType === 2) {
						var len = readVarint(bytes, state);
						state.pos += len;
					}
					else if (wireType === 5) state.pos += 4;
					else state.pos = bytes.length;
				}

				function decodeScalar(type, wireType, bytes, state) {
					if (wireType === 2) {
						var len = readVarint(bytes, state);
						var end = state.pos + len;
						var slice = copyBytes(bytes, state.pos, end);
						state.pos = end;
						if (type === 'string') return utf8Decode(slice);
						return slice;
					}
					if (wireType === 5) return readFixed32(bytes, state);
					if (wireType === 1) {
						state.pos += 8;
						return 0;
					}
					var value = readVarint(bytes, state);
					if (type === 'bool') return value !== 0;
					return value;
				}

				function decodeMessage(schema, typeName, bytes) {
					var fields = schema[typeName] || {};
					var state = { pos: 0 };
					var obj = {};
					Object.keys(fields).forEach(function(fieldNo) {
						var field = fields[fieldNo];
						if (field.rule === 'repeated') obj[field.name] = [];
					});
					while (state.pos < bytes.length) {
						var tag = readVarint(bytes, state);
						var fieldNo = Math.floor(tag / 8);
						var wireType = tag & 7;
						var field = fields[fieldNo];
						if (!field) {
							skipField(bytes, state, wireType);
							continue;
						}
						var value;
						if (wireType === 2 && isMessageType(schema, field.type)) {
							var len = readVarint(bytes, state);
							var end = state.pos + len;
							value = decodeMessage(schema, field.type, copyBytes(bytes, state.pos, end));
							state.pos = end;
						} else {
							value = decodeScalar(field.type, wireType, bytes, state);
						}
						if (field.rule === 'repeated') {
							if (!obj[field.name]) obj[field.name] = [];
							obj[field.name].push(value);
						} else {
							obj[field.name] = value;
						}
					}
					return obj;
				}

				function makeGrpcWebFrame(payload) {
					var frame = new Uint8Array(payload.length + 5);
					frame[0] = 0;
					var len = payload.length;
					frame[1] = (len >>> 24) & 255;
					frame[2] = (len >>> 16) & 255;
					frame[3] = (len >>> 8) & 255;
					frame[4] = len & 255;
					for (var i = 0; i < payload.length; i++) frame[i + 5] = payload[i];
					return frame;
				}

				function unwrapGrpcWebFrame(payload) {
					if (!payload || payload.length < 5) return new Uint8Array(0);
					var len = payload[1] * 16777216 + payload[2] * 65536 + payload[3] * 256 + payload[4];
					return copyBytes(payload, 5, 5 + len);
				}

				globalThis.fetchProto = function(protoInit, url, init) {
					try {
						var schema = parseProtoSchema(protoInit.proto || '');
						var requestPayload = new Uint8Array(encodeMessage(
							schema,
							protoInit.requestType,
							protoInit.requestData || {}
						));
						var framedRequest = makeGrpcWebFrame(requestPayload);
						var initStr = init ? JSON.stringify(init) : "{}";
						var responseStr = __nativeFetchProto(url, initStr, bytesToBase64(framedRequest));
						var response = responseStr ? JSON.parse(responseStr) : {};
						if (response.error) return Promise.reject(new Error(response.error));
						var responsePayload = unwrapGrpcWebFrame(base64ToBytes(response.bodyBase64 || ''));
						return Promise.resolve(decodeMessage(schema, protoInit.responseType, responsePayload));
					} catch (e) {
						return Promise.reject(e);
					}
				};
			})();
		""".trimIndent()
	}
	
	private fun extractHeaders(init: org.json.JSONObject?): Map<String, String> {
		if (init == null || !init.has("headers")) return emptyMap()
		val headersObj = init.optJSONObject("headers") ?: return emptyMap()
		val result = mutableMapOf<String, String>()
		val keys = headersObj.keys()
		while (keys.hasNext()) {
			val key = keys.next()
			result[key] = headersObj.optString(key)
		}
		return result
	}

	private fun extractRequestHeaders(
		init: org.json.JSONObject?,
		includeOrigin: Boolean,
	): Map<String, String> {
		val result = extractHeaders(init).toMutableMap()
		val referrer = init?.optString("referrer")?.takeIf { it.isNotBlank() }
		if (referrer != null && result.keys.none { it.equals("Referer", ignoreCase = true) }) {
			result["Referer"] = referrer
		}
		if (includeOrigin && referrer != null && result.keys.none { it.equals("Origin", ignoreCase = true) }) {
			runCatching {
				val referrerUri = URI(referrer)
				if (!referrerUri.scheme.isNullOrBlank() && !referrerUri.host.isNullOrBlank()) {
					result["Origin"] = buildString {
						append(referrerUri.scheme)
						append("://")
						append(referrerUri.host)
						if (referrerUri.port > 0) append(":${referrerUri.port}")
					}
				}
			}
		}
		return result
	}

	private fun extractBody(init: org.json.JSONObject?): String? {
		if (init == null || !init.has("body")) return null
		val body = init.opt("body")
		return when (body) {
			is String -> body
			is org.json.JSONObject -> {
				val parts = mutableListOf<String>()
				val keys = body.keys()
				while (keys.hasNext()) {
					val key = keys.next()
					parts.add("$key=${java.net.URLEncoder.encode(body.optString(key), "UTF-8")}")
				}
				parts.joinToString("&")
			}
			else -> body?.toString()
		}
	}
	
	private fun isValidUrl(url: String): Boolean {
		return try {
			val uri = URI(url)
			val scheme = uri.scheme?.lowercase()
			scheme == "http" || scheme == "https"
		} catch (e: Exception) {
			false
		}
	}
	
	private fun errorResponse(url: String, status: Int, statusText: String, error: String): String {
		val res = org.json.JSONObject()
		res.put("ok", false)
		res.put("status", status)
		res.put("statusText", statusText)
		res.put("url", url)
		res.put("text", "")
		res.put("error", error)
		return res.toString()
	}
}
