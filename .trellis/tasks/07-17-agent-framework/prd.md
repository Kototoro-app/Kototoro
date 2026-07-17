# Agent 框架层：通用 LLM 调用 + Tool 系统 + AgentRunner

## 范围

从现有 `ReaderTranslationCoordinator` 提取通用 LLM 调用能力，构建独立的 Agent 框架层，作为后续所有 AI 功能的基础。

## 产物

### 1. LlmClient — 通用 LLM 调用接口

- 抽象 `LlmClient` 接口（OpenAI 兼容 chat completions 格式）
- 实现 `DefaultLlmClient`，复用 `TranslationApiProviderCatalog` 的 8 个提供商
- 支持 tool calling（tools 参数、tool_choice）
- 从设置-AI 读取独立配置（agentApiProvider/Endpoint/Key/Model）
- DeepSeek 特殊处理：`thinking: { type: "disabled" }`

### 2. Tool 接口 + ToolRegistry

- `Tool` 接口：`definition: ToolDefinition` + `invoke(args): ToolResult`
- `ToolDefinition`：name, description, JSON Schema parameters
- `ToolResult`：success, data (JSON string), error
- `ToolRegistry`：register / get / listDefinitions

### 3. AgentRunner

- 输入 goal → 构建 system prompt + tool definitions → LlmClient.chat()
- 解析 tool_calls → Tool.invoke() → 追加 tool result message → 继续对话
- 最大轮数控制（默认 5 轮）
- 错误处理：工具异常报告给 LLM、API 超时优雅降级
- 返回 AgentResult（最终消息 + 工具调用历史）

### 4. DI 集成

- Hilt Module（SingletonComponent）
- 提供 LlmClient, ToolRegistry, AgentRunner

## 依赖

- `TranslationApiProviderCatalog`（直接复用）
- `AppSettings`（读取 AI 配置，需新增 agent 相关 key）
- `OkHttpClient`（DI 注入）

## 不依赖

- 不需要新增数据库表
- 不需要修改现有 UI

## 验收标准

- [ ] DefaultLlmClient 能正常调用 DeepSeek API 并返回结果
- [ ] ToolRegistry 能注册/查找 Tool
- [ ] AgentRunner 能完成单轮 tool calling（查询 → 执行 → 返回）
- [ ] AgentRunner 能处理 LLM 返回 invalid tool name 不 crash
- [ ] maxRounds 耗尽时返回最后一条消息
- [ ] 切换到不同提供商后正常切换（OpenAI/Zhipu 等）
- [ ] 单元测试覆盖 AgentRunner 的核心流程（mock LlmClient）
