# 修复主页横向列表边缘与详情页 Backdrop 采样

## Goal

修复主页“历史、更新、推荐”横向内容轨道右侧被父级内容边距截断的问题，让 iOS 风格详情页的 Backdrop 玻璃组件从明确、稳定且不包含自身的内容层采样，并恢复主界面更多菜单条目的整行点击区域。

## Background

- 主页主体 `Column` 在左右应用系统栏边距和 `CompactTopBarHorizontalPadding`；三个内容轨道位于该内缩内容区内。
- `HomeContentRowSection` 当前通过 `extendHorizontalViewport` 把 `LazyRow` 测量宽度扩大两侧各一个内容边距，但该修饰符向父布局报告超出传入约束的宽度；现有未提交修改仍保留这一约束冲突，表现为右侧扩展被裁切或偏移。
- Compose 官方 Lazy list 文档规定：`contentPadding` 作用于列表内容而非容器本身，因此应由列表视口负责延伸、由 `contentPadding` 负责首尾内容锚点。
- 详情页 Activity 创建并提供 `LayerBackdrop`，但当前把 `layerBackdrop` 挂在包含整个 `DetailsScreen` 的根 `Box` 上；其中同时包含 `drawBackdrop` 消费者。
- Backdrop 官方文档规定 `rememberLayerBackdrop` 必须由 `Modifier.layerBackdrop` 或 `drawBackdrop(exportedBackdrop=...)` 提供绘制源，且 `LayerBackdrop` 依赖源节点与消费节点的坐标。主界面现有实现已将采样内容层与玻璃 Chrome 作为兄弟节点隔离，详情页尚未遵循这一边界。
- 相关任务 `07-17-isolate-search-overlay-interactions` 已要求详情页基本信息面板使用 Backdrop，并使用官方 `exportedBackdrop` 机制避免把玻璃内容递归纳入采样；本任务只修复新暴露的采样源边界，不重新设计材质或效果参数。

## Requirements

- R1：主页历史、更新、推荐三类横向轨道的可绘制和可滚动视口必须同时延伸到左右屏幕内容边缘，不得只向左延伸或在右侧被截断。
- R2：横向轨道静止时首项继续与分区标题的内容起始线对齐；滚动到末尾时最后一项可到达右侧屏幕内容边缘。
- R3：网格、紧凑网格、列表、详细列表四种主页显示模式必须共用同一套边缘延伸语义，保留现有间距、吸附、稳定 key 与滚动动画行为。
- R4：自定义布局修饰符不得向父布局报告违反传入约束的尺寸；扩大后的 `LazyRow` 只改变内部视口和放置位置，不改变父级占位宽度。
- R5：iOS 风格详情页必须在实际背景/全景图内容层上注册 `layerBackdrop` 源，Backdrop 消费组件不得被再次纳入同一个采样层。
- R6：详情页现有 `LocalLiquidGlassBackdrop`/`LocalLiquidGlassLayerBackdrop` 传递、`exportedBackdrop`、Haze 非 iOS 路径和无 Backdrop 时的安全回退必须保留。
- R7：不调整 Backdrop/Haze 依赖版本，不扩展到弹窗跨 Window 采样，也不改变玻璃材质参数。
- R8：保留用户在 `HomeScreen.kt` 与 `GlassDropdownMenu.kt` 中已有的未提交修改；仅在与本任务重叠的代码上进行必要的兼容修正。
- R9：主界面更多菜单中的每个条目必须让面板宽度内的整行（包括文字/图标右侧留白和水平内边距）响应点击，同时保持菜单按内容自适应宽度，不因条目填充而无条件扩展到根浮层的最大宽度。

## Acceptance Criteria

- [ ] 主页历史、更新、推荐横向轨道在 LTR 下左右都可绘制到屏幕内容边缘，右侧不再被截断。
- [ ] 主页四种列表模式下，首项初始位置仍与标题起始线对齐，末项可滚动到右侧边缘，吸附位置无额外偏移。
- [x] `extendHorizontalViewport` 在有界宽度下向父级报告原约束内宽度，只在内部测量扩大后的子项并对称放置；无界宽度下保持安全退化。
- [ ] iOS 风格详情页的基本信息面板、顶栏控件与现代 dock 能从详情背景/全景图内容层采样，不再呈现仅有静态回退表面的观感。
- [x] 详情 Backdrop 源不包含上述 `drawBackdrop` 消费节点，避免递归采样与 RenderThread 风险。
- [x] Material 3/Haze 路径、非 iOS 样式、无采样源环境及详情页现有交互保持不变。
- [x] 主界面更多菜单的短文本条目与带图标条目均填满统一菜单宽度，点击右侧留白或水平内边距会触发对应操作。
- [x] `:app:compileDebugKotlin`、相关测试（若存在）和 `git diff --check` 通过。

## References

- Backdrop 官方文档：`LayerBackdrop` 必须关联 `layerBackdrop` 源且依赖坐标；玻璃叠加使用 `exportedBackdrop` 避免递归绘制。
- Android Developers Compose 官方文档：Lazy list 的 `contentPadding` 施加给内容而非列表容器，列表间距使用 `Arrangement.spacedBy`。
