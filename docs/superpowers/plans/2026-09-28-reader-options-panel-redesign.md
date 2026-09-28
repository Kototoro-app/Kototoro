# 阅读器「更多」面板重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把漫画 / 小说阅读器的「更多」面板重做为「快捷层 + 3 个详细 tab」的面板：在阅读器窗口内渲染，iOS 风格下使用透出阅读页的玻璃，并跟随阅读背景 / 主题取色。

**Architecture:** 从 `StableAnchoredBottomSheet` 抽出不依赖 Dialog 的 `StableAnchoredSheetLayout`（新增 Peek 锚点）；新增 `ReaderPanelColors`（从阅读背景 / 主题派生，并生成完整 `ColorScheme`）和一组面板组件；`ReaderOptionsPanelHost` 负责表面（玻璃 / 不透明 / E-ink）与 Peek 测量；两个阅读器的 sheet 改写为「快捷层 + tab」并挂到阅读器根部。

**Tech Stack:** Kotlin 2.4, Jetpack Compose (foundation / material3), Kyant Backdrop (`GlassSurface`), JUnit5（`org.junit.jupiter.api`）。

**Spec:** `docs/superpowers/specs/2026-09-28-reader-options-panel-redesign-design.md`

**对 spec 的细化（实现时以本计划为准，Task 13 会同步回 spec）：**
- tab 选中态使用颜色过渡动画（`animateColorAsState`），不做滑动指示器；tab 只显示文字。
- Stepper 不做长按连发，改为点击数值展开内联滑块。
- 漫画翻译 tile 以 `state.actions.translateRequestedVisible` 判断是否可用，开关状态取 `state.actions.translateActive`。
- 小说亮度滑块始终可拖；拖动即关闭「跟随系统」。

**通用约定：**
- 包根：`app/src/main/kotlin/org/skepsun/kototoro/`（下文简写 `main/`），测试根：`app/src/test/kotlin/org/skepsun/kototoro/`（简写 `test/`）。
- 单元测试环境为 `unitTests.returnDefaultValues true`：`android.graphics.Color.parseColor` 等 Android API 在 JVM 测试中返回 0，因此所有被测纯函数只接收 Compose `Color` / 基本类型。
- 运行单测：`./gradlew :app:testDebugUnitTest --tests "<FQCN>" --no-daemon`；编译：`./gradlew :app:compileDebugKotlin`。网络失败时追加 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890`。
- 每个 commit 末尾加：
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_014937ohDunZ8GHJ4hGpurbY
  ```

## 文件结构

| 文件 | 动作 | 职责 |
|------|------|------|
| `main/core/ui/compose/StableAnchoredBottomSheet.kt` | 改 | 抽出 `StableAnchoredSheetLayout`（无 Dialog）+ Peek 锚点；Dialog 版本变为薄封装 |
| `main/reader/ui/compose/design/ReaderPanelColors.kt` | 新 | `ReaderPanelColors`、派生函数、`toColorScheme`、`forEInk`、`ProvideReaderPanelColors` |
| `main/reader/novel/compose/NovelReaderPanelColors.kt` | 新 | 小说调色板 → `ReaderPanelColors` |
| `main/reader/ui/compose/design/ReaderPanelComponents.kt` | 新 | tab 栏、选项 chips、图标选择条、快捷 tile、toggle chip、stepper、slider |
| `main/reader/ui/compose/design/ReaderOptionControls.kt` | 改 | 分组 / 分割线 / 开关行 / 数值行改读面板配色；新增 section 标题；最终删除 `ReaderSegmentedChoice` |
| `main/reader/ui/compose/panel/ReaderOptionsPanelHost.kt` | 新 | 面板宿主：表面模式、scrim、Peek 测量、快捷层 + 详细区 |
| `main/reader/ui/compose/ReaderMangaQuickActions.kt` | 新 | 漫画快捷操作列表（纯函数） |
| `main/reader/ui/compose/ComposeReaderOptionsSheet.kt` | 改 | 漫画面板改写 |
| `main/reader/ui/compose/ComposeReaderActivityScaffold.kt` | 改 | 用新面板替换 `ReaderAnchoredBottomSheet` 调用 |
| `main/reader/novel/compose/NovelReaderQuickActions.kt` | 新 | 小说快捷操作列表（纯函数） |
| `main/reader/novel/compose/ComposeNovelReaderOptionsSheet.kt` | 改 | 小说面板改写，删除死代码 |
| `main/reader/novel/compose/NovelReaderChrome.kt` | 改 | 移除面板调用与返回键分支 |
| `main/reader/novel/NovelReaderActivity.kt` | 改 | 在根 Box 渲染小说面板 |
| `app/src/main/res/values/strings.xml` | 改 | 新增 5 个英文字符串 |
| `test/core/ui/compose/StableAnchoredBottomSheetTest.kt` | 改 | 锚点测试 |
| `test/reader/ui/compose/design/ReaderPanelColorsTest.kt` | 新 | 配色测试 |
| `test/reader/novel/compose/NovelReaderPanelColorsTest.kt` | 新 | 小说配色测试 |
| `test/reader/ui/compose/design/ReaderPanelComponentsTest.kt` | 新 | `steppedValue` 测试 |
| `test/reader/ui/compose/panel/ReaderOptionsPanelHostTest.kt` | 新 | 表面模式 / scrim 测试 |
| `test/reader/ui/compose/ReaderMangaQuickActionsTest.kt` | 新 | 漫画快捷操作测试 |
| `test/reader/novel/compose/NovelReaderQuickActionsTest.kt` | 新 | 小说快捷操作测试 |

---

### Task 1: `StableAnchoredSheetLayout` 与 Peek 锚点

**Files:**
- Modify: `main/core/ui/compose/StableAnchoredBottomSheet.kt`
- Test: `test/core/ui/compose/StableAnchoredBottomSheetTest.kt`

- [ ] **Step 1: 写失败测试**（追加到 `StableAnchoredBottomSheetTest` 类中，并补 import `org.junit.jupiter.api.Assertions.assertNull`）

```kotlin
    @Test
    fun `sheets without a peek keep the half anchor`() {
        assertEquals(
            mapOf(
                StableSheetAnchor.Full to 0f,
                StableSheetAnchor.ThreeQuarter to 250f,
                StableSheetAnchor.Middle to 500f,
                StableSheetAnchor.Hidden to 1000f,
            ),
            stableSheetAnchorOffsets(hostHeightPx = 1000f, peekHeightPx = null),
        )
    }

    @Test
    fun `peek anchor shows exactly the peek height`() {
        assertEquals(640f, stableSheetAnchorOffsets(1000f, 360f)[StableSheetAnchor.Middle])
    }

    @Test
    fun `a peek taller than sixty percent drops the middle anchor`() {
        val offsets = stableSheetAnchorOffsets(1000f, 700f)
        assertNull(offsets[StableSheetAnchor.Middle])
        assertEquals(250f, offsets[StableSheetAnchor.ThreeQuarter])
    }

    @Test
    fun `an unmeasured peek uses the half anchor`() {
        assertEquals(500f, stableSheetAnchorOffsets(1000f, 0f)[StableSheetAnchor.Middle])
    }

    @Test
    fun `peek sheets open at the middle anchor, others at three quarters`() {
        assertEquals(StableSheetAnchor.Middle, stableSheetInitialAnchor(usePeekAnchor = true))
        assertEquals(StableSheetAnchor.ThreeQuarter, stableSheetInitialAnchor(usePeekAnchor = false))
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.core.ui.compose.StableAnchoredBottomSheetTest" --no-daemon`
Expected: 编译失败，`Unresolved reference: stableSheetAnchorOffsets` / `StableSheetAnchor` 不可见。

- [ ] **Step 3: 实现**

在 `StableAnchoredBottomSheet.kt` 中：

3a. 把 `private enum class StableSheetAnchor { Full, ThreeQuarter, Half, Hidden }` 替换为：

```kotlin
internal enum class StableSheetAnchor {
    Full,
    ThreeQuarter,

    /** Half height, or the caller's peek height when the sheet uses a peek anchor. */
    Middle,
    Hidden,
}

internal const val STABLE_SHEET_THREE_QUARTER_OFFSET_FRACTION = 0.25f
internal const val STABLE_SHEET_HALF_OFFSET_FRACTION = 0.5f
internal const val STABLE_SHEET_MAX_PEEK_FRACTION = 0.6f

/**
 * Offset from the top of the host for each anchor. A peek taller than
 * [STABLE_SHEET_MAX_PEEK_FRACTION] of the host has no middle anchor (landscape, large fonts);
 * an unmeasured peek (`null` or `<= 0`) keeps the half anchor until it is measured.
 */
internal fun stableSheetAnchorOffsets(hostHeightPx: Float, peekHeightPx: Float?): Map<StableSheetAnchor, Float> {
    val middle = when {
        peekHeightPx == null || peekHeightPx <= 0f -> hostHeightPx * STABLE_SHEET_HALF_OFFSET_FRACTION
        peekHeightPx > hostHeightPx * STABLE_SHEET_MAX_PEEK_FRACTION -> null
        else -> hostHeightPx - peekHeightPx
    }
    return buildMap {
        put(StableSheetAnchor.Full, 0f)
        put(StableSheetAnchor.ThreeQuarter, hostHeightPx * STABLE_SHEET_THREE_QUARTER_OFFSET_FRACTION)
        middle?.let { put(StableSheetAnchor.Middle, it) }
        put(StableSheetAnchor.Hidden, hostHeightPx)
    }
}

internal fun stableSheetInitialAnchor(usePeekAnchor: Boolean): StableSheetAnchor =
    if (usePeekAnchor) StableSheetAnchor.Middle else StableSheetAnchor.ThreeQuarter
```

3b. `StableSheetState` 改为：

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Stable
private class StableSheetState(
    private val density: androidx.compose.ui.unit.Density,
    initialAnchor: StableSheetAnchor,
) {
    val anchoredState = AnchoredDraggableState(initialValue = initialAnchor)

    private var hostHeightPx by mutableFloatStateOf(0f)
    private var peekHeightPx: Float? = null
    private var nestedDragInProgress = false

    val offset: Float
        get() = anchoredState.offset.takeIf(Float::isFinite)
            ?: hostHeightPx * STABLE_SHEET_THREE_QUARTER_OFFSET_FRACTION

    val scrimAlpha: Float
        get() = if (hostHeightPx <= 0f) {
            StableSheetMaxScrimAlpha
        } else {
            StableSheetMaxScrimAlpha * (1f - offset / hostHeightPx).coerceIn(0f, 1f)
        }

    val isHidden: Boolean
        get() {
            val hiddenOffset = anchoredState.anchors.positionOf(StableSheetAnchor.Hidden)
            return hiddenOffset.isFinite() &&
                anchoredState.settledValue == StableSheetAnchor.Hidden &&
                anchoredState.targetValue == StableSheetAnchor.Hidden &&
                !anchoredState.isAnimationRunning &&
                abs(offset - hiddenOffset) < HIDDEN_OFFSET_TOLERANCE_PX
        }

    fun updateHostHeight(heightPx: Float) {
        if (heightPx <= 0f || hostHeightPx == heightPx) return
        hostHeightPx = heightPx
        updateAnchors()
    }

    fun updatePeekHeight(heightPx: Float?) {
        if (peekHeightPx == heightPx) return
        peekHeightPx = heightPx
        if (hostHeightPx > 0f) updateAnchors()
    }

    private fun updateAnchors() {
        val offsets = stableSheetAnchorOffsets(hostHeightPx, peekHeightPx)
        val target = anchoredState.targetValue.takeIf { it in offsets } ?: StableSheetAnchor.ThreeQuarter
        anchoredState.updateAnchors(
            DraggableAnchors { offsets.forEach { (anchor, position) -> anchor at position } },
            target,
        )
    }

    // dispatchNestedDelta / hasNestedDrag / settle / dismiss 保持原样

    private fun targetAnchor(velocityY: Float): StableSheetAnchor {
        val visibleAnchors = StableSheetAnchor.entries.filter { anchoredState.anchors.positionOf(it).isFinite() }
        val current = anchoredState.settledValue
        val currentOffset = anchoredState.anchors.positionOf(current)
        val currentIndex = visibleAnchors.indexOf(current)
        val velocityThreshold = with(density) { 96.dp.toPx() }
        val direction = when {
            abs(velocityY) >= velocityThreshold -> if (velocityY < 0f) -1 else 1
            offset < currentOffset -> -1
            offset > currentOffset -> 1
            else -> 0
        }
        val adjacent = visibleAnchors.getOrNull(currentIndex + direction) ?: return current
        if (abs(velocityY) >= velocityThreshold) return adjacent
        val adjacentOffset = anchoredState.anchors.positionOf(adjacent)
        return if (abs(offset - currentOffset) >= positionalThreshold(abs(adjacentOffset - currentOffset))) {
            adjacent
        } else {
            current
        }
    }

    // positionalThreshold 保持原样

    private companion object {
        const val DEFAULT_POSITIONAL_THRESHOLD_FRACTION = 0.28f
        val MAX_POSITIONAL_THRESHOLD = 48.dp
        const val HIDDEN_OFFSET_TOLERANCE_PX = 0.5f
    }
}
```

（删除旧的 `VisibleAnchors`、`THREE_QUARTER_OFFSET_FRACTION`、`HALF_OFFSET_FRACTION` 常量。`StableSheetAnchor.entries` 的声明顺序 Full → ThreeQuarter → Middle → Hidden 即旧的 `VisibleAnchors` 顺序。）

3c. 把 `fun StableAnchoredBottomSheet(...)` 整体替换为下面两个函数加一个 scope 类（`rememberStableSheetNestedScrollConnection` 保持不变）。新增 import：`androidx.activity.compose.BackHandler`、`androidx.compose.runtime.Immutable`、`androidx.compose.runtime.LaunchedEffect`。

```kotlin
/** What [StableAnchoredSheetLayout] hands to its sheet slot. */
@Immutable
class StableSheetSlotScope internal constructor(
    /** Apply to the drag handle / header so they drag the sheet. */
    val dragModifier: Modifier,
    /** Bottom padding that keeps content inside the visible part of the sheet. */
    val contentBottomPadding: Dp,
    /** Animates the sheet away, then calls `onDismissRequest`. */
    val dismiss: () -> Unit,
)

/**
 * The anchored sheet without a window of its own, so it can live in the same window as a
 * `layerBackdrop` and draw real glass over it. Handles back itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StableAnchoredSheetLayout(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetMaxWidth: Dp? = null,
    scrimColor: Color = Color.Black.copy(alpha = StableSheetMaxScrimAlpha),
    usePeekAnchor: Boolean = false,
    peekHeight: Dp? = null,
    sheet: @Composable (StableSheetSlotScope) -> Unit,
) {
    val density = LocalDensity.current
    val state = remember(density) { StableSheetState(density, stableSheetInitialAnchor(usePeekAnchor)) }
    val coroutineScope = rememberCoroutineScope()
    val currentOnDismissRequest = rememberUpdatedState(onDismissRequest)
    val nestedScrollConnection = rememberStableSheetNestedScrollConnection(state)
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state.anchoredState,
        state::positionalThreshold,
        StableSheetAnimationSpec,
    )
    val sheetDragModifier = Modifier.anchoredDraggable(
        state = state.anchoredState,
        orientation = Orientation.Vertical,
        flingBehavior = flingBehavior,
    )
    val dismissWithAnimation = remember(state, coroutineScope, currentOnDismissRequest) {
        {
            coroutineScope.launch {
                if (!state.dismiss()) currentOnDismissRequest.value()
            }
            Unit
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isHidden }
            .filter { it }
            .first()
        currentOnDismissRequest.value()
    }
    val peekHeightPx = peekHeight?.let { with(density) { it.toPx() } }
    LaunchedEffect(state, peekHeightPx) { state.updatePeekHeight(peekHeightPx) }
    BackHandler(onBack = dismissWithAnimation)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { state.updateHostHeight(it.height.toFloat()) },
    ) {
        val sheetWidth = calculateStableSheetWidth(maxWidth, sheetMaxWidth)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    scrimColor.copy(
                        alpha = (scrimColor.alpha * state.scrimAlpha / StableSheetMaxScrimAlpha)
                            .coerceIn(0f, 1f),
                    ),
                )
                .clickable(onClick = dismissWithAnimation),
        )
        val offset = state.offset.coerceAtLeast(0f)
        Box(
            modifier = Modifier
                .width(sheetWidth)
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, offset.roundToInt()) }
                .nestedScroll(nestedScrollConnection),
        ) {
            sheet(
                StableSheetSlotScope(
                    dragModifier = sheetDragModifier,
                    contentBottomPadding = with(density) { offset.toDp() },
                    dismiss = dismissWithAnimation,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StableAnchoredBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetMaxWidth: Dp? = null,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = Color.Unspecified,
    scrimColor: Color = Color.Black.copy(alpha = StableSheetMaxScrimAlpha),
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable (dragModifier: Modifier) -> Unit,
) {
    Dialog(
        // Back is handled (with the hide animation) by StableAnchoredSheetLayout's BackHandler.
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        StableAnchoredSheetLayout(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetMaxWidth = sheetMaxWidth,
            scrimColor = scrimColor,
        ) { scope ->
            Surface(
                shape = shape,
                color = containerColor,
                contentColor = contentColor,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = scope.contentBottomPadding),
                ) {
                    if (dragHandle != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(scope.dragModifier),
                        ) {
                            dragHandle()
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        content(scope.dragModifier)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.core.ui.compose.StableAnchoredBottomSheetTest" --no-daemon`
Expected: 8 tests PASS。

- [ ] **Step 5: 编译检查并提交**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL。

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/core/ui/compose/StableAnchoredBottomSheet.kt app/src/test/kotlin/org/skepsun/kototoro/core/ui/compose/StableAnchoredBottomSheetTest.kt
git commit -m "refactor(ui): split the anchored sheet from its dialog and add a peek anchor"
```

---

### Task 2: `ReaderPanelColors`

**Files:**
- Create: `main/reader/ui/compose/design/ReaderPanelColors.kt`
- Create: `main/reader/novel/compose/NovelReaderPanelColors.kt`
- Test: `test/reader/ui/compose/design/ReaderPanelColorsTest.kt`
- Test: `test/reader/novel/compose/NovelReaderPanelColorsTest.kt`

- [ ] **Step 1: 写失败测试**

`test/reader/ui/compose/design/ReaderPanelColorsTest.kt`：

```kotlin
package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderBackground
import kotlin.math.max
import kotlin.math.min

class ReaderPanelColorsTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    @Test
    fun `light and white backgrounds give a light panel`() {
        assertFalse(ReaderBackground.LIGHT.isDarkPanel(isSystemDark = true))
        assertFalse(ReaderBackground.WHITE.isDarkPanel(isSystemDark = true))
    }

    @Test
    fun `dark and black backgrounds give a dark panel`() {
        assertTrue(ReaderBackground.DARK.isDarkPanel(isSystemDark = false))
        assertTrue(ReaderBackground.BLACK.isDarkPanel(isSystemDark = false))
    }

    @Test
    fun `default and auto backgrounds follow the system`() {
        for (background in listOf(ReaderBackground.DEFAULT, ReaderBackground.AUTO)) {
            assertTrue(background.isDarkPanel(isSystemDark = true))
            assertFalse(background.isDarkPanel(isSystemDark = false))
        }
    }

    @Test
    fun `manga panel text is readable on every background`() {
        for (background in ReaderBackground.entries) {
            for (systemDark in listOf(false, true)) {
                val scheme = if (systemDark) darkColorScheme() else lightColorScheme()
                val colors = mangaReaderPanelColors(background, systemDark, scheme)
                val case = "$background / systemDark=$systemDark"
                assertTrue(contrast(colors.content, colors.container) >= 4.5f, case)
                assertTrue(contrast(colors.content, colors.card) >= 4.5f, case)
                assertTrue(contrast(colors.contentSecondary, colors.card) >= 3f, case)
            }
        }
    }

    @Test
    fun `accent switches to the inverse primary when panel and app themes differ`() {
        val scheme = lightColorScheme()
        assertEquals(scheme.inversePrimary, mangaReaderPanelColors(ReaderBackground.BLACK, false, scheme).accent)
        assertEquals(scheme.primary, mangaReaderPanelColors(ReaderBackground.WHITE, false, scheme).accent)
    }

    @Test
    fun `every surface container of the panel scheme is the card colour`() {
        val colors = mangaReaderPanelColors(ReaderBackground.LIGHT, false, lightColorScheme())
        val scheme = colors.toColorScheme(lightColorScheme())
        listOf(
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
        ).forEach { assertEquals(colors.card, it) }
        assertEquals(colors.container, scheme.surface)
        assertEquals(colors.content, scheme.onSurface)
        assertEquals(colors.accent, scheme.primary)
    }

    @Test
    fun `e-ink colours are pure paper and ink`() {
        val light = mangaReaderPanelColors(ReaderBackground.LIGHT, false, lightColorScheme()).forEInk()
        assertEquals(Color.White, light.container)
        assertEquals(Color.Black, light.content)
        assertEquals(light.container, light.card)
        val dark = mangaReaderPanelColors(ReaderBackground.BLACK, true, darkColorScheme()).forEInk()
        assertEquals(Color.Black, dark.container)
        assertEquals(Color.White, dark.content)
    }
}
```

`test/reader/novel/compose/NovelReaderPanelColorsTest.kt`：

```kotlin
package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.NovelReaderPalette

class NovelReaderPanelColorsTest {

    // Values copied from novelReaderPalette(PAPER, isDarkTheme = false).
    private val paperLight = NovelReaderPalette(
        backgroundColor = 0xFFF4ECD8.toInt(),
        textColor = 0xFF4F4032.toInt(),
        secondaryTextColor = 0xFF7A6A59.toInt(),
        chromeBackgroundColor = 0xFFE7DDC5.toInt(),
        chromeTextColor = 0xFF544436.toInt(),
        highlightColor = 0x4DA67C2E,
        placeholderColor = 0xFFDDD2BC.toInt(),
        placeholderTextColor = 0xFF7A6A59.toInt(),
        isDark = false,
    )

    @Test
    fun `panel takes the reading theme colours`() {
        val colors = novelReaderPanelColors(paperLight)
        assertEquals(Color(0xFFF4ECD8), colors.container)
        assertEquals(Color(0xFF4F4032), colors.content)
        assertEquals(Color(0xFF7A6A59), colors.contentSecondary)
        assertFalse(colors.isDark)
    }

    @Test
    fun `panel cards stay warm on a sepia theme instead of turning grey`() {
        val card = novelReaderPanelColors(paperLight).card
        assertNotEquals(Color(0xFFF4ECD8), card)
        assertTrue(card.red > card.blue)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelColorsTest" --tests "org.skepsun.kototoro.reader.novel.compose.NovelReaderPanelColorsTest" --no-daemon`
Expected: 编译失败，`Unresolved reference: isDarkPanel / mangaReaderPanelColors / novelReaderPanelColors`。

- [ ] **Step 3: 实现**

`main/reader/ui/compose/design/ReaderPanelColors.kt`：

```kotlin
package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.skepsun.kototoro.core.prefs.ReaderBackground

/**
 * Colours of the reader options panel, derived from what is being read (the novel theme or the
 * manga reader background) rather than from the app theme, so the panel belongs to the page.
 */
@Immutable
data class ReaderPanelColors(
    val container: Color,
    val card: Color,
    val content: Color,
    val contentSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val selectedContainer: Color,
    val divider: Color,
    val isDark: Boolean,
)

val LocalReaderPanelColors = staticCompositionLocalOf<ReaderPanelColors?> { null }

/** The panel colours, or colours taken from the Material theme outside a panel. */
@Composable
fun currentReaderPanelColors(): ReaderPanelColors {
    LocalReaderPanelColors.current?.let { return it }
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { materialReaderPanelColors(scheme) }
}

internal fun materialReaderPanelColors(scheme: ColorScheme) = ReaderPanelColors(
    container = scheme.surfaceContainerHigh,
    card = scheme.surfaceContainerLow,
    content = scheme.onSurface,
    contentSecondary = scheme.onSurfaceVariant,
    accent = scheme.primary,
    onAccent = scheme.onPrimary,
    selectedContainer = scheme.primaryContainer,
    divider = scheme.outlineVariant.copy(alpha = 0.55f),
    isDark = scheme.surface.luminance() < 0.5f,
)

internal fun readerPanelColors(
    base: Color,
    content: Color,
    contentSecondary: Color,
    accent: Color,
    isDark: Boolean,
): ReaderPanelColors {
    val card = if (isDark) lerp(base, Color.White, 0.07f) else lerp(base, Color.Black, 0.045f)
    return ReaderPanelColors(
        container = base,
        card = card,
        content = content,
        contentSecondary = contentSecondary,
        accent = accent,
        onAccent = if (accent.luminance() > 0.4f) Color.Black else Color.White,
        selectedContainer = lerp(card, accent, if (isDark) 0.30f else 0.18f),
        divider = content.copy(alpha = 0.12f),
        isDark = isDark,
    )
}

private val MangaPanelLight = Color(0xFFF4F3F7)
private val MangaPanelLightContent = Color(0xFF1C1B1F)
private val MangaPanelLightSecondary = Color(0xFF5E5C66)
private val MangaPanelDark = Color(0xFF1C1B20)
private val MangaPanelDarkContent = Color(0xFFE6E1E6)
private val MangaPanelDarkSecondary = Color(0xFFB3AFB8)

internal fun ReaderBackground.isDarkPanel(isSystemDark: Boolean): Boolean = when (this) {
    ReaderBackground.LIGHT, ReaderBackground.WHITE -> false
    ReaderBackground.DARK, ReaderBackground.BLACK -> true
    ReaderBackground.DEFAULT, ReaderBackground.AUTO -> isSystemDark
}

internal fun mangaReaderPanelColors(
    background: ReaderBackground,
    isSystemDark: Boolean,
    scheme: ColorScheme,
): ReaderPanelColors {
    val dark = background.isDarkPanel(isSystemDark)
    // The app scheme follows the system; a panel of the opposite brightness needs the inverse accent.
    val accent = if (dark == isSystemDark) scheme.primary else scheme.inversePrimary
    return if (dark) {
        readerPanelColors(MangaPanelDark, MangaPanelDarkContent, MangaPanelDarkSecondary, accent, isDark = true)
    } else {
        readerPanelColors(MangaPanelLight, MangaPanelLightContent, MangaPanelLightSecondary, accent, isDark = false)
    }
}

/** Paper and ink only: no tints, no translucency, for e-ink screens. */
fun ReaderPanelColors.forEInk(): ReaderPanelColors {
    val paper = if (isDark) Color.Black else Color.White
    val ink = if (isDark) Color.White else Color.Black
    return ReaderPanelColors(
        container = paper,
        card = paper,
        content = ink,
        contentSecondary = ink,
        accent = ink,
        onAccent = paper,
        selectedContainer = ink.copy(alpha = 0.12f).compositeOver(paper),
        divider = ink.copy(alpha = 0.5f),
        isDark = isDark,
    )
}

/**
 * A complete scheme for Material components inside the panel. Every surface role is set, so no
 * component can fall back to an app-theme surface that clashes with the page (the grey cards on
 * a sepia novel theme came from an unset `surfaceContainerLow`).
 */
internal fun ReaderPanelColors.toColorScheme(base: ColorScheme): ColorScheme = base.copy(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = selectedContainer,
    onPrimaryContainer = content,
    secondary = accent,
    onSecondary = onAccent,
    secondaryContainer = selectedContainer,
    onSecondaryContainer = content,
    background = container,
    onBackground = content,
    surface = container,
    onSurface = content,
    surfaceVariant = card,
    onSurfaceVariant = contentSecondary,
    surfaceTint = accent,
    surfaceBright = card,
    surfaceDim = container,
    surfaceContainerLowest = card,
    surfaceContainerLow = card,
    surfaceContainer = card,
    surfaceContainerHigh = card,
    surfaceContainerHighest = card,
    outline = contentSecondary,
    outlineVariant = divider,
    inverseSurface = content,
    inverseOnSurface = container,
)

@Composable
fun ProvideReaderPanelColors(colors: ReaderPanelColors, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(colors, base) { colors.toColorScheme(base) }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalReaderPanelColors provides colors,
            LocalContentColor provides colors.content,
            content = content,
        )
    }
}
```

`main/reader/novel/compose/NovelReaderPanelColors.kt`：

```kotlin
package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.ui.graphics.Color
import org.skepsun.kototoro.reader.novel.NovelReaderPalette
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.readerPanelColors

internal fun novelReaderPanelColors(palette: NovelReaderPalette): ReaderPanelColors = readerPanelColors(
    base = Color(palette.backgroundColor),
    content = Color(palette.textColor),
    contentSecondary = Color(palette.secondaryTextColor),
    accent = Color(palette.chromeTextColor),
    isDark = palette.isDark,
)
```

- [ ] **Step 4: 运行测试确认通过**

Run: 同 Step 2。
Expected: 9 tests PASS。若「readable on every background」失败，按报错的 case 调整 `MangaPanel*` 常量，不要放宽阈值。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/design/ReaderPanelColors.kt app/src/main/kotlin/org/skepsun/kototoro/reader/novel/compose/NovelReaderPanelColors.kt app/src/test/kotlin/org/skepsun/kototoro/reader/ui/compose/design/ReaderPanelColorsTest.kt app/src/test/kotlin/org/skepsun/kototoro/reader/novel/compose/NovelReaderPanelColorsTest.kt
git commit -m "feat(reader): derive options panel colours from the page being read"
```

---

### Task 3: 面板组件

**Files:**
- Create: `main/reader/ui/compose/design/ReaderPanelComponents.kt`
- Test: `test/reader/ui/compose/design/ReaderPanelComponentsTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.reader.ui.compose.design

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderPanelComponentsTest {

    @Test
    fun `stepping moves one step within the range`() {
        assertEquals(18f, steppedValue(17f, step = 1f, direction = 1, range = 14f..24f))
        assertEquals(16f, steppedValue(17f, step = 1f, direction = -1, range = 14f..24f))
    }

    @Test
    fun `stepping stops at the ends of the range`() {
        assertEquals(24f, steppedValue(24f, step = 1f, direction = 1, range = 14f..24f))
        assertEquals(14f, steppedValue(14f, step = 1f, direction = -1, range = 14f..24f))
    }

    @Test
    fun `stepping snaps off-grid values back onto the grid`() {
        assertEquals(18f, steppedValue(17.3f, step = 1f, direction = 1, range = 14f..24f))
        assertEquals(40f, steppedValue(36f, step = 4f, direction = 1, range = 12f..120f))
    }

    @Test
    fun `fractional steps do not drift`() {
        assertEquals(1.7f, steppedValue(1.6f, step = 0.1f, direction = 1, range = 1.2f..2.0f), 1e-4f)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelComponentsTest" --no-daemon`
Expected: 编译失败，`Unresolved reference: steppedValue`。

- [ ] **Step 3: 实现** `main/reader/ui/compose/design/ReaderPanelComponents.kt`

```kotlin
package org.skepsun.kototoro.reader.ui.compose.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import kotlin.math.roundToInt

private val ChipShape = RoundedCornerShape(16.dp)
private val TileShape = RoundedCornerShape(16.dp)

/** Centered capsule tabs; [trailing] sits at the end of the row (e.g. a settings button). */
@Composable
fun ReaderPanelTabBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = currentReaderPanelColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (trailing != null) Spacer(Modifier.width(40.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .clip(Capsule())
                    .background(colors.card)
                    .padding(3.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val background by animateColorAsState(
                        targetValue = if (selected) colors.selectedContainer else Color.Transparent,
                        label = "readerPanelTab",
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(Capsule())
                            .background(background)
                            .tvFocusable(shape = Capsule(), addFocusTarget = false)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onSelected(index) })
                            .heightIn(min = 36.dp)
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) colors.content else colors.contentSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (trailing != null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) { trailing() }
        }
    }
}

/**
 * Equal-width choice chips for 2–4 options. With an icon and more than three options the icon
 * sits above the label so labels are not squeezed.
 */
@Composable
fun ReaderChoiceChips(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (Int) -> Unit)? = null,
    height: Dp = 56.dp,
    unselectedColor: Color? = null,
) {
    val colors = currentReaderPanelColors()
    val stacked = icon != null && options.size > 3
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(
                shape = ChipShape,
                color = if (selected) colors.selectedContainer else unselectedColor ?: colors.card,
                contentColor = if (selected) colors.content else colors.contentSecondary,
                border = if (selected) BorderStroke(1.dp, colors.accent) else null,
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .tvFocusable(shape = ChipShape, addFocusTarget = false)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelected(index) }),
            ) {
                val text: @Composable () -> Unit = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                if (stacked) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) { icon?.invoke(index) }
                        text()
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        if (icon != null) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) { icon(index) }
                            Spacer(Modifier.width(8.dp))
                        }
                        text()
                    }
                }
            }
        }
    }
}

/** Icon-only capsule for five or more options; the selected option's name is shown below. */
@Composable
fun ReaderIconChoiceBar(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    icon: @Composable (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentReaderPanelColors()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(Capsule())
                .background(colors.card)
                .padding(4.dp),
        ) {
            options.indices.forEach { index ->
                val selected = index == selectedIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 52.dp, height = 44.dp)
                        .clip(Capsule())
                        .background(if (selected) colors.selectedContainer else Color.Transparent)
                        .tvFocusable(shape = Capsule(), addFocusTarget = false)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelected(index) })
                        .semantics { contentDescription = options[index] },
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides if (selected) colors.content else colors.contentSecondary,
                    ) {
                        icon(index)
                    }
                }
            }
        }
        Text(
            text = options.getOrElse(selectedIndex) { "" },
            style = MaterialTheme.typography.labelMedium,
            color = colors.contentSecondary,
            maxLines = 1,
        )
    }
}

/** A quick action; [toggled] is non-null for on/off actions and is shown by the tile's highlight. */
@Immutable
data class ReaderQuickAction(
    val id: String,
    val iconResId: Int,
    val labelResId: Int,
    val toggled: Boolean? = null,
)

@Composable
fun ReaderQuickActionGrid(
    actions: List<ReaderQuickAction>,
    onClick: (ReaderQuickAction) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier.fillMaxWidth()) {
        actions.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { action ->
                    ReaderQuickTile(action = action, onClick = { onClick(action) }, modifier = Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun ReaderQuickTile(
    action: ReaderQuickAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentReaderPanelColors()
    val active = action.toggled == true
    val interaction = if (action.toggled != null) {
        Modifier.toggleable(value = active, role = Role.Switch, onValueChange = { onClick() })
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(TileShape)
            .tvFocusable(shape = TileShape, addFocusTarget = false)
            .then(interaction)
            .padding(vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (active) colors.accent else colors.card),
        ) {
            Icon(
                painter = painterResource(action.iconResId),
                contentDescription = null,
                tint = if (active) colors.onAccent else colors.content,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = stringResource(action.labelResId),
            style = MaterialTheme.typography.labelSmall,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Small capsule on/off chip, e.g. "Follow system" beside the brightness slider. */
@Composable
fun ReaderPanelToggleChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentReaderPanelColors()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(Capsule())
            .background(if (checked) colors.selectedContainer else colors.card)
            .tvFocusable(shape = Capsule(), addFocusTarget = false)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            color = if (checked) colors.content else colors.contentSecondary,
            maxLines = 1,
        )
    }
}

/** [value] moved by one [step] in [direction] (±1), snapped onto the step grid and kept in [range]. */
internal fun steppedValue(
    value: Float,
    step: Float,
    direction: Int,
    range: ClosedFloatingPointRange<Float>,
): Float {
    val steps = ((value - range.start) / step).roundToInt() + direction
    return (range.start + steps * step).coerceIn(range)
}

/** Label, − value +; tapping the value opens an inline slider for large changes. */
@Composable
fun ReaderStepperRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
) {
    val colors = currentReaderPanelColors()
    var sliderVisible by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(contentPadding)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ReaderStepButton(
                symbol = "−",
                enabled = value > valueRange.start,
                onClick = { onValueChange(steppedValue(value, step, -1, valueRange)) },
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .clip(Capsule())
                    .clickable(role = Role.Button) { sliderVisible = !sliderVisible }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            ReaderStepButton(
                symbol = "+",
                enabled = value < valueRange.endInclusive,
                onClick = { onValueChange(steppedValue(value, step, 1, valueRange)) },
            )
        }
        if (sliderVisible) {
            Slider(
                value = value.coerceIn(valueRange),
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReaderStepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = currentReaderPanelColors()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.selectedContainer)
            .tvFocusable(shape = CircleShape, addFocusTarget = false)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        Text(text = symbol, style = MaterialTheme.typography.titleMedium, color = colors.content)
    }
}

@Composable
fun ReaderSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    leadingIcon: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
) {
    val colors = currentReaderPanelColors()
    Column(modifier = modifier.fillMaxWidth().padding(contentPadding)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
        ) {
            leadingIcon?.let {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    tint = colors.contentSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) colors.accent else colors.contentSecondary,
                maxLines = 1,
            )
            trailing?.invoke()
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
```

补 import：`androidx.compose.foundation.layout.widthIn`（`ReaderStepperRow` 使用）。

- [ ] **Step 4: 运行测试确认通过**

Run: 同 Step 2。Expected: 4 tests PASS。

- [ ] **Step 5: 编译并提交**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL。

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/design/ReaderPanelComponents.kt app/src/test/kotlin/org/skepsun/kototoro/reader/ui/compose/design/ReaderPanelComponentsTest.kt
git commit -m "feat(reader): add options panel components"
```

---

### Task 4: 现有选项行改读面板配色

**Files:**
- Modify: `main/reader/ui/compose/design/ReaderOptionControls.kt`（`ReaderOptionGroup`、`ReaderOptionDivider`、`ReaderOptionSwitchRow`、`ReaderOptionValueRow`；`ReaderSegmentedChoice` 暂不动）

- [ ] **Step 1: 替换这四个函数并新增两个 section 函数**

```kotlin
@Composable
fun ReaderOptionGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = currentReaderPanelColors()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        if (title != null) ReaderOptionSectionTitle(title)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.card,
            contentColor = colors.content,
            // E-ink cards share the panel colour; an outline keeps the group visible.
            border = if (colors.card == colors.container) BorderStroke(1.dp, colors.divider) else null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun ReaderOptionSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = currentReaderPanelColors().contentSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

/** A titled block without a card, for controls that draw their own surfaces (chips, swatches). */
@Composable
fun ReaderOptionSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        ReaderOptionSectionTitle(title)
        content()
    }
}

@Composable
fun ReaderOptionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = currentReaderPanelColors().divider,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

@Composable
fun ReaderOptionSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = currentReaderPanelColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.content,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.scale(0.85f),
        )
    }
}

@Composable
fun ReaderOptionValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentReaderPanelColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.accent,
            maxLines = 1,
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_forward),
            contentDescription = null,
            tint = colors.contentSecondary,
            modifier = Modifier.padding(start = 6.dp).size(16.dp),
        )
    }
}
```

- [ ] **Step 2: 确认其他调用方仍能编译**

`ReaderOptionGroup` 还被 `main/reader/ui/colorfilter/ReaderColorCorrectionEditor.kt` 使用。新增的 `title` 参数在 `modifier` 之后且有默认值，尾随 lambda 调用不受影响。

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/design/ReaderOptionControls.kt
git commit -m "refactor(reader): read option rows' colours from the panel palette"
```

---

### Task 5: `ReaderOptionsPanelHost`

**Files:**
- Create: `main/reader/ui/compose/panel/ReaderOptionsPanelHost.kt`
- Test: `test/reader/ui/compose/panel/ReaderOptionsPanelHostTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.reader.ui.compose.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderOptionsPanelHostTest {

    @Test
    fun `e-ink always gets the plain surface`() {
        assertEquals(
            ReaderPanelSurfaceMode.EInk,
            readerPanelSurfaceMode(isIosStyle = true, eInk = true, hasBackdrop = true),
        )
    }

    @Test
    fun `glass needs the iOS style and a reader backdrop`() {
        assertEquals(ReaderPanelSurfaceMode.Glass, readerPanelSurfaceMode(true, eInk = false, hasBackdrop = true))
        assertEquals(ReaderPanelSurfaceMode.Opaque, readerPanelSurfaceMode(true, eInk = false, hasBackdrop = false))
        assertEquals(ReaderPanelSurfaceMode.Opaque, readerPanelSurfaceMode(false, eInk = false, hasBackdrop = true))
    }

    @Test
    fun `scrim is lighter over glass and absent on e-ink`() {
        assertEquals(0.32f, readerPanelScrimAlpha(ReaderPanelSurfaceMode.Glass))
        assertEquals(0.42f, readerPanelScrimAlpha(ReaderPanelSurfaceMode.Opaque))
        assertEquals(0f, readerPanelScrimAlpha(ReaderPanelSurfaceMode.EInk))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.reader.ui.compose.panel.ReaderOptionsPanelHostTest" --no-daemon`
Expected: 编译失败，`Unresolved reference: ReaderPanelSurfaceMode`。

- [ ] **Step 3: 实现**

```kotlin
package org.skepsun.kototoro.reader.ui.compose.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.StableAnchoredSheetLayout
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.reader.ui.compose.design.ProvideReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlTokens
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelColors

internal enum class ReaderPanelSurfaceMode { Glass, Opaque, EInk }

internal fun readerPanelSurfaceMode(isIosStyle: Boolean, eInk: Boolean, hasBackdrop: Boolean): ReaderPanelSurfaceMode =
    when {
        eInk -> ReaderPanelSurfaceMode.EInk
        isIosStyle && hasBackdrop -> ReaderPanelSurfaceMode.Glass
        else -> ReaderPanelSurfaceMode.Opaque
    }

internal fun readerPanelScrimAlpha(mode: ReaderPanelSurfaceMode): Float = when (mode) {
    ReaderPanelSurfaceMode.Glass -> 0.32f
    ReaderPanelSurfaceMode.Opaque -> 0.42f
    ReaderPanelSurfaceMode.EInk -> 0f
}

@Composable
internal fun rememberReaderPanelSurfaceMode(eInk: Boolean): ReaderPanelSurfaceMode = readerPanelSurfaceMode(
    isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS,
    eInk = eInk,
    hasBackdrop = LocalLiquidGlassBackdrop.current != null,
)

/**
 * The reader options panel. Must be placed in the reader's own window, beside (not inside) the
 * content that carries `layerBackdrop`, so the glass surface can show the page through it.
 * Opens at the peek anchor, which shows exactly [quickLayer].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderOptionsPanelHost(
    colors: ReaderPanelColors,
    surfaceMode: ReaderPanelSurfaceMode,
    onDismissRequest: () -> Unit,
    quickLayer: @Composable ColumnScope.() -> Unit,
    details: @Composable (dragModifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var quickLayerHeightPx by remember { mutableIntStateOf(0) }
    val peekHeight = if (quickLayerHeightPx > 0) {
        with(density) { quickLayerHeightPx.toDp() } + navigationInset
    } else {
        null
    }
    ProvideReaderPanelColors(colors) {
        StableAnchoredSheetLayout(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetMaxWidth = ReaderControlTokens.SheetMaxWidth,
            scrimColor = Color.Black.copy(alpha = readerPanelScrimAlpha(surfaceMode)),
            usePeekAnchor = true,
            peekHeight = peekHeight,
        ) { scope ->
            ReaderPanelSurface(surfaceMode, colors) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = scope.contentBottomPadding),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(scope.dragModifier)
                            .onSizeChanged { quickLayerHeightPx = it.height },
                    ) {
                        BottomSheetDefaults.DragHandle(color = colors.contentSecondary.copy(alpha = 0.6f))
                        quickLayer()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .navigationBarsPadding(),
                    ) {
                        details(scope.dragModifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPanelSurface(
    mode: ReaderPanelSurfaceMode,
    colors: ReaderPanelColors,
    content: @Composable () -> Unit,
) {
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    when (mode) {
        ReaderPanelSurfaceMode.Glass -> GlassSurface(
            modifier = Modifier.fillMaxSize(),
            // Inside ProvideReaderPanelColors, so the glass tint comes from the panel scheme.
            style = GlassDefaults.prominentStyle().copy(containerAlpha = 0.86f),
            shape = RoundedRectangle(28.dp),
            componentRole = GlassComponentRole.Sheet,
        ) {
            content()
        }
        ReaderPanelSurfaceMode.Opaque -> Surface(
            shape = sheetShape,
            color = colors.container,
            contentColor = colors.content,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
        ReaderPanelSurfaceMode.EInk -> Surface(
            shape = sheetShape,
            color = colors.container,
            contentColor = colors.content,
            border = BorderStroke(1.dp, colors.content),
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: 同 Step 2。Expected: 3 tests PASS。

- [ ] **Step 5: 编译并提交**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL。

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/panel/ReaderOptionsPanelHost.kt app/src/test/kotlin/org/skepsun/kototoro/reader/ui/compose/panel/ReaderOptionsPanelHostTest.kt
git commit -m "feat(reader): host the options panel in the reader window"
```

---

### Task 6: 字符串

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（只改英文源文件）

- [ ] **Step 1: 在 `reader_more_tab_display` 那一行之后加入**

```xml
    <string name="reader_more_tab_layout">Layout</string>
    <string name="reader_panel_section_two_pages">Two pages</string>
    <string name="reader_panel_section_renderer">Renderer</string>
    <string name="reader_panel_section_performance">Performance</string>
    <string name="novel_reader_tab_translation_tools">Translation &amp; tools</string>
```

- [ ] **Step 2: 确认无重名并提交**

Run: `grep -c 'name="reader_more_tab_layout"\|name="reader_panel_section_\|name="novel_reader_tab_translation_tools"' app/src/main/res/values/strings.xml`
Expected: `5`

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(reader): add options panel strings"
```

---

### Task 7: 漫画快捷操作

**Files:**
- Create: `main/reader/ui/compose/ReaderMangaQuickActions.kt`
- Test: `test/reader/ui/compose/ReaderMangaQuickActionsTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderMangaQuickActionsTest {

    @Test
    fun `eight actions without translation, chapters first`() {
        val actions = mangaQuickActions(translationAvailable = false, translationActive = false)
        assertEquals(
            listOf("CHAPTERS", "BOOKMARK", "SAVE_PAGE", "CROP_NOTE", "AUTO_SCROLL", "ROTATE", "DOWNLOAD", "BROWSER"),
            actions.map { it.id },
        )
        actions.forEach { assertNull(it.toggled) }
    }

    @Test
    fun `translation is a toggle appended last when available`() {
        val actions = mangaQuickActions(translationAvailable = true, translationActive = true)
        assertEquals(9, actions.size)
        assertEquals("TRANSLATE", actions.last().id)
        assertEquals(true, actions.last().toggled)
        assertEquals(false, mangaQuickActions(true, translationActive = false).last().toggled)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.reader.ui.compose.ReaderMangaQuickActionsTest" --no-daemon`
Expected: 编译失败，`Unresolved reference: mangaQuickActions`。

- [ ] **Step 3: 实现**

```kotlin
package org.skepsun.kototoro.reader.ui.compose

import org.skepsun.kototoro.R
import org.skepsun.kototoro.reader.ui.compose.design.ReaderQuickAction

internal enum class MangaQuickActionId(val iconResId: Int, val labelResId: Int) {
    CHAPTERS(R.drawable.ic_grid, R.string.chapters_and_pages),
    BOOKMARK(R.drawable.ic_bookmark, R.string.bookmark_add),
    SAVE_PAGE(R.drawable.ic_save, R.string.save_page),
    CROP_NOTE(R.drawable.ic_crop, R.string.crop_and_annotate),
    AUTO_SCROLL(R.drawable.ic_timer, R.string.automatic_scroll),
    ROTATE(R.drawable.ic_screen_rotation, R.string.rotate_screen),
    DOWNLOAD(R.drawable.ic_download, R.string.download),
    BROWSER(R.drawable.ic_web, R.string.open_in_browser),
    TRANSLATE(R.drawable.ic_translate, R.string.reader_translation_action),
}

internal fun mangaQuickActions(translationAvailable: Boolean, translationActive: Boolean): List<ReaderQuickAction> =
    MangaQuickActionId.entries
        .filter { it != MangaQuickActionId.TRANSLATE || translationAvailable }
        .map { id ->
            ReaderQuickAction(
                id = id.name,
                iconResId = id.iconResId,
                labelResId = id.labelResId,
                toggled = if (id == MangaQuickActionId.TRANSLATE) translationActive else null,
            )
        }
```

- [ ] **Step 4: 运行测试确认通过**

Run: 同 Step 2。Expected: 2 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ReaderMangaQuickActions.kt app/src/test/kotlin/org/skepsun/kototoro/reader/ui/compose/ReaderMangaQuickActionsTest.kt
git commit -m "feat(reader): list the manga reader's quick actions"
```

---

### Task 8: 漫画面板改写与接入

**Files:**
- Modify: `main/reader/ui/compose/ComposeReaderOptionsSheet.kt`
- Modify: `main/reader/ui/compose/ComposeReaderActivityScaffold.kt:720-735`

以下为 `ComposeReaderOptionsSheet.kt` 的改动。**保持不变**的部分：`ComposeReaderOptionsState`、`ComposeReaderOptionsCallbacks`、`ReaderTranslationOptionsPage` 的结构（仅 Step 5 的替换）、`ReaderTranslationSettingsContent`（仅 Step 5 的替换）、`OptionsActionGrid`、`OptionAction`、`SelectRow`、`ReaderMode.label()`、`ReaderMode.iconResId()`、`ReaderAnimationIcon`。

- [ ] **Step 1: 删除旧入口与旧页面**

删除以下声明：`ComposeReaderOptionsSheet`、`ReaderOptionsPage`（data class）、`ReaderOptionsTab`（composable）、`ReaderReadingOptionsPage`、`ReaderPageOptionsPage`、`ReaderDoublePageSensitivity`、`ReaderToolsOptionsPage`、`OptionsPageTitle`、`ReaderToolActionRow`、`ReaderBackgroundPalette`。

- [ ] **Step 2: 新增入口、快捷层与 tab 容器**

```kotlin
private enum class ReaderOptionsTab(val labelResId: Int) {
    LAYOUT(R.string.reader_more_tab_layout),
    DISPLAY(R.string.reader_more_tab_display),
    TRANSLATION(R.string.reader_more_tab_translation),
}

@Composable
internal fun ComposeReaderOptionsPanel(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    translationAvailable: Boolean,
    translationActive: Boolean,
    eInkMode: Boolean,
    translationTaskPanelContent: @Composable () -> Unit = {},
) {
    if (!state.visible) return
    val colors = mangaReaderPanelColors(state.background, isSystemInDarkTheme(), MaterialTheme.colorScheme)
    ReaderOptionsPanelHost(
        colors = if (eInkMode) colors.forEInk() else colors,
        surfaceMode = rememberReaderPanelSurfaceMode(eInkMode),
        onDismissRequest = callbacks.onDismiss,
        quickLayer = {
            ReaderMangaQuickLayer(state, callbacks, translationAvailable, translationActive)
        },
        details = { dragModifier ->
            ReaderMangaDetailTabs(state, callbacks, translationTaskPanelContent, dragModifier)
        },
    )
}

@Composable
private fun ReaderMangaQuickLayer(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    translationAvailable: Boolean,
    translationActive: Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        ReaderIconChoiceBar(
            options = ReaderMode.entries.map { it.label() },
            selectedIndex = ReaderMode.entries.indexOf(state.mode),
            onSelected = { callbacks.onModeChanged(ReaderMode.entries[it]) },
            icon = { index ->
                Icon(
                    painter = painterResource(ReaderMode.entries[index].iconResId()),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
        )
        ReaderQuickActionGrid(
            actions = mangaQuickActions(translationAvailable, translationActive),
            onClick = { action ->
                val handler = when (MangaQuickActionId.valueOf(action.id)) {
                    MangaQuickActionId.CHAPTERS -> callbacks.onPages
                    MangaQuickActionId.BOOKMARK -> callbacks.onBookmark
                    MangaQuickActionId.SAVE_PAGE -> callbacks.onSavePage
                    MangaQuickActionId.CROP_NOTE -> callbacks.onCropNote
                    MangaQuickActionId.AUTO_SCROLL -> callbacks.onAutoScroll
                    MangaQuickActionId.ROTATE -> callbacks.onRotate
                    MangaQuickActionId.DOWNLOAD -> callbacks.onDownload
                    MangaQuickActionId.BROWSER -> callbacks.onOpenBrowser
                    MangaQuickActionId.TRANSLATE -> callbacks.onTranslation
                }
                callbacks.onDismiss()
                handler()
            },
        )
    }
}

@Composable
private fun ReaderMangaDetailTabs(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    translationTaskPanelContent: @Composable () -> Unit,
    dragModifier: Modifier,
) {
    val tabs = ReaderOptionsTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize()) {
        ReaderPanelTabBar(
            labels = tabs.map { stringResource(it.labelResId) },
            selectedIndex = pagerState.currentPage,
            onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
            modifier = dragModifier,
            trailing = {
                IconButton(onClick = { callbacks.onDismiss(); callbacks.onOpenSettings() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.settings),
                    )
                }
            },
        )
        HorizontalPager(
            state = pagerState,
            overscrollEffect = null,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            when (tabs[page]) {
                ReaderOptionsTab.LAYOUT -> ReaderLayoutOptionsPage(state, callbacks)
                ReaderOptionsTab.DISPLAY -> ReaderDisplayOptionsPage(state, callbacks)
                ReaderOptionsTab.TRANSLATION -> ReaderTranslationOptionsPage(state, callbacks, translationTaskPanelContent)
            }
        }
    }
}
```

- [ ] **Step 3: 新「版式」页**

```kotlin
@Composable
private fun ReaderLayoutOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
) {
    val animationLabels = stringArrayResource(R.array.reader_animation)
    val zoomLabels = stringArrayResource(R.array.zoom_modes)
    OptionsPageList {
        if (state.mode == ReaderMode.CONTINUOUS_HORIZONTAL) {
            // Direction is a preference of its own rather than another mode entry: the mode says
            // how pages are laid out, this says which way they are read.
            item {
                ReaderOptionGroup {
                    ReaderOptionSwitchRow(
                        label = stringResource(R.string.continuous_horizontal_reversed),
                        checked = state.continuousHorizontalReversed,
                        onCheckedChange = callbacks.onContinuousHorizontalReversedChanged,
                    )
                }
            }
        }
        item {
            ReaderOptionSection(stringResource(R.string.pages_animation)) {
                ReaderChoiceChips(
                    options = ReaderAnimation.entries.mapIndexed { index, animation ->
                        animationLabels.getOrElse(index) { animation.name }
                    },
                    selectedIndex = ReaderAnimation.entries.indexOf(state.animation),
                    onSelected = { callbacks.onAnimationChanged(ReaderAnimation.entries[it]) },
                    icon = { ReaderAnimationIcon(ReaderAnimation.entries[it]) },
                )
            }
        }
        item {
            ReaderOptionSection(stringResource(R.string.scale_mode)) {
                ReaderChoiceChips(
                    options = ZoomMode.entries.mapIndexed { index, mode -> zoomLabels.getOrElse(index) { mode.name } },
                    selectedIndex = ZoomMode.entries.indexOf(state.zoomMode),
                    onSelected = { callbacks.onZoomModeChanged(ZoomMode.entries[it]) },
                    icon = { index ->
                        Icon(
                            painter = painterResource(ZoomMode.entries[index].iconResId()),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
        item {
            ReaderOptionGroup(title = stringResource(R.string.reader_panel_section_two_pages)) {
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
                if (state.doublePage) {
                    // Only has an effect while landscape double pages are on.
                    ReaderOptionDivider()
                    ReaderSliderRow(
                        label = stringResource(R.string.two_page_scroll_sensitivity),
                        valueLabel = "${(state.doublePageSensitivity * 100).toInt()}%",
                        value = state.doublePageSensitivity,
                        valueRange = 0f..1f,
                        onValueChange = callbacks.onDoublePageSensitivityChanged,
                    )
                }
            }
        }
        item {
            ReaderOptionGroup {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.crop_pages),
                    checked = state.cropPages,
                    onCheckedChange = callbacks.onCropPagesChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.split_double_pages),
                    checked = state.splitPages,
                    onCheckedChange = callbacks.onSplitPagesChanged,
                )
            }
        }
        item {
            ReaderOptionGroup {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.fullscreen_mode),
                    checked = state.fullscreen,
                    onCheckedChange = callbacks.onFullscreenChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.show_pages_numbers),
                    checked = state.pageNumbers,
                    onCheckedChange = callbacks.onPageNumbersChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_chapter_title_at_bottom),
                    checked = state.chapterTitleAtBottom,
                    onCheckedChange = callbacks.onChapterTitleAtBottomChanged,
                )
            }
        }
        item {
            // One switch per renderer family, so the experimental paged engine can be tried
            // without turning the webtoon renderer off. Engine preferences, editable in any mode.
            ReaderOptionGroup(title = stringResource(R.string.reader_panel_section_renderer)) {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_scene_renderer_webtoon),
                    checked = state.webtoonSceneReader,
                    onCheckedChange = callbacks.onWebtoonSceneReaderChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_scene_renderer_paged),
                    checked = state.pagedSceneReader,
                    onCheckedChange = callbacks.onPagedSceneReaderChanged,
                )
            }
        }
        item {
            ReaderOptionGroup(title = stringResource(R.string.reader_panel_section_performance)) {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_optimize),
                    checked = state.optimization,
                    onCheckedChange = callbacks.onOptimizationChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_reduce_page_preloading),
                    checked = state.preloadReduction,
                    onCheckedChange = callbacks.onPreloadReductionChanged,
                )
            }
        }
    }
}

private fun ZoomMode.iconResId(): Int = when (this) {
    ZoomMode.FIT_CENTER -> R.drawable.ic_fullscreen
    ZoomMode.FIT_HEIGHT -> R.drawable.ic_swap_vert
    ZoomMode.FIT_WIDTH -> R.drawable.ic_move_horizontal
    ZoomMode.KEEP_START -> R.drawable.ic_size_large
}
```

- [ ] **Step 4: 「画面」页与背景色块**

把 `ReaderDisplayOptionsPage` 替换为：

```kotlin
@Composable
private fun ReaderDisplayOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
) {
    OptionsPageList {
        item {
            ReaderOptionSection(stringResource(R.string.background)) {
                ReaderBackgroundSwatches(selected = state.background, onSelected = callbacks.onBackgroundChanged)
            }
        }
        item {
            ReaderOptionGroup {
                ReaderImageComparisonPreview(
                    originalPreviewModel = state.appearancePreviewOriginalUri,
                    processedPreviewModel = state.appearancePreviewProcessedUri,
                    colorFilter = state.colorFilter,
                    isLoading = state.appearancePreviewLoading,
                    modifier = Modifier.padding(8.dp),
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
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
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
        item {
            ReaderOptionGroup {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_super_resolution),
                    checked = state.superResolution,
                    onCheckedChange = callbacks.onSuperResolutionChanged,
                )
            }
        }
        state.imageServer?.let { imageServer ->
            item {
                val automatic = stringResource(R.string.automatic)
                val labels = imageServer.entries.map { it.label ?: automatic }
                val selected = imageServer.entries.indexOfFirst { it.value == imageServer.selectedValue }.coerceAtLeast(0)
                ReaderOptionGroup {
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderBackgroundSwatches(
    selected: ReaderBackground,
    onSelected: (ReaderBackground) -> Unit,
) {
    val labels = stringArrayResource(R.array.reader_backgrounds)
    val colors = currentReaderPanelColors()
    // Wraps instead of scrolling, so no swatch is cut off at the edge.
    FlowRow(
        maxItemsInEachRow = 3,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ReaderBackground.entries.forEachIndexed { index, background ->
            val isSelected = background == selected
            val shape = RoundedCornerShape(16.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (isSelected) colors.selectedContainer else colors.card)
                    .border(1.dp, if (isSelected) colors.accent else Color.Transparent, shape)
                    .tvFocusable(shape = shape, addFocusTarget = false)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelected(background) })
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                ReaderBackgroundIcon(background)
                Text(
                    text = labels.getOrElse(index) { background.name },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) colors.content else colors.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
```

`ReaderBackgroundIcon` 中改两行，让「浅色 / 深色」色块显示真实的明暗（P7）：

```kotlin
            ReaderBackground.LIGHT -> drawCircle(Color(0xFFF1F0F4), radius)
            ReaderBackground.DARK -> drawCircle(Color(0xFF2A292E), radius)
```

- [ ] **Step 5: 翻译页替换两处 `ReaderSegmentedChoice`，并统一列表间距**

`ReaderTranslationOptionsPage` 中的设置 / 日志切换替换为：

```kotlin
        ReaderChoiceChips(
            options = listOf(
                stringResource(R.string.reader_translation_tab_settings),
                stringResource(R.string.reader_translation_tab_logs),
            ),
            selectedIndex = selectedTab.ordinal,
            onSelected = { selectedTab = TranslationPageTab.entries[it] },
            height = 40.dp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
```

`ReaderTranslationSettingsContent` 中 OCR 模式的 `ReaderSegmentedChoice(...)` 替换为：

```kotlin
                ReaderOptionSection(
                    title = stringResource(R.string.reader_translation_ocr_mode),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    ReaderChoiceChips(
                        options = listOf(
                            stringResource(R.string.reader_translation_ocr_mode_basic),
                            stringResource(R.string.reader_translation_ocr_mode_advanced),
                        ),
                        selectedIndex = ReaderOcrMode.entries.indexOf(state.translationOcrMode),
                        onSelected = { callbacks.onTranslationOcrModeChanged(ReaderOcrMode.entries[it]) },
                        height = 44.dp,
                        // Chips sit inside a card here, so unselected chips take the panel colour.
                        unselectedColor = currentReaderPanelColors().container,
                    )
                }
```

`OptionsPageList` 改为：

```kotlin
@Composable
private fun OptionsPageList(
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}
```

- [ ] **Step 6: 整理 import**

新增：`androidx.compose.foundation.background`、`androidx.compose.foundation.border`、`androidx.compose.foundation.isSystemInDarkTheme`、`androidx.compose.foundation.selection.selectable`、`androidx.compose.ui.draw.clip`、`androidx.compose.material3.IconButton`、`org.skepsun.kototoro.core.ui.adaptive.tvFocusable`、`org.skepsun.kototoro.reader.ui.compose.design.{ReaderChoiceChips, ReaderIconChoiceBar, ReaderOptionSection, ReaderPanelTabBar, ReaderQuickActionGrid, ReaderSliderRow, currentReaderPanelColors, forEInk, mangaReaderPanelColors}`、`org.skepsun.kototoro.reader.ui.compose.panel.{ReaderOptionsPanelHost, rememberReaderPanelSurfaceMode}`。删除不再使用的 `ReaderSegmentedChoice`、`BorderStroke`、`horizontalScroll`、`rememberScrollState` 等 import（以编译器 / IDE 的未使用警告为准）。

- [ ] **Step 7: 接入 scaffold**

`ComposeReaderActivityScaffold.kt` 中把

```kotlin
            if (state.options.visible) {
                ReaderAnchoredBottomSheet(
                    onDismissRequest = callbacks.options.onDismiss,
                ) { sheetDragModifier ->
                    ComposeReaderOptionsSheet(
                        state = state.options,
                        callbacks = callbacks.options,
                        embedded = true,
                        translationTaskPanelContent = translationTaskPanelContent,
                        headerModifier = sheetDragModifier,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxSize(),
                    )
                }
```

替换为

```kotlin
            if (state.options.visible) {
                // In the reader window (not a dialog) so the panel's glass can show the page.
                ComposeReaderOptionsPanel(
                    state = state.options,
                    callbacks = callbacks.options,
                    translationAvailable = state.actions.translateRequestedVisible,
                    translationActive = state.actions.translateActive,
                    eInkMode = state.eInkModeEnabled,
                    translationTaskPanelContent = translationTaskPanelContent,
                )
```

（保留原有的闭合括号结构。）确认该位置在 `CompositionLocalProvider(LocalLiquidGlassBackdrop provides readerBackdrop, ...)` 内、带 `layerBackdrop` 的内容 `Box` 之后：`grep -n "layerBackdrop(it)\|ComposeReaderOptionsPanel(" app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderActivityScaffold.kt` 中后者行号应大于前者。

- [ ] **Step 8: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若 `ReaderAnchoredBottomSheet` 的 import 变为未使用，不要删它（同文件的章节面板仍在使用）。

- [ ] **Step 9: 模拟器冒烟**

```bash
./gradlew :app:installDebug
```

打开任一漫画 → 点击屏幕 → 点 ⋮。截图并查看 PNG：面板以 Peek 打开，只显示模式图标条和 2 行 tile；上拉后出现 3 个 tab；返回键带动画关闭。

- [ ] **Step 10: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderOptionsSheet.kt app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderActivityScaffold.kt
git commit -m "feat(reader): redesign the manga reader options panel"
```

---

### Task 9: 小说快捷操作

**Files:**
- Create: `main/reader/novel/compose/NovelReaderQuickActions.kt`
- Test: `test/reader/novel/compose/NovelReaderQuickActionsTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NovelReaderQuickActionsTest {

    @Test
    fun `four actions with translation as the only toggle`() {
        val actions = novelQuickActions(translationEnabled = true)
        assertEquals(listOf("TTS", "BOOKMARK", "MARKINGS", "TRANSLATE"), actions.map { it.id })
        actions.dropLast(1).forEach { assertNull(it.toggled) }
        assertEquals(true, actions.last().toggled)
        assertEquals(false, novelQuickActions(translationEnabled = false).last().toggled)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.reader.novel.compose.NovelReaderQuickActionsTest" --no-daemon`
Expected: 编译失败，`Unresolved reference: novelQuickActions`。

- [ ] **Step 3: 实现**

```kotlin
package org.skepsun.kototoro.reader.novel.compose

import org.skepsun.kototoro.R
import org.skepsun.kototoro.reader.ui.compose.design.ReaderQuickAction

internal enum class NovelQuickActionId(val iconResId: Int, val labelResId: Int) {
    TTS(R.drawable.ic_voice_input, R.string.tts_settings_title),
    BOOKMARK(R.drawable.ic_bookmark_added, R.string.bookmark_add),
    MARKINGS(R.drawable.ic_bookmark, R.string.novel_reader_bookmarks_notes),
    TRANSLATE(R.drawable.ic_translate, R.string.novel_reader_translation_enabled),
}

internal fun novelQuickActions(translationEnabled: Boolean): List<ReaderQuickAction> =
    NovelQuickActionId.entries.map { id ->
        ReaderQuickAction(
            id = id.name,
            iconResId = id.iconResId,
            labelResId = id.labelResId,
            toggled = if (id == NovelQuickActionId.TRANSLATE) translationEnabled else null,
        )
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: 同 Step 2。Expected: 1 test PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/reader/novel/compose/NovelReaderQuickActions.kt app/src/test/kotlin/org/skepsun/kototoro/reader/novel/compose/NovelReaderQuickActionsTest.kt
git commit -m "feat(reader): list the novel reader's quick actions"
```

---

### Task 10: 小说面板改写与接入

**Files:**
- Modify: `main/reader/novel/compose/ComposeNovelReaderOptionsSheet.kt`（整文件替换）
- Modify: `main/reader/novel/compose/NovelReaderChrome.kt`
- Modify: `main/reader/novel/NovelReaderActivity.kt`

- [ ] **Step 1: 整文件替换 `ComposeNovelReaderOptionsSheet.kt`**

```kotlin
package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.reader.novel.NovelPageTurnAnimation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelReaderThemePreset
import org.skepsun.kototoro.reader.novel.NovelTranslationDisplayMode
import org.skepsun.kototoro.reader.novel.ReadingMode
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import org.skepsun.kototoro.reader.ui.compose.ReaderAnimationIcon
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChoiceChips
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSection
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelTabBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelToggleChip
import org.skepsun.kototoro.reader.ui.compose.design.ReaderQuickActionGrid
import org.skepsun.kototoro.reader.ui.compose.design.ReaderSliderRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderStepperRow
import org.skepsun.kototoro.reader.ui.compose.design.currentReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.forEInk
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderOptionsPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.rememberReaderPanelSurfaceMode
import kotlin.math.roundToInt

private const val DEFAULT_READER_BRIGHTNESS = 0.8f

private typealias NovelSettingsUpdate = (NovelReaderSettings.() -> NovelReaderSettings) -> Unit

private enum class NovelOptionsTab(val labelResId: Int) {
    TYPOGRAPHY(R.string.novel_reader_tab_typography),
    READING(R.string.novel_reader_tab_reading),
    TRANSLATION_TOOLS(R.string.novel_reader_tab_translation_tools),
}

@Composable
internal fun ComposeNovelReaderOptionsSheet(
    settings: NovelReaderSettings,
    onDismiss: () -> Unit,
    onSettingsChanged: (NovelReaderSettings) -> Unit,
    onToggleTranslation: () -> Unit,
    replaceRulesEnabled: Boolean = true,
    onToggleReplaceRules: () -> Unit = {},
    onShowReplaceRules: () -> Unit = {},
    onShowMarkings: () -> Unit = {},
    onBookmark: () -> Unit = {},
    onTts: () -> Unit,
    onClearTranslationCache: () -> Unit,
    eInkMode: Boolean = false,
) {
    fun update(transform: NovelReaderSettings.() -> NovelReaderSettings) {
        onSettingsChanged(settings.transform().normalized())
    }
    val colors = novelReaderPanelColors(novelReaderPalette(settings.themePreset, isSystemInDarkTheme()))
    ReaderOptionsPanelHost(
        colors = if (eInkMode) colors.forEInk() else colors,
        surfaceMode = rememberReaderPanelSurfaceMode(eInkMode),
        onDismissRequest = onDismiss,
        quickLayer = {
            NovelQuickLayer(
                settings = settings,
                update = ::update,
                onQuickAction = { id ->
                    when (id) {
                        NovelQuickActionId.TTS -> { onTts(); onDismiss() }
                        NovelQuickActionId.BOOKMARK -> { onBookmark(); onDismiss() }
                        NovelQuickActionId.MARKINGS -> { onShowMarkings(); onDismiss() }
                        NovelQuickActionId.TRANSLATE -> onToggleTranslation()
                    }
                },
            )
        },
        details = { dragModifier ->
            NovelDetailTabs(
                settings = settings,
                update = ::update,
                dragModifier = dragModifier,
                onToggleTranslation = onToggleTranslation,
                onClearTranslationCache = onClearTranslationCache,
                replaceRulesEnabled = replaceRulesEnabled,
                onToggleReplaceRules = onToggleReplaceRules,
                onShowReplaceRules = { onShowReplaceRules(); onDismiss() },
                onReset = { onSettingsChanged(NovelReaderSettings()) },
            )
        },
    )
}

@Composable
private fun NovelQuickLayer(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
    onQuickAction: (NovelQuickActionId) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        ReaderSliderRow(
            label = stringResource(R.string.brightness),
            valueLabel = settings.screenBrightness?.let { "${(it * 100f).roundToInt()}%" }
                ?: stringResource(R.string.follow_system),
            value = settings.screenBrightness ?: DEFAULT_READER_BRIGHTNESS,
            valueRange = NovelReaderSettings.SCREEN_BRIGHTNESS_RANGE,
            // Dragging sets a brightness of the reader's own, which ends "follow system".
            onValueChange = { value -> update { copy(screenBrightness = value) } },
            leadingIcon = R.drawable.ic_lightbulb,
            trailing = {
                ReaderPanelToggleChip(
                    label = stringResource(R.string.follow_system),
                    checked = settings.screenBrightness == null,
                    onCheckedChange = { followSystem ->
                        update {
                            copy(screenBrightness = if (followSystem) null else screenBrightness ?: DEFAULT_READER_BRIGHTNESS)
                        }
                    },
                )
            },
            contentPadding = PaddingValues(0.dp),
        )
        NovelFontSizeStepper(settings, update, contentPadding = PaddingValues(0.dp))
        NovelThemeSwatches(
            selected = settings.themePreset,
            onSelected = { preset -> update { copy(themePreset = preset) } },
        )
        ReaderQuickActionGrid(
            actions = novelQuickActions(settings.isTranslationEnabled),
            onClick = { onQuickAction(NovelQuickActionId.valueOf(it.id)) },
        )
    }
}

@Composable
private fun NovelFontSizeStepper(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
) {
    ReaderStepperRow(
        label = stringResource(R.string.novel_font_size),
        valueLabel = "%.1fsp".format(settings.fontSizeSp),
        value = settings.fontSizeSp,
        valueRange = NovelReaderSettings.FONT_SIZE_RANGE,
        step = 1f,
        onValueChange = { value -> update { copy(fontSizeSp = value) } },
        contentPadding = contentPadding,
    )
}

@Composable
private fun NovelThemeSwatches(
    selected: NovelReaderThemePreset,
    onSelected: (NovelReaderThemePreset) -> Unit,
) {
    val colors = currentReaderPanelColors()
    val isDark = isSystemInDarkTheme()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NovelReaderThemePreset.entries.forEach { preset ->
            val palette = novelReaderPalette(preset, isDark)
            val isSelected = preset == selected
            // Each swatch is drawn in its own theme, so it previews the page it would give.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(Capsule())
                    .background(Color(palette.backgroundColor))
                    .border(if (isSelected) 2.dp else 1.dp, if (isSelected) colors.accent else colors.divider, Capsule())
                    .tvFocusable(shape = Capsule(), addFocusTarget = false)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelected(preset) }),
            ) {
                Text(
                    text = stringResource(preset.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(palette.textColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NovelDetailTabs(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
    dragModifier: Modifier,
    onToggleTranslation: () -> Unit,
    onClearTranslationCache: () -> Unit,
    replaceRulesEnabled: Boolean,
    onToggleReplaceRules: () -> Unit,
    onShowReplaceRules: () -> Unit,
    onReset: () -> Unit,
) {
    val tabs = NovelOptionsTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize()) {
        ReaderPanelTabBar(
            labels = tabs.map { stringResource(it.labelResId) },
            selectedIndex = pagerState.currentPage,
            onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
            modifier = dragModifier,
        )
        HorizontalPager(
            state = pagerState,
            overscrollEffect = null,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            when (tabs[page]) {
                NovelOptionsTab.TYPOGRAPHY -> NovelTypographyPage(settings, update)
                NovelOptionsTab.READING -> NovelReadingPage(settings, update)
                NovelOptionsTab.TRANSLATION_TOOLS -> NovelTranslationToolsPage(
                    settings = settings,
                    update = update,
                    onToggleTranslation = onToggleTranslation,
                    onClearTranslationCache = onClearTranslationCache,
                    replaceRulesEnabled = replaceRulesEnabled,
                    onToggleReplaceRules = onToggleReplaceRules,
                    onShowReplaceRules = onShowReplaceRules,
                    onReset = onReset,
                )
            }
        }
    }
}

@Composable
private fun NovelTypographyPage(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
) = NovelOptionsPageList {
    item {
        ReaderOptionGroup {
            NovelReaderFontOptionRow(
                selected = settings.font,
                onSelected = { update { copy(font = it) } },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            ReaderOptionDivider()
            NovelFontSizeStepper(settings, update)
            ReaderOptionDivider()
            ReaderStepperRow(
                label = stringResource(R.string.novel_line_spacing),
                valueLabel = "%.1f".format(settings.lineSpacing),
                value = settings.lineSpacing,
                valueRange = NovelReaderSettings.LINE_SPACING_RANGE,
                step = NovelReaderSettings.LINE_SPACING_STEP,
                onValueChange = { value -> update { copy(lineSpacing = value) } },
            )
            ReaderOptionDivider()
            ReaderStepperRow(
                label = stringResource(R.string.novel_paragraph_spacing),
                valueLabel = stringResource(R.string.novel_paragraph_spacing_value, settings.paragraphSpacingLines),
                value = settings.paragraphSpacing,
                valueRange = NovelReaderSettings.PARAGRAPH_SPACING_RANGE,
                step = NovelReaderSettings.PARAGRAPH_SPACING_STEP,
                onValueChange = { value -> update { copy(paragraphSpacing = value) } },
            )
            ReaderOptionDivider()
            NovelMarginStepper(
                label = stringResource(R.string.novel_margin_horizontal),
                value = settings.marginHorizontal,
                onValueChange = { value -> update { copy(marginHorizontal = value) } },
            )
            ReaderOptionDivider()
            NovelMarginStepper(
                label = stringResource(R.string.novel_margin_vertical),
                value = settings.marginVertical,
                onValueChange = { value -> update { copy(marginVertical = value) } },
            )
            ReaderOptionDivider()
            ReaderOptionSwitchRow(
                label = stringResource(R.string.novel_first_line_indent),
                checked = settings.enableParagraphIndent,
                onCheckedChange = { update { copy(enableParagraphIndent = it) } },
            )
        }
    }
}

@Composable
private fun NovelMarginStepper(label: String, value: Int, onValueChange: (Int) -> Unit) {
    ReaderStepperRow(
        label = label,
        valueLabel = "${value}dp",
        value = value.toFloat(),
        valueRange = NovelReaderSettings.MARGIN_RANGE.first.toFloat()..NovelReaderSettings.MARGIN_RANGE.last.toFloat(),
        step = NovelReaderSettings.MARGIN_STEP.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
    )
}

@Composable
private fun NovelReadingPage(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
) = NovelOptionsPageList {
    item {
        ReaderOptionSection(stringResource(R.string.novel_reading_mode)) {
            ReaderChoiceChips(
                options = listOf(stringResource(R.string.novel_mode_paged), stringResource(R.string.novel_mode_scroll)),
                selectedIndex = if (settings.readingMode == ReadingMode.PAGED) 0 else 1,
                onSelected = { update { copy(readingMode = if (it == 0) ReadingMode.PAGED else ReadingMode.SCROLL) } },
                icon = { NovelReadingModeIcon(it) },
            )
        }
    }
    if (settings.readingMode == ReadingMode.PAGED) {
        item {
            ReaderOptionSection(stringResource(R.string.novel_page_turn_animation)) {
                ReaderChoiceChips(
                    options = NovelPageTurnAnimation.entries.map { stringResource(it.label) },
                    selectedIndex = NovelPageTurnAnimation.entries.indexOf(settings.pageTurnAnimation),
                    onSelected = { update { copy(pageTurnAnimation = NovelPageTurnAnimation.entries[it]) } },
                    icon = { NovelPageAnimationIcon(NovelPageTurnAnimation.entries[it]) },
                )
            }
        }
    }
    item {
        ReaderOptionGroup {
            NovelSwitchRows(settings, update)
        }
    }
}

@Composable
private fun NovelTranslationToolsPage(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
    onToggleTranslation: () -> Unit,
    onClearTranslationCache: () -> Unit,
    replaceRulesEnabled: Boolean,
    onToggleReplaceRules: () -> Unit,
    onShowReplaceRules: () -> Unit,
    onReset: () -> Unit,
) = NovelOptionsPageList {
    item {
        ReaderOptionGroup(title = stringResource(R.string.novel_reader_translation_section)) {
            ReaderOptionSwitchRow(
                label = stringResource(R.string.novel_reader_translation_enabled),
                checked = settings.isTranslationEnabled,
                onCheckedChange = { onToggleTranslation() },
            )
            ReaderOptionDivider()
            ReaderOptionSection(
                title = stringResource(R.string.novel_translation_display_mode),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                ReaderChoiceChips(
                    options = listOf(
                        stringResource(R.string.novel_translation_only),
                        stringResource(R.string.novel_translation_bilingual),
                    ),
                    selectedIndex = if (settings.translationDisplayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY) 0 else 1,
                    onSelected = { index ->
                        update {
                            copy(
                                translationDisplayMode = if (index == 0) {
                                    NovelTranslationDisplayMode.TRANSLATION_ONLY
                                } else {
                                    NovelTranslationDisplayMode.BILINGUAL
                                },
                            )
                        }
                    },
                    height = 44.dp,
                    // Inside a card: unselected chips take the panel colour so they stay visible.
                    unselectedColor = currentReaderPanelColors().container,
                )
            }
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onToggleTranslation, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_translate), contentDescription = null)
                Text(stringResource(R.string.novel_reader_translation_start), modifier = Modifier.padding(start = 8.dp))
            }
            FilledTonalButton(onClick = onClearTranslationCache, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                Text(stringResource(R.string.clear_translation_cache), modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                text = stringResource(R.string.novel_reader_translation_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = currentReaderPanelColors().contentSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    item {
        ReaderOptionGroup {
            NovelToolActionRow(
                icon = R.drawable.ic_replace,
                title = stringResource(R.string.replace_rule_effective_title),
                supporting = stringResource(R.string.novel_reader_replace_summary),
                onClick = onShowReplaceRules,
            )
            ReaderOptionDivider()
            ReaderOptionSwitchRow(
                label = stringResource(R.string.replace_rule_book_toggle),
                checked = replaceRulesEnabled,
                onCheckedChange = { onToggleReplaceRules() },
            )
        }
    }
    item { NovelResetRow(onReset) }
}

/** Reset asks first, in place: a destructive action should not fire on a single tap. */
@Composable
private fun NovelResetRow(onReset: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    ReaderOptionGroup {
        if (!confirming) {
            NovelToolActionRow(
                icon = R.drawable.ic_backup_restore,
                title = stringResource(R.string.novel_reset),
                supporting = null,
                onClick = { confirming = true },
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.novel_reader_reset_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = { confirming = false; onReset() }) {
                    Text(stringResource(R.string.reset))
                }
            }
        }
    }
}

@Composable
private fun NovelToolActionRow(
    icon: Int,
    title: String,
    supporting: String?,
    onClick: () -> Unit,
) {
    val colors = currentReaderPanelColors()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.selectedContainer),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = colors.content,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, color = colors.content)
                supporting?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.contentSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_forward),
                contentDescription = null,
                tint = colors.contentSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NovelOptionsPageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

@Composable
private fun NovelSwitchRows(
    settings: NovelReaderSettings,
    update: NovelSettingsUpdate,
) {
    ReaderOptionSwitchRow(
        label = stringResource(R.string.novel_dual_page_mode),
        checked = settings.enableDualPage,
        onCheckedChange = { update { copy(enableDualPage = it) } },
    )
    ReaderOptionDivider()
    ReaderOptionSwitchRow(
        label = stringResource(R.string.novel_fullscreen_mode),
        checked = settings.enableFullscreen,
        onCheckedChange = { update { copy(enableFullscreen = it) } },
    )
    ReaderOptionDivider()
    ReaderOptionSwitchRow(
        label = stringResource(R.string.novel_show_reading_status),
        checked = settings.showReadingStatus,
        onCheckedChange = { update { copy(showReadingStatus = it) } },
    )
    ReaderOptionDivider()
    ReaderOptionSwitchRow(
        label = stringResource(R.string.reader_chapter_title_at_bottom),
        checked = settings.chapterTitleAtBottom,
        onCheckedChange = { update { copy(chapterTitleAtBottom = it) } },
    )
    ReaderOptionDivider()
    ReaderOptionSwitchRow(
        label = stringResource(R.string.novel_transparent_status_bar),
        checked = settings.isReadingStatusTransparent,
        onCheckedChange = { update { copy(isReadingStatusTransparent = it) } },
    )
}

@Composable
private fun NovelReadingModeIcon(index: Int) {
    Icon(
        painter = painterResource(if (index == 0) R.drawable.ic_book_page else R.drawable.ic_gesture_vertical),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun NovelPageAnimationIcon(animation: NovelPageTurnAnimation) {
    ReaderAnimationIcon(
        if (animation == NovelPageTurnAnimation.SLIDE) ReaderAnimation.DEFAULT else ReaderAnimation.SIMULATION,
    )
}

private val NovelReaderThemePreset.label: Int get() = when (this) {
    NovelReaderThemePreset.PAPER -> R.string.novel_theme_paper
    NovelReaderThemePreset.SEPIA -> R.string.novel_theme_sepia
    NovelReaderThemePreset.MOSS -> R.string.novel_theme_moss
    NovelReaderThemePreset.SLATE -> R.string.novel_theme_slate
}

private val NovelPageTurnAnimation.label: Int get() = when (this) {
    NovelPageTurnAnimation.SLIDE -> R.string.novel_page_turn_slide
    NovelPageTurnAnimation.SIMULATION -> R.string.novel_page_turn_simulation
}
```

（该文件删除了 `ComposeNovelReaderOptionsPanel`（无调用方）、`SliderEditor` / `SliderEditorDialog`、`Action`、`asFloatRange`、`NovelReaderSliderRow`、`NovelOptionsTab` / `NovelOptionsPage`、`NovelThemeSwatch`，以及对 `ReaderAnchoredBottomSheet` 的依赖。）

- [ ] **Step 2: `NovelReaderChrome.kt` 移除面板调用**

删除 `NovelReaderBottomChrome` 中的整段：

```kotlin
    state.settings?.let { settings ->
        if (state.settingsSheetVisible) {
            ComposeNovelReaderOptionsSheet(
                ...
            )
        }
    }
```

并在 `BackHandler` 的 `when` 中删除 `state.settingsSheetVisible -> callbacks.onDismissSettings()` 分支，把 `enabled` 改为 `dismissiblePanelVisible && !state.settingsSheetVisible`（面板自己的 `BackHandler` 负责带动画关闭）。先用 `grep -n "dismissiblePanelVisible" app/src/main/kotlin/org/skepsun/kototoro/reader/novel/compose/NovelReaderChrome.kt` 确认该变量的其他用途不受影响（只改 `BackHandler` 这一处）。

- [ ] **Step 3: `NovelReaderActivity.kt` 在根 Box 渲染**

在 `NovelReaderBottomChrome(...)` 所在的 `Box(modifier = Modifier.align(Alignment.BottomCenter)) { ... }` 之后、`ttsVoiceDialogState?.let` 之前插入：

```kotlin
                        state.settings?.let { novelSettings ->
                            if (state.settingsSheetVisible) {
                                // In the reader window (not a dialog, not inside the bottom-aligned
                                // chrome) so the panel covers the screen and its glass shows the page.
                                ComposeNovelReaderOptionsSheet(
                                    settings = novelSettings,
                                    onDismiss = callbacks.onDismissSettings,
                                    onSettingsChanged = callbacks.onSettingsChanged,
                                    onToggleTranslation = callbacks.onToggleTranslation,
                                    replaceRulesEnabled = state.replaceRulesEnabled,
                                    onToggleReplaceRules = callbacks.onToggleReplaceRules,
                                    onShowReplaceRules = callbacks.onShowReplaceRules,
                                    onShowMarkings = callbacks.onShowMarkings,
                                    onBookmark = callbacks.onBookmark,
                                    onTts = callbacks.onTts,
                                    onClearTranslationCache = callbacks.onClearTranslationCache,
                                    eInkMode = isEInkModeEnabled,
                                )
                            }
                        }
```

补 import `org.skepsun.kototoro.reader.novel.compose.ComposeNovelReaderOptionsSheet`（若尚未导入）。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。`ComposeNovelReader.kt:928` 的旧调用使用具名参数且新参数 `eInkMode` 有默认值，应能直接编译；若报错，按新签名补齐。

- [ ] **Step 5: 模拟器冒烟**

`./gradlew :app:installDebug`，打开任一小说 → 点击屏幕 → 点 ⋮。截图并查看：Peek 显示亮度、字号、主题色块、4 个 tile；棕褐主题下卡片为暖色，不再是灰白；重置先出现确认。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/reader/novel/compose/ComposeNovelReaderOptionsSheet.kt app/src/main/kotlin/org/skepsun/kototoro/reader/novel/compose/NovelReaderChrome.kt app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelReaderActivity.kt
git commit -m "feat(reader): redesign the novel reader options panel"
```

---

### Task 11: 删除 `ReaderSegmentedChoice`

**Files:**
- Modify: `main/reader/ui/compose/design/ReaderOptionControls.kt`

- [ ] **Step 1: 确认无调用方**

Run: `grep -rn "ReaderSegmentedChoice" app/src`
Expected: 只剩 `ReaderOptionControls.kt` 中的定义（`ReaderSegmentedChoice` 与 `ReaderSegmentedChoiceOptions`）。

- [ ] **Step 2: 删除这两个函数及仅它们使用的 import，然后编译**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/design/ReaderOptionControls.kt
git commit -m "refactor(reader): drop the unused segmented choice"
```

---

### Task 12: 全量验证

- [ ] **Step 1: 全部单测**

Run: `./gradlew :app:testDebugUnitTest --no-daemon`
Expected: BUILD SUCCESSFUL；新增的 7 个测试类全部通过，已有测试无回归。有失败时贴出输出，不要跳过。

- [ ] **Step 2: 安装并截图检查**（每张截图都必须实际打开 PNG 查看）

```bash
./gradlew :app:installDebug
adb exec-out screencap -p > <scratchpad>/<name>.png
```

逐项检查并截图：
1. 小说 × 棕褐 / Slate × 系统明 / 暗：Peek、上拉、三个 tab；卡片与主题同色系。
2. 小说重置：先确认，取消后恢复原样。
3. 漫画 × 默认 / 黑色背景：模式图标条不折行；背景色块完整不被裁切；「深色」色块为深色。
4. 设置 → 界面风格切到 iOS：两个面板为玻璃，透出页面；在深色繁杂的漫画页上文字清晰。若出现额外浅色矩形、圆角外露矩形等伪影，按 `kototoro-glass-panel-artifacts` skill 排查。
5. 拖动：Peek ↔ 3/4 ↔ 全屏 ↔ 关闭；列表滚到顶后继续下拉能收起面板。
6. 返回键：面板带动画关闭，且不会连带退出阅读器。
7. 回归：主页搜索筛选面板（`SearchFilterSheet`，仍走 Dialog 版 sheet）能正常打开、拖动、返回键关闭。
8. 横屏：快捷层超过 60% 时以 3/4 打开，不遮挡内容。

- [ ] **Step 3: 记录结果**

把发现的问题逐条修复并单独提交（`fix(reader): ...`）；无法在本分支修复的，写进最终汇报。

---

### Task 13: 同步 spec

**Files:**
- Modify: `docs/superpowers/specs/2026-09-28-reader-options-panel-redesign-design.md`

- [ ] **Step 1: 把本计划开头「对 spec 的细化」四条写回 spec**

需要更新的位置：§4 表格中 `ReaderPanelTabBar`、`ReaderStepperRow` 两行；§5.1 翻译 tile 的判断条件；§5.2 亮度一项。

- [ ] **Step 2: 提交**

```bash
git add docs/superpowers/specs/2026-09-28-reader-options-panel-redesign-design.md
git commit -m "docs: align the options panel spec with the implementation"
```
