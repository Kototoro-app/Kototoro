# 收藏页重构思路（Favourites Page Refactor Plan）

> 状态：草案，等待设计评审
> 日期：2026-08
> 工作树：`/Users/sunchuxiong/kotatsu_demo/Kototoro`（branch: `devel`，HEAD `dec0ef781`）
> 关联文档：`docs/architecture/large-library-performance-handoff-2026-08.md`（HEAD 分页依据）、`docs/architecture/paging3-favourites-grid-research-2026-09.md`（Compose/Paging 官方资料逐条调研与 GitHub 一手来源，本轮新增）

---

## 1. 背景与目标

收藏页（Favourites）是本地库规模最大的页面之一：当前设备实测约 **6315 个去重 entity / 6345 条收藏 / 6818 部作品 / 25 个分类**。重构目标：

1. **滚动流畅**：快速 fling 时消除系统性卡顿源，帧预算稳定；
2. **首屏可控**：冷启动不因“一次构建全部收藏”造成数秒阻塞与内存峰值；
3. **可验证**：复用大库夹具与 `LibraryPaging` 日志，性能结论建立在真实数据上；
4. **不牺牲核心功能**：选择/多选、删除、置顶、排序、分类、过滤、Space、快速筛选保留；对**极其影响性能且收益低**的功能，作为「性能闸门」暂时移除/降级（§6）。

三个输入源：

- **Jetpack Compose 官方文档 / 示例 / issues** —— §4 与 `paging3-favourites-grid-research-2026-09.md`；
- **mihon 收藏列表机制**（`../mihon`，被确认顺滑的实现）—— §3；
- **Kototoro 自身 HEAD 逻辑**（已落地的 Room + Paging 3 管线）—— §2.1。

---

## 2. 现状盘点

### 2.1 HEAD 已有的 Paging 主链路（推荐基线）

HEAD（`dec0ef781`）已完成收藏/历史/更新三页的 entity 级 Paging：

```text
WorkFavouritesDao.pagingSource（Room 位置源，过滤/排序下推 SQL）
  -> BatchMappingPagingSource（整批构建 WorkAggregate + 过滤 + 映射 ListModel）
  -> ViewModel Pager(PagingConfig)  ->  PagingData<ListModel>
  -> collectAsLazyPagingItems()     ->  LazyVerticalGrid（稳定 key + contentType）
```

关键事实（来自 `large-library-performance-handoff-2026-08.md` §2/§5）：

- `FavouriteLibraryPagingConfig`：`pageSize=64, initialLoadSize=64, prefetchDistance=128, enablePlaceholders=false`。
- DAO `pagingSource` 已下推：Space 类型/来源、group-tab 内容类型、出版状态、NSFW、已下载、有新章、精确来源、标签过滤，以及全部排序（含稳定次级键 `entity_id ASC`）。
- `BatchMappingPagingSource.getRefreshKey` 用 `closestPageToPosition(...).prevKey`（raw 邻域）修复“失效刷新冲刷已加载收藏”；配套 JVM 回归测试存在。读路径不再写库（移除 `entity_preferences` 背填），静止时无自激刷新风暴。
- **已知成本**：设备实测每个 64 条批次 `favourites-aggregate ~259–349ms`、`favourites-ui ~313–487ms`（部分批次 400–806ms）。这不是纯 SQL 延迟，而是每批的投影解析 + 分类/历史/tracking 关联 + metadata/override 查询 + `ContentListMapper` 建模。

### 2.2 工作树 WIP 的风险：收藏页被改回「全量列表」

当前工作树把 `FavouritesListViewModel` 改回非分页路径（`observeFavouriteLibraryAggregates` → `mapFavouriteList` → `content: StateFlow<List<ListModel>>`，`pagingContent = null`；同时删除了 `pagingSource` DAO 方法与 paging 测试）。

```text
invalidationTracker.createFlow(9+ 张表).mapLatest {
    findFavouriteLibraryAggregates(...)   // 全量
} -> mapFavouriteList(...)                // 全量 WorkAggregate -> 全量 ListModel
```

**风险（交接文档 §5 已断言）**：

1. 任何一张订阅表（manga / work_favourites / entity_binding / history / tracks / prefs / local_index …）失效，都会**重建全部 6300+ 个富 `WorkAggregate` 与全部 `ListModel`**；
2. 每次重建都给 grid 一个**全新 List 与全新 item 实例** → Compose 全量 recompose、GC 抖动、回顶/下拉刷新时剧烈卡顿；
3. 交接文档结论：“一次构建全部 6300 个富 WorkAggregate，很可能重新造成数秒到几十秒的启动计算、内存峰值和 GC 抖动”。

**结论：收藏页必须保留 HEAD 的 Room + Paging 3 主链路。** WIP 中只保留一处正确改动——`mapFavouriteList` 给 mapper 传 `NO_FAVORITE | NO_PROGRESS | NO_COUNTER`（HEAD 只传 `NO_FAVORITE`，导致 mapper 内部又查了一遍 history/tracking）+ 合理的 `canUseFavouriteLibraryProjection` 分支；把它们带回 Paging 路径（§5.2）。

---

## 3. mihon 收藏列表机制拆解（为什么它顺滑）

以 mihon HEAD 的 `LibraryScreenModel`、`LibraryContent`、`LibraryPager`、`LibraryCompactGrid`、`LibraryList`、`CommonMangaItem` 为准：

| # | mihon 的做法 | 关键实现 | 给 Kototoro 的启示 |
|---|---|---|---|
| 1 | **一次 SQL 拉齐所有徽章字段** | `libraryView.sq` 定义 `CREATE VIEW libraryView`：`mangas` LEFT JOIN 章节聚合子查询（total/read/latestUpload/fetchedAt/lastRead/bookmarkCount）与 `mangas_categories` group_concat；`GetLibraryManga.subscribe()` 直接订阅该视图，返回扁平 `LibraryManga` | 每卡徽章在**数据层一次 JOIN 算好**，渲染时零 N+1 |
| 2 | **`@Immutable` 状态 + ID 索引分组** | `LibraryData`/`LibraryItem`/`State` 标 `@Immutable`；分组结果只存 `Map<Category, List<Long>>`（ID），内容在 `favoritesById` 单独索引 | 排序/分组不复制 item 对象；让 Compose 能跳过未变化 item |
| 3 | **过滤/分组/排序在后台线程且 diff 化** | `screenModelScope.launchIO` + `distinctUntilChanged` + `collectLatest`；整库一次内存持有 | 主线程只做引用级比较，heavy 计算离开 UI 线程 |
| 4 | **叶子 Composable 只收小稳定参数** | `MangaCompactGridItem(isSelected: Boolean, title: String?, coverData: @Immutable MangaCover, badge: content lambda)`；`contentType` 常量字符串 | 让每个卡片可被 strong-skipping 跳过 |
| 5 | **封面缓存完整** | `CoverCache`（内存 LRU）+ `MangaCover(mangaId, sourceId, url, lastModified)` 作为 Coil key | 滚动顺滑的隐性前提是图片命中内存缓存 |
| 6 | **Pager 只组合相邻页** | `LibraryPager` 内 `if (page !in currentPage-1..currentPage+1) return` | 避免多页 LazyGrid 同时组合/测量 |
| 7 | **不做 Paging：整库一次内存持有** | 全量 List + `distinctUntilChanged`，无 `enablePlaceholders` 心智负担 | **不能照搬**：mihon 行极轻（扁平投影）；Kototoro 一行要 entity 解析 + 多表关联，必须保留分页（§2.1） |

> 结论映射：mihon 的「扁平投影、不可变状态、后台 diff、稳定叶子参数、封面缓存、Pager 边界」全部可以迁移到 Kototoro 的分页链路上；「整库无 Paging」与 Kototoro 的数据模型不匹配，不采用。

---

## 4. Compose 官方实践要点

> 完整逐条调研（官方文档、官方示例、AndroidX 源码与 GitHub issue 一手来源，每条带 verbatim 引用、URL 与访问日期）见 `docs/architecture/paging3-favourites-grid-research-2026-09.md`。以下为与收藏页直接相关的要点，引用官方稳定 URL。

### 4.1 Lazy 容器的 item 身份与类型

- `LazyVerticalGrid` / `LazyColumn` 的 `items` 应提供**稳定 `key`** 与 **`contentType`**：key 决定“同一逻辑项的合成状态复用/移动动画”，contentType 让运行时复用 item 类型前缀的布局缓存（官方 *Lazy lists and lazy grids*：https://developer.android.com/develop/ui/compose/lists ）。
- 收藏页已做（`key = item.id`，`contentType = itemDescriptor`）；重构中**保留并约束**，不得回退为 index key。

### 4.2 稳定性（Stability）与强跳过

- item 组合参数必须**稳定**，否则 Compose 无法跳过子组合：`@Immutable`/`@Stable` 标注真正的不可变类型；含 `List`/`Set` 接口字段的模型在编译器眼里不稳定（官方 *Stability in Compose*：https://developer.android.com/develop/ui/compose/performance/stability ）。
- 本项目 `Content`（含 `Set<ContentTag>`、`List<ContentChapter>?`、`ContentSource`）与 `ContentGridModel.manga` 都不稳定 → **叶子卡片只准消费 `@Immutable ContentCardRenderModel`**（§5.3）。

### 4.3 昂贵的转换移出组合阶段

- 不要在 item lambda 内做 join/字符串拼接/查询；派生值用 `remember(...)` 缓存，或干脆在后台预计算（官方 *Follow best practices*：https://developer.android.com/develop/ui/compose/performance/bestpractices ）。
- 工作树引入的 `remember(item) { item.toContentCardRenderModel() }` 方向正确，但只对“同一实例复用”成立（Paging DiffUtil 满足，全量列表不满足）。

### 4.4 状态读取与收集

- 用 `collectAsStateWithLifecycle()`（收藏/路由已用）而不是 `collectAsState`；两状态以上用 `combine` 而非逐项 `collect`。
- 列表滚动状态等高频状态不应在组合阶段读取整个对象（官方 *State and Jetpack Compose*：https://developer.android.com/develop/ui/compose/state ）——收藏页无此问题，保持即可。

### 4.5 Paging 3 资源纪律（逐条来自调研表）

- `PagingData` 必须直达 `LazyPagingItems`，**禁止在 VM 里收集成 List**（官方 Paging overview：https://developer.android.com/topic/libraries/architecture/paging/v3-overview ）。
- **`pageSize` 对宫格应接近 100**：官方 `PagingConfig` KDoc 明确“tiled grid… consider closer to 100”（https://developer.android.com/reference/kotlin/androidx/paging/PagingConfig ）。HEAD 用 64 + `prefetchDistance=128` 是设备实测折中；Phase B 可在基准上把 `pageSize` 试向 96–128 移动，以官方建议为准重新测量（不要凭感觉改）。
- `enablePlaceholders=false` + `prefetchDistance > 0` 才合法：官方源码确认“只有 placeholders 或 prefetch 能触发继续加载，二者必须有一”（AndroidX `PagingConfig.kt`）。收藏页保持 `prefetch=128>0`，合法。
- **`maxSize`**：官方 KDoc 明确“cap the number of items kept in memory by dropping pages”，本轮建议 `maxSize = 6 * pageSize` 钉内存上界（默认不设上限 = 全保留）。
- `getRefreshKey` 是 PagingSource 契约必需项，用于失效/刷新后回到用户 anchor 附近（官方 *refresh keys*，https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data ）——`BatchMappingPagingSource` 已实现，**不得删除**（§9 风险）。
- `cachedIn(viewModelScope)`（官方 *avoid duplicate*，https://developer.android.com/topic/libraries/architecture/paging/v3-transform ）已做：让旋转/重组不重新冷启动、支持多次 collect。
- LoadState 语义：`refresh()`（新 generation）vs `retry()`（重试当前 generation，不失效）；`endOfPaginationReached` 是“到底”信号（https://developer.android.com/reference/kotlin/androidx/paging/compose/LazyPagingItems 与 load-state 页）。收藏页拉刷新仍走 `lazyPagingItems.refresh()`，DB 失效走 Room 自动 `invalidate()`，二者职责已经正确。
- `LazyPagingItems.get(index)` 会“通知 Paging 已访问该项并触发预取”，`peek` 不会（官方参考 https://developer.android.com/reference/kotlin/androidx/paging/compose/LazyPagingItems ）——现有 `getDuringSnapshotChangeOrNull` 用 `get` 语义，正确。

### 4.6 item 内修饰符纪律

- 滚动项里的 `onGloballyPositioned`、每帧测量、跨实例写全局 store 都是滚动帧的纯开销（对应 sharedElement/HeroCoverSnapshotStore，§6 默认关闭）。
- 官方对封面卡片还有个专门警告：item 尺寸在图片加载前后必须保持一致（避免 0 像素项、保持滚动位置），占位尺寸要对齐真实封面（https://developer.android.com/develop/ui/compose/lists ）——收藏卡片用固定 `aspectRatio` + 固定列宽已满足，重构中不要改成随图片尺寸塌陷的布局。

---

## 5. 目标架构（合并三源的重构方案）

总体原则：**保留 HEAD 的 Paging 管道不变形；把 mihon 的“一次拉齐 + 不可变扁平化”下沉到分页的 SQL/映射层；把 Compose 的“可跳过叶子”钉在渲染层。**

### 5.1 数据层：分页 SQL 直接投影「展示行」

让 `WorkFavouritesDao.pagingSource` 的每一行就是一个 entity 的**展示所需字段**（而不是只吐 `WorkFavouriteEntity` 再靠 Kotlin 侧多表关联）。参考 mihon `libraryView` 的 LEFT JOIN + 聚合子查询写法：

```sql
SELECT
  wf.entity_id,
  wf.anchor_manga_id,
  ep.preferred_local_manga_id,
  wf.pinned,
  m.title, m.cover_url, m.rating, m.state, m.nsfw, m.content_type,
  wf.created_at, wf.updated_at,
  tracking.new_chapters, tracking.last_chapter_date,
  wh.percent, wh.total_chapters, wh.updated_at AS history_updated_at
FROM selected ...           -- 现有 entity 去重逻辑（pinned/created/updated）
LEFT JOIN entity_preferences ep ...
LEFT JOIN manga m ...
LEFT JOIN (tracking 聚合) tracking ...
LEFT JOIN work_history wh ...
WHERE ...                   -- 现有全部过滤下推
ORDER BY <现有稳定排序>, wf.entity_id ASC
LIMIT :limit OFFSET :offset
```

效果：分页批次的 `BatchMapping` 不再需要 `resolveProjectionSet` + 分类 + 历史 + tracking 多表逐批关联来拼徽章；`WorkAggregate` 的**展示字段在 SQL 里已齐**。

取舍与注意：

- **先做最小字段集**（preferred_local_manga_id、pinned、title/cover/rating/state/nsfw、tracking.new_chapters、history.percent），其余按需追加；
- 多投影计数（`projection_count`）先降级为“有无投影”（0/1）或独立轻查询，**不要**在分页主查询里 GROUP BY 大表（没有基准前）；
- 每步在真实库上 `EXPLAIN QUERY PLAN`，**不凭猜测加索引**（沿用交接文档约束）；
- 保留现有去重子查询与稳定 `entity_id` 次级排序。

### 5.2 `BatchMapping` / 映射层瘦身（消除重复计算）

1. **mapper 不再重复查徽章**：收藏页 Pager 的 `toRequestedListModelList(flags = NO_FAVORITE or NO_PROGRESS or NO_COUNTER)`（采纳 WIP 的唯一正确改动）；`progress/counter` 一律由聚合行提供。
2. **无 metadata selection 时不预取 tracking 详情**：`prefetchTrackingDetails` 在 selection 全空时完全跳过（现在是空 keys 查空集合，收益为负）。
3. **投影解析按需降级**：分页行自带 `preferred_local_manga_id` 且列表模式为 GRID/COMPACT_GRID（不展示多投影明细）时，`BatchMapping` 不需要完整 `projections` 列表——只要代表投影 + “有无投影”标记；DETAILED_LIST 再补全。
4. **批量查询维持**：metadata selection / override 继续按“当前批 entity 集合”批量查（HEAD 已做，保留）。

### 5.3 渲染模型前置（钉死稳定性）

- 保留并推广 `@Immutable ContentCardRenderModel`（只含 `Long/String/Int/ReadingProgress?/枚举` 等全稳定字段，工作树已引入）。
- 目标：叶子卡片永远只消费 `ContentCardRenderModel`；`ListModel`（DOMAIN 语义、含 `Content`）留在 Paging 项里，由 `remember(item)` 或（更优）由 `BatchMapping` 直接产出渲染字段，避免组合阶段触碰 `Content`。
- `ContentGridModel.manga: Content` 在编译器眼里 **unstable**；任何把 `ContentGridModel` 直接传进叶子项、或在渲染模型里反序列化出 `Content` 的写法都会破坏跳过。

### 5.4 UI 层收紧

1. **稳定 key + contentType**（HEAD 已做，保留）：`key = item.id`（收藏页即 entityId），`contentType` 区分 grid/list/detailed/补充项。
2. **Pager 边界**：`FavoritesHostScreen` 的 `HorizontalPager` 默认只组合当前页；显式保持 `beyondViewportPageCount = 0`，并参照 mihon 加页范围 guard 注释，防止后人放大（每页内是独立 LazyVerticalGrid，多页同时组合会放大测量成本）。
3. **Paging 资源上限**：`FavouriteLibraryPagingConfig` 增加 `maxSize = 6 * pageSize`；`prefetchDistance=128`、`initialLoadSize=64` 保持（HEAD 实测）。
4. **删除恒为 false 的动画包装**：`VerticalRailAnimatedVisibility`（三个列表模式调用点 `isAnimationEnabled = isVerticalCardListAnimationEnabled(false)`）恒假 → 直接移除包装，省掉每 item 的状态与组合开销（收藏页 `enableItemAnimations=false` 已做，保留）。
5. **收集 API**：保持 `collectAsLazyPagingItems()`（PagingData 绝不在 VM 收集成 List）+ `collectAsStateWithLifecycle`。
6. **选中态**：保持 `Set<Long>` 不可变 + 状态提升到 Route（已做）；不要在下拉刷新/筛选时清空选中（除非语义要求）。

### 5.5 ViewModel 折叠

- **删除工作树非分页路径**（`content` 全量流及其关联代码）与 HEAD 遗留死代码（`observeFavorites`/`prepareGroups`/`mapList`/`PreparedGroupsState`）。
- 保留 `FavouriteListParams` + `flatMapLatest { Pager(...) }.cachedIn(viewModelScope)` 单一职责（HEAD §2.1 已有）。
- 过滤/排序/刷新参数变更 → `distinctUntilChanged` → 只重建受影响管道。

---

## 6. 性能闸门：可暂时移除 / 降级的功能

按“收益/成本”排序，成本高且非关键的功能**本轮默认关闭或删除**：

| 功能 | 现状 | 建议 | 理由 |
|---|---|---|---|
| 卡片 shared element 转场 | 收藏页当前页开启：每卡 `onGloballyPositioned` 记录 bounds + `HeroCoverSnapshotStore.put`（图片成功即写全局 store） | **默认关闭**；仅“停止滚动后”或进入详情前按需启用 | 滚动中每帧更新 bounds 是纯开销；mihon 收藏页无转场 |
| 封面 crossfade | `allowCrossfade = !sharedTransitionEnabled` | 收藏页**恒 false**（即使关转场） | 滚动中每张新图淡入抢帧 |
| `VerticalRailAnimatedVisibility` | 三个列表模式都有包装（恒 disabled） | **删除包装** | 恒假分支仍产生每 item 组合/状态开销 |
| MULTI_PROJECTION 快速过滤 / 投影计数徽章 | 需要完整投影集 | 先降级为“有无投影”（SQL 0/1），DETAILED_LIST 再补 | 只有列表模式需要精确多投影明细 |
| 快速筛选的“来源/分组”逐卡匹配 | VM 层 per-item `getContentGroup/getOriginGroup + matches` | **下推 SQL**（来源名/内容类型已在 SQL 侧过滤），VM 不再逐项匹配 | 减少每批 CPU；mihon 的 filter 就在数据层 |
| tracker metadata 详情预取 | mapper 每批 `prefetchTrackingDetails` | 无 tracking metadata selection 时跳过 | 空集合调用无收益 |
| 分类标签页实时计数 | 已有 DAO 投影（`observeCategoryCountEntries`） | 保留；把“计数刷新”与“列表刷新”解耦（计数慢不阻塞列表） | 避免一次失效同时拖慢两处 |
| 大封面 `largeCoverUrl` | 卡片用 `coverUrl` | 确认网格只用 `coverUrl`（小图），不回退大图 | 减少解码内存/耗时 |

**保留不动**：选择/多选、删除、置顶、排序、分类、Space、NSFW 隐藏、黑名单、源 preset、分页与“冲刷”修复（§2.5/§2.6 测试必须继续绿）。

---

## 7. 实施阶段

> 每阶段一个可编译、可测试、可人工复测的小步（沿用交接文档 §8 操作纪律：不覆盖用户改动、不动 Reader/播放器）。

### Phase A —— 收敛回 Paging（最小改动）
- 撤销工作树对 `FavouritesListViewModel` 的全量回归；恢复 `pagingContent`、`FavouriteLibraryPagingConfig`（当前 WIP 已删）、`BatchMappingPagingSource` 用法与 `WorkFavouritesDao.pagingSource`（以 HEAD 为基线，保留 WIP 的 `NO_PROGRESS|NO_COUNTER|NO_FAVORITE` flags 修正与 `canUseFavouriteLibraryProjection`）。
- 恢复/保留 `BatchMappingPagingSourceTest`、`WorkPagingDaoTest`、收藏 VM paging 测试。
- **验证**：`./gradlew :app:compileDebugKotlin` + `core.paging.*` 测试全绿；设备日志 `LibraryPaging` 静止无 Refresh。

### Phase B —— 扁平投影 + 映射瘦身
- 按 §5.1 扩展 `pagingSource` SELECT（先做最小字段集），`EXPLAIN QUERY PLAN` 验证。
- 按 §5.2 消除 mapper 重复查询与空 prefetch。
- **验证**：`WorkPagingDaoTest` 大库夹具（9800 行）批耗时对比；单元测试全绿。

### Phase C —— UI 收紧
- §6 性能闸门逐项落地：shared element 默认关、crossfade 关、删 `VerticalRailAnimatedVisibility` 包装、`maxSize` 配置、pager 边界注释。
- **验证**：设备快速 fling + `adb logcat -s LibraryPaging:D`；`android layout --diff` 检查层级无异常增长。

### Phase D —— 性能基准与基线
- 参考 mihon `macrobenchmark/`，为 Kototoro 增加收藏页滚动基准 + **Baseline Profile**（当前仓库无 baseline profile；这是首屏/滚动收益最大的单一手段）。
- 新增批耗时/帧率回归断言（CI 允许 macrobenchmark 则用 jank 率；否则用 `WorkPagingDaoTest` 批耗时 + logcat 采样）。
- **验证**：`:app:assembleDebug` + 设备人工复测（锁屏静止 1–2 分钟验证无冲刷、`entity_preferences` 不增长）。

---

## 8. 验收标准

1. 启动收藏页（6315 entity 库）：首屏 64 条渲染可交互，**不再全量构建**。
2. 快速 fling：`adb logcat -s LibraryPaging:D` 无 `IndexOutOfBoundsException`；无明显持续 Append 风暴。
3. 静止 40s：`LibraryPaging` 事件 0 条；`entity_preferences` 行数不变。
4. 带过滤（断网 Downloaded / SFW / 黑名单）停在列表中部静止 1–2 分钟：已加载收藏不“冲刷”（§2.5 回归测试绿）。
5. `./gradlew :app:testDebugUnitTest --no-daemon`（含 `core.paging.*`）与 `:app:compileDebugKotlin` 全绿。
6. 可选（Phase D）：macrobenchmark 滚动 jank 率较基线下降，且纳入 CI 阈值。

---

## 9. 风险与回滚

- **Paging × 过滤定位**：靠 §2.5/§2.6 修复与测试兜底；不得把 `getRefreshKey` 改回裸 `anchorPosition`。
- **大 SELECT / GROUP BY**：可能改变 SQLite 计划；每步先 `EXPLAIN QUERY PLAN`，退化则回退该查询，保持“字段少、JOIN 少”为默认。
- **不猜索引**：仅当 EXPLAIN 显示全表扫描且真实规模下可复现变慢时，由数据库负责人评估。
- **工作树未提交改动**：Phase A 前与用户确认保留哪些 WIP 改动（本方案只建议保留 mapper flags 与投影分支）。
- **回滚点**：每阶段独立 commit（conventional commits），可单独 revert。

---

## 10. 附：与现有文档/代码的关系

- 保留：`large-library-performance-handoff-2026-08.md`（HEAD Paging 设计依据）。
- 新增：`docs/architecture/paging3-favourites-grid-research-2026-09.md`（Compose/Paging 官方调研，§4 引用来源，含 AndroidX 源码与 GitHub 一手来源）。
- 参考：`docs/architecture/compose-performance-audit.md`、`compose-re-audit-results.md`（unstable `List` 与 lambda 问题与本方案 §5.3 结论一致）。
