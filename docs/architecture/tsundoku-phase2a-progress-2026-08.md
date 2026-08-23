# Tsundoku Phase 2A 进度：ABI、加载器与 manager（2026-08）

> 计划：`docs/architecture/tsundoku-extension-integration-plan-2026-08.md` Phase 2A（T2A.1–T2A.5，含 T0.3 fixture）。
> 本文件记录 Phase 2A 落地形态、结构化错误语义、证据与遗留项，供 Phase 2B/3A 接力。

## 本阶段产物

### 宿主 ABI（T2A.1，前一轮）
- `eu/kanade/tachiyomi/source/Source.kt`：`isNovelSource`（默认 false）、`suspend fetchPageText(page)`（默认抛）、`getChapterList(manga, RefreshContext)` 默认委托。
- `source/NovelSource.kt`（deprecated marker + `Source.isNovelSource()`）、`source/SourceTracker.kt`、`source/RateLimited.kt`、`source/model/RefreshContext.kt`、`source/model/Page.kt`（`@Transient var text` body 属性）、`SChapter` 阅读态字段、`HttpSource` fork 委托。
- 验证：`TsundokuNovelAbiTest`（11 用例，`assertThrows(X::class.java)` 形态）。

### 共享加载运行时（T1.2 抽取，Phase 2A 消费）
- `extensions/runtime/tachiyomi/TachiyomiApkEcosystemSpec.kt`：MIHON 宽松 1.2..1.9 / TSUNDOKU 严格 feature `tachiyomi.novelextension` + 精确白名单 `{"1.4","1.6"}`；新增 `nsfwMetadataKey` 与 `languageMarker` 字段。
- `TachiyomiLoadResult.kt`：结构化错误（`METADATA / LIB_VERSION / CLASSLOADER / INSTANTIATION / SOURCE` 五相）+ 逐源拒绝（`TachiyomiSourceRejection`）。
- `TachiyomiApkLoaderRuntime.kt`：`extractExtensionInfo`（复用 Mihon 错误措辞："No ApplicationInfo" / "No version name" / "No meta-data in manifest" / "No source class specified in manifest" / "Invalid lib version format"）+ `loadExtension`（lib 白名单门、ClassLoader 注入、direct/factory 实例化、逐源 novel 契约与重复 source ID 诊断）+ `loadFromClass`。
  - 工厂抛错 = 整包失败（INSTANTIATION，计划 §6.3）；直接类实例不是 Source = 逐源拒绝；重复 ID = 逐源拒绝。
  - ClassLoader 工厂可注入，JVM 单测用测试类加载器按名解析假扩展类，无需 dex/APK。

### Tsundoku 加载器 / manager（T2A.2–T2A.5）
- `tsundoku/TsundokuExtensionLoader.kt`：严格分类（双 feature → AMBIGUOUS 结构化错误）+ `VERSION_HIGHER_FIRST_TIE_SYSTEM` 候选解析（T2A.4 系统/私有版本选择）+ `tsundoku` 私有 APK 目录 + `validateSource = { it.isNovelSource || it is NovelSource }`。
- `tsundoku/TsundokuExtensionManager.kt`：复用 `ExternalExtensionManagerFacade`（状态流、缓存、**包广播 reload**（T2A.4，facade runtime 已注册 `ACTION_PACKAGE_*` 观察者）、`TSUNDOKU_{id}` name 解析（T2A.5））。
- `tsundoku/model/TsundokuNovelSource.kt`：`ContentSource + TachiyomiXSourceAdapter` 包装（NOVEL/HENTAI_NOVEL、`preferenceNamespace="tsundoku:$pkg:$id"`、`name=sourceKey=TSUNDOKU_{id}`、`baseUrlOrNull`、语言后缀 displayName），等价于 MihonMangaSource 的小说形态。

### 测试 APK fixture（T0.3，fixtures/）
- `:fixtures:tsundoku-14-single`（1.4 direct）、`:fixtures:tsundoku-14-factory`（1.4 factory，含一个漫画对象）、`:fixtures:tsundoku-16-suspend`（1.6 suspend + SourceTracker/RateLimited/RefreshContext）、`:fixtures:tsundoku-ambiguous`（双 feature）。
- 编译依赖 `:app:exportTachiyomiAbi` 导出的 host ABI jar（`app/build/libs/tachiyomi-abi.jar`，自检 7 个小说 ABI 类；debug 产物未混淆），不复制 ABI 源码、不依赖在线仓库（`compileOnly` + 缓存传递依赖）。
- 清单形态对齐真实 NovelSourcery（`tachiyomi.novelextension.class/.factory/.nsfw/.novel`、`tachiyomix.name/contentWarning/extensionLib`、相对类名 `.Name`、包前缀 `eu.kanade.tachiyomi.novelextension.{lang}.{slug}`）。
- 纯 Java 实现（避开 Kotlin 默认方法 JVM 形态），suspend 覆写签名见 `fixtures/README.md`。
- 产物（全部离线 BUILD SUCCESSFUL + aapt2 badging/xmltree 验证）：
  - `fixtures/tsundoku-14-single/build/outputs/apk/debug/tsundoku-14-single-debug.apk`（1.4.1，feature `tachiyomi.novelextension`）
  - `fixtures/tsundoku-14-factory/build/outputs/apk/debug/tsundoku-14-factory-debug.apk`（1.4.2）
  - `fixtures/tsundoku-16-suspend/build/outputs/apk/debug/tsundoku-16-suspend-debug.apk`（1.6.1，metadata `extensionLib=1.6`、`novel=1`）
  - `fixtures/tsundoku-ambiguous/build/outputs/apk/debug/tsundoku-ambiguous-debug.apk`（1.4.3，双 `uses-feature`）

## 验证证据

- `TachiyomiApkLoaderRuntimeTest`（13 用例）：metadata 抽取/错误措辞、lib 白名单门（1.5 → LIB_VERSION）、direct/工厂加载、漫画对象逐源保留、重复 ID 诊断、工厂抛错整包失败、无默认构造类失败、非 Source 实例拒绝、端到端加载。
- `TsundokuExtensionLoaderTest`（6 用例）：严格 spec、info 抽取、漫画对象拒绝保留合法源、双 feature AMBIGUOUS 结构化错误、跳过非 Tsundoku 包、lib 越界错误。
- `TsundokuExtensionManagerTest`（2 用例）：`TSUNDOKU_9001` 解析、结构化失败透传。
- `TsundokuNovelSourceTest`（6 用例）：身份/命名空间/contentType、`SourceRequestContext`/`MihonRequestContext` 共享 seam 消费（T1.4 evidence）。
- 全量单测基线：1646 用例全绿（Phase 2A 后复跑确认，见提交）。

## 对应计划任务映射

| 计划任务 | 落地 | 证据 |
|---|---|---|
| T2A.1 宿主 ABI | source/*.kt（前轮） | TsundokuNovelAbiTest |
| T2A.2 严格分类 + 歧义拒绝 + ABI 白名单 | TachiyomiApkClassifier(strict) + spec 白名单 + loader AMBIGUOUS 分支 | ClassifierTest + LoaderTest |
| T2A.3 direct/factory + 逐源验证 + 重复 ID 诊断 | TachiyomiApkLoaderRuntime.loadFromClass | RuntimeTest |
| T2A.4 系统/私有版本 + 包广播 reload | ExternalApkCandidateResolver(VERSION) + facade runtime 包观察者 | ResolverTest + ManagerTest |
| T2A.5 Manager + TSUNDOKU_ 解析 | TsundokuExtensionManager（facade 前缀解析） | ManagerTest |
| T0.3 离线 fixture | fixtures/ 模块（离线 build） | assemble + aapt badging/xmltree 验证 |

## 结构化错误一览

| 场景 | phase | message 前缀 |
|---|---|---|
| 缺 ApplicationInfo/versionName/meta-data/source class/APK path | METADATA | "No ..." |
| lib 版本非法或不在白名单 | LIB_VERSION | "Invalid lib version format" / "Incompatible lib version" |
| ClassLoader 构建失败 | CLASSLOADER | "Failed to create ClassLoader" |
| 类实例化失败 / 工厂抛错 | INSTANTIATION | "Failed to load source class ..." / "SourceFactory ... failed" |
| 漫画对象 / 非 Source / 重复 ID | SOURCE（逐源拒绝，不整包失败） | "Not a novel source" / "not a Source" / "Duplicate source ID" |
| 双 feature | AMBIGUOUS（掃描期 Error 条目） | "declares both 'tachiyomi.novelextension' and 'tachiyomi.extension'" |

## 遗留项 / 下一阶段
- Phase 3A：把 `TsundokuExtensionManager` 挂进统一源目录（`getSourcesForPackage` 的 TSUNDOKU 分支）、NovelSourcery 仓库安装、`TsundokuExtensionRepository`。当前 manager 可由 UI 初始化（`initialize()` + `loadExtensions()`），但尚未接入任何入口。
- 设备端验证：fixture APK 的 dex 级加载（`ChildFirstPathClassLoader`）需要 instrumented test / 真机；JVM 单测覆盖运行时逻辑。
- 信任（签名）通道（Untrusted）仍为占位（facade `untrustedPackageNameOf = { null }`），属 Phase 3A。
