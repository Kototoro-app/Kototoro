# Kototoro 体验简化：接手文档（2026-11-15）

这份文档是给**下一程的自己或另一个 agent**看的交接单。它不复述计划，只回答四个问题：

1. 这个目标现在到底做到哪了（哪些有真凭据、哪些只是“改完了”）；
2. 这一批（第二十八批）停在哪一步、下一步第一个命令是什么；
3. 这套工作里最容易踩的坑（照着踩一遍要浪费半天的那种）；
4. 还剩什么、按什么顺序继续。

计划正文与逐批记录在 [`experience-simplification-plan-2026-11.md`](experience-simplification-plan-2026-11.md)，
设计约束在 [`shared-ui-guidelines.md`](shared-ui-guidelines.md)。ESR 任务 `tsk_9ef42e06`（实施《体验简化》12 项计划）承载这份工作，
会话目标 `goal-0014ffb5-c9e1-441b-9b00-d00be64b9849`（maxGoalRounds 40）。接手先跑 `esr_status` 与
`engram_recall "体验简化"`，本文件可能比它们慢半拍。

## 0. 一句话状态

目标（12 项体验简化全部落地 + 每批有 Android/桌面真凭据）进行到**第二十八批**；前二十七批已闭环成文，
**第二十八批代码已完成、单点证据已绿，全量回归与记账未完成**。工作树未提交：HEAD `devel @ cf1f1ea19`，
122 个改动路径（其中 51 个未跟踪）——这是纪律要求，不是遗漏。

## 1. 不可违反的工作纪律

- **不提交**。所有改动留在工作树，由用户决定何时提交。
- **一批一闭环**：一串改动 + 真凭据 + 计划正文新增一节 + 逐项审计表对应行更新 + 一条 engram 记忆。
  只改代码不记账等于没做，下一批会重复发现同一个缺陷。
- **先红后绿**：布局类修复先用新用例打**旧**布局，证明它会失败（打印真实包围盒），再改布局让它过。
  没有红检的“修好了”按未验证处理。
- **证据三件套**：编译（`[exit code: 0]`）+ 真实测试条数（从 XML 数，不从 Gradle 摘要猜）+ 设备/桌面截图。
  拿不到的那一条要**明写**“未验”，不许用编译或单测替代。
- **状态说真话**：永远只允许一条解释、一个下一步；不要在没有内容的地方留占位白块。
- 桌面与 Android 的呈现差异要么消掉，要么在计划里写明理由；纯规则下沉 `core-domain`，呈现进 `core-ui/commonMain`，
  不写平行实现。

## 2. 前二十七批速览（细节见计划正文各节）

| 批次 | 主题 | 最硬的凭据 |
| --- | --- | --- |
| 5–7 | 设置分层与搜索、阅读器作用范围、来源识别 | 设置根页与各子页设备截图、`SettingsSearch` 用例 |
| 11–13 | 详情主线主操作、次操作分组、播放器核心控件 | 两端同一条排序规则，播放器 3 档宽度截图 |
| 14–15 | 导航与更新中心、恢复与状态 | 探针端到端断言 |
| 16–17 | 视觉预设可撤销、交付产物与逐项审计 | 7 张明暗×风格截图、APK/桌面产物、三组测试计数 |
| 18–19 | 播放器诊断后置、控制条按档位取舍 | `VideoPlayerControlsLayoutTest` 3 项 |
| 20–22 | 首次在模拟器上跑：1/3/9 三项设备目视 | 1.5× 首页 3 列整词、设置页逐页截图、值列挤压用例 |
| 23–24 | 空态文案说真话（历史页）；大字号浮动卡一字一列 | `HistoryEmptyStateRulesTest`、`SourceHealthBannerLayoutTest` |
| 25–26 | 探索页“哪种空 + 下一步”；同一件事只讲一次、不占位 | `sourceEmptyHintKind`、`sourceHealthNotice`、hero 预留规则 |
| 27 | 换源面板“没有可用来源”被压成一列字 | `MigrationFamilyCardLayoutTest` 3 项 + 前后设备截图，单测 3350 项 |
| 28 | **（进行中）** 浏览页空态与 Android 对齐，规则下沉共享模块 | 见下一节 |

反复出现的**同一类缺陷**值得单独记住：`Row { Column(Modifier.weight(1f)) { 文案 } ; Text("动作") }` 在 1.5×/2.0× 字号下，
非权重子项先把宽度吃掉，权重列塌成一列单字。第二十四、二十七批各修一处，**全仓库尚未扫过**。

## 3. 第二十八批：停在哪、差什么

主题：Windows 浏览页把“0 sources”写在浮动标题栏、下面一片空白；Android 探索页在第二十五批已经会说
“是哪种空 + 下一步”，两端口径不一致。

已完成（有凭据）：

- 规则下沉：`SourceEmptyHintKind` / `sourceEmptyHintKind` 从 `app/.../explore/domain/SourceEmptyHint.kt` 移到
  `core-domain/src/commonMain/kotlin/org/skepsun/kototoro/core/extensions/SourceEmptyHint.kt`，五个引用点改导入
  （`ExploreViewModel`、`ExploreSourcesEmptyHint`、`EmptyHint`、两个测试），旧文件删除；
  单测随规则一起搬到 `core-domain/src/commonTest/.../SourceEmptyHintTest.kt`。
  验证：`:app:compileDebugKotlin`、`:app:compileDebugUnitTestKotlin` 均通过；
  `:core-domain:jvmTest --tests org.skepsun.kototoro.core.extensions.SourceEmptyHintTest` 4 项 0 失败。
- 桌面呈现：`desktop-app/.../DesktopSourceGrid.kt` 去掉标题栏那行灰字，改为内容区里的空态面板
  `SourceGridEmptyState`——无任何来源时标题/说明/「扩展管理」按钮（`controller.showExtensions()`）；
  有来源但被搜索或筛选藏光时换成标题/说明/「重置筛选」按钮（清搜索词 + `DesktopSourceFilter()`）。
  面板按浮动标题栏的真实内边距居中，避免标题被浮层压住。
  新增 i18n 键（en + zh-CN）：`sourceGrid.noSourcesTitle`、`sourceGrid.filteredTitle`、`sourceGrid.filteredDetail`、
  `sourceGrid.filteredAction`；`sourceGrid.empty` 继续复用为无来源那段说明。
- 桌面回归探针：`desktop-app/src/test/.../DesktopBrowseEmptyProbe.kt`（注册在 `DesktopAppProbe` 的 `browse-empty`）
  + `DesktopBrowseEmptyStateTest`：全新数据根 → 断言面板标题是“还没有内容源”、点按钮进 `DesktopScreen.EXTENSIONS`；
  导入 fixture 扩展后网格回来 → 搜一个匹配不到的词 → 标题变“当前筛选没有匹配的内容源”、点「重置筛选」网格回来；
  两张状态截图落在 `desktop-app/build/reports/desktop-smoke/browse-empty-{no-sources,filtered}.png`。
  结果：1 项 0 失败，日志 `DESKTOP_UI_OK=browse-empty`；截图已复制到 `build/device-reports/batch28/`。

- 桌面全量回归（本轮已跑完）：`:desktop-app:test` **528 项 6 失败 6 跳过**，`browse-empty` 在这次全量里 1 项 0 失败。
  6 项失败都与本批无关、且都是既有失败，接手时**不要**把它们算到新批次头上：
  `Resources.desktop.kt` 找不到 `icons/ic_source_legado.svg`（测试 classpath 少打包一张图）、
  `DesktopSourceTagEntries` 期望列表还没跟上 `LEGADO` 标签（用例漂移）、
  `SharedTabletComponentsTest` 的背景玻璃模糊在无头环境测不出、
  以及三条依赖 fixture/网络的探针（来源测试页、自动跨章、平板页的流更新）。

- Android 全量单测（本轮已跑完）：`:app:testDebugUnitTest` **3346 项 0 失败**。比上一批的 3350 少 4 项不是丢用例，
  而是 `SourceEmptyHintTest` 那四条随规则搬到了 `core-domain`（`3350 − 4 = 3346`，条数自洽）。

**未完成（下一个接手者的第一件事）**：

1. 计划正文收尾：上面这些数字已经写进第二十八批那一节，但文件头的“前二十八批”与逐项审计表请再核一遍。
3. 计划正文：新增「第二十八批」一节（照第二十七批的三段式：缺陷 / 修法 / 证据 + 剩余），
   文件头 `前二十七批` → `前二十八批`，逐项审计表第 4 项（来源流程）那行补上桌面侧证据。
4. 一条 engram 记忆（本批的决定与“面板组件未共享”这个取舍）。

截图怎么对照：`build/device-reports/README.md` 是索引，`build/device-reports/comparisons/` 里是十一张
“左边改前 / 右边改后”的拼图（05 详情页两条提示、06 换源面板大字号、04 探索页空态与 hero、02 收藏页破损横幅、
08 桌面浏览页两种空态……），重新生成跑 `python3 build/device-reports/make_comparison_sheets.py`。

## 4. 环境与命令（本机实测过的那几条）

Android：

```bash
./gradlew :app:assembleDebug                       # APK: app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
./gradlew :app:testDebugUnitTest                   # 全量单测；条数从 app/build/test-results/testDebugUnitTest/*.xml 数
./gradlew :app:connectedDebugAndroidTest --tests "…LayoutTest"   # 结束会卸载应用，之后要 adb install -r 再起
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb shell am start -n org.skepsun.kototoro.debug/org.skepsun.kototoro.main.ui.MainActivity
```

模拟器：`medium_phone`（Android 36，1080x2400 ≈ 411dp，密度 2.625），**系统字号 = 1.5×**
（`adb shell settings get system font_scale`）。包名 `org.skepsun.kototoro.debug`。

桌面（两个都必须带）：

```bash
./gradlew :desktop-app:compileKotlin -PwithDesktopApp=true
./gradlew :desktop-app:test -PwithDesktopApp=true -PmihonCompatibilityDirectory=build/desktop-compatibility
```

`-PmihonCompatibilityDirectory` 指向仓库里已有的 `build/desktop-compatibility`（27 个 pinned JAR，`verifyMihonCompatibility` 通过）；
**漏掉它桌面测试连编译都过不去**（compat 的 `android.*` 不在 classpath 上，报的是 cloudstream shims 里的一串 Unresolved）。
桌面探针一个 mode 起一个 JVM，日志在 `desktop-app/build/reports/desktop-smoke/<mode>.log`，
成功标志是文件里那行 `DESKTOP_UI_OK=<mode>`。

## 5. 踩过的坑（照抄结论即可）

- **截图坐标 ×1.2**：`read_image` 预览约是真实像素的 0.833，`adb shell input tap` 要用真实坐标。
  `/tmp/find.py <text>` 只 dump 应用窗口，Compose 文本节点经常 NOT FOUND——以截图为准。
- **花屏/空图**：`automation.takeScreenshot()` 拿到的是启动窗口；用例里要截图用
  `executeShellCommand("screencap -p /sdcard/Download/<name>.png")`，`/sdcard/Download` 在测试结束卸载应用后仍在，
  私有目录里的 PNG 会跟着应用一起消失。
- **浮动标题栏**：内容区顶部被浮层盖住，任何自己定位的空态都必须吃 `inner.calculateTopPadding()`，
  否则标题正好藏在搜索框底下（第二十八批第一版就是这样，截图一眼看出）。
- **异步门控的抑制会竞态**：`EventFlow<Throwable>` 每次新收集都会重放最后一个事件，所以“等状态查完再决定是否抑制”
  必然漏一下（第二十六批详情页两条提示就是这么来的）。要抑制就按**异常类型**在产生处决定。
- **探针里的中文**：桌面探针拿 `desktopText(...)` 直接比对，别硬编码文案；桌面默认走 zh-CN 目录。
- **`esr_task(id=…)` 要全 id**：`tsk_9e` 会报 not found，真 id 是 `tsk_9ef42e06`（`~/.dsh/storages/dsh_engram.json` 的 `tasks` 表可查）。
- **本机网络**：模拟器是“5G + 感叹号”的假在线，装不了任何扩展 —— 探索页/详情页的“已装来源”分支、
  下载页真实章节、播放器真实播放**在这台机器上验不了**，只能靠 instrumented 布局用例代替，并在计划里如实标注。
- 种子备份在 `build/device-reports/seed/kototoro-seed.bk.zip`：全新建包后走“从备份恢复”→ SAF 选 Downloads 里的 zip →
  Home 出现 “Yet Another Title” 与 History 6，可作为有内容页面的起点。

## 6. 剩余缺口（按建议顺序）

1. **本批收尾**：只剩一条 engram 记忆（桌面全量 528/6、Android 3346/0 都已入正文）。
2. **同族缺陷扫描**：全仓库找 `Row { …weight(1f)… ; Text/IconButton }` 的写法，在 1.5×/2.0× 下逐个判死。
   已经修过三处（第二十四、二十七、二十八批），这类问题应当一次性扫完，不要再等截图偶然发现。
3. 计划内仍写明的缺口：
   - 第 3 项：装源**成功**分支与“失败后重试”未在设备上走过（本机装不了扩展，需真机或可用网络）；
   - 第 9 项：播放器内「播放倍速」行、控制条菜单内的键盘/遥控焦点；
   - 第 11 项：下载页在设备上没有真实已下载章节（种子里没有）；
   - `HIDDEN_BY_FILTERS` 分支只有 instrumented 覆盖，设备上没有可筛选来源；
   - 收藏页顶栏溢出菜单 12 项扁平，仍未分组；
   - 封面仍是占位图（fixture URL 离线取不到），真实封面裁切/圆角观感未验；
   - iOS 只有 `compileKotlinIosSimulatorArm64` 的 klib 编译证据，没有运行验证；
   - 全程没有真人可用性测试 —— 所有“更简单了”都是自评。
4. 组件共享的下一个自然目标：`EmptyStatePanel` 目前只在 `app` 里，Windows 用自己的 `EmptyPanel`
   （第二十八批刻意只共享**规则**，因为两端的空态视觉语言不同）。若要真正共用一份组件，
   得先把面板拆成“结构 + 宿主主题映射”，这是独立一批的量。

## 7. 关键文件索引（本批动过的）

| 文件 | 作用 |
| --- | --- |
| `core-domain/src/commonMain/kotlin/org/skepsun/kototoro/core/extensions/SourceEmptyHint.kt` | 空态原因规则（Android + Windows 共用） |
| `core-domain/src/commonTest/kotlin/org/skepsun/kototoro/core/extensions/SourceEmptyHintTest.kt` | 规则单测 4 项 |
| `desktop-app/src/main/kotlin/org/skepsun/kototoro/desktop/app/DesktopSourceGrid.kt` | 浏览页空态面板 `SourceGridEmptyState` |
| `desktop-app/src/main/resources/i18n/desktop-strings{,_zh-CN}.properties` | 空态四条新文案 |
| `desktop-app/src/test/kotlin/org/skepsun/kototoro/desktop/app/DesktopBrowseEmptyProbe.kt` | 探针 `browse-empty` |
| `desktop-app/src/test/kotlin/org/skepsun/kototoro/desktop/app/DesktopBrowseEmptyStateTest.kt` | 探针的 JUnit 入口 |
| `app/src/main/kotlin/org/skepsun/kototoro/explore/ui/compose/ExploreSourcesEmptyHint.kt` | Android 侧同一规则的消费者（未改行为） |
| `docs/architecture/experience-simplification-plan-2026-11.md` | 计划正文 + 逐批记录 + 逐项审计 |
| `build/device-reports/batch28/*.png` | 本批两张桌面空态截图 |
| `build/device-reports/README.md` + `comparisons/` | 截图索引与前后对照拼图（含本批桌面空态） |
