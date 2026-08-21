package org.skepsun.kototoro.core.network.webview

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView
import android.util.Log
import androidx.annotation.MainThread
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the UI-facing identity of reusable browser sessions. */
@Singleton
class BrowserSessionManager @Inject constructor() {
    private data class SessionHandle(
        val webView: WebView,
        var attachedHost: ViewGroup? = null,
    )

    private val sessions = ConcurrentHashMap<String, SessionHandle>()

    @MainThread
    fun register(sessionId: String, webView: WebView) {
        sessions[sessionId] = SessionHandle(webView)
    }

    @MainThread
    fun unregister(sessionId: String, webView: WebView? = null) {
        val handle = sessions[sessionId] ?: return
        if (webView == null || handle.webView === webView) {
            handle.attachedHost?.let { host -> detach(handle.webView, host) }
            sessions.remove(sessionId, handle)
        }
    }

    @MainThread
    fun attach(sessionId: String, activity: Activity): ViewGroup? {
        val handle = sessions[sessionId] ?: return null
        handle.attachedHost?.let { existingHost ->
            if (existingHost.context === activity || existingHost.rootView.context === activity) {
                return existingHost
            }
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        return runCatching {
            handle.attachedHost?.let { detach(handle.webView, it) }
            (handle.webView.parent as? ViewGroup)?.removeView(handle.webView)
            handle.webView.alpha = 1f
            handle.webView.visibility = WebView.VISIBLE
            handle.webView.translationY = 0f
            content.addView(handle.webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            handle.attachedHost = content
            content
        }.getOrElse {
            Log.w(TAG, "Unable to attach BrowserSession: session=$sessionId", it)
            null
        }
    }

    @MainThread
    fun detach(sessionId: String, host: ViewGroup): Boolean {
        val handle = sessions[sessionId] ?: return false
        if (handle.attachedHost !== host) return false
        detach(handle.webView, host)
        handle.attachedHost = null
        return true
    }

    @MainThread
    fun isAttached(sessionId: String): Boolean = sessions[sessionId]?.attachedHost != null

    private fun detach(webView: WebView, host: ViewGroup) {
        runCatching { host.removeView(webView) }
            .onFailure { Log.w(TAG, "Unable to detach BrowserSession WebView", it) }
    }

    private companion object {
        const val TAG = "BrowserSessionManager"
    }
}
