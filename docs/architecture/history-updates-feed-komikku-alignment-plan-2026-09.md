# 历史 / 更新 / 订阅三页 Komikku 技术流对齐实施计划（2026-09）

> 前置：收藏页已完成同构迁移（Phase 0–7），全部验证通过并推送。
> 模板代码：`favourites/data/FavouriteLibraryReadDao.kt`（窄投影）、`favourites/domain/library/FavouriteLibrarySnapshotStore.kt`（快照深模块）、`favourites/domain/library/FavouriteLibraryDeriver.kt`（纯内存派生）、`favourites/domain/library/FavouritesCardMapper.kt`（卡片映射）、`list/ui/ContentListHost.kt`（UI seam）。
> 交接文档：`favourites-komikku-migration-handoff-2026-09.md`（性能基准：10k 实体快照 202ms、派生 <150ms、全链路零逐行查库）。

## 1. 目标与范围

把收藏页验证过的技术流推广到三个页面：

| 页面 | 现状核心问题（与迁移前收藏页同构） |
|---|---|
| 历史 History | 宽行分页（`HistoryLibraryPagingRow` = `WorkHistoryEntity`+`MangaEntity`）；每页 `buildHistoryPagingAggregates` 批量补查 bindings/preferences；VM 每页内存过滤+折叠+取 pinnedIds（`favouritesRepository.getPinnedIds` 每页一次）；10 个排序全在 SQL CASE；preview cache 补丁式首屏 |
| 更新 Updates | `TracksDao.pagingUpdatedContent` 的 ORDER BY 内嵌相关子查询 `pinnedSortExpr`（**每行**算一次 MAX(pinned)）；FAVORITE/Tag/NSFW 过滤同为相关子查询；每页 `buildTrackingAggregates` + VM `resolveManyByEntityIds` + `getEntityMetadataSourceSelections`（逐页查库）；跨页累积的 grouped*Ids 映射（Volatile 可变状态） |
| 订阅 Feed | 两条 paging 源都在 **LIMIT 有界窗口**上分页（`feedLimit` 默认 200；`track_logs` 被 gc `trim(TRACK_LOG_RETAINED_SIZE=120)` 截断）——Paging3 纯开销；已有静态 fallback 路径双轨并存 |

**逐页共用的问题**：过滤/排序参数变化 → `flatMapLatest` 重建整个 Pager → 全列表重查重排；分页边界破坏实体折叠（history 相邻折叠、updates 实体聚合在页边界会分裂/累积错位）；无法像收藏页那样"filter 切换不闪空列表"。

## 2. 现状链路速查（Phase A 研读结论）

### 2.1 历史（history/）

- **UI 宿主**：`MainShellScene.kt:1427`（`HistoryTopLevelRouteContent`，直收 `pagingContent` 为 LazyPagingItems）+ `HistoryActivity` 入口；`history/ui/HistoryListViewModel.kt`（978 行）。
- **SQL**：`WorkHistoryDao.pagingSource(orderName, space, types, sources, tab)` —— 10 个排序（LAST_READ/LONG_AGO_READ/NEWEST/OLDEST/PROGRESS/UNREAD/NEW_CHAPTERS/UPDATED/ALPHABETIC/ALPHABETIC_REVERSE + 默认 updated_at DESC）全部 SQL CASE；tie-break `wh.entity_id ASC`；space/tab 过滤用 EXISTS/NOT EXISTS(entity_binding) 在 SQL。
- **每页变换**：`BatchMappingPagingSource("history-aggregate")` → `buildHistoryPagingAggregates`（每页 async 批量查 `findActiveLocalBindingsByEntities` + preferences + tracking）→ `HistoryRepository.mapPagingAggregates`（SQL filters 内存重放）→ VM `mapHistoryPage`（preset/groupTab/sourceTags/NSFW/blacklist 内存过滤 → `foldAdjacentByEntity` 相邻同实体折叠 → pinnedIds 每页查 → 映射 ListModel）。
- **分组头**：`applyHistoryPagingPresentation`（insertSeparators 注入日期头 + incognito InfoModel 头）。
- **死代码**：`observeHistory()`（私有、无调用方）、`limit` StateFlow（初始 32、从未增长）——迁移时删除。
- **PreviewCache**：`HistoryPreviewCache`（Singleton）由 **HomeViewModel:556** 喂（home 最近阅读 rail），历史页 `buildPreviewStateOrNull` 机会性读取首屏。迁移后：快照 store 天然提供即时首屏，preview 路径删除，home rail 改读同一 store（或保留 home 自身小限窗查询，见 §5 决策 D3）。
- **量级**：work_history 行数 = 读过的 work 数（用户 6.5k 收藏量级，上限估 ~10k）。收藏页已证 10k 快照 202ms——同量级可行。

### 2.2 更新（tracker/ui/updates/）

- **UI 宿主**：`MainShellScene.kt:1269`（`UpdatedTopLevelRouteContent`，自管选择状态/顶栏 pill，直收 LazyPagingItems）；`UpdatesViewModel.kt`（312 行）+ `UpdatesPageGroups.kt`（groupTrackingByEntity 聚合逻辑）。
- **SQL**：`TracksDao.pagingUpdatedContent(filters)`：`WHERE chapters_new > 0` + SQL 过滤（FAVORITE/Tag/NSFW 相关子查询，含 `representativeLocalMangaIdExpr` 嵌套子查询）+ `ORDER BY pinnedSortExpr(tracks.manga_id) DESC, last_chapter_date DESC, entity_id ASC, manga_id ASC`（**排序键本身是相关子查询**）。返回宽 `TrackEntity`。
- **每页变换**：`BatchMappingPagingSource("updates-aggregate")` → `workAggregateRepository.buildTrackingAggregates`（每页 resolveProjectionSet）→ VM `mapUpdatesPage`：`filterVisible`（groupTab/sourceTags/NSFW/blacklist 已在内存）→ `aggregateByEntity`（`workResolver.resolveManyByEntityIds` + `dataRepository.getEntityMetadataSourceSelections` **每页查库**）→ `UpdateGroup`（uiId/mangaIds/totalNewChapters/representative/metadataSourceSelection/lastChapterDate）。
- **跨页累积可变状态**：`groupedRemovalIds`/`groupedEntityIds`/`groupedPreferredLocalIds`（@Volatile Map，`+` 合并，flatMapLatest 时清空）——分页边界即错误窗口（页界处同实体聚合分裂）。
- **actions**：`remove(ids)`→`clearUpdates`；refresh→`TrackWorker.requestCheckNow`；`resolveEntityIdForUiItemId` 桥接。
- **量级**：`chapters_new>0` 的 tracks 子集——通常几十几百，批量检查后最坏几千。全量快照毫无压力。

### 2.3 订阅/Feed（tracker/ui/feed/）

- **UI 宿主**：`MainShellScene.kt:740`（`FeedRouteContent`：`fallbackItems` 静态 + `feedPagingItems`，**paging 优先、空则 fallback**——双轨已存在）。
- **数据源（均有界）**：
  - `showAllUpdates=false`：`TrackLogsDao.pagingAll(limit, filters)`（`track_logs` 表，`ORDER BY pinned DESC, created_at DESC, id DESC`，LIMIT=feedLimit 设置值默认 200；表本身被 `TrackingRepository.gc()` `trim(120)` 维护）。
  - `showAllUpdates=true`：`TrackingRepository.createAllTrackingLogItemsPagingSource(limit, filters)`（tracks 全表 + LIMIT，每页 `buildTrackingAggregates` + `resolveAllTrackingLogItems` 合并 logs）。
  - fallback：`observeUpdatedContent(UPDATED_CONTENT_LOOKAHEAD_SIZE=200, filters)` **已经是静态 Flow**（invalidation tracker 驱动重查）。
- **每页变换**：`resolveDisplayTrackingLogItems`（display content 解析）→ VM `mapFeedPage`（feedScope + blacklist 内存过滤）→ `applyFeedPagingPresentation`；静态路径 `buildStaticFeedContent`（`groupByDateBucket` 日期桶 + EmptyState）。
- **feedScope**：选中收藏分类（`selectedCategoryId`→`mangaCategoryIds`）+ groupTab + sourceTags + preset；与收藏页共享 `globalFavoritesState` 的 tab/tags。
- **actions**：`markAsRead(logId)`、删除章节 prompt、下载 prompt、`clearFeed(clearCounters)`。
- **量级**：≤ 数百行。快照化零风险。

## 3. 三页共同语义（characterization 必须固化）

1. **pinned 恒定排序之首**（三页的 `pinnedSortExpr` 同源：`work_favourites` 的 MAX(pinned)，绑定存在且 anchor 非空且未删）。
2. **tie-breaker 链**：history `entity_id ASC`；updates `last_chapter_date DESC, entity_id ASC, manga_id ASC`；feed `created_at DESC, id DESC`。
3. **display projection**：COALESCE(preferred_local, anchor)；entity 身份由 `entity_binding`（source IN local_manga/0，state IN MANUAL/CONFIRMED/LEGACY）决定（`TracksDao.entityIdExpr` 的 SQL 语义 = 快照侧的 binding facets）。
4. **SQL-level filter 的内存等价**：FAVORITE（按分类/任意）、Tag（tagId = `"${key}_${source.name}".longHashCode()`，作用在 representative 上）、NSFW；updates 的 `chapters_new > 0` 是数据集定义不是过滤器。
5. **VM 内存过滤**：preset（源名集合）、groupTab（content/origin group 匹配）、sourceTags（OR）、NSFW 设置（per-page：`isTrackerNsfwDisabled`/`isHistoryExcludeNsfw`/feed 的 `isFeedExcludeNsfw`）、`GlobalTagBlacklist`。
6. **实体折叠/聚合**：history 相邻折叠（同 entityId+contentType 相邻才折叠、顺序敏感、**全列表语义**——现状分页会在页界分裂）；updates 全集实体聚合（uiId/entityId/mangaIds/totalNewChapters=Σ newChapters/representative=显示投影）。
7. **分组头**：history 日期头（按 order 的 header()）、updates `calculateDateGroup(lastChapterDate)`、feed `groupByDateBucket(createdAt)`。
8. **`entity_id` 为 NULL 的行**（无绑定/本地未迁移）：history 单独行不折叠（uiId=manga.id）；updates/feed 的 entityId null 条目现状被 `buildTrackingAggregates` 的 `entityIds.mapNotNull` **丢弃**（注意：这是现状语义，快照路径必须保留——track 无 entity 即从更新列表消失）。
9. **gc/维护语义**：updates/feed 的 `gcIfNeeded()`（onStart）、`TrackWorker` 检查驱动 `tracks`/`track_logs` 变更（invalidation 天然触发快照重发）。

## 4. 目标架构（复用收藏页模板）

```text
history/data/HistoryLibraryReadDao.kt        # 窄投影：HistoryCardBaseRow（display+progress+history+tracking 摘要+membership pinned/created）
history/domain/library/HistoryLibrarySnapshotStore.kt   # 全量 work_history 快照（含 binding facets + preferred + pinned membership facets）
history/domain/library/HistoryLibraryDeriver.kt        # 10 排序 + 折叠 + 分组头 + 过滤（preset/groupTab/sourceTags/NSFW/blacklist/space）
tracker/data/TrackerReadDao.kt（或 updates/data/）    # 窄投影：UpdateCardRow（chapters_new>0 集合）+ TrackLogRow（120 行窗口）
tracker/domain/updates/UpdatesSnapshotStore.kt        # 快照（含 metadataSourceSelection facets 批量读）
tracker/domain/updates/UpdatesDeriver.kt             # 实体聚合 + 日期分组（全列表，无页界）
tracker/domain/feed/FeedSnapshotStore.kt（可并入 updates store） # track_logs 120 行 + tracks 有更新集合
list/ui/ContentListHost 适配器 ×3          # 三页 route 供 AppContentListRoute（或保留定制 route，换静态 StateFlow）
```

**复用**（直接 import，不复制）：`ContentListHost` seam、`AppContentListRoute` 静态分支、`GlobalFavoritesState`、`ContentListMapper`/`FavouritesCardMapper` 的轻量 Content 构造模式、`GlobalTagBlacklist.containsTagTitle`。

## 5. 关键设计决策（已定）

- **D1 窗口语义推翻计划原文**：原 favourites 计划写"历史/更新仍有明确窗口语义，保留 Paging"。Phase A 实测：Feed 窗口有界（120 行 trim）；Updates 子集天然有界（chapters_new>0 的 tracked works）；History 全量 ~10k 且收藏页已证 10k 快照 202ms。**三页全部走完整快照 + 内存派生**，不做混合。
- **D2 状态所有权**：History/Updates 各建一个 screen-level state holder（对齐 `FavouritesContainerViewModel` 的 `libraryState` 模式）；三页 route 的 selection/top-bar pill 状态保留在 route（现状即如此，不动）。Feed 的 `feedScope`（分类选择）留在 FeedViewModel。
- **D3 home rail 不动**：`HomeViewModel` 的最近阅读 rail 继续用 `findRecentHistoryAggregates`（小限窗、已有语义），但 `HistoryPreviewCache` 的历史页消费端删除（快照即首屏）；cache 本体保留给 home 或一并清理（迁移中验证 home 是否还需要）。
- **D4 死代码先删**：`observeHistory`/`limit` StateFlow（history VM）。
- **D5 顺序**：先 Feed（最小、双轨已在、风险最低）→ Updates（中）→ History（最大）。每页独立提交序列，失败可单独回退。
- **D6 共享 Paging 基础设施不删**：`BatchMappingPagingSource`/`LargeLibraryPagingConfig` 仍被其他页使用（remotelist/search/suggestions 等迁移前不删，最终 Phase 收尾时再评估）。

## 6. 实施阶段

### Phase F（Feed，最小闭环）
1. F0 characterization：`FeedViewModel` 的 fallback 路径语义（date buckets、scope 过滤、EmptyState）已有 `FeedPagingPresentationTest` 等固化；补 `track_logs`/`tracks` 窄投影语义 characterization（pinned 排序/tie-break/120 行窗口/showAll 两源合并）。
2. F1 `TrackerReadDao` 窄投影：`observeTrackLogRows()`（Flow，含 display 解析所需外键）+ `observeTracksWithUpdates()`；无过滤参数。
3. F2 `FeedSnapshotStore`：合并两源 → `FeedSnapshot`（log items + updated tracks + unread 计数）；invalidation 触发即重发。
4. F3 派生：`feedLimit`/showAllUpdates/quickFilter/feedScope 全内存。
5. F4 UI：`FeedRouteContent` 删 `feedPagingItems`，`fallbackContent` 升级为唯一路径（本质是把静态路径改为读 store）；`pagingContent` 返回 null。
6. F5 删 `createTrackingLogPagingSource`/`createAllTrackingLogItemsPagingSource`/`FeedPagingPresentation` 的 paging 分支 + `db.getTrackLogsDao().pagingAll`。

### Phase U（Updates）
1. U0 characterization：`UpdatesPageGroups` 聚合语义 + 排序 tie-break + `chapters_new>0` + pinned 语义 + entity-null 丢弃语义。
2. U1 `UpdatesReadDao`：`observeUpdateTrackRows()`（chapters_new>0 全集，窄列）+ `observeEntityFacets()`（binding/preferred/metadataSourceSelection 批量）。
3. U2 `UpdatesSnapshotStore`：快照 = rows + facets；per-entity 聚合一次完成。
4. U3 `UpdatesDeriver`：groupTab/sourceTags/NSFW/blacklist/pinned/日期分组/排序全内存；`UpdateGroup` 派生（替换 @Volatile 累积映射）。
5. U4 UI：`UpdatedTopLevelRouteContent` 改静态 StateFlow；actions（remove/markAsRead/refresh）走 `FavouriteItemRef` 式引用（entityId/displayMangaId）。
6. U5 删 `pagingUpdatedContent`/`createUpdatedPagingSource` + `UpdatesViewModel` 的 paging 分支 + `MangaQueryBuilder` filters 若再无调用方。

### Phase H（History）
1. H0 characterization：10 排序全序（含 tie-break、null title 处理对齐收藏页决定）、相邻折叠（全列表语义——**固化目标语义而非现状页界语义**）、SQL filter 内存等价、space/tab 过滤。
2. H1 `HistoryLibraryReadDao`：`observeHistoryCardBaseRows()`（窄）+ facets（binding/preferred/pinned membership）。
3. H2 `HistoryLibrarySnapshotStore`：全量快照。
4. H3 `HistoryLibraryDeriver`：10 排序 + 折叠 + 日期分组头 + 全部过滤；`HistoryUiParams` 派生输入。
5. H4 UI：`HistoryTopLevelRouteContent`/`HistoryActivity` 切静态；删 `HistoryPreviewCache` 历史页消费；`observeHistory`/`limit` 死代码删除。
6. H5 删 `WorkHistoryDao.pagingSource`/`createHistoryPagingSource`/`HistoryLibraryPagingRow`/`applyHistoryPagingPresentation` + `buildHistoryPagingAggregates`。

### Phase X（收尾）
- 全量 JVM + 设备回归（沿交接文档 §5 流程）；
- `rg "androidx.paging" history/ tracker/` 确认零残留；
- 评估 `MangaQueryBuilder`/`BatchMappingPagingSource` 剩余调用方（remotelist 等），写后续迁移备忘；
- 更新 `favourites-komikku-migration-handoff-2026-09.md` 或新建三页交接文档；
- Macrobenchmark（release）对照收藏页基线。

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| History 全量快照内存（10k 行 × 列） | 收藏页同量级已证（10k=202ms、内存 <50MB 增量）；快照行复用窄列模式 |
| updates 的 entity-null 行为 | U0 先固化"丢弃"语义，快照路径显式保留（broken row 概念不适用于 tracks——track 无 entity 即无展示单元） |
| history 折叠页界差异（行为变化） | H0 固化全列表语义为**目标**语义；迁移说明里标注"页界折叠分裂"为修复的缺陷而非回归 |
| home rail 依赖 preview cache | D3：home 独立查询先行验证 |
| TrackWorker 高频更新触发快照重发 | invalidation 驱动 + distinctUntilChanged（收藏页模式）；track_logs 仅 120 行重读成本可忽略 |
| 三页共享 groupTab/sourceTags 的联动 | 快照派生输入统一从 `GlobalFavoritesState` 取（现状即共享，不新增耦合） |

## 8. 测试与验证

- 每页 Phase 0 characterization 先行（JVM 为主，DAO 语义用 androidTest in-memory Room + raw SQL 种子，沿 `FavouriteLibrarySeed` 模式）。
- 规模断言：history 10k、updates 5k（模拟批量检查后）、feed 120——快照构建 <500ms、派生 <150ms（对齐收藏页预算）。
- 设备回归沿交接文档 §5 的 MIUI 流程（核心破解跨签名安装 + `am instrument`）。

## 9. 提交序列（每页独立、可回退）

```
test(feed): capture tracker feed semantics and window bounds        # F0
feat(feed): add tracker read projections and feed snapshot store    # F1-F3
refactor(feed): render the feed from one shared state holder        # F4-F5
test(updates): capture update aggregation semantics                  # U0
feat(updates): add updates read projections and snapshot store       # U1-U3
refactor(updates): render updates from one shared state holder       # U4-U5
test(history): capture history list semantics                        # H0
feat(history): add history read projections and snapshot store       # H1-H3
refactor(history): render history from one shared state holder       # H4-H5
docs: three-page komikku alignment handoff                           # X
```
