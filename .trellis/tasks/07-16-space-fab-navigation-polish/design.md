# 技术设计：Space FAB 与主导航栏自适应及视觉打磨

## 1. 设计目标

保持当前 Space FAB 的空间语义：主界面位于底部导航右侧，列表/详情位于右下动作区，阅读器/播放器位于底部控制层上方。改动只解决三个问题：

1. FAB 显示时主导航不再因为硬编码上限被压缩为 4 项。
2. 窄屏下导航栏、FAB、系统 inset 和标签不会互相挤压或裁剪。
3. Compose 页面和传统 View 页面拥有一致的 Space 入口视觉层级。

## 2. 当前布局边界

```text
KototoroApp
  ├─ MainBottomChrome
  │   └─ KototoroBottomNav
  │       ├─ activeItems.limitMainNavigationItems()
  │       └─ adjacentAction = SpaceSwitcherFab
  └─ SpaceSwitcherFab

ReaderActivity / VideoPlayerActivity
  └─ XML ExtendedFloatingActionButton
      └─ SpaceSwitcherDelegate
```

- `NavItem.kt` 的 `MAX_MAIN_NAV_ITEM_COUNT` 当前为 4，需要恢复为 5。
- `KototoroBottomNav` 的浮动模式将导航栏和 `adjacentAction` 放在同一水平 Row，但 `floatingMinWidth` 只按导航项计算，没有把 FAB 和间距纳入布局预算。
- 主界面 FAB 的锚点已通过 `mainSpaceSwitcherFabBounds` 与导航栏同步；详情页已有底部遮挡量和展开隐藏策略。
- 播放器已经根据 `controlBar` 高度更新 FAB 的 `bottomMargin`；阅读器需要补齐对底部工具栏及右下辅助控件的避让。

## 3. 主导航自适应策略

### 3.1 布局预算

浮动导航模式使用可用宽度预算：

```text
available = windowWidth - systemInsets - outerPadding
fabBudget = fabWidth + fabGap
navBudget = available - fabBudget
```

导航栏始终渲染最多 5 个已配置项，不通过 `take(4)` 静默丢弃用户配置。根据 `navBudget` 选择布局密度：

| 模式 | 标签 | 间距/留白 | 触摸目标 |
| --- | --- | --- | --- |
| regular | 遵循现有标签设置 | 现有值 | ≥48dp |
| compact | 隐藏未选中标签，必要时隐藏选中标签 | 收紧 | ≥48dp |
| minimum | 仅图标 | 最小安全留白 | ≥48dp |

实际计算由可测试的纯布局解析函数负责；Composable 只负责将结果映射到 `Modifier` 和导航项参数。布局压缩不得使用整体 `scale`，避免视觉尺寸和语义点击区域一起缩小。

### 3.2 稳定性

- 使用 `BoxWithConstraints` 或等价的实际布局约束获取可用宽度，不使用设备型号或固定屏幕宽度判断。
- 导航栏容器默认按实际内容宽度包裹，不用 `weight` 填满剩余空间；仅在布局规格判定需要时通过间距、留白和标签降级压缩内容。
- FAB 和导航栏使用同一份布局规格并一起动画，避免导航栏先缩放、FAB 后跳位。
- 窄屏切换模式时增加有限的滞回或使用离散阈值，避免横竖屏/窗口微小变化导致标签反复闪烁。
- 平板/横屏 `NavigationRail` 仍保留最多 5 项；其纵向 `LazyColumn` 不需要应用底部 FAB 的水平预算。

## 4. FAB 视觉设计

### 4.1 Compose 页面

扩展现有 `SpaceSwitcherFab`，使用 `GlassSurface` 作为圆形容器：

- 使用 `GlassComponentRole.Surface` 或新增专用 FAB role，避免复用 BottomBar 的低对比度样式。
- FAB 使用主题强调色/`primaryContainer` 色调，导航栏使用中性 surface 色调。
- FAB 边框和阴影层级高于导航栏，但不使用完全不透明的实色板。
- 保持当前 Space 图标/自定义 Space monogram、content description 和 48dp 以上触摸区域。

该实现复用项目现有 Haze 配置、降级和明暗主题处理，不在 Space FAB 内直接重复创建 `HazeState`。

### 4.2 阅读器和播放器

阅读器/播放器当前由 XML `ExtendedFloatingActionButton` 承载，继续保留 View 层结构。使用与 Compose FAB 相同的设计 token：背景色、前景色、圆角/圆形、边框 alpha、elevation 和尺寸；在不具备 Compose Haze source 的场景下使用半透明材质 fallback。

## 5. 页面锚点与遮挡

- 主界面/作品列表：FAB 与底部导航右侧保持固定间距；底部导航隐藏时同步隐藏或使用同一 chrome offset，不让 FAB 单独悬浮在内容卡片上。
- 详情页：沿用 `detailsBottomObstruction`，底部操作面板展开时隐藏 FAB；收起后恢复到右下动作区。
- 播放器：沿用 `controlBar.height + space_switcher_fab_control_gap` 的 bottom margin，并保留 controls 隐藏时的同步隐藏。
- 阅读器：根据 `toolbar_docked` 高度及 `zoomControl`、`timerControl` 的可见状态计算右下避让距离；控制层隐藏时通过现有 delegate transition 一起隐藏。

## 6. 数据流和兼容性

本任务不修改 Space 数据模型、切换事务、Space 面板或导航状态持久化。仅调整：

```text
window constraints / control visibility
→ layout spec / obstruction distance
→ nav + FAB placement and visual variant
```

旧设备或未启用玻璃效果时，`GlassSurface` 和传统 View 均使用现有 fallback 色彩，不改变交互和可访问性。

## 7. 验证与回滚

- 纯布局规格覆盖 4/5 项、FAB 有无、regular/compact/minimum、窄屏和宽屏。
- Compose 编译与 JVM 单测覆盖导航上限和布局分支。
- 手工验证主界面、列表、详情、阅读器、播放器的显示/隐藏、旋转、系统 inset 和底部控件重叠。
- 若 Haze 样式影响性能或低版本显示，回滚到同 token 的半透明 Material 容器，不回滚导航数量和锚点逻辑。
