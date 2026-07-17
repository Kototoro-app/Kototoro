# 技术设计

## 边界

变更限定在 Android app 的资源、Compose 主题色彩解析和 Compose 搜索建议 UI。动态背景仍由 `KototoroApp` 提供，搜索删除仍由现有 `onDeleteQuery` 回调完成。

## 方案

1. 在简体中文资源中补齐与默认英文资源同名的背景样式 key。
2. 在 `resolveComposeColorScheme` 中将 Dynamic Artwork Blur 视为暗背景渲染模式，确保浅色系统模式也采用高对比的亮色前景和暗色容器。仅对该枚举分支生效，避免影响其他背景模式。
3. 在 `RecentQueryDismissBackground` 的调用处根据 `SwipeToDismissBoxState.dismissDirection` 判断状态：`Settled` 时不绘制删除背景，实际拖动方向确定后才绘制带删除图标的背景。保留 `SwipeToDismissBox` 的状态和双向 dismiss 配置。
4. 将主顶栏的 `TopBarControlSurface` 提升为包内可复用组件，搜索顶栏直接复用该容器和主顶栏布局常量。动态背景下把搜索层使用的关键 surface 颜色提升为不透明，避免封面穿透。
5. 保留搜索覆盖层挂载状态对主顶栏 `canScroll` 的隔离，确保搜索列表滚动不会驱动主界面。
6. 将沉浸渐变的深浅判断扩展到 Dynamic Artwork Blur；搜索行通过 `LocalContentColor` 使用主题 `onSurface`；编辑框高度复用 `CompactTopBarPillHeight` 并移除额外上下行内边距。
7. 为 `TopBarControlSurface` 增加运行时 Haze 开关，搜索覆盖层传入 `allowRuntimeHaze = false`，保留玻璃容器形状和边框，但隔离主界面的 Haze 采样源。
8. 增加共享 `DynamicArtworkBackdrop` 容器；设置 Activity 注入并观察最近阅读内容，在 `KototoroTheme` 内包裹设置导航壳。背景层只负责图片/遮罩，不共享主界面的滚动或 Haze 状态。

## 取舍

- 采用主题级暗背景前景策略，而不是在每个搜索行/图标上逐个覆写颜色，避免遗漏其他页面控件并减少重复逻辑。
- 删除背景采用条件组合而不是修改数据流或引入额外状态，保持 Compose Material 的滑动行为和现有回调契约。
- 顶栏容器通过复用现有组件保持单一视觉实现；搜索层只覆盖动态背景的透明度，不改变普通背景模式的原有表现。

## 兼容性与回滚

所有修改均为资源或纯 UI 分支逻辑，不涉及持久化迁移。回滚对应文件即可恢复现状。

## iOS 26 设计研究结论

研究来源均为 Apple 官方 HIG 与 WWDC25 设计系统资料：

- [Liquid Glass](https://developer.apple.com/documentation/TechnologyOverviews/liquid-glass)
- [Materials](https://developer.apple.com/design/human-interface-guidelines/materials)
- [Adopting Liquid Glass](https://developer.apple.com/documentation/TechnologyOverviews/adopting-liquid-glass)
- [Get to know the new design system — WWDC25](https://developer.apple.com/videos/play/wwdc2025/356/)
- [Meet Liquid Glass — WWDC25](https://developer.apple.com/videos/play/wwdc2025/219/)
- [Typography](https://developer.apple.com/design/human-interface-guidelines/typography)
- [Buttons](https://developer.apple.com/design/human-interface-guidelines/buttons)
- [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)

### 必须遵循的原则

1. **内容层与功能层分离**

   Liquid Glass 只用于顶栏、底栏、工具栏、菜单、弹窗等功能层，不铺满内容卡片和列表。内容区域使用不透明或标准材质，避免整页背景、卡片和控件相互透视造成层级混乱。Haze 在 Kototoro 中承担相同职责：只给导航和必要的临时交互层使用。

2. **材质需要克制并保持单一层级**

   Regular 与 Clear 是两种不同材质，不能混用。Kototoro 的 iOS 风格默认采用 Haze/半透明的 Regular 语义；Clear 只允许用于媒体丰富背景上的导航控件，并且必须配合遮罩保证对比度。禁止在玻璃容器内部再叠玻璃容器。

3. **层级来自布局和分组，不来自装饰**

   顶栏按钮按照功能和使用频率分组：返回/导航属于一组，相关操作属于另一组，主要动作独立并使用强调色。避免给每个按钮额外套背景或边框。菜单应从触发按钮附近展开，保留触发源与菜单的空间关系。

4. **同心圆角与统一几何**

   形状分为固定圆角、胶囊和同心圆角。嵌套容器的内圆角应由外圆角减去内边距得到，不能为每层随意指定一个更大的圆角。手机上的主要按钮、开关、滑动条和导航选中项优先使用胶囊形；密集内容区域使用同心圆角矩形。

5. **文字层级优先于装饰**

   iOS 默认正文基准接近 17sp，避免过细字重；标题通过字号、字重和颜色建立层级，不通过大量颜色或容器强调。应用只使用一套主字体和必要的 CJK fallback，不能在 Material 3 与 iOS 风格中混用字体角色。

6. **触控尺寸与可读性优先**

   交互目标尽量达到 44dp，较小的视觉图标也必须通过透明触控区域满足命中尺寸。按钮必须有明确的按压反馈；文字、图标和背景在动态图片或 Haze 下都必须保持对比度。Reduced Transparency、Reduced Motion、Increase Contrast 等系统能力应对应到材质和动画降级策略。

7. **滚动边缘只用于固定功能层**

   顶栏/底栏覆盖滚动内容时，可使用一层柔和的边缘过渡；它用于标识内容与功能层边界，不是装饰性遮罩，也不能叠加多层或让搜索页滚动驱动主界面。

### 对 Kototoro 的实现映射

- `InterfaceStyle.IOS` 是完整风格入口，不能再通过零散的 Expressive 判断扩展第三种风格。
- `InterfaceStyleTokens` 继续承载字体角色、字号、间距、控件高度、圆角和命中区域等跨组件规则。
- `GlassSurface`/Haze 只应用于顶栏、底栏、工具栏、菜单、弹窗和必要的临时控件；主页内容卡片和浏览内容保持标准材质。
- 搜索界面必须拥有独立的功能层背景与滚动边界，不能透传主界面图片、滚动状态或 Haze 采样。
- 设置页分组使用单层容器；按钮组按功能分组，移除无意义的嵌套背景。
- 所有被界面风格接管的细分设置显示“当前风格实际生效值”和“手动值覆盖”提示。

## Haze Liquid Glass 进展（2026-07-17）

项目当前依赖为 `dev.chrisbanes.haze:2.0.0-alpha03`。官方最新文档显示，`haze-liquidglass` 和 `haze-liquidglass-materials` 仍处于实验开发阶段，尚未发布到 Maven Central，不能作为普通 Gradle 依赖接入。

当前官方 Liquid Glass 实现已经具备：

- 基于 AGSL/runtime shader 的折射、深度混合模糊、Fresnel/环境光抬升、镜面高光、边缘柔化和可选色散。
- `LiquidGlassStyle` 分组配置：Optics、Lighting、Color、Rendering，并通过 `LocalLiquidGlassStyle` 提供作用域级默认值。
- `SurfaceProfile` 轮廓：Circle、Squircle、Lip、Concave。
- 渐进式模糊、`Card`/`FloatingControl`/`Bar` 等材质预设仍在未发布开发分支中。
- Android API 33+ 的 runtime shader 路径已经修复单输入 shader 绑定问题，但受 Android RenderEffect 单输入限制，深度模糊混合会跳过；折射、镜面、Fresnel、色散、边缘柔化、着色和色彩调整仍然保留。

对 Kototoro 的结论：

1. 当前稳定实现继续使用已发布的 Haze Blur 与 `CupertinoMaterials`，作为 iOS 18 风格的默认材质；它们的参数来自 Apple 发布的 iOS 18 Figma 资源，适合生产使用。
2. Liquid Glass 实验模块可以继续保留在本地原型层，但不应直接替换所有 `GlassSurface`，尤其不能用于内容卡片、列表和搜索内容层。
3. 如果后续启用实验效果，应只用于顶栏、底栏和少量浮动操作按钮，并按 API 级别提供 Haze Blur fallback；Android 33+ 不应把“深度模糊”当作必然可用能力。
4. 上游后续值得跟踪的方向是渐进式 Liquid Glass、材质预设和折射内容参与模糊的组合变化；在模块正式发布前，不做依赖升级或大范围 API 迁移。
