# 大数据列表性能优化交接文档

> 状态：Phase 1 已完成并验证；Phase 2 暂缓。  
> 最后更新：2026-08-19  
> 工作树：`/Users/sunchuxiong/kotatsu_demo/Kototoro`  
> 文档编写时：尚未执行 `git commit`、`git push` 或分支操作；没有卸载应用或清理用户数据。之后的提交须以用户明确授权为前提。

## 1. 背景与目标

本轮针对 Mihon/Tachiyomi SY 备份恢复后的大数据列表做性能整改。实测备份规模约为：

| 数据 | 数量 |
| --- | ---: |
| 漫画/作品 | 6816 |
| 收藏记录 | 6383 |
| 去重后的收藏 entity | 约 6315 |
| 历史记录 | 3232（设备数据库实测 3194） |
| 分类 | 25 |

原始问题集中在四类：

1. 收藏初始查询固定 `limit=1000`，无法完整展示大收藏库。
2. 收藏和历史分页边界落在 projection/raw work 行，分页路径内逐 entity resolve，并在 ViewModel 中重复 `Aggregate -> Content -> Aggregate` 建模。
3. 历史首页预览以 `limit * 15` oversample，首屏实际重建最多 960 条；手写扩容时还会从头重建列表。
4. Compose Paging 在快速滚动和回顶期间存在越界崩溃、加载边界明显以及滚动位置不稳定。

长期目标是采用 Google 官方 Room + Paging 3 + Compose 的数据流：

```text
Room entity/work-level PagingSource
    -> Repository batch mapping
    -> ViewModel PagingData<ListModel>
    -> collectAsLazyPagingItems()
    -> LazyColumn/LazyVerticalGrid
```

分页边界必须是每行一个 entity/work 的展示投影，不能是 projection/raw work_favourites 行。所有排序需要稳定，至少使用业务排序字段加 `entity_id` 次级键。

## 2. 已完成并验证的 Phase 1

### 2.1 收藏与历史的 entity 级 Paging

核心文件：

- `app/src/main/kotlin/org/skepsun/kototoro/core/paging/BatchMappingPagingSource.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/work/domain/WorkAggregateRepository.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/favourites/data/WorkFavouritesDao.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/history/data/WorkHistoryDao.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/list/FavouritesListViewModel.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/history/ui/HistoryListViewModel.kt`

`WorkAggregateRepository` 提供：

- `createFavouritePagingSource(...)`
- `createHistoryPagingSource(...)`

这两个 source 先从 DAO 读取 entity/work 级数据，再用 `BatchMappingPagingSource` 按页批量构建 `WorkAggregate`。分页路径不再对每个 entity 单独调用 `resolveByEntityId`。

通用配置：

```kotlin
PagingConfig(
    pageSize = 64,
    initialLoadSize = 64,
    prefetchDistance = 24,
    enablePlaceholders = false,
)
```

收藏页另有基于设备实测的配置：

```kotlin
PagingConfig(
    pageSize = 64,
    initialLoadSize = 64,
    prefetchDistance = 128,
    enablePlaceholders = false,
)
```

收藏页扩大预取距离的原因见第 5 节。首屏大小仍为 64，没有把冷启动工作量直接放大。

### 2.2 Compose Paging 接入和边界保护

核心文件：

- `app/src/main/kotlin/org/skepsun/kototoro/list/ui/ContentListViewModel.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/AppContentListRoute.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/KototoroContentListScreen.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/history/ui/compose/HistoryScreen.kt`
- `app/src/main/kotlin/org/skepsun/kototoro/main/ui/compose/AppNavGraph.kt`

`ContentListViewModel` 增加可选的：

```kotlin
open val pagingContent: Flow<PagingData<ListModel>>? = null
```

收藏和历史 ViewModel 暴露真实 `PagingData`，UI 使用 `collectAsLazyPagingItems()`，没有把 PagingData 收集成普通 List。列表仍保留：

- grid/list/detailed list 三种模式；
- 稳定 key 和 contentType；
- 选择、多选、删除、收藏、下载、分享、置顶等操作；
- 内容类型、source tag、分类、Space、NSFW、来源 preset 等筛选；
- 历史日期分组和 incognito header。

`KototoroContentListScreen` 对 `peek/get` 做了索引边界保护，并为暂时尚未映射的 Paging 项使用位置占位 key，修复了：

```text
Illegal attempt to access index 64 in ItemSnapshotList of size 64
```

回顶逻辑现在会先立即滚到 index 0，再等待 refresh/append/prepend 全部 idle，下一帧再次滚到 index 0，避免在 Paging 加载中点击收藏/底部导航后停留在第二页或第三页。

### 2.3 收藏查询和聚合去重

已完成的主要方向：

- 收藏不再受最多 1000 条的旧限制。
- 收藏分类计数从逐项 N+1 改为 DAO 投影查询。
- `WorkAggregateRepository.resolveProjectionSet` 使用批量 entity resolve 和批量 manga 查询。
- `ContentDataRepository` 增加当前页 entity/manga 范围的 metadata selection/override 查询。
- 收藏、历史 mapper 通过当前页批量获取 metadata selection 和 manual override。
- `HistoryRepository.getProgress(mangaIds)` 和 `TrackingRepository.getNewChaptersCounts(mangaIds)` 改为批量解析和 DAO 查询。
- 主页历史预览新增严格限制数量的 `observeRecentWithHistory(limit)`，不再使用 `limit * 15` oversample。
- 主页历史预览 Flow 共享并 replay，避免主页多个消费者重复执行同一查询。
- Paging batch transform 放在 `Dispatchers.Default`，避免把聚合工作放在主线程。

### 2.4 主页、Feed 和更新页的已完成局部优化

已完成但不等于 Phase 2 全部结束：

- 主页历史预览从约 7.3 秒降至约 0.64 秒。
- 主页严重跳帧从约 228/284 帧降至约 30/34 帧。
- 主页、更新、推荐摘要范围已限制，避免无界全量读取。
- Feed 顶部更新 lookahead 从 2000 降到有界 200。
- Feed 仍保持约 120 条普通 Flow；原始要求明确“不应直接 Paging”，不要未经基准验证改成 Paging。
- 更新页普通查询的手写 limit 已不再固定为 200，扩容参数可以真正传入查询。
- Feed 和更新页部分 preferred projection resolve 已改为批量 `resolveManyByEntityIds`。

## 3. 当前工作树的真实状态

当前工作树包含本任务的未提交改动，另有新建的 Paging 测试目录。不要使用 `git reset --hard`、`git checkout --` 或批量清理来“整理”工作树；已有修改均属于本任务上下文，必须先阅读再继续。

本轮最后新增的收藏预取改动：

- `core/paging/BatchMappingPagingSource.kt`：新增 `FavouriteLibraryPagingConfig`，`prefetchDistance=128`。
- `favourites/ui/list/FavouritesListViewModel.kt`：收藏 Pager 使用该配置。

更新页 Paging 目前处于“基础设施已写入、ViewModel 尚未接通”的中间状态：

- `TracksDao` 已新增动态 raw-query `PagingSource<Int, TrackEntity>` 入口 `pagingUpdatedContent(...)`。
- `TrackingRepository` 已新增 `createUpdatedPagingSource(...)`，按页批量构建 `ContentTracking`。
- `UpdatesViewModel` 仍主要使用旧的 `Flow<List<ContentTracking>> + limit` UI 路径，尚未把上述 source 接入 `Pager<PagingData<ListModel>>`。
- 因此不能在交接时宣称“更新页 Paging 已完成”。继续开发前先补 ViewModel、日期 separator、空状态和映射缓存测试。

## 4. 设备与日志证据

设备：

```text
serial: ecd4369c
Android 16
机型标识：Xiaomi M332BF
debug applicationId: org.skepsun.kototoro.debug
```

设备数据库统计（保留数据安装）：

```text
manga:             6818
active favourites: 6345
distinct entities: 6315
history:           3194
entity_binding:    13636
entity_preferences: 194
tracks:            0（当次统计时）
```

旧崩溃日志中最关键的异常：

```text
java.lang.IndexOutOfBoundsException:
Illegal attempt to access index 64 in ItemSnapshotList of size 64
at androidx.paging.compose.LazyPagingItems.peek
```

修复后，设备日志出现过多次真实的 Paging `Refresh`、`Append`、`Prepend`，每批 64 条，跨过第 64 项未再出现同类崩溃。

当前日志未发现新的：

- `FATAL EXCEPTION`
- `IndexOutOfBoundsException`
- `OutOfMemoryError`
- `ANR in org.skepsun.kototoro.debug`

设备 Xiaomi ROM 拒绝了 shell 注入滑动：

```text
SecurityException: Injecting input events requires INJECT_EVENTS permission
```

因此快速滑动体验主要依靠人工操作和 `LibraryPaging` 日志验证，不能把 `adb shell input swipe` 当作可靠自动化手段。

## 5. 收藏页为什么仍有“分页感”

最后一次设备日志显示，收藏每个 64 条批次的主要耗时约为：

```text
favourites-aggregate Append: 约 259–349 ms，部分批次约 400–667 ms
favourites-ui Append:        约 313–487 ms，部分批次约 500–806 ms
```

这不是单纯的 SQLite 读取延迟。每页还会执行：

- entity/projection 解析；
- 作品代表 projection 选择；
- 分类、历史、统计、tracking 的批量关联；
- metadata selection 和 manual override 查询；
- 多投影 entity 分组；
- `ContentListMapper` UI 模型创建。

因此单纯取消 Paging、一次构建全部 6300 个富 `WorkAggregate`，很可能重新造成数秒到几十秒的启动计算、内存峰值和 GC 抖动。

Komikku 类应用可能采用的是另一种策略：一次读取轻量、扁平的 entity 行（ID、标题、封面 URL、时间等），先让列表拥有完整滚动范围，再由图片库和可见窗口逻辑异步补充封面/徽章/进度。两者不能仅凭“看起来没有分页”判断为同一种实现。

当前采用的折中方案是收藏首屏仍加载 64 条，但将预取距离提高到 128，让后续约两页在用户接近边界前后台加载。该方案已经有所缓解，但不能保证在极快 fling、低端设备或聚合耗时抖动时完全无感。

## 6. 暂缓的 Phase 2 清单

### 6.1 更新页真正 Paging

建议继续顺序：

1. 在 `UpdatesViewModel` 建立包含 filter、group tab、source tags、grouping、list mode、Space 的稳定参数对象。
2. 使用 `Pager(config = LargeLibraryPagingConfig)` 和 `repository.createUpdatedPagingSource(...)`。
3. 每页批量完成 `ContentTracking -> UpdateGroup -> ListModel`，禁止恢复逐 entity `resolveByEntityId`。
4. 用 `insertSeparators` 做日期 header，不要在每页内部直接生成 header；否则同一日期跨页时会出现重复 header。
5. 使用合并而不是覆盖的 grouped ID 映射，保证已加载页上的删除、选择、entity details 仍可用。
6. 补首屏/append、稳定 entity ID、日期跨页和空筛选结果测试。

Tracks SQL 必须保持稳定排序，建议：

```sql
<pinned sort> DESC,
last_chapter_date DESC,
tracks.entity_id ASC,
tracks.manga_id ASC
```

不要仅凭猜测添加索引。先用真实数据库和 `EXPLAIN QUERY PLAN` 检查查询计划，再决定索引是否必要。

### 6.2 更新页映射的已知风险

当前 `UpdatesViewModel` 的 `aggregateByEntity()` 仍有按 entity 调用 `workResolver.resolveByEntityId` 的旧路径。真正接通 Paging 时应改为：

- 优先使用 `ContentTracking.preferredLocalMangaId`；或
- 对当前页 entity IDs 一次调用 `resolveManyByEntityIds`。

不能在每个 Paging item 中恢复 N+1 resolve。

### 6.3 Feed

Feed 日志规模约 120 条，当前保持普通有界 Flow 是合理的。只需要继续验证：

- preferred projection 是否完整批量化；
- 顶部更新查询 200 条是否足够且不会重复订阅；
- Feed 的更新/推荐摘要是否与主页重复聚合。

除非有真实规模和滚动基准，不要把 Feed 直接改成 Paging。

### 6.4 主页更新与推荐

主页目前已限制摘要规模，但主页仍统一汇总历史、更新、推荐状态。后续如果继续卡顿，应按可见性拆分 Flow：

- 页面不可见时不要启动完整推荐/更新聚合；
- 共享同一批量查询结果，避免 Home、Updates、Feed 各自重复订阅；
- 保持明确 loading/empty/data 状态，不能用 `emptyList()` 暂时代替 loading。

### 6.5 本地页

原始计划要求让 `local_index` 成为读取主路径，避免每次扫描文件系统。本轮没有扩散处理本地页。后续应先确认：

- local_index 的写入、失效和恢复时机；
- 读取是否能按 entity/work 级分页；
- 文件系统扫描是否只作为索引缺失时的后台修复路径。

### 6.6 恢复分块进度

恢复任务的分块进度、断点续传和大备份事务边界仍未处理。不要把备份恢复改动与列表 Paging 混在同一轮中；需要单独设计可恢复 checkpoint、幂等写入和进度 UI。

## 7. 测试与验证记录

最近通过的命令：

```bash
./gradlew :app:testDebugUnitTest --no-daemon \
  --tests "org.skepsun.kototoro.core.paging.BatchMappingPagingSourceTest" \
  --tests "org.skepsun.kototoro.history.ui.HistoryListViewModelPagingTest" \
  --tests "org.skepsun.kototoro.work.domain.WorkAggregateSpaceQueryTest"

./gradlew :app:compileDebugKotlin --no-daemon
./gradlew :app:assembleDebug --no-daemon
git diff --check
```

最近一次结果：

- `:app:compileDebugKotlin`：`BUILD SUCCESSFUL`。
- 聚焦单元测试：`BUILD SUCCESSFUL`。
- `:app:assembleDebug`：`BUILD SUCCESSFUL`。
- `git diff --check`：通过。
- APK：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。
- 安装：`adb -s ecd4369c install -r "app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"`，成功。

已有测试位置：

- `app/src/test/kotlin/org/skepsun/kototoro/core/paging/BatchMappingPagingSourceTest.kt`
- `app/src/androidTest/kotlin/org/skepsun/kototoro/core/paging/WorkPagingDaoTest.kt`
- `app/src/test/kotlin/org/skepsun/kototoro/history/ui/HistoryListViewModelPagingTest.kt`

仍缺少的覆盖：

- 收藏 `FavouriteLibraryPagingConfig` 的预取行为测试；
- 更新页 Paging 首批/append 唯一 entity 测试；
- 更新日期 separator 跨页不重复测试；
- 过滤后页为空时的 UI 状态测试；
- 真实 6500 收藏/3200 历史生成夹具下的首屏耗时和滚动基准。

## 8. 接手者操作顺序

1. 在 `/Users/sunchuxiong/kotatsu_demo/Kototoro` 执行 `git status --short`，确认不要覆盖现有用户改动。
2. 阅读本文件和 `CLAUDE.md`，确认使用 Groovy DSL、JDK 17、Room KSP 约束。
3. 先运行现有聚焦测试和 `:app:compileDebugKotlin`，确认基线。
4. 如果继续更新页，先完成 `UpdatesViewModel` 的真正 Pager 和 focused tests，再运行设备安装验证。
5. 每次设备验证使用 `LibraryPaging` 标签收集日志：

   ```bash
   adb -s ecd4369c logcat -c
   adb -s ecd4369c logcat -v threadtime -s LibraryPaging:D '*:S'
   ```

6. 不要卸载 debug 应用、清空数据库或删除旧 ACRA 日志；这些数据用于复现大规模场景和崩溃回归。
7. 完成一个可编译、可测试、可人工复测的小步后再进入下一页，避免同时修改 Reader、播放器、本地索引和恢复流程。

## 9. 设计决策摘要

### 保留

- Room entity/work 级 Paging。
- 每页批量 resolve、批量 metadata/override、批量 UI model mapping。
- `PagingData` 直接流向 Compose `LazyPagingItems`。
- 稳定 entity ID 次级排序键。
- Feed 继续有界普通 Flow。
- 真实设备日志和大规模夹具驱动的性能判断。

### 暂不做

- 不直接全量构建所有富 `WorkAggregate`。
- 不把 PagingData 收集为普通 List。
- 不凭猜测添加索引。
- 不把 Feed、本地页、恢复分块和 Reader/播放器改动混入当前轮。
- 不主动执行 push 或分支操作；不得覆盖或清理用户已有改动。提交仅在用户明确授权后进行。
