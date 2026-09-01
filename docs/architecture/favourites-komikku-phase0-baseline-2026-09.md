# 收藏页 Komikku 对齐迁移 — Phase 0 基线记录（2026-09）

> 状态：Phase 0 完成（测试落地并通过），Phase 1 进行中
> 计划：[`favourites-komikku-alignment-implementation-plan-2026-09.md`](./favourites-komikku-alignment-implementation-plan-2026-09.md)
> 测量设备：Xiaomi M332BF（Android 16 / API 36，arm64），debug 变体 + in-memory Room，USB 连接
> 测量日期：2026-09-01

## 1. 已落地的基线测试

| 测试 | 位置 | 覆盖 |
|---|---|---|
| `FavouriteCardFieldContractTest`（JVM，10 用例） | `app/src/test/kotlin/org/skepsun/kototoro/favourites/ui/` | 三种卡片模式（GRID/COMPACT_GRID/LIST/DETAILED_LIST）真实读取字段契约：title+override、coverUrl+override、altTitle→subtitle（grid/detailed）、tags→compact subtitle & detailed chips、contentRating→NSFW badge、counter/progress/projectionCount/isPinned/isSaved/metadataTrackingService 透传。**验证了 description/sourceData/publicUrl/largeCoverUrl 对卡片渲染不可见（等价性断言）** |
| `FavouriteLibrarySemanticsCharacterizationTest`（instrumented，24 用例） | `app/src/androidTest/kotlin/org/skepsun/kototoro/favourites/data/` | 收藏 paging SQL 全语义：12 种排序（含 pinned 恒定优先 + entity_id tie-break）、代表 membership 选择（pinned→createdAt→updatedAt→categoryId）、分类 slice 独立 membership 属性、preferred/anchor display 选择、tracks 聚合（SUM/MAX）、history join、9 种 SQL 过滤（nsfw/downloaded/new-chapters/source/tag/publication/content-type/space/dangling-category） |
| `FavouriteLibraryAggregateChainCharacterizationTest`（instrumented，12 用例） | `app/src/androidTest/kotlin/org/skepsun/kototoro/favourites/domain/` | 聚合链语义（`observeFavouriteLibraryAggregates`）：display = preferred→anchor（窄路径不查 binding）、localMangaIds = preferred+anchor+bindings（与 WorkResolver 的 binding-only 语义不同！）、COMPLETED/NEW_CHAPTERS/MULTI_PROJECTION/PublicationState/ReadingStatus（显式 entity prefs 优先，history percent 兜底）/Tag/Downloaded 过滤、分类 slice |
| `FavouriteLibraryBaselineBenchmarkTest`（instrumented，3 用例） | 同上 | 6.5k 合成库性能基线 + EXPLAIN QUERY PLAN 记录 |

共享种子助手：`FavouriteLibrarySeed.kt`（androidTest）。

## 2. 性能基线（6.5k entities / ~7.1k memberships，in-memory DB）

| 指标 | 数值（warm，单次） |
|---|---:|
| `findLibraryRepresentatives(-1)`（窄身份查询） | **22 ms** |
| 宽 paging 行全量读取（500/页 ×13 页，含 work_history 嵌入 + tracks 聚合 join） | **360 ms** |
| （参考）计划第 11 节新快照构建预算（6.3k warm P95） | ≤ 250 ms |

**结论**：宽行 SQL 本身 360ms 只占链路第一步；其后 `buildFavouritePagingAggregates`（per-page bindings+categories+projections 批查）、`mapFavouritePage`（metadata authority + override + `ContentListMapper`）与 Paging 状态机才是滚动/返回卡顿的主成本。窄 read model（只取卡片契约字段）有充分余量满足 250ms 预算。

## 3. characterization 发现的真实语义（迁移必须保留或显式决策）

1. **`isPinned` 在所有排序中恒定最优先**（`ORDER BY selected.pinned DESC` 打头，12 种排序无一例外）。
2. **代表 membership 选择规则**：`pinned DESC, created_at DESC, updated_at DESC, category_id ASC`；All 分类用相关子查询逐行判定，分类 slice 各自独立。
3. **窄聚合路径与宽聚合路径的 `localMangaIds` 语义不一致**：
   - 窄路径（`findFavouriteLibraryAggregates`，无 space/groupTab/非 SFW filter 时启用）= `preferred + anchor + bindings` 并集；
   - 宽路径（`buildFavouritePagingAggregates` / `WorkResolver.resolveManyByEntityIds`）= bindings only。
   - 影响 `MULTI_PROJECTION` 过滤结果。新 snapshot store 必须**统一为 binding-based**（与 `WorkPagingDaoTest` 断言的语义一致：anchor 不得膨胀计数）。
4. **display projection 在窄路径 = preferred → anchor（不验证 binding）**；SQL 层 `COALESCE(preferred, anchor)` 的 dangling preferred 产生 NULL display（broken row），聚合层宽路径会 fallback 到 binding。新 store 需保留 broken row 并确定性放置。
5. **SQL NULL 语义**：
   - NULL 标题（missing display manga）在 ALPHABETIC ASC 排最前 / DESC 排最后；
   - NULL nsfw 既不匹配 NSFW 也不匹配 SFW 模式；
   - space 过滤的 `sm.content_type IN (...)` 不接受 NULL（binding 的 content_type 为 NULL → 排除），而 display 侧 content-type 过滤对 NULL 放行。两者不一致，新实现按"binding NULL → 排除"保守处理并记录。
6. **tag filter 的 tag ID 是确定性 hash**：`"${key}_${source.name}".longHashCode()`（`ContentTag.toEntity()`）；种子/测试必须用同一 ID 派生。
7. **`tracks` 聚合**：一个 entity 多条 track（每个 manga 一条，`owner_id`/`manga_id` 均 UNIQUE），summary = `SUM(chapters_new)`, `MAX(last_chapter_date)`, `MAX(last_check_time)`。
8. **dangling category membership（软删除分类下的收藏）当前仍可见**（SQL 不 join `favourite_categories`）——保留现状，与 `repairActiveDanglingCategoryRefs` 维护路径一致。
9. **rating 是纯排序键**：收藏卡片从不渲染 score（`scoreText` 仅 Discover feed 使用）——新 row 保留 rating 用于 RATING 排序即可。
10. **`largeCoverUrl` 不被收藏卡片消费**（只有 Home 封面请求 / 下载器用）——新 row 不需要它。

## 4. 未提交 Paging 实验（保留决定）

工作区有用户未提交的 Paging 实验：
- `BatchMappingPagingSource.kt`：`FavouriteLibraryPagingConfig.enablePlaceholders = true`
- `ContentListViewModel.createRetainedPagingSnapshot` / `RetainedPagingSnapshotController`：K510G debug 日志
- 另有 details UI / backup / AppModule / docs 的无关改动

**决定**：全部保留不动。这些属于旧 Paging 路径，随 Phase 7（删除收藏 Paging 专用代码）一并消失；debug 日志按计划第 11.3 节在 Phase 8 清理。Phase 1 只新增文件 + 新 DAO，不触碰上述文件。

## 5. Phase 1 输入（字段预算定稿）

Phase 0 字段契约 → Phase 1 窄 row 实际需要的列（全部有真实消费者）：

- 身份：`entity_id`, `display_manga_id`, `local_manga_ids`(binding facets), `preferred_local_manga_id`
- 展示：`title`, `alt_title`(→subtitle), `cover_url`, `author`
- 来源：`source`(名称, →source chip/cache key/group suffix/筛选), `content_type`(→type chips 兼容 display), `state`(→PublicationState 过滤 + info text), `nsfw`(→NSFW 过滤/badge)
- 排序键：`rating`, `created_at/updated_at/pinned`(membership), `percent/updated_at`(history), `new_chapters/last_chapter_date`(tracks)
- 卡片状态：`new_chapters`(counter), `percent/chapters_count`(progress), `projection_count`(multi badge/filter), `is_pinned`(badge), `downloaded`(saved badge/filter), `reading_status`(entity prefs → filter)
- 过滤 facets：`tag_ids`(Tag 过滤 + compact/detailed 展示), `source names`(Source 过滤 + quick filter chips)
- Override：`title_override`, `cover_override`（entity prefs + legacy preferences 兜底）
- 丢弃：`description`, `source_data`, `large_cover_url`, `public_url`, 完整 chapters/tags 对象图
