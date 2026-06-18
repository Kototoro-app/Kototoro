package org.skepsun.kototoro.settings.userdata

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.domain.BackupUtils
import org.skepsun.kototoro.backups.external.ExternalBackupApp
import org.skepsun.kototoro.backups.ui.backup.AniyomiBackupExportService
import org.skepsun.kototoro.backups.ui.backup.BackupService
import org.skepsun.kototoro.backups.ui.backup.MihonBackupExportService
import org.skepsun.kototoro.backups.ui.periodical.PeriodicalBackupSettingsViewModel
import org.skepsun.kototoro.backups.ui.restore.ExternalBackupImportService
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.os.OpenDocumentTreeHelper
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.settings.SettingsActivity
import org.skepsun.kototoro.settings.compose.BackupsSettingsScreen
import org.skepsun.kototoro.settings.compose.BackupsSettingsUiState
import org.skepsun.kototoro.settings.compose.SettingsChoiceOption
import javax.inject.Inject

@AndroidEntryPoint
class BackupsSettingsFragment : Fragment() {

    @Inject
    lateinit var settings: AppSettings

    private val viewModel by viewModels<PeriodicalBackupSettingsViewModel>()

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
            if (ExternalBackupImportService.start(requireContext(), uri, app)) {
                Snackbar.make(requireView(), R.string.import_backup_started_background, Snackbar.LENGTH_SHORT).show()
            } else {
                showOperationNotSupported()
            }
        }
        pendingExternalBackupApp = null
    }

    private var pendingExternalBackupApp: ExternalBackupApp? = null

    private val backupCreateCall = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null && !BackupService.start(requireContext(), uri)) {
            showOperationNotSupported()
        }
    }

    private val mihonBackupExportCall = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null && !MihonBackupExportService.start(requireContext(), uri)) {
            showOperationNotSupported()
        }
    }

    private val aniyomiBackupExportCall = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null && !AniyomiBackupExportService.start(requireContext(), uri)) {
            showOperationNotSupported()
        }
    }

    private val outputSelectCall = OpenDocumentTreeHelper(this) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context?.contentResolver?.takePersistableUriPermission(uri, takeFlags)
            settings.periodicalBackupDirectory = uri
            viewModel.updateSummaryData()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (view as ComposeView).setContent {
            KototoroTheme {
                BackupsSettingsRoute(
                    settings = settings,
                    viewModel = viewModel,
                    onBackupOutputClick = {
                        if (!outputSelectCall.tryLaunch(null)) {
                            showOperationNotSupported()
                        }
                    },
                    onCreateBackupClick = {
                        if (!backupCreateCall.tryLaunch(BackupUtils.generateFileName(requireContext()))) {
                            showOperationNotSupported()
                        }
                    },
                    onExportMihonBackupClick = {
                        if (!mihonBackupExportCall.tryLaunch(BackupUtils.generateMihonBackupFileName(requireContext()))) {
                            showOperationNotSupported()
                        }
                    },
                    onExportAniyomiBackupClick = {
                        if (!aniyomiBackupExportCall.tryLaunch(BackupUtils.generateAniyomiBackupFileName(requireContext()))) {
                            showOperationNotSupported()
                        }
                    },
                    onRestoreBackupClick = {
                        if (!backupSelectCall.tryLaunch(arrayOf("*/*"))) {
                            showOperationNotSupported()
                        }
                    },
                    onImportExternalBackupFilePick = { app ->
                        pendingExternalBackupApp = app
                        if (!externalBackupSelectCall.tryLaunch(arrayOf("*/*"))) {
                            pendingExternalBackupApp = null
                            showOperationNotSupported()
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.backup_restore))
    }

    private fun showOperationNotSupported() {
        val hostView = view ?: return
        Snackbar.make(hostView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
    }
}

@Composable
fun BackupsSettingsRoute(
    settings: AppSettings,
    viewModel: PeriodicalBackupSettingsViewModel,
    onBackupOutputClick: () -> Unit,
    onCreateBackupClick: () -> Unit,
    onRestoreBackupClick: () -> Unit,
    onExportMihonBackupClick: () -> Unit,
    onExportAniyomiBackupClick: () -> Unit,
    onImportExternalBackupFilePick: (ExternalBackupApp) -> Unit,
) {
    val context = LocalContext.current
    val lastBackupDate = viewModel.lastBackupDate.collectAsStateWithLifecycle().value
    val backupDirectory = viewModel.backupsDirectory.collectAsStateWithLifecycle().value
    val backupFrequency =
        settings.observeAsState(AppSettings.KEY_BACKUP_PERIODICAL_FREQUENCY) { periodicalBackupFrequency }.value
    val isPeriodicalTrimEnabled =
        settings.observeAsState(AppSettings.KEY_BACKUP_PERIODICAL_TRIM) { isPeriodicalBackupTrimEnabled }.value
    val periodicalBackupCount =
        settings.observeAsState(AppSettings.KEY_BACKUP_PERIODICAL_COUNT) { periodicalBackupCount }.value
    val snackbarHostState = remember { SnackbarHostState() }
    val backupFrequencyLabels = context.resources.getStringArray(R.array.backup_frequency)
    val backupFrequencyValues = context.resources.getStringArray(R.array.values_backup_frequency)
    val backupFrequencyOptions = backupFrequencyLabels.zip(backupFrequencyValues).mapNotNull { (label, value) ->
        value.toFloatOrNull()?.let { SettingsChoiceOption(it, label) }
    }
    var isExternalImportDialogVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel.onError, context, snackbarHostState) {
        viewModel.onError.collect { event ->
            event?.consume { error ->
                snackbarHostState.showSnackbar(error.getDisplayMessage(context.resources))
            }
        }
    }
    LaunchedEffect(viewModel.onActionDone, context, snackbarHostState) {
        viewModel.onActionDone.collect { event ->
            event?.consume { action ->
                snackbarHostState.showSnackbar(context.getString(action.stringResId))
            }
        }
    }

    val lastBackupSummary = when {
        lastBackupDate != null -> context.getString(
            R.string.last_successful_backup,
            DateUtils.getRelativeTimeSpanString(lastBackupDate.time),
        )
        else -> null
    }
    val state = BackupsSettingsUiState(
        backupOutputSummary = when (backupDirectory) {
            null -> context.getString(R.string.invalid_value_message)
            "" -> ""
            else -> backupDirectory
        },
        isBackupOutputInvalid = backupDirectory == null,
        backupFrequency = backupFrequency,
        isPeriodicalTrimEnabled = isPeriodicalTrimEnabled,
        periodicalBackupCount = periodicalBackupCount,
        lastBackupSummary = lastBackupSummary,
        isExternalImportDialogVisible = isExternalImportDialogVisible,
    )

    BackupsSettingsScreen(
        backupRestoreTitle = context.getString(R.string.backup_restore),
        state = state,
        snackbarHostState = snackbarHostState,
        backupFrequencyOptions = backupFrequencyOptions,
        onBackupOutputClick = onBackupOutputClick,
        onBackupFrequencyChange = { settings.periodicalBackupFrequency = it },
        onPeriodicalTrimChange = { settings.isPeriodicalBackupTrimEnabled = it },
        onPeriodicalBackupCountChange = { settings.periodicalBackupCount = it },
        onCreateBackupClick = onCreateBackupClick,
        onRestoreBackupClick = onRestoreBackupClick,
        onExportMihonBackupClick = onExportMihonBackupClick,
        onExportAniyomiBackupClick = onExportAniyomiBackupClick,
        onImportExternalBackupClick = { isExternalImportDialogVisible = true },
        onDismissExternalImportDialog = { isExternalImportDialogVisible = false },
        onImportExternalBackupAppClick = { app ->
            isExternalImportDialogVisible = false
            onImportExternalBackupFilePick(app)
        },
    )
}
