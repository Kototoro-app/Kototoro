# 收藏 / 库网格 Compose 性能研究（2026-08）

> 研究目标：为“收藏 / 库网格”列表屏（数百至数千张漫画封面卡片）收集 Jetpack Compose 性能最佳实践，
> 结果服务于后续设计任务，不产出应用代码。
>
> 库基线：Compose BOM 2026.08.00 / `ui` 1.12.0 / Material3 1.5.0-alpha26 / Paging 3
> （官方文档当前在 setup 示例中标注 `androidx.paging:*:3.4.2`）。
>
> 方法：仅使用一手来源——developer.android.com 官方文档与 API 参考、官方示例仓库
> `android/snippets` 与 `android/nowinandroid`、androidx / JetBrains / coil-kt 官方仓库的真实 issue 线程（GitHub
> issues 经本机 `gh` CLI + token 逐条读取原文与维护者回复核实）、Coil 官方文档（coil-kt.github.io/coil）。
> 所有 URL 均于 **2026-08-27** 逐一抓取确认可访问；正文每条结论后附来源链接、类型（官方文档 / 官方示例 /
> issue 编号）与该日期。
>
> 已落地实现对照（Kototoro 当前代码）：收藏页入口 `FavoritesListScreen` → `AppContentListRoute` →
> `KototoroContentListScreen`，已使用 `LazyVerticalGrid` + `key` + `contentType` +
> `span = GridItemSpan(maxLineSpan)`；Paging 配置为 `LargeLibraryPagingConfig`
> （`pageSize = 64`, `initialLoadSize = 64`, `prefetchDistance = 24`, `enablePlaceholders = false`，
> 收藏页 `prefetchDistance = 128`，见 [large-library-performance-handoff-2026-08.md](./large-library-performance-handoff-2026-08.md)）；
> ViewModel 已用 `collectAsStateWithLifecycle`。本文结论与上述方向一致，并指出可进一步加固的点。

## Key takeaways for a large favourites grid

1. **用 `LazyVerticalGrid` 装载，配合 Paging 3 按块加载，不要一次把整个收藏库放进组合或内存。**
   官方明确 Lazy 组件“只组合并布局视口内可见的项”，Paging 把大数据集切成小块按需加载并有内存缓存、请求去重能力
   （[Lists 文档](https://developer.android.com/develop/ui/compose/lists)、[Paging Overview](https://developer.android.com/topic/libraries/architecture/paging/v3-overview)，2026-08-27 访问）。

2. **每个 item 都要给稳定唯一 key（`key = { it -> it.id }`），并尽量提供 `contentType`。**
   默认按位置索引 key 会让“移动/增删”时 Compose 把没变的项也当删除重建，导致整表重组合；`contentType` 让 Compose 只在
   同类型项之间复用组合。官方 Lazy 列表示例与 Paging 的 `itemKey { it.id }` 都这样要求
   （[Lists 文档](https://developer.android.com/develop/ui/compose/lists)、[Performance Best Practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)，2026-08-27 访问）。

3. **封面卡片 item 的 UI model 要稳定：`@Immutable`/`@Stable` 或把 `List` 属性换成 `ImmutableList`。**
   编译器把 `List`/`Set`/`Map` 一律视为不稳定，`List<Foo>` 参数即使 `Foo` 稳定也仍不稳定；强跳过（Kotlin 2.0.20 起默认开启）
   对不稳定参数用 `===` 比较，Room 每次刷新都新建对象 → item 仍会重组合。若 data 层模块没编译 Compose 编译器，应在 UI 层包装成
   稳定的 UI model（或给类加注解）
   （[Stability](https://developer.android.com/develop/ui/compose/performance/stability)、
   [Fix stability issues](https://developer.android.com/develop/ui/compose/performance/stability/fix)、
   [Strong skipping](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping)，2026-08-27 访问）。

4. **不要在组合中直接读 `LazyListState.layoutInfo` / `firstVisibleItemIndex` 这类高频状态。**
   用 `derivedStateOf` 把“阀值型”结果包起来（如回顶按钮 `firstVisibleItemIndex > 0`），一次性副作用用
   `snapshotFlow { … }.distinctUntilChanged()`。真实 issue [JetBrains/compose-multiplatform#1992](https://github.com/JetBrains/compose-multiplatform/issues/1992)
   就是直接读 `layoutInfo` 导致无限重组的例子，维护者引用文档结论“避免在组合中使用它”
   （[Lists 文档](https://developer.android.com/develop/ui/compose/lists)、
   [Side-effects](https://developer.android.com/develop/ui/compose/side-effects)，2026-08-27 访问）。

5. **流收集集中在 ViewModel / 屏幕层：Paging flow 加 `cachedIn(viewModelScope)`，屏幕级 StateFlow 用
   `stateIn(WhileSubscribed)`，UI 只用 `collectAsStateWithLifecycle`。**
   后台时 Flow 自动暂停、节省资源；不要在每个 item 内部各自收集流。官方把 Lazy/Grid 状态（如 `LazyListState` 标注
   `@Stable`）hoist 到最低公共祖先，避免反复创建
   （[Lifecycle](https://developer.android.com/topic/libraries/architecture/lifecycle)、
   [State](https://developer.android.com/develop/ui/compose/state)、[State hoisting](https://developer.android.com/develop/ui/compose/state-hoisting)，2026-08-27 访问）。

6. **封面图片：先用固定约束/宽高比占位，再加载，避免 0 像素 item。** 0 像素会在首轮测量时触发 Lazy 布局把
   所有项组合一遍，图片到位后再丢弃重建。Coil 官方建议“用固定约束或已知宽高比预留空间”；用一个共享 ImageLoader
   （自带 memory/disk cache），滚动性能上优先简单的 `ImageRequest.crossfade` 而不是 `AnimatedContent`（详见 §5.6）
   （[Lists 文档](https://developer.android.com/develop/ui/compose/lists)、
   [Coil Recipes](https://coil-kt.github.io/coil/recipes/)、[Coil Image Loaders](https://coil-kt.github.io/coil/image_loaders/)，2026-08-27 访问）。

7. **只在 release + R8 下度量 Lazy 布局。** 官方指出 debug 构建下 Lazy 滚动可能更慢，量测须在 release 且 R8 开启
   时进行才有意义；排查重组合用 Compose compiler reports（`composables.txt` 标注 `unstable` 参数）与 Layout Inspector，
   但要避免“把所有东西都做成 skippable”的过早优化
   （[Lists 文档](https://developer.android.com/develop/ui/compose/lists)、
   [Diagnose stability issues](https://developer.android.com/develop/ui/compose/performance/stability/diagnose)，2026-08-27 访问）。

## 1. Lazy 容器基础：可见项组合、key、contentType、span

### 1.1 为什么大数据集必须用 Lazy 而不是 `Column`

官方 Lists 文档明确：显示“大量项或未知长度的列表”时，`Column` 会把所有项“无论是否可见都组合并布局”，造成性能问题；
`LazyColumn`/`LazyRow` 及网格组件“只组合并布局视口内可见的项”。这是“数百上千张封面”场景使用
`LazyVerticalGrid` 的直接依据
（[Lists 文档 — “Lazy lists”](https://developer.android.com/develop/ui/compose/lists)，2026-08-27 访问）。

### 1.2 item key：默认按位置，数据变化会丢状态、涨重组合

- “默认情况下，每个 item 的状态以它在列表/网格中的位置作为 key。如果数据集变化，换位的项会丢失 remember 状态”
  （官方引例：`LazyRow` 嵌在 `LazyColumn` 中，行换位后用户滚动位置丢失）。
- 解法是向 `key` 参数传“稳定且唯一”的 id，使状态在数据集变化时保持一致
  （[Lists 文档 — “Item keys”](https://developer.android.com/develop/ui/compose/lists)，2026-08-27 访问）。
- Best Practices 文档补足“为什么影响性能”：用户把一条笔记置顶后，其余项下移一位；若不提供 key，“Compose 会认为是删掉旧
  项、新建新项……即使只有一项真的变了，也会把所有项重组合一遍”；稳定 key 让 Compose 识别“位置变了但内容没变”，从而跳过重组合
  （[Performance Best Practices — “Use lazy layout keys”](https://developer.android.com/develop/ui/compose/performance/bestpractices)，2026-08-27 访问）。
- 限制：key 类型必须能被 `Bundle` 支持（基本类型、enum、Parcelable 等），`rememberSaveable` 才能在滚离再滚回、
  Activity 重建时恢复 item 内状态
  （[Lists 文档 — “Item keys”](https://developer.android.com/develop/ui/compose/lists)，2026-08-27 访问）。

### 1.3 contentType：异构列表/网格复用的关键

Compose 1.2 起提供 `contentType`：“提供 contentType 后，Compose 只会在同类型项之间复用组合”，避免把 A 类型项组合
“拼”到完全不同的 B 类型项上，从而最大化组合复用
（[Lists 文档 — “Consider adding contentType”](https://developer.android.com/develop/ui/compose/lists)，2026-08-27 访问）。

对收藏库网格：常规工作与“加载中 / 无更多 / 报错”footer 是不同 `contentType`；官方文档用 `span = { GridItemSpan(maxLineSpan) }`
在自适应列数网格中输出整行项，即我们现在 footer 的做法
（[Lists 文档 — “Lazy grids”](https://developer.android.com/develop/ui/compose/lists)；
API 参考 [LazyGridScope / maxLineSpan](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/grid/LazyGridScope)，
2026-08-27 访问）。

### 1.4 封面卡的占位尺寸：避免 0 像素项

官方 Tips 明确：item 在异步取到图片前高为 0 像素时，“Lazy 布局会在首次测量把所有项都组合起来（因为 0 高能塞进视口）；
图片加载后高度撑开，又不得不丢弃此前多余组合的项”。正确做法是给 item 默认尺寸，并保持加载前后“尺寸一致”（例如用占位卡或
固定封面宽高比），以维持滚动位置
（[Lists 文档 — “Avoid using 0-pixel sized items”](https://developer.android.com/develop/ui/compose/lists)，2026-08-27 访问）。

### 1.5 其他官方 Tips（与网格相关）

- 一个 item 里放多个元素：只有当其中一个可见时整组都要组合测量，过度使用会损害性能；极端情况“把所有元素放进一个 item
  就完全违背 Lazy 布局的意义”，还会干扰 `scrollToItem()`/`animateScrollToItem()`
  （[Lists 文档 — “Beware of putting multiple elements in one item”](https://developer.android.com/develop/ui/compose/lists)，2026-08-27 访问）。
- 不要嵌套同方向可滚动组件（如 `verticalScroll` 的 `Column` 里放无固定高度 `LazyColumn`，会抛异常）；要把 header/footer
  用 Lazy DSL 放进同一容器
  （[Lists 文档 — “Avoid nesting components scrollable in the same direction”](https://developer.android.com/develop/ui/compose/lists)，2026-08-27 访问）。
- 度量注意：“只有当运行在 release 模式且 R8 优化开启时，你才能可靠地度量 Lazy 布局的性能；debug 构建下 Lazy 布局滚动可能
  显得更慢”
  （[Lists 文档 — “Measuring performance”](https://developer.android.com/develop/ui/compose/lists)，2026-08-27 访问）。

### 1.6 官方示例仓库的网格写法对照

- **`android/snippets`**（官方代码片段仓库）：[LazyListSnippets.kt](https://github.com/android/snippets/blob/main/compose/snippets/src/main/java/com/example/compose/snippets/lists/LazyListSnippets.kt)
  包含 `GridItemSpan(maxLineSpan)` 整行项、`LazyVerticalGrid`、`contentType`、Paging 的
  `lazyPagingItems.itemKey { it.id }` 等完整示例（官方示例仓库，2026-08-27 访问）。
- **`android/nowinandroid`**（Google 官方示例应用）：`ForYouScreen.kt` 把“新闻流”做成
  `LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Adaptive(300.dp))`；需要整行的区块用
  `item(span = StaggeredGridItemSpan.FullLine, contentType = "bottomSpacing"/"onboarding")` 显式区分
  contentType；另一处 `LazyHorizontalGrid(rows = GridCells.Fixed(3))` 的
  `items(... key = { it.topic.id })` 给每项稳定 key，
  [ForYouScreen.kt（commit 7d45eae）](https://github.com/android/nowinandroid/blob/7d45eae4f8720a0c77f507712ba2437ff974b6ed/feature/foryou/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/foryou/impl/ForYouScreen.kt)
  （官方示例，2026-08-27 访问）。要点：官方 App 在“混合内容网格”里正是用 自适应列数 + 全行 `span`/`contentType` +
  稳定 key 的组合——与 Kototoro 收藏页当前 `LazyVerticalGrid` + `span(maxLineSpan)` + `contentType` 的写法一致。

## 2. Paging 3：大数据集的标准答案

### 2.1 为什么用 Paging

官方把 Paging 定位为“从更大数据集中加载并展示分页数据”的库，特性包括：分页数据的**内存缓存**、内置**请求去重**、
一流的协程/Flow 支持、内置错误处理与 refresh/retry
（[Paging Overview](https://developer.android.com/topic/libraries/architecture/paging/v3-overview)，2026-08-27 访问）。
Compose 端通过 `androidx.paging:paging-compose` 的 `collectAsLazyPagingItems()` 把 `PagingData` 接进
`LazyColumn`/`LazyVerticalGrid`
（[Lists 文档 — “Large data-sets (paging)”](https://developer.android.com/develop/ui/compose/lists)，2026-08-27 访问）。

### 2.2 PagingSource 与 key 链

- `PagingSource<Key, Value>`：重写 `load()`，返回 `LoadResult.Page / Error / Invalid`；`Page` 通过 `prevKey`/`nextKey`
  形成“上/下一页”的 key 链。`LoadResult.Invalid` 表示数据源已失效、应由新实例替换；还须实现 `getRefreshKey()`，
  用 `PagingState.anchorPosition` + `closestPageToPosition()` 决定刷新后回到哪一页
  （[Load and display paged data](https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data)，2026-08-27 访问）。
- Placeholder 的关键约束：**要支持 placeholder 必须把 `PagingConfig.enablePlaceholders` 设为 `true`，且自定义
  `PagingSource` 必须在 `LoadResult.Page` 里实现 `nextKey` 和 `prevKey`**
  （同上，2026-08-27 访问）。
- 注意：`refresh()`（`LazyPagingItems` 上的）“面向 UI 驱动的刷新信号，如下拉刷新；仓库层信号（如数据库更新）应改用
  `PagingSource.invalidate()`”；`retry()` 只重试失败加载、**不** invalidate 数据源
  （[LazyPagingItems API 参考](https://developer.android.com/reference/kotlin/androidx/paging/compose/LazyPagingItems)，2026-08-27 访问）。

### 2.3 PagingData 流：Pager + cachedIn

- 官方示范在 **ViewModel** 中创建 `Pager(config = PagingConfig(...), pagingSourceFactory = {...}).flow`，并
  用 `cachedIn(viewModelScope)` 处理
  （[Load and display paged data](https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data)，2026-08-27 访问）。
- `cachedIn`：**“让数据流可共享，并按提供的 `CoroutineScope` 缓存已加载数据；不加 `cachedIn`，`PagingData` 无法被再次
  收集”**；同时它会缓存其之前所有变换的结果，典型做法是在 ViewModel 里、曝光给组合之前调用
  （[Transform data streams](https://developer.android.com/topic/libraries/architecture/paging/v3-transform)，2026-08-27 访问）。
- 关键语义：“默认每个 `PagingData` 实例只能用一次”，`cachedIn` 通过组播解决“按需取一次、多次收集”的问题，与
  `Flow.combine()` 等复用最近一次 `PagingData` 的算子配合尤其有用
  （同上，2026-08-27 访问）。
- 与收藏页最相关：**`CollectAsLazyPagingItems` 的收集发生在组合作用域内**，不要把悬浮收集放到错误的作用域
  （[Load and display paged data](https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data)，2026-08-27 访问）。

### 2.4 LoadState 与占位

- `LoadState` 三态：`Loading` / `Error` / `NotLoading`（含 `endOfPaginationReached`）；`LazyPagingItems.loadState`
  返回 `CombinedLoadStates`，可分别看 `refresh` / `append` / `prepend`
  （[Manage and present loading states](https://developer.android.com/topic/libraries/architecture/paging/load-state)，2026-08-27 访问）。
- header/footer 的官方示例在 `LazyColumn` 的 `item {}` 里读 `loadState.prepend` / `.append`，但文档加注：
  **该写法只在 placeholders 关闭时成立；否则这些 loading item 会显示在占位符之后**——与收藏页
  `enablePlaceholders = false` 的选择一致
  （同上，2026-08-27 访问）。
- 需要区分“本地库为空”与“网络同步中”时可分别读 `loadState.source`（PagingSource/Room 侧）与 `loadState.mediator`
  （RemoteMediator 侧），避免有缓存数据时误显示全屏转圈
  （同上，2026-08-27 访问）。

### 2.5 RemoteMediator：网络 + 本地库缓存

- 适用场景：网络数据源 + 本地数据库缓存；此时“数据库是 **source of truth**，应用只展示已缓存到库里的数据”，由
  Room 生成的 `PagingSource` 把缓存数据喂给 UI；文档还指出本地库缓存下“**ViewModel 里就不需要再维护一个内存缓存**”
  （[Page from network and database](https://developer.android.com/topic/libraries/architecture/paging/v3-network-db)，2026-08-27 访问）。
- `RemoteMediator.load(loadType, state)` 返回 `MediatorResult.Success(endOfPaginationReached)` 或 `MediatorResult.Error`；
  Room 提供自动 invalidate `PagingSource` 的能力
  （同上，2026-08-27 访问）。
- 与 Lazy 列表结合的官方警告：**使用 RemoteMediator 时务必提供尺寸现实的 placeholder 项；placeholder 过小或没有，
  屏幕可能永远填不满，导致反复拉取大量页**
  （[Lists 文档 — “Large data-sets (paging)”](https://developer.android.com/develop/ui/compose/lists)，2026-08-27 访问）。
- 对收藏页的意义：封面卡片占位尺寸（固定宽高比）不只是滚动性能问题，也影响 RemoteMediator 场景下是否“多拉页”。

### 2.6 Multiplatform（KMP）演示层

- Paging 官方 Release Notes 明确：**3.3.0 起 Paging 制品支持 Kotlin Multiplatform**——“paging-common 已把全部 Paging 3
  API 下沉到 common，兼容 jvm 与 iOS；paging-compose 代码移到 common 并发布 Android 制品，与 androidx.compose 的多平台支持
  对齐”；3.4.0-alpha03 起 `paging-compose` 新增 desktop/iOS 等 KMP target
  （[Paging releases](https://developer.android.com/jetpack/androidx/releases/paging)，2026-08-27 访问）。
- 依赖正本：`androidx.paging:paging-common` + `androidx.paging:paging-compose`（官方 setup 示例标注 3.4.2）
  （[Paging Overview](https://developer.android.com/topic/libraries/architecture/paging/v3-overview)，2026-08-27 访问）。

## 3. 稳定性：@Stable / @Immutable、强跳过，以及“Lazy 项参数不稳定”的来龙去脉

### 3.1 稳定性定义与后果

- “类型稳定 = 不可变，或 Compose 能判断其值是否在重组合之间改变；不稳定则 Compose 无从判断”。
- 判定规则：“参数稳定且未变 → 跳过该组合；参数不稳定 → 父组件重组合时它**始终**重组合。”
- 例子：`data class Contact(val name: String, val number: String)` 是不可变数据类，`ContactRow` 内点击 toggle 时
  `ContactDetails` 被跳过；若把属性改成 `var`，类不再稳定，`ContactRow` 一重组合，整个 item 内容跟着重组合
  （[Stability in Compose](https://developer.android.com/develop/ui/compose/performance/stability)，2026-08-27 访问）。

### 3.2 集合为什么总是不稳定

- “Compose **始终把集合类视为不稳定**，比如 `List`、`Set`、`Map`——因为无法保证它们不可变。可以改用 Kotlinx
  immutable collections，或把类标注为 `@Immutable`/`@Stable`。”
- 更深一层：`Set<String>` 声明不可变，但底层实现仍可能是 `mutableSetOf(...)`，编译器只看声明类型，因此仍判不稳定
  （[Stability](https://developer.android.com/develop/ui/compose/performance/stability)、
  [Diagnose stability issues](https://developer.android.com/develop/ui/compose/performance/stability/diagnose)，2026-08-27 访问）。

### 3.3 强跳过（Strong Skipping）——Kotlin 2.0.20 起默认开启

- 两种行为改变：“**带不稳定参数的组合变为可跳过**；**带不稳定捕获的 lambda 会被 remember**”；默认在 Kotlin
  2.0.20 开启。
- 比较规则：“不稳定参数用实例相等 `===` 比较；稳定参数用 `Object.equals()` 比较”——这正是 `@Stable` 仍有意义的原因：
  “当你想用对象相等而非实例相等时，继续给类标 `@Stable`。典型例子：监听整表对象时，**Room 会在任一项变化时为每一项都
  新建对象**。”若这类 UI model 不稳定，强跳过后仍因 `===` 不同而重组合
  （[Strong skipping](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping)，2026-08-27 访问）。
- lambda 记忆化：强跳过会“自动 remember 组合内的每个 lambda”，按捕获值比较；若无记忆化，“每次重组合都分配新 lambda，
  导致接收方参数不相等 → 重组合”
  （同上，2026-08-27 访问）。

### 3.4 @Stable / @Immutable 是“契约”，不是魔法

- “这两个注解本身不会让类变得不可变或稳定；用它们等于向编译器承诺契约，**错误标注可能让重组（recomposition）出错**”，
  文档还类比为 Kotlin 的 `!!`，强调谨慎使用；能不加注解达到稳定就尽量不加
  （[Fix stability issues](https://developer.android.com/develop/ui/compose/performance/stability/fix)，2026-08-27 访问）。
- 而真正要给类加注解的官方边界：**“如果 data 层在独立于 UI 层的模块里（即推荐架构），就可能遇到此问题”——Compose 编译器
  只能对那些“引用类型全都显式标注稳定，或所在模块也用 Compose 编译器构建”的类推断稳定**。三种解法：① data 层类加
  `@Stable`/`@Immutable`（或给 data 层模块开启 Compose 编译器）；② 加稳定性配置文件（`stability_config.conf`，
  可包含 `kotlin.collections.*`）；③ **在 UI 模块里把 data 层类包装成 UI model 类**
  （[Fix stability issues — “Multiple modules”](https://developer.android.com/develop/ui/compose/performance/stability/fix)，2026-08-27 访问）。
- **注意一个花招不管用**：把 `Snack` 标成 `@Immutable` 后，`List<Snack>` 参数在编译器眼里**仍然不稳定**；也不能只给单个
  参数标稳定。出路是 `ImmutableList<Snack>`、wrapper 类，或稳定性配置文件
  （[Fix stability issues — “Annotated classes in collections”](https://developer.android.com/develop/ui/compose/performance/stability/fix)，2026-08-27 访问）。

### 3.5 “Lazy 项参数不稳定”为什么会 warn / 可被观察到

官方文档里最接近“警告”的载体是 Compose compiler reports：release 构建下生成 `*-composables.txt`（标出每个组合函数参数
的 `stable`/`unstable` 与 `skippable`/`restartable`）与 `*-classes.txt`（标出每个类的运行时稳定性）。文档给出的典型输出：

```
restartable scheme (...) fun HighlightedSnacks(
    stable index: Int
    unstable snacks : List<Snack>
    ...
)
```

并注明“`HighlightedSnacks` 不可跳过……原因就是不稳定参数 `snacks`”（官方以 `## unstable …` 标签表达“不稳定”）。
另外 Android Studio Layout Inspector 直接显示每个组合的重组/跳过计数。所以“编译器对不稳定参数告警”的现实形态是
**编译报告里的 `unstable` 标注 / Layout Inspector 计数**，而不是构建期警告；排查流程是 release 构建跑 reports + Inspector，
先确认是真问题，再动手（官方反复提醒这是“过早优化陷阱”）
（[Diagnose stability issues](https://developer.android.com/develop/ui/compose/performance/stability/diagnose)、
[Fix stability issues — “Not every composable should be skippable”](https://developer.android.com/develop/ui/compose/performance/stability/fix)，
2026-08-27 访问）。

### 3.6 官方 KDoc 定义

- `@Immutable`：“把类标记为产生不可变实例；类内部可访问属性与字段在构造后不会变化”，且“只有 `val` 属性、属性类型为
  基础类型或同样 `@Immutable` 的 data class 可以安全标注”
  （[Immutable — API 参考](https://developer.android.com/reference/kotlin/androidx/compose/runtime/Immutable)，2026-08-27 访问）。
- `@Stable`：“向 Compose 编译器传达类型/函数行为的保证”：`equals` 对相同实例恒返回相同结果；公共属性变化时组合会被通知；
  所有公共属性类型稳定；“若上述假设不满足则行为未定义，**除非确信满足这些条件，否则不应使用该注解**”
  （[Stable — API 参考](https://developer.android.com/reference/kotlin/androidx/compose/runtime/Stable)，2026-08-27 访问）。

### 3.7 对收藏页的落地含义

- item 的 UI model（封面、标题、评分、badge 等）建议：所有属性 `val` + 基础类型/`@Immutable` 类型；含 `List` 属性时改用
  `ImmutableList` 或把集合包进 `@Immutable` wrapper；`Content`/`ContentListModel` 若源自未编译 Compose 的 data/domain 层，
  在 UI 层建立稳定的展示 model（配 `toUiModel()` 映射），并配合 Compiler reports 验证参数已 `stable`。
- 回调 lambda 参数（`onClick`、`onLongClick`）在强跳过下会被自动 remember；若仍被外部每次新建传入，可用
  `remember` 稳定化（Best Practices 的“defer reads / lambda 延迟”一节与 Compose compiler 行为一致，
  见 [Performance Best Practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)）。

## 4. 滚动状态的读取：derivedStateOf、延迟读取、snapshotFlow

### 4.1 derivedStateOf 的正确用法

- 何时用：“输入变化频率高于你需要的重组频率时用 `derivedStateOf`——典型是滚动位置这类高频状态，而 UI 只需在跨过阀值时
  响应”；它“行为类似 `Flow.distinctUntilChanged()`”。
- 官方范例（回顶按钮）：“`val showButton by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }`”，
  并注明这样“把不必要的组合降到最少”
  （[Lists 文档 — “Reacting to scroll position”](https://developer.android.com/develop/ui/compose/lists)、
  [Side-effects — “derivedStateOf”](https://developer.android.com/develop/ui/compose/side-effects)，2026-08-27 访问）。
- 何时**不**用：`firstName` + `lastName` 拼 `fullName` 这类“派生结果更新频率和输入一样高”的情形，用 `derivedStateOf`
  纯属开销；官方明确“`derivedStateOf` 是昂贵的，只在结果未变、用来避免多余重组时才用”
  （[Side-effects — “Incorrect usage / Caution”](https://developer.android.com/develop/ui/compose/side-effects)，2026-08-27 访问）。

### 4.2 延迟读取（defer reads）与 lambda 版 modifier

- “把状态读取包装成 lambda，让读取只在实际需要时才发生”，把 `Modifier.offset(x, y)` 换成 `Modifier.offset { … }`、
  `Modifier.background(color)` 换成 `Modifier.drawBehind { … }`，能让 Compose 跳过组合阶段直奔 layout/draw
  （[Performance Best Practices — “Defer reads as long as possible”](https://developer.android.com/develop/ui/compose/performance/bestpractices)、
  [Official snippets PerformanceSnippets.kt](https://github.com/android/snippets/blob/main/compose/snippets/src/main/java/com/example/compose/snippets/performance/PerformanceSnippets.kt)，2026-08-27 访问）。
- 相应对照：不要在组合里直接读 `listState.layoutInfo`（含 `viewportEndOffset` 等），否则每次滚动/重测量都触发重组，
  甚至无限循环（见 §5 真实 issue）。

### 4.3 一次性副作用：snapshotFlow + distinctUntilChanged

官方示例把 `listState.firstVisibleItemIndex` 转成 Flow 再 `map{…}.distinctUntilChanged().filter{…}.collect { analytics }`，
用于“滚过某点只上报一次”；`snapshotFlow` 本身在块内读取的 State 变化时发射，且“值相等时不发”（类 `distinctUntilChanged`）
（[Lists 文档](https://developer.android.com/develop/ui/compose/lists)、
[Side-effects — “snapshotFlow”](https://developer.android.com/develop/ui/compose/side-effects)，2026-08-27 访问）。
Paging 的 LoadState 侧副作用（刷新完成回顶）官方同样示范 `snapshotFlow { pagingItems.loadState.refresh }
.distinctUntilChanged().filter{ is NotLoading }.collect { … }`
（[Manage and present loading states](https://developer.android.com/topic/libraries/architecture/paging/load-state)，2026-08-27 访问）。

## 5. LazyVerticalGrid / StaggeredGrid 已知坑与真实 issue 线索

> 说明：androidx Foundation 的 bug 大多登记在 Google Issue Tracker（issuetracker.google.com），GitHub 上
> 官方 issue 主要存在于共享代码库 JetBrains/compose-multiplatform。以下均为**真实的、可复现访问地址**的 issue 线程。

### 5.1 组合中读布局状态 → 无限重组（最相关）

[JetBrains/compose-multiplatform#1992](https://github.com/JetBrains/compose-multiplatform/issues/1992)
（`LazyColumn. Infinite recomposing if we use state.layoutInfo.viewportEndOffset and items`，closed）：
复现在组合中 `println(state.layoutInfo)` 并使用 items 即可触发无限重组；维护者 igordmn 回复属“按设计工作”，引用官方
`LazyLayoutInfo.layoutInfo` 文档：“该属性可观测、每次滚动/重新测量后更新；**在组合函数中使用它会在每次变化时被重组，
可能引发性能问题甚至无限重组循环，因此应避免在组合中使用**”。
→ 直接指导：“回到顶部 / 是否已滚过某行”这类判断必须走 `derivedStateOf`，混合滚动布局位置计算尽量在 layout 阶段完成
（2026-08-27 访问）。

### 5.2 滚动时 item 不被 remember、反复重组

[JetBrains/compose-multiplatform#3458](https://github.com/JetBrains/compose-multiplatform/issues/3458)
（`kotlin/native - items in UIKitView compose are not remembered and are recomposing on scroll.`，closed）：iOS
`UIKitView` 内 items 滚动时反复重组。虽然平台是 iOS，但它反映了“滚动即重组”的一类表象：从稳定性/组合作用域入手排查
（item 内不要持有会导致每次重组新建的对象，如未 remember 的 image request、未稳定的 model 等）（2026-08-27 访问）。

### 5.3 span 相关崩溃/边界（网格特有）

- [JetBrains/compose-multiplatform#2287](https://github.com/JetBrains/compose-multiplatform/issues/2287)
  （`Resizing LazyVerticalGrid crashes in combination with item span > 1`，closed）：自适应列数 + `span > 1` 在尺寸变化时
  有崩溃史。全宽 footer 用 `span = { GridItemSpan(maxLineSpan) }` 是文档推荐写法，但遇到“配置/窗口尺寸变化”场景应自测
  （2026-08-27 访问）。
- [JetBrains/compose-multiplatform#5623](https://github.com/JetBrains/compose-multiplatform/issues/5623)
  （`VerticalScrollbar over a LazyVerticalGrid using Modifier.animateItem() crashes…`，open）：`animateItem()` 与滚动条
  组合在网格上仍有报错历史；收藏页若对 item 排序动画 + 快速滚动条叠加，需留意该边界（2026-08-27 访问）。

### 5.4 官方对“keys / contentType / GridCells”的唯一权威表述

见 §1：官方 Lists 文档对 `GridCells.Adaptive/Fixed`、`span`/`maxLineSpan`、`contentType`、item key 与
“0 像素/单 item 多元素/嵌套滚动”等给出一致建议；API 参考
[LazyVerticalGrid.composable](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/grid/LazyVerticalGrid.composable)、
[LazyVerticalStaggeredGrid.composable](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/staggeredgrid/LazyVerticalStaggeredGrid.composable)
确认这些组件与参数（2026-08-27 访问）。
LazyVerticalStaggeredGrid 的官方定位是“**允许单项高度不同**”的 lazy 网格；对“数百到数千个统一封面卡”而言，常规
`LazyVerticalGrid` 通常已够用，仅在需要瀑布流式不同高度卡片时才引入 StaggeredGrid（该组件测量成本更高，官方目前无
专项性能文档，见 §7 局限）。

### 5.5 Coil 一手 issue：懒网格里封面图片 jank 的直接证据

[coil-kt/coil#1866 “Performance issue in lazy grid”](https://github.com/coil-kt/coil/issues/1866)（closed）
是极贴合的“网格 + 图片”jank 一手线程：复现工程只是“用一个 super simple 的 Compose App 显示网络图片网格”，
却“**滚动时能肉眼看到 jank，即便：App 以 R8 release 模式运行、已带滚动屏 baseline profile、Compose 参数已稳定、
网格项已用稳定唯一 key**”（三星 S21 Ultra / Android 13 实测）。评论里维护者 colinrtwhite 两次回复要点：
“你是在 release 模式测的吗？Compose 在 debug 下通常慢很多”；对另一位“滚动时内存飙到 ~2GB”的报告，指出“那更像
独立的内存泄漏，请单独开 issue”。同一线程有人对比：同一网格用 Glide 加载时“明显更少 jank 帧、更流畅”。
（issue 原文与评论，2026-08-27 经 `gh` 读取。）
→ 对我们的含义：当 Compose 侧（key / contentType / @Immutable / release 度量）都已做对，**刷新网格时剩余的可见卡顿来源
通常是图片解码与缓存管理（内存翻倍、巨图解码、每项新建请求）**，这正是 §5.6 的 Coil 官方建议（共享 ImageLoader、
宽高比占位、别让异步装载把 0 尺寸项塞进首轮测量、避免每帧新分配对象）要解决的；调试时务必先回到 release 再下结论。

### 5.6 Coil 封面加载的官方建议（scroll 性能相关）

全部来自 Coil 官方文档（coil-kt.github.io/coil，2026-08-27 访问）：

- **共享单个 `ImageLoader`**：“Coil 在‘整个 App 只创建一个 `ImageLoader` 并在各处共享’时表现最好，因为每个
  `ImageLoader` 都有自己独立的 memory cache、disk cache 和 `OkHttpClient`。”
  （[Image Loaders — Caching](https://coil-kt.github.io/coil/image_loaders/)，官方文档。）
- **先预留尺寸再加载**：“最终图片尺寸在加载完成前未知。**用固定约束或已知的宽高比预留空间**”——与 §1.4“避免 0 像素项”、
  保持加载前后尺寸一致完全对应，也是防止远程分页下 Lazy 布局“首轮组合全部项再丢弃”的关键。
  （[Recipes — “Compose AnimatedContent”](https://coil-kt.github.io/coil/recipes/)，官方文档。）
- **内存命中时跳过淡入动画**：“`rememberAsyncImagePainter` 即使图片已在 memory cache，首帧也是 `State.Empty`；
  用 `state.result.dataSource` 判断内存命中、跳过动画。”
  （[Recipes](https://coil-kt.github.io/coil/recipes/)，官方文档。）
- **懒列表里用简单 crossfade 而非 AnimatedContent**：“`AnimatedContent` 比 painter crossfade 昂贵得多，动画结束前还持有旧图；
  **懒列表中优先 `ImageRequest.Builder.crossfade` 或简单淡入**。”
  （[Recipes](https://coil-kt.github.io/coil/recipes/)，官方文档；注意官方没有“禁用 crossfade”的说法，见 §7 局限。）
- **预加载与缓存策略**：官方 FAQ 演示把大图预载到 disk cache（`memoryCachePolicy(CachePolicy.DISABLED)` 跳过解码/内存缓存）
  的做法，可作“后台预热封面”的参考（[FAQ — Preloading](https://coil-kt.github.io/coil/faq/)，官方文档）。
- **平台定位**：Coil 3 是“为 Android 和 Compose Multiplatform 设计的图片加载库”
  （[Coil Home](https://coil-kt.github.io/coil/)，官方文档）。

### 5.7 漫画类 App 上游旁证

- [mihonapp/mihon#2808 “Slower loading when the app opens (beta version)”](https://github.com/mihonapp/mihon/issues/2808)
  （open）：用户在大型本地库上打开 App 时“条目全部显示出来要 3–5 秒”。它说明“一次把整个库摆出来再渲染”是漫画
  客户端已知的痛，反证 Paging 分页 + 首屏有限量（Kototoro 已做的 `initialLoadSize = 64`）才是正确方向
  （上游 issue，2026-08-27 经 `gh` 读取）。
- 上游 [KotatsuApp/Kotatsu](https://github.com/KotatsuApp/Kotatsu)（Kototoro 的直接前身）以
  `LazyVerticalGrid`/封面网格为主的库屏，GitHub 搜索未发现直接命中“LazyVerticalGrid/重组合”的 issue；相关感知卡顿多
  以“scroll/lag/图片加载”形态出现（如 [#1716](https://github.com/KotatsuApp/Kotatsu/issues/1716) chapters 滚动），
  佐证该类问题的真正瓶颈在图片管线与滚动状态处理，而非网格组件本身（2026-08-27 搜索）。

## 6. Flow 收集与生命周期：避免列表里的“收集风暴”

- 官方指令：“**用 `collectAsStateWithLifecycle` 收集数据；不要根据生命周期手动起停 Flow 收集**”，这“能省电与资源，
  因为应用进入后台时 Flow 会暂停”；此 API 是“Android 上收集 Flow 的推荐方式”
  （[Lifecycle — “collectAsStateWithLifecycle”](https://developer.android.com/topic/libraries/architecture/lifecycle)、
  [State — “Flow: collectAsStateWithLifecycle()”](https://developer.android.com/develop/ui/compose/state)，2026-08-27 访问）。
- “不要把手动启动/停止”换成 `repeatOnLifecycle`/`LaunchedEffect` 自行管理——官方明确 API 差异
  （同一 Lifecycle 文档，2026-08-27 访问）。
- 屏幕级 StateFlow 用 `stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = …)`
  组合，官方在 hoisting 与 UI 层示例中即用此模式；`collectAsStateWithLifecycle` 在组合中消费（生命周期感知，后台停止收集）
  （[State hoisting — “ViewModels as state owner”](https://developer.android.com/develop/ui/compose/state-hoisting)，2026-08-27 访问）。
- `derivedStateOf` 与 `snapshotFlow` 官方分别“类似 `distinctUntilChanged`”与“不等价则不发射”，Paging 侧副作用示例也显式
  `.distinctUntilChanged()` —— 即“减少冗余发射”是官方推荐的流处理方式
  （[Side-effects](https://developer.android.com/develop/ui/compose/side-effects)、
  [Manage and present loading states](https://developer.android.com/topic/libraries/architecture/paging/load-state)，2026-08-27 访问）。
- 对列表 item 的推论（基于组合作用域与稳定性规则，官方无逐字语句，见 §7 局限）：不要在 item 组合内对每个 item 各自
  `collectAsStateWithLifecycle`/收集 flow；这类“每项收集”会在数据变化时放大重组与协程开销。应把数据在 ViewModel 攒成
  稳定的展示列表，item 只消费同步参数。

## 7. 局限 / 未能确证

- 本次会话 `web_search` 服务不可用（后端抓取失败），权威文档均改为直接抓取确认；GitHub issues 均经已登录 `gh`
  （账号 skepsun，经本地代理 `127.0.0.1:7890`）逐条读取正文与维护者评论核实，未使用网页抓取。
- 官方 Paging 文档页面（v3-paged-data 等）在 2026 年已重构路径（旧 `/paging/v3-paging-data` 等返回 404），本文使用当前的
  `/topic/libraries/architecture/paging/{v3-paged-data, v3-transform, load-state, v3-network-db, v3-overview}` 路径。
- “Lazy 项参数不稳定”官方文档没有独立的“告警”页面；真实形态是 Compose compiler reports 的 `unstable` 标注与
  Layout Inspector 重组计数（见 §3.5），未发现单独的编译期 warning 文档。
- Coil 官方文档没有“滚动画廊必须关 crossfade”的禁令；官方 Recipes 只明确“懒列表里优先 `ImageRequest.crossfade` 而非
  `AnimatedContent`”。cover 卡选择是否 crossfade 应以 release 实测为准。
- LazyVerticalStaggeredGrid 目前缺少官方专项性能/坑文，本文仅引用其 API 与 Lists 文档中的定位，以及官方示例
  （nowinandroid）中的实际用法。
- androidx Foundation 的 Lazy grid 性能问题主要登记在 issuetracker.google.com（`gh` 在 `androidx/androidx` 仓库里按
  “LazyVerticalGrid”搜 issue 为 0 条，佐证这一点），故正文“真实 issue 线程”取自已逐条验证的
  JetBrains/compose-multiplatform（#1992/#3458/#2287/#5623）与 coil-kt/coil（#1866）线程（§5）。
- 本文结论面向“数百至数千张封面卡”的常规 `LazyVerticalGrid`；极端超大库（上万项）翻页、RemoteMediator 离线库这类
  “源数据→本地缓存→UI”链路超出本次范围，仅按官方 RemoteMediator 文档给出方向。

## 来源清单（均于 2026-08-27 访问）

| 主题 | 来源 |
| --- | --- |
| Lazy lists & grids / key / contentType / span / 占位 / Paging 接入 | https://developer.android.com/develop/ui/compose/lists |
| Compose 性能最佳实践（keys / derivedStateOf / 延迟读取 / 反向写） | https://developer.android.com/develop/ui/compose/performance/bestpractices |
| Stability in Compose | https://developer.android.com/develop/ui/compose/performance/stability |
| Diagnose stability issues（compiler reports / Layout Inspector） | https://developer.android.com/develop/ui/compose/performance/stability/diagnose |
| Fix stability issues（@Stable/@Immutable / 集合 / 多模块 / wrapper） | https://developer.android.com/develop/ui/compose/performance/stability/fix |
| Strong skipping mode | https://developer.android.com/develop/ui/compose/performance/stability/strongskipping |
| Side effects（derivedStateOf / snapshotFlow） | https://developer.android.com/develop/ui/compose/side-effects |
| State hoisting（LazyListState 稳定化 / stateIn） | https://developer.android.com/develop/ui/compose/state-hoisting |
| State（collectAsStateWithLifecycle 推荐） | https://developer.android.com/develop/ui/compose/state |
| KDoc @Immutable / @Stable | https://developer.android.com/reference/kotlin/androidx/compose/runtime/Immutable ；https://developer.android.com/reference/kotlin/androidx/compose/runtime/Stable |
| LazyVerticalGrid / StaggeredGrid / LazyGridScope API | https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/grid/LazyVerticalGrid.composable ；…/LazyGridScope ；…/staggeredgrid/LazyVerticalStaggeredGrid.composable |
| Paging Overview / Load & display paged data / Transform / Load states / Network+DB | https://developer.android.com/topic/libraries/architecture/paging/v3-overview ；…/v3-paged-data ；…/v3-transform ；…/load-state ；…/v3-network-db |
| Paging Releases（KMP 说明） | https://developer.android.com/jetpack/androidx/releases/paging |
| LazyPagingItems API | https://developer.android.com/reference/kotlin/androidx/paging/compose/LazyPagingItems |
| Lifecycle（collectAsStateWithLifecycle） | https://developer.android.com/topic/libraries/architecture/lifecycle |
| Coil Image Loaders / Recipes / FAQ / getting_started | https://coil-kt.github.io/coil/image_loaders/ ；…/recipes/ ；…/faq/ ；…/getting_started/ |
| 官方示例仓库 android/snippets | https://github.com/android/snippets/blob/main/compose/snippets/src/main/java/com/example/compose/snippets/lists/LazyListSnippets.kt ；…/performance/PerformanceSnippets.kt |
| 官方示例应用 android/nowinandroid（网格写法） | https://github.com/android/nowinandroid/blob/7d45eae4f8720a0c77f507712ba2437ff974b6ed/feature/foryou/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/foryou/impl/ForYouScreen.kt |
| 真实 issue 线程（Compose 网格/重组） | https://github.com/JetBrains/compose-multiplatform/issues/1992 ；…/3458 ；…/2287 ；…/5623 |
| 真实 issue 线程（Coil 懒网格 jank） | https://github.com/coil-kt/coil/issues/1866 |
| 上游旁证（漫画客户端库加载） | https://github.com/mihonapp/mihon/issues/2808 ；Kotatsu 上游 https://github.com/KotatsuApp/Kotatsu/issues/1716 |
