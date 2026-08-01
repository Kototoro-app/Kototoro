package org.skepsun.kototoro.reader.novel.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeGradient
import org.skepsun.kototoro.core.ui.compose.toTransparentImmersiveColor
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.ReadingMode
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import org.skepsun.kototoro.reader.novel.tts.TtsState
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlTokens
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressDock

private val NovelTopGradientExtension = 72.dp
private val NovelBottomGradientExtension = 48.dp
private val NovelTopGradientStops = listOf(0f, 0.24f, 0.50f, 0.70f, 0.86f, 1f)
private val NovelBottomGradientStops = listOf(0f, 0.18f, 0.38f, 0.70f, 1f)

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
	val immersiveBaseColor = if (isSystemInDarkTheme()) Color.Black else Color.White
	val topHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 76.dp
	AnimatedVisibility(
		visible = state.controlsVisible,
		enter = slideInVertically { -it },
		exit = slideOutVertically { -it },
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(topHeight + NovelTopGradientExtension),
		) {
			ImmersiveEdgeGradient(
				height = topHeight + NovelTopGradientExtension,
				colors = listOf(
					immersiveBaseColor.copy(alpha = 0.86f),
					immersiveBaseColor.copy(alpha = 0.62f),
					immersiveBaseColor.copy(alpha = 0.32f),
					immersiveBaseColor.copy(alpha = 0.12f),
					immersiveBaseColor.copy(alpha = 0.035f),
					immersiveBaseColor.toTransparentImmersiveColor(),
				),
				stops = NovelTopGradientStops,
				modifier = Modifier.fillMaxWidth(),
			)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.statusBarsPadding()
					.padding(horizontal = 14.dp, vertical = 6.dp),
			) {
				NovelTopControlSurface(
					shape = CircleShape,
					modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
				) {
					IconButton(onClick = callbacks.onNavigateBack) {
						Icon(
							imageVector = Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.back),
							tint = contentColor,
						)
					}
				}
				NovelTopControlSurface(
					shape = RoundedCornerShape(24.dp),
					modifier = Modifier
						.align(Alignment.Center)
						.widthIn(min = 148.dp, max = 176.dp)
						.height(48.dp)
						.clickable(onClick = callbacks.onShowChapters),
				) {
					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
					) {
						Text(
							text = state.workTitle,
							color = contentColor,
							style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						if (state.chapterTitle.isNotBlank()) {
							Text(
								text = state.chapterTitle,
								color = contentColor.copy(alpha = 0.78f),
								style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
				}
				NovelTopControlSurface(
					shape = CircleShape,
					modifier = Modifier.align(Alignment.CenterEnd).size(48.dp),
				) {
					IconButton(onClick = callbacks.onShowSettings) {
						Icon(
							imageVector = Icons.Default.MoreVert,
							contentDescription = stringResource(R.string.options),
							tint = contentColor,
						)
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReaderBottomChrome(
	state: NovelComposeReaderUiState,
	callbacks: NovelReaderChromeCallbacks,
) {
	val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
	val toolsPanelVisible = state.toolsSheetVisible || state.ttsControlsVisible
	val dismissiblePanelVisible = state.settingsSheetVisible || state.chaptersSheetVisible || toolsPanelVisible
	BackHandler(enabled = dismissiblePanelVisible) {
		when {
			state.settingsSheetVisible -> callbacks.onDismissSettings()
			state.chaptersSheetVisible -> callbacks.onDismissChapters()
			toolsPanelVisible -> callbacks.onDismissTools()
		}
	}

	Box(
		contentAlignment = Alignment.BottomCenter,
		modifier = Modifier.fillMaxWidth().height(84.dp + NovelBottomGradientExtension),
	) {
		AnimatedVisibility(
			visible = state.controlsVisible && state.progressMax > 0f,
			// Alpha transitions clip the rounded Backdrop shadow to a rectangular layer.
			enter = slideInVertically { it },
			exit = slideOutVertically { it },
			modifier = Modifier.align(Alignment.BottomCenter),
		) {
			val immersiveBaseColor = if (isSystemInDarkTheme()) Color.Black else Color.White
			Box(
				contentAlignment = Alignment.BottomCenter,
				modifier = Modifier.fillMaxWidth().height(84.dp + NovelBottomGradientExtension),
			) {
				ImmersiveEdgeGradient(
					height = 84.dp + NovelBottomGradientExtension,
					colors = listOf(
						immersiveBaseColor.toTransparentImmersiveColor(),
						immersiveBaseColor.copy(alpha = 0.035f),
						immersiveBaseColor.copy(alpha = 0.16f),
						immersiveBaseColor.copy(alpha = 0.42f),
						immersiveBaseColor.copy(alpha = 0.78f),
					),
					stops = NovelBottomGradientStops,
					modifier = Modifier.fillMaxWidth(),
				)
				ReaderProgressDock(
					isIosStyle = isIosStyle,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
				) {
					NovelProgressPanel(state, callbacks, isIosStyle)
				}
			}
		}

		val statusSettings = state.settings
		AnimatedVisibility(
			visible = !state.controlsVisible &&
				statusSettings?.showReadingStatus == true &&
				statusSettings.readingMode != ReadingMode.PAGED,
			enter = fadeIn(),
			exit = fadeOut(),
			modifier = Modifier.align(Alignment.BottomCenter),
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

	if (state.chaptersSheetVisible) {
		ComposeNovelChaptersSheet(
			chapters = state.chapters,
			currentIndex = state.currentChapterIndex,
			onDismiss = callbacks.onDismissChapters,
			onChapterSelected = callbacks.onChapterSelected,
		)
	}
	state.settings?.let { settings ->
		if (state.settingsSheetVisible) {
			ComposeNovelReaderOptionsSheet(
				settings = settings,
				onDismiss = callbacks.onDismissSettings,
				onSettingsChanged = callbacks.onSettingsChanged,
				onToggleTranslation = callbacks.onToggleTranslation,
				onBookmark = callbacks.onBookmark,
				onTts = callbacks.onTts,
				onClearTranslationCache = callbacks.onClearTranslationCache,
			)
		}
	}
	if (toolsPanelVisible) {
		ModalBottomSheet(onDismissRequest = callbacks.onDismissTools) {
			NovelToolsPanel(state, callbacks)
		}
	}
}

@Composable
private fun NovelTopControlSurface(
	shape: androidx.compose.ui.graphics.Shape,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	GlassSurface(
		modifier = modifier,
		shape = shape,
		style = GlassDefaults.topBarChromeStyle().copy(
			containerAlpha = 0.84f,
			shadowElevation = ReaderControlTokens.ChromeShadowElevation,
		),
		componentRole = GlassComponentRole.TopBar,
	) {
		Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
			content()
		}
	}
}

@Composable
private fun NovelProgressPanel(
	state: NovelComposeReaderUiState,
	callbacks: NovelReaderChromeCallbacks,
	isIosStyle: Boolean,
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
		isIosStyle = isIosStyle,
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
