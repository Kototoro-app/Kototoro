# Tsundoku Phase 2B 进度：source_origins 注册表、恢复派生与备份 schema 4（2026-08）

> 计划：`docs/architecture/tsundoku-extension-integration-plan-2026-08.md` Phase 2B（T2B.1–T2B.4）。
> 本文件记录 Phase 2B 落地形态、版本语义、证据与遗留项，供 Phase 3A/5 接力。

## 目标与边界（§6.6/§6.8/§8.1/§8.2）

- `source_origins` 是**长期 origin 注册表**（非一次性缺失表）：卸载不清除、导入/迁移后置为 resolved 但行保留。
- `isMissing` 不落库：`SourceRecoveryRepository` 用 origin + 运行时快照 + 作品引用**实时派生**。
- 备份 schema 3 → 4：新增可选 `SOURCE_ORIGINS` 节（不进入 Kotatsu/外部格式）。
- 恢复语义对既有备份逐字节不变（schema 3 不降级为 legacy）。

## 本阶段产物

### T2B.1 Room 77 → 78（DB 层）
- `core/db/entity/SourceOriginEntity.kt`：`source_origins` 表（`source_key` PK；`kind`/`display_name`/`content_type`/`package_name`/`source_id`/`repository_url`/`repository_name`/`locator`/`version_name`/`version_code`/`signing_digest`/`last_seen_at`/`updated_at`；`kind` 与 `repository_url` 索引）。
- `core/db/entity/SourceRefreshStateEntity.kt`：`source_refresh_state`（`source_key`+`content_id` 复合 PK；`last_success_at`/`last_attempt_at`/`last_error`/`updated_at`；双列索引）。仅优化用途、**不入备份**。
- `core/db/dao/SourceOriginsDao.kt`：getByKey / findAll / observeAll / upsert / deleteByKey / countByKey / **deleteAll**（SNAPSHOT_REPLACE 清节用）。
- `core/db/dao/SourceRefreshStateDao.kt`：get / findBySource / observeBySource / upsert / delete / deleteBySource。
- `core/db/migrations/Migration77To78.kt`：建两表 + 索引 + 保守回填（`substr` 精确前缀 `MIHON_`/`ANIYOMI_`/`IREADER_`/`TSUNDOKU_` → 对应 kind，未知前缀不生成；来自 `sources.source`；`INSERT OR IGNORE`）。
- `MangaDatabase`：`DATABASE_VERSION = 78`，注册实体/DAO/迁移。schema `78.json` 由 KSP 生成。
- `AppModule`：补 `provideSourceOriginsDao` / `provideSourceRefreshStateDao`（Hilt DAO 绑定，避免装配断链）。
- androidTest `SourceOriginsMigration77To78Test`（MigrationTestHelper 自 schema 77 真升 78 + 两 DAO CRUD）。

### T2B.2 恢复派生（领域层，`extensions/recovery/`）
- `SourceRecoveryStatus.kt`：`RESOLVED / MISSING / REPOSITORY_REQUIRED / SIDELOAD_REQUIRED / REIMPORT_REQUIRED / SIGNATURE_CONFIRMATION_REQUIRED` + `isMissing`。
- `SourceRuntimeSnapshot.kt`：严格查询型接口（isInstalled / currentSigningDigest / packageNameFor）+ 默认 no-op。
- `SourceRecoveryDerivation.kt`：纯派生 object。规则顺序：① 已装 + 记录/当前签名齐全且不一致 → SIGNATURE_CONFIRMATION_REQUIRED（缺任一侧不猜、回落 RESOLVED）② 已装 → RESOLVED ③ 有 `repositoryUrl` → REPOSITORY_REQUIRED ④ 有 `locator` → REIMPORT_REQUIRED ⑤ `packageName` 或包生态 kind → SIDELOAD_REQUIRED ⑥ 其余 → MISSING。
- `SourceRecoveryRepository.kt`：`SourceRecoveryState(origin, status, referenced)`；`deriveAll/observeAll/statusOf/upsert/remove/countByKey/referencedSourceKeys`；snapshot 与 `SourceReferenceProvider` 可注入（默认 no-op，Phase 2A+ 接真实 manager/catalog）。
- `RecoveryModule.kt`：Hilt 提供两个默认实现（DAO 绑定属 AppModule）。
- 单测：`SourceRecoveryDerivationTest`（9 用例全分支）+ `SourceRecoveryRepositoryTest`（DAO mockk 转发 + referenced 合并）。

### T2B.3 备份 schema 3 → 4
- `BackupSection.SOURCE_ORIGINS("source_origins")`：KOTOTORO 导出自动含、Kotatsu/legacy 格式化剔除。
- `backups/data/model/SourceOriginBackup.kt`：全字段 snake_case 序列化模型 + `toEntity/fromEntity`（除 `source_key` 外全可空，tolerant 解码）。
- `backups/domain/SourceOriginMaterializer.kt`：严格无猜测的“最小 origin 构造”：
  - `kindForSourceKey`：仅已知稳定前缀 → MIHON/ANIYOMI/IREADER/TSUNDOKU，其余 **null（绝不猜）**；
  - `minimalOrigin`：displayName（MihonMangaSource/TsundokuNovelSource 提供时）、contentType（已装源严格提供时）、packageName（`TachiyomiXSourceAdapter` 严格暴露时），其余置空。
- `BackupRepository.createBackup` 新增 `SOURCE_ORIGINS` 节：`materializeSourceOrigins()` = 全部既有 origin ∪（sources 注册表 ∪ 已装 catalog ∪ 作品引用 distinct source）的最小 origin。
- 索引语义：`BackupIndex.CURRENT_SYNC_SCHEMA_VERSION = 4` + 新增 `LEGACY_SEMANTIC_SCHEMA_BOUNDARY = 3`。
  - `RestoreSemanticContext.isLegacySemanticSchema` / `isAuthoritativeWorkSchema` 改用 boundary（schema 3 恢复语义不变）。
  - `BackupPayloadGuard.requireRestoreFormat`：CURRENT 接受 `>= 3`（含新 4），legacy 仅 `< 3`。

### T2B.4 恢复通道 reconcile
- `restoreSection(SOURCE_ORIGINS)`：`readJsonArray<SourceOriginBackup>()` → `upsert(toEntity())`（MERGE 按 source_key 幂等 upsert）。
- `clearRestoreTargets`：SNAPSHOT_REPLACE 对 `source_origins` 先 `deleteAll()` 再写（只清该节，不误删作品）。
- 旧备份回退：`SOURCE_ORIGINS !in archiveSections && SOURCES in restoredSections` 时 `materializeSourceOriginsIntoDb()`（从已恢复 sources + 作品引用补最小 origin，仅新增不覆盖）。新备份该节被用户取消勾选时不补（archiveSections 含该节）。
- checkpoint/WebDAV/AppBackupAgent/周期备份走同一 `restoreBackup` 路径，无需分支。
- `MangaDao.distinctSources()`：`SELECT DISTINCT source FROM manga WHERE source <> ''`（作品引用来源）。

## 版本语义决策（重要）

schema 4 只**新增**可选节；若用 `semanticSchemaVersion < CURRENT` 判定 legacy，schema 3 会被降级走 legacy 工作态复活路径，破坏既有恢复。因此：
- legacy 边界常量 `LEGACY_SEMANTIC_SCHEMA_BOUNDARY = 3`（≤2 legacy，≥3 当前语义）；
- writer 发 `CURRENT_SYNC_SCHEMA_VERSION = 4`；guard/语义上下文按 boundary 比较；
- 既有 `BackupPayloadGuardTest`（字面 3 = current）不改仍绿；新增 schema-4 用例。

## 验证

- 全量单测：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline` → **1702 tests / 0 failures / 0 skipped**（上轮 1674，本轮 +28）。
- `:app:compileDebugKotlin` 与 `:app:compileDebugAndroidTestKotlin` 均 BUILD SUCCESSFUL。
- androidTest 迁移测试（MigrationTestHelper 自 schema 77 JSON 真升 78 + 回填断言 + 两 DAO CRUD）需设备实跑：`./gradlew :app:connectedDebugAndroidTest`。

## 遗留项（接力 Phase 3A/5）

- `SourceRuntimeSnapshot` / `SourceReferenceProvider` 仍是 no-op 默认：Phase 2A+ 用 Tsundoku/Mihon manager 已装 catalog 接真实实现（`isInstalled` 按 sourceKey、`currentSigningDigest` 按签名 digest、referenced 用 content catalog 缓存）。
- 卸载/安装生命周期写入 `last_seen_at` / `updated_at` 的接线（manager uninstall listener，Phase 3A）。
- Phase 5 缺失来源恢复 UI 消费 `SourceRecoveryRepository.observeAll()`。
- Tsundoku repo 安装（Phase 3A）后 origin 的 `repository_url/repository_name` 填充。
