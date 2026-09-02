# Komikku 收藏列表架构研究

> 日期：2026-09-01  
> 研究对象：`../komikku`  
> 上游：`https://github.com/komikku-app/komikku`，本地分支：`master`  
> 锁定提交：`936e25bf99af29e059f06c4ad613ab62df9ae53e`（2026-07-18，`fix(chapter-hash): Fix preference migration for existing users (#1800)`）  
> 方法：只核对该提交的一手源码；未以 README 宣传、历史 Tachiyomi/Mihon 实现或第三方文章替代当前代码。文中的
> “Presenter/ViewModel”对应当前使用的 Voyager `LibraryScreenModel`，仓库里没有另一个收藏 Presenter。

## 1. 结论先行

Komikku 的收藏页证明了一条现实可行的路线：**收藏列表可以完全不使用 Paging3，而由页面级状态持有完整列表，
Compose LazyColumn/LazyVerticalGrid 只负责可见项虚拟化。** 当前链路是：

```text
SQLite / SQLDelight libraryView
    -> MangaRepositoryImpl.getLibraryMangaAsFlow()
    -> GetLibraryManga.subscribe(): Flow<List<LibraryManga>>
    -> LibraryScreenModel.getFavoritesFlow(): Flow<List<LibraryItem>>
    -> 内存 filter / search / group / sort
    -> State.libraryData + groupedFavorites
    -> HorizontalPager
    -> FastScrollLazyColumn / FastScrollLazyVerticalGrid
```

这对 Kototoro 最有价值的启示不是“把 Paging3 换成 Komikku 的库”——Komikku 没有使用替代分页库——而是：

1. **数据总量与 UI 虚拟化是两个问题。** Komikku 一次持有全部收藏，Lazy 容器仍只组合可见项；
2. **完整列表应归页面级状态所有。** 详情 push 后，列表数据仍由 back-stack 上的 `LibraryScreenModel` 持有，返回
   不需要重新拼一个分页窗口；
3. **筛选、排序、分组可以在几千条内存列表上完成。** 这消除了 raw offset、mapped index、Paging anchor 三套坐标；
4. **Komikku 并不是轻量 read model 的范本。** `libraryView` 使用 `M.*`，每项构造完整 `Manga`，还聚合章节、历史、
   分类，并另取全部 tracks；搜索 metadata 和 merged downloads 路径甚至明确存在 N+1。因此它只能证明“非 Paging
   全量架构能工作”，不能证明当前 Kototoro 的宽 `WorkAggregate` 可以安全全量化；
5. **Komikku 的返回稳定性主要来自状态所有权，而不是更强的 Lazy 状态协议。** 收藏 list/grid 没有显式 stable
   item key，也没有语义 anchor 或 ViewModel-owned scroll position；这部分弱于 Kototoro 当前恢复设计，不应照搬。

用户补充了一个比纯源码对比更贴近本项目的数据点：**Komikku 恢复与 Kototoro 相同的备份后，收藏列表在实际使用中
依然极其流畅。** 这不是受控 benchmark，不能替代后续指标测试，但它使决策优先级发生变化：不再把 Paging 与全量
快照视为同等候选，而是**优先向 Komikku 的完整列表状态所有权对齐，将 Paging 保留为轻量 read model 仍未达标时的
回退方案**。

具体仍应先把收藏卡片拆成窄 `FavouriteCardRow`，但第一实现目标应直接是
`StateFlow<List<FavouriteCardRow>>`。Komikku 为全量方案提供了生产代码和同备份实机表现两方面的证据，也反向证明了
真正需要治理的是**查询宽度、派生数据和更新粒度**，而不是继续为 Paging3 增加恢复状态机。

## 2. SQL 读模型：一次性返回全部收藏

### 2.1 `libraryView` 的实际宽度

`libraryView` 是普通 SQLite `CREATE VIEW`，不是物化视图或增量维护的 summary table；查询时仍需由 SQLite 展开并执行
其定义。它也不是列表专用窄投影：先选择 `M.*`，即 `mangas` 的全部列，再附加章节与分类聚合
（[`libraryView.sq:2-15`](../../../komikku/data/src/main/sqldelight/tachiyomi/view/libraryView.sq)）。`mangas` 本身包含
artist、author、description、genre、notes、memo、更新策略和同步版本等列表卡片通常不必全部持有的字段
（[`mangas.sq:7-35`](../../../komikku/data/src/main/sqldelight/tachiyomi/data/mangas.sq)）。

普通来源的章节子查询按 `manga_id` 全量聚合：

- `count(*)` 得出总章节数；
- `sum(read)`、`sum(bookmark)` 和 bookmarked-read count；
- `max(date_upload)`、`max(date_fetch)`；
- 通过 `history` 得到 `max(last_read)`；
- 通过 `excluded_scanlators` 排除指定 scanlator。

证据见 [`libraryView.sq:16-44`](../../../komikku/data/src/main/sqldelight/tachiyomi/view/libraryView.sq)。合并来源
`source = 6969` 又用 `UNION` 执行一套针对 `merged.merge_id` 的章节/历史聚合
（[`libraryView.sq:46-97`](../../../komikku/data/src/main/sqldelight/tachiyomi/view/libraryView.sq)）。最终收藏查询只是：

```sql
SELECT *
FROM libraryView
WHERE libraryView.favorite = 1;
```

没有 `LIMIT`、`OFFSET` 或 cursor，见
[`libraryView.sq:99-102`](../../../komikku/data/src/main/sqldelight/tachiyomi/view/libraryView.sq)。因此数据库每次发射的是完整
收藏结果，不是窗口。

还需注意 SQL 文本把 `favorite = 1` 放在 view 外层，两个章节聚合子查询本身没有收藏条件。SQLite planner 可能做
谓词下推或其他改写，但仅凭源码不能断言它只扫描 favorite 的相关行；要判断真实成本必须对目标数据分布执行
`EXPLAIN QUERY PLAN` 与计时，不能把 `CREATE VIEW` 误认为已经物化的缓存结果。

### 2.2 categories、history、tracks 的位置

- **分类成员关系**已经被 `libraryView` 的 `group_concat(category_id)` 压成每本漫画一个字符串
  （[`libraryView.sq:39-44`](../../../komikku/data/src/main/sqldelight/tachiyomi/view/libraryView.sq)）；mapper 再执行
  `split(",").map(String::toLong)`，见
  [`MangaMapper.kt:112-155`](../../../komikku/data/src/main/java/tachiyomi/data/manga/MangaMapper.kt)。无分类时视图回退为
  字符串 `"0"`，所以领域模型得到系统分类 ID `0`。
- **分类定义**不与主查询 join；`GetCategories.subscribe()` 另订阅完整 `Flow<List<Category>>`
  （[`GetCategories.kt:7-17`](../../../komikku/domain/src/main/java/tachiyomi/domain/category/interactor/GetCategories.kt)、
  [`CategoryRepositoryImpl.kt:18-24`](../../../komikku/data/src/main/java/tachiyomi/data/category/CategoryRepositoryImpl.kt)）。SQL 只读
  id/name/order/flags/hidden 并按 `sort` 排序
  （[`categories.sq:26-42`](../../../komikku/data/src/main/sqldelight/tachiyomi/data/categories.sq)）。
- **历史**不作为对象图附着到每项；主视图只通过 chapters join history 后取 `max(history.last_read)`
  （[`libraryView.sq:17-36`](../../../komikku/data/src/main/sqldelight/tachiyomi/view/libraryView.sq)）。`history.chapter_id` 有索引，
  见 [`history.sq:3-13`](../../../komikku/data/src/main/sqldelight/tachiyomi/data/history.sq)。
- **tracks**完全不在 `libraryView`。`GetTracksPerManga` 订阅 `SELECT * FROM manga_sync` 的全量列表，再在 Kotlin 中
  `groupBy(mangaId)` 并过滤 unfollowed track
  （[`manga_sync.sq:30-32`](../../../komikku/data/src/main/sqldelight/tachiyomi/data/manga_sync.sq)、
  [`GetTracksPerManga.kt:8-21`](../../../komikku/domain/src/main/java/tachiyomi/domain/track/interactor/GetTracksPerManga.kt)）。

这是一种“有限 SQL 聚合 + 多个全量辅助 Flow + 内存组合”的读侧，而不是单条超宽 SQL 把所有关系都实体化。
它避免为每本漫画构造完整 chapters/history/tracks 列表，但仍会扫描聚合相关表并物化完整 `Manga`。

### 2.3 索引与复杂度边界

源码至少提供了这些关键索引：favorite partial index、chapters(manga_id)、history(chapter_id)、
mangas_categories(manga_id/category_id)、manga_sync(sync_id, remote_id, manga_id)
（[`mangas.sq:37-39`](../../../komikku/data/src/main/sqldelight/tachiyomi/data/mangas.sq)、
[`chapters.sq:25-27`](../../../komikku/data/src/main/sqldelight/tachiyomi/data/chapters.sq)、
[`history.sq:12-13`](../../../komikku/data/src/main/sqldelight/tachiyomi/data/history.sq)、
[`mangas_categories.sq:11-12`](../../../komikku/data/src/main/sqldelight/tachiyomi/data/mangas_categories.sq)、
[`manga_sync.sq:23-24`](../../../komikku/data/src/main/sqldelight/tachiyomi/data/manga_sync.sq)）。

但当前仓库没有 6,000/10,000 收藏的 benchmark、heap 上限或查询时延门槛。因此不能从“代码采用全量 List”推导出
“任意 6,000 条宽模型一定足够快”。尤其 `libraryView` 的章节聚合成本更接近“所有相关章节/历史行数”，而不是
仅由收藏本数决定。

## 3. Repository -> Interactor -> ScreenModel -> Compose 的完整数据流

### 3.1 数据与领域层

`MangaRepositoryImpl` 有同步全量读取与响应式全量读取两个入口：

- `getLibraryManga()` -> `awaitList { libraryViewQueries.library(...) }`；
- `getLibraryMangaAsFlow()` -> `subscribeToList { libraryViewQueries.library(...) }`。

见 [`MangaRepositoryImpl.kt:58-64`](../../../komikku/data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt)。
`AndroidDatabaseHandler.subscribeToList()` 使用 SQLDelight `Query.asFlow().mapToList(IO dispatcher)`，每次都得到一个完整
Kotlin `List<T>`，见
[`AndroidDatabaseHandler.kt:73-75`](../../../komikku/data/src/main/java/tachiyomi/data/AndroidDatabaseHandler.kt)。

`GetLibraryManga` 不再分页或缓存，只透传 `Flow<List<LibraryManga>>`，并对特定 NPE 做延迟重试、对异常做日志处理
（[`GetLibraryManga.kt:13-33`](../../../komikku/domain/src/main/java/tachiyomi/domain/manga/interactor/GetLibraryManga.kt)）。

`MangaMapper.mapLibraryManga()` 的结果仍然包含完整 `Manga`，外加 categories、章节计数、bookmark 计数、上传/抓取/
最后阅读时间，见
[`MangaMapper.kt:72-155`](../../../komikku/data/src/main/java/tachiyomi/data/manga/MangaMapper.kt) 与
[`LibraryManga.kt:5-18`](../../../komikku/domain/src/main/java/tachiyomi/domain/library/model/LibraryManga.kt)。

### 3.2 ScreenModel 第一阶段：全量 enrichment 与筛选

`LibraryScreenModel` 是 Voyager 的 `StateScreenModel<State>`，当前没有单独的 Presenter/ViewModel 层
（[`LibraryScreenModel.kt:132-165`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)）。

第一条 combine 管线同时观察：

- 搜索词（`distinctUntilChanged + debounce`）；
- categories 全量 Flow；
- favorites 全量 Flow；
- tracks 全量 map；
- tracking filter；
- include/exclude categories；
- badge、quick filter 等 preferences。

它先 `applyFilters()`，再按搜索词调用 `filterLibrary()`，最后生成 `LibraryData` 并写入 State
（[`LibraryScreenModel.kt:174-233`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)）。

`getFavoritesFlow()` 又把 library Flow、item preferences 和 download-cache changes 合并；每次任一上游变化，都对
完整 library list 执行 `map` 构造新的 `LibraryItem` 列表
（[`LibraryScreenModel.kt:778-837`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)）。
其中下载 badge 会逐项访问 download manager；merged manga 路径的源码直接标注 `FIXME: N+1 performance issues`
（同文件 `791-800`）。

### 3.3 ScreenModel 第二阶段：分组与排序

第二条 combine 管线观察第一阶段 `LibraryData`、分组类型、排序 preference、隐藏/空分类设置和 category filter，依次执行：

```text
favorites.applyGrouping(...).applySort(...).filter(empty categories policy)
```

结果以 `Map<Category, List<Long>>` 写入 `State.groupedFavorites`，只在 map 中保留 item ID，实体仍由
`favoritesById` 查回，见
[`LibraryScreenModel.kt:236-315`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt) 与
[`LibraryScreenModel.kt:1691-1700`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)。

这个“单份实体列表 + 分组只存 ID”的设计值得借鉴：它避免同一本漫画属于多个分类时复制整条 UI model。最终页面按
category ID 取 ID 列表，再通过 `favoritesById` 映射成 `List<LibraryItem>`
（[`LibraryScreenModel.kt:1777-1785`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)）。

### 3.4 Compose 展示层

`LibraryTab` 通过 `rememberScreenModel { LibraryScreenModel() }` 获取状态持有者并 collect state；点击漫画时向 Voyager
Navigator `push(MangaScreen(id))`，而不是替换收藏 route
（[`LibraryTab.kt:108-119`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt)、
[`LibraryTab.kt:303-317`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt)）。

页面按 category 使用 Compose `HorizontalPager`，每页直接取得普通 `List<LibraryItem>`；源码主动限制只组合当前页及相邻
一页（[`LibraryPager.kt:46-56`](../../../komikku/app/src/main/java/eu/kanade/presentation/library/components/LibraryPager.kt)）。
展示模式分别进入：

- list -> `FastScrollLazyColumn` -> `LazyColumn`；
- compact/cover/comfortable/panorama -> `FastScrollLazyVerticalGrid` -> `LazyVerticalGrid`。

证据见 [`LibraryPager.kt:81-137`](../../../komikku/app/src/main/java/eu/kanade/presentation/library/components/LibraryPager.kt)、
[`LazyList.kt:60-85`](../../../komikku/presentation-core/src/main/java/tachiyomi/presentation/core/components/LazyList.kt)、
[`LazyGrid.kt:18-56`](../../../komikku/presentation-core/src/main/java/tachiyomi/presentation/core/components/LazyGrid.kt)。

收藏相关包中没有 `PagingData`、`LazyPagingItems`、`collectAsLazyPagingItems` 或 Paging `Pager`；`LibraryPager` 名称指的是
分类 `HorizontalPager`，不是 Paging3。仓库虽然依赖 Paging3，也在 `DatabaseHandler` 提供
`subscribeToPagingSource()`（[`DatabaseHandler.kt:44-53`](../../../komikku/data/src/main/java/tachiyomi/data/DatabaseHandler.kt)），
但收藏链路没有调用它。

## 4. quick filters、sort、group、search 在哪里执行

| 能力 | 执行位置 | 数据成本与备注 |
|---|---|---|
| favorite = true | SQL | 主查询唯一直接下推的收藏条件。 |
| unread/started/bookmarked/completed/custom interval/lewd | Kotlin 内存 | 对完整 `List<LibraryItem>` 逐项 predicate，见 `applyFilters()`。 |
| downloaded | Kotlin + download manager | merged source 可能逐项 suspend 查询，源码已标 N+1。 |
| tracking filter | Kotlin | 先全量读取 tracks 并按 mangaId 分组，再 include/exclude。 |
| category include/exclude | Kotlin | 使用 `LibraryManga.categories` 集合判断。 |
| category/source/status/track-status grouping | Kotlin | map 到 `Map<Category, List<Long>>`；track-status grouping 还使用 `runBlocking` 全量取 tracks。 |
| alphabetical/last-read/update/unread/total/latest/fetch/date-added/tracker score/tag/random sort | Kotlin | 每个分组内排序，最后以标题作 tie-breaker；random 使用固定 seed。 |
| search | Kotlin | debounce 后扫描全量 filtered list；普通字段直接内存匹配，metadata source 会按匹配候选读取 tags/titles。 |

具体证据：

- filter predicates 与总 `fastFilter`：
  [`LibraryScreenModel.kt:426-547`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)；
- category/default/source/status 等 grouping：
  [`LibraryScreenModel.kt:550-607`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)、
  [`LibraryScreenModel.kt:1508-1585`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)；
- sort comparator：
  [`LibraryScreenModel.kt:612-727`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)；
- search 预取 tracks/source map、按 metadata item 获取 tags/titles：
  [`LibraryScreenModel.kt:1195-1258`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)；
- search 实际检查 title/author/artist/**description**/source/tracks/genre/tags/titles：
  [`LibraryScreenModel.kt:1261-1350`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)。

因此 Komikku 选择宽 `Manga` 很大程度是为了支持强大的全字段内存搜索，并非列表卡片本身需要 description/genre。
Kototoro 若把详情字段从 card row 移除，需要明确产品选择：普通搜索是否只搜预计算 searchable text/FTS，还是按查询
进入单独搜索路径；不能一边要求极窄 row，一边无成本保留对任意详情字段的全量即时扫描。

## 5. 全量 List、不可变性与对象宽度

### 5.1 它持有的是完整快照，不是 Paging window

`LibraryData.favorites` 的类型是普通 Kotlin `List<LibraryItem>`，默认空列表；`State` 和 `LibraryData` 标了 Compose
`@Immutable`（[`LibraryScreenModel.kt:1691-1718`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)）。
每次数据/筛选变化通过 `mutableState.update { state.copy(...) }` 替换快照，不在 Composable 中逐项 mutate。

需要准确区分：这里的 `List` 是 Kotlin 只读接口，不是 `PersistentList`，也没有在类型层保证底层绝对不可变；其 UDF
安全性来自当前实现每次用 `map/filter/sortedWith/toList` 产生新列表并通过 data-class copy 发布。类别 include/exclude
才显式使用 `ImmutableSet`。

### 5.2 UI model 并不轻

`LibraryItem` 包含：

- 完整 `LibraryManga` -> 完整 `Manga`；
- download/unread count；
- local/language badge；
- 可选完整 `Source`；
- 通过 DI 默认取得的 `SourceManager`。

见 [`LibraryItem.kt:11-23`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryItem.kt)。`Manga` 又有 URL、
原始 title/artist/author/thumbnail/description/genre/status、notes、memo 等
（[`Manga.kt:20-49`](../../../komikku/domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt)）。构造 favorite Manga 时还会从
内存 `customMangaMap` 查询 override；它不是数据库 N+1，但依然是逐项 enrichment
（[`Manga.kt:51-78`](../../../komikku/domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt)、
[`CustomMangaRepositoryImpl.kt:12-18`](../../../komikku/data/src/main/java/tachiyomi/data/manga/CustomMangaRepositoryImpl.kt)）。

实际 list/grid item 通常只读 title、cover identity/URL/lastModified、badges、selection 和 continue-reading 回调
（[`LibraryList.kt:41-77`](../../../komikku/app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt)、
[`LibraryCompactGrid.kt:32-70`](../../../komikku/app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt)）。
这说明 Komikku 仍把搜索/批量操作领域模型与卡片渲染模型耦合在一起；它没有解决“避免完整 domain/entity graph
enrichment”这一问题，只是其领域图比 Kototoro 的 `WorkAggregate + projections + bindings + tags` 更扁平。

## 6. 返回详情后的列表与滚动状态

### 6.1 数据为什么不会像 Paging generation 那样重建

主 Navigator 以 `HomeScreen` 为根，配置 `NavigatorDisposeBehavior(disposeNestedNavigators = false,
disposeSteps = true)`（[`MainActivity.kt:263-266`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt)）；
Home 内部用 `TabNavigator` 保留 LibraryTab（[`HomeScreen.kt:97-103`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt)）。
LibraryTab 的 `LibraryScreenModel` 与完整 `State.libraryData.favorites` 归该 screen/back-stack 生命周期所有。详情是
`navigator.push`，所以返回时没有 Pager/PagingSource generation 需要重建或重新围绕 refresh key 取页。

分类页还保存了两层身份：ScreenModel 内的 active category ID/index，以及 preference 中的 last-used index；更新时先记录
当前 category ID，再持久化校正后的 index
（[`LibraryScreenModel.kt:1439-1455`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)）。
`LibraryContent` 的 Pager 根据 active index 修正 category 变化后的页位置
（[`LibraryContent.kt:59-77`](../../../komikku/app/src/main/java/eu/kanade/presentation/library/components/LibraryContent.kt)）。

### 6.2 滚动恢复没有自定义语义锚点

list/grid wrapper 的 state 参数默认分别是 `rememberLazyListState()` 与 `rememberLazyGridState()`
（[`LazyList.kt:60-63`](../../../komikku/presentation-core/src/main/java/tachiyomi/presentation/core/components/LazyList.kt)、
[`LazyGrid.kt:18-22`](../../../komikku/presentation-core/src/main/java/tachiyomi/presentation/core/components/LazyGrid.kt)）。收藏调用方没有把 state
提升到 ScreenModel，也没有保存 `mangaId + offset` 语义锚点。

更重要的是，list 与两种 grid 的 `items(items = ..., contentType = ...)` 都**没有传 `key`**
（[`LibraryList.kt:41-44`](../../../komikku/app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt)、
[`LibraryCompactGrid.kt:32-35`](../../../komikku/app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt)、
[`LibraryComfortableGrid.kt:34-37`](../../../komikku/app/src/main/java/eu/kanade/presentation/library/components/LibraryComfortableGrid.kt)）。
所以它依赖 Compose/Voyager saveable composition state 和位置索引，在列表顺序不变时足够直接；但详情期间若前方新增/删除、
filter/sort 改变或全量刷新重排，它没有 Kototoro 当前 semantic item anchor 那样的恢复保证。

因此正确结论是：**Komikku 通过保留完整数据和 ScreenModel 大幅减少了返回时的数据空窗，但没有实现比 Kototoro 更强的
滚动身份协议。** Kototoro 若采用全量 List，应保留 stable `key = entityId`，并保留轻量 semantic anchor 作为排序/
过滤变化和进程恢复的保险，而不是复制 Komikku 的无 key 写法。

## 7. invalidation 与更新粒度

SQLDelight 的 `subscribeToList` 会在 query invalidation 后重新执行并物化完整结果；这里没有 PagingSource 的 page
invalidation，也没有 repository 层的 per-item patch。当前更新粒度如下：

| 变化源 | 重算范围 |
|---|---|
| mangas / chapters / history / category membership / merged / excluded scanlator 影响 library view | `library()` 重新发射完整 `List<LibraryManga>`，随后完整 `LibraryItem.map`、filters、groups、sorts。 |
| categories 定义变化 | 完整 categories list 发射，再做 grouping/sort。 |
| manga_sync 变化 | 完整 tracks list 发射，重新 groupBy，随后 filter/group/sort。 |
| download cache changes | 即使 DB library 不变，也对所有 LibraryManga 重建 LibraryItem；下载 badge 开启时逐项算 count。 |
| quick-filter/search/sort/group preference | 不重查主 SQL，但对内存全量列表重做相应阶段。 |

`distinctUntilChanged()` 只在构造结果相等时阻止后续 State 写入，不能避免上游 SQL/mapping 或 filter 本身已经执行，见
[`LibraryScreenModel.kt:174-233`](../../../komikku/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt)。

这种粗粒度更新对几千个轻量 row 可能是 KISS 的正确取舍：状态模型简单、没有窗口一致性问题、返回立即可用。对宽 row、
频繁 chapter/history 更新或复杂 metadata 搜索，它会产生不必要的全量 CPU/分配。Kototoro 不应只复制
`Flow<List<...>>`，还应增加：

1. 窄 SQL/read table，避免每次变化都重建完整 aggregate；
2. 将下载、tracking counters、projection count 等稳定派生值预计算或批量联结；
3. 将“DB 原始 rows”与“当前 filter/group/sort 派生 state”分层缓存；
4. 实测一次相关行更新触发的 SQL、mapping、filter/sort、heap allocation 与首帧影响。

## 8. Kototoro 可以直接借鉴什么

### 8.1 值得直接采用的结构

1. **全量数据归 ViewModel/ScreenModel 所有。** 轻量 card rows 达标后，使用
   `StateFlow<List<FavouriteCardRow>>`，详情返回直接复用同一快照，不依赖 `LazyPagingItems` 重建 presenter。
2. **一份实体 map，多份 ID 顺序。** 参考 `favoritesById + Map<Category, List<Long>>`：filter/group/sort 只生成稳定
   ID 序列，卡片对象只保存一份；分类交叉归属不会复制对象图。
3. **两阶段派生。** 第一阶段合并 DB rows、必要 counters 与 quick filters，第二阶段仅处理 group/sort；不同 preference
   变化可限制到相应阶段，避免全部 enrichment 重跑。
4. **category 使用语义 ID，而不是只保存 index。** category 增删/隐藏后用 ID 找回当前页，再以 index 作 fallback。
5. **普通 `List` + Lazy UI。** 对 6,000 个窄 row，先 benchmark 全量列表，而不是因为条数四位数就默认 Paging；
   LazyColumn/Grid 不要求输入本身必须分页。
6. **全量辅助关系先 groupBy ID。** tracking/category 等关系若必须内存处理，应一次批量读取并建 map，避免 item 级 DAO。

### 8.2 不能照搬的部分

1. **不能照搬 `M.*`。** Kototoro 的核心目标正是把 description、sourceData、完整 projections/categories/tags/
   overrides 从 card row 移出；Komikku 的宽模型是技术债证据，不是目标 schema。
2. **不能照搬全章节聚合 view 而不 benchmark。** 6,000 个 favourites 不大，不代表与其关联的数十万 chapters/history
   行聚合不大。应考虑专用计数表、触发器/事务维护的 summary，或增量 read table。
3. **不能照搬 N+1。** Komikku 已在 merged downloads filter/badge 路径自行标出问题；Kototoro 的 metadata、override、
   tags 必须按一批 IDs 查询或预计算。
4. **不能移除 stable Lazy key。** Komikku 当前按位置 identity；Kototoro 应继续按 entity/work ID 提供 stable key 和
   contentType。
5. **不能把 `@Immutable List` 当深不可变保证。** 应发布不可修改的新快照；若使用 persistent collections，必须用
   benchmark 证明收益，而不是为了类型美观增加转换成本。
6. **不能假定粗粒度 invalidation 永远便宜。** 对频繁阅读进度、下载、tracking 更新，需决定是全量重算仍在预算内，
   还是让 summary/card table 支持更精确的更新。
7. **不能以 Komikku 代替 Kototoro release benchmark。** 当前源码没有 6k/10k 基准数据，也没有证明低端设备、R8、
   大量 categories/tracks/tags 下的内存与返回耗时。

## 9. 对 Kototoro 的建议落地形态

推荐的最小深模块不是“无 Paging Repository”，而是一个不让 UI 知道实体系统复杂度的收藏快照模块。当前没有第二种
生产 adapter 的需求，不必为它预先增加 Kotlin `interface`；直接以一个可通过 Room in-memory database 测试的深模块
形成 seam：

```kotlin
class FavouriteLibrarySnapshotStore {
    fun observe(): Flow<FavouriteLibrarySnapshot>
}

data class FavouriteLibrarySnapshot(
    val rowsById: Map<Long, FavouriteCardRow>,
    val allIds: List<Long>,
    val categoryIds: Map<Long, List<Long>>,
)

data class FavouriteCardRow(
    val entityId: Long,
    val displayMangaId: Long,
    val title: String,
    val coverUrl: String?,
    val sourceLabel: String,
    val progress: Float?,
    val unreadCount: Int,
    val flags: Int,
)
```

这样全库只保留一份 row map，各分类只保存 entity ID 顺序，避免当前每个 category 的
`FavouritesListViewModel` 分别拥有数据生成链路。Quick Filters、source preset、space/group 和 sort 是该基础快照的内存
派生输入，不应成为重新创建数据库 Pager 的理由。字段必须由 Kototoro 三种真实卡片模式反推，上例不是预留未来能力。
结合相同备份在 Komikku 中的实际表现，建议实施顺序调整为：

1. 新增窄 `FavouriteCardRow` 查询与一次性可观察快照，先覆盖当前收藏卡片和 Quick Filters 的真实字段；
2. 由收藏容器级状态持有者持有唯一基础快照，在内存中派生 filter/group/sort 后的稳定 ID 序列；逐分类
   `FavouritesListViewModel` 应删除或退化为只消费共享快照的薄状态；
3. 收藏 Compose 层改为消费普通 `List<ListModel>`，继续使用 entity ID stable key，并保留语义滚动锚点；
4. 删除收藏专用的 Pager、`BatchMappingPagingSource` 和 retained paging window；通用列表模块暂不受影响；
5. 以至少 10k favourites + 高 chapters/categories/tracks 分布测：冷查询、单行进度更新后的重查、filter 切换、sort、
   详情返回首帧、retained heap、GC；
6. 若全量重查不达标，先优化 summary/card table 与 invalidation，不要立即回到宽 aggregate Paging；只有轻 row 的完整
   列表仍超预算时，才保留 Paging3。

最终判定：**Komikku 支持“6000 条本身不是 Paging3 的充分理由”这一判断，但它也支持更重要的限定——决定性能的是
每条收藏 row 背后的 SQL 聚合、对象宽度、辅助关系和 invalidation 频率。** Kototoro 应借鉴它的完整列表状态所有权，
而不是照搬它的宽 `libraryView`。
