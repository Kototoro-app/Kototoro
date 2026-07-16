# 执行计划：跨内容类型 Work 身份隔离与详情投影过滤

## 前置审阅

- [x] 审阅 `prd.md` 的范围和验收标准。
- [x] 审阅 `design.md` 的迁移拆分策略、详情页过滤策略和状态归属风险。
- [x] 确认任务仍只修改本地代码、测试和架构文档，不执行 Git commit/push。
- [x] `task.py start` 前重新读取 `trellis-before-dev` 相关规范和本任务 artifacts。

## 实施顺序

### 1. 建立测试 fixture 与失败基线

- [x] 增加同名 MANGA/VIDEO projection fixture。
- [x] 覆盖一个 entity 同时绑定漫画和视频的污染数据库 fixture。
- [x] 运行实体匹配、候选和详情过滤测试，记录基线。

### 2. Entity schema 与 Room migration

- [x] 增加 `EntityRecord.contentType` 和 `Entity.contentType`。
- [x] 更新 index、mapping、DAO 查询、显式 dump 查询。
- [x] 实现 Migration 74 → 75：添加字段、回填单类型 projection，混合/未知类型保留 null 并交由 repair 诊断，重建唯一索引。
- [x] 更新数据库版本、migration 注册和 schema tests。

风险点：已有 entity 可能包含多种类型，禁止用单一“众数/首个类型”覆盖整行。

### 3. Resolver、Matcher 和创建路径

- [x] 让 `pickCandidate`、probe、matcher 和候选查询感知 `contentType`。
- [x] 将 `contentType` 贯穿 `resolveOrCreateEntity`、`createEntity`、批量入口、detached/reset/restore/sync 创建路径。
- [x] 更新 unique conflict fallback 和 `updateEntityResolvingNameHashConflict`。
- [x] 为 Anime Offline/MAL-Sync 命中增加目标类型守卫。
- [x] 为 `mergeEntities` / `mergeLocalWorkEntities` 增加内容类型冲突拒绝和诊断信息。

### 4. 详情页运行时隔离

- [x] 从当前请求 projection 解析有效内容类型。
- [x] 将可选 Space 类型约束传入详情数据层，并抽取不依赖 UI 的 `DetailsProjectionFilter`。
- [x] 在 `buildActiveLocalSourceOptions` 建立单一过滤点。
- [x] 确保 `readingSourceOptions`、`readingChapterTabs`、`EntityChapterSourceInfo` 只消费过滤后的集合。
- [x] 验证无 Space 上下文和 Space 类型约束的过滤函数行为。

### 5. 历史污染诊断与 repair

- [x] 扩展现有 repair report 检测同一 entity 下不同 local content types，并在实体整理页顶部仅按需显示修复卡片。
- [x] 复用 split/rebind 流程生成 sibling entities。
- [x] 为 null/未知类型、tracking binding 和用户状态归属不确定的情况保留 review。
- [x] 沿用现有 repair ledger/provenance，并让修复后重新诊断以隐藏已解决卡片。
- [x] 拆分后协调投影 `sync_id` 前检查旧实体占用，冲突时保留新实体已有唯一 ID，避免 repair 触发唯一约束崩溃。

### 6. Backup / Restore / Sync

- [x] 更新 `SyncEntityRecord`、导出、导入、sync merger 和 restore insert/upsert。
- [x] 旧 payload 缺少字段时导入 null，类型冲突时隔离 `sync_id`，不触发标题自动合并。
- [x] 增加恢复冲突隔离测试；完整 round-trip 仍依赖 AndroidTest 基线恢复。

### 7. 文档和回归验证

- [x] 保持 `docs/architecture/entity-content-type-merge-bug-analysis-2026-07.md` 与最终实现一致。
- [x] 更新任务文档中的已验证约束。
- [x] 执行完整 JVM 单测、Debug 编译、差异检查并核对未改动用户无关文件。
- [x] 覆盖 repair 拆分中投影 `sync_id` 与旧实体冲突时的 ID 保留策略。
- [ ] AndroidTest 设备/编译质量门禁：被仓库现有旧 API 测试阻塞。

## 验证命令

基础编译和单测：

```bash
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew :app:compileDebugKotlin --no-daemon
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew :app:testDebugUnitTest --no-daemon
```

目标测试（实现后按实际测试类名调整）：

```bash
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew :app:testDebugUnitTest \
  --tests "org.skepsun.kototoro.entitygraph.*" \
  --tests "org.skepsun.kototoro.work.*" \
  --no-daemon
```

数据库迁移和详情数据流测试：

```bash
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew :app:connectedDebugAndroidTest \
  --tests "org.skepsun.kototoro.core.db.MangaDatabaseTest" \
  --no-daemon
```

## 检查点与回滚点

1. Schema 变更前：保留 migration fixture 和旧 schema 校验结果。
2. Resolver 变更后：同名不同类型必须产生不同 entity；同类型多来源行为不回退。
3. 详情过滤后：污染 fixture 不再显示跨类型 source/chapter tab。
4. Repair 完成后：核对 entity、binding、history/favourite/stats/track 的归属和 ledger。
5. Sync 完成后：执行旧 payload、当前 payload、冲突 payload round-trip。

任何一步失败时，优先回滚当前子步骤的代码变更并保留诊断 fixture；不得使用 `git reset --hard` 或删除用户数据。

## 完成门槛

- 所有 PRD acceptance criteria 有对应测试或可审计验证结果。
- 详情页安全网、身份边界和历史 repair 三者均完成，不能只交付其中一层。
- `trellis-check` 质量检查通过后，才进入 Trellis finish/commit 流程。
