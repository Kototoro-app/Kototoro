# Entity Graph 治理收敛与历史债务清理方案（2026-06）

## 目的

本文档定义 Kototoro 当前 entity graph / source / metadata / tracking 相关模型的治理收敛方案。

目标不是继续局部修补，而是系统性解决以下问题：

- 实体身份、阅读投影、追踪元数据、缓存/候选、历史兼容字段长期混写；
- repair 诊断将不同层级的问题混为一类，导致噪音过大；
- entity 级 metadata source 被批量镜像到 local manga，持续制造新污染；
- backup / restore 与旧迁移逻辑继续传播 legacy 语义；
- 新旧版本残留字段仍参与运行时主决策，维护成本持续上升。

本方案遵循以下原则：

- **KISS**：先收口语义，再做结构演进；
- **YAGNI**：不引入新图数据库、不引入复杂远程同步模型；
- **DRY**：运行时只能存在一套主 metadata authority 语义；
- **SOLID**：identity、projection、metadata authority、cache 各自单一职责。

同时明确范围边界：

- 当前跨设备数据主链以 `backup / restore / WebDAV` 为准；
- 仓库中遗留的 `sync/` 与旧 `kotatsu sync` 源码不作为本方案的当前 authoritative 输入；
- 因此文档中关于“同步隔离”“旧语义回流”的约束，默认都落在当前 backup / restore 主链上。

## 现状结论

当前主要污染链路已经明确：

1. `entity_preferences` 保存 entity 级 metadata source。
2. `ContentDataRepository.setEntityMetadataSourceSelection()` 支持 `mirrorLocalMangaIds`，会把 entity 级选择镜像写入多个 local manga prefs。
3. `EntityGraphRepository.inspectRepairIssues()` 将以下不同语义混入 tracking suspect：
   - entity-level tracking binding
   - per-manga metadata source mismatch
   - `tracking_site_links` 缓存漂移
4. backup / restore 与历史 migration 会继续保留 legacy 结构，并把历史语义带回当前运行时。

结果是系统中同时存在两套“metadata source 真相”：

- entity 级：`entity_preferences`
- manga 级：`preferences.metadata_source_*`

这正是概念冗余和持续污染的根源。

## 治理目标

将运行时语义收敛为一句话：

> Entity 只负责身份聚合，Local Manga 只负责阅读投影，Tracking Source 只负责元数据权威，缓存和局部覆盖全部降级为附属物。

对应目标如下：

1. entity 成为唯一的跨源身份中心；
2. 阅读源绑定不再反向定义 entity 身份；
3. entity metadata source 成为唯一默认 metadata authority；
4. per-manga metadata source 降级为显式 override；
5. `tracking_site_links` 降级为 cache / audit / suggestion history；
6. repair 分类按问题层级拆分，不再混报；
7. backup / restore 不再把 legacy 数据重新提升为当前真相。

## 概念分级

### 一级：核心模型（保留并继续演进）

以下概念保留为主模型：

- `entity`
- `entity_binding`
- `entity_preferences`
- `relation`

要求：

- 运行时主流程必须优先依赖这些结构；
- 新功能不得绕开这些结构另建平行语义；
- repair、details、merge、bind 的主决策必须落在这一层。

### 二级：附属模型（降级，不再承载主真相）

以下概念保留，但明确降级：

- `tracking_site_links`
- `preferences.metadata_source_*`
- `preferences.ignoredTrackingSuggestion*`

要求：

- 只能作为缓存、局部覆盖、候选历史或抑制状态存在；
- 不得继续被当作 entity 真相或 tracking binding 真相；
- 不得参与默认 metadata authority 计算。

### 三级：历史兼容模型（只读兼容，逐步退役）

以下语义视为历史债务：

- 由旧 `preferences` 迁移生成的 entity metadata 影子语义；
- restore 时恢复回来的 `LEGACY` / `SYNC` binding；
- 旧 relation 或历史 merge/mirror 残留；
- 新旧版本 backup 中残存但无法证明仍是当前真相的数据。

要求：

- 可读取、可修复、可清理；
- 不得默认参与当前运行时主决策；
- 不能在新写路径中被重新扩散。

## 目标边界模型

### Entity

职责：

- 表示同一作品/人物/组织的聚合身份；
- 持有 primary name、aliases；
- 持有 entity-level tracking bindings；
- 持有 entity-level metadata preference；
- 持有 preferred local projection。

非职责：

- 不负责具体阅读入口；
- 不负责 per-manga 级别 override；
- 不负责缓存性质的 tracking match history。

### Entity Binding

职责：

- 表示 entity 与 source-native item 的绑定；
- 区分来源类型，如 reading / tracking / local / manual；
- 提供 provenance：`sourceKind`、`state`、`createdBy`、`updatedAt`。

规则：

- `MANUAL` 不可被自动匹配覆盖；
- `REJECTED` 阻止同 key 自动回流；
- `LEGACY` 可读但不默认视为强真相；
- candidate/cache 不得直接提升为 confirmed。

### Entity Preferences

职责：

- 保存 entity 级展示与默认选择；
- 表达默认 metadata authority；
- 表达 preferred local projection。

规则：

- entity details 默认只读 entity preferences；
- manga details 仅在显式 override 存在时才覆盖 entity 默认值；
- preference 应逐步从 raw service/remote id 演进为 binding 引用。

### Local Manga

职责：

- 表示一个本地可读的 source projection；
- 负责打开、阅读、更新等执行行为；
- 在需要时可被 entity 选为 preferred local projection。

非职责：

- 不定义 entity 级 tracking identity；
- 不默认承载 entity metadata authority。

### Tracking Site Links

职责：

- 记录 per-manga tracking match cache；
- 记录 suggestion history / audit / suppression state。

非职责：

- 不作为 entity 真相；
- 不作为 repair 中的 tracking binding corruption 主证据。

### Per-Manga Metadata Source

职责：

- 仅作为单 manga 的显式 metadata override。

规则：

- 默认不写；
- 默认不继承 entity metadata source；
- 不允许批量镜像成为 entity 默认值的影子副本。

## 代码与数据现状映射

### 核心结构

- `entity` / `entity_binding` / `relation`：
  [EntityGraphEntities.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphEntities.kt:1)
- `entity_preferences`：
  [EntityPrefsRecord.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityPrefsRecord.kt:1)
- DAO 读写：
  [EntityGraphDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphDao.kt:1)

### 主要污染入口

- entity metadata source 镜像写入：
  [ContentDataRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/parser/ContentDataRepository.kt:131)
- 调用方：
  - [DetailsViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:1815)
  - [BindTrackingToEntitiesUseCase.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/domain/BindTrackingToEntitiesUseCase.kt:408)
  - [MergeFavoriteEntitiesUseCase.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/domain/MergeFavoriteEntitiesUseCase.kt:354)
  - [AttachReadingSourceToEntityUseCase.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/domain/AttachReadingSourceToEntityUseCase.kt:53)

### repair 噪音入口

- repair 扫描逻辑：
  [EntityGraphRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:900)

当前分支已明确包含：

- `entity_tracking_binding`
- `entity_metadata_source`
- `manga_metadata_source`
- `tracking_site_link`

### 历史传播入口

- `entity_preferences` 首次迁移：
  [Migration47To48.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/db/migrations/Migration47To48.kt:1)
- restore 逻辑：
  [BackupRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/BackupRepository.kt:606)

## 保留 / 降级 / 删除决策

### 保留

1. `entity`
2. `entity_binding`
3. `relation`
4. `entity_preferences`

原因：

- 已经构成当前 graph 主模型；
- 已有 FK、state、origin 等演进基础；
- 可以在不推翻现有架构的前提下继续收敛。

### 降级

1. `preferences.metadata_source_kind`
2. `preferences.metadata_source_service`
3. `preferences.metadata_source_remote_id`
4. `tracking_site_links`

降级后语义：

- `preferences.metadata_source_*`：单 manga 显式 override only
- `tracking_site_links`：cache / audit / suggestion history only

### 退役目标

以下能力需要逐步退役：

1. entity metadata source 批量 mirror 到全部 local manga
2. 将 per-manga metadata mismatch 计入 `SUSPECT_TRACKING_BINDING`
3. 将 `tracking_site_links` 视作 entity binding 失败证据
4. 让 raw service/remote id 长期直接承担 preference 主引用语义

## 分阶段治理方案

### Phase 1：止血与诊断去噪

目标：

- 停止继续制造新污染；
- 让 repair 结果重新可信；
- 不先做高风险 schema 改造。

实施项：

1. 修正 `inspectRepairIssues()` 分类：
   - `entity_tracking_binding` -> `SUSPECT_TRACKING_BINDING`
   - `manga_metadata_source` -> `SUSPECT_METADATA_SOURCE`
   - `tracking_site_link` -> 新增或单列为 stale cache/link 问题
2. repair UI 和计数同步调整；
3. `setEntityMetadataSourceSelection()` 的调用方默认不再传多项 `mirrorLocalMangaIds`；
4. 仅保留“用户明确对单个 projection 执行 override”时的单项写入。

验收：

- suspect tracking count 显著下降，且接近真实 entity binding 问题规模；
- 新操作不再把 entity metadata source 批量复制到 local manga。

### Phase 2：语义收口

目标：

- 将 entity metadata authority 与 per-manga override 彻底区分；
- 保留兼容读能力，但运行时只认一套默认真相。

实施项：

1. 统一读取优先级：
   - entity details 默认读取 `entity_preferences`
   - manga details 先看 explicit override，否则回退 entity default
2. 所有自动绑定/合并流程仅写 entity-level metadata source；
3. per-manga metadata source 不再由 entity bind / merge 自动生成；
4. 明确 `tracking_site_links` 不再影响 entity identity 判定。

当前工作树已完成一项基础收口：

- `EntityGraphRepository.findEntityIdsByAnyMangaIds(...)`
  已移除基于 `tracking_site_links` 的 owner fallback；
- 公共 owner helper 现在只依赖 `local_manga` confirmed binding；
- cache / audit 数据不再通过通用解析入口反向定义 entity。
- `DetailsViewModel` 的 `TrackingItem` 入口也已改为 entity-first anchor 解析；
- legacy 无 entity 场景下才允许从 link cache 里选择本地 projection anchor。
- `BindTrackingToEntitiesUseCase` 在 preview 阶段已优先复用 confirmed entity binding；
- 同一个 work/entity 已经存在的 tracking binding 不再强制重新搜索。

验收：

- details、repair、bind 三条主链路都能区分 entity default 与 manga override；
- 运行时没有新的“双真相”写入路径。

### Phase 3：偏好绑定化

目标：

- 将 preference 从裸 `serviceId + remoteId` 演进到 binding 引用；
- 降低 orphan/stale raw ids 的长期维护成本。

建议做法：

优先采用兼容式增量设计，而不是一次性重做表结构。

推荐新增字段：

- `metadata_binding_source`
- `metadata_binding_external_id`
- 可选：
  - `preferred_local_binding_source`
  - `preferred_local_binding_external_id`

说明：

- 当前 `entity_binding` 的主键就是 `source + external_id`；
- 先复用现有主键语义，比引入 surrogate binding id 风险更低；
- 旧 `metadata_source_service/remote_id` 保留为兼容回读字段。

验收：

- 新写入优先写 binding reference；
- 旧字段仅兼容读取，不再作为长期主写入口。

### Phase 4：历史数据清理

目标：

- 清理历史镜像污染；
- 控制 backup/restore 的旧语义回流；
- 让 legacy 数据可见、可修、不可继续扩散。

实施项：

1. 扫描并清理被 mirror 污染的 `preferences.metadata_source_*`；
2. 清理明显不兼容的 per-manga metadata source；
3. 清理 stale `tracking_site_links`；
4. 只对可确定错误的数据做自动清理；
5. 不能确定的降级为 `LEGACY` / `SUSPECT` / `CANDIDATE`，进入 repair 流程。

验收：

- 自动清理不误伤明显可能仍有用户意图的数据；
- legacy 数据不再参与默认主流程决策。

### Phase 5：兼容字段退役

目标：

- 将旧字段从“运行时主逻辑依赖项”降为“迁移兼容资产”；
- 为后续移除历史包袱创造条件。

实施项：

1. 停止新写入旧 raw metadata 字段；
2. 在至少一个稳定周期后，把它们降级为只读兼容；
3. 最终评估是否移除或只保留数据库历史兼容。

验收：

- 不再有新代码依赖这些旧字段做主决策；
- 兼容字段即便存在，也不会继续污染新数据。

## repair 体系重构

### 推荐分类

repair 结果建议拆分为以下类别：

1. `ORPHAN_PREFERRED_LOCAL`
2. `ORPHAN_METADATA_SOURCE`
3. `SUSPECT_TRACKING_BINDING`
4. `SUSPECT_METADATA_SOURCE`
5. `CONFLICTING_READING_BINDING`
6. `SUSPECT_MISMERGED_LOCAL_WORK`
7. `STALE_LEGACY_RELATION`
8. 可选：`STALE_TRACKING_CACHE_LINK`

### 分类原则

#### `SUSPECT_TRACKING_BINDING`

只包含：

- entity-level tracking binding 与 entity/local evidence 冲突

不包含：

- per-manga metadata source mismatch
- `tracking_site_links` 漂移

#### `SUSPECT_METADATA_SOURCE`

包含：

- entity metadata source 与 entity aliases / local projections 不兼容
- per-manga explicit metadata override 不兼容

#### `STALE_TRACKING_CACHE_LINK`

包含：

- `tracking_site_links` 中陈旧、漂移或与当前本地标题不兼容的缓存匹配

它是缓存质量问题，不是 entity identity corruption。

### repair 动作拆分

建议提供独立动作：

1. 拒绝 suspect entity tracking binding
2. 清理 entity metadata source
3. 清理 per-manga metadata override
4. 清理 stale tracking cache/link
5. 拆分或 detach suspect local projection

## Backup / Restore 治理规则

### 目标

- backup 允许保存历史数据；
- restore 不允许把历史数据重新提升为当前真相；
- 跨设备同步不继续放大旧污染。

### 规则

1. restore `entity_binding` 时保留 `state` / `createdBy` / `updatedAt` 语义；
2. `LEGACY` / `SYNC` 不自动提升为 `CONFIRMED` / `MANUAL`；
3. restore `entity_preferences` 时不得反向回写 manga prefs；
4. 评估是否将 `tracking_site_links` 从核心备份资产降级为可选缓存资产；
5. backup 中出现的旧 raw metadata 结构只做兼容导入，不得重建 blind mirror。

## 数据清理策略

### 可自动清理

- entity metadata source 指向不存在 binding；
- per-manga metadata source 与本地标题完全不兼容；
- stale `tracking_site_links`；
- orphan preference / orphan relation。

### 只降级不自动删除

- restore 回来的 legacy binding；
- 兼容性存疑但无法确定错误的 metadata source；
- 历史 relation 但仍可能有参考价值的数据。

### 必须人工确认

- 疑似误合并 local work；
- 多 tracking source 互相冲突且均可能合理的 entity；
- 用户可能主动设置过的跨语种特殊 metadata override。

## PR 拆分建议

### PR-1：repair 分类止血

范围：

- `EntityGraphRepository.inspectRepairIssues()`
- repair report / viewmodel / panel 文案与计数

特点：

- 风险低；
- 收益直接；
- 不动 schema。

### PR-2：停止 blind mirroring

范围：

- `ContentDataRepository.setEntityMetadataSourceSelection()`
- 所有传入 `mirrorLocalMangaIds` 的调用方

目标：

- entity metadata source 默认只写 entity；
- per-manga override 仅保留显式单项写入。

### PR-3：preference 绑定化

范围：

- `entity_preferences` migration
- DAO / repository 读写优先级

目标：

- 引入 binding reference 字段；
- 新写路径优先写 binding reference。

### PR-4：历史清理与 restore 降噪

范围：

- backup / restore
- stale data cleanup
- 可选：`tracking_site_links` 备份范围调整

目标：

- 阻断旧语义跨设备回流；
- 对历史污染执行温和但明确的收敛。

## 验收标准

治理完成后，系统应满足以下条件：

1. `SUSPECT_TRACKING_BINDING` 基本只反映真实 entity-level tracking identity 问题；
2. entity metadata source 不再自动污染所有 local manga prefs；
3. `tracking_site_links` 明确降级为 cache / audit 数据；
4. entity / local / tracking / metadata 四层语义在代码和 repair 中都能明确区分；
5. restore 不会重新制造 mirror 型污染；
6. 旧 raw metadata 字段即便仍存在，也不再主导运行时主逻辑；
7. merge / bind / details / repair 四条主链路都有一致的边界语义。

## 非目标

本方案不包含以下内容：

- 引入远程实体中心或 graph backend；
- 重写整个详情页 UI；
- 一次性删除所有 legacy 数据；
- 通过模糊标题匹配自动决定最终身份真相；
- 为未来未落地场景预建额外抽象层。

## 推荐执行顺序

推荐按以下顺序推进：

1. repair 分类止血；
2. 停止 blind mirroring；
3. 统一 entity default 与 manga override 读取语义；
4. preference 绑定化；
5. 历史数据清理；
6. 兼容字段退役。

这样可以先解决“持续制造脏数据”和“诊断噪音失真”，再进入 schema 与迁移层面的收敛工作，风险最低，回报最高。
