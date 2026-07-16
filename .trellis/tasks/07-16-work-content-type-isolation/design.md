# 技术设计：跨内容类型 Work 身份隔离与详情投影过滤

## 1. 设计目标

将 `ContentType` 提升为 Work 身份边界的一部分，并在详情页建立运行时防御，使新数据和已有污染数据都不会发生跨类型投影泄漏。

核心不变量：

```text
WORK identity = (entity_id, content_type)
同一 entity_id 下的 authoritative local projections 必须属于同一内容类型
```

`entity_id` 仍是用户状态 owner；`manga_id` 仍是来源 projection。内容类型不是 `name_hash` 的组成字符串，而是 Entity 的独立持久化字段和索引维度。

## 2. 当前数据流与缺陷边界

### 2.1 身份解析

```text
Content / source.contentType
→ DefaultWorkResolver.ensureForProjection
→ EntityGraphRepository.ensureLocalWorkEntity
→ resolveOrCreateEntity
→ source-scoped binding / Anime Offline / MAL-Sync / name candidate / createEntity
```

当前 `contentType` 只被 MAL-Sync 映射消费，名称候选和唯一索引冲突查询均不感知它。

### 2.2 详情投影展示

```text
搜索结果 content.id
→ resolveByMangaId(content.id)
→ DetailsOrigin.EntityGraph(entityId, initialProjectionLocalMangaId)
→ DetailsViewModel.applyEntityContext
→ getBindings(entityId)
→ buildActiveLocalSourceOptions
→ readingSourceOptions / readingChapterTabs
```

详情页当前只按 local reading binding 过滤，没有按内容类型或 Space 过滤。`DetailsScreen.activeSpaceId` 目前不是 ViewModel 的数据过滤输入，因此不能承担该不变量。

## 3. 数据模型与迁移

### 3.1 Entity schema

`EntityRecord` 新增：

```kotlin
@ColumnInfo(name = "content_type")
val contentType: String? = null
```

Entity domain model 使用 `ContentType?`，映射层负责安全解析未知字符串为 null。

唯一索引改为：

```kotlin
Index(
    name = "idx_entity_name_hash",
    value = ["type", "name_hash", "content_type"],
    unique = true,
)
```

SQLite 的 nullable unique column 允许多个 null，但这只解决未知类型之间的写入冲突，不允许未知类型实体参与标题自动确认。

### 3.2 Migration 74 → 75

迁移顺序：

1. 创建新的 nullable `content_type` 列。
2. 读取 active local bindings 对应的 `manga.content_type`，生成 entity-to-content-type 分组信息。
3. 单类型实体直接回填。
4. 多类型实体保留 `content_type = null`，不在 schema migration 中选择 survivor 或按众数拆分；实体整理页的诊断/repair 负责按明确投影类型拆分。
5. null/未知类型不做任意归类，保留给诊断/repair review。
6. 删除旧索引并创建 `(type, name_hash, content_type)` 新索引。
7. 更新 Room schema 与 migration tests。

迁移必须在唯一索引切换前后保持事务一致性；如果拆分逻辑过重，应将 schema migration 与可重试 repair worker 分成两个明确阶段，但新写入必须先被详情运行时防御隔离。

## 4. Resolver 与 Matcher

### 4.1 强证据路径

已有 source-scoped binding 仍优先，但如果目标是 WORK，必须验证来源 projection 的内容类型与目标 entity 类型一致。Anime Offline/MAL-Sync 的映射也必须经过同一守卫，不能因 mapping 强度绕过类型边界。

### 4.2 名称候选路径

`pickCandidate` 接收 `contentType`，probe 的 Entity 带上该字段；DAO 可以按 `(type, content_type)` 缩小候选集，但必须保留对 legacy null entity 的显式处理。

推荐匹配规则：

| probe | candidate | 名称相同的结果 |
| --- | --- | --- |
| 明确类型 A | 明确类型 B | `IGNORE` |
| 明确类型 A | 明确类型 A | 按现有证据规则处理，不因标题单独确认 |
| 明确类型 A | null | 最多 `WEAK_BIND`，不得 `AUTO_BIND` |
| null | 任意 | 不得仅凭标题 `AUTO_BIND` |

### 4.3 创建与 fallback

`createEntity` 接收 `contentType`，写入 EntityRecord；`insertEntityIgnore` 冲突后的查询使用同一 content type。所有直接构造 EntityRecord 的路径都必须检查：批量入口、detached entity、reset、restore、sync import、repair split。

### 4.4 Manual merge

`mergeEntities` 和 `mergeLocalWorkEntities` 在任何 binding/relation 重映射前读取全部 source/target entity 的内容类型；明确冲突直接返回失败并记录原因。null 与明确类型的合并不应自动通过，除非用户在 repair UI 中明确确认并产生 provenance。

## 5. 详情页运行时过滤

### 5.1 过滤输入

优先使用当前请求 projection 的持久化 `manga.content_type`；必要时回退到 `ContentSource(manga.source).getContentType()`。详情打开方式不应改变隔离规则。

如果详情拥有 Space 上下文，则有效过滤集合为：

```text
allowedTypes = currentProjectionType ∩ space.allowedContentTypes
```

如果 Space 未提供上下文，使用当前 projection type；不能把“无 Space”解释为“允许全部类型”。

### 5.2 单一过滤点

在 `buildActiveLocalSourceOptions` 建立唯一的 local projection 过滤点：

- 只保留 active local bindings。
- 读取 projection 内容类型。
- 过滤到有效类型集合。
- 当前请求 projection 即使是唯一候选，也必须作为 fallback 保留。

`readingSourceOptions`、`readingChapterTabs`、`EntityChapterSourceInfo.projectionCount` 全部只能消费过滤后的结果，禁止各自重新从 entity bindings 读取。

这能防止已污染数据库继续把漫画暴露给动画详情，但不改变错误 `entity_id` 上的用户状态，因此必须与 repair 同时交付。

## 6. 历史污染修复

现有 `SUSPECT_MISMERGED_LOCAL_WORK` 可扩展为内容类型冲突诊断，也可以新增专用 issue kind。本实现新增 `MIXED_WORK_CONTENT_TYPES`，诊断输出至少包括：entityId、localMangaId、source、externalId、冲突类型数量和 repair provenance。

拆分策略：

- 明确类型冲突：`inspectRepairIssues` 生成 `MIXED_WORK_CONTENT_TYPES`；实体整理页顶部只在 issue 数量大于零时展示修复卡片。
- 一键修复保留 survivor 类型，将其他明确类型 projection 逐个交给现有 `splitLocalWorkProjection`，由既有 ledger、binding provenance 和 anchor migration 处理状态归属。
- null/未知类型：不作为自动拆分依据，保留在 review；不能因修复方便而把它们并入某个明确类型 Work。
- 默认 survivor 选择使用已持久化 entity 类型，其次按明确投影数量和稳定类型名；从详情页触发时可优先保留当前投影类型。

## 7. Backup / Restore / Sync

新增字段必须贯穿：

```text
EntityRecord
→ EntityGraphDao.dumpEntities
→ GoogleDrive SyncEntityRecord
→ sync merger / restore mapping
→ EntityRecord insert/upsert
```

旧备份没有 `content_type` 时按 null 导入，不能用标题自动补齐或自动合并；恢复后的 null entity 只能进入兼容候选流程，不得成为跨类型自动 owner。

## 8. 兼容性与回滚

- Migration 75 必须支持从版本 74 升级，保留旧备份读取能力。
- 内容类型字段使用 nullable，兼容旧数据和旧备份。
- 运行时过滤可以先独立上线作为安全网；如果 migration/repair 失败，过滤仍能阻止详情页跨类型泄漏。
- 任何拆分操作都必须有 ledger/provenance，支持重试和诊断，不使用破坏性全库 reset。

## 9. 验证策略

分层验证：

1. domain/matcher 单测：内容类型矩阵和 AUTO/WEAK/IGNORE 分类。
2. repository/DAO 测试：候选、唯一索引 fallback、批量入口和 merge guard。
3. Room migration test：74 → 75、单类型回填、多类型保留 null、null 类型。
4. details data-flow test：漫画/视频同名污染 fixture 下，source options 与 chapter tabs 只保留当前类型。
5. backup/restore/sync tests：字段保留、旧 payload 兼容、冲突不自动合并。
6. repair report/UI test：无 `MIXED_WORK_CONTENT_TYPES` 时不显示卡片；存在时统计实体/投影并可调用一键拆分。
7. Gradle compile/unit/instrumentation quality gate。
