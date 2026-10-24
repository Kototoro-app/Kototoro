 CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Kototoro 是一个开源的 Android 应用，将漫画、小说和视频整合到一个阅读器中。核心特性包括：
- 本地 OCR + 机器翻译（ML Kit / PaddleOCR / Onnx 气泡检测 + 翻译 API 目录与 Gemini 端到端）
- 视频增强与播放（Anime4K / NCNN（RealCUGAN、Real-ESRGAN）超分、DLNA 投屏、弹幕、字幕/音轨）
- 多平台进度追踪（MAL、Kitsu、AniList、Bangumi 等）
- 广泛的图源支持：Mihon、Aniyomi、IReader、Legado、TVBox、Cloudstream3、Tsuki 扩展 + 动态解析器
- 自定义媒体空间（Spaces）：内置漫画/小说/动漫空间，支持按内容类型、语言与来源自定义浏览范围
- 动态 UI 插件系统（通过外部 classloader）
- 纯 Kotlin 实现的 OTA 增量更新（bspatch）
- WebDAV 多设备同步

# 开发和调试工具

除 Gradle 构建和测试外，优先使用官方 `android` CLI 完成设备/模拟器相关的开发与调试；它能补充 Gradle 不擅长的项目元数据发现、APK 部署、设备交互、UI 层级检查、截图、AVD 管理以及官方 Android 文档检索能力。Gradle 仍是本项目构建和测试结果的权威来源。

```bash
# 检查 CLI 和 SDK 环境
command -v android
android --version
android info

# 分析项目并定位构建目标/APK
android describe --project_dir=.

# 构建后部署并以调试模式启动（可按需指定设备、Activity 或 APK）
./gradlew :app:assembleDebug
android run --debug
# android run --debug --device=<serial> --activity=<activity>
# android run --debug --apks=<path/to.apk>

# 设备和 UI 调试：优先结构化层级，WebView/动画等场景再看截图
adb devices
android layout --pretty
android layout --diff
android screen capture -o /tmp/kototoro-screen.png
adb shell input tap <x> <y>
adb shell input swipe <x1> <y1> <x2> <y2> 500

# AVD 管理与官方 Android 知识库
android emulator list
android emulator create --list-profiles
android emulator create <profile>
android emulator start <avd-name>
android emulator stop <avd-name>
android docs search "<Android topic>"
android docs fetch <kb://...>
```

Android CLI 是面向 Agent 的 Android 终端工具层，而不是另一个 AI 编程模型。Google 官方博客将它与 Android skills、Android Knowledge Base 一起作为 Agent 工作流套件：CLI 负责环境/SDK、项目、设备和部署；skills 提供任务专用指导；Knowledge Base 通过 `android docs` 提供持续更新的 Android、Firebase、Google Developers 和 Kotlin 官方上下文。它可以补充 Gradle、ADB，但不能替代 Gradle 的构建/测试权威性。

官方博客强调，Android CLI 可以创建设备、运行应用并帮助 Agent 导航 UI，也适合 CI、维护和脚本自动化。它不是只能用于新项目；现有 Kototoro 项目应优先使用 `android describe` 发现目标，再结合 Gradle 构建和 `android run` 部署。完成快速原型或终端调试后，仍可转入 Android Studio 做视觉编辑、深度调试和高级性能分析。

当前版本没有 `android doctor`、`android inspect` 或独立的 `android journey` 命令；先运行 `android help`，以实际安装版本暴露的子命令为准。Journeys 是 Android CLI/Agent 的测试工作流概念，应在运行中的应用上按步骤执行并验证结果，而不是直接假设存在同名 CLI 子命令。`android emulator create --list-profiles` 可查看当前版本支持的设备 profile。

使用 `android screen capture` 或带标注截图时，必须先实际查看 PNG，再依据截图操作；对 UI 变化优先使用 `android layout --diff` 以减少无关输出。需要更新 CLI 时运行 `android update`；`android init` 用于初始化环境并安装用户级 Android CLI skill。若 CLI 或 Gradle 下载在当前网络环境失败，可在适用的命令/Java 参数中使用本地代理 `127.0.0.1:7890`。不要将 APK、截图、CLI 缓存或本地配置提交到仓库。

## 构建和测试命令

### 基础构建
```bash
# 构建 debug APK（applicationId 后缀 .debug）
./gradlew :app:assembleDebug

# 构建 release APK（需签名，R8 混淆 + 资源压缩）
./gradlew :app:assembleRelease

# 构建 nightly APK（后缀 .nightly，版本号基于日期自动生成）
./gradlew :app:assembleNightly

# 仅编译 Kotlin 代码（最快的验证方式）
./gradlew :app:compileDebugKotlin

# 本机完整编译命令（需 Java 17 + 代理）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
./gradlew :app:compileDebugKotlin \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890

# 清理构建
./gradlew clean
```

### 测试
```bash
# 运行所有 JVM 单元测试
./gradlew :app:testDebugUnitTest --no-daemon

# 运行单个测试类
./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.ClassName" --no-daemon

# 运行单个测试方法
./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.ClassName.methodName" --no-daemon

# 运行设备/模拟器上的 instrumented 测试
./gradlew :app:connectedDebugAndroidTest

# 运行所有检查（lint + 测试）
./gradlew :app:check
```

### 文档
```bash
# 启动本地文档站点（VitePress）
npm ci && npm run docs:dev

# 构建静态文档
npm run docs:build
```

### 版本信息
```bash
# 获取版本号
./gradlew printVersionName
./gradlew printVersionCode
```

## 技术栈

- **Kotlin** 2.4.0 / **AGP** 9.3.1 / **Gradle** 9.7.0（使用 `gradle/libs.versions.toml` 版本目录）
- **compileSdk** 37 / **minSdk** 26 / **targetSdk** 37
- **JVM 目标**: Java 11（开启 desugaring 以使用现代 API）
- **UI**: Jetpack Compose（BOM 2026.08.00，ui 1.12.0）+ Material3（1.5.0-alpha26 覆盖 BOM 的 1.4.0）+ ViewBinding（混合过渡期）
- **数据库**: Room 2.8.4，KSP 代码生成
- **DI**: Hilt/Dagger（dagger 2.60.1，androidx.hilt 1.3.0）
- **网络**: OkHttp 5.4.0
- **原生代码**: `app/src/main/cpp/CMakeLists.txt`（CMake 3.22.1，4 种 ABI）
- **序列化**: kotlinx.serialization 1.11.0（json / protobuf / json-okio 同版本）
- **测试**: JUnit5 + Kotest + MockK + MockWebServer

注意：`app/build.gradle` 使用 Groovy DSL（非 Kotlin DSL），Gradle wrapper 为 9.7.0。

## 项目架构

### 模块结构
- `app/` - 主应用模块，包含所有功能实现（compose + view）
- `parser-api/` - 共享的解析器接口定义（KMP：纯叶子模型在 `commonMain`；`Content*` 等带二进制兼容垫片的类和 `org.koitharu.kotatsu.parsers.*` 外部 ABI 在 `androidMain`，不要改动其 JVM 签名）
- `core-db/` - 共享 Room 数据库（KMP）：`MangaDatabase`、实体、DAO 在 `commonMain`，85 个旧迁移在 `androidMain`；包名不变，仍是 `org.skepsun.kototoro.core.db` 等
- `core-domain/` - 共享业务规则（KMP）：身份键、同步合并/导入、更新计数、四类列表快照与派生、书架、排序/浏览分组，
  换源写入计划/章节映射、阅读进度/历史章节恢复与标题匹配。筛选复用 `core-db/ListFilterCriteria`，
  黑名单通过 `TagBlacklist` 消费平台词库；标签目录 `TagTaxonomy`（334 个永久 ID、别名索引与繁简标签钩子）
  也在此模块，平台只注入简→繁转换器。Android 资源、追踪 UI 枚举、语言分支策略与
  Unicode/Locale 适配留在 `app`；收藏快照使用持久化状态名称，快照和进度的 Compose 不可变约定由
  `app/compose-stability.conf` 保持，进度模式枚举的偏好反射合约由 `app/proguard-rules.pro` 保持。
  授权、事务、仓库、扩展安装与 Hilt 仍在 `app`；Google Drive 与 WebDAV HTTP 协议进入 `core-net`。
  `SyncContentStore` 保留既有存储边界，
  `MigrationContentInput` 隔离 parser JVM 模型，`TitleMatchingRules` 通过函数回调消费平台文本处理
- `core-backup/` - 共享备份格式（KMP）：Kototoro 备份数据类（`backups/data/model`）、Mihon/Aniyomi/Usagi 格式模型与 protobuf 解码（`backups/external`，入口 `readExternalBackupBytes`、`decodeMihonOrAniyomiBackup`）；包名不变。依赖 Android 的部分留在 `app`：`Uri` 读取与 Venera(SQLite) 解码（`ExternalBackupDecoder`）、导出服务、`BackupRepository`、`BackupIndex(...)` 工厂函数（需要 `BuildConfig`）
- `reader-core/` - 阅读器几何/场景语义（KMP，纯 Kotlin）；PagedCameraTransform 提供独立于进度的
  指针锚点缩放/平移限界，复用 PagedPanBoundsResolver 与 PagedSlot 视口反算，Windows 已消费。
- `reader-novel/` - 小说阅读器共享模型/算法（KMP，纯 Kotlin，含 iOS 目标）：章节文档块 `NovelDocumentBlock`、段落切分、
  图片占位解析、标题行判定、翻译模型、分页切行 `splitNovelPageLineRanges`、阅读模式枚举，以及 HTML→文本分词转换
  `novelHtmlToText`（Android EPUB 与 iOS 阅读器共用；Android 在线正文仍走 `NovelContentLoader` 旧路径）、书籍样式
  `novelHtmlToStyledText`/`NovelStyleSheet`（CSS 子集，纯文本不变）、进度换算与分页源；包名仍为
  `org.skepsun.kototoro.reader.novel`。测量/渲染/存储/资源留在平台；测试在 commonTest，JVM 与 iOS 模拟器都跑。
- `core-source/` - 共享源契约（KMP）：独立协议 DTO、`SourceRuntime`、带版本/请求 ID 的 JSON
  `SourceEndpoint` 与 `SourceProtocolClient`。64 位 ID/时间戳用十进制字符串，保留 `sourceData`、
  页面上下文、筛选和分页语义；不依赖 parser JVM ABI 或 DI。`app/core/source` 的 Android 适配器
  复用现有 `ContentSourcesRepository` 与 `ContentRepository.Factory`，通过 Hilt 装配 JSON 入口，
  Android UI 的源调用链不变。新增图片执行协议与原始 Page context，文件产物只传 hash/相对路径；
  执行/物化由 source-host 提供。`SourcePreferenceStore` 提供六种原生偏好类型、编辑与监听契约。
  `preferences/updatePreference` 提供原生设置控制树/类型更新协议，支持 revision/拒绝/持久化失败结果。
  此模块不提供 JAR 安装、JNI 或 WKWebView 桥。
  独立 cover 操作返回 SourceCoverArtifact/contentId，章节 image 保留 pageId；能力默认关闭，旧 host
  显式 unsupported。Android/host 的既有封面 Referer 补充策略在 MihonModelRules 共享。
- `source-host/` - JVM 源 host：独立文本 manifest/SHA-256/字节码检查器与
  `MihonJarRegistry`；加载前验证 package/version/hash，展开 SourceFactory，保留 `MIHON_{id}`，
  使用宿主初始化的兼容 ClassLoader。默认依赖/发行目录不打包 Suwayomi、AndroidCompat、GPL 驱动或扩展。
  `:source-host:test` 用独立 Java JAR fixture，`installDist` 生成本地 CLI；`inspect` 可独立运行。
  `MihonSourceRuntime` 已接入 `SourceEndpoint`，支持列表/搜索/详情/章节/页面及图片 URL 解析，
  调用真实 suspend/Rx 默认 ABI，保留 memo、页面上下文和 Android 的 ID 规则。
  `getDynamicFilters` 返回完整标准 FilterList 控件树，`SourceFilter.dynamicFilters` 传输类型化状态；
  `MihonFilterRules` 与 Android 共享原标签键/分组/状态规则。过滤状态在真实回调结束后恢复，
  取消后的后续源操作（含另一个 runtime 实例）等待旧回调；重载 generation 独立。
  可选 `SourceImageStore` 启用章节图片 fetchImage：调用扩展 getImage/imageRequest/client，
  post-interceptor body 流式物化到 SHA-256 文件，迟到响应/取消也关闭并保留 loader lease。
  封面 fetchCover 尝试扩展 imageRequest，章节上下文失败时回退 headers GET；原 client 的公开
  enqueue/Callback 处理取消与迟到响应，loader lease/来源等待保留到回调结束，复用图片物化与关闭规则。
  自定义未知控件只声明 UNSUPPORTED；偏好高级输入/动作、相关推荐、完整图片格式、JNI/iOS 桥待实现。
  `FileSourcePreferenceStore` 提供持久化类型快照；`MihonPreferenceBridge` 实现兼容层的 SharedPreferences
  接口。配置 preferenceDirectory 要求 `SourceHostPreferencePlatform` 在扩展构造前绑定 Application。
  `SourceHostPreferenceUiPlatform` 进一步提供真实兼容 Context；`MihonNativePreferences` 保留标准控件并执行
  setup/callChangeListener/saveNewValue/commit，revision 验证在回调前；同步回调共用源 mutex/loader lease。
  Windows 同时纳入 KMP 目标；`serve <config.json>` 提供常驻 UTF-8 JSONL 调试入口，复用同一 JVM/registry。
  `SourceHostPlatform` 负责兼容层初始化/关闭；config 的相对路径基于其目录，stdout 保留协议、日志转 stderr。
  默认发行包不提供第三方 API；可选 `mihon-desktop-compat` 提供基础初始化 provider，完整 Windows
  平台能力/完整浏览器兼容/分发门槛仍待；桌面 UI 与本地 EXE/ZIP/MSI 已接入。见模块 README 与计划 §25–52。
- `mihon-ios-compat/` - 可选 iOS（Zero JVM）平台 adapter（`-PwithMihonIosCompat`）：固定 Mobile API 编译，复用
  `mihon-desktop-compat` 的 Aniyomi ABI 源码，经 `SourceHostRuntimePlatform` 提供 parser-host 的 JAR 路由，见计划 §91–94。
- `mihon-desktop-compat/` - 可选 JVM 平台 adapter（`-PwithMihonDesktopCompat`）：真实本地 AndroidCompat/
  Suwayomi API 为 compileOnly，五个关键产物验证 SHA-256；独立 Application 委托持久化偏好，
  装配真实 AppModule/NetworkHelper、兼容主 Looper、Koin/Injekt 与 CookieHandler。公开零参数
  `MihonDesktopPlatform` 可供 JSONL serve 反射装配；`runSourceHost` 提供 Gradle 调试入口。
  外部 API 需要 Java 21+，自己的字节码仍为 11；toolchain 默认 21，本机可用属性选择 25。
  一 JVM 一个生命周期，close 释放已知客户端/Looper/自有全局状态；SDK observer 仍依赖进程退出。
  不使用研究 bootstrap/stock CustomContext；显式配置桥后接 WebView2 HTML/JS、请求头/UA、原始字节
  POST、HTTP(S) base URL 和 SDK Cookie 双向同步，回调使用兼容主 Looper。平台初始化从原 cookie_store
  快照补回固定 SDK 漏掉的域名记录；新浏览器首次同步清除 SDK 已删除的 profile Cookie。
  桥提供可见可缩放窗口，关闭按钮隐藏、owner 释放；窗口控制可在导航中执行，句柄保持稳定。
  Cookie 删除 RPC 确认目标消失后返回，最长等 2 秒，超时明确报错。
  桌面 Session 显式启用 cf-mitigated GET/HEAD 人工交互，确认后共享 Cookie 并只重试一次；超时/取消
  释放窗口，JSONL 平台默认关闭。SDK 私有 client/cloudflareClient Lazy 委托绑定集中在固定版本 adapter，
  保留原 CloudflareInterceptor 实例，在其后接桌面验证与响应保护，避免触发 server-only handler；
  保留 SDK 客户端其他配置，升级须重验私有 ABI。
  完整 WebView API/真实挑战/代表源仍待；资源/系统服务明确 unsupported，HTTP cache 仍在 SDK 临时目录。
  默认 Android/source-host 发行不含第三方 API；Windows 预览锁定 29 个选入外部 JAR，拒绝
  成员/哈希变化，按编号维持打包后的类优先级。Compose 资源目录可定位随包桥，S3 待完成，见计划 §33–52。
- `source-tvbox/` - 苹果 CMS / TVBox type 0/1 的共享 `SourceRuntime`（KMP 纯 Kotlin，HTTP 由平台注入）：配置导入、
  JSON/XML 解析，存储格式、`JSON_TVBOX_` ID、`tvbox://` URL 与 ID 哈希同 Android；iOS 默认构建经 Ktor Darwin 原生执行，见计划 §93。
- `parser-host/` - JVM 上加载 kototoro-parsers / kotatsu-parsers / UMA（Tsuki）插件 JAR：子优先 classloader、
  宿主共享 ABI、`ParserSourceRuntime`（OFFSET 分页、小说 `getChapterContent`）；真实插件用 `-PkototoroParsersJar`、
  `-PdexPluginJar`、`-PparserHostOnline` 选择性验证。
- `dex-convert/` - Android DEX/APK → JVM JAR（dex2jar 2.4.38 + `computeFrames` + `dontSanitizeNames`），
  `DexRepair` 在转换前修复 R8 内联构造器（`new-instance T` + 祖先 `<init>`），`ApkManifestReader` 解码二进制清单，
  `ApkExtensionConverter` 产出带文本清单的扩展 JAR；`-PrealApkDirectory` 用真实 APK 验证并逐类链接校验。
- `desktop-player/` - libmpv（JNA）播放器：`MpvPlayer` 可嵌入原生窗口或无输出运行，`MpvLocator` 运行时查找
  `libmpv-2.dll`（不随仓库分发）；`-PlibmpvDirectory` 启用真实播放测试。
- source-host 的 `MihonJarRegistry` 同时承载 Mihon、Tsundoku（`TSUNDOKU_<id>`，`getChapterContent`）与 Aniyomi
  （`ANIYOMI_<id>`，`AniyomiSourceRuntime`；animesource ABI 移植在 `mihon-desktop-compat`）。- `desktop-runtime/` - Windows/JVM 平台存储装配：持久化数据根目录、共享 v84 `MangaDatabase` builder、
  bundled SQLite、图片与偏好 store；没有平行 schema/DAO。`DesktopRuntime.open` 验证 schema 后交付实例，
  幂等 close 与取消交付清理；driver owner 回收 Room 配置失败未入池的 native 连接。
  `installDist` 生成可运行的 `storage <directory>` CLI，输出明确 UTF-8 JSON 并关闭资源。
  `DesktopLibrary` 将源 DTO 写入现行 manga/标签/章节/收藏/历史表，用户状态直接归属 manga_id。
  DesktopLibraryBackup 复用 core-backup 导出/合并恢复收藏分类、收藏、历史、书签、统计、内容与来源排序；
  ZIP 私有预览/CRC、流式预算、完整事务回滚、分类映射和历史 tombstone 保护见 §53–54。
  书签使用完整主键，统计会话计数取较大值，旧 Work 状态按锚点投影；不重建实体体系。
  运行时沿用 Room 2.8.4/SQLite 2.6.2；其他备份节/外部格式与同步/高级控件/完整兼容层仍待，见计划 §30–35、§53–54。
- `desktop-app/` - 可选 Windows Compose Desktop 外壳（`-PwithDesktopApp`，同时启用 compat）：
  对照 Android 平板的 80dp 主导航、浏览来源栏、2:3 封面网格和 1000dp 详情分栏；辅助功能集中于更多。
  阅读器独占内容区，支持工具栏收起、页码滑条、章节/选项/书签侧面板和原生窗口全屏。
  收藏与历史提供快照搜索、分类/来源/进度/标签/出版状态/下载/宏（新章节·收藏·18+）筛选抽屉及排序
  （各多选组与 Android 同为组内“或”、跨组“且”，下载为三态单选）；收藏卡片沿用 Android 的封面右上角
  新章节数字角标（读完的作品与历史页不画）；书签可跨章恢复页码与连续滚动位置，
  详情书签标签支持“选择书签”多选删除（对应 Android 长按）；收藏页的“分类管理”对话框可新建/重命名/
  调序/删除分类，增删改查放在 `core-library` 的 `SourceLibrary`（删除连带该分类的收藏记录），
  见计划 §59–61、§131–134。
  .pb/新版 JSON 仓库添加/列表/搜索、JAR 安装/更新/导入及身份恢复；新产物保存到应用管理的 SHA 目录。
  仓库可“忘记”（只删索引条目，已安装扩展与 JAR 保留，重新添加即恢复）。
  来源浏览/搜索、详情、收藏、分页图片阅读、历史与源设置，以及可选网页/HTML/JS 调试。
  源设置按 Android 折成带标题的 section（`host:` 行置默认节、有标题 GROUP 各自成节），开关整行可点、
  选项/多选/文本/动作行显示当前值并开对话框，行组件在 `core-ui` 的 `core/ui/settings/SharedSettingsRows.kt`
  与 Android/iOS 共用，见计划 §135。推荐设置另有独立页面（更多 → 推荐设置）：推荐/标签/来源三节，
  每个已装来源的“优先/排除”改为多选对话框而不是一行 chips，标签用文本对话框；
  更新检查的频率也改成单选对话框。单选/多选/文本三种设置对话框集中在 `DesktopSettingsDialogs.kt`，
  源设置页共用，见计划 §136。“更多 → 扩展与仓库”同样用这套节/行走廊（仓库、目录、已安装三块，
  仓库行尾是刷新/忘记、已安装行尾是更新/卸载，自动更新是整行开关），行内控件由共享
  `SettingsActionRow` 承载（整页可滚动，目录列表按窗口高度取高，已安装行是页内 inline 行而不是嵌套滚动列表），
  见计划 §137。“更多”页本身也是分节设置页（功能/外观/高级工具），
  功能行带随包图标（`SettingsValueRow` 的 `leadingIcon`），语言/主题/界面风格改开单选对话框，
  见计划 §138；阅读设置另有独立页面（更多 → 阅读设置），分“页面/翻页”两节编辑同一份
  `DesktopReaderSettings`（模式、方向、缩放、背景、页码、裁剪、自动跨章、动画、速度），
  不与阅读器侧面板冲突，见计划 §139；收藏与历史支持长按进入批量选择（顶栏换成数量/全选/分类/移出，
  收藏的“分类”是勾选集合即目标分类集合的批量对话框，历史移除连进度一起删），见计划 §140。
  来源设置另有独立页面（更多 → 来源设置）：概览节改来源排序（安装顺序/名称/语言）、来源总览的网格或列表、
  按语言分组（组前加语言标题），成人内容节选“来源与浏览/收藏/历史/订阅/更新”五个过滤目标（来源侧滤掉 HENTAI_ 类型来源，
  书架侧作为展示规则并入筛选，订阅页的“订阅”滤时间线行、“更新”滤新章节轮播，筛空时显示筛选空态），
  设置存 `desktop_background` 的 `sources_*` 四键，见计划 §141、§146。
  来源设置的“标签”节是全局标签黑名单页（对应 Android 的“标签黑名单”）：搜索词库目录、按类别分组、
  点芯片切换、长按看键/英文/类别/别名、可“清空”或把词库不认识的标题加为 `raw:` 模式；
  选择存 `desktop_background` 的 `global_tag_blacklist`（与 Android 同键同格式，字符串是词库 ID 或 `raw:` 键），
  匹配规则在 `core-domain` 的 `TagTitleBlacklist`，收藏/历史书架、订阅时间线、更新轮播、浏览/搜索结果、
  主页更新与推荐都在渲染前应用它，见计划 §151、§152。
  同一“标签”节还有 Android 的“高亮敏感类别”开关：警示词表（110 条，含俄语别名）在 `core-domain` 的
  `TagWarnings`，Android 的 `ContentListMapper` 与 Windows 详情页共用；命中时详情标签芯片换成 warning 色，
  开关存 `desktop_background` 的 `tags_warnings`（与 Android 同键，默认开），见计划 §153。
  “成人”节另有 Android 的“成人漫画无痕模式”三态（启用/每次询问/禁用，默认每次询问）：键 `incognito_nsfw`
  与 Android 同、值存枚举名，成人的判定来自 `core-source` 的 `SourceContent.isNsfw`（`ADULT` 或
  `HENTAI_` 来源类型），与 `SourceLibrary` 写历史时同一份。预览卡与详情页的 `preview-incognito`
  （共享 `TabletPreviewContent` 的可选 `incognito` 动作）起一次“什么都不记录”的阅读会话
  （`DesktopAppState.readerIncognito` / `savesProgress`，四处自动保存都过它），
  ASK 时先弹 Android 那个“不再询问”对话框再打开阅读器，见计划 §154。
  更多 → 阅读统计（`reading-stats-open`）对应 Android 的 `StatsScreen`：周期切换、类型筛选（全部/漫画/小说/视频）、
  总时长/活跃天数/会话/作品、连续天数、每日活动、时段分布、类型占比、作品榜与清空确认。数据来自
  `core-library` 的 `ReadingSessionTracker`——阅读器各处自动保存经 `SourceLibrary.recordPage` 统一 tick，
  session 由 `beginReadingStop` 在进入阅读器前打开、离开时 `finishReadingSession` 关闭
  （无痕不打开；一次阅读器打开算一个 session），读模型复用 `core-domain` 的 `deriveReadingStatistics`，
  类型筛选就是把该函数吃的 `kinds` 映射按类型过滤，见计划 §155、§156。作品榜点一行先开 Android 的
  `ContentStatsDialog` 单作品统计弹窗（标题、90 天柱状图、首次阅读时间、总时长/会话/页数或章节或集数，
  只有“详情”才导航；读模型是 `SourceLibrary.contentReadingStats`），见计划 §157。
  更多 → 存储与网络（`storage-settings-open`）对应 Android 的存储与网络枢纽：“存储用量”节按数据根目录的顶层子项
  分成数据库/图片/偏好与下载记录/日志/其他/可用空间，Canvas 比例条加每行大小与卷百分比，图片行还会写
  “已保存章节保留多少”（那部分与图片目录是同一份，不重复计入），并有一行 Kototoro 占用 / 磁盘可用 / 卷容量；
  “网络”节是代理入口。读模型在 `desktop-runtime` 的 `DesktopStorageUsage`（只读元数据、不跟随符号链接、
  可用空间取 `usableSpace`、百分比按整卷），进页面时在 IO 线程遍历一次，见计划 §159。
  更多 → 存储与网络 → 代理（`proxy-settings-open`）对应 Android 的 `ProxySettingsScreen`：类型 DIRECT/HTTP/SOCKS、地址、端口、
  可选用户名密码与“测试连接”，值读写 Android 同名的 `proxy_type_2`/`proxy_address`/`proxy_port`/`proxy_login`/
  `proxy_password`（类型存枚举名），compat 客户端的 `ProxySelector`/`Authenticator` 每次请求才读当前值，
  保存后清一次连接池，所以换代理不用重启应用；测试连接与 Android 一样请求 `http://neverssl.com`，成功弹
  `connection_ok`，失败弹异常消息，代理页的返回按钮回到枢纽，见计划 §158。
  更多 → 播放设置（`playback-settings-open`）对应 Android 的 `PlaybackSettingsScreen`：播放背景（Android
  `video_backgrounds` 的五个值）、解码器（仅硬件/优先硬件/仅软件，映射到 libmpv 的 `hwdec` 与 `hwdec-fallback`）、
  控件不透明度与渐隐不透明度（共享 `SettingsSliderRow`，拖动只改本地 draft、松手才写盘），四键用 Android 同名
  `video_background`/`video_decoder_mode`/`video_controls_alpha`/`video_gradient_alpha`；播放器的视频面/外框取该背景，
  控件条与其上方渐隐取这两个不透明度，见计划 §160。
  存储与网络页的“网络”节还有 Android 的 GitHub 镜像（`github-mirror-open` → `github-mirror-dialog`）：
  目录与该反代改写规则 `GitHubMirrorCatalog` 已下沉 `core-net`（包名不变，Android 的
  `displayName(context)` 留在 `app`），Windows 用 `DesktopGitHubMirror` 读写 `desktop_network` 的
  `github_mirror`（与 Android 同键，值存镜像 id），`rewrite(url)` 每次读当前值，所以换镜像即时生效；
  应用更新下载、超分模型下载、扩展 JAR/解析器/Cloudstream 列表都过它，`api.github.com` 的版本查询
  与仓库索引地址本身不改写，见计划 §161。同一节还有 Android 的镜像测速（`github-mirror-probe`，
  按钮 `github-mirror-probe-start`/`-cancel`/`-fastest`）：`DesktopGitHubMirror.probe()` 用 JDK
  `HttpClient` 并行拉 `GitHubMirrorCatalog.PROBE_URL`（6 s 超时、响应头即通），进度/结果在
  `probeState`/`probeResults` 两个 flow 里（页面 `collectAsState()` 直接读，`DesktopAppState` 不加字段），
  取消走 `runInterruptible` 会真的断开 socket；文案全用 Android 的 `mirror_probe_*`，
  对话框选项追加 `" · 213 ms"`/`" · 超时"` 与最快的标记，探测的结果模型与目标 URL 也在 `core-net`
  共享（Android 侧的本地声明已删除、改从 `core.prefs` 导入这些类型），见计划 §162。
  更多页“关于与更新”卡对应 Android 的 `AboutSettingsScreen`/`AppUpdateScreen`：除了检查/下载/重启安装，
  现在也显示 Android `UpdateDescription` 那三行（新版本号、`app-update-size` 安装包大小、`app-update-published`
  发布日期）与发布说明本身（`app-update-notes`，用 `core-ui` 新增的共享 Markdown 子集渲染），行尾
  `app-update-changelog` 进新页“更新日志”（`DesktopScreen.CHANGELOG`，对齐 Android 的 `ChangelogScreen`）：
  本频道所有可安装版本按新→旧一张卡一版，带当前版本徽标、日期、包大小与各版说明，每次会话只拉一次、
  “重新加载”才重拉。Markdown 解析 `core-ui` 的 `parseMarkdownBlocks`/`parseMarkdownInline`、
  渲染 `MarkdownText`/`markdownPlainText` 都在 `core/ui/markdown/`（纯共享、无新依赖，Android 仍用 Markwon）；
  `desktop-runtime` 的 `DesktopAppUpdateClient.releases()` 与更新检查共用 `offerableFor(channel)`
  过滤（有包有下载地址、stable 不列预发布），所以说明列出的版本就是它真会装的版本，见计划 §163。
  收藏/历史的“筛选与排序”抽屉另有“布局”一节（封面网格 / 每行一个作品，对应 Android 的 ListMode），
  选择存 `desktop_library` 的 `library_list_mode`，列表模式下 `ContentGrid` 渲染 `content-list` 行视图，
  点击/长按语义与卡片一致，见计划 §142。
  扩展与仓库页可导入 TVBox/苹果 CMS 链接：配置按 Android 的同一份文档存进 `desktop_json_sources` 的
  `json_sources`，站点以 TVBOX 来源进入来源栏（第 4 条路由 `MacCmsSourceRuntime`），封面在协议返回
  UNSUPPORTED 时由 Session 直接 GET URL 落盘；type 3/4 爬虫站点明确跳过，见计划 §143。
  收藏/历史的选择栏另有“标为已完成”（对应 Android 的 MARK_AS_COMPLETED）：确认后每部作品的阅读位置
  移到最后一章、percent 记为 1，原进度被覆盖（与 Android 弹窗的警告一致），见计划 §144。
  收藏的选择栏还有“置顶/取消置顶”（对应 Android 的 PIN）：写 `favourites.pinned`，置顶作品在每种
  排序里都排最前（历史书架不受影响），卡片在右下角画 `ic_pin` 角标，见计划 §145。
  收藏的选择栏另有“分享”（对应 Android 的 SHARE）：Windows 没有分享面板，改成写系统剪贴板——单部作品是
  “标题 + 空行 + 链接”，多选每行“标题 - 链接”，链接取 `publicUrl`、为空退回 `url`，写后回读一致才算成功，
  失败时提示并以可选中的文本对话框给出同一段链接，见计划 §147。
  历史的选择栏同样有“分类”（对应 Android 历史页的 FAVOURITE）：打开的是收藏页那个分类对话框，
  勾上任意分类即成为收藏、取消最后一个分类即退回纯历史行，行本身留在历史页，见计划 §148。
  收藏与历史的选择栏另有“下载”（对应 Android 两条书架栏的 SAVE）：弹窗给出详情页那套章节范围
  （全部/未读/前 10、25、50 章），按每部作品各自的进度与已保存章节计算，章节取本地已存列表且覆盖
  全部分支（离线也可用），novel/video 与没有可下章节的作品直接跳过，结果以总章数报出，见计划 §149。
  收藏的选择栏还有“迁移”（对应 Android 的 MIGRATE）：一次一部作品，先选同内容类型的另一个来源，
  用作品标题在该来源搜索并挑出对应作品，“保留原作品”即 COPY、否则 REPLACE，分类/进度/统计随
  `SourceLibrary.migrate` 在一个事务里迁移，下载与书签留在原作品上，见计划 §150。
  索引模型共用 core-source，APK-only 明确不可安装，构造/持久化失败保留旧扩展；范围见计划 §56。
  reader-core 决定单页/双页/RTL 的可见页面、宽图独页、Fit 排版与进度锚点；键盘翻页和同分支跨章按钮
  已接入。阅读模式/方向保存于原偏好 store，历史 percent 包含页组末页，page 保留起始锚点用于恢复。
  调试页接可见 WebView2、窗口显示/隐藏、当前页面 JS 与 Cookie 同步；输入错误/导航取消保留 live browser。
  来源挑战显示继续/取消/重显窗口，确认后请求恢复；同步 Rx 调用关联当前协程 owner，退出先结束交互。
  Session 持有平台/registry/存储/浏览器，Controller 使用 StateFlow 与串行操作，退出先停止操作再关闭资源。
  来源意外错误按协议 requestId 关联本地有界诊断，UI 显示类别/编号及 logs/source-errors.log 路径；
  不记录请求参数、异常消息、Cookie 或响应内容，见计划 §58。
  `run-windows.ps1` 提供启动入口；外部 API 仅加入 run/test classpath，classpath 顺序复用 compat 配置。
  卡片/详情封面复用原 client 与 image store，Session 取消/等待共享任务；256 条内存/持久化记录、
  离线恢复校验和失败重试已接。标准动态筛选树、分页条件、热门/最新、草稿/失败恢复与来源隔离
  已接入，原生索引/方向经共享协议提交，不修改扩展 FilterList 的实例身份。
  四种 Fit 与基准溢出、相机缩放/拖动、鼠标/触摸/键盘已接共享数学；Fit 模式持久化，相机 motion
  不改变页组/历史或发起额外图片请求。连续模式复用 VerticalReaderScene 与可见范围加载，支持
  滚轮/拖动/滚动条/键盘；首页与页内像素偏移写入原 history，导航/退出保存最后已验证的视口。
  分页/连续阅读共用 256 条页面磁盘索引，按扩展哈希与完整请求上下文隔离，读取核对大小/hash，
  离线重启可复用；刷新走原 client，失败保留成功记录。临时 Skia Image 在 Bitmap 转换后释放。
  TileGrid/图像坐标几何已迁入 reader-core，Android 旧名称保留 typealias；PNG/JPEG 长页在分页
  相机与连续模式按可见区域分块，单块 2 MiB 输出预算、最多两个解码任务，复用原图片文件。
  PNG/JPEG 尺寸改用文件头，超过 419 万像素使用区域分块；完整页面输出通过共享 ImageDecodeSize
  限制 N32 到 16 MiB/单边 16384，并与分块共用两个并发名额；场景保留原始尺寸。大 WebP 等格式
  暂以降采样保留兼容，codec 内部临时内存与全局像素/GPU 预算仍待验证和控制。
  可选自动跨章沿用 shared 同来源/同分支顺序，分页及连续键盘/滚轮边界已接；向后进入末页组/底部，
  目标列表/锚点图片失败保留原章节。开关持久化，既有图片索引尺寸提示不绕过显示前的完整校验。
  漫画整章下载、串行后台执行、暂停/继续和跨进程离线入口已接；每页 manifest 独立保存完整
  请求/artifact/尺寸，不受阅读索引淘汰影响，读取仍校验 SHA/大小，下载不写入阅读进度。
  队列归 Controller scope，退出取消，重启保持暂停；64 页内容寻址块、每章原子清单与分片
  发现索引已接，旧平面记录自动迁移并保留，不再把全量/单章页面请求塞入一个 4 MiB snapshot。
  详情章节行的溢出菜单提供标为已读/未读（写原历史，首章标未读清空记录）与删除本地下载；
  后者与下载页批量移除共用 store 删除序列。详情目录的批量模式（选择章节按钮或按住行进入）按当前
  筛选全选/清除，并提供批量下载/标为已读/删除本地下载，退出模式后点击恢复导航。
  底层单文件上限保持原值；元数据占用预览和确认回收未引用旧块已接，按磁盘全部清单
  重新核对，包括未发现清单；损坏/不完整图阻止回收，旧缓存视图退休。图片总磁盘预算已接：
  下载页显示图片目录占用/已保存章节占用/上限，预设档位与手动清理即时执行，后台循环按 10 分钟
  节流回收最久未使用的 `*.img`，`verifiedImagePath` 命中刷新文件时间作 LRU，已保存章节引用的
  blob 永不删除，刚发布两分钟（手动清理五秒）内不动，残留 `.image-*.part` 一小时后清除。
  元数据驻留控制仍待。
  图书馆备份页已接导出/私有文件预览/取消/确认合并，包含书签/统计，未安装扩展也显示已恢复库；
  退出关闭未确认的预览。同页 WebDAV 云同步已接：设置持久化、连接测试、上传并保留清理、远端列表、
  下载后复用本地预览/合并，自动备份间隔由应用内计时器按“距上次成功上传”判定（首次启动即上传、
  失败退避十分钟、改设置或手动上传重排，不与手动并发；Google Drive/系统同步仍待）。详情页的批量下载
  菜单按全部/未读/翻译分支/前 N 章算待下章节（以未筛选列表下标判定未读，重复话号也不错位），
  下载页显示章/页/图片占用并按章多选批量暂停、继续、重试与移除（移除先取消等待再删索引与清单，
  遗留页面块转入可回收元数据，图片文件不删）；已完成章节可导出为 CBZ（逐页复核 SHA、文件名
  去非法字符、再次导出覆盖、失败不留半包），也可“导出全部已完成章节”一次写入整个文件夹
  （跳过未完成项，单章失败只计数不中断）；自动回收默认开启，启动与队列空闲时清理未引用块，
  只动元数据块、清单异常时整体放弃。其他备份节与外部格式、
  预测预取/其他格式区域解码/筛选预设与自定义控件继续推进。
  Windows 本地打包已接：独立 JVM/EXE、ZIP 与 per-user MSI，程序目录在 LocalAppData/Kototoro-App，
  与用户数据隔离；移动目录原生/窗口测试、MSI 全文件 SHA/目录检查已接。实际安装/升级/卸载、
  S3/签名仍待，见 README 与计划 §35、§40–54。
  共享文案层已接：`core-ui` 的 `UiStrings`/`LocalUiStrings`/`uiString` 让共享界面按 Android 资源名取词，
  Android 走 `R.string`（`AndroidUiStrings`），Windows 走 `prepareDesktopStrings` 由同一份
  `app/src/main/res` 导出的目录（`DesktopUiStrings`），内容类型标题改为派生自类型名而不再各存一份，
  重复的 `label.contentType.*` 已删除；`:core-ui:verifyUiStrings` 校验共享键与 `ContentType` 都有资源。
  界面栈已统一为 Material 3：`DesktopTheme` 删除 Material 2 桥接，桌面 23 个外壳文件改用
  `material3` 控件/角色，与共享组件的颜色、字体、圆角令牌一致，暗色分支读 `LocalDesktopDark`。
- `core-net/` - 共享 HTTP 协议（KMP）：`GoogleDriveSyncApi`、嵌套 `DriveFile` 与 API 异常保持原包名。
  WebDAV 的 `WebDavBackupClient`、远端备份分类/选取/保留规则与 `RemoteNamespace` 也在 commonMain；
  multistatus/HTTP 日期解析 `parseWebDavResources` 同样在 commonMain（无 DOM，Windows 与 iOS 共用），
  Android 的 `WebDavResponseParser` 只是它的薄封装。
  MALSync 的 `MALSyncMappingApi`、跨站映射分类/ID 选择/去重规则与窄投影模型也在 commonMain；
  `ScrobblerService` 的 Android 资源、平台 JSON 取值以及 Repository 的 64 项 LRU/同键去重留在应用。
  取消不写入空缓存；普通 HTTP/网络/解析失败仍保留既有空结果回退。
  MAL 的 `MALDiscoveryApi` 共享搜索、动漫/漫画榜单与季度查询的 GET 请求和字段选择；
  Android 注入原 MAL 限定客户端，保留 bearer/client ID 拦截器与 Authenticator 刷新链，
  JSON/UI 映射、媒体提示缓存、账号及列表写入继续由 `MALRepository` 负责。
  平台注入并管理 Ktor `HttpClient`；Android 的 Hilt 网络模块复用 `createKtorOkHttpClient`，
  保留各自超时、重定向和连接恢复设置，WebDAV 继承原基础客户端的代理、Cookie 与拦截器。
  共享协议释放每次响应、保留各自重试边界并传递取消；WebDAV 文件通过 channel 回调流式传输。
  授权、Repository、WebDAV 文件 IO、其他 tracker/scrobbler 客户端及 iOS 引擎装配仍由平台负责，
  Ktor 版本复用既有 3.5.0
- `docs/` - VitePress 文档站点

KMP/iOS 方案与进度见 `docs/architecture/kmp-ios-plan-2026-10.md`。KMP 模块的可移植性检查是 `./gradlew :<module>:compileCommonMainKotlinMetadata`（会拒绝 JVM 专有 API，Windows 上即可运行）；其测试任务是 `jvmTest` 而不是 `test`。

### 代码组织（app/src/main/kotlin/org/skepsun/kototoro/）
项目按功能模块组织，每个模块通常包含 `data`、`domain`、`ui` 三层：

**核心模块**：
- `core/` - 核心基础设施（数据库、网络、缓存、异常处理、模型）
  - `core/db/` - 数据库已移到 `core-db/` 模块（包名不变）；这里只剩 `MangaDatabase(context)` 构建函数、`DatabasePrePopulateCallback` 等依赖 Android 的部分
  - `core/network/` - OkHttp 拦截器、代理、Cookie 管理、WebView 集成
  - `core/parser/` - 解析层：多生态解析器（Mihon、Aniyomi、IReader、Legado、TVBox、Kotatsu、Tsuki、JS 规则）与解析规则引擎
  - `core/model/` - 核心数据模型
- `main/` - 主入口 Activity

**内容源集成**：
- `mihon/` - Mihon/Tachiyomi 扩展集成（动态 ClassLoader、依赖注入桥接）
- `aniyomi/` - Aniyomi 扩展集成
- `ireader/` - IReader 源集成
- `cloudstream/` - Cloudstream3 视频源运行时集成（外部 jar 消毒 + WebView 解析器/Cloudflare 拦截器桥接）
- `extensions/` - 扩展管理框架
- `local/` - 本地文件导入（CBZ、EPUB 等）
- `alternatives/` - 替代源/镜像站管理
- `explore/` - 源预设探索
- `remotelist/` - 远程内容列表
- `picker/` - 文件/内容选择器

**内容管理**：
- `home/` - 主页和内容列表
- `discover/` - 发现和浏览
- `search/` - 搜索功能
- `details/` - 内容详情页
- `favourites/` - 收藏管理
- `history/` - 历史记录
- `bookmarks/` - 书签
- `suggestions/` - 推荐/建议
- `filter/` - 内容筛选
- `list/` - 列表视图
- `space/` - 自定义媒体空间（内置 Manga/Novel/Anime + 用户自定义空间，含会话、路由、目录与内容策略）
- `stats/` - 阅读统计
- `readingrecord/` - 阅读记录与阅读时长（ReadingRecord / 阅读跳转点）

**阅读体验**：
- `reader/` - 漫画/小说阅读器（含 `translate/` 的 OCR + 翻译、`novel/` 小说阅读/TTS、`ui/` Compose 页面）
- `video/` - 视频播放器（media3 播放、Anime4K/NCNN 超分、DLNA 投屏、弹幕、字幕/音轨）
- `image/` - 图片查看器（OCR/翻译实际位于 `reader/translate/`，勿混淆）

**同步和备份**：
- `sync/` - WebDAV 同步
- `backups/` - 备份和恢复
- `tracker/` - 外部平台进度追踪（MAL、AniList、Bangumi 等）
- `tracking/` - 追踪网站发现与候选匹配
- `scrobbling/` - 进度同步

**其他功能**：
- `settings/` - 设置界面
- `download/` - 下载管理
- `browser/` - 内置浏览器（Cloudflare 绕过）
- `widget/` - 桌面小部件

### 关键技术实现

**外部扩展集成**（参考 `docs/architecture/external-extension-integration-guide.md`）：
- 使用 ChildFirstPathClassLoader 隔离扩展依赖
- 通过依赖注入桥接提供 Application Context 和网络实例
- 动态监听 APK 安装/卸载事件（BroadcastReceiver）
- 处理 Cloudflare 挑战和 Cookie 同步

**增量 OTA 更新**（参考 `docs/architecture/incremental-updates.md`）：
- CI/CD 使用 `bsdiff` 生成增量补丁
- 纯 Kotlin 实现的 `bspatch` 算法（无 NDK 依赖）
- 严格的版本匹配验证
- 自动回退到完整 APK 下载

**数据库**：
- Room 数据库（`MangaDatabase`）在 KMP 模块 `core-db/`，DATABASE_VERSION = 85（`core-db/src/commonMain/.../core/db/MangaDatabase.kt`）；导出的 schema 仍位于 `app/schemas/org.skepsun.kototoro.core.db.MangaDatabase/`（已提交，由 `core-db` 的 Room 插件导出，`app` 的 androidTest 以 assets 读取）
- 迁移文件 `core-db/src/androidMain/kotlin/org/skepsun/kototoro/core/db/migrations/Migration1To2.kt` 到 `Migration83To84.kt`（另含历史遗留的降级迁移 `Migration24To23.kt`，仍保留在 `getDatabaseMigrations` 列表中，位于 `core-db` 的 `MangaDatabaseMigrations.kt`）；84→85 起的迁移在 commonMain（`Migration84To85.kt` 与 `sharedDatabaseMigrations()`），Android/Windows/iOS 三端共用
- Android 必须继续使用框架 SQLite 驱动：旧迁移实现的是 `migrate(SupportSQLiteDatabase)`，换成 `BundledSQLiteDriver` 会抛 `NotImplementedError`
- DAO 的过滤条件使用数据类型 `ListFilterCriteria`（`core-db`）；应用层的 `ListFilterOption`（带资源 id 与图标）在 Repository 边界用 `toCriteria()` 转换
- 用户状态（收藏/历史/统计/偏好/追踪）直接挂在 `manga` 行上；实体图谱与 work 表已在 v84 移除（`Migration83To84` → `ProjectionOwnershipMigrationResolver`）
- Room 表映射类含 ReadingRecord/ReadingJumpPoint、RestoreCheckpoint、Space*（会话/导航/路由偏好/空间定义）等；
  `core/db/entity` 和 `*Entity.kt` 表示数据库行映射，不是已移除的 Entity Graph / Work 业务体系。
- 同步 ContentProvider 使用 `favourites(manga_id, category_id)` 和 `history(manga_id)`；
  旧 Work/Entity 表名仅供历史迁移使用，不能作为当前 Provider 的表名。
  收藏读模型的 `entity_id` 是 `manga_id` 查询别名，旧同步 DTO 的 nullable entity/anchor 字段用于协议兼容。
- 使用 KSP 生成 Kotlin 代码

**依赖注入**：
- Hilt/Dagger 用于依赖注入
- 需要在修改后运行 KSP 处理器

**测试框架**：
- JUnit5 + Kotest + MockK（单元测试）
- AndroidX Test + Hilt Testing（instrumented 测试）
- MockWebServer（网络测试）

## 开发注意事项

### 签名配置
Release 构建需要在 `local.properties` 或环境变量中配置签名：
```properties
RELEASE_STORE_FILE=/path/to/keystore
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=***
RELEASE_KEY_PASSWORD=***
```

### 本地属性
`local.properties` 中可配置：
- `tg_backup_bot_token` - Telegram 备份机器人 token
- `dandanplay.appId` / `dandanplay.appSecret` - 弹弹 Play API 凭证

### 版本管理
当前版本：`versionName 1.9.9` / `versionCode 1212`（`app/build.gradle`）。
在 `app/build.gradle` 中更新：
- `versionCode` - 每次发布递增
- `versionName` - 语义化版本号（如 "1.0.0.prev"）
- nightly 变体由 `androidComponents.onVariants` 按日期自动生成（versionCode=yyMMdd，versionName=N+yyyyMMdd），无需手动设置

### 代码风格
- 遵循 `.editorconfig`：UTF-8、LF、4 空格缩进、120 字符行宽
- Kotlin 官方代码风格，启用尾随逗号
- 类名使用 PascalCase，方法和属性使用 camelCase
- Android 资源使用小写下划线命名（如 `pref_appearance.xml`）

### 命名空间说明
项目主代码使用 `org.skepsun.kototoro` 命名空间（最初从 Kotatsu 派生，现已独立开发）。部分历史代码（如 instrumented test runner `org.koitharu.kotatsu.HiltTestRunner` 和部分 androidTest 测试类）仍保留原始包路径，这是正常现象，不要批量重命名。

### 提交规范
推荐使用 Conventional Commits 格式：
- `feat: ...` - 新功能
- `fix(scope): ...` - 修复
- `docs: ...` - 文档
- `chore: ...` - 杂项

### 翻译
翻译内容通过 Weblate 管理，避免手动批量修改字符串资源。

### 发布流程
参考 `.github/RELEASE_GUIDE.md`：
1. 更新 `versionCode` 和 `versionName`
2. 创建 Git 标签（如 `v1.0.0`）
3. 推送标签触发 GitHub Actions 自动构建
4. CI 自动生成 APK 和增量补丁并创建 Release

## 重要文档

- `docs/contributing.md` - 贡献指南
- `docs/development.md` - 开发入门
- `docs/getting-started.md` - 用户快速上手
- `docs/architecture/external-extension-integration-guide.md` - 外部扩展集成详解
- `docs/architecture/incremental-updates.md` - 增量更新机制
- `docs/architecture/dynamic_plugin_system.md` - 动态 UI 插件系统
- `docs/architecture/custom-spaces.md` - 自定义媒体空间架构
- `docs/architecture/entity-system-removal-handoff-2026-09.md` - 实体/work 体系移除与数据迁移交接（旧实体文档已归档到 `docs/archive/entity-system/`）
- `docs/architecture/large-library-performance-handoff-2026-08.md` - 大型本地库分页性能交接
- `docs/architecture/cloudflare-resolver-improvement-plan.md` - Cloudflare 解析器改进计划
- `docs/architecture/media3-video-player-migration-plan-2026-08.md` - media3 视频播放器迁移计划
- `docs/architecture/toolchain-upgrade-plan-2026-08.md` - 工具链升级计划
- `docs/architecture/ncnn-super-resolution.md` - 超分辨率实现
- `docs/unified_source_management.md` - 源管理 UI 重构方案（草案）
- `.github/RELEASE_GUIDE.md` - 发布指南

## 常见任务

### 添加新的数据库迁移
1. 在 `core-db/src/commonMain/kotlin/org/skepsun/kototoro/core/db/migrations/` 创建 `open class MigrationXToY`，覆写 `migrate(connection: SQLiteConnection)`（Windows/iOS 的 bundled 驱动调用它），SQL 放在 companion 常量里，并加入 `sharedDatabaseMigrations()`（桌面 `DesktopDatabase` 与 iOS `IosLibrary` 的 builder 注册的就是它）
2. 在 `core-db` 的 `MangaDatabaseMigrations.kt` 中的 `getDatabaseMigrations` 列表里注册一个子类，覆写 `migrate(db: SupportSQLiteDatabase)` 执行同一 SQL（Android framework 驱动只调用这个重载，默认实现会抛 `NotImplementedError`）；参考 `AndroidMigration84To85`
3. 递增 `core-db` 中 `MangaDatabase.kt` 的 `DATABASE_VERSION` 常量
4. 更新 `app/schemas/org.skepsun.kototoro.core.db.MangaDatabase/` 中的 schema JSON（构建 `core-db` 时由 Room KSP 导出，需提交）
5. 实体、DAO 只能使用 commonMain 可用的 API（不要用 `System.currentTimeMillis()`、`java.*`、`SupportSQLiteQuery`、`BuildConfig`、资源 id 等）；用 `./gradlew :core-db:compileCommonMainKotlinMetadata` 检查。新迁移用 `app/src/androidTest/.../MangaDatabaseSnapshotChainTest` 验证 Android（把新的快照版本加进它的 `snapshots` 列表），用 `core-db` 的 `SharedMigrationTest`（`:core-db:jvmTest`）验证 bundled 驱动从旧版本文件升级；该包里 `MangaDatabaseTest` 有 4 个既有失败，见 `docs/architecture/kmp-ios-plan-2026-10.md` §12

### 添加新的源类型
1. 在对应模块（如 `mihon/`、`ireader/`）实现源接口
2. 注册到源管理系统
3. 添加 UI 入口（通常在 `settings/sources/`）

### 修改阅读器功能
1. 核心逻辑在 `reader/` 模块
2. OCR + 翻译管线在 `reader/translate/`；纯图片查看在 `image/` 模块
3. 视频播放在 `video/` 模块（超分、DLNA、弹幕分别在 `video/performance`、`video/dlna`、`video/danmaku`）
4. 测试时验证不同内容类型（漫画、小说、视频）

### 调试网络问题
1. 检查 `core/network/` 中的拦截器
2. 查看 `browser/` 模块的 WebView 集成
3. 验证 Cookie 和代理配置


# 开发流程

## 核心规则

1. **按需使用 skill** — 仅在任务明确匹配某个项目 skill（如 Compose/Backdrop 相关）时才加载，不要为每个任务例行调用
2. **验证先于完成** — 声称完成前必须运行验证命令并确认输出
