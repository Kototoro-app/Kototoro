 Kototoro iOS / Windows / KMP 方案（2026-10）

## 文档信息

- 创建日期：2026-10-02
- 状态：规划草案；P0 中的 S1（KMP 工具链）与 S2（Room KMP）已完成并通过（§9、§10）。P1 已完成四个模块的抽取：
  `:reader-core`、`:parser-api`（叶子类型，§11）、`:core-db`（§12）和 `:core-backup`（§13）。
  `:core-domain` 已抽取同步/状态规则、四类列表快照与派生、书架、换源写入计划、进度与标题匹配规则（§14–§18）；
  完整业务服务仍待迁移。
  `:core-net` 已抽取 Google Drive、WebDAV、MALSync 和 MAL 公共查询 HTTP 协议，
  并完成 Android 引擎装配与契约回归（§19–§22）；
  XML/JSON 平台投影、文件 IO 与 iOS 引擎由平台负责，其余 tracker/scrobbler 待迁移。
  `:core-source` 已实现共享 DTO、版本化 JSON 客户端/入口与 Android 适配器（§24）；
  P2 的 `:source-host` 已实现 Mihon JAR 检查/注册、内容执行、动态过滤与章节图片执行/物化（§25–28）；
  Windows 同步纳入目标，已补上常驻 JSONL 调试入口（§29）、持久化数据库（§30）、偏好存储（§31）
  和原生标准 PreferenceScreen 控制树/变更协议（§32）；可选 `:mihon-desktop-compat` 已提供基础
  Windows 平台初始化及真实 API 离线测试（§33），WebView2 离线 HTML/JS/Cookie 桥（§34），
  可选 Compose Desktop 的浏览/搜索/详情/收藏/章节图片/历史/标准设置入口（§35）。
  完整桌面能力/安装包/其余平台服务、iOS C/JNI 桥、
  OpenJDK 镜像和真机源验收仍未完成。
  S0（真机评估）、S3（许可证）尚未开始
- 目标：共享一个 KMP 内核，同时支持 iOS 与 Windows；iOS 用 SwiftUI 外壳和嵌入式 JVM，Windows
  用 JVM 桌面外壳并优先承载调试。源（Mihon JAR、Kotatsu 解析器、Legado 规则）复用同一个 JVM host。
- 前提假设：az4521 的 TachiyomiAZ iOS 路线（OpenJDK Mobile Zero + Suwayomi AndroidCompat）在真机上
  可用。该前提**尚未由我们验证**，由 §5 的 G0 门槛负责证实或证伪
- 关联文档：
  - [Scene Reader 改进计划](./reader-scene-improvement-plan-2026-09.md)（§8.2 曾约定"无真实多平台消费者之前不引入 KMP"）
  - [Browser Transport 实施计划](./browser-transport-plan-2026-08.md)（Cloudflare 策略选择器）
  - [Legado Runtime 抽取方案](./legado-runtime-extraction-plan.md)
  - [外部扩展集成指南](./external-extension-integration-guide.md)

## 1. 决策摘要

1. **共享 KMP 内核 + iOS 原生外壳 + Windows JVM 桌面外壳 + JVM 源运行时。**
   全量 KMP/Compose Multiplatform 不现实：代码库 35.7 万行里，现在能直接进 commonMain 的只有约
   6～9%（§2.1）。
2. **iOS 版就是 §8.2 所说的"真实多平台消费者"，KMP 在此立项。** 首个模块是已经纯 Kotlin 的
   `reader-core`（58 个文件、2638 行）。
3. **源不用 Kotlin 重写，在 iOS 上由一个嵌入式 JVM 统一承载**（§2.2）：Mihon JAR、Kotatsu 解析器、
   Legado 运行时都是 JVM 代码。上游 Keiyoushi 已为每个 APK 发布同名 JAR，Kotlin/JS 或源码级编译
   路线因此失去前提。
4. **Android 现有的进程内源加载保持不变。** JVM host 与它共用同一份协议，用于 iOS、Windows 与契约测试。
5. **共享模块不依赖 DI 框架**（只用构造注入）；Android 继续用 Hilt 装配，iOS 手动装配。
6. **v1 只做漫画；iOS 只做侧载分发**（AltStore / SideStore）。Windows 单独做桌面调试/应用分发。
   小说、动漫、视频、翻译后置；Windows 优先顺序与验收见 §29。

## 2. 依据

### 2.1 代码库可移植性画像

对 `app`、`parser-api`、`reader-core` 共 2319 个 Kotlin 文件、357,508 行做静态扫描：按 import 与
同包标识符引用建依赖图，并对"依赖了不可移植文件的文件"做传递闭包。

| 口径 | 文件 | 占比 | 代码行 | 占比 |
|---|---|---|---|---|
| L0 严格（仅 kotlin/kotlinx/okio/ktor/coil3/Room/Paging 等 KMP 可用库） | 518 | 22.3% | 22,236 | 6.2% |
| L1 + 忽略 DI 注解 | 539 | 23.2% | 22,974 | 6.4% |
| L2 + `java.*` 可用 expect/actual 或 stdlib 替换 | 624 | 26.9% | 27,042 | 7.6% |
| L3 + Compose 若采用 CMP | 694 | 29.9% | 32,993 | 9.2% |

按包（L1 / L2）：`core/db` 92%；`core/prefs` 66% / 68%；`parser-api` 34% / 46%（阻塞项：okhttp 33 个
文件、jsoup 16 个）；`backups` 29% / 35%；`core/model` 23% / 27%；`core/network` 11% / 20%
（okhttp 33 个文件）；`reader` 18% / 19%（主体是 Android 与 Compose）。

局限：这是上界，未考虑反射、资源文件、测试代码；同包引用基于标识符匹配，存在少量误判。

**更正：** 上表中 `core/db` 的 92% 只统计该目录内的文件，会低估数据库抽取的范围。实际的 Room schema 是
`MangaDatabase` + 36 张表对应的实体 + 约 35 个 DAO 文件，其中大部分实体和 DAO 分散在约 15 个功能包
（`history/data`、`favourites/data`、`tracker/data`、`space/data` 等），不在 `core/db` 里。S2 实测这个
schema 层的闭包是 96 个文件、约 5000 行非空行（§9），所以数据库抽取是跨包搬迁，而不是移动一个目录。
同样，直接用 import 与同包引用做传递闭包会得到 1822 个文件的误导性结果，真正的依赖由编译器给出才可靠。

### 2.2 源生态与嵌入式 JVM

Keiyoushi 的 `index.json` 为 1396 个扩展各提供一个 `jarUrl`（与 APK 同名，带 sha256），其中
extension-lib 1.6 有 1323 个、1.4 有 73 个。我们下载了 1395 个（`tr.trmanga` 的下载返回 HTTP 500）。
环境：Suwayomi-Server `eb2dc0b`（固定 commit）+ az 的 iOS 补丁 + az 脚本固定的 36 个运行时 JAR +
az 的 `MobileCompatShims`（`TachiyomiAzIOS` @ `b86cead`），JDK 镜像用 jlink 裁成与 iOS 相同的
4 个模块（`java.base`、`java.xml`、`jdk.crypto.ec`、`jdk.unsupported`）。

| 测试 | 结果 |
|---|---|
| 静态：解析 class 常量池，对照 iOS JDK 镜像 / AndroidCompat / az shim | 1386 / 1395（99.4%）可满足；干净 322，加小型 shim 1051，加 UI stub 1163，加 QuickJs 1189，加 WebView 1223，加图形 1386；静态失败 9 个 |
| 动态：加载、构造、`listSources`、`getSearchFilters`、`getSettings` | 1389 / 1395（99.6%）。完整 JDK 与 4 模块镜像结果一致，没有只在 4 模块镜像上出现的失败 |
| 联网：每个 JAR 第一个 source 请求第 1 页 popular | 通过 750（53.8%）；站点有响应且能判定的 793 个中通过率 94.6% |

动态 6 个失败：3 个构造时创建 WebView（缺 WKWebView 原生桥，桌面无法验证），3 个调用了 Android
stub（`SpannableString`、`ColorMatrix`、`Preference.setOnPreferenceClickListener`）。

联网未通过的构成：Cloudflare 挑战 220（15.8%）、连不上或 HTTP 错误 329（23.6%）、需要原生桥 53
（3.8%：52 个 WebView + 1 个 JS 引擎）、空结果 33（2.4%）、其它异常 9（0.6%）、兼容层缺陷 1（0.1%）。

**这些数字不能证明什么：** 只覆盖构造阶段与第 1 页列表；没有核对内容是否正确；只测了每个 JAR 的第一个
source；延迟是桌面 JIT 加单一网络视角，不代表 iOS 的 Zero 解释器；没有 Android 基线对照；静态名字比对
有盲区（方法名存在但函数体是 stub）。

### 2.3 参考实现：az4521 / TachiyomiAZ

- 两个仓库：`TachiyomiAzIOS`（Swift，Aidoku 的 fork，2026-08-06 创建、2026-08-14 归档）与
  `TachiyomiAZ` monorepo（Android + KMP 共享模块 + SwiftUI 的 `iosApp`，持续更新）。
- 路线：OpenJDK Mobile 26 **Zero**（纯解释器）+ Suwayomi AndroidCompat + 自有 iOS shim（主 Looper、
  WebView→WKWebView、CookieManager→WKHTTPCookieStore、图形/文本、QuickJs）；Swift 经 JNI 以 JSON
  调用 Java 侧 `ExtensionHost`；每个扩展一个 `URLClassLoader`（同进程，非安全边界）。
- 分发：仅 AltStore / SideStore，README 明确不上 App Store / TestFlight。
- CI（GitHub Actions）：Linux job 构建兼容 JAR；macos-14 + Xcode 15.4 + JDK 24 构建 OpenJDK 镜像并缓存；
  macos-26 `xcodebuild … CODE_SIGNING_ALLOWED=NO` 出未签名 IPA（83.2 MB）。IPA 工作流近 12 次成功 10 次，
  缓存命中 8～12 分钟、冷启动 32 分钟；monorepo `ios.yml` 近 10 次成功 9 次。
- 许可：应用 GPLv3；Suwayomi MPL-2.0；OpenJDK 补丁源自 GPLv2 + Classpath 例外。
- 作者自述"可以正常使用"（Discord，未核实，范围不明）。monorepo 的 `IOS_PORT.md`（2026-08-17）自述
  "源"仍是未完成的大头，并记录标准运行时无法在模拟器启动，需给 OpenJDK 打 3 处源码补丁。

### 2.4 对 §8.2 的修订

`reader-scene-improvement-plan` §8.2 的约束是"无真实多平台消费者之前不为假想未来付 KMP 工具链复杂度"。
iOS 版是真实消费者，该条件满足。§8.2 其余约束保留：不创建空壳接口或一对一转发层；`:reader-core` 的
I1 边界（零 Android/Compose/渲染器依赖）不变，只是实现方式从"纯 JVM 模块"变为"KMP 模块"。

## 3. 目标架构

```
commonMain（KMP 共享内核）
  reader-core      纯 Kotlin，已存在，最先转 KMP
  core-model       Content / Chapter / Page 模型（parser-api 去掉 okhttp、jsoup 依赖）
  core-source      SourceRuntime 接口 + 协议 DTO（kotlinx.serialization）
  core-db          Room KMP，v84 schema
  core-domain      历史、收藏、更新规则、迁移规则
  core-backup      备份格式、Mihon / Aniyomi 备份解码（protobuf）
  core-net         Ktor：tracker、WebDAV
androidMain        Hilt 装配、现有进程内 loader、OkHttp、WebViewClearanceSolver
iosMain            Ktor Darwin、BrowserTransport(WKWebView)、JvmSourceRuntime(C 桥 → 嵌入式 JVM)
desktop/JVM        同一共享内核、Room JVM、文件/网络/偏好平台服务、同一 JVM host
JVM host（平台无关）  Mihon JAR + AndroidCompat / Kotatsu parsers / Legado runtime，统一协议
iosApp             SwiftUI + 共享 XCFramework
desktopApp         Windows Compose Desktop 外壳（待实现）；JSONL host 调试入口已可运行
```

原则：

- 共享模块只用构造注入；DI 框架只出现在各平台的装配层。
- 源协议只有一份。Android 继续用现有进程内适配器，JVM host 的契约测试在桌面 JVM 上运行
  （Linux CI，不需要 Mac）。
- 平台差异只放在 `expect/actual` 或平台模块，不在共享模块里写 `if (android)`。

## 4. 待决策项

| # | 决策 | 建议 |
|---|---|---|
| D1 | iOS UI 方案 | 原生 SwiftUI。Android UI、Media3 与原生服务需平台适配；Backdrop 上游已有 KMP/Skiko 实现，可用于 Windows CMP，但不能直接接入 SwiftUI（§55） |
| D2 | 分发方式 | 仅侧载。含下载并执行字节码的嵌入式 JVM 难以通过 App Store 审核（请核对审核指南原文） |
| D3 | 许可证 | iOS 外壳单独成 GPLv3 模块，可借用 az 的桥与 CI；共享内核保持现有许可。注意 Kotatsu 是 GPL-3.0，而根目录 `LICENSE` 是 Apache-2.0，请先确认现状 |
| D4 | v1 范围 | 仅漫画（Mihon JAR + Kotatsu 解析器）；小说、动漫、翻译、视频后置 |
| D6 | `Content` / `ContentChapter` / `ContentPage` 的二进制兼容垫片 | 这三个类带着给**旧版外部解析器 jar** 用的 JVM 垫片（手写的 `(…, mask, DefaultConstructorMarker)` 合成构造器、`copy$default`，注释写明"parsers compiled before sourceData"），无法进入 commonMain。要么保留垫片、三个类留在 androidMain（iOS 侧用协议 DTO），要么在外部解析器 jar 全部重编后放弃垫片。这是影响外部生态的决定，建议先保留，等你确认旧 jar 的存量再定 |
| D7 | `java.util.Locale` 出现在公开 API | `ContentListFilter`、`SearchableField` 的公开类型是 `Locale`。建议 `expect class Locale` + Android 上 `actual typealias`（Android 二进制不变），iOS 侧另写实现；需要你确认该 API 形状可以改成 expect |
| D5 | 共享数据库 | Room KMP（DAO 无需重写）。S2 已验证 Android 与 JVM 目标：v84 schema 与现有 `84.json` 完全一致，旧迁移链不需要改；iOS 目标待 macOS 验证 |

## 5. 阶段与门槛

相对工作量：S 小、M 中、L 大、XL 很大。

| 阶段 | 内容 | 工作量 | 退出条件 |
|---|---|---|---|
| **P0 验证**（不动主线） | S0：fork az 的 CI，AltStore 装机做真机评估，并向作者核实<br>S1：KMP 工具链（见 §9）<br>S2：Room KMP——新建 v84 schema，且 Android 侧 85 个迁移（约 298 处 `execSQL`）仍通过 `MigrationTestHelper`（**已通过**，见 §9）<br>S3：许可证决定（D3） | S～M | **G0**：真机上的冷启动、内存、五类场景（纯 HTML、JSON API、Cloudflare、图像解扰、带 JS）数字；S1、S2 通过。任一不过则方案回炉 |
| **P1 抽共享内核** | 顺序：`reader-core` → `core-model` → `core-db` → `core-domain`/`core-backup` → `core-net`。每步加元数据编译守卫，Android 测试全绿 | L | 各模块 `compileCommonMainKotlinMetadata` 通过，Android 构建与测试不回退 |
| **P2 源运行时** | 定义 `SourceRuntime` 协议；JVM host 模块；iOS 侧 C 桥；macOS CI 构建 OpenJDK 镜像 | L | **G2**：iOS 真机跑通 Keiyoushi 抽样（覆盖 G0 的五类场景） |
| **P3 iOS 最小应用** | 库、浏览、搜索、详情、阅读、历史；Kototoro 与 Mihon `.tachibk` 备份恢复；WebDAV | XL | 能读完一部漫画，并恢复 Android 备份 |
| **P4 对齐** | 下载、更新检查、tracker、WKWebView 的 Cloudflare 求解、图像解扰 shim、偏好投影 | XL | 功能对齐清单 |
| **P5 可选** | 小说（Legado 走 JVM）、翻译（Vision OCR + 共享 MT）、视频（AVPlayer） | 按需 | — |

## 6. 模块抽取顺序与守卫

1. `reader-core`：已纯 Kotlin，仅依赖 `kotlin.math`，25 个测试文件，隔离守卫测试已禁止 `java.` 导入。
2. `core-model`：不新建模块，直接把 `:parser-api` 原地转为 KMP（步骤与结果见 §11）。其中
   `org.koitharu.kotatsu.parsers.*` 是外部解析器 jar 的二进制 ABI，原样留在 androidMain。
   `ContentRepository` 里的 `LocalMangaSource`/`TestContentSource` 需要上提为抽象。
3. `core-db`：**已完成**（§12）。schema 层闭包约 96 个文件、5000 行，分散在约 15 个功能包，已按原包名跨包搬进
   `:core-db`（实体、DAO、约 14 个投影类 `*Row`/`*WithContent`）。S2 实测的切断清单（14 个文件的 1～9 行机械
   改动 + 4 个类型拆分）已全部落地；唯一的设计层面改动是 `ListFilterOption`：DAO 用它拼 SQL，但它带资源 id
   与图标，现在拆成数据类型 `ListFilterCriteria`，应用在 Repository 边界转换。85 个迁移留在 `androidMain`，
   iOS 直接从 v84 schema 建库。
4. `core-domain`、`core-backup`：**备份格式已完成抽取**（§13）：Kototoro JSON 模型、Mihon/Aniyomi protobuf
   解码与 Usagi 格式模型进入 `:core-backup`；Android 文件读取、ZIP、Venera SQLite 解码与恢复编排留在 `:app`。
   `core-domain` 已抽取内容身份、同步快照、历史/收藏冲突合并、内容导入、更新状态比较（§14），
   四类列表的快照/派生与书架（§15–§16），换源写入计划与章节映射（§17），以及进度、历史恢复与标题匹配规则（§18）。
   UI 资源、词库、历史语言分支选择、Unicode/Locale 适配、事务与完整仓库仍由应用负责。
5. `core-net`：Google Drive 同步、WebDAV、MALSync 映射与 MAL 公共查询 HTTP 协议进入 Ktor commonMain（§19–§22）。
   Android 保留原网络装配、授权/刷新链、平台解析、文件 IO、备份清理与缓存；其余 tracker/scrobbler 待迁移。

守卫：共享逻辑放在 commonMain，平台实现留在各自 source set；`compileCommonMainKotlinMetadata` 作为可移植性检查（JVM 编译
会放过 JVM 专有 API，元数据编译不会）。该检查在 Windows 与 Linux 上即可运行；链接 iOS 二进制只能在
macOS 上。

## 7. 风险与未验证项

- **性能与内存**：Zero 解释器无 JIT，我们没有真机数据；G0 是硬门槛。
- **进程内无隔离**：扩展崩溃即整个 App 崩溃；HotSpot 不能原地重建 VM，只能靠看门狗加整体重启。
- **OpenJDK Mobile 构建脆弱**：依赖 macos-14、Xcode 15.4、JDK 24 与源码补丁，按脚本哈希缓存。
- **图形与 WebView**：图像解扰约 12% 的源，WebView 约 3.8%，Cloudflare 约 15.8%，只能真机验证。
- **iOS 上的 Room 未验证**：S1/S2 已在 Windows 上验证 KMP 插件、AGP 9.3.1 的 KMP 库插件、Room KMP 的
  Android 与 JVM 目标。iOS 目标的 KSP 代码生成、链接与运行需要 macOS（Windows 上 Apple 原生任务被禁用）。
  JVM 目标与 iOS 走同一套非 Android 的代码生成路径，可作代理，但不等于验证。
- **Android 不能换 SQLite 驱动**：旧迁移依赖框架驱动；若改用 `BundledSQLiteDriver`，85 个迁移必须重写为
  `migrate(SQLiteConnection)`，否则会抛 `NotImplementedError`。
- **长期维护两套 UI**。
- **许可证**：见 D3。

## 8. 不做什么

- 不做全量 Compose Multiplatform，不把现有 Compose UI 搬到 iOS。
- 不走 App Store 分发。
- v1 不含小说、动漫、视频、翻译、超分。
- 不替换 Android 现有的进程内源加载器。
- 不复制 az 的 GPLv3 代码进共享内核。

## 9. S1 执行记录（KMP 工具链）

日期：2026-10-02。分支：`feat/kmp-s1-reader-core`（基于 `devel` 的 `fa6500c41`，未提交）。
环境：Windows 11、JDK 17、Gradle 9.7.0、AGP 9.3.1、Kotlin 2.4.0，`KONAN_DATA_DIR` 指向 E 盘。

### 改动

- `reader-core` 转为 KMP 模块：`src/main/kotlin` → `src/commonMain/kotlin`，`src/test` →
  `src/jvmTest`；`build.gradle` 改用 `org.jetbrains.kotlin.multiplatform`，目标为 `jvm()`、
  `iosArm64()`、`iosSimulatorArm64()`。`-Xjspecify-annotations=strict` 只留在 `jvm` 目标上。
- `ReaderCoreIsolationGuardTest` 里硬编码的源码与 fixture 路径同步更新。
- 元数据编译抓到并修复了 3 处 JVM 专有用法：`PageId.kt` 的 `@JvmInline` 缺
  `import kotlin.jvm.JvmInline`；`PagedReaderScene.kt` 两处 `Map.putIfAbsent` 换为语义等价的 `getOrPut`。

### 结果

| 检查 | 结果 |
|---|---|
| `:reader-core:compileCommonMainKotlinMetadata` | 首次失败 3 处（见上），修复后通过 |
| `:reader-core:jvmTest` | 20 个测试类、150 个测试、0 失败，与转换前基线一致（含隔离守卫测试） |
| `:app:compileDebugKotlin` | 通过，0 个编译错误；冷启动 4 分 11 秒，应用消费 `jvm` 变体 |
| 实验：再加 `com.android.kotlin.multiplatform.library`（`androidLibrary {}`） | 元数据编译、`jvmTest`、`assemble` 通过，产出 `reader-core.aar` 与 `iosArm64`/`iosSimulatorArm64` 元数据 jar；`:app:compileDebugKotlin` 通过（消费 Android 变体） |

Windows 上的行为：Apple 原生任务（如 `iosSimulatorArm64Test`）被禁用并给出警告，可用
`kotlin.native.ignoreDisabledTargets=true` 消除；但 iOS / Apple / Native 的元数据与 commonizer 任务
可以运行。因此可移植性检查在 Windows 与 Linux 上即可执行，只有链接与运行 iOS 二进制需要 macOS。

### 结论

S1 通过：KMP 插件、AGP 9.3.1 与 Kotlin 2.4.0 在本仓库中共存，commonMain 元数据编译能有效拒绝 JVM 专有
API，Android 应用可以继续消费该模块。

决定：`reader-core` 保持 `jvm()` 目标，不引入 `androidLibrary`（该模块没有 `androidMain` 需求）。
待首个需要 `androidMain` 的模块（如 `core-db`）再采用 AGP 的 KMP 库插件，上述实验证明其可用。

### 副作用

KMP 之后 `:reader-core:test` 不再存在，对应任务是 `:reader-core:jvmTest`（聚合任务为
`allTests`）。`reader-scene-improvement-plan` §10 中两处"当前该运行的命令"已更新；其余为历史验收记录，
保持原样。

### 未覆盖

- 未在 macOS 上编译、链接或运行 iOS 目标（commonMain 中没有 iosMain 代码，目前只验证了元数据层）。
- 未运行 `:app:testDebugUnitTest` 全量与 instrumented 测试，只验证了应用的 Kotlin 编译与 `reader-core` 的测试。
- 未在 Linux CI 上运行；仓库现有 CI 工作流未改动，也未验证它们是否引用旧的 `:reader-core:test`
  （本次在 `.github`、脚本与文档中未搜到其它引用）。
- S0（真机评估）、S3（许可证）尚未开始。

## 10. S2 执行记录（Room KMP 与 85 个迁移）

日期：2026-10-02。模块：`spikes/room-kmp`，由 `-PwithKmpSpikes` 门控（`settings.gradle` 新增 4 行，默认关闭，
普通构建、Android Studio 同步与 CI 都不会配置它）。

> **后续：** 该 spike 已被真正的 `:core-db` 取代并从仓库中删除（§12），它的 JVM 冒烟测试和迁移链测试分别迁到了
> `core-db/src/jvmTest` 与 `app/src/androidTest`。下面的记录保留作为依据。

### 方法

把 schema 层**复制**（不移动）到一个 KMP 模块：96 个 commonMain 文件（约 5000 行非空行），包括
`MangaDatabase`、36 张表对应的实体、约 35 个 DAO 文件、DAO 的投影类和少量辅助；85 个迁移原样复制到
`androidMain`。然后让 Kotlin 编译器与 Room KSP 指出需要切断的依赖，一轮一轮收敛：
首轮 215 个编译错误 → 26 → 11 → 0，再到 Room KSP 通过。

### 结果

| 检查 | 结果 |
|---|---|
| commonMain 元数据编译 | 0 个错误 |
| Room KSP（JVM、Android 目标） | 通过；Room 在编译期校验了全部 DAO 的 SQL |
| 导出的 v84 schema 对比 `app/schemas/…/84.json` | **完全一致**：`identityHash` 相同（`08b58827…`）、36 张表逐表相同、`setupQueries` 相同 |
| JVM DAO 冒烟测试（`BundledSQLiteDriver`，内存库） | 2/2 通过：`RoomRawQuery` 的 suspend 与 Flow 路径、`@Transaction` 的 open 方法、`@Upsert`、`@Insert(IGNORE)` |
| Android 设备测试（模拟器 API 35）：旧迁移链 | 3/3 通过：按现有 23 个 schema 快照分 22 段（32→33→…→84）全部通过 `runMigrationsAndValidate`；负控制（空的 83→84 迁移）被校验拒绝，说明测试不是空转 |
| 默认构建（不带开关）`:reader-core` 元数据编译与 `jvmTest` | 通过，spike 未被配置 |
| iOS 目标的 KSP、链接、运行 | **未验证**（Windows 上 Apple 原生任务被禁用） |

### 切断清单（把 schema 放进 commonMain 所需的全部改动）

96 个文件中 18 个与原文件不同：

| 类别 | 内容 |
|---|---|
| 14 个文件的小改动（每个 1～9 行） | `System.currentTimeMillis()` → `kotlin.time.Clock`（6 个文件）；`javaClass` → `::class`；`SupportSQLiteQuery` / `SimpleSQLiteQuery` → `RoomRawQuery`（5 个文件，所有原调用都不带绑定参数）；`DatabaseUtils.sqlEscapeString` → 本地函数；`BuildConfig.VERSION_CODE` → 注入的常量；`java.util.LinkedList` → `ArrayList`；`@SuppressWarnings` → `@Suppress`；`option.category.id` → `option.categoryId`（3 处） |
| 4 个类型拆分 | `ListFilterOption`：带 `@StringRes`/`@DrawableRes`、图标和各类 source 展示逻辑的 UI 向类型，却被 3 个 DAO 与 `MangaQueryBuilder` 用来拼 SQL，需要拆成纯数据的过滤条件（本次 210 行 → 22 行）；`SourcesSortOrder`：去掉 `@StringRes`；`ScrobblingStatus`：去掉 UI 的 `ListModel` 接口；`CloudFlareHelper`：只保留 3 个状态常量，其余依赖 OkHttp 与 Jsoup |
| `MangaDatabase` | 声明进 commonMain（加 `@ConstructedBy` 与 `expect` 构造器）；迁移列表与 `Room.databaseBuilder` 进 `androidMain`；依赖 Android 生命周期的 `removeObserverAsync` 留在 app |
| 需随 schema 一起搬的投影类 | 约 14 个 `*Row`、`*WithContent` 等，与 DAO 同包、没有 import，静态依赖图没有发现，是编译器报出来的 |

### 发现

1. **schema 层很小但分散。** 36 张表对应的实体与约 35 个 DAO 分布在约 15 个功能包；`core-db` 抽取是跨包搬迁。
2. **只有一处设计层面的耦合：** DAO 层依赖 UI 向的 `ListFilterOption`。其余全是机械替换。
3. **旧迁移不需要改。** Room 2.8.4 的 `Migration.migrate(SQLiteConnection)` 对 `SupportSQLiteConnection`
   委托给旧的 `migrate(SupportSQLiteDatabase)`；Android 继续用框架驱动即可。若 Android 改用
   `BundledSQLiteDriver`，旧迁移会抛 `NotImplementedError`，必须重写。
4. **既有问题（与 KMP 无关）：** `app/androidTest` 里的 `MangaDatabaseTest.migrateAll` 要从 `1.json` 开始，
   而仓库只保留了 23 个 schema 快照（32–37、39、40、42、68–73、76–80、82–84），所以该测试本来就无法通过。
   上面的分段测试是在现有快照上做的等价验证。
5. 单靠 import 和同包标识符做依赖闭包会严重高估（1822 个文件，实际 96 个），要以编译器为准。

### 结论

S2 通过（Android 与 JVM 目标）。D5 采用 Room KMP。iOS 目标需要在 macOS 上补验 KSP 代码生成与链接。

### 未覆盖

- 未在 macOS 上运行 iOS 目标；iOS 的数据库构建器（`BundledSQLiteDriver`、数据库文件路径）尚未编写。
- spike 是副本，`:app` 仍使用自己的 `MangaDatabase`；`DatabasePrePopulateCallback`（依赖应用资源）未纳入。
- DAO 行为测试只覆盖 `MangaSourcesDao` 的 6 条路径，没有覆盖其余 DAO 与复杂的关系查询。
- 没有测性能（框架驱动与 `BundledSQLiteDriver` 的差异）。
- schema 一致性是对 v84 做的；v84 之前的版本只验证了迁移链能走通并通过 Room 的校验。

## 11. P1 执行记录：`:parser-api` 转为 KMP

日期：2026-10-02。分支：`feat/kmp-p1-parser-api-model`（基于 S1/S2 的三个提交）。

### 做了什么

1. **原地转换**（提交 `build(parser-api): convert to a Kotlin Multiplatform module`）：模块改用
   `org.jetbrains.kotlin.multiplatform` + `com.android.kotlin.multiplatform.library`，目标为 androidLibrary、
   iosArm64、iosSimulatorArm64；所有源码**原样**从 `src/main` 移到 `src/androidMain`，对使用方没有变化。
   `kotlin-parcelize` 因没有任何 `@Parcelize` 用法而去掉；okhttp、okio、jsoup、rhino、`androidx.collection`
   继续作为 androidMain 的 `api` 依赖暴露，因为 `:app` 在传递使用它们。
2. **叶子类型进入 commonMain**：`ContentSource`、`ContentState`、`ContentType`、`ContentRating`、`SortOrder`、
   `ContentTag`、`ContentTagGroup`、`Demographic`、`EbookFormat`、`NovelChapterContent`、`WordSet`、
   `ContentListFilterCapabilities`、`Constants` 和 `InternalParsersApi`，共 14 个文件。改动只有：
   为 `@JvmField`/`@JvmStatic`/`@JvmOverloads` 补 `import kotlin.jvm.*`（common 不会默认导入）、
   `Constants.kt` 的文件级 `@file:JvmName` 写成全限定名、`ContentTag` 去掉一个只用于 KDoc 的 import。

### 验证

| 检查 | 结果 |
|---|---|
| `:parser-api:compileCommonMainKotlinMetadata`（common + iOS 元数据） | 通过 |
| `:parser-api:assemble` | 通过 |
| `:app:compileDebugKotlin` | 通过，0 个编译错误 |
| **字节码 ABI 对比** | 在基线提交上另行构建一份 AAR，对全部 307 个类用 `javap -protected -s` 比较公开/受保护签名（含 JVM 描述符）：类清单与签名**完全一致**，0 个差异 |

### 没有搬的，以及为什么

| 文件 | 原因 | 对应决策 |
|---|---|---|
| `Content`、`ContentChapter`、`ContentPage` | 带旧版外部 jar 的二进制兼容垫片（合成构造器、`copy$default`，纯 JVM） | D6 |
| `ContentListFilter`、`ContentListFilterOptions`、`search/*` | 公开 API 使用 `java.util.Locale`，`search/*` 还用了 `Class` 反射 | D7 |
| `Favicon`、`Favicons` | 依赖 OkHttp 的 `HttpUrl` | 需把 URL 解析换成纯 Kotlin，或留在 Android |
| `util/*` 里 `nullIfEmpty`、`formatSimple` 等 | 这些纯函数和 `java.net`、`DecimalFormat` 等 JVM 函数混在同一个文件里。拆分需要 `@file:JvmMultifileClass` 保持门面类名；我试过，可行，但在 D6 之前没有用到，所以回退了 | — |

### 结论

`:parser-api` 已经是 KMP 模块，且已有 14 个叶子类型可被 iOS 与后续共享模块使用，其中包括 `core-db` 切断清单里需要的
`ContentSource`、`ContentState`、`ContentType`、`SortOrder`。再往前走需要先做 D6、D7 两个决定。

### 未覆盖

- 没有运行应用的单元测试与 instrumented 测试，只验证了编译与字节码 ABI。
- iOS 目标仍只验证到元数据层。
- 14 个文件里没有加入新的测试；它们大多是枚举与数据类，现有行为由使用方的测试间接覆盖。

## 12. P1 执行记录：数据库抽成 `:core-db`

日期：2026-10-02。分支：`feat/kmp-p1-parser-api-model`。按 §11 末尾的建议，采用 `ListFilterCriteria` 方案，
schema 快照目录 `app/schemas` 留在 `app`（已提交，`app` 的 androidTest 以 assets 读取；`:core-db` 的 Room 插件
指向它）。

### 做了什么

- 新建 KMP 模块 `:core-db`（androidLibrary + jvm + iosArm64 + iosSimulatorArm64）。用 `git mv`（保留历史）把
  88 个 schema 文件从 `:app` 搬进 `commonMain`、85 个迁移搬进 `androidMain`，**包名全部不变**，所以 `:app` 里
  绝大部分 `import` 不需要改。
- 应用 S2 验证过的切断清单：`System.currentTimeMillis()` → `kotlin.time.Clock`（6 个文件）、`javaClass` →
  `::class`、`SupportSQLiteQuery`/`SimpleSQLiteQuery` → `RoomRawQuery`（5 个文件）、`sqlEscapeString` → 本地
  函数、`LinkedList` → `ArrayList`、`@SuppressWarnings` → `@Suppress`。
- `MangaDatabase` 一分为三：声明与 `@ConstructedBy` 进 `commonMain`；`getDatabaseMigrations(context)` 进
  `androidMain`；依赖应用资源与进程生命周期的 `MangaDatabase(context)` 构建函数和 `removeObserverAsync` 留在
  `:app`（`MangaDatabaseFactory.kt`，同包，调用方不用改）。
- 新增 `ListFilterCriteria`（`:core-db`，纯数据，保留原来的层级和 `groupKey`，不支持的选项仍然抛异常）。
  `:app` 的 `ListFilterOption` 保持不变（71 个文件在用），在 Repository 边界用 `toCriteria()` 转换。
- `CloudFlareHelper` 的三个持久化状态常量放进 `:parser-api` commonMain 的 `CloudFlareProtection`，原类引用它们，
  编译出的常量值不变。`:parser-api` 另加了 `jvm()` 目标，供 `:core-db` 的 JVM 测试使用。
- `:app` 的构建配置去掉 Room 插件、`ksp room-compiler` 和 `ksp { arg('room.generateKotlin') }`，加上
  `implementation project(':core-db')`。
- 删除已被取代的 S2 spike（`spikes/room-kmp` 与 `-PwithKmpSpikes` 开关）。

### 对调用方的改动（行为语义不变）

| 改动 | 原因 |
|---|---|
| `MangaSourcesDao.setPinned`、`setEnabled` 新增参数 `appVersionCode: Int`（5 个调用点传 `BuildConfig.VERSION_CODE`） | `BuildConfig` 属于 `:app`，模块里拿不到 |
| `TracksDao.observeUpdatedContent`、`SuggestionDao.observeAll` 改收 `ListFilterCriteria`（2 个 Repository 加 `.toCriteria()`） | DAO 不再依赖带资源 id 的 UI 类型 |
| `SourcesSortOrder.titleResId` 变成 `:app` 里的扩展属性（1 个调用点补 import） | 枚举搬走后不能带 `@StringRes` |
| `ComposeNovelMarkingsSheet` 里 2 处 `marking.note` 的智能转换改成 `.orEmpty()` | Kotlin 不允许对跨模块的公开属性做智能转换 |

### 验证

| 检查 | 结果 |
|---|---|
| `:core-db` 的 commonMain 元数据编译、JVM 与 Android 的 Room KSP | 通过 |
| 导出的 v84 schema 对比已提交的 `app/schemas/…/84.json` | **字节级一致**（用空的临时目录强制导出后 `cmp`）。普通构建不会重写它，因为 Room 发现内容相同 |
| `:app:compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin` | 通过，0 个错误 |
| `:core-db:jvmTest`（新增：查询构建器 6 个、DAO 冒烟 3 个） | 9/9 通过 |
| `:reader-core:jvmTest` | 150/150 通过 |
| `:app:testDebugUnitTest`（全量） | 2823 个测试，1 个失败（见下，与本次无关） |
| 模拟器（API 35）上 `core.db` 包的设备测试 | 24 个：19 通过、4 失败、1 跳过。4 个失败在抽取之前的基线提交上**以完全相同的原因失败**（见下） |
| 其中的迁移相关测试 | 原有的 `Migration83To84Test`（2 个）、`SourceOriginsMigration77To78Test`（3 个，含 DAO 的增删改查）、新增的 `MangaDatabaseSnapshotChainTest`（按 23 个快照走 22 段 + 空迁移的负控制）全部通过 |

### 顺带发现的既有问题（不是本次引入的）

1. `MangaDatabaseTest` 有 4 个失败：`migrateAll`、`migrate65To66…`、`migrate74To75…` 因缺 `1.json`/`65.json`/
   `74.json` 快照而抛 `FileNotFoundException`；`versions` 因迁移列表里的降级迁移 `Migration24To23` 破坏了
   "起止版本连续"的断言（`expected:<25> but was:<23>`）。已在基线提交上重跑确认完全一致。
2. `ListSortOptionTest.favorites exposes seven criteria…` 失败（`expected:<7> but was:<8>`）：测试与被测代码是
   同一个提交（`f9953753d`，2026-09-14）加入的，当时已在 `devel`；本分支没有改动排序相关的代码和测试。这是由
   "输入未变"推出的结论，没有在基线上实际运行。
3. `RealDataMigrationTest` 因缺少真实数据库副本而被跳过。

### 未覆盖

- **iOS 目标：** KSP 代码生成、链接与运行仍需 macOS；iOS 的数据库构建器（`BundledSQLiteDriver`、文件路径）尚未编写。
- **R8 / release 构建：** 没有跑 `assembleRelease`/`assembleNightly`，也没有在设备上运行混淆后的包。Room 的生成类现在
  位于库模块里，应在发布前用 nightly 构建验证一次，确认 `MangaDatabase_Impl` 与新增的 `MangaDatabaseConstructor`
  没有被混淆掉。
- 只跑了 `core.db` 包的设备测试，没有跑完整的 instrumented 套件。
- 没有测启动耗时与 APK 体积的变化。
- `DatabasePrePopulateCallback` 仍在 `:app`，依赖应用资源。

### 结论

P1 的数据库部分完成：schema、DAO 与迁移已是 KMP 共享模块，Android 行为在已覆盖的测试范围内没有回归。
P1 剩余：`core-domain` / `core-backup`、`core-net`；D6、D7 仍待决定。

## 13. P1 执行记录：备份格式抽成 `:core-backup`

日期：2026-10-02。从工作区中尚未提交的备份抽取继续完成验证与收尾；本节记录整个抽取阶段。

### 共享边界

- 新建 `:core-backup`，目标为 androidLibrary、jvm、iosArm64、iosSimulatorArm64。Android 应用依赖该模块。
- Kototoro 的 18 个备份模型、`BackupSection`、Mihon/Aniyomi protobuf 模型、Usagi 模型、外部备份 DTO 与
  `ExternalBackupLibrary` 进入 commonMain，**包名和序列化字段名不变**。保留 `WORK_*` 与实体图谱 section 名，
  用于读取旧版备份；不恢复已移除的实体体系。
- `readExternalBackupBytes` 以 Okio 读取普通或 gzip 数据，拒绝已有的三种 JSON 文件头；
  `decodeMihonOrAniyomiBackup` 负责 protobuf 解码、来源信息、分类、收藏时间和阅读进度映射。
- `:app` 保留 `Uri`/`Context` 读取、ZIP 文件处理、Venera SQLite 解码、导出服务、`BackupRepository` 与
  `BackupPayloadGuard`。共享模块依赖已抽取的 `core-db` 和 `parser-api` 叶子类型，沿用现有 serialization/Okio 版本，
  不引入新的依赖库或 DI 框架。

### 切断平台依赖

| 原依赖 | 迁移后 |
|---|---|
| `BackupIndex` 的 `BuildConfig` 构造器与 Kotatsu 兼容工厂 | 同包的应用层工厂 `BackupIndexFactory.kt`，输出的应用 id、版本及 generation/schema 默认值不变 |
| `BackupSection.of(ZipEntry)` 与 `Locale.ROOT` | 共享 `of(String)` 使用语言无关的 `lowercase()`；应用同包扩展保留 ZIP 调用入口 |
| category 默认排序、history 无进度标记依赖应用 UI 类型 | 共享持久化常量 `NEWEST`、`-1f`；应用测试核对其与原值一致 |
| `mapToSet` 所在文件带 JVM 依赖 | `mapTo(LinkedHashSet(...))`，保留标签去重与插入顺序 |
| Usagi 模型的 `System.currentTimeMillis()` | `kotlin.time.Clock.System` |
| Aniyomi 解码里的 Android 日志 | 移除日志，映射逻辑保持原样 |
| 跨模块公开属性的智能转换 | 应用恢复逻辑先读取局部变量，随后验证并写入历史记录 |

额外修复：共享读取函数现在在成功、JSON 拒绝、过短文件和 gzip 解码失败时都关闭输入流，函数 KDoc 明确所有权。
这是原 Android 调用方由外层 `InputStream.use` 保证、但新的独立共享入口需要自行保证的资源边界。

### 验证

| 检查 | 结果 |
|---|---|
| `:core-backup:compileCommonMainKotlinMetadata` | 通过，拒绝 JVM/Android 专有 API 的可移植性检查有效 |
| `:core-backup:jvmTest` | 2 个测试类，13/13 通过 |
| `:app:compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin` | 通过，0 个编译错误 |
| `:app:testDebugUnitTest --tests "org.skepsun.kototoro.backups.*" --tests "org.skepsun.kototoro.sync.*"` | 23 个测试类，89/89 通过，0 跳过；覆盖旧备份兼容、来源 id、导出映射、恢复守卫、WebDAV 编排及同步协议 |
| `git diff --check` | 通过 |

新增共享测试包括固定 protobuf 字节夹具（独立于模型 serializer，验证 Aniyomi
field 501 与 source registry 103）、gzip/原始输入、动漫与漫画来源映射、仅历史记录的恢复、最新阅读点、时间戳
归一化、进度边界和失败时关闭输入流；应用侧验证共享默认值与 `BuildConfig` 工厂。

Windows 验证遇到另一个 Gradle 测试 worker 占用原 `core-backup` 运行时 JAR，随后默认 `app/build` 的 ASM
中间产物也出现不可读取的错误。为保留现有进程，使用被忽略的本地 `build/kmp-backup-validation.init.gradle`
将两个模块的验证输出分别放到 `build/kmp-backup-validation/core-backup` 与 `build/kmp-backup-validation/app`；
项目的 `build.gradle`、默认输出目录和全局环境配置不受影响。

正常环境的复验命令（本次在命令中另加上述本地 `-I` 参数绕开输出目录占用）：

```powershell
./gradlew.bat :core-backup:compileCommonMainKotlinMetadata :core-backup:jvmTest `
    :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest `
    --tests "org.skepsun.kototoro.backups.*" --tests "org.skepsun.kototoro.sync.*" --console=plain
```

### 未覆盖与后续

- iOS 目标仍只验证 commonMain 元数据；原生编译、链接、gzip 运行与 Room 依赖的 KSP 代码生成需要 macOS。
- 尚未验证完整 Android 导出/恢复设备流程及 R8/nightly 包；已有数据库迁移设备测试结果仍见 §12。
- 应用单元测试只运行备份与同步相关包，本轮没有重跑全量测试；已知的排序测试基线失败仍见 §12。
- `core-backup` 共享的是格式与外部 protobuf 解码，完整 Kototoro ZIP 恢复编排尚未共享。
- P1 下一步为 `core-domain`，随后 `core-net`。D6/D7 继续保留为待决策项，不改旧外部解析器的 JVM ABI。

### 结论

P1 的备份格式阶段完成：Android 已消费共享模型与 protobuf 解码，共享元数据和本轮回归全部通过。
按单一职责将平台文件访问与共享格式分离，复用既有模块和依赖；完整恢复服务与 iOS 平台装配仍在后续范围内。

## 14. P1 执行记录：`:core-domain` 第一批业务规则

日期：2026-10-02。基于 §13 的工作区继续抽取，不改变数据库 schema 或源加载 ABI。

### 范围选择

梳理历史、收藏、更新列表后发现：`HistoryLibraryDeriver`、`FavouriteLibraryDeriver`、`UpdatesDeriver` 与
`FeedDeriver` 虽然在内存中工作，输入仍引用 `ListFilterOption`、带资源 id 的 `ListSortOrder`、浏览 UI 的
`BrowseGroupTab`/`SourceTag` 和依赖 `Content` 的 `GlobalTagBlacklist`。把整个目录直接移入 commonMain
会把 UI 或 D6 尚未决策的 JVM 模型一起拉进共享模块。

本轮先共享已有明确存储边界、覆盖实际历史/收藏/更新状态的规则，作为 `core-domain` 的第一批：

| 共享代码（包名不变） | 行为 |
|---|---|
| `core/model/ContentIdentityKeys` | 基于 source + URL/public URL 的内容身份，不用标题/封面猜测身份 |
| `sync/google/data/model/GoogleDriveSyncModels` | 现行同步 DTO、序列化字段、协议门槛、旧 work 快照到 content v3 的转换、与共享数据库实体互转 |
| `sync/google/domain/GoogleDriveSyncMerger` | 内容去重与所有者重映射；历史/收藏的最后更新获胜与 tombstone；统计去重；更新状态、设置版本、源配置和扩展描述合并 |
| `sync/google/domain/GoogleDriveSyncContentImport` | 复用本地身份；ID 冲突时分配负数 ID；跳过无 URL 内容；输出远端到本地的 ID 映射 |
| `tracker/data/TrackFeedRules` | 更新状态的新旧比较、恢复时未读计数合并、日志能否清除更新的时间边界 |

模块目标为 androidLibrary、jvm、iosArm64、iosSimulatorArm64，应用新增对 `:core-domain` 的依赖。
产品依赖沿用 `core-db`（传递使用 parser-api 叶子类型）和现有 serialization；没有新网络库、DI 框架或转发层。

### 平台边界与改动

- 4 个原文件移入 commonMain；包名、类名、序列化字段名与协议常量保持不变。
- `TrackFeedCompatibility.kt` 拆出 3 个纯函数到共享模块；`normalizeTrackFeedState` 的 Android Room 事务仍在应用。
- `SyncContentStore`、`importSyncContent` 与结果类型改为公开，供应用和后续 iOS 适配器调用；结果构造器仍为 internal。
  接口原本就存在，Android 的 DAO 适配器继续原样实现它，未新增一对一仓库抽象。
- `SyncJsonSource.toEntity` 两处 JVM 时钟改为 `kotlin.time.Clock.System`，保留缺少时间戳时使用当前时间的语义。
- Google Drive 授权与网络 API、数据库事务、扩展文件访问与安装、设置读写仍在 `:app`。
- 4 个已有测试文件随规则移入 `core-domain/src/jvmTest`，避免应用与共享模块重复维护同一份测试。

### 验证

| 检查 | 结果 |
|---|---|
| `:core-domain:compileCommonMainKotlinMetadata` | 通过 |
| `:core-domain:jvmTest` | 6 个测试类，35/35 通过，0 跳过 |
| `:app:compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin` | 通过，0 个编译错误 |
| 应用相关包的 `:app:testDebugUnitTest` | 63 个测试类，345/345 通过，0 跳过；范围见下方命令 |
| `ContentIdentityKeys`、`GoogleDriveSyncMerger` 对比抽取前 HEAD 源码 | 忽略换行编码后逐行一致；纯业务逻辑未重写 |
| `git diff --check` | 通过 |

新增覆盖固定旧版 JSON 夹具、协议字段名、重复压缩的幂等性、双向合并的删除标记、更新清零、设置版本与日志
清除边界。Android 应用继续使用抽取后的实现，原扩展描述合并和 UI 派生等使用方的测试也通过。

复验命令（本次沿用 §13 的本地 `-I build/kmp-backup-validation.init.gradle`，复用独立 Android 验证目录）：

```powershell
./gradlew.bat :core-domain:compileCommonMainKotlinMetadata :core-domain:jvmTest `
    :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest `
    --tests "org.skepsun.kototoro.backups.*" --tests "org.skepsun.kototoro.sync.*" `
    --tests "org.skepsun.kototoro.tracker.*" --tests "org.skepsun.kototoro.history.*" `
    --tests "org.skepsun.kototoro.favourites.*" --tests "org.skepsun.kototoro.migration.*" `
    --tests "org.skepsun.kototoro.core.parser.StoredContentIdentityResolverTest" --console=plain
```

### 未覆盖与后续

- 这是 `core-domain` 第一批，完整历史/收藏 Repository、列表派生、换源迁移计划与进度计算仍在应用。
- 下一步将列表派生输入转换为纯数据筛选条件，拆开排序/浏览分组的资源依赖，再迁移已有派生器及其测试；
  `Content`/`ContentChapter` 的迁移继续受 D6 约束。
- 本轮不迁移 Google Drive 网络层；`core-net` 与 iOS 平台适配器仍在后续阶段。
- iOS 原生编译、链接与运行仍需要 macOS；commonMain 元数据检查不能替代原生验收。
- 本轮没有重跑全量应用测试、设备流程或 R8/nightly 构建；既有全量测试基线问题仍见 §12。

### 结论

`core-domain` 第一批完成：共享规则已被 Android 消费，元数据、共享测试与相关应用回归全部通过。
以现有存储接口隔离平台实现、迁移而不复制业务算法与测试，保持单一职责和单份实现；下一批继续拆分列表派生输入。

## 15. P1 执行记录：共享历史、更新与动态列表派生

日期：2026-10-02。继续 §14 的 `:core-domain`，完成第二批；数据库 schema、同步格式及外部解析器 ABI 未改变。

### 共享范围与 Android 接入

| 移入 core-domain/commonMain 的代码（原包名保留） | 职责 |
|---|---|
| `HistoryLibrarySnapshot`、`HistoryLibraryDeriver` | 历史快照、十类排序、快速筛选、空间绑定、来源与内容分组 |
| `UpdatesSnapshot`、`UpdatesDeriver` | 更新实体组快照、标签/分类筛选、稳定排序 |
| `FeedSnapshot`、`FeedDeriver` | 动态快照、待更新合成条目与 owner 去重、分类作用域、置顶排序和数量窗口 |
| `ContentGroup`、`OriginGroup`、`SourceGroup` | 来源分类数据，保留枚举顺序及对应位掩码 |
| `BrowseGroupTab`、`SourceTag` | 分组匹配、来源筛选与持久化 ID/旧 ID 兼容规则 |
| `ListSortOrder`、`isReadingCompleted` | 排序枚举及其旧顺序/持久化名称、历史完成进度阈值 |
| `TagBlacklist` | 由平台实现标签标题匹配的最小接口，默认 `Empty` 不屏蔽标签 |

- 三类派生器复用 `core-db/ListFilterCriteria`。Android ViewModel 在调用边界通过已有
  `toCriteria()` 将 `ListFilterOption` 转为共享条件，未复制算法或另建筛选枚举。
- `ListFilterCriteria.Tag` 新增可选 `title`/`key`：SQL、更新、动态仍按 `tagId` 匹配；
  历史保留原本的“标题 + 键”匹配。Android 映射同时填充三者，其他平台调用历史筛选时也需提供标题和键。
- Android 资源标题和图标改为应用侧扩展属性；使用方补充导入，包括设备测试源码。
  排序枚举名称、顺序及 HISTORY/FAVORITES 的迭代顺序与旧 EnumSet 一致。
- `GlobalTagBlacklist` 实现共享 `TagBlacklist`；Android 词库、繁简转换及 `Content` 便利函数继续留在应用。
  iOS 需装配自己的匹配实现，当前尚未共享完整词库。
- `ReadingProgress.isCompleted` 委托共享函数，保留历史原来的 `0.99999f` 阈值。
  收藏派生器的 `0.999f` 阈值继续保留，未趁迁移修改产品语义。
- `TrackRowSeed` 仍是 Android 快照存储内部类型，独立放在应用文件中；
  DAO、SnapshotStore、卡片映射、日期分组、ViewModel、事务及 Hilt 继续由应用负责。
- 收藏快照含 Compose `Immutable` 与应用 `ScrobblingStatus`，本轮只让其复用已共享的排序/分组模型，
  `FavouriteLibraryDeriver` 与收藏快照本身留到后续批次。

### 验证

| 检查 | 结果 |
|---|---|
| `:core-domain:compileCommonMainKotlinMetadata` | 通过 |
| `:core-domain:jvmTest` | 9 类，49/49 通过，0 跳过 |
| `:core-db:jvmTest` | 2 类，9/9 通过，0 跳过 |
| `:app:compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin` | 通过 |
| 应用相关包 `:app:testDebugUnitTest` | 65 类，360/360 通过，0 跳过 |
| `git diff --check` | 通过 |

6 个既有浏览分组测试随模型移入共享 JVM 测试；新增 8 个共享边界测试覆盖三类列表的标签身份差异、
完成阈值与并列排序、平台标签匹配、分类查找键、先排序后截取、待更新合成去重和来源位掩码。
应用侧保留 47 个既有派生器测试，继续从 UI 筛选映射与实际黑名单进入共享实现；
另验证来源分组及词库别名、中文标签、raw 选择的匹配适配。

复验沿用 §13 的本地独立构建目录，未清理或终止其他构建进程：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" `
    :core-domain:compileCommonMainKotlinMetadata :core-domain:jvmTest :core-db:jvmTest `
    :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest `
    --tests "org.skepsun.kototoro.backups.*" --tests "org.skepsun.kototoro.sync.*" `
    --tests "org.skepsun.kototoro.tracker.*" --tests "org.skepsun.kototoro.history.*" `
    --tests "org.skepsun.kototoro.favourites.*" --tests "org.skepsun.kototoro.migration.*" `
    --tests "org.skepsun.kototoro.core.parser.StoredContentIdentityResolverTest" `
    --tests "org.skepsun.kototoro.core.model.GlobalTagBlacklistTest" `
    --tests "org.skepsun.kototoro.core.jsonsource.SourceGroupManagerTest" --console=plain
```

### 后续边界

- 下一批处理收藏快照/筛选输入的 Compose 与追踪状态依赖，随后继续换源迁移、进度规则与 `core-net`。
- 本批完成的是列表数据与派生规则共享；完整 Repository 和平台存储编排尚未共享。
- Windows 只验证 commonMain 元数据及 JVM/Android 编译测试，iOS 原生编译、链接和运行需 macOS。
- 尚未执行本批设备 UI 流程、全量应用测试或 R8/nightly；已知基线问题仍见 §12。
- D6/D7 继续待决策，不修改 `Content`/`ContentChapter` 的 JVM ABI 或解析器 Locale 公共接口。

## 16. P1 执行记录：共享收藏快照、派生与继续阅读书架

日期：2026-10-02。在 §15 之后继续抽取收藏列表；包名、字段名、类别 ID 与可见列表身份保持原约定。

### 边界与实现

- `FavouriteLibrarySnapshot`、卡片行/标签、facet、成员关系与筛选元数据共 6 个类型迁入 `core-domain/commonMain`。
  `FavouriteCardRow.readingStatus` 改为数据库持久化状态名称字符串，复用 `ListFilterCriteria.ReadingStatus.statusName`。
  Android SnapshotStore 仍按原规则解析有效状态或根据进度回退，再输出枚举 `name`；
  `ScrobblingStatus` 和它的 UI `ListModel` 接口留在应用，没有再造共享枚举。
- `FavouriteLibraryDeriver` 使用共享筛选条件与 `TagBlacklist`。Android 状态组装转换 UI 筛选、
  解析词库并传入匹配器，空选择用 `TagBlacklist.Empty`。类别成员置顶、手动顺序、OR/AND 筛选、
  同序时 entity ID 决胜、来源位掩码与 `0.999f` 完成阈值保持原行为。
- 收藏数据上的 Compose 注解移出共享代码；应用以 `app/compose-stability.conf` 精确列出原来
  6 个 `@Immutable` 类型，由 `composeCompiler.stabilityConfigurationFiles` 读取，继续沿用
  “快照发布后不修改集合”的原契约，没有将整个包或 Kotlin 集合全局标成稳定。
  方式依据 [Android 官方稳定性文档](https://developer.android.com/develop/ui/compose/performance/stability/fix)
  与 [Kotlin 2.4 Gradle 插件 API](https://kotlinlang.org/api/kotlin-gradle-plugin/compose-compiler-gradle-plugin/org.jetbrains.kotlin.compose.compiler.gradle/-compose-compiler-gradle-plugin-extension/stability-configuration-files.html)。
- `FavouriteFacetTag.toContentTag` 留为应用侧扩展函数，继续使用原来源工厂；已有 chip 身份测试核对
  生成标签 ID 与共享 facet ID 一致。
- `selectFavouritesShelfRows` 与 12 项上限移入 `FavouritesShelfRules`；包含网格卡片的
  `FavouritesShelfState` 留在应用。更新章节优先、阅读中内容随后、排序与截取语义未改。
- 修复迁出模块后失效的可空字段智能转换：卡片副标题/作者显式过滤空值，选择作品 ID 显式回退到 entity ID。
  继续使用原卡片映射、SnapshotStore、ViewModel、DAO、搜索与平台事务，不复制业务规则，不新增产品依赖。

### 验证

- 19 个既有收藏派生测试与 4 个书架测试随源码移入共享 JVM 测试；
  另增 2 个共享测试，固定收藏与历史不同的完成阈值，以及标签 OR / 状态 AND 的组合边界。
- Android 新增 2 个状态组装测试，验证追踪状态名称映射及中文词库别名/raw 黑名单适配；
  原卡片字段、facet 身份、类别计数、筛选后保留完整 row map 的测试继续通过。
- 本批共享 JVM 测试 11 类，74/74 通过；应用收藏、历史、追踪与黑名单相关回归 25 类，167/167 通过，
  均无失败或跳过。commonMain 元数据、Android 主代码、单元测试与设备测试源码编译通过。
- 合并后续换源规则后的最终复验见 §17；`git diff --check` 通过。

本批完成收藏数据与规则共享；iOS 仍需平台词库匹配器、存储装配与原生构建验证，完整收藏仓库仍在应用。

## 17. P1 执行记录：共享换源写入计划与章节映射

日期：2026-10-02。用户继续推进后，在 §16 验证通过的基础上抽取换源规则，不修改外部解析器模型 ABI。

### 实现与行为边界

| 共享类型/规则 | 内容 |
|---|---|
| `MigrationContentInput`、`MigrationChapter` | 窄输入：作品 ID、来源名、章节 ID/卷号/编号/分支/上传时间及平台选定的历史分支 |
| `MigrationChapterMapper` | 同分支优先；缺失分支取最大章节组；卷号与正编号优先，否则按位置并钳制到末章 |
| `MigrationSnapshot`、`MigrationPlan`、`MigrationPlanRules` | 输出收藏、历史、偏好、追踪绑定、更新状态、笔记、阅读会话与跳转记录的写入计划 |
| `MigrationDataFlag`、`MigrationMode`、`MatchMode` | 原迁移开关位值、模式和持久化名称，原样移动 |

- Android `MigrationPlanner.plan(Content, Content, ...)` 和 `ChapterIdMapper` 保留原调用入口，
  将 parser 模型投影为共享窄输入并消费共享结果。没有迁移或修改 `Content`/`ContentChapter` 的 JVM 签名。
- 历史分支仍由原 Android `getPreferredBranch` 根据当前章节或语言策略选择，仅在进度迁移需要时调用；
  commonMain 消费结果。iOS 适配器也需提供正确的回退分支（`null` 可以是实际分支）。
- 原行为保持：未知旧章节按进度比例定位并保留 percent；已知章节按卷号/编号或位置重映射并将 percent
  置为 -1；没有新章节不删除旧历史；统计仅在 REPLACE 模式迁移；笔记 COPY 分配新 ID、REPLACE 保留 ID。
- 历史回退分支采用平台选择，笔记/会话章节映射采用最大分支，继续保留这两个原本不同的规则。
- 共享模块只生成计划；Android `MigrateUseCase` 的数据库事务、实际写入、远端追踪与源访问保留。
  标题相似度/Unicode NFKC、源健康检查、智能搜索及迁移设置仍在应用，未引入新依赖。

### 最终验证

| 检查 | 结果 |
|---|---|
| `:core-domain:compileCommonMainKotlinMetadata` | 通过，收藏与迁移共享源码不含 Android/JVM 专有导入 |
| `:core-domain:jvmTest` | 13 类，90/90 通过，0 跳过 |
| `:app:compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin` | 通过 |
| `:app:testDebugUnitTest`（下列范围） | 41 类，245/245 通过，0 跳过 |
| `git diff --check` | 通过 |

9 个既有规划测试移入共享模块；新增 4 个共享规划边界测试和 3 个章节映射测试，覆盖无目标章节、
平台分支与最大分支的差异、比例进度、章节缺失、卷号身份、同大小分支顺序及原位置钳制行为。
应用保留 5 个章节适配测试，并新增 3 个规划适配测试，验证历史分支传递、未知旧章节以及禁用进度时跳过平台分支选择。

复验命令（沿用 §13 的 ignored 本地 init script 与独立 Android 输出目录）：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" `
    :core-domain:compileCommonMainKotlinMetadata :core-domain:jvmTest `
    :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest `
    --tests "org.skepsun.kototoro.favourites.*" --tests "org.skepsun.kototoro.history.*" `
    --tests "org.skepsun.kototoro.tracker.*" --tests "org.skepsun.kototoro.migration.*" `
    --tests "org.skepsun.kototoro.core.model.GlobalTagBlacklistTest" --console=plain
```

### 后续

- 继续处理标题匹配中的 Unicode/Locale 平台边界、尚未共享的历史更新/进度模型及 `core-net`。
- P1 的核心业务规则已覆盖四类列表和迁移计划，完整仓库/网络服务与 iOS 装配尚未完成。
- 本批没有执行设备迁移事务/UI 流程、全量应用测试或 R8/nightly；已知基线问题仍见 §12。
- iOS 原生编译、链接、运行与真机验收仍需 macOS；Windows 元数据与 JVM 验证不替代该门槛。

## 18. P1 执行记录：共享进度、历史章节恢复与标题匹配规则

日期：2026-10-02。继续 §17，优先共享现有纯规则并由 Android 消费，未增加依赖或修改数据库 schema。

### 实现与行为边界

| commonMain 类型/规则 | Android 保留的边界 |
|---|---|
| `ReadingProgress`、`ProgressIndicatorMode` | 偏好读取、资源/进度标签与 Compose 渲染 |
| `ReadingProgress.fromHistory` | 历史 DAO 与收藏行投影，复用完成归一化/有效性规则 |
| `recoverHistoryChapterId` | parser 章节 ID 投影、历史实体更新与数据库写入 |
| `TitleMatchingRules` | NFKC、Locale 小写转换和 Unicode 字母正则，通过窄回调传入 |

- `ReadingProgress` 和模式枚举保留原包名、字段、枚举顺序及名称；章节数继续向上取整，完成阈值仍为
  0.99999，与收藏快捷筛选的 0.999 阈值保持区别。`fromHistory` 提取历史单条/批量查询和收藏卡片中
  重复的逻辑，先将已完成进度归一到 1，再验证模式、进度范围与章节数。
- 移除共享类的 Android 注解后，`app/compose-stability.conf` 精确增加 `ReadingProgress`，保留原
  `@Immutable` 合约；`app/proguard-rules.pro` 为模式枚举增加原 `@Keep` 等效规则，保护偏好反射读取。
  历史卡片原本直接使用 raw percent，继续保持该路径，未统一为收藏/Repository 的归一化行为。
- 历史章节恢复只消费 ID 列表：本地作品、章节仍存在、章节列表缺失或存在不同 EPUB 父章节均不恢复。
  缺失远端章节按 `size * percent` 的原 Float→Int 规则定位；越界不钳制到末章。这与换源章节映射的
  钳制行为不同。原 NaN/微小负进度的转换结果也保留，未在迁移中加入额外纠错。
- 标题规则共享字母/数字过滤、UTF-16 Levenshtein 评分、备选标题匹配、括号清理、俄文章节后缀清理、
  短标题回退及查询排序/去重。评分函数用平台提供的规范化回调，仍在满分时提前结束匹配。
  私有编辑距离使用 `IntArray`，通过应用契约测试与现有 parser 工具逐对比较；parser 旧公共工具与 ABI 保留。
- Android `TitleNormalizer` 仍按 NFKC → `Locale.ROOT` 小写 → 共享过滤处理；`TitleSimilarity` 保留原入口，
  深搜继续使用 `Locale.getDefault()`，Unicode 过滤继续用原 `\p{L}` 正则。共享俄文后缀规则以 `[0-9]`
  明确原 JVM 默认 `\d` 的 ASCII 数字含义，不引入默认语言变化。
- 回归记录了既有 Unicode 限制：短标题倒序分支按 Char 插入，会破坏补充平面字符的代理对，随后被过滤；
  较长 Unicode 标题可保留这类字符。迁移保留原行为，后续修复需作为独立搜索行为变更验证。

以上通过窄输入与必要的函数回调隔离平台能力，复用已有规则，避免新的 DI、Unicode 或网络依赖。
`ContentHistory` 的 Parcelable/Instant、历史更新协程作用域、数据库事务与 scrobbling 仍留在应用。

### 验证

| 检查 | 结果 |
|---|---|
| `:core-domain:compileCommonMainKotlinMetadata` | 通过 |
| `:core-domain:jvmTest` | 16 类，116/116 通过，0 跳过 |
| `:app:compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin` | 通过 |
| `:app:testDebugUnitTest`（下列范围） | 32 类，176/176 通过，0 跳过 |
| `git diff --check` | 通过 |

新增 26 个共享测试，覆盖模式持久化合约、完成阈值、舍入/有效性、EPUB 父章节、历史越界恢复、编辑距离、
备选匹配短路、括号与查询规则；应用新增 4 个测试，覆盖 parser 距离合约、土耳其 Locale、补充平面字符
及兼容/组合 Unicode 规范化。原智能换源、重复匹配、收藏/历史映射、卡片进度标签与备份常量回归继续通过。

沿用 §13 的 ignored 本地 init script 与独立 Android 输出目录：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" `
    :core-domain:compileCommonMainKotlinMetadata :core-domain:jvmTest `
    :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest `
    --tests "org.skepsun.kototoro.favourites.*" --tests "org.skepsun.kototoro.history.*" `
    --tests "org.skepsun.kototoro.migration.*" `
    --tests "org.skepsun.kototoro.list.ui.compose.ContentCardStatusTest" `
    --tests "org.skepsun.kototoro.backups.data.model.BackupSharedConstantsTest" --console=plain
```

### 下一批网络层入口与剩余门槛

- `GoogleDriveSyncApi` 目前自行创建 OkHttp 客户端（连接 15 秒、读写 30 秒、整次调用 60 秒），
  执行同步文件查询/版本/下载/上传/删除与网络异常重试。授权在 `GoogleDriveSyncAuth`，可保留 Android
  Google Identity 装配，将 HTTP 请求/JSON 响应另行抽到 `core-net` 并以 mock transport 验证。
- `WebDavBackupUploader` 依赖应用 `BaseHttpClient` 的代理等配置，PROPFIND 调用超时 30 秒、传输 120 秒；
  另有 Basic auth、XML DOM/日期解析、文件流、重试与备份保留策略。迁移需明确传输、元数据解析和文件 I/O
  边界，不能直接以默认 Ktor 客户端替换。当前仅完成代码审计，尚未创建 `core-net`。
- iOS 仍需提供 Unicode/Locale 回调及存储/服务装配。Windows 元数据与 JVM 测试不验证原生 Unicode 分类、
  Kotlin/Native 编译或链接；macOS 与真机门槛不变。本批未执行全量应用测试、设备事务/UI 流程或 R8/nightly。

## 19. P1 执行记录：Google Drive 同步 HTTP 协议进入 `core-net`

日期：2026-10-02。继续 §18 的网络审计。此批只迁移 Google Drive HTTP 协议，不改授权方式、同步文件格式、
文件合并/选取策略或数据库事务；WebDAV 与 tracker 客户端仍在应用。

### 实现与依赖边界

- 新增 `:core-net`，采用已有 Kotlin/AGP 工具链与 Android/JVM/iOS Arm64/模拟器 Arm64 目标。
  `GoogleDriveSyncApi`、原嵌套 `DriveFile` 与 `GoogleDriveSyncApiException` 保留包名与方法/字段进入 commonMain。
  API 通过构造参数接收 Ktor `HttpClient`；客户端生命周期、引擎和网络配置由平台管理，不依赖 Hilt、Room 或 parser。
- Android `GoogleDriveSyncNetworkModule` 用 Hilt 提供 singleton API，并装配 Ktor OkHttp 引擎。
  `GoogleDriveSyncAuth` 的 Google Identity/PendingIntent、账号设置与完整 Repository 仍在应用。
  原 Repository 注入的 API 类型不变；构造改由 provider 完成。
- 新增迁移必要的 `ktor-client-core`、Android `ktor-client-okhttp` 和仅测试使用的 `ktor-client-mock`，
  全部复用版本目录既有 Ktor **3.5.0**，没有升级 Kotlin、OkHttp、serialization 或 Ktor 版本。
  直接使用已有 serialization JSON 解码，不引入 ContentNegotiation、自动 HTTP 重试或新 DI 依赖。
  原 `app` 的 `ktor-http` 仍有其他消费者，继续保留。
- 共享模块未创建 Darwin 引擎或 iOS 授权实现；iOS 将注入自身管理的 `HttpClient`，并须验证超时、重定向、
  证书与网络异常语义。commonMain 元数据检查只证明共享源码可移植，不证明原生运行已完成。

### 保留的 HTTP 合约与取消修正

| 合约 | 实现 |
|---|---|
| 三代文件查询 | current content v3、work v2、legacy 文件名；`appDataFolder`、字段、创建时间排序及 pageSize 100 不变 |
| 新文件上传 | 先 POST 创建 appDataFolder 元数据，再 PATCH JSON；空 PATCH 响应回退目标 ID |
| 下载/删除 | `alt=media` 原始字节下载；DELETE 2xx 与 404 成功，其他 HTTP 状态仍返回应用异常 |
| JSON/错误 | 忽略未知字段、可空元数据、空响应处理、原 HTTP code/正文/原因短语与操作名上下文 |
| Android 超时 | OkHttp 连接 15 秒、读写 30 秒、整次调用 60 秒；连接恢复设置不变 |
| 重定向 | 由原 OkHttp 行为处理，Ktor 重定向插件关闭；保留 POST→GET 转换和跨 origin 移除 Authorization |
| 显式网络重试 | 最多 2 次，仅在进入响应处理前的 IOException；HTTP/JSON/响应体读取错误不重试 |

- 响应在 `HttpStatement.execute { ... }` 范围内处理，成功、解码/读取异常和取消均释放响应；API 不关闭注入客户端。
  每个请求显式 `expectSuccess=false`，即使注入客户端打开默认成功检查，仍保留应用的 HTTP 异常类型与 404 合约。
- 取消不进入重试或错误正文回退。Android Repository 的同步/旧版导入/远端删除入口重新抛出
  `CancellationException`；同步锁仍在 finally 释放，不将取消写为网络错误或清空同步状态。
  三处远端文件清理复用 best-effort helper，普通删除失败继续忽略，取消继续向上传递。
- 本批沿用原重试策略，包括创建元数据时的网络重试；未引入新的 HTTP 状态重试或幂等策略。
  文件列表分页、POST 幂等改进及其他协议行为调整不混入本次迁移。

设计上通过一个窄 HTTP 客户端构造边界分离协议与平台，复用错误/请求处理及清理逻辑，减少重复并保持现有行为。

### 验证记录

| 检查 | 结果 |
|---|---|
| `:core-net:compileCommonMainKotlinMetadata`、`compileKotlinJvm`、`compileAndroidMain` | 通过 |
| `:core-net:jvmTest` | 1 类，18/18 通过，0 跳过 |
| `:core-domain:jvmTest`、`:core-backup:jvmTest` | 既有规则/格式回归，116/116 与 13/13 通过 |
| `:app:compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin` | 通过，含 Hilt 装配 |
| `:app:checkDebugDuplicateClasses` | 通过，新增 Ktor 客户端依赖没有打包重复类 |
| `:app:testDebugUnitTest`（同步/备份范围） | 22 类，82/82 通过，0 跳过 |
| `git diff --check` | 通过 |

新增 18 个共享协议测试验证请求字段/授权、三代文件名、nullable/未知 JSON 字段、创建后 PATCH、字节上传/下载、
空响应、HTTP 错误和 404、两次网络失败、正文失败、解析失败、取消及响应生命周期。
应用新增 6 个真实引擎测试验证超时、二进制请求、跨 origin 授权移除、POST 重定向、响应体断流不重试、
等待响应时取消真实网络请求；另有 5 个 Repository 测试验证取消传递、锁释放和 best-effort 删除原行为。
全部网络测试使用 MockEngine 或本机 MockWebServer，没有调用生产 Google Drive API。

复验命令（沿用 §13 的 ignored 本地 init script 与独立 Android 输出目录）：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" `
    :core-net:compileCommonMainKotlinMetadata :core-net:jvmTest `
    :core-domain:jvmTest :core-backup:jvmTest `
    :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin `
    :app:checkDebugDuplicateClasses :app:testDebugUnitTest `
    --tests "org.skepsun.kototoro.sync.*" --tests "org.skepsun.kototoro.backups.*" --console=plain
```

### 后续与未验证范围

- 下一批按 §18 审计抽取 WebDAV 协议，保留代理配置、XML/日期解析、文件流和保留策略的现有行为。
- iOS Darwin 引擎、Google 授权、网络能力装配及原生测试仍待实现/验证；macOS 与真机门槛不变。
- 本批未执行真实账号同步、设备/UI 验收、全量应用测试、APK 体积测量或 R8/nightly；已知基线问题仍见 §12。

### 官方参考

实现核对使用项目固定的 3.5.0 源码，未跟随文档当前版本升级：

- [Ktor 引擎与 OkHttp 配置](https://ktor.io/docs/client-engines.html)
- [Ktor 3.5.0 HttpStatement 响应资源生命周期](https://github.com/ktorio/ktor/blob/3.5.0/ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpStatement.kt)
- [Ktor 3.5.0 OkHttp 引擎配置与执行](https://github.com/ktorio/ktor/blob/3.5.0/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpEngine.kt)
- [Ktor MockEngine 测试](https://ktor.io/docs/client-testing.html)


## 20. P1 执行记录：WebDAV 协议与备份目录规则进入 `core-net`

本批继续 §18–§19 的网络边界抽取。共享 HTTP 请求与远端目录规则，Android 保留设置、Basic 授权编码、
DOM/日期解析、文件 IO、日志和上传后的 best-effort 清理编排。不新增依赖，不改变备份格式或数据库 schema。

### 共享与平台边界

- `WebDavBackupClient` 负责 PROPFIND/PUT/GET/DELETE，接收平台管理的元数据与传输两个 Ktor 客户端。
  上传通过 `WriteChannelContent` 的 channel 回调，下载在响应作用域内将 `ByteReadChannel` 交给平台；
  ZIP 不整体读入内存。平台在每次上传尝试重新打开文件流，所有文件操作在 IO dispatcher 执行并关闭流。
- `WebDavBackupCatalog` 负责 ZIP 过滤、三代文件名/版本分类、时间降序、最新备份选择与保留删除清单；
  `WebDavResource`/`WebDavBackupFile` 使用毫秒时间戳。`RemoteNamespace` 保持原包名与 writer generation。
  应用的 `BackupFileInfo.lastModified: Date`、公开上传/下载/列表方法及调用者继续保留。
- `WebDavResponseParser` 留在应用，以窄回调向共享层投影资源；使用原 namespace-aware DOM 和
  `SimpleDateFormat`。新文件名仍使用平台本地时区生成时间戳。
- `WebDavNetworkModule` 以 Hilt singleton 管理两个客户端，保留元数据 30 秒和文件传输 120 秒整次调用超时。
  它从原 `@BaseHttpClient` 克隆，继承代理、Cookie、拦截器、连接/读写超时和连接恢复设置。
  Google Drive 与 WebDAV 复用 `createKtorOkHttpClient`，保持 OkHttp 重定向处理与跨 origin 授权移除。
  singleton 客户端随应用进程存活；共享协议不关闭注入客户端，iOS 应自行管理其引擎生命周期。

### 保留的行为与取消修正

| 合约 | 实现 |
|---|---|
| 目录与命名 | V1/V2/V3 仍共用一个目录，以原文件名前缀区分；保留远端路径首尾斜杠处理和 href 百分号编码 |
| 连接与列表 | PROPFIND Depth 0 测连接、Depth 1 查属性；所有 2xx（含 207）成功，列表 404 返回空列表 |
| 解析兼容 | 忽略目录/缺失属性结构；取首个 propstat，不新增 status 过滤；无效日期/大小回退零，保留 lenient 日期解析 |
| 分类与排序 | ZIP 后缀大小写不敏感、generation 前缀大小写敏感；V1 可读取旧文件名，严格版本溢出不再 fallback |
| 最新备份 | 无参入口只列表一次，优先 V3→V2→V1；带 namespace 入口取该代最新，时间相同保留响应顺序 |
| 上传 | PUT ZIP，最多三次；普通网络/HTTP 失败仍按 1 秒、2 秒重试；读取重试时的授权设置并重开文件流 |
| 错误正文 | 上传失败正文 trim 后最多展示 1024 字符及省略号；读正文失败回退原 HTTP 状态/原因短语 |
| 下载/删除 | 先验证下载 HTTP 状态再打开输出文件；下载不重试；DELETE 2xx/404 成功，其他状态抛原操作异常 |
| 保留策略 | maxCount≤0 不请求目录；只删除所选代超出数量的旧文件，普通删除失败继续后续清理 |

- `HttpStatement.execute { ... }` 释放成功、错误、解析失败、文件写入失败和取消时的响应。
  每次请求显式 `expectSuccess=false`，保留自定义 HTTP 错误与 404 分支。
- `CancellationException` 不进入上传重试、错误正文回退或 best-effort 清理。
  上传、文件流循环和重试检查 coroutine 状态，取消时不继续后续删除。
- 未改变 WebDAV 原有的三次上传重试策略；它与 Google Drive 的两次、仅响应前 IOException 重试策略保持独立。
  XML 解析库替换、Native 日期解析和下载部分文件回滚不混入本批。

设计通过注入传输、XML 投影和文件 channel 回调隔离平台依赖，目录规则只有一个实现；
应用适配器承担设置与文件编排，避免重复网络代码与新增解析依赖，符合 SRP、DIP、DRY、KISS 与 YAGNI。

### 验证记录

| 检查 | 结果 |
|---|---|
| `:core-net:compileCommonMainKotlinMetadata`、`compileKotlinJvm`、`compileAndroidMain` | 通过 |
| `:core-net:jvmTest` | 3 类，41/41 通过，0 跳过（新增 WebDAV 23 项，Google Drive 18 项回归） |
| `:core-domain:jvmTest`、`:core-backup:jvmTest` | 116/116 与 13/13 通过 |
| `:app:compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin` | 通过，含 Hilt 装配 |
| `:app:checkDebugDuplicateClasses` | 通过 |
| `:app:testDebugUnitTest`（同步/备份范围） | 25 类，99/99 通过，0 跳过（新增 WebDAV 17 项） |
| `git diff --check` | 通过 |

共享测试覆盖协议字段、授权、三代分类/排序/保留、两级重试间隔、HTTP/网络/解析失败、取消与未读响应释放。
Android 新增 5 项 XML、7 项真实 OkHttp 引擎和 5 项清理编排测试，验证代理配置继承、原 Basic 授权编码合约、
200KB 文件上传重试的完整字节、下载流、HTTP 失败保留目标文件、单次 XML 列表映射、跨 origin 授权移除、
响应断流不重试、真实网络取消，以及清理期间普通错误/取消的不同处理。
网络测试只使用 MockEngine 和本机 MockWebServer，没有调用真实 WebDAV 服务。

复验命令（沿用 §13 的 ignored 本地 init script 与独立 Android 输出目录）：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" `
    :core-net:compileCommonMainKotlinMetadata :core-net:jvmTest `
    :core-domain:jvmTest :core-backup:jvmTest `
    :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin `
    :app:checkDebugDuplicateClasses :app:testDebugUnitTest `
    --tests "org.skepsun.kototoro.sync.*" --tests "org.skepsun.kototoro.backups.*" --console=plain
```

### 后续与未验证范围

- 下一批审计 tracker/scrobbler 的 HTTP、认证、响应解码与平台模型边界，再抽取可共享协议。
- iOS 需实现 XML/日期解析回调、文件流与 Darwin 客户端装配；Google 授权和网络能力仍待实现。
  Windows 元数据/JVM/Android 验证不能替代 macOS 的 Native 编译、链接和设备运行。
- 本批未执行真实 WebDAV 服务器验收、设备/UI 验收、全量应用测试、APK 体积或 R8/nightly。
  S0/S3、D6/D7 和 §12 的既有基线限制不变。

### 官方参考

- [Ktor 3.5.0 流式请求内容接口](https://github.com/ktorio/ktor/blob/3.5.0/ktor-http/common/src/io/ktor/http/content/OutgoingContent.kt)
- [Ktor 3.5.0 响应作用域与清理](https://github.com/ktorio/ktor/blob/3.5.0/ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpStatement.kt)
- [Ktor 3.5.0 MockEngine 请求体测试辅助](https://github.com/ktorio/ktor/blob/3.5.0/ktor-client/ktor-client-mock/common/src/io/ktor/client/engine/mock/MockUtils.kt)

## 21. P1 执行记录：MALSync 跨站映射协议进入 `core-net`

本批开始处理 tracking/scrobbler 的网络边界，先抽取无账号授权的 MALSync 映射。
Google Drive 与 WebDAV 的既有共享协议继续回归；各 scrobbler 的账号、列表写入与数据库编排仍由应用负责。

### 网络装配审计与本批选择

| 客户端 | 当前平台边界 |
|---|---|
| MAL / AniList / Shikimori | 从 BaseHttpClient 克隆；各自 Authenticator、token 拦截器与 ScrobblerStorage，MAL 还依赖资源 client ID |
| Kitsu / Bangumi | 独立 OkHttp Builder 与 Authenticator/拦截器，不能直接假设它们继承 BaseHttpClient 的代理/Cookie 配置 |
| MangaUpdates | BaseHttpClient、MutableCookieJar、token/cookie 双路径授权、Room 与 Jsoup 网页解析 |
| Simkl | BaseHttpClient、平台 client ID/app version/token 拦截器、Context 资源、Room 与时间格式化 |
| MangaBaka | 远端 HEAD/下载与本地 SQLite、tar/gzip 安装、索引和文件元数据耦合 |
| MALSync | 无账号授权；原 BaseHttpClient GET、JSONObject 投影、服务分类/ID 回退/去重、LRU 与同键 Mutex |

本批选择 MALSync，将协议与映射规则上提，不在迁移中重构上述有授权客户端的装配或账号存储。
审计表只记录已确认的边界，不表示其他服务已完成协议抽取或原生适配。

### 共享与 Android 适配

- `MALSyncMappingApi` 使用注入的 Ktor HttpClient 请求原公开端点。
  保留四个可查询服务路径 mal/anilist/kitsu/shikimori 与 manga/anime 两种媒体路径；
  Bangumi/MangaUpdates 只能作为返回的映射站点，SIMKL 仍不支持。没有新授权头或协议级重试。
- `MALSyncMappingRules` 负责大小写不敏感的六站点识别、排除来源站、identifier 优先与 entry key 回退、
  空元数据归一及按 service/remoteId 去重。零、负数、Long 边界和未 trim 的非空文本保持旧行为。
  重复映射保留平台投影顺序中的首项。
- `MALSyncEntry` 是窄平台投影，`MALSyncMapping`/`MALSyncService`/`MALSyncKind` 不依赖 Android。
  原 `ScrobblerService` 的资源 id 继续留在应用；Repository 显式转换服务与媒体枚举，
  保留其公开 `Kind`、`Mapping` 和 `resolve(...)` 合约及 UI 调用。
- `parseMALSyncEntries` 保留平台 JSONObject 的 optJSONObject/optString 与键迭代行为。
  Android 与 JVM 测试使用的 org.json 在 null/对象/数字转字符串和迭代细节上可能不同；
  不用共享 JSON 解码器静默替换这些行为。iOS 后续实现同样的窄投影回调。
- `MALSyncMappingNetworkModule` 用 Hilt singleton 管理协议客户端，复用 `createKtorOkHttpClient`。
  原 BaseHttpClient 的代理、Cookie、拦截器、超时、连接恢复和 OkHttp 重定向设置继续继承；
  不新增网络依赖或独立超时策略。

### 缓存、错误与取消合约

| 行为 | 本批结果 |
|---|---|
| HTTP 结果 | 所有 2xx 仍解析；非 2xx 返回空结果且不解析正文，每个请求关闭默认 expectSuccess 检查 |
| 响应生命周期 | 在 HttpStatement.execute 作用域内读取/投影，HTTP 错误的未读正文也释放 |
| 普通错误 | 网络与 JSON 解析异常交给 Repository，继续回退并缓存空结果，不新增重试 |
| 缓存键 | service path / media kind / remote ID，64 项 LRU 容量及命中更新最近使用顺序不变 |
| 并发 | 同键调用共用一次取数，不同键仍可并行；既有 per-key Mutex 表未做额外生命周期重构 |
| 取消 | CancellationException 向上传递，不写空缓存；锁自动释放，后续同键调用可重新请求 |

Repository 在入口、普通异常回退和写缓存前检查 coroutine 状态，
避免 socket 在已取消的协程中抛普通 IOException，或在取消后返回结果时污染缓存。
这一取消修正覆盖真实网络取消、等待同键锁、普通错误以及成功结果写入前的路径。

协议、映射规则、平台解析与缓存各自承担单一职责；窄回调隔离平台行为，
共享规则只有一份实现，网络装配复用现有工厂，符合 SRP/DIP、DRY、KISS 与 YAGNI。
没有改动账号数据、数据库 schema、资源枚举或已有外部解析器 JVM ABI。

### 验证记录

| 检查 | 结果 |
|---|---|
| `:core-net:compileCommonMainKotlinMetadata`、`compileKotlinJvm`、`compileAndroidMain` | 通过 |
| `:core-net:jvmTest` | 5 类，58/58 通过，0 跳过；新增 MALSync 17 项，既有网络 41 项回归 |
| `:core-domain:jvmTest`、`:core-backup:jvmTest` | 116/116 与 13/13 通过 |
| `:app:compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin` | 通过，含 Hilt 装配 |
| `:app:checkDebugDuplicateClasses` | 通过 |
| `:app:testDebugUnitTest`（tracking/scrobbling/tracker/sync/backups 范围） | 41 类，207/207 通过，0 跳过 |
| `git diff --check` | 通过 |

新增共享 8 项映射规则与 9 项 HTTP 协议测试；Android 新增 12 项 Repository 与 7 项投影/真实引擎测试。
覆盖查询路径、六站点映射、ID 溢出/回退、去重、HTTP/网络/正文/解析错误、响应清理、模型适配、
空结果回退、64 项 LRU、同键去重、不同键并发、取消不缓存、锁释放、真实 GET/UTF-8 JSON 和真实请求取消。
网络测试仅使用 MockEngine 与本机 MockWebServer，不访问生产映射 API 或用户账号。

复验命令（沿用 §13 的 ignored 本地 init script 与独立 Android 输出目录）：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" `
    :core-net:compileCommonMainKotlinMetadata :core-net:jvmTest `
    :core-domain:jvmTest :core-backup:jvmTest `
    :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin `
    :app:checkDebugDuplicateClasses :app:testDebugUnitTest `
    --tests "org.skepsun.kototoro.tracking.*" --tests "org.skepsun.kototoro.scrobbling.*" `
    --tests "org.skepsun.kototoro.tracker.*" --tests "org.skepsun.kototoro.sync.*" `
    --tests "org.skepsun.kototoro.backups.*" --console=plain
```

### 后续与未验证范围

- 后续抽取有授权服务的公共搜索/榜单等只读协议，再处理账号刷新、列表写入和 Room 编排边界；
  不能将 Kitsu/Bangumi 的独立客户端无条件换成 BaseHttpClient。
- MALSync 的 iOS JSON 投影、其他平台网络装配和 Native 测试仍待实现。
  Windows 元数据/JVM/Android 检查不替代 macOS 原生编译、链接或设备运行。
- 本批未做真实站点验收、账号操作、设备/UI 验收、全量应用测试或 R8/nightly；
  S0/S3、D6/D7 和 §12 已知基线限制不变。

### 官方参考

- [Ktor 3.5.0 HttpStatement 请求执行与响应清理](https://github.com/ktorio/ktor/blob/3.5.0/ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpStatement.kt)
- [Ktor MockEngine 测试](https://ktor.io/docs/client-testing.html)

## 22. P1 执行记录：MAL 搜索、榜单和季度查询进入 `core-net`

本批从 §21 的有授权服务审计继续，抽取 MAL 的只读公共查询。
应用的账号刷新、用户资料、详情/网页解析、列表同步/写入、媒体提示缓存和 Room 编排继续保持平台实现。

### 共享与平台边界

- `MALDiscoveryApi` 共享三类 GET 请求：动漫/漫画搜索、动漫/漫画榜单与动漫季度查询；
  `MALMediaType` 集中管理 endpoint 和不同媒体的 discovery fields，消除五个应用入口中的重复请求构造。
- `MALDiscoveryNetworkModule` 注入原 `@ScrobblerType(MAL) OkHttpClient` 与资源 client ID，
  复用 `createKtorOkHttpClient`。原 MALInterceptor、MALAuthenticator、Storage 和 Provider 依赖链保留，
  不替换成无鉴权的 BaseHttpClient。Hilt 主应用和 Android 测试源码装配均通过编译。
- `MALRepository` 的 findContent/searchAnime/getAnimeRanking/getMangaRanking/getSeasonalAnime 保留公开签名、
  默认参数、JSONObject 解析和原 ScrobblerContent 映射；返回内容仍记录原媒体提示。
  原始 JSON 文本是窄平台边界，iOS 需实现相应解析/UI 映射和认证装配。
- 没有新增依赖或更新版本，没有改动 OAuth 请求、PKCE、token 存储、列表写入、数据库 schema 或外部 parser ABI。

### 保留的协议与错误行为

| 合约 | 本批结果 |
|---|---|
| 搜索 | 原 anime/manga 路径，offset、nsfw=true、媒体 fields 和 q；不增加 limit，不 trim 查询 |
| 查询截断 | 继续 query.take(64) 的 UTF-16 单元限制；补充平面字符和截断代理对的解码值与原 OkHttp 对比通过 |
| 榜单 | ranking_type 默认 all、limit 默认 20、offset 默认 0；自定义值不新增校验或 clamp |
| 季度查询 | anime/season/year/season，season 作为一个编码路径段；原 sort/default/paging/fields 保留 |
| 授权头 | 公共 client ID 保留；无 token 仍可请求，有 token 继续由原拦截器加 bearer |
| token 刷新 | 401 继续由原 Authenticator 调用 authorize(null)，成功后用新 token 重发 |
| 剩余错误 | 拦截器留下的非 2xx 正文仍交给原 JSON 解析，不新增全局 HTTP 成功检查；HTML 继续转标题 IOException |
| JSON/UI | 搜索缺 data 仍报 Invalid response，榜单缺 data 仍为空；标题、别名、最佳匹配、评分、进度与日期格式保留 |
| 重定向/取消 | 原 OkHttp 跨 origin 移除 bearer；查询不增加协议级重试，取消继续传递 |

`HttpStatement.execute` 在响应作用域内读取正文并释放响应。
MALInterceptor 在未解决的 401/403 分支原先直接抛认证异常，未关闭已收到的响应；
本批补上 response.close()，保留原 ScrobblerAuthRequiredException 类型和服务值。
真实引擎测试抓取响应体，验证异常抛出后底层 source 已关闭。

设计集中请求/字段规则、复用网络工厂，并以文本边界隔离平台 JSON 和 UI，
保持 SRP/DIP、DRY、KISS 与 YAGNI；不提前构造新的跨平台账号或全量追踪模型。

### 验证记录

| 检查 | 结果 |
|---|---|
| `:core-net:compileCommonMainKotlinMetadata`、`compileKotlinJvm`、`compileAndroidMain` | 通过 |
| `:core-net:jvmTest` | 6 类，70/70 通过，0 跳过；新增 MAL 12 项，既有网络 58 项回归 |
| `:core-domain:jvmTest`、`:core-backup:jvmTest` | 116/116 与 13/13 通过 |
| `:app:compileDebugKotlin`、`compileDebugUnitTestKotlin`、`compileDebugAndroidTestKotlin` | 通过，含 Hilt 装配 |
| `:app:checkDebugDuplicateClasses` | 通过 |
| `:app:testDebugUnitTest`（tracking/scrobbling/tracker/sync/backups） | 43 类，222/222 通过，0 跳过 |
| `git diff --check` | 通过 |

新增共享 12 项请求/错误/取消测试，应用 7 项 Repository 和 8 项真实引擎测试。
真实引擎验证 client ID/JSON 头、旧 bearer、原 Authenticator 刷新、认证异常与响应关闭、
HTML 标题错误、保留字符与截断代理对的查询编码、跨 origin 重定向和真实请求取消。
Repository 验证五个入口、默认/自定义参数、动漫/漫画 UI 映射、两种缺 data 语义、JSON 失败与取消。
全部网络测试使用 MockEngine 或本机 MockWebServer，token/账号依赖为测试替身，没有访问生产服务或真实账号。

复验命令（沿用 §13 的 ignored 本地 init script 与独立 Android 输出目录）：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" `
    :core-net:compileCommonMainKotlinMetadata :core-net:jvmTest `
    :core-domain:jvmTest :core-backup:jvmTest `
    :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin `
    :app:checkDebugDuplicateClasses :app:testDebugUnitTest `
    --tests "org.skepsun.kototoro.scrobbling.*" --tests "org.skepsun.kototoro.tracking.*" `
    --tests "org.skepsun.kototoro.tracker.*" --tests "org.skepsun.kototoro.sync.*" `
    --tests "org.skepsun.kototoro.backups.*" --console=plain
```

### 后续与未验证范围

- 下一批继续其他 scrobbler 的只读协议或 MAL 详情请求，保留其认证/解析差异；
  账号刷新、列表写入和 Room 编排的共享仍需独立抽取。
- iOS 认证、MAL JSON/UI 映射、Darwin 装配与 Native 验证未完成。
  Windows 的元数据/JVM/Android 结果不替代 macOS 原生编译、链接和设备运行。
- 本批未执行真实账号/站点验收、设备/UI 测试、全量应用测试或 R8/nightly；
  S0/S3、D6/D7 与 §12 的基线限制不变。

### 官方参考

- [Ktor 3.5.0 路径段与 URL 参数构造](https://github.com/ktorio/ktor/blob/3.5.0/ktor-http/common/src/io/ktor/http/URLBuilder.kt)
- [Ktor 3.5.0 OkHttp 引擎及 preconfigured 客户端](https://github.com/ktorio/ktor/blob/3.5.0/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpEngine.kt)

## 23. Claude 方案交接核对：继承 JAR + 嵌入式 JVM 路线

日期：2026-10-02。按用户要求读取 Claude 最近的原始对话，而非只依赖摘要。
对应会话为 `f4687870-7e3a-44aa-b5d5-e65749db5353`，文件位于
`C:/Users/chuxi/.claude/projects/E--kototoro-demo-Kototoro/`。
研究从源码跨编译方案的评估，转向上游同名 JAR + 真 JDK 的方案；用户随后明确要求
“先假定他的这套可行，规划我们的 kmp 方案”。这与 §1–§5 的目标一致。

### 已复核的本机证据

Claude 的原始存档目录：
`C:/Users/chuxi/AppData/Local/Temp/claude/E--kototoro-demo-Kototoro/f4687870-7e3a-44aa-b5d5-e65749db5353/scratchpad/az/`。
本次直接逐行统计 TSV；错误文本含引号，应按制表符分列，不用 CSV 引号规则解析。

| 产物 | 本次复核 |
|---|---|
| `harness_fulljdk.tsv` | 1395 行：1389 OK、6 FAIL |
| `harness_jre4.tsv` | 1395 行：1389 OK、6 FAIL，与完整 JDK 一致 |
| `harness_network.tsv` | 1395 行：750 OK、33 EMPTY、612 FAIL；没有重发联网请求 |
| `Harness.java` | inspect、load、listSources、getSearchFilters、getSettings，各步骤限时；不等于完整阅读流程 |
| `D:/tmp/kt-harness` | 驱动源码与编译产物仍在，可作为独立实验入口 |
| `D:/tmp/kt-compat36`、`kt-shims`、`kt-jre4`、`kt-extra` | 原兼容层、shim 与裁剪镜像仍在；没有复制进项目或重新下载 |

TSV 核实的是当时的结果，未重新执行所有 JAR；§2.2 的细分失败归因沿用 Claude 的研究记录。
99.6% 表示桌面加载/构造/筛选/设置调用通过，不表示 iOS 真机兼容率。
94.6% 的分母是当时有响应且能判定的 793 个源，不是全部 1395 个 JAR。
桌面 HotSpot/JIT 与四模块镜像均不能替代 iOS Zero 的性能和语义验证。

### 后续实现必须继承的边界

1. **直接加载仓库提供的 JAR。** Android 继续安装 APK；iOS 不执行 DEX，也不做 dex2jar 或逐源
   Kotlin/JS 改写。JVM host 承载原 extension-lib 1.4–1.6，旧 Rx 由兼容 API 的 suspend 默认实现回落。
2. **JNI 只传协议数据。** iOS 的 SwiftUI/KMP 不持有 Mihon、OkHttp 或 Jsoup 对象。
   每个扩展的 `URLClassLoader` 负责命名空间隔离；一个进程内 VM，不提供安全隔离或原地重建 VM。
3. **源身份不能改名。** 上游 `SourceFactory` 展开后的 sourceId 必须独立寻址；Kototoro 继续使用
   `MIHON_{id}` 的稳定键，不能照搬参考实现的 `mihon.<id>`，否则 Android 备份/收藏/历史无法对应。
4. **保留不透明元数据与原页面上下文。** `memo`/`sourceData` 不重新解释；章节原始 page URL、
   图片 fragment、headers 都可能参与解扰。封面请求与章节 `HttpSource.imageRequest(Page)` 语义不同。
5. **图片仍经过扩展链。** 实际保留的 `ExtensionHost.java` 包含 `materializeImage`，通过扩展自己的
   OkHttp client 执行请求及拦截器。只导出图片 URL/headers 再交 Darwin 下载，无法覆盖图片解扰类源。
   当前 `core-source.getPageUrl` 只解析地址，没有完成图片执行/物化能力。
6. **浏览器和偏好是必要能力。** WKWebView、主 Looper、JavaScript 回调、CookieManager/OkHttp cookie jar
   和同一 user agent 必须相互衔接；偏好设置不是可忽略的 UI stub，`getSettings` 实际执行设置构建。
7. **v1 漫画侧载范围不扩张。** 继续 SwiftUI 外壳与嵌入式 JVM；P5 的小说/视频/翻译后置。
   读取参考源码用于理解，不把 GPL 实现直接搬进现有共享模块；D3/S3 的许可决策仍独立待办。

后续应在 `core-source` 上接实际 JVM host，并复用桌面驱动做本地契约验证。
扩展安装/校验、完整 Mihon FilterList/偏好投影、图片物化、WKWebView/Cookie/JS 原生桥和
OpenJDK 镜像构建均仍需实现；不能把协议与 Android 编译通过写成 P2/G2 完成。

## 24. P2 前置实现：共享源协议与 Android 适配器

本批建立 `:core-source`，目标是让源调用能穿过 JSON 边界，并验证与现有 Android 模型的映射。
它独立于 `parser-api` 的 JVM 类型与二进制垫片，不修改 D6/D7；模块使用既有版本的
kotlinx.serialization 与 coroutines，不引入新的第三方运行时或版本更新。

### 可执行入口与协议

- `SourceRuntime` 覆盖启用源目录、能力描述、筛选选项、列表/搜索、详情、章节页面、页面地址与相关推荐。
- `SourceEndpoint.exchange` 解码版本化请求并执行 runtime；`SourceProtocolClient` 实现同一接口，
  用注入的 transport 发 JSON，检查版本、请求 ID 与结果类型。请求 ID 生成及 transport 生命周期由平台管理。
- v1 JSON 包含 `version`、`requestId`、`call.operation`；响应只有 result/error 中的一项。
  未知版本、坏请求、源不可用、无效参数与普通运行时失败分别返回明确代码。
  错误正文不直接携带 parser 堆栈、token 或私有请求 URL。
- 64 位 ID 与上传时间戳以十进制字符串编码，避免 JSON 中间消费者丢精度；
  不透明 `sourceData`、null/已加载空章节、null/空 headers、章节分支、电子书格式及附加页面字段均保留。
- 枚举使用名称、Locale 使用 BCP 47；分页明确 OFFSET/PAGE_INDEX，详情保留 ALLOW_CACHE/FORCE_REFRESH。
  保留 query、author、排除标签、年份范围、原始语言、分组顺序/互斥属性及显式 effectiveTagGroups。
- 不增加 offset clamp、排序默认值、查询 trim 或协议重试。取消直接传递；
  runtime 即使吞掉取消并返回数据，入口也检查 coroutine 状态，不把它转成成功。

`AndroidSourceRuntime` 使用原 `ContentSourcesRepository.getEnabledSources()` 的用户启用/可见策略，
其余调用复用 `ContentRepository.Factory.createWithDiagnostics` 的解析、选择与缓存。
DTO 里的源元数据不能替代实际源对象，按稳定名称解析回现有 repository 的 canonical source。
无法选中 provider/返回 EmptyContentRepository 时报告不可用，不把它编码成成功空列表。
Hilt 仅在 `app` 装配 SourceRuntime 和 JSON 入口；共享模块没有 DI 依赖，Android UI 原调用链不变。

设计按 SRP 分离 DTO、协议、transport 和平台适配；DIP 通过 SourceRuntime/SourceTransport 接缝，
DRY 复用现有源工厂与字段映射，KISS/YAGNI 保持单次请求协议，不预建另一套源加载器或 UI。

### 验证与剩余能力

| 检查 | 结果 |
|---|---|
| `:core-source:compileCommonMainKotlinMetadata`、`compileKotlinJvm`、`compileAndroidMain` | 通过 |
| `:core-source:jvmTest` | 18/18 通过，0 跳过 |
| 应用源协议/映射测试 | 2 类，16/16 通过，0 跳过 |
| 既有 `ContentRepositoryFactoryTest` | 18/18 回归通过 |
| 应用 main/unit/androidTest Kotlin 编译与 Hilt 装配 | 通过 |
| `:app:checkDebugDuplicateClasses` | 通过 |
| `git diff --check` | 通过 |

共享测试验证八类调用端到端往返、64 位极值/JSON 编码、null 与空集合、增量字段兼容、
版本/请求 ID/结果类型校验、坏请求/坏枚举/ID 溢出、错误分类与正文脱敏、取消和并发逆序完成。
Android 测试通过真实适配器/客户端/入口验证完整字段往返、显式 effectiveTagGroups、
BCP 47、canonical source、启用目录策略、factory 不可用回退、详情刷新模式、原页面上下文与取消。
源目录、factory 和底层 repository 使用替身；未加载真实 Mihon JAR，未验证 JNI。

复验命令（沿用 §13 的 ignored 本地 init script）：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" `
    :core-source:compileCommonMainKotlinMetadata :core-source:jvmTest `
    :app:compileDebugAndroidTestKotlin :app:checkDebugDuplicateClasses `
    :app:testDebugUnitTest --tests "org.skepsun.kototoro.core.source.*" `
    --tests "org.skepsun.kototoro.core.parser.ContentRepositoryFactoryTest" --console=plain
```

本批没有实现 JAR 安装/签名或哈希校验、Mihon host、动态 FilterList/偏好设置、图片请求执行、
渐进页面 Flow、Cloudflare/JS 原生桥或 iOS 应用。过滤 DTO 是现有 Android ContentListFilter 的投影，
不能称为完整 Mihon FilterList 协议。Coroutine 测试也不能证明未来 JNI 阻塞调用可以被中断。
所有契约测试使用本地 runtime/repository 替身，没有访问站点；本次未重跑 Claude 的联网样本。
Windows 元数据/JVM/Android 编译结果不替代 macOS 原生编译、链接与真机 G0/G2。

## 25. P2 执行记录：JVM JAR 检查器与真实扩展注册

日期：2026-10-02。承接 §23 的 Claude 研究与 §24 的共享协议，建立 `:source-host` JVM 模块，
不修改 Android APK 加载器，不复制参考项目的 GPL Java/Swift/JNI 实现。

### 代码与边界

- `core-source/MihonExtensionModels` 定义检查/加载结果 DTO，源 ID 使用十进制字符串；
  保留包名、版本、extension-lib、entry classes、NSFW、hash、字节码版本和每个展开源的元数据。
- `MihonJarInspector` 读取带 Android XML namespace 的文本 manifest，接受 1.4–1.6；
  规范化相对/简单/完整、多 entry class；检查重复字段、manifest 大小、XML 外部实体与根 class header。
  检查 VM class 版本上限、拒绝 preview 字节码，不把未选中的 multi-release 版本误计入上限。
  inspection 阶段只读取数据，不加载或执行扩展。
- registry 必须先校验调用方的 package/version/SHA-256，再构造 entry。
  parent 是嵌入平台已经初始化的兼容 ClassLoader，每个扩展使用独立 URLClassLoader。
  接口来自 parent 的真实 CatalogueSource/SourceFactory，不创建另一份生产 Mihon ABI。
- SourceFactory 的所有语言源独立注册；稳定键继续 `MIHON_{id}`，显示名与语言仅是元数据。
  同包重复加载、同包/跨包 sourceId 冲突、空/失败 factory 拒绝发布部分注册结果。
  构造/访问 getter 时切换 context ClassLoader，退出时恢复原值。
- `unload`/`close` 只移除内存注册并关闭 JAR 句柄，不删除原文件或尝试重建 VM。
  失败构造关闭新 loader，关闭异常不覆盖原异常；VM 级 fatal error 不伪装成普通成功结果。
- CLI 的 `inspect` 直接输出共享 JSON；默认 `installDist` 仅包含自有 host、core-source 与既有
  Kotlin/serialization/coroutines 依赖，不打包外部兼容层、GPL bootstrap 或 Keiyoushi JAR。

SRP 分离数据检查与执行/注册，DIP 由平台注入兼容 ClassLoader，DRY 复用共享 DTO；
保持 KISS/YAGNI，不新造 Mihon ABI、另一套 Android loader 或完整虚拟机生命周期框架。
仓库信任、下载/安装记录/更新和产物不可变性仍由安装层负责。
hash 校验不等于签名验证，也不能把 URLClassLoader 称为安全沙箱。

### 真实语料检查与加载

本次重新用自有检查器读取了 Claude 留下的全部 1395 个 JAR，而非只转述旧报告。
期望身份取自其存档 `index.json`（包/版本/源 ID）与 `ra.json`（产物 SHA-256）。
逐个调用 inspect/verify，并另以本机 Get-FileHash 复核全部产物；没有下载或请求站点。

| 检查 | 结果 |
|---|---|
| 文本 manifest、支持版本、class header/ceiling | 1395/1395 通过 |
| package/version/hash 与存档目录比对 | 1395/1395 通过，0 mismatch |
| 本机语料分布 | 1.4：73 个；1.6：1322 个；最高 class major 为 55（Java 11） |

原目录共有 1396 个条目；缺失的一个不纳入本次 1395 个检查分母。
不把“manifest/class header 可读”写成整个字节码或运行行为通过。

接着仅在本机研究进程中使用已有 Suwayomi 兼容层、shim 与 Claude 的已编译 bootstrap 做初始化，
实际加载由本批独立编写的 registry 完成。运行 JDK 25，home/tmp 指向 ignored build 下的隔离目录。
外部 bootstrap 和兼容 JAR 没有复制到源码或发行包；没有执行 popular/search/details/image 网络调用。

| 真实扩展 | extension-lib | 本次展开源数 |
|---|---|---|
| `all.comicfury` | 1.6 | 14 |
| `all.mangadex` | 1.6 | 61 |
| `all.buondua` | 1.6 | 1 |
| `en.akaicomic` | 1.4 | 1 |

四个 JAR 全部加载，总计 77 个源；每个展开 sourceId 都与存档目录的完整集合比对一致，
所有 Kototoro 键均为 `MIHON_{id}`。这里只证明这些源的加载/展开/元数据读取，
不证明旧版 Rx 请求、联网解析、图片解扰、偏好持久化或 iOS 上的运行行为。
local smoke 驱动/目录 pins/日志在 ignored `build` 内，复验依赖 §23 的本机研究产物。

### 编译与单元测试

| 检查 | 结果 |
|---|---|
| `:source-host:compileKotlin`、`:source-host:installDist` | 通过；Java/Kotlin target 均为 11 |
| `:source-host:test` | 2 类，18/18 通过，0 跳过 |
| `:core-source:compileCommonMainKotlinMetadata`、JVM/Android 编译 | 通过 |
| `:core-source:jvmTest` | 18/18 回归通过 |
| `:app:compileDebugKotlin` 与 unitTest Kotlin 编译 | 通过 |
| 应用 source 适配/映射与既有工厂测试 | 3 类，34/34 回归通过 |
| `git diff --check` | 通过 |

host 的测试 JAR 使用 JavaCompiler 以 --release 11 实际编译，并由独立 ClassLoader 加载。
覆盖元数据、XML/大小/重复 entry、hash/package/version、字节码上限、multi-release、构造前校验、
单源/factory 全展开、Long 极值、namespace/class ownership、冲突、失败注册原子性、thread loader
恢复、关闭与重新加载。fixture API 是独立编写的最小测试接口，不在生产发行包内。

复验命令：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" `
    :source-host:test :source-host:installDist `
    :core-source:compileCommonMainKotlinMetadata :core-source:jvmTest `
    :app:compileDebugKotlin :app:testDebugUnitTest `
    --tests "org.skepsun.kototoro.core.source.*" `
    --tests "org.skepsun.kototoro.core.parser.ContentRepositoryFactoryTest" --console=plain
```

本批没有运行全量 app 测试、设备测试或 R8/nightly；此前基线限制保持不变。

### 后续与未完成范围

下一步把 Mihon 源对象接到 §24 的 SourceRuntime/SourceEndpoint：抽取共用 ID/URL/内容映射，
提供真实 suspend/Rx 调用、分页和状态隔离，再补 FilterList/偏好、图片请求与物化。
本批 registry 不以空列表或虚假成功替代这些执行能力；它也不提供完整 host RPC、JNI 或兼容层 bootstrap。
`sources` CLI 需要调用进程已准备的兼容运行环境；默认发行目录中的 `inspect` 独立可用。

iOS 原生桥、OpenJDK Mobile 镜像、WKWebView/Cookie/JS、SwiftUI 应用和 G0/G2 真机证据仍未完成。
D3/S3、D6/D7 及 P1 其余业务服务仍待；本批不宣称 P2 或 KMP 总目标完成。

## 26. P2 执行记录：Mihon 内容执行器与实际 Rx ABI 闭环

日期：2026-10-02。承接 §25 的源注册器，新增自有 `MihonSourceRuntime`，通过 §24 的
`SourceEndpoint` / `SourceProtocolClient` 调用已初始化兼容层中的源。Android 的原调用链保留。

### 实现与兼容性

- `core-source/MihonModelRules` 抽取现有 Android 的 manga/chapter/page ID、相对 URL、重复协议
  清理、genre 清洗和内容分级规则；Android 的 MihonDataConverters 改为复用同一实现。
  manga ID 继续使用既有 64 位 seed/31 哈希，chapter/page 继续使用原 String hash；没有改变备份键。
- host 反射真实 API 的 getPopularManga/getLatestUpdates/getSearchManga/getMangaUpdate/getPageList/
  getImageUrl；Continuation 与 COROUTINE_SUSPENDED 正常处理，异步回调支持取消。
  不另行实现 RxJava 或替换 Mihon API，旧 Rx 回落交给平台兼容 API 的默认方法。
- 实际 ABI 验证发现 Suwayomi HttpSource 的若干挂起转发方法只有 ACC_BRIDGE；选择器现在接受
  这些方法，并优先普通 override。测试 fixture 模拟同样的字节码 flag，防止再次跳过 bridge。
- 页索引无调用顺序状态：协议 PAGE_INDEX offset 0 对应 Mihon page 1，offset 1 对应 page 2。
  负值/溢出/不支持的 order 明确拒绝；不同搜索不会污染下一页。Android 原 OFFSET 行为不改。
- 详情始终请求 details+chapters。保留原 ID/URL，补全 partial SManga 的 title/thumbnail/memo。
  章节按 Android 规则反转、对未提供编号的章节按位置编号再升序；保留 parent-scoped ID、
  毫秒时间戳、scanlator/branch 与 memo。两个 fetchMode 都刷新，不把 snapshot 当作详情响应缓存。
- 每个源/运行时保存有界 manga/chapter LRU（100/500），由 registry 的扩展 generation 持有；
  卸载后旧状态不进入重载实例。不能仅靠弱 source key 加强 value，因为 native snapshot 可能引用其源。
  实际 Suwayomi 的默认 copy()/copyFrom() 均遗漏 memo；host 显式补复制及其余可用的扩展字段。
- 页面保留真实 index、origin URL、image URL fragment 与 protected imageRequest 的 headers。
  延迟图片地址编码到 mihon://resolve；有独立 origin 的直接图片编码到 mihon://image；解析时保留
  index/UTF-8/加号上下文。可选 public URL/header 投影失败按 Android 规则回退，不丢弃整个列表。
- 同一源 mutex 串行，扩展间可并行；挂起期间不持有 JVM synchronized monitor。
  ThreadContextElement 在 coroutine 调度/恢复时切换并还原 extension ClassLoader。
  unload/close 立即注销、拒绝新调用，但 active lease 保持旧 loader，直到已有调用/回调完成才关闭。
  取消不返回成功；若扩展忽略取消，回调 lease 保留至其完成，不强行关闭仍使用的 JAR。
- 尚未投影动态 FilterList，因此能力只声明文本搜索。非文本过滤、filters 与相关推荐返回新增
  `UNSUPPORTED_OPERATION` 结构化错误；没有以忽略条件/空列表伪装完成。普通错误仍经 endpoint 脱敏。

SRP 分开反射 ABI、模型投影与运行调度；DRY 复用 common 的身份/URL/分级规则和已有协议；
KISS 保留平台现成的 suspend/Rx 默认实现，不增加依赖版本、另一份生产 API 或独立虚拟机框架。

### 验证

| 检查 | 结果 |
|---|---|
| source-host 编译/发行目录 | 通过；仍为 Java 11 target，不打包兼容层/外部扩展 |
| source-host 单元测试 | 3 类，26/26（原 18 + 执行器 8），0 跳过 |
| core-source common metadata / JVM 测试 | 通过；2 类，24/24（原 18 + 规则 6） |
| app Kotlin / Hilt / unitTest 编译 | 通过 |
| app source 适配/映射、原工厂与 Mihon 差分测试 | 4 类，37/37（原 34 + 差分 3） |
| 本批合计 | 87/87；未运行全量应用/设备测试 |

执行器测试通过真正编译并装入 child URLClassLoader 的独立 Java fixture：JSON 请求/响应、
源 canonical 元数据、页索引/搜索、native memo 拷贝/DTO 恢复、partial details、章节编号、
非连续页面 index、Unicode/加号/fragment、protected headers、异步 resume 与错误脱敏。
取消/卸载测试令扩展完成回调前从旧 JAR 延迟加载 class，完成后确认 loader 不再加载新 class；
另测 registry close 后已有异步调用仍完成、新调用被拒绝。测试代码/最小 ABI 不进入发行目录。

随后用 §23 的本机真实 Suwayomi server-1.0.jar/依赖/shim 和已有外部 bootstrap，仅在研究进程
初始化兼容环境。独立编写的 OfflineRxSource 以 --release 11 针对该真实 API 编译，所有 fetch 方法
只返回 Observable.just 的本地结果。加载/执行/映射由自己的 host 实现，JSON 调用由共享 client 发起。

验证热门/最新/搜索、Rx 默认到 suspend、partial details、manga/chapter memo、章节页面、延迟图片
URL、原始 index、image fragment 与 protected imageRequest headers。调用结果：

```text
ACTUAL_SUWAYOMI_RX_ABI=PASS JSON_REQUESTS=7 NETWORK_REQUESTS=0
```

这验证真实兼容 API 的本机离线调用闭环，不是七个联网源成功、1395 个源请求通过或 iOS 成功。
fixture、驱动及日志在 ignored build；外部 GPL/MPL bootstrap/兼容产物不在源码/默认发行包。
最后构建与测试使用 §25 的 scoped 命令；host 生命周期改动之后重跑 host 测试/发行及真实 API smoke。

### 仍待完成

动态 FilterList 全部控件/偏好持久化、相关推荐搜索回落、图片通过扩展自己的 client 执行/解扰与物化
仍待。不能将 getPageUrl 的普通 URL 直接交给 Darwin 下载器并称图片路径完成。
JNI 原生入口、OpenJDK Mobile、WKWebView/Cookie/JS、iOS SwiftUI 壳、S0/S3 和 G0/G2 真机证据仍待；
Windows 的本机/JVM/Android 结果不替代 macOS 原生编译或设备表现，KMP 总目标保持进行中。

## 27. P2 执行记录：动态 FilterList、共享 Android 映射与回调状态隔离

日期：2026-10-02。§26 的标准筛选空缺已推进为可执行协议；偏好设置和 iOS 控件渲染未因此完成。

### 协议与映射

- commonMain 新增 `SourceDynamicFilters` / `SourceFilterNode` / `SourceFilterValue` / `SourceFilterChange`。
  标准类型包括 HEADER、SEPARATOR、CHECKBOX、TRISTATE、SELECT、SORT、TEXT、GROUP；
  其他 custom Filter 显式标为 UNSUPPORTED。嵌套组、顺序、默认 state 和原生 choice 索引保留。
- `getDynamicFilters` 经 SourceRuntime/Endpoint/Client 提供独立 JSON 操作；descriptor 新增
  `isDynamicFilteringSupported`，默认 false。`SourceFilter.dynamicFilters` 提供 typed 变更。
  state union 使用既有 serializer 的 operation discriminator：toggle/triState/choice/sort/text；
  sort 同时携带 index/ascending，null 清空 nullable selection。
- 节点 ID 使用 path/kind/name，区别同名控件；值仍由原生 index 选取，不将 label/清洗后的 genre
  写回扩展。类型、范围、未知/重复 ID 与跨源标签在写入前全部验证；树/选项改变需重新读取定义。
- `MihonFilterRules` 共享原 Android 的 top:/sort:/text: 与组路径键、genre 清洗、组标题合并、
  CheckBox/TriState/Select/Sort/Text 写入规则。既有标签选项和偏好中保存的键不改名。
  原来 text 标签 startsWith 会将 AuthorExtra 写入 Author；共享规则改为完整 key 或 key= 边界匹配。
- Android `MihonFilterMapper` 只做 typed ABI 投影/写回，复用 common 规则；旧 UI 的 permissive
  stale tag 策略、checkbox/tri 重置和 select/sort/text 默认值策略保留。外部二进制未知 Filter 子类
  不能仅依赖本地 sealed when 穷尽性，adapter 保留 UNSUPPORTED 分支。
- JVM `getFilterOptions` 现在返回通用标签选项，`getDynamicFilters` 返回完整标准树；热门/最新分页不改。
  query/tag/exclusion/typed 控件走源的 getSearchManga。query-only/typed 请求只修改指定状态，
  未指定控件保留源默认值；legacy tag 请求复用旧 Android 的 checkbox/tri 策略。
  未映射的通用 year/locale/author 字段仍报 UNSUPPORTED_OPERATION；不忽略它们后返回列表。
- Android SourceRuntime 的动态树默认不支持，descriptor 保持 false；toParser 对非空 typed 变更明确
  报 UNSUPPORTED_OPERATION，防止把 JVM 控件参数悄悄丢弃。原 UI 调用路径继续正常运行。

### 原生对象与挂起生命周期

`MihonNativeFilters` 保留 source 返回的真实 FilterList、subclass、choice 对象及原 state，只修改 state。
全部请求校验完成后再写，写失败恢复；suspend/Rx 真正完成（成功或失败）后恢复，继而交付调用结果。
这也支持多次返回同一 cached FilterList 的扩展，搜索结束后不改变其默认控件。

取消不能使仍在执行的扩展过早丢失过滤上下文。反射 bridge 的 completion hook 同时负责恢复、
释放回调 lease 和完成 source gate；调用方取消后立即获得 cancellation，但回调完成前控件 state
仍保留。同一源后续操作等待 gate，且 gate 在 registry generation 内由所有 runtime 实例共享。
每个 runtime 的 manga/chapter snapshot 仍独立；不同源不共用 gate。
unload/reload 使用新 generation，旧回调只恢复旧 source 对象，不修改新安装源的 state。

SRP 分离共用规则、Android ABI、JVM 反射/native state 与调度；DRY 复用协议与原标签规则；
KISS/YAGNI 只投影标准控件并保留现有原生对象，不创建平行 Filter API 或升级依赖。

### 验证与范围

| 检查 | 结果 |
|---|---|
| source-host 编译/发行、测试 | 3 类，32/32（执行器含 6 个新增动态筛选用例） |
| core-source common metadata / JVM | 3 类，33/33（含 9 个共用筛选规则用例）；协议闭环覆盖新增 operation |
| app Kotlin / Hilt / unitTest 编译、选定回归 | 5 类，43/43；含 Android mapper 5 项与 dynamic 参数拒绝 |
| 合计 | 108/108，0 跳过；不代表全量 app 或设备测试通过 |

测试编译并加载 Java 11 ABI/JAR fixture，读取所有标准控件/未知节点，验证真实 native 对象状态
被写成包含/排除、原索引、排序方向、Unicode/=+/& 文本与嵌套 boolean；结束后原状态完全恢复。
涵盖错误恢复、非法参数写入前失败、取消后仍保留旧状态、另一个 runtime 等待回调、重载后隔离。
Android 差分检查原 tags/groups/source 对象、旧默认行为、文本边界及二进制未知子类的兼容路径。
未知子类用例生成独立 JVM 11 subtype 并跳过构造，只验证 sealed-dispatch fallback，
不把它称为外部未知 Filter 的构造/API 兼容性验证；MockK 的 sealed 替身会选用已知 child，不适用该用例。

本机研究进程另针对真实 Suwayomi API 编译独立离线扩展。Java 提供 Rx/local data 与实际 native
Filter subclasses，Kotlin 2.4（项目已有编译器）override getFilterList；这遵循真实 Kotlin default
bridge ABI，未修改外部 runtime jar 的 flags 或内容。fixture target 为 Java 11。
经自有 registry/runtime/shared JSON client，验证 source 的原始 cached FilterList：真实 typed
控件写回、原 choice index、sort ascending、nested group、JSON tree/legacy options 与默认状态恢复。
同时复验 §26 的列表/详情/章节/图片 URL/memo 路径，全程 Observable.just，无站点网络调用。

```text
ACTUAL_SUWAYOMI_RX_ABI=PASS JSON_REQUESTS=12 NETWORK_REQUESTS=0
```

驱动、fixture、研究 bootstrap/compat 层与日志仍在 ignored 本机路径，不在生产源码/默认发行包。
复验使用 §25 的 scoped Gradle 命令；host/API smoke 不等同 1395 个扩展的联网筛选可用性或 iOS 可用性。

后续仍须偏好设置/持久化、相关推荐、图片 client 执行/解扰/物化、原生控件 UI 与 JNI。
OpenJDK Mobile、WKWebView/Cookie/JS、SwiftUI、S0/S3、G0/G2 真机证据及 P1 剩余业务迁移仍待；
KMP 总目标保持进行中。

## 28. P2 执行记录：章节图片经扩展 client 执行与文件物化

日期：2026-10-02。继续 §23–27 的 JAR/JVM host 路线，补上章节图片执行。封面、原生图片消费与缓存
保留策略仍待；本节不是 iOS 图片桥或完整 Mihon 兼容性验收。

### 协议与调用链

- commonMain 新增 `SourcePageRequestContext(index,url,imageUrl,uri)`，显式保留原始 Page 字段。
  页面 ID 是哈希，不能还原 index；非连续页索引、Unicode/+ 的页面 URL 和解扰 fragment 原样保留。
- 新增 `SourceRuntime.fetchImage`、JSON image operation 与 `SourceImageArtifact`；artifact 携带来源、
  pageId、SHA-256、hash.img 相对路径、字节数和 contentType，64 位值继续用十进制字符串传输。
  DTO 校验只允许与 hash 对应的相对文件名；不跨 JSON 传图片字节、原生 Response 或绝对路径。
- `MihonSourceRuntime(registry,imageStore)` 的 store 默认 null，descriptor 的
  `isImageFetchingSupported` 默认 false，仅显式配置后的 HTTP 源启用。embedding 平台提供私有图片根目录。
- fetchImage 使用原始 context；无 imageUrl 时先调用源的 getImageUrl，再创建原索引/URL 的 Page，
  调用真实 HttpSource.getImage。该 API 内部调用扩展自己的 getClient 和 protected imageRequest，
  因此 source interceptor 的解密/解扰结果作为 response body 进入 store，不绕过为通用 Darwin 下载。
- 旧 mihon://image/resolve 包装仍可解析 index/origin/image；普通 URL 若没有 context 明确拒绝，
  不从哈希 id 猜索引。非 null native uri 显式 UNSUPPORTED，等待平台 file/content URI 桥。
- Android ContentPage 的 parser ABI 不增字段；Android adapter 对 host context 明确拒绝，防止跨执行器
  投影悄悄丢失图像上下文。原 Android 页面/reader 链不因此切换到 JVM host。

### 文件与资源生命周期

`SourceImageStore` 是接收流与取消检查的窄接口；`FileSourceImageStore` 只负责存储，不持有 HTTP 客户端
或图片解码器。64 KiB 块写入自建 staging 文件，默认每张上限 64 MiB，校验响应长度与文件头类型。
文件以 SHA-256 命名，在同目录原子发布；复用相同内容，已有 blob 损坏时校验后替换。多个 store
在本 JVM 用有界锁串行化同一文件的校验/发布，修复 Windows 打开校验流时并发替换产生 AccessDenied。
不支持原子 move 的文件系统直接失败；一个 JVM host 独占发布目录，不声称跨进程写入协调。

类型覆盖 PNG/JPEG/GIF/WebP/BMP/TIFF/JXL/AVIF/HEIC/HEIF 文件头，**只做格式识别**，不代表完整解码或
iOS 可显示。没有新依赖，不把 java.desktop 或另一个 OkHttp/graphics 实现加入发行包。

`MihonImageResponse` 保有独立 lease；消费完成、错误、取消和迟到回调都关闭 native response，使用其
扩展 ClassLoader 上下文。取消先关闭 body，再随流退出清理 staging；回调/body 未结束时卸载只能退休
注册，不能提前关闭 JAR。反射 resume 的取消清理 hook 负责已转换却未交付的响应；completion/映射
异常仍完成同源 gate，并释放已获得的资源，清理异常附加到原错误。平台 API 必须提供可由 close 解阻的流。

取消若发生在原子发布之后可留下完整 blob，调用仍取消，不将其报告为成功。文件累计容量/LRU、清理、
崩溃遗留 staging 和原生消费者生命周期属于平台缓存管理，未在这一层伪称已完成。
SRP 分离协议、源调用、响应 ownership 与文件存储；DIP 通过 store 接口连接平台目录；DRY 复用
registry lease/JSON/原生 Page；KISS/YAGNI 保留源自身客户端，不创建平行 HTTP 或解码实现。

### 验证与剩余

Scoped Gradle 检查包含 host 测试/发行、core-source common metadata/JVM、app Kotlin/Hilt/unitTest 编译，
以及 SourceRuntime/映射/Mihon 兼容/RepositoryFactory 选定回归。最终 XML 汇总如下；不代表全量
app、macOS 编译或真机检查。

| 检查 | 结果 |
|---|---|
| source-host 编译/发行、测试 | 4 类，46/46（文件存储 8、执行器 20、检查器/注册表各 9） |
| core-source common metadata / JVM | 3 类，34/34；image/context/64 位 artifact 协议闭环通过 |
| app Kotlin/Hilt/unitTest 编译、选定回归 | 5 类，44/44；原生 context 投影拒绝及 RepositoryFactory 回归通过 |
| 合计 | 124/124，0 失败、0 跳过 |

新增测试覆盖 JSON 与 64 位 artifact、原始字段/legacy context、unsupported
能力/URI、响应错误、取消 blocked body、迟到回调/退休 JAR、completion 失败清理、逐块 bytes/hash、
限额/截断/错误格式、去重/损坏修复、并发 store 和已存在非普通文件拒绝。

真实 Suwayomi API 的本机研究 fixture 增加自有 OkHttpClient：第一层拦截器 XOR 解密，终端本地拦截器
直接返回加密 PNG，绝不调用网络 proceed。protected imageRequest 检查原始 index/origin/image fragment。
自有 registry/runtime/JSON client/store 保存的字节与预期 PNG 完全一致，两页相同内容复用同一 blob。
同时复验列表、memo、章节、页面解析、FilterList state 写回/恢复。

```text
ACTUAL_SUWAYOMI_RX_ABI=PASS JSON_REQUESTS=14 IMAGE_CLIENT_REQUESTS=2 NETWORK_REQUESTS=0
```

这是实际 getImage/client/interceptor API 的本机离线闭环；不代表真实站点图片解扰、1395 个扩展请求、
Android Bitmap shim 或 iOS OpenJDK Mobile 成功。fixture/bootstrap/外部 compat 与日志仍在 ignored
路径，不进入源码或默认发行包。日志为 build/kmp-host-image-final.log 与 build/kmp-host-image-actual-api.log。

偏好持久化、相关推荐、封面执行、图片 native 消费/缓存/格式支持、动态控件 UI、JNI、Mobile JVM、
WKWebView/Cookie/JS、SwiftUI、S0/S3 和 G0/G2 真机证据仍待；KMP 总目标保持进行中。

## 29. Windows 纳入迁移目标与桌面常驻调试入口

日期：2026-10-02。用户明确要求同时考虑 Windows，并优先利用它更容易调试的条件。此决定补充既有
iOS 路线；Windows 是正式消费者与首选 JVM 调试平台，Windows 通过不能代替 iOS Zero/真机门槛。

### 平台分工与推进顺序

| 层 | Windows | iOS |
|---|---|---|
| 业务内核 | 复用 shared JVM 产物、Room JVM、reader-core/domain/net/backup/source | 复用同一 commonMain 的 Native 产物 |
| 源运行时 | 桌面 JVM，直接调用同一 registry/runtime/协议；可以断点调试 | OpenJDK Mobile Zero，经 C/JNI 协议桥 |
| UI | 独立 Compose Desktop 外壳，先做调试/最小漫画应用 | SwiftUI 外壳 |
| 图片 | 同一 getImage/client/store；桌面从平台私有目录消费产物 | 同一 host 执行，原生侧从共享私有目录消费 |
| 浏览器/JS/Cookie | Windows 平台桥，优先评估 WebView2；待实现 | WKWebView 平台桥，待实现 |
| 分发与数据目录 | 独立 JVM 应用/Windows 安装包、用户私有数据目录；待实现 | 侧载、iOS sandbox；待实现 |

Windows 首先覆盖当前本机的 x64；ARM64 需单独验证兼容层、图像/native DLL 与安装包。桌面界面由
新外壳消费共享状态/reader 语义，不搬运 Android Activity/Hilt/资源/Backdrop/Media3，也不改变 iOS UI 决策。
当前官方 [Compose 平台/版本说明](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
列出 Windows 桌面支持与打包 JDK 条件；引入桌面插件前须用项目现有 Kotlin/Gradle 实测版本组合。
Windows 浏览器可参考微软 [WebView2](https://learn.microsoft.com/en-us/microsoft-edge/webview2/)，它支持
原生应用嵌入 HTML/CSS/JS；JVM 互操作、线程/Cookie 生命周期与 Cloudflare 场景仍需自己的桥和验证。

| 顺序 | 工作与验收 | 当前状态 |
|---|---|---|
| W0 | 可复用的常驻 JVM host，协议调试、UTF-8/空格路径、身份校验、图片 client/文件、错误恢复/EOF | 本节已实现并在 Windows fixture 验证；真实 API 离线研究通过 |
| W1 | 桌面平台初始化、数据/偏好/网络装配、备份恢复、Room 实例、封面与图像消费；独立应用数据目录 | 数据目录/Room/图片 store（§30）、偏好与控制协议（§31–32）、基础兼容初始化（§33）、封面执行/缓存/消费（§41）、图书馆备份导出/合并恢复（含书签/统计，§53–54）已实现；完整平台能力/HTTP cache 路径/其余备份节/外部格式/完整图片格式与保留管理待完成 |
| W2 | Windows BrowserTransport/JS/Cookie/graphics shim 与代表源请求、调试面板 | WebView2 桥/Provider（§34）、调试 UI（§35、§38）、POST/headers/base URL 与 SDK Cookie（§37）、可见来源挑战闭环（§39）已接入；真实挑战、完整 API、代表源与高级图形待完成 |
| W3 | Compose Desktop 的库/浏览/搜索/详情/阅读/历史最小闭环、Windows 打包 | 初步操作闭环与窗口/重启验证已完成（§35），共享核心分页阅读见 §40，真实封面见 §41，标准动态筛选见 §42，Fit/相机交互见 §43，连续阅读与像素进度恢复见 §44，页面离线索引与临时图像资源见 §45，PNG/JPEG 长图分块见 §46，可选自动跨章与尺寸提示见 §47，整章下载/暂停继续与离线入口见 §48，下载分块清单/原子发布与旧索引迁移见 §49，元数据占用预览/确认回收见 §50，文件头尺寸与完整页面输出像素预算见 §51，Windows 本地 EXE/ZIP/MSI 与独立运行验证见 §52，图书馆备份页及跨进程恢复（含书签/统计）见 §53–54；完整阅读器、下载批量/图片磁盘容量管理、筛选预设与自定义控件/其余备份节/外部格式/同步、全量源生态、实际安装/升级/卸载与 S3/签名待完成 |

推进时优先在 Windows 验证共享逻辑与 host，再把同一 DTO/行为接到 iOS。iOS 的 G0/G2、S0/S3
继续独立记录；桌面 HotSpot 的速度、网络栈与图形实现不作为 iOS Zero 性能和兼容率的替代证据。

### 本轮可运行入口

`source-host` 新增 `SourceHostPlatform` 初始化/关闭 SPI、`SourceHostConfig` 与 `SourceHostJsonSession`。
CLI 的 `serve <config.json>` 在一个 JVM/registry 中接收 UTF-8 JSONL，每行一个既有 SourceRequest，
输出对应 SourceResponse 后立即 flush；请求顺序执行，状态/loader 不因换行重建。空行跳过，普通协议
错误返回结构化 error 后继续，EOF 关闭 registry 与平台。调用方仍拥有 Reader/Writer。

config 指定零参数的 `SourceHostPlatform` 类、扩展 path + 预期 package/versionCode/SHA-256，以及可选
imageDirectory。相对路径基于 config 所在目录，不受进程当前目录影响；先验证所有扩展，再创建图片目录。
provider 在显式初始化后提供 compatibility ClassLoader，负责其线程/资源关闭；默认发行包不附第三方兼容层。
CLI 将 bootstrap/扩展的 stdout 日志转到 stderr，stdout 只用于协议，不启动 TCP 服务或占用网络端口。
调试启动时可以向 JVM 传标准 JDWP 参数，断点落在与 iOS 共用的 source-host 代码上。

示例及 SPI 装配说明见 source-host/README.md。production provider/Windows graphics/browser 不是 CLI
自动提供的能力；不能仅写一个 config 就宣称完整 Windows Mihon 兼容层已完成。

SRP 将平台初始化、配置/会话与 source 执行分离；DRY 复用既有 JSON/registry/图片实现；KISS/YAGNI
先用简单 stdio 常驻会话，不新增 HTTP 服务、依赖、Windows Native 主目标或 Android 模型副本。
serialization 使用项目已有插件/版本，未升级 Kotlin 或核心依赖。

### 验证与证据边界

- 本机 Windows 的 host 51/51、core-source 34/34 通过。新增 5 项覆盖 Unicode/空格目录、实际 JAR
  加载/跨请求图片复用、相关 ID、坏请求/未知源后的继续处理、初始化/身份失败清理与真实子进程 CLI。
  子进程 fixture 没有修改 os.name，验证 stdout 完整 JSON、日志走 stderr、EOF 退出与 provider 关闭。
- 与本轮前段已通过的 Android 44 项合计 129/129，0 跳过；common metadata、host installDist 通过。
  日志 build/kmp-windows-host-tests.log；默认发行目录仍只包含自有 host/core-source 与既有 Kotlin 依赖。
- 独立研究驱动通过真实 Suwayomi API/provider 调用生产 CLI serve，验证 6 个关联响应、2 次经源客户端
  解密的图片物化、64 位 pageId、错误后恢复、EOF platform close 和 stdout 隔离，站点网络请求 0。

```text
WINDOWS_JSONL_ACTUAL_ABI=PASS JSON_RESPONSES=6 IMAGE_CLIENT_REQUESTS=2 NETWORK_REQUESTS=0 STDOUT_PROTOCOL_ONLY=true
```

真实 API 驱动仍沿用之前本机研究 bootstrap 的 Mac OS X 属性/shim 配置，并在验证结束后退出研究 JVM。
它证明 stdio/真实 API 的连接，不证明 Windows 原生 compat provider、graphics、浏览器或研究运行时线程
完整关闭。后者是 W1/W2 工作；临时驱动/第三方产物均不进入生产源码/发行目录。
证据为 build/kmp-windows-stdio-actual-api.log 与 build/kmp-windows-stdio-responses.jsonl。

Windows 桌面 UI/安装包与 iOS 原生/真机工作均未标记完成；KMP 总目标保持进行中。

## 30. W1 执行记录：Windows 持久化 Room 装配与失败/取消资源回收

日期：2026-10-02。上一轮 W0 的 JSONL host 已可调试，本节新增实际桌面平台存储消费者。

### 代码与依赖

- 新增 JVM/application 模块 `desktop-runtime`，与未来 desktopApp UI 分开。复用 core-db/core-source/
  source-host，没有新 schema、DAO、Android 模型副本或 DI 框架。
- `DesktopDataPaths` 的 Windows 默认目录为 LOCALAPPDATA/Kototoro，缺省用
  user.home/AppData/Local/Kototoro；其他 JVM 提供 .kototoro 通用回退。默认选择函数只计算路径，
  不创建文件、不改环境；open 可指定独立 root，支持 Unicode/空格。实际验证仅使用临时/ignored build 目录。
- `DesktopDatabase` 用既有 Room 2.8.4 的 JVM databaseBuilder、bundled SQLite 2.6.2 与 IO dispatcher。
  SQLite bundled 原先已由 core-db JVM 测试使用，现在供桌面运行时使用；没有升级依赖。当前
  [官方 Room KMP 配置](https://developer.android.com/kotlin/multiplatform/room) 已引用 Room 3 alpha，
  本项目只参考 JVM builder/driver/协程上下文原则，以本地固定 2.8.4 API 编译和实际运行验证为准。
- `DesktopRuntime.open` 执行实际 DAO 查询，使 Room 的 lazy 数据库完成 schema/migration 校验后再
  创建图片目录并交付 runtime。对 unknown legacy version/corrupt 文件保留数据并报错；没有 destructive
  fallback，也没有把 Android 的 SupportSQLite 迁移链挪到 Windows。
- 同一 runtime 暴露共享 MangaDatabase 与 FileSourceImageStore，后续 repositories/host/UI 用构造装配。
  数据库和 post-interceptor 图片处于同一持久化根目录；暂不实现偏好、备份恢复或浏览器/兼容 provider。

### Windows 验证发现与修复

实际 JVM 子进程的 System.out 默认编码会把中文 JSON 路径输出成乱码。desktop storage CLI 改用明确的
UTF-8 字节输出，独立子进程按 UTF-8 解码并比对真实中文目录，不能仅以 JSON parse 成功作为验证。

在固定 Room 2.8.4 上，损坏/v83 schema 的 startup 失败后，即使常规 Room.close 被调用，本机 Windows
仍发现数据库/WAL 句柄占用，JUnit 无法清理其测试目录。失败发生在 driver 已 open 但 Room 配置尚未成功
入池的阶段。`DesktopSQLiteDriver` 以 SQLiteDriver/SQLiteConnection 接口包装、追踪自己创建的连接，
由 desktop database owner 先关闭 Room，再兜底关闭剩余 native 连接；不反射/修改 Room 或 JNI 库。
正常 pool 关闭与兜底关闭不会重复调用 native close；清理会尝试全部连接并保留 primary/suppressed 错误。

`DesktopRuntime.close` 幂等。另有 acquisition ownership：withContext(IO) 可能完成资源创建却在返回
dispatcher 时因取消丢弃结果，open 的外层捕获该路径，关闭已创建 runtime。用可控制的 caller dispatcher
测试在 IO 返回后、交付前取消，确保调用方没有获得 runtime，数据库句柄已释放，仍可重开既有完整数据库。
关闭不会删除数据；成功建成的数据库/目录可以在取消后保留并于后续启动复用。

SRP 分离路径、database builder/owner、driver 句柄与应用 runtime；DIP 通过原有 SQLiteDriver 与源
store 接口装配；DRY 使用共同 schema/DAO/图片实现；KISS/YAGNI 不重写 Room 池或先引入 UI/服务框架。

### 检查与证据

| 检查 | 结果 |
|---|---|
| desktop-runtime 测试/编译/发行 | 13/13，0 跳过；真实 bundled Windows native SQLite + 磁盘数据库 |
| core-db JVM 回归 | 9/9，0 跳过 |
| source-host 回归 | 51/51，0 跳过 |
| 合计 | 73/73，0 失败 |
| core-db common metadata / app compileDebugKotlin | 通过；Android schema 文件没有变动 |

测试关闭后重新打开磁盘 DB，逐项比较来源、漫画、opaque sourceData、收藏分类/成员与阅读进度（含大
64 位 ID/章节 ID），并读取原 DAO 的关联查询和 Flow。独立 SQL 查询实际 PRAGMA user_version=84，
room_master_table identity 与已提交 Android 84.json 一致。未知版本保留 sentinel/版本；损坏文件字节
不变；图片 cache 被占用时 startup 失败也释放数据库；Windows 对关闭/失败/取消后的文件移动均通过。
driver 测试覆盖正常/未入池连接、幂等 close、全部清理尝试与原错误保留。

installDist 中实际包含 Room 2.8.4、SQLite bundled 2.6.2 与自有 JVM 模块，不包含 Android app 或研究
compat/bootstrap。独立 Java 进程运行生成发行目录，输出中文路径的 UTF-8 JSON，正常退出并释放数据库。
另直接运行生成的 Windows .bat：

```powershell
& "desktop-runtime/build/install/desktop-runtime/bin/desktop-runtime.bat" storage "E:/kototoro_demo/kt-kmp/build/kmp-windows-storage-smoke"
```

结果退出码 0，实际 schemaVersion=84、sourceCount=0，database/images 位于指定 root。运行 JVM 产物不
引用 Android 应用组件；Gradle 构建仍在同一 Android/KMP 仓库工具链中。测试命令为：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :desktop-runtime:test :desktop-runtime:installDist :core-db:jvmTest :core-db:compileCommonMainKotlinMetadata :source-host:test :app:compileDebugKotlin
```

日志：build/kmp-desktop-storage-final.log、build/kmp-windows-storage-cli.log。
Windows storage 不是 Windows 整体完成：偏好/网络/备份、封面/图像消费、compat provider、browser shim、
Compose Desktop UI 与安装包仍待。iOS builder、C/JNI/Mobile JVM/SwiftUI、G0/G2/S0/S3 仍需独立推进，
KMP 总目标保持进行中。

## 31. Windows / JVM 偏好持久化与构造前绑定（2026-10-02）

Windows W1 继续接入偏好存储，供桌面应用和后续嵌入 JVM 的 iOS 平台使用。本批实现共享类型/存储契约、
JVM 文件 backend、真实 Android 接口 bridge 和初始化 SPI；偏好控制树/变更协议与原生设置 UI 仍待。

### 构造顺序与类型

真实 pinned Suwayomi ConfigurableSource.getSourcePreferences 默认通过 Injekt Application 获取
`source_<id>` SharedPreferences，扩展构造或 lazy baseUrl/client 可以立即读它。因此启动顺序必须是
store → 初始化兼容层并绑定 Application/Context → registry 加载并构造扩展。不能加载后才恢复设置。
AndroidCompat CustomContext 默认另建 JavaSharedPreferences，没有现成的外部 store setter；本批没有改写
第三方类/字节码。具体平台 Application override 委托自己的 `MihonPreferenceBridge`，完整 Windows
compat provider 仍待。

core-source 新增 SourcePreferenceStore/SourcePreferences、edit/change/listener 与六种序列化值：
String、Boolean、Int、Long、Float、StringSet。Int/Long 不隐式互转；Long 保持十进制字符串，Float 原始
IEEE bits 保留 -0、Infinity、NaN，避免非标准 JSON 数字。读写集合隔离，保留 namespace/key，无 DI。

FileSourcePreferenceStore 使用 namespace SHA-256 文件名，文档校验 namespace/version/UTF-8，单份默认
4 MiB，损坏/未知版本保留原文件并拒绝读取。一个目录一个进程 owner，Windows 文件锁在 close 释放。
写入同目录临时文件，force 后 atomic move；无非原子覆盖 fallback。clear 先于最终 key 变更，并发 editor
按 delta 合并，commit 写盘失败返回 false，已发布内存仍可读，之后 edit 重试完整最新快照。
监听回调执行时不持有 store monitor，可重入编辑。

bridge 只引用父兼容 ClassLoader 的 Android 接口，用 Proxy 实现 SharedPreferences/Editor；Application
仍是具体平台类。保留类型错配 ClassCastException、默认值、空值删除、最后一次 key 写入、editor 复用、
弱监听器/平台 dispatcher、Android R+ clear null key 通知（可配置旧目标行为）。apply 当前也同步完成磁盘
尝试，异步写盘调度未实现，不将此行为描述成完整 Android UI/Looper 兼容。

serve config 的可选 preferenceDirectory 要求 SourceHostPreferencePlatform.initialize(store)，不支持的
provider 在构造前明确失败，不默默落到临时偏好。session 先关闭 registry/platform，再关闭 store；失败
路径也回收 owner，平台 shutdown 仍能保存设置。DesktopRuntime 将 preferences 放在同一持久化根目录，
拥有 store、close/取消清理、初始化失败 DB 回收；storage CLI 同时输出偏好路径。

### 检查与证据

| 检查 | 结果 |
|---|---|
| core-source JVM | 35/35，0 跳过；新增类型 JSON roundtrip |
| source-host JVM | 63/63，0 跳过；新增文件存储 6、bridge 4、session 生命周期 2 项 |
| desktop-runtime JVM | 15/15，0 跳过；新增持久化/owner 启动失败 2 项 |
| 合计 | 113/113，0 失败 |
| core-source common metadata / app compileDebugKotlin | 通过；未改变 parser ABI/Android 偏好入口/DB schema |

新增测试覆盖重开、64 位/float bits、namespace 隔离、集合隔离、clear/remove/最终写入、监听重入与排队
注销、并发 delta、限额、损坏/未知版本保留、写盘失败保留内存/恢复重试、Windows 关闭后目录移动、
初始化失败释放、扩展构造前读取与平台关闭回调保存。所有目录是临时/ignored build，没有打开真实用户
LocalAppData 数据。

另以本地实际 AndroidCompat-1.0/server-1.0 API 编译独立离线 fixture，在两个普通 Windows JVM 进程依次
write/read：自己的 Application override + Injekt 绑定 → 真实 ConfigurableSource 默认偏好方法 → 构造时
读 domain → 真实 PreferenceScreen/ListPreference listener 拒绝/接受 → saveNewValue → 六种类型 commit。
第二进程构造立即读到保存 domain，控制对象 getCurrentValue 与其他类型均恢复。实际 PreferenceScreen
addPreference 会把 screen 的 SharedPreferences 传给控件，必须正确绑定 screen；这属于后续控制树桥
的真实 API 约束，不能把 UI 控件当作无行为 DTO。

结果：`ACTUAL_ANDROIDCOMPAT_PREFERENCES=PASS`，PHASE=write/read，NATIVE_TYPES=6，
CONSTRUCTOR_READ=true，OS=Windows 11，NETWORK_REQUESTS=0。该实验没有 os.name override、boot shim、
第三方 bootstrap 或站点请求；研究 fixture/API 仍只在 ignored 本地目录，默认发行包不含第三方 compat。
这只证明偏好链路可在原生 Windows JVM 运行，不扩展成整个兼容层/真实扩展全量通过。

日志：build/kmp-preferences-final.log、build/kmp-preferences-ownership.log、
build/kmp-preferences-actual-write.log、build/kmp-preferences-actual-read.log。
构建命令：

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :source-host:test :source-host:installDist :desktop-runtime:test :app:compileDebugKotlin
```

按 SRP 分离共同类型、磁盘 backend、Android ABI bridge 与平台构造；DIP 通过 store/SPI；DRY 共用桌面/
stdio backend；KISS/YAGNI 不先引入设置框架或复制 Android 应用 prefs。下一步接入可执行 PreferenceScreen
控制树/变更协议，再推进 Windows compat/browser 与桌面界面。iOS G0/G2/S0/S3、C/JNI/Mobile JVM/SwiftUI
仍独立待验，KMP 总目标继续进行中。

## 32. P2 / W1 执行记录：可执行偏好控制树与设置变更协议

日期：2026-10-02。继续 §31 的构造前偏好存储，补齐 host 标准设置树与源校验/保存调用链。
高级输入绑定/自定义动作、原生设置界面、完整 Windows compat/browser provider 仍待；KMP 总目标保持进行中。

### 契约与执行

core-source 新增 SourcePreferenceScreen/Node/Choice、Kind、UpdateStatus/Update 与 preferences/
updatePreference JSON 操作。树带 SourceRef、随机 revision、路径节点 ID、原 key、标题/summary、值/
默认值、entryValues 和 enabled/visible；子树共享平台无关类型。describe 的 isPreferencesSupported
默认为 false，Android adapter 保持原设置入口并明确报 UNSUPPORTED_OPERATION；parser ABI/DB schema 不变。

provider 可实现 SourceHostPreferenceUiPlatform 返回已初始化的真实 AndroidCompat Context；库装配由
MihonSourceRuntime 的第三个参数传入，省略时明确不支持。MihonNativePreferences 运行真实
ConfigurableSource.setupPreferenceScreen，setup 前绑定 screen 的 SharedPreferences，setup 后绑定
整个树；保留原生控件/回调对象与 loader generation，值不会只投影为未经验证的 key 写入。

List/MultiSelect/EditText/Switch/CheckBox 的基本修改已接入；choices 保留原 entryValue，重复标题允许。
重复 key、选择定义缺失/错位/重复值、自定义动作、OnBindEditTextListener 输入绑定明确 UNSUPPORTED。
group 子树继承 enabled/visible 限制；树限制 16 层/1024 个控件。按父兼容运行时 ABI 读取，不注入另一份
Android/Preference 类型，也没有复制第三方设置框架。

update 先验证 source session/revision/节点、enabled/visible、可编辑类型与原选择值；验证失败不执行回调。
随后 invalidate revision → callChangeListener → 接受时 saveNewValue → 空 editor.commit → 投影更新后树。
空 commit 使用 native 保存后的快照，既覆盖 Android apply 的异步 flush，也让自有 store 重试完整快照；
成功、源拒绝、持久化失败分别返回 ACCEPTED/REJECTED/PERSISTENCE_FAILED。

拒绝/抛异常也不能回滚源的其他设置或控制对象副作用；throw 使用原协议脱敏错误，旧 revision 已失效。
读 preferences 会重建树并使旧 token 失效；不同 runtime session/卸载重载不复用旧 token。
同步回调共用现有同源 execution mutex/loader lease；caller 取消不假装中断已进入的同步回调，其他 runtime
实例等其真正结束。设置写入可在取消后发生，调用方不能无条件自动重试含副作用的 listener。
改变设置不会自动重构构造时缓存的 domain/client；需要重建时由平台生命周期显式处理。

### 检查与证据

新增 JVM executor 测试覆盖 JSON 投影、原始选择值/重复标题、默认/当前值、嵌套可见性、未知动作/
输入绑定/重复 key、拒绝/接受、其他控件变更、非法值零回调、过期 revision、异常效果保留/错误脱敏、
磁盘失败/内存值/重试、typed save、取消阻塞回调对另一 runtime 的源锁、generation 重载与 stdio Context SPI。
core-source 既有全操作 roundtrip 扩展覆盖新协议；Android 新增显式 unsupported 回归。

| 检查 | 结果 |
|---|---|
| core-source JVM | 35/35，0 跳过；全操作 roundtrip 包含新设置协议 |
| source-host JVM | 72/72，0 跳过；新增偏好执行器 9 项 |
| desktop-runtime JVM | 15/15，0 跳过 |
| app core/source 选定回归 | 27/27，0 跳过；新增 Android 设置协议显式 unsupported 1 项 |
| 合计 | 149/149，0 失败 |
| common metadata / app compileDebugKotlin | 通过 |

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :source-host:test :desktop-runtime:test :app:compileDebugKotlin :app:testDebugUnitTest --tests "org.skepsun.kototoro.core.source.*"
```

另以真实本地 AndroidCompat-1.0/server-1.0 API 编译独立 NativePreferenceSource JAR，自己的 inspector
核对 package/version/hash 后经 registry 构造；Application override/Injekt 在构造前绑定自有持久化 store。
source 使用真实 ConfigurableSource/CatalogueSource、PreferenceScreen/List/EditText/Switch/MultiSelect；
所有内容入口禁止网络。

由自己的 runtime → endpoint → JSON transport → protocol client 完成 describe/preferences/拒绝/文本/
开关/多选/接受/过期请求。写阶段 8 个响应，读阶段新 JVM 2 个响应；源构造时恢复 domain，四种控件值
重开一致，listener 对另一控件 enabled 的修改正确投影。结果为：

```text
NATIVE_PREFERENCE_PROTOCOL=PASS PHASE=write JSON_RESPONSES=8 OS=Windows 11 NETWORK_REQUESTS=0
NATIVE_PREFERENCE_PROTOCOL=PASS PHASE=read JSON_RESPONSES=2 OS=Windows 11 NETWORK_REQUESTS=0
```

没有 os.name override、boot shim、第三方 bootstrap 或站点请求。研究 JAR/driver/兼容 API 均在 ignored
本地目录，默认发行包只含自己的代码与已有 Kotlin 依赖；此证据不扩大成全量真实扩展设置或完整 Windows
兼容层成功。

日志：build/kmp-preference-screen-final.log、build/kmp-native-preference-actual-write.log、
build/kmp-native-preference-actual-read.log；JSON 响应在 build/kmp-native-preferences-write/read.jsonl。
按 SRP 分离共享 DTO 与原生执行，DIP 用 Context SPI，DRY 使用源执行锁和既有持久化桥；KISS/YAGNI
先接真实标准控件，不引入 UI 框架、并行设置体系或自动源重建。

## 33. P2 / W1 执行记录：Windows 兼容平台初始化

日期：2026-10-02。基于 §29–32 的正式 Windows 消费者，新增可选 `:mihon-desktop-compat`，用实际
AndroidCompat/Suwayomi API 初始化平台后接到原 registry/runtime/JSONL。完整 W1、浏览器/UI、分发与
iOS 门槛仍未完成，KMP 总目标继续进行中。

### 初始化与生命周期

直接调用 stock AndroidCompatInitializer/startApp 的本机探针失败：CustomContext 创建 ActivityManager
时反射调用非公开构造器，Windows/JDK 25 抛 IllegalAccessException。没有修改外部 JAR 或复制参考 GPL
Context/bootstrap 实现；独立 Application 覆盖公开存储/偏好入口，使用实际 createAppModule 和
NetworkHelper，形成另一条已验证的 API 装配链。

`MihonDesktopPlatform` 实现 SourceHostPreferenceUiPlatform，公开零参数构造可供 CLI 反射；嵌入时可
显式传 dataDirectory。未指定时从 FileSourcePreferenceStore.directory 的父目录选择同级 `compat`。
store 必须在扩展构造前绑定；Application.getSharedPreferences 委托既有 MihonPreferenceBridge，
files/cache/no-backup 进入私有目录。不创建 ConfigManager/stock CustomContext 或 `server.conf`。

兼容主线程使用实际 Looper.prepare + setMainLooperForTest + loop；这些为 pinned desktop API 的公开
方法，使 Looper 可退出。SharedPreferences listener 派发到此线程，真实 NetworkHelper/PersistentCookieStore
使用同一 `cookie_store` backend。Koin 注册实际 AppModule/Context，Injekt 切换到实际 KoinRegistrar；
不创建生产 Mihon/Android ABI 副本，也没有 boot shim、os.name override 或研究 bootstrap。

SDK 含全局状态，因此一个 JVM 只允许一个平台生命周期，初始化失败后需新进程重试；扩展重载沿用
已有 registry。拒绝已有全局 Koin/main Looper，初始化中途失败执行同一 close 路径。已创建的客户端单独
记录，清理不会再次触发 lazy client 初始化；CookieHandler 在 helper 初始化的 finally 中记录，以覆盖
部分初始化失败。close 取消已知调用、关闭 cache/pool/dispatcher，恢复自己替换的 CookieHandler/Injekt/
Koin，退出 Looper；多次 close 幂等。CLI 在平台之后关闭 store，Windows 句柄释放由测试实际检查。

### 构建与运行入口

默认 settings 不包含模块，通过 `-PwithMihonDesktopCompat` 显式启用。API 目录由
`mihonCompatibilityDirectory` 指定，AndroidCompat → server → android-jar 的顺序固定，避免原始 stub
覆盖 compat 类；五个关键 API JAR 核对 SHA-256。项目提供现有 Kotlin/coroutines/serialization 版本，
外部目录对应重复核心 JAR 排除；完整传递产物锁定与许可证清单仍待。

第三方 API 实测 class major 65，需要 Java 21+；adapter 自有字节码仍为 Java 11。仅 fixture compiler、
测试/子进程与 JavaExec 选择独立 toolchain（默认 21），本机已有 17/25 时指定
`-PmihonCompatibilityJavaVersion=25`，不改变 Android/Gradle JVM 或核心依赖版本。

外部 API 为 compileOnly/testRuntimeOnly，模块 JAR 只含自己的 adapter。默认 source-host installDist
仍只有自有 host/core-source 与既有 Kotlin 依赖，不附第三方 SDK、fixture 或扩展。仓库根 LICENSE 为
Apache-2.0；没有复制参考 GPL 实现，不代表 D3/S3 的运行时集成和分发许可已经完成。

`runSourceHost` 接收 `sourceHostConfig`，启动原 SourceHostCliKt serve 并转交 stdin；配置的 platformClass
为 `org.skepsun.kototoro.desktop.compat.MihonDesktopPlatform`，必须提供 preferenceDirectory 和经核对的
JAR identity。相对路径依旧基于配置目录。Gradle 可能打印自身构建信息；程序化纯 JSONL 直接运行 CLI
进程，扩展日志进 stderr、UTF-8 响应进 stdout。配置/启动示例见 mihon-desktop-compat/README.md。

```powershell
./gradlew.bat -PwithMihonDesktopCompat "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :source-host:test :source-host:installDist :desktop-runtime:test
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin
./gradlew.bat -q -PwithMihonDesktopCompat "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 "-PsourceHostConfig=E:/path/to/config.json" :mihon-desktop-compat:runSourceHost
```

### 验证与能力边界

fixture 使用实际 HttpSource/ConfigurableSource、Rx 默认 suspend bridge、ListPreference、OkHttp
client/imageRequest/interceptor；编译为独立 Java 11 JAR，经自己的 inspector/registry 加载，不放入父
classpath 或发行 JAR。源构造读取 domain，真实 listener 拒绝/接受，第二个普通 Windows JVM 构造时
恢复保存值。终端 interceptor 返回本地加密 PNG，不调用网络 proceed；实际 SDK/client 解密后的字节
经 runtime/endpoint/shared JSON client/image store 与期望 PNG 比对一致，原 Page index=12 保留。

五项测试分别覆盖：Cookie/主线程 listener/关闭；跨进程源设置与离线图片；错误数据根目录原数据保留；
已建立 Koin/Looper 后偏好 backend 失败的清理；真实 CLI 零参数 provider/中文控制标题/JSON 请求 ID/
EOF 正常退出。关闭后检查全局状态、自己的线程/dispatcher、重开偏好目录与 Windows 目录移动。

| 检查 | 结果 |
|---|---|
| mihon-desktop-compat 实际 API | 5/5，0 失败/跳过；子进程 OS=Windows 11，离线 fixture NETWORK_REQUESTS=0 |
| core-source JVM / common metadata | 35/35；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过，未增加第三方 compat JAR |
| desktop-runtime JVM | 15/15 |
| 合计 | 127/127，0 失败/跳过 |
| 默认配置 app compileDebugKotlin | 通过；没有启用可选模块 |
| adapter JAR / JVM target | 只含自有类；class major 55 |

另通过同一生产 provider 的 runSourceHost 加载四个本地真实扩展：comicfury 14、mangadex 61、buondua 1、
akaicomic 1，共 77 个源。source ID 完整集合与 §25 存档目录逐项一致，MIHON 键保持不变；EOF 正常退出。
这次不使用旧研究 bootstrap/shim，只调用 sources 元数据，没有调用站点内容入口；不扩大成全量扩展
请求/设置/图片或 iOS 验收。

日志：build/kmp-desktop-compat-final.log、build/kmp-desktop-compat-ownership-final.log、
build/kmp-desktop-compat-default-app.log、build/kmp-desktop-production-real-load.log。
初始化研究探针日志：build/kmp-windows-compat-init-probe.log、build/kmp-windows-context-probe.log。
外部 API/真实扩展与临时研究驱动仍在本机 ignored 目录，测试 fixture 自有源码在可选模块的 src/fixture。

剩余约束必须继续推进：SDK UAFlow observer 没有公开 Job，依赖进程退出终结；WebView factory 为
进程全局，目前明确 unsupported，避免默认 KCEF 下载。Android 系统服务/Resources/PackageManager
也明确 unsupported。NetworkHelper 的 HTTP cache 使用 SDK 创建的系统临时目录，关闭只释放句柄，
目录迁移、容量与遗留清理尚待；不能把 Application.cacheDir 私有化写成 HTTP cache 全部完成。

按 SRP 分离 Application/Looper/装配 owner，DIP 接既有 store/Context SPI，DRY 共用 JSON/session/源锁与
SDK 网络实现；KISS/YAGNI 只接当前实际链路，不造另一个 Android runtime 或引入浏览器框架。
下一步为 W1 剩余网络/数据生命周期、W2 BrowserTransport/JS/Cookie/graphics 与 W3 桌面界面；
JNI/OpenJDK Mobile/WKWebView/SwiftUI/G0/G2/S0/S3 仍需独立完成。

## 34. W2 执行记录：Windows WebView2 跨进程桥、离线 HTML/JS/Cookie 验证与 WebViewProvider 装配

日期：2026-10-02。基于 §29–33 的 Windows 兼容层与运行时基础，接入真实 Windows 浏览器/JS 执行能力。

### 架构决策与选型

- **拒绝重型 CEF/KCEF 依赖**：避免运行时从外部网络动态拉取 200MB+ Chromium 二进制文件，不向发行包注入闭源大型二进制，符合轻量桌面分发与离线确定性原则。
- **利用 Windows 11 预装 Evergreen Edge WebView2**：Windows 11 系统路径（`C:\Program Files (x86)\Microsoft\EdgeWebView\Application`）已预装常青版 Edge WebView2 运行时（当前本机为 `154.0.4258.48`）。
- **跨进程架构与 STA 消息泵解耦**：
  WebView2 原生 CoreWebView2 与异步回调对调用线程有严格的 Windows STA 消息循环要求（无消息循环直接同步等待会死锁）。
  为此设计独立轻量 C# 桥进程 `KototoroWebViewBridge.cs`，使用 Windows 自带的 `.NET Framework 4.0` C# 编译器（`csc.exe`）编译。
  桥进程内部运行专有 STA 消息循环（`Application.Run(new ApplicationContext())`），对外通过标准输入输出（stdin/stdout）提供 JSON-RPC 协议通道。
  完全隔离了浏览器崩溃或文件锁对主 JVM 进程的影响。
- **协议契约**：
  提供 `init`, `navigate`, `loadHtml`, `evaluateJs`, `getCookies`, `setCookie`, `close` 七项操作。
  支持 origin Cookie 注入与读取，以及无界面（off-screen）JavaScript 表达式求值；尚未验证真实挑战和 SDK Cookie 互通。

### 客户端与 AndroidCompat 集成

- `DesktopWebViewBridge.kt`：基于 Kotlin 协程与 ProcessBuilder 的 JSON-RPC 客户端，支持结构化请求 ID 关联、UTF-8 字符流与安全超时，实现 `AutoCloseable`。
- `DesktopWebViewProvider.kt`：实现 `android.webkit.WebViewProvider`，通过动态代理实现 `ViewDelegate` 与 `ScrollDelegate`。接驳 `loadUrl`, `loadDataWithBaseURL`, `evaluateJavaScript`（`ValueCallback`）以及页面加载完成通知。
- **Looper 与线程安全**：
  AndroidCompat 的 `android.webkit.WebView.<init>` 强制检查 `Looper.myLooper() != null`。通过在主线程/兼容线程绑定 `DesktopCompatibilityLooper` 或调用 `Looper.prepare()` 满足要求；所有客户端回调（`WebViewClient.onPageFinished`, `ValueCallback.onReceiveValue`）均派发至兼容主 Looper。
- `MihonDesktopPlatform.kt`：在平台初始化时读取显式 `kototoro.compat.bridge.exe`，注册 `WebView.setProviderFactory`，在未提供桥可执行文件时回退至不支持状态。生产代码不自动搜索默认桥。

### 检查与验证证据

- `mihon-desktop-compat` 新增 `DesktopWebViewBridgeTest`，共 5 项测试：
  1. `bridge launches and reports Evergreen Edge WebView2 version`：成功启动桥进程并识别 Evergreen Edge WebView2 运行时版本。
  2. `bridge loads offline HTML and evaluates JavaScript expressions`：加载离线 HTML 并通过 JS 表达式求值准确返回 DOM/标题。
  3. `bridge sets and retrieves cookies for an origin`：针对特定 origin 写入 Cookie 并验证能够准确提取（验证 cf_clearance 场景所需能力）。
  4. `DesktopWebViewProvider integrates with android webkit WebView to evaluate JavaScript`：通过真实 `android.webkit.WebView` 实例接驳 Provider，异步执行 JS 并通过 `ValueCallback` 获取返回值。
  5. `bridge closes cleanly and terminates process`：验证桥正常退出、子进程销毁以及关闭后拒绝对话的幂等保护。
- 规避测试临时目录锁冲突：Edge 在异步退出时可能短时间持有 Crashpad 锁文件，测试配置使用专用子目录路径隔离，保证 JUnit 进程清理平稳。

| 检查 | 结果 |
|---|---|
| mihon-desktop-compat 单元测试 | 10/10，0 失败/跳过（5 项平台探针 + 5 项 WebView2 桥/Provider 集成测试） |
| core-source JVM / common metadata | 35/35；通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 15/15；通过 |
| 合计 | 132/132，0 失败/跳过 |
| app compileDebugKotlin | 通过；无任何编译退化 |

本节证明 Windows WebView2 离线 HTML/JS/Cookie 与基础 WebViewProvider 装配，不能据此标记完整 W2 完成。
2026-10-03 复核当前代码：`loadUrl` 丢弃 additional headers，`postUrl` 退化为 GET，
`loadDataWithBaseURL` 未保留真实 origin/base URL；SDK CookieManager 与 WebView2 尚未互通。
Settings 应用、并发导航次序、失败回传/请求取消和 Chromium profile 关闭仍需补齐。
Cookie 测试只写入固定名称和值，不证明 Cloudflare 挑战成功；跨进程也不能保证浏览器文件锁永不影响关闭。
W3 接口与验证见 §35，iOS 原生和许可门槛继续独立推进。

## 35. W3 执行记录：Windows Compose 桌面入口与实际操作/重启/原生窗口验证

日期：2026-10-03。按用户最新优先级先推进 Windows，接续另一个模型的 §34 桥代码。
新增可选 `desktop-app`，默认配置保持 Android 入口；完整 KMP/iOS 和 Windows 打包仍未完成。

### 代码与操作入口

- Compose Multiplatform 1.12.0 与项目既有 Kotlin/Compose compiler 配合，采用该版本稳定 Material 控件；
  没有升级 Kotlin、Room、SQLite、coroutines 或 serialization。JVM 目标为 Java 17，外部 API 仍需 Java 21+。
- `DesktopSession` 装配真实 `MihonDesktopPlatform`、registry、共享 JSON endpoint/client 和现行存储。
  JAR 导入保存原文件 path/package/version/hash，启动时核对恢复；不复制安装、不转换 APK、不触及 Android 包管理。
- `DesktopController` 使用不可变 StateFlow 和串行操作；窗口接来源筛选、热门/搜索分页、详情/章节、
  收藏、继续阅读/单页图片、历史、标准源控件与拒绝/持久化结果。
- `DesktopLibrary` 复用共享 v84 schema/DAO，写入漫画/标签/章节/收藏/历史，状态直接归属 manga_id。
  Entity Graph / Work 没有恢复；Room Entity 仅为现有表映射。写入标签实体，保留标签 pin、收藏 pin/order/
  createdAt、历史 createdAt，按分支计算进度，拒绝 ID 冲突和无效页面。
- Session 持有浏览器，profile 位于私有数据目录；界面离开和协程取消不会丢失 native process owner。
  启动取消会回收已获得未交付的 Session；窗口退出先取消/等待操作，再关闭 registry/平台/存储。
  `application(exitProcessOnExit=false)` 允许正常返回后验证资源释放，不依赖强制 JVM 退出掩盖关闭问题。
- 开发 classpath 顺序从 compat 的 `suppliedCompatibilityRuntime` 配置复用，仅加入 run/test；
  普通 runtimeClasspath/默认 Android/source-host 发行不附外部 SDK，Windows EXE/MSI 尚未交付。

启动文档见 [desktop-app README](../../desktop-app/README.md)。本机可运行：

```powershell
./desktop-app/run-windows.ps1 -CompatibilityDirectory "D:/tmp/kt-compat36" -JavaVersion 25
```

可选参数 `BridgeExecutable`、`DataDirectory`、`ImportJar` 分别指定 WebView2 exe、独立数据目录和启动导入。
默认数据目录为 LOCALAPPDATA/Kototoro。自动 UI/数据库/窗口测试使用自己的临时目录，不访问站点。
首次正式脚本 smoke 发现 Compose 的晚配置覆盖了 run classpath/args，遗漏 Koin 且忽略了指定数据目录；
启动在 LOCALAPPDATA/Kototoro 新建了 v84 存储。只读 SQL 核对 manga/favourites/history 均为 0 行，
该目录保留未删除。现于 run 的 doFirst 应用开发 classpath/参数，实际脚本已使用指定的 build/desktop-preview，
并持久化离线 fixture 的身份记录；日志为 build/kmp-desktop-ui-preview.log。LinkageError 在窗口边界显示错误。

### 验证与边界

`DesktopLibraryTest` 五项覆盖：大整数/opaque sourceData/标签/章节在重开后完整恢复；按分支的页进度与
历史创建时间；收藏 pin/order/createdAt 与墓碑恢复；删除分类过滤；身份冲突/无效页/变更章节拒绝后数据保留。
没有数据库 schema 改动。

`DesktopAppTest` 两项使用真实 Windows 子进程、实际兼容 SDK 与独立自有 fixture JAR：

1. 使用 [Compose v2 UI 测试 API](https://kotlinlang.org/docs/multiplatform/compose-test.html)，
   点击来源/中文搜索/详情/收藏/阅读/历史/标准选择设置，验证 listener 拒绝和保存；第二个进程恢复源设置/
   收藏/阅读进度。章节 PNG 仍由源 client/interceptor 解密，网络请求为 0；本机已配置桥时另执行真实 HTML/JS。
   对 browse/search/details/reader/preferences 渲染截图并目视检查中文布局。
2. 通过生产 main 创建实际原生 Window，分别在启动中与导入后触发关闭；退出后重开数据库/偏好、
   移动测试数据目录，验证自己的句柄释放。WebView2 profile 的 Edge 子进程可能晚于桥退出释放，
   包含浏览器的测试采用最长 10 秒有界等待，不能将其写成所有原生子进程已同步退出。

测试任务将 fixture JAR 与桥二进制作为输入，避免文件内容变化后误用 up-to-date 的旧探针结果。

| 检查 | 结果 |
|---|---|
| core-source JVM / common metadata | 35/35；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 20/20，含 5 项新库/历史测试 |
| mihon-desktop-compat 实际 API / WebView2 | 10/10 |
| desktop-app UI / 生产 Window | 2/2，四个 Windows JVM 验证 write/read/window/window-early |
| 合计 | 139/139，0 失败/跳过 |
| 默认 Android compileDebugKotlin | 通过，没有启用可选桌面模块 |
| 正式脚本启动 | 已启动实际窗口，指定数据目录的 fixture 注册记录存在 |

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :desktop-app:test :desktop-runtime:test :mihon-desktop-compat:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata
```

日志：build/kmp-desktop-ui-final.log、build/kmp-desktop-ui-interactions.log、build/kmp-desktop-window-tests.log、
build/kmp-desktop-ui-boundary-final.log、build/kmp-desktop-ui-default-app.log；
渲染图与 write/read/window/window-early 进程结果在 desktop-app/build/reports/desktop-smoke。
这些是本机 ignored 构建产物，不进入提交。

剩余 Windows 工作：真实封面、reader-core 场景/连续滚动/键盘/预取/下载、高级过滤、备份/同步、
APK 转换/扩展复制安装与更新、Kotatsu/Legado 桌面装配、完整 WebView/请求/Cookie/图形与代表源验收、
HTTP cache 生命周期、Windows 打包和 S3。iOS 的 JNI/OpenJDK Mobile/WKWebView/SwiftUI/G0/G2/S0
继续独立完成。已有通过项不替代这些验收，KMP 总目标保持进行中。

SRP 分离窗口、Controller、Session、浏览器与存储投影；DIP/DRY 共用既有协议、registry、native 设置和
Room schema；KISS 保持单窗口与单页阅读，未引入另一套 DI 或平行状态库。

## 36. Entity/Work 移除后的同步路径修复

核对剩余 `entity` 文件发现：Room 的 `MangaEntity`、`FavouriteEntity`、`HistoryEntity` 等仍是 v84
有效表的行映射，不能按目录名删除。旧表常量供历史迁移使用，旧备份及同步 DTO 的可空 entity/anchor
字段保留读取兼容；收藏、历史和追踪读模型部分 `entity_id` 是 `manga_id` 的查询别名。
详情页的 EntityRelation UI 名称目前用于远端追踪站点的人员、角色和关联作品展示。

运行时同步存在实际遗漏：`SyncProvider` 的白名单及冲突更新仍指向已删除的 `work_favourites`、
`work_history` 和 `entity_id`，`SyncHelper` 成功同步后的 GC 也访问旧表。已改为
`favourites(manga_id, category_id)`、`history(manga_id)`，继续使用既有事务和四天墓碑保留期。
没有修改 schema 或恢复实体图谱。收藏读模型的旧 work/entity preferences 注释也已纠正。

`SyncProviderProjectionTest` 使用 AndroidX ProviderTestRule、独立内存 Room 数据库和真实
ContentResolver/ContentProviderClient；通过可覆写的受保护 database 属性提供测试数据库，生产仍由
原 Hilt EntryPoint 装配。测试 APK 声明系统可选 `android.test.mock` 库供该规则使用，生产 APK 不添加
依赖。HTTP 测试在 transport/auth 拦截器前返回自有 204 响应，不打开 socket、不改账户或生产数据。
参考 [Android 官方 Provider 测试说明](https://developer.android.com/training/testing/other-components/content-providers)
与 [Room 测试说明](https://developer.android.com/training/data-storage/room/testing-db)。

本机 API 35 模拟器四项全部通过、零跳过：

1. 同一漫画的不同收藏分类保留独立行；重复插入只更新匹配分类，历史只更新匹配 manga_id。
2. 批量操作中无效外键导致整批回滚，之前更新的排序键也恢复。
3. 旧 Work URI 不再暴露，SQLite 实际没有对应表。
4. 调用生产 SyncHelper 的收藏及历史同步：先导出有效行和全部墓碑，再清除十天前墓碑及过期分类；
   有效行和一小时前墓碑完整保留。

```powershell
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" "-Pandroid.testInstrumentationRunnerArguments.class=org.skepsun.kototoro.sync.ui.SyncProviderProjectionTest" :app:connectedDebugAndroidTest
```

本机 ignored init 脚本只将 app/core-backup 构建输出隔离到 build 目录；没有打开桌面可选模块。
应用编译通过；测试编译日志 `build/kmp-sync-projection-test-compile.log`，设备最终结果日志
`build/kmp-sync-projection-device-tests-final.log`。最初 AndroidX 规则缺少系统 mock 库的运行时声明导致
测试失败，补齐测试 manifest 后同一四项全部通过。已有 `app/schemas` 没有变化。

本次复用 v84 表、现有 DTO、Provider 事务和同步实现，避免新增状态体系与依赖；只保留用于隔离
测试数据库的单一装配入口。Windows 的浏览器请求/Cookie 互通工作仍在推进，其中跨进程 Cookie
恢复尚未通过，不能将本次 Android 同步结果视为 Windows 同步完成。KMP 总目标保持进行中。

## 37. W2 续接：Windows 请求语义、SDK Cookie 互通与跨进程恢复

日期：2026-10-03。接续 §34 的桥与 §35 的实际桌面入口，修复原先被忽略的请求参数，并解决 §36
尚未通过的 Cookie 恢复。使用固定外部 API、真实 WebView2 和自有 fixture；测试 HTTP 请求仅到
127.0.0.1，`.invalid`/其他 origin 只用于 Cookie 元数据查询，没有代表站点或挑战成功的结论。

### 实现与证据

- 原生 GET/POST 使用 CreateWebResourceRequest/NavigateWithWebResourceRequest，保留自定义请求头、
  UA 和原始二进制 POST 内容；拒绝请求头的 CR/LF。采用
  [Microsoft 请求 API](https://learn.microsoft.com/en-us/microsoft-edge/webview2/how-to/webresourcerequested)。
- HTTP(S) base URL 的 HTML 通过一次主文档响应替换保留真实 origin、path/query/fragment 和相对资源；
  不请求原主文档。空 base URL 仍使用 NavigateToString。依据
  [本地内容说明](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/working-with-local-content)
  选择该方式，避免 about:blank 的 origin 影响 Cookie 和相对请求。
- 原生命令按 STA 顺序执行，stop/close 可打断导航；Kotlin 导航 mutex 和 Provider actor 保证调用顺序。
  actor 在启动子任务前登记 activeNavigation，防止 onPageStarted 立即 stop 时漏掉当前任务。
  取消/超时发送 Stop 并清理 pending request，错误后仍可继续操作。关闭时验证桥进程退出码 0，
  包括导航中关闭；原生 EOF 不再向已销毁 Form 排队，Kotlin 关闭不再吞掉等待进程终止的失败。
- Provider 将设置、headers、postData、base URL 接到真实桥，reload 保留原 POST 字节；原生 document
  结果更新 URL/title，page/title/JS 和旧式错误回调在兼容主 Looper 执行。导航及 JS 前同步 SDK
  Cookie，完成后读取浏览器 Cookie，使扩展 CookieManager 和真实 OkHttp client 使用同一 SDK store。
- `DesktopBrowserCookies` 通过公开 Cookie API 同步 Secure/HttpOnly、host-only/domain、path、会话和
  过期属性，并处理删除。首次 push 从已有 native profile 清掉 SDK 中不存在的 Cookie，防止在 SDK
  离线清除后重启时恢复旧 token；后续 push 只写 SDK 变更的值，保留浏览器尚未 pull 的修改。
  使用 [Microsoft Cookie API](https://github.com/MicrosoftEdge/WebView2Feedback/blob/main/specs/CookieManagement.md)。
- 固定 SDK 的 cookie_store 已写入域名键的 Set-Cookie 字符串集合，但其初始化错误截取最后一个点
  之前的部分，漏读 dotted host。负向探针确认磁盘仍有 token、SDK 内存却为空。平台在 helper 创建
  前保存同一个偏好快照，通过公开 Cookie.parse/addAll 补回缺失的有效持久化 Cookie，保留已经由
  SDK 恢复的记录；不改第三方字节码、不复制 SDK 实现、不新建 Cookie jar 或第二份存储。
  该适配依赖当前固定 SDK 存储格式，升级须重验。过期、会话、畸形及不匹配 domain 的记录不恢复。
- 桌面调试 Browser 复用平台 Cookie coordinator，Session 持有其生命周期。WebView2 子进程可能在
  桥退出后继续释放 profile 句柄，测试在自有临时目录内检查移动，最长等 10 秒；不宣称 Chromium
  全部子进程已同步退出。相关生命周期参考
  [Microsoft 进程模型](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/process-model)。

### 验证

新增九项测试：五项真实请求/HTML/设置/取消/导航中关闭；一项跨进程 Cookie 探针；两项持久化恢复
边界；一项实际平台 WebView 探针。Cookie 探针顺序为 write/read/sdk-clear/empty/write/clear/empty
七个独立 JVM，覆盖原生与 SDK 删除、浏览器自行变更、SDK 更新、domain/host-only/security 属性、
会话不恢复以及清除后重启不复活。只记录自有域名/成功标志，不输出通用 Cookie 值。

实际 WebView 探针通过平台 factory 构造 SDK WebView，验证 GET headers/UA/Cookie、连续 load/JS
顺序、POST/reload 字节、base URL/title、CookieManager 互通、错误后恢复、onPageStarted 内 stop
再加载和回调主线程；关闭后 main Looper 消失且自有 profile 句柄最终释放。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata
```

| 检查 | 当前结果 |
|---|---|
| core-source JVM / metadata | 35/35；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 20/20 |
| mihon-desktop-compat 实际 API / WebView2 | 19/19，含新增九项 |
| desktop-app UI / 生产 Window | 2/2，仍覆盖 write/read/window/window-early |
| 汇总 | 148/148，0 失败、0 跳过 |

最终日志 `build/kmp-windows-browser-complete-batch.log`；未变更模块由 Gradle 复用匹配输入的
up-to-date 结果。Cookie/Provider 子进程结果在 compat 的 build/reports/browser-cookies 和
build/reports/browser-provider，UI 截图及进程结果仍在 desktop-app/build/reports/desktop-smoke。
复用 app 两项测试证明新 coordinator 接入后窗口操作/关闭仍有效，没有重启用户已有 preview 窗口。

W2 仍需现代错误回调、完整 WebView API/可见挑战、SameSite/分区/同名多 path、多 WebView 并发、
代表源与高级图形验收。HTTP cache、完整阅读器、封面/下载/扩展管理/APK 转换、其他源运行时、
桌面备份/同步、Windows EXE/MSI 和 S3 继续推进；iOS Mobile JVM/JNI/WKWebView/SwiftUI 与真机门槛
保持原范围。基础请求和 Cookie 验证不替代这些验收，KMP 总目标未完成。

SRP 将 Cookie 启动恢复与运行中同步分开；DRY/DIP 共享原 SDK store、偏好 backend 和现有平台
factory；KISS 保持公开 API 的一次恢复与串行 browser 操作，没有引入新的依赖或状态持久化体系。

## 38. W2 续接：Windows 可见浏览器与桌面交互调试

日期：2026-10-03。将 §37 的隐藏执行器接入可见 Windows 窗口和生产桌面调试页。测试仍使用
自有 HTML / 127.0.0.1 服务；没有访问生产站点、执行真实 Cloudflare 挑战或得出代表源兼容结论。

### 窗口、页面与生命周期

- 原生桥启动时创建隐藏 Form 和固定 HWND，支持显示/隐藏、调整大小、只读地址栏及页面标题。
  浏览器嵌在填充窗口的 Panel 内，尺寸变化更新 controller.Bounds，移动通知父窗口位置变化。
  原先运行中修改 FormBorderStyle / ShowInTaskbar 会重建父 HWND、使 WebView2 controller 失效，
  真实显示测试捕获该问题；现在这些属性在创建前固定，整个生命周期不重建窗口。
- 显示/隐藏和窗口状态命令绕过导航队列，可在慢请求期间操作。原生关闭按钮通过 FormClosing
  取消销毁并隐藏窗口；Session/Provider 的 close 仍最终释放 controller、Form 与桥进程。
  `dismissWindow` 测试触发同一 FormClosing 路径，不宣称进行了物理鼠标关闭按钮验收。
- 桥增加窗口状态和 PNG 预览，使用
  [Microsoft Bounds API](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2controller.bounds?view=webview2-dotnet-1.0.3650.58)、
  [IsVisible API](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2controller.isvisible?view=webview2-dotnet-1.0.3650.58) 和
  [CapturePreviewAsync](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2.capturepreviewasync?view=webview2-dotnet-1.0.3650.58)。
  JSON 消息上限为 16 MiB，以容纳预览 PNG 的 Base64；预览用于验证，不新增截图持久化业务。
- 桌面浏览器调试页支持 HTTP(S) 地址、打开可见网页、显示/隐藏、加载 HTML、当前页面 JS 与
  手动 Cookie 同步。Session 继续持有同一个 Browser 和原 SDK store。操作复用串行入口；输入
  错误和导航取消保留已初始化的 live bridge，初始化失败或进程死亡才回收并允许创建新桥。
  调试页可滚动，Composable 的操作取消后原生窗口仍有 owner，退出时统一释放。
- 完整回归发现 Cookie 清除的时序问题：SDK store 已为空，原生删除返回后仍能查询到旧记录。
  删除 RPC 现在通过 GetCookiesAsync 确认指定 name/domain/path 已消失后才返回成功，最长等
  2 秒，超时明确失败；不采用测试内固定 sleep 隐藏问题。仍使用
  [DeleteCookiesWithDomainAndPath](https://learn.microsoft.com/en-us/dotnet/api/microsoft.web.webview2.core.corewebview2cookiemanager.deletecookieswithdomainandpath?view=webview2-dotnet-1.0.3650.58)
  的精确 domain/path 语义，不扩大删除范围。保留 Cookie 测试的失败身份信息，只有自有 fixture
  name/domain/path，避免打印 Cookie 内容。

### 验证与产物

新增四项测试，并复跑原平台与 Cookie 用例：

1. 真实原生窗口渲染中文页面；PNG 尺寸与 viewport 一致，背景像素匹配自有 HTML；调整窗口
   后 DOM innerWidth 减小。隐藏后仍能执行 JS，FormClosing 后可重开，owner close 退出码为 0。
2. 慢导航期间可显示/关闭窗口；停止导航后继续加载，正常退出。
3. 原生 Cookie 删除确认只删匹配 domain/path，同名的另一路径与 domain Cookie 保留，最终
   全部目标删除后查询为空。该项只覆盖桥的精确删除，不等于 SDK 同名多 path 已全部验证。
4. 新的独立 JVM 桌面 UI 探针点击调试页按钮，打开 loopback 页面、用 JS 触发自有确认按钮，
   Cookie 写入 SDK cookie_store；隐藏/重开、无效 URL 后保留页面、离线 HTML 均通过。离开
   面板取消慢导航后仍可使用原浏览器；Session 在可见窗口、未完成导航期间关闭，存储可重开，
   profile 最长 10 秒内释放句柄。测试等待 Compose 取消完成后再查询 browser，不阻塞测试调度器。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata
```

| 检查 | 当前结果 |
|---|---|
| core-source JVM / metadata | 35/35；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 20/20 |
| mihon-desktop-compat 实际 API / WebView2 | 22/22 |
| desktop-app UI / 生产 Window | 3/3 |
| 汇总 | 152/152，0 失败、0 跳过 |

最终日志 `build/kmp-windows-visible-browser-complete.log`。未变更模块由 Gradle 复用匹配输入的
up-to-date 结果；compat 与 desktop-app 实际重跑。原生 viewport PNG 在
`mihon-desktop-compat/build/reports/browser-window/visible-page.png`，UI 截图在
`desktop-app/build/reports/desktop-smoke/browser-panel.png`，两张均实际查看，中文文本和控件正常。
没有重启用户已有 preview 窗口；需要重启开发应用才能使用此次编译的代码。

当前可见窗口用于手动调试，并与 SDK Cookie store 互通；源调用中的挑战检测、自动提示/窗口
交付及请求恢复仍需完成。现代错误回调、完整 WebView API、多 WebView 并发、SameSite/分区
Cookie 和代表源验收继续推进。Windows 完整阅读器/封面/下载/扩展安装/APK 转换/其他源运行时、
桌面备份与同步、EXE/MSI、S3，以及 iOS Mobile JVM/JNI/WKWebView/SwiftUI 和真机门槛保持原范围。
本轮没有修改 schema、Room Entity、旧备份 wire 字段或恢复已移除的 Entity Graph / Work；KMP 总目标未完成。

SRP 保持原生窗口归桥 owner、UI 状态归 Composable；DRY 复用同一初始化/串行操作入口；DIP
沿用平台 Cookie factory 和共享存储；KISS 不增加依赖、平行浏览器状态库或新的数据模型。

## 39. W2 续接：Windows 来源请求的人工网页验证

日期：2026-10-03。接续 §38 的可见窗口，将来源 HTTP 请求、桌面提示、浏览器 Cookie 与请求恢复
接成同一个流程。验证使用固定外部 SDK、独立加载的自有扩展 JAR、真实 Compose UI 和 WebView2；
HTTP 服务仅为 127.0.0.1。模拟页面自行写入测试 Cookie，没有执行真实验证码或 Cloudflare 挑战。

### SDK 接入与交互行为

- 通过 javap 核对当前 SDK：NetworkHelper 的 CloudflareInterceptor 走 ServerConfig / FlareSolverr，
  并不使用 WebView factory。因此仅提供 WebView2 factory 不会让来源请求进入可见验证窗口。
  平台在扩展取得客户端前，通过原 client.newBuilder 替换这一 application interceptor，保留原
  CookieJar、UA、cache、dispatcher、connection pool 和其他拦截器。
- NetworkHelper 是 final 且没有公开 client setter。版本适配集中在 DesktopChallengeInterceptor：
  检查恰好一个 CloudflareInterceptor，并核对 client$delegate / cloudflareClient$delegate 为 Lazy，
  反射绑定到同一个新客户端；不匹配则初始化失败并清理。没有修改 SDK JAR、字节码或新建 helper。
  这是固定 SDK 私有 ABI 的显式依赖，升级必须重验；现行 Gradle 验证五个关键 JAR 哈希，不能据此
  宣称兼容任意 SDK 版本或已经完成发行锁定。
- 按 [Cloudflare 官方响应标记](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/)
  检测 cf-mitigated: challenge；普通 403 和 Server: cloudflare 不打开窗口。仅 GET/HEAD 进入交互，
  先关闭原响应，确认后只重试一次；再次收到挑战由来源处理，不循环。POST 等方法明确失败，不自动重发。
- DesktopSession 显式启用 enableBrowserChallenges；零参数平台及 JSONL host 默认关闭，避免缺少
  确认 UI 时等待人工操作。Session 持有一个 DesktopBrowserChallenges，通过 pending StateFlow
  发布带 ID 的提示；UI 支持“已完成，继续请求”“取消验证”和“显示验证窗口”，来源 busy 时仍可操作。
  旧 ID 不能接受后续请求。浏览器继承原请求 UA，导航前 push SDK Cookie，确认后 pull 再重试。
- 同一平台串行处理人工交互，默认超时两分钟。排队 Call 取消不会取消前一个提示；UI 取消、原 Call
  取消、桌面动作取消、超时和 owner close 均结束等待、清除提示并释放桥。关闭应用先结束交互，再
  cancelAndJoin 来源任务，避免退出等到人工超时。
- 实际 UI 取消测试暴露 SDK/Rx 的同步 subscribe/execute 时序：Call.cancel handler 尚未登记时，
  只取消桌面 Job 不会打断等待。withRequestCancellation 通过 ThreadLocal.asContextElement 传递
  当前操作的原始 Job，协调器同时观察此 owner 和 Call，并在首次请求及重试前检查取消。
  该归属按请求保存，避免全局“取消当前提示”误伤下一请求；扩展自建线程的 owner 传播尚未验收。
- 实际 HTTP 403 页面已渲染，但原生 NavigationCompleted 报 IsSuccess=false / WebErrorStatus.Unknown。
  为挑战导航增加显式 allowHttpErrorResponse，仅在有 HTTP 4xx/5xx 状态时允许页面交互；连接失败
  仍报错。普通 WebView 和浏览器调试导航保持原行为。

### 验证与产物

新增六项父测试，包含独立 JVM 内的多个真实交互场景：

1. 普通 403 不触发交互；挑战 POST 只发一次且不创建浏览器，两项 interceptor 边界测试。
2. 原生 HTTP 403 默认失败，显式允许后页面可读；关闭本地服务后，允许 HTTP 错误也不掩盖连接失败。
3. 原生交互超时，以及排队 Call 取消，两项独立 JVM 测试；取消排队请求后当前提示仍有效。
   关闭平台后在自有临时目录检查 profile 句柄最终释放，不宣称所有 Chromium 子进程同步终止。
4. 实际桌面加载独立 InteractiveSource 扩展：来源初始请求 → 浏览器导航 → 来源重试共三次，
   UA 一致，重试携带页面写入的测试 Cookie 并返回作品。随后验证 UI 取消无重试、动作 Job 取消、
   旧提示 ID 无效、未解除挑战只重试一次、重新显示窗口，以及等待人工操作时退出并重开 v84 存储。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata
```

| 检查 | 当前结果 |
|---|---|
| core-source JVM / metadata | 35/35；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 20/20 |
| mihon-desktop-compat 实际 API / WebView2 | 27/27 |
| desktop-app UI / 生产 Window | 4/4 |
| 汇总 | 158/158，0 失败、0 跳过 |

最终日志 build/kmp-windows-source-challenge-complete.log：BUILD SUCCESSFUL，46 秒，12 项执行、
34 项 up-to-date。compat 与 desktop-app 实际重跑；未变更模块复用匹配输入的测试结果。
生命周期日志在 mihon-desktop-compat/build/reports/browser-challenges，实际 UI 截图为
desktop-app/build/reports/desktop-smoke/source-challenge.png，已查看三个中文操作按钮与显示反馈。
测试结束未发现遗留的桥进程，没有重启用户已有 preview；开发应用需重启才会载入新编译代码。

来源挑战的本地闭环已建立，真实 Cloudflare、代表来源、其他请求方法、现代回调、完整 WebView API、
多 WebView 并发及 SameSite/分区 Cookie 仍待验收。Windows 完整阅读器、封面、下载、扩展安装/APK
转换、其他源运行时、桌面备份/同步、EXE/MSI 与 S3，以及 iOS Mobile JVM/JNI/WKWebView/SwiftUI
和真机门槛继续沿用原计划。没有修改 schema、Room Entity 或重建已移除的 Entity Graph / Work；
KMP 总目标仍未完成。

SRP 将固定 SDK 客户端适配、交互协调和 UI 分开；DRY/DIP 复用现有客户端资源、Cookie factory 与
Session 生命周期；KISS 使用单一提示、按请求取消和一次重试，没有增加依赖或另一套 Cookie 存储。

## 40. W3 续接：共享核心的 Windows 分页阅读器

日期：2026-10-03。接续 §35 的单张 Fit 阅读，将 reader-core 作为真实桌面消费者接入；保持 Android
阅读器与共享场景算法不变，不新增第三方依赖、数据库表或 Entity Graph / Work。Windows 优先范围
继续按 §29 执行，本节不代表完整阅读器、Windows 或 KMP 迁移完成。

### 场景、图片与操作

- DesktopReaderLayout 将原 SourcePage ID、章节 ID 和图片头尺寸转换为 PagedPageSpec，使用共享
  PagedReaderScene / PagedSpreadConfig 决定单页/双页、LTR/RTL、宽图单独显示和 Fit 几何。
  可见图片、前后页组、历史锚点均来自同一场景；UI 根据实际 viewport 像素尺寸绘制 ReaderFrame
  的页面位置，不另写配对或 RTL 算法。框架焦点和图片加载属于桌面层，reader-core 保持纯 Kotlin。
- 未取得图片尺寸时使用 Estimated hint；请求后读取实际宽高并重新解析当前页组，直到其可见页面
  已加载。宽图可能将预估双页拆成单页，保留请求目标的 PageId；未读取的更早页面仍只有预估尺寸，
  不宣称未加载整章也已知所有宽图。章节切换重建页面集合，不将不同章节拼成同一个双页。
- 图片继续经原 SourceProtocolClient → MihonSourceRuntime → 扩展 getImage/imageRequest/client，
  保留 Page requestContext 与 headers，再由既有 image store 物化。桌面只保存当前章节的路径和
  头尺寸，显示中的页组由 Compose/Skia 解码；回看已加载页面不重复请求，“重新加载”重新获取当前
  页组。加载/尺寸读取失败不发布新页组，也不推进历史；解码 UI 提供重新加载提示。
- DesktopReader 提供模式/方向、前后页组、同分支上一章/下一章和首尾键盘跳转。模式与方向写入
  原 store 的 desktop_reader namespace，不建立另一份持久化系统。image 状态从 readerImages
  和当前 anchor 派生，避免单页路径与页组路径互相失配。
- 依据 [Compose Desktop 官方键盘事件 API](https://kotlinlang.org/docs/multiplatform/compose-desktop-keyboard.html)，
  焦点范围内处理左右键（RTL 左键向前）、PageUp/PageDown、Space/Shift+Space、Home/End 和 Esc。
  进入章节及点击页面取得焦点，Ctrl/Alt/Meta 组合不翻页；busy/关闭期间消费快捷键但不排队新操作。
  页面区域使用指针手势取得焦点，避免 clickable 合并语义节点后隐藏独立页面；图片保留可读页码。
- DesktopLibrary.recordPage 新增默认等于 page 的 lastVisiblePage：page 保存页组起始锚点以恢复，
  percent 使用页组末页计算分支进度。双页到章末不会少算一页，既有单页调用保持原行为。
  参数越界先拒绝，继续写 v84 历史字段，没有 schema/DAO/Android 迁移变化。

### 验证与产物

新增四项父测试：两项桌面场景适配、一项持久化页组进度，以及一个包含两个独立 JVM 的完整阅读
UI 测试。独立 ReaderSource JAR 使用真实 SDK、host 和共享协议，提供五张自有彩色 PNG（第三张
900×300，其余 400×600）及两章；终端 interceptor 验证原始 index/Referer，返回自有数据，不访问
DNS、网络或真实来源。没有将 fixture 类放入测试父 classpath 或发行 JAR。

- 场景测试覆盖完整的 [1,2] → [3 宽图] → [4,5] 翻页顺序、首尾边界、大整数 PageId、双页的起始
  锚点、RTL 物理位置交换和不同 viewport 下页面仍可见。
- 实际 UI 通过触摸焦点与键盘输入事件验证左右键、PageUp/PageDown、Space/Shift+Space、Home/End、
  Esc 和 Ctrl 组合；断言真实图片节点的位置及页码。图片 503 时仍显示原页，历史行保持不变；
  回看不增加请求计数，重新加载当前双页恰好新增两次请求。
- 同分支下一章/上一章按钮、末章禁用、末页组完成百分比与历史锚点通过。第二个 JVM 在 920×620
  像素画布恢复双页/RTL 和第一章页组锚点 3（显示第 4–5 页），断言图片均在实际 viewport 内。
  第一进程使用 1260×850；这是两种渲染尺寸与 Compose 键盘注入验证，不等于物理键盘或高 DPI 全验收。
- v84 数据重开，以及已有来源交互、生产 Window 启动/导入/退出、设置、Cookie、Provider 与原生
  浏览器测试仍通过。全量回归曾暴露已有超时测试把原生冷启动算入 4 秒交互预算的问题：测试现在
  给同一完整请求 15 秒，明确等到提示或请求终结，仍断言窗口出现、超时 cause、提示清理和句柄
  最终释放；父进程 45 秒有界等待。生产协调器的两分钟超时未变，不用固定 sleep 掩盖失败。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
```

| 检查 | 当前结果 |
|---|---|
| core-source JVM / metadata | 35/35；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 21/21 |
| mihon-desktop-compat 实际 API / WebView2 | 27/27 |
| desktop-app UI / 场景适配 / 生产 Window | 7/7 |
| reader-core JVM / metadata | 150/150；metadata 通过 |
| 汇总 | 312/312，0 失败、0 跳过 |

最终日志 build/kmp-windows-reader-complete.log；未变更模块复用匹配输入的 up-to-date 结果。
UI 子进程记录在 desktop-app/build/reports/desktop-smoke/reader-write.log 和 reader-read.log；
同目录的 reader-write-ltr.png、reader-write-rtl.png、reader-write-wide.png、reader-read-restored.png
记录双页、宽图和较小窗口恢复。RTL、宽图及恢复截图已实际查看，彩色页面与中文操作正常。
没有重启用户已有 preview；开发应用需重启才能载入新编译代码。

后续继续完成连续滚动、缩放/拖动、预取、自动跨章与下载，真实封面、动态筛选、备份/同步、扩展
管理/APK 路线、其他源生态、HTTP cache 生命周期、Windows 安装包及 S3。真实 Cloudflare 和代表源
仍需独立验收；iOS 的 JNI/Mobile JVM/WKWebView/SwiftUI 与原生编译、硬件门槛保持原范围。

SRP 分离场景适配、操作与渲染；DRY 使用原 reader-core 配对/进度规则、图片 store 和偏好 backend；
DIP 继续经共享 SourceRuntime 执行图片，不直接绕过扩展 client；KISS 保持当前页组按需加载，避免
复制 Android renderer 或增加平行缓存/数据库。

## 41. W1 / W3 续接：真实来源封面、取消归属与离线缓存

日期：2026-10-03。将 Windows 浏览、详情、收藏与历史的占位封面接到既有源执行和图片 store。
使用当前固定 SDK、独立编译/加载的自有扩展、真实 Skia 解码和桌面 UI；终端 interceptor 返回自有
图片，测试不访问 DNS、生产站点或实际来源。KMP 总目标和 §29 的 Windows 优先范围保持不变。

### 共享协议与来源执行

- 增加独立 fetchCover(content, large) / cover 操作及 isCoverFetchingSupported 能力；默认关闭，
  未实现的 SourceRuntime 返回 unsupported。协议版本仍为 1 的增量操作，保留既有 image 字段。
  SourceCoverArtifact 使用 contentId，章节 SourceImageArtifact 仍使用 pageId；ID/byteSize 继续
  为十进制字符串，响应只有 source/hash/relativePath/size/type，不传图片字节或绝对文件路径。
  两种产物共享路径/哈希/大小校验，封面复用现有 SourceImageStore 的流式物化及原子发布。
- 核对 Android ContentCoverFetcher / MihonMangaRepository：封面尝试扩展 imageRequest，章节
  上下文假设失败时回退通用请求，仍使用扩展 client。host 采用相同顺序，回退使用 source.headers
  的 GET；不经 getImage 的章节处理，不绕过 client 的 Cookie/限流/解扰拦截器。既有特殊封面
  Referer 补充规则提取到 commonMain MihonModelRules，Android 与 host 共用，来源声明的值不覆盖。
- MihonHttpCall 使用平台提供的公开 OkHttp enqueue/Callback ABI，动态加载其类型；不增加
  OkHttp 依赖、不创建第二个 client、不修改第三方 JAR。取消执行 actual Call.cancel，回调期间
  设置扩展 context ClassLoader，迟到结果归响应 owner 关闭；回调结束前保留 loader lease 与
  pendingCall，后续同来源操作等待原调用结束。body 与章节图片共享同一关闭/限额/暂存清理逻辑。

### Windows 缓存、界面与生命周期

- DesktopSession 持有 DesktopCovers；相同 source name + content ID + 实际封面 URL 合并为一个
  正在执行的请求，等待者分别取消。最后一个等待者离开才取消 producer，后续调用可以重试；
  最多并行三个 producer，同一来源仍遵循 host 执行等待。Session 关闭先 cancelAndJoin 封面 owner，
  再关闭 registry/platform/storage，防止 UI 任务在存储关闭后继续写缓存。
- 内存仅保存最多 256 个文件路径；原 preferences backend 的 desktop_covers namespace 保存
  最多 256 条带写入时间的 metadata，按写入时间淘汰旧记录，不宣称磁盘 LRU。文件仍用原 image
  store 的 SHA-256 blob；记录 key 对来源/作品/URL 求哈希，不额外保存原 URL。恢复检查内容/来源
  身份、普通文件（不跟随文件符号链接）、大小和 SHA-256；损坏或缺失再获取，locale 缺失不会
  改变身份。成功可供收藏/历史和离线重启复用；失败没有负缓存，磁盘写入失败允许内存继续使用。
- UI 按 Compose 生命周期请求/解码封面，支持大图 URL、标题占位及单独“重试封面”。封面任务
  不将整个 Controller 标成 busy，也不把原始网络错误/URL 打进错误提示。列表与阅读操作仍经过
  原来源执行队列，不能因此宣称同一来源绕过其限流或正在等待的验证。新增状态不改写 Room schema
  或用户收藏/历史归属，没有恢复 Entity Graph / Work。
- 本节只限制封面 metadata/内存路径和并发，不回收已有图片 blob，不代表 HTTP cache 的私有目录、
  TTL/容量、全图片磁盘保留策略或全部解码格式已完成。生产的真实来源、挑战封面、缓存更新策略与
  iOS 图片消费仍需按原计划验收。

### 验证与证据

新增九项父测试：三项 commonMain 规则/协议边界、五项缓存生命周期/持久化、一个包括两个独立
JVM 的实际封面 UI/host 探针。确认五个缓存方法均为 JUnit 可发现的 Unit 返回类型，XML 中五项
全部实际执行；其中一个初版曾因推断返回异常对象而未被发现，已修正并重跑，不能只以编译/总绿作证。

1. cover JSON 保留超过 2^53 的 contentId/byteSize，结果没有 pageId/绝对 URL；拒绝越界路径/空大小。
   旧 descriptor 缺少字段默认能力 false，未实现执行返回 unsupported；既有 Referer 行为保留。
2. 两个缓存等待者共享一次请求，取消其中一个不停止另一个；最后一个取消终止 producer，可再次
   请求；owner close 在 producer 清理完成后返回。失败可重试、不发布失败记录。
3. 原 preferences 与文件重开后无网络恢复；来源缺少 locale 仍复用。自有 blob 被改坏后重新请求并
   修复；260 个记录限制为 256 条，相同图片内容仍只有一个 blob。
4. 独立 CoverSource 扩展的自定义请求、缺章节上下文后的 source.headers 回退、client XOR 解扰
   和坏图片重试通过。列表 → 详情 → 收藏 → 阅读 → 历史的同一封面只有一次成功请求；独立 large
   URL 的请求/产物身份通过。SDK 当前没有 banner setter，实际详情沿用 thumbnail fallback；
   large URL 的独立调用测试不冒充该 SDK 原生 banner 元数据支持。
5. 第二个 JVM 将终端请求设置为离线失败，浏览/收藏/历史仍渲染已验证的持久化封面，没有触发
   封面网络执行。取消故意延迟的实际 OkHttp 请求后，下一来源操作等待其回调；释放后原 body
   关闭，下一操作成功，图片目录没有 .part 残留。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin
```

| 检查 | 当前结果 |
|---|---|
| core-source JVM / metadata | 38/38；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过，未增加第三方 HTTP/SDK 依赖 |
| desktop-runtime JVM | 21/21 |
| mihon-desktop-compat 实际 API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 场景 / 生产 Window | 13/13 |
| reader-core JVM / metadata | 150/150；metadata 通过 |
| 汇总 | 321/321，0 失败、0 跳过 |
| Android 应用编译 | compileDebugKotlin 通过 |

最终日志 build/kmp-windows-covers-complete.log（1 分 3 秒；19 项执行、37 项 up-to-date）与
build/kmp-cover-android-compile.log（1 分 40 秒）。未变更模块复用匹配输入的结果；Android 本机
ignored init 只隔离 app/core-backup 的构建输出，不启用桌面模块或改变生产配置。没有运行设备/
真实账号/站点测试或 R8，Android 编译与共用规则测试不替代这些验收。
UI 日志/截图在 desktop-app/build/reports/desktop-smoke/cover-write-* 和 cover-read-*；已查看
cover-write-browse.png 与 cover-read-history.png，真实彩色封面、中文界面和离线历史显示正常。
测试结束未发现遗留的 WebView2 桥进程，没有重启用户已有 preview。

继续完成完整阅读器、下载/动态筛选/备份/同步、扩展管理/APK 路线与其他源生态、完整浏览器/真实
挑战及代表源、HTTP/图片 cache 管理、Windows 安装包与 S3。iOS 的 Mobile JVM/JNI/WKWebView/
SwiftUI、原生构建与硬件门槛仍按原范围推进；本节完成封面执行闭环，KMP 总目标仍未完成。

SRP 分开 cover 协议、HTTP callback、缓存 owner 和 UI；DRY 共用原 image store/response 生命周期、
偏好 backend 与 Android 的 Referer 规则；DIP 通过共享 SourceRuntime 执行，KISS 使用有界 metadata
和按等待者取消，没有另一套用户数据库、HTTP 下载器或第三方依赖。

## 42. W3 续接：Windows 原生动态筛选与网页验证互通

日期：2026-10-03。将既有 SourceDynamicFilters / SourceFilterChange 与 Mihon 原生筛选执行器
接入 Windows 浏览界面，继续复用共享 SourceRuntime JSON 契约。没有新增依赖、修改第三方 JAR、
schema 或 Android 代码，没有重启用户已有 preview；KMP 总目标仍未完成。

### 行为与边界

- DesktopFilters 直接消费共享控件树，显示 header/separator/group 与嵌套条件，支持 checkbox、
  tristate 的忽略/包含/排除、select、text、sort 的索引/升降序及 nullable 清除。提交的是类型化
  ID/index/value，不用展示标签替代原生值；同名选项可以选择不同的 opaque 原生对象。
  unsupported 控件明确显示提示并保留来源默认状态，不伪造自定义控件交互。
- 草稿属于当前面板；取消不执行来源请求，恢复默认取当前来源声明的实际值，包括 true、
  INCLUDE、非空文本及 null sort，而不是将全部条件置零。应用前使用共享 MihonFilterRules
  校验；只有来源成功返回时才发布新列表、查询和 appliedFilters，失败保留此前结果与草稿并允许重试。
- 翻批次和再次搜索沿用已应用条件；应用新条件回到第一个批次。热门/最新清除查询与筛选，
  最新仅对声明 UPDATED 能力的来源显示，并在翻批次时保留 UPDATED。切换来源清空旧筛选定义、
  查询、排序和应用状态，不把另一个来源的 ID 或值送到新来源。
- 筛选请求开始后，面板暂时收起，原草稿仍保存在 Compose composition；主窗口的网页验证继续/
  取消/重显按钮因此可操作。请求失败或验证取消后面板重新出现并保留草稿，成功后关闭。
  退出仍由 Controller/Session 原 owner 取消和清理，不引入第二套请求队列或浏览器。
- 搜索行与来源操作行分开排布，避免新增按钮压缩搜索框。面板列表可滚动，嵌套分组保留层级。
  当前筛选条件只在 Session 中保留，不包含筛选预设/跨重启存储、自定义控件与全部来源生态验收；
  库内收藏筛选也不等同于来源筛选，本节不宣称这些剩余项已完成。

### 实际验证

独立编译的自有 FilterSource JAR 使用当前固定 SDK 的缓存 FilterList、具体原生子类、嵌套 Group
和两个展示名称相同的 opaque Select 对象。通过共享协议进入真实 ClassLoader/host，再从实际
fetchSearchManga 接收的原生状态核对参数；不访问 DNS、HTTP 或生产站点。

新增一个 JUnit 父测试，在两个独立 Windows JVM 中分别使用 1260×900 与 920×620 的实际 Compose
桌面 UI，点击标准控件，覆盖取消、应用、分页、再次搜索、排序清除、热门/最新、恢复默认、
来源失败后重试和来源切换。核对查询/页号、checkbox/tristate、opaque 原生对象索引、文本及排序
方向；成功和失败后再执行 popular，确认缓存原生 FilterList 的默认状态恢复。首次未应用的查询
也可被热门按钮清空。不支持的控件实际显示提示，源码没有临时模拟“支持”标志。

既有 InteractiveSource 自有 loopback 扩展增加一个原生 Text，WebView2 UI 探针从筛选面板发起
真实 SDK/Rx HTTP 请求。请求等待自有 cf-mitigated 页面时，面板消失而验证按钮可操作；点击取消后
面板和原文本草稿恢复。只访问自有 127.0.0.1 fixture，不代表真实 Cloudflare 或代表来源兼容率。
UI 测试继续使用 [Compose 官方测试 API](https://kotlinlang.org/docs/multiplatform/compose-test.html)，
没有升级项目固定的 Compose/Kotlin 版本。空查询检查 EditableText，避免将 placeholder 当成输入内容。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 38/38；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 21/21 |
| mihon-desktop-compat API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 场景 / 生产 Window | 14/14 |
| reader-core JVM / metadata | 150/150；metadata 通过 |
| 汇总 | 322/322；0 失败、0 错误、0 跳过 |

最终日志 build/kmp-windows-filters-complete.log：59 秒，57 项任务（7 项执行、50 项 up-to-date）；
未变更模块复用匹配输入的既有结果。两种窗口的子进程日志 filters.log / filters-narrow.log 均有
DESKTOP_UI_OK 与 NETWORK_REQUESTS=0。已查看 filters-edited.png / filters-narrow-edited.png：
中文嵌套条件、排除、同名选项、文本、升序与恢复默认/取消/应用均正常显示。
本轮没有 Android 输入变更，未重跑 Android 编译/设备/R8；§41 的 Android 结果仍为对应输入的证据。

SRP 将面板草稿与来源执行分开；DRY 复用既有共享校验/协议和 host 原生状态恢复；DIP 依赖
SourceRuntime，UI 不引入 Mihon SDK 类型。KISS 使用 Session 内类型化条件，不建立平行用户状态
数据库或重复的筛选解释器。继续完成完整阅读器、下载、备份/同步、图片/HTTP cache 管理、扩展/APK
路线、安装包与 S3；iOS Mobile JVM/JNI/WKWebView/SwiftUI 及原生/真机门槛仍独立推进。

## 43. W3 续接：共享相机数学、Windows Fit 与缩放/平移

日期：2026-10-03。继续完成 Windows 阅读器交互，复用 reader-core 的 PagedSpreadResolver、
PagedPanBoundsResolver 与 PagedSlot 反算视口。没有新增依赖、修改数据库 schema/第三方 SDK 或
重启已有 preview；Android 渲染器行为未改写，KMP 总目标仍未完成。

### 共享语义与桌面接入

- commonMain 增加不可变 PagedCameraTransform，提供 1–5 倍指针锚点缩放、平移限界、基准阅读
  起点及 ReaderViewport 反算。限界/起点复用原 PagedPanBoundsResolver，不重新实现另一套范围
  数学；支持非零 scene slot、native/Fit 溢出和 RTL 起点。NaN/Infinity/无效倍率不进入 renderer，
  极大但有限的手势值限界后再构造状态。
- DesktopReaderSettings 使用原 ZoomMode 的 FIT_CENTER / FIT_WIDTH / FIT_HEIGHT / KEEP_START，
  Fit 模式写入原 desktop_reader namespace，旧记录缺少字段时仍默认 FIT_CENTER。KEEP_START
  继续表示共享核心的原始像素几何，不把原始尺寸伪装成 user zoom；四种模式均通过同一场景排版。
- DesktopReaderLayout 的加载集合/进度锚点来自完整 canonical slot，而不是相机当前可见节点。
  native 溢出或放大可能只显示双页中的一张，但整个页组仍可平移到达，记录的 page/percent 不因此
  改变。当前页组均已加载后，zoom/pan 不触发来源请求、重新解码或历史写入；翻页仍按原页组规则执行。
- DesktopReaderCanvas 使用原生 pixel placement 与单个 graphicsLayer 相机，外层视口裁剪。
  自定义 Layout 按共享页面的实际尺寸测量/摆放，允许子页面超出视口，不让普通 size 约束把
  FIT_WIDTH 高页或原始尺寸强制压小。页组切换/跨章/模式变化重置相机；视口 resize 后，用户已经
  操作的倍率保留、位移重新限界，未操作的视图按新几何重新对齐起点。这也处理 busy 加载条引起
  的短暂视口高度变化，避免 RTL/Fit 起点保留旧尺寸偏移。
- 增加缩放按钮、Ctrl+加/减号、Ctrl+0、Ctrl+滚轮、双击基准/200%、拖动、普通滚轮平移及触摸
  pinch。Fit 或原始尺寸在 100% 时已溢出的内容也允许平移；“重置视图”回到当前 Fit 的阅读起点。
  使用稳定 pointerInput 与 detectTransformGestures，参考 [桌面鼠标事件 API](https://kotlinlang.org/docs/multiplatform/compose-desktop-mouse-events.html)
  与 [Compose 多点手势 API](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch)。
  原翻页快捷键仍在 reader 焦点范围消费，缩放焦点不影响同分支跨章与错误后重试。

Fit 模式随偏好跨重启保存，临时 zoom/pan 不持久化。当前仍按完整图片解码/当前页组加载，没有
旋转、惯性、分块解码、连续滚动、预测预取、自动跨章或下载，不代表完整阅读器验收。这一纯 Kotlin
相机可由 iOS 消费，但 common metadata 通过不替代 iOS 编译/原生 UI/硬件手势与性能验收。

### 验证与证据

新增七项父测试：五项 shared camera 数学/边界、一项 native 溢出时完整页组装载、一项含两个独立
Windows JVM 的实际相机 UI 探针。共用现有独立 ReaderSource，自有彩色页码和 terminal interceptor，
不访问 DNS、网络站点或真实来源；WebView2/浏览器旧回归仍只访问自有 loopback。

1. 指针锚点在非零 slot 的缩放前后指向同一 scene 点，反算视口尺寸正确；极大有限倍率/平移
   限界、缩小到基准、FIT_WIDTH 从顶部到下缘、native 双页 RTL 起点、resize 限界和无效数值均通过。
2. 原始尺寸双页中离开基准视口的页面仍属于加载集合，完整 canonical group、翻页目标和起始
   进度锚点保持正确；既有单/双页、RTL 与宽图独页场景继续通过。
3. 实际 UI 点击按钮、键盘、鼠标双击/拖动、Ctrl+滚轮及两指触摸。核对 100%/125%/200%/500%
   和倍率上限；使用实际 transformed bounds 与截图中白色页码质心移动验证页面缩放/拖动，
   不只检查百分比文本，也不把 unscaled layout size 当作 graphicsLayer 缩放后的尺寸。
4. FIT_WIDTH 的页面宽度等于视口、长页从顶部开始且普通滚轮可向下平移；KEEP_START 的自有页面
   保持 400×600 原始像素；FIT_HEIGHT 宽页保持视口高度、超出宽度可平移，RTL 起点通过实际页码
   像素位置核对。缩放/平移过程中历史与图片请求数不变，双页放大不改变 canonical pageIndex。
5. 第二个 920×620 JVM 恢复 DOUBLE / RTL / FIT_WIDTH、页组 4–5 和 page=3 / percent=.5，倍率
   回到 100%。重开共享 v84 存储通过；原阅读器错误/回看/重载/键盘/跨章与生产 Window 退出仍通过。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 38/38；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 21/21 |
| mihon-desktop-compat API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 场景 / 生产 Window | 16/16 |
| reader-core JVM / metadata | 155/155；metadata 通过 |
| 汇总 | 329/329，0 失败、0 错误、0 跳过 |
| Android 应用编译 | compileDebugKotlin 通过 |

最终日志 build/kmp-windows-camera-complete.log：1 分 10 秒，57 项任务（7 项执行、50 项 up-to-date）；
未变更模块复用匹配输入的结果。Android 日志 build/kmp-reader-camera-android-compile.log：24 秒，
50 项任务（9 项执行、41 项 up-to-date），ignored init 只隔离 app/core-backup 输出，不改变生产
配置/开启桌面模块。未运行 Android 设备、R8、真实来源或 iOS Native/硬件测试。
已查看 reader-camera-write-zoom-drag.png / fit-width-pan.png 与 reader-camera-read-restored-fit.png；
真实页面、缩放、平移和较小窗口的 RTL/Fit 恢复正常。子 JVM 均输出 DESKTOP_UI_OK 和 NETWORK_REQUESTS=0。

SRP 由 shared camera 负责数学、canvas 负责输入/渲染、原 Controller 负责页组/历史；DRY 复用原
场景/边界/偏好 backend。DIP 让桌面依赖 commonMain 的相机/ZoomMode，KISS 保持单个视口 layer
与当前页组，不引入平行阅读进度或图片下载器。完整 Windows 阅读器、下载/备份/同步、缓存管理、
扩展/APK 路线、安装包与 S3，以及 iOS 全部原生/真机门槛继续推进。

## 44. W3 续接：Windows 连续阅读与像素位置恢复

日期：2026-10-03。增加 CONTINUOUS 模式，沿用原来源执行器、图片 store、偏好和 Room v84
历史表，没有新增依赖、schema 或第三方 SDK 改动，没有重启已有 preview。

### 实现

- DesktopScrollLayout 复用 VerticalReaderScene，已加载图片头和未知页面估计尺寸决定页面高度
  与视口进度。LazyColumn 使用稳定 page ID；上方迟到尺寸不改变 item/页内像素锚点，宽度变化
  重新限界并恢复锚点。支持滚轮、拖动、滚动条、上下键、PageUp/PageDown、Space/Shift+Space
  和 Home/End；连续模式按宽度排版，暂不使用分页相机，保存原分页方向/Fit 偏好。
- DesktopScrollOperations 由原 Controller scope/gate 持有，按可见页集合加载，同需求不重复
  建立任务。需求变化取消旧任务，generation 阻止迟到状态提交；前台操作先取消并等待任务。
  图片继续走扩展自己的 client/interceptor。成功页面逐页缓存，失败页等待显式重试，后台加载
  不设全局 busy。成功定位后恢复阅读焦点，重试按钮不阻断后续键盘操作。
- 全部可见页成功且实际 item 高度匹配共享几何后才提交进度。首页、页内像素偏移与末个可见页
  使用原 history；scroll 沿用 Android 的 page-relative pixel 语义，percent 沿用分支章节规则。
  跳转目标与最后验证位置分别保存，失败目标不推进 page/scroll/percent。
- 历史写入 debounce 150ms，导航与退出先 flush 最后验证的视口。负数/非有限 scroll 写库前
  拒绝；原分页调用默认 scroll=0，数据库版本仍为 84。

### 验证

新增四项父测试：两项垂直场景投影、一项实际 SQLite 像素进度与无效值拒绝、一项含两个独立
Windows JVM 的真实 Compose UI 探针。自有 ReaderSource 使用实际 SDK/JAR 和 terminal
interceptor 返回彩色 PNG，不触发 DNS、socket 或生产站点。UI 验证可见范围加载、可见页失败
不写历史、显式重试、缓存回看请求不增长、滚轮/键盘、首末页、跨章、失败跳转后的重试及键盘
继续阅读。立即返回详情后，第二个较小窗口 JVM 恢复 page=3 和相同页内像素偏移。
新增失败跳转测试发现并修正重试后焦点问题，专项复验通过。

已查看 failure/failed-navigation/pixel-progress/restored 真实渲染截图，两个子 JVM 输出
DESKTOP_UI_OK 与 NETWORK_REQUESTS=0。既有 WebView2 回归仍只访问自有 loopback，不代表
真实 Cloudflare 或代表来源兼容率。

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 38/38；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 22/22 |
| mihon-desktop-compat API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 场景 / 生产 Window | 19/19 |
| reader-core JVM / metadata | 155/155；metadata 通过 |
| 汇总 | 333/333，0 失败、0 错误、0 跳过 |

最终日志 build/kmp-windows-scroll-complete.log：1 分 20 秒，57 项任务（7 项执行、50 项
up-to-date）；未变更模块复用匹配输入的结果。
Android compileDebugKotlin 通过，日志 build/kmp-reader-scroll-android-compile.log：2 秒，
50 项任务（7 项执行、43 项 up-to-date），编译输入未变更而复用结果。ignored init 只隔离
app/core-backup 输出，默认 Android 构建未启用桌面模块；未运行 Android 设备或 R8。

SRP 分开场景投影、输入/渲染与加载/进度任务；DRY 复用共享垂直场景、图片请求与历史表；
DIP 通过来源协议执行扩展；KISS 使用现有 scope/gate 和稳定 item，不建立平行下载器或数据库。
章节已加载图片保留到离开/跨章，尚无长章节容量淘汰、预测预取、自动跨章、分块解码或下载。
Windows 备份/同步、缓存管理、扩展/APK、安装包与 S3，以及 iOS Mobile JVM/JNI/WKWebView/
SwiftUI、原生编译和真机门槛仍未完成；本轮没有运行 iOS Native/硬件验收。

## 45. W1 / W3 续接：页面离线索引、请求身份与临时 Skia 资源

日期：2026-10-03。检查长章节生命周期后确认 readerImages 保存的是文件路径/尺寸，像素由
Compose 页面节点持有；不能将路径数量当成已解码图片数量。当前明确缺口是跨章/重启丢失
artifact 索引而再次请求图片，以及转换 ImageBitmap 后临时 Skia Image 未及时关闭。

### 实现与归属

- Session 增加 DesktopReaderImages，分页/连续模式共用原图片 store 与 desktop_reader_images
  namespace。最多保存 256 条 artifact 索引，按写入时间淘汰；已有过量/无效记录也在新写入时
  限界。淘汰只移除索引，原 immutable blob 保留供现有阅读节点使用，没有删用户图片文件。
- cache key 使用扩展原 JAR SHA-256 与完整 SourcePage JSON，包括 source/page ID、URL、headers
  和原始 requestContext。扩展更新或请求上下文改变不命中旧记录，原生 image fragment 不丢失。
  索引只保存 artifact/写入时间，原始请求字段不写入索引值。
- 读取复用前检查 regular file、大小及 SHA-256；损坏/缺失走原来源 fetchImage，返回的
  page ID/source 和文件内容再次校验。共享 verifiedImagePath 也用于封面跨进程缓存恢复，
  读取时检查协程取消，I/O 缺失按 cache miss 处理，不建立另一套 HTTP 或图片下载器。
- 强制刷新绕过索引，只有新图片校验/头读取成功后替换记录，失败不覆盖此前成功缓存。
  请求仍使用原扩展 client/interceptor/挑战 owner；函数在调用者协程内执行，Mutex 保护索引
  更新，没有新增 detached scope/后台任务。偏好写入仍为既有 best-effort 缓存元数据语义，
  磁盘失败不会阻止当前图片阅读，也不承诺该记录能在重启后保留。
- 检查本机固定 Compose 1.12.0 的 SkiaImageAsset 与 Actuals 字节码，toComposeImageBitmap
  创建独立 Bitmap 并绘制源 Image。ReaderImage/DesktopCover 因此用 use 及时释放源 Image，
  转换后的 Bitmap 继续由 Compose 持有；不是在显示期间关闭 Compose 的 Bitmap。

### 验证

新增六项实际图片 store/偏好磁盘测试：关闭后离线重开、同尺寸内容损坏后恢复、强制刷新与
失败保留、扩展/来源/原生上下文/headers 隔离、数量淘汰且文件保留、无效记录/过量索引恢复、
错误来源身份拒绝及调用者取消后不提交记录。多个检查合在对应语义测试中，不只复刻实现。

既有 ReaderSource terminal interceptor 增加自有 offline 开关。分页和连续模式的第二个
独立 JVM 在读取前启用 offline，任何图片请求都计数并抛错；实际 UI 仍恢复 DOUBLE/RTL 页组
4–5 和连续模式 page=3/页内像素位置，图片请求数为零。测试没有要求源浏览/详情/章节元数据
离线可用，也不把页面缓存宣称为完整离线下载。查看 reader-read-restored.png 与
reader-scroll-read-restored.png，实际像素正确；既有缩放/触摸/Fit、失败重载、封面及原生窗口
退出回归继续通过。两个子进程输出 DESKTOP_UI_OK 和 NETWORK_REQUESTS=0。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 38/38；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 22/22 |
| mihon-desktop-compat API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 场景 / 生产 Window | 25/25 |
| reader-core JVM / metadata | 155/155；metadata 通过 |
| 汇总 | 339/339，0 失败、0 错误、0 跳过 |

最终日志 build/kmp-windows-reader-cache-complete.log：1 分 33 秒，57 项任务（11 项执行、46 项
up-to-date）；未变更模块复用匹配输入的结果。本轮未修改 Android 输入，§44 的 Android 编译
仍对应当前输入，未重复设备/R8/iOS Native 或硬件验收。未增加依赖、修改 schema/第三方 SDK、
提交 Git 或重启已有 preview，WebView2 旧测试仍只访问自有 loopback。

SRP 将持久化索引与视口/历史分开；DRY 共用 artifact 校验和原图片 store；DIP 通过共享来源
协议执行图片请求；KISS 沿用调用者取消和现有偏好，不创建平行下载数据库。256 条索引不是
像素内存/图片目录总容量上限；全图解码、长图分块、预测预取、自动跨章、下载与磁盘清理仍待。
完整 Windows 打包/备份/同步、扩展/APK 与 S3，以及 iOS 全部原生/真机门槛继续推进。

## 46. W3 续接：共享分块几何与 Windows 长图区域读取

日期：2026-10-03。将 Android 已有纯 Kotlin ImageSourceGeometry / TileGrid 迁入 reader-core
commonMain，连同十九项几何/裁剪/旋转/split/gutter 测试迁到 jvmTest。共享类型使用 reader.core
包，Android 原 reader.image 名称保留 typealias，消费同一实现。隔离守卫扫描扩大到整个
commonMain reader 目录，仍禁止 Android/Compose/应用层引用，没有放宽允许依赖名单。

### 场景、区域解码与像素生命周期

- VisibleImageTiles 使用共享 VisibleNode 的 sceneBounds/visibleRegion 反算源像素区域，按
  当前显示尺寸/相机倍率选择 power-of-two sample，再复用原 TileGrid 选择可见单元。
  512×512 解码基准格与原 seam padding 给出 gutter，目标 logicalRect 不重叠；当前不做预测预取。
- PNG/JPEG 高度超过 4096 或超过 16M 像素时，分页与连续模式使用 DesktopTiledImage。
  分页节点来自 PagedSlot.visibleContentNodes，相机缩放/平移进入同一反算；连续模式节点来自
  VerticalReaderScene。进度与图片下载仍由既有 Controller/来源协议负责，视口移动只读取原文件。
- DesktopTileDecoder 使用 JDK ImageReader 的 sourceRegion/sourceSubsampling，创建区域目标
  BufferedImage，不先建立一个全页目的 bitmap 再裁剪。实际输出尺寸与 TileSpec.decodedSize
  核对，超过 2 MiB 的 ARGB 估计 payload 在开文件前拒绝；最多两个区域读取同时执行。
  参考 [ImageReadParam 官方契约](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/ImageReadParam.html)。
- 当前 intersecting tile 才进入 composition，tile key 包含 page/sample/col/row；离开区域的块
  退出 composition，转换后的 Bitmap 沿用 Compose/Skia 管理语义。BufferedImage 转换后 flush，
  reader/文件流在 finally/use 释放；读取进度回调发现调用者取消时 abort，结果交付前再检查取消。
  区域解码失败有局部重试入口，不循环重新请求来源。
- Canvas 将 decodeRegion 的 gutter 映射到页面位置，并裁剪到 logicalRect；源尺寸与场景尺寸
  保持分开，不用 sampled 输出尺寸改写页面高度。没有新依赖、图片下载器、schema 或平台像素
  类型进入 commonMain，原 Android 解码/renderer 不切换到 JDK。

### 验证

新增五项父测试：两项共享可见区域/采样/限界、两项 PNG/JPEG 实际区域输出和超预算拒绝、
一项独立 Windows JVM 的真实长图 UI。十九项原 Android 几何测试迁入此次共享回归，不将它们
宣称为十九项新行为。自有 ReaderSource 提供 400×24000 PNG，三个彩色区段；terminal
interceptor 不访问 DNS/socket/生产站点。

真实 UI 从分页 FIT_WIDTH 顶部平移到中部，再切换连续模式滚到 source y=12180，使分块边界
落在视口中。逐行检查视口中间列的实际蓝色像素，覆盖接缝并等待新块显示，避免只以旧块消失
作为渲染成功证据。核对页索引不变、分页历史不变、视口移动没有新增来源图片请求。查看
reader-tiles-paged-top.png / paged-middle.png / continuous-middle.png；子 JVM 输出
DESKTOP_UI_OK=reader-tiles 和 NETWORK_REQUESTS=0。PNG/JPEG 单元测试读取 1200×12000
图片深处的真实颜色，输出尺寸最多 528×528（包含 gutter），而非全图输出后裁剪的结果尺寸。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 38/38；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 22/22 |
| mihon-desktop-compat API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 场景 / 生产 Window | 28/28 |
| reader-core JVM / metadata | 176/176；metadata 通过 |
| 汇总 | 363/363，0 失败、0 错误、0 跳过 |
| Android 编译 | compileDebugKotlin 通过 |

最终日志 build/kmp-windows-tiles-complete.log：1 分 35 秒，57 项任务（9 项执行、48 项
up-to-date）；未变更模块复用匹配输入结果。Android 日志 build/kmp-reader-tiles-android-compile.log：
31 秒，50 项任务（9 项执行、41 项 up-to-date）。ignored init 只隔离 app/core-backup 输出；
未运行 Android 设备/R8/iOS Native 或硬件验收，没有重启用户 preview 或提交 Git。

SRP 分离共享区域选择、JDK 解码与 Compose 绘制；DRY 迁移原网格/坐标数学而非复制；DIP 使
renderer 消费 shared TileSpec，来源仍执行原 client；KISS 只组合可见块与有界解码并行度。
2 MiB 是单块 ARGB 输出预算，不是 provider 内部内存、GC 后 resident 或应用峰值内存的证明。
PNG 深处读取可能扫描此前行，性能仍需代表图片与硬件验证；其他格式继续原 Skia 全图路径。
尚无全量格式区域解码、overview/预测预取、跨视口持久 tile cache、全局像素预算、超大 Compose
布局尺寸虚拟化、自动跨章、下载或磁盘容量清理。完整 Windows 功能/打包、S3 及 iOS 原生/真机
门槛继续推进，KMP 总目标没有缩减或标记完成。

## 47. W3 续接：Windows 可选自动跨章与持久化尺寸提示

日期：2026-10-03。阅读设置新增默认关闭的 automaticChapter，沿用现有偏好 store 保存。
共享 SourceChapterNavigation 按来源给出的章节顺序选择同 source.name、同 branch 的邻章，
不按章节号重排，不穿过其他分支；桌面手动章节按钮与自动边界导航消费同一规则。

### 导航、失败与图片身份

- 单页/双页模式在 canonical slot 边界继续翻页时跨章；RTL 仅改变物理按键方向，章节顺序
  不反转。向前从目标章首页进入，向后从末页组进入；正常手动章节按钮仍从首页进入。
- 连续模式在已恢复、已验证的实际列表边界接受新的键盘/滚轮输入；触摸残余滚动走
  NestedScrollConnection。显式处理 Desktop Scroll pointer 事件，避免实际滚轮边界遗漏。
  当前章/navigation 标识和 pending/busy 守卫阻止迟到事件切换新章节。恢复、窗口调整、
  Home/End 或单次滚动刚好抵达边界不会自行连续跨章。向后进入上章末页底部。
- 先获取目标页列表并加载分页目标页组，或连续模式的目标锚点图片，再发布新章节状态。
  这两步失败保留原章节/图片/已验证进度；连续模式其余可见图片仍按既有按需加载与进度
  验证规则处理。错误存在时滚轮不自动重试来源，用户可通过页面/章节按钮明确重试。
- 仅改变自动跨章开关不重新定位当前页组。切换章前仍 flush 原章已验证的连续视口；
  Float.MAX_VALUE 仅为向后跳转的临时滚动目标，按实际页面/列表限界，绝不写入 history。
- 实际 UI 验证发现清空图片状态也丢失已知宽图尺寸，会令返回上章按估计尺寸分组。
  既有 256 条页面索引追加带默认值的 width/height，不新增 schema/命名空间。重开章节时
  按原完整请求上下文和扩展哈希读取尺寸提示；实际加载图片的尺寸优先，旧记录在完整
  校验命中后补齐尺寸，并保留原 storedAt。尺寸提示不等于像素/ready 图片，不绕过原
  文件 SHA/大小校验；文件缺失、大小变化或读取尺寸时 I/O 失败只忽略提示。

### 验证与范围

新增 SourceChapterNavigation 两项边界/来源/分支/顺序测试、两项桌面场景/持久化提示测试，
以及一个父测试启动两个独立 Windows JVM 的实际 Compose UI。ReaderSource 自有扩展可选
第三个其他分支章节，并可注入 getPages 或目标图片错误；图片 terminal interceptor 不访问
DNS/socket。分页目标失败后核对旧章节、图片和数据库历史；RTL 回上章核对宽图后末页组
锚点；连续 PageUp/PageDown 与滚轮核对上章底部/下章顶部、错误后的重复滚轮请求数不变。
第二个较小窗口 JVM 将图片来源置为离线，核对开关/模式/章节与实际页面恢复、零图片请求。

持久化提示测试重开真实偏好 store，核对 revision/headers 隔离；同尺寸损坏文件仍可提供
尺寸提示，但 visible load 必须通过 SHA 发现损坏并重新请求。场景测试以空图片 map 复现
末页组，随后核对实际已加载尺寸覆盖过期提示。已查看 failed-image、continuous-final、
read-restored 三张截图；专项子进程输出 DESKTOP_UI_OK 和 NETWORK_REQUESTS=0。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 40/40；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 22/22 |
| mihon-desktop-compat API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 场景 / 生产 Window | 31/31 |
| reader-core JVM / metadata | 176/176；metadata 通过 |
| 汇总 | 368/368，0 失败、0 错误、0 跳过 |
| Android 编译 | compileDebugKotlin 通过 |

完整日志 build/kmp-windows-chapters-complete.log：1 分 51 秒，57 项任务（20 项执行、37 项
up-to-date）。Android 日志 build/kmp-reader-chapters-android-compile.log：26 秒，50 项任务
（11 项执行、39 项 up-to-date）。未修改隔离 init，未重启用户 preview、运行 Android 设备/
R8/iOS Native 或提交 Git；最终无遗留 WebView2 桥进程，git diff --check 通过。

SRP 分开章节选择、事务式目标准备与视口事件；DRY 复用共享章节规则与原图片索引；DIP
保留 SourceRuntime 请求边界；KISS 仅在用户继续边界输入时导航，没有轮询或后台跨章任务。
尚未取得尺寸的未读页仍使用估计几何，不宣称全章节任意宽图分组预知；连续触摸边界的真实
硬件验收、全量格式区域读取、预测预取、下载、全局像素/磁盘预算、Windows 安装包与 S3、
iOS 原生/真机门槛仍待。KMP 总目标继续保持 active。

## 48. W1 / W3 续接：Windows 整章下载、暂停恢复与离线入口

日期：2026-10-03。详情章节增加下载按钮，侧栏增加下载列表与暂停、继续、校验和离线阅读入口。
整章下载不再依赖 256 条 evictable 阅读索引；共享 SourceChapterDownload / SourceDownloadPage
在 commonMain 保存原章节、十进制 64-bit 作品/页身份、完整 SourcePage 请求、扩展哈希、
SourceImageArtifact 与尺寸，不含 JVM 路径/Compose bitmap。重复页、其他来源、错误 artifact
身份和无效尺寸拒绝构造；没有新增 Room schema、用户数据库或恢复 Entity Graph / Work。

### 请求、生命周期与离线数据

- DesktopDownloadStore 使用既有 SourcePreferences 保存 manifest，key 由 source/content/chapter
  身份取 SHA-256。排入队列先保存记录，获取页列表后保存请求快照，再逐页保存成功 artifact；
  暂停/失败保留先前进度。容量超限与磁盘失败显式报错，不发布成功下载状态。图片仍写入原
  内容寻址 store，下载引用不会因阅读索引淘汰而移除；当前不删除任何已发布图片。
- DesktopDownloads 使用 Controller 原 scope 的 child Job 与独立 Mutex 串行执行章节，同一
  章节重复请求复用活动任务。暂停取消当前任务或等待任务；退出随 Controller 取消/等待资源。
  重开应用，未完成记录展示暂停，完成记录展示已下载；不在启动时自动执行来源请求。
  “校验 / 继续”重新核对已完成文件，扩展 revision 改变则获取新页列表；下载没有 history 写入。
- prepareDownload 在下载 manifest 或原阅读索引中寻找匹配的已验证文件；否则执行原
  SourceRuntime.fetchImage。来源的 native Page index、Referer、opaque imageUrl 和 client/
  interceptor 保持原路径，网页挑战沿用现有 owner。后台传输不持有前台阅读缓存 gate，
  以免慢下载阻塞普通 reader cache.load；图片文件发布继续使用原有校验/原子发布。
- 下载列表的离线入口通过 DesktopLibrary.find 读取现行本地作品/章节 DTO，完成 manifest
  提供页列表；读图按下载引用校验大小/SHA 并解码，不调用来源详情或页列表。正常阅读也复用
  匹配的完成章节快照与尺寸，缺失/损坏下载文件保持错误，需用户联网后校验/继续修复。
  只有实际进入阅读器才按原 history 规则记录进度。下载不是“所有来源元数据都已离线”。
- 新增导航后，920×620 窗口的原侧栏固定间隔把 LazyColumn 可用高度挤空，完整筛选回归据此
  失败。将导航分组并减小组间距离，恢复真实滚动区域；窄窗口探针先滚到来源再执行原筛选
  交互，不删除窄窗口验收或减少控件覆盖。

### 验证与范围

新增两项 shared manifest 协议测试，核对完整请求/极限 Long round-trip 与无效数据拒绝。
五项桌面下载测试使用真实偏好 store / 图片文件，覆盖部分失败后的跨进程式重开/继续、活动及
等待 Job 取消、重复排队、revision 变化、容量失败、后台传输与前台缓存隔离。257 页测试使用
自有同一 1×1 PNG blob、257 个不同页身份与请求，先填满并淘汰阅读索引，再保存/重开独立下载
引用；完整校验没有来源 fetch，同尺寸损坏后必须重新 fetch 修复。它不代表 257 张代表图性能验收。

新增一个父测试启动两个独立 Windows JVM，通过实际 SDK 扩展和 Compose UI 操作。五页下载
在第三页失败，核对前两页已保存、history 为空；继续时延迟第三页，再从 UI 暂停，核对原
Rx/HTTP 调用结束；再次继续只请求剩余三页。整个下载过程总计 7 次图片调用、1 次页列表调用，
仍无 history。下载完成后把页列表/图片来源都置为离线，从下载列表进入并翻到第五页，核对
两种来源调用数不变、此时才产生页索引 4 的 history。第二个 920×620 JVM 直接从本地下载
入口显示末页，页列表及图片来源调用均为零。terminal interceptor 不访问 DNS/socket。

已查看 downloads-write-paused.png、complete.png 和 downloads-read-offline-last-page.png；
独立 JVM 输出 DESKTOP_UI_OK=downloads-write/read 与 NETWORK_REQUESTS=0。下载 UI 仅经过
自有来源验证，不代表真实站点、所有文件格式、Cloudflare 或全量扩展验收。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 42/42；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 22/22 |
| mihon-desktop-compat API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 下载 / 场景 / 生产 Window | 37/37 |
| reader-core JVM / metadata | 176/176；metadata 通过 |
| 汇总 | 376/376，0 失败、0 错误、0 跳过 |
| Android 编译 | compileDebugKotlin 通过 |

最终完整日志 build/kmp-windows-downloads-complete.log：1 分 41 秒，57 项任务（7 项执行、
50 项 up-to-date）；未变更模块复用匹配输入结果。Android 日志 build/kmp-reader-downloads-android-compile.log：
31 秒，50 项任务（11 项执行、39 项 up-to-date）。ignored init 沿用原 app/core-backup 输出
隔离，没有改变生产配置。未运行 Android 设备/R8/iOS Native 或硬件验收，未重启用户 preview
或提交 Git；最终无 WebView2 桥进程残留，git diff --check 通过。

SRP 分开共享下载数据、持久化、任务与 UI；DRY 复用图片完整性校验、原 SDK client/store 与
本地作品投影；DIP 保留 SourceRuntime 边界；KISS 沿用 Controller 任务所有权、逐页 manifest
与现有偏好原子快照，不引入另一套 HTTP 下载器或服务数据库。

当前整份 manifest 索引仍受偏好 store 的 4 MiB snapshot 上限，超限明确失败；不等于无限容量
下载系统。批量选择/优先级、导出/下载删除、磁盘容量/文件保留管理、预测预取与完整图片格式、
备份/同步、其他源运行时、扩展/APK 转换、Windows 安装包与 S3，以及 iOS Mobile JVM/JNI/
WKWebView/SwiftUI 和原生/真机门槛继续推进，KMP 总目标保持 active。

## 49. W1 / W3 续接：下载分块清单、原子发布与旧索引迁移

日期：2026-10-03。§48 的整份下载索引受 4 MiB 偏好 snapshot 上限，无法随章节/请求数据总量
扩展。本节不提高底层限制，改为共享 SourceDownloadManifest / SourceDownloadChunk 格式：
清单保存作品/章节/revision/标题/页数与块 hash；64 页一块，保留原 SourceDownloadPage 数据。
格式版本、hash 形状、页数/块数和块大小由 commonMain 构造约束；Long 仍用十进制字符串。

### 持久化与恢复顺序

- DesktopDownloadStore 改为消费 SourcePreferenceStore，图片字节仍在原 image store，不新增
  DB/schema。数据块按完整 JSON 内容的 SHA-256 寻址，写入独立 namespace；成功块可复用。
  每章 namespace 保存一个清单，发现索引按章节身份 hash 的首字符拆成 16 份，只保存 key。
  页面请求集合不再全部塞进一个偏好文件，实际底层每份 snapshot 仍保留 4 MiB 约束。
- 先提交所有引用块，再原子保存章节清单；首次记录最后提交发现索引。清单仍沿用偏好 store
  的临时文件、force 与同目录 ATOMIC_MOVE 发布规则。UI records 只在全部必要步骤成功后更新。
  改动只生成新块并改发布点，不修改清单仍引用的旧块；块写入或清单失败不会改写上次成功
  的章节文件，发现步骤失败的首次记录不进入成功状态。
- committedChunks / registered 仅在对应 edit 返回成功或启动读取验证通过后登记。既有偏好
  契约允许磁盘失败仍更新内存，因此不能仅凭 snapshot 含有同值跳过重试；章节清单每次
  save 都重新写入，失败阶段重试仍必须再次调用 edit，防止指向未提交的数据。
- 启动先读取新版发现索引/清单/块，核对块 JSON 的 hash、每块预期页数与完整章节的身份/
  source/唯一页约束。单章坏块报错并隔离，健康章节仍可读；已发现新版身份不会悄悄回退到
  原旧进度。发现分片读取失败与退休的旧 namespace 读取失败也保留错误和原数据。
- 再迁移未被新版发现的 desktop_downloads 平面记录，沿用相同发布顺序。旧记录与文件不
  删除或覆盖；迁移失败保留原内存记录/错误，下一次真正重开 store 可以继续迁移。
  当前下载/暂停/继续/离线入口和 history 语义不变，像素读取仍逐文件校验 SHA/大小。

### 验证与范围

新增两项共享块/清单测试，核对 64 页边界、65 页两块、空清单、版本/hash/数量拒绝与完整
请求/Long round-trip；五项 DesktopDownloadStore 测试使用真实 FileSourcePreferenceStore，
而非提高 maximumBytes 绕过限制。实际写入五章请求数据，总量与最后一章均超过 4 MiB；
最后一章含 448 页，来源请求使用自有长 opaque URL，各章 payload 不同。逐文件核对仍低于
原上限，重开后章节/完整页数据一致；同数据尝试旧平面 snapshot 明确被原容量检查拒绝。
测试数据仅为 manifest/合成 artifact 身份，不把长 URL 视作真实来源或下载图片性能验收。

旧版部分下载在迁移后保留原快照，更新新版完整记录后重开不复活旧进度。迁移块写入失败
保留旧两页并可在真实 store 重开后重试。块、章节清单和发现索引三阶段故障测试按偏好
契约模拟“内存已更新、磁盘未提交”，核对记录不错误发布、既有清单的实际文件字节不变、
首次发现文件未产生，且同一实例重试确实再次 edit。重开真实偏好目录验证最后提交可读。
坏块测试仅增加合法 JSON 的尾部空白，解析仍可成功但 SHA 不符，必须拒绝该章；保留旧平面
记录也不能回退，另一章仍可读。随后破坏退休的旧偏好文档，健康新版章节继续可恢复。

原下载 UI 父测试继续执行两个独立 Windows JVM，核对暂停/取消/仅补剩余页面、零下载进度
写入、页列表与图片来源同时离线后的真实末页显示、重启恢复及零额外来源调用；没有新增
生产请求。既有 reader/source/API/WebView2 回归继续保留。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 44/44；metadata 通过 |
| source-host JVM / 默认发行 | 72/72；installDist 通过 |
| desktop-runtime JVM | 22/22 |
| mihon-desktop-compat API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 下载 / 场景 / 生产 Window | 42/42 |
| reader-core JVM / metadata | 176/176；metadata 通过 |
| 汇总 | 383/383，0 失败、0 错误、0 跳过 |
| Android 编译 | compileDebugKotlin 通过 |

最终完整日志 build/kmp-windows-download-manifests-complete.log：1 分 56 秒，57 项任务
（18 项执行、39 项 up-to-date）。Android 日志 build/kmp-reader-download-manifests-android-compile.log：
25 秒，50 项任务（11 项执行、39 项 up-to-date）；原 ignored init 的 app/core-backup 输出
隔离配置未变更。下载子 JVM 再次输出 DESKTOP_UI_OK 与 NETWORK_REQUESTS=0，最终无
WebView2 桥进程残留，git diff --check 通过；未重启用户 preview、提交 Git 或运行 Android
设备/R8/iOS Native/真机验收。

SRP 分开共享格式与平台原子存储；DRY 复用原偏好/文件锁/发布实现、SourceDownloadPage 与
SHA helper；DIP 通过 SourcePreferenceStore 发布；KISS 固定 64 页块和每章单一清单，没有
引入新事务数据库或平台像素类型。旧清单引用/迁移记录和未发布的新块暂不清理，单文件仍有
容量限制，全部元数据启动时驻留；没有宣称无限容量、内存预算、断电 fsync 目录耐久性或
下载/磁盘管理全面完成。批量/导出/删除、旧块回收与容量策略、Windows 打包/S3，以及
iOS 原生/真机和全量 KMP 门槛继续推进。

## 50. W1 / W3 续接：下载元数据占用预览与确认回收

日期：2026-10-03。§49 每次修改生成新的内容寻址块，旧块已无引用但仍占磁盘/偏好 cache。
下载页新增占用预览与确认回收，列出记录占用、可回收旧块数量/字节；取消不执行回收。
用户明确确认后仅清理已批准且当时仍未引用的元数据块，图片/章节/旧平面记录和其他偏好保留。
本节没有自动清理、图片删除或全盘容量预算，测试动作仅针对自有临时目录。

### 文件身份、引用与任务

- FileSourcePreferenceStore 增加实际磁盘 snapshot inventory/read/remove API，仍由原目录
  owner 和 monitor 管理。只枚举 canonical SHA 文件名的 JSON，检查普通 NOFOLLOW 文件、
  原大小限制、版本/namespace 与文件名对应关系；token 包含 namespace、实际文件字节数和
  SHA。移除前核对同一文件身份，变化拒绝移除；路径从 namespace hash 构造在原目录内。
  未知/损坏文档停止扫描，不把不可识别数据当垃圾。
- 成功删除 snapshot 后移除相应缓存实例并退休旧视图，避免借用者用旧值重建已移除文件；
  新 open 可创建空的新视图。下载 store 也清除对应 committedChunks hash，若用户继续/
  重用某旧版本，save 必须重新写出所需块，不能因旧成功登记跳过。
- DesktopDownloadCleanup 遍历磁盘上所有章节根，包括首次清单已写但发现索引未成功的根。
  清单身份、块 hash/数量、页 source/唯一性/geometry 继续验证；根缺块、数据损坏或图不完整
  阻止回收。只有不被任何根引用的 v2 页面块可进入预览；清单/索引/其他设置不作为候选。
- DesktopDownloadStore 预览及确认与 save 共用 monitor。确认重新扫描引用，只取新合格
  token 与批准 token 的交集；预览后被引用/改变的旧块跳过，新产生未引用块也不加入本次
  批准集合。每次 I/O 扫描/删除前检查 Controller owner 取消，文件 token 删除前再验证。
  当前保证基于同一 Session/目录 owner 的写入纪律，不宣称恶意外部文件替换的无竞态证明。
- Controller 使用原 action/scope，窗口关闭取消；保存独立预览对象，确认必须匹配当前对象。
  UI 模态对话框展示具体范围，确认后报告实际移除/跳过数量，取消仅撤销预览。
  对话框有自己的 Compose root，截图探针改为捕获对话框节点，保留实际 modal UI 验证。

### 验证与范围

新增两项 source-host snapshot 测试：真实磁盘 inventory/read/remove、新旧视图生命周期及
重开；同尺寸文件内容变化拒绝删除，损坏实际偏好文档停止 inventory 且保留字节。
新增三项下载回收测试：旧块清理后保存旧版本必须重建且重开可读、确认时保护重新被引用
块且不删除未批准的新旧块、未索引的真实清单保护其块，损坏图阻止删除任何批准候选。

既有两个 Windows 子 JVM 的下载 UI 探针增加预览/取消/确认：取消前后候选 token 一致，
确认后旧候选为零、已下载状态/history 不变。随后同时将页列表/图片来源置为离线，仍显示
末页，第二个较小窗口 JVM 重开同数据仍离线显示，来源调用数不增加。自有 fixture 的确认
对话框显示 26 KB 元数据，5 条旧记录约 20 KB 可回收；已查看 downloads-write-cleanup-preview.png。
这些是实际文件字节数而非 NTFS allocated size，不是代表库规模或内存/硬件性能验收。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 44/44；metadata 通过 |
| source-host JVM / 默认发行 | 74/74；installDist 通过 |
| desktop-runtime JVM | 22/22 |
| mihon-desktop-compat API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 下载 / 场景 / 生产 Window | 45/45 |
| reader-core JVM / metadata | 176/176；metadata 通过 |
| 汇总 | 388/388，0 失败、0 错误、0 跳过 |
| Android 编译 | compileDebugKotlin 通过（up-to-date） |

首次完整回归中 Cookie 测试的原 10 秒目录释放检查遇到 AccessDeniedException；
未修改等待期限或移除断言。保持代码不变的单项复测通过，日志
build/kmp-windows-download-cleanup-cookie-recheck.log：17 秒，13 项任务
（3 项执行、10 项 up-to-date）。随后完整回归通过，最终日志
build/kmp-windows-download-cleanup-complete.log：1 分 1 秒，57 项任务
（7 项执行、50 项 up-to-date）；XML 核对上述 388 项全部通过。
初次目录释放失败原因尚未确认，不能据此宣布原生浏览器生命周期在所有硬件上稳定。

Android 日志 build/kmp-reader-download-cleanup-android-compile.log：2 秒，50 项任务
（7 项执行、43 项 up-to-date），复用原 ignored 输出隔离 init。最终 WebView2 桥进程
残留为 0，git diff --check 通过。未重启用户 preview、操作真实用户下载目录或提交 Git；
没有运行 Android 设备/R8/iOS Native/真机验收。

SRP 分开文件身份、引用分析和确认 UI；DRY 复用原偏好解码/大小/UTF-8/hash 与共享下载
格式；DIP 仍通过 owner 存储/Controller 任务，不新增服务数据库；KISS 仅一次预览后确认，
没有后台自动删除。全部元数据启动驻留、图片预算与已下载内容管理、批量/导出/删除、
Windows 打包/S3，以及 iOS 原生/真机和全量 KMP 门槛仍待。

## 51. W3 续接：文件头尺寸与完整页面输出像素预算

日期：2026-10-03。§46 已让 PNG/JPEG 长页按可见区域解码，但准备尺寸仍读取整份编码文件，
其他格式及普通页面仍在 Compose 转换时分配原始尺寸的 Bitmap。本轮分开原始场景几何与
显示输出尺寸，复用既有 Skia/JDK provider，没有新增解码依赖或删除图片文件。

### 共享几何与桌面解码

- reader-core commonMain 新增 ImageDecodeSize，仅消费共享 IntSize 和像素/边长限制。
  普通小页原样返回；超预算按比例缩小，并处理极细页面的一像素下限，使用 Long 计算面积。
  单边和像素上限分别约束输出，不改变 source 坐标、页组、历史位置或持久化原始尺寸。
- DesktopImageDecoder 的 PNG/JPEG header 使用 FileImageInputStream 与 ImageReader 的
  getWidth/getHeight，dispose reader 并关闭文件。其他既有格式继续通过 Skia 获取尺寸。
  文件头可提供尺寸，不能证明像素 payload 完整/可解码；原 artifact 大小/SHA 校验仍保留，
  可见解码失败仍显示失败提示。没有把尺寸提示升级成像素成功证明。
- 完整页面输出使用 ImageDecodeSize，限制 4194304 个 N32 像素（16 MiB）及单边 16384，
  allocPixels 保留原 color space、使用 premultiplied alpha；写入完成后 setImmutable，避免
  后续消费者按可变像素处理。Skia scalePixels 的 cache=false
  避免请求本地像素缓存，编码 Image/Pixmap 在输出完成后释放。
- Compose 直接 asComposeImageBitmap 接管成功输出，不再经过 toComposeImageBitmap 的
  另一份像素复制。已核对本机 Compose 1.12.0 的实际 bytecode：前者包装同一 Bitmap，
  后者调用 toBitmap。调用者取消/失败时关闭未交付 Bitmap；交付后的可见 Bitmap 沿用
  Compose/Skia 生命周期，尚未宣称全部 native/GPU 资源即时释放或全局 residency 受限。
- 区域解码和完整页面共用两个并发名额；PNG/JPEG 的分块触发阈值由原 16M 降至
  4194304 像素，高度超过 4096 的规则保留。这些格式超预算后仍可按局部采样保留细节。
  大 WebP 等暂未支持区域读取的格式使用降采样输出，放大细节受输出分辨率限制，原文件保留。

平台调用参考 [JetBrains Image API](https://jetbrains.github.io/skiko/skiko/org.jetbrains.skia/-image/index.html)
和 [Bitmap API](https://jetbrains.github.io/skiko/skiko/org.jetbrains.skia/-bitmap/index.html)，实际使用
版本由本机 Skiko 0.150.1 bytecode/编译验证；没有仅按最新文档猜测库签名。

### 验证与范围

三项共享测试核对普通页保持像素、比例与像素限制、Int.MAX_VALUE/极细页/一像素预算的
面积与单边边界、非法参数。五项桌面测试使用实际 PNG/JPEG/Skia WebP 与 N32 Bitmap，
核对尺寸/实际 computeByteSize/颜色、透明度、坏图失败和等待解码取消不丢并发名额。
只有 33 字节 PNG 文件头仍能返回 128×24000 尺寸，证明尺寸路径不要求读取像素 payload；
这不代表该截断文件可显示。文件流关闭后能移动测试文件。

新增父 UI 测试启动两个独立 Windows JVM。自有 fixture 经原 SDK imageRequest/client 返回
3000×2000 红蓝 WebP，保留原 headers/原生 index/opaque URL；第一个 JVM 验证真实像素、
放大不推进历史、连续阅读和宽图单页。第二个较小窗口 JVM 无可用图片来源，仍恢复原始
几何/双页模式中的宽图单页与红蓝像素，图片来源调用为零。已查看
reader-bounded-write-wide-solo.png。既有 PNG/JPEG 长图/接缝、相机、分页/连续、下载离线与
WebView2 检查继续保留；测试图片是自有样本，不是全格式/代表站点/硬件峰值内存验收。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 44/44；metadata 通过 |
| source-host JVM / 默认发行 | 74/74；installDist 通过 |
| desktop-runtime JVM | 22/22 |
| mihon-desktop-compat API / WebView2 | 27/27 |
| desktop-app UI / 缓存 / 下载 / 场景 / 生产 Window | 51/51 |
| reader-core JVM / metadata | 179/179；metadata 通过 |
| 汇总 | 397/397，0 失败、0 错误、0 跳过 |
| Android 编译 | compileDebugKotlin 通过，实际重新编译 |

完整日志 build/kmp-windows-image-decode-complete.log：2 分 40 秒，57 项任务
（13 项执行、44 项 up-to-date）。最后补上输出 setImmutable 后，再完整执行 desktop-app
51 项测试，日志 build/kmp-windows-image-decode-immutable-check.log：2 分 25 秒，37 项任务
（10 项执行、27 项 up-to-date）；全部通过，其他模块输入未变。最终 XML 核对上表 397 项。
两个新增子 JVM 均输出 DESKTOP_UI_OK 与 NETWORK_REQUESTS=0。

Android 日志 build/kmp-reader-image-decode-android-compile.log：35 秒，50 项任务
（9 项执行、41 项 up-to-date），复用原 ignored 输出隔离 init。最终 WebView2 桥进程残留
为 0，git diff --check 通过；没有重启用户 preview、操作真实用户下载目录、提交 Git 或
执行 Android 设备/R8/iOS Native/真机验收。

SRP 分开输出几何、平台解码与 Compose 所有权；DRY 将分块和整页并发限制统一，下载/阅读
复用同一 header；DIP 的共享几何不依赖 Compose/Skia/JDK，KISS 保留原文件/错误/请求路径。
预算只约束单次 destination Bitmap；Skia codec 中间缓冲、编码输入驻留、所有可见节点/封面/
GPU 的总预算、预测预取和其他格式区域读取仍待。磁盘容量策略、长章元数据驻留、下载批量/
导出/删除、Windows 打包/S3 与 iOS 原生/真机和全量 KMP 门槛仍待。

## 52. W3 续接：Windows 独立应用、本地 ZIP/MSI 与随包运行时验证

日期：2026-10-03。此前 desktop-app 的开发启动依赖本机 API 目录、JDK 和显式浏览器桥路径。
本轮将当前已验证的 Windows 实现生成本地预览应用，保留 Android/source-host 的默认发行范围；
没有新增依赖、升级工具链版本或改变 Room v84。此轮产物不等于公开发行或全部 KMP 完成。

### 单一运行目录与输入校验

- 可选 `-PwithWindowsDistribution` 打开分发配置；未提供标志的 native package 任务明确失败，
  避免生成缺少外部运行时的应用。新增 `desktop-app/package-windows.ps1` 串联验证与打包。
- `windows-runtime-pins.properties` 锁定实际选入的完整 29 个外部 JAR。新校验任务检查成员集合、
  重复文件名及 SHA-256，沿用原五个关键产物的身份检查。三份独立自有目录分别修改非关键 JAR、
  缺少 dec.jar、添加 unexpected.jar，实际执行 Gradle 均被拒绝；原 API 目录保持不变。
- 复用原 AndroidCompat → server → android-jar → 其余文件顺序，将外部 JAR 按三位数字编号，
  复制时不改变字节。Compose/jpackage 会重新按文件名排序，编号确保 Android 桩类不会遮蔽兼容实现。
  staging 目录按 pin 文件 SHA 分隔；其他既有 Kotlin/Compose/Room 依赖沿原 runtimeClasspath 进入应用。
- 同一应用目录携带 JVM、Skiko/SQLite、桥 EXE 与三个 WebView2 DLL。资源中保留输入文件名/大小/SHA、
  29 项 pin、本地预览范围说明及根 LICENSE 副本；JVM legal 文件保留。系统仍需 Edge WebView2 Runtime。
  资源定位优先使用显式桥路径，否则读取 Compose 的应用资源路径；错误显式覆盖不会悄悄回退。
- 保留随包 `runtime/bin/java.exe`，用于独立支持检查和生产窗口探针。Compose 1.12.0 的
  `getStripNativeCommands$compose` 适配已核对实际 bytecode；将来升级插件需重新核对这个内部属性。
  包装过程关闭 configuration cache；普通回归仍使用缓存。

基于 [Compose Multiplatform 官方原生发行说明](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)
配置应用/JVM/资源和 MSI；具体 1.12.0 的任务属性以本机插件字节码和实际构建为依据。

### 安装目录与同一份应用产物

MSI 使用 limited/per-user 权限及固定 upgrade UUID，默认程序目录为
`%LOCALAPPDATA%/Kototoro-App`，数据继续位于 `%LOCALAPPDATA%/Kototoro`，目录选择关闭。
最初默认同名目录会让卸载规则覆盖数据根，检查后修正。曾尝试 `Programs/Kototoro`：
单反斜杠被参数文件解析吞掉；修正转义后又因 jpackage 的中间 Programs 目录缺少 RemoveFile
规则触发 WiX ICE64。最终采用相邻的专用程序目录，没有禁用 ICE 校验或定制卸载删除逻辑。

MSI 的 `appImage` 直接引用经过集成测试的应用目录，ZIP 也使用同一目录；两者依赖
windowsDistributionTest。若分别按输入 JAR 重建，Compose 的 Skiko 解包/重打包会产生不同的
JAR 字节/名称，逐文件检查曾正确拒绝该路径。集成测试将整个应用目录声明为输入，避免只根据
路径字符串复用旧测试结果。`inspect-windows-msi.ps1` 仅使用项目内 WiX dark 反编译/提取，不执行 MSI。

MSI 内容检查拒绝程序/数据重叠、非 HKCU 注册表项、升级标识变化、越界文件、重复/额外/遗漏文件，
逐文件比较应用 payload 的 SHA-256。JDK25 将构建用 `app/.jpackage.xml` 替换为安装用 `app/.package`；
仅接受这一个具体元数据转换，并验证包名标记为 Kototoro，445 个应用文件仍逐一完全一致。
ZIP 保留完整 446 个目录文件，包括构建元数据，另用实际 ZIP 流逐文件比对 SHA 与成员数量。

### 验证与范围

新增三项桥定位测试、一项隐式用户数据目录拒绝测试，以及一项可选原生分发集成测试。
`--check-runtime <report>` 必须同时提供 `--data-dir`：使用独立目录启动并关闭真实 Session，
检查 Room v84、AndroidCompat 类所有者、Skia 实际 PNG 像素及 WebView2 本地 HTML/JS，成功后写报告。
可以显式导入自有 fixture，但检查不会请求生产来源网站。

分发集成测试将完整应用复制到含中文和空格的临时路径，从独立工作目录启动原生 Kototoro.exe；
子进程移除 JAVA_HOME/CLASSPATH/JVM 注入选项，PATH 仅含 System32。报告确认 Java.home 和浏览器
均来自移动后的应用，schema=84、image/browser=ok、来源数=1，Android 类来自编号后的 AndroidCompat。
随后用随包 java.exe 和应用自身 classpath 加载自有探针，创建生产 Window，验证启动中关闭和
导入后关闭；保留原十秒有界文件句柄释放检查。未启动/重启用户现有 preview。

```powershell
./desktop-app/package-windows.ps1 -CompatibilityDirectory "D:/tmp/kt-compat36" -JavaVersion 25
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 44/44；metadata 通过 |
| source-host JVM / 默认发行 | 74/74；installDist 通过 |
| desktop-runtime JVM | 22/22 |
| mihon-desktop-compat API / WebView2 | 30/30 |
| desktop-app UI / 缓存 / 下载 / 场景 / 生产 Window | 52/52 |
| reader-core JVM / metadata | 179/179；metadata 通过 |
| Windows 移动目录 / EXE / 随包 JVM / 生产 Window | 1/1 |
| 汇总 | 402/402，0 失败、0 错误、0 跳过 |
| MSI 内容 / ZIP 内容 | 445 个应用文件 + 一个校验的包名标记 / 446 个文件，SHA 与成员检查通过 |
| Android 编译 | compileDebugKotlin 通过，up-to-date |

普通完整回归日志 `build/kmp-windows-distribution-complete.log`：2 分 3 秒，57 项任务
（9 项执行、48 项 up-to-date），configuration cache 存储成功。Android 日志
`build/kmp-reader-windows-distribution-android-compile.log`：2 秒，50 项任务
（7 项执行、43 项 up-to-date），沿用 ignored 的输出隔离 init。
打包最终记录在 `build/kmp-windows-distribution-final-package.log`：1 分 1 秒，50 项任务
（13 项执行、37 项 up-to-date），分发集成测试本轮实际重新执行；运行报告和 MSI 清单位于
`desktop-app/build/reports/windows-distribution`、`desktop-app/build/reports/windows-msi`。
最终产物为 `desktop-app/build/compose/binaries/main` 下的 Kototoro.exe、portable ZIP 和 MSI；
均是 ignored 的本地构建输出，没有提交 Git。最终 ZIP 152128114 字节，MSI 153198764 字节；
ZIP SHA/成员复核日志为 `build/kmp-windows-zip-contents-check.log`。桥进程残留 0，git diff --check 通过。

输入 pin 和 LICENSE 副本只证明输入身份及保留项目声明，不替代组合产物的 S3 来源/许可/notice
审计。实际 MSI 安装、快捷方式、注册表、升级与卸载尚未执行，需按用户提供的 AGENTS 高风险
操作要求取得明确授权；dark 内容验证不证明安装 UI 或升级行为。没有进行签名、公开发布、
Android 设备/R8、iOS Native 或真机验收。

SRP 分开运行时支持检查、打包与内容检查；DRY 的 ZIP/MSI 共用已测试应用目录，桥和 API
复用现有实现；KISS 采用独立程序目录避免 WiX 自定义卸载；DIP 的共享模块不依赖桌面包装。
下载批量/导出/删除、磁盘总量与图片驻留预算、预取/更多格式、备份/同步、完整扩展/APK 与源生态、
Windows 实际安装/升级/卸载和 S3/签名，以及 iOS Mobile JVM/JNI/WKWebView/SwiftUI/原生真机门槛仍待。

## 53. W1 / W3 续接：Windows 收藏与历史备份、私有预览及事务合并恢复

日期：2026-10-03。§13 已共享备份模型，但 Windows 没有备份入口。本轮 desktop-runtime 直接
依赖既有 core-backup，接入当前库数据的导出与恢复。没有新依赖版本、schema 变更、平行数据库或
实体图谱；完整备份/外部格式/同步和 iOS 原生仍保留原目标。

### 格式与文件所有权

- DesktopLibraryBackup 导出 index/categories/favourites/history/sources/projections 六个原生 ZIP 节，
  共用 Android 的 BackupIndex、CategoryBackup、FavouriteBackup、HistoryBackup、SourceBackup、
  ContentBackup。索引使用现有 generation 3/schema 4；字段名和 Long 编号保持原样。
  各 DTO 逐条写 JSON 数组，使用同一数据库事务获取一致视图；写完 ZIP 后才发布最终文件。
  已有文件拒绝覆盖，失败/取消关闭流并清理自己创建的临时文件。
- 导出压缩文件上限 512 MiB、全部 payload 上限 1 GiB；读取采用相同上限，避免生成本端不能打开
  的备份。大小限制包含压缩 ZIP 元数据，解压扫描按实际读取字节计数，不信任 entry 声明大小。
  这些上限不证明全局内存受控：单个 JSON 记录、分类映射与作品锚点集合仍可能占用较多内存。
- DesktopBackupArchive 在预览时复制到唯一的私有临时文件。完整 ZipInputStream 扫描验证全部
  payload/CRC（含未支持节），拒绝路径/重复/无效节名、过多 entry，并核对 central directory 的
  节名集合。索引须恰好一条、版本受支持；支持的数据节逐条解码计数，其他节列入预览说明。
  原选中文件在预览后变化不会影响确认数据；取消/替换/恢复结束/退出关闭 ZIP 并回收自有副本。
  dispatcher 返回期间取消也关闭已创建未交付的预览。

### 合并与旧版兼容

恢复只做 MERGE，在一个 Room immediateTransaction 内执行，保留外键约束；格式、关联或身份
错误及取消会结束事务，避免部分分类/收藏/内容已经落盘。分类按现有同名记录映射；新分类使用
本地分配的编号，收藏统一使用映射后的编号，不改写既有分类。避免极大的外部分类编号把本地
SQLite 自增序列推到 Int 范围之外，测试覆盖 INT_MAX 分类恢复后新增收藏并再次导出/恢复。

内容按现行 source + URL/public URL 检查 Long 身份冲突；标签检查已有身份及重复编号，并保留
已有 pin。Android wire 没有 description/source_data/chapters，恢复已有内容时保留这些本地详情，
新作品需安装对应扩展并刷新详情。收藏以 updatedAt 合并；历史新增 findIncludingDeleted 查询，
比较时包含 tombstone，较旧备份不能复活更新的本地删除记录；写入复用 upsertSync。
共享 DAO 仅新增查询，没有新增表、改变 schema/version/identity 或 Android 现有 find 的语义。

旧 work_favourites/work_history 用共享 DTO 投影到 projections 中的 anchor_manga_id，允许
0、负数和完整 Long。活动记录缺少锚点拒绝恢复；无锚点的已删除旧记录可跳过。
entity graph 节不会恢复到已移除的实体体系。其他节在预览中明确标示未恢复，当前也不做 REPLACE、
自动导入插件/授权或后台同步；原 ZIP 文件保持不变。

### 界面与验收

侧栏新增“备份与恢复”，提供导出、选择文件、文件名/中文时间/记录数量预览、取消及“确认合并恢复”。
Controller 复用原有 gate、scope 和退出路径；确认须是当前预览实例，旧预览不能提交。
未安装扩展时收藏/历史仍能通过现有本地库显示；这不证明缺少扩展时可以请求详情或在线阅读。
完整用户备份迁移、Mihon/Aniyomi .tachibk 导入和 WebDAV 继续推进。

十一项数据库/ZIP 测试覆盖原生导出后读取/重开、手写 Android 字段格式且乱序的 ZIP、分类碰撞
重映射、本地 opaque/章节保留、较新的历史删除记录、后期内容冲突与进度/内嵌编号/重复标签错误
整次回滚、重复节/缺索引/未来版本/坏 JSON/路径拒绝、CRC 损坏、预览后文件变化、关闭预览拒绝
再次恢复、已有导出文件保留、流式写入预算，以及 0/负数旧锚点映射。只使用自有临时数据。

新增父 UI 测试启动两个独立 Windows JVM：先从自有数据库导出，真实界面验证预览/取消不写库、
确认合并、导出后再预览；第二个 JVM 在没有扩展的情况下显示同一收藏/历史，核对完整 Long
chapter、页码及像素 scroll。退出前留一个未确认预览，关闭后重开数据库并验证该预览已不可使用。
两个 JVM 输出 DESKTOP_UI_OK 和 NETWORK_REQUESTS=0；已查看 backups-write-preview.png。

完整回归初次发现 filters-narrow 的来源切换测试直接点击已被 LazyColumn 回收的不可见项；
新增侧栏入口降低了 620 像素高窗口的列表空间。保留现有窗口尺寸/断言和实际点击，改为在切换前
滚动到对应来源。普通与窄窗筛选回归均通过，没有禁用测试或移除 source isolation 检查。

原生 EXE 的 --check-runtime 新增本地备份导出/解析检查及 backup=ok 报告；检查不执行恢复，
只回收自己成功创建的诊断 ZIP。移动目录的分发测试断言该字段，验证随包 core-backup 与 serializer
可以实际工作，仍保留随包 JVM/Skia/WebView2/生产 Window 和 MSI 逐文件校验。

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata :core-backup:jvmTest :core-backup:compileCommonMainKotlinMetadata :core-db:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin :app:testDebugUnitTest --tests "org.skepsun.kototoro.backups.data.BackupIndexCompatTest" --tests "org.skepsun.kototoro.backups.data.KotatsuBackupPayloadCompatTest" --tests "org.skepsun.kototoro.backups.domain.BackupPayloadGuardTest" --tests "org.skepsun.kototoro.backups.domain.BackupRestoreFormatTest"
./desktop-app/package-windows.ps1 -CompatibilityDirectory "D:/tmp/kt-compat36" -JavaVersion 25
```

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 44/44；metadata 通过 |
| source-host JVM / 默认发行 | 74/74；installDist 通过 |
| desktop-runtime JVM | 33/33 |
| mihon-desktop-compat API / WebView2 | 30/30 |
| desktop-app UI / 缓存 / 下载 / 场景 / 生产 Window | 53/53 |
| reader-core JVM / metadata | 179/179；metadata 通过 |
| core-backup JVM / metadata、core-db metadata | 13/13；两模块 metadata 通过 |
| 普通 Windows 回归汇总 | 426/426，0 失败、0 错误、0 跳过 |
| Android 编译 / 指定兼容单元测试 | compileDebugKotlin 实际重新编译；4 类 14/14，0 跳过 |

完整日志 `build/kmp-windows-library-backup-complete.log`：2 分 8 秒，88 项任务
（14 项执行、74 项 up-to-date），configuration cache 复用。Android 日志
`build/kmp-reader-windows-library-backup-android.log`：2 分 27 秒，111 项任务
（27 项执行、84 项 up-to-date），沿用 ignored 的输出隔离 init；不是 Android 设备实际恢复测试。
分发日志 `build/kmp-windows-library-backup-package.log`：1 分 1 秒，53 项任务
（15 项执行、38 项 up-to-date），移动目录的原生分发测试 1/1，合计 Windows/共享测试 427/427。
MSI 验证 448 个应用文件及一个包名标记；ZIP 449 个文件逐一通过 SHA/成员检查，记录在
`build/kmp-windows-library-backup-zip-check.log`。ZIP 153070696 字节，MSI 154038516 字节；
桥进程残留 0。本轮只更新本地预览包，不执行 MSI 安装。

SRP 将 ZIP 所有权、数据库合并和界面确认分开；DRY 复用共享 wire 模型及原 DAO/store/Controller；
KISS 只提供可审查的显式合并、不做清空模式；DIP 运行时依赖共享备份契约，不依赖 Android UI。
书签/统计/追踪/设置与授权/扩展和图片备份、外部格式导入及 WebDAV、数据库大规模恢复驻留预算、
下载批量/删除/导出与磁盘/图像容量、完整平台和源生态、Windows 实际安装/升级/卸载与 S3/签名、
以及 iOS Mobile JVM/JNI/WKWebView/SwiftUI/原生真机门槛仍待，全量 KMP 未完成。

## 54. W1 / W3 续接：Windows 书签与阅读统计备份、完整主键及幂等合并

日期：2026-10-03。延续 §53，图书馆备份新增 bookmarks/statistics 两个原生节，直接复用
BookmarkBackup、StatisticBackup 与现行 BookmarksDao/StatsDao；索引仍是 generation 3/schema 4，
没有依赖版本、数据库 schema、表或状态所有权变更。Room 的 core/db/entity 目录继续承担现行表
映射；移除的是 Entity Graph / Work 业务体系，共享备份模型中误称旧图谱仍为状态所有者的注释已修正。

### 导出、预览及恢复规则

原生 ZIP 共八节：index/categories/favourites/history/sources/bookmarks/statistics/projections。
导出书签时保留 Android 的外置 tags；只含书签或统计、没有活动收藏或历史的作品也加入 projections，
避免恢复到空库时遗失其外键目标。继续复用同一数据库快照事务、流式 ZIP、文件预算与私有预览。
预览统计实际书签条数，不把分页产生的作品包装条目误报为书签数；支持节须在预览时完成解码。

- 书签按复合主键 manga_id/page_id 匹配，使用新增的精确 DAO 查询，不借用原 pageId-only 查询。
  保留 createdAt 较新的本地书签；已有同编号书签的 chapter/page 归属不同则拒绝恢复。
  检查内嵌作品编号、非负页码/scroll、有限且处于 [-1,1] 的 percent；完整 Long 编号仍保留。
- 统计按 manga_id/started_at 匹配，同一阅读会话的 duration/pages 各取较大值。
  格式没有 updatedAt，因此不把备份计数直接覆盖较大本地计数，也不相加导致重复导入膨胀。
  检查非负计数与作品关联；完整 Long 时长/时间与 Int 页数无需累加运算。
- 旧 work_stats 按 projections 的 anchor_manga_id 写入 stats；没有备份作品锚点的旧统计按
  既有兼容方向跳过。当前 statistics 缺少对应作品则拒绝恢复；两者都不重建 entity/work 表。
  所有支持节仍在同一个 immediateTransaction 合并，后期统计错误会回滚此前的分类、作品及书签。

界面改称“图书馆备份”，补充书签/统计范围、实际预览数量与统计合并说明。仅提供备份迁移能力；
桌面阅读器的书签编辑界面与实时统计记录仍待接入，不能把恢复测试当作这些功能的完成证明。
其余备份节、外部格式与 WebDAV 继续保留原目标。

### 验收与产物

新增五项数据库/ZIP 测试：9 条书签跨导出分页、仅有书签/统计的作品和标签、两轮导出/恢复后重开、
0/负数/Long.MAX/MIN 编号、同页面编号属于不同作品、较新书签保留、外置标签恢复、统计计数最大值及
重复恢复、旧统计锚点、错误位置/编号/计数/缺作品导致整次回滚、坏 JSON 在预览前拒绝。
统计错误测试先写入有效书签再触发失败，实际核对书签和此前作品/分类均回滚。
首轮手写样例误用 stats 节名，被真实 statistics 输出断言及合并测试发现；修正为既有格式，未新增别名。

两进程 UI 探针加入书签/统计 fixture，真实界面核对数量、取消后无数据、确认合并、导出后预览，
第二进程从同一数据库读取完整书签位置和统计。已查看 backups-write-preview.png，界面可滚动到确认按钮。
未使用用户库、用户真实备份、生产源请求或 Android 设备恢复。

| 检查 | 最终结果 |
|---|---|
| core-source JVM / metadata | 44/44；metadata 通过 |
| source-host JVM / 默认发行 | 74/74；installDist 通过 |
| desktop-runtime JVM | 38/38，含新增 5 项；最终回滚断言修改后再执行全部 38 项通过 |
| mihon-desktop-compat API / WebView2 | 30/30 |
| desktop-app UI / 存储 / 原生窗口 | 53/53 |
| reader-core JVM / metadata | 179/179；metadata 通过 |
| core-backup JVM / metadata、core-db metadata | 13/13；两模块 metadata 通过 |
| 普通 Windows/共享回归 | 431/431，0 失败、0 错误、0 跳过 |
| 移动目录原生分发 | 1/1；随包 JVM、Room、备份、Skia、WebView2 与生产 Window 通过 |
| Windows/共享含分发汇总 | 432/432，0 失败、0 错误、0 跳过 |
| Android 编译 / 指定兼容测试 | compileDebugKotlin 实际重新编译；4 类 14/14，0 失败、0 跳过 |
| MSI / ZIP 内容 | 448 个应用文件 + 一个包名标记 / 449 个文件，成员与 SHA 检查通过 |

普通回归日志 `build/kmp-windows-bookmark-stats-complete.log`：2 分 8 秒，88 项任务
（17 项执行、71 项 up-to-date）；最终 runtime 日志 `build/kmp-windows-bookmark-stats-runtime-final.log`：
15 秒，21 项任务（4 项执行、17 项 up-to-date）。Android 日志
`build/kmp-windows-bookmark-stats-android.log`：2 分 8 秒，111 项任务（24 项执行、87 项 up-to-date），
沿用 ignored 的输出隔离 init。没有 Android 真机、R8 或 iOS 原生验证。

包装脚本日志 `build/kmp-windows-bookmark-stats-package.log`：7 秒，53 项任务
（9 项执行、44 项 up-to-date），复用了内容未变化的应用与分发测试。随后对
windowsDistributionTest 使用单任务 --rerun 再实际执行移动目录检查；日志
`build/kmp-windows-bookmark-stats-native-final.log`：20 秒，53 项任务（10 项执行、43 项 up-to-date）。
原生 EXE 实际导出/解析的新节集合包含 BOOKMARKS/STATS，报告 backup=ok、status=ok，
Java.home 指向含中文和空格的移动目录内 runtime，schema=84；MSI 内容检查再次通过。

本地输出位于 desktop-app/build/compose/binaries/main；ZIP 153091981 字节，MSI 154054900 字节，
ZIP SHA/成员复核日志为 `build/kmp-windows-bookmark-stats-zip-check.log`。输出均被 Git 忽略；
桥进程残留 0，git diff --check 通过。未执行 MSI 安装/升级/卸载、注册表/快捷方式修改、签名、发布或提交。

DRY 复用共享 wire/DAO 与既有事务；KISS 用完整主键和会话计数最大值维持显式合并；SRP 保持
ZIP 所有权、数据库恢复和确认 UI 的边界。完整备份/外部格式/同步、桌面书签与统计交互、全局图像/
磁盘预算、下载批量操作、完整源生态、Windows 安装/升级/卸载与 S3/签名，以及 iOS Mobile JVM/
JNI/WKWebView/SwiftUI/原生真机门槛仍待，全量 KMP 未完成。

### 复验命令

```powershell
./gradlew.bat -PwithDesktopApp "-PmihonCompatibilityDirectory=D:/tmp/kt-compat36" -PmihonCompatibilityJavaVersion=25 :mihon-desktop-compat:test :desktop-app:test :desktop-runtime:test :source-host:test :source-host:installDist :core-source:jvmTest :core-source:compileCommonMainKotlinMetadata :reader-core:jvmTest :reader-core:compileCommonMainKotlinMetadata :core-backup:jvmTest :core-backup:compileCommonMainKotlinMetadata :core-db:compileCommonMainKotlinMetadata
./gradlew.bat -I "build/kmp-backup-validation.init.gradle" :app:compileDebugKotlin :app:testDebugUnitTest --tests "org.skepsun.kototoro.backups.data.BackupIndexCompatTest" --tests "org.skepsun.kototoro.backups.data.KotatsuBackupPayloadCompatTest" --tests "org.skepsun.kototoro.backups.domain.BackupPayloadGuardTest" --tests "org.skepsun.kototoro.backups.domain.BackupRestoreFormatTest"
./desktop-app/package-windows.ps1 -CompatibilityDirectory "D:/tmp/kt-compat36" -JavaVersion 25
```

早期普通回归与包装分别为 2 分 42 秒、2 分 1 秒；同名日志现已由上述复验覆盖，最终证据以本节
验收表和当前日志为准。单独强制原生分发复验时，在 withWindowsDistribution 模式下使用
`:desktop-app:windowsDistributionTest --rerun`，其余包装输入与任务保持原命令。

## 55. Windows 产品入口核对：仓库、内置解析器与 Android 视觉复用

日期：2026-10-03。用户反馈 Windows 只有 Mihon/JAR 入口、无法添加 .pb 仓库、内置解析器缺失，
以及界面与 Android 不一致。此前回归证明执行/存储闭环，不证明源生态、仓库安装或最终产品 UI 已迁移。
本节调整后续 Windows 优先顺序，不宣称以下待办已经实现。

| 项目 | 当前证据 | 后续实现边界 |
|---|---|---|
| 源执行 | source-host 仅装配 MihonSourceRuntime；桌面选择器仅接受 JAR | 继续保留 Mihon ABI；为 kototoro-parsers 增加同一 SourceRuntime 契约的适配，不把它伪装成 Mihon 扩展 |
| 仓库 | 桌面无仓库入口；Android ExtensionStoreIndex 已含 protobuf 索引与 resources.jarUrl（field 501） | 抽取中立索引模型/解码与仓库解析规则，提供添加/列表/下载/安装/更新和受应用管理的产物目录 |
| APK 与 JAR | 当前加载器检查文本 AndroidManifest/JVM class，未实现 DEX 转换或 APK 安装 | .pb 是索引，格式不决定产物类型；有 jarUrl 时使用发布的 JAR，只有 APK 时明确不可安装，不能替换后缀冒充 JAR |
| 内置解析器 | 同级 kototoro-parsers 是 kotlin.jvm/java-library；桌面尚未依赖或装配 | 补 ContentLoaderContext 的 HTTP/Cookie/配置/JS/Bitmap/浏览器操作，保持 parser 二进制合约及既有源 ID，不要求重写站点解析器 |
| UI | desktop-app 使用 compose.material/lightColors 与独立固定色，Android 使用 Material3/GlassSurface | 提取主题/字形/形状与纯 Compose 组件，再迁移主导航、卡片、详情和源管理；宽窗口布局可适配，视觉系统应共用 |
| Backdrop | 本地模块为 com.android.library，UPSTREAM.md 明确未取 skikoMain；四处 expect/actual 已压平为 Android 实现 | 恢复与现行 vendored 2.0.0 对应的 commonMain/androidMain/skikoMain/desktopMain，保留本地布局去重与采样补丁；不为此自动升级核心依赖 |

核对上游 [Backdrop 构建](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/backdrop/build.gradle.kts)
及 [Skiko shader 实现](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/backdrop/src/skikoMain/kotlin/com/kyant/backdrop/RuntimeShader.kt)：
桌面目标实际依赖 skikoMain，RuntimeShader 使用 Skia RuntimeEffect。原“Backdrop 不可移植”表述过宽，
已修正；恢复多平台模块和 GlassSurface 的 Android Context/Hilt/偏好边界后才能在 Windows 使用，
本次没有进行玻璃渲染编译/截图/性能验收，也不改变 iOS SwiftUI 的现行决策。

核对 [Keiyoushi 当前索引](https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json)，
resources 同时含 apkUrl/jarUrl。这仅证明该仓库发布 JAR，不证明任意 .pb 仓库都有 JAR，也不证明
全量扩展已通过当前桌面兼容层。具体用户仓库地址待提供后核对；当前可手动下载其 jarUrl 并导入，
由于加载记录仍引用原文件路径，导入后不能随意移动或删除该文件。

下一阶段优先补仓库获取/安装和 kototoro-parsers，使用户能从入口获得来源；随后落实共用视觉系统
与 Backdrop 桌面适配。其余备份/同步与原平台门槛继续保留；不把临时验证外壳当作最终 Windows UI。

## 56. Windows 仓库添加、JAR 安装更新与应用拥有的产物

日期：2026-10-03，执行 §55 的第一项产品入口修补。`ExtensionStoreIndex` 的唯一模型进入
core-source/commonMain，保留全部 protobuf 字段编号，Android 留原路径说明并引用共享模型。
共用解码支持 .pb 和新版 JSON 的前缀枚举/数值枚举、字符串 64 位整数及未知字段；不把长编号转为 Double。
沿用统一 serialization 1.11.0，没有更新核心依赖或引入独立索引模型。

桌面侧栏“扩展与仓库”新增地址输入、保存的仓库刷新、扩展/来源/语言搜索、安装与更新。
支持完整 index.pb/index.json、repo.json 或根地址；根地址只在 .pb 返回 HTTP 404 时回退 JSON。
相对外部列表/JAR 地址以实际响应地址解析，支持 gzip，索引上限 16 MiB、JAR 上限 64 MiB，
HTTP 整体 30 秒超时，取消关闭 header future/响应体，移除仅由此次请求创建的临时文件。
只有 APK 的条目禁用并显示“仅有 APK”，不执行 APK/DEX 转换或替换 URL 后缀。

下载与新手动导入先复制到应用 extensions 目录，再检查清单、包名、版本、扩展 API、JVM class
与 SHA，发布为不可变 SHA-256.jar。构造来源、重复源检查及偏好记录提交成功后才发布 registry
替换；失败保留原来源，旧调用的 lease 保持旧加载器到调用结束。禁止安装较旧版本。
重启用同一 managed JAR 与持久化 identity 恢复，不需要原导入文件。此前的外部路径记录未迁移，
仍需要保留该文件；旧版本 owned JAR 暂不清理。索引 signingKey 变化被拒绝，但此字段不证明
JAR 签名，SHA 校验仅证明应用下载副本及随后恢复的一致身份。

新增覆盖：共享 wire 三项、registry 替换/旧调用保活两项、仓库运行时六项，以及实际 Compose
仓库添加/安装/更新/另一离线进程恢复一项。UI fixture 使用自建 localhost 服务，下载两次，
重启下载零次，生产站点请求零次；安装后可点击实际来源浏览。已检查 catalog/installed/offline
截图与中文路径关闭后移动，未以静态 UI 或 fake registry 代替执行验证。

首次完整回归暴露取消测试的调度竞态：服务端发送首块不代表客户端已创建 staging 文件，
过早取消允许目录尚未创建。测试改为有界等待 owned partial 文件，再取消阻塞读取，
从而明确验证“下载过程中取消”的清理行为；生产关闭流导致的 IOException 转回 CancellationException。

SRP 分离共享 wire、平台 HTTP/产物所有权与 Session 的安装发布；DRY 复用现行偏好、inspector、
registry 与 SourceRuntime，KISS 保持显式失败/版本规则。内置 kototoro-parsers、共用 Material3
视觉/Backdrop、卸载/仓库删除/自动更新、APK-only 支持、其他生态与全量 KMP/iOS 门槛仍待，
此次不宣称上述工作完成。§55 的“无仓库入口/新导入仍依赖原路径”描述是本次改动前的证据。

最终普通回归 `build/kmp-windows-repository-complete.log`：2 分 52 秒，88 项任务
（16 项执行、72 项 up-to-date），Windows/shared 443/443、0 失败、0 跳过：core-source 47、
source-host 76、desktop-runtime 44、compat 30、desktop-app 54、reader-core 179、core-backup 13。
Android `build/kmp-windows-repository-android.log`：4 分 28 秒，111 项任务（22 项执行、89 项 up-to-date），
compileDebugKotlin 与 ExtensionRepoServiceTest 16/16、NovelSourceryIndexFixtureTest 4/4 通过。
复验使用 §54 的普通回归命令；Android 测试过滤改为上述两类，保持 ignored 输出隔离 init。

Windows 包装 `build/kmp-windows-repository-package.log`：1 分 21 秒，53 项任务（15 项执行、38 项
up-to-date）。移动目录的独立 JVM/原生 EXE/实际窗口验证 1/1 通过，status/backup/image/browser=ok，
schema=84，Java.home 位于移动后的中文目录；加上普通回归共 444 项。MSI 核对 448 个应用文件
及包名标记，ZIP 449 个成员逐文件 SHA 与应用目录一致。ZIP 153240922 字节，MSI 154202356 字节，
ZIP SHA-256 为 3705D9F1ACED98BFEF1117153A2205EF9A648A15523EA06CE506DE8691DB33B9。
没有执行 MSI 安装/升级/卸载、注册表/快捷方式写入、发布、签名或 Git 提交。

首次包装失败为 C 盘临时目录空间不足（剩余约 180 MiB），发生在复制运行时 image 期间。
将 windowsDistributionTest 的 java.io.tmpdir 放到本模块 ignored build/tmp，子进程 TEMP/TMP
也仅指向测试自有目录；不修改系统环境或清理用户数据。复验已通过，最后桥进程残留 0、
git diff --check 通过，catalog/installed/offline 截图位于 desktop-app/build/reports/desktop-smoke。

## 57. 再漫画浏览失败：补齐桌面 streaming JSON 运行时

日期：2026-10-03。用户导入 Zaimanhua 后点来源，显示 `Source operation failed`。
使用本机已安装的同一 JAR（只读）和独立 ignored build 数据目录复现：describe 成功，getList
抛出 Mihon runtime ABI failure，根因为 NoClassDefFoundError：
`kotlinx/serialization/json/okio/OkioStreamsKt`。SourceEndpoint 按既有规则将异常折叠为通用错误，
因此此前 UI 提示没有显示缺失的依赖名称。没有读取用户 Cookie/源设置、修改用户数据库或重新导入用户扩展。

在 mihon-desktop-compat 的 Gradle runtime 添加现行统一版本的 serialization-json-okio 1.11.0。
Android 已使用此版本；保持全项目版本与 29 个外部 runtime pin 不变，排除该依赖的传递 Okio，
沿用原 classpath 优先级。职责仍归兼容模块；没有新增 JSON shim、修改扩展 JAR 或改写协议错误为原始异常。
DRY 复用 Android 已有库与平台版本，KISS 用缺失的真实 ABI 依赖修复响应解析。

自建 OfflineSource 在 fetchPopularManga 中通过真实 OkioStreamsKt.decodeFromBufferedSource
解析 Buffer JSON，保持既有返回内容。已有独立进程/UI 测试会经过该路径；移动目录的随包 JVM
新增 source-browse probe，通过生产 Session/SourceProtocol 浏览同一离线来源并验证返回标题。
仓库 UI probe 改为等待浏览内容或显式错误，以免 action 调度前 busy=false 导致过早断言。

针对回归 7/7：MihonDesktopPlatformTest 五项、真实窗口浏览/状态恢复及仓库安装更新/恢复两项。
日志 build/kmp-windows-zaimanhua-focused.log（最终 21 秒；兼容测试复用本次首轮已通过的五项）。
最终包装与移动目录门槛 1/1：build/kmp-windows-zaimanhua-package-final.log（20 秒，53 项任务，
11 项执行、42 项 up-to-date），source-browse.log 报告 DESKTOP_SOURCE_BROWSE_OK=streaming-json。
首次新包包装日志 build/kmp-windows-zaimanhua-package.log 为 59 秒，随后新增随包浏览门槛再验证。

真实来源另用新 image 的 runtime/bin/java 和仅取自 Kototoro.cfg 的生产依赖重放；
build/zaimanhua-image-probe.log 报告再漫画 MIHON_524579092615598717、DESCRIBE=OK、BROWSE=OK COUNT=10。
这是一次实际来源的浏览验证，不推断全量来源、详情或阅读兼容率；离线 fixture 无生产请求。
真实来源请求只使用独立默认设置，探针代码/数据/日志均在 ignored build，用户根目录保持不变。

MSI 比对 449 个应用文件及包名标记；ZIP 450 个成员逐文件 SHA 与 image 一致，
新增且只含一份 serialization-json-okio 1.11.0。ZIP 153247038 字节，MSI 154210572 字节，
ZIP SHA-256 为 B9B938517C04F0336A276BC19FFE51B17E6483C0D8A6501C26E8073D04BC20F9。
原生运行/窗口关闭与 streaming JSON 浏览通过，桥进程残留 0、git diff --check 通过。
没有 MSI 实际安装、发布、签名、提交或 Android/iOS 重验；此修复仅增加可选桌面运行时依赖。

## 58. 再漫画仍报错：保留 SDK 客户端拦截器并验证完整浏览路径

日期：2026-10-03。用户反馈 §57 新包仍显示同一错误。只读检查正在运行的 E:/Kototoro，
确认已经包含 serialization-json-okio 1.11.0；沿 DesktopSession/Controller 的生产路径，使用
同一已导入 JAR 和独立 build 数据目录复现 UI_ERROR=Source operation failed、UI_ITEMS=0。
根因为扩展 keiyoushi.source.a.getClient 检查默认客户端必须包含 CloudflareInterceptor，
桌面开启网页验证时替换了此拦截器，触发 IllegalStateException。
§57 的真实来源探针关闭网页验证并直接调用 runtime，只证明 streaming JSON 依赖修复；
没有覆盖生产桌面客户端装配，验收范围不足。复现日志为 build/zaimanhua-session-probe.log。

适配改为保留原 SDK CloudflareInterceptor 实例，在其后加入 DesktopChallengeInterceptor。
后置处理仍按 cf-mitigated 标记进行 GET/HEAD 人工验证并最多重试一次；未解决的挑战，以及
SDK 识别的 403/503 + Server: cloudflare/cloudflare-nginx，关闭响应并抛出 IOException，
避免回到服务器专用的 FlareSolverr/ServerConfig 路径。普通拒绝响应不打开浏览器，POST 不重发。
不改第三方 JAR、原 CookieJar、UA、缓存或线程池，也不创建另一套 NetworkHelper。
新增测试检查原 SDK 拦截器身份及 403/503 保护；离线 fixture 也要求默认客户端保留真实 SDK 实例。

SourceEndpoint 增加可选本地失败观察，SourceRemoteException 携带现有响应 requestId；
保留两个类原单参数 JVM 构造函数，wire 格式和通用错误文本保持不变。Session 记录当前会话
最近 16 项诊断，单项最多 4096 字符、最多六层原因与每层六个栈帧。Controller 根据精确
requestId 显示错误类别、诊断编号及 logs/source-errors.log 路径。日志只包含时间、操作类型、
错误类型和栈帧，不记录请求参数、异常消息、原始网址、Cookie、请求头或响应内容。
写入失败不会掩盖来源错误；测试覆盖有界保留、敏感文本排除及日志目录不可写时的数据保护。

回归还发现操作入队后、IO 协程开始前 busy=false 的短暂空隙，导致仓库 UI 等待过早结束。
Controller 在返回 Job 前发布 busy，用串行操作的待完成计数在 completion 回调中清除状态，
包括尚未开始即被取消的操作。沿用原 scope/gate，不增加请求队列。SRP 将诊断归本地独立组件，
DRY 复用协议请求编号和原客户端，KISS 保留固定 SDK 对象身份。

普通回归 build/kmp-windows-zaimanhua-client-complete.log：3 分 16 秒，88 项任务
（20 项执行、68 项 up-to-date）；common metadata 与 source-host installDist 一并通过。

| 模块 | 通过测试 |
| --- | ---: |
| core-source | 48 |
| source-host | 76 |
| desktop-runtime | 44 |
| mihon-desktop-compat | 31 |
| desktop-app | 56 |
| reader-core | 179 |
| core-backup | 13 |
| 合计 | 447，0 失败、0 跳过 |

Android 隔离输出编译 build/kmp-windows-zaimanhua-diagnostics-android.log：1 分 29 秒，
50 项任务（11 项执行、39 项 up-to-date）。没有 Android 设备或 iOS 真机验证。

Windows 包装 build/kmp-windows-zaimanhua-client-package.log：1 分 22 秒，53 项任务
（15 项执行、38 项 up-to-date）。移动目录/随包 JVM/原生窗口门槛 1/1，加普通回归共 448 项。
source-browse probe 改为经过生产 Controller/Session/SourceProtocol，验证状态无错误和返回标题，
成功标记为 DESKTOP_SOURCE_BROWSE_OK=controller-streaming-json。运行时报告 status/backup/image/
browser=ok、schema=84，Java.home 与浏览器桥位于移动后的中文测试目录。

再用新 image 的随包 JVM、仅来自 Kototoro.cfg 的生产依赖和同一再漫画 JAR，沿生产
Controller.selectSource 验证，build/zaimanhua-session-fixed.log 报告 UI_ERROR=null、UI_ITEMS=10。
探针仅增加异常类型/栈帧观察，不替换来源逻辑；扩展读取只限已导入 JAR，所有新数据写入独立
ignored build 目录，没有读取用户 Cookie/源设置或修改用户数据库。这是再漫画本次浏览验证，
不能推断详情、阅读、真实网页挑战或全量扩展兼容率。

MSI 比对 449 个应用文件及包名标记；ZIP 450 个成员逐文件 SHA 与 image 一致，只含一份
serialization-json-okio 1.11.0。ZIP 153257258 字节，MSI 154218764 字节，ZIP SHA-256 为
1A6DF9DFABB83D7F41F345AB5C388BCA696D45BC9D67E65F296A1F1735AA475F。
另复制为 Kototoro-Windows-zaimanhua-client-fix-20261003.zip 并验证哈希一致，以区分 §57 旧包。
桥进程残留 0、git diff --check 通过；没有覆盖用户安装目录、执行 MSI 实际安装、发布、签名或 Git 提交。

## 59. Windows 对齐 Android 平板结构与阅读器交互

日期：2026-10-03。用户明确目标是 Android 版本的平板视图，并指出 Windows 阅读器简陋。
对照 KototoroBottomNav 的横屏导航、AppSettings 的默认 80dp 栏宽、TabletLayout 的 1000dp
expanded 阈值、ComposeReaderActivityScaffold 的独立章节/选项面板，完成第一轮布局改造。
同时查阅 [Android 官方 Reply 自适应示例](https://raw.githubusercontent.com/android/compose-samples/main/Reply/app/src/main/java/com/example/reply/ui/ReplyApp.kt)
和 [Compose Desktop 窗口状态文档](https://kotlinlang.org/docs/multiplatform/compose-desktop-top-level-windows-management.html)。
采用按窗口宽度切换单/双栏和声明式 WindowPlacement，不照搬 Android Activity、Hilt 或平台资源调用。

主界面调整：

- 80dp 主导航仅保留收藏、浏览、历史、更多；扩展与仓库、下载、备份及高级网页工具归更多。
  来源列表只属于浏览页，移除常驻 244dp 调试侧栏和每个页面底部的数据路径。
- 本地导入回到浏览页；仓库安装/更新保留仓库页。浏览入口重新调用原 browseOnIo，避免把
  收藏/历史条目误当作当前来源列表。既有 UI 回归改为走新的真实导航入口。
- 封面采用 2:3 竖版、156dp 自适应网格；沿用 DesktopCovers 的原请求、缓存与错误重试。
- 1000dp 及以上窗口，详情使用信息栏和独立 LazyColumn 章节栏，章节可搜索/正倒序；
  更窄窗口采用横向封面/操作区与页面级章节列表，避免大封面把阅读按钮挤出可见区域。
- 构建直接读取 Android 原有八个 vector 图标，生成随主 JAR 分发的 SVG；只支持本次实际使用的
  path 和 group translation，不认识的节点/变换明确失败。没有额外图标库或第二份手写图形。

阅读器调整：

- 独占内容窗口，隐藏主导航、来源栏和主页面标题；深色 ReaderTheme 显式提供内容色，
  修复从浅色父主题继承黑色文字导致的对比度问题。
- 顶部作品/章节标题、章节/设置/全屏入口，底部翻页、跨章、页码滑条和常用模式操作。
  滑条拖动只改本地目标，完成后通过原 controller.page 跳转，不创建另一套进度写入路径。
- H/收起隐藏工具栏并扩大画布，底部按钮/H 恢复；收起不重取图片或改历史。
  连续模式移除常驻缓存统计文字，沿用原可见范围、恢复和滚动条逻辑。
- 同窗口章节侧面板支持搜索、排序、当前章节标记及选择章节；选项侧面板提供明确的
  单页/双页/连续和适配选项。开关面板不替换阅读场景，不重建 Session/Controller。
- 章节输入拥有编辑键，面板存在时主阅读翻页快捷键让出；Esc 先关闭面板，再返回详情。
  F11/按钮通过生产 WindowState 切换 Fullscreen，退出恢复此前 Floating/Maximized；返回详情也退出。
- 保留原双页/RTL、宽图独页、相机输入、连续滚动、长图分块、自动跨章、图片缓存及历史恢复。
  SRP 将导航/详情与阅读侧面板拆为独立组件；DRY 复用 Android 图标和现有 Controller/reader-core，
  不新增用户数据库、schema、依赖、并行源执行器或阅读设置体系。

新增 DesktopTabletProbe 使用自有离线阅读扩展、真实 SDK、UI 和数据库，在 1260×850 与
920×620 窗口检查导航栏宽度、详情分栏、阅读器无主导航、收起后画布增长、图片请求数/历史不变、
页码跳转到第 4 页、面板及输入键归属、章节切换和全屏回调/返回恢复。全屏测试验证 UI 回调状态；
生产入口绑定真实 WindowPlacement，但没有单独录制操作系统全屏过渡。
截图位于 desktop-app/build/reports/desktop-smoke/tablet-*.png 和 tablet-narrow-*.png，已实际查看
宽屏阅读、选项/章节与窄屏详情；截图仅含自有彩色测试页，不代表生产漫画图像质量验收。

首次完整回归发现浏览器探针仍点击旧导航入口，以及长图探针固定 wheel=500 依赖旧侧栏宽度。
改为走更多入口、按真实视口宽度定位自有长图的蓝色中部，再核对像素、请求数和历史。
保留原分块接缝与连续场景检查，不放宽像素断言或跳过失败。

最终普通回归 build/kmp-windows-tablet-complete.log：2 分 32 秒，57 项任务
（11 项执行、46 项 up-to-date）。core-source 48、source-host 76、desktop-runtime 44、
mihon-desktop-compat 31、desktop-app 57、reader-core 179、core-backup 13，共 448 项，
0 失败、0 跳过；共享模块未改动的任务复用当前已通过的结果。最终编译后的分发门槛另 1/1，合计 449。

本地包装 build/kmp-windows-tablet-package-final.log：1 分 1 秒，54 项任务
（18 项执行、36 项 up-to-date）。移动中文目录的随包 JVM、原生窗口关闭、资源和 Controller
streaming JSON 浏览通过；MSI 比对 449 个应用文件及包名标记，ZIP 450 个成员逐文件 SHA 与
image 一致。新 image 另使用用户已导入的再漫画 JAR（只读）和独立 build 数据目录，经生产
Controller 验证，build/zaimanhua-tablet-session.log 报告 UI_ERROR=null、UI_ITEMS=10。
该结果只验证本次浏览，没有读取用户 Cookie/源设置或写用户数据库。

交付 Kototoro-Windows-tablet-reader-20261003.zip，153348590 字节；MSI 154321164 字节。
ZIP SHA-256：A9517A9EAC44ABA9B714E0136FBEEEB0C14292B6502D13DB18BE8CDF2DA8B0B5。
桥进程残留 0、git diff --check 通过；没有覆盖用户安装目录、实际 MSI 安装、发布、签名或 Git 提交。

本轮是信息结构与阅读操作的第一轮对齐，尚未完成 Android UI 的完全复用。桌面仍用现有 Material
宿主；Android Material3/主题偏好、Backdrop、书库筛选抽屉、详情浮动预览、阅读书签与完整手势/
高级选项仍待。仓库 vendored Backdrop 是 Android-only 模块，不能直接接到桌面；§55 所列 upstream
KMP 路线仍有效，应保留本地 geometry 修复并单独完成桌面渲染验证。本轮未修改 Android 源码或
Backdrop，也未执行 Android 设备/平板视觉对照、iOS 真机或真实网页挑战验收。

## 60. Windows 书库筛选抽屉与阅读书签

日期：2026-10-03。继续 §59 的 Android 平板信息结构对齐，补齐收藏/历史的查找与筛选，
以及阅读器的书签保存和跨章节恢复。当前 vendored Backdrop 和 Android Material3 主题未改动。

书库：

- DesktopLibrary.snapshot 在共享 Room 的读事务中取得作品、活跃收藏分类/成员关系和历史进度，
  投影为桌面 DTO；Compose 不接触持久化映射，也不重建已移除的 Entity Graph / Work。
- 标题/别名/作者搜索；右侧抽屉提供单分类、多来源、未读/阅读中/已读完组合筛选，以及默认顺序、
  标题、最近阅读、进度排序。多个来源取并集，与分类/进度/查询交集；排序以完整 Long id 稳定收尾。
- 搜索和条件仅派生现有快照，不重新请求来源列表或逐字查询数据库；可见封面仍沿用原请求/缓存。
  收藏与历史条件在当前 Controller 生命周期内分别保留，空结果可重置；显式刷新重新读取快照。
- 抽屉有可拖动滚动条、关闭按钮、点击遮罩/Esc 关闭，窄窗口可访问分类与来源。已读完按当前
  分支的共享历史 percent 判断，不代表所有分支已阅读；默认顺序沿用收藏/历史记录的更新时间。

阅读书签：

- 底部添加/移除书签、B 快捷键及顶部书签侧面板，显示章节、页码、作品进度和当前位置。
  面板内编辑仍拥有按键，原 H/F11/Esc、章节、单/双页/连续、相机和长图逻辑保留。
- DesktopLibrary 直接写现有 bookmarks 表，完整保留作品/页面/章节的有符号 64 位编号，
  按 Android 的章节+页码位置切换书签，即使来源重建页面 id，也能移除同位置旧记录。
  连续滚动保留整数像素偏移；写入前验证页码、偏移、内容身份和存储章节，使用原 writer 事务。
- 重启后重新读取书签；跳转优先匹配 pageId，旧 id 不存在时采用仍有效的保存页码；
  缺失章节/越界页明确失败。通过原 readOnIo/loadPagedOnIo 或连续导航恢复，不新增进度写入通道。
- 复用现有 core-backup 书签格式，没有 schema、依赖或 Android 源码变化。SRP 将书库界面、
  纯筛选派生和持久化 DTO 分开；DRY 复用原 Controller gate、数据库、阅读布局与缓存。

验证：

- DesktopLibraryTest 新增书签重开、极端 Long 编号、像素偏移、非法位置拒绝、按位置移除、
  书签不写历史，以及库快照/删除分类/历史维度检查。
- DesktopLibrarySelectionTest 覆盖来源并集与多维交集、别名/作者/忽略大小写查询、阅读状态和稳定排序。
- DesktopTabletProbe 在 1260×850、920×620 真实 UI 中保存/打开/移除第 4 页书签，并验证跨章返回、
  搜索无结果/重置、未读筛选、滚动到分类/来源选择，以及收藏/历史条件隔离。
- DesktopBookmarksProbe 分两个 JVM，使用自有扩展和独立数据根目录。写入连续模式第 4 页非零偏移，
  换到下一章后退出；第二个进程禁用图片网络，仍可跨章恢复书签页和偏移、移除书签，图片请求为 0。
  此像素恢复测试使用相同窗口尺寸，不保证改变窗口尺寸后的像素偏移等同于相同内容位置。
- 已查看宽、窄窗口截图；截图位于 desktop-app/build/reports/desktop-smoke/tablet*-library*.png
  和 tablet*-bookmarks.png，仅使用自有测试作品，未作 Android 平板设备视觉对照。

普通回归 build/kmp-windows-library-complete.log：2 分 47 秒，57 项任务（14 执行、43 up-to-date）。
core-source 48、source-host 76、desktop-runtime 46、mihon-desktop-compat 31、desktop-app 60、
reader-core 179、core-backup 13，共 453 项，0 失败、0 跳过；未改共享模块复用已通过的测试结果。
Windows 分发门槛另 1/1，合计 454。打包 build/kmp-windows-library-package.log：1 分 7 秒，
54 项任务（17 执行、37 up-to-date），移动中文路径随包 JVM、原生窗口关闭、资源和生产 Controller
streaming JSON 浏览通过；MSI 比对 449 个应用文件及包名标记，ZIP 450 个成员逐文件 SHA 与 image 一致。

新 image 使用只读的用户已导入再漫画 JAR，沿生产 Session/Controller 在独立 ignored build 数据目录
验证浏览，build/zaimanhua-library-session.log：UI_ERROR=null、UI_ITEMS=10。未读取用户 Cookie/设置
或修改用户数据库；此结果仅覆盖浏览，不推断真实漫画详情、阅读、网页挑战或全部扩展兼容。

交付 Kototoro-Windows-library-bookmarks-20261003.zip，153440654 字节；MSI 154403084 字节。
ZIP SHA-256：57DD7959A4F9FF476D968ACFBC9EFBCF0B2051ABD4B322D9E9559D8F4CBE3CA5。
桥进程残留 0、git diff --check 通过；未覆盖用户安装目录、执行 MSI 实际安装、发布或 Git 提交。

尚待：Android 主题/Material3 与 Backdrop 的桌面适配、详情浮动预览、更多阅读手势/选项。
本轮基础书库抽屉尚未覆盖 Android 标签、追踪状态、下载条件与分类管理；书签尚无缩略图、全局
书签页及批量管理。筛选条件只在当前运行保留。没有声称完成全量 Android UI 复用或整个 KMP 迁移。


## 61. Tsundoku / Aniyomi 接入、APK 转换与 R8 修复（Windows）

- **Tsundoku**：与 Mihon 共用 Tachiyomi ABI。`MihonJarInspector` 按清单键区分 `tachiyomi.extension` / `tachiyomi.novelextension` / `tachiyomi.animeextension`（同时出现则 `AMBIGUOUS_ECOSYSTEM`）；来源名 `TSUNDOKU_<id>`、类型 `NOVEL`/`HENTAI_NOVEL`；`MihonSourceRuntime.getChapterContent` 汇总 `Page.text` / `fetchPageText`（HTML 原样放行，纯文本转义成段落，图片页进 `images`）。
- **APK 摄取**：`dex-convert` 的 `ApkExtensionConverter` 把 APK 转成带文本清单的 JVM jar；`DesktopRepositories` 对只发布 APK 的条目下载后本机转换（jar 优先，否则 apk）；索引里的 `apkUrl` 同样按仓库基址解析。
- **dex2jar 缺陷修复**（`DexRepair.kt`）：R8 内联平凡构造器后留下 `new-instance LT; invoke-direct {v}, LS;-><init>`（S 为祖先），JVM 校验失败。转换前在 DexFileNode 上把调用改回 T 的构造器并补合成构造器（含跨 dex、整条继承链、寄存器搬运、循环）；另开 `dontSanitizeNames(true)` 保留 Kotlin 内联类的 `-impl` 名。`DexJarConverter.VERSION=3`，报告含 `repairedInstantiations`。
- **Aniyomi**：`mihon-desktop-compat` 内移植 animesource ABI（去掉 Kototoro 请求上下文；client 用平台 `network.client`）；`AniyomiSourceRuntime`（列表/搜索/筛选/详情/剧集/视频流：先 `getVideoList` 再 hoster，`resolveVideo`，`preferred` 优先；`SourcePage` 新增 `externalAudioTracks`）；路由 `mihonRuntime::owns` / `aniyomiRuntime::owns`；来源名 `ANIYOMI_<id>`、类型 `VIDEO`。无浏览器组件时仍用 `DesktopChallengeInterceptor`（`challenges=null`）拦住 SDK 的 Cloudflare 处理器，避免 `ServerConfigKt` 链接错误。
- **真实验证**：novelfull/allnovelfull（Tsundoku，AllNovelFull 联网列表→详情→正文通过）、animegg（Aniyomi，联网列表 125→1165 集→5 路流通过）；animekhor 列表/详情通过但无流（第三方播放器站点），animetake 需网页验证，animeparadise 站点 API 已变。测试开关：`-PrealApkDirectory`、`-PrealNovelApk`、`-PrealAnimeApk`、`-PrealDirect`。
- **回归**：desktop-app 63（除已修正默认 APK 的联网用例）、desktop-runtime 57、parser-host 23、dex-convert 14+5、compat 31、source-host 85、core-* 全绿。
- **未完成**：Windows 视频播放器（方案：JNA + libmpv，`--wid` 嵌入 SwingPanel，运行时发现 `libmpv-2.dll`，缺失时回退外部 mpv）；Aniyomi 旧版 `index.min.json` 仓库格式；`DesktopExtensionsPanel` 的 Aniyomi 提示与 UI 探针；Tsundoku 仓库 jar 是否含同样 R8 缺陷（已下载 allnovelfull-repo.jar 待检）；Aniyomi 偏好设置界面；文档 CLAUDE.md/README 补 `parser-host`、`dex-convert`。


## 62. Windows 视频播放（libmpv）与旧版仓库索引

- **`desktop-player` 模块**：JNA 绑定 libmpv 客户端 API。`MpvPlayer` 有窗口句柄时 `wid` 嵌入（`vo=gpu`），否则 `vo/ao=null`
  供测试；事件线程处理 START/FILE_LOADED/END_FILE（错误转为中文提示），采样线程每 200ms 读取位置/时长/暂停/缓冲/
  EOF/音量/倍速/轨道。请求头通过 `http-header-fields`（UA 用 `user-agent`）施加于主流与 `sub-add`/`audio-add` 的旁轨，
  `loadfile ... start=` 起播。`MpvLocator` 运行时查找，`commandLine` 生成外部 mpv 回退参数。
- **桌面接入**：`DesktopScreen.VIDEO` + `DesktopVideo`（剧集、线路列表、选中线路、起播秒、generation）；
  `watchOnIo` 用 `getPages` 取线路，`selectVideoStream` 续播切换，`changeVideoEpisode`，`DesktopVideoOperations`
  每 5 秒去抖写历史（page=秒、pageCount=时长）。UI：`SwingPanel` 内重量级 Canvas 取 HWND，创建/销毁播放器均在后台
  线程（避免与 EDT 的窗口消息互锁）；窗口重建时从最后位置续播；缺少 libmpv 时给出放置路径、重新检测、复制地址、外部 mpv。
  详情页对视频显示“播放”，视频章节不提供图片下载。
- **验证**：`MpvPlayerTest`（lavfi 测试源：加载/暂停/跳转/倍速/音量/标题；libmpv 自编码 MKV 经本地 HTTP：Referer/UA
  到达服务器、起播位置生效、缺头 403 报错）；`DesktopVideoProbe`（真实窗口：Aniyomi 夹具 → 详情 → 播放 → 线路切换续播
  → 历史 ≥3 秒 → 重开恢复 → 下一集）。本会话屏幕捕获整窗为黑（锁屏/远程会话），像素断言改为核对 mpv 日志中
  VO 窗口尺寸 = 画布 × DPI；可截屏时仍要求测试图案的彩色像素。
- **旧版仓库**：`decodeLegacyExtensionIndex` 支持 `index.min.json` 数组（APK-only，`extensionLib` 取版本名前缀）；
  根地址依次尝试 index.pb → index.json → index.min.json。仓库探针改为滚动到目标卡片后断言。
- **其他**：`SourcePage.externalAudioTracks` 放在参数末尾以保持位置参数兼容；Tsundoku 仓库自带 JAR 抽检无 R8 缺陷，仍优先下载 JAR。
- **未完成**：Anime4K/NCNN 超分、DLNA、弹幕；Aniyomi 偏好设置界面；libmpv 随包分发的授权决定；扩展卸载/自动更新。


## 63. Aniyomi 来源设置

- animesource ABI 的 `ConfigurableAnimeSource` 补上 `getSourcePreferences()`（默认取宿主 Application 的 `source_<id>`），
  以及 Aniyomi 的顶层 `preferenceKey()` / `sourcePreferences()` 辅助函数，旧版扩展自行读取同一存储也一致。
- `MihonNativePreferences.supports(lease, abi)` 识别 `ConfigurableAnimeSource`；`AniyomiSourceRuntime` 接收 preferenceContext，
  实现 `getPreferences` / `updatePreference`（与 Mihon 同一原生控件桥：选项、多选、开关、文本，监听器拒绝/异常语义不变）；
  桌面 Session 传入平台 Context，现有“来源设置”界面直接可用。
- 验证：`AniyomiPreferencesTest`（夹具 ABI：读取控件、修改选项与开关并落盘 `source_4243`，无 Context 时不声明支持）；
  桌面窗口探针在真实兼容运行时上打开 Aniyomi 夹具扩展的设置、把“首选线路”改为备用线路，重新播放时扩展按设置把备用线路排第一。


## 64. 超分：视频 Anime4K/FSR 与阅读器 NCNN/Anime4K

- **视频**（与 Android 视频增强对应）：`MpvEnhancementMode` = 关闭 / Anime4K 快速（Android FAST = Mode B）/ Anime4K 质量（QUALITY = Mode A）/
  Anime4K 仅修复（Mode C）/ FSR 1.0（锐度按 Android 换算 stops = 2×(1−值)，生成带 `#define SHARPNESS` 的变体文件）。Anime4K GLSL 由
  Gradle 从 `app/src/main/assets/shaders` 复制进 `desktop-player` 资源（与 Android 同一份，MIT 许可随附），FSR 为 AMD FidelityFX FSR
  1.0.2 的 mpv 移植（agyild，MIT，文件头含许可）。`MpvPlayer.setShaders` 写 `glsl-shaders`；播放器控制条“画质增强”菜单，设置存
  `desktop_video` 偏好、跨视频与重启保持。验证：单测解包/锐度/属性往返；窗口探针在真实 d3d11 管线确认 Anime4K（VL 上采样）与 FSR（RCAS）
  着色器编译运行且无 `[e][vo/gpu]` 错误。
- **阅读器**（与 Android 阅读器超分对应）：`desktop-runtime/DesktopSuperResolution` 调用官方 `realcugan-ncnn-vulkan`（nihui 20220728）与
  `realesrgan-ncnn-vulkan`（xinntao v0.2.5.0）——Android 模型目录本就引用后者的 Windows 包。不随应用分发：设置里“下载安装”（GitHub 资产 API
  优先，固定 SHA-256 校验后解压到 `tools/`）或“选择压缩包”。模型：RealCUGAN 2x（保守/无/1x/2x/3x 降噪）、Real-ESRGAN 4x 动漫、AnimeVideo v3 2x，
  以及 Android 的 Anime4K A/B/C/A+A/B+B/C+A——后者经 `MpvImageEnhancer` 用 libmpv 编码模式 + `vf=gpu` 离屏（Vulkan）复用同一套着色器。
  输入像素上限与 Android 相同，结果按内容+设置缓存为 PNG（默认 1 GiB LRU），同一时刻只处理一张；失败保留原图并在设置面板显示原因；
  切换设置会重新取当前页（命中缓存不重复下载）。本机耗时（120×180）：RealCUGAN ≈4.6s、ESRGAN 4x ≈2.5s、AnimeVideo ≈1.0s、Anime4K ≈1.5–2.6s。
- **验证**：`DesktopSuperResolutionTest`（非固定包拒绝、未安装报错、超限跳过；`-PncnnDirectory` 时三模型真实放大且缓存命中）；
  `MpvPlayerTest`（六种 Anime4K 图片模式尺寸正确且与无着色器缩放不同）；`DesktopUpscaleProbe`（阅读器 UI：未安装保留原图→安装后 2 倍→
  RealCUGAN 降噪→关闭恢复→4 倍→Anime4K B 2 倍 / C 原尺寸→偏好持久化）。


## 65. 扩展卸载与自动更新、DLNA 投屏、随包分发 libmpv / ncnn

- **扩展管理**：“扩展与仓库”新增已安装列表（Mihon/Tsundoku/Aniyomi 扩展与 kototoro/kotatsu/UMA 插件，显示生态、版本、来源数）。
  卸载先删安装记录再卸载来源，运行中的调用在旧加载器上完成；`cleanupArtifacts` 删除不再被记录引用的产物（卸载、更新后与每次启动时，
  Windows 下被占用的文件留待下次）。“检查更新”读取已保存仓库，取各包最高版本；“全部更新/单个更新”逐个安装，失败不影响其他。
  “启动时自动更新”存 `desktop_extension_updates`；启动后后台检查（仓库不可达时静默），开启则自动安装，否则提示有更新。
  验证：仓库探针（第三版经检查更新安装、旧产物清理、自动更新开关跨进程、离线启动无错误、卸载后来源/记录/文件移除）；解析器探针卸载 UMA 插件。
- **DLNA 投屏**（对应 Android `video/dlna` + LAN 代理）：`DlnaDiscovery`（各 IPv4 网卡分别发 SSDP M-SEARCH，解析描述含嵌入式设备、URLBase、
  相对 controlURL；`kototoro.dlna.targets` 可追加单播目标）、`DlnaRenderer`（SetAVTransportURI 带 DIDL-Lite、Play/Pause/Stop/Seek、
  GetPositionInfo/GetTransportInfo、RenderingControl SetVolume）、`DlnaStreamProxy`（0.0.0.0 中继：补站点请求头、透传 Range、HLS 主/子播放列表与
  `URI="…"`（密钥、map）改写经中继，附 DLNA 传输头）。控制器持有 `DesktopCast`（切集/返回即结束），投屏时本机暂停，结束后从渲染器位置续播；
  播放器顶栏“投屏”对话框搜索设备，投屏中控制条换成远端进度/暂停/±10s/结束。验证：`DlnaTest`（假渲染器与要求 Referer 的假站点）；
  窗口探针投屏到假渲染器（中继 200、约 4.9MB、动作序列、本机暂停、结束后从 12s 继续）。本次窗口探针截屏可用，视频区实测彩色像素。
- **随包分发**：`-PwithWindowsDistribution` 时 `prepareBundledThirdParty` 按固定版本与 SHA-256 取得 libmpv（zhongfly/mpv-winbuild LGPL 版
  2026-10-03，bsdtar 解 7z）与 realcugan/realesrgan ncnn-vulkan 官方包（可用 `-PlibmpvDirectory`/`-PncnnDirectory` 提供本地副本，否则 GitHub 资产 API
  下载并缓存于根 `build/third-party-downloads`），放入 `resources/mpv`、`resources/upscale/<tool>`（去掉示例媒体），写 `THIRD_PARTY_PLAYBACK.md`
  许可与来源说明。运行时优先使用随包程序。`--check-runtime` 新增播放器（lavfi 测试流）与两种 ncnn 超分自检；`windowsDistributionTest` 断言均来自
  resources。安装包体积约增加 200 MB（libmpv 101 MB、ncnn 程序与模型 102 MB）。

## 66. Windows 对齐 Android 截图风格与详情浮动预览

日期：2026-10-04。用户要求继续推进 Windows，并明确优先让整体视觉向仓库中的 Android 手机版
截图靠齐。实际查看 `metadata/en-US/images/phoneScreenshots/1-3.png`，同时对照 Android
`InterfaceStyleTokens`、`TabletLayout`、设计令牌及主题实现；参考官方 Reply 的约束驱动自适应布局。

- `DesktopTheme` 集中映射桌面颜色、排版和形状：柔和浅色画布、蓝色强调、主题派生顶部色调、
  28/36sp 一级标题、胶囊搜索框及按钮。所有原 Accent/Canvas/Ink/Muted 使用当前主题角色，
  深色不再继承硬编码浅色前景。没有增加依赖或改写 Android 主题。
- 80dp 导航槽保留，以圆角浮动侧栏表达 Android 浮动导航；收藏图标直接使用 Android 的心形
  vector，搜索图标仍走原构建转换。收藏/历史标题与搜索/筛选同排，书架保留 2:3 封面网格。
- “更多 → 外观”提供跟随系统、浅色、深色。使用现有偏好 store 的 `desktop_appearance/theme`；
  写入成功后才提交 UI 状态，未知值回退系统模式，没有新增设置数据库或 schema。
- 阅读器保留独立深色画布，控制组使用圆角浮动表面，强调色改为蓝色；不更换 reader-core、
  图片请求、阅读进度或相机控制路径。
- 从浏览/收藏/历史打开详情时记录原列表页面。1000dp 及以上窗口显示 380dp 右侧预览，窄窗口
  使用完整详情；完整详情按钮只改变呈现，不再抓取详情。两种呈现直接复用原详情/章节组件。
  原列表保持 composition，关闭、Esc 或宽屏遮罩返回原页，保留搜索草稿、筛选及网格位置；
  收藏/历史关闭时重新读取库快照以反映阅读与收藏变化。阅读后返回仍知道详情来自哪个列表。
- 详情柔化背景复用封面缓存，封面解码由同一 Compose helper 提供；仅模糊图片，不模糊或复制
  控件背景。失败与无封面时回退稳定主题画布，保留原封面重试行为。

验证增加真实 Windows JVM/UI 的浅色→深色→离线重启→浅色探针，核对画布像素、封面实际加载、
背景渲染、偏好持久化与零重复封面请求。宽/窄平板探针覆盖预览、完整详情、Esc/遮罩/关闭、
收藏与历史来源、搜索草稿及 37 项书库滚动后的原位置恢复，并继续原阅读器/书签/进度检查。
图片探针调整为明确验证预览与原列表同时存在的两个封面节点；未放宽封面请求计数或离线断言。
截图位于 `desktop-app/build/reports/desktop-smoke/appearance-*.png` 与 `tablet*.png`，使用自有夹具。

完整 `:desktop-app:test :desktop-runtime:test` 回归通过：127 项中 121 项执行成功、6 项可选外部
环境测试跳过，0 失败，日志 `build/windows-android-style-regression.log`。宽/窄截图及浅/深色截图
已实际查看；没有 Android 真机视觉对照。本轮复用原 Controller、详情组件、图标、偏好和封面缓存，
没有新增依赖、schema 或并行业务路径，遵循 KISS/DRY/SRP。

原默认 app image 被正在运行的旧窗口占用，常规打包失败；保留该窗口，使用 ignored build 中的
init script 将 desktop-app 输出隔离至 `desktop-app/build/windows-android-style`。独立输出打包成功，
分发门槛 1/1：移动中文目录后从随包 JVM 启动，schema/图片/备份/浏览器/播放器/超分自检及原生
窗口关闭全部通过。日志 `build/windows-android-style-package-isolated.log`，耗时 1 分 29 秒。
ZIP 520 个文件逐项核对大小与 SHA-256，与通过分发检查的 image 一致；便携包为
`Kototoro-Windows-Android-style-20261004.zip`，292852084 字节，SHA-256：
`A8AFD9DFF4C0B54E38A1E8B6A89AF8AFF893226A6AD73CAB5B05E4D40C7A0776`。
包含既有扩展仓库修复与随包播放器/超分工具；未执行 MSI 安装、发布、签名或 Git 提交。

仍待：Android Material3 Expressive 组件与主题偏好的直接共享、Backdrop 桌面渲染、完整动态背景
与玻璃折射、更多书架信息/标签/分组及高级阅读选项。本轮不声称完成 Android 全量 UI 或 KMP 迁移。

## 67. Android 平板 UI 直接共享（2026-10-04）

按用户确认的方向，新增 `core-ui` Android/JVM Compose Multiplatform 模块，`app` 与 `desktop-app`
共同消费从 Android 提取的 UI。共享代码不依赖应用、Context、Hilt、文件系统、平台 parser 或仓库；
平台适配器提供显示值、图片 Painter/封面渲染与操作回调。没有增加 iOS Compose UI 目标。

| 共享组件 | Android 保留的适配 | Windows 接入 |
|---|---|---|
| TabletNavigationRail | 原图标动画、徽标、重选、标题/继续阅读和系统 inset | 收藏/浏览/历史/更多动作及原 Android 图标 |
| TabletPreviewContent | Coil、源请求上下文、本地化/HTML/日期、玻璃或 Material 表面 | SourceContent、现有封面缓存及 Controller 回调 |
| TabletDetailsPanes | 原详情内容、章节面板、dock 与手机模式 | 原详情内层与章节列表，使用同一等宽双栏/16dp 间距 |
| TabletLayoutRules | 平板模式偏好与设备配置 | 窗口根约束提供实际完整宽度，共用 600/1000dp 分类及预览宽度 |
| 字体/形状/尺寸令牌 | 字体加载、风格偏好、颜色解析 | Material3 宿主，未迁移的 Material 2 屏幕映射同一语义角色 |

共享预览保留原模糊封面头部、标签/评分/状态、阅读/详情/收藏、展开简介及主分支最新/最初章节。
短窗口让头部与正文一起滚动，避免操作区不可达。原章节分支和升降序规则抽成泛型回调，避免两套策略；
阅读操作仍走 Controller，继续进度语义不变。Windows 忙碌时禁用操作，已收藏时禁止重复收藏。
共享 Kotlin 顶层文件采用独立 facade 名称，避免与保留 Android 适配器的同包文件产生重复 JVM 类。
新增桌面 Material3 依赖是共享既有组件所需；未升级 Kotlin/Compose 插件或改动数据库 schema。
组件负责呈现，平台负责数据/图片与执行操作，遵循 DRY/SRP，未复制仓库或新建业务状态机。

验证结果：

- `:core-ui:jvmTest :desktop-app:test` 成功：共 72 项，68 项通过、4 项可选环境测试跳过，0 失败。
  日志 `build/shared-tablet-desktop-regression.log`，完整 XML 保存在
  `desktop-app/build/reports/shared-tablet-full-regression/xml`。
- 共享章节测试覆盖空列表、短主分支、多翻译、升降序、无编号日期和未知分支。
  真实 Compose 预览在 380×640/380×300 验证封面头部高度、全部操作可滚动访问、章节回调及禁用状态；
  既有来源、收藏、历史、下载、阅读与离线重启回归继续通过。
- 完整窗口宽度修正后，平板/共享 UI 定向验证再次成功：920、999、1000、1260dp 窗口，预览/完整详情、
  搜索草稿、筛选、37 项网格原位置及阅读器/书签恢复；日志 `build/shared-tablet-window-boundaries.log`。
- Android `:app:compileDebugKotlin :app:checkDebugDuplicateClasses` 成功，5 分 45 秒；
  日志 `build/shared-tablet-android-compile.log`。已查看 Windows 浅/深色预览及详情双栏截图。
  初次探针按新预览更新封面数量检查，并限制测试子进程堆大小；请求数、离线和位置断言未放宽。
- 深色截图发现 Material 2 表面内的 Material3 标题/图标默认前景对比不足。原 Android 的 Material
  预览表面进一步提取为 `TabletPreviewSurface`，两端共用颜色/圆角/阴影与内容色。浅/深色与高/矮窗口
  再次验证全部操作，并新增深色章节标题前景的像素断言；外观离线重启、四种窗口宽度、共享章节测试和
  Android 编译/重复类一并再次通过。日志 `build/shared-tablet-final-surface-validation.log`，1 分 22 秒。
- 隔离输出 `desktop-app/build/windows-shared-tablet` 打包成功，分发检查 1/1：移至中文目录，从随包 JVM
  启动，schema/图片/备份/浏览器/播放器/超分自检及原生窗口关闭通过。
  最终日志 `build/windows-shared-tablet-package-final.log`，耗时 1 分 8 秒。
- ZIP 524 个文件逐项核对大小及 SHA-256，与通过检查的 image 一致。便携包
  `Kototoro-Windows-Shared-Tablet-20261004.zip`，297814347 字节，SHA-256：
  `07FE8457FD8A8C7FDC3E045D9111D249465BE36E8723BB07A4FFF88FB82F470B`。

仍待提取书库卡片、源管理屏幕、详情内层及阅读器控制栏。颜色/主题偏好、Backdrop 和动态背景仍由
平台适配；本轮不声称全部 Android UI 已共享。未执行 Git 提交、发布或安装。


## 68. 最新平板参考与进一步共享 UI（2026-10-04）

用户提供的订阅、浏览、历史、主页和收藏五张截图替代仓库的过时截图。主要视觉依据是窄侧栏、
顶部通栏搜索、紧凑封面网格、封面内渐变标题和徽标，以及主页推荐/历史/更新与快捷入口分区。
原图保存在 ignored 的 `build/latest-tablet-reference/`，不会进入 Git。颜色继续由主题解析。

本轮继续从现有 Android 组件提取实现，Android 与 Windows 都消费 `core-ui`，而不是另写桌面外观：

| 共享实现 | Android 适配 | Windows 适配 |
|---|---|---|
| TabletPosterCover、标题遮罩、书脊、封面边框顺序和尺寸规则 | Coil/快照缓存、共享转场坐标、选择态、四角徽标与进度插槽 | 既有封面缓存/详情动作、语言标签、紧凑网格 |
| TabletSourceTile 与 SourceQuickAccessMetrics | 实际源图标、本地化、置顶/空源徽标、TV 焦点和长按 | 来源选择/搜索、首字图标回退、生态/语言/内容类型说明 |
| SharedLiquidGlassSurface、调参模型、镜片限界和反馈规则 | Hilt/AppSettings、AMOLED/风格/窗口可用性及 Surface 回退 | 自有风格偏好、独立画布 Backdrop、搜索表面和 Material 回退 |
| Backdrop 2.0.0 commonMain/androidMain/skikoMain | 原平台支持检查及 Android actual | 发布版本原有 Skiko actual、RuntimeEffect/RuntimeShader |

Backdrop 的四处 expect/actual 已从相同 2.0.0 发布源码恢复，未升级上游版本；两处本地性能修补
原位保留。恢复的源码逐字节核对，哈希与位置记录在 `backdrop/UPSTREAM.md`。桌面默认 Material 3，
可在更多页切换 iOS 玻璃并持久化；只捕获控件绘制前的独立画布，避免玻璃采样自身。
共享模块不读取 Context/Hilt/文件/仓库，不接管平台业务状态；遵循 DRY/SRP/KISS。

仍待共享的是整页主页/订阅、主栏操作与完整筛选、详情内层、完整书库徽标/进度和阅读器控制；
Windows 的实际源 favicon 和动态作品背景也仍由后续平台适配推进。没有声称全量 Android UI 已迁移，
没有复制业务 ViewModel、修改 schema、提交或发布。

验证已通过（`build/shared-components-fixed-regression.log`，6 分 18 秒）：

- `:core-ui:jvmTest` 5/5；完整 `:desktop-app:test` 70 项中 66 项通过、4 项可选外部环境测试跳过。
- Android `:app:compileDebugKotlin :app:checkDebugDuplicateClasses` 通过。
- Android 既有 GlassTuningTest、GlassSurfacePolicyTest、SourceQuickAccessMetricsTest 共 34 项通过。
- 共 109 项，105 项通过、4 项跳过，0 失败。桌面完整 XML 已保存在
  `desktop-app/build/reports/shared-components-full-regression/xml`。
- 新增测试验证共用源卡片点击/长按/禁用、两种主题标题在封面范围内，以及 Skiko 模糊对条纹背景
  的像素变化。既有 UI 探针继续覆盖真实扩展、收藏、预览、阅读、离线封面与重启；风格切换及保存
  也进入真实重启检查。首轮发现的来源类型说明缺失和 import 清理漏掉 getValue 已修复，未降低断言。
- 实际查看了 `shared-components-light.png`、`parsers-write-sources.png` 和
  `appearance-write-light-library.png`；均为自有夹具。没有宣称 Android 真机视觉对照或全源兼容验证。


## 69. 共享卡片徽标与阅读进度（2026-10-04）

继续 §68 的“完整书库徽标/进度”。Android `KototoroContentCard` 中与平台无关的绘制提取到
`core-ui` 的 `SharedContentCardBadges.kt`（同包 `list.ui.compose`，独立 facade 名称）：

| 共享实现 | Android 保留的适配 | Windows 接入 |
|---|---|---|
| `ContentCardBadgeMetrics`/`contentCardBadgeMetricsFor` | 原调用方不变（同 FQN 迁移） | 按网格单元宽度缩放 |
| `ContentCardBadgePill` + `ContentCardBadgeTone`（中性/仅计数/仅 NSFW）+ `ContentCardBadgeText` | 徽标集合、tracker/来源图标、收藏/本地/置顶图标、NSFW 文案与内联颜色 | 语言徽标改用共享胶囊，与 Android 一样抬高到标题遮罩之上 |
| `ContentCardBottomProgressBar(percent, completed)` | 原 `ReadingProgress` 重载委托，保留 `isValid` 语义 | 书库/历史使用 `DesktopLibraryEntry.progressPercent`，完成判定复用 `ReadingProgress.isCompleted` |
| `ContentCardReadingProgressRing` | 原环形指示器委托，标签格式与 `ic_check` 由 Android 提供 | 暂未接入（桌面尚无进度样式偏好） |
| `rememberCoverRimBorderBrush(isIosStyle, isDark)` | 默认 `isSystemInDarkTheme()`，行为不变 | 使用桌面自有明暗偏好，替换原手写渐变 |

core-ui 仍不依赖 core-domain：共享组件只接收 percent/completed/label 等基础值。
独立 NSFW 徽标保留 iOS 描边，角标中“仅 NSFW”胶囊无描边，与原实现一致。
桌面未读/新章节计数尚无数据源（Android 来自追踪表），本轮未伪造计数，留待追踪接入。

验证（日志 `build/card-badges-*.log`）：

- `:core-ui:jvmTest :desktop-app:test` 76 项：72 项通过、4 项可选环境测试跳过、0 失败（4 分 10 秒）。
- 新增 `Android card badges and progress bar ...` 像素测试：Material/iOS 两种风格下，半进度用主题主色、
  未读部分为暗轨道、完成为 #34C759、0 进度不绘制，计数胶囊分别为主色 / #FF3B30。
- Android `:app:compileDebugKotlin :app:checkDebugDuplicateClasses` 通过（4 分 29 秒）。
- 实际查看了 `cover-read-history.png` 与 `appearance-write-dark-library.png`：历史卡片底部出现完成态
  绿色进度条，语言徽标为共享胶囊。未重新打包，未提交。

仍待共享：主栏顶栏/快速筛选 chip 行、主页与订阅整页、详情内层、阅读器控制栏。


## 70. 详情章节分组：分支与卷（2026-10-04）

用户反馈 Windows 详情页章节无法识别分组、直接平铺。原因：桌面 `ChapterList` 只把 `content.chapters`
平铺并搜索，未使用 `SourceChapter` 已有的 `branch`/`volume`/`scanlator`；Android 的分组规则散落在
`Content.getPreferredBranch`、`ChaptersPagesViewModel.quickFilter`、`ChaptersMapper.withVolumeHeaders`
与 `ChaptersScreen` 的私有标题中，未共享。

本轮提取到 `core-ui` 的 `core.ui.chapters`（泛型，不依赖平台章节模型）：

| 共享实现 | Android 委托 | Windows 接入 |
|---|---|---|
| `resolvePreferredChapterBranch`：历史章节分支 → 唯一分支 → 语言匹配（最大）→ 最大分支 | `getPreferredBranch` 仅提供 `LocaleListCompat` 语言列表与日志 | 打开详情时按历史进度与 JVM 显示语言选择，存入 `DesktopAppState.chapterBranch` |
| `chapterBranchOptions`（>1 分支才出现，比较器异常时保持原序）、`chaptersOfBranch`（失效分支回落最大分支） | 分支 chip 用 `LocaleStringComparator` | `Collator` 排序；无历史时“开始阅读”从选中分支第一章开始 |
| `withVolumeSections`/`shouldShowVolumeHeaders`：卷号变化或新组名插入标题 | 非 EPUB 路径委托，文案仍用 `volume_`/`volume_unknown` | “第 N 卷”/“未知卷”与 Android 中文资源一致 |
| `ChapterSectionHeader`、`ChapterBranchChips`、`chapterBranchChipLabel`（“分支 · 数量”） | TV 焦点作为外部 modifier；折叠图标经 `rememberVectorPainter` | 双栏与单栏详情都显示分支 chip 与卷标题 |

桌面顺序与 Android 一致：选分支 → 正/倒序 → 搜索 → 在剩余结果上插入卷标题。阅读器内上一/下一章
原本已按分支导航（`SourceChapterNavigation`），未改。EPUB 卷分组、合并重复章节、隐藏已读等 Android
专属选项未迁移。

验证（日志 `build/chapter-groups-*.log`）：

- 新增 `ChapterGroupingTest` 4 项（首选分支优先级、选项/回落、比较器异常、卷与组名标题），以及桌面
  `details chapters are grouped by branch and volume like Android`（真实 `desktopChapterList`、倒序、搜索保留
  卷标题、失效分支回落、单分支不显示 chip、点击 chip 切换分支与标题）。
- Android `:app:compileDebugKotlin` 通过（`checkDebugDuplicateClasses` 为 UP-TO-DATE），既有
  `ChaptersMapperVolumeHeaderTest` 2/2。
- `:core-ui:jvmTest :desktop-app:test` 81 项：77 通过、4 项可选环境测试跳过、0 失败（4 分 2 秒）。
- 未用真实多分支来源做端到端检查，未打包、未提交。


## 71. 共享顶栏标题、搜索胶囊与分类/筛选栏（2026-10-04）

`KototoroTopBar` 与玻璃表面、菜单、偏好深度耦合，未整体迁移；平台无关部分提取到 `core-ui` 的
`core.ui.topbar/TabletTopBar.kt`，胶囊背景经 `RailSurface` 插槽由宿主提供：

| 共享实现 | Android 委托 | Windows 接入 |
|---|---|---|
| `TopBarTitleBlock`（标题/副标题） | 展开与紧凑两处标题 | 收藏/历史页标题 |
| `TopBarSearchPillContent` | 展开搜索胶囊内容，TV 焦点作为外部 modifier | 未用（桌面保留可直接输入的搜索框） |
| `TopBarTabsRail` + `TopBarTabItem`（居中滚动、下划线指示、pager 位置插值） | `CompactTopBarTabsRail` 委托；`CompactTopBarTabItem` 改为 typealias | 收藏分类从筛选抽屉单选改为顶部分类栏（全部 + 分类），与最新平板截图一致 |
| `TopBarFilterRail<T>`（选中项可见、远处图标延迟加载标志） | `CompactTopBarFilterRail` 委托，来源图标仍由 Android 绘制 | 暂未接入（桌面快速筛选待迁移） |
| `compactRailEdgeFade`、`EnsureRailItemFullyVisible` | 内联分类栏继续使用 | 随共享栏使用 |

发现并修复一个自持重组循环：筛选栏以 `remember(listState.layoutInfo)` 计算可见区间，每次测量都会写入
新的 `layoutInfo`（neverEqual），导致重组→测量→重组永不停止；桌面 UI 测试因此等不到空闲而挂起。
改为 `derivedStateOf`，只在区间变化时重组。Android 筛选栏委托共享实现，同样获得修复（Android 侧未做
gfxinfo 帧数对比）。新测试加 60 秒超时，避免再次挂起整个构建。

验证（日志 `build/topbar-*.log`）：

- 新增 `Android top bar rails select tabs and filters on desktop`：标题/副标题、两条栏的胶囊插槽、点击标签、
  远处标签被滚动到可见、筛选项切换与图标插槽。
- `DesktopTabletProbe` 改为关闭抽屉后点选顶部分类标签，再在抽屉中选来源，筛选计数仍为 2。
- Android `:app:compileDebugKotlin` 通过；`:core-ui:jvmTest :desktop-app:test` 82 项：78 通过、4 项跳过、
  0 失败（4 分 5 秒）。已查看 `tablet-library.png`：标题、搜索胶囊与带下划线的分类栏。未打包、未提交。


## 72. 内容源类型 / 内容类型过滤与 Space 现状（2026-10-04）

用户要求：所有页面都应有 Android 的内容源类型过滤；内容类型过滤取决于 Space 是否开启。核查结果：

- Android 规则（`KototoroApp`）：源类型过滤 = 页面支持 && 设置开启；内容类型过滤 = 页面支持 && 设置开启 &&
  `!spaceUiState.switcherEnabled`（开启 Space 切换器后由 Space 决定内容范围，类型筛选隐藏）。
- `SourceTag` 枚举与匹配已在 `core-domain`，但 Windows 未使用；7 个 Android 页面各自重复同一段多选切换。
- Space：Android `space/` 37 个文件 + 其余引用，全部在 `app`；仅实体/DAO（`core-db` 7 个文件）共享。
  domain 接口/模型基本无平台依赖，data 实现依赖 Hilt/AppSettings，`SpaceSwitcher` 等 UI 依赖 Android。
  Windows 未实现、未共享 Space。

本轮实现：

| 位置 | 内容 |
|---|---|
| `core-domain` `SourceTag` | `toggle`（空→清空、已选→移除、否则加入）、`menuOrder`（已选在前）、`accepts`（OR，未知来源不匹配）、`matchesOrigin`（`matches` 委托，行为不变） |
| Android | 6 处页面切换与 `SourceTagDropdown` 排序改为委托（收藏页的 clear/set 变体保留） |
| Windows | `DesktopSourceFilter`：Kototoro/Kotatsu/UMA→内置（与 Android 插件归 NATIVE 一致）、Mihon、Aniyomi、Tsundoku；内容类型复用 `BrowseGroupTab.matchesContentType`；无对应已装来源的选项禁用 |
| Windows 页面 | 浏览来源网格与侧栏、收藏、历史各自持有过滤（与 Android 按页一致）；右侧胶囊放内容类型菜单与源类型菜单，单选时显示该源类型图标 |
| 图标 | 桌面图标转换支持 `pivot/scale` 与 `evenOdd`，复用 Android `ic_source_*`、`ic_filter_menu`、`ic_check` |

Windows 只列出本机生态可能满足的 4 种源类型（Legado/TVBox/IReader/Cloudstream/LNReader 在 Windows 永远为空）。
因 Windows 尚无 Space，内容类型过滤始终显示；接入 Space 后按 Android 规则在切换器开启时隐藏。
收藏/历史中来自未安装来源的作品无法判定源类型，启用源类型过滤时不显示。

验证（日志 `build/source-filter-*.log`）：`SourceTagTest` 新增 3 项；新增 `DesktopSourceFilterTest` 3 项（生态映射、
可用选项、OR 组合、书库按已装生态过滤、菜单交互与禁用项）；Android `:app:compileDebugKotlin` 通过；
`:core-domain:jvmTest` 119、`:core-ui:jvmTest` 9、`:desktop-app:test` 76（4 跳过）全部通过（4 分 6 秒）。
已查看 `parsers-write-sources.png`。未打包、未提交。

Space 后续建议：先把 domain 模型/接口与 `SpaceContentPolicy` 移到 `core-domain`，基于 `core-db` 实现平台无关仓库，
偏好经平台适配；再抽 `SpaceSwitcher` 去除 Android 依赖后共享。


## 73. 主页与订阅页（2026-10-04）

Windows 原先没有主页、订阅页，也没有新章节追踪。本轮按“数据规则下沉 + 展示组件共享”实现：

**数据层（core-domain，Android 委托）**

- `tracker/domain/TrackRules.kt`：`compareTrackedChapters`（空追踪/无新章/最后章节消失/有新章四种情况）、
  `afterSuccessfulCheck`/`afterFailedCheck`（追踪行更新）、`trackedLastChapterDate`、`trackLogChapters`。
  `CheckNewChaptersUseCase.compare` 与 `TrackingRepository.mergeWith`/日志拼接改为委托。
- `tracker/domain/feed/FeedSnapshotAssembler`：Android `FeedSnapshotStore.buildSnapshot/observe` 整体下沉，
  来源分组/来源归属由平台注入（Android 用 `SourceGroupManager`，Windows 用已装生态与内容类型）。
- Windows `desktop-runtime/DesktopTracker`：在共享 `tracks`/`track_logs` 上实现 Android 默认范围（开启追踪的收藏分类），
  分支选择同 Android（历史章节 → 上次最后章节 → 首选分支），拉取详情以追踪作品 id 保存（`copy(id = ownerId)`），
  任何异常记为失败而不中断整批；`markRead` 清计数与未读日志。Windows 新建默认收藏分类改为 `track = true`
  （与 Android 新建分类一致）；已有库在订阅页提示“全部开启”。

**共享展示（core-ui，Android 委托）**

| 组件 | Android 保留 |
|---|---|
| `core.ui.feed.FeedTimelineCard`（时间线、节点、日期标签、继续阅读按钮） | Coil 封面、共享元素、NSFW 徽标、快速滚动避让 |
| `core.ui.feed.UpdatedContentCarousel<T>` + 轮播几何（焦点宽度、距离衰减、倾斜形状） | 入场动画（`itemWrapper`）、Coil、角标、共享元素 |
| `core.ui.home.HomeSectionHeader`、`HomeQuickActionsGrid`（含配色规则）、`HomeListRailRow` | TV 焦点、iOS 玻璃/作品背景配色、封面角标/进度 |
| `HomeHeroText`、`HomeBadge`、`HomeHeroArtworkScrim`、`HeroPagerIndicator`（移入 core-ui 同包） | 多种 hero 版式、全景背景、自动轮播 |
| `buildHomeHeroEntries<C>`、`homeBalancedGridColumns`、`homeHeroCardWidth`、`homeCountLabel` | 原内部函数委托（测试不变） |

**Windows 页面**：导航按 Android 顺序加入“主页”“订阅”，订阅图标显示有更新作品数。主页：hero（继续阅读/历史/更新）、
历史栏、更新列表栏、右侧快捷入口（收藏/历史/订阅/下载/随机/拓展/备份/设置）；订阅页：检查更新、更新内容轮播、
`FeedDeriver` 派生的时间线。两页都有内容源类型/内容类型过滤；从两页打开作品时以浮动预览显示并在关闭后刷新。

未迁移：推荐（依赖 Android 推荐生成）、主页其他 hero 版式与全景背景、订阅页分类 chip 与“全部更新”页、阅读时自动调整
计数（Android `CheckNewChaptersUseCase(manga, chapterId)`）、后台定时检查与通知。日期分组标签为 Windows 本地实现
（Android 的 `DateTimeAgo` 依赖资源）。Windows 启动页仍为“浏览”。

验证（日志 `build/home-feed-*.log`）：

- 新增 `TrackRulesTest` 3 项、`HomeRulesTest` 3 项、`DesktopTrackerTest` 2 项（真实 SQLite：建立基线、发现新章节、
  写日志、订阅快照、失败保留计数、标记已读、关闭/开启追踪、按阅读分支计数）。
- 平板探针（四种窗口宽度）新增：订阅页检查更新、模拟上次只见第一章后发现新章节、轮播/时间线/导航徽标、
  主页 hero/历史/更新/快捷入口、从更新打开作品后计数清零、快捷入口跳转。
- Android `:app:compileDebugKotlin` 通过；相关 Android 单元测试 13 项通过。
- `:core-domain:jvmTest` 122、`:core-ui:jvmTest` 12、`:desktop-runtime:test` 63（2 跳过）、`:desktop-app:test` 76（4 跳过），
  0 失败（5 分 12 秒）。已查看 `tablet-home.png`、`tablet-feed.png`（夹具无封面图，显示首字母占位）。未打包、未提交。


## 74. 推荐（2026-10-04）

Android 推荐由 `SuggestionsWorker` 每 6 小时生成：最近 20 条历史 + 20 部收藏为种子，取白名单 + 最常见 10 个标签；
每个来源按优先排序（UPDATED/NEWEST/POPULARITY/RATING）并用首个匹配标签筛选取列表，空则回退无筛选；清洗后每源最多
20 条；按标签相关度排序、去重、优先来源与每源 12 条均衡，最多 160 条，名次编码进 relevance 存入共享 `suggestions` 表。

**core-domain（`suggestions/domain`，Android 委托）**：`SourceBalancedSelector`、`SuggestionSourceCollector`
（原样迁移，去掉 Android 扩展依赖）、`SuggestionRules`（`suggestionSeedTags`/`mostFrequent`、`suggestionRelevance`、
`pickSuggestionSortOrder`、`pickSuggestionTag`、`cleanSuggestionList`、`rankSuggestions`、`SuggestionTagBlacklist`、
`SuggestionLimits`）。core-domain 带 iOS 目标而 `almostEquals` 只在 JVM/Android，故模糊匹配以 `SuggestionTagMatcher`
注入（两端都传 `almostEquals(…, 0.4)`）。Android `SuggestionsWorker` 的种子、选序、选标签、清洗、排名改为委托，
`TagsBlacklist` 内部用共享实现；通知、调度、设置读取保留在 Android。

行为差异：无标签作品的相关度由 Android 的 NaN（会被排到最前）改为 0；种子标签并列时按首次出现排序
（Android 原 `ArrayMap` 按哈希序）。两者都在共享实现中，Android 一并采用。

**Windows**：`desktop-runtime/DesktopSuggestions` 用已安装来源与共享规则生成；`DesktopLibrary.replaceSuggestions`
单事务整体替换（同 Android `SuggestionRepository.replace`），已在库的作品只建立关联、不用列表数据覆盖详情，
身份冲突跳过。主页新增“推荐”栏（空时“生成推荐”，标题旁刷新）与 hero 推荐页，并受页面过滤器约束。
未做：定时后台生成与通知、推荐设置页（排除来源/标签黑白名单/排除 NSFW 目前用默认值）、推荐列表页。

验证（日志 `build/suggest-*.log`）：新增 `SuggestionRulesTest` 5 项、`DesktopSuggestionsTest` 2 项（假来源运行时 + 真实
SQLite：种子标签发给来源、无标签来源不筛选、失败来源跳过、空标题剔除、相关度排序、收藏详情不被覆盖、排除来源/NSFW、
每源均衡）；平板探针四种宽度新增主页“生成推荐”。Android 编译与原有 `SourceBalancedSelectorTest`/
`SuggestionSourceCollectorTest` 9 项通过；`:core-domain:compileCommonMainKotlinMetadata` 通过。
`:core-domain:jvmTest` 127、`:core-ui:jvmTest` 12、`:desktop-runtime:test` 65（2 跳过）、`:desktop-app:test` 76（4 跳过），0 失败。
未打包、未提交。


## 75. 推荐/更新检查设置与后台定时运行（2026-10-04）

**共享规则（core-domain，Android 委托）**

- `trackerCheckIntervalHours(trackCount, batchSize, frequency)`：Android `TrackWorker.Scheduler` 原公式
  （`18 / 批次数` 整除后除以频率、四舍五入、至少 2 小时；频率 ≤0 为手动），Android 调度改为调用。
- `SUGGESTIONS_INTERVAL_HOURS = 6`（Android 推荐周期任务改用）、`parseSuggestionTags`（Android `AppSettings`
  的排除/偏好标签解析改用）。

**Windows**

- `DesktopSuggestionSettings`/`DesktopTrackerSettings`：沿用 Android 键名与默认值（`suggestions` 默认关、
  `suggestions_exclude_nsfw`、`suggestions_exclude_tags`/`_preferred_tags` 逗号文本、`_preferred_sources`/
  `_excluded_sources` 集合、`tracker_enabled` 默认开、`tracker_freq` 默认 1），存于 `desktop_background` 偏好。
- 更多页新增“推荐”（启用、排除成人内容、排除/偏好标签、按来源优先/排除）与“更新检查”（启用、Android 的
  手动/低频/默认/高频 = -1/0.4/1/2）。
- 后台：`dueBackgroundTasks` 判断到期（Windows 每次检查全部追踪，故完整检查周期 = 18 h / 频率；推荐每 6 h），
  `startBackgroundWork` 在应用打开期间每 15 分钟评估一次（启动后 1 分钟首查），不进入 UI 动作队列；手动与后台
  同一任务经同一互斥锁串行。上次运行时间持久化。只有正式入口启动后台，探针显式调用 `runDueBackgroundWork`。
- 主页：推荐默认关闭（同 Android），空状态按钮为“开启推荐”（开启并立即生成）或“生成推荐”。

未做：Wi-Fi 限定、通知（推荐/新章节）、应用未打开时的系统级计划任务、追踪范围“历史”选项与按分类开关追踪的界面。

验证：`TrackRulesTest` 新增间隔公式（含 Android 整除细节）、`SuggestionRulesTest` 新增标签解析、
`DesktopBackgroundSettingsTest`（键名/默认值往返）、`DesktopBackgroundWorkTest`（到期判断）；平板探针新增
“开启推荐”持久化、频率选择持久化、手动运行后无任务到期、10 小时后两项任务到期并执行。`DesktopBrowserProbe`
点击“浏览器调试”前补滚动（更多页变长）。Android 编译与推荐单元测试 9 项通过；`compileCommonMainKotlinMetadata` 通过；
`:core-domain:jvmTest` 129、`:core-ui:jvmTest` 12、`:desktop-runtime:test` 66（2 跳过）、`:desktop-app:test` 77（4 跳过），0 失败。
未打包、未提交。

## 76. 共享 Mihon 默认 Cloudflare 求解器（2026-10-04）

新增纯 JVM 模块 `:core-cloudflare`（OkHttp、parser-api 由使用方提供，compileOnly），Android 与 Windows 共用：

- 从 Android 迁入（包名不变，Android 导入无改动）：`CloudflareHostCooldown`（单个 host 失败冷却 30 s，测试时钟
  `nowMillis` 改为 public）、`CloudflareSolveCoordinator`（同一 host 只跑一次求解；最后一个等待方离开时取消求解）、
  `CloudFlareDetection.kt`（`CF_STATE_JS`、`parseCloudFlarePageState` 等，`internal` 改为 public）。
- `cloudflare/ClearanceSolving.kt`：Mihon 默认方案的规则——触发条件 `isMihonCloudflareChallenge`（Cloudflare 服务器返回
  403/503，或带 `cf-mitigated: challenge`）、超时 `CLEARANCE_SOLVE_TIMEOUT_MS = 30 s`、`cf_clearance` 作为成功信号、
  浏览器可接受请求头的过滤（`isBrowserRequestHeaderSafe`、`Headers.safeForBrowser`）、`ClearanceSolveTracker`
  （根据事件判定：主框架 403/503 则等待 JS，其他错误或页面无挑战加载完成则失败；Windows 用页面状态轮询判定：
  勾选式挑战持续超过 8 s 才判失败，因为 Turnstile 常常只是短暂出现，硬拦截立即失败）、`ClearanceSolver` 接口，
  以及 `Interceptor.Chain.solveClearanceAndRetry`（经协调器求解后只重试一次；调用方取消时停止等待）。

**Android（委托）**：`WebViewClearanceSolver` 实现 `ClearanceSolver`，改用共享的请求头过滤、超时、cookie 名和
`ClearanceSolveTracker` 判定（删去私有副本）；`CloudFlareInterceptor` 与 `KotoNetworkHelper` 的 MIHON 分支中重复的
“求解后重试”代码改用 `solveClearanceAndRetry`。Cloudstream 拦截器重试的是改写过的请求，保持原样（协调器仍是共享的）。

**Windows**：`DesktopBrowserChallenges` 实现 `ClearanceSolver`，用 WebView2 隐藏窗口求解：把 SDK cookie 推入浏览器，
删除旧的 `cf_clearance`，以安全请求头导航（允许 HTTP 错误页），每 500 ms 轮询新 `cf_clearance` 和 `CF_STATE_JS`，
成功后把 cookie 拉回 SDK 存储。`DesktopChallengeInterceptor` 改用 Mihon 触发规则：先自动求解并重试一次；只有带
`cf-mitigated: challenge` 的挑战在自动求解失败后才弹出原有的人工验证窗口；普通 Cloudflare 403/503 求解不了时原样
返回响应（保留原响应体，不会误弹窗口）。

验证：
- 新增 `ClearanceSolvingTest`（触发条件、请求头、事件和轮询判定、MockWebServer 重试、失败冷却、取消），
  `CloudflareSolveCoordinatorTest` 和 `CloudFlareDetectionTest` 随代码迁入，`:core-cloudflare:test` 17 项全部通过。
- 桌面拦截器测试新增“明确挑战离屏解决后重试、不打扰用户”。
- 新增真实 WebView2 探针 `DesktopClearanceSolveTest`：本地模拟托管挑战（403 + `Server: cloudflare` +
  `#challenge-running`，页面脚本写入 `cf_clearance` 后重载），约 3 s 内在隐藏窗口自动通过，SDK 请求返回内容，
  没有人工弹窗；持续的勾选式挑战约 8 s 后转为人工验证。
- `DesktopChallengeProbe` 的请求计数改为包含一次隐藏求解。
- 结果：Android `compileDebugKotlin` 通过，Android Cloudflare 相关单元测试 16 项通过；
  `:mihon-desktop-compat:test` 34、`:core-domain:jvmTest` 129、`:core-ui:jvmTest` 12、`:desktop-runtime:test` 66（2 跳过）、
  `:desktop-app:test` 77（4 跳过），全部 0 失败；`compileCommonMainKotlinMetadata` 通过。

kototoro/kotatsu/UMA parser 插件复用平台的共享 HTTP 客户端，因此同样经过这条自动求解链路。

未做：Windows 的 TRANSPORT/MANUAL 策略选项（目前固定为 MIHON 加人工兜底），以及用真实 Cloudflare 站点做实测。未打包、未提交。

## 77. 浏览页返回来源列表；所有来源的 User-Agent 设置（2026-10-04）

**浏览页返回**：进入某个来源后原先无法回到来源网格（导航栏“浏览”只会重新加载当前来源）。现在：
- 来源结果页标题前新增返回按钮（`source-back`，复用 Android 的 `ic_arrow_forward` 并水平镜像）；
- 停留在来源内时再次点击导航栏“浏览”，同样回到来源网格（与 Android 重复点击标签的行为一致）；
- 两者都调用 `DesktopController.exitSource()`，它会清空所选来源、列表、查询、排序和筛选。

**User-Agent**：parser 来源（kototoro/kotatsu/UMA）通过 `ConfigKey.UserAgent` 本来就有这一项。Mihon/Tsundoku/Aniyomi
来源的扩展不提供 UA 设置，因此在 source-host 新增 `HostUserAgent`：
- 存储与 Android 来源设置一致，写在该来源的 `source_<id>` 偏好中，键为 `user_agent`；
- 生效方式：用来源自己的 `headersBuilder()` 重建默认请求头并替换 User-Agent，再替换 `HttpSource.headers` 的 lazy
  委托字段（`lazyOf` 由扩展类加载器中的 Kotlin 运行时创建）。之后来源基于默认请求头构造的所有请求（包括图片）都
  带上新 UA；留空则恢复来源默认值。
- 每次调用来源前检查一次，值有变化才重新安装。若来源自己覆盖了 `headers`，该行置灰并提示无法覆盖。
- 请求头值不合法（含控制字符）时直接拒绝，不写入存储。
- `isPreferencesSupported` 改为“可配置的扩展 或 任意 HTTP 来源”，因此没有自有设置的 HTTP 来源也会显示“源设置”。
- 协议层把 `host:user_agent` 这一行追加在扩展自有设置之后（保持扩展节点的位置不变），桌面界面渲染时把 `host:`
  行排到最前。

验证：
- 真实 SDK fixture（`OfflineSource` 的 `imageRequest` 改为以 `getHeaders()` 为基础，与 Mihon 默认实现一致）：
  `DesktopPlatformProbe` 覆盖 UA 行存在、非法值被拒绝、保存后页面请求头的 User-Agent 生效，第二个进程中设置仍保留
  且仍生效。
- `MihonDesktopPlatformTest` 的 CLI 断言更新为 `[域名设置, User-Agent]`；`DesktopAppProbe` 改为按 `host:` 前缀区分。
- `DesktopTabletProbe` 新增返回按钮和重复点击“浏览”的检查。
- 结果：`:source-host:test` 86、`:mihon-desktop-compat:test` 34、`:desktop-app:test` 77（4 跳过），全部 0 失败。

未做：Android 侧 Mihon/Aniyomi 来源同样没有 UA 设置，可改用同一套键名实现；UA 预设下拉（Android 的
`userAgentPresets`）也尚未共享。未打包、未提交。

## 78. Windows 支持 Cloudstream（2026-10-04）

官方 Cloudstream 的 `library` 模块有 JVM 目标（Android 端本来就用其 `library:jvmJar`），插件 `.cs3` 是 d8 产物
（`classes.dex` + `manifest.json`）。Windows 端复用三样已有能力：dex 转换（`:dex-convert`）、Mihon 用的 Android
兼容运行时（AndroidCompat 提供 Context/SharedPreferences/Log 等）、平台共享 HTTP 客户端（Cookie 与自动 CF 求解器）。

**共享（新增纯 JVM 模块 `:cloudstream-shared`，Android 与 Windows 共用）**
- 从 Android 迁入，包名不变：`CloudstreamSource`、元数据编解码 `CloudstreamMetadata`、`CloudstreamLinkSessionCache`、
  `CloudstreamApiGateway`、插件兼容性检查，以及 YouTube 提取器存根。
- 新增 `CloudstreamCatalog`：原 `CloudstreamContentRepository` 的完整逻辑，包括首页分区聚合与按分区筛选、搜索分页
  终止、详情与剧集映射（电影/直播/种子/剧集/动漫按配音分支）、`loadLinks` 链接与字幕事件、链接会话缓存、
  插件 fallback 后的挑战重试、推荐。
- 新增 `CloudstreamPlatform` 接口（日志、按源请求上下文、loadLinks 挑战透传、挑战识别与解决、诊断），以及
  `CloudstreamRequestScope`（源上下文 ThreadLocal，缺省 UA / Referer / POST Origin 头）。
- Android 委托：`CloudstreamContentRepository` 只剩缓存、Android 日志、CF 异常与 WebView 解析（TRANSPORT）、相关内容
  搜索兜底；`CloudstreamRequestContext` 的源上下文与缺省头改用共享作用域，只保留 CF 策略标签和调试日志。

**Windows（新增 `:cloudstream-desktop`，随兼容层开启时编译）**
- 宿主垫片（插件链接的 `com.lagradost.*` 宿主类）：
  - 与 Android 同一份源码，构建时从 app 拷入，直接对 AndroidCompat 编译：`Plugin`、`AcraApplication`、
    `CommonActivity`、`ContextHelper`、`VideoClickAction`/`Holder`、`TextUtil`（UiText）、`DataStore`。
    `DataStore` 在拷贝时去掉 androidx 的两处调用，`edit {}` 由桌面同包函数提供。
  - 只有 `CloudStreamApp` 是桌面版：成员与 Android 相同，去掉基于 Fragment 的浏览器方法，改用系统浏览器。
- 运行库：官方 jvmJar 去掉宿主替换的 `Youtube*` 和 `ContextHelper_jvmKt`（与 Android 的处理一致），依赖版本沿用 app 锁定的
  NiceHttp、jackson、jsoup、rhino、ktor-http、datetime、io、cryptography、fuzzywuzzy、gson。
- `CloudstreamPluginRegistry`：
  - 读取 manifest，做兼容性检查；把 dex 转成 jar（按哈希缓存），每个插件用独立 URLClassLoader，父加载器包含官方库、
    垫片和兼容运行时。
  - 调用 `Plugin.load(Context)`，Context 用兼容运行时的。从 `APIHolder` 收集该插件注册的 provider 并 `init`；卸载时
    `beforeUnload`，移除 provider 映射和提取器。
  - 全局环境：`CloudStreamApp.context`，`app.baseClient` 使用平台共享客户端并在最前面加上源请求头拦截器。
- `CloudstreamSourceRuntime`：按页序号翻页，排序只有 RELEVANCE，首页分区作为互斥标签组，封面带 provider 的海报请求头；
  通过公开后的 parser-host 模型映射接入 `:core-source` 协议。
- 会话与界面：
  - 新增 `SourceEcosystem.CLOUDSTREAM`（来源过滤：Cloudstream 标签 / 视频分组）。
  - 导入 `.cs3`（文件选择器接受 jar/apk/cs3），按哈希受管存放，安装记录前缀 `cloudstream:`，启动时恢复；已安装列表、
    卸载、产物清理都覆盖 `.cs3`。
  - Cloudstream 仓库：`repo.json`（pluginLists）或 `plugins.json` 映射为扩展目录（下架状态的插件被过滤，按插件版本
    检查更新）；raw.githubusercontent 链接自动改走 jsDelivr。
  - 内容类型为 VIDEO，剧集直接进入现有 libmpv 播放器，线路带请求头和外挂字幕。

验证：
- `:cloudstream-desktop:test`：
  - 自建插件经 Gradle 任务用 SDK d8 打成 `.cs3`，在真实兼容运行时与共享客户端里覆盖：首页两个分区聚合、按分区第 2 页
    与终止、搜索、电影/剧集详情、剧集链接（m3u8、quality、自定义头、referer、UA）与字幕、卸载。
  - 官方仓库的 3 个真实插件（InternetArchive、Dailymotion、Invidious，经 jsDelivr 下载）全部加载并注册 provider。
  - 联网往返（`-PcloudstreamOnline`）因本机到这些站点都不通（archive.org、dailymotion 超时，代理未开）未能执行。
- `DesktopRepositoriesTest` 新增 Cloudstream 仓库读取与安装、jsDelivr 改写两项。
- `DesktopAppTest` 新增 Cloudstream UI 端到端（写入轮：导入→浏览页源网格→首页分区→搜索→详情→进入播放状态并带
  请求头与字幕；读取轮：重启后恢复，再卸载）。
- Android：`compileDebugKotlin` 通过，Cloudstream 相关单元测试 124 项通过。
- 全量回归：
  - `compileCommonMainKotlinMetadata` 通过；
  - core-source 52、core-domain 129、core-ui 12、core-cloudflare 17、cloudstream-desktop 2、source-host 86、
    parser-host 23（2 跳过）、dex-convert 14（2 跳过）、mihon-desktop-compat 34、desktop-runtime 68（2 跳过）、
    desktop-app 78（4 跳过），全部 0 失败；
  - `DesktopSourceFilterTest` 的期望改为包含 Cloudstream 来源标签。

未做：
- 插件设置页（`openSettings`，需要 Android UI）和 `requiresResources` 插件（没有 Resources）。
- Windows 上依赖 WebView 的 `WebViewResolver`：仍是官方的 JVM 空实现，后续可接 WebView2。
- 同步追踪（AccountManager/AniList 垫片）。
- 真实站点联网验证。
未打包、未提交。

## 79. 阅读器：预加载、拖拽翻页、翻页动画（第一阶段，2026-10-05）

原状：Windows 分页阅读器只渲染当前页组，翻页时同步加载下一页（期间界面忙碌），没有预加载、拖拽翻页和动画；鼠标滚轮
只能平移。Android 的分页阅读器建立在 reader-core 之上：所有页组排成一条，用同一个滚动偏移驱动；`PagedDragState`
负责平移与翻页的衔接，`PagedSnapResolver` 负责吸附，过渡效果由 `PagedTransitionResolver` 和场景过渡渲染器给出，
预加载窗口由 `PagedSceneResourceWindowStrategy` 规划。Windows 本来就依赖 reader-core，这一轮改为走同一条路径。

**共享（迁入 core-ui，包名不变，Android 导入不改）**：`ReaderAnimation`（无 / 滑动 / 覆盖 / 仿真）、`TapGridArea`、
`ComposeReaderPageAnimation`（翻页变换、卷页几何、`composeReaderPageCurl`、卷页阴影）、`ComposeReaderTapGrid`
（九宫格点击识别）、`ScenePageTransitionRenderer`（滑动 / 覆盖 / 卷页的单页组变换和覆盖模式固定页）。
core-ui 的 commonMain 新增依赖 reader-core（Android 目标能正常消费）。

**Windows 分页阅读器（`DesktopReaderCanvas` 重写）**
- 当前页组和前后相邻页组排在一条上，由 `travel`（以页组为单位）平移。每个页组按 Android 的方式应用共享过渡：
  卷页沿用 `composeReaderPageCurl`，覆盖模式用固定页偏移，加上 zIndex、透明度和翻开页阴影。每个页组单独裁剪，
  这样宽页或原始尺寸溢出时不会画到当前页上（与 Android 的裁剪一致）。
- 鼠标拖拽：用 `PagedDragState` 处理缩放平移与翻页的衔接（放大时先平移到边缘再翻页），松手时按 reader-core 的阈值
  和速度吸附；未过阈值回弹；在章节首尾继续拖动会进入相邻章节（自动跨章开启时）；双指捏合仍是缩放。
- 键盘、按钮、点击区和滚轮翻页都有动画：键盘和按钮在页组切换后从原位置滑入；点击区和滚轮先滑出再提交。
  动画时长与 Android 相同（280 ms）。
- 点击区按 Android 默认九宫格：左列和上中为上一页，右列和下中为下一页，中间切换工具栏；双击缩放保留。
- 滚轮：页面溢出时平移，到边缘或页面完全可见时翻页；Ctrl+滚轮缩放。
- 设置：新增“翻页动画”（无 / 滑动 / 覆盖 / 仿真翻页），默认与 Android 一样是滑动，持久化；快捷键说明同步更新。

**预加载**
- 分页模式：每次页组稳定后，按 `PagedSceneResourceWindowStrategy(lookahead 2, prepareAdjacentSlots)` 在后台加载前后
  两个页组，近的优先。后台加载不经过动作锁，不会让翻页等待；新的窗口会取消旧的。相邻页组一直参与组合，所以离屏时
  已经完成解码，翻页时不会闪占位图。
- 连续滚动模式：使用 Android 连续模式的 `ReaderPrediction` 规划可见范围以外的前瞻页。
- 后台加载失败的页面在本章内不再重复请求，真正显示该页时照常加载并报错。

验证：
- 新增 `DesktopReaderGestureProbe`：后台预取、拖拽翻页、短拖回弹、反向拖拽、预取页不重复请求、左右点击区、
  中间切换工具栏、滚轮翻页，以及动画设置持久化（重启后仍是“覆盖”）。
- 原有阅读器探针按新行为调整：
  - 只统计视口内的页面节点（相邻页组离屏、裁剪后边界为空）；
  - 用 `requestFocus` 代替点击视口中心（中心点击现在用来切换工具栏）；
  - 失败注入改在打开章节前设置（夹具支持多个页序号）；
  - 请求计数改为“增量等于新加载的页数”，保留“已缓存页面不重复请求”的原意；
  - 等待 `isPrefetching` 结束后再计数。
- 结果：
  - Android `compileDebugKotlin` 通过，阅读器单元测试 694 项通过；
  - 全量回归：reader-core 179、core-source 52、core-domain 129、core-ui 12、core-cloudflare 17、cloudstream-desktop 2（1 跳过，
    本次没有提供真实插件目录）、source-host 86、parser-host 23（2 跳过）、dex-convert 14（2 跳过）、mihon-desktop-compat 34、
    desktop-runtime 68（2 跳过）、desktop-app 79（4 跳过），全部 0 失败；`compileCommonMainKotlinMetadata` 通过。

**未做（下一阶段候选）**
- 竖向翻页、长条（webtoon）模式的拖拽惯性。
- 自定义九宫格动作。
- 音量键 / 自动翻页、阅读器背景色与亮度、页面裁边、图片滤镜。
- 预加载下一章。
- 翻译叠加层。
- 小说阅读器的翻页动画。
- 把 Android 的选项面板 UI 搬到 core-ui 共享。

## 80. 阅读器第二阶段：色彩校正、背景、页码、竖向翻页、下一章预载、连续模式拖拽（2026-10-05）

**共享**：新增 core-ui `ReaderColorMatrix`，是 Android `ReaderColorFilter` 的 4×5 颜色矩阵运算，与平台无关。按 Android
`ColorMatrix` 的语义逐步重放：灰度用 `setSaturation(0)` 替换单位矩阵，之后的每一步都是 `postConcat`；反色沿用
Android 的 R' = A − R + 1；护眼系数 0.92。Android 的 `ReaderColorFilter.toColorMatrix()` 和 `isEmpty` 改为委托它。
`ReaderColorMatrixTest` 用像素结果验证亮度、以中灰为轴的对比度、反色先于亮度的顺序、灰度权重和护眼效果。

**Windows**
- 色彩校正：反色、灰度、亮度和对比度（−1..1，显示为 0–200%，与 Android 相同）、护眼，可一键重置。通过
  `ColorFilter.colorMatrix(ReaderColorMatrix.of(...))` 作用于整页图像和分块图像，连续模式同样生效。
- 阅读背景：沿用 Android 的六种（默认 / 浅色 / 深色 / 白色 / 黑色 / 跟随系统）；浅色背景开启护眼时同样被染成书页色。
  页面样式（背景、滤镜、文字色）由一个 CompositionLocal 提供，分页、连续、分块图像统一读取。
- 显示页码：分页视口底部居中显示“3 / 5”（双页显示“4–5 / 5”）。
- 竖向翻页（Android 的竖向模式）：方向按钮在“从左向右 / 从右向左 / 从上到下翻页”之间切换；竖向时不组双页。
  页带、拖拽（`PagedDragState` 按纵轴衔接平移与翻页）、吸附速度、过渡和卷页都切换到纵轴，↑/↓ 也可以翻页。
- 下一章预载：分页模式到达最后两个页组、连续模式最后可见页进入末尾两页时，在后台取得下一章页面列表和前两页；
  打开该章时直接使用缓存的页面列表（不再请求来源），图片命中缓存。预载失败的章节在本章阅读期间不会在后台重试
  （回归测试发现，进度每次上报都会重试一次，会持续请求失败的来源），打开其他章节后才会重新尝试。
- 连续模式：
  - 鼠标拖拽滚动，松手按 Compose 默认惯性甩动，到章节首尾时触发自动跨章；
  - Android 九宫格：中间切换工具栏，左右两侧各滚动 0.9 屏，带动画。
- 设置全部持久化：`cf_brightness`、`cf_contrast`、`cf_invert`、`cf_grayscale`、`cf_book`、`background`、
  `page_numbers`、`vertical`。只影响显示的设置不会重新加载页面。

验证：
- `DesktopReaderGestureProbe` 新增：
  - 反色后黄色页面在屏幕上变成蓝色为主，白色背景的上下留白为纯白；
  - 页码显示“3 / 5”；
  - 竖向时向上拖拽和按 ↓ 都翻到下一页；
  - 进入章节末尾后下一章被预载，自动跨章后缓存被消费；
  - 连续模式下鼠标拖拽使滚动位置前进，点击中间切换工具栏。
- 原有探针按预载行为调整：
  - 跨章失败注入改在打开章节前设置；连续模式里第二次注入前用测试钩子丢弃已预载的章节；
  - 离线阅读已下载章节时，允许预载下一章（未下载）发出一次页面列表请求和前两页请求；
  - 不再点击视口中心来获得焦点。
- Android：`compileDebugKotlin` 通过，阅读器单元测试 694 项通过。
- 全量回归：reader-core 179、core-source 52、core-domain 129、core-ui 16（新增色彩矩阵 4 项）、core-cloudflare 17、
  cloudstream-desktop 2（1 跳过）、source-host 86、parser-host 23（2 跳过）、dex-convert 14（2 跳过）、mihon-desktop-compat 34、
  desktop-runtime 68（2 跳过）、desktop-app 79（4 跳过），全部 0 失败；`compileCommonMainKotlinMetadata` 通过。

**仍未做**
- 页面裁边（Android 按位图分析白边）。
- 自定义九宫格动作和长按菜单。
- 自动翻页。
- 翻译叠加层。
- 小说阅读器动画。
- 共享 Android 的阅读器选项面板 UI（目前桌面面板是 Material2，Android 是带资源字符串的 Material3 组件）。

## 81. 阅读器第三阶段：裁剪白边、自动翻页 / 自动滚动（2026-10-05）

**共享（reader-core，Android 委托）**
- `ReaderEdgeDetection`：原 Android `EdgeDetector` 的白边检测算法，与平台无关。保留原有判定：以 100 px 块从四边扫描到第一个
  非白像素（容差 16）；任一边的空白超过页面三分之一就视为没有可裁的白边；大图按 1 / 0.75 / 0.5 / 0.25 缩小后扫描。
  像素通过 `BlockReader` 读取：Android 用 `Bitmap.getPixels`，Windows 用 Skia 位图，各自的解码器不变。Android
  `EdgeDetector` 只保留解码和缓存，`isColorTheSame` 也委托共享实现（`TrimTransformation` 不受影响）。
- `ReaderAutoScroll`：原 `ScrollTimer` 的速度曲线（0.1 + 速度 × 10）、滚动间隔（32 ms / 倍率）、换页间隔
  （10 s / 倍率）、交互后暂停 2 s、默认速度 0.24。Android 的 `ScrollTimer` 和 `AppSettings` 默认值改为引用它。
- `ReaderEdgeDetectionTest`：用合成页面验证四边白边、2× 降采样结果一致、无白边或白边超过三分之一时不裁、
  降采样档位，以及自动滚动的时间曲线。

**Windows**
- 裁剪白边：沿用 Android 分开设置翻页和连续滚动的做法。
  - `DesktopImageDecoder.contentBounds` 降采样解码后调用共享检测，并按文件缓存结果。
  - 会话的 `cropPages`（与超分同样的接法）决定是否给页面附上 `crop`；分页和连续场景都按裁剪后的尺寸排版，
    页面图像用 `BitmapPainter` 只绘制内容区域，按解码缩放比例换算。
  - 分块显示的超长页暂不裁剪。
  - 切换开关会清空已加载页面并按新设置重新排版（本地缓存命中，不重新请求）。
- 自动翻页 / 自动滚动：
  - 底部新增“自动翻页 / 自动滚动”按钮。分页模式下，页面比视口大时每次滚动 1 dp，滚不动或页面完全可见时按共享
    换页间隔翻页；连续模式每次滚动 1 dp，到章末交给自动跨章。
  - 点击、滚轮和按键都会暂停 2 s（与 Android 一致）。
  - 速度滑块显示倍率，例如 ×2.5。
- 新设置持久化：`crop_paged`、`crop_continuous`、`as_speed`。

验证：
- `DesktopReaderGestureProbe` 新增：
  - 每章第一页四周带白边（内容区为 40,60–360,540）；开启裁剪后检测结果正确，页面显示比例为 320:480，中心像素是内容色；
  - 速度设为 1 时，自动翻页在约 1 s 内翻到下一页，停止后 2.5 s 内页码不再变化。
- Android：`compileDebugKotlin` 通过，阅读器单元测试 694 项通过。
- 全量回归：reader-core 183、core-source 52、core-domain 129、core-ui 16、core-cloudflare 17、cloudstream-desktop 2（1 跳过）、source-host 86、parser-host 23（2 跳过）、dex-convert 14（2 跳过）、mihon-desktop-compat 34、desktop-runtime 68（2 跳过）、desktop-app 79（4 跳过），全部 0 失败；`compileCommonMainKotlinMetadata` 通过。

**仍未做**
- 自定义九宫格动作和长按菜单。
- 翻译叠加层。
- 小说阅读器动画。
- 共享阅读器选项面板 UI。
- 超长分块页的裁剪。

## 82. 阅读器第四阶段：自定义九宫格动作（阅读操作）与长按 / 右键（2026-10-05）

**共享（core-ui，包名 `org.skepsun.kototoro.reader.ui.tapgrid` 不变，Android 导入不改）**
- `TapAction`（下一页 / 上一页 / 下一章 / 上一章 / 显示隐藏 UI / 显示菜单，含配置网格的着色）迁入 core-ui，去掉 Android 字符串资源；
  Android 用扩展属性 `TapAction.nameStringResId`（`TapActionNames.kt`）保留原写法。
- `TapGridConfig`：Android `tap_grid` 的键名（`CENTER`、`CENTER_long`、`_init`）、默认布局（左列和上中为上一页，右列和下中为下一页，
  中间点按切换工具栏、长按显示菜单）、“全部禁用”，以及按区域读写动作。`TapActions` 从 ViewModel 的嵌套类移到这里。
  Android `TapGridSettings` 的键名和默认值改为引用它。
- `ReaderTapGridConfigGrid`：Android “阅读操作”页的九宫格（按点按动作着色、标出点按 / 长按动作、分隔线），文案和文字样式由调用方传入；
  Android `ReaderTapGridConfigScreen` 只保留顶栏、菜单和选择对话框。
- `readerTapGestures` 新增可选的 `onSecondaryTap`：右键单击直接报告所在区域，不进入点按 / 长按判定。Android 不传，行为不变。

**Windows**
- `DesktopReaderSettings.tapGrid` 持久化为 `tap_grid.<Android 键名>`：值为动作名，空字符串表示“无”，缺省时用共享默认值。
- 分页和连续阅读器都按配置执行动作（`performTapAction`，对应 Android `ReaderControlDelegate.processAction`）：翻页动作在分页模式下带动画翻页，
  在连续模式下滚动 0.9 屏；“显示菜单”打开阅读设置面板。长按和右键执行长按动作，默认中间区域打开阅读设置。
  点击回调由 `rememberTapGridHandlers` 固定下来，重组时不会重启手势检测，按住过程中的长按不会被打断。
- 阅读设置新增“阅读操作”：使用共享网格，点击单元格设置点按动作，长按或右键设置长按动作，下方列出可选动作；支持“重置”和“全部禁用”。
  快捷键说明同步更新。

验证：
- core-ui `TapGridConfigTest`：默认布局与 Android 一致、键名、单项修改不影响其他项、全部禁用。
- `DesktopReaderGestureProbe` 新增：在面板里把左侧中间改为“下一页”，右键右下单元格把长按设为“下一章”；点击左侧中间后向前翻页；
  在阅读区右键中间打开阅读设置且不翻页；重启后配置仍在，未修改的区域保持默认值。
- Android：`compileDebugKotlin` 通过，阅读器单元测试 694 项通过。
- 全量回归：reader-core 183、core-source 52、core-domain 129、core-ui 19（新增 3 项）、core-cloudflare 17、cloudstream-desktop 2（1 跳过）、
  source-host 86、parser-host 23（2 跳过）、dex-convert 14（2 跳过）、mihon-desktop-compat 34、desktop-runtime 68（2 跳过）、
  desktop-app 79（4 跳过），全部 0 失败；`compileCommonMainKotlinMetadata` 通过。

**仍未做**
- 翻译叠加层。
- 小说阅读器动画。
- 共享阅读器选项面板 UI（桌面面板仍是 Material2）。
- 超长分块页的裁剪。
- Android 双页模式中“长按左右页中央打开菜单”的特殊判定，以及按长按位置选中目标页（`setTargetPageBySide`），桌面暂未实现。

## 83. 阅读器选项面板共享：Android 面板直接用于 Windows（2026-10-05）

目标：Windows 阅读器设置不再单独实现一套 Material2 侧栏，而是直接使用 Android 的阅读器选项面板。

**迁入 core-ui（包名不变，Android 导入基本不改）**
- 面板组件（`reader.ui.compose.design`）：`ReaderPanelColors`（含 `mangaReaderPanelColors`、`forEInk`、`ProvideReaderPanelColors`）、
  `ReaderOptionControls`（选项卡片、分节、分隔线、开关行、取值行）、`ReaderPanelComponents`（胶囊标签栏、选择芯片、图标选择条、
  快捷操作宫格、开关芯片、步进行、滑块行）、`ReaderControlTokens`。为了不依赖平台资源，`ReaderQuickAction` 只保留 `id` 和 `toggled`，
  图标和文字由宿主传入宫格；`ReaderSliderRow.leadingIcon` 改为 `Painter`。
- 图标：新增 Gradle 任务 `:core-ui:generateReaderPanelIcons`，在构建时把 Android 的矢量图（`ic_arrow_forward`、`ic_settings`、阅读模式、
  缩放、快捷操作等 20 个）生成为共享的 `ReaderPanelIcons` ImageVector，两端绘制同一套图标。
- 面板宿主（`reader.ui.compose.panel`）：`ReaderPanelHost` / `ReaderOptionsPanelHost`。Android 的液态玻璃通过 `LocalReaderPanelGlass`
  注入（`AndroidReaderPanelGlass` 由 `KototoroTheme` 提供），没有玻璃的宿主使用不透明面板。`rememberReaderPanelSurfaceMode` 留在 Android。
- 可拖拽底部面板 `StableAnchoredSheetLayout`（`core.ui.compose`）。返回键改用 expect/actual 的 `PlatformBackHandler`：Android 端为
  activity BackHandler（core-ui androidMain 新增 activity-compose 依赖），桌面端为空实现，由桌面自己处理 Esc。窗口版
  `StableAnchoredBottomSheet` 留在 Android。新增 `openExpanded`：以四分之三高度打开且不设预览停靠点，与横屏平板“预览过高时没有中间档”
  的行为一致，快捷层保持可见。
- 漫画选项面板（`reader.ui.compose.ComposeReaderOptions.kt`）：`ComposeReaderOptionsState` / `Callbacks`，快捷层 `ReaderMangaQuickLayer`，
  标签页 `ReaderOptionsDetailTabs`，“排版”页 `ReaderLayoutOptionsPage` 和“显示”页 `ReaderDisplayOptionsPage`，背景色块，
  动画图标和背景图标。文案通过 `ReaderOptionsStrings` 传入；`ReaderOptionsFeatures` 声明宿主支持哪些项，不支持的行会隐藏。
  Android 的 `ComposeReaderOptionsPanel` 只负责组装：传入字符串资源、翻译标签页和效果预览。
- 色彩校正控件 `ReaderColorCorrectionControls`（`reader.ui.colorfilter`，文案由 `ReaderColorCorrectionLabels` 传入）。Android 的同名
  4 参数版本改为委托共享实现；前后对比预览依赖 coil，仍留在 Android。
- 模型类型：`ReaderMode`、`ReaderBackground`（仅枚举；Android 的 `resolve(context)` / `isLight(context)` 改为扩展函数）、`ReaderOcrMode`、
  `UiPresentationMode`、`UiPresentationConfig` / `LocalUiPresentationConfig`、`tvFocusable`、`ReaderColorFilter`（Android 的
  `toColorMatrix` / `toColorFilter` / `getBackgroundTint` 改为扩展函数）、`ImageServerOptions`。
- 注意：Android 与 core-ui 中同包同名的 Kotlin 文件会编译成同一个 `XxxKt` 类，导致其中一个被遮蔽。因此 Android 的面板文件
  改名为 `AndroidReaderPanelGlass.kt`。

**Windows**
- 构建时从 Android 的 `values` 和 `values-zh-rCN`（`strings*.xml`、`arrays.xml`）生成 `android-strings.properties`
  （任务 `prepareDesktopStrings`，字符串数组会展开为对应的 `@string`），由 `AndroidStrings` 读取，面板文案与 Android 中文版逐字一致。
- “阅读设置”按钮改为打开共享面板 `DesktopReaderOptionsPanel`。桌面设置与 Android 状态之间的映射：
  - 阅读模式：默认 / 从右到左 / 从上到下 / 条漫，分别对应 SINGLE·DOUBLE + 方向，以及 CONTINUOUS；不提供横向连续模式。
  - 横屏双页对应 DOUBLE；新增“首图作为封面”（`double_cover`，即 reader-core 的 `isCoverOffset`）。
  - 动画、缩放、页码、裁切（按当前模式写入翻页或连续滚动的开关）、背景、色彩校正、超分开关，以及全屏（对应 F11）。
  - 色彩校正的滑块每一步都会立即显示，停止 250 ms 后才保存，避免每一步都排队写盘。
  - 快捷操作：章节与页面、添加书签、自动翻页。
- 面板的齿轮按钮打开“更多阅读设置”侧栏，对应 Android 的阅读器设置页。侧栏只保留 Android 面板中没有的项：阅读操作（九宫格）、
  自动翻页速度、超分模型与降噪、快捷键说明。旧侧栏中与共享面板重复的项已删除。阅读操作的文案也改用 Android 字符串。

验证：
- `DesktopReaderGestureProbe`：在共享面板中切换动画（使用 Android 文案“高级”）；从齿轮按钮进入阅读操作；右键中间打开面板；
  在图标条中依次切换从右到左、双页、封面、条漫、默认；在“显示”页选择白色背景，反色后重置（验证保存防抖）；
  快捷操作“添加书签”会关闭面板并切换书签。`DesktopTabletProbe` 和 `DesktopUpscaleProbe` 按新流程调整（Esc 关闭面板、齿轮进入超分设置）。
- Android：阅读器及面板相关单元测试 738 项通过（包括已迁入 core-ui 的面板配色、组件、宿主和底部面板测试）。
- 全量回归：reader-core 183、core-source 52、core-domain 129、core-ui 19、core-cloudflare 17、cloudstream-desktop 2（1 跳过）、source-host 86、parser-host 23（2 跳过）、dex-convert 14（2 跳过）、mihon-desktop-compat 34、desktop-runtime 68（2 跳过）、desktop-app 79（4 跳过），全部 0 失败；`compileCommonMainKotlinMetadata` 通过。

**仍未做**
- 漫画阅读器的顶部和底部控制栏（Android `ReaderControlShell`）尚未共享，桌面仍是自己的工具栏。
- 小说阅读器的选项面板（`ComposeNovelReaderOptionsSheet`）尚未接入桌面。
- 翻译标签页；“横向连续”模式；分割双页、折叠屏、渲染器、性能等桌面没有对应引擎的项。
- “Layout”“Two pages”等少数字符串在 Android 中文资源里没有译文，两端都显示英文（应通过 Weblate 补译）。

## 84. 阅读器控制栏共享：Android 与 Windows 共用浮动控件（2026-10-05）

**共享（core-ui，原包名保持）**
- `ReaderChrome.kt` 提取 Android 顶部返回/章节标题/选项、书签等浮动按钮、进度滑条与上一章/下一章、
  进度底座和缩放按钮。图标复用原 Android vector；文案、状态和动作由宿主提供。
- `ReaderChromeSurfaces` / `LocalReaderChromeSurfaces` 隔离平台表面：Android 的
  `AndroidReaderChromeSurfaces` 通过主题注入原玻璃药丸与底座，共享组件提供 Material3 表面回退。
- `ImmersiveEdgeGradient` 与透明色处理迁入共享模块，Android 原顶部/底部渐变与布局参数保持。
- Android `ComposeReaderActivityScaffold` 委托共享组件，保留 TV 焦点、章节标题底置、资源字符串及浮动动作装配。

**Windows**
- `DesktopReaderChrome` 使用共享顶部栏、进度底座、书签/自动翻页按钮及边缘渐变；分页画布使用共享缩放控件，
  保留倍率显示和重置视图。缩放控件文字色取自阅读背景，避免浅色应用主题在深色阅读画布上显示黑色按钮。
  控制栏悬浮在全窗口视口上，显示/隐藏不再改变页面排版或阅读进度。
- 中央点击或 H 切换控制栏，隐藏时显示章节与可见页码；标题打开章节，选项打开共享 Android 阅读设置。
  点按书签按钮保存/移除当前书签，长按打开书签面板。全屏沿用 F11 和“排版”页的全屏开关。
- 自动跨章与重新加载归入“更多阅读设置”；阅读操作、超分模型、自动翻页速度等桌面适配仍由原 Controller 处理。
- 现有交互探针改为操作共享选项与浮动控件。`ReaderChromeTestKit` 统一焦点、快捷键、模式、适配方式、
  刷新及隐藏状态页码检查；像素/分块接缝断言先隐藏覆盖层，继续验证原有颜色与完整采样列。
- 全屏开关通过选项列表滚动操作；短窗口首次滚动会先展开面板，探针使用有次数上限的分步滚动，
  并断言开关确实显示，避免对尚未进入组合的控件调用 `performScrollTo`。

验证：
- `:core-ui:jvmTest` 19 项通过；`:core-ui:compileCommonMainKotlinMetadata` 通过。
- Android `:app:compileDebugKotlin` 通过；阅读器与阅读设置单元测试 694 项通过，
  `ImmersiveEdgeGradientTest` / `StableAnchoredBottomSheetTest` 另 15 项通过。
- Windows `:desktop-app:test` 首轮覆盖 79 项，72 项通过、4 项按运行条件跳过；3 项控制栏适配失败
  （背景像素、分块接缝、窗口面板）修复后分别定向重跑通过。
- 窗口探针覆盖 1260×850、920×620、1000×850、999×620：控制栏显隐不改变视口、全屏开关可滚动到达、
  滑条保存进度、书签长按及跨章恢复、章节搜索不触发快捷键、返回详情退出全屏。
- 缩放对比度修复后额外重跑相机探针，鼠标/触摸/适配/倍率限界及重启恢复均通过；
  截图已检查共享控制栏、浅色主题的深色阅读画布和短窗口全屏选项。

**仍未做**
- 小说阅读器选项面板共享、翻译标签页、横向连续模式、超长分块页裁剪。
- 双页长按按位置选择目标页与 Android 特殊中央菜单判定。

## 85. 小说阅读选项 UI 共享（2026-10-05）

**共享（core-ui）**
- `NovelReaderOptionsUi` 提取 Android 小说字号步进控件、主题预览色卡、快捷层和排版卡片。
  宿主提供文字、数值范围、主题颜色和修改回调，不把 Android Settings、Context 或 Windows 存储带进共享层。
- 小说标签页复用已有 `ReaderOptionsDetailTabs`，不显示齿轮时也不预留按钮宽度；
  排版、阅读与翻译工具页复用已有 `ReaderOptionsPageList`，保持原有间距和滚动行为。
- Android 原字体选择、段距、页边距、首行缩进、亮度、翻译、替换规则和 TTS 继续使用原宿主逻辑。
  字号范围与归一化保持原值；纸张主题、电子墨水颜色、TV 焦点和玻璃表面由原适配层提供。
- 状态和事件边界参考 [Compose 官方状态提升指南](https://developer.android.com/develop/ui/compose/state-hoisting)，
  只共享呈现组件，业务状态和持久化留在宿主；没有新增依赖或偏好格式。

**Windows**
- `DesktopNovelOptionsPanel` 替代旧 Material 2 侧栏，使用共享锚点面板、主题色卡、字号、排版卡片及标签布局。
  宽窗口直接展开；短窗口保留可滚动内容，Esc 或遮罩关闭后把焦点交还正文。
- 字号、行距、衬线字体和版心宽度沿用 `DesktopNovelSettings` 范围及原偏好键，主题仍兼容已有
  `LIGHT` / `SEPIA` / `GREEN` / `DARK` 记录；阅读页全屏开关接入实际窗口回调。
- 原按段落连续滚动、章节切换、进度报告、插图请求和第二进程恢复流程保持。
  桌面没有显示尚未接入的亮度、分页动画、双页、翻译或 TTS 控件。

验证：
- `:core-ui:jvmTest` 19 项通过；共享模块 Android/JVM 编译和 `:app:compileDebugKotlin` 通过。
- Android 小说相关 JUnit 测试 168 项通过，0 失败、0 跳过。首次默认引擎运行中，Kotest 未遵守
  Gradle 的小说筛选范围而执行无关 EPUB 属性测试；停止该进程后，使用仅限本地的
  `build/kmp-novel-validation.init.gradle` 限定 JUnit Jupiter，并重验最终代码。
  正式测试配置未改动，本轮结果不包含 Kotest 属性测试或 Android 真机 UI 验证。
- 桌面解析器探针从真实 fixture 插件打开小说，经共享 UI 修改主题、字号、行距、版心和字体，
  检查全屏、Esc、F11 及跨章，再启动第二个 JVM 检查设置与章节恢复。
- 探针第一进程为 1260×850，第二进程为 920×620；两种尺寸的排版面板截图已检查。
  短窗口的后续扩展卸载检查先滚动已安装列表，避免依赖控件已进入组合。
- 漫画平板回归通过，覆盖 1260×850、920×620、1000×850 和 999×620，验证共用标签栏、
  全屏、书签及章节操作未回归。

**仍未做**
- 小说正文分页/双页引擎、章节面板和翻译/TTS 的桌面接入；本节完成的是阅读选项组件共享。
- 漫画横向连续模式、超长分块页裁剪及双页长按按位置选择目标页。


## 86. 小说上下栏共享与浮动布局对齐（2026-10-05）

**共享边界**
- `SharedNovelReaderChrome` 提取小说顶部返回、章节胶囊、选项按钮、底部进度底座、跨章按钮、
  渐变、显隐动画、底置章节标题、隐藏阅读状态与浮动动作布局；基础按钮和滑条沿用共享 `ReaderChrome`。
- `NovelReaderChromeState`、颜色、文案和事件由宿主传入；进度单位归属阅读引擎，Android 按页，
  Windows 按正文块。共享呈现不依赖 Android Settings、Context、小说模型或桌面存储。
- 控件颜色跟随小说阅读主题，底座和浮动按钮支持显式内容色；玻璃表面仍由 Android 主题注入。
  底部控件可见性和电子墨水动画开关迁入共同策略，Android 原入口委托该策略。
- Android 保留原书签、翻译、替换规则、标记、TTS 与 BackHandler 的装配；
  `navigationBarsIgnoringVisibility` 由 Android 宿主传入，保留沉浸模式的原导航栏间距。

**Windows 行为**
- `DesktopNovelReaderChrome` 委托同一套上下栏，正文使用完整窗口视口，控件作为覆盖层呈现。
  固定正文首尾留白不随显隐变化；点击正文空白或 H 切换控件，隐藏阅读状态可点击恢复。
- 标题打开章节面板、选项打开共享小说阅读设置、滑条跳转段落、跨章按钮遵守章节边界。
  全屏沿用 F11 和阅读设置中的真实窗口开关。
- 进度报告使用 LazyList 的实际阅读锚点；顶部留白后方仍组合的正文块不会把目标段落的保存位置向前偏移。
  沿用原 Controller、历史表、偏好键和章节加载，没有新增依赖或数据格式。
- fixture 小说扩展增加长章节，检查非零段落的定位、保存及跨进程恢复；原插图仍经过来源客户端。

**验证范围**
- `:core-ui:jvmTest` 19 项通过，共享 UI 的 JVM/Android 编译通过。
- `:app:compileDebugKotlin` 通过；小说相关 JUnit 168 项和底部可见性 2 项，共 170 项通过，
  0 失败、0 跳过。沿用 §85 的本地 init script 限定 JUnit Jupiter，本轮不包含 Kotest 属性测试。
- 桌面解析器与漫画平板两个定向测试通过。小说第一进程 1260×850 写入正文块索引 12，
  通过跨章按钮切到第二章，再用滑条定位索引 16；第二进程 920×620 验证章节、正文块和深色排版偏好恢复。
- 探针检查 H 与鼠标点击显隐、阅读状态点击恢复、正文视口/块坐标/插图请求稳定、标题打开章节、
  Esc 返回、进度持久化、跨章边界及实际全屏回调；长章节插图仍从扩展客户端加载。
- 漫画回归覆盖 1260×850、920×620、1000×850、999×620，检查共享底座与控件调整未回归。
  浅色/深色上下栏、隐藏状态、段落跳转与窄窗口设置截图已检查，保存在忽略的
  `desktop-app/build/reports/desktop-smoke`；相关差异的 `git diff --check` 通过。

**仍未做**
- Windows 小说分页/双页引擎、共享章节面板、小说书签与翻译/TTS 接入；本节对齐的是上下栏呈现和现有滚动引擎交互。
- Android 真机控制栏视觉与系统栏回归、iOS 原生构建与真机验证。


## 87. 小说章节目录共享（2026-10-05）

**共享呈现与目录规则**

- `NovelChapterDirectoryEntry` 是面向呈现的轻量输入；宿主解析标题、分卷、组名及搜索别名，
  共享目录保留原章节索引，倒序和筛选不会改变选择事件的索引含义。
- `NovelChapterDirectoryContent` 提取 Android 搜索框、清除与倒序按钮、分组标题、章节行、
  已读淡化、当前章背景及强调条；定位当前章会清除筛选并滚动到对应行。
  非空筛选从首项展示，空筛选定位当前章；目录数据或当前章变化后重新解析位置。
- 列表继续使用 LazyColumn 和唯一 key，重复组名/卷名以及重复章节 ID 保留原索引以区分节点。
  参考 [Compose 官方列表指南](https://developer.android.com/develop/ui/compose/lists) 的 key 和滚动状态说明。
- `ReaderChapterPanelHeader`、副标题格式与定位按钮进入共同层。搜索框图标复用已有 Android vector
  生成流程；没有新增依赖。Android 原目录入口和测试辅助函数委托共享实现，保留本地小说组名规则。
- Android 正文搜索、笔记/标记、书签与 Pager 标签仍由原宿主装配；本节不迁移这些业务。

**Windows 接入**

- `DesktopNovelChaptersPanel` 替代小说原 Material 2 侧栏，复用共享 ReaderPanelHost、标题和章节目录。
  面板颜色跟随阅读主题；目录内容保持 Android 的最大宽度、搜索、正倒序和当前章定位布局。
- 章节选择回到原 Controller；选择当前章只关闭面板，保留滚动位置和已加载正文。
  选择其他章沿用原加载/历史流程。忙碌时章节行禁用；Esc 或遮罩关闭后恢复正文焦点。
- 目录打开期间正文仍挂载；搜索输入 H 不触发阅读区的显隐快捷键。

**验证范围**

- `:core-ui:jvmTest` 24 项通过，包括新增目录规则 5 项；共享代码 JVM/Android 编译通过。
- `:app:compileDebugKotlin` 通过，小说相关 JUnit 168 项及章节标题测试 2 项，共 170 项通过，
  0 失败、0 跳过。沿用 §85 的本地 init script 限定 JUnit Jupiter，本轮不包含 Kotest 属性测试。
- 共享组件长目录探针使用 120 个章节，覆盖 1260×850 浅色和 520×620 深色布局，验证离开当前章后
  定位、筛选清除、倒序后原索引选择、组名/译者搜索、禁用行不触发事件及空目录。
- 真实 fixture 插件的小说探针在 1260×850 写入非零正文块，第二进程 920×620 恢复章节与设置，
  检查目录打开期间输入 H、搜索/倒序/定位、当前章返回、Esc 关闭、正文视口和插图请求保持。
- 漫画平板回归通过，覆盖 1260×850、920×620、1000×850、999×620；共享标题提取未回归。
  桌面目录首轮断言误把占位文案与 EditableText 一起比较，改为检查 EditableText 后两项定向测试均通过。
- 浅色/深色目录、当前章和窄窗口长列表截图已检查，保存在忽略的 `desktop-app/build/reports/desktop-smoke`；
  相关差异的 `git diff --check` 通过，没有新增依赖、偏好键或存储格式。

**仍未做**

- Windows 小说书签/笔记、正文搜索与翻译/TTS 接入；小说分页/双页引擎。
- 漫画章节目录呈现共享，以及 Android 真机/iOS 原生视觉和交互验收。

## 88. 小说书签卡片共享与 Windows 保存/恢复（2026-10-05）

**共同呈现**

- 将 Android 小说书签卡片提取为 `NovelBookmarkCardContent`：正文摘要、强调条、章节、日期和删除按钮
  使用同一 Material3 布局。Android 保留原摘要解析、日期格式和笔记/标记操作，由原宿主传入数据及回调。
- 删除图标沿用 Android vector 的共享生成流程；Windows 面板复用 ReaderPanelHost、共享标题与搜索框。
  宽屏内容居中，颜色跟随小说阅读主题，没有新增依赖或另一套图标。
- Windows 小说浮动书签按钮复用 `ReaderFloatingControlButton` 与 `NovelReaderFloatingControls`，
  点击或 B 添加/移除当前正文位置，长按打开书签列表；面板输入 B 不触发正文快捷键。

**存储与定位**

- `DesktopLibrary` 共用漫画/小说书签的位置切换事务及身份检查。小说摘要截取纯文本至 200 字符，
  写入既有书签表的 image 字段；page 保存正文块索引，scroll 为 0，percent 按章节所属分支计算。
  同一作品内生成记录 ID 时检查已有主键，保存书签本身不写阅读历史。
- 单条删除先比对完整持久化记录，拒绝过期卡片、作品身份冲突和错误作品 ID；无效正文位置或变更章节
  不写入书签。桌面 DTO 补充已有 image 字段的投影，Room schema 和备份格式保持兼容。
- 小说打开时重新载入本作品书签。列表支持摘要/章节标题搜索、最近添加排序、单条删除和同章/跨章跳转。
  同章跳转保留正文对象，通过 navigation 请求重新滚动，连续跳回同一目标也生效且不新增插图请求。
  跨章先加载正文并校验位置，失效位置不强行夹到其他段落；阅读进度仍走原历史流程。

**验证结果**

- `:core-ui:jvmTest` 24 项通过；`:desktop-runtime:test --tests "*DesktopLibraryTest*"` 11 项通过，
  包含新增小说书签重启/摘要/分支进度和无效位置/过期删除保护测试。
- 桌面 fixture 小说使用 1260×850 写入两章书签，再在第二进程 920×620 恢复；验证空列表、搜索、编辑 B、
  跨章和重复同章跳转、正文视口不变、单条删除、快捷键切换及持久化读取。浅色/深色书签截图已检查。
- 原漫画书签的跨章节、页码/像素偏移和第二进程恢复探针通过。居中修正后定向小说探针再次通过。
- `:app:compileDebugKotlin` 与 Android 小说/章节标题 JUnit 170 项通过，0 失败、0 跳过；
  沿用 §85 的本地 init script 限定 Jupiter，不包含其他 Kotest 属性测试。
- 验证日志及截图位于忽略的 build 目录；本节相关文件的 `git diff --check` 通过。

**边界与后续**

- Windows 的正文块索引与 Android 的分页索引不能保证逐项对应，共享 schema 不代表跨阅读引擎精确定位。
  当前验证针对桌面创建的纯文本书签；Android 历史 HTML/data URL 摘要的桌面解析仍待统一。
- Windows 小说笔记/标记、正文搜索、翻译/TTS 和分页/双页引擎继续推进；Android 真机及 iOS 验收仍待。

## 89. 小说书签摘要、正文定位与当前位置状态统一（2026-10-05）

**共享规则**

- `core-domain/commonMain` 提供摘要清理、分支进度和 `NovelBookmarkTextIndex`。纯文本、HTML 和
  HTML/plain base64 data URL 采用同一预览策略，去除无关标签、规范空白并保留最多 200 字符。
  网络/文件图片地址、无效 data URL 返回空摘要；不会读取外部文件或发起网络请求。
- Android 与 JVM 从同一个 `jvmSharedMain` 目录编译已有 Jsoup 的适配器，Android 原入口保留 LruCache。
  不新增依赖、Room schema、偏好键或备份字段；common metadata 编译验证了定位规则的可移植性。
  Base64 使用 [Kotlin 标准库共同 API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io.encoding/-base64/)。
- 章节索引一次拼接渲染后的文本，忽略换行、缩进和空白，书签返回摘要起点所属的页或正文块。
  摘要跨页、跨段落仍可匹配；有文本而未命中时返回失败，不使用另一端保存的页码强行跳转。
  重复摘要按分支进度选择较近位置；空摘要保留范围内的原数字位置作为旧格式兼容。

**两端接入**

- Windows 的展示和搜索解析历史 HTML/data URL 摘要，保留原 DTO 用于身份校验和删除。
  同章/跨章跳转、当前书签按钮和 B 移除操作均使用解析后的正文位置，保存页码即使超出当前正文块数也可恢复。
  同章继续保留正文对象；跨章匹配失败时保留原章节和导航位置。
- Android 分页与连续滚动分别投影当前渲染页/块到共同索引；跳转等待目标章与精确分页结果就绪。
  请求编号防止旧请求被误消费，等待期间阻止旧滚动位置回报覆盖目标，完成或失败后恢复正常回报。
  相关副作用按请求与布局结果设置 key，参考 [Compose 官方副作用说明](https://developer.android.com/develop/ui/compose/side-effects)。
- Android 当前位置按钮、选区切换和普通切换按正文映射识别已有记录，删除原持久化记录。
  单条 Repository 删除在事务内比对完整记录，拒绝过期对象误删新记录。
  连续滚动发布当前正文块的原文，修复摘要取到旧页面或章节开头的问题；图片块保留空摘要。
- 新建 Android 小说书签采用与 Windows 一致的分支进度计算，修正此前只记录章内进度的行为。

**验证与边界**

- `:core-domain:compileCommonMainKotlinMetadata` 通过；共享定位 JUnit 9 项、桌面存储 11 项通过。
  覆盖中文 HTML、base64 换行、跨页摘要、空白、失效文本、图片旧位置、重复文本和分支进度。
- `:app:compileDebugKotlin` 通过，Android 小说/摘要/章节标题 JUnit 180 项通过，0 失败、0 跳过。
  包含新增分页/滚动窗口映射、当前按钮状态以及请求跨章保留和旧请求消费保护测试；
  沿用 §85 本地 init script 限定 Jupiter，不包含其他 Kotest 属性测试。
- 桌面 fixture 增加旧 HTML/base64 书签：保存索引 700，经搜索跨章跳至当前正文块 12，
  验证移除按钮与 B 删除原记录；修改摘要后拒绝跳转且保留原正文、章节、首块和导航编号。
  失效跳转断言检查导航状态，不比较会随可见区测量更新的末块索引。
- 小说解析器与漫画书签两个端到端测试通过，均覆盖第二进程恢复；桌面写入进程 1260×850、
  读取进程 920×620。小说浅色/深色书签截图仍使用共享卡片，日志与截图保存在忽略的 build 目录。
  本轮相关文件的 `git diff --check` 通过。
- 摘要定位解决 §88 的文本书签兼容缺口，但不是原文字符偏移或像素锚点协议。
  重复文本及旧 Android 章内 percent 可能产生近似选择；译文、替换规则或源正文改变后可能无法匹配。
  图片/空摘要的旧数字位置仍无法保证跨引擎对应，没有升级或重写旧数据。
- iOS 尚需原生 HTML 适配器与原生构建验证；Android 真机分页/滚动跳转和视觉验收仍待。
  Windows 小说笔记/标记、正文搜索、翻译/TTS 及分页/双页引擎继续推进。
