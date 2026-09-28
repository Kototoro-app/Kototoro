# 批量迁移、失效源检测与收藏去重提醒 设计

日期：2026-09-29
参考实现：`../mihon`（`mihon/feature/migration/**`、`eu/kanade/tachiyomi/ui/browse/migration/**`、`DuplicateMangaDialog.kt`、`BaseSmartSearchEngine.kt`）

## 目标

仿照 Mihon 的迁移与去重功能，为 Kototoro 提供：

1. **批量迁移**：把一批收藏作品从一个来源换到另一个来源，并带上进度、分类、追踪等数据，过程中可以逐条审阅。
2. **失效源检测**：离线识别已失效或可能失效的来源，并引导用户去迁移。
3. **收藏时的重复提醒**：收藏一部书架里已有的作品时给出提示，并允许一键换源。

要求：界面美观，没有学习成本，操作高效。所有选项都有可以直接使用的默认值。

不在本次范围内：重做收藏页现有的「查找重复」工具，升级单本「替代源」弹窗。

## 现状

- `alternatives/`：详情页的「替代源」弹窗，支持单本迁移（`MigrateUseCase`，只有替换模式，不迁移笔记、统计和阅读记录）。
- `AutoFixService` / `AutoFixUseCase`：列表多选后的「修复」，在后台自动换源，没有预览。
- 收藏页的「查找重复」弹窗：不在本次改动范围。
- 没有按来源批量迁移的入口，没有迁移设置，也没有可以逐条审阅的迁移列表，收藏时不检查重复。

## 用户流程

```
收藏页菜单「迁移」 ──► 按来源迁移页（失效源排在前面） ──┐
收藏页失效源提示条 ─────────────────────────────────────┤
收藏多选「迁移」（替换原来的「修复」） ──────────────────┼─► 迁移设置面板 ─► 迁移列表 ─► 确认 ─► 进度 ─► 结果
详情页失效警告里的「迁移」（单本） ──────────────────────┘
详情页点收藏 ─► 重复检查 ─► 重复提醒面板 ─►「换成新来源」（单本迁移）/「仍然收藏」/ 取消
```

### 1. 按来源迁移页 `MigrationSourcesScreen`

- 入口：收藏页顶部菜单「迁移」，以及收藏页的失效源提示条。
- 列出收藏涉及的所有来源和各自的收藏数，分为「需要处理」和「正常」两组。「需要处理」组按收藏数从多到少排列。
- 顶部红色横幅显示「N 个来源可能已失效，涉及 M 部收藏」，带主按钮「一键迁移这 M 部」，点击后进入设置面板。
- 点击任一来源，进入该来源的作品多选列表（默认全选），然后进入设置面板。
- 来源健康标签（全部离线判断，由 `SourceHealthUseCase` 计算）：

| 标签 | 颜色 | 判定 |
|---|---|---|
| 未安装 | 红 | 作品的 source 解析为 `UnknownContentSource` |
| 已标记损坏 | 红 | 插件来源 `isBroken == true`（`GlobalExtensionManager` / `brokenSourceNames`） |
| 连续更新失败 | 琥珀 | 该来源下有 `TrackEntity` 的收藏作品中，至少 2 部在最近 14 天内检查过，并且这些作品的 `lastResult` 全部是 `RESULT_FAILED`；副标题显示 `lastError` 的摘要 |
| 已停用 | 灰 | 来源被用户禁用，但收藏里还有它的作品 |

- 不做主动的网络探测。

### 2. 其他失效源露出位置

- **收藏页顶部提示条**：只要存在「需要处理」的来源就显示「N 个来源可能失效 · 去迁移」，可以关闭。关闭时记下当前失效来源集合的哈希，集合发生变化后再次显示。
- **详情页**：当前作品的来源处于失效状态时，在信息区显示一行警告和「迁移」按钮，点击后以这一本作品进入设置面板。

### 3. 迁移设置面板 `MigrationConfigSheet`（ModalBottomSheet）

- 标题：「迁移 N 部作品」；副标题显示内容类型和来源概况。
- **搜索这些来源（按顺序）**：用带序号的芯片列出，末尾「+k」表示还有更多，右上角「编辑」打开来源编辑页。
  - 候选范围只包括与待迁移作品**相同内容类型**的来源。混选了多种类型时，按类型分组，每组各自使用对应类型的来源列表。
  - 默认值：优先使用按内容类型保存的上次选择；没有保存过时，使用「已置顶的来源在前，其余已启用的来源在后」。待迁移作品当前所在的来源不会出现在列表中。
  - **来源编辑页**：勾选加拖动排序；顶部提供快捷按钮「全选 / 仅置顶 / 仅已启用 / 清空」。
- **一并迁移**：用芯片多选，默认全部选中。可选项为：收藏分类（锁定开启）、阅读进度、追踪与同步、笔记、阅读统计。
- **匹配方式**（分段按钮）：「最快命中」（默认）/「章节最多」。
- **更多选项**（默认折叠）：附加关键词、深度搜索、隐藏未找到的作品、隐藏没有新章节的作品。
- 主按钮「开始匹配」。所有设置持久化保存，下次打开时恢复。

### 4. 迁移列表 `MigrationListActivity`（行布局 B：单行紧凑）

- 顶栏显示「迁移」和「匹配中 x / N」，下方是一条细进度条。
- 筛选芯片：全部 / 已匹配 / 未找到 / 章节变少，每个芯片都带计数。
- 每一行的内容：
  - 封面：已匹配时显示新作品的封面，未匹配时显示灰度的旧封面。
  - 标题：显示旧标题；新标题不同时，追加「→ 新标题」。
  - 副标题：「旧来源 X 话 → **新来源 Y 话**」，后面跟章节差值徽标（`+12` 绿色，`−2` 红色，相等为灰色）。
  - 第三行：「还有 k 个候选 ›」，或者「点击手动搜索」。
  - 状态：等待中 / 匹配中（转圈）/ 未找到（红字）。
- 点击行：打开**候选面板**，列出所有来源的候选，按相似度和章节数排序，点一下就切换；面板顶部有手动搜索框，在已选的目标来源中搜索。
- 行尾的 ⋮ 菜单：手动搜索 / 跳过 / 立即迁移这本 / 打开原作品。
- 匹配进行中也可以操作。底部栏有「复制 N」（次要按钮）和「迁移 N 部」（主按钮），N 为当前已匹配的数量，实时更新。
- 点击底部按钮后弹出确认框：「将迁移 N 部，跳过 k 部（未找到）」，替换模式下额外说明「旧条目会从收藏和历史中移除」。
- 执行时显示进度弹窗，可以取消，已经完成的作品保持完成状态。
- 结果：弹出摘要「成功 a，失败 b」。失败的作品留在列表中，标红并附上原因，可以重试；成功的作品从列表中移除，列表清空后自动返回。
- 匹配未完成时点返回，弹出确认框「放弃这次迁移？」。

### 5. 收藏时的重复提醒 `DuplicateFavouriteSheet`

- 触发时机：在详情页点收藏，或在列表中通过长按或多选加入收藏，并且设置「收藏时检查重复」处于开启状态（默认开启）。只在单部作品加入收藏时检查；多选批量收藏跳过检查，以免连续弹窗。
- 判定规则（`FindLibraryDuplicatesUseCase`）：内容类型相同，排除自身 id，只在收藏中查找；标题或任一 `altTitles` 经 `TitleNormalizer` 规范化后与对方的标题或别名相等即算重复。不做模糊匹配。
- 面板内容：标题「书架里可能已经有这部作品」，副标题「你正在收藏：{标题} · {来源} · {X 话}」。
- 横向卡片：封面、标题、来源、章节数、「读到 第 N 话」、进度条；角标显示「来源失效」或收藏分类名。
  - 点击封面打开该作品的详情。
  - 按钮「换成新来源」：以替换模式把旧条目迁移到当前正在收藏的作品，使用上次保存的数据项设置。旧条目来源失效时，这个按钮显示为主按钮样式。
- 底部按钮：「取消」和「仍然收藏」。「仍然收藏」会继续原有的分类选择流程。
- 查重本身出错时静默放行，照常收藏。

## 架构

实现方案：**ViewModel 承载**（与 Mihon 相同）。匹配和执行都在 `MigrationListViewModel` 中完成。匹配阶段已经取好目标作品的详情，执行阶段基本只剩数据库事务。离开页面等于取消。

```
migration/
  domain/
    MigrationOptions.kt              data class：targetSources（List<ContentSource>，有序）、
                                     dataFlags（Set<MigrationDataFlag>）、matchMode（FIRST_HIT / MOST_CHAPTERS）、
                                     extraQuery、deepSearch、hideUnmatched、hideWithoutUpdates
    MigrationDataFlag.kt             CATEGORIES（锁定）、PROGRESS、TRACKING、NOTES、STATS
    MigrationSettings.kt             持久化（SharedPreferences，按 ContentType 保存目标来源列表）
    TitleNormalizer.kt               trim、lowercase、NFKC（全角转半角）、去掉标点和空白
    TitleSimilarity.kt               标准化 Levenshtein、深度搜索的查询生成、括号内容清洗（移植自 Mihon）
    SmartMatchEngine.kt              对单个来源搜索并返回按分数排序的前 3 个候选；
                                     match(content, options)：以 Flow 形式发出 MatchProgress / MatchResult
    SourceHealthUseCase.kt           输出 List<SourceHealth(source, status, favouriteCount, errorSummary)>
    FindLibraryDuplicatesUseCase.kt
  ui/
    config/MigrationConfigSheet.kt, MigrationSourcePickerScreen.kt, MigrationConfigViewModel.kt
    list/MigrationListActivity.kt, MigrationListScreen.kt, MigrationListViewModel.kt,
         MigrationCandidatesSheet.kt, model/MigratingItem.kt
    sources/MigrationSourcesScreen.kt, MigrationSourcesViewModel.kt
    duplicate/DuplicateFavouriteSheet.kt
alternatives/domain/MigrateUseCase.kt  扩展（见下文）
```

- 导航：在 `AppRouter` 中新增 `openMigration(contentIds: LongArray)` 和 `openMigrationSources()`。设置面板通过 `showComposeModal` 以弹窗形式显示（与 `openAlternatives` 的做法相同），迁移列表单独使用一个 Compose Activity，通过 Intent 传入 id 列表和设置。
- 多选：`SelectionAction.FIX` 改为 `SelectionAction.MIGRATE`（新的图标和文案）。`AutoFixService` 和 `AutoFixUseCase` 的代码保留，列表中不再有它们的入口。
- 字符串：新增资源放在 `values/strings.xml`（英文）和 `values-zh-rCN/strings.xml`，其他语言通过 Weblate 翻译。

### 匹配引擎

- 搜索：使用 `SearchV2Helper.Factory.create(source)(query, SearchKind.TITLE)`。有附加关键词时，查询内容为 `"$title $extra"`。
- 打分：`max over (旧标题 ∪ 旧别名) × (候选标题 ∪ 候选别名)` 的规范化相似度，阈值为 0.4。相似度计算在规范化后的字符串上进行；深度搜索时，比较前额外做括号清洗。
- 排除条件：候选与原作品的 source 和 url 都相同，或者 id 相同。
- 最快命中（FIRST_HIT）：按来源顺序依次搜索，第一个有合格候选的来源胜出。之后的来源仍会在后台以低优先级继续搜索，结果补充到候选面板里。
- 章节最多（MOST_CHAPTERS）：并发搜索所有来源，对每个来源分数最高的候选拉取详情，选出章节数最多的一个；章节数相同时，取分数较高者。
- 并发控制：作品之间 `Semaphore(4)`；每个来源 `Semaphore(2)`（为每个来源懒创建，存放在 ViewModel 中）。
- 详情：最佳候选立即调用 `getDetails` 拉取；其余候选在打开候选面板时懒加载。
- 旧章节数：取 `ChaptersDao` 中的本地缓存，不发网络请求。
- 取消：每个 `MigratingItem` 持有自己的 `Job`。跳过或退出页面时调用 `cancel()`。

### MigrateUseCase 扩展

```kotlin
suspend operator fun invoke(
    oldContent: Content,
    newContent: Content,              // 已经带有章节时，不再重复请求
    mode: MigrationMode = REPLACE,    // REPLACE / COPY
    flags: Set<MigrationDataFlag> = MigrationDataFlag.ALL,
)
```

| 数据 | REPLACE | COPY |
|---|---|---|
| 收藏分类（总是迁移） | 删除旧的，按相同分类 upsert 新的 | upsert 新的，保留旧的 |
| 阅读进度 PROGRESS | 按「卷号 + 章节号」映射，映射不到就按百分比折算（沿用现有的 `makeNewHistory`）；删除旧的 | 映射后写入新条目，保留旧的 |
| 追踪与同步 TRACKING（追踪链接、`TrackEntity`、scrobbler） | 转移 | 复制 |
| 笔记 NOTES（`media_notes`） | 更新 mangaId；chapterId 按章节号重新映射，映射不到时保留 `chapterIndex`，并把 chapterId 设为该索引处的新章节 | 复制（id 置 0） |
| 阅读统计 STATS（`stats`、reading records、jump points） | 更新 mangaId；记录中的章节 id 尽量重新映射 | 不复制 |
| 阅读偏好 prefs | 转移 | 复制 |
| 书签 | 不迁移 | 不迁移 |

- 每部作品一个 `withTransaction`，先转移或复制所有数据，最后再删除旧的收藏和历史。**旧的 manga 行不删除**（以免外键级联删掉本地下载等数据）。
- 新条目先通过 `storeContentAndReturn(replaceExisting = true)` 落库；事务结束后调用 `progressUpdateUseCase`。
- 现有调用方（`AlternativesViewModel`、`AutoFixUseCase`）使用默认参数，行为不变。

## 错误处理

- 单个来源的搜索或详情请求抛出异常（包括 Cloudflare 和超时）时：记录该来源的错误，继续处理其他来源。
- 某部作品在所有来源都没有合格候选时：状态为「未找到」，行内可以展开查看各来源的错误摘要。
- 迁移执行时单部作品失败：该部标记为失败并显示原因，其余继续；失败的作品可以重试。
- `FindLibraryDuplicatesUseCase` 抛出异常时：捕获后放行，照常收藏。
- `SourceHealthUseCase` 某一项判定失败时：该来源按正常处理，不误报。

## 测试

JUnit5 + Kotest + MockK。数据库相关的测试使用 Room in-memory 数据库（Robolectric）。

- `TitleNormalizerTest`：全角/半角、大小写、标点、空白、中日文字符保留。
- `TitleSimilarityTest`：相似度的边界情况；深度搜索的查询生成与 Mihon 的输出一致；括号清洗（包括反向解析）。
- `SmartMatchEngineTest`：FIRST_HIT 的顺序与短路、MOST_CHAPTERS 的选择与平分处理、阈值过滤、排除自身、单个来源异常时容错、附加关键词拼接。
- `SourceHealthUseCaseTest`：四种状态的判定与优先级（未安装 > 已标记损坏 > 连续更新失败 > 已停用），以及「连续更新失败」需要至少 2 部作品、14 天窗口这两个条件。
- `FindLibraryDuplicatesUseCaseTest`：通过别名命中、内容类型不同时不命中、排除自身。
- `MigrateUseCaseTest`：REPLACE 和 COPY 两种模式下，各张表的结果；每个 flag 关闭时不迁移对应数据；章节映射（按章节号、按百分比兜底）；笔记重新映射；COPY 模式不复制统计数据；旧 manga 行保留。
- `MigrationListViewModelTest`：筛选计数、跳过即取消、执行时部分失败的处理、列表清空后自动返回。

## 验收标准

- 从收藏页菜单进入迁移，到完成一次 20 部作品的迁移：在默认设置下只需 5 次点击（迁移 → 一键迁移 → 开始匹配 → 迁移 N 部 → 确认）。
- 匹配过程中列表可以滚动、筛选，也可以切换候选，界面不卡顿。
- 替换模式迁移后，新条目在同样的分类中，阅读进度指向对应的章节，追踪绑定已经转移；旧条目不再出现在收藏和历史中。
- 收藏一部别名与书架中已有作品相同的作品时，会弹出重复提醒；关闭设置开关后不再弹出。
- `./gradlew :app:testDebugUnitTest` 全部通过；`./gradlew :app:compileDebugKotlin` 编译通过。

## 实现细化（2026-09-29，编写实现计划时确定）

1. 来源排序改为「按勾选顺序排序」（项目中没有拖拽排序库），勾选的先后即搜索顺序。
2. 「收藏时检查重复」开关放在重复提醒面板的「不再提醒」和设置面板「更多选项」里，不新增设置页条目。
3. 「最快命中」命中后即停止；其余来源可以通过候选面板的手动搜索补充。
4. 收藏页的失效源提示改为底部浮动卡片，详情页的失效源警告使用带「换源」动作的 Snackbar。
5. 中文文案沿用应用内已有的「换源」一词。
