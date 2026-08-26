package org.skepsun.kototoro.core.os

import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import coil3.network.ConnectivityChecker
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.MediatorStateFlow

/**
 * Unified network state for the app, driven by [ConnectivityManager.registerDefaultNetworkCallback]
 * plus [NetworkCapabilities.NET_CAPABILITY_VALIDATED].
 *
 * Semantics (see [NetworkStateLogic]):
 * - [hasNetwork]        — a default network exists (a broken VPN still counts).
 * - [isValidated]       — the default network actually reaches the internet
 *                         (INTERNET && VALIDATED).
 * - [allowAutomaticTraffic] — automatic traffic (Coil loads, background sync, auto-retry) is
 *                         enabled only when the default network is validated. On an invalid VPN
 *                         automatic traffic stops, while user-initiated attempts are NOT gated
 *                         here and keep running.
 * - `value` / [isOnline] (Coil [ConnectivityChecker]) reflect [allowAutomaticTraffic].
 * - [isMetered] / [isDataSaverEnabled] / [isRestricted] are preserved as-is.
 */
class NetworkState(
    private val connectivityManager: ConnectivityManager,
    private val settings: AppSettings,
) : MediatorStateFlow<Boolean>(connectivityManager.isAutomaticTrafficAllowed(settings)), ConnectivityChecker {

    private val callback = NetworkCallbackImpl()

    @Volatile
    private var registered = false

    @Volatile
    private var defaultNetwork: Network? = null

    @Volatile
    private var defaultCapabilities: NetworkCapabilities? = null

    /** 是否存在默认网络（VPN 也算）。用户在设置里关掉离线检查时视为始终存在。 */
    val hasNetwork: Boolean
        get() = settings.isOfflineCheckDisabled || currentNetwork() != null

    /** 默认网络是否真正可上网（INTERNET + VALIDATED）。 */
    val isValidated: Boolean
        get() {
            if (settings.isOfflineCheckDisabled) {
                return true
            }
            val caps = currentCapabilities() ?: return false
            return NetworkStateLogic.isValidated(
                hasInternetCapability = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                hasValidatedCapability = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }

    /** 自动流量是否允许：有默认网络且已验证；无效 VPN 上自动流量停止。 */
    val allowAutomaticTraffic: Boolean
        get() = NetworkStateLogic.allowAutomaticTraffic(
            hasNetwork = hasNetwork,
            isValidated = isValidated,
            offlineCheckDisabled = settings.isOfflineCheckDisabled,
        )

    override val value: Boolean
        get() = allowAutomaticTraffic

    override fun isOnline(): Boolean = allowAutomaticTraffic

    @Synchronized
    override fun onActive() {
        registered = true
        // Seed from the current state so the first read is already correct before callbacks arrive.
        val active = connectivityManager.activeNetwork
        defaultNetwork = active
        defaultCapabilities = active?.let { connectivityManager.getNetworkCapabilities(it) }
        invalidate()
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    @Synchronized
    override fun onInactive() {
        registered = false
        connectivityManager.unregisterNetworkCallback(callback)
        defaultNetwork = null
        defaultCapabilities = null
        invalidate()
    }

    fun isMetered(): Boolean {
        return connectivityManager.isActiveNetworkMetered
    }

    fun isDataSaverEnabled(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        && connectivityManager.restrictBackgroundStatus == RESTRICT_BACKGROUND_STATUS_ENABLED

    fun isRestricted() = isMetered() && isDataSaverEnabled()

    fun isOfflineOrRestricted() = !allowAutomaticTraffic || isRestricted()

    suspend fun awaitForConnection() {
        if (value) {
            return
        }
        first { it }
    }

    private fun currentNetwork(): Network? {
        return if (registered) defaultNetwork else connectivityManager.activeNetwork
    }

    private fun currentCapabilities(): NetworkCapabilities? {
        return if (registered) {
            defaultCapabilities
        } else {
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        }
    }

    private fun invalidate() {
        publishValue(allowAutomaticTraffic)
    }

    private inner class NetworkCallbackImpl : NetworkCallback() {

        override fun onAvailable(network: Network) {
            defaultNetwork = network
            defaultCapabilities = connectivityManager.getNetworkCapabilities(network)
            invalidate()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (defaultNetwork == null) {
                defaultNetwork = network
            }
            defaultCapabilities = networkCapabilities
            invalidate()
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == null || network == defaultNetwork) {
                defaultNetwork = null
                defaultCapabilities = null
            }
            invalidate()
        }

        override fun onUnavailable() {
            defaultNetwork = null
            defaultCapabilities = null
            invalidate()
        }
    }

    private companion object {

        fun ConnectivityManager.isAutomaticTrafficAllowed(settings: AppSettings): Boolean {
            if (settings.isOfflineCheckDisabled) {
                return true
            }
            val caps = getNetworkCapabilities(activeNetwork) ?: return false
            return NetworkStateLogic.isValidated(
                hasInternetCapability = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                hasValidatedCapability = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }
    }
}
