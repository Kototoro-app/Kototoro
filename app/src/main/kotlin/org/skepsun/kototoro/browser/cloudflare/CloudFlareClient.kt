package org.skepsun.kototoro.browser.cloudflare

import android.graphics.Bitmap
import android.webkit.WebView
import org.skepsun.kototoro.browser.BrowserClient
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.webview.adblock.AdBlock
import org.skepsun.kototoro.parsers.network.CloudFlareHelper

private const val LOOP_COUNTER = 3

open class CloudFlareClient(
	private val cookieJar: MutableCookieJar,
	private val callback: CloudFlareCallback,
	adBlock: AdBlock,
	private val targetUrl: String,
) : BrowserClient(callback, adBlock) {

	private val oldClearance = getClearance()
	private var counter = 0

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		checkClearance(countFailure = true)
	}

	override fun onPageCommitVisible(view: WebView, url: String) {
		super.onPageCommitVisible(view, url)
		checkClearance(countFailure = false)
		callback.onPageLoaded()
	}

	override fun onPageFinished(webView: WebView, url: String) {
		super.onPageFinished(webView, url)
		checkClearance(countFailure = false)
		callback.onPageLoaded()
	}

	fun reset() {
		counter = 0
	}

	fun checkClearance(): Boolean = checkClearance(countFailure = false)

	private fun checkClearance(countFailure: Boolean): Boolean {
		val clearance = getClearance()
		if (clearance != null && clearance != oldClearance) {
			callback.onCheckPassed()
			return true
		} else if (countFailure) {
			counter++
			if (counter >= LOOP_COUNTER) {
				reset()
				callback.onLoopDetected()
			}
		}
		return false
	}

    override fun onReceivedSslError(
        view: WebView?,
        handler: android.webkit.SslErrorHandler?,
        error: android.net.http.SslError?
    ) {
        // Ignore SSL errors during CloudFlare check to avoid handshake failures on legacy sites
        handler?.proceed()
    }

    override fun onReceivedError(
        view: WebView?,
        request: android.webkit.WebResourceRequest?,
        error: android.webkit.WebResourceError?
    ) {
        // Log errors to help debugging loops
        android.util.Log.w("CloudFlareClient", "WebView error: ${error?.errorCode} - ${error?.description}")
        super.onReceivedError(view, request, error)
    }

    private fun getClearance() = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
}
