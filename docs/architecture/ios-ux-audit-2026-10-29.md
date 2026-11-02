# iOS 全页面 UI/UX 与流畅度审计（2026-10-29，Mac 复核）

接续同日的 [导航与页面性能检查](ios-navigation-performance-2026-10.md)。本轮在 Mac 上进行，
有 Xcode 与 iOS 27 模拟器，但 **没有** 真机 Instruments trace：下文的性能结论都来自源码路径分析，
优先级表示建议的处理顺序，不代表已测得的卡顿贡献。已在上一份文档列出且未变化的项目只引用，不重复。

## 本轮已实现

| 需求 | 实现 | 依据 |
| --- | --- | --- |
| 记录 Tab 显示未读更新章节数 | `LibraryModel.unreadUpdateChapters` 汇总 `tracks.chapters_new`（阅读章节时由 `completeUpdateChapter` 递减，与作品库封面角标同源），跳过“更新记录”成人过滤隐藏的作品；`ContentView` 记录 Tab 加 `.badge(_:)`，0 时系统自动不显示。 | [HIG Tab bars：用角标传达与该 Tab 相关的新信息](https://developer.apple.com/design/human-interface-guidelines/tab-bars)、[`badge(_:)`](https://developer.apple.com/documentation/swiftui/view/badge(_:)-84e43) |
| 记录条目点击直接进作品详情 | 时间轴阅读/更新/下载条目点击即在记录页 `NavigationStack` 中推入 `SourceDetailsView`（下载条目仍用 `withoutChapters`），点击更新条目同时标为已查看。原“记录详情”页没有删除，移入长按菜单，书签条目保持“点击即回到书签位置”。 | [HIG Navigation：减少到达内容所需的层级](https://developer.apple.com/design/human-interface-guidelines/navigation-and-search)、[HIG Context menus：放次要操作](https://developer.apple.com/design/human-interface-guidelines/context-menus) |

UI 测试 `LocalImportTests/testReadingStatisticsPersistAndAppearInHistoryAndTheCenteredTimeline` 与
`testDetailsChapterSelectionDownloadsWithoutOpeningReader` 已改为断言“点击直达详情 / 长按查看记录详情”。

## 审计发现（未修改）

参考范例：Apple Books（阅读器沉浸与目录）、Photos/Files（批量选择操作栏）、TV/系统播放器
（`AVPlayerViewController` 手势与 Now Playing）、Music（搜索建议与最近搜索），以及
[Food Truck](https://developer.apple.com/documentation/swiftui/food_truck_building_a_swiftui_multiplatform_app)
的值导航结构。

### P1：直接影响操作流畅度

1. **视频播放器单击要等双击超时**（`VideoPlayerView.swift:422-423`）。`onTapGesture(count: 2)` 在
   `onTapGesture` 之前，SwiftUI 必须等双击判定失败（约 0.3 s）才切换控件，点一下有明显迟滞。
   系统播放器用捏合切换填充/适应，单击立即响应。建议：填充改为 `MagnifyGesture`，或双击只在左右
   区域做快进/快退（与 TV app 一致），单击路径不再等待。
2. **作品详情章节目录随下载进度整页重算**（`DetailsChaptersView.swift:26-31, 140-146`）。`chapters`
   与 `directory` 在 body 中做 `chapterList` + 反转 + 搜索过滤 + 遍历整个下载队列建字典 +
   `ChapterRow.rows`。下载时队列每页进度都会发布，2000+ 章节的目录每次都完整重算。建议在 model 中
   按（章节 revision、排序、查询）缓存行，下载状态只按本作品条目变化发布。
3. **来源浏览只在最后一个作品出现时才加载下一页**（`SourceBrowseView.swift:234-236`）。快速滑动会
   先撞到底部再等网络，出现停顿。建议在剩余约 1–2 屏（如最后 12 项任一出现）时预取，并保持现有
   “出错不自动重试”的条件。
4. **全局搜索结果在用户浏览时跳动**（`GlobalSearchView.swift:250-275`）。每个加载中的来源占一行标题 +
   60 pt 进度条，完成后若无结果整块消失，上方内容收缩，正在看的结果被挤走。建议加载中的来源只汇总在
   顶部进度行，有结果的来源按完成顺序追加到末尾，已显示的行不再移动。
5. **笔记列表在主线程同步解码原图**（`NotesView.swift:113`）。`UIImage(contentsOfFile:)` 在每行 body
   中读取并按原尺寸解码截图，没有缓存。笔记一多滚动就会掉帧。建议复用 `SourceImageFile.decode` 的
   降采样与后台 `.task` 加载（`BookmarkImageStore` 已有 600 px 先例）。

### P2：HIG 一致性与可访问性

6. **漫画/小说阅读器隐藏控件时不隐藏 Home 指示条**。视频已用 `persistentSystemOverlays(.hidden)`，
   `SourceReaderView.swift:1498` 与 `NovelReaderView.swift:683` 只隐藏了状态栏。Apple Books 沉浸阅读时
   指示条会自动淡出。建议与 `statusBarHidden` 同步加 `.persistentSystemOverlays(controls ? .automatic : .hidden)`。
7. **详情简介的点击与文本选择冲突**（`SourceDetailsView.swift:913-917`）。简介同时有
   `.textSelection(.enabled)` 与 `.onTapGesture`，标题处注释已记录真机上文本交互会吞掉点击；
   不足 100 字但超过 3 行的简介又没有“展开”按钮，可能无法展开。建议去掉点击展开，改为总是在截断时
   显示“展开”（用 `ViewThatFits` 或测量截断判断）。
8. **点击区域小于 44×44 pt**：详情页来源胶囊与“追踪与资料”胶囊（caption 字号 + 上下 4 pt，约 24 pt 高，
   `SourceDetailsView.swift:690-720`）、记录更新下载按钮 28×28（`LibraryRecordsView.swift`）。
   [HIG 要求最小 44×44 pt](https://developer.apple.com/design/human-interface-guidelines/accessibility#Buttons-and-controls)。
   视觉尺寸可以不变，用 `.contentShape` + 外扩 `frame(minHeight: 44)` 扩大命中区。
9. **固定字号不随动态字体缩放**：17 处 `.font(.system(size:))`，包括封面上的 18+/下载/新章节角标（9 pt）、
   详情胶囊箭头（8 pt）。角标可以保持紧凑，但应使用 `@ScaledMetric` 或 `.caption2` 搭配
   `dynamicTypeSize(...DynamicTypeSize.accessibility1)` 限幅，而不是完全固定。
10. **用 `onTapGesture` 代替按钮**：详情封面（`SourceDetailsView.swift:657`，有标签但没有按钮特征）、
    详情标题、笔记行（`NotesView.swift:121`）、统计图表选日（`ReadingStatisticsView.swift:151`）。
    VoiceOver 读不出“按钮”、无法激活，也没有按压高亮。改为 `Button` 或补
    `.accessibilityAddTraits(.isButton)`；统计图表改用 Swift Charts 的 `chartXSelection(value:)`，自带
    VoiceOver 与拖动选择。
11. **批量选择的操作位置不一致**：章节目录的批量操作在底部工具栏（与 Photos/Files 一致），作品库与来源
    浏览把“收藏分类/移出”等放在顶部栏。建议统一为 `ToolbarItem(placement: .bottomBar)`，顶部只留
    “完成/全选”。
12. **详情 toast 生命周期**（`SourceDetailsView.swift:417-431`）。`DispatchQueue.main.asyncAfter` 不可取消，
    连续两个提示时第二个会被第一个的计时器提前收起；VoiceOver 也不会播报。建议用 `.task(id: toastMessage)`
    计时，并 `AccessibilityNotification.Announcement(toast).post()`。
13. **视频没有接入 Now Playing / 远程控制**。只有小说朗读注册了 `MPRemoteCommandCenter`。自绘播放器
    不会像 `AVPlayerViewController` 那样自动更新锁屏与控制中心、AirPods 按键；画中画切到后台后
    无法从锁屏暂停。建议设置 `MPNowPlayingInfoCenter` 与播放/暂停/跳转命令。
14. **列表模式用下标作为身份**（`ContentCollection.swift:286`，`ForEach(items.indices, id: \.self)`）。
    刷新、筛选或分页插入时，单元格会绑定到别的作品：封面闪烁、上下文菜单与 `.task` 对应错位。
    网格模式已用 `\.identity`，列表应保持一致（分隔线可用 `item.identity != items.last?.identity` 判断）。
15. **封面预览缩放后不能拖动**（`SourceDetailsView.swift:1360-1376`）。使用已弃用的
    `MagnificationGesture`，只改 `scaleEffect`，放大后无法查看边缘。建议用 `ScrollView` 的缩放
    （或 `MagnifyGesture` + 拖动偏移，与阅读器 `ReaderPageZoom` 复用）。
16. **全局搜索未使用系统搜索建议与范围**。最近搜索是页面里的玻璃按钮，内容类型过滤藏在菜单里。
    Music/App Store 的做法是 `.searchSuggestions` 显示最近搜索（键盘弹出时即可点），类型用
    `.searchScopes`。`targets` 也在 body 中每次过滤排序全部来源（用于计数文案），可缓存。
17. **记录页筛选是自绘胶囊**（`LibraryRecordsView.swift` 筛选行）。5 个两字选项可以放进系统
    `Picker(.segmented)`，VoiceOver 会读出“第 n 项，共 5 项”；若保留胶囊，至少把整组设为
    `.accessibilityElement(children: .contain)` 并标注“筛选”。时间轴仍在 body 中合并、排序、分组，
    单日内用 `VStack`（上一份文档已记录，未变化）。
18. **网格/轮播用 `NavigationLink(destination:)`**（`ContentCollection`、`GlobalSearchView` 轮播）。
    每个单元格求值时都会构造目标视图。Apple 推荐 `NavigationLink(value:)` +
    `navigationDestination(for:)`，同时让导航路径可以保存和恢复。记录页本轮已用 `navigationDestination(item:)`。

### 已符合规范、建议保留

- 系统 `TabView` + 独立搜索 Tab（`role: .search`）、`sidebarAdaptable` 适配 iPad、
  `tabViewBottomAccessory` 继续阅读。
- 设置页全部用系统 `Form`/`List` 与 `NavigationLink`；章节目录用 `List(selection:)` + `editMode`，
  有滑动操作与上下文菜单。
- 空状态统一用 `ContentUnavailableView`（47 处）；下拉刷新、`confirmationDialog` 破坏性确认齐全。
- 阅读器在 `accessibilityReduceMotion` 下关闭翻页/控件动画；保持常亮、边缘返回开关已存在。
- 作品库横向切换分类的手势只在横向位移明显大于纵向时开始，与纵向滚动同时识别，不会抢滚动。

## 第二轮已修复（同日）

| 项 | 修改 |
| --- | --- |
| 1 视频单击迟滞 | 去掉播放面上的双击识别，单击立即切换控件；填充/适应改为捏合（`MagnifyGesture`，放大为填充、缩小为适应），与系统播放器一致，“更多”菜单里的按钮保留。 |
| 2 章节目录重算 | `SourceDetailsModel.chaptersRevision` 随 `visibleChapters` 递增；`DetailsChaptersView` 用 `DirectoryCache` 按（revision、分支、排序方向、搜索词、选择模式）缓存章节、ID 数组/集合与分组行，下载进度只重算本作品的状态字典。离线标记（每行查文件系统）按章节缓存，本作品已完成下载数变化时失效。`ChapterRow.rows` 改为线性分组（原来每加一章复制一次成员数组）。 |
| 3 浏览分页 | 末尾 12 部作品任一出现即请求下一页；`loadMore` 原有的 `hasMore`/`isLoading` 守卫防止重复请求，出错时仍不自动重试。 |
| 5 笔记截图 | 新增 `NoteSnapshot`：后台 `Task.detached` 读取并用 `SourceImageFile.decode` 降采样到 1000 px，`NSCache` 按路径缓存（文件名是 UUID，不会被覆盖）。 |

## 第三轮已修复（同日）

| 项 | 修改 |
| --- | --- |
| 4 全局搜索跳动 | `GlobalSearchModel.arrived` 记录有结果的来源，按返回顺序追加（同一批内仍按置顶/手动顺序）；加载中的来源只计入顶部进度行，全部未返回时显示一个占位进度；失败的来源排在最后。已显示的结果不再被收起或挤动。取舍：结果顺序不再严格按置顶顺序。 |
| 6 Home 指示条 | 漫画与小说阅读器加 `persistentSystemOverlays`，与状态栏一起随控件隐藏。 |
| 7 简介展开 | 去掉与 `textSelection` 冲突的点击展开；用隐藏的完整文本测量高度，三行放不下时才显示“展开”，不再按字数猜。 |
| 8 点击区域 | 详情来源/追踪胶囊的命中区上下各扩 8 pt（负 padding 抵消，外观与间距不变）；记录更新下载按钮 28→44 pt，图标位置不变。 |
| 9 动态字体 | 封面收藏/下载/已读角标与 18+ 标记改用 `@ScaledMetric`（分别限制在 28 pt / 14 pt 以内）；详情胶囊箭头、来源排序箭头、来源语言标签改用文本样式。 |
| 10 可访问性 | 详情封面加按钮特征（标题保持静态文本，VoiceOver 双击即触发其点击）；笔记行改为 `Button`；阅读统计每日图表改为 VoiceOver 可调节元素（上下轻扫逐日选择并读出时长），触摸点选不变。 |
| 12 详情 toast | 改用 `.task(id:)` 计时，新消息重新计时，并发布 VoiceOver 播报。 |

另修正 `VideoSourceTests` 中已过时的 `browse.filter` 标识符（应用里是 `browse.filterChip`）。

## 第四轮已修复（同日）

| 项 | 修改 |
| --- | --- |
| 11 批量操作位置 | 作品库：换源/移出分类/取消收藏移到底部工具栏，选择期间隐藏 Tab 栏（与 Photos 一致），顶部只留退出与全选。来源浏览：“收藏到分类（N）”作为底部主按钮，同样隐藏 Tab 栏。两处都用 `batchSelectionChrome(_:)`：隐藏 Tab 栏，并通过 `ReaderPresence` 计数收起“继续阅读”附件条（否则附件条留在原位，正好盖住底部操作）。 |
| 13 视频 Now Playing | `VideoPlayerModel` 注册播放/暂停/切换、±10 s、拖动进度、上/下一集远程命令，并在播放状态变化与跳转时发布标题、作品名、时长、进度、倍速与封面（内存缓存）；`stop()`（关闭/换集）移除命令目标并清空信息。 |
| 14 列表身份 | 列表模式 `ForEach` 改用作品 `identity`，与网格一致；分隔线按“不是最后一部”判断。 |
| 15 封面预览 | `MagnifyGesture` 缩放（1–5 倍），放大后可拖动且限制在图片溢出范围内；1 倍时不拦截表单下拉关闭，放大时禁止下拉关闭；双击切换 2.5 倍并复位。 |
| 16 搜索建议 | 全局搜索用 `.searchSuggestions` 列出以已输入文字开头的最近搜索（最多 5 条），点一下直接搜索；页面内的最近搜索保留。类型范围（`searchScopes`）未改，仍在筛选菜单中。 |
| 17 记录筛选 | 保留胶囊（测试与布局依赖逐项标识），整组加“记录类型”无障碍分组，选中项已有 `isSelected`。 |

## 第五轮（同日）：第 18 项已尝试并撤回

改为 `NavigationLink(value:)` + 各栈根部 `navigationDestination(for:)` 后，UI 测试发现两处点击无反应：
从“浏览”列表里用 `NavigationLink(destination:)` 推入的推荐页，以及从详情页经
`navigationDestination(item:)` 推入的来源浏览页。即使在这两个页面自身再注册一次，详情 → 来源浏览 → 作品
仍然不推入。本应用大量混用视图式链接与 `navigationDestination(item:/isPresented:)`，在这样推入的页面里按值
链接不可靠；要可靠迁移，需要把整个应用的推入改为统一的 `NavigationPath`，属于大重构。该项本身收益很小
（`SourceDetailsView` 的初始化只保存参数，`StateObject` 惰性创建），因此撤回，保留闭包式链接。

保留的副产物：
- `ProductFlowTests/testWorkLinksOpenDetailsFromLibraryAndGlobalSearch` 覆盖作品库 → 详情 → 来源胶囊
  （新增标识 `details.source`）→ 来源浏览 → 作品，以及全局搜索结果打开详情；以后再做导航迁移可直接用它回归。
- 详情页爱心改为 `Menu(primaryAction:)`：头部整体是一个列表行，行内多个 `contextMenu` 只有最后一个（标签的
  “加入标签黑名单”）生效，原来长按爱心从未弹出“收藏分类/取消收藏”。现在点按行为不变，长按弹出这两项。
- 过时测试修正：已收藏时点爱心即取消收藏（10-18 起），测试改为长按爱心 → “收藏分类”。

## 建议处理顺序

审计条目均已处理（第 18 项撤回，原因见上）。每项修改后按上一份文档的“回归与真机验收”步骤，用 SwiftUI Instruments
（View Body Updates、Hangs、Animation Hitches）在真机上做前后对比。
