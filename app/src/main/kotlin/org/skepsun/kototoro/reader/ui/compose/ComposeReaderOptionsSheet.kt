package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderImageScalingQuality
import org.skepsun.kototoro.reader.ui.config.ImageServerOptions
import org.skepsun.kototoro.reader.ui.colorfilter.ReaderColorCorrectionControls
import org.skepsun.kototoro.reader.ui.colorfilter.ReaderImageComparisonPreview
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionValueRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderSegmentedChoice

@Immutable
internal data class ComposeReaderOptionsState(
	val visible: Boolean = false,
	val mode: ReaderMode = ReaderMode.STANDARD,
	val animation: ReaderAnimation = ReaderAnimation.DEFAULT,
	val doublePage: Boolean = false,
	val doublePageFoldable: Boolean = false,
	val doublePageCover: Boolean = false,
	val splitPages: Boolean = false,
	val doublePageSensitivity: Float = 0.5f,
	val superResolution: Boolean = false,
	val appearancePreviewOriginalUri: String? = null,
	val appearancePreviewProcessedUri: String? = null,
	val appearancePreviewLoading: Boolean = false,
	val colorFilter: ReaderColorFilter? = null,
	val imageScalingQuality: ReaderImageScalingQuality = ReaderImageScalingQuality.DEFAULT,
	val background: ReaderBackground = ReaderBackground.DEFAULT,
	val imageServer: ImageServerOptions? = null,
)

internal data class ComposeReaderOptionsCallbacks(
	val onDismiss: () -> Unit = {},
	val onModeChanged: (ReaderMode) -> Unit = {},
	val onAnimationChanged: (ReaderAnimation) -> Unit = {},
	val onDoublePageChanged: (Boolean) -> Unit = {},
	val onDoublePageFoldableChanged: (Boolean) -> Unit = {},
	val onDoublePageCoverChanged: (Boolean) -> Unit = {},
	val onSplitPagesChanged: (Boolean) -> Unit = {},
	val onDoublePageSensitivityChanged: (Float) -> Unit = {},
	val onSuperResolutionChanged: (Boolean) -> Unit = {},
	val onBackgroundChanged: (ReaderBackground) -> Unit = {},
	val onImageServerChanged: (String?) -> Unit = {},
	val onSavePage: () -> Unit = {},
	val onPreviousChapter: () -> Unit = {},
	val onNextChapter: () -> Unit = {},
	val onPages: () -> Unit = {},
	val onBookmark: () -> Unit = {},
	val onDownload: () -> Unit = {},
	val onRotate: () -> Unit = {},
	val onAutoScroll: () -> Unit = {},
	val onTranslation: () -> Unit = {},
	val onOpenSettings: () -> Unit = {},
	val onColorFilterChanged: (ReaderColorFilter?) -> Unit = {},
	val onImageScalingQualityChanged: (ReaderImageScalingQuality) -> Unit = {},
	val onSaveColorFilterForManga: (ReaderColorFilter?) -> Unit = {},
	val onSaveColorFilterGlobally: (ReaderColorFilter?) -> Unit = {},
	val onOpenBrowser: () -> Unit = {},
	val onTranslationSettings: () -> Unit = {},
	val onRetranslatePage: () -> Unit = {},
	val onRetryFailedTranslations: () -> Unit = {},
	val onRetranslateChapter: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposeReaderOptionsSheet(
	state: ComposeReaderOptionsState,
	callbacks: ComposeReaderOptionsCallbacks,
	embedded: Boolean = false,
	translationTaskPanelContent: @Composable () -> Unit = {},
	headerModifier: Modifier = Modifier,
	modifier: Modifier = Modifier,
) {
	if (!state.visible) return
	val pages = listOf(
		ReaderOptionsPage(R.drawable.ic_book_page, R.string.reader_page_turning_mode),
		ReaderOptionsPage(R.drawable.ic_translate, R.string.reader_translation_tools),
		ReaderOptionsPage(R.drawable.ic_appearance, R.string.image_post_processing),
		ReaderOptionsPage(R.drawable.ic_more_vert, R.string.miscellaneous),
	)
	val pagerState = rememberPagerState(pageCount = { pages.size })
	val scope = rememberCoroutineScope()
	Surface(
		shape = if (embedded) androidx.compose.foundation.shape.RoundedCornerShape(0.dp) else MaterialTheme.shapes.large,
		color = if (embedded) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = modifier
			.widthIn(max = 560.dp)
			.fillMaxWidth()
			.then(if (embedded) Modifier.fillMaxHeight() else Modifier.heightIn(max = 560.dp)),
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.then(headerModifier)
					.padding(horizontal = 8.dp, vertical = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				pages.forEachIndexed { index, page ->
					ReaderOptionsTab(
						page = page,
						selected = pagerState.currentPage == index,
						onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
					)
				}
			}
			HorizontalPager(
				state = pagerState,
				modifier = Modifier
					.fillMaxWidth()
					.weight(1f),
			) { page ->
				when (page) {
					0 -> ReaderModeOptionsPage(state, callbacks)
					1 -> ReaderTranslationOptionsPage(callbacks, translationTaskPanelContent)
					2 -> ReaderAppearanceOptionsPage(state, callbacks)
					else -> ReaderMiscOptionsPage(state, callbacks)
				}
			}
		}
	}
}

@Immutable
private data class ReaderOptionsPage(
	val iconResId: Int,
	val labelResId: Int,
)

@Composable
private fun ReaderOptionsTab(
	page: ReaderOptionsPage,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Surface(
		shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
		color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
	) {
		IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
			Icon(
				painter = painterResource(page.iconResId),
				contentDescription = stringResource(page.labelResId),
				tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderModeOptionsPage(
	state: ComposeReaderOptionsState,
	callbacks: ComposeReaderOptionsCallbacks,
) {
	val backgroundLabels = stringArrayResource(R.array.reader_backgrounds)
	val animationLabels = stringArrayResource(R.array.reader_animation)
	OptionsPageList {
		item {
			ReaderSegmentedChoice(
				title = stringResource(R.string.reader_page_turning_mode),
				options = ReaderMode.entries.map { it.label() },
				selectedIndex = ReaderMode.entries.indexOf(state.mode),
				onSelected = { callbacks.onModeChanged(ReaderMode.entries[it]) },
				iconOnly = true,
				icon = { index ->
					Icon(
						painter = painterResource(ReaderMode.entries[index].iconResId()),
						contentDescription = null,
						modifier = Modifier.size(20.dp),
					)
				}
			)
		}
		item {
			ReaderSegmentedChoice(
				title = stringResource(R.string.pages_animation),
				options = ReaderAnimation.entries.mapIndexed { index, animation ->
					animationLabels.getOrElse(index) { animation.name }
				},
				selectedIndex = ReaderAnimation.entries.indexOf(state.animation),
				onSelected = { callbacks.onAnimationChanged(ReaderAnimation.entries[it]) },
				iconOnly = true,
				icon = { ReaderAnimationIcon(ReaderAnimation.entries[it]) },
			)
		}
		item {
			ReaderSegmentedChoice(
				title = stringResource(R.string.background),
				options = ReaderBackground.entries.mapIndexed { index, background ->
					backgroundLabels.getOrElse(index) { background.name }
				},
				selectedIndex = ReaderBackground.entries.indexOf(state.background),
				onSelected = { callbacks.onBackgroundChanged(ReaderBackground.entries[it]) },
				iconOnly = true,
				icon = { ReaderBackgroundIcon(ReaderBackground.entries[it]) },
			)
		}
		item {
			ReaderOptionGroup {
				ReaderOptionSwitchRow(
					label = stringResource(R.string.double_page_landscape),
					checked = state.doublePage,
					enabled = state.mode == ReaderMode.STANDARD || state.mode == ReaderMode.REVERSED,
					onCheckedChange = callbacks.onDoublePageChanged,
				)
				ReaderOptionDivider()
				ReaderOptionSwitchRow(
					label = stringResource(R.string.double_page_foldable),
					checked = state.doublePageFoldable,
					enabled = state.doublePage,
					onCheckedChange = callbacks.onDoublePageFoldableChanged,
				)
				ReaderOptionDivider()
				ReaderOptionSwitchRow(
					label = stringResource(R.string.double_page_cover_page),
					checked = state.doublePageCover,
					enabled = state.doublePage,
					onCheckedChange = callbacks.onDoublePageCoverChanged,
				)
				ReaderOptionDivider()
				ReaderOptionSwitchRow(
					label = stringResource(R.string.split_double_pages),
					checked = state.splitPages,
					onCheckedChange = callbacks.onSplitPagesChanged,
				)
				if (state.doublePage) {
					ReaderOptionDivider()
					Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
						Row(verticalAlignment = Alignment.CenterVertically) {
							Text(
								text = stringResource(R.string.two_page_scroll_sensitivity),
								style = MaterialTheme.typography.bodyMedium,
								modifier = Modifier.weight(1f),
							)
							Text(
								text = "${(state.doublePageSensitivity * 100).toInt()}%",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.primary,
							)
						}
						Slider(
							value = state.doublePageSensitivity,
							onValueChange = callbacks.onDoublePageSensitivityChanged,
							valueRange = 0f..1f,
						)
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderTranslationOptionsPage(
	callbacks: ComposeReaderOptionsCallbacks,
	translationTaskPanelContent: @Composable () -> Unit,
) {
	fun dismissThen(action: () -> Unit): () -> Unit = {
		callbacks.onDismiss()
		action()
	}
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 12.dp, vertical = 4.dp),
	) {
		OptionsActionGrid {
			OptionAction(R.drawable.ic_translate, R.string.reader_translation_action, dismissThen(callbacks.onTranslation))
			OptionAction(R.drawable.ic_retry, R.string.reader_translation_retranslate_current_page, callbacks.onRetranslatePage)
			OptionAction(R.drawable.ic_retry, R.string.reader_translation_retranslate_current_chapter, callbacks.onRetranslateChapter)
			OptionAction(R.drawable.ic_settings, R.string.reader_translation_action_settings, dismissThen(callbacks.onTranslationSettings))
		}
		ReaderOptionDivider()
		Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
			translationTaskPanelContent()
		}
	}
}

@Composable
private fun ReaderAppearanceOptionsPage(
	state: ComposeReaderOptionsState,
	callbacks: ComposeReaderOptionsCallbacks,
) {
	OptionsPageList {
		item {
			ReaderOptionGroup {
				ReaderImageComparisonPreview(
					originalPreviewModel = state.appearancePreviewOriginalUri,
					processedPreviewModel = state.appearancePreviewProcessedUri,
					colorFilter = state.colorFilter,
					imageScalingQuality = state.imageScalingQuality,
					isLoading = state.appearancePreviewLoading,
					modifier = Modifier.padding(8.dp),
				)
			}
		}
		item {
			ReaderOptionGroup {
				val scalingQualityLabels = ReaderImageScalingQuality.entries.map { it.label() }
				SelectRow(
					title = stringResource(R.string.reader_image_scaling_quality),
					selected = state.imageScalingQuality.label(),
					options = scalingQualityLabels,
					onSelected = {
						callbacks.onImageScalingQualityChanged(ReaderImageScalingQuality.entries[it])
					},
				)
				ReaderOptionDivider()
				ReaderOptionSwitchRow(
					label = stringResource(R.string.reader_super_resolution),
					checked = state.superResolution,
					onCheckedChange = callbacks.onSuperResolutionChanged,
				)
			}
		}
		item {
			ReaderColorCorrectionControls(
				colorFilter = state.colorFilter,
				isLoading = state.appearancePreviewLoading,
				onColorFilterChange = callbacks.onColorFilterChanged,
				onReset = { callbacks.onColorFilterChanged(null) },
			)
		}
		item {
			ReaderOptionGroup {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(min = 52.dp)
						.padding(horizontal = 4.dp),
				) {
					Text(
						text = stringResource(R.string.save),
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.Medium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.weight(1f).padding(start = 8.dp),
					)
					TextButton(onClick = { callbacks.onSaveColorFilterGlobally(state.colorFilter) }) {
						Text(stringResource(R.string.globally))
					}
					TextButton(onClick = { callbacks.onSaveColorFilterForManga(state.colorFilter) }) {
						Text(stringResource(R.string.this_manga))
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderMiscOptionsPage(
	state: ComposeReaderOptionsState,
	callbacks: ComposeReaderOptionsCallbacks,
) {
	fun dismissThen(action: () -> Unit): () -> Unit = {
		callbacks.onDismiss()
		action()
	}
	OptionsPageList {
		item {
			OptionsActionGrid {
				OptionAction(R.drawable.ic_save, R.string.save_page, dismissThen(callbacks.onSavePage))
				OptionAction(R.drawable.ic_prev, R.string.prev_chapter, dismissThen(callbacks.onPreviousChapter))
				OptionAction(R.drawable.ic_next, R.string.next_chapter, dismissThen(callbacks.onNextChapter))
				OptionAction(R.drawable.ic_grid, R.string.chapters_and_pages, dismissThen(callbacks.onPages))
				OptionAction(R.drawable.ic_bookmark, R.string.bookmark_add, dismissThen(callbacks.onBookmark))
				OptionAction(R.drawable.ic_download, R.string.download, dismissThen(callbacks.onDownload))
				OptionAction(R.drawable.ic_screen_rotation, R.string.rotate_screen, dismissThen(callbacks.onRotate))
				OptionAction(R.drawable.ic_timer, R.string.automatic_scroll, dismissThen(callbacks.onAutoScroll))
				OptionAction(R.drawable.ic_web, R.string.open_in_browser, dismissThen(callbacks.onOpenBrowser))
				OptionAction(R.drawable.ic_settings, R.string.settings, dismissThen(callbacks.onOpenSettings))
			}
		}
		state.imageServer?.let { imageServer ->
			item {
				val automatic = stringResource(R.string.automatic)
				val labels = imageServer.entries.map { it.label ?: automatic }
				val selected = imageServer.entries.indexOfFirst {
					it.value == imageServer.selectedValue
				}.coerceAtLeast(0)
				SelectRow(
					title = stringResource(R.string.image_server),
					selected = labels.getOrElse(selected) { automatic },
					options = labels,
					onSelected = { callbacks.onImageServerChanged(imageServer.entries[it].value) },
				)
			}
		}
	}
}

@Composable
private fun OptionsPageList(
	content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
	LazyColumn(
		verticalArrangement = Arrangement.spacedBy(6.dp),
		modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
		content = content,
	)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsActionGrid(
	content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit,
) {
	FlowRow(
		maxItemsInEachRow = 2,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.fillMaxWidth().padding(10.dp),
		content = content,
	)
}

@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.OptionAction(
	icon: Int,
	label: Int,
	onClick: () -> Unit,
) {
	Surface(
		onClick = onClick,
		shape = MaterialTheme.shapes.medium,
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		modifier = Modifier
			.weight(1f)
			.height(52.dp),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painterResource(icon),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(22.dp),
			)
			Text(
				stringResource(label),
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(start = 10.dp),
			)
		}
	}
}

@Composable
private fun SelectRow(
	title: String,
	selected: String,
	options: List<String>,
	onSelected: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	var expanded by remember { mutableStateOf(false) }
	Box(modifier = modifier) {
		ReaderOptionValueRow(
			label = title,
			value = selected,
			onClick = { expanded = true },
		)
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			options.forEachIndexed { index, option ->
				DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelected(index) })
			}
		}
	}
}

@Composable
private fun ReaderMode.label(): String = stringResource(
	when (this) {
		ReaderMode.STANDARD -> R.string.standard
		ReaderMode.REVERSED -> R.string.right_to_left
		ReaderMode.VERTICAL -> R.string.vertical
		ReaderMode.WEBTOON -> R.string.webtoon
	},
)

@Composable
private fun ReaderImageScalingQuality.label(): String = stringResource(
	when (this) {
		ReaderImageScalingQuality.NEAREST -> R.string.reader_image_scaling_nearest
		ReaderImageScalingQuality.BILINEAR -> R.string.reader_image_scaling_bilinear
		ReaderImageScalingQuality.DEFAULT -> R.string.reader_image_scaling_default
		ReaderImageScalingQuality.BICUBIC -> R.string.reader_image_scaling_bicubic
		ReaderImageScalingQuality.LANCZOS -> R.string.reader_image_scaling_lanczos
	},
)

private fun ReaderMode.iconResId(): Int = when (this) {
	ReaderMode.STANDARD -> R.drawable.ic_reader_ltr
	ReaderMode.REVERSED -> R.drawable.ic_reader_rtl
	ReaderMode.VERTICAL -> R.drawable.ic_reader_vertical
	ReaderMode.WEBTOON -> R.drawable.ic_gesture_vertical
}

@Composable
internal fun ReaderAnimationIcon(animation: ReaderAnimation) {
	val color = androidx.compose.material3.LocalContentColor.current
	Canvas(modifier = Modifier.size(20.dp)) {
		val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
		val left = size.width * 0.18f
		val top = size.height * 0.16f
		val right = size.width * 0.82f
		val bottom = size.height * 0.84f
		when (animation) {
			ReaderAnimation.NONE -> {
				drawRoundRect(
					color = color,
					topLeft = Offset(left, top),
					size = Size(right - left, bottom - top),
					cornerRadius = CornerRadius(2.dp.toPx()),
					style = stroke,
				)
				drawLine(
					color = color,
					start = Offset(left, bottom),
					end = Offset(right, top),
					strokeWidth = stroke.width,
					cap = StrokeCap.Round,
				)
			}
			ReaderAnimation.DEFAULT -> {
				drawRoundRect(
					color = color,
					topLeft = Offset(left, top),
					size = Size(size.width * 0.46f, bottom - top),
					cornerRadius = CornerRadius(2.dp.toPx()),
					style = stroke,
				)
				val arrowEnd = Offset(right, size.height * 0.5f)
				drawLine(
					color,
					Offset(size.width * 0.62f, size.height * 0.5f),
					arrowEnd,
					stroke.width,
					StrokeCap.Round,
				)
				drawLine(
					color,
					Offset(size.width * 0.72f, size.height * 0.4f),
					arrowEnd,
					stroke.width,
					StrokeCap.Round,
				)
				drawLine(
					color,
					Offset(size.width * 0.72f, size.height * 0.6f),
					arrowEnd,
					stroke.width,
					StrokeCap.Round,
				)
			}
			ReaderAnimation.ADVANCED -> {
				repeat(3) { index ->
					val offset = index * size.width * 0.12f
					drawRoundRect(
						color = color,
						topLeft = Offset(left + offset, top + offset * 0.35f),
						size = Size(size.width * 0.44f, size.height * 0.58f),
						cornerRadius = CornerRadius(2.dp.toPx()),
						style = stroke,
					)
				}
			}
			ReaderAnimation.SIMULATION -> {
				val path = Path().apply {
					moveTo(left, top)
					lineTo(size.width * 0.56f, top)
					cubicTo(right, size.height * 0.28f, right, size.height * 0.7f, size.width * 0.58f, bottom)
					lineTo(left, bottom)
					close()
				}
				drawPath(path, color, style = stroke)
				drawLine(
					color = color,
					start = Offset(size.width * 0.58f, bottom),
					end = Offset(right, size.height * 0.68f),
					strokeWidth = stroke.width,
					cap = StrokeCap.Round,
				)
			}
		}
	}
}

@Composable
private fun ReaderBackgroundIcon(background: ReaderBackground) {
	val colors = MaterialTheme.colorScheme
	val outline = androidx.compose.material3.LocalContentColor.current
	Canvas(modifier = Modifier.size(24.dp)) {
		val radius = size.minDimension * 0.38f
		val glyphTopLeft = Offset(
			x = center.x - radius,
			y = center.y - radius,
		)
		val glyphSize = Size(radius * 2f, radius * 2f)
		when (background) {
			ReaderBackground.DEFAULT -> {
				drawArc(
					color = colors.surface,
					startAngle = -90f,
					sweepAngle = 180f,
					useCenter = true,
					topLeft = glyphTopLeft,
					size = glyphSize,
				)
				drawArc(
					color = colors.onSurface,
					startAngle = 90f,
					sweepAngle = 180f,
					useCenter = true,
					topLeft = glyphTopLeft,
					size = glyphSize,
				)
			}
			ReaderBackground.LIGHT -> drawCircle(colors.surfaceBright, radius)
			ReaderBackground.DARK -> drawCircle(colors.surfaceDim, radius)
			ReaderBackground.WHITE -> drawCircle(Color.White, radius)
			ReaderBackground.BLACK -> drawCircle(Color.Black, radius)
			ReaderBackground.AUTO -> {
				drawCircle(colors.primaryContainer, radius)
				drawCircle(colors.primary, radius * 0.42f)
			}
		}
		drawCircle(outline, radius, center, style = Stroke(width = 1.5.dp.toPx()))
	}
}
