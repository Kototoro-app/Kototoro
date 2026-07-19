# 技术设计：Issues 404、405、407、408、409、411

## 边界

父任务只维护 issue 范围、执行顺序和跨任务回归；实际代码改动分别归属六个子任务。优先复用现有 `DiscoverHeroCarousel`、`InterfaceStyleTokens`/主题设置、`RootGlassMenuHost`、详情 source 统一过滤点、EntityGraph projection 迁移和现有图片/Backdrop 管线。

## 依赖与顺序

- #404、#405 为相对独立的 UI/设置改动。
- #407 复用已有根层 Backdrop 菜单基础设施，完成后作为后续玻璃交互回归基线。
- #408/#409 依赖当前内容类型隔离和 Migration 74 → 75 的现状检查；如已有任务尚未完成，先在对应子任务中补齐而不是创建第二套模型。
- #411 最后处理，使用前五项改动后的完整渲染路径和内存行为作为基线。

## 数据流约束

```text
设置/窗口状态 → UI 视觉与手势
详情请求/缓存 → source presentation
EntityGraph/收藏查询 → 完整 projections
图片与 Backdrop 生命周期 → Android 渲染内存
```

每条数据流保留单一归属点：渐变只由轮播容器负责，内容类型面板只由顶栏过滤器负责，reading source 与 projection 列表不得在 UI 层各自拼接。

## 兼容与回滚

- 主题和 Backdrop 不可用时沿用现有静态半透明 fallback。
- 详情和实体数据修复必须可在旧数据库数据上安全运行，未知类型或不完整归属不得静默猜测。
- 若 #411 的设备复现无法稳定获得，先落地已证实的资源上限/生命周期修复，并将无法证明的推测保留为验证记录，不扩大改动范围。
