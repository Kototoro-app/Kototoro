# 隔离跨内容类型 Work 投影并修复详情页泄漏

## Goal

修复同名但不同 `ContentType` 的作品被绑定到同一 Work，以及由此导致的漫画投影出现在动画详情播放源和章节面板中的问题。

用户应能在视频 Space 中打开“庙不可言”的动画版本，而不会看到此前漫画版本的阅读源、漫画章节或共享错误的 Work 用户状态。

## Background and confirmed facts

- `entity` 当前没有 `content_type`，唯一索引是 `(type, name_hash)`：[EntityGraphEntities.kt:20-40](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphEntities.kt:20)。
- `resolveOrCreateEntity` 接收 `contentType`，但传给 `pickCandidate` 时丢失：[EntityGraphRepository.kt:2186-2257](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:2186)。
- 同名 Work 会得到名称匹配的高置信度并自动绑定：[DefaultEntityBindingMatcher.kt:23-38](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/DefaultEntityBindingMatcher.kt:23)。
- `createEntity` 的唯一索引 fallback 也只按 `(type, name_hash)` 查找并合并：[EntityGraphRepository.kt:2530-2573](../../app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt:2530)。
- 详情页从实体获取全部 local bindings，未按内容类型过滤：[DetailsViewModel.kt:1463-1516](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:1463)、[DetailsViewModel.kt:4091-4115](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:4091)。
- 过滤后的 source options 会直接生成章节 source tabs：[DetailsViewModel.kt:2523-2537](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:2523)、[DetailsViewModel.kt:2567-2608](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt:2567)。
- 当前数据库版本为 74，尚无对应迁移：[MangaDatabase.kt:150](../../app/src/main/kotlin/org/skepsun/kototoro/core/db/MangaDatabase.kt:150)。

完整证据和复现链路记录在：[entity-content-type-merge-bug-analysis-2026-07.md](../../../docs/architecture/entity-content-type-merge-bug-analysis-2026-07.md)。

## Requirements

### R1. 建立内容类型感知的 Work 身份边界

- `EntityRecord`、`Entity`、DAO、映射和唯一索引支持 nullable `content_type`。
- 同名不同内容类型的 Work 不得共享 `entity_id`。
- 所有实体创建和唯一冲突 fallback 路径都必须传递并使用内容类型，包括批量入口。

### R2. 阻止跨类型自动匹配和手动合并

- `pickCandidate`、`DefaultEntityBindingMatcher`、Anime Offline/MAL-Sync 映射结果都必须遵守内容类型边界。
- 内容类型明确且不一致时不得绑定。
- 一方内容类型未知时不得仅凭标题自动升级为 `AUTO_BIND`。
- `mergeEntities` 和 `mergeLocalWorkEntities` 拒绝内容类型冲突。

### R3. 修复详情页投影泄漏

- 详情页应按当前请求投影的 `ContentType` 过滤 local source options。
- 播放源/阅读源选项、章节来源 Tab 必须来自同一过滤集合。
- 有 Space 上下文时，还必须满足 Space 的 `allowedContentTypes`；无 Space 上下文时也必须完成当前投影类型过滤。
- 过滤后不得把其他类型投影作为 fallback 重新加入。

### R4. 修复已有污染数据

- 诊断同一 Work 下 active local bindings 的内容类型冲突。
- 诊断通过时不显示异常提示；发现问题时，在实体整理页顶部显示诊断卡片和一键修复按钮。
- 能按明确内容类型拆分实体并复用现有 split ledger，迁移 bindings、投影关系和可归属用户状态。
- 类型缺失或归属不明确的数据保持待诊断状态，不得在数据库迁移中按“首个/众数”静默覆盖。

### R5. 保持数据库、备份和同步兼容

- 提供 74 → 75 Room migration，并覆盖 schema 验证。
- 更新 `dumpEntities`、Google Drive sync entity model、restore/import 映射。
- 保留 `sync_id`、binding provenance、repair 操作记录及用户状态。

### R6. 回归测试

- 覆盖同名 MANGA + VIDEO 的隔离、同类型多来源聚合、候选匹配、唯一索引 fallback、批量入口、详情页 source/chapter 过滤、迁移、手动 merge 和 backup/restore。

## Acceptance Criteria

- [x] “庙不可言”漫画和动画在打开后拥有不同的 Work `entity_id`（由类型边界和 matcher/候选回归测试覆盖）。
- [x] 动画详情页的播放源和章节来源不包含漫画投影；漫画详情页同理不包含视频投影（过滤函数和统一 source/chapter 数据流已接入）。
- [x] 同一内容类型的多个来源仍可正常作为同一 Work 的投影聚合。
- [x] 同名不同类型不会通过候选匹配、唯一索引 fallback、Anime Offline 或 MAL-Sync 路径自动合并。
- [x] 已污染实体可被诊断；无问题时不显示卡片，有问题时实体整理页顶部显示修复卡片；明确类型的投影可拆分，未知类型保留 review，用户状态和 bindings 有可验证的迁移结果。
- [x] 数据库从版本 74 迁移到 75 的迁移实现和 schema fixture 已加入，备份/恢复和同步模型保留内容类型。
- [ ] 相关 Android 迁移/详情数据流测试：受仓库现有 AndroidTest 编译错误阻塞；JVM 相关单测和 Debug 编译已通过。

## Out of scope

- 不重做整个 Entity Graph 或 tracking provider 的身份协议。
- 不把标题、封面或 tracking cache 改造为新的 authoritative identity 证据。
- 不在本任务中重新设计所有 Space 的产品信息架构；只补齐详情页投影隔离所需的内容类型约束。

## Planning status

- 文档根因与复现链路已通过代码检查确认。
- 实体身份隔离、详情页安全网、混合实体诊断/一键拆分、迁移和同步兼容已实现。
- JVM 全量单测通过；AndroidTest 编译仍被仓库现有旧 API 测试阻塞，需后续单独清理测试基线后执行迁移设备测试。
