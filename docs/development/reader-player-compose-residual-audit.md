# 阅读器与视频播放器 Compose 残留审计

本文记录漫画阅读器、小说阅读器和视频播放器当前仍存在的 View、Fragment、XML 与
`ComposeView` 过渡层。审计基于 2026-07-23 的源码引用关系；文件存在但没有发现运行时
引用的项目单独标记为“疑似遗留”，不能仅凭本表直接删除。

## 判定标准

- **纯 Compose**：Activity 直接设置 Compose content，功能界面不依赖 ViewBinding、
  `ComposeView`、Fragment 或 XML 布局。
- **过渡态**：XML/View/Fragment 承载 Compose，或 Compose 页面仍调用 View Dialog、
  ViewBinding Sheet。
- **平台互操作**：播放器渲染面、第三方自定义 View 等必须由 Android View 提供的能力。
  这类组件可通过 `AndroidView` 接入 Compose，不以消除底层 View 为迁移目标。
- **疑似遗留**：文件仍存在，但静态引用扫描没有找到调用方。删除前仍需执行资源合并、
  编译和设备回归。

## 结论

| 功能 | 当前状态 | 主要残留 |
| --- | --- | --- |
| 小说阅读器 | 主体基本完成 Compose 化 | View Dialog、疑似无引用 XML |
| 漫画阅读器 | 阅读主体为 Compose，工具面板仍混合 | Fragment Sheet、ViewBinding、XML、辅助 View Activity |
| 视频播放器 | 仍是 View 主体的混合架构 | ViewBinding Activity、XML 控制层、`ComposeView` 岛、Fragment Sheet |

总体完成度由高到低为：小说阅读器、漫画阅读器、视频播放器。

## 漫画阅读器

### 已完成部分

`ReaderActivity` 已直接承载 Compose 阅读内容，不再通过读者根 XML、ViewBinding 或
`ComposeView` 托管核心页面。图片分页、阅读器 chrome 和主要操作面板不属于本次发现的
View 残留。

### 现役 Fragment 与 XML

共享章节、页面和书签面板仍是 `BaseAdaptiveSheet<SheetChaptersPagesBinding>`：

- `app/src/main/kotlin/org/skepsun/kototoro/details/ui/pager/ChaptersPagesSheet.kt`
- `app/src/main/res/layout/sheet_chapters_pages.xml`

该 XML 内部包含 `ComposeView`，形成 Fragment → XML → Compose 的过渡结构。该面板同时
服务详情页和阅读器，迁移时必须保留两处入口、三态展开行为、当前章节定位及返回手势。

翻译任务面板仍是完整的 ViewBinding Fragment：

- `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/TranslationTaskPanelSheet.kt`
- `app/src/main/res/layout/sheet_translation_task_panel.xml`
- `app/src/main/res/layout/item_translation_task_panel.xml`

它仍依赖 `View.OnClickListener`、RecyclerView/Adapter 和 `MaterialAlertDialogBuilder`。

`ReaderActivity` 内仍有 `MaterialAlertDialogBuilder` 调用。它们属于 View Dialog 过渡层，
后续应由状态提升的 Compose Dialog 替代。

### 辅助功能的 View Activity

以下页面不是核心阅读画布，但仍属于漫画阅读器功能链：

- `PageCropActivity` 使用 `setContentView(R.layout.activity_page_crop)`：
  `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/PageCropActivity.kt`
- 页面裁剪布局：
  `app/src/main/res/layout/activity_page_crop.xml`
- `ColorFilterConfigActivity` 使用 `ActivityColorFilterBinding`：
  `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/colorfilter/ColorFilterConfigActivity.kt`
- 颜色滤镜布局：
  `app/src/main/res/layout/activity_color_filter.xml`
  和 `app/src/main/res/layout-w600dp-land/activity_color_filter.xml`

### 疑似无引用遗留

静态引用扫描未发现以下旧组件被活动界面实例化：

- `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderActionsView.kt`
  - 自定义 View 内部创建 `ComposeView`
- `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ScrollTimerControlView.kt`
  - 自定义 View 内部 inflate `ViewScrollTimerBinding`
- `app/src/main/res/layout/layout_reader_actions.xml`

这些项目适合作为清理候选，但删除前应再次检查反射、布局类名引用和变体资源。

## 小说阅读器

### 已完成部分

`NovelReaderActivity` 已直接设置 Compose content。核心阅读正文、分页/连续模式、工具栏、
底栏、章节面板和外观面板没有发现 `ComposeView`、ViewBinding、Fragment、RecyclerView
Adapter 或 `setContentView` 残留。

Activity 对 `window.decorView` 的访问仅用于窗口级交互和锚点，不代表 View UI 树重新承载
了小说阅读器。

### 现役 View Dialog

`NovelReaderActivity` 仍有三组 `MaterialAlertDialogBuilder` 调用，主要用于 TTS 音色等
选择流程：

- `app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelReaderActivity.kt`

这些弹窗使小说阅读器尚未达到严格意义上的纯 Compose。迁移时应将弹窗可见性、选项和
结果提升为 Compose 状态，并由 Compose `AlertDialog` 或自适应 Compose Sheet 展示。

### 疑似无引用 XML

当前源码和资源引用扫描没有找到以下文件的消费者：

- `app/src/main/res/layout/activity_novel_reader.xml`
- `app/src/main/res/layout/sheet_novel_settings.xml`
- `app/src/main/res/layout/item_bookmark_novel.xml`

它们是小说阅读器迁移后的优先清理候选。

## 视频播放器

### Activity 根结构

`VideoPlayerActivity` 仍继承 `BaseFullscreenActivity<ActivityVideoPlayerBinding>`，并通过
`setContentView(ActivityVideoPlayerBinding.inflate(layoutInflater))` 创建界面：

- `app/src/main/kotlin/org/skepsun/kototoro/video/ui/VideoPlayerActivity.kt`
- `app/src/main/res/layout/activity_video_player.xml`
- `app/src/main/res/layout-port/activity_video_player.xml`

当前根界面仍大量使用 `findViewById`、`MaterialToolbar`、`ImageButton`、`MaterialButton`、
`TextView`、Media3 `DefaultTimeBar` 和其他传统 View。

### ComposeView 过渡岛

`VideoPlayerActivity` 至少存在三处 `ComposeView` 承载点：

1. 运行时创建的顶部控制区。
2. 运行时创建的底部控制区。
3. 从现有 XML View 树中通过 `findViewById<ComposeView>` 获取的 Compose 内容。

这说明播放器当前是 View 容器托管局部 Compose，而不是 Compose 根界面托管必要的
Android View。

### 现役 XML 控制层

播放器仍保留以下主要布局：

- `app/src/main/res/layout/video_controller.xml`
- `app/src/main/res/layout/video_player_docked_toolbar.xml`
- `app/src/main/res/layout/video_player_docked_toolbar_portrait.xml`
- `app/src/main/res/layout/dialog_video_player_info.xml`

视频信息弹窗由 `VideoPlayerActivity` 直接 inflate
`dialog_video_player_info.xml`，同时播放器内仍有多处 `MaterialAlertDialogBuilder`。

### 现役 Fragment Sheet

以下面板均继承 `BaseAdaptiveSheet` 并使用 ViewBinding/XML：

- `VideoSettingsSheet` / `sheet_video_settings.xml`
- `VideoSubtitleSettingsSheet` / `sheet_video_subtitle_settings.xml`
- `VideoDanmakuSettingsSheet` / `sheet_video_danmaku_settings.xml`
- `VideoSuperResolutionSheet` / `sheet_video_super_resolution.xml`
- `VideoSuperResolutionAdvancedSheet` /
  `sheet_video_super_resolution_advanced.xml`

对应 Kotlin 文件位于：

`app/src/main/kotlin/org/skepsun/kototoro/video/ui/`

DLNA 设备面板仍直接继承 `BottomSheetDialogFragment`，通过 `inflate` 和 `findViewById`
创建 RecyclerView 界面：

- `app/src/main/kotlin/org/skepsun/kototoro/video/ui/DlnaDeviceSheet.kt`
- `app/src/main/res/layout/sheet_dlna_devices.xml`

### 平台互操作保留项

MPV `SurfaceView`、弹幕 `DanmakuView` 及类似播放器渲染组件不应为了“无 View”而强行
重写。合理的 Compose 终态是：

- Compose 负责播放器根布局、控制层、状态、动画、弹窗和面板。
- 必要的原生或第三方渲染 View 通过 `AndroidView` 承载。
- View 生命周期、播放器生命周期和 Compose 状态之间保持单向、可测试的适配边界。

因此，底层渲染 View 属于受控互操作，不应与 `ComposeView` 过渡岛混为一谈。

### 疑似无引用资源

静态引用扫描没有发现以下旧工具栏布局的调用方：

- `app/src/main/res/layout/video_player_docked_toolbar_old.xml`

删除前仍需检查资源别名、构建变体和运行时资源名查找。

## 推荐迁移顺序

1. 删除经过二次引用核验的小说阅读器 XML 和旧漫画阅读器 View/布局。
2. 将小说阅读器剩余 View Dialog 迁移为 Compose Dialog。
3. 将漫画阅读器翻译任务面板迁移为状态提升的 Compose 面板。
4. 将共享章节面板改为纯 Compose，同时保留详情页和阅读器入口。
5. 把视频播放器根控制层迁移到 Compose，以 `AndroidView` 保留必要渲染面。
6. 迁移视频设置、字幕、弹幕、超分辨率和 DLNA Fragment Sheet。
7. 删除播放器 `ComposeView` 岛、ViewBinding 根布局及确认无引用的旧 XML。

## 删除与验收门槛

每批清理至少执行：

```bash
./gradlew :app:compileDebugKotlin --no-daemon
./gradlew :app:assembleDebug
```

涉及交互迁移时还应在手机、横屏和平板宽度下验证：

- 返回手势优先关闭当前面板。
- 点击控件和面板之外的区域能按阅读器约定关闭 chrome。
- 系统栏、全屏状态和窗口 Insets 不跳动。
- 漫画章节/书签/页面入口在详情页与阅读器中均可用。
- 小说分页、连续跨章、TTS、书签及当前位置恢复不回归。
- 视频播放、旋转、锁定、进度拖动、字幕、弹幕、DLNA 和超分辨率不回归。
- MPV/弹幕 View 的创建、销毁和配置变更生命周期正确。

清理后可再次执行静态检查：

```bash
git grep -n -E "ComposeView|ViewBinding|DialogFragment|BottomSheetDialogFragment|setContentView|findViewById" -- \
  app/src/main/kotlin/org/skepsun/kototoro/reader \
  app/src/main/kotlin/org/skepsun/kototoro/video
```

目标不是在全局消灭 Android View，而是让 Compose 成为三个功能的界面所有者，仅在明确的
平台互操作边界保留 View。
