# iOS 导航、阅读手势与页面性能检查（2026-10-29）

本次依据当前源码修复交互和明确的高频开销路径。工作环境为 Windows，没有 Swift/Xcode、
iOS SDK、模拟器或连接的真机；未取得本次卡顿的 Instruments trace，不能把代码审查视作
卡顿根因确认，也没有修复前后的帧率、耗时或截图验收结果。

## Apple 官方依据与实现

| 依据 | 本次应用 |
| --- | --- |
| [导航官方示例](https://developer.apple.com/documentation/swiftui/bringing-robust-navigation-structure-to-your-swiftui-app)、[WWDC22 导航示例](https://developer.apple.com/videos/play/wwdc2022/10054/) | 记录 → 记录详情 → 打开作品沿用记录页的 `NavigationStack`，通过 `navigationDestination` 推入；移除记录详情的 sheet、内部导航栈和拖动关闭行为。返回逐层回到原记录位置。 |
| [Tab bar 设计](https://developer.apple.com/design/human-interface-guidelines/tab-bars)、[WWDC25 TabView 与附件示例](https://developer.apple.com/videos/play/wwdc2025/323/)、[never](https://developer.apple.com/documentation/swiftui/tabbarminimizebehavior/never) | 高频的作品库、浏览、记录、设置和搜索保留系统 TabView，使用 `.tabBarMinimizeBehavior(.never)`。继续阅读使用系统 `tabViewBottomAccessory`，与主导航保持独立点击区域，继续显示作品和章节。详情/阅读期间仍按原有生命周期收起全局附件。 |
| [滚动几何 API 与 Bool 示例](https://developer.apple.com/documentation/swiftui/view/onscrollgeometrychange(for:of:action:))、[WWDC23 依赖与视图更新](https://developer.apple.com/videos/play/wwdc2023/10160/) | Apple 明确提示滚动几何频繁改变时避免更新大范围界面。将连续 offset 状态移到 `DetailsBackdropModifier`，其内容参数由外部提供；背景、封面锚点和标题层消费 offset，章节/资料正文不再直接观察它。保留全景封面原有滚动和拉伸效果。 |
| [边缘返回识别器](https://developer.apple.com/documentation/uikit/uinavigationcontroller/interactivepopgesturerecognizer)、[内容区返回识别器](https://developer.apple.com/documentation/uikit/uinavigationcontroller/interactivecontentpopgesturerecognizer) | 使用公开 UIKit API。阅读时由作品设置控制边缘返回，同时禁用会与横向翻页竞争的内容区返回；离开阅读器时恢复原 delegate 与启用状态。内容区识别器从 iOS 26 提供，与项目最低部署目标一致。 |
| [保持主线程响应](https://developer.apple.com/documentation/xcode/improving-app-responsiveness)、[官方后台缩略图教程](https://developer.apple.com/tutorials/instruments/ensuring-work-executes-in-a-background-thread)、[Image I/O 立即解码](https://developer.apple.com/documentation/imageio/kcgimagesourceshouldcacheimmediately) | 共用封面及来源图标的视图初始化只查内存；磁盘读取和缩略图准备复用现有合并后台任务。Image I/O 在后台创建缩略图时请求立即解码，避免将延迟解码留给首次显示。 |

交互取舍：本应用的跨页切换比持续播放控制更频繁，因此固定主导航；继续阅读保留在其上方，
不需要先反向滚动才能切页。继续阅读仍是一点恢复最近进度，空间选择保留原有独立入口。
保留系统布局以适配 iPad 的侧栏。漫画开关位于“更多 → 控件 → 边缘滑动返回”，默认延续
已发布行为；关闭后返回按钮仍可退出。设置自动保存在本作品，可通过现有按钮设为全局默认。

共享评估：已对照 `shared-ui-guidelines.md`。iOS 目前使用 SwiftUI，`core-ui` 没有 iOS
目标；本次只调整原生导航、渲染依赖和系统手势，继续消费现有共享章节/进度/数据接口。
没有复制 Android/CMP 页面，没有增加应用依赖或改动数据库。

## 本次已修改的开销路径

- `SourceDetailsView`：整页不再保存每次滚动的 offset；章节排序复用 model 已准备的非视频列表，
  避免每次视图更新都重新排序全部章节。
- `CoverCache.image(for:)`：原先被 `CoverImage.init`、body 和各附件同步调用，内存未命中便
  读磁盘、构造图片；现在只查 NSCache。磁盘缓存仍在共享后台加载任务中优先于网络读取，
  不改变源客户端、请求头、合并请求和等待者取消规则。写入时才创建磁盘目录。
- `SourceIconStore.cached`：原先由每个来源图标的 init 触发同步文件读取；现在只查内存。
  磁盘查找、来源请求及 96 像素缩略图准备共用已有后台任务，同键去重和失败重试窗口保留。
- `ReaderDownloadQueue`：初始化只创建存储 actor，读取与重启恢复由 `prepare` 异步执行。
  所有队列修改按主 actor 上的任务链提交，先从最新已提交状态派生下一份快照，再在存储 actor
  上编码/原子写盘，成功后才发布到 UI。失败不会发布未落盘状态，后续提交也不会被失败任务阻断。
  进度回调改为可等待的异步检查点；漫画、小说和视频调用点均已接入。存储维护先等待已接受的提交，
  仍保留下载请求槽位、暂停/立即恢复、目录删除回滚及后台暂停。后台保存复用现有带过期处理的租约。
- `WidgetSnapshotStore`：缩略图绘制/JPEG 编码和 JSON/图片写盘在独立 actor 串行执行；
  主 actor 只准备展示字段和获取共用封面。递增请求版本阻止慢的旧历史快照覆盖新结果；
  维持既有 JSON 字段与封面文件名，并将缩略图长边限制为 240 像素。
- `ReaderStorageModel`：图片空闲回调先合并等待 3 秒，相同预算的自动扫描至少间隔 60 秒；
  预算设置变化可绕过间隔。忙碌而未进入维护时不消耗扫描窗口；未超预算时不再排序缓存文件。

这些修改能从源码证明减少了对应调用路径的工作，但尚不能证明用户设备上的所有停顿均已消失。

后续持久化修改继续对照 Apple 的[actor 与重入示例](https://developer.apple.com/videos/play/wwdc2021/10133/)
和[文件系统性能指导](https://developer.apple.com/documentation/foundation/improving-performance-and-stability-when-accessing-the-file-system)：
仅把方法标成 async 或把存储放进 actor，并不能自动保证多个 UI 修改跨 await 的提交顺序。
本次任务链同时覆盖快照派生、后台提交和 UI 发布；存储 actor 的读写方法自身不含 await。

## 页面检查与待测风险

优先级描述处理顺序，不代表已测得对用户卡顿的贡献。表中未修改项保留为后续测量目标。

| 页面/路径 | 当前发现与已有保护 | 优先级及建议 |
| --- | --- | --- |
| 作品库与来源作品列表 | 共用封面的同步磁盘入口本次已改。作品库已有 `LibraryCollectionIndex`、筛选/排序 `ItemCache`，收藏和更新角标也有 model 索引，不应重复宣称完全没有缓存。`ContentCollection` 仍观察整个下载队列，普通网格的 `ContentText.firstLine` 仍在卡片 body 中处理摘要。 | P2：大作品库/下载中记录 body 更新次数与摘要耗时；角标仅订阅完成作品变化，必要时在数据边界准备摘要。不能仅凭 LazyVGrid、阴影或 material 判定 GPU 瓶颈。 |
| 来源列表/图标网格 | 图标初始化同步读盘本次已改。来源分组、排序和多项筛选仍可能遍历大来源目录。 | P2：使用大量来源对比进程冷启动和热缓存，记录分组/筛选的主线程耗时；按输入 revision 缓存派生值。 |
| 详情与完整目录 | 本次隔离连续滚动状态并复用已有排序。完整目录仍派生搜索结果、章节 ID 数组、下载状态和 `ChapterRow.rows`；详情还观察 `LibraryModel`、`SourcesModel`、下载队列。 | P2：2,000+ 章节及下载进度更新时采集 SwiftUI View Body Updates；按实际热点进一步缩小订阅和预处理边界。全景动画/模糊的渲染成本需在开关对照中测量。 |
| 记录、统计与书签 | 时间轴在 body 内合并、排序、按日期分组；同一天内和双栏 lane 使用 VStack，一天记录很多时不会逐条延迟创建。多个 task 会在进入页面、记录 revision、下载完成时发起统计读取；统计已有最新请求 token。 | P2：长历史、单日大量事件和“全部”时间范围下测量派生和布局；缓存时间轴快照、合并重复请求，保持双栏日期与书签定位语义。 |
| 下载队列及下载页 | 原主线程队列读/解析/编码/写盘已改为异步提交链和存储 actor；下载页区分读取中与空队列。仍每页保存完整快照，最多 500 项/32 MiB，`entries.didSet` 还重建完成作品集合。 | P1 代码已修改，待 Xcode/运行验收。后续测量大队列的后台编码/写盘吞吐与 UI 发布成本，保留持久化失败和维护回滚检查。 |
| 全局搜索 | 已有六路有界请求、取消和结果缓冲；`applyBatch` 仍逐条修改 Published rows，最多一次缓冲内多次发布。 | P2：多来源密集返回时看 body 更新；若出现热点，先合成一次 rows 更新再发布，保留取消和来源身份。 |
| 推荐与网站发现 | 推荐有持久化缓存/请求身份，网站预览有内存缓存；推荐 `configuration` 在视图路径扫描并排序来源目录、编码 JSON。 | P2：来源目录很大时测量 configuration；按来源 revision 和偏好变化预计算，避免单纯扩大并发。 |
| 仓库与扩展 | `RepositoriesModel` 主线程重建汇总和语言索引，并在初始化读展示元数据。`ExtensionIconLoader` 为非 actor 的 async 方法，不能仅因 `.task` 被主线程调用就断言内部必在主线程。 | P2：大仓库目录更新/批量安装时测量重建频次与 UI 更新时间；按真正热点处理。 |
| 漫画阅读器 | 已有有界下载/解码、场景/分块窗口和页内状态。分页 offset 写入单独 scroll 状态；本次没有改动缩放或章节窗口算法。保存/分享路径的 `PageExport.init` 仍同步 PNG 编码。 | P2：长图/多页预览/低内存设备及保存大图时采样，优先排查编码峰值；不能把所有 Kotlin/JNI 或图片加载归为主线程阻塞。 |
| 小说阅读器 | `NovelReaderModel` 主 actor 上的 `restoreTranslations` 同步读取/解析翻译缓存；翻译每批保存 JSON，并重建展示文档。 | P2：长章及双语模式的批量结果到达时测量；文件 I/O 和文档构造应分别确认线程与成本。 |
| 视频播放器 | 系统 AVPlayer 渲染，0.5 秒更新进度；外挂字幕 0.1 秒查询，但只在文本变化时发布，不能视作每 0.1 秒重建界面。 | P2：大字幕轨、控制栏打开、离线下载同时运行时检查字幕查询和主线程更新；与网络缓冲分开测量。 |
| 笔记与裁切 | `NoteImages.save` 同步 JPEG 编码及写盘；较大截图在调用方处可能阻塞。 | P2：记录裁切/视频截图保存时的调用栈与图片尺寸，再迁移到后台存储边界。 |
| 设置、备份、同步与 Widget | 普通设置以系统 Form/List 为主。Widget 缩略图和快照已移入 `WidgetSnapshotStore`，加入最新请求检查。 | Widget P1 代码已修改，待运行验收。大备份/恢复和同步另测数据库/序列化，不因为方法为 async 就认为一定不占主线程。 |
| 全局缓存维护 | 本轮新增空闲合并、同预算扫描间隔和超预算后才排序；仍会枚举文件，并在维护时让新请求等待。扫描保留在存储 actor。 | P1 代码已修改，待缓存规模/出图延迟对比；全量枚举的剩余开销仍需测量，保留租约和维护互斥。 |

旧的[网格审计](ios-grid-performance-audit-2026-10.md)包含历史测量，不能当作本次修复的前后对比。
当前扩展封面已经使用 600 像素缩略图、角标已有索引；本次没有重复实施旧审计中已完成的建议。

## 回归与真机验收

第一轮本机已运行 `python "iosApp/Scripts/test-reader-zoom-animation.py"`，5 项现有图像分析脚本测试通过，
但这些测试不覆盖本轮持久化、原生导航、手势或实际渲染性能。
修改的 Swift 文件使用临时目录中的 Tree-sitter 工具与 HEAD 对照做语法检查，并检查差异格式和新增工程注册；
`SourceReaderView` 原有多 trailing closure 的 `action:` 语法不被该解析器识别，需与基线区分。
这些检查均不能替代 Xcode 编译、类型检查或 XCTest。

新增/更新现有 XCTest：

- `ReaderSettingsTests/testEdgeSwipeBackMigratesAndPersistsWithoutChangingPageLayout`：旧 JSON 默认、
  关闭后的编码恢复，以及手势设置不改变阅读布局。
- `LocalImportTests/testMainTabsAndContinueReadingRemainReachableWhileScrolling`：上下文有续读作品时，
  向下滚动仍能直接点击主 Tab、空间和续读入口。
- `LocalImportTests/testReadingStatisticsPersistAndAppearInHistoryAndTheCenteredTimeline`：记录详情与作品
  都没有 sheet，返回逐层回到时间轴。
- `LocalImportTests/testReaderEdgeSwipeGoesBackOnlyFromTheEdgeBand`：显式开启后保持原有边缘返回。
- `LocalImportTests/testDisabledEdgeSwipeKeepsComicOpenAcrossModesAndRestart`：关闭后分页/纵向条漫/
  横向条漫不被边缘导航退出，重启保留设置，返回按钮可用，离开后恢复正常详情返回手势。
- `ReaderDownloadQueueTests`、`ReaderStorageTests`：现有暂停/立即恢复、后台暂停、重启读取、
  损坏归档、写入失败与目录回滚用例全部迁移为等待持久化结果；新增并发入队/移除不丢修改、
  写入失败后继续提交，以及自动扫描间隔/预算变更用例。
- `WidgetSnapshotStoreTests`：旧请求不能覆盖新历史，提交的封面路径可读取且长边不超过 240 像素。

这些 XCTest 本次未执行，必须在 Mac 上编译并运行。先按 `iosApp/README.md` 暂存 picker fixtures，
用同一个模拟器按 `-only-testing:KototoroUITests/<类>/<方法>` 定向运行；同时回归
`testBottomAccessorySwitchesSpacesAndResumes` 和全景/目录返回相关测试。

性能验收按 Apple 的[SwiftUI Instruments 指导](https://developer.apple.com/documentation/xcode/understanding-and-improving-swiftui-performance)
和[响应分析范例](https://developer.apple.com/videos/play/wwdc2026/268/)执行：

1. 同一真机、同一 Release 构建条件，固定作品、章节数、缓存规模和滚动路径，保存修改前后 trace。
2. 分别测试封面冷/热缓存、下载关闭/开启；详情全景动画与降低动态效果分别对照。
3. 使用 SwiftUI View Body Updates、Time Profiler、Hangs/System Trace 和 Animation Hitches，
   确认 offset 变化不再使整页正文求值，定位下载写盘及 Widget 工作的线程和耗时。
4. iPhone/iPad、横竖屏、较大字体、手势开/关、阅读器返回/重启分别回归；记录截图及导航位置。
5. 仅在相同条件下报告改进，不把网络慢、模拟器录屏帧率或单次样本总数当作界面 FPS。

实现遵循 KISS/DRY/SRP：复用系统导航、现有偏好与后台请求入口；将装饰滚动状态限制在
自己的职责内，没有另建路由框架、缓存系统或阅读布局实现。
