package org.skepsun.kototoro.extensions.recovery

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.extensions.install.ExtensionInstallMode
import org.skepsun.kototoro.extensions.install.ExtensionInstallResult
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionLoaderSupport
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.settings.sources.unified.RecoveryActionResult
import org.skepsun.kototoro.settings.sources.unified.RecoveryUiState
import org.skepsun.kototoro.tsundoku.TsundokuExtensionManager

/**
 * Executes source-recovery actions (T5.2/T5.4) and exposes a UI snapshot (T5.1).
 *
 * Flow for every action: derive → plan → execute → re-derive. `run`/`sideLoad`/
 * `confirmSignature` exit "missing" automatically once the re-derivation turns
 * [SourceRecoveryStatus.RESOLVED] (T5.1), and fire a [RecoveryNotifier.notifyRecovered]
 * notification. All actions are serialized through a single [Mutex] so concurrent work on
 * the same key cannot interleave; a per-key in-flight guard makes repeat taps safe.
 *
 * Signature invariant (T5.6): automatic flows never re-associate a source while its
 * signing digest differs from the recorded one. [run] on a
 * [SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED] origin performs NO install and NO
 * digest write — it only re-derives; only an explicit [confirmSignature] mutates the
 * recorded digest, and [rejectSignature] explicitly keeps the recorded digest untouched,
 * leaving the origin in the confirmation-required state.
 *
 * Execution entry points are the real ones from the 3A pipeline:
 *  - repository install / side-load storage: [ExtensionInstallService] + [LocalApkExtensionSupport];
 *  - rescans / manager reloads: the four APK extension managers' `loadExtensions()`;
 *  - JSON re-import: [JsonSourceManager] with [LegadoHttpClient] fetching the locator.
 *
 * `UnifiedSourceCatalogRepository` is deliberately NOT a dependency: its public API is
 * read/observe-only (verified against the source) — it has no install/side-load/rescan
 * methods — so the concrete execution seams above are used instead.
 */
@Singleton
class RecoveryActionCoordinator @Inject constructor(
    private val recoveryRepository: SourceRecoveryRepository,
    private val runtimeSnapshot: SourceRuntimeSnapshot,
    private val extensionRepoRepository: ExternalExtensionRepoRepository,
    private val installService: ExtensionInstallService,
    private val jsonSourceManager: JsonSourceManager,
    private val legadoHttpClient: LegadoHttpClient,
    private val mihonExtensionManager: MihonExtensionManager,
    private val aniyomiExtensionManager: AniyomiExtensionManager,
    private val ireaderExtensionManager: IReaderExtensionManager,
    private val tsundokuExtensionManager: TsundokuExtensionManager,
    private val notifier: RecoveryNotifier,
    @ApplicationContext private val appContext: Context,
) {

    private val actionMutex = Mutex()
    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())
    private val _lastActionResult = MutableStateFlow<RecoveryActionResult?>(null)
    private val _snapshot = MutableStateFlow(RecoveryUiState())

    /** UI projection of the current recovery domain (missing counts, per-source status). */
    val snapshot: StateFlow<RecoveryUiState> = _snapshot.asStateFlow()

    /**
     * Re-derives the whole state and refreshes [snapshot]. Safe to call any time (e.g. from
     * the ViewModel on startup so the badge is populated before the first action).
     */
    suspend fun refresh() {
        runCatching {
            publishStates(recoveryRepository.deriveAll())
        }
    }

    /**
     * Runs the automatic recovery action for [sourceKey]: derive → plan → execute → re-derive.
     * Returns the outcome; a successful resolution exits missing (T5.1) and notifies.
     */
    suspend fun run(sourceKey: String): RecoveryActionResult {
        if (sourceKey in _inFlight.value) {
            return failure(sourceKey, "action already in progress")
        }
        _inFlight.update { it + sourceKey }
        return try {
            val result = actionMutex.withLock { performRun(sourceKey) }
            _lastActionResult.value = result
            result
        } finally {
            _inFlight.update { it - sourceKey }
            runCatching { refresh() }
        }
    }

    /**
     * Side-loads the package behind [uri] for [sourceKey] (T5.4). A `null` uri (picker
     * dismissed) is a clean failure, never a crash.
     */
    suspend fun sideLoad(sourceKey: String, uri: Uri?): RecoveryActionResult {
        if (sourceKey in _inFlight.value) {
            return failure(sourceKey, "action already in progress")
        }
        _inFlight.update { it + sourceKey }
        return try {
            val result = actionMutex.withLock { performSideLoad(sourceKey, uri) }
            _lastActionResult.value = result
            result
        } finally {
            _inFlight.update { it - sourceKey }
            runCatching { refresh() }
        }
    }

    /**
     * Explicitly records the currently installed package's signing digest on the origin
     * (T5.6). After the write the origin re-derives; with digest now matching the installed
     * package it resolves.
     */
    suspend fun confirmSignature(sourceKey: String): RecoveryActionResult {
        if (sourceKey in _inFlight.value) {
            return failure(sourceKey, "action already in progress")
        }
        _inFlight.update { it + sourceKey }
        return try {
            val result = actionMutex.withLock { performConfirmSignature(sourceKey) }
            _lastActionResult.value = result
            result
        } finally {
            _inFlight.update { it - sourceKey }
            runCatching { refresh() }
        }
    }

    /**
     * Rejects the signature change without touching the recorded digest (T5.6). The origin
     * stays unassociated (`SIGNATURE_CONFIRMATION_REQUIRED`) while the installed package's
     * digest differs, and no automatic flow will re-associate it.
     */
    suspend fun rejectSignature(sourceKey: String): RecoveryActionResult {
        val result = if (recoverable(sourceKey) == null) {
            failure(sourceKey, "unknown origin")
        } else {
            failure(
                sourceKey,
                "signature change rejected; source stays unassociated until the " +
                    "installed package matches the recorded signature",
            )
        }
        _lastActionResult.value = result
        runCatching { refresh() }
        return result
    }

    /**
     * Re-scans every installed extension (T5.4) and returns how many previously-missing
     * origins resolved thanks to the scan. A summary notification reports the remaining
     * missing count.
     */
    suspend fun rescanAll(): Int {
        return actionMutex.withLock {
            val before = recoveryRepository.deriveAll().count { it.status.isMissing }
            reloadManagers()
            val states = recoveryRepository.deriveAll()
            val stillMissing = states.count { it.status.isMissing }
            val resolved = (before - stillMissing).coerceAtLeast(0)
            publishStates(states)
            if (resolved > 0) {
                runCatching { notifier.notifyMissingSummary(stillMissing) }
            }
            resolved
        }
    }

    /** Notifies that a single source was recovered (T5.3). */
    fun notifyRecovered(sourceKey: String) {
        notifier.notifyRecovered(sourceKey)
    }

    /** Notifies that [missingCount] sources still need recovery (T5.3). */
    fun notifyMissingSummary(missingCount: Int) {
        notifier.notifyMissingSummary(missingCount)
    }

    // -----------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------

    private suspend fun performRun(sourceKey: String): RecoveryActionResult {
        val state = recoverable(sourceKey) ?: return failure(sourceKey, "unknown origin")
        if (state.status == SourceRecoveryStatus.RESOLVED) {
            // T5.1: never auto-trigger a resolved origin.
            return success(sourceKey, "already resolved")
        }
        val plan = planRecoveryAction(state.status, state.origin)
        return when (plan) {
            is RecoveryActionPlan.InstallFromRepository ->
                installFromRepository(sourceKey, state.origin, plan)

            is RecoveryActionPlan.InstallSideload ->
                // Sideload requires a file picker decision; route through sideLoad().
                failure(sourceKey, "side-load required: pick the ${plan.kind} package")

            is RecoveryActionPlan.Reimport ->
                reimportSource(sourceKey, state.origin, plan.locator)

            is RecoveryActionPlan.ConfirmSignature ->
                performConfirmSignature(sourceKey)

            is RecoveryActionPlan.Rescan ->
                rescanOne(sourceKey)

            RecoveryActionPlan.NoActionMissing ->
                failure(sourceKey, "no recovery channel available")
        }
    }

    private suspend fun installFromRepository(
        sourceKey: String,
        origin: org.skepsun.kototoro.core.db.entity.SourceOriginEntity,
        plan: RecoveryActionPlan.InstallFromRepository,
    ): RecoveryActionResult {
        val type = origin.kind.toExternalExtensionTypeOrNull()
        if (type == null) {
            // JSON-backed / non-extension origins recover by re-importing the repository.
            return reimportSource(sourceKey, origin, plan.repositoryUrl)
        }
        val catalog = runCatching { extensionRepoRepository.getCatalogExtensions(type) }
            .getOrDefault(emptyList())
        val candidate = catalog
            .firstOrNull {
                sameRepositoryUrl(it.repoUrl, plan.repositoryUrl) &&
                    (origin.packageName == null || it.pkgName == origin.packageName)
            }
            ?: catalog.firstOrNull { origin.packageName == null || it.pkgName == origin.packageName }
        if (candidate == null) {
            return failure(sourceKey, "no install candidate in ${plan.repositoryUrl}")
        }
        return try {
            when (val result = installService.install(candidate, ExtensionInstallMode.LOCAL_APK)) {
                ExtensionInstallResult.Completed -> {
                    reloadManagers()
                    finishAfterAction(sourceKey, "installed ${candidate.pkgName}")
                }
                is ExtensionInstallResult.RequiresInstaller -> failure(
                    sourceKey,
                    "system installer required for ${candidate.pkgName}; use the package screen",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure(sourceKey, "install failed: ${e.message}")
        }
    }

    private suspend fun performSideLoad(sourceKey: String, uri: Uri?): RecoveryActionResult {
        if (uri == null) {
            return failure(sourceKey, "no file selected")
        }
        val state = recoverable(sourceKey) ?: return failure(sourceKey, "unknown origin")
        val origin = state.origin
        val ecosystem = origin.kind.toLocalApkEcosystem()
        if (ecosystem == null) {
            return failure(sourceKey, "kind ${origin.kind} cannot be side-loaded")
        }
        val tempFile = copyContentUriToCache(uri)
        if (tempFile == null) {
            return failure(sourceKey, "cannot read the selected file")
        }
        val packageName = origin.packageName
            ?.takeIf { it.isNotBlank() }
            ?: readArchivePackageName(tempFile)
        val stored = LocalApkExtensionSupport.storeManagedApk(
            context = appContext,
            ecosystem = ecosystem,
            packageName = packageName ?: tempFile.nameWithoutExtension,
            sourceFile = tempFile,
        )
        tempFile.delete()
        reloadManagers()
        return finishAfterAction(sourceKey, "side-loaded ${stored.name}")
    }

    private suspend fun performConfirmSignature(sourceKey: String): RecoveryActionResult {
        val state = recoverable(sourceKey) ?: return failure(sourceKey, "unknown origin")
        val origin = state.origin
        val currentDigest = runtimeSnapshot.currentSigningDigest(sourceKey)
        if (currentDigest.isNullOrBlank()) {
            return failure(sourceKey, "cannot read the current package signature")
        }
        recoveryRepository.upsert(
            origin.copy(
                signingDigest = currentDigest,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return finishAfterAction(sourceKey, "signature confirmed")
    }

    private suspend fun rescanOne(sourceKey: String): RecoveryActionResult {
        reloadManagers()
        return finishAfterAction(sourceKey, "rescanned")
    }

    private suspend fun reimportSource(
        sourceKey: String,
        origin: org.skepsun.kototoro.core.db.entity.SourceOriginEntity,
        locator: String,
    ): RecoveryActionResult {
        if (locator.isBlank()) {
            return failure(sourceKey, "empty locator")
        }
        val content = runCatching { fetchRemoteText(locator) }
            .getOrElse { error -> return failure(sourceKey, "fetch failed: ${error.message}") }
        if (content.isBlank()) {
            return failure(sourceKey, "empty repository content")
        }
        val result = when (origin.kind) {
            "LEGADO" -> jsonSourceManager.importLegadoJson(
                jsonContent = content,
                sourceLocator = locator,
                sourceTitle = origin.displayName,
            )
            "TVBOX" -> jsonSourceManager.importTvBoxJson(
                jsonContent = content,
                sourceLocator = locator,
                sourceTitle = origin.displayName,
            )
            "JS" -> jsonSourceManager.importJsSource(content, enabled = null)
            "LNREADER" -> jsonSourceManager.importLNReaderPlugin(content, enabled = null)
            else -> return failure(sourceKey, "kind ${origin.kind} cannot be re-imported")
        }
        return result.fold(
            onSuccess = { finishAfterAction(sourceKey, "re-imported") },
            onFailure = { error -> failure(sourceKey, "re-import failed: ${error.message}") },
        )
    }

    private suspend fun finishAfterAction(
        sourceKey: String,
        doneMessage: String,
    ): RecoveryActionResult {
        val after = recoveryRepository.statusOf(sourceKey)
        return when {
            after == null -> failure(sourceKey, "origin disappeared")
            after == SourceRecoveryStatus.RESOLVED -> {
                // T5.1: resolved -> automatically exits missing.
                notifyRecovered(sourceKey)
                success(sourceKey, doneMessage)
            }
            else -> failure(sourceKey, "$doneMessage; still ${after.name.lowercase()}")
        }
    }

    private suspend fun reloadManagers() {
        runCatching { mihonExtensionManager.loadExtensions() }
        runCatching { aniyomiExtensionManager.loadExtensions() }
        runCatching { ireaderExtensionManager.loadExtensions() }
        runCatching { tsundokuExtensionManager.loadExtensions() }
    }

    private suspend fun fetchRemoteText(url: String): String {
        val response = legadoHttpClient.get(url)
        return try {
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        } finally {
            response.close()
        }
    }

    private fun copyContentUriToCache(uri: Uri): File? = runCatching {
        val tempDir = File(appContext.cacheDir, "recovery-installs").apply { mkdirs() }
        val tempFile = File(tempDir, "sideload_${System.currentTimeMillis()}.apk")
        val input = appContext.contentResolver.openInputStream(uri)
            ?: return@runCatching null
        input.use { stream ->
            tempFile.outputStream().use { output -> stream.copyTo(output) }
        }
        tempFile
    }.getOrNull()

    private fun readArchivePackageName(apkFile: File): String? = runCatching {
        ExternalExtensionLoaderSupport.getPackageArchiveInfoOrNull(appContext.packageManager, apkFile)
            ?.packageName
    }.getOrNull()

    private suspend fun recoverable(
        sourceKey: String,
    ): SourceRecoveryState? {
        return recoveryRepository.deriveAll().firstOrNull { it.origin.sourceKey == sourceKey }
    }

    private fun publishStates(states: List<SourceRecoveryState>) {
        _snapshot.value = RecoveryUiState(
            missingCount = states.count { it.status.isMissing },
            total = states.size,
            perSource = states.associate { it.origin.sourceKey to it.status },
            inFlightSourceKeys = _inFlight.value,
            actionResult = _lastActionResult.value,
        )
    }

    private fun success(sourceKey: String, message: String? = null): RecoveryActionResult {
        return RecoveryActionResult(sourceKey = sourceKey, ok = true, message = message)
    }

    private fun failure(sourceKey: String, message: String?): RecoveryActionResult {
        return RecoveryActionResult(sourceKey = sourceKey, ok = false, message = message)
    }

    private fun String?.toExternalExtensionTypeOrNull(): ExternalExtensionType? {
        return this?.let { kind -> ExternalExtensionType.entries.firstOrNull { it.name == kind } }
    }

    private fun String.toLocalApkEcosystem(): String? {
        return when (this) {
            "MIHON" -> "mihon"
            "ANIYOMI" -> "aniyomi"
            "IREADER" -> "ireader"
            "TSUNDOKU" -> "tsundoku"
            else -> null
        }
    }

    private fun sameRepositoryUrl(left: String, right: String): Boolean {
        return normalizeRepositoryUrlForRecovery(left) == normalizeRepositoryUrlForRecovery(right)
    }

    private fun normalizeRepositoryUrlForRecovery(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return trimmed
            .removeSuffix("/index.pb")
            .removeSuffix("/index.min.json")
            .removeSuffix("/plugins.json")
            .removeSuffix("/repo.json")
            .removeSuffix("/repo")
            .trimEnd('/')
            .lowercase()
    }
}
