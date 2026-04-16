package org.skepsun.kototoro.entitygraph.ui.details

import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.text.parseAsHtml
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.lifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseFragment
import org.skepsun.kototoro.core.util.ext.consume
import org.skepsun.kototoro.core.util.ext.getThemeColor
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.databinding.FragmentEntityDetailsBinding
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import javax.inject.Inject

@AndroidEntryPoint
class EntityDetailsFragment : BaseFragment<FragmentEntityDetailsBinding>() {

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<EntityDetailsViewModel>()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentEntityDetailsBinding {
		return FragmentEntityDetailsBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentEntityDetailsBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		(activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.hide()
		(activity as? org.skepsun.kototoro.core.ui.FragmentContainerActivity)?.appBar?.visibility = View.GONE

		binding.toolbar.setNavigationOnClickListener { activity?.finish() }

		// Immersive toolbar: transparent by default, becomes solid on scroll
		val surfaceColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
		binding.appbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)

		binding.swipeRefreshLayout.setOnRefreshListener { viewModel.refresh() }
		binding.buttonRetry.setOnClickListener { viewModel.refresh() }

		viewModel.screenState.observe(viewLifecycleOwner) { state ->
			renderState(binding, state)
		}
		viewModel.error.observe(viewLifecycleOwner) { error ->
			binding.errorGroup.isVisible = error != null
			binding.contentGroup.isVisible = error == null && viewModel.screenState.value.entity != null
			if (error != null) {
				val ref = viewModel.screenState.value.trackingReference
				binding.buttonOpenTrackingFallback.isVisible = ref != null
				binding.buttonOpenTrackingFallback.setOnClickListener {
					ref?.let {
						router.openTrackingSiteRawDetails(it.service, it.remoteId, it.url)
					}
				}
			}
		}
		viewModel.isLoading.observe(viewLifecycleOwner) {
			binding.swipeRefreshLayout.isRefreshing = it
			binding.progressBar.isVisible = it && viewModel.screenState.value.entity == null
		}
	}

	private fun renderState(binding: FragmentEntityDetailsBinding, state: EntityDetailsScreenState) {
		val entity = state.entity ?: return
		val trackingDetails = state.trackingDetails

		binding.contentGroup.isVisible = true

		// Title
		binding.textViewTitle.text = entity.primaryName
		binding.toolbar.title = ""

		// Type + source badge
		val typeName = when (entity.type) {
			EntityType.WORK -> getString(R.string.entity_graph_type_work)
			EntityType.CHARACTER -> getString(R.string.entity_graph_type_character)
			EntityType.PERSON -> getString(R.string.entity_graph_type_person)
			EntityType.ORGANIZATION -> getString(R.string.entity_graph_type_organization)
		}
		val sourceBadge = state.trackingReference?.service?.let { getString(it.titleResId) }
		binding.textViewType.text = if (sourceBadge != null) "$typeName · $sourceBadge" else typeName

		// Cover image
		val coverUrl = entity.coverUrl ?: trackingDetails?.coverUrl
		if (!coverUrl.isNullOrBlank()) {
			loadCoverImage(binding, coverUrl)
			loadPanoramaCover(binding, coverUrl)
		}

		// Quick info bar
		renderQuickInfo(binding, trackingDetails)

		// Description
		val desc = entity.description ?: trackingDetails?.description
		if (!desc.isNullOrBlank()) {
			binding.layoutDescription.isVisible = true
			binding.textViewDescription.text = desc.parseAsHtml().toString().trim()
			binding.buttonDescriptionMore.isVisible = true
			binding.buttonDescriptionMore.setOnClickListener {
				if (binding.textViewDescription.maxLines < Int.MAX_VALUE) {
					binding.textViewDescription.maxLines = Int.MAX_VALUE
					binding.buttonDescriptionMore.setText(R.string.show_less)
				} else {
					binding.textViewDescription.maxLines = 5
					binding.buttonDescriptionMore.setText(R.string.show_more)
				}
			}
		}

		// Tags
		if (trackingDetails != null && trackingDetails.tags.isNotEmpty()) {
			binding.chipsTags.isVisible = true
			binding.chipsTags.setChips(trackingDetails.tags.map {
				org.skepsun.kototoro.core.ui.widgets.ChipsView.ChipModel(title = it, data = it)
			})
		}

		// Aliases card
		val displayAliases = entity.aliases.filter { it != entity.primaryName }
		val hasAliases = displayAliases.isNotEmpty()
		val hasBindings = state.bindings.isNotEmpty()
		binding.cardMetadata.isVisible = hasAliases || hasBindings
		if (hasAliases) {
			binding.textViewAliasesLabel.isVisible = true
			binding.textViewAliases.isVisible = true
			binding.textViewAliases.text = displayAliases.joinToString("\n")
		} else {
			binding.textViewAliasesLabel.isVisible = false
			binding.textViewAliases.isVisible = false
		}
		if (hasBindings) {
			binding.textViewBindingsLabel.isVisible = true
			binding.textViewBindings.text = state.bindings.joinToString("\n") { b ->
				"${b.source} · ${b.externalId}"
			}
		}

		// Action buttons
		val ref = state.trackingReference
		binding.buttonOpenTrackingDetails.isVisible = ref != null
		binding.buttonOpenTrackingDetails.setOnClickListener {
			ref?.let { router.openTrackingSiteRawDetails(it.service, it.remoteId, it.url) }
		}
		val url = trackingDetails?.url
		binding.buttonOpenSite.isVisible = !url.isNullOrBlank()
		binding.buttonOpenSite.setOnClickListener {
			url?.let { router.openExternalBrowser(it) }
		}

		// Source results
		renderSourceResults(binding, state)

		// Entity graph relations
		renderRelationSections(binding, state)

		// Related works from tracking API
		renderRelatedWorks(binding, trackingDetails)

		// Recommendations from tracking API
		renderRecommendations(binding, trackingDetails)
	}

	private fun renderQuickInfo(binding: FragmentEntityDetailsBinding, details: TrackingSiteItemDetails?) {
		if (details == null) {
			binding.layoutQuickInfo.isVisible = false
			return
		}
		val hasScore = details.score != null
		val hasYear = details.year != null
		val hasEpisodes = details.totalEpisodes != null
		val hasRank = details.rank != null
		binding.layoutQuickInfo.isVisible = hasScore || hasYear || hasEpisodes || hasRank

		binding.textViewScore.isVisible = hasScore
		binding.textViewScore.text = details.score?.let { getString(R.string.discover_score, it) }

		binding.textViewYear.isVisible = hasYear
		binding.textViewYear.text = details.year?.toString()

		binding.textViewEpisodes.isVisible = hasEpisodes
		binding.textViewEpisodes.text = details.totalEpisodes?.let { "$it eps" }

		binding.textViewRank.isVisible = hasRank
		binding.textViewRank.text = details.rank?.let { "#$it" }
	}

	private fun renderSourceResults(binding: FragmentEntityDetailsBinding, state: EntityDetailsScreenState) {
		val sourceResults = state.sourceResults
		binding.textViewSourceResultsTitle.isVisible = sourceResults.isNotEmpty()
		binding.sourceResultsContainer.isVisible = sourceResults.isNotEmpty()
		binding.sourceResultsContainer.removeAllViews()
		sourceResults.forEach { result ->
			val row = LayoutInflater.from(requireContext()).inflate(
				android.R.layout.simple_list_item_2,
				binding.sourceResultsContainer,
				false,
			)
			row.findViewById<TextView>(android.R.id.text1).text = result.content.title
			row.findViewById<TextView>(android.R.id.text2).text = getString(
				R.string.entity_graph_source_result_subtitle,
				result.source.name,
				(result.confidence * 100).toInt(),
			)
			row.setOnClickListener {
				router.openDetails(result.content)
			}
			binding.sourceResultsContainer.addView(row)
		}
	}

	private fun renderRelationSections(binding: FragmentEntityDetailsBinding, state: EntityDetailsScreenState) {
		val sections = state.relationSections
		binding.textViewRelationsTitle.isVisible = sections.isNotEmpty()
		binding.relationsContainer.removeAllViews()
		if (sections.isEmpty()) {
			val emptyHint = TextView(requireContext()).apply {
				text = getString(R.string.entity_graph_no_relations)
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
				setPadding(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(4))
			}
			binding.relationsContainer.addView(emptyHint)
		}
		sections.forEach { section ->
			val sectionTitle = TextView(requireContext()).apply {
				setText(section.titleRes)
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
				setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(4))
			}
			binding.relationsContainer.addView(sectionTitle)
			section.items.forEach { item ->
				val itemView = TextView(requireContext()).apply {
					text = item.name
					setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
					setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6))
					isClickable = true
					isFocusable = true
					val outValue = TypedValue()
					context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
					setBackgroundResource(outValue.resourceId)
					setOnClickListener { router.openEntityDetails(item.entityId) }
				}
				binding.relationsContainer.addView(itemView)
			}
		}
	}

	private fun renderRelatedWorks(binding: FragmentEntityDetailsBinding, details: TrackingSiteItemDetails?) {
		val works = details?.relatedWorks.orEmpty()
		binding.textViewRelatedWorksTitle.isVisible = works.isNotEmpty()
		binding.scrollRelatedWorks.isVisible = works.isNotEmpty()
		if (works.isNotEmpty()) {
			binding.textViewRelatedWorksTitle.text = getString(R.string.entity_graph_section_related_entities)
			binding.relatedWorksContainer.removeAllViews()
			works.forEach { work -> addWorkCard(binding.relatedWorksContainer, work, showRelationship = true, details) }
		}
	}

	private fun renderRecommendations(binding: FragmentEntityDetailsBinding, details: TrackingSiteItemDetails?) {
		val recs = details?.recommendations.orEmpty()
		binding.textViewRecommendationsTitle.isVisible = recs.isNotEmpty()
		binding.scrollRecommendations.isVisible = recs.isNotEmpty()
		if (recs.isNotEmpty()) {
			binding.textViewRecommendationsTitle.setText(R.string.recommended)
			binding.recommendationsContainer.removeAllViews()
			recs.forEach { work -> addWorkCard(binding.recommendationsContainer, work, showRelationship = false, details) }
		}
	}

	private fun addWorkCard(
		container: LinearLayout,
		work: TrackingSiteItemDetails.RelatedWork,
		showRelationship: Boolean,
		details: TrackingSiteItemDetails?,
	) {
		val cardWidth = dpToPx(100)
		val card = LinearLayout(requireContext()).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
				marginEnd = dpToPx(8)
			}
			isClickable = true
			isFocusable = true
			val outValue = TypedValue()
			context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
			setBackgroundResource(outValue.resourceId)
			setOnClickListener {
				router.openTrackingSiteDetails(
					service = details?.service ?: return@setOnClickListener,
					remoteId = work.id,
					url = work.url,
				)
			}
		}

		val coverView = ImageView(requireContext()).apply {
			layoutParams = LinearLayout.LayoutParams(cardWidth, dpToPx(140))
			scaleType = ImageView.ScaleType.CENTER_CROP
			clipToOutline = true
			setBackgroundResource(com.google.android.material.R.drawable.m3_tabs_background)
		}
		if (work.coverUrl.isNotBlank()) {
			val req = ImageRequest.Builder(requireContext())
				.data(work.coverUrl)
				.lifecycle(this)
				.crossfade(true)
				.target(onSuccess = { coverView.setImageDrawable(it.asDrawable(resources)) })
				.build()
			coil.enqueue(req)
		}
		card.addView(coverView)

		if (showRelationship && !work.relationship.isNullOrBlank()) {
			val relLabel = TextView(requireContext()).apply {
				text = work.relationship
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
				setTextColor(requireContext().getThemeColor(androidx.appcompat.R.attr.colorPrimary))
				setPadding(0, dpToPx(2), 0, 0)
			}
			card.addView(relLabel)
		}

		val titleView = TextView(requireContext()).apply {
			text = work.title
			setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
			maxLines = 2
			ellipsize = android.text.TextUtils.TruncateAt.END
			setPadding(0, dpToPx(4), 0, 0)
		}
		card.addView(titleView)

		container.addView(card)
	}

	private fun loadCoverImage(binding: FragmentEntityDetailsBinding, imageUrl: String) {
		val request = ImageRequest.Builder(requireContext())
			.data(imageUrl)
			.lifecycle(this)
			.crossfade(true)
			.target(onSuccess = { result ->
				binding.imageViewCover.setImageDrawable(result.asDrawable(resources))
			})
			.build()
		coil.enqueue(request)
	}

	private fun loadPanoramaCover(binding: FragmentEntityDetailsBinding, imageUrl: String) {
		binding.imageViewPanorama.isVisible = true
		binding.viewPanoramaScrim.isVisible = true

		val request = ImageRequest.Builder(requireContext())
			.data(imageUrl)
			.lifecycle(this)
			.crossfade(true)
			.allowRgb565(true)
			.target(
				onSuccess = { result ->
					binding.imageViewPanorama.setImageDrawable(result.asDrawable(resources))
					applyBlurEffect(binding.imageViewPanorama)
				},
				onError = {
					binding.imageViewPanorama.isVisible = false
					binding.viewPanoramaScrim.isVisible = false
				},
			)
			.build()
		coil.enqueue(request)
	}

	private fun applyBlurEffect(imageView: ImageView) {
		val blurLevel = if (::settings.isInitialized) settings.panoramaCoverBlur else 50
		if (blurLevel <= 0) return
		val radius = 1f + (blurLevel / 100f) * 24f
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			imageView.setRenderEffect(
				android.graphics.RenderEffect.createBlurEffect(
					radius, radius,
					android.graphics.Shader.TileMode.MIRROR,
				),
			)
		} else {
			imageView.alpha = 1f - (blurLevel / 100f) * 0.7f
		}
	}

	override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		val binding = requireViewBinding()
		binding.appbar.updatePadding(top = systemBars.top)
		binding.swipeRefreshLayout.updatePadding(
			left = systemBars.left,
			right = systemBars.right,
			bottom = systemBars.bottom,
		)
		return insets.consume(view, WindowInsetsCompat.Type.systemBars(), start = true, end = true, bottom = true)
	}

	private fun dpToPx(dp: Int): Int {
		return TypedValue.applyDimension(
			TypedValue.COMPLEX_UNIT_DIP,
			dp.toFloat(),
			resources.displayMetrics,
		).toInt()
	}
}
