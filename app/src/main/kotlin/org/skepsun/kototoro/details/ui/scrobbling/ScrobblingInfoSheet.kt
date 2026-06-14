package org.skepsun.kototoro.details.ui.scrobbling

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.text.parseAsHtml
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.sheet.BaseAdaptiveSheet
import org.skepsun.kototoro.core.ui.widgets.ChipsView
import org.skepsun.kototoro.core.util.ext.consume
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.sanitize
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.databinding.SheetScrobblingBinding
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import javax.inject.Inject

@AndroidEntryPoint
class ScrobblingInfoSheet :
	BaseAdaptiveSheet<SheetScrobblingBinding>(),
	AdapterView.OnItemSelectedListener,
	RatingBar.OnRatingBarChangeListener,
	View.OnClickListener,
	PopupMenu.OnMenuItemClickListener {

	@Inject
	lateinit var discoveryService: TrackingSiteDiscoveryService

	private val viewModel by activityViewModels<DetailsViewModel>()
	private var scrobblerServiceId: Int = -1

	private var menu: PopupMenu? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		scrobblerServiceId = requireArguments().getInt(AppRouter.KEY_ID, scrobblerServiceId)
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetScrobblingBinding {
		return SheetScrobblingBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetScrobblingBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		viewModel.scrobblingInfo.observe(viewLifecycleOwner, ::onScrobblingInfoChanged)
		viewModel.onError.observeEvent(viewLifecycleOwner) {
			Toast.makeText(binding.root.context, it.getDisplayMessage(binding.root.resources), Toast.LENGTH_SHORT)
				.show()
		}

		binding.spinnerStatus.onItemSelectedListener = this
		binding.ratingBar.onRatingBarChangeListener = this
		binding.buttonMenu.setOnClickListener(this)
		binding.imageViewCover.setOnClickListener(this)
		binding.textViewDescription.movementMethod = LinkMovementMethodCompat.getInstance()

		menu = PopupMenu(binding.root.context, binding.buttonMenu).apply {
			inflate(R.menu.opt_scrobbling)
			setOnMenuItemClickListener(this@ScrobblingInfoSheet)
		}

		// Load tracking site details
		loadTrackingSiteDetails()
	}

	override fun onDestroyView() {
		super.onDestroyView()
		menu = null
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.root?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}


	override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
		viewModel.updateScrobbling(
			scrobblerServiceId = scrobblerServiceId,
			rating = requireViewBinding().ratingBar.rating / requireViewBinding().ratingBar.numStars,
			status = ScrobblingStatus.entries.getOrNull(position),
		)
	}

	override fun onNothingSelected(parent: AdapterView<*>?) = Unit

	override fun onRatingChanged(ratingBar: RatingBar, rating: Float, fromUser: Boolean) {
		if (fromUser) {
			viewModel.updateScrobbling(
				scrobblerServiceId = scrobblerServiceId,
				rating = rating / ratingBar.numStars,
				status = ScrobblingStatus.entries.getOrNull(requireViewBinding().spinnerStatus.selectedItemPosition),
			)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_menu -> menu?.show()
			R.id.imageView_cover -> router.openImage(
				url = currentScrobbling()?.coverUrl ?: return,
				source = null,
				anchor = v,
			)
		}
	}

	private fun onScrobblingInfoChanged(scrobblings: List<ScrobblingInfo>) {
		val scrobbling = scrobblings.firstOrNull { it.scrobbler.id == scrobblerServiceId }
		if (scrobbling == null) {
			dismissAllowingStateLoss()
			return
		}
		val binding = viewBinding ?: return
		binding.textViewTitle.text = scrobbling.title
		binding.ratingBar.rating = scrobbling.rating * binding.ratingBar.numStars
		binding.textViewDescription.text = renderDescription(scrobbling.description?.toString())
		binding.spinnerStatus.setSelection(scrobbling.status?.ordinal ?: -1)
		binding.imageViewLogo.contentDescription = getString(scrobbling.scrobbler.titleResId)
		binding.imageViewLogo.setImageResource(scrobbling.scrobbler.iconResId)
		binding.imageViewCover.setImageAsync(scrobbling.coverUrl?.takeIfUsableImageUri())
	}

	private fun loadTrackingSiteDetails() {
		val scrobbling = currentScrobbling() ?: return
		val binding = viewBinding ?: return

		binding.progressDetails.isVisible = true

		viewLifecycleOwner.lifecycleScope.launch {
			try {
				val details = withContext(Dispatchers.IO) {
					discoveryService.getDetails(scrobbling.scrobbler, scrobbling.targetId)
				}
				val currentBinding = viewBinding ?: return@launch
				currentBinding.progressDetails.isVisible = false
				renderTrackingDetails(currentBinding, details)
			} catch (e: Exception) {
				val currentBinding = viewBinding ?: return@launch
				currentBinding.progressDetails.isVisible = false
				// Silently fail — the basic scrobbling info is still shown
			}
		}
	}

	private fun renderTrackingDetails(binding: SheetScrobblingBinding, details: TrackingSiteItemDetails) {
		var hasAnyData = false

		// Score
		details.score?.let { score ->
			binding.rowScore.isVisible = true
			binding.textViewScoreValue.text = getString(R.string.discover_score, score)
			hasAnyData = true
		}

		// Rank
		details.rank?.let { rank ->
			binding.rowRank.isVisible = true
			binding.textViewRankValue.text = getString(R.string.discover_rank_value, rank)
			hasAnyData = true
		}

		// Year
		details.year?.let { year ->
			binding.rowYear.isVisible = true
			binding.textViewYearValue.text = year.toString()
			hasAnyData = true
		}

		// Episodes
		details.totalEpisodes?.let { episodes ->
			binding.rowEpisodes.isVisible = true
			binding.textViewEpisodesValue.text = episodes.toString()
			hasAnyData = true
		}

		// Authors
		if (details.authors.isNotEmpty()) {
			binding.rowAuthors.isVisible = true
			binding.textViewAuthorsValue.text = details.authors.joinToString()
			hasAnyData = true
		}

		// Show card if any data
		binding.cardScoreRank.isVisible = hasAnyData

		// Tags
		if (details.tags.isNotEmpty()) {
			binding.chipsTags.isVisible = true
			binding.chipsTags.setChips(details.tags.map { ChipsView.ChipModel(title = it, data = it) })
		}

		// Infobox
		if (details.infoboxProperties.isNotEmpty()) {
			binding.layoutInfobox.isVisible = true
			renderInfobox(binding.infoboxContainer, details.infoboxProperties)
		}

		// Update description if current one is empty and details has one
		if (binding.textViewDescription.text.isNullOrBlank() && !details.description.isNullOrBlank()) {
			binding.textViewDescription.text = renderDescription(details.description)
		}
	}

	private fun renderDescription(raw: String?): CharSequence? {
		if (raw.isNullOrBlank()) {
			return null
		}
		return runCatching {
			raw.parseAsHtml().sanitize()
		}.getOrElse {
			raw.sanitize()
		}
	}

	private fun renderInfobox(container: LinearLayout, properties: List<Pair<String, String>>) {
		container.removeAllViews()
		val ctx = container.context

		properties.forEach { (key, value) ->
			val row = LinearLayout(ctx).apply {
				orientation = LinearLayout.HORIZONTAL
				layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT,
				).apply { bottomMargin = dpToPx(4) }
			}
			val keyView = TextView(ctx).apply {
				text = key
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
				layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT,
				).apply { marginEnd = dpToPx(8) }
				minWidth = dpToPx(80)
			}
			val valueView = TextView(ctx).apply {
				text = value
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
				layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
			}
			row.addView(keyView)
			row.addView(valueView)
			container.addView(row)
		}
	}

	private fun dpToPx(dp: Int): Int {
		return TypedValue.applyDimension(
			TypedValue.COMPLEX_UNIT_DIP,
			dp.toFloat(),
			resources.displayMetrics,
		).toInt()
	}

	override fun onMenuItemClick(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.action_browser -> {
				val url = currentScrobbling()?.externalUrl ?: return false
				if (!router.openExternalBrowser(url, getString(R.string.open_in_browser))) {
					Snackbar.make(
						viewBinding?.textViewDescription ?: return false,
						R.string.operation_not_supported,
						Snackbar.LENGTH_SHORT,
					).show()
				}
			}

			R.id.action_unregister -> {
				viewModel.unregisterScrobbling(scrobblerServiceId)
				dismiss()
			}

			R.id.action_edit -> {
				val manga = viewModel.manga.value ?: return false
				val scrobblerService = currentScrobbling()?.scrobbler
				activity?.router?.showScrobblingSelectorSheet(manga, scrobblerService)
				dismiss()
			}
		}
		return true
	}

	private fun currentScrobbling(): ScrobblingInfo? {
		return viewModel.scrobblingInfo.value.firstOrNull { it.scrobbler.id == scrobblerServiceId }
	}
}
