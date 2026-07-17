# Design: Kototoro AI Agent 系统

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      UI Layer                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ AI 搜索增强   │  │ 实体整理建议  │  │ 智能推荐卡片     │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
│         │                 │                    │            │
├─────────┼─────────────────┼────────────────────┼────────────┤
│         ▼                 ▼                    ▼            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                Agent Layer (new)                     │   │
│  │  ┌────────────┐  ┌──────────────┐  ┌─────────────┐  │   │
│  │  │ AgentRunner │  │ ToolRegistry │  │ LlmClient   │  │   │
│  │  │ run(goal)   │  │ register(T)  │  │ chat(...)   │  │   │
│  │  │ → Response  │  │ invoke(name) │  │              │  │   │
│  │  └─────┬──────┘  └──────┬───────┘  └──────┬──────┘  │   │
│  │        │                │                  │         │   │
│  │        └────────────────┼──────────────────┘         │   │
│  │                         │                            │   │
│  │  ┌──────────────────────┴───────────────────────┐   │   │
│  │  │          AgentOrchestrator                     │   │   │
│  │  │  system_prompt → tool_defs → chat →           │   │   │
│  │  │  parse tool_calls → execute → observe →       │   │   │
│  │  │  rechat → final_answer                        │   │   │
│  │  └──────────────────────────────────────────────┘   │   │
│  └──────────────────────────┬───────────────────────────┘   │
│                             │ calls                          │
├─────────────────────────────┼───────────────────────────────┤
│                             ▼                                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │          Existing Domain Layer                       │   │
│  │  EntityGraphRepo / SearchV2Helper / ExploreRepo /    │   │
│  │  FavouritesRepo / TrackingService / SpaceRepo ...    │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 1. LlmClient — 通用 LLM 调用层

### 复用策略

从 `ReaderTranslationCoordinator` 提取通用组件，不做重复实现：

| 现有组件 | 位置 | 复用方式 |
|---------|------|---------|
| `TranslationApiProviderCatalog` | `reader/translate/domain/` | **直接复用** — 8 个提供商预设 + `applyAuthentication` |
| `isOpenAiCompatibleChatCompletionsEndpoint` | `ReaderTranslationCoordinator` | 提取为 util |
| OpenAI 格式 JSON 构建 | `ReaderTranslationCoordinator.requestOpenAiBatch` | 提取通用 `buildChatRequest()` |
| `isDeepSeekEndpoint` + `thinking: disabled` | `ReaderTranslationCoordinator` | 提取为 util |
| OkHttp 配置 | 构造注入的 `OkHttpClient` | 通过 DI 复用同实例 |

### 新增接口

```kotlin
// agent/domain/LlmClient.kt
interface LlmClient {
    suspend fun chat(request: ChatRequest): ChatResponse
}

data class ChatRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val toolChoice: ToolChoice? = null,  // "auto" | "none" | specific
    val temperature: Float = 0.3f,
    val maxTokens: Int? = null,
)

data class ChatMessage(
    val role: String,  // "system" | "user" | "assistant" | "tool"
    val content: String?,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
)

data class ChatResponse(
    val message: ChatMessage,
    val finishReason: String?,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonSchema,  // JSON Schema 格式
)
```

### 实现

```kotlin
// agent/data/DefaultLlmClient.kt
class DefaultLlmClient(
    private val providerCatalog: TranslationApiProviderCatalog,
    private val okHttpClient: OkHttpClient,
    private val settings: AppSettings,  // 读取 AI 配置
) : LlmClient {
    // 从 AppSettings 读取当前选择的提供商、API key、model
    // 构建 OpenAI 兼容 JSON payload
    // 通过 OkHttp 发送 POST 到 chatEndpoint
    // 解析 response JSON → ChatResponse
}
```

---

## 2. Tool 系统

### 接口设计

```kotlin
// agent/domain/Tool.kt
interface Tool {
    val definition: ToolDefinition
    suspend fun invoke(args: Map<String, JsonElement>): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val data: String,       // JSON string, 给 LLM 读
    val error: String? = null,
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>,
)
```

### ToolRegistry

```kotlin
// agent/domain/ToolRegistry.kt
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) { tools[tool.definition.name] = tool }
    fun get(name: String): Tool? = tools[name]
    fun listDefinitions(): List<ToolDefinition> = tools.values.map { it.definition }
}
```

### Tool 实现清单

| Tool | 封装 | 参数 |
|------|------|------|
| `search_works` | `SearchV2Helper` | query, sources?, category?, content_type?, limit? |
| `get_entity_detail` | `EntityGraphRepository` | entity_id |
| `find_duplicate_candidates` | `EntityBindingMatcher` | entity_id?, content_type?, limit? |
| `suggest_merge` | Agent 逻辑（非 Tool） | entity_ids[] |
| `suggest_tracking_bind` | `TrackingSiteDiscoveryService` | entity_id |
| `suggest_projection` | `EntityGraphSourceAdapter` | entity_id, sources[] |
| `get_recommendations` | `ExploreRepository` | entity_id?, category?, limit? |
| `list_favorites` | `FavouritesRepository` | category?, sort?, offset?, limit? |

### Tool 注册（DI Module）

```kotlin
// agent/AgentModule.kt
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {
    @Provides
    fun provideToolRegistry(
        searchTool: SearchWorksTool,
        entityDetailTool: GetEntityDetailTool,
        duplicateTool: FindDuplicateCandidatesTool,
        // ... 其余 Tool
    ): ToolRegistry = ToolRegistry().apply {
        register(searchTool)
        register(entityDetailTool)
        register(duplicateTool)
        // ...
    }
}
```

---

## 3. AgentRunner — Agent 执行引擎

### 流程

```
用户输入 goal
  │
  ▼
AgentRunner.run(goal, tools)
  │
  ├─ 1. 构建 system_prompt（包含 Tool 定义列表）
  ├─ 2. LlmClient.chat(messages=[system, user(goal)], tools=defs)
  │     │
  │     ├─ LLM 返回 tool_calls → 进入步骤 3
  │     └─ LLM 返回纯文本    → 步骤 5（结束）
  │
  ├─ 3. 解析 tool_calls，逐个执行 Tool.invoke(args)
  │     │
  │     └─ 结果作为 tool role message 追加到 messages
  │
  ├─ 4. 回到步骤 2（最多 N 轮，默认 5 轮）
  │
  └─ 5. 返回最终 ChatResponse
```

### 核心类

```kotlin
// agent/domain/AgentRunner.kt
class AgentRunner(
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry,
    private val maxRounds: Int = 5,
) {
    suspend fun run(
        goal: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    ): AgentResult

    // 内部维护 messages 列表
    // 每轮 check 是否有 tool_calls
    // 工具错误时向 LLM 报告错误信息，让其重试
}
```

### System Prompt 模板

```
你是一个 Kototoro 阅读/追番应用的智能助手。
你可以使用工具来搜索作品、查看实体详情、检查重复等。
当用户询问时：
- 如果需要搜索，调用 search_works
- 如果用户提到"这个可能和XX重复"，使用 find_duplicate_candidates 检查
- 每次回答前先确认调用结果再回复
- 工具返回数据用中文解释给用户
```

---

## 4. 与现有模块的集成点

### 设置-AI 读取

```kotlin
// AppSettings 新增（或复用现有 key）
val agentApiProviderPreset: String         // "DEEPSEEK" 等
val agentApiEndpoint: String               // 自定义 endpoint
val agentApiKey: String                    // API key
val agentApiModel: String                  // "deepseek-v4-pro"
val agentEnabled: Boolean                  // 功能开关
```

> **决策**: 是复用现有翻译 API 配置还是新增独立配置？
> **建议**: 独立配置。翻译和 Agent 使用场景不同（翻译需要低成本批量，Agent 需要高推理能力），可能用不同模型。

### 搜索入口集成

```
搜索框输入 → SearchViewModel
  ├─ 短查询（< 5 字或无特殊语义）→ 现有关键词搜索
  └─ 长查询 / 包含"类似""推荐""还有什么"等 → AI Agent 搜索
     └─ AgentRunner.run(query) → search_works → LLM 格式化 → 展示
```

---

## 5. 错误处理策略

| 场景 | 处理 |
|------|------|
| LLM API 超时 (10s) | 返回友好错误 + "请检查网络和 API 配置" |
| LLM 返回非 tool_call 格式 | 尝试解析为纯文本直接返回 |
| Tool 执行异常 | 捕获异常 → tool_result 返回 error → LLM 自行处理 |
| maxRounds 耗尽 | 返回最后一条 assistant message |
| API key 未配置 | 提示用户前往设置-AI 配置 |
| LLM 返回不在注册表里的 tool name | 在下一轮告知 LLM 该工具不可用 |

---

## 6. 安全约束

1. **所有破坏性 Tool 仅返回建议，不实际执行**
   - `suggest_merge` 返回"建议合并实体 A + B → C"，不调用 MergeFavoriteEntitiesUseCase
   - 用户必须在 UI 上确认后才触发实际合并
2. **敏感数据在传给 LLM 前脱敏**
   - 去掉文件路径、本地 ID（改用 UUID）
   - 用户信息不传给 LLM
3. **Prompt 注入防护**
   - 用户输入作为 user role message，不和 system prompt 拼接

---

## 7. 子任务依赖关系

```
agent-framework (先做)
  ├── agent-tools (依赖框架的 Tool 接口 + ToolRegistry)
  │     ├── agent-smart-search (依赖 search_works + get_entity_detail)
  │     └── agent-entity-organize (依赖 find_duplicate + suggest_*)
  └── agent-framework 里的 LlmClient 是所有子任务的底层
```

执行顺序：`agent-framework` → `agent-tools` → `agent-smart-search` 和 `agent-entity-organize` 可并行
