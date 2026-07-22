# Compose 漫画阅读器功能等价矩阵

本文是 Compose 阅读器替换旧 View 阅读器前的验收清单。旧实现是行为权威；任何项目只有在对应测试、手工验证或同等强度的自动化证据存在时才能标记完成。

当前漫画阅读画布由 `ReaderActivity` 直接持有 `ComposeReaderController`，所有模式切换均为 Compose 状态更新，已不再创建或替换模式 Fragment。Activity chrome 仍使用 `activity_reader.xml`/ViewBinding，必须在工具栏、计时器、翻译、书签、保存和配置入口完成 Compose 迁移后删除。

## 全模式共同能力

| 能力 | 旧实现来源 | Compose 验收要求 | 当前状态 |
| --- | --- | --- | --- |
| 章节边界预加载、追加/裁剪页面 | `ReaderViewModel`、`ChapterPages`、`BaseReaderFragment` | 页面列表更新不跳转、不重复加载且维持阅读锚点 | 迁移中 |
| 进度持久化与恢复 | `ReaderState`、`BaseReaderFragment` | chapter/page/scroll 在旋转、模式切换、前后台后保持一致 | chapter/page 与条漫长图内部 scroll 已接入；仍需设备级旋转/前后台验证 |
| 图像请求、重试、预览、错误详情 | `PageLoader`、`BasePageHolder` | Loading/preview/error/retry 的状态和可达操作完整保留 | Loading/preview/error、异常类型动作标签、验证码/登录/设置等解析后重试及可序列化异常详情入口已接入；仍需补齐图片解码失败的浏览器验证与 URL override 重试 |
| 动图生命周期 | `BasePageHolder` | 离屏停止、可见恢复、复用时释放旧 drawable | GIF/WebP/AVIS 识别、跳过静态 Bitmap transformation/超分、可见页启停及 AVIS 组合销毁释放已接入；仍需真机覆盖 Pager 邻页、条漫多可见项和前后台切换 |
| 图片裁边、拆页和方向检测 | `PageLoader`、`ReaderPageSplit` | 标准与条漫裁边设置独立生效；先裁边后按 LEFT/RIGHT 拆页；宽页按旧实现 1.15 阈值触发共享页面重建，拆页稳定 key 保持不变 | 已接入；仍需真机覆盖裁边、宽页识别和 RTL 拆页顺序 |
| 翻译、OCR、气泡图层、显示层切换 | `ReaderPageEnhancementController`、`PageViewModel` | 原图先显示，翻译/增强异步覆盖不闪白、不重置进度 | 迁移中 |
| 超分辨率 | `ReaderSuperResolutionManager` | 原图可立即阅读；增强图替换后比例、缩放与锚点不变 | 迁移中 |
| 自动背景/颜色滤镜/亮度 | `ReaderSettings`、`BasePageHolder` | 配置变化不重建页面、不丢失当前进度 | 固定背景、单页/双页自动背景、亮度/对比度/灰度/反色/书页效果及背景 tint 已接入并即时生效 |
| 触控网格、长按、键盘、音量键、外部翻页 | `ReaderControlDelegate` | 所有 `TapAction`、反转导航和无障碍语义一致 | 外部翻页、反转导航及显式错误按钮语义已接入；长按与完整 TapAction 仍待迁移 |
| 书签、下载、保存页、旋转、沉浸式、计时滚动 | `ReaderActivity` | 顶/底工具栏和快捷操作对所有模式可用 | 保留 Activity 层，待 UI 收口 |

## 各布局专有能力

| 布局 | 旧实现 | 不可回归的专有行为 | Compose 目标 |
| --- | --- | --- | --- |
| 单页 | `PagerReaderFragment`、`PageHolder` | 水平翻页、页动画、单页缩放/拖拽边界、当前页精确保存 | `HorizontalPager` + 每页缩放状态；页边缘手势交还 Pager |
| 反转单页 | `ReversedReaderFragment` | 翻页方向、外部导航方向、章节/页索引映射均反转 | `HorizontalPager(reverseLayout = true)`，所有命令采用同一逻辑索引 |
| 从上到下 | `VerticalReaderFragment` | 垂直翻页、垂直动画、保存当前页 | `VerticalPager`，不与条漫的连续滚动混用 |
| 条漫 | `WebtoonReaderFragment`、`WebtoonScalingFrame`、`WebtoonImageView`、`WebtoonRecyclerView` | 容器级缩放 `0.5x..2.5x`、默认缩小、双击/捏合/平移/惯性；未知尺寸先占满视口；加载后锚点不跳；长图高度最多视口、内部滚动与列表协同；上下拉章节；可选间隙 | Compose 容器缩放已接入；占位已接入；其余（长图内部滚动、锚点保持、拉取章节、间隙）必须补齐后才可宣告完成 |
| 双页 | `DoubleReaderFragment`、`DoublePageViewportResolver` | 成对页对齐、末页空白半页、横屏切换、双页当前范围、可见页同时缩放 | spread 数据模型、lower/upper 范围与按稳定 page key 独立保存的缩放状态已接入 |
| 反转双页 | `ReversedDoubleReaderFragment` | adapter 反转与原始内容索引双向映射；章节边界选择正确页 | 单一 spread 索引映射及稳定 page key 锚点已接入，覆盖首末页、奇数页和边界前插/追加测试 |

## 设置—阅读映射

以下每项都应在 Compose 状态层有明确消费者，不能仅保留偏好值。

| 设置类别 | 关键选项 | 影响范围 |
| --- | --- | --- |
| 阅读模式 | 默认模式、自动检测、双页横屏、折叠屏双页、拆页 | ReaderManager、所有布局 |
| 导航 | 点击网格、底栏控件、始终 LTR、反转导航、音量键 | 所有布局与键盘 |
| 动画与缩放 | 翻页动画、缩放按钮、条漫缩放、默认条漫缩小 | 单页/双页已接入 `NONE`/`DEFAULT`/`ADVANCED` 翻页效果，覆盖水平、反转、垂直书脊变换；程序化翻页及双击/工具栏缩放动画尊重系统与阅读器动画开关；仍需真机验证 3D 透视和反转手势方向 |
| 图像 | 读取优化、标准裁边、条漫裁边、背景、色彩滤镜 | 加载管线和渲染层；背景和色彩滤镜均已有 Compose 消费者 |
| 沉浸与状态 | 全屏、浮动工具栏、多任务、信息栏、透明信息栏、章节 Toast、保持亮屏、自动滚动 | 信息栏（章节/页码/百分比、时间、电量、透明背景和明暗配色）与章节/翻译消息已建立 Compose 状态和动画宿主；Activity 仍双写旧 View，自动滚动和其余 chrome 待收口 |
| 条漫 | 间隙、拉取手势、缩放、默认缩小 | 条漫专用 |
| 翻译与增强 | 翻译开关/显示层/OCR/气泡检测与分组/渲染风格、超分辨率引擎/模型/缓存 | `ComposeReaderImagePipeline` 和图层 |

## 验收方法

1. 每个模式至少覆盖：初始定位、向前/后翻页、章节边界、旋转、前后台、模式切换、失败重试。
2. 条漫必须额外覆盖：未知尺寸占位、慢网加载、预览到原图到增强图、长图内部滚动、容器缩小/放大/复位、锚点不跳。
3. 双页和反转双页必须覆盖偶数/奇数页面、章节首尾、双页切换与进度恢复。
4. 每个设置项至少做一次即时生效或重新创建后生效的回归测试，并记录证据。

当前 Compose 实现不得以本清单中的“迁移中”或“未迁移”项目宣称功能等价。
