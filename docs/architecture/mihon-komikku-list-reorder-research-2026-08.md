# Mihon / Komikku Compose 列表与拖拽排序研究（2026-08）

> 研究基线：Mihon 本地 `../mihon`，提交 `0da73df9385efeb72ef78081fddac45d83710c24`；
> Komikku 本地 `../komikku`，提交 `936e25bf99af29e059f06c4ad613ab62df9ae53e`；
> Kototoro 当前提交 `dec0ef781644245f6937dc1cafc8ca84963fe08e`。
>
> 研究范围：Compose `LazyColumn` / `LazyVerticalGrid` 的组织方式，拖拽排序依赖及实际用法，
> 状态更新与持久化、边缘自动滚动、手柄/触觉/动画、可访问性和测试。证据仅来自本地源码和
> Calvin-LL/Reorderable 官方仓库。

## 结论摘要

1. Mihon 和 Komikku 没有自行维护一套 Compose 拖拽命中与滚动算法。Mihon 使用
   `sh.calvin.reorderable:reorderable:3.0.0`，Komikku 使用 `3.1.0`；分类排序、迁移来源排序，
   以及 Komikku 的 Feed 排序均基于该库。
2. 两个项目仍直接使用 Compose Lazy 容器，但将快速滚动条封装为
   `FastScrollLazyColumn` / `FastScrollLazyVerticalGrid`，并在复杂异构列表中普遍使用稳定
   `key`、`contentType`、`animateItem()` 或禁用淡入淡出的 `animateItemFastScroll()`。
   这是一种“薄封装基础设施 + 领域页面保留 DSL”的做法，不是重新造 LazyColumn。
3. Reorderable 3.1.0 原生覆盖 Lazy list/grid/staggered grid，负责拖动偏移、目标命中、边缘
   自动滚动、跨屏移动、拖动结束回位、立即拖动/长按手柄和非排序项动画。它不替应用决定
   数据模型、持久化时机、触觉或 TalkBack 动作。
4. Mihon/Komikku 的使用方式不是全部都应照搬：其分类与 Feed 排序在每次跨项时立即调用领域
   持久化；手柄没有可访问名称，也没有触觉回调；本地源码未发现专项 UI/排序测试。
5. Kototoro 的最高优先级迁移点是 `FavouriteCategoriesScreen.kt`。该页已手写偏移、命中、
   zIndex、触觉和提交逻辑，但只能命中当前 `visibleItemsInfo`，没有边缘自动滚动；同时拖动期间
   上游状态可覆盖本地顺序。应以 Reorderable 替换手势/命中/滚动/偏移动画，保留当前“拖动
   结束后一次事务提交”的持久化边界。
6. 主导航与自定义 Space 的显式上移/下移按钮可作为后续拖拽增强点，但应保留为 TalkBack
   路径或转换为 `customActions`。底部导航的横向拖动是 scrub selection，不是排序，不能迁移。

## 1. 两个对照项目怎样组织普通列表

### 1.1 对 Lazy 容器做薄封装

Mihon 的 `ScrollbarLazyColumn` 只是给原生 `LazyColumn` 加绘制型滚动条，所有 state、padding、
layout 和 `LazyListScope` 原样透传；`FastScrollLazyColumn` 同理，在外层组合
`VerticalFastScroller`。证据见
[`LazyList.kt:23-53`](/Users/sunchuxiong/kotatsu_demo/mihon/presentation-core/src/main/java/tachiyomi/presentation/core/components/LazyList.kt:23)
与
[`LazyList.kt:60-86`](/Users/sunchuxiong/kotatsu_demo/mihon/presentation-core/src/main/java/tachiyomi/presentation/core/components/LazyList.kt:60)。

Grid 采用相同结构：`FastScrollLazyVerticalGrid` 接收 `LazyGridScope`，外层处理快速滚动，内层仍是
原生 `LazyVerticalGrid`，见
[`LazyGrid.kt:18-57`](/Users/sunchuxiong/kotatsu_demo/mihon/presentation-core/src/main/java/tachiyomi/presentation/core/components/LazyGrid.kt:18)。
Komikku 继承了同一套 presentation-core 结构。

这套边界值得 Kototoro 借鉴：滚动条、窗口 padding 等跨页面能力适合组件化；列表 item 的 key、
content type、分页状态和领域交互仍留在页面 DSL，避免制造覆盖全部列表场景的胖组件。

### 1.2 key、contentType 和快速滚动动画

Mihon 的异构列表会显式区分 header/item 的 `contentType` 并设置稳定 key。例如扩展列表在
[`ExtensionsScreen.kt:168-216`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/presentation/browse/ExtensionsScreen.kt:168)
分别为 header 和 extension item 设置 content type/key；来源列表在
[`SourcesScreen.kt:56-83`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/presentation/browse/SourcesScreen.kt:56)
按 UI model 类型区分复用槽并添加 `animateItem()`。

快速滚动列表使用 `animateItemFastScroll()`，其实现保留 placement 动画但关闭 fade-in/fade-out，
规避快速跳转时的大面积淡入淡出，见
[`FastScrollAnimateItem.kt:6-9`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/presentation/util/FastScrollAnimateItem.kt:6)。

这不是绝对统一的规则。例如 Mihon/Komikku 的 `LibraryList` 只设置固定 `contentType`，没有显式
item key，见
[`LibraryList.kt:27-44`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt:27)。
因此应学习“异构/动态列表明确身份与复用类型”的原则，而不是把对照仓库当前代码当成无例外模板。

### 1.3 list/grid 共享领域 item，而不是复制业务逻辑

Library 的 list 与 grid 入口分别使用 `FastScrollLazyColumn` 和 `FastScrollLazyVerticalGrid`：

- List：[`LibraryList.kt:27-72`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt:27)；
- Grid 容器：[`LazyLibraryGrid.kt:15-28`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/presentation/library/components/LazyLibraryGrid.kt:15)；
- Komikku 保持同一结构，只在 item badge/cover model 内添加 fork 特性，见
  [`LibraryList.kt:41-78`](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt:41)。

来源浏览则直接消费 Paging 的 `LazyPagingItems`，在 prepend/refresh/append 对应位置插入 loading
item，list 与 grid 共用相同 load-state 语义，见
[`BrowseSourceList.kt:25-48`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceList.kt:25)
和
[`BrowseSourceCompactGrid.kt:29-55`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceCompactGrid.kt:29)。

## 2. Reorderable 依赖与官方能力

### 2.1 依赖版本

| 项目 | 坐标/版本 | 声明与接入 |
| --- | --- | --- |
| Mihon | `sh.calvin.reorderable:reorderable:3.0.0` | [`libs.versions.toml:67`](/Users/sunchuxiong/kotatsu_demo/mihon/gradle/libs.versions.toml:67)，[`app/build.gradle.kts:273`](/Users/sunchuxiong/kotatsu_demo/mihon/app/build.gradle.kts:273) |
| Komikku | `sh.calvin.reorderable:reorderable:3.1.0` | [`libs.versions.toml:68`](/Users/sunchuxiong/kotatsu_demo/komikku/gradle/libs.versions.toml:68)，[`app/build.gradle.kts:294`](/Users/sunchuxiong/kotatsu_demo/komikku/app/build.gradle.kts:294) |
| Kototoro | 当前未声明 | [`libs.versions.toml:1`](/Users/sunchuxiong/kotatsu_demo/Kototoro/gradle/libs.versions.toml:1)，Compose 依赖区见 [`app/build.gradle:417`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/build.gradle:417) |

官方 v3.1.0 README 给出的当前坐标同为 `sh.calvin.reorderable:reorderable:3.1.0`，见
[官方安装说明](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/README.md#version-catalog)。
v3.1.0 发布于 2026-04-20，见
[官方 Release](https://github.com/Calvin-LL/Reorderable/releases/tag/v3.1.0)，许可证为
[Apache-2.0](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/LICENSE)。

对 v3.0.0 与 v3.1.0 tag 的源码 diff 显示，两个版本间主要是构建/平台配置与 README 更新，
`reorderable/src` 没有运行时代码差异。因此 Mihon 3.0.0 与 Komikku 3.1.0 在本文涉及的核心拖拽
API 和算法上等价；Kototoro 若引入，应选当前 3.1.0，不必为复刻 Mihon 固定到 3.0.0。

### 2.2 官方支持范围与职责边界

官方列出的容器包括 `LazyColumn`、`LazyRow`、vertical/horizontal grid、staggered grid，另支持
非 lazy 的 `Column`/`Row`；支持不同尺寸 item、禁用部分 item、header/footer、立即或长按拖动、
子组件手柄和 Lazy 容器的边缘自动滚动，见
[README Features](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/README.md#features)。

最小 LazyColumn 结构是：同一个稳定 key 同时传给 Lazy `items(key=...)` 和
`ReorderableItem(key=...)`，在 `ReorderableCollectionItemScope` 内给手柄加
`Modifier.draggableHandle()`，见
[官方 LazyColumn 示例](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/README.md#simple-example)。

库负责：

- `ReorderableItem` 为拖动项添加 zIndex 和 graphics translation，为普通项默认添加
  `animateItem()`，见
  [ReorderableLazyList.kt#L272-L328](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/reorderable/src/commonMain/kotlin/sh/calvin/reorderable/ReorderableLazyList.kt#L272-L328)；
- 根据拖动矩形跨过候选 item 中心点执行命中，并用 mutex 防止 `onMove` 并发重入，见
  [ReorderableLazyCollection.kt#L280-L315](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/reorderable/src/commonMain/kotlin/sh/calvin/reorderable/ReorderableLazyCollection.kt#L280-L315)；
- 手柄中心进入阈值区后启动方向滚动，距离边缘越近速度倍率越高，并在滚动过程中把拖动项继续
  移向新出现的 item，见
  [ReorderableLazyCollection.kt#L417-L480](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/reorderable/src/commonMain/kotlin/sh/calvin/reorderable/ReorderableLazyCollection.kt#L417-L480)；
- `rememberReorderableLazyListState` 默认按 viewport 大小构造 scroller，并允许配置
  `scrollThreshold`、`scrollThresholdPadding` 和自定义 scroller，见
  [ReorderableLazyList.kt#L123-L188](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/reorderable/src/commonMain/kotlin/sh/calvin/reorderable/ReorderableLazyList.kt#L123-L188)；
- `draggableHandle()` 默认立即拖动，`longPressDraggableHandle()` 使用长按检测器；两者都暴露
  `onDragStarted` / `onDragStopped` 和 `interactionSource`，见
  [ReorderableLazyCollection.kt#L680-L795](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/reorderable/src/commonMain/kotlin/sh/calvin/reorderable/ReorderableLazyCollection.kt#L680-L795)。

库不负责：

- 改写应用数据；`onMove` 必须在返回前同步更新列表，否则官方明确指出会闪烁/跳动，见
  [README FAQ](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/README.md#when-onmove-is-called-to-move-items-the-dragging-item-flickersjumpsflashes)；
- 决定何时写数据库/Preferences；
- 自动触发触觉。官方完整示例是在 `onMove`、`onDragStarted`、`onDragStopped` 中由应用调用
  `LocalHapticFeedback`，见
  [README haptic example](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/README.md#complete-example-with-haptic-feedback)；
- 自动提供 TalkBack 上移/下移语义。官方建议没有其他按钮时为 item 添加
  `customActions`，并对拖动手柄 `clearAndSetSemantics`，见
  [README Accessibility](https://github.com/Calvin-LL/Reorderable/blob/v3.1.0/README.md#accessibility)。

## 3. Mihon 的实际 Reorderable 用法

### 3.1 分类排序

`CategoryScreen` 的实现流程如下：

1. 用 `categories.toMutableStateList()` 建立可即时移动的 UI 镜像；
2. `rememberReorderableLazyListState` 的 `onMove` 立即 remove/add；
3. 同步调用 `onChangeOrder(item, to.index)`；
4. 上游 categories 再发射时，仅在 `!isAnyItemDragging` 时重建 UI 镜像；
5. Lazy item 与 `ReorderableItem` 使用同一个 `category-$id` key。

证据见
[`CategoryScreen.kt:85-119`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/presentation/category/CategoryScreen.kt:85)。

手柄是 item 内的 `Icons.Outlined.DragHandle`，直接使用 `Modifier.draggableHandle()`，见
[`CategoryListItem.kt:44-50`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/presentation/category/components/CategoryListItem.kt:44)。
该页面没有使用 `isDragging` 参数做 elevation/color，也没有给手柄内容描述、触觉回调或 TalkBack
custom actions。也就是说，它采用了成熟的拖动/自动滚动内核，但 UI 反馈和可访问性并不完整。

### 3.2 固定 header/多分组场景：按 key 映射，不信任 index

迁移来源配置同一个 LazyColumn 中包含 selected header、selected items、available header 和
available items。它没有直接拿 `from.index` / `to.index` 改领域列表，而是根据 `from.key` /
`to.key` 在 `selectedSources` 中重新查找索引，找不到即拒绝，见
[`MigrationConfigScreen.kt:157-162`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/mihon/feature/migration/config/MigrationConfigScreen.kt:157)。

只有 selected 分组且 item 数量大于 1 时 `enabled=true`，见
[`MigrationConfigScreen.kt:189-202`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/mihon/feature/migration/config/MigrationConfigScreen.kt:189)
和
[`MigrationConfigScreen.kt:237-255`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/mihon/feature/migration/config/MigrationConfigScreen.kt:237)。
这是 Kototoro 分类页存在固定 “All categories” 行时应直接采用的模式。

迁移来源顺序保存在 `SourcePreferences.migrationSources()`；UI move 先更新 ScreenModel，
`updateSources()` 随即调用 `saveSources()`，见
[`MigrationConfigScreen.kt:333-340`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/mihon/feature/migration/config/MigrationConfigScreen.kt:333)
与
[`MigrationConfigScreen.kt:402-416`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/mihon/feature/migration/config/MigrationConfigScreen.kt:402)。

### 3.3 分类持久化

UI 每跨过一个 item 都会调用 `CategoryScreenModel.changeOrder()`；ScreenModel 启动协程调用
`ReorderCategory.await()`，见
[`CategoryScreenModel.kt:68-74`](/Users/sunchuxiong/kotatsu_demo/mihon/app/src/main/java/eu/kanade/tachiyomi/ui/category/CategoryScreenModel.kt:68)。

领域 interactor 使用 mutex 串行化并发移动，在 non-cancellable context 中重新读取完整分类列表，
remove/add 后为所有分类生成新 order，再批量提交 repository，见
[`ReorderCategory.kt:12-44`](/Users/sunchuxiong/kotatsu_demo/mihon/domain/src/main/java/tachiyomi/domain/category/interactor/ReorderCategory.kt:12)。

这保证了连续 move 不会并发写乱，但拖过多个 item 会多次读全表/重写全序列。Kototoro 已有拖动
结束一次提交，不应退化为这一写入模型。

## 4. Komikku 的增量实现

Komikku 保留 Mihon 的分类与迁移来源排序，并把依赖升级到 3.1.0。除此以外还将同一模式用于
全局 Feed 和单来源 Feed 排序：

- 全局 Feed：本地 mutable state 即时 remove/add，非拖动期间才接收上游列表，稳定 key 同时传入
  Lazy item 与 `ReorderableItem`，见
  [`FeedOrderScreen.kt:40-75`](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/presentation/browse/FeedOrderScreen.kt:40)；
- 单来源 Feed：将 Scaffold padding 同时传给 LazyColumn 和 reorder state，使边缘滚动阈值与
  实际可视内容一致，见
  [`SourceFeedOrderScreen.kt:60-98`](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/presentation/browse/SourceFeedOrderScreen.kt:60)；
- 手柄仍是无 content description 的 `draggableHandle()`，见
  [`FeedOrderListItem.kt:39-45`](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/presentation/browse/components/FeedOrderListItem.kt:39)。

Feed 的领域持久化也使用 mutex + non-cancellable context：按 global/source 重新读取列表，更新
每项 `feedOrder` 后批量提交，见
[`ReorderFeed.kt:12-49`](/Users/sunchuxiong/kotatsu_demo/komikku/domain/src/main/java/tachiyomi/domain/source/interactor/ReorderFeed.kt:12)。
ScreenModel 的调用点见
[`FeedScreenModel.kt:226-231`](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/tachiyomi/ui/browse/feed/FeedScreenModel.kt:226)。

## 5. 自动滚动、手柄、触觉、动画与可访问性对照

| 维度 | Reorderable 3.1.0 能力 | Mihon / Komikku 实际使用 | Kototoro 分类页当前实现 |
| --- | --- | --- | --- |
| 边缘自动滚动 | Lazy 容器内置，速度随距边缘距离变化，可配置 threshold/padding/scroller | 通过默认 state 自动获得；Scaffold 场景传入 padding | 无 `scrollBy`；只搜索 `visibleItemsInfo`，无法一次跨屏拖动 |
| item 命中 | 库维护拖动矩形、中心点判定、预测 offset 与 move mutex | 交给库；复杂分组用 key 再映射领域索引 | 手写“最近中心点”算法 |
| 手柄 | `draggableHandle` 或 `longPressDraggableHandle`；支持 enabled、interactionSource、start/stop callback | 使用立即拖动手柄；未接 interactionSource/start/stop | 48dp Icon + `detectDragGestures`，立即拖动 |
| 触觉 | 库不自动提供，官方示例要求应用回调 | 未配置 | start 为 `LongPress`，跨项为 `TextHandleMove` |
| 动画 | 库负责拖动 translation/zIndex、结束回位和非拖动项默认 `animateItem` | item 内又显式加 `animateItem()`；未使用 isDragging 做 elevation/color | 手写 translation、1.02 scale、8dp elevation、背景色和 zIndex |
| 状态同步 | 暴露 `isAnyItemDragging` | 上游发射仅在未拖动时覆盖 UI 镜像 | `LaunchedEffect(items)` 无条件 clear/addAll |
| TalkBack | 官方要求应用提供 custom actions/清理手柄语义 | 手柄 description 为 null，无 move actions | 手柄有“reorder”描述，无 move up/down actions |
| 持久化 | 不负责 | 每次跨项调用领域写入 | 手势结束/取消后若发生移动，一次提交完整顺序 |

Kototoro 当前证据集中在：

- UI 镜像与无条件上游同步：
  [`FavouriteCategoriesScreen.kt:97-105`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/categories/compose/FavouriteCategoriesScreen.kt:97)；
- 稳定 key/contentType、动画和 zIndex：
  [`FavouriteCategoriesScreen.kt:177-235`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/categories/compose/FavouriteCategoriesScreen.kt:177)；
- 手写视觉状态：
  [`FavouriteCategoriesScreen.kt:279-304`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/categories/compose/FavouriteCategoriesScreen.kt:279)；
- 手写手势、可见项命中、触觉和结束/取消提交：
  [`FavouriteCategoriesScreen.kt:325-373`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/categories/compose/FavouriteCategoriesScreen.kt:325)；
- 私有 remove/add 算法：
  [`FavouriteCategoriesScreen.kt:465-469`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/categories/compose/FavouriteCategoriesScreen.kt:465)。

注意：当前 `onDragCancel` 与 `onDragEnd` 一样提交已经发生的移动。这不一定是错误，但迁移时必须
明确产品语义：取消是“停止并保留当前顺序”，还是“回滚到拖动前快照”。Reorderable 不替应用做
这个决定。

## 6. Kototoro 持久化边界：应保留而不是照搬

分类页结束拖动后把完整 snapshot 交给 ViewModel。ViewModel 取消并等待前一次 commit job，从
snapshot 抽取分类 ID，再调用 repository，见
[`FavouritesCategoriesViewModel.kt:85-95`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/categories/FavouritesCategoriesViewModel.kt:85)。

Repository 在单个 Room transaction 中逐项更新 `sort_key`，见
[`FavouritesRepository.kt:420-426`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/domain/FavouritesRepository.kt:420)。
DAO 的所有有效分类查询均按 `sort_key` 排序，写入口为 `updateSortKey`，见
[`FavouriteCategoriesDao.kt:17-30`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/data/FavouriteCategoriesDao.kt:17)
和
[`FavouriteCategoriesDao.kt:55-56`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/data/FavouriteCategoriesDao.kt:55)。

建议保留这条链路。Reorderable 的 `onMove` 只同步更新本地 `SnapshotStateList`；在
`draggableHandle(onDragStopped=...)` 中检测顺序是否变化并提交一次。这样同时满足官方“onMove
返回前列表已更新”的要求，以及 Kototoro 对数据库写入次数的控制。

## 7. 可迁移点与明确排除项

### P0：收藏分类排序

建议替换范围：

1. 引入 Reorderable 3.1.0；用 `rememberReorderableLazyListState(listState, contentPadding)` 创建状态。
2. 每个 `CategoryListModel` 使用 `ReorderableItem`；`AllCategoriesListModel` 保持固定、不可排序。
3. `onMove` 根据 `from.key` / `to.key` 解析 category id，再查 `localItems` 的真实位置。不能直接使用
   Lazy index，因为 index 0 是固定 All row，loading/empty 状态也可能改变布局。
4. 上游 items 同步增加 `!reorderableState.isAnyItemDragging` 门控；拖动结束后再接受数据库回流。
5. 保留当前 scale/elevation/background 和两类触觉，可通过 `isDragging`、`onDragStarted`、
   `onMove`、`onDragStopped` 接回；删除 pointerInput、dragOffset、可见项命中和手动 translation。
6. 保留结束时一次事务提交；不要复制 Mihon/Komikku 的“每次跨项都写全序列”。
7. 在 item 上增加“上移/下移” `customActions`，对手柄使用 `clearAndSetSemantics`；或者保留显式
   上下按钮。拖拽不是完整的无障碍操作路径。

这符合 KISS/DRY：用已经覆盖长列表、反向/RTL、不同 item 尺寸和滚动边界的成熟实现，删除本地
重复算法；同时遵守 YAGNI，不为普通浏览列表引入排序状态。

### P1：主导航配置

当前配置项通过 `forEachIndexed` 全部组合在 LazyColumn 的单个 `item(key="nav_config")` 内，见
[`NavConfigScreen.kt:60-100`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/settings/compose/NavConfigScreen.kt:60)。
每行已有可访问的上移/下移/删除按钮，见
[`NavConfigScreen.kt:143-200`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/settings/compose/NavConfigScreen.kt:143)。

ViewModel 已有通用 `reorder(from,to)`，且 500 ms debounce 后写 Preferences 并重建 MainActivity，见
[`NavConfigViewModel.kt:80-95`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/settings/nav/NavConfigViewModel.kt:80)
与
[`NavConfigViewModel.kt:114-121`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/settings/nav/NavConfigViewModel.kt:114)。

若增强为拖拽，先把配置行改为真正的 keyed Lazy items，再接 Reorderable；保留上下按钮或等价
custom actions。这个改动涉及 SettingsPreferenceGroup 的布局边界，优先级低于分类页。

### P1/P2：自定义 Space

Space 页同样使用显式上下按钮，UI 见
[`SpacesSettingsScreen.kt:230-268`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/settings/compose/SpacesSettingsScreen.kt:230)；
ViewModel 通过交换相邻两个自定义 Space 的 sortKey 持久化，见
[`SpacesSettingsViewModel.kt:79-87`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/settings/compose/SpacesSettingsViewModel.kt:79)。
如接入拖拽，应限制在 custom-space 分区内，并考虑把两次 update 收敛成一个事务型 reorder API。

### P2：来源手动排序，先确认入口

`SourcesListProducer` 在 MANUAL 模式标记 `isDraggable`，搜索态禁用拖拽，见
[`SourcesListProducer.kt:80-118`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/settings/sources/manage/SourcesListProducer.kt:80)。
`SourcesManageViewModel` 已有 pinned/enabled 分组限制与内存 move/save，见
[`SourcesManageViewModel.kt:97-117`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/settings/sources/manage/SourcesManageViewModel.kt:97)
和
[`SourcesManageViewModel.kt:181-190`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/settings/sources/manage/SourcesManageViewModel.kt:181)。
但本次全仓检索未找到 Screen/Route 消费这些拖拽 API，疑似旧 UI 残留。先确认产品入口，再决定是否
接入 Reorderable，避免为不可达路径增加依赖面。

### 明确排除：底部导航拖动

`KototoroBottomNav.kt` 中两处 `detectDragGestures` 用于横向拖动预览/选择导航目的地，松手执行
`onItemSelected`，不改变 items 顺序，见
[`KototoroBottomNav.kt:941-990`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/ui/widgets/KototoroBottomNav.kt:941)
和
[`KototoroBottomNav.kt:1333-1378`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/ui/widgets/KototoroBottomNav.kt:1333)。
Reorderable 的语义是改变集合顺序，不适合替换该 scrub selection 交互。

### 明确排除：普通内容浏览列表

Kototoro 的内容 list/grid/detailed-list 已分别保存 Lazy state，见
[`AppContentListRoute.kt:202-215`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/AppContentListRoute.kt:202)；
三种模式均提供稳定 key/contentType，并复用分页近尾加载逻辑，见
[`KototoroContentListScreen.kt:369-444`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/KototoroContentListScreen.kt:369)、
[`KototoroContentListScreen.kt:460-514`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/KototoroContentListScreen.kt:460)
和
[`KototoroContentListScreen.kt:529-555`](/Users/sunchuxiong/kotatsu_demo/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/KototoroContentListScreen.kt:529)。
这些列表没有用户排序需求，不应因为引入 Reorderable 而改动。

## 8. 测试现状与迁移验收

本地 Mihon/Komikku 的 test 源集未检索到 `Reorderable`、分类拖拽或 Feed 拖拽专项测试；
Reorderable v3.1.0 tag 本身也未包含 Kotlin test 源集。因此采用成熟库可以减少自维护算法，但不
代表应用层无需测试，特别是固定 row、领域 key 映射和持久化时机仍属于 Kototoro 自己的契约。

Kototoro 当前也未发现 `FavouriteCategoriesScreen`、`moveItem`、
`FavouritesCategoriesViewModel.saveOrder()` 或 `FavouritesRepository.reorderCategories()` 专项测试。
迁移至少应覆盖：

1. 纯状态测试：固定 All row 不可移动，category key 到真实 index 的映射正确，跨多项移动结果正确；
2. 同步测试：拖动期间 repository Flow 回流不覆盖本地顺序，结束后能够收敛到持久化结果；
3. 持久化测试：一次拖动只提交一次，Room transaction 后 `observeAll()` 顺序正确；
4. Compose UI 测试：手柄启动/停止、长列表边缘自动滚动、拖动取消语义、非排序 item 不可拖；
5. 可访问性测试：每个可移动 category 都有可执行的 Move Up/Move Down action，首尾边界正确禁用；
6. 设备验证：TalkBack、触觉开关关闭时无异常、窄屏/大字体、RTL，以及系统栏 padding 下的上下
   边缘滚动阈值。

## 9. 推荐实施顺序

1. 先只迁移收藏分类页，保留现有 ViewModel/Repository/DAO；不要同时重构普通内容列表。
2. 增加 key 映射和结束提交测试，再替换 pointerInput/offset/visibleItemsInfo 逻辑。
3. 接回现有视觉反馈与触觉，并补齐 TalkBack custom actions。
4. 在长列表设备测试中调整 `scrollThresholdPadding`；默认值可先用，只有实测不合适再自定义 scroller。
5. 验证 APK/依赖体积增量后，再决定是否将同一模式扩展到导航与 Space 设置。
6. 来源管理仅在确认入口仍有效后处理；底部导航与内容浏览列表保持不动。

这个顺序保持改动单一且可回滚：第一阶段只替换一个已确认有正确性/长列表缺口的手写算法，
不借“统一列表”的名义扩大范围。
