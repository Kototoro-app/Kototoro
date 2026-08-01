# Compose 迁移：当前状态快照

> 最后校对日期：2026-07-21
>
> 本文件只描述当前代码事实，不记录历史决策，不展开未来计划。
>
> 最小校验基线：`./gradlew :app:compileDebugKotlin --no-daemon` 已于 2026-07-21 通过。

## 迁移深度定义

| 深度 | 定义 | 标准 |
|------|------|------|
| **L1** | Compose UI body | 渲染层用 Compose，但 host 仍是 Fragment / DialogFragment / BaseAdaptiveSheet / ViewBinding Activity |
| **L2** | Compose route + ViewModel 边界清楚 | Activity `setContent {}` 或 Compose `NavHost` 承载，主要交互已走 Compose 路由 |
| **L3** | 去壳完成 | 无 Fragment / DialogFragment / XML host 依赖 |
| **L4** | 可讨论共享层 | 状态与 UI 对 Android API 直接依赖很薄，可开始评估 commonMain |

---

## 整体概览

- **Compose UI 覆盖率**：~85%（按页面数估算），**代码层面 49 个文件仍 import ViewBinding（较上轮减少 104 个）**
- **主壳导航**：已是 Compose `NavHost`，路由已全面 typed
- **高频内容页**：Home / Explore / History / Favorites / Feed / Local / ContentList 主体已是 Compose 路由，L2 以上
- **设置页**：可见 UI 已 Compose 化，仍保留 28 个 Fragment 壳（ComposeView 宿主），但 `SourceSettingsFragment`、`SettingsTabbedFragmentsScreen` 等关键遗留路径已去壳
- **详情页**：Compose body 完整（EntityGraph + Tracking 统一已完成），Activity 壳仍在
- **阅读器/视频**：核心渲染仍保留 ViewBinding + AndroidView 边界，但对话框/叠加层/浮层已全面 Compose 化
- **弹窗/Sheet**：本轮移除了 `ChaptersPagesSheet`、`ContentStatsSheet`、`AlternativesSheet`、`FilterSheetFragment`、`TagsCatalogSheet`、`ListConfigBottomSheet` 等大量 Sheet 和 DialogFragment；仅剩 5 个 `BaseAdaptiveSheet` 子类
- **列表适配器**：49 个文件仍使用 AdapterDelegates + ViewBinding 渲染列表项（本轮未触及）
- **XML 布局**：205 → 145 个（本轮删除 48 个，剩余多为 item_* 列表项布局、preference 控件、widget 布局）
- **CMP/commonMain**：无基础设施

### ViewBinding / Fragment 详细清单（2026-07-21 更新）

#### Activity（24 个 `BaseActivity` + `ViewBinding` 宿主）

| 类别 | 文件 | 状态 |
|------|------|------|
| 主入口 | `MainActivity`、`SearchActivity` | 核心阻塞项 |
| 详情 | `DetailsActivity`、`AlternativesActivity` | Phase 1 |
| 阅读器 | `ReaderActivity`、`NovelReaderActivity` | 暂缓（核心渲染仍用 ViewBinding） |
| 视频 | `VideoPlayerActivity` | Compose 根；MPV/弹幕为 AndroidView 边界 |
| 收藏 | `FavouriteCategoriesActivity`、`FavouritesCategoryEditActivity` | L2 |
| 图片 | `ImageActivity` | L2 |
| 设置子页 | `UnifiedSourcesActivity`、`SourcesCatalogActivity`、`SourcePresetListActivity`、`SourcePresetEditActivity`、`OverrideConfigActivity`、`ProtectSetupActivity`、`ReaderTapGridConfigActivity`、`ContentDirectoriesActivity`、`JsonSourceEditActivity`、`ScrobblerConfigActivity` | 逐个评估 |
| 浏览器 | `BaseBrowserActivity` | 需 WebView |
| OAuth | `KitsuAuthActivity`、`MangaUpdatesAuthActivity` | 低优先 |
| 其他 | `TrackerDebugActivity`、`StatsActivity`、`AppUpdateActivity`、`ProtectActivity`、`ShelfWidgetConfigActivity`、`RecentWidgetConfigActivity` | 低优先 |

#### Sheet（5 个 `BaseAdaptiveSheet` 子类，较上轮减少 14 个）

| 类别 | 文件 | 状态 |
|------|------|------|
| 核心 | `ScrobblingInfoSheet`、`ScrobblingSelectorSheet` | 仍保留旧壳 |
| 其他 | `WelcomeSheet`、`TrackerCategoriesConfigSheet` | 低优先 |

本轮移除：`ChaptersPagesSheet`、`ContentStatsSheet`、`AlternativesSheet`、`FilterSheetFragment`、`TagsCatalogSheet`、`ListConfigBottomSheet`、`TranslationTaskPanelSheet` → Compose 替代

#### DialogFragment（仅剩零散引用，不再有独立 DialogFragment 子类）

本轮移除：`DownloadDialogFragment`、`BackupDialogFragment`、`RestoreDialogFragment`、`ImportDialogFragment`、`ImportJsonDialogFragment`、`SyncHostDialogFragment`、`LocalInfoDialog`、`ContentDirectorySelectDialog`、`FavoriteDialog`、`ErrorDetailsDialog` → 全部替换为 Compose Dialog/Route

#### Fragment（28 个，全部为设置页 ComposeView 壳 + 少量工具 Fragment）

设置页的 28 个 `*Fragment` 类仍保留作为 ComposeView 宿主壳，但本轮已移除 `SourceSettingsFragment`（PreferenceFragmentCompat）、`SettingsTabbedFragmentsScreen`（AndroidView tab host）、`ExtensionsBrowserFragment`、`ExtensionRepositoriesFragment`、`JsonSourcesFragment` 等关键遗留链路上的 Fragment。

设置页 Fragment 壳清单：
`AIImageEnhancementSettingsFragment`、`AISettingsFragment`、`AIVideoEnhancementSettingsFragment`、
`AppearanceSettingsFragment`、`DownloadsSettingsFragment`、`NotificationSettingsLegacyFragment`、
`OcrModelsFragment`、`PlaybackSettingsFragment`、`ProxySettingsFragment`、`ReaderSettingsFragment`、
`ServicesSettingsFragment`、`StorageAndNetworkSettingsFragment`、`SuggestionsSettingsFragment`、
`SyncSettingsFragment`、`TranslationApiSettingsFragment`、`TranslationEndToEndApiSettingsFragment`、
`TranslationSettingsFragment`、`TtsSettingsFragment`、`UsersSettingsFragment`、`AboutSettingsFragment`、
`ChangelogFragment`、`DiscordSettingsFragment`、`NavConfigFragment`、`SourcesSettingsFragment`、
`TrackerSettingsFragment`、`BackupsSettingsFragment`、`DataCleanupSettingsFragment`

#### AdapterDelegates（49 个文件，本轮未触及）

列表项使用 `adapterDelegateViewBinding` 模式渲染，绑定到 XML 布局。主要分布在 `list/ui/adapter/`、`search/ui/suggestion/adapter/`、`details/ui/adapter/`、`settings/` 子树。

#### XML 布局文件（145 个，较上轮减少 60 个）

本轮移除 48 个 layout XML + 5 个 pref XML。剩余 145 个主要为：
- `item_*` 列表项布局（~70 个，对应 AdapterDelegates）
- `activity_*` 布局（~27 个，对应 ViewBinding Activity 宿主壳）
- `preference_*` 控件布局（~10 个）
- `widget_*` / `view_*` 自定义 View 布局
- `sheet_*` 残留（4 个：filter, scrobbling, scrobbling_selector, welcome）
- `fragment_*` 残留（8 个）

---

## 主壳 / 导航

### MainActivity + KototoroApp — **L2**

| 方面 | 当前状态 |
|------|------|
| Activity 宿主 | `MainActivity` 仍继承 `BaseActivity`，类型参数为 `ActivityMainBinding`，通过 `setContentViewWebViewSafe { ActivityMainBinding.inflate(...) }` 托管根视图 |
| 主内容入口 | `viewBinding.composeRoot.setContent { KototoroApp(...) }` |
| 导航 | `KototoroApp.kt` + `AppNavGraph.kt` 使用 Compose `NavHost`，路由已全面 typed（`@Serializable` data class） |
| 搜索链路 | `onQueryChanged` / `onSearch` / `suggestions` 已完整接回 |
| 过滤器默认态 | `clearActiveFilters()` 会把语言预设 / 内容类型 / 源标签三类过滤器全部重置为可见 |
| 顶栏更多菜单 | `KototoroTopBar` 直接用 Compose `DropdownMenu`；展示视图与网格大小已合并为统一"Display options"面板 |
| 顶栏 anchor | 已改用 `LocalView.current`，不再依赖隐藏 `AndroidView` |
| 首启初始化 | `savedInstanceState == null` 时调用 `onFirstStart()`，首启服务链路已恢复 |
| 残留问题 | Activity 里仍持有多组 `mutableStateOf` 顶栏/过滤器/inset 状态，主壳仍未直接 `setContent {}` |

**结论**：主壳导航已稳定进入 Compose 路由层，但 Activity 根宿主和部分状态仍停留在旧结构里，不能记为 L3。

## 高频内容页

| 页面/模块 | 深度 | 当前状态 |
|------|------|------|
| Home | **L2** | Compose 路由，接入全局顶栏过滤器回调。HomeScreen 已从 2593 行精简至 ~650 行，actions 已归组为 `HomeScreenActions` data class |
| Explore / Discover | **L2** | 统一走 `KototoroExploreHostRoute`，`discover` 与 `explore` 路由都汇入 Compose Host。`DiscoverViewModel` 通过 `GlobalFavoritesState` 读取 group tab |
| History | **L2** | Compose `HistoryScreen`，列表/清理对话框均在 Compose 路径 |
| Favorites | **L2** | `FavouritesActivity` 已 `setContent`，宿主为 `FavoritesHostScreen` |
| Feed | **L2** | Compose 路由，仍通过 ViewModel + filter callback 与主顶栏联动 |
| Local / Suggestions / Updated / Bookmarks 等主列表 | **L2** | 已走 `AppContentListRoute` 或等价 Compose route |
| Downloads | **L2** | `DownloadsActivity` 使用 `setContent {}` |
| ContentList / Search | **L2** | 主体列表 UI 已是 Compose |

## 详情页 / Tracking

### Details — **L1（Compose body 基本完成，Activity 壳仍在）**

| 方面 | 当前状态 |
|------|------|
| Activity 宿主 | `DetailsActivity` 仍继承 `BaseActivity`，类型参数为 `ActivityDetailsBinding` |
| Compose 接入 | 通过 `viewBinding.composeView.setContent { DetailsScreen(...) }` |
| Pane 主入口 | 普通详情页里的章节 / 页面 / 书签入口已在 `DetailsScreen` 内使用 Compose `ModalBottomSheet` |
| EntityGraph + Tracking 统一 | `DetailsScreen` 已统一接入 EntityGraph 和 Tracking origins，`DetailsHeader` 已接入 `DetailsBindingCard` |
| 详情头部动作 | 收藏按钮已显式使用 `onPrimary/onSurface` 配色；翻译按钮只在作品语言与当前目标翻译语言不一致时显示 |
| 紧凑底部 pane | 紧凑态 pane 可收窄为居中的悬浮宽度；展开透明度改为连续过渡 |
| 转场 | 仍依赖 XML 共享元素转场；仅保留 1200ms fallback 兜底 |
| 封面过渡 | Compose 主列表到详情页封面 bounds 透传已覆盖 Compose 主列表、Home 三合一卡片、Feed 动态卡片、更新轮播、通用 Discover 组件 |

**结论**：详情页的 Compose body 已很重。但 Activity 壳、转场锚点和若干遗留 action 仍在，当前只能保守记为 L1。

### Tracking Site Details — **L2**

| 方面 | 当前状态 |
|------|------|
| Activity | `TrackingSiteDetailsActivity` 已改为 `AppCompatActivity` + `setContent {}` |
| Screen | `TrackingSiteDetailsScreen` 已与普通详情页共享更多 Compose 视觉结构 |
| ViewModel | 已暴露 `linkedTrackingItem` 等绑定信息，能复用统一绑定卡片模型 |

**结论**：tracking 详情页已完成 Compose host 迁移。

---

## 自 2026-05-01 以来的变化

### 第二轮大规模清理（2026-07-21，本次提交）

**删除 126 个文件（+2,205 / −24,253 行）**：

| 类别 | 数量 | 代表性文件 |
|------|------|------|
| Fragment / Sheet 壳 | 40+ | `ChaptersPagesSheet`、`ContentStatsSheet`、`AlternativesSheet`、`FilterSheetFragment`、`TagsCatalogSheet`、`ListConfigBottomSheet`、`TranslationTaskPanelSheet`、`HomeFragment`、`ContentListFragment`、`PreviewFragment`、`FilterHeaderFragment`、`RelatedListFragment`、`DiscoverCategoryFragment`、`ContentPickerFragment`、`PagePickerFragment`，以及设置页链路 `ExtensionsBrowserFragment`、`ExtensionRepositoriesFragment`、`JsonSourcesFragment`、`LNReaderRepositoriesFragment`、`ReplaceRulesFragment`、`SourceComposeSettingsFragment`、`SourceSettingsFragment`、`SettingsTabbedFragmentsScreen`、`SettingsSearchFragment` 等 |
| DialogFragment | 10+ | `DownloadDialogFragment`、`BackupDialogFragment`、`RestoreDialogFragment`、`ImportDialogFragment`、`ImportJsonDialogFragment`、`SyncHostDialogFragment`、`LocalInfoDialog`、`ContentDirectorySelectDialog`、`FavoriteDialog`、`ErrorDetailsDialog` |
| ViewBinding 组件 | 8+ | `ReaderActionsView`、`ReaderInfoBarView`、`ReaderToastView`、`ScrollTimerControlView`、`SlidingBottomNavigationView`、`ReadButtonDelegate`、`MainActionButtonBehavior`、`BottomNavOwner` |
| 视频 Sheet | 5 | `DlnaDeviceSheet`、`VideoDanmakuSettingsSheet`、`VideoSettingsSheet`、`VideoSubtitleSettingsSheet`、`VideoSuperResolutionSheet` / `VideoSuperResolutionAdvancedSheet` |
| 核心基础设施 | 3 | `BaseFragment`、`BasePreferenceFragment`、`AlertDialogFragment` |
| Debug 壳 | 2 | `DebugSettingsFragment`（debug + nightly） |
| XML 布局 | 48 | 全部 activity_*、fragment_*、sheet_*、dialog_* 根布局 |
| XML pref | 5 | `pref_root.xml`、`pref_debug.xml`（debug + nightly ×2） |

**新增 21 个 Compose 替代文件**：

| 领域 | 新文件 |
|------|------|
| 备份恢复 | `RestoreDialog.kt` |
| 错误处理 | `ErrorDetailsActivity.kt` |
| 筛选面板 | `FilterSheetRoute.kt` |
| 详情导航 | `DetailsReaderLauncher.kt` |
| 阅读器 TTS | `NovelTtsVoiceDialog.kt` |
| 阅读器操作 | `ReaderActionsContent.kt` |
| 翻译面板 | `ComposeTranslationTaskPanel.kt`、`TranslationTaskBenchmarkFormatter.kt` |
| 阅读器选择 | `ComposeReaderSelectionDialog.kt` |
| 存储目录 | `ContentDirectorySelectRoute.kt` |
| 视频播放器 | `VideoPlayerRenderLayer.kt`、`VideoGestureOverlays.kt`、`VideoSeekFeedback.kt`、`VideoSubtitleOverlay.kt`、`VideoScreenLockOverlay.kt`、`VideoPlayerInfoDialog.kt`、`VideoActionDialog.kt`、`VideoSelectionDialog.kt`、`VideoSuperResolutionDialog.kt`、`DlnaDeviceDialog.kt`、`VideoPlayerNativeInitErrorDialog.kt` |

---

## 继续存在的阻塞项

- `MainActivity` 仍依赖 `ActivityMainBinding` 作为 Compose 根宿主，尚未直接 `setContent {}`
- `DetailsActivity` 仍依赖 `ActivityDetailsBinding`
- 24 个 Activity 仍继承以 `ViewBinding` 为类型参数的 `BaseActivity`
- 28 个设置页 Fragment 壳（ComposeView 宿主）仍保留
- 5 个 `BaseAdaptiveSheet` 子类（ScrobblingInfoSheet、ScrobblingSelectorSheet、WelcomeSheet、TrackerCategoriesConfigSheet）
- 49 个 AdapterDelegate 文件仍使用 ViewBinding 渲染列表项
- 145 个 XML 布局文件（多为 item_* 列表项布局和 preference 控件）
- 阅读器/视频核心渲染仍走 ViewBinding + AndroidView 边界
- 系统级共享元素锚点仍未完全收口
- 仓库内仍无 `shared/` / `commonMain/` / `expect/actual` 结构

---

## 关键阻塞项（Phase 1–2，2026-07-21 更新）

- `MainActivity` 仍依赖 `ActivityMainBinding` 作为 Compose 根宿主，尚未直接 `setContent {}`
- `DetailsActivity` 仍依赖 `ActivityDetailsBinding`
- 28 个设置页 Fragment 壳（ComposeView 宿主），虽然内容已 Compose 化
- 49 个 AdapterDelegate 文件仍使用 ViewBinding 渲染列表项 → 非 trivial，需计划
- 5 个 `BaseAdaptiveSheet` 子类（低优先：Scrobbling、Welcome、Tracker）
- tracking 自动推荐绑定仍局限于本地内容
- 系统级共享元素锚点仍未完全收口

### 质量收口（Phase 5）

- 多处 `collectAsState` 未改用 `collectAsStateWithLifecycle`
- 仓库内仍无可复用的 `shared/` / `commonMain/` / `expect/actual` 结构
