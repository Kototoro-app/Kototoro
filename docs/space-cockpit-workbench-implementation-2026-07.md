# Space Cockpit 常驻工作台实施方案（2026-07）

## 1. 目标

在保留现有 Space 短按切换和长按拖放工作台的基础上，增加可固定的 `Cockpit` 模式。手机竖屏下，
当前页面以左下角为锚点等比缩放，释放出的顶部与右侧组成 L 形工作台：

- 顶部展示轻量 Space 快照并承担 Space 切换；
- 右侧以可滚动命令坞替代底部导航，并逐步承载页面动作、快捷功能与最近阅读；
- 主区域只保活当前 Space 的真实 `NavHost`，其他 Space 继续使用 session 与缓存封面生成语义预览。

本功能不改变 Work、Projection、历史、收藏和下载的数据所有权，也不同时保活多套页面树。

## 2. 产品模式

```kotlin
enum class SpaceWorkbenchMode {
    Hidden,
    Overlay,
    Cockpit,
}
```

- `Hidden`：普通应用布局，显示既有底部导航和 Space FAB。
- `Overlay`：长按或长按拖动期间显示临时工作台；点击遮罩或返回键关闭。
- `Cockpit`：L 形工作台常驻；隐藏底部导航与 Space FAB；返回键只处理页面导航。

`Overlay` 与 `Cockpit` 互斥。固定操作将 `Overlay -> Cockpit`；取消固定将 `Cockpit -> Hidden`。
Space 切换完成后，`Overlay` 自动关闭，`Cockpit` 保持显示。

## 3. 手机竖屏几何

使用统一比例缩放而不是重新约束页面，避免列表列数、详情页结构和页面宽高比突变：

```text
┌──────────────────────────────────────┐
│ Space 快照区                         控制│
│ [漫画] [小说] [视频]             取消固定│
├───────────────────────────────┬──────┤
│                               │ │首页│
│                               │ │收藏│
│       当前真实页面             │ │浏览│
│       左下锚定、等比缩放         │ │历史│
│                               │ │快捷│
│                               │ │更多│
└───────────────────────────────┴──────┘
```

首版参数：

- 竖屏比例 `0.82`，典型 `360 x 800dp` 窗口释放约 `65dp` 右栏和 `144dp` 顶栏；
- 横屏/大屏使用不低于 `0.84` 的比例，后续可改为按最小触控尺寸求解；
- 主页面使用 `TransformOrigin(0f, 1f)` 固定左下角；
- 顶部快照条贯穿窗口全宽；右侧命令坞从顶部快照条下方开始，与主页面顶部平齐；
- 动画只改变图层 scale/alpha，不在动画过程中销毁或替换 `NavHost`；
- reduced motion 或系统动画关闭时使用 `snap()`。

缩放后的主页面触控目标会同步缩小。首版下限为 `0.82`；后续结合字体缩放和无障碍设置动态提高比例。

## 4. 顶部 Space 快照

顶部使用 `LazyRow`，卡片横向排列。快照封面作为整张卡片的背景，底部叠加渐变与文字。每张卡片包含：

- Space 图标、名称和选中状态；
- 最近作品的缓存封面与标题；
- session 所在位置（详情页、来源列表等）；
- 拖放命中反馈与切换中状态。

内置 Space 使用“漫画 / 小说 / 视频”短标签，不显示冗余的 `Space` 后缀；自定义 Space 保留用户名称。

卡片不是实时页面截图。封面继续保持 `diskCachePolicy(READ_ONLY)` 和
`networkCachePolicy(DISABLED)`，打开或固定工作台不会新增网络请求。

右上角公共控制核心提供固定/取消固定与关闭入口。Cockpit 下不再显示主页面 Space FAB。

## 5. 右侧可滚动命令坞

右栏采用固定头尾与中间 `LazyColumn`：

```text
WorkbenchRailHeader
ScrollableCommandRail
├── MainNavigationSection
├── ContextualActionsSection
├── PinnedActionsSection
└── RecentHistorySection
WorkbenchRailFooter
```

### 5.1 主导航

首版直接复用 `BottomNavState`、`mainNavItems`、badge 和现有 dispatcher。Cockpit 只改变呈现位置，
不创建第二套 `NavController` 或选中状态。普通底部导航、横屏 Rail 与 Cockpit 命令坞任一时刻只显示一个。

### 5.2 扩展功能

后续分阶段接入：

1. 现有 `routeContextualMenuActions`，默认最多直接显示两个，其余进入“更多”；
2. 搜索、继续阅读、下载管理等可固定动作；
3. 最近阅读封面按钮，默认三项，单击继续，长按显示详情；
4. 用户可调整固定动作顺序并隐藏整个分组。

导航目的地、一次性动作与最近内容必须使用不同模型，避免错误的持续选中状态。

## 6. 状态与所有权

`SpaceViewModel` 只持有模式、切换进度和 Space 业务状态：

```kotlin
data class SpaceUiState(
    val workbenchMode: SpaceWorkbenchMode,
    // existing state
)
```

指针坐标、卡片 bounds、磁吸和 settle 动画继续由 `SpaceWorkbenchGestureState` 持有。

Cockpit 固定偏好最终写入现有 `AppSettings`；首个实现阶段先完成 UDF 与 UI 闭环，再接入持久化，避免偏好层
反向依赖 Compose 类型。持久化值使用稳定字符串或布尔值，并对旧安装默认关闭。

## 7. 动画规范

- Overlay：保持现有 `fadeIn/fadeOut` 与页面缩放，时长约 `180–280ms`；
- 固定：顶部快照和右栏从各自边缘淡入，主页面围绕左下角以 spring/tween 平滑收敛；
- Space 切换：Cockpit 外壳保持稳定，仅主页面执行现有 Space transition；
- Cockpit 内切换不显示全屏 Space 遮罩幕布，直接更新主页面并保持外围工作台稳定；
- 命令选中：复用现有导航 item 的图标和选中动画；
- 不在动画中改变手势 owner 的 Composition 位置；
- reduced motion 下全部退化为即时布局。

## 8. Insets、窗口与特殊页面

- 背景可延伸到状态栏，顶部卡片内容使用 `safeDrawing`；
- 右栏避开 cutout 与导航手势区域；
- IME 展开时允许暂时隐藏或紧凑化 Cockpit，固定偏好不丢失；
- Reader、Novel Reader 与 Video Player 不能继续作为脱离 Cockpit 的全屏孤岛。三者通过
  `SpaceSwitcherDelegate` 挂载 Activity 级 Cockpit Shell；Shell 只变换该 Activity 的内容根节点，
  不重建 Reader adapter、WebView、播放器 Surface 或 Activity；
- Cockpit 固定状态由进程级可观察 Repository 持有，MainActivity 与沉浸式 Activity 使用同一状态源，
  不通过 Intent 临时复制，避免恢复和旋转后状态分叉；
- Video Player 在 Cockpit 激活时请求 sensor landscape；退出播放器或取消固定后恢复播放器原有方向策略，
  不通过重建 MainActivity 实现旋转；
- Reader 和 Novel Reader 尊重各自阅读方向设置，不强制横屏；
- 横竖屏和分屏窗口变化只重新计算布局，不重建 Space session。

### 8.1 横屏转置

横屏下 L 形区域的职责与竖屏对调：

```text
┌────────────────────────────────────────────┐
│ 可横向滚动命令坞：导航、上下文动作、快捷功能 │
├─────────────────────────────────────┬──────┤
│                                     │漫画  │
│         当前真实 Reader/Player       │小说  │
│         左下锚定、等比缩放             │视频  │
│                                     │快照  │
└─────────────────────────────────────┴──────┘
```

- 顶部：命令坞由纵向 `LazyColumn` 转为横向 `LazyRow`；
- 右侧：Space 快照由横向卡片转为纵向卡片；
- 顶部贯穿全宽，右侧从主内容顶部开始，与竖屏规则一致；
- 方向变化只替换工作台排列，不替换或重新创建底层内容 View；
- 播放器 Surface 变换必须通过外层容器完成，不直接修改视频画面比例。

## 9. 分阶段交付

### Phase 1：可用 Cockpit

- `Hidden/Overlay/Cockpit` UDF 状态；
- Overlay 固定入口与 Cockpit 取消固定入口；
- L 形主页面变换；
- 顶部横向 Space 快照；
- 右侧复用现有可滚动导航；
- Cockpit 下隐藏底部导航与 Space FAB；
- Space 切换后 Cockpit 保持。

### Phase 2：命令扩展

- 页面上下文动作；
- 搜索、继续阅读和下载快捷操作；
- 固定动作编辑与排序。

### Phase 3：最近阅读与持久化

- 最近阅读小卡片与展开抽屉；
- Cockpit 固定偏好和命令配置持久化；
- IME、字体缩放、折叠屏和沉浸页面细化。

### Phase 4：沉浸式统一宿主

- 新增进程级 Cockpit 状态 Repository；
- `SpaceSwitcherDelegate` 安装共享 Activity Cockpit Shell；
- Reader、Novel Reader、Video Player 内容根节点安全缩放；
- 横屏转置顶部命令坞与右侧 Space 快照；
- Video Player Cockpit 自动横屏及退出恢复；
- 验证返回详情页不触发 MainActivity recreation、封面重载或系统栏跳变。

### Phase 5：同 Activity 阅读路由

- Cockpit 模式下，漫画阅读入口不再启动独立 `ReaderActivity`；
- `MainActivity` 的内容区域挂载 `EmbeddedReaderFragment`，Cockpit 留在 Compose 外层；
- 宿主 Fragment 继续复用现有 `ReaderManager`、Pager/Webtoon Fragment、`ReaderViewModel`、
  `PageLoader` 与图片缓存链；
- Reader 子 Fragment 的 ViewModel 所有者在独立模式下仍为 Activity，在嵌入模式下切换为父宿主 Fragment；
- 系统返回键关闭内嵌阅读器并恢复原详情页，不发生 Activity 窗口切换；
- 小说、视频及漫画高级工具栏暂时保留原 Activity 路径，待宿主接口稳定后逐项迁移；
- 非 Cockpit 模式、外部深链、多任务阅读继续回退 `ReaderActivity`，避免首阶段扩大回归范围。

## 10. 测试与验收

- ViewModel：三种模式转换、Overlay/Cockpit 互斥、Space 切换后模式保持规则；
- 纯函数：不同窗口尺寸的比例、顶部高度和右栏宽度；
- Compose：固定/取消固定、底部导航与右栏互斥、顶部卡片选择、Back 行为；
- 真机：短按、长按、拖放、Cockpit 中滚动右栏、横竖屏、字体缩放、IME、后台恢复；
- 性能：只存在一个真实 `NavHost`，打开 Cockpit 不触发封面网络请求，切换时无明显掉帧。

Phase 1 完成标准：手机竖屏可以固定 L 形工作台，主页面保持等比，顶部可切换 Space，右侧导航可滚动，
取消固定后完整恢复普通页面布局与原有导航。

## 11. 上下文驾驶舱模型

常驻工作台不是把主界面导航栏机械地搬到右侧，而是一个随当前页面变化的上下文驾驶舱。它分为三个职责明确的区域：

- Space 区回答“我在哪个工作空间”，负责 Space 快照、切换和取消固定；
- 内容架回答“接下来读/看什么”，承载历史、更新、推进三个可横向切换的列表；
- 命令区回答“当前页面能做什么”，按页面类型生成导航与动作按钮。

统一状态模型保持单向数据流，UI 不直接推断路由字符串或拼装业务动作：

```kotlin
data class CockpitUiState(
    val pageContext: CockpitPageContext,
    val shelf: CockpitShelfState,
    val commands: List<CockpitCommand>,
)

enum class CockpitPageContext {
    Main,
    ContentList,
    Details,
    MangaReader,
    NovelReader,
    VideoPlayer,
}

sealed interface CockpitCommand {
    data class Navigate(val destination: Any) : CockpitCommand
    data class Action(val id: String, val enabled: Boolean = true) : CockpitCommand
    data class Toggle(val id: String, val checked: Boolean) : CockpitCommand
    data class Menu(val id: String) : CockpitCommand
}
```

`CockpitCommand` 只描述能力与状态，具体执行仍由现有 NavController、页面 ViewModel 或播放器/阅读器控制器负责，
避免驾驶舱成为新的业务状态所有者。

### 11.1 页面命令映射

- 主界面：完整复用原导航栏目的地、选中态、badge 和重选行为；
- 作品列表：返回、搜索、筛选、排序、显示模式、刷新、主页、更多；
- 详情页：返回、开始/继续、收藏、章节、来源、下载、主页、更多；
- 漫画阅读器：返回、章节、书签、上一章、下一章、阅读模式、翻译、设置、主页、更多；
- 小说阅读器：目录、书签、字体、排版、朗读、翻译、设置及返回；
- 播放器：选集、播放/暂停、倍速、弹幕、字幕、投屏、画面设置及返回。

动作必须来自页面公开的状态和回调接口。未就绪或不适用的动作不占位；危险或低频动作进入“更多”，不扩大首屏按钮密度。

## 12. 内容架：历史、更新与推进

右侧上部用于作品小卡片，下部固定上下文命令。内容架包含三个分页列表：

1. 历史：最近阅读/播放记录，支持直接续读；
2. 更新：有未读章节或新集数的作品；
3. 推进：根据近期活跃度、未读量、接近完结程度和用户固定状态给出继续推进项。

三个列表通过横向分页切换，每页内部独立纵向滚动；标题、页指示和命令区不随列表滚动。初始比例建议内容架约占右栏
`45%`、命令区约占 `55%`，窗口高度不足时内容架可折叠，命令区维持至少 `48dp` 的触控目标。

“推进”首版采用可解释的本地评分，不引入推荐服务：近期阅读、存在未读、接近读完和用户固定加分，长期未访问减分。
卡片仅使用现有数据库与 Coil 缓存，不因打开驾驶舱触发网络请求。

## 13. 阅读器与播放器职责

漫画阅读器在 Cockpit 下继续使用同 Activity 的 `EmbeddedReaderFragment` 承载成熟的 Pager/Webtoon 图片管线，
Compose 负责手势协调、工具栏、驾驶舱命令和转场边界。迁移以宿主接口为界，不重写 Coil3/PageLoader 底层。

固定模式下，阅读器原底栏只保留无法自然放入右侧命令区的进度控件：

- 收起态为 `3–4dp` 连续进度线；
- 展开态高 `40–48dp`，展示章节、页码与百分比；
- 拖动时提供刻度和触觉反馈，释放后才提交跳页，避免持续重载；
- 目录、书签、阅读模式、翻译、设置等迁移到上下文命令区。

播放器优先横屏，并使用横屏转置布局：顶部承载上下文命令和内容架，右侧承载 Space；播放器 Surface 只由外层容器缩放，
不改变视频内容比例。小说阅读器沿用竖屏职责划分，后续按相同宿主接口逐步迁移。

## 14. 统一视觉：单一 L 型材质壳层

三个区域必须看起来是一个完整界面，而不是三张相邻卡片。Cockpit 只绘制一个覆盖“完整顶部 + 完整右侧”的 L 型
`CockpitMaterialLayer`，顶部 Space 区和右侧内容只负责排版与点击，不再各自绘制 Surface、玻璃、阴影或外轮廓。

几何约束：

- 主内容与 L 型壳层的接缝全部为直角；固定模式下主内容四角均不再裁成圆角，也不绘制悬浮阴影；
- L 型壳层仅拥有窗口外缘，不在顶部/右侧交界处生成重复边框；
- 导航、内容架和 Space 区内部使用透明容器，以弱分隔线、间距和色阶建立层级；
- 作品卡片等可交互内容可保留 `12–18dp` 局部圆角，但不得再形成一层“玻璃中的玻璃”；
- 横竖屏复用同一 L 型轮廓，只交换 Space 与命令/内容架的职责。

### 14.1 纯色材质约束

- L 型工作台在 Material 3 与 iOS 界面风格下均使用同一个不透明 `surfaceContainer` 纯色背景；
- 工作台不得调用 Haze、Backdrop、blur、lens、vibrancy、noise 或任何运行时玻璃效果；
- 顶部、右侧和导航内容层保持透明，只由最底层的单一 L 型 Surface 提供背景；
- 不绘制玻璃描边和投影，层级仅通过普通 Material 色阶、间距和弱分隔线表达；
- 风格切换不得改变工作台几何、颜色层级或引入模糊计算。

## 15. 后续实施顺序

1. 视觉壳层：建立单一 L 型材质层，内部区域透明化，移除主内容及嵌套导航的重复圆角、边框和阴影；
2. 上下文模型：落地 `CockpitPageContext`、`CockpitCommand` 与页面能力适配接口；
3. 基础页面：依次接入主界面、作品列表、详情页命令映射；
4. 内容架：实现历史、更新、推进分页及缓存封面卡片；
5. 漫画阅读器：完善 Compose 手势/工具栏协调，迁移命令并增加底部进度控件；
6. 小说阅读器：接入统一宿主和页面命令；
7. 播放器：完成自动横屏、职责转置及播放命令；
8. 打磨与定制：动效、无障碍、字体缩放、折叠屏、命令排序和持久化。

每一步必须在上一层职责稳定后进行。不得为后续页面复制平行状态树，也不得在视觉层完成前继续堆叠独立玻璃容器。
