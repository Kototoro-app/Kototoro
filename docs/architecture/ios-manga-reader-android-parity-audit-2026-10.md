# iOS/KMP 漫画阅读器与 Android 对齐审查（2026-10-13）

## 实施进度：第一批正确性与资源控制改进

本节记录审查之后的实施；后文保留 `3dcca3a5b` 基线发现，其引用行号也对应基线，不应再把已修项当作当前缺失。

| 发现 | 本批处理 |
| --- | --- |
| P1 分块显示坐标/采样/接缝 | 分开相机 zoom 与屏幕 density；Swift 按 source→point 比例排 tile，裁去 sampling gutter |
| F2 长页书签偏移 | 书签单独传递完整目标；加载正确章节后使用 bookmark.scroll，页码跳转与书签共享定位实现 |
| F4 页级失败 | 按页保存 error/challenge，页面内定向重试；邻页失败不显示全局错误浮层 |
| P5 尺寸补偿 | Kotlin/Native 返回补偿视口；Swift 发起显式滚动请求，未加载锚点时保留恢复意图 |
| P4 解码与取消 | overview/base/fallback 共用 2 个 decode permits；页面取消传递到 detached overview/fallback；退出取消本章未完成 fetch |
| P3 清理后回填 | 清理取消 pending，递增 generation；迟到结果不能写回旧缓存 |
| P6 高频发布 | index/slotIndex 只有值变化时才发布；尚未解决全章 DTO 导出和全部滚动依赖范围 |

第一批实现保持现有 reader-core、SwiftUI 与 RequestGate 边界，不引入依赖。该批未处理的坏离线文件修复、跨章/生命周期能力和长章元数据 I/O 见下节；真正区域解码、实际内存账本与跨会话缓存仍待后续。并发限制和 generation 防护不等于真机峰值内存已经达标。

回归包括 Native 的 Retina 可见范围/相机独立缩放/晚到尺寸补偿，Swift 的 tile 放置与 gutter，以及本地 CBZ 的长页书签、损坏邻页与重试。最终结果：**Native `IosProductTest` 13 项通过，Swift/XCTest 9 项通过，均无失败或跳过**。书签恢复和坏邻页测试实际走文件导入/阅读 UI；已导出并检查两组截图。

验证入口：`./gradlew :ios-shared:iosSimulatorArm64Test -PiosSimulatorDevice=<UUID> --tests "org.skepsun.kototoro.ios.IosProductTest" --no-daemon`；Xcode 使用 `Kototoro` scheme，选择 `SourceImageFileTests`、`ImageLoadPolicyTests` 和上列两个 `LocalImportTests` 用例。当前机器复用 `RealApiPreview`/真实源运行时，未清理或重置原有模拟器数据；默认构建应使用独立 DerivedData，避免已有 JVM 资源触发仓库防混包检查。

Swift 最终结果位于本机 ignored `iosApp/build/RealApiPreview/Logs/Test/Test-Kototoro-2026.10.13_10-56-20-+0800.xcresult`，Native 报告位于 `ios-shared/build/reports/tests/iosSimulatorArm64Test/`。以上是模拟器功能/编译验证，尚无真机性能数字；缓存清理竞态与内存告警仍需更专门的压力验证。

## 实施进度：第二批离线恢复与阅读生命周期

| 发现 | 本批处理与边界 |
| --- | --- |
| F3 坏离线文件闭环 | 缺失、SHA 不符或不能解码的离线图片按页重取，再原子更新副本与清单；修复前核对原页序及记录，避免覆盖已经变更的章节。坏清单回退来源页列表并显示重新下载提示，保留原文件 |
| F1 连续自动阅读 | 纵向条漫自动滚动到章末进入下一章；等当前图片与恢复定位就绪后再滚动。加载下一章页列表失败时保留旧章并提供重试；手势跨章及横向条漫坐标验收仍待后续 |
| F6 生命周期与常亮 | 离开 active 停止自动滚动、取消预读、保存位置；短时后台任务保护保存。新增默认开启的阅读常亮设置，退出阅读器或后台时恢复之前的系统 idle timer 状态 |
| P6 无关设置触发重排 | 颜色、常亮等非布局设置不再重新 configure/导出全章 slots，也不把条漫滚动到页首；布局设置仍复用现有共享排版 |
| P7 离线元数据 I/O | 新增串行 actor 负责清单 JSON 读写和修复；仅缓存最近一个清单，以文件长度、修改时间、inode 检测替换。队列快照、partial 清单读取与 KMP 页列表转换仍有独立优化空间 |

实现复用现有图片校验、源请求和下载队列；没有增加依赖或改变清单格式。下载写入与阅读端修复共享清单存储，保持暂停/继续及已有检查点语义。未实现系统后台持续下载，也没有真机 FPS 或峰值内存结论。

验证覆盖 `ReaderDownloadStoreTests` 4 项、`ReaderDownloadQueueTests` 5 项，以及自动跨章、显示设置/后台续读两个 UI 用例。自动跨章用例在旧实现上失败、修复后通过。后台用例实际进入主屏幕、终止应用并重启，长页偏移恢复误差小于 5 个未缩放像素。另用专用下载夹具验证下载完成、损坏单张副本、重开阅读器后的文件修复；保留来源与全局图片缓存，避免影响其他作品。修复后副本与原字节完全一致，长度及 SHA-256 均正确。以上 **13 项 Swift/XCTest 用例全部通过**；本批未重复运行第一批 Native 测试。

本机命令日志：`/tmp/kototoro-reader-second-batch.log`、`/tmp/kototoro-reader-offline-prepare.log`、`/tmp/kototoro-reader-offline-repair.log`；文件校验信息保存在 `/tmp/kototoro-reader-repair-check.json`。Xcode 会替换 DerivedData 中的旧测试报告，画面验收使用单独保存的 `/tmp/kototoro-reader-second-ui-verified.xcresult`。两个 UI 用例再次通过，截图已导出到 `/tmp/kototoro-reader-second-attachments/` 并检查：章名变为 02、显示第二章蓝色夹具，重启续读显示长页中段且控件正常。

## 实施进度：第三批横向条漫方向、缩放与定位

| 发现 | 本批处理 |
| --- | --- |
| F5 独立横向方向 | 新增 `horizontalReversed` 偏好及布局开关；新作品与 Android 一致默认 LTR，旧 `mode=4` 设置缺字段时迁移为 RTL。方向传给现有 `HorizontalReaderScene`，不改共享核心规则；“切换方向”快捷入口在横向模式只切换该偏好 |
| F5 交互缩放 | 横向与纵向连续模式共用捏合／双击缩放和双轴滚动；按主轴／横轴分别限制平移，保持手势焦点下的物理内容位置 |
| F5 坐标一致性 | 连续 ScrollView 始终使用 LTR 物理坐标；横向 RTL 只反转页面集合的显示顺序，使用 `ReversedCollection` 避免每帧复制数组。点击前进、自动滚动、章首／章末判断统一使用偏好方向 |
| F2/F5 定位组合 | 方向变更后通过显式恢复请求重新定位原页及按阅读方向计算的页内偏移；横向滚动保留纵轴平移，缩放后的历史／书签仍用未缩放偏移 |

默认方向的 Native 回归先在旧实现上失败：第 1 页左边界期望 `0`，实际 `2400`；修改后 `IosProductTest` **17 项全部通过**，其中新增 4 项覆盖默认方向、双方向 1/2/4 倍缩放定位、RTL 尺寸补偿及可见分块。日志位于 `/tmp/kototoro-reader-horizontal-native-red.log` 与 `/tmp/kototoro-reader-horizontal-native.log`，XML 副本保存在 `/tmp/kototoro-reader-horizontal-native-tests.xml`。

Swift/UI 首轮 8 项中有 7 项通过：设置兼容/序列化/布局判断 4 项，横向方向/点击/捏合/纵向平移/书签组合、纵向书签及纵向自动跨章各 1 项。横向自动跨章已切到 02，但立即检查图片的断言早于异步解码；将其改为等待新章图片后，单独重跑通过并检查了第 02 章蓝色图片截图。首轮结果在 `/tmp/kototoro-reader-horizontal.xcresult`，自动跨章结果在 `/tmp/kototoro-reader-horizontal-auto.xcresult`。首次失败后 Xcode 诊断收集停留约 3 分钟，已只中止本轮的 `simctl diagnose` 子进程以保留报告，后续使用 `-collect-test-diagnostics never`。

补充快捷方向入口后，组合用例还校验了放大状态切换方向时保留连续模式、缩放和页内偏移。复跑时修正了两项测试初始化：先实际切到另一页再返回页首，避免复用夹具时相同滑条值不触发定位；在工具栏隐藏状态下捏合，保证两个触点属于画布。最终该组合用例 **连续 2 次通过**，结果为 `/tmp/kototoro-reader-horizontal-stable.xcresult`，截图位于 `/tmp/kototoro-reader-horizontal-stable-attachments/` 并已检查。按唯一用例计，本批 **Native 17 项、Swift/XCTest 8 项最终全部通过**；不把重复运行计入用例数量。没有真机内存、帧率或大规模源兼容性结论。

坐标 API 参考 Apple 的 [ScrollGeometry.contentOffset](https://developer.apple.com/documentation/swiftui/scrollgeometry/contentoffset)，其描述偏移相对于整体内容；LTR/RTL 正确性以共享几何测试和真实 UI 操作为证据。仍未实现手势跨章、真正区域解码及真机性能基线。

## 跨章语义复核：普通翻页与边界拉动必须区分

用户复核指出，不能把“到章末后额外拉动、松手切章”当作 Android 普通跨章的对齐。核对当前 Android 实现后确认：

- 分页模式使用 `ChapterPages.readerWindow` 拼接前章尾页、本章及后章首页，`ReaderViewModel.getSplitPagesSnapshot` 消费该窗口；邻章页进入可见范围后，`onCurrentPageChanged` 根据页面所属章节更新当前章节和章内页码，再重建窗口。
- 连续模式在关闭拉动选项时也会加载邻章并接续页面；`autoLoadAllowed` 的条件是分页模式或未开启 `isWebtoonPullGestureEnabled`，所以普通分页跨章不以额外拉动为前提。
- `AppSettings.isWebtoonPullGestureEnabled` 默认 `false`。边界拉动是独立交互，不能替代普通翻页和连续滚动的页面接续。

iOS 原实现已共享 `SourceChapterNavigation` 的邻章选择和 `reader-core` 的排版、进度及翻页动画，但渲染列表只包含当前章节。下一章预热只用于显式切章时复用请求，并未把邻章页面接入正在阅读的场景。

第四批最初的 `ReaderChapterPull` 是 Swift 本地的边界拉动实现，没有提取共享 Android 的章节窗口或章节提升规则，不能算普通跨章对齐。这一方案已停止接入：其文件不再属于 Xcode 应用及测试 target，产品入口和拉动提示已移除。下节记录改为普通分页窗口后的实现，连续模式的自然跨章仍须单独处理。

## 实施进度：第四批普通分页跨章窗口

| 处理 | 实现与边界 |
| --- | --- |
| 共享窗口规则 | 从 Android `ChapterPages.readerWindow` 提取 `ReaderChapterWindow.pages` 到 `reader-core/commonMain`；Android 保留原接口，iOS 经 `IosReader.chapterWindow` 调用同一实现。窗口包含前章末 2 页、本章全部页及后章前 2 页 |
| 普通翻页跨章 | Swift 保留每页的章节 ID、章内页码和窗口索引；原生分页及高级／仿真 pager 都直接翻进邻章页，由页面归属更新活动章节，不调用拉动阈值或显式重新加载章节 |
| 页码与持久化 | 进度条、目录页码、书签和历史使用章内索引；离线读取按页面实际所属章节及章内索引查副本。图片加载与 scene 使用窗口索引，两者分开 |
| 窗口稳定性 | 移动中延后发布窗口；更新后保留语义锚点及拆页 segment。原生分页与 `ForEach` 都使用章节／章内页码／拆页片段组成的稳定 ID，并在布局就绪后确认定位；高级／仿真 pager 在窗口 revision 更新时取消旧动画完成回调。触摸跟踪阶段不取消恢复，真实拖动才取消 |
| 有界预读与复用 | 最多保留本章和相邻两章的元数据；图片仍按可见邻域淘汰。Kotlin 按稳定页面身份迁移尺寸，Swift 迁移已解码图、artifact、页级错误及已完成 fetch；保留邻章首尾预热和离线章节跳过网络预读 |
| 导航与发布 | 窗口内书签直接选中已缓存页面；高频滚动几何回调只在活动页变化时发布和保存。显式上一章按钮沿用 Android `switchChapterBy(-1, openLastPage=false)` 的章首语义，向后翻页跨章才进入上一章末页 |
| 连续模式范围 | （第五批已接入，见下节）本批连续 scene 仍只包含本章，保留自动切章、点击切章和目录／按钮切章。邻章短页与视口终点的自然接续尚未解决；不把分页验证扩大为五种模式全部对齐 |

没有新增依赖、提交或分支。当前通过的验证为共享窗口 JVM 测试 2 项、`IosProductTest` Native 测试 19 项、共享元数据编译及 Android `compileDebugKotlin`。Native 首次运行构建 daemon 异常退出，降低本次构建的内存上限和并发后重跑通过，未修改全局 Gradle 设置。

UI 首轮未更新活动章节，补充原生分页几何确认后，章名、进度及 RTL 原生／高级／仿真组合通过；截图随后发现只检查图片存在不能证明图片与章节一致。新增红／蓝／绿夹具的像素断言，修正滚动 ID 与列表 ID，并让书签优先复用当前窗口后，跨章书签和重新启动后的续读已通过实际像素检查。两次 UI Runner 曾以 kill 信号退出；将准备书签与重新启动的检查分为按顺序运行的两个用例，并及时释放截图资源。

另修正了两项测试预期：小幅快速滑动是否翻页由原生分页决定；上一章按钮按 Android 默认打开章首，不应断言长页尾部。新文件导入等待系统文件选择器加载完成，再通过“浏览”进入本机目录，避免读取底层应用同名 tab 或仅搜索最近项目。三章用例验证滑条跳至实际末页后，正常翻页经过 01→02→03，再向后回到 02 的第 3 页；截图确认蓝色图片与 02、第 3 / 3 页一致。

最终按唯一用例计：**JVM 窗口测试 2 项、Native `IosProductTest` 19 项、Swift/UI 8 项均通过**，未把重复运行计入数量。UI 覆盖左右／纵向分页、RTL 原生／高级／仿真动画、跨章书签、按顺序终止并重新启动后的续读、三章窗口裁剪、显式上一章到章首，以及横向连续自动跨章。最后的方向回归 5 项全部通过；三章、上一章按钮和连续自动阅读分别有独立通过记录。原生分页确认包括实际夹具像素，不能只凭章名与图片元素存在下结论。没有实现连续条漫的邻章自然页面接续。

日志及结果：`/tmp/kototoro-reader-window-shared.log`、`/tmp/kototoro-reader-window-native-retry.log`、`/tmp/kototoro-reader-window-android.log`、`/tmp/kototoro-reader-window-directions.xcresult`（5 项最终方向／书签／续读回归）、`/tmp/kototoro-reader-window-trim-position.xcresult`（三章窗口）、`/tmp/kototoro-reader-window-catalog.xcresult`（上一章按钮通过）、`/tmp/kototoro-reader-window-final.xcresult`（横向连续自动阅读通过）。后两份报告也包含先前失败的用例，引用其通过项不等于整份报告全通过。Native 和 JVM XML 副本位于 `/tmp/kototoro-reader-window-native-tests.xml`、`/tmp/kototoro-reader-window-jvm-tests.xml`。以上仍是模拟器和编译验证，没有真机 FPS 或峰值内存结论。

## 实施进度：第五批连续条漫自然跨章

| 处理 | 实现与边界 |
| --- | --- |
| 窗口内容 | 连续模式经同一 `ReaderChapterWindow.pages` 拼接**完整**前章、本章与后章（Android 条漫显示全部已加载章节；iOS 仍按三章限定元数据）。分页模式保持前后各 2 页 |
| 原位追加 | 窗口只在末尾增长且条带物理起点不变（纵向、左到右横向）时，滚动中也直接追加下一章：索引、已解码图与进行中的下载都保留，不取消也不重新定位。前插、裁剪或右到左横向仍延后到滚动停止 |
| 重建定位 | 延后重建以视口首个可见页的页面身份和页内偏移为锚点恢复，而不是阅读页，避免条带因阅读页与视口首页不同而跳动 |
| 末端短页 | 首个可见页仍是阅读页；但比屏幕短的邻章页永远成不了首个可见页。用户或自动滚动到达条带末端时改读最后可见页，从而进入短邻章、加载再下一章，并记录全书末页。阅读页领先首个可见页时，只有真正向后滚动（按页面身份比较，不受重建坐标位移影响）才回退；放得下整个窗口的条带只保留给定页 |
| 滚动范围 | 安全区 inset 使原生 offset 范围（`-inset...content-container-inset`）与 scene 的 `0...scrollLimit` 不同。末端判定、点击翻页到边界、自动滚动落边，以及被末端夹住的恢复目标都改用滚动视图自身范围；此前纵向条漫在有 inset 的设备上点击到章末不会切章 |
| 自动滚动 | 先落到边缘再判定章末；下一章页列表正在预载时等待其接入而非重建场景；窗口重建后等恢复完成再继续，不使用旧 offset |
| 显式切章 | 上一章/下一章按钮、目录、边界点击与自动切章按 Android `switchChapterBy` 打开章首或章末，不再套用作品历史中同章的位置（此前历史恰为该章时会恢复到历史页）；书签随后仍按自身偏移跳转 |

验证：`IosProductTest` Native **20 项通过**（新增连续窗口追加不移动条带、右到左追加会移动、短尾页为最后可见页）。UI 新增 `testContinuousStripScrollsIntoNeighborChapters`（三章普通滚动 01→02→03 并回到 02 第 3 页，窗口 6/9 页，像素核对）与 `testContinuousStripEndEntersAShortNextChapter`（长页后的短 02 在末端成为阅读章、末章禁用下一章），均通过。完整 `LocalImportTests` + `ReaderSettingsTests` 一轮 25 项中 23 项通过；`testPreviousChapterButtonOpensTheChapterStart` 随后单独重跑通过。

测试辅助修正：上一批在位置描述末尾加了“，窗口 N 页”，`readingOffset` 仍把“页内偏移 ”之后整段转 Int，恒得 -1，令 `abs(offset) < 5` 类断言假通过、`> 300` 类断言必然失败。改为只读数字后，暴露两个基线即存在的问题（已用 HEAD 版 `SourceReaderView.swift` 复现）：上一章按钮恢复到历史页（本批已修）；`testLongPageBookmarkRestoresItsOffset` 点书签后停在页内偏移 5757 而非保存值。追踪与库内记录确认这是测试状态依赖而非应用缺陷：阅读器按前次运行的历史续读到第 2 页，新书签正确存为第 2 页（偏移 336），测试却按“第 1 页”点中更早运行留下的第 1 页旧书签（偏移 5757）。用例改为先用滑条回到第 1 页起点并断言页码后再加书签；在长页历史残留后与单独重复运行均通过。

仍未覆盖：横向连续在真实手势下的前插定位只有共享几何测试；真机滚动中追加的流畅度、内存峰值未测。

## 范围与证据边界（审查基线）

审查对象是漫画图片阅读器，不包括小说和视频。本轮以当前工作树代码为主证据；历史 handoff/计划只用于定位已经交付的切片。审查启动时 `SourceReaderView.swift`、`Info.plist` 和 `project.pbxproj` 含已有修改；其中阅读器的邻页解码、下一章预热和恢复修正均纳入判断。后续核对时 HEAD 为 `3dcca3a5b`，上述修改已不在 Git 状态中。本轮未修改产品代码、未启动应用；以下“已对齐”指源码具备相应链路，不代表已完成真机体验或性能验收。

分类：**缺失**＝没有对应链路；**部分实现**＝具备入口但关键语义未覆盖；**已对齐**＝现行基础链路已经存在；**待实测**＝存在平台坐标、生命周期或真实性能风险，不能只凭静态检查判定故障。

本轮最需要优先处理的是：高清 tile 的图片像素到显示坐标转换、尺寸更新时的视口补偿、长页书签偏移、按页错误恢复，以及解码任务的内存/生命周期所有权。五种模式、下载、历史和预读已经有基础实现；剩余问题更多在组合功能和极端输入。后文 F1–F10 是功能发现编号，P1–P9 是性能发现编号，均不是严重级别。

## 基础功能审查

### 已完成的基础能力，避免重复列为缺失

| 能力 | 状态 | 当前调用链与依据 |
| --- | --- | --- |
| 五种阅读模式、四种 Fit、双页、封面独页、宽页拆分 | 已有基础排版；大图拆分高清层部分实现 | `ReaderMorePanel` 的 mode/fit/doublePage/cover/split → `SourceReaderModel.configure` → `IosReader.configure/rebuild` → `PagedReaderScene`/`VerticalReaderScene`/`HorizontalReaderScene`。见 [ReaderPanels.swift:235](../../iosApp/Kototoro/ReaderPanels.swift#L235)、[SourceReaderView.swift:91](../../iosApp/Kototoro/SourceReaderView.swift#L91)、[IosReader.kt:112](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosReader.kt#L112)。拆半页面不走 tile 高清层，见 P9。 |
| 同分支前后章、章节选择、页码滑条、页码跳转 | 已对齐入口 | `previousChapter/nextChapter` → `IosSourceContent.adjacentChapter` → `SourceChapterNavigation.adjacent`；按钮/目录 → `moveChapter`；滑条 → `jump`。见 [SourceReaderView.swift:83](../../iosApp/Kototoro/SourceReaderView.swift#L83)、[IosSources.kt:115](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosSources.kt#L115)、[SourceReaderView.swift:744](../../iosApp/Kototoro/SourceReaderView.swift#L744)。滑动跨章的差距另列如下。 |
| 历史、继续阅读、页码/条漫页内偏移、双页进度计入末页 | 已对齐基础链路，恢复稳定性待实测 | `load` → `IosLibrary.position` → 共享历史章节恢复；`persist` → `recordPosition` → `SourceLibrary.recordPage`。保存双页起始锚点并将末页传为 `lastVisible`，条漫偏移归一化到未缩放像素。见 [SourceReaderView.swift:132](../../iosApp/Kototoro/SourceReaderView.swift#L132)、[SourceReaderView.swift:425](../../iosApp/Kototoro/SourceReaderView.swift#L425)、[IosLibrary.kt:247](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosLibrary.kt#L247)、[IosReader.kt:169](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosReader.kt#L169)。 |
| 书签新增、删除、跨章返回 | 部分实现 | 数据层和 UI 已有；长页偏移恢复存在确定遗漏，见 F2。`IosBookmark.scroll` 已导出，不能再归因于共享模型缺字段。见 [IosLibraryRecords.kt:18](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosLibraryRecords.kt#L18)。 |
| 分页捏合/双击缩放、拖动，纵向条漫整条缩放和平移 | 已对齐基础交互 | `ReaderSpread` 管理分页缩放/拖动；纵向 `ReaderStripZoom` → `zoomStrip` → `setStripZoom` → `IosReader.setZoom`。见 [SourceReaderView.swift:1127](../../iosApp/Kototoro/SourceReaderView.swift#L1127)、[SourceReaderView.swift:670](../../iosApp/Kototoro/SourceReaderView.swift#L670)、[SourceReaderView.swift:896](../../iosApp/Kototoro/SourceReaderView.swift#L896)。横向条漫缩放另列 F5。 |
| 原生分页、默认/淡入/缩放、高级/仿真动画、左缘返回 | 已有实现，边界手感待实测 | `.paging` 与 `ReaderTurnPager` 两条渲染路径；高级/仿真由 `IosReader.pageTurn` 调共享 `PageTurnTransition`；`EdgeSwipeBack` 恢复导航控制器边缘返回。见 [SourceReaderView.swift:589](../../iosApp/Kototoro/SourceReaderView.swift#L589)、[SourceReaderView.swift:943](../../iosApp/Kototoro/SourceReaderView.swift#L943)、[ReaderPageTurn.swift:78](../../iosApp/Kototoro/ReaderPageTurn.swift#L78)。 |
| 设置按作品保存、设为全局默认、反色/灰度/对比度/亮度、裁白边、截图导出/裁切批注 | 已有基础链路；长页裁边和原图导出部分实现 | `ReaderSettings` 写 `reader.work.<contentID>`/`reader.defaults`；更多面板 → 设置/动作 → `ReaderPageTile`/`ReaderCropView`/`fileExporter`。见 [ReaderSettings.swift:52](../../iosApp/Kototoro/ReaderSettings.swift#L52)、[ReaderPanels.swift:254](../../iosApp/Kototoro/ReaderPanels.swift#L254)、[SourceReaderView.swift:578](../../iosApp/Kototoro/SourceReaderView.swift#L578)、[SourceReaderView.swift:923](../../iosApp/Kototoro/SourceReaderView.swift#L923)。裁白边只覆盖非 tiled 图片，导出的是当前解码 `UIImage`，见 P9。 |
| 单章/多章下载、暂停/恢复/重试、页级断点、完整章离线读图 | 已对齐前台基础能力 | 目录多选/本章下载 → 应用级 `ReaderDownloadQueue` → `ReaderDownloads.download`，逐页 checkpoint 与 SHA/解码校验；`load`/`loadImage` 优先离线副本。见 [ReaderPanels.swift:157](../../iosApp/Kototoro/ReaderPanels.swift#L157)、[ReaderDownloads.swift:49](../../iosApp/Kototoro/ReaderDownloads.swift#L49)。后台继续下载和损坏恢复另列。 |
| 本地 CBZ/ZIP 导入与阅读 | 已对齐该格式基础流程 | 文件选择 → `IosSources.importLocal` → `LocalSourceRuntime.import`；页图片 → `LocalSourceRuntime.fetchImage` → 通用阅读器。见 [SourceBrowseView.swift:572](../../iosApp/Kototoro/SourceBrowseView.swift#L572)、[LocalSourceRuntime.kt:118](../../core-local/src/commonMain/kotlin/org/skepsun/kototoro/local/shared/LocalSourceRuntime.kt#L118)、[LocalSourceRuntime.kt:135](../../core-local/src/commonMain/kotlin/org/skepsun/kototoro/local/shared/LocalSourceRuntime.kt#L135)。裸图片目录导入另列 F9；不能把 PDF/CBR 等未经 Android 源码确认的格式列作 Android 已有差距。 |
| 源错误说明、浏览器挑战验证、重试 | 部分实现 | `fail` → `SourceErrorText.describe/challenge` → `ChallengeVerifyButton` → `retry`。见 [SourceReaderView.swift:453](../../iosApp/Kototoro/SourceReaderView.swift#L453)、[SourceReaderView.swift:511](../../iosApp/Kototoro/SourceReaderView.swift#L511)。页面级错误隔离另列 F4。 |

历史文档中“未接完整 reader-core/缩放/分块/预读/离线/历史”的 §92 初期限制已经过时。主计划 §95、§97、§107、§111 记录了后续交付，当前代码又增加了邻页解码与下一章预热。尤其 §111 的“横向条漫交互缩放未做”仍与现行 Swift 条件一致。参见 [kmp-ios-plan:5115](./kmp-ios-plan-2026-10.md#L5115)、[kmp-ios-plan:5221](./kmp-ios-plan-2026-10.md#L5221)、[kmp-ios-plan:5597](./kmp-ios-plan-2026-10.md#L5597)、[kmp-ios-plan:5711](./kmp-ios-plan-2026-10.md#L5711)。

### F1：滑动跨章缺失，连续自动阅读不能跨章

**状态：部分实现；优先级高。** 当前点击区到边界可进下一章/回上一章，分页自动翻页也会换章。但系统分页和条漫 ScrollView 仅报告位置/滚动阶段，不拼接邻章，也没有边界拉动回调；高级/仿真分页器在 `.onEnded` 把目标夹在 `0...count-1`，不存在换章委托。

对应链路：普通滑动 → `scrollView/onScrollPhaseChange` → `scrolledToSlot`/`continuousPosition`，到章边界停止；高级滑动 → `ReaderTurnPager.drag` → `settle`，同样夹到本章。见 [SourceReaderView.swift:628](../../iosApp/Kototoro/SourceReaderView.swift#L628)、[ReaderPageTurn.swift:91](../../iosApp/Kototoro/ReaderPageTurn.swift#L91)。已有点击换章见 [SourceReaderView.swift:869](../../iosApp/Kototoro/SourceReaderView.swift#L869)。

条漫自动滚动在超出 `scrollLimit` 时直接关闭 `autoScroll`，没有调用下一章；分页分支则明确 `moveChapter(next)`。见 [SourceReaderView.swift:548](../../iosApp/Kototoro/SourceReaderView.swift#L548)。Android 当前窗口在前/后章临近边界加载邻章，并在条漫启用拉动模式时由显式边界阈值切章：见 [ReaderViewModel.kt:898](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderViewModel.kt#L898)、[ComposeSceneWebtoonReader.kt:614](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeSceneWebtoonReader.kt#L614)、[ComposeScenePagedReader.kt:899](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeScenePagedReader.kt#L899)。iOS 新增 `preloadNextChapter` 只预热下一章资源，不能等同于这些跨章交互已对齐。

### F2：书签保存了长页位置，但导航丢弃它

**状态：部分实现，确定的调用链遗漏；优先级高。** `toggleBookmark` 把 `offset` 写入记录，`IosBookmark.scroll` 也已暴露；书签面板点击却只做 `moveChapter(chapter); onJump(bookmark.index)`，而 `jump` 一律 `offset = 0`，父视图也只滚到该页起点。条漫长页中部书签因此回到页首。

完整链路及证据：[SourceReaderView.swift:439](../../iosApp/Kototoro/SourceReaderView.swift#L439) → [IosLibrary.kt:270](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosLibrary.kt#L270) → [IosLibraryRecords.kt:22](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosLibraryRecords.kt#L22) → [ReaderPanels.swift:99](../../iosApp/Kototoro/ReaderPanels.swift#L99) → [SourceReaderView.swift:398](../../iosApp/Kototoro/SourceReaderView.swift#L398)、[SourceReaderView.swift:804](../../iosApp/Kototoro/SourceReaderView.swift#L804)。Android 从详情书签进入阅读器会构造含 `bookmark.scroll` 的 `ReaderState`：[BookmarksScreenRoot.kt:133](../../app/src/main/kotlin/org/skepsun/kototoro/details/ui/pager/bookmarks/compose/BookmarksScreenRoot.kt#L133)、[ReaderIntent.kt:60](../../app/src/main/kotlin/org/skepsun/kototoro/core/nav/ReaderIntent.kt#L60)。注意 Android 阅读器内部默认 `ReaderNavigationCallback.onBookmarkSelected` 也只传页对象，不能未经进一步实测就声称其所有入口都恢复偏移。

### F3：坏离线文件可能形成“重试仍读同一坏文件”的闭环

**状态：部分实现；优先级高。** 下载执行时有逐页校验/重写能力，但阅读端的 `pages`、`loadedPage` 直接 `try` 解码 manifest 或加载本地图片。JSON 损坏、缺文件或 SHA 校验失败会向上抛错，既不把完整下载判为失效，也不回退在线重取；`isDownloaded` 仅检测 manifest 存在。阅读器重试调用同一 `load/loadImage`，路径不变。

链路：[ReaderDownloads.swift:23](../../iosApp/Kototoro/ReaderDownloads.swift#L23)、[ReaderDownloads.swift:28](../../iosApp/Kototoro/ReaderDownloads.swift#L28)、[ReaderDownloads.swift:124](../../iosApp/Kototoro/ReaderDownloads.swift#L124) → [SourceReaderView.swift:165](../../iosApp/Kototoro/SourceReaderView.swift#L165) → [SourceReaderView.swift:458](../../iosApp/Kototoro/SourceReaderView.swift#L458)。下载执行端具备重新校验和写入能力，但阅读器的重试没有调用它；下载管理的“移除任务”也明确保留文件，不能当作删除坏文件的恢复入口，见 [ReaderDownloadsView.swift:49](../../iosApp/Kototoro/ReaderDownloadsView.swift#L49)。此项是 iOS 自身的确定恢复缺口；尚未证明 Android 对所有损坏离线输入都会自动在线回退。

### F4：错误状态没有按页面隔离，邻页失败会遮挡正在读的成功页

**状态：部分实现；优先级高。** 模型只有一个全局 `error/challenge`，所有 `loadImage(at:)` 失败都调用 `fail`；当前工作树 `loadNeighbors` 又会主动加载相邻页。于是邻页加载失败也会显示全屏上的错误浮层；浮层“重试”只重试 `index` 当前页。如果当前页已经在 `images`，`loadImage` 提前返回，失败邻页没有作为明确目标重试。翻到别页成功也没有清除原先全局错误。

链路：[SourceReaderView.swift:13](../../iosApp/Kototoro/SourceReaderView.swift#L13)、[SourceReaderView.swift:153](../../iosApp/Kototoro/SourceReaderView.swift#L153)、[SourceReaderView.swift:195](../../iosApp/Kototoro/SourceReaderView.swift#L195)、[SourceReaderView.swift:322](../../iosApp/Kototoro/SourceReaderView.swift#L322)、[SourceReaderView.swift:458](../../iosApp/Kototoro/SourceReaderView.swift#L458)、[SourceReaderView.swift:511](../../iosApp/Kototoro/SourceReaderView.swift#L511)。Android 页面组件把错误状态和 `retryKey` 绑定到 `page.readerKey`，重试对该页面调用 `imagePipeline.observe(page, force = true)`：[ComposeReaderPageComponents.kt:118](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderPageComponents.kt#L118)、[ComposeReaderPageComponents.kt:291](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderPageComponents.kt#L291)。

### F5：横向条漫只有固定 RTL，交互缩放未启用

**状态：缺失两个设置/交互；坐标一致性待实测。** `IosReader` 将模式 4 固定成 `RIGHT_TO_LEFT`，Swift 的 `.layoutDirection` 也固定镜像。没有 Android 的独立“横向连续反向”选项；Android 默认该选项为 false，布局模式和阅读方向分开。见 [IosReader.kt:115](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosReader.kt#L115)、[SourceReaderView.swift:650](../../iosApp/Kototoro/SourceReaderView.swift#L650)、[ComposeReaderOptions.kt:86](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderOptions.kt#L86)、[ComposeReaderOptions.kt:324](../../core-ui/src/commonMain/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeReaderOptions.kt#L324)。

共享层虽然能放大横向 scene 的高度，Swift `setStripZoom` 仅接受模式 3，`ReaderStripZoom(enabled: strip)` 的 strip 也仅模式 3：[SourceReaderView.swift:387](../../iosApp/Kototoro/SourceReaderView.swift#L387)、[SourceReaderView.swift:631](../../iosApp/Kototoro/SourceReaderView.swift#L631)、[SourceReaderView.swift:670](../../iosApp/Kototoro/SourceReaderView.swift#L670)。主计划 §111 已明确记录这一边界。

还应实测横向连续的点击/自动滚动/恢复是否使用同一种阅读顺序坐标：`turn` 按 forward 加正 step 并在正 `limit` 端换下一章；自动滚动模式 4 却减 step；进度直接把系统 `contentOffset.x` 传入共享 scene。见 [SourceReaderView.swift:554](../../iosApp/Kototoro/SourceReaderView.swift#L554)、[SourceReaderView.swift:652](../../iosApp/Kototoro/SourceReaderView.swift#L652)、[SourceReaderView.swift:869](../../iosApp/Kototoro/SourceReaderView.swift#L869)。这是静态不一致风险，SwiftUI RTL 的实际 offset 行为需要设备/夹具验证，不能仅凭此断言所有横向导航已经坏掉。

### F6：生命周期保存与阅读常亮缺失

**状态：缺失生命周期/常亮链路；进度丢失的实际窗口待实测。** 普通页码切换/图像加载完成/滚动 idle/关闭页面/自动滚动每 5 秒会保存位置，但漫画阅读页不监听 `scenePhase`/后台通知，不主动在应用转 inactive/background 时保存当前位置或停止自动滚动；`onDisappear` 并不等于应用进入后台。Android `ReaderActivity.onPause` 明确保存当前 scene state。见 [SourceReaderView.swift:547](../../iosApp/Kototoro/SourceReaderView.swift#L547)、[SourceReaderView.swift:571](../../iosApp/Kototoro/SourceReaderView.swift#L571)、[SourceReaderView.swift:664](../../iosApp/Kototoro/SourceReaderView.swift#L664)、[ReaderActivity.kt:882](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderActivity.kt#L882)。

现行 iOS 漫画阅读器及 settings 没有 `isIdleTimerDisabled`/阅读常亮设置；Android 观察 `isKeepScreenOnEnabled` 并切换 `FLAG_KEEP_SCREEN_ON`：[ReaderActivity.kt:739](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderActivity.kt#L739)、[ReaderActivity.kt:1122](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderActivity.kt#L1122)。自动滚动时熄屏是需要优先验收的基础体验，不能把颜色“亮度”滑条当成屏幕常亮或系统亮度控制。

### F7：阅读模式自动识别、双页自适应和设置覆盖语义尚未对齐

**状态：部分实现；优先级中。** iOS 从作品 JSON 或全局 defaults 读取 mode，首次默认 0，没有基于页面长宽比的条漫识别；Android `DetectReaderModeUseCase` 优先作品模式，再按用户开关检测页面并持久化结果。见 [ReaderSettings.swift:57](../../iosApp/Kototoro/ReaderSettings.swift#L57)、[DetectReaderModeUseCase.kt:38](../../app/src/main/kotlin/org/skepsun/kototoro/reader/domain/DetectReaderModeUseCase.kt#L38)、[ReaderViewModel.kt:1050](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderViewModel.kt#L1050)。

iOS doublePage 是直接传给共享 scene 的手动布尔值，不按横屏/可用宽度自动开启/关闭；Android 当前 `applyDoubleModeAuto` 使用方向、窗口比例和相关偏好。这一差距在 iPad 旋转/分屏阅读时比手机更明显。见 [SourceReaderView.swift:96](../../iosApp/Kototoro/SourceReaderView.swift#L96)、[ReaderActivity.kt:1075](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderActivity.kt#L1075)。

保存任意一个 iOS 选项会写整份作品 preferences，以后该作品不再消费全局 defaults 的变更；现有 UI 也没有清除作品 override/恢复默认入口。这是现行设置语义边界，不一定要求照搬 Android 的所有偏好，但需要明确用户能否让旧作品重新继承全局默认。见 [ReaderSettings.swift:59](../../iosApp/Kototoro/ReaderSettings.swift#L59)。

### F8：输入定制、屏幕方向、隐私阅读未覆盖

**状态：缺失；按 iOS 平台能力取舍。** iOS 当前只有固定点击区（左右 30%、上下 20%）与整体反转/禁用，没有单区域动作、长按动作配置；Android `TapGridSettings` 逐区域分别保存 tap/longTap，并由 `ReaderControlDelegate` 消费。见 [SourceReaderView.swift:851](../../iosApp/Kototoro/SourceReaderView.swift#L851)、[TapGridSettings.kt:30](../../app/src/main/kotlin/org/skepsun/kototoro/reader/data/TapGridSettings.kt#L30)、[ReaderControlDelegate.kt:31](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderControlDelegate.kt#L31)。

iOS 漫画阅读页未发现键盘翻页绑定/方向锁定入口/隐私阅读开关。Android 已有键盘 Space/PageUp/PageDown/方向键，以及音量键开关：[ReaderControlDelegate.kt:39](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderControlDelegate.kt#L39)；方向由 `ScreenOrientationHelper` 管理；无痕模式在保存历史前检查：[ReaderViewModel.kt:558](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderViewModel.kt#L558)。iOS `persist` 无条件写历史：[SourceReaderView.swift:425](../../iosApp/Kototoro/SourceReaderView.swift#L425)。iOS 音量键支持需要单独评估平台 API 和交互规范，不能把 Android 实现直接复制为可行方案；iPad 键盘和不记录历史可独立推进。

### F9：前台下载与裸目录本地漫画支持边界

**状态：部分实现；优先级中。** iOS 队列 `setForeground(false)` 把 running/queued 变为 paused 并取消 worker，没有后台继续下载。重新前台的 `pump()` 也不会把 paused 自动转 queued，需要用户恢复。见 [ReaderDownloadQueue.swift:137](../../iosApp/Kototoro/ReaderDownloadQueue.swift#L137)、[ReaderDownloadQueue.swift:119](../../iosApp/Kototoro/ReaderDownloadQueue.swift#L119)。主计划 §95 已明确仅前台页级恢复；后台能力不是该切片已交付内容。

本地漫画支持 CBZ/ZIP 文件，压缩包内部的目录分章由 `LocalBook` 解析；文件选择器没有 directory 类型，`LocalSourceRuntime.import` 也只接受文件扩展。Android 单作品/多作品目录导入是独立入口：[SingleContentImporter.kt:49](../../app/src/main/kotlin/org/skepsun/kototoro/local/data/importer/SingleContentImporter.kt#L49)、[SingleContentImporter.kt:66](../../app/src/main/kotlin/org/skepsun/kototoro/local/data/importer/SingleContentImporter.kt#L66)。iOS 差距是裸图片目录/多作品目录导入，不是“完全没有本地阅读”。

### F10：目录筛选只改变显示，没有改变实际阅读序列

**状态：部分实现；优先级中。** iOS 的“合并重复章节”只是面板 `chapters` 的临时过滤；`nextChapter/previousChapter` 始终使用 `model.content.chapters` 的共享 adjacent 规则。在存在重复章节的作品中，目录隐藏重复项不等于跨章导航会跳过它们。目录内的倒序、网格、下载筛选、隐藏之前章节也都仅在 `@State`，关闭/重建面板后恢复初值。见 [ReaderPanels.swift:12](../../iosApp/Kototoro/ReaderPanels.swift#L12)、[ReaderPanels.swift:25](../../iosApp/Kototoro/ReaderPanels.swift#L25)、[SourceReaderView.swift:83](../../iosApp/Kototoro/SourceReaderView.swift#L83)。Android 阅读状态计算使用 `getReadingChapters()` 并对 `isMergeRepeatedChapters` 处理合并键：[ReaderViewModel.kt:1275](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderViewModel.kt#L1275)。应验证在真实重复章/多分支夹具中，iOS 是否需要统一目录与实际导航序列。

## 基础功能验收建议

1. 优先做长页中段书签跨章恢复、邻页故意失败不遮挡当前页、坏离线 manifest/图片一键恢复三类夹具；这些问题可用确定输入验证，不依赖真实网站稳定性。
2. 再验五种阅读模式的最后一页拖动、条漫章末自动滚动、向前/向后换章及上一章末尾锚点。
3. 横向条漫单独验 RTL offset、点击/自动滚动方向、页码滑条和重启恢复；先确定坐标约定，再开放方向/缩放选项。
4. 在真机/iPad 验旋转/分屏、后台后被系统终止、自动滚动时锁屏、键盘与 VoiceOver。已有 XCTest/共享单测只说明有覆盖实现，不是本轮通过记录。

现有可复用测试位置：[IosProductTest.kt:86](../../ios-shared/src/iosTest/kotlin/org/skepsun/kototoro/ios/IosProductTest.kt#L86)（共享 scene/拆页/偏移/缩放）、[SourceRuntimeTests.swift:127](../../iosApp/KototoroUITests/SourceRuntimeTests.swift#L127)（翻页/书签入口/下载/重启）、[LocalImportTests.swift](../../iosApp/KototoroUITests/LocalImportTests.swift)。本轮未执行这些 iOS 测试；共享 JVM 测试的实际结果见末节。

## 性能与图像管线审查

### 已存在的优化

当前 iOS 并非串行逐页现用现下：图片请求有 `RequestGate`，默认 3 并发，可见请求会提升正在等待的预取；同章页面下载任务共享，窗口外未完成预取会被取消；默认预读 6 页、候选窗口 10 页，接入低电量/低数据/网络计费策略。邻页提前解码、接近章末预热下一章页面列表和首批图片也已实现。见 [ImageLoadPolicy.swift:88](../../iosApp/Kototoro/ImageLoadPolicy.swift#L88)、[SourceReaderView.swift:198](../../iosApp/Kototoro/SourceReaderView.swift#L198)、[SourceReaderView.swift:246](../../iosApp/Kototoro/SourceReaderView.swift#L246)。

文件 SHA 校验与初始解码在 detached task；单张预览输出使用共享 `ImageDecodeSize` 限制到 4 Mi 像素/单边 16384；长图可见 tile 几何由共享 `VisibleImageTiles` 计算。`ReaderTileBaseCache` 对同一 SHA/sample 的解码去重，有 192 MiB LRU 和内存告警清理。见 [SourceImageLoader.swift:42](../../iosApp/Kototoro/SourceImageLoader.swift#L42)、[SourceImageLoader.swift:100](../../iosApp/Kototoro/SourceImageLoader.swift#L100)、[IosReader.kt:175](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosReader.kt#L175)。这些能力不能再列为“完全缺失”，但它们也不等于下面各层已经对齐。

### P1：高清 tile 的显示坐标、采样密度和边缘裁切没有完整对齐

**性质：确定的坐标契约遗漏；可见表现需 iOS 图像夹具验证。优先处理。**

共享 `VisibleImageTiles` 先把 scene 可见区域转到图片空间，再创建 `TileGrid(ImageSourceGeometry(encodedSize))`；返回的 `logicalRect` 仍是原图页像素。`IosReader.visibleTiles` 原样导出其 left/top/width/height，而 `ReaderTiledOverlay` 直接将这些数值用作 SwiftUI 的 frame/offset，没有乘图片空间到显示空间的比例。

例如，2000 像素宽的长页排版到 500 point 宽，比例应为 0.25；源空间一块宽 2000 的 tile 应显示为 500 point，现行 overlay 却给它 2000 point。已有共享测试也明确 scene 高度约 30000 时 tile.logicalRect.top 可超过 45000，证明返回值不是 scene 坐标。证据：[VisibleImageTiles.kt:15](../../reader-core/src/commonMain/kotlin/org/skepsun/kototoro/reader/core/VisibleImageTiles.kt#L15)、[VisibleImageTilesTest.kt:9](../../reader-core/src/jvmTest/kotlin/org/skepsun/kototoro/reader/core/VisibleImageTilesTest.kt#L9)、[IosReader.kt:229](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosReader.kt#L229)、[SourceReaderView.swift:1096](../../iosApp/Kototoro/SourceReaderView.swift#L1096)。

还有两项相关遗漏：

- `decodeRegion` 默认包含 seam padding；Swift 把整块解码图拉伸到 `logicalRect`，没有先裁去 padding。Android `TiledPageDrawMath` 分别计算 src 裁切和 dst 缩放：[ComposeSceneRenderer.kt:685](../../app/src/main/kotlin/org/skepsun/kototoro/reader/render/compose/ComposeSceneRenderer.kt#L685)、[TileGrid.kt:41](../../reader-core/src/commonMain/kotlin/org/skepsun/kototoro/reader/core/TileGrid.kt#L41)。
- Swift 传入的是 `zoomScale`，没有屏幕 `displayScale`；连续模式恒传 1。共享采样按 source/scene/displayScale 求解，因此 Retina 屏可能取到不足物理像素需求的 LOD。`IosReader` 又把同一个参数同时用于相机视口反算和采样，不能简单将 zoom 乘 Retina 密度后继续传同一参数，否则会改变可见区域。见 [SourceReaderView.swift:692](../../iosApp/Kototoro/SourceReaderView.swift#L692)、[SourceReaderView.swift:1059](../../iosApp/Kototoro/SourceReaderView.swift#L1059)、[IosReader.kt:217](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosReader.kt#L217)。

最小修正方向：分清相机缩放、point 到物理像素密度、图片到页面的缩放；为 tile 返回/计算明确的 src 与 dst 矩形。先用网格/编号长图验证位置、接缝和 1×/2×/3× 清晰度，再调整缓存。

### P2：有可见区域选块，但还没有与 Android 等价的区域解码

**性质：确定的解码机制差异；峰值和耗时待测。**

iOS 常规 tile 先由 `ReaderTileBaseCache` 按 sample 解码整页，再 `CGImage.cropping`；整页估算超过 192 MiB 时，fallback `decodeRegion` 仍是读取/校验整份文件、`CGImageSourceCreateImageAtIndex` 创建完整图像、再裁切和缩小。它没有把 rect 作为压缩图像区域解码的输入。见 [SourceImageLoader.swift:14](../../iosApp/Kototoro/SourceImageLoader.swift#L14)、[SourceImageLoader.swift:121](../../iosApp/Kototoro/SourceImageLoader.swift#L121)、[SourceImageFile.swift:53](../../iosApp/Kototoro/SourceImageFile.swift#L53)。

因此缓存能消除常见场景的逐 tile 重复整页解码，但在极长图、高倍缩放、缓存超限路径，重复读/hash/整图创建仍可能发生。不能从“返回 tile 小于 2 MiB”推导“内部只分配 2 MiB”。ImageIO 的实际解码时机和临时内存也不能仅凭函数名判断。

Android 通过每页复用的 `BitmapRegionDecoder` session，将 `region` 与 `inSampleSize` 一起传入 `decodeRegion`，tile 成本按真实 bitmap allocation 记账。见 [AndroidRegionDecoderFactory.kt:130](../../app/src/main/kotlin/org/skepsun/kototoro/reader/image/AndroidRegionDecoderFactory.kt#L130)、[AndroidRegionDecoderFactory.kt:162](../../app/src/main/kotlin/org/skepsun/kototoro/reader/image/AndroidRegionDecoderFactory.kt#L162)、[KototoroImagePipelineAdapter.kt:95](../../app/src/main/kotlin/org/skepsun/kototoro/reader/image/KototoroImagePipelineAdapter.kt#L95)。

建议先验证平台解码器是否能保证区域输出和受控峰值；若不能，为超长页选择一次受控生成分块文件等方案，保持原始文件与请求上下文，避免只把整图路径改名为“区域解码”。不预设一定要引入新依赖。

### P3：192 MiB 缓存预算不等于阅读器实际驻留上限

**性质：确定的资源所有权/记账缺口；不能直接预测 OOM。**

`ReaderTileBaseCache.bytes` 只计算 entries 内整页底图。`model.images` 按附近页数保留预览，overlay 的 `decodedTiles`、在途解码、压缩数据、GPU 和 JVM 内存都未进入这份账；连续模式邻页窗口覆盖三屏，页数也不是固定 6。见 [SourceReaderView.swift:185](../../iosApp/Kototoro/SourceReaderView.swift#L185)、[SourceReaderView.swift:290](../../iosApp/Kototoro/SourceReaderView.swift#L290)、[SourceReaderView.swift:1083](../../iosApp/Kototoro/SourceReaderView.swift#L1083)、[SourceImageLoader.swift:157](../../iosApp/Kototoro/SourceImageLoader.swift#L157)。

更重要的是 Apple 明确说明裁切得到的 CGImage 保留原图引用。因此 entries 淘汰一张 base 时，overlay 手中的裁切图仍可能保留它；账面减掉 base 不代表其实际内存已经释放。见 [Apple cropping(to:) 文档](https://developer.apple.com/documentation/coregraphics/cgimage/cropping(to:))。此项是由官方所有权规则和当前调用链得到的推论，不是本轮测得的泄漏。

`clear()` 仅清 entries/order/bytes，没有取消 pending 或设置 generation。告警/退出后，正在运行的 base task 仍可完成并重新写回缓存。见 [SourceImageLoader.swift:148](../../iosApp/Kototoro/SourceImageLoader.swift#L148)、[SourceImageLoader.swift:168](../../iosApp/Kototoro/SourceImageLoader.swift#L168)。

Android 有 VISIBLE/NEARBY/STANDBY/CACHE 的保留等级和真实 payload 成本账本，退出页可取消 tile job、释放页资源；也必须注意其默认 256 MiB tile 预算会保留 VISIBLE，**同样不是整个进程的硬上限**。参见 [ReaderTileManager.kt:92](../../app/src/main/kotlin/org/skepsun/kototoro/reader/image/ReaderTileManager.kt#L92)、[TileMemoryBudget.kt:135](../../app/src/main/kotlin/org/skepsun/kototoro/reader/image/TileMemoryBudget.kt#L135)。iOS 对齐目标应是清晰的所有权和可观测预算，而不是照抄 256 MiB。

### P4：网络并发已限流，漫画解码并发与资源取消仍不完整

**性质：确定的调度差异；竞争和浪费程度待测。**

`pageGate` 只包住 `runtime.image`。网络返回后各页 `loadedPage` 都能发起 detached 解码，tile base/fallback 又各有 detached task；漫画路径没有独立的 decode gate。封面路径已使用 `RequestGate.coverDecoding(limit: 3)`，不能把这个限制误认为漫画也有。Android Coil decoder 也有限并发 3，但这不表示 Android 所有原生 tile 解码都统一限成 3。见 [SourceReaderView.swift:230](../../iosApp/Kototoro/SourceReaderView.swift#L230)、[SourceImageLoader.swift:45](../../iosApp/Kototoro/SourceImageLoader.swift#L45)、[ImageLoadPolicy.swift:13](../../iosApp/Kototoro/ImageLoadPolicy.swift#L13)、[SourceBrowseView.swift:450](../../iosApp/Kototoro/SourceBrowseView.swift#L450)、[AppModule.kt:214](../../app/src/main/kotlin/org/skepsun/kototoro/core/AppModule.kt#L214)。

页面/overlay task 取消不自动取消其 detached task，缓存 pending 还被设计为继续完成。`onDisappear → stopPreloading` 取消邻页 load 和下一章预热，却没有遍历取消本章 fetches；本章 `isPrefetch == true` 的请求也不会在 consumers 归零时被 release 取消。见 [SourceReaderView.swift:209](../../iosApp/Kototoro/SourceReaderView.swift#L209)、[SourceReaderView.swift:340](../../iosApp/Kototoro/SourceReaderView.swift#L340)、[SourceReaderView.swift:571](../../iosApp/Kototoro/SourceReaderView.swift#L571)。离屏后仍可能下载/解码并回填缓存，应给离屏、换章、取消交互建立明确的任务所有权。

低电量/网络策略已有，但 iOS 的预读判断没有内存压力门控；Android `isPrefetchApplicable` 包含低可用 RAM 条件。见 [ImageLoadPolicy.swift:121](../../iosApp/Kototoro/ImageLoadPolicy.swift#L121)、[PageLoader.kt:131](../../app/src/main/kotlin/org/skepsun/kototoro/reader/domain/PageLoader.kt#L131)。先抑制非必要预读/解码，再清缓存；无需机械照抄 Android RAM 阈值。

### P5：尺寸更新没有消费共享视口补偿，长章更新有全量成本

**性质：确定的接线缺口和复杂度；是否出现可见跳动待测。**

初始页面比例统一估计为 0.7；解码后 `setImageSize` 调 `scene.updatePageHint`，不传 currentViewport，也不接收/返回 `AnchorCompensation`。随后 Swift 替换整份 `slots`。Android 连续 scene 在真实尺寸到达时传当前视口并应用 delta，保持正在读的位置。见 [IosReader.kt:100](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosReader.kt#L100)、[IosReader.kt:114](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosReader.kt#L114)、[SourceReaderView.swift:176](../../iosApp/Kototoro/SourceReaderView.swift#L176)、[ComposeSceneWebtoonReader.kt:642](../../app/src/main/kotlin/org/skepsun/kototoro/reader/ui/compose/ComposeSceneWebtoonReader.kt#L642)。

快速滑过还未知道尺寸的长页，或恢复到章中部后前面的图片才加载完成时，页面高度变化与保存 offset 之间缺少显式锚点补偿。SwiftUI 自身可能有布局锚定行为，但没有证据证明它等价于共享进度锚点，须专门验证。

`IosReader.slots()` 每次遍历并导出整章 DTO，`VerticalReaderScene.updatePageHint` 又重排变更页后的条目。如果 N 页都逐一得到尺寸，重排和导出在最坏情况下是 O(N²) 工作量。普通滚动没有每帧重建整章，但下面的滚动查找仍有线性成本。见 [IosReader.kt:153](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosReader.kt#L153)、[VerticalReaderScene.kt:240](../../reader-core/src/commonMain/kotlin/org/skepsun/kototoro/reader/core/VerticalReaderScene.kt#L240)。

最小推进：尺寸到达时输出并消费补偿；随后根据实测合并批量尺寸更新、缓存页到 slot 的索引，减少反复全章导出。共享 scene 的重排成本并非 iOS 独有，iOS 额外的 Swift DTO 全量导出是具体优化点。

### P6：连续滚动更新范围偏大，tile 没有 Android 的保留/邻域策略

**性质：确定的更新与缓存策略差异；卡顿需 Instruments 归因。**

滚动每次 contentOffset 改变都写视图 `scrollOffset`，并调用 `continuousPosition` 写 `@Published index/slotIndex`，其中还用 `slots.first` 查找；各页面均 `@ObservedObject` 观察完整模型。`ReaderPageTile.body` 再调用跨语言 `visibleTiles`、创建 tile DTO 和字符串 signature。见 [SourceReaderView.swift:375](../../iosApp/Kototoro/SourceReaderView.swift#L375)、[SourceReaderView.swift:652](../../iosApp/Kototoro/SourceReaderView.swift#L652)、[SourceReaderView.swift:1042](../../iosApp/Kototoro/SourceReaderView.swift#L1042)。

overlay 的 signature 变化会替换 task，只保留本次 activeKeys，并按顺序逐块 decode。没有 tile 级 NEARBY/STANDBY，`VisibleImageTiles` 查询 halo 设为 0；回滚出界 tile 会被重建，超限 fallback 路径可能重新读/hash/创建整图。已有整页 base cache 可以缓解常规路径，不能直接声称每个滚动像素都会重解码所有 tile。见 [SourceReaderView.swift:1107](../../iosApp/Kototoro/SourceReaderView.swift#L1107)、[VisibleImageTiles.kt:26](../../reader-core/src/commonMain/kotlin/org/skepsun/kototoro/reader/core/VisibleImageTiles.kt#L26)。Android tile manager 区分可见/邻域请求，出界资源逐级降级并去重：[ReaderTileManager.kt:92](../../app/src/main/kotlin/org/skepsun/kototoro/reader/image/ReaderTileManager.kt#L92)、[ReaderTileManager.kt:279](../../app/src/main/kotlin/org/skepsun/kototoro/reader/image/ReaderTileManager.kt#L279)。

Apple 建议用 SwiftUI Instruments 的更新原因图确认过度更新，再缩小依赖范围、避免无变化发布与重复计算。这里适合先对 index/slotIndex 做值变化检查、把高频视口与低频 chrome 状态分开，保留小范围 tile 缓存；是否迁移到 Observation 应由测量决定。参见 [Apple SwiftUI 性能指南](https://developer.apple.com/documentation/xcode/understanding-and-improving-swiftui-performance)。

### P7：长章离线元数据读写和下载检查点仍有主线程/放大成本

**性质：确定的 I/O 与序列化路径；长章耗时待测。**

读离线图片前 `manifest()` 每次同步读取并 JSON 解码整个 `chapter.json`，由 `@MainActor SourceReaderModel.loadImage` 调用时发生在主 actor；加载页列表还重新解码完整 `IosPageArchive`。见 [ReaderDownloads.swift:28](../../iosApp/Kototoro/ReaderDownloads.swift#L28)、[ReaderDownloads.swift:34](../../iosApp/Kototoro/ReaderDownloads.swift#L34)、[ReaderDownloads.swift:124](../../iosApp/Kototoro/ReaderDownloads.swift#L124)。

下载逐页执行时，每页重写包含整章 archive 与累积 image records 的 partial manifest；队列每个 progress 回调又在 main actor 编码、原子写入完整 Snapshot（最多 500 entries/32 MiB）。虽然 partial 文件写入放到 detached，JSON 编码和队列快照写入没有全部离开主 actor。累计 records 序列化有 O(N²) 字节量，整章 archive 被重复 N 次。见 [ReaderDownloads.swift:76](../../iosApp/Kototoro/ReaderDownloads.swift#L76)、[ReaderDownloads.swift:109](../../iosApp/Kototoro/ReaderDownloads.swift#L109)、[ReaderDownloadQueue.swift:157](../../iosApp/Kototoro/ReaderDownloadQueue.swift#L157)、[ReaderDownloadQueue.swift:178](../../iosApp/Kototoro/ReaderDownloadQueue.swift#L178)。

优先缓存当前已验证 manifest、把文件 I/O/序列化移出 UI actor；在不损失逐页恢复语义的前提下合并队列进度写盘。是否需要分片清单再由千页章/大队列测量决定，避免提前重建整个存储系统。

### P8：图片落盘不等于具有跨会话阅读缓存与磁盘预算

**性质：确定的应用层缓存缺口；实际源 HTTP 缓存命中不能一概否定。**

同一个 reader model 中，已完成 fetch Task 保留 artifact，可回读原文件；新会话 `IosSources.image` 则再次 `runtime.fetchImage`。Mihon 会调用扩展 `getImage`，parser-host 会再次执行原客户端请求。`FileSourceImageStore` 在响应流读完、算出 SHA 后才检查已有文件，属于内容去重发布，不是请求前的 page→artifact 查找。底层扩展/HTTP 客户端仍可能命中自身缓存，因此不能声称每次必然重走网络。见 [IosSources.kt:396](../../ios-shared/src/iosMain/kotlin/org/skepsun/kototoro/ios/IosSources.kt#L396)、[MihonSourceRuntime.kt:255](../../source-host/src/main/kotlin/org/skepsun/kototoro/source/host/MihonSourceRuntime.kt#L255)、[ParserSourceRuntime.kt:118](../../parser-host/src/main/kotlin/org/skepsun/kototoro/parserhost/ParserSourceRuntime.kt#L118)、[FileSourceImageStore.kt:34](../../source-host/src/main/kotlin/org/skepsun/kototoro/source/host/FileSourceImageStore.kt#L34)。Android PageLoader 在下载前查 `cache.get(pageUrl)`：[PageLoader.kt:497](../../app/src/main/kotlin/org/skepsun/kototoro/reader/domain/PageLoader.kt#L497)。

iOS store 的 64 MiB 是单文件输入限制，未见图片目录总预算/按引用回收；完整下载另复制图片到章目录。下载管理“移除任务”保留文件，现行界面没有相应存储占用与删章入口。见 [FileSourceImageStore.kt:14](../../source-host/src/main/kotlin/org/skepsun/kototoro/source/host/FileSourceImageStore.kt#L14)、[ReaderDownloads.swift:99](../../iosApp/Kototoro/ReaderDownloads.swift#L99)、[ReaderDownloadsView.swift:49](../../iosApp/Kototoro/ReaderDownloadsView.swift#L49)。

建议用稳定来源身份、扩展版本和完整页面请求上下文建立经过校验的缓存索引，保持显式下载与可淘汰缓存的所有权区别；先有引用核对/占用显示，再提供清理，避免误删离线内容。

### P9：大图的组合功能和图像格式覆盖还不完整

**性质：明确功能边界；格式支持需要样本矩阵。**

裁白边只走 `cropMargins && !tiled`；大图/长页不裁。宽页拆半的 `segment != 0` 又被排除在 tile overlay 之外，因此该路径只有有界 overview，放大不会获得更高精度分块。导出/裁切批注基于 `model.image`，大图也不能认为导出原始分辨率。见 [SourceImageLoader.swift:55](../../iosApp/Kototoro/SourceImageLoader.swift#L55)、[SourceReaderView.swift:1057](../../iosApp/Kototoro/SourceReaderView.swift#L1057)、[SourceReaderView.swift:927](../../iosApp/Kototoro/SourceReaderView.swift#L927)。

解码固定 ImageIO index 0，没有多帧 GIF/WebP/AVIF 播放链；Android pipeline 则有 Animated asset、AVIF 动画池。区域 fallback 没有像 thumbnail transform 路径一样处理 EXIF 方向；涉及旋转图片时，还需统一 raw encoded 与 display 坐标。见 [SourceImageFile.swift:18](../../iosApp/Kototoro/SourceImageFile.swift#L18)、[SourceImageFile.swift:53](../../iosApp/Kototoro/SourceImageFile.swift#L53)、[KototoroImagePipelineAdapter.kt:241](../../app/src/main/kotlin/org/skepsun/kototoro/reader/image/KototoroImagePipelineAdapter.kt#L241)、[AppModule.kt:206](../../app/src/main/kotlin/org/skepsun/kototoro/core/AppModule.kt#L206)。静态 WebP/AVIF 是否正常应逐格式验证，不能仅从使用 ImageIO 判断全部支持或全部不支持。

## 建议推进顺序与验收

本节顺序表示本轮工程优先级，前文 P1–P9 只是发现编号。

| 顺序 | 工作 | 最小验收 |
| --- | --- | --- |
| 1 | 修正 tile src/dst/Retina 契约、长页书签 offset、按页错误重试 | 带编号/网格的长图在中段/底部位置与接缝正确；3× 屏清晰度符合输入；邻页失败不遮挡成功页；书签恢复中段 |
| 2 | 接入尺寸变更的 AnchorCompensation，统一横向阅读坐标和跨章行为 | 慢图尺寸到达时锚点稳定；五种模式前后跨章、横向 tap/auto/slider/重启一致 |
| 3 | 完成解码预算、任务所有权与内存告警降载 | 快速翻页/高倍缩放/退出时非必要解码受控；清理后 pending 不回填旧缓存；记录实际持有内存 |
| 4 | 修复离线损坏恢复、缓存索引、长章元数据 I/O | 损坏文件可明确修复；同请求重开命中已验证缓存；千页章加载和队列进度不阻塞 UI |
| 5 | 缩小滚动更新范围、补 tile 保留策略与常亮/后台保存 | 对同一夹具测 SwiftUI 更新次数、tile decode 次数、滚动 hitches、后台续读 |
| 6 | 补横向缩放、自动模式/双页、目录导航语义、键盘等 | 在基础稳定后逐项扩展回归矩阵；OCR/翻译按既有约定暂缓 |

建议性能夹具至少覆盖：常规 30 页漫画；300/1000 页章节；2000×12000、2000×120000 长图；触发 base 超限的高倍缩放；宽页拆分/双页；EXIF 旋转图；完整离线与损坏离线；阅读同时下载；低电量/内存告警/后台退出。以上尺寸是建议测试输入，不是本轮已测数据。

在同一真机、同一资源、Release 配置下分别记录冷首图、热重开、连续/分页翻动、1×→2×→4× 缩放的：首个可用图/高清层延迟、hitch time ratio、内存峰值和退出后保留量、解码并发/次数、SHA 扫描字节、tile 失效/重新上传、磁盘增长和电量/温度状态。iOS 与 Android 使用各自平台帧指标和相同用户步骤对照，不把模拟器时间或两种平台指标直接当作设备性能差值。

Apple 提供 `XCTMemoryMetric` 测峰值/增长，以及动画/滚动 signpost 指标捕获 hitches；建议独立 Release 性能 scheme，关闭 debugger、coverage 与自动截图。见 [Preventing memory-use regressions](https://developer.apple.com/documentation/xcode/preventing-memory-use-regressions)、[Eliminate animation hitches with XCTest](https://developer.apple.com/videos/play/wwdc2020/10077/)。是否达到 Android 水平必须建立这份设备基线，不能用共享几何测试或“图片成功显示”代替。

## 本轮验证与结论边界

- 已重新执行 `./gradlew :reader-core:jvmTest --rerun --no-daemon`，**27 个测试套件、187 项测试，0 失败/错误/跳过**；`jvmTest` 实际执行，编译产物复用。覆盖共享几何、长页可见 tile、进度、缩放与视口语义。
- 复核了 Swift/Kotlin、Android 现行 scene 与 image 管线、iOS 测试源码和既有交付文档，确认旧的“无预读/下载/恢复”描述不能继续当差距。
- 当前 iOS XCTest 包含小图 SHA/裁切与请求优先级测试；`IosProductTest` 检查 tile 非空和输出范围，未验证 Swift overlay 的显示坐标/实际解码峰值。全 Swift 测试源码未发现 `measure(...)`、`XCTMemoryMetric`、`XCTOSSignpostMetric` 性能基线。证据：[SourceImageFileTests.swift:46](../../iosApp/KototoroUITests/SourceImageFileTests.swift#L46)、[ImageLoadPolicyTests.swift:8](../../iosApp/KototoroUITests/ImageLoadPolicyTests.swift#L8)、[IosProductTest.kt:119](../../ios-shared/src/iosTest/kotlin/org/skepsun/kototoro/ios/IosProductTest.kt#L119)。
- 本轮没有运行 iOS UI/真机性能采集，没有测得 FPS、峰值内存、温度、掉帧比例或跨平台性能差值。源码确定项、由机制推断的风险、待实测项已经分开标注。
- 工程原则：研究只新增这一份文档；建议优先补全现有共享几何契约、平台资源所有权与确定回归夹具，避免平行阅读器/一对一抽象层；不增加依赖，不改变产品代码或 Git 分支/提交。

## 2026-10-14 交互修复：缩放、双击、拖拽翻页与跨章

| 问题 | 根因 | 处理 |
| --- | --- | --- |
| 放大后拖拽卡顿 | 分页缩放用 SwiftUI `MagnifyGesture`/`DragGesture` 改 `@State`，每帧重建页面并重算 `visibleTiles`、取消正在解码的 tile；拖拽同时也驱动外层分页 ScrollView。条漫的 `scrollOffset` 是 `@State`，每帧重渲染整个阅读器 | 分页槽改为 `ReaderPageZoom`：页面仍是 SwiftUI，缩放/平移交给原生 `UIScrollView`（SwiftUI 无原生缩放滚动视图），有惯性/回弹，放大后到边缘再把拖拽交给翻页器（对应 Android Telephoto 的未消费平移）。tile 只按停稳后的相机（及长距离平移时每 1/3 屏）重算。条漫滚动偏移改为非视图状态，tile 视口按 1/4 屏步进 |
| 双击放大不跟随手指 | 分页双击只把 scale 设为 2，锚点固定在中心 | 双击按 Android：未放大时放大到 `DOUBLE_TAP_ZOOM`（2×）并保持触点不动，已放大时回到适配；最大倍率共用 `PagedCameraTransform.MAX_SCALE` |
| 拖到一半页面被替换 | 拖动中按偏移四舍五入提交选中页，`scrollPosition(id:)` 绑定随之改变并程序化滚动；SwiftUI 也可能在原生分页回弹结束前报告 idle | 分页 ScrollView 去掉位置绑定，只用 proxy 移动；只在停稳（idle 且偏移连续两帧对齐页边界）后提交 |
| 末页拖入下一章跳到下一章末页附近 | 提交后立即重建窗口（前一章只保留末 2 页），像素偏移不变；原生分页回弹随后继续，按旧偏移把新窗口中的 `n:6` 提交为阅读页 | 同上的停稳判定；拖动记录开始时的窗口版本，跨越窗口重建的拖动不提交；重建后同步回到阅读槽 |

SSIV 对应关系：base 为页面在像素预算（4 MP）内的整图解码，超过预算的大图按当前相机的 sample size 解码可见区域 tile（`VisibleImageTiles`）。本轮补上 SSIV 的过渡策略：新相机的 tile 解码完成前保留旧 tile（粗采样在下），不再回落到模糊 base。

回归：`LocalImportTests/testPagedDragsAcrossLongChaptersKeepTheChapterLocalPage`（新夹具 `fixture_reader_paged`，3 章 × 8 页逐页拖动跨章）、`testPagedDoubleTapZoomsAtTheTapAndPansBeforeTurning`、`testContinuousDoubleTapZoomsAtTheTap`（新夹具 `fixture_reader_zoom`，红/绿/蓝三等分，用屏幕像素判断缩放锚点）。修复前第一个用例稳定复现“进入第 2 章第 7 页”。拖到一半被替换的问题由机制修复，XCUITest 无法在按住手指时截图，未单独自动化。

## 2026-10-19 条漫重构：共享场景单一坐标、全屏与资源窗口

用户反馈的三个问题：条漫上下各有一条黑边；读久了全部页面一直转圈；往回翻时可能直接跳到上一章。

| 问题 | 根因 | 处理 |
| --- | --- | --- |
| 上下黑边 | 画布所在的 `GeometryReader` 受安全区约束，只有背景忽略了安全区；滚动视图另外再加安全区 inset，代码里只能靠 `rawRange` 折算 | 阅读层整体 `.ignoresSafeArea()`；条漫的 `UIScrollView` 设 `contentInsetAdjustmentBehavior = .never`（同 Aidoku），宿主 `safeAreaRegions = []`。只有控件层留在安全区内，显示/隐藏控件不再让页面移动或重排 |
| 跳到上一章 | 页面坐标有两套：SwiftUI `LazyVStack` 按估算高度排未建出的行，`VerticalReaderScene` 另算一套。前插上一章后重建场景，`PageId` 用的是窗口位置；条漫视图 `.id(slot.index)` 整体后移，再经 `Task.yield` 用 `ScrollPosition` 点坐标恢复，落到上一章，`continuousPosition` 随即切章并级联重建。另外章内前 10 页就会预载并前插上一章 | 条漫改用 UIKit `ReaderStrip`（`UIScrollView`），内容大小与每页 frame 直接取自 scene，坐标只此一套。`IosReader` 在连续模式下用章节内稳定 ID，窗口变化走 `updatePages(viewport)` 锚点补偿（Android `snapBy(deltaY)` 的做法），在同一主线程轮次直接写 `contentOffset`（不打断惯性）。上一章只在离条漫起点 3 屏以内时预载（类似 Aidoku 到顶才前插） |
| 读久后全部转圈 | ① Kotlin/Native 的 async 导出不传递 Swift 取消，已滑走的下载一直占着阅读线程闸门（默认 3 个），来源一旦挂住就永久占满；② `loadImage` 遇到“正在加载”直接返回而不等待，这次加载被邻页调度或窗口重建取消后，没人再加载这一页 | `IosSources.image(page, IosCancellation)` 把 Swift 取消转发给协程，并有 90 秒超时。`RequestGate` 修复“排队前已取消”的竞态。页面加载按页身份归模型所有，后来的调用方等待同一任务；窗口重建只重映射按位置存的状态，不再取消全部加载。资源窗口改用共享 `ReaderPrediction`（参数同 Android 条漫默认值），停稳时强制重新规划，把未显示也未在加载的页重新启动 |

验证：`IosProductTest` 新增前插/裁章保持阅读页（纵向、横向、RTL）以及资源窗口覆盖与重规划两项；`IosLocalImportTest` 覆盖可取消图片接口。

补充两条与 Android 语义不同、需要 iOS 侧单独处理的规则：

- **末端保留的阅读页**：iOS 在条漫末端会把短于屏幕的末页保留为阅读页，即使它不是首个可见页（Android 的阅读页始终是首个可见页）。上方长页的真实尺寸到达时，scene 的锚点修正会固定首个可见页，把短章推出屏幕，随即误切回上一章（`testPreviousChapterButtonOpensTheChapterStart` 能复现）。现在阅读页处于保留状态时，改为固定阅读页本身的屏幕位置。
- **恢复等阅读页图片**：阅读页图片到达前，估算高度会把长页保存的页内偏移截断在条漫末端，因此恢复要等阅读页图片到达才算完成（`testDisplayPreferenceAndBackgroundPreserveTheLongPagePosition`）。
- 程序移动条漫（锚点修正、被末端截断）后清空移动方向基准，不把几何变化当成用户往回滚动。
