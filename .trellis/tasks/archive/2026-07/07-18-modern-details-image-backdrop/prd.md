# Modern 详情页背景图片 Backdrop

## 目标

在 modern 风格详情页中，让底部 dock 和展开的详情内容面板使用详情页背景/全景封面作为 Backdrop source，从而让玻璃面板呈现实际作品背景图片，而不是只采样主题背景色。

## 已确认事实

- modern 详情 dock 由 `DetailsDockContainer` 和 `GlassBottomBarContainer` 渲染，入口位于 `app/src/main/kotlin/org/skepsun/kototoro/details/ui/compose/DetailsScreen.kt`。
- 展开的详情面板使用 `GlassSurface`，modern dock 与面板都在详情页内消费 `LocalLiquidGlassBackdrop`。
- `DetailsActivity` 当前通过 `rememberLayerBackdrop` 仅绘制 `MaterialTheme.colorScheme.background` 后再绘制内容；该根 source 不包含详情背景图片。
- `DetailsScreen` 已创建 `detailsBackgroundBackdrop`，并在背景/全景图层上使用 `layerBackdrop`，同时将该 backdrop 提供给详情页玻璃组件。
- 现有设计要求 Backdrop source 不包含 `drawBackdrop` 消费者，避免递归采样和 RenderThread 风险；此前详情页采样边界已专门修复。

## 需求

- R1：modern 详情底部 dock 的背景采样使用详情页背景/全景封面内容。
- R2：modern 详情展开的章节/页面面板的背景采样使用同一详情页背景/全景封面内容。
- R3：保留非 modern 风格、非 iOS 风格、无封面、无 Backdrop source 时的现有回退行为。
- R4：不改变玻璃效果参数、底栏交互、面板展开状态、详情转场和背景图片加载策略。
- R5：保持 source 与消费者分离，不能把底栏或面板自身重新纳入被采样内容。

## 验收标准

- [x] 开启 modern 详情 dock 并使用有封面的详情页时，底栏玻璃能看到对应背景图片的采样内容。
- [x] 展开章节/页面详情面板时，面板玻璃能看到同一详情背景/全景图的采样内容，且不会出现自采样、旧帧或明显空白色块。
- [x] 切换到普通详情底栏、非 iOS 风格或没有可用背景时，现有外观和功能保持不变。
- [x] 详情页编译检查和 `git diff --check` 通过。

## 非目标

- 不修改背景图片来源、全景图位置、模糊半径或图片加载缓存。
- 不修改 Backdrop/Haze 依赖版本或全局玻璃组件材质参数。
- 不扩展到搜索页、主页或其他详情页弹窗。
