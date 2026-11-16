# Kototoro 体验简化实施与验收

目标：完成 2026-11-14《Kototoro 体验审查：代码复核与简化方案》的全部实施计划。默认体验简单、紧凑、主操作明确，保留高级能力、已有偏好和内容状态。

本文记录整个目标，不以某个小批次或单个编译通过代替完成。当前工作树包含前二十八批改动，尚未提交。

## 完成清单

| 项目 | 要求 | 当前状态 | 完成证据要求 |
| --- | --- | --- | --- |
| 1. 书架与显示选项 | 减少常驻工具；已选条件可见可取消；明确页面/全局范围；支持大字体 | 已实施第二十批（设备）：共享网格改为按 `宽度/字体比例` 分列（`homeQuickActionTileWidth`/`homeQuickActionRows`），1.5× 字号下首页从“5 列劈词”变成 3 列整词、1.0× 与改动前一致；显示选项面板与筛选弹窗在设备上逐张看过 | 宽窄窗口、字体放大、选择/分类/滚动状态回归；Android/桌面消费端验证（书架的封面网格在有内容时的大字体排版仍未验） |
| 2. 默认视觉 | 打磨现有 Material 与 iOS 风格默认组合，减少叠加视觉层；预设可预览撤销且仅改视觉键 | 已实施第十六批（桌面）：视觉预设下沉 `core-domain`（`VisualPreset`/`visualPresetApplication`，只写白名单里的 `theme`/`interface_style`，按键记录被替换的旧值），外观页顶部一拍设好整套观感、底下多一行「撤销预设」只还原它改过的键；来源栏卡片里那块搜索框不再叠第二层色（`DesktopOutlinedControlSurface`）；两种风格 × 明暗的渲染截图由外观探针输出到 `build/reports/desktop-smoke/` 并逐张看过 | 两种风格、明暗、背景图、缩减动态效果截图；偏好隔离测试 |
| 3. 首次使用 | 欢迎首页直接添加来源/本地导入/恢复/进入；权限与外观可后设 | 已实施第二十批（设备）：全新安装在模拟器上冷启动直接进向导，四步（简介/权限/外观/完成）全部走到并截图，完成页在“未添加来源”时如实说明并给下一步 | 共享页面测试、实际宿主路由验证、错误/取消可恢复；首次安装检查（装源成功分支与失败重试仍未在设备上试） |
| 4. 来源流程 | 一个日常入口，链接/文件识别，安装/启用预览，完成后可直接浏览；失败有下一步 | 已实施（第十批收尾，Android）：一个地址框 → 正文判类型（判不出来回九类清单）→ 提示框先给安装预览（几个来源、前 5 个名字、可随时启停）→ 导入完成后对话框直接「立即浏览」→ 识别路径上的失败给「重试 / 手动选择类型」。文件导入与三态安装策略保持原样 | 链接/文件/仓库/格式错误/无来源/离线/拒绝权限/取消启用测试；保留三态安装策略 |
| 5. 设置分层 | 外观、阅读、播放按常用/进阶/诊断组织；无需全局高级模式 | 已实施第五批：外观（字体语言前移、背景微调独立成组）、阅读（性能与兼容性折叠组）、播放（共享“更多播放设置”折叠组，Android 与桌面同一份内容）；旧键、旧值不变 | Android/桌面共享实现与平台差异依据；分层后所有旧选项可达 |
| 6. 搜索与术语 | 搜索覆盖隐藏项、旧名别名，定位到正确页/展开组；更新提醒与站点账号语义清楚 | 已实施第五批：折叠组内选项可被搜索展开并滚动到目标行（播放、阅读性能、全景高级），阅读设置按偏好切到所属标签页，返回时保留搜索结果；「章节检查更新→更新提醒」「追踪账号→站点账号与进度同步」改名并保留旧名别名 | 搜索定位、分组展开、返回恢复与重命名别名测试 |
| 7. 阅读器默认与覆盖 | 当前体验与默认设置入口含义清楚，按现有保存规则说明范围；字号/主题等就地可调 | 已实施第六批（说明层）：阅读面板底部注明“阅读模式与章节顺序按作品记忆、其余写回默认、颜色滤镜可另存本作品”，阅读设置页顶部注明此处即默认体验；小说字号/主题本就就地可调；偏好键与存储一律未改 | 漫画/小说、默认/本作品状态回归，旧偏好保存与读取一致 |
| 8. 详情主线 | 一个明确的开始/继续主动作；次操作按任务分组；不同内容类型保留线路/目录语义 | 已实施（第十一、十二批，桌面为主）：主操作判定下沉 `core-domain` 的 `detailsPrimaryAction`，桌面三种详情样式（普通摘要栏、影视英雄头、小说英雄头）现在共用 `DesktopDetailsPrimaryButton`，按钮说出要打开的章节（继续阅读 · 第 7 话）并真的从该章打开；次操作共用 `core-ui` 的 `DetailsSecondaryActionRow`，按任务排序（阅读方式 → 馆藏）并把状态放进语义（`selected`）与容器色；线路语义沿用详情目录已有的 `ChapterBranchChips`，主操作也按当前线路取章。Android 侧 `resolveReadActionLabel` 本就区分 继续/阅读/加载/无痕 | 无章节/加载/错误/有无进度/离线/多线路/无痕、多选测试和截图 |
| 9. 播放器与面板 | 播放/进度/选集/倍速/全屏无需横滑；低频功能可达；诊断后置；面板按能力呈现 | 部分实施第十三、十八、十九、二十批：控制条的空间判定是纯规则 `videoPlayerActionLayout`（按 `宽度/字体比例` 分 EXPANDED/COMPACT/NARROW 三档，只收宿主声明支持的动作），**并且规则是约束而不是建议**——控制条只画 `inline` 里的槽位，被取舍的动作一个都不画，溢出按钮在 `overflow` 非空时恒在行尾；屏幕锁提进核心控件（看片时盲摸要按到的按钮不该因窗口窄 40dp 进菜单）；两端补齐被移出动作的入口：Android 溢出菜单新增「播放倍速」（画质/片头/片尾/笔记本就有），桌面「更多」新增倍速项；诊断后置由两端共用一条排序规则（`VideoPlayerMoreSection` + `orderVideoPlayerMoreItems`），Android「视频详情」与桌面新增「播放信息」都落菜单末尾；第二十批在 Android 模拟器上真跑 instrumented 三档用例并导出三张真机截图 | 320dp 实际渲染（`desktop-app/build/reports/desktop-smoke/video-actions-narrow.png`）只有 6 个控件且全部在窗口内，真机 320dp（`build/device-reports/video-toolbar/video-toolbar-320-1.0.png`）、360dp/1.5×、840dp 同样成立；Android 溢出菜单观感与菜单内焦点移动仍缺 |
| 10. 导航与更新中心 | 四入口原型与实现；保留旧导航/恢复路径；首页摘要跳唯一完整视图；更新按作品/时间 | 已实施第十四批（桌面）：① 导航配置的读取补上迁移表——Android 拼法的 `FAVORITES`/`EXPLORE`/`FEED` 现在解析为本侧 `FAVOURITES`/`BROWSE`/`SUBSCRIPTIONS`，桌面没有对应页的 `LOCAL`/`DISCOVER` 是**写明跳过**而不是顺手丢弃，未知键不再连带整份文档回落到默认顺序；② 首页「更新」摘要的「全部」改跳更新中心（`showUpdates`），此前跳订阅时间线——摘要与其完整视图从此是同一个页面，与 Android 的 `onHomeViewAllUpdatesClick → UpdatedNavKey` 一致；③ 更新中心本就按作品归并（`displayMangaId ?: mangaId`）、置顶优先再按最后章节时间排序，四入口 = 五项可配置 + 恒在的「更多」。事件已见与章节已读的分离由既有探针把关 | 配置迁移、深链/通知/返回栈/空间范围；事件已见与章节已读不混淆；原型与实际页面验证 |
| 11. 恢复与状态 | 区分下载任务/已下载内容；筛选空态、来源/下载失败有恢复操作；同步备份显示状态 | 已实施第十五批（桌面）：下载页拆成「正在下载」与「已下载」两节（纯规则 `desktopDownloadSections`，两节各自保序、状态一分不丢），空页不再是死路（提示 + 「去书库挑选章节」按钮）；失败行继续、筛选空态清除筛选、浏览失败重试、同步备份的本地与自动上传统计都已在位并逐条核对过 | 无数据/过滤无结果/网络与任务失败/同步状态测试；不丢已保存数据 |
| 12. 跨平台与交付 | Android 与桌面实际调用共享呈现；iOS 按原生边界对齐语义；交付可测产物与完整验收 | 已实施第十七批：可测产物落地（debug APK `app-arm64-v8a-debug.apk` 221,632,053 字节；桌面 `Kototoro.app` 340 MB，`-PwithMacosDistribution` 构建并通过构建期的 ad-hoc 签名步骤）；全量回归 Android 单元测试 3332 项 0 失败、四个共享模块 1029 项 0 失败、`DesktopAppTest` 50 项 3 失败（与 HEAD 基线同三项）、`:core-domain:compileKotlinIosSimulatorArm64` 真实产出 iOS klib；下方「逐项完成审计」表逐个列出 12 项各自的把关证据与仍未闭合的缺口 | 两端编译与必要测试、实际运行平台说明、Android APK/桌面产物、iOS 适用变更验证、逐项完成审计 |

## 通用约束

- 常见场景的主选项一屏可达，允许滚动/展开适配小屏、大字体和多语言；不以缩小热区实现紧凑。
- 只对安全可逆调节使用即时生效；删除、清理、恢复和安装沿用明确确认流程。
- 不重建通用设置框架，不合并不同来源的作品身份，不增加平行 UI。
- 四入口为默认方案；保留已有自定义导航，迁移必须可恢复。首页只提供摘要与跳转，完整管理视图不重复实现。
- 将“看过更新事件”“读过章节”“加入收藏”“已下载”作为不同状态处理。
- 人类体验测试建议（5–8 位参与者、完成率/耗时/误操作/求助）尚未执行，自动测试不替代此证据。

## 已有验证记录

前四批已有 Android/桌面编译、共享欢迎页 4 项测试、筛选相关 5 项测试、设置搜索 7 项测试及 Android APK 构建记录。它们只覆盖对应局部；后续修改后重新执行受影响检查。

第五批（设置分层与搜索）：

- `./gradlew :app:testDebugUnitTest --tests "*SettingsSearchHelperTest" --tests "*SettingsSearchQueryTest" --tests "*ReaderSettingsSearchNavigationTest" :desktop-app:test --tests "*PlaybackSettingsContentTest" -PwithDesktopApp=true`：BUILD SUCCESSFUL，搜索索引 10 项、别名过滤 4 项、阅读设置分页 1 项、共享播放页 3 项通过。
- 搜索定位曾失败：搜索展开折叠组时 `AnimatedVisibility` 的展开动画让滚动落在 0 高度那一帧，`playback-setting-decoder` 判为未显示。改为“搜索跳转不播放展开动画、按最终高度参与测量”后通过；这条竞态是真实缺陷，不是测试写法问题。
- 该批只验证共享组件与搜索索引，未做真机交互与 Android 实机截图。

第六批（阅读器作用范围）：

- `./gradlew :desktop-app:test --tests "*ReaderOptionsScopeNoteTest" :app:compileDebugKotlin -PwithDesktopApp=true`：BUILD SUCCESSFUL，面板说明 2 项通过（有说明时显示、无按作品区分的宿主不显示）；`:core-ui:verifyUiStrings` 通过。
- 口径依据实查：阅读模式经 `ContentDataRepository.getReaderMode`、章节顺序经 `isChaptersReversed` 存在 `manga_preferences`，属按作品记忆；背景、缩放、翻页动画、双页等写 `AppSettings`，属默认设置；颜色滤镜有独立的“全局/本作品”保存入口。文字只描述这些既有行为，没有改变任何读写路径。
- 一次自造失败值得记下：`assertTextContains` 默认为整串相等而非包含，用截断前缀断言会被判失败；已改为 `assertTextEquals`。
- 尚未执行：真机上漫画/小说分别改默认与本作品的回归、旧偏好读取一致性实测。

第七批（来源识别）：

- 新增 `core-domain/src/commonMain/.../extensions/SourceInputDetection.kt`：`detectSourceInput` 只看地址（自定义 scheme、扩展名、常见仓库索引文件名），`sniffSourceConfigFormat` 只看已抓到的 JSON 顶层键（`packages`/`manifest_version` 判为仓库索引，`sites`/`lives`/`bookSource`/`ruleSearch`/`api` 判为来源配置，裸数组判为来源配置），判断不了就返回 `UNKNOWN`/`JSON_ADDRESS` 交给人工选择，绝不猜。顶层键扫描是手写的文本扫描器，正确处理字符串里的花括号与转义、嵌套对象与数组，不引入 JSON 依赖。
- 桌面 `DesktopExtensionImportDialog` 已消费它：输入地址后自动切换「扩展仓库 / TVBox·CMS·Legado」两个芯片，并在输入框下用一行小字说明识别结果；两个芯片保留，地址判不出来时仍是人工选择。
- 证据：`:core-domain:jvmTest --tests "*SourceInputDetectionTest"` 6 项、`:desktop-app:test --tests "*DesktopSourceAddressDetectionTest"` 2 项（自动选配置芯片、仓库索引保持并显示原因、判不出来时手选仍生效），加上上一批的 `ReaderOptionsScopeNoteTest` 2 项，`:app:compileDebugKotlin` 同时通过，BUILD SUCCESSFUL。
- 一次自我纠偏：曾顺手把 `tvbox://`/`legado://` 加进 Android 的 `add-repo` scheme 映射与清单，但仓库里 `UnifiedAddRepoDeepLinkTest` 明确断言未知 scheme 解析为 null（kind 参数才是权威），且 `tvbox://` 不是本应用拥有的 scheme，注册它有劫持风险。已整体回滚，Android 只在 `kind` 参数缺失时才走 scheme 回退。
- Android 单一入口（第八批）：`UnifiedSourcesDialogState.AddSourceAuto` 取代默认打开的九类清单——一个地址框、「识别并添加」、底部一行说明；「手动选择类型」保留原来的九类清单，判不出来时也会自动回到那里（不再只丢一条吐司让用户自己重来）。
- 分流链路 `UnifiedSourcesViewModel.resolveSourceAddress`：先看地址（`detectSourceInput`）。扩展文件链接直接提示改用文件导入；`.pb` 由地址本身判定为 Mihon 索引（protobuf 是二进制，按文本嗅探只会得到垃圾）；其余才抓正文交给 `resolveSourceKindForBody`。识别成功发 `SourceAddressResolved`，屏幕据此走**与手动流程完全相同**的 `ThirdPartyDisclaimer → 安装策略 → addRepositoryFromUrl`，第三方提示与询问/仅安装/安装并启用三态一个都没绕开。
- 判定规则（`SourceAddressRouting.kt`，纯函数）：正文 `sites`/`lives` → TVBox；`bookSourceUrl`/`ruleSearch`/裸数组 → Legado；`packages` → 仓库索引，再按包名后缀分生态（`.animeextension.` → Aniyomi、`.cs3` → Cloudstream、只有 `.jar` → JAR 插件仓库、其余 → Mihon）。判不出来返回 null，绝不默认。
- 证据：`:app:testDebugUnitTest --tests "*SourceAddressRoutingTest"` 4 项（TVBox/Legado 正文、按包名分生态、`.pb` 只看地址、读不懂就返回 null）、`:desktop-app:test --tests "*DesktopSourceAddressDetectionTest"` 2 项、`:app:compileDebugKotlin` 均通过。Android 新增文案 `unified_sources_recognise_and_add`、`unified_sources_choose_kind_manually`、`unified_sources_address_hint`、`unified_sources_address_is_file`、`unified_sources_address_unrecognised`（中英各一份）。
- 已知代价：识别阶段抓一次正文、随后 `addRepositoryFromUrl` 再抓一次（约多一个请求），因为各 kind 的既有分支自己负责抓取与校验；后续可以把已取到的正文透传下去。
- 安装预览（第九批）：识别成功后抓到的正文不再只用一次——Legado 用 `inspectLegadoJson` 取书源名、TVBox 用 `inspectTvBoxJson` 取站点名（与真正导入是同一套检查器，所以预览不会承诺和落库不一致）；仓库索引用新增的纯函数 `countSourceEntries` 数 `packages` 条目。结果随 `SourceAddressResolved` 进第三方提示框：`将添加 N 个来源。` + 前 5 个名字（更多写 `…+N`）+ 一行“添加后即可直接浏览；启用或禁用随时在本页调整”。
- 证据：`:core-domain:jvmTest --tests "*SourceInputDetectionTest"` 8 项（含 `countSourceEntries` 的对象条目计数、空数组、键名不符、嵌套不算、截断正文不抛异常）、`:app:testDebugUnitTest --tests "*SourceAddressRoutingTest"` 4 项、`:desktop-app:test --tests "*DesktopSourceAddressDetectionTest"` 2 项、`:app:compileDebugKotlin` 全部通过。新增文案 `unified_sources_preview_count`、`unified_sources_preview_note`（中英各一份）。
- 装完即可浏览（第十批）：JSON 仓库导入成功后 `importJsonRepository` 用与删除路径同一套 `jsonRepositoryIdForAction()` 定位刚入库的来源 id，发 `SourceImportCompleted(sourceIds, sourceCount)`；屏幕在构建对话框时才去 `allSources` 匹配（列表可能落后一帧），匹配到就以来源名列表给出「立即浏览」，点一下直接进该来源的浏览页；一个都没匹配上时如实写“已导入但尚未启用”，而不是给一个按了没反应的按钮。内联导入（没有仓库定位）不发这个事件，避免出现“已添加 0 个来源”。
- 失败下一步（第十批）：识别路径上的添加带 `fromRecognisedAddress = true`，VM 捕获异常后发 `SourceAddFailed(kind,url,title,message)`，屏幕给出「重试」（同样带失败上报）与「手动选择类型」，并说明“如果这个地址其实不是识别出的类型，也可以手动选择”。手动路径（用户自己选过类型）不弹这个框，仍走原来的消息提示——它已经知道自己在做什么。
- 证据（第九、十批合并）：`:core-domain:jvmTest "*SourceInputDetectionTest"` 8 项、`:app:testDebugUnitTest "*SourceAddressRoutingTest"` 4 项 + `"*UnifiedAddRepoDeepLinkTest"` 7 项（回归：scheme 回退未被改动）、`:desktop-app:test "*DesktopSourceAddressDetectionTest"` 2 项 + `"*DesktopExtensionImportTest"` 均通过，`:app:compileDebugKotlin` 通过（累计 21 项通过）。
- 仍未做：JS / LNReader / iReader / Cloudstream 正文形态不自动区分（仍走手动清单）；`InstallError`（扩展包安装失败）本身仍只有“关闭”，本轮补的是地址识别路径的失败恢复；Android 无设备，所有 Android 侧证据都只是编译 + 单元测试，UI 观感未经真机确认。

第十一批（详情主线的主操作）：

- 共享判定 `core-domain/…/core/details/DetailsPrimaryAction.kt`：`detailsPrimaryAction(chapters, currentChapterId, readThroughChapterId)` → `NONE / START / CONTINUE` + 要打开的章节 id 与标题 + 章节总数。优先级：没有章节 → NONE；历史当前章仍在当前线路 → CONTINUE 它；否则最远已读章的**下一章** → START 它；已读到最后 → START 回到第一章（读完的作品按“重读”处理）；都不是 → START 第一章。只用章节身份、不用百分比——跨线路残留的百分比会指到错误的章，像素位置本来就由历史恢复。
- 桌面：`DesktopTabletDetails` 的主按钮改为「继续阅读 · 第 7 话」/「开始阅读 · 第 1 话」（视频用观看/播放措辞），点击直接 `controller.read(那一章)`，不再落到“由阅读器自己猜”。标签映射 `desktopDetailsPrimaryLabel` 与投影 `desktopDetailsPrimaryAction` 单独成文件，便于 Android 后续复用同一份判定。
- 证据：`:core-domain:jvmTest "*DetailsPrimaryActionTest"` 6 项（无章节 / 首开 / 继续 / 跨线路章节不劫持 / 最远已读推下一章 / 读完回第一章）、`:desktop-app:test "*DesktopDetailsPrimaryActionTest"` 3 项（标签含章节名、start 与 continue 不同、视频与阅读措辞不同）、`:desktop-app:test "*DesktopFilterChipsTest"` 3 项与 `"*DesktopSourceAddressDetectionTest"` 2 项回归、`:app:compileDebugKotlin` 与 `:desktop-app:compileKotlin` 通过。
- 未做（第十一批时的记录）：Android 详情坞的按钮文案仍是 继续/阅读/加载/无痕 四态、不显示具体章节；当时次操作分组与多线路语义尚未核。

第十二批（次操作按任务分组，并把三处重复动作收敛）：

- 共享组件 `core-ui` 的 `DetailsSecondaryActions.kt`：`DetailsActionGroup`（READING / LIBRARY / REFERENCE）、`DetailsSecondaryAction(key,label,group,enabled,selected)`、`DetailsSecondaryActionRow`。行内按 `group.ordinal` 稳定排序（“怎么读”先于“留不留”），组内保持调用方顺序；每个按钮带 `DetailsActionGroupKey` 与 `DetailsActionSelectedKey` 两条语义，选中态同时换 `secondaryContainer` 容器色；`tagPrefix` 让各详情样式保留自己探针已在用的 tag；不可用的动作**画出来但按不动**（而不是消失），48dp 最小高。
- 桌面收敛：`DesktopTabletChrome` 里重复了三遍的「主按钮 + 收藏 + 无痕」动作块，现在换成一个 `DesktopDetailsPrimaryButton` 与一个 `DesktopDetailsSecondaryRow`（`DesktopDetailsActions.kt`），文件因此变短，三种样式也不可能再各自漂移。影视/小说英雄头保留自己的药丸造型（`shape`/`minHeight` 由调用方给），只把文案、可用性、目标章节统一到共享判定；`quickActions` 的排序改成先「无痕阅读」后「收藏」，与任务顺序一致。收藏文案仍是“加入收藏 / 取消收藏”（沿用本页原有措辞），状态由按钮高亮表达，不再靠文字暗示。
- 证据：`:desktop-app:test "*DesktopDetailsSecondaryActionsTest"` 2 项（组标签/选中态/点击回调/禁用不可点、阅读组排在馆藏组左侧）+ `"*DesktopDetailsPrimaryActionTest"` 3 项、`"*DesktopIncognitoTest"` 6 项、`"*DesktopFilterChipsTest"` 3 项、`"*DesktopSourceAddressDetectionTest"` 2 项回归，共 16 项通过；`:app:compileDebugKotlin` 与 `:desktop-app:compileKotlin` 通过。
- 剩余（第 8 项）：Android 详情坞的按钮仍不显示具体章节（无设备，改版式无法验证）；iOS 未涉及；详情页没有设备截图。

第十三批（播放器：核心控件不靠横滑，低频动作有新家）：

- 空间判定抽成纯规则 `core-ui` 的 `VideoPlayerActionLayout.kt`：`VideoPlayerControlsTier`（EXPANDED/COMPACT/NARROW）、`VideoPlayerActionSlot`（十个动作槽）、`videoPlayerActionLayout(availableWidth, fontScale, supports)`。判定用**有效宽度**（宽度 ÷ 字体比例），所以 900dp 配 2.0 字体比例会像 450dp 一样降档——大字体不再把按钮挤出屏幕；EXPANDED 门槛仍是原来的 `600dp × fontScale`，Android 在这个边界上的行为不变。输入未测量（宽度 0）时返回空布局，不猜。
- 控制条 `VideoPlayerBottomControls` 改用这份判定：核心槽（上下一集/选集）恒在行内；EXPANDED 全量平铺；COMPACT 只留核心 + 溢出；NARROW 再让倍速标签让位（它移到行尾，与低频动作同处），选集与全屏仍然在画面内。行上多了 `video-actions-tier` 语义，测试可以断言“定了哪一档”而不是量像素。
- 低频动作可达：行尾新增溢出按钮 `video-actions-overflow`，**只在宿主声明 `supportsMore` 时**出现（没有溢出菜单的宿主不会拿到一枚点不动的按钮）。
- 桌面补上真正的溢出菜单：`DesktopVideoMoreMenu.kt` 的 `DesktopPlayerMoreCapabilities` / `desktopPlayerMoreItems`（增强恒在——它是观众的开关不是文件的属性；清晰度/音轨/字幕只在文件真的可选时出现）+ 桌面的 `PlayerMenu.MORE` 窗口，`supportsMore` 由 false 改 true。此前窗口一变窄，桌面自己的轨道/增强/音量按钮就跟着 `trailingActions` 一起消失，没有替代路径。
- 证据：`:core-ui:jvmTest "*VideoPlayerActionLayoutTest"` 6 项（三档取舍、大字体降档、能力过滤、未测量返回空）；`:desktop-app:test "*DesktopPlayerControlsTierTest"` 4 项（渲染**真实控制条**在 900/420/300dp 上分别读到 EXPANDED/COMPACT/NARROW，300dp 时全屏按钮 `assertIsDisplayed()` 且溢出按钮在场，`supportsMore=false` 时溢出按钮不存在）；`"*DesktopPlayerMoreMenuTest"` 4 项。回归：`:desktop-app:test` 16 项、`:core-ui:jvmTest` 105 项全绿，`:app:compileDebugKotlin` 与 `:desktop-app:compileKotlin` 通过。
- 剩余（第 9 项）：Android 消费同一条控制条，但没有设备，档位在真机上的观感只由共享规则测试 + 编译背书，不是目视确认；“诊断后置”未动（桌面播放器没有诊断面板，Android 的统计入口未核）；键盘/遥控在播放器里的操作路径未测；无截图。

第十四批（导航与更新中心）：

- 配置迁移（`desktop-runtime` 的 `DesktopNavigationSettings`）：`nav_main` 是 Android 的文档，但这条侧栏有自己的词汇表，两者拼法并不一致——Android 写 `FAVORITES`（没有 U）、`EXPLORE`（本侧叫 `BROWSE`）、`FEED`（本侧叫 `SUBSCRIPTIONS`）。原来的读取只认本侧拼法，于是一份按 Android 词汇写成的文档会被读成“一个已知项都没有”，整条列表回落默认顺序，用户的排列静默丢失。现在 `resolveItem` 先认本侧名字、再走别名表；`LOCAL`/`DISCOVER`（桌面没有对应页）是显式跳过并写在注释里，不是 `mapNotNull` 的副作用。
- 首页摘要跳唯一完整视图：首页「更新」节的「全部」原先跳**订阅**页（时间线），而它概括的是“哪些作品有新章”——完整视图其实是更新中心。现在改跳 `showUpdates()`，与 Android 的 `onHomeViewAllUpdatesClick → UpdatedNavKey` 对齐；订阅页轮播的「全部」仍是另一个入口，两条路径都保留。
- 更新中心本来的形状就符合要求，未改：按作品归并（同一部作品的多条 track 合成一行、计数相加）、置顶优先再按最后章节时间、最后回落到检查时间与标题；页头「N 部作品更新 · M 章」。
- 证据：`:desktop-runtime:test "*DesktopNavigationMigrationTest"` 6 项（Android 拼法解析、混合文档保序、别名与正名去重、大小写与空白、无页目的地跳过且不牵连邻居、未知键不吃掉其余项）+ 既有 `"*DesktopNavigationSettingsTest"` 7 项回归；`DesktopUpdatesPageTest`（探针 `updates`）新增一段：从首页点 `home-updates-more` 断言落到更新中心，之后仍从订阅轮播进入，整条探针通过——这正是“摘要与完整视图同页”的端到端证据，旧代码在这一步会停在订阅页。`:desktop-runtime` 225 项测试全绿，`:desktop-app:compileKotlin` 与 `:app:compileDebugKotlin` 通过。
- 剩余（第 10 项）：没有设备/桌面安装包，深链、通知点击与返回栈未验证（桌面没有通知与深链入口，Android 侧无法在本环境跑）；空间范围（Space）在导航上的呈现未核；“四入口原型”是审查报告里的说法，仓库中没有对应的设计原型文件可比对，故只能按实现收敛，不能宣称“与原型一致”。

共享欢迎页曾以桌面 Compose 测试渲染 390px 画布并检查截图；这不是 Android 设备验证。当前没有据此宣称整个计划完成。

第十五批（恢复与状态，第十五批为桌面端）：

- 问题一：下载页是一张平铺列表。排队、下载中、暂停、失败和已完成全在一行里，只有每行那行小字写着状态，于是“哪些还要我处理”和“哪些已经躺在磁盘上”要靠眼睛逐条分辨；章节一多就退化成一锅粥。
- 改为两节：`desktop-app` 的 `DesktopDownloads.kt` 新增纯规则 `DesktopDownloadSections`/`desktopDownloadSections`——队列只收非 `COMPLETE` 的状态（排队/下载中/暂停/失败），已下载只收 `COMPLETE`，两节都保持下载队列自己的顺序，所以某一章完成时只是“从上面那节挪到下面那节”，不会把别的行顶乱；`isEmpty` 同时给空态用。面板画两个节标题（`download-section-queue`「正在下载（N）」/`download-section-completed`「已下载（N）」），行卡片与批量栏读的仍是同一份 `rows`，批量暂停/继续/重试/移除的目标没有改变。
- 问题二：空页只有一句 `downloads.empty` 的说明，没有任何出路。
- 改为：说明之下加“去书库挑选章节”按钮（`downloads.emptyAction` → `controller.library()`，标签 `download-empty-browse`），点它回到书架而不是让人自己找路。
- 恢复入口核对（本批只核对、不新做）：失败行本来就有「继续」（`download-resume`，探针里失败 → 继续 → 完成这条链路已经跑通）；书架的筛选空态有「清除筛选」（`library-clear-empty`，`DesktopTabletProbe` 已点）；浏览失败有 `browse-retry`（`DesktopJsonSourcesProbe` 已点）；同步备份页有本地副本与自动上传状态行（`webdav-local-status`、`webdav-auto-status`，`DesktopBackupsProbe` 已断言前者）。这些都不是本批新增，列出来是为了说明第 11 项要求的四个场景今天各自由谁把关。
- 证据：`DesktopDownloadSectionsTest` 4 项（非完成态全进队列、两节各自保序、每个状态恰好落在其中一节、空页与单章不算空）；`DesktopAppTest` 的 `downloads` 探针新增三段——空页断言提示与按钮存在并点它落到书架、暂停时只有「正在下载」节没有「已下载」节、完成后只剩「已下载」节；整条探针仍跑完暂停/继续/导出/清理的原有步骤。
- 这一批还翻出并修掉两个**我自己前几批引入的回归**（真实缺陷，不是测试写法问题）：
  - 第十二批把详情次操作换成共享 `DetailsSecondaryActionRow` 时，把测试标签写成了 `preview:favourite`/`preview:incognito`，而仓库里 8 个既有探针与 UI 测试（`DesktopAppTest` 的 downloads/tablet、`SharedTabletUiTest`、`DesktopIncognitoTest` 等）都按短横线 `preview-favourite` 找按钮，于是它们全部找不到节点。已把共享行的标签恢复成 `$tagPrefix-${key}`，并在代码里写明为什么必须是短横线。
  - 第十一批的详情主按钮把目标章节显式传进 `controller.read(chapter)`，而控制器的语义是“显式给章节 = 从该章第一页开始”，于是「继续阅读」不再恢复上次读到的那一页——读者探针在重开章节后停在 page 0 而不是 page 3，`ready(3, 2)` 超时。现在只有「开始/重新开始」（`DetailsPrimaryActionKind.START`）才传章节，`CONTINUE` 把章节交给控制器按历史恢复。
  - 顺带让播放探针跟上第五批的折叠组：解码器与两个不透明度滑块现在在「更多播放设置」里，探针先断言折叠时这三个节点不存在，点开组之后再断言存在，然后照旧验证两个对话框与两个滑块。
- 基线对照（避免把既有失败当成自己的、也避免把自己的失败留在树上）：用 `git worktree add --detach /tmp/kt-head HEAD` 起了一份干净的 HEAD（`cf1f1ea19`）跑同一套 `DesktopAppTest`，结果 50 项 3 失败（`the source test page…`、`Windows automatic chapter reading…`、`tablet layout…`）；本工作树修完后同样是 50 项 3 失败，且正是同样的三项。也就是说这三项在 HEAD 上就红，不属本批改动面，本批没有新增失败。
- 剩余（第 11 项）：没有设备与安装包，未做真机检查，下载页状态只能通过探针导出的 PNG 佐证，没有人眼看过实际观感；同步备份的两个状态行只核对了既有实现与既有断言，没有为本项新增测试；“筛选无结果”在下载页不存在（下载页没有筛选器），所以只在书架侧核对。

第十六批（默认视觉：预设可预览可撤销 + 少一层色）：

- 问题：外观散成六行（主题、界面风格、语言、三种详情样式），而真正“看起来对不对劲”的是**组合**。想换个观感得逐行点，点错了还得回忆原来是什么再点回去。审查要求“预设可预览、撤销，且只改视觉键”，正是这个组合成本。
- 预设模型下沉 `core-domain`（`core/appearance/VisualPreset.kt`）：`VisualPreferenceKeys.VISUAL_KEYS` 是**封闭白名单**（只有 `theme` 与 `interface_style`），`VisualPreset` 只带 `id`、两个文案键和 `values`——文案不进 `values`，所以加一条说明不可能顺带扩宽它能写的键；`visualPresetApplication(preset, current)` 返回两样东西：现在要写的 `changes`，以及**只含它真的改过的键**的 `undo`（已经等于目标值的键不出现，因此“按一个我已经在用的预设”不会生成一条把同样值“还原”回去的假撤销）。
- 三个预设今天是：`MATERIAL · 紧凑`（跟随系统 + Material）、`深色专注`（固定深色 + Material）、`iOS · 分组`（跟随系统 + iOS）。桌面外观页把它们摆在该组最上面一行（`visual-preset`），**摘要本身就是预览**：对话框每个选项的文字是「iOS · 分组 — 跟随系统 · iOS 风格」，也就是按下之前就能看见它会写哪两个值；应用后组顶部多一行 `visual-preset-undo`「撤销预设（恢复为 iOS）」，点它把 `undo` 里的键写回去并消失。
- 少一层色：来源栏是一张卡片，卡片里那块「查找来源」输入框原本再画一层 `surfaceContainer` 填充——同一屏里出现了“页面底色 → 卡片 → 卡片里的色块”三层。新增 `DesktopOutlinedControlSurface`（保持轮廓线、去掉填充；iOS 玻璃风格分支不变），来源栏的搜索框改用它，其它浮动控件仍走原 `DesktopControlSurface`（它们在页面底色上，本来就需要填充区分）。前后截图逐张看过：输入框与卡片同色，只剩发丝描边，筛选芯片仍保留填充以表示可点。
- 证据：`:core-domain:jvmTest "*VisualPresetTest"` 6 项（每个预设只写白名单键、绝不碰 `language`/`details_style_*`/`nav_items`、撤销只还原被替换的值、已在用则 `changes`/`undo` 双空、无旧值时写入但不记录撤销、预设若写了非视觉键直接 `IllegalArgumentException`）；`DesktopAppTest` 的 `appearance` 探针新增一段：在「更多 → 外观」里点 `visual-preset` → 选 `MATERIAL_COMPACT` → 断言界面风格真的变成 MATERIAL、主题真的变成 SYSTEM → 断言 `visual-preset-undo` 出现 → 点它 → 断言风格回到 IOS、主题回到 DARK、撤销行消失（这一步走的是真实的偏好存储与真实的状态流转，不是函数单测）；整个 `DesktopAppTest` 仍是 50 项 3 失败，与 HEAD 基线同三项。`:desktop-app:compileKotlin` 通过。
- 截图：`desktop-app/build/reports/desktop-smoke/` 里外观探针每次运行都会写 `appearance-write-{light,dark}-{browse,library,preview,settings}` 与 `dark-glass-browse` 共 7 张（明/暗 × Material/iOS），这批逐张打开看过：深色 iOS 的侧栏卡片、玻璃顶栏与右侧导航栏层次清楚；浅色 Material 的卡片与芯片对比正常；换掉输入框填充后侧栏不再出现第三层色。
- 剩余（第 2 项）：① 没有 Android 设备，**Android 的 Material/iOS 默认组合本轮一行未改也未目视验证**——本批的视觉结论只对桌面成立；② “缩减动态效果”在两端都没有独立开关，Android 跟随系统动画时长（`Settings.Global.ANIMATOR_DURATION_SCALE`），桌面没有等价项，所以这一条不是“没做”，而是现有实现里没有这样一个可截图的开关；③ 背景图（`ArtworkBackdrop` 的 ATMOSPHERE/GALLERY）在桌面没有对应渲染，Android 侧无法截图核对；④ 预设目前只有桌面消费，Android 外观页仍是逐项行，接进 Android 需要它先有同一组视觉键的读取写入面（下一步，不在本批）。

第十七批（跨平台与交付：产物、全量回归、逐项审计）：

- 产物（本轮真的构建出来，不是“编译通过”就算）：
  - Android debug APK：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`，221,632,053 字节。只有一个 APK 是仓库既有设定而非缺陷——`app/build.gradle` 里 `debugOnlyAbiBuild` 时 `activeAbiFilters` 收到 `['arm64-v8a']`，release/nightly 才全 ABI 拆分（`universalApk` 同理）。
  - 桌面：`desktop-app/build/compose/binaries/main/app/Kototoro.app`，340 MB，`./gradlew :desktop-app:createDistributable -PwithDesktopApp -PwithMacosDistribution` 产出；构建脚本在 `createDistributable` 之后会把随包的 WKWebView bridge 恢复可执行位，并在 `codesign --verify --deep --strict` 不过时做一次 ad-hoc 签名（Intel 镜像 jpackage 不签名，自更新器会拒装）。
- 全量回归（同一棵工作树上跑完）：
  - `:app:testDebugUnitTest` 3332 项 0 失败（596 个测试类报告）。
  - 共享模块：`:core-domain:jvmTest` 356、`:core-ui:jvmTest` 105、`:reader-core:jvmTest` 343、`:desktop-runtime:test` 225，合计 1029 项 0 失败。
  - `:desktop-app:test`（`DesktopAppTest` 探针套件）50 项 3 失败，失败的仍是 HEAD 基线那三项（`the source test page…`、`Windows automatic chapter reading…`、`tablet layout…`）。
  - iOS 边界：`:core-domain:compileKotlinIosSimulatorArm64` 本轮真实执行（非 UP-TO-DATE）并产出 klib，所以第十六批新增的 `core/appearance/VisualPreset.kt` 在 iOS 目标上可编译。这是**编译级**证据：iOS 应用本身没有构建、没有运行、没有截图。
第十八批（播放器：诊断后置，两端同一条排序规则）：

- 问题：两端播放器的「更多」菜单都是平铺清单。Android 的「视频详情」把编解码/渲染器/代理/计数器整份 dump 和「保存到本地」「设置」摆在同一格；桌面干脆没有播放信息入口——恰恰是出问题时要看的东西，平时最挡路，需要时又找不到。
- 规则：新增共享 `core-ui/src/commonMain/kotlin/org/skepsun/kototoro/video/ui/compose/VideoPlayerMoreSections.kt`——`VideoPlayerMoreSection`（FREQUENT / TOOLS / DIAGNOSTICS）加 `orderVideoPlayerMoreItems(items, sectionOf)`（按节序稳定分区、节内保序、少于两项原样返回），两端不再各写一份“把技术条目挪到最后”。`VideoPlayerMoreSectionsTest` 5 项：声明在中段的诊断条目被排到末尾、节内顺序不变、已排好的不动、无诊断条目时一字不改、空/单项原样返回。
- Android：`VideoActionDialogItem` 多一个 `section`（默认 FREQUENT，所以宿主既有顺序不变），`showOverflowMenu()` 的列表过一遍 `orderVideoPlayerMoreItems`，只给「视频详情」标 DIAGNOSTICS——技术 dump 从此落在「设置」之后，其余条目顺序一字未动。
- 桌面：新增 `DesktopPlayerMoreItem.PLAYBACK_INFO`（诊断节，恒在最后），点开一个只读的「播放信息」窗口，7 行：解码器（`hwdec-current`）、渲染器（`vo`）、视频编解码（`video-codec`）、音频编解码（`audio-codec-name`）、分辨率（`width` × `height`）、帧率（`container-fps`）、mpv 版本（`mpv-version`）；读不到的行写「—」，分辨率只读到一半也回退为「—」而不是给半组数字。行拼装是纯函数 `desktopPlayerDiagnosticsRows(map)`，菜单里诊断节之前画一条发丝分隔线；窗口用播放器既有的 `PlayerMenuWindow`（自带失焦关闭/Escape），没有再叠一层点击穿透的浮层。
- 证据：`DesktopPlayerMoreMenuTest` 8 项（恒在项、诊断恒在最后且全列表只有一个诊断节、空值回退、属性读取与分辨率拼接、分辨率半信息回退）；`core-ui` 的 5 项；`./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.video.*"` 113 项 0 失败；`:app:compileDebugKotlin`、`:desktop-app:compileKotlin` 通过；`:core-ui:jvmTest` 110 项 0 失败；`:desktop-app:test` 全量 519 项 6 失败——6 项里 3 项是 HEAD 基线那三条探针，另 3 项（`DesktopSourceFilterTest` 2 项、`SharedTabletComponentsTest` 的玻璃模糊 1 项）在干净 HEAD 工作树（`git worktree add --detach /tmp/kt-head2 cf1f1ea19`）上逐条复跑同样失败，与本批无关（前者要的 `icons/ic_source_legado.svg` 在 HEAD 的桌面资源里就没有）。
- 顺手修掉的陈旧测试：`DesktopVideoControlsTest` 里两条在第十三批之后就错了——它们仍假设 320dp 时倍速在行内、需要横滑才能拿到尾部。按现行契约重写为 6 项：NARROW 档的档位语义、行首四个控件可见、倍速仍在行尾但被推出视野（`assertExists` 而非“不存在”）、拖到尾部后倍速与溢出按钮真的可见且「更多」动作可触发、能从尾部拖回行首再点上一集、宽屏无横滑提示、遥控聚焦分别到达行内笔记按钮与最窄档的溢出按钮。这 6 项在本工作树全绿，而同样的两条旧测试在 HEAD 上是过的——即它们是被本工作树第十三批改红的，算在这份工作里。
- 剩余（第 9 项仍未闭合）：320dp 实测行内容约 618dp，「无需横滑」在窄屏上并未真正兑现（拖拽与尾部溢出按钮都要先把行拖过去）；要兑现得让控制条只画档位认可的 inline 动作、把其余动作交给溢出菜单，而 Android 溢出菜单目前只收了画质/标记/重载/外部播放器/投屏/画中画，笔记与倍速等没有入口，所以这是一次跨宿主的能力映射改动，本轮没有顺手做；Android 溢出菜单的实际观感仍无设备可看；桌面播放信息窗口没有在真实播放中截图（现有播放探针不覆盖这个菜单，行值只有单测级证据）；遥控/键盘只覆盖控制条的聚焦，没有覆盖菜单窗口内的焦点移动。

第十九批（控制条按档位真的取舍：窄屏不再需要横滑）：

- 先承认上一批记下的缺口：320dp 时控制条内容约 618dp，档位规则算出了 inline/overflow，行却照旧把所有动作都画出来——规则只是排序建议，不是约束，所以「无需横滑」并没有兑现。
- 这一批把规则变成约束：控制条只画 `actionLayout.inline` 里的槽位，其余一个都不画；溢出按钮的条件从「非 EXPANDED」改成「`overflow` 非空且宿主声明 `supportsMore`」，因此它在行尾恒在。
- 顺带把屏幕锁提进核心：`VideoPlayerActionSlot.SCREEN_LOCK` 现在是核心控件。看片时盲摸就要按到的按钮，不该因为窗口窄 40dp 被塞进菜单。
- 两端补齐被移出行的动作的入口，避免“更紧凑”变成“找不到”：Android 溢出菜单新增「播放倍速」（副标题是当前倍速，图标 `ic_timelapse`），画质/片头/片尾/笔记本来就在菜单里；桌面「更多」新增 `DesktopPlayerMoreItem.SPEED`（TOOLS 节），点击开既有的倍速菜单——桌面控制条在窄档丢掉的正是倍速标签。
- 证据：`desktop-app/build/reports/desktop-smoke/video-actions-narrow.png`（测试里真导出、逐像素看过）现在是 6 个控件——上一集、下一集、选集、锁、旋转、更多——全部落在 320dp 窗口内，没有一个被切断；`DesktopVideoControlsTest` 6 项（窄档只画 inline、倍速/清晰度/笔记 `assertDoesNotExist`、无横滑提示、行首控件直接可点、溢出派发 `OpenMore`）；`DesktopPlayerMoreMenuTest` 8 项（倍速恒在、TOOLS 节、诊断仍恒在最后）；`:core-ui:jvmTest` 110 项 0 失败（含 `VideoPlayerActionLayoutTest` 的 SCREEN_LOCK 断言）；`:app:testDebugUnitTest --tests "org.skepsun.kototoro.video.*"` 113 项 0 失败；`:desktop-app:test` 全量 519 项 6 失败（与上一批同一批 HEAD 既有失败，未新增）；`:app:compileDebugAndroidTestKotlin` 通过。
- 一并改掉的既有测试：`app/src/androidTest/.../VideoPlayerControlsLayoutTest.kt` 原本在 320dp 断言行内有「1.00x」，还断言底栏**不该**出现 options 按钮——那是旧契约。现在按档位分支：有效宽度 ≥380 断言行内倍速且底栏无 options，否则断言底栏 overflow 可点并派发 `OpenMore`。**这条 instrumented 测试在本环境没有设备，只做到编译通过，没有运行**。
- 剩余（第 9 项仍未闭合）：Android 行内观感与新增菜单行没有设备可点；遥控/键盘只在控制条内验证了聚焦，菜单窗口内的焦点移动仍无覆盖；桌面播放信息窗口仍无真实播放截图（探针不覆盖该菜单）；`leadingActions`/`trailingActions` 是宿主自己注入的按钮（桌面的 ±10s 与增强），不受档位规则约束，宿主注入过多时行仍可能变宽——这条限制写在这里而不是留给下次猜。

第二十批（第一次真的在 Android 上跑：设备补齐第 1/3/9 三项的目视证据，并揪出一处大字体真缺陷）：

- 前提变了：这台机器此前一直“没有 Android 设备”，所以本文里所有 Android 观感结论都只是代码判断。前十九批正文里的“无设备/没有设备可看”是**当时的记录**，没有改写；从此批起凡写“已实施（设备）”的，都是真的在模拟器上跑过并看过截图。这一批先建了模拟器——`android emulator create medium_phone`（Android 36 / google_apis_playstore / arm64-v8a，1080×2400 ≈ 411dp），`adb devices` 在启动约 90 秒后出现 `emulator-5554`，`sys.boot_completed=1`，debug APK 装上去能真跑。
- 第 1 项（书架与显示选项）在这一批发现并修掉一个真实缺陷：把系统字号调到 1.5×（`adb shell settings put system font_scale 1.5`，需强制停止后重启应用）后，首页「快捷访问」仍然是 5 列固定 68dp 的瓦片，而标签跟着系统字号变大——截图里读作 `Favorite / s`、`Bookma / rks`、`Extensi / on`、`Translat / ion`、`Downlo / ads`，五个词全被从中间劈开。根因不是换行策略，而是列数只按宽度算、不看字号：瓦片不变而字变大，必然挤不下。
- 修法沿用播放器那条既有口径（`宽度/字体比例` 决定档位），把它做成两条纯规则放进 `core-ui` 的 `HomeUi.kt`（`app` 与 `desktop-app` 消费同一份网格）：
  - `homeQuickActionTileWidth(preferred, fontScale)`：瓦片预算 = `preferred × fontScale`（夹在 1×–3×），于是 68dp 在 1.5× 时按 102dp 参与分列；
  - `homeQuickActionRows(itemCount, maxColumns)`：把 N 个瓦片均分成若干行（10 个在 3 列下是 3/3/2/2，在 5 列下是 5/5），行内不够的用 `Spacer(weight)` 补齐，最后一行不再孤零零多出一列。
  - 网格本身（`HomeQuickActionsGrid`）改为读 `LocalDensity.current.fontScale` 算列数、按行规则逐行渲染；旧的 `homeBalancedGridColumns` 一个字没动，`HomeTabletLayout` 与它的既有断言因此仍然有效。
- 证据（真的在设备上看）：
  - 1.5×：首页变 3 列、行分布 3/3/2/2，标签整词换行——`Wizard / Favorites / Bookmarks`、`Local storage / Downloads / Random`、`Extension / Translation`、`Reader settings / Settings`，没有一个词被劈开；
  - 1.0×：仍是 5 列 5/5，与改动前逐像素一致（没有为修大字体把常规字号弄坏）；
  - 同一台机器上 1.5× 的收藏页显示选项面板与筛选/排序弹窗也逐张看过：标题、范围说明、开关行、底部按钮都在屏内，未发现截断（唯一观感问题是面板最后一行开关的下内边距偏紧，未改，因为那是 `padding` 常量级调整、需要单独一轮视觉决定）；
  - 单元测试：新增 `HomeQuickActionsLayoutTest` 6 项（1.0/1.5/2.0 的瓦片预算、0.85 与 5.0 的夹取边界、411dp 窗口在 1.0/1.5/2.0 下分别得到 5/3/2 列、行均分、1..24 个瓦片 × 1..8 列的穷举不变量——总和不变、每行不超列数、没有空行、行长差不超过 1、空输入返回空）。
- 第 3 项（首次使用）第一次走的是真实安装路径，不是探针：全新装之后冷启动直接进向导，四步全部截图——① 简介页给出「添加来源 / 导入本地文件 / 从备份恢复 / 直接进入」四个出口；② 权限页三项（通知「授予」、已安装应用「确定」自动通过、后台存活「允许」）；③ 外观页较长（玻璃完成度 8 个芯片、换场、全景开关、空间开关、空间按钮位置），底部浮动条会盖住滚动中的卡片，滚到底后正文能完整露出（内容内边距是对的）；④ 完成页在“本次没有添加任何来源”时如实说明，并给「打开扩展管理 / 从备份恢复」与排查步骤。点完成进首页，快捷访问 10 项全部到位。
- 第 9 项（播放器）把此前“只能编译不能跑”的 instrumented 用例真的跑起来了：`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…VideoPlayerControlsLayoutTest` 在 `medium_phone(AVD)` 上 3 项全过（320dp/1.0、360dp/1.5、840dp/1.0），断言用的是真实无障碍边界与真实点击派发。
- 顺手修掉这条用例自身的一个坑：它以前把 `VideoPlayerBottomControls` 直接挂在空 `CompositionLocalProvider` 里，共享文案 `UiStrings` 没有宿主实现，标签回落到资源名本身——设备上的树里读出来是 `[rotate_screen]`、`[options]`，而测试找的是 `Rotate screen`，于是三条用例一律挂在“rotate 被裁掉”上（`Rect(0,0-0,0)`，其实是节点根本不存在）。补上 `LocalUiStrings provides AndroidUiStrings(activity)` 后标签才是用户真正看到的字，用例随即全绿；同时给失败路径加了一份无障碍树 dump，下次再挂能直接看到当时到底画了什么。
- 设备截图（`adb exec-out run-as … cat cache/…` 取出，因为 connected 跑完会卸载应用，改用 `am instrument` 手动跑同一套用例才能留住它）：`build/device-reports/video-toolbar/video-toolbar-320-1.0.png` 是 6 个控件（上一集、下一集、选集、锁、旋转、更多）全在该 320dp 盒子内；`video-toolbar-360-1.5.png` 是同一组 6 个控件在 1.5× 字号下仍全在框内、时间文字明显变大；`video-toolbar-840-1.0.png` 是宽档全展开——选集芯片（超长章节名被省略号收掉，不挤别人）、1080p、笔记、1.00x、片头、片尾、锁、旋转，且**没有**溢出按钮。
- 回归（同一棵工作树）：`:core-ui:jvmTest` 116 项 0 失败（含新增 6 项）、`:app:testDebugUnitTest --tests "org.skepsun.kototoro.home.*"` 17 项 0 失败、`:desktop-app:test` 全量 519 项 6 失败——就是本文已经记在案的那 6 项（3 条 `DesktopAppTest` 探针 + `DesktopSourceFilterTest` 2 条 + `SharedTabletComponentsTest` 的玻璃模糊 1 条），共享网格被桌面首页消费，所以这一轮桌面重跑是必要的而不是形式；`:app:assembleDebug` 与 `:app:compileDebugAndroidTestKotlin` 通过，APK 装机后即为上述截图。
- 剩余（这三项仍未算“全部闭合”）：第 1 项的书架封面网格没有在设备上验过大字体——这台机器没有内容源，收藏/历史都是空态，10 个快捷入口瓦片与显示选项面板已是真机证据，但“有封面的书架在 1.5× 下如何排”仍只有代码推理；第 3 项的向导走的是“没有任何来源”的分支，装源成功的分支与“添加来源失败后重试”没有在设备上试；第 9 项的设备证据只覆盖控制条本身，Android 溢出菜单（含新增的「播放倍速」行）还没有截图，菜单窗口内的遥控/键盘焦点移动依旧只在桌面侧覆盖。

第二十一批（继续在模拟器上走：把“无设备”那几行的设置面逐页看过，并去掉一处同屏重复文案）：

- 这一批不做功能改动，主力是把前二十批里只能靠代码判断的设置面（第 2、5、6 项）在同一台 `medium_phone` 上按 1.5× 与 1.0× 两种字号逐页走一遍，看什么真的成立、什么只是代码里成立。
- 设置根页（1.5×，`build/device-reports/settings/settings-root-1.5.png`）：分节标题（Reading & interface / Content & sources）、每行图标、标题 + 副标题多行换行都正常，没有内容溢出屏幕。观察到两处单行标题被省略号收掉——「Source preferences & con…」「AI / Manage translation, image enhancement, video enhan…」。这一批不改：单行标题是当前行组件的既有契约，改成两行是视觉取舍，记在这里而不是偷偷调。
- 设置搜索（1.5×，`settings-search-1.5.png`）：输入 `mirror` 得到三条结果，副标题给出所属页面（`Source preferences & content filters`、`Network` 两条），命中旧名与折叠项都对。
- 但点「GitHub Download Mirror」落地的行为还没到位（`settings-search-landing-noscroll-1.5.png`）：打开的是「Storage and network」的**页首**，而目标行在下面 Network 一节里，得自己往下滚。原因看清了：`LocalSettingsSearchTarget` 已经下发给每个设置页，但真正消费它的只有 `ReaderSettingsScreen`（用它切标签页），其余页面没有“按 key 滚到行”的实现。所以第 6 项里“滚动到目标行”目前只对实现了的页面成立，跨页面落地仍是缺口，记为剩余。
- 外观页（1.5×，`appearance-1.5.png`）：行结构、单选值、iOS 提示都正常；`ios (modern)` 与 `md3 expressive` 确认是 `app/src/main/res/values/strings.xml` 里的原始文案（不是未翻译泄漏），大小写属于文案取舍，未改。另记一处观感：App font 行下面那句 iOS 专属蓝色提示在 1.5× 下占三行且用强调色，与副标题抢注意力，是否降级为普通副标题需要视觉决定。
- 统一来源页顶栏（`unified-sources-1.0.png` / `unified-sources-1.5.png`）：一开始我怀疑「Repositories」标签被右侧搜索胶囊**压住**。逐像素量了一下顶栏那一行（y=125）：标签胶囊结束在 x≈701，动作胶囊从 x≈724 开始，中间有 23px 空隙——**没有重叠**，只是滚动视口在边界上把标签切断，1.0× 与 1.5× 都是这个行为。既然没有重叠就不改行为（只补证据），但给共享标签条加了 `testTag("extensions-tabs")` 与 `DesktopExtensionTabsWidthTest` 4 项，把“标签随字号与计数变长时不能超出宿主槽位、不能把宿主尾部动作挤出这一行”钉住——这条正是将来换掉 `horizontalScroll` 时会立刻踩的坑。
- 这一批唯一的产品改动：存储用量卡片同屏印了三遍「Storage usage」——分节标题、环形图画心说明、汇总行左侧标签。画心现在只留数字（`156.8 kB`），分节标题与汇总标签各自保留一个（一个标区块、一个标这一列数字），1.5× 前后对比见 `storage-usage-1.5-after.png` 与上一批的存储页截图。改动只有 `StorageUsagePieChart.kt` 里那一段 `Text`。
- 回归：`:app:testDebugUnitTest --tests "org.skepsun.kototoro.settings.*" --tests "*.settings.search.*" :core-ui:jvmTest` 共 302 项 0 失败（含既有的 `StorageUsagePieChartTest`/`StorageAndNetworkSettingsScreenTest`/`ReaderSettingsSearchNavigationTest`）；`:desktop-app:test --tests "*.DesktopExtensionTabsWidthTest"` 4 项 0 失败；`:desktop-app:test` 全量重跑 523 项 6 失败（本批新增 4 项后由 519 变 523，失败的仍是那 6 项既有基线：3 条 `DesktopAppTest` 探针 + `DesktopSourceFilterTest` 2 条 + `SharedTabletComponentsTest` 玻璃模糊 1 条），因为标签条是两端共用的；`:app:assembleDebug` 通过，重装 APK 后才是上面那些截图。
- 剩余：第 5 项目视只覆盖根页、外观页与存储与网络页，阅读设置（7）、下载（11）、导航配置（10）、详情（8）、播放（5）这几页还没在同一台机器上逐页看；1.5× 下单行标题省略号的处理未定；搜索跨页面“滚到目标行”未实现（机制已定位）。

第二十二批（把设置根页“单行标题被省略号收掉”这条从记账改成修掉，并修大字号下值列挤掉标题的布局缺陷）：

- 设置根页与搜索结果行的标题此前是 `maxLines = 1`，1.5× 下「Source preferences & content filters」被收成「Source preferences & con…」。两处标题改成两行（副标题仍是两行），并各留一行注释说明为什么标题要拿到第二行；设备 1.5× 下整句完整显示、1.0× 下仍是单行（`build/device-reports/settings/settings-root-1.5-batch22.png`）。
- 真正的布局缺陷在共享行组件 `SettingsValueRow`（Android 与桌面共用）：值列是“平铺 260dp 上限”，1.5× 下值列把整行剩余宽度吃光，权重标题被压到 0 宽——播放页展开「More playback settings」后的解码器行只剩「Prefer hardware (reco…」，标题「Video decoder」整段消失（本批前后的对照：`build/device-reports/settings/playback-value-row-1.5-before.png` 与 `playback-value-row-1.5.png`）。
- 修法不是调数字而是换规则：用 `TextMeasurer` 按当前字号的 `bodyMedium` 量一次值列文字的实际宽度，值列最多占整行 40%；量出来放不下就把**值移到标题下面**（副标题同一列、强调色），放得下就仍旧贴着 chevron 右侧一列。于是短值（`Default`、`Automatic`、`90%`）在 1.0×/1.5× 都还是紧凑的右侧一列，长值（`Prefer hardware (recommended)`）在两种字号下都完整折行显示，标题不再被裁（`playback-value-row-1.0.png`、`playback-value-row-1.5.png`）。
- 回归：`:desktop-app:test --tests "*.DesktopSettingsValueRowWidthTest"` 4 项（411dp 手机宽 1.0×/1.5×、1000dp 宽窗 1.5× 三种情形：长值折到标题下方并与标题左对齐、短值留在标题右侧、值不越出本行）；先证明它是**真回归**——把值列改回原来的平铺上限，1.5× 用例立刻失败（`value drifted left to 99.0dp`）。`SharedSettingsRows.kt` 之外只动了 `SettingsRootScreen.kt` 的两处 `maxLines`。
- 回归全量：`:app:testDebugUnitTest --tests "org.skepsun.kototoro.settings.*" --tests "*.settings.search.*" :core-ui:jvmTest` 242 项 0 失败；`:desktop-app:test` 全量 527 项 6 失败（本批新增 4 项后由 523 变 527，失败的仍是那 6 项既有基线：3 条 `DesktopAppTest` 探针 + `DesktopSourceFilterTest` 2 条 + `SharedTabletComponentsTest` 玻璃模糊 1 条）；`:app:assembleDebug` 通过，重装 APK 后两级字号各自复看播放页。
- 剩余：1.5× 下副标题仍会在两行内被省略号收掉（如「…and tag bl…」）；本批只修了标题与值列的争抢，没有改副标题的行数契约；值列实测宽度用的是 `TextMeasurer`（一次测量/行），大量行同屏时的测量成本没有单独压测。

第二十三批（空态的文案说真话：历史页不再把“从来没读过”说成“被筛选藏起来了”）：

- 设备巡查里发现：历史页在**完全没有历史**的情况下写的是「Nothing found / There are no manga matching the filters you selected / Reset filter」，点那个 Reset filter 也变不出任何东西。原因是空态只判断“当前有没有筛选”——`getEmptyState(hasFilters = params.filters.isNotEmpty() || 分组 ≠ All || 来源标签 || 搜索词)`——而不判断库里原本有没有历史；收藏页一直是对的（`FavouritesListHost.emptyStateOf` 先读 `library.allEntityIds` 再决定文案），历史页漏了这一层。
- 触发这条的第一个用户场景不是用户自己筛的：历史页（和收藏页）的「On device」快速筛选会在 `NetworkState.value` 为 false 时自动勾上（离线/网络未通过验证时“只显示设备上的内容”）。这台模拟器上报的默认网络没有 `NET_CAPABILITY_VALIDATED`（状态栏 5G 带「!」），所以一进历史页就自动带上了这条筛选，于是“库是空的”被讲成了“你筛掉了”。
- 修法：新增纯规则 `historyEmptyStateKind(hasAnyHistory, isNarrowed)`（`app/.../history/domain/HistoryEmptyStateRules.kt`，两值枚举 `NO_HISTORY` / `HIDDEN_BY_FILTERS`）；`HistoryListViewModel` 多一条 `hasAnyHistory`（来自同一份快照的 `rows.isNotEmpty()`，与列表共用，不额外查库），空列表时按规则选文案：真没有历史 → 原来那句「What you read will be displayed here / Find what to read in the «Explore» section」且**没有** Reset filter；确有历史但被筛掉 → 仍是「Nothing found + 被筛选」并给 Reset filter（文案与行为一字未改）。
- 设备复核（1.5× 字号，重装 APK 后冷启动进历史页）：`build/device-reports/settings/history-empty-1.5-batch23.png`——空态改成「What you read will be displayed here / Find what to read in the «Explore» section」，Reset filter 消失，统计卡与筛选芯片行照常（修前那句话是在同一轮巡查的 UI 层级里读到的，当时没有另存 PNG）。
- 回归：新 `HistoryEmptyStateRulesTest` 4 项（新装机两例都必须是 `NO_HISTORY`、有历史被筛掉必须是 `HIDDEN_BY_FILTERS`、有行时不得回落到筛选文案）；`:app:testDebugUnitTest` **全量 3336 项 0 失败**（第二十二批时是 3332）；`:app:assembleDebug` 通过并重装复核。
- 走过又回退的一版（留档）：曾把自动筛选的判定从 `NetworkState.value` 改成 `networkState.isValidated`，想让“验证还没完成的冷启动”不再自动勾「On device」。实测这台模拟器的网络**本来就没有** VALIDATED 能力（`dumpsys connectivity` 里没有、状态栏 5G 带「!」，但 `ping` 通），所以原来的判定是对的；而 `isValidated` 会忽略用户“关闭离线检查”这个设置，属于新引入的回归 → 整条回退，`HistoryListQuickFilter` 与 `FavouritesContainerViewModel` 现在与 HEAD 一字不差。
- 同一轮巡查把另外三个列表页的空态也在设备 1.5× 下看过：收藏页本来就是对的（`build/device-reports/settings/favourites-empty-1.5-batch23.png`：「It's kind of empty here… / No favorites yet」），订阅页（`feed-empty-1.5-batch23.png`：「New chapters of what you are reading are shown here」）也对，只有历史页错。
- 剩余：有内容时“筛选把历史藏起来”那条分支只在单测里验证（模拟器上没有历史）；Suggestion 页分“推荐未开启/被筛掉”两支，这两处文案没有在设备上复看；`NetworkState` 以 VALIDATED 为准，在访问不到验证探针的网络里会长期判定为离线，于是历史/收藏默认只显示已下载内容（既有行为，本批未改）。

第二十四批（大字号下真的会坏的浮动卡：收藏页「sources may be broken」一字一列，另补上有内容的书架/历史/详情设备证据）：

- 缺陷（1.5× 字号，设备实测）：收藏页底部那张「N sources may be broken」浮动卡，把提示语画成了一行一个字——无障碍包围盒 `Rect(84, 21 - 143, 1568)`（宽 59px、高 1547px），整张卡高 2085px，从底部一直顶到状态栏把整页盖住（`build/device-reports/settings/favourites-broken-banner-before-1.5-batch24.png`）。根因是卡片只有一个 `Row`：文案 `weight(1f)`，后面跟「Migrate」「Don't show again」和关闭按钮；1.5× 下这三个动作本身比整行还宽，Row 先量非权重子项，权重文案只剩下 ~30px，于是每个字符占一行。
- 修法：`SourceHealthBanner` 拆成“有状态的壳（读设置、读 `sourceCount`）+ 无状态的 `SourceHealthBannerContent(message, onMigrate, onDisable, onDismiss)`”，内容改成纵向两段——文案独占一行，三个动作在下一行右对齐。不测量、不设宽度上限，所以任意字号都成立；关闭按钮顺带补上 `contentDescription = R.string.close`（原来是 `null`，读屏软件读不出这颗按钮）。设备 1.5× 复核：文案一行、动作一行，卡片高约 190px 不再遮挡（`favourites-broken-banner-after-1.5-batch24.png`，同一张图里还能看到「Continue reading」轨道与「Total 5 works」网格）。
- 先证明是真回归再算修好：新增 instrumented `SourceHealthBannerLayoutTest`（411dp 宽 × 1.0/1.5/2.0 三档字号），断言文案节点的真实无障碍包围盒宽度 ≥ 卡片宽 40%、高度不超过宽度（一字一列必然违反），并真点 Migrate / Don't show again / Close 校验回调确实被调用。把这个用例对着**旧 Row 布局**跑，1.5× 用例立刻失败并打印 `message is clipped at font 1.5: Rect(84, 21 - 143, 1568)`；换回新布局后 `:app:connectedDebugAndroidTest --tests ...SourceHealthBannerLayoutTest` 3 项全过。
- 顺带把“库里有内容时什么样”这条欠账补上（第一、八、十一项都缺这几张图）。做法是给桌面侧加一个 env-gated 播种器 `desktop-runtime` 的 `DesktopDeviceSeedBackupTest`（只有设了 `KOTOTORO_SEED_BACKUP=<zip 路径>` 才执行）：用共享 `SourceLibrary` 写 6 部作品（4 漫画 2 小说）与 8 章、两个分类「Manga shelf」「Novels」、每部一条历史与页面进度，再用桌面备份导出器写成标准 `.bk.zip` → `build/device-reports/seed/kototoro-seed.bk.zip`（3.0 KB，节包含 `index`/`categories`/`favourites`/`history`/`projections`）。设备上 `adb push` 到 `/sdcard/Download/`，走 Settings → Backup and restore → Restore backup → 系统文件选择器的 Downloads → 选该 zip → 合并恢复，收藏与历史立刻有内容。**注意**：`connectedDebugAndroidTest` 会重装并清空应用数据，所以真机目视必须在 instrumented 测试之后做，必要时重新导入一次种子包。
- 设备复核（1.5× 字号，`medium_phone`）：`favourites-grid-1.5-batch24.png`（Continue reading 横向轨道 + 「Total 5 works」4 列封面网格，长标题两行后省略号，如「A Rather Very L…」）；`history-rows-1.5-batch24.png`（Today 分组、5 张封面带心形角标、标题两行）；`details-broken-source-1.5-batch24.png`（详情坞主按钮「Read」+ 右侧展开箭头，标题三行不被裁）；`details-secondary-actions-1.5-batch24.png`（点箭头后弹出 Incognito mode / Remove from history / Download，浮层贴着底部坞）；`favourites-overflow-1.5-batch24.png`（顶栏溢出菜单 12 项带分隔线，滚动可读）。另外 `history-filtered-empty-1.5-batch24.png` 补上了第二十三批那条**有历史但被筛掉**分支的设备证据：模拟器离线时「On device」被自动勾上 → 「Nothing found + Reset filter」，取消勾选立刻出 5 行。
- 回归：`:app:testDebugUnitTest` 全量 **3336 项 0 失败**（与第二十三批同数，本批只加 instrumented 用例）；`:core-ui:jvmTest` 116 项 0 失败；`:app:assembleDebug` 通过并重装复核。桌面 `:desktop-app:compileKotlin` 本批未跑通——该模块要求外部固定 API 目录（`-PmihonCompatibilityDirectory`）在本机不存在，而本批没有触碰任何共享或桌面代码（`core-ui` 只跑测试未改），所以以 `:core-ui:jvmTest` 作为共享层回归。
- 同一轮巡查里先记成“探索页骨架一直停着”的那条，复核后要更正：骨架确实会自己走掉（离线时轨道服务的请求要失败一次，约一分钟），真正的问题是**骨架走掉之后只剩一片空白**——探索页一个可用来源都没有时，`Sources / Extension management` 两个页签下面是纯空，既不解释为什么空，也不给下一步（第二十五批处理，见下）；同一次目视还看到：轨道录像没有可用内容时，顶部仍保留 Hero 的占位高度，于是页签上方留白约 700px（本批未改，记在剩余里）。另外详情页“来源未安装”的作品会同时压上两条深色提示（broken + not supported），盖住主按钮，留给下一批。
- 剩余：种子的封面地址是 `https://fixture.invalid/cover/i.jpg`，离线只画同比例的占位卡，所以“网格比例与换行”验了、“真实封面裁切/圆角观感”没验；收藏页溢出菜单 12 项仍偏长（本批只记录不改，属于第二十五批以后的候选）；详情页那两张图来自“来源未安装”的作品，章节列表为空，所以章节行、批量选择与详情坞的“继续阅读”文案仍未在设备上看过；模拟器仍非物理机。

第二十五批（探索页“空”要说出是哪种空：骨架走掉之后不该剩一片白）：

- 缺陷（1.5× 字号，设备实测）：探索页在没有任何可用来源时，先画骨架（一张大灰卡 + 三条药丸），骨架走掉后页签下面**什么都没了**——`explore-blank-before-1.5-batch25.png` 就是那一刻：`Sources / Extension management` 两个页签下面纯空，没有一句话，也没有按钮。
- 根因不是“没算”，是**算完丢掉了**：`ExploreViewModel.buildList` 早就在来源列表为空时产出 `EmptyHint`（还按当前内容页签挑好文案：`no_manga_sources` / `no_novel_sources` / `no_video_sources` 与对应副标题），但 `EmptyHint` 在整个 app 里**没有任何渲染方**——`grep EmptyHint app/src/main` 只命中它自己的模型文件。Compose 化之后的探索页在 `prepareBrowseSourceItems` 里只收 `ContentSourceItem` 和 `LoadingState`，`EmptyHint` 被静默丢掉，于是页面落到“来源 0 个且不 loading ⇒ 什么都不画”。
- 修法分三层：① 判定下沉成纯规则 `sourceEmptyHintKind(totalSourceCount, visibleSourceCount, isLoading)`（`app/.../explore/domain/SourceEmptyHint.kt`，三值 `NONE / NO_SOURCES / HIDDEN_BY_FILTERS`）——它同时区分“还在取（继续画骨架）”“一个来源都没有（去找扩展管理）”“有来源但被页签/标签/预设/空间范围筛光（去重置筛选）”；② `EmptyHint` 带上 `kind`，ViewModel 按 kind 给**不同的动作**：筛掉的给 `reset_filter`，真没有的给 `welcome_open_extension_management`（原来两处都写死 `catalog`，而那个动作从来没被接上）；③ 探索页把 `EmptyHint` 收进 `BrowseSourceItems`，在来源区为空且不 loading 时渲染新组件 `ExploreSourcesEmptyHint`，动作分别路由到“清空来源标签 + 页签回到 All”和 `appRouter.openManageSources()`。
- 顺手去掉一处平行实现：`settings/sources/unified/UnifiedSourcesLists.kt` 里的私有 `UnifiedEmptyState` 被提到 `core/ui/compose/EmptyStatePanel.kt` 成为共享组件（图标块 + 标题 + 说明 + 可选主按钮），原调用点 3 处改为直接用 `EmptyStatePanel`，探索页用的是同一个，于是“空态长什么样”只有一份。
- 设备复核（1.5× 字号，重装 APK 后冷启动）：`explore-empty-state-1.5-batch25.png`——页签下方是「No content sources / Enable content sources to browse content online / Open extension manager」；改前同一位置对应 `explore-blank-before-1.5-batch25.png` 的纯空。CTA 走的是既有 `appRouter.openManageSources()`（与首页「Extension」快捷卡、来源区「管理」同一个入口），点按复核被同一时刻的 instrumented 重装打断，因此这一跳只在代码路径上确认，未重拍截图。
- 回归：新 `SourceEmptyHintTest` 4 项（有来源不提示、取来源时仍是骨架、零来源给扩展管理、被筛光给重置）；新 instrumented `ExploreSourcesEmptyHintTest` 3 项（411dp 宽 × 1.5× 与 2.0× 断言标题/说明的真实包围盒不被压成竖排，并真点动作按钮校验 `NO_SOURCES → manage`、`HIDDEN_BY_FILTERS → reset` 各自落到不同回调）；`:app:connectedDebugAndroidTest --tests ...ExploreSourcesEmptyHintTest` 3 项全过；`:app:testDebugUnitTest` 全量 **3340 项 0 失败**（第二十四批 3336，本批 +4），`:core-ui:jvmTest` 116 项 0 失败。
- 剩余：`HIDDEN_BY_FILTERS` 那条分支只有单测与 instrumented 证据，模拟器上没有已安装来源可用来“筛到空”，设备上没有复看图；重置动作会一并清掉与收藏/历史页共享的页签选择（`globalFavoritesState` 是同一份状态，属有意为之，但对别的页签有副作用）；轨道 Hero 在无可用内容时仍留约 700px 占位高度；桌面端探索页（`desktop-app` 的浏览页）走的是另一套实现，本批未对齐。

第二十六批（同一件事只讲一次、没有东西就不要占位：详情页两条深色提示、探索页 700px 死白）：

- 缺陷一（1.5× 字号，设备实测）：来源没装的作品打开详情页，顶部内联提示「This source may be broken (Not installed)」和底部浮出的「This source is not supported」是**同一个原因的两句话**，而且后者是 snackbar，正好压在「Read」坞上面（`build/device-reports/batch26/details-two-messages-before.png`）。根因是两条平行机制：`DetailsViewModel` 的 `onError` 经 `SnackbarErrorObserver` 弹 snackbar，页面另有 `sourceHealth` 派生出的内联提示；两者互不知道。
- 修法：判定下沉成纯规则 `detailsSourceNoticeKind(healthKind, sourceUnavailable)`（有健康结论就听它，没有则加载失败自带 `UNINSTALLED` 兜底）与 `detailsOwnsSourceError(isUnsupportedSourceError)`（`app/.../details/domain/DetailsSourceHealthRules.kt`）；`DetailsScreen` 收到 `UnsupportedSourceException` 时先置 `sourceUnavailable = true`，再据此**不再转发**给 snackbar——于是“来源不可用”这件事在页面上永远只有一句话，且自带唯一下一步「Migrate」。**第一次改法踩的坑**：先按“健康结论已加载”做条件（`shouldSuppressDetailsSourceError(sourceIsUnusable, …)`），设备上照样冒出 snackbar——`onError` 是 StateFlow，进页面即重放，而健康查询是异步的，失败先到、`noticeKind` 还是 `NONE`，抑制就落空了。日志实证（临时在 `SnackbarErrorObserver.emit` 打类名与调用栈）：`emit UnsupportedSourceException ← DetailsScreenKt$DetailsScreenContent$14$1$1.emit(DetailsScreen.kt:629)`。所以改成“失败自身设状态、再按失败类型决定是否上浮”，不再依赖时序。
- 抑制刻意收窄：只有 `UnsupportedSourceException` 走这条路（页面的内联提示已经覆盖它）；网络、解析、鉴权等失败照旧弹 snackbar，不会被顺手吞掉——`DetailsSourceHealthRulesTest` 5 项把这条边界钉住。
- 设备复核（1.5× 字号，重装 APK + 恢复种子后从历史进同一部作品）：`build/device-reports/batch26/details-one-notice-after.png`——顶部只有一张内联提示（文案 + 「Migrate」），底部 snackbar 不再出现，「Read」坞完整可见、可点；同一张图里主按钮与次操作箭头都在原位。点「Migrate」确实进换源面板（`migrate-sheet-narrow-text.png`）。
- 缺陷二（同一轮目视）：换源面板在 1.5× 下也把「No enabled sources of this type yet」画成一行两三个字并压住「Manage content sources」（`build/device-reports/batch26/migrate-sheet-narrow-text.png`）——这是与第二十四批同类的大字号横向挤压，**本批只记录不改**，留给后续批次（面板里那句提示与动作仍是并排）。
- 缺陷三（探索页，1.5× 字号）：上一批留下的“轨道 Hero 无可用内容时仍占约 700px 高”的欠账。根因是占位判定只看开关不看内容——`shouldShowBrowseHero = isBrowseTrackingRecommendationsEnabled`（默认就是开），于是列表开头照样插一条 hero 高度的 `Spacer`，而 `BrowseHeroBlock` 在 `heroItems` 为空且不在加载时画的是一个空的渐变 Box（`discoverHeroHeight(...detachedBottomContent = true)` = 262dp + `topContentInset`），两处叠加就是那片死白。
- 修法：把判定抽成纯规则 `shouldReserveBrowseHeroSpace(trackingRecommendationsEnabled, heroItemCount, isDiscoverLoading)` 与 `browseHeroHasContent(heroItemCount, isDiscoverLoading)`（`app/.../explore/domain/BrowseHeroVisibilityRules.kt`）；`ExploreHostScreen` 用它决定是否插 `Spacer` / 是否切到 0 顶内边距，`BrowseHeroBlock` 自己没有东西可画时直接不画（骨架仍算“有东西”，数据在路上时保留占位，避免先占后撤的跳变）。
- 证据：新 `BrowseHeroVisibilityRulesTest` 5 项；新 instrumented `BrowseHeroReservedSpaceTest` 2 项（411dp 宽，把 `BrowseHeroBlock` + 一行标记文本放进同一列，读标记的真实无障碍包围盒）——空内容时标记必须在 80dp 以内，加载中必须在 200dp 以下**之外**；用例自己 `screencap` 存到 `/sdcard/Download/` 当证据（`connectedDebugAndroidTest` 之后应用被卸载，私有目录里的图会被一起删掉）：`build/device-reports/batch26/browse-hero-empty.png`（标记贴在最顶，无灰带）与 `browse-hero-loading.png`（骨架带仍在，标记被推到约 700px 处）。
- 未能在真机复现“有来源 + 探索页无内容”这一画面的原因写在这里：种子备份恢复进来的来源其插件并未安装，可用来源数为 0，探索页落到第二十五批的 `NO_SOURCES` 空态（页面上根本没有 hero），而要装一个真来源又需要能联网的扩展。所以这一条的证据是上面两张由 instrumented 用例在设备上抓的真实布局截图，不是应用整页截图。
- 回归：`:app:testDebugUnitTest` 全量 **3350 项 0 失败**（第二十五批 3340，本批 +10：详情提示规则 5 项 + hero 占位规则 5 项）；`:core-ui:jvmTest` 116 项 0 失败；`:app:connectedDebugAndroidTest --tests ...BrowseHeroReservedSpaceTest` 2 项、`...DetailsSourceHealthNoticeTest` 3 项全过；`:app:assembleDebug` 通过并重装复核。桌面侧未动（本批只碰 `app` 模块与 `core-ui` 无关）。
- 剩余：换源面板大字号挤压未修（上面缺陷二）；`DetailsSourceHealthNoticeTest` 只断言内联提示的可读性与动作可达，没有断言“snackbar 一定不出现”的自动化用例（那条靠设备截图与日志实证）；探索页 Hero 有真实数据时的观感仍只由桌面探针覆盖。

第二十七批（第二十六批现场记下的大字号挤压：换源面板里“没有可用来源”被压成一列字）：

- 缺陷（1.5× 字号，设备）：在详情页点「Migrate」打开换源面板，那张“Manga · 2 entries”卡里「No enabled sources of this type yet」被压成每行一到两个词、竖着排了 9 行，旁边并排着「Manage content sources」，卡片一路从标题占到「Also migrate」——把下面两节全部顶出第一屏（`build/device-reports/batch27/migrate-sheet-before-1.5.png`）。根因与第二十四批收藏页那张破损卡**一模一样**：`Row { Column(Modifier.weight(1f)) { 文案 } ; Text("Manage content sources") }`，Row 先量非权重子项，1.5× 下这个标签自己就有约 660px 宽，权重列只剩一个词的位置。
- 修法：`FamilySourcesCard`（`app/.../migration/ui/config/MigrationConfigSheet.kt`）在无可用来源时改成**纵向两段**——说明句独占一行，动作标签落在它下面一行；整张卡本来就是 `Surface(onClick = onManageSources)`，所以动作仍然整卡可点，只是不再和句子抢宽度。非空分支不变（图标仍在行尾，摘要在左侧两行省略）。
- 先证明是真回归：新增 instrumented `MigrationFamilyCardLayoutTest`（411dp 宽 × 1.0/1.5/2.0 三档字号，`FamilySourcesCard` 由 `private` 改为 `internal` 以便测试直接渲染），断言说明句的真实无障碍包围盒（a）不能比自身高还窄（一字一列）、（b）宽度至少是卡片的一半、动作标签必须落在句子**下方**，并真点这张卡校验回调确实派发到 `onManageSources`。把用例对着**旧 Row 布局**跑，1.5× 那一档立刻失败并打印 `message must use most of the card at font 1.5: Rect(37, 219 - 379, 468)`（342px 宽、249px 高）；换回新布局后三档全过。
- 设备复核（1.5× 字号，重装 APK + 恢复种子后从首页进详情 → Migrate）：`migrate-sheet-after-1.5.png`——同一张卡现在是一句正常的两行句子 + 下面一行蓝色的「Manage content sources」，卡片高度从约 660px 掉到约 270px，「Also migrate」的四个芯片与「Matching」的两个选项都回到第一屏；同一张图里还能看到第二十六批那唯一的一条内联提示仍在详情页顶部。三档字号的独立截图：`migration-family-card-1.0.png` / `-1.5.png` / `-2.0.png`。
- 回归：`:app:testDebugUnitTest` 全量 3350 项 0 失败（本批只加 instrumented 用例，未加单测）；`:app:connectedDebugAndroidTest --tests ...MigrationFamilyCardLayoutTest` 3 项全过；`:app:assembleDebug` 通过并重装复核；`:core-ui` 与桌面未动。
- 剩余：换源面板里“已选来源”摘要与「Matching」两个选项在 2.0× 下的观感只由 instrumented 卡片刻度覆盖，没有整页截图；同一类“文案 + 行尾动作挤在一行”的写法在别处是否还有，本批只修了迁移卡这一处（没有做全仓库扫描）。

第二十八批（桌面浏览页的空态与 Android 对齐：别再让“0 sources”挂在一片空白上）：

- 缺陷：Windows 浏览页在没有内容源时，只在浮动标题栏里写一行灰字「0 sources / 安装或导入扩展后，内容源会显示在这里」，下面整片空白；有来源但被搜索或筛选藏光时连那行灰字都没有，页面看起来像坏了。Android 探索页在第二十五批已经会说“是哪种空 + 下一步”，两端口径因此分叉。
- 修法：判断“为什么空”的规则本来就是纯逻辑，却住在 `app` 里。本批把 `SourceEmptyHintKind`/`sourceEmptyHintKind` 下沉到 `core-domain` 的 `core/extensions/SourceEmptyHint.kt`（五个引用点改导入，`app` 里的旧文件删除，单测随规则搬到 `core-domain` 的 `commonTest`）；Windows 的 `DesktopSourceGrid` 去掉标题栏那行灰字，改成内容区里的空态面板 `SourceGridEmptyState`——没有任何来源时是「还没有内容源 / 安装或导入扩展后，内容源会显示在这里 / 扩展管理」，被搜索或筛选藏光时换成「当前筛选没有匹配的内容源 / 清空搜索词或取消筛选 / 重置筛选」（重置同时清搜索词与 `DesktopSourceFilter`）。面板按浮动标题栏的真实内边距居中，第一版没吃这个内边距、标题正好被浮层压住，截图上直接看得出来。
- 证据：规则单测 `SourceEmptyHintTest` 4 项在 `:core-domain:jvmTest` 下 0 失败；桌面新增探针 `browse-empty`（`DesktopBrowseEmptyProbe` + `DesktopBrowseEmptyStateTest`）在全新数据根上断言“无来源→按钮进扩展管理”，导入 fixture 扩展后再搜一个匹配不到的词，断言标题切换成筛选口径、点「重置筛选」网格回来，1 项 0 失败、日志 `DESKTOP_UI_OK=browse-empty`，两张状态截图 `build/device-reports/batch28/browse-empty-no-sources.png` / `browse-empty-filtered.png`；`:app:compileDebugKotlin` 与 `:app:compileDebugUnitTestKotlin` 通过。
- 回归：桌面全量 `:desktop-app:test`（必须带 `-PmihonCompatibilityDirectory=build/desktop-compatibility`）528 项 6 失败 6 跳过，6 项全部与本批无关且都是既有失败——`Resources.desktop.kt` 找不到 `icons/ic_source_legado.svg`（测试 classpath 少打一张图）、`DesktopSourceTagEntries` 期望列表还没跟上 `LEGADO` 标签、Skiko 背景玻璃在无头环境测不出模糊、以及三条依赖 fixture/网络的探针（来源测试、自动跨章、平板流更新）；`browse-empty` 在这次全量里 1 项 0 失败。`:app:testDebugUnitTest` 全量 3346 项 0 失败——比上一批的 3350 少 4 项，正是 `SourceEmptyHintTest` 那四条搬到 `core-domain` 的结果（条数对得上，不是用例丢了）。
- 剩余：两端只共享了**规则**，空态面板组件本身仍各写一份（`app` 的 `EmptyStatePanel` 与桌面的 `EmptyPanel` 视觉语言不同，真要共用得先拆“结构 + 主题映射”，是独立一批的量）。
- 交接：本批的完整状态、命令与坑位见 [`experience-simplification-handoff-2026-11-15.md`](experience-simplification-handoff-2026-11-15.md)。

- 逐项完成审计（12 项各自“谁把关、还缺什么”；缺的就是缺点，不拿编译或单测替代）：

| 项 | 状态 | 今天真正把关的是什么 | 仍未闭合 |
| --- | --- | --- | --- |
| 1 书架与显示选项 | 已实施（第二十批；有内容的网格与悬浮卡见第二十四批） | 模拟器（`medium_phone`，Android 36）上 1.5× 字号首页 3 列整词、1.0× 5 列不变（截图 `build/device-reports/home/home-quick-access-1.5-after.png`、`home-quick-access-1.0-after.png`，改前对照 `home-quick-access-1.5-before.png`）；1.5× 收藏页显示选项面板与筛选/排序弹窗逐张看过；第二十四批用种子备份在设备上灌入 6 部作品，1.5× 下看过「Continue reading」轨道 + 4 列封面网格、长标题两行省略号（`build/device-reports/settings/favourites-grid-1.5-batch24.png`），并修掉同屏「sources may be broken」浮动卡在大字号下一字一列的缺陷（`SourceHealthBannerLayoutTest` 3 项，前/后截图 `favourites-broken-banner-{before,after}-1.5-batch24.png`）；`HomeQuickActionsLayoutTest` 6 项 + `:core-ui:jvmTest` 116 项；已选筛选栏与「清除筛选」空态仍由 `DesktopTabletProbe` 真点 | 封面仍是占位图（fixture URL 离线不可取），真实封面裁切/圆角观感未验；分类/滚动状态没有专门的跨端回归用例；显示选项面板最后一行开关的下内边距偏紧，未改 |
| 2 默认视觉 | 已实施（第十六批，Android 观感见第二十一、二十二批） | `VisualPresetTest` 6 项隔离/撤销；外观探针真点预设与撤销；明暗 × 两种风格 7 张 PNG 逐张看过；设备 1.5× 下看过外观页（`build/device-reports/settings/appearance-1.5.png`），并确认 `ios (modern)`/`md3 expressive` 是资源原文而非未翻译泄漏；设置行标题的省略号已在第二十二批修掉 | 背景图与“缩减动态效果”无对应开关；Android 未消费预设；iOS 蓝色提示在 1.5× 下占三行的观感未定 |
| 3 首次使用 | 已实施（第二十批，设备） | 模拟器上全新安装后冷启动进向导，四步全走完并截图（权限页三项的真实反馈、外观页浮动条与内容内边距、完成页在无来源时的说明与下一步）；共享欢迎页与平台回调仍由 `DesktopAppTest` 向导探针覆盖 | 装源成功分支与“添加来源失败后重试”未在设备上试；错误/取消可恢复仅桌面侧覆盖 |
| 4 来源流程 | 已实施（第十批；探索页的来源空态见第二十五批） | Android 识别→预览→导入→立即浏览链路与失败下一步；三态安装策略未动。第二十五批补上探索页这一环：来源为零时不再是一片空白，而是“哪种空 + 下一步”（空库→去扩展管理、被筛光→重置筛选），规则 `sourceEmptyHintKind` + `SourceEmptyHintTest` 4 项 + instrumented `ExploreSourcesEmptyHintTest` 3 项（1.5×/2.0× 真包围盒 + 动作分流），设备 `explore-empty-state-1.5-batch25.png` 对照 `explore-blank-before-1.5-batch25.png`；第二十六批再把探索页 hero 的 700px 死白去掉（`shouldReserveBrowseHeroSpace`/`browseHeroHasContent` + `BrowseHeroVisibilityRulesTest` 5 项 + instrumented `BrowseHeroReservedSpaceTest` 2 项及设备 `browse-hero-empty.png` / `browse-hero-loading.png`）；第二十八批让 Windows 浏览页也说同一种话：规则下沉 `core-domain` 的 `SourceEmptyHint.kt`，桌面空态面板 + 探针 `browse-empty`（`DesktopBrowseEmptyStateTest`，两种空态与按钮去向都真点，截图 `build/device-reports/batch28/`） | 链接/文件/仓库/离线/拒绝权限等场景没有在本环境逐条真跑；`HIDDEN_BY_FILTERS` 分支无 Android 设备复看（模拟器上没有可筛选的已装来源，桌面侧已由探针覆盖）；安装成功/失败重试仍未在设备上走完 |
| 5 设置分层 | 已实施（第五批，设备抽看见第二十二批） | 折叠组共享同一份内容；旧键旧值不变，Android/桌面同时消费；设备 1.5× 下看过设置根页、外观页、存储与网络页、阅读设置页与播放页（`build/device-reports/settings/`，含折叠组展开后的解码器行），分节与多行标题/副标题都正常；同屏重复的「Storage usage」第三处已删；值列挤掉标题的大字号缺陷已修（`DesktopSettingsValueRowWidthTest` 4 项） | 下载（11）、导航配置（10）、详情（8）几页仍未在同一台机器上逐页看过 |
| 6 搜索与术语 | 部分实施（第五批；跨页面落地见第二十一批） | 折叠组内可搜索展开并滚动到行；改名保留旧名别名；设备 1.5× 下输入 `mirror` 命中三条并显示所属页面（`build/device-reports/settings/settings-search-1.5.png`） | 跨页面落地不滚到目标行（点「GitHub Download Mirror」只打开页首，`settings-search-landing-noscroll-1.5.png`）；`LocalSettingsSearchTarget` 目前只有 `ReaderSettingsScreen` 消费；别名命中率只由单测覆盖 |
| 7 阅读器默认与覆盖 | 已实施（第六批，设备抽看见第二十二批） | 面板底部与设置页顶部的范围说明；偏好键一文未改；设备 1.5× 下看过阅读设置页（General/Manga/Novel 三个标签、范围说明与各行折行都正常） | 漫画/小说实际阅读时的覆盖优先级未在设备上对照；桌面侧只看过探针 |
| 8 详情主线 | 已实施（第十一、十二批；设备证据见第二十四、二十六批） | `detailsPrimaryAction` 纯规则 + 桌面三种详情样式共用主按钮；次操作按任务分组；第二十四批在设备 1.5× 下看过详情页：主按钮「Read」独占坞内宽条、右侧箭头展开次操作浮层（Incognito mode / Remove from history / Download），长标题三行不被裁（`build/device-reports/settings/details-broken-source-1.5-batch24.png`、`details-secondary-actions-1.5-batch24.png`） | Android 详情坞文案仍四态；iOS 未动；设备截图来自“来源未安装”的作品，章节列表为空，章节行/批量选择与「继续阅读」文案仍未目视；第二十六批把“同一原因两句话”改成只有内联提示（`detailsSourceNoticeKind`/`detailsOwnsSourceError` + `DetailsSourceHealthRulesTest` 5 项，设备 `build/device-reports/batch26/details-one-notice-after.png` 对照 `details-two-messages-before.png`），换源面板“没有可用来源”那句在 1.5× 下的横向挤压已在第二十七批修好（`MigrationFamilyCardLayoutTest` 3 项 + 设备 `build/device-reports/batch27/migrate-sheet-after-1.5.png`） |
| 9 播放器与面板 | 部分实施（第十三、十八、十九、二十批；溢出菜单设备图见第二十四批） | 同上规则与两端入口 + 第二十批的真机运行：`:app:connectedDebugAndroidTest` 的 `VideoPlayerControlsLayoutTest` 在 `medium_phone(AVD)` 上 3 项全过，并用 `am instrument` 手动跑同一套用例留住三张截图（320dp 6 个控件、360dp@1.5× 6 个控件、840dp 全展开且无溢出按钮）；用例自身缺 `LocalUiStrings` 的坑一并修掉；第二十四批补上 1.5× 下收藏页顶栏溢出菜单的设备截图（12 项、带分隔线、可滚动可读，`build/device-reports/settings/favourites-overflow-1.5-batch24.png`） | 播放器内的「播放倍速」菜单行仍无截图（拍到的是书架页溢出菜单，不是播放器控制条菜单）；键盘/遥控只测到控制条聚焦，菜单内焦点未测；桌面播放信息窗口无真实播放截图；宿主注入的 leading/trailing 动作不受档位约束 |
| 10 导航与更新中心 | 已实施（第十四批） | 配置迁移 6 项 + 首页摘要跳更新中心由探针端到端断言 | 深链/通知/返回栈、Space 范围未验；无原型文件可比对 |
| 11 恢复与状态 | 已实施（第十五批；空态文案见第二十三批；有内容的历史见第二十四批） | 下载页两节 + 空态出路由探针断言；恢复入口逐条核对；历史页空态在设备 1.5× 下分成“真没有历史”与“被筛选藏起来”两种文案（`HistoryEmptyStateRulesTest` 4 项，`build/device-reports/settings/history-empty-1.5-batch23.png`）；第二十四批用种子备份走过完整恢复链路（系统文件选择器 → 预览 → 合并 → 库里有内容），并在设备上验到“有历史被筛掉”这条分支：`history-filtered-empty-1.5-batch24.png`（On device 自动勾上时 Nothing found + Reset filter）与 `history-rows-1.5-batch24.png`（取消勾选后 5 行带心形角标） | 下载页仍无设备目视（种子里没有已下载章节）；同步状态行只核既有实现 |
| 12 跨平台与交付 | 已实施（第十七批） | 本轮产物 + 3332/1029/50 三组测试 + iOS klib 编译 | 见下「交付剩余」 |

- 交付剩余（第 12 项，也是整个目标的剩余）：
  1. Android 侧从第二十批起有了**模拟器**（`medium_phone`，Android 36 / arm64），所以首页、显示选项面板、向导四步、播放器控制条、设置根页/外观页/存储与网络页与统一来源页顶栏已有截图（见 `build/device-reports/`），播放器控制条与首页网格还有真跑用例；但这仍是模拟器而非物理机，且深链、通知、权限长期行为、真实媒体播放、性能与发热都没有验证；
  2. **iOS 只有编译级证据**：`core-domain` 的新共享文件能在 iOS 目标上编译，但 iOS 应用没有构建、没有运行，iOS 原生边界的语义对齐没有验证；
  3. **人类可用性测试（5–8 人）没有做**，自动化测试不能替代它——本文件里任何“更简单了”的说法都只是本人的代码与截图判断；
  4. 第 1、3、9 项在第二十批之后各自只剩小口子：第 1 项“有封面的书架在大字号下如何排”已由第二十四批的种子备份补上（占位封面，真实封面裁切仍未验）；第 3 项缺装源成功与失败重试那条分支；第 9 项缺播放器内「播放倍速」菜单行（书架页溢出菜单已在第二十四批拍到）、遥控/键盘在菜单窗口内的焦点移动、以及桌面播放信息窗口的真实播放截图；
  5. 工作树仍有约 40 个修改文件与 14 个未跟踪文件**全部未提交**（按仓库约定不由代理提交），所以这些产物来自未提交的工作树而非一次已落库的提交。

## 第二十九批（2026-11-15）：存档与观感类系统问题

从这一批起改为简短记录。工作树已在分支 `feat/experience-simplification` 上按主题拆成 11 个提交（前二十八批），
之后每个修复单独提交。拆分只验证了最终状态可编译，中间提交未逐个编译。

- **浮层透字**：底部面板与弹窗菜单的回退底色由 0.98 改为不透明（`GlassSurface` 的 `dialogSurface` 分支）；
  根层玻璃菜单的默认底色 0.42 → 0.62、模糊 6dp → 16dp（`GlassDropdownMenu`）；设置页全宽顶栏加一层 0.62 的底
  （`SettingsTopBarSurface`）。设备截图前后对照在 `build/device-reports/batch29/`，面板内原先能读到的页面文字像素差为 0。
- **换源面板**：单个作品标题不再写 “Migrate 1 entries”；未安装来源的 `MIHON_7000000000000000001` 显示为
  `Mihon …0001`（`unresolvedSourceLabel`，`MigrationSourceTitleTest` 2 项 0 失败）；去掉锁定的「Categories」芯片
  （下方说明句保留）；两张匹配卡等高、图标固定；“没有可用来源”改中性色；分节标题加重。1.0× 与 1.5× 均已在模拟器截图。
- **收藏页破损横幅**：横幅高度并入列表底部内边距，最后一行可以滚到横幅上方。

未验与遗留：
- `MigrationFamilyCardLayoutTest`、`SourceHealthBannerLayoutTest` 等 instrumented 用例本批未重跑。
- 详情页点「Migrate」后面板约 2–3 秒才出现（模拟器），期间再点一次会把刚打开的面板关掉，原因未查。
- 设置页玻璃顶栏四周有一圈矩形细线；主页面顶部标签栏下仍能看到滚动内容的模糊影子（玻璃质感预设决定，未动）。
- 界面风格对话框等 `AlertDialog` 是否仍透字未复查。
- 模拟器已设全局代理 `10.0.2.2:7890`、字号 1.0，尚未安装真实来源。
- 其余观感问题清单（大字号列数、内部值外露、设置行不统一、重复信息、向导）尚未开始。

## 第三十批（2026-11-15）：改用真机

模拟器已删除（磁盘满），之后在已连接的真机上只读查看（不迁移、不删除、不恢复备份、不跑 instrumented 用例）。

- **未验证网络被当成离线**：真机 Wi-Fi 未通过系统联网探测，收藏与历史被自动勾上「本地」而显示为空。
  改为仅在完全没有网络时自动勾选（`HistoryListQuickFilter`、`FavouritesContainerViewModel`）。真机重启后两页恢复内容。
  封面等自动流量仍按“已验证”门控，未改，待决定。
- **扩展管理空态**：没有任何来源时给「添加源」，并隐藏禁用的批量操作；被筛选藏光时给「重置筛选」。
  后者已在真机验证（清空搜索词、列表恢复、来源数不变）；前者真机有来源，未验。
- **筛选角标**：默认的“可用”不再计为已应用筛选（原先恒为 1，重置也清不掉）。已编译，真机未验。

观察（未改）：详情页“元数据来源 | 阅读来源”两段在同一来源时显示两个相同名字；底部导航在封面上方时标签对比度低；
收藏页没有已选筛选时排序图标独占一行（计划第 1 项“只显示已选条件”的结果）。

## 第三十一批（2026-11-15）：真机复核与设置页一致性

真机复核（均通过）：放开离线判断后封面与来源图标正常加载、详情页背景正常（上一批看到的纯色底是刷新中途）；
导航玻璃加底后底部导航在封面上方可读；扩展管理筛选角标默认不再显示 1。

- **自动流量**：有默认网络即允许，不再要求系统“已验证”（`NetworkStateLogic`，4 项测试）。
- **导航玻璃**：顶栏/底栏/胶囊在调节值之上叠一层 0.35 的底（`chromeLegibleSurfaceAlpha`，3 项测试），滑块仍全程有效。
- **浏览页溢出菜单**：原有两行都叫「内容源管理」——一行实际打开来源偏好页，改用该页自己的名字；另一行与「拓展管理」重复，删除。
- **设置页**：共享分节标题与分组标题同色（原先同一页一灰一强调色）；滑块刻度超过 10 个时不画刻度点；
  下载页说明行标题由重复的「下载」改为「限速下载」。截图在 `build/device-reports/batch30/`。

未做：设置行“当前值”三种画法的统一（右侧强调色 / 标题下灰字）、向导精简、大字号列数、背景图把手遮挡。

## 第三十二批（2026-11-15）：向导与设置值

- **设置行当前值**：右侧的值由强调色改为与“标题下的值”同样的次要色（共享 `SettingsValueRow`，桌面同变）。位置两种画法仍并存。已编译，未单独截图。
- **向导第 3 步**：玻璃质感只列默认、当前与两个对比项，其余收在「更多」；界面风格名由 `md3 expressive` / `ios (modern)` 改为正式写法（仅 en 与 zh-CN）。
- **向导第 4 步**：排查步骤默认折叠（`wizard-advice-toggle`），`SetupWizardContentTest` 4 项 0 失败。真机前后截图 `build/device-reports/batch30/wizard-*.png`。

不改：右上角把手是空间切换按钮，位置是用户设置（向导里可选），不算遮挡缺陷。

## 第三十三批（2026-11-15）：大字号网格（真机）

- **浏览页来源网格**：列数原先只看网格缩放偏好，不看系统字号，1.5× 下四列把来源名截成“Constella…”。
  `calculateSourceGridColumns` 现按字号放大单卡所需宽度、并下调偏好列数（不低于 2）：1.0× 四列不变，1.5× 三列，2.0× 两列（真机截图 `font-1.5-*`、`font-2.0-*`；`SourceQuickAccessMetricsTest` 4 项 0 失败）。
- **底部导航标签**：高度固定、单行，2.0× 下被裁掉上半。标签字号最多跟到 1.3×（`navigationLabelFontScale`，2 项测试），其余界面照常放大。
- 收藏/历史海报网格 1.5×/2.0× 下仍为 3 列、标题两行省略，判定可接受，未改。
- 桌面端不受影响（改动在 app 的 Android 浏览页与底栏）。字号已恢复为 1.0。

## 第三十四批（2026-11-15）：详情页来源同名

“元数据来源 | 阅读来源”在未绑定追踪站时是同一个来源，药丸里同名出现两次。元数据面板（「追踪和元数据」）只有这一处入口，所以不能直接删一段。
改为：两侧是同一个普通来源时只显示一次来源名，元数据那一段缩成左侧的一个链接图标（保持“元数据在左、阅读在右”的位置）（无障碍名「追踪和元数据」），点它仍打开同一个面板；任一侧是追踪站或两侧来源不同时保持原样两段。
规则 `sourceSummaryIsMerged`（4 项测试），真机截图 `details-source-merged-after.png`、`details-metadata-sheet-via-link.png`（点链接图标可开面板，未做任何绑定）。
未验：来源不同、已绑定追踪站两种状态在真机上没有对应数据，只有规则测试。

## 第三十五批（2026-11-16）：设置搜索落到目标行

- **详情页来源药丸**：合并态的链接图标改放左侧，保持“元数据在左、阅读在右”。已编译，真机未复看。
- **设置搜索跨页落地**（第 6 项的遗留）：原先只有声明了 `searchKey` 的 13 行会滚动到位，其余结果只打开页首。
  现在搜索目标带上结果标题，所有基于 `settingsPreferenceLayout` 的共享行按标题应答并短暂高亮；
  惰性页（外观各子页、下载、存储与网络、备份、同步、追踪、通知、关于、来源、全景）由 `SettingsSearchLazyReveal`
  向下找到该行，找不到就回页首。`SettingsSearchLazyRevealTest` 1 项、`PlaybackSettingsContentTest` 3 项 0 失败。
- 未验：没有连接设备，Android 上的滚动与高亮观感未目视；标题与索引文案不一致的行仍停在页首；
  `SpacesSettingsScreen` 的自绘行与推荐设置里的来源列表未接。

真机复核（2026-11-16）：设置搜索「wi-fi」「github」两条结果都落到目标行并高亮（后者在惰性页中部，需要滚动）；
详情页合并态药丸为“链接图标 | 来源名”。截图 `build/device-reports/batch35/landing-*.png`、`details.png`。

- **返回主页面后顶栏消失**（真机发现，`devel` 既有问题，不是本分支引入）：从详情返回后，标题/搜索框/分类标签整条不见，
  只留空白，切换页面与滚动都不恢复，重启才好；约每 3–4 次出现一次。状态是回到主页面时“进入详情”阶段仍未结束
  （`pending=true`、`phase=EnteringDetails`），顶栏停在“为进入详情而隐藏”的分支。现在离开详情时若该阶段仍在就复位。
  修后连续 16 次进出详情（停留 0.2–5 秒）顶栏都回来了；但该阶段为何偶尔不结束未查明，也没有自动化用例。

## 第三十六批（2026-11-16）：设置搜索的返回

- 从搜索结果打开的页面按返回回到搜索结果（原先回到重置后的设置首页）；首页搜索中再按返回先关闭搜索，不直接退出设置。
  真机：结果 → 落地页 → 返回见结果 → 返回收键盘 → 返回关闭搜索（`build/device-reports/batch35/back-*.png`）。
- 下载页补三条搜索索引（本地内容目录、限速下载、默认页面保存目录）；设置首页「下载」摘要原先写的是页面里不存在的
  「仅在连接 Wi-Fi 时下载」，改用页面里真实的行名。这两处已编译，未在真机复看。
- **收藏页排序按钮**：没有已选筛选时，排序按钮原先独占书架上方一行；现在并到「共 N 部作品」之后（有已选筛选、TV、
  书架为空时仍是原来的筛选行）。真机截图 `fav-sort-final.png`、`fav-sort-final-menu.png`，菜单可开。
  没放在行尾的原因：屏幕右缘 36dp 是快速滚动条的触摸带（`FastScrollTouchWidth`），滚动条不可见时也吃掉点击，
  放在那里的按钮点不到。这个触摸带对右缘其他控件的影响未查。

## 第三十七批（2026-11-16）：右缘触摸带与详情页提示

- **右缘 36dp 点不到**：快速滚动条的触摸带是盖在列表上的兄弟节点，独占了落在其上的全部触摸。改为与下层内容共享触摸
  （`SiblingSharedPointerInputElement`），只在竖向拖动越过阈值时接管。真机：同一坐标的排序按钮修前三次点击无反应、
  修后菜单打开；带内竖向拖动仍快速滚动，列表中部普通滚动正常（`build/device-reports/batch35/band-*.png`）。
  排序按钮因此放回「共 N 部作品」行尾。未验：TV、平板竖栏、带内横向边缘返回手势。
- **详情页来源失效提示可关闭**：「可能已损坏 / 连续更新失败」两种推测性提示多一个「不再提示」，与收藏页横幅共用
  `source_health_banner` 开关（收藏页显示选项里的「源损坏提示」可重新打开）；「未安装」是事实且是加载失败的唯一解释，
  不可关闭。`DetailsSourceHealthRulesTest` 7 项 0 失败。真机上该开关原本就是关，所以提示直接不再出现；
  按钮本身的样子没有在真机看到（没找到当时带提示的作品）。
- **设置页顶栏改为羽化边缘**：iOS 风格下原先的全宽玻璃条（下沿有一条分界）换成主界面那套上缘羽化——底色渐变延伸到栏下
  24dp，开启边缘模糊时叠渐进模糊；设置页自己读取同一份玻璃偏好（强度、模糊半径），所以跟随「沉浸」里的调节。
  Material 风格与 TV 仍是不透明顶栏。真机截图 `feather-rest.png`、`feather-scrolled.png`、`feather-sources.png`。
