# 阅读器章节面板重设计 + 小说 MD3 顶栏配色

- 日期：2026-09-28
- 分支：`feat/reader-options-panel-redesign`（承接「更多」面板重设计）
- 前置：`2026-09-28-reader-options-panel-redesign-design.md`（`ReaderPanelColors`、`ReaderOptionsPanelHost`、`ReaderPanelTabBar` 等已实现）

## 1. 问题（模拟器截图确认）

漫画章节面板（复用详情页 `ChaptersPagesTabsContent`）：
- C1 顶部 tab 只有图标（列表 / 网格 / 书签）无文字；右侧搜索、⋮ 无标签。
- C2 无卷信息的漫画也显示一个「Unknown volume」组头（`withVolumeHeaders` 在 volume ≤ 0 时仍插入组头）。
- C3 当前章节只有 16dp 小三角 + 主色文字，扫视难以定位。
- C4 书签 tab 为空时整片空白；`BookmarksScreen` 的空态文字是硬编码英文 "No bookmarks"。
- C5 面板为白色 Dialog，与阅读背景、玻璃 chrome 无关。

小说章节面板（`ComposeNovelChaptersSheet`）：
- N1 信息重复：tab 标签带计数（「章节 3」）、其下又有「3 Chapters / Ascending」两行、右侧再有「Ascending」按钮。
- N2 每行都是带底色的圆角卡片，列表很重；右侧序号与标题「Chapter 1」重复。
- N3 已读 / 未读无区分。
- N4 底部整宽「Locate current chapter」按钮占一行。
- N5 面板在 Dialog 中，无法使用阅读器玻璃；配色靠局部 `colorScheme.copy`。

小说 chrome：
- M1 MD3 风格下顶部胶囊、底部进度条为冷灰（`StableGlassFallback` 取 `colorScheme.surfaceContainer`），与棕褐等阅读主题冲突。

## 2. 方案

### 2.1 通用宿主 `ReaderPanelHost`
把 `ReaderOptionsPanelHost` 泛化为 `ReaderPanelHost(colors, surfaceMode, onDismissRequest, usePeekAnchor, header, content)`：
- `header` 位于拖动区（把手之下），`content(dragModifier)` 占剩余空间。
- 仅 `usePeekAnchor = true` 时测量 header 高度作为 Peek。
- `ReaderOptionsPanelHost` 改为调用 `ReaderPanelHost(usePeekAnchor = true, header = quickLayer, …)`，行为不变。
- 章节面板 `usePeekAnchor = false`，打开于 3/4。

### 2.2 共用头部 `ReaderChapterPanelHeader`
- 第一行作品名（titleMedium，单行），第二行副标题（labelMedium，secondary）。
- 行尾 `actions` 槽位。
- 副标题由纯函数 `readerChapterPanelSubtitle(parts: List<String?>)` 拼接：过滤空白、去重，用 ` · ` 连接。
  - 漫画：`[subtitle(当前章节名), infoBar.text("Ch. 1/3 Pg. 1/8"), infoBar.progressText("4%")]`。
  - 小说：`[当前章节标题, "${index + 1}/${size}"]`。
- 头部下方为 `ReaderPanelTabBar`（带文字）。

### 2.3 漫画
- scaffold 中 `state.chaptersVisible` 分支改用 `ReaderPanelHost`，配色 `mangaReaderPanelColors(state.options.background, …)`，E-ink 走 `forEInk()`。
- header：`ReaderChapterPanelHeader(title = state.title, …)`。
  - actions：仅在章节 tab 显示搜索按钮与 ⋮ 菜单（沿用 `ReaderChapterPanelMoreMenu`）。
  - 章节多选时整行换成 `ChapterSelectionBar`（沿用）。
  - 下方 `ReaderPanelTabBar(章节 / 页面 / 书签)`。
- C2：新增 `shouldShowVolumeHeaders(chapters)`，没有任何章节带 volume > 0 或自定义组名（scanlator）时，`withVolumeHeaders` 不插组头。详情页同样受益。
- C3：`ChapterListCard` 当前章节加底色高亮（`primaryContainer` 45%，12dp 圆角），保留小三角。
- C4：先定位空白原因（systematic-debugging），再把空态改为图标 + 字符串资源 `bookmarks_empty`（若已有同义资源则复用）。

### 2.4 小说
- `ComposeNovelChaptersSheet` 改用 `ReaderPanelHost`，配色 `novelReaderPanelColors`。
  - 新增参数 `bookTitle: String`、`eInkMode: Boolean`。
  - 调用处从 `NovelReaderChrome` 上移到 `NovelReaderActivity` 根 Box（同「更多」面板），chrome 的 BackHandler 排除 `chaptersSheetVisible`。
- header actions：「定位当前章节」图标按钮（`ic_current_chapter`），切到章节 tab 并滚动到当前章。删除底部整宽按钮（N4）。
- tab 标签去掉计数（N1）；章节 tab 顶部只保留「搜索框 + 排序图标按钮」一行，删除「3 Chapters / Ascending」两行（N1）。
- 行（N2 / N3）：无底色的行，左侧小号序号（secondary），标题单行省略。
  - 当前章：`selectedContainer` 底色 + 3dp accent 左竖条 + SemiBold。
  - 已读（index < 当前）：content 60% 透明度。
  - 状态由纯函数 `novelChapterRowState(index, currentIndex)` 给出。
- 删除未使用的 `ComposeNovelChaptersPanel`。

### 2.5 小说 MD3 chrome 配色（M1）
新增纯函数 `novelChromeColorScheme(base, palette)`：
- 所有 `surface*` 与 `surfaceContainer*` 取 `chromeBackgroundColor`。
- `onSurface`、`primary` 取 `chromeTextColor`；`onSurfaceVariant` 取 `secondaryTextColor`；`onPrimary` 取 `chromeBackgroundColor`。

非 iOS 风格时，`NovelReaderActivity` 用它包裹 top / bottom chrome。iOS 风格不变（玻璃 tint 走 chrome tint）。

## 3. 测试
- 单测：`readerChapterPanelSubtitle`、`shouldShowVolumeHeaders`、`novelChapterRowState`、`novelChromeColorScheme`。
- 回归：全量 `testDebugUnitTest`。
- 模拟器截图：漫画 / 小说章节面板 × iOS / MD3；书签空态；多选模式；定位按钮；返回键；小说 MD3 顶栏与进度条。
