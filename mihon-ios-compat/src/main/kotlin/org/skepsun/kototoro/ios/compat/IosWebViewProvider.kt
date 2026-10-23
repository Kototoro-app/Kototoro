package org.skepsun.kototoro.ios.compat

import android.graphics.Bitmap
import android.graphics.Picture
import android.net.Uri
import android.net.http.SslCertificate
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewProvider
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.skepsun.kototoro.ios.compat.IosWebKit.bool
import org.skepsun.kototoro.ios.compat.IosWebKit.int
import org.skepsun.kototoro.ios.compat.IosWebKit.payload
import org.skepsun.kototoro.ios.compat.IosWebKit.string
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import java.io.BufferedWriter
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * One `android.webkit.WebView` backed by a WKWebView in the app (see [IosWebKit]). Navigation, script evaluation and
 * JavaScript interfaces go to the app; its page events come back as the WebViewClient / WebChromeClient callbacks
 * an extension expects, on the compatibility main Looper as on Android's UI thread. shouldInterceptRequest sees the
 * page's own fetch / XMLHttpRequest calls and documents (WebKit hides other subresources).
 */
internal class IosWebViewProvider(private val view: WebView) : WebViewProvider {
    private val settings = KcefWebSettings()
    private val id: Long = IosWebKit.command("create").toLong()
    private val interfaces = ConcurrentHashMap<String, Any>()
    @Volatile private var client: WebViewClient = WebViewClient()
    @Volatile private var chrome: WebChromeClient? = null
    @Volatile private var paused = false
    @Volatile private var destroyed = false

    init {
        IosWebKit.register(id, this)
    }

    private fun command(operation: String, payload: JsonObject = JsonObject(emptyMap())): String =
        if (destroyed) "" else IosWebKit.command(operation, id, payload)

    // MARK: Navigation

    override fun loadUrl(url: String) = loadUrl(url, null)

    override fun loadUrl(url: String, headers: Map<String, String>?) {
        if (url.startsWith("javascript:", ignoreCase = true)) {
            evaluateJavaScript(url.substringAfter(':'), null)
            return
        }
        prepare(url)
        command("load", payload("url" to url, "headers" to headersJson(headers.orEmpty())))
    }

    override fun postUrl(url: String, data: ByteArray?) {
        prepare(url)
        command("post", payload("url" to url, "body" to Base64.getEncoder().encodeToString(data ?: ByteArray(0))))
    }

    override fun loadData(data: String, mimeType: String?, encoding: String?) =
        loadDataWithBaseURL(null, data, mimeType, encoding, null)

    override fun loadDataWithBaseURL(baseUrl: String?, data: String, mimeType: String?, encoding: String?,
        historyUrl: String?) {
        val html = if (encoding.equals("base64", ignoreCase = true)) {
            String(Base64.getDecoder().decode(data), Charsets.UTF_8)
        } else data
        prepare(baseUrl)
        command("html", payload("baseUrl" to baseUrl, "html" to html, "mimeType" to (mimeType ?: "text/html")))
    }

    /** Settings and cookies apply to the next navigation, as WebView reads them when a load starts. */
    private fun prepare(url: String?) {
        command("configure", payload("userAgent" to settings.userAgentString, "javaScript" to settings.javaScriptEnabled))
        if (url != null && url.startsWith("http")) (android.webkit.CookieManager.getInstance() as? IosCookieManager)?.exportToWebKit(url)
    }

    override fun evaluateJavaScript(script: String, callback: ValueCallback<String>?) {
        val result = runCatching { command("evaluate", payload("script" to script)) }.getOrDefault("null")
        callback?.let { IosWebKit.post { it.onReceiveValue(result) } }
    }

    override fun stopLoading() { command("stop") }
    override fun reload() { command("reload") }
    override fun canGoBack(): Boolean = state().bool("canGoBack")
    override fun goBack() { command("back") }
    override fun canGoForward(): Boolean = state().bool("canGoForward")
    override fun goForward() { command("forward") }
    override fun canGoBackOrForward(steps: Int): Boolean = command("canGo", payload("steps" to steps)) == "true"
    override fun goBackOrForward(steps: Int) { command("go", payload("steps" to steps)) }
    override fun getUrl(): String? = state().string("url")
    override fun getOriginalUrl(): String? = state().string("originalUrl")
    override fun getTitle(): String? = state().string("title")
    override fun getProgress(): Int = state().int("progress")
    override fun getContentHeight(): Int = state().int("contentHeight")
    override fun getContentWidth(): Int = state().int("contentWidth")
    override fun clearCache(includeDiskFiles: Boolean) { command("clearCache") }

    private fun state(): JsonObject =
        runCatching { IosWebKit.json.parseToJsonElement(command("state")) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    override fun destroy() {
        if (destroyed) return
        command("destroy")
        destroyed = true
        IosWebKit.unregister(id)
        interfaces.clear()
    }

    // MARK: JavaScript interfaces

    override fun addJavascriptInterface(target: Any?, name: String?) {
        if (target == null || name.isNullOrEmpty()) return
        interfaces[name] = target
        publishInterfaces()
    }

    override fun removeJavascriptInterface(name: String?) {
        if (name == null || interfaces.remove(name) == null) return
        publishInterfaces()
    }

    /** The page needs every exposed method and whether it returns a value (those block the script for the answer). */
    private fun publishInterfaces() {
        val described = buildJsonObject {
            for ((name, target) in interfaces) putJsonArray(name) {
                for (method in exposed(target)) add(buildJsonObject {
                    put("name", method.name)
                    put("returns", method.returnType != Void.TYPE)
                })
            }
        }
        command("interfaces", payload("interfaces" to described))
    }

    private fun exposed(target: Any): List<Method> =
        target.javaClass.methods.filter { it.isAnnotationPresent(JavascriptInterface::class.java) }

    /** A page call into an interface; arguments arrive as the page's values, converted like Android's bridge does. */
    private fun call(payload: JsonObject): String {
        val target = interfaces[payload.string("interface")] ?: return "null"
        val name = payload.string("method") ?: return "null"
        val arguments = (payload["args"] as? JsonArray).orEmpty()
        val method = exposed(target).firstOrNull { it.name == name && it.parameterCount == arguments.size }
            ?: return "null"
        val values = method.parameterTypes.mapIndexed { index, type -> convert(arguments[index], type) }.toTypedArray()
        method.isAccessible = true
        return encode(method.invoke(target, *values))
    }

    private fun convert(value: kotlinx.serialization.json.JsonElement, type: Class<*>): Any? {
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive is JsonNull) {
            return if (type.isPrimitive) defaultOf(type) else if (type == String::class.java) value.toString() else null
        }
        val text = primitive.content
        return when (type) {
            String::class.java, Any::class.java, CharSequence::class.java -> text
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> text.toBoolean()
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> text.toDoubleOrNull()?.toInt() ?: 0
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> text.toDoubleOrNull()?.toLong() ?: 0L
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> text.toDoubleOrNull() ?: 0.0
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> text.toFloatOrNull() ?: 0f
            Short::class.javaPrimitiveType, Short::class.javaObjectType -> text.toDoubleOrNull()?.toInt()?.toShort() ?: 0
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> text.toDoubleOrNull()?.toInt()?.toByte() ?: 0
            Char::class.javaPrimitiveType, Char::class.javaObjectType -> text.firstOrNull() ?: '\u0000'
            else -> null
        }
    }

    private fun defaultOf(type: Class<*>): Any = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Char::class.javaPrimitiveType -> '\u0000'
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> 0
    }

    private fun encode(value: Any?): String = when (value) {
        null, Unit -> "null"
        is Number, is Boolean -> JsonPrimitive(value.toString()).content
        else -> JsonPrimitive(value.toString()).toString()
    }

    // MARK: Events from the app

    fun event(name: String, payload: JsonObject): String {
        if (destroyed) return ""
        when (name) {
            // Complete early extension hooks before Swift starts loading the destination document.
            "willStart" -> IosWebKit.onMain { client.onPageStarted(view, payload.string("url"), null) }
            "started" -> IosWebKit.post { client.onPageStarted(view, payload.string("url"), null) }
            "finished" -> {
                payload.string("url")?.takeIf { it.startsWith("http") }?.let { url ->
                    (android.webkit.CookieManager.getInstance() as? IosCookieManager)?.importFromWebKit(url)
                }
                IosWebKit.post { client.onPageFinished(view, payload.string("url")) }
            }
            "progress" -> IosWebKit.post { chrome?.onProgressChanged(view, payload.int("value")) }
            "title" -> IosWebKit.post { chrome?.onReceivedTitle(view, payload.string("value")) }
            "console" -> IosWebKit.post { console(payload) }
            "failed" -> IosWebKit.post { failed(payload) }
            "crashed" -> IosWebKit.post { client.onRenderProcessGone(view, null) }
            "call" -> {
                if (payload.bool("returns")) return call(payload)
                IosWebKit.calls.execute { runCatching { call(payload) } }
            }
            "navigate" -> {
                val request = IosRequest(payload["request"] as? JsonObject ?: return "")
                return IosWebKit.onMain { overrides(request) }.toString()
            }
            "request" -> {
                // Android asks shouldInterceptRequest on a network thread, never the UI thread.
                val request = IosRequest(payload["request"] as? JsonObject ?: return "")
                return intercept(request)?.let(::encodeResponse).orEmpty()
            }
        }
        return ""
    }

    @Suppress("DEPRECATION")
    private fun overrides(request: WebResourceRequest): Boolean {
        val modern = client.javaClass.declares("shouldOverrideUrlLoading", WebView::class.java, WebResourceRequest::class.java)
        return if (modern) client.shouldOverrideUrlLoading(view, request)
        else client.shouldOverrideUrlLoading(view, request.url.toString())
    }

    @Suppress("DEPRECATION")
    private fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val modern = client.javaClass.declares("shouldInterceptRequest", WebView::class.java, WebResourceRequest::class.java)
        return if (modern) client.shouldInterceptRequest(view, request)
        else client.shouldInterceptRequest(view, request.url.toString())
    }

    private fun Class<*>.declares(name: String, vararg parameters: Class<*>): Boolean =
        runCatching { getMethod(name, *parameters).declaringClass != WebViewClient::class.java }.getOrDefault(false)

    private fun encodeResponse(response: WebResourceResponse): String {
        val body = response.data?.use { it.readNBytes(MAX_INTERCEPTED_BYTES + 1) } ?: ByteArray(0)
        check(body.size <= MAX_INTERCEPTED_BYTES) { "Intercepted response over 64 MiB" }
        return buildJsonObject {
            put("status", response.statusCode.takeIf { it > 0 } ?: 200)
            put("reason", response.reasonPhrase ?: "OK")
            put("mimeType", response.mimeType ?: "")
            put("encoding", response.encoding ?: "")
            put("headers", headersJson(response.responseHeaders.orEmpty()))
            put("body", Base64.getEncoder().encodeToString(body))
        }.toString()
    }

    private fun console(payload: JsonObject) {
        val level = runCatching { ConsoleMessage.MessageLevel.valueOf(payload.string("level") ?: "LOG") }
            .getOrDefault(ConsoleMessage.MessageLevel.LOG)
        chrome?.onConsoleMessage(ConsoleMessage(payload.string("message").orEmpty(), payload.string("source").orEmpty(),
            payload.int("line"), level))
    }

    @Suppress("DEPRECATION")
    private fun failed(payload: JsonObject) {
        val url = payload.string("url").orEmpty()
        val description = payload.string("description") ?: "WebKit error"
        val code = WebViewClient.ERROR_UNKNOWN
        if (payload.bool("mainFrame")) client.onReceivedError(view, code, description, url)
        client.onReceivedError(view, IosRequest(payload("url" to url, "mainFrame" to payload.bool("mainFrame"))),
            android.webkit.KototoroResourceError(code, description))
    }

    // MARK: Clients and settings

    override fun init(items: Map<String, Any>?, isPrivate: Boolean) = Unit
    override fun getSettings(): WebSettings = settings
    override fun setWebViewClient(client: WebViewClient?) { this.client = client ?: WebViewClient() }
    override fun getWebViewClient(): WebViewClient = client
    override fun setWebChromeClient(client: WebChromeClient?) { chrome = client }
    override fun getWebChromeClient(): WebChromeClient? = chrome
    override fun onPause() { paused = true }
    override fun onResume() { paused = false }
    override fun isPaused(): Boolean = paused
    override fun insertVisualStateCallback(requestId: Long, callback: WebView.VisualStateCallback?) {
        callback?.let { IosWebKit.post { it.onComplete(requestId) } }
    }

    private val viewDelegate = inert(WebViewProvider.ViewDelegate::class.java)
    private val scrollDelegate = inert(WebViewProvider.ScrollDelegate::class.java)
    override fun getViewDelegate(): WebViewProvider.ViewDelegate = viewDelegate
    override fun getScrollDelegate(): WebViewProvider.ScrollDelegate = scrollDelegate

    // MARK: Without an iOS counterpart

    override fun setHorizontalScrollbarOverlay(overlay: Boolean) = Unit
    override fun setVerticalScrollbarOverlay(overlay: Boolean) = Unit
    override fun overlayHorizontalScrollbar(): Boolean = false
    override fun overlayVerticalScrollbar(): Boolean = false
    override fun getVisibleTitleHeight(): Int = 0
    override fun getCertificate(): SslCertificate? = null
    override fun setCertificate(certificate: SslCertificate?) = Unit
    override fun savePassword(host: String?, username: String?, password: String?) = Unit
    override fun setHttpAuthUsernamePassword(host: String?, realm: String?, username: String?, password: String?) = Unit
    override fun getHttpAuthUsernamePassword(host: String?, realm: String?): Array<String>? = null
    override fun setNetworkAvailable(available: Boolean) = Unit
    override fun saveState(state: Bundle?): WebBackForwardList? = null
    override fun savePicture(state: Bundle?, file: File?): Boolean = false
    override fun restorePicture(state: Bundle?, file: File?): Boolean = false
    override fun restoreState(state: Bundle?): WebBackForwardList? = null
    override fun saveWebArchive(name: String?) = Unit
    override fun saveWebArchive(name: String?, autoName: Boolean, callback: ValueCallback<String>?) = Unit
    override fun isPrivateBrowsingEnabled(): Boolean = false
    override fun pageUp(top: Boolean): Boolean = false
    override fun pageDown(bottom: Boolean): Boolean = false
    override fun clearView() = Unit
    override fun capturePicture(): Picture? = null
    override fun createPrintDocumentAdapter(name: String?): android.print.PrintDocumentAdapter? = null
    override fun getScale(): Float = 1f
    override fun setInitialScale(scale: Int) = Unit
    override fun invokeZoomPicker() = Unit
    override fun getHitTestResult(): WebView.HitTestResult? = null
    override fun requestFocusNodeHref(message: Message?) = Unit
    override fun requestImageRef(message: Message?) = Unit
    override fun getFavicon(): Bitmap? = null
    override fun getTouchIconUrl(): String? = null
    override fun pauseTimers() = Unit
    override fun resumeTimers() = Unit
    override fun freeMemory() = Unit
    override fun clearFormData() = Unit
    override fun clearHistory() = Unit
    override fun clearSslPreferences() = Unit
    override fun copyBackForwardList(): WebBackForwardList? = null
    override fun setFindListener(listener: WebView.FindListener?) = Unit
    override fun findNext(forward: Boolean) = Unit
    override fun findAll(find: String?): Int = 0
    override fun findAllAsync(find: String?) = Unit
    override fun showFindDialog(text: String?, showIme: Boolean): Boolean = false
    override fun clearMatches() = Unit
    override fun documentHasImages(response: Message?) = Unit
    override fun getWebViewRenderProcess(): WebViewRenderProcess? = null
    override fun setWebViewRenderProcessClient(executor: Executor?, client: WebViewRenderProcessClient?) = Unit
    override fun getWebViewRenderProcessClient(): WebViewRenderProcessClient? = null
    override fun setDownloadListener(listener: DownloadListener?) = Unit
    override fun setPictureListener(listener: WebView.PictureListener?) = Unit
    override fun createWebMessageChannel(): Array<WebMessagePort>? = null
    override fun postMessageToMainFrame(message: WebMessage?, targetOrigin: Uri?) = Unit
    override fun setMapTrackballToArrowKeys(setMap: Boolean) = Unit
    override fun flingScroll(vx: Int, vy: Int) = Unit
    override fun getZoomControls(): View? = null
    override fun canZoomIn(): Boolean = false
    override fun canZoomOut(): Boolean = false
    override fun zoomBy(factor: Float): Boolean = false
    override fun zoomIn(): Boolean = false
    override fun zoomOut(): Boolean = false
    override fun dumpViewHierarchyWithProperties(out: BufferedWriter?, level: Int) = Unit
    override fun findHierarchyView(className: String?, hashCode: Int): View? = null
    override fun setRendererPriorityPolicy(priority: Int, waivedWhenNotVisible: Boolean) = Unit
    override fun getRendererRequestedPriority(): Int = 0
    override fun getRendererPriorityWaivedWhenNotVisible(): Boolean = false
    override fun notifyFindDialogDismissed() = Unit

    private companion object {
        const val MAX_INTERCEPTED_BYTES = 64 * 1024 * 1024

        fun headersJson(headers: Map<String, String>): JsonObject = buildJsonObject {
            for ((key, value) in headers) put(key, value)
        }

        /** Delegates for view plumbing an off-screen page never uses: every call answers its type's zero value. */
        @Suppress("UNCHECKED_CAST")
        fun <T> inert(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        } as T
    }
}

/** A page request as WebViewClient sees it. */
internal class IosRequest(private val value: JsonObject) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(value.string("url") ?: "about:blank")
    override fun isForMainFrame(): Boolean = value.bool("mainFrame")
    override fun isRedirect(): Boolean = value.bool("redirect")
    override fun hasGesture(): Boolean = value.bool("gesture")
    override fun getMethod(): String = value.string("method") ?: "GET"
    override fun getRequestHeaders(): Map<String, String> = (value["headers"] as? JsonObject).orEmpty()
        .mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }
}
