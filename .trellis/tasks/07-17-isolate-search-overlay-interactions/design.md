# 技术设计

## 边界

变更限定在主壳搜索 Overlay、顶栏玻璃采样开关和主壳 nested scroll 连接。搜索数据、结果导航、筛选状态和现有转场不变。

## 方案

1. 在 `KototoroSearchOverlay` 的全屏 scrim 上增加输入屏障。该节点必须具备 pointer handler，并消费所有落在搜索层空白区域的指针事件；搜索内容列位于更高层级，继续接收自己的点击和滚动。
2. 在 `TopBarControlSurface` 中区分“运行时 Haze/Backdrop 采样允许”和“只使用静态表面”。搜索顶栏传入禁用采样参数后，iOS 分支不再读取主界面共享 Backdrop。
3. 在主壳 `NestedScrollConnection` 中，搜索层挂载时提前返回，不更新主壳滚动累计状态；搜索列表自身的 LazyColumn 继续使用自己的滚动机制。
4. 动态背景搜索层保持不透明面板和高对比颜色，避免透出主内容；不创建新的 `LayerBackdrop`，防止嵌套采样循环和 RenderThread 崩溃。

## 取舍

- 使用同窗 Overlay 而非 Dialog：保留现有展开/收起动画和搜索状态，修复真正的交互隔离问题。
- 使用全屏 pointer barrier 而非仅增加 `clickable` 到卡片：空白区域也必须阻断拖拽和滚动，不能只阻断点击。
- 搜索顶栏采用静态材质而非主界面采样：搜索页的正确性和层级隔离优先于透视效果。

## 风险与回滚

- 输入屏障如果层级高于搜索内容，会阻断搜索列表；通过兄弟节点 z-order 保证 scrim 在前、搜索面板/列表在后。
- 如果某些手势仍驱动主壳，检查 pointer consumption 和 nested scroll early return，而不是重新创建 Haze/Backdrop。
- 回滚限于 `KototoroSearchOverlay.kt`、`KototoroTopBar.kt`、`KototoroApp.kt`。
