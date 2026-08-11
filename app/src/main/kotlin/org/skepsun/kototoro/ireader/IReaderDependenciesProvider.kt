package org.skepsun.kototoro.ireader

import android.content.Context
import android.webkit.CookieManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.BrowserUserAgent
import ireader.core.http.BrowserEngine
import ireader.core.http.CloudflareBypassHandler
import ireader.core.http.CookieSynchronizer
import ireader.core.http.HttpClientsInterface
import ireader.core.http.NetworkConfig
import ireader.core.http.SSLConfiguration
import ireader.core.prefs.Preference
import ireader.core.prefs.PreferenceStore
import ireader.core.source.Dependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import okhttp3.Headers
import okhttp3.Interceptor
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.exceptions.resolve.CaptchaAutoResolveCoordinator
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.UserAgentProvider
import org.skepsun.kototoro.parsers.model.ContentSource
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides a [Dependencies] instance for IReader extensions loaded in Kototoro.
 *
 * This is a minimal implementation that satisfies the constructor requirement of
 * IReader's HttpSource base class. Extensions use Dependencies to access HTTP
 * clients and preferences.
 */
object IReaderDependenciesProvider {

    fun createSession(
        context: Context,
        kototoroClient: okhttp3.OkHttpClient,
        captchaCoordinator: CaptchaAutoResolveCoordinator,
    ): IReaderDependenciesSession {
        val sourceRef = IReaderSourceRef()
        val httpClients = KotoHttpClients(
            context = context,
            kototoroClient = kototoroClient,
            sourceRef = sourceRef,
            captchaCoordinator = captchaCoordinator,
        )
        val preferences = InMemoryPreferenceStore()
        return IReaderDependenciesSession(
            dependencies = Dependencies(
                httpClients = httpClients,
                preferences = preferences,
            ),
            sourceRef = sourceRef,
        )
    }
}

class IReaderDependenciesSession internal constructor(
    val dependencies: Dependencies,
    private val sourceRef: IReaderSourceRef,
) {
    fun bindSource(source: ContentSource) {
        sourceRef.source = source
    }
}

internal class IReaderSourceRef {
    @Volatile
    var source: ContentSource? = null
}

/**
 * Minimal HttpClientsInterface for IReader extensions running in Kototoro.
 * Provides basic Ktor HttpClient with OkHttp engine.
 */
private class KotoHttpClients(
    context: Context,
    kototoroClient: okhttp3.OkHttpClient,
    sourceRef: IReaderSourceRef,
    captchaCoordinator: CaptchaAutoResolveCoordinator,
) : HttpClientsInterface {
    override val browser: BrowserEngine = BrowserEngine()
    override val config: NetworkConfig = NetworkConfig()
    override val sslConfig: SSLConfiguration = SSLConfiguration()
    override val cookieSynchronizer: CookieSynchronizer
        get() = throw UnsupportedOperationException("CookieSynchronizer not available in Kototoro bridge")
    override val cloudflareBypassHandler: CloudflareBypassHandler = KotoIReaderCloudflareBypassHandler(
        context = context,
        sourceRef = sourceRef,
        captchaCoordinator = captchaCoordinator,
    )

    // Some IReader extensions (e.g. novelfire) format page numbers as {N} in
    // URL query values. The server expects plain N, so we strip the braces.
    private val sanitizedClient = kototoroClient.newBuilder().apply {
        interceptors().add(0, Interceptor { chain ->
            val original = chain.request()
            val originalUrl = original.url
            val rawUrl = originalUrl.toString()
            val requestBuilder = original.newBuilder()
            sourceRef.source?.let { source ->
                requestBuilder.tag(ContentSource::class.java, source)
            }
            if ('{' in rawUrl) {
                requestBuilder.url(rawUrl.replace(Regex("\\{(\\d+)\\}"), "$1"))
            }
            chain.proceed(requestBuilder.build())
        })
    }.build()

    override val default: HttpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = sanitizedClient
        }
        BrowserUserAgent()
    }

    override val cloudflareClient: HttpClient = default
}

private class KotoIReaderCloudflareBypassHandler(
    private val context: Context,
    private val sourceRef: IReaderSourceRef,
    private val captchaCoordinator: CaptchaAutoResolveCoordinator,
) : CloudflareBypassHandler {

    private val cache = ConcurrentHashMap<String, CloudflareBypassHandler.CookieData>()

    override suspend fun bypass(url: String): CloudflareBypassHandler.BypassResult? {
        val source = sourceRef.source ?: return null
        val challengeUrl = url.toIReaderChallengeUrl() ?: return null
        val userAgent = UserAgentProvider.get(context)
        val exception = CloudFlareProtectedException(
            url = challengeUrl,
            source = source,
            headers = Headers.headersOf(CommonHeaders.USER_AGENT, userAgent),
        )
        if (!captchaCoordinator.resolveInBackground(source, exception)) {
            return null
        }
        val cookieData = readCookies(challengeUrl, userAgent) ?: return null
        cache[url.toIReaderCacheKey()] = cookieData
        return CloudflareBypassHandler.BypassResult(
            cfClearance = cookieData.cfClearance,
            cfBm = cookieData.cfBm,
            userAgent = cookieData.userAgent,
            expiresAt = cookieData.expiresAt,
        )
    }

    override fun getCachedCookies(domain: String): CloudflareBypassHandler.CookieData? {
        val cacheKey = domain.toIReaderCacheKey()
        cache[cacheKey]?.takeUnless { it.isExpired() }?.let { return it }
        val challengeUrl = domain.toIReaderChallengeUrl() ?: return null
        return readCookies(challengeUrl, UserAgentProvider.get(context))?.also {
            cache[cacheKey] = it
        }
    }

    private fun readCookies(
        url: String,
        userAgent: String,
    ): CloudflareBypassHandler.CookieData? {
        val cookies = CookieManager.getInstance().getCookie(url).toIReaderCookieMap()
        val clearance = cookies[CF_CLEARANCE] ?: return null
        return CloudflareBypassHandler.CookieData(
            cfClearance = clearance,
            cfBm = cookies[CF_BM],
            userAgent = userAgent,
            expiresAt = System.currentTimeMillis() + COOKIE_CACHE_DURATION_MS,
        )
    }

    private companion object {
        const val CF_CLEARANCE = "cf_clearance"
        const val CF_BM = "__cf_bm"
        const val COOKIE_CACHE_DURATION_MS = 30 * 60 * 1000L
    }
}

internal fun String.toIReaderChallengeUrl(): String? {
    val value = trim()
    if (value.isEmpty()) return null
    return if (value.startsWith("http://") || value.startsWith("https://")) {
        value
    } else {
        "https://${value.trimStart('.')}/"
    }
}

internal fun String?.toIReaderCookieMap(): Map<String, String> = this
    ?.split(';')
    ?.mapNotNull { item ->
        val separator = item.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        item.substring(0, separator).trim() to item.substring(separator + 1).trim()
    }
    ?.toMap()
    .orEmpty()

internal fun String.toIReaderCacheKey(): String = runCatching {
    URI(toIReaderChallengeUrl()).host?.lowercase()
}.getOrNull() ?: trim().lowercase()

/**
 * Simple in-memory PreferenceStore for IReader extensions.
 * Values are not persisted across app restarts.
 */
private class InMemoryPreferenceStore : PreferenceStore {

    private val store = mutableMapOf<String, Any?>()

    override fun getString(key: String, defaultValue: String): Preference<String> =
        InMemoryPreference(store, key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        InMemoryPreference(store, key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        InMemoryPreference(store, key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        InMemoryPreference(store, key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        InMemoryPreference(store, key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        InMemoryPreference(store, key, defaultValue)

    override fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T
    ): Preference<T> = InMemoryPreference(store, key, defaultValue)

    override fun <T> getJsonObject(
        key: String,
        defaultValue: T,
        serializer: KSerializer<T>,
        serializersModule: SerializersModule
    ): Preference<T> = InMemoryPreference(store, key, defaultValue)
}

@Suppress("UNCHECKED_CAST")
private class InMemoryPreference<T>(
    private val store: MutableMap<String, Any?>,
    private val key: String,
    private val defaultVal: T,
) : Preference<T> {

    override fun key(): String = key

    override fun get(): T = (store[key] as? T) ?: defaultVal

    override fun set(value: T) {
        store[key] = value
    }

    override fun isSet(): Boolean = store.containsKey(key)

    override fun delete() {
        store.remove(key)
    }

    override fun defaultValue(): T = defaultVal

    override fun changes(): Flow<T> = flowOf(get())

    override fun stateIn(scope: CoroutineScope): StateFlow<T> =
        MutableStateFlow(get())
}
