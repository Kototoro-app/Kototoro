# 技术设计

## 设计边界

本任务只修复三个 UI 边界问题：

1. 主页三类横向内容轨道的视口测量与父级占位关系。
2. 独立 `DetailsActivity` 中 Backdrop 源节点与效果消费节点的绘制层级。
3. 主界面更多菜单的面板宽度与条目命中区域。

不调整数据流、列表内容、吸附算法、玻璃效果参数、Popup 采样策略或依赖版本。

## 更多菜单整行命中

### 当前问题

`CompactDropdownMenuItem` 移除 `fillMaxWidth()` 后，每个 `Row` 只按自身文字和图标宽度测量。外层 `Column` 和菜单面板会采用最宽条目的宽度，但较短条目不会被重新测量到该宽度，因此其右侧留白不属于 `clickable` 节点。

### 方案

- `CompactMenuContent` 使用 `IntrinsicSize.Max` 先按最宽条目的固有宽度确定统一内容宽度。
- 每个 `CompactDropdownMenuItem` 再使用 `fillMaxWidth()` 填满该统一宽度；在固定 192dp 的普通菜单中仍服从父约束，在 iOS 根浮层中保持按内容自适应且受 280dp 上限约束。
- Modifier 顺序采用 `fillMaxWidth -> clickable -> padding`，使水平内边距和文字/图标右侧留白都进入语义与指针命中区域。
- 点击回调、菜单关闭逻辑、图标布局和 40dp 紧凑高度保持不变。

## 主页横向轨道

### 当前问题

主页主体先通过 `Column.padding(horizontal = screen inset + content padding)` 缩小内容宽度。`LazyRow` 再由 `extendHorizontalViewport` 扩大测量宽度并向父级报告扩大后的尺寸。由于父级传入的是有界且通常为固定宽度的约束，子布局报告 `maxWidth + 2 * extension` 会违反父约束；Compose 会对表观尺寸进行约束修正，叠加内部负向放置后造成左右不对称，右侧扩展被截断。

### 方案

- 父级占位宽度始终保持原有受约束视口宽度。
- 在自定义 `layout` 内，仅把被测量的 `LazyRow` 宽度扩大为 `viewportWidth + 2 * extension`。
- 将扩大后的 `LazyRow` 按 start 方向负向放置一个 `extension`，使实际视口对称覆盖父内容区两侧。
- 把 Lazy list 的 `contentPadding.start` 设为一个 `extension`，让首项仍与标题起始线对齐；`end = 0.dp`，让末项能到达扩展后的右侧边缘。
- 网格与列表分页模式复用相同的 viewport/content-padding 组合，不引入第二套算法。
- 无界宽度时不强制扩展，保持原测量结果，避免制造无限宽或溢出。

该方案遵循 Compose 官方语义：`contentPadding` 只控制列表内容首尾空间，不负责扩大 Lazy list 容器本身；容器扩展由布局边界负责。[Lazy lists and lazy grids](https://developer.android.com/develop/ui/compose/lists#content-padding)

## 详情页 Backdrop 采样

### 当前问题

`DetailsActivity` 在包含整个 `DetailsScreen` 的根 `Box` 上调用 `layerBackdrop`。该子树既包含背景/全景图，也包含 `LiquidGlassSurface`、`TopBarControlSurface` 和 `GlassBottomBarContainer` 等 `drawBackdrop` 消费者。于是采样层会再次记录消费层，形成不清晰的自包含采样边界，并可能只显示旧帧、静态填充或反馈内容。

主界面已有正确先例：内容层持有 `layerBackdrop`，主壳 Chrome 与根菜单作为后绘制的兄弟节点消费同一 Backdrop。

### 方案

- `DetailsActivity` 只负责创建并通过 `LocalLiquidGlassBackdrop`/`LocalLiquidGlassLayerBackdrop` 提供同一个 `LayerBackdrop`，不再把整个 `DetailsScreen` 注册为源。
- `DetailsScreen` 在现有背景/全景图 `Box` 上注册 `layerBackdrop`。该节点本来就是 Haze 的 `hazeSource`，因此两套实现共享同一个“纯背景源、后续兄弟消费”的绘制边界。
- 仅在 iOS 风格且 CompositionLocal 中存在 `LayerBackdrop` 时注册该源，其他风格不增加 Backdrop 录制开销。
- 详情信息面板、顶栏与现代 dock 继续使用现有共享 Backdrop；现有 `exportedBackdrop` 保留，用于玻璃叠加，不创建嵌套源。

Backdrop 官方说明 `LayerBackdrop` 必须由 `Modifier.layerBackdrop` 或 `exportedBackdrop` 提供内容，且它依赖源与消费者坐标。[Backdrops API](https://kyant.gitbook.io/backdrop/api/backdrops) 官方教程也将 `layerBackdrop` 内容与玻璃效果作为兄弟层，并明确要求玻璃叠加通过 `exportedBackdrop` 避免绘制循环。[Glass Bottom Sheet](https://kyant.gitbook.io/backdrop/tutorials/glass-bottom-sheet)

Haze 路径采用同样原则：背景 source 先绘制，效果层后绘制，避免把 source 放在包含 effect 的父列表或父容器上。

## 兼容性与风险

- RTL：继续使用 `placeRelative` 和 `PaddingValues(start=...)`，保持 start/end 语义；扩展量对称，不引入绝对 left/right 偏移。
- 横屏/折叠屏：父级占位尺寸不改变，因此不会推挤相邻内容；实际 LazyRow 仍由有界 viewport 决定可见范围。
- 详情转场：不改变 Activity、`DetailsScreen` 参数或共享元素结构，只移动 Modifier 的采样挂载点。
- Haze：原 `hazeSource(detailsHazeState)` 保留在背景层，Material 3 路径不变。
- Backdrop：效果参数和 `exportedBackdrop` 不变；若局部源移动产生视觉回归，可单独回滚 Details 两处 modifier/import 变更。

## 验证策略

- 静态检查 `extendHorizontalViewport` 在有界约束下报告的宽度不超过父约束。
- 检查网格与列表分支使用一致的 start content padding。
- 检查详情页唯一根 `layerBackdrop` 挂载点位于不包含 `drawBackdrop` 消费者的背景/全景图层。
- 检查菜单内容先确定固有宽度、条目再填满该宽度，且 `clickable` 位于水平 padding 之前。
- 运行 `:app:compileDebugKotlin` 与 `git diff --check`；项目当前无覆盖这些私有 Compose 布局的 UI 测试，按现有质量规范不运行 lint。
