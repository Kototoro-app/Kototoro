package org.skepsun.kototoro.scrobbling.common.ui.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.util.ext.isSerializable
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo

@AndroidEntryPoint
class ScrobblerConfigActivity : BaseComposeActivity() {

	private val viewModel: ScrobblerConfigViewModel by viewModels()
	private var pendingBindInfo: ScrobblingInfo? = null
	private var pendingBindHandled = false
	private var pendingDialog by mutableStateOf<ScrobblerDialogState?>(null)

	private val pickContentLauncher = registerForActivityResult(
		androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
	) { result ->
		if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
		val manga = result.data
			?.getParcelableExtraCompat<ParcelableContent>(AppRouter.KEY_MANGA)
			?.manga
		val scrobblingInfo = pendingBindInfo
		if (manga != null && scrobblingInfo != null) {
			pendingBindInfo = null
			viewModel.bindContent(scrobblingInfo, manga)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		observeViewModelEvents()
		setComposeContent {
			val user = viewModel.user.collectAsStateWithLifecycle().value
			val items = viewModel.content.collectAsStateWithLifecycle().value
			val isLoading = viewModel.isLoading.collectAsStateWithLifecycle().value
			ScrobblerConfigScreen(
				title = getString(viewModel.titleResId),
				user = user,
				items = items,
				isLoading = isLoading,
				onNavigateUp = ::finishAfterTransition,
				onAvatarClick = ::showUserDialog,
				onRefresh = viewModel::syncLibrary,
				onItemClick = ::onItemClick,
				onContentBound = viewModel::onContentBound,
			)
			RenderScrobblerDialog()
		}
		processIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		pendingBindHandled = false
		processIntent(intent)
	}

	private fun observeViewModelEvents() {
		viewModel.onError.observeEvent(this) { error ->
			lifecycleScope.launch {
				val actionResId = when {
					ExceptionResolver.canResolve(error) -> exceptionResolver.getResolveStringId(error)
					error is ParseException && error.isSerializable() -> R.string.details
					else -> 0
				}
				val result = snackbarHostState.showSnackbar(
					message = error.getDisplayMessage(resources),
					actionLabel = actionResId.takeIf { it != 0 }?.let(::getString),
				)
				if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
					if (ExceptionResolver.canResolve(error)) {
						exceptionResolver.resolve(error, tryAutoResolve = false)
					} else {
						exceptionResolver.showErrorDetails(error)
					}
				}
			}
		}
		viewModel.onLoggedOut.observeEvent(this) { finishAfterTransition() }
		viewModel.onSyncResult.observeEvent(this) { count ->
			lifecycleScope.launch {
				snackbarHostState.showSnackbar(
					if (count >= 0) getString(R.string.sync_complete, count)
					else getString(R.string.sync_not_supported),
				)
			}
		}
		viewModel.onBindResult.observeEvent(this) { title ->
			lifecycleScope.launch {
				snackbarHostState.showSnackbar(getString(R.string.bind_manga_success, title))
			}
		}
	}

	private fun onItemClick(item: ScrobblingInfo) {
		lifecycleScope.launch {
			val hasLocal = withContext(Dispatchers.Default) {
				viewModel.hasLocalContent(item.mangaId)
			}
			if (hasLocal) {
				router.openDetails(item.mangaId)
			} else {
				router.openTrackingSiteDetails(item.scrobbler, item.targetId, item.externalUrl)
			}
		}
	}

	private fun showSearchContentKindDialog(item: ScrobblingInfo) {
		pendingDialog = ScrobblerDialogState.SearchContentKind(item)
	}

	private fun processIntent(intent: Intent) {
		extractPendingBindInfo(intent)
		if (intent.action == Intent.ACTION_VIEW) {
			intent.data?.getQueryParameter("code")?.takeIf(String::isNotEmpty)?.let(viewModel::onAuthCodeReceived)
		}
		val info = pendingBindInfo
		if (info != null && !pendingBindHandled) {
			pendingBindHandled = true
			showSearchContentKindDialog(info)
		}
	}

	private fun extractPendingBindInfo(intent: Intent) {
		val remoteId = intent.getLongExtra(AppRouter.KEY_REMOTE_ID, 0L)
		val title = intent.getStringExtra(AppRouter.KEY_TITLE)
		if (remoteId == 0L || title.isNullOrBlank()) return
		pendingBindInfo = ScrobblingInfo(
			scrobbler = viewModel.getScrobblerService(),
			mangaId = 0L,
			targetId = remoteId,
			status = null,
			chapter = 0,
			comment = null,
			rating = 0f,
			title = title,
			coverUrl = "",
			description = null,
			externalUrl = intent.getStringExtra(AppRouter.KEY_URL).orEmpty(),
		)
	}

	private fun showUserDialog() {
		pendingDialog = ScrobblerDialogState.User
	}

	@Composable
	private fun RenderScrobblerDialog() {
		when (val dialog = pendingDialog) {
			is ScrobblerDialogState.SearchContentKind -> {
				AlertDialog(
					onDismissRequest = { dismissDialog(dialog) },
					title = { Text(stringResource(R.string.search_content_kind)) },
					text = {
						LazyColumn {
							items(searchContentKindChoices) { choice ->
								Row(
								modifier = Modifier
									.fillMaxWidth()
									.selectable(
										selected = false,
										onClick = { selectContentKind(dialog, choice) },
									)
									.padding(vertical = 4.dp),
									verticalAlignment = Alignment.CenterVertically,
								) {
									RadioButton(
										selected = false,
										onClick = null,
									)
									Text(
										text = stringResource(choice.labelRes),
										modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
									)
								}
							}
						}
					},
					confirmButton = {},
				)
			}
			ScrobblerDialogState.User -> {
				AlertDialog(
					onDismissRequest = { dismissDialog(dialog) },
					title = { Text(stringResource(viewModel.titleResId)) },
					text = {
						Text(stringResource(R.string.logged_in_as, viewModel.user.value?.nickname.orEmpty()))
					},
					confirmButton = {
						TextButton(onClick = ::logout) {
							Text(stringResource(R.string.logout))
						}
					},
					dismissButton = {
						TextButton(onClick = { dismissDialog(dialog) }) {
							Text(stringResource(R.string.close))
						}
					},
				)
			}
			null -> Unit
		}
	}

	private fun dismissDialog(dialog: ScrobblerDialogState) {
		if (pendingDialog === dialog) {
			pendingDialog = null
		}
	}

	private fun selectContentKind(
		dialog: ScrobblerDialogState.SearchContentKind,
		choice: ContentKindChoice,
	) {
		if (pendingDialog !== dialog) return
		pendingDialog = null
		pendingBindInfo = dialog.item
		pickContentLauncher.launch(
			AppRouter.searchIntent(
				this,
				dialog.item.title,
				contentKinds = choice.contentKinds,
				pickMode = true,
			),
		)
	}

	private fun logout() {
		if (pendingDialog !== ScrobblerDialogState.User) return
		pendingDialog = null
		viewModel.logout()
	}

	private sealed interface ScrobblerDialogState {
		data class SearchContentKind(val item: ScrobblingInfo) : ScrobblerDialogState
		data object User : ScrobblerDialogState
	}

	private data class ContentKindChoice(
		val labelRes: Int,
		val contentKinds: Set<SearchContentKind>?,
	)

	private val searchContentKindChoices = listOf(
		ContentKindChoice(R.string.all, null),
		ContentKindChoice(R.string.manga, setOf(SearchContentKind.MANGA)),
		ContentKindChoice(R.string.novel, setOf(SearchContentKind.NOVEL)),
		ContentKindChoice(R.string.video, setOf(SearchContentKind.VIDEO)),
	)

	companion object {
		const val HOST_SHIKIMORI_AUTH = "shikimori-auth"
		const val HOST_ANILIST_AUTH = "anilist-auth"
		const val HOST_MAL_AUTH = "mal-auth"
		const val HOST_KITSU_AUTH = "kitsu-auth"
		const val HOST_BANGUMI_AUTH = "bangumi-auth"
		const val HOST_MANGAUPDATES_AUTH = "mangaupdates-auth"
		const val HOST_SIMKL_AUTH = "simkl-auth"
	}
}
