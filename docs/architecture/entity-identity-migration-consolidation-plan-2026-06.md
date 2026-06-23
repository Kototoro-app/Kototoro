# 实体身份迁移收敛计划（2026-06）

## 目的

本文档把 2026-06 之前的实体图谱、Work 化、同步隔离和治理补救文档重新收敛为一份可执行计划。

当前结论不是“放弃实体中心”，而是：

> 保留实体为产品中心，但把实体系统收敛为稳定的作品身份层；收藏、历史、统计、追踪等用户状态统一挂到作品身份；来源条目只作为可读、可播放、可更新的投影。

核心不变量：

```text
entity_id = 作品级用户状态键
manga_id = 来源投影 / 执行锚点
entity_binding = 来源投影与作品身份之间的唯一证据
preferred_local_manga_id = 当前默认展示/阅读投影，不改变作品身份
```

只要这个不变量不成立，收藏聚合、详情页、阅读记录、追踪、备份、同步都会继续出现局部修补和状态回流。

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
- preferred projection 失效后 fallback 到任一 local binding
- work 状态缺失时从 legacy manga 状态补齐
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

旧版本备份和旧同步快照里的 `manga_id` 状态是必须兼容的，但不能被直接恢复成当前主真相。

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
- 只对 `WORK` 承担用户状态 owner 语义。

非职责：

- 不直接表示具体阅读入口。
- 不直接承载 source-native 原始内容。
- 不要求 `CHARACTER / PERSON / ORGANIZATION` 参与收藏、历史、同步主链。

建议：

- `WORK` 是迁移期主线。
- `CHARACTER / PERSON / ORGANIZATION / relation` 暂时降级为详情页元数据缓存和导航辅助，不参与用户状态 ownership。

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

### Projection

职责：

- `manga` 表示一个来源中的可执行投影。
- 负责打开详情、加载章节、阅读、更新、下载、播放。

规则：

- `manga_id` 可以作为 execution anchor。
- `manga_id` 不再默认等价于作品 owner。
- 同一 entity 下可以有多个 local projection。

### Work User State

职责：

- 收藏、分类、历史、统计、追踪状态以 `entity_id` 为主键。
- 需要执行来源动作时携带 `anchor_manga_id`。

规则：

- 新写路径必须写 work/entity 状态。
- legacy manga 状态只作为兼容输入和 fallback。
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
    val isLegacyProjectionOnly: Boolean,
)
```

约束：

- 页面、repository、backup、sync、repair 不再各自手写 identity fallback。
- 所有 `mangaId -> entityId -> preferred projection` 规则集中在这里。
- 这里是迁移期间最重要的防腐层。

## 数据库迁移策略

### Phase 0：不删旧表，先建立统一解析门面

不做高风险大迁移：

- 不删除 `favourites`。
- 不删除 `history`。
- 不删除 `stats`。
- 不删除 track/scrobbling 中的 legacy `manga_id`。
- 不重命名 `entity` / `manga` 表。

先落地：

- `WorkResolver`
- `WorkAggregateRepository`
- 明确所有新读写入口优先走 `entity_id`

验收：

- 收藏页、详情页、历史页可以通过同一聚合模型读取状态。
- 旧数据不丢失。

### Phase 1：幂等归一化

建立可重复执行的 normalization：

1. 对所有 active legacy favourites/history/stats/tracks，确保存在 local manga binding。
2. 缺失 `WORK` entity 时创建最小 entity。
3. 从 legacy manga 状态补齐 work 状态。
4. 不自动删除 legacy 状态。
5. 不基于模糊标题自动合并不同 entity。
6. 已存在 `MANUAL` / `REJECTED` 绑定时严格尊重。

需要记录 normalization version，例如：

```text
work_identity_normalization_version = 1
```

验收：

- 同一设备多次运行结果一致。
- 崩溃中断后可重跑。
- 不会把候选绑定提升成 confirmed。

### Phase 2：双读单写

读：

- 通过 `WorkResolver` 同时兼容 work 状态和 legacy manga 状态。

写：

- 新行为只写 work/entity 状态。
- 只有兼容层需要时才同步更新 legacy projection 状态。
- legacy projection 写入必须标记为 projection mirror / compatibility write，不能反向定义 owner。

验收：

- 新收藏、新历史、新统计、新追踪默认以 `entity_id` 为 owner。
- `favourites(manga_id)` 仍可供旧导入和降级 fallback 使用。

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

验收：

- 同一作品多来源收藏在收藏页只出现一个 work 行。
- 行内可以展示来源数量、当前默认来源、切换入口。
- 点击具体来源时仍能进入 source-native 详情或阅读。

### Phase 4：同步/备份语义隔离

必须兼容旧输入：

- 旧 backup 中只有 `FavouriteBackup(manga_id)`。
- 旧 sync snapshot 中可能没有 work sections。
- 旧 WebDAV 远端可能只有 legacy semantic schema。

恢复规则：

1. 旧 favourites/history/stats/tracks 先作为 import 输入。
2. 通过 `WorkResolver.ensureForProjection()` 映射到本地 entity。
3. 再写 work 状态。
4. 远端 `entity_id` 只作为快照内临时 id，不能直接当本地主键。
5. restore 后如果 normalization 未完成，auto upload 必须禁写。

新导出规则：

- 新 schema 导出 work sections。
- legacy sections 可保留兼容，但不得作为 authoritative state。
- semantic schema version 必须独立于 WebDAV transport generation。

验收：

- 新版可以读旧备份。
- 新版不把旧语义直接上传成新主真相。
- 多设备恢复后，本地 `entity_id` 映射稳定。

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

### Phase 6：退役 legacy 主决策

只有在以下条件满足后，才允许考虑退役旧字段：

- 所有主页面都使用 `WorkAggregate`。
- backup / restore 已稳定支持 semantic schema version。
- normalization 可重复且有测试覆盖。
- 旧版本导入路径明确。
- 至少一个稳定版本周期内没有新增 legacy mirror 污染。

退役顺序：

1. legacy manga 状态从主读路径退出。
2. legacy projection mirror 写入停止。
3. 旧字段仅保留导入兼容。
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
- 无 entity 的 legacy projection 单独显示，并提示可整理。
- 分类 membership 以 work 状态为准，legacy favourites 只补缺。

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
- 如果实体解析失败，写 legacy 状态并进入 normalization 候选。

### Tracker / Scrobbling

规则：

- tracking binding 绑定到 entity。
- update feed 和 unread counters 以 entity 聚合。
- provider 的 per-manga cache 不参与 identity owner 决策。

## 备份和同步兼容矩阵

| 输入类型 | 允许读取 | 允许直接主写 | 处理方式 |
| --- | --- | --- | --- |
| 旧 `FavouriteBackup(manga_id)` | 是 | 否 | import -> ensure work -> 写 work favourite |
| 旧 history/stat backup | 是 | 否 | import -> map anchor -> 写 work state |
| 旧 `entity_binding` | 是 | 否 | 保留 state，`LEGACY` 不自动提升 |
| 新 work sections | 是 | 是 | 先 remote id 映射，再写本地 work state |
| 远端 `entity_id` | 是 | 否 | 只作为快照内 id，必须映射到本地 id |
| `tracking_site_links` | 可选 | 否 | cache / audit only |
| legacy manga metadata source | 是 | 否 | explicit override 才保留 |

## 测试计划

最小回归覆盖：

1. 旧数据库升级后，legacy favourite 被补齐为 work favourite。
2. 同一 entity 下多个 manga 收藏只显示一个收藏行。
3. `MANUAL` binding 不被 normalization 覆盖。
4. `REJECTED` binding 阻止自动回流。
5. preferred projection 失效后 fallback 到 active local binding。
6. 旧备份恢复后不立即 auto upload。
7. 旧备份中的 remote `entity_id` 不直接写成本地 `entity_id`。
8. 新收藏写入只产生 work authoritative state。
9. 详情页 Projection 入口尊重用户点击的 projection。
10. 阅读器写历史时使用 entity owner 和 projection anchor。

## 执行顺序

推荐 PR 拆分：

1. 新增 `WorkResolver` 和 `WorkIdentity`，迁移现有重复解析逻辑。
2. 新增 `WorkAggregateRepository`，先服务收藏页。
3. 收藏页切换到 `WorkAggregate`，保留 legacy fallback。
4. 详情页入口收敛为 Work / Projection 两类。
5. normalization 版本化和幂等测试。
6. backup / restore 通过 `WorkResolver` 做本地身份映射。
7. WebDAV auto upload gate 绑定 normalization 状态。
8. 实体整理工具削减为合并、拆分、默认来源三类动作。
9. tracker / scrobbling 的 owner 读写全部走 WorkResolver。
10. legacy 主决策退役评估。

## 验收标准

迁移阶段完成后应满足：

- 页面代码不再各自实现 `mangaId -> entityId -> preferred projection`。
- 收藏、历史、统计、追踪的主 owner 是 `entity_id`。
- `manga_id` 只作为 projection anchor 使用。
- 旧备份和旧同步快照可以导入，但不会直接污染新主状态。
- 实体整理工具处理的是真实身份边界，而不是运行时双主状态造成的脏数据。
- repair 分类能区分 identity、metadata、cache、legacy import。

## 非目标

本计划不做：

- 一次性删除旧表。
- 一次性重命名 `Entity -> Work`。
- 引入图数据库或远程实体服务。
- 用标题模糊匹配自动决定所有合并。
- 把角色、人物、组织关系图纳入收藏/同步主链。
- 兼容旧客户端继续向新语义 namespace 写入。
