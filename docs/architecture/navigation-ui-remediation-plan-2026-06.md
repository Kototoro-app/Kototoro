# 导航 UI 与过渡架构整改计划

## 背景

近期围绕收藏页、作品列表页、详情页 hero 过渡与主界面 chrome 的排查表明，当前问题不只是某个页面卡顿，而是主导航 UI 架构在以下几个方面逐渐累积了复杂度：

- 顶部/底部 chrome、shared element、route transition、edge-to-edge 保护混在一个全局容器中协调。
- 收藏页、浏览页、发现页等主路由通过 `TopBarOverrideState` 把 tabs、filter rail、上下文操作逐步叠加到全局 top bar。
- 详情页进入时存在多套时序：NavHost route transition、hero 保护窗口、chrome alpha、自定义 delay。
- 顶部和底部栏的显隐、滚动折叠、系统栏 inset、玻璃效果、shared transition 叠层没有清晰边界。

这套实现可以工作，但已经偏离 Jetpack Compose / Material 3 官方推荐的职责分层，后续继续叠功能会进一步放大：

- 动画节奏不一致
- 页面特判增多
- 真实性能热点难定位
- 平板/折叠屏适配难度上升

## 目标

### 用户目标

- 主界面各顶级路由切换时，top/bottom chrome 节奏一致，不再“慢半拍”或突兀闪现。
- 列表页/收藏页进入详情页时，hero 过渡、route transition、chrome 显隐保持统一体验。
- 在手机、平板、折叠屏上，主导航布局遵循 Material 3 adaptive guidance，不再依赖过多手工布局分支。

### 工程目标

- 拆分 chrome、shared transition、system bars、页面 override 的职责边界。
- 用单一 timing contract 管理详情页导航与 chrome 时序。
- 逐步向 Material 3 scroll behavior、Insets、adaptive navigation 推荐模式靠拢。
- 优先执行低风险、高收益、可编译验证的改造。

## 官方文档对照

以下建议基于 Android 官方文档，而不是项目私有偏好：

### 1. Shared element 期间的浮层/栏位应通过 overlay 参与，而不是主要依靠全局 alpha 状态机

- 官方文档建议在 shared transition 期间，把需要保持在上层的 UI 放入 `renderInSharedTransitionScopeOverlay()`。
- 这比“在全局根节点里通过 `heroTransitionPhase + delay + alpha` 手工模拟叠层”更接近推荐实现。

参考：

- <https://developer.android.com/develop/ui/compose/animation/shared-elements/customize>

### 2. Top app bar 的滚动行为优先交给 Material 3 `TopAppBarScrollBehavior`

- `enterAlwaysScrollBehavior`
- `exitUntilCollapsedScrollBehavior`
- `pinnedScrollBehavior`

官方推荐通过 `nestedScroll(scrollBehavior.nestedScrollConnection)` 让 app bar 跟内容同步，而不是在全局容器里长期手工维护滚动 offset 和 alpha。

参考：

- <https://developer.android.com/develop/ui/compose/components/app-bars>

### 3. Insets 优先走 Material 3/Scaffold 语义，避免多重手工 padding

官方明确建议：

- `TopAppBar` / `BottomAppBar` / `NavigationRail` / `NavigationBar` 优先使用内建 inset 处理
- `Scaffold` 负责把 `innerPadding` 提供给内容
- 避免在使用 `Scaffold` 时再叠额外 inset 处理，否则容易出现过度 padding

参考：

- <https://developer.android.com/develop/ui/compose/system/material-insets>

### 4. 多个顶级目的地应各自管理 app bar 状态，而不是在全局容器内不断增加页面特判

官方 adaptive guidance 的方向是：

- 每个顶级 destination 自己声明需要什么 top bar / chrome 行为
- 全局导航容器负责承载，而不是推断页面意图

参考：

- <https://developer.android.com/develop/ui/compose/components/app-bars>
- <https://developer.android.com/jetpack/androidx/releases/compose-material3>

### 5. 顶级导航在不同窗口尺寸下，应优先向 Material 3 adaptive navigation 靠拢

当前 `KototoroBottomNav` 已经区分了手机和平板 rail，但整体仍是手工拼装。官方方向更接近：

- `NavigationBar`
- `NavigationRail`
- `NavigationSuiteScaffold`

参考：

- <https://developer.android.com/jetpack/androidx/releases/compose-material3>

### 6. 顶栏结构更推荐“单一语义 bar + 局部增强背景”，而不是多个并列独立浮层

结合官方文档与 Google Compose 样例，可以得出比较明确的结构倾向：

- `Scaffold` 或等价导航 shell 内，通常只有一个明确的 top app bar 容器负责顶部 chrome 语义、insets 与滚动行为。
- 沉浸感通常通过透明 `containerColor`、渐变保护层、edge-to-edge 背景来实现。
- 搜索框、筛选 chip、局部按钮可以有各自 `Surface` / 胶囊背景，但它们通常仍属于同一个 top bar 区域，或者是与内容自然衔接的次级行，而不是多个彼此独立、分别计算 offset/insets/alpha 的浮层。

成熟项目对照：

- Now in Android：使用 `Scaffold` + 单一 `NiaTopAppBar`，顶部栏背景可透明，内容区再配合渐变背景与 `safeDrawing` inset。
- Reply：邮件列表页顶部是单个搜索栏容器，整体通过 `WindowInsets.statusBars` 贴合系统栏，不存在多个并列顶栏浮层。
- Jetcaster：主结构仍然是单一 scaffold/toolbar 语义，局部控件可用 `SearchBar`、`Surface`、`FloatingToolbar` 增强，但不是把主顶栏拆成多个独立 chrome 容器。

这意味着当前项目里：

- `KototoroTopBar`
- `CompactTopBarFilterRail`
- tabs rail
- contextual selection top bar

作为多个独立容器并列悬浮的做法，虽然视觉上灵活，但在官方语义上属于“重定制 chrome 组合”，不是主流推荐路径。其直接代价通常是：

- inset 归属不清晰
- scroll behavior 难以复用官方能力
- shared transition 期间需要更多手工叠层协调
- alpha/offset/height 状态源增多，放大卡顿与抖动风险

因此后续收敛方向应当是：

- 以单一 top bar 主容器承载顶部 chrome 语义
- 局部搜索按钮、筛选按钮、更多菜单允许保留独立胶囊/玻璃背景
- tabs / filter rail 优先视为主 top bar 的扩展层，而不是完全独立的浮层系统
- 仅把确实需要跨 hero 保持最高层级的部件放入 shared transition overlay

## 当前实现问题清单

### P0 架构层问题

- [ ] 详情页 route transition、hero 保护窗口、chrome alpha 各自维护时间，缺少单一 timing source
- [ ] `KototoroApp.kt` 同时承担 system bars、chrome、shared transition、route orchestration、adaptive navigation 多重职责
- [ ] 收藏页等顶级路由仍存在全局特判式 chrome 协调

### P1 交互一致性问题

- [ ] 收藏页与普通列表页进入详情页的视觉体感不一致
- [ ] top/bottom chrome 对不同 route 的折叠/显现节奏不稳定
- [ ] main route 切换、详情页进出、搜索覆盖层等过渡时序没有统一 contract

### P1 性能问题

- [ ] chrome 显隐时有额外 alpha / delay / route state 叠加，增加无效重组和调试复杂度
- [ ] 收藏页 host 叠加 pager、tabs、filter、hero 后容易把过渡窗口推到掉帧边缘
- [ ] 多个顶级 route 自定义 top bar 状态透传过深，难以局部优化

### P2 适配问题

- [ ] 手机/平板/折叠屏导航布局依然是定制逻辑为主，尚未向 adaptive navigation suite 收敛
- [ ] 顶部 chrome 与导航 rail 的职责还没有明确拆分

## 目标架构

目标不是一次性重写导航，而是逐步收敛到下面的分层：

### 1. Navigation Shell

职责：

- `NavHost`
- adaptive navigation 容器
- shared transition 根作用域

不再直接负责：

- 复杂页面特判
- 具体页面 tabs/filter 的业务策略

### 2. Chrome Coordinator

职责：

- 顶部 chrome 与底部 chrome 的容器化承载
- 与 shared transition overlay 的叠层协调
- route 级可见性控制

不再直接负责：

- 页面滚动语义推断
- 多套自定义 delay 拼接

### 3. Route-scoped Chrome State

职责：

- 每个顶级 route 声明 tabs/filter/contextual actions
- 页面自己决定“需要什么 chrome”，全局只负责展示

### 4. System Bars / Insets Layer

职责：

- edge-to-edge
- status/navigation bars protection
- content padding contract

与 chrome / hero 逻辑解耦

## 分阶段整改路线

## 阶段 1：统一时序与计划落地

目标：

- 不改变视觉大方向
- 先把分散的 details transition timing 收口
- 建立文档、范围和验证标准

执行项：

- [x] 建立本计划文档
- [x] 统一详情页 route transition 与 chrome 协调相关 timing 常量源
- [ ] 标记并整理 `KototoroApp.kt` 中后续待拆的 chrome 状态

验收：

- 代码编译通过
- 不引入行为变化
- 之后所有详情页时序调整都有单一入口

## 阶段 2：shared transition overlay 化

目标：

- 用官方 shared transition overlay 能力替换部分全局 alpha/phase 状态机

执行项：

- [ ] 为 top/bottom chrome 识别“必须在 hero 期间保持叠层”的部分
- [ ] 试验 `renderInSharedTransitionScopeOverlay()`
- [ ] 把详情页进入期间的部分 `chromeAlpha` 控制迁出全局状态机

验收：

- hero 期间顶部栏/底部栏层级正确
- 删除至少一组 `delay/phase` 逻辑

## 阶段 3：top bar 滚动行为收敛

目标：

- 标准 route 的 top app bar 行为改用 Material 3 scroll behavior

执行项：

- [ ] 识别哪些 top bar 可以归入 `pinned/enterAlways/exitUntilCollapsed`
- [ ] 让标准场景优先使用 `TopAppBarScrollBehavior`
- [ ] 保留极少数复杂 route 的自定义折叠逻辑

验收：

- 主 route 顶栏滚动表现更一致
- `topBarOffset` 相关全局手工计算明显减少

## 阶段 4：Insets 与 adaptive navigation 收敛

目标：

- 减少手工 inset 计算
- 审视是否向 `NavigationSuiteScaffold` 或至少其语义靠拢

执行项：

- [ ] 识别可交给 Material 3 inset 处理的 top/bottom bar
- [ ] 减少重复 `PaddingValues` / `systemBars` 叠加
- [ ] 评估 `NavigationSuiteScaffold` 引入成本

验收：

- 顶部/底部导航与内容 padding 关系更清晰
- 平板/折叠屏分支逻辑减少

## 阶段 5：route-scoped chrome 状态精简

目标：

- 把收藏页等特判从全局容器移回 route 作用域

执行项：

- [ ] 审计 `FavoritesTopBarOverrideState` / `LayeredTopBarOverrideState`
- [ ] 缩小全局 chrome 需要理解的页面语义
- [ ] 把页面级策略下沉到 route 自己管理

验收：

- 全局容器不再含明显的 route 特判分支

## 当前轮次执行范围

本轮只执行阶段 1 的低风险部分：

- 文档化官方建议与全局整改路线
- 统一 details transition timing 常量

原因：

- 当前主导航 UI 已经较复杂，先建立统一 timing contract 能降低后续改造风险
- overlay/insets/scroll behavior 改造需要更大范围联调，不适合在本轮同时推进

## 验证方式

### 编译验证

```bash
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ./gradlew :app:compileDebugKotlin --no-daemon
```

### 手动验证

- 主界面 -> 详情页的进入/返回行为与本轮前保持一致
- 收藏页、浏览页、历史页不应因为 timing 抽取而出现回归

### 后续性能验证

- Perfetto / System Trace 观察点击进入详情后的前 300ms
- Layout Inspector 观察 chrome 与 shared transition 期间的重组热点
- 使用 release 构建验证，而不是只看 debug 体感

## 风险与回滚

### 风险

- timing 常量收口如果引用不全，可能造成个别 route 动画时间不一致
- 后续 overlay 化若处理不当，可能影响 chrome 叠层或点击区域

### 回滚策略

- 阶段 1 仅为常量抽取，风险低，可单独回滚
- 后续阶段应保持每轮改动独立，便于逐步验证

## 进度记录

### 2026-06-04

- 建立“导航 UI 与过渡架构整改计划”文档。
- 基于 Android 官方 shared elements、app bars、Material Insets、adaptive navigation 建议，明确当前实现与推荐分层的偏差。
- 确认本轮优先执行“统一 details transition timing 常量”，后续再进入 overlay、scroll behavior、Insets 与 adaptive navigation 收敛。
