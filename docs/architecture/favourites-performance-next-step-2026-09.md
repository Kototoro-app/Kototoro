# 收藏性能下一步：Baseline Profile vs 历史/更新/订阅轻量行优化（2026-09）

> 状态：**选项 A 已实施**（2026-08-27，test-first，见 §0.1）；**选项 B（Baseline Profile）暂缓**（用户指示“这个可以先不管”）
> 日期：2026-09
> 分支：`devel`；实施提交：`perf(work): lightweight history paging row, drop tracking aggregate N+1`（本文档随该提交落地）；基线：`10598b6ce`（`perf(favourites): paginate library with lightweight row projection`）
> 关联文档：`paging3-favourites-grid-research-2026-09.md`、`favourites-page-refactor-plan-2026-08.md`、`large-library-performance-handoff-2026-08.md`
> 方法：仓库源码为一手证据（标注 `文件:行号`）；外部事实只使用 developer.android.com / androidx 官方文档（本会话通过 `android docs fetch` 逐条抓取核实）。

---

## 0.1 实施记录（2026-08-27，选项 A 落地）

按 §5 顺序（历史 → 更新 → 订阅）以 **test-first** 实施并一次提交；JVM 单测全量 1970 项（仅 3 个既有 style-token 失败，与本改动无关），实机 DAO 测试 12/12（`WorkPagingDaoTest` 10 + `SpaceWorkDaoTest` 2）全绿。

1. **历史页（完整轻量投影行）**
   - `WorkHistoryDao.pagingSource` 改为返回 `HistoryLibraryPagingRow`（`history/data/HistoryLibraryPagingRow.kt`）：单条 JOIN 语句投影 `wh.*` + `preferred_local_manga_id` + `display_*`（展示 manga，锚点即 `COALESCE(preferred, anchor)`）+ `tracking_*` 摘要（tracking 子查询扩为 anchor/last_chapter/new_chapters/last_check/last_chapter_date）。
   - `WorkAggregateRepository.buildHistoryPagingAggregates`（`createHistoryPagingSource` 的新映射）替代每页 `resolveProjectionSet`：并行 bindings + categories + entity 内容类型批量查询，display 投影从嵌入行的达成查（`findWithTagsByIds` 一页一批，历史详表要标签）。
   - 行为保真点：**Novel/Video 芯片依赖持久化内容类型**（`HistoryListViewModel.kt:374` 的 `typeMatches`），故 `contentType = contentTypesById[anchor] ?: contentTypesByEntityId[entity]`（与旧 `buildHistoryAggregates` 一致），**不是** display 投影类型；`aggregate.categories` 仍需填充（`HistoryRepository.favouriteCategoryIds` 支撑 FAVORITE 快捷筛选）。
   - 测试：`WorkPagingDaoTest.historyPageCarriesDisplayLocalMangaAndTrackingSummary`（实机，投影/追踪逐字段断言）+ `WorkAggregateHistoryPagingTest`（MockK：字段正确 + `workResolver.resolveManyByEntityIds` / `historyDao.findByEntityIds` 零调用，防 N+1 回归）。

2. **更新页 + 订阅 Feed（消除 N+1 与无用批量）**
   - 关键发现：`buildTrackingAggregates` 的全部下游（`TrackingRepository` 的 `createUpdatedPagingSource` / `observeUpdatedContent` / `observeAllTracks` / `getTracks`）都只经 `toContentTracking()` 消费 **display + identity + tracking**；**history / favourite / stats / categories 在追踪路径上从不被消费**。
   - `buildTrackingAggregates` 重写：tracking 摘要直接来自手中 `tracks`（`groupBy(entityId) + toWorkTrackingSummary`，不重查 `tracksDao`）；删除循环内 `historyDao.find` + `favouritesDao.findActiveForEntity`（每页 64×2 = 128 单行查询 → 0）；删除 `findStatsByEntityId` / `findCategoriesByEntityId` 两个无用批量。
   - 因此**不需要新增** `WorkFavouritesDao.findActiveForEntities` 批量（最初计划 B，经证据修正为“直接删除”）。
   - 测试：`WorkAggregateTrackingTest`（MockK：`historyDao.find` / `favouritesDao.findActiveForEntity` / `tracksDao.findByEntityIds` 零调用 + 字段正确）。

> 未做（后续候选）：把 display manga 嵌入 `TracksDao.pagingUpdatedContent` / `observeContentImpl`（需改 `MangaQueryBuilder` 的 JOIN 能力，风险较高）；当前更新/Feed 仍走 `resolveProjectionSet`（本身已并行批量），N+1 已消除，收益最大的一部分已兑现。

---

## 0. 本次复核（2026-08-27）新增的修正与事实

> 本文初稿由一次早期会话写成；2026-08-27 按任务要求对一手来源做了二次核验，以下是与初稿/背景假设不一致、或初稿缺失的关键事实。

1. **“ConfigurationProfileRule”不是当前官方 API —— 修正上一版计划的记忆。** 当前 `developer.android.com/topic/performance/baselineprofiles/create-baselineprofile` 与 androidx-main 源码（`benchmark/benchmark-macro-junit4/src/main/java/androidx/benchmark/macro/junit4/BaselineProfileRule.kt`）定义的生成 API 都只有 **`androidx.benchmark.macro.junit4.BaselineProfileRule`**；对 `androidx-main` `benchmark/**` 全树检索未发现任何 `ConfigurationProfileRule` 符号。后续如出现该 API，以 Benchmark 发布说明为准。
2. **AGP 9 插件兼容性约束（本仓库 AGP 9.3.1 直接相关）。** 官方/JetBrains AGP 9 插件兼容表：`androidx.baselineprofile` **低于 1.5.0-alpha01 需设置 `android.newDsl=false`** 才能在 AGP 9 使用；本仓库 `gradle.properties` 未设该标志，因此接入时必须用 `androidx.baselineprofile` ≥ 1.5.0-alpha01（Benchmark 发布说明当前最新为 **1.5.0-rc02**）或 AGP 内置插件。
3. **官方“最佳情况”量级有实测样例可引**：Google 文档示例（Now in Android @ Pixel 7，冷启动 TTID）`CompilationMode.None()` **324.8ms** → Baseline Profiles **229.0ms**（约 -29%）。这是官方文档直接给出的数值，可直接作为 §3.3 的“代码层上限”参考（不可直接换算为本仓库秒数，见 §3.3）。
4. **Macrobenchmark 目标 App 的硬性前提**：需 `com.android.test` 独立模块、目标 App 带 `<profileable>`、`androidx.profileinstaller` ≥ 1.3；新增 `benchmark` build type 时其他模块（本仓库 `:parser-api`）需 `matchingFallbacks = ['release']`（官方 macrobenchmark-overview）。
5. **仓库内对历史/更新的“端到端聚合”基准缺失**：`WorkPagingDaoTest.historyFirstScreenBenchmarkFitsBudget`（`:229-246`）只测 **raw `WorkHistoryDao` 首窗**（不含 `buildHistoryAggregates`）；没有测量 Updates 聚合或历史聚合映射的测试。§2.2 的收益数字是推断值，落地前应补 E2E budget。

---

## 1. 现状证据（按页面）

### 1.1 收藏页（已修复，本轮提交）

- 冷启动从「全量解析 6317 实体 + 全量映射 6317 卡 ≈ 11.6s」恢复到 Room + Paging 3：
  - `BatchMappingPagingSource.kt:18` `FavouriteLibraryPagingConfig` = `pageSize 64 / initialLoadSize 64 / prefetchDistance 64 / maxSize 384 / enablePlaceholders=false`。
  - `WorkFavouritesDao.kt` 位置分页查询直接返回 `FavouriteLibraryPagingRow`（内嵌展示 manga + history + tracking 摘要，单条 JOIN 语句）；另提供 `findListRepresentatives` / `findLibraryRepresentatives` 与分块的 `findList()` 全量路径。
  - `WorkAggregateRepository.kt:636` `buildFavouritePagingAggregates`：每页只解析 64 实体，bindings/categories/history 并行批量查询；`:452` `canUseFavouriteLibraryProjection` 仅在无 Space/分组/宏过滤时走代表项快路径。
- 实测（前序会话，真机 6500 收藏）：Activity 冷启动 2146–2204ms；收藏首批加载 554–583ms；后续两批各 64 项后台预取；DAO 实机测试（`WorkPagingDaoTest`，9 项）与 `SpaceWorkDaoTest`（2 项）在本会话全部通过。

### 1.2 历史页（History）

- `HistoryListViewModel.kt:281` `pagingContent`：已是 `Pager` + `LargeLibraryPagingConfig` + `BatchMappingPagingSource(createHistoryPagingSource)` → 每页 `buildHistoryAggregates`。
- `WorkAggregateRepository.kt:376` `buildHistoryAggregates`：每页先 `resolveProjectionSet`（`WorkResolver.resolveManyByEntityIds` + `findWithTagsByIds` + `findEntitiesByIds`，`:984`），再并行 `findCategoriesByEntityId` + `findStatsByEntityId` + `findTrackingByEntityId`。
- 结论：**已被 Paging 限流（首屏 64 项），没有收藏页当年的 11.6s 式全量问题；但每页聚合成本与收藏修复前的「每页全量聚合」同构**（收藏修复前单页聚合实测 455–464ms）。优化收益集中在：首屏/滚动每页延迟与后台预取。

### 1.3 更新页（Updated）

- `UpdatesViewModel.kt:137` `pagingContent`：`Pager` + `LargeLibraryPagingConfig` + `repository.createUpdatedPagingSource` → 每页 `buildTrackingAggregates`。
- `WorkAggregateRepository.kt:293` `buildTrackingAggregates`：除 `resolveProjectionSet` + 3 个批量查询外，还在循环内对**每个实体**各执行一次 `db.getWorkHistoryDao().find(entityId)`（`:254/:322`）与 `db.getWorkFavouritesDao().findActiveForEntity(entityId)`（`:255/:323`）——即每页 **64 × 2 = 128 次单行查询**（N+1）。
- 结论：**更新页存在真实 N+1 放大**，是三个页面里单页成本最高、轻量化收益最确定的一个。

### 1.4 订阅/追踪 Feed

- `FeedViewModel.kt:204` `logFlow`：`observeAllTrackingLogItems(limit, filters)`；`AppSettings.kt:1237` `feedLimit` 默认 **200**，且 `FeedViewModel.kt:328` 每次 load-more `limit += 50`。
- `TrackingRepository.observeAllTracks(limit, filters)` → `mapLatest { buildTrackingAggregates(tracks) }`：**整个窗口一次性构建全量聚合**（含上述 N+1）。
- `observeUpdatedContent(UPDATED_CONTENT_LOOKAHEAD_SIZE=200)`（`FeedViewModel.kt:219`，`:70` 常量）同样是窗口内全量 `buildTrackingAggregates`。
- 结论：订阅 Feed 保留着收藏页当年的「全量构建」模式（规模小一些：200→几百条），**是重构后最接近原病灶的调用方**。

### 1.5 基础设施

- `settings.gradle:56` 仅 `include ':app', ':parser-api'` + fixtures；仓库内无 `macrobenchmark` / `baselineprofile` / `benchmark` 模块（`find . -iname "*baseline*"` 仅命中文档）。
- gradle/app 均无 Macrobenchmark / StartupTiming 测试；`WorkPagingDaoTest.kt` 里只有 DAO 首窗计时（`history-bench`、`favourites-bench` log），不是帧级/启动级基准。
- 设备：真机 M332BF，**API 36**（Android 16），满足「API 33+ 无需 root 生成 Baseline Profile」的前提。

---

## 2. 选项 A：把轻量行优化推广到历史 / 更新 / 订阅

### 2.1 做法（对收藏已落地模式的可迁移复刻）

收藏页已经抽象出可复用套路：位置分页单条 JOIN 查询返回「展示行 + 历史 + 追踪摘要 + preferred 投影」，`WorkAggregateRepository` 每页只做 `coroutineScope` 并行批量查询，完整绑定集合仍保留（`localMangaIds` / `projectionCount` 不受损）。

- **历史页**：`WorkHistoryDao.pagingSource` 增加 `FavouriteLibraryPagingRow` 式投影（display manga + tracking 摘要，history 本身就是主键）；`buildHistoryAggregates` 改为复用 `buildFavouritePagingAggregates` 的嵌入行路径，去掉每页 `resolveProjectionSet`。
- **更新页**：`TracksDao.pagingUpdatedContent` 同样返回嵌入行；`buildTrackingAggregates` 用「按 entityId 批量加载 history/favourite 状态」替换两处循环内单行查询（`:254/:255`、`:322/:323`），消除 N+1。
- **订阅 Feed**：`observeUpdatedContent(limit)` / `observeAllTracks(limit)` 由全量 `buildTrackingAggregates` 改为分页/批量投影；或至少把 Feed 窗口切成 Paging 首窗（对齐收藏策略）。

### 2.2 预期收益（基于收藏实测推断）

- 收藏修复后单页从「9.49s 全量聚合 + 2.13s 全量映射」→「554–583ms 首批」。历史/更新每页仍走全量聚合路径（收藏修复前该路径单页 455–464ms + N+1），轻量化后单页成本应显著下降；订阅 Feed 的 200+ 全量聚合窗口收益最大（接近当年收藏病灶的缩小版）。
- 收益是可检测的既有反馈环：`WorkPagingDaoTest` 的 `favourites-bench` / `history-bench` 与真机 `LibraryPaging` 日志可直接复用到历史/更新。

### 2.3 风险与阻塞点

| 风险 | 说明 |
|---|---|
| 历史分组/表头语义 | `applyHistoryPagingPresentation(grouped)` 依赖每页是否带回完整 entity（`WorkHistoryEntity` 字段），轻量行必须保留 orderKey/分组所需列；改动需回归历史分组与时间线表头。 |
| 更新页多字段状态 | 更新卡显示 fav/进度/新章节/徽章，嵌入行需带全追踪摘要 + favourite 布尔 + history 最近读；`buildTrackingAggregates` 当前按实体返回完整 `WorkAggregate`，下游 `toContentTracking()` 消费哪些字段需逐项核对。 |
| Feed 的非分页契约 | `observeAllTrackingLogItems` 返回整组日志项（含 `TrackingLogItem` 组装），不只是卡片；轻量化对象是底层 `buildTrackingAggregates`，Feed 组装层可不动。 |
| 行为回归 | MULTI_PROJECTION / 多选 ID 展开依赖完整绑定集合，收藏已证明可行，历史/更新需保留同样的绑定批量查询（`findActiveLocalBindingsByEntities` 已存在，`DefaultWorkResolver.kt` 已并行化）。 |

### 2.4 小结

选项 A 是**同一套模式的横向复制**，复用已建好的 DAO/Repository/测试基建；更新页的 N+1 与订阅 Feed 的全量窗口都是能被明确量化的真实缺陷。无新增构建/发布基础设施。

---

## 3. 选项 B：接入 Baseline Profile（自定义 App Profile）

### 3.1 官方事实（均为会话内抓取的 developer.android.com / androidx 一手来源）

1. **收益定位**：Baseline Profile「提高代码执行速度约 30%（首次启动起）」，通过避免解释 + JIT、让 ART 对包含的代码路径做 AOT 编译（`developer.android.com/develop/ui/compose/performance/baseline-profiles`）。**它改善的是冷启动编译成本，不改善数据库查询/聚合成本**。
2. **Compose 自带一份**：Compose 库自带 baseline profile，安装即默认受益；自定义 App Profile 额外覆盖的是 **Hilt/Dagger、Navigation、Coil、应用自身**的启动类。
3. **当前推荐接入路径**（`developer.android.com/topic/performance/baselineprofiles/create-baselineprofile`）：
   - 新增 `:baseline-profile`（`com.android.test` 模块）+ `androidx.baselineprofile` Gradle 插件，`targetProjectPath ':app'`；
   - 测试类继承/使用 `BaselineProfileRule`，把关键用户旅程（此处：冷启动直达收藏页、返回到 Home、进入详情）放进 `collect {}`；
   - 运行 `:app:generateBaselineProfile` / `:app:generateVariantBaselineProfile`，产物复制到 `app/src/<variant>/generated/baselineProfiles/baseline-prof.txt`；
   - App 模块加 `androidx.profileinstaller` 依赖；**Baseline Profile 仅对 release 构建生效**（调试态不安装，避免拖慢迭代）。
   - 生成需真机，rooted 或 **API 33+ 无需 root**（Macrobenchmark 1.2.0-alpha06+）。本仓库连着的设备是 API 36，满足。
4. **量测闭环**：官方要求用 Macrobenchmark（`StartupTiming`）前后对比；仓库当前没有任何 Macrobenchmark 模块，需一并新建（`:macrobenchmark` 或复用 baseline-profile 模块的 benchmark 测试）才能在提交里带上「有数据」的证据。

### 3.2 成本

- 新增 1–2 个 Gradle 模块（`com.android.test` + Groovy DSL 配置）、依赖版本目录条目、`profileinstaller`、测试源（生成器 + StartupTiming 基准）、CI/本机生成流程与产物入库策略。
- 需要 release 产物参与验证：当前复现/反馈环（debug 真机 + `LibraryPaging` 日志）不能直接验证 profile 生效，需另开 release 构建 + Macrobenchmark 基线。
- `CONTRIBUTING.md` 要求「不新增不必要依赖」——profileinstaller 与 benchmark 库属于构建/发布级新增，需在提交说明里给出理由。

### 3.3 预期收益（需测量，不可先验）

- 目标病灶是剩余 ~2.1–2.2s 的 Activity 冷启动（Compose/Hilt/Nav/Coil 首次类加载）。官方给出的「约 30% 代码执行提升」是 ART 对**已包含路径**的改善，不能直接换算成启动秒数；必须在 `StartupTiming` 上实测。
- 对数据阶段（已是 ~0.58s 且继续被选项 A 压低）无帮助；它是**全局每个页面/入口都受益**的横向优化。

---

## 4. 对比

| 维度 | 选项 A（轻量行推广） | 选项 B（Baseline Profile） |
|---|---|---|
| 目标病灶 | 每页聚合 / 全量窗口 / N+1（数据层） | 冷启动类加载 / JIT（代码层） |
| 影响面 | 历史、更新、订阅三个页面 | 全局所有页面冷启动 |
| 已知可量化缺陷 | 有：更新 N+1、Feed 全量窗口 | 无仓库内数据，需先建 Macrobenchmark 基线 |
| 新增构建/发布基础设施 | 无 | 有（1–2 个新模块 + release 验证线） |
| 复用已建基建 | 高（DAO/Repository/测试同构） | 低（全部新建） |
| 风险 | 中（历史分组/更新徽章语义需回归） | 低-中（官方成熟路径，但需 release 验证） |
| 验证闭环 | 现成（DAO 计时 + 真机 LibraryPaging 日志） | 需新建（Macrobenchmark StartupTiming） |

---

## 5. 建议

**先做选项 A（历史 → 更新 → 订阅），做完后再做选项 B（Baseline Profile）。**

理由：

1. **可量化缺陷明确**：更新页 `buildTrackingAggregates` 每页 128 次单行查询（N+1）、订阅 Feed 默认 200 行的全量聚合窗口，都是能直接写进测试断言并被真机日志证明的病灶；选项 B 在仓库里目前连测量基线都没有。
2. **复用而非新建**：选项 A 是收藏页已落地的同一种模式（嵌入行 + 并行批量 + 完整绑定集合），DAO/Repository/测试全部同构，改动面和回归成本可预测；选项 B 要先搭一套 release-only 的构建+测量线。
3. **顺序上 B 依赖 A 的收尾**：先让数据层在所有大列表页保持一致（轻量行），再让 Baseline Profile 的「代码层 AOT」在干净的数据路径上兑现其整体启动收益；否则 profile 优化的是尚未瘦身、仍可能回归的旧路径。
4. **不放弃 B**：剩余 ~2.1s 的 Activity 冷启动是全局问题，Compose 自带 profile 只覆盖 Compose 库，Hilt/Nav/Coil/应用代码仍靠 JIT；接入官方 `:baseline-profile` 模块（设备 API 36 满足免 root 前提）作为 A 之后的第二里程碑，并用 Macrobenchmark `StartupTiming` 提供前后数据。

**建议的落地顺序（供评审）：**
1. 历史页轻量行（回归：历史分组/时间线表头）。
2. 更新页消除 N+1 + 轻量行（回归：更新卡徽章/进度/收藏态）。
3. 订阅 Feed 窗口分页化 / 批量投影（回归：TrackingLog 组装，分组模式）。
4. 新增 `:baseline-profile` + Macrobenchmark `StartupTiming`，对收藏/历史/更新冷启动做前后对比并入库 `baseline-prof.txt`。

---

## 6. 一手来源

### 仓库源码（本次核验）
- `app/src/main/kotlin/org/skepsun/kototoro/core/paging/BatchMappingPagingSource.kt:10-24`（`LargeLibraryPagingConfig` / `FavouriteLibraryPagingConfig`）
- `app/src/main/kotlin/org/skepsun/kototoro/favourites/data/WorkFavouritesDao.kt`（`FavouriteLibraryPagingRow` 位置分页、`findList*`）
- `app/src/main/kotlin/org/skepsun/kototoro/work/domain/WorkAggregateRepository.kt:293,376,452,636,984`（`buildTrackingAggregates` / `buildHistoryAggregates` / `canUseFavouriteLibraryProjection` / `buildFavouritePagingAggregates` / `resolveProjectionSet`）
- `app/src/main/kotlin/org/skepsun/kototoro/history/ui/HistoryListViewModel.kt:281`（历史 Paging 入口）
- `app/src/main/kotlin/org/skepsun/kototoro/tracker/ui/updates/UpdatesViewModel.kt:137`（更新 Paging 入口）
- `app/src/main/kotlin/org/skepsun/kototoro/tracker/ui/feed/FeedViewModel.kt:70,204,219,328`（Feed 全量窗口）
- `app/src/main/kotlin/org/skepsun/kototoro/core/prefs/AppSettings.kt:1237`（`feedLimit` 默认 200）
- `settings.gradle:56`（无 benchmark/baseline profile 模块）
- `app/src/androidTest/kotlin/org/skepsun/kototoro/core/paging/WorkPagingDaoTest.kt`（DAO 首窗/budget 测试，本会话真机 9/9 + `SpaceWorkDaoTest` 2/2 通过）

### 官方 Android 文档（本会话 `android docs fetch` 抓取）
- Create Baseline Profiles — https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile （`androidx.baselineprofile` 插件、`com.android.test` 模块、`targetProjectPath`、`BaselineProfileRule`、`:app:generateBaselineProfile`、API 33+ 免 root / Macrobenchmark 1.2.0-alpha06+）
- Baseline Profiles (Compose) — https://developer.android.com/develop/ui/compose/performance/baseline-profiles （约 30% 代码执行提升、Compose 自带 profile、建议 Macrobenchmark 验证、启动类 AOT）
- 其余官方入口：https://developer.android.com/topic/performance/baselineprofiles/overview 、https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile
