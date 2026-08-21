package org.skepsun.kototoro.browser

interface BrowserCallback : OnHistoryChangedListener {

    fun onLoadingStateChanged(isLoading: Boolean)

    fun onTitleChanged(title: CharSequence, subtitle: CharSequence?)

    fun onPageFinished(webView: android.webkit.WebView, url: String)
}
