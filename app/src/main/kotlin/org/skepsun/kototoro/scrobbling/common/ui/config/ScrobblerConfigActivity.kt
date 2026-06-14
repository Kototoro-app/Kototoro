package org.skepsun.kototoro.scrobbling.common.ui.config

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.snackbar.Snackbar
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseActivity
import org.skepsun.kototoro.core.ui.list.OnListItemClickListener
import org.skepsun.kototoro.core.util.ext.consumeAllSystemBarsInsets
import org.skepsun.kototoro.core.util.ext.getParcelableExtraCompat
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.core.util.ext.showOrHide
import org.skepsun.kototoro.core.util.ext.systemBarsInsets
import org.skepsun.kototoro.databinding.ActivityScrobblerConfigBinding
import org.skepsun.kototoro.list.ui.adapter.TypedListSpacingDecoration
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerUser
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.scrobbling.common.ui.config.adapter.ScrobblingContentAdapter
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class ScrobblerConfigActivity : BaseActivity<ActivityScrobblerConfigBinding>(),
	OnListItemClickListener<ScrobblingInfo>, View.OnClickListener {

	private val viewModel: ScrobblerConfigViewModel by viewModels()
	private var pendingBindInfo: ScrobblingInfo? = null
	private var pendingBindHandled = false

	private val pickContentLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
		android.util.Log.d("ScrobblerConfig", "pickContentLauncher: resultCode=${result.resultCode}, hasData=${result.data != null}")
		if (result.resultCode == android.app.Activity.RESULT_OK) {
			val manga = result.data?.getParcelableExtraCompat<org.skepsun.kototoro.core.model.parcelable.ParcelableContent>(AppRouter.KEY_MANGA)?.manga
			val scrobblingInfo = pendingBindInfo
			android.util.Log.d("ScrobblerConfig", "pickContentLauncher: manga=${manga?.title}, scrobblingInfo=${scrobblingInfo?.title}")
			if (manga != null && scrobblingInfo != null) {
				pendingBindInfo = null
				viewModel.bindContent(scrobblingInfo, manga)
			} else {
				android.util.Log.w("ScrobblerConfig", "pickContentLauncher: manga or scrobblingInfo is null!")
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityScrobblerConfigBinding.inflate(layoutInflater))
		setTitle(viewModel.titleResId)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)

		val listAdapter = ScrobblingContentAdapter(this, viewModel::onContentBound)
		with(viewBinding.recyclerView) {
			adapter = listAdapter
			setHasFixedSize(true)
			val decoration = TypedListSpacingDecoration(context, false)
			addItemDecoration(decoration)
		}
		viewBinding.imageViewAvatar.setOnClickListener(this)

		viewBinding.swipeRefreshLayout.setOnRefreshListener {
			viewModel.syncLibrary()
		}

		viewModel.content.observe(this, listAdapter)
		viewModel.user.observe(this, this::onUserChanged)
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		viewModel.onLoggedOut.observeEvent(this) {
			finishAfterTransition()
		}
		viewModel.onSyncResult.observeEvent(this) { count ->
			viewBinding.swipeRefreshLayout.isRefreshing = false
			if (count > 0) {
				Snackbar.make(viewBinding.recyclerView, getString(R.string.sync_complete, count), Snackbar.LENGTH_SHORT).show()
			} else if (count == 0) {
				Snackbar.make(viewBinding.recyclerView, R.string.sync_not_supported, Snackbar.LENGTH_SHORT).show()
			}
			// count < 0 means sync is not applicable for this service, just dismiss silently
		}
		viewModel.onBindResult.observeEvent(this) { title ->
			Snackbar.make(viewBinding.recyclerView, getString(R.string.bind_manga_success, title), Snackbar.LENGTH_SHORT).show()
		}

		processIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		pendingBindHandled = false
		processIntent(intent)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		val basePadding = v.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding.appbar.updatePadding(
			top = barsInsets.top,
			left = barsInsets.left,
			right = barsInsets.right,
		)
		viewBinding.recyclerView.setPadding(
			barsInsets.left + basePadding,
			barsInsets.top + basePadding,
			barsInsets.right + basePadding,
			barsInsets.bottom + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onItemClick(item: ScrobblingInfo, view: View) {
		lifecycleScope.launch {
			val hasLocal = withContext(Dispatchers.Default) {
				viewModel.hasLocalContent(item.mangaId)
			}
			if (hasLocal) {
				router.openDetails(item.mangaId)
			} else {
				// Open the tracking site discovery detail page
				router.openTrackingSiteDetails(
					service = item.scrobbler,
					remoteId = item.targetId,
					url = item.externalUrl,
				)
			}
		}
	}

	private fun showSearchContentKindDialog(item: ScrobblingInfo) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.search_content_kind) 
			.setItems(
				arrayOf(
					getString(R.string.all),
					getString(R.string.manga),
					getString(R.string.novel),
					getString(R.string.video)
				)
			) { _, which ->
				pendingBindInfo = item
				val contentKinds = when (which) {
					1 -> setOf(SearchContentKind.MANGA)
					2 -> setOf(SearchContentKind.NOVEL)
					3 -> setOf(SearchContentKind.VIDEO)
					else -> null
				}
				val intent = AppRouter.searchIntent(
					context = this,
					query = item.title,
					contentKinds = contentKinds,
					pickMode = true
				)
				pickContentLauncher.launch(intent)
			}.show()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.imageView_avatar -> showUserDialog()
		}
	}

	private fun processIntent(intent: Intent) {
		extractPendingBindInfo(intent)
		if (intent.action == Intent.ACTION_VIEW) {
			val uri = intent.data ?: return
			val code = uri.getQueryParameter("code")
			if (!code.isNullOrEmpty()) {
				viewModel.onAuthCodeReceived(code)
			}
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
		if (remoteId == 0L || title.isNullOrBlank()) {
			return
		}
		pendingBindInfo = ScrobblingInfo(
			scrobbler = viewModel.getScrobblerService(),
			entityId = null,
			preferredLocalMangaId = null,
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

	private fun onUserChanged(user: ScrobblerUser?) {
		if (user == null) {
			viewBinding.imageViewAvatar.disposeImage()
			viewBinding.imageViewAvatar.setImageResource(appcompatR.drawable.abc_ic_menu_overflow_material)
			return
		}
		if (!user.avatar.isNullOrEmpty()) {
			viewBinding.imageViewAvatar.setImageAsync(user.avatar)
		} else {
			viewBinding.imageViewAvatar.setImageResource(user.service.iconResId)
		}
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.progressBar.showOrHide(isLoading)
		if (!isLoading) {
			viewBinding.swipeRefreshLayout.isRefreshing = false
		}
	}

	private fun showUserDialog() {
		MaterialAlertDialogBuilder(this)
			.setTitle(title)
			.setMessage(getString(R.string.logged_in_as, viewModel.user.value?.nickname))
			.setNegativeButton(R.string.close, null)
			.setPositiveButton(R.string.logout) { _, _ ->
				viewModel.logout()
			}.show()
	}

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
