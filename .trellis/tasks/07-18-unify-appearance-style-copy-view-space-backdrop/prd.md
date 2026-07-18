# 统一外观风格文案并接入 View Space FAB Backdrop

## Goal

统一设置-外观中的界面风格命名，避免 `Legacy` / `Modern` 与实际风格标识不一致；同时让 iOS 风格下 View 实现的阅读器和播放器中的 Space 切换 FAB 使用与 Compose 界面一致的 Backdrop 视觉效果。

## Requirements

1. 界面风格选项文案统一为 `md3 (legacy)` 和 `ios (modern)`；中文和英文资源均遵循该固定写法。
2. 设置-外观下所有描述 iOS 预设的说明文本中，将表示风格名称的 `modern` 统一改为 `iOS`，不改变普通技术语义中的 `legacy`。
3. iOS 风格下，View 阅读器、小说阅读器和视频播放器的 Space 切换 FAB 使用 Backdrop/玻璃效果；Material 3 风格保持现有 FAB 行为和外观。
4. FAB 的现有交互契约必须保持：显示/隐藏、控件联动、点击打开 Space 切换器、空间切换中禁用、图标/无障碍描述和启动动画均不回归。
5. 当 Backdrop 不可用或设备/渲染路径不支持时，FAB 必须回退为可用的静态半透明样式。

## Confirmed Facts

- 文案入口为 `InterfaceStyle.titleResId`，资源位于 `app/src/main/res/values*/strings.xml`。
- 默认英文资源当前使用 `Legacy` / `Modern`，简体中文资源当前使用 `Material 3` / `iOS 风格`。
- Compose Space FAB 已在 `space/ui/SpaceSwitcher.kt` 使用 `LocalLiquidGlassBackdrop` 和 `drawBackdrop`。
- View FAB 由 `view_immersive_space_switcher_fab.xml` 声明，并由 `SpaceSwitcherDelegate` 统一管理。
- View reader/player 的正文是 Android View；Backdrop 是 Compose modifier，需要通过 Compose overlay/layer 接入，不能直接附加到 XML Material FAB。

## Acceptance Criteria

- [ ] 外观页界面风格两项在默认英文和简体中文中均显示为 `md3 (legacy)`、`ios (modern)`。
- [ ] 外观页涉及 iOS 预设的说明不再显示 `Modern style` / `现代风格` 等风格名称，统一显示 iOS；其他非设置语义的 legacy/modern 文案不被误改。
- [ ] iOS 风格下 View reader、novel reader、video player 的 Space FAB 可见时呈现 Backdrop 效果，且无可用采样源时正常回退。
- [ ] Material 3 风格和现有 FAB 交互行为不变。
- [ ] 通过资源检查、Kotlin 编译/静态检查及相关测试。

## Out Of Scope

- 不重命名 Kotlin 中的 `InterfaceStyle.IOS`、历史偏好键或内部变量。
- 不将整个 View reader/player 重写为 Compose。
- 不修改设置之外的普通业务文案中的 `legacy` / `modern`。

## Resolved Risk Decision

Backdrop 对 Compose layer 的采样可能无法覆盖同一 Activity 中的 Android View 内容；本次实现接受这种限制。优先使用 Backdrop，采样不可用时回退到静态玻璃样式，保证 FAB 可用性和视觉一致性。
