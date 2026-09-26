# Entity/Work 系统移除 —— 交接（2026-09）

> 状态（2026-09-26 第二轮）：审查 + 设备测试共修出 **4 个会导致崩溃或数据丢失的缺陷**与若干数据正确性问题（§6）。
> 验证：`compileDebugKotlin` / `compileDebugAndroidTestKotlin` 0 错误；`testDebugUnitTest` 全绿；
> 设备（M332BF, API 37）：本次涉及的 androidTest 全部通过（迁移、DAO、快照、备份恢复共 70 项），
> 真实用户库副本迁移结果与预期逐项一致（§6.5）。所有改动尚未提交。
> 本文档是继续这条工作线的唯一权威交接，替代会话内上下文。

## 1. 目标与已达成的架构决策

用户目标：**彻底移除 Entity Graph / Work ownership，回归 projection-first / content-first**，
并保证已有版本数据、备份、Google Drive 同步的合理迁移。

冻结的决策（不要重新讨论）：

| 决策 | 内容 |
|---|---|
| 一次性移除 | 不做分阶段弃用、不留多版本兼容层；`org.skepsun.kototoro.entitygraph` 与 `org.skepsun.kototoro.work` 整体删除 |
| 状态所有者 | 全部用户状态（收藏 / 历史 / 统计 / 偏好 / 追踪 / scrobbling）直接挂在持久化的 `manga` 行（`manga_id`）上 |
| `entityId` 命名残留 | 读取模型（`FavouriteCardRow.entityId`、`HistoryCardRow.entityId`、`TrackerReadRows.entityId`、`DetailsOrigin.EntityGraph`、`DetailsNavKey.entityId`、`SpaceRouteSnapshot.WorkDetails.entityId`）**保留字段名**，语义退化为「锚点投影 manga_id」。重命名会牵动 ~40 个 UI/导航点与已持久化的 space session JSON，收益低风险高。DAO 里一律写成 `manga_id AS entity_id` |
| `DetailsOrigin.EntityGraph` | 保留为「打开这个 manga 的详情」的导航 origin；`DetailsViewModel` 已按 `initialProjectionLocalMangaId ?: preferredLocalMangaId ?: entityId` 取值 |
| `projectionCount` 语义 | 退化为 1（`localMangaIds` 只剩自身），所有 "×N 投影" 徽标自然不再出现；字段不动 |
| 旧备份 / Drive 兼容 | `BackupSection` 保留全部 legacy key（`WORK_*`、`ENTITY_GRAPH_*`、`PROJECTIONS`）；v1/v2/v3 旧备份的 `WORK_*` 段**读取后按 `anchor_manga_id` 落到 `favourites` / `history` / `stats`**，`ENTITY_GRAPH_*` 段 drain 后忽略。备份的 wire 字段 `owner_id` / `entity_id` 保留（保证旧 JSON 可反序列化），但不再参与身份判定 |
| DB 迁移 | `DATABASE_VERSION = 84`，`Migration83To84` → `ProjectionOwnershipMigrationResolver.migrate(db)`，物理 DROP 全部 8 张 entity/work 表 |

## 2. 已完成并自查通过的工作

### 2.1 数据库层
- `MangaDatabase`（v84）不再注册任何 entity/work 实体，新增只读 DAO：`HistoryLibraryReadDao`、`FavouriteLibraryReadDao`、`TrackerReadDao`。
- `Migration83To84` + `ProjectionOwnershipMigrationResolver`（788 行，纯逻辑可单测）已完成：
  work_favourites→favourites、work_history→history（含 chapter 归属/reading-session 反歧义）、
  entity_preferences→preferences、work_stats→stats、tracks/track_logs/scrobblings/tracking_site_links 重建去掉 `owner_id`/`entity_id`。
- **本轮修复的 KSP 阻塞**：`TagsDao`、`MangaDao`（4 条查询）、`ChaptersDao` 中残留的 `work_*` / `entity_*` SQL 已全部改写为 `history` / `favourites` / `stats` / `preferences` 上的投影查询。KSP 现在能过（可进入 Kotlin 编译阶段）。
- `core/db/Tables.kt` 新增 `TABLE_STATS`；`TABLE_WORK_*` / `TABLE_ENTITY_*` 常量**故意保留**——迁移解析器仍需用它们引用旧表名。

### 2.2 Scrobbling 层（本轮完成，无残留）
`ScrobblingOwnership.kt` 已重写为纯投影语义（`findScrobblingByManga` / `deleteScrobblingByManga` /
`upsertScrobbling` / `upsertScrobblingForManga` / `upsertScrobblingPreview` / `rebindScrobblingToManga` /
`observeByMangaCandidates` / `findByMangaCandidates`）。

本轮清掉了 7 个 Repository + 7 个 Scrobbler 子类 + `ScrobblingModule` + `ScrobblerConfigViewModel`
的 `workResolver` 依赖、旧函数名 import、以及 `ScrobblingEntity` 构造参数（`entityId` 已是只读计算属性）。

### 2.3 其它已完成
- `core/BaseApp.kt`：删除 `EntityGraphMigrationWorker` / `EntityNameCollisionRepairWorker` 入队、
  `requiresWorkMigrationNormalization` 归一化分支、EntryPoint 里的 `workResolver()`。
- `backups/`：`FavouriteBackup` / `HistoryBackup` / `StatisticBackup` 删除 Work*Entity 次构造；
  `TrackBackup` / `TrackLogBackup` / `ScrobblingBackup` 的 `toEntity()` 改为投影直构（wire 字段保留）；
  `AppBackupAgent` 去掉 `workResolver` 注入。
- `sync/domain/SyncHelper.kt`：`getHistory` / `getFavourites` 改读投影表（含 tombstone：`findAllEntriesIncludingDeleted()`），
  upsert 走 `HistoryDao.upsertSync` / `FavouritesDao.upsert`；删除 4 个 entity 解析辅助函数。
- `sync/domain/SyncGcCoordinator.kt`、`favourites/ui/container/FavouritesContainerViewModel.performDeduplication()`
  改为投影语义（去重 = 删除重复 manga 行，FK CASCADE 带走其状态）。
- `core/parser/ContentDataRepository.kt`：删除 entity-prefs 层（`getEntityMetadataSourceSelection(s)`、
  `setEntityMetadataSourceSelection`、`setEntityPreferredLocalMangaId`、`getOverridesForWorkItems`），
  统一到 `preferences` 上的 `get/setMetadataSourceSelection(s)`；invalidation tracker 改为监听 `preferences` / `favourites`。
- `favourites/data/FavouritesDao.kt`：补齐投影方法 `findActiveNewest` / `findActive(categoryId)` /
  `findAllActiveByMangaIds` / `countCategories` / `observeCountActive`；`FavouritesRepository` 已切换到这些名字。
- `history/data/HistoryDao.kt`：补 `isActive` import、`updateFromEntity()`（原来 `update(entity)` 解析到了 8 参数重载）。
- `tracking/discovery/domain/EntityType.kt` **删除**——`TrackingDiscoveryModels.kt` 里已有同名枚举（重复声明）。
- `details/domain/RelatedContentUseCase.kt`：去掉 `EntityGraphRepository` 与 `boundProjectionKeys`（投影优先下永远为空）。
- `details/domain/DetailsProjectionFilter.kt`：`isWorkContentTypeCompatibleWith` → `core/model/ContentTypeHeuristics.kt`
  新增的 `isSameContentFamilyAs`（原测试保留并迁移为 `core/model/ContentTypeFamilyTest.kt`）。
- `space/data/DefaultSpaceSessionValidator.kt`：去掉 `WorkResolver`，`WorkDetails` 路由按「不破坏性丢弃」策略原样保留；测试已重写。
- `space/ui/MediaUniverseViewModel.kt`：`mergeMediaUniverseItems` 改为按 `Content.id` 合并；测试已重写。
- `tracking/discovery/domain/TitleSimilarity.kt`：新增 `normalizeStrictTitleKey(value, sourceNames)` 与
  `stripTrailingSourceTitleSuffix`。
- `search/domain/ContentSearchRepository.kt`、`suggestions/ui/SuggestionsViewModel.kt`、
  `tracker/ui/feed/FeedViewModel.kt`（metadata selection 改按 mangaId）、`home/ui/HomeViewModel.kt`：
  去掉 `WorkResolver`，聚合退化为「每条内容自成一项」。
- `defaultSpaceSessionValidator` / `MediaUniverse` / `ContentSearch` 相关单测已同步重写。
- **物理删除**（本轮执行，`git status` 可见 91 个 D）：
  `entitygraph/`（20 文件）、`work/`（11 文件）、`favourites/ui/migration/`（15 文件）、`favourites/work/`（2 文件）、
  6 个 dead use case、`WorkFavourite*` / `WorkHistory*` / `WorkStats*` Entity+DAO+RestoreMerge、
  `favourites/data/FavouriteLibraryRepresentative.kt`（无引用）、
  以及 test 侧 `entitygraph/`、`work/`、`favourites/ui/migration/`、6 个对应测试文件。

### 2.4 与 Antigravity 会话的关系
本轮接续的是 Antigravity 会话 `3f16a746-6bdf-407a-b69d-1f31fcc3d7d6`（因配额 429 中断）。
Antigravity 留下了大量**半迁移、不编译**的文件（会话内自述「已完成」的部分实际不可编译），
本轮已修复其中系统性的部分并完成物理删除；错误数从约 250 降到 147。

## 3. 如何继续（构建循环）

```bash
cd E:\kototoro_demo\Kototoro
# JAVA_HOME 必须指向 JDK 17+（当前 D:\Java\jdk17\jdk-17.0.16+8）
.\gradlew :app:compileDebugKotlin --console=plain 2>&1 | Select-String -Pattern "^e: "
# 收敛后：
.\gradlew :app:testDebugUnitTest --no-daemon
.\gradlew :app:compileDebugAndroidTestKotlin   # androidTest 尚未处理，见 §5
```

本轮使用的辅助脚本保留在 `.dsh_tmp_antigravity/`（未跟踪，请勿提交）。

**注意（第二轮）**：此前构建反复报 JVM 内存不足，根因是 C 盘被占满（页面文件无法扩展）。已删除过时的
`C:\Users\chuxi\.gradle\caches\build-cache-1`（24GB；构建缓存已改到项目根 `.gradle/build-cache`），
并把 Gradle 用户目录迁到 `E:\gradle-home`（用户环境变量 `GRADLE_USER_HOME`）。
androidTest 需要设备：本机目前没有连接设备，也没有 AVD。

## 4. 编译收敛（2026-09-26 完成）

原 147 个错误已全部清掉，要点（便于回溯行为变化）：

- `HistoryDao` 新增投影查询 `findRecent` / `findRecentForSpace` / `findRecentForSpaceAndSources`
  （按 `manga.content_type IN allowedTypes` 过滤，与 `TracksDao`/`SuggestionDao` 同口径；`content_type` 为 NULL 的行不进入空间）、
  `observeCountActive`、`findActiveMangaIds`，`update(entity)` 公开。
- `HistoryRepository.findRecentContents`：NSFW 过滤后若不够数，按 32→64→… 扩大窗口（恢复旧 "resume 跳过整批成人内容" 行为）。
- `TrackingRepository` 补回 `mergeWith(ContentTracking)`（`RESULT_EXTERNAL_MODIFICATION`）、`clearReadUpdates`、
  `clearUpdates`、`clearCounters`、`clearLogs`、`getLogsCount`；owner 一律为 `manga_id`。
  `TracksDao.findByOwnerId`、`TrackLogsDao.findDuplicate(ownerId,…)` 旧重载、`repairWorkIdentities`、`resolveTrackOwnerId` 已删除。
- `TrackingLogItemMapper` 未读判断改为 `manga.id in unreadOwnerIds`（旧逻辑用 `-mangaId`，投影优先下永远不匹配）。
- `DetailsViewModel`：删 entity 残留；`bindReadingCandidateToTracking` = `storeContentAndReturn` 后切换到该 manga；
  关系分区 `buildEntityRelationSections()` 不再依赖 entityId（只看当前 tracking 详情）。
- 收藏「疑似重复」提示（`FavoriteDuplicatePrompt` / `DuplicateFavoritePromptDialog`）已无触发源，端到端删除；
  `favourite_duplicate_*` 字符串经 Weblate 管理，未动（可后续清理）。`MainShellScene` 的 `organizeMessages` effect 删除。
- 外部备份导入：`ExternalBulkImportPlanner.kt` 重建为仅含 `BulkImportEntry`（按 `manga_id` 合并），无 entity 分配。
- `GoogleDriveSyncMerger.compactSnapshot`：content 按 `ProjectionIdentityKeys.contentCompactKey`（同 source + url/publicUrl）
  合并，保留最小 id，并把 history/favourites/stats/tracks/logs 重定向到该 id 再做 LWW。
  旧的镜像站 / http↔https / url↔publicUrl 交叉 / syncId 合并属于 entity 推断，**有意不再支持**。
- `RestoreSemanticContext.isAuthoritativeSchema`（原 `isAuthoritativeWorkSchema`，同定义）供 WebDAV 自动恢复判断是否回传合并快照。
- `MigrateUseCase`：迁移时整行 `preferences` 跟随到新 manga（投影优先下偏好即用户状态）。
- 清理欠账已做：`AppSettings` 的 `isEntityGraphMigrated` / `isLegacyFavouriteProjectionMigrationCompleted` /
  `isLegacyEntityNameCollisionRepairCompleted` / `requiresWorkMigrationNormalization`（及 KEY）、
  `FavouritesRepository`/`HistoryRepository` 的 `normalizeWork*` / `ensureLegacy*` no-op、`SuggestionsViewModel` 死代码。
- 单测：`HistoryRepositoryResumeFilterTest`、`FavouritesFeedCategoryIdsTest`、`GoogleDriveSyncMergerTest`、
  `KotatsuBackupPayloadCompatTest`、`ScrobblingInfoIdentityTest`、`TrackingLogItemMapperTest` 等已按投影语义重写。

## 5. 尚未处理的区域

- **androidTest 已迁移（第二轮）**，但**尚未编译验证、未在设备上跑过**：
  - 删除（测的是已删除概念）：`WorkPagingDaoTest`、`SpaceWorkDaoTest`、`FavouriteLibraryAggregateChainCharacterizationTest`。
  - 重写为投影语义：`FavouriteLibrarySeed`（共享 fixture）、`FavouriteLibraryReadDaoTest`、`FavouriteLibrarySnapshotStoreTest`、
    `HistoryLibrarySnapshotStoreTest`、`HistoryLibraryReadDaoScaleTest`（语料种子）、`TracksDaoTest`、`TrackerReadDaoTest`、
    `FeedSnapshotStoreTest`、`UpdatesSnapshotStoreTest`、`RestoreCheckpointTest`、`AppBackupAgentTest`（旧 Kotatsu 备份历史数 5→6）。
  - `RestoreCheckpointTest` 新增两条旧备份用例：v3 `WORK_*` 段按 anchor 落到投影；MERGE 时较新墓碑不被复活。
  - `Migration83To84Test` 开启 dropped-table 校验，新增边界用例（索引存在、遗留新行 LWW、entity-only 行按绑定回落）。
  - `MangaDatabaseTest` 中 65→66 / 74→75 等历史迁移用例操作的是当时真实存在的 entity 表，**保留不动**。
- **清理欠账**（剩余，低优先级）：`HistoryLibraryDeriver` 等处提到 `WorkHistoryDao` 的注释；
  `toUiGroupId` 在 `HistoryLibrarySnapshotStore` / `UpdatesSnapshotStore` 仍按 entityId 生成 UI id（语义已退化为 manga_id，行为正确，只是命名残留）。
- **行为回归需要人工验证的点**：
  1. 旧备份（含 `work_*` 段）恢复后，收藏分类 / 历史进度 / 阅读统计是否全部落到正确 manga；
  2. Google Drive 同步在 v2↔v3 之间的合并；
  3. 详情页「换源」(`selectActiveLocalSource`) 在投影优先下应只切 manga，不再有 entity 偏好持久化；
  4. `HistoryListViewModel` / feed 的分组卡片不再出现 "×N 投影" 徽标（预期行为）。

## 6. 第二轮：迁移审查结论（2026-09-26）

### 6.1 修复的缺陷
| 严重度 | 位置 | 问题 | 修复 |
|---|---|---|---|
| **P0 升级必崩** | `ProjectionOwnershipMigrationResolver` 重建 `track_logs` / `tracking_site_links` | v83 已有同名索引（`index_track_logs_manga_id`、`index_tracking_site_links_manga_id`、`index_tracking_site_links_service_remote_id`）。在 `*_new` 上 `CREATE INDEX IF NOT EXISTS` 被静默跳过，`DROP TABLE` 又带走旧索引 → Room 校验失败，所有 v83 用户首次启动崩溃 | 索引改为在 `DROP` + `RENAME` 之后创建 |
| P1 数据 | 同上 `migrateHistory` / `migrateFavourites` | v83 旧 `history`/`favourites` 表可能残留未规范化的更新数据（恢复后 normalization 未跑、或收藏未解析），被 work 行 `INSERT OR REPLACE` 无条件覆盖 | 先用旧表行做种子，再按 LWW 合并 |
| P2 数据 | 同上 tracks / scrobblings / links / logs | `manga_id` 无效时（links 直接要求 `!= 0`）整行丢弃 | 统一 `ownerMangaIdSql()`：自身 manga 存在则用之，否则回落到 entity 最优本地绑定 |
| P2 数据 | 同上 stats | 只取 `work_stats`，丢弃旧 `stats` 表 | 两表 UNION 后按 `(manga_id, started_at)` 取 MAX |
| P2 数据 | `BackupRepository` 恢复 `HISTORY` / `WORK_HISTORY` | `HistoryDao.upsert` 走“活跃写入”语义，强制 `deleted_at = 0`；旧备份的删除墓碑在 MERGE 时被复活 | 改用 `upsertSync`（保留墓碑） |
| **P0 数据丢失** | `BackupRepository.restoreBackup` SNAPSHOT_REPLACE | 上一轮把 `WORK_HISTORY`/`WORK_FAVOURITES`/`WORK_STATS` 的清表映射到投影表后，它们（新备份里为空数组）在 `HISTORY`/`FAVOURITES`/`STATS` 恢复**之后**再次清表 → 用当前格式备份做快照恢复会丢光历史和收藏（设备测试发现） | 按 `restoreTarget` 每张表每次恢复只清一次；断点续传时已完成节的目标表视为已清 |
| **P0 升级必崩** | 迁移 EPUB / 阅读会话归属查询 | 列名写错（`epub_chapter_mapping` 无 `manga_id`；`end_time` 应为 `end_at`），真实数据走到该分支即崩（设备测试发现） | 见 §6.5 |
| P2 行为 | `BackupPayloadGuard` | 恢复旧 v3 备份时因“WORK 状态引用的 entity 缺失 / sync_id 重复”拒绝整份恢复，而投影优先只需要 anchor | 删除 entity/sync_id 校验；保留 identity-only、缺失分类、缺失 anchor 三项 |

### 6.2 随之删除的死代码
`BackupOrphanReviewActivity`（及 Manifest 声明）、`ActiveWorkStateMissingEntityException`、`BackupOrphanInfo`/`BackupOrphanReport`、
`BackupPayloadGuard.WorkEntityMissingSyncIdException`、`BackupService` 的孤儿复核流程与 `allowDiscardingActiveWorkState` 参数链。
`backup_orphan_*`、`backup_guard_work_entity_missing_sync_id`、`favourite_duplicate_*` 字符串由 Weblate 管理，未删除。

### 6.3 验证基础设施的坑
- `app/schemas/` 被 `.gitignore` 忽略；本地 `83.json` 曾被重构中途的构建覆盖（与 84 完全相同），导致上述 P0 无法被
  `Migration83To84Test` 发现（该测试在污染的 schema 上根本跑不通）。已从 HEAD（v83 代码）用 KSP 重新生成真实 `83.json` 覆盖回去。
- **建议**：把 `app/schemas/` 纳入版本控制（Room 官方推荐），否则迁移测试的基准会随本地构建漂移。

### 6.4 对整体思路的判断
- 投影优先 + 一次性迁移 + 保留读模型字段名 + 保留备份 wire 字段，这套取舍是合理的；风险集中在 **DB 迁移** 与 **旧备份/同步兼容**，
  这两处都已有自动化用例覆盖，但**必须在真机/模拟器上跑一次 `connectedDebugAndroidTest`**（至少 `Migration83To84Test`、
  `RestoreCheckpointTest`、`MangaDatabaseTest`）后才能发版。
- 有意放弃的能力（需产品确认）：Drive 同步不再合并镜像站 / http↔https / url↔publicUrl 交叉的“同一作品”；
  详情页不再有“多投影聚合卡片”；收藏不再提示“疑似重复”。
- `BackupSection` 仍在新备份里写出空的 `WORK_*` / `ENTITY_GRAPH_*` 段（恢复时 drain）。无害；若确认不需要降级兼容可改为不写。


### 6.5 设备验证（2026-09-26，M332BF / API 37）
- 运行方式：`installDebug` + `installDebugAndroidTest`（`adb install -r` 保留数据）后用 `am instrument -e class ...`，
  **不要用 `connectedDebugAndroidTest`**（结束时会卸载 debug 包）。`RestoreCheckpointTest` / `AppBackupAgentTest` 会对
  注入的真实 `kototoro-db` 执行 `clearAllTables()`，只能在无重要数据的设备上跑。
- 设备测试又抓到**两处迁移 SQL 写错列名**（均会在真实数据上让升级崩溃），已修复：
  - EPUB 归属查询：`epub_chapter_mapping` 没有 `manga_id`，列名为驼峰 `internalChapterId`/`parentChapterId`；
    改为经映射取父章节，再在 `chapters` 中找拥有它的候选 manga。
  - 阅读会话归属查询：`reading_sessions` 列名是 `end_at` 而非 `end_time`。
  - 已用脚本将解析器 SQL 中所有标识符对照真实 v83/v84 schema 核验。
- `Migration83To84Test` 原 fixture 缺 v83 必填列（`entity.name_hash`、`favourite_categories.deleted_at`、唯一 `sync_id`），
  在真实 v83 schema 上从未跑通，已补齐。
- 新增 `RealDataMigrationTest`（opt-in）：把 v83 库副本以 `realdata-83.db` 放进 debug 包 `databases/` 即运行，
  否则跳过；走生产迁移链 + Room 校验并在 logcat `RealDataMigration` 输出行数。真实用户库副本结果：
  history 1416（有效 1284）、favourites 317（有效 309 / 258 部）、stats 5、preferences 8、tracks 200、track_logs 104、
  外键违规 0——与按规则离线计算的期望完全一致。丢弃的 6 条 work_favourites 均为无绑定的删除墓碑。
- 真实用户库备份：`E:\kototoro_demo\debug-db-backup-20260926-1640\`（含 WAL）。
- `MangaDatabaseTest` 中 `versions`（历史遗留 `Migration24To23` 导致不连续）与需要 1/65/74 等旧 schema JSON 的用例
  是既有失败，与本次改动无关；根因同 §6.3（schemas 目录未纳入版本控制）。
