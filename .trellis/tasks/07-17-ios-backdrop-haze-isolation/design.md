# 技术设计：玻璃前景、沉浸顶边与全局 Space 覆盖层

## 边界

本次扩展只调整 Compose UI 的共享视觉和覆盖层归属，不修改列表数据、详情业务、Space 状态机或导航快照。

## 设计

1. `TopBarControlSurface` 和 `RootGlassMenuOverlay` 的 Backdrop 分支显式提供 `LocalContentColor = onSurface`。调用方继续可以通过更内层的 `CompositionLocalProvider` 或显式 `tint/color` 覆盖。
2. 作品列表的返回/操作容器及标签胶囊复用 `TopBarControlSurface`。列表内容层使用 `LayerBackdrop` Source，顶栏作为后续兄弟节点消费；若上层没有提供 Source，则页面创建同窗口备用 `LayerBackdrop`。
3. 顶边渐变透明度使用 `max(contentScrollAlpha, chromeAlpha)`。它保留内容滚入顶边后的保护层，也保证 Chrome 重新出现时保护层不会消失。计算下沉为纯函数并覆盖单元测试。
4. 实际 Space FAB 和唯一 `RootGlassMenuHost` 由 `KototoroApp` 全局覆盖层持有。所有路由都使用同窗根层锚定菜单，不再为沉浸路由创建 `ModalBottomSheet` 分支。
5. 每个导航 BackStackEntry 创建独立 `LayerBackdrop` 并在成为当前路由时注册到应用级 Active Backdrop Host。目标路由即使只绘制加载态，也先注册自己的空白/加载 Source，避免消费者沿用离场路由缓存。
6. 详情页使用两层不同的 Source：内部背景 Source 供详情页自身顶栏消费；路由 Source 记录已经合成的背景、滚动内容和静态控件，供路由外的 Space FAB 与根层菜单消费。两者不得互相自采样。

## 兼容性

- Material/Haze 路径继续由 `TopBarControlSurface` 和统一 Haze 门控负责。
- iOS Backdrop Source 与消费者保持兄弟关系。
- Space FAB 的目标偏移、隐藏条件、尺寸和动效不变，只改变渲染作用域。
- 根层菜单请求携带打开时的 Backdrop，不依赖 Overlay 所在组合树恰好继承某个页面 Source。
