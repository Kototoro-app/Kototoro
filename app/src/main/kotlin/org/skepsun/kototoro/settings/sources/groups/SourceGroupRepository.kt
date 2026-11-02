package org.skepsun.kototoro.settings.sources.groups

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.SourceOriginsDao
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class SourceGroupFormatException(message: String) : IllegalArgumentException(message)

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
) {

	private val groupsDir: File
		get() = File(context.filesDir, GROUPS_DIR)

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
					if (it.isEnabled || lock != null || it.isPinned) {
						SourceGroupSourceState(
							source = it.source,
							enabled = it.isEnabled,
							pinned = it.isPinned,
							sortKey = it.sortKey,
							locked = lock,
							domain = currentDomainOverride(it.source),
						)
					} else null
				} + database.getJsonSourceDao().findAll().mapNotNull {
					// JSON sources live in their own table; their lock is keyed by the entity id.
					val lock = contentSources.lockState(it.id)
					if (it.enabled || lock != null || it.isPinned) {
						SourceGroupSourceState(
							source = it.id,
							enabled = it.enabled,
							pinned = it.isPinned,
							sortKey = 0,
							locked = lock,
							domain = currentDomainOverride(it.id),
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

	suspend fun importFromUri(uri: Uri): SourceGroup = withContext(Dispatchers.IO) {
		val input = context.contentResolver.openInputStream(uri)
			?: throw IOException("Cannot open input stream")
		val bytes = input.use { readLimited(it, SOURCE_GROUP_MAX_BYTES) }
		val decoded = runCatching { SourceGroupJson.decode(bytes.toString(Charsets.UTF_8)) }
			.getOrElse { throw SourceGroupFormatException(it.message ?: "Invalid JSON") }
		if (!decoded.isValid()) {
			throw SourceGroupFormatException("Unsupported schema or invalid id")
		}
		// Never overwrite an existing group on import: duplicate it under a fresh id instead.
		val stored = if (groupFile(decoded.id).exists()) {
			decoded.copy(id = UUID.randomUUID().toString())
		} else {
			decoded
		}
		writeGroup(stored)
		stored
	}

	/**
	 * Applies the group to this device. Every step is isolated: a failing step is recorded in
	 * the returned list and the remaining steps still run. Progress is published to [progress].
	 */
	suspend fun apply(id: String): List<String> = withContext(Dispatchers.IO) { applyGroup(id) }

	private suspend fun applyGroup(id: String): List<String> {
		check(!_progress.value.running) { "Another source group is being applied" }
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
		fun tick() {
			step++
			_progress.update { it.copy(step = step, errors = errors.toList()) }
		}
		_progress.value = ApplyProgress(
			running = true,
			total = total,
			groupName = group.name,
			manualInstall = manualPackages.map { it.pkg },
		)
		try {
			for (repo in group.repos) {
				runGuarded(errors, context.getString(R.string.source_groups_error_repo, repo.url)) {
					applyRepo(repo)
				}
				tick()
			}

			val catalogCache = HashMap<ExternalExtensionType, List<RepoAvailableExtension>>()
			for (pkg in missing) {
				runGuarded(errors, context.getString(R.string.source_groups_error_package, pkg.pkg)) {
					applyPackage(pkg, catalogCache)
				}
				tick()
			}

			group.sourceStates.forEach { state ->
				runGuarded(errors, context.getString(R.string.source_groups_error_source, state.source)) {
					ensureSourceRow(state)
				}
			}

			// Exclusive batch over the unfiltered db view: getEnabledSources() hides NSFW-disabled,
			// broken and tvbox-invisible sources, so those would never be switched off. The exclusive
			// helper walks every row, keeps locked sources at their lock value and clears the
			// all-sources-enabled flag it just made irrelevant.
			val groupEnabled = group.sourceStates.filter { it.enabled }
				.mapTo(mutableSetOf()) { contentSources.resolveSource(it.source) }
			runGuarded(errors, context.getString(R.string.source_groups_error_apply_state)) {
				contentSources.setSourcesEnabledExclusive(groupEnabled)
			}

			for (state in group.sourceStates) {
				runGuarded(errors, context.getString(R.string.source_groups_error_source, state.source)) {
					applySourceDetails(state)
				}
				tick()
			}

			runGuarded(errors, context.getString(R.string.source_groups_error_jar_priority)) {
				if (group.jarPriority.isNotEmpty()) {
					settings.jarPriorityOrder = group.jarPriority.joinToString(",")
				}
			}
			tick()
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

	private suspend fun applySourceDetails(state: SourceGroupSourceState) {
		val source = contentSources.resolveSource(state.source)
		contentSources.setIsPinned(listOf(source), state.pinned)
		if (state.locked != null) {
			contentSources.setSourceLock(source, state.locked)
		}
		if (!state.source.startsWith("JSON_")) {
			contentSources.setSourceSortKey(state.source, state.sortKey)
		}
		if (!state.domain.isNullOrBlank()) {
			sourcePrefs(state.source).edit()
				.putString(SourceSettings.KEY_DOMAIN, state.domain)
				.apply()
			// Rebuild the parser so the restored domain takes effect without an app restart.
			repositoryFactory.invalidate(source)
		}
	}

	/** The per-source settings file, named exactly like [org.skepsun.kototoro.core.prefs.SourceSettings] names it. */
	private fun sourcePrefs(sourceKey: String): android.content.SharedPreferences =
		context.getSharedPreferences(sourceKey.replace(File.separatorChar, '$'), Context.MODE_PRIVATE)

	/** The manual domain override currently saved for the source, or null when none is set. */
	private fun currentDomainOverride(sourceKey: String): String? =
		sourcePrefs(sourceKey).getString(SourceSettings.KEY_DOMAIN, null)
			?.trim()
			?.takeIf { it.isNotEmpty() }

	private suspend fun runGuarded(errors: MutableList<String>, label: String, block: suspend () -> Unit) {
		try {
			block()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Source group step failed: $label", e)
			errors += "$label: ${e.localizedMessage ?: e.javaClass.simpleName}"
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
		if (target.exists()) target.delete()
		check(temp.renameTo(target)) { "Cannot store source group ${group.id}" }
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
		const val GROUPS_DIR = "source_groups"
		val LOCAL_APK_ECOSYSTEMS = listOf("mihon", "aniyomi", "ireader", "tsundoku")
	}
}
