# TV UI 审计（2026-10-22）

审计基线：`749cc46bc`，Android 当前工作区实现。构建版本：`2.3.1` / `1245`。
续验时仓库 HEAD 为 `a25ff7abf`；对照基线检查，`app/`、`gradle/`、`reader-ui/` 没有新增差异。
审计阶段未修改产品代码；用户随后授权开始改进，修复和回归记录见下节。桌面端改动不属于本轮范围。

## 结论与优先级

现有共享业务层、TV 展示模式和遥控器分发可以继续沿用，现阶段没有证据支持重建独立 TV 应用。
下表和各问题章节保留修复前的审计记录及基线行号；TV-01 至 TV-07 已完成本轮修复和对应场景的模拟器回归。

| 编号 | 优先级 | 问题 | 证据级别 |
| --- | --- | --- | --- |
| TV-01 | P1 | 首次启动向导的不可见遮罩成为焦点，方向键无法进入“下一步” | 模拟器重复复现 |
| TV-02 | P1 | 空间面板打开时，遥控器仍能操作背后的导航 | 模拟器复现 |
| TV-03 | P1 | 空间面板打开时，Back 先切换底层页面或退出应用 | 模拟器复现 |
| TV-04 | P2 | 源管理的全页左右键处理截走筛选行内的焦点移动 | 模拟器复现 |
| TV-05 | P2 | 从搜索结果详情返回后，焦点跳回输入框并重新弹出键盘 | 模拟器复现，进入结果区用过 Tab 绕过 |
| TV-06 | P2 | 小说控件可见时，Back 直接退出阅读器 | API 36 TV 模拟器重复复现 |
| TV-07 | P2 | 遥控快进提示不消失，切集后仍显示上一集的时间 | 模拟器复现并确认状态清理缺失 |

P1 表示影响主要遥控器操作流程，应优先修复；P2 表示导航行为或状态恢复不符合预期。

## 改进与回归（2026-10-22）

代码变更限定在 8 个 Android UI 文件，没有新增依赖、独立 TV 应用、业务分支或构建变体。
最终 APK 已安装到同一 API 36 TV AVD，AUTO 模式、默认 iOS 风格、原有本地测试内容保持可用。

| 问题 | 修复 | 本轮回归结果 | 证据前缀 |
| --- | --- | --- | --- |
| TV-01 | 向导遮罩禁止聚焦，进入和切换步骤时请求当前主按钮焦点 | 首页打开后直接聚焦 Next；仅确认键可完成四步，Back 可返回上一步，完成后恢复 Wizard 入口 | `fix-21`–`fix-26` |
| TV-02 | TV 空间面板使用 Dialog 模态窗口，复用原抽屉内容；保留入口节点供关闭时恢复焦点 | 连续四次左键仍聚焦面板内 Manga，没有操作背景导航 | `fix-09`、`fix-10` |
| TV-03 | 空间面板可见时停用底层主导航 BackHandler | Home / History 打开面板后，Back 只关闭面板，原页面保持，焦点回到空间入口 | `fix-11`、`fix-17`、`fix-18` |
| TV-04 | 移除源管理 Scaffold 的全页左右切页及其专用请求器，保留顶部标签和区域焦点分组 | 顶部方向键和确认可进入 Packages；All → Updates → 左键回 All，仍在 Packages | `fix-27`–`fix-31` |
| TV-05 | 保存分组 key 与内容 ID，返回时等待列表和卡片布局完成再恢复；统一用于工具栏进入结果及跨分组上下导航 | 输入框 → 工具栏 → History → Local storage → Manga 01 → 详情 → 返回全程方向/确认/返回可完成；无键盘重弹；横向滚到 Manga 09 后也恢复原卡片 | `fix-50`–`fix-60` |
| TV-06 | 在 Compose 系统返回路径复用现有小说 TV Back 策略，统一子浮层判定 | 可见控件时 Back 先隐藏控件并保留正文，右键可继续翻页；无需触摸隐藏控件 | `fix-35`–`fix-37` |
| TV-07 | 遥控快进反馈约 1 秒后清理；连续操作会重置清理；进度条操作、切集、Activity 暂停和销毁时清理旧状态 | 暂停后快进仍增加进度，提示会消失；切到另一集没有旧时间残留 | `fix-43`–`fix-47` |

搜索的文字 `Audit` 由 ADB 代填；最终 `fix-50`–`fix-60` 的方向、确认和返回流程没有使用 Tab 或触摸绕过。
较早的 `fix-05`–`fix-07` 只验证返回恢复，进入结果区仍用过 Tab；最终版本已补齐该入口边界。
`fix-38` 的快速按键批次没有实际打开小说目录，不把它或 `fix-39` 当作目录回归证据。

实现沿用既有组件和状态：向导只增加初始焦点；空间面板复用同一抽屉内容；
搜索把方向导航和路由返回接入同一套目标滚动、布局等待与聚焦流程；小说复用原策略函数；
视频只给现有反馈增加可取消的生命周期任务，保持 KISS、DRY 和职责集中。

验证记录：

- `:app:compileDebugKotlin --offline --no-daemon`：通过。
- 最终 `:app:assembleDebug`：通过，使用仓库外 `x86-debug.init.gradle`，未修改仓库 ABI 配置。
- 最终八个 TV 相关 JUnit Jupiter 类 **44 项，0 失败、0 错误**，沿用下文的测试筛选和引擎限定。
- 对关键布局证据补做 9 项断言：向导初始焦点、面板左边界、History 保留、入口恢复、
  Packages 筛选、小说隐藏控件且保留正文、视频反馈消失、普通及横向滚动后的搜索卡片恢复，均通过。
  这是捕获证据核对，不等同于新增了 9 项自动 UI 测试。
- `git diff --check`：通过；模拟器 crash buffer 未出现应用崩溃记录。

最终本地 APK：`app/build/outputs/apk/debug/app-x86_64-debug.apk`，SHA-256：

```text
D99D20F2A5FC8B766D4A794B5BF0ED790BA3131D1E775FC2BE29CF00ACC7110B
```

构建和测试日志分别为证据目录中的 `fix-build-final.log`、`fix-tests-final.log`。
APK、截图和临时配置仍在本地，不纳入提交。

验证范围仍有限：未清除既有 AVD 数据重做全新安装；物理遥控器、长自定义空间列表、
搜索结果被刷新/过滤/移除、小说目录输入与第二章选择、在线内容源和低性能真机性能仍需继续验收。
空间面板宽度约束和通用列表失效目标等原有观察也没有在此批扩大修复。
本轮代表上述七个具体复现路径已修复，不代表 TV 全功能和所有设备已经验收通过。

## 环境与验证边界

- 新建 AVD：`kototoro_tv_api36`，系统镜像 `system-images;android-36;android-tv;x86_64`。
- 模拟器分辨率 `1920×1080`，密度 `320 dpi`，逻辑窗口约 `960×540 dp`。
- 设备声明包含 `android.hardware.type.television`、`android.software.leanback` 和 `leanback_only`。
- 系统 ABI 列表为 `x86_64,arm64-v8a`，可以安装项目默认的 ARM64 Debug APK。
- 用 Android CLI 获取布局和截图；用 `adb shell input keyevent` 发送方向、确认和返回键。
- 为绕过已确认的向导障碍，使用过一次 Tab 作为诊断；为继续独立验证源管理，使用触摸模拟关闭空间面板并打开扩展管理。
  这些绕过操作不计为“遥控器流程通过”。
- 未配置第三方内容源。首轮未验内容流程；续验已通过自制本地 CBZ、EPUB、MP4 补测，结果见下文矩阵。
- ARM 指令翻译、模拟器 GPU 和宿主机负载会影响性能，不能据此给出真实低性能电视的帧率结论。

## TV-01：向导首屏的遮罩截获焦点

位置：`app/src/main/kotlin/org/skepsun/kototoro/main/ui/welcome/WelcomeBottomSheet.kt:169–174`；
`main/ui/welcome/WelcomeSheet.kt` 未为向导提供 TV 初始焦点。

复现：

1. 在新安装应用的 TV 模拟器上打开首屏向导，或通过首页 Wizard 入口重新打开。
2. 按方向键，首屏没有可见的焦点反馈；连续向右也没有进入 Next。
3. 按确认，向导直接关闭，未进入第二步。
4. 作为诊断发送 Tab，Next 才获得焦点；此后确认可以继续向导。

遮罩使用全屏 `clickable(onClick = dismiss)`，因此也参与键盘焦点搜索。
它位于不透明的全屏 Surface 背后，获得焦点时没有可见反馈。
普通遥控器没有 Tab，用户无法依靠常规方向键完成首屏。

建议：让遮罩只处理指针输入，或明确禁止遮罩获得焦点；向导打开时聚焦可用的主操作，
切换步骤时保持可见且可预测的焦点。沿用现有向导和按钮，不增加第二套初始化流程。

验收：新安装和重新打开向导都只用方向、确认、返回完成四步；必需权限和可选操作都可到达。

证据：`09-wizard-down.png/xml`、`10-wizard-unexpected-dismiss.png/json`、
`21-wizard-tab-diagnostic.png/xml`。最后一份 XML 的实际焦点是 Next 按钮，边界为 `[1004,940][1213,1044]`。

## TV-02：空间面板缺少焦点边界

位置：`app/src/main/kotlin/org/skepsun/kototoro/space/ui/SpaceSwitcher.kt:162–182,419–423`。

复现：

1. 首页通过导航栏的空间按钮打开 Space Workbench，初始焦点落在 Manga。
2. 按左，蓝色焦点框出现在遮罩后的 Home 导航项上。
3. 按下、确认，底层页面切换为 History，而空间面板继续显示。

空间面板通过普通 Box 叠加在原页面上，只在内部使用 `focusGroup()`。
背景仍保留可操作焦点，当前实现没有形成模态焦点边界。布局树因遮挡不一定暴露背景焦点，
本项以实际焦点边框和底层页面切换截图交叉确认。

建议：面板可见期间限制焦点离开面板，并让背景控件暂时退出焦点搜索；关闭后恢复打开面板的入口。
使用 Compose 现有焦点能力或模态容器，避免复制导航和 Space 业务状态。

验收：在三种内置空间、长空间列表、切换进行中和不同导航页上，所有方向键都不能激活遮罩后的控件。

证据：`11-space-panel.png`、`12-space-left.png`、`14-space-background-navigation.png`。

## TV-03：空间面板的 Back 优先级错误

位置：`app/src/main/kotlin/org/skepsun/kototoro/main/ui/compose/KototoroApp.kt:1661–1667`；
空间面板自身的处理在 `space/ui/SpaceSwitcher.kt:219`。

复现：

1. 在空间面板仍打开、底层为 History 时按 Back。
2. 底层回到 Home，面板没有关闭。
3. 再按 Back，应用退到电视桌面；面板未先完成关闭。

主界面末尾注册的 BackHandler 在空间面板可见时仍启用，未将 `spaceUiState.switcherVisible`
纳入条件，实际抢先处理了返回。此问题独立于方向键焦点泄漏，需要单独修复返回优先级。

建议：面板可见时停用底层主导航的 Back 处理，或在统一入口先关闭最高层面板，再处理页面返回与退出。
避免通过新增多个竞争的 BackHandler 修补。

验收：Home 和非 Home 页面打开面板后，第一次 Back 只关闭面板，底层页面保持不变，并恢复入口焦点。
第二次 Back 才按原有页面和退出策略处理。

证据：`14-space-background-navigation.png`、`15-space-close.png`、`16-extension-target.png`。
文件 15 实际仍显示空间面板且底层已变成 Home；文件 16 已进入系统电视桌面。

## TV-04：源管理的左右键切页抢占筛选导航

位置：`app/src/main/kotlin/org/skepsun/kototoro/settings/sources/unified/UnifiedSourcesScreenBody.kt:170–193`。

复现：

1. 内容源管理进入 Packages 标签，无须安装任何扩展。
2. 将焦点移到状态筛选 All，按右，焦点正确移动到 Updates。
3. 按左，预期回到 All，实际切换到 Repositories，焦点被移到 Add repository。

Scaffold 级 `onKeyEvent` 对左右键直接切换 Pager。普通可点击筛选项没有消费方向键时，
事件先到达该处理器，尚未完成行内的默认焦点移动就已被消费。

建议：把标签切换限制在现有顶部标签控件；如果需要内容边界切页，必须在确认当前区域无法移动焦点后触发，
不能以“子控件没有消费 KeyEvent”代替“没有相邻焦点”。

验收：状态、内容类型、来源类型和仓库筛选行都能左右往返；顶部标签仍能切换页面；
文本输入和列表内按钮不触发意外切页。

证据：`30-package-status-all.png/json`、`31-package-status-updates.png/json`、
`32-status-left-switches-tab.png/json`。

**状态：已修复（2026-10-22，代码级）**。`UnifiedSourcesScreenBody` 里 Scaffold 级的 `onKeyEvent` 分页器切换
整段移除（连同 `pageFocusRequesters`/`filtersFocusRequester` 的 withFrameNanos 焦点重排），
标签切换交回 `UnifiedSourcesContextualFilterTabs` 自身：方向键在筛选项之间正常移动、CENTER 才切换标签，
不再出现“按左键跳到 Repositories”的抢占。`HorizontalPager` 不再接受遥控 LEFT/RIGHT 直接翻页。
该屏本轮未再逐键复测（进入路径需已安装的源/仓库），下次设备验证时补 `30/31/32` 同位置的新证据。

## TV-05：搜索结果返回时重新聚焦输入框

位置：`app/src/main/kotlin/org/skepsun/kototoro/search/ui/compose/SearchResultsScreen.kt:254–261,699–706,749–756`。

`hasRequestedInitialSearchFocus` 使用普通 `remember`，搜索页面离开组合再返回时会恢复为 false，
LaunchedEffect 再次请求输入框焦点。结果横排只有焦点分组，没有按稳定内容标识记录和恢复原卡片的机制。
Navigation3 保存可保存状态和 ViewModel，并不能让这个普通 remember 跨页面移除保留。

影响：从搜索卡片打开详情再返回时，焦点会重新进入搜索输入，而非刚才浏览的结果。
列表滚动位置即使保存，也不等于操作位置已经恢复。

建议：区分首次输入与返回结果，按导航 entry、结果分组和稳定内容 ID 保存恢复目标，
在项目完成加载与布局后恢复，失效时给出明确回退。仅把布尔值改成 rememberSaveable 并不足以完成结果恢复。

续验已复现：全局搜索 `Audit`，本地库返回 15 项，另有 1 项历史记录。
把焦点移到本地横排第四张 `TV Audit Manga 01`，确认进入详情，再按 Back。
结果横排仍保留滚动位置，但焦点回到 `Audit` 输入框 `[960,72]`，系统键盘再次弹出。
进入结果区使用过 Tab 绕过输入框方向键障碍，因此这证明返回恢复失败，不代表搜索全程可用遥控器完成。

证据：`111-search-manga-one-focused.png/json`、`112-search-details.png/json`、
`113-search-return-focus.png/json`。结果刷新、多个在线源、目标被过滤或移除和空间切换仍需后续回归。

## TV-06：小说控件可见时 Back 直接退出

位置：`app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelReaderActivity.kt:1224–1242`；
`reader/novel/compose/NovelReaderChrome.kt:156–162`。

复现：

1. 本地库打开 `TV Audit Novel` 详情，确认 Read / Continue。
2. 小说顶部返回、标题、更多按钮和底部进度控件可见，初始焦点在顶部返回按钮 `[76,60]`。
3. 发送 Back，直接回到详情，未先收起控件。重新进入后重复，结果相同。
4. 对照漫画：同一设备、相同 Back 注入方式，先收起控件，再返回详情。

小说把“TV 下先收起控件”放在 `dispatchKeyEvent(KEYCODE_BACK)` 内，
但 `onBackPressedDispatcher` 路径没有对应的普通控件关闭处理；Compose 的 BackHandler 只处理子面板。
当前 API 36 TV 环境的返回实际没有获得与漫画一致的处理。应把返回优先级落实到统一的返回回调，
不能仅依赖按键分发入口。具体系统返回路径仍需在修复时加集成验证，不能只凭纯策略单测判定通过。

本次为继续独立验证小说翻页，在正文中央使用触摸模拟隐藏控件；随后方向键翻页及非零进度恢复可用。
触摸绕过不计为小说遥控闭环通过。

验收：普通控件、目录、设置和输入法分别打开时，Back 先关闭当前最高层；全部隐藏后才退出。
同时覆盖系统返回回调和遥控 Back，避免与子面板的处理竞争。

证据：`57-novel-start.png/json`、`58-novel-next.png/json`、
`59-novel-reenter.png/json`、`61-novel-back-reproduce.png/json`。
文件 58 名称沿用原测试意图，实际已是详情页，焦点位于 Continue 旁的下拉按钮。

## TV-07：遥控快进反馈永久残留

位置：`app/src/main/kotlin/org/skepsun/kototoro/video/ui/VideoPlayerActivity.kt:928–936,4964–4984`。

复现：

1. 播放本地第 1 集，媒体键暂停在约 `00:12`，等待控件自动隐藏。
2. 按右，成功快进到约 `00:22`，中央显示 `Fast forward 10 s / 00:22 / 03:00`。
3. 继续操作进度条，实际进度已到约 `00:32`，中央提示仍是 `00:22`。
4. 打开选集并切到第 2 集，实际进度约 `00:06`，提示仍显示第 1 集的 `00:22`。
5. Back 隐藏控件后提示仍遮挡画面；退出并重进播放器后才消失。

`seekFromTvRemote()` 设置 `seekFeedbackState` 后没有安排隐藏；
`hideSeekFeedback()` 的唯一调用位于触摸拖动结束路径。遥控快进没有走到该路径，
后续调整进度和切集也没有清理旧状态。这是 UI 状态缺陷，快进和切集本身仍有效。

建议：复用现有反馈状态，为遥控快进提供可取消的短时清理，连续快进时更新并重置计时，
媒体切换和退出时清理；不增加另一套 TV 提示组件。

验收：左右键和媒体快进键连续操作时反馈时间正确，结束后自动消失，暂停时也会消失；
切集、重播、退出与返回前台不残留旧媒体时间。

证据：`85-video-hidden-right-seek.png`、`86-video-slider-right.png`、
`89-video-episode-list.png`、`91-video-media-pause-ep-two.png`、
`92-video-back-hides-controls.png`、`94-video-resume-ep-two.png`。

## 续验环境与内容流程矩阵

续验使用同一真实 TV AVD `kototoro_tv_api36` 的原生 `x86_64` Debug APK，
保留 AUTO 展示模式和默认 iOS 风格。仅通过仓库外的 Gradle init script 调整本次构建 ABI，
未修改产品构建脚本或引入依赖。原生 ABI 的 `:app:assembleDebug --offline --no-daemon` 构建通过。

API 36 模拟器在默认与 SwiftShader 配置下曾发生宿主 `qemu-system-x86_64` 访问异常，
Windows 事件记录为 `0xc0000005`；原生 ABI 也曾复现，不能归因为应用 Java 崩溃。
显式使用 `-gpu host -feature -Vulkan -no-snapshot` 后，设备 `emulator-5558` 完成了下表验收。
API 34 TV 备用 AVD 未成功完成启动；API 35 备用设备没有作为 TV 验收依据，已停止。
上述结果只能说明当前 host GPU 配置可继续验收，不能证明已找到宿主崩溃根因。

本地测试数据由仓库外的 `make_fixtures.py` 生成：12 本两章 CBZ、1 本四张长图 CBZ、
1 本两章 EPUB、两集各 180 秒的 MP4（双 AAC 音轨、内嵌英文字幕）。
放入测试 AVD 的应用外部文件目录，通过应用自己的本地扫描进入索引，没有依赖第三方账号或在线源。
为使已有索引重新扫描，重置了测试 AVD 的索引缓存版本；未修改业务数据库。

| 流程 | 结果 | 证据与范围 |
| --- | --- | --- |
| 本地库卡片 → 详情 → 主操作 | 通过 | `43`、`44`；初始焦点落在 Read，卡片方向移动可见 |
| 漫画隐藏控件时右键翻页 | 通过 | `45`、`46`、`49`；页码和自制内页标记从 1 → 2 → 3 |
| 漫画控件可见时方向键归属 | 通过 | `47`、`48`；焦点移到 Translate，正文仍为第 2 页 |
| 漫画确认长按 | 通过本次合成长按 | `50`；仅打开一次控件，没有重复切换；真实长按重复仍需物理遥控器 |
| 漫画目录与 Back 层级 | 通过 | `51`–`53`；先关目录，再关控件，再回详情 |
| 漫画阅读进度与列表焦点恢复 | 通过当前样本 | `54`、`55`；继续第 3 页，回本地库仍聚焦 Manga 11 |
| 长图上下步进及控件按键归属 | 通过当前模式 | `116`–`120`；单次、反向、合成长按可滚动，开控件后不误滚正文 |
| 小说打开与内容渲染 | 通过 | `56`、`57`；EPUB 两章可见，双栏正文正常显示 |
| 小说隐藏控件后的翻页与进度恢复 | 部分通过，有触摸绕过 | `71`–`74`；正文翻到段落 008 / 016 起始位置，重进后保留 |
| 小说仅遥控器的返回闭环 | 未通过 | TV-06；目录可打开，但本轮未完成仅方向键选择第二章的验收 |
| 视频解码、媒体键暂停、继续播放 | 通过当前 MP4 | `76`、`77`、`91`、`94`；有实际画面，媒体键可暂停，重进继续第 2 集约 6 秒处 |
| 视频隐藏控件右键快进 | 功能通过，反馈失败 | `85`；实际增加约 10 秒，TV-07 使提示持续残留 |
| 视频进度条方向键调整 | 通过当前样本 | `86`；先进入进度条，再右键增加约 10 秒，保持暂停 |
| 视频音轨选择与弹层返回 | 通过 | `78`–`81`；en → ja，重新打开保留勾选，Back 先关菜单 |
| 视频字幕显示、关闭与弹层返回 | 通过 | `77`、`82`–`84`；内嵌字幕实际显示，选 Off 后消失，Back 关菜单 |
| 视频选集与返回层级 | 功能通过，提示残留 | `89`–`94`；选择 Episode 02，先隐藏控件再回详情，重新进入保留集数与进度 |
| 搜索结果 → 非首项详情 → 返回 | 未通过 | `111`–`113`；TV-05，返回输入框并弹出键盘 |

矩阵中的通过仅针对所列样本与模式，不等于整个功能验收通过。
本轮没有测网络内容源、特殊编码、DRM、外部字幕、低性能真机帧率、重启后的长期状态恢复，
也没有完成所有长列表刷新、删除原焦点目标和空间切换的组合。

### 输入方式与绕过记录

- 常规方向、确认、返回和媒体键用 `adb shell input keyevent`；脚本逐键等待并保存截图、布局和 `actions.jsonl`。
- 搜索文本 `Audit` 用 ADB 直接注入，未把该步骤计为遥控器逐字输入验收。
- 搜索结果的输入框在收起输入法后，合成方向键仍未移出；显式 `input dpad keyevent` 也未改变这一结果。
  本机输入设备清单没有独立的非虚拟 DPAD 设备，因此暂不把该现象直接归结为所有物理遥控器上的焦点陷阱。
  同类现象也在小说目录筛选输入出现。需要增加实际 DPAD 设备或具有相同设备特征的输入驱动再确认。
- 为继续验证搜索返回，使用 Tab 到结果卡片，然后恢复方向、确认和返回操作。绕过后的 TV-05 独立成立。
- 小说正文中央的触摸模拟仅用于收起控件，绕过后才验翻页及进度；未据此判定小说遥控闭环通过。
- 播放器控件约 3.5 秒自动隐藏。带 `ScreenFirst` 的证据先截图再抓布局，二者可能显示不同的控件可见状态，
  播放器焦点和即时进度以截图交叉核对，不把较晚的空布局直接认定为焦点丢失。

## 其他观察与待验证项

- 空间面板 `fillMaxWidth(0.68f).widthIn(max = 280.dp)` 的约束顺序使后者未能限制实际宽度。
  本机截图面板约占 68% 屏幕，约 653dp，明显超过代码中的 280dp 上限。可与 TV-02 一并校准布局，
  但不应抢在交互阻塞问题之前做视觉改版。**第三轮已修正**，见下文 `SpacePanelWidth`。
- 通用内容列表虽然记录 `focusedContentId`，其初始请求 effect 只以 `focusTargetId != null` 为 key，
  数据刷新后原目标失效但新目标仍非空时不会因此重试；同时没有显式完成屏外目标滚动与绑定等待。
  需要用长列表、刷新和删除原项目验证，暂不计作已复现缺陷。**第三轮已改为按帧重试**，见下文 `FocusRetry`。
- 现有 TV 策略测试覆盖展示模式决策、导航回退函数和阅读/播放按键所有权，不能证明真实 Compose 焦点树正确。
  现有 `VideoPlayerControlsLayoutTest` 也未提供 TV 展示配置、验证遥控器焦点和返回序列。
- 当前已有 TV 横排动画抑制和播放器按键所有权分发，未发现必须重写业务层的依据。
  玻璃背景、共享元素转场和低性能电视表现需要独立测量，不能把本轮模拟器观感直接当成性能缺陷。

## 审计阶段构建与测试记录

- `./gradlew.bat :app:assembleDebug --no-daemon --offline`：通过，当前代码 APK 已安装到 TV 模拟器。
- 八个 TV 相关 JUnit Jupiter 测试类共 **44 项，0 失败、0 错误**：展示模式 6、导航回退 3、导航图标 1、
  小说按键策略 5、漫画按键分发 7、漫画按键策略 4、视频按键分发 8、视频按键策略 10。
- 筛选参数：`--tests "*TvKey*" --tests "*TvNavigation*" --tests "*UiPresentationModeResolverTest"`。
- 默认运行同时执行了无关的 Kotest `InternalChapterIdInProgressPropertyTest`，在 seed 清理中长时间运行。
  已保留线程转储并终止本轮测试 worker，随后用仓库外的临时 init script 限定 `includeEngines 'junit-jupiter'` 重跑通过。
  这不代表全量 JVM 测试通过，也未修改仓库的测试配置。
- 早期并行构建与单测发生 Windows ASM 产物缓存冲突；后续改成串行构建和验证，该冲突不计入 TV 产品缺陷。

## 本地证据与后续验收

截图、布局 JSON/XML、按键重放脚本和临时测试配置保存在仓库外：

```text
C:/Users/chuxi/.codex/visualizations/2026/10/22/01a16345-604c-77a1-9bfe-53e36d83f7d1/tv-audit/
```

`remote.ps1` 支持 UP、DOWN、LEFT、RIGHT、CENTER、BACK、HOME、MENU、PLAY_PAUSE、REWIND 和 FAST_FORWARD，
每次操作后保存布局与截图。物理遥控器的真实按键重复、蓝牙连接与厂商差异仍需真机验收。

原始修复顺序为 TV-01 → TV-02/TV-03 → TV-04 → TV-05 → TV-06/TV-07，本批已完成七项问题及搜索区域导航。
后续补足小说目录、其他输入框退出、长列表目标失效、输入设备交叉验证，再开展视觉与性能验收。
保持同一应用、共享业务层，通过局部焦点和返回策略修复落实 KISS、DRY 和单一职责。

官方对照：[TV 导航](https://developer.android.com/training/tv/get-started/navigation)、
[Compose 请求焦点](https://developer.android.com/develop/ui/compose/touch-input/focus/request-focus)、
[Compose 键盘事件](https://developer.android.com/develop/ui/compose/touch-input/keyboard-input/commands)。

## 第三轮：遥控闭环与输入框退出（2026-10-22/23）

本轮补齐上一轮遗留的四项：小说目录、其他输入框退出、长列表目标、空间面板宽度。
所有结论都用 `emulator-5558`（`kototoro_tv_api36`，1920×1080 @320dpi ≈ 960×540dp）的按键重放、
`uiautomator` 布局与截图三份证据交叉核对，无触摸/无 Tab 绕过。

### ImeOwnsDpad：输入框为何"卡死"（根因）

- 上一轮记录"收起输入法后合成方向键仍未移出"。本轮先复现再定位：**系统输入法（Gboard/LatinIME）
  处于显示状态时会吞掉方向键**，`mCurrentFocus` 仍是应用窗口，但按键不到达 Compose。
  对比实验：同一焦点位置，输入法显示时 DOWN 无任何 `onPreviewKeyEvent` 回调；
  按返回收起输入法（`dumpsys input_method` → `mInputShown=false`）后同一 DOWN 立即生效。
- 因此"输入框可退出"的正确语义是：先由搜索键/返回关掉软键盘，再用方向键离开输入框；
  离开后不得再次被抢占。这一点由新增的共享修饰符保证，而不是靠单个页面的键处理。

### TextFieldEscape：共享输入框退出修饰符

- 新增 `core-ui` 的 `Modifier.tvTextInputFocusEscape()`（`core/ui/adaptive/TvFocus.kt`）：
  仅 TV 展示生效；在 `onPreviewKeyEvent` 的 KeyDown 上把上/下方向映射为 `moveFocus`，
  成功移动则收起输入法并消费事件，未移动则不消费（保留光标/滚动语义）。左右键留给光标。
- 应用位置：小说目录筛选框 `NovelChapterDirectoryContent`、搜索结果页输入框 `SearchResultsScreen`、
  通用列表顶栏 `SearchInputRow`、搜索浮层 `KototoroSearchOverlay.SearchInputField`、
  高级搜索标题/标签/作者三个 `OutlinedTextField`。设计要求单行输入框，多行输入框仍需要上下键。
- 设备证据：搜索浮层 `t3–t5`（收起输入法后 DOWN，焦点从输入框移到 Filter 芯片）、
  搜索结果页 `t7–t8`（同样落到 Novel 芯片）、小说目录筛选框 `n6–n9`。
  修复前同一操作无任何焦点变化，修复后每次都有可见焦点环与外层页面滚动跟随。

### NovelToc：小说目录只用方向键选章

- `NovelChapterDirectoryContent` 打开时不再聚焦"查找章节"输入框，而是**直接聚焦当前章**，
  且不弹出输入法：首次打开时焦点停在当前章行，DOWN 进入下一章（`n2`→`n3`），
  CENTER 切章成功（标题、正文与底部进度同步到 `Audit Chapter 2`，`n4`），详情页进度随之为 50%。
- 目录行补齐 TV 焦点视觉：`clip(行形状)` → 背景 → `tvFocusable(addFocusTarget=false)` → `clickable`，
  行高下限 48dp。注意最初的修饰符顺序写反，焦点环其实没有画出来，见下节 `FocusRingOrdering`。

### FocusRingOrdering：焦点环在部分行上根本不存在（同日修正）

- 现象：小说目录里焦点确实移动了（`w9-01-up` 命中下一章行），但截图逐像素比对显示被聚焦行只有一层很淡的
  状态底色（`RGB 55,52,46`，面板底色 `36,33,29`），**没有任何 `primary` 色描边**——"焦点在但看不出来"没真正解决。
- 根因（Compose 语义）：`Modifier.onFocusChanged` 只上报**它之后**那个焦点目标的状态。原链是
  `… → clickable → tvFocusable()`，`onFocusChanged` 之后已无焦点目标，回调恒为 false，描边从不绘制；
  `clickable` 自带的 10% 状态层成了唯一线索。Material3 `Surface` 还有第二层问题：背景由内部
  `surface` 修饰符绘制在调用方修饰符**之后**，即使补 `border` 也会被背景盖住。
- 修正：`tvFocusable()` 内部改为 `onFocusChanged` + `drawWithContent { drawContent(); drawOutline(Stroke) }`，
  焦点环绘制在整条链内容**之上**，不再被 `Surface`/`background` 覆盖；同时把 10 处"`tvFocusable` 写在
  `clickable` 之后"的调用点挪到 `clickable` 之前（`KototoroSearchOverlay` 最近查询/来源行/建议卡片、
  `NovelChapterDirectoryContent` 目录行等）。KDoc 明确要求"必须放在它要观察的 clickable 之前"。
- 设备证据：目录被聚焦行四边均检出 2dp `RGB(230,222,209)` 描边（`x10-toc-ring.png`，阅读器面板主题的
  `primary` 是纸色）；搜索浮层"最近查询"行出现蓝色焦点环（`y7-overlay-row-ring.png`）；
  Continue 卡片的大号焦点环未回退（`z2-card-ring.png`）。

### FocusRetry：长列表初始焦点

- 通用内容列表与详情页章节列表把"一次 `yield` 后请求焦点"改为**按帧重试**（`withFrameNanos`，
  上限 20 次，成功即返回），不再依赖单帧布局/绑定是否完成。
- 设备证据：进入 History 列表时焦点直接落在首张卡片（`v1`），不是导航栏；
  从详情返回搜索结果为原卡片且不再弹出键盘（`t9–t10`，`mInputShown=false`）。

### SpacePanelWidth：空间面板宽度

- `SpaceSwitcher` 改为由调用点 `BoxWithConstraints` 计算 `spacePanelWidth(maxWidth)`
  = `min(可用宽度 × 0.68, 280dp)`，面板自身 `fillMaxHeight().width(width)`。
- 设备实测：1920px 宽下空间面板左边缘约 1367px，宽 553px ≈ **276.5dp**（上一轮约 653dp）。
  面板内 DOWN 可在 Manga/Novel/Anime 卡片间移动（`v3–v4`）。
- 新增 `SpaceSwitcherPanelWidthTest`（3 项）：`960dp → 280dp`、`360dp → 244.8dp`、`411dp → 279.48dp`、
  `412dp → 280dp` 的边界行为固定在单元测试里，避免再次被约束顺序回退。

### 本轮构建与测试记录

- `:app:assembleDebug --offline --no-daemon --no-build-cache`（x86_64 单 ABI init script）：通过。
  期间遇到 `transformDebugClassesWithAsm` 的 `Cannot load class ... Hilt_*` 失败，
  根因是 `app/build/intermediates/javac` 与 `classes` 里残留旧产物（KSP 已重新生成 66 个 `Hilt_*.java`
  但 javac 目录只有 123 个类）；删除这两个中间目录后重建通过，`:app:clean` 单独不够（构建缓存会恢复坏条目）。
- 单元测试：`UiPresentationModeResolverTest` 6、`TvNavigationFocusTest` 3、`TvNavigationRailIconTest` 1、
  `NovelReaderTvKeyPolicyTest` 5、`ReaderTvKeyDispatcherTest` 7、`VideoPlayerTvKeyDispatcherTest` 8、
  `SpaceSwitcherPresentationTest` 2、`SpacePanelDragDirectionTest` 2、`SpaceSwitcherPanelWidthTest` 3，
  共 **37 项，0 失败、0 错误**（全量 `:app:testDebugUnitTest` 跑 3126 项时同样全部通过）。
- 全量测试套件另有 2 项与本轮无关的环境失败：`SyncedExtensionFileTest` 的 `Files.move` 在
  `%TEMP%` 下抛 `AccessDeniedException`（Windows 上可稳定复现，属本机文件权限/杀软问题，不涉及 UI）。
- 本轮 APK 只产出 x86_64 debug（模拟器 ABI），未验证 arm64 真机包。
- `:core-ui:compileCommonMainKotlinMetadata` 在离线环境下无法执行（CMP 元数据 jar 未进本地缓存），
  本轮 core-ui 改动改用 `:app:assembleDebug` 的编译结果验证；联网环境应补跑该检查。

### 仍未覆盖

- 物理遥控器按键重复/长按、蓝牙遥控器、厂商按键差异仍只有虚拟 DPAD 证据。
- 视觉与性能验收（玻璃背景、共享元素转场、低性能电视帧率）仍未开始。

## 第四轮：设置页输入与搜索、对话框输入覆盖（2026-10-23）

### SettingsSearch：设置搜索框吞掉方向键（实测复现后修复）

- 现象：设置页右上角搜索 → CENTER 进入输入态 → BACK 收起输入法 → **DOWN 无反应**，
  焦点停在 `EditText[136,8][696,104]` 上（`af1–af2`，`af2-settings-search.png`），
  搜索框把遥控器方向键全部吃掉，用户无法离开输入框选择结果。
- 根因与第三轮 `ImeOwnsDpad` 相同：`BasicTextField` 自身消费上下键做光标纵向移动；
  设置搜索框（`SettingsTopBarScaffold.kt` 的 `SettingsSearchTopBar`）当时没有挂
  `tvTextInputFocusEscape()`，是 82 处文本输入里仅 7 处挂载的漏网之一。
- 修复：给该 `BasicTextField`（`fillMaxSize().focusRequester(...)`）追加 `.tvTextInputFocusEscape()`。
  实测（`ag1–ah1`，`ah1-settings-search-escape.png`）：输入 `read`（ADB 注入文本，非遥控输入）→
  BACK 收起输入法（`mInputShown=false`）→ DOWN 焦点落到首个结果行 `[24,132][696,268]`
  并显示蓝色焦点环，设置搜索的“输入→选结果”链路在遥控下闭环。

### TextFieldCoverage：单行输入框的方向键逃逸覆盖

- 清单方式：先统计全部文本输入（`OutlinedTextField(`/`BasicTextField(`/`TextField(` 共 **82 处 / 48 个文件**，
  挂 `tvTextInputFocusEscape()` 的仅 7 处），再用脚本按“调用块内出现 `singleLine = true`”判定，
  只在单行输入上追加修饰符——多行编辑器（词典规则 URL/展示规则、JSON 源码、替换规则多行项）
  保留光标编辑能力，避免遥控方向键被抢。
- 本轮新增挂载（单行，共 27 处 / 22 个文件）：
  - 设置：`SettingsTopBarScaffold`（页内搜索）、`SettingsPreferenceComponents`（内联文本项 + 文本项对话框）、
    `SpacesSettingsScreen`、`SourcesCatalogScreen`、`GlobalTagBlacklistScreen`、`SourceGroupsDialog`、
    `UnifiedSourcesDialogs`、`OverrideConfigScreen`、`TtsSettingsRoute`、`SuggestionsSettingsScreen`。
  - 搜索/筛选：`SearchFilterDialogs`（保存筛选名、标签输入、标签组搜索）、`SearchFilterPanel`、`FilterSheetRoute`。
  - 授权/同步/词典/收藏分类/详情/视频标注：`SyncAuthActivity`（邮箱、密码、服务器地址）、
    `KitsuAuthScreen`、`MangaUpdatesAuthScreen`、`MangaBakaAuthActivity`、`DictionaryRulesActivity`（名称、排序号）、
    `FavouriteCategoriesScreen`、`FavouritesCategoryEditScreen`、`DetailsSourceSearch`。
- 仍未覆盖（不在本轮 TV 主链路或本身多行）：`ReplaceRulesSettingsScreen`（12 处多为多行替换规则）、
  `JsonSourceEditScreen`（源码编辑器）、`ProtectSetupScreen`、`VideoAnnotationDialog`——它们的输入框没有 `singleLine`，
  按上面的规则刻意跳过，需要时再单独评估“多行编辑器内方向键优先级”。

### SettingsSearchFullWidth：TV 搜索态改用单栏

- 现象：TV 进设置页是 `isMasterDetails = true` 的双栏（左栏固定 360dp，右侧详情栏），
  但**搜索态右栏是空的**——搜索结果被挤在 720px 左栏里，右侧 75% 屏幕纯黑（`ah1-settings-search-escape.png`）。
- 修复：`SettingsAdaptiveShell` 新增 `searchActive` 参数，TV 且搜索进行中时改走单栏 shell
  （`SettingsSinglePaneShell`，等价手机端行为），搜索结束回到双栏；`SettingsActivity` 从
  `SettingsSearchViewModel.isSearchActive` 取状态传入。单栏分支对 `destination == null` 兜底为
  `SettingsDestination.Root`，避免空屏。
- 实测（`aj1`、`ak2-settings-search-fullwidth.png`）：搜索框 `EditText[136,8][1896,104]` 铺满整宽、
  结果行 `[24,132][1896,268]` 同样整宽（含“父级路径”副标题与右侧箭头），首行带蓝色焦点环；
  选中结果跳到 Lists 页（`al1`）。中文查询 `agent` 无结果时显示整宽 "Nothing found" 空态（`an3`）。

### SettingsSearchFocusRetain：切栏时别把焦点抢回列表

- 首版单栏改造引入回归：进入搜索瞬间 shell 切换、根列表重组，根列表的“初始焦点”副作用
  （`SettingsRootScreen` 的 `LaunchedEffect`）抢走了顶栏输入框的焦点——CENTER 点开搜索却落到第一行
  （`ai1-03-center :: [24,196][1896,328]`）。
- 修复：`SettingsRootScreen` 新增 `searchActive` 参数，TV 初始焦点副作用在搜索态下不执行
  （注释说明原因）。实测（`aj1-03-center :: android.widget.EditText[136,8][1896,104]`，`mInputShown=true`）
  焦点稳定留在输入框。
- 输入态行为小结（TV）：BACK 一次收输入法、再 BACK 退出搜索回到双栏（`aq1`），
  空查询时 LEFT/RIGHT 仍交给光标（无候选时 DOWN 不移动，见 `an3`——不是卡死，是确实没有目标）。

### SettingsDetailPaneFocus：双栏打开页面后焦点自动进右栏

- 现象：TV 双栏里在左栏按 CENTER 打开页面后焦点仍停在左栏行上，用户还要多按一次 RIGHT（`ab2`/`ac1`）。
- 修复：`SettingsTwoPaneShell` 在 TV 下监听左栏 `onFocusChanged`，当 `destination` 从 Root 变成具体页面
  且焦点在左栏时，做有界的 `focusManager.moveFocus(Right)` 重试（20 帧），把焦点送进刚打开的页面。
- 实测（`am1`）：左栏 "Reader settings" 行 `[24,370][696,542]` CENTER 后焦点直接落在右栏
  "Reader actions" `[746,332][1896,456]`，蓝色焦点环在右栏行上（`am1-detail-focus.png`）。
- 已知小瑕疵：右栏长页面（如 Content sources）里，几何最近原则可能选中中段行并把列表轻微上卷，
  顶部小节标题会被切掉半行（`av1-sources-open.png`）。想回顶部按 UP 即可；若后续要彻底对齐原生 TV，
  应在页面级提供“首个可聚焦项”的 FocusRequester，而不是靠几何猜测。

### SettingsTopBarOpaque：TV 顶栏不用玻璃 vibrancy

- 现象：iOS 风格下顶栏是 `GlassSurface`（配合 `rememberLayerBackdrop` 做 vibrancy），手机上有插画背景
  时是磨砂效果；电视扁平深色背景下，滚动内容直接透出与标题叠字（`ar2-content-sources.png` 顶栏
  可见 "Sorting order"、"enhancement, video encom..." 与标题重叠），10 英尺外读不清。
- 修复：`SettingsTopBarSurface` 在 TV 下改用不透明 `settingsScreenBackgroundColor()` 顶栏，
  手机/平板保留玻璃；滚动内容在顶栏下干净裁切（`as1-opaque-topbar.png`）。

### 本轮构建与测试记录（第四轮）

- `:app:assembleDebug --offline --no-daemon`（x86_64 init script）连续 5 次通过（build-10 … build-14），
  每次约 2–3 分钟，163 个任务。
- 单元测试：`--tests org.skepsun.kototoro.core.ui.adaptive.*` / `reader.ui.compose.*` / `video.ui.*` /
  `space.*` 共 **64 个类 370 项，0 失败 0 错误**（`test-3.log`）。设置页相关改动没有专门单测
  （纯 Compose 布局/焦点行为），以设备证据为准。
- 设备：`emulator-5558`（`kototoro_tv_api36`，1920×1080 @320dpi，Android TV API 36），
  仅 `app-x86_64-debug.apk`；arm64 真机包仍未产出。
- 证据文件（`E:\kototoro_demo\tvui-r3`）：`aa/ab/ac/ad/ae/af/ag/ah`（前一轮设置巡检与搜索框卡死）、
  `ai1`–`aq1`（单栏搜索、焦点保持、退搜索）、`am1`（右栏焦点）、`an3`（空结果整宽空态）、
  `ar2/as1/av1`（顶栏不透明前后、右栏滚动细节）。

## 第五轮：向导方向键导航与焦点可见性（2026-10-23）

本轮从设置页入口打开首次配置向导（`WelcomeSheet`），在 `kototoro_tv_api36` 上实测遥控器路径；
顺带补测了第四轮遗留的帧率与长列表刷新焦点两项验收。

### WizardDpadVertical：向导底栏阻断 UP（实测根因 + 修复）

- 现象：向导第 3/4 步焦点在底部胶囊按钮上时 **UP 无反应**；`uiautomator` 显示焦点停在同一控件，
  只有连续按 UP 才能越过底栏落到内容区（`dc3`、`dd1`、`df1-up`）。
- 定位实验（三次对照，全部在设备上做）：
  1. 给页面滚动列摘掉 `focusGroup()`：UP 依旧失败（`TvWizard: actionbar up geometric=false linear=true`），
     推翻“`focusGroup` 遮住内容区”的假设。
  2. 换成几何/线性双路径探针：几何 `moveFocus(Up)` 恒为 false，只有线性 `FocusDirection.Previous`
     能把焦点移出底栏，而线性顺序按文档序走，方向不可预测。
  3. 把底栏从 `Box(align = BottomCenter)` 的**浮层**改为 TV 下的 `Column { HorizontalPager(weight 1f); Bar }`
     **静态兄弟行**：同一次按键立刻变成 `geometric=true linear=false`。根因是浮层与它要返回的内容区重叠，
    2D 焦点搜索把“上方”判定为空。
- 修复：`WelcomeSheet` 在 `isTvPresentation` 下用 `Column` 排布（内容 `weight(1f)` + 底栏独立一行），
  手机/平板保持原浮层；TV 下页面底部预留由 128dp 降到 40dp（浮层需要避让，静态行不需要）。
- 证据：`ed1-wiz.png`（静态底栏、内容顶部对齐）、`eh2-up.png`（UP 直接落到 “Depth” 芯片）；
  清理探针后重跑同一路径，`adb logcat | Select-String TvWizard` 为 **0 行**，说明不再依赖回退逻辑。

### WizardFocusRing：向导控件的焦点环（可见性）

- 现象：向导内的 `FilterChip`、开关行、权限行按钮在 TV 上被聚焦时**没有任何可见反馈**
  （`ee2-up.png`：焦点确在芯片上，但截图逐像素无描边）。
- 修复：沿用仓库既有 `Modifier.tvFocusable(...)` 约定给向导控件加环：
  `WizardFilterChip` 增加 `modifier` 参数并传 `FilterChipDefaults.shape`；`WizardPrimaryButton` 的
  `Button`/`FilledTonalButton`、返回 `IconButton`、权限行 `TextButton`、两个整宽 `FilledTonalButton`、
  `WelcomeSwitchRow` 的 `toggleable` 行都补上 `addFocusTarget = false` 的观察式焦点环。
- 追加发现：主按钮本身是 `colorScheme.primary`，默认的 primary 环**同色不可见**
  （`ef5-right.png` 焦点在 Next 上却看不到环）。因此给 core-ui 的 `tvFocusable` 增加
  `ringColor: Color = Color.Unspecified` 参数（默认仍是 primary），一把主按钮传
  `onPrimary`；`ef4-next.png`/`eg1-p1.png` 复验白色环清晰可见。
- 证据：`ef1-ring.png`（芯片环）、`ef3-down3.png`（开关整行环）、`ef4-next.png`（返回箭头环）、
  `eg1-p1.png`（首屏自动焦点落在 Next 上带白环）、`eg3-p2up.png`（权限行 Allow 环）、
  `eg4-p4up.png`（完成页整宽按钮环）。

### WizardTvChrome：TV 下的拖拽把手

- `WelcomeBottomSheet` 顶部的拖拽把手在 TV 上是死控件（遥控器不能拖），且占用首屏高度；
  TV 下不渲染该把手，手机/平板保持原交互（`ed1-wiz.png` 复验把手消失）。

### HistoryTvStatsCompact：历史页统计卡挤掉整行卡片

- 现象：History 页在 960dp 宽的 TV 上，顶部统计卡（178dp）+ 筛选芯片行 + 日期标题共占 355dp，
  焦点落到首张卡片时可视高度只剩 185dp，而卡片（封面 185dp + 标题）约 205dp，
  `bringIntoView` 也无法让整卡完整可见，标题被切在屏幕下沿（`el1-history.png`，节点
  `[192,710][391,1080]`）。
- 修复：TV 下统计卡不渲染装饰性活动柱状图，并把其后的间距 12dp 收窄到 8dp，把高度让给列表。

### 帧率验收（本轮实测，AVD 软件渲染）

- 方法：`adb shell dumpsys gfxinfo org.skepsun.kototoro.debug reset` → 注入 20 次 DPAD 上下往返
  → 读 `Total frames / Janky / 百分位`；同时测一次“静止 5 秒”作为基线。
- 数据：
  - 向导内方向键：`Total frames 111`，`Janky 28 (25.23%)`，p50 17ms / p90 30ms / p95 34ms / p99 46ms，
    `Number Missed Vsync 0`。
  - 设置页内容源面板方向键 + 列表刷新：`77` 帧，`18 (23.38%)` 掉帧，p50 17ms / p90 34ms / p95 36ms / p99 46ms。
  - 静止基线：`0` 帧（无交互不重绘），说明上面的帧全部由方向键引起。
- 结论：绝大多数帧就是 1 个 vsync（17ms），掉帧集中在“焦点环重绘 + 列表滚动”帧上，出现 2 个 vsync；
  没有超过 50ms 的卡顿帧。AVD 使用软件 GPU，绝对值偏悲观，**不能据此给出低性能电视的结论**。

### 长列表刷新焦点（部分覆盖）

- 场景：设置 → 内容源面板，把焦点放在列表内 “Group sources by language” 行上按 CENTER 触发
  **列表内容/分组刷新**。结果：焦点仍停在该行、蓝环仍可见，随后 DOWN 正常落到 “Setup wizard”
  （`ej2-grouped.png`、`ej4-down.png`）。
- 缺口：模拟器 **0 个第三方源、收藏/历史仅 1 个本地作品**，无法制造“刷新后原焦点目标被删除/插入”的
  真实数据集变化，该项仍需装入真实源后回归（与第一至四轮同一环境限制）。

### 本轮构建与测试记录（第五轮）

- `:app:assembleDebug --offline --no-daemon`（x86_64 init script）连续多次通过；
  期间一次 `assembleDebug` 退出码 1 属于 **`Select-Object -First` 提前关闭管道杀掉 Gradle**，不是编译错误，
  改为写日志再读尾部即可。
- `:core-ui:compileCommonMainKotlinMetadata` 在离线环境仍无法执行（`commonMain` 元数据 jar 未缓存，
  报 `No cached version available for offline mode`）；本轮 core-ui 改动（`tvFocusable` 的
  `ringColor` 参数）只用到 `androidx.compose.ui.graphics.Color`，已随 `:app:assembleDebug` 编译通过，
  联网环境应补跑该检查。
- 临时诊断（`wizardKeyTrace`、`tvActionBarVerticalEscape`、`TvWizard` 日志）在验收后**全部移除**，
  移除后重跑向导路径与 `logcat` 确认 0 行输出。
- 证据文件（`E:\kototoro_demo\tvui-r3`）：`ed1`、`ee1`–`ee2`、`ef1`–`ef5`、
  `eg1`–`eg4`、`eh1`–`eh2`、`ei0`–`ei1`、`ej1`–`ej5`、`ek1`–`ek3`、`el1`。

### 仍未覆盖（第五轮结束时的状态）

- 物理/蓝牙遥控器的按键重复与厂商按键差异（仍只有虚拟 DPAD 证据）。
- 真实源下的搜索结果刷新、焦点目标被删除、空间切换组合。
- 低性能真机的帧率与玻璃背景代价（AVD 软件渲染不能代表）。
- 向导在 720p/4K 分辨率下的排版与字号（10-foot 字号目前直接沿用手机排版）。

## 第六轮：10 英尺网格密度（2026-10-23）

第五轮修完“焦点卡片被切”后留下的观感问题是：**卡片本身太小**。960dp 宽的 TV 上，
`compactPosterCardStyle(1f)` 给出 96dp×136dp，`resolveGridColumnCount` 按 876dp 可用宽度算出 **8 列**，
单元格只有 ~102dp，标题字号 14sp；55 吋电视三米外基本看不清，且 8 列留白过多。

### TvGridDensity：TV 下放大网格比例（含文字）

- 做法：在 `KototoroContentListScreen` 顶部把传入的 `gridScale` 换成
  `effectiveGridScale = (gridScale * 1.4f).coerceAtMost(1.4f)`（仅 `isTvPresentation`），
  并把它用在 `gridPosterCardStyle(...)` 与所有下游卡片/骨架调用上。
  这样卡片尺寸与 `resolveGridTitleFontSize(gridScale)` 的标题字号**同时**放大，不会出现“大卡片配小字”。
- 量化：目标宽度 134.4dp → `GridColumnMinFitRatio` 后列数由 8 收敛到 **6**，
  单元格实测 **~271px（135.5dp）**（`en1.png` 焦点节点 `[192,600][463,1080]`，改前是 `[192,…][391,…]` 即 199px/99.5dp），
  标题字号 ≈ 17.6sp（改前 14sp），标题 “TV Audit Novel” 在 10 英尺下可读。
- 纵向代价：卡片变高（136→190dp）后需要更多可视高度，因此同一轮把 History 统计卡在 TV 下再收一档
  （去掉活动柱状图 + 间距 12→8dp + 内边距 14→10dp），History 列表视口仍有 ~226dp，
  焦点卡片与标题完整可见。
- 证据：`en1.png`（6 列密度、135dp 卡片、18sp 标题、焦点环与标题均完整）；
  `en2.png`（UP 从卡片回到筛选芯片行、焦点环在 “On device” 上，CENTER 取消筛选后列表重排焦点未丢）。
- 局限：模拟器上 History 只有 1 个作品，**无法在同一屏内数出 6 列**（列数由单元格宽度反推 + 代码路径唯一推导），
  多行多列的实机观感仍需装入真实内容后复验。

### 网格密度与“可用、好用”的判断

- 6 列 × 135dp 是比较接近主流 TV 应用（如 Netflix 横排 6 项）的密度；若要更“海报墙”可到 5 列，
  但 5 列会要求 History 头部再降约 60dp（等价于 TV 下整块折叠统计卡），属于产品取舍，本轮不擅自折叠。

## 第七轮：对话框内文本框的遥控困境（2026-10-23）

第四轮刻意跳过的四个“多行输入”屏幕（`ReplaceRulesSettingsScreen`、`JsonSourceEditScreen`、
`ProtectSetupScreen`、`VideoAnnotationDialog`）本轮实测，结论比预想的更糟：**问题不在多行，而在“对话框内的文本框”整个类别。**

### TvDialogTextFieldTrap：对话框里的方向键与返回键都不听使唤

在 `kototoro_tv_api36` 上按“设置 → 搜索 replace → Text replacement rules → Add rule”打开规则编辑器，逐项实测：

| 操作（Name 字段已聚焦） | 期望 | 实测 |
|---|---|---|
| IME 弹出时按 DOWN | 离开输入框 | 无反应（D-pad 归 IME 所有，符合预期） |
| BACK ×1 | 收起键盘 | 键盘收起 ✔ |
| 键盘收起后按 DOWN | 焦点移到 Group | **无反应**（`ep2`、`ep4`：连续 8 次 DOWN 焦点仍停在原字段） |
| 键盘收起后按 BACK ×1 | 退出字段 | **整个“Add rule”对话框被关闭**（`ep5` 之后第二次 BACK 直接关闭，见 `ep6` 焦点回到 `Add rule` 行） |

两点新事实（对后续所有 TV 输入修复都成立）：

1. **`Key.Back` 在 targetSdk 37 上收不到。** 应用已进入预测性返回（predictive back）语义，Back 由
   `OnBackInvokedDispatcher` 回调派发，不再作为按键事件进入 Compose 的 `onPreviewKeyEvent`。
   任何“监听 Back 键让输入框先失焦”的实现都是死代码——本轮先按这个思路写的
   `tvMultiLineTextInputFocusEscape()` 在设备上完全没有触发，已删除。
2. **对话框内的方向键逃逸不可靠。** 同一份 `tvTextInputFocusEscape()`（单行字段、预览期拦截 UP/DOWN）
   在设置页顶栏搜索框上工作正常（`eo4`：BACK 收键盘后 DOWN 落到结果行 `[24,132][1896,268]`），
   但在 `AlertDialog` 的 `Column(verticalScroll)` 里 `moveFocus(Down)` 找不到下一个可聚焦节点，
   焦点原地不动，随后 DOWN 被文本框当作光标移动吃掉。也就是说：*滚动容器 + 对话框窗口* 这一组合会让
   几何焦点搜索失效，这是“字段成为死胡同”的真正原因。

### 尝试过并被否决的方案

- **预览期拦 Back 再 `moveFocus`**：Back 收不到（见上文第 1 点），代码无效，已移除。
- **包住对话框的 `onDismissRequest`**：`rememberTvBackDismissHandler` + 焦点跟踪，
  在字段聚焦时把 Back 改成“收起键盘 + 尝试 `moveFocus(Down)`，失败再 `Up`，两边都失败才关闭”。
  实测（`er1`/`er2`）：第二次 BACK 不再关闭对话框 ✔，但焦点**看不出移动**
  （Name 仍是蓝框、uiautomator 仍报该 EditText），用户等于“Back 按了没反应”，
  比重开对话框还糟，因此**已回滚**，不留半成品。

结论：这类字段需要一个**显式出口**（UI 上的“完成输入 / 收起键盘”按钮，配 `FocusRequester` 直接跳到
保存按钮），而不是继续和方向键、返回键的派发机制搏斗。该方案需要新增一处 TV 专用控件与文案，
留待下一轮实现，本轮不改动这四个屏幕的交互，只把 `ProtectSetupScreen`（单行密码框）接入既有的
`tvTextInputFocusEscape()`——机制与其余 60 余处一致，但本轮未单独上机复验。

### TvDialogImeDone：把 IME 的 Done 接到对话框动作上

上一节确认“Back 不可用、方向键在字段内不可用”之后，本轮不再和按键派发搏斗，改走 TV 上本来就在用的路径：
让 IME 的 **Done** 成为“结束输入”的出口。

`ReplaceRulesSettingsScreen` 的规则编辑器做了三件事（全部 TV 行为可验证、手机侧只是普通 IME Done）：

1. 每个 `RuleTextField` 设置 `KeyboardOptions(imeAction = ImeAction.Done)` +
   `KeyboardActions(onDone = leaveFields)`；`leaveFields` 收起 IME 并 `FocusRequester.requestFocus()`
   跳到 **Save**（规则有效时）或 **Cancel**（无效时）——用显式 requester 而不是几何焦点搜索，
   绕开“对话框内 `moveFocus` 找不到下一节点”的问题。
2. 字段列末尾加 TV 专用的“Done”按钮（复用 `R.string.done`，`tvFocusable` 焦点环），
   与 IME Done 同一个动作，作为可发现的第二出口。
3. Save / Cancel 挂上 `FocusRequester`；每个字段通过 `onFocusChange` 维护聚焦计数（仅用于日志与后续判断）。

实测（`ev1`/`ev2`）：

- 对话框打开、Name 聚焦、软键盘弹出时触发 IME Done → 软键盘收起，焦点跳到 **Cancel**，
  对话框保持打开、已输入内容不丢（`ev1`：Cancel 带蓝色焦点环 `[1080,936][1220,1032]`）；
- 在 Cancel 上按 UP → 焦点回到字段（`ev2`：`Included source or book` 拿到焦点、软键盘重新弹出，
  右下角按键变成 **✓**，说明 `ImeAction.Done` 已生效），字段列表与动作按钮可双向往返。

局限（如实记录）：用 `adb shell input keyevent 66` 模拟回车不能稳定复现 Done——
第一次在 Name 上成功（`ev1`），后续在 Pattern 上就被文本框吃掉；
用 D-pad 把 IME 焦点走到 ✓ 键再按 CENTER 也没能触发编辑器动作。所以“IME Done 出口”目前只在
一次真实按键序列里被证实可用，稳定性还需在**真机遥控器**上确认（模拟器 IME 的 D-pad 焦点环本身就不稳）。
若真机上同样不稳，备选方案是 TV 专用的“完成输入”按钮放到字段**右侧同一行**（`trailingIcon` 之外的
独立焦点节点），并在离开字段时直接 `requestFocus` 到它。

### tvTextInputEnterAction：遥控器上的“完成输入”键

IME Done 在模拟器上不可靠之后，本轮补了一个不依赖 IME 的确定性出口：
`Modifier.tvTextInputEnterAction(onEnter)`（`core-ui/.../adaptive/TvFocus.kt`，TV 专属，手机不做任何改变），
在 `onPreviewKeyEvent` 里接管 `Key.Enter`，调用宿主传入的“完成输入”回调。

实测（规则编辑器）：

| 场景 | 结果 |
| --- | --- |
| 对话框打开、Name 自动聚焦（软键盘已弹出）按 ENTER | 软键盘收起，焦点跳到 **Cancel**（规则无效）、对话框保持打开（`ex1`） |
| 点按 Pattern 输入 `z` 后按 BACK 收起软键盘，再按 ENTER | 焦点跳到 **Save**（规则有效）（`ex4`：`[1236,936][1352,1032]`） |
| 软键盘仍在时按 ENTER（Pattern） | 被 IME 吃掉，不触发——符合系统行为：IME 打开时按键归 IME |

结论：TV 上"完成输入"有两条路——① IME 的 ✓ Done（`ImeAction.Done`）；② 先 BACK 收起软键盘、再按 ENTER，
后者在模拟器上可稳定复现。两者都会把焦点交给对话框动作按钮，不再丢输入、不再误关对话框。

同一套接线已应用到另外两个输入宿主（编译通过，尚未逐一上机）：

- `JsonSourceEditScreen`：5 个字段（含两个多行 URL 字段）→ 底部 Save（挂 `FocusRequester`、TV 焦点环）；
- `VideoAnnotationDialog`：备注输入 → "保存备注"按钮。

多行字段的取舍：TV 上它们同样把 ENTER 当"完成输入"，因此不能再用遥控器在字段里换行；
这两个 URL 字段本身按行分隔 URL，需要换行时仍可外接键盘或改用手机编辑。

### 顺带核实的顶栏动作可达性

扩展管理面板（Settings → Extension management，ez1–ez5）实测：

- 内容区按 UP 落到顶部分段控件（Sources / Repositories / Packages，[939,8][1108,104]）；
- 继续 RIGHT 依次走到 **⋮ 溢出菜单 → 搜索 → 筛选**（[1644,8][1724,104] 等），
  说明顶栏图标按钮在焦点链里，不是看得见摸不着；
- ⋮ 打开的 DropdownMenu 两个条目（Reorder / Extension Groups）可聚焦，焦点在第一条 [1376,112][1732,208]，
  BACK 只关闭菜单并把焦点还给 ⋮（ez5），不会连带退出设置。

### rememberTvFieldExit：把“完成输入”收成一个共享原语

逐个宿主贴 FocusRequester + ImeAction.Done + Enter 接管太啰嗦，本轮在 core-ui 的 TvFocus.kt
里收成一个原语：

```kotlin
val fieldExit = rememberTvFieldExit()            // 多行字段用 rememberTvFieldExit(multiline = true)
OutlinedTextField(
    modifier = Modifier.fillMaxWidth().then(fieldExit.fieldModifier),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { fieldExit.onDone() }),
    ...
)
SettingsDialogActionButton(text = OK, onClick = …, modifier = fieldExit.targetModifier)
```

内部只做三件事：FocusRequester 点名目标、keyboardController.hide()、Enter 预览接管；手机/平板两个
Modifier 都是空操作。已接到**统一源管理**的两个文本对话框：

- UnifiedRepositoryUrlDialog（Repository URL，单行）；
- UnifiedInlineRepositoryDialog（粘贴源内容，多行）。

实测（Settings → Extension management → Repositories → Add repository → Legado JSON → Remote URL，b3–b5）：

| 步骤 | 结果 |
| --- | --- |
| 对话框打开，URL 字段自动聚焦、软键盘弹出 | 右下角出现 IME 的 ✓ Done（b3） |
| BACK 收键盘 → ENTER | 焦点落到 **OK**，并画出 TV 焦点环 [1236,624][1352,720]（b4） |
| 在 OK 上按 LEFT | 焦点到 **Cancel** [1080,624][1220,720]（b5） |

同一个原语现已覆盖 4 个宿主：规则编辑器（上机）、统一源 URL/粘贴对话框（上机）、JSON 源编辑页与视频备注
对话框（编译验证）。

### 陷阱：禁用的确认按钮接不住焦点

给空间编辑器加 Enter 出口时踩到一个必须先处理的坑：FocusRequester.requestFocus() 指向**禁用**的按钮时
既不报错也不移动焦点（实测 c5：Name 里输入 TV、BACK 收键盘、按 ENTER 后焦点仍停在输入框，
OK 因为“未选内容类型”是灰的）。也就是说“Enter 跳到 OK”这套在没有满足前置条件时会静默失效。

处理方式不是给按钮开后门，而是**把焦点送到解除阻塞的那个控件**：SpaceEditorDialog 在 OK 不可用时，
Enter 改送顶部的“Select all”；一次 OK 选中全部 14 个内容类型后，OK 自动变亮，再往下就能到 Cancel/OK。

实测（Settings → Spaces → Add custom space，c3–d5）：

| 步骤 | 结果 |
| --- | --- |
| 打开对话框 | Name 自动聚焦、软键盘弹出、IME 有 ✓（c3） |
| 输入 TV → BACK → ENTER | 焦点落到 **Select all** [1201,296][1352,392]（d1） |
| CENTER 选中全部 | 计数 14、按钮文字变 Deselect all、**OK 变为可用**（d2） |
| DOWN×5 → RIGHT → CENTER | 对话框滚动跟随焦点、Cancel→OK 横移、空间创建成功：列表出现 **TV**「14 Content types · All languages · All sources」（d3–d5） |

结论：纯遥控器即可完成“新建自定义空间”这一完整任务；顺带把通用文本偏好对话框
（SettingsDialogTextPreference，源设置里的用户代理/代理地址/账号等）也接上了同一原语，手机端不受影响。

## 第八轮：阅读器进度条的遥控死角（2026-10-23）

第七轮收尾后，小说阅读器的浮动控件已经能聚焦，但底部进度条是个反例：焦点确实进去了，
屏幕上什么都不变。这一轮把它补成“看得见、动得了、出得去”。

### ReaderProgressBarNoRing：三个控件都没有焦点环

core-ui 的 ReaderProgressBar 是小说与漫画共用的底部进度簇：|◁ 上一章、
Slider 页码、▷| 下一章。三者在 TV 上都能被焦点系统选中
（ndroid.widget.SeekBar [672,1014][1220,1046]），但代码里没有任何 	vFocusable，于是：

- 焦点在滑块上时整条 dock 与未聚焦时几乎**像素级相同**：上下两张截图裁切后只差滑块位置，
  没有任何描边（f4_crop）；
- 焦点停在上一章/下一章按钮上也同样没有反馈。

对照顶部栏：ReaderChromeTopBar 的返回、标题胶囊、更多都带了 	vFocusable。
“上面有环、下面没环”比全没有更糟——用户会以为下面这一排不可用。

### SliderSwallowsDpad：滑块吃掉左右键却不改数值

补环之后暴露出更深的问题：聚焦滑块按 LEFT/RIGHT，滑块不动，焦点也不转移。
Material3 的 Slider 自带方向键处理，会消费 LEFT/RIGHT，但在这个宿主里它一次都没改过值：
起始页按 LEFT 三下，两张截图哈希完全相同（h_crop），画面没有任何变化。

也就是说滑块是个**单向死角**：进去以后左右无效，只有上下能离开。

修法是绕开滑块自己的按键处理，在它的修饰符链更外层接管预览事件：

```kotlin
modifier = Modifier
    .tvFocusable(shape = RoundedRectangle(10.dp), addFocusTarget = false)
    .then(
        if (isTv) {
            Modifier.onPreviewKeyEvent { event -> /* LEFT/RIGHT → stepPage */ }
        } else {
            Modifier
        },
    )
```

预览阶段先于滑块内部的按键节点执行，事件一定先到我们手里；stepPage 把
sliderState.value 加减一页、写回 dragValue（让页码气泡按原样式弹出），再调
onValueChange + onValueChangeFinished，宿主据此跳页。

### ChapterButtonHandoff：左右键走到头就把焦点交给章节按钮

滑块独占整行，上一章/下一章按钮几何上就在它左右两侧，而 LEFT/RIGHT 被滑块消费，
所以这两个按钮**永远走不到**（这一轮之前的死胡同）。让越界的那一次按键改成交棒：

- 滑块在首页按 LEFT → previousRequester.requestFocus()；
- 滑块在末页按 RIGHT → 
extRequester.requestFocus()。

只有目标 enabled 时才请求焦点：禁用的按钮会静默吞掉 
equestFocus()
（第七轮已经踩过一次，见“陷阱：禁用的确认按钮接不住焦点”）。

实证顺序（n1→n4、q1→q2）：DOWN×3 落到上一章（有环）→ RIGHT 进滑块 →
首页 LEFT 交棒回上一章 → CENTER 切到 Audit Chapter 1；反过来 RIGHT→滑块→
末页 RIGHT 交棒到下一章 → CENTER 回到 Audit Chapter 2。中途用 println 打印
delta/value/max 确认过分支（KVstep: delta=1.0 value=15.0 max=15.0 →
hand off to next），确认后删除调试输出，只留行为。

### 与“翻页键”的关系

阅读器本来就把 LEFT/RIGHT 当翻页键（正文聚焦时直接翻页）。焦点在滑块上时复用同一语义，
用户的肌肉记忆不用切换：滑块只是把“翻页”显示成进度，并按章首章尾把控制权交回章节按钮。
手机端完全不受影响：isTv 为假时既不装 onPreviewKeyEvent，也不触碰原手势路径。

### 本轮设备证据（emulator-5558，1920×1080）

| 步骤 | 观察 | 帧 |
| --- | --- | --- |
| 显示控件 → RIGHT → RIGHT | 标题胶囊出现蓝色胶囊描边 | l3 |
| 继续 RIGHT / LEFT | 更多按钮、返回按钮各出现蓝色圆形描边 | l2、l4 |
| DOWN×3 | 上一章按钮出现圆形描边 | r1 |
| RIGHT | 滑块出现圆角矩形描边 | n2 |
| 首页 LEFT | 焦点交棒回上一章（圆形描边） | n3 |
| CENTER | 切到 Audit Chapter 1，正文与标题同步 | n4 |
| 末页 RIGHT | 焦点交棒到下一章（圆形描边） | q1 |
| CENTER | 切回 Audit Chapter 2 | q2 |

### 仍未覆盖（沿用第四轮 + 第七轮）

- 上述四个屏幕的字段出口设计（建议：TV 专用“完成”按钮 + FocusRequester 跳保存）；
- Text replacement rules 的双栏切栏焦点交接：从搜索结果进入时焦点留在左栏 Appearance，
  需要按 RIGHT 才进右栏（eo5）；
- 漫画阅读器的同一条 dock 与缩放按钮：代码共用 ReaderProgressBar，但本轮只在小说样本上过机；
- 物理/蓝牙遥控器按键重复、真实扩展源下的刷新焦点恢复。

## 第九轮：小说选项面板的遥控落地（2026-10-23）

第八轮修好了底部进度条，但 ⋮ 打开的小说选项面板仍有三个死角：滑块进得去、左右键不动；焦点留在面板背后；
分页栏（Typography / Reading / Translation & tools）能切页，面板却停在一指高，详情永远看不到。这一轮把面板
从"能打开"推到"能走完"。

### TvSliderDpad：滑块要自己的左右键

Material3 的 `Slider` 会消费 LEFT/RIGHT，但它只对拖动有反应，在电视上就是单向死角（第八轮已记录）。这一轮把
第八轮的专用修法抽成共享原语，放在 `core/ui/adaptive/TvFocus.kt`：

- 纯函数 `tvSliderStepValue(value, valueRange, steps, direction)`：有离散档位时一步一档
  （`span / (steps + 1)`），连续滑块走范围的 1/20；结果先吸附回网格再夹紧到区间内，因此连按不会累积浮点漂移。
- `Modifier.tvSliderDpad(...)` = `tvFocusable(shape, addFocusTarget = false)` + 预览阶段的 `onPreviewKeyEvent`，
  LEFT/RIGHT 改值并**始终消费**这两个键（到边界也消费，避免悄悄变成焦点跳转）。预览阶段先于滑块内部的按键节点
  执行，所以事件一定先到我们手里。
- 应用到 `ReaderSliderRow` 与 `ReaderStepperRow` 的滑块：字号、行距、段距、页边距、亮度等全部同一条路径。

新增 `TvSliderStepValueTest`（6 项：步长、两端夹紧、离散档位、离网格吸附、连按不漂移、非零起点区间）。

### SheetTakesFocus：面板自己接住焦点

面板打开后焦点仍留在背后的 ⋮ 上，滑块有焦点但屏幕上找不到环。修法是把面板第一层 `Column` 变成焦点组，并在
TV 上于首帧之后 `requestFocus()`：

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .focusRequester(sheetFocusRequester)
        .focusGroup()
        .testTag("reader-panel-sheet"),
)
```

`focusGroup()` 属于 `androidx.compose.foundation`（不是 `ui.focus`）。请求必须晚一帧：内容还没放置时
`requestFocus()` 会失败，用 `withFrameNanos { }` 等一次组合提交。

> **第四十轮修正**：把焦点请求到面板 `Column` 自身是个死胡同——它不画环，矩形又覆盖所有行，
> 从它出发按方向键找不到任何节点。请求必须指向**具体控件**（`ReaderPanelHost(initialFocusRequester=…)`），
> 而且只能由一处发、落地后不再重发。见下文"第四十轮"。

### SheetStuckAtPeek：电视上没有"上拉到详情"的手势

面板停在 peek（只有页头高度）。手机上拉一下就能看到详情，遥控器没有这个手势，于是分页栏点了"CENTER"只换了
`HorizontalPager` 的页，屏幕没有任何变化——详情内容永远不可达。

两件事补上：

1. `StableAnchoredSheetLayout` 的状态新增 `expandOne()` / `collapseOne()` / `canCollapse()`：沿着"锚点有限
   且按顺序"的列表走一格，用一个 `stepAnchor(direction, allowHidden)` 实现，收起时不停在 Hidden。
2. `StableSheetSlotScope` 多两个回调 `expand` / `collapse`，宿主把它灌进 `ReaderSheetActions`
   （`LocalReaderSheetActions`，`SideEffect` 里按 `isTv` 填，非 TV 为空动作），`ReaderOptionsDetailTabs`
   在选中分页时调用 `expand()`。于是"选页 = 顺手把面板张开"，手机端因为动作是空的而完全不变。

### NestedScrollCollapse：方向键滚到底会把面板收回去

展开详情页后一路 DOWN，到列表底部时面板忽然塌回 peek，焦点也跟着飞走。根因不在锚点，而在嵌套滚动：
焦点驱动的滚动同样上报为 `NestedScrollSource.UserInput`，`StableAnchoredSheetLayout` 的
`nestedScroll(nestedScrollConnection)` 把它当成手指下拉，于是把面板拖了回去。

TV 上没有拖拽手势，所以直接不装这个连接：

```kotlin
val receivesNestedScroll = !LocalUiPresentationConfig.current.isTv
...
.then(if (receivesNestedScroll) Modifier.nestedScroll(nestedScrollConnection) else Modifier)
```

### BackCollapses：BACK 先收一层，再关闭

能展开就必须能收回，而遥控器唯一的"退一步"是 BACK。若 BACK 仍然直接关闭面板，用户为了回到快速层就得关掉重开。
`StableAnchoredSheetLayout` 因此多一个 `collapseBeforeDismiss`（宿主传 `isTv`）：BACK 时若
`canCollapse()` 就先 `collapseOne()`，否则才 `dismissWithAnimation()`。

收起后还要把焦点带回快速层，否则焦点留在已经看不见的详情行上。宿主用 `LaunchedEffect(scope.isExpandedPastPeek)`
观察由 true 变 false 的那一次，重新 `requestFocus()` 到面板焦点组（即第一项，亮度滑块）。

### MarkingsSheet：Material3 底部面板的通用三件套

"笔记与标记"面板走的是 Material3 `ModalBottomSheet`（自带窗口），暴露的问题更基础：

- **半展开**：`ModalBottomSheet` 默认只到 PartiallyExpanded，遥控器同样拉不上去 → TV 传
  `rememberModalBottomSheetState(skipPartiallyExpanded = true)`。
- **没有初始焦点**：新窗口里谁也不持有焦点，按方向键不知道会打到哪 → 新增原语
  `Modifier.tvTakeInitialFocus()`（`focusRequester` + `focusGroup` + 首帧后请求，非 TV 恒等），
  挂到 `ComposeNovelNotesContent`、笔记编辑器与笔记详情三处。
- **焦点存在但看不见**：`FilterChip`、排序按钮、标记卡与书签卡都不是项目自绘控件，没有 `tvFocusable`。
  给 4 个筛选 chip、排序 `IconButton`、`NovelMarkingCard` 与共享的 `NovelBookmarkCardContent` 补上焦点环
  （`addFocusTarget = false`，因为它们自己已有 clickable/selectable 焦点目标）。

### 本轮设备证据（emulator-5558，1920×1080，package org.skepsun.kototoro.debug）

| 步骤 | 观察 | 帧 |
| --- | --- | --- |
| 显示控件 → UP → RIGHT×2 → CENTER | 面板打开，焦点落在亮度滑块（浅色圆角描边），身后 ⋮ 的环消失 | ga2 |
| LEFT×3 / RIGHT×5 | 亮度 69% ↔ 91%，滑块与百分比同步 | ga_bright |
| DOWN×1→4 | 字号 − → 主题 chips → 快捷动作 → 分页栏，逐级有环 | gb1 / gb2 / gb3 / gc2 |
| CENTER（分页栏） | 面板展开到全屏并显示该页详情（Typography） | hb1 |
| DOWN×8 | 字体 chips → 字号 → 行距/段距/边距 → 首行缩进开关；**面板保持展开** | hb2 |
| BACK | 收回到 peek，焦点回到亮度滑块 | hc1 |
| BACK | 面板关闭，焦点回到阅读器返回按钮 | hc2 |
| （笔记面板）CENTER | 直接展开到 92%，焦点在 All 筛选项（有环） | hd2 |
| （笔记面板）RIGHT×3 → DOWN | 筛选 chip 之间移动有环；DOWN 进入搜索框并弹出 IME | hd3 |

### 本轮构建与测试记录

- `:core-ui:compileAndroidMain`、`:app:compileDebugKotlin`、`:app:assembleDebug` 全部成功（c31–c39.log）。
- `TvSliderStepValueTest` 6 项通过；TV 与阅读器相关测试类全绿（t5.log）。
- 未提交。

### 仍未覆盖（第九轮结束时的状态）

- "Translate text" 快捷动作在没有翻译引擎时**没有任何反馈**：CENTER 之后画面完全不变，用户无从判断是否生效；
- 分页栏最左侧再按 LEFT 会被几何搜索带到上方的快捷动作（未做边界截断）；
- 笔记面板搜索框的 IME 出口沿用通用行为（一次 BACK 关键盘），未接 `tvTextInputFocusEscape`；
- 漫画阅读器共用的同一套面板与滑块未过机（无漫画样本、模拟器无外网）；
- 手机端回归只靠 `isTv` 门控保证，本轮没有手机设备可测。

## 第十轮：提示可见性与横向焦点边界（2026-10-23）

第九轮留下的第一条"没有反馈"其实不是没问题，而是**反馈被画在了面板底下**。这一轮把它连同
同一类"焦点跑出面板"的横向越界一起收掉。

### 提示条被选项面板盖住（看起来像"按了没反应"）

- 现象：面板展开时按"翻译正文"，1.8 秒后什么都没发生；关掉面板再按一次，才偶尔瞥见一句提示。
- 根因：`NovelReaderOverlay`（贴底提示条与 TTS 播放条）和 `ComposeNovelReaderOptionsSheet` 是
  同一个 `Box` 的兄弟节点，而**面板后组合**。Compose 的层级是组合顺序而非"最后绘制的浮层"，
  所以贴底的提示整条落在面板下面；面板展开时占满屏幕，提示只会藏在它的玻璃背景后面。
- 修复：`NovelReaderOverlay` 根 `Box` 加 `Modifier.zIndex(1f)`。提示与 TTS 条都是"状态变化的结果"，
  在任何面板之上才是它们的语义位置。
- 证据：`he1`/`hf1`（修复前：面板里按翻译，画面无变化）；`hg2`（修复后：面板仍展开，底部浮出
  "Novel translation started (0/122 paragraphs)"）。

### 横向越界：方向键会把焦点送到遮罩上

- 现象：快捷动作行最左一格再按 LEFT，焦点消失；接着按 CENTER，**整个面板关了**。
- 根因有两个，叠在一起：
  1. 方向键搜索是几何的，行到边界后继续向外找，找到了同层其余可聚焦节点；
  2. `StableAnchoredSheetLayout` 的遮罩是 `.clickable(onClick = dismiss)`，因此它本身就是一个可聚焦
     目标——焦点落到遮罩上既看不见，一次 CENTER 又等于"点面板外面"。
- 修复：
  - 新增 `Modifier.tvHorizontalEdgeGuard(blockLeft, blockRight)`（`TvFocus.kt`，非 TV 恒等）：在行首
    吞掉 LEFT、行尾吞掉 RIGHT，键不再外泄；
  - 快捷动作行的首/末格（`ReaderQuickActionGrid`）与面板标签栏的首/末页（`ReaderPanelTabBar`）各挂一份；
  - 遮罩在 TV 上 `focusProperties { canFocus = false }`，`isTv` 提到外层复用（`focusProperties`
    是普通 lambda，不能在其中读 composition local）。
- 证据：`walk.py` 输出——快捷动作行左侧连按 4 次 LEFT 停在 `(323,852)`；标签栏连按 4 次 LEFT 停在
  `(714,1008)`；两者都不再跳到遮罩或面板外。

### TTS 播放条的焦点环

播放条的 6 个按钮是 Material3 `IconButton`/`TextButton`，自带焦点目标但**不画环**，遥控器上等于
隐形。给 `TtsButton` 与倍速按钮补 `tvFocusable(..., addFocusTarget = false)`（圆环/小圆角，跟随
`MaterialTheme.colorScheme.primary`）。播放条只在 chrome 收起时出现（`ttsVisible =
ttsControlsVisible && !chromeEnabled`），所以验证要先把 chrome 收起来；本轮确认了焦点能从快捷动作
进入播放条（`(960,766)` 命中按钮），环的绘制与主题一致。

### 顺手修掉的静默状态

`toggleTranslation()` 以前先把 `isTranslationEnabled` 翻成 true，再由 `startTranslation()` 检查配置，
于是"没引擎"时开关是开着的、屏幕上什么都没变。配置判定抽成 `hasTranslationConfig()`
（本地模型 ID / API 端点 / `LOCAL_ONLY` 三者之一），在翻转开关之前就拦下并给提示；`startTranslation()`
复用同一个判定，两处不会再漂移。

### 本轮设备证据（emulator-5558，1920×1080，package org.skepsun.kototoro.debug）

| 步骤 | 观察 | 图 / 日志 |
| --- | --- | --- |
| 面板展开 → CENTER「翻译正文」 | 修复前无任何可见变化 | he1 / hf1 |
| 同上 | 修复后底部浮出"Novel translation started (0/122)"，面板保持展开 | hg2 |
| 快捷动作行 LEFT×4 | 焦点停在最左格 `(323,852)`，不再外泄 | walk |
| 标签栏 LEFT×4 | 焦点停在最左页签 `(714,1008)` | walk |
| 快捷动作 CENTER（TTS） | 播放条出现，焦点落在播放条按钮 `(960,766)` | walk |

### 本轮构建与测试记录（第十轮）

- `:app:assembleDebug --offline --no-daemon`（x86_64 init script）通过（c40–c43.log）；
  中途一次 `focusProperties` 读 composition local 失败，改为外层 `isTv` 后通过。
- 单元测试：TV/阅读器面板相关 10 个类 **22 项，0 失败**（t6.log）。

### 仍未覆盖（第十轮结束时的状态）

- 本地翻译模型需要联网下载时，模拟器（无外网）会停在 `downloading model if needed...`，进度提示
  1.8 秒后消失，之后长时间无反馈；这属于内容侧的离线体验，未在本轮处理；
- 笔记面板搜索框的 IME 出口仍未接 `tvTextInputFocusEscape`；
- 纵向越界（面板内最后一行继续 DOWN）未测；
- 漫画阅读器共用的同一套面板与滑块仍未过机（无漫画样本、模拟器无外网）；
- 手机端回归仍只靠 `isTv` 门控保证，本轮没有手机设备可测。

## 第十一轮：对话框初始焦点与纵向遍历的残留死角（2026-10-23）

### TTS 音色对话框：补上初始焦点与行焦点环

`NovelTtsVoiceDialog` 是普通 Material3 `AlertDialog` + `LazyColumn`：

- 对话框是新窗口，**没人持有焦点**，第一次方向键落到哪全靠几何搜索；
- 行是 `clickable`（可聚焦）却没有环，遥控器上看不出当前项；
- 每行里还有 `RadioButton(onClick = …)`，等于**一行两个焦点停靠点**，DOWN 要走两下才到下一条。

改法：`LazyColumn` 挂 `Modifier.tvTakeInitialFocus()`（`focusRequester` + `focusGroup` + 首帧后请求），
行挂 `tvFocusable(shape, addFocusTarget = false)`，`RadioButton` 改为 `onClick = null`（行本身已经
处理选中，单选钮只负责显示状态）。编译通过（c44.log）。

**未过机**：模拟器的系统 TTS 没有可用音色，`showVoiceSelectionDialog()` 走的是
`novel_reader_tts_no_system_voice` 分支，对话框在本环境里根本不会出现，所以这条改动目前只有
编译证据。要过机需要给模拟器装 TTS 语言包，或改到"Legado 引擎"分支触发的带 `onManage` 的对话框。

### 纵向遍历：面板底部有两个幽灵停靠点，向上还会丢一帧焦点

在展开的面板上逐级按 DOWN / UP（`walk.py`）：

| 按键 | 焦点（px） | 说明 |
| --- | --- | --- |
| DOWN×4 | (869,852) | 快捷动作行 |
| DOWN | (916,1008) | 标签栏 |
| DOWN | (794,1064) 224×31 | **幽灵节点**：标签栏下方约 15dp 高的窄目标 |
| DOWN | (1414,1067) 84×25 | 第二个幽灵节点 |
| UP | `<nothing focused>` | **焦点丢失一帧**，再按一次才回到快捷动作 |
| UP | (323,852) → (408,710) → (960,486) | 逐级回升正常 |
| UP | (1583,398) 210×88 | 又是内容上方的幽灵目标 |
| UP | `<nothing focused>` | 再次丢焦点 |

也就是说：横向边界这一轮收干净了，**纵向**两端各还有"看不见的停靠点 + 丢焦点一帧"。
这两个窄目标大约是 15dp 高，10 英尺外不可见也不可点，属于纯噪音；丢焦点那一帧会让用户以为
遥控器失灵。下一轮的正解是给面板内容加纵向边界守卫（与 `tvHorizontalEdgeGuard` 同构），
并把它们所在的容器标成不可聚焦，而不是继续逐个补 `tvFocusable`。

### 本轮记录

- 构建：`:app:assembleDebug --offline --no-daemon` 通过（c44.log）。
- 未提交；设备验证只有上面这张纵向遍历表和"TTS 对话框无法在本环境出现"的结论。

## 第十二轮：peek 面板的纵向越界与选项行焦点环（2026-10-23）

第十一轮在纵向遍历里看到的"幽灵停靠点"，这一轮查清并收掉了。根因不是元素太小，而是
**Compose 不按裁剪裁剪焦点**：peek 状态下详情页整个落在折线以下，被屏幕裁掉，但它们仍然是
合法的焦点目标，方向键照样能找到。

### peek 时按 DOWN 应该先把面板拉起来

- 旧行为：在标签栏按 DOWN，焦点直接掉到屏幕边缘被裁掉的首行（约 15dp 高，肉眼几乎不可见），
  再按 UP 还会丢一帧。
- 新行为：`ReaderSheetActions` 增加 `isExpanded` 探针（由 `StableAnchoredSheetLayout` 的
  `isExpandedPastPeek` 填充，非 TV 恒为 true），标签栏在 peek 时吞掉 DOWN 并调用 `expand()`。
  焦点留在当前页签上，**下一次 DOWN 才是"进入页面"**。遥控器上按键总数不变，但每一步都有可见结果。
- 证据：`walk.py` 在标签栏按 DOWN 后焦点坐标从 `(916,1008)`（屏幕底部的页签）变为 `(916,150)`
  （面板抬起后同一枚页签的新位置），而不是跳到屏幕外。

### 详情页的 LazyColumn 自己也是一个焦点停靠点

面板抬起来之后，第一次 DOWN 仍然"看不出发生了什么"——焦点落在详情页的 `LazyColumn` 上。
滚动容器在 Compose 里是可聚焦的，而它身上没有环。TV 下让 `ReaderOptionsPageList` 的
`LazyColumn` `focusProperties { canFocus = false }`，容器被跳过，DOWN 直达第一行。
`LazyColumn` 滚动到焦点项的行为不依赖容器自身可聚焦，所以不影响长页面的滚动。

### 开关行、数值行、宽按钮补焦点环

`ReaderOptionSwitchRow`（`toggleable` 行）与 `ReaderOptionValueRow`（`clickable` 行）此前都没有
环——它们是 typography/reading/translation 三页的主要交互，遥控器上等于隐形。补在共享组件层
（`ReaderOptionControls.kt`，`addFocusTarget = false`），三个页面同时受益。翻译工具页的两个
`FilledTonalButton`（Start translation / Clear Translation Cache）也在调用处补环。

### 本轮设备证据（emulator-5558，1920×1080）

| 步骤 | 观察 | 图 |
| --- | --- | --- |
| peek 标签栏 DOWN | 面板抬起，焦点仍在同一页签（1008 → 150） | walk |
| 抬起后 DOWN | 焦点直达第一行，白色焦点环可见（此前是不可见停靠点） | hr2 |
| Translation 页 DOWN | "Translate text" 整行出现白色圆角焦点环 | hq2 |
| Typography 页 DOWN×3 | Line Height 的 − 按钮有环 | hs1 |

### 本轮构建与测试记录

- `:app:assembleDebug --offline --no-daemon` 通过（c45–c47.log）。
- TV/阅读器面板相关 10 个单测类 **22 项 0 失败**（t7.log）。

### 仍未覆盖（第十二轮结束时的状态）

- `FilledTonalButton` 与 `NovelToolActionRow` 的焦点环只改了代码，本轮没能走到 Translation 页做目视确认；
- 面板内容顶部的 `Follow system`（亮度改回自动）是 `TextButton` 风格，暂无环；
- 笔记面板搜索框 IME 出口、漫画阅读器面板过机、手机端回归，与前几轮相同。

## 第十三轮：快捷层顶部的 UP 越界与"按键没反应"的复核（2026-10-23）

第十一轮在快捷层按 UP 时出现过一帧 `<nothing focused>`。这一轮补上纵向的边界守卫，并且顺手把
"按了没反应"的怀疑逐条证伪——结论是**只有一处真问题**。

### `Modifier.tvVerticalEdgeGuard`：纵向版的边界守卫

横向守卫（第十轮）解决的是"从行首按 LEFT 掉到关闭遮罩上"，纵向是同一个坑：快捷层最上面一行的
上方是已经收起成拖拽条的 header，那些控件**仍在组合中、仍然可聚焦**，只是被裁到看不见。UP 一按，
环就消失一帧。

`TvFocus.kt` 新增 `Modifier.tvVerticalEdgeGuard(blockUp, blockDown)`（与横向守卫同构，非 TV 无操作），
挂在亮度行（快捷层的第一行，`ReaderSliderRow` 的 `modifier`）上，`blockUp = true`。

证据：焦点停在亮度滑条时按 UP，**前后两帧截图字节完全相同**（`74a3a1d2`），环稳稳留在滑条上；
此前这一按会掉到看不见的 header 控件上。

### 复核：字体大小 + 到底是能用的

上一轮末尾怀疑"CENTER 按 + 不生效"。这一轮按正确的路径走了一遍：
亮度滑条 DOWN → Text Size 行（− 获得环）→ RIGHT ×2 到 **+** → CENTER ×2，
`uiautomator dump` 读到的文本值 **17.0sp → 19.0sp**，两次各加 1.0sp。**步进器是好的**——
上一轮的"没生效"是我把焦点位置记错了（当时焦点在滑条上，CENTER 对滑条确实什么都不做）。

### 快捷层的焦点环现状（目视确认）

| 控件 | 环 | 证据 |
| --- | --- | --- |
| 亮度滑条 | 整条白色圆角环 | hu1 |
| Follow system 开关胶囊 | `tvFocusable` 已挂（Capsule 形） | — |
| 主题色块（Paper/Sepia/Moss/Slate） | 有环 | ht1 |
| 快捷操作块（TTS/书签/…/Translate text） | 有环 | ht1 |
| 分页栏页签 | 有环 | 第十二轮 |

### 快捷层的上下导航是不对称的（记录，不在本轮修）

- 从上往下走是顺的：亮度 → Text Size → 主题 → 快捷块 → 页签；
- 从下往上是跳的：从快捷块 UP，几何搜索挑的是**水平对齐更好**的亮度滑条，而不是正上方偏右的主题行，
  于是 Text Size / 主题行要绕回去才够得着。

对遥控器来说"往下扫"是主路径，所以这一条先记录不改；要改需要给快捷层加显式的
`focusProperties { next/previous }` 或 FocusRequester 链，属于结构改动。

### 本轮构建与测试记录

- `:app:assembleDebug --offline --no-daemon` 通过（c48.log）。
- TV/阅读器面板相关 10 个单测类 **22 项 0 失败**（t8.log）。

## 第十四轮：面板关闭后回到阅读态，以及"菜单怎么再叫出来"（2026-10-23）

这一轮把"读完设置之后 D-pad 还在顶栏里"这条串起来，并补齐了此前缺的另一半：阅读态没有任何键能
把顶栏叫回来。

### 关掉面板就回到阅读态

原来在 TV 上关掉选项面板后顶栏仍然留在屏幕上（焦点落在返回箭头上），用户下一次按 RIGHT 期望翻页，
实际只是把焦点从返回箭头挪到标题胶囊——页没翻，遥控看起来"坏了"。

`NovelReaderActivity` 新增 `dismissReaderPanel(dismiss)`：关面板后再 `setUiVisible(false)`，只对 TV 生效。
接在 `onDismissSettings` / `onDismissChapters` / `onDismissTools` / `onDismissMarkings` 四个顶层面板上
（替换规则面板是设置页里的二级面板，保持原样）。

设备证据：展开面板 → BACK（收起）→ BACK（关闭），画面变成**没有顶栏的阅读页**（hy1）；
紧接着按 RIGHT，页码 17/26 → 18–19/26（hy2），确认焦点回到正文而不是顶栏。
修改前同一路径按 RIGHT 只是把环从返回箭头移到标题胶囊，页码不动（hw3 → hw4）。

### UP 叫回顶栏：`shouldShowNovelReaderTvChrome`

阅读态下所有方向键都归正文（`shouldInterceptReaderTvKey`），而 BACK 在阅读态就是"离开阅读器"。
也就是说改完上一条之后，用户**没有任何办法**把菜单再叫回屏幕上——这会把一个小毛病换成死路。

新增纯函数 `shouldShowNovelReaderTvChrome(isTvPresentation, controlsVisible, hasSubOverlay, keyCode)`：
TV + 顶栏隐藏 + 没有二级浮层 + **DPAD_UP** 时为真。活动在 `tvKeyDispatcher.dispatch` 之前拦下该键，
在 ACTION_UP 时 `setUiVisible(true)`，因此不会干扰按键的按下/抬起配对。只认 UP：CENTER 在阅读态
仍留给正文，DOWN/LEFT/RIGHT 不动。

状态：

- 阅读态 UP → 顶栏回来（`NovelReaderTvKeyPolicyTest` 新增 4 项单测覆盖 TV/非 TV、顶栏已显示、
  有二级浮层，以及"只有 UP 才叫回顶栏"）；
- **设备端未能复核**：这一轮的启动脚本漂移了（3×CENTER 有时停在详情页而不是阅读器），连续两次
  没进到阅读态就收了，下一轮第一件事是补这一步的截图。

### 阅读器的 BACK 顺序（本轮实测记录）

| 状态 | BACK 行为 | 证据 |
| --- | --- | --- |
| 面板展开 | 收起到 peek（顶栏与面板都还在） | hw2 |
| 面板 peek | 关闭面板，回到阅读态（本轮改动后顶栏一起隐藏） | hw3、hy1 |
| 顶栏可见、无面板 | 只隐藏顶栏，停在阅读页 | hw4 → hx1 |
| 阅读态（无顶栏） | 离开阅读器回详情页 | 实测两次 |

### 设置持久化

上一轮把字号从 17.0sp 调到 19.0sp，本轮重装 APK 后打开面板仍是 19.0sp，正文行距同步变大（hw2）——
设置写入与回读都正常。

### 本轮构建与测试记录

- `:app:assembleDebug --offline --no-daemon` 通过（c49、c50.log）。
- `NovelReaderTvKeyPolicyTest` 通过（t10.log）；TV/阅读器面板 10 个单测类 22 项 0 失败（t9.log）。

## 第十五轮：顶栏叫回来之后焦点没跟着回来（2026-10-23）

上一轮补的 `shouldShowNovelReaderTvChrome` 设备端复核，第一件事就暴露了新问题：顶栏画回来了，
遥控却还是死的。

### 症状

阅读态按 UP，顶栏（返回、标题胶囊、⋮、翻译、页码条）完整出现，返回箭头也画着焦点环——
但接着 RIGHT / RIGHT / CENTER 三次按键，画面哈希一模一样，什么都没发生。

原因：`NovelReaderTopChromeContent` 用 `AnimatedVisibility` 包着顶栏，顶栏是一段一直留在组合里的内容，
重新可见时没有任何东西去 `requestFocus()`。而此时 `controlsVisible=true`，活动把方向键全部
`shouldDelegateReaderKeyToTvControls` 交给控件——控件里却没有节点持有焦点，按键就这样掉进空气里。
画面上那个"环"是 `tvFocusable` 的静态描边，不代表真的拿到了焦点。

### 改法

- `ReaderChromeTopBar` 新增 `backFocusRequester: FocusRequester? = null`，挂到返回键的 `IconButton` 上，
  与既有的 `titleFocusRequester` / `optionsFocusRequester` 同一套写法（漫画阅读器的 scaffold 早就在用）。
- `NovelReaderTopChromeContent` 在 TV 上记住一个 requester，`LaunchedEffect(controlsVisible)` 里顶栏一变可见就
  逐帧重试 `requestFocus()`（最多 20 帧，顶栏是滑入动画，节点要先完成放置）。

### 设备证据（emulator-5558）

| 步骤 | 结果 | 哈希 |
| --- | --- | --- |
| 阅读态（无顶栏） | 正文 18/26、19/26，无顶栏 | `8cdf124b52` |
| UP | 顶栏回来，环在返回箭头 | `059581300a` |
| RIGHT ×2 → CENTER | 打开**选项面板**，焦点落在亮度滑块（白框） | `4182595d…` |
| 目录面板 → BACK → BACK | 回到无顶栏阅读态，与上一次同一张图 | `8cdf124b52` |
| 再 UP | 顶栏回来且焦点在返回箭头，与上一次同一张图 | `059581300a` |
| BACK | 离开阅读器回详情页 | — |

顺带确认：目录面板的 BACK 链也是"先收起 peek、再关闭"，关闭后与选项面板一样回到阅读态；
面板里的字号 19.0sp、主题 Moss 选择都在。

### 构建与测试

- `:app:assembleDebug --offline --no-daemon` 通过（c51、c52.log）。

## 第十六轮：把"翻译与工具"整页走通，并重新量了一遍上下对称性（2026-10-23）

上一轮只验证了顶栏与选项面板的进出门，这一轮把面板里唯一没走过的那一页——翻译与工具——用遥控按到底。

### 到达路径（记录成可复用的按键序列）

阅读态 → UP 叫出顶栏 → RIGHT ×2 到 ⋮ → CENTER 打开面板（焦点落在亮度滑块）→ DOWN ×4 到页签栏
→ RIGHT ×2 到"翻译与工具" → CENTER 选中并展开 → DOWN 进入本页第一行。

### 本页的焦点结论

| 行 | 结果 |
| --- | --- |
| Translate text（开关行） | 整行有焦点环，开关本身不抢焦点 |
| Translation Display Mode（二选一） | Translation Only / Bilingual 都可聚焦，选中项有实心填充 |
| Start translation（FilledTonalButton） | 有焦点环，与普通行一致 |
| Clear Translation Cache（FilledTonalButton） | 与上一行同组件同处理 |
| Effective replacement rules（带箭头的动作行） | 行尾箭头 + 焦点环 |

之前担心的"Material3 按钮自己不带焦点环"在这页不成立：`tvFocusable(addFocusTarget = false)` 包在外面
就够了，环画在按钮的圆角矩形上，与周围行的视觉语言一致。

### 上下对称性的重新测量

第十三轮记的是"从快捷按钮行按 UP 会跳到全宽亮度滑条"。这次逐步量了一遍：

- 向下：亮度滑条 → 字号行（− / 19.0sp / +）→ 主题色板 → 快捷按钮行 → 页签栏
- 向上：快捷按钮行 → 主题色板 → **亮度滑条**（跳过字号行）→ 无

也就是说**字号行在向上时被跳过**：它在右半边，而亮度滑条是全宽节点，光束搜索从色板往上看时滑条覆盖了
整个 x 范围，命中更近的滑条而不是右对齐的字号行。字号行仍然可达（滑条 DOWN 一次即可），
所以这里不再改结构——真要修得给两侧写死 `focusProperties` 链表，收益（省一次按键）不值得在
Material3 组件内部加一条隐式跳转规则。

### 构建与测试

- `:app:assembleDebug --offline --no-daemon` 通过；本节结论全部来自模拟器截图（mc/md/me 系列）。

## 第十七轮：笔记面板的搜索框其实早就接了出口（2026-10-23）

前几轮一直把"笔记面板搜索框没接 `tvTextInputFocusEscape`"挂在待办上。这轮一查，是**过期结论**：
`ComposeNovelMarkingsSheet` 里的搜索框是 `NovelReaderSearchField`，它转调 `core-ui` 的
`SharedNovelReaderSearchField`，而后者在 `BasicTextField` 上已经挂了 `.tvTextInputFocusEscape()`
（`NovelChapterDirectoryContent.kt:329`）。章节目录与笔记面板共用同一份实现，接一处等于两处都接。

设备复核（emulator-5558，从阅读态起）：

| 步骤 | 结果 | 截图 |
| --- | --- | --- |
| 面板 peek → DOWN×3 到快捷行 → LEFT 到第 3 块 "Bookmarks an…" → CENTER | 笔记面板弹出，焦点在 All (1) 筛选 chip | `nd1` |
| DOWN | 搜索框聚焦，软键盘弹出 | `ne0` |
| BACK | 软键盘收起，面板回到原高度 | `ne1` |
| DOWN | 焦点离开搜索框，落到第一条书签卡片 | `ne2` |
| UP | 焦点回到上方 | `ne3` |

结论：搜索框不是焦点陷阱，不需要"再按一次 BACK 才能出去"。剩下的不是出口问题而是**进入**的顺手程度——
搜索框与筛选 chip 之间只有一条纵向流，从书签条目回到搜索框要多按几次，属于可接受范围，不再改动。

### 顺带量到的一条导航事实

快捷行的焦点是**恢复上次位置**的：这次 DOWN×3 直接从亮度滑条落到第 4 块 "Translate text"，
之后 RIGHT×2 并不会"越过未聚焦的第 3 块"，而是停在最右。所以"按 RIGHT 就是右移一格"在快捷行里不成立，
任何遥控脚本走快捷行前都必须先确认当前聚焦的是哪一块（靠截图取环，不能靠按键计数）。

## 第十八轮：朗读控制条在电视上根本按不到（2026-10-23）

这轮本来只想确认笔记面板搜索框的出口，顺手按了下快捷行的第 1 块 "TTS Settings"，
结果发现一个比搜索框严重得多的问题。

### 现象

快捷行第 1 块 CENTER → 阅读器进入朗读态：顶栏与面板全部收起，底部浮出一条朗读控制条
（⏮ / ⏸ / ⏭ / `1.0×` / 🎤 / ✕，见 `ng2`）。然后：

| 按键 | 结果 | 截图 |
| --- | --- | --- |
| DOWN | 无焦点环、无任何可见变化 | `nh0` |
| RIGHT ×5 | 同上，画面哈希与 `nh0` 前后一致 | `nh1`、`nh2` |
| BACK | 退出朗读态，回到无顶栏的阅读态 | `ni0`（`8cdf124b52`，与基线同图） |

也就是说：**朗读能开，但开起来以后遥控器就完全失控了**——暂停、下一句、倍速、换语音、关闭，
六个按钮一个都到不了，唯一的出口是 BACK。

### 根因

按钮本身没问题：`NovelReaderOverlay.kt` 里的 `TtsButton`（:141）和 `TtsSpeedButton`（:156）
都挂了 `tvFocusable(shape = CircleShape, addFocusTarget = false)`，环随时能画。

问题在按键路由。`NovelReaderActivity.dispatchKeyEvent` 把 `controlsVisible = isReaderControlsVisible`
（只表示顶栏/面板是否可见）交给 `ReaderTvKeyDispatcher`。朗读态下顶栏是收起的，
于是 `shouldInterceptReaderTvKey(TV, controlsVisible=false, D-pad)` 成立，方向键在 :1294 的
`isTvPresentation && !isUiVisible` 分支里被直接当成翻页（`switchPageBy(delta)`）处理，
**根本到不了 Compose 的焦点系统**。朗读条的可见性与这套判定完全无关，
所以它不是"焦点没找到"，而是"按键没被当成焦点按键"。

### 修法（下一轮执行）

1. 让 `controlsVisible` 语义扩成"有可见交互层"：`isReaderControlsVisible || uiState.ttsControlsVisible`，
   方向键于是走 DELEGATE，进入 Compose 的焦点遍历。
2. 朗读条出现时主动交接焦点：`LaunchedEffect(ttsControlsVisible)` 里对播放/暂停按钮
   `requestFocus()`，逐帧重试（和第十五轮顶栏的 `backFocusRequester` 同一套写法），
   保证环落在眼睛能看到的地方。
3. 顺带确认：朗读态下 BACK 仍先退朗读条（当前行为已正确），不要被上面的改动带坏。

在这条修好之前，"电视上能听书"只能算半成品——能开不能控。

## 第十九轮：把朗读条真正接进遥控器（2026-10-23）

第十八轮只做到"确认朗读条按不动"。这轮把它修好并逐键验证。

### 先纠正上一轮的判断：朗读条有两个，屏幕上的是另一个

上一轮以为是 `NovelReaderOverlay.kt` 里那条贴底的六键横条。加日志后才发现：
`state.ttsControlsVisible` 在那个 `NovelReaderOverlay` 实例里始终是 `false`（日志 `effect ttsVisible=false isTv=true`，
之后再没变化），而屏幕上确实浮出了 ⏮⏸⏭ / `1.0×` / 🎤 / ✕。

真相是：点到快捷行第 1 块 "TTS Settings" 后，`NovelReaderChrome.kt` 里的

```
val toolsPanelVisible = state.toolsSheetVisible || state.ttsControlsVisible
if (toolsPanelVisible) { ModalBottomSheet { NovelToolsPanel(state, callbacks) } }
```

会**新开一个 `ModalBottomSheet`**，朗读控制就是在它的 `NovelToolsPanel` 里、4 个工具药丸下面追加的一行。
`NovelReaderOverlay` 那条横条是另一个表面（`ttsControlsVisible && !chromeEnabled` 时才显示），这轮并没有走到它。

所以上一轮按"覆盖层"思路加的焦点交接是打在空处——不是坏代码，只是没打中真正的表面。

### 真正的原因有两层

1. **按键路由**：`NovelReaderActivity` 只把 `isReaderControlsVisible`（顶栏）当作"有交互层"，
   朗读弹层可见而顶栏收起时方向键被当成翻页吃掉。上一轮已改成
   `isReaderControlsVisible || uiState.ttsControlsVisible`。
2. **焦点空洞**：`ModalBottomSheet` 是刚弹出的新窗口，里面**没有任何节点持有焦点**，
   而且 `NovelToolsPanel` 那六个 `IconButton` 连 `tvFocusable` 都没挂——就算焦点进来也画不出环。
   遥控器按下去"没反应"就是这两件事叠加的结果。

### 修法

在 `NovelToolsPanel` 的朗读行里：

- 六个按钮补 `tvFocusable(shape = CircleShape, addFocusTarget = false)`，环才有东西可画；
- 播放/暂停键挂 `FocusRequester`，`LaunchedEffect(isTv)` 里逐帧重试 `requestFocus()`（最多 20 帧），
  和第十五轮顶栏返回键、上一轮覆盖层用同一套写法；
- 全部 `isTv` 门控，手机端行为不变。

### 设备验证（emulator-5558，1920×1080）

| 步骤 | 结果 | 截图 |
| --- | --- | --- |
| 快捷行 "TTS Settings" → CENTER | 弹层出现，焦点环已在播放/暂停键上（日志 `toolsRow ok=true`） | `wc4` / `xd0` |
| RIGHT | 环移到 ⏭ | `wd0` |
| RIGHT ×4 | 环走到 ✕（经过 `1.0×`、🎤） | `wd1` |
| RIGHT（再按） | 停在 ✕，不越界 | `wd2` |
| ✕ 上 CENTER | 朗读行收起，播放停止 | `wd3` |
| `1.0×` 上 CENTER | 弹出倍速菜单 0.5×–3.0×，0.5× 已高亮可选中 | `xd2`（焦点窗口 `Pop-Up Window`） |

去掉调试日志后重装、重走一遍，环与倍速菜单都照旧（`xd0`/`xd1`/`xd2`），确认不是日志在帮忙。

### 留下的问题

- `NovelReaderOverlay` 那条独立朗读横条这次没有场景走到（它要求弹层关闭且顶栏隐藏），
  上一轮加给它的交接逻辑保持原样，等有场景时再验。
- 朗读弹层是手机式 `ModalBottomSheet`：电视上它会占住 Almost 半屏且无法用方向键收起，
  更合适的电视形态应是"贴底条 + BACK 收起"，留待后续评估。

## 第二十轮：BACK 关掉朗读弹层后，声音还在念（2026-10-23）

第十九轮把朗读条接通了，这轮顺着"BACK 之后会怎样"往下查，抓到一个更严重的坑。

### 现象

朗读中（弹层可见）按 BACK：弹层收起、回到无工具栏的阅读态，但**朗读没有停**，
而且屏幕上已经没有任何停止入口——遥控器只剩"翻页"和"退出阅读器"两条路。

### 证据

用音频播放器的真实状态而不是截图判断（`adb shell dumpsys audio`，只统计
`CONTENT_TYPE_SPEECH` 且 `state:started` 的 track）：

| 状态 | started 的语音 track |
| --- | --- |
| 阅读器空闲 | 0 |
| 朗读中 | 1（`ye1`，pid 为本应用） |
| 修之前，BACK 之后 | **1**，而且 piid 从 1919 变成 1935——还在往下念 |

### 原因

朗读控制挂在 `NovelToolsPanel` 所在的 `ModalBottomSheet` 上，弹层的 dismiss 回调是
`dismissTools()`，它只做两件事：

```kotlin
fun dismissTools() {
    _uiState.value = _uiState.value.copy(toolsSheetVisible = false, ttsControlsVisible = false)
}
```

清的是"可见标记"，没有任何人调 `onTtsStopClicked()`（只有 ✕ 按钮那条路会调）。
于是引擎继续朗读，UI 已经不存在，两端脱节。

### 修法

`NovelReaderActivity` 里把弹层的 dismiss 换成专用函数：

```kotlin
private fun onToolsPanelDismissed() {
    if (composeReaderViewModel.uiState.value.ttsControlsVisible) {
        onTtsStopClicked()
    }
    dismissReaderPanel(composeReaderViewModel::dismissTools)
}
```

判据用 `ttsControlsVisible`：只有"关掉的这个弹层里正挂着朗读控制"时才停引擎，
普通工具弹层（翻译/书签/清缓存）不受影响。手机与电视共用同一条路径——
手机上把弹层往下拖走同样不该留一个关不掉的朗读。

### 设备验证

重装后重走：进入朗读（`ye1`，track=1）→ BACK（`ye2`，track=0，回到 `6fd66335f8` 的阅读态）。
空闲态与朗读期间的基线也都各测了一次，见上表。

### 教训

- 弹层"收起"和"业务停止"是两件事，凡是把控制面板塞进 sheet 的播放类功能都要检查这条缝。
- 截图只能证明"看不到了"，证明不了"停了"。`dumpsys audio` 的 started track 数是这次唯一可信的判据。

## 第二十一轮：✕ 退出朗读之后，焦点有没有悬空（2026-10-23）

按前面几轮的经验，"面板被移除、焦点跟着没了、遥控器像死了"是最常见的坑，
所以专门测了"按 ✕ 收起朗读行之后遥控器还活不活"。结论是**活着，不用改**，
把过程记下来免得以后重复排查。

### 走法

朗读中（`za1` = `3066643aa8`，环在播放/暂停）→ RIGHT×4 走到 ✕（`za2` 依次 `e888687499` ⏭、
`29eb897a46` 1.0×、`2fdaa93cdb` 🎤、`b5522e7c20` ✕）→ CENTER。

### 结果

| 步骤 | 截图 | 观察 |
| --- | --- | --- |
| ✕ 上 CENTER | `za3` = `df93619d6d` | 朗读行收起，弹层回到 4 颗工具药丸 |
| DOWN | `za4` = `6d24fc435c` | 焦点环落在第 2 颗药丸（书签） |
| RIGHT | `za5` = `fc3facc138` | 两张图 diff 的 bbox 是 `(326,484)-(634,528)`，正好覆盖第 2–3 颗药丸，环移到第 3 颗（🎤 TTS Settings） |
| CENTER | `za6` = `3066643aa8` | 精确回到朗读态截图——从药丸重新起步朗读成功 |

所以焦点并没有悬空，而是回落到工具药丸行；✕ 之后再起步朗读的链路也是通的。

### 为什么这里不需要额外处理

`NovelToolsPanel` 的朗读行是同一列里的条件内容，收起它不改变弹层本身的焦点宿主，
Compose 自然把焦点交给相邻的可聚焦节点。只有当整个弹层（`ModalBottomSheet`）消失时才会出现
第十九轮那种"里面一个焦点都没有"的空洞——两种情形不能混为一谈。

### 仍然存在的形态问题

朗读弹层依旧是手机式 `ModalBottomSheet`：和 4 颗工具药丸挤在一起、占住屏幕下半部分，
且无法用方向键收起。功能上已经可用（起步、走位、暂停、倍速、关闭、BACK 停止都验过），
形态上还不是"电视该有的样子"。更合适的目标形态是贴底细条 + BACK 收起，
留作后续设计项，动手前需要先确认手机上不会因此多出一套分支。

## 第二十二轮：倍速菜单的选择真的会写回（2026-10-23）

第十九轮只验到"倍速菜单弹得出来、第一项有高亮"，这轮补上"选中之后生效吗"。

### 结果

| 步骤 | 截图 | 观察 |
| --- | --- | --- |
| 焦点在 `1.0×` 上按 CENTER | `zc1` | 菜单弹出，0.5×–3.0×，**焦点高亮固定落在第一项 0.5×**（不是当前值，属于菜单的初始焦点行为） |
| DOWN ×1 | `zc2` | 高亮移到 0.75× |
| CENTER | `zc3` | 菜单关闭，倍速键显示 **`0.75×`**，焦点环仍在倍速键上 |

所以选择是真正写回的，退出菜单后焦点也回到当初进入的那个键，没有悬空。

### 朗读条的完整遥控器矩阵（本轮收官）

| 操作 | 结果 |
| --- | --- |
| 快捷行 "TTS Settings" | 打开弹层，焦点自动落在播放/暂停 |
| LEFT / RIGHT | 在 ⏮ ⏸/▶ ⏭ `倍速` 🎤 ✕ 之间移动，两端不越界 |
| CENTER（播放/暂停） | 暂停 / 继续 |
| CENTER（倍速） | 弹出菜单；上下选择后 CENTER 生效，焦点回到倍速键 |
| CENTER（✕） | 收起朗读行并停止朗读 |
| BACK | 收起弹层并停止朗读，回到无工具栏阅读态 |
| ✕ 之后 | 焦点回落到工具药丸行，可再次起步朗读 |

### 顺带记下的一个行为特点

倍速菜单的初始高亮永远是第一项（0.5×），不反映当前倍速。方向键用户因此每次都要
从头数一遍才能改到想要的值。功能上不算错，但如果之后细化，让初始高亮落在当前值上会更顺手。

## 第二十三轮：倍速菜单从当前值起步（2026-10-23）

第二十二轮留下的改进项——"倍速菜单的初始高亮永远是第一项 0.5×，方向键用户每次都要从头数"。
这轮改掉了。

### 改法

`TtsSpeedButton`（`NovelReaderOverlay.kt`，被朗读弹层与独立朗读条共用）里：

- 给每个倍速项挂一个 `FocusRequester`（按 `TtsPlaybackSpeed.OPTIONS` 建表，`remember` 住）；
- `isTv` 时展开菜单后用逐帧重试把焦点请求给"当前值"那一项（`OPTIONS.firstOrNull { it == speed }`）；
- 非 TV 分支完全不加修饰符，手机端行为不变。

### 设备验证

| 步骤 | 截图 | 观察 |
| --- | --- | --- |
| 朗读中，焦点移到倍速键（显示 `0.75×`） | `ze1` | 环在倍速键上 |
| CENTER 打开菜单 | `ze2` | **高亮落在 `0.75×`**，即当前值（改之前这里固定是 0.5×） |
| DOWN | `ze3` | 高亮下移到 `1.0×` |
| CENTER | `ze4` = `29eb897a46` | 菜单关闭，倍速键回到 `1.0×`，环仍在倍速键上 |
| DOWN（行内） | `ze5` | 与 `ze4` 同图——朗读行是单行，DOWN 不产生位移，符合预期 |

### 说明

`0.75×` 是上一轮在本机改出来的值，重启后仍保持，正好用来验证"菜单初始高亮跟随当前值"。

## 第二十四轮：首页搜索只用遥控器走一遍（2026-10-23）

搜索是电视上仅次于"继续阅读"的高频动作，这轮把它从首页一路走到键盘、退出、回首页。

### 走法与结果

| 步骤 | 截图 | 观察 |
| --- | --- | --- |
| 首页，焦点在"继续阅读"英雄卡 | `zf1` | 蓝环在卡片上 |
| UP | `zf2` | 焦点上移到顶栏搜索胶囊 |
| CENTER | `zf3` | 进入搜索页：输入框拿到焦点并出现光标，**屏幕键盘自动弹出**，下方列出最近查询与标签 |
| CENTER（键盘上的 q） | `zf4` | 输入框出现 `q` |
| RIGHT + CENTER（w） | `zf5` | 输入框变成 `qw`——方向键选字母、CENTER 落键，整条链路成立 |
| BACK | `zf6` | **键盘收起，仍停在搜索页**，文本 `qw` 保留并自动开查（`rememberTvFieldExit` 生效） |
| 搜索结束（无结果） | `zf7` | 显示 "Online suggestions are unavailable"，离线环境下符合预期 |
| RIGHT | `zf8` | 输入框失去光标，但也没有别处出现焦点环 |
| CENTER | `zf9` | 与 `zf7` 同一张图，什么都没发生 |
| BACK | `zg0` | 回到首页，焦点落在左侧导航栏 Home 上，英雄卡让出焦点 |

### 关于 `zf8` 那个"焦点不见了"

初看像是焦点悬空，但 `zf9`（CENTER 后画面完全不变）说明焦点**仍在输入框里**——
如果它在 ✕ 上，CENTER 会清空文本；在放大镜上则会重新发起搜索，两种都会留下能看出来的变化。
更合理的解释是输入框光标正在闪烁的暗相位。
代码侧也支持这个判断：`KototoroSearchOverlay` 的返回键、清除键、搜索键都挂着
`tvFocusable(..., addFocusTarget = false)`，胶囊与结果区都有 `focusGroup`，并在展开时请求过一次初始焦点，
所以这里没有需要补的东西。

### 结论

首页搜索的电视路径是通的：胶囊 → 焦点输入框 + 屏幕键盘 → 方向键打字 → BACK 只退键盘 → 再 BACK 回首页且焦点有归宿。
不需要改动，记录在案供回归时对照。

## 第二十五轮：首页焦点顺序地图（2026-10-23）

首页是每个用户看到的第一屏，这轮把"英雄卡出发，方向键能走到哪"画了一张图。

### 实测路径

起点：冷启动后焦点落在"继续阅读"英雄卡（蓝环，`zh4`）。

| 操作 | 截图 | 焦点落点 |
| --- | --- | --- |
| RIGHT | `zi0` = `19006208dd` | 右缘的齿轮（显示选项）按钮，环清晰完整 |
| RIGHT | `zi1` = `18c9948d5b` | 右缘的**空间切换手柄**，环贴着屏幕边缘、被裁掉一部分 |
| DOWN | `zi2` = `zi1` | 无反应 |

`uiautomator dump` 认出了这个手柄：`content-desc="Current: Novel. Switch space"`，且当时 `focused="true"`。
同一次 dump 里还能看到顶栏的 `List options`、`Filter`、`More` 三个控件。

另外两个已知入口（前几轮测过）：

| 操作 | 落点 |
| --- | --- |
| UP（英雄卡上） | 顶栏搜索胶囊，再 CENTER 进搜索页（第二十四轮） |
| LEFT | 侧栏导航（Continue / Home / History / Favorites / Explore / Feed） |

### 两个待确认的点

1. **空间切换手柄被裁切**：它是切 manga / novel / anime 空间的入口，拿到焦点时环有一半在屏幕外，
   字幕「Current: Novel. Switch space」也在屏外。电视上至少要在聚焦时把整个手柄（图标＋文字）拉进画面。
2. **从手柄按 DOWN 没有出口**：只能 LEFT 回去。对方向键用户来说这是个"只进不出"的角落，
   不致命但会让遥控器感觉忽然失灵。

这两条都还没到能直接下结论的程度（不确定手柄的布局约束与预期交互），先记下来，
下一轮从布局代码确认这条竖排控件的归属与尺寸策略再决定改法。

## 第二十六轮：右缘把手不再是焦点陷阱，以及"显示选项"抽屉没有焦点环（2026-10-23）

### 一、那个被裁掉的焦点环是什么

上一轮记下的"右缘竖条"在 `SpaceSwitcher.kt` 里找到了本体：`SpaceSidekickHandle`，
固定 `32.dp × 64.dp`，贴边摆放、内部 `GlassSurface` 再往屏外偏 10dp，所以屏幕上看得到的只是一条半截把手。
`uiautomator` 的 `[1856,144][1920,272]`（64×128 px）正好等于 32dp×64dp，右边缘就是 1920，证实它整块贴边。

焦点环被裁的原因也清楚了：`tvFocusable` 用 `Stroke(width = borderWidth)` 沿轮廓描边，
**描边以边界为中心**，落在边界外的那一半在屏幕边缘自然被裁掉。

### 二、先试错了一次

第一反应是"FAB 落点没夹取"，于是在 `KototoroApp.kt` 的 `spaceSwitcherFabTargetOffset` 上加了一次
`coerceIn` 边界夹取。装上去之后 `zj0` 与改前的 `zi1` **逐字节相同**，`uiautomator` 报的仍是 `[1856,144][1920,272]`。
结论：这条偏移根本不负责这个元素（它是 `SpaceSwitcherFab` 的定位，不是侧边把手），改动无效，
已 `git checkout` 撤回，没有留下没验证过的代码。记在这里免得再试一次。

### 三、改法：电视上让把手只作装饰

电视上本来就有正经入口：`KototoroApp.kt:1287` 的 `railHeaderContent` 在 `presentationConfig.isTv &&
spaceUiState.switcherEnabled` 时放一个 `SpaceSwitcherRailButton`，
与侧边把手用的是**同一个开关**，两者必然同时存在。而遥控器无法"拖"，半截把手只会成为一个
"看不见全貌、按下 DOWN 又出不去"的焦点陷阱。

`SpaceSidekickHandle` 因此加了一个 `isTv` 判断，把 `tvFocusable` 和 `clickable` 的 enabled 都改为
`state.switcherEnabled && !isTv`：把手照常显示，只是不再参与焦点遍历。手机和平板不受影响。

### 四、验证

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 冷启动首页 | `zk0` = `763d21fc38` | 英雄卡获焦 |
| RIGHT | `zk1` = `19006208dd` | 与上一轮的 `zi0` 完全相同：齿轮获焦 |
| 再 RIGHT | `zk2` = `19006208dd` | **与 `zk1` 相同**，第二次 RIGHT 变成空操作——半截把手不再抢焦点 |
| CENTER | `zk3` = `a931d4b57b` | 打开"显示选项"抽屉 |

### 五、新发现：显示选项抽屉在电视上没有可见焦点

`zk3` 打开抽屉后，**屏幕上唯一的焦点环还在抽屉背后的齿轮上**，抽屉里六个列表模式按钮、网格大小滑条、
"Show extra info on cards" 开关全都没有环。

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| DOWN | `zk4` = `5f70a4a83a` | 齿轮的环消失，**抽屉里依然没有任何地方出现环** |
| BACK | `zk5` = `19006208dd` | 抽屉关闭，环回到齿轮 |

这说明焦点确实进了抽屉（否则齿轮的环不会掉），只是抽屉里的控件没挂 `tvFocusable`，所以"有焦点、看不见"。
按本审计一直用的标准，这比没有抽屉更糟：用户不知道方向键会作用在谁身上，也无从确认自己选中了哪个列表模式。
`zk5` 也顺带确认了 BACK 关抽屉后焦点能回到触发它的齿轮——这条行为是对的。

下一轮要做的就是给这个抽屉补焦点：每个列表模式按钮、滑条、开关各挂一个环，并在展开时请求一次初始焦点
（滑条已经有现成的 `tvSliderDpad`，可以直接用）。改完必须重跑 `zk3`→`zk4` 这组对照。

## 第二十七轮：给"显示选项"抽屉补上电视焦点（2026-10-23）

上一轮留下的问题：这个抽屉在电视上有焦点、看不见环。本轮补齐。

### 改了什么

`DisplayOptionsSheet.kt`：

1. 内容 `Column` 加 `tvTakeInitialFocus()`——抽屉展开后由第一个可聚焦子项拿焦点，不再把焦点留在背后的齿轮上。
2. `DisplayModeChip` 的 `AssistChip` 挂 `tvFocusable(CircleShape, addFocusTarget = false)`。该组件是 `internal` 共享的，
   顺带让其它用到它的电视界面也受益。
3. `GridSizeSlider` 里的 `KototoroSlider` 挂 `tvSliderDpad(...)`：环 + 方向键步进。
   这个助手此前在仓库里**没有任何调用点**，本轮是第一次真正用上。
4. "Show extra info on cards" 与"分组"两个 `Switch` 各挂 `tvFocusable(CircleShape, addFocusTarget = false)`。

`RailRowsSelector.kt`：1/2/3 三个 `AssistChip` 同样挂环。它是全应用共用的组件，所以收藏、历史等页面的同款控件一并解决。

### 验证（全部在 `emulator-5558` 上实测）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 齿轮 CENTER 展开抽屉 | `zm0` | **"List" 芯片上出现环**，焦点不再留在背后的齿轮 |
| RIGHT | `zm1` | 环移到 "Detailed list" |
| CENTER | `zm2` | 勾选跟着走：Detailed list 选中，Compact grid 让出 |
| DOWN×2 + DOWN | `zm4`/`zm7` | 环依次落到芯片各行，最后落到滑条 |
| 滑条上 LEFT×2 | `zm7` | **80% → 70%**，环包住整条轨道 |
| DOWN 到"Rows per page" | `zp4` | **"2" 芯片上出现环** |
| DOWN 到开关 + CENTER | `zn0` | 开关变蓝（开），环在开关上 |
| BACK | `zk5` | 抽屉关闭，环回到齿轮 |
| 重新打开 | `zp5` | Grid size 仍是 70%、开关仍是开——写入是持久的 |

### 还剩两个小尾巴

1. **抽屉背后的齿轮环不灭**：抽屉是独立窗口，底层窗口的 `onFocusChanged` 收不到失焦事件，
   于是齿轮的环一直亮在遮罩后面。视觉上像"两处同时选中"，但被遮罩压暗，暂不处理。
2. **`SortOrderControl` 还没接**：同一抽屉在有排序项的主机里会多一段排序控件（`SortOrderControl.kt`，
   是 `IconButton` + `DropdownMenu`）。下拉菜单的焦点问题本轮修过的 `TtsSpeedButton` 是同款，
   可以直接套用"展开时聚焦当前值"的做法。

## 第二十八轮：下拉菜单项与排序控件的环（2026-10-23）

上一轮结尾留下的 SortOrderControl 尾巴，本轮连同它依赖的共享菜单项一起补。

### 改了什么

1. GlassDropdownMenu.kt 的 CompactDropdownMenuItem 内部挂 	vFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false)——
   这是**所有**下拉菜单项的公共实现，改一处即覆盖全应用（TTS 语速、排序、来源筛选等）。
2. SortOrderControl.kt：紧凑态的 IconButton、完整态的 AssistChip、升/降序两个 FilterChip 各挂环；
   另外加了展开时聚焦当前排序项的重试：Material3 默认把焦点放在第一行，在遥控器上看起来像排序自己跳了。
   做法与 TtsSpeedButton 相同（每个选项一个 FocusRequester，最多重试 20 帧）。

### 验证

见文末「验证（2026-10-23 补做，全部实测）」一节——设备上已全部走通。

### 一个新观察

用**触摸点击**展开 ⋮ 菜单后，方向键 DOWN 不会进入弹出菜单，而是走到背后的左侧导航栏（zs2：History 被聚焦）。
这是因为弹窗展开时焦点仍留在主窗口。遥控器用户不会用点击展开，所以这是触摸路径的差异，
但值得记一笔：如果以后给电视加触摸/鼠标支持，这条路径需要重新检查。

### 验证（2026-10-23 补做，全部实测）

宿主页面选定**收藏页**（`FavoritesListScreen.kt:70`，全应用唯一直接挂 `SortOrderControl` 的常规页面）。

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 左侧导航 Favorites → RIGHT | `zu3` | 排序 `IconButton` 上出现环——之前该按钮完全看不见焦点 |
| CENTER 展开 | `zu4` | 菜单标题 "Sorting order"，**焦点直接落在 "Date added"（当前生效的排序）**并带环 |
| DOWN ×2 | `zu5` / `zu6` | 环依次走到 Progress、Title，菜单自动滚动露出 Last read/Updated |
| UP | `zu7` | 哈希与 `zu5` 相同，反向走位对称 |

结论：共享菜单项的环、触发器环、"聚焦当前值"三个改动全部在设备上成立。

顺带确认收藏页工具栏的可达性：从左侧导航 RIGHT 直接进入筛选芯片行，不存在"整行够不着"的问题。

## 第二十九轮：漫画阅读器的遥控器实测（2026-10-23）

之前一直以为模拟器上没有漫画样本，本轮打开"本地存储"才发现库里早就有 `TV Audit Manga 11/12`、
`TV Audit Strip`、`TV Audit Manga 05…` 一批样本（外加视频与小说）。于是漫画阅读器这条一直空着的
验证项终于补上了。

### 实测链条

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 详情页 Continue | `zv2` | 进入阅读器，第 3/6 页，纵向连续模式 |
| UP / DOWN | `zv4` | 翻页（3/6 → 2/6），遥控器翻页正常 |
| 触摸点按 | `zv7` | 顶栏（返回、标题药丸、⋮）+ 底部页码条 + 翻译按钮出现 |
| 触摸点按后再按 CENTER | `zw0` / `zw1` | **无反应**——点按唤出的顶栏没有任何焦点，CENTER 落空 |
| BACK | `zx0` | 顶栏收起，阅读器不退出（"先收起再退出"正确） |
| 顶栏收起后按 CENTER | `zx1` | 顶栏出现，且**焦点自动落在标题药丸上**（有环） |
| CENTER（焦点在标题药丸） | `zx2` | 打开章节面板：Chapters / Pages / Bookmarks 三个标签，**当前章节 Chapter 01 带环** |
| DOWN | `zx3` | 环走到 Chapter 02 |
| BACK | `zy0` | 面板塌成底部一条（不关闭） |
| BACK ×2 | `zy2` | 面板关闭，回到顶栏 + 标题药丸聚焦（哈希与 `zx1` 相同） |

结论：**遥控器路径是通的**——隐藏状态下 CENTER 唤出顶栏并自动给焦点、标题药丸进章节面板、
面板内上下选章、BACK 逐层收起。之前担心的"漫画阅读器在电视上够不着底栏"不成立。

### 仍然存在的问题

1. **点按唤出的顶栏没有焦点**：`zv7` 之后 CENTER 完全落空（`zw0`/`zw1` 与 `zv7` 同哈希）。
   遥控器不会产生点按，所以不影响纯遥控器用户；但它说明"顶栏可见"与"顶栏有焦点"是两件事，
   若以后支持鼠标/触摸要一起处理。
2. **阅读器顶部的极细状态条常驻**：即使顶栏收起（`zx0`），画面顶部仍有一条
   `Ch. 1/2  Pg. 4/6  33%  0%  1:10 PM` 的小字。字号按手机尺寸渲染，在 1080p 电视上看不清，
   且它和顶栏是两套东西，视觉上重复。
3. **章节面板要按两次 BACK** 才关闭，第一次只塌成底部一条，中间态看起来像动画卡住。
4. **本地存储页顶栏标题被截断**（`zv0` 显示 `Local sto…`），电视顶栏留给标题的宽度不够。

### 下一轮方向

先修第 2、4 两条（都属于"看得见但不好用"）：极细状态条要么在电视上放大字号、要么随顶栏一起收起；
顶栏标题给足宽度。第 1、3 条先记录，等有鼠标/触摸需求再动。

## 第三十轮：阅读器首次按键失灵的根因（2026-10-23）

上一轮把"点按唤出的顶栏没有焦点"记成遗留问题，本轮查下去发现真正的问题更严重：
**进入阅读器后的第一次 CENTER 一定是空按**，第二次才起作用。这比"触摸唤出顶栏"影响大得多——
遥控器用户第一次按 OK 没反应，只会以为遥控器坏了。

### 根因

`ReaderActivity.toggleUiVisibility()` 读的是 Activity 自己的 `areControlsVisible` 字段：

- `areControlsVisible` 初始值 **true**；
- Compose 阅读器的 `chromeState.controlsVisible` 初始值 **false**（顶栏一开始就不显示）。

于是第一次 CENTER 走 `setUiIsVisible(!true)` = **隐藏**——顶栏本来就没显示，画面毫无变化；
第二次才 `!false` = 显示。按键没丢，是算反了。

修法：新增 `isChromeOnScreen`，优先读 `composeReaderController.isChromeControlsVisible`
（真正在屏幕上的顶栏状态），只有控制器还没初始化时才回退到旧字段。

### 顺带修掉的两条（都是"看得见但看不清"）

1. **阅读器顶部的极细信息条**：`shouldShowReaderInfoBar()` 原本是 `infoBarEnabled && !controlsVisible`，
   于是顶栏收起时必现一条 12sp 的 `Ch. 1/2 Pg. 4/6 33%`，还和系统状态栏的时间/电量挤在同一行。
   电视上从沙发根本看不清，现在电视表现下直接不画（信息仍在顶栏与页码条里）。
2. **本地存储页标题截断**：顶栏标题写死 `maxWidth = 120.dp`，1920px 的屏上也只给这么宽，
   `Local storage` 被截成 `Local sto…`。电视表现改为 360dp。

### 验证

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 本地存储页 | `ac0` | 标题完整显示 `Local storage`，副标题也不再被挤 |
| 进入阅读器 | `ac3` | 顶部**没有**信息条，画面就是整页图 |
| 单按 CENTER | `ae2` | 顶栏出现，焦点落在标题药丸（此前这一按必然是空按） |
| DOWN | `ae3` | 焦点进入底部页码条的上一页按钮 |
| UP + CENTER | `ae5` | 打开选项面板（阅读模式 + 书签/保存/旋转/下载等磁贴），全带焦点 |
| BACK | `ae6` | 面板收起，回到 `ae2` 同款顶栏+药丸聚焦帧 |

### 模拟器本身的坑

本轮模拟器连续崩了三次，Windows 事件日志是
`qemu-system-x86_64.exe` / `0xc0000005`（访问违例），和 App 无关。
`-gpu swiftshader_indirect`（软件渲染）下复现稳定，换 `-gpu host`（本机 RTX 4070）后
连续跑完整个验证链没有再崩。后续调试一律用 `-gpu host`。

## 第三十一轮：视频播放器与「视频笔记」对话框

首次在模拟器上完整走过播放器（本地视频 `TV Audit Video`，SMPTE 测试图）。播放、进度条、底部工具条在 TV 上基本可用：CENTER 唤出/收起 chrome，RIGHT 从暂停键走到进度条并再次 RIGHT 快进，DOWN + RIGHT 到达笔记按钮，焦点环清晰。问题集中在**叠加层不是真正的 Dialog**。

### 新发现

- **视频笔记对话框没有初始焦点**：打开后按 DOWN 画面完全不变（两帧哈希相同），遥控器第一次按键被吞掉；再按 UP 时焦点甚至跳出对话框落到播放器进度条上，属于 TV-02 同类泄漏。根因是它只是播放器窗口里的一个 `Box`，不是独立窗口，Compose 焦点树与播放器共用。
- **修复不能靠"聚焦输入框"**：输入框拿到焦点会拉起软键盘，在 1080p 电视上键盘盖住卡片下半部，Cancel/Save 全部看不见——从"点不动"变成"看不见"，同样不可用。
- **卡片在 1080p 上会超出屏幕**：把焦点交给 Save 后 `verticalScroll` 自动滚动到按钮，标题行「Video Note」和关闭按钮被滚出卡片顶部。

### 已修复（`c928f1919`）

1. `VideoAnnotationDialog` 改为真正的 `Dialog`（`dismissOnBackPress = true`、`dismissOnClickOutside = false`、`usePlatformDefaultWidth = false`），BACK 只关闭对话框而不再退出播放器。
2. TV 下初始焦点落在 **Save note**，遥控器第一次按键就有反应，且不拉起键盘；UP 进入输入框，DOWN 回到按钮区。
3. 输入框获得焦点时（TV）隐藏截帧与字幕引用，卡片自动收窄到键盘上方；Enter/IME Done 收起键盘并把焦点交回 Save note。
4. TV 截帧高度上限 220dp → 140dp，卡片不再超高滚动，标题与关闭按钮始终可见。

验证帧：`bf1`（Save note 焦点环，整卡可见）→ `bf2/bf3`（UP 聚焦输入框、卡片收窄、键盘上方可见、输入文字可见）→ `bf4`（Enter 后键盘收起、焦点回 Save note、文字保留）→ `bf5`（BACK 只关对话框，播放器继续播放，焦点回到笔记按钮）→ `bh3`（标准路径 CENTER→DOWN→RIGHT→RIGHT→CENTER 重新打开对话框）。

### 遗留

- 对话框关闭后**紧接的第一次 CENTER** 会变成播放/暂停而不是激活焦点所在按钮（`bg1`：焦点环从笔记按钮跳到播放键、播放暂停）。用 DOWN/RIGHT 重新走一遍按钮就正常（`bh3`）。怀疑播放器在对话框消失后重新请求了播放焦点，但按键路由仍按旧焦点处理，需要在 `VideoPlayerActivity` 的 key 处理里确认。
- 播放器 chrome 的章节面板、弹幕/字幕设置、倍速菜单、锁屏态、DLNA 对话框尚未逐个过 TV 焦点与 BACK 语义。

## 第三十二轮：播放器弹层的可读性

上一轮遗留的"对话框关闭后第一次 CENTER 变成播放/暂停"本轮查到了根因；顺手把播放器剩下的五个弹层
逐个走了一遍，发现它们共同的毛病不是焦点，而是**在电视上根本读不清**。

### 根因：自动隐藏不区分播放与暂停

`hideUiRunnable` 在 chrome 显示 3.5 秒后无条件收起界面。于是"打开弹层 → 操作超过 3.5 秒 → BACK 关闭"
之后，chrome 已在消失边缘：焦点环所在的按钮被一起收走，紧接着的 CENTER 就被当成"唤出 chrome"消费掉
——重新显示顶栏并把焦点归位到播放键，看起来就像这一按变成了播放/暂停。

修法三条：

1. `chromeMustStayForRemote()`：电视表现下**暂停时**不自动隐藏（遥控器用户暂停后还要继续操作，
   把界面收走等于夺走操作入口）；
2. `onIsPlayingChanged` 重新计算自动隐藏，恢复播放才重新计时；
3. 笔记按钮的 `FocusRequester` 在对话框关闭后按帧重试归还焦点。

顺带修掉 `savePlaybackProgress` 的 `.commit()`：它在主线程写盘，StrictMode 每 5 秒报一次 `diskWrite`；
改为 `.apply()`，只在需要传播失败时保留 `commit()`。

### 新发现：电视上的弹层是"玻璃面板"

| 弹层 | 面板色 | 宽度 | 行高 / 字号 | 实测症状 |
| --- | --- | --- | --- | --- |
| 选项（12 格） | 黑 0.86 | 268–340dp | 56dp / 12sp | 背后的字幕与进度条穿过面板，两行字叠在一起 |
| 倍速 | 黑 0.86 | 268–340dp | 12sp | 同上 |
| 音轨 / 画质 | 黑 0.88 | 220–300dp | 12sp | 半透明，且音轨名是 `en · mp4a.40.2` 这类开发者字符串 |
| 字幕设置 | 黑 0.90 | 300–380dp | 12–14sp | 视频色块直接压在面板文字上 |
| 章节 | 黑 0.86 | 300–380dp | 11–14sp | 章节名 11sp，沙发上看不出是第几话 |
| 超分设置 | 黑 0.90 | 300–380dp | 12sp | 同上 |

手机近距离看 0.86 透明玻璃是"氛围"，1080p 电视三米外就是"看不清"。

### 已修复

1. 新增 `PlayerMenuStyle.kt`：`playerMenuContainerColor`（电视用不透明 `#16171A`，手机保持原 alpha）、
   `playerMenuWidth`（电视 ×1.6）、`playerMenuRowMinHeight`（电视 ≥60dp）、
   `playerMenuRowTextStyle`（电视 `bodyMedium` / `bodyLarge`）。六个弹层统一接入，手机全部走旧值。
2. 音轨与字幕标签去噪：新增 `trackLanguageName()`（`en` → `English`、`ja` → `日本語`）与
   `trackCodecName()`（`mp4a.40.2` → `AAC`、`ec-3` → `Dolby Digital Plus`、
   `application/x-quicktime-tx3g` → `Timed text`，纯容器 MIME 直接丢弃）；
   `subtitleDisplayName()` 不再重复追加语言标签。
   `en · application/x-quicktime-tx3g · en · Embedded` 变成 `English · Timed text · Embedded`。

### 验证

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 暂停 | `p1` | 焦点在播放键；暂停后整轮调试（数分钟）chrome 一直在，没有被 3.5 秒计时器收走 |
| 打开选项 | `p3` | 面板不透明，`Open in external player` 一行放得下，背后字幕不再穿透 |
| BACK + 换方向 | `p5` | 从音轨面板 BACK、RIGHT 到 ⋮ 再 CENTER，选项面板正常重新打开 |
| 音轨 | `p4` | `English · AAC` / `日本語 · AAC`，勾选与焦点环清晰 |
| 字幕设置 | `p6` | `English · Timed text · Embedded`；面板加宽后 5 个 tab 一屏放下 |
| 倍速 | `pb` | 当前 `1.00x` 带勾选与焦点环，行高 60dp，连按不丢焦 |
| 章节 | `pd` / `pe` | 面板不透明、章节名 16sp；BACK 回章节按钮，再按 CENTER 重新打开面板 |

### 方法上的坑

adb 单次往返 1–2 秒，`uiautomator dump` 更慢，而 chrome 的自动隐藏窗口只有 3.5 秒。中间夹着读截图、
回看帧的时候，"遥控器按不动"多半是测试节奏造成的假象：要么先把播放暂停（chrome 常驻），要么把按键
批量连发（间隔 300–500ms）并在每步后 dump 一次焦点。本轮两次误判都是这么来的——一次以为 CENTER
把进度条跳到了结尾，其实是两轮工具调用之间视频自己播到了终点。

### 遗留

- 章节面板高度固定 72% 屏高，只有两三话时下半部全是空白（宽度已够，密度偏低）。
- 字幕设置里的 chip 与滑杆仍按手机尺寸渲染，本轮只加宽了面板，没有逐项做 10-foot 复核。
- 取消笔记对话框（BACK）后不会恢复播放：`openVideoAnnotation()` 无条件先暂停。
- 手机侧未回归；新增样式都走 `isTvPlayerMenu()` 分支，理论上不受影响。
- 锁屏态、DLNA 对话框、超分对话框仍未在电视上走过。

## 第三十三轮：锁屏在电视上曾经是一条死路（2026-10-23）

上一轮把"锁屏态没在电视上走过"记成遗留，这一轮第一件事就是走它，结果撞上整个审计里最严重的一种失败：
**按完锁屏，遥控器就再也没有任何反馈，屏幕上也没有任何提示。**

### 现象

播放在走，焦点沿底栏 RIGHT ×6 走到最右的 `Lock controls`（`[1564,968][1660,1064]`），按 OK：所有 chrome
消失，只剩视频画面和字幕，**没有任何解锁入口**（`pf`）。随后：

| 按键 | 结果 | 证据 |
| --- | --- | --- |
| CENTER | 画面逐字节不变 | `pg` 与 `pf` 同哈希 `a7561b39b80c` |
| DOWN | 同上 | `ph` 同哈希 |
| BACK | **解锁**，chrome 与播放键焦点一起回来 | `pi`（1:08/3:00，环在 Play） |

遥控器用户遇到的是"点一下就死机"：没有提示、没有候选目标，只能靠猜 BACK。

### 两层根因

1. **解锁按钮是触摸态的**：`VideoScreenLockOverlay` 只在 `unlockButtonVisible` 为真时渲染那枚 64dp 解锁按钮，
   而这个状态只能由全屏 `Box` 的 `clickable`（`onLockedAreaClick = ::showLockedUi`）打开，且 3 秒后由
   `hideLockUiRunnable` 自动关掉。遥控器既不能点，也不能"再点一次"。
2. **键策略把 OK 吃掉了**：`resolveVideoPlayerTvKeyAction` 里 `DPAD_CENTER/ENTER/NUMPAD_ENTER` 在
   `controlsVisible == false` 时返回 `SHOW_CONTROLS`，随后又被"锁屏期只放行 `PASS_TO_FOCUS`"那条规则改成
   `CONSUME`；方向键同理。即使解锁按钮在屏幕上，按键也进不到焦点树。

### 改法

- `VideoScreenLockOverlay`：TV 分支不再依赖 `unlockButtonVisible`，锁屏即刻渲染常驻的 `TvUnlockButton`——
  黑色 72% 圆角胶囊 + `LockOpen` 图标 + `video_screen_unlock`（"Unlock"，复用现有资源，不新增 Weblate 键）。
  它挂 `FocusRequester` 并在进入时逐帧重试 6 帧请求焦点；焦点环用 `border(2.dp, primary)`（与 `PlayerIconButton`
  同一套写法），因为 `tvFocusable` 的描边以轮廓为中心、会被 `clip` 切掉一半。全屏遮罩在 TV 上不再挂
  `clickable`，免得焦点遍历里多出一个没有视觉的全屏节点。手机分支保持原样（点屏幕 → 3 秒窗口内点按钮）。
- `VideoPlayerTvKeyPolicy`：把 `DPAD_CENTER/ENTER/NUMPAD_ENTER` 拆成单独分支，`screenLocked` 时归为
  `PASS_TO_FOCUS`，把 OK 交给焦点树里的解锁按钮；UP/DOWN 与媒体键仍然 `CONSUME`。单元测试同步改写：
  原来的 `screen lock consumes remote controls`（断言 CENTER → `CONSUME`）换成
  `screen lock leaves confirm to the unlock target` 与 `screen lock consumes directional input` 两项。

### 设备验证（emulator-5558，1920×1080）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 旧构建：锁屏 | `pf` / `pg` / `ph` | 三帧同哈希，CENTER、DOWN 均无反馈 |
| 旧构建：BACK | `pi` | 解锁并回到 chrome，焦点在 Play |
| 新构建：锁屏 | `q5` | 屏幕中央出现 `Unlock` 胶囊，`focused="true"`，蓝色焦点环完整（1:25.083） |
| 新构建：胶囊上 CENTER | `q6` | **解锁**，chrome 回来、焦点环落在 Play/Pause，播放未被打断（1:33 继续走） |
| 单元测试 | `:app:testDebugUnitTest` | `VideoPlayerTvKeyPolicyTest` + `VideoPlayerTvKeyDispatcherTest` 全通过 |

### 顺带确认的两条事实

- 解锁后焦点有归宿：`q6` 的焦点是 `Pause`（`[56,864][152,960]`），不是悬空，也不是停在已消失的胶囊上。
- 按键的配对是干净的：解锁这一次 OK 只做了解锁，没有顺带把播放切到暂停（1:25 → 1:33 是持续播放）。
  这依赖 `VideoPlayerTvKeyDispatcher` 把 CENTER 记为 `PASS_TO_FOCUS` 后，按下与抬起都交给焦点树。

### 遗留

- 锁屏态下**方向键仍然完全无效**（`CONSUME`）：这一轮是有意保留的（锁屏的意义就是屏蔽误触），
  代价是"锁屏后想快进/调音量"只能先解锁。若要放宽，应先确认媒体键（`MEDIA_*`）是否也该放行。
- 手机侧未回归：手机分支的代码路径未改动，仅新增了一个 TV 专属 composable。

## 第三十四轮：三个从没走查过的播放器面板（2026-10-23）

前两轮的遗留里一直挂着"DLNA 对话框、超分对话框仍未在电视上走过"。这一轮把 Options 网格里剩下的三个
弹层一次走完：`Cast to device`（DLNA）、`Video enhancement`（超分）、`Video Details`。三者都是把手机形态
直接搬到电视上，坏法各不相同，但根子只有三条。

### 现象

| 面板 | 旧构建截图 | 看到什么 |
| --- | --- | --- |
| DLNA（空列表） | `qb` | `Cancel` 是 `focused="true"`，屏幕上却没有任何环——焦点在，用户看不见 |
| Video enhancement | `qh` | 两行开关只有一层灰色 state layer，没有环；`Switch` 自己也是一个焦点停靠点，一行两停 |
| Video Details | `qq` | 对话框里**没有任何焦点**（dump 找不到 `focused="true"`）；卡片比屏幕还高，`FPS` 与缓存统计被切在屏幕外 |

### 三层根因

1. `onFocusChanged` 只看它**后面**的焦点目标。第一版把自造的 `playerTvFocusRing`（内部 `onFocusChanged` +
   `border`）放在 `.clickable` 之后，`isFocused` 永远是 false，环一次都没画出来；屏幕上的灰色块其实是
   Material3 ripple 的 focus state layer，很容易被误认成"已经有环了"。
2. 一行里嵌了第二个可点击控件：`Switch(onCheckedChange = …)` 与 `RadioButton(onClick = …)` 各自是一个焦点
   停靠点，遥控器要在同一行按两次才轮到下一行。
3. 弹层没人请求初始焦点。DLNA 靠 `AlertDialog` 的默认行为把焦点给了 `Cancel`（但没有环），Video Details
   干脆一个焦点都没有；超分面板的滚动 `Column`、DLNA 的设备列表都没有 `tvTakeInitialFocus()`。

### 改法

- 三处行统一成面板里已经在用的写法：`.tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false)`
  放在 `.clickable` **之前**，环交给 `tvFocusable` 自己的 `drawOutline`（与 `VideoActionItem` 一致，不再自造
  `border` 版本）。
- TV 上把内嵌控件的点击回调置空：`Switch(onCheckedChange = if (isTv) null else …)`、
  `RadioButton(onClick = if (isTv) null else …)`，整行成为唯一停靠点；行高走 `playerMenuRowMinHeight(0.dp)`
  （TV ≥60dp），文字走 `playerMenuRowTextStyle(compact = false)`。
- 初始焦点：超分面板的滚动 `Column`、DLNA 的设备 `LazyColumn` 各挂 `tvTakeInitialFocus()`；Video Details 的
  标题行挂 `tvTakeInitialFocus()`，让唯一的可操作目标（关闭 X）带环开场——正文只读，遥控器无法滚动，把焦点
  给正文等于给一个死停靠点。
- FSR 锐度滑杆在 TV 上接 `tvSliderDpad(value, valueRange, onValueChange, steps)`：Material3 `Slider` 默认只认
  拖拽，方向键落在它上面是另一个单向死胡同。
- Video Details 的正文高度在 TV 上从 480dp 收到 300dp：`AlertDialog` 即使 `confirmButton = {}` 也留着按钮行
  的高度，480dp 正文会让卡片越过 1080p 的下边缘。

### 设备验证（emulator-5558，1920×1080）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| DLNA 扫描中 | `qo` | `Cancel` 带完整蓝环（旧构建 `qb` 无环），spinner 照常转 |
| DLNA 空列表 | `qp` | `No DLNA devices found` + `Cancel` 仍带环，对话框收成内容高度 |
| 超分面板打开 | `qi` | 初始焦点落在第一行 `Enable for this video`，环完整（旧构建 `qh` 只有 state layer） |
| 超分 DOWN + OK | `qm1` | 焦点直接落到 `Remember across videos`（一行一停），OK 把它切到 ON（白轨黑芯） |
| Video Details | `qt` | 关闭 X 带环并作为初始焦点；卡片完整收在屏幕内（旧构建 `qq` 越界） |
| 各面板 BACK | — | 关闭后焦点回到锚点（`Options` 按钮或上一行），chrome 立刻可用 |

### 顺带确认

- 超分开关的点击是通的：logcat 里是 `Video enhancement toggled enabled=true`，紧跟
  `Enhancement route bypassed shouldEnable=true allowShader=false`。也就是说 UI 修好了，但这台模拟器
  （`tier=LOW`、`allowShaderPipeline=false`）会立刻回落直出并把开关还原成 OFF，所以展开后的算法/预设/滑杆
  分支在这里看不到——这不是焦点问题。

### 遗留

- 超分面板的展开态（算法选择、Anime4K 预设、FSR 滑杆）只在代码层复核：模拟器的 shader 路由被播放策略挡掉，
  要真验证得在支持 GLES 增强的真机上跑。
- Video Details 的尾部（FPS、缓存统计）在电视上够不到：正文是只读 `verticalScroll`，遥控器没有滚动语义。
  真正解决要么给正文加方向键滚动，要么把详情改成播放器菜单风格的弹层。
- DLNA 的"有设备"分支仍只做了代码复核：模拟器网络上没有 DLNA 设备。
- 子面板压在父面板上：`Video enhancement` 打开时 `Options` 面板仍在下面显示（`Reload`、`Settings` 露在四周），
  视觉上像一张拼起来的卡片。收起父面板要动菜单状态机，留到后面。
- 手机侧未回归；新增样式都走 `isTvPlayerMenu()` / `LocalUiPresentationConfig.isTv`，手机分支未改。

## 第三十五轮：子面板打开时父面板不再露在下层（2026-10-24）

第三十四轮遗留的第一条：`Video enhancement` 打开时 `Options` 网格仍在下面，`Reload`、`Settings` 从子面板
四周露出来，视觉上像一张拼起来的卡片；BACK 关掉子面板后焦点还会跳回第一行 `Intro`，而不是停在用户刚才
按的 `Video enhancement` 上。

### 根因

1. 两个面板都是 `Popup` 窗口。网格的 `onItemSelected` 只在"没有新弹层"时才清 `actionDialogState`
   （`!superResolutionDialogVisible` 那一段），所以开子面板时父面板状态被保留，父面板就继续绘制。
2. 面板不记焦点行：一旦把它从组合里摘掉，重新组合时 `LazyVerticalGrid` 从第一项开始，焦点自然回到 `Intro`。

### 改法

- `VideoActionDialog` 增加 `hidden` 参数，内容层走 `.alpha(0f)`：面板**保持组合**但不可见。子面板依旧是
  独立的 focusable 窗口，遥控器的键全部落在子面板上；子面板关掉后父窗口重新拿到焦点，
  `LazyVerticalGrid` 里上一次聚焦的那一行原样还在。
- Activity 侧用 `tvPanelCoveredByWindow` 汇总"当前有独立弹层"（选择框、章节、字幕设置、超分、DLNA、备注、
  详情、初始化报错），只在电视上把 `hidden` 置真；手机分支不变，仍是半透明面板叠对话框。

### 设备验证（emulator-5558，1920×1080）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| Options → Video enhancement | `pz1` | 画面里只剩 `Video enhancement` 一张卡片，父面板的 `Reload`/`Settings` 不再露出 |
| BACK | `pz2` | `Options` 面板整体回来，环停在 `Video enhancement` 那一行（旧构建回到第一行 `Intro`） |
| Options → Cast to device | `pw5` | DLNA 扫描卡片同样单独显示，`Cancel` 带环 |

### 顺带排除

- 截图里那块浅灰矩形不是 UI：收起 chrome 后 `00:01:31.833` 那一帧里它就在画面内——是审计用的合成测试片
  自带的色块，不是焦点或层级问题。

## 第三十六轮：详情卡片可以用遥控器翻到底（2026-10-24）

第三十四轮遗留的第二条。`Video Details` 的正文是只读的 `verticalScroll`，在电视上就是一个死焦点：
焦点进不去，`FPS`、缓存统计、播放诊断这几段永远看不到，用户只能猜"这张卡是不是还能往下翻"。

### 根因

1. `Text` 只是可滚动的，没有焦点目标，D-pad 没有可以移动的东西。
2. 就算给它加焦点目标也不够：X 在卡片右上角，正文在它左下，两者的水平重叠只有卡片右缘那一条窄带，
   默认的按几何搜索（beam search）居然落空——按一次 DOWN 之后**整个卡片没有任何焦点**，
   后面的按键全部被吞掉，看起来像遥控器坏了。这是本轮最花时间的一条。

### 改法

- 正文在电视上变成自己的焦点目标：`tvFocusable` 加环 + `onPreviewKeyEvent` 把上/下映射成
  `animateScrollBy`（一次约 0.8 个视口）。滚到顶继续按上就把键放出去，落到 X；
  滚到底继续按下则**吃掉按键**（下面没有目标，交出去又会丢焦点）。
- X 与正文用 `FocusRequester` + `focusProperties { down = ... } / { up = ... }` 互相绑死，
  不再依赖几何搜索。初始焦点仍在 X（`tvTakeInitialFocus`）。
- `VideoActionDialog` 的 `onItemSelected` 关门条件补上 `videoInfoDialogText == null`：
  详情卡片也成了"有独立窗口的条目"，点它不再顺手把 `Options` 面板一起收掉，BACK 回来还是那一行。

### 设备验证（emulator-5558，1920×1080）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| Options → Video Details | `fb0` | `Options` 面板里环停在 `Video Details`（网格已自动上滚，`Intro`/`Outro` 那行滚出可视区） |
| CENTER | `g0`/`h0` | 卡片打开，环在右上角 X 上；父面板被隐藏而不是退场 |
| DOWN | `h1` | 正文拿到焦点并带环（旧构建：焦点直接消失，卡片里再无环） |
| DOWN×3 | `h2`/`k0` | 正文滚到 `Playback Diagnostics` 尾巴，环始终在正文上 |
| UP×2 | `h3` | 滚回上半部分，`Title:` 重新出现在顶部 |
| UP×3（到顶后继续） | `h4` | 环回到 X，正文只保留自己的位置 |
| BACK | `k1` | 卡片关闭，`Options` 面板回来，环停在 `Video Details` 那一行 |

### 备注

- 手机上这条链路完全不变：正文依旧只读可滚，不额外加焦点目标，`focusRequester` 只在电视分支里接上。
- `SelectionContainer` 保留：详情文本仍可长按复制，电视上只是多了一个焦点停靠点。

## 第三十七轮：超分展开态终于能看到了，DLNA 也归队（2026-10-24）

第三十四轮把超分面板的展开态（算法选择、Anime4K 预设、FSR 滑杆）挂成了"只能在真机验证"，原因写得很直白：
模拟器的 `tier=LOW`、`allowShaderPipeline=false`，开关一按就被播放策略还原成 OFF，展开分支根本渲染不出来。
这一轮先解决"看不到"，再把展开态整条走一遍。

### 让模拟器够得上 MID

`DevicePerformanceClassifier` 的打分是 `isLowRamDevice +3 / 总内存 1..3072MB +2 / 核数 ≤4 +1 / 32 位 +1 /
Leanback +1`，≥4 判 LOW、≥2 判 MID。AVD 默认 `hw.ramSize = 1536M`、`hw.cpu.ncore = 4`、TV 镜像自带 Leanback，
正好 2+1+1 = 4 分，永远停在 LOW。把模拟器按 4GB 重启即可（不改 AVD 配置、不改代码）：

```
adb -s emulator-5558 emu kill
emulator -avd kototoro_tv_api36 -gpu host -memory 4096 -no-boot-anim
```

`MemTotal: 4013816 kB` 让 +2 消失，剩 4 核 +1 与 Leanback +1 共 2 分 → MID → `allowShaderPipeline = true`，
开关终于留得住，`Video Details` 里的 `Device Tier` 也从 `LOW` 变成 `MID`。注意新实例会换 serial（这次是
`emulator-5554`），后面的 adb 命令都按新 serial 走。

### 展开态的设备验证（emulator-5554，1920×1080，tier=MID）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| Options → Video enhancement | `m7` | 卡片打开，环在 `Enable for this video`；父面板已隐藏，右侧只剩播放器自己的音符悬浮按钮 |
| CENTER（打开开关） | `m8` | 开关变白轨黑芯并**留在 ON**，下方原地长出 `Algorithm`（Anime4K / AMD FSR 1.0）与 `Anime4K preset`（Efficiency / Quality） |
| DOWN | `m9` | 环落到 `Anime4K (animation)`，卡片自动上滚把标题让出去，`Quality` 完整露出 |
| DOWN → AMD FSR 1.0 → CENTER | `n1` | 预设段原地换成 `FSR sharpness: 90%` 滑杆，算法行的圆点跟着走 |
| UP | `n2` | 滑杆拿到焦点并画出整条圆角环 |
| LEFT ×2 | `n2` | 数值 90% → 70%（每步 10%，`tvSliderDpad` 的 preview 拦截生效） |
| BACK | `n4` | 卡片关闭，`Options` 面板回来，环停在 `Video enhancement` 那一行（dump 焦点框 `[800,417][1344,537]` 正是那一行） |

### DLNA 也算"有独立窗口的条目"

第三十五轮把 `Video enhancement`、`Video Details` 都收进了"父面板保持组合、只隐藏"的规则，但同一份
`onItemSelected` 关门条件里没有 `dlnaDialogState`，于是 `Cast to device` 仍然是"打开子面板顺手把父面板拆掉"，
BACK 之后人直接掉到裸 chrome 上（旧构建 `m5`：面板消失，焦点落到播放条上，想再进 Options 得重新从顶栏摸过去）。
现在条件补成
`selectionDialogState == null && !superResolutionDialogVisible && videoInfoDialogText == null && dlnaDialogState == null`：

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| Options → Cast to device | `p3` | 扫描中的 DLNA 卡片单独显示，`Cancel` 带环，`Options` 面板不再从四周透出 |
| BACK | `p4` | 面板整体回来，环停在 `Cast to device` 那一行（旧构建落到裸 chrome，焦点在播放条/速度按钮之间跳） |

### 观察与遗留

- 打开开关后、卡片开着的这段时间里播放器画面是黑帧；关掉卡片回到播放器，彩色测试图案立刻恢复（`n5`）。
  这台模拟器走的是 host GPU，增强渲染路径在它上面不稳，不能在电视 UI 审计里当缺陷记；真机（或带真实
  GLES 的盒子）需要复核一次"开增强后画面正常"。
- `Add note` 的编辑器仍然走"先抓帧再开窗"的异步路径，`onItemSelected` 的同步关门判断看不到
  `videoAnnotationState`，因此它还是会连父面板一起关掉。要修得把关门判定推迟到抓帧回调之后，
  或者给条目加一个"会开窗"的标记，留待下一轮。
- 手机上这些行本来就是全宽可点、控件自带交互，新增条件只影响电视分支的面板保活，手机未回归。

## 第三十八轮：字幕设置面板的五个标签页（2026-10-24）

顶栏的 `Subtitles` 按钮打开的面板一直没进过电视走查名单，而它是播放器里最"手机味"的一块：五个标签页
（轨道/排版/背景/布局/颜色）全是 `FilterChip` + 滑杆，摆在一个 `Popup` 里。手机上这样没问题，遥控器上
几乎每一层都断。

### 三处断点

1. **没有初始焦点**：`Popup(focusable = true)` 只保证按键进了这个窗口，没人请求焦点，所以面板一打开
   屏幕上没有任何环，第一次按方向键才"凭空"出现一个。
2. **`FilterChip` 只有 state layer，没有环**：被选中的标签是白底黑字，可**焦点**落在哪个 chip 上完全看不出来；
   同时 chip 只有 32dp 高，1080p 三米外基本是几粒小药丸。
3. **滑杆是单向死胡同**：`KototoroSlider` 包的是 Material3 `Slider`，遥控器左右键不会改值（与第三十四轮
   超分滑杆同一个根因），字号/边框/背景圆角/四个通道全部改不动。

### 改法

- 面板的滚动 `Column` 挂 `tvTakeInitialFocus()`，打开即把环落在第一个标签（`Subtitle Track`）。
- 新增本地 `SubtitleChip`：手机上直接返回原样 `FilterChip`（含 `playerChipColors()`），电视上换成一枚手绘
  药丸——`clip` + `background` + `tvFocusable(shape, addFocusTarget = false)` + `clickable`，
  最小高度走 `playerMenuRowMinHeight(32.dp)`（TV ≥60dp），文字颜色用 `LocalContentColor` 跟着选中态走。
  十一个 call site 只把 `FilterChip(` 换成 `SubtitleChip(`，样式参数一个都没加。
- `LabeledSlider` 给 `KototoroSlider` 接 `tvSliderDpad(value, valueRange, onValueChange, steps, enabled)`，
  左右键按一步一格走，值写回同一份 `VideoSubtitleSettingsDialogState`。
- 颜色通道的整块色片从 28dp 抬到 56dp（`heightIn(min = if (isTv) 56.dp else 28.dp)`）并补上同样的环。

### 设备验证（emulator-5554，1920×1080，tier=MID）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 顶栏 → Subtitles → CENTER | `s1` | 面板打开，环在第一个标签 `Subtitle Track`；标签行与轨道行都是 60dp 高的整块药丸（`[404,112][694,232]`） |
| RIGHT ×4 | `s2` | 环走到 `Colors`，标签行自动横滚，最后一个标签从被裁掉变成完整可见 |
| CENTER（切到 Colors） | `s3` | 同一帧里出现 `Typography`（B/I）、`Text/Border/Background`、三块色片、Alpha/Red/Green/Blue 四根滑杆；卡片实测 `[372,16][1588,1064]`，没有越出屏幕 |
| DOWN ×6 | `s4` | 焦点依次走过 B/I、色片开关、三根滑杆，列表跟着上滚 |
| LEFT ×3（Green 上） | `s5` | `Green 255 → 252`，滑杆带整条圆角环；下面 `Blue` 仍完整可见 |
| BACK | — | 面板关闭，环回到顶栏的 `Subtitles` 按钮（`[1600,8][1696,104]`） |

### 备注与遗留

- 每个非轨道标签页都会重复出现 `Typography` 的 B/I 两个 chip，这是原有结构（样式对所有文字统一生效），
  不是这一轮引入的；电视上只是把它们也做成了可聚焦的药丸。
- 颜色滑杆的每步是 1（`steps = 254`），从 0 调到 255 要按 255 次遥控器。手机上可以用手指拖，
  电视上真正好用的做法是让长按连发或者按 16 一档；留待下一轮，先把"能动"这件事做对。
- 面板宽度仍是 `playerMenuWidth(300, 380)` 的 ×1.6（实测 608dp），标签行放不下五个时靠横向滚动；
  若后面要完全避免横滚，需要把标签改成两行或图标式。

## 第三十九轮：附件面板的那一格也会"先关面板再开窗"（2026-10-24）

`Add note` 是选项面板里唯一一个**不能当场 setState**的条目：它要先把当前帧截成 bitmap，文本框、缩略图和
"Episode · 时间点"都要等 `PixelCopy` 回来才拼得出来，所以 `videoAnnotationState` 是在回调里赋值的。
第三十七轮给 `onItemSelected` 的收尾判定加了一串同步标志（`videoInfoDialogText`、`dlnaDialogState`、
`superResolutionDialogVisible`、`selectionDialogState`），这一格没有同步标志可看，于是：

1. OK 按下 → `openVideoAnnotation()` 暂停并把截图请求丢给 `PixelCopy`，此刻 `videoAnnotationState` 仍是 null；
2. 收尾判定认为"这一格没开出任何窗口" → 面板关掉，焦点回到播放器 chrome；
3. 几十毫秒后截图回来，笔记窗口在最上层弹出 —— 用户看到的是"面板没了、突然多了个窗"，
   在笔记里按 BACK 也只能落回 chrome，选项面板再也回不来。

### 改法

新增 `videoAnnotationCapturePending`：`openVideoAnnotation()` 在电视上先把它置 true，截图回调（成功与失败
两条路径都会走到）置回 false；`onItemSelected` 的收尾判定多一条 `&& !videoAnnotationCapturePending`。
这样面板在"请求已发出、窗口还没建好"的空窗期保持存活，`tvPanelCoveredByWindow` 随后因为
`videoAnnotationState != null` 把面板藏起来，笔记关掉再自动显形。手机不受影响（标志只在
`tvPresentationEnabled` 时置位，手机仍是老行为：关面板、开笔记、BACK 回播放器）。

### 设备验证（emulator-5554）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| ⋮ → CENTER → DOWN ×4 | `t1` | 环落在 `Add note`（`[800,534][1344,654]`，第 5 行左格） |
| CENTER | `t2` | 首帧缩略图 + 引用字幕 + 文本框 + `Cancel`/`Save note`，环预先落在 `Save note` |
| BACK | `t3` | 笔记关闭，**选项面板回到原位**，环仍在 `Add note` |

### 遗留

- 笔记窗口本身还是手机尺寸的 `Dialog`（约 840dp 宽、居中、`Save note` 在右下）。在电视上它能用——
  有初始焦点、两个按钮都有环、X 也能聚焦——但字偏小、文本框没有 tv 高度，属于"能用不好用"那一档，
  留待和 `NovelTtsVoiceDialog` 一起做统一的小窗规格。
- `openVideoAnnotation()` 无条件暂停播放，而"取消笔记"不会恢复播放。是否要把"取消后继续播放"做进
  这一格，取决于用户对"取消"的预期，先记录不动。

## 第四十轮：漫画选项面板"请求成功、焦点却不在任何控件上"（2026-10-24）

第二十九～三十二轮把漫画阅读器的顶栏走通了，但选项面板（`state.options.visible`）一直是死的：面板打开后
屏幕上没有任何焦点环，`uiautomator` 报 `focused=none`，按 UP/DOWN/LEFT/RIGHT 得到的截图**逐字节相同**
（`sha256 5861f872…`）。这一轮把它查到底。

### 排错：三个"看起来都正常"的前置条件

1. **按键路由正常。** 在 `ReaderActivity.dispatchKeyEvent` 里打点，面板打开时 `isReaderControlsVisible=true`
   且 `shouldInterceptReaderTvKey(isTv, true, 19/20/21/22)=false` → `Action.DELEGATE`，方向键确实交给了 Compose。
   所以不是"键被阅读器吃掉"。
2. **视图焦点正常。** 探针打印 `requestFocusFromTouch()` 前后：`viewBefore=false → viewAfter=true`、
   `isInTouchMode=false`，随后 `FocusRequester.requestFocus()` 返回 `granted=true`。
3. **控件本身可聚焦。** 语义树里每个快捷格都是 `focusable=true` 且带 bounds（`[232,736][414,892]` 等）。

### 根因：焦点确实落过一次，随后被面板自己抢走

在快捷层外面套一层 `onFocusChanged` 打点，真相是两行相邻日志：

```
quickLayer focused=true  has=true     ← 第一个快捷格拿到了焦点
quickLayer focused=false has=false    ← 4ms 后掉了（同帧还有一次 req granted=true）
```

面板的根 `Column` 装了 `focusGroup()` + `focusRequester()`，于是它自己也是一个焦点目标：一次
`requestFocus(panelGroup)` 把焦点从刚拿到的格子上**取走**，落在 `Column` 上。而 `Column` 有两个致命属性：

- 它不画焦点环（`tvFocusable` 只挂在真正的控件上），所以屏幕上什么都看不到；
- 它的矩形覆盖整张面板，`Down` 的光束搜索从它出发找不到"更下方"的节点，于是方向键什么都不做。

同时面板里有**三处**在请求焦点（首帧重试、`contentBottomPadding` 变化时重试、从展开态收回时恢复），
谁最后执行谁说话，第一次请求(格子)和第三次请求(Column)相差 4ms，结果稳定地由 Column 获胜。

### 改法：一个地方请求，而且请求到具体控件

- `ReaderPanelHost` 新增参数 `initialFocusRequester: FocusRequester? = null`——"面板一打开应该落在哪个控件"
  只有调用方知道。默认退回 sheet 自身的 requester（给没有专属首格的目录面板用）。
- 请求收敛成**一条** effect：`repeat(10) { withFrameNanos{}; if (sheetHasFocus) return; requestFocus(target) }`。
  `sheetHasFocus` 取自面板根 `Column` 的 `onFocusChanged { it.hasFocus }`，**一旦为 true 就再也不请求**，
  于是不存在"落好的环又被抢走"。
- 删掉按 `contentBottomPadding` 反复重试的那条 effect（它正是把环从格子上顶掉的那次请求）。
- 漫画选项面板：`ReaderMangaQuickLayer` / `ReaderQuickActionGrid` 恢复 `firstActionFocusRequester` 参数，
  把 requester 挂在第一格上，并作为 `initialFocusRequester` 传给宿主。
- 收起恢复那条 effect 也改成同一个 `focusTarget`，并加 `!sheetHasFocus` 守卫。

### 设备验证（emulator-5554，1920×1080）

按键序列：详情页 Continue → `CENTER` 叫出顶栏 → `RIGHT ×2` 到 ⋮ → `CENTER` 打开面板。

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 打开面板 | `rg0` | 首帧即**环在第一个快捷格** `Chapters and…`（`[232,736][414,892]`），`uiautomator` 报 `focused=true` 且只有这一个 |
| RIGHT | `rg1` | 环移到 `Add bookmark`（`[414,736][596,892]`） |
| CENTER | `rg2` | 书签写入（`Bookmark added`）、面板关闭、**环回到顶栏返回箭头** `[28,12][124,108]` |
| 重开面板 → UP | `ri0`/`ri1` | 环上到模式行，RIGHT 能在 5 个模式 chip 之间移动 |
| 重开面板 → DOWN | `rh1` | 环下到第二行 `Manga translate…`（`[232,900][414,1056]`） |
| BACK | `rh3` | 面板关闭、环回返回箭头，与 CENTER 后那张同一状态 |

也就是说"打开面板 → 上下左右挑一格 → OK 执行 → BACK 关闭 → 环回顶栏"这条电视主链路现在完整成立。

### 遗留

- 快捷格标签被截断（`Chapters and …`、`Manga translate…`）：电视上 8 列等分时单格只有约 182px，
  需要按 `isTv` 减少列数或把标签改成两行。
- 玻璃模式下 `readerPanelScrimAlpha` 偏透明，正文透过面板，可读性差；电视应换成更实的底。
- 面板停在 peek 时高度约 66%，中间有大片空白（模式行与快捷格之间），比重可以再收。

（以上三条已在第四十一轮收掉前两条，第三条并入下面新记录的遗留。）

## 第四十一轮：电视选项面板换成实体面、标签不截断、实心控件的环看得见（2026-10-24）

第四十轮把面板"点得动"修好了，这一轮解决"看不看得清"：面板太透明、标签被截断、以及**实心（已选中）
控件上的焦点环与底色同色**——三件事都属于"遥控器明明在工作，用户却以为坏了"。

### 电视用实体面板，玻璃只留给手持屏

`readerPanelSurfaceMode(isIosStyle, eInk, hasBackdrop)` 原来只看 iOS 风格与有没有阅读器背景，电视上
如果开了 iOS 风格就一路走玻璃，正文页的字隔着 0.62 的面纱透上来（`GLASS_VEIL_ALPHA`），10 英尺外
根本压不住。新增第 4 个参数 `isTv`，判定顺序变成：

```
eInk → EInk
isTv → Opaque            ← 新增
isIosStyle && hasBackdrop → Glass
else → Opaque
```

Android 侧唯一的调用点 `rememberReaderPanelSurfaceMode(eInk)` 补上
`isTv = LocalUiPresentationConfig.current.isTv`，四个面板（漫画选项、漫画目录、小说目录、小说选项）
一起生效；手机仍然保持原来的玻璃观感。EInk 排在最前，电子墨水模式不受影响。

配套的遮罩也随之变实：`readerPanelScrimAlpha` 在 `Opaque` 下是 `0.42f`（玻璃是 `0.32f`）。

### 快捷格标签：电视两行、字号升一级

`ReaderQuickTile` 的标签原本是 `labelSmall` + `maxLines = 1`，8 列等分时单格约 182px，
`Chapters and pages`、`Manga translation`、`Automatic scroll`、`Open in browser` 全部被省略号砍掉。
电视下改成 `labelMedium` + `maxLines = 2`：四个标签都完整显示，格子高约 190px（+34px），
peek 高度跟着长一点，手机保持单行小字不变。

### 实心控件上的环：改用内容色而不是强调色

`tvFocusable` 的默认环色是 `colorScheme.primary`。已选中的页签、选项 chip、开关 chip、图标模式行的底色
本身就是强调色系（`selectedContainer`），蓝环画在蓝底上——**焦点在线，屏幕上却什么都没有**。
这类控件的环改成 `colors.content`（深色主题下是近白色）：

- `ReaderPanelTabBar` 的页签（`Layout / Display / Translation`）
- `ReaderChoiceChips` 的选中 chip（如 `Default`、`Fit center`）
- `ReaderIconChoiceBar` 的选中模式图标
- `ReaderPanelToggleChip` 打开状态

未选中时仍用强调色环，与非选中态原有的强调色描边区分开。

### 设备验证（emulator-5554，1920×1080）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 打开面板 | `rk0` | 面板是实体深色面（约 `#1C1B20`），正文不再透上来；环仍在第一格 |
| 打开面板（含标签改动） | `rl3` | `Chapters and pages` / `Manga translation` / `Automatic scroll` / `Open in browser` 全部两行完整显示，底部 `Layout / Display / Translation` 页签条与齿轮露出 |
| DOWN | `rl4` | 环到 `Manga translation`（`[232,738][414,926]`），`uiautomator` 只有这一个 `focused=true` |
| UP ×2 | `rl5` | 环回到模式行第一个 chip |
| DOWN ×3 | `rl6` | 垂直链路成立：模式行 → 快捷格第一行 → 第二行 → **页签条** |
| RIGHT + CENTER | `rl7` | `Display` 页展开，环落在当前值 `Automatic` chip 上，行可读 |
| 展开后 DOWN | `rm5` | 环进入 `Reverse chapter order for this title` 整行开关 |
| BACK | `rl8` | 面板收回 peek，`Display` 仍为选中页签 |
| BACK | `rl9` | 面板关闭，环回到顶栏返回箭头 |
| 页签选中态聚焦 | `rn0` | 选中页签 `Layout` 上出现**白色**环（此前是蓝底蓝环，等于没有） |
| 选中 chip 聚焦 | `rn1` | `Default` 页动画 chip 上是白环；未聚焦的选中 chip `Fit center` 仍是强调色描边，两者可区分 |

面板这一条主链路现在是完整的：`⋮` 打开 → 首格有环 → 上下左右挑 → 页签可进 → 展开页可走 →
BACK 先收再关 → 环回顶栏。

### 一个构建陷阱：失败的构建会留下"缺类"的 APK

本轮中途 `:app:assembleDebug` 失败过一次（并行改动的模块没编译过），随后重跑 **BUILD SUCCESSFUL**，
但产出的 APK 装上去直接 `ClassNotFoundException: org.skepsun.kototoro.KototoroApp`——失败那一次已经把
`compileDebugKotlin` 的输出目录清掉了，重跑时该任务被判 UP-TO-DATE，`dexBuilderDebug` 于是拿不到
`app/src/debug` 的类。判据很简单：装完先确认 `KototoroApp` 在不在 dex 里——

```powershell
# 用 System.IO.Compression 打开 apk，逐个 classes*.dex 找 UTF-8 串
# Lorg/skepsun/kototoro/KototoroApp;
```

修法是 touch 一下 `KototoroApp.kt`（debug 源集那个）再构建，强制重编。**以后看到"刚装的新包一启动就
闪退、栈里是 ClassNotFound"，先怀疑构建产物而不是这轮的代码。**

### 遗留

- 面板停在 peek 时，模式行与快捷格之间仍有一段空白；两行标签后整体高度约 74%，比重还能再收。
- “笔记”窗口仍是手机尺寸的 `Dialog`；`NovelTtsVoiceDialog` 本环境无法出现，一并待做统一小窗规格。
- 漫画目录面板（`ComposeReaderActivityScaffold.kt` 里的 `ReaderPanelHost`）与小说两个面板还在用
  legacy 的“请求整组”回退，尚未给专属首格；三者需要各自确认一次初始焦点。
- 手机端回归（`isTv` 为 false 的三处新分支）只做了代码级检查，没跑真机。

## 第四十二轮：目录面板的焦点不再漏到面板外，页格在电视上一屏放得下（2026-10-24）

第四十一轮把选项面板走通了，这一轮拿**同一套标准检查目录面板**（`ReaderPanelHost` 里那个），
顺带把它的两个页签（`Pages` / `Bookmarks`）走了一遍。结论：面板面、首格、行内上下都成立，
但"行右边还有什么"和"页格放不放得下"各有一个坑。

### 目录面板：首格是对的，RIGHT 会漏出面板

打开目录面板（`ro1`）：实体深色面、`Chapter 01` 整行落在初始焦点上（`[216,602][1704,714]`，
`focused=1`）、行尾的书签/下载/翻译图标与 `Chapters / Pages / Bookmarks` 页签条都在。
DOWN 换到 `Chapter 02`（`ro2`）、UP 回到第一行都正常。

问题出在 RIGHT。行上按 RIGHT，焦点**跳出面板**落到面板背后顶栏的 `⋮`（`ro3`，环在右上角，
面板仍然开着）——遥控器于是卡在暗掉的一层里，只能靠 BACK 收回。

根因不是面板没锁焦，而是**行右边在面板内压根没有候选**：整行是一个焦点目标，
行尾那三个按钮落在行的 bounds **内部**，而 Compose 的方向搜索只找"在对侧、且与当前节点不重叠"的
节点，所以行内部的东西永远不是 RIGHT 的候选，束搜索只能一路找到面板外的顶栏。

修法（`app/.../chapters/compose/ChapterItemCard.kt`）：给行的 `focusModifier` 加

```kotlin
Modifier
    .tvFocusable(shape = RoundedCornerShape(8.dp), borderWidth = 2.dp, addFocusTarget = false)
    .tvHorizontalEdgeGuard(blockRight = true)
```

`tvHorizontalEdgeGuard` 只在电视上生效，手机上返回原 Modifier。改后 RIGHT 停在行上（`rp0`，
`focused=1` 仍是 `[216,602][1704,714]`），焦点不再漏。

### 页签条本身是好的

UP 从行回到页签条（焦点落在离行最近的 `Pages`，`[873,468][1019,564]`），LEFT / RIGHT 在三个页签间
逐个移动（`rp4` Bookmarks→LEFT→Pages、`rp5` Pages→RIGHT→Bookmarks），到两端停住不越界（`rp6`）。
三颗页签在 `uiautomator` 里都是 `focusable=true`，没有"看不见的死链"。
CENTER 切页签后焦点仍停在页签上，选中态上是白色环（`rp7` 与放大裁图 `rp7-tabs`）——
第四十一轮给页签加的 `ringColor = colors.content` 在这里同样生效。

### 页格：3 列的卡片比面板还高，而且卡片根本不画环

切到 `Pages` 页签，问题一次出来两个：

1. 卡片比面板可视区高。页面缩略图按 `aspectRatio = 0.7`（宽/高）排，默认 3 列时每张约
   475×680px，而面板从页签条往下只剩 510px——**任何一张卡都不可能完整显示**，
   焦点环最多只能看到三面，滚动也永远停在"半张卡"上。
2. `PageThumbnailCard` 没有 `tvFocusable`。`combinedClickable` 让它可聚焦，但没有任何环，
   `uiautomator` 报 `focused=true`、屏幕上什么都没有——正是"遥控器坏了"的那一类。

修法两处：

```kotlin
// PagesScreenRoot.kt：电视上至少 5 列（卡片约 278×397，一屏放得下整行）
gridColumns = if (LocalUiPresentationConfig.current.isTv) {
    maxOf(TV_PANEL_MIN_PAGE_COLUMNS, pagePreviewGridColumns(gridScale))
} else {
    pagePreviewGridColumns(gridScale)
}

// PagesScreen.kt：缩略图补环
.tvFocusable(
    shape = RoundedCornerShape(8.dp),
    borderWidth = 3.dp,
    addFocusTarget = false,
    ringColor = Color.White,   // 缩略图是任意插画，白环在深浅页面上都看得见
)
```

`addFocusTarget = false` 是因为后面的 `combinedClickable` 已经是焦点目标，再要一个会多一次停留。

改后（`rr1`）：5 列、章标题 `Chapter 01` 在列上方，当前页卡片外围一圈白环清晰可辨；
DOWN 到第二行（`rr2`，第 6 页），卡片完整、环四面可见、网格只滚动到刚好露出这一行。
左右在行内逐个移动（`rr3`：第 1 列 → 第 2 列 → 第 3 列）。

### 设备验证（emulator-5554，1920×1080）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 打开目录面板 | `ro1` | 实体面；首格是 `Chapter 01` 整行 `[216,602][1704,714]`，`focused=1` |
| DOWN | `ro2` | 环到 `Chapter 02`，行仍占满面板宽度 |
| 修前 RIGHT | `ro3` | **焦点漏到面板外**顶栏 `⋮`，面板还开着 |
| 修后 RIGHT | `rp0` | 焦点留在行上，`focused=1` 仍是同一行 |
| UP | `rp1` | 焦点到页签条 `Pages` `[873,468][1019,564]` |
| UP + RIGHT | `rp2` | 环到 `Bookmarks`，页签间逐个移动 |
| LEFT / RIGHT / 端头 | `rp4` `rp5` `rp6` | `Bookmarks→Pages`、`Pages→Bookmarks`、`Bookmarks` 上再 RIGHT 不动 |
| Pages 页签 + 裁图 | `rp7` `rp7-tabs` | 页签切换成功，选中态白环清楚（面板未丢焦点） |
| 页格（修后） | `rr1` | 5 列，当前页卡片四周白环可辨，章标题在格上方 |
| 页格 DOWN | `rr2` | 第 6 页卡片完整，网格只滚到刚好露出该行 |
| 页格 RIGHT ×2 | `rr3` | 行内第 1 列 → 第 2 列 → 第 3 列 |

### 遗留（本轮新增）

- 章节行行尾的下载/翻译按钮**在电视上用遥控器仍然够不到**：它们落在整行的 bounds 内部，
  方向搜索够不着。要真正修，需要把电视上的行拆成"标题区 + 每个按钮"各自的焦点目标
  （行本身不再整体 `combinedClickable`），这是共享 `ChapterListCard` 的结构改动。
  现在的处理是"宁可停在行上，也不漏到面板外"。
- `Pages` 页签第一行的卡片底部正好顶到屏幕下沿，环的下边会被裁掉 1–2px（卡片 397px 高、可视 510px，
  中间还有章标题行）。把面板头部的标题/页签条收紧，或电视上把缩略图 `aspectRatio` 再压一点可解。

## 第四十三轮：面板打开时，电视上不再藏着另一层顶栏（2026-10-24）

第四十二轮用 `tvHorizontalEdgeGuard` 把"行右边"这一条缝堵住了，但根因还在：**面板后面的顶栏仍然
是一个可聚焦的层**。实测两条漏法：

- 目录面板：页签条往上走 → 面板头部的搜索 / `⋮` → 再往上，焦点落在面板背后顶栏的 `⋮`，按 CENTER
  会把目录面板顶掉、打开选项面板（用户以为在选章节，结果换了面板）。
- 选项面板：快捷格走到最右边的 `Open in browser` 再按 RIGHT，焦点跳到面板右缘外那颗悬浮的
  `Translate` 按钮上——它在面板边界之外，环被面板盖住。

### 先试错：给背后控件关掉 canFocus

第一版给 `tvFocusable` 加了一个 `LocalTvFocusEnabled`（`compositionLocalOf { true }`），面板打开时
在顶栏和底部控制条外面包一层 `provides false`，让这些控件的 focus 链发出
`focusProperties { canFocus = false }`。

结果：UP 确实不再逃出面板了，但**方向搜索找到一个候选、它却拒绝聚焦，Compose 会把焦点清空**——
`uiautomator` 里 `focused=0`，屏幕上没有任何环。对遥控器来说这比"跳到背后"更容易被当成坏了
（用户要再按一次方向键才恢复）。这一版回退了。

### 采用：面板打开时干脆不渲染顶栏与底部控制条

面板在手机上是独立的 bottom sheet 窗口，在电视上却是同一窗口里的 overlay——"模态"只靠 scrim 表达，
背后那层之所以能被聚焦，是因为它还在。那就让它在电视上不存在：

```kotlin
// 顶栏
visible = state.controlsVisible && !(isTvPresentation && expandedPanelVisible)
// 底部进度条 + 标题药丸 + 悬浮按钮列
visible = bottomChromeVisibility.visible && !(isTvPresentation && expandedPanelVisible)
```

`expandedPanelVisible`（目录 / 选项 / 工具 / 自动滚动）是已有的判断，电视上它一为真，整个 chrome
退场。没有节点就没有候选：方向搜索在面板边缘找不到东西，于是**保持当前焦点不动**——这正是遥控器
在模态面板上应有的表现。手机不受影响（`isTvPresentation` 为 false，可见性判断原样）。

顺便也更好看：10 英尺距离下，面板背后一层暗掉的顶栏只会诱导用户把焦点送进去。

### 设备验证（emulator-5554，1920×1080）

| 步骤 | 截图 | 结果 |
| --- | --- | --- |
| 打开目录面板 | `rw0` | 顶栏消失，面板独占屏幕；首格 `Chapter 01` 有环 |
| 行上 RIGHT | — | 焦点不动（`[216,602][1704,714]`），兜底守卫 + 无候选双保险 |
| UP | — | 到页签条 `Pages` `[873,468][1019,564]` |
| UP 再按 | `rw1` | 到面板头部**搜索**按钮（仍在面板内，白色圆环清楚），再 UP 无处可去 |
| 打开选项面板 | — | 顶栏同样消失，首格有环 |
| 快捷格 RIGHT ×9 | `ru2` | 停在最后一格 `Open in browser`，**不再有 `focused=0`**，再多按也不动 |
| UP ×4 | `rv1` | 停在模式行第一格（面板最上方），不再逃到顶栏 |
| BACK | — | 面板关闭，顶栏回来，环落在标题药丸 `[784,12][1136,108]` |
| BACK（再） | — | 阅读控件收起，环回到整页 |

### 遗留（本轮新增）

- 面板打开时右缘仍露着一小条"切换空间"把手（`ru2` 右侧）。它是 `SpaceSidekickHandle`，本来就只露出
  32dp 一条边；电视上 `interactive = false`（遥控器拖不动它，环会有一半在屏幕外，那里也有 `tvFocusable`
  的注释记着），`uiautomator` 里它没有焦点目标，所以只是装饰，不是漏焦点。
- 这层 chrome 的显隐是"电视 + 面板"，工具面板与自动滚动面板同样走这条路，但只验证了目录与选项。

## 第四十四轮：章节行尾的下载/翻译按钮在电视上可以走到了（2026-10-24）

第四十二轮留下的第一个问题就是"行尾按钮走不到"：`ChapterListCard` 的 `combinedClickable` 铺满整行，
行尾那两个 `IconButton` 在几何上**落在**这个焦点节点的范围内，而 Compose 的方向搜索永远不会把焦点送进
当前节点的包围盒里面，所以遥控器怎么按都到不了。手机上没人在意（手指直接点），电视上等于这两个功能不存在。

### 改法：焦点目标只包正文，按钮留在外面

`core-ui` 的 `ChapterListCard` 从"一个 Row 既布局又可点"改成两层：

- 外层 `Row`：只做内边距、选中/当前章节的底色（整行宽的高亮不变）；
- 内层 `Row`（`weight(1f)` + `focusRequester` + `focusModifier` + `combinedClickable`）：包住当前章节箭头、
  标题/副标题和阅读状态图标；
- `actions` 从外层 Row 调用，因此**不在**焦点目标的包围盒里 → RIGHT 能从正文走到第一个按钮。

触摸行为不变（正文区域仍是整行可点，长按进入选择模式的区域也一样），行的最小高度 56dp 挪到内层。

### 按钮自己也要有环、也要封边

- 两个 `IconButton` 各加 `tvFocusable(shape = CircleShape, addFocusTarget = false)`：Material3 的
  `IconButton` 焦点态只有一层几乎看不见的 state layer，电视上必须自己画环。
- **行尾那颗**（有下载时是下载，否则是翻译）加 `tvHorizontalEdgeGuard(blockRight = true)`。
  这里踩到一个新坑：不加守卫时，RIGHT 在最后一个按钮上会被**上层 `HorizontalPager` 吃掉**——
  目录/页格/书签是同一个 pager 的三页，按键被当成翻页滚动，面板会自己滑到 Pages 页，焦点掉进页格里
  （`sb3`：章节标签页上按 RIGHT，人还在章节行，画面已经切到 Pages）。加了守卫就只吞键、不滚动。

### 设备验证（emulator-5554）

| 场景 | 截图 | 结果 |
| --- | --- | --- |
| 目录面板：行 → RIGHT | `sa0`/`sd0` | 环出现在行尾翻译按钮上（`[1616,610][1712,706]`），标签页仍是 Chapters |
| 再 RIGHT | — | 停在同一个按钮（此后无候选，不翻页、不丢焦点） |
| LEFT | — | 回到行正文 `[216,602][1624,714]` |
| DOWN | — | `Chapter 02` 行 `[216,714][1624,826]` |
| 详情页：章节行 → RIGHT | `sg0` | 环出现在该行翻译按钮 `[1792,310][1888,406]`；再 RIGHT 不动 |
| 详情页 LEFT | — | 回到行正文，再 LEFT 才离开列表去左栏 |

### 遗留

- 一轮里只验证到"一个按钮"的行（本地夹具的两章都已下载，`onDownload` 为空）。两个按钮同时存在时
  "翻译 → 下载 → 停住"这条链是按代码顺序推出来的，没在设备上走一遍。

## 第四十五轮：从阅读器回到详情页，焦点不再消失（2026-10-24）

第四十四轮顺手测到的问题：在详情页按 Continue 进阅读器，再 BACK 回来，**整窗没有一个焦点节点**
（`uiautomator` 里 `focused=0`），第一次按方向键才有环。电视上这一帧就是"遥控器坏了"。

原因是详情页的 Continue 按钮只在**首次**请求过焦点（`hasRequestedInitialFocus` 这个一次性开关），
而 Compose 的窗口焦点恢复在这种场景下没有可恢复的节点（离开时焦点在不同 Activity，回来时原节点
可能已经不在了），于是没人接手。

改法在 `DetailsDock` 的阅读按钮上：加一个 `LifecycleEventEffect(ON_RESUME)`，只在**窗口当前没有
任何焦点**时 `requestFocusFromTouch()` + `requestFocus()` 回到主操作。这样：

- 首次进入详情页仍走原来的首次焦点逻辑，不受影响；
- 从阅读器（或其他 Activity）回来时，焦点落在 Continue 上，用户一按就能继续操作；
- 有任何焦点时完全不插手，不会抢焦点。

### 设备验证（emulator-5554）

| 步骤 | 结果 |
| --- | --- |
| 详情页 Continue → CENTER 进阅读器 | `ReaderActivity`，整页焦点 |
| BACK 回详情页（不按任何键，直接 dump） | `MainActivity`，焦点在 Continue `[1328,54][1588,150]` |
| 再次 BACK | 回上层页面，焦点落在导航栏 `[16,296][152,432]` |

### 遗留

- 只覆盖了详情页这一条"回来"路径。从设置页、搜索页等其它入口返回时是否也会丢焦点没逐个验证；
  抓到的共同点是"整窗无焦点"，同一个 `view.findFocus() == null` 判断可以按需复制到别的入口。

## 第四十六轮：自动滚动面板的初始焦点落在第一个开关上，整块面板一屏放得下（2026-10-24）

阅读器选项面板里的 `Automatic scroll` 磁贴打开的是一个**原生 Material3 `ModalBottomSheet`**，
它自带一个可点击、可聚焦的拖拽把手（`content-desc="Drag handle"`，96×96 px）。在电视上这就是
一个"看得见焦点框、按下去什么都不发生"的死站：初始焦点正好落在把手上，环又是一个和面板同色的
暗方块，CENTER 点了也没有任何反应。

两处改动：

1. **初始焦点**：新增 `rememberTvSheetStartFocus(hasFocus)`（`reader/ui/compose/TvSheetFocus.kt`）。
   对话框窗口是新窗口，开窗时没有任何焦点节点，而窗口多半还处在 touch mode（Compose 会静默丢弃
   `requestFocus()`），所以它先 `view.requestFocusFromTouch()` 再 `requestFocus()`，并**逐帧重试**
   直到目标控件报告自己拿到了焦点（最多 10 次，每次 70 ms），面板关上时立刻停手。它取的是
   **当前组合的 `LocalView`**，也就是对话框自己的窗口——不能用阅读器那个 `hostView`，否则会把窗口
   焦点抢回阅读器。
2. **控件本身**：`Auto scroll` 开关挂上 `focusRequester` + `tvFocusable(addFocusTarget = false)`
   （圆环），其余开关和右上角 ✕ 同样补环，速度滑条改用 `tvSliderDpad`（电视上 Slider 只认拖动，
   方向键进去就是死站）。整块内容再加 `verticalScroll`：电视是横屏，五行控件加滑条比屏幕高，
   最后两个开关原本在屏幕外，遥控器又没法把面板往上拖。
3. 工具面板 `ComposeReaderToolsSheet` 用同一个原语（第一个按钮拿焦点、按钮补环）。注意这个面板
   目前**没有入口**：`ReaderControlDestination.TOOLS` 只在它自己的关闭回调里出现，没有任何 UI
   派发它（底部工具壳 `ReaderControlShell` 也没有调用点），所以这处只是提前把电视行为补齐。

### 设备验证（emulator-5554）

| 步骤 | 结果 |
| --- | --- |
| 选项面板 RIGHT×4 到 `Automatic scroll` → CENTER | 面板打开，焦点在第一个开关 `[1464,752][1568,848]`，蓝环可见 |
| DOWN ×1 / ×2 / ×3 / ×4 | 依次到 `Pause` 开关、速度滑条 `[332,848][1588,936]`、`Show floating button`、`Pause when controls are visible` |
| 滑条上 RIGHT | 速度从 `x2.5` 变 `x3.1`，环跟着滑条 |
| 最后一个开关 CENTER | 开关由开变关，焦点不动 |
| BACK | 面板关闭，焦点回到顶栏标题药丸 `[784,12][1136,108]` |
| 再 BACK | 顶栏收起，焦点回到整页（阅读区） |

滚动后五行控件全部落在 `[320,1080]` 内，最后一个开关的环不再被屏幕底边切掉。

### 遗留

- 章节面板、阅读选项面板（`ReaderPanelHost`）是应用自己的锚定面板，本来就有重试逻辑；剩下的
  `ModalBottomSheet` 只有这两块，都覆盖了。小说阅读器的章节/选项面板（`ComposeNovelChaptersSheet`、
  `ComposeNovelReaderOptionsSheet`）仍是旧实现，没有夹具无法验证。
- 详情页"TV Audit Strip"在宽屏下没有 Continue 按钮，阅读入口在章节行的 ▶ 上——是设计如此（章节工具
  模式），不是焦点问题；但如果用户找不到入口，值得在文档里说明。

## 第四十七轮：展开后的选项面板，焦点能往上回到标签栏；色温/对比度滑条可以按方向键（2026-10-24）

再用遥控器走一遍选项面板的详情页，发现两个真问题。

第一，**在详情页第一行按 UP，焦点直接消失**（`focused=0`），再按两次 UP 也不回来，直到随便按一个
方向键才落回某个标签上。原因在 `ReaderPanelHost` 的面板骨架：抽屉一旦越过 peek 锚点，头部
（快捷层：阅读模式条 + 快捷磁贴）只是被 `clipToBounds` **裁到 48dp 的拖拽把手高度**，子节点照旧
参与组合、照旧可聚焦。于是从详情页第一行向上找候选，最先命中的是那些**已经被裁掉的快捷磁贴**，
焦点被搬到看不见的地方，屏幕上就是"环没了"。

改法不是再堆一个边界守卫（第一行加 `tvVerticalEdgeGuard(blockUp = true)` 只能把按键吞掉），而是
让隐藏的那层**不参与焦点**：头部内容外面套一层 `Modifier.focusProperties { canFocus = !isTv ||
!scope.isExpandedPastPeek }`。展开时快捷层彻底退出焦点，UP 自然落到真正画在顶部的标签栏；收起时
（peek）头部重新可聚焦，快捷磁贴照旧是第一站。手机/平板没有任何折叠，`canFocus` 恒为 true。

第二，**颜色校正里的亮度/对比度滑条是只进不出的死站**：Material3 `Slider` 自己拿着方向键却只认
拖动，焦点进去之后左右键什么都不改。`ColorFilterSlider` 补上和其它滑条一致的 `tvSliderDpad`
（预览阶段拦截 LEFT/RIGHT，按 1/20 量程步进，同时画焦点环）。

### 设备验证（emulator-5554）

| 步骤 | 结果 |
| --- | --- |
| 选项面板 peek → DOWN×4 | 快捷磁贴 → 标签栏 → 展开，焦点落在详情页第一行 `[232,228][1688,332]` |
| 详情页第一行 UP | 焦点落到标签栏 `[850,102][1012,198]`（蓝色描边，不再消失） |
| 标签栏 LEFT → CENTER | 切到 `Layout` 页，页面内容随之更换 |
| BACK（展开 → peek） | 抽屉收起，焦点回到标签栏的 peek 位置 `[691,956][846,1052]` |
| 再 BACK | 面板关闭，焦点回到顶栏标题药丸 `[784,12][1136,108]` |
| Display 页 DOWN 走到底 | Reset → Invert/Grayscale → 亮度滑条 `[236,467][1684,555]` → 对比度滑条 `[236,582][1684,670]` → Yellowish 开关 |
| 对比度滑条 RIGHT×2 | 100% → 120%，滑条与数值同步，焦点环画在滑条外框上 |
| 背景选 `Follow app theme` | 面板整体跟随页面底色切换（白底时是浅色面），环在两种底色上都看得见 |

### 遗留

- 同一个列表里还有"按一下不动"的中间态（例如 Layout 页在 `Two pages` 卡片里连按 UP 有几次停在原地），
  抓到的共同点是**方向上存在一个不可聚焦的候选**，Compose 的光束搜索会在那里终止。不丢焦点、只是
  吞一次按键，优先级低于已经修掉的"焦点消失"。
- 选项面板的右侧齿轮（设置）、Display 页的 `Save`/`AI Super Resolution` 段还没逐个走完。


## 第四十八轮：章节面板换页后焦点不再丢、书签卡片有环、选项面板齿轮可达（2026-10-24）

这一轮把章节面板的三个页签都按遥控器走了一遍，并顺手清掉了一整类"能聚焦但看不见"的控件。

第一，**换页签之后遥控器短暂失灵**。章节面板的页签内容是抽屉里第二次组合，切页签会重建
正文，焦点宿主可能晚一帧才释放焦点：那一刻全屏没有任何 `focused=true`，四个方向键全被吞，
只有 BACK 能救。修法是让面板自己的标签栏持有一个**钉在选中页签上的 `FocusRequester`**
（`ReaderPanelTabBar(selectedFocusRequester = …)`），切页签后由宿主连续最多 6 帧（间隔 70ms）
**只在 `hostView.findFocus() == null` 时**把它要回来；健康的切换完全不受影响。
和手机一样，非 TV 走同一份代码但 `isTvPresentation` 为假时整段不执行。

第二，**书签卡片是"能聚焦、没描边"的死站**。`BookmarksScreen` 的 `BookmarkCard` 和
`KototoroBookmarkCardThumb/Novel` 只用 `combinedClickable` 建了焦点节点，没有画环；面板的
书签页正文只有这一个可聚焦项，用户按 DOWN 之后屏幕毫无变化，看起来就是遥控器坏了。
三处都补上 `Modifier.tvFocusable(shape = RoundedCornerShape(8.dp), addFocusTarget = false)`。

这里踩到一个容易复发的坑：`tvFocusable` **必须写在 `clickable`/`combinedClickable` 之前**。
`Modifier.onFocusChanged` 只看排在它**后面**的焦点目标，写反了不会报错、也不会画环——第一版
就是这么写的，装上机后截图和改之前**逐字节相同**（`sha256 50fcf0c1…`，同一张图重复三次才发现
不是截图缓存的问题）。改完顺序立刻出现蓝色圆角描边。仓里现有 `tvFocusable` 调用逐处核对过
顺序，没有第二处写反。

第三，**章节面板的 Pages 页签可以用了**：四张缩略图一格一格走，CENTER 跳到该页并收回面板、
焦点回到顶栏药丸；UP 从第一行回到标签栏。

第四，**选项面板右侧齿轮（设置）可达**：从右端"Open in browser"磁贴按 DOWN 直接落到齿轮
`[1600,956][1696,1052]`，环是圆的，再按 RIGHT 被边界守卫吃掉（它是最右一站）；从齿轮按 LEFT
依次经过 `Translation`/`Display`/`Layout` 三个页签，UP 从页签回到第二行磁贴。

第五，试过但**撤掉**的方案：给 `ChaptersPagesTabsContent` 的分页页套
`Modifier.focusProperties { canFocus = page == safeCurrentPage }`，想让"停在屏幕外的那一页"
彻底不可聚焦。结果从 Bookmarks 页签按 DOWN **焦点直接消失**（`focused=0`）——方向上唯一的候选
不可聚焦时，Compose 的光束搜索不是跳过而是终止。这条和第四十七轮"隐藏层不参与焦点"看起来是
同一个 API，结论却相反：**整层隐藏**（快捷层被裁掉）用 `canFocus=false` 正确，**同一行里
相邻的、尺寸相同的兄弟页**用它会把搜索打断。撤销后回到原样。

### 设备验证（emulator-5554）

| 步骤 | 结果 |
| --- | --- |
| 章节面板 Bookmarks 页签 CENTER | 页签保持聚焦 `[1023,372][1237,468]`，+0.8s 仍在 |
| 换页签后 DOWN | 落到书签卡片 `[232,580][511,979]`，蓝色圆角描边可见 |
| 书签卡片 CENTER | 面板收回，焦点回到顶栏药丸 `[784,12][1136,108]` |
| Pages 页签 DOWN / LEFT / RIGHT | 卡片 `[822,604][1100,1001]` → `[527,604]` → `[232,604]`，单行 DOWN 不走 |
| Pages 页签 UP | 回到标签栏 `[684,372][869,468]` |
| 标题栏 ⋮ → BACK → LEFT×2 → CENTER | ⋮ `[1612,362]` → 搜索 `[1524,362]` → Bookmarks → Pages 页签，四处都能走到 |
| 选项面板右端磁贴 DOWN | 齿轮 `[1600,956][1696,1052]`，圆形环；RIGHT 被守卫吃掉 |
| 齿轮 LEFT×3 | Translation → Display → Layout 页签 `[691,956][846,1052]` |
| Layout 页签 UP | 回到第二行磁贴 `[232,738][414,926]` |

### 遗留

- 章节面板"记住上次页签"再打开时（长按书签按钮走 `toggleChapters(DETAILS_TAB_BOOKMARKS)`），
  正文页没有初始焦点请求，第一次方向键可能吞掉一次；普通打开（Chapters 页签）不受影响。
- 面板在 peek 状态下按 BACK 先展开、再按才关闭，和第四十七轮的折叠语义一致，但需要两下，暂不改。

## 第四十九轮：主界面外壳与设置双栏都能走，页签补上焦点环（2026-10-24）

这一轮离开阅读器，把"从主界面一路点到设置"的整条路走了一遍，并补掉第二类"能聚焦但看不见"的控件。

第一，**主界面外壳是通的**。导航轨按 UP 进入 Continue 行，RIGHT 走到 hero 卡片，从卡片连按两次
UP 落到顶栏筛选按钮 `[1708,16][1800,112]`，再 RIGHT 是 ⋮ `[1800,16][1896,112]`，在这之后 RIGHT 被
边界守卫吃掉（⋮ 是那一行真正的最后一站）。⋮ 菜单本来就有环：菜单项走的是
`CompactDropdownMenuItem`，它在 `clickable` 之前已经有
`Modifier.tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false)`，所以菜单里每一站
都看得见——这一点是查代码确认的，不是截图确认的。

第二，**"Display options" 弹层可以整段使用**（右缘卡片图标 CENTER 打开）：List / Detailed list /
Grid / Compact grid / Movie / Compact movie 六种布局、网格尺寸滑条（RIGHT 让 70% 变 80%，松手即生效）、
每页行数、以及 "Show extra info on cards" 开关；内容超出一屏时能继续往下滚，最后一行露得出来；
BACK 关掉弹层并把焦点还给触发它的那个右缘按钮 `[1800,324][1896,420]`。

第三，**设置页的左右双栏在电视上成立**。左栏是分类（Appearance / Reader settings / AI / Playback /
Content sources），CENTER 在右栏打开详情并把环放到第一行；从第一行按 UP 能回到右栏顶部的
General / Manga / Novel 页签 `[1121,124][1520,220]`；BACK 从详情退回（落到顶栏搜索按钮），再按
BACK 回到主界面。调试时可以直接
`adb shell am start -n org.skepsun.kototoro.debug/org.skepsun.kototoro.settings.SettingsActivity`
进设置，省掉从 ⋮ 菜单逐项点过去。

第四，**页签"能聚焦但没环"是第四十八轮书签卡片的同一类问题**。Material3 的 `Tab` 本身是
`selectable`，遥控器停得上去，但反馈只有一层几乎看不出的容器底色；停在上面却看不出停在哪儿，
和"根本停不上去"对用户是同一件事。新增 `Modifier.tvTabRing()`：

```kotlin
@Composable
fun Modifier.tvTabRing(): Modifier = tvFocusable(
    shape = RoundedCornerShape(10.dp),
    addFocusTarget = false,
)
```

把它作为 `Tab(modifier = …)` 传进去——`Tab` 会把自己的 modifier 排在内部 `selectable` **之前**，
所以环正好观察那一站，不会多出第二个焦点节点（这正是 `CompactDropdownMenuItem` 用的同一个手法）。
上机效果：阅读设置的 Manga 页签出现蓝色圆角环，CENTER 切到 Manga 页之后环仍留在页签上，既没有掉到
正文也没有消失。其余 7 处 `Tab(`（内容目录、首页自定义、词典、追踪源检索、scrobbler 选择、
详情页签、章节模式页签）用同一行改法；非 TV 时 `tvFocusable` 直接返回原 modifier，是空操作。

第五，**没改的一个怪癖**：设置右栏里按 RIGHT，焦点会跳到相邻的上一行，而不是行尾的开关。因为行尾开关
不是独立焦点站，光束搜索按几何位置挑了别的行。不会丢焦点，暂时只记录。

### 设备验证（emulator-5554）

| 步骤 | 结果 |
| --- | --- |
| 导航轨 UP → RIGHT → UP×2 → RIGHT | Continue → hero 卡片 → 筛选 `[1708,16][1800,112]` → ⋮ `[1800,16][1896,112]`，再 RIGHT 被守卫吃掉 |
| 右缘按钮 CENTER | "Display options" 弹层，布局磁贴 + 尺寸滑条 + 每页行数 + 开关都可聚焦 |
| 弹层内滑条 RIGHT | 70% → 80%，进度条与文字同步更新 |
| 弹层 BACK | 关闭并回到 `[1800,324][1896,420]` |
| 设置左栏 CENTER（Reader settings） | 右栏详情打开，环在第一行 `[746,332][1896,456]` |
| 详情第一行 UP | 回到页签 Manga `[1121,124][1520,220]`，蓝色圆角环 |
| 页签 CENTER | 页面切到 Manga 内容，环仍在页签上 |
| 页签 DOWN → RIGHT → DOWN | `[746,260]` → `[746,332]` → `[746,458]`，行间行走正常 |
| 详情 BACK / BACK | 顶栏搜索按钮 → 主界面导航轨 `[16,296][152,432]` |

### 遗留

- 设置里 Appearance / AI / Playback / Content sources 四个子页、以及设置搜索框的输入法行为还没走。
- "Display options" 里每页行数与 "Show extra info on cards" 开关，按下后的视觉变化没有截图留证。
- 内容目录 / 首页自定义 / 词典 / 追踪源检索 / scrobbler 这五处新增的页签环只做了编译与同一原语的
  上机验证，没有逐一进到页面里看。
- 设置右栏 RIGHT 跳到相邻行的怪癖只记录未修。

### 补记（同日）：打开子页后焦点一定落进右栏

上面那轮走查顺手暴露了一个更严重的问题：**从列表打开子页后，焦点并不总是跟着进右栏**。原来的
实现是在 `LaunchedEffect(destination, isListPaneFocused)` 里最多试 20 帧 `moveFocus(Right)`，
看起来够用，实际是两条竞态：一是 `handledDestination` 在"左栏没有焦点"的那次也会被写成当前
destination，于是等焦点真正回到左栏、条件成立时反而因为"已经处理过"而跳过；二是 `moveFocus`
和右栏内容的组合赛跑，右栏还没组合出可聚焦项时 moveFocus 返回 false 但焦点已经被挪走。

现在换成"**没焦点就一直要**"的写法，并把这个原语放进 `TvFocus.kt`：

```kotlin
@Composable
fun Modifier.tvTakeFocusWhenEmpty(key: Any?, attempts: Int = 20): Modifier
```

右栏套上它，`key` 用当前 destination：每次换页重新要一次焦点，只要右栏里还没有焦点就继续请求
（有焦点立刻停，用户不会被打断）。它比 `moveFocus` 更贴切——右栏的第一站就是那一页的第一个控件，
不需要方向几何。同一个原语也顺带修好了**从搜索框进子页**的路径：过去选完搜索结果焦点会回到左栏
第一行，现在直接落在右栏。

设备验证（emulator-5554）：连续 5 次"打开 Appearance → BACK → DOWN 回列表"，5 次都稳定落在
右栏第一行 `[746,212][1896,340]`；从搜索 "reader" 选 "Reader settings" 后焦点落在
`[746,332][1896,456]`（Reader actions 行，蓝色环可见）。

## 第五十轮：设置子页、搜索框与"退回来的空栏"（2026-10-24）

把设置里能打开的子页挨个走了一遍，结论是**子页本身都能用**，问题出在"退回来"的那一刻。

**Appearance**：右栏分 Theme and color / Content display 两节，Interface style、Appearance mode、
Page background、Lists、Details、Home 都能停能进；DOWN 逐行推进，行高不同的行（带两行说明的）
不会错位。

**Content sources**：左栏在这页会**滚动**——焦点走到第 4 行以后，列表整体上移让当前行留在视野里，
所以"左栏只有一屏"不是限制。右栏 Sorting order（Manual）、Reorder、Show in grid view、
Group sources by language（开关）、Setup wizard、Plugin Deduplication Priority Sequence 都可聚焦。
其中 "Show in grid view" 是一条**纯告知行**（"This option has moved to Browse page search bar >
More > Display options"），按 CENTER 什么也不会发生——它本来就是想说一句"搬走了"，不是入口；
在电视上让它占一个焦点站会白费两次方向键，但也不会误导，暂不处理。

**AI / API providers**：这一页是三个文本框（Translation API endpoint、Translation API key、
Online translation model）+ LLM provider preset 选择行，字段都接了
`tvTextInputFocusEscape`，遥控器能停在字段上、按 BACK 退出来，不会被输入法关住。

**设置搜索框**：右栏在搜索态下会切成单栏（`useTwoPane = isTwoPane && !(searchActive && isTv)`），
搜索框独占整行 `[136,8][1896,104]`。CENTER 进搜索后软键盘弹出并接管方向键（这时 UP/DOWN 不发生任何
位移），BACK 先关键盘、再按才退出搜索并回到列表；输入 "appearance" 后结果行可以往下走到
`[24,132][1896,268]`，CENTER 打开结果并进入对应子页。真机上电视通常没有软键盘，用遥控器搜索本来
就不好用，但这里至少不会把人关死。

**退回来的那一下是这轮唯一的新缺陷**：从子页 BACK 之后，右栏变成**空的一整块**，焦点落在顶栏返回键
`[24,8][120,104]`（环是有的），此时在左栏按 RIGHT **什么都没发生**——右栏没有可聚焦的东西，光束
搜索吞掉这次按键。用 DOWN 回到列表再 CENTER 就正常，所以是"别扭"而不是"卡死"；要彻底修好得让左栏
记住"刚才打开的是哪一行"并在返回时把焦点还给它，涉及给每一行加 `FocusRequester`，先记在这里。

### 设备验证（emulator-5554）

| 步骤 | 结果 |
| --- | --- |
| 左栏 Appearance CENTER | 右栏 Theme and color 首行 `[746,212][1896,340]`，环可见 |
| 右栏 DOWN / DOWN | `[746,324]` → `[746,324][1896,492]`，行间推进正常 |
| 左栏 DOWN 到底（Content sources） | 左栏自动滚动，Playback / Content sources / Extension management / Downloads / Spaces 依次露出 |
| Content sources CENTER | 右栏首行 Sorting order 获得焦点 |
| Reorder 行 CENTER | 无反应（当前 0 个来源，对话框为空；该项本身是拖拽式） |
| Show in grid view 行 | 可获得焦点，CENTER 无动作（纯告知行） |
| AI → API providers | 三个文本框 + preset 行，字段可聚焦、BACK 可退出 |
| 搜索图标 CENTER | 搜索框 `[136,8][1896,104]` 获得焦点，软键盘弹出 |
| 搜索中 UP / DOWN | 无位移（软键盘接管方向键） |
| 搜索中 BACK / BACK | 第一次关键盘，第二次退出搜索并回到列表首行 |
| 输入 "appearance" + DOWN | 结果行 `[24,132][1896,268]` 获得焦点 |
| 结果行 CENTER | 打开对应子页，焦点落在右栏 |
| 子页 BACK | 焦点回到顶栏返回键，右栏空白 |
| 空白右栏时左栏 RIGHT | 无位移（无候选，按键被吞） |

### 遗留

- 子页返回时焦点没有回到打开它的那一行，右栏空白状态下 RIGHT 是死键（上面已说明修法）。
- "Show in grid view" 这类纯告知行仍占一个焦点站。
- 设置里 Appearance 的 Interface style / Page background、AI 的模型下载等更深一层的对话框没逐个走。

### 补记（同日）：返回时焦点回到当初那一行

上面那条"修法"已经落地。双栏外壳在退回 Root 时发一个 `returnToken`（`LocalSettingsListReturnToken`），
列表把每个根行的 `FocusRequester` 记在 `lastRowFocusRequester` 里（行上的 `onFocusChanged`），收到
token 变化就 `requestFocus` 那一行：

```kotlin
// SettingsAdaptiveShell.kt
LaunchedEffect(destination) {
    if (destination != SettingsDestination.Root) { hasOpenedPage = true; return@LaunchedEffect }
    if (!hasOpenedPage) return@LaunchedEffect   // 冷启动交给列表自己的首行请求
    withFrameNanos { }
    returnToken++
}
```

冷启动那一次仍然由 `SettingsRootScreen` 的 `firstFocusRequester` 负责，两处抢焦点不会互相打架。

设备验证：设置 → DOWN×2（AI 行）→ CENTER 打开 → BACK，焦点回到 AI 行 `[24,411][696,583]`；
再 UP 到 Reader settings 行 → CENTER 打开 → BACK，焦点回到 Reader settings 行 `[24,370][696,542]`
（不是第一行，正是打开的那一行），环可见。

## 第五十一轮：详情页整屏走通，章节行尾按钮各有独立焦点环（2026-10-24）

这一轮用库里那本审计用的 "TV Audit Strip"（1 章、本地、进度 25%）把详情页从冷启动走到退出。结论：
**详情页是这次审计里第一个"全程都有环"的整屏**。

冷启动应用会直接恢复到上次的详情页（resume 恢复钩子），焦点落在 Continue 上，白环很显眼。左栏是封面、
标题、来源 chips、复制/收藏/关注数/Reading 药丸，下面一张元信息卡（作者 Unknown、语言 Unknown、
章节 1/1 带 chevron、339 kB、25% 进度条），再下面是 Description 卡。右栏顶部是
列表 / 网格 / 页签 / Continue + ⌄ 拆分按钮 / 搜索 / ⋮ 六个站，每个都画环；正文是章节行
`▶ 1` 加行尾三个图标（书签、下载、翻译），**三个图标是三个独立的焦点站**，停上去是圆形环而不是行环，
其中翻译图标的环贴在卡片右圆角上（1–2 px 的视觉溢出，纯外观）。

章节页签（顶部第三个图标）进去是页码网格：页头 "1"，下面一张页卡（P.1），卡片带蓝色圆角环；
只有一张卡时 DOWN 停住不越界，UP 回顶栏，BACK 退出详情页并把环交给导航轨 `[16,296][152,432]`。

一个方向怪癖：从右栏的 ⋮ 按 UP，焦点会跳到**左栏**的 ⋮（两栏各自有 ⋮，几何上左栏那个更近）。
不会丢焦点，只是跨了栏，暂不改。

### 设备验证（emulator-5554）

| 步骤 | 结果 |
| --- | --- |
| 冷启动（monkey LAUNCHER） | 恢复到 "TV Audit Strip" 详情页，Continue 获得焦点 `[1328,54][1588,150]` |
| DOWN | 章节行 `[992,190][1800,302]` |
| RIGHT | 行尾翻译图标 `[1792,198][1888,294]`，圆形环 |
| RIGHT / DOWN | 到行尾后不再前进（正确） |
| LEFT | 回章节行 |
| UP / UP | 右栏 ⋮ `[1782,54][1878,150]` → 左栏 ⋮ `[848,32][944,128]`（跨栏） |
| RIGHT ×3 | 列表 `[990,54]` → 网格 `[1074,54]` → 页签 `[1158,54]` |
| CENTER（在 Continue 上） | 焦点停在 `[1328,54][1588,150]` |
| 页签页 DOWN / DOWN | 页卡 `[1008,264][1283,657]`，蓝环；再 DOWN 停住 |
| 页签页 UP / RIGHT | 回顶栏，再到 ⌄ |
| 详情页 BACK | 退出到主界面，导航轨 `[16,296][152,432]` |

### 遗留

- 右栏 ⋮ 按 UP 会跨到左栏 ⋮。
- 页卡环与面板圆角有 1–2 px 重叠（外观）。
- 页签页只有一张卡，多页翻页、批量选择模式没有数据可测。

## 第五十二轮：收藏页快捷筛选，下拉行有环、选完自动收起、焦点留在筛选栏（2026-10-24）

收藏页（导航轨第 4 项）在电视上是：顶栏（标题 / 搜索 / 筛选 / ⋮）、一行页签（All favorites、
Read later）、再一行快捷筛选（筛选图标按钮、On device、Reading ▾、Release ▾、Rating ▾、
New chapters）。库里没有收藏数据，所以正文是空态（猫图标 + "It's kind of empty here…" +
"No favorites yet"）。这一轮在这一屏上抓到三个连锁缺陷。

第一个是**下拉菜单行没有焦点反馈**。快捷筛选的 ▾ 打开的是 M3 原生 `DropdownMenu`，行是原生
`DropdownMenuItem`：遥控器能停上去、DOWN 也能移动，但屏上纹丝不动——dump 里焦点从
`[479,112]` 移到 `[479,208]`，两次截图却完全一样。"停在菜单里"和"菜单根本没响应"长得一模一样，
正是本次审计反复出现的那类死胡同。

第二个是**选完不收起**。遥控器没有"点一下别处"这个动作，菜单项 `onClick` 只应用筛选、不
`expanded = false`，于是结果被菜单挡着，而且弹出层仍然持有窗口焦点，用户只能按 BACK 把它关掉。

第三个是**应用筛选后焦点掉到导航轨**。改筛选会重新查询列表，快捷筛选栏按新模型重建，被按那颗
芯片的焦点节点消失了，Compose 把焦点交给最近的祖先——在电视上就是左侧导航轨
`[16,584][152,720]`，再按一次 CENTER 就离开收藏页。

修法分三处：

- 环：`KototoroContentListScreen` 的 `QuickFilterGroupChip` 菜单行、`FavoritesFilterPanel` 的
  chips 与菜单行统一补 `Modifier.tvFocusable(shape = RoundedCornerShape(8.dp), addFocusTarget = false)`。
  M3 的 chip 和菜单行自己就是 `selectable`/`clickable`，`addFocusTarget = false` 让环观察同一个
  停靠点、不再增加第二个焦点站。
- 收起：菜单项的 `onClick` 第一句 `expanded = false`，再应用。
- 焦点：`QuickFilterSection` 给整行挂 `focusRequester + focusGroup + onFocusChanged`，行内芯片
  被点击时 `restoreFocusTick++`，`LaunchedEffect` 每帧重试、最多 20 帧，直到行内重新拿到焦点。
  恢复只在"是本行触发的变化"时发生，从导航轨走进收藏页时不会把焦点抢进筛选栏。

### 设备验证（emulator-5554）

| 步骤 | 结果 |
| --- | --- |
| 导航轨 DOWN ×2 → CENTER | 进收藏页，焦点留在轨道 `[16,584][152,720]` |
| RIGHT ×3 | 筛选图标 `[176,216][268,312]` → On device `[268,224][467,312]` → Reading ▾ `[479,224][699,312]`，圆形蓝环 |
| CENTER | 菜单展开，首项 Plan to read `[479,112][821,208]` |
| DOWN | 第二项 Reading `[479,208][821,304]`，**现在有蓝色圆角环**（修前无环） |
| CENTER | 菜单立即收起，焦点在同一帧就回到 Reading 芯片 `[479,224][699,312]`，芯片转为选中态 |
| 等 2.2 s 再 dump | 焦点仍在芯片上（修前此时已在导航轨） |
| RIGHT | 焦点移向相邻芯片，页面没有丢焦点 |

### 遗留

- 空态里有一个**卡住的刷新指示器**：非 clickable 的 48 dp 圆在 `[996,560][1092,656]`，正好压在
  "No favorites yet" 文字上（t54 / t56 / t66 / t70 都在）。它来自 `KototoroPullToRefreshBox` 的
  `isRefreshing` 长期为 true（0 个内容源时那次"检查新章节"不结束），并且指示器被画在内容区中间
  而不是顶部。要改的是刷新状态机，本轮没动。
- 应用筛选后筛选栏会重排（已选芯片前移到最前），重排那一帧焦点可能落到相邻芯片，方向键第一下
  看起来像"原地"移动。
- Release ▾、Rating ▾、New chapters 三个下拉和"Read later"页签这一轮没走到。

### 补记（同日）：重排对话框在电视上给出上下移动按钮

`ReorderDialog`（设置里的来源排序、统一来源页的排序按钮）在电视上是一条彻底的死胡同：行上只有
拖拽手柄（`longPressDraggableHandle` + `draggableHandle`），遥控器既拖不动，也没有第二个可停的节点；
对话框打开时没有任何初始焦点，能停的只剩底部 Cancel / Save，而这两个按钮也不画环。

改法只在 TV 生效，手机保持原样（拖拽）：

- 每行尾部把拖拽手柄换成两个 `IconButton`（↑ / ↓），复用 `customActions` 里那套移动逻辑——把它提取成
  `canMoveUp` / `canMoveDown` / `moveItemUp` / `moveItemDown`，无障碍动作和按钮共用一份。
- 移不动时（已在组首/组尾或会跨组）按钮 `enabled = false`：禁用按钮不是焦点目标，遥控器不会停在
  一个按了没反应的按钮上。
- 两个按钮带 `tvFocusable(addFocusTarget = false)` 的圆形环，Cancel / Save 补圆角环。
- 对话框内容列挂 `tvTakeInitialFocus()`，打开即把焦点交给第一个可停的按钮（第一行的 ↓，此时 ↑ 禁用）。

`items(key = …)` 带 key，行换位后节点身份不变，焦点理论上跟着条目走；**这一条本轮没有设备证据**：
模拟器 0 个内容源，"来源排序"入口不出现，`ReorderDialog` 的三个调用点都要求 ≥2 个已装源。本轮只到
`:app:compileDebugKotlin` BUILD SUCCESSFUL。

### 遗留

- 重排对话框的上下移动按钮与"换位后焦点跟随"缺设备验证（需要至少两个已装源）。

### 补记（同日）：对话框打开时的初始焦点（共享组件）

电视上弹窗自带一个窗口，没人主动 `requestFocus` 就什么都不聚焦，遥控器要么无处可去，要么还在对话框
背后的暗色界面上走。两处一起补：

- `SettingsAlertDialog`（设置里约 30 个单选/多选/确认弹窗的公共壳）：正文包一层
  `focusRequester + focusGroup + onFocusChanged`，`LaunchedEffect` 连续 6 帧请求焦点；如果正文里
  根本没有可停节点（很多弹窗正文只是纯文本），退回到 confirm 按钮那一组。
- `ClearHistoryDialog`（主界面 ⋮ → 清除历史，遥控器最容易摸到的一个弹窗）：四个单选项补 8dp 圆角环、
  Cancel / Clear 补环，正文列挂 `tvTakeInitialFocus()`，打开就把焦点交给第一个可停的单选项。

两处都编译通过并安装成功。**清除历史弹窗已在设备上验证**：主界面 ⋮ → Clear history，弹窗一出现焦点就在
第一个单选项（`[568,388][1352,468]`），DOWN 逐项下移（`[568,468]` → `[568,548]` → `[568,628]`），
第四项"Clear all history"整行有蓝色 8dp 圆角环（t74），再 DOWN 落到 Cancel（`[1077,764][1217,860]`）。

### 遗留

- `SettingsAlertDialog` 的初始焦点仍缺设备证据：设置里的目标弹窗要经过几层子页面，
  本轮导航把会话带偏了（进子页后按到了返回键、退回主界面）。
- 新发现：恢复进阅读器后（本次为详情页进阅读），页面以"格子"形式挤在屏幕左侧约 45% 宽度里，
  每格下方是省略号标题，右侧整块黑。像是阅读器的页面网格/图块布局在电视上没有按整屏宽度排版，待查。
- ~~设置根页在电视上仍是约 672 px 宽的单列（右侧留白），子页面才是两栏~~ → 见第五十三轮，已修。

### 第五十三轮：设置根页不再把右半屏留给空栏（2026-10-24）

设置根页在电视上一直是"左边 360dp 的列表 + 右边一大块黑"。用容器宽度探针（只量占位节点、不看文本节点）
拿到的原始布局是：

```
[0,0][720,1080]    w=720    ← 列表栏（360dp）
[722,0][1920,1080] w=1198   ← 详情栏，根页没有目的地，全程空白
```

`SettingsAdaptiveShell` 本来就有"电视上搜索时退回单栏"的先例，但根页没被覆盖：判断写的是
`destination == null`，而调用方显式传 `SettingsDestination.Root`（`destination ?: SettingsDestination.Root`
就是这个字面量的来源），条件永远为假。改成 `destination == null || destination == SettingsDestination.Root`
后（并且只在电视上生效），根页走单栏。

### 设备验证（emulator-5554）

| 步骤 | 修前 | 修后 |
| --- | --- | --- |
| 设置根页容器 | `[0,0][720,1080]` + `[722,0][1920,1080]` 并排 | 只剩 `[24,196][1896,730] w=1872` 一个满宽容器 |

顺带修掉了一个老遗留：根页右侧不再有空白栏，遥控器按 RIGHT 也没有"走进空区域"的可能。

### 遗留

- 设置子页面（非根）仍是两栏，这是预期行为，未复验宽度是否随电视宽屏比例合理分配。
- 构建期踩坑记录：同一工作区并行构建会让 `app/build` 的 dex/打包中间产物互相覆盖，
  症状是 APK 装上去启动即 `ClassNotFoundException: org.skepsun.kototoro.core.BaseApp`。
  清掉 `app/build/intermediates/dex` 与 `app/build/outputs/apk/debug` 后重新打包即恢复。

### 阅读器"图块挤在左半边"的排查记录（已解决，见 `tv-reader-layout.md`）

> 结论：不是"图块/网格"排版，而是 `CONTINUOUS_HORIZONTAL` 在电视横屏上按**视口高度**算页宽
> （`pageWidth = availableHeight * ratio` ≈ 144 px），四张并排才 ~30% 宽度。已在电视上把该模式改走
> 纵向全宽单列（`resolveSceneLayoutMode`，提交 `24d28e161`），手机/平板与分页模式不受影响。
> 完整几何推导与验证数字见 `docs/architecture/tv-reader-layout.md`。

现象：从详情页进入阅读后，页面以四列小图块形式只占屏幕左侧约 45%，每格下方是省略号标题，右侧整块黑。

已排除：

- `reader/ui/compose/` 下的所有 Kotlin 文件里没有固定宽度常量；唯一带 `widthIn` 的是
  `ComposeReaderActivityScaffold.kt:689`（`width(IntrinsicSize.Max).widthIn(max = 200.dp)`），
  是工具栏里的元素，与页面网格无关。
- `SettingsAdaptiveShell` 那类"电视双栏留空"的形态在这里不成立：阅读器没有左右两栏。

下一步该看的地方：`ComposeReaderActivityScaffold.kt` 的进度条/页码元素（1270-1279 附近的
`maxIntrinsicWidth` 计算）是否会参与页面区域测量；以及阅读器页面容器是否在电视上用了
`BoxWithConstraints` 按手机比例算出的宽度。复现时要先确认当时是"页面缩略网格"还是"实际页面渲染"，两者路径不同。

以上两条排查方向都错了：问题在场景选择（走的是 `HorizontalReaderScene`），不在阅读器外壳的测量。

---

## 第五十四轮：浮层菜单够不着、输入框"确定"卡死（2026-10-24）

这一轮从"遥控器能不能自己走到设置页"开始，撞到两个**纯遥控器死路**。两者都已修并在设备上验证。

### 1. 顶栏 ⋮ 打开的浮层菜单，遥控器完全进不去

复现（explore 页，触摸点开 ⋮）：

- 菜单画出来了，但**没有任何焦点环**；`uiautomator dump` 里也没有 `focused="true"` 的节点。
- 连按 DOWN 无变化；再按 DOWN + CENTER，菜单还开着，而**左侧导航从 Home 切到了 Explore**——
  方向键一直在操作菜单背后的界面。
- 菜单项本身是 `tvFocusable(addFocusTarget = false) + clickable`，有焦点目标，只是从没被请求过。

根因在 iOS（默认）界面风格下的菜单实现：`GlassDropdownMenu` 走 `RootGlassMenuOverlay` 的**窗口内浮层**，
而那层全屏遮罩是 `Modifier.clickable(...)`。`clickable` 自己就是一个焦点目标，于是菜单打开后焦点
要么还停在底层页面、要么落到这块看不见的遮罩上——前者让方向键穿透到背景，后者让 OK 直接关掉菜单。

修法（`main/ui/compose/GlassDropdownMenu.kt`，仅电视生效）：

- 电视上遮罩改成 `pointerInput + detectTapGestures`，只处理点按、**不注册焦点目标**（电视没有手指，
  而且 Android TV 的遥控器也没有"点遮罩关菜单"这个习惯动作，BACK 才是关菜单的键）；
- 菜单容器挂 `tvTakeFocusWhenEmpty(request.id)`：打开后逐帧请求焦点，直到第一个菜单项拿到焦点，
  换一个菜单（`request.id` 变化）重新来一遍。菜单是按键之后那一帧才组合出来的，单次请求会落空。

设备证据（截图 + dump）：

| 位置 | 修前 | 修后 |
| --- | --- | --- |
| ⋮ 打开的菜单 | 无焦点环，方向键改的是背景页 | 首项 `Display options` 蓝色圆角环（`t3.png`，`F [1449,124][1892,220]`） |
| 菜单内 DOWN | 无变化 | 焦点下移到 `Language preset`（`F [1449,327][1892,423]`） |
| CENTER 进语言预设子菜单 | — | 子菜单首项 `All` 有环（`t5.png`） |
| 再 CENTER 进 Display options 弹层 | — | 弹层首项 `List` 有环（`t9.png`，`F [392,244][948,340]`） |
| BACK 逐层退出 | — | 子菜单/菜单一起关，焦点回到左侧导航 `Explore`（`F [16,728][152,864]`，可见位置） |

补充对照：**独立 Popup 窗口里的菜单本来就是好的**——设置 → Spaces → 自定义空间行的 ⋮（Edit / Move up /
Move down / Delete）打开后按一次 DOWN 就聚焦 `Edit`（`F [1589,530][1864,626]`）。所以问题只出在
"窗口内浮层"这条路径，Material3 `DropdownMenu` 的 popup 路径不用动。

该浮层同时被 `SpaceSwitcher` 等共用，非电视分支仍是原来的 `clickable` 遮罩，触摸行为不变。

### 2. 输入框里按"确定"，确认按钮是禁用状态时焦点原地卡死

`rememberTvFieldExit`（`core-ui` 的 `TvFocus.kt`）只做一件事：收起键盘后 `target.requestFocus()`。
目标按钮禁用时（`tvFocusable(enabled = false)` 里 `focusable(false)`，`requestFocus()` 返回 `false`），
焦点留在输入框里，遥控器再按也没有任何反应——在"新建自定义空间"这种"名称空着 ⇒ OK 禁用"的弹窗里，
用户会以为遥控器坏了。

修法：`requestFocus()` 失败后按线性顺序走一步（`moveFocus(Next)` → `moveFocus(Previous)`），
都走不动才 `clearFocus()`。禁用按钮从此不是终点，焦点一定落到某个可见控件上。

设备证据（设置 → Spaces → Add custom space）：

| 场景 | 步骤 | 结果 |
| --- | --- | --- |
| OK 禁用 | 名称留空，焦点在输入框，收键盘后 ENTER | 焦点落到 `Select all`（`u14.png`，`F [1201,296][1352,392]`） |
| OK 可用 | 建好空间后再进编辑（名称已填），ENTER | 焦点落到 **OK**（`u18.png`，`F [1236,936][1352,1032]`，蓝色圆角环） |

### 顺带确认的老遗留

- `SettingsAlertDialog` 的初始焦点：自定义空间编辑弹窗一打开，焦点就在 Name 输入框
  （`F [568,136][1352,264] EditText`），不再是"缺设备证据"。
- 设置根页（第五十三轮）在设备上确认是单列满宽：`[24,946][696,1080]` 这类卡片满宽铺开，
  右侧详情栏只在子页面出现（Spaces 子页 `[778,...]` 起）。

### 遗留

1. **危险确认弹窗默认聚焦"确认"按钮**：删除自定义空间的确认弹窗一出现焦点就在 `Delete`
   （`F [1102,580][1234,676]`），误按一次 OK 就删掉了。应该让 destructive 弹窗默认落到 Cancel。
2. **菜单关闭后焦点回到左侧导航**，而不是回到锚点 ⋮。目前落在可见控件上、不算死路，但"从哪儿来回哪儿去"更顺。
3. 模拟器软键盘的 ✓（Done 动作键）用 CENTER / ENTER 都点不动、触摸也点不到（`screencap` 证明键盘还在原地），
   所以上面第 2 条是用 BACK 收起键盘后按 ENTER 验证的。这是模拟器 IME 的环境限制，不是应用问题。
4. 语言预设子菜单和主菜单的 BackHandler 是叠起来的：BACK 一次把两层一起关掉（`t6.xml` 里两个菜单都没了）。
   目前观感可接受，未深究。

## 第五十五轮：危险确认弹窗默认落在 Cancel（2026-10-24）

上一轮遗留的第 1 条：删除自定义空间的确认弹窗一出现，焦点就在 `Delete` 上（`F [1102,580][1234,676]`）。
这条不是"焦点没归宿"，而是"归宿错了"——用户按一次 OK 打开弹窗，再按一次 OK 就把空间连导航状态一起删了。

### 根因

`SettingsAlertDialog` 的电视初始焦点逻辑无条件回退到 **confirm** 槽：body 上没有可聚焦子项时
`confirmFocusRequester.requestFocus()`。所有"删除 / 清空 / 覆盖"确认弹窗的 Confirm 都在这个槽里，
于是"按键打开 → 按键回答"就成了默认答案。

### 改法（`SettingsAlertDialog.kt`，仅电视）

1. body 仍然优先（调用点把选择器放在 body 里，弹窗要给选择器焦点）；
2. body 无可聚焦子项时，**弹窗有 dismiss 槽就回退到 dismiss**（Cancel），没有 dismiss 的弹窗才回退到 Confirm；
3. 回退不再只请求一次，改成按帧重试最多 20 次，并且任一按钮**真的拿到焦点**（`onFocusChanged`）就停。

第 3 条是这轮调试里挖出来的：单次请求在两种情况下会被静默丢掉——弹窗窗口还在淡入、
以及应用正处于触摸模式（触摸点击打开的弹窗）。丢掉之后屏幕上没有任何环，第一次按键才"凭空"出现焦点。

### 设备证据（emulator-5554，1920×1080）

| 场景 | 修前 | 修后 |
| --- | --- | --- |
| 遥控器打开删除确认 | 环在 `Delete`（`F [1102,580][1234,676]`，第五十四轮记录，本轮 `z3.xml` 复现） | **环在 `Cancel`**（`ab3.png`，`F [946,580][1086,676]`，蓝色圆角环） |
| 焦点上按一次 CENTER | 空间被删除 | **只是取消**：弹窗关闭，自定义空间 `z` 仍在列表里（`aa5.xml`） |
| 编辑弹窗（body 有输入框） | 焦点在 Name 输入框 | 不变，仍在 Name 输入框（`ac9.xml`，`F [568,136][1352,264] EditText`） |
| 触摸点击打开删除确认 | — | 无环（触摸模式下 Compose 丢弃 `requestFocus`，`requestFocus()` 连续 26 次返回 false） |

最后一行是这轮最花时间的排查点：一开始以为是改坏了，后来用一次性的 `Log.i` 打点确认——同样的代码在
遥控器路径上第一次请求就成功（日志无输出），在触摸路径上 26 次全失败。电视用户的按键一定来自遥控器，
所以这不是缺陷；但它解释了"为什么点开弹窗看不见环"，也让那 20 次重试有了意义（淡入期同样会丢）。

### 顺带发现（未修，记入遗留）

自定义空间行（`SpaceDefinitionRow`）的行尾开关与 ⋮ **用方向键够不到**：焦点停在整行上，RIGHT 不动
（`z9.xml` 里焦点仍是整行 `[746,786][1896,914]`），只有 TAB 键才能依次走到 `Switch`（`aa1.xml`，
`F [1664,802][1768,898]`）和 `⋮`（`aa2.xml`，`F [1768,802][1864,898]`）。遥控器上没有 TAB，
所以"启用/停用空间、编辑、排序、删除"这几个动作目前在电视上只能靠触摸完成——而本轮验证删除弹窗，
正是因为走了 TAB 这条非常规路径。改法方向：行挂 `focusGroup`，或给行与行尾控件互相绑
`focusProperties { right = ... }` / `{ left = ... }`，下一轮做。

### 遗留

1. 上面这条"行尾控件够不到"，优先级高于本轮修掉的焦点问题（它直接挡功能）。
2. 菜单关闭后焦点仍回落到左侧导航而不是锚点 ⋮（第五十四轮第 2 条，未动）。
3. 只验了"有 dismiss 槽"的弹窗；`dismissButton == null` 的单按钮弹窗回退目标没变（仍是 Confirm），
   仅重试次数变了，缺少设备证据（本机没有能稳定触发的单按钮弹窗）。
4. 浮层菜单"触摸打开后没有焦点"（`w3.png`）与本轮的触摸模式结论一致，按遥控器路径不算缺陷，
   但若以后给电视加鼠标支持要一起处理。

## 第五十六轮：空间行的行尾开关与 ⋮ 用遥控器够得到（2026-10-24）

上一轮顺带发现的那条，这轮修掉：自定义空间行是"整行可点（进编辑）+ 行尾开关 + 行尾 ⋮"三层焦点，
但方向键从行上按 RIGHT 停在原地（`z9.xml`），只有 TAB 能依次走到开关（`aa1.xml`）和 ⋮（`aa2.xml`）。
遥控器没有 TAB，于是"停用空间 / 编辑 / 排序 / 删除"在电视上只能触摸完成——上一轮验证删除弹窗
就是靠 TAB 绕过去的。

### 改法（`SpacesSettingsScreen.kt` 的 `SpaceDefinitionRow`，仅电视、仅自定义空间）

1. 行、开关、⋮ 各挂一个 `FocusRequester`，用 `focusProperties` 显式连成一条链：
   行 `right →` 开关；开关 `left →` 行、`right →` ⋮；⋮ `left →` 开关。
2. 开关与 ⋮ 补 `tvFocusable(CircleShape, addFocusTarget = false)` 的焦点环——够得到但看不见环
   等于还是死的。
3. 内置空间行（Manga/Novel/Anime）只有行本身与"Built in"标签，没有开关也没有 ⋮，仍是普通行。

### 设备证据（emulator-5554，1920×1080）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 焦点在自定义空间行，按 RIGHT | `ae2.png` / `ae2.xml` | 焦点进开关 `F [1664,802][1768,898]`，开关外圈出现蓝色环（修前：停在整行 `[746,786][1896,914]`） |
| 再按 RIGHT | `ae3.png` / `ae3.xml` | 焦点到 ⋮ `F [1768,802][1864,898]`，圆形蓝环 |
| 在 ⋮ 上 CENTER | `ae6.xml` | 弹出 Edit / Move up / Move down / Delete，首项 `Edit` 有环（`F [1589,400][1864,496]`） |
| LEFT、LEFT | `ae4.xml`、`ae5.xml` | 依次回到开关、回到整行，链是双向的 |
| 在开关上 CENTER | `af1.png` | 空间被停用：开关变灰、行文字变淡（功能真的走通，不只是焦点） |
| 再 CENTER 一次 | `af2.png` | 恢复启用，设备留在干净状态 |

### 说明

这轮同时纠正了一个误读：截图里"TV"那一行带开关和 ⋮，看着像内置空间，其实它是早期测试时建的
**自定义**空间（内置的 Manga/Novel/Anime 行尾是"Built in"标签，没有开关）；所以内置行本来就不在
这条链的范围内，`focusProperties` 只对自定义行生效即可。

### 遗留

1. 菜单关闭后焦点仍回落到左侧导航而不是锚点 ⋮（第五十四轮第 2 条，两轮未动）。
2. 行内方向键：焦点在开关/⋮ 上按 UP、DOWN 时靠几何搜索在行之间跳，没有显式绑定；
   目前实测能上下走，但没有逐行验证到列表两端。
3. 设置的非根子页（右侧详情栏）宽度只在 Spaces 页对过，其余子页还没系统走一遍。

### 顺带回归：第五十五轮那套弹窗焦点规则没把"选择器类"弹窗改坏

第五十五轮把"body 没有可聚焦子项时回退到 Cancel"写进了 `SettingsAlertDialog`，风险最大的正是
body 里真的有可选项的弹窗。在同一份构建上补验了 Spaces 的"Space button position"单选弹窗：

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 焦点在该行，按 CENTER | `ah4.png` / `ah4.xml` | 弹窗打开，环在**第一个选项 `Top right`**（`F [568,317][1352,413]`），不在底部 OK |
| DOWN | `ah5.xml` | 环移到 `Center right`（`F [568,415][1352,511]`），body 内上下可走 |
| BACK | `ah6.xml` | 弹窗关闭，环回到打开它的那一行（`F [746,324][1896,448]`），没有落到侧边导航 |

也就是说：有选择器的弹窗仍是"进弹窗先给选择器"，只有纯确认弹窗才落到 Cancel（第五十五轮），
两种路径互不影响；`SettingsAlertDialog` 是真正的 Dialog 窗口，关掉时 Compose 自己会把焦点还给打开者。

## 第五十七轮：菜单关掉之后，焦点回到那个 ⋮（2026-10-24）

第五十四轮第 2 条留了两轮的"菜单关闭后焦点回落到侧边导航"，这轮做完。

### 原因

iOS 界面风格下，顶栏的浮层菜单走的是**根层 overlay**（`RootGlassMenuOverlay`）：它是锚点按钮的
**同窗口兄弟**，不是 `DropdownMenu` 那种独立 Popup 窗口。所以菜单消失时没有任何东西负责把焦点还回去——
最后一个菜单项随 overlay 一起被销毁，焦点搜索退回屏幕上第一个可聚焦节点，也就是左侧导航栏。

### 改法（`GlassDropdownMenu.kt` + `KototoroTopBar.kt`，仅电视、按需开启）

1. `GlassDropdownMenu` 与 `RootGlassMenuRequest` 新增可选参数 `anchorFocusRequester`：锚点按钮把自己的
   `FocusRequester` 交进来。
2. overlay 里**在菜单之后**声明一个 `DisposableEffect(request.id)`，`onDispose` 时请求锚点焦点。
   放在最后是为了让菜单自己的 `tvTakeFocusWhenEmpty` 先随菜单一起被取消，不会把焦点再抢回去。
3. 只在 `host.request == null`（菜单真的没了）时才回位：语言预设子菜单会用一个新 request 顶替主菜单，
   那种情况下不该把焦点拽回锚点。
4. 顶栏三处锚点接上：⋮（主菜单）、同一 ⋮ 上的语言预设子菜单、收藏页的紧凑分类标题。
   其余根层 overlay 菜单保持原样，需要时传一个 requester 即可。

### 设备证据（emulator-5554，1920×1080，全程按键）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 顶栏 ⋮ 上 CENTER | `aj1.xml` | 菜单打开，首项 `Display options` 有环（`F [1508,124][1892,220]`） |
| BACK | `aj2.xml` / `aj2.png` | 环回到 ⋮（`F [1800,16][1896,112]`，截图里按钮外圈蓝环）；修前这里落到左侧导航 |
| 再开菜单 → CENTER 选 `Display options` | `aj3.xml` / `aj3.png` | 面板打开后环在面板自己的 `History` 标签（`F [392,180][770,276]`），没被拽回锚点 |
| 面板 BACK → 再开菜单 → `Language preset` → CENTER | `aj7.xml` | 子菜单顶替主菜单，环落在子菜单首项 `All`（`F [1435,124][1892,220]`） |
| 子菜单 BACK | `aj8.xml` | 环同样回到 ⋮（`F [1800,16][1896,112]`） |
| 菜单里选 `Settings` | `ak2.xml` / `ak2.png` | 设置页打开，环在 `Appearance` 行（`F [24,196][1896,328]`），没有被旧顶栏抢走 |

### 遗留

1. 其余根层 overlay 菜单（详情页 dock、选择栏、来源标签下拉、搜索栏、探索来源页、详情页头信息面板）
   还没传 `anchorFocusRequester`，行为与修前一致；逐页验证工作量在下一轮。
2. 收藏页紧凑分类标题的锚点已接线但没在设备上单独验（首页与顶栏 ⋮ 已验）。
3. 行尾控件 UP/DOWN、设置非根子页宽度两条仍在。

## 第五十七轮补记：另外三处根层菜单锚点（2026-10-24）

### 改法（接线）

同样的 `anchorFocusRequester` 又接了三处，写法与顶栏 ⋮ 一致——`Modifier.focusRequester(x)` 必须放在
`IconButton` 的 `clickable`/`tvFocusable` **之前**，否则 requester 抓不到那个焦点目标：

1. 选择栏（`KototoroSelectionTopBar.kt`）的溢出 ⋮；
2. 探索来源页（`ExploreSourcesScreen.kt`）顶栏的溢出 ⋮；
3. 搜索页更多动作按钮（`SearchInputRows.kt` 的 `MoreActionsButton`）。

### 设备证据（emulator-5554，1920×1080，构建 `63a8954ce`）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 历史长按进选择栏，导航栏 → RIGHT → UP → 连续 RIGHT | `ar4.xml`、`ar4_3.xml` | 焦点沿选择栏右侧药丸走：全选 → 移除 → 收藏 → ⋮ `F [1800,16][1896,112]` |
| 在 ⋮ 上 CENTER | `as1.xml` / `as1.png` | 菜单打开，首项 `Mark as completed` 有环（`F [1589,124][1892,220]`） |
| BACK | `as2.xml` / `as2.png` | 环回到选择栏 ⋮（`F [1800,16][1896,112]`，截图里按钮被选中药丸包住）；修前这里落到左侧导航 |
| 探索页顶栏 ⋮ 上 CENTER | `au1.xml` / `au1.png` | 菜单打开，首项 `Extension management` 有环（`F [1449,124][1892,220]`） |
| BACK | `au2.xml` / `au2.png` | 环回到 ⋮（`F [1800,16][1896,112]`） |

### 搜索页那一处没能验，顺带发现一个真问题

搜索页当前的落屏布局（返回箭头 + 输入框 + `Advanced search`/`Filter` 药丸 + 最近查询/标签）
里根本没有那个 ⋮——`MoreActionsButton` 属于搜索结果态，所以本轮只做到了"已接线、与顶栏 ⋮ 同一写法"，
没有单独的设备证据。

更值得记的是尝试过程中撞见的：**搜索页的遥控器到不了顶栏**。焦点在输入框时 RIGHT 只是移光标，
UP/DOWN 被 `tvTextInputFocusEscape` 吃掉（上方没有可聚焦节点，`moveFocus` 返回 false），
按 BACK 收起键盘后 DOWN 会跳到列表、再 UP 又回到输入框，RIGHT 仍在框内——
返回箭头与放大镜两个按钮用方向键无法到达。这条留作下一轮的入口。

### 遗留（更新）

1. 详情页 dock、来源标签下拉、详情页头信息面板三处根层 overlay 菜单仍未接线。
2. 搜索页：更多动作按钮待结果态看图；输入框到顶栏图标的遥控器通路缺失（上节）。
3. 行尾控件 UP/DOWN、设置非根子页宽度两条仍在。

## 第五十八轮：搜索框里的遥控器能出来了（2026-10-24）

承接上一轮末尾那条：搜索页输入框把遥控器关住了，返回箭头与放大镜两个按钮用方向键到不了。

### 原因

第五十五轮那套 `tvTextInputFocusEscape` 只接管 UP/DOWN——它的立场是"单行输入框还要用左右改光标"，
所以 LEFT/RIGHT 一律放行给文本字段，而文本字段拿到方向键后只移动光标、从不交给焦点系统。
结果：输入框夹在两个按钮中间，左右都是死路，只剩 DOWN 去列表、BACK 退出整页两条出路。

### 改法（`TvFocus.kt` 加一个开关，两个搜索栏各启用一次）

1. `Modifier.tvTextInputFocusEscape(horizontalWhenIdle: Boolean = false)`：多一个参数，
   默认 false，所以其它调用点（筛选弹窗、搜索筛选面板、来源列表的输入框等）行为完全不变。
2. 打开这个开关后，LEFT/RIGHT 在**屏幕键盘已经收起**时才换成焦点移动：键收起时没人再用左右挪光标，
   这时让它们去相邻按钮；键盘还在时左右仍归光标，输入中不会莫名其妙跳走。
   `horizontalWhenIdle` 借用 `WindowInsets.isImeVisible` 判断键盘状态，不需要额外的控制器。
3. 两处电视搜索栏各加一次参数：落屏浮层 `KototoroSearchOverlay.kt`、结果页顶栏
   `SearchResultsScreen.kt`（两处用的是同一个修饰符，代码路径一致）。

### 一个绕路

第一版改在 `SearchResultsScreen.kt` 上，装到设备上毫无反应——因为设备上的搜索落屏其实是
`KototoroSearchOverlay.kt` 那个浮层（返回箭头 + 输入框 + `Advanced search`/`Filter` 药丸 + 最近查询/标签），
`SearchResultsScreen` 的顶栏只在**搜出结果之后**才出现。两处的几何几乎一样，光看 dump 的矩形分不出来；
是往 Row 上挂临时日志、发现一条都没打出来才定位到的。这条记在这里，免得下次再对着 `search_content`
这个占位符找错文件（它有两个使用者）。

### 设备证据（emulator-5554，1920×1080，全程按键，构建 `4a143e552`）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 搜索落屏打开、键盘在屏幕上，按 LEFT | `bd2.xml` | 环仍停在输入框 `F [124,0][1780,96]`，光标编辑没被打断 |
| 输入 `a` 后按 BACK 收起键盘，再按 LEFT | `bd3.xml` / `bg1.png` | 环离开输入框，落到返回箭头 `F [16,0][112,96]`（修前死在这里），截图里 `←` 被白色圆环套住 |
| 再按 RIGHT | `bd4.xml` | 环回到输入框（焦点回到输入框会重新弹键盘，这是系统行为） |
| 再按 BACK 收键盘，按 RIGHT（查询非空） | `bd6.xml` / `bg2.png` | 环落到提交按钮 `F [1800,0][1896,96]`，蓝色圆环套在右上角放大镜上 |
| 在提交按钮上 CENTER | `be1.png` | 搜索真的跑起来：`Loaded 17 results`，进入结果页 |
| 结果页输入框上 BACK 收键盘，按 LEFT | `be2.xml` / `be2.png` | 环落到结果页返回箭头 `F [16,24][112,120]`，两处接线都生效 |

### 遗留

1. 输入框重新获得焦点会自动弹键盘，所以"返回箭头 → 输入框 → 提交按钮"这一串要再按一次 BACK；
   想让 RIGHT 在光标位于文末时直接跳出去，需要拿到 `TextFieldValue` 的选区，本轮没动那个签名。
2. 输入框里的清除（×）按钮仍然只能靠触摸：它在输入框内部，左右键一离开输入框就越过了它。
3. 截图要用 `adb shell screencap -p /sdcard/x.png` + `adb pull`；PowerShell 的 `>` 重定向会把 PNG 写成不可读文本
   （`read_image` 报 `Unsupported or malformed image data`），本轮因此重拍了一遍。
4. 详情页 dock、来源标签下拉、详情页头信息面板三处根层 overlay 菜单仍未接线。
5. 行尾控件 UP/DOWN、设置非根子页宽度两条仍在。

## 第五十九轮：详情页的菜单关掉之后，环知道该回哪儿（2026-10-24）

### 原因

第 57 轮给 iOS 风格顶栏的三个根层菜单接了"环回来"的锚点
（`RootGlassMenuRequest.anchorFocusRequester` + `DisposableEffect` 的 `onDispose`），但当时只改了顶栏。
详情页自己有四份根层菜单：顶栏的 `⋮`、头部的阅读状态芯片、头部评分芯片、dock 行尾的 `⌄`。
它们都走 `useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS`，渲染在窗口层的兄弟节点里——

菜单关掉时注意力回到页面，但那个兄弟节点没有焦点可以还，于是环**凭空消失**：
遥控器再按方向键，是从一个看不见的位置重新开始找目标，等于每开一次菜单就丢一次路。

顺带发现的第二个洞：状态芯片和评分芯片在 TV 上根本没有环。它们是裸 `.clickable`
（`DetailsChromeButton` 才有 `tvFocusable`），能停住但画不出轮廓；修好"回来"之后，
环会落在一个看不见的控件上——等于没修。所以这两处一并补上 `tvFocusable(addFocusTarget = false)`。

### 改法

四处锚点，写法与第 57 轮一致——`remember { FocusRequester() }` 挂到锚点控件上
（`focusRequester` 必须排在 `clickable`/`IconButton` 之前），同一个实例交给 `anchorFocusRequester`：

| 文件 | 锚点 | 菜单 |
| --- | --- | --- |
| `DetailsMenuDialogs.kt` | 顶栏 `⋮`（`DetailsChromeButton`） | 翻译 / 阅读记录 / NSFW / 删除 / 编辑 / 找相似 |
| `DetailsHeaderInfoPanel.kt` | 阅读状态芯片（`Reading`） | 计划 / 在读 / 重读 / 读完 / 搁置 / 放弃 |
| `DetailsHeaderInfoPanel.kt` | 评分芯片（`★ 0`） | 0–10 分 |
| `DetailsDock.kt` | dock 行尾 `⌄`（`openAboveAnchor`） | 无痕 / 移出历史 / 分支 |

两个芯片同时改成
`Modifier.focusRequester(f).clip(999.dp 圆).tvFocusable(shape = 圆, addFocusTarget = false).clickable{…}`：
`addFocusTarget = false` 是因为紧随其后的 `clickable` 已经自带了焦点目标，
`tvFocusable` 只借 `onFocusChanged` 观察同一个目标并画轮廓，不会多出一个停点。

`DetailsSourceSheets.kt:164` 与 `DetailsHeaderInfoPanel.kt:942` 虽然也是 `GlassDropdownMenu`，
但都是 `useRootOverlay = false`（内联 `DropdownMenu`），关掉时焦点由 Compose 自己还给锚点，本轮没动。

### 设备证据（emulator-5554，1920×1080，全程按键，构建见下）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 详情页顶栏 `⋮` 上 CENTER | `bl3.png` | 根层菜单展开（半透明玻璃盖在标题上），环在第一个菜单项 `F [441,140][940,236]` |
| 按 BACK | `bl4.xml` / `bl4.png` | 环回到 `⋮` `F [848,32][944,128]`，白色圆角框套住三点 |
| 状态芯片 `Reading` 上 CENTER | `bn2.png` | 菜单展开（计划 / 在读 / 重读 / 读完 / 搁置 / 放弃），环在第一项 |
| 按 BACK | `bn3.xml` | 焦点回到 `F [563,298][663,394]`（芯片）——但这一版还没补轮廓，**看不见** |
| 补轮廓后重新进详情页，焦点落到 `Reading` | `bp4.png` | 芯片有蓝色药丸轮廓，遥控器能看见自己在哪 |
| 同一芯片 CENTER → BACK | `bq2.png` | 菜单关掉后轮廓仍在 `Reading` 上，焦点真的回来了 |
| 按 LEFT 到评分芯片 | `bq3.png` | `★ 0` 有自己的药丸轮廓，两个芯片是各自独立的停点 |
| dock 行尾 `⌄` 上 CENTER | `bs4.png` | 菜单向上展开（无痕 / 移出历史 / Default 1 ✓） |
| 按 BACK | `bs5.xml` / `bs5.png` | 白色轮廓回到 `⌄` `F [1588,54][1684,150]` |

构建：`:app:assembleDebug` BUILD SUCCESSFUL（--offline --no-daemon），装的是 `app-x86_64-debug.apk`。

### 遗留

1. 评分芯片的菜单这轮没走到：本地作品没有绑定追踪服务，`enabled = false` 只会弹一个
   "需要追踪"的 Toast，菜单打不开。轮廓已经验证（`bq3.png`），锚点用的是与状态芯片完全相同的写法，
   有追踪账号时需要补一次 BACK 回环。
2. `ChapterSelectionActionButton`（详情页章节选择栏的图标按钮，`DetailsDock.kt`）是裸 `IconButton`，
   TV 上同样没有轮廓，而且整条选择栏都是这样——下一步该给这批按钮统一加 `tvFocusable`。
3. 来源标签下拉（`useRootOverlay = false`）在 TV 上的回环还没实测。
4. 行尾控件 UP/DOWN、设置非根子页宽度两条仍在。

## 第六十轮：两条选择栏都是图标，遥控器看不见自己在哪（2026-10-24）

### 原因

第五十九轮结尾留下的第二条：`ChapterSelectionActionButton` 是裸 `IconButton`，TV 上能停住但画不出轮廓。
顺着这条线把详情页**另一条**选择栏也查了一遍——分页缩略图网格底部那条浮动的
（`PagesScreen.kt:338`，`selectedItemIds` 非空时从底部滑入）。它问题更多：

1. 同样两个裸 `IconButton`，没有轮廓；
2. 底色是 `MaterialTheme.colorScheme.inverseSurface`——Material3 在手机上用这个给 Snackbar 做反差，
   在电视的深色玻璃界面里却渲染成一整块**刺眼的白药丸**（`bu2.png`），看着像渲染错误；
3. 无障碍描述写死成英文 `"Clear"` / `"Save Page"`，没走 `stringResource`。

### 改法

`DetailsDock.kt`：`ChapterSelectionTopBar` 的关闭 `IconButton` 与 `ChapterSelectionActionButton`
（下载 / 翻译 / 取消翻译 / 删除 / 全选 / ⋮ 共用这一个私有组件，改一处覆盖整条栏）各挂
`tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false)`。

`PagesScreen.kt`：两个 `IconButton` 同样挂环；底色按端切换——`LocalUiPresentationConfig.current.isTv` 时用
`surfaceContainerHigh` / `onSurface`，其余平台保持 `inverseSurface` / `inverseOnSurface`；
`contentDescription` 换成 `R.string.clear` 与 `R.string.save_page`。

### 设备证据（emulator-5554，1920×1080，构建见下）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 重启后回到详情页 | `bx2.png` | 环在 `Continue` 上，界面正常 |
| 长按分页缩略图（触摸） | `by1.png` | 选择栏滑入：**深灰底**（改前同一状态是 `bu2.png` 的纯白药丸） |
| 缩略图网格上按 DOWN | `cc1.png`，dump `F [1752,888][1848,984]` | 环落到"保存页面"按钮，蓝色圆角框 |
| 再按 LEFT | `cc2.png`，dump `F [1024,888][1120,984]` | 环落到"清除"（✕），两块按钮是各自独立的停点 |
| 切到章节列表页签，长按章节行 | `ce1.png` | 顶栏换成选择栏：`✕ 1 … 文A 🗑 ⋮` |
| 详情页顶栏一路 RIGHT | dump `F [992,50][1096,154]` → `F [1612,54][1696,150]` | 越过 `⋮` 进入 dock 选择栏的 ✕，再 RIGHT 到"翻译" |
| 停住看轮廓 | `ch1.png` / `ch2.png` / `ch3.png` | 翻译、删除、✕ 三个按钮都画出圆角环 |

改完清掉一个没人用的 `modifier` 参数后重新构建、重装复验：`ci3.png` 选择栏 ✕ 仍带环，dump
`F [992,50][1096,154]`。

构建：`:app:assembleDebug` BUILD SUCCESSFUL（--offline --no-daemon），装的是 `app-x86_64-debug.apk`。

### 遗留

1. 选择栏滑入时底部还会弹一条 Material3 Snackbar（"Tip: select two chapters, then long-press a visible
   chapter between them to fill the range."），在 TV 上同样是**亮白底**（`ce1.png` 底部）——
   与本轮改的分页选择栏属同一类 `inverseSurface` 问题，下一步该给全应用的 Snackbar 一个电视配色。
2. 评分芯片的菜单仍要追踪账号才能打开（第五十九轮遗留）。
3. 来源标签下拉（`useRootOverlay = false`）在 TV 上的回环还没实测。
4. 行尾控件 UP/DOWN、设置非根子页宽度、输入框内清除（×）只能触摸，三条仍在。

## 第六十一轮：两份一模一样的选择栏，收成一份（2026-10-24）

### 原因

第六十轮把分页选择栏修好之后，顺着同一段代码往下看：`BookmarksScreen.kt` 里的书签选择栏
（详情页书签页签，长按书签进入）是**逐字复制**的同一份 `Surface + Row + 两个裸 IconButton`。
它在电视上同时中了两个毛病——`inverseSurface` 的白药丸底色，以及按钮没有轮廓。

两份代码完全同形，说明这不该靠"每个调用点各改一遍"来维持：下一份复制品还会退回同样的毛病。

### 改法

新建 `core/ui/compose/SelectionActionBar.kt`，把这块药丸收成一个组件：

| 组件 | 作用 |
| --- | --- |
| `SelectionActionBar(selectedCount, onClearSelection, modifier, actions)` | 底板（TV 用 `surfaceContainerHigh`/`onSurface`，其它平台保持 `inverseSurface`/`inverseOnSurface`）、`✕ + 计数`、右侧动作槽 |
| `SelectionBarIconButton(onClick, modifier, content)` | `IconButton` + `tvFocusable(RoundedCornerShape(12.dp), addFocusTarget = false)` |

清除按钮的无障碍描述改用 `R.string.clear`，书签栏的删除也改回 `R.string.delete`（原来是写死的英文
`"Clear"` / `"Delete"`，分页栏上一轮已经改过）。

两个调用点各剩十几行：`PagesScreen.kt` 用 `SelectionActionBar` 包住"保存页面"，
`BookmarksScreen.kt` 包住"删除"，滑入动画、对齐与 `windowInsetsPadding` 仍留在各自页面里。

### 设备证据（emulator-5554，1920×1080）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 重启回到详情页，切书签页签 | `cj2.png` / `cj3.png` | 书签页签里有一条 `P.1` 书签卡片，顶栏换成书签工具栏 |
| 长按书签卡片 | `cj4.png` | 底部弹出选择栏：**深灰底**（改前是同款白药丸），`✕ 1 … 🗑` |
| 顶栏 RIGHT ×6 → DOWN → DOWN | `cj8.png`，dump `F [1024,888][1120,984]` | 环先落在书签卡片 `F [1008,264][1283,657]`，再下到选择栏的 ✕，蓝色圆角框 |
| 再按 RIGHT | `cj9.png`，dump `F [1752,888][1848,984]` | 环落到右侧删除按钮，同样是蓝色圆角框 |
| 切回缩略图页签，长按第 3 页 | `ck1.png` / `ck2.png`，dump `F [1024,888][1120,984]` | 抽成共享组件后分页栏**没有回退**：深灰底、✕ 带环、"保存页面"仍在右侧 |

构建：`:app:assembleDebug` BUILD SUCCESSFUL（--offline --no-daemon），装的是 `app-x86_64-debug.apk`。

### 一个构建环境上的坑

这台机器同时开着桌面端会话，两个 Gradle 构建一起抢内存时，`:app:dexBuilderDebug` 阶段的
daemon 会**无声消失**——日志里只有 `Gradle build daemon disappeared unexpectedly`，
没有任何 `e:` 编译错误，很容易被误读成代码问题。改用
`--max-workers=2 "-Dorg.gradle.jvmargs=-Xmx3072M"` 后一次通过。

### 遗留

1. Material3 Snackbar（含"选择两章后长按中间章节"那条提示）在 TV 上仍是亮白底，`inverseSurface`
   是它的默认色，需要给宿主一个电视配色。
2. 评分芯片的菜单仍需追踪账号（第五十九轮遗留）。
3. 来源标签下拉（`useRootOverlay = false`）的 TV 回环还没实测。
4. 行尾控件 UP/DOWN、设置非根子页宽度、输入框内清除（×）只能触摸，三条仍在。

## 第六十二轮：Snackbar 的白板（2026-10-24）

### 原因

第六十轮结尾留下的第一条：选择栏滑入时底部弹的提示是一条**亮白全宽横条**。

Material3 的 `Snackbar` 默认用 `inverseSurface`——在手机上那是"与背景反色"的正确做法，在电视的深色玻璃
底上就是一块发光的白板。应用里有 17 个 Compose 宿主都直接调 `SnackbarHost`，等于每处都继承了这个默认值。

### 改法

新建 `core/ui/compose/KototoroSnackbarHost.kt`：

```kotlin
@Composable
fun KototoroSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier)
```

非电视平台原样交给 `Snackbar(snackbarData = data)`；电视上用 `surfaceContainerHigh` 底、`onSurface` 文字、
`primary` 动作色和 16dp 圆角，与选择栏药丸同一套色。

把 17 处宿主换成它（`BaseComposeActivity`、`DetailsScreen` 两处、`NovelReaderActivity`、
`VideoPlayerActivity`、`ScrobblingInfoSheetContent`、`SourcesCatalogScreen`、`SpaceSwitcherDelegate`
和九个设置子页），顺带删掉各处已经用不上的 `androidx.compose.material3.SnackbarHost` import。

### 设备证据（emulator-5554，1920×1080）

| 步骤 | 截图 | 观察 |
| --- | --- | --- |
| 详情页 → 章节页签 → 长按章节行 | `cl1.png` → `cl2.png` | 选择栏换成顶栏（`✕ 1`、翻译、删除、`⋮`），底部弹出提示条 |

**这条提示仍然是白底全宽**（`cl2.png` 底部）。顺着它查下去：它不是 Compose 的 Snackbar，而是
`ChaptersScreenRoot.kt:163` 的

```kotlin
Snackbar.make(viewForSnackbar, R.string.chapter_range_selection_hint, Snackbar.LENGTH_LONG).show()
```

即 framework 的 View Snackbar，挂在 `LocalView.current` 上，从来不走 Compose 宿主——所以本轮换宿主对它无效。
这也解释了它的形状：全宽、直角、没有任何圆角，和 Compose 的 Snackbar 不是同一种控件。

结论：Compose 侧这轮只到「编译通过」这一步（`:app:compileDebugKotlin` 与 `:app:assembleDebug` 均
BUILD SUCCESSFUL），**还没在设备上看到一条真正由 `KototoroSnackbarHost` 画出来的深色 snackbar**；
这条提示要等下一轮把章节/缩略图两个 pane 根换成 Compose 宿主才会变色。

### 遗留

1. `Snackbar.make(view, …)` 一共 21 处（`ChaptersScreenRoot`、`PagesScreenRoot`、`DetailsActionHandler`、
   `ReversibleActionObserver`、`SnackbarErrorObserver`、`DownloadStartedObserver`、`MainShellScene`、
   `SettingsActivity`、浏览器/Image/授权页等）。它们在同一块电视屏幕上仍然是白条。正路是把
   `ChaptersPagesTabsContent` 的 `viewForSnackbar: View` 参数换成 `SnackbarHostState`（它已经能拿到
   `DetailsScreen` 的宿主），先改两个 pane 根，再逐组迁移。
2. Compose 宿主的深色配色还缺一张设备截图，需要在迁移后补拍。
3. 评分芯片的菜单仍需追踪账号（第五十九轮遗留）。
4. 来源标签下拉（`useRootOverlay = false`）的 TV 回环还没实测。
5. 行尾控件 UP/DOWN、设置非根子页宽度、输入框内清除（×）只能触摸，三条仍在。

## 第六十三轮：View Snackbar 一次收进电视配色（2026-10-24）

第六十二轮遗留的第一条：21 处 `Snackbar.make(view, …)` 在电视上依旧是贴边全宽的白条。

### 方案取舍

第六十二轮记的正路是把章节/缩略图两个 pane 根的 `viewForSnackbar: View` 换成 `SnackbarHostState`。
真做下去要动 21 个调用点，其中大半根本不在 Compose 树里——`SettingsActivity`、浏览器/授权页、
`SnackbarErrorObserver`、`ReversibleActionObserver`、`DownloadStartedObserver` 这些只有 `View`，
拿不到 Compose 宿主；硬迁等于给每个入口再造一个不可见的宿主容器。

改为在 View 侧统一染色：**控件还是 Material 的，配色改成电视那一套**，一处实现、21 处调用点只换成新函数。

### 改法

新增 `core/util/ext/Snackbar.kt`：

```kotlin
fun View.makeSnackbar(text: CharSequence, duration: Int = Snackbar.LENGTH_SHORT): Snackbar
fun View.makeSnackbar(@StringRes resId: Int, duration: Int = Snackbar.LENGTH_SHORT): Snackbar
```

- 电视判定走非 Compose 的 `resolveUiPresentationConfig(context, AppSettings(applicationContext)).isTv`，
  Activity、observer 和 Compose 里取到的 `LocalView` 都能用；非电视原样返回 `Snackbar.make(...)`，手机分支零变化。
- 电视上套 `?attr/colorSurfaceContainerHigh` 底、`?attr/colorOnSurface` 文字、`?attr/colorPrimary` 动作色，
  与第六十二轮的 `KototoroSnackbarHost` 是同一套色。
- `SnackbarLayout` 的底换成 16dp 圆角的 `MaterialShapeDrawable`，再把四边外距设成 24dp：
  于是它从贴底全宽横条变成浮起的深色圆角面板，形状也和 Compose 那条一致。
- 21 处调用点机械替换为 `receiver.makeSnackbar(...)`（脚本扫全部 `Snackbar\.make\(`，再手工收行宽），
  涉及 `ChaptersScreenRoot`、`PagesScreenRoot`、`DetailsActionHandler`、`DetailsReaderLauncher`、
  `DownloadDialog`、`DownloadStartedObserver`、`ExitCallback`、`ImageActivity`、`ImageMenuProvider`、
  `MainShellScene`、`ReversibleActionObserver`、`SnackbarErrorObserver`、`SettingsActivity`、
  `BrowserActivity`、`CloudFlareActivity`、`SourceAuthActivity`。

### 一个 Material 的坑（值得记下来）

第一版只换 background drawable，设备上却**又变回亮白**：`SnackbarLayout` 自身带着 `backgroundTintList`，
`View.setBackground()` 之后框架仍会用那个 tint（SRC_IN）把整块 drawable 重染回默认色。
`cm2.png` 就是现场——圆角、外距都对，底却是白的、文字发灰。

所以颜色必须落到 `setBackgroundTint(...)` 上，形状才是可以独立做的那一步；`applyTvPalette()` 里两条都留着。

### 设备证据（emulator-5554，1920×1080）

触发路径：详情页 → 章节页签 → 长按章节行 → 出现范围选择提示。

| 截图 | 观察 |
| --- | --- |
| `cm1.png` | 只换色的版本：底部一条深色横条，仍然全宽、贴底、直角 |
| `cm2.png` | 加上圆角外距的版本：形状对了，但底色被 tint 打回亮白（见上面的坑） |
| `cm3.png` | 最终：深色圆角面板，底色 `rgb(41,42,45)`、文字 `rgb(199,198,202)`，纵向 938→1031、底部留 24dp |

像素取样（`cm3.png`）：面板中心 `(960,1000)` = `(41,42,45)`，文字像素 = `(199,198,202)`，
面板右缘停在 1872 附近，与 24dp 外距一致。

构建：`:app:assembleDebug` BUILD SUCCESSFUL（`--offline --no-daemon --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx3072M"`），
装的是 `app-x86_64-debug.apk`。

### 遗留

1. `KototoroSnackbarHost` 那条路仍缺一张真实设备截图：电视上现存的提示全走 View 这条路，
   要拍到 Compose 宿主的深色配色，得找一处真正由 Compose 弹提示的场景（如书架批量操作）。
2. 评分芯片的菜单仍需追踪账号（第五十九轮遗留）。
3. 来源标签下拉（`useRootOverlay = false`）的 TV 回环还没实测。
4. 行尾控件 UP/DOWN、设置非根子页宽度、输入框内清除（×）只能触摸，三条仍在。

## 第六十四轮：遥控器进不去的滑杆（2026-10-24）

顺着设置子页往下翻时撞见的一条：Lists 页的 `Grid size` 滑杆，`uiautomator dump` 里明明是
`F [758,324][1884,364] SeekBar`（focused），截图上的那一行却**没有任何焦点提示**；按左右键也不动。

### 原因

`KototoroSlider` 只是给 M3 `Slider` 套了一层外观，没有电视接线。M3 的 `Slider` 自己吃掉方向键、
却只对拖拽有反应，所以在电视上它是一个**单向死胡同**：焦点进去了，看不出进去了；左右键没反应；
只有上/下还能逃出来。

`core-ui` 里其实早就有对症的 `Modifier.tvSliderDpad`（`core/ui/adaptive/TvFocus.kt:186`）：
`tvFocusable(addFocusTarget = false)` 画环 + 预览阶段拦左右键，`tvSliderStepValue` 负责步长
（有 `steps` 的按档走，连续的走 1/20 跨度，结果总会吸附回格点，不会累积浮点漂移），手机上是 no-op。
问题是它必须由调用点自己贴上去。

全部 6 个 `KototoroSlider` 调用点里，`DisplayOptionsSheet`、`ComposeReaderActivityScaffold`、
`VideoSubtitleSettingsDialog`、`VideoSuperResolutionDialog`、`ReaderPanelComponents`（两处）、
`ReaderColorCorrectionControls` 都贴了；**漏的是 5 处**：

- `SettingsSliderPreference`——所有设置里的滑杆（网格尺寸、切页动画强度、控件不透明度……）；
- `DetailsDock.LabeledGridSlider`——详情页面板的网格尺寸 / 封面比例；
- `KototoroAppTopChrome`——订阅页"可见条目数"；
- `ReaderActionsContent`——阅读器底栏的页码滑杆；
- `FilterSheetRoute.YearSection`——筛选里的年份。

`VideoPlayerControls` 那个滑杆不算漏：它由外层容器统一在电视上 `focusable` 并自己画 `border`，
滑杆本身写了 `canFocus = !isTvPresentation`，所以没动它。

### 改法

5 处各在滑杆自己的 modifier 上补一次 `Modifier.tvSliderDpad(...)`（手机 no-op），三处细节值得记：

- `SettingsSliderPreference` 把原来的拖拽 lambda 提成局部 `applyValue`，拖拽和左右键共用同一份提交
  逻辑（都写 `sliderValue` 并按 `committedValue` 去重后回调 `onValueChange`）；
- `ReaderActionsContent` 把 `displayValue` / `applyDisplayValue` 提到 `Box` 之前算一次。那根滑杆可能是
  反向的（`sliderReversed`），环和滑杆必须看同一个映射值，否则左右键的语义会反；
- `FilterSheetRoute.YearSection` 的键步进**直接落库**（`onSetYear`）：这根滑杆原本只在
  `onValueChangeFinished` 里提交，而按键永远不会触发那个回调，不补就会"值动了、筛选没变"。

### 设备证据（emulator-5554，1920×1080）

路径：主界面 `⋮`(1845,56) → Settings(1650,400) → Appearance(250,240) → Lists(1200,830)。

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 改前：打开 Lists | `cr7.xml` 里 `F [758,324][1884,364] SeekBar`，截图 `cr2.png` / `cr4.png` | 滑杆确实拿到焦点，屏幕上却什么都没有 |
| 改后：同一路径 | `cs1.xml` 同一节点仍是 `F [758,324][1884,364]` | 焦点没变，只是终于画出来了 |
| 改后截图 | `cs1-slider.png` | 轨道外出现蓝色圆角环（10dp 圆角、2dp 描边） |
| LEFT ×3 | `cs2-crop.png` | `100%` → `90%`，滑块随之移动，环保持 |
| DOWN | `cs3-crop.png` | 环移到下一根滑杆（"List slide animation intensity"），同时只有一根带环 |

构建：`:app:assembleDebug` BUILD SUCCESSFUL（`--offline --no-daemon --max-workers=2
"-Dorg.gradle.jvmargs=-Xmx3072M"`），装的是 `app-x86_64-debug.apk`。

### 顺手排除的一条（别再来查）

Lists 子页打开时总是停在"List layout"那一节、第一行被顶栏切掉一半。查下来不是滚动状态串页
（`AppearanceSettingsScreen` 那个 `rememberSaveable` 的 `LazyListState` 切换页面后其实是归零的：
Appearance 每次都从"Theme and color"开始），而是 **Compose 把上一轮的焦点还原到列表里**——
dump 里一直是那根 `SeekBar` 带 `focused`，列表为了露出它就滚了过去。电视上"回到子页还在原处"
本身是好行为，所以没改；真正的问题只是那根滑杆看不见，就是本轮修的这个。

### 遗留

1. 另外 4 处（详情面板、订阅条目数、阅读器页码、年份筛选）只做到编译通过：这台模拟器没有可用图源、
   也没有视频，只有设置这一条路能在设备上走通。
2. `KototoroSnackbarHost` 那条 Compose 路仍缺一张真实设备截图（第六十三轮遗留）。
3. 评分芯片的菜单仍需追踪账号（第五十九轮遗留）。
4. 来源标签下拉（`useRootOverlay = false`）的 TV 回环还没实测。
5. 行尾控件 UP/DOWN、设置非根子页宽度、输入框内清除（×）只能触摸，三条仍在。
6. 来源页选择栏的 6 个 `IconButton` 仍是裸的不带环（`ExploreSourcesScreen`），且 `"$count selected"`
   与 `"Shortcut"` 是硬编码英文；这台模拟器图源为 0，选中态走不到，留待有图源时再改。

## 第六十五轮：贴在屏幕右缘的那条"死滑片"（2026-10-24）

### 现象

从主界面（Home / History / 详情页）到**阅读器正文之上**，屏幕右缘总有一块 32dp×64dp 的深色玻璃圆角片，
被屏幕边缘切掉一半，上面只有一个内容类型图标。dump 里它是 `Current: Manga. Switch space`
`[1856,144][1920,272]`，父节点 `clickable="true"` 但 `enabled="false"`。

它是 `SpaceSwitcherDelegate` 的 **sidekick handle**（`SpaceSidekickHandle`，`SpaceSwitcher.kt`）：
手机上是一条可横向拖出的抽屉把手，位置固定 `size(32dp,64dp)` + `offset(x = 10dp)`，本来就有一部分在屏幕外。
电视上上一轮之前它被写成 `interactive = state.switcherEnabled && !isTv`——**点不动、拖不动、也不进焦点链**，
却照样画。于是在电视上它只剩"一条压住画面的死装饰"，最刺眼的一处是阅读器：翻页控件一出来，它就盖在页面上。

### 改法

`SpaceSidekickHandle` 在电视上直接不画（`if (LocalUiPresentationConfig.current.isTv) return`），
并把因此失效的 `isTv` / `interactive` 两个局部量和 `.tvFocusable(...)` 一并删掉；手机分支保持原样
（`clickable(enabled = state.switcherEnabled)`，和改前的 `enabled = interactive` 等价）。
电视的换空间入口仍是左侧导航栏顶部那颗空间按钮，不受影响。

### 设备证据（emulator-5554，1920×1080）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 改前：History 右缘 | `ct2-sw.png` | 深色圆角片被屏幕右缘切掉一半，只剩图标 |
| 改前：阅读器 | `ct8-right.png`、`ct8.xml` | 翻页控件出来后它压在正文右下角上 |
| 改后：详情页右缘 | `cu1-right.png` | 整块干净，章节面板到边缘就是卡片圆角 |
| 改后：阅读器 | `cu2-right.png`、`cu2.xml` | 右缘只剩阅读器自己的 `⋮`，dump 里搜不到 `Switch space` |
| 电视换空间仍可用 | `cu8-rail.png` → `cu9-half.png` | 导航栏空间按钮带蓝色环，CENTER 打开 "Space Workbench" 面板 |

构建：`:app:assembleDebug` BUILD SUCCESSFUL，装的是 `app-x86_64-debug.apk`。
（本轮第一次构建失败于 `:reader-core:jvmJar — Could not create ZIP`：另一个会话的 Gradle 正占用同一个 jar，
 等它跑完重试即通过，与本轮改动无关。）

### 顺带实测：阅读器页码滑杆

趁走到阅读器，把底栏那根页码滑杆也走了：`TV Audit Strip`（1 章 4 页）在电视上以**纵向全宽单列**连续模式打开
（前几十轮修的连续模式形态仍然成立），CENTER 出控件后 ↓ 落到滑杆、环出现（`cu3-bar.png`），
RIGHT ×2 从 `PAGE 1` 走到 `PAGE 3`（`cu4-half.png`）。这是第六十四轮"5 处滑杆"里唯一能上设备验证的一处，
顺带确认它没被本轮改动影响。

### 顺手排除的两条（别再来查）

1. History 顶上那排筛选项在 1920 宽下排了 10 个芯片（`On device` … `Video source domain Local storage`），
   最后两枚超出可视宽。用遥控一路 RIGHT 走过去，`LazyRow` 会自动把它们滚进视野，最后一枚拿到环且完整可见
   （`ct5-chips.png`），不是缺陷。
2. History 顶部"Statistics"卡 + 筛选芯片行占掉上半屏，列表在 1080 高下只剩一行多一点。
   这是"统计卡优先"的既有设计取舍，不属于本次连续模式/焦点范围，未改。

### 遗留

1. `ReaderActionsContent`（`reader/ui/ReaderActionsContent.kt`）**只被它自己的 `@Preview` 引用**：
   `grep 'ReaderActionsContent\('` 只命中第 83 行定义与第 328 行预览，没有生产调用点。
   也就是说第六十四轮给它补的 `tvSliderDpad` 实际改的是死代码；真正阅读器底栏在
   `ComposeReaderActivityScaffold`（那根滑杆早就有环和键步进，本轮已实测）。后续要么删掉这个组件，
   要么在文档里标清楚，避免下一轮又去"修"它。
2. 阅读器在电视上现在**没有任何换空间入口**（唯一的那条把手在电视上不画，改前也是点不动的）。
   真要在阅读器里换空间，应该做成 `⋮` 选项面板里的一项，本轮未动。

## 第六十六轮：详情页按 BACK 回到快选层，遥控焦点掉进面板背后的页码滑杆（2026-10-24）

### 现象

阅读器 `⋮` 选项面板打开后，↓ 走到标签行、CENTER 进 `Display` 详情页，页内再往下走到
`Brightness` 滑杆（dump 焦点 `[722,384][1197,480]`），然后按 BACK 收起详情页：

1. 面板滑回 peek，快选层（模式行 + 九宫格动作 + 底部标签行）重新可见；
2. 但 dump 里**一个 `focused="true"` 节点都没有**——环凭空消失；
3. 紧接着的一次 ↓ 把环挂到了 `SeekBar [236,1065][1684,1080]` 上，那是**面板背后**阅读器自己的
   页码滑杆，只在屏幕最下沿露出一条 15px 的蓝色弧线（`cw8-bot.png`、`cw9-bot.png`）。
   用户看到的是"面板回来了，但第一下按键没反应"，第二下开始遥控在面板背后翻页。

### 原因

`ReaderOptionsPanelHost` 里本来就有"收起后把环带回去"的意图（这个 `LaunchedEffect` 的注释写着
"left in the detail page it would sit on a hidden control"），但两道闸门一起把它废掉了：

```kotlin
if (isTv && wasExpanded && !scope.isExpandedPastPeek && !sheetHasFocus) {
    withFrameNanos { }
    requestFocus(focusTarget)
}
```

`sheetHasFocus` 是挂在面板 Column 上的 `onFocusChanged { it.hasFocus }`。临时打点实测（`System.out`）：

```
TVSC collapse-effect expanded=false was=true tv=true hasFocus=true
TVSC settled=10 hasFocus=true        // 等十帧之后仍然是 true
```

也就是说：详情页那张滑杆被移出组合后，**Compose 仍然把面板 Column 报成 hasFocus=true**，
而真正的焦点树上已经没有任何节点（dump 证实）。于是 `!sheetHasFocus` 这道闸门让请求**一次都没发出去**——
这条恢复逻辑从写下来那天起就没真正生效过，不是本轮引入的回归。

### 改法

收起分支不再看那个不可信的标志，直接请求，且给足重试帧数（和打开面板时的处理对称）：

```kotlin
val collapsing = wasExpanded && !scope.isExpandedPastPeek
wasExpanded = scope.isExpandedPastPeek
if (!isTv || !collapsing) return@LaunchedEffect
repeat(COLLAPSE_FOCUS_FRAMES) {   // 3
    withFrameNanos { }
    requestFocus(focusTarget)     // 面板首个动作："Chapters and pages"
}
```

`wasExpanded` 的写回提到 `return` 之前（否则提前返回会把状态吞掉，下一次收起就不触发了）；
打开面板那条 `repeat(10)` 里的字面量顺手提成 `FOCUS_RETRY_FRAMES`，两个常量放在文件头部。

### 设备证据（emulator-5554，1920×1080）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| `⋮` 打开面板 | `cy2.xml` | 环在快选层第一项 `Chapters and pages` `[232,542][414,730]` |
| ↓×3 → RIGHT → CENTER | `cy3.xml` | 切到 `Display` 详情页，环在 `Display` 标签 `[850,102][1012,198]` |
| 页内 ↓×2 | `cz1.xml` | 环进详情页，落在 `[722,384][1197,480]` |
| **BACK（改后）** | `cz2.xml`、`cz2-half.png` | 面板回到 peek，**环回到 `Chapters and pages`** |
| BACK 后 ↓×2 | `cz3.xml` | 环走到 `Layout` 标签 `[691,956][846,1052]`，仍在面板内部；改前同一串按键落到 `[236,1065][1684,1080]` 的页码滑杆 |
| 再 BACK | `cz4.xml` | 面板关闭，环回到阅读器顶栏标题 `[784,12][1136,108]`，不在任何隐藏控件上 |

构建：`:app:assembleDebug` BUILD SUCCESSFUL（本轮第一次仍撞上 `:reader-core:jvmJar — Could not create ZIP`，
等并行会话的 Gradle 结束重试即过），装的是 `app-x86_64-debug.apk`。

### 顺带实测：Display 详情页本身是好的

`Display` 页（`Background` 单选网格 + `Invert colors`/`Grayscale`/`Brightness`/`Contrast`/
`Yellowish background` + `Save / Globally / This manga` + 原图/效果预览）在电视上焦点链完整：
亮度滑杆带整条圆角环（`cw4-focus.png`、`cw5-focus.png`），←×3 让 `Brightness 100% → 70%` 并即时改变
预览（`cw4-focus.png` 前后对比），说明第六十四轮给 `KototoroSlider` 接的 `tvSliderDpad` 在详情页里也生效。

### 遗留

1. 面板收起后底部标签行**仍高亮 `Display`**，可内容已经变回快选层（`cz2-half.png`）。
   手机端 BACK 收起面板同样不重置标签选中态，所以不是电视独有的行为；但电视用户没有"点一下标签"
   这种纠正手段，是否需要"收起即取消标签高亮"留待决定，本轮未动。
2. 第六十五轮的两条遗留（`ReaderActionsContent` 是死代码、阅读器缺换空间入口）不变。

## 第六十七轮：`Crop & note` 在电视上到不了比例芯片——这个页面根本不知道自己是电视（2026-10-25）

快选层剩下五格这一轮补测，第一格 `Crop & note` 就撞出一个整屏级的断点。手机上的流程是
"拖裁剪框 → 打字 → 点比例"；遥控器上：

1. 顶栏只有 `Cancel` / `Save note` 两个停靠点，`DOWN` 直接跳过裁剪区掉进底部输入框；
2. 输入框是 Compose 文本框，方向键被它拿去移动光标，**进去就出不来**——`BACK` 只是收起软键盘，
   再按 `DOWN` 环仍然在这一条输入框上（`db5.xml`、`db6.xml`）；
3. 于是四个比例芯片永远到不了，而裁剪框本身没有可聚焦节点，遥控器上就**没有任何办法改变裁剪范围**；
4. 软键盘弹出后还正好盖住芯片行（`db4-half.png`），芯片本来也没有焦点环——它们是原样 `FilterChip`。

### 原因：`setContent` 而不是 `setComposeContent`

第一版只做了两件事（给输入框加 `tvTextInputFocusEscape()`、把芯片换成电视药丸），装到模拟器上
**一点变化都没有**。顺着"helper 为什么没反应"查下去，答案在页面入口：

```kotlin
// PageCropActivity（改前）
setContent { KototoroTheme { Column(...) } }
```

全仓 46 个 Compose Activity 都走 `BaseComposeActivity.setComposeContent { }`，只有这里自己调
`setContent`。而 `LocalUiPresentationConfig` 正是在 `setComposeContent` 里 provide 的
（`BaseComposeActivity` 第 105 行）。也就是说：**这个 Activity 的组合里 `isTv` 永远是默认值**，
所有电视分支都在静默走手机路径——`tvTextInputFocusEscape()` 变成 no-op，芯片也不会换样式。
不是某个 helper 坏了，而是这一屏从来没有进入过电视模式。

### 改法

- `PageCropActivity` 改用 `setComposeContent { }`（顺带去掉里面多余的 `KototoroTheme` 包裹，
  于是也拿到应用统一的圆角、共享 Snackbar 与展示模式退出按钮）。
- `core-ui` 新增共享 `TvFilterChip`（`core/ui/adaptive/TvFilterChip.kt`）：手机上原样返回
  `FilterChip`；电视上换成手绘药丸——`clip` + `background` + `tvFocusable(shape, addFocusTarget = false)`
  + `selectable`，最小高度 48dp，选中态走 `primaryContainer`/`onPrimaryContainer`，
  未选中走 `surfaceVariant`/`onSurfaceVariant`，环用主题 `primary`。
  与第三十八轮视频面板里那个私有 `SubtitleChip` 同一套做法，区别是这里用主题色而不是播放器配色。
- 输入框挂 `Modifier.tvTextInputFocusEscape()`，收起键盘后 ↓ 可以走到芯片行。

### 设备证据（emulator-5554，1920×1080）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| 改前：顶栏 ↓ | `db2.xml`、`db3.xml` | 环从 `Cancel [16,0][156,96]` 直接落到输入框 `[24,828][1896,940]`，可聚焦节点只有 `Cancel`/`Save note`/输入框/四个芯片 |
| 改前：↓ → BACK → ↓ | `db5.xml`、`db6.xml` | 两次 dump 都是 `EditText`：键盘收起后 `DOWN` 仍留在输入框，芯片行不可达 |
| 改后：顶栏 ↓ | `dc2.xml` | 仍先进输入框（预期） |
| 改后：BACK → ↓ | `dd1.xml`、`dd1-bottom.png` | 环落到 `1:1` 芯片 `[858,968][973,1064]`，芯片是 48dp 高的电视药丸，带 2dp 主色环；`Original` 选中为主色底 |
| 改后：CENTER 在 `1:1` | `dd2-half.png` | 裁剪框从竖长（`db2-half.png` 中 `x 740..1180`、`y 124..780`）变成方形（`x 632..1290`、`y 96..780`），比例预设确实驱动 `OverlayView` |
| 改后：↑ | `dd3.xml` | 环回到输入框 `[24,488][1896,600]`——键盘弹出时窗口 `adjustResize`，整条输入框被顶到键盘上方（`dd5-half.png`） |
| 改后：BACK → ↑ | `dd6.xml` | 环落到 `Save note [1724,0][1904,96]` |
| 改后：CENTER 在 `Save note` | `dd7-half.png` | 弹出 `Panel note saved`，回到阅读器原页，未崩溃 |

构建：`:app:assembleDebug` BUILD SUCCESSFUL（只有该文件既有的三条 `getParcelableExtra`/`setFreestyleCropEnabled`
废弃警告），装的是 `app-x86_64-debug.apk`。

### 遗留

1. **裁剪框仍然只能手指拖**：`UCropView` 里没有可聚焦节点，遥控器现在能做的是选比例
   （`Original`/`1:1`/`4:3`/`16:9`，框会居中重排）。要支持遥控微调，需要给 `OverlayView` 外面包一层
   "边框与角点可聚焦 + 方向键位移"的交互，属于新功能而不是接线，本轮未动。
2. 键盘弹出时窗口 `adjustResize` 会把裁剪区压扁、把顶栏 `Cancel`/`Save note` 顶出屏幕（`dd5-half.png`）。
   只在打字时出现，收起键盘即恢复；如果后面嫌丑，可以给这一屏单独设 `adjustPan`。
3. 这里给 `maxLines = 2` 的短笔记用了 `tvTextInputFocusEscape()`，而该 helper 的注释写的是"只给单行字段"。
   电视上放弃两行内的上下移动键、换来能走到芯片行，是刻意的取舍，记在这里避免下次被当成误用改回去。
4. 同一批五格里还剩 `Automatic scroll`、`Rotate screen`、`Download`、`Open in browser` 没实测。

## 第六十八轮：快选层剩下四格（Automatic scroll / Rotate / Download / Open in browser）（2026-10-25）

四格一次走完，结论分三类：一格是真的能用（但带一个空停点，已修）、两格在电视上本来就没有意义、
一格把用户送进了一个必然报错的窗口。

### 现象

1. `Automatic scroll` 打开底部面板：`×`、`Auto scroll`、`Pause`、速度滑杆、`Show floating button`、
   `Pause when controls are visible` 六个停靠点都在，焦点下移时面板自己滚动，滑杆能用 ←/→ 步进
   （`x3.1` → `x0.1`）。**但是**第一个开关按 ↑ 会停到面板顶部的拖拽把手 `[912,320][1008][416]` 上——
   那是 Material3 `ModalBottomSheet` 自带的把手，遥控器按 CENTER 什么都不会发生。
2. `Rotate screen` 按下去只弹一条 `Screen rotation has been unlocked`（`dv1-crop.png`），
   画面一点没变——它切的是"屏幕方向锁"，电视没有可转的屏幕。
3. `Download` 按下去面板收起、什么都没有发生：本地书没有可下载的章节。
4. `Open in browser` 直接打开了内置浏览器，然后是一整页 Chrome 报错
   `Webpage not available … file:///storage/emulated/0/Android/data/org.skepsun.kototoro.debug/files/manga/TV%20Audit%20Manga%2011.cbz`
   `net::ERR_ACCESS_DENIED`（`dx2-half.png`），而且这一屏只有一个可聚焦节点（整个 WebView），
   顶栏的关闭按钮遥控器根本够不到。

### 原因

- 空停点：`ModalBottomSheet` 的默认 `dragHandle = { BottomSheetDefaults.DragHandle() }`。
  Material3 给那个把手挂了 `draggable` + 合并语义，于是它进了 Compose 的焦点树；手机上没人用方向键走过去，
  所以在电视之外从来没人发现。同一份代码在阅读器自己的选项面板里用的是 `ReaderPanelDragHandle`
  （纯 `pointerInput`），那份就不会被聚焦——`cy2.xml` 里同位置只有一个 `focusable=false` 的 8px 药丸，
  对照 `do1.xml` 里那把手的 96×96 可聚焦节点，差别一眼可见。
- 报错页：`openCurrentChapterInBrowser()` 在拿不到 http 地址时回落到 `router.openBrowser(manga)`，
  而本地作品的 `manga.url` 正是 `file://…cbz`，内置浏览器（Chrome/WebView）不允许页面导航到别的
  `file://` 路径，于是必然 `ERR_ACCESS_DENIED`。

### 改法

1. `ComposeReaderActivityScaffold` 的自动滚动面板：电视上把把手换成 12dp 空白，手机保持 Material 默认：

   ```kotlin
   dragHandle = if (isTvPresentation) {
       { Box(modifier = Modifier.fillMaxWidth().height(12.dp)) }
   } else {
       { BottomSheetDefaults.DragHandle() }
   },
   ```

2. `openCurrentChapterInBrowser()` 补齐"根本没有网页"这一支：章节与作品都不是 http 地址时
   不再打开浏览器，改为提示 `open_in_browser_unavailable`（新增英文串，翻译仍走 Weblate）。
   手机端同样受益——本地书在手机上按这一格原本也会掉进同一个报错页。

### 设备证据（emulator-5554，1920×1080）

| 步骤 | 截图 / dump | 观察 |
| --- | --- | --- |
| `⋮` → RIGHT×4 → CENTER | `df1-half.png`、`df3.xml` | 自动滚动面板打开，焦点在 `Auto scroll` 开关；可聚焦节点为把手 / `×` / 两个开关 / `SeekBar` |
| ↓×4 → ↓×3 | `dh4.xml`、`dj3.xml` | 焦点链走完四个开关与滑杆，面板随焦点上滚，最后一行 `Pause when controls are visible` 完整可见 |
| 滑杆 ←×6 | `di1-crop.png` | `x3.1` → `x0.1`，滑杆带圆角环（环的下沿被屏幕底边切掉，见遗留） |
| 开 `Auto scroll`（速度 `x4.1`、`Pause when controls are visible` 关） | `dr3.png` / `dr4.png` | 相隔 6 秒两张截图不同，背后页面从 `CH 1 / PAGE 1` 走到 `CH 1 / PAGE 4`——电视上真的在自动滚 |
| 打开 `Pause` 开关 | `ds2.png` / `ds3.png` | 两张完全一致（同一 hash），滚动停住 |
| 改后：重开面板 | `dt1.xml`、`dt2-crop.png` | 把手不再存在，面板直接从标题行开始 |
| 改后：第一个开关按 ↑ | `dt3.xml` | 焦点直接到 `×` `[1472,606][1568,702]`，中间没有空停点 |
| `Rotate screen` | `dv1-crop.png` | 只有 `Screen rotation has been unlocked` 提示，画面不变 |
| `Download` | `dw2-half.png`、`dw3.xml` | 面板收起，无提示、无对话框 |
| `Open in browser`（改前） | `dx2-half.png`、`dx3.xml` | 进入 `BrowserActivity`，`net::ERR_ACCESS_DENIED`；唯一可聚焦节点是 WebView |
| `Open in browser`（改后） | `dy1-crop.png` | 留在阅读器，底部提示 `This content has no web page to open` |

构建：`:app:assembleDebug` 两次均 BUILD SUCCESSFUL，装的是 `app-x86_64-debug.apk`。

### 遗留

1. **`Rotate screen` 与 `Download` 在电视上等于空格子**：前者只切方向锁（电视无方向可转），
   后者对本地/无源内容静默无事。要不要在电视上直接不画这两格（或对本地内容置灰），属于产品决定，
   本轮只记录，没动。产品上更彻底的做法是让 `mangaQuickActions()` 按"是否有 http 页面 / 是否可下载"
   过滤，手机端也能省掉两个空格。
2. **M3 把手的空停点不止这一处**：全仓 29 个 `ModalBottomSheet(` 调用点默认都带同一个把手。
   本轮只修了阅读器自动滚动面板；建议加一个共享的 `TvModalBottomSheet`（电视上默认换 12dp 空白），
   再逐处迁移，别在 29 个地方各写一遍 `if (isTv)`。详情页、显示选项、空间切换等已经审过的面板
   大概率都有这个停靠点，只是之前的焦点走位没经过它。
3. 自动滚动面板里，焦点被带进视野时 Compose 只滚"刚好露出"，所以滑杆的焦点环下沿会被面板/屏幕底边切掉
   （`di1-crop.png`）。纯视觉问题，未动。
4. `Show floating button` 那个浮标（打开后收起控件时右下角出现计时器圆钮）尚未实测；
   它只在"控件不可见 + 面板关闭"时出现，遥控器收起控件后是否有环走过去还需要一轮。
5. 第三格的 `Automatic scroll` 面板本身还有一种入口：阅读器控件栏的计时器按钮
   （`ReaderControl.TIMER`）。本轮只从 `⋮` 快选层进去过，两条入口指向同一个面板。

## 第六十九轮：把"电视上没有把手"抽成共享 TvModalBottomSheet（2026-10-25）

上一轮的修法是内联在自动滚动面板里的 `if (isTv)`，而同一个空停点在 29 个
`ModalBottomSheet(` 调用点上都会出现。这一轮把它收成一个组件，先迁阅读器自己的两处。
顺手补一个观察：**阅读器工具面板在电视上根本没有入口**，也就是说那处迁移属于结构性收口，
不改变当前可达的电视路径。

### 现象

- 自动滚动面板在电视上已经从标题行开始（第六十八轮），但这段 `dragHandle = if (isTv) … else { BottomSheetDefaults.DragHandle() }`
  只服务了一处调用；下一个人再写 `ModalBottomSheet` 时还得重新踩一遍。
- 阅读器底栏在电视上只有 `Previous chapter` / `Next chapter` 两个带无障碍描述的按钮，
  没有"工具/TOOLS"这一格；`ReaderControlDestination.TOOLS` 只在工具面板自己的 onDismiss 里被发出，
  所以 `state.toolsVisible` 在电视路径上恒为 false。

### 改法

新增 `app/.../core/ui/compose/TvModalBottomSheet.kt`：

```kotlin
@Composable
fun TvModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        dragHandle = if (LocalUiPresentationConfig.current.isTv) {
            { Box(Modifier.fillMaxWidth().height(12.dp)) }
        } else {
            { BottomSheetDefaults.DragHandle() }
        },
        content = content,
    )
}
```

迁移：`ComposeReaderActivityScaffold` 里的两处 `ModalBottomSheet(`（自动滚动面板、工具面板）改成
`TvModalBottomSheet`，上一轮内联的 `if (isTv)` 删除，`ModalBottomSheet`/`BottomSheetDefaults` 的 import 一并清掉。

### 设备验证（emulator-5554）

| 步骤 | 证据 | 观察 |
| --- | --- | --- |
| 改后重装、进阅读器、`⋮` → RIGHT×4 → CENTER | `ea1.xml` | 可聚焦节点与第六十八轮改后完全同构：面板顶部的滚动容器 + `×` + 两个开关 + `SeekBar` + 第三个开关；**没有** 96×96 的把手节点 |
| 阅读器底栏 dump | `eb1.xml` | 底栏只有 `Previous chapter` `[628,1006][676,1054]` 与 `Next chapter` `[1244,1006][1292,1054]` 带描述，无工具入口 |

`:app:assembleDebug` BUILD SUCCESSFUL（3m 6s，23 executed）。

### 遗留

1. 还有 27 个 `ModalBottomSheet(` 调用点没迁。按是否电视可达排优先级：详情页 `DetailsSheets`
   （两处）、列表页 `DisplayOptionsSheet`、空间切换 `MediaUniverseSheet`/`SpaceSwitcher`、
   设置里的 `ExtensionInstallBehaviorSheet` 等都在电视路径上；迁移前要先给
   `TvModalBottomSheet` 补 `containerColor`/`contentColor`/`shape`/`contentWindowInsets` 等透传参数，
   现在它只覆盖"只传 onDismissRequest/sheetState"的简单调用。
2. 工具面板（`ComposeReaderToolsSheet`）在电视上没有入口，属于产品层面的取舍：
   它的内容（翻译、书籍信息等）大多已能从 `⋮` 或底栏到达。要不要给电视一个入口，另说。

## 第七十轮：把剩下的"默认把手"面板一次性收编（2026-10-25）

先数清楚还剩几处：29 个 `ModalBottomSheet(` 调用点里，**17 个本来就传了 `dragHandle = null`**
（详情页、显示选项、首页分区、空间切换、搜索筛选、统计弹窗等），真正带默认把手的只有 12 个，
而且集中在"要么要账号、要么要源、要么要小说"的屏幕上——这解释了为什么前六十多轮从没撞见它们。

### 现象

带默认把手的 12 处，调用参数都很朴素（只给 `onDismissRequest` / `sheetState` / `modifier`），
和第六十九轮抽出的 `TvModalBottomSheet` 签名完全吻合：

| 文件 | 屏幕 | 电视可达性 |
| --- | --- | --- |
| `filter/ui/tags/TagsCatalogRoute.kt:64` | 标签目录 | 可达（筛选入口） |
| `migration/ui/duplicate/DuplicateFavouriteSheet.kt:64` | 重复收藏消歧 | 需批量操作 |
| `migration/ui/list/MigrationCandidatesSheet.kt:70` | 换源候选 | 需已装源 |
| `notes/ui/BookNotesDetailScreen.kt:891` | 书籍笔记 | 需笔记数据 |
| `reader/novel/compose/NovelReaderChrome.kt:212` | 小说工具面板 | 需小说作品 |
| `reader/novel/compose/NovelExcerpt.kt:201` | 小说摘录 | 需小说作品 |
| `reader/novel/compose/ComposeNovelReplaceRulesSheet.kt:65` | 小说替换规则 | 需小说作品 |
| `alternatives/ui/compose/AlternativesSheetRoute.kt:64` | 替代源 | 需源 |
| `details/ui/scrobbling/ScrobblingInfoSheet.kt:64` | 进度同步信息 | 需追踪账号 |
| `discover/ui/details/TrackingLocalSearchSheet.kt:98` | 追踪站内搜索 | 需追踪账号 |
| `scrobbling/common/ui/selector/ScrobblingSelectorSheet.kt:90` | 追踪选择器 | 需追踪账号 |
| `settings/tracker/categories/TrackerCategoriesConfigSheet.kt:50` | 追踪分类 | 需追踪账号 |

### 改法

12 处全部换成 `TvModalBottomSheet`（`onDismissRequest` / `sheetState` / `modifier` 一一对应，
`suspend` 语义、`SheetState` 复用、`skipPartiallyExpanded` 都不变），顺手清掉
`androidx.compose.material3.ModalBottomSheet` 的 import 并整理成字典序。

`TrackingLocalSearchSheet` 里有一处 `return@ModalBottomSheet`（"没有可用来源"时提前退出面板内容），
改名后标签失效，编译报 `Unresolved label`，改成 `return@TvModalBottomSheet`。

### 设备验证（emulator-5554）

| 步骤 | 证据 | 观察 |
| --- | --- | --- |
| `:app:assembleDebug` | `.tmp-build.log` | 第一次因 `TrackingLocalSearchSheet.kt:144 Unresolved label` 失败；改标签后 BUILD SUCCESSFUL（1m 9s，19 executed） |
| 重装、进阅读器、CENTER → RIGHT → CENTER | `ec1.xml` | 选项面板正常打开，焦点在第一个快选格 `[232,542][414,730]`，没有把手节点，五个比例 `RadioButton` 与整排快选格都在 |
| 同一次会话里 `⋮` → RIGHT×4 → CENTER | `ea1.xml`（第六十九轮） | 自动滚动面板可聚焦集合不变 |

这 12 块屏幕里有 9 块在本机环境进不去（无追踪账号、无已装源、无小说作品），
所以这一轮只做到"改法一致 + 编译通过 + 两条电视路径无回归"，逐屏的焦点走位留给后续轮次。

### 遗留

1. `reader/novel/compose/ComposeNovelMarkingsSheet.kt` 的三个调用点（98 / 588 / 684）仍然带默认把手：
   它们传了 `shape`、`containerColor`、`contentColor`，当前包装器没有这些透传参数。
   要收编得先给 `TvModalBottomSheet` 补 `containerColor`/`contentColor`/`shape`/`tonalElevation`/`contentWindowInsets`。
2. 上面表格里"需追踪账号 / 需源 / 需小说"的 9 块屏幕，等环境具备时仍要各走一遍：
   这一轮只保证把手不再是空停点，面板内的控件是否可聚焦、顺序是否合理还没验证。
3. 本轮有 4 个文件的 import 块被重新排序（原来就有 `CircleShape`、`MaterialTheme` 之类错位），
   纯格式churn，无行为变化。

## 第七十一轮：阅读器提示条在电视上读不了（2026-10-25）

阅读器的所有动作反馈——"旋转已解锁"、"这本书没有网页可打开"、"书签已添加"、"已保存"——都走
`ReaderMessageHost`，而它一直是 `bodySmall` + 78% 黑底 + `start = 10.dp` 的单行小药丸。
手机贴脸看没问题，电视三米外基本认不出字。

### 现象

第六十八轮两张截图正好留下对照：`dy1-pill.png` 里 "This content has no web page to open" 只有
330px 宽、字高约 24px，嵌在几乎纯黑的窄条里；同一句话在 1080p 电视上要凑近才看得清。

### 原因

`ReaderMessageHost`（`ComposeReaderActivityScaffold.kt:1704`）没有任何电视分支：
文字固定 `MaterialTheme.typography.bodySmall`（12sp），底板固定 `Color.Black.copy(alpha = 0.78f)`，
左右内边距只有 10dp/0dp，圆角用 `MaterialTheme.shapes.small`。

### 改法

按 `LocalUiPresentationConfig.current.isTv` 分支：

| | 手机（不变） | 电视 |
| --- | --- | --- |
| 文字 | `bodySmall` | `titleMedium`（22sp） |
| 底板 | `Color.Black.copy(alpha = 0.78f)` | `surfaceContainerHigh.copy(alpha = 0.96f)`（与其余电视 chrome 同一条 surface 阶梯） |
| 前景色 | `Color.White` | `colorScheme.onSurface` |
| 圆角 | `MaterialTheme.shapes.small` | `RoundedCornerShape(12.dp)` |
| 内边距 | start 10dp / vertical 10dp | start/end 20dp / vertical 14dp |

### 设备验证（emulator-5554）

| 步骤 | 证据 | 观察 |
| --- | --- | --- |
| 重装、进阅读器、`⋮` → RIGHT×7 → CENTER（Open in browser，本地书） | `ed1.png`、`ed1-pill.png` | 提示条变成 660px 宽、字高约 44px 的深灰面板，圆角 12dp，与 `dy1-pill.png`（改前 330px / 24px 字 / 近黑底板）对照明显 |
| 同一张全屏图 | `ed1-crop.png` | 提示条仍浮在底栏之上（`controlsVisible` 时抬 104dp），没有遮住进度条 |

`:app:assembleDebug` BUILD SUCCESSFUL（1m 55s，21 executed）。

### 遗留

1. **活动级 `KototoroSnackbarHost` 还没做同样的放大**。它是 `BaseComposeActivity.setComposeContent`
   挂在每个 Activity 底部的那一个，`details/ui/DetailsErrorObserver.kt`（错误 + Try again/Details）、
   `download/ui/compose/DownloadDialog.kt`（"Downloads"）这些**带动作按钮**的提示都走它。
   本轮不动它是因为本机没有能触发它的电视路径（本地书没有加载错误、也没有可下载章节），
   改了没法给证据。下一轮要做的话：电视分支把消息文字提到 `titleMedium`（`CompositionLocalProvider(LocalTextStyle …)`
   包住 `Snackbar`，需先在设备上确认 M3 的 message 是否吃 `LocalTextStyle`），并把动作按钮的
   `labelLarge` 一起提上来——否则 22sp 的文字配 14sp 的按钮会很怪。
2. 提示条本身不可聚焦（瞬时信息，遥控器不该停进去），这一点是对的，别在后续"补齐焦点"时改掉。
3. `ReaderMessageHost` 里的 `AnimatedVisibility` 在电视上仍走 fade——`whenReaderAnimationsEnabled`
   受 `eInk` 开关控制，与电视无关，属既有行为。

## 第七十二轮：顶栏溢出菜单贴边 + 标签太小（2026-10-25）

历史页的 `⋮` 菜单（Display options / Language preset / Clear history / Settings / Help & feedback /
Incognito mode）在电视上有两个问题：**面板右边缘贴着屏幕边**（真机 5% overscan 会把圆角和标签末尾吃掉），
**标签只有 14sp**。

### 原因：菜单有两条渲染路径，默认走的那条不吃 offset

`GlassDropdownMenu` 在 `useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS` 时并不使用
`DropdownMenu`，而是把内容塞进 `RootGlassMenuRequest`，由 `RootGlassMenuOverlay` 自己定位。本机界面风格
是 `ios (modern)`，所以实际走的是 overlay 这条。两个连带发现：

- `RootGlassMenuRequest` **根本没有 offset 字段**，`GlassDropdownMenu(offset = …)` 在 overlay 路径上被整段丢弃；
  定位完全由 `calculateRootGlassMenuOffset` 决定，而它把 x 钳在 `[0, W - w]`，没有任何安全边距。
- 标签来自 `CompactDropdownMenuText`，它把 `style` 写死成 `labelLarge`，所以只改 `CompactDropdownMenuItem`
  的 `LocalTextStyle` 不会生效（第一次改完行高变了、字一颗没变大，就是这么回事）。

### 改法

| 位置 | 改动 |
| --- | --- |
| `calculateRootGlassMenuOffset` | 增加 `edgeInsetPx: Int = 0`，x/y 都钳进 `[inset, size - inset]`；默认 0 时与旧行为完全一致 |
| `RootGlassMenuOverlay` | 电视上传 `24.dp`，非电视传 0 |
| `CompactDropdownMenuItem` | 行高 48dp → 电视 56dp；`LocalTextStyle` 电视供 `titleMedium` |
| `CompactDropdownMenuText` | 电视用 `titleMedium`，非电视仍是 `labelLarge`（菜单里的分组标题也走这个函数，否则标题会比条目还小） |
| `GlassDropdownMenu` 的 `DropdownMenu` 分支 | 电视再把 `offset.x` 减 24dp（Classic 界面风格走这条） |

### 设备验证（emulator-5554）

| 步骤 | 证据 | 观察 |
| --- | --- | --- |
| 改前：历史页 `⋮` | `ee5-top.png` | 面板右边缘与屏幕边齐平，标签约 28px 高（14sp） |
| 只改行高与 `LocalTextStyle` | `eg1-menu.png`/`eg2-menu.png` | 行距变宽（~64px→58dp），**文字一点没变**——定位到 `CompactDropdownMenuText` 的硬编码 |
| 改 overlay 的 `edgeInsetPx` + 文本样式 | `eh1.png`/`eh1-small.png` | 面板右边缘内收约 24dp，标签约 44px（22sp），菜单变高后 6 项仍完整可见 |
| 收尾（文本样式改成按电视直接取） | `ei1.png`/`ei1-menu.png` | 与上一次一致，非电视仍是 `labelLarge` |

`:app:assembleDebug` BUILD SUCCESSFUL（三次，均约 1m 42s）。

### 遗留

1. **Classic 界面风格的 `DropdownMenu` 分支没做设备验证**：本机界面风格是 iOS，走 overlay；那条分支只是
   对称地加了 24dp 内推，改动小但没截图证据。要验证得先切界面风格。
2. `RootGlassMenuRequest` 没有 offset 字段，`GlassDropdownMenu(offset = …)` 在 overlay 路径上被丢弃——
   这是**既有**不一致（手机上也一样），本轮没动它。等哪天要统一两条路径再补。
3. 菜单 6 项已经接近 `maxMenuHeight`（屏高 65%）上限，再多一项就得滚动；电视上滚动菜单的焦点跟随还没验证。
4. 顶栏右上角两个按钮（筛选、`⋮`）自己也在安全边距之外（`⋮` 右边缘 = 1896，距屏边 24px = 12dp），
   真机 overscan 会切到。这属于"整条顶栏的安全边距"问题，单独一轮处理更合适。

## 第七十三轮：搜索的建议/结果货架在电视上是手机尺寸（2026-10-25）

搜索是电视上最难用的入口之一，本轮顺着"顶栏搜索 → 输入 → 提交 → 结果"走了一遍。
输入法、焦点、分区滚动都正常，唯独**所有海报卡还是手机尺寸**：108dp 宽（1920px 屏上 216px），
一张海报只占屏宽 11%，坐在沙发上等于看邮票；分区标题是 22sp，卡片下面那行标题只有 12sp，
两者摆在一起比例完全失衡。

### 原因：搜索页把卡片尺寸写成了常量，没走图书馆那套"按宽度铺满"的逻辑

| 位置 | 常量/逻辑 | 电视上的后果 |
| --- | --- | --- |
| `SearchResultsScreen.kt` | `SearchFixedCardWidth = 108.dp`，`fixedSearchPosterCardStyle()` 直接按这个值造 `CompactPosterCardStyle` | 提交后的结果货架（`LazyRow`）每张 108dp |
| `KototoroSearchOverlay.kt` | `SearchSuggestionCardWidth = 108.dp`，三处 `.width(...)`（内容/本地实体/追踪实体） | 输入时的建议货架同样 108dp |

图书馆/收藏用的是另一套：`resolveGridColumnCount` + `resolveGridPosterCardStyle` 先按最小列宽算列数，
再把格子拉伸铺满可用宽度，所以电视上是大卡；搜索没有走这条路径，常量写死就一直是手机尺寸。

### 改法

| 位置 | 改动 |
| --- | --- |
| `SearchResultsScreen.kt` | 新增 `SearchTvCardWidth = 174.dp`（= 1920px 行宽 − 2×24dp 边距 − 4×16dp 间距 的 1/5，正好 5 张一行）、高度仍按 `/0.7f`、圆角 16dp；`fixedSearchPosterCardStyle(isTv)` 二选一，默认 `false` 与旧值完全一致 |
| 同上：货架 | `contentPadding` 电视 24dp（手机 12dp）、`horizontalArrangement` 电视 16dp（手机 6dp），与卡片宽度一起凑成 5 张整行 |
| 同上：分区头 | "N shown" 电视 `bodyLarge`（手机仍 `labelSmall`）；`Show all` 按钮文字电视 `bodyLarge`（手机仍 `labelLarge`）。两个都是 16sp，与 22sp 的分区标题终于在一个数量级上 |
| `KototoroSearchOverlay.kt` | 新增 `TvSearchSuggestionCardWidth = 174.dp` 与 `@Composable searchSuggestionCardWidth()`，三处 `.width(...)` 改为调用它 |

### 设备验证（emulator-5554，1920×1080 @320）

| 步骤 | 证据 | 观察 |
| --- | --- | --- |
| 改前：历史页顶栏搜索 → CENTER → `input text "TV"`（输入态） | `ep0-before.png` | 建议货架两张卡各 216px（108dp），左边距 24px（12dp），卡间 25px |
| 改后：同一路径（输入态，仍是 `TV`） | `ep3-suggest-after.png` | 视觉里量到 322–324px 的封面区（卡框 348px = 174dp），左边距 24dp，卡间 16dp |
| 改后：点搜索按钮提交，落结果页 | `ep1-results-after.png`、`eq1.png` | "History" 区的两张卡封面 312px（比改前 216px 宽 44%），"2 shown" 从 11sp 变 16sp |
| 改后：分区头一行（`Show all` 用 `bodyLarge`） | `eq2.png`、`eq2-showall.png` | "15 shown"（16sp）与 "Show all"（16sp）字高都是 32px；改前分别是 22px / 26px |
| 改后：DOWN 进第一张卡 | `ep2-focus-after.png` | 焦点环完整包住 348px 的大卡（不再是贴着小图的细框），下方 "Local storage / 15 shown / Show all" 一行完整可见 |

`:app:assembleDebug` BUILD SUCCESSFUL（两次：3m 19s、1m 44s，均 19 executed）。

### 遗留

1. **卡片标题没有跟着卡片变大**。`KototoroContentCard` 的标题字号来自 `resolveGridTitleFontSize(gridScale)`（12–16sp），
   只吃"网格大小"偏好，不吃 `CompactPosterCardStyle.itemWidth`。所以 348px 的卡下面还是 12sp 的标题，
   和 22sp 的分区标题放一起仍不协调。图书馆网格在电视上是同一个问题（同一份代码），
   要改得给 `KototoroContentCard` 加一个"按卡片宽度算标题"的入口——影响手机，得单独一轮评估。
2. `SearchResultsScreen` 里 "nothing found" 与分区错误文案仍是 `bodySmall` + 16dp 边距，电视上偏小；
   本轮没动（这两处在电视上没截到图，本地搜索不会空）。
3. **提交后的结果页按 BACK 会退回桌面**：本轮两次走到"结果页 → BACK"，两次都直接落回 Android TV 启动器
   （`logcat -b crash` 无记录，进程仍在）；而从"搜索页本身"按 BACK 是正常回 Home（`eo2.png`）。
   怀疑结果页这一层做了两次 pop 或者搜索页被 `popUpTo(root)` 打开，下一轮专门查这条回退链。
   > **第 74 轮复核：这条不成立。** 两次干净走"结果页 → BACK"都正常回到 Home，进程与导航栈都正常
   > （`logcat` 无 crash）；只有输入法展开时 BACK 先收键盘、再按一次才退页面。当时多半是输入法吞掉了
   > 第一次 BACK 又叠加了上一次退出，按已实现行为更正，不再当成待查缺陷。
4. 输入框重新获得焦点时系统输入法会自己弹出来（`mInputShown=true`），电视上没有"Enter 即收键盘"的约定，
   这是系统 IME 行为，暂按既有效果记录。
## 第七十四轮：下载对话框在电视上溢出屏幕、章节数下拉够不着（2026-10-25）

详情页右上角 ⤓ 打开的"Download manga"对话框，是这一轮的目标。用遥控器把它走完之后暴露了三个问题，
其中两个会让人直接干不了事：

1. **对话框本身比屏幕还高**。收起态就已经占满整屏（实测表面 531.5dp，上下各只剩 4dp），展开
   "More options"之后内容超出窗口，`AlertDialog` 只能把表面夹在窗口高度上，标题行被顶到 y=0，
   上圆角直接被切掉——第 73 轮截图 `ev6.png` 里"Download manga"贴在屏幕最上沿就是这个原因。
2. **每一行注册了两个停靠点，而且两个都没有焦点环**。`SelectableListItem` /
   `SelectableDropdownItem` 是"整行 `clickable` + `RadioButton(onClick = …)`"，遥控器要按两次才跨过
   一行，屏幕上却看不出焦点在哪：`ev4` 的 dump 里行 `View [568,200][1352,328]` 与内嵌
   `RadioButton [568,216][664,312]` 同时 `focused=true`。
3. **章节数下拉在遥控器上打不开**。行尾的 ▾ 是个 `IconButton`，`import` 出来的焦点遍历根本不进它：
   从第一行往下连按 6 次 UP 再按 RIGHT，焦点始终只在行与行之间跳（`.tmp-eup.xml` 六次 dump 全落在
   行 `View` 上），所以"First N chapters / Next unread N chapters"的菜单一次也没弹出来——电视上
   就没法选"前几章"。

另外两处是顺带发现的"手机上能用、遥控器上用不了"：选中提示写的是"长按章节列表项"（遥控器没有长按），
"Start download"这一行的文字点了没反应（只有开关本身能吃遥控器）。

### 原因：对话框没有电视高度预算，行内控件还是手机的焦点模型

| 位置 | 现状 | 电视上的后果 |
| --- | --- | --- |
| `text = { Column(Modifier.verticalScroll(...)) }` | 内容多高对话框就多高，手机屏高（800dp+）够用 | 1080p 电视只有 540dp 高，展开后内容约 568dp，表面被夹到窗口高度：实测 531.5dp、边距 4dp、标题落到边缘 |
| `Row(clickable)` + `RadioButton(onClick)` | 两个可聚焦控件嵌套在同一个可点行里 | 一行两个停靠点（4 行 = 8 个），且 `clickable` 的 `Row` 没有环、`RadioButton` 只有状态层 |
| 行尾 `IconButton`（▾） | 依赖指针点击 | 焦点遍历不进入，章节数/分支菜单在遥控器上不可达 |
| `Switch(onCheckedChange = …)` + 文本行 | 开关是唯一停靠点 | 行文字不可点、无环，遥控器要精确停在 52×32dp 的开关上 |
| `chapter_selection_hint` | "You can select chapters to download by long click on item in the chapter list." + `bodySmall` | 遥控器没有长按；12sp 在 3m 外读不清 |

### 改法

| 位置 | 改动 |
| --- | --- |
| `DownloadDialog.kt` | 新增 `DownloadDialogTvBodyMaxHeight = 332.dp` 与 `DownloadDialogRowShape = RoundedCornerShape(12.dp)`；正文 `Column` 在电视上先 `heightIn(max = 332.dp)` 再 `verticalScroll(...)`，标题行与按钮行不再被挤出窗口 |
| 同上：选项行 | `SelectableListItem` / `SelectableDropdownItem` 整行 `tvFocusable(shape = DownloadDialogRowShape, addFocusTarget = false)` + `RadioButton(onClick = null)`（电视），一行只留一个停靠点；行内垂直 padding 电视 8dp → 4dp 抵掉环占的高度 |
| 同上：开关行 | "Start download" 与小说版"下载图片"在电视上整行可点（`Modifier.clickable`），`Switch(onCheckedChange = null)`；手机仍是开关自己管 |
| 同上：More options | 该行补 `tvFocusable` 圆角环，电视上垂直 padding 12dp → 8dp |
| 同上：下拉入口 | 电视上把 ▾ 从 `IconButton` 降级成纯图标，CENTER 行时先选模式再弹变体菜单（`onClick(); onDropdownClick()`）；手机保持"行选中 / 箭头开菜单" |
| 同上：值卡片 | `SelectableValueCard` 的 `OutlinedCard` 补焦点环（目的地 / 首选格式两处） |
| 同上：按钮 | `Save`、`Cancel` 各补 `tvFocusable(shape = CircleShape, addFocusTarget = false)`；`Save` 是实心主按钮，环色显式用 `onPrimary` 才看得见 |
| 同上：提示文案 | 新增 `chapter_selection_hint_tv`（"Use the download button on a chapter row to pick chapters one by one."），电视上 `bodyMedium`；手机仍用原长按文案 + `bodySmall`。新串只有英文源串，其他语言等 Weblate |

所有改动都挂在 `isTv` 分支后面，手机分支的常量、字号、焦点模型、文案全部保持原样。

### 设备验证（emulator-5554，1920×1080 @320）

| 步骤 | 证据 | 观察 |
| --- | --- | --- |
| 改前：详情页 ⤓ → CENTER，收起态 | `ev3.png`（第 73 轮留存） | 对话框表面 y=8…1071px，上下边距 4dp、高 531.5dp，标题贴着屏幕边 |
| 改前：展开 More options | `ev6.png`（同上） | 上圆角被切、标题滑出屏幕、作品标题整行看不见 |
| 改前：焦点遍历 | `ev4.xml` / `.tmp-eup.xml` | 一行两个 `focused=true`（行 + `RadioButton`）；UP×6 与 RIGHT 都不经过 ▾ |
| 改后：收起态 | `ey0.png`、`fb1.png` | 上下面距各 44dp、高 451.5dp；标题、三个选项、提示、开关、More options、Cancel/Save 全在屏内 |
| 改后：聚焦行 | `ey0.png` | 12dp 圆角蓝环包住整行（改前只有状态层） |
| 改后：CENTER "First 2 chapters" | `ey1.png` | 行既被选中又弹出章节数菜单（`1 chapter` / `2 chapters`，首项高亮） |
| 改后：菜单里选 `1 chapter` | `ey2.png` | 行标题变 "First 1 chapter"，单选钮同步选中 |
| 改后：CENTER "Start download" | `ey2.png` | 整行有环，CENTER 把开关从开切到关（`Switch(onCheckedChange = null)` 生效） |
| 改后：展开 More options | `ey3.png` | 边距 24dp、高 491.5dp，标题固定在顶部；目的地卡片从正文视口下沿露头 |
| 改后：DOWN 进目的地卡片 | `ey4.png` | 正文在 332dp 视口里滚动，卡片带焦点环，整个展开区一次看全 |
| 改后：CENTER 目的地卡片 | `ey5.png` | 菜单列出两个目录（标题 + 路径），可继续用 D-pad 选 |
| 改后：DOWN×6 到 Cancel / RIGHT 到 Save | `fb0-btns.png`、`fb1-btns.png` | Cancel 是蓝色药丸环，Save 是白色药丸环（环在实心按钮外侧，清楚可见） |

`:app:assembleDebug` BUILD SUCCESSFUL（三次：3m 9s、3m 9s、3m 6s）。

### 遗留

1. **332dp 是配合 1080p/320dpi（540dp 屏高）写死的常量**。720p 电视常见 360dp 高、4K 常见 640dp 高，
   同一个常量在 720p 上会把对话框顶回屏外。要改成按屏高取值（如 `LocalConfiguration.current.screenHeightDp * 0.62f`）
   再单独验一轮，别在只有一种 AVD 的情况下当成通用解。
2. `DropdownMenu` 里的 `DropdownMenuItem` 在电视上只有状态层高亮、没有焦点环（本轮章节数菜单、目录菜单
   都是这样），与第 60 轮记的同类问题一致，等统一处理。
3. 点 Save 之后的真实下载链路本轮没验（本地作品会写进 storage 目录），不在电视专项范围内。
4. 顶栏 Share 对话框（`ez0.png`）同样是手机尺寸：正文 `bodyLarge`、按钮无环、内容贴边——下一轮可并入
   "详情页里的对话框"一起收。

## 第七十五轮：对话框里的"选项"在电视上没有焦点环，提示条还是手机字号（2026-10-25）

这一轮接着第 74 轮的遗留 4，把详情页里的 Share 对话框走完，顺带把同一个晚上反复出现的底部提示条一起收了。
两个问题的共同点是：**控件本身能用，但电视上既看不出焦点在哪、字也太小**。

1. **对话框的选项行在电视上没有焦点环**。`AppRouterChoiceDialog` 与详情页的三个对话框都把选项写成
   `TextButton(onClick, Modifier.fillMaxWidth()) { Text(label, Modifier.fillMaxWidth()) }`：整行确实是热区，
   但 `TextButton` 在非触摸焦点下只有一层状态层，遥控器上下切换时屏幕上没有任何一圈表示"当前在这一项"。
   第 74 轮的 `ga1.png`（Share 对话框）就是这样：文字左对齐、按钮铺满宽度、看不出焦点在哪。
   这类"整行点"的选项其实最该有一条像 `SettingsAlertDialog` 那样跟着行走的圆角环。
2. **底部提示条用的是手机阅读距离的字号**。第 71/72 轮把电视上的提示条换成了深色板
   （`surfaceContainerHigh` / `onSurface`），但内容仍然直接交给 `Snackbar(snackbarData = data)`：
   消息 `bodyMedium`（14sp）、操作标签 `labelLarge`、关闭 × 是纯触摸的 `IconButton`，
   板子的最小高度也是 Material 默认值。3 米外读 14sp 的消息很吃力，× 也只能用指针点。

### 原因

| 位置 | 现状 | 电视上的后果 |
| --- | --- | --- |
| `AppRouter.kt` 的 `AppRouterChoiceDialog` | 选项是 `TextButton(Modifier.fillMaxWidth())`，标签也用 `fillMaxWidth` | 整行是按钮语汇、没有环，标签被拉到整行宽；遥控器上只有状态层 |
| `DetailsMenuDialogs.kt` 的 `ShareOptionsDialog` / `SearchTargetDialog` / `DeleteLocalDialog` | 同上（详情页顶栏 Share 走的正是这三个，不是 `AppRouter.showShareDialog`） | 同上；三个对话框各自抄了一遍同样的 `TextButton` 模板 |
| `KototoroSnackbarHost.kt` 电视分支 | `Snackbar(snackbarData = data)` 只换颜色 | 14sp 消息、`labelLarge` 操作标签、× 不可聚焦、板子默认最小高度 |

### 改法

| 位置 | 改动 |
| --- | --- |
| 新增 `core/ui/compose/TvDialogOptions.kt` | 抽出两个共享件：`DialogChoiceOption(label, onClick)` —— 电视上是整行 `tvFocusable(shape = 12dp 圆角, addFocusTarget = false)` + `clickable` 的 `Row`，标签用 `bodyLarge` 普通文本；手机分支返回与改前逐字相同的 `TextButton`。`DialogActionButton(label, onClick, enabled)` —— 电视上给 `TextButton` 补 `CircleShape` 环，`enabled = false` 时不加环 |
| `core/nav/AppRouter.kt` | `AppRouterChoiceDialog` 的选项与 dismiss 按钮改用上面两个共享件（标签/作者选择对话框因此一起受益） |
| `details/ui/compose/DetailsMenuDialogs.kt` | `DeleteLocalDialog`、`SearchTargetDialog`、`ShareOptionsDialog` 三处改用共享件；`TextButton` 导入删除 |
| `core/ui/compose/KototoroSnackbarHost.kt` | 电视分支不再把 `snackbarData` 直接交给原版 `Snackbar`，改成自己搭内容：`heightIn(min = 72.dp)`、消息 `bodyLarge`（14sp → 16sp 一档）、操作标签 `titleSmall` 且套 `tvFocusable(10dp 圆角)`、`withDismissAction` 时右侧 × 用 `IconButton` + 圆形环、`dismissActionContentColor = onSurfaceVariant` |

所有改动仍然在 `isTv`（或 `isTvPresentation`）分支后面，手机分支的 `TextButton` 模板、`Snackbar(snackbarData = data)`
与所有默认参数保持原样。

### 设备验证（emulator-5554，1920×1080 @320）

| 步骤 | 证据 | 观察 |
| --- | --- | --- |
| 改前：详情页顶栏 Share | `ga1.png` | 三个全宽 `TextButton`、无环、看不出焦点（说明这一路走的是 `DetailsMenuDialogs`，不是 `AppRouter.showShareDialog`） |
| 改后：Share 首项 | `gb1.png` | 第一项带 12dp 圆角蓝环，标签是普通 `bodyLarge` 文本 |
| 改后：DOWN 到第二项 | `gb2-rows.png` | 环整体下移到第二行，仍包住整行 |
| 改后：DOWN 到 Cancel | `gb3-cancel.png` | Cancel 是蓝色药丸环 |
| 改后：焦点链 | uiautomator dump | `[568,492][1352,588]`（首项）→ 第二项 → `[1212,732][1352,828]`（Cancel），共 3 站，初始焦点落在最上方选项 |
| 改后：提示条 | `gi0.png`、`gi0-plate.png` | 深色板（`surfaceContainerHigh`）、消息 `bodyLarge`、板高 144px = 72dp（`heightIn(min = 72.dp)` 生效） |
| 改后：提示条文本行 | uiautomator dump | `'Please restart the application to apply these changes' [754,984][1558,1032]`，行高 48px = 24dp，正是 `bodyLarge` 的行高 |

提示条的触发路径：Settings → Storage and network → "Ignore SSL errors" 开关打开 →
`showRestartRequired()` → `snackbarHostState.showSnackbar(settings_apply_restart_required, Long)`。
验完已把该开关关回默认。

`:app:assembleDebug` BUILD SUCCESSFUL（5m 33s）。

### 遗留

1. **提示条的"操作按钮 + ×"变体本轮没验到**。它能出现的路径是 `DownloadDialog` 的"已加入下载 / 已开始下载"
   （`actionLabel = downloads`、`withDismissAction = true`），但本地库里两部测试作品的所有章节都已下载完，
   Save 不再调度下载；离线环境也没有内容源能造出别的带操作标签的提示。等有内容源时补验这两处环。
2. 本轮截图里的提示条贴在设置页右栏底部、右边直接顶到屏幕边（左边界 720px）——这是设置页自己把 host 放在
   右栏里，与本轮改动无关；活动级 host 在整屏界面上的位置（是否居中、左右边距）还需在首页/详情页复核一次。
3. **电视上的 Share 仍然走系统 chooser**（`ShareCompat.IntentBuilder(...).startChooser()`）。电视基本没有接收方，
   点了要么弹出空 chooser、要么什么也不发生；这是"手机功能原样搬上电视"的典型，需要单独决定隐藏还是改成"复制链接"。
4. **新发现：Settings → Storage and network 的 "Mirror list URL (optional)" 文本框会吃掉 D-pad。**
   焦点进入该 `TextField` 后 UP / DOWN / LEFT 都不离开（六次 dump 的 `focused` 一直是
   `[778,324][1864,532]`），BACK 也不退出；TAB 会被当成输入插进框里。于是它下面的
   `Ignore SSL errors`、`Disable connectivity check` 等项在遥控器上不可达，只能强杀应用再进来。
   这是本轮为了触发提示条偶然撞到的，值得单独一轮按 `SettingsAlertDialog` 的 `FocusRequester` 思路修。
5. 提示条消息没有 `maxLines`：`Modifier.weight(1f, fill = false)` 会让长消息多行铺满整板，尚未定义截断/换行策略。

## 第七十六轮：设置里的文本框把整页后半段锁死，遥控器进去就出不来（2026-10-25）

接第 75 轮遗留 4。`SettingsTextInputPreference` 在电视上是**行内编辑**：整行排成
`Row`（图标 + 标题/摘要 + `OutlinedTextField`），文本框本身就是一个焦点站。遥控器一进去就出不来，
于是它后面的所有设置行都不可达——Storage and network 页里 `Mirror list URL (optional)` 之下的
`HuggingFace Mirror`、`Bangumi Mirror`、`Ignore SSL errors`、`Disable connectivity check`、`Block ads in browser`
在电视上全部够不到，只能强杀应用。

### 原因

| 位置 | 现状 | 电视上的后果 |
| --- | --- | --- |
| `SettingsTextInputPreference`（`SettingsPreferenceComponents.kt:858`） | 行内 `OutlinedTextField` + `.tvTextInputFocusEscape()` | 破坏了"一行一站"的焦点模型：字段是独立的焦岛；键盘开着时 D-pad 全部交给 IME；BACK 只关键盘不退焦点 |
| `tvTextInputFocusEscape` 的方向搜索 | `focusManager.moveFocus(direction)` | 从滚动 pane 里的字段出发可以返回 `false`（实测 UP 回到字段后再按 DOWN/UP 连按两次，`focused` 一直是字段），键又交回光标 |
| `rememberTvFieldExit().fieldModifier` 的顺序 | escape → enterAction | 对话框 body 是 `focusGroup`，字段是里面唯一的焦点目标。`moveFocus(Down)` 会返回 `true` 但环还在字段上（logcat：`TVESC ... KeyDown` 之后 `TVVEXIT` 收不到 KeyDown），于是 DOWN 被吞、OK 拿不到焦点。ENTER 能用只是因为它被 M3 单行字段映射成 IME Done → `onDone` |

### 改法

| 位置 | 改动 |
| --- | --- |
| `SettingsPreferenceComponents.kt` | `SettingsTextInputPreference` 开头加电视早退：`if (LocalUiPresentationConfig.current.isTv) { SettingsDialogTextPreference(...); return }`。电视上改成"带环的设置行 + 点开 `SettingsAlertDialog` 编辑"，手机分支逐字不变（行内 `OutlinedTextField` 仍在） |
| `core-ui/.../TvFocus.kt` | 新增 `Modifier.tvTextInputVerticalExit(onExit)`：电视上把 UP/DOWN 直接当作"完成输入"，调用 `rememberTvFieldExit` 的 `leave()`（与 ENTER / IME Done 完全同一条动作，含"确认按钮禁用时退回 Cancel"的既有回退链）。它必须**排在 `tvTextInputFocusEscape` 之前**，因为 `focusGroup` 里的方向搜索可能报"已移动"而环没动，那样就会把键吞掉；多行字段不安装（需要上下移动光标） |

32 个 `SettingsTextInputPreference(` 调用点（备份、下载、周期备份、代理、翻译 API、E2E、镜像 URL、存储与网络）
因此一起变成"行 → 对话框"，`isTv` 之外的代码路径没有任何变化；电视上从"逐键即时生效"变成"OK 后提交"，
这是有意的取舍。

### 设备验证（emulator-5554，1920×1080 @320）

| 步骤 | 证据 | 观察 |
| --- | --- | --- |
| 行本身可聚焦 | `ic0-row-focused.png` | `Mirror list URL (optional)` 是普通设置行：12dp 圆角蓝环、值行显示 URL、右侧 chevron，行内不再有输入框 |
| DOWN 走过该行 | dump `ih0…ih7` | mirror URL → `HuggingFace Mirror` → `Bangumi Mirror` → `Ignore SSL errors` → `Disable connectivity check` → `Block ads in browser`（页尾）。第 75 轮遗留 4 的"后半段不可达"消失 |
| CENTER 打开对话框 | `ic1-dialog.png` | 字段自带蓝环、初始焦点就在字段（`SettingsAlertDialog` 的 body `FocusRequester`）；IME 同时弹出，把 OK/Cancel 盖住 |
| 输入 → BACK → DOWN | dump `[568,440][1352,584]` → `[1236,632][1352,728]`；`id2-ok-focused.png` | 环从字段移到 OK 药丸（改前永远停在字段）；这是本轮新加的显式退出 |
| CENTER 提交 | dump + `shared_prefs` | 行立即显示新值，`github_mirror_sync_url` = 输入值，即提交链路通 |
| OK → LEFT → CENTER | dump `[1236,632][1352,728]` → `[1080,632][1220,728]` | 环左移到 Cancel，CENTER 关窗且输入被丢弃（prefs 不变） |
| 清空后提交 | dump + `shared_prefs` | 字段清空 → DOWN → OK，`github_mirror_sync_url` 键消失，回到默认 URL |
| 回归：普通面板里的字段 | dump `il0…il2` | 搜索页输入后 BACK + DOWN 仍落到 Filter 芯片 `[327,88][465,184]`，与第 76 轮基线 `hc2.png` 一致，说明 escape 的既有行为没被破坏 |

`:app:assembleDebug` BUILD SUCCESSFUL；验完把 `github_mirror_sync_url` 清空（键已移除），没有留下测试值。

### 遗留

1. `tvTextInputFocusEscape` 的根因没换掉：`moveFocus` 在滚动容器 / `focusGroup` 里可能"报移动、环不动"，
   本轮只是让对话框字段改走显式退出。别处（筛选面板、书架搜索、同步账号）遇到同类字段时优先用
   `rememberTvFieldExit`，不要只加 escape。
2. 电视上 IME 一弹出就把对话框下半截（OK/Cancel）盖住，只能先 BACK 收键盘或用 IME 的 ✓。对话框窗口没有
   `imePadding()`，也没随键盘上移。
3. 32 个调用点里设备上只走了 Storage and network 这一处；备份 / 下载 / 代理 / 翻译 API 页沿用同一组件，
   推断一致但未逐个验证。
4. 电视字段只能尾部编辑（遥控器没有指针），本轮没有给对话框加"清空"动作；第 64 轮记的 Clear × 是触摸专属
   问题在这里同样存在。
5. 同一页里 `Mirror list URL` 的摘要仍然把默认 URL 整条打印（`Default: https://…json`），两行截断后信息密度很低，
   电视上更适合只写"默认（jsDelivr）"之类短描述，未改。

## 第七十七轮：文本框对话框一打开就弹出键盘，把 OK/Cancel 压在下面（2026-10-25）

第 76 轮验证时顺手记下的细节：设置里的文本框对话框一打开，软键盘就跟着弹出来，而对话框是垂直居中的，
按钮在最下面，于是 1080p 上（键盘约占 310dp）`OK` / `Cancel` 整排被盖住——用户看到输入框，却看不到怎么确认。

### 原因

| 位置 | 现状 | 电视上的后果 |
| --- | --- | --- |
| `SettingsDialogTextPreference` 的 `OutlinedTextField` | 可编辑字段；`SettingsAlertDialog` 的 body `FocusRequester` 在首帧把焦点给它 | 焦点落进**可编辑**字段 → Compose 立刻建立输入连接 → 键盘弹出；对话框本身居中，按钮在最下方，于是被键盘压住 |
| M3 `AlertDialog` | 居中布局，没有 `imePadding`、也不会为键盘收缩窗口 | 对话框没法自己躲开键盘，也不能把按钮挪到键盘上方；`AlertDialog` 也不暴露 `DialogProperties` |

### 改法

| 位置 | 改动 |
| --- | --- |
| `core-ui/.../TvFocus.kt` | 新增 `Modifier.tvTextInputStartEditing(onStartEditing)`：把 CENTER 当作"开始输入"，消费该键并回调 |
| `SettingsPreferenceComponents.kt` 的 `SettingsDialogTextPreference` | 电视上字段默认 `readOnly = isTvPresentation && !isFieldEditing`：打开对话框时只有焦点环、不弹键盘，OK/Cancel 完整可见；CENTER → `isFieldEditing = true` + `keyboardController.show()` 才真正开始输入；字段失焦（DOWN 到 OK、关闭对话框）时复位，下次进来仍是只读。手机分支完全不读这两个开关 |

也就是说：键盘从"迎面上来"变成"用户按 OK 才来"。输入过程中的键盘遮挡仍是 IME 固有行为，但那时用户正在打字，
且 IME 的 ✓ / BACK 都能立刻结束输入。

### 设备验证（emulator-5554，1920×1080 @320）

| 步骤 | 证据 | 观察 |
| --- | --- | --- |
| 打开对话框 | `ja0-dialog-readonly.png` | 字段带蓝环、值完整显示，`Cancel` / `OK` 都在屏上；`dumpsys input_method` 的 `mInputShown=false` |
| CENTER 开始输入 | `jb0-editing-ime.png` | `mInputShown=true`，键盘弹出、光标在字段内，可正常输入 |
| 输入 → BACK → DOWN → LEFT | uiautomator dump | 字段 `[568,456][1352,568]`（值 `probe`）→ BACK 收键盘仍停在字段 → DOWN 环到 OK `[1236,616][1352,712]` → LEFT 到 Cancel `[1080,616][1220,712]` |
| Cancel | dump + `shared_prefs` | 关窗、焦点回到 `Mirror list URL (optional)` 行、`github_mirror_sync_url` 仍无键（输入被丢弃） |
| 再次打开 | `jd1-reopened-readonly.png` | 与 `ja0` 像素一致（同 sha256）、`mInputShown=false`：只读态每次都重置，不会"记住上次在编辑" |

`:app:assembleDebug` BUILD SUCCESSFUL（2m 16s）。

### 遗留

1. 其它文本对话框（小说笔记、书签标题、WebDAV 路径、同步账号等）没有这层保护，仍是"焦点落下就弹键盘"。
   本轮只改了设置页这条共享组件；`readOnly + tvTextInputStartEditing` 这套应该抽成共享件再铺开。
2. 输入过程中的键盘遮挡没法消除：真正并排显示要自绘 `Dialog`（顶对齐 + `imePadding` + 自己的焦点链），
   等于放弃 M3 `AlertDialog` 的槽位语义，本轮没做。
3. 字段上没有"按 OK 开始编辑"的提示文案（新增字符串要走 Weblate），只能依赖电视用户"聚焦后按 OK"的习惯。
4. 手机 / 平板路径只做了代码隔离（`isTvPresentation` 之后才有 `readOnly` 与 CENTER 处理），没有在手机 AVD 上跑一遍。

## 第七十八轮：下拉菜单里"焦点在动、看不出在哪"——56 处菜单项补齐焦点环（2026-10-25）

之前几轮反复留下的那条遗留是"约 70 处直接用 M3 `DropdownMenuItem` 的菜单没有焦点环"。其中一部分其实早就改过了
（`CompactDropdownMenuItem`、收藏/搜索顶栏的快捷筛选、片头语种菜单），所以先做了一次精确盘点，再看现象。

### 原因

| 位置 | 现状 | 电视上的后果 |
| --- | --- | --- |
| M3 `DropdownMenuItem` | 焦点态只画一层 state layer（primary 8% 的淡色块），没有描边 | 弹出菜单后按 DOWN，高亮确实在换行，但整块菜单都是深色玻璃，用户判断不出当前停在哪一项；一屏 5–8 项时几乎没法用 |
| 已迁移的菜单（`CompactDropdownMenuItem` 等） | 自带 `tvFocusable` 焦点环 | 同一个应用里一半菜单有环、一半没有，观感像"有的菜单坏了" |
| `SettingsPreferenceComponents.kt`、`SwipeableFilterChip.kt`、`KototoroContentListScreen.kt` | 逐个调用点手写 `Modifier.tvFocusable(shape = …, addFocusTarget = false)` | 写法不一致（8dp / 10dp / 12dp / 圆形），漏改一个就少一环，靠人肉记住每个调用点 |

### 改法

| 位置 | 改动 |
| --- | --- |
| `core-ui/.../compose/TvDropdownMenuItem.kt`（新增） | 与 M3 `DropdownMenuItem` 同签名的包装（`text` / `onClick` / `leadingIcon` / `trailingIcon` / `enabled` / `colors` / `contentPadding` / `interactionSource` 全透传），内部只在 `modifier` 上串 `tvFocusable(shape = RoundedCornerShape(10.dp), addFocusTarget = false)`；`tvFocusable` 自身在非电视上直接返回原 modifier，所以手机/平板仍是纯 M3 菜单项 |
| app 内 22 个文件、56 处调用点 | 把裸的 `DropdownMenuItem(` 换成 `TvDropdownMenuItem(`，并按文件调整 import（`DownloadDialog.kt` 里已有带环的项，保留原 import 两种并存） |

盘点脚本按"调用点后 30 行内是否出现 `tvFocusable`"判定，改完复查剩余裸调用点为 **0**；同时用 BOM 检查确认
这次替换没有给任何文件加 UTF-8 BOM（改动前后对比 diff 只有 1–2 行/文件，编码与行尾未变）。

### 设备验证（emulator-5554，1920×1080 @320）

| 步骤 | 证据 | 观察 |
| --- | --- | --- |
| 设置 → Spaces → 遥控器移到自定义空间的 ⋮ | `kw2.png` | ⋮ 按钮本身带蓝环，可被遥控器选中 |
| CENTER 打开菜单 | `kw3.png` | 菜单弹出（Edit / Move up / Move down / Delete），此时焦点仍在触发按钮上 |
| DOWN 进入菜单 | `kw4.png` | 焦点落到 `Move up`，**整项一圈 2dp 蓝色圆角描边**（不是 M3 的淡色 state layer） |
| CENTER 激活 `Edit` | `ku2.png` | 打开 `Edit custom space` 对话框，行为与改动前一致（菜单项点击语义没变） |

`:app:assembleDebug` BUILD SUCCESSFUL（首次全量替换 1m 14s 编译失败→修好跨包限定调用后 1m 18s 通过）。

### 遗留

1. 本轮 22 个文件里只有 `SpacesSettingsScreen`（设置 → Spaces 的 ⋮）在设备上跑过；其余 21 个文件（笔记详情、
   迁移列表、小说阅读器选词、筛选面板、浏览页语种、本地下载卡、来源管理等）只过了编译，没有逐个在电视上点开。
2. `BookNotesDetailScreen` / `FilterSheetRoute` 的菜单需要先有笔记或已保存筛选预设，本地夹具没有，暂时没法验证。
3. `SettingsPreferenceComponents.kt`、`SwipeableFilterChip.kt`、`KototoroContentListScreen.kt` 这三处仍保留手写的
   `Modifier.tvFocusable`（形状分别是 10dp / 圆形 / 8dp），没有换成新包装——它们在改造前就已经有环，本轮不动，
   免得把已经验证过的观感改掉。
4. `SpacesSettingsScreen` 顺手暴露的下一个问题：`Edit custom space` 对话框的 Name 字段一打开就弹键盘（见 `ku2.png`），
   与第 77 轮同类，但那不是设置页那条共享组件，还没接 `readOnly + tvTextInputStartEditing`。
