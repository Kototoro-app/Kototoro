package org.koitharu.kotatsu.parsers.webview

/**
 * Represents a WebView request that was intercepted during page loading.
 * Contains the URL, HTTP method, headers, and timestamp of the request.
 */
public data class InterceptedRequest(
    /**
     * The full URL of the intercepted request
     */
    public val url: String,

    /**
     * HTTP method (GET, POST, etc.)
     */
    public val method: String,

    /**
     * Request headers as key-value pairs
     */
    public val headers: Map<String, String>,

    /**
     * Timestamp when the request was intercepted (System.currentTimeMillis())
     */
    public val timestamp: Long,

    /**
     * Optional request body for POST requests
     */
    public val body: String? = null,
) {
    /**
     * Extract parameter value from URL query string
     */
    public fun getQueryParameter(name: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null

        return query.split('&')
            .map { it.split('=', limit = 2) }
            .find { it.size == 2 && it[0] == name }
            ?.get(1)
    }

    /**
     * Check if URL matches a pattern
     */
    public fun urlMatches(pattern: Regex): Boolean = pattern.containsMatchIn(url)

    /**
     * Check if URL contains a specific substring
     */
    public fun urlContains(substring: String): Boolean = url.contains(substring, ignoreCase = true)
}

/**
 * Callback interface for WebView request interception
 */
public interface WebViewRequestInterceptor {
    /**
     * Called when a request is intercepted.
     * Return true to capture this request, false to ignore it.
     */
    public fun shouldCaptureRequest(request: InterceptedRequest): Boolean

    /**
     * Called when interception is complete (timeout or manual stop)
     */
    public fun onInterceptionComplete(capturedRequests: List<InterceptedRequest>)

    /**
     * Called if interception fails due to error
     */
    public fun onInterceptionError(error: Throwable)
}

/**
 * Configuration for WebView request interception
 */
public data class InterceptionConfig(
    public val timeoutMs: Long,
    public val maxRequests: Int = 100,
    public val urlPattern: Regex? = null,
    public val filterScript: String? = null,   // JS containing predicate (last return)
    public val pageScript: String? = null      // JS to actually run in the page
)
