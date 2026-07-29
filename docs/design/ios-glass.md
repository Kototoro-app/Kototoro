# Kototoro iOS Glass 规范

## 1. 定位

iOS Glass 是 Kototoro 在 Android 上对 Apple 设计原则的适配，不是 iOS 控件的逐像素复制。
它保留 Kototoro 的信息架构和 Android 系统行为，只借鉴通透材质、连续几何、上下文感知和克制动效。

核心边界：

> 内容保持真实，Glass 只承载导航与控制。

## 2. 材质层级

### 2.1 内容层禁止 Glass

以下元素使用普通主题 Surface 或直接显示内容：

- 漫画页、小说纸张、视频画面；
- 封面、列表卡片主体和设置分组；
- Sheet、Dialog 的主要阅读区域；
- 长文本、表单和需要稳定对比度的区域。

模糊内容层会同时损害清晰度、性能和层级，不属于 iOS Glass。

### 2.2 Glass 的允许范围

Glass 只用于少量顶级控制：

- 顶栏与底部导航；
- 阅读器浮动控制条；
- Space Sidekick 入口；
- 紧凑的上下文菜单或悬浮工具组；
- 需要短暂悬浮于媒体内容之上的主操作。

同一屏幕最多保留一个主要 Glass 控制组。多个相邻控制应合并采样或组成一个视觉容器，
避免每个按钮各自形成“玻璃泡”。

### 2.3 材质等级

| 等级 | 使用场景 | 表现 |
| :--- | :--- | :--- |
| Stable | 文本密集、背景复杂、低端设备或降级状态 | 主题 Surface，轻微透明或完全不透明 |
| Regular Glass | 大多数浮动导航与控制 | 适度模糊、主题染色、清晰轮廓 |
| Clear Glass | 媒体背景上极少数强调控制 | 更高通透度，必须有稳定前景与对比保护 |

Clear Glass 仅在背景是丰富媒体、内容不会因变暗而受损、前景足够醒目时使用。禁止把所有 Glass
统一设为 Clear。

## 3. 颜色与可读性

- 表面染色从 `MaterialTheme.colorScheme.surfaceContainer` 等语义色派生，禁止固定纯白或纯黑。
- 标准控制前景使用 `onSurface`；主动作才使用强调色。
- 深色模式的主文字与关键图标必须接近高对比前景，不能为了“柔和”降为难辨的灰色。
- 背景采样之后再绘制主题表面染色，确保文字对比稳定。
- 纯白、纯黑和大面积低纹理背景不能只依赖模糊、折射或外部阴影。均匀颜色经过这些效果后仍接近原色，
  Regular Glass 必须通过语义 Surface 产生克制但可见的内部明度差。
- Shadow 是辅助深度线索，不是容器轮廓的唯一来源。浅色背景上看不清时，优先校正 Surface tint 和
  滚动边缘材质，不通过无限提高 elevation 补偿。
- 边框只用于增强轮廓，不承担主要对比度；高对比模式下应提高表面不透明度而非无限加亮边框。
- 不从背景媒体实时提取高饱和色作为整组控件染色。

## 4. 几何与密度

- 大容器比小控件拥有更厚的材质感，但不以更重的模糊替代层级。
- 内外轮廓保持同心；嵌套间距与圆角半径必须视觉连续。
- 工具组采用内容自适应宽度和合理最大宽度，禁止无条件铺满屏幕。
- 视觉容器可紧凑，实际点击目标仍遵守共享组件规范。
- 图标按钮不额外套多层圆形背景；选中态优先通过单一填充、前景色或位置响应表达。

## 5. 动效

- Glass 控件的出现应与触发源保持空间连续，可采用轻量形变、位移和透明度变化。
- 展开控制组时，让容器整体变化，避免子按钮依次夸张弹跳。
- 背景变化时材质可以平滑响应，但不能造成持续闪烁或亮度抽动。
- 滚动边缘可以使用轻微遮罩或材质增强来保持顶栏可读，不能形成明显的灰色矩形。
- 开启减少动态效果后取消大幅缩放、视差、折射变化和模糊过渡。

## 6. 组件规则

### 6.1 顶栏

- 顶栏只包含导航、标题和当前页面的一至两个关键操作。
- 主界面与 Material 共享 64 dp 内容区、16 dp 水平边距和 48 dp 命中槽；Glass 可见控件为居中的
  44 dp，一级目的地主标题为 28/36 sp Bold。
- 滚动前可更通透，内容进入顶栏下方后提高表面稳定度。
- 设置页等文本页面优先 Regular 或 Stable，不使用 Clear。

iOS Glass 不再使用额外的上下各 15 dp 来制造 74 dp 主行，也不单独维护一套字号。两种风格的
信息密度和换行行为保持一致，Glass 只改变材质、轮廓和有限字重。

### 6.2 底部导航与阅读器控制条

- 容器宽度由按钮组决定，并设置最大宽度；首帧和动画结束态都不能铺满屏幕。
- 阅读器控制条上下内边距保持紧凑，按钮触摸范围通过透明布局区域保证。
- 进度常驻时与控制条形成一个层级，不再增加“进度”入口按钮。
- 控制条隐藏后尽可能归还完整内容画布。

### 6.3 Space Sidekick

- 入口固定在屏幕右侧上方约四分之一处，轮廓近似 `[`，向左滑动或点击展开。
- 静止态低干扰；命中区域大于可见轮廓。
- 展开面板保持窄，并与屏幕右缘形成明确关系，不模拟全宽 Sheet。
- 面板主体采用稳定 Surface；只有入口或顶级控制可以使用 Glass。

### 6.4 Sheet、Dialog 与 Menu

- Sheet 和 Dialog 是任务容器，默认采用稳定语义 Surface，不用高透明 Glass。
- 顶部拖拽区域只有在拖拽行为需要被提示时保留。
- 标题不得重复入口语义，例如翻译面板不再增加无信息量的 “AI” 标题。
- Popup 或 Dialog 属于不同窗口时，不尝试采样根窗口 Backdrop，使用稳定降级表面。
- Menu 项使用 14/20 sp Medium；Sheet 与 Dialog 标题使用 22/28 sp SemiBold，正文使用
  14/20 sp Regular，操作使用 14/20 sp SemiBold。不得因为 Glass 背景而降低文字对比。

## 7. Compose 与 Backdrop 实现约束

- iOS 风格判定统一使用 `LocalInterfaceStyle.current == InterfaceStyle.IOS`。
- Backdrop 来源统一从 `LocalLiquidGlassBackdrop.current` 获取。
- Backdrop 是唯一的实时 Glass 渲染器；项目不再依赖 Haze。
- 源与目标必须处于同一窗口；Popup、Dialog 优先走已有 Overlay 宿主或稳定表面降级。
- 效果顺序固定为 `color filter -> blur -> lens`。
- `lens` 只用于 `CornerBasedShape`，尺寸不足或轮廓不适合时直接省略。
- 表面染色使用 `drawBackdrop(onDrawSurface = ...)` 绘制；只有无法使用该回调时才在
  `drawBackdrop` 之后追加主题背景，防止效果覆盖语义颜色。
- `drawBackdrop` 默认提供 `Highlight.Default` 与 `Shadow.Default`。需要不同层级时显式传入
  Backdrop `Highlight`/`Shadow`；禁止再套一层 Compose elevation shadow，避免重复阴影。
- 不在 `drawBackdrop` 外层添加同形状 `clip`。Backdrop 已按 `shape` 裁切玻璃内容，外层裁切会截断
  Backdrop 自带的外扩阴影与高光。
- 包含 Backdrop 外扩阴影的父容器不使用 `alpha`/淡入淡出动画。透明度低于 `1f` 时，Compose 会将
  内容绘制到离屏图层并按图层边界裁切阴影；玻璃导航控件的进出场优先只使用位移动画。
- 顶栏使用 `mainTopBarHeight` 作为布局槽位，并在其中居中放置 `topBarButtonSize` 控件，为原生阴影
  保留上下外扩空间。下方存在标签 rail 时，只在顶栏实际折叠期间裁切折叠槽位，完全展开时不裁切
  顶栏或 rail。
- `containerAlpha` 表达材质密度意图，必须参与 Surface tint 计算，但不等同于把同一 alpha 原样覆盖到
  `onDrawSurface`；应映射到适合玻璃合成的克制范围。
- 避免嵌套 Backdrop；相邻控件共享容器或导出的 Backdrop。
- Android 12 以下以及效果能力不足的设备必须保持完整功能和正确对比度。

## 8. 无障碍与降级

- “减少透明度”：Regular/Clear 全部降为 Stable。
- “增强对比度”：提高表面不透明度和前景对比，保留语义色。
- “减少动态效果”：取消视差、折射变化和大幅形变。
- 字体放大：容器允许增高或换行，不能裁切标题和主动作。
- TalkBack：Glass 不改变语义顺序；装饰层不暴露无意义节点。

## 9. 验收清单

- 去掉 Backdrop 后，页面层级是否仍成立？
- Glass 是否只出现在导航和控制层？
- 深色模式关键文字是否清晰，而非中灰？
- 背景最亮、最暗和高纹理情况下是否都可读？必须包含纯白阅读页与纯黑媒体画面。
- 纯白背景上是否仍能通过 Surface 明度差识别完整容器，而不是只看到局部阴影？
- 是否误将 Backdrop 自带阴影裁切，或同时叠加了 Backdrop 与 Compose 两套阴影？
- Popup/Dialog 是否使用了正确的跨窗口降级？
- 首帧、展开态和收起态的容器宽度是否一致合理？
- 减少透明度、增强对比度和减少动态效果是否真正改变渲染策略？

## 10. 官方依据

- [Apple Design Principles](https://developer.apple.com/design/human-interface-guidelines/design-principles)
- [Apple Materials](https://developer.apple.com/design/human-interface-guidelines/materials)
- [Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/)
- [Apple Typography](https://developer.apple.com/design/human-interface-guidelines/typography)
- [Apple Motion](https://developer.apple.com/design/human-interface-guidelines/motion)
- [Apple Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)
- [Backdrop Glass Bottom Bar](https://kyant.gitbook.io/backdrop/tutorials/glass-bottom-bar)
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
