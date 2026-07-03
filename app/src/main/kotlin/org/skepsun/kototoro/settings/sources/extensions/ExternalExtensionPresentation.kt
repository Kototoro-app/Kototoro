package org.skepsun.kototoro.settings.sources.extensions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.repo.RepoAvailableExtension
import org.skepsun.kototoro.mihon.MihonExtensionManager

internal data class InstalledExtensionVersionInfo(
	val versionCode: Long,
	val libVersion: Double,
	val versionName: String,
)

private inline fun <T> Flow<List<T>>.mapInstalledExtensionInfo(
	crossinline packageNameOf: (T) -> String,
	crossinline versionCodeOf: (T) -> Long,
	crossinline libVersionOf: (T) -> Double,
	crossinline versionNameOf: (T) -> String,
): Flow<Map<String, InstalledExtensionVersionInfo>> {
	return map { list ->
		list.associate { ext ->
			packageNameOf(ext) to InstalledExtensionVersionInfo(
				versionCode = versionCodeOf(ext),
				libVersion = libVersionOf(ext),
				versionName = versionNameOf(ext),
			)
		}
	}
}

private inline fun <T> Flow<List<T>>.mapInstalledExtensionEntries(
	crossinline packageNameOf: (T) -> String,
	crossinline appNameOf: (T) -> String,
	crossinline versionNameOf: (T) -> String,
	crossinline versionCodeOf: (T) -> Long,
	crossinline libVersionOf: (T) -> Double,
	crossinline languageOf: (T) -> String,
	crossinline isNsfwOf: (T) -> Boolean,
	crossinline sourceNamesOf: (T) -> List<String>,
): Flow<List<InstalledExtensionEntry>> {
	return map { list ->
		list.map { ext ->
			InstalledExtensionEntry(
				pkgName = packageNameOf(ext),
				name = appNameOf(ext),
				versionName = versionNameOf(ext),
				versionCode = versionCodeOf(ext),
				libVersion = libVersionOf(ext),
				lang = languageOf(ext),
				isNsfw = isNsfwOf(ext),
				sourceNames = sourceNamesOf(ext),
			)
		}
	}
}

internal fun observeInstalledExtensionInfoMap(
	type: ExternalExtensionType,
	mihonExtensionManager: MihonExtensionManager,
	aniyomiExtensionManager: AniyomiExtensionManager,
	ireaderExtensionManager: org.skepsun.kototoro.ireader.IReaderExtensionManager,
): Flow<Map<String, InstalledExtensionVersionInfo>> {
	return when (type) {
		ExternalExtensionType.MIHON -> mihonExtensionManager.installedExtensions.mapInstalledExtensionInfo(
			packageNameOf = { it.pkgName },
			versionCodeOf = { it.versionCode },
			libVersionOf = { it.libVersion },
			versionNameOf = { it.versionName },
		)

		ExternalExtensionType.ANIYOMI -> aniyomiExtensionManager.installedExtensions.mapInstalledExtensionInfo(
			packageNameOf = { it.pkgName },
			versionCodeOf = { it.versionCode },
			libVersionOf = { it.libVersion },
			versionNameOf = { it.versionName },
		)

		ExternalExtensionType.IREADER -> ireaderExtensionManager.installedExtensions.mapInstalledExtensionInfo(
			packageNameOf = { it.pkgName },
			versionCodeOf = { it.versionCode },
			libVersionOf = { it.libVersion },
			versionNameOf = { it.versionName },
		)
		
		ExternalExtensionType.JAR,
		ExternalExtensionType.CLOUDSTREAM -> kotlinx.coroutines.flow.flowOf(emptyMap()) // Unused by non-APK ecosystems right now
	}
}

internal fun observeInstalledExtensionEntries(
	type: ExternalExtensionType,
	mihonExtensionManager: MihonExtensionManager,
	aniyomiExtensionManager: AniyomiExtensionManager,
	ireaderExtensionManager: org.skepsun.kototoro.ireader.IReaderExtensionManager,
	context: android.content.Context? = null,
): Flow<List<InstalledExtensionEntry>> {
	return when (type) {
		ExternalExtensionType.MIHON -> observeMihonInstalledExtensionEntries(mihonExtensionManager)

		ExternalExtensionType.ANIYOMI -> observeAniyomiInstalledExtensionEntries(aniyomiExtensionManager)

		ExternalExtensionType.IREADER -> observeIReaderInstalledExtensionEntries(ireaderExtensionManager)

		ExternalExtensionType.JAR -> {
			val prefs = context?.getSharedPreferences("jar_plugin_versions", android.content.Context.MODE_PRIVATE)
			combine(
				org.skepsun.kototoro.core.extensions.GlobalExtensionManager.mangaSources,
				org.skepsun.kototoro.core.extensions.GlobalExtensionManager.contentSources
			) { manga, content ->
				val allJarNames = (manga.map { it.jarName } + content.map { it.jarName }).distinct()
				allJarNames.map { jarName ->
					val pkg = jarName.removeSuffix(".jar")
					val version = prefs?.getLong(pkg, 1L) ?: 1L
					InstalledExtensionEntry(
						pkgName = pkg,
						name = pkg,
						versionName = "1.0",
						versionCode = version,
						libVersion = 1.0,
						lang = "all",
						isNsfw = false,
						sourceNames = emptyList(),
					)
				}
			}
		}

		ExternalExtensionType.CLOUDSTREAM -> combine(
			context?.let { org.skepsun.kototoro.core.BaseAppHolder.get()?.sources } ?: flowOf(emptyList()),
			flowOf(context?.getSharedPreferences("cloudstream_plugin_versions", android.content.Context.MODE_PRIVATE)),
		) { runtimeSources, prefs ->
			val packageSourceNames = runtimeSources
				.groupBy { it.pluginPackageName }
				.mapValues { (_, sources) -> sources.map { it.displayName }.distinct().sorted() }
			val prefs = context?.getSharedPreferences("cloudstream_plugin_versions", android.content.Context.MODE_PRIVATE)
			val pluginsDir = context?.filesDir?.let { java.io.File(java.io.File(it, "cloudstream"), "plugins") }
			pluginsDir?.listFiles()
				?.filter { it.isFile && (it.extension.equals("cs3", ignoreCase = true) || it.extension.equals("zip", ignoreCase = true)) }
				.orEmpty()
				.map { file ->
					val pkg = prefs?.all?.entries?.firstOrNull { (_, value) -> value is String && value == file.name }
						?.key?.substringBefore(":archive")
						?: file.nameWithoutExtension
					InstalledExtensionEntry(
						pkgName = pkg,
						name = prefs?.getString("${pkg}:name", pkg) ?: pkg,
						versionName = (prefs?.getLong(pkg, 1L) ?: 1L).toString(),
						versionCode = prefs?.getLong(pkg, 1L) ?: 1L,
						libVersion = 1.0,
						lang = prefs?.getString("${pkg}:lang", "all") ?: "all",
						isNsfw = false,
						sourceNames = packageSourceNames[pkg].orEmpty(),
					)
				}
		}
	}
}

internal fun observeMihonInstalledExtensionEntries(
	mihonExtensionManager: MihonExtensionManager,
): Flow<List<InstalledExtensionEntry>> {
	return mihonExtensionManager.installedExtensions.mapInstalledExtensionEntries(
		packageNameOf = { it.pkgName },
		appNameOf = { it.appName.removePrefix("Tachiyomi: ") },
		versionNameOf = { it.versionName },
		versionCodeOf = { it.versionCode },
		libVersionOf = { it.libVersion },
		languageOf = { it.lang },
		isNsfwOf = { it.isNsfw },
		sourceNamesOf = { ext -> ext.sources.map { it.name } },
	)
}

internal fun observeAniyomiInstalledExtensionEntries(
	aniyomiExtensionManager: AniyomiExtensionManager,
): Flow<List<InstalledExtensionEntry>> {
	return aniyomiExtensionManager.installedExtensions.mapInstalledExtensionEntries(
		packageNameOf = { it.pkgName },
		appNameOf = { it.appName.removePrefix("Aniyomi: ") },
		versionNameOf = { it.versionName },
		versionCodeOf = { it.versionCode },
		libVersionOf = { it.libVersion },
		languageOf = { it.lang },
		isNsfwOf = { it.isNsfw },
		sourceNamesOf = { ext -> ext.sources.map { it.name } },
	)
}

internal fun observeIReaderInstalledExtensionEntries(
	ireaderExtensionManager: org.skepsun.kototoro.ireader.IReaderExtensionManager,
): Flow<List<InstalledExtensionEntry>> {
	return ireaderExtensionManager.installedExtensions.mapInstalledExtensionEntries(
		packageNameOf = { it.pkgName },
		appNameOf = { it.appName },
		versionNameOf = { it.versionName },
		versionCodeOf = { it.versionCode },
		libVersionOf = { it.libVersion },
		languageOf = { ext -> ext.sources.map { it.lang }.selectExtensionLanguageCode() },
		isNsfwOf = { it.isNsfw },
		sourceNamesOf = { ext -> ext.sources.map { it.name } },
	)
}

internal fun RepoAvailableExtension.resolveAvailableState(
	installedInfo: InstalledExtensionVersionInfo?,
	isInstalling: Boolean,
): AvailableExtensionState {
	return when {
		isInstalling -> AvailableExtensionState.INSTALLING
		installedInfo == null -> AvailableExtensionState.AVAILABLE
		isNewerThanInstalled(installedInfo.versionCode) -> AvailableExtensionState.UPDATE_AVAILABLE
		else -> AvailableExtensionState.INSTALLED
	}
}
