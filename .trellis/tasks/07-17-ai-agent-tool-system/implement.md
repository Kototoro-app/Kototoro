# Implement: Kototoro AI Agent 系统

## 执行顺序

```
Phase 1a: agent-framework (先做)
  → Task: 07-17-agent-framework
  → 产物: LlmClient + Tool 接口 + ToolRegistry + AgentRunner

Phase 1b: agent-tools (依赖 1a)
  → Task: 07-17-agent-tools
  → 产物: 8 个 Tool 实现 + DI 注册

Phase 1c: agent-smart-search + agent-entity-organize (可并行)
  → Task: 07-17-agent-smart-search
  → 产物: 搜索框增强 + AI 搜索结果展示
  → Task: 07-17-agent-entity-organize
  → 产物: 实体整理工作台 AI 建议面板
```

## 文件变更清单

### Phase 1a: agent-framework

```
新增:
  app/src/main/kotlin/org/skepsun/kototoro/agent/
    domain/
      LlmClient.kt            # 接口
      ChatModels.kt           # ChatRequest/ChatResponse/ChatMessage/ToolDefinition 等
      Tool.kt                 # Tool 接口 + ToolResult
      ToolRegistry.kt         # 注册/查找/列出 Tool
      AgentRunner.kt          # Agent 执行引擎
    data/
      DefaultLlmClient.kt     # OpenAI 兼容格式实现
    AgentModule.kt            # Hilt DI Module

提取/重构:
  reader/translate/domain/
    TranslationApiProviderCatalog.kt  # 不变，直接复用
    ReaderTranslationCoordinator.kt   # 提取 isOpenAiCompatible 等 util 到 core
```

### Phase 1b: agent-tools

```
新增:
  app/src/main/kotlin/org/skepsun/kototoro/agent/tools/
    SearchWorksTool.kt
    GetEntityDetailTool.kt
    FindDuplicateCandidatesTool.kt
    SuggestMergeTool.kt
    SuggestTrackingBindTool.kt
    SuggestProjectionTool.kt
    GetRecommendationsTool.kt
    ListFavoritesTool.kt
```

### Phase 1c: agent-smart-search

```
修改:
  search/ui/multi/SearchViewModel.kt    # 增加 AI 搜索分支
  search/ui/compose/SearchResultsScreen.kt  # AI 结果展示
```

### Phase 1c: agent-entity-organize

```
修改:
  entitygraph/ui/...  # 实体整理工作台增加 AI 审核面板
新增:
  agent/tools/  # suggest_merge / suggest_tracking / suggest_projection
```

## 验证命令

```bash
# 编译检查
./gradlew assembleDebug

# 单元测试
./gradlew testDebugUnitTest --tests "*agent*"

# Lint
./gradlew lintDebug
```

## 回滚点

- 每个 Phase 完成后 commit
- 如果 Agent 框架与系统不稳定，可整体 revert 不影响翻译功能
