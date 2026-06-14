# 实体中心 Work 化改造执行计划（2026-06）

## 目的

这份文档不是再讨论“目标架构是否正确”，而是把当前 Kototoro 的实体治理、ownership 上移、历史兼容收敛，整理成一份真正可执行的改造路线。

本文的核心判断只有一句话：

> 不再继续“修 entity 补丁层”，而是把现有 entity graph 直接演进为过渡期 Work 内核。

同时明确一个边界：

> 当前讨论的“同步隔离”只针对现行的 `backup / restore / WebDAV auto upload / WebDAV auto restore` 主链；仓库中的 `sync/` 与旧 `kotatsu sync` 源码仅为历史保留，不再作为当前 authoritative 同步方案依据。

## 当前事实

截至当前工作树，系统已经不是单纯的：

```text
Manga 主模型
+ Entity 外挂聚合
```

而更接近：

```text
Entity = 过渡期 Work
Manga = 过渡期 Projection
Tracking = enrichment / binding evidence
Resolved state = entity/work-first
```

已经完成的关键收口：

1. `work_history` / `work_favourites` / `work_stats` 已落地；
2. tracking / scrobbling / history / recent list 已开始走 work-aware owner 解析；
3. metadata default 主链已基本回到 `entity_preferences`；
4. blind mirror 已退出主链，projection prefs 越来越接近 local override；
5. backup / restore 当前 schema 已转为 work-aware；
6. `tracking_site_links` 已开始从 owner 解析主链退出。

因此，下一阶段不该再做“概念层争论”，而应该直接推进 ownership、写入边界和运行时入口的彻底收口。

## 改造目标

### 1. 主模型目标

运行时只允许存在一个主模型：

```text
Work（当前由 entity 承担）
```

其它对象全部降级：

- `Manga` -> Projection / source-native execution anchor
- tracking binding -> Work enrichment
- resolved metadata -> derived cache / derived selection
- `tracking_site_links` -> cache / audit / suppression history

### 2. 语义目标

所有高价值用户状态最终都要满足：

- owner 在 Work
- projection 只承载来源上下文和执行锚点
- tracking 不直接拥有用户状态
- metadata 不再多点复制为“多个真相”

### 3. 工程目标

本轮不追求：

- 一次性全量 rename
- 一次性表结构大爆炸
- 一次性删光所有 legacy 字段

本轮只追求：

1. 新写路径彻底 Work-first
2. 旧兼容路径明确降级
3. repair / restore / migration 不再继续放大污染
4. 后续命名统一时不再需要反复返工 ownership

## 执行原则

### 1. 先 ownership，后 rename

顺序必须是：

```text
ownership 上移
-> 写入边界收口
-> 运行时入口统一
-> 兼容字段降级
-> 最后才做 Entity -> Work / Manga -> Projection 命名统一
```

### 2. 先止血，后清理

只要旧 mirror、旧 fallback、旧 restore 回写仍在持续制造新污染，任何“整理实体”都会持续返工。

### 3. 当前同步边界只认 backup / WebDAV

当前跨设备主链以：

- 本地 backup
- restore
- WebDAV auto upload
- WebDAV auto restore

为准。

仓库里的旧 `sync/` 代码不作为本次 Work 化路线的主设计依据，也不作为验收对象。

### 4. Projection 允许继续存在，但不得继续持有 owner 语义

这意味着：

- `mangaId` 仍可作为大量 API 的执行锚点；
- 但不得再默认等价于“作品 owner id”。

## 工作流拆分

### Stream A：Work Ownership 收口

目标：

- 把用户状态 ownership 全部收敛到 entity/work；
- 限制 projection 只保留执行上下文。

实施范围：

- favourites
- history
- reading record
- stats
- tracking / scrobbling
- details 的默认状态读写

完成定义：

- 新增状态写入不再依赖 projection 作为 owner；
- projection fallback 只在 no-entity / legacy import 场景触发；
- 公共 helper 默认表达 “resolve work owner”，而不是 “resolve manga owner”。

### Stream B：Metadata 写入边界收口

目标：

- Work default 与 projection override 完全分流；
- 停止一切 blind mirror 变体。

实施范围：

- `ContentDataRepository`
- `EntityGraphRepository`
- details metadata selection
- tracking bind / merge 后的 metadata 写入
- repair / migration worker

完成定义：

- Work authoritative write 只写 Work/entity prefs；
- projection metadata write 只在显式 local override 场景出现；
- source-native metadata 更新不反向定义 Work 默认值。

### Stream C：Projection 降级与本地锚点统一

目标：

- 明确 `manga` 的当前语义是 projection；
- 所有“代表内容”选择都通过 work preference 解析。

实施范围：

- recent history / continue reading
- updates / tracker / new chapter counters
- source migration
- preferred local manga / representative content

完成定义：

- 同一 work 的多 projection 不再在高频列表里重复放大；
- source switch 后 continue reading / updates / shortcut / widget 跟随当前 work representative；
- 同一 work 的 tracker anchor 不再分裂。

### Stream D：Restore 与历史兼容隔离

目标：

- 旧数据允许导入；
- 旧语义不允许重新成为主真相。

实施范围：

- backup schema
- restore normalization
- WebDAV auto restore / auto upload gate
- legacy section degrade

完成定义：

- legacy payload 导入后默认进入 normalize / degrade / repair；
- 不自动回灌为 authoritative work state；
- restore 后 auto upload 必要时继续禁写，直到本地归一化完成。

### Stream E：Repair 与治理工具重构

目标：

- repair 结果重新可信；
- organize / migration / merge 工具只处理真正的边界问题。

实施范围：

- `inspectRepairIssues()`
- organize panel categories
- prune 系列动作
- suspect / stale / redundant 分类

完成定义：

- `SUSPECT_TRACKING_BINDING` 只反映真实 work tracking identity 问题；
- metadata drift、cache drift、mismerge 风险独立呈现；
- repair 动作不再混合不同层级语义。

## 分阶段执行顺序

### Phase 0：冻结错误主链

目标：

- 不再继续扩散旧语义。

必做项：

1. 禁止新增任何 `entity -> all manga` mirror 写法；
2. 禁止新增任何基于 `tracking_site_links` 的 owner fallback；
3. 禁止新增任何 restore 后直接 authoritative writeback 的 shortcut；
4. 所有新入口默认走 entity/work-first helper。

验收：

- 新 PR 不再引入 projection-owned 新状态；
- metadata 新写链全部可归类到 authoritative / override / source-native 三类之一。

### Phase 1：高价值 ownership 主链补齐

目标：

- 把最容易污染用户感知的状态先完全 Work-first。

优先级：

1. favourite / category
2. history / continue reading
3. stats / reading duration
4. tracking / updates / scrobbling

验收：

- 上述链路的主写路径都能在不依赖 projection owner 的情况下闭环；
- source 切换后用户状态不再分叉。

### Phase 2：metadata authority 完全分流

目标：

- 真正切开 Work metadata default 与 projection override。

必做项：

1. `setEntityMetadataSourceSelection(...)` 只保留 Work 语义；
2. projection override 改成显式单项入口；
3. repair / worker 分别处理 Work drift 与 projection drift；
4. 用户 override 开始从 manga prefs 向 Work override 迁移。

验收：

- 不再存在“同一方法长期混写 Work default + projection override”的主链；
- per-manga metadata source 明确降级为局部 override / legacy fallback。

### Phase 3：projection 锚点运行时统一

目标：

- 所有运行时“代表内容”都显式走 Work preference。

必做项：

1. continue reading / recent list / shortcuts / widgets 统一代表内容解析；
2. tracker / update counter / new chapters 统一 anchor；
3. source migration 只迁移当前 projection anchor，不波及整个 work 的其它证据。

验收：

- 同 work 多 projection 不再在 recent、updates、tracking 主链里造成重复和分裂；
- preferred source 切换后共享入口表现一致。

### Phase 4：restore / backup 旧语义隔离收尾

目标：

- 完成“能导入旧数据，但旧数据不再继续写坏新模型”。

必做项：

1. 继续推进 `WORK_*` / `ENTITY_GRAPH_*` authoritative sections；
2. legacy sections 保持 import-compatible，但不回升为主真相；
3. WebDAV auto upload gate 与 restore normalization 状态严格绑定；
4. 明确 remote payload 的 semantic schema version 语义。

验收：

- restore 后不会重新制造 mirror 型污染；
- auto upload 不会把 legacy import 结果直接传播为 authoritative state。

### Phase 5：术语与表结构统一

目标：

- 在 ownership 和写入边界稳定后，再处理命名债务。

候选项：

1. `Entity -> Work`
2. `local_manga / manga -> projection`
3. `preferred_local_manga_id -> preferred_projection_id` 语义统一
4. backup section / DTO / helper 命名去 legacy 化

前置条件：

- 运行时主链已经稳定；
- 兼容字段已降级；
- repair 和 restore 不再依赖旧语义名称做判断。

## 代码层责任矩阵

### 1. EntityGraphRepository

角色：

- 过渡期 Work 内核
- identity / binding / repair / ownership 治理中心

禁止：

- 继续承担 projection mirror 批量写入分发器

### 2. ContentDataRepository

角色：

- metadata 读取与局部 override 网关

禁止：

- 再把 source-native metadata 更新回写成 Work authoritative truth

### 3. History / ReadingRecord / Stats / Tracking Repository

角色：

- 逐步改造成“Work owner + projection anchor”结构

禁止：

- 继续把任意传入 `mangaId` 直接等价解释为 owner id

### 4. Backup / Restore / WebDAV Coordinator

角色：

- 导入导出边界控制器

禁止：

- 让 legacy section 或 legacy restore 直接变成当前 authoritative state

## 里程碑验收

### M1：治理止血完成

标准：

- repair 噪音显著下降；
- blind mirror 不再扩散；
- `tracking_site_links` 不再回流 owner 解析。

### M2：用户状态主链 Work-first

标准：

- favourite / history / stats / tracking 主链完成 ownership 上移；
- 多 projection 下用户状态不再系统性分裂。

### M3：metadata 写入边界稳定

标准：

- authoritative / override / source-native 三类写入边界清晰；
- 用户 override 不再主要挂在 manga prefs。

### M4：restore 隔离稳定

标准：

- restore 与 auto upload 不再传播旧语义污染；
- backup 当前 schema 与 legacy import 的边界清晰。

### M5：命名统一可启动

标准：

- rename 只剩工程噪音问题，而不再夹杂 ownership 风险。

## 非目标

本计划当前不包含：

- 重启或扩展旧 `kotatsu sync` 体系；
- 引入远程 work/entity 服务端；
- 一次性重写所有详情页 UI；
- 用模糊匹配自动决定全部最终身份真相；
- 为未来未落地场景预建新抽象层。

## 推荐下一步

按当前工作树状态，下一步应直接进入下面三个并行切口：

1. **继续完成 override ownership 上移**
   - 把 title / cover / content rating 等 override 从 manga prefs 往 Work 收。
2. **继续削减 projection-owned runtime path**
   - 逐条审计 details / reader / player / download / preview 的 `mangaId == owner` 惯性。
3. **继续收紧 restore write gate**
   - 让 legacy import、normalize、authoritative upload 三段彻底分离。

这三项做完，后续的 `Entity -> Work`、`Manga -> Projection` 命名统一才值得开始。
