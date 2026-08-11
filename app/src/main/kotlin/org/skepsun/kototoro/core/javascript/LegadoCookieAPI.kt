package org.skepsun.kototoro.core.javascript

import android.util.Log
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar
import org.skepsun.kototoro.core.parser.legado.LegadoNetworkUtils

/**
 * Legado Cookie API 实现
 * 
 * 提供 Legado 兼容的 Cookie 管理 API，供 JavaScript 代码调用
 * 参考 Legado 源码: app/src/main/java/io/legado/app/help/http/CookieStore.kt
 * 
 * 功能:
 * - getCookie(url): 获取指定 URL 的 Cookie 字符串
 * - setCookie(url, cookie): 设置指定 URL 的 Cookie
 * - removeCookie(url): 删除指定 URL 的所有 Cookie
 * - cookieToMap(cookie): 将 Cookie 字符串转换为 Map
 * - mapToCookie(cookieMap): 将 Map 转换为 Cookie 字符串
 */
class LegadoCookieAPI(
    private val cookieJar: PersistentCookieJar
) {
    
    companion object {
        private const val TAG = "LegadoCookieAPI"
    }
    
    /**
     * 获取指定 URL 的 Cookie 字符串
     * 
     * 格式: "key1=value1; key2=value2; key3=value3"
     * 
     * @param url 目标 URL
     * @return Cookie 字符串，如果没有 Cookie 则返回空字符串
     */
    fun getCookie(url: String): String {
        return try {
            cookieJar.getCookieHeader(url)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cookie: ${e.javaClass.simpleName}")
            ""
        }
    }
    
    /**
     * 设置指定 URL 的 Cookie
     * 
     * Cookie 格式支持:
     * - 简单格式: "key1=value1; key2=value2"
     * - 完整格式: "key=value; Domain=example.com; Path=/; Expires=..."
     * 
     * @param url 目标 URL
     * @param cookie Cookie 字符串
     */
    fun setCookie(url: String, cookie: String?) {
        if (cookie.isNullOrBlank()) {
            Log.w(TAG, "Attempted to set an empty cookie")
            return
        }
        
        try {
            val httpUrl = url.toHttpUrl()
            val cookieList = parseCookieString(cookie, httpUrl)
            
            if (cookieList.isNotEmpty()) {
                cookieJar.setCookies(url, cookieList)
                Log.d(TAG, "Set ${cookieList.size} cookies for $url")
            } else {
                Log.w(TAG, "No valid cookies were parsed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set cookie: ${e.javaClass.simpleName}")
        }
    }
    
    /**
     * 替换指定 URL 的 Cookie
     * 
     * 与 setCookie 不同，此方法会合并现有 Cookie 和新 Cookie
     * 如果 key 相同，则新值会覆盖旧值
     * 
     * @param url 目标 URL
     * @param cookie Cookie 字符串
     */
    fun replaceCookie(url: String, cookie: String) {
        if (cookie.isBlank()) {
            Log.w(TAG, "Attempted to replace cookies with an empty value")
            return
        }
        
        try {
            val oldCookie = getCookie(url)
            
            if (oldCookie.isEmpty()) {
                // 没有旧 Cookie，直接设置新 Cookie
                setCookie(url, cookie)
            } else {
                // 合并旧 Cookie 和新 Cookie
                val oldMap = cookieToMap(oldCookie)
                val newMap = cookieToMap(cookie)
                oldMap.putAll(newMap)
                
                val mergedCookie = mapToCookie(oldMap)
                setCookie(url, mergedCookie)
                
                Log.d(TAG, "Replaced cookies for $url (merged ${oldMap.size} cookies)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to replace cookie: ${e.javaClass.simpleName}")
        }
    }
    
    /**
     * 删除指定 URL 的所有 Cookie
     * 
     * @param url 目标 URL
     */
    fun removeCookie(url: String) {
        try {
            cookieJar.removeCookies(url)
            Log.d(TAG, "Removed all cookies for $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove cookies: ${e.javaClass.simpleName}")
        }
    }
    
    /**
     * 将 Cookie 字符串转换为 Map
     * 
     * 输入格式: "key1=value1; key2=value2; key3=value3"
     * 输出格式: {"key1": "value1", "key2": "value2", "key3": "value3"}
     * 
     * @param cookie Cookie 字符串
     * @return Cookie Map
     */
    fun cookieToMap(cookie: String): MutableMap<String, String> {
        val cookieMap = mutableMapOf<String, String>()
        
        if (cookie.isBlank()) {
            return cookieMap
        }
        
        try {
            // 按分号分割 Cookie 对
            val pairs = cookie.split(";")
            
            for (pair in pairs) {
                val trimmedPair = pair.trim()
                if (trimmedPair.isEmpty()) continue
                
                // 按等号分割键值对
                val parts = trimmedPair.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    
                    // 对齐 legado-with-MD3：允许 value 为 "null"
                    if (value.isNotBlank() || value == "null") {
                        cookieMap[key] = value
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cookie string: ${e.javaClass.simpleName}")
        }
        
        return cookieMap
    }
    
    /**
     * 将 Map 转换为 Cookie 字符串
     * 
     * 输入格式: {"key1": "value1", "key2": "value2"}
     * 输出格式: "key1=value1; key2=value2"
     * 
     * @param cookieMap Cookie Map
     * @return Cookie 字符串，如果 Map 为空则返回空字符串
     */
    fun mapToCookie(cookieMap: Map<String, String>?): String {
        if (cookieMap.isNullOrEmpty()) {
            return ""
        }
        
        return try {
            cookieMap.entries.joinToString("; ") { (key, value) ->
                "$key=$value"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert map to cookie string: ${e.javaClass.simpleName}")
            ""
        }
    }
    
    /**
     * 获取指定 URL 和 key 的 Cookie 值
     * 
     * @param url 目标 URL
     * @param key Cookie 名称
     * @return Cookie 值，如果不存在则返回空字符串
     */
    fun getKey(url: String, key: String): String {
        return try {
            val cookie = getCookie(url)
            val cookieMap = cookieToMap(cookie)
            cookieMap[key] ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cookie by key: ${e.javaClass.simpleName}")
            ""
        }
    }
    
    /**
     * 解析 Cookie 字符串为 OkHttp Cookie 对象列表
     * 
     * 支持简单格式和完整格式的 Cookie 字符串
     * 
     * @param cookieString Cookie 字符串
     * @param url HTTP URL
     * @return Cookie 对象列表
     */
    private fun parseCookieString(cookieString: String, url: okhttp3.HttpUrl): List<Cookie> {
        val cookies = mutableListOf<Cookie>()
        val rootDomain = LegadoNetworkUtils.getSubDomain(url.toString())
        
        try {
            // 按分号分割多个 Cookie
            val pairs = cookieString.split(";")
            
            for (pair in pairs) {
                val trimmedPair = pair.trim()
                if (trimmedPair.isEmpty()) continue
                
                // 尝试解析为 OkHttp Cookie
                val cookie = Cookie.parse(url, trimmedPair)
                if (cookie != null) {
                    cookies.add(cookie.toDomainCookie(rootDomain))
                } else {
                    // 如果解析失败，尝试简单格式 "key=value"
                    val parts = trimmedPair.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        
                        if (key.isNotBlank() && (value.isNotBlank() || value == "null")) {
                            // 创建简单的 Cookie（使用 URL 的域名和路径）
                            val simpleCookie = Cookie.Builder()
                                .name(key)
                                .value(value)
                                .domain(rootDomain)
                                .path("/")
                                .build()
                            cookies.add(simpleCookie)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cookie string: ${e.javaClass.simpleName}")
        }
        
        return cookies
    }

    private fun Cookie.toDomainCookie(domain: String): Cookie {
        if (this.domain == domain && !this.hostOnly) return this
        return Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .path(path)
            .apply {
                if (secure) secure()
                if (httpOnly) httpOnly()
                domain(domain)
            }
            .build()
    }
}
