# CMP UI 收敛盘点与改造计划

日期：2026-10-26。状态：第一批已实施；第二批已共享卡片、列表容器、空态、选择栏、部分确认/分类呈现
、书库筛选面板、页头操作胶囊、来源结果页头、列表加载/出错状态、来源列表行，以及详情页的相关作品区、标签芯片、书签卡片与信息网格与简介展开；第二批只剩详情页整体组合与补充元数据切换，已暂缓（见下）。
原始盘点基于静态代码审查。

依据：[CMP 共享 UI 开发指导](shared-ui-guidelines.md)。盘点基于当前工作树，包含此前尚未提交的
macOS 适配与扩展管理改造；不能仅用 HEAD 的内容复现这份现状。执行每一批前应重新核对相关文件。

## 结论与范围

需要继续收敛，但不是所有桌面文件都应删除或搬进 `commonMain`。

- **优先收敛**：收藏/历史、内容卡片与列表、浏览结果、详情信息，以及设置、下载、备份等独立桌面页面。
- **在已有共享之上继续**：主页、订阅、章节目录、阅读器控制栏和选项面板，避免再实现一遍已有共享模块。
- **保留平台 Adapter**：文件选择、图像解码与缓存、窗口、原生播放器、WebView、安装更新、后台任务执行。
- **前置工作**：共享页面消费按功能划分的状态与操作，不继续接入整个 `DesktopController`。

本次覆盖 `DesktopScreen` 的 36 个入口，以及影响多个入口的主题、基础交互和状态装配。
这是源码结构和调用关系审查，未逐页进行 Android/Mac/Windows 的运行截图对比；下文的“分叉”
表示两端仍有独立呈现或装配，不等同于已经确认了每一处用户可见缺陷。

当前 `core-ui` 配置 Android/JVM 目标。iOS 的原生 UI 迁移、Android 尚无桌面对等能力的页面，
不在本次实施范围；新增跨端需求时再按共享 UI 指导评估。

## 已确认的共享基础

下文的 `core-ui/<包路径>` 简写指 `core-ui/src/commonMain/kotlin/org/skepsun/kototoro/<包路径>`。

| 已有实现 | 当前应保留的价值 |
| --- | --- |
| `core-ui/core/ui/home`、`feed` | 主页布局、Hero、时间线卡片和更新轮播已被桌面消费；继续收敛外围分区装配。 |
| `core-ui/core/ui/adaptive`、`topbar` | 导航栏、宽屏结构、顶栏与标签控件已经有共享实现。 |
| `core-ui/core/ui/settings`、`chips` | 设置行、分组与选择芯片已共享；整页、偏好编辑流程并未因此全部共享。 |
| `core-ui/core/ui/chapters`、`reader/**` | 章节目录、漫画/小说阅读控件、选项和部分渲染呈现已经共享。 |
| `core-ui/core/ui/extensions` | 此前改造已让 Android/桌面共用标签栏和仓库卡片；来源、包列表和导入装配仍各自实现。 |
| `core-domain`、`core-library`、`reader-core`、`reader-novel` | 已有列表派生、统计、进度、阅读几何和小说算法，应继续消费，避免另建同名业务规则。 |

上述是“部分模块共享”，不作为整页已共享的证明。

## 改造清单

优先级：P0 为支撑后续迁移的前置项；P1 为高频主流程；P2 为后续功能页面；P3 为高风险专项。
体量是相对评估：小/中/大，不是工期承诺。每一行可以分多批完成，不要求一次改完。

| 编号 | 区域与入口 | 共享现状及需改造点 | 推荐共享切口与保留部分 | 优先级/体量 |
| --- | --- | --- | --- | --- |
| 01 | 主题、搜索、对话框 | 字体/形状/玻璃已有共享；`DesktopTheme` 仍自行装配颜色，`DesktopSearchField`、`DesktopSettingsDialogs` 与 Android 设置交互独立。 | 抽取语义颜色、搜索和偏好编辑呈现；平台注入动态色、图标/资源和系统交互。先区分有意的平台差异与意外漂移。 | P0 / 中 |
| 02 | 页面状态与根装配 | `DesktopApp` 直接分发页面，`DesktopAppState` 与 `DesktopController` 集中多个功能域；部分操作类已拆出，但多数页面仍依赖整个控制器。 | 随功能迁移建立小的状态/操作 Interface；先共享纯状态派生和呈现，Android ViewModel、桌面任务与导航继续作 Adapter。 | P0 / 大，贯穿各批 |
| 03 | 收藏、历史：`LIBRARY`、`HISTORY` | 桌面独立实现搜索、筛选抽屉、分类、选中态和批量操作；Android 走 `AppContentListRoute` 与各自 Host。 | 先提取卡片/列表和筛选、选择呈现，再共享书库页面主体；保留仓库订阅、导航和平台分享。 | P1 / 大 |
| 04 | 来源浏览与结果：`EXPLORE` | 来源网格使用共享 `TabletSourceTile`；列表模式、来源侧栏、结果搜索、分页和动态筛选仍由桌面拼装。 | 来源列表与结果页面分别提取；统一加载/空态/失败/分页呈现及筛选操作，源执行与取消留在宿主。 | P1 / 大 |
| 05 | 详情：`DETAILS` | 宽屏外壳、章节和预览已部分共享；信息区、详情书签、关联推荐与页面组合仍独立。 | 先提取详情信息、书签和关联区，再收敛页面主体；封面加载、来源请求、返回栈与窗口留在 Adapter。 | P1 / 大 |
| 06 | 扩展管理：`EXTENSIONS` | 标签栏、仓库卡片已共享；来源、包列表、安装提示和导入表单尚非完整共用页面。 | 在既有改造上继续提取来源/包呈现与导入状态；APK/JAR/CS3、文件选择和 WebView 能力保留宿主实现。 | P1 / 中至大 |
| 07 | 设置入口及常规设置：`MORE`、`SERVICES`、`READER_SETTINGS`、`SOURCE_SETTINGS`、`PLAYBACK_SETTINGS`、`PROXY_SETTINGS`、`DOWNLOAD_SETTINGS`、`NAV_CONFIG` | 大量页面只共用了设置行，页面分组、条件显示、偏好编辑和导航仍有两套实现。 | 用明确的设置状态/回调提取真实页面；先以代理设置作完整页面试点，再迁移来源、阅读、下载与导航配置。 | P1 / 大，逐页做 |
| 08 | 主页：`HOME` | `HomeScreenLayout`、Hero 和部分封面呈现共用；历史、更新、快捷入口和推荐分区的装配仍分别实现。 | 共享分区状态及分区呈现，继续使用现有 Hero；图片管线、数据订阅与点击导航保留 Adapter。 | P2 / 中 |
| 09 | 订阅、更新、推荐：`FEED`、`UPDATED`、`SUGGESTIONS`、`RECOMMENDATIONS` | 时间线卡片、轮播及部分派生规则已共用；筛选、菜单、日期显示、页面容器和列表交互仍独立。 | 在共用派生结果之上收敛页面和工具栏；宿主装配刷新、更新检查、网络结果和导航。 | P2 / 中至大 |
| 10 | 下载：`DOWNLOADS` | 桌面列表、批量工具栏、缓存用量与清理弹窗独立，文件导出直接进入页面；Android 有另一套队列呈现。 | 共享队列条目、分组、选择/批量操作和清理预览；WorkManager、桌面下载执行、文件导出各自保留。 | P2 / 大 |
| 11 | 备份/WebDAV：`BACKUPS` | 格式和部分协议已共享，桌面页面仍直接使用 `JFileChooser` 并独立呈现预览/恢复/远程文件列表。 | 共享配置、备份预览、恢复确认及进度/错误状态；URI/Path、凭据保存、文件选择、定时执行留在 Adapter。 | P2 / 大，高数据风险 |
| 12 | 全局书签：`BOOKMARKS` | 小说书签解析规则共用；分组、搜索、卡片、删除与跳转呈现独立，详情书签也有另一组桌面实现。 | 统一书签 UiModel 和卡片/选择呈现，再接全局页与详情页；图片解码和阅读定位保留接口。 | P2 / 中 |
| 13 | 阅读/作品统计：`READING_STATS`，另含作品统计弹窗 | 规则已有共享基础；Android 使用 `StatsDashboard`，桌面使用 `LibraryReadingStats`，图表和摘要布局各自实现。 | 先核对两个投影的口径，再提取统计看板、时间/类型选择和作品弹窗；不要新增第三套统计计算。 | P2 / 中至大 |
| 14 | 来源偏好：`PREFERENCES` | 桌面已经消费 `SourcePreferenceNode`，但分组、选择/文本对话框与 Android 偏好入口仍分开。 | 先共享协议节点的编辑呈现及校验结果；Android 兼容偏好调用、revision/提交和扩展运行时留在宿主。 | P2 / 中 |
| 15 | 规则与诊断：`TAG_BLACKLIST`、`REPLACE_RULES`、`DICTIONARY_RULES`、`SOURCE_TEST` | 黑名单、替换/字典规则和测试结果使用独立桌面页面；部分业务模型和规则已经共享。 | 提取规则编辑/预览/分组与诊断结果呈现；格式导入导出、来源测试执行、日志采集保留 Adapter。 | P2 / 中至大 |
| 16 | 存储与清理：`STORAGE`、`DATA_CLEANUP` | 桌面状态、路径和操作页面与 Android 独立；系统存储能力存在真实差异。 | 共享容量分类、扫描/预览/执行状态及确认流程；路径、权限、系统设置跳转和实际清理保留平台侧。 | P2 / 中 |
| 17 | 向导、说明与日志：`SETUP_WIZARD`、`CHANGELOG`、`CRASH_LOGS`，另含关于/更新卡片 | 向导和关于组装独立；Markdown 渲染已有共享模块，但宿主接入程度不同；更新存在 Windows 安装能力限制。 | 共享介绍/外观/完成、日志列表与说明呈现；权限步骤、日志打开/分享、升级安装按能力装配。 | P2 / 中 |
| 18 | 漫画阅读：`READER` | 控制栏、目录/选项、点击网格及部分算法已共享；场景装配、输入处理和资源生命周期仍由桌面实现。 | 在既有共享模块上核对动作/状态接线，逐步共享可通用的场景；不重写解码、缓存、窗口或已有相机算法。 | P3 / 大，高回归风险 |
| 19 | 小说阅读：`NOVEL` | 上下栏、目录、选项、书签内容及算法部分共享；正文、分页测量和会话装配仍分别实现。 | 优先统一阅读状态与文本/分页呈现；分清可共用的排版逻辑与平台字体、图片、TTS 的 Adapter。 | P3 / 大，高进度风险 |
| 20 | 视频、浏览器：`VIDEO`、`BROWSER` | Android 的播放器已有 Compose 控制层和原生渲染层；桌面使用独立 mpv 控件与浏览器调试页。 | 先共享播放状态、控制栏、轨道/章节等可通用呈现；Media3/libmpv、WebView、窗口嵌入、硬解留在平台实现。 | P3 / 大，高平台风险 |

## 源码依据与下一步检查入口

以下是已核对的主要入口，不是仅按文件名推测。路径均相对仓库根目录。

| 清单编号 | Android 参考入口 | 桌面入口 | 已有共享入口 |
| --- | --- | --- | --- |
| 01–02 | [SettingsAlertDialog](../../app/src/main/kotlin/org/skepsun/kototoro/settings/compose/SettingsAlertDialog.kt)、[SettingsRootScreen](../../app/src/main/kotlin/org/skepsun/kototoro/settings/compose/SettingsRootScreen.kt) | [DesktopTheme](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopTheme.kt)、[DesktopSettingsDialogs](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopSettingsDialogs.kt)、[DesktopController](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopController.kt)、[DesktopApp](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopApp.kt) | `core-ui/core/ui/theme`、`settings`、`text`、`topbar` |
| 03 | [FavoritesListScreen](../../app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/compose/FavoritesListScreen.kt)、[HistoryScreen](../../app/src/main/kotlin/org/skepsun/kototoro/history/ui/compose/HistoryScreen.kt)、[KototoroContentCard](../../app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/KototoroContentCard.kt)、[FilterSheetRoute](../../app/src/main/kotlin/org/skepsun/kototoro/filter/ui/sheet/FilterSheetRoute.kt) | [DesktopLibraryPanel](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopLibraryPanel.kt)、`DesktopApp/ContentGrid`、`DesktopLibrarySelection`、`DesktopCategoryDialog` | `core-ui/list/ui/compose`、`core-domain` 的书库派生 |
| 04 | [ExploreHostScreen](../../app/src/main/kotlin/org/skepsun/kototoro/explore/ui/compose/ExploreHostScreen.kt)、[ExploreSourcesScreen](../../app/src/main/kotlin/org/skepsun/kototoro/explore/ui/compose/ExploreSourcesScreen.kt)、[SearchResultsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/search/ui/compose/SearchResultsScreen.kt) | [DesktopSourceGrid](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopSourceGrid.kt)、`DesktopApp/Browse/SourceResults`、`DesktopFilters`、`DesktopSourceFilters` | `core-ui/core/ui/source/TabletSourceTile`、`chips/ChoiceChips` |
| 05 | [DetailsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/compose/DetailsScreen.kt)、[DetailsHeaderInfoPanel](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/compose/DetailsHeaderInfoPanel.kt) | [DesktopDetailsInfo](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopDetailsInfo.kt)、`DesktopDetailsBookmarks`、`DesktopDetailsRelated`、`DesktopTabletChrome` | `core-ui/core/ui/chapters`、`adaptive/TabletDetailsPanes`、`preview` |
| 06 | [UnifiedSourcesScreen](../../app/src/main/kotlin/org/skepsun/kototoro/settings/sources/unified/UnifiedSourcesScreen.kt) | [DesktopExtensionsPanel](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopExtensionsPanel.kt)、`DesktopExtensionImport`、`DesktopExtensionRepositories` | `core-ui/core/ui/extensions` |
| 07 | [ProxySettingsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/settings/compose/ProxySettingsScreen.kt)、`settings/compose` 下各设置页 | [DesktopProxySettingsPage](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopProxySettingsPage.kt)、`DesktopReaderSettingsPage`、`DesktopSourceSettingsPage`、`DesktopNavigationConfigPage` | `core-ui/core/ui/settings` |
| 08–09 | [HomeScreen](../../app/src/main/kotlin/org/skepsun/kototoro/home/ui/compose/HomeScreen.kt)、[FeedScreen](../../app/src/main/kotlin/org/skepsun/kototoro/tracker/ui/feed/compose/FeedScreen.kt)、[DiscoverScreen](../../app/src/main/kotlin/org/skepsun/kototoro/discover/ui/compose/DiscoverScreen.kt) | [DesktopHomePanel](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopHomePanel.kt)、`DesktopFeedPanel`、`DesktopUpdatesPage`、`DesktopSuggestionsPage`、`DesktopRecommendations` | `core-ui/core/ui/home`、`feed`；`core-domain` 的 Feed/Updates 派生 |
| 10 | [DownloadsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/download/ui/compose/DownloadsScreen.kt) | [DesktopDownloadsPanel](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopDownloadsPanel.kt)、`DesktopDownloadOptions`、`DesktopDownloadCleanup` | 通用卡片、设置行；队列执行仍分别装配 |
| 11 | [BackupsSettingsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/settings/compose/BackupsSettingsScreen.kt) | [DesktopBackupsPanel](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopBackupsPanel.kt)、[DesktopWebDavPanel](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopWebDavPanel.kt) | `core-backup`、`core-net` 已有备份/协议实现 |
| 12–13 | [AppBookmarksRoute](../../app/src/main/kotlin/org/skepsun/kototoro/bookmarks/ui/compose/AppBookmarksRoute.kt)、[StatsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/stats/ui/StatsScreen.kt)、[ContentStatsDialog](../../app/src/main/kotlin/org/skepsun/kototoro/stats/ui/sheet/compose/ContentStatsDialog.kt) | [DesktopBookmarksPage](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopBookmarksPage.kt)、[DesktopReadingStatsPage](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopReadingStatsPage.kt)、`DesktopContentStatsDialog` | 小说书签解析、`core-library` 统计与 `core-domain` 统计规则 |
| 14–17 | [SourceSettingsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/settings/compose/SourceSettingsScreen.kt)、[ReplaceRulesSettingsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/settings/compose/ReplaceRulesSettingsScreen.kt)、[DataCleanupSettingsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/settings/compose/DataCleanupSettingsScreen.kt)、[WelcomeSheet](../../app/src/main/kotlin/org/skepsun/kototoro/main/ui/welcome/WelcomeSheet.kt) | [DesktopSourcePreferences](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopSourcePreferences.kt)、`DesktopReplaceRulesPage`、`DesktopDictionaryRulesPage`、`DesktopSourceTestPage`、`DesktopStorageSettingsPage`、`DesktopDataCleanupPage`、`DesktopSetupWizard`、`DesktopChangelogPage`、`DesktopCrashLogsPage` | `SourcePreferenceNode`、共享规则、`core-ui/core/ui/markdown`；来源偏好还需逐类核对 Android 的兼容设置入口 |
| 18–19 | [ComposeReaderOptionsSheet](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderOptionsSheet.kt)、[NovelReaderActivity](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelReaderActivity.kt) | [DesktopReaderChrome](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopReaderChrome.kt)、`DesktopReaderOptionsPanel`、`DesktopReader`、`DesktopNovelReader`、`DesktopNovelReaderChrome` | `core-ui/reader/**`、`reader-core`、`reader-novel` |
| 20 | [VideoPlayerActivity](../../app/src/main/kotlin/org/skepsun/kototoro/video/ui/VideoPlayerActivity.kt)、[VideoPlayerControls](../../app/src/main/kotlin/org/skepsun/kototoro/video/ui/compose/VideoPlayerControls.kt) | [DesktopVideoPlayer](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopVideoPlayer.kt)、`BrowserPanel`、`DesktopBrowser` | 现有播放器/浏览器 Adapter；共享 UI 切口仍需在专项中细化 |

## 分批实施

### 第一批：基础交互与一个完整页面试点

范围：01、02 的最小必要部分、07 中的代理设置。

1. 对照 Android/桌面的搜索、单选、多选、文本编辑行为，提取所需共享呈现，保留现有文案层。
2. 为代理设置定义小的状态/操作 Interface；共享页面不直接依赖 `AppSettings` 或 `DesktopController`。
3. Android 先接回，再由桌面 Adapter 接入；连接测试、偏好持久化仍调用各端既有实现。
4. 用一个真实页面验证能力注入、错误呈现、输入与返回，不先建立全应用“万能设置框架”。

完成条件：两端实际调用同一代理设置主体；类型切换、地址/端口/凭据编辑、测试连接的禁用条件与
结果显示保持正确；新呈现没有直接平台依赖。保留必要的平台样式差异并记录依据。

#### 第一批完成记录（2026-10-26）

| 共享入口（`core-ui/core/ui/settings`） | Android 调用方 | 桌面调用方 |
| --- | --- | --- |
| `SettingsSearchField` | `SettingsTopBarScaffold/SettingsCompactSearchField` | `DesktopTheme/DesktopSearchField` |
| `PreferenceChoiceDialog`、`PreferenceTextDialog` | `ProxySettingsScreen` 经共享代理页调用 | `DesktopProxySettingsPage` 经共享代理页调用 |
| `ProxySettingsContent`、`ProxySettingsUiState` | `ProxySettingsScreen` | `DesktopProxySettingsPage` |
| `ProxyTestResultDialog` | `SettingsActivity` 的 `ProxyTestResult` 分支 | `DesktopProxySettingsPage` |

- 两端已实际调用相同的完整代理表单、字段编辑和测试结果呈现。共享入口只接收功能状态与操作回调；
  原偏好键、持久化、网络客户端、测试任务及返回导航由各端 Adapter 保留。
- Android 手机/平板保留直接输入；TV 与桌面使用弹窗草稿，取消不保存。
  TV 保留只读起始、清空和输入完成后的焦点交还；Android 的玻璃/窗口处理通过
  `AndroidPreferenceDialog` 包裹共享弹窗内容，桌面搜索保留自己的表面样式和高度。
- Android 继续允许编辑中的不完整端口，并通过配置有效性禁用测试；桌面继续在保存时拒绝非法端口，
  此时保留草稿并显示错误。有效性由实际执行连接的宿主提供，避免 UI 另建一套网络规则。
- `SharedSettingsUiTest` 验证了 380/760 宽度下取消、非法端口修正、密码遮罩/保存、运行中禁用，
  直接输入模式下的未完成端口，以及搜索光标、宿主回显、外部清空和占位文案。
- 已通过 Android Kotlin 编译、桌面 Kotlin 编译、两份文案校验，以及上述共享 UI 测试、
  `DesktopHomeMouseGesturesTest` 和现有代理集成 probe。代理 probe 使用本地 HTTP 服务验证真实客户端、
  连接测试、来源导入及切回直连，未访问生产代理。窄窗口错误态截图已检查，位于
  `desktop-app/build/reports/desktop-smoke/shared-proxy-rejected-{380,760}.png`（生成物不入库）。
- 验证范围：macOS 上的 JVM UI/集成测试及 Android 编译；Android 实机/模拟器和 Windows 实机/CI
  尚未运行。本批完成不表示其余设置页、多选弹窗、全应用主题和状态装配已经迁移。

### 第二批：主列表与详情

范围及依赖顺序：03 的内容卡片/列表 → 收藏与历史 → 04 浏览结果 → 05 详情；06 按独立功能片段继续收敛。

- 先梳理 Android `ContentListModel`/卡片 render model 与桌面 `SourceContent` 的投影，复用已有共享 DTO
  能表达的字段；确需呈现模型时再新增，不调整扩展 ABI 或数据库模型来迁就 UI。
- 共享稳定 ID、封面/角标/进度、搜索筛选、选择模式、空态/错误/分页；图像加载通过 slot 或小 Interface 注入。
- 分类管理、批量操作、换源和导出只在本次涉及范围接入；操作执行继续消费已有规则与仓库。
- 详情章节/预览已经共享的部分保留，重点迁移信息、书签、关联区与组合方式。

完成条件：收藏/历史和浏览使用共用列表呈现；详情新增共享区由两端消费；翻页、选中项裁剪、
分类/筛选变化、返回滚动位置与封面加载均有回归证据。扩展管理剩余部分按实际共享范围单独验收。

#### 第二批阶段记录：卡片呈现（2026-10-26）

- 新增共享 [SharedContentCards](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/list/ui/compose/SharedContentCards.kt)：
  `ContentPosterCard` 提取 Android 网格卡片的标题、附加信息、选中遮罩/标记、高亮及点击/长按；
  `ContentCompactCard` 提取 Android 紧凑列表行的封面与文字排版、分隔线和同一套交互/选择语义。
  `ContentCardPresentation` 仅携带稳定 ID 与已投影的文字，不引入 parser、Android 资源或追踪服务依赖。
- Android 的 `KototoroContentCardGrid`、`KototoroContentCardList` 已接回共享入口；收藏、历史及其他原卡片
  调用方直接获得改动。桌面 `DesktopApp/ContentGrid`、`ContentRowList` 调用相同入口，覆盖收藏、历史、浏览
  及其他使用该列表的页面。桌面紧凑行采用 Android 的 48×72 封面比例和文字层级，移除独立的卡片排版。
- 图片请求/解码、Android 封面快照与共享转场、TV 焦点请求仍由 Adapter 注入；角标与进度的宿主数据装配
  继续使用已有共享绘制。未改数据库、偏好键、来源身份或扩展 ABI，也未增加依赖。
- 回归发现并修复第一批共享搜索框的状态同步问题：收藏/历史切换时，从空输入恢复已保存查询不应被当作
  延迟回显而忽略。现在只识别实际发出过的待回显文本，接收外部查询替换，同时保留光标与较新的本地输入。
  仅移动光标不再重复提交查询。`SharedSettingsUiTest` 补充了空值恢复、前缀替换和延迟回显回归。
- 验证通过：Android/桌面 Kotlin 编译、共享/桌面文案校验；Android 的 `ContentCardRenderModelTest` 与
  `ContentGridLayoutTest` 共 11 项测试；桌面共享卡片/搜索、书库筛选、自动分页及现有宽窄窗口端到端
  probe 共 18 项测试。包含点击、长按、选择/取消、禁用、横竖封面尺寸及转场坐标传递、收藏/历史查询恢复、
  列表模式、批量操作与阅读返回。380/760 宽度的明暗主题行截图、真实桌面收藏列表截图已检查，生成物位于
  `desktop-app/build/reports/desktop-smoke/`。
- 本阶段范围是**卡片共享**，当时尚未完成整个第二批：Android 详细列表、两端列表容器/分页装配、收藏/历史页头、
  筛选与操作面板、浏览结果及详情外围仍需后续收敛。下一步先处理列表容器和收藏/历史的页面装配，
  再进入浏览结果与详情。
- 实测平台：macOS JVM UI/集成测试；Android 编译与 JVM 单元测试。Android 实机/模拟器、Windows 实机/CI
  尚未运行，不能据此宣称平台行为已全部验收。

#### 第二批阶段记录：列表容器、空态与滚动恢复（2026-10-26）

- 新增共享 [ContentCollections](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/list/ui/compose/ContentCollections.kt)：
  `ContentCollectionGrid` / `ContentCollectionColumn` 统一索引式条目、稳定 key、content type、全宽补充项、
  页头/页尾及接近底部加载。触发同一条目数量时仅请求一次，追加条目或显式重新启用加载后可以继续请求。
  Paging 快照读取、错误/重试状态和后台加载仍由原宿主驱动。
- Android 的 `KototoroContentListScreen` 已接回共享网格、紧凑/详细列表容器；原来的滚动状态、TV 焦点组、
  转场准备、补充条目、刷新及滚动条装配保留。桌面 `ContentGrid` / `ContentRowList` 使用相同容器，
  移除独立的页尾加载触发实现。
- `ContentCollectionState` 将网格/列表的两个滚动位置分开保存。桌面 `DesktopApp` 在页面路由之上分别
  持有收藏和历史的状态，`DesktopLibraryPanel` 只接收对应实例；打开阅读器、离开书库或切换显示模式时，
  已有位置不随列表离开组合而丢失。Android 继续注入原有的 retained/saveable 状态，不更换状态持有者。
- 新增共享 [ContentCollectionEmpty](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/list/ui/compose/ContentCollectionEmpty.kt)，
  由 Android `EmptyStateCard` 与桌面收藏/历史空态实际调用；文字、图标和清除筛选操作由宿主提供。
  未改来源身份、数据库、偏好键或批量操作执行，也未增加依赖。
- `ContentCollectionsTest` 覆盖网格/列表分页去重、追加和重试、全宽页头/补充项、重排后的条目状态，以及
  收藏/历史、网格/列表各自的位置恢复。现有真实桌面 probe 增加多条收藏，验证滚动到第 30 项后离开页面、
  返回仍保持同一偏移；共享空态的清除筛选操作也在原端到端流程中回归。
- 本阶段仍是共享列表呈现和部分书库装配；收藏/历史页头、筛选与选择操作面板、Android 详细卡片，
  以及浏览和详情外围尚未全部收敛。下一步处理书库页头/筛选/选择装配，再推进浏览结果与详情。
- 验证结果：Android/桌面 Kotlin 编译、共享/桌面文案校验及 8 项桌面 UI/集成测试通过；真实书库恢复测试
  覆盖 1260、920、1000、999 宽度，截图 `tablet*-library-restored-scroll.png` 已生成并检查窄窗口版本。
  验证在 macOS 上完成，Android 实机/模拟器与 Windows 实机/CI 未运行。
- 补充 Android `ContentListItemDescriptorTest` / `RetainedPagingSnapshotPolicyTest` 共 8 项测试通过。
  磁盘空间不足的阻塞已解除：经用户明确确认，仅清理 Android 的 `merged_native_libs`、
  `stripped_native_libs` 中间产物与旧 `Kototoro-1.0.0.dmg`，随后补测及 macOS
  `:desktop-app:createDistributable` 均成功。新版应用位于
  `desktop-app/build/compose/binaries/main/app/Kototoro.app`；旧 DMG 未重新生成。

#### 第二批阶段记录：自适应选择操作栏（2026-10-26）

- 新增共享 [ContentSelectionToolbar](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/list/ui/compose/ContentSelectionToolbar.kt)，
  提取 Android 的关闭/计数/操作组结构。可用宽度决定直接显示的常用操作，其他操作进入共享菜单；
  不再让桌面把所有批量操作文字按钮挤在一行。选择动作枚举保留原包名和成员顺序，定义迁入 `commonMain`。
- Android `KototoroSelectionTopBar` 与桌面 `DesktopLibrarySelectionBar` 实际调用同一布局、按钮、
  更多菜单内容与动作分发。宿主提供文案、图标、可用操作、禁用条件和事件；Android 原有上下文操作策略、
  系统栏 padding、玻璃/root-overlay 菜单、TV 锚点和焦点恢复通过 Adapter 保留。
- 桌面沿用 Android 收藏栏的固定/移除/下载常用操作，其余分类、全选、标记完成、分享、迁移进入更多菜单。
  历史页仍只提供原先支持的动作；多项选择时迁移仍禁用，取消选择保持可用。固定按钮的无障碍标签随状态
  更新为取消置顶，菜单操作执行后自动关闭。确认弹窗与批量操作执行不在本阶段迁移。
- 验证通过：Android/桌面 Kotlin 编译、两份文案校验、2 项共享选择栏 UI 测试，以及 1 项真实桌面端到端测试
  （内部覆盖 1260/920/1000/999 宽度）。独立测试覆盖 280/380/900 宽度、明暗主题、全部动作可达性、
  单/多项选择下的迁移禁用、全局禁用、取消与未支持操作缺席。端到端回归覆盖分类、移除确认、标记完成、
  固定/取消固定、分享、下载、迁移及历史批量操作；已检查窄窗口选择栏与真实桌面选择态截图。
- macOS `.app` 已重新打包。Android 实机/模拟器、Windows 实机/CI 尚未运行。
  此阶段完成的是**选择栏共享**；普通页头、筛选面板及其余页面装配仍需继续收敛，不代表完整书库页面已共享。

#### 第二批阶段记录：确认主体与分类选择列表（2026-10-26）

- 新增共享 [ContentActionConfirmationDialog](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/list/ui/compose/ContentActionConfirmationDialog.kt)，
  提取 Android 的标题、进度覆盖警告、确认/取消呈现，复用已有 `PreferenceDialogContent` 外壳接口。
  Android `AppContentListRoute` 和 `MainShellScene` 的标记完成提示，以及桌面标记完成/移除提示实际调用
  同一确认主体。宿主继续持有待处理条目并执行操作；文案、选择数量、禁用条件和取消生命周期仍由宿主提供。
  共享按钮使用既有 TV 焦点支持，Android 系统“确定/取消”文案保持原资源。
- 新增共享 [CategoryChoicesContent](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/list/ui/compose/CategoryChoicesContent.kt)，
  提取 Android 分类列表的三态勾选、跟踪/隐藏标记、加载/空态及显示全部分类入口。
  Android `FavoriteCategoryDialog` 的两个入口及桌面 `DesktopCategoryPickerDialog` 均消费相同列表。
  分类 ID 作为稳定 key，行提供 Checkbox 三态语义与 TV 焦点提示；图标继续由 Android 原向量生成。
- 保留实际交互差异：Android 分类勾选即时生效，管理/完成按钮与加载状态由原宿主管理；桌面保留弹窗私有
  草稿，取消不保存、确认后提交。桌面原先对多作品的初始集合/批量写入语义保持原样，不以 UI 迁移调整
  数据规则。Android 原有直接移除流程未新增确认；共享确认组件并不代表所有操作策略已经统一。
- 验证通过：Android/桌面 Kotlin 编译、两份文案校验、3 项独立弹窗/分类 UI 测试，以及 1 项真实桌面端到端
  测试（内部覆盖 1260/920/1000/999 宽度）。测试包含确认禁用和取消不执行、警告可见、混合状态切换、
  跟踪/隐藏标记模型、加载/空态、显示全部分类，以及 380/760 窗口中 50 项分类列表的滚动、草稿取消与保存。
  原真实书库的分类、移除和标记完成流程均回归；已检查滚动后的分类列表和真实进度覆盖提示截图。
- 本阶段完成的是共享确认主体与分类列表；分类弹窗的管理/保存外壳、下载范围弹窗、普通页头和筛选面板
  尚未全部收敛。Android 实机/模拟器与 Windows 实机/CI 尚未运行；实测范围为 macOS JVM UI/集成测试
  及 Android 编译。macOS `.app` 已重新打包并通过签名完整性校验。

#### 第二批阶段记录：书库筛选面板（2026-10-26）

- 新增共享 [FilterPanel](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/core/ui/compose/FilterPanel.kt)，
  提取 Android 收藏筛选面板的呈现词汇：`FilterPanelGroup`（由 app 迁入，包名不变）、`FilterPanelHeader`、
  `FilterPanelSwitchRow`、`FilterPanelReset`、`CompactFilterChip(Flow)`、`FilterFacetChip` 与多选
  `FilterFacetDropdown`。只接收文字、选中状态、图标 slot 与回调，不依赖列表筛选模型、资源或设置。
- Android `FavoritesFilterPanelContent` 的面板头、开关行、快速筛选芯片、分面下拉（标签/来源组）、来源类型锚点
  芯片及重置实际调用共享实现；`SearchFilterSheet` 继续使用迁入的 `FilterPanelGroup`。Android 资源、
  `ListFilterOption` 映射、菜单底色（`popupMenuContainerColor`）和单选的来源类型菜单保留在宿主。
- 桌面 `DesktopLibraryPanel` 的筛选抽屉由单选/复选行改为同一套分组卡与紧凑芯片：排序、布局、阅读进度、
  下载为单选芯片（带 RadioButton 角色），出版状态与快速筛选为多选芯片，来源改为 Android 同款分面下拉
  （菜单在多选时保持打开、可单独重置），标签沿用可搜索的选择对话框但入口换成分面芯片。
  “完成/重置筛选/`filter_group_selected_count`”直接取 Android 文案；桌面新增导出 `ic_expand_more`、`ic_tag`。
  筛选语义、`DesktopLibrarySelection` 与偏好键未改。
- 仍未收敛：`FilterSheetRoute`、`UnifiedSourcesScreenBody` 各自的紧凑芯片副本，Android 排序/布局所在的
  `DisplayOptionsSheet`，以及书库普通页头。
- 验证通过：Android/桌面 Kotlin 编译、两份文案校验；新增 `SharedFilterPanelTest` 2 项（下拉多选保持打开、
  无选中时重置禁用、点击外部关闭、禁用态；芯片/开关/完成/重置回调），并回归 `ContentActionDialogsTest`、
  `SharedSettingsUiTest`、`DesktopLibrarySelectionTest` 与真实书库端到端探针（1260/920/1000/999 宽度，
  新增来源下拉选择、单组重置与外部点击关闭）。已检查宽/窄窗口抽屉及来源菜单截图
  `tablet*-library-filter*.png`。macOS `.app` 已重新打包并通过签名校验；Android 实机/模拟器、Windows 实机/CI 尚未运行。

#### 第二批阶段记录：页头操作胶囊（2026-10-26）

- Android 主壳顶栏的紧凑菜单行 `CompactDropdownMenuItem/Text/Divider` 从 `GlassDropdownMenu.kt` 迁入
  [core-ui](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/main/ui/compose/CompactDropdownMenu.kt)，
  包名不变，Android 现有 12 个调用文件无需改动；玻璃弹层 `GlassDropdownMenu` 仍留在 Android。
- 新增共享 [TopBarActions](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/core/ui/topbar/TopBarActions.kt)：
  `TopBarIconAction`（胶囊内图标按钮，激活着色、更新红点、TV 焦点环）与 `SourceTypeMenuItems`
  （“全部”复选行 + 来源类型行，选中着色与尾部对勾）。图标/文案由宿主给，`SourceTag` 仍在 core-domain，core-ui
  只接收通用条目。
- Android `SourceTagDropdown` 的按钮与菜单行、`KototoroTopBar` 的“更多”按钮实际调用共享实现；
  胶囊表面、玻璃菜单、`anchorTapThrough` 与 TV 向下焦点链接仍由 Android 宿主装配。桌面
  `DesktopSourceFilterControls`（主页/订阅/更新/浏览/收藏/历史共用）的来源类型按钮与菜单改用同一组件，
  并新增 `actions` 槽：书库页头把原来三颗文字按钮收进同一胶囊——“筛选与排序”成为 `ic_sort` 图标
  （有筛选时着色，无障碍标签带数量），“刷新/分类管理”进入“更多”菜单，与 Android 页头结构一致。
- 保留差异：桌面搜索框仍是就地过滤书库的输入框（Android 的搜索胶囊跳转全局搜索）；内容类型选择仍各自实现
  （Android 为可滑动芯片）；桌面筛选面板仍为侧边抽屉而非 Android 收藏页挂在来源类型按钮下的弹层。
- 验证通过：Android/桌面 Kotlin 编译、两份文案校验；新增 `SharedTopBarActionsTest` 2 项，回归
  `DesktopSourceFilterTest` 3 项、`DesktopLibrarySelectionTest` 10 项、`SharedFilterPanelTest`，以及书库端到端
  探针（4 种宽度，新增“更多”菜单与筛选数量标签断言）。已检查宽/窄窗口书库页头截图；macOS `.app` 已重新打包并通过签名校验。
- 完整 `DesktopAppTest` 首轮在 macOS 上有 5 项失败，均非本阶段改动引入，已全部处理：
  - 来源测试/来源排序探针仍点击扩展管理改版前的 `json-source-add`：改走统一添加入口
    （`extension-add` → `extension-add-tvbox`），“测试来源”行与卸载按钮需先切到“扩展包”标签（`extensions-tab:2`）。
  - 更新日志探针依赖 Windows 包更新器：非 Windows 平台以 JUnit assumption 跳过，与关于卡片的平台判断一致。
  - 存储探针对第一批移出滚动区的代理返回按钮调用 `performScrollTo`：改为直接点击。
  - 插件探针的分页小说：确认为真实缺陷——从滚动模式切到分页时 `DesktopNovelPagerState.jumpToBlock` 把锚点设成
    被阅读的段落而非页首，之后改字号重排会按该段落重新分页，原页上方的段落消失。现按 `anchorBlock` 的既有约定
    取页首，并在 `DesktopNovelPaginationTest` 增加回归。
  修复后完整 `:desktop-app:test` 在 macOS 上 330 项通过、7 项按条件跳过、0 失败。
  Android 实机/模拟器、Windows 实机/CI 尚未运行。

#### 第二批阶段记录：来源结果页头（2026-10-26）

- 新增共享 [SourceResultsTopBar](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/core/ui/topbar/SourceResultsTopBar.kt)，
  提取 Android `SourceListTopActionsRow`：返回按钮、来源标题与操作胶囊。操作以 `TopBarActionItem` 描述，
  按 `minWidth` 随栏宽折入“更多”，`overflowOnly` 只进菜单，带 `menu` 的按钮从自身打开菜单；胶囊表面
  （`TopBarCapsuleSurface`）与弹层（`TopBarMenuHost`，带锚点矩形和焦点请求器）由宿主注入。
  `TopBarIconAction` 增加焦点环形状参数。
- Android `SourceListTopActionsRow` 改为调用共享顶栏，筛选/搜索/随机/显示选项/设置及只在溢出菜单出现的“重置筛选”
  沿用原来的 420/476/532dp 断点、徽标、空间切换器与 TV 焦点组，弹层仍为 `GlassDropdownMenu`；
  私有 `MoreActionsButton` 删除。可见差异：溢出菜单里“显示选项”现排在“设置”之前，与胶囊顺序一致。
- 桌面结果页原先的返回行 + “热门/最新/排序/筛选/源设置”五颗文字按钮，改为同一顶栏：排序菜单（热门、最新、
  插件声明的全部排序，当前排序带对勾）、动态筛选（已应用时带徽标）、源设置；Material 菜单作弹层。
  仍保留：桌面常驻搜索框与“搜索”按钮（Android 由搜索图标切换输入行）、上一批/下一批分页行（§199 的手动入口）、
  Android 的随机与显示选项（桌面结果页暂无对应能力）。
- 验证通过：Android/桌面 Kotlin 编译、两份文案校验；新增 `SharedSourceResultsTopBarTest` 2 项（宽栏全部直出、
  排序菜单；窄栏折叠与仅溢出项）；筛选、主流程探针改为从排序菜单选择热门/最新、以 `source-settings` 打开源设置。
  完整 `:desktop-app:test` 在 macOS 上 332 项通过、7 项跳过、0 失败。已检查宽/窄窗口结果页截图。
  Android 实机/模拟器、Windows 实机/CI 尚未运行。

#### 第二批阶段记录：列表加载与出错状态（2026-10-26）

- 新增共享 [ContentCollectionStates](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/list/ui/compose/ContentCollectionStates.kt)：
  `ContentCollectionError`（图标、标题、原因、重试与可选次要操作）、`ContentGridLoadingSkeleton`
  （按列数与封面比例的一行骨架，可带标题线）、`ContentListLoadingSkeleton` 与 `ContentSkeletonBlock`。
  空态继续使用此前共享的 `ContentCollectionEmpty`。
- Android `KototoroContentListScreen` 的 `ErrorStateCard` 与网格/列表 `LoadingStateItem` 改为调用共享实现；
  列数仍由 Android 的 `resolveGridColumnCount` 与卡片样式算出，错误图标、文案、`ExceptionResolver` 重试与
  “在浏览器中打开”次要操作由 Android 宿主提供。
- 桌面来源结果区原先在首页加载中也显示“没有作品”，出错只在全局通知条提示。现在首批加载中显示同列宽的
  骨架（`browse-loading`），失败显示 Android 同款出错卡片（`browse-error`，`ic_error_large` 与
  `error_occurred`/`retry` 原文案），重试重新请求当前批次；有结果后行为不变。
- 已知但未改：桌面顶部全局通知条仍会同时显示同一错误；TVBox/苹果 CMS 运行时在接口探测阶段所有候选地址都失败时
  报 `SourceOperationUnsupportedException`（“Unsupported source operation”），站点不可达时文案有误导。
  后者属于共享来源运行时语义，需单独评估对 Android/iOS 的影响。
- 验证：Android/桌面 Kotlin 编译；JSON 来源端到端探针（宽/窄窗口）新增本地 CMS 列表 500 → 出错卡片 → 挂起请求
  看到骨架 → 放行后正常浏览的流程，并检查了出错与加载截图。完整 `:desktop-app:test` 在 macOS 上 332 项通过、
  7 项跳过、0 失败；macOS `.app` 已重新打包。Android 实机/模拟器、Windows 实机/CI 尚未运行。

#### 第二批阶段记录：来源列表行（2026-10-26）

- 新增共享 [SourceListRow](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/core/ui/source/SourceListRow.kt)，
  提取 Android `KototoroSourceCard` 的列表形态：48dp 图标框、加粗名称与单行摘要、选中时的 secondaryContainer、
  Expressive 的圆角与内边距。图标、可用性徽标与点击由宿主提供。
- Android `KototoroSourceCard(isGrid = false)` 改为调用共享行（`ContentSourceIcon` 与“空源”徽标仍在 Android）。
- 桌面有两份独立的来源行：浏览页左侧栏的 `SourceRailTile`（只有两行文字、无图标）与来源总览列表模式的自绘行。
  两者都改为 `DesktopSourceRow` → `SourceListRow`，带上已有的 favicon/首字母图标，侧栏选中的来源使用选中底色；
  测试标签 `source:<name>` 不变，行间距按 Android 去掉额外的 8dp。侧栏其余部分（导入 JAR、来源搜索、来源过滤胶囊）未动。
- 验证：Android/桌面 Kotlin 编译；完整 `:desktop-app:test` 在 macOS 上 332 项通过、7 项跳过、0 失败；
  已检查侧栏（宽/窄、深浅色）与来源总览列表模式截图；macOS `.app` 已重新打包。
  Android 实机/模拟器、Windows 实机/CI 尚未运行。

#### 第二批阶段记录：详情相关作品区与标签芯片（2026-10-26）

- 新增共享 [ContentRailSection](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/list/ui/compose/ContentRailSection.kt)
  （标题 + 可选标题尾部 + 横向卡片轨，页面内边距作为轨道内容边距，TV 下为一个焦点组）与
  [DetailsTagChips](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/core/ui/compose/DetailsTagChips.kt)
  （Android 详情页 8dp 圆角标签，敏感标签琥珀底与边框；不传点击时只作标签显示）。
- Android `DetailsRelatedContentSection` 与 `DetailsHeader` 的标签流改为调用共享实现；卡片仍是 `KototoroContentCard`，
  敏感判定仍是成人关键词，标签点击仍打开搜索。
- 桌面相关作品区原先是自画的封面 + 标题 + 来源名，现改为共享分区 + 共享 `ContentPosterCard`
  （`compactPosterRailCardStyle`，与 Android 一样不再单列来源名），外层仍是页面的卡片表面，加载中的小转圈放在标题尾部。
  桌面信息区的标签改为共享芯片，警示判定仍是“高亮敏感类别”的 `TagWarnings`，`details-tag:`/`DesktopWarnedTagKey`
  语义保留；桌面暂无标签点击行为，芯片不可点击。
- 仍未收敛：详情信息行（Android `MetadataItem` 对桌面 `InfoRow`）、详情书签、描述展开/补充元数据切换与详情页整体组合。
- 验证：Android/桌面 Kotlin 编译、两份文案校验；完整 `:desktop-app:test` 在 macOS 上 332 项通过、7 项跳过、0 失败
  （覆盖相关作品探针与 `DesktopTagWarningsUiTest`）；已检查相关作品与标签截图；macOS `.app` 已重新打包。
  Android 实机/模拟器、Windows 实机/CI 尚未运行。

#### 第二批阶段记录：详情书签卡片（2026-10-26）

- 新增共享 [DetailsBookmarkCard](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/core/ui/compose/DetailsBookmarkCard.kt)，
  提取 Android `BookmarksScreen` 的私有书签卡：0.7 比例卡，页面图片 + “P.n” 角标或小说的章节行 + 摘录，
  阅读进度环，选中时 4dp 主色边框、主色蒙层与选中图标。图片、选中图标、容器色与阴影由宿主提供。
- Android 书签页签改为调用共享卡片（Coil 图片、快照文件解析、artwork 底色与 CheckCircle 图标保留在 Android）。
- 桌面详情书签原先分成两套：页面书签的缩略图网格（卡片带章节标题、页码与每卡“删除”）和小说书签的纵向列表。
  现统一为一个 `GridCells.Adaptive(150.dp)` 网格的共享卡片，交互改为 Android 的：点击打开位置、长按进入选择、
  选择中点击切换；删除走已有的选择栏（全选/清除/删除所选），每卡“删除”按钮与未用文案 `details.bookmarkDelete` 移除。
  缩略图仍走阅读器的图片管线与解码预算；`details-bookmark:`、`bookmark-thumb-image:` 等测试标签保留。
- 未改：详情信息行。Android `MetadataItem` 是“图标 + 值”并依赖 Android 图标映射，桌面 `InfoRow` 是“标签：值”；
  对齐需要先为状态/评分/别名等建立共享图标映射，另行评估。全局书签页（`DesktopBookmarksPage`）本次未动。
- 验证：Android/桌面 Kotlin 编译、两份文案校验；插件探针改为长按选择后从选择栏删除小说书签，网格同时覆盖页面与小说；
  完整 `:desktop-app:test` 在 macOS 上 332 项通过、7 项跳过、0 失败；已检查选择态与小说书签截图；
  macOS `.app` 已重新打包。Android 实机/模拟器、Windows 实机/CI 尚未运行。

#### 第二批阶段记录：详情信息网格（2026-10-26）

- 新增共享 [DetailsMetadata](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/core/ui/compose/DetailsMetadata.kt)：
  `DetailsMetadataItem`（Android `MetadataItem`：15dp 图标携带标签的无障碍名称、单行值、弱化值、可点击时的焦点环与导航箭头）
  与 `DetailsMetadataGrid`（两列成对排布，单个末项保留半宽）。图标为宿主给的 `Painter`。
- Android `MetadataItem` 保留原签名、委托共享实现；`DetailsHeader` 的信息卡网格改为 `DetailsMetadataGrid`。
- 桌面信息卡原来是“标签：值”的纵向行，现改为同一网格：状态（按 Android `ContentState.iconResId` 的图标；
  UPCOMING 的时钟来自 Material 库，桌面以 `ic_timer` 代替）、评分 `ic_star_small`、预计剩余 `ic_timer`、别名 `ic_info_outline`。
  桌面构建新增导出 6 个 Android 矢量图标；`details-status` 等测试标签与文本断言保留（行合并语义）。
- 第二批剩余：详情页整体组合（Android 是可折叠头部 + 分页标签，桌面是左右分栏卡片），以及描述展开/补充元数据切换。
- 验证：Android/桌面 Kotlin 编译、两份文案校验；完整 `:desktop-app:test` 在 macOS 上 332 项通过、7 项跳过、0 失败；
  已检查信息卡截图；macOS `.app` 已重新打包。Android 实机/模拟器、Windows 实机/CI 尚未运行。

#### 第二批阶段记录：详情简介展开（2026-10-26）

- 新增共享 [ExpandableDescriptionText](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/core/ui/compose/ExpandableDescriptionText.kt)，
  提取 Android `DetailsHeader` 的简介：折叠为 3 行并让末尾渐隐，点击正文切换全文，仅在确有溢出时成为按钮。
  相对 Android 原实现的唯一差异：不溢出的简介不再挂“已禁用按钮”语义（原为 `clickable(enabled = false)`），
  读屏不会把短简介念成禁用按钮。
- Android `DetailsHeader` 改为调用共享组件（选择容器、TV 焦点处理、展开偏好仍在 Android）。
  桌面详情的“简介”卡原先全文常显，现在与 Android 一样默认折叠（桌面无对应偏好，固定从折叠开始）。
- 验证：Android 编译、两份文案校验；新增 `SharedDetailsComponentsTest` 2 项（长简介点击展开/收起、短简介无点击动作；
  信息网格向读屏给出字段名）；完整 `:desktop-app:test` 在 macOS 上 334 项通过、7 项跳过、0 失败；macOS `.app` 已重新打包。
  Android 实机/模拟器、Windows 实机/CI 尚未运行。

#### 第二批暂缓项：详情页整体组合

Android 详情是可折叠头部 + 章节/页面/书签分页 + 底部 Dock，桌面是左右分栏卡片（左侧信息、右侧章节/书签），
页面结构、滚动容器与返回栈都不同。前述各分区已共享，整体组合涉及结构改动、风险最高，暂缓到第四批阅读专项前单独评估；
补充元数据切换（`DetailsViewToggle` + `SupplementalMetadataDescription`）依赖 Android 追踪元数据来源，桌面暂无对应数据，一并暂缓。

#### 共享基础：风格环境与 Material3 升级（2026-10-26）

- 风格环境下沉 [SharedStyleEnvironment](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/core/ui/theme/SharedStyleEnvironment.kt)：
  `LocalMaterialExpressiveComponentsEnabled`（由 app 迁入，包名不变）、`LocalIsIosInterfaceStyle`、`LocalArtworkBackdrop`
  （NONE/ATMOSPHERE/GALLERY），以及 `isDarkTheme`、`ArtworkSurfaceRole`、artwork 透明度预设与
  `artworkAwareContainerColor`。Android `KototoroTheme` 从 `InterfaceStyle`/`BackgroundStyle` 偏好派生提供
  （`BackgroundStyle.artworkBackdrop`、`ArtworkBackdropPresets.forStyle` 留在 app 作映射），桌面 `DesktopTheme`
  按 MATERIAL→Expressive、IOS→iOS 提供。偏好枚举本身带 Android 资源，仍留在 app。
- `KototoroSlider` 与拇指迁入 core-ui；`KototoroRangeSlider` 留在 app（其 thumb interaction source 参数只在比 CMP
  所跟随版本更新的 androidx Material3 中存在），复用共享拇指。
- 经用户确认，core-ui 的 Material3 从 CMP 插件附带的 `org.jetbrains.compose.material3:material3:1.9.0`
  （对应 androidx 1.4.0）升级为显式固定的 `1.12.0-alpha03`（基于 CMP foundation/runtime 1.12.0-beta01，
  对应 androidx `1.5.0-alpha22`），CMP 插件不变。Android 运行时仍解析为 app 固定的 androidx `1.5.0-alpha26`。
- 验证：三端编译；`:core-ui:jvmTest` 与完整 `:desktop-app:test` 共 382 项通过、7 项跳过、0 失败；
  抽查筛选抽屉、阅读器滑块截图无视觉回退。Android 实机/模拟器、Windows 实机/CI 尚未运行。

### 第三批：设置与功能页面

范围：07 的其余设置，以及 08–17。

建议拆成独立批次：常规设置/主页订阅、下载、备份同步、书签统计、规则诊断/存储向导。
复用前两批的列表和编辑呈现；每批只新增实际需要的 Interface。

- 下载先统一状态到呈现的映射，再处理批量操作；不合并两端的后台执行框架。
- 备份/同步先固定“选择 → 预览 → 确认 → 进度 → 完成/错误”的状态，再接文件与网络 Adapter。
  UI 迁移不顺带调整备份格式、恢复策略、凭据存储或数据库 schema。
- 统计先对齐投影及时间/时区口径；书签先对齐定位与摘要语义，复用已有算法。
- 首页/订阅重点收敛分区和页面装配，保留已有共享 Hero、卡片、Feed/Updates 派生。

完成条件：每个迁移页都能列出同一共享入口及两个调用方；对应的失败重试、取消、确认和重启恢复流程通过。

### 第四批：阅读与原生能力专项

范围：18–20，分别安排漫画、小说、视频/浏览器任务。

- 阅读器先处理尚未共用的状态、动作与呈现；复用既有章节、控制栏、选项、几何和分页算法。
- 单独验证进度/书签恢复、章节切换、全屏、鼠标/触摸/键盘、缩放与性能。
- 视频先统一控制呈现与状态，再接 Media3/libmpv；浏览器呈现与网页执行分别设计 Interface。
- macOS WebView、内嵌视频、原生超分、应用更新的能力补齐另列平台任务。CMP 收敛本身不代表这些能力已实现。

完成条件：共享呈现的操作结果一致，平台 Adapter 的差异有实测；阅读进度、资源释放、帧率与内存没有未解释的回归。

## 实施约束

- 共享入口接受功能状态和操作，不接受整个桌面控制器、Android Activity、Hilt 容器或全局设置对象。
- 每批优先扩展现有 `core-ui` 与领域模块；不预先增加通用路由、DI、图像或偏好框架及新依赖。
- 先让 Android 调用提取后的实现，再接桌面；随替换移除该批重复呈现，不保留长期双轨实现。
- 状态分离按功能语义进行。`DesktopController` 的体量是线索，不把机械拆文件当作完成目标。
- 不通过屏蔽失败测试或硬编码平台分支取得“共享”；把缺失能力明确装配到宿主状态。
- 维持 schema、来源/作品/章节身份和偏好键兼容；保留本次任务以外的工作树修改。

## 验证与验收

第一批及第二批阶段已执行的验证见各自记录。以下是后续各批的通用验收要求。

每批先做受影响目标的编译/文案校验，示例命令如下；兼容目录按开发环境实际准备：

```bash
./gradlew -PwithDesktopApp \
  "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :core-ui:compileKotlinJvm :core-ui:verifyUiStrings \
  :app:compileDebugKotlin :desktop-app:compileKotlin :desktop-app:verifyDesktopStrings
```

按该批的真实风险选择共同模型测试、Android 测试和现有桌面 probe；不要为搬运基础样式重复建立测试。
可复用的桌面验证包括 `DesktopAppTest`、`DesktopLibrarySelectionTest`、`DesktopBrowsePagingTest`、
`DesktopRepositoriesProbe`、`DesktopSourcePreferencesTest`、`DesktopWebDavRoundTripTest`、
`DesktopReaderProbe`、`DesktopNovelPaginationTest` 等；执行前确认具体测试入口与依赖。

完成记录至少包含：

1. 共享入口和 Android/桌面调用方；本批替换了哪些重复呈现、仍留下哪些。
2. 同一数据场景下的 Android 手机/平板和桌面宽窄窗口截图或交互证据。
3. 搜索/筛选、选择/确认、错误/取消/重试、导航返回与重启恢复中本批涉及的行为。
4. 实际执行的构建/测试命令，以及 Android、macOS、Windows 各自的验证状态。

Windows 与 macOS 的共用 Kotlin 代码会同时获得改动，但平台行为仍各自验收。Windows 实机或对应 CI
尚未运行时，完成记录必须明确这一点。代码不以“已在 Mac 运行”替代 Windows 回归。

## 推荐启动项

第一批“共享偏好编辑呈现 + 代理设置完整页面试点”以及第二批卡片、列表容器、空态与滚动恢复已经完成；
选择栏主体、确认主体、分类列表、书库筛选面板、页头操作胶囊、来源结果页头、列表加载/出错状态、来源列表行、详情相关作品区、标签芯片、书签卡片、信息网格与简介展开也已共享，第二批仅剩暂缓的详情页整体组合，下一步进入第三批，
按实际需要收敛其余弹窗，再处理浏览结果与详情。
其他设置页面在第三批逐页接入现有共享编辑呈现。
此处仅记录推荐顺序，不代表已经授权或开始实施这些批次。
