package org.skepsun.kototoro.settings

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.domain.BackupUtils
import org.skepsun.kototoro.backups.external.ExternalBackupApp
import org.skepsun.kototoro.backups.ui.backup.BackupService
import org.skepsun.kototoro.backups.ui.periodical.PeriodicalBackupSettingsViewModel
import org.skepsun.kototoro.backups.ui.restore.ExternalBackupImportService
import org.skepsun.kototoro.core.github.AppVersion
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.os.AppShortcutManager
import org.skepsun.kototoro.core.os.OpenDocumentTreeHelper
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseActivity
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.ui.util.ActivityRecreationHandle
import org.skepsun.kototoro.core.ui.util.ReversibleActionObserver
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.core.util.FoldableUtils
import org.skepsun.kototoro.core.util.ext.buildBundle
import org.skepsun.kototoro.core.util.ext.end
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getQuantityStringSafe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.start
import org.skepsun.kototoro.core.util.ext.textAndVisible
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.scrobbling.common.ui.ScrobblerAuthHelper
import org.skepsun.kototoro.settings.about.AboutSettingsRoute
import org.skepsun.kototoro.settings.about.AboutSettingsViewModel
import org.skepsun.kototoro.settings.about.AppUpdateActivity
import org.skepsun.kototoro.settings.about.changelog.ChangelogRoute
import org.skepsun.kototoro.settings.about.changelog.ChangelogViewModel
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.SettingsRootScreen
import org.skepsun.kototoro.settings.compose.SettingsSectionScaffold
import org.skepsun.kototoro.settings.compose.buildSettingsRootSections
import org.skepsun.kototoro.settings.nav.NavConfigRoute
import org.skepsun.kototoro.settings.nav.NavConfigViewModel
import org.skepsun.kototoro.settings.userdata.storage.DataCleanupSettingsRoute
import org.skepsun.kototoro.settings.userdata.storage.DataCleanupSettingsViewModel
import org.skepsun.kototoro.settings.discord.DiscordSettingsRoute
import org.skepsun.kototoro.settings.discord.DiscordSettingsViewModel
import org.skepsun.kototoro.settings.protect.ProtectSetupActivity
import org.skepsun.kototoro.settings.search.SettingsItem
import org.skepsun.kototoro.settings.search.SettingsSearchViewModel
import org.skepsun.kototoro.settings.support.TranslationApiSettingsSupport
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.settings.sources.SourceSettingsHostFragment
import org.skepsun.kototoro.settings.sources.SourcesSettingsRoute
import org.skepsun.kototoro.settings.sources.SourcesSettingsViewModel
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourcesActivity
import org.skepsun.kototoro.settings.tracker.TrackerSettingsRoute
import org.skepsun.kototoro.settings.tracker.TrackerSettingsViewModel
import org.skepsun.kototoro.settings.userdata.BackupsSettingsRoute
import org.skepsun.kototoro.settings.utils.RingtonePickContract
import org.skepsun.kototoro.suggestions.ui.SuggestionsWorker
import org.skepsun.kototoro.settings.users.TrackingUserAccountSummaryProvider
import org.skepsun.kototoro.tracker.ui.debug.TrackerDebugActivity
import org.skepsun.kototoro.tracker.work.TrackerNotificationHelper
import org.skepsun.kototoro.tracking.animeoffline.data.AnimeOfflineRepository
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import org.skepsun.kototoro.video.ui.VideoSuperResolutionAdvancedSheet
import org.skepsun.kototoro.scrobbling.discord.ui.DiscordAuthActivity
import kotlin.coroutines.cancellation.CancellationException

@AndroidEntryPoint
class SettingsActivity :
	BaseActivity<SettingsActivityLayoutBinding>(),
	PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

	@Inject
	lateinit var activityRecreationHandle: ActivityRecreationHandle

	@Inject
	lateinit var appShortcutManager: AppShortcutManager

	@Inject
	lateinit var sourcePresetsRepository: SourcePresetsRepository

	@Inject
	lateinit var storageManager: LocalStorageManager

	@Inject
	lateinit var downloadsScheduler: DownloadWorker.Scheduler

	@Inject
	lateinit var animeOfflineRepository: AnimeOfflineRepository

	@Inject
	lateinit var scrobblerAuthHelper: ScrobblerAuthHelper

	@Inject
	lateinit var trackingUserAccountSummaryProvider: TrackingUserAccountSummaryProvider

	@Inject
	lateinit var trackingDiscoveryService: TrackingSiteDiscoveryService

	@Inject
	lateinit var trackerNotificationHelper: TrackerNotificationHelper

	@Inject
	@BaseHttpClient
	lateinit var okHttpClient: OkHttpClient

	@Inject
	lateinit var suggestionsScheduler: SuggestionsWorker.Scheduler

	@Inject
	lateinit var onnxModelManager: OnnxModelManager

	private val isMasterDetails
		get() = viewBinding.containerMaster != null && if (kototoroAppSettings.tabletUiMode == org.skepsun.kototoro.core.prefs.TabletUiMode.STRICT) {
			resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
		} else {
			true
		}

	private val viewModel: SettingsSearchViewModel by viewModels()
	private val rootSettingsViewModel: RootSettingsViewModel by viewModels()
	private val aboutSettingsViewModel: AboutSettingsViewModel by viewModels()
	private val periodicalBackupSettingsViewModel: PeriodicalBackupSettingsViewModel by viewModels()
	private val discordSettingsViewModel: DiscordSettingsViewModel by viewModels()
	private val sourcesSettingsViewModel: SourcesSettingsViewModel by viewModels()
	private val storageAndNetworkSettingsViewModel: StorageAndNetworkSettingsViewModel by viewModels()
	private val dataCleanupSettingsViewModel: DataCleanupSettingsViewModel by viewModels()
	private val navConfigViewModel: NavConfigViewModel by viewModels()
	private val changelogViewModel: ChangelogViewModel by viewModels()
	private val trackerSettingsViewModel: TrackerSettingsViewModel by viewModels()

	private var isFoldUnfolded = false
	private var composeDestination: SettingsDestination? by mutableStateOf(null)
	private val composeNavigationStack = ArrayDeque<SettingsDestination>()
	private var shouldRestoreFragmentOnComposeExit = false
	private var composeDestinationToRestore: SettingsDestination? = null
	private var ttsSettingsCoordinator: TtsSettingsCoordinator? = null
	private var isDataCleanupObserversBound = false
	private var translationApiFetchModelsJob: Job? = null
	private var translationE2EApiFetchModelsJob: Job? = null
	private var proxyTestJob: Job? = null
	private val downloadsStorageTick = MutableStateFlow(0)
	private val downloadsDozeTick = MutableStateFlow(0)
	private val usersResumeTick = MutableStateFlow(0)
	private val trackerDozeTick = MutableStateFlow(0)
	private val trackerNotificationTick = MutableStateFlow(0)
	private val proxyTestSummaryFlow = MutableStateFlow<String?>(null)
	private val proxyIsTestRunningFlow = MutableStateFlow(false)
	private val suggestionsExcludeTagsFlow = MutableStateFlow("")
	private val suggestionsPreferredTagsFlow = MutableStateFlow("")
	private var pendingExternalBackupApp: ExternalBackupApp? = null

	private val composeBackCallback = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() {
			handleComposeNavigateUp()
		}
	}

	private val pickDownloadsPagesDirectory = OpenDocumentTreeHelper(this) { uri ->
		if (uri == null) return@OpenDocumentTreeHelper
		onDownloadsPagesDirectoryPicked(uri)
	}

	private val ignoreDownloadsDozeLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		downloadsDozeTick.update { it + 1 }
	}

	private val ignoreTrackerDozeLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		trackerDozeTick.update { it + 1 }
	}

	private val backupSelectCall = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			router.showBackupRestoreDialog(uri)
		}
	}

	private val externalBackupSelectCall = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			val app = pendingExternalBackupApp ?: return@registerForActivityResult
			if (ExternalBackupImportService.start(this, uri, app)) {
				Toast.makeText(this, R.string.import_backup_started_background, Toast.LENGTH_SHORT).show()
			} else {
				Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
			}
		}
		pendingExternalBackupApp = null
	}

	private val backupCreateCall = registerForActivityResult(
		ActivityResultContracts.CreateDocument("application/zip"),
	) { uri ->
		if (uri != null && !BackupService.start(this, uri)) {
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}

	private val backupOutputSelectCall = OpenDocumentTreeHelper(this) { uri ->
		if (uri != null) {
			val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			contentResolver.takePersistableUriPermission(uri, takeFlags)
			kototoroAppSettings.periodicalBackupDirectory = uri
			periodicalBackupSettingsViewModel.updateSummaryData()
		}
	}

	private val ringtonePickContract = registerForActivityResult(
		RingtonePickContract(R.string.notification_sound),
	) { uri ->
		kototoroAppSettings.notificationSound = uri ?: return@registerForActivityResult
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(SettingsActivityLayoutBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		onBackPressedDispatcher.addCallback(this, composeBackCallback)
		viewBinding.containerCompose.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		viewBinding.containerCompose.setContent {
			KototoroTheme {
				val saveableStateHolder = rememberSaveableStateHolder()
				AnimatedContent(targetState = composeDestination, label = "settings_page") { destination ->
					if (destination != null) {
						saveableStateHolder.SaveableStateProvider(composeDestinationStateKey(destination)) {
							RenderComposeDestination(destination)
						}
					}
				}
			}
		}
		masterContainerComposeView()?.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		renderMasterSettingsRoot()
		supportFragmentManager.addOnBackStackChangedListener {
			restoreComposeDestinationIfNeeded()
		}
		val fm = supportFragmentManager
		val currentFragment = fm.findFragmentById(R.id.container)
		val restoredDestination = savedInstanceState?.toComposeDestination()
		composeDestinationToRestore = savedInstanceState
			?.getBoolean(STATE_PENDING_RESTORE_ROOT)
			?.takeIf { it }
			?.let { SettingsDestination.Root }
		if (currentFragment == null) {
			openDefaultFragment()
		}
		viewModel.onNavigateToPreference.observeEvent(this, ::navigateToPreference)
		aboutSettingsViewModel.onUpdateAvailable.observeEvent(this, ::onAboutUpdateAvailable)
		changelogViewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.root, null))
		restoredDestination?.let { openComposeDestination(it, shouldRestoreFragment = savedInstanceState.getBoolean(STATE_COMPOSE_RESTORE_FRAGMENT)) }

		observeFoldableState()
	}

	override fun onResume() {
		super.onResume()
		when (composeDestination) {
			SettingsDestination.DownloadsSettings -> {
				downloadsStorageTick.update { it + 1 }
				downloadsDozeTick.update { it + 1 }
			}
			SettingsDestination.UsersSettings -> {
				usersResumeTick.update { it + 1 }
			}
			SettingsDestination.SuggestionsSettings -> {
				refreshSuggestionsTags()
			}
			SettingsDestination.TrackerSettings -> {
				trackerDozeTick.update { it + 1 }
				trackerNotificationTick.update { it + 1 }
			}
			else -> Unit
		}
		// 从后台恢复或状态变化后，立即按当前折叠状态调整布局
		adjustLayoutForFoldableState()
	}

	override fun onDestroy() {
		translationApiFetchModelsJob?.cancel()
		translationE2EApiFetchModelsJob?.cancel()
		ttsSettingsCoordinator?.stop()
		ttsSettingsCoordinator = null
		super.onDestroy()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		val isTablet = viewBinding.containerMaster != null
		viewBinding.appbar.updatePaddingRelative(
			start = bars.start(v),
			top = bars.top,
			end = if (isTablet) 0 else bars.end(v),
		)
		viewBinding.appbarDetail?.updatePaddingRelative(
			end = bars.end(v),
			top = bars.top,
		)
		return insets
	}

	override fun onPreferenceStartFragment(
		caller: PreferenceFragmentCompat,
		pref: Preference,
	): Boolean {
		val fragmentName = pref.fragment ?: return false
		openFragment(
			fragmentClass = FragmentFactory.loadFragmentClass(classLoader, fragmentName),
			args = pref.peekExtras(),
			isFromRoot = false,
		)
		return true
	}

	override fun onSupportNavigateUp(): Boolean {
		if (composeDestination != null) {
			handleComposeNavigateUp()
			return true
		}
		return super.onSupportNavigateUp()
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
			when (val destination = composeDestination) {
				SettingsDestination.Root -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_ROOT)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.AppearanceSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_APPEARANCE_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.UsersSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_USERS_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.AISettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_AI_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.OcrModelsSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_OCR_MODELS_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.AiImageEnhancementSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_AI_IMAGE_ENHANCEMENT_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.AiVideoEnhancementSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_AI_VIDEO_ENHANCEMENT_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.TtsSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_TTS_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.PlaybackSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_PLAYBACK_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.ReaderSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_READER_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.SourcesSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_SOURCES_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.SuggestionsSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_SUGGESTIONS_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.SyncSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_SYNC_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.BackupsSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_BACKUPS_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.TranslationSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_TRANSLATION_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.TranslationApiSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_TRANSLATION_API_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.TranslationE2EApiSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_TRANSLATION_E2E_API_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.StorageAndNetworkSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_STORAGE_AND_NETWORK_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.DataCleanupSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_DATA_CLEANUP_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.DownloadsSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_DOWNLOADS_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.TrackerSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_TRACKER_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.NotificationSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_NOTIFICATION_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.ServicesSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_SERVICES_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.DiscordSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_DISCORD_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.ProxySettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_PROXY_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.NavConfigSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_NAV_CONFIG_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.ChangelogSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_CHANGELOG_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				SettingsDestination.AboutSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_ABOUT_SETTINGS)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				is SettingsDestination.UnifiedSources -> Unit
				null,
				is SettingsDestination.FragmentDestination -> Unit
			}
		outState.putBoolean(
			STATE_PENDING_RESTORE_ROOT,
			composeDestinationToRestore == SettingsDestination.Root,
		)
	}

	fun setSectionTitle(title: CharSequence?) {
		viewBinding.toolbarDetail?.let { toolbar ->
			toolbar.title = title
			toolbar.isVisible = title != null
		} ?: setTitle(title ?: getString(R.string.settings))
	}

	fun setSectionToolbarActions(view: View?, fillAvailableWidth: Boolean = false) {
		val toolbar = viewBinding.toolbarDetail ?: viewBinding.toolbar
		val tag = "section_toolbar_actions"
		(0 until toolbar.childCount)
			.map { toolbar.getChildAt(it) }
			.firstOrNull { it.tag == tag }
			?.let(toolbar::removeView)
		if (view == null) return

		view.tag = tag
		val maxActionWidth = (420 * resources.displayMetrics.density).toInt()
		val actionWidth = (resources.displayMetrics.widthPixels * 0.62f).toInt()
			.coerceAtMost(maxActionWidth)
		toolbar.addView(
			view,
			androidx.appcompat.widget.Toolbar.LayoutParams(
				if (fillAvailableWidth) ViewGroup.LayoutParams.MATCH_PARENT else actionWidth,
				ViewGroup.LayoutParams.MATCH_PARENT,
				Gravity.END or Gravity.CENTER_VERTICAL,
			),
		)
	}

	private fun setLegacyTopBarVisible(isVisible: Boolean) {
		viewBinding.legacyTopBarHost.isVisible = isVisible
		viewBinding.appbarDetail?.isVisible = isVisible
	}

	private fun renderComposeContent(
		showLegacyTopBar: Boolean,
		destination: SettingsDestination,
	) {
		setLegacyTopBarVisible(showLegacyTopBar)
		setTitle(composeDestinationTitle(destination))
	}

	@Composable
	private fun RenderComposeSection(
		title: String,
		actions: (@Composable BoxScope.() -> Unit)? = null,
		content: @Composable () -> Unit,
	) {
		SettingsSectionScaffold(
			title = title,
			onNavigateUp = ::handleComposeNavigateUp,
			actions = actions,
			content = content,
		)
	}

	fun openFragment(fragmentClass: Class<out Fragment>, args: Bundle?, isFromRoot: Boolean) {
		composeDestinationToRestore = composeDestination.takeIf { it == SettingsDestination.Root }
		composeNavigationStack.clear()
		val shouldPopHiddenFragment = composeDestination != null &&
			shouldRestoreFragmentOnComposeExit &&
			supportFragmentManager.backStackEntryCount > 0
		closeComposeDestination(restorePreviousFragment = false)
		if (shouldPopHiddenFragment) {
			supportFragmentManager.popBackStackImmediate()
		}
		viewModel.discardSearch()
		val hasFragment = supportFragmentManager.findFragmentById(R.id.container) != null
		supportFragmentManager.commit {
			setReorderingAllowed(true)
			replace(R.id.container, fragmentClass, args)
			setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
			if (!isMasterDetails || (hasFragment && !isFromRoot)) {
				addToBackStack(null)
			}
		}
	}

	fun replaceCurrentFragmentWithDestination(destination: SettingsDestination) {
		if (destination is SettingsDestination.UnifiedSources) {
			startActivity(
				UnifiedSourcesActivity.newIntent(
					context = this,
					initialRepositoryKind = destination.initialRepositoryKind,
					initialRepositoryUrl = destination.initialRepositoryUrl,
				),
			)
			return
		}
		if (supportFragmentManager.isStateSaved) {
			return
		}
		composeNavigationStack.clear()
		val currentFragment = supportFragmentManager.findFragmentById(R.id.container)
		closeComposeDestination(restorePreviousFragment = false)
		if (currentFragment != null) {
			supportFragmentManager.commitNow {
				setReorderingAllowed(true)
				remove(currentFragment)
			}
		}
		viewModel.discardSearch()
		composeDestinationToRestore = null
		composeDestination = destination
		shouldRestoreFragmentOnComposeExit = false
		viewBinding.containerCompose.isVisible = true
		prepareComposeDestination(destination)
		renderComposeContent(showLegacyTopBar = false, destination = destination)
		composeBackCallback.isEnabled = true
	}

		fun openDestination(destination: SettingsDestination, args: Bundle?, isFromRoot: Boolean) {
			when (destination) {
				SettingsDestination.Root -> openComposeDestination(
					destination,
					shouldRestoreFragment = false,
					pushCurrentToStack = false,
				)
				SettingsDestination.AppearanceSettings,
				SettingsDestination.UsersSettings,
				SettingsDestination.AISettings,
				SettingsDestination.OcrModelsSettings,
				SettingsDestination.AiImageEnhancementSettings,
				SettingsDestination.AiVideoEnhancementSettings,
				SettingsDestination.TtsSettings,
				SettingsDestination.PlaybackSettings,
				SettingsDestination.ReaderSettings,
				SettingsDestination.SourcesSettings,
				SettingsDestination.SuggestionsSettings,
				SettingsDestination.SyncSettings,
				SettingsDestination.BackupsSettings,
				SettingsDestination.TranslationSettings,
				SettingsDestination.TranslationApiSettings,
				SettingsDestination.TranslationE2EApiSettings -> openComposeDestination(
					destination,
					shouldRestoreFragment = shouldRestoreFragmentForNextDestination(isFromRoot),
				)
				SettingsDestination.StorageAndNetworkSettings,
				SettingsDestination.DataCleanupSettings,
				SettingsDestination.DownloadsSettings,
				SettingsDestination.TrackerSettings,
				SettingsDestination.NotificationSettings,
				SettingsDestination.ServicesSettings,
				SettingsDestination.DiscordSettings,
				SettingsDestination.ProxySettings,
				SettingsDestination.NavConfigSettings,
				SettingsDestination.ChangelogSettings,
				SettingsDestination.AboutSettings -> openComposeDestination(
					destination,
					shouldRestoreFragment = shouldRestoreFragmentForNextDestination(isFromRoot),
				)
				is SettingsDestination.FragmentDestination -> openFragment(destination.fragmentClass, args, isFromRoot)
				is SettingsDestination.UnifiedSources -> {
					startActivity(
						UnifiedSourcesActivity.newIntent(
							context = this,
							initialRepositoryKind = destination.initialRepositoryKind,
							initialRepositoryUrl = destination.initialRepositoryUrl,
						),
					)
				}
			}
		}

		private fun openDefaultFragment() {
			if (intent?.action == AppRouter.ACTION_MANAGE_SOURCES) {
				startActivity(UnifiedSourcesActivity.newIntent(this))
				finishAfterTransition()
				return
			}
			if (intent?.action == Intent.ACTION_VIEW && intent.data?.host == "add-repo") {
				startActivity(Intent(this, UnifiedSourcesActivity::class.java).setData(intent.data))
				finishAfterTransition()
				return
			}
			val composeDestination = when (intent?.action) {
				AppRouter.ACTION_SUGGESTIONS -> SettingsDestination.SuggestionsSettings
				AppRouter.ACTION_SYNC_SETTINGS,
				AppRouter.ACTION_PERIODIC_BACKUP,
				AppRouter.ACTION_HISTORY -> SettingsDestination.BackupsSettings
				AppRouter.ACTION_TRANSLATION -> SettingsDestination.TranslationSettings
				AppRouter.ACTION_TRACKER -> SettingsDestination.TrackerSettings
				AppRouter.ACTION_TRACKING_ACCOUNTS -> SettingsDestination.UsersSettings
				AppRouter.ACTION_MANAGE_DISCORD -> SettingsDestination.DiscordSettings
				AppRouter.ACTION_PROXY -> SettingsDestination.ProxySettings
				AppRouter.ACTION_READER -> SettingsDestination.ReaderSettings
				AppRouter.ACTION_SOURCES -> SettingsDestination.SourcesSettings
			AppRouter.ACTION_MANAGE_DOWNLOADS -> SettingsDestination.DownloadsSettings
			AppRouter.ACTION_MANAGE_SOURCES -> null
			Intent.ACTION_VIEW -> when (intent.data?.host) {
				"add-repo" -> null
				HOST_ABOUT -> SettingsDestination.AboutSettings
				else -> null
			}
			else -> null
		}
		if (composeDestination != null) {
			openComposeDestination(composeDestination, shouldRestoreFragment = false)
			return
		}
		val fragment = when (intent?.action) {
			AppRouter.ACTION_SOURCES -> null
			AppRouter.ACTION_SOURCE -> SourceSettingsHostFragment.newInstance(
				ContentSource(intent.getStringExtra(AppRouter.KEY_SOURCE)),
			)
			Intent.ACTION_VIEW -> {
				when (intent.data?.host) {
					HOST_ABOUT -> null
					else -> null
				}
			}
			else -> null
		}
		if (fragment == null) {
			openComposeDestination(SettingsDestination.Root, shouldRestoreFragment = false)
			return
		}
		supportFragmentManager.commit {
			setReorderingAllowed(true)
			replace(R.id.container, fragment)
		}
	}

	private fun navigateToPreference(item: SettingsItem) {
		val args = buildBundle(1) {
			putString(ARG_PREF_KEY, item.key)
		}
		openDestination(item.destination, args, true)
	}

	private fun shouldRestoreFragmentForNextDestination(isFromRoot: Boolean): Boolean {
		val hasFragment = supportFragmentManager.findFragmentById(R.id.container) != null
		return !isMasterDetails || (hasFragment && !isFromRoot)
	}

	private fun openComposeDestination(
		destination: SettingsDestination,
		shouldRestoreFragment: Boolean,
		pushCurrentToStack: Boolean = true,
	) {
		viewModel.discardSearch()
		if (supportFragmentManager.isStateSaved) {
			return
		}
		if (isMasterDetails && destination == SettingsDestination.Root) {
			composeNavigationStack.clear()
			closeComposeDestination(restorePreviousFragment = false)
			return
		}
		val currentComposeDestination = composeDestination
		if (
			pushCurrentToStack &&
			currentComposeDestination != null &&
			currentComposeDestination != destination
		) {
			composeNavigationStack.addLast(currentComposeDestination)
		}
		val currentFragment = supportFragmentManager.findFragmentById(R.id.container)
		if (currentComposeDestination == null && currentFragment != null && !currentFragment.isHidden) {
			supportFragmentManager.commit {
				setReorderingAllowed(true)
				hide(currentFragment)
				if (shouldRestoreFragment) {
					addToBackStack(COMPOSE_HIDE_BACKSTACK_NAME)
				}
			}
		}
		composeDestination = destination
		shouldRestoreFragmentOnComposeExit = shouldRestoreFragment
		viewBinding.containerCompose.isVisible = true
		prepareComposeDestination(destination)
		renderComposeContent(showLegacyTopBar = false, destination = destination)
		composeBackCallback.isEnabled = true
	}

	private fun prepareComposeDestination(destination: SettingsDestination) {
		when (destination) {
			SettingsDestination.UsersSettings -> {
				usersResumeTick.update { it + 1 }
			}
			SettingsDestination.TtsSettings -> {
				if (ttsSettingsCoordinator == null) {
					ttsSettingsCoordinator = TtsSettingsCoordinator(this, kototoroAppSettings).also { it.start() }
				}
			}
			SettingsDestination.SourcesSettings -> {
				sourcesSettingsViewModel.refreshLinksEnabled()
			}
			SettingsDestination.SuggestionsSettings -> {
				refreshSuggestionsTags()
			}
			SettingsDestination.BackupsSettings -> {
				periodicalBackupSettingsViewModel.updateSummaryData()
			}
			SettingsDestination.DataCleanupSettings -> {
				bindDataCleanupObservers()
			}
			SettingsDestination.DownloadsSettings -> {
				downloadsStorageTick.update { it + 1 }
				downloadsDozeTick.update { it + 1 }
			}
			SettingsDestination.TrackerSettings -> {
				trackerDozeTick.update { it + 1 }
				trackerNotificationTick.update { it + 1 }
			}
			else -> Unit
		}
	}

	private fun composeDestinationStateKey(destination: SettingsDestination): String {
		return when (destination) {
			SettingsDestination.Root -> COMPOSE_DESTINATION_ROOT
			SettingsDestination.AppearanceSettings -> COMPOSE_DESTINATION_APPEARANCE_SETTINGS
			SettingsDestination.UsersSettings -> COMPOSE_DESTINATION_USERS_SETTINGS
			SettingsDestination.AISettings -> COMPOSE_DESTINATION_AI_SETTINGS
			SettingsDestination.OcrModelsSettings -> COMPOSE_DESTINATION_OCR_MODELS_SETTINGS
			SettingsDestination.AiImageEnhancementSettings -> COMPOSE_DESTINATION_AI_IMAGE_ENHANCEMENT_SETTINGS
			SettingsDestination.AiVideoEnhancementSettings -> COMPOSE_DESTINATION_AI_VIDEO_ENHANCEMENT_SETTINGS
			SettingsDestination.TtsSettings -> COMPOSE_DESTINATION_TTS_SETTINGS
			SettingsDestination.PlaybackSettings -> COMPOSE_DESTINATION_PLAYBACK_SETTINGS
			SettingsDestination.ReaderSettings -> COMPOSE_DESTINATION_READER_SETTINGS
			SettingsDestination.SourcesSettings -> COMPOSE_DESTINATION_SOURCES_SETTINGS
			SettingsDestination.SuggestionsSettings -> COMPOSE_DESTINATION_SUGGESTIONS_SETTINGS
			SettingsDestination.SyncSettings -> COMPOSE_DESTINATION_SYNC_SETTINGS
			SettingsDestination.BackupsSettings -> COMPOSE_DESTINATION_BACKUPS_SETTINGS
			SettingsDestination.TranslationSettings -> COMPOSE_DESTINATION_TRANSLATION_SETTINGS
			SettingsDestination.TranslationApiSettings -> COMPOSE_DESTINATION_TRANSLATION_API_SETTINGS
			SettingsDestination.TranslationE2EApiSettings -> COMPOSE_DESTINATION_TRANSLATION_E2E_API_SETTINGS
			SettingsDestination.StorageAndNetworkSettings -> COMPOSE_DESTINATION_STORAGE_AND_NETWORK_SETTINGS
			SettingsDestination.DataCleanupSettings -> COMPOSE_DESTINATION_DATA_CLEANUP_SETTINGS
			SettingsDestination.DownloadsSettings -> COMPOSE_DESTINATION_DOWNLOADS_SETTINGS
			SettingsDestination.TrackerSettings -> COMPOSE_DESTINATION_TRACKER_SETTINGS
			SettingsDestination.NotificationSettings -> COMPOSE_DESTINATION_NOTIFICATION_SETTINGS
			SettingsDestination.ServicesSettings -> COMPOSE_DESTINATION_SERVICES_SETTINGS
			SettingsDestination.DiscordSettings -> COMPOSE_DESTINATION_DISCORD_SETTINGS
			SettingsDestination.ProxySettings -> COMPOSE_DESTINATION_PROXY_SETTINGS
			SettingsDestination.NavConfigSettings -> COMPOSE_DESTINATION_NAV_CONFIG_SETTINGS
			SettingsDestination.ChangelogSettings -> COMPOSE_DESTINATION_CHANGELOG_SETTINGS
			SettingsDestination.AboutSettings -> COMPOSE_DESTINATION_ABOUT_SETTINGS
			is SettingsDestination.FragmentDestination -> "fragment:${destination.fragmentClass.name}"
			is SettingsDestination.UnifiedSources -> "unified:${destination.initialRepositoryKind}:${destination.initialRepositoryUrl}"
		}
	}

	private fun composeDestinationTitle(destination: SettingsDestination): String {
		return when (destination) {
			SettingsDestination.Root -> getString(R.string.settings)
			SettingsDestination.AppearanceSettings -> getString(R.string.appearance)
			SettingsDestination.UsersSettings -> getString(R.string.users)
			SettingsDestination.AISettings -> getString(R.string.ai_settings)
			SettingsDestination.OcrModelsSettings -> getString(R.string.reader_translation_ocr_models_title)
			SettingsDestination.AiImageEnhancementSettings -> getString(R.string.ai_image_enhancement_settings)
			SettingsDestination.AiVideoEnhancementSettings -> getString(R.string.ai_video_enhancement_settings)
			SettingsDestination.TtsSettings -> getString(R.string.tts_settings_title)
			SettingsDestination.PlaybackSettings -> getString(R.string.playback_settings)
			SettingsDestination.ReaderSettings -> getString(R.string.reader_settings)
			SettingsDestination.SourcesSettings -> getString(R.string.remote_sources)
			SettingsDestination.SuggestionsSettings -> getString(R.string.suggestions)
			SettingsDestination.SyncSettings -> getString(R.string.sync_settings)
			SettingsDestination.BackupsSettings -> getString(R.string.backup_restore)
			SettingsDestination.TranslationSettings -> getString(R.string.translation_settings)
			SettingsDestination.TranslationApiSettings -> getString(R.string.ai_api_settings)
			SettingsDestination.TranslationE2EApiSettings -> getString(R.string.reader_translation_e2e_api_settings_title)
			SettingsDestination.StorageAndNetworkSettings -> getString(R.string.storage_and_network)
			SettingsDestination.DataCleanupSettings -> getString(R.string.data_removal)
			SettingsDestination.DownloadsSettings -> getString(R.string.downloads)
			SettingsDestination.TrackerSettings -> getString(R.string.check_for_new_chapters)
			SettingsDestination.NotificationSettings -> getString(R.string.notifications)
			SettingsDestination.ServicesSettings -> getString(R.string.services)
			SettingsDestination.DiscordSettings -> getString(R.string.discord)
			SettingsDestination.ProxySettings -> getString(R.string.proxy)
			SettingsDestination.NavConfigSettings -> getString(R.string.main_screen_sections)
			SettingsDestination.ChangelogSettings -> getString(R.string.changelog)
			SettingsDestination.AboutSettings -> getString(R.string.about)
			is SettingsDestination.FragmentDestination,
			is SettingsDestination.UnifiedSources -> getString(R.string.settings)
		}
	}

	@Composable
	private fun RenderComposeDestination(destination: SettingsDestination) {
		when (destination) {
			SettingsDestination.Root -> {
				val enabledSourcesCount by rootSettingsViewModel.enabledSourcesCount.collectAsStateWithLifecycle()
				val searchResults by viewModel.content.collectAsStateWithLifecycle()
				val searchQuery by viewModel.queryText.collectAsStateWithLifecycle()
				SettingsRootScreen(
					sections = buildSettingsRootSections(
						context = this,
						enabledSourcesCount = enabledSourcesCount,
						totalSourcesCount = rootSettingsViewModel.totalSourcesCount,
						classLoader = classLoader,
						onOpenFragment = { fragmentClass ->
							openFragment(fragmentClass, null, true)
						},
						onOpenDestination = { composeDestination ->
							openDestination(composeDestination, null, true)
						},
					),
					title = getString(R.string.settings),
					subtitle = getString(R.string.app_version, org.skepsun.kototoro.BuildConfig.VERSION_NAME),
					searchQuery = searchQuery,
					searchResults = searchResults,
					onSearchQueryChange = viewModel::setSearchQuery,
					onSearchResultClick = { item -> navigateToPreference(item) },
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.AppearanceSettings -> RenderComposeSection(title = getString(R.string.appearance)) {
				AppearanceSettingsRoute(
					settings = kototoroAppSettings,
					activityRecreationHandle = activityRecreationHandle,
					appShortcutManager = appShortcutManager,
					sourcePresetsRepository = sourcePresetsRepository,
					onOpenNavConfig = {
						openDestination(SettingsDestination.NavConfigSettings, null, false)
					},
					onOpenProtectSetup = {
						startActivity(Intent(this, ProtectSetupActivity::class.java))
					},
				)
			}
			SettingsDestination.UsersSettings -> RenderComposeSection(title = getString(R.string.users)) {
				val refreshKey by usersResumeTick.collectAsStateWithLifecycle()
				UsersSettingsRoute(
					settings = kototoroAppSettings,
					scrobblerAuthHelper = scrobblerAuthHelper,
					trackingUserAccountSummaryProvider = trackingUserAccountSummaryProvider,
					trackingDiscoveryService = trackingDiscoveryService,
					refreshKey = refreshKey,
					onOpenScrobblerSettings = { service ->
						router.openScrobblerSettings(service)
					},
				)
			}
			SettingsDestination.AISettings -> RenderComposeSection(title = getString(R.string.ai_settings)) {
				AISettingsRoute(
					onOpenOcrModels = { openDestination(SettingsDestination.OcrModelsSettings, null, false) },
					onOpenApiSettings = { openDestination(SettingsDestination.TranslationApiSettings, null, false) },
					onOpenE2eApiSettings = { openDestination(SettingsDestination.TranslationE2EApiSettings, null, false) },
					onOpenTranslationSettings = { openDestination(SettingsDestination.TranslationSettings, null, false) },
					onOpenImageEnhancementSettings = {
						openDestination(SettingsDestination.AiImageEnhancementSettings, null, false)
					},
					onOpenTtsSettings = { openDestination(SettingsDestination.TtsSettings, null, false) },
					onOpenVideoEnhancementSettings = {
						openDestination(SettingsDestination.AiVideoEnhancementSettings, null, false)
					},
				)
			}
			SettingsDestination.OcrModelsSettings -> RenderComposeSection(
				title = getString(R.string.reader_translation_ocr_models_title),
			) {
				OcrModelsRoute(
					onnxModelManager = onnxModelManager,
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.AiImageEnhancementSettings -> RenderComposeSection(
				title = getString(R.string.ai_image_enhancement_settings),
			) {
				AIImageEnhancementSettingsRoute(
					settings = kototoroAppSettings,
					onnxModelManager = onnxModelManager,
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.AiVideoEnhancementSettings -> RenderComposeSection(
				title = getString(R.string.ai_video_enhancement_settings),
			) {
				AIVideoEnhancementSettingsRoute(
					settings = kototoroAppSettings,
					onAdvancedSettingsClick = ::showVideoSuperResolutionAdvancedSheet,
				)
			}
			SettingsDestination.TtsSettings -> RenderComposeSection(title = getString(R.string.tts_settings_title)) {
				TtsSettingsRoute(
					settings = kototoroAppSettings,
					coordinator = requireNotNull(ttsSettingsCoordinator),
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.PlaybackSettings -> RenderComposeSection(title = getString(R.string.playback_settings)) {
				PlaybackSettingsRoute(
					settings = kototoroAppSettings,
					onMpvConfClick = {
						org.skepsun.kototoro.video.player.MpvConfigManager.showMpvConfigDialog(this, viewBinding.containerCompose)
					},
					onAiSettingsClick = {
						openDestination(SettingsDestination.AISettings, null, false)
					},
				)
			}
			SettingsDestination.ReaderSettings -> RenderComposeSection(title = getString(R.string.reader_settings)) {
				ReaderSettingsRoute(
					settings = kototoroAppSettings,
					onReaderTapActionsClick = {
						startActivity(Intent(this, org.skepsun.kototoro.settings.reader.ReaderTapGridConfigActivity::class.java))
					},
					onReaderAiSettingsEntryClick = {
						openDestination(SettingsDestination.AISettings, null, false)
					},
				)
			}
			SettingsDestination.StorageAndNetworkSettings -> RenderComposeSection(
				title = getString(R.string.storage_and_network),
			) {
				StorageAndNetworkSettingsRoute(
					settings = kototoroAppSettings,
					viewModel = storageAndNetworkSettingsViewModel,
					dataCleanupViewModel = dataCleanupSettingsViewModel,
					onOpenProxySettings = {
						openDestination(SettingsDestination.ProxySettings, null, false)
					},
					onConfirmClearSearchHistory = ::confirmClearSearchHistory,
					onConfirmClearCookies = ::confirmClearCookies,
					onConfirmCleanupChapters = ::confirmCleanupChapters,
					onConfirmClearLocalManga = ::confirmClearLocalManga,
					onConfirmClearLocalNovels = ::confirmClearLocalNovels,
					onConfirmClearLocalVideos = ::confirmClearLocalVideos,
				)
			}
			SettingsDestination.DataCleanupSettings -> RenderComposeSection(title = getString(R.string.data_removal)) {
				DataCleanupSettingsRoute(
					settings = kototoroAppSettings,
					viewModel = dataCleanupSettingsViewModel,
					onClearSearchHistory = ::confirmClearSearchHistory,
					onClearCookies = ::confirmClearCookies,
					onDeleteReadChapters = ::confirmCleanupChapters,
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.SuggestionsSettings -> RenderComposeSection(title = getString(R.string.suggestions)) {
				SuggestionsSettingsRoute(
					settings = kototoroAppSettings,
					suggestionsScheduler = suggestionsScheduler,
					excludeTagsFlow = suggestionsExcludeTagsFlow,
					preferredTagsFlow = suggestionsPreferredTagsFlow,
				)
			}
			SettingsDestination.SyncSettings -> RenderComposeSection(title = getString(R.string.sync_settings)) {
				SyncSettingsRoute(
					settings = kototoroAppSettings,
					backupSettingsViewModel = periodicalBackupSettingsViewModel,
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.BackupsSettings -> RenderComposeSection(title = getString(R.string.backup_restore)) {
				BackupsSettingsRoute(
					settings = kototoroAppSettings,
					viewModel = periodicalBackupSettingsViewModel,
					onBackupOutputClick = {
						if (!backupOutputSelectCall.tryLaunch(null)) {
							Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
						}
					},
					onCreateBackupClick = {
						if (!backupCreateCall.tryLaunch(BackupUtils.generateFileName(this))) {
							Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
						}
					},
					onRestoreBackupClick = {
						if (!backupSelectCall.tryLaunch(arrayOf("*/*"))) {
							Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
						}
					},
					onImportExternalBackupFilePick = { app ->
						pendingExternalBackupApp = app
						if (!externalBackupSelectCall.tryLaunch(arrayOf("*/*"))) {
							pendingExternalBackupApp = null
							Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
						}
					},
				)
			}
			SettingsDestination.TranslationSettings -> RenderComposeSection(
				title = getString(R.string.translation_settings),
			) {
				TranslationSettingsRoute(
					settings = kototoroAppSettings,
					onnxModelManager = onnxModelManager,
					onOpenOcrModels = { openDestination(SettingsDestination.OcrModelsSettings, null, false) },
					onOpenApiSettings = { openDestination(SettingsDestination.TranslationApiSettings, null, false) },
					onOpenE2eApiSettings = { openDestination(SettingsDestination.TranslationE2EApiSettings, null, false) },
				)
			}
			SettingsDestination.TranslationApiSettings -> RenderComposeSection(
				title = getString(R.string.ai_api_settings),
			) {
				TranslationApiSettingsRoute(
					settings = kototoroAppSettings,
					onFetchModelsClick = ::fetchAndPickTranslationApiModel,
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.TranslationE2EApiSettings -> RenderComposeSection(
				title = getString(R.string.reader_translation_e2e_api_settings_title),
			) {
				TranslationE2EApiSettingsRoute(
					settings = kototoroAppSettings,
					onFetchModelsClick = ::fetchAndPickTranslationE2EApiModel,
				)
			}
			SettingsDestination.DownloadsSettings -> RenderComposeSection(title = getString(R.string.downloads)) {
				val storageRefreshKey by downloadsStorageTick.collectAsStateWithLifecycle()
				val dozeRefreshKey by downloadsDozeTick.collectAsStateWithLifecycle()
				DownloadsSettingsRoute(
					settings = kototoroAppSettings,
					storageManager = storageManager,
					storageRefreshKey = storageRefreshKey,
					dozeRefreshKey = dozeRefreshKey,
					onOpenMangaDirectories = { router.openDirectoriesSettings() },
					onOpenMangaStorage = { router.showDirectorySelectDialog() },
					onOpenNovelStorage = {
						router.showDirectorySelectDialog(
							org.skepsun.kototoro.settings.storage.ContentDirectorySelectDialog.CONTENT_TYPE_NOVEL,
						)
					},
					onOpenVideoStorage = {
						router.showDirectorySelectDialog(
							org.skepsun.kototoro.settings.storage.ContentDirectorySelectDialog.CONTENT_TYPE_VIDEO,
						)
					},
					onAllowMeteredNetworkChange = { option ->
						kototoroAppSettings.allowDownloadOnMeteredNetwork = option
						updateDownloadsConstraints()
					},
					onRequestIgnoreDoze = ::startDownloadsIgnoreDozeActivity,
					onPickPagesDirectory = { initialUri ->
						pickDownloadsPagesDirectory.tryLaunch(initialUri)
					},
				)
			}
			SettingsDestination.TrackerSettings -> RenderComposeSection(
				title = getString(R.string.check_for_new_chapters),
			) {
				val dozeRefreshKey by trackerDozeTick.collectAsStateWithLifecycle()
				val notificationRefreshKey by trackerNotificationTick.collectAsStateWithLifecycle()
				TrackerSettingsRoute(
					settings = kototoroAppSettings,
					notificationHelper = trackerNotificationHelper,
					viewModel = trackerSettingsViewModel,
					dozeRefreshKey = dozeRefreshKey,
					notificationRefreshKey = notificationRefreshKey,
					onTrackCategoriesClick = { router.showTrackerCategoriesConfigSheet() },
					onOpenNotificationsSettings = ::openTrackerNotificationsSettings,
					onOpenTrackerDebug = {
						startActivity(Intent(this, TrackerDebugActivity::class.java))
					},
					onRequestIgnoreDoze = ::startTrackerIgnoreDozeActivity,
					onOpenTrackerWarning = ::openTrackerWarning,
				)
			}
			SettingsDestination.NotificationSettings -> RenderComposeSection(title = getString(R.string.notifications)) {
				NotificationSettingsRoute(
					settings = kototoroAppSettings,
					onNotificationSoundClick = {
						ringtonePickContract.launch(kototoroAppSettings.notificationSound)
					},
				)
			}
			SettingsDestination.ServicesSettings -> RenderComposeSection(title = getString(R.string.services)) {
				ServicesSettingsRoute(
					settings = kototoroAppSettings,
					animeOfflineRepository = animeOfflineRepository,
					onAnimeOfflineUpdate = {
						org.skepsun.kototoro.tracking.animeoffline.work.AnimeOfflineUpdateWorker.enqueue(
							applicationContext,
							force = true,
						)
					},
					onSuggestionsClick = {
						openDestination(SettingsDestination.SuggestionsSettings, null, false)
					},
					onStatsClick = { router.openStatistic() },
					onDiscordSettingsClick = {
						openDestination(SettingsDestination.DiscordSettings, null, false)
					},
				)
			}
			SettingsDestination.DiscordSettings -> RenderComposeSection(title = getString(R.string.discord)) {
				DiscordSettingsRoute(
					settings = kototoroAppSettings,
					viewModel = discordSettingsViewModel,
					onTokenClick = ::openDiscordSignIn,
					onLogoutClick = ::logoutDiscord,
				)
			}
			SettingsDestination.ProxySettings -> RenderComposeSection(title = getString(R.string.proxy)) {
				ProxySettingsRoute(
					settings = kototoroAppSettings,
					testSummaryFlow = proxyTestSummaryFlow,
					isTestRunningFlow = proxyIsTestRunningFlow,
					onTestConnection = ::testProxyConnection,
				)
			}
			SettingsDestination.NavConfigSettings -> RenderComposeSection(
				title = getString(R.string.main_screen_sections),
			) {
				NavConfigRoute(
					viewModel = navConfigViewModel,
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.ChangelogSettings -> RenderComposeSection(title = getString(R.string.changelog)) {
				ChangelogRoute(
					viewModel = changelogViewModel,
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.AboutSettings -> RenderComposeSection(title = getString(R.string.about)) {
				AboutSettingsRoute(
					settings = kototoroAppSettings,
					viewModel = aboutSettingsViewModel,
					onChangelogClick = {
						openDestination(SettingsDestination.ChangelogSettings, null, false)
					},
					onLinkClick = { key -> openAboutLink(key) },
					onCrashLogsClick = {
						startActivity(org.skepsun.kototoro.settings.about.crashlog.CrashLogActivity.newIntent(this))
					},
				)
			}
			SettingsDestination.SourcesSettings -> RenderComposeSection(title = getString(R.string.remote_sources)) {
				SourcesSettingsRoute(
					settings = kototoroAppSettings,
					viewModel = sourcesSettingsViewModel,
					onSetupWizardClick = { router.showWelcomeSheet() },
				)
			}
			is SettingsDestination.UnifiedSources,
			is SettingsDestination.FragmentDestination -> Unit
		}
	}

	private fun renderMasterSettingsRoot() {
		val masterComposeView = masterContainerComposeView() ?: return
		masterComposeView.setContent {
			KototoroTheme {
				val enabledSourcesCount by rootSettingsViewModel.enabledSourcesCount.collectAsStateWithLifecycle()
				val searchQuery by viewModel.queryText.collectAsStateWithLifecycle()
				val searchResults by viewModel.content.collectAsStateWithLifecycle()
				SettingsRootScreen(
					sections = buildSettingsRootSections(
						context = this,
						enabledSourcesCount = enabledSourcesCount,
						totalSourcesCount = rootSettingsViewModel.totalSourcesCount,
						classLoader = classLoader,
						onOpenFragment = { fragmentClass ->
							openFragment(fragmentClass, null, true)
						},
						onOpenDestination = { composeDestination ->
							openDestination(composeDestination, null, true)
						},
					),
					title = getString(R.string.settings),
					subtitle = getString(R.string.app_version, org.skepsun.kototoro.BuildConfig.VERSION_NAME),
					searchQuery = searchQuery,
					searchResults = searchResults,
					onSearchQueryChange = viewModel::setSearchQuery,
					onSearchResultClick = { item -> navigateToPreference(item) },
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
	}

	private fun bindDataCleanupObservers() {
		if (isDataCleanupObserversBound) return
		isDataCleanupObserversBound = true
		dataCleanupSettingsViewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.root, null))
		dataCleanupSettingsViewModel.onActionDone.observeEvent(this, ReversibleActionObserver(viewBinding.root))
		dataCleanupSettingsViewModel.onChaptersCleanedUp.observeEvent(this, ::onDataCleanupChaptersCleanedUp)
		dataCleanupSettingsViewModel.onStorageChanged.observeEvent(this) {
			storageAndNetworkSettingsViewModel.refreshStorageUsage()
		}
		dataCleanupSettingsViewModel.onLocalContentCleanedUp.observeEvent(this, ::onLocalContentCleanedUp)
	}

	private fun onDataCleanupChaptersCleanedUp(result: Pair<Int, Long>) {
		val text = if (result.first == 0 && result.second == 0L) {
			getString(R.string.no_chapters_deleted)
		} else {
			getString(
				R.string.chapters_deleted_pattern,
				resources.getQuantityStringSafe(R.plurals.chapters, result.first, result.first),
				FileSize.BYTES.format(this, result.second),
			)
		}
		Snackbar.make(viewBinding.root, text, Snackbar.LENGTH_SHORT).show()
	}

	private fun confirmClearSearchHistory() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.clear_search_history)
			.setMessage(R.string.text_clear_search_history_prompt)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.clear) { _, _ ->
				dataCleanupSettingsViewModel.clearSearchHistory()
			}
			.show()
	}

	private fun confirmClearCookies() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.clear_cookies)
			.setMessage(R.string.text_clear_cookies_prompt)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.clear) { _, _ ->
				dataCleanupSettingsViewModel.clearCookies()
			}
			.show()
	}

	private fun confirmCleanupChapters() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.delete_read_chapters)
			.setMessage(R.string.delete_read_chapters_prompt)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.delete) { _, _ ->
				dataCleanupSettingsViewModel.cleanupChapters()
			}
			.show()
	}

	private fun onLocalContentCleanedUp(result: DataCleanupSettingsViewModel.LocalContentCleanupResult) {
		val labelRes = when (result.kind) {
			org.skepsun.kototoro.local.data.StorageContentKind.MANGA -> R.string.local_manga_storage
			org.skepsun.kototoro.local.data.StorageContentKind.NOVEL -> R.string.local_novel_storage
			org.skepsun.kototoro.local.data.StorageContentKind.VIDEO -> R.string.local_video_storage
		}
		val text = if (result.removedCount == 0 && result.bytesFreed == 0L) {
			getString(R.string.no_local_content_deleted)
		} else {
			getString(
				R.string.local_content_deleted_pattern,
				getString(labelRes),
				resources.getQuantityStringSafe(R.plurals.items, result.removedCount, result.removedCount),
				FileSize.BYTES.format(this, result.bytesFreed),
			)
		}
		Snackbar.make(viewBinding.root, text, Snackbar.LENGTH_SHORT).show()
	}

	private fun confirmClearLocalManga() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.clear_local_manga_storage)
			.setMessage(R.string.clear_local_manga_storage_prompt)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.clear) { _, _ ->
				dataCleanupSettingsViewModel.clearLocalMangaContent()
			}
			.show()
	}

	private fun confirmClearLocalNovels() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.clear_local_novel_storage)
			.setMessage(R.string.clear_local_novel_storage_prompt)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.clear) { _, _ ->
				dataCleanupSettingsViewModel.clearLocalNovelContent()
			}
			.show()
	}

	private fun confirmClearLocalVideos() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.clear_local_video_storage)
			.setMessage(R.string.clear_local_video_storage_prompt)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.clear) { _, _ ->
				dataCleanupSettingsViewModel.clearLocalVideoContent()
			}
			.show()
	}

	private fun fetchAndPickTranslationApiModel() {
		translationApiFetchModelsJob?.cancel()
		translationApiFetchModelsJob = lifecycleScope.launch {
			try {
				val endpoint = kototoroAppSettings.readerTranslationApiEndpoint.trim()
				if (endpoint.isBlank()) {
					Toast.makeText(this@SettingsActivity, R.string.reader_translation_api_endpoint_missing, Toast.LENGTH_SHORT).show()
					return@launch
				}
				val modelsUrl = TranslationApiSettingsSupport.buildModelsUrl(endpoint)
				val key = kototoroAppSettings.readerTranslationApiKey.trim()
				val models = withContext(Dispatchers.IO) {
					val requestBuilder = Request.Builder().get().url(modelsUrl)
					if (key.isNotBlank()) {
						requestBuilder.header("Authorization", "Bearer $key")
						requestBuilder.header("X-API-Key", key)
					}
					okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
						if (!response.isSuccessful) return@withContext emptyList<String>()
						TranslationApiSettingsSupport.parseModelIds(response.body?.string().orEmpty())
					}
				}
				if (models.isEmpty()) {
					Toast.makeText(this@SettingsActivity, R.string.reader_translation_api_models_fetch_failed, Toast.LENGTH_SHORT).show()
					return@launch
				}
				showTranslationApiModelPicker(models)
			} catch (_: Throwable) {
				Toast.makeText(this@SettingsActivity, R.string.reader_translation_api_models_fetch_failed, Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun showTranslationApiModelPicker(models: List<String>) {
		val current = kototoroAppSettings.readerTranslationApiModel.trim()
		val selected = models.indexOf(current).coerceAtLeast(0)
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.reader_translation_api_models_pick_title)
			.setSingleChoiceItems(models.toTypedArray(), selected) { dialog, which ->
				val chosen = models.getOrNull(which).orEmpty()
				if (chosen.isNotBlank()) {
					PreferenceManager.getDefaultSharedPreferences(this).edit {
						putString(AppSettings.KEY_READER_TRANSLATION_API_MODEL, chosen)
					}
				}
				dialog.dismiss()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun fetchAndPickTranslationE2EApiModel() {
		if (translationE2EApiFetchModelsJob?.isActive == true) return

		val endpoint = kototoroAppSettings.readerE2eApiEndpoint
		val apiKey = kototoroAppSettings.readerE2eApiKey
		if (endpoint.isEmpty() || apiKey.isEmpty()) {
			Toast.makeText(this, R.string.reader_translation_api_endpoint_missing, Toast.LENGTH_SHORT).show()
			return
		}

		val request = Request.Builder()
			.url(endpoint.removeSuffix("/chat/completions").removeSuffix("/") + "/models")
			.get()
			.header("Authorization", "Bearer $apiKey")
			.build()

		translationE2EApiFetchModelsJob = lifecycleScope.launch(Dispatchers.IO) {
			try {
				val response = okHttpClient.newCall(request).execute()
				val bodyStr = response.body?.string()
				if (!response.isSuccessful || bodyStr == null) {
					withContext(Dispatchers.Main) {
						Toast.makeText(this@SettingsActivity, R.string.reader_translation_api_models_fetch_failed, Toast.LENGTH_SHORT).show()
					}
					return@launch
				}
				val models = TranslationApiSettingsSupport.parseModelIds(bodyStr)
				withContext(Dispatchers.Main) {
					if (models.isEmpty()) {
						Toast.makeText(this@SettingsActivity, R.string.reader_translation_api_models_fetch_failed, Toast.LENGTH_SHORT).show()
						return@withContext
					}
					MaterialAlertDialogBuilder(this@SettingsActivity)
						.setTitle(R.string.reader_translation_api_models_fetch)
						.setItems(models.toTypedArray()) { _, which ->
							kototoroAppSettings.prefs.edit()
								.putString(AppSettings.KEY_READER_E2E_API_MODEL, models[which])
								.apply()
						}
						.setNegativeButton(android.R.string.cancel, null)
						.show()
				}
			} catch (_: Exception) {
				withContext(Dispatchers.Main) {
					Toast.makeText(this@SettingsActivity, R.string.reader_translation_api_models_fetch_failed, Toast.LENGTH_SHORT).show()
				}
			}
		}
	}

	private fun handleComposeNavigateUp() {
		if (composeDestination == null) {
			return
		}
		if (composeDestination == SettingsDestination.Root) {
			closeComposeDestination(restorePreviousFragment = false)
			dispatchNavigateUp()
			return
		}
		val shouldRestore = shouldRestoreFragmentOnComposeExit && supportFragmentManager.backStackEntryCount > 0
		closeComposeDestination(restorePreviousFragment = false)
		if (shouldRestore) {
			supportFragmentManager.popBackStack()
		} else if (composeNavigationStack.isNotEmpty()) {
			val previousDestination = composeNavigationStack.removeLast()
			openComposeDestination(
				destination = previousDestination,
				shouldRestoreFragment = false,
				pushCurrentToStack = false,
			)
		} else if (!isMasterDetails) {
			dispatchNavigateUp()
		}
	}

	private fun closeComposeDestination(restorePreviousFragment: Boolean) {
		val destination = composeDestination ?: return
		if (destination == SettingsDestination.TtsSettings) {
			ttsSettingsCoordinator?.stop()
			ttsSettingsCoordinator = null
		}
		viewBinding.containerCompose.isVisible = false
		setLegacyTopBarVisible(true)
		setSectionToolbarActions(null)
		composeDestination = null
		shouldRestoreFragmentOnComposeExit = false
		composeBackCallback.isEnabled = false
		if (restorePreviousFragment && supportFragmentManager.backStackEntryCount > 0) {
			supportFragmentManager.popBackStack()
		}
	}

	private fun restoreComposeDestinationIfNeeded() {
		if (composeDestination != null || supportFragmentManager.isStateSaved) {
			return
		}
		if (supportFragmentManager.backStackEntryCount != 0) {
			return
		}
		if (supportFragmentManager.findFragmentById(R.id.container) != null) {
			return
		}
		val destination = composeDestinationToRestore ?: return
		composeDestinationToRestore = null
		openComposeDestination(destination, shouldRestoreFragment = false)
	}

	private fun clearToolbarMenu() {
		(viewBinding.toolbarDetail ?: viewBinding.toolbar).menu.clear()
	}

	private fun masterContainerComposeView(): ComposeView? {
		return findViewById(R.id.container_master) as? ComposeView
	}

	private fun refreshSuggestionsTags() {
		suggestionsExcludeTagsFlow.value =
			kototoroAppSettings.prefs.getString(AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS, "") ?: ""
		suggestionsPreferredTagsFlow.value =
			kototoroAppSettings.prefs.getString(AppSettings.KEY_SUGGESTIONS_PREFERRED_TAGS, "") ?: ""
	}

	private fun clearSuperResolutionCache() {
		lifecycleScope.launch(Dispatchers.IO) {
			val srCacheDir = java.io.File(cacheDir, "sr_cache")
			var deletedCount = 0
			if (srCacheDir.exists() && srCacheDir.isDirectory) {
				srCacheDir.listFiles()?.forEach { file ->
					if (file.delete()) {
						deletedCount++
					}
				}
			}
			withContext(Dispatchers.Main) {
				Toast.makeText(
					this@SettingsActivity,
					getString(R.string.reader_super_resolution_cache_cleared) + " ($deletedCount files)",
					Toast.LENGTH_SHORT,
				).show()
			}
		}
	}

	private fun showVideoSuperResolutionAdvancedSheet() {
		VideoSuperResolutionAdvancedSheet().show(
			supportFragmentManager,
			"VideoSuperResolutionAdvancedSheet",
		)
	}

	private fun openDiscordSignIn() {
		startActivity(Intent(this, DiscordAuthActivity::class.java))
	}

	private fun logoutDiscord() {
		kototoroAppSettings.discordToken = null
		val webStorage = WebStorage.getInstance()
		runCatching { webStorage.deleteOrigin(DISCORD_ORIGIN) }
		runCatching { webStorage.deleteOrigin(DISCORD_WWW_ORIGIN) }

		val cookieManager = CookieManager.getInstance()
		cookieManager.removeSessionCookies(null)
		cookieManager.removeAllCookies(null)
		cookieManager.flush()
	}

	private fun onDownloadsPagesDirectoryPicked(uri: Uri) {
		storageManager.takePermissions(uri)
		val doc = DocumentFile.fromTreeUri(this, uri)?.takeIf { it.canWrite() }
		kototoroAppSettings.setPagesSaveDir(doc?.uri)
		downloadsStorageTick.update { it + 1 }
	}

	private fun openTrackerNotificationsSettings(onUnsupported: () -> Unit) {
		when {
			android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O -> {
				val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
					.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
				if (!startSettingsActivitySafe(intent)) {
					onUnsupported()
				}
			}
			!trackerNotificationHelper.getAreNotificationsEnabled() -> {
				val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
					.setData(android.net.Uri.fromParts("package", packageName, null))
				if (!startSettingsActivitySafe(intent)) {
					onUnsupported()
				}
			}
			else -> {
				openDestination(SettingsDestination.NotificationSettings, null, false)
			}
		}
	}

	private fun openTrackerWarning(onUnsupported: () -> Unit) {
		val intent = Intent(Intent.ACTION_VIEW, "https://dontkillmyapp.com/".toUri())
		if (!startSettingsActivitySafe(intent)) {
			onUnsupported()
		}
	}

	private fun startTrackerIgnoreDozeActivity(): Boolean {
		return startIgnoreDozeActivity(this, ignoreTrackerDozeLauncher)
	}

	private fun updateDownloadsConstraints() {
		lifecycleScope.launch {
			runCatching {
				when (kototoroAppSettings.allowDownloadOnMeteredNetwork) {
					org.skepsun.kototoro.core.prefs.TriStateOption.ENABLED -> downloadsScheduler.updateConstraints(true)
					org.skepsun.kototoro.core.prefs.TriStateOption.ASK -> Unit
					org.skepsun.kototoro.core.prefs.TriStateOption.DISABLED -> downloadsScheduler.updateConstraints(false)
				}
			}.onFailure {
				it.printStackTrace()
			}
		}
	}

	private fun startDownloadsIgnoreDozeActivity(): Boolean {
		return startIgnoreDozeActivity(this, ignoreDownloadsDozeLauncher)
	}

	private fun testProxyConnection() {
		proxyTestJob?.cancel()
		proxyTestJob = lifecycleScope.launch {
			proxyTestSummaryFlow.value = getString(R.string.loading_)
			proxyIsTestRunningFlow.value = true
			try {
				withContext(Dispatchers.Default) {
					val request = Request.Builder()
						.get()
						.url("http://neverssl.com")
						.build()
					okHttpClient.newCall(request).await().use { response ->
						check(response.isSuccessful) { response.message }
					}
				}
				showProxyTestResult(null)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				e.printStackTraceDebug()
				showProxyTestResult(e)
			} finally {
				proxyIsTestRunningFlow.value = false
				proxyTestSummaryFlow.value = null
			}
		}
	}

	private fun showProxyTestResult(error: Throwable?) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.proxy)
			.setMessage(error?.getDisplayMessage(resources) ?: getString(R.string.connection_ok))
			.setPositiveButton(android.R.string.ok, null)
			.setCancelable(true)
			.show()
	}

	private fun onAboutUpdateAvailable(version: AppVersion?) {
		if (version == null) {
			Toast.makeText(this, R.string.no_update_available, Toast.LENGTH_SHORT).show()
		} else {
			startActivity(Intent(this, AppUpdateActivity::class.java))
		}
	}

	private fun openAboutLink(key: String): Boolean {
		val urlRes = when (key) {
			org.skepsun.kototoro.core.prefs.AppSettings.KEY_LINK_WEBLATE -> R.string.url_weblate
			org.skepsun.kototoro.core.prefs.AppSettings.KEY_LINK_GITHUB -> R.string.url_github
			org.skepsun.kototoro.core.prefs.AppSettings.KEY_LINK_DONATE -> R.string.url_donate
			org.skepsun.kototoro.core.prefs.AppSettings.KEY_LINK_MANUAL -> R.string.url_user_manual
			org.skepsun.kototoro.core.prefs.AppSettings.KEY_LINK_DISCORD -> R.string.url_discord
			else -> return false
		}
		val title = when (key) {
			org.skepsun.kototoro.core.prefs.AppSettings.KEY_LINK_WEBLATE -> getString(R.string.about_app_translation_summary)
			org.skepsun.kototoro.core.prefs.AppSettings.KEY_LINK_GITHUB -> getString(R.string.source_code)
			org.skepsun.kototoro.core.prefs.AppSettings.KEY_LINK_DONATE -> getString(R.string.about_donate)
			org.skepsun.kototoro.core.prefs.AppSettings.KEY_LINK_MANUAL -> getString(R.string.user_manual)
			org.skepsun.kototoro.core.prefs.AppSettings.KEY_LINK_DISCORD -> getString(R.string.about_discord)
			else -> null
		}
		return if (router.openExternalBrowser(getString(urlRes), title)) {
			true
		} else {
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
			false
		}
	}

	private fun startSettingsActivitySafe(intent: Intent): Boolean {
		return runCatching {
			startActivity(intent)
		}.isSuccess
	}

	companion object {

		private const val HOST_ABOUT = "about"
		private const val DISCORD_ORIGIN = "https://discord.com"
		private const val DISCORD_WWW_ORIGIN = "https://www.discord.com"
		const val ARG_PREF_KEY = "pref_key"
		private const val COMPOSE_HIDE_BACKSTACK_NAME = "settings_compose_hide"
		private const val STATE_COMPOSE_DESTINATION = "compose_destination"
		private const val STATE_COMPOSE_RESTORE_FRAGMENT = "compose_restore_fragment"
		private const val STATE_PENDING_RESTORE_ROOT = "pending_restore_root"
		private const val COMPOSE_DESTINATION_ROOT = "root"
		private const val COMPOSE_DESTINATION_APPEARANCE_SETTINGS = "appearance_settings"
		private const val COMPOSE_DESTINATION_USERS_SETTINGS = "users_settings"
		private const val COMPOSE_DESTINATION_AI_SETTINGS = "ai_settings"
		private const val COMPOSE_DESTINATION_OCR_MODELS_SETTINGS = "ocr_models_settings"
		private const val COMPOSE_DESTINATION_AI_IMAGE_ENHANCEMENT_SETTINGS = "ai_image_enhancement_settings"
		private const val COMPOSE_DESTINATION_AI_VIDEO_ENHANCEMENT_SETTINGS = "ai_video_enhancement_settings"
		private const val COMPOSE_DESTINATION_TTS_SETTINGS = "tts_settings"
		private const val COMPOSE_DESTINATION_PLAYBACK_SETTINGS = "playback_settings"
		private const val COMPOSE_DESTINATION_READER_SETTINGS = "reader_settings"
		private const val COMPOSE_DESTINATION_SOURCES_SETTINGS = "sources_settings"
		private const val COMPOSE_DESTINATION_SUGGESTIONS_SETTINGS = "suggestions_settings"
		private const val COMPOSE_DESTINATION_SYNC_SETTINGS = "sync_settings"
		private const val COMPOSE_DESTINATION_BACKUPS_SETTINGS = "backups_settings"
		private const val COMPOSE_DESTINATION_TRANSLATION_SETTINGS = "translation_settings"
		private const val COMPOSE_DESTINATION_TRANSLATION_API_SETTINGS = "translation_api_settings"
		private const val COMPOSE_DESTINATION_TRANSLATION_E2E_API_SETTINGS = "translation_e2e_api_settings"
		private const val COMPOSE_DESTINATION_STORAGE_AND_NETWORK_SETTINGS = "storage_and_network_settings"
		private const val COMPOSE_DESTINATION_DATA_CLEANUP_SETTINGS = "data_cleanup_settings"
		private const val COMPOSE_DESTINATION_DOWNLOADS_SETTINGS = "downloads_settings"
		private const val COMPOSE_DESTINATION_TRACKER_SETTINGS = "tracker_settings"
		private const val COMPOSE_DESTINATION_NOTIFICATION_SETTINGS = "notification_settings"
		private const val COMPOSE_DESTINATION_SERVICES_SETTINGS = "services_settings"
		private const val COMPOSE_DESTINATION_DISCORD_SETTINGS = "discord_settings"
		private const val COMPOSE_DESTINATION_PROXY_SETTINGS = "proxy_settings"
		private const val COMPOSE_DESTINATION_NAV_CONFIG_SETTINGS = "nav_config_settings"
		private const val COMPOSE_DESTINATION_CHANGELOG_SETTINGS = "changelog_settings"
		private const val COMPOSE_DESTINATION_ABOUT_SETTINGS = "about_settings"
	}

	private fun Bundle.toComposeDestination(): SettingsDestination? {
		return when (getString(STATE_COMPOSE_DESTINATION)) {
			COMPOSE_DESTINATION_ROOT -> SettingsDestination.Root
			COMPOSE_DESTINATION_APPEARANCE_SETTINGS -> SettingsDestination.AppearanceSettings
			COMPOSE_DESTINATION_USERS_SETTINGS -> SettingsDestination.UsersSettings
			COMPOSE_DESTINATION_AI_SETTINGS -> SettingsDestination.AISettings
			COMPOSE_DESTINATION_OCR_MODELS_SETTINGS -> SettingsDestination.OcrModelsSettings
			COMPOSE_DESTINATION_AI_IMAGE_ENHANCEMENT_SETTINGS -> SettingsDestination.AiImageEnhancementSettings
			COMPOSE_DESTINATION_AI_VIDEO_ENHANCEMENT_SETTINGS -> SettingsDestination.AiVideoEnhancementSettings
			COMPOSE_DESTINATION_TTS_SETTINGS -> SettingsDestination.TtsSettings
			COMPOSE_DESTINATION_PLAYBACK_SETTINGS -> SettingsDestination.PlaybackSettings
			COMPOSE_DESTINATION_READER_SETTINGS -> SettingsDestination.ReaderSettings
			COMPOSE_DESTINATION_SOURCES_SETTINGS -> SettingsDestination.SourcesSettings
			COMPOSE_DESTINATION_SUGGESTIONS_SETTINGS -> SettingsDestination.SuggestionsSettings
			COMPOSE_DESTINATION_SYNC_SETTINGS -> SettingsDestination.SyncSettings
			COMPOSE_DESTINATION_BACKUPS_SETTINGS -> SettingsDestination.BackupsSettings
			COMPOSE_DESTINATION_TRANSLATION_SETTINGS -> SettingsDestination.TranslationSettings
			COMPOSE_DESTINATION_TRANSLATION_API_SETTINGS -> SettingsDestination.TranslationApiSettings
			COMPOSE_DESTINATION_TRANSLATION_E2E_API_SETTINGS -> SettingsDestination.TranslationE2EApiSettings
			COMPOSE_DESTINATION_STORAGE_AND_NETWORK_SETTINGS -> SettingsDestination.StorageAndNetworkSettings
			COMPOSE_DESTINATION_DATA_CLEANUP_SETTINGS -> SettingsDestination.DataCleanupSettings
			COMPOSE_DESTINATION_DOWNLOADS_SETTINGS -> SettingsDestination.DownloadsSettings
			COMPOSE_DESTINATION_TRACKER_SETTINGS -> SettingsDestination.TrackerSettings
			COMPOSE_DESTINATION_NOTIFICATION_SETTINGS -> SettingsDestination.NotificationSettings
			COMPOSE_DESTINATION_SERVICES_SETTINGS -> SettingsDestination.ServicesSettings
			COMPOSE_DESTINATION_DISCORD_SETTINGS -> SettingsDestination.DiscordSettings
			COMPOSE_DESTINATION_PROXY_SETTINGS -> SettingsDestination.ProxySettings
			COMPOSE_DESTINATION_NAV_CONFIG_SETTINGS -> SettingsDestination.NavConfigSettings
			COMPOSE_DESTINATION_CHANGELOG_SETTINGS -> SettingsDestination.ChangelogSettings
			COMPOSE_DESTINATION_ABOUT_SETTINGS -> SettingsDestination.AboutSettings
			else -> null
		}
	}

	private fun observeFoldableState() {
		val foldableState = FoldableUtils.observeFoldableState(this, this)
		
		lifecycleScope.launch {
			foldableState.collect { unfolded ->
				if (unfolded != isFoldUnfolded) {
					isFoldUnfolded = unfolded
					adjustLayoutForFoldableState()
				}
			}
		}
	}

    private fun adjustLayoutForFoldableState() {
        // 设置页不改变屏幕方向，仅保持默认方向
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // 仅在折叠屏展开且窗口满足双栏宽度时重建，避免分屏窄窗口反复重建
        if (isFoldUnfolded && viewBinding.containerMaster == null && FoldableUtils.shouldUseTwoPaneLayout(this)) {
            recreate()
            return
        }

        viewBinding.root.requestLayout()
    }
}
