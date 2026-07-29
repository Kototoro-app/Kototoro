package org.skepsun.kototoro.main.ui.welcome

import android.content.Context
import androidx.core.os.ConfigurationCompat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.LocaleComparator
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.SpaceSwitcherPosition
import org.skepsun.kototoro.core.util.ext.mapSortedByCount
import org.skepsun.kototoro.core.util.ext.sortedWithSafe
import org.skepsun.kototoro.core.util.ext.toList
import org.skepsun.kototoro.core.util.ext.toLocale
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.filter.ui.model.FilterProperty
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.parsers.util.mapToSet
import java.util.Locale
import javax.inject.Inject

import org.skepsun.kototoro.extensions.repo.ExternalExtensionRepoRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.extensions.install.ExtensionInstallResult
import org.skepsun.kototoro.extensions.install.ExtensionInstallService
import kotlinx.coroutines.flow.asStateFlow

import org.skepsun.kototoro.core.extensions.GlobalExtensionManager

@HiltViewModel
class WelcomeViewModel @Inject constructor(
	private val repository: ContentSourcesRepository,
	private val settings: AppSettings,
	private val repoRepository: ExternalExtensionRepoRepository,
	private val installService: ExtensionInstallService,
	@LocalizedAppContext private val context: Context,
) : BaseViewModel() {
	private val supportedContentTypes = listOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO)

	private var updateJob: Job? = null

	private val _isInitializingPlugins = MutableStateFlow(false)
	val isInitializingPlugins = _isInitializingPlugins.asStateFlow()
	private val _spacesEnabled = MutableStateFlow(settings.isEntitySpaceEnabled)
	val spacesEnabled = _spacesEnabled.asStateFlow()
	private val _interfaceStyle = MutableStateFlow(settings.interfaceStyle)
	val interfaceStyle = _interfaceStyle.asStateFlow()
	private val _heroTransitionsEnabled = MutableStateFlow(settings.isSharedElementTransitionsEnabled)
	val heroTransitionsEnabled = _heroTransitionsEnabled.asStateFlow()
	private val _panoramaAnimationEnabled = MutableStateFlow(settings.isPanoramaCoverAnimationEnabled)
	val panoramaAnimationEnabled = _panoramaAnimationEnabled.asStateFlow()
	private val _panoramaTransitionIntensity = MutableStateFlow(settings.panoramaTransitionIntensity)
	val panoramaTransitionIntensity = _panoramaTransitionIntensity.asStateFlow()
	private val _detailsPanoramaHalfScreenEnabled =
		MutableStateFlow(settings.isDetailsPanoramaLimitedToInfoCardMidpoint)
	val detailsPanoramaHalfScreenEnabled = _detailsPanoramaHalfScreenEnabled.asStateFlow()
	private val _spaceSwitcherPosition = MutableStateFlow(settings.spaceSwitcherPosition)
	val spaceSwitcherPosition = _spaceSwitcherPosition.asStateFlow()

	val locales = MutableStateFlow(
		FilterProperty<Locale>(
			availableItems = listOf(Locale.ROOT),
			selectedItems = setOf(Locale.ROOT),
			isLoading = true,
			error = null,
		),
	)

	val types = MutableStateFlow(
		FilterProperty(
			availableItems = supportedContentTypes,
			selectedItems = setOf(ContentType.MANGA),
			isLoading = true,
			error = null,
		),
	)

	init {
		settings.hasSeenPluginWelcome = true
		refreshState()
		launchJob(kotlinx.coroutines.Dispatchers.Default) {
			GlobalExtensionManager.contentSources.collect {
				android.util.Log.d("KototoroInit", "contentSources collected a new plugin map! Triggering reactive chips refresh!")
				refreshState()
			}
		}
		launchJob(kotlinx.coroutines.Dispatchers.Default) {
			GlobalExtensionManager.mangaSources.collect {
				android.util.Log.d("KototoroInit", "mangaSources collected a new plugin map! Triggering reactive chips refresh!")
				refreshState()
			}
		}
	}

	fun refreshState() {
		updateJob?.cancel()
		updateJob = launchJob(Dispatchers.Default) {
			val allSourcesSnapshot = repository.queryAllSources(includeDisabledSources = true)
			val localesGroupsSnapshot = allSourcesSnapshot.groupBy { it.getLocale() ?: Locale.ROOT }

			types.value = types.value.copy(
				availableItems = supportedContentTypes,
				isLoading = false,
			)
			val previouslySelectedLanguages = settings.contentLanguages
			val selectedLocales = if (previouslySelectedLanguages.isNotEmpty()) {
				localesGroupsSnapshot.keys.filterTo(HashSet()) { it.language in previouslySelectedLanguages }
			} else {
				val languagesMap = localesGroupsSnapshot.keys.associateBy { x -> x.language }
				val set = HashSet<Locale>(2)
				ConfigurationCompat.getLocales(context.resources.configuration).toList()
					.firstNotNullOfOrNull { lc -> languagesMap[lc.language] }
					?.let { set += it }
				set += Locale.ROOT
				set
			}
			locales.value = locales.value.copy(
				availableItems = localesGroupsSnapshot.keys.sortedWithSafe(LocaleComparator()),
				selectedItems = selectedLocales,
				isLoading = false,
			)

			val enabledSources = repository.getEnabledSources().map { it.name }.toSet()
			val selectedTypes = allSourcesSnapshot
				.filter { it.name in enabledSources }
				.map { source ->
					when (source.getContentType()) {
						ContentType.HENTAI_MANGA -> ContentType.MANGA
						ContentType.HENTAI_NOVEL -> ContentType.NOVEL
						ContentType.HENTAI_VIDEO -> ContentType.VIDEO
						else -> source.getContentType()
					}
				}
				.toSet()
			if (selectedTypes.isNotEmpty()) {
				types.value = types.value.copy(selectedItems = selectedTypes)
			}

			repository.clearNewSourcesBadge()
			commit()
		}
	}

	fun initializePlugins(mirrorOriginalPosition: Int, repoUrls: List<String>) {
		android.util.Log.d("KototoroInit", "WelcomeViewModel initializePlugins triggered! Args: mirror=$mirrorOriginalPosition, urls=$repoUrls")
		launchJob(Dispatchers.IO) {
			_isInitializingPlugins.value = true
			android.util.Log.d("KototoroInit", "Coroutine launched, isInitializing=true")
			try {
				val newMirror = AppSettings.GitHubMirror.entries.getOrElse(mirrorOriginalPosition) { AppSettings.GitHubMirror.NATIVE }
				settings.gitHubMirror = newMirror
				android.util.Log.d("KototoroInit", "Proxy mirror set to $newMirror")

				for (url in repoUrls) {
					android.util.Log.d("KototoroInit", "Preparing Repo: $url")
					when (val prep = repoRepository.prepareAddRepo(ExternalExtensionType.JAR, url)) {
						is ExternalExtensionRepoRepository.PrepareAddRepoResult.Ready -> {
							android.util.Log.d("KototoroInit", "Repo Prepared successfully, confirming addition")
							repoRepository.confirmAddRepo(prep.repo)
						}
						else -> {
							android.util.Log.d("KototoroInit", "Repo already prepared or invalid url: $prep")
						}
					}
				}

				android.util.Log.d("KototoroInit", "All Repos iterated. Dispatching Global URL Sync...")
				repoRepository.refresh(ExternalExtensionType.JAR)
				android.util.Log.d("KototoroInit", "Global URL Sync execution finished smoothly.")

				val available = repoRepository.getCatalogExtensions(ExternalExtensionType.JAR)
				android.util.Log.d("KototoroInit", "Discovered available extensions: ${available.size}")
				val jarVersions = context.getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE)
				var newlyInstalledCount = 0
				for (extension in available) {
					if (extension.versionCode > jarVersions.getLong(extension.pkgName, -1L)) {
						when (installService.install(extension)) {
							is ExtensionInstallResult.RequiresInstaller -> Unit
							ExtensionInstallResult.Completed -> newlyInstalledCount++
						}
					}
				}
				GlobalExtensionManager.initialize(context)
				refreshState()
				updateJob?.join()
				android.util.Log.d("KototoroInit", "All background initialization work scheduled successfully.")
				kotlinx.coroutines.withContext(Dispatchers.Main) {
					if (newlyInstalledCount > 0) {
						android.widget.Toast.makeText(context, context.getString(org.skepsun.kototoro.R.string.welcome_jar_install_success, newlyInstalledCount), android.widget.Toast.LENGTH_LONG).show()
					} else {
						android.widget.Toast.makeText(context, context.getString(org.skepsun.kototoro.R.string.welcome_jar_install_up_to_date, GlobalExtensionManager.contentSources.value.size), android.widget.Toast.LENGTH_SHORT).show()
					}
				}
			} catch (e: Exception) {
				android.util.Log.e("KototoroInit", "CRITICAL ERROR inside initializePlugins: ${e.message}", e)
				e.printStackTrace()
				kotlinx.coroutines.withContext(Dispatchers.Main) {
					android.widget.Toast.makeText(context, context.getString(org.skepsun.kototoro.R.string.welcome_jar_install_failed, e.message.orEmpty()), android.widget.Toast.LENGTH_LONG).show()
				}
			} finally {
				android.util.Log.d("KototoroInit", "Restoring UI interactive state")
				_isInitializingPlugins.value = false
			}
		}
	}

	fun setLocaleChecked(locale: Locale, isChecked: Boolean) {
		val snapshot = locales.value
		locales.value = snapshot.copy(
			selectedItems = if (isChecked) {
				snapshot.selectedItems + locale
			} else {
				snapshot.selectedItems - locale
			},
		)
		val prevJob = updateJob
		updateJob = launchJob(Dispatchers.Default) {
			prevJob?.join()
			commit()
		}
	}

	fun setTypeChecked(type: ContentType, isChecked: Boolean) {
		val snapshot = types.value
		types.value = snapshot.copy(
			selectedItems = if (isChecked) {
				snapshot.selectedItems + type
			} else {
				snapshot.selectedItems - type
			},
		)
		val prevJob = updateJob
		updateJob = launchJob(Dispatchers.Default) {
			prevJob?.join()
			commit()
		}
	}

	fun setSpacesEnabled(enabled: Boolean) {
		_spacesEnabled.value = enabled
		settings.isEntitySpaceEnabled = enabled
		settings.isSpaceSwitcherEnabled = enabled
	}

	fun setInterfaceStyle(value: InterfaceStyle) {
		_interfaceStyle.value = value
		settings.interfaceStyle = value
	}

	fun setHeroTransitionsEnabled(enabled: Boolean) {
		_heroTransitionsEnabled.value = enabled
		settings.isSharedElementTransitionsEnabled = enabled
	}

	fun setPanoramaAnimationEnabled(enabled: Boolean) {
		_panoramaAnimationEnabled.value = enabled
		settings.isPanoramaCoverAnimationEnabled = enabled
	}

	fun setPanoramaTransitionIntensity(value: Int) {
		_panoramaTransitionIntensity.value = value
		settings.panoramaTransitionIntensity = value
	}

	fun setDetailsPanoramaHalfScreenEnabled(enabled: Boolean) {
		_detailsPanoramaHalfScreenEnabled.value = enabled
		settings.isDetailsPanoramaLimitedToInfoCardMidpoint = enabled
	}

	fun setSpaceSwitcherPosition(position: SpaceSwitcherPosition) {
		_spaceSwitcherPosition.value = position
		settings.spaceSwitcherPosition = position
	}

	private suspend fun commit() {
		val languages = locales.value.selectedItems.mapToSet { it.language }
		val selectedTypes = types.value.selectedItems
		// Expand selected types to include adult variants
		val expandedTypes = selectedTypes.flatMapTo(HashSet()) { type ->
			when (type) {
				ContentType.MANGA -> listOf(ContentType.MANGA, ContentType.HENTAI_MANGA)
				ContentType.NOVEL -> listOf(ContentType.NOVEL, ContentType.HENTAI_NOVEL)
				ContentType.VIDEO -> listOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO)
				else -> listOf(type)
			}
		}
		val enabledSources = repository.queryAllSources(includeDisabledSources = true)
			.filterTo(HashSet()) { x ->
				val localeLang = x.getLocale()?.language ?: ""
				val mappedLang = if (x is org.skepsun.kototoro.ireader.model.IReaderMangaSource) {
					org.skepsun.kototoro.core.model.mapIReaderLangToLocale(x.language) ?: x.language.lowercase()
				} else if (x is org.skepsun.kototoro.mihon.model.MihonMangaSource) {
					x.language.lowercase()
				} else {
					localeLang
				}
				val langMatches = if (mappedLang == "all" || mappedLang == "") {
					"" in languages
				} else {
					languages.any { it.isNotEmpty() && (mappedLang == it || mappedLang.startsWith("$it-") || it.startsWith("$mappedLang-")) }
				}
				x.getContentType() in expandedTypes && langMatches
			}
		repository.setSourcesEnabledExclusive(enabledSources)
		settings.contentLanguages = languages
	}
}
