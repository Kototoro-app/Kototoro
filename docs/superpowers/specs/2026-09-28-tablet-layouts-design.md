# 平板布局优化：作品列表 / 主页 / 详情

- 日期：2026-09-28
- 实施顺序：① 源作品列表（本 spec 重点，独立计划与实现）→ ② 主页 → ③ 详情。②③ 在各自计划前再细化。

## 1. 现状问题（模拟器 `wm size` 2560×1600 / 1600×2560 @ 320dpi 截图确认）

**源作品列表**（`search/ui/compose/SearchContentListScreen.kt`、`SearchFilterPanel.kt`、`SearchPreviewPane.kt`）：
- L1 宽屏时过滤面板固定占 50% 宽，列表只剩 6 列；分隔仅一条细线。
- L2 过滤与预览共用一个侧栏：打开预览会替换掉过滤。
- L3 预览面板的问题：
  - 顶部按钮压在状态栏下。
  - 整幅封面 Crop 后只剩局部，没有清晰的封面缩略图。
  - 灰色描述、「详情」按钮在彩色背景上对比度不足。
  - 底部不贴边。
- L4 过滤头部的「刷新 / 保存 / ⊗ Show / Clear」含义不明；「Show」实为切换标签黑名单状态行。
- L5 过滤面板默认展开（`isTabletListFilterPanelDefaultOpen`），用户希望默认收起。
- L6 **从预览进入详情再返回：预览被关闭、过滤面板被打开**，并回到列表初始状态。可能原因（实施时用日志确认）：
  - `showFilterPanel`、`sidePaneMode` 以 `rememberSaveable(isWideAdaptiveLayout)` 保存，而 `previewContent` 只用 `remember`，返回后丢失。
  - 以设置为 key 的 `LaunchedEffect`（`SearchContentListScreen.kt:489-521`）在重新进入组合时再次执行，把状态「按默认值」重置。
  - 预览恢复逻辑在列表数据尚未加载时判定「找不到作品」而 `clearPreview()`。
- L7 打开 / 关闭任一面板都会改变列表列数和排布（reflow），体验跳动。

**主页**：
- H1 hero 卡片为固定宽度，横屏只占约 60% 宽，右侧留空。
- H2 搜索栏横跨整屏。
- H3 竖屏快捷入口 9 个一行后，剩下 1 个「设置」独占整行。
- H4 下半屏空白。

**详情**：
- D1 宽屏布局没有「开始 / 继续阅读」主操作。
- D2 左栏标签以下空白。
- D3 右栏 tab 仅图标。
- D4 顶部操作胶囊对齐左栏右缘，而不是屏幕右缘。

## 2. 共享基础：窗口尺寸分级

新增纯函数（`core/ui/adaptive/TabletLayoutClass.kt`）：

```kotlin
enum class TabletLayoutClass { COMPACT, MEDIUM, EXPANDED }
fun tabletLayoutClass(widthDp: Int, tabletLayoutEnabled: Boolean): TabletLayoutClass
```

- `tabletLayoutEnabled` 为 false（`FoldableUtils.shouldUseTabletLayout` 的结果，尊重「平板界面模式」设置）→ COMPACT。
- 否则 `< 600` → COMPACT；`600..999` → MEDIUM；`≥ 1000` → EXPANDED。

共享尺寸 token（`TabletLayoutTokens`）：
- `FilterDrawerWidth = 300.dp`
- `PreviewCardWidth = 380.dp`（MEDIUM 为 `min(360.dp, 窗口宽 * 0.5)`）
- `OverlayMargin = 12.dp`
- `HomeContentMaxWidth = 1200.dp`
- `DetailsSingleColumnMaxWidth = 720.dp`

## 3. 源作品列表（本期实现）

### 3.1 布局原则：面板一律浮层，列表永不 reflow
- 网格的列数与位置只取决于窗口宽度，任何面板的开关都不改变它。
- 过滤：从左侧滑入的**浮层抽屉**（300dp，全高，圆角右侧，阴影），**默认关闭**。
  - 由顶栏过滤按钮开关；✕、返回键可关闭。不做「点抽屉外关闭」：否则点作品会先关掉抽屉，与「两者可同开」冲突。
  - 过滤即时生效，抽屉背后的结果实时刷新。
  - 过滤按钮在有生效条件时显示数字角标。
- 预览：从右侧滑入的**浮层卡片**（380dp，四周 12dp 边距，28dp 圆角，阴影）。
  - 点作品打开，点其它作品只换内容（Crossfade）。
  - ✕、返回键或右滑关闭。
  - 无 scrim，网格保持可交互。
- 两者可同时打开。若 `窗口宽 - 两者宽度 - 2×边距 < 240dp`，打开一个时关闭另一个（MEDIUM 下通常成立）。
- 选中作品卡片显示 2dp accent 描边（沿用 `highlightedItemId`）。
- 两个浮层都从顶栏下方开始，不遮挡返回 / 过滤 / 搜索按钮；iOS 风格的玻璃表面内叠 0.9 透明度的 surface 色，避免网格封面透过控件。
- 返回键顺序：预览 → 过滤 → 离开页面。
- 玻璃：iOS 风格用 `GlassSurface`（`componentRole = BottomPanel`，与现有浮层一致），MD3 用 `surfaceContainerHigh` 不透明卡片。
- `TabletListPreviewMode`：
  - `SIDE_PANE` 与 `FLOATING` 统一为本浮层卡片（`SIDE_PANE` 视为 `FLOATING` 读取，不改存储值）。
  - `OFF` 时点作品直接进详情。
- 删除 `isWideSplitLayout` 分栏布局与中间分隔线。

### 3.2 状态归属（修 L6）
- 新增 `SearchPanelsState`（`@Immutable`），字段 `filterOpen: Boolean`、`previewContentId: Long?`，由 `RemoteListViewModel` 持有 `MutableStateFlow`，经 `SavedStateHandle` 持久化（进程重建也保留）。
- 预览的 `Content` 由 ViewModel 按 `previewContentId` 从当前列表项解析。解析不到时：
  - 列表仍在加载 → 保持预览（显示骨架）。
  - 加载完成后仍找不到 → 用已缓存的上次 `Content`。
  - 都没有才关闭。
- 删除 `SearchContentListScreen.kt:489-521` 中进入组合即按设置重置面板的 `LaunchedEffect`。`filterOpen` 的初值只在 ViewModel 首次创建时取 `isTabletListFilterPanelDefaultOpen`，其默认值改为 `false`（L5）。
- 列表滚动位置：宽屏网格状态已由 `wideGridState` 等持有，确认其在返回后保留（`rememberSaveable` 的 `LazyGridState.Saver`）。
- 验收：预览 → 详情 → 返回后，预览仍是同一作品、过滤开合不变、滚动位置不变。

### 3.3 过滤抽屉内容（L4）
- 头部：「过滤」标题 + 已选数量角标 + `清除` 文本按钮 + ⋮ 菜单（刷新可选项 / 保存当前过滤 / 标签黑名单）。
- 删除独立的刷新、保存、⊗ Show 按钮；标签黑名单状态行仅在黑名单非空时显示，不再有开关。
- 其余分组内容与交互不变（`SearchFilterPanel` 各段复用）。

### 3.4 预览卡片视觉（L3）
- **头部**（约 200dp）：
  - 背景为模糊封面（`blur 24dp`）加由上到下 0.25→0.65 的黑色渐变。
  - 左侧清晰封面缩略图 112×160（12dp 圆角）。
  - 右侧：标题（titleLarge，2 行）、作者、来源名（白色系，保证对比度）。
  - 右上角 ✕。
- **正文**（页面色、不透明，可滚动）：
  - 标签 chips（1 行，溢出折叠为 +N）。
  - 统计行：评分 · 章节数 · 状态。
  - 操作行：[开始 / 继续阅读]（主按钮）[详情] [♡]。
  - 简介：默认 4 行，点击展开。
  - 前 8 个章节；「查看全部」进入详情。
- 加载中正文显示骨架；失败时保留头部，正文显示错误与重试。

### 3.5 竖屏平板（MEDIUM）
与 3.1 相同的浮层模型，预览宽 `min(360dp, 50%)`，并满足上面的互斥规则。

## 4. 主页（第二期，要点）
- MEDIUM / EXPANDED：内容最大宽 1200dp 居中；搜索栏最大宽 720dp。
- hero 卡片宽度随可用宽度分配：EXPANDED 每屏 2–3 张铺满，MEDIUM 每屏 1.5 张（露出下一张）。
- 快捷入口改为自适应网格（最小格宽 110dp），末行不再出现单个整行块。
- EXPANDED 试行：「历史」与「快捷入口」左右并排（6:4），填补下半屏；效果不佳则回退为上下排列。

## 5. 详情（第三期，要点）
- EXPANDED 双栏（左 40% / 右 60%）：
  - 左栏底部固定主操作条：[继续阅读 ▸ 第 X 章][下载][分享]。
  - 右栏 tab 带文字（复用 `ReaderPanelTabBar` 风格）。
  - 顶部操作胶囊对齐屏幕右缘。
  - 封面放大到 160×230。
- MEDIUM：单栏，内容最大宽 720dp 居中，沿用手机底部操作栏。

## 6. 测试
- 单测：
  - `tabletLayoutClass` 的边界值。
  - 双浮层互斥规则 `shouldCloseOtherPanel(windowWidth, …)`。
  - `SearchPanelsState` 的 SavedStateHandle 往返。
  - 预览解析的三种分支（加载中保持 / 用缓存 / 关闭）。
- 模拟器：
  - `wm size 2560x1600` 与 `1600x2560`，`wm density 320`。
  - 列表：过滤开 / 关、预览开 / 关、两者同开、预览 → 详情 → 返回、返回键顺序、iOS / MD3。
  - 手机尺寸回归（`wm size reset`）。
