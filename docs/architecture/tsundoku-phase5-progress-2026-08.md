# Tsundoku Phase 5 进度：缺失来源管理闭环（2026-08-22）

状态：**已完成并提交**。并行双代理实现（5A=UI 侧于 local-vllm、5B=状态/动作侧于 local-vllm-61），主会话统一集成 + 补齐生产快照。
验证：`:app:compileDebugKotlin` + Hilt 组件编译通过；`:app:testDebugUnitTest` = **1880 tests / 0 failures**（基线 1833 → +47）。

## 5A（UI，T5.1/T5.2/T5.3/T5.4/T5.6 的 UI 部分）
- 新建 `settings/sources/unified/UnifiedSourcesDeepLink.kt` + 测试（10）：
  - `UnifiedSourcesDeepLink(initialTab?, packageFilter?, sourceKey?)`；`fromUri`（query tab/package/source）/`fromExtras`（`EXTRA_INITIAL_TAB` 等）/`merge`（uri 优先）；tab 别名映射（sources/repos/installed/recovery），trim+lowercase，unknown→null。
- `UnifiedSourcesActivity`：onCreate 解析 intent → `applyDeepLink`；`onSaveInstanceState` 持久化 3 字段（**进程恢复 T5.2**）；侧载 OpenDocument ActivityResult → `onSideloadPicked`。
- `UnifiedSourcesScreenBody`：恢复过滤器 chip（MISSING 计数）+「N of M 来源需处理」摘要条 → `toggleRecoveryFilter`。
- `UnifiedSourcesLists/Display`：来源行状态色标（MISSING 红/REPOSITORY 橙/SIDELOAD 蓝/REIMPORT 青/SIGNATURE 紫）+ 恢复动作 chip（inFlight 转 spinner）。
- `UnifiedSourcesDialogs`：① 恢复结果对话框（成功/失败+原因+重试/关闭）② 签名确认对话框（重新关联→`confirmSignature`/保持未关联→`rejectSignature`）③ 侧载文件选择对话框。
- T5.3 入口：SettingsRootSections 扩展管理项摘要徽标（`RecoveryBadgeProvider.count()`）；SourcesSettingsScreen 新增「来源恢复」入口（深链 `initialTab=recovery`）。
- 新增 30 条 `recovery_*` 字符串（EN + zh-rCN）。

## 5B（状态/动作，T5.1–T5.6 核心）
- 新建 `settings/sources/unified/RecoveryUiState.kt`：`RecoveryUiState(missingCount, total, perSource, inFlightSourceKeys, actionResult, recoveryFilterActive)` + `RecoveryActionResult`.
- 新建 `extensions/recovery/RecoveryActionPlan.kt` + 测试（9）：`planRecoveryAction(status, origin)`；REPOSITORY_REQUIRED→InstallFromRepository(url)、SIDELOAD_REQUIRED→InstallSideload(pkg,kind)、REIMPORT_REQUIRED→Reimport(locator)、SIGNATURE→ConfirmSignature(digest)、MISSING/RESOLVED→NoActionMissing。
- 新建 `extensions/recovery/RecoveryActionCoordinator.kt` + 测试（10）：`snapshot: StateFlow<RecoveryUiState>`、`run/sideLoad/confirmSignature/rejectSignature/rescanAll`；真实 3A 管线执行 seam（ExtensionInstallService/LocalApkExtensionSupport/manager loadExtensions/JsonSourceManager）；单 Mutex + per-key 防重入；**执行后重新派生 → 解决后自动退出 missing**。
  - **T5.6 不变量（测试断言）**：`run()` 对 SIGNATURE_CONFIRMATION_REQUIRED 不安装、不写 digest；只有显式 `confirmSignature()` 用当前快照 digest upsert；`rejectSignature()` 为零 upsert 保持确认态——自动恢复永不跨签名。
- 新建 `extensions/recovery/RecoveryNotifier.kt`：恢复成功/缺失汇总通知（`recovery_status` 频道，全 runCatching）。
- 新建 `extensions/recovery/SourceMigrationPreselection.kt` + 测试（5）：`preselectAffectedWorks(sourceKey, worksBySource)` 去重纯函数；KDoc 记录 SourceMigrationPanel(initialSelectedContentIds) 复用入口（T5.5，UI 启动接线留给主会话/后续）。
- `UnifiedSourcesViewModel.kt`（2130 行，5B 独占改 +228）：构造注入 RecoveryActionCoordinator + SavedStateHandle；`recoveryState`（combine 协调器 snapshot + filter）、`toggleRecoveryFilter`（作用于 uiState 过滤）、`runRecoveryAction/onSideloadPicked/confirmSignature/rejectSignature/runRescanAll`、`applyDeepLink`（recovery tab→过滤开启、package→query、sourceKey→高亮 + `runHighlightedRecoveryAction`）、SavedStateHandle 进程恢复（`dl_*` 键）、`RecoveryBadgeProvider`。
- 测试：`RecoveryActionPlanTest`（9）、`SourceMigrationPreselectionTest`（5）、`RecoveryActionCoordinatorTest`（10）、`UnifiedSourcesRecoveryViewModelTest`（6）。
- 过程性修复：relaxed mock 的 `installedExtensions` val getter 不可靠 → 不再 mock 管理器属性；runTest 清理用 backgroundScope；stale test-results 清理。

## 主会话补齐：生产真实快照（T5.1「解决后自动退出 missing」的关键）
- Phase 2A 遗留：生产 `SourceRuntimeSnapshot` 一直是 `DefaultSourceRuntimeSnapshot`（no-op）→ 恢复动作即使安装成功也不会从 missing 退出。
- 新建 `extensions/recovery/ManagerBackedSourceRuntimeSnapshot.kt`（纯查询，注入 `(sourceId)→packageName?` 解析器）+ 测试（7）；`RecoveryModule.provideSourceRuntimeSnapshot` 从 4 个 manager（Mihon/Aniyomi/IReader/Tsundoku）的 `installedExtensions` 实时匹配 `PREFIX_<id>` 源键，digest 经 `InstalledExtensionSignatureValidator.firstFingerprint(pkgName)` 计算（T5.6 在真机可用）。
- 设计要点：因 `installedExtensions` 为 final val getter（MockK 不可靠拦截）且 ireader source-api 是 Java 21 字节码（Java 17 单测 worker 加载即崩），快照做成**函数式注入**、把类型化 id 提取留在 module —— 单测全是纯 lambda 驱动，生产无多余依赖。

## 测试与提交
- 全量单测 **1880 / 0**（Phase 5 新增 47：5A 深链 10 + 5B 30 + 快照 7）。
- 提交：`feat(sources): missing-source recovery loop`（Phase 5）。

## 已知剩余
- T5.6 真机验证（卸载换签→确认/拒绝→关联恢复）。
- T5.3 作品错误页 UI 为占位（恢复入口统一走 UnifiedSourcesActivity 深链；通知面由 RecoveryNotifier 提供）。
- T5.5 只提供预选纯函数 + 复用入口文档；SourceMigrationPanel 启动接线（UI 发起）留待 Phase 6 或用户显式要求。
- 离线 Tsundoku 仓库（REPOSITORY_REQUIRED 无目录候选）退化为侧载路径。
- 非 Tachiyomi 前缀键（JSON/TVBox/JS/CLOUDSTREAM/JAR）不参与快照解析——恢复仍走 origin 的 locator/package/repository 通道。
