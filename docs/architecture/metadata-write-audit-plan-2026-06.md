# Metadata Write Audit Plan（2026-06）

## 目的

本文档用于审计 Kototoro 当前所有关键 metadata 写入路径，并定义收敛策略。

当前最危险的问题不是 metadata 读取复杂，而是：

> metadata 在多个层级被多点写入、互相覆盖，并且都被误认为真相。

因此本计划的目标是：

1. 列出当前 metadata 写入入口；
2. 区分 authoritative write / override write / cache write；
3. 明确哪些写入必须保留、哪些必须降级、哪些必须删除；
4. 为 Work 化迁移建立 metadata 单向流动边界。

## 审计范围

本次重点审计以下 metadata 类别：

- title
- cover
- description
- tags
- content rating
- metadata source selection
- tracking enrichment-derived presentation fields

以及以下承载层：

- `manga`
- `manga prefs`
- `entity_preferences`
- `tracking_site_links`
- tracking details cache
- backup / restore payload

## 核心原则

### 1. 允许读取聚合，不允许多点主写

允许：

- resolver 聚合 metadata
- cache 派生 snapshot

不允许：

- manga / entity / tracking cache 同时都能写“canonical truth”

### 2. 必须区分三种写入

#### authoritative write

改变主语义的写入。

例如：

- Work 默认 metadata source 选择
- Work override

#### override write

局部显式覆盖。

例如：

- per-projection metadata source override
- 用户手动标题/封面覆盖

#### cache write

仅为了读取性能或离线体验的派生缓存。

例如：

- tracking details cache
- resolved metadata snapshot

### 3. cache 不得反向成为真相

任何 cache write：

- 不得反向覆盖 Work ownership；
- 不得反向提升为 entity/projection 主真相；
- 不得通过 sync 回流重新污染主模型。

## 当前主要写入路径

以下路径基于当前代码扫描整理。

### A. Work / Entity metadata selection 写入

#### 入口 1

[ContentDataRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/parser/ContentDataRepository.kt:132)

方法：

- `setEntityMetadataSourceSelection(...)`

当前行为：

- 写 `entity_preferences`
- 可选写 `manga prefs`（`mirrorLocalMangaIds`）

分类：

- entity 写入：**authoritative write**
- mirror 到 manga prefs：**override write / 历史兼容写**

当前状态：

- 已经做了止血，不再默认大范围 blind mirror；
- 但 API 仍保留显式 mirror 能力。

治理结论：

- 保留 entity 写入；
- mirror 仅允许窄范围显式 override；
- 不能再作为默认传播机制。

#### 入口 2

[EntityGraphRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:1346)

方法：

- `applyMetadataSelection(...)`

当前行为：

- 写 `entity_preferences`

分类：

- **authoritative write**

治理结论：

- 这条入口已经完成了从“混写 entity + manga prefs”到“只写 Work/entity default”的收口；
- 当前风险不再是它自身混写，而是调用方是否仍把 projection drift 和 Work drift 混在同一 repair 流程里处理；
- 后续审计重点应转向：
  - projection drift 的诊断分类是否继续独立；
  - repair 动作是否还会通过其它入口重建 projection shadow。

### B. Projection metadata selection 写入

#### 入口 3

[ContentDataRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/parser/ContentDataRepository.kt:272)

方法：

- `setMetadataSourceSelection(mangaId, selection)`

当前行为：

- 写 `manga prefs.metadata_source_*`

分类：

- **override write**

治理结论：

- 保留，但语义必须明确为：
  - projection-local override only
- 不得再被用来承载 Work 默认 metadata authority。

#### 入口 4

[DetailsViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:1783)

方法：

- `persistMetadataSourceSelection(...)`
- `persistMetadataSourceSelectionForCurrentEntity(...)`

当前行为：

- 有 entity 时写 entity metadata preference
- 无 entity 时写 current manga prefs

分类：

- 有 entity：**authoritative write**
- 无 entity：**override write / legacy fallback**

治理结论：

- 当前方向是对的；
- 后续需要把“无 entity fallback”逐步缩小为纯 projection 场景。

补充说明：

- `DetailsViewModel` 当前又新增了一层运行时锚点收口：
  - `updateUnifiedReadingStatus()`
  - `updateUnifiedRating()`
  - `unregisterScrobbling()`
  - `bindTrackingMatch()`
  - `ignoreTrackingSuggestion()`
  - `removeTrackingMatch()`
- 这些入口不再直接复用页面初始 `mangaId`，而是先解析当前 resolved local projection。

这组改动本身没有新增新的 authoritative write 类型，但它修正了一个高频误用：

- runtime write 的落点仍然可能是 projection anchor；
- 但锚点必须来自当前详情上下文，而不是历史残留参数。

### C. 用户 override 写入

#### 入口 5

[ContentDataRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/parser/ContentDataRepository.kt:255)

方法：

- `setOverride(manga, override)`

当前行为：

- 写 `manga prefs`：
  - `title_override`
  - `cover_override`
  - `content_rating_override`

分类：

- 当前实现已经是：
  - 有 entity 时写 `entity_preferences`
  - 无 entity 时回退写 `manga prefs`
- 因此主链分类已是 **Work override**，projection 侧只剩 **legacy fallback / local compatibility write**

治理结论：

- 这条主写链已经基本完成 ownership 上移；
- 下一步重点不再是改主入口，而是继续清理历史镜像；
- 对于与 entity override 完全重复的 projection override，应通过 repair 剪枝。

当前对应治理入口：

- `REDUNDANT_PROJECTION_OVERRIDE`
- `pruneRedundantProjectionOverrides()`

### D. Repair / cleanup 写入

#### 入口 6

[EntityGraphRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:1222)

方法：

- `clearMangaMetadataSourceIfSuspect(...)`
- `clearEntityMetadataSourceIfSuspect(...)`

当前行为：

- repair 中回退 `tracking -> base`

分类：

- **repair corrective write**

治理结论：

- 必须保留；
- 但必须严格限制只修自己层级：
  - projection override 修 projection
  - Work default 修 Work

不能跨层顺手覆盖。

### D-1. Projection-local tracking suggestion hint 写入

#### 入口 6-1

[ContentDataRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/parser/ContentDataRepository.kt:355)

方法：

- `setIgnoredTrackingSuggestion(mangaId, suggestion)`

当前行为：

- 只写 `preferences.ignored_tracking_suggestion_*`
- 当前 details 页相关入口已经统一先解析 current local projection，再执行该写入
- tracking suggestion 的首次加载与后续 refresh 也已统一跟随 current local projection

分类：

- **projection-local hint write**

治理结论：

- 这条写链应保留在 projection 层；
- 不应上移成 Work owner state；
- 但所有调用方都应避免继续把“初始打开时的 mangaId”当作当前 hint owner。

### E. Tracking link cache 写入

#### 入口 7

[DefaultTrackingSiteMatcher.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/tracking/discovery/data/DefaultTrackingSiteMatcher.kt:46)

方法：

- `matchLocalContent(...)`
- `confirmMatch(...)`

当前行为：

- 在 auto-match 或 manual confirm 后删除同 work/projection candidate 范围内的旧 link；
- 回写一条 `tracking_site_links` 记录，作为当前 tracking match cache / audit anchor。

分类：

- **cache write**
- manual confirm 虽然带有用户意图，但 `tracking_site_links` 本身仍不是 authoritative owner store

当前状态：

- 已完成 owner-aware 收口：
  - 写入统一走 `attachEntityOwnership(...)`
  - 已有 link 选择不再依赖 `first()` 或数据库返回顺序
  - 当前优先级改为：
    - requested projection
    - anchor projection
    - manual
    - confidence
    - updatedAt

治理结论：

- 保留这条写链，但明确只作为 cache / audit；
- 不得再通过公共 helper 反向定义 entity owner；
- 后续如果要进一步演进，应把 “confirmed work binding” 与 “projection cache anchor” 在数据语义上继续拆开。

#### 入口 8

[MigrateUseCase.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/alternatives/domain/MigrateUseCase.kt:79)

方法：

- source migration 中的 tracking link 搬运逻辑

当前行为：

- 只迁移旧 projection 自己持有的 tracking link anchor；
- 有 entity 时，仅搬 `entityId` 下 `mangaId == oldMangaId` 的 link；
- 无 entity 时，仅搬旧 projection 自己的 legacy link，并按 `manual > confidence > updatedAt` 选主记录。

分类：

- **cache write**
- **migration compatibility write**

治理结论：

- 允许保留，因为 source migration 需要把旧 projection 的可读入口迁到新 projection；
- 但不得再做 work 范围的整组删写；
- entity-only link 与其他 projection anchor 不能被一次 source migration 顺带重写。

#### 入口 9

[MergeFavoriteEntitiesUseCase.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/domain/MergeFavoriteEntitiesUseCase.kt:142)

方法：

- `buildTrackingBindingGroups(...)`
- `isSafeTrackingMergeGroup(...)`
- `selectPreferredTrackingSelection(...)`

当前行为：

- tracking link 仍可参与 merge 分组与 merge 后默认 tracking metadata 选择；
- 但当前工作树已经把证据门槛收紧为：
  - `entity-owned`
  - 或 `manual`

分类：

- **cache-assisted merge evidence**

治理结论：

- 允许把强证据 link 用于 merge 候选与 metadata 选择；
- 纯 legacy auto cache 不得再作为 work merge 的主证据；
- 后续若继续演进，应把 tracking binding truth 与 merge suggestion cache 彻底拆层。

#### 入口 10

[BindTrackingToEntitiesUseCase.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/domain/BindTrackingToEntitiesUseCase.kt:92)

方法：

- `preview(...)`
- `bindOne(...)`

当前行为：

- preview 阶段会先检查 merge group 是否已经解析到单一 entity；
- 如果该 entity 已存在目标 service 的 confirmed binding，则直接复用并产出 `EXISTING_BINDING` preview；
- 只有不存在 confirmed binding 时，才继续走 dataset / aggregate api / online search。
- bind 阶段最多只执行一次 `confirmMatch()`；
- 如果 entity 上已经存在对齐的 projection anchor，则直接跳过重复写入。

分类：

- **authoritative binding reuse**
- **cache-avoiding read path**
- **cache write dedup**

治理结论：

- 这是符合 Work-first 的正确短路；
- 已有 confirmed binding 不应被重复外部搜索覆盖；
- 后续 bind 阶段仍可继续复用 `confirmMatch(...)` 维护 projection anchor，但不能把 preview 重新退回 cache-first。

### F. Backup / Restore 写入

#### 入口 11

[BackupRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/BackupRepository.kt:536)

当前行为：

- restore `manga prefs` 时会恢复：
  - title override
  - cover override
  - content rating override
  - metadata source raw fields

分类：

- **legacy import write**

治理结论：

- 必须降级为导入兼容写；
- 不得在 restore 后自动提升为 Work 主真相；
- 后续 Work migration 期间必须与 sync isolation 一起收紧。

#### 入口 12

[BackupRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/BackupRepository.kt:606)

当前行为：

- restore entity binding / entity prefs

分类：

- **legacy import write**

治理结论：

- 可保留；
- 但 legacy/sync 数据不得反向触发大范围 metadata propagation。

### F. Source-native metadata 写入

#### 入口 9

[ContentDataRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/parser/ContentDataRepository.kt:357)

方法：

- `storeContent(...)`

当前行为：

- 更新 `manga` 表中的 source-native title/cover/authors/tags 等

分类：

- **projection source write**

治理结论：

- 必须保留；
- 这是 Projection 原始数据更新，不属于 Work 真相写入；
- 但不得反向更新 Work canonical metadata。

## 问题汇总

### 问题 1：Work 与 Projection 的 metadata 写入边界未完全切开

当前仍有路径会在一个事务里同时写：

- `entity_preferences`
- `manga prefs`

这会导致：

- 语义混叠
- repair 复杂
- sync 难隔离

### 问题 2：override 仍被旧实现当成默认值承载层

`manga prefs.metadata_source_*` 本应降级为 projection override，
但历史上承担过 entity metadata mirror。

这会导致：

- “局部 override” 和 “作品默认 authority” 混在一起

### 问题 3：用户 override ownership 仍挂在 manga prefs

以下字段目前仍在 manga prefs：

- title override
- cover override
- content rating override

这与 Work ownership 目标不一致。

补充当前状态：

- `setOverride(manga, override)` 在存在 entity/work owner 时，
  已经优先写入 `entity_preferences`；
- 同一 projection 上遗留的完全冗余 shadow override，
  当前也会在写入时顺手清掉；
- 因此这条主链已不再是“纯 projection-owned override”。

但它仍未完全达到最终形态：

- override API 入口仍以 `manga` / projection 参数驱动；
- backup / restore / source migration 等兼容链路仍需继续防止旧 override 回流。

### 问题 4：backup/restore 仍能把旧 raw metadata 结构导回主流程

虽然这属于兼容需要，但如果没有 sync isolation 和导入降级策略，会继续造成：

- mirror 回流
- stale raw selection 回流

## 收敛目标

### 最终只允许四类 metadata 写入

1. **Projection source write**
   - 更新 source-native metadata
2. **Work authoritative write**
   - 更新 Work 默认 metadata authority / Work override
3. **Projection override write**
   - 更新显式 local override
4. **Cache write**
   - 更新 tracking / resolved metadata cache

除此之外的跨层 propagation 写入都应视为异常设计。

## 治理决策表

| 写入路径 | 当前分类 | 去留决策 | 说明 |
| --- | --- | --- | --- |
| `setEntityMetadataSourceSelection` 写 entity prefs | authoritative | 保留 | 继续作为 Work metadata default 入口 |
| `setEntityMetadataSourceSelection` mirror manga prefs | 历史兼容 / override | 收紧 | 仅显式单项 override 允许 |
| `setMetadataSourceSelection(mangaId)` | override | 保留 | 仅 projection-local override |
| `setOverride(manga, override)` | override | 上移 | 过渡期保留，最终迁到 Work override |
| `applyMetadataSelection` 同时写 entity + manga | repair mixed write | 拆分 | 后续拆成 Work default 与 Projection override 两类动作 |
| `clearMangaMetadataSourceIfSuspect` | repair override | 保留 | 仅修 projection override |
| `clearEntityMetadataSourceIfSuspect` | repair authoritative | 保留 | 仅修 Work metadata default |
| `storeContent` 更新 manga metadata | source write | 保留 | Projection source-native 更新 |
| backup/restore 导回 manga prefs metadata | import write | 降级 | 仅导入兼容，不提升为主真相 |
| tracking cache / details cache 更新 | cache write | 保留 | 但不得反写 Work/Projection 真相 |

## 分阶段执行

### Phase 1：冻结传播型写入

目标：

- 停止 metadata 在多个层级无差别传播。

动作：

1. 停 blind mirror
2. repair 分类拆层
3. binding 化 entity metadata preference

状态：

- 该阶段已部分完成

### Phase 2：把 override 与 default 彻底拆层

目标：

- Work metadata default 与 projection override 不能再由同一方法长期混写。

动作：

1. 拆 `applyMetadataSelection(...)`
2. 区分 Work repair 与 Projection repair
3. 统一 Details/repair 写入语义

### Phase 3：把用户 override 上移到 Work

目标：

- `titleOverride`
- `coverUrlOverride`
- `contentRatingOverride`

从 manga prefs 迁移到 Work ownership。

### Phase 4：引入 ResolvedMetadata cache

目标：

- 将 canonical title/cover/tags 收敛为单点 derived cache；
- 不再依赖多点 raw metadata copy。

约束：

- cache 只读或重建；
- 不反写主真相。

### Phase 5：与 sync isolation 联动

目标：

- 阻断旧协议把旧 metadata mirror 语义重新写回。

动作：

1. sync version 分代
2. namespace 切换
3. 旧版禁写新语义

## 验收标准

metadata 写入审计完成并执行收敛后，应满足：

1. 新代码中所有 metadata 写入都能被归类为四种写入之一；
2. 不再存在默认的 entity -> manga metadata blind mirror；
3. projection override 与 Work default 在代码路径上明确分离；
4. source-native metadata 更新不再反向定义 Work canonical metadata；
5. backup/restore 不再把旧 raw metadata 结构重新扩散为主真相；
6. metadata cache 明确只是 derived cache，而不是第三主模型。

## 一句话结论

你们当前最需要冻结的不是 metadata 读取，而是：

> **metadata 到底谁能写、写到哪一层、是否允许跨层传播。**
