# Tsundoku Phase 3A 进度（2026-08）

> 集成计划：`docs/architecture/tsundoku-extension-integration-plan-2026-08.md`（§522-538 Phase 3A）
> 前置：Phase 0 / 1 / 2A / 2B 已落地并提交（2B = `755c6ee52`，Room 78 source_origins/source_refresh_state + 备份导出/导入 + SourceRecovery 六态派生）。
> 本阶段目标（Round 5）：NovelSourcery 仓库兼容、推荐未配置、统一管理可见、origin 生命周期。

## 完成项

### T3A.1 中性化 protobuf 索引模型 ✅
银行级重命名 `MihonExtensionStoreIndex` → `ExtensionStoreIndex`（`git mv` 保历史），连同
`ExtensionRepoService.fetchMihonExtensionStoreIndex/fetchMihonExtensionList` →
`fetchExtensionStoreIndex/fetchExtensionList`，kdoc 注明该格式同时服务 Mihon 兼容与
NovelSourcery 的 `index.pb`（extensions-lib `index.proto` 字段号完全一致）。
- 测试引用同步 + 新用例：`ExtensionStoreIndex` 解码 `fixtures/novelsourcery-index.protobuf`
  后 `name`/`signingKey` 非空、`extensionList.extensions` 非空。
- 残留检查：`app/src/main|test` 无 `MihonExtensionStoreIndex`/`fetchMihon*` 引用。

### T3A.2 NovelSourcery 推荐未配置 ✅
`UnifiedRecommendedRepositories.all` 追加：
```kotlin
UnifiedRecommendedRepository(
    kind = UnifiedSourceKind.TSUNDOKU,
    name = "NovelSourcery (Tsundoku novels)",
    url = "https://github.com/NovelSourcery/extensions/raw/repo/index.pb",
    locationType = UnifiedRepositoryLocationType.REMOTE_URL,
    capabilities = extensionRepoCapabilities,
    note = "Tsundoku novel extensions; protobuf index (index.pb)",
)
```
直接指向 protobuf 索引（**不是** repo 分支上只有诱饵条目的 `index.min.json`）。
推荐仓库绝不自动添加：`UnifiedRecommendedRepository` 为纯内存描述符，仅由用户在统一源管理
页手动添加后才经 `ExternalExtensionRepoRepository` 持久化；`withPresetRepositories` 自动把
未配置 preset 渲染为 `isConfigured=false`。测试 `UnifiedRecommendedRepositoriesTest` 固化。

### T3A.3 协议兼容 + 安装/更新生命周期（补强）✅
- `ExtensionRepoService`：TSUNDOKU 与 MIHON/ANIYOMI 并列纳入 `repo.json` 失败时的
  `$baseUrl/index.pb` 回退、以及 legacy JSON 失败时的 `$baseUrl/index.pb` 回退——
  第三方协议兼容仓库可手动添加并正确走 protobuf 目录。
- 安装路径复用既有流程（Round 2 已通）：`ExtensionInstallService.install(LOCAL_APK)` →
  `toLocalApkEcosystem()` = "tsundoku" → `storeManagedApk`；SYSTEM 模式 → `SystemPackageInstaller`。
- `InstalledExtensionSignatureValidator.getManagedPackageInfo` 增加 "tsundoku" 生态，
  LOCAL_APK 托管包的签名指纹可读；新增 `firstFingerprint(packageName)` 公共方法供 origin 记录。

### T3A.4 合并键 = ecosystem + package ✅（既有机制）
统一 catalog / 安装管理按 `package:TSUNDOKU:{pkgName}` 包引用分组，与 MIHON/ANIYOMI/IREADER
同包不同生态不冲突（`TsundokuNovelSource.name = TSUNDOKU_{id}` 前缀隔离）。

### T3A.5 / T3A.6 统一管理可见 + 无重复入口 ✅
- `UnifiedSourceCatalogRepository`：注入 `TsundokuExtensionManager`；`getInstalledApkSources()`
  纳入 Tsundoku 源；`observeRuntimeSourceChanges()` 四路 combine 加入 `tsundoku.changes`；
  `resolveKind`/`resolvePackageRef` 增加 `TsundokuNovelSource → TSUNDOKU` / `package:TSUNDOKU:*`
  映射；`buildSourceItems` 日志含 `tsundokuWrapped=`。新增 `internal getTsundokuApkSources()`
  测试缝（见下）。
- `ContentSourcesRepository`：新增 `getEnabledTsundokuNovelSources()`（镜像 Mihon，仅按
  NSFW 设置过滤），并接入 `getAllAvailableSources()` / `getAllAvailableSourcesForListing()` /
  `getEnabledSources()`（后者尊重 `disabledNames`）——浏览与搜索候选可见。
- 无重复入口：旧扩展浏览器 `ExternalExtensionType.TSUNDOKU` 显式空（既有代码），
  `ExtensionsBrowserTypeGuardTest` 固化为完成门槛。
- `AppBackupAgent` 两处手工 DI 图补齐 `tsundokuExtensionManager` 与 `TsundokuOriginRecorder`。

### T3A.7 origin 生命周期 ✅
- 新增 `tsundoku/TsundokuOriginRecorder.kt`：对每个 `TsundokuLoadResult.Success` 的每个源
  upsert `source_origins`（kind=TSUNDOKU, pkgName, sourceId, displayName=源名,
  contentType=NOVEL/HENTAI_NOVEL, versionName/Code, signingDigest=首指纹或 null, lastSeenAt/updatedAt）。
- `TsundokuExtensionManager` 通过 facade `loadResults` lambda 注入记录（覆盖 initialize /
  显式调用 / 包广播重载全部路径）；**卸载/缺失绝不删除 origin**（注册表长期保留，plan §6.8）。
- 测试 `TsundokuOriginRecorderTest`（7 例）：字段严格派生、NSFW→HENTAI_NOVEL、多源多行、
  失败/拒绝不写、空加载不删除、缺指纹不猜。

## 完成门槛核对
- [x] 完整包生命周期可用（NovelSourcery protobuf 目录 + LOCAL_APK 安装链路贯通；更新/降级/
  卸载走既有 install service）
- [x] 同包跨生态不冲突（TSUNDOKU_ 前缀 + package:TSUNDOKU:* 引用）
- [x] 推荐仓库不自动添加（纯描述符，未配置 preset）
- [x] 第三方协议兼容仓库可手动添加（protobuf 回退补强 + 复用 repo 添加流程）
- [x] 统一源管理与浏览可见 Tsundoku 源；旧浏览器无重复入口
- [x] 安装/扫描成功 upsert origin；卸载不删除

## 验证
- `:app:compileDebugKotlin --offline`：通过
- `:app:testDebugUnitTest --offline`：**1718 tests / 0 failures / 0 skipped**（上轮 1702）
- 全树 `grep -rn "MihonExtensionStoreIndex"` 干净（build/ 除外）

## 遗留 / 后续阶段
- 单测缝取舍：`UnifiedSourceCatalogTsundokuTest` 不 mock 兄弟 manager——IReaderExtensionManager
  的 final 方法在本模块 JVM 测试运行时无法被 every 拦截（relaxed/subclass 均落真实方法体，
  facade null 直接 NPE，已用隔离探针证实），Mihon/Aniyomi/Tsundoku 则正常。因此 catalog 测试
  走新增的 `internal getTsundokuApkSources()` 缝 + `toUnifiedSourceItem`（反射），不测
  `getInstalledApkSources` 整链；`observeSources` 全链需 instrumented 测试（后续阶段）。
- `externalExtensionRepositories` 对 TSUNDOKU 的安装检查（自动更新 stable 渠道、prerelease
  仅显式选择）拿到 Phase 3B 的「更新」粒度继续；本阶段安装管理与统一 UI 已就绪。
- androidTest `SourceOriginsMigration77To78Test`（Round 4 遗留）仍需真机/模拟器。
- 下一步：Phase 3B（小说仓库 + 阅读器消费 NovelSourcery 源）。
