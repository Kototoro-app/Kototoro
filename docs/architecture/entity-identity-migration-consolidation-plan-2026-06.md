# 实体身份迁移收敛计划（2026-06）

## 目的

本文档把 2026-06 之前的实体图谱、Work 化、同步隔离和治理补救文档重新收敛为一份可执行计划。

当前结论不是“放弃实体中心”，而是：

> 保留实体为产品中心，但把实体系统收敛为稳定的作品身份层；收藏、历史、统计、追踪等用户状态统一挂到作品身份；来源条目只作为可读、可播放、可更新的投影。

核心不变量：

```text
entity_id = 本地作品级用户状态键，只在当前数据库内有效
sync_id = 作品身份跨设备稳定键，用于 backup / sync / restore 映射本地 entity_id
manga_id = 本地来源投影 / 执行锚点，只在当前数据库内有效
entity_binding = 来源投影与作品身份之间的唯一证据
preferred_local_manga_id = 当前默认展示/阅读投影，不改变作品身份
```

只要这个不变量不成立，收藏聚合、详情页、阅读记录、追踪、备份、同步都会继续出现局部修补和状态回流。

最终状态：

> 每一个作品在系统中只有一个身份真相；所有用户状态都挂在这个身份上；来源只是可替换的阅读、播放、更新窗口。

## 硬性工程约束

以下约束不是建议，而是迁移期间的 review gate。任何新代码、迁移脚本、repair 动作、restore 逻辑都必须满足。

### 1. `manga_id` 不得作为作品 owner

禁止新代码直接把 `manga_id` 当作收藏、历史、统计、追踪、阅读记录的主 owner。

允许场景：

- 打开来源详情。
- 加载章节、阅读、播放、下载。
- 作为 `anchor_manga_id` 记录当前执行投影。
- legacy import 中作为待迁移输入。

必须通过 `WorkResolver` 得到 `entity_id` 后，才能写作品级用户状态。

### 2. 远端 `entity_id` 不得作为本地主键

任何 backup / sync / WebDAV restore 中出现的 `entity_id` 都只能视为远端快照内临时 ID。

禁止：

- 直接把远端 `entity_id` 写入本地 work 状态。
- 直接按远端 `entity_id` 关联本地 binding / prefs / favourites / history / stats。

必须：

- 建立 `remoteEntityId -> localEntityId` 映射。
- 优先通过 `sync_id` 找到本地 entity；没有 `sync_id` 时再通过 local projection anchor、strong binding、manual binding 找到本地 entity。
- 无法可靠映射时创建本地 entity 或 candidate，不得污染已有本地主实体。

### 2a. `sync_id` 是 Work 跨设备身份，不是本地主键

`sync_id` 只属于 `EntityRecord` / Work identity，用于跨设备和备份恢复时识别同一个作品身份。

禁止：

- 把远端 `sync_id` 当成本地 `entity_id` 或 `manga_id` 写入。
- 把 `sync_id` 写到 projection / manga 表作为执行锚点。
- 因 preferred projection 变化而重写一个已存在 Work 的 `sync_id`。
- 用不可信裸 `Content.id` / `manga_id` 生成 projection-derived `sync_id`。

必须：

- restore / sync 映射优先使用 `(type, sync_id)` 找本地 entity。
- `sync_id` 缺失时，才允许退回到强 binding / anchor projection / legacy name-hash 映射。
- 单 projection Work 可以使用 source-scoped projection key 生成确定性 `sync_id`。
- 多 projection 或用户合并后的 Work 必须保留 survivor entity 的 `sync_id`；选择默认来源只改 `preferred_local_manga_id`。
- `sync_id` 只能解决“哪个本地 entity 对应远端 Work”，不能替代 `entity_binding(source, external_id)` 对 projection 归属的证据职责。

### 3. 自动流程不得确认候选身份

`CANDIDATE` 只能进入整理建议和 repair UI，不能被自动流程提升为 `CONFIRMED`。

允许自动确认的证据仅限：

- 用户明确合并或绑定。
- 已存在且未被拒绝的 manual / confirmed binding。
- 同一 source-scoped provider key 的可靠恢复映射。
- legacy normalization 中可证明一一对应的本地投影绑定。

禁止：

- 仅凭标题相似度自动合并。
- 仅凭 tracking cache / `tracking_site_links` 自动确认。
- 仅凭同名、同别名、同封面自动确认。
- restore 时把 `LEGACY` / `SYNC` binding 自动提升为 `CONFIRMED`。

### 4. `ensureForProjection` 不得成为隐式 owner 制造器

`ensureForProjection(content)` 是迁移防腐入口，不是“看到一个 `manga_id` 就创建作品主实体”的快捷方式。

允许调用场景：

- 用户明确打开或收藏一个 projection，需要让它进入当前 Work 体系。
- legacy import / restore 正在把旧投影状态迁移为新模型。
- 读取链遇到旧状态时，需要触发一次性 migration review，而不是长期兼容读。

禁止：

- 后台扫描全库时为所有 projection 批量创建 authoritative work。
- 更新/订阅轮询时为了方便写状态而创建 work。
- 因为某个 projection 标题相似就把它绑定到已有 work。

要求：

- 自动创建的 work 必须带 provenance，例如 `createdBy = MIGRATION / IMPORT / USER`。
- 由旧状态迁移创建的 work 初始只能绑定当前 projection，不得自动吸附其它 projection。
- 只有用户合并、强 provider key、或明确 import 映射，才能把多个 projection 合成同一 work。

### 5. 聚合读必须有确定的冲突合并策略

Work-first 不是把多个 projection 状态简单 `GROUP BY entity_id`。所有旧 projection 状态迁移进入 Work 模型时，必须有确定、可测试的 merge policy。

唯一规则表定义在 Phase 1 的“迁移合并策略”。没有 merge policy 的字段不得进入 `WorkAggregate` 主展示。

## 旧计划评价

### 合理的部分

现有文档里有几条判断是正确的，应该保留：

- [entity-centered-work-migration-execution-plan-2026-06.md](./entity-centered-work-migration-execution-plan-2026-06.md) 已经识别到 `Entity = 过渡期 Work`、`Manga = Projection`。
- [work-migration-sync-isolation-plan-2026-06.md](./work-migration-sync-isolation-plan-2026-06.md) 已经识别到迁移不是普通字段兼容，而是身份模型升级。
- [work-sync-schema-and-restore-isolation-spec-2026-06.md](./work-sync-schema-and-restore-isolation-spec-2026-06.md) 已经把传输代际、语义版本和 restore import 隔离拆开。
- [entity-graph-governance-remediation-plan-2026-06.md](./entity-graph-governance-remediation-plan-2026-06.md) 已经指出 metadata mirror、repair 噪音、tracking cache 回流是主要污染源。
- [entity-graph-hardening-plan.md](./entity-graph-hardening-plan.md) 对 FK、唯一约束、merge 覆盖、并发创建的诊断是必要的底层加固。

这些方案的问题不在方向，而在执行抓手不够单一。

### 不足的部分

旧计划存在三个系统性不足：

1. **口号正确，但运行时入口分散。**
   多份文档都说 Work-first / Entity-first，但 `FavouritesRepository`、history、tracker、backup、details 各自仍在解析 `mangaId -> entityId -> preferred manga`，导致规则重复且容易分叉。

2. **把实体图谱和作品身份混在一起。**
   `WORK / CHARACTER / PERSON / ORGANIZATION`、`relation`、tracking ingest、收藏聚合、阅读锚点都被放进同一个 `entitygraph` 概念里。产品上需要的是“作品身份中心”，而不是让所有页面都直接依赖一个广义知识图谱。

3. **迁移计划偏“继续修补”，缺少停止条件。**
   旧文档列了大量 Phase 和 PR，但没有强制规定“页面、同步、备份、整理工具只能通过同一个 Work 解析门面读写”。结果每修一个入口，就可能在另一个入口重新制造脏数据。

## 为什么现在表现得脏乱

当前脏乱不是单点 bug，而是身份模型没有唯一运行时真相。

### 1. 双主状态长期并存

当前同时存在：

- `favourites(manga_id, category_id)`
- `work_favourites(entity_id, category_id)`
- `history(manga_id)`
- `work_history(entity_id, anchor_manga_id)`
- `stats(manga_id)`
- `work_stats(entity_id, anchor_manga_id)`
- track / scrobbling 中的 `manga_id` 与 `entity_id`

这些表在迁移期间可以并存，但不能长期都参与主决策。否则每个页面都要猜：

```text
这次应该读 manga 状态，还是读 entity 状态，还是合并两者？
```

### 2. 解析逻辑散落在多个仓库

典型重复逻辑包括：

- 根据 local manga binding 找 `entity_id`
- 根据 `entity_preferences.preferred_local_manga_id` 选择展示内容
- preferred projection 失效后重新选择合法 active local binding
- 旧 manga 状态迁移为 work 状态
- restore / sync 时把远端 `entity_id` 映射成本地 `entity_id`

这些逻辑散落在 favourites、history、reading record、tracker、sync、backup、details 中。DRY 被破坏后，任何一处规则变化都会造成行为漂移。

### 3. 自动合并和整理工具承担了太多职责

实体整理现在同时处理：

- 合并重复收藏
- 绑定阅读源
- 绑定 tracking
- 迁移阅读记录
- 选择 metadata source
- 修复 legacy relation
- repair 噪音分类

这让整理工具从“确认边界问题”膨胀成“补救所有模型不清晰造成的后果”。一旦运行时主链继续产生污染，整理工具就永远修不完。

### 4. restore / sync 仍可能把旧语义重新带回主链

旧版本备份和旧同步快照里的 `manga_id` 状态是必须支持迁移的，但不能被直接恢复成当前主真相。

如果 restore 逻辑执行：

```text
legacy favourites/history/tracks -> 直接写当前主状态 -> 自动上传
```

旧语义会跨设备扩散。表现上就是“新版本整理好了，过一段时间又脏了”。

### 5. metadata authority 与 projection override 边界不稳

`entity_preferences` 和 manga prefs 中的 `metadata_source_*` 曾经互相 mirror，导致系统里出现两个 metadata 真相：

- entity 默认 metadata source
- per-manga metadata source

如果 per-manga 字段既可能是显式用户 override，又可能是历史 mirror 残留，repair 就无法判断它到底是用户意图还是污染。

## 外部参考结论

Jellyfin 的经验对本迁移有参考价值，但不能机械照搬。

可借鉴点：

- Jellyfin 文档建议通过带命名空间的 metadata provider id 提升识别准确性，例如 TMDB / TVDB / IMDb id。这对应 Kototoro 的 `entity_binding(source, external_id)`，说明身份证据必须是 source-scoped key，而不是裸标题匹配。
- Jellyfin 曾出现 `UserDataKey` 碰撞导致收藏/观看状态串到错误条目的问题。这说明用户状态键必须稳定、带类型边界，不能让不同来源或不同条目的模糊身份共享同一个状态键。
- 多版本影片问题说明“同一作品的多个版本”应被建模为同一身份下的不同版本/投影，而不是让每个版本都成为独立用户状态 owner。

参考：

- [Jellyfin Metadata Provider Identifiers](https://jellyfin.org/docs/general/server/metadata/identifiers/)
- [Jellyfin UserDataKey collision issue](https://github.com/jellyfin/jellyfin/issues/11840)
- [Jellyfin multiple versions discussion](https://github.com/orgs/jellyfin/discussions/13128)

映射到 Kototoro：

```text
Jellyfin Item / ProviderId       -> Kototoro entity / entity_binding
Jellyfin multiple versions       -> Kototoro multiple local manga projections
Jellyfin UserData                -> Kototoro favourites/history/stats/tracking user state
Jellyfin version selection       -> Kototoro preferred_local_manga_id
```

## 目标模型

### Entity Identity

职责：

- 表示一个作品级身份。
- 持有 primary name、aliases、创建时间、访问统计。
- 持有跨设备稳定身份 `sync_id`。
- 只对 `WORK` 承担用户状态 owner 语义。

非职责：

- 不直接表示具体阅读入口。
- 不直接承载 source-native 原始内容。
- 不要求 `CHARACTER / PERSON / ORGANIZATION` 参与收藏、历史、同步主链。

建议：

- `WORK` 是迁移期主线。
- `CHARACTER / PERSON / ORGANIZATION / relation` 暂时降级为详情页元数据缓存和导航辅助，不参与用户状态 ownership。

### Entity Sync Identity

职责：

- `sync_id` 表示一个 Work 在 backup / sync / restore 语义中的跨设备身份。
- 本地 `entity_id` 可以不同，但同一 `(type, sync_id)` 应映射到同一作品身份。
- `sync_id` 是 restore 时建立 `remoteEntityId -> localEntityId` 映射的首选证据。

规则：

- `sync_id` 必须在本地 entity 表内唯一。
- 新建普通 Work 时可以生成 UUID `sync_id`。
- 如果 Work 只有一个 authoritative projection binding，可以使用 `computeProjectionSyncId(source, externalId)` 这类确定性键，让多设备独立创建的同一单来源 Work 可自动对齐。
- 一旦 Work 拥有多个 projection 或经过用户合并，`sync_id` 不再随 projection / preferred projection 改变。
- 合并 Work 时保留 survivor entity 的 `sync_id`；loser entity 的远端 id 只能通过 import mapping / merge ledger 关联，不得继续作为 active 本地主键。
- `sync_id` 不是 metadata authority，不参与章节、阅读、下载等 source-native 执行动作。

禁止：

- 用标题、封面、tracking cache、裸 `Content.id` 生成 `sync_id`。
- 因自动候选匹配而把两个不同 `sync_id` 的 Work 合并为 confirmed。
- 把 `sync_id` 当作 `entity_binding.external_id` 的替代品；binding 仍必须保存 source-scoped projection/provider key。

### Entity Binding

职责：

- 表示来源条目与实体身份之间的绑定。
- 唯一键是 `(source, external_id)`。
- 保存 `sourceKind`、`state`、`createdBy`、`updatedAt`。

规则：

- `MANUAL` 不可被自动流程覆盖。
- `REJECTED` 阻止同 key 自动回流。
- `CANDIDATE` 只用于整理建议，不参与主状态。
- `LEGACY` 可读，但不能自动提升为 `CONFIRMED`。
- 标题相似度只能生成候选，不能直接成为强真相。
- authoritative projection binding 的 `external_id` 必须是 source-scoped provider / projection key；如果源返回的裸 id 不可靠，应使用规范化 URL / publicUrl。
- 单 projection Work 的 projection-derived `sync_id` 应从同一个 source-scoped key 计算，避免不同设备对同一来源条目生成不同 Work 身份。

### Projection

职责：

- `manga` 表示一个来源中的可执行投影。
- 负责打开详情、加载章节、阅读、更新、下载、播放。

规则：

- `manga_id` 可以作为 execution anchor。
- `manga_id` 不再默认等价于作品 owner。
- 同一 entity 下可以有多个 local projection。
- `manga_id` 是本地数据库锚点，backup / sync / restore 时必须通过 projection key 重新映射，不能跨设备直接复用。
- Projection key 至少包含 `source.name + normalized url/publicUrl`；源返回的 `Content.id` 只能作为候选输入，不能在已知可能重复时作为唯一跨设备 key。

### Work User State

职责：

- 收藏、分类、历史、统计、追踪状态以 `entity_id` 为主键。
- 需要执行来源动作时携带 `anchor_manga_id`。

规则：

- 新写路径必须写 work/entity 状态。
- 旧 manga 状态只作为迁移输入；成功迁移后不得继续参与主读写。
- 主页面不直接合并多套状态，统一通过 Work 解析门面读取。

### WorkResolver

新增或明确一个唯一门面，负责所有身份解析：

```kotlin
interface WorkResolver {
    suspend fun resolveByMangaId(mangaId: Long): WorkIdentity
    suspend fun resolveByEntityId(entityId: Long): WorkIdentity?
    suspend fun resolveManyByMangaIds(mangaIds: Collection<Long>): Map<Long, WorkIdentity>
    suspend fun ensureForProjection(content: Content): WorkIdentity
    suspend fun selectPreferredProjection(entityId: Long): Long?
}
```

`WorkIdentity` 至少包含：

```kotlin
data class WorkIdentity(
    val entityId: Long?,
    val requestedMangaId: Long?,
    val preferredMangaId: Long?,
    val localMangaIds: Set<Long>,
    val migrationState: WorkMigrationState,
)
```

```kotlin
enum class WorkMigrationState {
    VALID,
    NEEDS_REVIEW,
}
```

约束：

- 页面、repository、backup、sync、repair 不再各自手写旧状态迁移或 identity fallback。
- 所有 `mangaId -> entityId -> preferred projection` 规则集中在这里。
- 这里是迁移期间最重要的防腐层。

#### WorkResolver 方法语义

`WorkResolver` 不是 `EntityGraphRepository` 的替代品，而是它前面的身份解析门面。

关系定义：

- `EntityGraphRepository` 保留底层 graph / binding / relation / repair 能力。
- `WorkResolver` 包装 `EntityGraphRepository` 中与 `WORK` identity 相关的能力。
- 页面和业务 repository 不直接调用 `EntityGraphRepository.findEntityIdsByAnyMangaIds(...)` 等低层 fallback。
- 非 `WORK` 图谱能力仍留在 `EntityGraphRepository`，但不得参与用户状态 ownership。

方法契约：

| 方法 | 是否创建实体 | 返回语义 | 典型调用方 |
| --- | --- | --- | --- |
| `resolveByMangaId(mangaId)` | 否 | 只解析已有绑定；找不到时返回 `entityId = null, migrationState = NEEDS_REVIEW` | 列表、详情、只读查询 |
| `resolveByEntityId(entityId)` | 否 | entity 不存在返回 null；存在但无可用 projection 时 `preferredMangaId = null` | Work 入口、同步映射后校验 |
| `resolveManyByMangaIds(mangaIds)` | 否 | 批量只读解析；不得触发创建或迁移写入 | 列表分页、批量状态查询 |
| `ensureForProjection(content)` | 是 | 只在用户动作、restore/import、显式 migration job 中创建最小 WORK | 收藏、restore、normalization |
| `selectPreferredProjection(entityId)` | 否 | 只选择合法 active local binding；不写入偏好 | UI 展示、reader anchor |

`ensureForProjection(...)` 失败 UX：

- 用户收藏/打开时遇到 rejected evidence 或 provider 冲突，不静默失败。
- UI 应创建/返回 `NEEDS_REVIEW` 结果，并显示“需要确认作品身份”入口。
- 用户可以选择：保持为单独作品、合并到已有作品、重新选择默认来源、取消本次操作。
- restore/import 场景下失败项进入 migration review 列表，不写 work authoritative state。

事务与线程：

- `ensureForProjection(...)` 必须在单个数据库事务内完成 entity、binding、prefs 的最小写入。
- 批量 normalization 应分批事务执行，避免长事务阻塞主库。
- 解析方法使用 IO dispatcher；不得在 Compose composition 中直接调用 suspend 查询。
- `resolveManyByMangaIds(...)` 必须限制 IN 参数批次，复用当前 `MAX_BINDING_QUERY_PARAMS` 级别策略。

调用频率：

- 列表页必须优先用批量解析，不允许逐行调用 `resolveByMangaId(...)`。
- 高频 UI state 通过 repository Flow 暴露，不由 Composable 逐次触发 resolver。
- restore / normalization 可以调用 `ensureForProjection(...)`，但必须记录 provenance。

缓存边界：

- 初版不要求全局缓存，先以批量查询和索引保证性能。
- 如果热路径仍过慢，可以在 `WorkResolver` 内部增加短生命周期 LRU，key 为 `mangaId` / `entityId`。
- 缓存必须监听 `entity_binding`、`entity_preferences`、`manga` invalidation；不得缓存 `NEEDS_REVIEW` 结果超过当前事务或当前列表加载周期。

### Migration Review

`migrationState != VALID` 不能只是 UI 标记，它必须表示旧状态尚未成功转换为新合法状态。

review 规则：

- 可以显示。
- 可以被用户打开。
- 可以被用户整理、合并、拆分、选择默认来源后转为 `VALID`。
- 可以作为 import 输入继续尝试迁移为 work 状态。

禁止：

- 作为 confirmed work 参与跨 projection 聚合。
- 作为 sync v2 authoritative state 导出。
- 作为 tracking / subscription owner。
- 自动吸附其它同名 projection。

退出 review 条件：

- 用户明确确认绑定或合并。
- 恢复流程通过强 source-scoped key 完成本地映射。
- normalization 能证明这是单 projection work，且没有冲突 binding / rejected evidence，此时自动转换为 `VALID`。

rejected evidence 定义：

- `entity_binding.state = REJECTED` 且 key 匹配当前 `(source, external_id)`。
- 同一 `(source, external_id)` 已被另一个 `MANUAL / CONFIRMED` work 占用。
- 用户曾明确拆分该 projection 与目标 work。
- repair 记录中存在未解决的 suspect mismerge，且涉及当前 projection 或 target entity。

不属于 rejected evidence：

- 标题不相似。
- 封面不同。
- tracking cache / `tracking_site_links` 不一致。
- metadata source 不一致。metadata 冲突只进入 metadata review，不阻止纯 identity VALID，除非它同时包含 binding 冲突。

如果不能退出 review，应作为待整理迁移项显示，而不是保留一套旧世界读写路径。

#### Review 状态机

```text
OLD_INPUT
  -> AUTO_MIGRATED -> VALID
  -> NEEDS_REVIEW
```

状态含义：

- `VALID`：可进入 Work 聚合、订阅、备份和同步。
- `NEEDS_REVIEW`：可以展示和手动整理，不参与 authoritative 状态；如果存在 rejected binding、冲突 provider key、疑似误合并等风险，只允许人工处理。

自动转 `VALID` 的最低证据：

- 当前旧状态只对应一个 local projection。
- 该 projection 没有 rejected evidence。
- 没有另一个 confirmed/manual entity binding 指向同一 `(source, external_id)`。
- 没有同源 provider key 冲突。

批量 UX：

- review 列表按原因分组：缺少 binding、provider 冲突、preferred projection 失效、metadata 冲突。
- 支持批量确认“单 projection work”。
- 支持逐项合并、拆分、选择默认来源。
- 只有“单 projection work 且无冲突证据”的项提供一键确认。
- 有拒绝证据、provider 冲突、duplicate confirmed binding、missing projection、metadata conflict 的项只允许逐项处理。

## 数据库迁移策略

### Phase 0：不删旧表，先建立统一迁移与解析门面

不做高风险大迁移：

- 不删除 `favourites`。
- 不删除 `history`。
- 不删除 `stats`。
- 不删除 track/scrobbling 中的 legacy `manga_id`。
- 不重命名 `entity` / `manga` 表。

先落地：

- `WorkResolver`
- `WorkAggregateRepository`
- tombstone / migrated shadow 基础设施
- 明确所有新读写入口优先走 `entity_id`
- 明确旧状态进入主页面前必须自动迁移或进入 review

schema 前置项：

- 已有 `deleted_at / updated_at` 的表继续使用软删除语义。
- 当前 `work_favourites` 已具备 `deleted_at / updated_at`：
  - [WorkFavouriteEntity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/data/WorkFavouriteEntity.kt:35)
  - [WorkFavouritesDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/data/WorkFavouritesDao.kt:50)
- 普通收藏删除应继续走软删除；FK `ON DELETE CASCADE` 只处理 entity/category 被物理删除的级联清理。
- 缺少 tombstone 的 work-owned 状态必须先补齐字段或等价事件表，再进入跨设备 authoritative 合并。
- 旧 owner 状态成功迁移后不得物理删除，优先通过 migrated / shadow 标记防止重复导入。
- 最小 migration ledger 是 Phase 0 必需项，用于幂等、诊断和回滚；shadow 字段可作为主查询过滤优化，但不能替代 ledger。

最小 ledger schema：

```kotlin
data class WorkMigrationLedgerEntity(
    val legacyTable: String,
    val legacyKey: String,
    val legacyChecksum: String?,
    val targetEntityId: Long?,
    val migrationVersion: Int,
    val status: String, // MIGRATED, NEEDS_REVIEW, ROLLED_BACK
    val migratedAt: Long,
)
```

约束：

- `legacyTable + legacyKey + migrationVersion` 唯一。
- `legacyChecksum` 用于判断旧 row 是否在迁移后又被旧版本修改；如果旧表已有 `updated_at` 且不再被旧代码写入，可省略。
- `status = MIGRATED` 的旧 row 不再参与主读。
- `ROLLED_BACK` 用于回滚时撤销对应 work-owned row 后重新开放迁移。

验收：

- 收藏页、详情页、历史页可以通过同一聚合模型读取状态。
- 旧数据不丢失，并且不会以旧 owner 语义继续参与主读写。

### Phase 1：幂等归一化

建立可重复执行的 normalization：

1. 对所有 active legacy favourites/history/stats/tracks，确保存在 local manga binding。
2. 缺失 `WORK` entity 时创建最小 entity。
3. 从 legacy manga 状态补齐 work 状态。
4. 成功补齐 work 状态后，将旧状态标记为 migrated / shadow，不再参与主读写。
5. 不基于模糊标题自动合并不同 entity。
6. 已存在 `MANUAL` / `REJECTED` 绑定时严格尊重。
7. 无法证明身份边界的 projection 进入 migration review，不进入 confirmed work 聚合。

需要记录 normalization version，例如：

```text
work_identity_normalization_version = 1
```

验收：

- 同一设备多次运行结果一致。
- 崩溃中断后可重跑。
- 不会把候选绑定提升成 confirmed。
- 成功迁移的旧状态不会继续被主查询读取。

迁移合并策略：

- 收藏：按 `(entity_id, category_id)` 合并；同一 key 下取最新 `updated_at` 决定 active/delete，`created_at` 取最早，`pinned` 只在 active 行中 OR。
- 历史：按 `entity_id` 合并；取最新 `updated_at` 的进度作为当前位置，保留该进度的 `anchor_manga_id`。
- 统计：按事件 ledger 去重；没有稳定事件 id 时使用 `(entity_id, anchor_manga_id, started_at, duration, pages)`，宁可少合并也不跨源臆测去重。
- 更新：按 `(entity_id, anchor_manga_id, source_chapter_key)` 去重，不做跨源章节同一性推断。
- 追踪：按 `(entity_id, scrobbler, target_id, media_type)` 合并，manual/confirmed 优先于 candidate。

### Phase 2：迁移读，单写新模型

读：

- 通过 `WorkResolver` 检测旧状态并触发自动迁移。
- 迁移成功后只返回新模型状态。
- 迁移失败时返回 `migrationState = NEEDS_REVIEW`，供 UI 提示整理。

写：

- 新行为只写 work/entity 状态。
- 禁止为了兼容继续写 legacy projection owner 状态。
- 如仍需写 projection snapshot，只能写非 authoritative snapshot 字段，且不得参与主读写。

验收：

- 新收藏、新历史、新统计、新追踪默认以 `entity_id` 为 owner。
- `favourites(manga_id)` 只可作为导入/迁移输入，不作为降级 fallback 主状态。

### Phase 3：主页面切换到 WorkAggregate

优先迁移：

1. 收藏页
2. 详情页
3. 继续阅读 / 最近阅读
4. 更新 / tracker feed
5. widget / shortcut

统一读模型：

```kotlin
data class WorkAggregate(
    val identity: WorkIdentity,
    val displayProjection: Content?,
    val projections: List<Content>,
    val categories: Set<FavouriteCategory>,
    val history: WorkHistory?,
    val stats: WorkStats?,
    val tracking: TrackingSummary?,
)
```

#### WorkAggregate 查询策略

列表页不能把所有 projection 全量拉到内存后再聚合。

收藏页推荐两阶段查询：

1. `work_favourites` 驱动分页，先查出当前页的 `entity_id`。
2. 批量查询这些 entity 的：
   - `entity_preferences`
   - active local bindings
   - preferred projection manga rows
   - category memberships
   - pinned / sort metadata
3. 对 review 旧状态使用单独查询追加，不混入 confirmed work 分页。

排序原则：

- 主排序键来自 work-owned 状态，如 `work_favourites.created_at / sort_key / updated_at`。
- projection 字段只能作为展示排序补充，例如标题字母序。
- legacy/review 项排在 confirmed work 之后或单独分组。

筛选原则：

- 分类筛选按 work category membership。
- 来源筛选按 active local bindings / display projection source。
- downloaded/local 筛选可以使用 projection 条件，但返回仍按 `entity_id` 聚合。

性能约束：

- 收藏页、历史页、更新页必须支持分页。
- `WorkAggregateRepository` 不允许对全库 `findAll()` 后在内存里 `groupBy(entityId)` 作为主实现。
- 批量查询要限制 IN 参数数量。
- 需要为高频查询补索引，例如 `work_favourites(deleted_at, category_id, sort_key, updated_at)`、`entity_binding(entity_id, source_kind, state)`。

验收：

- 同一作品多来源收藏在收藏页只出现一个 work 行。
- 行内可以展示来源数量、当前默认来源、切换入口。
- 点击具体来源时仍能进入 source-native 详情或阅读。

### Phase 4：同步/备份语义隔离

必须支持旧输入迁移：

- 旧 backup 中只有 `FavouriteBackup(manga_id)`。
- 旧 sync snapshot 中可能没有 work sections。
- 旧 WebDAV 远端可能只有 legacy semantic schema。

恢复规则：

1. 旧 favourites/history/stats/tracks 先作为 migration input。
2. 先用 `(type, sync_id)` 建立 `remoteEntityId -> localEntityId` 映射；缺失 `sync_id` 时才回退到强 binding / projection anchor / legacy name-hash。
3. 通过 projection key 将远端 `manga_id` / anchor 映射成本地 `manga_id`；远端 `manga_id` 不能直接复用。
4. 对缺失 Work 的旧输入，通过 `WorkResolver.ensureForProjection()` 创建最小本地 Work。
5. 再写 work 状态，owner 使用本地 `entity_id`，执行锚点使用本地 `manga_id`。
6. 远端 `entity_id` 只作为快照内临时 id，不能直接当本地主键。
7. restore 后如果 normalization 未完成，auto upload 必须禁写。

新导出规则：

- 新 schema 导出 work sections。
- 新 schema 必须导出 entity `sync_id`。
- 新 schema 中的 `entity_id` / `manga_id` 只能作为 snapshot 内引用；跨设备合并必须依赖 `sync_id`、binding 和 projection key 重新映射。
- legacy sections 不作为 v2 authoritative payload；如保留，只能作为 projection snapshot 或调试迁移信息。
- semantic schema version 必须独立于 WebDAV transport generation。

验收：

- 新版可以读旧备份。
- 新版会把旧语义自动转换为新合法状态，不能转换的进入 review。
- 多设备恢复后，本地 `entity_id` 映射稳定，且优先由 `sync_id` 驱动。
- 不同设备独立创建的同一单 projection Work 在拥有相同 projection-derived `sync_id` 时会合并到同一 Work。
- 合并后的多 projection Work 切换默认来源不会改变 `sync_id`。

### Phase 5：整理工具降级为边界确认工具

实体整理只保留三个主动作：

1. **合并作品**：多个 projection/entity 确认为同一个 work。
2. **拆分投影**：某个 manga 从当前 work 移出，绑定到新 work 或其他 work。
3. **选择默认来源**：设置 preferred projection。

其它功能降级：

- tracking 绑定：作为 binding evidence 编辑。
- metadata source：作为 work metadata authority 选择。
- relation 清理：作为详情页缓存治理，不进入收藏整理主流程。
- repair 诊断：只呈现真实边界风险，不混合缓存漂移。

验收：

- 整理工具不再承担运行时污染的兜底职责。
- 用户看到的问题与实际身份边界一一对应。

### Phase 6：移除旧状态主决策残留

只有在以下条件满足后，才允许考虑停止读取旧 owner 字段：

- 所有主页面都使用 `WorkAggregate`。
- backup / restore 已稳定支持 semantic schema version。
- normalization 可重复且有测试覆盖。
- 旧版本导入会自动迁移为新合法状态。
- 至少一个稳定版本周期内没有新增 legacy owner 写入。

退役顺序：

1. legacy manga 状态从主读路径退出。
2. legacy projection owner 写入停止。
3. 旧字段仅保留迁移输入。
4. 最后才评估 schema 删除或命名统一。

## 页面数据流

### 收藏页

输入：

```text
work_favourites + entity_binding + entity_preferences + manga projection
```

输出：

```text
List<WorkAggregate>
```

规则：

- 列表行以 `entity_id` 去重。
- 无 entity 的旧 projection 先尝试自动迁移；不能迁移时作为 review 项显示，并提示整理。
- 分类 membership 以 work 状态为准；旧 favourites 只作为 migration input。

UX 要求：

- work 行显示主标题、默认来源、来源数量。
- 多来源 work 提供来源切换入口，切换只更新 preferred projection。
- 分类管理作用于 work；如果用户需要只管理某个来源，应进入 projection/source 详情执行局部动作。
- review 项必须有明确标识和整理入口，不能混在普通 work 行里。
- 批量分类操作只作用于 `VALID` work；review 项需要先迁移或确认。

### 详情页

入口分两类：

- `DetailsOrigin.Work(entityId, preferredProjectionId?)`
- `DetailsOrigin.Projection(mangaId)`

规则：

- Projection 入口先解析到 Work。
- 用户明确点击 projection 时，详情页尊重 requested projection。
- 系统入口使用 preferred projection。
- Work metadata 默认来自 `entity_preferences`。
- Projection metadata 只作为 source-native 内容或显式 override。

### 阅读器 / 播放器

规则：

- 打开内容必须携带 `manga_id`。
- 写阅读历史和统计时必须解析 `entity_id`。
- 如果实体解析失败，写入 review 队列或提示整理，不再写 legacy owner 状态。

### Tracker / Scrobbling

规则：

- tracking binding 绑定到 entity。
- update feed 和 unread counters 以 entity 聚合。
- provider 的 per-manga cache 不参与 identity owner 决策。

### Metadata authority 修复

metadata 修复收敛 [entity-graph-governance-remediation-plan-2026-06.md](./entity-graph-governance-remediation-plan-2026-06.md) 的结论，纳入本计划主线：

- `entity_preferences` 是 Work 默认 metadata authority。
- manga prefs 中的 `metadata_source_*` 只表示用户显式 projection override。
- 自动绑定、合并、restore、repair 不得把 entity metadata source mirror 到所有 projection。
- 旧 manga metadata source 在迁移时分类：
  - 与 entity default 一致且疑似 mirror：标记 migrated / shadow。
  - 用户明确设置或无法判定：保留为 explicit override。
  - 指向不存在 binding 或明显冲突：进入 review / repair。
- 新写入优先写 binding reference：`metadata_binding_source + metadata_binding_external_id`。
- raw `metadata_source_service / remote_id` 仅作旧版本导入输入。

为避免 Identity 迁移同时变成 metadata 大重构，首批只做两件事：

1. 禁止新 mirror。
2. 统一读取规则：entity default 优先，projection override 必须显式。

`MetadataAuthority` 不进入 PR-1 到 PR-10 的 identity 主迁移；它作为独立 follow-up task 跟踪。触发条件：

- PR-10 后仍有 details、bind、merge、restore、repair 直接写 `metadata_source_*`。
- metadata mirror 污染仍在新增。
- projection override 与 entity default 的读取规则仍在多个 repository 中重复。

follow-up 目标：

- 类似 `WorkResolver`，提供 metadata authority 的唯一读写入口。
- `resolveDefault(entityId)` 只读 entity default。
- `resolveForProjection(entityId, mangaId)` 只在显式 projection override 存在时覆盖 entity default。
- `setEntityDefault(...)` 不 mirror 到任何 manga prefs。
- `setProjectionOverride(...)` 必须来自用户显式 projection 覆盖操作。

### 更新 / 订阅

更新和订阅最容易伪装成 Work-first，实际仍由 projection 驱动。必须拆成两层：

```text
订阅意图 = Work-owned
更新观测 = Projection-owned
```

规则：

- 用户是否订阅、是否在书架追更、是否参与通知，属于 `entity_id`。
- 实际轮询哪个源、哪个 URL、哪个章节列表，属于 projection anchor。
- 一个 work 可以有多个可轮询 projection，但默认只轮询 preferred / subscribed anchors，不自动轮询所有 bindings。
- 新章节事件必须带 `entity_id + anchor_manga_id + source chapter key`。
- 列表展示按 `entity_id` 聚合；详情里可以展开显示各 projection 的更新来源。
- 如果不同 projection 报告同一章节，默认不做跨源自动去重；只能按 source-scoped chapter key 去重，跨源合并需要用户或强映射证据。
- projection 轮询失败不能删除 work subscription，只能标记该 projection anchor 的更新状态。

禁止：

- 把“某个 projection 有新章节”直接解释为 work 订阅状态变化。
- 因为 work 被收藏就自动订阅所有 projection。
- 因为某个 projection 被删除就删除 work 订阅。
- 用 `manga_id` 的 unread/new chapter count 作为 work 级唯一计数。

## 备份和同步兼容矩阵

| 输入类型 | 允许读取 | 允许直接主写 | 处理方式 |
| --- | --- | --- | --- |
| 旧 `FavouriteBackup(manga_id)` | 是 | 否 | import -> ensure work -> 写 work favourite |
| 旧 history/stat backup | 是 | 否 | import -> map anchor -> 写 work state |
| 旧 `entity_binding` | 是 | 否 | 保留 state，`LEGACY` 不自动提升 |
| 新 work sections | 是 | 是 | 先通过 `sync_id` / binding / anchor 映射本地 entity，再写本地 work state |
| 远端 `entity_id` | 是 | 否 | 只作为快照内 id，必须映射到本地 id |
| 远端 `sync_id` | 是 | 否 | 作为跨设备 Work 身份键，用于查找或创建本地 entity，不得当本地主键 |
| 远端 `manga_id` | 是 | 否 | 只作为快照内 projection 引用，必须通过 projection key 映射到本地 `manga_id` |
| `tracking_site_links` | 可选 | 否 | cache / audit only |
| legacy manga metadata source | 是 | 否 | explicit override 才保留 |

### 同步/备份防回流规则

备份和同步不能通过“同时导出 legacy section 和 work section”制造双主语义。

规则：

- sync v2 / Work-centric namespace 中，work sections 是唯一 authoritative 用户状态。
- legacy sections 在 v2 中只能作为 projection snapshot / migration evidence，不参与合并主决策。
- 自动上传前必须满足 normalization complete；否则禁写。
- restore 后必须记录 import provenance 和 semantic schema version。
- restore / merge 必须优先按 `(type, sync_id)` 映射 Work；缺失 `sync_id` 才使用强 binding / anchor fallback。
- `sync_id` 冲突且 entity type 不一致时进入 review / isolated import，不自动覆盖。
- 单 projection Work 的 projection-derived `sync_id` 只能由 source-scoped projection key 生成。
- 多 projection Work、用户合并 Work、手动整理后的 Work 不得因 preferred projection 改变而重写 `sync_id`。
- 删除必须保留 tombstone；不能因为旧 backup 里还有 active legacy row 就恢复较新的 work 删除。
- 跨设备合并必须按字段级 policy 执行，不允许整行“远端覆盖本地”。
- 设备时钟不可信时，必须优先使用本地导入顺序、dataVersion 或 monotonic revision；不能只靠 wall-clock `updated_at`。

确定性冲突算法：

1. 每个 v2 snapshot 带 `deviceId + dataVersion + exportedAt`。
2. 本地维护每个 remote device 的最大已导入 `dataVersion`，低于或等于已导入版本的 payload 直接跳过。
3. 字段级合并优先级：
   - tombstone 胜过旧 active row，除非 active row 的 `(deviceDataVersion, fieldRevision)` 更新。
   - `MANUAL` / 用户显式设置 胜过自动迁移或 candidate。
   - 同一字段都为自动来源时，比较 `(deviceDataVersion, fieldRevision, exportedAt, deviceId)`，按元组确定性排序。
4. `updated_at` 只作为同一设备同一 dataVersion 内的 tie-breaker，不作为跨设备主排序。
5. 无法确定胜负的冲突进入 review / repair，不自动覆盖。

禁止：

- 新版 sync v2 为了支持旧版导入而回写 v1 authoritative payload。
- v2 restore 完成前立即 auto upload。
- 旧客户端继续向 v2 namespace 上传。
- 远端 legacy `favourites(manga_id)` 覆盖本地较新的 `work_favourites(entity_id)`。
- 把远端 `sync_id` 复制到本地 projection / manga anchor。
- 用远端 `manga_id` 或不可信源裸 id 推导新的 `sync_id`。

## 测试计划

### 按阶段验收

Phase 0 / 1：升级与归一化

- 旧数据库升级后，legacy favourite 被补齐为 work favourite。
- `MANUAL` binding 不被 normalization 覆盖。
- `REJECTED` binding 阻止自动回流。
- `CANDIDATE` binding 不会被 normalization 自动提升为 `CONFIRMED`。
- `migrationState != VALID` 的记录不会参与 work 聚合、订阅 owner 或 sync v2 authoritative export。
- 同一 work 下多个 legacy favourite 的 category 删除/恢复按 tombstone 和 `updated_at` 合并，不会被旧 active row 误恢复。

Phase 2 / 3：运行时读写与页面迁移

- 同一 entity 下多个 manga 收藏只显示一个 work 行。
- preferred projection 失效后 fallback 到 active local binding。
- 新收藏写入只产生 work authoritative state。
- 详情页 Projection 入口尊重用户点击的 projection。
- 阅读器写历史时使用 entity owner 和 projection anchor。
- 新写路径不能只传入 `manga_id` 就写入作品级状态，必须经过 `WorkResolver`。

Phase 4：备份 / 同步隔离

- 旧备份恢复后不立即 auto upload。
- 旧备份中的 remote `entity_id` 不直接写成本地 `entity_id`。
- restore 优先用 `(type, sync_id)` 映射本地 entity。
- 远端 `sync_id` 不会被写入 projection / manga anchor。
- 远端 `manga_id` 通过 projection key 映射成本地 `manga_id`。
- restore 中远端 `entity_id` 与本地已有 `entity_id` 冲突时，不覆盖本地实体状态。
- 单 projection Work 在不同设备独立创建时，可通过相同 projection-derived `sync_id` 合并。
- 多 projection Work 切换 preferred projection 后 `sync_id` 保持不变。
- sync v2 导出时 legacy sections 只作为 projection snapshot / migration evidence，不参与 authoritative merge。
- 设备时钟倒退时，restore merge 不只依赖 wall-clock `updated_at` 做整行覆盖。

Phase 5：更新 / 订阅 / 整理

- `tracking_site_links` 和标题相似度只能生成整理候选，不会生成 confirmed binding。
- 更新订阅以 `entity_id` 保存订阅意图，以 `anchor_manga_id` 保存轮询观测；projection 删除不会删除 work subscription。
- 多 projection 更新只按 source-scoped chapter key 去重，不会把不同来源章节强行合并。

### 测试分层

单元测试：

- `WorkResolver`：找不到 entity、已有 binding、rejected binding、preferred projection 失效、批量解析分批。
- `sync_id`：UUID Work、projection-derived Work、合并 survivor、preferred projection 切换不变。
- migration review 状态机：`OLD_INPUT -> VALID / NEEDS_REVIEW`。
- merge policy：收藏 tombstone、历史进度选择、统计事件去重、tracking manual 优先。
- metadata migration：mirror shadow、explicit override、orphan metadata source。

DAO / Repository 测试：

- `WorkAggregateRepository` 分页、排序、分类筛选、来源筛选。
- migrated shadow 不参与主查询。
- review 项单独返回，不混入 confirmed work 分页。

集成测试：

- 旧数据库升级到新模型。
- 旧备份 restore 后 normalization complete 才允许 auto upload。
- 远端 `entity_id` 冲突映射。
- 远端 `sync_id` 与本地已有 entity 匹配时复用本地 `entity_id`。
- 远端 `sync_id` 缺失时才回退到 binding / anchor / legacy name-hash。
- 远端 `manga_id` 与本地 `manga_id` 冲突时，通过 projection key 重建 anchor 映射。
- 多设备 tombstone 合并。

性能测试：

- 5000 条收藏、每个 work 1-5 个 projection 的分页查询。
- 10000 条收藏、每个 work 1-10 个 projection 的压力查询。
- 1000 个 NEEDS_REVIEW 项与 5000 个 confirmed work 混合展示。
- 旧备份批量恢复 5000 条 favourites/history 的 normalization 时间。
- 旧备份批量恢复 10000 条 favourites/history/stats/tracks 的 normalization 时间。
- 收藏页首屏和翻页不允许全库内存聚合。

## 执行顺序

推荐 PR 拆分：

1. 新增 migrated shadow 基础设施、最小 migration ledger，并固化 `sync_id` 语义约束。
2. 新增 `WorkResolver` 和 `WorkIdentity`，迁移现有重复解析逻辑，统一 `sync_id` / binding / projection anchor 映射优先级。
3. 新增 `WorkAggregateRepository`，先服务收藏页。
4. normalization 版本化和幂等测试。
5. 收藏页切换到 `WorkAggregate`，旧状态自动迁移或进入 review。
6. 详情页入口收敛为 Work / Projection 两类。
7. backup / restore 通过 `sync_id` + `WorkResolver` 做本地身份映射。
8. WebDAV auto upload gate 绑定 normalization 状态。
9. 更新/订阅、tracker、scrobbling 的 owner 读写全部走 WorkResolver。
10. 实体整理工具削减为合并、拆分、默认来源三类动作。
11. 旧 owner 字段主决策移除评估。
12. metadata authority cleanup（可在主 identity 迁移稳定后执行）。

### PR 依赖与发布策略

```text
PR-1 migrated shadow infrastructure + minimal migration ledger + sync_id invariants
  -> PR-2 WorkResolver contract + sync_id/binding/projection mapping tests
  -> PR-3 WorkAggregateRepository read model
  -> PR-4 normalization merge policies + idempotency tests
  -> PR-5 favourites page WorkAggregate migration
  -> PR-6 history / reader write path migration
  -> PR-7 backup / restore semantic schema + sync_id mapping + auto-upload gate
  -> PR-8 updates / subscription owner split
  -> PR-9 organize tool simplification
  -> PR-10 legacy owner main-read removal
  -> PR-11 metadata authority cleanup (optional follow-up)
```

可并行项：

- PR-8 的 UI 文案和整理工具信息架构可以和 PR-4 之后并行。
- PR-11 的 metadata 清理可在 PR-1/PR-2 后开始探索，但不阻塞 identity 主迁移。
- enforcement 检查可从 PR-1 开始独立落地。

阻塞项：

- PR-1 阻塞 normalization 和 sync/backup authoritative 合并。
- PR-2 阻塞所有新读写路径。
- PR-7 阻塞开启 v2 auto upload。

增量发布：

- M1：PR-1 到 PR-4 可发布内部测试版，验证 shadow、ledger、resolver 和 normalization。
- M2：PR-5 到 PR-6 可发布小范围测试版，验证收藏/历史主链。
- M3：PR-7 后才允许启用 v2 sync namespace。
- M4：PR-10 后才考虑移除旧 owner 主读路径。

### 灰度与特性开关

Android 客户端不能像服务端一样按用户远程 rollout，因此迁移使用本地 feature gate 和数据库状态 gate。

建议开关：

- `workIdentityResolverEnabled`：启用 resolver 只读路径。
- `workAggregateFavoritesEnabled`：收藏页使用 `WorkAggregate`。
- `workIdentityWritesEnabled`：新写路径只写 work/entity 状态。
- `workSyncV2Enabled`：允许 v2 semantic schema 导出和 WebDAV auto upload。

启用顺序：

1. 内部/Debug 构建先启用 resolver 只读路径。
2. migrated shadow 和 minimal ledger 完整后启用收藏页 WorkAggregate。
3. 页面主链稳定后启用 work-only writes。
4. restore / normalization / tombstone 测试通过后启用 sync v2。

规则：

- `workSyncV2Enabled` 必须依赖 normalization complete。
- 任一 gate 关闭时，不能写入会让旧版本无法理解的新 authoritative payload。
- gate 状态必须写入诊断日志，方便用户反馈时定位处于哪个迁移阶段。

### Enforcement

文档规则必须工具化，至少包含：

- Code review checklist：禁止新增 `manga_id` owner 写入、禁止远端 `entity_id` 直写、禁止 candidate 自动 confirmed。
- `rg`/自定义脚本 CI：扫描新增直接调用 `EntityGraphRepository.findEntityIdsByAnyMangaIds`、直接写 `favourites(manga_id)` 的业务路径。
- Repository 层架构测试：主页面 ViewModel 不直接依赖 `EntityGraphRepository`，只能依赖 `WorkResolver` / `WorkAggregateRepository`。
- DAO 查询测试：主查询不得读取 migrated shadow。
- Sync/backup 测试：v2 payload 不导出 legacy authoritative section。

### 回滚策略

迁移必须可回滚到“继续使用旧版本数据”而不是造成数据丢失。

规则：

- Phase 0-3 不物理删除旧 owner rows，只标记 migrated / shadow。
- 每次 migration 必须能通过 shadow 和 minimal ledger 定位 legacy key、target entity、时间、schema version。
- 如果 Work 状态生成错误，可以通过 shadow / minimal ledger 重新生成或撤销对应 work-owned rows。
- v2 sync namespace 启用前，auto upload 必须默认禁写。
- v2 namespace 一旦写入，不再回写 v1 authoritative payload；回滚客户端只能读取本地旧 rows 或提示升级，不参与 v2 上传。

## 验收标准

迁移阶段完成后应满足：

- 页面代码不再各自实现 `mangaId -> entityId -> preferred projection`。
- 收藏、历史、统计、追踪的主 owner 是 `entity_id`。
- `manga_id` 只作为 projection anchor 使用。
- 旧备份和旧同步快照会自动转换为新合法状态；不能转换的进入 review，不会直接污染新主状态。
- 实体整理工具处理的是真实身份边界，而不是运行时双主状态造成的脏数据。
- repair 分类能区分 identity、metadata、cache、legacy import。
- 代码审查中禁止新增 `manga_id` 作为作品 owner 的写路径。
- 跨设备输入中的远端 `entity_id` 必须先映射成本地 `entity_id`。
- 自动流程不能把 candidate evidence 写成 confirmed identity。
- migration review 记录不得伪装成 confirmed work 参与聚合、订阅或 v2 同步。
- 更新/订阅必须区分 work-owned intent 和 projection-owned observation。
- sync / backup 必须有字段级冲突策略和 tombstone 规则，禁止整行覆盖。

## 非目标

本计划不做：

- 一次性删除旧表。
- 一次性重命名 `Entity -> Work`。
- 引入图数据库或远程实体服务。
- 用标题模糊匹配自动决定所有合并。
- 把角色、人物、组织关系图纳入收藏/同步主链。
- 允许旧客户端继续向新语义 namespace 写入。
