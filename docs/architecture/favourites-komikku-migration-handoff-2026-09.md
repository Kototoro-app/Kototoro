# 收藏页 Komikku 对齐迁移 — 阶段交接（2026-09-01）

> 目的：在任何机器上从当前进度继续实施
> 计划：[`favourites-komikku-alignment-implementation-plan-2026-09.md`](./favourites-komikku-alignment-implementation-plan-2026-09.md)
> 基线：[`favourites-komikku-phase0-baseline-2026-09.md`](./favourites-komikku-phase0-baseline-2026-09.md)
> 分支：`devel`（提交见文末 git log）

## 1. 当前进度总览

| Phase | 状态 | 交付物 |
|---|---|---|
| 0 基线/语义清单 | ✅ 完成 | 字段契约 + SQL/聚合链 characterization（46 用例）+ 6.5k/10k 基准 + 基线文档 |
| 1 窄 Room read projections | ✅ 完成 | `FavouriteLibraryReadDao`（6 个 Flow）+ 窄 row 类型；语义 10 用例 + 规模 2 用例 |
| 2 快照深模块 | ✅ 完成 | `FavouriteLibrarySnapshotStore` + `FavouriteLibrarySnapshot`/`FavouriteCardRow` 等；9+1 用例 |
| 3 纯内存派生 | ✅ 完成 | `FavouriteLibraryDeriver`（visibility/quickFilters/groupAndSort 三阶段）；16 用例 |
| 4 Container 接管状态 | ✅ 完成（管线侧） | `libraryState` StateFlow + `buildFavouriteLibraryUiState` 纯函数 + debug shadow comparison 挂点；6 用例 |
| 5 UI 切静态 List | ✅ 完成（渲染侧） | `FavouritesCardMapper` + `FavouriteContentResolver`；`pagingContent = null`；详见 §4 |
| 6 actions 迁移/删逐分类 VM | ✅ 完成（待设备回归） | `ContentListHost` seam + `FavouritesListHost` 适配器；`FavouritesListViewModel`/`Factory` 删除；quick filter chips 与 metadata authority 一并转内存/快照；详见 §4.5 |
| 7 删除收藏 Paging 代码 | ✅ 完成（设备待补测） | `FavouriteLibraryPagingRow`/`WorkFavouritesDao.pagingSource`/`createFavouritePagingSource`/`observeFavouriteAggregates`/`FavouriteLibraryPagingConfig` 与两个旧链路测试套删除；`findList` 改成直接投影 entity；详见 §4.6 |
| 8 验收收敛 | ⬜ 未开始 | |

**性能实测**（Xiaomi M332BF / Android 16 / debug 变体 / Room in-memory）：

| 指标 | 旧链路 | 新链路 | 预算 |
|---|---:|---:|---:|
| 6.5k 宽行全量读取 | 360 ms | — | — |
| 10k 窄读取（6 Flow） | — | **138 ms** | ≤ 500 ms |
| 10k 完整快照构建（读+组装） | — | **202 ms** | ≤ 500 ms |
| 10k 内存派生（filter+sort） | — | < 150 ms（JVM 断言） | ≤ 150 ms |

## 2. 新代码地图（全部为新增，旧链路未动）

```text
app/src/main/kotlin/org/skepsun/kototoro/
  favourites/data/
    FavouriteCardBaseRow.kt            # 主查询窄 row（~30 字段，全部有消费者）
    FavouriteLibraryReadRows.kt        # Membership/ProjectionFacet/TagFacet/Downloaded/LegacyOverride rows
    FavouriteLibraryReadDao.kt         # 6 个 observe* Flow；只读；无过滤参数
  favourites/domain/library/
    FavouriteLibrarySnapshot.kt        # FavouriteCardRow / FavouriteMembership / Snapshot / QuickFilterMetadata
    FavouriteLibrarySnapshotStore.kt   # @Singleton；observe() 无参 → 完整快照；combine 6 Flow
    FavouriteLibraryDeriver.kt         # 纯函数三阶段 + FavouriteLibraryDerivationInput/DerivedState
  favourites/ui/container/
    FavouriteLibraryUiState.kt         # UiState + FavouriteLibraryParams + buildFavouriteLibraryUiState 纯函数
  core/db/MangaDatabase.kt             # + getFavouriteLibraryReadDao()（修改）
  core/model/GlobalTagBlacklist.kt     # + containsTagTitle(title)（修改）
  favourites/ui/container/FavouritesContainerViewModel.kt  # + libraryState/startLibraryShadowComparison（修改）
  # Phase 5（渲染切换，2026-09-02）
  favourites/domain/library/
    FavouritesCardMapper.kt            # row → ContentListModel（@Reusable，纯核心 buildFavouriteCardModel）
    FavouriteContentResolver.kt        # 选中项按 displayMangaId 批量解析真实 projection
  favourites/ui/compose/FavoritesListScreen.kt    # 静态渲染、onResolveSelectionContents
  favourites/ui/compose/FavoritesHostScreen.kt    # 每页注入 viewModel.listHost(categoryId)
  list/ui/compose/AppContentListRoute.kt          # + onResolveSelectionContents（分享/下载/分类/override）
  list/domain/ContentListMapper.kt               # + tagTint(title)
  core/prefs/... / res/values/strings.xml         # + favourites_broken_projection_title
  favourites/domain/library/FavouriteLibraryDeriver.kt      # + pinnedIdsByCategory（membership 语义）
  favourites/ui/container/FavouriteLibraryUiState.kt        # + pinnedIdsByCategory
  # Phase 6（唯一状态持有者，2026-09-02）
  list/ui/ContentListHost.kt                   # 路由所需的 state-holder 契约（ContentListViewModel 实现它）
  favourites/ui/list/FavouritesListHost.kt     # 逐分类适配器（非 VM）：切片 + 动作转调 Container
  favourites/domain/library/FavouritesQuickFilterOptions.kt # buildFavouritesFilterOptions 纯函数（chips 无 DAO）
  favourites/ui/list/FavouritesListViewModel.kt # 删除（连同 @AssistedInject Factory）
  # Phase 7（删收藏 Paging，2026-09-02）
  favourites/data/FavouriteLibraryPagingRow.kt  # 删除（宽行随收藏 Paging 退休）
  favourites/data/WorkFavouritesDao.kt          # 删 pagingSource / 全量收集包装 / findQuickFilter*；findList 改直接投影 entity
  work/domain/WorkAggregateRepository.kt        # 删 createFavouritePagingSource / observeFavouriteAggregates / buildFavouritePagingAggregates
  core/paging/BatchMappingPagingSource.kt       # 删 FavouriteLibraryPagingConfig（历史页仍用 LargeLibraryPagingConfig + BatchMappingPagingSource）
  # 测试删除：FavouriteLibrarySemanticsCharacterizationTest(24)、FavouriteLibraryBaselineBenchmarkTest(3)
```

测试：

```text
app/src/test/kotlin/org/skepsun/kototoro/favourites/
  ui/FavouriteCardFieldContractTest.kt                    # JVM，10 用例
  domain/library/FavouriteLibraryDeriverTest.kt           # JVM，18 用例（Phase 5 +pinned 2）
  ui/container/FavouriteLibraryUiStateTest.kt             # JVM，7 用例（Phase 5 +pinned 1）
  domain/library/FavouritesCardMapperTest.kt              # JVM，11 用例（Phase 5 卡片映射契约）
app/src/androidTest/kotlin/org/skepsun/kototoro/favourites/
  data/FavouriteLibrarySeed.kt                            # 共享种子助手（raw SQL）
  data/FavouriteLibraryReadDaoTest.kt                     # 10 用例
  data/FavouriteLibraryReadDaoScaleTest.kt                # 2 用例（10k）
  domain/FavouriteLibraryAggregateChainCharacterizationTest.kt  # 12 用例（旧聚合链语义）
  domain/library/FavouriteLibrarySnapshotStoreTest.kt     # 9 用例
  domain/library/FavouriteLibrarySnapshotStoreScaleTest.kt# 1 用例（10k=202ms）
```

## 3. 必须保留的语义（已 characterization 固化）

> 2026-09-02（Phase 7）：pin 这些语义的旧 SQL characterization 套已随收藏 Paging 一起删除，现在由新链路的 `FavouriteLibraryReadDaoTest` / `FavouriteLibrarySnapshotStoreTest` / `FavouriteLibraryDeriverTest` + 改写后 `findList` 的 `WorkPagingDaoTest` 用例固化，对应关系见 §4.6。

1. **isPinned 恒定所有排序之首**；`entity_id` 恒定 tie-breaker。
2. 代表 membership 选择：`pinned DESC, created_at DESC, updated_at DESC, category_id ASC`（DAO 用 NOT EXISTS 反连接实现——**Room SQL 解析器不支持 `ROW_NUMBER() OVER`**，别改回窗口函数）。
3. 投影集合 **binding-based**（anchor 不膨胀 `projectionCount`/`localMangaIds`）——`MULTI_PROJECTION` 语义。旧窄聚合路径曾把 anchor 并入（语义分叉），新实现统一为 binding-based（与 `WorkPagingDaoTest` 一致）。
4. display = `COALESCE(preferred_local_manga_id, anchor)`；dangling → broken row **保留**（entity organize 可达），title 空 → ALPHABETIC 排最后（新实现有意改掉旧 SQL 的 NULL-first）。
5. Override 优先级：entity prefs > legacy `preferences`(per-manga)（同 `ContentDataRepository.getOverridesForWorkItems`）。
6. Tag 过滤 ID = `"${key}_${sourceName}".longHashCode()`（`ContentTag.toEntity()`）。
7. tracks 聚合 = `SUM(chapters_new)`/`MAX(last_chapter_date)`；`tracks.owner_id` 与 `tracks.manga_id` 均 UNIQUE（一个 entity 多 track 必须挂不同 manga）。
8. groupTab 是 **OR 语义**（persisted contentType 匹配 或 sourceGroupFlags 匹配即通过）。
9. 软删除分类下的 membership 仍可见（与 `repairActiveDanglingCategoryRefs` 维护路径一致）。
10. readingStatus：entity prefs 显式 > history percent（≥0.999=COMPLETED，>0=READING，null=PLANNED）。

## 4. Phase 5/6/7 已完成：UI 切静态 List + 唯一状态持有者 + 删收藏 Paging（2026-09-02）

目标达成：收藏渲染路径不再产生 `PagingData`、`WorkAggregate` 或收藏 DAO 读取；每页卡片由 Container 的共享快照在内存里映射。

### 4.1 渲染链

```text
Container.libraryState (StateFlow<FavouriteLibraryUiState>)
    ├─ FavoritesHostScreen：activeFavouritesHostRef（顶栏 filter panel 指向当前分类的 host）
    └─ KototoroFavoritesListScreen(categoryId, listHost)
         └─ Container.listHost(categoryId) → FavouritesListHost（缓存的适配器，不是 VM）
              content = combine(libraryState, container.observeListModeWithTriggers())
                        .flowOn(Default) → FavouritesCardMapper.map(rows, Slice(mode, pinnedIds))
         └─ AppContentListRoute(viewModel: ContentListHost；pagingContent == null → 静态 items 分支)
```

- **`FavouritesCardMapper`**（`favourites/domain/library/`）：`FavouriteCardRow` → GRID/COMPACT_GRID(`ContentGridModel`)/LIST(`ContentCompactListModel`)/DETAILED_LIST(`ContentDetailedListModel`)。纯核心是 `buildFavouriteCardModel(FavouriteCardModelRequest)`（无 Android/无 I/O，`FavouritesCardMapperTest` 11 用例覆盖）。字段规则：`id = entityId`、`counter = newChapters`（reading 完成时 0）、`progress` 来自 work history（无 history → null）、`projectionCount` binding-based、`isSaved = isDownloaded`、`isFavorite = false`、`isPinned` 用 **membership** 标志、`override = ContentOverride(overrideCoverUrl, overrideTitle, null)`；subtitle：grid=`altTitle`、detailed=`altTitle · 后缀`、compact=`tags.join(", ") · 后缀`（后缀=`favourites_entity_current_projection[_with_count]`，与旧 `groupSuffix` 等价）。
- **stub `Content`**：title/altTitle/cover/author/state/tags/source 全部来自 row，`chapters=null`、`description=null`、`sourceData=null`、`url=publicUrl=""`；`contentRating` 显式置 ADULT/SAFE，使 NSFW badge 与 row 的持久化标志（也是 NSFW 快筛依据）一致。
- **broken row 不再消失**：`displayMangaId == null` 的 row 以 `R.string.favourites_broken_projection_title` 为标题显示（旧链路在 map 阶段 `return@mapNotNull null` 直接丢弃），点击仍走 entity details → entity organize 可达。
- **per-slice pinned**：`FavouriteLibraryDerivedState.pinnedIdsByCategory` / `FavouriteLibraryUiState.pinnedIdsByCategory`（membership 的 pinned 才是卡片标志；All 切片用代表 membership）。deriver 2 用例 + UiState 1 用例。

### 4.2 卡片 stub 与真实 projection 的边界

`AppContentListRoute` 新增可选参数 `onResolveSelectionContents: suspend (Set<Long>) -> List<Content>`；收藏页传入 `FavouriteContentResolver.resolveByDisplayMangaIds`（`MangaDao.findWithTagsByIds` 一次批量查询，保持选择顺序）。分享 / 下载 / 分类对话框 / 编辑 override 都先解析再执行；`MARK_AS_COMPLETED` 改成传 entity ids 由 VM 解析（`MarkAsReadUseCase` 需要真实 projection）。置顶 / 移除 / entity organize 选择继续用 `row.localMangaIds`（空则回退 displayMangaId→entityId），与旧 `expandGroupedIds()` 等价。

### 4.3 其余改动

- `FavouritesListViewModel`：删掉 `Pager`/`BatchMappingPagingSource`/`WorkAggregateRepository`/`ContentListMapper`/`SourceGroupManager`/`SourcePresetsRepository`/`FavouriteItemLookup`/`refreshTrigger`/`mapFavouritePage`/`toGroupedListModel`/`groupSuffix`/`sortOrder`/`setSortOrder(order)`；`pagingContent = null`、`hasMoreItems = false`、`onRefresh()` 变成 no-op（Room invalidation 驱动）；Factory 变成 `create(categoryId, libraryState)`；Quick Filters（`FavoritesListQuickFilter`）与 space 绑定原样保留。
- `FavoritesListScreen`：`retainPagingSnapshotOnDetailsNavigation = false`（不再有 retained window；saveable 的 LazyListState/LazyGridState 是唯一真相），详情跳转 `initialProjectionLocalMangaId = preferred ?: content.id.takeIf { it != entityId }`。
- `ContentListMapper.tagTint(title)` 公开（原来只有私有 `getTagTint(ContentTag)`），供详细列表 tag chips 复用同一份 warn-list 着色。

### 4.4 有意留下的偏差 / 待办（重要）

1. ~~**metadata authority（tracking 站点）派生的标题/封面 override 与 `metadataTrackingService` badge 丢失**~~ → **Phase 6 已修复**（见 §4.5），原文如下。旧链路 `resolveDisplayOverride` 会把「显示元数据权威=某追踪站点」的作品换成 tracking 缓存里的 title/cover，并画一个服务 badge；row 里没有这些列。修复方式：给 `observeFavouriteCardBaseRows()` 增补 `ep.metadata_source_service/remote_id` 并 `LEFT JOIN tracking_site_items`（PK=(service,remote_id)，最多一行，不会放大基数）→ row 加 3 个原语字段 → mapper 按 manual > tracking 合并。Room KSP 会静态校验该 SQL；仍需真机跑 `FavouriteLibraryReadDaoTest`/`SnapshotStoreTest`（androidTest 里构造 `FavouriteCardBaseRow` 的测试要同步加参数，`compileDebugAndroidTestKotlin` 可先验编译）。
2. ~~**Quick Filter chips 仍逐分类查 DAO**~~ → **Phase 6 已改为内存派生**（见 §4.5），原文如下。（`FavoritesListQuickFilter`，`suspendLazy` 每 VM 一次，切分类会新建 VM→新查询），且它 `init{}` 里的「离线时自动勾选 Downloaded」副作用要一并迁移；Filter Panel 仍走 `activeFavouritesViewModelRef` 桥接。→ 与 Phase 6 一起做：chips 由 `snapshot.quickFilterMetadata`（+分类切片）在内存派生。
3. **reorder 场景没有 semantic anchor**（计划 §6.1 第 4 点，明确列为 Phase 8 按需项）：详情返回用普通 saveable 索引恢复，列表未变化时精确；筛选/排序变化后按新顺序从同一 offset 开始。
4. ~~`getEmptyState()` 仍是私有死代码~~ → 随逐分类 VM 一起删除。

### 4.5 Phase 6 已完成：容器是唯一状态持有者（2026-09-02）

计划 §11 Phase 6 的退出条件（收藏页只有一个 screen-level state holder）达成：逐分类 `FavouritesListViewModel` 与其 `@AssistedInject Factory`、`spaceViewModelKey("favorites-$categoryId", spaceId)` 逐分类 Hilt key、`getEmptyState()` 死代码全部删除。

**4.5.1 共享路由的 host seam**

`AppContentListRoute` 原本硬性要求 `VM : ContentListViewModel`（一个 ViewModel）。抽出接口 `list/ui/ContentListHost.kt`——只声明路由真正用到的成员（content/pagingContent/hasMoreItems/isLoading/listMode/gridScale/onError/onContentMessage/onContentActionHostRequest/currentSourceTags/currentGroupTab + onRefresh/onRetry/onContentClick/setSelected*/resolve*IdForUiItemId），`ContentListViewModel` 实现它，路由参数改为 `viewModel: ContentListHost`。其余页面（history/updates/search/recommend…）继续传 ViewModel，行为不变。`rememberRetainedPagingSnapshotState` 的 `host` 参数改为可空，路由传 `viewModel as? RetainedPagingSnapshotHost`（收藏页 `retainPagingSnapshotOnDetailsNavigation = false`，本就不需要 retained window）。

**4.5.2 `FavouritesListHost`（`favourites/ui/list/`）取代逐分类 VM**

普通类（非 ViewModel、无 Hilt），实现 `ContentListHost + QuickFilterListener`，只做切片：`content = combine(container.libraryState, container.observeListModeWithTriggers()) → FavouritesCardMapper.map(...)`、`topQuickFilter` / `popupQuickFilter`、`resolveEntityIdForUiItemId(id) = id`、`resolvePreferredLocalMangaIdForUiItemId`。所有动作转调 Container。实例由 `FavouritesContainerViewModel.listHost(categoryId)` 缓存（`@Synchronized getOrPut`），因此卡片映射在重组、切分类、旋转后都复用，随容器（= 收藏屏，且 shell 用 space-scoped key 取容器 → 换 space 会重建）一起销毁。

**4.5.3 容器吸收的东西**

- 新增注入：`FavouritesCardMapper`、`FavouriteContentResolver`、`FavoritesListQuickFilter.Factory`、`MarkAsReadUseCase`、`TrackingRepository`、`TrackWorker.Scheduler`、`@LocalStorageChanges SharedFlow<LocalContent?>`（`mangaDataRepository` 改为字段）。
- 新增成员：`listScope`（viewModelScope+Default）、`gridScale`、`onContentMessage`/`onContentActionHostRequest`（路由收集的消息/宿主请求）、`observeListModeWithTriggers()`（模式 + badges/progress/tracker/overrides/favorites/local-storage 触发器）、`isSpaceBound()`。
- 新增动作（原逐分类 VM 的动作，entity-id 语义不变）：`removeFromFavourites(categoryId, ids)`、`isPinned/setPinned/togglePinned`、`markAsRead`、`resolveSelectedContents`、`resolveSelectionToMangaIds`、`checkForUpdates()`（`TrackWorker.Scheduler.requestCheckNow` 闸口 + 汇总 toast）、私有 `expandToMangaIds`（localMangaIds → displayMangaId → entityId 回退链）。
- Space：改由屏级绑定——`FavoritesHostScreen` 里 `LaunchedEffect(viewModel, spaceId) { viewModel.bindSpace(spaceId) }`（shell 已用 `spaceBoundHiltViewModel` 绑过一次，重复 set 同一 id 是 no-op）；逐分类 `spaceBinding` 消失，group tab 的 space 作用域只剩容器一份。

**4.5.4 Quick Filter chips 改内存派生（§4.4.2）**

- 纯函数 `favourites/domain/library/FavouritesQuickFilterOptions.kt`：`buildFavouritesFilterOptions(FavouritesQuickFilterInput)`，无 DAO、无 I/O；JVM 测试 `FavouritesQuickFilterOptionsTest`（tag top-3、分类只统计本分类 membership、chip 的 `tagId` 与 deriver 匹配的 id 同一个、settings 门）。
- 快照 facet 现在带 tag 身份：`FavouriteFacetTag(tagId, title, key, source)`（`toContentTag()` 必须回到同一个 `tagId`），tag facet 查询多 select `t.key/t.source`。
- `FavouriteLibraryUiState` 增加 `membershipsByCategory` / `allEntityIds`（直接引用快照字段，零拷贝）：chips 统计的是**未经快筛**的分类 membership，所以点掉一个 chip 不会让兄弟 chip 消失。
- tag chips 保留旧查询的 3 个上限与 `数量 DESC, 标题(忽略大小写) ASC` 排序；source chips 按 `数量 DESC, 名称 ASC`。有意偏离一处：两者都按**过滤器真正匹配的字段**计数（row 的 tagIds / 显示投影 source），旧 SQL 用 anchor∪binding 并集计数，可能给出过滤后为空的 chip。
- `FavoritesListQuickFilter` 不再查 DAO（`create(categoryId, libraryState)`）；`FavouritesRepository.findQuickFilterMetadata` 与其 `QuickFilterMetadata` 类型删除。`WorkFavouritesDao.findQuickFilterTags/findQuickFilterSourceNames` 至此无主代码调用方（只剩 `WorkPagingDaoTest` 的 characterization 用例）→ Phase 7 一起删。
- 「离线自动勾选 Downloaded」从每分类 VM 的 `init{}` 上移到容器 `init`（一次/屏，而不是每 tab 一次）。

**4.5.5 显示元数据权威（§4.4.1）**

`observeFavouriteCardBaseRows()` 增补 `ep.metadata_source_kind/service/remote_id` 并 `LEFT JOIN tracking_site_items ON (service, remote_id)`（PK 唯一，不放大基数；join 条件带 `metadata_source_kind = 'tracking'`，权威切回本地时列全 NULL）。`FavouriteCardBaseRow` 加 `metadataTrackingService/Title/CoverUrl`，`FavouriteCardRow` 同名三字段；mapper 优先级 `manual override > tracking 站点缓存 > 投影`，tracking 的 service 也用于详情 badge。JVM：`FavouriteCardModelTest`/`FavouritesCardMapperTest` 覆盖 manual>tracking 与「无缓存行→全 NULL」；androidTest：`FavouriteLibrarySeed.insertPrefs(metadataSourceKind/metadataService/metadataRemoteId)` + `insertTrackingSiteItem`，`FavouriteLibraryReadDaoTest.metadataAuthorityReadsTheCachedSiteItem` 覆盖 tracking 命中 / 缓存缺失 / kind=base 的守卫。

### 4.6 Phase 7 已完成：删除收藏 Paging 链路（2026-09-02）

计划 §8 Phase 7 清单按「只删确认无其他调用方」执行，结果分两类。

**已删除（主代码）**

- `favourites/data/FavouriteLibraryPagingRow.kt`（整文件）：宽行（内嵌整个 `MangaEntity` + `WorkHistoryEntity` + tracking 列）随收藏 Paging 一起退休。
- `WorkFavouritesDao.pagingSource()`（含那段宽 `@Query`）、`FULL_LIST_PAGE_SIZE`、`import androidx.paging.PagingSource`；顺带删掉 Phase 6 起就无主代码调用方的 `findQuickFilterTags()` / `findQuickFilterSourceNames()`（chips 已内存派生）。
- `WorkAggregateRepository.createFavouritePagingSource()`、`observeFavouriteAggregates()`（两者零调用方）、`buildFavouritePagingAggregates()`、`FavouriteLibraryPagingRow.toTrackingSummary()`。
- `core/paging/BatchMappingPagingSource.kt` 里的 `FavouriteLibraryPagingConfig`（`LargeLibraryPagingConfig` 与 `BatchMappingPagingSource` 本身留给历史页）。
- 收藏页删掉 `retainPagingSnapshotOnDetailsNavigation = false`（路由默认已是 false，收藏不再启用 retained window）。

**改写而非删除**

- `WorkFavouritesDao.findList(...)`：**签名不变**，但不再是「`pagingSource` + 全量翻页收集」的包装，而是同一个 `@Query` 直接把投影从 `selected.*, ep…, m…, wh…, tracking…` 收窄成 `selected.*`，返回 `List<WorkFavouriteEntity>`。原因：`findList` 不是「只服务收藏 UI 分页」的包装——它撑着 `findFavouriteEntries → findFavouriteAggregates/findFavouriteContents`，而后者仍被 `FavouritesRepository.getAllContent/getLastContent/buildWorkFavourite*Contents/Covers`（小组件、备份、通知等）使用；那些调用方从来只要 entity 行。Room KSP 已静态校验改写后的 SQL。

**测试**

- 删除 `FavouriteLibrarySemanticsCharacterizationTest`（24 用例）与 `FavouriteLibraryBaselineBenchmarkTest`（3 用例）：它们 pin 的是被删掉的宽行分页 SQL 与旧链路基准（基准数字已归档在 §1）。
- `WorkPagingDaoTest`：删 `favouriteColdStartLoadsOnlyTheInitialWindow`、`favouritePageCarriesDisplayHistoryAndTrackingSummary`（用 `pagingSource`）与 `favouriteQuickFilterMetadataUsesDatabaseAggregation`（用已删的 chips 查询）。**保留** `favouritesListAndHistoryPageByUniqueEntity`、`favouriteRepresentativeCarriesPreferredProjectionInTheSameQuery`、`favouriteFullListBenchmarkFitsBudget`、`favouriteFiltersAreAppliedBeforePaging`——最后一组经 `favouriteList()` 助手直接跑改写后的 `findList`，是它现在的实机覆盖。历史/更新页的 Paging 用例与 `BatchMappingPagingSourceTest` 其余用例不动（只删了 favourites config 断言）。
- §3 的语义现在由新链路自己固化：代表 membership 与排序在 `FavouriteLibraryReadDaoTest`（11）+ `FavouriteLibraryDeriverTest`（18），快照组装语义（binding-based 投影、broken row 保留、override 优先级、readingStatus）在 `FavouriteLibrarySnapshotStoreTest`（9），`findList` 侧仍在 `WorkPagingDaoTest`。

**没删（计划明令保留）**：`WorkAggregateRepository` 其余领域用途（`observeFavouriteLibraryAggregates` 还撑 Phase 8 的 shadow comparison、`findFavouriteAggregates`/`findFavouriteContents` 撑小组件与备份）、历史页 Paging 基础设施、`RetainedPagingSnapshotController` 与通用 semantic anchor、Room 表/备份格式/entity identity。

**与计划的一处偏差**：§8 要求「`rg` 确认 favourites 包无 Paging import」。实际剩一处：`FavouritesListHost.pagingContent = null` 需要 `androidx.paging.PagingData` 才能满足 `ContentListHost` 契约（共享路由的接口成员，收藏永远不填它）。

### 4.7 下一步（Phase 8）

Phase 6 已完成（见 §4.5）、Phase 7 已完成（见 §4.6，设备侧 `WorkPagingDaoTest` 待补跑）。Phase 8：跑计划 §11 全部基准 + 与 Komikku 并列对照 + 删 shadow comparison/临时日志 + 更新 `large-library-performance-handoff-2026-08.md`。

设备必测（Phase 5/6 改动直接覆盖收藏页，见 §5 流程）：收藏四模式渲染、分类/快筛/排序切换（logcat 确认 `FavouriteLibraryReadDao` 不重复查询）、详情返回、置顶/移除/标记完成/分享/下载/分类对话框/编辑 override/实体整理、broken row 显示与跳转、多分类下 pin badge 只在本分类出现。

Phase 6 追加：切分类/旋转后卡片不重置（host 由容器缓存）、top-bar filter panel 的快筛仍作用于**当前** tab（`activeFavouritesHostRef`）、切 space 后容器重建且列表按 space 作用域、离线时进入收藏页自动勾选 Downloaded（现在一次/屏而不是每 tab 一次）、tracking 权威作品的标题/封面与站点 badge。

## 5. 设备/构建备忘（踩过的坑）

- **androidTest 类名不能含空格**（R8/dex 限制）：反引号方法名用驼峰。
- **MIUI 真机 orchestrator 崩溃**（StatusCode 134 / "Unable to find instrumentation info"）：`connectedDebugAndroidTest` 不可靠。可靠流程：
  ```bash
  ./gradlew :app:packageDebug --rerun-tasks        # 主 APK 有新类时必须强制重打包（UP-TO-DATE 判断不可靠）
  ./gradlew :app:assembleDebugAndroidTest
  adb install -r -t app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w -e class <全限定类名> org.skepsun.kototoro.debug.test/org.skepsun.kototoro.HiltTestRunner
  ```
- **Gradle `connectedDebugAndroidTest` 一定失败**：`pm path androidx.test.services` 为空 → AGP shell executor 的 `app_process` 直接 SIGABRT（`StatusCode: 134` / `Starting 0 tests`），而且这次运行会**顺手卸掉主 APK**，之后 `am instrument` 报 `Unable to find instrumentation target package: org.skepsun.kototoro.debug`。用上面的 `am instrument` 流程；若 target 丢了就重新 `adb install -r -t app-arm64-v8a-debug.apk`（用 `pm path org.skepsun.kototoro.debug` 确认装上了）。
- `adb: device 'xxx' not found` 之后若所有 adb 调用都变成 `failed to check server version: protocol fault (couldn't read status)`，说明 5037 上留下了无人拥有的半死监听套接字（`ss -ltnp | grep 5037` 有 LISTEN 但 `pgrep adb` 为空、`adb kill-server` 无效）。不必重启机器：换端口起一个新 server 即可——`ANDROID_ADB_SERVER_PORT=5039 adb devices`（后续 adb 命令都带上这个环境变量）。
- **交互 UI 验证在 adb 侧不可做**：`adb shell input` 抛 `SecurityException`（MIUI 需另开「USB 调试(安全设置)」并登录账号），`android layout --pretty` 会挂死（>150s 无输出），`adb shell wm dismiss-keyguard` + `svc power stayon true` 只能把屏幕从 Dozing 叫醒、锁屏 PIN 仍在（screencap 得到锁屏图）。所以 §4.7 的设备清单必须人工在手机上进；自动侧能做到的上限是 Room/无 UI 的 androidTest（`FavouriteLibraryReadDaoTest` 11 用例、`FavouriteLibrarySnapshotStoreTest` 9 用例在 0.5s 内跑完）。
- **JVM 单测**的 `assertEquals` 解析到 JUnit4 `org.junit.Assert`：message 必须放**最后**：`assertEquals(expected, actual, "msg")`。
- **种子数据**：`preferences` 表 7 个 NOT NULL 无默认值列（manga_id/mode/cf_brightness/cf_contrast/cf_invert/cf_grayscale/cf_book）必须显式 INSERT；`manga.cover_url` NOT NULL。
- Room DAO 测试用 `Room.inMemoryDatabaseBuilder(...).build()` + raw SQL 种子（见 `FavouriteLibrarySeed`），不要用 Hilt 真库。
- kotlinx `combine` 6+ flow 必须用 `Array<*>` 签名 + 显式 cast。
- JDK 24 可用；设备 `ecd4369c`（M332BF，Android 16/API 36）。

## 6. 提交归属（本次推送）

**本次重构的提交**（按计划 §17 拆分，只含重构相关文件）：

1. `test(favourites): capture library semantics and performance baseline` — Phase 0 测试 + 种子 + 2 篇文档（计划/基线）
2. `feat(favourites): add lightweight library read projections` — Phase 1 DAO + rows + MangaDatabase 注册 + DAO 测试
3. `feat(favourites): add observable library snapshot store` — Phase 2 store + 模型 + 测试
4. `refactor(favourites): derive filters grouping and sorting in memory` — Phase 3 deriver + GlobalTagBlacklist.containsTagTitle + 测试
5. `refactor(favourites): move library state ownership to container` — Phase 4 Container + UiState + 测试

**留在工作区不提交**（用户自己的未提交改动，Phase 7/8 处理）：
- `core/paging/BatchMappingPagingSource.kt`（enablePlaceholders=true 实验）
- ~~`list/ui/ContentListViewModel.kt` / `list/ui/compose/RetainedPagingSnapshotController.kt`（K510G debug 日志）~~ → 工作区里这两个文件现在的 diff **全部是 Phase 6 的改动**（`ContentListHost` 实现 + retained host 可空），debug 日志已不在。
- `backups/external/*`、`core/AppModule.kt`、`details/ui/compose/*`、`docs/.vitepress/config.mts`、`docs/index.md`、`backups/external/MihonBackupSourceIdPreservationTest.kt`、`core/paging/BatchMappingPagingSourceTest.kt`
- `docs/architecture/komikku-library-list-architecture-research-2026-09.md`、`docs/architecture/paging3-lazy-list-alternatives-research-2026-09.md`（研究文档，归属由用户决定）

## 7. 验证命令

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:compileDebugAndroidTestKotlin   # androidTest 只需编译（本会话无设备）
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.favourites.*" --no-daemon
# 设备（见 §5 流程）：
#   FavouriteLibraryReadDaoTest / FavouriteLibraryReadDaoScaleTest
#   FavouriteLibrarySnapshotStoreTest / FavouriteLibrarySnapshotStoreScaleTest
#   FavouriteLibraryAggregateChainCharacterizationTest / WorkPagingDaoTest
#   （FavouriteLibrarySemanticsCharacterizationTest 与 FavouriteLibraryBaselineBenchmarkTest 已在 Phase 7 删除）
```

Phase 6 之后（2026-09-02，接了手机但只能跑无 UI 的 androidTest）：`compileDebugKotlin` ✓、`compileDebugAndroidTestKotlin` ✓、`testDebugUnitTest` 全量 **2148/2148** ✓（favourites 包 90：新增 `FavouritesQuickFilterOptionsTest` 4、`FavouritesCardMapperTest` 14）。设备（`am instrument` 直跑，见 §5）：`FavouriteLibraryReadDaoTest` **11/11**（含 `metadataAuthorityReadsTheCachedSiteItem`）、`FavouriteLibrarySnapshotStoreTest` **9/9**。冷启动新 APK 无 crash（`adb install` + monkey 起 `MainActivity`，logcat 无 AndroidRuntime FATAL）。§4.7 的交互清单（含 Phase 6 追加项）仍需人工在手机上跑——本会话 adb 无法注入输入（见 §5）。

Phase 7 之后（2026-09-02）：`compileDebugKotlin` ✓（Room KSP 校验改写后的 `findList` SQL）、`compileDebugAndroidTestKotlin` ✓、`testDebugUnitTest` 全量 ✓（删了 `BatchMappingPagingSourceTest` 的 favourites config 断言）。设备侧本轮没跑成——手机在装机后从 adb 掉线（`adb: device not found`），`WorkPagingDaoTest` 留在下一次接机补跑（它现在也负责改写后 `findList` 的实机覆盖）。

全部通过状态（2026-09-01）：JVM 32/32（契约 10 + deriver 16 + UiState 6），设备 58/58（SQL 24 + 聚合链 12 + DAO 10+2 + Store 9+1 + 基准 3——基准 3 含在其中）。

Phase 5 之后（2026-09-02，无设备会话）：`compileDebugKotlin` ✓、`compileDebugAndroidTestKotlin` ✓、`testDebugUnitTest` 全量 **2141/2141** ✓（其中 favourites 包 83：新增 mapper 11、deriver pinned 2、UiState pinned 1）。androidTest 仅验证编译；§4.5 的设备清单是下一次接手机的入口（Phase 6 之后还需加测：切分类/旋转后卡片不重置、filter panel 的快筛仍作用于当前 tab、离线自动 Downloaded）。
