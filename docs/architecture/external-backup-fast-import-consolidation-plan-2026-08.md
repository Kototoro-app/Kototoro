# 外部备份快速导入与延迟实体整理方案（2026-08）

## 背景

外部备份（Mihon / TachiyomiSY / Aniyomi 等）导入走 `ExternalBackupImportService` →
`ExternalBackupRepository.import`。当前实现在单事务内**逐条**处理记录，每条都执行：

- `workResolver.ensureForProjection` → `EntityGraphRepository.ensureLocalWorkEntity`
  （每条一个嵌套事务：`findEntityByLocalMangaId` + `findBindingBySourceKey` + 新条目走
  `resolveOrCreateEntity` → `pickCandidate` 扫描最多 `ENTITY_SCAN_LIMIT=120` 个 WORK 实体逐个打分）
- 之后 `resolveByEntityId` 再查 2~3 轮，收藏/历史各若干次 upsert

6000+ 收藏 ≈ 数万次串行 SQLite 往返（含调度/嵌套事务开销），耗时分钟级。
对照 DropSauce `MihonBackupManager`（一次性解码 → 纯内存构建 → 单事务按表批量写）为秒级。

## 目标

1. **阶段 1（批量导入）**：附加式纯写入，零逐条查询/模糊匹配，秒级完成。
2. **阶段 2（实体整理）**：把"同一作品跨源/同源重复"的合并从逐条导入中剥离，
   改为导入后一次性批量归组 + 合并，幂等可重跑。

非目标：不改变 KOTOTORO_CURRENT 格式恢复的 SNAPSHOT_REPLACE 语义（见文末可选工作流）。

## 阶段 1：批量导入

改动主体：`ExternalBackupRepository`（新增 `importBulk`，替换现有 `import` 的循环体）。

单事务内按序执行：

1. **源解析**：沿用 `SourceMatcher`（candidates 已缓存，非热点）。
2. **纯内存构建**（每条记录，零 DB 访问）：
   - `mangaId = generateContentId(record)`（现状不变，确定性 hash）
   - 临时 WORK 实体：`EntityRecord(type=WORK, primaryName=title, nameHash, contentType, …)`
   - 两条 binding：`(local_manga, mangaId, createdBy=IMPORT)`、
     `(sourceName, bindingKey, sourceKind=READING_SOURCE, createdBy=IMPORT)`
   - `WorkFavouriteEntity(entityId=<临时实体>, anchorMangaId=mangaId, …)`（每分类一行）
   - `WorkHistoryEntity(entityId=<临时实体>, anchorMangaId=mangaId, …)`
3. **按表批量写**：
   - `TagEntity`：`getTagsDao().upsert(List)`（已有 List 版本）
   - `EntityRecord`：新增 `insertEntities(List<EntityRecord>): List<Long>`（Room @Insert 支持集合，
     返回 id 按下标对齐，内存映射，零查询）
   - `MangaEntity`：逐条 `upsert(manga, tags)`（单事务内可接受，DropSauce 同款；可选加批量）
   - `WorkFavouritesDao` / `WorkHistoryDao`：新增 `upsert(List<…>)`
   - `EntityGraphDao`：新增 `upsertBindings(List<EntityBindingRecord>)`（@Upsert 支持集合）
4. 产出 summary，并把本次导入的 entityId 集合写入阶段 2 的 Worker input。

**临时实体标记**：`EntityBindingCreatedBy` 新增 `IMPORT` 枚举值（`entity_binding.created_by`
为 TEXT 列存枚举名，无 schema 迁移）。阶段 2 以 `created_by=IMPORT` 的 local_manga binding
圈定"待整理集合"。现状 `WorkIdentityProvenance.IMPORT.toBindingCreatedBy()` 映射到 INGEST，
需同步改映射或区分（避免与解析器 INGEST 混淆）。

## 阶段 2：实体整理（Consolidation）

- 新 `EntityConsolidationWorker`（`entitygraph/work/`，CoroutineWorker + @HiltWorker，
  参照 `EntityGraphMigrationWorker`）。由 `ExternalBackupImportService` 在阶段 1 完成后
  enqueue（UNIQUE）；另在设置中提供手动触发。
- **幂等**：分组是派生状态，中断/重跑安全。

步骤：

1. **批量读**（2~3 个 IN 查询）：待整理集合的 entity records、local bindings、manga rows；
   另取现有库 entity 的 `(name_hash, content_type)` 索引用于挂接既有实体。
2. **内存分组**（两级 union-find，直接参照 `EntityIdentityResetPlanner.ResetProjectionDisjointSet`）：
   - L1 强键：`source|location|url` / `publicUrl` —— 抓**同源**重复（零成本）；
   - L2 标题：规范化标题 / `name_hash` + contentType 兼容 —— 抓**跨源**重复
     （跨源 URL 不同，强键抓不到；此即 `DefaultEntityBindingMatcher.scoreNames` 的精确命中路径）；
   - 对既有库：`name_hash` 精确命中且 contentType 兼容 → 该组挂接到既有实体，而非新建合并组。
3. **选 canonical**：参照 `buildResetCanonicalScores`（收藏/历史 updatedAt 新近度优先，最小 id 兜底）。
4. **合并落盘**，按组成员构成走两条路径：
   - 组内**全为临时实体**（无 relations / prefs / tracking ownership）→ 快路径：
     批量 `UPDATE … SET entity_id=? WHERE entity_id IN (…)` 重映射
     binding / work_favourites / work_history / work_stats，再 delete 被吸收的 entity 行；
   - 组内**含既有实体** → 走现有 `mergeEntities`（复用其 name_hash 冲突吸收、别名合并、
     `remapWorkOwnedState` 重映射收藏/历史/统计的完整逻辑）。
5. 报告：合并组数、吸收实体数、挂接既有实体数。

**执行位置（二选一，推荐 A）**：

- A. 同一 service 内联执行：阶段 1 完成后、展示完成通知前跑阶段 2。
  窗口期 ≈ 0；合并调用次数 = 重复组数 k（远小于 n），预计秒级。
- B. Worker 异步 + UI 过滤未整理临时实体。仅当 A 实测不可接受时再引入（需额外 UI 过滤逻辑）。

## DAO 新增清单

| DAO | 新增 |
|---|---|
| `WorkFavouritesDao` | `upsert(List<WorkFavouriteEntity>)` |
| `WorkHistoryDao` | `upsert(List<WorkHistoryEntity>)` |
| `EntityGraphDao` | `insertEntities(List<EntityRecord>): List<Long>`、`upsertBindings(List<EntityBindingRecord>)` |
| （可选）`WorkFavouritesDao` / `WorkHistoryDao` | `remapEntityIds(Map<Long, Long>)` 批量版 |

## 测试

- 单元：`ExternalBackupBulkImportTest`（payload → 阶段 1 → 断言 manga/收藏/历史/实体/binding
  行数与字段）；`EntityConsolidationPlannerTest`（同源重复、跨源同名、contentType 冲突不合并、
  挂接既有实体、canonical 选择、空组/单元素组）。
- 集成/冒烟：6000 记录阶段 1 耗时上限；阶段 1 中断重跑幂等；阶段 2 中断重跑收敛。
- 回归：现有 `backups/`、`entitygraph/` 相关测试全绿；`./gradlew :app:compileDebugKotlin` +
  `:app:testDebugUnitTest`。

## 风险

- **标题合并误伤**（同名不同作品）：与现状 `pickCandidate` 风险相当；且全局分组消除了
  现状"顺序依赖 + LIMIT 120 窗口"的不确定性。后续可为 L2 组提供"人工确认"入口。
- **`IMPORT` 枚举新增**：TEXT 存储无迁移；旧数据不受影响。
- **收藏行随临时实体 remap**：`WorkFavouriteEntity` 主键含 entityId，重映射后即最终态，
  无需迁移；与 `remapWorkOwnedState` 既有行为一致。

---

## 可选工作流（独立 PR）：KOTOTORO_CURRENT 恢复提供"替换 / 合并"选择

现状（用户观察到的差异）：

- KOTOTORO_CURRENT 恢复 = `RestoreMode.SNAPSHOT_REPLACE`（`RestoreService.kt:101-104`），
  逐节「先清后写」（`BackupRepository.kt:846-847` 注释；`1032-1034` / `1055-1057` 触发
  `clearRestoreTargets`），对所选节清空 `favourites` / `work_favourites` / `history` /
  `work_history` 等表（`BackupRepository.kt:1185-1268`）——**备份里没有的既有收藏/历史会被删除**。
  设计动机：快照语义 + 逐节先清后写支撑断点续传（崩溃只波及当前节）。
- Mihon 外部导入 = 纯附加 upsert，从不删除。

方案：RestoreDialog 对 KOTOTORO_CURRENT 增加模式选择，默认保持"替换"并给明确警告；
"合并"复用 `RestoreMode.MERGE` 的 entityIdMapping 重映射机制（清空逻辑按模式跳过），
需验证 current-format 的 WORK_* / ENTITY_GRAPH_* 节在 MERGE 下的 id remap 正确性
（现有 MERGE 分支多带 `isLegacySemanticSchema` 条件，需逐节梳理）。

---

## 实施状态（2026-08 已完成）

- **阶段 1（批量导入）** ✅ `ExternalBulkImportPlanner`（纯内存构建 + 挂接/临时实体判定，
  含既有 local_manga binding 优先挂接，防止 binding PK 被抢占）+ `ExternalBackupRepository.import`
  单事务按表批量写；`EntityBindingCreatedBy.IMPORT` 枚举 + provenance 映射修正。
  测试：`ExternalBulkImportPlannerTest`；全量 `:app:testDebugUnitTest` 通过。
- **阶段 2（实体整理）** ✅ 按推荐方案 A 内联执行（`ExternalBackupImportService` 在阶段 1
  完成后调用 `EntityGraphRepository.consolidateImportProvisionalEntities()`，完成通知前出结果，
  未引入 Worker）。分组 = 强键 union-find + 规范化标题×contentType union-find（仅圈定
  createdBy=IMPORT 的临时实体，既有实体绝不入组）；canonical 优先未加盐 nameHash，最小 id 兜底；
  合并复用 `remapWorkOwnedState` + `remapBindingsAndRelations` + 删除被吸收行，幂等。
  测试：`EntityConsolidationPlannerTest`；全量通过。手动触发入口待后续按需添加。
- **可选工作流（替换/合并选择）** ✅ `RestoreService` 接受 `EXTRA_RESTORE_MODE`
  （仅 KOTOTORO_CURRENT 生效，legacy 恒为 MERGE；checkpoint id 纳入模式避免串断点续传）；
  `RestoreDialog` 对 KOTOTORO_CURRENT 显示「替换现有数据 / 与现有数据合并」单选（默认替换）。
  MERGE 复用现有 entityIdMapping 机制——WORK_* 节按锚点重解析 + upsert、ENTITY_GRAPH_* 节
  按映射重映射 + upsert，`clearRestoreTargets` 仅在 SNAPSHOT_REPLACE 触发，current-format
  各节无 legacy-only 门，逐节梳理确认可用。
