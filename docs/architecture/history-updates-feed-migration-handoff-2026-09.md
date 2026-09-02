# 历史/更新/订阅三页 Komikku 对齐迁移 — 阶段交接（2026-09-02）

> 目的：在任何机器上从当前进度继续实施
> 计划：[`history-updates-feed-komikku-alignment-plan-2026-09.md`](./history-updates-feed-komikku-alignment-plan-2026-09.md)
> 参考实现：收藏页 [`favourites-komikku-migration-handoff-2026-09.md`](./favourites-komikku-migration-handoff-2026-09.md)（本迁移的模板）

## 1. 当前进度总览

| Phase | 状态 | 交付物 |
|---|---|---|
| F（Feed 订阅页） | ✅ 完成 | `TrackerReadDao`（窄投影 Flow）+ `FeedSnapshotStore` + `FeedDeriver`；`FeedViewModel.pagingContent = null`；`FeedRouteContent` 静态渲染；paging 工厂已删 |
| U（Updates 更新页） | ✅ 完成 | `UpdatesSnapshotStore`/`UpdatesSnapshot`/`UpdatesDeriver`；`UpdatedTopLevelRouteContent` 静态；`pagingUpdatedContent`/`createUpdatedPagingSource` 已删 |
| H（History 历史页） | ✅ 完成 | `HistoryLibraryReadDao`（6 Flow，含 downloaded）+ `HistoryLibrarySnapshotStore` + `HistoryLibraryDeriver` + `HistoryCardMapper`；`HistoryListViewModel` 全静态；旧链路删除详见 §3 |
| X（收尾） | ✅ 完成（Macrobenchmark 除外） | 零 paging 残留验证（§4）、`BatchMappingPagingSource` 零调用方、全量 JVM/设备回归通过；Macrobenchmark release 基线 ⬜ 未做 |

**本次会话修复**：历史页「本地」（Downloaded）quick filter 无效——旧分页路径本来就是 no-op 存根，快照迁移时补齐了真实实现（§3.3）。

## 2. 三页共享模式（与收藏页一致）

每页都是同一四层结构，全部 Room invalidation 驱动：

```
ReadDao（窄投影 Flow，无过滤参数）
  → SnapshotStore（combine 多路 facets → 全量快照，distinctUntilChanged）
    → Deriver（排序/过滤/分组全内存，纯函数）
      → ViewModel（静态 StateFlow<List<ListModel>>，pagingContent = null）
```

历史页文件：`history/domain/library/`（Snapshot/SnapshotStore/Deriver/CardMapper/Snapshot.kt）
Feed/Updates 文件：`tracker/domain/feed/` 与 `tracker/domain/updates/`

`GlobalFavoritesState` 继续作为 groupTab/sourceTags 的共享状态源，三页派生输入统一从这里取。

## 3. 历史页迁移要点（H Phase 详情）

### 3.1 新链路

- `HistoryLibraryReadDao` 6 个 Flow：base rows（每活跃实体一行，含 display 投影/tracking 摘要/pinned/收藏 membership/metadata authority）、tag facets、binding facets（space 过滤数据）、category facets、overrides、downloaded rows。
- `HistoryCardEntry.uiId = -(entityId shl 8 or (contentTypeOrdinal + 1))`——与收藏页相同的负数 UI id 编码；删除/导航解析走 `rowsByUiId` 索引。
- 删除动作：ui id → `rowsByUiId` → `displayMangaId ?: anchorMangaId` → `HistoryRepository.delete(mangaIds)`（内含 owner ref 解析）。
- 空态判定沿旧语义：`filters.isEmpty() && groupTab == All && sourceTags.isEmpty()` 决定「无历史」vs「无筛选结果」——space/preset 不算筛选条件。

### 3.2 已删代码

- `WorkHistoryDao.pagingSource`（120 行 SQL）+ `HistoryLibraryPagingRow`
- `WorkAggregateRepository.createHistoryPagingSource`/`buildHistoryPagingAggregates`/`toTrackingSummary`
- `HistoryListViewModel`：`observeAllWithHistory` 消费、`mapHistoryPage`、preview 构建链（`buildPreviewStateOrNull`/`mapList`/`mapPreviewList`/`syncSelection`）、fold/`HistoryGroup` 累积映射、`applyHistoryPagingPresentation`
- `HistoryPreviewCache`（@Singleton）+ `HomeViewModel` 的 preview snapshot 构建（`ContentDataSnapshot.historyWithMetadata` 字段一并删除）
- `HistoryRepository.observeAllWithHistory`/`buildObservedHistoryList`/`filterPreviewItems`/`historyComparator`/`prewarmTrackAggregatesIfNeeded`/`getCachedTrackAggregate`
- `HistoryListQuickFilter.previewFilterItem`
- 测试：`WorkAggregateHistoryPagingTest`（整个文件）、`WorkPagingDaoTest` 的 3 个历史 paging 用例 + 5 个孤儿 helper

### 3.3 Downloaded filter 修复（本次）

旧 `matchesHistoryFilters` 的 `ListFilterOption.Downloaded -> true` 是存根（旧 `observeAllWithHistory` 的 `requiresLocalMapping` 路径也从未覆盖 paging 路径）。快照链路补齐：

- `HistoryDownloadedRow` + `observeHistoryDownloadedRows()`（work_history ⋈ entity_binding(local_manga/0) ⋈ local_index，与 favourites 的 `observeDownloadedFavouriteRows` 同形）
- `HistoryCardEntry.isDownloaded` 字段（store 里按 entityId 折叠）
- `HistoryLibraryDeriver`: `Downloaded -> isDownloaded`
- 测试：deriver JVM 用例 + store 仪器用例（`downloadedRowsFoldIntoTheRow`）

## 4. Phase X 验证记录（2026-09-02）

- `grep androidx.paging history/ tracker/` 残留：仅剩与收藏页一致的接缝（`pagingContent: Flow<PagingData>? = null` + 可空 `LazyPagingItems` 参数），零实质使用
- `BatchMappingPagingSource`：**零调用方**（类本身保留，等 remotelist 等最后调用方审计后统一处理——见 §5）
- `LargeLibraryPagingConfig`：仅剩类定义，无引用
- `MangaQueryBuilder`：剩余调用方 `TrackLogsDao`/`TracksDao`/`SuggestionDao`（条件构建用途，非 paging）
- 全量 JVM 单测通过；历史 library 套件 27 用例（deriver 21 + mapper 6）
- 设备回归（Xiaomi M332BF / Android 16）：`HistoryLibrarySnapshotStoreTest` 9/9、`WorkPagingDaoTest` 7/7、历史页渲染/quick filter/无崩溃冒烟通过

## 5. 后续备忘（未完成项）

1. **Macrobenchmark（release）**：三页静态路径 vs 收藏页基线的对比未跑。预期与收藏页一致（同模式），但需实测确认。
2. **`BatchMappingPagingSource` 退役**：零调用方后可删。`core/paging/` 剩余基础设施（`LargeLibraryPagingConfig` 等）一并评估。
3. **`ContentDataSnapshot.historyWithMetadata` 删除的连锁**：HomeViewModel 的 `recentHistoryWithMetadataFlow` 仍在（`recentHistoryFlow` 从它派生）——如果 home rail 不再需要 metadata 版本，可进一步收窄查询。
4. **history 折叠页界差异**：计划里标注的「页界折叠分裂」已随全量快照自然消除（派生在全列表上进行），迁移说明中作为修复记录。

## 6. 验证命令

```bash
# JVM 单测（三页 library 套件）
./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.history.domain.library.*"
./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.tracker.*"

# 设备回归
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class org.skepsun.kototoro.history.domain.library.HistoryLibrarySnapshotStoreTest \
  org.skepsun.kototoro.debug.test/org.skepsun.kototoro.HiltTestRunner
```

## 7. 设备/构建备忘

- MIUI 设备 `adb install` 不支持 `-g`（INSTALL_GRANT_RUNTIME_PERMISSIONS 拒绝）；`adb shell input tap` 被禁（INJECT_EVENTS）——UI 操作需手动完成，dump 用 `uiautomator dump /sdcard/ui.xml` + `cat`。
- Gradle connectedAndroidTest 在该设备上 orchestrator 启动失败（StatusCode 134），改用 `adb shell am instrument` 直跑。
- work_history 列序（schema 78）：`entity_id, anchor_manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters, parent_chapter_id`——手写 `INSERT INTO work_history VALUES` 时 percent 在第 8 位、deleted_at 第 9 位。
