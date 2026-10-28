package org.skepsun.kototoro.desktop.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.library.DriveSyncConflictException
import org.skepsun.kototoro.core.library.DriveSyncStage
import org.skepsun.kototoro.core.library.GoogleDriveSyncCoordinator
import org.skepsun.kototoro.core.library.LibraryDriveSync
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncApi
import org.skepsun.kototoro.sync.google.domain.DriveSyncProtocolException
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncApiException
import java.io.Closeable
import java.util.UUID

internal data class DesktopDriveUi(
    val signedIn: Boolean = false,
    val account: String? = null,
    val enabled: Boolean = false,
    val syncOnStart: Boolean = true,
    val intervalMinutes: Int = 360,
    val syncing: Boolean = true,
    val stage: DriveSyncStage? = null,
    val authorizationRequired: Boolean = false,
    val lastSync: Long = 0,
    val lastAttempt: Long = 0,
    val failure: DesktopDriveFailure? = null,
)

/** Owns desktop authorization and local settings; the synchronization algorithm is shared with Android and iOS. */
internal class DesktopGoogleDriveOperations(
    storage: DesktopRuntime,
    httpClient: HttpClient? = null,
    credentialStore: DesktopDriveCredentialStore? = null,
    openBrowser: (String) -> Boolean = ::openDesktopUrl,
    private val clock: () -> Long = System::currentTimeMillis,
    clientSecret: String = DesktopGoogleOAuthConfig.clientSecret,
) : Closeable {
    private val preferences = storage.preferences.open("desktop_google_drive")
    private val ownsHttp = httpClient == null
    private val http = httpClient ?: HttpClient(Java) {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 120_000
        }
    }
    private val auth = DesktopGoogleDriveAuth(http, openBrowser = openBrowser, clock = clock, clientSecret = clientSecret)
    private val api = GoogleDriveSyncApi(http)
    private val credentials = credentialStore ?: desktopDriveCredentialStore(storage.paths.root)
    private val coordinator = GoogleDriveSyncCoordinator(LibraryDriveSync(storage.database)::merge, clock)
    private val mutex = Mutex()
    private val mutableUi = MutableStateFlow(DesktopDriveUi())
    val ui = mutableUi.asStateFlow()
    private var credential: DesktopDriveCredential? = null
    private var deviceId = ""

    suspend fun restore() = operation {
        val saved = preferences.snapshot()
        deviceId = (saved["device_id"] as? SourcePreferenceValue.Text)?.value ?: UUID.randomUUID().toString().also {
            save(mapOf("device_id" to SourcePreferenceValue.Text(it)))
        }
        mutableUi.update { it.copy(
            enabled = (saved["enabled"] as? SourcePreferenceValue.Toggle)?.value ?: false,
            syncOnStart = (saved["sync_on_start"] as? SourcePreferenceValue.Toggle)?.value ?: true,
            intervalMinutes = (saved["interval_minutes"] as? SourcePreferenceValue.Integer)?.value
                ?.takeIf { value -> value in INTERVALS } ?: 360,
            lastSync = (saved["last_sync"] as? SourcePreferenceValue.Text)?.value?.toLongOrNull() ?: 0,
            lastAttempt = (saved["last_attempt"] as? SourcePreferenceValue.Text)?.value?.toLongOrNull() ?: 0,
        ) }
        val stored = credentialCall { credentials.read() }
        credential = stored?.let {
            runCatching { Json.decodeFromString(DesktopDriveCredential.serializer(), it) }.getOrNull()
        }?.takeIf { it.clientId == DESKTOP_GOOGLE_CLIENT_ID && it.refreshToken.isNotBlank() && it.accountId.isNotBlank() }
        mutableUi.update { it.copy(signedIn = credential != null, account = credential?.email,
            authorizationRequired = credential == null && (stored != null || it.enabled)) }
    }

    suspend fun signIn() = operation {
        credentialCall { credentials.read() }
        val authorized = auth.authorize()
        persistCredential(authorized)
        // A different account must not inherit the former account's successful-sync time or startup cooldown.
        save(mapOf("last_sync" to null, "last_attempt" to null))
        mutableUi.update { it.copy(signedIn = true, account = authorized.email,
            authorizationRequired = false, lastSync = 0, lastAttempt = 0) }
    }

    suspend fun disconnect() = operation {
        credentialCall { credentials.clear() }
        credential = null
        save(mapOf("enabled" to SourcePreferenceValue.Toggle(false), "last_sync" to null, "last_attempt" to null))
        // Revocation affects other clients in the Google Cloud project, so disconnect only this machine.
        mutableUi.update { it.copy(signedIn = false, account = null, enabled = false,
            authorizationRequired = false, lastSync = 0, lastAttempt = 0) }
    }

    suspend fun settings(enabled: Boolean = ui.value.enabled, interval: Int = ui.value.intervalMinutes,
        onStart: Boolean = ui.value.syncOnStart): Boolean = operation {
        require(interval in INTERVALS)
        save(mapOf("enabled" to SourcePreferenceValue.Toggle(enabled),
            "interval_minutes" to SourcePreferenceValue.Integer(interval),
            "sync_on_start" to SourcePreferenceValue.Toggle(onStart)))
        mutableUi.update { it.copy(enabled = enabled, intervalMinutes = interval, syncOnStart = onStart) }
    }

    suspend fun sync(): Boolean {
        if (!ui.value.enabled || !ui.value.signedIn || ui.value.authorizationRequired) return false
        return operation {
            val attempted = clock()
            mutableUi.update { it.copy(lastAttempt = attempted) }
            save(mapOf("last_attempt" to SourcePreferenceValue.Text(attempted.toString())))
            var current = credential ?: throw DesktopDriveException(DesktopDriveFailure.AUTHORIZATION)
            if (current.expiresAt - clock() <= 60_000) {
                current = auth.refresh(current)
                persistCredential(current)
            }
            try {
                synchronize(current.accessToken)
            } catch (error: GoogleDriveSyncApiException) {
                if (error.code != 401) throw error
                current = auth.refresh(current)
                persistCredential(current)
                synchronize(current.accessToken)
            }
            val completed = clock()
            save(mapOf("last_sync" to SourcePreferenceValue.Text(completed.toString())))
            mutableUi.update { it.copy(lastSync = completed, authorizationRequired = false) }
        }
    }

    fun shouldSyncOnStart(): Boolean = ui.value.run {
        enabled && signedIn && !authorizationRequired && syncOnStart && clock() - lastAttempt >= 15 * 60_000
    }

    /** Timer lifetime belongs to the app. A failed run backs off instead of spinning on an unchanged due time. */
    fun nextSyncDelay(): Long? = ui.value.run {
        if (!enabled || !signedIn || authorizationRequired || intervalMinutes == 0) return null
        if (failure == DesktopDriveFailure.CLIENT_SECRET || failure == DesktopDriveFailure.CONFIGURATION) return null
        val due = if (failure == null) lastSync + intervalMinutes * 60_000L else lastAttempt + 10 * 60_000L
        (due - clock()).coerceAtLeast(0L)
    }

    private suspend fun synchronize(token: String) {
        coordinator.sync(api, token, deviceId) { stage -> mutableUi.update { it.copy(stage = stage) } }
    }

    private fun persistCredential(value: DesktopDriveCredential) {
        credentialCall { credentials.write(Json.encodeToString(DesktopDriveCredential.serializer(), value)) }
        credential = value
    }

    private fun save(changes: Map<String, SourcePreferenceValue?>) {
        if (!preferences.edit(SourcePreferenceEdit(changes = changes))) {
            throw DesktopDriveException(DesktopDriveFailure.SETTINGS)
        }
    }

    private fun <T> credentialCall(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE)
    } catch (_: LinkageError) {
        throw DesktopDriveException(DesktopDriveFailure.CREDENTIAL_STORE)
    }

    private suspend fun operation(block: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext false
        mutableUi.update { it.copy(syncing = true, failure = null, stage = null) }
        try {
            block()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = when (error) {
                is DesktopDriveException -> error.failure
                is IllegalArgumentException -> DesktopDriveFailure.PROTOCOL
                is DriveSyncConflictException -> DesktopDriveFailure.CONFLICT
                is GoogleDriveSyncApiException -> when (error.code) {
                    401 -> DesktopDriveFailure.AUTHORIZATION
                    403 -> DesktopDriveFailure.ACCESS
                    else -> DesktopDriveFailure.NETWORK
                }
                else -> DesktopDriveFailure.NETWORK
            }
            mutableUi.update { it.copy(failure = failure,
                authorizationRequired = it.authorizationRequired ||
                    (failure == DesktopDriveFailure.AUTHORIZATION && (it.signedIn || it.enabled)) ||
                    (failure == DesktopDriveFailure.CREDENTIAL_STORE && it.enabled)) }
            false
        } finally {
            mutableUi.update { it.copy(syncing = false, stage = null) }
            mutex.unlock()
        }
    }

    override fun close() { if (ownsHttp) http.close() }

    companion object { val INTERVALS = setOf(0, 360, 720, 1440, 10080) }
}
