# 统一弹出菜单玻璃样式

## Goal

让主界面内容源类型过滤菜单、详情页菜单和作品列表菜单与主界面“更多”菜单保持一致的视觉和交互表现。FAB 弹出菜单作为现有基准，继续维持现状。

## Background

- 共享入口 `GlassDropdownMenu` 已存在，但部分调用方仍使用跨窗口 `DropdownMenu` fallback，因此 iOS 风格下没有正确的 Backdrop 采样。
- 主界面“更多”菜单已使用 `RootGlassMenuOverlay`、共享 root Backdrop、紧凑菜单行和统一字体，是目标样式基准。
- Android Compose `DropdownMenu` 在独立 Popup window 中，不能直接采样主窗口 Backdrop；必须使用同窗口 root overlay，或在 Backdrop 不可用时使用静态玻璃 fallback。

## Requirements

1. 主界面内容源类型过滤菜单使用与主界面“更多”菜单相同的 root-level Backdrop overlay。
2. 详情页所有 Compose 弹出菜单使用相同的 root-level Backdrop overlay和菜单视觉参数。
3. 作品列表所有 Compose 弹出菜单使用相同的 root-level Backdrop overlay和菜单视觉参数。
4. 菜单项的字体、行高、内边距、图标间距、分隔线和内容颜色统一使用主界面的 `CompactDropdownMenuItem`、`CompactDropdownMenuText`、`CompactDropdownMenuDivider` 规范。
5. iOS Backdrop 不可用或菜单没有安全的同窗口 overlay host 时，使用静态玻璃表面，不回退到 Haze；非 iOS 继续保留现有 Haze/Material fallback。
6. FAB 弹出菜单行为和样式不改变。
7. 选中态、禁用态、leading/trailing icon 以及现有菜单动作语义保持不变。

## Acceptance Criteria

- [ ] iOS 风格下，主界面内容源类型过滤菜单、详情页菜单和作品列表菜单均能看到其下方内容的 Backdrop 效果，不采样窗口左上角，不出现纯黑/纯透明异常。
- [ ] 上述菜单的尺寸、圆角、字体、行高、内边距、分隔线和颜色与主界面“更多”菜单一致。
- [ ] 菜单从顶部操作按钮向下展开，从底部操作按钮向上展开，并在窗口边界内正确夹紧。
- [ ] FAB 菜单回归测试保持原有表现。
- [ ] 非 iOS 风格和 Backdrop 不可用路径不使用 Haze 作为 iOS 菜单的隐式 fallback。
- [ ] `git diff --check` 和 `:app:compileDebugKotlin` 通过。

## Out Of Scope

- Android View 系统的 `PopupMenu`、视频播放器原生 Popup、跨 Activity 菜单。
- FAB 弹出菜单的视觉或交互重做。
