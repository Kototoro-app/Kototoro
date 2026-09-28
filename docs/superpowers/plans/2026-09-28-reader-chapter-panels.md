# 阅读器章节面板重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 漫画 / 小说章节面板迁入阅读器窗口的统一面板宿主（玻璃 / 随页取色），统一头部与带文字 tab，清理重复信息并强化当前章节；同时修正小说在 MD3 风格下的 chrome 配色。

**Architecture:** 泛化 `ReaderOptionsPanelHost` 为 `ReaderPanelHost`；新增 `ReaderChapterPanelHeader`；漫画保留 `ChaptersPagesTabsContent` 内容，仅改外壳与少量共享行为；小说重写章节 tab 的列表与头部；新增 `novelChromeColorScheme` 包裹 MD3 下的小说 chrome。

**Tech Stack:** Kotlin, Jetpack Compose, JUnit5。

**Spec:** `docs/superpowers/specs/2026-09-28-reader-chapter-panels-design.md`

通用约定同 `2026-09-28-reader-options-panel-redesign.md`：
- 单测命令：`./gradlew :app:testDebugUnitTest --tests "<FQCN>"`。
- 每个 commit 末尾加：
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_014937ohDunZ8GHJ4hGpurbY
  ```

---

### Task 1: 纯函数与测试（副标题 / 组头 / 行状态 / chrome 配色）

**Files:**
- Create: `main/reader/ui/compose/design/ReaderChapterPanelHeader.kt`（先只放纯函数）
- Modify: `main/details/ui/ChaptersMapper.kt`（新增 `shouldShowVolumeHeaders` 并在 `withVolumeHeaders` 使用）
- Create: `main/reader/novel/compose/NovelChapterRowState.kt`
- Create: `main/reader/novel/compose/NovelChromeColors.kt`
- Tests:
  - `test/reader/ui/compose/design/ReaderChapterPanelHeaderTest.kt`
  - `test/details/ui/ChaptersMapperVolumeHeaderTest.kt`
  - `test/reader/novel/compose/NovelChapterRowStateTest.kt`
  - `test/reader/novel/compose/NovelChromeColorsTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
// ReaderChapterPanelHeaderTest
class ReaderChapterPanelHeaderTest {
    @Test
    fun `subtitle joins the non-blank parts with dots`() {
        assertEquals(
            "Chapter 01 · Ch. 1/3 Pg. 1/8 · 4%",
            readerChapterPanelSubtitle(listOf("Chapter 01", "Ch. 1/3 Pg. 1/8", "4%")),
        )
    }

    @Test
    fun `subtitle skips blank and repeated parts`() {
        assertEquals("Chapter 01 · 2/3", readerChapterPanelSubtitle(listOf("Chapter 01", "", null, "Chapter 01", "2/3")))
        assertEquals("", readerChapterPanelSubtitle(listOf(null, " ")))
    }
}

// ChaptersMapperVolumeHeaderTest
class ChaptersMapperVolumeHeaderTest {
    private fun chapter(volume: Int, scanlator: String? = null) = ContentChapter(
        id = volume.toLong() + 1, title = "c", number = 1f, volume = volume, url = "u$volume",
        scanlator = scanlator, uploadDate = 0L, branch = null, source = TestSource,
    )

    private data object TestSource : ContentSource {
        override val name = "test"
        override val locale = ""
        // Implement the remaining ContentSource members exactly as TestVideoSource in
        // VideoLaunchResolverTest does (copy its body).
    }

    @Test
    fun `no volumes and no groups means no headers`() {
        assertFalse(shouldShowVolumeHeaders(listOf(chapter(0), chapter(0))))
    }

    @Test
    fun `any volume or group shows headers`() {
        assertTrue(shouldShowVolumeHeaders(listOf(chapter(0), chapter(2))))
        assertTrue(shouldShowVolumeHeaders(listOf(chapter(0, scanlator = "Team"))))
    }
}

// NovelChapterRowStateTest
class NovelChapterRowStateTest {
    @Test
    fun `rows before the current chapter are read, after are unread`() {
        assertEquals(NovelChapterRowState.READ, novelChapterRowState(index = 0, currentIndex = 2))
        assertEquals(NovelChapterRowState.CURRENT, novelChapterRowState(index = 2, currentIndex = 2))
        assertEquals(NovelChapterRowState.UNREAD, novelChapterRowState(index = 3, currentIndex = 2))
    }
}

// NovelChromeColorsTest
class NovelChromeColorsTest {
    private val sepia = NovelReaderPalette(
        backgroundColor = 0xFFF4ECD8.toInt(), textColor = 0xFF4F4032.toInt(),
        secondaryTextColor = 0xFF7A6A59.toInt(), chromeBackgroundColor = 0xFFE7DDC5.toInt(),
        chromeTextColor = 0xFF544436.toInt(), highlightColor = 0x4DA67C2E,
        placeholderColor = 0xFFDDD2BC.toInt(), placeholderTextColor = 0xFF7A6A59.toInt(), isDark = false,
    )

    @Test
    fun `md3 novel chrome surfaces take the theme chrome colour`() {
        val scheme = novelChromeColorScheme(lightColorScheme(), sepia)
        listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerLow, scheme.surfaceContainerHigh)
            .forEach { assertEquals(Color(0xFFE7DDC5), it) }
        assertEquals(Color(0xFF544436), scheme.onSurface)
        assertEquals(Color(0xFF544436), scheme.primary)
    }
}
```

- [ ] **Step 2: 运行确认编译失败**（未解析引用）

- [ ] **Step 3: 实现**

```kotlin
// ReaderChapterPanelHeader.kt (pure part)
internal fun readerChapterPanelSubtitle(parts: List<String?>): String =
    parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct().joinToString(" · ")

// ChaptersMapper.kt
/** A lone "Unknown volume" header says nothing; only group when some chapter has a volume or group name. */
internal fun shouldShowVolumeHeaders(chapters: List<ContentChapter>): Boolean =
    chapters.any { it.volume > 0 || !it.scanlator.isNullOrBlank() }
// In withVolumeHeaders' non-EPUB branch, before the loop:
//     if (!shouldShowVolumeHeaders(map { it.chapter })) return toMutableList<ListModel>()

// NovelChapterRowState.kt
internal enum class NovelChapterRowState { READ, CURRENT, UNREAD }
internal fun novelChapterRowState(index: Int, currentIndex: Int): NovelChapterRowState = when {
    index == currentIndex -> NovelChapterRowState.CURRENT
    index < currentIndex -> NovelChapterRowState.READ
    else -> NovelChapterRowState.UNREAD
}

// NovelChromeColors.kt
internal fun novelChromeColorScheme(base: ColorScheme, palette: NovelReaderPalette): ColorScheme {
    val background = Color(palette.chromeBackgroundColor)
    val text = Color(palette.chromeTextColor)
    return base.copy(
        surface = background, surfaceBright = background, surfaceDim = background,
        surfaceContainerLowest = background, surfaceContainerLow = background, surfaceContainer = background,
        surfaceContainerHigh = background, surfaceContainerHighest = background, surfaceVariant = background,
        onSurface = text, onSurfaceVariant = Color(palette.secondaryTextColor),
        primary = text, onPrimary = background,
    )
}

/** MD3 chrome follows the reading theme; iOS glass chrome keeps its own tint. */
@Composable
internal fun NovelChromeTheme(palette: NovelReaderPalette, enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) return content()
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, palette) { novelChromeColorScheme(base, palette) }
    MaterialTheme(colorScheme = scheme, content = content)
}
```

- [ ] **Step 4: 运行 4 个测试类确认通过**
- [ ] **Step 5: 提交** `feat(reader): add chapter panel helpers`

---

### Task 2: `ReaderPanelHost` 与 `ReaderChapterPanelHeader`

**Files:**
- Modify: `main/reader/ui/compose/panel/ReaderOptionsPanelHost.kt`
- Modify: `main/reader/ui/compose/design/ReaderChapterPanelHeader.kt`

- [ ] **Step 1: 泛化宿主**

在 `ReaderOptionsPanelHost.kt` 中把现有函数体提取为：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderPanelHost(
    colors: ReaderPanelColors,
    surfaceMode: ReaderPanelSurfaceMode,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    usePeekAnchor: Boolean = false,
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable (dragModifier: Modifier) -> Unit,
)
```

函数体与原 `ReaderOptionsPanelHost` 一致，差异：
- `usePeekAnchor` 透传给 `StableAnchoredSheetLayout`。
- `peekHeight` 仅在 `usePeekAnchor` 时计算，否则为 `null`。
- 原 `quickLayer()` 调用处改为 `header()`，`details(scope.dragModifier)` 改为 `content(scope.dragModifier)`。

`ReaderOptionsPanelHost` 保留签名，函数体改为：

```kotlin
ReaderPanelHost(
    colors = colors,
    surfaceMode = surfaceMode,
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    usePeekAnchor = true,
    header = quickLayer,
    content = details,
)
```

- [ ] **Step 2: 头部组件**（追加到 `ReaderChapterPanelHeader.kt`）

```kotlin
@Composable
fun ReaderChapterPanelHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = currentReaderPanelColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}
```

- [ ] **Step 3: 编译并提交** `refactor(reader): generalise the reader panel host and add a chapter panel header`

---

### Task 3: 漫画章节面板

**Files:**
- Modify: `main/reader/ui/compose/ComposeReaderActivityScaffold.kt`（`state.chaptersVisible` 分支、`ReaderChapterPanelToolbar`、`ReaderChapterPanelTab`）
- Modify: `main/details/ui/pager/chapters/compose/ChapterItemCard.kt`（当前章高亮）

- [ ] **Step 1: 替换 `state.chaptersVisible` 分支**

```kotlin
if (state.chaptersVisible) {
    val chapterPanelColors = mangaReaderPanelColors(state.options.background, isSystemInDarkTheme(), MaterialTheme.colorScheme)
    ReaderPanelHost(
        colors = if (state.eInkModeEnabled) chapterPanelColors.forEInk() else chapterPanelColors,
        surfaceMode = rememberReaderPanelSurfaceMode(state.eInkModeEnabled),
        onDismissRequest = callbacks.onBackPressed,
        header = {
            val selectionState = chapterSelectionState
            if (chapterPanelTabId == DETAILS_TAB_CHAPTERS && selectionState != null) {
                ChapterSelectionBar(state = selectionState, modifier = Modifier.height(52.dp))
            } else {
                ReaderChapterPanelHeader(
                    title = state.title,
                    subtitle = readerChapterPanelSubtitle(
                        listOf(state.subtitle, state.infoBar.text, state.infoBar.progressText),
                    ),
                    actions = {
                        if (chapterPanelTabId == DETAILS_TAB_CHAPTERS) {
                            ReaderChapterPanelActions(state.chapterPanel, callbacks.chapterPanel)
                        }
                    },
                )
                ReaderPanelTabBar(
                    labels = ReaderChapterPanelTabs.map { stringResource(it.second) },
                    selectedIndex = ReaderChapterPanelTabs.indexOfFirst { it.first == chapterPanelTabId }.coerceAtLeast(0),
                    onSelected = { callbacks.chapterPanel.onTabSelected(ReaderChapterPanelTabs[it].first) },
                )
            }
        },
    ) { dragModifier ->
        chaptersPanelContent(
            chapterPanelTabId,
            state.chapterPanel,
            { chapterSelectionState = it },
            dragModifier,
        )
    }
}
```

`private val ReaderChapterPanelTabs` 为：

```kotlin
listOf(
    DETAILS_TAB_CHAPTERS to R.string.chapters,
    DETAILS_TAB_PAGES to R.string.pages,
    DETAILS_TAB_BOOKMARKS to R.string.bookmarks,
)
```

`ReaderChapterPanelActions` 是原 `ReaderChapterPanelToolbar` 中搜索按钮加 ⋮ 菜单那段（`Row`，逻辑不变）。删除 `ReaderChapterPanelToolbar` 与 `ReaderChapterPanelTab`。

- [ ] **Step 2: 当前章高亮**：`ChapterListCard` 的背景 `then(...)` 改为

```kotlin
when {
    isSelected -> Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
    item.isCurrent -> Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
    else -> Modifier
}
```

- [ ] **Step 3: 编译、安装、截图**：章节 / 页面 / 书签三个 tab，iOS 与 MD3 各一遍。
- [ ] **Step 4: 书签空白**：按 systematic-debugging 定位（先确认 `BookmarksScreen` 是否被组合、`items` 的值、文字是否被绘制在可视区外），修复后把 "No bookmarks" 改为字符串资源并加图标。
- [ ] **Step 5: 提交** `feat(reader): move the manga chapter panel onto the reader panel host`

---

### Task 4: 小说章节面板

**Files:**
- Modify: `main/reader/novel/compose/ComposeNovelChaptersSheet.kt`
- Modify: `main/reader/novel/compose/NovelReaderChrome.kt`
- Modify: `main/reader/novel/NovelReaderActivity.kt`

- [ ] **Step 1: 顶层改用 `ReaderPanelHost`**
  - 新参数：`bookTitle: String = ""`、`eInkMode: Boolean = false`。
  - 配色：`novelReaderPanelColors(novelReaderPalette(themePreset, isSystemInDarkTheme()))`。
  - header：`ReaderChapterPanelHeader(title = bookTitle, subtitle = readerChapterPanelSubtitle(listOf(currentTitle, "${currentIndex + 1}/${chapters.size}")))`。其 actions 为 `IconButton(ic_current_chapter)`：先 `scrollToPage(CHAPTERS)`，再 `locateRequest++`。
  - header 下接 `ReaderPanelTabBar`，标签为 章节 / 笔记 / `novel_reader_chapter_search_tab`，不带计数。
  - content 为原 `HorizontalPager`。
  - 删除 `MaterialTheme.colorScheme.copy` 与 `ReaderAnchoredBottomSheet` 调用，删除 `NovelChaptersTabRow`。

- [ ] **Step 2: `ComposeNovelChaptersContent`**
  - 新增参数 `locateRequest: Int`，它与内部 query 一起作为 `LaunchedEffect` 的 key。
  - 删除「计数 / 排序」行与底部 `FilledTonalButton`。
  - 顶部改为 `Row { NovelReaderSearchField(weight 1f); IconButton(ic_sort_desc, 反序时 tint = accent) }`。
  - 列表项改用 `NovelChapterRow`：

```kotlin
@Composable
private fun NovelChapterRow(
    number: Int,
    title: String,
    state: NovelChapterRowState,
    onClick: () -> Unit,
) {
    val colors = currentReaderPanelColors()
    val current = state == NovelChapterRowState.CURRENT
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (current) colors.selectedContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .alpha(if (state == NovelChapterRowState.READ) 0.6f else 1f),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(24.dp)
                .background(if (current) colors.accent else Color.Transparent, RoundedCornerShape(2.dp)),
        )
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.contentSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp).padding(end = 12.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 16.dp),
        )
    }
}
```

  - 删除 `ComposeNovelChaptersPanel`（无调用方）。

- [ ] **Step 3: 调用处上移**
  - `NovelReaderChrome` 删除 `ComposeNovelChaptersSheet` 调用；`BackHandler.enabled` 追加 `&& !state.chaptersSheetVisible`，并删除 when 中的 chapters 分支。
  - `NovelReaderActivity` 根 Box（紧接 options 面板调用之后）加入同参数调用，另传 `bookTitle = state.workTitle`、`eInkMode = isEInkModeEnabled`。

- [ ] **Step 4: 编译、安装、截图**：Paper / Slate 下的章节、笔记、搜索三个 tab；定位按钮；返回键。
- [ ] **Step 5: 提交** `feat(reader): redesign the novel chapter panel`

---

### Task 5: 小说 MD3 chrome 配色

**Files:** Modify `main/reader/novel/NovelReaderActivity.kt`

- [ ] **Step 1:** 用 `NovelChromeTheme(palette, enabled = LocalInterfaceStyle.current != InterfaceStyle.IOS)` 包裹 `NovelReaderTopChrome` 与 `NovelReaderBottomChrome` 两个 Box 的内容。palette 为：

```kotlin
novelReaderPalette(state.settings?.themePreset ?: NovelReaderThemePreset.PAPER, isSystemInDarkTheme())
```

- [ ] **Step 2:** 安装后在 MD3 下截图：顶部胶囊、进度条为主题色；再切回 iOS 截图确认无变化。
- [ ] **Step 3: 提交** `fix(reader): tint the novel chrome with the reading theme under MD3`

---

### Task 6: 验证

- [ ] 全量 `./gradlew :app:testDebugUnitTest --no-daemon`，统计 0 failures。
- [ ] 截图清单：spec §3。
- [ ] 回归：详情页章节列表（组头、当前章高亮）。
