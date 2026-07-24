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
| 小说阅读器 | 界面承载已完成纯 Compose 化 | 窗口级 `decorView` 适配 |
| 漫画阅读器 | 阅读主体为 Compose，工具面板仍混合 | Fragment Sheet、ViewBinding、XML、辅助 View Activity |
| 视频播放器 | 仍是 View 主体的混合架构 | ViewBinding Activity、XML 控制层、`ComposeView` 岛、Fragment Sheet |

总体完成度由高到低为：小说阅读器、漫画阅读器、视频播放器。

## 漫画阅读器

### 已完成部分

`ReaderActivity` 已直接承载 Compose 阅读内容，不再通过读者根 XML、ViewBinding 或
`ComposeView` 托管核心页面。图片分页、阅读器 chrome 和主要操作面板不属于本次发现的
View 残留。

### 现役 Fragment 与 XML

共享章节、页面和书签内容已由 `ChaptersPagesTabsContent` 直接服务详情页和漫画 Reader Compose
根。Reader 的普通入口、书签长按入口以及 iOS/非 iOS 控制样式均不再打开 Fragment。

旧 `BaseAdaptiveSheet<SheetChaptersPagesBinding>`、菜单适配器、AppRouter 兼容方法、Reader
Fragment 关闭兜底和 XML/`ComposeView` 宿主均已删除：

- `app/src/main/kotlin/org/skepsun/kototoro/details/ui/pager/ChaptersPagesSheet.kt`
- `app/src/main/res/layout/sheet_chapters_pages.xml`

详情页和 Reader 现在都以 Compose 直接拥有章节、页面和书签内容，不再保留
Fragment → XML → `ComposeView` 过渡结构。

翻译任务面板运行时入口已切换到状态提升的 Compose `ModalBottomSheet`：

- `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeTranslationTaskPanel.kt`

新面板直接观察 `ReaderViewModel.translationTaskPanelVersion`，保留任务筛选、状态汇总、失败页
重试、逐页结构化日志、复制和单页重试。无 UI 的 `TranslationTaskBenchmarkFormatter` 负责解析
跨页耗时 p50/p95、缓存命中率、OCR 管线/回退/引擎/失败码分布，以及逐页语言、OCR、耗时、
失败原因和时间线。二次引用核验后，以下无运行时调用方的旧文件已经删除：

- `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/TranslationTaskPanelSheet.kt`
- `app/src/main/res/layout/sheet_translation_task_panel.xml`
- `app/src/main/res/layout/item_translation_task_panel.xml`

漫画翻译任务面板运行时路径不再依赖 `View.OnClickListener`、RecyclerView/Adapter、
ViewBinding、Fragment 或 `MaterialAlertDialogBuilder`。旧报告额外输出的设备/模型配置、翻译
样例对和 ROI 结论文案未进入新面板；核心任务操作与可观测性能指标已经迁移。

`ReaderActivity` 的翻译快捷操作与语言选择已迁为 Controller 持有状态的 Compose
`AlertDialog`。漫画 Reader 核心路径不再包含 View Dialog。

### 辅助功能的 View Activity

以下页面不是核心阅读画布，但仍属于漫画阅读器功能链：

- `PageCropActivity` 已使用 Compose 根布局，标题栏、比例选择、保存/取消、Insets 和状态均由
  Compose 管理；uCrop 官方仅提供 View API，因此唯一的 `UCropView` 通过 `AndroidView` 作为
  受控第三方互操作边界：
  `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/PageCropActivity.kt`
- 原页面裁剪布局已删除：
  `app/src/main/res/layout/activity_page_crop.xml`
- `ColorFilterConfigActivity` 已改为 Compose 根，预览、滤镜开关、参数滑块、保存目标选择和
  未保存更改确认均由 Compose 管理：
  `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/colorfilter/ColorFilterConfigActivity.kt`
- 原返回分派器及手机/横向大屏两套 ViewBinding XML 已删除；颜色滤镜配置路径不再保留
  ViewBinding、View Dialog 或 XML 过渡实现。

### 已清理遗留

二次引用核验后已删除以下无消费者旧组件：

- `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderActionsView.kt`
  - 自定义 View 内部创建 `ComposeView`
- `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ScrollTimerControlView.kt`
  - 自定义 View 内部 inflate `ViewScrollTimerBinding`
- `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderInfoBarView.kt`
  - 信息栏的进度、时间、电量、背景与对比描边均已有 Compose 实现
- `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderToastView.kt`
  - 无运行时消费者，Reader 提示状态已由 Compose 根管理
- `app/src/main/res/layout/layout_reader_actions.xml`

同时删除了 `attrs.xml` 中仅供旧 `ReaderInfoBarView` 使用的 styleable。删除前已检查生产源码、
资源引用、布局类名和变体资源，没有发现运行时消费者。

详情页旧 `ReadButtonDelegate` 已无构造调用方，其 `MaterialSplitButton`、`PopupMenu` 和 View
监听实现已删除。仍由 Compose 详情页调用的阅读器启动逻辑已迁入 `DetailsReaderLauncher.kt`，
启动状态匹配与跳转行为保持不变。

## 小说阅读器

### 已完成部分

`NovelReaderActivity` 已直接设置 Compose content。核心阅读正文、分页/连续模式、工具栏、
底栏、章节面板和外观面板没有发现 `ComposeView`、ViewBinding、Fragment、RecyclerView
Adapter 或 `setContentView` 残留。

Activity 对 `window.decorView` 的访问仅用于窗口级交互和锚点，不代表 View UI 树重新承载
了小说阅读器。但按“功能代码零 View 引用”的严格目标，它仍需由 Compose 触觉反馈、Compose
消息宿主和窗口级 API 替代，不能视为最终清零。

### Compose Dialog

TTS 系统音色、OEM 语言回退和 Legado 网络音源选择已统一为状态提升的 Material3 Compose
`AlertDialog`：

- `app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelReaderActivity.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/reader/novel/compose/NovelTtsVoiceDialog.kt`

`NovelReaderActivity` 的运行时 UI 路径已不再引用 `MaterialAlertDialogBuilder`、`ComposeView`、
ViewBinding、Fragment、`setContentView` 或 `findViewById`。系统 TTS 的原生服务对象仅负责
查询音色，并在 Compose 对话框选择或关闭时释放，不参与 UI 承载。

### 已清理 XML

二次引用核验后已删除以下无消费者文件：

- `app/src/main/res/layout/activity_novel_reader.xml`
- `app/src/main/res/layout/sheet_novel_settings.xml`
- `app/src/main/res/layout/item_bookmark_novel.xml`

小说阅读器不再保留旧 Activity、设置 Sheet 或书签 item XML。

## 视频播放器

### Activity 根结构

`VideoPlayerActivity` 已切换为 `BaseComposeFullscreenActivity`，Activity 级 `setContent` 直接
承载播放器根界面。以下过渡结构已删除：

- `app/src/main/res/layout/activity_video_player.xml`
- `app/src/main/res/layout-port/activity_video_player.xml`
- `ActivityVideoPlayerBinding`
- `MaterialToolbar` 与 Media3 `PlayerControlView`

### ComposeView 过渡岛

播放器运行路径中的 `ComposeView` 已清零。控制 chrome、Dialog、手势反馈、字幕覆盖、锁屏覆盖、
seek 反馈及空间切换 FAB 共享 Activity 的同一 Compose 状态树。

字幕覆盖、锁屏触摸拦截/解锁按钮以及拖拽 seek 时间与进度反馈也已迁入同一 Compose 状态树，
对应的 XML `TextView`、`View`、`ImageButton`、`LinearProgressIndicator` 和反向更新传统
`DefaultTimeBar` 的代码均已删除。

MPV 初始化通过一次性就绪信号与 Compose `AndroidView` 创建时序协调，媒体加载会等待 Surface
初始化完成，避免 `lateinit` 未初始化和首次 Composition 时序竞争。

### 现役 XML 控制层

旧横竖屏控制布局已删除：

- `app/src/main/res/layout/video_player_docked_toolbar.xml`
- `app/src/main/res/layout/video_player_docked_toolbar_portrait.xml`

视频信息弹窗已由 Compose `AlertDialog` 承载，可选择的等宽诊断文本继续复用原有数据生成逻辑：

- `app/src/main/kotlin/org/skepsun/kototoro/video/ui/compose/VideoPlayerInfoDialog.kt`

旧 `dialog_video_player_info.xml` 已删除。Activity 级字幕、音轨、清晰度、画面比例、播放速度、
默认速度和跳转间隔选择已统一为 Compose 单选对话框；MPV 初始化失败提示也已改为 Compose
`AlertDialog`。`VideoPlayerActivity` 自身不再使用 `MaterialAlertDialogBuilder`。

`MpvConfigManager` 已收敛为纯文件读写组件，不再创建 `TextInputEditText`、`ScrollView` 或
`MaterialAlertDialogBuilder`。mpv.conf 的指南、多行等宽编辑、保存、重置和反馈由 Compose
播放设置页直接管理。

### 现役 Fragment Sheet

以下面板均继承 `BaseAdaptiveSheet` 并使用 ViewBinding/XML：

旧 `VideoSettingsSheet` / `sheet_video_settings.xml` 已确认无运行时入口后删除。播放器现役设置
入口由 `buildPlayerSettingsActions()` 提供，重复的字幕、音轨、速度、比例等选择已由 Compose
单选对话框承载。

旧 `VideoSubtitleSettingsSheet` 与 `VideoDanmakuSettingsSheet` 及对应 XML 同样没有运行时入口，
已删除。现役字幕轨道选择和弹幕开关保留在播放器设置动作中；高级样式选项后续直接进入
Compose 设置面板。

播放器超分辨率模式、预设子模式和自定义 GLSL 开关已由
`VideoSuperResolutionDialog.kt` 统一承载；旧 `VideoSuperResolutionSheet` 及主 XML 已删除。
全局 AI 视频增强 Compose 设置页也已直接承载自定义 GLSL 开关，旧高级 Sheet 与 XML 已删除。

DLNA 设备发现与投屏命令已由 Activity 协程管理，加载、空状态、设备列表和投屏中状态由
Compose 对话框承载：

- `app/src/main/kotlin/org/skepsun/kototoro/video/ui/compose/DlnaDeviceDialog.kt`

旧 `BottomSheetDialogFragment`、RecyclerView Adapter 和 XML 已删除：

- `app/src/main/kotlin/org/skepsun/kototoro/video/ui/DlnaDeviceSheet.kt`
- `app/src/main/res/layout/sheet_dlna_devices.xml`

### 平台互操作保留项

MPV `SurfaceView`、弹幕 `DanmakuView` 及类似播放器渲染组件不应为了“无 View”而强行
重写。合理的 Compose 终态是：

- Compose 负责播放器根布局、控制层、状态、动画、弹窗和面板。
- 必要的原生或第三方渲染 View 通过 `AndroidView` 承载。
- View 生命周期、播放器生命周期和 Compose 状态之间保持单向、可测试的适配边界。

因此，底层渲染 View 属于受控互操作，不应与 `ComposeView` 过渡岛混为一谈。

最终形态的渲染互操作边界已集中到
`app/src/main/kotlin/org/skepsun/kototoro/video/ui/compose/VideoPlayerRenderLayer.kt`：它只通过
`AndroidView` 承载 `CustomMpvView` 与 `DanmakuView`，不包含工具栏、控制器、弹窗或其他传统
View UI。Activity 已接入该根；Surface 手势、`PixelCopy` 截图和第三方渲染 View 属于明确的平台
互操作边界，不是 `ComposeView` 过渡态。

### 已删除无引用资源

资源别名、构建变体、生成绑定和运行时资源名查找均未发现消费者后，已删除旧工具栏布局：

- `app/src/main/res/layout/video_player_docked_toolbar_old.xml`
- `app/src/main/res/layout/video_controller.xml`
- `app/src/main/res/layout/video_player_docked_toolbar.xml`
- `app/src/main/res/layout/video_player_docked_toolbar_portrait.xml`
- `app/src/main/res/layout/item_video_demo.xml`

## 推荐迁移顺序

1. 如仍需完整翻译导出报告，将设备/模型配置和样例对加入 formatter。
2. 播放器传统 Snackbar/Toast 反馈已收敛为 Compose `SnackbarHost`；空间切换 Delegate 的
   选择层、玻璃菜单宿主与切换幕布也已嵌入漫画、小说、视频各自的 Compose 根树，不再动态
   创建 `ComposeView` 或调用 `addContentView`。
3. 无引用的 `item_video_demo.xml` 已删除；继续以运行时交互验收替代过渡层清理。

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
