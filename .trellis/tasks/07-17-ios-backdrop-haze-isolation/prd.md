# 隔离 iOS Backdrop 与 Haze

## Goal

确保启用 iOS 界面风格时，所有通用玻璃表面都只使用可安全采样的 Backdrop/Liquid Glass 或无特效半透明降级，不创建或绘制任何 Haze 特效；显示选项面板在保留 `ModalBottomSheet` 承载方式时遵守跨窗口采样限制。

## Background

- 根导航层已在 `InterfaceStyle.IOS` 下禁用 `hazeSource`，见 `app/src/main/kotlin/org/skepsun/kototoro/main/ui/compose/AppNavGraph.kt:740`。
- 通用 `GlassSurface` 的 `useRuntimeHaze` 只检查玻璃设置、调用方开关和运行时能力，未检查界面风格，见 `app/src/main/kotlin/org/skepsun/kototoro/core/ui/glass/GlassSurface.kt:206`。
- `DisplayOptionsSheet` 当前直接使用 `GlassSurface`，见 `app/src/main/kotlin/org/skepsun/kototoro/list/ui/compose/DisplayOptionsSheet.kt:112`。
- iOS 风格滑条按钮已经通过 `drawBackdrop` 绘制，见 `app/src/main/kotlin/org/skepsun/kototoro/core/ui/compose/KototoroSlider.kt:184`。
- 项目前端规范明确：Popup/Dialog 使用独立窗口时必须使用静态降级，除非存在同窗口 Overlay Host；不得直接采样主窗口 `LayerBackdrop`，见 `.trellis/spec/frontend/quality-guidelines.md` 的 “Backdrop Menus and Popup Coordinates” 与 “Backdrop Source and Effect Isolation”。
- Space 切换面板使用根层 `GlassDropdownMenu`，但 `RootGlassMenuOverlay` 当前统一按 `anchorBounds.bottom + gap` 向下放置；底部 Space FAB 因而把面板放到导航栏下方。
- `TopBarControlSurface` 与根层 `RootGlassMenuOverlay` 的 Backdrop 分支使用裸 `Box`，没有像 Material `Surface` 一样提供主题 `LocalContentColor`，深色主题下顶栏图标和菜单内容会继承黑色。
- 作品列表顶栏按钮组和标签胶囊仍直接使用 `GlassSurface`；iOS 风格禁用 Haze 后只能显示静态回退，并且作品列表尚未把可采样内容层注册为与顶栏消费者分离的 `LayerBackdrop` Source。
- 主界面顶栏沉浸渐变只按内容滚动累计量控制；顶栏在反向浏览时重新出现，渐变可能先于 Chrome 消失。作品列表使用相同的“滚动量与 Chrome 可见度互斥”计算。
- `KototoroApp` 已计算沉浸路由的 Space FAB 位置和显示条件，但实际 FAB 与 `SpaceSwitcherSheet` 被放在只由 `MainShellRouteContent` 调用的 `mainShellChrome` 中，因此作品列表和详情路由无法渲染入口。

## Requirements

- R1：`InterfaceStyle.IOS` 必须作为运行时 Haze 的统一否决条件；所有 `hazeSource` 与通用 `GlassSurface`/`hazeEffect` 资格判断复用同一门控，调用方不能因遗漏局部判断而在 iOS 风格下启用 Haze 管线。
- R2：显示选项面板继续由 `ModalBottomSheet` 承载；由于其独立窗口无法安全采样主窗口 Backdrop，iOS 风格下使用无 Haze 的半透明 `Surface` 降级，不强行接入主窗口 Backdrop。
- R3：任何 iOS 风格调用在 Backdrop 不可用或无法安全使用时，只允许回退到无 Haze 的半透明 `Surface`；不得回退到 Haze。
- R4：非 iOS 风格继续遵循现有玻璃偏好和运行时能力启用 Haze，避免改变现有 Material 风格表现。
- R5：保留显示选项面板当前的形状、布局、交互、拖拽行为及零 Material 容器 elevation 设置。
- R6：根层玻璃菜单支持按调用方要求在锚点上方展开；Space 切换面板必须使用上方展开，而顶部栏菜单保持现有向下展开行为。
- R7：上方展开必须基于菜单实测高度计算，并把最终位置约束在根窗口范围内；不得用固定菜单高度猜测。
- R8：所有裸 `Box` Backdrop 容器必须显式提供 `MaterialTheme.colorScheme.onSurface` 作为默认内容色，深色主题下顶栏按钮、更多菜单以及 Space 菜单图标和名称不得呈现黑色。
- R9：作品列表顶栏按钮组和标签胶囊复用统一 `TopBarControlSurface`；iOS 风格使用同窗口 Backdrop，非 iOS 风格保留现有 Haze/静态路径。
- R10：作品列表为当前内容层注册独立或继承的 `LayerBackdrop` Source，顶栏消费者作为后续兄弟节点绘制；不得把消费者纳入同一 Source。
- R11：顶栏沉浸渐变在内容滚动覆盖或顶栏 Chrome 可见任一条件成立时保持可见；该规则同时应用于主界面和作品列表，不依赖 iOS 风格。
- R12：Space FAB 和切换面板必须位于所有主导航目的地共享的覆盖层，作品列表、详情页和搜索路由均可显示和打开；继续遵循总开关、详情底部面板避让和现有定位条件。
- R13：每个导航 BackStackEntry 必须拥有独立的 `LayerBackdrop`；进入目标界面后立即切换全局消费者到目标 Source，即使目标内容仍在加载，也不得继续显示离场界面的采样缓存。
- R14：Space 切换菜单和 `RootGlassMenuHost` 必须是应用级唯一实例；主界面、作品列表、详情页和搜索路由均使用同一套锚定 Backdrop 菜单，不得按路由降级为 `ModalBottomSheet`。
- R15：详情页用于全局 FAB/菜单的 Source 必须记录全景背景和实际详情内容，包括滚动到 FAB 下方的相关作品封面；详情页内部顶栏仍从独立背景 Source 采样，避免 Source 自采样。
- R16：Space 菜单标题左边缘与 Space 行首图标左边缘对齐。

## Acceptance Criteria

- [x] AC1：iOS 风格下，统一 Haze 可用性门控恒为 `false`，不会附加 `Modifier.hazeSource` 或 `Modifier.hazeEffect`。
- [x] AC2：iOS 风格下，显示选项面板继续正常显示为静态半透明玻璃表面，不采样主窗口 Backdrop，也不使用 Haze。
- [x] AC3：iOS 风格下，其他通用 `GlassSurface` 在 Backdrop 不可用或未采用专用 Backdrop 组件时仍能正常显示，且不使用 Haze。
- [x] AC4：非 iOS 风格下，`GlassSurface` 的 Haze 开关行为与修改前一致。
- [x] AC5：应用 Kotlin 编译通过，相关静态检查或测试通过。
- [x] AC6：Space 切换面板显示在 Space FAB 和底部导航栏上方，且绘制层级保持高于主界面 Chrome。
- [x] AC7：顶部栏现有根层菜单仍向下展开，菜单尺寸变化时不会越过根窗口上下边界。
- [x] AC8：深色主题下主顶栏搜索/更多按钮、更多菜单、Space 菜单的图标和主名称使用主题高对比前景色。
- [x] AC9：iOS 风格作品列表的返回/操作按钮组和标签胶囊使用有效 Backdrop 采样，不再呈现静态回退；非 iOS 样式不回归。
- [x] AC10：主界面和作品列表在顶栏可见或内容已滚入顶边时始终显示沉浸渐变，反向浏览时不先于顶栏消失。
- [x] AC11：Space 功能开启时，作品列表和详情页显示 Space FAB 并能打开切换面板；功能关闭或详情底部面板展开时遵循现有隐藏逻辑。
- [ ] AC12：从任意界面切换到仍在加载的目标界面时，目标 FAB、顶栏和菜单不会显示上一个界面的 Backdrop 内容。
- [ ] AC13：主界面、作品列表、详情页和搜索路由的 Space FAB 均打开相同的根层锚定 Backdrop 菜单。
- [ ] AC14：详情页 FAB 能采样其实际下方的详情控件和相关作品封面，而不只采样全景封面。
- [ ] AC15：Space 菜单标题与首列 Space 图标左对齐。

## Out of Scope

- 不调整 Haze/Backdrop 的参数、材质预设或用户偏好含义。
- 不重构所有 Sheet、Dialog 或 Popup 的承载方式。
- 不修改显示选项的业务状态和事件处理。
- 不修改 Space 切换状态、选择流程或 FAB 定位算法。
- 不把 Space FAB 改造成新的顶栏专用入口，也不修改 Space 切换事务。
