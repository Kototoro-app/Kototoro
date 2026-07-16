# 同名不同内容类型作品的 Work 身份合并与详情投影泄漏：根因分析与修复计划（2026-07）

## 摘要

已确认这是一个以实体身份污染为主、详情页 fallback 泄漏为表现的组合问题：

1. Entity Graph 的 Work 身份边界没有包含 `ContentType`，同名漫画和动画可能被绑定到同一个 `entity_id`；这才是必须修复的主问题。
2. 详情页在加载一个 Work 的本地投影时，没有按当前投影的内容类型或当前 Space 过滤 active local bindings，因此已污染实体中的漫画投影会出现在动画详情的播放源面板和章节来源面板中；这是对历史错误实体的 fallback 防线，不能替代实体拆分。

这违反 [entity-identity-migration-consolidation-plan-2026-06.md](./entity-identity-migration-consolidation-plan-2026-06.md) 中“实体只包含同类型投影”的约定，也违反该计划关于禁止仅凭标题相似度自动确认身份的硬性约束。

本文档记录已通过代码检查和测试验证的事实、完整复现链路、修复边界以及对应 Trellis 任务：
`.trellis/tasks/07-16-work-content-type-isolation/`。

## 用户可复现现象

以“庙不可言”为例：

1. 用户先打开漫画版本并阅读过。
2. 用户切换到视频 Space，搜索同名动画并打开。
3. 动画详情页可以看到之前漫画投影的播放源。
4. 章节面板的阅读/播放来源 Tab 中也包含漫画来源和漫画章节。

这说明污染不止影响收藏、历史、统计、追踪等用户状态键，也已经泄漏到了详情页的投影选择和章节展示层。

## 已验证结论

### 1. Entity 身份边界缺少 `ContentType`（主根因）

修复前实现的事实如下：

| 层 | 当前实现 | 结论 |
| --- | --- | --- |
| DB 表 `entity` | `EntityRecord` 没有 `content_type` 列 | Work 类型无法持久化 |
| 唯一索引 `idx_entity_name_hash` | `(type, name_hash)` UNIQUE | 同名不同内容类型共享冲突边界 |
| 领域模型 `Entity` | 没有 `contentType` 字段 | 匹配器无法比较内容类型 |
| `pickCandidate` | 只接收 `type, primaryName, aliases, now` | 名称候选不区分内容类型 |
| `DefaultEntityBindingMatcher` | 先比较 `EntityType`，再比较名称 | 同名 Work 可得到 `1.0` |
| `MangaSource.contentType` | 来源提供非空内容类型 | 输入信息存在，但未进入 Entity 身份边界 |

证据：

- Entity 表索引和字段：[EntityGraphEntities.kt:20-40](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphEntities.kt:20)
- DAO 的类型/名称哈希查询：[EntityGraphDao.kt:60-100](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphDao.kt:60)
- `Entity` 领域模型和映射均无内容类型：[EntityGraphModels.kt:13-21](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/domain/EntityGraphModels.kt:13)、[EntityGraphMapping.kt:16-24](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphMapping.kt:16)

### 2. 同名自动合并的真实链路（修复前）

单投影详情入口会把 `content.source.contentType` 传入 `resolveOrCreateEntity`：[EntityGraphRepository.kt:873-946](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:873)。但该参数目前只用于 MAL-Sync 类型映射，调用 `pickCandidate` 时被丢弃：[EntityGraphRepository.kt:2186-2257](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:2186)。

#### 路径 A：名称候选自动绑定

```text
resolveOrCreateEntity(type=WORK, ..., contentType=currentType)
→ resolveAnimeOfflineCandidate / resolveMalSyncCandidate 未命中
→ pickCandidate(type, primaryName, aliases, now)
→ findEntitiesByType("WORK", ENTITY_SCAN_LIMIT)
→ matcher 只比较 EntityType 和名称
→ 同名得到 confidence = 1.0
→ classify = AUTO_BIND
→ mergeIntoResolvedEntity
```

对应代码：[pickCandidate](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:3086)、[DefaultEntityBindingMatcher.tryBindEntities](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/DefaultEntityBindingMatcher.kt:23)。

这里的“会合并”不是无条件必然发生：候选必须在前 `ENTITY_SCAN_LIMIT` 条结果内，并且此前没有命中已存在 binding、Anime Offline 或 MAL-Sync 的更强映射。但在同名候选可见时，内容类型不会阻止 `AUTO_BIND`。

#### 路径 B：`name_hash` 唯一索引 fallback

```text
createEntity(type=WORK, primaryName=sameTitle, ...)
→ computeNameHash(sameTitle)
→ INSERT OR IGNORE
→ (type, name_hash) 冲突
→ findEntityByTypeAndNameHash("WORK", hash)
→ mergeIntoResolvedEntity
```

对应代码：[EntityGraphRepository.kt:2530-2573](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:2530)。

批量入口 `ensureLocalWorkEntities` 直接调用 `createEntity`，当前连 `contentType` 参数都没有传递：[EntityGraphRepository.kt:604-680](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:604)。

### 3. 详情页投影泄漏的真实链路（历史脏数据的表现）

视频搜索结果打开详情时，会先通过 `workResolver.resolveByMangaId(content.id)` 解析实体，并以 `DetailsOrigin.EntityGraph` 打开详情：[ContentListActivity.kt:233-251](../../app/src/main/kotlin/org/skepsun/kototoro/search/ui/ContentListActivity.kt:233)。如果漫画和动画已共享 `entity_id`，详情页上下文就是同一个 Work。

详情页随后执行：

```text
applyEntityContext(entityId)
→ entityGraphRepository.getBindings(entityId)
→ buildActiveLocalSourceOptions(all bindings)
→ updateSourceOptions()
→ updateChapterSourceTabs()
```

关键事实：

1. `applyEntityContext` 获取实体全部 bindings，并直接构建 active local source options：[DetailsViewModel.kt:1463-1516](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:1463)。
2. `buildActiveLocalSourceOptions` 只检查 binding 是否为本地阅读来源，没有检查 `ContentType`、当前请求投影类型或 Space：[DetailsViewModel.kt:4091-4115](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:4091)。
3. 所有 active local options 都被转换为阅读/播放源选项：[DetailsViewModel.kt:2523-2537](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:2523)。
4. 所有阅读源选项又被转换为章节来源 Tab：[DetailsViewModel.kt:2567-2608](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:2567)。

因此，问题不是“视频 Space 搜索结果把漫画搜出来了”，而是“动画和漫画先被错误归入同一 Work，详情页又把该 Work 的所有投影无条件暴露出来”。

### 4. Space 过滤没有覆盖详情页

Space 模型有 `allowedContentTypes`，列表聚合查询也存在可选的 `allowedContentTypes` 过滤：[SpaceModels.kt:15-25](../../app/src/main/kotlin/org/skepsun/kototoro/space/domain/SpaceModels.kt:15)、[WorkAggregateRepository.kt:528-549](../../app/src/main/kotlin/org/skepsun/kototoro/work/domain/WorkAggregateRepository.kt:528)。

但 `DetailsViewModel` 的构造参数没有 `SpaceId` 或允许的内容类型：[DetailsViewModel.kt:465-505](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:465)，`DetailsScreen.activeSpaceId` 当前主要用于显示 Space 切换按钮：[DetailsScreen.kt:289-315](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/compose/DetailsScreen.kt:289)。详情页投影加载因此没有使用 Space 过滤条件。

## 对收敛计划的违规映射

[entity-identity-migration-consolidation-plan-2026-06.md](./entity-identity-migration-consolidation-plan-2026-06.md) 的相关约束：

| 计划条款 | 当前违规 |
| --- | --- |
| 禁止仅凭标题相似度自动合并 | 同名 Work 可直接得到 `AUTO_BIND` |
| `ensureForProjection` 不得成为隐式 owner 制造器 | 新投影会因名称候选吸附到已有 Work |
| `entity_id` 是作品级用户状态键 | 漫画和动画共享收藏、历史、统计等状态 |
| Entity 只包含同类型投影 | 详情页把漫画投影暴露给动画详情 |
| binding 必须承担 source-scoped 证据职责 | `(type, name_hash)` 被错误地当作隐式身份依据 |

## 修复目标与不变量

修复后必须满足：

1. 同名但不同 `ContentType` 的 Work 使用不同 `entity_id`。
2. 同一 `ContentType` 的多个来源投影仍可以聚合到同一个 Work，但必须有可靠 binding 或明确的用户操作作为依据。
3. 详情页默认只展示与当前请求投影内容类型一致的本地投影；如果存在 Space 约束，则还必须满足 Space 的 `allowedContentTypes`。
4. 内容类型未知或历史数据缺失时，不得因为标题相同而自动升级为 `AUTO_BIND`。
5. 已污染实体中的收藏、历史、统计、追踪和投影 binding 在拆分后必须可追溯，不能静默丢失。

## 修复方案

### 1. Entity Schema 与领域模型

- `EntityRecord` 增加 nullable `content_type`。
- `Entity` 增加 `ContentType?` 并完成 Record/Model 双向映射。
- `idx_entity_name_hash` 从 `(type, name_hash)` 扩展为 `(type, name_hash, content_type)`。
- 所有创建路径都必须传递内容类型，包括单投影入口、批量入口、detached entity、reset、restore 和同步导入。
- 所有按 `(type, name_hash)` 查询的冲突检测和 fallback 都必须升级为内容类型感知查询。

### 2. Migration 74 → 75 与历史数据

迁移不能简单地给一个混合实体选择“众数”或“首个”内容类型。当前采用“schema migration + 可重试 repair”两阶段，避免数据库升级阶段静默改变用户状态归属：

- 类型明确且只有一个分组时，回填该类型。
- 类型明确但存在多个分组时，迁移保留 `content_type = null`，交由实体整理页的 `MIXED_WORK_CONTENT_TYPES` 诊断和一键拆分；不在 migration 中选 survivor。
- 类型缺失或无法判定的 projection 不得被任意归类，应进入 repair/review，避免迁移再次制造错误身份。
- repair 复用现有 split ledger，保留 `sync_id`、binding provenance、用户状态和操作记录。

### 3. Resolver、Matcher 与手动合并

- `pickCandidate` 和 `EntityBindingMatcher` 增加 `contentType` 守卫。
- 两个 Work 的内容类型都明确且不同时，候选必须是 `IGNORE`。
- 一方内容类型为空时，名称匹配最多生成弱候选，不能自动绑定。
- authoritative source-scoped binding、Anime Offline、MAL-Sync 映射命中后，也必须校验目标 Work 的内容类型；不能让强映射绕过类型边界。
- `mergeEntities` 与 `mergeLocalWorkEntities` 在执行前拒绝内容类型冲突。当前代码不存在可复用的 `same_type_guard`，需要显式实现。

### 4. 详情页运行时防御

在数据库修复完成前，详情页必须先阻断已污染数据的 UI 泄漏：

- 从当前请求投影解析有效 `ContentType`。
- `buildActiveLocalSourceOptions` 只保留同类型 local projections。
- `readingSourceOptions` 和 `readingChapterTabs` 只能从过滤后的集合生成。
- 如果详情由 Space 打开，过滤条件取“当前投影类型”与 Space `allowedContentTypes` 的交集。
- 详情页在无 Space 上下文打开时，仍必须按当前投影类型过滤，不能依赖 Space 作为唯一防线。
- 过滤结果为空时保留当前投影作为唯一 fallback，但不能重新加入其他类型的 projection。

### 5. 已污染数据的诊断与拆分

当前已有 `SUSPECT_MISMERGED_LOCAL_WORK`，但它主要检查标题不一致：[EntityGraphRepository.kt:1672-1700](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:1672)。本任务新增明确的 `MIXED_WORK_CONTENT_TYPES` issue kind，检测同一 Work 下 active local bindings 的已知内容类型冲突，并在实体整理页顶部仅在发现问题时显示修复卡片。

repair 复用现有 `splitLocalWorkProjection` 能力，同时：

- 保留一个明确的 survivor 类型；默认按已有 entity 类型、投影数量和稳定类型名确定，详情页后续可传入当前投影作为优先类型。
- 对 null/未知类型不做无依据拆分；它们保留为待诊断数据，不会被自动归入某一类型。
- 不因 repair 方便而把不同类型重新合并。
- 每个投影沿用现有 split ledger、binding provenance 和收藏/历史/统计锚点迁移逻辑。
- 拆分后重新协调投影 `sync_id` 时，必须先检查唯一值是否仍被旧实体占用；如果占用，保留新实体已有的唯一 ID，不能直接覆盖成冲突的投影 ID。
- 诊断通过时不显示任何异常提示；只有 `MIXED_WORK_CONTENT_TYPES` 数量大于零时，实体整理页顶部才显示“一键拆分”卡片。

### 6. Backup、Restore 与 DAO 投影

需要同步更新：

- `EntityGraphDao.dumpEntities()` 的显式字段列表；当前查询不会自动带出新列：[EntityGraphDao.kt:32-40](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphDao.kt:32)。
- Google Drive `SyncEntityRecord`、导出和导入映射。
- restore/merge 的内容类型冲突策略。
- Room schema、MigrationTestHelper 和数据库版本测试。

### 7. 测试

至少覆盖：

- 同名 MANGA + VIDEO：各自产生不同 Work entity。
- 同名同类型多来源：仍能按可靠 binding 聚合。
- `pickCandidate` 不跨内容类型匹配。
- null content type 不会触发自动绑定。
- `createEntity` 的唯一索引 fallback 不跨内容类型合并。
- 批量 `ensureLocalWorkEntities` 传递并持久化内容类型。
- 详情页 source options/chapter tabs 不显示其他内容类型。
- Migration 74 → 75 对单类型实体安全回填；混合实体保留 `content_type = null`，交由实体整理诊断/repair 拆分，避免迁移阶段用“首个/众数”静默丢失归属。
- Entity organize diagnostics 在无问题时不显示卡片，在发现混合类型 Work 时显示顶部修复卡片；一键修复复用 projection split 并保留用户状态。
- 手动 merge、backup/restore 和 sync 不跨内容类型丢失或合并实体。

## 影响面与风险

- 新 schema 会改变 Entity Graph 的唯一性和所有冲突查询，必须同时更新所有创建、更新、restore 和 merge 路径。
- 详情页过滤是必要的兼容性防线，但不能替代数据 repair；否则状态仍然挂在错误的 `entity_id` 上。
- 历史混合实体拆分存在状态归属歧义，不能用标题或“首个 binding”静默决定全部归属。
- `computeNameHash` 本身不需要包含内容类型；内容类型应作为独立持久化字段和唯一索引维度。

## 当前实现状态

- 根因定位：已完成
- 详情页投影泄漏链路：已完成
- 修复边界：已完成
- Trellis 任务：`.trellis/tasks/07-16-work-content-type-isolation/`，当前为 `in_progress`
- Entity schema、Room 74 → 75、resolver/matcher/merge guard、backup/sync 字段贯通：已实现
- 详情页按当前投影类型和 Space 过滤：已实现，作为历史脏数据的运行时防线
- 混合类型 Work 诊断与实体整理页顶部一键拆分：已实现
- repair 拆分中的 `sync_id` 唯一冲突保护：已实现并补充单测
- 剩余工作：补齐迁移/repair/restore 的回归测试并执行 Trellis quality check
