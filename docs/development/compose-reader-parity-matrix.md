# Compose 漫画阅读器功能等价矩阵

本文是 Compose 阅读器替换旧 View 阅读器前的验收清单。旧实现是行为权威；任何项目只有在对应测试、手工验证或同等强度的自动化证据存在时才能标记完成。

当前漫画阅读画布由 `ReaderActivity` 持有 `ComposeReaderController`，所有模式切换均为 Compose 状态更新，已不再创建或替换模式 Fragment。旧页面 Adapter、Holder、LayoutManager、页面 ViewModel、分页/条漫自定义 View、页面 XML、`ReaderManager`、`activity_reader.xml`、Reader 配置 XML 和 ViewBinding 已删除；`ReaderActivity` 已通过 `BaseComposeActivity` 直接设置 Compose content。全局 Space FAB 已通过共享 `SpaceSwitcherDelegate.Fab` 进入 Reader Compose 树；共享章节 Sheet 和翻译任务日志仍为 Fragment，这是彻底移除漫画阅读器 Fragment 前的主要过渡代码。

## Kotatsu-Redo 差异审计

以下结论以同工作区的 `../Kotatsu-Redo` 为行为基线，审计日期为 2026-07-25。这里的“缺口”表示代码级已确认的行为差异，不等同于尚未做设备验证。

## 全模式共同能力

| 能力 | 旧实现来源 | Compose 验收要求 | 当前状态 |
| --- | --- | --- | --- |
| 章节边界预加载、追加/裁剪页面 | `ReaderViewModel`、`ChapterPages`、`BaseReaderFragment` | 页面列表更新不跳转、不重复加载且维持阅读锚点 | 迁移中 |
| 进度持久化与恢复 | `ReaderState`、`BaseReaderFragment` | chapter/page/scroll 在旋转、模式切换、前后台后保持一致 | chapter/page 与条漫长图内部 scroll 已接入；仍需设备级旋转/前后台验证 |
| 图像请求、重试、预览、错误详情 | `PageLoader`、`BasePageHolder` | Loading/preview/error/retry 的状态和可达操作完整保留 | 页面下载失败和 Coil 解码失败均可进入统一错误 UI 并重试；当前详情按钮对所有异常显示，属于有意偏离旧实现“仅 `isSerializable()` 显示详情”的行为。旧实现的 IOException 转换重试、前台自动重试和 URL override 重试也未完全等价 |
| 动图生命周期 | `BasePageHolder` | 离屏停止、可见恢复、复用时释放旧 drawable | GIF/WebP/AVIS 识别、跳过静态 Bitmap transformation/超分、可见页启停及 AVIS 组合销毁释放已接入；仍需真机覆盖 Pager 邻页、条漫多可见项和前后台切换 |
| 图片裁边、拆页和方向检测 | `PageLoader`、`ReaderPageSplit` | 标准与条漫裁边设置独立生效；先裁边后按 LEFT/RIGHT 拆页；宽页按旧实现 1.15 阈值触发共享页面重建，拆页稳定 key 保持不变 | 已接入；仍需真机覆盖裁边、宽页识别和 RTL 拆页顺序 |
| 翻译、OCR、气泡图层、显示层切换 | `ReaderPageEnhancementController`、`PageViewModel` | 原图先显示，翻译/增强异步覆盖不闪白、不重置进度 | 迁移中 |
| 超分辨率 | `ReaderSuperResolutionManager` | 原图可立即阅读；增强图替换后比例、缩放与锚点不变 | 迁移中 |
| 自动背景/颜色滤镜/亮度 | `ReaderSettings`、`BasePageHolder` | 配置变化不重建页面、不丢失当前进度 | 固定背景、单页/双页自动背景、亮度/对比度/灰度/反色/书页效果及背景 tint 已接入并即时生效 |
| 触控网格、长按、键盘、音量键、外部翻页 | `ReaderControlDelegate` | 所有 `TapAction`、反转导航和无障碍语义一致 | Compose 画布以非消费式指针观察实现九宫格、长按与双击抑制，完整 TapAction 继续委托 `ReaderControlDelegate`；Activity 的 `dispatchTouchEvent`/`TapGridDispatcher` 已删除，键盘、音量键、外部翻页和反转导航已接入；仍需 TalkBack 与手势冲突设备验证 |
| 书签、下载、保存页、旋转、沉浸式、计时滚动 | `ReaderActivity` | 顶/底工具栏和快捷操作对所有模式可用 | Compose chrome 已成为实际输出，旧 loading/info/message/zoom/actions/timer 双写已移除；普通 Snackbar 与页面保存/分享操作已迁移到 Compose message host；阅读模式、双页、拆页、背景、图源服务器、颜色滤镜、浏览器、翻译诊断及常用操作已进入分区、自适应多列的 Compose Sheet；全局 Space FAB 已直接组合；错误宿主、共享章节 Sheet 和翻译任务日志仍待收口。图片错误详情的可见条件与旧实现不一致，见上行 |

## 各布局专有能力

| 布局 | 旧实现 | 不可回归的专有行为 | Compose 目标 |
| --- | --- | --- | --- |
| 单页 | `PagerReaderFragment`、`PageHolder` | 水平翻页、页动画、单页缩放/拖拽边界、当前页精确保存 | `HorizontalPager` + 每页缩放状态；页边缘手势交还 Pager。已确认缺口：Compose 页面始终使用 `ContentScale.Fit`，没有消费 `ReaderSettings.zoomMode` 的 `FIT_HEIGHT`、`FIT_WIDTH`、`KEEP_START`；缩放上限固定为 `5f`，也不等于旧实现按适配比例计算的 `2x` 上限 |
| 反转单页 | `ReversedReaderFragment` | 翻页方向、外部导航方向、章节/页索引映射均反转 | `HorizontalPager(reverseLayout = true)`，所有命令采用同一逻辑索引 |
| 从上到下 | `VerticalReaderFragment` | 垂直翻页、垂直动画、保存当前页 | `VerticalPager`，不与条漫的连续滚动混用 |
| 条漫 | `WebtoonReaderFragment`、`WebtoonScalingFrame`、`WebtoonImageView`、`WebtoonRecyclerView` | 容器级缩放 `0.5x..2.5x`、默认缩小、双击/捏合/平移/惯性；未知尺寸先占满视口；加载后锚点不跳；长图高度最多视口、内部滚动与列表协同；上下拉章节；可选间隙 | Compose 已有缩放状态、默认缩小、捏合焦点修正、真实视口尺寸、缩小时固定 Y 边界、放大时完整平移边界、边界接力、速度跟踪衰减惯性、未知尺寸占位、长图内部滚动、尺寸解析锚点恢复、可选间隙及 `30%` 阈值上下拉切章；仍缺旧版触摸命中区域调整。缩小时通过 `requiredSize` 扩大目标高度并按旧版边界修正，仍需设备验证。无裁边/拆页的静态条漫优先走 SSIV 的 `SkiaPooledImageRegionDecoder` 区域解码，直接以原图尺寸建立 tile 和内部滚动；SSIV 解码失败自动回退 Coil，裁边、拆页、动图直接走 Coil，且静态 Coil 回退使用有限目标尺寸降采样。 |
| 双页 | `DoubleReaderFragment`、`DoublePageViewportResolver` | 成对页对齐、章节间不跨页配对、封面单页、末页空白半页、横屏切换、双页当前范围、全 spread 同时缩放、宽图跨双页显示 | Compose 已接入章节边界 spacer、可选封面单页 spacer、稳定 page key 恢复、全 spread 基础缩放/平移、边界限制、惯性衰减和宽图整 spread 适配；仍需完整边界行为与宽图叠放的设备验证 |
| 反转双页 | `ReversedDoubleReaderFragment` | adapter 反转与原始内容索引双向映射；章节边界选择正确页；每个 spread 内页序正确 | Compose 已按反转布局交换 spread 内页面顺序，并保留章节 spacer；仍需设备验证反转导航、进度回调、宽图叠放、缩放边界和惯性 |

## 设置—阅读映射

以下每项都应在 Compose 状态层有明确消费者，不能仅保留偏好值。

| 设置类别 | 关键选项 | 影响范围 |
| --- | --- | --- |
| 阅读模式 | 默认模式、自动检测、双页横屏、折叠屏双页、拆页 | ReaderManager、所有布局 |
| 导航 | 点击网格、底栏控件、始终 LTR、反转导航、音量键 | 所有布局与键盘 |
| 动画与缩放 | 翻页动画、缩放按钮、条漫缩放、默认条漫缩小、缩放模式 | 单页/双页已接入 `NONE`/`DEFAULT`/`ADVANCED` 翻页效果，单页已消费 `FIT_CENTER`/`FIT_HEIGHT`/`FIT_WIDTH`/`KEEP_START`，双页仍按旧实现使用居中适配；程序化翻页及双击/工具栏缩放动画尊重系统与阅读器动画开关；仍需真机验证 3D 透视、`KEEP_START` 锚点、反转手势方向、双页缩放边界和惯性 |
| 图像 | 读取优化、标准裁边、条漫裁边、背景、色彩滤镜 | 加载管线和渲染层；背景和色彩滤镜均已有 Compose 消费者。条漫静态长图的区域解码由 SSIV 私有 AndroidView 承载，视图手势关闭，滚动和缩放统一由 Compose 容器处理 |
| 沉浸与状态 | 全屏、浮动工具栏、多任务、信息栏、透明信息栏、章节 Toast、保持亮屏、自动滚动 | 信息栏（章节/页码/百分比、时间、电量、透明背景和明暗配色）与章节/翻译消息已建立 Compose 状态和动画宿主；Activity 仍双写旧 View，自动滚动和其余 chrome 待收口 |
| 条漫 | 间隙、拉取手势、缩放、默认缩小 | 均已有 Compose 消费者；拉取手势关闭时恢复边界自动预加载 |
| 双页 | 横屏/折叠屏、封面单页、拆页、滚动灵敏度 | Compose options 已消费双页封面设置，并由 `ReaderSettings` 驱动 `padForDoublePage` 等价 spacer 构造 |
| 翻译与增强 | 翻译开关/显示层/OCR/气泡检测与分组/渲染风格、超分辨率引擎/模型/缓存 | `ComposeReaderImagePipeline` 和图层 |

## 验收方法

1. 每个模式至少覆盖：初始定位、向前/后翻页、章节边界、旋转、前后台、模式切换、失败重试。
2. 条漫必须额外覆盖：未知尺寸占位、慢网加载、预览到原图到增强图、长图内部滚动、容器缩小/放大/复位、锚点不跳。
3. 双页和反转双页必须覆盖偶数/奇数页面、章节首尾、双页切换与进度恢复。
4. 每个设置项至少做一次即时生效或重新创建后生效的回归测试，并记录证据。

当前 Compose 实现不得以本清单中的“迁移中”或“未迁移”项目宣称功能等价。条漫区域解码和 Coil 回退已接入，但仍需真机验证文件、`file+zip`、不同颜色配置及极端长图；条漫触摸命中区域仍属于已确认缺口。双页封面 spacer 和惯性已接入，宽图跨 spread 已有实现但仍需设备验证。
