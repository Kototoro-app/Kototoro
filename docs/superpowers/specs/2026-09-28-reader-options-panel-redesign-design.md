# 漫画 / 小说阅读器「更多」面板重设计

- 日期：2026-09-28
- 分支：`feat/reader-options-panel-redesign`
- 范围：漫画阅读器 `ComposeReaderOptionsSheet`、小说阅读器 `ComposeNovelReaderOptionsSheet` 及其共用设计组件

## 1. 背景与问题

在模拟器上对两个面板截图（小说：棕褐主题；漫画：默认主题）后确认的问题：

| # | 问题 | 位置 |
|---|------|------|
| P1 | 小说面板的分组卡片、未选中选项呈冷灰白色，与棕褐 / 苔绿等阅读主题割裂。根因：`MaterialTheme.colorScheme.copy(...)` 只覆盖了部分颜色，`ReaderOptionGroup` 使用的 `surfaceContainerLow` 未被覆盖 | `ComposeNovelReaderOptionsSheet.kt` |
| P2 | 漫画面板的分组卡片与 sheet 底色几乎相同，组的边界只剩分割线 | `design/ReaderOptionControls.kt` |
| P3 | 每页顶部大标题与选中 tab 同名，重复且占一行 | 两个 sheet |
| P4 | tab 栏左对齐，右侧留白 | 两个 sheet |
| P5 | 选项卡片 84dp 高、粗描边；5 个选项时文字折行（「Standar/d」「Continu/ous ho…」） | `ReaderSegmentedChoice(verticalOptions = true)` |
| P6 | 漫画「工具」页 11 行、每行带 → 箭头，常用操作需滚动 | `ReaderToolsOptionsPage` |
| P7 | 漫画背景色块横向滚动被右侧裁切；「深色」色块显示为浅灰 | `ReaderBackgroundPalette` |
| P8 | 小说「重置」无确认即执行，副标题却写成问句 | `NovelReaderToolsPage` |
| P9 | 小说排版数值需弹对话框拖滑块，调整时看不到正文 | `SliderEditorDialog` |
| P10 | 面板样式与阅读器已有的玻璃胶囊（顶栏、进度栏）不统一 | 两个 sheet |

## 2. 目标与非目标

目标：
- 面板视觉与阅读器玻璃 chrome 统一，并跟随阅读背景 / 主题取色。
- 常用操作打开即可触达（快捷层），详细设置收敛为 3 个 tab。
- 修复 P1–P10。

非目标：
- 不改变任何设置项的语义、存储与默认值（`ComposeReaderOptionsCallbacks`、`NovelReaderSettings` 保持不变）。
- 不改动章节面板、视频播放器面板、`ComposeReaderToolsSheet`。
- 不改动其他使用 `StableAnchoredBottomSheet` 的页面行为。
- 不新增或手改非英文字符串翻译（Weblate 管理）；新增字符串只加 `values/strings.xml`。

## 3. 架构

### 3.1 面板容器：从 Dialog 移入阅读器窗口

现状：`ReaderAnchoredBottomSheet` → `StableAnchoredBottomSheet` → `Dialog`（独立窗口）。Backdrop 玻璃只能采样同一窗口内 `layerBackdrop` 的内容，因此 Dialog 中无法实现透出阅读页的玻璃。

方案：
1. 从 `core/ui/compose/StableAnchoredBottomSheet.kt` 抽出不依赖 Dialog 的 `StableAnchoredSheetLayout`（包含 scrim、anchored drag、嵌套滚动、隐藏后回调）。`StableAnchoredBottomSheet` 改为 `Dialog { StableAnchoredSheetLayout(...) }` 的薄封装，行为不变。
2. `StableAnchoredSheetLayout` 支持可选的 **Peek** 锚点：高度由调用方传入（快捷层实测高度 + 拖动把手 + 导航栏 inset）。锚点序列为 `Full / ThreeQuarter / Peek / Hidden`，未提供 Peek 时维持现有 `Full / ThreeQuarter / Half / Hidden`。提供 Peek 时初始锚点为 Peek。
3. 新增 `reader/ui/compose/panel/ReaderOptionsPanelHost.kt`：在两个阅读器根部（与顶栏 / 底栏同层、位于 `layerBackdrop` 内容之外）渲染 `StableAnchoredSheetLayout`；`BackHandler` 处理返回；打开时把焦点请求到 tab 栏首项以保持 TV 可用。
4. 漫画：`ComposeReaderActivityScaffold` 中 `state.options.visible` 分支改用 `ReaderOptionsPanelHost`。小说：显隐仍由 ViewModel 的 `state.settingsSheetVisible` 驱动，不新增状态；`NovelReaderBottomChrome` 中的 `ComposeNovelReaderOptionsSheet` 调用改为使用 `ReaderOptionsPanelHost`。它与内容层（`NovelReaderActivity.kt:762` 的 `layerBackdrop`）同级；若 bottom chrome 的约束不是全屏，则把该调用上移到 `NovelReaderActivity` 根 Box 中、与 `NovelReaderBottomChrome` 同级。

### 3.2 面板表面：三种外观

`readerBackdrop` 仅在 iOS 界面风格且非 E-ink 时存在（`ComposeReaderActivityScaffold.kt:510`、`NovelReaderActivity.kt:657`），因此：

| 条件 | 表面 |
|------|------|
| iOS 风格且非 E-ink | `GlassSurface(componentRole = Sheet)`，backdrop 为 `readerBackdrop`，容器不透明度约 0.86，scrim 0.32 |
| MD3 风格 | 不透明表面，颜色取 `ReaderPanelColors.container` |
| E-ink | 不透明高对比表面，无 scrim 渐变、无阴影、无透明度 |

实现时按 `backdrop` skill 与 `kototoro-glass-panel-artifacts` skill 检查叠层伪影（额外浅色矩形、圆角外露矩形等）。

### 3.3 配色：`ReaderPanelColors`

新增 `reader/ui/compose/panel/ReaderPanelColors.kt`：

```kotlin
@Immutable
data class ReaderPanelColors(
    val container: Color,      // 面板表面（玻璃时作 tint）
    val card: Color,           // 分组卡片
    val content: Color,        // 主文字 / 图标
    val contentSecondary: Color,
    val accent: Color,         // 选中态、数值、开关
    val onAccent: Color,
    val selectedContainer: Color,
    val divider: Color,
    val isDark: Boolean,
)
val LocalReaderPanelColors = staticCompositionLocalOf<ReaderPanelColors> { error("...") }
```

- 小说：`readerPanelColors(palette: NovelReaderPalette)`，由 `novelReaderPalette(themePreset, isDark)` 派生。
- 漫画：`readerPanelColors(background: ReaderBackground, isSystemDark: Boolean, scheme: ColorScheme)`。先把 `ReaderBackground` 解析为明 / 暗（`DEFAULT` / `AUTO` 跟随系统），底色取对应的中性色，accent 取 `scheme.primary`。
- 两个都是纯函数，可单元测试。
- 面板内的 `MaterialTheme` 仍需提供给 Material 组件（Switch、Slider）。从 `ReaderPanelColors` **完整**生成一份 `ColorScheme`（包含所有 `surfaceContainer*`），不再做局部 copy，以消除 P1。

## 4. 共用组件（`reader/ui/compose/design/`）

所有组件只读 `LocalReaderPanelColors`，不直接读 `MaterialTheme.colorScheme`。

| 组件 | 规格 |
|------|------|
| `ReaderPanelTabBar` | 居中胶囊分段控件，滑动指示器（`animateDpAsState`）；右侧齿轮按钮打开完整设置（漫画 `onOpenSettings`；小说无对应入口时隐藏）；整行作为拖动区域 |
| `ReaderOptionGroup`（改） | `card` 色、20dp 圆角；可选 `title` 作为组内小号 label 标题；分割线仅在行间 |
| `ReaderChoiceChips` | 2–4 项：56dp 高、「图标 + 单行文字」，文字过长时 `autoSize` 缩小至 11sp 仍不够则省略号；选中项 `selectedContainer` + 1dp accent 描边，未选中无描边 |
| `ReaderIconChoiceBar` | ≥5 项：图标胶囊（每项 44dp），下方居中显示选中项名称；每项有 contentDescription |
| `ReaderQuickTile` | 4 列网格单元：44dp 圆形图标底 + 单行短标签；`toggled` 参数用于开关类（高亮 = 开） |
| `ReaderStepperRow` | 标签 + `−` 数值 `+`，长按连续调整；数值点击展开内联滑块 |
| `ReaderSliderRow` | 标签 + 数值 + 内联 Slider（替代 `SliderEditorDialog`） |
| `ReaderOptionSwitchRow` / `ReaderOptionValueRow`（改） | 仅改为读取面板配色 |

`ReaderSegmentedChoice` 在迁移完成后删除（仅两个 sheet 使用；迁移前确认无其他调用方）。

## 5. 内容布局

面板统一结构：拖动把手 → **快捷层**（Peek 时唯一可见部分） → `ReaderPanelTabBar` → 详细 tab 的 `HorizontalPager`。各页不再有页标题（P3）。

### 5.1 漫画

快捷层：
1. `ReaderIconChoiceBar`：翻页模式（标准 / 从右到左 / 垂直 / 条漫 / 连续横向）。
2. `ReaderQuickTile` 网格（2 行 × 4）：章节与页面、添加书签、保存页面、裁剪与笔记、自动滚动、旋转屏幕、下载、在浏览器打开。翻译可用时追加「翻译」开关 tile（第 9 个，网格自动换行）。

详细 tab：
- **版式**：连续横向反向（仅连续横向模式）、翻页动画（`ReaderChoiceChips`）、双页组（横屏双页 / 折叠屏双页 / 封面单页 / 灵敏度）、缩放模式、裁边 / 拆分双页、渲染器组（条漫 / 分页场景渲染器）、性能组（优化 / 减少预加载）、全屏 / 页码 / 章节标题置底。
- **画面**：背景色块（`FlowRow` 换行，不再横向滚动，P7；「深色」色块使用真实暗色）、原图 / 结果对比预览、色彩校正、保存（全局 / 本作）、超分辨率、图源服务器（有时）。
- **翻译**：现有「设置 / 日志」内容，改用新组件。

移除：「上一章 / 下一章」行（进度栏已有同功能按钮）。

### 5.2 小说

快捷层：
1. `ReaderSliderRow` 亮度 + 行尾「跟随系统」图标开关。
2. `ReaderStepperRow` 字号（A− 数值 A+）。
3. 主题色块（4 个圆形色块，选中带 accent 环）。
4. `ReaderQuickTile`：朗读、添加书签、书签与笔记、翻译开关。

详细 tab：
- **排版**：字体 chip 行、字号 / 行距 / 段距 / 左右边距 / 上下边距（内联 stepper 或 slider，P9）、首行缩进。
- **阅读**：分页 / 滚动、翻页动画（仅分页）、双页 / 沉浸 / 阅读状态 / 章节标题置底 / 透明状态栏。
- **翻译与工具**：翻译开关、显示模式、开始翻译、清除缓存及说明；替换规则入口与本书开关；重置（点击后在行内展开确认 / 取消，P8）。

删除未被调用的 `ComposeNovelReaderOptionsPanel` 与 `SliderEditorDialog`。

## 6. 错误处理与边界

- 快捷层高度超过屏幕 60%（横屏、字体放大）时，Peek 锚点退化为 `ThreeQuarter`。
- 平板 / 横屏：沿用 `ReaderControlTokens.SheetMaxWidth` 居中。
- 面板打开时阅读器手势不应穿透：scrim 消费点击并关闭面板（与现有行为一致）。
- 翻译不可用时不显示翻译 tile。「翻译」tab 保留，内部显示现有的禁用态。
- TV：tab 栏、tile、chip 均使用 `tvFocusable`，方向键顺序为 快捷层 → tab 栏 → 页面内容。

## 7. 测试

JVM 单元测试（`app/src/test/.../reader/ui/compose/panel/`）：
- `readerPanelColors` 两个重载：各 `NovelReaderThemePreset` × 明 / 暗、各 `ReaderBackground` × 系统明 / 暗，断言 `isDark`、`content` 与 `container` 的对比度 ≥ 4.5:1。
- 由 `ReaderPanelColors` 生成的 `ColorScheme` 中所有 `surfaceContainer*` 均等于 `card` 或 `container`（防止 P1 回归）。
- `StableAnchoredSheetLayout` 锚点计算：有 / 无 Peek，Peek 超 60% 时退化。
- 漫画快捷 tile 列表：翻译可用 / 不可用。

手动验证（模拟器截图，`android layout` 检查层级）：
- 小说：棕褐 / Slate × 系统明 / 暗；iOS 风格与 MD3 风格；E-ink。
- 漫画：深色繁杂页面上的玻璃可读性；默认 / 黑色背景。
- Peek ↔ 展开 ↔ 全屏拖动、返回键关闭、TV 方向键导航。

构建验证：`./gradlew :app:compileDebugKotlin`、相关 `testDebugUnitTest`。
