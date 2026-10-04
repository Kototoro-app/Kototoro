package org.skepsun.kototoro.desktop.compat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Picture
import android.net.Uri
import android.net.http.SslCertificate
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.textclassifier.TextClassifier
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewProvider
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import java.io.BufferedWriter
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Base64

/**
 * Concrete desktop WebViewProvider backed by the out-of-process Microsoft Edge WebView2 bridge.
 * Offloads browser execution to native Windows Edge WebView2 without heavy KCEF downloads.
 */
internal class DesktopWebViewProvider(
    private val webView: WebView,
    private val bridge: DesktopWebViewBridge,
    private val looper: DesktopCompatibilityLooper? = null,
    private val cookies: DesktopBrowserCookies? = null,
) : WebViewProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Channel<Operation>(Channel.UNLIMITED)
    private val destroyed = AtomicBoolean()
    @Volatile private var activeNavigation: Job? = null
    private val settings = KcefWebSettings()
    private var webViewClient: WebViewClient? = null
    private var webChromeClient: WebChromeClient? = null
    @Volatile private var currentUrl: String? = null
    @Volatile private var currentTitle: String? = null
    private var lastNavigation: (() -> Unit)? = null

    private data class Operation(val navigation: Boolean, val execute: suspend () -> Unit)

    init {
        scope.launch {
            for (operation in operations) {
                val job = scope.launch(start = CoroutineStart.LAZY) { operation.execute() }
                if (operation.navigation) activeNavigation = job
                job.start()
                try { job.join() } finally { if (operation.navigation) activeNavigation = null }
            }
        }
    }

    private val viewDelegate: WebViewProvider.ViewDelegate = Proxy.newProxyInstance(
        WebViewProvider.ViewDelegate::class.java.classLoader,
        arrayOf(WebViewProvider.ViewDelegate::class.java)
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType, Boolean::class.java -> false
            Int::class.javaPrimitiveType, Int::class.java -> 0
            Long::class.javaPrimitiveType, Long::class.java -> 0L
            Float::class.javaPrimitiveType, Float::class.java -> 0f
            Double::class.javaPrimitiveType, Double::class.java -> 0.0
            else -> null
        }
    } as WebViewProvider.ViewDelegate

    private val scrollDelegate: WebViewProvider.ScrollDelegate = Proxy.newProxyInstance(
        WebViewProvider.ScrollDelegate::class.java.classLoader,
        arrayOf(WebViewProvider.ScrollDelegate::class.java)
    ) { _, method, _ ->
        when (method.returnType) {
            Int::class.javaPrimitiveType, Int::class.java -> 0
            else -> null
        }
    } as WebViewProvider.ScrollDelegate

    override fun init(items: Map<String, Any>?, isPrivate: Boolean) {
        // init callback from WebView constructor
    }

    override fun getSettings(): WebSettings = settings

    override fun setWebViewClient(client: WebViewClient?) {
        this.webViewClient = client
    }

    override fun getWebViewClient(): WebViewClient? = webViewClient

    override fun setWebChromeClient(client: WebChromeClient?) {
        this.webChromeClient = client
    }

    override fun getWebChromeClient(): WebChromeClient? = webChromeClient

    override fun loadUrl(url: String) {
        loadUrl(url, null)
    }

    override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>?) {
        if (url.startsWith("javascript:", true)) {
            evaluateJavaScript(url.substringAfter(':'), null)
            return
        }
        val headers = additionalHttpHeaders.orEmpty().toMap()
        lastNavigation = { loadUrl(url, headers) }
        navigate(url) { bridge.navigate(url, headers = headers) }
    }

    override fun postUrl(url: String, postData: ByteArray?) {
        val body = postData?.clone() ?: byteArrayOf()
        lastNavigation = { postUrl(url, body) }
        navigate(url) { bridge.navigate(url, headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            postData = body) }
    }

    override fun loadData(data: String, mimeType: String?, encoding: String?) {
        loadDataWithBaseURL(null, data, mimeType, encoding, null)
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        lastNavigation = { loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl) }
        navigate(baseUrl ?: "about:blank") {
            val html = if (baseUrl.isNullOrBlank() && encoding.equals("base64", true)) {
                Base64.getDecoder().decode(data).toString(Charsets.UTF_8)
            } else data
            bridge.loadHtml(html, baseUrl = baseUrl, mimeType = mimeType ?: "text/html")
        }
    }

    override fun evaluateJavaScript(script: String, resultCallback: ValueCallback<String>?) {
        submit(false) {
            try {
                cookies?.push()
                val value = bridge.evaluateJs(script)
                cookies?.pull()
                dispatchToMain {
                    resultCallback?.onReceiveValue(value)
                }
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                dispatchToMain {
                    resultCallback?.onReceiveValue("null")
                }
            }
        }
    }

    private fun navigate(url: String, block: suspend () -> Boolean) {
        currentUrl = url
        submit(true) {
            try {
                bridge.applySettings(settings.javaScriptEnabled, settings.userAgentString)
                cookies?.push()
                dispatchToMain { webViewClient?.onPageStarted(webView, url, null) }
                check(block()) { "Navigation failed" }
                cookies?.pull()
                val document = bridge.document()
                currentUrl = document.url
                currentTitle = document.title
                dispatchToMain {
                    webChromeClient?.onReceivedTitle(webView, document.title)
                    webViewClient?.onPageFinished(webView, document.url)
                }
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                dispatchToMain { webViewClient?.onReceivedError(webView, WebViewClient.ERROR_UNKNOWN,
                    error.message ?: "Browser request failed", url) }
            }
        }
    }

    private fun submit(navigation: Boolean, operation: suspend () -> Unit) {
        check(!destroyed.get()) { "WebView has been destroyed" }
        check(operations.trySend(Operation(navigation, operation)).isSuccess) { "WebView queue is closed" }
    }

    private fun dispatchToMain(action: () -> Unit) {
        if (destroyed.get()) return
        val callback = { if (!destroyed.get()) action() }
        if (looper != null) {
            looper.dispatch(callback)
        } else {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper != null && Looper.myLooper() != mainLooper) {
                Handler(mainLooper).post(callback)
            } else {
                callback()
            }
        }
    }

    override fun getUrl(): String? = currentUrl

    override fun getOriginalUrl(): String? = currentUrl

    override fun getTitle(): String? = currentTitle

    override fun stopLoading() {
        val navigation = activeNavigation
        if (navigation != null) navigation.cancel() else scope.launch { runCatching { bridge.stopLoading() } }
    }

    override fun reload() {
        lastNavigation?.invoke()
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        operations.close()
        scope.cancel()
        bridge.close()
    }

    override fun getViewDelegate(): WebViewProvider.ViewDelegate = viewDelegate

    override fun getScrollDelegate(): WebViewProvider.ScrollDelegate = scrollDelegate

    // Stubbed UI methods
    override fun setHorizontalScrollbarOverlay(p0: Boolean) {}
    override fun setVerticalScrollbarOverlay(p0: Boolean) {}
    override fun overlayHorizontalScrollbar(): Boolean = false
    override fun overlayVerticalScrollbar(): Boolean = false
    override fun getVisibleTitleHeight(): Int = 0
    override fun getCertificate(): SslCertificate? = null
    override fun setCertificate(p0: SslCertificate?) {}
    override fun savePassword(p0: String?, p1: String?, p2: String?) {}
    override fun setHttpAuthUsernamePassword(p0: String?, p1: String?, p2: String?, p3: String?) {}
    override fun getHttpAuthUsernamePassword(p0: String?, p1: String?): Array<String>? = null
    override fun setNetworkAvailable(p0: Boolean) {}
    override fun saveState(p0: Bundle?): WebBackForwardList? = null
    override fun savePicture(p0: Bundle?, p1: File?): Boolean = false
    override fun restorePicture(p0: Bundle?, p1: File?): Boolean = false
    override fun restoreState(p0: Bundle?): WebBackForwardList? = null
    override fun saveWebArchive(p0: String?) {}
    override fun saveWebArchive(p0: String?, p1: Boolean, p2: ValueCallback<String>?) {}
    override fun canGoBack(): Boolean = false
    override fun goBack() {}
    override fun canGoForward(): Boolean = false
    override fun goForward() {}
    override fun canGoBackOrForward(p0: Int): Boolean = false
    override fun goBackOrForward(p0: Int) {}
    override fun isPrivateBrowsingEnabled(): Boolean = false
    override fun pageUp(p0: Boolean): Boolean = false
    override fun pageDown(p0: Boolean): Boolean = false
    override fun insertVisualStateCallback(p0: Long, p1: WebView.VisualStateCallback?) {}
    override fun clearView() {}
    override fun capturePicture(): Picture? = null
    override fun createPrintDocumentAdapter(p0: String?): android.print.PrintDocumentAdapter? = null
    override fun getScale(): Float = 1.0f
    override fun setInitialScale(p0: Int) {}
    override fun invokeZoomPicker() {}
    override fun getHitTestResult(): WebView.HitTestResult? = null
    override fun requestFocusNodeHref(p0: Message?) {}
    override fun requestImageRef(p0: Message?) {}
    override fun getFavicon(): Bitmap? = null
    override fun getTouchIconUrl(): String? = null
    override fun getProgress(): Int = 100
    override fun getContentHeight(): Int = 0
    override fun getContentWidth(): Int = 0
    override fun pauseTimers() {}
    override fun resumeTimers() {}
    override fun onPause() {}
    override fun onResume() {}
    override fun isPaused(): Boolean = false
    override fun freeMemory() {}
    override fun clearCache(p0: Boolean) {}
    override fun clearFormData() {}
    override fun clearHistory() {}
    override fun clearSslPreferences() {}
    override fun copyBackForwardList(): WebBackForwardList? = null
    override fun setFindListener(p0: WebView.FindListener?) {}
    override fun findNext(p0: Boolean) {}
    override fun findAll(p0: String?): Int = 0
    override fun findAllAsync(p0: String?) {}
    override fun showFindDialog(p0: String?, p1: Boolean): Boolean = false
    override fun clearMatches() {}
    override fun documentHasImages(p0: Message?) {}
    override fun getWebViewRenderProcess(): WebViewRenderProcess? = null
    override fun setWebViewRenderProcessClient(p0: Executor?, p1: WebViewRenderProcessClient?) {}
    override fun getWebViewRenderProcessClient(): WebViewRenderProcessClient? = null
    override fun setDownloadListener(p0: DownloadListener?) {}
    override fun setPictureListener(p0: WebView.PictureListener?) {}
    override fun addJavascriptInterface(p0: Any?, p1: String?) {}
    override fun removeJavascriptInterface(p0: String?) {}
    override fun createWebMessageChannel(): Array<WebMessagePort>? = null
    override fun postMessageToMainFrame(p0: WebMessage?, p1: Uri?) {}
    override fun setMapTrackballToArrowKeys(p0: Boolean) {}
    override fun flingScroll(p0: Int, p1: Int) {}
    override fun getZoomControls(): View? = null
    override fun canZoomIn(): Boolean = false
    override fun canZoomOut(): Boolean = false
    override fun zoomBy(p0: Float): Boolean = false
    override fun zoomIn(): Boolean = false
    override fun zoomOut(): Boolean = false
    override fun dumpViewHierarchyWithProperties(p0: BufferedWriter?, p1: Int) {}
    override fun findHierarchyView(p0: String?, p1: Int): View? = null
    override fun setRendererPriorityPolicy(p0: Int, p1: Boolean) {}
    override fun getRendererRequestedPriority(): Int = 0
    override fun getRendererPriorityWaivedWhenNotVisible(): Boolean = false
    override fun notifyFindDialogDismissed() {}
}
