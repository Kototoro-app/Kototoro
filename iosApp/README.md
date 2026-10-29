# Kototoro iOS 开发入口

接手开发先读 [iOS 阶段交接记录](../docs/architecture/ios-development-handoff-2026-10.md)，
其中列出调用链、生命周期约束、验证证据与后续工作入口。
当前产品实施与验收状态见 [主要功能实施记录](../docs/architecture/ios-product-implementation-2026-10.md)。
2026-10-29 导航、阅读手势修改和各页面性能检查见
[iOS 导航与性能记录](../docs/architecture/ios-navigation-performance-2026-10.md)：
记录详情推入现有导航栈；主底栏滚动时保持展开，继续阅读保留独立附件；漫画“更多 → 控件”
可关闭“边缘滑动返回”，按作品保存，也可设为全局默认。该轮尚未在 Xcode/真机完成验收。
后续将下载队列的读取/编码/提交与 Widget 缩略图/快照写入移到后台 actor，
缓存自动维护增加空闲合并与扫描间隔；对应并发、持久化和预算回归也需在 Mac 验收。
逐项补全的当前清单见 [iOS / Android 关键功能差距](../docs/architecture/ios-android-feature-parity-2026-10.md)。

本模块已打通 SwiftUI 作品库分类 → Kotlin/Native → 共享 Room v84 数据库，以及可选的
SwiftUI → 共享源协议 → C/JNI → OpenJDK Mobile Zero → JVM source-host 源调用链。
可选预览已支持列表 → 详情/封面 → 章节 → 图片阅读，以及前后翻页和同分支换章。
`ios-shared` 是 iOS 平台装配层，复用 `core-db`、`core-domain`、`core-source`、`core-backup`、`core-net`
和 `core-library`、`reader-core`。Swift 只消费应用接口，不直接操作 Room 实体或 JVM parser ABI。

## 本机运行

需要 Apple Silicon Mac、Xcode、iOS 模拟器运行时、JDK 17+，以及本项目 Android SDK 配置。
首次 Gradle 构建会下载 Kotlin/Native 和 Apple 目标依赖。Xcode 构建阶段使用
[Kotlin 官方 direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)
编译 `KototoroShared` 静态 framework；无需 CocoaPods 或 XcodeGen。

```bash
open "iosApp/Kototoro.xcodeproj"
```

选择 `Kototoro` scheme 和 iPhone/iPad 模拟器，然后运行。JDK 由构建脚本通过 `java_home -v '17+'` 定位。
真机运行时，在 Xcode Signing & Capabilities 中选择自己的开发团队。

命令行构建（仓库根目录）：

```bash
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme "Kototoro" \
    -configuration Debug -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "iosApp/build/DerivedData" CODE_SIGNING_ALLOWED=NO build
```

默认应用支持 CMS 浏览／详情／播放、收藏分类、历史、播放进度恢复与手动更新检测。
浏览页顶部只有一个可收起的紧凑推荐横条，保留现有四个主入口，通过右侧“更多”菜单选择内容：
“为你推荐”展示最多 6 张精选卡片，“网站推荐”展示最多 10 部作品的小封面；卡片约 100 点高，
点击标题进入各自的完整页面。收起后仅保留一行入口，推荐类型与展开状态会保存。
“为你推荐”复用 Android 的标签评分、来源均衡和超时规则。
首次需开启推荐；根据收藏／阅读记录或手动偏好标签生成，支持标签排除、来源优先／排除、成人过滤与内容空间。
推荐保存在共享数据库，进入页面等时机先读缓存，超过六小时或偏好、可用来源变化时重新生成。
推荐完整页支持下拉强制刷新，浏览页横条的“更多 → 刷新推荐”也会绕过缓存时效；全部来源失败或取消时保留缓存。
浏览页本身的下拉刷新更新来源目录，不强制重新生成推荐；推荐收起时不自动抓取。
推荐偏好中的“管理推荐来源”按来源类型分组，支持名称／标识搜索及内容类型、来源类型、语言、推荐状态筛选。
筛选仅影响显示，全部修改在偏好页保存时统一生效，取消则丢弃草稿。
此更新检查仅在前台页面执行，没有接入系统后台任务或推荐通知。
网站内容流可选择追踪网站与分区（热门／高分；Bangumi、AniList、Shikimori、SIMKL 视频支持每日放送及星期选择），
选择会带入完整发现页。首页预览使用十五分钟内存缓存，切换网站、分区或内容空间重新请求，菜单可强制刷新。
两个内容流都按当前内容空间过滤；书籍空间不提供视频放送分区。
Bangumi 使用站点的每周放送表；AniList、Shikimori、SIMKL 按本周所选日期的本地时区边界查询实际排期。
AniList 会读取当日排期的全部分页并按作品去重；SIMKL 支持动画与剧集两个分区，使用官方 v2 月历并处理跨 UTC 月边界。
接口依据：[AniList 排期](https://docs.anilist.co/reference/object/page)、
[Shikimori 日历](https://github.com/shikimori/shikimori/blob/master/app/controllers/api/v1/calendars_controller.rb)、
[SIMKL v2 日历](https://api.simkl.org/api-reference/calendar)。Kitsu、MAL 暂未提供每日放送入口；MangaUpdates 不属于视频放送站。
横条不另设漫画／小说／视频选择，直接跟随顶栏 space；“全部”空间的普通榜单汇总网站支持的类型。
网站作品详情展示封面、站点评分、作品信息、可展开简介、人物及关联作品，并提供“搜索作品并关联”。
该入口直接复用全局搜索，预填网站标题、限定作品类型，保留搜索过滤及单来源“更多”入口；选中结果后返回确认绑定。
关联查询保留临时关键词，不写入普通搜索的最近记录；浏览页持有网站预览，来源使用记录更新后仍保留详情导航。
绑定后打开来源作品详情，可继续阅读／播放或加入收藏；账号进度同步仍单独开启。
全局搜索的筛选按钮位于内容顶部，搜索激活时仍可操作；改变类型、置顶或成人来源筛选会立即更新当前搜索。
“浏览 → 网站发现”与“设置 → 追踪网站”现提供 Bangumi、AniList、MyAnimeList、Kitsu、Shikimori、
MangaUpdates、SIMKL 的目录和账号入口。作品详情的“追踪与资料”可更换展示资料、关联网站记录、编辑状态／评分／进度；
账号页可单独启用阅读后自动同步，凭据保存在 Keychain。真实账号写入回读与完整站点能力仍需验收，
具体范围见[追踪网站增量记录](../docs/architecture/ios-tracking-support-2026-10.md)。
“设置 → Google Drive 同步”支持与 Android 共用的收藏、分类、进度、统计和更新记录协议；
启用前需在 Android 同一 Google Cloud 项目中配置 iOS OAuth 客户端。
配置、同步边界与真实账号验收步骤见 [Google Drive 同步](../docs/architecture/ios-google-drive-sync.md)。
来源列表长按作品可收藏或进入多选（顶栏换为全选／收藏操作栏）；列表与详情页的收藏都弹出可多选的分类框，
批量时显示部分选中，只写入改动的分类，取消不写入。作品库在作品区左右滑动切换分组，横滑不会打开作品。
回归：`-only-testing:KototoroUITests/ProductFlowTests/testBrowseAndDetailsShareMultiCategoryFavouritesAndBulkSelection`（需 CMS fixture）。
详情页头部显示开始／继续阅读按钮；头部阅读区滚出可视范围后，系统底部工具栏才显示章节与阅读入口，
滚回头部即隐藏。显隐使用 SwiftUI 的 `onScrollVisibilityChange`，不依赖固定滚动距离。
标题沿用 Android 头部规则：折叠三行，点标题展开最多六行、再点折回；需要更多行的超长标题两种状态都省略，
不会把阅读按钮挤出屏幕。标题整行宽都可点（不启用 `textSelection`：它会吞掉这个点按，复制改由头部
「复制标题」按钮提供）。附加标题固定两行，与 Android 一致。回归：
`-only-testing:KototoroUITests/LocalImportTests/testLongDetailsTitleExpandsAndCollapsesWithinTheAndroidLineBudget`
（需 `fixture_details_title.cbz`）。
有续读章节时才显示“从第一章／集开始”的菜单；资料卡始终显示作者与状态行，来源未提供时以「佚名」
「状态未知」次要色占位（对齐 Android 的 `unknown_author`／`unknown`），不因字段缺失隐藏整行。
资料卡的「章节／剧集」行沿用 Android 头部的 `current + 1 / total` 读数：未读为 `0/N`，无章节显示 `-`，
点击进入完整目录；漫画、小说与视频都显示该行，不因内容类型隐藏资料卡（视频按所选线路计数）。
回归：`-only-testing:KototoroUITests/LocalImportTests/testDetailsChapterInfoRowShowsUnreadProgressAndOpensTheDirectory`、
`LocalImportTests/testVideoPlaybackCollectsStatistics`（视频行，需 `fixture_video.mp4`）。
资料、简介、章节预览和书签卡片使用系统 `regularMaterial`，正文不叠加 Liquid Glass。
阅读、复制及收藏等操作使用原生玻璃按钮，同组控件置于 `GlassEffectContainer`；
内容卡片在降低透明度时切换为不透明系统背景，边界随增强对比度设置调整。
章节直接展示在详情正文中：漫画／小说显示当前章附近最多 5 条，视频显示最多 8 集；完整目录推入独立页面。
完整章节页使用系统搜索和列表多选，排序／分支／线路／刷新／下载队列集中在菜单，多选下载使用底部工具栏。
漫画章节行提供明确的页面预览按钮与长按入口，推入单章页面网格；返回保留目录状态。
书签有内容时显示横向预览，全部书签使用独立页面；漫画恢复保存的页码与页内位置，小说按文本定位保存的段落。
漫画书签保存独立的页面缩略图，重启与清理阅读缓存后仍可预览；旧书签通过原来源页上下文或离线文件补建图片，
不直接请求保存的图片 URL。小说书签显示正文摘录，沿用共享文本规范解析 Android 备份中的 HTML／Base64 内容。
详情预览、全部书签和阅读器内书签复用同一展示组件；移除漫画书签时清理对应快照，不改变共享数据库结构。
详情主列表保留下拉刷新，目录列表的拖动只负责滚动；已移除自定义 dock 与章节面板手势联动。
回归：`DetailsChapterPreviewTests` 与 `LocalImportTests` 的 `testDetailsShowsChapterPreviewAndPushesTheFullDirectory`、
`testChapterPagePreviewOpensAnExactPageAndReturnsToTheDirectory`、`testFullChapterDirectorySearchAndScrollingDoNotOpenReader`、
`testDetailsChapterSelectionDownloadsWithoutOpeningReader`、`testDetailsBookmarkRestoresPageInsteadOfTheLatestReadingPosition`、
`testDetailsNovelBookmarkRestoresTheSavedParagraph`；
UI 回归需先暂存 picker 夹具。
`fixture_details_layout.cbz` 的长简介用于验证阅读入口随滚动切换、返回目录后的恢复，以及未知资料不占位。
默认构建不包含第三方 JVM/兼容 JAR；下面的开发预览支持扩展仓库、安装、搜索与分页、漫画／小说阅读。
数据库位于应用沙盒 `Library/Application Support/Kototoro/library.db`，不在缓存或临时目录。
不使用破坏性 schema 回退；导入旧版本 SQLite 文件的迁移不属于当前切片。

## 验证

同时打包真机 Arm64 和模拟器 Arm64 的 Debug XCFramework：

```bash
./gradlew :ios-shared:assembleKototoroSharedDebugXCFramework
```

输出为 `ios-shared/build/XCFrameworks/debug/KototoroShared.xcframework`。真机切片构建通过不等于真机运行验收。

先用 `xcrun simctl list devices available` 获取设备 UUID，再执行：

```bash
./gradlew :ios-shared:iosSimulatorArm64Test -PiosSimulatorDevice=<UUID>

xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme "Kototoro" \
    -configuration Debug -destination 'platform=iOS Simulator,id=<UUID>' \
    -derivedDataPath "iosApp/build/DerivedData" CODE_SIGNING_ALLOWED=NO \
    -parallel-testing-enabled NO test
```

Native 测试覆盖数据库重开、空标题拒绝、分类排序／可见性及备份恢复；XCTest 覆盖 SwiftUI 分类操作及重启读取。
来源“最近使用”读取共享使用时间，“使用频率”沿用 Android 的本地作品记录数，重复打开同一作品不累加。
来源“新”标记读取共享加入版本；首次目录建立基线，之后新增来源记录 iOS 构建版本。

漫画阅读器定向回归：`SourceImageFileTests` 验证分块缩放和采样边缘，`ImageLoadPolicyTests` 验证请求优先级与取消；
先执行 `python3 "iosApp/Scripts/stage-picker-fixtures.py" <UUID>`，再运行
`LocalImportTests/testLongPageBookmarkRestoresItsOffset` 和
`LocalImportTests/testNeighbourFailureDoesNotHideReadablePageAndRetriesTheFailedPage`，覆盖长页书签和坏邻页。
Native 的 `IosProductTest` 覆盖 Retina 密度与相机缩放分离、晚到尺寸的阅读锚点补偿。
`ReaderDownloadStoreTests` 覆盖离线清单缓存、坏清单标记、图片替换及页序变更保护；
`LocalImportTests/testContinuousAutoScrollAdvancesToTheNextChapter` 验证纵向条漫自动跨章，
`LocalImportTests/testDisplayPreferenceAndBackgroundPreserveTheLongPagePosition` 验证常亮设置不重排长页、后台保存与重启续读。
离线修复验证需顺序执行 `testPrepareDownloadedReaderFixture`、篡改专用 `fixture_reader_download` 的一张下载副本、
再执行 `testDownloadedReaderFixtureReopensAfterImageRepair`；最后核对副本的长度与 SHA-256 已恢复。
仅修改该测试作品的下载副本，保留导入源与全局图片缓存；两个 UI 用例单独通过不能证明修复链路。
横向条漫支持独立的“横向连续从右到左”设置，以及捏合／双击缩放、放大后的双轴平移。
新作品默认从左到右，旧横向设置保持原有从右到左方向。定向回归：`ReaderSettingsTests`、
`LocalImportTests/testHorizontalContinuousDirectionZoomAndBookmarkRestore` 和
`LocalImportTests/testHorizontalReverseAutoScrollAdvancesToTheNextChapter`，需先暂存上述 picker 夹具。
条漫双击缩放按共享场景的页内锚点（`IosReader.progress`／`scrollOrigin`）重排，不按缩放比例换算场景偏移：
跳转或恢复后上方仍未测量的页面保留一屏估值，比例换算会把阅读页带偏。
`LocalImportTests/testContinuousDoubleTapZoomsAtTheTap` 与 `testContinuousDoubleTapKeepsThePageAfterAJump` 覆盖该契约。
`IosProductTest` 另覆盖横向 LTR/RTL 定位、缩放无关的页内偏移、晚到尺寸补偿及物理视口分块。
分页跨章复用 Android 提取到 `reader-core` 的章节窗口规则：前章末两页、本章、后章前两页。
普通翻页直接进入邻章页，并按页面归属更新章节；进度条、书签和历史仍使用章内页码。
定向回归：`LocalImportTests/testPagedWindowChangesChapterAndStopsAtTheBookEnds`、
`LocalImportTests/testRtlPagedAndAnimatedWindowsChangeChapter`、
`LocalImportTests/testPagedCrossChapterBookmarkKeepsChapterLocalPage`；之后顺序运行
`LocalImportTests/testPagedCrossChapterHistoryReopensWithTheMatchingImage`，核对重新启动后的实际图片。
`IosProductTest` 验证窗口裁剪后尺寸复用及双页章界隔离；
`LocalImportTests/testPagedWindowTrimsNeighborsAndKeepsTheDisplayedChapter` 验证三章窗口裁剪后的实际图片，
`LocalImportTests/testPreviousChapterButtonOpensTheChapterStart` 验证上一章按钮打开章首；
向后翻页跨章进入上一章末页由三章窗口用例验证。两种导航契约与 Android 一致。
连续模式的自然页面接续仍待实现，本轮分页跨章不依赖额外拉动。
`testNewSourceFilterAndRestartPreserveBaseline` 使用两次独立导入验证基线、新来源数量提示、列表／网格及筛选重启恢复。
CMS fixture 的 `usage-sites.json?run=<标识>` 提供两个不同身份的测试站点，用于首次访问保持手动顺序和使用排序验收。
修改服务脚本后需重启本地 fixture 服务，再运行相应用例。
UI 测试每次创建唯一分类，测试数据留在测试设备沙盒中。

本地作品现支持单个视频文件，入口为“浏览 → 视频空间 → 本地作品 → 本地视频 → 导入文件”。
可选 MP4/M4V/MOV/MKV/TS/WebM/AVI，导入前由 AVFoundation 检查实际编码/容器及视频轨道；
不支持的媒体明确拒绝。播放、收藏与进度沿用现有播放器/数据库；再次导入相同文件保留身份与续播。
当前不支持仅导入 M3U8（缺少分片）、视频目录、自动封面或侧车字幕。
本地视频回归（默认原生构建不需要 JVM）：
`LocalImportTests/testImportedVideoPlaysAndReimportAfterRestartKeepsItsPosition` 和
`LocalImportTests/testBrokenVideoIsRejectedBeforeItEntersTheLocalLibrary`。
先运行 `stage-picker-fixtures.py <UUID>`（需已有 ffmpeg），生成 30 秒的 H.264/AAC 视频与损坏文件。
若本机工程默认启用了真机 JVM，模拟器原生验收需显式指定
`KOTOTORO_JVM=0 KOTOTORO_JVM_DIRECTORY= KOTOTORO_JVM_LDFLAGS=`，保留本机工程配置。

如依赖下载失败，可给 Gradle 添加仓库约定的代理参数：
`-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`。
Xcode 构建阶段也会调用 Gradle，可用命令作用域内的 `JAVA_TOOL_OPTIONS` 传入这些参数，或在首次构建前完成下载。

## 苹果 CMS 影视源（默认构建）

浏览页右上角“仓库与扩展”，在添加仓库时选择“苹果 CMS / TVBox”，粘贴苹果 CMS 接口
（如 `https://…/api.php/provide/vod/`）或 TVBox 配置/多仓地址。
站点存入共享 `json_sources` 表（与 Android 同格式），由 `:source-tvbox` 在 Kotlin/Native 中执行，
播放使用 `AVPlayer`／`AVPlayerLayer` 与原生自定义控制栏。type 3/4 爬虫站点与加密配置暂不支持。部署目标为 iOS 26。

本机端到端测试先启动 fixture（需要 ffmpeg，产物在 ignored `iosApp/build/cms-fixture`），再运行 XCTest：

```bash
python3 "iosApp/Scripts/serve-cms-fixture.py" &
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme Kototoro \
    -configuration Debug -destination 'platform=iOS Simulator,id=<UUID>' \
    -derivedDataPath "iosApp/build/DerivedData" CODE_SIGNING_ALLOWED=NO \
    -only-testing:KototoroUITests/VideoSourceTests test
```

未启动 fixture 时该用例跳过。共享逻辑测试：`./gradlew :source-tvbox:jvmTest`。

真实站点（可选，依赖网络）：`TEST_RUNNER_KOTOTORO_REAL_CMS=<接口> xcodebuild … -only-testing:KototoroUITests/VideoSourceTests/testRealAppleCmsSitePlaysOnSimulator test`；
批量探测：`./gradlew :source-tvbox:jvmTest --tests '*OnlineProbe*' -PtvboxOnlineList=<每行一个地址的文件> [-PtvboxProxy=127.0.0.1:7890]`。

## 真机 IPA（本地侧载预览）

发行 IPA 由本机打包后手动上传：
`iosApp/Scripts/package-ipa.sh [--jvm [运行时目录]] [版本] [构建号]`，版本默认取工程的 `MARKETING_VERSION`，构建号默认为提交数；
产物在 `iosApp/build/release-ios/`（`Kototoro-<版本>-ios[-jvm]-unsigned.ipa` 与 `.sha256`），需由侧载工具签名。
不带 `--jvm` 的默认构建没有扩展运行时，只能使用 CMS/TVBox。`--jvm` 默认嵌入 `iosApp/build/jvm-device`，
先用 `build-jvm-preview.py --platform iphoneos --profile "iosApp/build/jvm-real-api"` 生成；脚本拒绝模拟器运行时。
发行包不带预览配置里的样本扩展与 `preview.json`：打包时另存 `iosApp/build/jvm-device-release`，`jars`/`plugins` 为空，
扩展由用户从仓库或文件安装。

应用图标位于 `Kototoro/Assets.xcassets/AppIcon.appiconset`，复用 Android 的 Kototoro 图标。
真机必须使用 `iphoneos` JVM 切片；模拟器的 Arm64 静态库不能直接放进真机应用。
已准备真实扩展预览后，可复用其 Java 模块、兼容库与来源配置：

```bash
python3 "iosApp/Scripts/build-jvm-preview.py" --platform iphoneos --profile "iosApp/build/jvm-real-api"
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme Kototoro \
    -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' \
    -derivedDataPath "iosApp/build/DeviceIPA" -archivePath "iosApp/build/Kototoro-Device.xcarchive" \
    CODE_SIGNING_ALLOWED=NO KOTOTORO_JVM=1 \
    "KOTOTORO_JVM_DIRECTORY=$(pwd)/iosApp/build/jvm-device" \
    "KOTOTORO_JVM_LDFLAGS=$(pwd)/iosApp/build/jvm-device/libkototoro-jvm.a -lz -liconv -framework Security -framework SystemConfiguration" \
    archive
```

将归档中的 `Products/Applications/Kototoro.app` 放入 ZIP 的 `Payload/Kototoro.app/`，文件扩展名为 `.ipa`。
此命令生成未签名的 Debug 侧载预览，需要由侧载工具完成签名，最低 iOS 26。
真机 Arm64 JVM、应用归档和图标编译已通过；真机安装、启动与扩展运行仍须在实际设备验收。

## 嵌入式 JVM 模拟器预览

额外需要 JDK 24。所有下载/源码/构建工具/原生库/兼容 JAR 均在 ignored `iosApp/build` 中；
脚本不创建 Git 分支、不提交、不执行全局安装或清理用户数据。GNU m4/autoconf 安装到本地 build/tools。
固定版本和 SHA-256 见 `Scripts/jvm-preview-pins.json`，OpenJDK 原始许可证保存在本地源码树中。

仓库根目录执行：

```bash
python3 "iosApp/Scripts/build-jvm-preview.py"
python3 "iosApp/Scripts/test-jvm-bridge.py"
```

第一条从固定 OpenJDK 源码构建 **arm64 模拟器 Zero**，使用本项目 `Scripts/openjdk-ios.py` 的 iOS 路径、
Darwin 信号上下文、静态符号和模拟器 MAP_JIT / W^X 适配。符号表从本次构建的静态库生成。
Java 26 模块、时区和证书数据来自同一份本地 OpenJDK 构建，保留其 legal 目录；不从 IPA 提取运行时镜像。
固定的 JDK 26 仅作构建工具，使用 Mobile 修订的 JlinkTask，禁用 JLI 预生成。详见
[`mihon-ios-shims`](../mihon-ios-shims/README.md)。
默认预览只放入自有测试 ABI/扩展；不使用第三方兼容 JAR 或上游 ExtensionHost。
第二条验证同一个 C/JNI 桥的 Unicode、64 位 ID、多线程交换、Java 异常恢复和关闭。

```bash
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme Kototoro \
    -configuration Debug -sdk iphonesimulator -destination 'platform=iOS Simulator,id=<UUID>' \
    -derivedDataPath "iosApp/build/JVMPreview" CODE_SIGNING_ALLOWED=NO KOTOTORO_JVM=1 \
    "KOTOTORO_JVM_LDFLAGS=$(pwd)/iosApp/build/jvm/libkototoro-jvm.a -lz -liconv -framework Security -framework SystemConfiguration" \
    -only-testing:KototoroUITests/SourceRuntimeTests/testIndependentSourceRunsThroughEmbeddedJVM test
```

测试覆盖源枚举、真实 Java fixture 调用、应用终止/重启、详情/章节、图片解码、前后翻页、换章与返回导航。
仍不是生态兼容性验收。
不同预览使用各自的 DerivedData 目录。普通构建若复用含 JVM 产物的目录会明确拒绝，避免残留的第三方产物进入默认包。
含 parser-host 的预览及真机 Release 在 Xcode 嵌入 JVM 资源前运行 `:mihon-ios-compat:stageIosHost`，将当前源码的
iOS 适配器、shims、宿主及项目依赖一起覆盖到应用包内，避免构造方法等 ABI 不匹配；第三方库保持原有版本。
编译使用 `build/mihon-ios-api` 中已校验的 API，可通过 `KOTOTORO_IOS_COMPATIBILITY_DIRECTORY` 指定目录。
修改解析器设置等宿主代码后，直接重新构建应用即可生效。
构建后可运行 `python3 iosApp/Scripts/probe-jvm-preview.py --runtime <Kototoro.app/jvm> --startup-only`，
使用最终包内的同一组 JAR 检查宿主启动与来源枚举，覆盖无预装扩展的 Release 配置。
真实哔咔登录控件回归：`-only-testing:KototoroUITests/ExtensionEcosystemTests/testPicacgSettingsExposeCredentialLogin`，
检查用户名、密码、登录按钮与安全密码输入框，不提交真实账号或发起登录。
fixture 预览只包含自有测试 ABI，扩展管理会禁用真实扩展安装并说明原因。
运行 fixture 测试后，继续真实扩展开发前务必重新安装并启动下节的 `RealApiPreview` 应用；
否则 CopyManga、MH1234 等真实扩展会缺少 Mihon 接口而初始化失败。

## 真实扩展预览（Mihon / Tsundoku / Aniyomi / JAR）

`stage-mihon-preview.py` 现在固定 6 个样本（见脚本顶部 `ARTIFACTS`）：再漫画、Comix、AllNovelFull、AnimeGG、Anime1.me 与
kototoro-parsers 插件 137，在 Mac 上转换后与 parser-host 一起装入 `iosApp/build/jvm-real-api`。上模拟器前可先在 Mac JVM 探测：

```bash
python3 "iosApp/Scripts/probe-jvm-preview.py"                 # 每个生态一个源
python3 "iosApp/Scripts/probe-jvm-preview.py" MANGADEX BILINOVEL FANTUAN
```

模拟器用例：`-only-testing:KototoroUITests/ExtensionEcosystemTests`（构建参数同下方真实 APK 预览）。

## 真实 Mihon APK 预览

```bash
python3 "iosApp/Scripts/stage-mihon-preview.py"
```

该脚本从已校验发行容器提取 AndroidCompat/Suwayomi API 和基础依赖，排除所有 `000-*` shim，启用独立
`:mihon-ios-compat` 和项目自写的 `:mihon-ios-shims`，不提取或加载 boot shims；
下载 SHA-256 固定的再漫画 1.6.0 APK，并调用共享 `:dex-convert:convertApk` 生成 JAR。
没有安装 APK、启动 Suwayomi Server 或调用上游 ExtensionHost。运行时 JAR 以明确的编号维持 classpath 顺序，
保留独立的 serialization-json-okio 与 coroutines-jdk8 适配包；共享基础 Kotlin/JSON/coroutines 使用本项目版本。
生成目录为 `iosApp/build/jvm-real-api`，`runtime-origin.json` 记录 OpenJDK 构建来源，`inventory.json` 记录产物摘要。
`00-kototoro-ios-shims.jar` 首先加载，通过本项目 JNI 桥使用 ImageIO、CoreText、CoreGraphics 与 JavaScriptCore。
独立验证这些能力：`python3 "iosApp/Scripts/test-ios-shims.py"`。现有基础依赖仍按各自许可使用，自写 shim 不重新许可它们。

```bash
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme Kototoro \
    -configuration Debug -sdk iphonesimulator -destination 'platform=iOS Simulator,id=<UUID>' \
    -derivedDataPath "iosApp/build/RealApiPreview" CODE_SIGNING_ALLOWED=NO KOTOTORO_JVM=1 \
    "KOTOTORO_JVM_DIRECTORY=$(pwd)/iosApp/build/jvm-real-api" \
    "KOTOTORO_JVM_LDFLAGS=$(pwd)/iosApp/build/jvm-real-api/libkototoro-jvm.a -lz -liconv -framework Security -framework SystemConfiguration" \
    -only-testing:KototoroUITests/SourceRuntimeTests/testConvertedRealMihonApkRunsOnSimulator test
```

此测试访问真实源的公开在线列表，依赖站点与网络可用性。2026-10-10 已验证再漫画
`MIHON_524579092615598717` 的 APK 转换、模拟器内枚举及第一页列表，返回 10 项。
继续验证《大理寺日志》详情与封面、392 个章节，以及首章两张真实图片的 SwiftUI 阅读/翻页。
JNI 复用常驻 JVM/Java 会话；Java 的临时目录与数据目录位于应用沙盒，偏好文件由既有 store 持久化。
`IosSources` 复用 `core-source/SourceProtocolClient`，Swift 不重复实现版本/请求 ID/错误/64 位 ID 协议。

APK 转换期间修复了 dex2jar 2.4.38 的 `java/util/Object` 公共父类型回退错误，仅纠正栈帧，
不改写指令/字段/字符串；转换缓存版本现为 5，新增不依赖 `jdk.zipfs` 的目录输出／ZIP 流式封装，
支持在移动 JVM 内转换 APK，已有桌面消费者同步获益。
最小 DEX/JVM 回归覆盖外部类型不在转换 classpath、实际运行时两条分支均可验证并执行。

### 仓库、设备安装与记录闭环

浏览页的“仓库与扩展”可粘贴 repo-hub 的 `kototoro://add-repo?url=…&kind=…`，也可从系统打开链接，
或直接从系统文件选择器导入本地 APK／JAR 扩展包。
支持 Mihon、Aniyomi、Tsundoku、JAR 仓库与 TVBox/CMS 地址，扩展转换与注册在当前移动 JVM 会话中执行。
仓库、托管安装、收藏、历史、漫画／小说书签和阅读／播放进度会在重启后恢复；记录页支持手动检查收藏更新。
记录页只保留统一时间轴，阅读、更新与下载通过日期右侧的独立按钮筛选，不再提供三个独立页面。
阅读统计卡默认收起图表和详细统计，展开后可查看活动与阅读时段；下载筛选下的“下载列表”进入任务列表。
顶部概览汇总阅读时长、更新章节和已完成下载；日期右侧的独立按钮可筛选阅读、更新或下载，概览随之切换。
三类记录使用同一日期范围与内容空间／搜索条件；下载按各章完成时间统计，进行中的任务单独在下载队列管理。
下载列表包含所有空间的任务；时间轴因内容空间筛选为空、但其他空间存在匹配下载时，会说明原因并提供“显示全部”。
阅读事件显示当次章节／剧集进度；漫画、小说更新可直接加入现有下载队列，跳过离线完成及已排队章节。
旧日志中的章节名称无法唯一匹配时提供目录选择入口，视频更新沿用详情与播放入口。

扩展管理使用原生“扩展／仓库”分段列表。工具栏的“＋”添加仓库或从文件安装；
支持名称／来源／语言搜索、生态与语言筛选、安装状态筛选与更新分区。
语言筛选常驻在安装状态分段下方，支持多选和单独重置；勾选中文与英语时显示匹配任一种语言的扩展。
菜单保持展开以便连续勾选；未选语言时显示全部，多来源扩展按包含的语言参与匹配。
语言多选、生态与安装状态使用 `@AppStorage` 保存，返回页面或重启应用后恢复；添加／刷新仓库也保留选择。
“重置”仅清空语言，“清除筛选”同时恢复语言、生态与安装状态的默认值，并保存重置结果。
点击扩展行可查看版本、仓库及包含的来源；已安装扩展可重新安装或卸载，仓库支持刷新与移除。
多仓库同包按生态分别合并并选择最高版本；单仓库详情显示其实际版本，并可安装该仓库提供的版本。
仓库安装与文件导入直接使用已有设备转换和安装流程，运行时错误由实际操作反馈；仓库刷新期间可继续添加仓库。
扩展名称优先采用扩展／仓库的可读名称，缺失时使用所含来源名称；已安装扩展的展示信息单独保存。
仓库图标缓存到本地并缩小解码，仓库不可用或移除后保留已知图标；缺图时使用名称首字占位。

确定性安装回归使用原始 APK，设备负责转换；本地测试服务器：

```bash
python3 "iosApp/Scripts/serve-repository-fixture.py"
```

同时启动前述 CMS fixture 后，使用真实 JVM 预览构建参数运行
`-only-testing:KototoroUITests/ProductFlowTests -collect-test-diagnostics never test`。
同一仓库服务器提供 `/ui/repo.json` 浏览样本；默认构建的
`RepositoryManagementTests` 验证添加失败后恢复、搜索、状态分段、仓库导航与扩展详情。
`ExtensionPresentationTests` 独立覆盖版本合并、跨生态身份、旧 host 元数据、成人管理与筛选规则。
漫画 fixture UI 回归也验证章节／更多面板、书签、下载、换章和重启续读。
漫画下载使用应用级持久化队列：设置 → 下载，或阅读器的章节／更多 → 下载队列可暂停、继续和重试。
退出阅读器后前台任务继续；进入后台暂停，进程中断的任务重启后等待手动继续。
每页保存检查点，继续时校验已有图片，整章完成后发布离线清单；移除任务保留已下载文件。
设置 → 存储管理（下载队列内也可进入）分别显示漫画/小说离线章节与页面、封面、来源/扩展图标缓存。
章节名称由下载时的轻量元数据保留，移除任务后仍可管理；旧版无关联目录按日期和占用显示。
删除离线章节保留本地导入作品、收藏与进度；清除缓存保留离线章节。当前阅读窗口与实际未结束的
下载/图片请求会阻止冲突清理。整章删除先暂存，队列写入失败则回滚；重启恢复未提交操作，
已提交但未清理完的文件显示待清理占用并在下次启动重试。
缓存上限默认 512 MiB，可选 256/512/1024/2048 MiB 或不限；文件空闲时按修改时间清理较旧文件。
统计为这些文件的逻辑字节数，未包含数据库、导入文件、HTTP 缓存、运行时和安装包；该上限为软预算。
定向回归：`ReaderStorageTests` 验证真实文件/队列回滚与恢复、符号链接、小说离线元数据及请求隔离；
`LocalImportTests` 的三个 `testStorage…` 用例验证清理/删除/重开、移除任务后管理与阅读保护/上限持久化。
队列回归：`-only-testing:KototoroUITests/ReaderDownloadQueueTests`，测试实际生产队列的串行性、迟到回调、
重启／失败恢复与文件写入失败。源请求已进入 JNI 后仍须等源超时或请求结束，当前暂停为页面边界上的协作取消。
这些证据为 Apple Silicon 模拟器验证，不代表真机／发布包已验收。

小说阅读控件采用与漫画相同的分布：上方返回／章节目录／更多，下方章节切换和进度。
“更多 → 快捷”打开书签、排版、下载等操作；“更多 → 控件”配置四种悬浮按钮，可全部关闭，重启保留。
点击正文中央收起／显示控件；分页文本不会因此重新排版。
“上下边距”作用于正文布局区域，上方另外避让挖孔，页脚位于下边距内，不额外扣减正文高度；
默认 36 pt。字形末行下方的剩余空白取决于实际行高，不能作为设定值的测量边界。
定向 UI 回归：`LocalImportTests/testNovelFloatingActionsPersistAndOperateFromTheMorePanel`、
`testNovelPagedChromeHidesWithoutChangingTheDisplayedText`、`testNovelPagedTextMarginsAreSymmetricAndContainTheFooter`。
夹具由 `stage-picker-fixtures.py` 生成；边距用例测量实际布局坐标，不依赖写死的挖孔尺寸。

详情、章节与页对象在 Kotlin 内保留完整 `SourceContent` / `SourceChapter` / `SourcePage`，包括 opaque
字段和原生页面索引；Swift 不重建请求模型。翻章复用 `SourceChapterNavigation` 的来源/分支规则。
封面与正文图片均由 JVM 源客户端下载并物化，保持扩展自己的 headers/interceptors/cookies，
Swift 读取同一沙盒图片目录中的文件，校验路径、长度、SHA-256 后使用 ImageIO 在后台解码。
基础预览图层按 `ImageDecodeSize`（4MP 像素预算、16384 纹理上限）计算采样尺寸，阅读器持有有限的当前页与邻页解码窗口；
同时支持 `reader-core` 宽页左右拆分（`PageSegment`）、白边自动裁切（`ReaderEdgeDetection`）与超长图可视区域分块叠加解码（`VisibleImageTiles`）。

新增 Native 回归验证完整隐藏字段、阅读器分块／裁边／宽页拆分及图片身份校验；`SourceImageFileTests` 验证实际 PNG 解码、篡改、
长度错误、路径越界、符号链接、超限、无法解码的数据，以及尺寸探测、区域降采样解码、宽页切分与像素采样。可单独执行：

```bash
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme Kototoro \
    -configuration Debug -destination 'platform=iOS Simulator,id=<UUID>' \
    -derivedDataPath "iosApp/build/DerivedData" CODE_SIGNING_ALLOWED=NO \
    -only-testing:KototoroUITests/SourceImageFileTests test
```

## 作品资料与追踪网站

详情页标题下方的“追踪与资料 / 追踪 · 网站名”是常驻入口。点击后按当前作品名自动检索，
可按作品类型切换七个网站；结果右侧的“使用”直接应用资料，点击结果本身可先预览。
应用后返回详情，标题、高清封面、简介、作者、标签和评分按网站资料展示，缺失字段保留源信息。
“恢复源信息”一键恢复显示；已关联资料保留在菜单中，可再次切换或解除关联。
浏览 → 网站发现与设置 → 追踪网站提供独立的公开作品检索，默认网站会保留。
“我的账号”可连接网站、查看远端列表；作品关联后可编辑状态、评分、进度与支持的备注。
自动同步默认关闭，在账号页开启后，仅同步明确开始追踪的作品。

绑定沿用 Android 的 `tracking_site_items` / `tracking_site_links` 与 `preferences.metadata_source_*`，
没有新增 schema。作品身份、源请求的 opaque 数据、章节、收藏和阅读进度仍归内容源；
资料仅在展示时覆盖，作品库与历史也读取同一选择，重启后恢复。
网站封面通过原生 HTTP 加载，封面缓存按来源、作品、封面 URL 及请求方式隔离。

共享部分：`:core-net` 承载公开网站协议与解析，Android 的 Bangumi 搜索/详情也消费同一协议，
保留原客户端与镜像设置；`:core-domain` 提供 Android/iOS 共用的手动关联规则；
`:core-library` 提供事务性绑定、选择与展示投影。账号协议与章节编号算法也由共享层承载，
SwiftUI 负责选择、授权、进度编辑与同步反馈。具体能力及尚未验收范围见
[追踪网站增量记录](../docs/architecture/ios-tracking-support-2026-10.md)。

回归：`:core-net:jvmTest` 验证请求、分页、类型、解析与失败/取消；
`:ios-shared:iosSimulatorArm64Test` 中的 `IosTrackingTest` 验证资料覆盖、数据库重开、
绑定替换、恢复、解绑、身份冲突及章节/进度/阅读偏好保留。
准备前述本地文件夹具后，运行
`-only-testing:KototoroUITests/LocalImportTests/testMetadataIsVisibleAndSearchStartsWithTheWorkTitle`。
可选的真实 Bangumi 验收使用 `TEST_RUNNER_KOTOTORO_TRACKING_ONLINE=1` 配合
`-only-testing:KototoroUITests/LocalImportTests/testOnlineMetadataCanBeUsedInOneTapAndRestoredAfterRestart`，
覆盖直接使用、重启恢复与一键恢复源资料，依赖网站和网络可用。

## 接续范围

1. 在真实超长条漫源与真机内存压力下补充端到端阅读器性能验收。
2. 接入 WKWebView、WebKit Cookie、JavaScript 与图形 native 桥，扩大代表源兼容验证。
3. 补充真机 Zero 构建/启动、内存和代表源性能数据；模拟器测试不替代主方案 G0/G2。
4. 完成运行时/兼容层的分发与许可证 S3 门槛，以及后台更新检测与系统后台下载调度。

[上游 IOS_PORT.md](https://github.com/az4521/TachiyomiAZ/blob/master/IOS_PORT.md) 记录了 OpenJDK 模拟器
切片对 `MAP_JIT`、W^X 与 `pthread_jit_write_protect_np` 的专用补丁；接入 JVM 时需要逐项核对。

第三方源码/运行时仅用于本地可选开发预览，没有加入仓库或默认发行包。WKWebView/完整 Cookie 同步、
JS/图形与备份恢复仍待实现；
当前图片阅读与模拟器回归不能代表全量源或真机兼容性。Swift Task 取消会丢弃过期结果，
尚不能中断已进入同步 JNI 的网络调用；调用仍受 JVM 源客户端的超时约束。
