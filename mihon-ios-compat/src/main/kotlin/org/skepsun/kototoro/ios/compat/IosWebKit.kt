package org.skepsun.kototoro.ios.compat

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Field
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * Android WebView for extensions on iOS, backed by WKWebView in the app. Kotlin talks to the app through one
 * native method, [nativeCommand] (`operation`, view id, JSON payload -> text), which the app registers with
 * `ktr_webkit_install`; the app reports page events through [onNativeEvent]. Everything else (the provider behind
 * `android.webkit.WebView`, the cookie manager, JavaScript interfaces) lives here.
 *
 * Operations: `create`, `destroy`, `load`, `post`, `html`, `evaluate`, `interfaces`, `configure`, `stop`, `reload`,
 * `back`, `forward`, `go`, `canGo`, `state`, `clearCache`, and on view 0 `cookies.set`, `cookies.get`, `cookies.list`,
 * `cookies.clear`. Events: `started`, `finished`, `progress`, `title`, `console`, `failed`, `crashed`, `call`,
 * `navigate`, `request`.
 */
object IosWebKit {
    internal val json = Json { ignoreUnknownKeys = true }
    private val views = ConcurrentHashMap<Long, IosWebViewProvider>()
    /** JavaScript-interface calls without a result run here in order, as Android runs them off the UI thread. */
    internal val calls = Executors.newSingleThreadExecutor { Thread(it, "Kototoro WebView JavaScript").apply { isDaemon = true } }

    /** Answered by the app; registered natively before the platform starts. */
    @JvmStatic
    external fun nativeCommand(operation: String, view: Long, payload: String): String?

    /** True once the app registered [nativeCommand]. */
    val isAvailable: Boolean get() = System.getProperty("kototoro.webkit") == "native"

    internal fun command(operation: String, view: Long = 0, payload: JsonObject = JsonObject(emptyMap())): String =
        nativeCommand(operation, view, payload.toString()).orEmpty().also {
            if (it.startsWith(UNAVAILABLE)) throw UnsupportedOperationException(it.removePrefix(UNAVAILABLE))
        }

    internal fun register(id: Long, provider: IosWebViewProvider) { views[id] = provider }

    internal fun unregister(id: Long) { views.remove(id) }

    /** Page events from the app, on its threads; the answer is text (JSON or a flag), "" when there is none. */
    @JvmStatic
    fun onNativeEvent(view: Long, name: String, payload: String): String {
        val provider = views[view] ?: return ""
        return try {
            provider.event(name, json.parseToJsonElement(payload) as? JsonObject ?: JsonObject(emptyMap()))
        } catch (error: Throwable) {
            System.err.println("Kototoro WebView event $name failed: $error")
            ""
        }
    }

    /**
     * Makes `android.webkit.WebView` and `CookieManager` use WKWebView. Call after the NetworkHelper set up its cookie
     * handler: the cookie manager keeps that store as the one OkHttp reads.
     */
    fun install() {
        WebView.setProviderFactory { view -> IosWebViewProvider(view) }
        org.skepsun.kototoro.ios.shims.IosBrowserPresentation.setHost(
            object : org.skepsun.kototoro.ios.shims.IosBrowserPresentation.Host {
                override fun show(view: android.view.View, width: Int, height: Int, densityDpi: Int) {
                    val provider = views.values.firstOrNull { it.owns(view) }
                        ?: throw UnsupportedOperationException("Presentation content is not a native WebView")
                    provider.present(true, width, height, densityDpi)
                }
                override fun hide(view: android.view.View) {
                    views.values.firstOrNull { it.owns(view) }?.present(false)
                }
            },
        )
        val instance: Field = android.webkit.CookieManager::class.java.getDeclaredField("INSTANCE")
        instance.isAccessible = true
        instance.set(null, IosCookieManager())
    }

    /** Runs [body] on the compatibility main Looper (the "UI thread" WebViewClient callbacks expect) and waits. */
    internal fun <T> onMain(body: () -> T): T {
        val looper = requireNotNull(Looper.getMainLooper()) { "Compatibility main Looper is not running" }
        if (looper.isCurrentThread) return body()
        val task = FutureTask(Callable(body))
        Handler(looper).post(task)
        return try { task.get() } catch (error: ExecutionException) { throw error.cause ?: error }
    }

    internal fun post(body: () -> Unit) {
        val looper = Looper.getMainLooper() ?: return
        Handler(looper).post(body)
    }

    internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun JsonObject.int(key: String): Int = (get(key) as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    internal fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.content == "true"

    internal fun payload(vararg values: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in values) when (value) {
            null -> Unit
            is String -> put(key, value)
            is Number -> put(key, value)
            is Boolean -> put(key, value)
            is JsonElement -> put(key, value)
            else -> put(key, value.toString())
        }
    }

    private const val UNAVAILABLE = "unavailable:"
}
