package org.skepsun.kototoro.settings.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.prefs.InterfaceStyle

internal val SettingsContentHorizontalPadding = CompactTopBarHorizontalPadding
private val SettingsTopBarBottomExtension = 6.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBarScaffold(
	title: String,
	onNavigateUp: (() -> Unit)?,
	modifier: Modifier = Modifier,
	searchContent: (@Composable () -> Unit)? = null,
	actions: (@Composable BoxScope.() -> Unit)? = null,
	content: @Composable (PaddingValues) -> Unit,
) {
	val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
	val backdropColor = settingsScreenBackgroundColor()
	val contentBackdrop = if (isIosStyle) {
		rememberLayerBackdrop {
			drawRect(backdropColor)
			drawContent()
		}
	} else {
		null
	}
	CompositionLocalProvider(
		LocalLiquidGlassBackdrop provides contentBackdrop,
		LocalLiquidGlassLayerBackdrop provides contentBackdrop,
	) {
		Scaffold(
			modifier = modifier.fillMaxSize(),
			topBar = {
				searchContent?.invoke() ?: SettingsSeparatedTopAppBar(
					title = title,
					onNavigateUp = onNavigateUp,
					actions = actions,
				)
			},
			containerColor = backdropColor,
			contentWindowInsets = WindowInsets(0, 0, 0, 0),
			content = { innerPadding ->
				Box(
					modifier = Modifier
						.fillMaxSize()
						.then(contentBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
				) {
					CompositionLocalProvider(
						LocalLiquidGlassBackdrop provides null,
						LocalLiquidGlassLayerBackdrop provides null,
					) {
						content(innerPadding)
					}
				}
			},
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchTopBarAction(
	onStartSearch: () -> Unit,
) {
	SettingsTopBarIconButton(onClick = onStartSearch) {
		val tokens = LocalInterfaceStyleTokens.current
		Icon(
			painter = rememberSafePainter(androidx.appcompat.R.drawable.abc_ic_search_api_material),
			contentDescription = stringResource(R.string.search),
			modifier = Modifier.size(tokens.topBarIconSize),
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchTopAppBar(
	query: String,
	onNavigateUp: () -> Unit,
	onQueryChange: (String) -> Unit,
) {
	BackHandler(onBack = onNavigateUp)

	val colorScheme = MaterialTheme.colorScheme
	val tokens = LocalInterfaceStyleTokens.current
	val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
	SettingsTopBarSurface {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(tokens.secondaryTopBarHeight),
			horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
			verticalAlignment = Alignment.CenterVertically,
		) {
			SettingsTopBarIconButton(onClick = onNavigateUp) {
				Icon(
					imageVector = Icons.AutoMirrored.Filled.ArrowBack,
					contentDescription = null,
					modifier = Modifier.size(tokens.topBarIconSize),
				)
			}
			TextField(
				value = query,
				onValueChange = onQueryChange,
				modifier = Modifier
					.weight(1f)
					.height(tokens.secondaryTopBarHeight)
					.then(
						if (isIosStyle) {
							Modifier.border(
								width = 1.dp,
								color = colorScheme.outlineVariant.copy(alpha = 0.34f),
								shape = RoundedCornerShape(14.dp),
							)
						} else {
							Modifier
						},
					),
				singleLine = true,
				shape = if (isIosStyle) RoundedCornerShape(14.dp) else MaterialTheme.shapes.extraSmall,
				placeholder = {
					Text(
						text = stringResource(R.string.search),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				},
				colors = TextFieldDefaults.colors(
					focusedContainerColor = if (isIosStyle) {
						colorScheme.surfaceContainerLow.copy(alpha = 0.78f)
					} else {
						colorScheme.surfaceContainerHigh.copy(alpha = 0.80f)
					},
					unfocusedContainerColor = if (isIosStyle) {
						colorScheme.surfaceContainerLow.copy(alpha = 0.78f)
					} else {
						colorScheme.surfaceContainerHigh.copy(alpha = 0.80f)
					},
					disabledContainerColor = if (isIosStyle) {
						colorScheme.surfaceContainerLow.copy(alpha = 0.78f)
					} else {
						colorScheme.surfaceContainerHigh.copy(alpha = 0.80f)
					},
					focusedIndicatorColor = Color.Transparent,
					unfocusedIndicatorColor = Color.Transparent,
				),
			)
		}
	}
}

@Composable
private fun SettingsSeparatedTopAppBar(
	title: String,
	onNavigateUp: (() -> Unit)?,
	actions: (@Composable BoxScope.() -> Unit)?,
) {
	val colorScheme = MaterialTheme.colorScheme
	val tokens = LocalInterfaceStyleTokens.current
	SettingsTopBarSurface {
		if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(tokens.secondaryTopBarHeight),
			) {
				if (onNavigateUp != null) {
					Box(modifier = Modifier.align(Alignment.CenterStart)) {
						SettingsTopBarIconButton(onClick = onNavigateUp) {
							Icon(
								imageVector = Icons.AutoMirrored.Filled.ArrowBack,
								contentDescription = null,
								modifier = Modifier.size(tokens.topBarIconSize),
							)
						}
					}
				}
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					color = colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier
						.align(Alignment.Center)
						.padding(horizontal = 64.dp),
				)
				if (actions != null) {
					Box(
						modifier = Modifier.align(Alignment.CenterEnd),
						contentAlignment = Alignment.CenterEnd,
						content = actions,
					)
				}
			}
		} else {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(tokens.secondaryTopBarHeight),
				horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (onNavigateUp != null) {
					SettingsTopBarIconButton(onClick = onNavigateUp) {
						Icon(
							imageVector = Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = null,
							modifier = Modifier.size(tokens.topBarIconSize),
						)
					}
				}
				Text(
					text = title,
					style = MaterialTheme.typography.titleLarge,
					color = colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				if (actions != null) {
					Box(
						contentAlignment = Alignment.CenterEnd,
						content = actions,
					)
				}
			}
		}
	}
}

@Composable
private fun SettingsTopBarSurface(content: @Composable () -> Unit) {
	val topBarContent: @Composable () -> Unit = {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.statusBarsPadding()
				.padding(
					start = CompactTopBarHorizontalPadding,
					end = CompactTopBarHorizontalPadding,
				)
				.padding(bottom = SettingsTopBarBottomExtension),
			content = { content() },
		)
	}
	if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
		GlassSurface(
			modifier = Modifier.fillMaxWidth(),
			style = GlassDefaults.topBarChromeStyle().copy(shadowElevation = 0.dp),
			shape = RoundedCornerShape(0.dp),
			componentRole = GlassComponentRole.TopBar,
		) {
			topBarContent()
		}
	} else {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.background(settingsScreenBackgroundColor()),
		) {
			topBarContent()
		}
	}
}

@Composable
fun SettingsTopBarIconButton(
	onClick: () -> Unit,
	content: @Composable () -> Unit,
) {
	val tokens = LocalInterfaceStyleTokens.current
	Surface(
		onClick = onClick,
		modifier = Modifier.size(tokens.minimumTouchTarget),
		shape = CircleShape,
		color = Color.Transparent,
	) {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center,
		) {
			Surface(
				modifier = Modifier.size(tokens.topBarButtonSize),
				shape = CircleShape,
				color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
				border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)),
			) {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center,
					content = { content() },
				)
			}
		}
	}
}
