# 工具集：搜索/实体/推荐 8 个 Tool 实现

## 范围

基于 `agent-framework` 的 Tool 接口，实现面向 Kototoro 业务域的 8 个工具。

## 依赖

- `agent-framework`（Tool 接口 + ToolRegistry）
- 现有 Repository/UseCase（不直接访问 DAO）

## Tool 清单

### 1. search_works
- **封装**: `SearchV2Helper`
- **参数**: query (String), sources? (List<Long>), category? (String), content_type? (String), limit? (Int, default 5)
- **返回**: JSON 数组 `[{id, title, author, source, type, cover_url, score}]`
- **数据量控制**: limit 默认 5，最多 10

### 2. get_entity_detail
- **封装**: `EntityGraphRepository`
- **参数**: entity_id (Long)
- **返回**: JSON 对象含 title, alt_titles, authors, tags, sources 列表, tracking_bindings 列表, progress
- **脱敏**: 不返回 local_file_path

### 3. find_duplicate_candidates
- **封装**: `EntityBindingMatcher` + `TitleSimilarity`
- **参数**: entity_id? (Long), content_type? (String), limit? (Int, default 5)
- **返回**: JSON 数组 `[{entity_a: {id, title}, entity_b: {id, title}, similarity_score, reason}]`
- **逻辑**: 如果指定 entity_id，找跟它重复的；否则返回全局 Top N 高疑似重复

### 4. suggest_merge
- **参数**: entity_ids (List<Long>)
- **返回**: 建议 JSON `{suggested: [{keep: {id, title}, absorb: [{id, title}]}], reasoning: String}`
- **注意**: 仅返回建议，不执行合并

### 5. suggest_tracking_bind
- **封装**: `TrackingSiteDiscoveryService`
- **参数**: entity_id (Long)
- **返回**: 搜索追踪源候选项 `[{site, title, url, match_score, reason}]`

### 6. suggest_projection
- **封装**: `EntityGraphSourceAdapter`
- **参数**: entity_id (Long), sources? (List<Long>)
- **返回**: 可用 source entry 候选 `[{source_id, source_name, title, cover_url, relvance}]`

### 7. get_recommendations
- **封装**: `ExploreRepository`
- **参数**: entity_id? (Long), category? (String), limit? (Int, default 5)
- **返回**: 推荐列表 `[{id, title, author, type, reason}]`
- **注意**: 如果指定 entity_id，基于同类作品推荐

### 8. list_favorites
- **封装**: `FavouritesRepository`
- **参数**: category? (Long), sort? (String: "recent"|"title"|"progress"), offset? (Int), limit? (Int, default 10)
- **返回**: 收藏列表 `[{entity_id, title, author, category, progress, last_read}]`

## 验收标准

- [ ] 每个 Tool 能正常调用并返回符合格式的 JSON
- [ ] Tool 异常时返回 ToolResult(success=false, error=...)
- [ ] 所有 Tool 在 DI 中正确注册
- [ ] 单元测试覆盖每个 Tool 的 success/error 路径
