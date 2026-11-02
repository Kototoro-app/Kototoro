# CMP UI 收敛盘点与改造计划

日期：2026-10-26。状态：第一批已实施；第二批已共享卡片、列表容器、空态、选择栏、部分确认/分类呈现
、书库筛选面板、页头操作胶囊、来源结果页头、列表加载/出错状态、来源列表行，以及详情页的相关作品区、标签芯片、书签卡片与信息网格与简介展开；第二批只剩详情页整体组合与补充元数据切换，已暂缓（见下）。
第三批已把播放设置、服务设置、导航配置与来源设置四个页面主体收敛到 `core-ui`，并把下载设置的速度与重试分组收敛为共享节。
阅读设置已共享阅读模式、缩放/裁边/动画、显示分组和小说规则入口，整页仍在逐段迁移。
长条间隙、减少预加载与自动滚动速度控件也已共享；速度控件同时供 Android 阅读器与桌面设置/面板消费。
小说字号/行距及分页/滚动选择已由 Android 设置页、Android 阅读器与桌面阅读器共同消费。
安装行为、安装确认、重排和多选编辑器也已共享，Android/桌面保留各自支持的扩展类型与提交方式。
词典规则的列表与编辑器、替换规则页与编辑器已由 Android/桌面共用；下载队列的条目与批量可用性判断、
全局标签黑名单页的搜索/分组/芯片网格、数据清理页的分组/行与已读章节开关、书签卡（Android 两个书签面共用，
桌面详情书签面板也在用）也已共享。
阅读统计看板的摘要板、活跃度卡、时段分布与类型占比卡已下沉 `core-ui/core/ui/stats` 并由两端共用，
日期热度图/条形图与作品榜仍是各自的绘图。
来源设置的协议偏好树也已共享：折叠与值文案在 `core-source`，整页行呈现是 `core-ui` 的
`SourcePreferencesContent`，Android 与桌面都已消费（Android 侧只多一层 androidx `Preference` → 协议节点的桥）。
全局书签页的分节头和选择栏、来源测试的结果呈现、作品统计的九十日走势图与统计页的类型分段筛选也已共享；
第三批 07–17 的页面级共享入口已按项核对成表（见“第三批完成情况核对”），14 来源偏好在 Android 侧也已接上共享呈现，
备份/WebDAV（11）的恢复预览节清单与恢复模式也已共享；11 剩下的 WebDAV 远端清单与第二批暂缓的详情页整体组合
都已复核并写明不共享的理由（见下方两段收尾记录），第二批与第三批至此没有悬空项。
第四批已从 18 漫画阅读开始：阅读器信息条的对齐/避让几何与描边文字下沉到 `core-ui`，Android 的
`ReaderComposeInfoBar` 保留窗口 insets/刘海/圆角测量后改用共享排布，桌面信息条改为同一个共享 row 并由两端共用。
第四批 19 小说阅读核对后确认呈现已全在 `core-ui`/`reader-novel`（chrome、章节目录、书签卡、选项页控件、排版与分页），
Windows 只是改用 `reader-novel` 的 Android 调色板当阅读主题。
第四批 20 视频/浏览器：播放器时钟与跳转气泡已下沉到 `core-ui`，Android 与 Windows 共用；浏览器两端都还没有 Compose 呈现。
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
| 14 | 来源偏好：`PREFERENCES` | 两端都已消费共享的协议偏好呈现（`sourcePreferenceSections` + `SourcePreferencesContent`）；Android 只多一层 androidx `PreferenceScreen` → 协议节点的桥。 | 共享协议节点的编辑呈现及校验结果；`callChangeListener` 否决、revision/提交和扩展运行时留在宿主。 | 已完成 |
| 15 | 规则与诊断：`TAG_BLACKLIST`、`REPLACE_RULES`、`DICTIONARY_RULES`、`SOURCE_TEST` | 黑名单、替换/字典规则和测试结果使用独立桌面页面；部分业务模型和规则已经共享。 | 提取规则编辑/预览/分组与诊断结果呈现；格式导入导出、来源测试执行、日志采集保留 Adapter。 | P2 / 中至大 |
| 16 | 存储与清理：`STORAGE`、`DATA_CLEANUP` | 桌面状态、路径和操作页面与 Android 独立；系统存储能力存在真实差异。 | 共享容量分类、扫描/预览/执行状态及确认流程；路径、权限、系统设置跳转和实际清理保留平台侧。 | P2 / 中 |
| 17 | 向导、说明与日志：`SETUP_WIZARD`、`CHANGELOG`、`CRASH_LOGS`，另含关于/更新卡片 | 向导和关于组装独立；Markdown 渲染已有共享模块，但宿主接入程度不同；更新存在 Windows 安装能力限制。 | 共享介绍/外观/完成、日志列表与说明呈现；权限步骤、日志打开/分享、升级安装按能力装配。 | P2 / 中 |
| 18 | 漫画阅读：`READER` | 控制栏、目录/选项、点击网格及部分算法已共享；场景装配、输入处理和资源生命周期仍由桌面实现。 | 在既有共享模块上核对动作/状态接线，逐步共享可通用的场景；不重写解码、缓存、窗口或已有相机算法。 | P3 / 大，高回归风险 |
| 19 | 小说阅读：`NOVEL` | 上下栏、目录、选项、书签内容及算法部分共享；正文、分页测量和会话装配仍分别实现。 | 优先统一阅读状态与文本/分页呈现；分清可共用的排版逻辑与平台字体、图片、TTS 的 Adapter。 | P3 / 大，高进度风险 |
| 20 | 视频、浏览器：`VIDEO`、`BROWSER` | Android 的播放器已有 Compose 控制层和原生渲染层；桌面使用独立 mpv 控件与浏览器调试页。 | 先共享播放状态、控制栏、轨道/章节等可通用呈现；Media3/libmpv、WebView、窗口嵌入、硬解留在平台实现。 | P3 / 大，高平台风险 |

表中“共享现状及需改造点”是**改造前**的盘点快照，不是当前状态；逐项落地结果、有意保留的差异与“按理由不共享”的结论都在
“分批实施”的各批阶段记录里。截至 2026-10-27，01–20 的代码收敛已无悬空项，剩下的只有平台运行证据与三处 P3 后续项
（见文末“推荐启动项”）。

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
| 14–17 | [SourceSettingsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/settings/compose/SourceSettingsScreen.kt)、[ReplaceRulesSettingsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/settings/compose/ReplaceRulesSettingsScreen.kt)、[DataCleanupSettingsScreen](../../app/src/main/kotlin/org/skepsun/kototoro/settings/compose/DataCleanupSettingsScreen.kt)、[WelcomeSheet](../../app/src/main/kotlin/org/skepsun/kototoro/main/ui/welcome/WelcomeSheet.kt) | [DesktopSourcePreferences](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopSourcePreferences.kt)、`DesktopReplaceRulesPage`、`DesktopDictionaryRulesPage`、`DesktopSourceTestPage`、`DesktopStorageSettingsPage`、`DesktopDataCleanupPage`、`DesktopSetupWizard`、`DesktopChangelogPage`、`DesktopCrashLogsPage` | `SourcePreferenceNode`、共享规则、`core-ui/core/ui/markdown`；来源偏好已由 Android 的 `PreferenceScreenNodes` 桥接共享呈现 |
| 18–19 | [ComposeReaderOptionsSheet](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderOptionsSheet.kt)、[NovelReaderActivity](../../app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelReaderActivity.kt) | [DesktopReaderChrome](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopReaderChrome.kt)、`DesktopReaderOptionsPanel`、`DesktopReader`、`DesktopNovelReader`、`DesktopNovelReaderChrome` | `core-ui/reader/**`、`reader-core`、`reader-novel` |
| 20 | [VideoPlayerActivity](../../app/src/main/kotlin/org/skepsun/kototoro/video/ui/VideoPlayerActivity.kt)、[VideoPlayerControls](../../app/src/main/kotlin/org/skepsun/kototoro/video/ui/compose/VideoPlayerControls.kt) | [DesktopVideoPlayer](../../desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopVideoPlayer.kt)、`BrowserPanel`、`DesktopBrowser` | `core-ui/video/ui/compose/`（播放时钟 `formatPlaybackTime`、跳转气泡）；控制条与浏览器仍留宿主 |

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

2026-10-27 复核（第四批完成后补做）：结论是继续“分区共享、整体各画”，不设共享组合层，理由与新切块评估见
下方“第二批暂缓项的复核：详情页整体组合仍不共享”。

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

#### 第三批阶段记录：播放设置完整页面（2026-10-27）

- 共享入口 `core-ui/core/ui/settings/PlaybackSettingsContent.kt`：`PlaybackSettingsUiState` 只带宿主持久化的
  背景/解码器键与两个不透明度百分比；选项标签、行图标和 Android 专属的 AI 设置行由宿主装配
  （`PlaybackSettingsRowIcons`、可选 `PlaybackSettingsAction`）。两个滑杆范围收敛到 `PlaybackSettingsRanges`，
  桌面 `DesktopVideoSettings` 的 clamp 也引用同一份定义。
- Android `PlaybackSettingsScreen` 保留偏好订阅与 `video_backgrounds` / `video_decoder_modes` 数组，页面和
  两个选择弹窗改为共享实现；图标仍取 `ic_images` 等，AI 行继续进入 `AISettings`，弹窗经 `AndroidPreferenceDialog`
  保留玻璃表面与 TV 焦点行为。
- 桌面 `DesktopPlaybackSettingsPage` 只保留返回按钮与 `controller.videoSettings(...)` 写入；选择项标签仍用
  桌面文案目录，AI 行因无对应页面不出现。
- 替换的重复呈现：两端各一套选择行/选择弹窗与滑杆行。仍留在宿主：偏好持久化、选项数组与文案、图标、AI 入口、
  返回导航和播放器状态投影。
- 验证：三端编译与两份文案校验通过；新增 `PlaybackSettingsContentTest` 2 项覆盖选择写入、可选 AI 行、滑杆提交、
  共享范围 clamp 与禁用态；`DesktopPlaybackProbe` 继续验证真实页面写入 `DesktopVideoSettings`。
  Linux/Xvfb 上完整 `:core-ui:jvmTest` 与 `:desktop-app:test` 共 336 项：本节相关的共享设置测试全部通过；
  6 项重型探针在整个 `DesktopAppTest` 里同样失败（存储枢纽/下载设置/平板布局/阅读器预取与相机/Windows
  筛选），把本页改动临时移出后重跑同一套仍有这 6 项失败，所以不是本页回归；其中下载设置那项是探针滚到底再点
  返回、按钮已不在视口，已在下载设置阶段改为先滚回顶部。
  Android 实机/模拟器、macOS/Windows 实机尚未运行。

#### 第三批阶段记录：服务设置完整页面（2026-10-27）

- 共享入口 `core-ui/core/ui/settings/ServicesSettingsContent.kt`：`ServicesSettingsUiState` 带建议/相关漫画/
  阅读统计/阅读时间估算四个开关，Android 的浏览追踪两行用可空的 `browseTracking`/`browseMoreTracking` 表达
  （子行只在其父开关打开时出现），文案与“已启用/已禁用”摘要统一走共享 `uiString`；`ServicesSettingsRowIcons`
  给 Android 的行图标留槽位，`ServicesSettingsAction` 给 Android 的 Discord RPC 入口留槽位。
- 共享行组件 `SettingsSwitchRow` 与 `SettingsSplitSwitchRow` 这次补上 `leadingIcon`，与 `SettingsValueRow`、
  `SettingsSliderRow` 一致，Android 不必为了共享而丢掉开关行的图标。
- Android `ServicesSettingsScreen` 只保留 `Scaffold` + snackbar 宿主与共享页面装配；`ServicesSettingsRoute`
  继续订阅偏好并把布尔值直接投影进共享状态，浏览追踪与 Discord 作为宿主槽位传入。
- 桌面 `DesktopServicesPage` 只保留返回按钮与 `controller.servicesSettings` / `showRecommendations` /
  `showReadingStats` 写入；浏览追踪与 Discord 行因 Windows 无对应行为不出现。
- 替换的重复呈现：两端整套服务页行（建议入口、四个开关、统计分裂行）。仍留在宿主：偏好持久化、导航
  （推荐/统计/Discord 页）、snackbar 宿主、Android 专属行与其图标。
- 验证：三端编译与两份文案校验通过；新增 `ServicesSettingsContentTest` 3 项覆盖各行写入、分裂行两半、
  可选行随槽位出现/消失与禁用态；`DesktopServicesProbe`（真实页面 + 重启读回）在共享页面下通过；
  `core-ui:jvmTest`、`SharedSettingsUiTest` 与 `PlaybackSettingsContentTest` 回归通过。Android 实机/模拟器、
  macOS/Windows 实机尚未运行。

#### 第三批阶段记录：下载速度与重试共享节（2026-10-27）

- 共享入口 `core-ui/core/ui/settings/DownloadSpeedSettingsContent.kt`：`DownloadSpeedSettingsUiState` 只带五个
  数字（线程、并行作品数、请求间隔、重试次数与重试间隔），范围与步长收敛到 `DownloadSpeedSettingsRanges`
  （线程 1..10、作品 1..11 且末位表示不限、间隔 0..5000 步长 100、重试 1..10、重试间隔 500..10000 步长 500），
  “不限”文案与其余标题/摘要走共享 `uiString`。这一节不是整页：Android 的目录/格式/画质/蜂窝网络/电池等行是
  这台机器无法执行的平台能力，仍留在 Android 页面；桌面页面只有这一节加一条平台差异说明。
- Android `DownloadsSettingsScreen` 的“下载速度与重试”分组改为共享节，行图标与数值仍由宿主传；页面其余分组、
  下载格式/蜂窝网络选项与目录选择器保持不变。
- 桌面 `DesktopDownloadSettingsPage` 的五个滑杆改为共享节，写入仍是 `controller.saveDownloadSettings(...)`；
  队列读取与 `DownloadPolicy` 的 clamp 没有改动。
- 替换的重复呈现：两端各一套滑杆行、范围/步长与“不限”文案。仍留在宿主：偏好持久化、Android 专属行与图标、
  桌面平台差异说明与返回导航。
- 验证：三端编译与两份文案校验通过；新增 `DownloadSpeedSettingsContentTest` 2 项覆盖五条滑杆写入、步长吸附、
  上下界 clamp、不限档与禁用态；`DesktopDownloadSettingsProbe` 在共享节下通过（并顺手修正了它滚到底后直接点
  返回的探针问题）。Android 实机/模拟器、macOS/Windows 实机尚未运行。

#### 第三批阶段记录：导航配置共享编辑器（2026-10-27）

- 共享入口 `core-ui/core/ui/settings/NavigationSettingsContent.kt`：`NavigationSettingsItem` 接受宿主的稳定目的地
  ID、标题、不可用提示和可选图标；列表、添加选择器、拖动草稿、松手提交与无障碍上下移进入同一实现。
  `showMoveButtons` 表达桌面的显式上下移按钮，Android 保留拖动手柄；两者调用同一排序事件。
- Android `NavConfigScreen` 保留资源映射、背景/圆角、系统栏间距、TV 焦点样式与 `AndroidPreferenceDialog`
  表面；`NavConfigRoute` 继续消费原 ViewModel，立即保存和延迟重建 Activity 的行为未变。
- 桌面 `DesktopNavigationConfigPage` 只装配目的地、不可用提示与 `DesktopNavigationSettings.move/add/remove`
  写入，返回按钮固定在共享滚动区外；“更多固定在最后”与上限说明仍由桌面 footer 提供。桌面行改为与 Android
  同一套独立圆角卡片，按钮与已满时的入口隐藏行为保留。
- 五项上限、去重和删除最后一项回到主页继续由 `MainNavigationRules` 负责，偏好键及存储格式不变。
  共用拖动使用仓库已有的 `libs.reorderable` 固定版本 3.1.0（新增 core-ui 的依赖声明，无版本升级）；
  没有另建排序框架。替换两端的行组合与添加弹窗后，清理了两个不再被消费的桌面文案键。
- 验证：`:core-ui:compileKotlinJvm`、`:app:compileDebugKotlin`、`:desktop-app:compileKotlin`、
  `:core-ui:verifyUiStrings` 与 `:desktop-app:verifyDesktopStrings` 通过。Linux/Xvfb 上
  `NavigationSettingsContentTest` 3 项与 `DesktopAppTest.navigation configuration reorders adds and removes the rail destinations`
  1 项通过，覆盖拖动松手提交、无障碍排序、上下移边界、添加/取消、满额禁用、忙碌时已打开的选择器禁用，以及真实
  页面的侧栏同步与离页后偏好值。380/760 像素窗口交互及截图已检查，截图在
  `desktop-app/build/reports/desktop-smoke/shared-navigation-{380,760}.png`。
  Android 目标完成编译，Android 实机/模拟器、macOS/Windows 实机与进程重启恢复本轮未运行；共享编辑器的
  窄/宽截图来自 JVM 测试，不代替 Android 系统栏与 TV 实机验收。

本批验证命令（Xvfb 由本地工具目录提供）：

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*NavigationSettingsContentTest' \
  --tests '*DesktopAppTest.navigation configuration reorders adds and removes the rail destinations' \
  --console=plain --no-configuration-cache
```

#### 第三批阶段记录：阅读模式分组与小说规则入口（2026-10-27）

- 共享入口 `core-ui/core/ui/settings/ReaderModeSettingsContent.kt`：`ReaderModeSettingsUiState` 表达默认模式、
  自动识别开关和背景键，分组结构与模式/背景选择器由两端共用；候选项仍由宿主装配，避免把 Android 的
  `ReaderMode` 与桌面 `DesktopReaderMode` 强行映射成相同枚举。空键或未知键保持无选中项，打开/关闭弹窗不写偏好。
- Android `ReaderMangaSettingsPage` 订阅原偏好，直接把 `reader_modes` / `reader_backgrounds` 数组和原键传入，
  写入仍使用原 `SharedPreferences`。屏幕方向、全屏行通过 `platformSettings` 槽位保留；图标与
  `AndroidPreferenceDialog` 表面由 Android 提供。共享行接入现有 `tvFocusable`，保留 TV 焦点呈现。
- 桌面 `DesktopReaderSettingsPage` 接入同一分组并移除独立的模式/背景弹窗。方向、适应、裁边与其他行仍在宿主，
  分组顺序调整为“模式/识别/背景”后接方向与适应等设置，行为及存储不变。
- `NovelRuleSettingsRows` 共用替换规则/词典规则入口的标题、摘要、交互与禁用状态；Android 小说页和桌面
  阅读设置均消费。规则管理页与导航继续由宿主负责。Android 小说页的两个入口现在作为同一组合项呈现，
  其余小说设置与通用/漫画/小说分页未迁移。
- 本批替换两端三个相同设置行、两份模式/背景选择器，以及规则入口行；清理两个被共享文案替代的桌面键。
  缩放/动画/裁边未迁移：Android 的裁边是分别配置两类模式的多选项，桌面是当前模式开关；Android 的其他
  阅读页、设备显示/音量键/电子墨水/性能设置也仍在宿主。本批属于分组与入口共享，不代表阅读设置整页已共享。
- 验证：Android 与桌面编译、两份文案校验通过。Linux/Xvfb 上 `ReaderModeSettingsContentTest` 3 项通过，
  覆盖 380/760 像素窗口的写入、规则回调、宿主显示槽位、空/未知键、取消和忙碌状态（含已打开选择器）。
  `DesktopReaderSettingsPageTest` 1 项通过：两个独立进程验证真实页面模式/背景/识别写入、原方向/适应编辑器、
  规则页面导航、返回及进程重启读回；没有依赖此前含已知失败的整套平板探针。
  截图 `desktop-app/build/reports/desktop-smoke/shared-reader-mode-{380,760}.png` 已查看。
  Android 实机/模拟器与 TV、macOS/Windows 实机本轮未运行，JVM 截图不代替这些平台的运行验收。

本批验证命令：

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*ReaderModeSettingsContentTest' --tests '*DesktopReaderSettingsPageTest' \
  --console=plain --no-configuration-cache
```

#### 第三批阶段记录：缩放、裁边与翻页动画（2026-10-27）

- 共享入口 `core-ui/core/ui/settings/ReaderZoomSettingsContent.kt`：分组、缩放与动画的选择器，以及裁边的
  呈现与编辑均由 Android 和桌面消费。模式键和候选标签仍由宿主装配，空/未知键不自动写回默认项。
- `ReaderCropSetting` 明确表达两种既有交互：Android 的 `ByReaderType` 使用原字符串键 `1` / `2` 多选，
  勾选只修改草稿，确认后调用原 `KEY_READER_CROP` 写入，取消不保存；未识别的已有集合成员继续保留。
  桌面的 `CurrentMode` 仍为立即写入的开关，宿主只修改 `cropPaged` 或 `cropContinuous`，不覆盖另一个值。
- Android `ReaderMangaSettingsPage` 保留缩放按钮与下拉手势，通过共享分组槽位装配；资源图标和
  `AndroidPreferenceDialog` 表面仍由 Android 提供，原缩放/动画/裁边偏好键与默认值不变。
- 桌面 `DesktopReaderSettingsPage` 移除独立缩放/动画弹窗与裁边行，改为同一共享分组。动画在连续阅读时仍禁用，
  分页恢复可选；当前模式裁边继续与正在打开的阅读器使用同一偏好。页面顺序现为模式分组、缩放分组、显示行与
  自动翻页分组。宿主只剩方向选择弹窗，其原单项枚举简化为布尔状态。清理三条被共享文案替代的桌面键。
- 验证：Android/桌面编译、两份文案校验通过。Linux/Xvfb 上 `ReaderZoomSettingsContentTest` 3 项、
  既有 `ReaderModeSettingsContentTest` 3 项和扩充的 `DesktopReaderSettingsPageTest` 1 项，共 7 项通过。
  覆盖裁边草稿确认/取消/重新打开、未知集合成员保留、当前模式立即写入、已打开弹窗的忙碌禁用、动画能力变化，
  以及真实桌面页面在双页/纵向连续/横向连续模式间切换后裁边值独立、动画禁用与进程重启读回。
  380/760 像素截图 `desktop-app/build/reports/desktop-smoke/shared-reader-zoom-{380,760}.png` 已查看。
  Android 实机/模拟器与 TV、macOS/Windows 实机本轮未运行；该批未改动阅读器裁边算法或动画执行。

本批验证命令：

```bash
./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :desktop-app:compileKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings \
  --console=plain --no-configuration-cache
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" :desktop-app:test \
  --tests '*ReaderZoomSettingsContentTest' --tests '*ReaderModeSettingsContentTest' \
  --tests '*DesktopReaderSettingsPageTest' --console=plain --no-configuration-cache
```

#### 第三批阶段记录：页码与章节标题显示（2026-10-27）

- 共享入口 `core-ui/core/ui/settings/ReaderDisplaySettingsContent.kt`：`ReaderDisplaySettingsUiState` 只带章节标题
  位置与页码两个布尔值；同一分组负责标题、摘要、开关状态、写入回调、忙碌禁用及 TV 焦点呈现。
- Android `ReaderMangaSettingsPage` 继续订阅并写入原 `KEY_READER_CHAPTER_TITLE_BOTTOM` / `KEY_PAGES_NUMBERS`，
  原默认值不变。保持亮屏、多任务与章节提示通过前置槽位保留，增强色彩通过后置槽位保留，行顺序不变；
  Android 资源图标仍由宿主提供。小说页中的标题设置未在本批迁移。
- 桌面 `DesktopReaderSettingsPage` 消费同一分组，保留 `controller.readerSettings` 的原写入路径，移除两个独立
  显示开关，并清理不再消费的桌面页码标题键。方向、双页封面、长条间隙和预加载控制仍在宿主，显示分组移到
  它们之前。页码摘要允许宿主提供：Android 保留右下角说明，桌面页码实际居中，继续不显示位置摘要，避免错述。
- 验证：Android/桌面编译与两份文案校验通过。Linux/Xvfb 上 `ReaderDisplaySettingsContentTest` 2 项、
  扩充的 `DesktopReaderSettingsPageTest` 1 项、现有 `DesktopReaderTitleBottomTest` 1 项，共 4 项通过。
  覆盖开关独立写入、宿主外部偏好更新不反向写回、忙碌禁用、可选行与摘要、真实页面保存与新进程恢复；
  真实阅读器探针验证标题在顶部/底部之间移动并保持只有一个标题胶囊。
  380/760 像素截图 `desktop-app/build/reports/desktop-smoke/shared-reader-display-{380,760}.png` 已查看。
  Android 实机/模拟器与 TV、macOS/Windows 实机本轮未运行；阅读器页码与标题的绘制代码未改动。

本批验证命令：

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*ReaderDisplaySettingsContentTest' --tests '*DesktopReaderSettingsPageTest' \
  --tests '*DesktopReaderTitleBottomTest' --console=plain --no-configuration-cache
```

#### 第三批阶段记录：长条间隙、预加载与自动滚动速度（2026-10-27）

- `core-ui/core/ui/settings/ReaderFlowSettingsContent.kt` 提供 `ReaderWebtoonSettingsContent` 和
  `ReaderPreloadSettingsRow`。前者共享长条分组和间隙开关，后者共享“减少页面预加载”的语义行；不是两端的
  整个性能页面或加载执行框架共享。
- Android `ReaderMangaSettingsPage` 接入同一长条分组，缩放、默认缩小比例与音量键滚动距离作为宿主槽位保留，
  包括旧 Long→Int 偏好迁移。预加载开关仍处于原折叠性能组中。桌面 `DesktopReaderSettingsPage` 只在纵向
  连续模式下装配长条分组，横向连续和分页模式不出现；间隙与预加载继续写入原偏好，未改加载算法或间隙计算。
- `core-ui/reader/ui/compose/design/ReaderAutoScrollSpeedControl.kt` 被 Android `ReaderAutoScrollPanel`
  （`ComposeReaderActivityScaffold`）、桌面设置页及 `DesktopReaderSidePanel` 实际消费。共享速度草稿、
  0..1 滑杆、`ReaderAutoScroll.speedMultiplier` 倍率文案及 TV 键盘处理。Android 仍拖动时实时生效，桌面仍
  松手后保存；TV 每次方向键作为完整编辑提交，阻止底层 Slider 在 KeyUp 再次回调造成重复保存。
  桌面设置页的速度值从原始百分比改为实际倍率，与阅读面板一致；存储值与定时算法不变。
- Android 自动滚动面板的启动/暂停、FAB、UI 显示时暂停与翻页时间说明留在宿主。Android 设置页没有桌面
  “自动跨章”的对应项，本批不新增伪对应开关，也不把该行为称为已经共享。
- 验证：Android/桌面编译、两份文案校验通过。Linux/Xvfb 上 `ReaderFlowSettingsContentTest` 2 项、
  `ReaderAutoScrollSpeedControlTest` 3 项、扩充的 `DesktopReaderSettingsPageTest` 1 项、现有
  `DesktopPreloadReductionTest` 1 项与 `DesktopReaderWebtoonGapsTest` 1 项，共 8 项通过。
  覆盖独立开关与禁用、实时/松手提交、外部速度恢复、TV 单次提交、真实页面的模式条件显示与新进程读回，
  真实阅读器的页面保留数量与长条间隙联动。380/760 像素截图
  `desktop-app/build/reports/desktop-smoke/shared-reader-flow-{380,760}.png` 已查看；速度控件另有手势/键盘测试。
  Android 实机/模拟器与 TV、macOS/Windows 实机本轮未运行，TV 证据来自 JVM Compose 键盘测试。

本批验证命令：

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*ReaderFlowSettingsContentTest' --tests '*ReaderAutoScrollSpeedControlTest' \
  --tests '*DesktopReaderSettingsPageTest' --tests '*DesktopPreloadReductionTest' \
  --tests '*DesktopReaderWebtoonGapsTest' --console=plain --no-configuration-cache
```

#### 第三批阶段记录：小说排版与阅读模式（2026-10-27）

- 在已有 `core-ui/reader/novel/compose/NovelReaderOptionsUi.kt` 中提取 `NovelTypographyControls`，
  原 `NovelTypographyOptionsPage` 改为装配这一主体。Android 小说阅读器与桌面继续通过原选项页消费，
  Android `ReaderSettingsScreen` 小说页也接入同一主体，替换独立的字号/行距滑杆实现。
  复用现有 `NovelReaderValueOption`、步进按钮与可展开滑杆，不新建另一套排版设置框架。
- 保留各入口的候选范围、步长与格式：Android 设置页字号 14..24、步长 0.5，行距 1.2..2.0、步长 0.1；
  桌面仍是字号 14..36、步长 1，行距 1.2..2.6、步长 0.05。Android 阅读器面板原字号步长 1 也保持不变。
  Android 设置页继续通过 `NovelReaderSettings.normalized().save(...)` 保存，归一化后未变化时跳过重复保存。
- 同文件的 `NovelReadingModeOptions` 由 Android 设置页、`ComposeNovelReaderOptionsSheet` 与
  `DesktopNovelOptionsPanel` 消费，同一选择芯片表达分页/滚动。Android 设置页从弹窗选择改为直接选择，
  桌面从分页开关改为两项选择；宿主仍分别映射原 `ReadingMode` 枚举与 `paged` 布尔值，原键/默认值不变。
  Android 阅读器继续提供原模式图标，分页动画的可见性与禁用规则未改动；清理一条不再使用的桌面标签键。
- 保留在宿主：字体选择、不同主题色板、段落/页边距、桌面内容宽度与 serif、全屏和其余小说开关。
  本批是排版主体与模式选择共享，不代表小说设置整页或两端小说引擎能力已经一致。
- 验证：Android/桌面编译、两份文案校验通过。Linux/Xvfb 上 `NovelSettingsControlsTest` 2 项与
  `DesktopNovelSettingsTest` 1 项，共 3 项通过；排版测试组合了两端取值与 380/760 像素宽度，验证小数步进、
  边界禁用、可选字体/额外布局槽位，以及模式双向切换和外部状态更新。
  桌面探针挂载真实选项面板、控制器与存储，用两个进程验证字号/行距/模式编辑和重启读回，未装载章节或来源。
  `shared-novel-typography-{true,false}-{380,760}.png` 四张 JVM 截图已查看（true/false 表示两端选项配置，
  不表示 Android 实机截图）。Android 实机/模拟器、macOS/Windows 实机本轮未运行。

本批验证命令：

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*NovelSettingsControlsTest' --tests '*DesktopNovelSettingsTest' --console=plain --no-configuration-cache
```

#### 第三批阶段记录：来源设置页面主体（2026-10-27）

- 共享入口 `core-ui/core/ui/settings/SourcesSettingsContent.kt`：概览、远程来源、成人过滤、更多四个分组共用；
  排序/隐身单选编辑器、手动排序入口条件、语言分组、损坏来源、安装策略入口、黑名单与标签警告，以及可选镜像/
  链接开关由同一主体呈现。宿主通过纯 UI 状态、候选项、图标与回调装配，不把控制器或平台枚举传入 commonMain。
- Android `SourcesSettingsScreen` 保留 Scaffold/snackbar、原偏好与导航回调、资源图标、系统栏间距和安装策略 Sheet；
  排序/隐身弹窗通过 `AndroidPreferenceDialog` 保留宿主表面。原“网格显示”行并不修改偏好，本批明确作为无点击的
  说明行呈现；未启用传入的旧 grid 写入回调。镜像与链接仍只在 Android 装配，错误 snackbar 路径未改变。
- 桌面 `DesktopSourceSettingsPage` 装配同一主体，返回按钮固定在共享滚动区外。原五组收敛为与 Android 同样的
  四组：黑名单入口随成人过滤组，标签警告在末组。网格开关继续实际写入；没有镜像/链接能力的宿主不画这两行。
  原排序枚举与候选集、`CUSTOM` 条件、隐身三态、偏好键与默认值保持不变。
- 保留的编辑器边界：Android JAR 优先级和成人多选在确认后保存，桌面分别逐次移动/逐项即时写入；两个宿主继续
  提供各自的编辑器槽位，不把不同的提交方式合并。安装策略 Sheet/Dialog 与手动来源排序编辑器本批仍在宿主。
  桌面重排使用按钮，向导不重置来源列表；共享页继续消费桌面原有说明，避免套用 Android 的操作/重置文案。
  替换重复页面呈现后清理九条被共享目录取代的桌面文案键。
- 验证：Android/桌面编译、两份文案校验通过。本轮 Linux/Xvfb 分批运行共 9 个不同用例：
  `SourcesSettingsContentTest` 3 项、`DesktopSourcesSettingsPageTest` 1 项，以及 `DesktopAppTest` 中
  手动来源排序、JAR 优先级、安装策略、隐身阅读与设置向导 5 项。初次隐身探针仍对固定返回按钮执行滚动，
  修正探针后单独重跑通过；最终对页面、文案覆盖和相关入口的 6 项回归也全部通过。
  覆盖条件行、能力缺省、忙碌时已打开的选择器、排序取消、导航回调、真实存储与新进程恢复，以及原编辑器提交语义。
  380/760 像素截图 `desktop-app/build/reports/desktop-smoke/shared-sources-settings-{380,760}.png` 已查看。
  Android 实机/模拟器与 TV、macOS/Windows 实机本轮未运行。来源设置属于页面主体共享，不等同于所有编辑器共享。

本轮最终回归命令（排序/JAR/安装策略另在前一批相应用例中通过）：

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*SourcesSettingsContentTest' --tests '*DesktopSourcesSettingsPageTest' \
  --tests '*DesktopAppTest.Windows incognito reader*' --tests '*DesktopAppTest.the setup wizard walks*' \
  --console=plain --no-configuration-cache
```

#### 安装行为、安装确认与重排编辑器后续收敛

- `core-ui/core/ui/settings/ExtensionInstallBehaviorContent.kt` 统一批量策略、混合状态与单类型编辑。
  Android `ExtensionInstallBehaviorSheet` 保留底部弹层、图标和九类来源装配；桌面
  `DesktopInstallBehaviorDialog` 保留窗口表面与五类已支持来源。原始策略值与存储键保持不变。
  窄窗口英文批量标签采用可换行的单选行，避免分段按钮的固定高度裁切文字。
- `ExtensionInstallPromptDialog.kt` 统一安装确认、来源数量未知时的说明、记住选择和两种安装动作。
  Android `UnifiedInstallChoiceDialog` 继续按类型保存选择状态并映射安装策略；桌面继续按请求重置状态。
  系统安装执行与弹窗表面由宿主提供。
- `core-ui/core/ui/compose/ReorderEditorDialog.kt` 从 Android 原编辑器提取完整排序会话，
  供 Android `ReorderDialog`、通用 `SettingsReorderPreference` 与桌面来源/JAR 编辑器实际调用。
  保留 Android/JAR 确认保存、桌面 JAR 每次移动立即保存；来源手动排序仍确认保存。
  共用分组边界、拖动、上下移按钮、可访问性动作、异步保存锁、错误重试与取消规则。
  原通用偏好允许确认空优先级，因此显式保留空列表保存能力；未知键与 `.jar` 原始键保持不变。
  保存动作同步锁住会话，防止同一帧第二次可访问性移动越过保存锁。
- 验证：Android 编译、两份文案校验与 Linux/Xvfb 16 项组合测试通过：安装行为 3 项、安装确认 2 项、
  重排编辑器 8 项，以及真实桌面安装策略、来源排序和 JAR 优先级恢复 3 项。
  截图 `desktop-app/build/reports/desktop-smoke/shared-install-behavior-{380,760}.png`、
  `shared-install-prompt-{380,760}.png` 和 `shared-reorder-{380,760}.png`。
  先前安装行为分段按钮的文字溢出测试曾失败，改为单选行后上述组合全部通过。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*ExtensionInstallPromptDialogTest' --tests '*ExtensionInstallBehaviorContentTest' \
  --tests '*ReorderEditorDialogTest' --tests '*DesktopAppTest.the install behavior answers*' \
  --tests '*DesktopAppTest.the source reorder editor stores Android*' \
  --tests '*DesktopAppTest.source settings reorder the parser*' \
  --console=plain --no-configuration-cache
```

Android `:app:assembleDebug` 已通过，生成 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。
首次依赖下载因 DNS 失败，按仓库约定使用 `127.0.0.1:7890` 的 HTTP/HTTPS Gradle 代理重试成功。
该 APK 对应以上安装与重排阶段；后续修改仍需重新编译验证。
Android CLI 已检查实际 SDK `/d1/android-sdk`：无连接设备、已有 AVD、系统镜像或 emulator，
本机也没有 `/dev/kvm`。因此本轮没有 Android 手机/平板/TV 的运行截图与输入验收；
Windows/macOS 实机同样未运行，不用 Linux 测试替代这些平台的验收条件。

#### 多选偏好编辑器后续收敛

- `core-ui/core/ui/settings/PreferenceMultiChoiceDialog.kt` 统一选项、选择数量限制、禁用状态、
  TV 焦点与 iOS 风格勾选/分隔呈现。默认私有草稿确认保存，也支持受宿主状态控制的即时提交；
  即时模式不乐观覆盖宿主状态，提交尚未被确认时仍显示已保存值。
- Android 通用 `SettingsMultiChoicePreference` 与两端使用的 `ReaderZoomSettingsContent` 裁边编辑器
  实际调用同一实现；桌面 `DesktopMultiChoiceSettingsDialog` 变为适配器，来源成人过滤与来源偏好多选
  也实际接入。Android 成人过滤继续确认保存，桌面逐项保存；选择数量限制与未知原始键保持不变。
  Android 玻璃/TV 弹窗表面仍走已有 `LocalSettingsDialogRenderer`，不复制平台表面实现。
- Android 编译、两份文案校验和 Linux/Xvfb 12 项检查通过：新多选交互 3 项、裁边回归 3 项、
  来源偏好分组模型 5 项、真实成人过滤/隐身状态恢复 1 项。其中 5 项模型测试不作为来源偏好整页运行证据。
  首次截图因存在两个 Compose 根节点失败，改为选择含编辑器的根节点后组合重跑通过。
  `desktop-app/build/reports/desktop-smoke/shared-multi-choice-{380,760}.png` 已查看。
  `git diff --check` 通过。未新增依赖；共享编辑会话替换重复列表，宿主只负责原始值与保存操作。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*PreferenceMultiChoiceDialogTest' --tests '*ReaderZoomSettingsContentTest' \
  --tests '*DesktopSourcePreferencesTest' --tests '*DesktopAppTest.Windows incognito reader*' \
  --console=plain --no-configuration-cache
```

#### 词典规则列表与编辑器收敛

- `core-ui/core/ui/settings/DictionaryRulesContent.kt` 提取 Android 原规则卡片与五字段编辑器，
  两端共用加载/空态、懒加载列表、启停/编辑/删除动作、草稿身份、必填校验与滚动编辑区域。
  `DictionaryRuleUiModel` 只含呈现值，避免给共享 UI 增加 Room/数据库模块依赖；排序原始文本交回宿主解析，
  Android 原有不 trim、桌面原有 trim 的数值处理均保留。名称/URL 保存前去空白，显示规则保留原始多行文本。
- Android `DictionaryRulesActivity` 继续提供顶栏/FAB、SAF 与 ViewModel，列表和弹窗实际调用共享实现；
  保存/删除后仍由 Android 关闭弹窗。桌面 `DesktopDictionaryRulesPage` 保留文件选择、数据流与异步保存，
  成功后才关闭；工具栏改为可换行，列表改为同一份规则卡片，取代原独立设置行和编辑器。
  数据库身份、改名即新增、JSON 格式、默认规则、启用规则查询与导入导出执行不变。
- Android APK 打包、两份文案校验和 Linux/Xvfb 4 项组合测试通过：`DictionaryRulesContentTest` 3 项
  覆盖加载/空态、30 条滚动、宽窄交互、禁用状态、草稿验证/切换/取消和原始输入；真实桌面 probe 1 项
  覆盖默认种子、增删改、启停、改名新增、导出重导入。共享编辑器成功回调不自行关闭，以保留宿主成功语义。
  `shared-dictionary-list-{380,760}.png` 与 `shared-dictionary-editor-{380,760}.png` 已生成并检查宽窄代表图。
  当前 APK 已包含本阶段与前述多选修改；仍没有 Android/TV、Windows/macOS 实机运行证据。
- 本批只共享词典规则列表和编辑器；Android 顶栏/FAB 与桌面导航/文件操作仍各自装配。
  替换规则管理页及词典查询界面没有在本批迁移，不把词典局部共享计为整个规则管理区域完成。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:assembleDebug :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*DictionaryRulesContentTest' --tests '*DesktopAppTest.dictionary rules are seeded*' \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 \
  --console=plain --no-configuration-cache
```

#### 替换规则页面与编辑器收敛

- `core-ui/core/ui/settings/ReplaceRulesContent.kt` 提取 Android 原替换规则页与五字段编辑器：两端共用一个可滚动的
  规则列表（加载行、空态、按分组标题分段、每行摘要与行尾开关）、工具栏（新增/全部启用/全部禁用/导入/导出）
  和 `ReplaceRuleEditorDialog`。`ReplaceRuleUiModel` 只含呈现值（名称/分组/正则/替换/作用范围/排除范围/序号/
  超时/三个开关），保留 `core-ui` 不依赖 `core-db` 的边界；正则与纯文本两种模式的摘要文案改为走 Android
  资源名（新增 `replace_rule_mode_regex`/`replace_rule_mode_text`，作用范围复用
  `novel_reader_replace_scope_{title,content,title_content}`），所以两端摘要不再各写一份。
- `SettingsPreferenceComponents.kt` 的 `SettingsActionPreference`、`SettingsSplitSwitchPreference`、
  `SettingsInfoPreference` 补上 `modifier`（分裂行另有 `switchModifier`）挂点，替换规则页与既有共享设置行
  因此在宿主里可以加宽窄约束，不再需要为了一行修饰符复制组件。
- Android `ReplaceRulesSettingsScreen` 删除自己的列表/工具栏/编辑器组合，只保留页面内边距与滚动容器、
  `editingRule` 状态和双向映射（组名/作用范围/排除范围的空串与 null 互转），校验与预览仍调
  `ReplaceRule.isValid()`/`apply()`；保存与删除沿用 Android 自己关闭弹窗的语义。
- 桌面 `DesktopReplaceRulesPage` 删除重复的摘要、工具栏与编辑器，只保留返回按钮、`JFileChooser` 导入导出
  与 controller 写入；编辑器在写入落地后才关闭，禁用/启用全部仍走 `setAllReplaceRulesEnabled`。
- 两端因此出现的收敛行为差异都已确认可接受：规则表为空时导出按钮在两端都禁用（Android 原来可点后报错）；
  桌面编辑器里重复的“规则已启用”开关删除（列表行尾开关仍写 `isEnabled`）；桌面序号与超时输入改为只接受
  数字且最长 8 位（与 Android 一致，超时仍按毫秒）；桌面不再有仅桌面的 scope/regex 文案键。
- 图标沿用 Android 的 drawable：`core-ui/build.gradle` 的 `readerPanelIcons` 追加
  `ic_book_page`、`ic_disable`、`ic_import`、`ic_cloud_upload`、`ic_reorder_handle`，共享页通过生成的
  `ReaderPanelIcons` 取画笔，Android 外观与原来一致的 `R.drawable` 来源。
- 桌面 catalog 相应删掉 `replaceRules.scopeTitle/scopeContent/scopeBoth/regexMode/textMode/enabled`
  六个不再使用的键，保留 `replaceRules.loading/filter/importFailed/exported/exportFailed`。
- 验证：APK 打包、两份文案校验与 Linux/Xvfb 组合测试通过——`ReplaceRulesContentTest` 2 项覆盖加载/空态、
  分组标题、摘要本地化、行点击/开关/导出/全部启用、30 条滚动、`enabled=false` 忙碌态，以及编辑器的
  保存禁用、序号 `1a2`→12 与超时 `9x9`→99、预览输出、作用范围勾选门槛、`id=7` 重灌、删除与取消；
  真实桌面 probe（`DesktopReplaceRulesProbe` → `replacerules.log`）写入 `DESKTOP_UI_OK=replacerules`，
  覆盖默认种子、增删改、启停与导入导出。`shared-replace-rules-list-{380,760}.png` 与
  `shared-replace-rules-editor-{380,760}.png` 已生成并查看宽窄代表图。`:core-ui:jvmTest` 亦通过。
  当前 APK 已包含本阶段修改；仍没有 Android 手机/平板/TV 实机运行证据，也没有 Windows/macOS 实机运行证据。
- 本批只共享替换规则列表与编辑器；Android 顶栏/返回、桌面导航与文件选择仍各自装配。词典查询界面与
  其余设置页不在本阶段内，不把替换规则页面计为整个规则管理区域完成。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:assembleDebug :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*ReplaceRulesContentTest' --tests '*DesktopAppTest.replacement rules*' \
  --console=plain --no-configuration-cache
```

#### 下载队列条目与批量可用性收敛

- 共享入口 `core-ui/core/ui/downloads/DownloadQueueContent.kt`：`DownloadQueueItemUiModel`（键、标题、章节副标题、
  状态、状态行、字节/页数行、ETA 或错误行、百分比、选中/展开、章节子项和四个批量旗标）只带呈现值，
  文本仍由宿主给（Android 的 `R.string` 与 work/task kind、桌面的 `desktopText`），`core-ui` 因此不接触
  WorkManager、`DownloadTaskKind` 或桌面队列；`DownloadQueueCard` 是 Android 原 `DownloadItemRow` 的版面
  （封面槽、标题行、状态/信息/详情三行、百分比、进度条、可展开章节列表、行尾动作按钮），两个宿主实际调用。
- 批量规则也只剩一份：`DownloadQueueBatchItem`/`DownloadQueueBatchState` 与
  `downloadQueueBatchState(...)`/`downloadQueueBatchStateOf(...)` 由同一组逐行旗标算出“有多少行支持”与
  “是否所有行都支持”。Android 选择栏用后者决定显示哪些动作（与原来逐项 `and` 的判断等价），桌面批量按钮用
  计数决定启用与标签，两种读法不再各写一遍。
- Android `DownloadsScreen` 删除自带的卡片实现（含重复收集两次章节 flow 的写法），改为把 `DownloadItemModel`
  映射成共享行后交给共享卡片；选择栏的四个可用性判断改调共享推导。工作队列、通知、展开状态和跳转仍留在宿主。
- 桌面 `DesktopDownloadsPanel` 删除自己的 `Card` 版面，改为共享卡片；勾选框通过 `leading` 槽保留（桌面没有长按，
  选择必须可点），动作行仍由桌面拼（暂停/继续或校验、离线阅读、导出），文件选择器仍走 `JFileChooser`。
- 因共享而变化的可见行为：桌面行现在有 Android 的进度条与百分比（`已完成页/总页`）、字数更多时不再挤在一行；
  Android 原来硬编码的 `"N chapters"` 改用 `chapters_count_info`（可翻译），字节进度行不再重复章节名
  （章节移到副标题行）；`CANCELLED` 的行只在进度条判定上按 `FAILED` 处理，状态文字仍是 `canceled`。
  混选时两端读法不同但旗标同源：Android 只有在所选每行都支持时才显示动作，桌面按支持的行数显示并只作用于这些行。
- 未共享的宿主部分：WorkManager 队列与任务类型文案、ETA/错误文案、桌面下载执行与 CBZ 导出、图片缓存预算行、
  清理预览弹窗（桌面独有），以及 `local/ui/compose/LocalDownloadsCard.kt` 那张电视/本地卡片本批未动。
- 验证：`:app:compileDebugKotlin`、`:core-ui:compileKotlinJvm`、`:desktop-app:compileKotlin` 与
  `:app:assembleDebug`（APK `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`）、`:core-ui:jvmTest`、
  两份文案校验（`:core-ui:verifyUiStrings` 151 个共享键）通过；Linux/Xvfb 上 10 项测试全绿——
  `DownloadQueueCardTest` 3 项（380/760 两档渲染与展开章节、动作按钮的标签/启用态/回调、已完成行不画进度、
  批量状态计数与“全部支持”两种读法）、`DesktopDownloadsBatchTest` 6 项（队列/选择/清理规则未回归）、
  `DesktopAppTest.Windows chapter downloads…` 真实 probe 1 项。宽窄代表图
  `desktop-app/build/reports/desktop-smoke/shared-download-queue-{380,760}.png` 已生成并检查。
  仍没有 Android 手机/平板/TV 实机运行证据，也没有 Windows/macOS 实机运行证据。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:assembleDebug :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*DownloadQueueCardTest' --tests '*DesktopDownloadsBatchTest' \
  --tests '*DesktopAppTest.Windows chapter downloads*' \
  --console=plain --no-configuration-cache
```

#### 全局标签黑名单页收敛

- 共享入口 `core-ui/core/ui/settings/BlacklistedTagsContent.kt`：`BlacklistTagUiModel`（存储键、显示名、别名、
  类别 id 与类别名）+ `BlacklistedTagsContent`，画出 Android 原来的整页正文——搜索框、`available` /
  `selected_count` 计数行、`add_tag_pattern` 动作、已选分组、按类别分组的自适应芯片网格（`GridCells.Adaptive(104dp)`）。
  词库、ID、别名索引与匹配规则本来就在 `core-domain` 的 `TagTaxonomy`/`TagTitleBlacklist`，本批把两端各自的
  呈现也合成一份，所以在手机上拉黑的标签与在 Windows 上拉黑的是同一批作品。
- 页面需要的宿主行通过 `header`/`footer` 槽留在同一个滚动容器里（桌面放返回按钮与 `clear`，以及底部说明行；
  已选数量由共享网格的 `selected_count` 承担，桌面原来的“已选 N 个标签”因此删掉），
  这样不会出现“外层再套一个 `verticalScroll`”的嵌套滚动；`tagPrefix` 参数让桌面原有的 probe 标签
  （`tag-blacklist:<key>`、`tag-blacklist-selected:<key>`）保持不变，搜索框标签经 `searchModifier` 传入。
- 详情弹窗仍归宿主：Android 长按只看别名（`aliases_for_tag`），桌面还多显示键、英文名与类别，手机放不下也不该放。
  共享芯片用 `SettingsMaterialIcons.Close`（本批新增的手写矢量，`core-ui` 不依赖 material-icons）作“移除”标记，
  长按语义标签是 `show_aliases`，无障碍上标为 `Role.Checkbox` + `selected`。
- 两侧改动：Android 的 `GlobalTagBlacklistScreen` 只保留 `Scaffold`/顶栏（返回、`clear`）与别名弹窗，
  正文委托共享网格，原来的本地 IME 选区同步逻辑由共享 `SettingsSearchField` 承担；桌面 `DesktopTagBlacklistPage`
  删掉自写的 `ChipFlow`（`FlowRow` + `pointerInput` 长按）与 `SettingsSectionHeader` 分节，改用共享网格，
  因此芯片从“只有标题的小胶囊”变成 Android 的“标签 + 别名行 + 选中标记”卡片，类别顺序仍按词库顺序。
- 因共享而变化的可见行为：桌面搜索框换成 Android 的紧凑圆角搜索框（`SettingsSearchField`），
  `available` 与已选计数改用 Android 的 `available`/`selected_count`，空结果用 `nothing_found`；
  桌面 `desktop-strings.properties` 与 `_zh-CN` 各删除 6 个已无调用的键
  （`sources.tagBlacklistSearch/Selected/Empty/Available/NotFound/Count`），否则 `verifyDesktopStrings` 报“无界面使用”。
- 未共享的宿主部分：Android 的顶栏与 TV 焦点环、`GlobalTagBlacklistViewModel` 的过滤与持久化、桌面的
  `desktop_background` 读写与 `desktopBlacklistChips` 词库投影；`raw:` 模式的判定条件也各留一份
  （Android 有文本就给、桌面要求词库解析不到），这是既有差异，本批没有改变。
- 验证：`:app:compileDebugKotlin`、`:core-ui:compileKotlinJvm`、`:desktop-app:compileKotlin`、
  `:core-ui:verifyUiStrings`（154 个共享键）、`:desktop-app:verifyDesktopStrings`（933 键，含 zh-CN）
  通过；Linux/Xvfb 上 `BlacklistedTagsContentTest` 2 项（420/900 两档的类别分组、别名行、点击切换、长按详情、
  已选分区与选中语义、宿主 header/footer 槽、空态无 `add` 动作）全绿，`DesktopAppTest` 的 tablet probe
  1 项、四种窗口（宽/窄/1000dp 边界/999dp 边界）全绿，含黑名单页那一段：搜索、点选、`desktop_background`
  写盘断言、长按详情、清空、返回。
  仍没有 Android 手机/平板/TV 实机运行证据，也没有 Windows/macOS 实机运行证据。
- 顺带修掉一处遗留：`DesktopTabletProbe` 与 `DesktopSuggestionPolicyProbe` 仍在找 `settings-recommendations-open`，
  那是“更多 → 服务”页改吃共享 `ServicesSettingsContent` 之前的行标签，现名 `services-suggestions`；
  三处引用已改到共享行标签，probe 才能走过服务页继续验证后面的标签黑名单之后各步。
  另有一处会让 probe 隔次失败的断言：批量加入下载后排队列随即回到空闲，后台回收若在这窗口里发出
  `downloads.cleanupAuto` 就会占掉唯一的 message 位（Android 那边是 toast，不存在这个问题），
  断言因此接受“已加入队列 N 章”或回收提示两种文案，队列内容仍由前后逐条断言严格核对。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*BlacklistedTagsContentTest' --tests '*DesktopAppTest.tablet layout*' \
  --console=plain --no-configuration-cache
```

#### 数据清理页收敛

- 共享入口 `core-ui/core/ui/settings/DataCleanupContent.kt`：`DataCleanupRow`（键、标题、摘要、启用态、可选的
  开关值/回调、可选前导图标）+ `DataCleanupSection`（标题 + 行）+ `DataCleanupContent`，按 Android 的
  “本地存储 / 缓存 / 隐私 / 章节”分组顺序排出 `SettingsSectionHeader` + `SettingsGroup`，行间自动插分隔线，
  有 `checked` 与 `onCheckedChange` 的行画成开关行，其余画成动作行；整页只有一个滚动容器，
  宿主自己的行（桌面的返回按钮）走 `header` 槽。
- 谁做哪些行仍归宿主：两个宿主各自构造 section 列表，所以能力差异是显式的——Android 有本地漫画/小说/视频缓存、
  HTTP 缓存、搜索历史、Cookie/浏览器数据与整库删除，Windows 只有它真能执行的图片缓存、页面索引、更新记录
  与已读章节，空 section 直接不画，而不是给一个清不掉东西的按钮。确认框、执行与计数也留在宿主。
- 两侧改动：Android 的 `DataCleanupSettingsScreen` 保留原签名（路由与 ViewModel 不动），只把 30 多个
  `SettingsActionPreference`/`SettingsSwitchPreference` 换成 4 个 section 的行模型，图标改由 `painterResource`
  经 `leadingIcon` 传入；页面顶部内边距经 `contentPadding` 进滚动容器，滚动到底时内容仍从顶栏下方开始。
  桌面 `DesktopDataCleanupPage` 从手写 `SettingsGroup`/`SettingsRowDivider` 改成同一份共享页面，
  行的 probe 标签 `cleanup-image-cache`/`cleanup-page-index`/`cleanup-feed`/`cleanup-read-chapters`/
  `cleanup-read-chapters-auto` 由 `"$tagPrefix-${row.key}"` 生成，保持不变。
- 因共享而变化的可见行为：桌面数据清理页的三节标题改由 `AndroidStrings` 取词（缓存/隐私/章节）后再进共享页面，
  行高、内边距和分隔线与 Android 一致；Android 这一页改用 `core-ui` 的共享行组件（与本批前面几个共享设置页
  相同），不再是 `SettingsPreferenceGroup` 的玻璃卡片分组，行尾因此和共享设置页一样多一个箭头（Android 原来
  这些行是 `showChevron = false`），这也是本批其他共享设置页已经接受的一致化差异。
- 验证：`:core-ui:compileKotlinJvm`、`:app:compileDebugKotlin`、`:desktop-app:compileKotlin`、
  `:core-ui:verifyUiStrings`、
  `:desktop-app:verifyDesktopStrings`（933 键，本批不需要新增或删除任何文案键）通过；新增
  `DataCleanupContentTest` 2 项（420/900 两档的分组顺序与标题、动作行点击、禁用行不触发、开关行翻转、
  空 section 跳过、宿主 header 槽、`tagPrefix` 换前缀）在 Linux/Xvfb 通过。
- 可移植性检查对 `core-ui` 是 SKIPPED（该模块没有需要 metadata 编译的目标，`reader-core` 之类才有），
  所以本批只有 JVM 与 Android 两端的实际编译信号。
- 真实页面证据：数据清理页原来只在存储枢纽 probe 的后半段被走到，而那条 probe 的网络节要扫 28 个真实
  GitHub 镜像——本机这条路本身已经不稳（镜像测速的 `Running`/结算状态在这台机器上等不到，取消后
  state 仍停在 Running），所以把清理页拆成独立 probe（新增 `DesktopAppTest` 的
  “the data cleanup page clears what this data root holds and arms Android's auto sweep”，
  `DesktopCleanupProbe`，模式 `cleanup`，从存储枢纽进入、不碰网络）：图片缓存行删掉未受保护的 blob、
  页面索引行清空 store 并回读、更新记录行弹 Android 的确认框、已读章节行因无已保存章节而禁用、自动开关
  写 `desktop_cleanup` 后返回枢纽，Linux/Xvfb 通过并留下截图。存储枢纽 probe 的镜像测速步顺带加了两处
  稳健性修正（“已开始”接受 Running 或已出结果；点取消前先断言按钮启用），但它在本机仍会卡在镜像扫描那一步，
  与清理页改动无关。仍无 Android 手机/平板/TV 与 Windows/macOS 实机运行证据。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings :desktop-app:test \
  --tests '*DataCleanupContentTest' --tests '*DesktopAppTest.the data cleanup page*' \
  --console=plain --no-configuration-cache
```

#### 全局书签页收敛

- 共享入口不是新组件，而是本批之前已经存在的 `core-ui/core/ui/compose/DetailsBookmarkCard.kt`：一张 0.7 的
  书签卡，`image` 槽放页面缩略图、`pageLabel` 画左下角黑底标签、`text`（`DetailsBookmarkText`）画小说章节行
  与摘录、`percent` 画右下角阅读环、`selected` 画主色蒙层与勾选图标，`selectedIcon` 由宿主给。
- Android 侧原先有两份卡片实现：详情页书签标签用的是这份共享卡，顶层书签页用的是
  `KototoroBookmarkCardThumb`/`KototoroBookmarkCardNovel`（188 行，自己的圆角/底色/底部黑色横条/进度环）。
  这次把详情页那段包装（磁盘上的页面快照、`file://` 与远程页两条回退、Coil 缩略图、小说摘录）提取成
  `app/bookmarks/ui/compose/BookmarkCard.kt`，两个页面共用；顶层页因此也走共享卡，自己的那份实现删除。
  `pageLabel` 是唯一按宿主区分的入参：详情页写具体页码（`P.7`），顶层页写章节名（空则退到
  `bookmark_position`），因为顶层页的分节只按作品分，卡上不留章节名就丢了这条信息。
- Windows 侧的两个书签面（独立书签页与详情书签面板）各自写了一遍“位置说明”规则（同一段
  `pageId == chapterId` 判定加两条文案），现在判定下沉到 `desktop-runtime` 的 `DesktopBookmark.isChapterBookmark`，
  文案收在 `desktopBookmarkLabel` 一处；桌面仍按行呈现（章节名 + 位置 + 小说摘录），原因见下。
- 因共享而变化的可见行为（Android 顶层书签页）：卡片不再是自绘的整条黑色底栏，章节名改由共享卡左下角的
  小标签承载（同样单行省略），阅读环移到底部右侧 24dp，选中态从 0.3 透明底色换成主色 0.4 蒙层加勾选图标，
  宽高比跟随共享卡的 0.7（原来按 `posterHeight` 的 1.5）。桌面这一页仍是文本书架：它的卡片不做逐条缩略图，
  否则一屏书签会各自拉起一次页面请求，这条差异在 §195 已记录，本批没有改。
- 验证：`:app:compileDebugKotlin`、`:desktop-app:compileKotlin`、`:desktop-runtime:compileKotlin` 通过；
  `:core-ui:verifyUiStrings`（154 键）与 `:desktop-app:verifyDesktopStrings`（933 键）无增删；
  `desktop-runtime` 的 `DesktopBookmarkShelfTest` 新增“章节书签判定”一项后 5 项通过；
  `DesktopBookmarksShelfTest`（真实两进程：写一条书签、另一次启动在书架里找到它、无痕打开并选择删除）在
  Linux/Xvfb 通过。Android 侧仍无手机/平板/TV 实机运行证据，只有编译。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :app:compileDebugKotlin :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings \
  :desktop-runtime:test --tests '*DesktopBookmarkShelfTest*' \
  :desktop-app:test --tests '*DesktopBookmarksShelfTest*' \
  --console=plain --no-configuration-cache
```

#### 阅读统计看板收敛

- 两个宿主早就在读同一份统计（`core-domain` 的 `deriveReadingStatistics`、`ReadingStatsPeriod`、
  `StatsContentKind`，桌面另加 `core-library` 的 `LibraryReadingStats` 投影），各自又重写了一遍同样的卡片：
  摘要、活跃度、时段分布、类型占比、时长文案。这次把这五样下沉到新模块文件
  `core-ui/core/ui/stats/ReadingStatsCards.kt`：
  `StatsSummaryCard`（Android 的 primaryContainer 摘要板：周期·类型眉标、`statsDurationText` 大字号总时长、
  活跃天数/会话/作品三格，带 `stats-total`/`stats-active-days`/`stats-sessions`/`stats-works` 标签）、
  `StatsCard`（24dp 圆角底板的卡片外壳）与 `StatsCardHeader`（标题/副标题/右侧徽标药丸，可带标签）、
  `StatsActivityCard(dayCount, streak, plot)`（Android 的标题、`stats_activity_days` 与连续天数药丸，中间那条
  日期图由宿主填 `plot` 槽，带 `stats-activity-days`/`stats-activity-streak`）、
  `StatsTimeDistributionCard(hourlyActivity, periodLabel)`（Android 把 24 小时折成八个三小时桶、最高桶配
  `stats_peak_period` 药丸、凌晨/正午/深夜三个轴标签，桶标签为 `stats-hour:<起始小时>`）、
  `StatsKindBreakdownCard(kinds: List<StatsKindShare>)`（每个类型一行色点+名称+“时长 · 百分比”与占比条，
  行标签 `stats-kind:<key>`）以及 `statsDurationText`（Android 原样的三日时/二分档格式，带接收 `UiStrings`
  的非组合重载，供单测与日志这类组合外场景使用）。
- `core-ui` 不依赖 `core-domain`（后者又依赖 `core-db`，这是既有的模块边界），所以共享卡不认识
  `StatsContentKind`：宿主把自己的枚举投影成 `StatsKindShare(key, label, color, duration)` 再传进来，
  颜色仍由 `MaterialTheme` 在组合里取。两端各自的 `StatsContentKind.label()`/`color()`/`title()`
  因此保留，但只用于构造投影与类型选择芯片。
- Android（`StatsScreen`）：摘要、活跃度、时段、类型占比四张卡改成调用共享实现，删掉自己的
  `SummaryCard` 内部实现、`SummaryMetric`/`SummaryDivider`、`ActivityCard` 外壳、`TimeDistributionCard`、
  `formatHourRange`、`KindBreakdownCard`、`StatsSectionCard` 与私有 `formatStatsDuration`；
  `ActivityHeatmap`（方格热力+星期首字母）作为 `plot` 保留在页内，作品榜 `WorkStatsCard`、类型选择芯片与
  `tvScrollableDashboard` 的电视遥控翻页也都留给 Android。几何与文案是照搬，所以 Android 的可见结果按设计不变。
- 桌面（`DesktopReadingStatsPage`）：摘要从五行 `SettingsValueRow`（带回纹箭头、且“连续 %1$d 天”那行把未格式化的
  文案原样露出来）换成 Android 的紫色摘要板，连续天数交给活跃度卡上的药丸；活跃度/时段/类型占比的节标题从卡外
  `SettingsSectionHeader` 挪进卡内（与 Android 一致），作品榜标题也搬进它自己的卡片；时段分布从 24 根小时柱改成
  Android 的八根三小时柱（同样带 `stats-hour:*` 标签，探针按每三小时一格断言）；类型占比从桌面的长条列表改成
  Android 的色点+“时长 · 百分比”行。日期图仍是桌面的竖条（`ActivityBars`），但每列限宽 44dp 并居中，
  三日窗口不会再画成三块大板。
- 明确保留的差异：日期图 Android 是方格热力、桌面是条形；作品榜 Android 是独立卡片列表（封面+排名+时长），
  桌面仍用一张卡片内的行列表（同一个共享 `StatsCard` 外壳、同样的 `supportingText` 文案）；
  作品统计弹窗（`ContentStatsDialog`/`DesktopContentStatsDialog`）本批只共享了时长文案，弹窗主体仍未合并。
- 验证：`:core-ui:compileKotlinJvm`、`:app:compileDebugKotlin`、`:desktop-app:compileKotlin` 通过；
  `:core-ui:verifyUiStrings` 从 154 个 lookup 增到 164 个（8 个统计键改由共享卡读取），
  `:desktop-app:verifyDesktopStrings` 仍是 933 键、全部被 UI 使用；新增 `ReadingStatsCardsTest` 5 项
  （摘要板数字与标签、活跃度卡标题/天数/药丸与 `plot` 槽、八桶分段与峰值药丸、类型占比的名称与百分比、
  时长四舍五入），`DesktopReadingStatsPageTest` 6 项、`DesktopContentStatsDialogTest` 2 项；
  真实探针 `DesktopAppTest` 的“一次阅读会出现在统计页、清空前先确认”在 Linux/Xvfb 通过，并新增截图
  `desktop-smoke/stats-stats-dashboard.png`、`stats-stats-activity.png`、`stats-stats-cleared.png`
  （探针只读了一页漫画，类型占比卡要多于一种类型才出现，所以那次没有拍到它）。
- 环境限制：本机 `:app:testDebugUnitTest` 跑不起来（`io.kotest:kotest-property:5.9.1` 不在本地缓存且外网被墙），
  因此 Android 侧只有编译证据；仍无手机/平板/TV 实机运行，也没有 Windows/macOS 真机运行。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :core-ui:compileKotlinJvm :app:compileDebugKotlin :desktop-app:compileKotlin \
  :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings \
  :desktop-app:test --tests '*ReadingStatsCardsTest*' --tests '*DesktopReadingStatsPageTest*' \
  --tests '*DesktopContentStatsDialogTest*' --tests '*DesktopAppTest*statistics*' \
  --console=plain --no-configuration-cache
```

#### 来源设置（协议偏好树）收敛

- 这一项的切口按计划就写明了：共享“协议节点的编辑呈现”，Android 的兼容偏好调用留在宿主。落地后分两处：
  折叠与文案规则进了 `core-source`（和 DTO 放一起，任何拿到 `SourcePreferenceNode` 树的宿主都能用）——
  `SourcePreferenceEntry`/`SourcePreferenceSection`、`sourcePreferenceSections`（`host:` 行领默认节、有标题 GROUP
  各自成节、无标题 GROUP 摊平、嵌套 GROUP 折成标签行、不可见节点与空节丢弃）、
  `sourcePreferenceRowTitle`（标题空则退到 key）、`sourcePreferenceChoiceLabel`/`sourcePreferenceMultiChoiceLabel`/
  `sourcePreferenceTextLabel`（选项显标签不显原始值、多选按 Android 逗号连接、密钥画至多 12 个圆点、空文本不显示）
  以及按控件种类取值的 `sourcePreferenceValueLabel`；整页呈现进了新文件
  `core-ui/core/ui/settings/SourcePreferencesContent.kt`（节标题 + 卡片 + 行，开关行/值行/信息行/嵌套分组标签行，
  行标签沿用 `preference:<id>`，行与行之间才画分隔线）。
- `core-ui` 因此新增 `api project(':core-source')`。这是本批唯一新增的模块依赖，理由写在构建脚本注释里：
  本项要共享的就是协议偏好树，而 `core-source` 是纯契约 KMP 模块（serialization/coroutines/okio，不带 Android、
  DB、DI 或第三方 UI 库），与既有的 `:backdrop`、`:reader-core` 同一层级；没有引入新的第三方依赖。
- 桌面 `DesktopSourcePreferences.kt`：删掉自己那份折叠、节点→行的映射和三个值文案函数，只留返回按钮、
  单选/多选/文本三个对话框、controller 写回与“哪一行打开了哪个对话框”的状态；页面结构变成
  “返回按钮 + `SourcePreferencesContent` + 对话框宿主”。这页的外观与交互没有变（行组件本来就是共享的），
  改造后重新生成的 `write-preferences.png`/`read-preferences.png` 与改造前一致。
- `DesktopSourcePreferencesTest` 那 5 项折叠用例随之搬进 `core-source` 的 `SourcePreferenceSectionsTest`
  （命名与断言不变），并补一项值文案用例，共 6 项；新增 `SourcePreferencesContentTest` 3 项，覆盖节标题与
  `preference:<id>` 标签、选项/多选/密钥的显示值、不支持控件用宿主文案兜底、每行回调都带回节点、
  忙碌页与源禁用的行不响应点击。
- Android 侧审计结论（本项要求的“核对”）：Android 的来源设置不是协议树，而是 androidx `Preference`——
  `ComposePreferenceAdapter` 把 `TwoStatePreference`/`ListPreference`/`MultiSelectListPreference`/`EditTextPreference`
  折成 `SourceSettingsRowUiState`，再由 `SourceSettingsScreen` 的 `SettingsSwitchPreference` 等行组件画出来，
  对话框状态挂在这些行组件内部。它与 `sourcePreferenceSections` 的折叠规则一致，但数据源与提交路径（
  `callChangeListener` + SharedPreferences 兼容层）不同，所以本批**没有**把 Android 接到共享呈现上：
  那需要先做 `PreferenceGroup → SourcePreferenceNode` 投影，并把对话框状态上提到页面级。这条记为后续项，
  不把“来源设置页两端同源”当作已完成。
- 验证：`:core-source:jvmTest`（`SourcePreferenceSectionsTest` 6 项）、
  `:desktop-app:test --tests '*SourcePreferencesContentTest*'`（3 项）通过；
  真实探针 `DesktopAppTest` 的“详情/阅读器/设置/共享状态跨第二个进程”——`write` 与 `read` 两个进程都打印
  `DESKTOP_UI_OK=write`/`DESKTOP_UI_OK=read`，其中就包含来源设置页：`preference:<id>` 行点击、对话框打开、
  来源拒绝后提示、保存后行上显示新值；`:core-ui:compileKotlinJvm`、`:app:compileDebugKotlin`、
  `:desktop-app:compileKotlin` 通过；`:core-ui:verifyUiStrings`（164 lookups）与
  `:desktop-app:verifyDesktopStrings`（933 键）无增删（这次只搬代码，没有新增文案）。
- 缺口：没有 Android 手机/平板/TV 实机或模拟器运行（本机 `:app:testDebugUnitTest` 也因 `kotest-property` 缺包跑不了，
  Android 侧只有编译证据）；没有 Windows/macOS 真机运行；“Android 接共享呈现”仍是待办。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :core-source:jvmTest :core-ui:compileKotlinJvm :app:compileDebugKotlin :desktop-app:compileKotlin \
  :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings \
  :desktop-app:test --tests '*SourcePreferencesContentTest*' \
  --tests '*DesktopAppTest.Windows rendered source details*' \
  --console=plain --no-configuration-cache
```

#### 设置向导（简介/外观/完成三页）收敛

- 计划 17 项的向导部分：Android 的 `main/ui/welcome` 是四页 pager（简介、权限、外观、完成），能跨端的是
  与平台无关的两页——简介（仓库/拓展/源三个概念 + 免责声明）和完成（去拓展管理、恢复备份、排障建议）。
  共享呈现进了 `core-ui` 的新包 `core/ui/wizard/`：
  - `WizardPageHeader(step, totalSteps, title, summary, icon: Painter?, iconVector: ImageVector?, prominent,
    containerColor, contentColor, stepTag)` 是 Android 私有 `WizardPageHeader` 的照搬：圆角图标底托、
    `welcome_step_counter`（带 `wizard-step-counter` 标签，探针据此断言步数）、`AnimatedContent` 滑入的标题、
    摘要；图标两个槽兼容两端（Android 仍用 `rememberSafePainter(R.drawable.ic_*)`，共享页用生成的 ImageVector）。
  - `WizardSectionCard(title, summary, actions, content)` 同 Android 的 `WizardSectionCard`（`surfaceContainerLow`
    卡片、标题/摘要/操作行、16dp 内边距、12dp 行距）。
  - `WizardIntroPage(step, totalSteps)` / `WizardDonePage(step, totalSteps, onOpenExtensionManagement,
    onRestoreBackup)` 是这两页的整页正文：文案全部走 Android 资源名（`welcome_intro_*`、`welcome_done_*`、
    `restore_backup`），完成页两个入口带 `wizard-open-extensions`/`wizard-restore-backup` 标签并保留
    Android 的 `FilledTonalButton` + `tvFocusable(ButtonDefaults.shape)` 遥控焦点圈。宿主只留两个回调。
- `:core-ui` 生成的图标集多了 `ic_welcome`、`ic_backup_restore`、`ic_palette`（同一个
  `generateReaderPanelIcons` 从 `app/src/main/res/drawable` 生成，所以两端拿到的是同一份 ImageVector），
  并新增 `api compose.animation`（只为共享标题的 `AnimatedContent`；这是 Compose 模块，没有引入
  Android/DB/DI/第三方 UI 依赖，`core-ui` 仍不依赖 `core-db`/`core-domain`）。
- Android（`WelcomeSheet.kt`）：`WelcomeIntroStep`/`WelcomeDoneStep` 的正文变成一行调用共享页，原来的
  `IntroConceptItem` 私有实现删除；`WizardComponents.kt` 只剩 `WizardFilterChip`，`WizardPageHeader`/
  `WizardSectionCard` 的私有副本删掉后，**权限页、空间页、外观页也改用共享页头与卡片**（传 `icon =` 画笔），
  所以四页的页头/卡片渲染只有一份。留在 Android 的：权限页（运行时授权）、空间页（书库切换）、外观页里的
  转场/全景开关、pager 与动作区、底部弹层外壳、TV 焦点与 BackHandler。
- 桌面（`DesktopSetupWizard.kt`）：简介页与完成页直接就是共享页；外观页保留桌面自己的三行（主题、界面风格、语言，
  打开的还是“更多”页那三个单选对话框），页头换成共享页头（标题用 Android 的 `welcome_appearance_title`，
  摘要用桌面自己的 `sources.wizardAppearanceSummary`，因为 Android 的摘要承诺了手机才有的转场/全景）。
  宿主原来自己画的那行 `wizard-progress` 删掉了——共享页头已经印 Android 的“第 N / M 步”，
  所以 `sources.wizardStep` 从两份 catalog 里删除（933 → 932 键，全部仍被 UI 使用）。
- 明确保留的差异：桌面没有权限页（Windows 没有运行时授权）与空间页（只有一个书库）；外观页没有
  转场/全景开关；向导不会在首次启动时自动弹出，只能从来源设置进入。这些都是能力差异，不是待补的空操作。
- 验证：`:core-ui:compileKotlinJvm`、`:app:compileDebugKotlin`、`:desktop-app:compileKotlin` 通过；
  `:core-ui:verifyUiStrings` 从 164 个 lookup 增到 184 个（向导页新读 20 个键），
  `:desktop-app:verifyDesktopStrings` 932 键且全部被 UI 使用；新增 `SetupWizardContentTest` 2 项
  （简介页的步数/标题/三个概念/免责声明；完成页的步数/标题/建议卡与两个入口各回传一个宿主回调）；
  真实探针 `DesktopAppTest` 的“the setup wizard walks the intro appearance and done pages from the source settings row”
  在 Linux/Xvfb 通过（它现在按共享页头的 `wizard-step-counter` 断言步数，仍走完三页、主题写盘、
  两个跳转与返回），截图 `desktop-smoke/wizard-intro.png`、`wizard-appearance.png`、`wizard-done.png`、`wizard-returned.png`。
- 环境限制：本机 `:app:testDebugUnitTest` 仍跑不起来（`io.kotest:kotest-property:5.9.1` 不在本地缓存且外网被墙），
  Android 侧只有编译证据；仍无手机/平板/TV 实机运行，也没有 Windows/macOS 真机运行。

```bash
PATH="$PWD/build/linux-tools/usr/bin:$PATH" xvfb-run -a -s "-screen 0 1600x1200x24 -nolisten tcp" \
  ./gradlew -PwithDesktopApp "-PmihonCompatibilityDirectory=build/desktop-compatibility" \
  :core-ui:compileKotlinJvm :app:compileDebugKotlin :desktop-app:compileKotlin \
  :core-ui:verifyUiStrings :desktop-app:verifyDesktopStrings \
  :desktop-app:test --tests '*SetupWizardContentTest*' \
  --tests '*DesktopAppTest.the setup wizard*' \
  --console=plain --no-configuration-cache
```

#### 崩溃日志列表与报告正文收敛

- 计划 17 项剩余部分里能跨端的是崩溃日志：Android 是列表页 + 详情页（`CrashLogActivity`/`CrashLogDetailActivity`，
  一个文件一个 `CrashLogItem`，正文是等宽可选文本），桌面是一页列表 + 对话框。共享呈现进了 `core-ui` 的新包
  `core/ui/crashlog/`：
  - `CrashLogEntry(id, title, summary, sizeLabel)` 是两端都能给出的行数据（行的标签用 `id`、时间戳由宿主格式化、
    摘要由共享规则得出、`sizeLabel` 只给有这项信息的一端），共享页不读文件系统。
  - `CrashLogsContent(entries, loading, emptyText, enabled, onOpen, tagPrefix, loadingText, emptyTag, loadingTag)`
    是 Android 列表正文：时间戳一行、异常行一行（两行截断），行间发丝线，`crash-log:<id>` 标签；空态与
    “还没读过目录”分开（`loading`），宿主另给 `loadingText`/`emptyTag`/`loadingTag` 保留自己的探针契约。
  - `CrashLogReportContent(text, modifier, tag = "crash-log-text")` 是报告正文：整段等宽 12sp、`SelectionContainer`
    可选、自身竖滚，Android 详情页和桌面对话框里是同一份渲染。
- “哪一行算一条报告的摘要”这条规则原先两端各写一份（Android 在列表项里现读整个文件、取第一条含
  `Exception`/`Error` 的行，没有就退回文件名；桌面读前 8 KiB、没有异常行就退回第一条非空行）。现在下沉 `core-domain`
  的 `core/diagnostics/CrashLogSummary.kt` 的 `crashLogSummaryLine(text, fallback)`：异常/错误行 → 第一条非空行 →
  `fallback`，`desktop-runtime` 的 `summary()` 与 Android 列表都调它，所以两端给同一条报告起同一个名字
  （Android 以前对没有异常行的报告只显示文件名，现在显示它的首行）。Android 侧顺带从“整文件读”改成
  只读前 8 KiB，和桌面一致。
- Android：`CrashLogScreen` 的标题与图标描述改用资源（`crash_logs`/`back`/`clear_crash_logs`，原来是硬编码英文 TODO），
  正文换成共享列表，私有 `CrashLogItem` 与两个 Preview 删除；`CrashLogDetailScreen` 正文换成共享报告正文，
  标题用 `crash_logs`、动作描述用 `copy`/`share`。留在 Android 的：`Scaffold` 顶栏、返回/清除动作、
  清空确认框、`CrashLogManager` 与启动详情页的导航、分享 Intent。
- 桌面：`DesktopCrashLogsPage` 的列表换成共享列表（多了 `sizeLabel` 一列，是桌面才有的信息），
  自己那段 Surface 卡片行、加载/空态文字与 `LazyColumn` 删除；页头去掉重复的页面标题（外壳已经印了，
  与替换规则页、数据清理页一致），只留返回与清除。报告对话框正文换成共享报告，并把 Material3 对话框默认
  ≤560dp 的宽度放开（`DialogProperties(usePlatformDefaultWidth = false)` + `width(760.dp)`），
  栈帧不再在词中间折行；标题、复制/删除/关闭按钮与 `crash-log-copy`/`-delete`/`-close`/`-report` 标签留在宿主。
- 明确保留的差异：Android 列表行不带大小（手机版面给不出这一列），报告是独立页面而桌面是对话框（手机放不下
  列表与正文两栏），Android 详情页有“分享 Intent”而桌面只有复制（Windows 没有分享面板，剪贴板是既有替代）。
- 验证：`:core-domain:jvmTest --tests '*CrashLogSummaryTest*'` 4 项、新增 `CrashLogsContentTest` 4 项
  （行内容与回传、空态与未读态分开、busy 时行禁用、报告正文）、`:core-ui:compileKotlinJvm`、
  `:desktop-runtime:compileKotlin`、`:desktop-app:compileKotlin`、`:app:compileDebugKotlin` 全部通过；
  `:core-ui:verifyUiStrings` 184 个 lookup 不变（共享页文案由宿主传入）、`:desktop-app:verifyDesktopStrings`
  932 键且全部被 UI 使用。真实探针 `DesktopAppTest` 的“a real crash writes a report the crash log page lists
  opens and deletes”通过：真线程崩溃写出报告 → 列表两行 → 打开最新一条 → 删除 → 清除 → 空态 → 返回，
  截图 `desktop-smoke/crashlogs-list.png`、`crashlogs-report.png`、`crashlogs-empty.png` 已人眼确认。
- 环境限制照旧：本机 `:app:testDebugUnitTest` 跑不起来（`io.kotest:kotest-property:5.9.1` 不在缓存且外网被墙），
  Android 侧只有编译证据；仍无手机/平板/TV 实机与 Windows/macOS 真机运行。

#### 存储用量分类行与汇总行收敛

- 计划 16 项的存储枢纽里，容量统计两端都有，但分类集合不同：Android 的 `StorageUsageCategory` 有十七项（本地漫画/小说/视频、
  各类缓存、AI 模型、可用空间），Windows 的数据根只有数据库/图片/偏好与下载记录/日志/其他/可用空间六项。所以共享的不是枚举，
  而是行的呈现：`core-ui/core/ui/storage/StorageUsageContent.kt` 的 `StorageUsageItem(id,label,sizeLabel,shareLabel,color)` 由
  宿主自己给 id、文案、大小与占比文字（两端的大小格式化与百分比小数位本来就不一样），`StorageUsageRow` 画色点 + 分类名 + 大小 +
  右对齐占比，`StorageUsageTotals` 画图下方那排小标题 + 半粗数字。
- Android 的 `StorageUsagePieChart.kt` 原本自带 `StorageUsageSummaryBar` 与 `StorageUsageLegend` 两段私有实现，现在都只是把
  `StoragePieSlice`（圆心角、颜色、`FileSize.BYTES` 文案、`%.1f%%`）投影成共享行；点行高亮对应扇区、再点取消仍由共享行
  `selected`/`onSelect` 承担，`onSelect == null` 时行没有点击动作。
- 桌面的 `DesktopStorageSettingsPage` 删掉自绘 `StorageUsageRow`，改用共享行（保留设置分组的 16dp 内缩，多出来的 4dp 由外层补），
  并把原来那句 “Kototoro 占用 X · 磁盘可用 Y，共 Z” 换成三列共享汇总行：Android 的两个数字（`storage_usage`/`available`）
  加上 Windows 才知道的卷容量（新键 `storage.volume`，同时删掉不再使用的 `storage.summary`，桌面目录仍是 932 键且全部被引用）。
- 图表本身仍各端保留：Android 是 Canvas 圆环（点击扇区选中），Windows 是 12dp 比例条（按数据根顶层子项宽度），两者都从同一份
  共享行数据里取颜色与大小；这次没有把圆环搬进 `core-ui`。
- 验证：新增 `StorageUsageContentTest` 4 项通过（行打印分类/大小/占比并按宿主标签定位、有 `onSelect` 时点击回调且选中态生效、
  没有回调时行无点击动作、busy 时点击无效；汇总行按宿主给的列数与文字渲染）；`:core-ui:compileKotlinJvm`、
  `:app:compileDebugKotlin`、`:desktop-runtime:compileKotlin`、`:desktop-app:compileKotlin` 通过；
  `:core-ui:verifyUiStrings` 仍 184 lookups，`:desktop-app:verifyDesktopStrings` 932 键全被使用。
  桌面真实存储页探针跑到网络节前已截 `storage-usage.png`（三列汇总行 421.3 KB / 215.23 GB / 299.94 GB，五行分类行带占比），
  其后固定在 GitHub 镜像测速那一步超时——本机无外网，是既有缺口，与本次改动无关。
- 证据缺口同前：`:app:testDebugUnitTest` 仍因 `io.kotest:kotest-property:5.9.1` 不在缓存且无外网不可运行（Android 只有编译证据），
  无手机/平板/TV 真机与 Windows/macOS 真机截图对比。

#### 顶层列表页摘要头卡收敛

- 计划 09 项的页头工具栏先落地：Android 早就有一份“顶层列表页摘要卡”（`app/core/ui/compose/PageSummaryHeader.kt`，被更新、推荐、
  历史、书签概览四处共用），桌面的同位置是手拼的 `Row(TopBarTitleBlock + TextButton + Button)`。现在这份卡片下沉到
  `core-ui/core/ui/page/PageSummaryHeader.kt`：`PageSummaryHeader(title, subtitle, icon: Painter?, accent, horizontalPadding,
  onClick, actions)` 与 `PageSummaryAction(text, onClick, icon: Painter?, imageVector, contentDescription, emphasized, enabled,
  accent)`。
- 两处为跨端做的接口调整：图标从 `@DrawableRes Int` 换成 `Painter`（Android 传 `painterResource(R.drawable.…)`，桌面传它自己
  由 `prepareDesktopIcons` 从 Android 矢量图导出的 SVG）；左右内缩变成 `horizontalPadding` 参数（Android 用 `AppLayoutTokens`
  的 12dp，桌面页面在自己的留白里传 0dp）；`PageSummaryAction` 增加 `enabled`，因为桌面在这些动作上本来就有忙碌态。
  书签概览页那两枚 `Icons.Default.*` 动作改走新加的 `imageVector` 参数，观感不变。
- Android 四个调用点改吃共享卡片后删掉 `app` 里那份实现（含 `@DrawableRes` 与 `AppLayoutTokens` 依赖）；桌面推荐页把标题行换成
  同一张卡：Android 的 `suggestions_header_title`/`suggestions_header_subtitle` 文案、`ic_explore_normal` 图标块、强调色 tertiary、
  两枚药丸（“推荐设置”带齿轮图标、换一批带同步图标且为主动作），`suggestions-summary`/`-settings`/`-refresh` 三个标签保留。
  为让刷新药丸用上 Android 的同一个字形，桌面图标白名单补了 `ic_sync`。
- 验证：新增 `PageSummaryHeaderTest` 3 项通过（标题/副标题/两枚动作与宿主标签、仅图标动作靠 `contentDescription` 定位、
  按下回调与 `enabled = false` 时不回调、无副标题无图标仍渲染标题）；`:core-ui:compileKotlinJvm`、`:app:compileDebugKotlin`、
  `:desktop-app:compileKotlin` 通过；`:core-ui:verifyUiStrings` 184 lookups 不变，`:desktop-app:verifyDesktopStrings` 932 键全被使用；
  桌面推荐页真实探针 `DesktopSuggestionsPageTest` 通过并断言了新头卡标题与刷新药丸，截图
  `desktop-app/build/reports/desktop-smoke/suggestions-suggestions.png` 已人眼确认为 Android 那张卡。
- 尚未接入：更新页与订阅页的页头仍各自拼装（更新页那行还要放来源/生态筛选控件），家庭页的分区装配属计划 08。
- 证据缺口同前：`:app:testDebugUnitTest` 因 `io.kotest:kotest-property:5.9.1` 无缓存无外网不可运行（Android 只有编译证据），
  无手机/平板/TV 真机与 Windows/macOS 真机截图对比。

##### 更新页接入共享摘要头卡

- 继推荐页之后，更新页的标题行也换成同一张 `PageSummaryHeader`：文案是 Android `UpdatesHeaderCard` 的四种组合
  ——有更新时 `updated_header_title` + `updated_header_summary`（作品数与新话数）配“检查更新”仅图标药丸与强调的
  “全部标为已读”，无更新时 `updated_header_up_to_date` + `_subtitle` 配带文字的“检查更新”药丸，图标块用 `ic_updated`。
- 来源/生态筛选控件按 Android 的摆放留在卡片下方单独一行（Android 也不把这些筛选放进摘要卡），`updates-summary`、
  `updates-check`、`updates-mark-all-read` 三个标签原样保留，探针的点击路径不变。
- 空态去重：卡片已经在说“所有订阅均是最新”，原来列表区那句同样的标题+副标题删掉，只留带 `updates-empty` 标签的占位区。
- 验证：`DesktopUpdatesPageTest` 真实探针通过，新增断言卡片标题、仅图标检查药丸与全部已读药丸、空态仍显示 Android 的
  `updated_header_up_to_date`；`:desktop-app:compileKotlin` 通过，`:desktop-app:verifyDesktopStrings` 仍是 932 键全被使用；
  两帧截图 `build/reports/desktop-smoke/updates-header.png`（有更新）与 `updates-empty.png`（空态）已人眼确认。
- 已知保留差异：空态副标题沿用 Android 的“下拉可检查最新章节更新”原句，桌面上检查更新的入口是卡片里的药丸而不是下拉手势；
  订阅页页头（`TopBarTitleBlock` + 筛选 + 检查 + 溢出菜单）与 Android 的顶栏结构一致，本轮不动。
- 证据缺口同前：无 Android 真机截图对照（`:app:testDebugUnitTest` 因 kotest 依赖无缓存不可运行），无 Windows/macOS 真机运行。

#### 作品统计看板的九十日走势图收敛

- 计划 13 项先落图表这一块：Android 的 `ContentStatsDialog` 与桌面的 `DesktopContentStatsDialog` 各抄了一份
  “每作品九十天柱状图”（12dp 柱宽、12dp 最小间距、放不下时按窗口平均压缩、虚线数值网格、实线基线、正值柱圆角），
  现在统一到 `core-ui/core/ui/stats/ContentStatsContent.kt` 的 `ContentStatsBarChart(values, barColor, emptyText,
  modifier, testTag)`：宿主只给序列（Android 传每天页/话/集数，桌面传每天秒数）、内容类型色、高度（Android 表为 240dp，
  桌面弹窗 200dp）与空态文案，几何与取整规则由共享实现负责。
- Android 侧删掉私有 `ContentStatsBarChart` 与 `computeValueStep`，`formatContentStatsDuration` 也换成共享的
  `statsDurationText(durationMs)`（本来就是同一套 `stats_duration_hours_minutes`/`_hours`/`_minutes` 分组）；
  桌面侧删掉私有 Canvas 与网格步进，改调共享图表并保留 `content-stats-chart` 标签。
- 验证：新增 `ContentStatsBarChartTest` 3 项通过（全零序列在宿主标签下显示宿主空态句、空列表同样走空态、
  90 天真实序列在 240dp 与 200dp 两个宿主高度都画得出来且不再显示空态句）；既有 `DesktopContentStatsDialogTest` 2 项、
  `DesktopStatsProbe`（`content-stats-chart`/`-first`/`-total`/`-sessions`/`-units` 五个标签）通过；
  `:core-ui:compileKotlinJvm`、`:app:compileDebugKotlin`、`:desktop-app:compileKotlin` 通过。
- 顺带修掉两端共有的空白图：原来的窗口平均会把“五天里只有一天读了一秒”的那一格平均成 0，图表于是整块空白
  （探针里 1.4 秒的阅读记录就是这样）；现在只要窗口里有过任何活动就保留一根 1 单位的最小柱，
  所以极短的阅读也看得见，量级不会被放大。
- 尚未收敛：看板的周期/类型选择与整页装配（Android `StatsScreen` 与桌面 `DesktopReadingStatsPage` 仍各自组合）、
  三枚指标小板的容器（Android 用 GlassSurface、桌面用 surfaceContainerHigh，属有意保留的平台观感差异）。
- 证据缺口同前：无 Android 真机运行（`:app:testDebugUnitTest` 因 kotest 依赖无缓存不可运行），无 Windows/macOS 真机运行。

#### 统计页的类型分段筛选收敛

- 计划 13 项再落筛选控件：Android `StatsScreen` 的 `ContentKindSelector` 与桌面 `DesktopReadingStatsPage` 的
  `KindSelector` 是同一个分段胶囊（`surfaceContainerHigh` 的 50% 圆角容器、4dp 内边距、2dp 间距、选中项填 primary +
  onPrimary 文字、labelLarge、水平 13dp），现在统一到 `core-ui` 的
  `core/ui/stats/StatsKindSelector.kt`：`StatsKindSelector(options, selectedKey, onSelect, modifier, enabled,
  pillHeight, horizontalPadding, optionModifier)` 与 `StatsKindOption(key, label)`。
- 两端只投影自己的枚举与文案：Android 传 40dp 胶囊高度、`tvFocusable` 的遥控焦点环（选中项因为填充了环形色，环色改用
  onPrimary，与旧代码一致）；桌面传 36dp、`stats-kind-filter`/`stats-kind-option:<KEY>` 标签与 `active` 的禁用态。
  `core-ui` 不依赖统计领域枚举，所以选项的 key 是宿主枚举名，由宿主自己映射回 `StatsContentKind`。
- 验证：新增 `StatsKindSelectorTest` 3 项通过（四个选项各自带宿主标签且点击回报一次 key、禁用行不回报、
  选中项由宿主标签区分）；`DesktopReadingStatsPageTest` 6 项通过；stats 探针重跑并确认
  `stats-stats-dashboard.png` 里“全部/漫画/小说/视频”分段胶囊与 Android 同形（选中项填 primary）；
  `:core-ui:compileKotlinJvm`、`:app:compileDebugKotlin`、`:desktop-app:compileKotlin` 通过。
- 有意保留的差异：周期选择仍是各端自己的形状——Android 是带日历图标与下拉箭头的 `FilledTonalButton` + 菜单，
  桌面因为窗口够宽直接铺成一行药丸；`StatsKindSelector` 只收敛内容类型这一组。
- 证据缺口同前：无 Android 真机运行（`:app:testDebugUnitTest` 因 kotest 依赖无缓存不可运行），无 Windows/macOS 真机运行。

#### 来源测试（诊断）结果呈现收敛

- 计划 15 项诊断侧的这一段：Android 的“测试全部来源”对话框与桌面 `DesktopSourceTestPage` 各自画了一遍同样的形状——
  进度条 + “已检查 N / M” + 正在问的来源 + 通过/不可用/Cloudflare 汇总 + 取消注记，以及每个来源可展开的六步结果
  （步骤名、判定、耗时、失败消息）。现在统一到 `core-ui` 的 `core/ui/sourcetest/SourceTestContent.kt`：
  `SourceTestProgressHeader(...)`、`SourceTestResultRow(result: SourceTestResultLine, expanded, onToggle, ...)`、
  `SourceTestStepLine`、`SourceTestResultLine` 与 `SourceTestVerdict`（PASS/FAIL/SKIP/CHALLENGE，对应 Android 的
  `StepStatus` 与桌面的 `SourceTestStatus`）。
- 两端只投影：Android 用 `UiStrings.string("source_test_*")` 取文案、把刚被禁用的源标成 `source_test_disabled`
  并保留自己的展开箭头；桌面用 `AndroidStrings` 取同一批键、把“六步”这类计数作为 `trailingLabel` 传进来。
  判定色也统一成主题角色（primary / error / onSurfaceVariant / tertiary）——桌面原来写死的四个十六进制色
  （`0xFF2E7D32` 等）在暗色主题下不跟随，现在与 Android 一致。
- 验证：新增 `SourceTestContentTest` 5 项通过（折叠行只有标题/判定/计数、展开出步骤判定与耗时与消息、
  禁用源改用宿主文案、进度块四个探针标签齐全、整行点击回调一次）；真实来源测试探针
  （`DesktopAppTest` 的 `the source test page runs every step against a local CMS site and exports the run`）
  端到端通过并重出截图 `sourcetest-source-test-results.png`：进度条、`2 / 2 checked`、
  “Available: 1, unavailable: 1, Cloudflare: 0”、两行判定（坏源红色 Failed / 本地源蓝色 Passed）与逐步耗时都在；
  `:core-ui:compileKotlinJvm`、`:app:compileDebugKotlin`、`:desktop-app:compileKotlin` 通过。
- 有意保留的差异：Android 是对话框（`SettingsAlertDialog`，结果区 360dp 高、每行后跟分隔线），桌面是整页
  （返回按钮、设置分组、结果节标题）；RANDOM 一步只在 Android 跑，挑战在桌面只报告不弹浏览器。
- 证据缺口同前：无 Android 真机运行（`:app:testDebugUnitTest` 因 kotest 依赖无缓存不可运行），无 Windows/macOS 真机运行。

#### 第三批阶段记录：全局书签页的分节头与选择栏收敛

- 这一项按计划只需要“现成组件真正接上线”，不新增 Interface：两端的分节头和选择栏都在重复画同样的东西。
- 共享分节头 `core-ui/coreMain/.../list/ui/compose/ContentCollectionHeader.kt`（同目录，紧邻 `ContentCollectionEmpty`）：
  `ContentCollectionHeader(title, metaLabel, onClick, trailing, horizontalPadding, verticalPadding)`。标题一行
  （`titleMedium`、单行省略、`weight(1f)`），中间可选计数，尾部是宿主自己的槽；整行只在宿主传 `onClick` 时可点。
  - Android `AppBookmarksRoute` 的 `ListHeader` 分支改为调用它：标题取 `ListHeader.getText`，`onClick` 打开作品详情，
    `trailing` 仍是 `KeyboardArrowRight` 箭头（内容描述改用 `R.string.more`，原来是写死的 "More"），`verticalPadding = 24.dp`。
  - 桌面 `DesktopBookmarksPage` 的每组标题也改为调用它：`metaLabel` 是“N 个书签”，`trailing` 是带
    `bookmark-group-more:<id>` 标签的“更多”按钮，`horizontalPadding`/`verticalPadding` 传 0 因为页面已有内边距。
- 共享选择栏 `ContentSelectionToolbar`（第二批已存在，Android 顶栏与桌面书库批量栏在用）接上第三个调用方：
  桌面书签页原来的自行 `Row`（数量 + 全选 + 清除 + 删除三个 TextButton）换成共享工具条，
  `tagPrefix = "bookmarks"` 因而计数节点仍是 `bookmarks-count`，两个动作项用显式 `tag` 保住 `bookmarks-select-all`
  与 `bookmarks-select-delete`（探针沿用），全选/删除的启用条件、文案与动作分发都与原先一致；原来的“清除”文字按钮由
  工具条关闭（X）按钮承担，即 `onClear`。
- 有意保留的差异：Android 仍用海报卡（卡片缩略图是保存的页面），桌面仍是文本行——打开一行才请求来源，
  所以一屏多部的书签架不会逐部下载；桌面书签页没有 Android 的 SAVE 动作（没有 `PageSaveHelper` 管线），
  工具条只放全选与移除。详情页的书签面板仍保留自己的“选择书签”模式切换行（那里的入口是模式而不是常驻选择栏）。
- 验证：`:core-ui:compileKotlinJvm`、`:app:compileDebugKotlin`、`:desktop-app:compileKotlin` 通过；
  `:core-ui:verifyUiStrings` 仍是 184 lookups、`:desktop-app:verifyDesktopStrings` 仍是 932 keys 全部使用；
  `DesktopBookmarksShelfTest` 的两个真实进程探针通过（`bookmarks-shelf-write`/`-read`，后者新增断言
  `bookmarks-bar` 存在、计数先显示总数再显示“已选 1 个书签”），所以共享工具条在真实页面上确实在画；
  新增 `ContentCollectionHeaderTest` 4 项（只画标题、计数 + 尾部槽、有 `onClick` 才可点、没传 `onClick` 时
  该节点没有点击动作），`--tests '*Bookmarks*'` 全部通过。
  Android 实机/模拟器、macOS/Windows 实机仍未运行。

#### 第三批阶段记录：Android 来源偏好页改为消费 `SourcePreferencesContent`（2026-10-27）

第三批遗留项 14。此前 Android 的来源设置页把 androidx `PreferenceScreen` 映射成自己的一套行 UiState
（`ComposePreferenceAdapter.buildSections`，293 行）再用 `Settings*Preference` 画，而桌面早已是
`sourcePreferenceSections` → `SourcePreferencesContent`，同一份协议树在两端走了两条呈现路径。

核对结论：

- 共享层不需要新 Interface。`SourcePreferencesContent` 的入参（`sections`/`active`/`unsupportedText` 与四个回调）
  已经覆盖 androidx 偏好的全部控件，缺的只是“androidx 控件 → 协议节点”的投影和三个对话框的外壳。
- 因此本轮只动 Android：新增 `settings/sources/PreferenceScreenNodes.kt`（188 行）做桥接，
  `ComposePreferenceAdapter` 只保留 `createScreen()`（23 行），`SourceSettingsRoute` 用它生成
  `SourcePreferencesSectionUiState`，`SourceSettingsScreen` 在对应位置渲染共享内容。

映射规则（androidx → 协议，全部保持既有编辑语义）：

| androidx | 协议节点 | 写回 |
| --- | --- | --- |
| `PreferenceCategory` / 有标题 `PreferenceGroup` | 有标题 GROUP（折叠成 section；组套组成标签行） | — |
| 无标题 `PreferenceGroup` | 无标题 GROUP（折叠时摊平进所在 section） | — |
| `TwoStatePreference` | TOGGLE + `Toggle(isChecked)` | `callChangeListener(checked)` 通过后 `isChecked = checked` |
| `ListPreference` | CHOICE + `Text(value)` + choices（entry/entryValue 成对） | `callChangeListener(value)` 通过后 `value = value` |
| `MultiSelectListPreference` | MULTI_CHOICE + `TextSet(values)` + choices | `callChangeListener(values)` 通过后 `values = values` |
| `EditTextPreference` | TEXT + `Text(text)`，键名/标题含 `password` 记 `isSecret`（沿用原判断） | `callChangeListener(text)` 通过后 `text = text` |
| `fragment != null` | UNSUPPORTED（页首加一条 INFO 兼容提示行） | — |
| 不可点、无 `intent`、无点击监听 | INFO | — |
| 其余（含扩展自定义控件） | ACTION + `Action()` | 先 `onPreferenceClickListener`，再 `intent` |

有意差异（都不影响编辑语义）：

- 多选与文本行的空值不再显示“未指定”：共享值文案（`sourcePreferenceMultiChoiceLabel`/`sourcePreferenceTextLabel`）
  对空集合/空串返回 `null`，桌面一直如此，本轮让 Android 一致。
- `EditTextPreference.dialogMessage` 的输入框占位不再传递（`PreferenceTextDialog` 没有占位参数）。
- 原来的“不可点行→纯标签行”改为 INFO 行（标题 + 摘要），与桌面同一画法。
- 扩展给偏好设的图标不再画：共享来源偏好行没有图标槽（`SettingsValueRow` 的 `leadingIcon` 目前只有更多页在用）。
- ACTION 行始终带右箭头，不再按 `isSelectable` 区分（共享 `SettingsValueRow` 就一种形状）。

顺带删除：`ComposePreferenceAdapter` 的行映射与 `rowIdFor`/`sectionIdFor`、`SourceSettingsGroupLabelRowUiState`、
`SourceSettingsMultiChoiceRowUiState` 及其在页面里的分支——映射迁走后它们已无人构造。

Android 侧留下的是平台该留的：`PreferenceManager` 与 per-source 的 `SharedPreferences` 文件名、
`MihonRequestContext` 下的 `setupPreferenceScreen`、`callChangeListener` 的扩展否决、
`suggestion`/登录等扩展自身的点击行为，以及对话框外壳（`AndroidPreferenceDialog` 保住 Android 的画与 TV 焦点）。

验证：

- `:app:compileDebugKotlin`、`:core-ui:compileKotlinJvm`、`:desktop-app:compileKotlin` 三个模块编译通过。
- `:core-source:jvmTest` 11 个类 72/72（含 `SourcePreferenceSectionsTest` 的折叠与值文案用例，桥接产出的节点形状
  正是它断言的形状）；`:core-ui:jvmTest` 10 个类 70/70；桌面 `SourcePreferencesContentTest` 3/3 与
  `PreferenceMultiChoiceDialogTest` 3/3（共享内容与多选编辑器的实际渲染未受影响）。
- `:core-ui:verifyUiStrings` 仍是 184 条查找、15 种内容类型；`:desktop-app:verifyDesktopStrings` 仍是 932 键全被引用
  （本轮没有新增文案，复用了 `settings_source_compat_title`/`_summary`）。

已知限制：本机 `:app:testDebugUnitTest` 因 `io.kotest:kotest-property:5.9.1` 无法解析而跑不起来（离线环境），
所以桥接本身没有单元测试，证据是编译与逐个控件的对照审查；没有 Android 设备可做真机点击验证。

下一步：11 的预览部分已补完（见下方阶段记录），剩下的 WebDAV 远端文件清单需要按 Android 的按文件恢复模式和
状态呈现来设计共享行的形状。

#### 第三批阶段记录：备份恢复预览的节清单与恢复模式共享（2026-10-27）

第三批遗留项 11 的前半段。核对发现两端“预览一个备份文件”的呈现重叠有限，但重叠的正是决策那部分：

- Android（`backups/ui/restore/RestoreDialog.kt`）：SAF 选文件 → `RestoreViewModel` 校验格式 → `AlertDialog` 里
  “替换/合并”两个单选 + 节复选清单（`BackupSectionModel`，缺分类时收藏被禁用并清勾）→ `RestoreService` 前台服务恢复。
- Windows（`DesktopBackupsPanel`）：`JFileChooser` → `DesktopBackupArchive` 私有快照 → 页面内文本预览
  （文件名、时间、每节条数、其他条目、合并说明）→ 合并恢复，整份档案一次性恢复。

所以共享的是**节清单与恢复模式的行呈现**，各自的文件访问、快照与执行留在宿主：

| 共享项 | 位置 | Android | Windows |
| --- | --- | --- | --- |
| `BackupRestoreSection(id, title, summary, checked, enabled)` + `BackupRestoreSectionList` | `core-ui/core/ui/backups/BackupRestoreContent.kt` | 对话框里的节复选清单 | 页面预览里的节清单（标题带条目数） |
| `BackupRestoreMode(id, title, summary)` + `BackupRestoreModeList` | 同上 | “替换现有/与现有合并”单选 | 无（Windows 只有合并，见下） |

`interactive = false` 是给“只能整份恢复”的宿主用的：行照常画标题、条目数与勾选状态，但不可点、不做任何回调——
Windows 的恢复入口是 `LibraryBackup.restore(archive)`，没有节过滤参数，所以宁可让它描述“将恢复什么”，也不给一个
点了不起作用的复选框。要让 Windows 也能选节，得先给 `core-library` 的恢复加节过滤（本批不做，记为后续项）。

有意差异：

- Windows 的节行从纯文本变成勾选行（同一条 `backups.sectionCount` 文案，仍是“收藏：12 条”），文字不再是 38% 透明，
  因为 `interactive = false` 时不套用禁用态的弱化色。
- Android 的模式行与节行现在带 `restore-mode:<MODE>`/`restore-section:<SECTION>` tag（此前没有任何 tag），
  `AlertDialog`、`TvTextButton`、`RestoreService`、`getCheckedSections()` 补 `INDEX` 与“无分类则禁收藏”的校验都没动。
- Windows 的 `backup-preview`/`backup-confirm`/`backup-cancel` 三个既有 tag 与按钮位置不变，新增节行 tag
  `backup-restore-section:<SECTION>`。

验证：

- 新增 `desktop-app/src/test/.../BackupRestoreContentTest.kt` 4 项：每节一行且有标题与条目数、缺节仍显示但禁用、
  点行回调宿主自己的 id、`interactive = false` 时点击不回调、“替换/合并”两行点哪行回哪个 id——全部真跑（4/4）。
- `DesktopAppTest` 的 `backups-write`/`backups-read` 探针在同一构建里通过（1 项，18.1 s），新增断言
  `backup-restore-section:BOOKMARKS`/`:STATS` 存在、点节行不会关掉预览、原有的“书签：1 条”“阅读统计：1 条”
  两行文本仍命中（现在它们就是共享行的标题）。
- `:app:compileDebugKotlin`、`:core-ui:compileKotlinJvm`、`:desktop-app:compileKotlin` 三模块编译通过；
  `:core-ui:jvmTest` 10 类 70/70；`:core-ui:verifyUiStrings` 184 查找/15 内容类型、
  `:desktop-app:verifyDesktopStrings` 932 键（1 个翻译）全被引用——本轮没有新增文案，复用了 `backups.sectionCount`。

已知限制：本机 `:app:testDebugUnitTest` 仍因 `io.kotest:kotest-property:5.9.1` 离线不可解析而跑不起来，Android 侧证据是
编译加共享组件的桌面侧测试；`:core-ui:compileCommonMainKotlinMetadata` 仍因 `atomicfu:0.23.1` 离线失败，可移植性靠审阅
（新文件只用 Compose foundation/material3 与既有的 `tvFocusable`/`LocalIsIosInterfaceStyle`，没有 JVM 专有 API）；
没有 Android 设备可点真机对话框。

下一步：11 的后半段——WebDAV 远端备份清单（Android 的 `WebDavRemoteBackupUiItem` 行带恢复状态与按文件的
“替换/合并”菜单，Windows 是文本行加恢复/删除两个按钮），先定共享行的形状再接线。

#### 第三批完成情况核对（07–17，2026-10-27）

按盘点表的编号逐项核对“共享入口是否两端真在消费”，而不是只看有没有新文件；下表每一行都对着工作树里的 import
和调用点核过。判据沿用指导文件：共享层只收功能状态与回调，宿主持久化、导航、平台能力留在自己那边。

| 项 | 共享入口（core-ui 及其他共享模块） | Android 调用方 | 桌面调用方 | 仍留宿主 / 有意差异 |
| --- | --- | --- | --- | --- |
| 07 设置 | `settings/ProxySettingsContent`、`PlaybackSettingsContent`、`ServicesSettingsContent`、`DownloadSpeedSettingsContent`、`NavigationSettingsContent`、`ReaderModeSettingsContent`、`ReaderZoomSettingsContent`、`ReaderDisplaySettingsContent`、`ReaderFlowSettingsContent`、`ReaderWebtoonSettingsContent`、`SourcesSettingsContent`、`SharedSettingsRows`、`PreferenceChoiceDialog`/`PreferenceTextDialog` | `ProxySettingsScreen`、`ServicesSettingsScreen`、`PlaybackSettingsScreen`、`DownloadsSettingsScreen`、`NavConfigScreen`、`ReaderMangaSettingsPage`、`SourcesSettingsScreen` | `DesktopProxySettingsPage`、`DesktopServicesSettingsPage`、`DesktopPlaybackSettingsPage`、`DesktopDownloadSettingsPage`、`DesktopNavigationConfigPage`、`DesktopReaderSettingsPage`、`DesktopSourceSettingsPage` | 偏好键与持久化、行图标、AI/空间等只在一端的入口、返回导航；“更多/服务”两个枢纽页仍各自分组 |
| 08 主页 | `home/HomeScreenLayout`、`HomeHeroPager`、`HomeSectionHeader`、`HomeListRailRow`、`HomeQuickActionsGrid` | `HomeScreen`、`sections/HomeContentRows`、`hero/HomeHeroSection` | `DesktopHomePanel`（分区装配仍在宿主） | 图片管线、数据订阅、点击导航、分区取舍 |
| 09 订阅/更新/推荐 | `tracker/feed/FeedTimelineCard`、`UpdatedContentCarousel`、`ContentCardBadge` 系列与派生规则 | `FeedItemCard`、`FeedScreen` | `DesktopFeedPanel`、订阅/更新/推荐三页 | 筛选与菜单容器、日期文案、刷新与更新检查；桌面订阅是列表行而非时间线卡（历史遗留，未强行统一） |
| 10 下载 | `downloads/DownloadQueueContent`（`DownloadQueueItemUiModel`、`DownloadQueueCard`、`downloadQueueBatchStateOf`） | `DownloadsScreen` | `DesktopDownloadsPanel` | 清理预览弹窗、CBZ 导出、图片预算行、WorkManager 与桌面串行执行 |
| 11 备份/WebDAV | `core-backup` 的格式、`core-net` 的 `WebDavBackupCatalog` 与 WebDAV 协议 + `core-ui` 的 `BackupRestoreSectionList`/`BackupRestoreModeList` + 共享设置行 | `RestoreDialog`（SAF 选文件 → 校验 → 共享节清单/模式 → `RestoreService`）、`BackupsSettingsScreen` 的远端行 | `DesktopBackupsPanel` 的预览节（`JFileChooser` → 私有快照 → 共享节清单 → 合并）、`DesktopWebDavSection` 的远端行 | 变更部分已共享：节清单（含条目数）与恢复模式；远端清单的目录/命名空间/保留规则已在 `core-net`，行用共享设置行。留宿主的是文件读写、`RestoreService`、逐文件 inspect 校验（可恢复状态）与替换语义，理由见下方收尾记录 |
| 12 书签 | `compose/DetailsBookmarkCard`、本轮的 `ContentCollectionHeader` 与 `ContentSelectionToolbar`、`core-domain` 的 `parseNovelBookmarkPreview` | `BookmarkCard`、`AppBookmarksRoute` | `DesktopBookmarksPage`、`DesktopDetailsBookmarks` | 详情书签面板自己的“选择书签”模式行；桌面没有 SAVE（无 `PageSaveHelper` 管线）；Android 海报卡 vs 桌面文本行 |
| 13 统计 | `stats/ReadingStatsCards`（摘要/活跃度/时段/类型占比）、`ContentStatsBarChart`、`StatsKindSelector` | `StatsScreen`、`HistorySummarySection`、`ContentStatsDialog` | `DesktopReadingStatsPage`、`DesktopContentStatsDialog` | 周期选择的形态（下拉 vs 胶囊行）、类型占比环图 vs 比例条、日期热度图与作品榜仍是各自绘图、存储分类数不同（17 vs 6） |
| 14 来源偏好 | `core-source` 的 `sourcePreferenceSections`/行文案/值文案 + `core-ui` 的 `SourcePreferencesContent` + `SharedSettingsRows` + `PreferenceChoiceDialog`/`PreferenceMultiChoiceDialog`/`PreferenceTextDialog` | `SourceSettingsRoute`（`PreferenceScreenNodes` 把 androidx `PreferenceScreen` 桥成协议节点）+ `SourceSettingsScreen` 的共享节 | `DesktopSourcePreferences` | androidx 偏好的回调、`callChangeListener` 否决、运行时与对话框外壳留在宿主；Android 不再有自己那套行映射 |
| 15 规则与诊断 | `settings/ReplaceRulesContent` + `ReplaceRuleEditorDialog`、`DictionaryRulesContent`、`BlacklistedTagsContent`、`sourcetest/SourceTestContent`（进度头 + 结果行） | `ReplaceRulesSettingsScreen`、`DictionaryRulesActivity`、`GlobalTagBlacklistScreen`、`SourceTestDialog` | `DesktopReplaceRulesPage`、`DesktopDictionaryRulesPage`、`DesktopTagBlacklistPage`、`DesktopSourceTestPage` | 格式导入导出的选择器、来源测试的执行/RANDOM 步骤/挑战处理、日志采集；桌面测试是页面而 Android 是对话框 |
| 16 存储与清理 | `settings/StorageUsageRow` 与 `StorageUsageTotals`、`DataCleanupContent` | `StorageUsagePieChart`、`DataCleanupSettingsScreen` | `DesktopStorageSettingsPage`、`DesktopDataCleanupPage` | 扫描/预览/执行的真实实现、系统设置跳转、可执行项取舍（桌面只列真能执行的项） |
| 17 向导/说明/日志 | `wizard/SetupWizardContent`、`crashlog/CrashLogsContent`、`markdown/` 渲染 | `WelcomeSheet`、`CrashLogScreen`、`CrashLogDetailScreen` | `DesktopSetupWizard`、`DesktopCrashLogsPage`、更新页与更新日志页 | 权限页与空间页（桌面无对应能力，不搬）、日志打开/分享、更新日志卡（Android 仍用 Markwon 单块渲染）、升级安装能力 |

核对后的判断：

- 07、08、09、10、12、13、15、16、17 九项的页面级共享入口都已两端实际消费，剩下的差异是容器形态、平台能力和
  有意保留的同功能不同画法，不再有“两端各写一遍同样呈现”的重复。
- 14 来源偏好已补完（2026-10-27，见下方阶段记录）：Android 用一层 `PreferenceScreenNodes` 桥接 androidx 偏好，
  行呈现交给共享 `SourcePreferencesContent`，`ComposePreferenceAdapter` 的 androidx→行映射与随之失效的
  `SourceSettingsGroupLabelRowUiState`/`SourceSettingsMultiChoiceRowUiState` 一并删除。
- 11 备份/WebDAV 已按两部分处理完（2026-10-27，见下方两段记录）：预览的节清单与恢复模式是 `core-ui` 的
  `BackupRestoreSectionList`/`BackupRestoreModeList`；远端清单不再另设共享层，因为它的目录/命名空间规则已在
  `core-net`、行本来就是共享设置行，剩下的差异（逐文件 inspect 得到的“可恢复”状态、替换语义）来自恢复管线而非画法。
- 第三批不再有“待迁移的整页”，也没有悬空的“未迁移”项：11 的两半分别落到“已共享”和“按理由不共享”（见下方收尾记录）；
  后续批次按计划转向 18 阅读器 / 19 小说 / 20 视频与浏览器这三个高风险专项。

#### 第三批收尾：11 的 WebDAV 远端清单不另设共享层（2026-10-27）

11 的另一半“远端备份清单”核对后**不再下沉**一层共享呈现，因为这一层的差异全部来自恢复管线与平台能力，
不是画法差异：

- 目录与规则已经共享：远端文件来自 `core-net` 的 `WebDavBackupCatalog.classify`（按名字分 V1/V2/V3 命名空间、
  取数据版本、按修改时间倒序），`latest`/`retired` 与 `remoteName` 也在同一处，两端拿到的是同一份
  `WebDavBackupFile`，写回文件名也一样。
- 行呈现没有可再榨的重复：Android 的远端行是 `core-ui` 的 `SettingsActionPreference`（图标 + 标题 + 摘要 + chevron，
  点行进确认框），Windows 的 `DesktopWebDavRow` 是“一行文字 + 恢复/删除两个行内按钮”。两端的动作模型不同（见下），
  行里剩下的只有标题与一行摘要，抽出来也只是一层没有行为的壳，按“不为共享而共享”不做。
- 真正分叉的是两处行为，都在宿主侧：
  - **可恢复状态**：Android 的“可恢复/不可恢复/未知”来自 `inspectWebDavRemoteBackups`——把每个远端文件
    `downloadBackup` 到临时文件再跑 `BackupPayloadGuard.requireRestorableWorkSnapshot`，本质是一次“逐文件下载校验”。
    Windows 没有这条 inspect 流水线；只为了在摘要里多一句状态就为每个远端文件下载一遍，代价与收益不成比例。
  - **恢复入口**：Android 点行 → 确认框（不可恢复时“恢复”禁用）→ `WebDavRestoreModeDialog`（替换/合并）→
    `RestoreService` 前台服务；Windows 是行内“恢复/删除”两个按钮，恢复即下载进本机预览/合并，而 `LibraryBackup`
    只有合并、没有替换（与 11 前半的只读节行同一个原因）。
- 所以这一层留在宿主，但不记为“未迁移”：目录、命名空间/保留规则、行组件与合并语义都是共享或与 Android 同源的，
  差别只剩上面两处。若将来要再前进，顺序是先把 inspect 校验下沉到 `core-backup`、再给 `LibraryBackup` 加节过滤与
  替换语义——那是恢复管线的工作，不在 UI 收敛批次里。

#### 第二批暂缓项的复核：详情页整体组合仍不共享（2026-10-27）

计划把详情页整体组合（含补充元数据切换）暂缓到“第四批阅读专项前单独评估”。第四批已完成，这里补上评估结论：

- Android 的 `DetailsScreen` 是手机/平板/TV 共用的重型组合：可折叠头部（`toolbarBottomPx`/`infoCardTopPx` 逐像素联动、
  `DetailsPaneState` + `DetailsDock`）、章节/页面/书签 pager、补充元数据（来源绑定、补充属性与分区、评论/评价、
  元数据搜索、翻译）、阅读记录与分享，单一 `DetailsScreenContent` 就 1900 行、依赖 5 个 ViewModel。
- Windows 的详情是“左信息卡 + 右章节/书签两张卡”（`DesktopTabletChrome` 的 `TabletDetailsPanes` 与堆叠分支），
  没有折叠几何、没有 pager、没有 Dock，也没有补充元数据的来源绑定与评论/评价（Windows 侧缺少这些数据源）。
- 分区早已共享（简介展开、标签芯片、信息网格、相关作品、书签卡片、加载/错误状态），所以“整体组合”剩下的部分
  在两端不是同一件东西：一端是逐像素折叠 + pager + Dock，另一端是两栏卡片。强行统一要改结构、返回栈与 TV 焦点，
  与本批收益无关。
- 唯一还能再切的小块是详情页 Tab 栏，但两端设计本就分叉：Android 是 `SecondaryTabRow` 的图标 Tab（带 TV 焦点环
  `tvTabRing()`），Windows 是带计数的文字按钮。共享任何一种画法都是给另一端做视觉回退，按
  [CMP 共享 UI 开发指导](shared-ui-guidelines.md) 的“不为共享而共享”原则不做。
- 结论：详情页维持“分区共享、整体各画”。补充元数据切换仍随该结论暂缓（Windows 需要先有元数据来源绑定数据）。

### 第四批：阅读与原生能力专项

范围：18–20，分别安排漫画、小说、视频/浏览器任务。

- 阅读器先处理尚未共用的状态、动作与呈现；复用既有章节、控制栏、选项、几何和分页算法。
- 单独验证进度/书签恢复、章节切换、全屏、鼠标/触摸/键盘、缩放与性能。
- 视频先统一控制呈现与状态，再接 Media3/libmpv；浏览器呈现与网页执行分别设计 Interface。
- macOS WebView、内嵌视频、原生超分、应用更新的能力补齐另列平台任务。CMP 收敛本身不代表这些能力已实现。

完成条件：共享呈现的操作结果一致，平台 Adapter 的差异有实测；阅读进度、资源释放、帧率与内存没有未解释的回归。

#### 第四批阶段记录：18 漫画阅读的接线核对与信息条共享（2026-10-27）

计划对 18 的要求是“在既有共享模块上核对动作/状态接线，逐步共享可通用的场景；不重写解码、缓存、窗口或已有相机算法”。
按此先核对两端阅读器的每一块呈现，再补上唯一还各写一遍的那块。

已由两端实际消费（本轮只核对，不改）：

| 呈现 | 共享入口 | Android 消费点 | 桌面消费点 |
| --- | --- | --- | --- |
| 顶栏（返回/标题/动作、沉浸高度） | `reader/ui/compose/design/ReaderChrome.kt` 的 `ReaderChromeTopBar`/`ReaderTopControlSurface` | `ComposeReaderActivityScaffold` 的 `ReaderComposeTopBar` | `DesktopReaderChrome` |
| 章节药丸、浮动控制钮、进度坞/进度条、缩放控件 | 同上（`ReaderChapterTitleChip`、`ReaderFloatingControlButton`、`ReaderProgressDock`、`ReaderProgressBar`、`ReaderZoomControls`） | 同文件 | 同文件（含“章节标题在底部”两处摆放） |
| 目录/选项/书签面板、点击网格、颜色滤镜、翻页动画、自动滚动速度 | `reader/ui/compose/` 下既有共享面板与 `ReaderPanelIcons` | 阅读器侧面板 | `DesktopReaderPanel` 等 |
| 分页/连续场景几何与可见范围 | `reader-core`（`PagedScene*`、`VerticalReaderScene`、`HorizontalReaderScene`、`ReaderModeDetection`） | 场景装配 | 场景装配 |

仍留宿主（本轮确认属于平台能力而非重复呈现）：场景装配与资源生命周期、键盘/滚轮/手势输入（Android 没有键盘映射）、
窗口 insets/刘海矩形/圆角半径的读取、系统状态槽内容（Android 画电池与时钟）、TTS/翻译/页面保存等安卓专有管线。

本轮唯一的重复呈现是信息条：

- 新增 `core-ui/.../reader/ui/compose/design/ReaderInfoBar.kt`：`ReaderInfoBarAlignment`（居中/贴边）、
  `ReaderInfoBarBounds`/`ReaderInfoBarCutout`/`ReaderInfoBarInsets`/`ReaderInfoBarPlacement`/`ReaderInfoBarItemPlacement`
  这些纯数据、`resolveReaderInfoBarPlacement`/`resolveReaderInfoBarProgressPlacement`/
  `resolveReaderInfoBarRoundedCornerPadding` 与 `readerInfoBarMeasurePolicy`（从 Android 原样搬来的“每个子项只测一次”）、
  以及 `ReaderInfoBarText`（无底色时的描边文字）与 `ReaderInfoBarRow`（bounds 由 row 自己上报，insets/刘海由宿主按
  `bounds -> 值` 的 lambda 提供）。
- Android `ComposeReaderActivityScaffold`：删除私有的排版/摆放/命名文字实现，`ReaderCutoutAwareInfoBarLayout` 只保留
  `WindowInsets.displayCutout`、`rootWindowInsets.displayCutout.boundingRects`、`getRoundedCorner` 与基础内边距的测量，
  再交给共享 row；`ReaderComposeInfoBar` 的底色、文字颜色、`tnum`、`ReaderSystemStatusContent` 槽位不变，
  所以 Android 的显示结果与改动前一致（同一份测量代码只是换了位置）。
- 桌面 `DesktopReaderChrome`：原来那两行自绘 `Row` 换成共享 `ReaderInfoBarRow`（居中、Android 的 20dp 横内边距与 6dp
  竖内边距、10dp 项间距），章节改用共享描边文字，页码仍是 `reader-progress`；`reader-info-bar` 仍是整条 tag，
  桌面因此第一次拿到颜色反转时依然可读的信息条。

测试：Android 侧纯几何的 12 个摆放断言与 1 个圆角断言搬到 `core-ui/src/jvmTest` 的 `ReaderInfoBarGeometryTest`
（`ReaderInfoBarAlignment` 取代偏好枚举，13 项全部真跑）；`ReaderInfoBarMeasureTest` 继续留在 `:app`（mockk + Compose
内部类型，本机无法执行，只保证编译）。桌面新增 `ReaderInfoBarTest`（居中时章节在页码左侧且整组居中、贴边时落在
左内边距、竖向内边距为 6dp×2），3 项全绿。

已知限制：`:app:testDebugUnitTest` 在本机无法解析 `io.kotest:kotest-property:5.9.1` 且无外网，Android 侧证据仍是编译；
`paged reader prefetches neighbours …` 探针仍以既有的 “UI process did not finish” 环境限制失败（进程在渲染前退出，
与本次改动无关，`reader-read` 探针在同一构建里通过并断言了共享 row 里的 `reader-progress`）。

下一步：19 小说阅读按同一方式核对（正文渲染/分页/翻译/样式已在 `reader-novel` 与共享面板），20 视频/浏览器再定控制栏
与播放状态的共享切口。

#### 第四批阶段记录：19 小说阅读的接线核对与主题调色板共享（2026-10-27）

19 的核对结果是：小说阅读的呈现入口早已全部在共享层，两端都在消费，本轮补上的是唯一一处“两端各存一份颜色”。

已由两端实际消费（本轮只核对，不改）：

| 呈现 | 共享入口 | Android 消费点 | 桌面消费点 |
| --- | --- | --- | --- |
| 阅读器顶/底栏、浮动控制区、颜色方案 | `reader/novel/compose/SharedNovelReaderChrome.kt`（`NovelReaderTopChromeContent`、`NovelReaderBottomChromeContent`、`NovelReaderFloatingControls`、`novelChromeColorScheme`、`ReaderInfoBarRow` 的读数条） | `ComposeNovelReader` / `NovelReaderChrome` | `DesktopNovelReaderChrome` |
| 章节目录（分卷分组、搜索、定位当前章） | `NovelChapterDirectoryContent`、`NovelChapterLocateButton`、`SharedNovelReaderSearchField` | `ComposeNovelChaptersSheet`（含 `directoryEntry()` 投影） | `DesktopNovelChaptersPanel`、`DesktopDetailsChapters` |
| 小说书签卡 | `NovelBookmarkCardContent` | `ComposeNovelMarkingsSheet` | `DesktopNovelBookmarksPanel` |
| 选项面板控件 | `NovelReaderQuickLayer`、`NovelReaderValueStepper`、`NovelReaderThemeSwatches`、`NovelTypographyControls`/`NovelTypographyOptionsPage`、`NovelReadingModeOptions`、`NovelReaderValueOption`/`NovelReaderThemeSwatch` | `ComposeNovelReaderOptionsSheet`（快捷层 + 详情页）、`ReaderSettingsScreen` 的字号/行距 | `DesktopNovelOptionsPanel` |
| 正文排版与样式 | `reader-novel` 的 `NovelReaderStyle`（字号/行距/边距/主题调色板）、`NovelStyleSheet`/`novelHtmlToStyledText` | `ComposeNovelReader`、`NovelBookStyleText` | `DesktopNovelTextStyles`、`DesktopNovel.kt` 的块视图 |
| 分页切行与阅读位置 | `reader-novel` 的 `splitNovelPageLineRanges`、块/进度换算 | Android 的测量后切页 | `desktopNovelPages`（每块当一行喂同一个切行器）、`DesktopNovelPagerState` |
| 替换规则与书签摘录 | `core-domain`/`core-replace` 的 `ReplaceRule`/`DEFAULT_REPLACE_RULE_TIMEOUT_MS`、`parseNovelBookmarkPreview` | `NovelTextProcessor` | `DesktopNovelTextRules` |

仍留宿主（平台能力或有意差异）：TTS 与朗读状态、机器翻译的执行与缓存、批注/标记的创建与编辑、文本选择与复制动作、
字体文件加载、章节图片解码与缓存，以及两端的排版引擎本身——Android 是一整段 `AnnotatedString` 按行切页，桌面是按块
（段落/标题/分隔线/插图）渲染并以块为进度单位，块永不被切开；这条差异与滚动/分页切换时的锚点规则一致，不合并。

本轮改动：Windows 的小说主题不再自带四组 ARGB，而是直接用 `reader-novel` 的 `novelReaderColors` 调色板：

- `DesktopNovelTheme` 从 `(background, text, muted)` 的私有常量变成 `preset: NovelReaderThemePreset` +
  `palette: NovelReaderColors`（`LIGHT→PAPER`、`SEPIA→SEPIA`、`GREEN→MOSS`、`DARK→SLATE` 的暗色变体）。
  枚举名不变，因为偏好 `desktop_novel` 的 `theme` 和来源偏好值都按名字存。
- 七个消费点改读 `palette`：阅读器背景/content color/分隔线/插图占位文字、顶底栏的 `NovelReaderChromeColors`、
  章节面板与书签面板（`isDark` 也改用共享调色板的 `isDark`）、选项面板的 `readerPanelColors` 与四个主题色块、
  正文/标题文字样式与次要文字色。
- 结果：Windows 的“浅色”不再是纯白页，而是 Android 默认的纸色底（`0xFFF4ECD8`，正文 `0xFF4F4032`），
  “深色”是 Android 的 dark slate（`0xFF1D2329`/`0xFFD7DDE2`），两端主题名、设置值与观感一一对应。

验证：新增 `desktop-app/src/test/.../DesktopNovelThemeTest.kt`（5 项：四个主题逐一等于 Android 调色板、只有 DARK 用暗色
变体、LIGHT 是纸色而非纯白、偏好里的主题名不变、正文与次要文字样式取自调色板、调色板确实来自共享函数）；
`DesktopNovelPaginationTest` 8 项与 `DesktopNovelSettingsProbe`（真实选项面板与持久化）在同一次构建里通过；
`:desktop-app:compileKotlin` 成功。Android 侧本轮没有任何代码改动（`novelReaderColors` 本来就是 Android 在用的那份），
所以 Android 证据仍只需 `:app:compileDebugKotlin`。

下一步：20 视频与浏览器，先核对播放器控制条/播放状态与浏览器呈现的现有共享部分，再决定控制呈现的共享切口；
第三批只剩 11（备份/WebDAV 预览）一项，14 已补完。

#### 第四批阶段记录：20 视频的时钟与跳转气泡共享、浏览器无共享呈现（2026-10-27）

20 的核对结论与 18/19 不同：视频这块**没有现成的共享呈现可接线**，两端各自独立。

已核对（本轮不改）：

- Android：`VideoPlayerControls.kt`（627 行，播放/暂停、进度条、章节/倍速/画质锚点、TV 焦点环、手势防误触）与
  `VideoPlayerActivity`（Media3，手势跳转、锁定、字幕/音轨对话框、弹幕、DLNA、超分）都紧贴 Media3 的播放状态。
- Windows：`desktop-player` 的 libmpv（JNA）把画面画进原生窗口，`DesktopVideoPlayer.kt` 自带一条控制条
  （进度滑条、±10s、上一集/下一集、画质/字幕/音轨/倍速/增强菜单、音量），投屏另走 `CastControls`。
- 浏览器：Android 是 Activity 里的 `WebView`（View 层，adblock/拦截器/挑战都在 `core/network/webview`），
  没有任何 Compose 呈现；Windows 的 `BrowserPanel` 是 WebView2 调试桥（HTML/JS/Cookie 操作），也不是浏览器界面。
  所以 20 的浏览器部分**没有可提取的 Compose 呈现**，等 Android 出现 Compose 浏览器界面、或 Windows 做真实
  浏览器页时再定共享切口；这里只记录事实，不为了“有东西共享”而造通用壳。

本轮实际共享（下沉 `core-ui` 的 `org.skepsun.kototoro.video.ui.compose`）：

| 共享入口 | 内容 | Android 消费点 | Windows 消费点 |
| --- | --- | --- | --- |
| `formatPlaybackTime(millis, padMinutes, forceHours)` | 播放器时钟：`M:SS` / `H:MM:SS`，可选补零分钟、可选强制小时位 | `VideoPlayerControls` 的进度两侧、书签/笔记时间戳（原 `formatDuration`）、`VideoPlayerActivity` 的跳过片头提示（原私有 `formatTimeMs`） | 控制条与投屏条的当前位置/总时长（原私有 `clock`） |
| `VideoSeekFeedback` / `VideoSeekFeedbackState` / `videoSeekFeedbackState(...)` | 跳转气泡：跳语 + “新位置 / 总长” + 进度条 | `VideoPlayerActivity.showSeekFeedback` 改用它组装（跳语仍取 `R.string.video_fast_forward_time`/`video_rewind_time`） | 新增：±10s 按钮与方向键 ±5/±30s 跳转时浮出同一个气泡，1 秒后消失（与 Android 的 `delay(1000)` 一致） |

随替换删掉的重复实现：Android 的 `internal fun formatDuration`（4 处调用点改共享）、`VideoPlayerActivity.formatTimeMs`、
`video/ui/compose/VideoSeekFeedback.kt` 整个文件；Windows 的 `private fun clock`。

已知差异（有意保留，不伪装成一致）：

- mpv 把画面画进原生 AWT 窗口，Compose 内容盖不住它，所以 Windows 的跳转气泡浮在**控制条上方**而不是画面正中；
  Android 的气泡居中于画面。等桌面走 render API（`MpvRenderApi`）时才可能真正叠在画面上。
- Windows 没有 Android 的手势跳转（双击左右、长按倍速），只有按钮与键盘，所以气泡只由按钮/方向键触发。
- 控制条本身（进度排、菜单锚点、TV 焦点态、锁屏、手势层）仍各自实现：它们的输入模型来自 Media3 的手势与遥控器焦点，
  和 mpv 的 `MpvState` 不是同一份状态，等两端播放状态模型统一后再谈整体控制条共享。

验证：`:core-ui:jvmTest` 70/70（新增 `VideoPlaybackTimeTest` 9 项：短值不补零、跨小时自动出小时位、补零形态、
强制小时形态、负数/未知位按 0 处理、气泡文本与进度、拖动无跳语、未知时长无进度、进度不越界）；
Windows 新增 `VideoSeekFeedbackTest` 5 项（跳语取 Android 同名字符串、前进/后退各自的措辞、两端夹紧、
时长未知返回 null、气泡真实渲染出文本）；`:app:compileDebugKotlin`（含 `:core-ui:compileKotlinJvm`）与
`:desktop-app:compileKotlin` 成功；`:core-ui:verifyUiStrings` 仍是 184 lookups/15 内容类型，
`:desktop-app:verifyDesktopStrings` 仍是 932 键且全部被使用（本轮没有新增键，跳语复用 Android 的 `video_*_time`）。

已知限制：`:core-ui:compileCommonMainKotlinMetadata` 在本机离线环境无法解析 `atomicfu:0.23.1` 元数据而失败，
可移植性只能靠审阅（新文件只用 Compose 与纯 Kotlin，无 `String.format`/`Locale`/`java.*`）；
本机没有 libmpv，`video` 探针仍只会走到“缺少 libmpv”的提示页，所以 Windows 气泡的运行证据是组件测试而不是真机截图。

下一步：控制条整体共享需要先统一播放状态（P3，风险高，按计划留到后面）；第三批遗留项 14 已补完（Android 来源偏好页
改吃共享呈现，见第三批阶段记录），11 的恢复预览节清单也已共享；剩下的 WebDAV 远端文件清单仍记在第三批段落里。

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
选择栏主体、确认主体、分类列表、书库筛选面板、页头操作胶囊、来源结果页头、列表加载/出错状态、来源列表行、详情相关作品区、标签芯片、书签卡片、信息网格与简介展开也已共享；详情页整体组合经复核仍维持“分区共享、整体各画”，没有悬空项。
第三批已迁移播放设置、服务设置与导航配置的页面主体，并把下载设置的速度与重试分组收敛为共享节（两端页面上
真正相同的那一段；Android 的平台专属行留在宿主）。阅读设置已迁移阅读模式、缩放/裁边/动画、显示分组和小说规则入口，
长条间隙、减少预加载行和三处自动滚动速度控件也已共享；自动跨章、各端执行框架与设备专属控制仍在宿主。
小说排版主体和模式选择也已让 Android 设置页、两端阅读器共用；通用页的音量键/导航反转/控制标签与其余小说
字段仍有平台差异，未把不存在的对应设置补成空操作。来源设置主体及排序/隐身单选编辑器已收敛；
安装行为、安装确认、重排与多选编辑器也已按提交模式收敛；词典规则列表与编辑器已共享，
替换规则页与编辑器、下载队列条目与批量可用性判断、全局标签黑名单页、数据清理页、书签卡也已共享，
来源设置的协议偏好树、设置向导的简介/完成页（含两端共用的向导页头与卡片）、崩溃日志的列表/报告正文、存储用量的分类行与汇总行、
以及顶层列表页的摘要头卡（含药丸动作，推荐页与更新页已接入）、作品统计的九十日走势图与统计页的类型分段筛选已收敛，
第三批其余功能页面（含替换规则、词典规则、黑名单、数据清理、设置向导、崩溃日志、存储用量、
统计走势图与类型筛选）也都已逐页接入现有共享编辑呈现，11 备份/WebDAV 的两半按收尾记录处理完毕。
用户已要求持续推进文档任务，范围仍是 01–20 清单及各批验收条件。截至 2026-10-27，四批的**代码收敛**已全部走到
“已共享”或“写明不共享”两种终态：01–20 盘点表里没有悬空的“未迁移”项，两处暂缓（详情页整体组合、11 的远端清单）
都已复核并留下理由。仍未完成的是**平台运行证据**这一类验收项，本机做不了：没有 Android 设备/模拟器（无 `/dev/kvm`）、
没有 Windows/macOS 实机，`:app:testDebugUnitTest` 因 `io.kotest:kotest-property` 离线不可解析跑不起来，
`:core-ui:compileCommonMainKotlinMetadata` 因 `atomicfu` 离线失败（可移植性靠审阅）。另外三处按计划留下的后续项是
P3 级工作而非本批遗漏：视频控制条整体共享（要先统一播放状态）、浏览器呈现（Android 侧还没有 Compose 浏览器界面）、
备份恢复管线的 inspect 下沉与 `LibraryBackup` 替换语义（11 那两处行为差异的根）。
