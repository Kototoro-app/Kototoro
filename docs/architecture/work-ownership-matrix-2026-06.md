# Work Ownership Matrix（2026-06）

## 目的

本文档定义 Kototoro 从 `Entity -> Work`、`Manga -> Projection` 迁移期间的 ownership 归属矩阵。

目标不是先改命名，而是先明确：

> 哪类数据最终属于 Work，哪类数据属于 Projection，哪类数据属于 TrackingBinding，哪类数据只是 ResolvedMetadata cache。

没有 ownership，系统就不会稳定。

## 核心原则

### 1. 只有 Work 是主模型

最终必须保证：

- `Work` 是唯一的用户语义聚合根；
- `Projection` 只是来源投影；
- `TrackingBinding` 只是 metadata enrichment；
- `ResolvedMetadata` 只是 derived cache。

### 2. 不允许三层主模型并存

必须避免以下状态同时被视为主真相：

- `Manga`
- `Entity`
- `ResolvedMetadata`

否则：

- fallback 会继续爆炸；
- repair 永远收不拢；
- sync 会持续产生语义撕裂。

### 3. 先迁移 ownership，再迁移命名

迁移顺序必须是：

1. 先把状态归属迁到 Work；
2. 再把读写流程改成 Work-first；
3. 最后才做 `Entity -> Work`、`Manga -> Projection` 的命名统一。

## 模型定义

### Work

Work 是用户真正操作的作品对象。

它最终负责：

- library/favorite state
- categories
- tracking bindings
- preferred projection
- history anchor
- reading progress anchor
- user overrides
- resolved metadata cache invalidation anchor

### Projection

Projection 是作品在某个阅读源中的具体投影。

它最终负责：

- source identity
- source-native metadata
- chapters/pages/episodes
- 打开/阅读/播放能力
- source-specific availability

### TrackingBinding

TrackingBinding 是外部 tracking site 的 work-level 绑定。

它最终负责：

- 外部站点 identity
- 外部站点进度/状态同步锚点
- metadata enrichment source

### ResolvedMetadata

ResolvedMetadata 是派生缓存，而不是主真相。

它最终负责：

- canonical title cache
- canonical cover cache
- canonical description cache
- merged tags cache

它必须满足：

- 可重建
- 可失效
- 不参与主身份决策
- 不得成为用户状态 ownership 持有者

## Ownership Matrix

| 数据 | 最终归属 | 当前状态 | 迁移策略 |
| --- | --- | --- | --- |
| favorite / library state | Work | 主要在 manga/favourites 侧 | 上移到 Work，过渡期双读，最终单写 |
| categories | Work | 主要在 favourites / manga 侧 | 上移到 Work category binding |
| tracking binding | Work | entity + manga link + tracking_site_link 混合 | 收敛到 Work-level binding |
| preferred source / preferred projection | Work | `entity_preferences.preferred_local_manga_id` | 先保留，后重命名为 preferred projection |
| reading history anchor | Work | `history.manga_id` 驱动 | 改为 Work anchor + active projection context |
| reading progress anchor | Work | 主要以 manga/history 驱动 | 上移到 Work，但保留 projection/chapter 定位信息 |
| reading status / scrobbling status | Work | manga prefs / scrobbling 混杂 | Work-level 状态，projection 只做上下文 |
| manual title override | Work | manga prefs | 上移为 Work override |
| manual cover override | Work | manga prefs | 上移为 Work override |
| content rating override | Work | manga prefs | 上移为 Work override |
| entity metadata selection | Work | `entity_preferences` | 保留并演进为 Work metadata preference |
| update tracking anchor / new chapters counters | Work-owned anchor, legacy projection storage | `tracks.manga_id` | 运行时先收敛到 preferred local projection，后续再迁表 |
| per-manga metadata selection | Projection-local override | manga prefs | 降级为显式 local override only |
| source title | Projection | manga | 保持在 Projection |
| source cover | Projection | manga | 保持在 Projection |
| source description | Projection | manga | 保持在 Projection |
| source tags/authors | Projection | manga | 保持在 Projection |
| chapters / episode list | Projection | manga + chapters | 保持在 Projection |
| source URL / remote id | Projection | manga/source | 保持在 Projection |
| tracking raw metadata | TrackingBinding | tracking cache / tracking item details | 不上移为 Work 真相，只作为 enrichment |
| canonical title / cover / tags | ResolvedMetadata cache | 当前多处复制/覆盖 | 收敛为单点派生缓存 |
| tracking suggestion ignore state | Projection-local hint | manga prefs | 保留为投影级抑制状态 |
| tracking_site_links | Cache / audit | 当前半真相 | 降级为缓存/审计，不再持有 ownership |

## 关键说明

### favorite / categories

最终语义：

- 用户收藏的是作品，不是单个来源条目；
- 分类属于作品聚合，不属于单一 projection。

现实注意事项：

- 初期可保留现有 favourites 表作为兼容入口；
- 但新增/修改逻辑必须逐步改为以 Work 为中心；
- UI 列表即使仍从 projection 渲染，也不应让 projection 重新拥有 favorite truth。

### history / reading progress

这是最容易误判的一类。

正确做法不是简单把 `history` 全部脱离 projection，而是：

- Work 持有“这个作品的阅读状态”
- Projection 持有“当前在哪个来源、哪个 chapter/page/episode 上定位”

也就是说：

```text
Work = state owner
Projection = execution context
```

否则会丢失：

- 章节定位
- 不同来源章节映射差异
- reader/player 恢复能力

### tracking binding

最终 tracking binding 必须只属于 Work。

原因：

- tracking 表示外部作品 identity 或 enrichment 来源；
- 它不应再由任意 manga/projection 独立定义；
- `tracking_site_links` 只能作为 cache / audit / suggestion history。

### metadata selection

需要明确区分两层：

#### Work metadata selection

表示：

- 这个作品默认以哪个 metadata authority 展示。

它属于 Work。

#### Projection metadata selection

表示：

- 这个 projection 在局部场景下显式覆盖 Work 默认值。

它不是 Work 主真相。

### overrides

以下 override 最终都应视为 Work override：

- manual title
- manual cover
- content rating override

因为这些都是用户对“作品”的判断，而不是对单一来源投影的判断。

如果长期留在 manga prefs，会继续制造：

- projection-centric ownership
- Work / Projection 语义冲突

### reading status

`reading status / scrobbling status` 的 ownership 应和 override 一样理解：

- 当前 authoritative 状态应落在 Work / entity prefs；
- projection 侧如果还留有相同值，只能视为 legacy mirror；
- repair 应优先清理与 entity 完全一致的 projection reading status 镜像。

### tracking suggestion ignore state

这类状态需要明确保持在 Projection-local hint：

- 它表示“这个本地投影的某个 tracking 建议被忽略”；
- 不等于整个 Work 永久拒绝该 tracking 候选；
- 不应被错误上移成 Work owner state。

### ResolvedMetadata

需要特别强调：

- `ResolvedMetadata` 不是 source of truth；
- 它是 derived cache；
- 它的存在目的是提升：
  - 列表性能
  - SQL 查询能力
  - 离线详情稳定性

但是：

- 不允许用户状态写入它；
- 不允许它反向重写 Work / Projection / TrackingBinding；
- 不允许把它误当作第三主模型。

## 当前到目标的过渡映射

### 当前 `entity`

当前 `entity` 应视为：

```text
过渡期 Work
```

后续要做的是：

- 给它 Work ownership；
- 让它接管用户状态；
- 最终再统一命名。

### 当前 `manga`

当前 `manga` 应视为：

```text
过渡期 Projection
```

后续要做的是：

- 让它失去主用户状态 ownership；
- 保留 source-native metadata 和 chapters；
- 保留打开/阅读/播放能力。

### 当前 `tracking_site_links`

当前应视为：

```text
cache / audit / stale suggestion history
```

不得再把它当成 work identity 主真相。

### 当前 `entity_preferences`

当前应视为：

```text
过渡期 WorkPreferences
```

已经在承担：

- preferred local projection
- metadata preference

后续应继续演进，而不是重新发明另一套 preference。

## 迁移优先级

### 第一优先级：必须尽快迁到 Work

1. tracking binding
2. favorite / library state
3. categories
4. preferred projection
5. metadata default selection

### 第二优先级：需要 Work 持有主状态，但保留 Projection 上下文

1. history anchor
2. reading progress
3. chapter / page / episode 恢复点

### 第三优先级：继续留在 Projection

1. source metadata
2. chapters / episodes
3. source URL / remote id
4. availability / playable/readable capability

### 第四优先级：派生缓存

1. canonical title
2. canonical cover
3. canonical tags
4. merged description

## 设计约束

### 约束 1

任何新增功能都不得再把 projection 当作用户状态最终归属。

### 约束 2

任何 metadata cache 都不得反向成为 Work/Projection 的主真相来源。

### 约束 3

任何 tracking 相关逻辑都不得再把 per-manga link 当作 authoritative binding。

### 约束 4

过渡期允许双读，但不允许无限期双主模型并存。

## 验收标准

当 ownership 迁移完成到可接受阶段时，应满足：

1. 用户收藏/分类/追踪操作在语义上都作用于作品；
2. projection 不再承担用户主状态；
3. tracking binding 只属于 Work；
4. metadata default 只属于 Work；
5. per-projection metadata 只作为局部 override；
6. resolved metadata 明确只是 derived cache；
7. 开发者在新代码里不再把 `manga` 视为作品主对象。

## 一句话结论

Work 化迁移最重要的不是先改名字，而是先把下面这件事做对：

> **所有用户语义最终都必须收敛到 Work ownership。**
