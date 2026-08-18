package org.skepsun.kototoro.core.network.proxy

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okio.IOException
import org.skepsun.kototoro.core.exceptions.ProxyConfigException
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.net.Authenticator as JavaAuthenticator

@Singleton
class ProxyProvider @Inject constructor(
	private val settings: AppSettings,
) {

	@Volatile
	private var cachedSelection: CachedSelection? = null
	private val webViewConfigMutex = Mutex()
	private var appliedWebViewProxy: ProxySnapshot? = null

	private val directSelection = listOf(Proxy.NO_PROXY)

	val selector = object : ProxySelector() {
		override fun select(uri: URI?): List<Proxy> {
			return getSelection()
		}

		override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
			ioe?.printStackTraceDebug()
		}
	}

	val authenticator = ProxyAuthenticator()

	init {
		JavaAuthenticator.setDefault(authenticator)
	}

	suspend fun applyWebViewConfig() = webViewConfigMutex.withLock {
		val isProxyEnabled = isProxyEnabled()
		if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
			if (isProxyEnabled) {
				throw IllegalArgumentException("Proxy for WebView is not supported") // TODO localize
			}
		} else {
			val controller = ProxyController.getInstance()
			if (settings.proxyType == Proxy.Type.DIRECT) {
				if (appliedWebViewProxy == null) {
					return@withLock
				}
				suspendCoroutine { cont ->
					controller.clearProxyOverride(
						(cont.context[CoroutineDispatcher] ?: Dispatchers.Main).asExecutor(),
					) {
						cont.resume(Unit)
					}
				}
				appliedWebViewProxy = null
			} else {
				val proxyConfigData = requireProxyConfig()
				val snapshot = ProxySnapshot(
					type = proxyConfigData.type,
					address = proxyConfigData.address,
					port = proxyConfigData.port,
				)
				if (appliedWebViewProxy == snapshot) {
					return@withLock
				}
				val url = buildString {
					when (proxyConfigData.type) {
						Proxy.Type.DIRECT -> Unit
						Proxy.Type.HTTP -> append("http")
						Proxy.Type.SOCKS -> append("socks")
					}
					append("://")
					append(proxyConfigData.address)
					append(':')
					append(proxyConfigData.port)
				}
				if (proxyConfigData.type == Proxy.Type.SOCKS) {
					System.setProperty("java.net.socks.username", settings.proxyLogin)
					System.setProperty("java.net.socks.password", settings.proxyPassword)
				}
				val proxyConfig = ProxyConfig.Builder()
					.addProxyRule(url)
					.build()
				suspendCoroutine { cont ->
					controller.setProxyOverride(
						proxyConfig,
						(cont.context[CoroutineDispatcher] ?: Dispatchers.Main).asExecutor(),
					) {
						cont.resume(Unit)
					}
				}
				appliedWebViewProxy = snapshot
			}
		}
	}

	private fun isProxyEnabled() = settings.proxyType != Proxy.Type.DIRECT

	private fun getSelection(): List<Proxy> {
		if (!isProxyEnabled()) {
			return directSelection
		}
		val proxyConfig = requireProxyConfig()
		val snapshot = ProxySnapshot(
			type = proxyConfig.type,
			address = proxyConfig.address,
			port = proxyConfig.port,
		)
		cachedSelection?.takeIf { it.snapshot == snapshot }?.let {
			return it.proxies
		}
		val proxy = Proxy(proxyConfig.type, InetSocketAddress(proxyConfig.address, proxyConfig.port))
		return listOf(proxy).also {
			cachedSelection = CachedSelection(snapshot, it)
		}
	}

	private fun requireProxyConfig(): ProxyConfigData {
		val type = settings.proxyType
		val address = settings.proxyAddress?.trim().orEmpty()
		val port = settings.proxyPort
		if (type == Proxy.Type.DIRECT || address.isEmpty() || port !in 1..0xFFFF) {
			throw ProxyConfigException()
		}
		return ProxyConfigData(
			type = type,
			address = address,
			port = port,
		)
	}

	private data class ProxySnapshot(
		val type: Proxy.Type,
		val address: String,
		val port: Int,
	)

	private data class ProxyConfigData(
		val type: Proxy.Type,
		val address: String,
		val port: Int,
	)

	private data class CachedSelection(
		val snapshot: ProxySnapshot,
		val proxies: List<Proxy>,
	)

	inner class ProxyAuthenticator : Authenticator, JavaAuthenticator() {

		override fun authenticate(route: Route?, response: Response): Request? {
			if (!isProxyEnabled()) {
				return null
			}
			if (response.request.header(CommonHeaders.PROXY_AUTHORIZATION) != null) {
				return null
			}
			val login = settings.proxyLogin ?: return null
			val password = settings.proxyPassword ?: return null
			val credential = Credentials.basic(login, password)
			return response.request.newBuilder()
				.header(CommonHeaders.PROXY_AUTHORIZATION, credential)
				.build()
		}

		public override fun getPasswordAuthentication(): PasswordAuthentication? {
			if (!isProxyEnabled()) {
				return null
			}
			val login = settings.proxyLogin ?: return null
			val password = settings.proxyPassword ?: return null
			return PasswordAuthentication(login, password.toCharArray())
		}
	}
}
