# 收藏列表向 Komikku 对齐实施计划（2026-09）

> 状态：待实施
> 最后更新：2026-09-01
> 范围：Kototoro 收藏页的数据读取、状态所有权、Quick Filters、分类分页与详情返回恢复
> 不在范围：历史、更新、浏览来源等仍有明确窗口语义的列表；实体身份体系重构；收藏 UI 视觉重做
> 参考：[`komikku-library-list-architecture-research-2026-09.md`](./komikku-library-list-architecture-research-2026-09.md)、
> [`paging3-lazy-list-alternatives-research-2026-09.md`](./paging3-lazy-list-alternatives-research-2026-09.md)

## 1. 决策摘要

收藏页的实施主线调整为：

```text
Room 窄投影
    -> 唯一、完整、可观察的 FavouriteLibrarySnapshot
    -> 内存 Quick Filters / Space / Category / Group / Sort 派生
    -> ViewModel-owned immutable List
    -> LazyColumn / LazyVerticalGrid
```

收藏页不再以 Paging3 为默认架构。Paging3 只在以下条件同时成立时作为回退：

1. 已完成窄 read model；
2. 已消除逐项 DAO、完整 `WorkAggregate` 和详情字段；
3. 10k release 基准仍超过本文性能门槛；
4. 继续优化 SQL、索引、summary 或 invalidation 后仍不达标。

决策依据：

- 用户把相同备份恢复到 Komikku 后，实际收藏列表依然极其流畅；
- Komikku 收藏链路不使用 Paging3，而是完整 `Flow<List<...>>`、内存筛选排序和 Lazy UI；
- Kototoro 当前收藏条数约 6,300，数量本身不大；
- 当前主要成本是每页构造宽 `WorkAggregate`、执行复杂 SQL、读取 metadata/override、再映射通用
  `ContentListModel`；
- 当前 retained Paging window、raw/mapped offset、placeholder 和 generation 交接属于分页引入的附加状态；
- 完整快照由收藏页面级状态持有后，详情返回不需要重建数据窗口。

这不是把 Komikku 源码逐行移植。Kototoro 必须保留自身更强的 entity ID、stable key、Space、projection 和语义锚点，
同时避免照搬 Komikku 的 `M.*`、N+1 与无 item key。

## 2. 成功定义

完成后必须同时满足：

### 2.1 用户体验

- 恢复 6k+ 收藏备份后，首次进入收藏能稳定加载全部作品；
- 进入详情再返回，原列表立即存在，无空列表、骨架屏、页窗口接管或明显封面重载；
- 未改变筛选/排序且数据未变化时，返回同一 entity 和相同 viewport offset；
- Quick Filters、分类、Space、来源 preset 和排序切换不触发新的收藏数据库查询；
- 分类切换不创建独立的数据 generation，也不复制完整 row 对象；
- GRID、COMPACT_GRID、LIST、DETAILED_LIST 功能一致；
- 选择、置顶、移除、标记完成、下载、分享、编辑覆盖、实体整理行为不回归。

### 2.2 架构

- 收藏 UI 不依赖 `PagingData`、`LazyPagingItems` 或收藏专用 `PagingSource`；
- 收藏 UI 不依赖 `WorkAggregate`；
- 全库只有一份基础 `FavouriteCardRow` 快照；
- 分类和排序结果只保存 entity ID，不复制 card row；
- 所有 item identity 使用 `entityId`；
- Room、实体图、metadata/override、tracking 和下载细节隐藏在收藏快照模块实现内部；
- 模块的 interface 是测试面，调用方不需要理解 joins、bindings、projection fallback 或 invalidation 表集合。

### 2.3 性能

本文第 11 节的 release/R8 基准全部通过。任何“debug 看起来流畅”都不能代替验收。

## 3. 当前实现基线

### 3.1 状态所有权

当前 `FavoritesHostScreen`：

- 由 `FavouritesContainerViewModel` 持有分类和全局过滤状态；
- 使用 `HorizontalPager` 展示分类；
- 每个分类通过 `hiltViewModel(key = "favorites-$categoryId")` 创建独立 `FavouritesListViewModel`；
- 顶栏筛选面板需要保存“当前分类 ViewModel 引用”才能修改 Quick Filters；
- 每个分类页面分别收集 `PagingData`。

结果是：分类状态、过滤状态、数据库查询、列表 generation 和选中操作分散在 Container、分类 ViewModel、通用列表 Route
与 retained snapshot controller 之间，interface 很浅，调用方必须理解多个模块如何协作。

### 3.2 数据链路

当前收藏链路：

```text
filter/sort/mode/category/space/refresh combine
    -> flatMapLatest
    -> new Pager
    -> WorkFavouritesDao.pagingSource()
    -> FavouriteLibraryPagingRow
    -> WorkAggregateRepository.buildFavouritePagingAggregates()
    -> WorkAggregate
    -> metadata source selection + manual override
    -> ContentListMapper
    -> PagingData<ListModel>
```

当前 DAO 投影包含：

- `work_favourites.*`；
- `entity_preferences.preferred_local_manga_id`；
- 完整展示 Manga 的 title、URL、封面、author、description、sourceData 等字段；
- `work_history` 多个字段；
- 对 tracks 的 group by 聚合；
- Space、内容类型、出版状态、NSFW、下载、新章节、来源、tag 的动态 SQL 条件；
- 为所有排序类型准备的动态 `CASE WHEN ORDER BY`。

页映射之后仍会执行：

- projection/source/group/origin 解析；
- category、binding、tag 聚合；
- metadata authority 查询；
- manual override 查询；
- global tag blacklist；
- 通用 `ContentListMapper`；
- `WorkAggregate -> ReadingProgress -> ListModel`。

Paging 限制了单次峰值，但没有减少每条收藏的建模成本，并引入了 raw/mapped coordinate、refresh key、placeholder、
page eviction 和 retained-window 状态。

### 3.3 已有工作必须保留

以下成果不因收藏取消 Paging 而丢弃：

- entity ID 是收藏列表唯一 item identity；
- preferred projection 与 anchor fallback 的既有语义；
- 读取路径不得写 `entity_preferences`；
- Room 相关索引、批量查询和备份恢复修复；
- Navigation3 back-stack、ViewModelStore 与 saveable state；
- Lazy item stable key、contentType 与语义 anchor；
- `BatchMappingPagingSource` 对历史、更新等其他页面仍可继续使用；
- 通用 `AppContentListRoute` 的静态 List 路径。

## 4. 目标深模块

### 4.1 Seam 放置

在 `favourites` 功能内部、Room DAO 与 UI state holder 之间建立收藏快照模块：

```text
favourites/data       Room rows and queries
        |
        v
favourites/domain/library/FavouriteLibrarySnapshotStore
        |
        v
favourites/ui/container/FavouritesContainerViewModel
        |
        v
favourites/ui/compose
```

数据库是 local-substitutable dependency，可使用 Room in-memory database 测试。当前只有一个生产实现，不新增没有实际
变化点的 Kotlin port/adapter 层。`FavouriteLibrarySnapshotStore` 可以是 concrete class；其公开函数、结果不变量、错误和
性能特征共同组成模块 interface。

### 4.2 最小 interface

```kotlin
class FavouriteLibrarySnapshotStore @Inject constructor(
    private val database: MangaDatabase,
    private val sourceGroupManager: SourceGroupManager,
) {
    fun observe(): Flow<FavouriteLibrarySnapshot>
}
```

interface 约束：

- `observe()` 不接收 category、filter、sort、list mode、Space 或 search 参数；
- 每次发射都是自洽的完整快照；
- 同一个 entity 在 `rowsByEntityId` 中最多一行；
- `allEntityIds` 和所有 category slice 只引用存在的 row；
- 无效/dangling projection 不导致整条 Flow 失败；按确定性 fallback 或显式 broken 标记处理；
- 数据库读取和 mapping 不在主线程；
- 上游未变化时不重新发射相等快照；
- 不在读取路径写库；
- 不执行网络访问；
- 不把 Room entity、`WorkAggregate` 或完整 entity graph 暴露给调用方。

### 4.3 快照模型

```kotlin
@Immutable
data class FavouriteLibrarySnapshot(
    val rowsByEntityId: Map<Long, FavouriteCardRow>,
    val allEntityIds: List<Long>,
    val membershipsByCategory: Map<Long, List<FavouriteMembership>>,
    val quickFilterMetadata: FavouriteQuickFilterMetadata,
) {
    companion object {
        val Empty = FavouriteLibrarySnapshot(
            rowsByEntityId = emptyMap(),
            allEntityIds = emptyList(),
            membershipsByCategory = emptyMap(),
            quickFilterMetadata = FavouriteQuickFilterMetadata.Empty,
        )
    }
}

@Immutable
data class FavouriteMembership(
    val entityId: Long,
    val categoryId: Long,
    val isPinned: Boolean,
    val sortKey: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
```

不能把 `isPinned/createdAt/updatedAt` 简化为 entity 全局字段：`work_favourites` 以 `(entity_id, category_id)` 为主键，
这些属性属于 membership。全部收藏 slice 需要沿用现有代表 membership 选择规则，并以 `entityId` 作最终 tie-breaker。

### 4.4 `FavouriteCardRow` 字段预算

首版 row 只包含三种卡片、现有 Quick Filters、排序和 action routing 真正需要的数据：

```kotlin
@Immutable
data class FavouriteCardRow(
    val entityId: Long,
    val displayMangaId: Long,
    val localMangaIds: Set<Long>,
    val title: String,
    val altTitle: String?,
    val coverUrl: String?,
    val largeCoverUrl: String?,
    val author: String?,
    val sourceName: String,
    val sourceGroupFlags: Int,
    val sourceOriginFlags: Int,
    val contentType: ContentType?,
    val publicationState: ContentState?,
    val isNsfw: Boolean,
    val rating: Float,
    val progress: ReadingProgress?,
    val readingStatus: ScrobblingStatus,
    val newChapters: Int,
    val lastChapterDate: Long,
    val projectionCount: Int,
    val projectionSourceNames: Set<String>,
    val tagIds: Set<Long>,
    val displayTags: List<FavouriteCardTag>,
    val isDownloaded: Boolean,
    val hasBrokenProjection: Boolean,
    val overrideTitle: String?,
    val overrideCoverUrl: String?,
)
```

这只是字段审计起点，不是预留能力。实施 Phase 1 必须逐个关联到真实消费者；没有消费者的字段删除。

明确禁止进入 row：

- description；
- sourceData；
- 完整 `Content`/`MangaEntity`；
- 完整 projections 对象列表；
- `WorkAggregate`、`WorkIdentity`；
- 完整 `WorkHistoryEntity`、TrackEntity；
- parser、repository 或 Android Context；
- 封面 bitmap；
- 详情页 metadata。

`displayTags` 只保留详细列表要展示的有限 tag 数据；用于过滤的 tag identity 使用紧凑 `tagIds`。若 global tag blacklist
依赖名称而不是 ID，应在快照构建阶段统一解析为 flags，不能把完整 tag graph 带到 UI。

`sourceGroupFlags/sourceOriginFlags` 表示已经归一化的筛选维度，不是展示文案。来源本地化标题在 UI mapping 时按需解析，
避免 domain row 持有 Android Context 或随语言变化的字符串快照。

### 4.5 代表 projection 不变量

选择展示 projection 的优先级：

1. `entity_preferences.preferred_local_manga_id` 指向当前 entity 的有效本地 projection；
2. active memberships 的有效 `anchor_manga_id`，按 pinned、updatedAt、createdAt、categoryId、mangaId 确定性选择；
3. `entity_binding` 中有效 local projection，使用现有权威状态排序；
4. 无可用 projection 时保留 broken row，允许用户执行实体整理，不静默丢失收藏。

必须新增 characterization test，证明正常数据库中同一 entity 的 active membership anchors 是否一致。如果不一致：

- 第一阶段只做确定性读取，不在热路径修复；
- 单独记录诊断计数；
- 如需归一化，放到显式数据库维护/迁移事务，不能在 `observe()` 内写库。

## 5. 数据读取设计

### 5.1 首版不新增物化表

遵循 YAGNI，第一版使用窄 Room projections 和组合 Flow，不立即新增 summary table、触发器或数据库迁移。建议拆为：

1. `observeFavouriteCardBaseRows()`：一行一个 entity，展示 projection + progress + tracking summary；
2. `observeFavouriteMembershipRows()`：全部 active `(entityId, categoryId)` membership；
3. `observeFavouriteProjectionFacets()`：批量 projection count、source names、broken flag；
4. `observeFavouriteTagFacets()`：批量 entity/tag identity 和有限展示 tag；
5. `observeDownloadedFavouriteIds()`：下载索引变化映射成 entity ID set；
6. `observeFavouriteOverrides()`：只读取 title/cover 等列表需要的 override。

Store 内部 `combine` 后一次构造完整快照。辅助 rows 只携带 primitives/strings，不创建领域对象图。

如果 Room 对多个全量 Flow 的 invalidation 扩散过大，先合并查询或对每条 Flow 做 `distinctUntilChanged()`；只有 release
trace 证明 SQL 重算仍超预算，才进入第 12 节的 summary table 方案。

### 5.2 SQL 原则

- 主查询一行一个 entity，不在映射后过滤分页行；
- 所有排序必须以 `entity_id` 作为最终 tie-breaker；
- 收藏条件尽可能进入最内层 CTE；
- tracking 聚合只处理 active favourite entity IDs；
- Space、Quick Filters、category、sort 不作为主查询动态参数；
- category membership 独立返回，避免复制 card fields；
- tags/sources 使用批量 rows 后在 Kotlin groupBy，不使用逐 entity DAO；
- 为 `entity_binding(entity_id, source, state)`、tracks entity、active favourites 等实际 query plan 补齐覆盖索引前，必须先
  执行 `EXPLAIN QUERY PLAN`；
- 不为了避免 Kotlin groupBy 使用超长 `group_concat` 拼复杂结构；
- 不使用 `SELECT m.*`；
- 不在 query 中保留十余种 `CASE WHEN ORDER BY`，sort 移到内存；
- 不在 read path 执行 INSERT/UPDATE。

### 5.3 Invalidation 原则

基础快照可由以下变化重新构建：

- active `work_favourites` 或 category membership；
- preferred projection/列表 override；
- 展示 manga 的 title、cover、state、source 等列表字段；
- work history progress；
- tracks new chapter summary；
- entity bindings/projection facets；
- relevant tags；
- local download index。

以下变化不得触发数据库重新读取：

- Quick Filter 选择；
- 当前 category；
- group/source tag；
- source preset；
- sort order；
- list mode；
- viewport/selection；
- 进入详情或返回。

如果单章阅读进度更新导致完整窄快照重查仍在预算内，保留粗粒度 invalidation，优先 KISS。如果超预算，再把 progress/
tracking/download 拆成独立 map Flow，在内存中替换受影响 row；不要提前实现复杂 per-item patch protocol。

## 6. 内存派生设计

### 6.1 状态所有权

`FavouritesContainerViewModel` 升级为收藏页面唯一 screen-level state holder：

```kotlin
data class FavouriteLibraryUiState(
    val isInitialized: Boolean,
    val categories: List<FavouriteTabModel>,
    val rowsByEntityId: Map<Long, FavouriteCardModel>,
    val visibleIdsByCategory: Map<Long, List<Long>>,
    val activeCategoryId: Long,
    val filters: FavouriteLibraryFilters,
)
```

基础快照使用 `stateIn(viewModelScope, SharingStarted.WhileSubscribed(...), Empty)` 或等价的明确生命周期策略。收藏详情 push
期间，NavEntry/ViewModelStore 必须继续持有该 state holder；不得因为 Composable 暂时不可见而清空 replay 值。

逐分类 `FavouritesListViewModel` 最终删除。过渡期允许它变成只消费 Container 共享 state 的薄状态，但不得继续创建 Pager、
访问 DAO 或拥有基础 rows。

### 6.2 派生流水线

派生拆成三个纯函数阶段：

```text
FavouriteLibrarySnapshot
    -> applyVisibility(space/group/source preset/nsfw/global blacklist)
    -> applyQuickFilters(downloaded/new chapters/projection/status/source/tag)
    -> groupAndSort(category/membership/order)
    -> Map<CategoryId, List<EntityId>>
```

要求：

- 所有阶段运行在 `Dispatchers.Default`；
- 输入相等时不重复计算；
- filter/group/sort 函数无 I/O、无 Context、无全局单例读取；
- source group/origin group 在 snapshot 构建时转成紧凑 flags，或一次性预计算 map；
- sort comparator 只读 row/membership primitives；
- 结果只生成 ID list；
- 同一 entity 在同一 category slice 中最多出现一次；
- selected item identity 始终为 entity ID；
- 空结果和未初始化是不同状态，禁止用瞬时 `emptyList()` 表示 Loading。

### 6.3 Quick Filter metadata

当前 `FavoritesListQuickFilter` 按 category 查询 tags/sources。迁移后：

- 可用 source/tag 从基础快照 facets 派生；
- category 切换只在内存统计该 category 的 entity IDs；
- network offline 自动启用 Downloaded 的产品语义保持不变；
- filter selection 继续由 `GlobalFavoritesState` 持有；
- Filter Panel 直接调用 Container，不再保存 active child ViewModel reference；
- Quick Filter model 生成不得访问 DAO。

## 7. UI 与返回恢复

### 7.1 Compose 数据入口

收藏页改用 `AppContentListRoute` 的静态 `content: StateFlow<List<ListModel>>` 路径，或在迁移中发现通用 Route 的
`Content` 依赖阻碍窄 row 时，提取收藏专用 `FavouriteLibraryRoute`。选择规则：

- 如果构造 `ListModel` 不需要 description/sourceData/full tags/full projections，复用通用 Route；
- 如果通用 selection/navigation contract 强迫 row 携带完整 `Content`，建立收藏专用 Route；
- 不允许为了复用 UI 而重新把宽领域模型塞回快照。

允许复用底层卡片 Composable、selection toolbar 和 Lazy layout；避免复制视觉实现。

### 7.2 Action routing

收藏 action 使用稳定引用，不依赖卡片携带完整 `Content`：

```kotlin
data class FavouriteItemRef(
    val entityId: Long,
    val displayMangaId: Long,
    val localMangaIds: Set<Long>,
)
```

- 详情：直接构造 `DetailsOrigin.EntityGraph(entityId, preferredLocalMangaId)`；
- 移除/置顶/实体整理：使用 entity ID，经 snapshot lookup 扩展 manga IDs；
- 标记完成：UseCase 接收 entity/display IDs，在操作时批量读取所需 projection；
- 下载/分享/编辑 override：用户触发后按选中 IDs 批量解析完整 Content，不常驻在 6k rows；
- 多选期间快照变化时，删除已经不存在的 selected IDs。

### 7.3 Lazy identity

- `key = entityId`；
- `contentType` 按 card/header 类型稳定；
- category 只改变 ID 序列，不改变 row identity；
- 禁止 positional key；
- 代表 projection、封面、标题改变不改变 item key；
- shared transition instance key 继续包含页面/category 上下文，实体 key 本身保持稳定。

### 7.4 滚动状态

删除收藏专用 retained Paging window 后仍保留：

- 每个 category 的 `LazyListState`/`LazyGridState` saveable state；
- active category ID，而不仅是 page index；
- `entityId + offset` 语义 anchor，用于列表在离开期间合法重排后的二次校正；
- anchor 删除时退化到最近邻或保存的 index hint；
- process death 只恢复 filters/sort/category/anchor，不序列化完整快照。

普通详情返回且快照未变化时，不执行 scroll correction；Compose 原 state 即为真值。语义 anchor 只处理实际重排和进程恢复，
不再承担 Paging generation handoff。

## 8. 分阶段实施

每个 Phase 必须独立可编译、可测试、可回退。禁止一次提交同时改 schema、DAO、ViewModel、UI 和删除旧链路。

### Phase 0：固定基线与语义清单

目标：先证明“不能回归什么”。

工作：

1. 固定同一份 6k+ 备份或生成等价脱敏 fixture；
2. 记录 GRID/LIST/DETAILED_LIST 三种卡片实际读取字段；
3. 列出每种 Quick Filter、Space、preset、category、sort 的当前结果；
4. 记录同一 entity 多 category、不同 anchors、preferred projection 缺失、broken binding 的样本；
5. 记录收藏冷启动、filter/sort、详情返回、heap、GC 基线；
6. 为现有 SQL 执行 `EXPLAIN QUERY PLAN` 并保存 debug 报告，不提交用户数据库；
7. 明确当前未提交 Paging 实验的保留/删除归属，不覆盖用户改动。

交付：

- `FavouriteCardFieldContractTest`；
- `FavouriteLibrarySemanticsCharacterizationTest`；
- 基线 benchmark 结果附件或文档表格。

退出条件：所有现有筛选/排序/action 行为有测试或明确人工验收步骤。

### Phase 1：建立窄 Room projections

目标：得到不依赖 Paging、`WorkAggregate` 和完整 Manga 的基础 rows。

建议新增：

```text
favourites/data/FavouriteCardBaseRow.kt
favourites/data/FavouriteMembershipRow.kt
favourites/data/FavouriteProjectionFacetRow.kt
favourites/data/FavouriteTagFacetRow.kt
favourites/data/FavouriteLibraryReadDao.kt
```

工作：

1. 从字段契约反推 SQL 列；
2. 主查询一行一个 entity；
3. memberships、tags、projection facets 分离为窄批量 Flow；
4. 将现有代表 projection policy 写成确定性查询/纯函数；
5. 对 6k/10k fixture 验证无 N+1；
6. 对所有查询保存 `EXPLAIN QUERY PLAN` 断言/人工检查结果；
7. 暂不接 UI，旧 Paging 路径继续工作。

退出条件：

- 10k 查询不会返回完整 description/sourceData/domain graph；
- 一次发射的 DAO 调用数量固定，不随收藏数增长；
- row identity、代表 projection、membership 与旧路径语义一致；
- Room in-memory integration tests 通过。

### Phase 2：实现收藏快照深模块

目标：集中隐藏多 Flow 组合、fallback、facets 和 invalidation。

建议新增：

```text
favourites/domain/library/FavouriteCardRow.kt
favourites/domain/library/FavouriteLibrarySnapshot.kt
favourites/domain/library/FavouriteLibrarySnapshotStore.kt
```

工作：

1. 组合 Phase 1 的窄 Flow；
2. 构造 `rowsByEntityId`、`allEntityIds`、memberships 和 filter metadata；
3. 保证完整快照原子发布；
4. 对相等快照做 `distinctUntilChanged`；
5. 记录 debug-only 构建耗时、row 数、缺失 projection 数和 retained bytes 估算；
6. 用 interface-level tests 覆盖正常更新、broken rows、删除、category 变化和 progress 更新；
7. 不暴露内部 DAO rows。

退出条件：Store 在 10k fixture 上稳定发射完整快照，调用方只需理解一个 `observe()`。

### Phase 3：实现纯内存派生

目标：Quick Filters、Space、preset、category 和 sort 不再重查数据库。

建议新增：

```text
favourites/domain/library/FavouriteLibraryFilter.kt
favourites/domain/library/FavouriteLibrarySorter.kt
favourites/domain/library/FavouriteLibraryDeriver.kt
```

这些可以是同一模块内的 internal 文件/函数，不为了测试而暴露多个 external seams。

工作：

1. 将 `matchesFavouriteMacroFilter` 等逻辑迁移到窄 row；
2. 将 category/source/tag/Space 筛选转成 primitives/set 判断；
3. 将所有收藏排序转为稳定 comparator；
4. 生成 `Map<CategoryId, List<EntityId>>`；
5. 从快照派生 Quick Filter metadata；
6. 编写 table-driven 与 property tests；
7. 在 `Dispatchers.Default` 上测 10k filter/sort。

退出条件：任何 filter/sort 输入变化的 DAO 查询计数为 0。

### Phase 4：Container 接管唯一状态

目标：形成与 Komikku `LibraryScreenModel` 等价的状态所有权。

工作：

1. 将 Store 注入 `FavouritesContainerViewModel`；
2. Container combine snapshot + GlobalFavoritesState + category order + Space/preset；
3. Container 暴露 rowsById、visible IDs、active category、quick filter model；
4. Filter Panel 改为直接调用 Container；
5. 删除 `activeFavouritesViewModelRef` 桥接；
6. 过渡期 `FavouritesListViewModel` 只转发共享 state/action，不访问数据库；
7. 使用 debug-only shadow comparison 对比新旧 visible entity ID 顺序，记录首个差异，不渲染双份 UI。

退出条件：切换 category/filter/sort 不创建 Pager，不执行收藏 DAO 主查询。

### Phase 5：UI 切换普通 List

目标：从 `LazyPagingItems` 切到完整静态快照。

工作：

1. 将 card rows 映射为轻量 card models；
2. `HorizontalPager` 各页只读取共享 `rowsById + category IDs`；
3. 使用静态 List Lazy DSL；
4. 保留 entity stable key/contentType；
5. 详情导航改用 `FavouriteItemRef`；
6. 保留 list/grid states 与 semantic anchor；
7. 对收藏设置 `retainPagingSnapshotOnDetailsNavigation = false`；
8. 验证 pull-to-refresh：更新检查可以触发 tracker 工作，但本地列表不通过伪 refresh token 重建。

退出条件：收藏运行时不收集 `PagingData`，详情返回没有 snapshot handoff。

> 状态（2026-09-02）：**已完成渲染切换**。工作项 1~4、7、8 落地；工作项 5 以「list item id 即 `entityId` + row 的 `displayMangaId`」实现，`FavouriteItemRef` 类型留给 Phase 6；工作项 6 的 semantic anchor 按 §6.1 的说明推迟到 Phase 8（未变化时 saveable 索引即精确）。实施记录、有意偏差（tracking metadata authority 的显示覆盖与 badge、快筛 chips 仍查 DAO）见 [`favourites-komikku-migration-handoff-2026-09.md`](./favourites-komikku-migration-handoff-2026-09.md) §4。

### Phase 6：迁移 actions 与删除逐分类 ViewModel

目标：去掉通用 `Content` contract 对窄 row 的反向污染。

工作：

1. action handlers 全部接受 entity/item refs；
2. 需要完整 Content 的动作在点击后批量 resolve；
3. 将 selection state 提升到 Container 或页面 saveable state；
4. 删除 assisted `FavouritesListViewModel.Factory`；
5. 删除每 category Hilt ViewModel key；
6. 简化 `FavoritesHostScreen`、Filter Panel 和 top-bar bridge；
7. 保证 standalone `FavouritesActivity` 与 main shell 使用同一状态模块。

退出条件：收藏页只有一个 screen-level state holder。

### Phase 7：删除收藏 Paging 专用代码

只删除确认无其他调用方的内容：

- `FavouritesListViewModel.pagingContent`；
- `WorkAggregateRepository.createFavouritePagingSource()`；
- `FavouriteLibraryPagingRow`；
- `WorkFavouritesDao.pagingSource()` 和仅服务该路径的 `findList()` 包装；
- `FavouriteLibraryPagingConfig`；
- 收藏对 `BatchMappingPagingSource` 的使用；
- 收藏对 `RetainedPagingSnapshotController` 的启用；
- 收藏专用 raw/mapped coordinate 日志与测试。

不得删除：

- 历史、更新等其他页面仍使用的 Paging 基础设施；
- 通用 semantic anchor；
- 通用 Lazy state/saveable state；
- Room 表、备份格式、entity identity；
- 仍被统计、迁移、详情等模块使用的 `WorkAggregateRepository`。

退出条件：`rg` 确认 favourites 包无 Paging import，相关 dead code 和 tests 已清理。

### Phase 8：Release 验收与收敛

1. 运行第 11 节全部测试；
2. 使用同备份设备与 Komikku 做并列人工对照；
3. 记录 6k 与 10k 指标；
4. 删除 debug shadow comparison、临时开关和诊断日志；
5. 更新 `large-library-performance-handoff-2026-08.md`，注明收藏部分已被本文取代，历史/更新 Paging 结论不变；
6. 输出风险、验证命令和回退说明。

## 9. 文件级改造地图

| 当前文件 | 计划 |
|---|---|
| `favourites/data/WorkFavouritesDao.kt` | 保留写侧和通用查询；收藏读取迁到窄 read DAO；最终删除 favourite Paging 查询。 |
| `favourites/data/FavouriteLibraryPagingRow.kt` | Phase 7 删除。 |
| `work/domain/WorkAggregateRepository.kt` | 保留其他领域用途；删除收藏 Paging 构建路径，收藏 UI 不再依赖。 |
| `favourites/ui/list/FavouritesListViewModel.kt` | 先退化为共享状态消费者，Phase 6 删除。 |
| `favourites/ui/container/FavouritesContainerViewModel.kt` | 成为唯一 screen-level state holder，持有 snapshot 与派生 IDs。 |
| `favourites/ui/compose/FavoritesHostScreen.kt` | 去掉 active child VM bridge；各 category page 消费共享快照。 |
| `favourites/ui/compose/FavoritesListScreen.kt` | 改静态 List；关闭 retained Paging snapshot；最终可并回 Host。 |
| `list/ui/ContentListViewModel.kt` | 不为收藏继续扩展 Paging 恢复；其他页面保持兼容。 |
| `list/ui/compose/AppContentListRoute.kt` | 优先复用静态 List 路径；若完整 Content 成为阻碍，提取可复用 Lazy/selection primitives。 |
| `list/ui/compose/RetainedPagingSnapshotController.kt` | 收藏停用；其他 Paging 页面按自身需求保留。 |
| `core/paging/BatchMappingPagingSource.kt` | 删除 favourite config/调用，保留其他调用方。 |

## 10. 测试策略

### 10.1 纯函数单元测试

`FavouriteLibraryDeriverTest` 至少覆盖：

- all/category membership 去重；
- category-specific pinned/created/updated 排序；
- alphabetical 正反序、rating、newest/oldest、progress、last read、new chapters、updated；
- Downloaded、SFW/NSFW、publication status、reading status、new chapters；
- multi/broken projection；
- tag/source；
- Space/group/source preset；
- 多过滤条件组合；
- 空快照、单项、10k 项；
- comparator 传递性和 entity ID tie-breaker；
- 输入不变时输出顺序确定。

### 10.2 Room integration tests

使用 in-memory `MangaDatabase`：

- 10k entities + 10k~30k memberships；
- 25 categories；
- 多 projection、preferred projection、dangling/broken projection；
- history、tracks、tags、downloads；
- 查询调用数量固定，无 N+1；
- 更新一条 history 后发射自洽完整快照；
- filter/sort 改变不触发 DAO；
- 读取不写 `entity_preferences`；
- 删除/恢复 category 后无 dangling IDs；
- snapshot replace backup 恢复结果与旧语义一致。

### 10.3 ViewModel tests

- Loading 与 Empty 明确区分；
- 第一份非空快照发布后不会因 filter 参数切换短暂变空；
- category 切换复用相同 `rowsByEntityId` 引用或等价快照；
- Filter Panel 不依赖 child VM；
- DB 更新期间保留上一份有效 state，直到新完整快照到达；
- snapshot error 显示错误但不清空上一份成功数据；
- selection 对 snapshot 删除做收敛。

### 10.4 Compose/instrumented tests

- GRID/LIST/DETAILED_LIST 渲染；
- category pager 切换与各自滚动位置；
- scroll -> details -> back，entity key 与 offset 恢复；
- details 期间 title/cover/progress 更新；
- details 期间前方插入、删除、排序改变、anchor 删除；
- process death 恢复 category/filter/sort/anchor；
- shared transition；
- 多选 action；
- main shell 与 standalone Activity；
- 旋转、分屏和大屏 grid span 改变。

### 10.5 旧测试处理

遵循 replace-don't-layer：

- 新 snapshot/deriver interface tests 覆盖后，删除只描述收藏 Paging 内部实现的测试；
- `BatchMappingPagingSourceTest` 中非收藏通用行为继续保留；
- `WorkPagingDaoTest` 中历史/更新行为继续保留；
- 收藏 SQL 语义测试迁移到 `FavouriteLibraryReadDaoTest`；
- retained snapshot 通用测试保留，收藏特定 policy 测试删除或改成静态列表恢复测试。

## 11. 性能预算与验收

### 11.1 测试数据

至少两组：

- 实际恢复备份：约 6.3k entity、25 categories；
- 合成压力库：10k entity、30k memberships、平均 2~3 projections、100k+ chapters、tracking/tags/download 分布。

必须在 release/R8、固定设备、固定数据库快照上重复至少 20 次，报告 median/P95。记录设备型号、Android 版本、刷新率、
温度和是否冷 cache。

### 11.2 暂定门槛

| 指标 | 目标 |
|---|---:|
| 6.3k warm snapshot 构建 P95 | <= 250 ms |
| 10k warm snapshot 构建 P95 | <= 500 ms |
| 10k Quick Filter 派生 P95 | <= 50 ms |
| 10k sort/group 派生 P95 | <= 100 ms |
| 详情返回恢复已有列表 | 首个可见 frame 不等待 DB；无 Loading/Empty 中间态 |
| 10k snapshot retained heap 增量 | <= 32 MiB，不含 Coil bitmap cache |
| 主线程 | 无 Room 查询、全量 mapping、filter 或 sort |
| 滚动 | Macrobenchmark jank 不差于 Komikku 同备份对照，且不差于当前 Kototoro 基线 |
| invalidation | 单条 progress/tracking 更新后 P95 <= 300 ms，期间旧快照继续可见 |

这些是首轮工程预算，不是永远不变的产品常量。若参考设备能力不同，可在 Phase 0 校准，但任何放宽必须记录设备数据和
用户可感知理由，不能为了让实现过关而静默修改。

### 11.3 必须采集的 trace sections

- `FavouriteLibrary.query.base`；
- `FavouriteLibrary.query.memberships`；
- `FavouriteLibrary.query.facets`；
- `FavouriteLibrary.snapshot.build`；
- `FavouriteLibrary.derive.visibility`；
- `FavouriteLibrary.derive.filters`；
- `FavouriteLibrary.derive.sort`；
- `FavouriteLibrary.ui.firstContent`；
- `FavouriteLibrary.return.firstFrame`。

Release 代码只保留低成本 tracing；逐 row 日志和对象 dump 必须在 Phase 8 删除。

## 12. 性能不达标时的优化顺序

严格按以下顺序处理，禁止直接恢复宽 `WorkAggregate` Paging：

1. 删除 row 中未被消费的字段；
2. 修复 query plan 和缺失索引；
3. 把 filter 限定到 ID/set primitives；
4. 缓存 source group/origin 与 tag blacklist flags；
5. 拆分 progress/tracking/download Flow，避免稳定 base rows 重建；
6. 合并 invalidation 高度重叠的 DAO queries；
7. 为真正昂贵且稳定的聚合建立 `favourite_library_summary` 物化 read table；
8. 在明确事务中增量维护 summary，并增加 rebuild/repair 命令；
9. 只有窄 row 全量读取仍超预算时，实现 Store 内部的轻量 Paging adapter。

如果进入 summary table：

- 它是可重建的 read model，不是新的身份真值；
- owner 仍是 entity/work 表；
- 写入必须与用户状态事务一致，或具备版本/dirty 标志；
- 备份不导出 summary；
- 恢复后重建；
- 必须有一致性检查和修复路径；
- 不允许 UI 直接写 summary。

## 13. 风险与对策

| 风险 | 对策 |
|---|---|
| 全量化宽模型导致 heap/GC 恶化 | 先拆窄 row；禁止完整 Content/WorkAggregate；32 MiB heap 门槛。 |
| 同一 entity 多 category 属性不同 | membership 独立建模；分类 slice 只存 ID + membership sort 字段。 |
| preferred/anchor 语义改变 | characterization + shadow ID/order comparison；确定性 fallback。 |
| broken projection 被静默过滤 | 保留 broken row 与整理入口；不让单项错误终止 Flow。 |
| tags/source filters 重新引入对象图 | facets 使用 IDs/names/flags；批量 groupBy。 |
| 详细列表需要 tags/summary | 限定 displayTags；按真实 UI 字段审计，不读取 description。 |
| 通用列表 Route 强迫完整 Content | 提取收藏专用 item ref/action contract，不牺牲 read model。 |
| Room 任一变化全量重算 | 先 benchmark；超预算再拆动态 map Flow，不提前上 patch protocol。 |
| 分类页复制 6k rows | Container 唯一 row map；category 只保存 entity IDs。 |
| 返回时合法排序变化 | 保留 semantic anchor；不恢复 stale Paging window。 |
| 进程死亡无法恢复完整快照 | DB 重建快照；SavedState 只存 query/category/anchor。 |
| 新旧链路并存太久 | shadow comparison 只限 Phase 4；Phase 5 后立即进入删除阶段。 |
| 其他页面误删 Paging 基础设施 | 删除前用 `rg` 确认调用方；收藏与历史/更新分别验收。 |

## 14. 回退策略

每个 Phase 的回退单位：

- Phase 1~3：新模块尚未接 UI，删除新增读取模块即可；
- Phase 4：关闭 debug shadow collection，旧 UI 不受影响；
- Phase 5~6：临时保留一个 debug-only 收藏数据源切换开关，最多跨一个稳定验证周期；
- Phase 7：只有 release 验收完成后才删除旧收藏 Paging；
- Phase 8 后发生性能回归：恢复的是“窄 row 的 Paging adapter”，不是宽 `WorkAggregate` 链路。

禁止把长期双实现作为保险。双实现会复制筛选、排序、identity 和 action 语义，违反 DRY，并使 bug 修复需要两次。

## 15. 工程原则

### KISS

- 一份完整快照、一份 row map、分类只存 ID；
- Quick Filters 是纯内存函数；
- 返回依赖 ViewModel 状态所有权，不维护页窗口交接状态机。

### YAGNI

- 首版不新增数据库表、触发器、第三方分页库或跨进程 snapshot 序列化；
- 不为假设中的 100k 收藏设计；
- 不提前实现 per-item diff/patch protocol；
- 不同时维护全量和 Paging 两套长期实现。

### DRY

- source/tag/category/projection facets 只在 Store 构建一次；
- entity identity 与 action routing 共享 `FavouriteItemRef`；
- 三种卡片复用同一 row，不重复加载领域对象；
- 旧筛选语义迁移后删除旧实现。

### SOLID

- 快照模块只负责收藏 read model 与一致快照；
- Container 只负责 screen state 和用户意图；
- Composable 只渲染 state、上报事件；
- 详情/下载/分享等完整实体解析在各自 UseCase 中按需完成；
- 依赖 Room concrete implementation，不增加只有一个 adapter 的假抽象。

## 16. Definition of Done

### 代码

- [ ] 收藏 DAO 不返回 PagingSource；
- [ ] 收藏 UI 不 import Paging；
- [ ] 收藏 UI/Container 不依赖 `WorkAggregate`；
- [ ] 单一 `FavouriteLibrarySnapshotStore.observe()`；
- [ ] 单一 Container-level screen state holder；
- [ ] category slices 只保存 entity IDs/membership；
- [ ] Quick Filters 和 sort 无 DAO 调用；
- [ ] item key 为 entity ID；
- [ ] 收藏关闭 retained Paging snapshot；
- [ ] 无读路径写库；
- [ ] 无新增第三方依赖。

### 验证

- [ ] `./gradlew :app:compileDebugKotlin`；
- [ ] `./gradlew :app:testDebugUnitTest --no-daemon`；
- [ ] 收藏 Room instrumented tests；
- [ ] `./gradlew :app:connectedDebugAndroidTest` 中相关用例；
- [ ] release/R8 Macrobenchmark；
- [ ] 6.3k 实际备份人工回归；
- [ ] 10k synthetic 压力测试；
- [ ] GRID/LIST/DETAILED_LIST；
- [ ] category/filter/sort/Space/preset；
- [ ] details/back、重排、anchor 删除、进程死亡；
- [ ] selection/actions；
- [ ] main shell/standalone Activity；
- [ ] 与 Komikku 同备份并列体验对照。

### 清理

- [ ] 删除 debug shadow comparison 与临时开关；
- [ ] 删除收藏旧 Paging/retained-window dead code 和实现型测试；
- [ ] 保留其他页面 Paging；
- [ ] 更新大型列表交接文档；
- [ ] 文档记录最终指标、已知限制和回退结论。

## 17. 推荐提交拆分

实际执行时保持窄提交，建议顺序：

1. `test(favourites): capture library list semantics and performance baseline`
2. `feat(favourites): add lightweight library read projections`
3. `feat(favourites): add observable library snapshot store`
4. `refactor(favourites): derive filters grouping and sorting in memory`
5. `refactor(favourites): move library state ownership to container`
6. `refactor(favourites): render full library snapshot without paging`
7. `refactor(favourites): route actions by entity item reference`
8. `refactor(favourites): remove per-category list view models`
9. `chore(favourites): remove favourites paging and retained snapshot path`
10. `test(favourites): add 10k release benchmark and return stability coverage`
11. `docs(favourites): record final migration metrics and decisions`

这里只定义拆分策略，不授权执行 `git commit`、创建分支或 push；任何 Git 写操作仍需用户明确要求。

## 18. 最终验收判断

本计划完成的标志不是“删掉 Paging3 import”，而是同时实现：

```text
轻量 read model
    + 完整列表状态所有权
    + 内存筛选/排序
    + entity stable identity
    + 可证明的返回稳定性
    + 10k release 性能预算
```

如果只把 `PagingData<WorkAggregate>` 改成 `List<WorkAggregate>`，实施失败；如果保留宽 SQL、逐项 enrichment 和分类级
重复快照，实施失败；如果为了复用通用 UI 重新把完整 Content 塞回 row，实施失败。

正确结果应比 Komikku 更进一步：采用它已经证明流畅的完整列表状态所有权，同时保留 Kototoro 的 entity identity、
stable key、Space 和语义锚点，并用真正窄的收藏 read model 消除当前超重条目查询。
