package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** Android's `Proxy.Type` with the same order and names, so a backup or a hand-edited store reads the same value. */
enum class DesktopProxyType { DIRECT, HTTP, SOCKS }

/**
 * Android's `ProxySettingsScreen` as one value: the connection type, the optional address/port pair and the optional
 * credentials. The keys are Android's (`proxy_type_2`, `proxy_address`, ...), so both platforms can share a store.
 *
 * The port stays a string because Android stores exactly what the user typed; an unparsable port is reported as an
 * incomplete configuration instead of silently becoming a different proxy.
 */
data class DesktopProxySettings(
    val type: DesktopProxyType = DesktopProxyType.DIRECT,
    val address: String = "",
    val port: String = "",
    val login: String = "",
    val password: String = "",
) {
    val isEnabled: Boolean get() = type != DesktopProxyType.DIRECT

    /** The port the user typed, when it is a usable TCP port. */
    val portNumber: Int? get() = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** A type other than DIRECT needs a host and a port before it can carry a request. */
    val isConfigured: Boolean get() = !isEnabled || (address.isNotBlank() && portNumber != null)

    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_PROXY_TYPE to SourcePreferenceValue.Text(type.name),
        KEY_PROXY_ADDRESS to SourcePreferenceValue.Text(address),
        KEY_PROXY_PORT to SourcePreferenceValue.Text(port),
        KEY_PROXY_LOGIN to SourcePreferenceValue.Text(login),
        KEY_PROXY_PASSWORD to SourcePreferenceValue.Text(password),
    )

    companion object {
        const val KEY_PROXY_TYPE = "proxy_type_2"
        const val KEY_PROXY_ADDRESS = "proxy_address"
        const val KEY_PROXY_PORT = "proxy_port"
        const val KEY_PROXY_LOGIN = "proxy_login"
        const val KEY_PROXY_PASSWORD = "proxy_password"

        fun from(values: Map<String, SourcePreferenceValue>): DesktopProxySettings = DesktopProxySettings(
            type = (values[KEY_PROXY_TYPE] as? SourcePreferenceValue.Text)?.value
                ?.let { name -> DesktopProxyType.entries.firstOrNull { it.name == name } } ?: DesktopProxyType.DIRECT,
            address = (values[KEY_PROXY_ADDRESS] as? SourcePreferenceValue.Text)?.value.orEmpty(),
            port = (values[KEY_PROXY_PORT] as? SourcePreferenceValue.Text)?.value.orEmpty(),
            login = (values[KEY_PROXY_LOGIN] as? SourcePreferenceValue.Text)?.value.orEmpty(),
            password = (values[KEY_PROXY_PASSWORD] as? SourcePreferenceValue.Text)?.value.orEmpty(),
        )
    }
}

/**
 * Android's `ProxyProvider` for Windows. It is the shared SDK client's `ProxySelector` and `proxyAuthenticator`, so one
 * instance decides every source, image, JSON-source and extension request; a change on the settings page takes effect
 * on the next request because the getters read the live value instead of a client built at startup.
 */
class DesktopProxy(private val preferences: SourcePreferences) {
    private val mutableSettings = MutableStateFlow(DesktopProxySettings.from(preferences.snapshot()))
    val settings: StateFlow<DesktopProxySettings> = mutableSettings.asStateFlow()

    /** Cached Java proxy object: the address is unresolved on purpose, so a request never blocks on DNS here. */
    @Volatile
    private var cached: Pair<DesktopProxySettings, Proxy>? = null

    fun save(value: DesktopProxySettings): Boolean {
        val persisted = preferences.edit(SourcePreferenceEdit(changes = value.toPreferences()))
        mutableSettings.value = value
        return persisted
    }

    fun reload() {
        mutableSettings.value = DesktopProxySettings.from(preferences.snapshot())
    }

    /** The proxy a request must use, or null for a direct connection (also for an incomplete configuration). */
    fun proxyFor(value: DesktopProxySettings = mutableSettings.value): Proxy? {
        val port = value.portNumber ?: return null
        val host = value.address.trim()
        if (host.isEmpty()) return null
        val javaType = when (value.type) {
            DesktopProxyType.DIRECT -> return null
            DesktopProxyType.HTTP -> Proxy.Type.HTTP
            DesktopProxyType.SOCKS -> Proxy.Type.SOCKS
        }
        cached?.let { (settings, proxy) -> if (settings == value) return proxy }
        return Proxy(javaType, InetSocketAddress.createUnresolved(host, port)).also { cached = value to it }
    }

    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(proxyFor() ?: Proxy.NO_PROXY)

        override fun connectFailed(uri: URI?, socketAddress: SocketAddress?, error: IOException?) = Unit
    }

    /**
     * Android sends the same basic credentials from an OkHttp `Authenticator`. Only an HTTP proxy answers 407 with a
     * proxy challenge; SOCKS authentication is not part of OkHttp's contract, so those credentials simply do not apply.
     */
    val authenticator: Authenticator = Authenticator { route, response -> proxyAuthorization(route, response) }

    private fun proxyAuthorization(route: Route?, response: Response): Request? {
        val value = mutableSettings.value
        if (!value.isEnabled || value.type != DesktopProxyType.HTTP) return null
        if (value.login.isBlank() && value.password.isEmpty()) return null
        if (response.request.header("Proxy-Authorization") != null) return null
        // Only retry once per proxy, the same guard OkHttp's own examples use.
        if (response.priorResponse?.request?.header("Proxy-Authorization") != null) return null
        if (route == null) return null
        return response.request.newBuilder()
            .header("Proxy-Authorization", Credentials.basic(value.login, value.password))
            .build()
    }
}
