package org.skepsun.kototoro.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.coordinatorlayout.widget.CoordinatorLayout
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
import org.skepsun.kototoro.backups.ui.backup.AniyomiBackupExportService
import org.skepsun.kototoro.backups.ui.backup.BackupService
import org.skepsun.kototoro.backups.ui.backup.MihonBackupExportService
import org.skepsun.kototoro.backups.ui.backup.UsagiBackupExportService
import org.skepsun.kototoro.backups.ui.periodical.PeriodicalBackupSettingsViewModel
import org.skepsun.kototoro.backups.ui.restore.ExternalBackupImportService
import org.skepsun.kototoro.core.github.AppVersion
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.applyHorizontalRouteCloseTransition
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.os.AppShortcutManager
import org.skepsun.kototoro.core.os.OpenDocumentTreeHelper
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseActivity
import org.skepsun.kototoro.core.ui.compose.DynamicArtworkBackdrop
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.history.data.HistoryRepository
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
import org.skepsun.kototoro.favourites.ui.migration.compose.EntityOrganizePageIntroCard
import org.skepsun.kototoro.favourites.ui.migration.compose.SourceMigrationPanel
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.main.ui.compose.encodeEntityOrganizeSelection
import org.skepsun.kototoro.main.ui.compose.parseEntityOrganizeSelection
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.scrobbling.common.ui.ScrobblerAuthHelper
import org.skepsun.kototoro.settings.about.AboutSettingsRoute
import org.skepsun.kototoro.settings.about.AboutSettingsViewModel
import org.skepsun.kototoro.settings.about.AppUpdateActivity
import org.skepsun.kototoro.settings.about.changelog.ChangelogRoute
import org.skepsun.kototoro.settings.about.changelog.ChangelogViewModel
import org.skepsun.kototoro.settings.compose.SettingsAdaptiveShell
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import org.skepsun.kototoro.settings.compose.SettingsRootScreen
import org.skepsun.kototoro.settings.compose.SettingsSectionScaffold
import org.skepsun.kototoro.settings.compose.SettingsSearchTopBarAction
import org.skepsun.kototoro.settings.compose.SettingsSearchTopAppBar
import org.skepsun.kototoro.settings.compose.SettingsTopBarScaffold
import org.skepsun.kototoro.settings.compose.SpacesSettingsRoute
import org.skepsun.kototoro.settings.compose.buildSettingsRootSections
import org.skepsun.kototoro.settings.nav.NavConfigRoute
import org.skepsun.kototoro.settings.nav.NavConfigViewModel
import org.skepsun.kototoro.settings.userdata.storage.DataCleanupSettingsRoute
import org.skepsun.kototoro.settings.userdata.storage.DataCleanupSettingsViewModel
import org.skepsun.kototoro.settings.discord.DiscordSettingsRoute
import org.skepsun.kototoro.settings.discord.DiscordSettingsViewModel
import org.skepsun.kototoro.settings.protect.ProtectSetupActivity
import org.skepsun.kototoro.settings.search.SettingsItem
import org.skepsun.kototoro.settings.search.SettingsSearchMenuProvider
import org.skepsun.kototoro.settings.search.SettingsSearchViewModel
import org.skepsun.kototoro.settings.support.TranslationApiSettingsSupport
import org.skepsun.kototoro.reader.translate.domain.TranslationApiProviderCatalog
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.settings.sources.SourceComposeSettingsFragment
import org.skepsun.kototoro.settings.sources.SourceSettingsRoute
import org.skepsun.kototoro.settings.sources.SourceSettingsFragment
import org.skepsun.kototoro.settings.sources.SourcesSettingsRoute
import org.skepsun.kototoro.settings.sources.SourcesSettingsViewModel
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourcesToolbarActions
import org.skepsun.kototoro.settings.sources.unified.UnifiedToolbarFilterPanel
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourcesRoute
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourcesViewModel
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
import org.skepsun.kototoro.core.parser.EmptyContentRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.JsContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserRepository
import org.skepsun.kototoro.core.parser.legado.LegadoRepository
import org.skepsun.kototoro.core.parser.tvbox.TVBoxRepository
import org.skepsun.kototoro.parsers.model.ContentSource
import kotlin.coroutines.cancellation.CancellationException

@AndroidEntryPoint
class SettingsActivity :
	BaseActivity<SettingsActivityLayoutBinding>(),
	PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

	private val initialEntityOrganizeSelection: Set<Long> by lazy(LazyThreadSafetyMode.NONE) {
		parseEntityOrganizeSelection(intent?.getStringExtra(EXTRA_ENTITY_ORGANIZE_SELECTION).orEmpty())
	}

	@Inject
	lateinit var activityRecreationHandle: ActivityRecreationHandle

	@Inject
	lateinit var appShortcutManager: AppShortcutManager

	@Inject
	lateinit var sourcePresetsRepository: SourcePresetsRepository

	@Inject
	lateinit var storageManager: LocalStorageManager

	@Inject
	lateinit var historyRepository: HistoryRepository

	@Inject
	lateinit var downloadsScheduler: DownloadWorker.Scheduler

	@Inject
	lateinit var animeOfflineRepository: AnimeOfflineRepository

	@Inject
	lateinit var mangaRepositoryFactory: ContentRepository.Factory

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
	lateinit var googleDriveSyncSettings: GoogleDriveSyncSettings

	@Inject
	lateinit var onnxModelManager: OnnxModelManager

	private val isMasterDetails
		get() = FoldableUtils.shouldUseTabletLayout(this, kototoroAppSettings) && if (kototoroAppSettings.tabletUiMode == org.skepsun.kototoro.core.prefs.TabletUiMode.STRICT) {
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
	private val unifiedSourcesViewModel: UnifiedSourcesViewModel by viewModels()
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
	private var hasAppliedCloseRouteTransition = false
	private var pendingExternalBackupApp: ExternalBackupApp? = null
	private var pendingUnifiedSourcesFileImportKind: UnifiedSourceKind? = null
	private var unifiedSourcesSearchActive by mutableStateOf(false)
	private var unifiedSourcesActivePanel by mutableStateOf<UnifiedToolbarFilterPanel?>(null)
	private var isLegacyTopBarVisible = false

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

	private val mihonBackupExportCall = registerForActivityResult(
		ActivityResultContracts.CreateDocument("application/octet-stream"),
	) { uri ->
		if (uri != null && !MihonBackupExportService.start(this, uri)) {
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}

	private val aniyomiBackupExportCall = registerForActivityResult(
		ActivityResultContracts.CreateDocument("application/octet-stream"),
	) { uri ->
		if (uri != null && !AniyomiBackupExportService.start(this, uri)) {
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}

	private val usagiBackupExportCall = registerForActivityResult(
		ActivityResultContracts.CreateDocument("application/zip"),
	) { uri ->
		if (uri != null && !UsagiBackupExportService.start(this, uri)) {
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

	private val openUnifiedSourcesRepositoryFile = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri == null) return@registerForActivityResult
		val kind = pendingUnifiedSourcesFileImportKind ?: return@registerForActivityResult
		pendingUnifiedSourcesFileImportKind = null
		persistReadPermission(uri)
		unifiedSourcesViewModel.addRepositoryFromFile(kind, uri)
	}

	private val openUnifiedSourcesLocalJar = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri == null) return@registerForActivityResult
		persistReadPermission(uri)
		unifiedSourcesViewModel.importLocalJar(uri)
	}

	private val unifiedSourcesInstallLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		unifiedSourcesViewModel.onInstallActivityResult()
	}

	private val unifiedSourcesUninstallLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		unifiedSourcesViewModel.onUninstallActivityResult()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(SettingsActivityLayoutBinding.inflate(layoutInflater))
		setLegacyTopBarVisible(false)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		addMenuProvider(
			SettingsSearchMenuProvider(viewModel) {
				isLegacyTopBarVisible
			},
		)
		onBackPressedDispatcher.addCallback(this, composeBackCallback)
		val fm = supportFragmentManager
		val currentFragment = fm.findFragmentById(R.id.container)
		val restoredDestination = savedInstanceState?.toComposeDestination()
		val initialComposeDestination = if (currentFragment == null) {
			restoredDestination ?: resolveDefaultComposeDestination(intent)
		} else {
			restoredDestination
		}
		composeDestination = initialComposeDestination
		composeDestinationToRestore = savedInstanceState
			?.getBoolean(STATE_PENDING_RESTORE_ROOT)
			?.takeIf { it }
			?.let { SettingsDestination.Root }
		viewBinding.containerCompose.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		viewBinding.containerCompose.setContent {
			val lastReadContent by historyRepository.observeLast().collectAsStateWithLifecycle(initialValue = null)
			KototoroTheme {
				DynamicArtworkBackdrop(content = lastReadContent) {
					SettingsAdaptiveShell(
						isTwoPane = isMasterDetails,
						destination = composeDestination,
						destinationKey = ::composeDestinationStateKey,
						modifier = Modifier.fillMaxSize(),
						rootContent = { modifier -> RenderSettingsRootContent(modifier) },
						destinationContent = { destination -> RenderComposeDestination(destination) },
					)
				}
			}
		}
		masterContainerComposeView()?.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		supportFragmentManager.addOnBackStackChangedListener {
			restoreComposeDestinationIfNeeded()
		}
		if (currentFragment == null) {
			if (initialComposeDestination != null) {
				openComposeDestination(
					initialComposeDestination,
					shouldRestoreFragment = savedInstanceState?.getBoolean(STATE_COMPOSE_RESTORE_FRAGMENT) == true,
				)
			} else {
				openDefaultDestination()
			}
		}
		viewModel.onNavigateToPreference.observeEvent(this, ::navigateToPreference)
		aboutSettingsViewModel.onUpdateAvailable.observeEvent(this, ::onAboutUpdateAvailable)

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
		val bars = insets.getInsets(
			WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
		)
		val isTablet = isMasterDetails
		viewBinding.appbar.updatePaddingRelative(
			start = bars.start(v),
			top = bars.top,
			end = if (isTablet) 0 else bars.end(v),
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

	override fun finish() {
		super.finish()
		applyCloseRouteTransitionIfNeeded()
	}

	override fun finishAfterTransition() {
		super.finishAfterTransition()
		applyCloseRouteTransitionIfNeeded()
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
				SettingsDestination.SpacesSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_SPACES_SETTINGS)
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
				SettingsDestination.EntityOrganizeSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_ENTITY_ORGANIZE_SETTINGS)
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
				SettingsDestination.CacheLimitsSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_CACHE_LIMITS_SETTINGS)
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
				is SettingsDestination.SourceSettings -> {
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_SOURCE_SETTINGS)
					outState.putString(STATE_SOURCE_SETTINGS_SOURCE, destination.sourceName)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
				}
				is SettingsDestination.UnifiedSources -> {
					val unifiedDestination = composeDestination as? SettingsDestination.UnifiedSources ?: return
					outState.putString(STATE_COMPOSE_DESTINATION, COMPOSE_DESTINATION_UNIFIED_SOURCES)
					outState.putBoolean(STATE_COMPOSE_RESTORE_FRAGMENT, shouldRestoreFragmentOnComposeExit)
					outState.putString(
						STATE_UNIFIED_SOURCES_KIND,
						unifiedDestination.initialRepositoryKind?.name,
					)
					outState.putString(
						STATE_UNIFIED_SOURCES_URL,
						unifiedDestination.initialRepositoryUrl,
					)
				}
				null -> Unit
			}
		outState.putBoolean(
			STATE_PENDING_RESTORE_ROOT,
			composeDestinationToRestore == SettingsDestination.Root,
		)
	}

	fun setSectionTitle(title: CharSequence?) {
		setTitle(title ?: getString(R.string.settings))
	}

	private fun updateUnifiedSourcesSearchActive(active: Boolean) {
		unifiedSourcesSearchActive = active
	}

	private fun setLegacyTopBarVisible(isVisible: Boolean) {
		val showLegacyTopBar = isVisible
		isLegacyTopBarVisible = showLegacyTopBar
		viewBinding.legacyTopBarHost.isVisible = showLegacyTopBar
		updateSinglePaneScrollBehavior(showLegacyTopBar)
		invalidateOptionsMenu()
		viewBinding.root.requestLayout()
	}

	private fun showLegacyFragmentContainer() {
		viewBinding.containerCompose.isVisible = false
		findViewById<View>(R.id.container)?.isVisible = true
		findViewById<View>(R.id.container_search)?.isVisible = false
		setLegacyTopBarVisible(true)
	}

	private fun renderComposeContent(
		showLegacyTopBar: Boolean,
		destination: SettingsDestination,
	) {
		setLegacyTopBarVisible(showLegacyTopBar)
	}

	@Composable
	private fun RenderComposeSection(
		title: String,
		actions: (@Composable BoxScope.() -> Unit)? = null,
		content: @Composable () -> Unit,
	) {
		SettingsSectionScaffold(
			title = title,
			onNavigateUp = if (isMasterDetails) null else ::handleComposeNavigateUp,
			showTopBar = true,
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
		showLegacyFragmentContainer()
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
			SettingsDestination.SpacesSettings,
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
			SettingsDestination.EntityOrganizeSettings,
			SettingsDestination.TranslationSettings,
			SettingsDestination.TranslationApiSettings,
			SettingsDestination.TranslationE2EApiSettings,
			SettingsDestination.StorageAndNetworkSettings,
			SettingsDestination.CacheLimitsSettings,
			SettingsDestination.DataCleanupSettings,
			SettingsDestination.DownloadsSettings,
			SettingsDestination.TrackerSettings,
			SettingsDestination.NotificationSettings,
			SettingsDestination.ServicesSettings,
			SettingsDestination.DiscordSettings,
			SettingsDestination.ProxySettings,
			SettingsDestination.NavConfigSettings,
			SettingsDestination.ChangelogSettings,
			SettingsDestination.AboutSettings,
			is SettingsDestination.SourceSettings,
			is SettingsDestination.UnifiedSources -> openComposeDestination(
				destination,
				shouldRestoreFragment = shouldRestoreFragmentForNextDestination(isFromRoot),
			)
		}
	}

	private fun openDefaultDestination() {
		resolveDefaultComposeDestination(intent)?.let { destination ->
			openComposeDestination(destination, shouldRestoreFragment = false)
			return
		}
		val fragment = when (intent?.action) {
			AppRouter.ACTION_SOURCES -> null
			AppRouter.ACTION_SOURCE -> resolveSingleSourceSettingsFragment(
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
		showLegacyFragmentContainer()
		supportFragmentManager.commit {
			setReorderingAllowed(true)
			replace(R.id.container, fragment)
		}
	}

	private fun resolveDefaultComposeDestination(intent: Intent?): SettingsDestination? {
		return resolveInitialUnifiedSourcesDestination(intent) ?: when (intent?.action) {
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
			AppRouter.ACTION_ENTITY_ORGANIZE -> SettingsDestination.EntityOrganizeSettings
			AppRouter.ACTION_MANAGE_DOWNLOADS -> SettingsDestination.DownloadsSettings
			AppRouter.ACTION_MANAGE_SOURCES -> null
			AppRouter.ACTION_SOURCE -> intent.getStringExtra(AppRouter.KEY_SOURCE)
				?.takeIf { it.isNotBlank() }
				?.let(SettingsDestination::SourceSettings)
			Intent.ACTION_VIEW -> when (intent.data?.host) {
				HOST_ADD_REPO -> null
				HOST_ABOUT -> SettingsDestination.AboutSettings
				else -> SettingsDestination.Root
			}
			else -> SettingsDestination.Root
		}
	}

	private fun resolveSingleSourceSettingsFragment(source: ContentSource): Fragment {
		val repository = mangaRepositoryFactory.create(source)
		return when (repository) {
			is ParserContentRepository,
			is KotatsuParserRepository,
			is EmptyContentRepository,
			is JsContentRepository,
			is LegadoRepository,
			is TVBoxRepository,
			is org.skepsun.kototoro.mihon.MihonMangaRepository,
			is org.skepsun.kototoro.aniyomi.AniyomiAnimeRepository,
			-> SourceComposeSettingsFragment.newInstance(source)

			else -> SourceSettingsFragment.newInstance(source)
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

	private fun shouldKeepComposeHistory(): Boolean = true

	private fun openComposeDestination(
		destination: SettingsDestination,
		shouldRestoreFragment: Boolean,
		pushCurrentToStack: Boolean = true,
	) {
		viewModel.discardSearch()
		if (destination !is SettingsDestination.UnifiedSources) {
			updateUnifiedSourcesSearchActive(false)
			unifiedSourcesActivePanel = null
		}
		if (supportFragmentManager.isStateSaved) {
			return
		}
		if (isMasterDetails && destination == SettingsDestination.Root) {
			composeNavigationStack.clear()
			composeDestination = SettingsDestination.Root
			viewBinding.containerCompose.isVisible = true
			findViewById<View>(R.id.container)?.isVisible = false
			findViewById<View>(R.id.container_search)?.isVisible = false
			setLegacyTopBarVisible(false)
			return
		}
		val currentComposeDestination = composeDestination
		if (
			pushCurrentToStack &&
			shouldKeepComposeHistory() &&
			currentComposeDestination != null &&
			currentComposeDestination != destination
		) {
			composeNavigationStack.addLast(currentComposeDestination)
		} else if (!shouldKeepComposeHistory()) {
			composeNavigationStack.clear()
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
		if (isMasterDetails) {
			findViewById<View>(R.id.container)?.isVisible = false
			findViewById<View>(R.id.container_search)?.isVisible = false
		} else {
			findViewById<View>(R.id.container)?.isVisible = false
			findViewById<View>(R.id.container_search)?.isVisible = false
		}
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
			is SettingsDestination.UnifiedSources -> {
				updateUnifiedSourcesSearchActive(false)
				unifiedSourcesActivePanel = null
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
			is SettingsDestination.SourceSettings -> {
				intent.putExtra(AppRouter.KEY_SOURCE, destination.sourceName)
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
			SettingsDestination.SpacesSettings -> COMPOSE_DESTINATION_SPACES_SETTINGS
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
			SettingsDestination.EntityOrganizeSettings -> COMPOSE_DESTINATION_ENTITY_ORGANIZE_SETTINGS
			SettingsDestination.TranslationSettings -> COMPOSE_DESTINATION_TRANSLATION_SETTINGS
			SettingsDestination.TranslationApiSettings -> COMPOSE_DESTINATION_TRANSLATION_API_SETTINGS
			SettingsDestination.TranslationE2EApiSettings -> COMPOSE_DESTINATION_TRANSLATION_E2E_API_SETTINGS
			SettingsDestination.StorageAndNetworkSettings -> COMPOSE_DESTINATION_STORAGE_AND_NETWORK_SETTINGS
			SettingsDestination.CacheLimitsSettings -> COMPOSE_DESTINATION_CACHE_LIMITS_SETTINGS
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
			is SettingsDestination.SourceSettings -> "source:${destination.sourceName}"
			is SettingsDestination.UnifiedSources -> "unified:${destination.initialRepositoryKind}:${destination.initialRepositoryUrl}"
		}
	}

	private fun composeDestinationTitle(destination: SettingsDestination): String {
		return when (destination) {
			SettingsDestination.Root -> getString(R.string.settings)
			SettingsDestination.AppearanceSettings -> getString(R.string.appearance)
			SettingsDestination.UsersSettings -> getString(R.string.users)
			SettingsDestination.SpacesSettings -> getString(R.string.spaces)
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
			SettingsDestination.EntityOrganizeSettings -> getString(R.string.entity_organize_title)
			SettingsDestination.TranslationSettings -> getString(R.string.translation_settings)
			SettingsDestination.TranslationApiSettings -> getString(R.string.ai_api_settings)
			SettingsDestination.TranslationE2EApiSettings -> getString(R.string.reader_translation_e2e_api_settings_title)
			SettingsDestination.StorageAndNetworkSettings -> getString(R.string.storage_and_network)
			SettingsDestination.CacheLimitsSettings -> getString(R.string.cache_limits)
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
			is SettingsDestination.SourceSettings -> org.skepsun.kototoro.core.model.ContentSource(
				destination.sourceName,
			).getTitle(this)
			is SettingsDestination.UnifiedSources -> getString(R.string.extension_management)
		}
	}

	@Composable
	private fun RenderComposeDestination(destination: SettingsDestination) {
		when (destination) {
			SettingsDestination.Root -> {
				RenderSettingsRootContent(modifier = Modifier.fillMaxSize())
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
			SettingsDestination.SpacesSettings -> RenderComposeSection(title = getString(R.string.spaces)) {
				SpacesSettingsRoute(settings = kototoroAppSettings)
			}
			SettingsDestination.AISettings -> RenderComposeSection(title = getString(R.string.ai_settings)) {
				AISettingsRoute(
					onOpenOcrModels = { openDestination(SettingsDestination.OcrModelsSettings, null, false) },
					onOpenApiSettings = { openDestination(SettingsDestination.TranslationApiSettings, null, false) },
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
					settings = kototoroAppSettings,
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
					onOpenCacheLimits = {
						openDestination(SettingsDestination.CacheLimitsSettings, null, false)
					},
					onOpenDataRemoval = {
						openDestination(SettingsDestination.DataCleanupSettings, null, false)
					},
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
			SettingsDestination.CacheLimitsSettings -> RenderComposeSection(title = getString(R.string.cache_limits)) {
				CacheLimitsSettingsRoute(
					settings = kototoroAppSettings,
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.DataCleanupSettings -> RenderComposeSection(title = getString(R.string.data_removal)) {
				DataCleanupSettingsRoute(
					settings = kototoroAppSettings,
					viewModel = dataCleanupSettingsViewModel,
					onClearLocalManga = ::confirmClearLocalManga,
					onClearLocalNovels = ::confirmClearLocalNovels,
					onClearLocalVideos = ::confirmClearLocalVideos,
					onClearSearchHistory = ::confirmClearSearchHistory,
					onClearCookies = ::confirmClearCookies,
					onDeleteReadChapters = ::confirmCleanupChapters,
					onOpenEntityOrganize = {
						openDestination(SettingsDestination.EntityOrganizeSettings, null, false)
					},
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
					modifier = Modifier.fillMaxSize(),
				)
			}
			SettingsDestination.BackupsSettings -> RenderComposeSection(title = getString(R.string.backup_restore)) {
				BackupsSettingsRoute(
					settings = kototoroAppSettings,
					googleDriveSyncSettings = googleDriveSyncSettings,
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
					onExportMihonBackupClick = {
						if (!mihonBackupExportCall.tryLaunch(BackupUtils.generateMihonBackupFileName(this))) {
							Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
						}
					},
					onExportAniyomiBackupClick = {
						if (!aniyomiBackupExportCall.tryLaunch(BackupUtils.generateAniyomiBackupFileName(this))) {
							Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
						}
					},
					onExportUsagiBackupClick = {
						if (!usagiBackupExportCall.tryLaunch(BackupUtils.generateUsagiBackupFileName(this))) {
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
			SettingsDestination.EntityOrganizeSettings -> RenderComposeSection(
				title = getString(R.string.entity_organize_title),
			) {
				SourceMigrationPanel(
					initialSelectedContentIds = initialEntityOrganizeSelection,
					onDismiss = ::handleComposeNavigateUp,
					showHeader = false,
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
					onNotificationVibrateClick = {
						trackerNotificationHelper.updateChannels()
						startSettingsActivitySafe(
							Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
								.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
								.putExtra(
									android.provider.Settings.EXTRA_CHANNEL_ID,
									TrackerNotificationHelper.CHANNEL_ID,
								),
						)
					},
				)
			}
			SettingsDestination.ServicesSettings -> RenderComposeSection(title = getString(R.string.services)) {
				ServicesSettingsRoute(
					settings = kototoroAppSettings,
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
			is SettingsDestination.SourceSettings -> RenderComposeSection(
				title = composeDestinationTitle(destination),
			) {
				SourceSettingsRoute(appRouter = router)
			}
			SettingsDestination.SourcesSettings -> RenderComposeSection(title = getString(R.string.remote_sources)) {
				SourcesSettingsRoute(
					settings = kototoroAppSettings,
					viewModel = sourcesSettingsViewModel,
					onSetupWizardClick = { router.showWelcomeSheet() },
				)
			}
			is SettingsDestination.UnifiedSources -> {
				val readyState by unifiedSourcesViewModel.uiState.collectAsStateWithLifecycle()
				RenderComposeSection(
					title = getString(R.string.extension_management),
					actions = {
						UnifiedSourcesToolbarActions(
							readyState = readyState as? org.skepsun.kototoro.settings.sources.unified.UnifiedSourcesUiState.Ready,
							searchActive = unifiedSourcesSearchActive,
							onSearchClick = { updateUnifiedSourcesSearchActive(true) },
							onSearchClose = {
								updateUnifiedSourcesSearchActive(false)
								unifiedSourcesViewModel.setSearchQuery("")
							},
							onSearchQueryChange = unifiedSourcesViewModel::setSearchQuery,
							onLanguageFilterClick = {
								unifiedSourcesActivePanel = UnifiedToolbarFilterPanel.LANGUAGE
							},
							onMoreFiltersClick = {
								unifiedSourcesActivePanel = UnifiedToolbarFilterPanel.MORE
							},
							modifier = Modifier.fillMaxSize(),
						)
					},
				) {
					UnifiedSourcesRoute(
						searchActive = unifiedSourcesSearchActive,
						onSearchActiveChange = ::updateUnifiedSourcesSearchActive,
						activePanel = unifiedSourcesActivePanel,
						onActivePanelChange = { unifiedSourcesActivePanel = it },
						initialAddRepositoryKind = destination.initialRepositoryKind,
						initialAddRepositoryUrl = destination.initialRepositoryUrl,
						viewModel = unifiedSourcesViewModel,
						onBrowseSource = { item -> router.openList(item.source, null, null) },
						onOpenSourceSettings = { item -> router.openSourceSettings(item.source) },
						onOpenRepositoryFile = ::openUnifiedSourcesRepositoryFilePicker,
						onOpenLocalJarPicker = ::openUnifiedSourcesLocalJarPicker,
						onStartInstall = { intent ->
							runCatching { unifiedSourcesInstallLauncher.launch(intent) }
								.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show() }
						},
						onStartUninstall = { intent ->
							runCatching { unifiedSourcesUninstallLauncher.launch(intent) }
								.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show() }
						},
						modifier = Modifier.fillMaxSize(),
					)
				}
			}
		}
	}

	@Composable
	private fun RenderSettingsRootContent(modifier: Modifier = Modifier) {
		val enabledSourcesCount by rootSettingsViewModel.enabledSourcesCount.collectAsStateWithLifecycle()
		val searchResults by viewModel.content.collectAsStateWithLifecycle()
		val searchQuery by viewModel.queryText.collectAsStateWithLifecycle()
		val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
		val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
		SettingsTopBarScaffold(
			title = getString(R.string.settings),
			onNavigateUp = ::handleComposeNavigateUp,
			modifier = modifier,
			searchContent = if (isSearchActive) {
				{
					SettingsSearchTopAppBar(
						query = searchQuery,
						onNavigateUp = viewModel::discardSearch,
						onQueryChange = viewModel::onQueryChanged,
					)
				}
			} else {
				null
			},
			actions = {
				SettingsSearchTopBarAction(
					onStartSearch = viewModel::startSearch,
				)
			},
		) { innerPadding ->
			SettingsRootScreen(
				sections = buildSettingsRootSections(
					context = this,
					enabledSourcesCount = enabledSourcesCount,
					totalSourcesCount = rootSettingsViewModel.totalSourcesCount,
					onOpenDestination = { composeDestination ->
						openDestination(composeDestination, null, true)
					},
				),
				searchQuery = searchQuery,
				searchResults = searchResults,
				onSearchResultClick = { item -> navigateToPreference(item) },
				listState = listState,
				topInset = innerPadding.calculateTopPadding(),
				applyHorizontalDisplayCutoutPadding = false,
				modifier = Modifier.fillMaxSize(),
			)
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
				val providerId = kototoroAppSettings.readerTranslationApiProviderPreset
				val modelsUrl = TranslationApiSettingsSupport.buildModelsUrl(endpoint, providerId)
				val key = kototoroAppSettings.readerTranslationApiKey.trim()
				val models = withContext(Dispatchers.IO) {
					val requestBuilder = Request.Builder().get().url(modelsUrl)
					TranslationApiProviderCatalog.applyAuthentication(requestBuilder, providerId, key)
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
		val currentDestination = composeDestination ?: return
		val previousDestination = composeNavigationStack.lastOrNull()
		if (currentDestination == SettingsDestination.Root) {
			finishFromComposeDestination(currentDestination)
			return
		}
		if (shouldKeepComposeHistory() && previousDestination != null) {
			onLeavingComposeDestination(currentDestination)
			composeNavigationStack.removeLast()
			openComposeDestination(
				destination = previousDestination,
				shouldRestoreFragment = false,
				pushCurrentToStack = false,
			)
			return
		}
		val shouldRestore = shouldRestoreFragmentOnComposeExit && supportFragmentManager.backStackEntryCount > 0
		if (isMasterDetails && !shouldRestore) {
			onLeavingComposeDestination(currentDestination)
			openComposeDestination(
				destination = SettingsDestination.Root,
				shouldRestoreFragment = false,
				pushCurrentToStack = false,
			)
			return
		}
		closeComposeDestination(restorePreviousFragment = false)
		if (shouldRestore) {
			supportFragmentManager.popBackStack()
		} else if (!isMasterDetails) {
			finishFromComposeDestination(currentDestination)
		}
	}

	private fun onLeavingComposeDestination(destination: SettingsDestination) {
		if (destination == SettingsDestination.TtsSettings) {
			ttsSettingsCoordinator?.stop()
			ttsSettingsCoordinator = null
		}
	}

	private fun finishFromComposeDestination(destination: SettingsDestination) {
		onLeavingComposeDestination(destination)
		composeBackCallback.isEnabled = false
		shouldRestoreFragmentOnComposeExit = false
		dispatchNavigateUp()
	}

	private fun closeComposeDestination(restorePreviousFragment: Boolean) {
		val destination = composeDestination ?: return
		onLeavingComposeDestination(destination)
		viewBinding.containerCompose.isVisible = false
		if (isMasterDetails) {
			findViewById<View>(R.id.container)?.isVisible = true
			findViewById<View>(R.id.container_search)?.isVisible = false
		} else {
			findViewById<View>(R.id.container)?.isVisible = true
			findViewById<View>(R.id.container_search)?.isVisible = false
		}
		composeDestination = null
		shouldRestoreFragmentOnComposeExit = false
		composeBackCallback.isEnabled = false
		invalidateOptionsMenu()
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

	private fun updateSinglePaneScrollBehavior(useLegacyTopBar: Boolean) {
		if (isMasterDetails) {
			return
		}
		fun update(view: View?, shouldUseBehavior: Boolean) {
			val targetView = view ?: return
			val params = targetView.layoutParams as? CoordinatorLayout.LayoutParams ?: return
			val currentBehavior = params.behavior
			val behaviorChanged = when {
				shouldUseBehavior && currentBehavior !is AppBarLayout.ScrollingViewBehavior -> true
				!shouldUseBehavior && currentBehavior != null -> true
				else -> false
			}
			if (!behaviorChanged) {
				return
			}
			params.behavior = if (shouldUseBehavior) {
				AppBarLayout.ScrollingViewBehavior()
			} else {
				null
			}
			targetView.layoutParams = params
		}
		update(viewBinding.containerCompose, shouldUseBehavior = false)
		update(findViewById(R.id.container), shouldUseBehavior = useLegacyTopBar)
		update(findViewById(R.id.container_search), shouldUseBehavior = useLegacyTopBar)
	}

	private fun openUnifiedSourcesRepositoryFilePicker(kind: UnifiedSourceKind) {
		pendingUnifiedSourcesFileImportKind = kind
		openUnifiedSourcesRepositoryFile.launch(
			arrayOf(
				"application/json",
				"text/plain",
				"application/javascript",
				"text/javascript",
				"*/*",
			),
		)
	}

	private fun openUnifiedSourcesLocalJarPicker() {
		openUnifiedSourcesLocalJar.launch(
			arrayOf(
				"application/java-archive",
				"application/zip",
				"*/*",
			),
		)
	}

	private fun persistReadPermission(uri: Uri) {
		runCatching {
			contentResolver.takePersistableUriPermission(
				uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION,
			)
		}
	}

	private fun resolveInitialUnifiedSourcesDestination(intent: Intent?): SettingsDestination.UnifiedSources? {
		if (intent == null) {
			return null
		}
		if (intent.action == AppRouter.ACTION_MANAGE_SOURCES) {
			return SettingsDestination.UnifiedSources(
				initialRepositoryKind = intent.getStringExtra(EXTRA_UNIFIED_SOURCES_KIND)
					?.let { runCatching { enumValueOf<UnifiedSourceKind>(it) }.getOrNull() },
				initialRepositoryUrl = intent.getStringExtra(EXTRA_UNIFIED_SOURCES_URL),
			)
		}
		if (intent.action == Intent.ACTION_VIEW && intent.data?.host == HOST_ADD_REPO) {
			return SettingsDestination.UnifiedSources(
				initialRepositoryKind = when (intent.data?.scheme) {
					"aniyomi", "anikku" -> UnifiedSourceKind.ANIYOMI
					else -> UnifiedSourceKind.MIHON
				},
				initialRepositoryUrl = intent.data?.getQueryParameter("url"),
			)
		}
		return null
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
		private const val HOST_ADD_REPO = "add-repo"
		private const val DISCORD_ORIGIN = "https://discord.com"
		private const val DISCORD_WWW_ORIGIN = "https://www.discord.com"
		const val EXTRA_USE_HORIZONTAL_ROUTE_TRANSITION = "use_horizontal_route_transition"
		const val ARG_PREF_KEY = "pref_key"
		private const val EXTRA_UNIFIED_SOURCES_KIND = "extra_unified_sources_kind"
		private const val EXTRA_UNIFIED_SOURCES_URL = "extra_unified_sources_url"
		private const val COMPOSE_HIDE_BACKSTACK_NAME = "settings_compose_hide"
		private const val STATE_COMPOSE_DESTINATION = "compose_destination"
		private const val STATE_COMPOSE_RESTORE_FRAGMENT = "compose_restore_fragment"
		private const val STATE_PENDING_RESTORE_ROOT = "pending_restore_root"
		private const val STATE_SOURCE_SETTINGS_SOURCE = "source_settings_source"
		private const val STATE_UNIFIED_SOURCES_KIND = "unified_sources_kind"
		private const val STATE_UNIFIED_SOURCES_URL = "unified_sources_url"
		private const val COMPOSE_DESTINATION_ROOT = "root"
		private const val COMPOSE_DESTINATION_APPEARANCE_SETTINGS = "appearance_settings"
		private const val COMPOSE_DESTINATION_USERS_SETTINGS = "users_settings"
		private const val COMPOSE_DESTINATION_SPACES_SETTINGS = "spaces_settings"
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
		private const val COMPOSE_DESTINATION_ENTITY_ORGANIZE_SETTINGS = "entity_organize_settings"
		private const val COMPOSE_DESTINATION_TRANSLATION_SETTINGS = "translation_settings"
		private const val COMPOSE_DESTINATION_TRANSLATION_API_SETTINGS = "translation_api_settings"
		private const val COMPOSE_DESTINATION_TRANSLATION_E2E_API_SETTINGS = "translation_e2e_api_settings"
		private const val COMPOSE_DESTINATION_STORAGE_AND_NETWORK_SETTINGS = "storage_and_network_settings"
		private const val COMPOSE_DESTINATION_CACHE_LIMITS_SETTINGS = "cache_limits_settings"
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
		private const val COMPOSE_DESTINATION_SOURCE_SETTINGS = "source_settings"
		private const val COMPOSE_DESTINATION_UNIFIED_SOURCES = "unified_sources"
		private const val EXTRA_ENTITY_ORGANIZE_SELECTION = "entity_organize_selection"

		fun newUnifiedSourcesIntent(
			context: Context,
			initialRepositoryKind: UnifiedSourceKind? = null,
			initialRepositoryUrl: String? = null,
		): Intent {
			return Intent(context, SettingsActivity::class.java)
				.setAction(AppRouter.ACTION_MANAGE_SOURCES)
				.apply {
					if (initialRepositoryKind != null) {
						putExtra(EXTRA_UNIFIED_SOURCES_KIND, initialRepositoryKind.name)
					}
					if (initialRepositoryUrl != null) {
						putExtra(EXTRA_UNIFIED_SOURCES_URL, initialRepositoryUrl)
					}
				}
		}

		fun newEntityOrganizeIntent(
			context: Context,
			selectedContentIds: Set<Long> = emptySet(),
		): Intent {
			return Intent(context, SettingsActivity::class.java)
				.setAction(AppRouter.ACTION_ENTITY_ORGANIZE)
				.putExtra(
					EXTRA_ENTITY_ORGANIZE_SELECTION,
					encodeEntityOrganizeSelection(selectedContentIds),
				)
		}
	}

	private fun applyCloseRouteTransitionIfNeeded() {
		if (hasAppliedCloseRouteTransition) {
			return
		}
		if (!intent.getBooleanExtra(EXTRA_USE_HORIZONTAL_ROUTE_TRANSITION, false)) {
			return
		}
		hasAppliedCloseRouteTransition = true
		applyHorizontalRouteCloseTransition()
	}

	private fun Bundle.toComposeDestination(): SettingsDestination? {
		return when (getString(STATE_COMPOSE_DESTINATION)) {
			COMPOSE_DESTINATION_ROOT -> SettingsDestination.Root
			COMPOSE_DESTINATION_APPEARANCE_SETTINGS -> SettingsDestination.AppearanceSettings
			COMPOSE_DESTINATION_USERS_SETTINGS -> SettingsDestination.UsersSettings
			COMPOSE_DESTINATION_SPACES_SETTINGS -> SettingsDestination.SpacesSettings
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
			COMPOSE_DESTINATION_ENTITY_ORGANIZE_SETTINGS -> SettingsDestination.EntityOrganizeSettings
			COMPOSE_DESTINATION_TRANSLATION_SETTINGS -> SettingsDestination.TranslationSettings
			COMPOSE_DESTINATION_TRANSLATION_API_SETTINGS -> SettingsDestination.TranslationApiSettings
			COMPOSE_DESTINATION_TRANSLATION_E2E_API_SETTINGS -> SettingsDestination.TranslationE2EApiSettings
			COMPOSE_DESTINATION_STORAGE_AND_NETWORK_SETTINGS -> SettingsDestination.StorageAndNetworkSettings
			COMPOSE_DESTINATION_CACHE_LIMITS_SETTINGS -> SettingsDestination.CacheLimitsSettings
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
			COMPOSE_DESTINATION_SOURCE_SETTINGS -> getString(STATE_SOURCE_SETTINGS_SOURCE)
				?.takeIf { it.isNotBlank() }
				?.let(SettingsDestination::SourceSettings)
			COMPOSE_DESTINATION_UNIFIED_SOURCES -> SettingsDestination.UnifiedSources(
				initialRepositoryKind = getString(STATE_UNIFIED_SOURCES_KIND)
					?.let { runCatching { enumValueOf<UnifiedSourceKind>(it) }.getOrNull() },
				initialRepositoryUrl = getString(STATE_UNIFIED_SOURCES_URL),
			)
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
		viewBinding.root.requestLayout()
		setLegacyTopBarVisible(composeDestination == null)
    }
}
