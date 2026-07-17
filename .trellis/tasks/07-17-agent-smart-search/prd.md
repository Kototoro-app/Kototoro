# 智能搜索增强：自然语言搜索入口

## 范围

在现有搜索框里集成 AI Agent 搜索能力，用户可用自然语言描述需求，Agent 调用 search_works 工具组合查询。

## 入口

现有搜索框（`SearchViewModel` + `SearchActivity`），不做新入口。

## 行为

```
用户输入
  │
  ├─ 短查询 (< 5 字 且 无特殊语义词)
  │    → 现有关键词搜索（不变）
  │
  └─ 长查询 / 包含 AI 触发词
       → AgentRunner.run(query)
       → LLM 调用 search_works + get_entity_detail
       → LLM 格式化结果
       → UI 展示（标记为 "AI 搜索"）
```

### AI 触发词（任意一个命中即触发 AI 搜索）
- "类似" "推荐" "有没有" "帮我找" "想看" "有没有什么"
- 句子长度 > 10 字
- 包含多条件（"完结的热血后宫番"）

## UI

- AI 搜索结果与普通搜索结果**同一列表展示**
- AI 结果条目右上角有 "AI" 标签区分
- 搜索过程中显示 AI 思考动画（三点闪烁）
- AI 搜索超时 8s 后降级为普通搜索

## 依赖

- `agent-framework`（AgentRunner）
- `agent-tools`（search_works, get_entity_detail）
- `SearchViewModel`
- `SearchResultsScreen`

## 验收标准

- [ ] "找类似鬼灭之刃的热血番" 触发 AI 搜索并返回相关结果
- [ ] "naruto" 不触发 AI 搜索，走普通关键词
- [ ] AI 搜索超时后自动降级
- [ ] AI 搜索结果与普通结果 UI 可区分
- [ ] 错误时用户看到友好提示而非 crash
