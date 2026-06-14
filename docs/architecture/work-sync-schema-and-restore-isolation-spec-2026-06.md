# Work Sync Schema And Restore Isolation Spec（2026-06）

## 目的

本文档定义 Kototoro 在 Work 化迁移期间的同步协议分代、restore 隔离和 auto-upload 禁写闸门。

本文中的“sync schema”同样只针对当前 backup / restore / WebDAV 这条主链的语义协议，不针对仓库内保留的旧 `sync/` / `kotatsu sync` 实现。

目标是把下面三件事彻底拆开：

1. **传输代际**
2. **数据语义版本**
3. **导入兼容与主语义写入**

如果这三者继续混在一起，旧版 Manga-centric 语义会持续通过：

- backup
- restore
- WebDAV auto restore
- auto sync upload

重新污染 Work-centric 迁移。

## 当前现状

### 已有能力

当前代码里已经存在：

- WebDAV 远端 namespace：
  - `RemoteNamespace.V1`
  - `RemoteNamespace.V2`
- 远端备份递增版本：
  - `backupWebDavDataVersion`
- legacy restore 后阻断 auto upload 的开关：
  - `isBackupWebDavAutoUploadBlockedByLegacyRestore`

相关入口：

- [WebDavBackupUploader.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/ui/periodical/WebDavBackupUploader.kt:24)
- [BackupWebDavUploadCoordinator.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/domain/BackupWebDavUploadCoordinator.kt:1)
- [BackupWebDavRestoreCoordinator.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/domain/BackupWebDavRestoreCoordinator.kt:1)
- [DataSyncManager.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/ui/webdav/DataSyncManager.kt:1)
- [WebDavAutoRestoreService.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/ui/webdav/WebDavAutoRestoreService.kt:120)

### 当前问题

现有 `V1 / V2` 只是：

```text
WebDAV 备份命名空间/写入代际
```

它不是：

```text
Work 化语义协议版本
```

当前 `backupWebDavDataVersion` 表示的是：

```text
远端备份文件序号
```

也不是：

```text
数据语义 schemaVersion
```

因此当前系统还不具备：

- old read / new write 的显式协议隔离
- restore 导入降级
- Work 主语义防旧版写回

## 核心术语

### 1. Transport Generation

表示远端备份文件的传输代际或命名空间代际。

例如：

- `RemoteNamespace.V1`
- `RemoteNamespace.V2`

它回答的是：

> 远端文件怎么命名、怎么分目录、怎么识别。

它**不等于**业务数据的语义版本。

### 2. Semantic Schema Version

表示同步数据的领域语义版本。

建议定义：

- `1`：Manga-centric legacy model
- `2`：Work migration model

它回答的是：

> 这个备份/同步数据遵循哪套领域模型。

### 3. Import-Compatible Restore

表示：

- 可以读取旧结构
- 可以导入旧内容
- 但不能把旧结构直接提升为新世界主真相

### 4. Authoritative Sync Write

表示：

- 对当前主语义模型的正式写入
- 会参与后续自动同步、跨设备传播和主流程决策

## 设计原则

### 原则 1：传输代际和语义版本必须分离

不要把：

- `RemoteNamespace.V2`
- `backupWebDavDataVersion`

误当成 Work migration 的语义协议版本。

建议新增：

```kotlin
data class SyncEnvelope(
    val transportGeneration: Int,
    val semanticSchemaVersion: Int,
    val dataVersion: Int,
    val exportedAt: Long,
)
```

建议映射：

- `transportGeneration`
  - 1 = legacy WebDAV naming
  - 2 = current WebDAV naming
- `semanticSchemaVersion`
  - 1 = Manga-centric
  - 2 = Work-centric

### 原则 2：兼容读取不等于允许写回

新版可以：

- 读取旧 backup
- 导入旧数据

但旧数据一旦被导入，不应继续保持对 Work 主语义的写回资格。

### 原则 3：restore 是 import，不是直接主写

特别是在 Work 化迁移期间，restore 应视为：

```text
import -> normalize -> degrade legacy semantics -> commit
```

而不是：

```text
raw restore -> immediate authoritative writeback
```

### 原则 4：旧协议客户端不能继续写新协议核心

当客户端只理解 Manga-centric 模型时，它不能继续向 Work-centric namespace 做 authoritative upload。

## 目标架构

### 同步元数据

建议每个远端 backup / sync payload 带以下元信息：

```json
{
  "transport_generation": 2,
  "semantic_schema_version": 2,
  "data_version": 143,
  "app_version": 20260612
}
```

### 协议分层

#### 传输层

负责：

- 文件命名
- 远端目录/namespace
- 列表/下载/上传

#### 语义层

负责：

- Work / Projection ownership
- binding 结构
- metadata default / override 语义
- tracking binding 语义

#### 导入层

负责：

- 从低版本语义读入
- 映射到当前模型
- 标记 legacy 数据
- 阻止旧语义直接回流

## 同步行为规范

### 新版客户端

当客户端支持 Work-centric 模型时：

1. 可以读取 `semantic_schema_version = 1`
2. 可以导入 legacy 数据
3. 导入后只允许写 `semantic_schema_version = 2`
4. 不回写 `schemaVersion = 1`

### 旧版客户端

当客户端只支持 Manga-centric 模型时：

若检测到：

```text
remote semanticSchemaVersion > supportedSchemaVersion
```

应：

1. 禁止上传
2. 只读或提示升级
3. 不执行任何自动修复回写

## Restore 行为规范

### 分类

restore 期间必须区分两类 section：

#### A. 可直接 authoritative restore 的 section

仅限语义稳定、不会破坏 Work ownership 的数据。

例如：

- settings
- source enablement
- extension repos
- auth（视风险控制）

#### B. 只能 import-compatible restore 的 section

这些 section 在 Work 化期间不能直接恢复为新模型主真相。

包括：

- favourites
- history
- tracking-related state
- `manga prefs.metadata_source_*`
- `entity_preferences`
- `entity_binding`
- `tracking_site_links`

### 导入降级规则

#### favourites

旧 favorites 恢复后：

- 视为 library import source
- 不直接认定为最终 Work ownership 完成态
- 后续必须经过 Work anchor 归一化

#### history

旧 history 恢复后：

- 视为 projection-bound reading trace
- 不直接认定为最终 Work reading state
- 后续需要建立 Work history anchor

#### `manga prefs.metadata_source_*`

恢复后：

- 只作为 projection-local override 候选
- 不得自动提升为 Work metadata default

补充当前实现约束：

- legacy `ContentBackup` embedded prefs 已不再直接重建 projection prefs；
- 导入阶段只保留 content snapshot，本地 authoritative owner state
  仍必须来自 `ENTITY_GRAPH_PREFS` / `WORK_*` 或后续 normalize。

#### `entity_preferences`

恢复后：

- 可作为 Work preference 候选导入
- 但若 payload 的 `semanticSchemaVersion = 1`，必须视为 legacy imported semantics

#### `entity_binding`

恢复后：

- 可导入
- 但 legacy / sync 恢复数据不得自动触发大范围 metadata propagation

#### `tracking_site_links`

恢复后：

- 只能作为 cache/audit 导入
- 不得成为 authoritative work-tracking binding

## Auto Restore 与 Auto Upload 闸门

### 问题

当前 [WebDavAutoRestoreService.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/ui/webdav/WebDavAutoRestoreService.kt:120)
在非 legacy restore 场景下，restore 后可能自动比较并再次 upload。

在 Work 化迁移期间，这存在高风险：

- 旧语义导入后还没完成 normalize
- restore 结果就被当作最新 authoritative 数据上传
- 造成新污染立刻扩散到远端

### 新规则

引入新闸门概念：

- `isWorkMigrationSyncWriteBlocked`
- `lastImportedSemanticSchemaVersion`
- `lastAuthoritativeSemanticSchemaVersion`

### 闸门策略

#### 情况 1：restore 的 payload 是 legacy schema

则：

- restore 后禁止 auto upload
- 直到本地完成 normalize / ownership migration / metadata cleanup

#### 情况 2：restore 的 payload 是 current Work schema

则：

- 可允许后续 auto upload
- 但仍要校验 transport generation 和 writer generation

### 最低要求

至少需要：

1. legacy restore 后阻断 auto upload
2. legacy restore 后标记本地为“导入未归一化”
3. 只有归一化完成后才允许重新进入 authoritative upload

## 建议新增状态字段

建议在 settings 或本地 sync metadata 中新增：

- `syncSemanticSchemaVersionSupported`
- `lastImportedSemanticSchemaVersion`
- `lastAuthoritativeSemanticSchemaVersion`
- `isWorkMigrationSyncWriteBlocked`
- `requiresWorkMigrationNormalization`

它们用于：

- 判定是否可上传
- 判定 restore 后是否需要归一化
- 判定旧数据是否仍在污染风险窗口中

## 实施阶段

### Phase 1：只加元信息，不改主流程

动作：

1. 为 backup index / envelope 增加：
   - `semanticSchemaVersion`
   - `transportGeneration`
2. 记录本地支持版本
3. restore 时解析版本

目标：

- 先让系统知道自己面对的是哪种语义数据。

### Phase 2：restore 导入降级

动作：

1. 把 legacy restore 结果标记为 imported-not-authoritative
2. `tracking_site_links`、`manga prefs.metadata_source_*`、legacy entity prefs 等按降级规则导入
3. 阻断 restore 后自动 authoritative upload

### Phase 3：旧版禁写新协议

动作：

1. 当 remote `semanticSchemaVersion > supported` 时：
   - 只读
   - 禁上传
   - 提示升级
2. 停止任何 old -> new writeback

### Phase 4：切换 Work-centric namespace

动作：

1. 确立 Work-centric 的正式语义版本
2. 迁移后只写新 schema
3. 旧 namespace 只保留导入用途

## 对现有代码的首批改造建议

### 1. `BackupIndex`

先扩展为可携带：

- `semanticSchemaVersion`
- `transportGeneration`

这是最低成本切入点。

### 2. `BackupRepository.restoreBackup(...)`

增加：

- restore semantic context
- section-level import policy

让 restore 知道自己在恢复：

- authoritative current snapshot
还是
- legacy import payload

### 3. `BackupWebDavRestoreCoordinator`

增加：

- 记录 restore 的 semantic schema
- 若为 legacy，设置：
  - `isWorkMigrationSyncWriteBlocked = true`
  - `requiresWorkMigrationNormalization = true`

### 4. `BackupFlowPolicy.autoSyncUploadDecision()`

增加闸门：

- 若 `isWorkMigrationSyncWriteBlocked = true`
  - 禁止 auto upload

### 5. `WebDavAutoRestoreService`

改造 restore 后逻辑：

- legacy restore 不参与 post-restore upload
- current Work schema restore 才允许比较后上传

## 验收标准

完成本规范第一轮落地后，应满足：

1. 系统能区分 transport generation 与 semantic schema version；
2. legacy restore 后不会自动 authoritative upload；
3. 旧语义 section 导入后被明确降级，而不是直接成为 Work 主真相；
4. 旧客户端无法继续写入新语义核心；
5. 新版可读旧数据，但不会把旧数据原样回写成 authoritative current snapshot。

## 一句话结论

在 Work 化迁移期间，必须把下面三件事彻底分开：

> **怎么传、传的是哪一代语义、导入后是否有资格继续作为主真相写回。**
