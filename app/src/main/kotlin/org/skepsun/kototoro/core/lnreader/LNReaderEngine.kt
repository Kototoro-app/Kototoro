package org.skepsun.kototoro.core.lnreader

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * QuickJS-based JavaScript engine for executing LNReader plugins.
 * Mirrors IReader's JSEngine — manages a QuickJS context with injected native bridges.
 * 
 * Uses `com.dokar.quickjs` (dokar3/quickjs-kt) — same library already used by
 * JsContentRepository and TVBoxQuickJsSpiderRuntime in this project.
 */
class LNReaderEngine(
	private val context: Context,
	private val fetchBridge: LNReaderFetchBridge
) {
	companion object {
		private const val TAG = "LNReaderEngine"
		private const val MAX_STACK_SIZE = 1L shl 20   // 1MB
		private const val MEMORY_LIMIT = 64L shl 20    // 64MB
	}
	
	private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
	
	/**
	 * Load a LNReader plugin and set up its execution environment.
	 * Returns a configured QuickJs instance ready to call plugin methods.
	 * 
	 * The caller should use the returned QuickJs in a coroutine scope via `qjs.use { ... }`.
	 *
	 * @param jsCode The compiled JS plugin bundle
	 * @param pluginId Plugin identifier (used for global variable naming)
	 */
	suspend fun createPluginContext(jsCode: String, pluginId: String): QuickJs {
		val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
		qjs.maxStackSize = MAX_STACK_SIZE
		qjs.memoryLimit = MEMORY_LIMIT
		
		try {
			// 1. Register native fetch bridge
			registerFetchBridge(qjs)
			
			// 2. Register console polyfill
			registerConsole(qjs)
			
			// 3. Register global polyfills (URL, URLSearchParams, atob, btoa, etc.)
			registerGlobalPolyfills(qjs)
			
			// 4. Register synchronous cheerio bridge
			registerCheerioBridge(qjs)

			// 5. Register native crypto helpers
			registerCryptoBridge(qjs)
			
			// 6. Setup module system for plugins
			setupModuleSystem(qjs)
			
			// 5. Load the plugin code
			qjs.evaluate<Any?>(jsCode, "<lnreader-plugin>")
			
			// 5. Store plugin instance in global scope
			val sanitizedId = pluginId.replace(Regex("[^a-zA-Z0-9_]"), "_")
			qjs.evaluate<Any?>(
				"""
				(function() {
					var plugin = (typeof exports !== 'undefined' && exports.default) || 
					             (typeof exports !== 'undefined' && exports) ||
					             (typeof module !== 'undefined' && module.exports && module.exports.default) ||
					             (typeof module !== 'undefined' && module.exports);
					if (plugin && typeof plugin === 'function') {
						plugin = new plugin();
					}
					globalThis.__plugin_${sanitizedId} = plugin;
					var methods = [];
					for (var p = plugin; p && p !== Object.prototype; p = Object.getPrototypeOf(p)) {
						methods = methods.concat(Object.getOwnPropertyNames(p));
					}
					console.log('Plugin ' + '$pluginId' + ' exports: ' + methods.join(', '));
				})();
				""".trimIndent(),
				"<plugin-init>"
			)
			
			Log.d(TAG, "Plugin $pluginId loaded successfully")
			return qjs
		} catch (e: Exception) {
			qjs.close()
			Log.e(TAG, "Failed to load plugin $pluginId", e)
			throw LNReaderJSException("Failed to load plugin $pluginId: ${e.message}", e)
		}
	}
	
	/**
	 * Register the native fetch function bridge.
	 * JS code calls fetchApi(url, init) which delegates to OkHttp.
	 */
	private suspend fun registerFetchBridge(qjs: QuickJs) {
		// Register __nativeFetch as a native function
		qjs.defineBinding("__nativeFetch", FunctionBinding<String?> { args ->
			val url = args.getOrNull(0) as? String ?: return@FunctionBinding null
			val init = args.getOrNull(1) as? String
			fetchBridge.fetch(url, init)
		})
		qjs.defineBinding("__nativeFetchProto", FunctionBinding<String?> { args ->
			val url = args.getOrNull(0) as? String ?: return@FunctionBinding null
			val init = args.getOrNull(1) as? String
			val bodyBase64 = args.getOrNull(2) as? String ?: return@FunctionBinding null
			fetchBridge.fetchBinary(url, init, bodyBase64)
		})
		
		qjs.evaluate<Any?>(
			"""
			var __fetchBridgeResults = {};
			var __fetchBridgeNextId = 0;
			""".trimIndent(),
			"<fetch-init>"
		)
		
		// Inject fetchApi wrapper that uses synchronous __nativeFetch
		val fetchScript = fetchBridge.toJavaScriptFunction()
		qjs.evaluate<Any?>(fetchScript, "<fetch-bridge>")
	}
	
	/**
	 * Register console.log/warn/error polyfill.
	 */
	private suspend fun registerConsole(qjs: QuickJs) {
		qjs.defineBinding("__nativeConsole", FunctionBinding<Any?> { args ->
			val level = args.getOrNull(0) as? String ?: "log"
			val msg = args.drop(1).joinToString(" ") { it.toString() }
			when (level) {
				"error" -> Log.e(TAG, "[JS] $msg")
				"warn" -> Log.w(TAG, "[JS] $msg")
				else -> Log.d(TAG, "[JS] $msg")
			}
		})
		
		qjs.evaluate<Any?>(
			"""
			var console = {
				log: function(...args) { __nativeConsole('log', ...args); },
				warn: function(...args) { __nativeConsole('warn', ...args); },
				error: function(...args) { __nativeConsole('error', ...args); },
				info: function(...args) { __nativeConsole('info', ...args); },
				debug: function(...args) { __nativeConsole('debug', ...args); }
			};
			if (!String.prototype.replaceAll) {
				String.prototype.replaceAll = function(str, newStr) {
					if (Object.prototype.toString.call(str).toLowerCase() === '[object regexp]') {
						return this.replace(str, newStr);
					}
					var escapeRegex = function(s) {
					    return s.replace(/[.*+?^${'$'}()|[\]\\]/g, '\\$&');
					};
					return this.replace(new RegExp(escapeRegex(str), 'g'), newStr);
				};
			}
			if (!Array.prototype.flat) {
				Array.prototype.flat = function(depth) {
					var result = [];
					var maxDepth = depth === undefined ? 1 : Number(depth);
					function flatten(items, currentDepth) {
						for (var i = 0; i < items.length; i++) {
							var item = items[i];
							if (Array.isArray(item) && currentDepth < maxDepth) {
								flatten(item, currentDepth + 1);
							} else {
								result.push(item);
							}
						}
					}
					flatten(this, 0);
					return result;
				};
			}
			if (!Array.prototype.flatMap) {
				Array.prototype.flatMap = function(callback, thisArg) {
					if (typeof callback !== 'function') throw new TypeError('flatMap callback is not a function');
					var mapped = [];
					for (var i = 0; i < this.length; i++) {
						if (i in this) mapped.push(callback.call(thisArg, this[i], i, this));
					}
					return mapped.flat(1);
				};
			}
			""".trimIndent(),
			"<console>"
		)
	}
	
	/**
	 * Register global polyfills for missing QuickJs properties like URL, URLSearchParams, atob, btoa
	 */
	private suspend fun registerGlobalPolyfills(qjs: QuickJs) {
		qjs.evaluate<Any?>(
			"""
			// Setup URL API polyfill with comprehensive error handling
			globalThis.URL = function(url, base) {
				if (url === null || url === undefined) throw new Error('Invalid URL');
				if (typeof url === 'object' && url.href) url = url.href;
				url = String(url);
				let fullUrl = url;
				if (base && !url.match(/^[a-zA-Z]+:\/\//)) {
					base = String(base);
					if (url.startsWith('/')) {
						const baseMatch = base.match(/^(https?:\/\/[^\/]+)/);
						fullUrl = baseMatch ? baseMatch[1] + url : url;
					} else if (url.startsWith('?')) {
						const baseMatch = base.match(/^([^?#]+)/);
						fullUrl = baseMatch ? baseMatch[1] + url : url;
					} else if (url.startsWith('#')) {
						const baseMatch = base.match(/^([^#]+)/);
						fullUrl = baseMatch ? baseMatch[1] + url : url;
					} else {
						const match = base.match(/^(https?:\/\/[^\/]+)(.*)${'$'}/);
						if (match) {
							const origin = match[1];
							let path = match[2] || '/';
							if (path.indexOf('?') >= 0) path = path.substring(0, path.indexOf('?'));
							if (path.indexOf('#') >= 0) path = path.substring(0, path.indexOf('#'));
							path = path.substring(0, path.lastIndexOf('/') + 1);
							fullUrl = origin + path + url;
						} else {
							fullUrl = base.endsWith('/') ? base + url : base + '/' + url;
						}
					}
				}
				// Resolve dot segments (. and ..)
				let urlMatch = fullUrl.match(/^(https?:\/\/[^\/]+)(.*)${'$'}/);
				if (urlMatch) {
					let origin = urlMatch[1];
					let pathAndRest = urlMatch[2] || '/';
					let queryHash = '';
					let qIdx = pathAndRest.indexOf('?');
					let hIdx = pathAndRest.indexOf('#');
					let splitIdx = qIdx >= 0 ? qIdx : (hIdx >= 0 ? hIdx : -1);
					if (splitIdx >= 0) {
						queryHash = pathAndRest.substring(splitIdx);
						pathAndRest = pathAndRest.substring(0, splitIdx);
					}
					let segments = pathAndRest.split('/');
					let resolved = [];
					for (let seg of segments) {
						if (seg === '.') continue;
						if (seg === '..') {
							if (resolved.length > 0 && resolved[resolved.length - 1] !== '') {
								resolved.pop();
							}
						} else {
							resolved.push(seg);
						}
					}
					fullUrl = origin + resolved.join('/') + queryHash;
				}
				const match = fullUrl.match(/^(https?):\/\/([^/?#]+)(\/[^?#]*)?(\\?[^#]*)?(#.*)?${'$'}/);
				if (!match) throw new Error('Invalid URL: ' + fullUrl);
				
				const protocol = match[1] || 'http';
				const hostWithPort = match[2] || '';
				const pathname = match[3] || '/';
				const search = match[4] || '';
				const hash = match[5] || '';
				
				const hostParts = (hostWithPort || '').split(':');
				this.protocol = String(protocol) + ':';
				this.host = String(hostWithPort);
				this.hostname = String(hostParts[0] || '');
				this.port = String(hostParts[1] || '');
				this.pathname = String(pathname);
				this.search = String(search);
				this.hash = String(hash);
				this.href = String(fullUrl);
				this.origin = String(protocol) + '://' + String(hostWithPort);
				this.toString = function() { return this.href; };
				this.toJSON = function() { return this.href; };
			};
			
			// Setup URLSearchParams
			globalThis.URLSearchParams = function(init) {
				this.params = {};
				if (typeof init === 'string') {
					const query = init.startsWith('?') ? init.substring(1) : init;
					if (query) {
						query.split('&').forEach(function(pair) {
							const parts = pair.split('=');
							const key = decodeURIComponent(parts[0]);
							const value = parts[1] ? decodeURIComponent(parts[1]) : '';
							if (!this.params[key]) this.params[key] = [];
							this.params[key].push(value);
						}.bind(this));
					}
				} else if (init && typeof init === 'object') {
					for (const key in init) {
						if (init.hasOwnProperty(key)) this.params[key] = [String(init[key])];
					}
				}
				this.append = function(key, value) {
					if (!this.params[key]) this.params[key] = [];
					this.params[key].push(String(value));
				};
				this.delete = function(key) { delete this.params[key]; };
				this.get = function(key) { return this.params[key] ? this.params[key][0] : null; };
				this.getAll = function(key) { return this.params[key] || []; };
				this.has = function(key) { return key in this.params; };
				this.set = function(key, value) { this.params[key] = [String(value)]; };
				this.toString = function() {
					const parts = [];
					for (const key in this.params) {
						if (this.params.hasOwnProperty(key)) {
							this.params[key].forEach(function(value) {
								parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
							});
						}
					}
					return parts.join('&');
				};
				this.entries = function() {
					const entries = [];
					for (const key in this.params) {
						if (this.params.hasOwnProperty(key)) {
							this.params[key].forEach(function(value) { entries.push([key, value]); });
						}
					}
					return entries;
				};
				this.keys = function() { return Object.keys(this.params); };
				this.values = function() {
					const values = [];
					for (const key in this.params) {
						if (this.params.hasOwnProperty(key)) values.push(...this.params[key]);
					}
					return values;
				};
			};
			
			if (typeof globalThis.atob === 'undefined') {
				globalThis.atob = function(str) {
					const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
					let output = '';
					str = String(str).replace(/\s/g, '');
					if (str.length % 4 === 1) throw new Error('Invalid base64 string');
					for (let i = 0; i < str.length;) {
						const enc1 = chars.indexOf(str.charAt(i++));
						const enc2 = chars.indexOf(str.charAt(i++));
						const char3 = str.charAt(i++);
						const char4 = str.charAt(i++);
						const enc3 = char3 ? chars.indexOf(char3) : 64;
						const enc4 = char4 ? chars.indexOf(char4) : 64;
						if (enc1 < 0 || enc2 < 0 || enc3 < 0 || enc4 < 0) {
							throw new Error('Invalid base64 string');
						}
						const chr1 = (enc1 << 2) | (enc2 >> 4);
						const chr2 = ((enc2 & 15) << 4) | (enc3 >> 2);
						const chr3 = ((enc3 & 3) << 6) | enc4;
						output += String.fromCharCode(chr1);
						if (enc3 !== 64 && enc3 !== -1) output += String.fromCharCode(chr2);
						if (enc4 !== 64 && enc4 !== -1) output += String.fromCharCode(chr3);
					}
					return output;
				};
			}
			
			if (typeof globalThis.btoa === 'undefined') {
				globalThis.btoa = function(str) {
					const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
					let output = '';
					str = String(str);
					for (let i = 0; i < str.length;) {
						const chr1 = str.charCodeAt(i++);
						const chr2 = str.charCodeAt(i++);
						const chr3 = str.charCodeAt(i++);
						const enc1 = chr1 >> 2;
						const enc2 = ((chr1 & 3) << 4) | (chr2 >> 4);
						let enc3 = ((chr2 & 15) << 2) | (chr3 >> 6);
						let enc4 = chr3 & 63;
						if (isNaN(chr2)) { enc3 = enc4 = 64; } else if (isNaN(chr3)) { enc4 = 64; }
						output += chars.charAt(enc1) + chars.charAt(enc2) + chars.charAt(enc3) + chars.charAt(enc4);
					}
					return output;
				};
			}
			
			if (typeof globalThis.TextEncoder === 'undefined') {
				globalThis.TextEncoder = function() {
					this.encode = function(str) {
						const utf8 = unescape(encodeURIComponent(str));
						const result = new Uint8Array(utf8.length);
						for (let i = 0; i < utf8.length; i++) result[i] = utf8.charCodeAt(i);
						return result;
					};
				};
			}
			
			if (typeof globalThis.TextDecoder === 'undefined') {
				globalThis.TextDecoder = function() {
					this.decode = function(buffer) {
						const bytes = new Uint8Array(buffer);
						let str = '';
						for (let i = 0; i < bytes.length; i++) str += String.fromCharCode(bytes[i]);
						return decodeURIComponent(escape(str));
					};
				};
			}
			
			if (typeof globalThis.Uint8Array === 'undefined') {
				globalThis.Uint8Array = function(length) {
					const arr = new Array(length);
					for (let i = 0; i < length; i++) arr[i] = 0;
					arr.buffer = new ArrayBuffer(length);
					arr.byteLength = length;
					return arr;
				};
			}
			
			if (typeof globalThis.ArrayBuffer === 'undefined') {
				globalThis.ArrayBuffer = function(length) { this.byteLength = length || 0; };
			}
			
			globalThis.Blob = function(parts, options) {
				this.parts = parts || [];
				this.options = options || {};
				this.size = this.parts.reduce(function(acc, part) { return acc + (part.length || 0); }, 0);
				this.type = this.options.type || '';
			};
			
			globalThis.FormData = function() {
				this.data = {};
				this.append = function(key, value) { if (!this.data[key]) this.data[key] = []; this.data[key].push(value); };
				this.get = function(key) { return this.data[key] ? this.data[key][0] : null; };
				this.getAll = function(key) { return this.data[key] || []; };
				this.has = function(key) { return key in this.data; };
				this.delete = function(key) { delete this.data[key]; };
				this.set = function(key, value) { this.data[key] = [value]; };
				this.entries = function() {
					const entries = [];
					for (const key in this.data) { for (const value of this.data[key]) entries.push([key, value]); }
					return entries;
				};
			};

			// Comprehensive Date polyfill for QuickJS.
			// QuickJS throws "Date value is NaN" from both the constructor (new Date("invalid"))
			// AND from prototype methods (date.toISOString()). We must patch both.
			(function() {
				var _RealDate = Date;
				
				// 1. Wrap constructor to catch throws on invalid date strings
				function SafeDate() {
					var d;
					try {
						if (arguments.length === 0) d = new _RealDate();
						else if (arguments.length === 1) {
							var arg = arguments[0];
							if (typeof arg === 'string') {
								// Try numeric timestamp first
								var num = Number(arg);
								if (!isNaN(num)) {
									d = new _RealDate(num);
								} else {
									// Try ISO-like format: replace common separators
									try { d = new _RealDate(arg); }
									catch(e2) { d = new _RealDate(0); }
								}
							} else {
								d = new _RealDate(arg);
							}
						}
						else if (arguments.length === 2) d = new _RealDate(arguments[0], arguments[1]);
						else if (arguments.length === 3) d = new _RealDate(arguments[0], arguments[1], arguments[2]);
						else d = new _RealDate(arguments[0], arguments[1], arguments[2], arguments[3], arguments[4], arguments[5], arguments[6]);
					} catch(e) {
						d = new _RealDate(0);
					}
					return d;
				}
				SafeDate.now = function() { return _RealDate.now(); };
				SafeDate.parse = function(s) { try { return _RealDate.parse(s); } catch(e) { return NaN; } };
				SafeDate.UTC = function() { try { return _RealDate.UTC.apply(_RealDate, arguments); } catch(e) { return NaN; } };
				SafeDate.prototype = _RealDate.prototype;
				globalThis.Date = SafeDate;
				
				// 2. Patch prototype methods to catch NaN-related throws
				var strMethods = ['toString', 'toISOString', 'toUTCString', 'toDateString',
					'toTimeString', 'toLocaleDateString', 'toLocaleTimeString', 'toLocaleString', 'toJSON',
					'toGMTString'];
				strMethods.forEach(function(method) {
					var orig = _RealDate.prototype[method];
					if (orig) {
						_RealDate.prototype[method] = function() {
							try { return orig.apply(this, arguments); }
							catch(e) { return ''; }
						};
					}
				});
				var numMethods = ['getTime', 'valueOf', 'getFullYear', 'getMonth', 'getDate',
					'getHours', 'getMinutes', 'getSeconds', 'getMilliseconds',
					'getUTCFullYear', 'getUTCMonth', 'getUTCDate', 'getUTCHours',
					'getUTCMinutes', 'getUTCSeconds', 'getUTCMilliseconds',
					'getTimezoneOffset', 'getDay', 'getUTCDay'];
				numMethods.forEach(function(method) {
					var orig = _RealDate.prototype[method];
					if (orig) {
						_RealDate.prototype[method] = function() {
							try { return orig.apply(this, arguments); }
							catch(e) { return NaN; }
						};
					}
				});
			})();

			globalThis.window = globalThis;
			globalThis.location = {
				href: 'about:blank', protocol: 'about:', host: 'blank', hostname: 'blank',
				port: '', pathname: '/blank', search: '', hash: '', origin: 'about:blank',
				toString: function() { return this.href; }
			};
			globalThis.document = {
				location: globalThis.location, URL: 'about:blank', domain: 'blank', referrer: '',
				title: '', cookie: '', documentURI: 'about:blank', baseURI: 'about:blank'
			};
			""".trimIndent(),
			"<polyfills>"
		)
	}

	/**
	 * Setup module stubs for common LNReader require imports.
	 */
	private suspend fun setupModuleSystem(qjs: QuickJs) {
		qjs.evaluate<Any?>(
			"""
			globalThis.__cheerioIdCounter = 0;
			globalThis.__cheerioQueue = [];
			globalThis.__cheerioResults = {};
			
			globalThis.cheerio = ${getNativeCheerioBridge()};
			globalThis.htmlparser2 = ${getHtmlParser2Library()};
			
			globalThis.__libs_novelStatus = ${getNovelStatusLibrary()};
			globalThis.__libs_filterInputs = ${getFilterInputsLibrary()};
			globalThis.__libs_dayjs = ${getDayjsLibrary()};
			globalThis.__libs_aes = ${getAesLibrary()};
			
			// Module stubs for LNReader plugin imports
			if (typeof globalThis.require === 'undefined') {
				globalThis.require = function(name) {
					console.log('REQUIRE:', name);
					if (name === '@libs/fetch') return {
						fetchApi: function(url, options) { return globalThis.fetch(url, options); },
						fetchText: function(url, options) { return globalThis.fetch(url, options).then(function(res) { return res.text(); }); },
						fetchProto: function(protoInit, url, options) { return globalThis.fetchProto(protoInit, url, options); },
						fetchFile: function(url) { return globalThis.fetch(url).then(function(res) { return res.text(); }); }
					};
					if (name === '@libs/novelStatus') return globalThis.__libs_novelStatus;
					if (name === '@libs/filterInputs') return globalThis.__libs_filterInputs;
					if (name === '@libs/aes') return globalThis.__libs_aes;
					
					if (name === '@libs/storage') return {
						storage: { get: function(key) { return null; }, set: function(key, value) {}, delete: function(key) {} },
						get: function(key) { return null; },
						set: function(key, value) {},
						delete: function(key) {}
					};
					if (name === '@libs/defaultCover') return { defaultCover: '' };
					if (name === '@libs/isAbsoluteUrl') return {
						isUrlAbsolute: function(url) {
							if (!url) return false;
							return /^https?:\/\//i.test(url);
						}
					};
					if (name === '@libs/isUrlAbsolute') return {
						isUrlAbsolute: function(url) {
							if (!url) return false;
							return /^https?:\/\//i.test(url);
						}
					};
					
					if (name === 'htmlparser2') return globalThis.htmlparser2;
					if (name === 'cheerio') return globalThis.cheerio;
					if (name === 'dayjs') return globalThis.__libs_dayjs;
					
					// Return a dummy proxy that absorbs any property access without throwing
					return new Proxy(function() {}, {
						get: function(target, prop) {
							if (prop === Symbol.toPrimitive) return () => '';
							if (prop === 'then') return undefined; // Prevent infinite promise resolving loops 
							if (prop === 'toJSON') return undefined; // Prevent infinite recursion during JSON.stringify
							console.log('PROXY GET:', name, prop ? String(prop) : 'unknown');
							return new Proxy(function() {}, this);
						},
						apply: function(target, thisArg, argumentsList) {
							console.log('PROXY CALL:', name);
							return new Proxy(function() {}, this);
						},
						construct: function(target, args) {
							console.log('PROXY CONSTRUCT:', name);
							return new Proxy(function() {}, this);
						}
					});
				};
			}
			// CommonJS module support
			if (typeof globalThis.exports === 'undefined') {
				globalThis.exports = {};
			}
			if (typeof globalThis.module === 'undefined') {
				globalThis.module = { exports: globalThis.exports };
			}
			// Timers polyfill
			if (typeof globalThis.setTimeout === 'undefined') {
				globalThis.setTimeout = function(fn) { fn(); return 1; };
				globalThis.clearTimeout = function() {};
				globalThis.setInterval = function(fn) { fn(); return 1; };
				globalThis.clearInterval = function() {};
			}
			""".trimIndent(),
			"<module-stubs>"
		)
	}
	
	/**
	 * Register the native cheerio bridge.
	 * Gives QuickJs access to Jsoup synchronously.
	 */
	private fun registerCheerioBridge(qjs: QuickJs) {
		val parsedElements = mutableMapOf<Int, org.jsoup.nodes.Element>()
		var cheerioIdCounter = 0
		
		qjs.defineBinding("__nativeCheerio", FunctionBinding<String> { args ->
			val type = args.getOrNull(0) as? String ?: return@FunctionBinding "{}"
			
			if (type == "parse") {
				val html = args.getOrNull(1) as? String ?: ""
				val docId = cheerioIdCounter++
				try {
					parsedElements[docId] = Jsoup.parse(html)
					return@FunctionBinding docId.toString()
				} catch (e: Exception) {
					Log.e(TAG, "Cheerio parse error: ${e.message}")
					return@FunctionBinding "-1"
				}
			} else if (type == "query") {
				val parentIdStr = args.getOrNull(1)?.toString() ?: "-1"
				val parentId = parentIdStr.toIntOrNull() ?: -1
				val selector = args.getOrNull(2) as? String ?: ""
				
				val parent = parsedElements[parentId] ?: return@FunctionBinding "{}"
				
				try {
					if (selector.startsWith("__is__:")) {
						val sel = selector.substringAfter("__is__:")
						return@FunctionBinding if (parent.`is`(sel)) "true" else "false"
					}
					if (selector == "__root_text__") {
						return@FunctionBinding parent.text()
					}
					if (selector == "__root_html__") {
						return@FunctionBinding parent.html()
					}
					if (selector == "__remove__") {
						parent.remove()
						return@FunctionBinding "true"
					}
					
					val selection = when {
						selector == "__parent__" -> org.jsoup.select.Elements(parent.parent() ?: parent)
						selector == "__next__" -> parent.nextElementSibling()
							?.let { org.jsoup.select.Elements(it) }
							?: org.jsoup.select.Elements()
						selector == "__prev__" -> parent.previousElementSibling()
							?.let { org.jsoup.select.Elements(it) }
							?: org.jsoup.select.Elements()
						selector.startsWith("__closest__:") -> parent.closest(selector.substringAfter("__closest__:"))
							?.let { org.jsoup.select.Elements(it) }
							?: org.jsoup.select.Elements()
						selector == "__children__" -> parent.children()
						selector.isNotEmpty() -> parent.select(selector)
						else -> org.jsoup.select.Elements()
					}
					val resultItems = mutableListOf<String>()
					for (element in selection) {
						val elId = cheerioIdCounter++
						parsedElements[elId] = element
						val attrs = buildMap {
							element.attributes().forEach { attr ->
								put(attr.key, attr.value)
							}
						}
						
						val itemData = mapOf(
							"id" to elId.toString(),
							"text" to element.text(),
							"html" to element.html(),
							"tagName" to element.tagName(),
							"attrs" to attrs
						)
						// Convert map to Json string manually
						resultItems.add(json.encodeToString(
							JsonObject.serializer(),
							JsonObject(itemData.mapValues { (k, v) ->
								if (v is String) JsonPrimitive(v)
								else JsonObject((v as Map<String, String>).mapValues { JsonPrimitive(it.value) })
							})
						))
					}
					
					val resultJson = """
						{
							"text": ${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(selection.text()))},
							"html": ${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(selection.html()))},
							"attrs": {
								"href": ${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(selection.attr("href")))},
								"src": ${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(selection.attr("src")))},
								"class": ${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(selection.attr("class")))},
								"id": ${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(selection.attr("id")))}
							},
							"items": [${resultItems.joinToString(",")}]
						}
					""".trimIndent()
					
					return@FunctionBinding resultJson
				} catch (e: Exception) {
					Log.e(TAG, "Cheerio query error: ${e.message}")
				}
			}
			"{}"
		})
	}

	private fun registerCryptoBridge(qjs: QuickJs) {
		qjs.defineBinding("__nativeAesGcmDecrypt", FunctionBinding<String?> { args ->
			val keyBase64 = args.getOrNull(0) as? String ?: return@FunctionBinding null
			val ivBase64 = args.getOrNull(1) as? String ?: return@FunctionBinding null
			val dataBase64 = args.getOrNull(2) as? String ?: return@FunctionBinding null
			runCatching {
				val key = Base64.getDecoder().decode(keyBase64)
				val iv = Base64.getDecoder().decode(ivBase64)
				val data = Base64.getDecoder().decode(dataBase64)
				val cipher = Cipher.getInstance("AES/GCM/NoPadding")
				cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
				Base64.getEncoder().encodeToString(cipher.doFinal(data))
			}.getOrElse { error ->
				Log.e(TAG, "AES-GCM decrypt failed: ${error.message}")
				null
			}
		})
	}
	
	private fun getNativeCheerioBridge(): String {
		return """
			{
				load: function(html) {
					const docIdStr = globalThis.__nativeCheerio('parse', html);
					const docId = parseInt(docIdStr);
					
					function createSelection(parentId, result) {
						function parseQuery(targetId, selector) {
							const resultStr = globalThis.__nativeCheerio('query', targetId, selector || '');
							let r = {items:[]};
							try { r = JSON.parse(resultStr); } catch (e) {}
							return r;
						}
						function selectionFromItems(items) {
							items = items || [];
							return createSelection(parentId, {
								items: items,
								text: items.map(function(item) { return item.text || ''; }).join(' '),
								html: items.map(function(item) { return item.html || ''; }).join(''),
								attrs: items[0] ? (items[0].attrs || {}) : {}
							});
						}
						return {
							_parentId: parentId,
							_result: result,
							text: function() { return result.text || ''; },
							attr: function(name) { return (result.attrs && result.attrs[name]) || ''; },
							html: function() { return result.html || ''; },
							find: function(subSelector) {
								if (result.items && result.items.length > 0) {
									let items = [];
									result.items.forEach(function(item) {
										const r = parseQuery(parseInt(item.id), subSelector || '');
										if (r.items) items = items.concat(r.items);
									});
									return selectionFromItems(items);
								}
								return createSelection(parentId, parseQuery(parentId, subSelector || ''));
							},
							is: function(sel) {
								if (!result.items || result.items.length === 0) return false;
								for (let i = 0; i < result.items.length; i++) {
									const flag = globalThis.__nativeCheerio('query', result.items[i].id, '__is__:' + sel);
									if (flag === 'true') return true;
								}
								return false;
							},
							hasClass: function(name) {
								const classes = (this.attr('class') || '').split(/\s+/);
								return classes.indexOf(name) >= 0;
							},
							prop: function(name) {
								if (name === 'tagName' || name === 'nodeName') {
									const item = result.items && result.items[0];
									return item && item.tagName ? String(item.tagName).toUpperCase() : undefined;
								}
								return this.attr(name);
							},
							data: function(name) {
								if (!name) return {};
								return this.attr('data-' + name);
							},
							parent: function() {
								if (!result.items || result.items.length === 0) return createSelection(parentId, {items:[]});
								const resultStr = globalThis.__nativeCheerio('query', result.items[0].id, '__parent__');
								let r = {items:[]};
								try { r = JSON.parse(resultStr); } catch (e) {}
								return createSelection(docId, r); 
							},
							next: function() {
								if (!result.items || result.items.length === 0) return createSelection(parentId, {items:[]});
								return createSelection(docId, parseQuery(result.items[0].id, '__next__'));
							},
							prev: function() {
								if (!result.items || result.items.length === 0) return createSelection(parentId, {items:[]});
								return createSelection(docId, parseQuery(result.items[0].id, '__prev__'));
							},
							closest: function(sel) {
								if (!result.items || result.items.length === 0) return createSelection(parentId, {items:[]});
								return createSelection(docId, parseQuery(result.items[0].id, '__closest__:' + (sel || '')));
							},
							children: function() {
								if (!result.items || result.items.length === 0) return createSelection(parentId, {items:[]});
								const resultStr = globalThis.__nativeCheerio('query', result.items[0].id, '__children__');
								let r = {items:[]};
								try { r = JSON.parse(resultStr); } catch (e) {}
								return createSelection(docId, r);
							},
							contents: function() {
								return this.children();
							},
							remove: function() {
								if (result.items) {
									result.items.forEach(function(item) {
										globalThis.__nativeCheerio('query', item.id, '__remove__');
									});
								}
								return this;
							},
							slice: function(start, end) {
								if (!result.items) return createSelection(parentId, {items:[]});
								return selectionFromItems(result.items.slice(start, end));
							},
							not: function(selector) {
								if (!result.items) return createSelection(parentId, {items:[]});
								if (typeof selector !== 'string') return this;
								const items = result.items.filter(function(item) {
									const flag = globalThis.__nativeCheerio('query', item.id, '__is__:' + selector);
									return flag !== 'true';
								});
								return selectionFromItems(items);
							},
							first: function() {
								if (!result.items || result.items.length === 0) return this;
								return selectionFromItems([result.items[0]]);
							},
							last: function() {
								if (!result.items || result.items.length === 0) return this;
								const lastIdx = result.items.length - 1;
								return selectionFromItems([result.items[lastIdx]]);
							},
							eq: function(index) {
								if (!result.items || !result.items[index]) return this;
								return selectionFromItems([result.items[index]]);
							},
							each: function(callback) {
								if (result.items) {
									result.items.forEach(function(item, index) {
										const elId = parseInt(item.id);
										const itemObj = createSelection(elId, item);
										callback.call(itemObj, index, itemObj);
									});
								}
								return this;
							},
							filter: function(callback) {
								if (!result.items) return createSelection(parentId, {items:[]});
								if (typeof callback === 'function') {
									const results = [];
									result.items.forEach(function(item, index) {
										const elId = parseInt(item.id);
										const itemObj = createSelection(elId, item);
										if (callback.call(itemObj, index, itemObj)) {
											results.push(item);
										}
									});
									return createSelection(parentId, {items: results});
								}
								return this;
							},
							map: function(callback) {
								const results = [];
								if (result.items) {
									result.items.forEach(function(item, index) {
										const elId = parseInt(item.id);
										const itemObj = createSelection(elId, item);
										const value = callback.call(itemObj, index, itemObj);
										if (value !== null && value !== undefined) {
											results.push(value);
										}
									});
								}
								return { get: function() { return results; }, toArray: function() { return results; } };
							},
							get: function(index) {
								if (!result.items) return null;
								if (index === undefined) return result.items;
								return result.items[index] || null;
							},
							toArray: function() {
								return result.items || [];
							},
							length: (result.items ? result.items.length : 0)
						};
					}
					
					var ${'$'} = function(selector) {
						if (typeof selector === 'object' && selector._parentId !== undefined) {
							return selector;
						}
						if (typeof selector === 'object' && selector.id !== undefined) {
							return createSelection(docId, {
								items: [selector],
								text: selector.text || '',
								html: selector.html || '',
								attrs: selector.attrs || {}
							});
						}
						const resultStr = globalThis.__nativeCheerio('query', docId, selector || '');
						let result = {items:[]};
						try {
							result = JSON.parse(resultStr);
						} catch (e) {}
						
						return createSelection(docId, result);
					};
					const rootSelection = createSelection(docId, {
						items: [],
						text: globalThis.__nativeCheerio('query', docId, '__root_text__') || '',
						html: globalThis.__nativeCheerio('query', docId, '__root_html__') || '',
						attrs: {}
					});
					${'$'}.text = function() { return rootSelection.text(); };
					${'$'}.html = function() { return rootSelection.html(); };
					${'$'}.root = function() { return rootSelection; };
					return ${'$'};
				}
			}
		""".trimIndent()
	}
	
	private fun getHtmlParser2Library(): String {
		return """
			(function() {
				const voidElements = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
				return {
					Parser: function(handlers, options) {
						this.handlers = handlers || {};
						this.options = options || {};
						this.tagStack = [];
						this.isVoidElement = function(tagName) { return voidElements.has(tagName.toLowerCase()); };
						
						this.write = function(html) {
							const tagRegex = /<(\/?)([\w-]+)([^>]*)>/g;
							let match;
							let lastIndex = 0;
							while ((match = tagRegex.exec(html)) !== null) {
								if (match.index > lastIndex) {
									const text = html.substring(lastIndex, match.index);
									if (text && this.handlers.ontext) this.handlers.ontext(text);
								}
								const isClosing = match[1] === '/';
								const tagName = match[2].toLowerCase();
								const attrsStr = match[3];
								const isSelfClosing = attrsStr.trim().endsWith('/');
								
								if (isClosing) {
									if (this.handlers.onclosetag) this.handlers.onclosetag(tagName);
								} else {
									const attrs = {};
									const attrRegex = /([\w-]+)(?:=["']([^"']*)["'])?/g;
									let attrMatch;
									while ((attrMatch = attrRegex.exec(attrsStr)) !== null) {
										if (attrMatch[1] && attrMatch[1] !== '/') attrs[attrMatch[1]] = attrMatch[2] || '';
									}
									if (this.handlers.onopentag) this.handlers.onopentag(tagName, attrs);
									if (voidElements.has(tagName) || isSelfClosing) {
										if (this.handlers.onclosetag) this.handlers.onclosetag(tagName);
									}
								}
								lastIndex = tagRegex.lastIndex;
							}
							if (lastIndex < html.length) {
								const text = html.substring(lastIndex);
								if (text && this.handlers.ontext) this.handlers.ontext(text);
							}
						};
						this.end = function() {
							if (this.handlers.onend) this.handlers.onend();
						};
					}
				};
			})()
		""".trimIndent()
	}
	
	private fun getNovelStatusLibrary(): String {
		return """
			(function() {
				return {
					NovelStatus: {
						Unknown: 0,
						Ongoing: 1,
						Completed: 2,
						Licensed: 3,
						PublishingFinished: 4,
						Cancelled: 5,
						OnHiatus: 6
					}
				};
			})()
		""".trimIndent()
	}
	
	private fun getFilterInputsLibrary(): String {
		return """
			(function() {
				return {
					FilterTypes: {
						Picker: 'Picker',
						Text: 'Text',
						TextInput: 'Text',
						Switch: 'Switch',
						Checkbox: 'Checkbox',
						CheckboxGroup: 'Checkbox',
						ExcludableCheckbox: 'ExcludableCheckbox',
						ExcludableCheckboxGroup: 'XCheckbox',
						TriState: 'TriState',
						Sort: 'Sort',
						Title: 'Title'
					}
				};
			})()
		""".trimIndent()
	}

	private fun getAesLibrary(): String {
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
				return {
					gcm: function(key, iv) {
						return {
							decrypt: function(data) {
								var result = __nativeAesGcmDecrypt(
									bytesToBase64(key || new Uint8Array(0)),
									bytesToBase64(iv || new Uint8Array(0)),
									bytesToBase64(data || new Uint8Array(0))
								);
								if (!result) throw new Error('AES-GCM decrypt failed');
								return base64ToBytes(result);
							}
						};
					}
				};
			})()
		""".trimIndent()
	}

	private fun getDayjsLibrary(): String {
		return """
			(function() {
				function createDay(value) {
					var date = value === undefined || value === null || value === '' ? new Date() : new Date(value);
					var valid = !isNaN(date.getTime());
					function api() {}
					api.subtract = function(amount, unit) {
						if (!valid) return api;
						amount = Number(amount || 0);
						unit = String(unit || '').toLowerCase();
						if (unit.indexOf('second') === 0) date = new Date(date.getTime() - amount * 1000);
						else if (unit.indexOf('minute') === 0) date = new Date(date.getTime() - amount * 60 * 1000);
						else if (unit.indexOf('hour') === 0) date = new Date(date.getTime() - amount * 60 * 60 * 1000);
						else if (unit.indexOf('day') === 0) date = new Date(date.getTime() - amount * 24 * 60 * 60 * 1000);
						else if (unit.indexOf('week') === 0) date = new Date(date.getTime() - amount * 7 * 24 * 60 * 60 * 1000);
						else if (unit.indexOf('month') === 0) date.setMonth(date.getMonth() - amount);
						else if (unit.indexOf('year') === 0) date.setFullYear(date.getFullYear() - amount);
						return api;
					};
					api.format = function(pattern) {
						if (!valid) return 'Invalid Date';
						var yyyy = String(date.getFullYear());
						var mm = String(date.getMonth() + 1).padStart(2, '0');
						var dd = String(date.getDate()).padStart(2, '0');
						if (pattern === 'LL') return yyyy + '-' + mm + '-' + dd;
						return yyyy + '-' + mm + '-' + dd;
					};
					api.toDate = function() { return date; };
					api.valueOf = function() { return valid ? date.getTime() : NaN; };
					return api;
				}
				createDay.default = createDay;
				createDay.__esModule = true;
				return createDay;
			})()
		""".trimIndent()
	}

}

/**
 * Exception thrown by LNReader JS engine operations.
 */
class LNReaderJSException(
	message: String,
	cause: Throwable? = null
) : Exception(message, cause)
