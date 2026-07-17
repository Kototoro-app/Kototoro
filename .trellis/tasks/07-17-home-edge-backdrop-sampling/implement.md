# 实施计划

## 1. 修复主页横向视口约束

- [x] 在修改前再次确认 `HomeScreen.kt` 的现有未提交差异，只接管 `extendHorizontalViewport` 及其两个调用点附近的重叠代码。
- [x] 调整 `extendHorizontalViewport`：有界宽度下内部测量扩大后的 LazyRow，但向父级报告原 viewport 宽度并对称负向放置；无界宽度保持安全退化。
- [x] 将网格与列表分页分支的 `contentPadding.start` 从双倍扩展修正为单倍扩展，保留 `end = 0.dp`、现有 `Arrangement` 和 snap 配置。
- [x] 核对 LTR/RTL 使用 `placeRelative` 与 start/end padding，不引入绝对坐标。

## 2. 隔离详情页 Backdrop 源

- [x] 从 `DetailsActivity` 移除包裹整个 `DetailsScreen` 的 `Box.layerBackdrop`，保留 Backdrop 创建和两个 CompositionLocal provider。
- [x] 在 `DetailsScreen` 读取 `LocalLiquidGlassLayerBackdrop` 与当前界面风格。
- [x] 在已有背景/全景图 Box 上按 iOS + 非空 Backdrop 条件挂载 `layerBackdrop`，并保留同节点的 Haze source。
- [x] 确认 `LiquidGlassSurface`、详情顶栏与现代 dock 均位于源节点之后的兄弟/后续绘制层，现有 `exportedBackdrop` 未被移除。

## 3. 验证与评审

- [x] 为 `CompactMenuContent` 增加最大固有宽度约束，并让 `CompactDropdownMenuItem` 填满该宽度。
- [x] 调整条目 Modifier 顺序，使 `clickable` 覆盖水平内边距及右侧留白，不改变回调与视觉间距。
- [x] 核对普通 Popup 固定宽度与 iOS 根浮层自适应宽度均受原有父约束控制。

- [x] 搜索详情范围内所有 `layerBackdrop`/`drawBackdrop` 调用，确认不存在新的自包含采样层或 Popup 跨 Window 采样。
- [x] 运行 `./gradlew :app:compileDebugKotlin`。
- [x] 如仓库存在相关快速测试则运行；当前未发现主页/详情 Backdrop 的 Compose UI 测试。
- [x] 运行 `git diff --check`。
- [x] 审阅完整 diff，区分本任务修改与用户原有 `GlassDropdownMenu.kt` 修改，不改写或回退无关内容。

## 验证结果

- `./gradlew :app:compileDebugKotlin`：通过。
- `git diff --check`：通过。
- 菜单命中区域修复后再次运行 `./gradlew :app:compileDebugKotlin` 与 `git diff --check`：通过。
- `./gradlew :app:testDebugUnitTest`：840 项中 839 项通过；既有 `ContentListSourceGateViewModelTest` 在全量套件中协程超时。
- `./gradlew :app:testDebugUnitTest --tests '*ContentListSourceGateViewModelTest*'`：单独重跑通过，确认失败为测试间时序波动且与本任务 UI 文件无依赖。
- 当前连接了 Android 设备，但未在未获授权的情况下安装/覆盖设备上的应用；主页边缘与 iOS Backdrop 的最终视觉表现保留设备人工验收。

## 风险点与回滚

- `HomeScreen.kt` 已有用户未提交修改：只在同一 helper 内做兼容修正，并在最终交付中明确说明合并结果。
- 若首页边缘仍不对称，优先检查 Modifier 顺序和父级 clip，不通过继续增加 `contentPadding` 掩盖约束错误。
- 若详情 Backdrop 无内容，检查背景源节点是否实际有尺寸、是否先于效果层绘制，以及 `LayerBackdrop` CompositionLocal 是否一致；不创建新的嵌套 Backdrop。
- 回滚边界为 `HomeScreen.kt` 的视口 helper/两处 padding，以及 `DetailsActivity.kt`、`DetailsScreen.kt` 的 Backdrop 源挂载位置。
