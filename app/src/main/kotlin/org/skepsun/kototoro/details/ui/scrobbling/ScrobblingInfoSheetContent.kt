package org.skepsun.kototoro.details.ui.scrobbling

import android.text.method.LinkMovementMethod
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RatingBar
import android.widget.Spinner
import androidx.core.text.parseAsHtml
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.ContentCoverShape
import org.skepsun.kototoro.core.ui.widgets.SelectableTextView
import org.skepsun.kototoro.core.util.ext.sanitize
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScrobblingInfoSheetContent(
	viewModel: DetailsViewModel,
	scrobblerServiceId: Int,
	discoveryService: TrackingSiteDiscoveryService,
	onDismissRequest: () -> Unit,
	onOpenCover: (ScrobblingInfo) -> Unit,
	onOpenBrowser: (String) -> Boolean,
	onEdit: (ScrobblerService) -> Unit,
	onUnregister: () -> Unit,
	onUpdate: (Float, ScrobblingStatus?) -> Unit,
) {
	val scrobblingItems by viewModel.scrobblingInfo.collectAsStateWithLifecycle()
	val scrobbling = scrobblingItems.firstOrNull { it.scrobbler.id == scrobblerServiceId }
	var details by remember { mutableStateOf<TrackingSiteItemDetails?>(null) }
	var isLoadingDetails by remember { mutableStateOf(false) }

	LaunchedEffect(scrobbling?.scrobbler, scrobbling?.targetId) {
		val current = scrobbling ?: return@LaunchedEffect
		isLoadingDetails = true
		details = null
		try {
			details = withContext(Dispatchers.IO) {
				discoveryService.getDetails(current.scrobbler, current.targetId)
			}
		} catch (error: Exception) {
			if (error is CancellationException) throw error
		} finally {
			isLoadingDetails = false
		}
	}

	val snackbarHostState = remember { SnackbarHostState() }
	val coroutineScope = rememberCoroutineScope()
	val unsupportedMessage = stringResource(R.string.operation_not_supported)
	ScrobblingInfoSheetLayout(
		scrobbler = scrobbling,
		details = details,
		isLoadingDetails = isLoadingDetails,
		snackbarHostState = snackbarHostState,
		onDismissRequest = onDismissRequest,
		onOpenCover = onOpenCover,
		onOpenBrowser = { url ->
			if (!onOpenBrowser(url)) {
				coroutineScope.launch {
					snackbarHostState.showSnackbar(unsupportedMessage)
				}
			}
		},
		onEdit = onEdit,
		onUnregister = onUnregister,
		onUpdate = onUpdate,
	)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ScrobblingInfoSheetLayout(
	scrobbler: ScrobblingInfo?,
	details: TrackingSiteItemDetails?,
	isLoadingDetails: Boolean,
	snackbarHostState: SnackbarHostState,
	onDismissRequest: () -> Unit,
	onOpenCover: (ScrobblingInfo) -> Unit,
	onOpenBrowser: (String) -> Unit,
	onEdit: (ScrobblerService) -> Unit,
	onUnregister: () -> Unit,
	onUpdate: (Float, ScrobblingStatus?) -> Unit,
) {
	if (scrobbler == null) {
		return
	}

	Box(modifier = Modifier.fillMaxSize()) {
		Scaffold(
			topBar = {
				ScrobblingTopBar(
					onDismissRequest = onDismissRequest,
					onOpenBrowser = { onOpenBrowser(scrobbler.externalUrl) },
					onEdit = { onEdit(scrobbler.scrobbler) },
					onUnregister = onUnregister,
				)
			},
			snackbarHost = { SnackbarHost(snackbarHostState) },
		) { contentPadding ->
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.padding(contentPadding)
					.padding(bottom = 16.dp),
			) {
				ScrobblingHeader(
					scrobbler = scrobbler,
					onOpenCover = { onOpenCover(scrobbler) },
					onUpdate = onUpdate,
				)
				val description = scrobbler.description?.toString()?.takeIf { it.isNotBlank() }
					?: details?.description?.takeIf { it.isNotBlank() }
				if (!description.isNullOrBlank()) {
					RichDescription(description)
				}
				val comment = scrobbler.comment
				if (!comment.isNullOrBlank()) {
					Text(
						text = comment,
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
					)
				}
				if (isLoadingDetails) {
					LinearProgressIndicator(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp, vertical = 8.dp),
					)
				}
				details?.let { trackingDetails ->
					TrackingDetails(
						details = trackingDetails,
						fallbackDescriptionShown = !description.isNullOrBlank(),
					)
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScrobblingTopBar(
	onDismissRequest: () -> Unit,
	onOpenBrowser: () -> Unit,
	onEdit: () -> Unit,
	onUnregister: () -> Unit,
) {
	var isMenuExpanded by remember { mutableStateOf(false) }
	TopAppBar(
		title = { Text(stringResource(R.string.tracking)) },
		navigationIcon = {
			IconButton(onClick = onDismissRequest) {
				Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
			}
		},
		actions = {
			Box {
				IconButton(onClick = { isMenuExpanded = true }) {
					Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.open_in_browser))
				}
				DropdownMenu(
					expanded = isMenuExpanded,
					onDismissRequest = { isMenuExpanded = false },
				) {
					DropdownMenuItem(
						text = { Text(stringResource(R.string.open_in_browser)) },
						onClick = { isMenuExpanded = false; onOpenBrowser() },
					)
					DropdownMenuItem(
						text = { Text(stringResource(R.string.edit)) },
						onClick = { isMenuExpanded = false; onEdit() },
					)
					DropdownMenuItem(
						text = { Text(stringResource(R.string.remove)) },
						onClick = { isMenuExpanded = false; onUnregister() },
					)
				}
			}
		},
	)
}

@Composable
private fun ScrobblingHeader(
	scrobbler: ScrobblingInfo,
	onOpenCover: () -> Unit,
	onUpdate: (Float, ScrobblingStatus?) -> Unit,
) {
	val context = LocalContext.current
	val currentStatusOrdinal by rememberUpdatedState(scrobbler.status?.ordinal ?: -1)
	val currentRating by rememberUpdatedState(scrobbler.rating)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		verticalAlignment = Alignment.Top,
	) {
		Box(
			modifier = Modifier
			.weight(0.36f)
			.aspectRatio(13f / 18f)
			.clip(ContentCoverShape),
		) {
			AsyncImage(
				model = ImageRequest.Builder(context)
					.data(scrobbler.coverUrl.takeIfUsableImageUri())
					.crossfade(true)
					.build(),
				contentDescription = scrobbler.title,
				contentScale = ContentScale.Crop,
				placeholder = rememberSafePainter(R.drawable.ic_placeholder),
				error = rememberSafePainter(R.drawable.ic_placeholder),
				modifier = Modifier
					.fillMaxSize()
					.clip(ContentCoverShape),
			)
			IconButton(
				onClick = onOpenCover,
				modifier = Modifier.fillMaxSize(),
			) { }
			Box(
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(8.dp)
					.size(32.dp),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = rememberSafePainter(scrobbler.scrobbler.iconResId),
					contentDescription = stringResource(scrobbler.scrobbler.titleResId),
					tint = MaterialTheme.colorScheme.onSecondary,
					modifier = Modifier
						.size(32.dp)
						.padding(4.dp),
				)
			}
		}
		Spacer(Modifier.size(16.dp))
		Column(modifier = Modifier.weight(0.64f)) {
			Text(
				text = scrobbler.title,
				style = MaterialTheme.typography.headlineSmall,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			AndroidView(
				factory = {
					RatingBar(it).apply {
						numStars = 5
						stepSize = 0.5f
						setOnRatingBarChangeListener { _, rating, fromUser ->
							if (fromUser) onUpdate(rating / 5f, ScrobblingStatus.entries.getOrNull(currentStatusOrdinal))
						}
					}
				},
				update = { it.rating = currentRating * it.numStars },
				modifier = Modifier.padding(top = 6.dp),
			)
			AndroidView(
				factory = {
					Spinner(it).apply {
						adapter = ArrayAdapter.createFromResource(
							context,
							R.array.scrobbling_statuses,
							android.R.layout.simple_spinner_item,
						).also { arrayAdapter ->
							arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
						}
						setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
							var ignoreInitialSelection = true

							override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
								if (ignoreInitialSelection) {
									ignoreInitialSelection = false
								} else if (position != currentStatusOrdinal) {
									onUpdate(currentRating, ScrobblingStatus.entries.getOrNull(position))
								}
							}

							override fun onNothingSelected(parent: AdapterView<*>?) = Unit
						})
					}
				},
				update = { spinner ->
					if (currentStatusOrdinal >= 0 && spinner.selectedItemPosition != currentStatusOrdinal) {
						spinner.setSelection(currentStatusOrdinal)
					}
				},
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 6.dp),
			)
		}
	}
}

@Composable
private fun RichDescription(description: String) {
	AndroidView(
		factory = {
			SelectableTextView(it).apply {
				movementMethod = LinkMovementMethod.getInstance()
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
			}
		},
		update = { it.text = renderDescription(description) },
		modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
	)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackingDetails(
	details: TrackingSiteItemDetails,
	fallbackDescriptionShown: Boolean,
) {
	val rows = buildList {
		details.score?.let { add(stringResource(R.string.rating) to stringResource(R.string.discover_score, it)) }
		details.rank?.let { add(stringResource(R.string.rank) to stringResource(R.string.discover_rank_value, it)) }
		details.year?.let { add(stringResource(R.string.year) to it.toString()) }
		details.totalEpisodes?.let { add(stringResource(R.string.discover_total_episodes) to it.toString()) }
		if (details.authors.isNotEmpty()) add(stringResource(R.string.authors) to details.authors.joinToString())
	}
	if (rows.isNotEmpty()) {
		Card(
			colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
		) {
			Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				rows.forEach { (label, value) ->
					Row(modifier = Modifier.fillMaxWidth()) {
						Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
						Text(value, style = MaterialTheme.typography.bodyMedium)
					}
				}
			}
		}
	}
	if (details.tags.isNotEmpty()) {
		FlowRow(
			modifier = Modifier.padding(horizontal = 16.dp),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			details.tags.forEach { tag ->
				AssistChip(onClick = {}, label = { Text(tag) })
			}
		}
	}
	if (details.infoboxProperties.isNotEmpty()) {
		Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
			Text(stringResource(R.string.work_info), style = MaterialTheme.typography.titleSmall)
						details.infoboxProperties.forEach { (key, value) ->
								Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
									Text(
										key,
										style = MaterialTheme.typography.titleSmall,
										modifier = Modifier.widthIn(min = 80.dp).padding(end = 8.dp),
									)
					Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
				}
			}
		}
	}
	if (!fallbackDescriptionShown && !details.description.isNullOrBlank()) {
		RichDescription(details.description)
	}
}

private fun renderDescription(raw: String): CharSequence {
		return runCatching {
			raw.parseAsHtml().sanitize()
		}.getOrElse { raw.sanitize() }
}
