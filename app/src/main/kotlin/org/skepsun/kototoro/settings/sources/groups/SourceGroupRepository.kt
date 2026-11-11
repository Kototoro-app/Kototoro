package org.skepsun.kototoro.settings.sources.groups

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.tsundoku.TsundokuExtensionManager
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.SourceOriginsDao
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.SourceSettings
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.extensions.install.ExtensionInstallMode
import org.skepsun.kototoro.extensions.install.ExtensionInstallResult
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository.AddRepoResult
import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository.PrepareAddRepoResult
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionLoaderSupport
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.settings.sources.lock.SourceLockStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

class SourceGroupFormatException(message: String) : IllegalArgumentException(message)

/** The group file declares a schema newer than [SOURCE_GROUP_SCHEMA_VERSION]; the app must be updated. */
class SourceGroupNewerSchemaException : IllegalArgumentException("Source group schema is newer than supported")

class SourceGroupTooLargeException : IOException("Source group file is too large")

data class ApplyProgress(
	val running: Boolean = false,
	val finished: Boolean = false,
	val step: Int = 0,
	val total: Int = 0,
	val groupName: String? = null,
	val errors: List<String> = emptyList(),
	/** Normal-APK packages that must be installed by hand through the system installer. */
	val manualInstall: List<String> = emptyList(),
)

/** What applying a group would add to this device; shown for confirmation before [SourceGroupRepository.apply]. */
data class SourceGroupApplyPreview(
	val groupName: String,
	/** Repository URLs of the group that are not registered yet. */
	val reposToAdd: List<String> = emptyList(),
	/** Package names that apply would download and install by itself. */
	val packagesToInstall: List<String> = emptyList(),
	/** Normal-APK packages the user has to install by hand. */
	val manualPackages: List<String> = emptyList(),
	/** Number of sources whose manual domain the group would overwrite. */
	val domainOverrideCount: Int = 0,
	/** First few overrides as "source → domain". */
	val domainOverrideSample: List<String> = emptyList(),
) {
	val needsConfirmation: Boolean
		get() = reposToAdd.isNotEmpty() || packagesToInstall.isNotEmpty() || domainOverrideCount > 0
}

/** [updated] is true when an imported group replaced a stored group with the same id. */
data class SourceGroupImportResult(
	val group: SourceGroup,
	val updated: Boolean,
)

@Singleton
class SourceGroupRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val sourceOriginsDao: SourceOriginsDao,
	private val repoRepository: ExternalExtensionRepoRepository,
	private val installService: ExtensionInstallService,
	private val contentSources: ContentSourcesRepository,
	private val repositoryFactory: ContentRepository.Factory,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager,
	private val aniyomiExtensionManager: AniyomiExtensionManager,
	private val ireaderExtensionManager: IReaderExtensionManager,
	private val tsundokuExtensionManager: TsundokuExtensionManager,
) {

	private val groupsDir: File
		get() = SourceGroupsDirectory.resolve(context.filesDir)

	private val applyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val applying = AtomicBoolean(false)

	private val _progress = MutableStateFlow(ApplyProgress())
	val progress: StateFlow<ApplyProgress> = _progress.asStateFlow()

	suspend fun list(): List<SourceGroup> = withContext(Dispatchers.IO) {
		groupsDir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
			.filter { it.length() in 1..SOURCE_GROUP_MAX_BYTES }
			.mapNotNull { file ->
				runCatching { SourceGroupJson.decode(file.readText()) }
					.getOrNull()
					?.takeIf { it.isValid() && file.nameWithoutExtension == it.id }
			}
			.sortedByDescending { it.createdAt }
	}

	suspend fun saveCurrentAsGroup(name: String): SourceGroup {
		val trimmed = name.trim()
		require(trimmed.isNotEmpty()) { "Group name must not be blank" }
		val group = withContext(Dispatchers.IO) {
			// Installed sources that never got a row (fresh install) must be part of the saved states.
			contentSources.syncSourceRows()
			SourceGroup(
				id = UUID.randomUUID().toString(),
				name = trimmed,
				createdAt = System.currentTimeMillis(),
				repos = database.getExternalExtensionRepoDao().getAll().map {
					SourceGroupRepo(type = it.type.name, url = it.baseUrl, name = it.shortName ?: it.name)
				},
				packages = collectInstalledPackages(),
				sourceStates = database.getSourcesDao().findAll().mapNotNull {
					val lock = contentSources.lockState(it.source)
					val domainOverride = currentDomainOverride(it.source)
					if (it.isEnabled || lock != null || it.isPinned || domainOverride != null) {
						SourceGroupSourceState(
							source = it.source,
							enabled = it.isEnabled,
							pinned = it.isPinned,
							sortKey = it.sortKey,
							locked = lock,
							domain = domainOverride,
						)
					} else null
				} + database.getJsonSourceDao().findAll().mapNotNull {
					// JSON sources live in their own table; their lock is keyed by the entity id.
					val lock = contentSources.lockState(it.id)
					val domainOverride = currentDomainOverride(it.id)
					if (it.enabled || lock != null || it.isPinned || domainOverride != null) {
						SourceGroupSourceState(
							source = it.id,
							enabled = it.enabled,
							pinned = it.isPinned,
							sortKey = 0,
							locked = lock,
							domain = domainOverride,
						)
					} else null
				},
				jarPriority = settings.jarPriorityOrder.split(',').map { it.trim() }.filter { it.isNotEmpty() },
			)
		}
		withContext(Dispatchers.IO) { writeGroup(group) }
		return group
	}

	suspend fun createCleanGroup(name: String): SourceGroup {
		val trimmed = name.trim()
		require(trimmed.isNotEmpty()) { "Group name must not be blank" }
		val group = withContext(Dispatchers.IO) {
			SourceGroup(
				id = UUID.randomUUID().toString(),
				name = trimmed,
				createdAt = System.currentTimeMillis(),
				repos = database.getExternalExtensionRepoDao().getAll().map {
					SourceGroupRepo(type = it.type.name, url = it.baseUrl, name = it.shortName ?: it.name)
				},
				packages = emptyList(),
				sourceStates = emptyList(),
				jarPriority = emptyList(),
			)
		}
		withContext(Dispatchers.IO) { writeGroup(group) }
		return group
	}

	suspend fun delete(id: String) = withContext(Dispatchers.IO) {
		groupFile(id).delete()
	}

	suspend fun exportToUri(id: String, uri: Uri) = withContext(Dispatchers.IO) {
		val file = groupFile(id)
		require(file.isFile) { "Source group not found" }
		val output = context.contentResolver.openOutputStream(uri, "wt")
			?: throw IOException("Cannot open output stream")
		output.use { it.write(file.readBytes()) }
	}

	suspend fun importFromUri(uri: Uri): SourceGroupImportResult = withContext(Dispatchers.IO) {
		val input = context.contentResolver.openInputStream(uri)
			?: throw IOException("Cannot open input stream")
		val bytes = input.use { readLimited(it, SOURCE_GROUP_MAX_BYTES) }
		val text = bytes.toString(Charsets.UTF_8)
		val decoded = runCatching { SourceGroupJson.decode(text) }
			.getOrElse { error ->
				// A newer file may carry a body this version cannot decode; its schema still tells it apart.
				if ((SourceGroupJson.peekSchema(text) ?: 0) > SOURCE_GROUP_SCHEMA_VERSION) {
					throw SourceGroupNewerSchemaException()
				}
				throw SourceGroupFormatException(error.message ?: "Invalid JSON")
			}
		if (decoded.isNewerSchema()) {
			throw SourceGroupNewerSchemaException()
		}
		if (!decoded.isValid()) {
			throw SourceGroupFormatException("Invalid schema or id")
		}
		// Importing is idempotent: the same id replaces the stored group instead of adding a copy.
		val updated = groupFile(decoded.id).exists()
		writeGroup(decoded)
		SourceGroupImportResult(group = decoded, updated = updated)
	}

	/** Lists the repositories and packages [apply] would add, without changing anything. */
	suspend fun previewApply(id: String): SourceGroupApplyPreview = withContext(Dispatchers.IO) {
		val group = list().firstOrNull { it.id == id }
			?: throw IllegalArgumentException("Source group not found")
		val dao = database.getExternalExtensionRepoDao()
		val reposToAdd = group.repos.filter { repo ->
			val type = repo.type.toExternalTypeOrNull() ?: return@filter false
			dao.get(type, repo.url) == null
		}.map { it.url }.distinct()
		val (manual, auto) = group.packages.filterNot { isPackageInstalled(it) }
			.partition { it.installMode == SOURCE_GROUP_INSTALL_MODE_SYSTEM }
		// Preview runs before packages are installed, so only the shape is checked here; apply also needs a resolvable source.
		val allNames = group.sourceStates.mapTo(HashSet()) { it.source }
		val domainSources = group.sourceStates
			.filter { !it.domain.isNullOrBlank() && isValidGroupDomain(it.domain) && isValidGroupSourceName(it.source, allNames) }
		SourceGroupApplyPreview(
			domainOverrideCount = domainSources.size,
			domainOverrideSample = domainSources.take(3).map { "${it.source} → ${it.domain}" },
			groupName = group.name,
			reposToAdd = reposToAdd,
			packagesToInstall = auto.map { it.pkg }.distinct(),
			manualPackages = manual.map { it.pkg }.distinct(),
		)
	}

	/**
	 * Applies the group to this device. Every step is isolated: a failing step is recorded in
	 * the returned list and the remaining steps still run. Progress is published to [progress].
	 */
	suspend fun apply(id: String): List<String> {
		check(applying.compareAndSet(false, true)) { "Another source group is being applied" }
		// Runs in an application-level scope: closing the dialog (cancelling the caller) must not stop it half-way.
		val job = applyScope.async {
			try {
				applyGroup(id)
			} finally {
				applying.set(false)
			}
		}
		return job.await()
	}

	private suspend fun applyGroup(id: String): List<String> {
		val group = list().firstOrNull { it.id == id }
			?: throw IllegalArgumentException("Source group not found")
		val notInstalled = group.packages.filterNot { isPackageInstalled(it) }
		// Normal APK installs need Android's own confirmation per package, so they are listed, not installed.
		val (manualPackages, autoPackages) = notInstalled.partition { it.installMode == SOURCE_GROUP_INSTALL_MODE_SYSTEM }
		// JARs first: they install without the system installer. MIHON packages next: dependent
		// readers (e.g. Nyora-style forks sharing the Mihon repo format) only recognize their
		// ecosystem once the base Mihon packages are in place. Everything else keeps file order.
		val missing = autoPackages.sortedBy {
			when {
				it.kind.equals("jar", ignoreCase = true) -> 0
				it.kind.equals("mihon", ignoreCase = true) -> 1
				else -> 2
			}
		}
		val total = group.repos.size + missing.size + group.sourceStates.size + 1
		val errors = mutableListOf<String>()
		var step = 0
		fun tick(publish: Boolean = true) {
			step++
			if (publish) _progress.update { it.copy(step = step, errors = errors.toList()) }
		}
		_progress.value = ApplyProgress(
			running = true,
			total = total,
			groupName = group.name,
			manualInstall = manualPackages.map { it.pkg },
		)
		var snapshot: GroupStateSnapshot? = null
		var stateFailed = false
		suspend fun stateStep(label: String, block: suspend () -> Unit) {
			if (!runGuarded(errors, label, block)) stateFailed = true
		}
		try {
			snapshot = takeSnapshot(group)
			val failedRepoUrls = mutableSetOf<String>()
			for (repo in group.repos) {
				val ok = runGuarded(errors, context.getString(R.string.source_groups_error_repo, repo.url)) {
					applyRepo(repo)
				}
				if (!ok) failedRepoUrls += repo.url.trimEnd('/')
				tick()
			}

			val catalogCache = HashMap<ExternalExtensionType, List<RepoAvailableExtension>>()
			val installedTypes = LinkedHashSet<ExternalExtensionType>()
			for (pkg in missing) {
				// A package that comes from a repository which just failed cannot be fetched either:
				// skip it so one broken repository does not add a second, chained error.
				val pkgRepoUrl = pkg.repoUrl?.trimEnd('/')
				if (pkgRepoUrl != null && pkgRepoUrl in failedRepoUrls) {
					tick()
					continue
				}
				val installed = runGuarded(errors, context.getString(R.string.source_groups_error_package, pkg.pkg)) {
					applyPackage(pkg, catalogCache)
				}
				if (installed) pkg.kind.toExternalTypeOrNull()?.let { installedTypes += it }
				tick()
			}
			// APK-based packages only show up after their manager rescans: once per kind, not per package.
			runGuarded(errors, context.getString(R.string.source_groups_error_apply_state)) {
				reloadManagers(installedTypes)
			}

			database.withTransaction {
				group.sourceStates.forEach { state ->
					stateStep(context.getString(R.string.source_groups_error_source, state.source)) {
						ensureSourceRow(state)
					}
				}
			}

			// The group's lock state replaces the device lock of every source it lists (null clears it).
			// Locks an earlier group apply wrote for sources this group does not list are released; locks
			// the user set by hand (not in the applied set) stay. Locks are written before the exclusive
			// batch, which keeps locked sources at their lock value.
			stateStep(context.getString(R.string.source_groups_error_apply_state)) {
				val lockStore = SourceLockStore.get(context)
				val groupLocks = group.sourceStates.associate { contentSources.resolveSource(it.source) to it.locked }
				val lockPlan = groupLockPlan(
					groupLocks = groupLocks.entries.associate { it.key.name to it.value },
					previouslyApplied = lockStore.groupAppliedNames(),
				)
				contentSources.setSourceLocks(lockPlan.mapKeys { contentSources.resolveSource(it.key) })
				lockStore.setGroupApplied(groupLocks.filterValues { it != null }.keys.mapTo(HashSet()) { it.name })
			}

			// Exclusive batch over the unfiltered db view: getEnabledSources() hides NSFW-disabled,
			// broken and tvbox-invisible sources, so those would never be switched off. The exclusive
			// helper walks every row, keeps locked sources at their lock value and clears the
			// all-sources-enabled flag it just made irrelevant.
			val groupEnabled = group.sourceStates.filter { it.enabled }
				.mapTo(mutableSetOf()) { contentSources.resolveSource(it.source) }
			stateStep(context.getString(R.string.source_groups_error_apply_state)) {
				contentSources.setSourcesEnabledExclusive(groupEnabled)
			}

			val knownNames = knownGroupSourceNames(group)
			var skippedDomains = 0
			database.withTransaction {
				group.sourceStates.forEachIndexed { index, state ->
					stateStep(context.getString(R.string.source_groups_error_source, state.source)) {
						if (!applySourceDetails(state, knownNames)) skippedDomains++
					}
					tick(publish = (index + 1) % PROGRESS_BATCH == 0 || index == group.sourceStates.lastIndex)
				}
			}
			if (skippedDomains > 0) errors += context.getString(R.string.source_groups_domain_skipped, skippedDomains)

			// Applying is a sync: per-source state the group does not mention is reset, never kept.
			stateStep(context.getString(R.string.source_groups_error_apply_state)) {
				syncOutsideGroup(group, snapshot)
			}

			stateStep(context.getString(R.string.source_groups_error_jar_priority)) {
				val installed = installedJarNames()
				// A group without a JAR order leaves the user's own order alone.
				groupJarPriorityToApply(group.jarPriority, installed)?.let { settings.jarPriorityOrder = it }
			}
			tick()
			if (stateFailed) rollback(snapshot, errors)
		} catch (e: CancellationException) {
			withContext(NonCancellable) { rollback(snapshot, errors) }
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Source group apply failed", e)
			errors += "${e.localizedMessage ?: e.javaClass.simpleName}"
			rollback(snapshot, errors)
		} finally {
			_progress.update {
				it.copy(running = false, finished = true, step = total, errors = errors.toList())
			}
		}
		return errors.toList()
	}

	private suspend fun applyRepo(repo: SourceGroupRepo) {
		val type = repo.type.toExternalTypeOrNull()
			?: throw IllegalArgumentException("Unknown repo type ${repo.type}")
		if (database.getExternalExtensionRepoDao().get(type, repo.url) != null) return
		// A repository that is already registered — under the same url or under another url with
		// the same signing key — means the group's content is present: skip it instead of failing.
		when (val prepared = repoRepository.prepareAddRepo(type, repo.url)) {
			is PrepareAddRepoResult.Ready -> when (val added = repoRepository.confirmAddRepo(prepared.repo)) {
				is AddRepoResult.FetchFailed -> throw added.error
				AddRepoResult.InvalidUrl -> throw IllegalArgumentException(repo.url)
				else -> Unit
			}
			is PrepareAddRepoResult.RepoAlreadyExists -> Unit
			is PrepareAddRepoResult.DuplicateFingerprint -> Unit
			is PrepareAddRepoResult.FetchFailed -> throw prepared.error
			PrepareAddRepoResult.InvalidUrl -> throw IllegalArgumentException(repo.url)
		}
	}

	private suspend fun reloadManagers(types: Set<ExternalExtensionType>) {
		if (ExternalExtensionType.MIHON in types) mihonExtensionManager.loadExtensions()
		if (ExternalExtensionType.ANIYOMI in types) aniyomiExtensionManager.loadExtensions()
		if (ExternalExtensionType.IREADER in types) ireaderExtensionManager.loadExtensions()
		if (ExternalExtensionType.TSUNDOKU in types) tsundokuExtensionManager.loadExtensions()
	}

	private suspend fun applyPackage(
		pkg: SourceGroupPackage,
		catalogCache: MutableMap<ExternalExtensionType, List<RepoAvailableExtension>>,
	) {
		val type = pkg.kind.toExternalTypeOrNull()
			?: throw IllegalArgumentException("Unknown package kind ${pkg.kind}")
		val catalog = catalogCache.getOrPut(type) { repoRepository.getAvailableExtensions(type) }
		val candidates = catalog.filter { it.pkgName == pkg.pkg }
		val extension = candidates.firstOrNull { it.repoUrl == pkg.repoUrl } ?: candidates.firstOrNull()
			?: throw IllegalStateException(context.getString(R.string.source_groups_package_missing, pkg.pkg))
		val result = installService.install(extension, ExtensionInstallMode.LOCAL_APK)
		if (result is ExtensionInstallResult.RequiresInstaller) {
			throw IllegalStateException(context.getString(R.string.source_groups_requires_installer, pkg.pkg))
		}
	}

	/** A restored source may have no row yet (new device, extension just installed); create it disabled. */
	private suspend fun ensureSourceRow(state: SourceGroupSourceState) {
		if (state.source.startsWith("JSON_")) return
		val dao = database.getSourcesDao()
		if (dao.find(state.source) != null) return
		dao.insertIfAbsent(
			listOf(
				MangaSourceEntity(
					source = state.source,
					isEnabled = false,
					sortKey = state.sortKey,
					addedIn = BuildConfig.VERSION_CODE,
					lastUsedAt = 0L,
					isPinned = false,
					cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
				),
			),
		)
	}

	/** Names of the group's sources that this device can actually resolve (the only ones a domain may be written for). */
	private fun knownGroupSourceNames(group: SourceGroup): Set<String> =
		group.sourceStates.mapNotNullTo(HashSet()) { s -> s.source.takeIf { contentSources.isSourceAvailable(it) } }

	private suspend fun applySourceDetails(state: SourceGroupSourceState, knownNames: Set<String>): Boolean {
		val source = contentSources.resolveSource(state.source)
		contentSources.setIsPinned(listOf(source), state.pinned)
		if (state.domain.isNullOrBlank()) return true
		if (!isValidGroupSourceName(state.source, setOf(state.source)) || !isValidGroupDomain(state.domain)) {
			throw IllegalArgumentException("Invalid source name or domain")
		}
		if (state.source !in knownNames) return false
		sourcePrefs(state.source).edit()
			.putString(SourceSettings.KEY_DOMAIN, state.domain)
			.apply()
		// Rebuild the parser so the restored domain takes effect without an app restart.
		repositoryFactory.invalidate(source)
		return true
	}

	/**
	 * Resets the per-source state of everything the group does not mention: pins an earlier group apply wrote are
	 * removed (hand-set pins and manual domain overrides are user data and kept), and the manual order becomes the group order followed by the remaining rows.
	 */
	private suspend fun syncOutsideGroup(group: SourceGroup, snapshot: GroupStateSnapshot?) {
		val inGroup = group.sourceStates.mapTo(HashSet()) { it.source }
		val rows = database.getSourcesDao().findAll()
		val jsonRows = database.getJsonSourceDao().findAll()

		val currentlyPinned = rows.filter { it.isPinned }.mapTo(HashSet()) { it.source } +
			jsonRows.filter { it.isPinned }.map { it.id }
		val previousPins = pinStore().all.keys.toSet()
		val pinned = groupPinsToRemove(previousPins, inGroup, currentlyPinned)
		if (pinned.isNotEmpty()) {
			contentSources.setIsPinned(pinned.map(contentSources::resolveSource), false)
		}
		val groupPinned = group.sourceStates.filter { it.pinned }.mapTo(HashSet()) { it.source }
		val appliedPins = groupAppliedPinsToStore(groupPinned, previousPins, snapshot?.pinnedNames.orEmpty())
		storePins(appliedPins)

		contentSources.applySourceOrder(
			group.sourceStates
				.filterNot { it.source.startsWith("JSON_") }
				.sortedBy { it.sortKey }
				.map { it.source },
		)
	}

	/** Lower-cased base names of the JAR plugins that exist on disk. */
	private fun installedJarNames(): Set<String> =
		File(context.filesDir, "plugins")
			.listFiles { f -> f.isFile && f.extension.equals("jar", ignoreCase = true) }
			.orEmpty()
			.mapTo(HashSet()) { it.nameWithoutExtension.lowercase() }

	/** The per-source settings file, named exactly like [org.skepsun.kototoro.core.prefs.SourceSettings] names it. */
	private fun sourcePrefs(sourceKey: String): android.content.SharedPreferences =
		context.getSharedPreferences(sourceKey.replace(File.separatorChar, '$'), Context.MODE_PRIVATE)

	/** The manual domain override currently saved for the source, or null when none is set. */
	private fun currentDomainOverride(sourceKey: String): String? {
		// Opening a missing prefs file would create it: only sources that ever saved a setting have one.
		val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
		val file = File(prefsDir, sourceKey.replace(File.separatorChar, '$') + ".xml")
		if (!file.isFile) return null
		return sourcePrefs(sourceKey).getString(SourceSettings.KEY_DOMAIN, null)
			?.trim()
			?.takeIf { it.isNotEmpty() }
	}

	private fun storePins(names: Set<String>) {
		val editor = pinStore().edit()
		editor.clear()
		names.forEach { editor.putBoolean(it, true) }
		editor.commit()
	}

	private fun pinStore(): android.content.SharedPreferences =
		context.getSharedPreferences(GROUP_APPLIED_PINS_PREFS, Context.MODE_PRIVATE)

	/** Everything a group apply writes, captured before the first write so a failed apply can be undone. */
	private class GroupStateSnapshot(
		val sources: List<MangaSourceEntity>,
		val jsonSources: List<JsonSourceEntity>,
		val locks: Map<String, Boolean>,
		val lockApplied: Set<String>,
		val pinApplied: Set<String>,
		val domains: Map<String, String?>,
		val jarPriority: String,
		val allSourcesEnabled: Boolean,
	) {
		val pinnedNames: Set<String> =
			sources.filter { it.isPinned }.mapTo(HashSet()) { it.source } +
				jsonSources.filter { it.isPinned }.map { it.id }
	}

	private suspend fun takeSnapshot(group: SourceGroup): GroupStateSnapshot {
		val lockStore = SourceLockStore.get(context)
		return GroupStateSnapshot(
			sources = database.getSourcesDao().findAll(),
			jsonSources = database.getJsonSourceDao().findAll(),
			locks = lockStore.locks.value,
			lockApplied = lockStore.groupAppliedNames(),
			pinApplied = pinStore().all.keys.toSet(),
			domains = group.sourceStates.map { it.source }.distinct().associateWith { currentDomainOverride(it) },
			jarPriority = settings.jarPriorityOrder,
			allSourcesEnabled = settings.isAllSourcesEnabled,
		)
	}

	private suspend fun rollback(snapshot: GroupStateSnapshot?, errors: MutableList<String>) {
		if (snapshot == null) return
		val failures = mutableListOf<String>()
		runGuarded(failures, "rollback sources") {
			val dao = database.getSourcesDao()
			database.withTransaction {
				dao.deleteAll()
				dao.insertIfAbsent(snapshot.sources)
				val jsonDao = database.getJsonSourceDao()
				snapshot.jsonSources.forEach { jsonDao.update(it) }
			}
		}
		runGuarded(failures, "rollback locks") {
			val lockStore = SourceLockStore.get(context)
			val restore = HashMap<String, Boolean?>()
			lockStore.locks.value.keys.forEach { restore[it] = null }
			restore.putAll(snapshot.locks)
			lockStore.setAll(restore)
			lockStore.setGroupApplied(snapshot.lockApplied)
		}
		runGuarded(failures, "rollback pins") {
			storePins(snapshot.pinApplied)
		}
		for ((key, domain) in snapshot.domains) {
			runGuarded(failures, "rollback domain $key") {
				if (currentDomainOverride(key) == domain) return@runGuarded
				val editor = sourcePrefs(key).edit()
				if (domain == null) editor.remove(SourceSettings.KEY_DOMAIN) else editor.putString(SourceSettings.KEY_DOMAIN, domain)
				editor.commit()
				repositoryFactory.invalidate(contentSources.resolveSource(key))
			}
		}
		runGuarded(failures, "rollback jar priority") {
			settings.jarPriorityOrder = snapshot.jarPriority
		}
		runGuarded(failures, "rollback all-sources flag") {
			settings.isAllSourcesEnabled = snapshot.allSourcesEnabled
		}
		errors += context.getString(R.string.source_groups_rolled_back)
		errors += failures
		_progress.update { it.copy(errors = errors.toList()) }
	}

	/** Runs [block]; a failure is recorded in [errors] and reported as false so dependent steps can be skipped. */
	private suspend fun runGuarded(errors: MutableList<String>, label: String, block: suspend () -> Unit): Boolean {
		return try {
			block()
			true
		} catch (e: CancellationException) {
			// Timeouts and installer aborts are CancellationExceptions too: only a cancelled apply propagates.
			currentCoroutineContext().ensureActive()
			Log.w(TAG, "Source group step failed: $label", e)
			errors += "$label: ${e.localizedMessage ?: e.javaClass.simpleName}"
			false
		} catch (e: Exception) {
			Log.w(TAG, "Source group step failed: $label", e)
			errors += "$label: ${e.localizedMessage ?: e.javaClass.simpleName}"
			false
		}
	}

	private suspend fun collectInstalledPackages(): List<SourceGroupPackage> {
		val result = mutableListOf<SourceGroupPackage>()
		val repoByPackage = sourceOriginsDao.findAll()
			.mapNotNull { origin ->
				val pkg = origin.packageName
				val repo = origin.repositoryUrl
				if (pkg != null && repo != null) pkg to repo else null
			}
			.toMap()

		val jarPrefs = context.getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE)
		File(context.filesDir, "plugins")
			.listFiles { f -> f.isFile && f.extension.equals("jar", ignoreCase = true) }
			.orEmpty()
			.forEach { file ->
				val pkg = file.nameWithoutExtension
				result += SourceGroupPackage(
					pkg = pkg,
					kind = ExternalExtensionType.JAR.name.lowercase(),
					versionCode = jarPrefs.getLong(pkg, 0L),
					repoUrl = jarPrefs.getString("$pkg:repo", null),
				)
			}

		val csPrefs = context.getSharedPreferences("cloudstream_plugin_versions", Context.MODE_PRIVATE)
		File(File(context.filesDir, "cloudstream"), "plugins")
			.listFiles { f -> f.isFile && (f.extension.equals("cs3", true) || f.extension.equals("zip", true)) }
			.orEmpty()
			.forEach { file ->
				val pkg = csPrefs.all.entries
					.firstOrNull { it.key.endsWith(":archive") && it.value == file.name }
					?.key?.substringBefore(":archive")
					?: file.nameWithoutExtension
				result += SourceGroupPackage(
					pkg = pkg,
					kind = ExternalExtensionType.CLOUDSTREAM.name.lowercase(),
					versionCode = csPrefs.getLong(pkg, 0L),
					repoUrl = csPrefs.getString("$pkg:repo", null),
				)
			}

		for (ecosystem in LOCAL_APK_ECOSYSTEMS) {
			LocalApkExtensionSupport.findLocalApkFiles(context, ecosystem).forEach { file ->
				val info = ExternalExtensionLoaderSupport.getPackageArchiveInfoOrNull(context.packageManager, file)
					?: return@forEach
				val pkg: String = info.packageName ?: return@forEach
				result += SourceGroupPackage(
					pkg = pkg,
					kind = ecosystem,
					versionName = info.versionName,
					versionCode = PackageInfoCompat.getLongVersionCode(info),
					repoUrl = repoByPackage[pkg],
				)
			}
		}

		// Normal (system-installed) APK extensions are recorded too so an import can list them.
		for (info in ExternalExtensionLoaderSupport.getInstalledPackages(context.packageManager)) {
			val kind = classifySourceGroupApk(info) ?: continue
			val pkg: String = info.packageName ?: continue
			result += SourceGroupPackage(
				pkg = pkg,
				kind = kind,
				versionName = info.versionName,
				versionCode = PackageInfoCompat.getLongVersionCode(info),
				repoUrl = repoByPackage[pkg],
				installMode = SOURCE_GROUP_INSTALL_MODE_SYSTEM,
			)
		}
		return result.distinctBy { Triple(it.kind, it.pkg, it.installMode) }
	}

	private fun isPackageInstalled(pkg: SourceGroupPackage): Boolean {
		val version = pkg.versionCode
		val kind = pkg.kind.lowercase()
		return when (kind) {
			"jar" -> {
				val file = File(File(context.filesDir, "plugins"), "${pkg.pkg}.jar")
				val prefs = context.getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE)
				file.isFile && file.length() > 0 && prefs.getLong(pkg.pkg, -1L) >= version
			}
			"cloudstream" -> {
				val prefs = context.getSharedPreferences("cloudstream_plugin_versions", Context.MODE_PRIVATE)
				val archive = prefs.getString("${pkg.pkg}:archive", null) ?: "${pkg.pkg}.cs3"
				val file = File(File(File(context.filesDir, "cloudstream"), "plugins"), archive)
				file.isFile && file.length() > 0 && prefs.getLong(pkg.pkg, -1L) >= version
			}
			in LOCAL_APK_ECOSYSTEMS -> {
				val systemInfo = ExternalExtensionLoaderSupport.getPackageInfoOrNull(context.packageManager, pkg.pkg)
				if (systemInfo != null && PackageInfoCompat.getLongVersionCode(systemInfo) >= version) {
					return true
				}
				val managed = File(
					File(LocalApkExtensionSupport.getManagedExtensionsDir(context, kind), pkg.pkg),
					"${pkg.pkg}.apk",
				)
				val info = if (managed.isFile && managed.length() > 0) {
					ExternalExtensionLoaderSupport.getPackageArchiveInfoOrNull(context.packageManager, managed)
				} else {
					null
				}
				info != null && PackageInfoCompat.getLongVersionCode(info) >= version
			}
			else -> false
		}
	}

	private fun writeGroup(group: SourceGroup) {
		groupsDir.mkdirs()
		val target = groupFile(group.id)
		val temp = File(groupsDir, "${group.id}.json.tmp")
		temp.writeText(SourceGroupJson.encode(group))
		try {
			Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
		}
	}

	private fun groupFile(id: String): File {
		require(SOURCE_GROUP_ID_REGEX.matches(id)) { "Invalid source group id" }
		return File(groupsDir, "$id.json")
	}

	private fun readLimited(input: InputStream, limit: Long): ByteArray {
		val out = ByteArrayOutputStream()
		val buffer = ByteArray(8 * 1024)
		var total = 0L
		while (true) {
			val read = input.read(buffer)
			if (read < 0) break
			total += read
			if (total > limit) throw SourceGroupTooLargeException()
			out.write(buffer, 0, read)
		}
		return out.toByteArray()
	}

	private fun String.toExternalTypeOrNull(): ExternalExtensionType? =
		ExternalExtensionType.values().firstOrNull { it.name.equals(this, ignoreCase = true) }

	private companion object {
		const val TAG = "SourceGroups"
		const val PROGRESS_BATCH = 50
		const val GROUP_APPLIED_PINS_PREFS = "group_applied_pins"
		val LOCAL_APK_ECOSYSTEMS = listOf("mihon", "aniyomi", "ireader", "tsundoku")
	}
}
