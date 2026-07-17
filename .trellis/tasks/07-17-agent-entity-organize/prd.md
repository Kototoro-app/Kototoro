# 实体整理增强：AI 辅助合并/追踪/投影建议

## 范围

在现有实体整理工作台（Entity Organize）中增加 AI 辅助审查面板，利用 LLM 帮助判断：
- 实体是否应该合并
- 追踪源绑定是否正确
- 投影是否匹配

## 功能

### 1. 合并审查

```
用户选中疑似重复的实体组 → 点击 "AI 审查"
  → AgentRunner: 调用 find_duplicate_candidates + get_entity_detail(x2)
  → LLM 分析: 标题相似度、作者、类型、标签
  → 返回审查结果:
     "高度疑似真重复: 标题仅差空格/标点，作者相同，类型一致"
     "可能是不同作品: 同名但不同作者，分属不同类型"
  → 用户确认后才执行合并/拆分
```

### 2. 追踪绑定审查

```
用户在追踪绑定预览中查看候选
  → AgentRunner: 调用 get_entity_detail + suggest_tracking_bind
  → LLM 分析: 作品信息 vs 追踪源候选项的匹配度
  → 返回建议: "建议绑定 AniList: Kaiju No.8 (匹配度: 高)"
```

### 3. 投影补全审查

```
用户在投影补全预览中查看候选
  → AgentRunner: 调用 get_entity_detail + suggest_projection
  → LLM 分析: 源列表中的候选项是否真的是同一作品
  → 返回建议: "建议附加 source_id=X 作为可用投影"
```

## UI

- 实体整理工作台增加 "AI 审查" 按钮（在 Stage 1/2/3 操作栏中）
- 审查结果以卡片形式展示在 entity 行下方
- 标注置信度（高/中/低）
- "AI 审查" 可批量选中多行一次性审查

## 安全

- **所有建议须用户确认才执行**
- AI 审查结果作为额外列展示，不影响现有工作流
- 单次审查最多 10 个实体（避免 token 膨胀）

## 依赖

- `agent-framework`（AgentRunner）
- `agent-tools`（find_duplicate_candidates, get_entity_detail, suggest_tracking_bind, suggest_projection）
- 现有实体整理工作台 UI

## 验收标准

- [ ] 选中 2 个已知重复实体，"AI 审查" 正确识别为"真重复"
- [ ] 选中 2 个已知不同实体，"AI 审查" 正确识别为"不同作品"
- [ ] 追踪绑定审查正确匹配已知绑定
- [ ] 审查结果置信度标注合理
- [ ] 批量审查 10 个实体不超时、不 crash
- [ ] 审查建议不可直接执行（需用户手动确认）
