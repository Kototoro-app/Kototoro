# Kototoro iOS / KMP 方案（2026-10）

## 文档信息

- 创建日期：2026-10-02
- 状态：规划草案；P0 中的 S1（KMP 工具链）与 S2（Room KMP）已在分支 `feat/kmp-s1-reader-core` 完成并通过，
  记录见 §9；S0、S3 尚未开始
- 目标：评估并规划 Kototoro 的 iOS 版本——共享一个 KMP 内核，iOS 用原生外壳，源（Mihon JAR、
  Kotatsu 解析器、Legado 规则）由一个嵌入式 JVM 承载
- 前提假设：az4521 的 TachiyomiAZ iOS 路线（OpenJDK Mobile Zero + Suwayomi AndroidCompat）在真机上
  可用。该前提**尚未由我们验证**，由 §5 的 G0 门槛负责证实或证伪
- 关联文档：
  - [Scene Reader 改进计划](./reader-scene-improvement-plan-2026-09.md)（§8.2 曾约定"无真实多平台消费者之前不引入 KMP"）
  - [Browser Transport 实施计划](./browser-transport-plan-2026-08.md)（Cloudflare 策略选择器）
  - [Legado Runtime 抽取方案](./legado-runtime-extraction-plan.md)
  - [外部扩展集成指南](./external-extension-integration-guide.md)

## 1. 决策摘要

1. **不把 Kototoro 改成 KMP 应用，而是"共享内核 + iOS 原生外壳 + 嵌入式 JVM 源运行时"。**
   全量 KMP/Compose Multiplatform 不现实：代码库 35.7 万行里，现在能直接进 commonMain 的只有约
   6～9%（§2.1）。
2. **iOS 版就是 §8.2 所说的"真实多平台消费者"，KMP 在此立项。** 首个模块是已经纯 Kotlin 的
   `reader-core`（58 个文件、2638 行）。
3. **源不用 Kotlin 重写，在 iOS 上由一个嵌入式 JVM 统一承载**（§2.2）：Mihon JAR、Kotatsu 解析器、
   Legado 运行时都是 JVM 代码。上游 Keiyoushi 已为每个 APK 发布同名 JAR，Kotlin/JS 或源码级编译
   路线因此失去前提。
4. **Android 现有的进程内源加载保持不变。** JVM host 与它共用同一份协议，仅用于 iOS 与契约测试。
5. **共享模块不依赖 DI 框架**（只用构造注入）；Android 继续用 Hilt 装配，iOS 手动装配。
6. **v1 只做漫画，只做侧载分发**（AltStore / SideStore）。小说、动漫、视频、翻译后置。

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
JVM host（平台无关）  Mihon JAR + AndroidCompat / Kotatsu parsers / Legado runtime，统一协议
iosApp             SwiftUI + 共享 XCFramework
```

原则：

- 共享模块只用构造注入；DI 框架只出现在各平台的装配层。
- 源协议只有一份。Android 继续用现有进程内适配器，JVM host 的契约测试在桌面 JVM 上运行
  （Linux CI，不需要 Mac）。
- 平台差异只放在 `expect/actual` 或平台模块，不在共享模块里写 `if (android)`。

## 4. 待决策项

| # | 决策 | 建议 |
|---|---|---|
| D1 | iOS UI 方案 | 原生 SwiftUI。Backdrop、media3、NCNN 不可移植，UI 又是代码主体 |
| D2 | 分发方式 | 仅侧载。含下载并执行字节码的嵌入式 JVM 难以通过 App Store 审核（请核对审核指南原文） |
| D3 | 许可证 | iOS 外壳单独成 GPLv3 模块，可借用 az 的桥与 CI；共享内核保持现有许可。注意 Kotatsu 是 GPL-3.0，而根目录 `LICENSE` 是 Apache-2.0，请先确认现状 |
| D4 | v1 范围 | 仅漫画（Mihon JAR + Kotatsu 解析器）；小说、动漫、翻译、视频后置 |
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
2. `core-model`：`parser-api` 的模型层。去掉 `okhttp3.Response`、jsoup 等泄漏；`ContentRepository`
   里的 `LocalMangaSource`/`TestContentSource` 需要上提为抽象。
3. `core-db`：schema 层闭包约 96 个文件、5000 行，但分散在约 15 个功能包，需要跨包搬迁（实体、DAO、
   约 14 个投影类 `*Row`/`*WithContent`）。S2 的实测切断清单见 §9：14 个文件的 1～9 行机械改动，加 4 个
   类型拆分（`ListFilterOption`、`SourcesSortOrder`、`ScrobblingStatus`、`CloudFlareHelper`）。其中
   `ListFilterOption` 是唯一的设计层面改动：DAO 用它拼 SQL，但它带资源 id 与图标，需要拆成纯数据的过滤
   条件。85 个迁移留在 `androidMain`，iOS 直接从 v84 schema 建库。
4. `core-domain`、`core-backup`：备份格式与外部备份解码已是 protobuf 模型。
5. `core-net`：把 OkHttp 依赖的 tracker/sync 客户端迁到 Ktor。

守卫：每个共享模块只有 commonMain 代码，`compileCommonMainKotlinMetadata` 作为可移植性检查（JVM 编译
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

日期：2026-10-02。同一分支，未提交。模块：`spikes/room-kmp`，由 `-PwithKmpSpikes` 门控（`settings.gradle`
新增 4 行，默认关闭，普通构建、Android Studio 同步与 CI 都不会配置它）。

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
