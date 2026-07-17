# PRD: Kototoro AI Agent — 智能搜索/实体整理/推荐

## 概述

在 Kototoro 现有 LLM API 基础设施（翻译模块已验证）之上，构建 AI Agent 层，让大模型通过结构化工具调用（Native Tool Calling）来增强搜索、辅助实体整理、生成智能推荐。

## 动机

- 用户搜索时经常需要跨源、跨分类组合查询，现有关键词搜索不够灵活
- 实体整理（合并/拆分/追踪绑定/投影补全）是手动操作，判断重复实体需要用户逐一审查
- 现有推荐系统（Explore/RecommendationsItem）缺乏个性化解释能力
- 设置-AI 里已有 LLM API 配置（DeepSeek/OpenAI/Zhipu 等 10+ 提供商），目前仅用于翻译，可复用

## 范围

### Phase 1: 智能搜索 + 实体整理增强

#### 1.1 Agent 框架层
- [ ] 从现有 `ReaderTranslationCoordinator` 提取通用 LLM 调用层
- [ ] 实现 `Tool` 接口和 `ToolRegistry`
- [ ] 实现 `AgentRunner`：chat → parse tool_calls → execute → observe → respond 循环
- [ ] LLM 后端从设置-AI 读取配置，支持所有已预置提供商

#### 1.2 工具集（Tool Registry）
- [ ] `search_works(query, source?, category?, limit?)` — 封装 SearchV2Helper
- [ ] `get_entity_detail(entity_id)` — 返回实体完整信息（标题/作者/标签/sources/绑定/进度）
- [ ] `find_duplicate_candidates(entity_id?, threshold?)` — 复用 EntityBindingMatcher，找疑似重复
- [ ] `suggest_merge(entity_ids)` — 分析候选组，建议合并/保留
- [ ] `suggest_tracking_bind(entity_id, candidate_sites)` — 匹配追踪源候选
- [ ] `suggest_projection(entity_id, sources)` — 为实体推荐可用 source entry
- [ ] `get_recommendations(entity_id?, category?)` — 封装 ExploreRepository
- [ ] `list_favorites(category?, sort?, limit?)` — 列出收藏

#### 1.3 智能搜索
- 用户输入自然语言查询 → LLM 解析意图 → 调用 search_works → 格式化返回
- 支持多步骤搜索（先搜 A 源，没结果再搜 B 源）
- 搜索结果附带 LLM 生成的解释

#### 1.4 实体整理增强
- 实体合并场景：LLM 审查 `find_duplicate_candidates` 返回的候选，标记"高度疑似真重复"/"可能是不同作品"
- 追踪绑定场景：LLM 匹配作品与追踪源搜索候选项
- 投影补全场景：LLM 协助选择最佳 source entry
- **所有变更仅作为建议，用户确认后执行**

### Phase 2: 智能推荐（待定 PRD）
- 基于收藏历史 + 标签偏好 → 个性化推荐
- LLM 解释推荐理由

### Phase 3: 对话式助手面板（待定 PRD）
- 聊天界面，接入全部工具集
- 作为"AI 助手"入口

## 约束

- 复用现有 `ReaderTranslationCoordinator` 的 OkHttp + OpenAI 兼容格式调用链
- Tool 实现必须走现有 Repository/UseCase，不绕过后端直接访问数据库
- Agent 不自动执行破坏性操作（合并/拆分），必须经用户确认
- 搜索结果不缓存 LLM 输出（每次都重新调用以保证时效）

## 非目标

- 不在本地运行 LLM（云端方案已确定）
- 不修改现有实体系统的核心逻辑（如 EntityBindingMatcher 的匹配算法）
- Phase 1 不包含聊天 UI（Phase 3 再做）

## 验收标准

- [ ] 搜索能理解自然语言意图（如"找类似鬼灭的热血番"）
- [ ] 实体整理建议准确率 > 80%（人工抽样 20 组对比）
- [ ] Tool calling 单轮延迟 < 3s（不含 LLM API 耗时）
- [ ] Agent 错误时优雅降级，不 crash
- [ ] 从设置-AI 切换 LLM 提供商后 Agent 正常切换
- [ ] 所有 Tool 通过单元测试
