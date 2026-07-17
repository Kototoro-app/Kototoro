# 隔离搜索页面交互与 Backdrop 采样

## Goal

让搜索界面成为真正独立的模态交互层：搜索页打开后，主界面不能接收点击、滚动、拖拽或导航操作；搜索页顶栏玻璃不能错误采样主界面内容。

## Requirements

- R1：搜索层覆盖全屏有效输入区域，透明/非卡片区域也必须消费指针事件。
- R2：搜索列表滚动只影响搜索列表，不得驱动主页内容、顶栏、底栏或主界面 FAB。
- R3：搜索顶栏在动态背景下不使用主界面的 Backdrop/Haze 采样源，使用独立的静态高对比表面。
- R4：搜索面板、输入框、筛选区域和搜索结果保持现有功能与转场动画。
- R5：返回键、返回按钮、搜索结果点击和筛选操作仍按现有回调工作。
- R6：收藏页顶栏的收藏分组切换控件复用顶栏 Backdrop 材质，不能继续使用独立的旧 GlassSurface。
- R7：iOS 风格导航栏直接拖拽时，按钮命中位置必须使用实际按下点和导航栏内部统一坐标，不得向按钮右侧偏移。
- R8：导航栏右侧独立 FAB 的玻璃采样应稳定对准 FAB 正下方；小尺寸独立控件不得使用造成明显位移的 Lens 折射。
- R9：弹出菜单分组分割线左右保留内边距，所有作品列表、详情页和主界面的相关菜单统一使用玻璃菜单组件。
- R10：主界面空间切换面板改为锚定 FAB 的弹出菜单；iOS 风格使用根层 Backdrop，其他风格保留统一菜单表面。
- R11：作品列表过滤器栏、详情页顶栏按钮组以及详情页现代 dock 使用统一的 Backdrop 控件入口。
- R12：详情页基本信息面板使用内容容器级 Backdrop 材质；面板必须带圆角裁剪、适度模糊和弱边缘描边，并在非根层/非 iOS 环境安全回退。
- R13：列表内容层的 Backdrop 控件不得把自身再次纳入同一个 LayerBackdrop；必须使用官方 `exportedBackdrop` 机制避免渲染循环和 RenderThread SIGSEGV。独立 FAB 也必须保持稳定采样。

## Acceptance Criteria

- [ ] 搜索界面打开时，点击空白区域不会进入主界面作品详情或触发主界面按钮。
- [ ] 在搜索界面非列表区域拖拽/滚动，主界面内容位置、顶栏和底栏状态不变化。
- [ ] 搜索列表滚动只改变搜索列表自身的位置。
- [ ] 动态背景开启时，搜索顶栏按钮不显示主界面被遮挡内容，也不出现左上角错误采样。
- [ ] 关闭搜索后，主界面仍可正常点击和滚动。
- [ ] 收藏页横向分组控件在 iOS 风格下具有与顶栏一致的 Backdrop 玻璃效果。
- [ ] 导航栏拖拽从按钮任意位置开始时，切换阈值与实际按钮区域一致。
- [ ] Space 切换 FAB 和继续阅读 FAB 的 Backdrop 内容不再向右视觉偏移。
- [ ] 菜单分割线不贯穿菜单左右边缘，作品列表和详情页新增菜单不再使用裸 DropdownMenu。
- [ ] 空间切换菜单锚定 FAB；详情现代 dock 在 iOS 风格下使用 Backdrop。
- [ ] 详情页基本信息面板在 iOS 风格下使用内容容器级 Backdrop，非 iOS 或无采样源时保持稳定回退。
- [ ] 收藏页和推荐页切换、滚动时不再触发 Backdrop RenderThread SIGSEGV；作品列表标签栏和主界面 FAB 在 iOS 风格下保持 Backdrop。
- [ ] `:app:compileDebugKotlin` 和 `git diff --check` 通过；按项目约定不运行 lint。

## Notes

- 官方参考：[Understand gestures](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)、[Nested scrolling modifiers](https://developer.android.com/develop/ui/compose/touch-input/scroll/nested-scroll-modifiers)、[Pointer input API](https://developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/pointerInput.modifier)。
- 保留同窗 Overlay 转场；本任务不迁移到独立 Dialog Window，避免改变现有搜索状态、键盘和导航生命周期。
