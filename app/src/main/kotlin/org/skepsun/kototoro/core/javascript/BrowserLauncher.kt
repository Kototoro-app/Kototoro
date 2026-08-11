package org.skepsun.kototoro.core.javascript

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager as WebViewCookieManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.browser.BrowserActivity
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar
import org.skepsun.kototoro.core.parser.legado.LegadoNetworkUtils
import org.skepsun.kototoro.parsers.model.ContentSource

/**
 * 浏览器启动器
 * 
 * 用于从 JavaScript 代码中启动浏览器并等待结果
 * 
 * 注意: 这个实现需要在 Activity 上下文中使用
 */
class BrowserLauncher(
    private val context: Context,
    private val cookieJar: PersistentCookieJar? = null
) {
    data class BrowserWaitResult(
        val url: String,
        val html: String,
    )

    
    companion object {
        private const val TAG = "BrowserLauncher"
        private const val TIMEOUT_SECONDS = 300L // 5 minutes timeout
    }
    
    /**
     * 启动浏览器并等待用户完成操作
     * 
     * 此方法会阻塞调用线程，直到浏览器关闭或超时
     * 
     * @param url 要打开的 URL
     * @param title 浏览器标题
     * @param source 源信息（可选）
     * @return 浏览器最终页面的 HTML 内容，如果失败则返回空字符串
     */
    fun launchAndWait(
        url: String,
        title: String,
        source: ContentSource? = null,
        refetchAfterSuccess: Boolean = true,
        html: String? = null,
    ): BrowserWaitResult? {
        Log.i(TAG, "Launching browser: url=$url, title=$title")
        val token = BrowserVerificationBridge.register()
        // 创建 Intent
        val intent = AppRouter.browserIntent(
            context = context,
            url = url,
            source = source,
            title = title
        ).putExtra(AppRouter.KEY_BROWSER_WAIT_TOKEN, token)
            .putExtra(AppRouter.KEY_BROWSER_REFETCH_AFTER_SUCCESS, refetchAfterSuccess)
            .putExtra(AppRouter.KEY_BROWSER_HTML, html)
        
        // 添加标志以在新任务中启动
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        // 启动 Activity
        try {
            context.startActivity(intent)
            val result = BrowserVerificationBridge.await(token, TIMEOUT_SECONDS)
            syncCookiesFromWebView(url)
            if (result == null) {
                Log.w(TAG, "Browser operation timed out or canceled")
                return null
            }
            syncCookiesFromWebView(result.url)
            Log.i(TAG, "Browser verification completed: finalUrl=${result.url}")
            return BrowserWaitResult(
                url = result.url.ifBlank { url },
                html = result.html,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch browser", e)
            BrowserVerificationBridge.cancel(token)
            return null
        }
    }
    
    /**
     * 从 Activity 上下文启动浏览器并等待结果
     * 
     * 这个方法需要在 Activity 的生命周期中注册 ActivityResultLauncher
     * 
     * @param activity Activity 上下文
     * @param url 要打开的 URL
     * @param title 浏览器标题
     * @param source 源信息（可选）
     * @return 浏览器最终页面的 HTML 内容
     */
    suspend fun launchAndWaitFromActivity(
        activity: FragmentActivity,
        url: String,
        title: String,
        source: ContentSource? = null
    ): String {
        Log.i(TAG, "Launching browser from activity: url=$url, title=$title")
        
        val result = CompletableDeferred<String>()
        
        // 创建 Intent
        val intent = AppRouter.browserIntent(
            context = activity,
            url = url,
            source = source,
            title = title
        )
        
        // 使用 ActivityResultContracts 启动
        // 注意: 这需要在 Activity 创建时注册，不能在运行时注册
        // 因此这个方法目前无法完全实现
        
        Log.w(TAG, "ActivityResultLauncher approach requires pre-registration")
        result.complete("")
        
        return result.await()
    }
    
    /**
     * 从 WebView 提取 Cookie 并同步到 PersistentCookieJar
     * 
     * @param url 要提取 Cookie 的 URL
     */
    fun syncCookiesFromWebView(url: String) {
        if (cookieJar == null) {
            Log.w(TAG, "CookieJar not provided, skipping cookie sync")
            return
        }
        
        try {
            val httpUrl = url.toHttpUrlOrNull()
            if (httpUrl == null) {
                Log.w(TAG, "Invalid URL for cookie sync")
                return
            }
            
            // 获取 WebView 的 CookieManager
            val webViewCookieManager = WebViewCookieManager.getInstance()
            val cookieString = webViewCookieManager.getCookie(url)
            
            if (cookieString.isNullOrEmpty()) {
                Log.d(TAG, "No cookies found in WebView for: $url")
                return
            }
            
            Log.d(TAG, "Syncing cookies from WebView: $cookieString")
            
            // 解析 Cookie 字符串并保存到 CookieJar
            val cookies = parseCookieString(cookieString, httpUrl)
            cookieJar.setCookies(url, cookies)
            Log.d(TAG, "Saved ${cookies.size} cookies")
            
            Log.i(TAG, "Successfully synced ${cookies.size} cookies from WebView")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync cookies from WebView", e)
        }
    }
    
    /**
     * 解析 Cookie 字符串为 OkHttp Cookie 对象列表
     * 
     * @param cookieString Cookie 字符串 (格式: "name1=value1; name2=value2")
     * @param url HTTP URL
     * @return Cookie 对象列表
     */
    private fun parseCookieString(cookieString: String, url: okhttp3.HttpUrl): List<Cookie> {
        val rootDomain = LegadoNetworkUtils.getSubDomain(url.toString())
        return cookieString.split(";").mapNotNull { cookiePart ->
            val trimmed = cookiePart.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            
            val parts = trimmed.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            
            val name = parts[0].trim()
            val value = parts[1].trim()
            
            try {
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(rootDomain)
                    .path("/")
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cookie: ${e.javaClass.simpleName}")
                null
            }
        }
    }
}
