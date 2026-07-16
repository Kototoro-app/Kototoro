# 同名不同类型作品被自动合并：根因分析与修复方案（2026-07）

## 摘要

用户反馈：**相同名称但不同类型**的作品（例如 MANGA 与 VIDEO、MANGA 与 NOVEL），打开后会被自动合并到同一个 WORK 实体。这违反 [entity-identity-migration-consolidation-plan-2026-06.md](./entity-identity-migration-consolidation-plan-2026-06.md) 中「实体只包含同类型投影」的约定，也违反该计划的硬性工程约束。

本文档给出根因定位、对收敛计划的违规映射，以及待确认的修复方案（**尚未执行**）。

## 问题现象

同名但 `ContentType` 不同的两个作品，先后打开后：

- 第二个作品不再创建独立 WORK 实体。
- 两个作品绑定到同一个 `entity_id`。
- 收藏、历史、统计、追踪等用户状态被错误地共享到同一身份键上。

## 根因

**核心事实：entity 身份边界完全不包含 `contentType`。**

| 层 | 现状 | 问题 |
| --- | --- | --- |
| DB 表 `entity` | 无 `content_type` 列 | 无法持久化作品类型 |
| 唯一索引 `idx_entity_name_hash` | `(type, name_hash)` UNIQUE | 同名不同类型被视为同一身份边界 |
| 领域模型 `Entity` | 无 `contentType` 字段 | 匹配阶段无法携带类型信息 |
| `pickCandidate(...)` 签名 | 只传 `type, primaryName, aliases, now` | 名称匹配时不区分类型 |
| `DefaultEntityBindingMatcher.tryBindEntities` | 只比较 `type` 与名称 | 同名 + 同 `EntityType.WORK` 即可 AUTO_BIND |
| `MangaSource.contentType` | 非空 `ContentType` | 运行时一定有值，但未被身份边界使用 |

### 入口链路

```
用户打开 projection
→ DefaultWorkResolver.ensureForProjection(content)
→ EntityGraphRepository.ensureLocalWorkEntity(content)
→ 无现成 binding 时
→ resolveOrCreateEntity(type=WORK, ..., contentType=content.source.contentType)
```

`resolveOrCreateEntity`（`EntityGraphRepository.kt:2186`）确实拿到了 `contentType`，但**只用于 `resolveMalSyncCandidate`**，传给 `pickCandidate` 时丢掉了。

### 路径 A — pickCandidate 名称匹配（`EntityGraphRepository.kt:3086`）

```kotlin
pickCandidate(type, primaryName, aliases, now)   // 无 contentType
  → dao.findEntitiesByType("WORK", 120)          // 扫描所有 WORK 实体
  → bindingMatcher.tryBindEntities(probe, entity) // 只比 type + 名称
  → 同名 → confidence = 1.0 > 0.90 → AUTO_BIND
  → mergeIntoResolvedEntity(...)                  // 直接合并 ★
```

同名不同类型作品：`type` 都是 `WORK`、名称相同 → `scoreNames` 返回 1.0 → `AUTO_BIND` → 直接合并。

### 路径 B — createEntity 的 name_hash 唯一约束 fallback（`EntityGraphRepository.kt:2557`）

```kotlin
createEntity(type=WORK, primaryName=同名, ...)
  → computeNameHash(同名)
  → dao.insertEntityIgnore(record)               // (type, name_hash) UNIQUE 冲突
  → id == -1L
  → dao.findEntityByTypeAndNameHash("WORK", hash)
  → mergeIntoResolvedEntity(...)                  // fallback 合并 ★
```

即使路径 A 因 `ENTITY_SCAN_LIMIT = 120` 没扫到候选，`INSERT OR IGNORE` 仍会因 `(type, name_hash)` 唯一冲突而 fallback 合并。

### 结论

两条路径都以 `(type=WORK, name_hash)` 为身份边界，**完全忽略 `contentType`**。同名不同类型必然被合并。这不是单点 bug，而是身份模型缺失一个维度。

## 对收敛计划的违规映射

[entity-identity-migration-consolidation-plan-2026-06.md](./entity-identity-migration-consolidation-plan-2026-06.md) 的硬性工程约束：

| 计划条款 | 要求 | 违规点 |
| --- | --- | --- |
| 硬约束 3 | 禁止仅凭标题相似度自动合并 | 路径 A 仅凭同名 + 同 `type` 即 `AUTO_BIND` |
| 硬约束 4 | `ensureForProjection` 不得因标题相似就把 projection 绑定到已有 work | `ensureForProjection` 正是这么做 |
| 核心不变量 | `entity_id` = 作品级用户状态键 | 同名不同类型应是不同作品身份 |
| Entity Identity 职责 | 只对 `WORK` 承担用户状态 owner 语义 | 不同类型作品被错合为一个 owner |
| Entity Binding 规则 | authoritative projection binding 的 `external_id` 必须是 source-scoped key | 当前用 `(type, name_hash)` 作为隐式身份键，绕过了 binding 证据职责 |

## 相关代码位置

- `app/src/main/kotlin/org/skepsun/kototoro/work/data/DefaultWorkResolver.kt` — `ensureForProjection` 入口
- `app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:873` — `ensureLocalWorkEntity`
- `app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:2186` — `resolveOrCreateEntity`（拿到 contentType 却未用于候选匹配）
- `app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:3086` — `pickCandidate`（签名无 contentType）
- `app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:2557` — `createEntity` 的 `insertEntityIgnore` + fallback 合并
- `app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:2356` — `mergeIntoResolvedEntity`
- `app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/DefaultEntityBindingMatcher.kt` — `tryBindEntities` / `classify`
- `app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphEntities.kt:29` — `EntityRecord`（无 content_type 列）
- `app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphEntities.kt:24` — `idx_entity_name_hash` 唯一索引 `(type, name_hash)`
- `app/src/main/kotlin/org/skepsun/kototoro/entitygraph/domain/EntityGraphModels.kt:11` — `Entity` 领域模型（无 contentType 字段）
- `parser-api/src/main/kotlin/org/koitharu/kotatsu/parsers/model/ContentType.kt` — `ContentType` 枚举
- `parser-api/src/main/kotlin/org/koitharu/kotatsu/parsers/model/MangaSource.kt:7` — `MangaSource.contentType` 非空来源
- `app/src/main/kotlin/org/skepsun/kototoro/core/db/entity/MangaEntity.kt:29` — `manga.content_type` 列（回填来源）

当前数据库版本：`DATABASE_VERSION = 74`（`MangaDatabase.kt:150`）。

## 修复方案（待确认，尚未执行）

核心思路：**让 `contentType` 进入 entity 的身份边界。同名不同 `ContentType` 的 WORK 是不同实体。**

### 1. Schema（Migration 74 → 75）

- `EntityRecord` 增加 `@ColumnInfo(name = "content_type") val contentType: String? = null`
- 唯一索引 `idx_entity_name_hash` 从 `(type, name_hash)` 扩展为 `(type, name_hash, content_type)`
  - SQLite 中多个 `NULL` 视为不同，`NULL` 不冲突 → 老数据 / 未知类型不会误冲突
  - 显式相同 `contentType` 才冲突，自然区分同名不同类型
- Migration 回填：`entity JOIN entity_binding(source='local_manga') JOIN manga.content_type`，把已有 WORK 实体的 `content_type` 补上（取众数或首个 active local binding 的类型）

### 2. 领域模型

- `Entity` 增加 `val contentType: ContentType? = null`
- `EntityRecord.toModel()` / `Entity.toRecord()` 补全映射

### 3. DAO

- `findEntityByTypeAndNameHash(type, nameHash)` → 增加 `contentType` 参数重载
- 可选：`findEntitiesByTypeAndContentType(type, contentType, limit)` 让 `pickCandidate` 缩小扫描范围

### 4. `pickCandidate` 加 contentType（`EntityGraphRepository.kt:3086`）

- 签名加 `contentType: ContentType?`
- probe 带上 `contentType`
- 扫描候选时只匹配 `contentType` 相同（或候选 `contentType` 为 null）的实体
- `resolveOrCreateEntity`（`:2186`）把已有的 `contentType` 传进来

### 5. `DefaultEntityBindingMatcher.tryBindEntities` 加 contentType 守卫

- 两个 entity 都有 `contentType` 且不同 → 返回 0（`IGNORE`）
- 一方为 null（老数据）：保留名称匹配，但**不升 `AUTO_BIND`**（降为 `WEAK_BIND`），避免老数据污染继续吸附

### 6. `createEntity` 的 fallback（`EntityGraphRepository.kt:2557`）

- `insertEntityIgnore` 冲突后，`findEntityByTypeAndNameHash` 改用带 `contentType` 的查询
- 同名但不同 `contentType` 不再命中 → 创建独立实体

### 7. 已污染数据修复（diagnostic + repair）

- 新增 repair issue kind：检测同一 entity 下 active local bindings 包含不同 `contentType` 的 manga
- 复用现有 entity organize split 流程，按 `contentType` 分组拆出独立 entity
- 可提供批量自动拆分（按 `contentType` 分组）

### 8. 测试要点

- 单测：同名 `MANGA` + `VIDEO` 两个 content，`ensureForProjection` 各自产生独立 entity
- 单测：`pickCandidate` 不跨 `contentType` 匹配
- 单测：`createEntity` 同名不同 `contentType` 不触发 fallback 合并
- DAO 测试：`(type, name_hash, content_type)` 唯一性
- Migration 测试：回填后老 WORK 实体 `content_type` 正确

## 影响面与风险

- `ensureLocalWorkEntities` 批量入口走 `createEntity`，会受唯一索引扩展影响 → 同名不同类型不再冲突，**正向修复**
- `mergeEntities` / `mergeLocalWorkEntities` 合并时需校验 `contentType` 一致，否则拒绝（与现有 manual merge `same_type_guard` 对齐）
- backup / restore 的 entity 重建需带上 `content_type` 列
- `computeNameHash` 本身无需改，唯一索引扩展即可

## 状态

- 根因定位：已完成
- 修复方案：已定稿，待确认
- 执行：尚未开始
