# Paging3 / LazyColumn 返回稳定性与替代方案研究

> 日期：2026-09-01  
> 范围：Kototoro 收藏、历史等 6,000+ 项 Compose 列表在进入详情后返回时的重载、闪跳与滚动锚点丢失  
> 方法：以 Android/AndroidX/SQLite 官方文档与源码、候选项目官方文档/源码/发布记录为主；GitHub
> stars 仅作为社区采用度的粗略快照，不代表质量。本文只做架构研究，不修改业务代码。

## 1. 结论先行

没有一个成熟、高采用度的单库能同时替换 Paging3、LazyColumn 和 Navigation，并自动保证“返回列表不重载、
不丢进度”。这个症状横跨三个独立状态域：

1. **数据窗口**：Pager/PagingData generation、已加载页、刷新键和 Room invalidation；
2. **UI 身份**：Lazy item stable key、contentType、LazyListState/LazyGridState 和语义锚点；
3. **生命周期所有权**：Navigation back-stack entry、ViewModelStore、SaveableStateHolder。

**结合当前实现与同备份实机对照后的明确判定**：用户将相同备份恢复到 Komikku 后，实际收藏列表依然极其流畅；
而 Komikku 收藏页使用完整 `Flow<List<...>>`，不使用 Paging3。这不是受控 benchmark，但足以把实施优先级从
“暂时保留 Paging”调整为：**优先建立专用轻量收藏 read model，并直接向 ViewModel-owned immutable List + Lazy
layout 对齐；Paging 只作为轻量模型在 10k release 指标仍未达标时的回退。** 当前宽 `WorkAggregate`、完整
Manga/History/Tracking、bindings、categories/tags 及 metadata/override enrichment 不能直接全量化；两条路径都不应
继续让列表消费完整领域聚合。

替换其中一层不会自动修复另外两层。对当前 Kototoro，推荐顺序是：

1. **先测并拆轻收藏 read model**。6000 条记录本身不足以证明必须分页；先区分“6000 个轻量 UI row”与
   “分批执行 6000 个重聚合、标签、来源和 override 解析”。Paging 只能延后后一种成本；
2. 以普通 `StateFlow<List<LibraryCardRow>>` 作为收藏页第一目标，ViewModel 保留基础快照并在内存派生 quick
   filter/group/sort；LazyColumn/LazyGrid 继续只合成可见项；
3. 保留 stable entity key、LazyListState 和 semantic anchor，但停止继续扩展收藏专用 retained Paging 状态机；
4. 若仍需有界窗口但 `LazyPagingItems` 的瞬时空窗口是唯一问题，试验 Paging 3.5 的
   `asItemSnapshotListFlow()`，把展示快照明确提升到 ViewModel，而不是引入第三方分页引擎；
5. 只有经 release benchmark/Perfetto 证明 Lazy layout 本身仍无法达标，才对单一屏幕 A/B 试验
   RecyclerView + PagingDataAdapter；
6. 只有产品需求明确要求任意页跳转、完整分页缓存跨进程序列化或跨平台实时流，才隔离试验 Paginator 或
   paging-kmp。它们目前都不足以支撑全项目替换 AndroidX Paging。

这与 Android 官方对 screen state 的边界一致：Navigation 会在 destination 仍在 back stack 时缓存其
ViewModel，使返回时已有数据可立即使用；UI element state 则由 `rememberLazyListState()`/saveable state
负责。[State holders and UI state](https://developer.android.com/topic/architecture/ui-layer/stateholders)

## 2. 附件观点核验

| 附件观点 | 核验 | 修正 |
|---|---|---|
| 没有“专门替换 Paging3/LazyColumn 并保证返回稳定”的高 star 单库 | **准确** | 症状跨数据、UI、导航三层，不能由单个分页库兜底。 |
| 优先 Paging3 + stable key + cachedIn + LazyListState + 不重建 Pager | **方向准确** | 当前项目已经实现其中大部分，不应再给泛化示例，而应核验现有 generation、anchor 坐标和 retained snapshot 交接。 |
| AndroidX `LazyPagingItems` 会用 `cachedIn` SharedFlow replay 初始化 | **准确** | 当前源码会读取 `SharedFlow.replayCache.firstOrNull()`，在开始 collection 前初始化 presenter。[LazyPagingItems.kt](https://github.com/androidx/androidx/blob/androidx-main/paging/paging-compose/src/commonMain/kotlin/androidx/paging/compose/LazyPagingItems.kt) |
| Tivi 是当前生产参考 | **已过时** | Tivi 约 6.7k stars，但仓库已归档，最后 push 为 2024-11-12；可读历史实现，不应作为 2026 年依赖或当前行为依据。[Tivi](https://github.com/chrisbanes/tivi) |
| paging-kmp 支持完整 cache serialization、bookmark | **错误/混淆** | 截至核验约 **6 stars**。README 主打 position-based random access、bounded cache、offline-first、SSE/WebSocket；未声明完整状态序列化或 bookmark。[paging-kmp](https://github.com/White-Wind-LLC/paging-kmp) |
| Paginator 支持 bookmark/jump/full cache serialization | **作者文档支持，但成熟度被高估** | 截至核验约 **107 stars**；这些能力确实在 README/源码中，但“优于 Paging3”的对比是项目作者自述，不是独立生产证据。[Paginator](https://github.com/jamal-wia/Paginator) |
| Paging3 只能保存“最后一个 PagingData”，不能做本地展示快照 | **过时** | Paging 3.5.0 新增 `asItemSnapshotListFlow()`，官方明确支持在 UI 外访问、组合、缓存、修改已加载数据。[Paging 3.5 release notes](https://developer.android.com/jetpack/androidx/releases/paging#3.5.0) |
| Navigation3 本身不是问题 | **大体准确，但需条件化** | 必须保证同一 route/content key、SaveableStateHolder、ViewModelStore decorator 和 back stack 未被替换。Kototoro 已配置这些机制。 |

## 3. 当前工作树事实

以下结论针对当前工作树，而非抽象示例：

- 版本：Paging **3.5.1**、Navigation3 **1.1.3**、Compose BOM **2026.08.00**。
- `FavouritesListViewModel` 由 filter/sort 等参数 `flatMapLatest` 创建新 Pager generation，最后
  `cachedIn(viewModelScope)`；主动 refresh 通过改变 `refreshTrigger` 合法地产生新 generation。
- `MainTopLevelNavDisplay` 和独立 `ContentListActivity` 均使用
  `rememberSaveableStateHolderNavEntryDecorator` 与 `rememberViewModelStoreNavEntryDecorator`；主导航每个
  top-level route 有独立 `rememberNavBackStack`。
- 列表同时为 grid/list/detailed-list 提供稳定 descriptor key 和 contentType；Paging item 读取区分
  `peek`（布局/key，不触发预取）与 `get`（展示，触发预取）。
- 已有 `RetainedPagingSnapshotController`：离开列表时保存最多 **192** 项的有界窗口、语义 item anchor、
  absolute/window-relative index、offset 和 list mode；返回过渡期间先显示旧窗口，再等 live generation
  围绕语义锚点接管。
- 已有 `RetainedPagingSnapshotTest`、`RetainedPagingSnapshotPolicyTest`，并有
  `BatchMappingPagingSourceTest` 覆盖 3,000 深度附近刷新。
- 当前未提交修改把收藏配置设为 `pageSize=64`、`prefetchDistance=64`、`enablePlaceholders=true`、
  `maxSize=384`。这是关键实验变量，不应把实验结果误归因于“Paging3 vs 替代库”。
- `BatchMappingPagingSource` 会把 Room 原始页映射成 UI rows；当一条原始记录可能被过滤、扩展或插入
  separator 时，raw key、mapped anchor 和 itemsBefore/itemsAfter 不是天然同一坐标系。当前未提交修改正在
 触及这一区域，因此它比“Pager 是否放进 ViewModel”更值得优先验证。

### 3.1 更本质的成本：不是 6000，而是每一条代表什么

当前收藏 `PagingSource` 不是读取一个窄表。一次 page 的主查询包含：

- 相关子查询从多分类收藏中选一个代表项；
- `entity_preferences`、完整 `manga`、`work_history`、按 entity 聚合的 `tracks`；
- 多组 `EXISTS`/`NOT EXISTS` 访问 `entity_binding`、`manga`、`local_index`、`manga_tags`；
- 为所有排序模式保留的一串 `CASE WHEN ... ORDER BY`；
- 返回 `MangaEntity` 的 title/URL/cover 之外，还包括 description、source_data、author、alt title 等完整字段。

主查询之后，每一页还会在 `buildFavouritePagingAggregates()` 中查询 bindings、categories，并按条件读取
所有 projection 的标签；构造 `WorkIdentity`、完整 `WorkAggregate` 与 projections 集合。随后
`mapFavouritePage()` 再执行：

- source group/origin group 解析与一轮 Kotlin 过滤；
- global tag blacklist 检查；
- entity metadata selection 和 manual override 两次批查询；
- `ContentListMapper` 生成三种展示模式的模型；
- progress/counter/projection suffix 与 item lookup 合并。

因此当前 `pageSize=64` 的真实含义不是“读取 64 个轻量卡片”，而是“对 64 个 entity 执行一套 read-side
聚合管线”。如果 mapping 过滤掉一部分结果，`BatchMappingPagingSource` 还会继续读取 raw pages 直到凑够
mapped load size。Paging 在这里既降低首屏峰值，也可能掩盖 read model 过重、让返回时重复 enrichment。

必须明确区分：

| 对象 | 典型成本 | 是否天然需要 Paging |
|---|---|---|
| 6000 个 `Long` ID | 约几十 KB 量级，加集合开销后仍很小 | 否 |
| 6000 个窄 `LibraryCardRow(entityId,title,cover,source,progress,counter,flags)` | 通常是数 MB 到十数 MB 量级，取决于 String 重用/长度；必须实测 | 未必 |
| 6000 个 `WorkAggregate` + projections + categories + tags + description/source_data + override | 对象图、字符串与查询/映射成本可能很高 | 分页只能控制峰值，不能修复模型过重 |
| 6000 张封面 bitmap | 极高，但应由 Coil 内存/磁盘 cache 和可见项生命周期控制，不应嵌入 UI model | 与 row 是否分页是两回事 |

所以“6000 不大”和“当前全量查询可能很贵”可以同时成立。真正应回答的是：**收藏列表渲染所需的最小
read model 是什么，以及获取 6000 个这种 read model 的成本是否达标。**

相关本地实现：

- `app/src/main/kotlin/org/skepsun/kototoro/main/ui/navigation3/MainTopLevelNavDisplay.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/AppContentListRoute.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/RetainedPagingSnapshotController.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/list/ui/ContentListViewModel.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/core/paging/BatchMappingPagingSource.kt`

## 4. 问题分层与可观测症状

### 4.1 导航与生命周期

正常的“List -> Details -> Back”应保留列表 back-stack entry。ViewModel 在 entry 留在 back stack 时应继续
存在，pop 后才清理。Navigation3 官方 recipes 也同时展示 saveable back stack、每 top-level 多 back
stack、SaveableStateHolder 和 ViewModel entry decorator。
[Nav3 recipes](https://github.com/android/nav3-recipes)

常见失效方式：

- 用 `replace`/清空再重建 list route 模拟返回；
- route data class 的 equality/content key 每次不同；
- list ViewModel 实际取自 Activity 或另一个 entry；
- tab 切换时替换整个 stack，而不是恢复原 stack；
- 返回 transition 未结束时提前清除 retained window。

这些问题换成 Voyager/RecyclerView/Paginator 后仍然存在，只是表现不同。

### 4.2 Paging generation 与缓存

`PagingData` 是一个 generation 的不可变快照容器；数据源变化需要 invalidate 并创建新 pair。
`cachedIn(viewModelScope)` 让同一 PagingData 可重复 collection，并把已加载数据的生命周期绑定到 scope。
[Load and display paged data](https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data)；
[PagingSource source](https://github.com/androidx/androidx/blob/androidx-main/paging/paging-common/src/commonMain/kotlin/androidx/paging/PagingSource.kt)

必须区分三种行为：

- `retry()`：只重试当前 generation 的失败 load；
- `LazyPagingItems.refresh()`：明确创建新 PagingData/PagingSource generation，适合 pull-to-refresh；
- Room table invalidation：repository/data 层变化驱动新 generation，不应额外在 `ON_RESUME` 再 refresh。

当前 AndroidX 还会在新的 `LazyPagingItems` 构造时从 `cachedIn` SharedFlow replay 同步取旧 PagingData，
因此“Compose 一回来一定先空列表”已不是当前实现的必然行为。

### 4.3 两套不同的 key

必须避免把两种 key 混为一谈：

- `PagingSource<Key, Value>` 的 **load/refresh key**：告诉数据源从哪一页/offset/cursor 加载；
- Lazy layout 的 **item key**：告诉 Compose 某个 UI item 的稳定身份。

`getRefreshKey(PagingState)` 错误会让新 generation 从错误页开始；item key 错误会让 Compose 无法把原来的
可见 item 与新列表关联。前者不是给 item ID，后者也不是给 page offset。

Paging 3.5.1 专门修复了 separator + placeholders 场景中 refresh 时 `anchorPosition` 过大的 bug，当前项目
已在这个版本上，说明旧 issue/旧 workaround 不应直接套用。
[Paging 3.5.1 release notes](https://developer.android.com/jetpack/androidx/releases/paging#3.5.1)

### 4.4 Lazy item identity、contentType 与尺寸

官方要求 lazy item key 稳定且唯一；提供 key 后，前方插入/删除时 lazy layout 会尽量保持同一个 key 为
首个可见 item。Paging Compose 的标准写法是 `lazyPagingItems.itemKey { it.id }`。
[Lazy lists](https://developer.android.com/develop/ui/compose/lists)

`contentType` 只帮助 Compose 在结构相同的 item 之间复用 composition，改善性能；它**不保存滚动状态、
不缓存页面、也不修复 identity**。当前 Kototoro 已为不同 list model 提供 descriptor contentType，这一层
不构成换库理由。

另一个常被忽略的变量是 item 高度：官方要求异步图片/placeholder 在加载前后保持尺寸，0 高度或尺寸突变
会造成额外加载和视觉位移。同一个 index+offset 在卡片高度改变后也不代表同一个视觉位置。

### 4.5 LazyListState 与语义锚点

`rememberLazyListState()` 使用 saveable state 保存 first visible index + offset，足以处理同一数据身份和稳定
顺序下的配置变化；但 index 是位置，不是业务身份。排序、过滤、separator 或前方增删后，`3782` 已可能是
另一项。

Kototoro 当前的“语义 item identity + absolute index hint + offset + 有界旧窗口”比只保存
`LazyListState.Saver` 更符合问题要求。进程死亡时则只应把小型 anchor DTO 放入 SavedStateHandle/Bundle，
不能序列化数百卡片或完整 PagingData；官方提醒 Bundle 只保存恢复 UI 所需的最小状态，避免
`TransactionTooLarge`。[Save UI state](https://developer.android.com/develop/ui/compose/state-saving)

### 4.6 Room 查询、OFFSET 与 keyset

Room 返回 `PagingSource<Int, Row>` 时由 Room paging integration 负责 LIMIT/OFFSET 与 invalidation。它适合
当前多条件动态列表，但深 offset 的代价不能忽略：SQLite 官方说明 `LIMIT x OFFSET y` 实际读取 `x+y` 行
再丢弃前 `y` 行，时间随 offset 增长。
[SQLite row-value scrolling windows](https://www.sqlite.org/rowvalue.html#scrolling_window_queries)

替代数据访问形态：

| 形态 | 优点 | 对 Kototoro 的限制 |
|---|---|---|
| Room PagingSource/LIMIT-OFFSET | Room 自动 invalidation；支持任意动态查询；与 Paging3 直接集成 | 深 offset 成本上升；refresh key 是位置坐标；复杂 mapping 会制造坐标转换问题 |
| 手工 window：`LIMIT :size OFFSET :offset` | 简单、完全可控；容易和普通 List/SnapshotStateList 组合 | 仍有深 offset 成本；需自写并发、去重、错误、刷新、前插、事务一致性和测试 |
| keyset/seek：`WHERE (sort,id) < (?,?) ORDER BY sort,id LIMIT n` | 有复合索引时深页成本稳定；插入对后续页偏移影响小 | 每种排序都需稳定、唯一 tie-breaker 与匹配索引；随机跳到第 3782 项/准确 scrollbar 困难；多 CASE 动态排序会显著复杂化 |
| 全量 `Flow<List<Row>>` | 逻辑最简单；返回时 ViewModel 可直接持有完整 list | 每次相关表变化可能重跑并物化全部结果；6,000+ 复杂 aggregate/mapping 和卡片模型的 CPU/内存成本需测量 |

当前收藏和历史 SQL 已用 `entity_id ASC` 作为最终 tie-breaker，这是稳定排序的良好基础；若未来改 keyset，
每个 `ListSortOrder` 都必须把其主排序字段与 `entity_id` 组成 cursor 和复合索引。不能用单一 `id < ?`
覆盖 rating/title/progress/updated 等所有顺序。

## 5. 继续使用 Paging3 的路线

### 5.1 当前首选：修复和验证现有实现

这不是“什么都不做”，而是把已有的复杂恢复机制收敛成可证明的契约：

1. 同一 list route 在 Details 返回前后保持相同 content key、ViewModel 实例和 paging Flow identity；
2. 只有 filter/sort/category/space/明确 refresh 事件创建新 generation，`RESUME` 不触发 refresh；
3. `BatchMappingPagingSource` 的 `prevKey`、`nextKey`、itemsBefore/itemsAfter、refresh key 全部明确处于 raw 还是
   mapped 坐标；
4. stable UI key 对 content item 使用 entity/work ID，对 separator/header 使用互不碰撞的命名空间；
5. retained snapshot 只在 Details navigation 捕获，在 live generation 已加载语义 anchor 且 return transition
   稳定后交接；
6. placeholder 开关、maxSize、separator 和 grid span phase 形成测试矩阵，而不是同时变化后凭肉眼判断。

优点：不新增依赖，复用项目已有 Navigation3/Room/Paging 测试和 192 项有界窗口；改造面最小。风险：
retained snapshot controller 已经是项目特有的复杂状态机，需要用不变量和集成测试防止继续膨胀。

### 5.2 Paging 3.5 `asItemSnapshotListFlow()`

3.5.0 起，官方允许在 UI 层外把 `Flow<PagingData>` 转成 `Flow<ItemSnapshotList>`，再组合、缓存、修改或作为
UI state 暴露；配套 `Pager.append/prepend/refresh/retry` 支持手动加载。
[Paging 3.5 release notes](https://developer.android.com/jetpack/androidx/releases/paging#3.5.0)

它为 Kototoro 提供一个官方中间路线：

```text
Room PagingSource -> Pager -> asItemSnapshotListFlow -> ViewModel UI snapshot
                                                   -> LazyColumn(items = snapshot.items)
```

收益：展示快照的所有权显式位于 ViewModel，可与当前 semantic anchor/retained window 合并，减少
`LazyPagingItems` 只存在于 composition 的边界问题。代价：必须自行在靠近边缘时调用 append/prepend，
处理 placeholder、load state 与 UI key；不能假设它比 `collectAsLazyPagingItems()` 更快。建议先做单屏原型和
状态机测试，不要全局迁移。

## 6. 普通 List / SnapshotStateList + 手工分页

`SnapshotStateList` 是可观察、可 snapshot、语义接近 ArrayList 的 MutableList；它不是磁盘缓存、分页器或
导航状态保存器。[SnapshotStateList API](https://developer.android.com/reference/kotlin/androidx/compose/runtime/snapshots/SnapshotStateList)

可选实现有两类：

- `StateFlow<Immutable List>`：ViewModel 每次发不可变新列表，最符合 UDF；
- ViewModel/state holder 内部维护 `SnapshotStateList`：元素级增删方便，但把 Compose runtime 状态类型带入
  presentation/domain 边界，并增加并发 snapshot 约束。

手工分页至少要重新实现：query identity、首刷/追加/前插、并发 load 串行化、重复请求合并、错误/重试、
刷新时旧数据保留、DB invalidation、capacity/eviction、semantic anchor、进程重建和测试。对当前 Room 本地
收藏库，这些正是 Paging3 已提供的能力。

适用条件：列表上限有严格小界限、全量查询和 mapping benchmark 足够便宜，或产品交互明确是“加载更多”而
非无限虚拟列表。对 6,000+ aggregate 默认不推荐直接全量替换；应先测全量 SQL、mapping、GC 和首次合成，
而不是只比较 Kotlin List 本身的内存。

### 6.1 推荐优先试验：专用轻量收藏 read model

当前 `WorkAggregate` 是面向领域整合的宽模型，适合详情、迁移、过滤语义或跨 projection 操作；列表卡片不应
必然承担整套对象图。可引入只服务收藏列表读取的深模块，例如：

```kotlin
data class FavouriteCardRow(
    val entityId: Long,
    val displayMangaId: Long,
    val title: String,
    val coverUrl: String?,
    val sourceName: String,
    val contentType: ContentType?,
    val isPinned: Boolean,
    val progress: Float?,
    val newChapterCount: Int,
    val projectionCount: Int,
)
```

字段应由真实 grid/list/detailed-list 渲染契约决定；上例不是最终 schema。关键是避免默认携带 description、
source_data、完整 tags、完整 categories、所有 projections 和 parser `Content` 对象。

Room 支持 DAO 直接返回任意列匹配的 POJO，也可用 `@RewriteQueriesToDropUnusedColumns` 移除返回对象不使用的列。
[Room Query](https://developer.android.com/reference/androidx/room/Query)；
[@RewriteQueriesToDropUnusedColumns](https://developer.android.com/reference/androidx/room/RewriteQueriesToDropUnusedColumns)

但当前 SQL 已显式列出宽 projection，单加 annotation 不会神奇地消除 join、相关子查询和后续 enrichment；
需要真正设计窄 SQL/read table。

### 6.2 四级读取管线

建议按成本从低到高拆成四级，而不是在一次 page mapping 中构造全部领域对象：

1. **Identity/order/filter row**：entityId、displayMangaId、稳定排序字段、SQL 可直接过滤的 flags；
2. **Card projection**：title、cover、source、contentType、progress、counter；
3. **按可见窗口 enrichment**：只有 LIST/DETAILED_LIST 或可见卡片需要的 subtitle、metadata override、tags；
4. **详情/操作时 aggregate**：完整 projections、categories、source_data、description、parser Content。

方案 A 是一次性加载 6000 个第 2 级 row；方案 B 是一次性加载 6000 个第 1 级 row，再按 Lazy layout 可见窗口
批量获取第 2/3 级。两者都比“先构造 6000 个 WorkAggregate”更有机会去掉 Paging，同时保持返回瞬时稳定。

按需 enrichment 必须**批量**（例如一批 64/128 IDs），不能在 item composable 中逐项访问 DAO，否则会制造
N+1 查询、滚动抖动和 composition side effect。缓存 key 至少包含 entityId、展示模式、metadata/override
版本；DB invalidation 时做精确失效或生成新不可变 map。

### 6.3 Filter pushdown 与预计算

当前已有一部分 filter pushdown：publication state、NSFW、downloaded、new chapters、exact source、tag、space
与 content type 在 SQL 内完成；随后又有 Kotlin 层的 preset、category IDs、source group/origin group、adult、
global blacklist 和 macro filter。优化顺序应是：

1. 将能由持久字段表达且选择性高的条件下推 SQL；
2. source group/origin group 若是确定映射，可在写入/同步时保存规范化枚举，而不是每行运行时解析；
3. projection count、new chapter count、display projection、content type 等高频展示字段可进入专用投影表；
4. tags/categories 采用规范化关联表 + `EXISTS` 做 filter，只有详细展示时才取 tag strings；
5. 对只影响详情的字段延迟加载。

SQLite 没有自动维护的 materialized view。所谓“物化投影”在本项目应是一个真实 Room entity/table，通过同一
事务内的 write path、触发器或明确 projector 更新。它需要版本/重建策略、回填 migration 和一致性测试，
不能把普通 SQL `VIEW` 误认为已预计算；普通 view 仍在读取时执行底层查询。

专用表可能包含：`entity_id` 主键、`display_manga_id`、规范化 type/source group、pinned、created/updated、
progress、tracking counters 和 projection_count。title/cover 是否复制要权衡一致性与 join 成本。只为高频
sort/filter 建匹配复合索引；Room 官方提醒索引会加速 SELECT 但减慢 INSERT/UPDATE，复合索引的列顺序应
匹配高频 WHERE/ORDER BY。
[Room Index](https://developer.android.com/reference/androidx/room/Index)

当前大 SQL 使用参数化 `CASE WHEN` 支持十余排序，通常难以让单个索引同时满足所有 ORDER BY。更可测的做法
是为高频排序生成少量独立、静态 SQL 方法，让查询计划能使用对应索引；不要为每个低频组合建立索引。

### 6.4 先证明查询计划，而不是先换分页框架

对真实 6k/10k 数据分别采集：

- `EXPLAIN QUERY PLAN`：是否 scan、临时 B-tree sort、相关子查询重复执行、覆盖索引命中；
- 主查询时间、附加批查询时间、WorkAggregate 构造时间、ContentListMapper 时间；
- 返回行 bytes、对象数、allocation、GC、ViewModel retained heap；
- toggle 每一种 quick filter 后的 P50/P95 首帧和完整结果时间。

复杂查询的 planner 还依赖统计信息。SQLite 官方建议长期连接在合适时机运行 `PRAGMA optimize`，schema/index
变化后尤其应更新统计；这应先作为只读诊断/独立数据库维护决策验证，不能未经测试直接塞进热路径。
[SQLite ANALYZE/PRAGMA optimize](https://www.sqlite.org/lang_analyze.html)

### 6.5 去掉 Paging 的明确门槛

收藏页满足以下条件时，普通 `StateFlow<List<FavouriteCardRow>> + Lazy layout` 是合理主线候选：

- 目标上限（建议至少 10k，而非只测当前 6k）全量窄 SQL + mapping 的 P95 在产品预算内；
- retained 6000/10k card rows 的 heap 峰值可接受，无 description/source_data/tags/projections 对象图；
- quick-filter/sort 的 P95 可接受，或能在后台计算并在切换时保留旧 list；
- DB 更新能用 immutable list/diff 或小粒度 projector 更新，不频繁全量重建宽模型；
- kill-process 只保存 query + semantic anchor，不保存 6000 rows；重建时间达标；
- grid/list/detailed-list 切换不要求重新做领域 enrichment。

以下任一成立则保留 Paging 更合理：

- 收藏量没有可接受上限，可能达到数万/数十万；
- 即使是窄、覆盖索引良好的 read model，全量读取/排序仍超预算；
- 远程源必须按页加载，或本地 DB 不是完整 source of truth；
- page window 显著降低 heap/GC，且 refresh/anchor 契约已经稳定；
- 需要快速首屏，而完整结果可以渐进出现，产品接受分页窗口语义。

因此决策不应是“6000 > 某阈值所以 Paging”，而应是两条实测曲线的交点：

```text
全量轻量 read model：固定返回稳定性简单度 + 随 N 增长的 query/heap 成本
Paging 轻量 read model：窗口 heap 优势 + generation/anchor/placeholder 状态复杂度
```

不论哪条胜出，**轻量 read model 都先于分页框架选择**。如果 row 仍然是重 WorkAggregate，两个方案都只是在
不同时间支付同一笔高成本。

## 7. 数据层候选

### 7.1 Store5（原 Dropbox Store，现 Mobile Native Foundation）

Store 是 network、memory cache 和 local SourceOfTruth 之间的 typed repository；它能让返回页面先得到
本地/内存数据，并统一 offline-first/刷新规则。
[Store foundations](https://store.mobilenativefoundation.org/docs/concepts/store5/overview)；
[Store repository](https://github.com/MobileNativeFoundation/Store)

它**不负责** Lazy layout、scroll state 或 Navigation ownership。Kototoro 已经以 Room 为收藏/历史的
source of truth，引入 Store 只为修复本地列表返回稳定会形成重叠抽象。

成熟度需精确表述：Store5 核心已有稳定 5.0.0；包含 paging 支持的 5.1 线截至 2026-08-29 最新仍是
`5.1.0-alpha11`。官方 quickstart 展示 `5.1.0`，与 Maven 实际发布状态有偏差，是集成审计信号。
[Maven metadata](https://repo1.maven.org/maven2/org/mobilenativefoundation/store/store5/maven-metadata.xml)

适合：未来统一 REST/多源网络缓存和本地 SSOT。迁移成本中高；对当前症状的直接收益低。

### 7.2 Cash App Molecule

Molecule 用 Compose runtime 构建 `Flow`/`StateFlow` presenter。它能把复杂 UI state production 集中到一个
可测试 presenter，但不加载分页、不虚拟化 item、不保存滚动或导航状态。
[Molecule](https://github.com/cashapp/molecule)

适合：重构 presentation state machine。成本中等，并把 Compose compiler/runtime 引入状态生产层；不是
Paging3/LazyColumn 替代品，也不应与 Store5 拼成“返回稳定套件”。

### 7.3 Apollo Kotlin normalized cache

Apollo normalized cache 支持内存/SQLite cache、按 cache ID 去重对象、watch query，并可把 Relay 风格分页
合并到同一字段；非 Relay 分页需要配置或手工操作 ApolloStore。
[Normalized cache](https://www.apollographql.com/docs/kotlin/caching/normalized-cache)；
[Pagination support](https://www.apollographql.com/docs/kotlin/caching/pagination/home)

Kototoro 的主要数据来源是 Room、本地文件、REST/HTML/多生态 parser，不是统一 GraphQL schema。因此采用
Apollo 意味着数据协议/后端级重构，不能作为通用 Paging3 替代。只有将来某个独立 GraphQL tracker/feed
需要 normalized object cache 时才局部适用。

## 8. 导航与 retained-state 候选

| 方案 | 官方能力 | 对当前问题的实际作用 | 迁移成本/风险 |
|---|---|---|---|
| Navigation3（现有） | saveable back stack、SaveableStateHolder、entry ViewModelStore、多 back stack recipes | 当前已具备正确所有权机制；应验证 content key 和 snapshot 交接 | **低**；无需换框架 |
| Decompose | child configuration 自动 StateKeeper 保存；back-stack component 有独立 lifecycle；InstanceKeeper 可保留实例 | 能把 screen state lifetime 建模得更显式 | **很高**；替换导航/组件架构，仍需 Paging 和 LazyListState。[Docs](https://arkivanov.github.io/Decompose/navigation/overview/) |
| Voyager | Navigator 管 lifecycle、back、state restoration、nested navigation；CurrentScreen 使用 SaveableStateHolder | 能保存 screen subtree/state | **高**；仍不解决 refresh key/Paging generation；1.0.1 后发布节奏需审计。[Docs](https://voyager.adriel.cafe/navigation/) |
| PreCompose | KMP Navigation + ViewModel/Lifecycle + SavedStateHolder | 对 KMP 可统一 API | **高**；最新稳定 1.6.2 发布于 2024-09，当前 AndroidX/Hilt 项目收益有限。[Repo](https://github.com/Tlaster/PreCompose) |

替换导航框架只有在项目整体需要 KMP component model 或 Navigation3 无法表达的 retained lifetime 时才合理。
单为列表返回稳定迁移，会同时改动 route、Hilt ViewModel scope、transition、shared element、多 top-level stack，
回归面远大于收益。

## 9. RecyclerView / ListAdapter 回退

RecyclerView 是这里唯一真正替代 LazyColumn/LazyGrid 的成熟 Android UI 列表。可选组合：

- RecyclerView + `PagingDataAdapter`：只替换渲染层，保留 Paging3；
- RecyclerView + `ListAdapter`：普通 List，后台 DiffUtil；
- `AndroidView` 嵌入现有 Compose screen，或让 ViewHolder 承载 ComposeView。

`ListAdapter` 在后台计算 list diff；RecyclerView 还提供
`StateRestorationPolicy.PREVENT_WHEN_EMPTY`，让 adapter 非空后再把保存状态交给 LayoutManager，直接处理
“异步数据初始为空导致恢复过早”这一类问题。
[ListAdapter](https://developer.android.com/reference/androidx/recyclerview/widget/ListAdapter)；
[StateRestorationPolicy](https://developer.android.com/reference/androidx/recyclerview/widget/RecyclerView.Adapter.StateRestorationPolicy)

但它不会修复错误的新 Pager generation、Room invalidation 或 route/ViewModel 重建。混合架构还会增加
Compose/View interop、shared element、selection、glass UI、insets 和测试成本。官方支持渐进式互操作，但对
Compose-only app 仍建议 Compose-only 架构。
[Views in Compose](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)

采用门槛：release/R8 下 Macrobenchmark 与 Perfetto 明确证明，同一数据/图片/动画配置下 RecyclerView 的
frame time、GC 或内存显著更好，且 Paging/nav 状态已排除。否则它只是把 bug 搬到 adapter restoration。

## 10. Compose Multiplatform 分页候选

### 10.1 官方 AndroidX Paging KMP：默认基线

Paging 3.3 起把 common/compose API 移到 KMP；3.4 扩展到 JVM/Desktop、Native 和 Web。Cash App 的
`multiplatform-paging` 工作已 upstream，原仓库随后归档，因此未来 KMP 首选仍应是 AndroidX Paging，
而不是迁移到旧 Cash App fork。
[Paging releases](https://developer.android.com/jetpack/androidx/releases/paging)；
[archived fork](https://github.com/cashapp/multiplatform-paging)

### 10.2 White-Wind-LLC/paging-kmp

定位：absolute position/index、随机 jump、sparse/bounded window、offline mediator、实验性 SSE/WebSocket
实时 page window。约 6 stars，2025-08 创建，API/生产证据远弱于 AndroidX。

与 Kototoro 的匹配点是“按绝对位置跳到深列表”和 bounded cache；不匹配点是当前主要数据来自 Room，且
并无实时 SSE/WebSocket 页流。附件所说的完整序列化/bookmark 不属于其 README 能力。

迁移成本很高：Room PagingSource、BatchMappingPagingSource、LazyPagingItems、load states 和测试工具全部
重写。只适合 future KMP/实时流 spike，不适合当前主线。

### 10.3 jamal-wia/Paginator

定位：offset/cursor 两套 paginator、bidirectional、jump/bookmark、page cache、element CRUD、
kotlinx.serialization 完整 cache、Compose 和 RecyclerView binding。约 107 stars。

它的确比 AndroidX 的默认 UI 集成更强调显式 page cache 和 process-death serialization；但风险包括：

- 与 Paging3 的能力表是作者自述；尤其“Paging3 只靠 cachedIn 保存最后 PagingData”忽略 3.5 的
  `asItemSnapshotListFlow` 和应用层 anchor persistence；
- 完整缓存放入 Android saved state 不可行，仍需文件/DB 持久化和版本/查询 identity 校验；
- 项目会从 Room 自动 PagingSource/invalidation 迁移到自管 cache coherency；
- 社区、测试矩阵、兼容历史和问题发现率远小于 AndroidX。

只有“任意 page jump + 可持久化 page cache”成为明确产品需求，且能在隔离模块通过 kill-process、DB mutation、
过滤/排序切换和 10k 项压力测试，才值得进一步评估。

## 11. 方案对照

| 方案 | 解决数据重载 | 解决滚动锚点 | 解决导航所有权 | 迁移成本 | 当前建议 |
|---|---:|---:|---:|---:|---|
| 轻量收藏 read model + 全量不可变 List | ViewModel 存活时是 | 简单，stable key + semantic anchor | 沿用 Nav3 | 中 | **当前实施主线** |
| 轻量 read model + 现有 Paging3/retained snapshot | 是 | 是，项目已有语义锚点 | 沿用已正确配置的 Nav3 | 中 | 10k 全量指标未达标时回退 |
| Paging 3.5 `asItemSnapshotListFlow` | 是，展示快照更显式 | 需复用现有 anchor | 否 | 中 | 单屏原型 |
| 全量宽 WorkAggregate List | ViewModel 存活时是 | 需自写 | 否 | 中-高 | 不推荐；先拆窄投影 |
| 手工 Room window/keyset | 自管 | 自管，keyset 更偏 semantic cursor | 否 | 高 | 特定排序/深页 SQL 优化 |
| Store5 | 强化 repository/cache | 否 | 否 | 中-高 | 网络数据层需求出现时评估 |
| Molecule | 只组织 UI state | 否 | 否 | 中 | 非分页替代 |
| Apollo cache | GraphQL 场景是 | 否 | 否 | 极高 | 仅局部 GraphQL |
| Decompose/Voyager/PreCompose | 间接，保留 state holder | 仍需保存 | 是 | 很高 | 不为本问题单独迁移 |
| RecyclerView + PagingDataAdapter | 保留 Paging3 | 有 adapter/LayoutManager restoration | 否 | 高 | benchmark 证明 Lazy UI 是瓶颈后 A/B |
| paging-kmp | bounded positional cache | 支持 absolute jump | 否 | 很高 | 暂不主线采用 |
| Paginator | 显式 page cache/serialization | bookmark/jump | 否 | 很高 | 仅产品需求驱动 spike |

## 12. 推荐分阶段路线

### Phase 0：建立可重复基线并拆账

- 固定当前未提交 placeholder/maxSize/mapping 变更，避免交叉变量；
- 建立 6k/10k synthetic Room 数据；覆盖 GRID/LIST/DETAILED_LIST；
- 记录返回前后：route content key、ViewModel identity、PagingData generation、refresh 原因、anchor entityId、
  raw/mapped index、itemCount、firstVisible index/offset；
- 分别计时主 SQL、binding/category/tag 批查询、WorkAggregate 构造、override/metadata 查询、ContentListMapper；
- 记录每页 raw rows、mapped rows、为补齐 mapped load 又加载的 raw page 数；
- release/R8 运行 Macrobenchmark：List -> scroll 3000 -> Details -> mutate/no-mutate -> Back；分别测试
  不刷新、DB reorder、删除 anchor、切换过滤、进程死亡。

### Phase 1：建立专用轻量 read model

- 从三种卡片真实渲染契约反推 `FavouriteCardRow` 最小字段；
- 用独立 DAO projection 替代完整 MangaEntity/WorkAggregate；
- 将高选择性 quick filters 下推 SQL，将 source group/projection count 等稳定派生值预计算或窄表化；
- 对 6k/10k 采集全量窄 rows 的 query、mapping、heap、filter/sort 指标；
- 先接入全量 List；保留轻量 Paging adapter 的设计可能性，但不为尚未出现的失败提前维护两套生产链路。

### Phase 2：以全量快照替换收藏 Paging

- ViewModel 暴露不可变基础快照和派生后的 ID 顺序；
- 保留 stable keys、LazyListState 和 semantic anchor，删除收藏页特有的 Paging/retained-window 复杂度；
- 以 10k release benchmark 作为验收而不是实施前置许可；若失败，优先优化 read table 与 invalidation；
- 只有轻量 read model 仍无法达标时，才恢复 Paging adapter，且不再每页构造完整 aggregate。

### Phase 3：收敛保留下来的 Paging3 契约

- 给 `BatchMappingPagingSource` 写明 raw/mapped 坐标不变量并扩展 property/integration tests；
- 分离“返回详情”和“用户 refresh”，确保 lifecycle resume 不产生 generation；
- 验证 placeholders on/off、separator、maxSize page drop 组合；
- 语义 anchor 消失时明确策略：最近邻、index hint 或顶部，不要无限保留旧快照；
- 对 snapshot handoff 加 trace，证明旧窗口只跨返回过渡存在且最终释放。

### Phase 4：官方 API 原型

- 在收藏或历史二选一，用 `asItemSnapshotListFlow()` 建立 ViewModel-owned display snapshot 原型；
- 复用同一 item descriptor 和 semantic anchor；
- 对比现有 `LazyPagingItems + retained snapshot` 的代码量、内存、返回 frame、load correctness；
- 只有指标和状态机复杂度明显改善才迁移另一屏。

### Phase 5：有证据的局部替代

- 若 SQL 深 offset 是瓶颈：对单一高频排序设计复合索引 + keyset cursor prototype；
- 若 Lazy layout 是瓶颈：同一 screen 做 RecyclerView + PagingDataAdapter A/B；
- 若产品要求跨进程完整 page cache/任意 jump：隔离 Paginator prototype，并把 cache 放持久层而非 Bundle；
- 若产品要求 KMP 实时 position stream：再评估 paging-kmp，同时以官方 AndroidX Paging KMP 为基线。

### Phase 6：架构迁移门槛

只有 Navigation3 的 entry ownership 在最小复现中被证实无法满足产品场景，且官方 recipes/新版本没有修复
路径，才评估 Decompose/Voyager。导航替换必须作为独立 ADR，不与分页引擎替换同时进行。

## 13. 风险清单与验收标准

### 主要风险

- placeholder absolute count 与 batch mapping 输出数量不一致；
- separator/header 让 layout index、paging index、raw DB offset 三套坐标混用；
- `maxSize` page drop 后 anchor 只存在于 retained window；
- shared transition/pop transition 期间 live generation 提前接管导致闪跳；
- DB mutation 更新 `updated_at`，排序项合法移动，被误报为“丢位置”；
- 保存完整 list/cache 进入 Bundle 导致 TransactionTooLarge；
- debug build 的 Compose 性能被误当 release 结论。官方明确 Lazy layout 性能应在 release/R8 下测量。
  [Compose lazy list performance](https://developer.android.com/develop/ui/compose/lists#measuring-performance)

### 验收标准

1. 无数据变化：Back 第一帧显示返回前同一 semantic item，offset 误差不超过一个像素/平台舍入范围，无全屏
   loading/empty flash；
2. 非排序字段变化：anchor identity 和视觉 offset 保持；
3. 排序字段变化：同一 semantic item 可二次校正，或按产品规则展示合法新位置，不出现随机页；
4. anchor 删除：确定性退化到邻近 item/index hint，不无限显示 stale snapshot；
5. 进程死亡：恢复 query/filter/sort、semantic anchor + offset，不要求恢复完整内存 page cache；
6. 10k 项 release benchmark：返回路径无重复网络/全量 DB load，P95 frame、内存峰值和 GC 在设定预算内；
7. 所有 snapshot/controller cache 在 pop 或完成 handoff 后可释放，无 ViewModel/Activity 泄漏。

## 14. 最终建议

当前最合理的目标不是先找一个 Paging3/LazyColumn 替代库，而是先把收藏列表从宽领域聚合中解耦，并默认走
Komikku 已验证的完整列表状态所有权：

```text
write/domain model -> dedicated lightweight favourite read model
                                      `-> ViewModel-owned full immutable List

                              Paging adapter only if release budget fails

NavEntry owns ViewModel -> stable semantic item identity -> Lazy viewport restoration
```

这条路线避免让 Paging 掩盖昂贵 read model。相同备份在 Komikku 上的实际表现已经提供了足够强的方向性证据；
10k benchmark 用于验收和发现 read model/invalidation 问题，而不是继续保留复杂 Paging 状态机的前置理由。去掉收藏页
Paging 能删除 generation、placeholder、mapped/raw key 与 retained-window 交接复杂度，符合 KISS；若轻量全量路径最终
仍不达标，Paging 回退也能受益于窄 projection。read projection、item identity 与 anchor 恢复应形成各自清晰的
interface，而不是让列表 UI 直接依赖完整 `WorkAggregate`。

第三方候选应保留为需求触发的 spike：Store5 对网络 SSOT，Apollo 对 GraphQL，Decompose/Voyager 对整体 KMP
导航，RecyclerView 对已证实的 Lazy UI 瓶颈，Paginator 对完整 page-cache/jump，paging-kmp 对实时 position
stream。它们都不是当前返回稳定性问题的低风险直替。
