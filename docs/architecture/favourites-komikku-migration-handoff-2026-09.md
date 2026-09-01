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
| 5 UI 切静态 List | ⬜ 未开始 | 见 §4 |
| 6 actions 迁移/删逐分类 VM | ⬜ 未开始 | |
| 7 删除收藏 Paging 代码 | ⬜ 未开始 | |
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
```

测试：

```text
app/src/test/kotlin/org/skepsun/kototoro/favourites/
  ui/FavouriteCardFieldContractTest.kt                    # JVM，10 用例
  domain/library/FavouriteLibraryDeriverTest.kt           # JVM，16 用例
  ui/container/FavouriteLibraryUiStateTest.kt             # JVM，6 用例
app/src/androidTest/kotlin/org/skepsun/kototoro/favourites/
  data/FavouriteLibrarySeed.kt                            # 共享种子助手（raw SQL）
  data/FavouriteLibrarySemanticsCharacterizationTest.kt   # 24 用例（旧 SQL 语义，Phase 7 后删除）
  data/FavouriteLibraryReadDaoTest.kt                     # 10 用例
  data/FavouriteLibraryReadDaoScaleTest.kt                # 2 用例（10k）
  data/FavouriteLibraryBaselineBenchmarkTest.kt           # 3 用例（旧链路基线，Phase 8 后可删）
  domain/FavouriteLibraryAggregateChainCharacterizationTest.kt  # 12 用例（旧聚合链语义）
  domain/library/FavouriteLibrarySnapshotStoreTest.kt     # 9 用例
  domain/library/FavouriteLibrarySnapshotStoreScaleTest.kt# 1 用例（10k=202ms）
```

## 3. 必须保留的语义（已 characterization 固化）

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

## 4. 下一步：Phase 5（UI 切静态 List）实施指南

目标：`FavoritesListScreen` 消费 Container 的 `libraryState`，`FavouritesListViewModel.pagingContent` 停用（Phase 6 删除）。

建议顺序：

1. **卡片模型映射**：新增 `FavouritesCardMapper`（favourites/domain/library/）：`FavouriteCardRow` → 三种 `ContentListModel`（GRID/COMPACT_GRID/LIST/DETAILED_LIST）。注意：
   - `manga: Content` 参数：卡片渲染需要 `Content` 对象（title/cover/tags/source/chapters=空/url）。从 row 构造**轻量 Content**（`chapters=null`、`description=null`、`sourceData=null`）；item id 用 `entityId`（现有 `ContentListModel.id` 即 list id）。
   - `subtitle`：compact=displayTags.joinToString；grid/detailed=`altTitle`。
   - favourites 专属后缀（`favourites_entity_current_projection`，见 `FavouritesListViewModel.groupSuffix`）拼进 subtitle。
   - `counter`=newChapters（completed 时 0）；`progress`=ReadingProgress(percent, chapters, mode)（history null 时不显示）；`projectionCount`/`isPinned`(membership)/`isSaved`=isDownloaded；`isFavorite`=true（或在 favourites 页传 NO_FAVORITE flags）。
   - `override` = ContentOverride(overrideTitle/overrideCoverUrl/null)。
2. **HorizontalPager 页面**：`FavoritesHostScreen` 每页从 Container 取 `visibleIdsByCategory[categoryId]` + `rowsByEntityId` 映射 ListModel；`AppContentListRoute` 走静态路径（`viewModel.content` 已有静态分支——给收藏新建薄 `ContentListViewModel` 子类或改造 `FavouritesListViewModel.content` 指向共享 state，`pagingContent` 返回 null）。
3. **滚动恢复**：保留每 category 的 LazyListState/LazyGridState saveable + `entityId+offset` 语义 anchor；`retainPagingSnapshotOnDetailsNavigation = false`（收藏不再需要 retained window）。
4. **Filter Panel**：`FavoritesFilterPanelRoute` 改读 Container（quickFilterMetadata 派生 chips；toggle 直接进 `globalFavoritesState`），删除 `activeFavouritesViewModelRef` 桥接。
5. **验证**：切换 category/filter/sort 时 `FavouriteLibraryReadDao` 无新查询（logcat 或 Room query counter）；详情返回列表即时存在。

Phase 5 完成后 → Phase 6（actions 用 `FavouriteItemRef(entityId, displayMangaId, localMangaIds)`；删除 `FavouritesListViewModel.Factory`/每 category Hilt key/`activeFavouritesViewModelRef`）→ Phase 7（按计划 §8 Phase 7 清单删除，删前 `rg` 确认无其他调用方；**不得删除**历史/更新页面仍在用的 Paging 基础设施）→ Phase 8（跑 §11 全部基准 + 与 Komikku 并列对照 + 删 shadow comparison/临时日志 + 更新 `large-library-performance-handoff-2026-08.md`）。

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
- `list/ui/ContentListViewModel.kt` / `list/ui/compose/RetainedPagingSnapshotController.kt`（K510G debug 日志）
- `backups/external/*`、`core/AppModule.kt`、`details/ui/compose/*`、`docs/.vitepress/config.mts`、`docs/index.md`、`backups/external/MihonBackupSourceIdPreservationTest.kt`、`core/paging/BatchMappingPagingSourceTest.kt`
- `docs/architecture/komikku-library-list-architecture-research-2026-09.md`、`docs/architecture/paging3-lazy-list-alternatives-research-2026-09.md`（研究文档，归属由用户决定）

## 7. 验证命令

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --no-daemon
# 设备（见 §5 流程）：
#   FavouriteLibraryReadDaoTest / FavouriteLibraryReadDaoScaleTest
#   FavouriteLibrarySnapshotStoreTest / FavouriteLibrarySnapshotStoreScaleTest
#   FavouriteLibrarySemanticsCharacterizationTest / FavouriteLibraryAggregateChainCharacterizationTest
#   FavouriteLibraryBaselineBenchmarkTest
```

全部通过状态（2026-09-01）：JVM 32/32（契约 10 + deriver 16 + UiState 6），设备 58/58（SQL 24 + 聚合链 12 + DAO 10+2 + Store 9+1 + 基准 3——基准 3 含在其中）。
