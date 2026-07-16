# 修复浅色模式 Space FAB 白色八边形

## Goal

移除 Space 切换 FAB 的 Haze 背景，改为普通半透明按钮，并确保透明度不低于 60%，修复浅色模式白色八边形背景伪影。

## Requirements

- 移除 `SpaceSwitcherFab` 对 `GlassSurface`/Haze 背景的依赖。
- 使用圆形 Material3 普通按钮承载 Space 图标和点击行为。
- 按钮容器不透明度不得低于 60%，浅色和深色主题均保持可辨识度。
- 保留现有尺寸、位置、无障碍描述和 Space 切换回调。
- 不修改 Space sheet、rail button 或全局 Haze 行为，不执行提交或推送。

## Acceptance Criteria

- [x] `SpaceSwitcherFab` 不再使用 `GlassSurface`、Haze style 或 Haze layer。
- [x] 按钮使用圆形普通容器，容器 alpha 为至少 `0.60f`，图标内容色不被整体 alpha 淡化。
- [x] `SpaceSwitcherRailButton` 和 `SpaceSwitcherSheet` 行为不受影响。
- [x] 定向搜索确认无残留无用 import 或 Haze API。
- [x] `:app:compileDebugKotlin` 通过；本小任务不运行 lint。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
