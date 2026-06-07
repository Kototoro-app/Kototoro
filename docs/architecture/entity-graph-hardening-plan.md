# EntityGraph 数据完整性加固方案

## 目的

本文档是对 `entity-graph-implementation-plan.md` 的安全补充，聚焦于当前 entitygraph 模块已发现的 7 个数据完整性/性能漏洞的修复方案。

## 审查基准

- 审查范围：`entitygraph/` 模块全部 16 个 Kotlin 源文件 + 相关 migration + DAO
- 审查日期：2026-06-07
- 审查者：Pi Coding Agent（自动化架构分析）
- 总漏洞数：7（严重 2、高危 2、中危 2、低危 1）

## 根因分析

entity 表的设计文档将其定位为 *cache-like and deletable* 的本地缓存层，但实现缺少缓存应有的核心属性：

| 缓存属性 | 期望行为 | 当前实现缺陷 |
|---------|---------|-------------|
| 幂等性 | 同一 key 多次写入 = 同一个实体 | 仅 binding 层有 PK，实体层无唯一约束 |
| 去重 | 同名实体自动合并 | 依赖脆弱的 Levenshtein 启发式（阈值 0.72） |
| 引用完整性 | 删除实体时级联清理关联数据 | binding/relation 表无 FK 约束，依赖手动代码顺序 |
| 并发安全 | 并发写入不产生重复或损坏 | `resolveOrCreateEntity` 分阶段读写，写偏斜窗口存在 |

---

## 漏洞修复方案

### VULN-1: `entity_binding` 和 `relation` 表缺少外键约束 [严重: 8/10]

**位置**：`Migration36To37.kt` — DDL 中未声明 FOREIGN KEY

**现状**：
```sql
-- Migration36To37.kt: 无 FK 的当前 DDL
CREATE TABLE `entity_binding` (
    `entity_id` INTEGER NOT NULL,
    `source` TEXT NOT NULL,
    `external_id` TEXT NOT NULL,
    `confidence` REAL NOT NULL,
    `is_primary` INTEGER NOT NULL,
    PRIMARY KEY(`source`, `external_id`)
);

CREATE TABLE `relation` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `from_entity_id` INTEGER NOT NULL,   -- 无 FK 约束
    `to_entity_id` INTEGER NOT NULL,     -- 无 FK 约束
    `type` TEXT NOT NULL,
    `weight` REAL NOT NULL,
    `created_at` INTEGER NOT NULL
);
```

**修复方案**：

Room 实体注解是首选方案——类型安全、可被 KSP 验证、与现有代码风格一致：

```kotlin
// EntityGraphEntities.kt — 修改后

@Entity(
    tableName = TABLE_ENTITY_GRAPH_BINDING,
    primaryKeys = ["source", "external_id"],
    foreignKeys = [
        ForeignKey(
            entity = EntityRecord::class,
            parentColumns = ["id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_binding_entity", value = ["entity_id"]),
        Index(name = "idx_binding_external", value = ["source", "external_id"]),
    ],
)
data class EntityBindingRecord(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "external_id") val externalId: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "is_primary") val isPrimary: Boolean,
)

@Entity(
    tableName = TABLE_ENTITY_GRAPH_RELATION,
    foreignKeys = [
        ForeignKey(
            entity = EntityRecord::class,
            parentColumns = ["id"],
            childColumns = ["from_entity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EntityRecord::class,
            parentColumns = ["id"],
            childColumns = ["to_entity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_relation_from", value = ["from_entity_id"]),
        Index(name = "idx_relation_to", value = ["to_entity_id"]),
        Index(name = "idx_relation_unique", value = ["from_entity_id", "to_entity_id", "type"], unique = true),
    ],
)
data class RelationRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "from_entity_id") val fromEntityId: Long,
    @ColumnInfo(name = "to_entity_id") val toEntityId: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "weight") val weight: Float,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
```

**配套修改**：

```kotlin
// MangaDatabase.kt — 添加 FK 迁移
// 新的 MigrationXXToXX 类：
class MigrationFkConstraints : Migration(from, to) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // SQLite 不支持 ALTER TABLE ADD CONSTRAINT，需要重建表
        db.execSQL("""
            CREATE TABLE entity_binding_new (
                entity_id INTEGER NOT NULL,
                source TEXT NOT NULL,
                external_id TEXT NOT NULL,
                confidence REAL NOT NULL,
                is_primary INTEGER NOT NULL,
                PRIMARY KEY(source, external_id),
                FOREIGN KEY(entity_id) REFERENCES entity(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("INSERT INTO entity_binding_new SELECT * FROM entity_binding")
        db.execSQL("DROP TABLE entity_binding")
        db.execSQL("ALTER TABLE entity_binding_new RENAME TO entity_binding")
        db.execSQL("CREATE INDEX idx_binding_entity ON entity_binding(entity_id)")
        db.execSQL("CREATE INDEX idx_binding_external ON entity_binding(source, external_id)")
        
        // relation 表同理
        // ...
    }
}
```

**风险**：迁移需要锁表 + 全表复制，在实体积大的设备上可能耗时数百毫秒。考虑在后台 migration worker 中执行。

**可简化替代方案**：不做 FK 迁移，改为在 `pruneStaleEntities` 前后增加验证步骤：

```kotlin
suspend fun verifyIntegrity(): List<String> {
    val orphanBindings = db.query("""
        SELECT b.rowid FROM entity_binding b 
        LEFT JOIN entity e ON b.entity_id = e.id 
        WHERE e.id IS NULL
    """)
    val orphanRelations = db.query("""
        SELECT r.id FROM relation r 
        LEFT JOIN entity e1 ON r.from_entity_id = e1.id
        LEFT JOIN entity e2 ON r.to_entity_id = e2.id
        WHERE e1.id IS NULL OR e2.id IS NULL
    """)
    // log and clean up
}
```

**建议**：采用 FK 注解方案（Room 原生支持，安全且可维护），耦合一个新的 migration 版本号。

---

### VULN-2: `mergeEntities` 中的 binding 静默覆盖 [严重: 8/10]

**位置**：`EntityGraphRepository.kt` — `mergeEntities()` 和 `mergeLocalWorkEntities()`

**问题代码**：
```kotlin
// 第 254-258 行
distinctSourceIds.forEach { sourceEntityId ->
    dao.findBindingsByEntity(sourceEntityId).forEach { binding ->
        dao.upsertBinding(
            binding.copy(
                entityId = targetEntityId,
                isPrimary = false,
            ),
        )
    }
}
```

`entity_binding` 的 PK 是 `(source, external_id)`。如果 target 实体已经有一个同 key 的 binding，source 实体的旧值（包括 `confidence`, `isPrimary`）会静默覆盖。

**场景复现**：
1. 作品 A 从 Bangumi 导入 → entity_1, binding(confidence=1.0, source=bangumi, extId=42)
2. 作品 A 也从 MAL 导入，通过 AnimeOffline 解析到同一 entity_1 → binding(confidence=0.98, source=mal, extId=7)
3. 用户手动合并 entity_1 和 entity_2
4. entity_2 的旧 binding(confidence=0.5, source=bangumi, extId=42) 覆盖了 entity_1 的好 binding

**修复方案**：

```kotlin
// EntityGraphRepository.kt — mergeEntities() 内

distinctSourceIds.forEach { sourceEntityId ->
    dao.findBindingsByEntity(sourceEntityId).forEach { sourceBinding ->
        val existingTargetBinding = dao.findBinding(sourceBinding.source, sourceBinding.externalId)
        when {
            existingTargetBinding == null -> {
                // Target 没有这个 binding——直接迁移
                dao.upsertBinding(
                    sourceBinding.copy(entityId = targetEntityId, isPrimary = false)
                )
            }
            existingTargetBinding.confidence < sourceBinding.confidence -> {
                // Target 的 binding 置信度更低——用 source 的值覆盖
                dao.upsertBinding(
                    sourceBinding.copy(
                        entityId = targetEntityId,
                        isPrimary = existingTargetBinding.isPrimary, // 保留 target 的 isPrimary
                    )
                )
            }
            else -> {
                // Target 的 binding 置信度相等或更高——保留 target，仅记录日志
                // source binding 将被丢弃（source entity 本身也会被删除）
            }
        }
    }
}
```

**额外保护**：在 `mergeEntities` 方法头部添加快速检查：

```kotlin
// 不要合并自己
val distinctSourceIds = sourceEntityIds
    .asSequence()
    .filter { it != targetEntityId }
    .distinct()
    .toList()
if (distinctSourceIds.isEmpty()) return@withTransaction targetEntityId
```

---

### VULN-3: 并发实体创建竞态条件 [高危: 7/10]

**位置**：`EntityGraphRepository.kt` — `resolveOrCreateEntity()`

**问题描述**：

两个并发请求（如同时打开 Bangumi 和 MAL 的同一作品详情页）可能：
1. 同时发现 entity_binding 中不存在对应记录
2. 同时进入 `pickCandidate` 扫描 → 都返回 IGNORE（名称差异导致匹配分 < 0.60）
3. 各自创建独立的实体 → 同一作品生成了两个树根

**修复方案 A（推荐）**：添加实体层面的唯一约束——名称 hash 列

```kotlin
// EntityRecord 增加 name_hash 列
@Entity(
    tableName = TABLE_ENTITY_GRAPH_ENTITY,
    indices = [
        Index(name = "idx_entity_name", value = ["primary_name"]),
        Index(name = "idx_entity_name_hash", value = ["type", "name_hash"], unique = true),
    ],
)
data class EntityRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "primary_name") val primaryName: String,
    @ColumnInfo(name = "name_hash") val nameHash: Long,  // 新增
    @ColumnInfo(name = "aliases") val aliases: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_accessed") val lastAccessed: Long,
    @ColumnInfo(name = "access_count") val accessCount: Int,
)
```

name_hash 的生成规则：`MurmurHash3(normalizeName(primaryName).toByteArray())` 或利用已有的 `String.longHashCode()`。

```kotlin
// 在 createEntity 中使用 INSERT OR IGNORE 语义
private suspend fun createEntity(...): Entity {
    val dao = db.getEntityGraphDao()
    val nameHash = normalizeName(primaryName.trim()).longHashCode()
    
    // 改用 insertOrIgnore，捕获主键冲突
    val id = dao.insertEntityOrIgnore(
        EntityRecord(
            type = type.name,
            primaryName = primaryName.trim(),
            nameHash = nameHash,
            aliases = encodeStringList(mergeAliases(primaryName, aliases).drop(1)),
            createdAt = now,
            lastAccessed = now,
            accessCount = 1,
        ),
    )
    if (id == -1L) {
        // 冲突了——另一个并发请求已经创建了这个实体，回退到查询
        val existing = dao.findEntityByTypeAndNameHash(type.name, nameHash)
        if (existing != null) {
            return mergeIntoResolvedEntity(
                entity = existing.toModel(),
                primaryName = primaryName,
                aliases = aliases,
                source = source,
                externalId = externalId,
                confidence = confidence,
                now = now,
            )
        }
        // 理论上不应该到这里，如果到了说明 hash 冲突
    }
    // ... 插入 binding
}
```

**修复方案 B（轻量）**：使用 `INSERT OR IGNORE` + 事后重查

不引入新列，在 `createEntity` 中用事务级别的重试：

```kotlin
// EntityGraphDao.kt
@Insert(onConflict = OnConflictStrategy.IGNORE)
abstract suspend fun insertEntityIgnore(entity: EntityRecord): Long
```

如果返回 -1（IGNORE 生效），就在同一事务内重新查询并返回已有实体。

**推荐**：方案 A，因为 name_hash 还能显著加速 `pickCandidate` 的预过滤（见 VULN-5）。

---

### VULN-4: 无名称级唯一性约束 [高危: 7/10]

**位置**：`Migration36To37.kt` — 仅创建了普通索引

**修复方案**：与 VULN-3 的方案 A 合并处理——通过 `(type, name_hash)` UNIQUE 约束同时解决两个问题。

如果不想引入 hash 列，可以复用 VULN-3 的方案 B（幂等 insert）。

**核心原则**：`ensureLocalWorkEntities` 已通过 `existingBindings` 做了去重，但 `resolveOrCreateEntity` 的非 binding 路径没有。统一在 `createEntity` 层做防护。

---

### VULN-5: `pickCandidate` 在事务内 O(n×m×k) 扫描 [中危: 5/10]

**位置**：`EntityGraphRepository.kt` — `pickCandidate()`

```kotlin
private suspend fun pickCandidate(...): CandidateMatch? {
    // ...
    return db.getEntityGraphDao().findEntitiesByType(type.name, ENTITY_SCAN_LIMIT) // 120 条
        .map { it.toModel() }
        .map { entity ->
            val confidence = bindingMatcher.tryBindEntities(probe, entity)  // Levenshtein
            CandidateMatch(entity, confidence, bindingMatcher.classify(confidence))
        }
        .filter { it.strength != EntityBindingStrength.IGNORE }
        .maxWithOrNull(...)
}
```

**开销分析**：
- `scoreNames` 对每个候选实体执行 `namesA.size × namesB.size` 次 Levenshtein
- 单次 Levenshtein 复杂度 O(len1×len2)，日文/中文作品名长度 10-30 字符
- `scoreContext` 对 CHARACTER 和 PERSON 类型额外执行 2 次 DB 查询
- 总计：最多 120 × (3×3) × 900 = ~972,000 次字符比较 + 最多 240 次 DB 查询

**修复方案**：两阶段匹配

```kotlin
// DefaultEntityBindingMatcher.kt
override suspend fun tryBindEntities(entityA: Entity, entityB: Entity): Float {
    if (entityA.type != entityB.type) return 0f
    
    // Phase 1: 快速名称精确匹配（hash 预过滤）
    val nameScore = scoreNames(entityA, entityB)
    if (nameScore < WEAK_BIND_THRESHOLD) return 0f  // 提前终止，避免无意义的 context 查询
    
    // Phase 2: 仅在名称匹配通过后才查询 context
    val contextScore = scoreContext(entityA, entityB)
    return (nameScore + contextScore).coerceIn(0f, 1f)
}
```

`scoreNames` 内部优化——优先快速路径：

```kotlin
private fun scoreNames(entityA: Entity, entityB: Entity): Float {
    val namesA = mergeAliases(entityA.primaryName, entityA.aliases)
    val namesB = mergeAliases(entityB.primaryName, entityB.aliases)
    
    // 快速路径 1: exact match
    val aSet = namesA.toSet()
    if (namesB.any { it in aSet }) return 1f
    
    // 快速路径 2: lowercase exact match
    val aLower = namesA.map { it.lowercase() }.toSet()
    if (namesB.any { it.lowercase() in aLower }) return 0.9f
    
    // 快速路径 3: normalized match
    val aNormalized = namesA.map { normalizeName(it) }.toSet()
    if (namesB.any { normalizeName(it) in aNormalized }) return 0.9f
    
    // 慢速路径 4: Levenshtein（仅在上述路径都不匹配时执行）
    var best = 0f
    for (left in namesA) {
        for (right in namesB) {
            val score = scoreLevenshtein(left, right)
            if (score > best) best = score
            if (best >= 0.88f) return best  // 达到上限，提前结束
        }
    }
    return best
}
```

**配套索引**：见 VULN-7。

---

### VULN-6: Levenshtein 匹配假阳性风险 [中危: 6/10]

**位置**：`DefaultEntityBindingMatcher.kt` — `scoreName()`

**问题**：
```kotlin
private fun normalizeName(value: String): String {
    return value.lowercase()
        .replace(Regex("\\s+"), "")
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fff\\u3040-\\u30ff\\u31f0-\\u31ff\\uff66-\\uff9d]"), "")
}
```

归一化去掉了所有标点和特殊字符。短名称发生碰撞的风险较高。

**修复方案**：

```kotlin
private const val MIN_LENGTH_FOR_FUZZY = 5

private fun scoreName(left: String, right: String): Float {
    // 精确匹配
    if (left == right) return 1f
    if (left.equals(right, ignoreCase = true)) return 0.9f
    
    val normalizedLeft = normalizeName(left)
    val normalizedRight = normalizeName(right)
    
    if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) return 0f
    if (normalizedLeft == normalizedRight) return 0.9f
    
    // 新增：短名称保护
    val minNormLength = minOf(normalizedLeft.length, normalizedRight.length)
    if (minNormLength < MIN_LENGTH_FOR_FUZZY) {
        // 短名称（< 5 个规范化字符）不允许模糊匹配
        // 必须精确匹配才会走上面的分支
        return 0f
    }
    
    // 原有 Levenshtein 逻辑
    val maxLength = maxOf(normalizedLeft.length, normalizedRight.length).coerceAtLeast(1)
    val similarity = 1f - normalizedLeft.levenshteinDistance(normalizedRight).toFloat() / maxLength.toFloat()
    if (similarity < 0.72f) return 0f
    return (0.7f + ((similarity - 0.72f) / 0.28f) * 0.18f).coerceIn(0.7f, 0.88f)
}
```

**阈值调优建议**：
- `AUTO_BIND_THRESHOLD`: 从 0.85 提高到 0.90（减少自动合并误判）
- `WEAK_BIND_THRESHOLD`: 从 0.60 提高到 0.65（减少弱绑定噪声）

```kotlin
private const val AUTO_BIND_THRESHOLD = 0.90f   // was 0.85
private const val WEAK_BIND_THRESHOLD = 0.65f   // was 0.60
```

**验证数据**：在 production DB 上运行 `dumpEntities()` 导出全量数据，用新旧阈值分别计算 AUTOBIND/WEAK/IGNORE 分布，确保改动不破坏已有合理绑定。

---

### VULN-7: 缺少 `(type, access_count, last_accessed)` 复合索引 [低危: 4/10]

**位置**：`EntityGraphDao.kt` — `findEntitiesByType()` 和 `pickCandidate()`

**问题**：

```sql
-- findEntitiesByType 的 WHERE/ORDER BY 缺少匹配的复合索引
SELECT * FROM entity
WHERE type = ?
ORDER BY access_count DESC, last_accessed DESC, id DESC
LIMIT ?
```

当前仅有 `idx_entity_name (primary_name)`，此查询会全表扫描 + filesort。

**修复方案**：

```kotlin
// EntityGraphEntities.kt — EntityRecord 注解修改
@Entity(
    tableName = TABLE_ENTITY_GRAPH_ENTITY,
    indices = [
        Index(name = "idx_entity_name", value = ["primary_name"]),
        Index(name = "idx_entity_type_access", value = ["type", "access_count", "last_accessed", "id"]),  // 新增
        Index(name = "idx_entity_name_hash", value = ["type", "name_hash"], unique = true),  // VULN-3 新增
    ],
)
data class EntityRecord(
    // ...
)
```

**对应的 migration**：

```sql
CREATE INDEX idx_entity_type_access 
ON entity (type, access_count, last_accessed, id);
```

由于是新列+新索引，只需在 migration 中执行 CREATE INDEX，代价极低。

**Query plan 验证**：部署后用 `EXPLAIN QUERY PLAN` 确认索引被命中：

```sql
EXPLAIN QUERY PLAN 
SELECT * FROM entity 
WHERE type = 'WORK' 
ORDER BY access_count DESC, last_accessed DESC, id DESC 
LIMIT 120;
-- 期望：SEARCH TABLE entity USING INDEX idx_entity_type_access
```

---

## 实施计划

### Phase 1: P0 修复（目标：消除数据损坏风险）

| 步骤 | 内容 | 文件 |
|------|------|------|
| 1a | `EntityBindingRecord` 添加 `@ForeignKey` | `EntityGraphEntities.kt` |
| 1b | `RelationRecord` 添加 `@ForeignKey` | `EntityGraphEntities.kt` |
| 1c | 新建 Migration 添加 FK 约束 | `migrations/MigrationXXToXX.kt` |
| 1d | `mergeEntities` 添加 binding 覆盖保护 | `EntityGraphRepository.kt` |
| 1e | `mergeLocalWorkEntities` 添加 binding 覆盖保护 | `EntityGraphRepository.kt` |
| 1f | 单元测试：FK 约束生效、merge 不覆盖高置信度 binding | `EntityGraphRepositoryTest.kt` |

### Phase 2: P1 修复（目标：消除竞态 + 重复实体）

| 步骤 | 内容 | 文件 |
|------|------|------|
| 2a | `EntityRecord` 添加 `name_hash` 列 + UNIQUE 索引 | `EntityGraphEntities.kt` |
| 2b | 新建 Migration 添加列和索引，回填已有数据 | `migrations/MigrationXXToXX.kt` |
| 2c | `createEntity` 改为 `INSERT OR IGNORE` + 冲突重查 | `EntityGraphRepository.kt` |
| 2d | `EntityGraphDao` 添加 `insertEntityIgnore` 和 `findEntityByTypeAndNameHash` | `EntityGraphDao.kt` |
| 2e | 单元测试：并发创建同名实体不产生重复 | `EntityGraphRepositoryTest.kt` |

### Phase 3: P2 修复（目标：性能 + 匹配质量）

| 步骤 | 内容 | 文件 |
|------|------|------|
| 3a | 添加 `idx_entity_type_access` 复合索引 | `EntityGraphEntities.kt` + migration |
| 3b | `scoreNames` 分层快速路径优化 | `DefaultEntityBindingMatcher.kt` |
| 3c | 短名称保护（`MIN_LENGTH_FOR_FUZZY=5`） | `DefaultEntityBindingMatcher.kt` |
| 3d | AUTO_BIND_THRESHOLD 调整为 0.90 | `DefaultEntityBindingMatcher.kt` |
| 3e | `pickCandidate` 增加 name_hash 预过滤 | `EntityGraphRepository.kt` |
| 3f | 基准测试：ingestion 耗时对比 | `EntityGraphBenchmarkTest.kt` |

---

## 测试策略

### 单元测试

```kotlin
class EntityGraphRepositoryTest {
    
    @Test
    fun `mergeEntities preserves higher confidence binding`() = runTest {
        // Given: target 已有 binding(bangumi, 42, confidence=1.0)
        // And: source 有 binding(bangumi, 42, confidence=0.5)
        // When: mergeEntities(target, source)
        // Then: target 的 binding(bangumi, 42) confidence 仍为 1.0
    }
    
    @Test
    fun `concurrent entity creation deduplicates by name hash`() = runTest {
        // Given: 两个协程同时创建同名 WORK 实体
        // When: 两者都执行 resolveOrCreateEntity
        // Then: 只创建了一个实体，第二个请求返回已有实体
    }
    
    @Test
    fun `foreign key cascade deletes bindings and relations`() = runTest {
        // Given: entity A 有 2 个 bindings 和 1 个 relation
        // When: 删除 entity A
        // Then: bindings 和 relations 都被级联删除
    }
}

class DefaultEntityBindingMatcherTest {
    
    @Test
    fun `short names below 5 chars require exact match`() = runTest {
        val left = entity(name = "A")
        val right = entity(name = "B")
        val confidence = matcher.tryBindEntities(left, right)
        assertEquals(0f, confidence)
    }
    
    @Test
    fun `normalized match is case and whitespace insensitive`() = runTest {
        val left = entity(name = "Sword Art Online")
        val right = entity(name = "swordartonline")
        val confidence = matcher.tryBindEntities(left, right)
        assertEquals(0.9f, confidence)
    }
}
```

### 集成测试 / 属性测试

```kotlin
class EntityGraphPropertyTest {
    
    @PropertyTest
    fun `no duplicate entities for same name across all supported scripts`() {
        // 用 property-based testing 生成随机名称组合
        // 验证 dedup 在各种 Unicode 块下正确工作
    }
}
```

### 性能基准

```kotlin
class EntityGraphBenchmarkTest {
    
    @Test
    fun `ingestion latency under 50 entities`() {
        // 预热：插入 50 个 WORK 实体 + 随机 aliases
        // 测量：ingestWorkFromTracking 的 p50/p95/p99 耗时
        // 预期：p95 < 100ms（移动设备 SQLite）
    }
}
```

---

## 回滚安全

所有修改均为**增量式**：

- **FK 约束**：仅影响将来的删除操作，不影响现有读取路径。如果迁移失败，回退 migration 版本即可
- **name_hash 列**：新增列，不影响已有代码的读写。hash 回填在 migration 中一次性完成
- **阈值调整**：可在运行时通过 `AppSettings` 控制，允许 A/B 测试
- **merge 保护**：`mergeEntities` 的修改不改变 API 签名——仅内部逻辑更保守

---

## 未覆盖的风险（后续追踪）

| 风险 | 当前状态 | 计划 |
|------|---------|------|
| JSON 别名解析失败静默丢弃 | `decodeStringList` 使用 `getOrElse` | 长期考虑引入结构化别名表 |
| binding source key 格式不一致 | `bindingSourceKeys()` 做了兼容，但依赖代码约定 | 定义 source key 枚举 |
| `EntityGraphMigrationWorker` 重复运行 | 无幂等保护 | 添加 migration 标记位 |
| 无关系类型校验 | RelationType 直接 store as String | 保持现状，Room 实体层不感知 enum |
| `EntityGraphMigrationWorker` 未更新 name_hash 回填 | 现有实体 name_hash 使用 row-id 占位，未回填真正 normalized hash | 已在 Phase 4-2b 中通过 worker 更新实现 |
| 多份重复的 normalizeName 实现 | `MergeFavoriteEntitiesUseCase`, `BindTrackingToEntitiesUseCase` 各有独立实现 | 已在 Phase 4-4 中统一 |

---

## 第二轮排查：跨模块集成漏洞（2026-06-07 第二轮）

分析范围：entitygraph 模块被 33 个外部模块引用。本轮深入审查了 6 个关键集成点。

### VULN-8: Backup 恢复创建全零 name_hash，违反 UNIQUE 约束 [严重: 10/10]

**位置**：`backups/data/BackupRepository.kt` — `restoreEntityRecord()`

**问题代码**：
```kotlin
val localId = if (existing == null) {
    dao.insertEntity(
        remote.copy(
            id = 0L,
            aliases = encodeStringList(...),
            // ❌ name_hash 使用默认值 0，导致备份恢复失败
        ),
    )
}
```

**根因**：`backups` 模块直接使用 `EntityGraphDao.insertEntity` 绕过 `EntityGraphRepository`，不经过 `createEntity` 逻辑。旧备份 JSON 反序列化后 `name_hash` 字段为 `0L`（data class 默认值），第二个同 type 实体恢复时触发 `UNIQUE (type, name_hash)` 约束冲突。

**修复方案**：

```kotlin
// 方案：restoreEntityRecord 中计算 name_hash
private suspend fun MangaDatabase.restoreEntityRecord(
    remote: EntityRecord,
    entityIdMapping: MutableMap<Long, Long>,
) {
    val dao = getEntityGraphDao()
    val trimmedName = remote.primaryName.trim()
    val computedHash = computeNameHash(trimmedName)
    val existing = dao.findEntity(remote.id)
        ?.takeIf { it.type == remote.type }
        ?: dao.findEntityByTypeAndPrimaryName(remote.type, trimmedName)
    val localId = if (existing == null) {
        dao.insertEntityIgnore(
            EntityRecord(
                type = remote.type,
                primaryName = trimmedName,
                nameHash = computedHash,  // ✅ 使用 normalized hash
                aliases = encodeStringList(mergeAliases(trimmedName, decodeStringList(remote.aliases)).drop(1)),
                createdAt = remote.createdAt.coerceAtLeast(0L),
                lastAccessed = remote.lastAccessed.coerceAtLeast(0L),
                accessCount = remote.accessCount.coerceAtLeast(1),
            ),
        ).takeIf { it != -1L } ?: run {
            // Conflict — another entity already has this name hash. Try to merge.
            dao.findEntityByTypeAndNameHash(remote.type, computedHash)?.id
                ?: dao.insertEntity(  // Fallback: force insert if hash collision
                    EntityRecord(
                        type = remote.type,
                        primaryName = trimmedName,
                        nameHash = remote.id,  // Use remote ID as hash to guarantee uniqueness
                        aliases = encodeStringList(mergeAliases(trimmedName, decodeStringList(remote.aliases)).drop(1)),
                        createdAt = remote.createdAt.coerceAtLeast(0L),
                        lastAccessed = remote.lastAccessed.coerceAtLeast(0L),
                        accessCount = remote.accessCount.coerceAtLeast(1),
                    ),
                )
        }
    } else {
        val mergedNames = mergeAliases(
            existing.primaryName,
            decodeStringList(existing.aliases) + listOf(trimmedName) + decodeStringList(remote.aliases),
        )
        val newPrimary = mergedNames.firstOrNull() ?: existing.primaryName
        val merged = existing.copy(
            primaryName = newPrimary,
            nameHash = computeNameHash(newPrimary),
            aliases = encodeStringList(mergedNames.drop(1)),
            createdAt = minOf(existing.createdAt, remote.createdAt.coerceAtLeast(0L)),
            lastAccessed = maxOf(existing.lastAccessed, remote.lastAccessed.coerceAtLeast(0L)),
            accessCount = maxOf(existing.accessCount, remote.accessCount.coerceAtLeast(1)),
        )
        dao.upsertEntityRecord(merged)
        existing.id
    }
    entityIdMapping[remote.id] = localId
}
```

### VULN-9: `AttachReadingSourceToEntityUseCase` 绕过 Repository 创建实体 [严重: 9/10]

**位置**：`favourites/domain/AttachReadingSourceToEntityUseCase.kt` — `resolveOrCreateEntityId()`

**问题代码**：
```kotlin
private suspend fun resolveOrCreateEntityId(content: Content): Long {
    findLocalBinding(content.id)?.let { return it.entityId }
    val now = System.currentTimeMillis()
    val entityId = database.getEntityGraphDao().insertEntity(
        EntityRecord(
            type = EntityType.WORK.name,
            primaryName = content.title.trim(),
            aliases = null,       // ❌ 不合并别名
            // ❌ name_hash=0，第二个调用冲突
            ...
        ),
    )
    ...
}
```

**修复方案**：完全替换为 `EntityGraphRepository.ensureLocalWorkEntity()`，不再直接操作 DAO：

```kotlin
private suspend fun resolveOrCreateEntityId(content: Content): Long {
    return entityGraphRepository.ensureLocalWorkEntity(content).id
}
```

### VULN-10: 多份重复的 `normalizeName`/Levenshtein 实现 [中危: 5/10]

| 文件 | 函数 | 阈值 |
|------|------|------|
| `EntityGraphMapping.kt` | `normalizeName()` | —（共享实现） |
| `MergeFavoriteEntitiesUseCase` | `normalizeTitle()` | `FUZZY_MATCH_THRESHOLD=0.82` |
| `BindTrackingToEntitiesUseCase` | `normalizeTitle()` | `EXACT_MATCH_THRESHOLD=0.995` |

**修复方案**：两个 UseCase 改用 `entitygraph.data.normalizeName`（同包 internal 可访问），删除各自的 `normalizeTitle`、`levenshtein`、`similarity` 私有方法。

### VULN-11: Backup 恢复静默丢弃无映射的 binding/relation [中危: 6/10]

**位置**：`backups/data/BackupRepository.kt`

```kotlin
private suspend fun MangaDatabase.restoreEntityBinding(
    remote: EntityBindingRecord,
    entityIdMapping: Map<Long, Long>,
) {
    val localEntityId = entityIdMapping[remote.entityId] ?: return  // ❌ 静默丢弃
    ...
}
```

**修复方案**：添加计数器跟踪跳过的 binding/relation，记录日志。

### VULN-12: `MigrateUseCase` 直接操作 DAO [中危: 5/10]

**位置**：`alternatives/domain/MigrateUseCase.kt`

直接使用 `entityGraphDao.upsertBinding()` 维护 source migration 的 binding 关联。虽已有前置 lookup 保护，但仍绕过了 Repository 的 `upsertBindingForSource` 逻辑。

**修复方案**：改为通过 `entityGraphRepository` 间接操作（或确认无风险后保持现状并添加注释说明）。

---

## Phase 4: 跨模块集成修复

### 步骤

| 步骤 | 内容 | 文件 | 数据库变更 |
|------|------|------|-----------|
| 4a | Backup `restoreEntityRecord` 计算 name_hash + `insertEntityIgnore` | `BackupRepository.kt` | 无 |
| 4b | `EntityGraphMigrationWorker` 添加 name_hash 回填步骤 | `EntityGraphMigrationWorker.kt` | 无（运行时回填）|
| 4c | `AttachReadingSourceToEntityUseCase` 改用 `EntityGraphRepository` | `AttachReadingSourceToEntityUseCase.kt` | 无 |
| 4d | 统一 normalizeName 引用，删除重复实现 | `MergeFavoriteEntitiesUseCase.kt`, `BindTrackingToEntitiesUseCase.kt` | 无 |
| 4e | Backup 恢复添加丢失 binding/relation 日志 | `BackupRepository.kt` | 无 |
| 4f | `MigrateUseCase` 审查和修正 | `MigrateUseCase.kt` | 无 |

### 数据库版本

Phase 4 不引入新的 migration（无需新增列/索引），所有修改均为应用层逻辑修正。当前 DB 版本保持在 52。

---

## 第五轮：EntityWorkbench UI 架构重构（统一表格方案）

### 设计评审结论

实体整理功能的核心设计——**统一工作台表格 + 三阶段操作维度**——在概念上是合理的：

- 每行是一个实体组，MERGE/TRACKING/READING 是同一行的三组列操作
- 跨阶段筛选器（`ACTION_REQUIRED`）和排序（`ACTION_FIRST`）需要统一表格上下文
- `EntityWorkbenchRow` 和 `WorkbenchRowStageSnapshot` 数据模型正确表达了这种同表多维度语义

问题不在设计概念，而在**实现分层**：

| 反模式 | 具体表现 |
|--------|---------|
| ViewModel 混合表格元数据和 UI-local 操作 | `moveTrackingServiceUp/Down`、`toggleXxxContentType/Tag`、`setConcurrency` 等纯 UI 状态操作不应该在 VM 层 |
| Compose 文件 4427 行单文件 | 60+ 个 `@Composable` 函数堆在一个文件，表格行渲染和阶段面板和对话框全混在一起 |
| 工作台状态关闭即丢失 | 表格选中状态只在 `rememberSaveable` 中，Activity 重建后丢失 |

### 重构目标

1. **保留统一表格语义** — 不拆 stage，不拆 ViewModel（表格状态需要集中管理）
2. **拆分 Compose 文件** — 按组件类型分文件，不改变任何行为
3. **分离 UI-local 状态** — 纯 UI 操作（上下移动列表项、切换筛选器）从 VM 移出

### Compose 文件拆分方案

```
favourites/ui/migration/compose/
├── SourceMigrationPanel.kt          ← 面板主入口 + HeaderSection + 顶层 LazyColumn 组装
├── EntityWorkbenchTable.kt          ← EntityWorkbenchSection + 表头 + 行渲染 + 工具栏
├── EntityWorkbenchCells.kt          ← MergeCandidateSection / TrackingPreviewCard /
│                                       ReadingPreviewCard / ProjectionSummaryCard
├── EntityWorkbenchDialogs.kt        ← TrackingServiceSelectorDialog /
│                                       SourceSelectorDialog / SourceSearchDialog
├── StageConfigCard.kt               ← StageConfigCard + TrackingBindingSection +
│                                       TargetSourcesSection + SourceFilterSection
├── DatasetBridgeCard.kt             ← DatasetBridgeCard + DatasetMetaChip
├── SharedComponents.kt              ← CompactInfoChip, ButtonLabel, SearchPillTextField,
│                                       FilterDropdown, ConcurrencyDropdown, etc.
└── EntityWorkbenchModels.kt         ← data classes (EntityWorkbenchRow, WorkbenchStageSnapshot,
                                        WorkbenchColumnWidths, etc.) + 工具函数
```

拆分后每个文件预计 200-500 行，可读性大幅提升。

### ViewModel 瘦身方案

将以下纯 UI-local 操作从 `SourceMigrationViewModel` 移到 Compose 层 `remember` + `mutableStateListOf`：

```kotlin
// 从 ViewModel 移除，在 Compose 用 remember { mutableStateListOf<ScrobblerService>() } 替代
fun moveTrackingServiceUp(service)
fun moveTrackingServiceDown(service)

// 从 ViewModel 移除，在 Compose 用 remember { mutableStateListOf<ContentSource>() } 替代
fun moveTargetSourceUp(sourceKey)
fun moveTargetSourceDown(sourceKey)
fun toggleTargetSource(source)
fun removeTargetSource(sourceKey)

// 从 ViewModel 移除，在 Compose 用 remember { mutableStateOf() } 替代
fun toggleFromContentType(tab)
fun toggleFromSourceTag(tag)
fun toggleToContentType(tab)
fun toggleToSourceTag(tag)
fun setConcurrency(value)
```

这些操作都是瞬时的 UI 排列变化，不涉及数据层，不需要在 ViewModel 中管理。

### 实施步骤

| 步骤 | 内容 | 文件变更 |
|------|------|---------|
| 6a | 提取 `SharedComponents.kt`（CompactInfoChip、ButtonLabel、SearchPillTextField、FilterDropdown、ConcurrencyDropdown） | 新建 1 文件，SourceMigrationPanel.kt 删除对应代码 |
| 6b | 提取 `EntityWorkbenchModels.kt`（data class + 工具函数） | 新建 1 文件 |
| 6c | 提取 `EntityWorkbenchCells.kt`（MergeCandidateSection、TrackingPreviewCard 等） | 新建 1 文件 |
| 6d | 提取 `EntityWorkbenchDialogs.kt`（所有 Dialog composable） | 新建 1 文件 |
| 6e | 提取 `EntityWorkbenchTable.kt`（EntityWorkbenchSection + 表头 + 行 + 工具栏） | 新建 1 文件 |
| 6f | 提取 `StageConfigCard.kt` | 新建 1 文件 |
| 6g | 提取 `DatasetBridgeCard.kt` | 新建 1 文件 |
| 6h | 将 UI-local 状态从 VM 移到 Compose 层 | SourceMigrationViewModel.kt 删方法，Compose 加 remember |

### 数据库版本

不引入 migration。当前 DB 版本保持在 52。
