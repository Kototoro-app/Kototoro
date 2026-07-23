package org.skepsun.kototoro.reader.novel.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import org.skepsun.kototoro.reader.novel.tts.TtsState
import org.skepsun.kototoro.reader.ui.ReaderToolbarChrome
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlItem
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPrimaryControlBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressBar

internal data class NovelReaderChromeCallbacks(
	val onNavigateBack: () -> Unit = {},
	val onProgressSelected: (Int) -> Unit = {},
	val onPreviousChapter: () -> Unit = {},
	val onNextChapter: () -> Unit = {},
	val onSettingsChanged: (NovelReaderSettings) -> Unit = {},
	val onChapterSelected: (Int) -> Unit = {},
	val onDismissSettings: () -> Unit = {},
	val onDismissChapters: () -> Unit = {},
	val onDismissTools: () -> Unit = {},
	val onShowSettings: () -> Unit = {},
	val onShowChapters: () -> Unit = {},
	val onShowTools: () -> Unit = {},
	val onToggleTranslation: () -> Unit = {},
	val onBookmark: () -> Unit = {},
	val onTts: () -> Unit = {},
	val onClearTranslationCache: () -> Unit = {},
	val onTtsPrevious: () -> Unit = {},
	val onTtsPlayPause: () -> Unit = {},
	val onTtsNext: () -> Unit = {},
	val onTtsVoice: () -> Unit = {},
	val onTtsClose: () -> Unit = {},
)

@Composable
internal fun NovelReaderTopChrome(
	state: NovelComposeReaderUiState,
	callbacks: NovelReaderChromeCallbacks,
) {
	val contentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
	AnimatedVisibility(
		visible = state.controlsVisible,
		enter = slideInVertically { -it } + fadeIn(),
		exit = slideOutVertically { -it } + fadeOut(),
	) {
		androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
			ReaderToolbarChrome()
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.fillMaxWidth()
					.statusBarsPadding()
					.padding(horizontal = 18.dp, vertical = 4.dp),
			) {
				IconButton(onClick = callbacks.onNavigateBack, modifier = Modifier.size(44.dp)) {
					Icon(
						imageVector = Icons.AutoMirrored.Filled.ArrowBack,
						contentDescription = stringResource(R.string.back),
						modifier = Modifier.size(21.dp),
						tint = contentColor,
					)
				}
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = state.workTitle,
						color = contentColor,
						style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					if (state.chapterTitle.isNotBlank()) {
						Text(
							text = state.chapterTitle,
							color = contentColor.copy(alpha = 0.78f),
							style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}
		}
	}
}

@Composable
internal fun NovelReaderBottomChrome(
	state: NovelComposeReaderUiState,
	callbacks: NovelReaderChromeCallbacks,
) {
	var progressExpanded by rememberSaveable { mutableStateOf(true) }
	val visible = state.controlsVisible || state.ttsControlsVisible
	val toolsPanelVisible = state.toolsSheetVisible || state.ttsControlsVisible
	val dismissiblePanelVisible =
		state.settingsSheetVisible ||
			state.chaptersSheetVisible ||
			toolsPanelVisible
	BackHandler(enabled = dismissiblePanelVisible) {
		when {
			state.settingsSheetVisible -> callbacks.onDismissSettings()
			state.chaptersSheetVisible -> callbacks.onDismissChapters()
			toolsPanelVisible -> {
				callbacks.onDismissTools()
			}
		}
	}
	AnimatedVisibility(
		visible = visible,
		enter = slideInVertically { it } + fadeIn(),
		exit = slideOutVertically { it } + fadeOut(),
	) {
		androidx.compose.foundation.layout.Box(
			contentAlignment = Alignment.BottomCenter,
			modifier = Modifier.fillMaxWidth(),
		) {
			GlassSurface(
				modifier = Modifier
					.padding(horizontal = 12.dp, vertical = 4.dp)
					.fillMaxWidth()
					.widthIn(max = 360.dp)
					.animateContentSize(alignment = Alignment.BottomCenter),
				shape = RoundedCornerShape(28.dp),
				style = GlassDefaults.bottomBarChromeStyle().copy(
					containerAlpha = 0.86f,
					borderAlpha = 0.18f,
				),
				componentRole = GlassComponentRole.BottomBar,
			) {
				CompositionLocalProvider(
					LocalContentColor provides MaterialTheme.colorScheme.onSurface,
				) {
					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
					) {
						when {
						state.chaptersSheetVisible -> {
							ComposeNovelChaptersPanel(
								chapters = state.chapters,
								currentIndex = state.currentChapterIndex,
								onChapterSelected = callbacks.onChapterSelected,
							)
							NovelChromeDivider()
						}
						state.settingsSheetVisible && state.settings != null -> {
							ComposeNovelReaderOptionsPanel(
								settings = state.settings,
								onSettingsChanged = callbacks.onSettingsChanged,
							)
							NovelChromeDivider()
						}
						toolsPanelVisible -> {
							NovelToolsPanel(state, callbacks)
							NovelChromeDivider()
						}
						progressExpanded && state.progressMax > 0f -> {
							NovelProgressPanel(state, callbacks)
							NovelChromeDivider()
						}
						}
						if (state.controlsVisible) {
							ReaderPrimaryControlBar(
								items = listOf(
							ReaderControlItem(
								ReaderControlDestination.PROGRESS,
								stringResource(R.string.progress),
								R.drawable.ic_progress_marker,
								active = progressExpanded &&
									!state.settingsSheetVisible &&
									!state.chaptersSheetVisible &&
									!toolsPanelVisible,
							),
							ReaderControlItem(
								ReaderControlDestination.NAVIGATION,
								stringResource(R.string.chapters),
								R.drawable.ic_grid,
								active = state.chaptersSheetVisible,
							),
							ReaderControlItem(
								ReaderControlDestination.DISPLAY,
								stringResource(R.string.appearance),
								R.drawable.ic_appearance,
								active = state.settingsSheetVisible,
							),
							ReaderControlItem(
								ReaderControlDestination.TOOLS,
								stringResource(R.string.reader_actions),
								R.drawable.ic_more_vert,
								active = toolsPanelVisible,
							),
						),
						onDestinationSelected = { destination ->
							when (destination) {
								ReaderControlDestination.PROGRESS -> {
									callbacks.onDismissSettings()
									callbacks.onDismissChapters()
									callbacks.onDismissTools()
									progressExpanded = !progressExpanded
								}
								ReaderControlDestination.NAVIGATION -> {
									callbacks.onDismissSettings()
									callbacks.onDismissTools()
									progressExpanded = false
									if (state.chaptersSheetVisible) callbacks.onDismissChapters() else callbacks.onShowChapters()
								}
								ReaderControlDestination.DISPLAY -> {
									callbacks.onDismissChapters()
									callbacks.onDismissTools()
									progressExpanded = false
									if (state.settingsSheetVisible) callbacks.onDismissSettings() else callbacks.onShowSettings()
								}
								ReaderControlDestination.TOOLS -> {
									callbacks.onDismissSettings()
									callbacks.onDismissChapters()
									progressExpanded = false
									if (toolsPanelVisible) {
										callbacks.onDismissTools()
									} else {
										callbacks.onShowTools()
									}
								}
								else -> Unit
							}
						},
								transparentContainer = true,
								showLabels = true,
								modifier = Modifier.widthIn(max = 352.dp),
							)
						}
					}
				}
			}
		}
	}
	val statusSettings = state.settings
	AnimatedVisibility(
		visible = !state.controlsVisible && statusSettings?.showReadingStatus == true,
		enter = fadeIn(),
		exit = fadeOut(),
	) {
		val palette = novelReaderPalette(
			statusSettings?.themePreset ?: return@AnimatedVisibility,
			isSystemInDarkTheme(),
		)
		val statusTextColor = Color(palette.chromeTextColor).copy(alpha = 0.78f)
		val statusBackground = if (statusSettings.isReadingStatusTransparent) {
			Color.Transparent
		} else {
			Color(palette.chromeBackgroundColor).copy(alpha = 0.72f)
		}
		Surface(
			color = statusBackground,
			contentColor = statusTextColor,
			modifier = Modifier.fillMaxWidth(),
		) {
			Row(
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
			) {
				Text(
					text = state.chapterTitle,
					color = statusTextColor,
					style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				if (state.progressLabel.isNotBlank()) {
					Text(
						text = state.progressLabel,
						color = statusTextColor,
						style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
						modifier = Modifier.padding(start = 10.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun NovelProgressPanel(
	state: NovelComposeReaderUiState,
	callbacks: NovelReaderChromeCallbacks,
) {
	var selectedValue by remember(state.progressValue) { mutableFloatStateOf(state.progressValue) }
	ReaderProgressBar(
		value = selectedValue,
		max = state.progressMax,
		onValueChange = { selectedValue = it },
		onValueChangeFinished = { callbacks.onProgressSelected(selectedValue.toInt()) },
		onPreviousChapter = callbacks.onPreviousChapter,
		onNextChapter = callbacks.onNextChapter,
		previousEnabled = state.currentChapterIndex > 0,
		nextEnabled = state.currentChapterIndex < state.chapters.lastIndex,
		isIosStyle = true,
	)
}

@Composable
private fun NovelToolsPanel(
	state: NovelComposeReaderUiState,
	callbacks: NovelReaderChromeCallbacks,
) {
	Column(
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
	) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
			NovelToolButton(R.drawable.ic_translate, R.string.reader_translation_action, callbacks.onToggleTranslation)
			NovelToolButton(R.drawable.ic_bookmark, R.string.bookmark_add, callbacks.onBookmark)
			NovelToolButton(R.drawable.ic_voice_input, R.string.tts_settings_title, callbacks.onTts)
			NovelToolButton(R.drawable.ic_delete, R.string.clear_translation_cache, callbacks.onClearTranslationCache)
		}
		if (state.ttsControlsVisible) {
			HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
			Row(
				horizontalArrangement = Arrangement.SpaceEvenly,
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.fillMaxWidth(),
			) {
				IconButton(onClick = callbacks.onTtsPrevious) {
					Icon(painterResource(R.drawable.ic_prev), stringResource(R.string.prev_page))
				}
				IconButton(onClick = callbacks.onTtsPlayPause) {
					Icon(
						painterResource(if (state.ttsState == TtsState.PLAYING) R.drawable.ic_pause else R.drawable.ic_play),
						stringResource(if (state.ttsState == TtsState.PLAYING) R.string.pause else R.string.play),
					)
				}
				IconButton(onClick = callbacks.onTtsNext) {
					Icon(painterResource(R.drawable.ic_next), stringResource(R.string.next))
				}
				IconButton(onClick = callbacks.onTtsVoice) {
					Icon(painterResource(R.drawable.ic_voice_input), stringResource(R.string.tts_settings_title))
				}
				IconButton(onClick = callbacks.onTtsClose) {
					Icon(painterResource(R.drawable.ic_tts_close), stringResource(R.string.close))
				}
			}
		}
	}
}

@Composable
private fun RowScope.NovelToolButton(icon: Int, label: Int, onClick: () -> Unit) {
	FilledTonalButton(
		onClick = onClick,
		contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
		modifier = Modifier.weight(1f),
	) {
		Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(18.dp))
	}
}

@Composable
private fun NovelChromeDivider() {
	HorizontalDivider(
		modifier = Modifier.padding(horizontal = 12.dp),
		color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
	)
}
