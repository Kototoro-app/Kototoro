# 实施计划

- [x] 为共享 Backdrop 顶栏和根层菜单补充主题内容色。
- [x] 为作品列表建立内容 Source，并把顶栏按钮组和标签胶囊迁移到统一顶栏容器。
- [x] 提取并应用顶边沉浸渐变透明度计算，添加回归测试。
- [x] 将 Space FAB 移到全局覆盖层，并为非主壳路由挂载安全的 Space 切换面板。
- [x] 运行目标单元测试、`:app:compileDebugKotlin` 与 `git diff --check`。
- [ ] 新增路由级 Backdrop Host，隔离 BackStackEntry 的采样生命周期。
- [ ] 将 RootGlassMenuHost 和 SpaceSwitcherSheet 提升为应用级唯一覆盖层。
- [ ] 为详情页拆分背景 Source 与完整路由 Source。
- [ ] 对齐 Space 菜单标题与行首图标，并补充回归验证。
- [ ] 重新运行目标单元测试、`:app:compileDebugKotlin` 与 `git diff --check`。
