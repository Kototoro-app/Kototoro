# 章节更新检测与调度重做计划

日期：2026-11-08。状态：计划已定，按阶段实施中（见文末“进度”）。

参考：Mihon（`SyncChaptersWithSource`、`ChapterRecognition`、`FetchInterval`、`LibraryUpdateJob`）与
Kotatsu-Redo（`CheckNewChaptersUseCase`、`GetTracksUseCase`、`StaleTrackPolicy`、`TrackerUnstuckMigrationUseCase`）。
相关 issue：#649（大库更新检查）、#652 无关但同期。

## 1. 现状

- 新章节由追更检查独占判断：`tracks.last_chapter_id` 是唯一锚点，取锚点之后的章节为新章节；锚点消失时按
  `last_chapter_date` 兜底（`compareTrackedChapters`，`ebb2fddb6`），再不行静默重新锚定。
- 更新记录是按章节的：`track_logs.chapter_metadata` 存章节 key（来源、分支、URL、ID）与已读时间，角标由
  `refreshPendingUpdateCount` 从记录推导。
- `chapters` 表不是追踪专用：详情页加载、阅读器保存进度、追更检查都经 `upsertContentSnapshot` 覆盖它。
  本地作品不写这张表。
- 调度：每轮取最久未检查的 46 部，按来源交错（每源 2、总计 8），429 退避/跨轮冷却，挑战来源只放一部试探
  （#649 的三个提交）。Mihon/Tsundoku 检查只拉章节列表，有新章节时再拉详情（`a9b13c3df`）。

### 已知问题

1. **阅读时吞章节**：作品还没有带章节信息的更新记录时，阅读器保存进度走旧的按位置计算
   （`CheckNewChaptersUseCase.invoke(manga, currentChapterId)`），把锚点设成阅读器列表的最后一章。用户在详情页
   看到新章节、去读旧章节，锚点就越过新章节，追更检查再也报不出来。Kotatsu-Redo 修过同一问题。
2. **只认单个锚点**：中间插进的章节（来源补录）、换地址/重新上传、多分支都处理不好；日期兜底在来源整体
   重标日期或没有日期时失效。
3. **首次检查与备份恢复后静默建基线**：这期间新出的章节被吞掉。
4. **调度只看“多久没查”**：半年没更新的作品和周更作品同频检查。
5. **持续失败的作品**（下架、来源卸载）每轮照查照失败，没有地方集中看到。

## 2. 两个参考项目

| | Mihon | Kotatsu-Redo |
|---|---|---|
| 新章节判定 | 已存列表与来源列表按 URL 差集 | 单锚点 → 日期 → 阅读位置 → 静默重锚 |
| 谁能发现 | 任何章节同步（后台检查、详情页） | 仅追更检查 |
| 重新上传 | 新章节号等于被删章节号 → 同一章，继承已读，不报新 | 无 |
| 章节号 | `ChapterRecognition` 从章节名解析，区分识别出的号 | 用来源给的号 |
| 首次/恢复 | 首次同步作为基线 | 用阅读位置当锚点 |
| 防误报 | 同号已读可选自动标已读；屏蔽汉化组 | 去掉阅读位置之前与 90 天前的章节 |
| 调度 | 按最近上传间隔中位数算下次检查（1–28 天），可跳过不在周期内/已完结/有未读/未开始 | 最久未查优先；90 天不活跃的每 7 天查一次 |
| 失败 | 错误日志 | `StaleTrackPolicy` 判断来源可能挂了并通知 |
| 详情 | 默认只拉章节 | 每次拉详情 |

取舍：以 Mihon 的差集模型为核心（不依赖单锚点，天然处理插入、换地址、多分支），补上 Kotatsu-Redo 的
防误报过滤与首次/恢复处理，再按本项目约束调整（`chapters` 表被多处写、本地作品无基线、Windows 共用 core-domain）。

## 3. 设计

### 3.1 写入点差集（核心）

- 在 `upsertContentSnapshot` 用新列表 `replaceAll` 之前，按 `updateKey` 求旧/新差集。规则在 core-domain
  （`ChapterSnapshotDiff`），Android 的 `ContentDataRepository` 与 Windows 的 `SourceLibrary.save` 共用。
- 只对被追踪的作品（`tracks` 有行）记录更新；新增章节经 3.3 过滤后写入 `track_logs`（`recordUpdateChapters`）。
- 旧列表为空：只建基线，不报。
- **重新上传**：新增章节的可信章节号（3.2）等于某个被移除章节的可信号，视为同一章：不报新；该被移除章节在
  更新记录里的已读状态迁到新 key。
- **列表缩水保护**：新列表比旧列表少一半以上（且旧列表至少 10 章）时视为拉取不完整：不替换基线、不做差集，
  本次结果当作失败。
- **分支**：只记录正在读的分支（阅读历史所在分支，否则 `getPreferredBranch`）；其他分支只更新基线。
- 追更检查只负责拉取，结果走同一写入点；它返回的“新章节”就是写入点记录下的章节。

`last_chapter_id` 不再参与判定，只作为“最新章节”保留；阅读路径不再移动它（同时消除问题 1）。

### 3.2 可信章节号

- core-domain `ChapterNumberRules` 移植 `ChapterRecognition`：去作品名、`ch.xx`、去 `vol/season/version`
  干扰、`extra/omake/special` 与 `x.a` 小数后缀；识别不出返回 null。
- 来源给的号仅在“不是按顺序编出来的”时可信：Mihon 仓库在扩展没给号时按顺序编号，匹配时一律以章节名识别结果
  为准，识别不出再用来源号且要求该号在列表内不重复。
- 用途只有两个：重新上传匹配（3.1）、同号已读（3.3）。“哪些算新”由差集决定，不由号决定。

### 3.3 防误报过滤

依次去掉：

1. 阅读位置之前（含）的章节（同分支、按列表顺序）。
2. 上传日期早于 90 天的章节（无日期不过滤）。
3. 可选（设置项，默认关）：与已读章节同号的新章节自动标为已读（Mihon `markDuplicateReadChapterAsRead`）。
   标已读的章节仍写入记录（`readAt` 置为当前时间），不计角标。

被过滤的章节仍进基线。

### 3.4 本地作品

本地作品没有 `chapters` 基线，继续走锚点链：ID → 日期 → 阅读位置（新增）→ 静默重锚。记录用
`recordUpdateChapters`，完成用阅读器章节列表（`adfafa652`）。

### 3.5 首次检查与恢复后

- 远程作品第一次拉到：只建基线。
- 备份恢复/同步后基线可能早已过时：差集结果只保留阅读位置之后的章节，配合 90 天过滤。

### 3.6 智能调度

- `tracks` 新增 `fetch_interval`（天）、`next_check_time`。算法同 `FetchInterval`：取最近 3–10 个不同上传日，
  相邻间隔取中位数，限制 1–28 天；无上传日期用发现时间；都没有默认 7 天；长期无更新时间隔倍增至 28 天。
- 选批：先取到期的，再按最久未查；保留按来源交错、冷却、挑战试探。
- 兜底：任何作品至少每 7 天检查一次。手动“检查更新”不受限。“跳过已完结”保留。

### 3.7 失败处理

- `tracks` 新增 `fail_count`：连续失败按 1/2/4/7 天推迟，成功清零。
- 更新页提供“持续失败”（连续 5 次及以上）列表与换源入口。

### 3.8 数据库

一次迁移 86 → 87：`tracks` 增加 `fetch_interval INTEGER NOT NULL DEFAULT 0`、
`next_check_time INTEGER NOT NULL DEFAULT 0`、`fail_count INTEGER NOT NULL DEFAULT 0`。
按 CLAUDE.md 的迁移流程：commonMain 迁移 + Android 子类 + schema JSON + 两类迁移测试。

### 3.9 Windows

`SourceTracker` 与 `SourceLibrary.save` 用同一写入点规则；接上 429 识别以使用限流退避与冷却。Windows 无本地作品。

## 4. 阶段

| 阶段 | 内容 | 迁移 | 风险 |
|---|---|---|---|
| 1 | 修阅读时吞章节；一次性补报被吞章节 | 否 | 低 |
| 2 | `ChapterNumberRules` 与防误报过滤，先用于现有锚点模型 | 否 | 低 |
| 3 | 写入点差集、本地作品锚点链、首次/恢复处理；追更检查改为只拉取 | 否 | 高 |
| 4 | 智能调度与失败退避 | 86 → 87 | 中 |
| 5 | Windows 对齐、持续失败列表 | 否 | 低 |

## 5. 进度

- [ ] 阶段 1
- [ ] 阶段 2
- [ ] 阶段 3
- [ ] 阶段 4
- [ ] 阶段 5
