package org.skepsun.kototoro.core.network.jsonsource

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.network.SniBypassSSLSocketFactory
import org.skepsun.kototoro.core.network.ContentHttpClient
import java.net.CookieManager
import java.net.CookiePolicy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Qualifier
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Qualifier for JSON source HTTP client
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class JsonSourceHttpClient

/**
 * Hilt module for JSON source network components
 */
@Module
@InstallIn(SingletonComponent::class)
object JsonSourceNetworkModule {

    /**
     * Provide a CookieManager for JavaScript engine
     * This is used by the JavaScript API to manage cookies
     */
    @Provides
    @Singleton
    fun provideCookieManager(): CookieManager {
        return CookieManager().apply {
            setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        }
    }
    
    /**
     * Provide a specialized HTTP client for JSON sources
     * Includes User-Agent rotation, rate limiting, and logging
     * 
     * Note: Uses lenient SSL verification to support sources with expired/self-signed certificates
     * This matches Legado's behavior for compatibility with various book sources
     */
    @Provides
    @Singleton
    @JsonSourceHttpClient
    fun provideJsonSourceHttpClient(
        @ContentHttpClient baseClient: OkHttpClient,
        userAgentInterceptor: UserAgentInterceptor,
        rateLimitInterceptor: JsonSourceRateLimitInterceptor,
        loggingInterceptor: JsonSourceLoggingInterceptor,
    ): OkHttpClient {
        return baseClient.newBuilder().apply {
            connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            
            // Add JSON source specific interceptors
            addInterceptor(userAgentInterceptor)
            addInterceptor(rateLimitInterceptor)

            // Keep JSON sources always fresh: avoid OkHttp HTTP cache returning stale bodies.
            // This mirrors legado-with-MD3 default (no explicit cache configured).
            cache(null)
            
            // Keep the CloudFlareInterceptor inherited from ContentHttpClient so Legado and TVBox
            // report challenges through the same host-level solver as every other source type.
            
            // Add logging in debug builds
            if (BuildConfig.DEBUG) {
                addInterceptor(loggingInterceptor)
            }
            
            // Use lenient SSL verification for JSON sources
            // This allows accessing sources with expired or self-signed certificates
            // Similar to Legado's approach for maximum compatibility
            configureLenientSsl()
        }.build()
    }

    
    /**
     * Configure lenient SSL verification
     * This trusts all certificates, including expired and self-signed ones
     * 
     * WARNING: This reduces security but is necessary for compatibility with
     * many book sources that have certificate issues
     */
    private fun OkHttpClient.Builder.configureLenientSsl() {
        try {
            // Create a trust manager that trusts all certificates
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            
            sslSocketFactory(SniBypassSSLSocketFactory(sslContext.socketFactory), trustAllCerts[0] as X509TrustManager)
            hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            // If SSL configuration fails, log but continue with default settings
            android.util.Log.w("JsonSourceNetwork", "Failed to configure lenient SSL", e)
        }
    }
}
