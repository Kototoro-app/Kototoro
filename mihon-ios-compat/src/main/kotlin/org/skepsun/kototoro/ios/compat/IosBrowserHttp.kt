package org.skepsun.kototoro.ios.compat

import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.Base64

/** WebKit keeps its own transport identity even when URLSession uses exactly the same UA and cookies. */
internal fun browserHttpResponse(request: Request, cookieJar: CookieJar): Response? {
    if (!IosWebKit.isAvailable) return null
    return runCatching {
        val headers = buildJsonObject {
            request.headers.names().forEach { name -> put(name, request.header(name).orEmpty()) }
        }
        val cookies = cookieJar.loadForRequest(request.url).filter {
            it.expiresAt > System.currentTimeMillis() && it.matches(request.url)
        }
        val payload = buildJsonObject {
            put("url", request.url.toString())
            put("method", request.method)
            put("headers", headers)
            putJsonArray("cookies") { cookies.forEach { add(it.toString()) } }
        }
        val result = IosWebKit.json.parseToJsonElement(IosWebKit.command("http.fetch", payload = payload)).jsonObject
        val code = result["status"]?.jsonPrimitive?.intOrNull ?: return null
        if (code !in 200..599) return null
        val encoded = result["body"]?.jsonPrimitive?.content ?: return null
        // Reject an oversized bridge response before decoding it.
        if (encoded.length > 12 * 1024 * 1024) return null
        val bytes = Base64.getDecoder().decode(encoded)
        val responseHeaders = Headers.Builder().apply {
            result["headers"]?.jsonObject?.forEach { (name, value) ->
                if (name.lowercase() !in setOf("content-encoding", "content-length", "transfer-encoding", "set-cookie")) {
                    add(name, value.jsonPrimitive.content)
                }
            }
        }.build()
        val imported = result["cookies"]?.jsonArray.orEmpty().mapNotNull {
            Cookie.parse(request.url, it.jsonPrimitive.content)
        }
        if (imported.isNotEmpty()) cookieJar.saveFromResponse(request.url, imported)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("WebKit")
            .headers(responseHeaders).body(bytes.toResponseBody(responseHeaders["Content-Type"]?.toMediaTypeOrNull()))
            .build()
    }.getOrNull()
}
