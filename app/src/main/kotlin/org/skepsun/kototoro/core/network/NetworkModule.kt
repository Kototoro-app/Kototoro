package org.skepsun.kototoro.core.network

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.ConnectionSpec
import okhttp3.CipherSuite
import okhttp3.TlsVersion
import okhttp3.brotli.BrotliInterceptor
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.network.cookies.AndroidCookieJar
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.cookies.PreferencesCookieJar
import org.skepsun.kototoro.core.network.imageproxy.ImageProxyInterceptor
import org.skepsun.kototoro.core.network.imageproxy.RealImageProxyInterceptor
import org.skepsun.kototoro.core.network.proxy.ProxyProvider
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.assertNotInMainThread
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.local.data.LocalStorageManager
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface NetworkModule {

	@Binds
	fun bindCookieJar(androidCookieJar: MutableCookieJar): CookieJar

	@Binds
	fun bindImageProxyInterceptor(impl: RealImageProxyInterceptor): ImageProxyInterceptor

	companion object {

		@Provides
		@Singleton
		fun provideCookieJar(
			@ApplicationContext context: Context
		): MutableCookieJar = runCatching {
			AndroidCookieJar()
		}.getOrElse { e ->
			e.printStackTraceDebug()
			// WebView is not available
			PreferencesCookieJar(context)
		}

		@Provides
		@Singleton
		fun provideHttpCache(
			localStorageManager: LocalStorageManager,
		): Cache = localStorageManager.createHttpCache()

		@Provides
		@Singleton
		@BaseHttpClient
		fun provideBaseHttpClient(
			@ApplicationContext contextProvider: Provider<Context>,
			cache: Cache,
			cookieJar: CookieJar,
			settings: AppSettings,
			proxyProvider: ProxyProvider,
		): OkHttpClient = OkHttpClient.Builder().apply {
			assertNotInMainThread()
			val chromeTlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
				.tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
				.cipherSuites(
					CipherSuite.TLS_AES_128_GCM_SHA256,
					CipherSuite.TLS_AES_256_GCM_SHA384,
					CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
					CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
					CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
					CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
					CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
					CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
					CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
				)
				.build()
			connectionSpecs(listOf(chromeTlsSpec, ConnectionSpec.CLEARTEXT))
			connectTimeout(20, TimeUnit.SECONDS)
			readTimeout(60, TimeUnit.SECONDS)
			writeTimeout(20, TimeUnit.SECONDS)
			callTimeout(300, TimeUnit.SECONDS)
			cookieJar(cookieJar)
			proxySelector(proxyProvider.selector)
			proxyAuthenticator(proxyProvider.authenticator)
			dns(DoHManager(cache, settings))
			if (settings.isSSLBypassEnabled) {
				disableCertificateVerification()
			} else {
				installExtraCertificates(contextProvider.get())
			}
			cache(cache)
			// Send requests normally first; GZipInterceptor only retries compatible GET/HEAD
			// requests after a 400/415 response.
			addInterceptor(GZipInterceptor())
			addInterceptor(CloudFlareInterceptor())
			addInterceptor(RateLimitInterceptor())
			addNetworkInterceptor(BrotliInterceptor)
			if (BuildConfig.DEBUG) {
				addInterceptor(CurlLoggingInterceptor())
			}
		}.build()

		@Provides
		@Singleton
		@ContentHttpClient
		fun provideContentHttpClient(
			@BaseHttpClient baseClient: OkHttpClient,
			commonHeadersInterceptor: CommonHeadersInterceptor,
		): OkHttpClient = baseClient.newBuilder().apply {
			addNetworkInterceptor(CacheLimitInterceptor())
			addInterceptor(commonHeadersInterceptor)
		}.build()

	}
}
