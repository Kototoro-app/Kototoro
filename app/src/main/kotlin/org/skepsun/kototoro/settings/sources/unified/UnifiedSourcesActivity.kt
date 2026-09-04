package org.skepsun.kototoro.settings.sources.unified

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity

@AndroidEntryPoint
class UnifiedSourcesActivity : BaseComposeActivity() {

    private val viewModel by viewModels<UnifiedSourcesViewModel>()
    private var pendingFileImportKind: UnifiedSourceKind? = null
    private var pendingFileImportEnabled = true
    private var activeDeepLink: UnifiedSourcesDeepLink? = null

    private val openRepositoryFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val kind = pendingFileImportKind ?: return@registerForActivityResult
        pendingFileImportKind = null
        persistReadPermission(uri)
        viewModel.addRepositoryFromFile(kind, uri, pendingFileImportEnabled)
    }

    private val openLocalJar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        persistReadPermission(uri)
        viewModel.importLocalJar(uri)
    }

    private val installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onInstallerActivityReturned()
    }

    private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onUninstallActivityResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialKind = intent.resolveInitialRepositoryKind()
        val initialUrl = intent.resolveInitialRepositoryUrl()
        val deepLink = resolveDeepLink(intent, savedInstanceState)
        activeDeepLink = deepLink
        // T5.2: apply the deep link (parsed from the intent Uri and/or extras; restored from
        // saved state on process recreation so the target tab / filter survives a kill).
        viewModel.applyDeepLink(deepLink)
        setComposeContent {
            UnifiedSourcesContent(
                initialAddRepositoryKind = initialKind,
                initialAddRepositoryUrl = initialUrl,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persist the resolved deep link under the parser's extra keys so a recreated
        // `onCreate(savedInstanceState != null)` can re-apply it (T5.2 process restoration).
        activeDeepLink?.let { link ->
            link.initialTab?.let { outState.putString(EXTRA_INITIAL_TAB, it) }
            link.packageFilter?.let { outState.putString(EXTRA_PACKAGE_FILTER, it) }
            link.sourceKey?.let { outState.putString(EXTRA_SOURCE_KEY, it) }
        }
    }

    /**
     * Resolves the [UnifiedSourcesDeepLink] for this launch: on process recreation the saved
     * state Bundle (written by [onSaveInstanceState]) wins; otherwise the intent's Uri query
     * parameters and extras are merged with Uri taking precedence (see
     * [UnifiedSourcesDeepLinkParser.merge]).
     */
    private fun resolveDeepLink(intent: Intent, savedInstanceState: Bundle?): UnifiedSourcesDeepLink {
        val restored = savedInstanceState?.let(UnifiedSourcesDeepLinkParser::fromExtras)
        if (restored != null && restored != UnifiedSourcesDeepLink()) {
            return restored
        }
        val uriLink = intent.data?.let(UnifiedSourcesDeepLinkParser::fromUri) ?: UnifiedSourcesDeepLink()
        val extrasLink = UnifiedSourcesDeepLinkParser.fromExtras(intent.extras)
        return UnifiedSourcesDeepLinkParser.merge(uriLink, extrasLink)
    }

    @Composable
    fun UnifiedSourcesContent(
        initialAddRepositoryKind: UnifiedSourceKind? = null,
        initialAddRepositoryUrl: String? = null,
        modifier: Modifier = Modifier,
    ) {
        var searchActive by remember { mutableStateOf(false) }
        var activePanel by remember { mutableStateOf<UnifiedToolbarFilterPanel?>(null) }
        UnifiedSourcesRoute(
            searchActive = searchActive,
            onSearchActiveChange = { searchActive = it },
            activePanel = activePanel,
            onActivePanelChange = { activePanel = it },
            initialAddRepositoryKind = initialAddRepositoryKind,
            initialAddRepositoryUrl = initialAddRepositoryUrl,
            viewModel = viewModel,
            onBrowseSource = { item -> router.openList(item.source, null, null) },
            onOpenSourceSettings = { item -> router.openSourceSettings(item.source) },
            onOpenRepositoryFile = ::openRepositoryFilePicker,
            onOpenLocalJarPicker = ::openLocalJarPicker,
            onStartInstall = { intent ->
                runCatching { installLauncher.launch(intent) }
                    .onFailure {
                        viewModel.onInstallerActivityReturned()
                        Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show()
                    }
            },
            onStartUninstall = { intent ->
                runCatching { uninstallLauncher.launch(intent) }
                    .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show() }
            },
            modifier = modifier,
        )
    }

    private fun openRepositoryFilePicker(kind: UnifiedSourceKind, enableImportedSources: Boolean) {
        pendingFileImportKind = kind
        pendingFileImportEnabled = enableImportedSources
        openRepositoryFile.launch(
            arrayOf(
                "application/json",
                "text/plain",
                "application/javascript",
                "text/javascript",
                "*/*",
            ),
        )
    }

    private fun openLocalJarPicker() {
        openLocalJar.launch(
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

    companion object {

        const val EXTRA_INITIAL_REPOSITORY_KIND = "initial_repository_kind"
        const val EXTRA_INITIAL_REPOSITORY_URL = "initial_repository_url"
        const val EXTRA_INITIAL_TAB = UnifiedSourcesDeepLinkParser.EXTRA_INITIAL_TAB
        const val EXTRA_PACKAGE_FILTER = UnifiedSourcesDeepLinkParser.EXTRA_PACKAGE_FILTER
        const val EXTRA_SOURCE_KEY = UnifiedSourcesDeepLinkParser.EXTRA_SOURCE_KEY
        private const val HOST_ADD_REPO = "add-repo"

        fun newIntent(
            context: Context,
            initialRepositoryKind: UnifiedSourceKind? = null,
            initialRepositoryUrl: String? = null,
        ): Intent {
            return Intent(context, UnifiedSourcesActivity::class.java).apply {
                if (initialRepositoryKind != null) {
                    putExtra(EXTRA_INITIAL_REPOSITORY_KIND, initialRepositoryKind.name)
                }
                if (initialRepositoryUrl != null) {
                    putExtra(EXTRA_INITIAL_REPOSITORY_URL, initialRepositoryUrl)
                }
            }
        }

        /**
         * Deep-link intent (T5.2): carries both the parser extras (consumed by this Activity)
         * and the `dl_*` keys read directly by [UnifiedSourcesViewModel]'s SavedStateHandle,
         * so the link also survives process death.
         */
        fun newDeepLinkIntent(
            context: Context,
            link: UnifiedSourcesDeepLink,
        ): Intent {
            return Intent(context, UnifiedSourcesActivity::class.java).apply {
                link.initialTab?.let { putExtra(EXTRA_INITIAL_TAB, it) }
                link.packageFilter?.let { putExtra(EXTRA_PACKAGE_FILTER, it) }
                link.sourceKey?.let { putExtra(EXTRA_SOURCE_KEY, it) }
                link.initialTab?.let { putExtra(DL_SAVED_STATE_TAB, it) }
                link.packageFilter?.let { putExtra(DL_SAVED_STATE_PACKAGE, it) }
                link.sourceKey?.let { putExtra(DL_SAVED_STATE_SOURCE_KEY, it) }
            }
        }

        private fun Intent.resolveInitialRepositoryKind(): UnifiedSourceKind? {
            val extraKind = getStringExtra(EXTRA_INITIAL_REPOSITORY_KIND)
                ?.let { runCatching { enumValueOf<UnifiedSourceKind>(it) }.getOrNull() }
            if (extraKind != null) {
                return extraKind
            }
            if (action != Intent.ACTION_VIEW || data?.host != HOST_ADD_REPO) {
                return null
            }
            val parsed = UnifiedAddRepoDeepLinkParser.fromUri(data)
            return parsed.kind ?: UnifiedAddRepoDeepLinkParser.kindFromScheme(data?.scheme)
        }

        private fun Intent.resolveInitialRepositoryUrl(): String? {
            return getStringExtra(EXTRA_INITIAL_REPOSITORY_URL)
                ?: data
                    ?.takeIf { action == Intent.ACTION_VIEW && it.host == HOST_ADD_REPO }
                    ?.let { UnifiedAddRepoDeepLinkParser.fromUri(it).url }
        }
    }
}
