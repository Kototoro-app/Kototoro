package org.skepsun.kototoro.settings.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import com.kyant.shapes.RoundedRectangle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    title: String?,
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

    val tokens = LocalInterfaceStyleTokens.current
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
            SettingsCompactSearchField(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier
                    .weight(1f),
                autofocus = true,
            )
        }
    }
}

@Composable
internal fun SettingsCompactSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(query)) }
    // 受控 value（String 重载）会在外部异步回传时重置内部 selection，出现
    // “首字符跑到光标后面 / 移动光标后再输入又错位”。这里本地持有
    // TextFieldValue：onValueChange 原样保留用户 selection，避免与 IME 竞争。
    // 外部 query 仅在真正的“替换”时才同步回本地（否则异步回显比用户输入慢时
    // 会误把刚打的字符抹掉）：
    //  - query 为空而本地还有字 → 外部清空（退出搜索/清除）
    //  - query 非空且既不包含本地、本地也不以其开头 → 外部注入新值
    LaunchedEffect(query) {
        val localText = textFieldValue.text
        val shouldSync = when {
            query.isEmpty() && localText.isNotEmpty() -> true
            query.isNotEmpty() &&
                !localText.startsWith(query) &&
                !query.startsWith(localText) -> true
            else -> false
        }
        if (shouldSync) {
            textFieldValue = TextFieldValue(query)
        }
    }
    LaunchedEffect(autofocus) {
        if (autofocus) {
            focusRequester.requestFocus()
        }
    }
    Surface(
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue: TextFieldValue ->
                textFieldValue = newValue
                onQueryChange(newValue.text)
            },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )
    }
}

@Composable
private fun SettingsSeparatedTopAppBar(
    title: String?,
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
                if (title != null) {
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
                }
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
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
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
internal fun SettingsTopBarSurface(content: @Composable () -> Unit) {
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
            shape = RoundedRectangle(0.dp),
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
