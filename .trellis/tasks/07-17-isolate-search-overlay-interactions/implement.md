# 实施计划

1. 检查并修改搜索 Overlay 的全屏输入屏障，消费空白区域 pointer event。
2. 修改顶栏玻璃组件，使 `allowRuntimeHaze = false` 同时禁用 iOS Backdrop 采样。
3. 修改主壳 nested scroll 连接，在搜索层挂载时不更新主壳滚动状态。
4. 将收藏分组顶栏轨道统一接入 `TopBarControlSurface`。
5. 修正 iOS 导航栏拖拽起点从按钮局部坐标到导航栏行坐标的换算。
6. 移除独立 56dp FAB 的 Lens 折射，保留 Vibrancy/Blur 玻璃表面，避免小控件内容视觉偏移。
7. 统一菜单分割线内边距；迁移作品列表选择菜单和详情页裸 DropdownMenu 到 `GlassDropdownMenu`。
8. 将作品列表过滤器栏、详情页顶栏按钮组和现代 dock 接入统一 Backdrop 容器；空间切换面板锚定主界面 FAB。
9. 为详情基本信息面板增加内容容器级 `LiquidGlassSurface`，仅在同窗 iOS Backdrop 源可用时启用。
10. 按 Backdrop 官方 glass-on-glass 方案为内容层和独立 FAB 增加 `exportedBackdrop`，避免同一 LayerBackdrop 递归采样。
11. 编译、差异检查，并复查搜索结果点击、筛选、返回和关闭后的主界面交互。

## 验证命令

- `./gradlew :app:compileDebugKotlin`
- `git diff --check`
- 不运行 lint。

## 当前进度记录（2026-07-17）

- 已完成搜索层输入隔离、主壳滚动隔离，以及搜索/收藏/列表/详情相关 Backdrop 容器迁移。
- 已为独立 `DetailsActivity` 注入 root `LayerBackdrop`，详情页顶栏和现代 Dock 不再因缺少 source 而回退为普通半透明。
- 已统一应用级屏幕与分组水平边距；主页历史、更新、推荐 rail 使用扩展 viewport，首项保留锚定边距，左右可滚动至屏幕边缘。
- 已修复根菜单层级和菜单项点击区域：菜单项现在整行可点击，不再只有文字区域命中。
- 最近验证：`./gradlew :app:compileDebugKotlin` 与 `git diff --check` 均通过；未运行 lint。
- 待设备验证：详情页 Backdrop 实际采样效果、主页 rail 两侧滚动边界，以及主界面更多菜单整行点击命中率。
