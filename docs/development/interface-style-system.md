# Kototoro 界面风格系统

> [!NOTE]
> 本文保留风格收敛的依据、迁移顺序和工程实施记录。产品级规范已拆分到
> [Kototoro 设计语言文档库](../design/README.md)，发生冲突时以设计语言文档库为准。

状态：规范草案，作为后续 UI 收敛与审查基线  
更新日期：2026-07-26

## 1. 决策

Kototoro 只维护两套完整界面风格：

1. **Material 3 Expressive（默认）**
2. **iOS Glass**

现有 `MATERIAL_3` 与 `MATERIAL_3_EXPRESSIVE` 应合并为一条 Material 路径。迁移完成后，
设置中的“界面风格”只在确有切换需求时保留为两项；若产品最终决定只提供一种默认风格，
则隐藏该设置，但内部仍保留两套明确的主题策略供开发和兼容使用。

不再接受第三套“经典 Material 3”组件、尺寸或动效分支。新增 UI 必须属于上述两种风格之一。

## 2. 依据

Google 将 Material 3 Expressive 定义为 Material 3 的下一阶段，更新范围包括主题、组件、动效、
排版和形状，并与 Android 16 的系统视觉语言配合。Compose 的主题系统由颜色、排版、形状和
动效方案共同组成，而不是由模糊或玻璃效果定义。

官方参考：

- [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [MaterialExpressiveTheme API](https://developer.android.com/reference/kotlin/androidx/compose/material3/MaterialExpressiveTheme.composable)
- [MotionScheme API](https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme)
- [MaterialTheme API](https://developer.android.com/reference/kotlin/androidx/compose/material3/MaterialTheme)
- [Compose app bars](https://developer.android.com/develop/ui/compose/components/app-bars)
- [Material 3 insets](https://developer.android.com/develop/ui/compose/system/material-insets)
- [Adaptive Compose UI](https://developer.android.com/develop/ui/compose/build-adaptive-apps)
- [Miuix Compose Multiplatform](https://github.com/compose-miuix-ui/miuix)
- [Miuix documentation](https://compose-miuix-ui.github.io/miuix/zh_CN/)

项目当前使用 Compose BOM `2026.05.01`。采用新的 Expressive API 前必须核对该 BOM 实际解析的
Material 3 版本与 API 稳定性；实验性 API 只能封装在主题或共享组件层，禁止散落到业务页面。

## 3. 总体原则

### 3.1 共享原则

- 信息架构、功能位置、无障碍语义和业务状态必须在两种风格间一致。
- 风格差异只影响视觉、几何和动效，不得复制 ViewModel、导航或业务逻辑。
- 优先使用官方 Material 3 组件，再通过主题和小型共享组件适配。
- 最小触控区域为 `48dp`；紧凑视觉尺寸不能缩小可点击语义区域。
- 所有颜色必须来自 `MaterialTheme.colorScheme` 的匹配角色；禁止随意混用 container 与 on-color。
- 页面必须适配系统字体缩放、深色模式、横屏、分屏及大屏窗口变化。
- 系统栏与内容边缘优先通过 Material 3 的 `windowInsets` / `contentWindowInsets` 参数处理，
  避免重复叠加手工 padding。

### 3.2 禁止用效果代替层级

层级顺序为：

1. 布局与留白
2. 排版
3. 语义色
4. 形状
5. 动效
6. 极少量透明度或玻璃效果

如果前五项不能表达层级，不得直接增加模糊、阴影或半透明。

## 4. Material 3 Expressive

### 4.1 定位

Material 路径是 Android 原生、清晰、直接且高性能的默认体验。Expressive 表现力来自：

- 更明确的排版层级；
- 有语义的容器色和选中态；
- 与组件重要程度对应的形状；
- 突出交互使用 expressive motion；
- 高频、工具型交互使用 standard motion；
- 动态色与 Android 系统视觉语言。

它不使用 Haze、Backdrop 或实时背景模糊。

### 4.2 渲染约束

在 `InterfaceStyle.MATERIAL_3_EXPRESSIVE` 下：

- `GlassSurface` 必须退化为普通 Material `Surface`，或由调用方直接使用 Material 组件。
- 禁止 `Modifier.hazeSource`、`Modifier.hazeEffect`、`drawBackdrop`、`layerBackdrop`。
- 禁止为 Material 路径创建屏幕级 backdrop/haze state。
- 禁止噪点、折射、vibrancy、lens 和实时采样背景。
- 阴影只使用 Material 组件默认 elevation；普通列表卡片优先用 tonal surface 而非阴影。
- 分割关系优先使用间距和 `surfaceContainer*`，其次才是低强调 `outlineVariant`。

这项限制应最终由共享组件策略保证，而不是依靠每个页面自觉判断。

### 4.3 半透明使用

半透明是例外，不是基础材质。允许场景：

- 内容在顶栏下滚动时，设置页等低动态页面的顶栏可使用主题表面色叠加；
- 图片封面上的文字保护层；
- 模态 scrim；
- 系统栏到页面背景的边缘过渡。

Material 顶栏建议：

- 静止且内容未滚入顶栏：`surface` 或透明；
- 内容滚入顶栏：`surfaceContainer`，建议 alpha `0.92–0.98`；
- 不使用模糊；
- 深色模式仍使用主题表面色，不使用固定黑色；
- 顶栏文字始终使用 `onSurface`，不得因背景半透明而降低文字 alpha。

禁止场景：

- 普通按钮、列表项、设置分组大面积半透明；
- 多个半透明容器相互叠加；
- 用 `Color.White/Black.copy(alpha)` 替代语义色；
- 在高动态封面或视频上仅靠轻微透明保证文字可读。

### 4.4 颜色

- 默认支持 Android 12+ dynamic color；不可用时使用 Kototoro 品牌色方案。
- 主操作：`primary/onPrimary`。
- 选中容器：`primaryContainer/onPrimaryContainer`。
- 普通页面：`surface`。
- 分组：`surfaceContainerLow` 或 `surfaceContainer`。
- 强调卡片：`surfaceContainerHigh`，不可无差别用于所有卡片。
- 次级文字：`onSurfaceVariant`，但正文和关键标签不得通过降低 alpha 进一步弱化。
- 禁用态才允许 on-color alpha。
- 深色模式禁止把正常文字统一处理成灰色；关键文字必须保持 `onSurface` 对比度。

### 4.5 排版

- 页面主标题：`headlineSmall` 或适配大顶栏的官方 title style。
- 分组标题：`titleMedium` / `titleSmall`。
- 列表主信息：`bodyLarge`。
- 辅助信息：`bodyMedium` / `bodySmall`。
- 操作标签：`labelLarge` / `labelMedium`。
- 每个容器最多两个明显文字层级，避免标题、说明、徽标同时竞争。
- Expressive 不等于全局粗体；字重只用于建立重点。
- 用户字体选择可以覆盖字体家族，但不能破坏字号、行高与权重层级。

### 4.6 形状

形状表达组件类别与重要程度，禁止每个组件随意指定圆角：

| 组件 | 建议形状 |
|---|---|
| 小型标签、菜单项内部控件 | `extraSmall` / `small` |
| 设置项、普通卡片、输入框 | `medium` |
| 面板、底部 Sheet、重要卡片 | `large` |
| Hero 容器、扩展 FAB | `extraLarge` 或 Expressive increased shape |
| 图标按钮、单一播放操作 | `CircleShape` |

同屏不超过三个主要圆角等级。嵌套容器的内圆角必须小于外圆角。

### 4.7 间距与密度

基础间距采用 `4dp` 网格：

- 紧邻图标与文字：`4–8dp`
- 控件内部：`8–12dp`
- 列表项水平：`16dp`
- 页面水平：紧凑窗口 `16dp`，中等及以上窗口按自适应布局增加
- 分组之间：`16–24dp`

Expressive 不能通过普遍放大控件实现。高频阅读、浏览和设置界面保持信息密度；
仅 Hero、空状态和关键首次引导允许更宽松的空间。

### 4.8 动效

当依赖版本支持时，通过 `MaterialTheme.motionScheme` 统一提供动效：

- Hero、主导航切换、重要容器展开：`MotionScheme.expressive()` 对应 spatial spec。
- 颜色、alpha、图标状态：effects spec。
- 高频列表操作、筛选、设置开关：standard/fast spec。
- 禁止为每个组件自行定义无关联的 easing 与时长。
- Reduced motion 开启时，移除弹性、缩放和长距离空间动效，仅保留必要短淡入淡出。
- 列表滚动期间不启动装饰性动画。

### 4.9 组件规则

#### 顶栏

- 优先使用 `TopAppBar`、`CenterAlignedTopAppBar`、`MediumTopAppBar` 或 `LargeTopAppBar`。
- 设置页使用 Small/Medium 顶栏；详情 Hero 页面才考虑 Large。
- 滚动行为使用官方 `TopAppBarScrollBehavior`。
- 最多保留 2 个常用 action，其余进入 overflow。

#### 导航

- 紧凑窗口且一级目的地不超过 5 个：`NavigationBar`。
- 中等/展开窗口：根据窗口尺寸切换 `NavigationRail` 或自适应导航。
- 选中态由官方 indicator、语义色和标签共同表达，不增加模糊光晕。

#### 按钮与 FAB

- 每屏最多一个真正的主 FAB。
- FAB 只承载页面主操作，不承担全局模式切换。
- 常驻工具操作使用 icon button、toolbar 或菜单。
- 文字能显著降低歧义时使用 Extended FAB；空间紧张或语义明确时使用普通 FAB。

#### 卡片与列表

- 设置页优先使用列表和分组，不把每一行包装成独立悬浮卡片。
- 内容浏览卡片可使用封面，但文字必须有稳定的对比保护层。
- 选中态优先使用 container 色和形状，不同时叠加粗边框、阴影和缩放。

#### Sheet、Dialog、Menu

- 使用 Material 3 官方组件和 surface/elevation。
- Material 路径不在 Popup/Dialog 内尝试跨窗口背景采样。
- Sheet 顶部拖拽手柄仅在确有拖拽交互且能表达状态时出现。

## 5. iOS Glass

### 5.1 定位

iOS Glass 是独立的材质路径，强调内容之上的轻量悬浮控制、背景采样与连续圆角。它可以使用
Backdrop；仅在 Backdrop 不适用或平台受限时使用现有稳定降级路径。

### 5.2 基本规则

- 使用 `GlassSurface` 和共享 glass tokens，禁止页面自行拼装效果链。
- Backdrop 源与目标必须位于同一窗口；Dialog/Popup 使用稳定表面降级。
- 效果顺序遵循 color filter → blur → lens。
- 玻璃表面仍需主题化 tint，不能把模糊本身当作背景颜色。
- 内容色与材质色分离；关键文字使用高对比 `onSurface`。
- 一个区域只允许一个主玻璃层，避免玻璃嵌套。
- 阅读器等高频界面以紧凑控制为主，不能为了“玻璃感”增加无意义高度。

### 5.3 与 Material 的边界

iOS 路径可以共享 Material 的语义色和基础无障碍能力，但不应把 Material Expressive 的
大形状、indicator 和空间动效机械叠加到玻璃组件上。

## 6. 两种风格对照

| 维度 | Material 3 Expressive | iOS Glass |
|---|---|---|
| 默认地位 | Android 默认 | 用户可选 |
| 背景模糊 | 禁止 | 允许，受共享策略约束 |
| Haze | 不依赖 | 不依赖；已由 Backdrop 与稳定 Surface 完全取代 |
| Backdrop | 禁止 | 首选玻璃采样方案 |
| 半透明 | 少量主题表面叠加 | 玻璃 tint 的组成部分 |
| 层级表达 | 语义色、排版、形状、elevation | tint、边框、背景采样、形状 |
| 动效 | Material motion scheme | 克制的连续/空间动效 |
| 组件来源 | 官方 Material 3 优先 | 共享 Glass 组件 |
| 设置/列表密度 | 中高 | 中高 |
| Hero 表现力 | 色彩、排版、形状、动效 | 图像、玻璃控制、连续过渡 |

## 7. 代码结构目标

保留以下单一入口：

- `InterfaceStyle`: 最终仅 `MATERIAL_3_EXPRESSIVE`、`IOS`
- `InterfaceStylePolicy`: 行为能力，不包含具体 dp
- `InterfaceStyleTokens`: 几何 token
- `KototoroTheme`: 颜色、排版、形状、motion 的唯一主题装配点
- `GlassSurface`: 仅 iOS Glass 材质入口；Material 路径必须是普通 Surface

禁止：

- 页面直接读取旧 `MATERIAL_3` 决定布局；
- 同一组件内出现三个 style 分支；
- 业务层保存风格相关状态；
- 为单一页面复制一套主题 token。

## 8. 迁移顺序

### Phase 1：收敛模型

- 将旧 `MATERIAL_3` 用户值迁移为 `MATERIAL_3_EXPRESSIVE`。
- `InterfaceStyle` 收敛为两项。
- 设置页只展示两项；若隐藏入口，仍需保留旧值迁移。
- 删除 `isMaterialExpressiveComponentsEnabled` 等兼容桥接字段的运行时使用。

### Phase 2：切断 Material 玻璃

- 修改 `GlassSurface`：Material 风格直接走普通 Surface。
- Haze 依赖、采样源和兼容实现全部删除。
- 清查所有 `drawBackdrop`、`layerBackdrop` 调用，确保仅在 iOS 分支构造。
- 优先处理主界面、设置、详情、阅读器和欢迎页。

### Phase 3：建立 Expressive Theme

- 使用当前依赖可用的官方 Expressive theme/motion API。
- 统一 color、typography、shapes、motion。
- 删除页面级任意圆角、随意 alpha 和自定义动画时长。

### Phase 4：组件打磨

按优先级：

1. 设置页顶栏与分组
2. 主导航与主页 FAB
3. 内容卡片和筛选
4. 详情 Hero 与操作区
5. 阅读器控制栏
6. Sheet、Dialog、Menu

### Phase 5：验证与删除旧路径

- 截图测试覆盖两种风格、明暗主题、动态色开关。
- Compose UI 测试覆盖语义、触控区域和导航状态。
- Macrobenchmark 对比移除 Haze 前后的滚动帧时间与功耗。
- 删除旧 Material token、字符串、设置项和不可达分支。

## 9. 审查清单

每个 UI PR 必须回答：

- 组件属于哪一种风格？
- Material 路径是否完全没有 Haze/Backdrop？
- 是否优先使用官方 Material 3 组件？
- container 与 on-color 是否匹配？
- 透明度是否属于本规范允许的例外？
- 是否使用共享 shape、spacing、motion token？
- 深色模式关键文字是否保持足够对比？
- 触控目标是否至少 `48dp`？
- 是否适配窗口尺寸和系统 Insets？
- Reduced motion 下是否合理降级？
- 是否新增了第三套风格分支或复制业务逻辑？

## 10. 当前已知差距

- `InterfaceStyle` 当前仍有三项。
- `GlassSurface` 已收敛为 iOS Backdrop 与 Material 稳定 Surface 两条路径。
- 欢迎页、阅读器和主界面已移除 Haze state/source；Backdrop 只在 iOS 同窗口宿主创建。
- `KototoroTheme` 仍通过布尔 CompositionLocal 区分 Expressive 组件。
- Material 与 Expressive token 并存，造成三套几何参数。
- 部分页面用玻璃效果表达本应由 surface container 表达的层级。
- 当前主题尚未统一接入官方 `MotionScheme`。

上述差距是后续实现工作的任务清单，不应继续扩大。

## 11. Miuix 参考边界

Miuix 是社区维护的 Compose Multiplatform UI 库，并非 Google Material 规范，也不能视为小米官方
设计规范的权威实现。其仓库明确标记为实验性，API 可能随时变化。因此 Kototoro 只把它作为组件
工程和交互密度参考，不引入 Miuix 依赖，也不增加“Miuix 风格”。

### 11.1 值得借鉴

#### 设置页的信息结构

Miuix 将 preference 作为独立组件模块维护，说明设置界面应有稳定且统一的组件语法。Kototoro
应建立自己的 Compose 设置组件层：

- `SettingsPage`
- `SettingsSection`
- `SettingsItem`
- `SwitchSettingsItem`
- `SelectSettingsItem`
- `SettingsNavigationItem`

这些组件统一管理标题、摘要、尾部控件、禁用态、分组间距和无障碍语义。业务设置页只描述内容，
不再自行组合 Row、Surface、Divider 和 padding。

#### 紧凑但可触达

可借鉴 Miuix 的高信息密度和清晰分组，但必须遵守 Android 最小触控目标：

- 视觉内容可以紧凑；
- 点击区域维持至少 `48dp`；
- 设置项不为每一行增加独立厚重卡片；
- 分组通过外部留白、标题和共同容器表达；
- 摘要为空时自动收紧高度，不能保留空白占位。

#### 主题控制器的集中化

Miuix 通过统一 Theme/ThemeController 管理明暗模式、Monet 和种子色。Kototoro 不复制其 API，
但采用同一工程思想：

- 所有风格选择在 `KototoroTheme` 解析；
- 页面不直接读取壁纸色或构建自己的 ColorScheme；
- dynamic color、品牌 fallback、深色模式和对比度策略集中测试；
- 主题值通过 `MaterialTheme` 与极少量项目 CompositionLocal 下发。

#### 平滑圆角

Miuix 将 squircle 作为独立能力，说明平滑圆角应是 shape token，而不是散落的 Canvas 实现。
Kototoro 可以在 Hero、封面和大型浮层中评估连续圆角，但：

- MD3 Expressive 默认仍优先使用官方 `Shapes`；
- 仅当视觉对比测试证明普通 RoundedCornerShape 不足时引入共享 smooth shape；
- 禁止每个卡片都使用 squircle；
- 不为此新增第三方依赖，优先复用 Compose 已有 Path/Shape 能力。

#### Overlay 宿主

Miuix Scaffold 为 overlay、popup 和 dialog 提供统一宿主。这与 Kototoro 已有 root overlay 思路一致。
后续应统一菜单、选择器和浮层的宿主与坐标空间，避免每个页面分别解决：

- Popup 跨窗口材质问题；
- Insets；
- 返回键；
- scrim；
- 焦点和无障碍；
- 动画与 z-order。

### 11.2 不应借鉴

- 不引入 `miuix-ui` 或 `miuix-preference` 取代 Material 3。
- 不新增 Miuix 主题或第三个 `InterfaceStyle`。
- 不采用其 blur 模块；MD3 路径的无模糊原则保持不变。
- 不照搬小米式大标题、开关造型、弹窗或导航外观。
- 不用弹性动画覆盖所有点击；高频工具操作仍使用 Material standard/fast motion。
- 不使用品牌无关的固定蓝色、固定灰阶或固定圆角。
- 不因 Miuix 支持跨平台而把当前 Android UI 抽象成不必要的 KMP 层。

### 11.3 对 MD3 Expressive 规范的补充

结合 Miuix 的优点，Kototoro 的 MD3 Expressive 应体现为：

1. **Google Material 3 作为语义和组件基础。**
2. **Miuix 式的信息密度与集中组件工程。**
3. **Kototoro 自身的媒体内容层级与阅读优先体验。**

建议设置页视觉模型：

- 页面使用 `surface` 背景；
- 顶栏滚动后使用克制的半透明 `surfaceContainer`，不模糊；
- 分组标题置于共同容器外；
- 分组容器使用 `surfaceContainerLow`；
- 行之间默认不画全宽 Divider，仅在语义分区必要时使用；
- 单行视觉高度目标 `52–56dp`，双行目标 `64–72dp`；
- 尾部开关或箭头对齐主标题视觉中心；
- 页面水平 padding `16dp`，组间距 `16–20dp`；
- 大屏限制内容最大宽度，避免设置项横跨整个窗口。

Miuix 的参考价值最终应沉淀为 Kototoro 自有 token 和设置组件，而不是运行时依赖。

## 12. Material 3 Expressive 具体策略

本节将官方“颜色、形状、尺寸、动效和 containment”原则落实到 Kototoro 组件。Expressive
的目标是让关键动作更快被发现，而不是让所有元素更大、更圆或持续运动。Google 的研究表明，
合理使用这些手段能显著加快关键元素的识别；同时也明确指出，不熟悉的新形式会带来学习成本。

补充参考：

- [Google 对 M3 Expressive 的 UX 研究](https://design.google/library/expressive-material-design-google-research)
- [Inside M3 Expressive](https://design.google/library/design-notes-material-3-expressive-liam-spradlin)
- [Compose Material components](https://developer.android.com/develop/ui/compose/components)
- [Compose Carousel](https://developer.android.com/develop/ui/compose/components/carousel)
- [Compose ButtonGroup API](https://developer.android.com/reference/kotlin/androidx/compose/material3/ButtonGroup.composable)
- [Compose LoadingIndicator API](https://developer.android.com/reference/kotlin/androidx/compose/material3/LoadingIndicator.composable)
- [Compose FloatingToolbar API](https://developer.android.com/reference/kotlin/androidx/compose/material3/FloatingToolbarDefaults)
- [Compose SearchBar](https://developer.android.com/develop/ui/compose/components/search-bar)

### 12.1 表现力预算

每个屏幕只允许一个主表现焦点：

- 主页：继续阅读 Hero 或主内容区；
- 详情页：作品封面、标题与主动作组合；
- 阅读器：当前内容本身，工具栏必须退后；
- 设置页：页面标题和当前分组，不设置 Hero；
- 空状态：插图或主要恢复动作。

一个焦点最多组合三项表达手段，例如“更大尺寸 + accent container + expressive motion”。
不得再叠加高 elevation、粗边框、渐变、模糊和持续动画。

### 12.2 组件采用矩阵

| 官方组件/能力 | Kototoro 用途 | 限制 |
|---|---|---|
| `LargeTopAppBar` | 一级浏览页的大标题 | 设置、阅读器、详情页内层页面不用 |
| `TopAppBar` | 设置、管理、搜索结果、阅读器显式控制 | 滚动后仅克制 surface tint |
| `SearchBar` | 搜索是页面首要任务时 | 普通页面只使用搜索 action，不常驻大搜索框 |
| `HorizontalUncontainedCarousel` | 继续阅读、最近更新 | 项目同宽，尾项露出提示可横滑 |
| `HorizontalMultiBrowseCarousel` | 封面比例接近、数量较多的推荐 | 不用于严格排序或需要快速纵向扫描的列表 |
| Hero Carousel | 单个重点推荐及下一项预告 | 首页最多一个，不自动轮播 |
| `ButtonGroup` | 2–4 个同级、紧邻的阅读器/筛选动作 | API 稳定且交互测试通过后采用；避免所有按钮都伸缩 |
| `FloatingToolbar` | 图片/漫画阅读器的临时工具集合 | 只在控制层显示，不能常驻遮挡内容 |
| `LoadingIndicator` | 首次导入、模型处理、显著长任务 | 普通分页和列表刷新仍用低干扰进度；Reduced motion 降级 |
| `ExtendedFloatingActionButton` | 文本能消除歧义的唯一主动作 | 滚动时可收缩；同屏不得再有第二个 FAB |
| `NavigationBar/Rail` | 一级目的地 | 根据窗口尺寸切换，不自行缩放坐标 |
| `SegmentedButton` | 少量互斥显示模式 | 不替代普通筛选 chip，不超过 3–4 项 |

### 12.3 Carousel 的媒体规则

Kototoro 是封面驱动产品，但不能把所有横向列表都改成 Expressive Carousel：

- “继续阅读”使用 Uncontained Carousel：保持稳定封面尺寸和明确阅读进度。
- “为你推荐”可使用 Multi-browse：允许大小节奏变化，但标题必须在稳定区域显示。
- 单一重点内容才使用 Hero Carousel，并显示下一项的一小部分作为可滑动提示。
- 收藏、历史、下载和搜索结果使用标准 Lazy grid/list，保证扫描和排序效率。
- 禁止自动轮播；禁止让当前阅读进度因卡片变形而跳动。
- Carousel shape 使用主题 shape，图片通过 `maskClip` 裁切，不额外包多层 Surface。

### 12.4 ButtonGroup 与工具栏

`ButtonGroup` 的触压扩展能强化反馈，但只适合紧密相关、同一高度的动作。Kototoro 采用条件：

- 最多 4 个可见动作，多余项进入 overflow；
- 按钮触压扩展不能引起外层容器宽度变化；
- 危险动作不与高频动作组成会移动的组；
- 阅读器中的章节、显示、工具可组成稳定组，进度条不属于按钮组；
- TalkBack、Switch Access 和大字体下必须退化为不变形的标准按钮；
- Reduced motion 下关闭宽度伸缩。

`FloatingToolbar` 仅适合临时阅读控制。主页导航、设置操作和固定底栏不得为了新颖改成悬浮工具条。

### 12.5 Expressive Loading

形状变形的 `LoadingIndicator` 是高注意力组件：

- 仅用于用户明确发起、需要等待且当前页面被阻塞的任务；
- 列表首屏骨架、分页、封面加载不使用；
- 与明确文字状态组合，例如“正在准备离线内容”；
- 超过 10 秒时提供阶段、取消或后台运行入口；
- Reduced motion 下改为普通 determinate/indeterminate indicator；
- 不在多个卡片中同时播放。

### 12.6 动效与触觉

- 进入 Hero、展开主容器：expressive spatial。
- 颜色和选中态：fast/default effects。
- 列表插入、筛选和设置开关：standard motion。
- 拖拽越界、吸附、完成切换可使用一次轻触觉反馈。
- 普通点击不同时叠加缩放、弹簧和触觉。
- 返回手势必须与 Predictive Back 协同，不创建反向竞争动画。

## 13. 阅读器设计规范

Google Play Books 是可靠的功能结构参考，而不是需要逐像素复制的视觉模板。其官方说明确认：

- 用户通过轻触阅读页显示阅读统计和控制；
- 页码选择和快速翻页使用底部滑杆；
- 文字设置包含字体、字号、行距和对齐；
- 页面布局支持自动、单页和双页；
- 照明设置包含亮度以及亮色、棕褐、深色阅读主题；
- 漫画和 Manga 提供专门的沉浸阅读能力；
- 新的阅读辅助工具位于阅读器顶栏或文本选择上下文中，而不是永久占据阅读画布。

参考：

- [Google Play Books 官方应用页](https://play.google.com/store/apps/details?id=com.google.android.apps.books)
- [Google Play Books：字体、颜色、布局与阅读进度](https://support.google.com/googleplay/answer/9755756?co=GENIE.Platform%3DAndroid&hl=en)
- [Google Play Books Book insights](https://blog.google/products-and-platforms/platforms/google-play/book-insights/)

### 13.1 阅读器的视觉优先级

1. 内容页、漫画图像或视频画面；
2. 当前阅读位置；
3. 必要导航；
4. 显示与辅助工具；
5. 低频设置。

控制层默认隐藏。用户轻触中央区域后，顶部栏、紧凑进度区和底部操作组作为一个状态同时出现。
再次轻触内容或经过合理空闲时间后隐藏。展开 Sheet 时控制层保持可预测，不自动切换到另一套工具栏。

### 13.2 小说阅读器

#### 顶栏

- 返回；
- 单行书名或章节名；
- 书签；
- 最多一个上下文工具入口；
- 其余动作进入 overflow。

顶栏不显示阅读百分比、来源、作者和多行副标题。当前章节信息优先在进度区域表达。

#### 进度区

- 控制层显示期间常驻，不设置单独“进度”按钮；
- 滑杆占主要宽度；
- 左右章节切换是辅助动作，不能比滑杆更醒目；
- 拖动时显示页码/章节位置浮标；
- 未拖动时最多显示一个紧凑位置文本；
- 加载出最大值前，容器按底栏实际内容宽度自适应，不提前占满；
- 进度条和底部按钮属于同一视觉容器，但不是同一个交互组。

#### 显示设置

按三组组织，避免长表单：

1. **文字**：字号、字体、字重（若支持）、行距、段距、对齐；
2. **页面**：边距、滚动/翻页、自动/单页/双页；
3. **照明**：亮色、棕褐、深色、跟随系统、亮度覆盖。

首层只显示最常用控制；高级排版进入二级页面。主题预览使用小型真实文本样本，不使用纯色圆点代替。

### 13.3 漫画阅读器

- 图像始终是唯一 Hero，控制容器不能使用大面积不透明背景；
- 顶栏只保留返回、标题/章节和必要动作；
- 进度区常驻于显式控制状态，默认阅读状态隐藏；
- 页面模式、单双页、裁剪、方向和图像处理归入“显示”；
- OCR/翻译归入“工具”，不能占据主工具栏的最大视觉权重；
- 长条漫画优先显示章节内位置，而非模拟离散纸张页码；
- 双页模式必须由窗口宽度、方向和折叠状态共同决定；
- 漫画辅助放大可以参考 Play Books Bubble Zoom 的“按需增强”，但不能自动改变原图或持续遮挡。

### 13.4 动画/视频阅读器

- 播放画面是唯一 Hero；
- 轻触显示控制，空闲后隐藏；
- 时间进度条常驻于显式控制状态；
- 播放/暂停是最高优先级，剧集、字幕、清晰度属于次级层；
- Space 切换侧边把手与播放器控制不能争夺同一屏幕边缘区域；
- 任何 Expressive 动效不得增加首次播放延迟或影响解码线程。

### 13.5 阅读辅助与 AI

Google Play Books 将 Book insights 放在顶栏灯泡入口和文本选择上下文中，这说明阅读辅助应由阅读行为触发：

- 全局入口命名为“工具”或具体能力，不使用笼统“AI”标题；
- 选中文字后提供解释、翻译、搜索等上下文动作；
- “继续上次内容/回顾”可在重新进入作品时出现一次，不常驻；
- 回答只基于已读位置时必须明确范围，避免剧透；
- 生成任务必须展示来源、状态、失败和取消；
- AI 能力不能挤占书签、章节和显示这些基础阅读操作。

### 13.6 大屏与折叠屏

- 小说正文设置可读最大行宽，不随窗口无限拉伸；
- 展开窗口优先双页或正文 + 辅助 pane，而不是放大字号填满；
- 漫画双页尊重封面单页和阅读方向；
- 章节列表在中等/展开窗口可使用 supporting pane；
- 阅读设置使用 side sheet/pane，避免覆盖全部内容；
- 窗口改变时保留章节、页码、滚动锚点和面板状态。

### 13.7 阅读器验收清单

- 无控制状态下是否只剩内容？
- 轻触是否一次显示完整且稳定的控制层？
- 是否无需额外按钮即可看到和拖动进度？
- 顶栏是否不超过两个直接 action？
- 小说显示设置是否按文字、页面、照明分组？
- 漫画工具是否没有遮挡主体图像？
- 深色阅读主题是否独立保证正文对比度？
- 大字体下底栏是否仍可理解和操作？
- 横屏、双页和折叠状态切换是否保持当前位置？
- Reduced motion 下是否仍有完整状态反馈？
