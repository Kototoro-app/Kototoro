package org.skepsun.kototoro.scrobbling.discord.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.skepsun.kototoro.browser.BrowserCallback
import org.skepsun.kototoro.browser.BrowserClient
import org.skepsun.kototoro.parsers.util.removeSurrounding

class DiscordTokenWebClient(private val callback: Callback) : BrowserClient(callback, null) {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var tokenObtained = false
    private var pollCount = 0

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(webView: WebView, url: String) {
        super.onPageFinished(webView, url)
        if (!tokenObtained) {
            // Start polling for token after page load
            pollCount = 0
            scheduleTokenPoll(webView)
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        // Intercept Discord API requests to capture the Authorization header
        if (!tokenObtained && request != null) {
            val url = request.url?.toString().orEmpty()
            if (url.contains("discord.com/api/")) {
                val authHeader = request.requestHeaders?.entries
                    ?.find { it.key.equals("Authorization", ignoreCase = true) }
                    ?.value
                if (!authHeader.isNullOrEmpty() && !authHeader.startsWith("Bot ", ignoreCase = true)) {
                    Log.d(TAG, "Token captured from Authorization header")
                    tokenObtained = true
                    handler.post { callback.onTokenObtained(authHeader) }
                }
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    private fun scheduleTokenPoll(view: WebView) {
        if (tokenObtained || pollCount >= MAX_POLL_COUNT) return
        handler.postDelayed({
            if (!tokenObtained) {
                checkToken(view)
                pollCount++
                scheduleTokenPoll(view)
            }
        }, POLL_INTERVAL_MS)
    }

    private fun checkToken(view: WebView) {
        // Try multiple approaches to extract the token from localStorage
        val script = """
			(function() {
				try {
					// Method 1: Direct localStorage access
					var token = window.localStorage.getItem('token');
					if (token) return token;
					// Method 2: iframe trick for isolated storage
					var iframe = document.createElement('iframe');
					iframe.style.display = 'none';
					document.body.appendChild(iframe);
					token = iframe.contentWindow.localStorage.getItem('token');
					document.body.removeChild(iframe);
					if (token) return token;
				} catch(e) {}
				return null;
			})()
        """.trimIndent()
        view.evaluateJavascript(script) { result ->
            val token = result
                ?.replace("\\\"", "")
                ?.removeSurrounding('"')
                ?.takeUnless { it == "null" || it == "undefined" || it.isEmpty() }
            if (!token.isNullOrEmpty() && !tokenObtained) {
                Log.d(TAG, "Token captured from localStorage poll")
                tokenObtained = true
                callback.onTokenObtained(token)
            }
        }
    }

    interface Callback : BrowserCallback {

        fun onTokenObtained(token: String)
    }

    private companion object {
        const val TAG = "DiscordTokenWebClient"
        const val POLL_INTERVAL_MS = 2000L
        const val MAX_POLL_COUNT = 30 // Poll for up to 60 seconds
    }
}
