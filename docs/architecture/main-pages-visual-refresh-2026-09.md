# 主要页面视觉整理（2026-09）

## 背景

用户反馈收藏页顶部的横向列表（原“常读与关注”聚焦栏）不美观。排查后发现问题不止一处：

- 收藏页聚焦栏使用私有卡片（104×162dp 固定尺寸、封面下方另叠一块半透明底板、`+N` 贴角标签），
  与下方网格卡片、主页横排卡片是三套不同的视觉语言；它还把置顶作品排在最前，
  而置顶作品本来就在下方网格的最前面，等于重复展示。
- 聚焦栏为空时退化成一张“共 N 部作品 · 下拉可检查更新”的提示卡，信息量低但占一整行。
- 更新、推荐、历史（统计占位）等页面的顶部卡片是各自复制粘贴的私有实现，
  内边距、图标底板、按钮样式（`TextButton` / `IconButton` 混用）逐渐漂移。

## 本轮改动

### 收藏页：“继续阅读”栏（`FavoritesShelf`）

- 选取规则抽成纯函数 `selectFavouritesShelfRows`（`favourites/domain/library/FavouritesShelf.kt`，
  单测 `FavouritesShelfTest`）：有新章节的作品在前（按最新章节时间倒序），
  其次是正在读的作品（按最近阅读倒序）；已读完、未读过、仅置顶的作品不进入；上限 12 部。
- 数据由 `FavouritesListHost.shelf` 在后台线程产出，卡片模型直接复用 `FavouritesCardMapper`
  的网格映射，因此封面、角标、进度条、徽章设置与下方网格完全一致。
- 渲染复用 `KototoroContentCardGrid` + `compactPosterRailCardStyle(gridScale)`，与主页横排同尺寸，
  跟随用户的网格大小设置；标题行是“继续阅读 · N 部更新”，栏下方用“共 N 部作品”为网格起头，
  避免网格看起来像继续阅读的延续。
- 页面顺序改为：分类标签 → 快速筛选栏 → 继续阅读 → 网格。快速筛选同时作用于继续阅读栏和网格，
  原先它作为网格的第一个列表条目渲染，被夹在两者之间；现在由 `FavoritesListScreen` 放进
  `listHeader` 顶部（`showQuickFilterInline = false`）。
- 没有可展示的作品时整栏不渲染（不再显示提示卡；检查更新仍可下拉刷新）。
- 收藏页筛选面板新增开关“显示‘继续阅读’栏”（`AppSettings.KEY_FAVOURITES_SHELF`，默认开启）。

### 收藏页：筛选隐藏全部作品时不再显示“还没有收藏”

- 离线时容器会自动加上“本地（Downloaded）”快速筛选。若此时没有已下载作品，旧逻辑把所有分类
  都当作无内容过滤掉，整页显示“还没有收藏”，连分类标签和筛选栏都消失，用户无从取消筛选。
- `buildFavoritesHostUiState`（`favourites/ui/container/FavoritesHostUiStateBuilder.kt`，
  单测 `FavoritesHostUiStateTest`）：空间/来源标签仍按计数隐藏分类；但快速筛选导致全部为空时保留分类标签。
- `FavouritesListHost` 在切片为空时发出 `EmptyState`：分类里有作品但被筛掉 →“未找到 + 重置筛选”；
  分类本身为空 →“还没有收藏”。
- 快速筛选栏（`QuickFilterSection`，所有列表页共用）把已选中的芯片排在分组下拉之前，
  激活的筛选不会再被挤出屏幕。

### 订阅页（Feed）

- 顶部“更新”透视轮播：侧卡随距离变窄，旧实现允许 3 行标题，窄卡里单词被逐字折行。
  现在侧卡标题只保留 1 行省略，宽度 < 88dp 时不显示文字、< 120dp 时不显示章节数（角标仍显示更新数）。
- 分类筛选芯片去掉外层描边容器，与其它页面的芯片栏一致。

### 统一页面头部（`core/ui/compose/PageSummaryHeader.kt`）

- `PageSummaryHeader`：强调色图标方块 + 标题/副标题（最多两行）+ 右侧操作区，
  半透明卡片底色叠一层由强调色向透明过渡的淡渐变。
- `PageSummaryAction`：32dp 药丸按钮，`emphasized` 用强调色实心填充表示主操作，
  `text = null` 时为纯图标圆形按钮。
- 已迁移：更新页 `UpdatesHeaderCard`（`ic_updated` 图标，“全部已读”为主操作、刷新为图标按钮）、
  推荐页 `SuggestionsHeaderCard`（tertiary 强调色）、历史页统计占位卡、书签页笔记概览头部
  （NSFW 开关与搜索改为药丸操作，搜索框展开在卡片下方）。
- 本地页下载卡（`LocalDownloadsCard`）保留自己的内容结构，但外边距、圆角和图标方块与之对齐。

### 作品列表卡片（第二轮）

- 列表 / 详细模式：副标题（标签 + 来源）改用次要色，不再整列主色蓝字；新增状态行
  `ContentCardStatusRow`（`list/ui/compose/ContentCardStatus.kt`，单测 `ContentCardStatusTest`）：
  有更新时显示“N 个新章节”色调标签，有进度时按用户的“进度显示模式”显示
  已读/剩余百分比、已读/剩余章数或“已读完”。
- 紧凑网格：叠加标题字号比网格标题小 1.5sp（`resolveCompactGridTitleFontSize`），
  遮罩高度改为封面高度的 42%（54–72dp，`compactGridTitleOverlayHeight`），两行标题不再被截成一行。
- iOS 风格下置顶、收藏角标的图标与“已下载”一致改为白色，原先主色图标落在深色角标底上几乎看不见。

### 删除投影（projection）残留

实体系统移除后，每个收藏/历史/更新条目就是一部作品本身，“投影”概念已无意义，作品列表链路上的相关功能全部删除：

- 卡片：`ContentListModel.projectionCount`、渲染模型字段、“投影数”角标（`projection_count`，
  及显示选项中的对应项）。
- 收藏快照：`FavouriteCardRow.projectionCount / projectionSourceNames / hasBrokenProjection`、
  facet 查询 `observeFavouriteProjectionFacets` 与 `FavouriteProjectionFacetRow`（来源计数改由基础行统计）；
  空间/来源筛选改为直接匹配作品来源。
- 快速筛选：删除“多投影”“失效投影”两个宏及“作品关系”分组。
- 列表副标题：收藏/历史/更新 mapper 的“当前投影：X（· N 个投影 · N 条记录）”改为来源名
  （`groupSuffix` → `sourceLabel`）；推荐页硬编码的“N 个投影来源”、搜索建议的“N 个投影 · N 个来源”删除。
- 空标题占位改为 `untitled_content`（“无标题”）；删除上述功能不再使用的字符串（含各语言译文）。

第二批清理（作品列表之外）：

- 详情页换源面板：阅读来源只有当前一项，“可切换投影”分支永远不会显示，删除；`DetailsSourceRole`
  改为 `METADATA` / `READING_SOURCE`，显示字符串只保留 `readingSourceLabel`；删除未被调用的
  `DetailsProjectionFilter`（及测试）；`details_current_projection_sheet_hint` 更名为
  `details_reading_source_sheet_hint`。
- 备份恢复拦截：校验旧版 `WORK_*` 段锚点的逻辑保留，提示文案不再让用户去已不存在的“实体整理”，
  改为说明备份不完整（`backup_guard_missing_anchor_contents`，异常 `MissingAnchorContentsException`）；
  其它语言的旧译文删除，等待 Weblate 重新翻译。
- 标识符改名（只改代码名字）：`ProjectionIdentityKeys` → `ContentIdentityKeys`、
  `ProjectionIdentityResolver` → `StoredContentIdentityResolver`、`ProjectionContentTypeBackfill` →
  `ContentTypeBackfill`、`BackupSection.PROJECTIONS` → `CONTENTS`、`resolveStoredProjection` →
  `resolveStoredContent`，以及详情页 ViewModel 中一批 `*Projection*` 内部名称；相关注释措辞同步改写。
- 删除 338 个实体整理 / 实体图谱 / 投影相关、全仓库无引用的死字符串（含各语言译文）。

有意保留：备份 zip 条目名 `"projections"`（新旧备份互通）、`ContentIdentityKeys` 生成的
`"projection:…"` 键值、Room 列 `resume_projection_id`（及其实体字段）、历史数据库迁移代码
（`core/db/migrations/`，含 `ProjectionOwnershipMigrationResolver`），以及“投影”的其它含义
（MangaBaka SQL 列投影、视频/小说的状态投影、阅读器 `SceneAxisProjection`）。

## 验证

- 单测：`FavouritesShelfTest`、`FavoritesHostUiStateTest`、`ContentCardStatusTest`、`ContentGridLayoutTest`；
  全量 `testDebugUnitTest` 通过（投影清理后 2611 个）；instrumented 测试编译通过（未在设备上运行）。
- 模拟器（API 35，演示数据库）逐页截图检查：收藏（浅色/深色）、历史、订阅、更新、推荐、本地、书签。

## 后续可继续的方向

- 订阅页时间线分组标题与主页分区标题统一字号/字重。
- 主页顶部 hero 滚动到状态栏/顶栏下方时缺少遮罩，状态栏图标对比度偏低。
