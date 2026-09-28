# 平板源作品列表 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 平板上的源作品列表改为「过滤抽屉 + 预览卡片」双浮层、网格永不 reflow；从详情返回时保留预览、过滤开合与滚动位置；预览卡片与过滤头部重做。

**Architecture:**
- 新增纯函数尺寸分级 `tabletLayoutClass` 与浮层互斥判断。
- 面板状态移入 `RemoteListViewModel` 持有的 `SearchPanelsController`（SavedStateHandle 持久化 + 内存缓存预览 `Content`）。
- `SearchContentListScreen` 删除分栏分支，宽屏统一为全宽网格加左右两个浮层。
- 新增 `SearchPreviewCard` 替换旧的预览面板与浮动卡片。

**Tech Stack:** Kotlin, Compose, Hilt ViewModel + SavedStateHandle, JUnit5。

**Spec:** `docs/superpowers/specs/2026-09-28-tablet-layouts-design.md` §2、§3

**已确认的 L6 根因**（日志实证）：
- ViewModel 未被重建。
- 返回时 `contentListItems` 先为空，`SearchContentListScreen.kt:522-533` 的恢复 effect 走进「找不到且不在加载」分支，调用 `clearPreview()`。
- 因 SIDE_PANE 预览会强制 `showFilterPanel = true`，预览清掉后侧栏回到过滤。

**对 spec 的细化：** 过滤抽屉不做「点外部关闭」（否则点作品会先关抽屉，与「两者可同开」冲突），只能通过过滤按钮、✕ 或返回键关闭。

通用：
- 单测命令：`./gradlew :app:testDebugUnitTest --tests "<FQCN>"`。
- 平板截图：`adb shell wm size 2560x1600` / `1600x2560`，`adb shell wm density 320`；结束后执行 `wm size reset` 与 `wm density reset`。
- 以本地存储作为测试源：`adb shell am start -S -n org.skepsun.kototoro.debug/org.skepsun.kototoro.search.ui.ContentListActivity -a org.skepsun.kototoro.debug.action.EXPLORE_MANGA --es source LOCAL`。
- commit 末尾附 Co-Authored-By / Claude-Session 两行。

---

### Task 1: 尺寸分级与浮层互斥（纯函数）

**Files:**
- Create: `main/core/ui/adaptive/TabletLayout.kt`
- Test: `test/core/ui/adaptive/TabletLayoutTest.kt`

```kotlin
enum class TabletLayoutClass { COMPACT, MEDIUM, EXPANDED }

fun tabletLayoutClass(widthDp: Int, tabletLayoutEnabled: Boolean): TabletLayoutClass = when {
    !tabletLayoutEnabled || widthDp < 600 -> TabletLayoutClass.COMPACT
    widthDp < 1000 -> TabletLayoutClass.MEDIUM
    else -> TabletLayoutClass.EXPANDED
}

object TabletLayoutTokens {
    val FilterDrawerWidth = 300.dp
    val PreviewCardWidth = 380.dp
    val PreviewCardMediumWidth = 360.dp
    val OverlayMargin = 12.dp
    val MinVisibleGridWidth = 240.dp
}

/** Preview card width for a window: 380dp, or on medium windows at most half the width. */
fun tabletPreviewCardWidth(windowWidth: Dp): Dp

/** Whether the filter drawer and the preview card can be open together and leave some grid visible. */
fun tabletOverlaysFitTogether(windowWidth: Dp): Boolean =
    windowWidth - TabletLayoutTokens.FilterDrawerWidth - tabletPreviewCardWidth(windowWidth) -
        TabletLayoutTokens.OverlayMargin * 3 >= TabletLayoutTokens.MinVisibleGridWidth
```

`tabletPreviewCardWidth` 的实现：`if (windowWidth >= 1000.dp) PreviewCardWidth else minOf(PreviewCardMediumWidth, windowWidth / 2)`。

测试覆盖：
- 599 / 600 / 999 / 1000 的分级边界。
- 关闭平板模式时一律 COMPACT。
- 1280dp → 两者可共存；800dp → 不可共存。
- 800dp 时预览宽为 360dp；640dp 时为 320dp。

步骤：写失败测试 → 实现 → 通过 → 提交 `feat(ui): classify tablet window widths`。

---

### Task 2: 面板状态控制器（修 L6 的核心）

**Files:**
- Create: `main/search/ui/SearchPanelsController.kt`
- Modify: `main/remotelist/ui/RemoteListViewModel.kt`（持有 controller）
- Modify: `main/core/prefs/AppSettings.kt:626`（`isTabletListFilterPanelDefaultOpen` 默认值 `true` 改为 `false`）
- Test: `test/search/ui/SearchPanelsControllerTest.kt`

```kotlin
@Immutable
data class SearchPanelsState(
    val filterOpen: Boolean = false,
    val previewContentId: Long? = null,
    /** In memory only; restored from the list after process death. */
    val previewContent: Content? = null,
)

class SearchPanelsController(
    private val savedStateHandle: SavedStateHandle,
    defaultFilterOpen: Boolean,
) {
    private val _state = MutableStateFlow(
        SearchPanelsState(
            filterOpen = savedStateHandle[KEY_FILTER_OPEN] ?: defaultFilterOpen,
            previewContentId = savedStateHandle[KEY_PREVIEW_ID],
        ),
    )
    val state: StateFlow<SearchPanelsState> = _state.asStateFlow()

    fun setFilterOpen(open: Boolean)
    fun openPreview(content: Content)            // 设置 id 与 content
    fun updatePreviewContent(content: Content)   // 仅当 id 匹配时替换（加载完详情）
    fun closePreview()
    /** After process death the id survives but the content does not; pick it up once the list has it. */
    fun restorePreviewFrom(candidates: List<Content>)

    private companion object {
        const val KEY_FILTER_OPEN = "search_panels_filter_open"
        const val KEY_PREVIEW_ID = "search_panels_preview_id"
    }
}
```

每次变更同步写入 `savedStateHandle`。

`RemoteListViewModel`：
```kotlin
val panels = SearchPanelsController(savedStateHandle, settings.isTabletListFilterPanelDefaultOpen)
```

测试：
- 默认值取构造参数；SavedStateHandle 中已有值时优先使用。
- `openPreview` 后 `closePreview` 清空 id 与 content，且 SavedStateHandle 同步。
- `updatePreviewContent` 在 id 不匹配时忽略。
- `restorePreviewFrom` 只在 content 为空且 id 命中时填充；列表为空时保持不变（L6 回归测试）。

步骤：写失败测试 → 实现 → 通过 → 提交 `fix(search): keep the list panels across details navigation`。

---

### Task 3: 列表页改为双浮层

**Files:** Modify `main/search/ui/compose/SearchContentListScreen.kt`

- [ ] 3.1 状态替换：
  - 删除 `showFilterPanel`（宽屏部分）、`wasFilterPanelOpenBeforePreview`、`sidePaneMode`、`previewContentId`、`previewContent`、`clearPreview()`、`closePreviewPane()`、`openFilterPaneFromPreview()`。
  - 新增 `val panels by viewModel.panels.state.collectAsStateWithLifecycle()`。
  - 手机端保留局部 `var showFilterSheet by rememberSaveable { mutableStateOf(false) }`，供原底部 sheet 使用。
  - 删除 `SearchSidePaneMode` 枚举（若无其它引用）。
- [ ] 3.2 删除 489-521 的「按设置重置」`LaunchedEffect`，替换为：

```kotlin
LaunchedEffect(isWideAdaptiveLayout, tabletListPreviewMode) {
    if (!isWideAdaptiveLayout || tabletListPreviewMode == TabletListPreviewMode.OFF) {
        viewModel.panels.closePreview()
    }
}
```

- [ ] 3.3 删除 522-533 的恢复 effect，替换为：

```kotlin
LaunchedEffect(contentListItems) {
    viewModel.panels.restorePreviewFrom(contentListItems.map { it.toContentWithOverride() })
}
```

  详情加载 effect（535-553）改为以 `panels.previewContentId` 为 key，成功后调用 `viewModel.panels.updatePreviewContent(details)`；loading / error 仍为局部 `remember` 状态。
- [ ] 3.4 `openContentOrPreview`：
  - 两种预览模式合并：再次点同一作品 → 进详情；否则调用 `viewModel.panels.openPreview(content)`。
  - 若 `!tabletOverlaysFitTogether(windowWidth)` 且过滤已开，则关闭过滤。
  - 过滤按钮：宽屏时 `setFilterOpen(!filterOpen)`，打开时若不能共存则 `closePreview()`；手机端切换 `showFilterSheet`。
- [ ] 3.5 布局：
  - 删除 `if (isWideSplitLayout)` 分支与分隔线，所有尺寸都走原「全宽」分支。
  - 用 `BoxWithConstraints` 取窗口宽。
  - 宽屏时在列表与顶栏之上叠加两个浮层（见下）。
  - 删除旧 `SearchFloatingPreviewCard` 调用。
- [ ] 3.6 过滤抽屉：
  - 容器：`AnimatedVisibility(visible = panels.filterOpen, enter = slideInHorizontally { -it } + fadeIn(), exit = …)`，`align(TopStart)`，padding：`start = 12`、`top = statusBar + 8`、`bottom = navBar + 12`，宽 `FilterDrawerWidth`。
  - 表面：iOS 用 `GlassSurface(componentRole = BottomPanel, shape = RoundedRectangle(28.dp))`；MD3 用 `Surface(shape = RoundedCornerShape(28.dp), color = surfaceContainerHigh, shadowElevation = 8.dp)`。
  - 内容为原宽屏 `SearchFilterPanel(...)` 调用（参数整段搬过来），另加 `onClose = { setFilterOpen(false) }`。
- [ ] 3.7 预览卡片：
  - 容器：`AnimatedVisibility(visible = panels.previewContent != null, enter = slideInHorizontally { it } + fadeIn())`，`align(TopEnd)`，padding：`end = 12`、`top = statusBar + 8`、`bottom = navBar + 12`，宽 `tabletPreviewCardWidth(windowWidth)`。
  - 内容：`SearchPreviewCard`（Task 4），并用 `Crossfade(targetState = content.id)` 切换作品。
- [ ] 3.8 `BackHandler(enabled = previewContent != null || filterOpen)`：先关预览，再关过滤。
- [ ] 3.9 `highlightedItemId = panels.previewContentId`。

编译 → 平板截图验证：
- 默认无抽屉；过滤按钮打开抽屉时网格不动。
- 点作品出预览卡，网格不动。
- 两者同开。
- 预览 → 详情 → 返回，状态保留。
- 返回键顺序正确。

提交 `feat(search): float the filter and preview over the tablet works list`。

---

### Task 4: 预览卡片视觉

**Files:**
- Create: `main/search/ui/compose/SearchPreviewCard.kt`
- Modify: `main/search/ui/compose/SearchPreviewPane.kt`（删除 `SearchPreviewPane` 与 `SearchFloatingPreviewCard`；保留 `rememberPreviewCoverRequest`、`previewDescription`、`previewAuthors`、`SearchPreviewChapterRow`、`resolvePreviewChapters`，并把它们的 `private` 改为 `internal`）

签名：

```kotlin
@Composable
internal fun SearchPreviewCard(
    content: Content,
    isLoading: Boolean,
    hasLoadError: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onRead: () -> Unit,
    onOpenDetails: () -> Unit,
    onAddToFavorites: () -> Unit,
    onOpenChapter: (ContentChapter) -> Unit,
    modifier: Modifier = Modifier,
)
```

**外壳**：iOS 用 `GlassSurface`（`RoundedRectangle(28.dp)`，`BottomPanel`）；MD3 用 `Surface(surfaceContainerHigh, shadowElevation = 8.dp)`。

**头部**（`Box`，高 200dp）：
- 背景：`AsyncImage(cover, Crop, Modifier.matchParentSize().blur(24.dp))`，上面叠 `Brush.verticalGradient(0f to Black 0.25, 1f to Black 0.65)`。
- 内容 `Row(padding 16)`：
  - 左：封面 `AsyncImage` 112×160，`RoundedCornerShape(12.dp)`。
  - 右：`Column`，依次为标题（titleLarge，白色，2 行）、作者（bodyMedium，白色 80%）、来源名（labelMedium，白色 70%）。
- 右上角：✕ `IconButton`，白色图标，底为 `Color.Black.copy(alpha = 0.3f)` 的圆。

**正文**（`Column(verticalScroll, padding 16, spacedBy 12)`）：
- 加载中：`LinearProgressIndicator`；失败时显示错误文字与「重试」`TextButton`。
- 标签：`FlowRow(maxLines = 1)`，前 6 个 chip，其余显示为「+N」。
- 统计行：评分 · 章节数 · 状态（复用旧代码片段）。
- 操作行：`Button(onRead, weight 1f)`（文字 `R.string.read`）、`OutlinedButton(onOpenDetails)`、`OutlinedIconButton(onAddToFavorites)`。
- 简介：`maxLines` 在展开 / 收起之间切换（4 ↔ `Int.MAX_VALUE`），点击切换。
- 章节：`resolvePreviewChapters()` 的前 8 个（最新章节优先），标题行右侧「全部 N 章」→ `onOpenDetails`。

**onRead**：`appRouter.openReader(content)`（不带 state）。先确认 `openReader(manga)` 在有历史时是否续读；若不是，改为：有 `history` 用其 state，否则用首章。

截图验证（iOS / MD3）→ 提交 `feat(search): redesign the tablet preview card`。

---

### Task 5: 过滤头部

**Files:** Modify `main/search/ui/compose/SearchFilterPanel.kt:150-221`

- 头部：「过滤」标题 + 已选数量角标（沿用） + `TextButton(清除)` + ⋮ `IconButton`。
  - ⋮ 菜单项：刷新可选项（`onRefreshFilters`）；保存当前过滤（`enabled = isSaveEnabled`）；标签黑名单（`onOpenGlobalTagBlacklist`）。
  - 可选 `onClose: (() -> Unit)? = null`，非空时显示 ✕（用于平板抽屉）。
- 删除独立的刷新 / 保存 / ⊗ Show 按钮与 `showTagBlacklist` 状态；`GlobalTagBlacklistStatus` 仅在 `blacklistedTagCount > 0` 时显示。

截图（平板抽屉 + 手机底部 sheet）→ 提交 `refactor(search): simplify the filter panel header`。

---

### Task 6: 验证
- [ ] 全量 `testDebugUnitTest`，统计 0 failures。
- [ ] 平板横屏 / 竖屏截图清单：Task 3 的验证项 + iOS / MD3 + 竖屏两浮层互斥。
- [ ] 手机回归：`wm size reset`；过滤底部 sheet、点作品直接进详情（手机无预览）。
- [ ] 同步 spec：记录「过滤抽屉不做点外部关闭」这一细化。
