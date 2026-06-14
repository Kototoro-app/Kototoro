# Work 化迁移期间的新旧同步隔离方案（2026-06）

## 目的

本文档定义 Kototoro 在 `Entity -> Work`、`Manga -> Projection` 迁移期间的同步隔离策略。

这里的“同步”有明确边界：

- 当前只指现行的 `backup / restore / WebDAV auto upload / WebDAV auto restore` 主链；
- 不指仓库中遗留保留的 `sync/` 与旧 `kotatsu sync` 服务源码；
- 旧 `sync/` 代码不作为本次 Work 化同步隔离设计的 authoritative 依据。

核心目标只有一个：

> 在 Work 化迁移期间，禁止旧版 Manga-centric 语义继续污染新版 Work-centric 语义。

这不是普通字段兼容问题，而是**身份模型升级**问题。

因此：

- 新版可以兼容读取旧数据；
- 旧版不能继续写入新版核心语义；
- 同步协议必须显式分代；
- 迁移完成后必须切换新的 sync namespace。

## 背景

当前系统正从以下模型迁移：

```text
旧模型：
  Manga = 用户主对象
  Entity = 后挂聚合层
  tracking / metadata / prefs / cache 多点写入

目标模型：
  Work = 用户主对象
  Projection = 阅读源投影
  TrackingBinding = metadata enrichment
  ResolvedMetadata = derived cache
```

这意味着：

- `favorite`、`category`、`history`、`tracking`、`preferred source` 等状态的 ownership 正在变化；
- metadata 正从多点镜像写入改为单点解析；
- entitygraph 正从“补丁式聚合层”演进为 Work 内核。

如果旧版客户端继续同步写入：

- Manga-centric 的收藏/历史/tracking 语义会回流；
- metadata mirror 会重新污染 Work / Projection 边界；
- repair / merge / fallback 会继续制造旧语义残留；
- Work 化迁移将长期处于“边迁移边回退”的不稳定状态。

## 问题性质

### 不是字段兼容问题

当前迁移不是：

```text
新增一个字段
```

而是：

```text
谁才是真正的作品主对象
```

发生了变化。

旧版理解：

```text
Manga 是主对象
```

新版理解：

```text
Work 是主对象
Projection 只是作品在阅读源中的投影
```

因此这属于：

- worldview change
- identity model change
- sync protocol incompatibility

### 兼容读取不等于兼容写入

迁移期间必须遵循：

```text
新版：read old, write new
旧版：read old only
```

禁止：

```text
old -> new writeback
```

否则旧客户端会把新语义重新扁平化回 Manga-centric 模型。

## 风险分析

### 风险 1：收藏/分类 ownership 回流

旧版可能修改：

- `manga.favorite`
- `manga.category`

但新模型中这些状态目标上应属于 `Work`。

结果：

- Work library state 被旧 Projection 状态覆盖；
- 多 projection 下状态不一致；
- 用户在新版本做的整理被旧版本写回破坏。

### 风险 2：tracking 语义回流

旧版可能把 tracking 绑定继续当作 Manga 属性处理。

结果：

- duplicate binding
- dangling mapping
- work-tracking binding 被旧版重新扁平化

### 风险 3：metadata mirror 回流

旧版可能继续做以下路径：

- manga -> entity
- entity -> manga
- tracking -> manga
- repair -> overwrite

结果：

- Work metadata cache 被旧 mirror 覆盖；
- Projection 原始 metadata 与 resolved metadata 边界再次混乱；
- repair 永远修不完。

### 风险 4：旧版自动修复逻辑污染

最危险的不是 crash，而是旧客户端启动后静默执行：

- repair
- mirror
- fallback
- rebind
- overwrite

这种污染通常表现为：

- slowly drifting data
- 难复现
- 难归因
- 修完又回来

## 总体策略

### 核心原则

Work 化迁移期间采用：

1. **协议分代**
2. **同步命名空间隔离**
3. **新版双读单写**
4. **旧版禁止写入新版核心语义**

### 推荐结论

最推荐方案是：

```text
sync schema v1 = manga-centric
sync schema v2 = work-centric
```

升级到 Work 化版本后：

1. 本地执行一次性迁移；
2. 切换到新的 sync namespace；
3. 旧客户端检测到更高协议版本后只读或拒绝同步。

## 同步协议设计

### 数据版本

建议在同步元数据中引入：

```kotlin
data class SyncEnvelope(
    val schemaVersion: Int,
    val namespace: String,
    val exportedAt: Long,
)
```

建议版本定义：

- `1`：legacy / manga-centric
- `2`：work migration / work-centric

### namespace

建议直接分开同步命名空间，例如：

- `kototoro-sync-v1`
- `kototoro-sync-v2`

规则：

- `v1` 只给旧版使用；
- `v2` 只给 Work 化版本使用；
- 升级到 `v2` 后不再回写 `v1`。

这样可以避免：

- 旧版误读新版数据；
- 新版被迫向旧结构扁平回写；
- sync payload 因兼容逻辑变得极其复杂。

## 客户端行为规则

### 旧版客户端

当旧版客户端发现：

```text
server schemaVersion > supportedVersion
```

应采取以下行为之一：

1. 只读
2. 禁止上传
3. 明确提示升级

不应继续：

- 上传本地旧语义数据
- 自动修复服务端状态
- 把 Manga-centric 状态回写到新版 namespace

### 新版客户端

新版客户端在迁移期应满足：

1. 可以读取旧版 `v1` 数据进行一次性导入；
2. 本地迁移完成后，所有新写入都写 `v2`；
3. 不再回写 `v1`；
4. 对 `v1` 的读取只用于迁移或只读兼容，不作为长期双向同步目标。

## 数据流规则

### 允许

#### `v1 -> v2`

允许一次性导入，用于初次升级迁移：

- 读取旧收藏
- 读取旧 tracking
- 读取旧 history
- 读取旧 prefs

然后映射到：

- Work ownership
- Projection bindings
- Work preferences

#### `v2 -> v2`

允许新版正常双向同步。

### 禁止

#### `v2 -> v1`

禁止把 Work-centric 语义回写成 Manga-centric 数据。

否则会出现：

- ownership flattening
- metadata backfill pollution
- fallback mirror 回流

#### `v1 -> v2` 持续增量双向写

除了升级时的一次性迁移导入，不应长期允许旧版继续增量写 `v2`。

## 迁移阶段

### Phase 1：Legacy 读入，新语义写出

状态：

- 旧版仍在使用 `v1`
- 新版支持读取 `v1`
- 新版开始内部 Work 化

要求：

- 新版只把 `v1` 作为导入来源；
- 新版内部写入全部使用新语义；
- 不做 `v2 -> v1` 回写。

### Phase 2：本地 Work 化迁移完成

执行：

- ownership 上移
- metadata 去镜像化
- binding 化
- projection 降级

此时本地已经具备：

- Work 主模型
- Projection 投影模型
- tracking binding 模型
- resolved metadata cache 模型

### Phase 3：切换 sync namespace

迁移完成后：

- 切到 `kototoro-sync-v2`
- 写入 `schemaVersion = 2`

此时：

- 新版只读/只导入 `v1`
- 新版正式写 `v2`
- 旧版无法继续写入 `v2`

### Phase 4：旧版只读或不支持

当 `v2` 成为正式协议后：

- 旧版只读
- 或直接提示升级
- 或禁止同步

目标是：

- 阻断旧世界观继续写入新世界

## 与 Work 化迁移的关系

### 为什么同步隔离必须先于 Work 化收尾

如果不同步隔离，以下工作都会被持续破坏：

- ownership 上移
- metadata resolver 落地
- entity -> work 迁移
- manga -> projection 迁移
- repair 去噪
- binding 主语义收敛

也就是说：

> 不先阻断旧协议写入，就无法稳定完成新模型收敛。

### 哪些数据最需要优先隔离

优先级从高到低建议如下：

1. `tracking bindings`
2. `favorite / library state`
3. `category bindings`
4. `preferred projection / source preference`
5. `metadata selection`
6. `history / reading progress anchors`

原因：

- 这些字段直接决定 Work ownership；
- 一旦继续被旧版回写，Work 化会出现语义撕裂。

## 实施建议

### 建议 1：显式记录同步协议版本

不要隐式猜测客户端/数据格式。

必须显式记录：

- `schemaVersion`
- `namespace`

### 建议 2：旧版检测到高版本时禁止上传

即使仍允许浏览本地数据，也不能让旧版继续写服务器数据。

### 建议 3：升级迁移时采用一次性导入

旧数据迁移应视为：

- import
- transform
- re-anchor

而不是：

- 长期双向兼容写

### 建议 4：禁止旧语义自动修复逻辑写入新版 namespace

尤其要防止：

- fallback 自动补写
- repair 自动修复
- metadata mirror
- tracking relink

这些逻辑一旦保留，会让 Work 化迁移长期不稳定。

## 非目标

本方案不包含：

- 立即重写所有同步实现；
- 立即删除 `v1` 协议；
- 立即删除所有本地 legacy 字段；
- 在迁移前一次性完成所有 Work/Projection 结构重命名。

本方案只定义：

- 迁移期间新旧同步的隔离原则；
- 协议分代；
- namespace 切换；
- 旧客户端写入禁令。

## 验收标准

同步隔离完成后，应满足：

1. 旧客户端不能继续写入新版 Work-centric namespace；
2. 新版可以一次性读取并导入旧数据；
3. 新版不会把 `v2` 语义回写成 `v1`；
4. metadata mirror、tracking flattening、旧 repair 写回不会再通过同步回流；
5. Work ownership 迁移后不会被旧版本增量同步破坏；
6. 线上/云端同步数据能够按协议版本明确区分。

## 一句话结论

你们现在做的不是普通数据库升级，而是：

> **作品身份模型升级**

这种级别的迁移，必须明确执行：

> **新版兼容读取旧数据，旧版禁止继续写入新世界。**
