# Work 化迁移当前状态审计（2026-06）

## 目的

本文档用于沉淀 Kototoro 当前工作树里已经完成的 Work 化主链收口，以及仍然明确存在的命名、结构和兼容债务。

这里的重点不是再讨论理想架构，而是回答三个现实问题：

1. 现在到底已经收口了哪些主链；
2. 哪些旧路径已经被明确降级或禁止；
3. 下一阶段还剩哪些值得继续推进的切口。

相关设计文档：

- [实体中心 Work 化改造执行计划（2026-06）](./entity-centered-work-migration-execution-plan-2026-06.md)
- [Entity Graph 治理收敛与历史债务清理方案（2026-06）](./entity-graph-governance-remediation-plan-2026-06.md)
- [Entity Graph Source Boundary 审计（2026-06）](./entity-graph-source-boundary-audit-2026-06.md)
- [Work Ownership Matrix（2026-06）](./work-ownership-matrix-2026-06.md)
- [Metadata Write Audit Plan（2026-06）](./metadata-write-audit-plan-2026-06.md)
- [Work 化迁移期间的新旧同步隔离方案（2026-06）](./work-migration-sync-isolation-plan-2026-06.md)
- [Work Sync Schema And Restore Isolation Spec（2026-06）](./work-sync-schema-and-restore-isolation-spec-2026-06.md)

## 当前判断

截至当前工作树，Kototoro 已经不再是单纯的 `Manga 主模型 + Entity 外挂聚合`。

更准确的描述是：

- `entity` 已经在多个关键链路上承担过渡期 Work 的 ownership；
- `manga` 在主流程里仍然大量作为物理锚点存在，但越来越接近 Projection；
- metadata authority 的运行时主链，已经基本回到 entity/work；
- projection prefs 仍然存在，但主要被压回 fallback、local override 和 legacy compatibility。

这意味着当前阶段的主要任务，已经从“证明方向对不对”转成“继续清理残余入口并降低命名误导”。

## 按计划文档的阶段兑现情况

基于当前代码与上面各节证据，对
[实体中心 Work 化改造执行计划（2026-06）](./entity-centered-work-migration-execution-plan-2026-06.md)
的分阶段状态可以先做一个收敛判断：

### Phase 0：冻结错误主链

当前判断：**基本完成**

证据：

- `ContentDataRepository.setEntityMetadataSourceSelection(...)`
  不再默认做 blind mirror；
- `ContentDataRepository.setOverride(...)`
  / `setReadingStatus(...)` / `setMetadataSourceSelection(...)`
  已按 entity/work-first 收口；
- backup / restore / WebDAV restore 的 authoritative sections 与 upload gate
  已经明确围绕 `WORK_*` 与 restore normalization 收紧；
- details / reader / preview / related / alternatives / scrobbling selector
  等高频运行时入口已普遍切到“当前数据库内容优先，seed fallback 降级”。

仍保留的兼容口：

- 无独立 content id 锚点的旧入口，仍允许 seed fallback；
- projection prefs 仍保留 local override / legacy fallback 语义。

### Phase 1：高价值 ownership 主链补齐

当前判断：**大部分完成**

证据：

- `work_history` / `work_favourites` / `work_stats` 已落地；
- history / reading record / scrobbling / tracking ownership
  已经 work-aware；
- details 页的大量高频状态写链，已不再直接把初始化 `mangaId`
  当 owner id 使用。

仍待继续核查的点：

- `updates / tracker / new chapter counters`
  虽然 repository 层已经大量按 Work anchor 聚合，
  但仍有残余 DAO / SQL / 清理链在继续使用 projection-centric 存储语义；
- recent / shortcut / widget / updates 主链是否还有代表内容分裂的残留。

### Phase 2：metadata authority 完全分流

当前判断：**主链基本完成，但审计与命名仍需收尾**

证据：

- `setEntityMetadataSourceSelection(...)` 已是 Work-only authoritative write；
- `setMetadataSourceSelection(mangaId, ...)` 已降级为 projection-local override；
- `setOverride(...)` 主写链已上移到 entity/work；
- repair 已区分冗余 projection metadata / override / reading status shadow。

仍待继续核查的点：

- repair 诊断分类与动作命名，是否还把 Work drift / projection drift
  混在同一语义桶里；
- 文档与状态审计里仍有少量过时结论，需要继续和代码现状对齐。

### Phase 3：projection 锚点运行时统一

当前判断：**进行中**

已完成证据：

- details / reader / related / preview / alternatives / scrobbling selector /
  video player / prefetch / shortcut 等一批高频入口已切到当前 projection 优先解析；
- `DetailsViewModel` 已把大量 owner-sensitive 读取统一到
  `currentObservedLocalMangaId`。

仍未证明完成的点：

- continue reading / updates / tracker / new chapter counters /
  widget / shortcut 是否在所有共享入口都使用一致的 representative /
  preferred projection 解析；
- 同 work 多 projection 在这些链路里是否已经完全不再重复放大。
- favourites / history 列表的排序与过滤、
  以及 updates 列表的 pinned/favorite 判定，
  已开始从直接 `tracks.manga_id` / `history.manga_id` 子查询
  收口到 `work_favourites` / `work_history` / `entity_preferences`，
  但 `tracks` 表本身仍然是 preferred projection anchor 存储，
  因此这一 phase 还不能视为完成。
- 下载执行链虽然已经引入 `displayMangaId` 与 `ExecutionChapterRef`
  作为 execution/display 双锚补充，但下载 worker 仍然以 execution projection
  为真实拉取上下文，因此这一 phase 目前只能算继续推进，不能算收口。

### 近期止血与推进补记

#### details 运行时止血继续收口

本轮又补了两类直接对应线上日志的问题：

- `DetailsViewModel.observeTrackingLinksByWork(...)` 与 tracking item 初始化路径，
  对可能返回 `null` 的 DAO `Flow` 统一套上 `flowOrFallback(...)`；
- `refreshActiveLocalBrowserContent(...)` / `refreshResolvedPresentationState(...)`
  相关读取继续改成安全快照读取，避免构造期直接触发空 `StateFlow`；
- `normalizedImageUrl()` 会过滤失效的本地 `file://` 缓存路径，
  不再继续把不存在的缓存文件交给 Coil 解码。

这对应了近期日志中的两类异常：

- `Flow.collect(...) on a null object reference`
- `StateFlow.getValue() on a null object reference`
- `RealImageLoader` 读取失效 `file://...jpg` 导致 `ENOENT`

当前结论仍然是：

- **代码级止血已落地**
- **`compileDebugKotlin` 已作为最小构建门槛验证目标**
- **尚无真机/模拟器复验证据，因此不能宣称详情页运行时已彻底修复**

#### 自动下载章节 identity 漂移开始脱离“只靠旧 chapter id”

本轮对下载链继续推进了 execution/display 双锚模型：

- `DownloadTask` 新增 `ExecutionChapterRef`
  （`id/url/title/number/volume/branch`）；
- `TrackWorker` 自动下载入口、章节页下载入口、
  下载确认入口都开始把 chapter refs 一并写入任务；
- `DownloadsViewModel.retryWork(...)` 与
  `DownloadWorker.Scheduler.schedule(...)`
  重建任务时会继续保留 `executionChapterRefs`，
  避免重试/重排队把这层 identity 丢掉；
- `DownloadWorker.getChapters(...)` 在原 execution chapter id
  在新 details 中找不到时，开始按 `ExecutionChapterRef`
  对缺失章节做保守重匹配。

这一步的意义是：

- 自动下载链开始摆脱“更新检测拿到的 chapter ids
  必须和后续下载执行时的 details chapter ids 完全一致”的脆弱前提；
- 当 source 重抓后 chapter id 漂移时，worker 至少有一层
  `branch/url/title/number/volume` 的保守重定位依据。

但边界也要明确：

- 这仍然不是下载 execution identity 的最终 Work-owned 模型；
- 当前只是让 worker 不再只认旧 chapter id，
  还没有把下载执行 ownership 从 projection 语义彻底抬升出去；
- 因此它应被视为 **Phase 3 的推进项**，不是完成项。

#### updates / tracker 锚点保活开始从 legacy history/favourites 转向 Work-owned 状态

本轮又修了一处容易被忽略、但会持续污染追更主链的旧语义：

- `TracksDao.gc()` 之前只依据旧 `history` / `favourites`
  表判断 track anchor 是否仍然存活；
- 当一个作品已经主要转到 `work_history` / `work_favourites`
  维护，而 legacy 表不再完整镜像时，
  旧 gc 可能会把仍然有效的追更锚点误删。

当前 `tracks` gc 规则已改为：

- 先保留 `work_history.anchor_manga_id`
- 再保留 `work_favourites` 对应 entity 的
  `entity_preferences.preferred_local_manga_id`
  或本地 binding fallback
- 最后才兼容旧 `history` / `favourites` 存量

这一步的意义是：

- updates / tracker 的锚点存活条件开始真正依赖 Work-owned 状态；
- legacy favourites/history 不再是唯一保活真相；
- Work 化迁移过程中，旧 gc 逻辑对追更状态的误删风险明显下降。

边界同样要说清楚：

- 这还没有把 `tracks` 表本身变成 Work-owned 存储；
- 只是先把“谁有资格继续保留 track anchor”收口到 Work-first；
- updates 过滤、排序与展示层仍有进一步清理空间，因此仍属于 **Phase 3 进行中**。

#### updates 列表过滤与置顶开始摆脱对旧 `tracks.manga_id` 的直接依赖

除了 track anchor 保活，本轮还继续收了 `TracksDao` 在展示过滤上的旧语义：

- `favorite` / `favorite(category)` 的 legacy fallback
  不再直接用 `tracks.manga_id` 查旧 favourites，
  而是先解析当前 entity 的 `preferred_local_manga_id`；
- `tag` / `nsfw` 过滤不再默认读取 track 行自身的 local manga 元数据，
  而是优先读取当前 representative local projection；
- `pinned` 排序的 legacy fallback
  也改成优先基于 representative local projection 解析。

这一步的实际意义是：

- 即使某个 `tracks` 行还保留旧 anchor，
  updates 列表的过滤与排序也会尽量跟随当前 Work representative；
- source switch 之后，updates 展示层继续盯住旧 projection 的概率下降；
- `tracks` 表继续保留为 projection-anchor 存储时，
  展示层和 owner 语义之间的撕裂进一步缩小。

边界：

- 这仍然不是 `tracks` 存储模型的最终统一；
- `updates` 的行物理 identity 还没有彻底摆脱 projection anchor；
- 因此这里只能算 **Phase 3 的继续收口**，不能算完成。

#### tracker 存储写链开始拒绝“不可持久化的伪 anchor”

本轮又补了一处更底层的约束，直接对应近期 feed 页面里的
`tracks` / `track_logs` 外键失败风险：

- `TrackingRepository` 过去通过 `resolveTrackAnchorMangaId(...)`
  既做展示/执行锚点解析，也做 `tracks.manga_id` / `track_logs.manga_id`
  的持久化锚点解析；
- 当输入 id 已经不是本地 `manga`，但还能间接映射到某个 entity 时，
  旧逻辑可能把原始 id 原样返回；
- 一旦这个 id 最终写入 `tracks.manga_id` 或 `track_logs.manga_id`，
  就会直接撞上到 `manga(manga_id)` 的外键约束。

当前收口：

- `TrackingRepository` 新增了“只解析可持久化本地 projection anchor”
  的写链约束；
- `saveUpdates(...)` / `mergeWith(...)` / `deleteTrack(...)` /
  `clearUpdates(...)` / `getTrackOrNull(...)` /
  `getNewChaptersCount(s)` 等真正会落到 `tracks` 表上的入口，
  都改成只接受存在于本地 `manga` 表中的 anchor；
- 对于无法解析出本地 anchor 的情况，当前行为改成：
  - 读链返回空/零值；
  - 写链直接跳过，不再把伪 id 落库。

#### scrobbling 从 owner-first 落库继续推进到 work-aware 运行时上下文

本轮 `scrobbling` 又往前推进了一层，不再只是表结构和 upsert helper
开始写 `owner_id`，而是把运行时“到底在操作哪个作品、哪个投影、哪个持久化锚点”
这三层语义继续拆开了。

当前已落地的点：

- `scrobblings` 表已经是 `owner_id` 主键参与的 owner-first 结构；
- `ScrobblingDao` 大部分读取入口的 preferred row 选择顺序已统一，
  优先 entity-owned、再看本地 projection、再看评分/评论/进度/预览信息完整度；
- `Scrobbler` 内部不再继续使用语义含混的单一 `anchorMangaId`，
  改成显式解析：
  - `requestedMangaId`
  - `preferredLocalMangaId`
  - `persistedLocalMangaId`
  - `entityId`
- `observeScrobblingInfo(...)` /
  `getScrobblingInfoOrNull(...)` /
  `linkContent(...)` /
  `unregisterScrobbling(...)`
  已开始统一走这套 context；
- `ScrobblingInfo` 运行时模型已补上：
  - `entityId`
  - `preferredLocalMangaId`
  使 details / selector / config 这类上层不必再各自猜 owner。

这一步的意义：

- `scrobbling` 终于不再只是“数据库里开始有 ownerId”，
  而是连运行时选择逻辑也开始承认 Work / Projection 是两层对象；
- 同一个 work 下多 projection 并存时，
  当前详情页请求的 projection、work 偏好的 projection、
  以及真正拿去落库/调用远端接口的 projection，
  不再被一个旧 `anchorMangaId` 混在一起；
- 后续继续收 details / scrobbling selector /
  tracking discovery 的 representative / preferred 解析时，
  已经有了可复用的稳定分层。

边界仍然明确存在：

- `ScrobblingInfo` 对外仍保留 `mangaId` 作为兼容锚点，
  因此上层 UI 和调用方还没有完全摆脱 projection-centric 字段名；
- 各 scrobbler repository 远端接口仍然大量以 `entity.mangaId`
  作为本地 source/type 推断输入；
- details 页里与 tracking discovery、source switch、metadata source selection
  相关的 representative/projection 解析，仍有一部分重复逻辑没有回收到统一 helper。

所以当前判断应是：

- **scrobbling 的底层 ownership 改造已经连续推进**
- **运行时 work-aware 语义也开始收口**
- **但它还没有达到“整个实体改造基本完成”的程度**

#### details 内部的 tracking link anchor 解析开始和 scrobbling 使用同一套分层语义

前一轮 `scrobbling` 已经把内部选择逻辑从单一 `anchorMangaId`
拆成了 `requested / preferred / persisted` 三层。

本轮 `DetailsViewModel` 又把 tracking link 这条详情页内部高频观察链，
沿着同一思路补了一刀：

- 原先 `observeTrackingLinksByWork(...)` /
  `resolveTrackingLinkAnchor(...)` /
  `selectTrackingLinksForWork(...)`
  维护的是一套 details 自己的旧 `anchorMangaId` 语义；
- 现在这组逻辑已改成围绕显式的 `WorkProjectionContext` 运行，
  内部同样区分：
  - `requestedMangaId`
  - `preferredLocalMangaId`
  - `persistedLocalMangaId`
  - `entityId`
- duplicate tracking links 的选择顺序，
  也改成先匹配当前请求 projection，
  再匹配 work preferred projection，
  再匹配真正可持久化的 representative/persisted projection。

这一步的意义不是“功能增加”，而是：

- details 页里最容易继续复制旧世界观的一条内部链，
  开始和 scrobbling 共享同一种 owner/projection 分层；
- 同一个 work 下多 projection 并存时，
  tracking links 的详情展示不再只被一个旧 `anchorMangaId`
  粗暴代表；
- 后续继续收 details 中 tracking discovery / metadata source /
  source switch 的重复解析时，已经有一层可以继续复用的本地语义模板。

边界同样明确：

- 这仍然只是 details 内部一处高价值重复逻辑的收口；
- `DetailsViewModel` 里还有其它 representative/projection 解析 helper
  没有统一回收到单一 Work context；
- 因此这里只能记为 **details 侧 owner-first 收口继续推进**，
  不能把它夸大成 details 全链完成统一。

#### details 当前本地投影 / 当前阅读投影的快照解析开始收敛到统一 helper

在 details 页继续往下收时，又碰到另一类长期残余：

- “当前 active local projection 是谁”
- “当前 reading projection 是谁”
- “metadata/source 操作应该落到哪个本地 projection 上”

这些判断此前虽然大体围绕 `currentObservedLocalMangaIdSnapshot()`、
`activeLocalSourceOptions`、`sessionReadingProjectionLocalMangaId`
协作，但仍然散落在多个 helper 和写链入口里。

本轮先做了一个收敛动作：

- `DetailsViewModel` 新增 `CurrentWorkProjectionSnapshot`
  与 `currentWorkProjectionSnapshot()`；
- 这层快照会统一给出：
  - `activeLocalMangaId`
  - `currentReadingProjectionMangaId`
- `refreshActiveLocalBrowserContent(...)`
  / `resolveEntityChapterSourceInfo(...)`
  / `resolveCurrentLocalMangaId()`
  等 details 内部高频 helper，
  已开始优先消费这层统一快照，而不是各自重复拼装当前 local context。

这一步的意义：

- details 内部关于“当前 local projection / 当前 reading projection”
  的判断开始从分散状态收缩到单一入口；
- 后续继续收 `source switch` / `metadata source` /
  `tracking suggestion bind/remove` 这些写链时，
  可以复用同一份 current projection context，
  不必继续扩散新的 snapshot 变体；
- 这属于把 details 从“很多局部看起来都差不多”
  往“真正共享一套 work/projection 运行时上下文”推进。

边界仍然明确：

- 这层 helper 目前还是 details 内部局部收口，
  没有外提成跨模块共享 abstraction；
- tracking discovery 打开详情后的 legacy fallback、
  以及 metadata/source 相关的部分入口，
  还没有全部改成只消费这层统一快照；
- 因此当前仍然只能算 **details 内部上下文继续去重复**，
  还不是 details 主链的最终统一。

#### tracking discovery 打开详情后的 legacy fallback 开始优先尊重当前 work/projection context

details 页还有一个长期容易反向污染当前 projection 语义的入口：

- 从 tracking discovery / tracking item 打开详情；
- 如果当前还没有确认 entity binding，
  旧逻辑会直接按 `tracking_site_links` 的 legacy 排序，
  选一个本地 `mangaId` 把详情页拉过去。

这类逻辑的问题不在于“不能 fallback”，而在于：

- 它很容易让 link cache 的旧排序结果，
  重新定义当前 details 应该展示哪个 projection；
- 与前面刚收起来的
  `requested / preferred / persisted / current reading`
  语义完全脱节。

本轮收口：

- `selectLegacyTrackingLinkAnchor(...)`
  不再只能盯着 link 自己的 manual/confidence/updatedAt 排序；
- 调用方会先传入一组当前 details 已知的首选本地 projection：
  - `currentReadingProjectionMangaId`
  - `activeLocalMangaId`
  - `baseLoadedDetails.local.manga.id`
- 只有这些都无法命中时，
  才退回旧的 legacy link 排序。

这一步的意义：

- tracking discovery 打开详情时，
  legacy fallback 终于开始先尊重当前 details 已经解析出的
  work/projection context；
- 旧 `tracking_site_links` 缓存顺序不再天然有资格覆盖当前详情页
  已经建立起来的 representative 选择；
- 这进一步减少了不同入口把 details 拉回旧 projection 的概率。

边界：

- 这里仍然保留了 legacy fallback，
  没有直接把无 entity 的 tracking 打开流程彻底废掉；
- metadata/source 相关的部分写链，
  仍然还在继续传 `fallbackMangaId`
  而不是统一消费单一 Work context object；
- 因此这依旧是 **details legacy 入口止血与收口**，
  不是 details 入口统一的终局。

#### details 的 metadata/source 持久化写链开始收敛到统一的 metadata persistence anchor

前面几轮虽然已经把 details 内部的 current projection
解析往统一 helper 收了一些，但 metadata/source 相关写链还有一个明显残余：

- `selectMetadataSource(...)`
- `removeMetadataSourceBinding(...)`
- `bindMetadataSource(...)`
- reading candidate 绑定后的 metadata source 回写

这些入口过去普遍会各自传一个临时 `fallbackMangaId` 给
`persistMetadataSourceSelectionForCurrentEntity(...)`。

这类写法的问题是：

- 持久化锚点的选择散在 UI 分支里；
- 不同入口对“应该把 metadata source 记到哪个本地 projection 上”
  可能给出不完全一致的答案；
- work/entity 没命中时，projection fallback 语义会继续在入口层扩散。

本轮收口：

- `DetailsViewModel` 新增了
  `resolveCurrentMetadataPersistenceMangaId()`；
- 这层 helper 会统一按当前已知上下文解析 metadata 持久化锚点：
  - `activeLocalMangaId`
  - `currentReadingProjectionMangaId`
  - `baseLoadedDetails.local.manga.id`
  - `resolveCurrentLocalMangaId()`
- `persistMetadataSourceSelectionForCurrentEntity(...)`
  默认不再要求调用方手传 fallback；
- `selectMetadataSource(...)` /
  `bindMetadataSource(...)` /
  reading candidate bind 后的 metadata 回写，
  已开始直接使用这层统一解析。

这一步的意义：

- metadata/source 持久化终于开始有一个更明确的“当前写到哪里”中心点；
- details 页里这类 owner/projection 语义不再继续散落在每个 UI 分支里各自判断；
- 后续继续收 details 的 metadata/source / tracking suggestion /
  source switch 写链时，可以在同一个 persistence anchor 上继续演进。

边界：

- 仍有少数入口会显式传入 `fallbackMangaId`，
  因为它们本身就在明确切换/删除某个指定 projection；
- 这层 helper 目前仍然是 details 内部 abstraction，
  还没有上提成跨模块通用的 work context writer；
- 因此这里仍然属于 **details 写链继续收口**，
  不是 metadata authority 终局完成。

#### details 的 metadata source 恢复链也开始默认走统一 persistence anchor

除了“写入 metadata source 选择”本身，details 页还有一条经常被忽略的残余：

- `applyEntityContext(...)`
- 初始化阶段命中本地 entity 后的 restore
- `doLoad()` 得到最终 details 后的 restore

这些路径过去在恢复 metadata source selection 时，
也会各自把一个临时 `fallbackMangaId` 传给
`restoreEntityMetadataSourceSelection(...)`。

这类逻辑的问题和写链一样：

- “恢复时如果 entity 没有独立 selection，就该回退哪个 projection”
  的答案分散在多个入口里；
- 同一页在不同加载阶段可能给出不同 fallback；
- 这会让 metadata restore 语义继续依赖入口时序而不是统一上下文。

本轮收口：

- `restoreEntityMetadataSourceSelection(...)`
  也开始默认解析统一的 metadata persistence anchor；
- `applyEntityContext(...)`
  / 初始化命中本地 entity
  / `doLoad()` 后命中本地 entity
  这三条恢复路径，已不再各自手传 fallback；
- 只有那些“明确要对某个指定 projection 做操作”的写链，
  仍然继续显式传入 `fallbackMangaId`。

这一步的意义：

- metadata source 的“恢复读路径”开始和“持久化写路径”
  围绕同一套 persistence anchor 收拢；
- details 页在不同生命周期阶段恢复 metadata source 时，
  更不容易因为入口不同而落到不同的 projection fallback；
- 当前工作树里，显式传 `fallbackMangaId` 的剩余位置，
  更接近“明确指定目标 projection”的必要参数，
  而不是 owner/projection 语义散落的普遍表现。

边界：

- source switch / remove binding 这类显式操作，
  仍然保留指定 projection 的参数是合理的；
- `restorePersistedMetadataSourceSelection(mangaId)` 这类更底层 legacy API
  仍然存在，本轮没有继续下切；
- 因此这里只能记作 **restore 链也跟上统一 persistence anchor**，
  不是 details metadata/source 全部收口的终点。

补一条当前边界判断：

- 继续往下清理后，`DetailsViewModel` 里残余的显式
  `fallbackMangaId` 调用点已经明显减少；
- 当前还保留的几处，主要是：
  - `selectActiveLocalSource(mangaId)`
  - `removeActiveLocalSource(nextActiveMangaId)`
  - tracking discovery legacy fallback 选中的 `trackingMangaId`
- 这些位置的共同点是：
  **它们本身就在明确指定一个要切换/恢复到的目标 projection**，
  因而保留显式参数是语义合理的；
- 相反，那些只是把“当前上下文里本来就能推导出的 local id”
  再手传一遍的调用，已经继续被裁掉。

这一步的意义：

- `tracks` / `track_logs` 的 owner-first 过渡结构，
  终于补上了最基本的物理存储边界；
- runtime 可以继续处理 entity/work 语义，
  但落到 legacy `manga_id` 外键列时，至少不再把无效 id 当本地 projection；
- 这能直接降低近期 `SQLiteConstraintException: FOREIGN KEY constraint failed`
  这一类错误的再发生概率。

边界同样要明确：

- 这还不是 tracker 最终的 Work-owned 存储模型；
- 当前只是把“写入 legacy projection anchor 列时必须是真实本地 projection”
  这条底线补齐；
- `TrackingRepository` 内部仍保留大量 `anchorMangaId` 运行时语义，
  后续仍需要继续区分 owner / anchor / display representative。

#### tracker 运行时展示模型开始直接携带 Work 上下文

本轮继续把 tracker 的运行时聚合语义从“UI 再按 `manga.id` 反查 entity”
往 repository 侧前移了一层：

- `ContentTracking` 不再只是
  `anchorMangaId + manga + counters` 的薄展示对象；
- 当前它已经开始直接携带：
  - `entityId`
  - `preferredLocalMangaId`
- `TrackingRepository.resolveDisplayTrackings(...)` /
  `getTrack(...)` / `getTrackOrNull(...)`
  在输出 `ContentTracking` 时，会一起填入这层 Work 上下文。

连带收口：

- `UpdatesViewModel.aggregateByEntity()`
  不再默认对 `item.manga.id` 再做一次
  `entityGraphRepository.findEntityIdsByAnyMangaIds(...)` 反查；
- `FeedViewModel.aggregateFeedUpdatesByEntity()`
  也开始直接消费 `ContentTracking.entityId /
  preferredLocalMangaId` 做分组与 representative 选择；
- 这意味着 tracker 的 UI 聚合层开始逐步摆脱
  “展示内容 id 同时承担 entity lookup key” 的混合职责。

这一步的意义：

- owner / persisted anchor / display representative
  这三层职责，第一次在 tracker 运行时模型里有了显式分离的雏形；
- UI 分组链不再必须把 `manga.id` 当作 work identity lookup 的唯一入口；
- source switch 之后，updates / feed 再次黏回旧 projection id 的概率继续下降。

边界：

- `ContentTracking.anchorMangaId` 这个命名仍然保留了旧时代的混合色彩；
- 当前只是把 Work 上下文从 UI 回查前移到 repository 输出，
  还没有把 tracker 全链路的命名与 API 完整重塑；
- 因此这里仍应视为 **Phase 3 的继续推进项**，不是最终收口。

#### history / tracks 交界的 favorite 与 updates 判定继续从 legacy manga 语义收口

本轮把 `HistoryDao` 里两类长期混杂的旧判断继续往 Work-first 收紧：

- `deleteNotFavorite()`
  过去只看 legacy `favourites.manga_id = history.manga_id`，
  这会在 favorite ownership 已经主要迁到 `work_favourites`
  后误删仍然有效的历史项；
- history 列表里 `favorite` / `new chapters` 相关过滤与排序，
  虽然已经能碰到 `tracks.entity_id`，
  但 legacy fallback 仍然容易直接盯住当前 `history.manga_id`。

本轮收口结果：

- `HistoryDao.setDeletedAtNotFavorite(...)`
  现在会先检查 `work_favourites` 的 entity ownership，
  再 fallback 到 representative local projection 的 legacy `favourites`；
- `entityIdExpr(...)`
  也补上了与其它 owner-first SQL 一致的 binding 选择优先级：
  `MANUAL > CONFIRMED > LEGACY`，
  并按 `updated_at / rowid` 做稳定 tie-break；
- `preferredTrackAnchorExpr(...)`
  被明确重命名成更贴近真实语义的
  `representativeLocalMangaIdExpr(...)`；
- history 的 `trackFieldExpr(...)` 与 `favouriteExistsExpr(...)`
  的 legacy fallback 都开始跟随 representative local projection，
  不再默认等同于当前 `history.manga_id`。

这一步的意义：

- history 清理链不再只把 legacy `favourites` 当作唯一真相；
- history 列表里的“这个作品是否被收藏/是否有追更”
  开始更稳定地跟随当前 Work representative；
- `history` 与 `tracks / work_favourites / entity_preferences`
  之间的 owner 语义撕裂继续缩小。

边界：

- `history` 物理表本体仍然是 legacy local projection anchor；
- 搜索、tag、downloaded 等很多查询仍然直接建立在 `history.manga_id`
  与本地 `manga` 关联上；
- 因此这里依然属于 **Phase 3 进行中**，不是 history 主链最终统一。

#### history 运行时列表模型开始直接携带 Work 上下文

继 tracker 之后，history 主链本轮也开始把 Work 上下文从 UI 反查前移到
repository / observer 输出：

- `ContentWithHistory`
  不再只是 `manga + history` 的薄模型；
- 当前它已经开始直接携带：
  - `entityId`
  - `preferredLocalMangaId`
- `HistoryRepository.buildObservedHistoryList(...)`
  与 `HistoryLocalObserver.observeAll(...)`
  在构造 `ContentWithHistory` 时会一起填入这层上下文。

连带收口：

- `HistoryListViewModel.foldAdjacentByEntity()`
  不再默认对列表项的 `manga.id` 再做一次
  `findEntityIdsByLocalMangaIds(...)` 反查；
- history 列表分组开始直接消费
  `ContentWithHistory.entityId / preferredLocalMangaId`；
- 代表 projection 的选择逻辑也开始优先跟随
  repository 已解析出的 preferred local projection。

这一步的意义：

- history 运行时链也开始摆脱
  “展示 content id 兼作 work identity lookup key” 的混合职责；
- UI 层对 entity graph 的直接回查继续减少；
- history / tracker 两条高频主链的 runtime 语义开始更一致，
  都转向“repository 输出 work-aware item，UI 只消费结果”。

边界：

- `ContentWithHistory.manga`
  仍然是当前代表 projection 的显示内容，而不是独立 Work 对象；
- history 的搜索、分页、恢复、legacy mirror 等链路里，
  仍保留大量 local projection anchor 语义；
- 因此这里只是 **Phase 3 的继续推进**，不是 history 最终统一。

#### history repository 内部 aggregate/cache key 开始从 `manga.id` 转向 owner-first

本轮继续深入到 `HistoryRepository` 内部，把之前仍默认按
`manga.id` 建立缓存与聚合 lookup 的地方进一步收口：

- `favouriteCache`
  与 `trackCache`
  之前默认都直接以 `manga.id` 作为 key；
- 这会让同一个 Work 下不同 projection 在 history 列表的过滤、排序、
  new chapters 聚合里重复分叉；
- 即使运行时模型已经带上 `entityId`，
  repository 内部仍可能继续沿用旧 projection-first key。

当前收口：

- `HistoryRepository` 新增了 owner-aware 的 `HistoryOwnerRef`；
- `getFavouriteCategoryIds(...)` /
  `getTrackAggregate(...)` /
  `getCachedTrackAggregate(...)`
  开始优先用 `entityId` 作为 cache key；
- 没有 entity 的 legacy 内容，才回落到 `-mangaId` 的过渡 key；
- favourite / track aggregate 的 anchor 解析，
  也开始优先跟随 `preferredLocalMangaId`，
  不再默认用当前展示 `manga.id` 当唯一锚点。

这一步的意义：

- history 列表内部用于排序、过滤、计数的缓存层，
  终于开始和 work ownership 语义对齐；
- 同 work 多 projection 在 history 列表里的 aggregate 结果，
  被旧 projection key 撕裂的概率继续下降；
- repository 内部开始不只是“输出 work-aware item”，
  连中间计算缓存也在向 work-aware key 迁移。

边界：

- 当前仍保留了 `entityId == null` 时的 `-mangaId` fallback；
- `resolveHistoryAnchorIds(mangaId)` 旧入口依然存在，
  兼容未完成 work 绑定的 legacy 内容；
- 因此这里只是继续降低 residual manga-centric 行为，
  还不是 history 主链的最终 owner-only 形态。

#### history 的删除/恢复/单项读取入口开始统一走 owner-aware helper

本轮继续把 history 主链外缘的几个高频入口，从“直接拿 `manga.id` 走旧 helper”
收口到 owner-aware helper：

- `delete(manga)`
- `delete(ids)`
- `findHistoryEntityByWorkAnchor(...)`
- `getOneByWorkAnchor(...)`

当前收口：

- `HistoryRepository` 新增了按单个 `mangaId`
  解析的 `resolveHistoryOwnerRef(mangaId)`；
- 删除单项与批量删除时，
  会先解析 `entityId + anchorMangaId + cacheKey`
  再决定：
  - 删哪条 `work_history`
  - 删哪些 legacy `history` rows
- `findHistoryEntityByWorkAnchor(...)`
  也不再先天假设“传入的 mangaId 就是唯一 legacy anchor”，
  而是通过 owner-aware anchor 集合做回查。

这一步的意义：

- history 主链的读写删除入口开始共享同一套 owner 解析；
- batch delete / single delete / single lookup
  不再分别各自套一层老式 `mangaId -> entity/anchor` 推断；
- 后续要继续替换 `resolveHistoryAnchorIds(mangaId)` 的地方时，
  已经有了统一的 owner-aware 中间层可复用。

边界：

- `recent` / `search` / legacy observer 等链路里仍存在 projection-first 入口；
- `resolveHistoryOwnerRef(mangaId)` 仍对缺失 entity 的情况回落到 `mangaId`；
- 因此这里只是继续减少入口分裂，不代表 history 主链已经统一完成。

#### history 的 recent / getLast / observeLast 开始统一到同一条 recent 主链

本轮又收了一处典型的过渡结构：history 的“最近阅读”相关外露入口，
之前分成两套：

- 一套先查 `work_history`
- 另一套在 work 结果为空时再 fallback 到 legacy `history`

涉及入口：

- `getList(...)`
- `getLastOrNull()`
- `observeLast()`
- `observeRecentContents(...)`

当前收口：

- 这些入口现在都统一走
  `findRecentContentsByWorkAnchor(...)`
  这条 recent 聚合主链；
- `findLastContentByWorkAnchor()` 这一层单独分支已经被去掉；
- `collectRecentLegacyEntries(...)`
  也开始通过 owner-aware helper 过滤掉已经有 Work ownership 的内容，
  而不是把 legacy page 结果原样当作独立真相。

这一步的意义：

- “最近阅读”相关接口不再各自保留一套 work / legacy 双路径判断；
- recent 主链开始成为 history 外露读取的统一入口；
- 后续要继续压缩 legacy fallback 时，
  只需要集中收敛 recent 聚合链，不必分别改多个 API。

边界：

- `findRecentContentsByWorkAnchor(...)`
  目前内部仍然保留 `collectRecentWorkEntries + collectRecentLegacyEntries`
  的双源合并；
- 这说明 recent 主链已经统一，但底层 source 归并仍处于过渡期；
- 所以这里只是进一步集中入口，不是 recent 逻辑的最终形态。

补记：

- `collectRecentLegacyEntries(...)`
  之前对每条 legacy history 记录都单独做一次 owner 解析；
- 当前已经先按 page 批量解析 `entityIdsByMangaId`，
  再过滤掉已有 Work ownership 的项；
- 这还没有消除 recent 的双源结构，
  但已经把其中最明显的逐行 owner lookup 散射收成了批量解析。

#### history 列表构建链开始把 owner 解析前移到批量阶段

本轮继续推进 `buildObservedHistoryList(...)` 这条链，
它之前最明显的问题是：

- 先拿 `getList(0, Int.MAX_VALUE)` 得到 recent 展示内容；
- 然后对每个 content 再分别做：
  - `resolveWorkEntityId(content.id)`
  - `findEntityPrefs(entityId)`
  - `getOneByWorkAnchor(content.id)`

也就是典型的“先拿显示内容，再逐项反查 owner/state”。

当前收口：

- `buildObservedHistoryList(...)`
  现在会先按 recent contents 批量建立 `ownerRefsByMangaId`；
- 再按 entity 批量解析 preferred local projection；
- 然后统一构造 `ContentWithHistory`，
  而不是在 map 中一边迭代一边重复拆 owner。

这一步的意义：

- history 列表构建开始从“内容驱动 + 逐项反查”
  往“owner 预解析 + 统一装配”移动；
- entity / preferredLocal 的重复查询进一步减少；
- 后续如果要继续把 history 列表主链压成更清晰的 work-first 组装，
  已经有了更合适的中间结构。

边界：

- 当前仍然保留了 `findHistoryEntityByWorkAnchor(content.id)` 这层逐项历史状态回查；
- 因此这还不是 history 列表构建的最终模型，
  只是把 owner 相关的重复解析先批量化收口。

补记：

- `HistoryDao` 现已补上 `findByIds(ids)` 批量查询接口；
- `buildObservedHistoryList(...)`
  也不再逐项调用 `findHistoryEntityByWorkAnchor(content.id)`；
- 当前做法改成：
  - 先批量拉 `work_history`
  - 再批量拉 legacy `history`
  - 最后按 ownerRef/anchor 集合统一装配 `ContentWithHistory`

这意味着 history 列表状态装配已经从“逐项状态回查”迈向
“批量状态拉取 + 统一装配”的下一阶段。

#### history 的 search / popular 聚合入口开始显式依赖 unified recent 主链

本轮对 history 的上层聚合接口做了一次小但必要的语义收口：

- `search(...)`
- `getPopularTags(...)`
- `getPopularSources(...)`
- `buildObservedHistoryList(...)`

之前它们都直接写死：

- `getList(0, Int.MAX_VALUE)`

虽然这已经会经过 unified recent 主链，
但代码层面仍然把“全量 recent 内容”隐藏成了一个普通分页调用。

当前收口：

- `HistoryRepository` 新增了明确语义的
  `getAllRecentContents()`；
- 上面这些聚合接口现在都显式依赖
  `getAllRecentContents()`，
  不再把“取全部 recent 内容”伪装成普通分页读取。

这一步的意义：

- history 的上层聚合接口开始直接表达自己依赖的是
  “统一 recent 主链的全量视图”；
- 后续若要把 search/popular 聚合继续从 recent 主链里拆出独立 work-first 查询，
  已经有了更清晰的抽象边界。

边界：

- 这还没有改变 search/popular 的底层算法；
- 当前只是把它们与 recent 主链的依赖关系显式化，
  不是这些接口的最终形态。

#### history / tracker 列表 UI 开始去掉多余的 EntityGraphRepository 依赖

随着 `ContentWithHistory` 与 `ContentTracking` 都已经开始直接携带
`entityId / preferredLocalMangaId`，
本轮把几处已经失效的 UI 层 entity graph 依赖继续拆掉：

- `HistoryListViewModel`
- `FeedViewModel`
- `UpdatesViewModel`

当前变化：

- 这些 ViewModel 不再注入 `EntityGraphRepository`；
- 也不再保留“列表项里没有上下文时，UI 自己再补查 entity”
  这一层过时依赖预期；
- work-aware 上下文的唯一来源继续收敛到 repository / domain 输出。

这一步的意义：

- repository 输出与 UI 消费之间的边界更清楚；
- work-first 改造不再只是“逻辑上可用”，而是开始真正反映到依赖图；
- 后续如果继续收口 `details / scrobbling` 等链路，
  也能更容易判断哪些 entity graph 依赖仍然是必要的，
  哪些只是历史残留。

#### 下载完成通知与追更通知，详情回跳开始统一走 Work-aware origin

本轮又补了两条用户高频但之前容易漏掉的“回到详情页”链路：

- `DownloadNotificationFactory`
  在下载完成通知里不再直接把
  `displayManga / localManga / executionManga`
  当作最终详情 owner；
- 当前逻辑会先尝试解析 entity，
  命中后构造 `DetailsOrigin.EntityGraph(entityId, preferredLocalMangaId)`，
  否则才回落到 `LocalMangaContent`；
- `TrackerNotificationHelper`
  也不再直接用 `AppRouter.detailsIntent(context, representativeManga)`，
  而是先按当前 local projection 查 entity，
  命中时同样回跳到 Work-aware 的 `EntityGraph` 详情 origin。

这一步的意义是：

- 下载完成后点通知，不会再稳定绕回旧 projection-first 详情入口；
- 追更通知点击详情，也开始跟随当前 Work / preferred projection，
  而不是把通知生成时拿到的 representative manga 永久当真；
- `details` 的入口收口不再只停留在列表页、搜索页、收藏页，
  连后台通知回跳也开始对齐 Work-first 语义。

#### feed 主日志列表点击链，开始脱离 representative content 反推

本轮继续补了一个之前仍然残留的用户面入口：

- `UpdatedContentHeaderItem` 之前已经显式携带
  `entityId / preferredLocalMangaId`，
  因此顶部 updated carousel 早已可以直接回到
  `DetailsOrigin.EntityGraph(...)`；
- 但主 feed 日志流里的 `FeedItem`
  之前只保留 `manga` 与展示 override，
  点击详情时仍然只能走 `navigateToDetailsWithContent(...)`，
  本质上还是从 representative content 反推 owner；
- 当前已把 `TrackingLogItem -> FeedItem` 这条映射链补齐：
  `entityId / preferredLocalMangaId` 会从
  `track_logs.manga_id` 对应的 work/entity 解析出来，
  并一路带到 feed item；
- `AppNavGraph` 中 feed 主列表点击后，
  若命中 `entityId`，将直接构造
  `DetailsOrigin.EntityGraph(entityId, preferredLocalMangaId, initialProjectionLocalMangaId)`，
  只有无法解析 work 时才回落到 content-only 详情入口。

这一步的意义是：

- feed 顶部轮播和 feed 主日志列表，
  终于都进入同一条 Work-aware 详情跳转链；
- `TrackingLogItem.manga`
  现在更明确只是展示 representative，
  不再被暗中复用成详情 owner；
- tracker/feed 用户面入口与 execution anchor 的语义撕裂，
  又少了一条最直接的回流路径。

边界同样明确：

- `track_logs` 表物理上仍然是 `manga_id -> tracks.manga_id -> manga.manga_id`
  的 projection-anchor 外键；
- `TrackLogWithContent` / `TrackWithContent` / `ContentWithTrack`
  仍然是 Room relation 层的 raw anchor 结构；
- 因此这仍然属于 **Phase 3 的继续推进**，
  不能误判成 tracker/feed 底层结构已经统一完成。

#### tracker relation 结果模型已显式收正为 anchor record 语义

本轮还补了一步容易被忽视、但对后续维护非常重要的收敛：

- 原先 `TrackWithContent` / `ContentWithTrack` / `TrackLogWithContent`
  这组类型名，天然会误导人以为 DAO 已经返回了“可直接展示的内容结果”；
- 实际上这些 Room relation 仍然只是
  `tracks.manga_id` / `track_logs.manga_id`
  对应的 **raw projection anchor 记录**，
  展示 representative 一直都是 repository 再解析出来的；
- 当前已把这组三个类型显式改名为：
  `TrackAnchorRecord` /
  `TrackAnchorWithTags` /
  `TrackLogAnchorWithTags`；
- 对应 DAO 与 repository/debug 消费点也一起切到新命名，
  让“DAO 返回 anchor，repository 负责 display resolve”
  这个职责边界在代码里直接可见。

这一步的意义是：

- 后续再读 tracker/feed 代码时，不会继续把 relation 结果误判成 display truth；
- 继续向 Work-first 演进时，结构债和语义债至少不再混在同一个命名层；
- 即便物理表结构暂时还没动，维护者也更不容易在 repository 之下偷偷塞回展示逻辑。

边界：

- 这仍然只是 **语义收正**，不是存储结构迁移；
- `tracks` / `track_logs` 的外键与 Room relation 形态没有本质变化；
- 所以它是后续底层替换的准备动作，不是终局完成。

#### tracker/feed 动态流查询，已开始脱离 Room `@Relation` 组装

在仅靠命名收正之外，本轮还对 tracker/feed 的动态流进一步做了结构削弱：

- `TracksDao.observeUpdatedContent(...)`
  现在返回裸 `TrackEntity` 行；
- `TrackLogsDao.observeAll(...)`
  现在返回裸 `TrackLogEntity` 行；
- `TrackingRepository`
  新增批量 hydrate 逻辑：
  先批量取 `MangaEntity`、`MangaTagsEntity`、`TagEntity`，
  再在 repository 内组装 fallback content，
  最后才解析 display representative；
- 也就是说，feed / updates / tracking log 这条动态展示链，
  已经不再依赖 Room `@Relation`
  直接产出 `anchorManga + tags` 的复合结果。

这一步的意义是：

- tracker/feed 最活跃、最容易被继续打补丁的动态流，
  已经从“DAO 直接拼内容”退回到“DAO 给原始 anchor，repository 负责 hydrate”；
- 这让 projection-anchor 的物理存在与 display/work 语义之间，
  有了更清晰的隔离层；
- 下一阶段如果要继续把 `tracks / track_logs`
  改造成 work-owned 存储，repository 已经更接近唯一组装点。

边界：

- `TracksDao.findAll(...)` / `observeAll()`
  这类 debug/静态读取路径目前仍保留旧的 relation 类型；

#### scrobbling provider 写回链继续向 entity-first 公共 helper 收口

本轮继续把 `scrobbling` provider 层的 owner 归一化与 preview 补写，从
“各仓库各自 `attachEntityOwnership + dao.upsert`”
进一步收口到公共 helper：

- `ScrobblingOwnership`
  新增 `upsertScrobbling(...)` 与 `upsertScrobblingPreview(...)`；
- `MAL / AniList / Simkl / Kitsu / MangaUpdates`
  的远端同步、增量同步、保存评分等高频写回，
  已开始统一走 `db.upsertScrobbling(...)`；
- `Kitsu.persistPreview(...)`
  与 `MangaUpdates.persistRemoteCoverIfMissing(...)`
  不再直接把 `entity.copy(remoteCoverUrl/remoteTitle/remoteUrl)` 回写到 DAO，
  而是统一走 `db.upsertScrobblingPreview(...)`；
- `MAL.getContentInfo(id)` 的 endpoint 解析
  不再直接用任意一条 `findAllByTargetId(...)` 记录猜测，
  而是先按 `preferredScrobblingByTargetAndMediaType()` 做统一优选后再判定。

这一步的意义是：

- provider 层高频写回，不再一边收口 ownership、一边继续散落新的直写口；
- preview / remote cover 这类“看起来只是补展示字段”的写链，
  也开始服从同一套 entity ownership 归一化；
- targetId 多行记录并存时，provider 读取主记录的策略进一步一致，
  后续继续推进 entity-first physical identity 时阻力更小。

边界：

- `scrobblings` 表的物理主键仍然是 `manga_id` 参与的 projection-first 结构；
- `findByLocalManga(...)` / `observeByLocalManga(...)` /
  `deleteByLocalManga(...)` 这类 legacy projection-anchor DAO
  仍然存在；
- 因此这一步仍然是 **运行时与写回链继续收口**，
  不是 `scrobblings` 底层 identity 已完成 Work-native 迁移。

#### scrobblings DAO 开始补显式 preferred entity 查询语义

在继续推进 provider 写回收口之外，本轮还补了一层更明确的 DAO / helper 语义，
为后续 `scrobblings` 主键迁移做准备：

- `ScrobblingDao`
  新增 `findPreferredByEntityTargetAndMediaType(...)` /
  `observePreferredByEntityTargetAndMediaType(...)`；
- `ScrobblingOwnership`
  新增 `findPreferredScrobblingByWorkTargetAndMediaType(...)`；
- `Bangumi / Shikimori`
  也已切到 `db.upsertScrobbling(...)`，
  至此主要 provider 的高频写回入口已基本不再散落
  `attachEntityOwnership + dao.upsert` 组合。

这一步的意义是：

- `entity_id + target_id + media_type` 这组过渡期 owner 语义，
  开始从“调用方自己拼排序规则”
  变成 DAO 明确提供的 preferred 读取语义；
- provider 层继续减少对 legacy projection-anchor API 的直接依赖；
- 后续若要重建 `scrobblings` 表并调整唯一约束，
  调用面已经更接近 Work-first，而不是一堆隐式约定。

边界：

- 这仍然没有改变 `scrobblings` 的物理主键；
- 只是先把“谁是 entity owner 下的首选记录”显式化；
- 因此它依旧属于 **结构迁移前的过渡层清理**，
  还不是最终的 Work-native identity 完成态。

#### scrobblings 物理 identity 已从 `manga_id` 直接主键推进到过渡 `owner_id`

本轮开始真正触碰 `scrobblings` 的底层存储结构，而不是只做运行时和 helper 收口：

- `ScrobblingEntity`
  主键已从
  `("scrobbler", "id", "manga_id", "media_type")`
  改成
  `("scrobbler", "id", "owner_id", "media_type")`；
- 新增 `ownerId`
  作为过渡期的单一物理 owner 标识：
  - 优先使用 `entity_id`
  - 否则退化为 `-manga_id`
  - 再不行才用 `0`
- `Migration63To64`
  已重建 `scrobblings` 表，并将旧数据按上面的规则回填 `owner_id`；
- `ScrobblingOwnership.attachEntityOwnership(...)`
  / `upsertScrobblingForManga(...)`
  / `rebindScrobblingToManga(...)`
  已同步写入 `ownerId`；
- `ScrobblingBackup`
  已带上 `owner_id`，
  且恢复旧备份时会按 `entity_id/manga_id`
  自动推导过渡 owner；
- `BackupRepository`
  的 scrobbling restore
  已切到 `upsertScrobbling(...)`，
  不再直接裸 `dao.upsert(...)`。

这一步的意义是：

- `scrobblings` 的物理 identity
  已经不再直接把 `manga_id` 本体当主键组成部分；
- 对已 Work 化的记录，物理 owner 可以直接跟随 `entity_id`；
- 对仍是弱实体/legacy projection 的记录，
  也能通过 `-manga_id` 保留兼容过渡锚点，
  不需要一次性要求所有数据都先完成 entity 绑定。

边界必须说清楚：

- 这仍然是 **过渡 owner key**，
  不是最终的纯 Work-native identity；
- `manga_id` 字段仍然保留，运行时也仍承担 local projection reference；
- `tracks / track_logs` 还没有做同级别的底层 owner 重建；
- 因此 Work-first 底层改造还在进行中，不能把这一步当成全部计划结束。

#### tracks / track_logs 也已进入 owner-first 过渡结构

在 `scrobblings` 之后，本轮继续把 tracker 底层也推进到同级别的过渡 owner 模型：

- `TrackEntity`
  新增 `ownerId`，
  主键已从单纯 `manga_id`
  改成 `owner_id`；
- `tracks`
  表新增 `manga_id` 唯一索引，
  因此运行时仍能按 anchor manga 读取，
  但物理 identity 已不再由 `manga_id` 直接主导；
- `TrackLogEntity`
  新增 `ownerId`，
  用于把日志和过渡 owner 对齐；
- `Migration64To65`
  已完成：
  - `tracks` 表重建
  - 旧 `tracks` 数据按 `entity_id ?: -manga_id ?: 0`
    回填 `owner_id`
  - `track_logs` 增加 `owner_id` 并回填
- `TrackingRepository`
  的 track 保存、merge、log 写入
  已开始统一写入 `ownerId`；
- `MigrateUseCase`
  的 source alternative 迁移路径，
  也已同步补齐新的 track owner 写入。

这一步的意义是：

- tracker 主链终于不再完全依赖
  `tracks.manga_id` / `track_logs.manga_id`
  作为唯一物理身份；
- 已 Work 化的条目，owner 可以直接跟随 `entity_id`；
- legacy / 弱实体条目仍可通过 `-manga_id`
  保持过渡兼容，不会因为 owner 上移而立即丢锚。

边界：

- `track_logs` 目前仍保留自增 `id` 主键，
  这里只是补齐 owner 列并让写链对齐，
  还不是最终日志模型；
- `tracks / track_logs / scrobblings`
  三者虽然都已有过渡 owner 结构，
  但 `manga_id` 仍保留为 local projection reference，
  因此还不能宣称底层已经彻底去 projection 化；
- 下一阶段更关键的是继续清理 DAO / SQL / runtime
  对旧 `manga_id` 语义的隐式主导假设。
- `tracks` / `track_logs` 表结构仍未升级；
- 因此这里仍然只是 **底层削弱旧模型控制力**，
  不是 tracker 存储模型完成替换。

#### `tracks` 静态读取与 debug 展示链，也已脱离 `@Relation` 消费

继动态 flow 之后，本轮又补掉了 `tracks` 剩余的静态 relation 消费链：

- `TracksDao.findAll(...)` / `observeAll()`
  现在都直接返回裸 `TrackEntity`；
- `TrackingRepository.getTracks(...)`
  不再依赖 `TrackAnchorRecord`，
  而是和动态流一样走批量 hydrate；
- `TrackerDebugViewModel`
  不再直接注入 `MangaDatabase` 读取 relation，
  而是改为调用 `TrackingRepository.observeTrackDebugItems()`；
- 换句话说，tracker 模块里 **所有当前仍在实际消费的读取链**
  已经不再要求 DAO 返回 `anchorManga + tags` 的 Room relation 结果。

这一步的意义是：

- `tracks` 这张表在运行时的主要消费路径，
  已经全面退回到“DAO 给 anchor row，repository 负责 hydrate/display resolve”；
- debug 页面也不再是一个偷偷绕过 repository、直接绑定旧 projection relation 的后门；
- 后续真的要替换 `tracks` 存储结构时，消费层已经更接近单点入口。

边界：

- `TrackAnchorRecord` / `TrackAnchorWithTags` / `TrackLogAnchorWithTags`
  这几个旧 relation 类型文件已从 tracker 模块物理删除；
- `tracks / track_logs` 的表结构与外键仍然没有迁移；
- 因此这依然是 **去 relation 依赖**，不是 **去 projection-anchor 存储**。

#### `tracks / track_logs` 已进入第一阶段 Work-owned 存储迁移

在消费层脱离 relation 之后，本轮开始真正触碰 tracker 存储层：

- `TrackEntity` 已新增 `entity_id`；
- `TrackLogEntity` 已新增 `entity_id`；
- `Migration61To62`
  会为 `tracks / track_logs` 加列、回填 entity ownership、补索引；
- `TrackingRepository.saveUpdates(...)` /
  `mergeWith(...)` /
  `getOrCreateTrack(...)` /
  `syncTrackAnchors()`
  等写入路径，已经开始把 `entity_id` 一并写入；
- `TracksDao` / `TrackLogsDao` 在 owner 解析表达式里，
  也开始优先读取表内 `entity_id`，再回退到 `entity_binding`。

这一步的意义是：

- `tracks / track_logs` 终于不再只有 projection anchor，
  而是开始同时持有明确的 Work owner；
- 后续如果要把主键、唯一约束、清理策略、聚合查询
  进一步切到 Work-first，
  现在已经有了真实落地的数据支点；
- 这也让 tracker 存储层的演进路径与
  `tracking_site_links` / `scrobblings`
  之前的兼容迁移方式保持一致。

边界：

- 当前主键和外键仍然是 `manga_id`；
- `entity_id` 目前是 owner compatibility column，
  不是新的主键；
- `track_logs.gc()` 与多处 legacy SQL 仍然主要围绕 `manga_id` 运作；
- 所以这一步应视为 **存储层 Phase 1：owner column 落地**，
  还不是 tracker 表结构最终统一。

边界：

- 这仍然只是“详情回跳入口 Work-aware”，
  不是下载和 tracker 存储模型已经完成 Work-owned；
- `downloads / tracks` 的物理锚点和执行上下文仍然保留 projection 语义，
  所以这里只能算 **Phase 3 继续推进**。

#### `syncTrackAnchors()` 对真实存在的 local projection 增加了保护，降低外键漂移风险

本轮还对 `TrackingRepository.syncTrackAnchors()` 做了一个很实际的止血：

- entity / favourite 链路在决定 track anchor 时，
  现在统一复用 `resolveExistingTrackAnchorForEntity(entityId)`；
- 该解析会先取 `preferred_local_manga_id`，
  但只在对应 `manga` 真实存在时才采用；
- 否则回退到当前仍然存在的 local binding；
- `syncTrackAnchors()` 在 `upsert(TrackEntity.create(...))` 前
  也再次校验 `manga` 是否存在。

这一步主要针对的就是近期已经出现过的风险：

- `tracks` 物理表仍然对 `manga(manga_id)` 挂外键；
- 一旦 `preferred_local_manga_id` 或 fallback binding 指向已失效 projection，
  后续 `upsert` 就可能触发
  `SQLiteConstraintException: FOREIGN KEY constraint failed`。

当前这层保护的实际作用是：

- Work-owned favourites / prefs 在继续驱动追更锚点时，
  不会再盲信已经失效的 preferred projection；
- `tracks` anchor 同步更偏向“当前仍存在的 local projection”，
  从而降低 feed / updates 打开时 GC 或同步链路写坏 `tracks` 的概率。

边界同样明确：

- `tracks` 依然是 projection-anchor 表，不是 Work-owned 表；
- 这一步只是把“写入不存在的 manga 外键”先挡住，
  不是对 tracker 存储模型的最终统一。

#### dynamic shortcut 开始跟随当前 display / preferred projection

本轮继续收了共享入口里的 dynamic shortcut 主链：

- `AppShortcutManager.buildShortcutInfo(manga)` 现在会优先用
  `findDisplayContentById(...)` 解析当前 display / preferred projection，
  不再直接把传入内容的旧 local manga id 当 shortcut identity；
- `notifyContentOpened(mangaId)` 上报 shortcut usage 前，
  也会先解析当前 display manga id；
- `cleanupDatabase()` 读取 shortcut ids 后，
  会再次映射到当前 display / representative local content，
  再作为数据库清理保留集。

这一步的意义是：

- recent history 虽然已经是 Work-aware 数据源，
  但 shortcut 的物理 id、usage 统计、cleanup 保活
  之前仍可能固化在旧 projection 上；
- 现在这条链开始跟随当前 representative / preferred projection，
  旧 projection 继续“借 shortcut 活着”的概率下降；
- `continue reading / recent history / shortcut`
  之间的 representative 选择进一步一致。

边界：

- shortcut 仍然以具体 local manga id 作为 Android 层物理 shortcut id；
- 这里只是把它统一到当前 display/projection 解析，而不是把 Android shortcut
  自身抽象成 Work id；
- 因此它仍然属于 **Phase 3 的共享入口收口**，不是最终统一形态。

#### home resume 开始按 entity / preferred local projection 解析代表内容与历史锚点

本轮继续收了首页 resume 卡片这条共享入口：

- `HomeViewModel.resumeStateFlow`
  不再直接对首次命中的 `history.content.id` 做 `observeOne(...)`；
- 当前会先解析该内容所属 `entityId`，
  再读取 `entity_preferences.preferred_local_manga_id`；
- representative content 优先通过 `findDisplayContentById(...)`
  / `findPreferredLocalContentById(...)` 解析，
  历史观察锚点则优先落到当前 preferred local projection。

这一步的意义是：

- 首页 resume 不再把“第一次命中的 projection”
  永久当作继续阅读锚点；
- 当同一 work 切换当前阅读源后，
  home resume 更可能跟随当前 representative / preferred projection；
- `recent history / shortcut / home resume`
  三条共享入口的 representative 选择进一步收敛。

边界：

- 这还不是 reader resume / continue reading 全链路统一完成；
- `history.observeOne(...)` 仍然基于具体 local manga id 观察，
  只是观察锚点开始按当前 work preferred projection 解析；
- 因此这仍然属于 **Phase 3 进行中**，不是完成项。

#### 多源搜索结果页打开链开始跟随 entity / preferred projection

本轮还补了一条之前仍明显停留在旧语义的共享入口：

- `SearchActivity`
  之前从多源搜索结果点击内容时，仍然直接 `router.openDetails(content)`；
- 当前改为先解析 `entityId`，
  再解析 `preferredLocalMangaId`；
- 若已进入 Work 聚合语义，则直接走
  `router.openEntityDetails(entityId, preferredLocalMangaId)`，
  否则才回退到旧 `router.openDetails(content)`。

这一步的意义是：

- 多源搜索结果不再天然绕过当前 entity/work 详情入口；
- `首页 / 搜索建议 / 多源搜索结果`
  三条高频“打开内容”链路进一步收敛到一致的 representative 解析；
- source switch 后，从搜索结果重新进入详情页时继续命中旧 projection 的概率下降。

边界：

- `SearchContentListScreen` 仍然保留默认 `onOpenDetails = appRouter.openDetails(content)`
  的通用回退行为；
- 当前只是把 `SearchActivity` 这一条实际高频调用链收口到
  entity/preferred projection 优先；
- 因此它仍然属于 **Phase 3 的继续推进**，不是阶段完成信号。

#### 源内列表的内部详情中转开始直接传 `DetailsOrigin.EntityGraph`

本轮还补了另一条容易被忽略的搜索共享入口：

- `ContentListActivity`
  之前从源内列表进入内部详情子路由时，
  仍然只是把 `Content` 塞进 `PendingDetailsNavigation`；
- 当前会先解析目标内容的 `entityId`
  与 `preferredLocalMangaId`；
- 若已具备 Work 聚合上下文，则直接写入
  `DetailsOrigin.EntityGraph`，
  否则才回退到 `DetailsOrigin.LocalMangaContent`。

这一步的意义是：

- 搜索相关的两条高频详情中转
  （多源搜索结果页、源内列表内部详情）
  都开始直接进入 entity/work 详情语义；
- 内部详情子路由不再天然依赖“先把一个 local projection content
  塞进去，再由详情页自己二次修正”的旧前提；
- `PendingDetailsNavigation`
  在这些高频入口上的 payload 更接近真实 owner 语义。

边界：

- `AppNavGraph.navigateToDetailsWithContent(...)`
  这类更泛化的全局 helper 仍保留 `Content -> PendingDetailsNavigation`
  的旧兼容路径；
- 当前优先收的是高频实际调用方，而不是先把所有通用 helper 一次性改写；
- 因此这仍然属于 **Phase 3 的继续推进**，不是“所有详情入口统一完成”。

### Phase 4：restore / backup 旧语义隔离收尾

当前判断：**大部分完成**

证据：

- `WORK_HISTORY` / `WORK_FAVOURITES` / `WORK_STATS` /
  `SETTINGS_READER_GRID` 已进入 backup / restore / WebDAV restore 主链；
- `normalizeRestoredWorkState(...)` 已改成按 section 精细 gate；
- auto restore 后 upload gate 已统一复用 `BackupFlowPolicy.autoSyncUploadDecision()`。

仍未完全兑现的点：

- `dataVersion / semanticSchemaVersion / transportGeneration`
  已不再只停留在文档层：
  `WebDavAutoRestoreService` 会把候选备份的 `dataVersion`
  和 `BackupRepository.resolveRestoreSemanticContext(...)`
  解析出的语义上下文一起提交给 restore coordinator；
- 若要宣称这一 phase 完成，还需要更强证据证明
  remote payload 版本/代际边界已经在 restore 之外的整条现行同步主链
  形成稳定的拒写/隔离/兼容闭环，而不是主要集中在 restore 协调层。

### Phase 5：术语与表结构统一

当前判断：**尚未开始，且不应现在启动**

原因：

- 当前 ownership 与 runtime 边界虽然已经大幅稳定，但仍有收尾项；
- 这阶段如果现在做，会把大量语义修复和 rename 噪音混在一起；
- 按计划文档原意，它本就应该排在最后。

## 已完成的主链收口

### 1. ownership 已经在高价值状态上移

以下用户状态已经有明确的 Work-owned 结构：

- `work_history`
- `work_favourites`
- `work_stats`

对应代码落点：

- [WorkHistoryEntity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/history/data/WorkHistoryEntity.kt)
- [WorkFavouriteEntity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/data/WorkFavouriteEntity.kt)
- [WorkStatsEntity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/stats/data/WorkStatsEntity.kt)

这一步的意义不是简单加表，而是把以下语义从 projection 侧挪到了 work/entity 侧：

- 收藏归属
- 历史归属
- 统计归属

### 2. tracking / scrobbling ownership 已经 work-aware

tracking 与 scrobbling 相关 ownership 已经不再单纯依赖 `mangaId`。

当前主链：

- `tracking_site_links.entity_id`
- `scrobblings.entity_id`
- `EntityOwnershipResolver.resolveWorkEntityIdByMangaId(...)`
- `ScrobblingOwnership`

对应代码落点：

- [TrackingSiteLinkEntity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/db/entity/TrackingSiteLinkEntity.kt)
- [ScrobblingEntity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/scrobbling/common/data/ScrobblingEntity.kt)
- [EntityOwnershipResolver.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityOwnershipResolver.kt)
- [ScrobblingOwnership.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/scrobbling/common/data/ScrobblingOwnership.kt)

当前代码已经明确：

- `mangaId` 经常只是 projection anchor；
- owner 优先是 `entity/work`；
- projection fallback 只用于兼容无 entity 或旧数据场景。

### 3. reading record / history 主链已经 work-first

阅读记录与历史的 ownership 已经有清晰边界：

- 读：聚合同 work 下所有 local projections；
- 写：落到 preferred projection anchor；
- 清理：按整个 work 清理。

对应代码落点：

- [ReadingRecordRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/readingrecord/data/ReadingRecordRepository.kt)
- [HistoryRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/history/data/HistoryRepository.kt)

当前语义已经接近目标模型：

```text
Work = 状态 owner
Projection = 阅读执行上下文
```

### 4. metadata authority 已基本回到 entity/work

`ContentDataRepository` 当前已经完成这几个关键收口：

- `getMetadataSourceSelection(s)` 走 entity-first 读取；
- `setMetadataSourceSelection(mangaId, ...)` 在有 entity 时只写 `entity_preferences`；
- 无 entity 时才回退到 `preferences`；
- entity-level metadata selection 不再保留 projection mirror 批量回写入口。

对应代码落点：

- [ContentDataRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/parser/ContentDataRepository.kt)
- [PreferencesDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/db/dao/PreferencesDao.kt)

特别是以下边界已经明确：

- `getLegacyMetadataSourceSelections(...)` 明确只是 legacy fallback；
- `setMetadataSourceSelection(mangaId, ...)` 本质上已经变成 projection-local override / no-entity fallback；
- `setEntityMetadataSourceSelection(..., mirrorLocalMangaIds=...)` 这类旧式 blind mirror 主链已被移除。

### 5. repair / migration worker 已纳入 metadata 镜像治理

当前 repair 已经可以识别并清理冗余 projection metadata mirror：

- `REDUNDANT_PROJECTION_METADATA_SELECTION`
- `pruneRedundantProjectionMetadataSelections()`

并且当前工作树已经进一步把 override mirror 也纳入同一类治理：

- `REDUNDANT_PROJECTION_OVERRIDE`
- `pruneRedundantProjectionOverrides()`

以及把 reading status mirror 纳入同一 repair 主链：

- `REDUNDANT_PROJECTION_READING_STATUS`
- `pruneRedundantProjectionReadingStatuses()`

对应代码落点：

- [EntityGraphModels.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/entitygraph/domain/EntityGraphModels.kt)
- [EntityGraphRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/entitygraph/data/EntityGraphRepository.kt)
- [EntityGraphMigrationWorker.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/entitygraph/work/EntityGraphMigrationWorker.kt)
- [SourceMigrationViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/migration/SourceMigrationViewModel.kt)
- [SourceMigrationPanel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/migration/compose/SourceMigrationPanel.kt)

### 5.28 tracker log feed 的 pinned / favorite 过滤开始脱离单投影 favourites

`track_logs` 仍然是 legacy 物理表，但其列表语义已经开始向 Work ownership 收口。

当前代码已完成：

- `TrackLogsDao.observeAll(...)` 的 pinned 排序
  不再只看 `favourites.manga_id`
  而是优先经 `entity_binding -> work_favourites` 解析；
- `TrackLogsDao.getCondition(...)`
  对：
  - `ListFilterOption.Macro.FAVORITE`
  - `ListFilterOption.Favorite(category)`
  已改为 `work_favourites` first，`favourites` fallback；
- 当某条 track log 对应内容已经进入 entity/work ownership 后，
  feed 排序与 favorite 过滤不再被单一 projection favourite 记录误导。

对应代码落点：

- [TrackLogsDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/db/dao/TrackLogsDao.kt)

这一步的边界也要说清楚：

- `track_logs.manga_id` 目前仍然是 preferred/local anchor；
- `TrackLogsDao.gc()` 仍然通过 `tracks` 表做物理清理；
- 所以这不是“track logs 已经 work-native”，而是
  “feed 视图层面的 pinned / favorite 语义已开始 work-aware”。

### 5.29 DetailsViewModel 构造期状态解析继续去除 `StateFlow.value` 硬依赖

详情页在 Work / Projection 混合态下，仍然存在少量“属性初始化或构造早期直接读取另一个 `StateFlow.value`”的链路。

当前工作树已继续收口：

- `currentObservedLocalMangaId.stateIn(...)`
  初始值不再直接取 `activeMangaIdFlow.value`；
- `observedVideoDownloadChanges.stateIn(...)`
  初始值不再直接取 `currentObservedLocalMangaId.value`；
- 构造期和 tracking/entity 上下文恢复中的若干读取：
  - metadata source restore
  - local work ensure
  - tracking entity applyContext
  - reading candidate bind
  已优先改走 `currentObservedLocalMangaIdSnapshot()`。
- 详情页其余高频入口里对 `currentObservedLocalMangaId` 的直接读取，
  也已继续收口到 snapshot helper，
  使切源、解绑、tracking metadata 切换时更少暴露空窗口。

对应代码落点：

- [DetailsViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt)

这一步的目标不是“彻底消灭所有 `.value` 读取”，而是先把详情页打开阶段最容易触发的
构造顺序 / 空状态窗口收紧，降低：

- `StateFlow.getValue()` 空引用
- `flatMapLatest` 下游拿到空 Flow 引用
- tracking 打开详情时的初始化竞态

当前判断：

- 这是稳定性止血与 Work-first 运行时统一的必要收尾；
- 但还不能宣称详情页所有初始化竞态都已完全消除，仍需继续结合运行期验证推进。

这一步很关键，因为它把以下两类 projection 影子污染都提升成了可诊断、可修复、可追踪的问题类别：

- metadata source 冗余镜像
- title / cover / content rating override 冗余镜像
- reading status 冗余镜像

同时，repair / normalize 的具体写链也继续向 Work-first 收紧：

- `clearMangaMetadataSourceIfSuspect(...)`
  不再把 projection metadata selection 修回 `"base"`；
- `resetDetachedLocalWorkPrefs(...)`
  也不再给 projection prefs 重建 `"base"` shadow；
- 当前规则已经明确成：
  - Work/entity prefs 可以用 `"base"` 表达默认 authority；
  - projection prefs 一旦被判定为冗余或可疑 shadow，就应直接清空为 `null`。

本轮还补了一层 repair 诊断去噪：

- `inspectRepairIssues()` 在 entity/work 已经持有 metadata selection 时；
- 不再继续把同一 work 下 projection prefs 的 tracking metadata selection
  额外当成独立 `SUSPECT_METADATA_SOURCE` 嫌疑源重复上报；
- projection prefs 在这条链路里继续只作为 shadow/cleanup 对象，而不再和 entity prefs 并列成“第二真相”。
- `inspectRepairIssues()` 在同一 work 已经存在确认 tracking binding 时；
- 不再把 projection prefs 上同 service + remoteId 的 tracking metadata selection
  继续报成独立 `SUSPECT_METADATA_SOURCE`；
- `STALE_TRACKING_CACHE_LINK` 诊断也已经收紧：
  若 entity/work 已有同 service + remoteId 的 active tracking binding，
  则对应 cache link 不再重复上报为 stale。

### 5.1 详情/阅读初始化入口开始优先解析当前本地投影，而非旧 Parcelable 快照

`ContentDataRepository.resolveIntent(...)` 已从：

- `intent.manga` 优先；
- `intent.mangaId` 次之；

收口为：

- 有 `mangaId` 时，优先读取数据库中的当前本地 projection；
- 只有数据库里已经不存在该本地锚点时，才回退到 `ParcelableContent` seed；
- `uri` 解析继续作为最后兜底。

对应代码落点：

- [ContentDataRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/parser/ContentDataRepository.kt)

这一步的意义是：

- details / reader / page picker 等共享初始化链，不再无条件相信启动时塞进来的旧内容快照；
- 当本地 projection 已经被切源、修复、补章或重新绑定后，运行时会优先回到数据库中的当前状态；
- `ParcelableContent` 降级为启动 seed / 缺库 fallback，而不再天然拥有更高 authority。

### 5.2 小说阅读器启动入口已切到 `KEY_ID -> 当前内容 -> seed fallback`

`NovelReaderActivity` 之前仍然直接依赖：

- `intent.getParcelableExtraCompat<ParcelableContent>(KEY_MANGA)`

来决定当前阅读内容，这意味着：

- 历史恢复、切源后重进、修复后重进时；
- 旧启动快照仍可能继续定义当前章节集、source 与后续历史写入上下文。

当前已收口为：

- 路由侧打开 `NovelReaderActivity` 时显式附带 `KEY_ID`；
- Activity 启动时优先通过 `ContentIntent(intent)` 解析当前数据库内容；
- 仅当当前 id 无法解析时，才回退 `ParcelableContent` seed。

对应代码落点：

- [AppRouter.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/nav/AppRouter.kt)
- [NovelReaderActivity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/reader/novel/NovelReaderActivity.kt)

这一步的边界很明确：

- 仍保留 seed fallback，避免旧入口直接崩掉；
- 但启动 authority 已从“谁先塞进 Intent 就信谁”转成“本地当前内容优先，seed 只是兜底”。

### 5.3 alternatives / scrobbling selector 已开始脱离 seed-only 初始化

以下旧 UI 入口原先仍然主要依赖 `savedStateHandle` 里的 `ParcelableContent`：

- `AlternativesViewModel`
- `ScrobblingSelectorViewModel`

它们的问题不只是展示，而是会继续驱动：

- alternative 搜索参考内容；
- migrate/link/scrobbling 相关写链；

也就是旧 seed 一旦过期，后续交互就可能建立在过时 projection 上。

当前已收口为：

- `AlternativesViewModel` 使用 `ContentIntent(savedStateHandle)` + `resolveIntent(...)`
  优先回到当前数据库内容；
- `ScrobblingSelectorViewModel` 启动时优先按 `initialManga.id`
  回查当前本地内容，但因为该 sheet 的 `KEY_ID` 已被 `scrobblerService.id` 占用，
  这里没有独立的 content id 锚点，所以当前策略仍保留
  “DB 缺失时直接 fallback 到 seed” 的 legacy compatibility；
- 旧 Fragment 路由的 `AlternativesSheet` / `LocalInfoDialog` / `ContentStatsSheet`
  也开始补传 `KEY_ID`，为后续进一步统一初始化语义铺路。

对应代码落点：

- [AlternativesViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/alternatives/ui/AlternativesViewModel.kt)
- [ScrobblingSelectorViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/scrobbling/common/ui/selector/ScrobblingSelectorViewModel.kt)
- [AppRouter.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/nav/AppRouter.kt)

### 5.4 related / preview / stats / local info 旧入口继续向当前内容优先收口

这轮继续处理了一批仍以 `ParcelableContent` 为唯一初始化来源的旧入口：

- `RelatedListViewModel`
- `PreviewViewModel`

### 5.20 details 运行时本地锚点继续统一到 `currentObservedLocalMangaId`

`DetailsViewModel` 前几轮虽然已经把大部分 owner-sensitive 读写链从
`activeMangaIdFlow` 收到 `currentObservedLocalMangaId`，但仍残留几处
“活动投影 id”直接充当当前本地锚点的读取：

- `persistMetadataSourceSelectionForCurrentEntity()` 的默认 fallback；
- `refreshActiveLocalBrowserContent()` 的浏览器内容锚点；
- tracking metadata 详情页下 `toggleMarkSafe()` 对“当前是否存在本地锚点”的判断；
- tracking item 反向落回 entity 上下文时，`preferredLocalMangaId` 的兜底值。

当前已继续收口为：

- 默认 fallback 改为 `currentObservedLocalMangaId.value`；
- 浏览器内容预览优先使用 `activeLocalSourceOptions`，否则回到
  `currentObservedLocalMangaId`，而不是直接信 `activeMangaIdFlow.value`；
- tracking metadata 视图下的内容 override 判断也改成看
  `currentObservedLocalMangaId.value == null`；
- tracking item 已绑定 entity 但暂未显式切换本地投影时，
  `preferredLocalMangaId` 的兜底也改成当前观测到的本地锚点。

这一步的边界很明确：

- `activeMangaIdFlow` 仍然保留“当前会话投影选择”的职责；
- 但凡是“谁代表当前本地 owner / 当前本地锚点”的读取，都继续往
  `currentObservedLocalMangaId` 统一；
- 这样可以减少 tracking synthetic header、projection 切换、
  local source 选项刷新之间的短暂错位把会话态误当 owner truth。

### 5.21 tracking item 外部入口的 metadata source 不再被本地 restore 重放抢回

`DetailsOrigin.TrackingItem` 是一个特殊入口：

- 页面一开始就带着明确的 tracking source；
- 随后可能再通过 tracking link / entity binding 挂上本地 projection；
- 这时 `applyEntityContext()` 和后续 `doLoad()` 又会触发
  `restoreEntityMetadataSourceSelection(...)`。

之前这条链存在一个竞争问题：

- 用户当前仍然停留在 tracking origin 对应的 metadata source；
- 但 entity/work 上历史持久化的 metadata selection 可能是另一个 source
  或 `Base`；
- 一旦本地详情加载完成，restore 会把当前 tracking origin 选择重新覆盖。

当前已补 gate：

- 若当前页面属于 `DetailsOrigin.TrackingItem`；
- 且当前 `selectedMetadataSource` 仍然就是这个 origin 自带的
  tracking selection；
- 则 `applyEntityContext()` 和 `doLoad()` 后续的
  `restoreEntityMetadataSourceSelection(...)` 不再抢先重放旧持久化选择。

这一步的作用不是取消 restore，而是明确优先级：

- tracking item 外部入口的显式来源选择，优先于 entity/work 上的旧 restore；
- 只有当用户已经离开这个 origin 选择，或页面本身不是 tracking item 入口，
  才继续按既有 entity/work restore 逻辑收敛。

### 5.22 TrackingEntity 入口的 supplemental 面板改为可解析 entity tracking details

`DetailsOrigin.TrackingEntity` 与普通 work/item tracking 页面不同：

- 它的主 header 可能来自 synthetic content；
- 它依赖 `cachedEntityTrackingDetails` / `readEntityDetails(...)`
  持有人物、角色等 entity 级 tracking 详情；
- 但此前 `syncDisplayedState()` 只把 `currentTrackingMetadataDetails()`
  传给 `updateSupplementalDetailsState(...)`。

这会导致一个实际缺口：

- TrackingEntity 页面即使已经拿到 entity tracking details；
- supplemental 区域里的 infobox、评论、长评、角色/相关作品/推荐等内容
  仍可能是空的；
- 因为这些数据没有从 entity tracking cache 解析出来。

当前已补统一解析：

- 新增 `currentSupplementalTrackingDetails()`；
- 优先沿用当前 work/item tracking selection；
- 若当前页面是 `DetailsOrigin.TrackingEntity`，则回退到
  `cachedEntityTrackingDetails` / `readEntityDetails(...)`；
- `syncDisplayedState()` 改为把这份 supplemental tracking details
  传给 `updateSupplementalDetailsState(...)`。

这一步的意义是：

- TrackingEntity 页面不再只有 synthetic header；
- 它的 supplemental 信息现在可以真正跟随当前 entity tracking 详情刷新；
- 不再把 “work/item tracking details” 当成 supplemental 的唯一来源。

### 5.23 details 构造期的本地锚点读取改为 snapshot helper，避免初始化时序崩溃

本轮在真机日志里暴露出一个构造期时序问题：

- `syncDisplayedState()` 会在 `DetailsViewModel` 的早期 `init` 阶段执行；
- 它会继续走到 `refreshResolvedPresentationState()` ->
  `refreshActiveLocalBrowserContent()`；
- 若这里直接读取后声明的 `currentObservedLocalMangaId.value`，
  就可能在属性尚未初始化完成时触发 `NullPointerException`。

当前已改为：

- 新增 `currentObservedLocalMangaIdSnapshot()`；
- 在构造期敏感的 `refreshActiveLocalBrowserContent()` 里，
  只用这个 snapshot helper 从
  `activeMangaIdFlow.value / mangaDetails.value?.local?.manga?.id / displayed local content`
  组合出当前本地锚点；
- 避免在 ViewModel 构造完成前直接解引用延后初始化的 `StateFlow` 属性。

这一步是纯时序止血，不改变 ownership 语义，只是把
“当前本地锚点的快照读取” 和 “持续观测 flow” 分开。

补充：

- 同一轮真机日志还暴露出另一类同源问题：
  `init` 阶段已经开始 `collect currentObservedLocalMangaId...`，
  但 `currentObservedLocalMangaId` / `observedVideoDownloadChanges`
  属性如果声明在过晚位置，也会在 ViewModel 构造完成前被空引用。
- 当前已把这两个 flow 属性前移到主属性区，
  使构造期的 `init`、`syncDisplayedState()` 与后续协程收集都能安全访问。

### 5.24 details 主数据出口开始过滤失效的本地 `file://` 封面路径

真机日志还暴露出另一类运行时污染：

- 详情页 header / source option / fallback cover 会直接使用
  `ContentDetails.coverUrl` / `toContent().coverUrl`；
- 若 override 或历史缓存里残留的是已删除的本地 `file://...cache/...jpg`；
- Coil 会继续尝试打开这个失效路径，并在日志里反复报
  `FileNotFoundException`。

当前已补一个统一边界：

- 新增 `String.takeIfUsableImageUri()`；
- 对本地 `file://` 路径会先检查文件是否仍存在；
- 不存在则直接降级为 `null`，不再把失效 file-uri 继续向 UI 下游传播；
- `ContentDetails.coverUrl` 与 `mergedContent` 的封面出口已接入这层过滤。

这一步的作用不是清理所有历史脏数据，而是先把详情页主展示链收口：

- 失效的本地缓存封面不再继续被当成可展示真相；
- header / fallback cover / source option 等详情页主出口会优先退回到
  仍然可用的封面来源，而不是反复触发图片加载失败噪音。

### 5.25 tracker / favourites / history 的排序与筛选开始从 projection-centric SQL 收口

本轮继续推进了 `updates / tracker / new chapter counters` 相关的 DAO 层收口，
重点不是改 repository 语义，而是把最明显的
`tracks.manga_id = 当前列表项 manga_id`
这类 projection-centric 子查询开始改成 work-aware 解析。

当前变化：

- `TracksDao.observeUpdatedContent(...)`
  的 pinned 排序，不再只看 legacy `favourites.manga_id`；
  会先尝试按 `entity_binding` -> `work_favourites` 解析当前 work 的 pinned 状态，
  再回退到 legacy `favourites`；
- `TracksDao.getCondition(...)`
  里的 `FAVORITE` / `Favorite(category)`，
  已改成 work favourite first、legacy favourites fallback；
- `FavouritesDao`
  的 `NEW_CHAPTERS / PROGRESS / UNREAD / LAST_READ / LONG_AGO_READ / UPDATED`
  排序，已开始分别从
  `entity_preferences.preferred_local_manga_id`
  与 `work_history`
  解析 work 级 representative / history 状态，
  再回退到 legacy `tracks` / `history`；
- `FavouritesDao`
  的 `COMPLETED / NEW_CHAPTERS` filter，
  也不再只看单一 projection 的 `history` / `tracks`；
- `HistoryDao`
  的 `NEW_CHAPTERS / UPDATED` 排序和
  `FAVORITE / Favorite(category) / NEW_CHAPTERS` filter，
  同样开始接入 `work_favourites` 与 preferred projection anchor 解析。

对应代码落点：

- [TracksDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/tracker/data/TracksDao.kt)
- [FavouritesDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/data/FavouritesDao.kt)
- [HistoryDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/history/data/HistoryDao.kt)

这一步的边界也要说清：

- `tracks` 表当前仍然是 preferred local projection anchor 驱动，
  不是 work-native 存储；
- 底层表结构和部分辅助查询仍保留 legacy projection 假设；
- 因此这里只能算 Phase 1 / Phase 3 的继续收口，
  不能据此声称 tracker 主链已经完全 Work 化。

### 5.26 tracker 生命周期主链开始脱离 legacy `tracks.gc()` 语义

在上一轮 DAO / SQL 收口之后，`tracker` 运行时还有一条明显的旧主链：

- `TrackingRepository.gc()`
- `TrackingRepository.updateTracks()`
- `TrackingRepository.clearCounters()`

仍然依赖：

- `TracksDao.gc()`
- legacy `history` / `favourites`
- 以及“让 `tracks` 表自己按 projection 集合自清理”的旧语义。

本轮已继续收口为：

- `observeUpdatedContentCount()`
  不再直接读取 `TracksDao.observeUpdateContentCount()`；
  改为复用已 work-aware 的 `observeUpdatedContent(...)` 聚合结果计数；
- `gc()`
  不再把 `TracksDao.gc()` 作为主清理入口；
  改为按当前 work-aware track anchor 集合显式 `syncTrackAnchors()`；
- `updateTracks()`
  改为直接用 `work_history.anchor_manga_id`
  与 `work_favourites + entity_preferences.preferred_local_manga_id`
  重建当前应存在的 track anchor 集合；
- `clearCounters()`
  也不再盲清整个 `tracks` 表，
  而是只清当前仍然有效的 track anchor 集合；
- 为此新增了：
  - `WorkHistoryDao.findActiveAnchorMangaIds()`
  - `WorkFavouritesDao.findTrackedEntityIds()`

对应代码落点：

- [TrackingRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/tracker/domain/TrackingRepository.kt)
- [WorkHistoryDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/history/data/WorkHistoryDao.kt)
- [WorkFavouritesDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/data/WorkFavouritesDao.kt)

这一步的意义是：

- `tracks` 表虽然还没有升级成 work-native 存储，
  但它的“存在集合”已经开始由 work-aware ownership 主链驱动；
- legacy `TracksDao.gc()` 已不再定义当前 tracker 主链的真相边界；
- 后续剩余工作更多会集中在底层表结构、旧辅助查询
  和少量 legacy fallback 上，而不是继续放任 projection 自治。

### 5.27 分类新章节统计开始脱离 `favourites -> tracks` 的单投影求和

收藏分类页和 updates quick filter 还保留着一条很旧的统计链：

- `FavouriteCategoriesDao.getMostUpdatedCategories(...)`

此前它的实现是：

- 先取某个分类下的 `favourites.manga_id`
- 再直接对这些 `manga_id` 的 `tracks.chapters_new` 求和

这会把同一 work 下多个 projection 的脏历史直接放大进分类统计，
也完全绕过了 `work_favourites` 和 `preferred_local_manga_id`。

本轮已改成：

- 先按 `work_favourites`
  解析当前分类下的 work-owned favourite 集合；
- 再通过 `entity_preferences.preferred_local_manga_id`
  或该 work 的本地绑定回退，求出 representative track anchor；
- 只对这些 representative anchors 的 `tracks.chapters_new` 求和；
- legacy `favourites.manga_id` 仅在“该条收藏尚未进入 entity/work ownership”时继续作为 fallback。

对应代码落点：

- [FavouriteCategoriesDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/data/FavouriteCategoriesDao.kt)

这意味着：

- 分类级“哪些收藏夹最近更新最多”的排序，
  也开始从 projection-centric 统计转向 work-aware representative 统计；
- tracker quick filter 和相关分类入口看到的“新章节最多分类”，
  不再天然受同 work 多 projection 重复放大的影响。
- `ContentStatsViewModel`
- `LocalInfoViewModel`

当前变化：

- `RelatedListViewModel` 改为先按 `ContentIntent(savedStateHandle)` 解析当前内容，
  再决定 `source` 与 `getRelated(...)` 的查询基准；
- `PreviewViewModel` 先回到当前数据库内容，再做 `getDetails(...)`，
  避免预览页继续拿旧 seed 直接刷详情；
- `ContentStatsViewModel` / `LocalInfoViewModel`
  在 legacy Fragment 路由下也会优先按 `manga.id` 回查当前本地内容；
- `openRelated(...)` 也补传了 `KEY_ID`，和前面几条旧路由保持一致。

对应代码落点：

- [RelatedListViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/details/ui/related/RelatedListViewModel.kt)
- [PreviewViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/list/ui/preview/PreviewViewModel.kt)
- [ContentStatsViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/stats/ui/sheet/ContentStatsViewModel.kt)
- [LocalInfoViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/local/ui/info/LocalInfoViewModel.kt)
- [AppRouter.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/nav/AppRouter.kt)

### 5.5 收藏/下载批量对话框开始脱离 seed-only 集合

批量收藏与批量下载之前有一个共同问题：

- legacy Fragment 路由只传 `ParcelableContent` 列表；
- `FavoriteDialogViewModel` / `DownloadDialogViewModel`
  会直接把这批 seed 当作后续收藏/下载操作对象。

这会导致：

- 切源后批量收藏仍可能落在旧 projection 集合上；
- 批量下载也可能继续以过时快照作为章节、source 与详情加载起点。

当前已收口为：

- 路由侧额外补传 `KEY_ID` 的 `LongArray`；
- `FavoriteDialogViewModel` 启动时优先按 id 回查当前内容集合；
- `DownloadDialogViewModel` 启动时优先按 id 回查当前内容集合；
- 仅在当前数据库缺失该 id 时，才继续使用原先的 seed fallback。

对应代码落点：

- [FavoriteDialogViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/categories/select/FavoriteDialogViewModel.kt)
- [DownloadDialogViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/download/ui/dialog/DownloadDialogViewModel.kt)
- [AppRouter.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/nav/AppRouter.kt)

### 5.6 tracking 配置页绑定后改为以当前落库内容继续同步

`ScrobblerConfigViewModel.bindContent(...)` 之前的链路是：

- picker 返回一个 `ParcelableContent`；
- 直接以这个返回内容继续做 tracking relink；
- 再直接用这份返回内容继续做章节补全和历史同步。

这会让“绑定动作之后的后续 owner / history 同步”继续建立在 picker seed 上，
而不是当前数据库里真正落下来的 projection。

当前已收口为：

- 先 `storeContent(pickedContent, replaceExisting = false)`；
- 再按 `mangaId` 从数据库回读当前内容；
- 之后的章节补全、历史同步、成功提示标题都改用这份当前内容继续。

对应代码落点：

- [ScrobblerConfigViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/scrobbling/common/ui/config/ScrobblerConfigViewModel.kt)

### 5.7 prefetch / video UI 继续从启动快照退回运行时当前内容

这轮又补了两条剩余但仍有价值的边界：

1. `ContentPrefetchService`
   - `prefetchDetails` 之前完全依赖 `ParcelableContent`；
   - 现在会优先按 `KEY_ID` 回查当前内容，再回退 seed。

2. `VideoPlayerActivity`
   - 控制器里“章节/分页按钮是否可见”之前仍直接看启动 `ParcelableContent`；
   - 现在改成看 `currentMangaContent()`，
     与已经收口过的运行时内容主链保持一致。

对应代码落点：

- [ContentPrefetchService.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/details/service/ContentPrefetchService.kt)
- [VideoPlayerActivity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/video/ui/VideoPlayerActivity.kt)

### 5.8 VideoPlayerActivity 当前上下文已不再在行为期回退启动 intent

前面几轮已经把 `VideoPlayerActivity` 多数高风险分支改成优先运行时状态，
但 `currentMangaContent()` / `currentReaderStateOrIntent()` 这两个基础入口
仍然保留了：

- `readerState ?: intent.extra`
- `mangaContent ?: intent.KEY_MANGA`

这意味着只要后续任意行为链复用这两个方法，
旧启动快照仍会重新参与当前章节、当前内容、历史保存、标题解析等判断。

当前已进一步收口为：

- `currentReaderStateOrIntent()` 只返回运行时 `readerState`
- `currentMangaContent()` 只返回运行时 `mangaContent`

也就是说：

- intent 现在只承担启动初始化；
- 进入行为期后的“当前上下文”不再从启动快照回补。

对应代码落点：

- [VideoPlayerActivity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/video/ui/VideoPlayerActivity.kt)

### 5.9 VideoPlayerActivity 广告跳过重载分支不再回退启动 URL

`onPlaybackEnded()` 里的可疑广告自动跳过逻辑，
之前在无法从 `readerState` 解析当前章节时，仍会直接回退：

- `intent.KEY_URL`

来寻找当前章节与下一次 `prepareAndPlay(...)` 的目标 URL。

这会让已经切换过的运行时媒体 URL，
在广告重载这种行为期分支里重新被启动快照覆盖。

当前已收口为：

- 优先使用 `currentMediaUrl`
- 再回退当前运行时 `manga?.url`
- 不再从 `intent.KEY_URL` 重新取启动快照

对应代码落点：

- [VideoPlayerActivity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/video/ui/VideoPlayerActivity.kt)

### 5.10 快捷方式生成前不再把旧内容快照直接写回数据库

`AppShortcutManager.buildShortcutInfo(manga)` 之前会直接：

- 用传入的 `manga` 生成 icon / title
- 然后 `storeContent(manga, replaceExisting = true)`

如果调用入口传入的是旧 seed，
pin shortcut 或动态 shortcut 刷新本身就可能把旧快照重新写回数据库。

当前已收口为：

- 先按 `manga.id` 回读当前数据库内容；
- icon/title/intent 以及 `storeContent(...)`
  都改为优先使用这份当前内容；
- 只有缺库时才继续使用传入 seed 兜底。

对应代码落点：

- [AppShortcutManager.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/os/AppShortcutManager.kt)

### 5.11 下载调度入队前不再直接把旧内容快照写回数据库

`DownloadWorker.Scheduler.schedule(...)` 之前在为下载、翻译预处理、超分预处理建 WorkRequest 前，
会直接对调用方传入的 `manga` 执行：

- `storeContent(manga, replaceExisting = true)`

这意味着只要入口传入的是旧详情页快照、旧章节页快照或旧列表项快照，
下载入队动作本身就会把过期 projection 再写回数据库。

当前已收口为：

- 先按 `task.mangaId` 回读数据库中的当前内容；
- `storeContent(...)` 优先写回这份当前内容；
- 只有该 id 已经缺库时，才继续使用调用方传入的 seed 兜底。

这一步没有改变下载任务的物理锚点模型：

- work input 仍然只记录 `mangaId`；
- 真正执行时仍由 `doWork()` 按 `task.mangaId` 读取当前内容；
- 但“入队前先把谁写回库”这件事，已经不再默认相信旧 seed。

对应代码落点：

- [DownloadWorker.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/download/ui/worker/DownloadWorker.kt)

### 5.12 VideoPlayerActivity 启动入口已补齐 `KEY_ID -> 当前内容 -> seed fallback`

此前 `VideoPlayerActivity` 虽然已经把大量行为期上下文判断收回到运行时状态，
但启动期仍然直接使用：

- `intent.getParcelableExtraCompat<ParcelableContent>(KEY_MANGA)`

来初始化 `mangaContent`。

这意味着视频详情页、章节页或历史入口只要带着旧 seed 重进播放器，
启动阶段的标题、副标题、章节上下文与后续运行时锚点仍可能先被旧快照定义。

当前已收口为：

- `AppRouter.openVideo(url, manga, ...)` 显式补传 `KEY_ID`；
- `VideoPlayerActivity` 启动时优先按 `KEY_ID` 回查数据库中的当前内容；
- 只有当前 id 已经缺库时，才回退 `ParcelableContent` seed。

这一步的边界与之前小说阅读器收口一致：

- seed 仍保留，用于兼容旧入口与缺库场景；
- 但启动 authority 已不再默认由 `ParcelableContent` 决定。

对应代码落点：

- [AppRouter.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/nav/AppRouter.kt)
- [VideoPlayerActivity.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/video/ui/VideoPlayerActivity.kt)

### 5.13 视频章节面板初始化不再提前把 `intent.manga` 升格为当前上下文

`VideoChaptersViewModel` 之前在 `detailsLoadUseCase(...)` 真正开始解析当前内容之前，
会先执行：

- `mangaDetails.value = intent.manga?.let { ContentDetails(it) }`
- 再把 `observedLocalMangaId` 回填成这份 seed 的 id

这会让视频章节面板启动早期的：

- history 观察
- 当前章节 restore
- 下载状态观察锚点

先基于旧 `ParcelableContent` 建立一轮上下文。

当前已收口为：

- 初始化阶段不再把 `intent.manga` 直接塞进 `mangaDetails`；
- `observedLocalMangaId` 只先保留 `ContentIntent.mangaId`；
- 真正的当前内容、章节集与后续观察锚点统一等待 `detailsLoadUseCase(...)` 解析结果。

这一步没有移除 seed fallback 本身，但把 seed 的职责继续压回到：

- 启动参数兼容；
- `resolveIntent(...)` 缺库时的兜底输入；

而不是在章节面板里抢先定义当前 runtime content。

对应代码落点：

- [VideoChaptersViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/video/ui/VideoChaptersViewModel.kt)

### 5.14 页面选择器初始化不再提前把 `intent.manga` 当作当前详情

`PagePickerViewModel` 之前在真正加载详情前，会直接：

- `val manga = MutableStateFlow(intent.manga?.let { ContentDetails(it) })`

这样页面选择器启动早期的空态判断、章节缩略图上下文，
会先绑定到启动 seed，而不是当前数据库内容解析结果。

当前已收口为：

- 初始化阶段不再直接把 `intent.manga` 塞进 `manga` 状态；
- 页面选择器和章节面板一样，统一等待 `detailsLoadUseCase(...)`
  解析出的当前内容来建立章节缩略图上下文。

这里调用方本来就已经传了 `KEY_ID`，
因此这一步的目标不是新增能力，而是去掉“seed 抢先定义当前内容”的旧行为。

对应代码落点：

- [PagePickerViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/picker/ui/page/PagePickerViewModel.kt)

### 5.15 主阅读器初始化不再提前把 `intent.manga` 塞进 `mangaDetails`

`ReaderViewModel` 之前在 `loadImpl()` 真正开始走 `detailsLoadUseCase(intent, ...)` 之前，
会先执行：

- `mangaDetails.value = intent.manga?.let { ContentDetails(it) }`

这意味着主阅读器启动早期的：

- `manga` 相关设置观察
- incognito 判定输入
- 部分空态 / restore 前置观察

会先基于旧 `ParcelableContent` seed 建一层暂态上下文。

当前已收口为：

- 初始化阶段不再直接用 `intent.manga` 预热 `mangaDetails`；
- 主阅读器与小说阅读器、视频播放器、页面选择器一样，
  统一等待 `detailsLoadUseCase(...)` / `resolveIntent(...)`
  解析出的当前内容来建立 runtime content。

这一步没有移除 seed fallback：

- 缺库时仍然可以通过 `resolveIntent(...)` 回退 seed；
- 但 seed 不再在阅读器里抢先充当当前详情。

对应代码落点：

- [ReaderViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/reader/ui/ReaderViewModel.kt)

### 5.16 details 首屏 helper 不再把 `intent.manga` 当作当前详情的最后兜底

这轮继续收了一刀 details 页剩余的 seed 预热语义。

之前 `DetailsViewModel` 里这几类 helper 还会把 `intent.manga` 当作最后兜底：

- `currentDetailsContent()`
- `knownSearchSourceNames()`
- `currentBaseContentType()`
- `currentMetadataLanguageCode()`
- `refreshReadingSearchSources()`
- `updateSourceOptions()`
- reading search 当前内容上下文

同时，`init` 阶段还会直接：

- `baseLoadedDetails = (originContent ?: intent.manga)?.let { ContentDetails(it) }`

这意味着 details 首屏在真实 `doLoad()` / `detailsLoadUseCase(...)` 完成前，
仍可能先由旧 `ParcelableContent` seed 定义：

- 标题搜索词
- 阅读搜索默认源
- metadata 语言/类型提示
- source options 基底内容

当前已收口为：

- `init` 阶段只允许 `DetailsOrigin.LocalMangaContent` 这类显式 origin payload
  参与首屏预热；
- raw `intent.manga` 不再作为 details helper 的最后一层“当前详情”兜底；
- `intent.manga` 只继续保留为 `doLoad()` 启动存在性检查的一部分，
  以及更底层 `resolveIntent(...)` 缺库时的 seed fallback 输入。

这一步的边界很明确：

- 不是把 seed fallback 从系统里删掉；
- 而是阻止 details 页在真实 current content 解析之前，
  继续把旧 seed 抢先升格为当前上下文。

对应代码落点：

- [DetailsViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/details/ui/DetailsViewModel.kt)

### 5.17 related 页在存在 `mangaId` 时不再把 seed 缓存成当前内容

`RelatedListViewModel` 之前虽然已经先走 `resolveIntent(...)`，
但初始化和刷新仍然会直接：

- `resolveIntent(...) ?: seed`

并把结果塞进 `currentContent`。

这意味着只要路由里同时带了：

- `KEY_ID`
- `ParcelableContent seed`

一旦当前数据库内容暂时缺失或尚未 materialize，
related 页还是会马上把旧 seed 升格成“当前 related 基准内容”。

当前已收口为：

- 有 `mangaId` 时，related 页只信 `resolveIntent(...)` 的当前解析结果；
- 只有纯 seed 入口、没有 `mangaId` 时，才允许回退到 `seed`；
- 初始化缓存和后续 refresh 都复用这条同一规则。

这一步的意义是：

- related 页不再在“明明已有当前 id 锚点”的前提下，
  又把旧详情快照缓存成当前上下文；
- `seed fallback` 继续保留，但只留给真正没有 id 的 legacy 入口。

对应代码落点：

- [RelatedListViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/details/ui/related/RelatedListViewModel.kt)

### 5.18 preview 页在存在 `mangaId` 时不再把 seed 升格成当前内容

`PreviewViewModel` 之前也保留了同类模式：

- `resolveIntent(...) ?: seed`

这意味着 preview 页在路由已经带了 `KEY_ID` 的前提下，
仍可能因为当前库内容暂时缺失，
直接拿旧 `ParcelableContent` seed 作为当前详情/历史 footer 的基底。

当前已收口为：

- 有 `mangaId` 时，preview 页只信 `resolveIntent(...)` 的当前解析结果；
- 只有真正没有 `mangaId` 的 legacy seed 入口，才允许回退到 `seed`。

这一步和 related 页的意义一致：

- `seed fallback` 继续保留兼容能力；
- 但不再在已有当前 id 锚点时，抢先冒充当前 runtime content。

对应代码落点：

- [PreviewViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/list/ui/preview/PreviewViewModel.kt)

### 5.19 alternatives / scrobbling selector 在存在 `mangaId` 时不再把 seed 升格成当前内容

这轮把另外两条同型入口也收掉了：

- `AlternativesViewModel`
- `ScrobblingSelectorViewModel`

它们之前都保留了类似语义：

- 有 id 先回查当前内容
- 查不到就立刻回退到 seed

这会让 alternatives 搜索参考内容、scrobbling selector 初始绑定对象，
在已有当前 id 锚点时仍可能被旧 `ParcelableContent` 抢先定义。

当前已收口为：

- 有 `mangaId` 时，只信当前数据库解析结果；
- 只有真正没有 `mangaId` 的 legacy 入口，才允许 seed fallback。

这一步的意义和 related / preview 一致：

- 保留 seed fallback 的兼容能力；
- 但不再让它在已有 id 锚点时，继续冒充当前 runtime content。

对应代码落点：

- [AlternativesViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/alternatives/ui/AlternativesViewModel.kt)
- [ScrobblingSelectorViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/scrobbling/common/ui/selector/ScrobblingSelectorViewModel.kt)

### 6. backup / restore 当前 schema 主链已经转为 work-aware

当前 V3 authoritative backup / restore 主链已经具备以下状态：

- `transportGeneration = 3`
- `semanticSchemaVersion = 3`
- WebDAV 默认 namespace 已切到 V3
- restore normalization 已接 `WORK_*`
- `ContentBackup` embedded prefs 仅保留 legacy compatibility

对应代码落点：

- [BackupRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/BackupRepository.kt)
- [ContentBackup.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/model/ContentBackup.kt)
- [BackupIndex.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/model/BackupIndex.kt)
- [BackupFlowPolicy.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/domain/BackupFlowPolicy.kt)
- [BackupWebDavUploadCoordinator.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/domain/BackupWebDavUploadCoordinator.kt)
- [BackupWebDavRestoreCoordinator.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/domain/BackupWebDavRestoreCoordinator.kt)

这里还要特别强调一个范围边界：

- 仓库里 `sync/` 与旧 `kotatsu sync` 相关源码仍然保留；
- 但当前 Work 化主链的备份/恢复隔离，主要落在本地 backup / WebDAV restore 体系；
- 不应再把旧 sync 服务源码误判为当前 authoritative 路径；
- 本轮所有“同步隔离”设计与验收，均以当前 backup / restore / WebDAV upload / WebDAV auto restore 主链为准。

### 6.1 WebDAV restore 入口已补齐 `INDEX + WORK_*`，不再把 v3 authoritative 备份降级成 partial legacy import

这轮补到一个真正的 restore gate 缺口：

- `WebDavAutoRestoreService.buildRestoreSections(...)`
- `PeriodicalBackupSettingsViewModel.restoreWebDavNow()`

之前都没有显式恢复：

- `BackupSection.INDEX`
- `WORK_HISTORY`
- `WORK_FAVOURITES`
- `WORK_STATS`

其中 auto restore 还缺：

- `SETTINGS`
- `SETTINGS_READER_GRID`

这会导致两个问题：

1. 即使远端备份本身是 v3 authoritative work schema，
   restore 主链也拿不到 `BackupIndex`，
   于是 `resolveRestoreSemanticContext(...)` 会退回 legacy 默认值；
2. 手动/自动 WebDAV restore 会只恢复 legacy history/favourites/stats，
   把 authoritative `WORK_*` payload 直接漏掉，
   结果把一次 v3 restore 错做成 partial legacy import。

当前已收口为：

- WebDAV auto restore 恢复集合显式加入 `INDEX`；
- v3 writer generation 下显式恢复 `WORK_*` 与设置相关 section；
- 手动 WebDAV restore 也显式恢复 `INDEX + WORK_* + SETTINGS_READER_GRID`。

这一步的意义不是“恢复更多字段”这么简单，而是把 restore gate 重新对齐到协议语义：

- 先正确读出 transport/semantic schema；
- 再按 authoritative work restore 或 legacy import 去做后续 write gate 与 normalization 判定。

对应代码落点：

- [WebDavAutoRestoreService.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/ui/webdav/WebDavAutoRestoreService.kt)
- [PeriodicalBackupSettingsViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/ui/periodical/PeriodicalBackupSettingsViewModel.kt)
- [HomeViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/home/ui/HomeViewModel.kt)

### 6.2 auto restore 后的 upload gate 已统一复用 `BackupFlowPolicy.autoSyncUploadDecision()`

之前 `WebDavAutoRestoreService` 在 restore 完成后，
对“是否允许 post-restore authoritative upload”的判断还是本地拼接条件：

- `isLegacyMigration`
- `restoreResultCommit.writeBlocked`

这会造成一个语义分裂：

- 普通 auto sync upload 走 `BackupFlowPolicy.autoSyncUploadDecision()`
- post-restore upload 走另一套 if/else

而真正需要拦截的条件其实不只一类，例如：

- `isBackupWebDavAutoUploadBlockedByLegacyRestore`
- `isWorkMigrationSyncWriteBlocked`

当前已收口为：

- post-restore upload gate 直接复用 `BackupFlowPolicy.autoSyncUploadDecision()`；
- `legacy_restore_block` / `work_migration_write_block`
  以及后续新增的 upload gate 条件，
  不再需要在 auto restore 里再手写一套判断分支。

这一步的意义是：

- restore 后自动回传是否允许，
  现在和日常 auto sync upload 共享同一份策略真相；
- legacy import、work migration write block、WebDAV 配置不完整等场景，
  都不会在 restore 后因为“分叉 gate”而漏拦。

对应代码落点：

- [WebDavAutoRestoreService.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/ui/webdav/WebDavAutoRestoreService.kt)
- [BackupFlowPolicy.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/domain/BackupFlowPolicy.kt)

### 6.3 `normalizeRestoredWorkState()` 已从整段早退改为按 section 精细 gate

`BackupRepository.normalizeRestoredWorkState()` 之前有个过粗的逻辑：

- 只要判定是 authoritative work restore，
  并且 archive 里同时存在 `WORK_HISTORY / WORK_FAVOURITES / WORK_STATS`，
  就直接整段 `return`

这个写法有两个问题：

1. `normalizeRestoredScrobblingState()` 也会被一起跳过，
   但它和 `WORK_*` backfill 不是同一种语义；
2. 历史/收藏/统计的 legacy -> work backfill 是按 section 是否缺 authoritative payload
   决定的，不应该用一个全局早退把三者绑死。

当前已收口为：

- `scrobbling` normalize 独立执行；
- `history / favourites / stats` 的 legacy backfill
  分别按对应 `WORK_*` section 是否已 authoritative restore 单独判定；
- 已恢复 `WORK_HISTORY` 的 restore，不再额外用 legacy history 再补一遍；
- `WORK_FAVOURITES` / `WORK_STATS` 同理。

这一步的意义是：

- authoritative work restore 不再被多余 legacy backfill 覆盖或搅混；
- restore normalize 终于从“全或无”变成和 section 语义一致的细粒度 gate。

对应代码落点：

- [BackupRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/BackupRepository.kt)

### 7. legacy backup section 已经从主真相降级

## 剩余高风险入口审计结论

基于当前工作树再扫一轮后，剩余项可以比较明确地分成两类。

### 仍值得继续收的

1. `ContentDataRepository.resolveIntent(...)` 的 seed fallback 使用面
   - 当前实现仍是：
     - `mangaId -> DB current`
     - `intent.manga -> seed fallback`
     - `intent.uri -> resolver fallback`
   - 这本身不是 bug；
   - 但后续仍要继续审调用方，避免有人把“能 fallback 到 seed”误当成“可以重新定义当前上下文”。

2. `DetailsViewModel` 剩余的 `originContent / baseLoadedDetails / activeMangaIdFlow` 交叉边界
   - `intent.manga` 这一层已经基本压掉；
   - 后续若还出问题，更可能来自：
     - synthetic header
     - tracking origin
     - entity preferred projection
     - active local anchor 之间的上下文切换。

### 当前应明确保留

1. `OverrideConfigViewModel`
2. `ColorFilterConfigViewModel`
3. `LocalChaptersRemoveService`
4. `VideoPlayerActivity` 里纯展示/检索兜底的 `KEY_TITLE / KEY_URL`
5. `ContentDataRepository.resolveIntent(...)` 的 seed fallback 本身

这些点当前都不应为了“形式统一”继续硬削：

- 前四项前面已经分别确认是 projection-local 或展示兜底；
- `resolveIntent(...)` 的 seed fallback 则是当前系统对缺库/旧入口兼容的基础能力，
  关键在于调用方不能再把 fallback 结果升级成 owner truth。

以下 legacy section 已经明确不再承载当前 schema 的 work/entity prefs 真相：

- `HistoryBackup`
- `FavouriteBackup`
- `BookmarkBackup`
- `ContentBackup` embedded prefs

当前规则：

- legacy section 只保留 projection/content snapshot 与 bridge 作用；
- V3 当前 schema 的主真相来自 `ENTITY_GRAPH_*` / `WORK_*` section；
- embedded prefs 只在 `isLegacySemanticSchema` 下被识别；
- 但当前代码已经进一步收紧，不再把 `ContentBackup` embedded prefs
  里的 `*_override / reading_status / metadata_source_*`
  重新恢复成 projection prefs 主状态。

对应代码落点：

- [HistoryBackup.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/model/HistoryBackup.kt)
- [FavouriteBackup.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/model/FavouriteBackup.kt)
- [BookmarkBackup.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/model/BookmarkBackup.kt)
- [ContentBackup.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/model/ContentBackup.kt)
- [BackupRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/BackupRepository.kt)

### 8. UI 列表主链已大面积切回 entity-first metadata 解析

以下高频列表链路，已经从逐项 `mangaId -> metadata source` 读取，改成批量 entity-first 解析：

- Home
- Favourites categories
- Favourites list
- Updates
- History
- Feed

对应代码落点：

- [HomeViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/home/ui/HomeViewModel.kt)
- [FavouritesCategoriesViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/categories/FavouritesCategoriesViewModel.kt)
- [FavouritesListViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/ui/list/FavouritesListViewModel.kt)
- [UpdatesViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/tracker/ui/updates/UpdatesViewModel.kt)
- [HistoryListViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/history/ui/HistoryListViewModel.kt)
- [FeedViewModel.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/tracker/ui/feed/FeedViewModel.kt)
- [ContentListMapper.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/list/domain/ContentListMapper.kt)

这一步的意义在于：

- UI 仍可以用 projection 渲染；
- 但 metadata source owner 不再退回 projection 侧。

## 已经明确降级或禁止的旧路径

### 1. entity metadata -> projection blind mirror

当前已经不再允许把 entity metadata selection 作为默认传播机制，批量镜像回所有 local manga prefs。

结论：

- blind mirror 已退出主链；
- projection prefs 只能作为局部 override 或 legacy fallback。

### 2. current schema restore 直接消费 embedded content prefs

当前 restore 已限制为：

- 只有 legacy semantic schema 才会消费 `ContentBackup` embedded prefs；
- 当前 schema 的 authoritative prefs 已回到 `ENTITY_GRAPH_PREFS` / `WORK_*`。

### 3. V3 backup 重复导出 content embedded prefs

当前 V3 export 已经不再把 `EntityPrefsRecord` 重复塞进：

- history section
- favourite section
- bookmark section
- `ContentBackup` embedded prefs

这避免了同一份 work/entity prefs 在多个 backup section 里重复扩散。

同样的判断也适用于 override：

- 当前 authoritative override 已经优先落在 `entity_preferences`
- `ContentBackup` embedded override 字段仅继续承担 legacy import compatibility
- projection prefs 中与 entity 完全一致的 override 现在可以通过 repair 主动剪枝

## 当前仍保留的兼容面

### 1. `PreferencesDao.getLegacyMetadataSourceSelections(...)`

这个入口仍然保留，而且必须继续保留一段时间。

原因：

- 还有 no-entity 场景；
- 还有旧库 / 旧备份恢复后的历史数据；
- 还有 projection-local override 兼容需求。

但它的角色已经非常明确：

- legacy fallback
- 不再是 metadata authority 主入口

### 2. `ContentBackup` embedded prefs 仍可读

当前不是彻底删除 embedded prefs，而是限制为：

- legacy schema import compatibility

这符合当前阶段的 KISS 原则：

- 不在导入链直接断旧；
- 但不让旧结构重新升级成当前 schema 真相。

### 3. no-entity fallback 的 projection prefs 写入

当前仍允许：

- 无 entity 时写 `preferences.metadata_source_*`

这是现实中必须保留的过渡面，因为系统还没有做到所有内容都先 materialize work/entity 再进入主流程。

### 4. tracking suggestion ignore 仍是 projection-local hint

当前 `ignoredTrackingSuggestion*` 仍应明确视为投影级抑制状态，而不是 Work owner state。

原因：

- 它针对的是某个 local projection 的候选建议；
- 不应自动扩散为整个作品对某个 tracking 候选的全局拒绝；
- 因此这条链目前保留在 `preferences` 是合理的，只需要避免误上移。

### 5. `mangaId` 作为大量 API 的物理锚点

当前很多仓库、用例、ViewModel 和 DAO 仍然以 `mangaId` 作为主要参数。

这不是错误，但必须明确它的当前语义通常是：

- projection anchor
- local execution key
- preferred local representative row

而不是：

- 最终 owner id

### 6. 以下入口当前应明确保留 projection-local / 展示兜底语义

这几类入口当前不应为了“形式上的 Work-first 一致”继续硬收：

- `OverrideConfigViewModel`
- `ColorFilterConfigViewModel`
- `LocalChaptersRemoveService`
- `VideoPlayerActivity` 里纯展示用途的 `intent.KEY_TITLE / KEY_URL` fallback

原因分别是：

- `OverrideConfigViewModel` / `ColorFilterConfigViewModel`
  仍承担当前 projection 的本地显示与阅读表现配置；
- `LocalChaptersRemoveService`
  目前还缺少稳定的“仅凭 id 回查本地 content”通道，
  贸然强改只会引入删错本地内容的风险；
- `VideoPlayerActivity` 剩余的 `KEY_TITLE / KEY_URL` 回退，
  当前主要是展示与检索兜底，而不是在行为期重新定义当前作品上下文。

所以后续收口应继续聚焦：

- 旧 seed 参与 owner 判断；
- 旧 seed 参与写库；
- 旧 seed 在行为期重新定义运行时上下文；

而不是追求把所有 `ParcelableContent` / `intent extra` 读取机械清零。

## 当前仍残留的命名与结构债务

### 1. `Entity` / `Manga` 命名仍然误导

当前最明显的认知债务仍然存在：

- `entity` 在业务语义上越来越像 Work；
- `manga` 在业务语义上越来越像 Projection；
- 但代码命名还停留在旧时代。

这会持续带来两个问题：

- 新代码容易误把 `manga` 当 owner；
- 维护者需要靠上下文猜测参数语义。

结论：

- 现在还不适合大规模 rename；
- 但应继续通过注释、helper 命名和文档显式约束语义。

### 2. 读写 helper 仍有 `mangaId == owner` 的历史惯性

虽然高价值 helper 已经补过注释，但仍不能假设全项目都完成了同等语义收口。

尤其是以下区域仍需持续警惕：

- details / reader / player 行为链
- provider / parser / preview 链
- download / local deletion / progress update 链
- 旧 sync DTO / planner / helper 链

其中 details 行为链本轮又补了一刀：

- `DetailsViewModel` 新增 `resolveCurrentLocalMangaId()` / `resolveCurrentLocalContent()`；
- `updateUnifiedReadingStatus()` / `updateUnifiedRating()` / `unregisterScrobbling()` 已不再直接假设初始 `mangaId` 就是当前操作锚点；
- `bindTrackingMatch()` / `ignoreTrackingSuggestion()` / `removeTrackingMatch()` 统一先解析当前本地 projection，再执行 tracking link 与 projection-local hint 写入；
- `removeFromHistory()` 也已改为删除当前 resolved local anchor，而不是盲删初始 intent 的 `mangaId`。

这还不是 details 全面 Work 化，但已经先止住了一个常见污染面：

- 切换 active local source 后；
- 从 tracking metadata 视图回落到本地视图后；
- 或同一详情页里发生 projection rebinding 后；

部分操作不再继续把“页面初始打开时的 mangaId”当成当前 owner 或唯一锚点。

### 3. backup section 语义虽然已收口，但命名仍偏 legacy

即便当前 `WORK_*` / `ENTITY_GRAPH_*` 已经成为主链，很多导出模型仍沿用：

- `HistoryBackup`
- `FavouriteBackup`
- `ContentBackup`

这些命名本身不会造成错误，但会持续隐藏“谁是 authoritative payload”。

### 4. `tracking_site_links` 仍是半真相风险区

虽然总体方向已经明确要把它降级为 cache / audit / suggestion history，但只要代码里还存在：

- 读链偷懒直接取它当绑定线索；
- repair 逻辑把它和 confirmed binding 混看；

它就仍然是潜在污染面。

不过当前工作树已经完成一个关键收口：

- `EntityGraphRepository.findEntityIdsByAnyMangaIds(...)`
  已不再通过 `tracking_site_links` fallback 反推 entity owner；
- owner 解析现在只接受 `local_manga` confirmed binding；
- unresolved projection 会保持 unresolved，而不是让 cache 反向定义主语义。

这一步的意义很直接：

- `tracking_site_links` 仍可服务 details 展示、候选匹配和审计；
- 但它已经不能再通过公共 helper 间接污染 history / favourites / stats / updates / search 等 owner 主链。

另外，`DetailsViewModel` 的 `TrackingItem` 入口也已经做了一层语义收口：

- 有 confirmed entity binding 时，详情页只通过 `applyEntityContext(...)` 解析本地 projection；
- `tracking_site_links` 在这条链路里最多只提供一个 projection anchor 提示；
- 无 entity 的 legacy 场景下，才允许从 link cache 里按 `manual > confidence > updatedAt` 选择本地锚点；
- 不再使用 `links.first()` 这类顺序偶然性去冒充“当前作品的首选本地源”。
- work 详情页对重复 tracking link 的展示选择，也已统一为：
  - requested projection
  - anchor projection
  - manual
  - confidence
  - updatedAt

同一个 ViewModel 里，本轮也顺手把几个高频交互入口统一到了“当前本地投影锚点”语义：

- tracking match confirm / ignore / remove
- tracking metadata item select / bind
- tracking suggestion initial refresh / refresh
- direct scrobbling update
- unified reading status update
- unified rating update
- scrobbling unregister
- remove from history

这里的原则很明确：

- tracking/scrobbling 运行时仍然需要一个 local manga anchor；
- 但这个 anchor 应该来自当前 resolved projection，而不是历史上恰好传进来的某个 `mangaId`。

同一块区域本轮还补了一层 tracking-only fallback：

- 当详情页当前没有本地 projection anchor；
- 但 metadata source 已经选中了某个 tracking item；
- `linkedTrackingItems` 不再直接断流为空，而会先退回到 `tracking_site_links.observeLinks(service, remoteId)`；
- `scrobblingInfo` 也会退回到同 service + `targetId` 的只读 scrobbling 记录；
- 这样 tracking-only / no-local-anchor 场景下，已链接 tracking 项仍能继续显示。

针对这层 fallback，本轮还补了一次边界复核：

- `readingStatus`
- `unifiedRating`
- `canEditUnifiedRating`
- `DetailsHeaderUiState`

当前都仍然只是展示层合成：

- 本地 work/projection owner 状态优先；
- tracking fallback 仅提供只读展示候选值；
- 没有新增把 tracking-only fallback 反向写回 owner 真相的链路。

另外，本轮还补了一层更底层的详情页观察锚点统一：

- 新增 `currentObservedLocalMangaId`
- `history`
- `favouriteCategories`
- `isStatsAvailable`
- `readingRecordSnapshot`
- `readingStatus` 的本地 owner 读取

这些链路不再只盯 `activeMangaIdFlow`，而是统一退回到：

- `activeMangaIdFlow`
- 当前 `mangaDetails.local`
- 当前展示内容里的本地 projection

三者共同解析出的“当前可观察本地锚点”。

这一步的意义是：

- `activeMangaIdFlow` 继续是主交互锚点；
- 但 details 页的展示/观察主链不再把它误当唯一真相；
- 当当前本地 projection 已经通过 base details / local merge / 运行时切换解析出来时，
  history / favourite / stats / reading record / reading status 不会因为 active id 尚未同步而整块断流。

在同一条思路下，details 页几处初始化 / restore / binding 入口也继续做了补收：

- 初始 metadata restore 不再只看页面最初 active id；
- `ensureLocalWorkEntity(...)` 的本地内容解析不再只回退 `activeMangaIdFlow`；
- `bindReadingCandidateToCurrentEntity(...)` 继承 local binding `confidence` 时，也改为读取当前观察锚点。

这意味着：

- 当前本地 projection 已经通过运行时详情状态解析出来时；
- details 页不会再在初始化或后续 binding 时，又退回去拿旧 active id 当唯一上下文。

同一轮里，metadata source 相关交互链也一起切到了同一条统一观察锚点：

- `resolveContextualEntityId()`
- metadata source select / clear
- reading projection select 后的 `entityChapterSourceInfo` 刷新

因此 details 页后续的：

- metadata source 持久化
- tracking metadata 绑定移除
- contextual entity 解析

也不再默认优先旧 `activeMangaIdFlow`，而是跟随当前已经解析出的本地 projection 上下文。

另外，本轮还去掉了 details 页“当前本地锚点”解析里最后一层旧语义兜底：

- `resolveCurrentLocalMangaId()` 不再在无可靠本地上下文时，
  直接退回 `intent.mangaId` 冒充当前本地 projection。

这一步的意义是：

- details 页当前如果确实没有可证明的本地 projection anchor；
- 运行时交互链会显式保持 `null / no-local-anchor` 语义；
- 而不是继续偷偷把启动时传入的旧 `mangaId` 升格为当前 owner/anchor。

同一轮里，details 页剩余的 tracking / scrobbling 观察链也一起切到了这条统一观察锚点：

- linked tracking metadata candidate refresh
- `observedTrackingLinks`
- `observedScrobblingInfo`
- `videoDownloadIndex` 驱动的下载状态刷新

因此当前 details 页里：

- work-aware 本地状态展示
- tracking links 展示
- scrobbling 展示
- 下载状态刷新

已经不再分裂成“部分看 active id、部分看 resolved local anchor”的两套逻辑。

同时，`EntityGraphMigrationWorker` 也补了一层迁移边界：

- entity-only 的 `tracking_site_links`（`mangaId == 0`）不再尝试回灌成 local reading binding；
- migration 现在只会把真实本地 projection id 重新绑定到 entity/work；
- 这避免了历史 tracking cache 在迁移阶段被误提升为伪本地阅读源。

`MigrateUseCase` 的 source migration 写链也已经收了一刀：

- 不再按 work/entity 范围整组删除再重建同 service 的 tracking link；
- 现在只迁移 `oldDetails.id` 这一个旧 projection anchor；
- entity-only link 和同 work 下其他 projection 的 anchor 不会因为一次 source migration 被一起重写。

`MergeFavoriteEntitiesUseCase` 的 tracking 合并证据也已经加了门槛：

- `tracking_site_links` 只有在 `entity-owned` 或 `manual` 时，才允许参与 tracking merge 分组；
- 纯 legacy auto cache 不再作为 work merge 的主证据；
- merge 完成后用于回写默认 tracking metadata selection 的统计，也同步使用同一证据规则。

`BindTrackingToEntitiesUseCase` 的 preview 链也补上了 work-first 短路：

- 当 merge group 已经稳定落在同一个 resolved entity 上，并且该 entity 已有目标 service 的 confirmed tracking binding 时；
- preview 会直接复用该 binding，命中类型记为 `EXISTING_BINDING`；
- 不再重复走外部检索并制造额外 tracking cache 噪音。

同一个 use case 的 bind 写链也已经去重：

- group 内不再对每个 projection 都重复执行一次 `confirmMatch()`；
- 现在最多只确认一次本地 anchor；
- 如果 entity 上已经存在对齐的 tracking projection anchor，则直接跳过重复写入。

另外，`TrackingRepository` 的常用读写入口也开始向 work anchor 收口：

- `getTrackOrNull(...)`
- `saveUpdates(...)`
- `mergeWith(...)`
- `clearUpdates(...)`
- `deleteTrack(...)`
- `updateTracks()`

这些入口当前都会先解析到 `preferred_local_manga_id`，再落到 `tracks.manga_id`。

这还不是最终的表结构 Work 化，但已经先止住了：

- 同一个 work 下多 projection 重复建 track 行；
- history / favourite 侧把任意 projection 直接提升成 tracker 主 anchor；
- update counter 和 update log 在 source 切换后继续分裂。

同时，`observeNewChaptersCount(mangaId)` 这条直接面向 UI 的读链也已经 work-aware：

- 会先解析当前 manga 所属 work 的本地 projection anchors；
- 再把同一 work 下各 anchor 的 `chapters_new` 聚合起来；
- details 等页面的更新角标不再只受当前 raw projection 的单条 `tracks` 行影响。

`HistoryRepository` 的“最近阅读”主链也已经补到 work-first：

- `getLastOrNull()` 会先读取 `work_history`，只在没有 work-owned 历史时回退 legacy `history`；
- `observeLast()` 已改成监听 `work_history + entity_preferences + manga`，不再单纯盯 legacy `history` 首行；
- 当 work 已存在时，最近阅读条目的 representative content 会优先跟随 `preferred_local_manga_id`，而不是继续固定在历史旧 anchor 上。

这一步直接影响以下共享入口：

- `MainViewModel.openLastReader()`
- `HistoryListViewModel.openLastReader()`
- `ReadingResumeEnabledUseCase`
- `ContentPrefetchService.prefetchLast()`

也就是说，source migration 或 preferred source 切换之后，“继续阅读 / 恢复阅读 / 预取最后一本” 这整条主链，现在会更稳定地跟随 work 当前代表投影，而不是继续被旧 projection 历史行拖住。

同一轮里，`HistoryRepository.getList()/observeAll()` 这类“最近历史列表”入口也已经不再单纯返回 legacy `history` 行：

- 当前会把 `work_history` 代表内容和 `no-entity` 的 legacy history 合并；
- 再统一按 `updated_at` 排序；
- 已绑定到 work 的历史项，不会再因为旧 projection 行继续重复出现在 recent list 里；
- 尚未进入 entity/work 体系的历史项，仍然会通过 legacy fallback 保留。

这一步直接影响：

- `HomeViewModel.recentHistoryFlow`
- `AppShortcutManager`
- `RecentListFactory`
- `SuggestionsWorker`
- `ContentPickerViewModel`

也就是首页最近阅读、快捷方式、最近小组件、内容选择器、建议流量口这些复用历史列表的入口，都开始共享同一套 work-first recent list 语义。

围绕 recent list 的运行时失效边界也已经同步补齐：

- `AppShortcutManager` 不再只监听 `TABLE_HISTORY`；
- 当前已经把 `TABLE_WORK_HISTORY` / `TABLE_ENTITY_PREFERENCES` / `TABLE_MANGA` 一并纳入动态快捷方式刷新条件；
- `WidgetUpdater` 也已经把 recent widget 的刷新条件扩展到 `TABLE_WORK_HISTORY` / `TABLE_ENTITY_PREFERENCES`。

这一步解决的是一个容易被忽略的 runtime 漏洞：

- 仓库层 recent list 虽然已经 work-first；
- 但如果 shortcut / widget 的 invalidation 仍只盯 legacy history；
- 那么 preferred source 切换或 work-history 更新后，UI 侧仍会继续显示旧 projection。

现在 recent shortcut 和 recent widget 至少已经在触发层跟上了 work-first recent list 语义。

`HistoryRepository.search()` 和 history quick filter 的标签/来源统计也已经切到同一套 work-first recent set：

- history search 不再直接命中 legacy `HistoryDao.searchBy*`；
- 当前改为基于 `HistoryRepository.getList(0, Int.MAX_VALUE)` 的代表内容集合做本地过滤；
- `getPopularTags()` / `getPopularSources()` 也不再直接从 legacy history 行聚合，而是从 work-first representative contents 统计。

这一步的意义是：

- 已绑定到同一个 work 的多 projection，不会继续在历史搜索或 quick filter 统计里重复放大；
- source/tag filter 的候选集合开始和 recent list 使用同一套 representative content 语义；
- no-entity 的 legacy history 仍然会通过 `getList()` 的 fallback 保留在搜索与筛选入口里。

另外，`TrackingRepository.observeUpdatedContent(...)` / `getTracks(...)` 也已经开始在仓库层做 work-anchor 聚合：

- 同一 work 下来自多个 projection 的 `tracks` 行会先在仓库层合并；
- `Home` / `Updates` / `Feed` 不再必须先接收重复 projection 行再各自二次聚合；
- `newChapters` / `lastCheck` / `lastChapterDate` 会在同一 work 维度上归并。

统计链最近也补了一刀同类问题：

- `StatsCollector` / `StatsRepository` 的 owner 解析已经统一改走 `resolveWorkEntityIdByMangaId(...)`；
- `WorkStatsDao.getDurationStats(...)` 不再按 `entity_id + anchor_manga_id` 分组；
- 当前会优先用 `entity_preferences.preferred_local_manga_id` 作为展示 representative，缺省时才退回历史 anchor。

这一步解决的是一个典型的 projection 泄漏：

- 同一个 work 在切源、切 preferred source 或历史 anchor 漂移后；
- 统计页不应再被拆成多个独立作品条目；
- work 统计的展示聚合现在重新回到了 `entity/work` 维度。

这里还要明确一个边界：

- `UpdatesViewModel` / `HomeViewModel` 保留的 entity 聚合逻辑，已经不主要是在给 tracker 仓库兜底去重；
- 它们现在更偏向 display 层职责：
  - 生成稳定的 work UI group key
  - 选择 preferred representative projection
  - 注入 entity-level metadata selection override
  - 为 entity details 导航保留 `preferredLocalMangaId`

也就是说：

- tracker 仓库层负责减少 projection-centric 原始结果；
- ViewModel 层继续负责 work-aware 展示编排。

### 5. overrides 仍主要挂在 manga prefs

以下 override 的最终 ownership 目标仍然是 Work：

- `title_override`
- `cover_override`
- `content_rating_override`

但当前大部分落点仍然在 manga prefs。

这意味着：

- metadata selection 主链已基本收口；
- 但 override ownership 还没有完成同等级别的上移。

不过当前工作树已经继续向前收了一步：

- [ContentDataRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/parser/ContentDataRepository.kt) 的 `setOverride(...)`
  在存在 entity/work owner 时，已经优先写入 `entity_preferences`；
- 同时会顺手清掉同一 projection 上遗留的
  `preferences.title_override / cover_override / content_rating_override`
  影子值；
- `observeOverridesTrigger(...)` 也已经开始同时监听
  `TABLE_PREFERENCES + TABLE_ENTITY_PREFERENCES`，
  列表层不会再只盯 projection prefs 的旧 owner 信号。

也就是说：

- override ownership 还没有“彻底完成”；
- 但 runtime 主链已经不再把 projection shadow override 当成首选真相。

### 6. source migration 的 prefs 搬运已收紧为 projection-local

`MigrateUseCase` 的 source migration 之前还会整块复制 `preferences`，
这会把以下本应属于 Work 的状态重新带回 projection：

- metadata source
- title / cover / content rating override
- reading status

当前这条写链已经继续收紧：

- 只迁移 projection-local 的 reader / color filter / ignore suggestion 等局部 prefs；
- source migration 时不再复制 Work-owned 的
  `metadata_source_* / *_override / reading_status`；
- 这样切源动作不会再顺带制造一份新的 projection-owned work state 影子。

这一步非常关键，因为 source migration 是最容易反复触发的用户路径之一。

### 7. history 主链当前已恢复可编译、可验证基线

本轮还顺手补齐了 `HistoryRepository` / `HistoryLocalObserver` 里一次未完成重构留下的编译断点：

- `TrackAggregate` 本地模型缺失；
- history 排序里在 comparator 上下文误用了 suspend 查询；
- `HistoryWithContent -> Content` 的局部映射缺失。

当前 `:app:compileDebugKotlin` 已重新通过，
意味着后续 Work-first 收口又回到了可持续验证状态。

`ScrobblerConfigViewModel.bindContent()` 这条手动绑定入口，本轮也补上了同类 owner 语义收口：

- 原先仍按 `info.mangaId` 单点查询旧 scrobbling 行；
- 当前已改为统一走 `findScrobblingByWorkOrManga(...)`；
- 也就是说，手动把线上 tracking 条目绑定到新的本地 projection 时，会优先复用同一 work 下已有的 scrobbling owner，而不是遗漏已经迁到别的 projection anchor 的旧行。

这一步的价值不在于新增能力，而在于减少一类很隐蔽的状态分裂：

- 同一 work 的 scrobbling 记录已经 work-aware；
- 但如果配置页手动绑定仍按 raw projection id 查旧行；
- 就会在 source 切换或重绑后重新造出第二条 anchor 记录。

`VideoPlayerActivity` 这条绕开 ViewModel 的执行链，本轮也做了一次上下文收敛：

- 新增 `currentMangaContent()` helper；
- intro/outro 跳过设置读取；
- danmaku 标题 / cache key / 关键词上下文；
- 基于历史的初始 seek 恢复；
- 保存历史进度；
- reading session 统计；
- jump point 记录；
- chapter switch / auto-next 运行时章节上下文；

这些入口当前都会先取 `mangaContent`，只在缺失时才回退到 `intent` 里的 `ParcelableContent`。

这一步仍然不是播放器全面 Work 化，但至少先解决了一个现实问题：

- 播放器运行时已经可能拥有更新后的当前内容上下文；
- 如果历史恢复、弹幕加载、跳过片头片尾设置仍反复只读 `intent` 初始快照；
- 那么 source 切换、章节补全、或运行期内容更新后，这些链路就会继续跟着旧 projection 快照走。

同一条视频播放链后续又继续补了一层：

- 新增 `currentReaderStateOrIntent()`
- 广告跳过后的当前章节重解析
- `extractChapterInfo()`
- 本地视频 URL 解析前的当前章节状态判断
- `restoreInitialSeekPercentFromHistory()`

这些分支当前都优先使用运行时 `readerState` 与 `currentMediaUrl`，
只在缺失时才回退到 `intent` 快照。

这一步的意义是：

- 播放器已经切换章节、重载流地址、或更新当前媒体 URL 后；
- 标题/章节提取、历史恢复校验、异常回退重播
  不会再优先拿启动时那份 `ReaderState` / `KEY_URL` 误判当前播放上下文。

同类问题在 `VideoChaptersViewModel` 这条视频章节面板子链上，本轮也补了一刀：

- 不再把 `intent.mangaId` 固化成整个 ViewModel 生命周期里的唯一内容 id；
- 新增 `observedLocalMangaId`；
- history 观察和 `videoDownloadIndex` 变更观察，当前都跟随已加载 details 的当前内容 id；
- `doLoad()` 每次加载完成后会刷新这条运行时锚点。

这一步解决的是：

- 视频章节面板在运行时上下文已经切到新内容后；
- 历史高亮和下载状态刷新仍继续盯着页面最初传入的旧 projection id；
- 从而出现章节面板和播放器当前上下文不同步的问题。

`ChaptersPagesSheet` 这条章节页签容器也补了一层边界收紧：

- `resolveContentSource()` 不再回退去读 `activity.intent` 里的 `KEY_MANGA` 快照；
- 当前只接受 `DetailsViewModel` / `VideoChaptersViewModel` 已经解析出的运行时内容 source。

这意味着：

- 章节页签的内容类型判断与 tab 决策；
- 不会再因为 Activity 启动时附带的旧 `ParcelableContent`，
  覆盖掉当前页面已经切换后的真实 source 上下文。

## 下一阶段建议切口

### 1. 优先继续清理 override ownership

比起现在就做大 rename，更值得继续推进的是：

- manual title override
- manual cover override
- content rating override

把它们从 projection prefs 逐步上移到 work/entity 侧。

这是 metadata 治理的下一块高价值区域。

### 2. 继续收口 `mangaId` 语义最重的主流程

建议继续优先检查以下链路：

- `DetailsInteractor`
- `ProgressUpdateUseCase`
- `ReaderViewModel`
- `VideoPlayerActivity`
- `DownloadDialogViewModel`

目标不是重命名参数，而是先确认：

- 这里的 `mangaId` 到底是 owner、anchor、还是 execution context；
- 是否还存在 projection 反向定义 work 状态的写链。

### 3. 对 backup/export 模型增加更明确的 authority 注释

当前代码已经朝正确方向走了，但还可以继续降低维护歧义：

- 哪些 section 是 authoritative
- 哪些 section 只是 projection snapshot
- 哪些字段只是 legacy bridge

这类注释成本低，收益高。

### 4. 暂不推进全仓 rename

当前不建议做：

- `Entity -> Work`
- `Manga -> Projection`

的全仓重命名。

原因很直接：

- 代码还在快速迁移期；
- 大 rename 会制造海量噪音 diff；
- review、blame、冲突成本都会明显上升；
- 逻辑问题容易被重命名噪音淹没。

正确顺序仍然是：

1. 继续迁移 ownership
2. 继续收口读写边界
3. 最后再统一命名

## 对当前状态的一句话结论

当前 Kototoro 已经完成了 Work 化迁移里最关键的一段：

- ownership 已开始脱离 `manga`
- metadata authority 已基本回到 `entity/work`
- backup / restore 当前 schema 已不再以 projection embedded prefs 为真相
- UI 高频读链已大面积 entity-first

但系统仍然处在过渡期：

- `mangaId` 仍是广泛存在的物理锚点；
- override ownership 还没有完全上移；
- `Entity` / `Manga` 命名仍然持续制造认知债务。

因此下一阶段最合理的策略不是推翻重来，而是继续沿当前主链做小步收口，把剩余 projection-centric 写链一段段拿掉。

### 5.30 DetailsViewModel 构造期空锚点观察链止血

这轮继续收口详情页里几条 owner-sensitive 观察流的空值边界：

- `history`
- `favouriteCategories`
- `isStatsAvailable`
- `readingStatus`

此前这些链路都直接写成：

- `currentObservedLocalMangaId.filterNotNull().flatMapLatest { ... }`

这在常规本地详情页上通常没问题，但在以下过渡态里不够稳：

- tracking-only origin，尚未解析到本地 projection；
- entity/work 上下文已建立，但当前展示链还没拿到 representative local anchor；
- 构造期 `syncDisplayedState()` 已开始刷新 UI，而 `currentObservedLocalMangaId` 仍暂时为空。

这轮改成显式的“空锚点 -> 空结果流”：

- `null -> flowOf(null)`
- `null -> flowOf(emptySet())`
- `null -> flowOf(false)`

这样做的目的不是改变 ownership，而是保证：

- 详情页在 work-first 迁移期，即使当前还没有 local projection anchor，
  这些辅助状态流也不会因为上游锚点为空而进入不稳定状态；
- UI 先拿到一个稳定初值，后续等 representative local projection 解析出来后再自然切换。

这一步属于 `Phase 3` 的详情页稳定性收尾，不应夸大为 ownership 迁移完成。

### 5.31 Dynamic Shortcut representative anchor 收口

这轮继续推进 shared runtime entry 的 representative anchor 统一，补到：

- `AppShortcutManager.buildShortcutInfo(manga)`

此前这条链虽然上游 `historyRepository.getList(...)` 已经 work-aware，
但真正生成 shortcut 时仍然直接：

- `findContentById(manga.id, withChapters = true) ?: manga`

这意味着：

- recent/history 列表即使已经按 work 聚合；
- 如果传下来的 `manga.id` 不是当前 work 的 preferred local projection，
  dynamic shortcut 仍可能最终绑定到旧 projection。

现在这里改成：

- `findPreferredLocalContentById(manga.id, withChapters = true)`
- 再 fallback `findContentById(manga.id, withChapters = true)`
- 最后才 fallback 原始 `manga`

并且 shortcut 的：

- icon source
- `storeContent(...)`
- `ShortcutInfoCompat.Builder(...)` id
- `ReaderIntent.Builder(...).mangaId(...)`

都统一跟随这个 representative local projection。

这一步的意义很明确：

- shared runtime entry 不再只因为历史调用方还传着旧 `manga.id`，
  就把 shortcut 重新锚回旧 projection；
- shortcut 这条运行时入口开始和 work-owned `preferred_local_manga_id`
  的语义对齐。

当前这仍然只是 `Phase 3` 的一个入口收口点，不代表所有 shortcut / widget / continue reading 下游都已审计完毕。

### 5.32 VideoChaptersViewModel 跟进 details 空锚点观察链

这轮把 `VideoChaptersViewModel` 里和详情页同类的一条观察链也收掉了：

- `observedLocalMangaId.distinctUntilChanged().flatMapLatest { historyRepository.observeOne(...) }`

此前这里仍然依赖：

- `filterNotNull()`

问题在于视频章节面板当前已经不是静态 `intent.mangaId` 语义：

- `doLoad()` 会在 details 加载后刷新 `observedLocalMangaId`；
- 这意味着构造期 / 切换期 / tracking-style 过渡态里，
  当前本地 projection anchor 是允许暂时为空的。

现在改成：

- `null -> flowOf(null)`
- 非空时再 `historyRepository.observeOne(mangaId)`

这样这条视频章节子链和 `DetailsViewModel` 的防线保持一致：

- 在 representative local projection 还没解析出来之前先给稳定初值；
- 等真实锚点到位后再切换到 work-aware history 观察。

这一步依旧属于详情页稳定性和 projection 锚点统一的收尾，不代表视频详情整链已经完全去除 legacy fallback。

### 5.33 详情预取入口切到 representative local projection

`ContentPrefetchService` 这轮补了一处 shared runtime 入口收口：

- `ACTION_PREFETCH_DETAILS`

此前这条链在拿到 `AppRouter.KEY_ID` 后，仍直接：

- `findContentById(mangaId, withChapters = false)`

这会导致：

- 上游即使已经把 recent / history / details 列表按 Work 聚合；
- 只要下游还传的是旧 projection id，
  预取就仍可能重新打回旧 projection 上下文。

现在这里改成：

- `findPreferredLocalContentById(mangaId, withChapters = false)`
- 再 fallback `findContentById(mangaId, withChapters = false)`
- 最后才 fallback `ParcelableContent seed`

这意味着 details 预取开始和 work-owned representative local projection 对齐，
不再默认把入口传入的 raw `mangaId` 当成唯一执行锚点。

### 5.34 封面恢复链切到 representative local projection

`CoverRestoreInterceptor.restoreContentImpl(...)` 这轮也补了一刀：

- 先解析当前 work 的 preferred / representative local projection；
- 再决定是否执行封面恢复。

此前这里虽然已经避免了 seed-only 恢复，
但仍然是：

- `findContentById(manga.id, ...)`
- `repositoryFactory.create(manga.source)`
- `repo.find(manga)`

这在同一 work 多 projection 已经切换 representative 后，仍可能出现：

- 封面恢复继续盯着旧 projection；
- 进而把旧 source / 旧 cover 状态重新写回当前运行时。

现在改成当前 representative local projection first 之后，
这条链的恢复目标就和当前 work-owned local anchor 语义对齐了。

这一步依旧只是 `Phase 3` shared runtime 入口统一的一部分，
不是 metadata authority 全面完成的证明。

### 5.35 PreviewViewModel 历史观察链改为显式本地锚点

`PreviewViewModel` 这轮也补了一条高频但容易忽略的下游链路：

- footer 里的 `historyRepository.observeOne(...)`

此前这里直接绑定：

- `manga.id`

问题在于 preview 页当前的 `manga` 已经不只是“启动 seed”：

- `resolveIntent(...)` 会优先拿数据库里的当前内容；
- `repo.getDetails(current)` 又可能进一步把展示内容刷新成当前 representative projection 的详情快照。

如果 footer 继续盯着“某一时刻的 `manga.id`”，就会出现：

- 展示内容已经切到当前 representative projection；
- 但阅读进度仍然继续从旧 projection 锚点读 history。

这轮改成：

- 引入 `observedLocalMangaId`
- preview 初始化和 details 刷新后都同步刷新这条本地锚点
- `null -> flowOf(null)`，非空时再 `historyRepository.observeOne(mangaId)`

这样 preview 的 footer 历史观察链就和 details / video chapters 的策略统一了：

- 展示内容和 history anchor 不再各自漂移；
- work-aware history 读取仍然通过当前本地 representative projection 进入。

### 5.36 Home continue reading 子链跟随 representative local projection

首页 continue reading 这轮也收了一刀：

- `resumeCandidateFlow -> historyRepository.observeOne(content.id)`

此前 `resumeCandidateFlow` 的 `content` 虽然大多已经来自 work-aware recent 列表，
但下游继续直接拿：

- `content.id`

仍然默认假设“列表里当前这条 content 的 id 就是永久 history anchor”。

这在 representative projection 切换后并不稳：

- 列表显示内容可能还是旧 projection 快照；
- 但当前 work 的 preferred local projection 已经变化。

这轮改成：

- 先 `findPreferredLocalContentById(content.id, withChapters = false)`
- 找不到再 fallback 原始 `content`
- 再用 representative local projection 的 id 去 `observeOne(...)`

这样首页 continue reading 的进度读取就开始和当前 work-owned preferred local anchor 对齐，
不会因为上游列表项沿用了旧 `content.id` 就继续把进度读回旧 projection。

这一步仍然只是 `Phase 3` 收尾中的一个子链，不代表首页所有卡片与 secondary actions 都已完全 work-first。

### 5.37 VideoPlayerActivity 启动内容解析切到 representative local projection

这轮把 `VideoPlayerActivity.resolveLaunchContent()` 也补到了 representative local projection first：

- 先 `findPreferredLocalContentById(mangaId, withChapters = true)`
- 再 fallback `findContentById(mangaId, withChapters = true)`
- 最后才 fallback `ParcelableContent`

此前这里虽然已经不再只信 `ParcelableContent seed`，
但只要入口传进来的是旧 projection id，播放器仍可能：

- 用旧 projection 作为启动内容；
- 后续章节、历史恢复、下载视频文件定位都继续围绕旧 projection 展开。

改完之后，视频播放器这条启动入口就和 details / preview / home resume /
shortcut / prefetch 的 representative 解析策略对齐了：

- 优先使用当前 work 的 preferred local projection 作为执行锚点；
- 旧 projection id 只作为 fallback 兼容，而不是 owner truth。

### 5.38 FavouritesRepository 同步读取统一到 representative local projection

这轮没有直接改 `ShelfListFactory`，而是收了更上游的一层：

- `FavouritesRepository.getAllContent()`
- `getLastContent(limit)`
- `getContent(categoryId)`
- `search(...)`

这些同步读取此前虽然 favourites 主链已经 work-aware，
但最终对外返回时仍然直接把 DAO 查到的 `Content` 列表原样返回。

这意味着：

- shelf widget
- favourites 同步搜索
- 以及其它直接消费这些同步读取接口的入口

都可能继续拿到旧 projection 作为显示和跳转锚点。

现在这些同步读取统一追加：

- `resolveWorkAnchorContents(...)`

它会按 entity/work 的当前 `preferred_local_manga_id`
把同一 work 的返回内容收敛到 representative local projection。

这一步的意义是：

- 不必在每个 favourites 同步消费方单独写一遍 representative 解析；
- shelf widget 之类的入口会自动开始受益；
- favourites 侧的同步读取终于和此前已经存在的 work-aware ownership 语义对齐。

### 5.39 DetailsViewModel 阅读记录观察链构造期空锚点止血

这轮详情页首屏崩溃里，除了前面已经处理过的 history / favourite / stats /
tracking 链外，还暴露出 `readingRecordSnapshot` 仍然沿用：

- `currentObservedLocalMangaId.filterNotNull().flatMapLatest { observeSnapshot(it) }`

这条写法在语义上看似安全，但对当前迁移期的 details 首屏并不够稳：

- `currentObservedLocalMangaId` 在构造期和 source 切换期都可能暂时为空；
- 上层一旦进入 `flatMapLatest` 重建窗口，底层若还存在旧分支或竞态，就会把“空锚点阶段”暴露给组合链。

这轮改成显式的空锚点分支：

- `null -> flowOf(ReadingRecordSnapshot())`
- `non-null -> readingRecordRepository.observeSnapshot(mangaId)`

这样 details 首屏在 representative local projection 尚未稳定前，
阅读记录子链也会像 history / favourite / stats 一样，始终返回有效 Flow，
不再依赖 `filterNotNull()` 隐式跳过空值。

这一步是 details 构造期稳定性止血，不代表 reading record 全链已经完成 Work/Projection 语义收口。

### 5.40 DetailsScreen / DetailsHeader 失效 file:// 封面 URI 清洗补全

这轮另一条详情页问题来自旧缓存或旧 seed 里残留的本地封面路径：

- `file:///data/user/0/.../cache/...jpg`

当文件已经被清理后，详情页部分 Compose 入口仍会直接把：

- `mangaDetails.coverUrl`
- `content.coverUrl`
- `content.largeCoverUrl`
- `displayModel.coverUrl`

原样送进 `ImageRequest` / `AsyncImage`。

虽然 `ContentDetails` 已经对合成后的主内容做了 `takeIfUsableImageUri()`，
但 details UI 自己还有若干直接取值的支路，所以旧失效 `file://` 仍会打到 Coil，
并持续产生 `ENOENT` 噪音。

这轮补齐到以下入口：

- `DetailsScreen` 的 panorama background 选择与 header 入参
- `DetailsHeader` 的主封面 / fallback 封面
- `DetailsHeader` 的 source card cover

统一改成在送入图片请求前先走 `takeIfUsableImageUri()`。

这样在本地临时缓存文件已经不存在时：

- UI 会退回为空或 fallback；
- 不再反复把失效 `file://` 喂给 Coil。

这一步属于 details UI 侧的封面止血与旧缓存去污染，不代表全项目所有封面消费点都已完成统一清洗。

### 5.40.1 DetailsViewModel scrobbling 观察流空 Flow 防护与残余图片入口补漏

这轮继续收 details 打开阶段的两类运行时问题。

#### a) `observedScrobblingInfo` 对空 Flow 返回值做显式退化

虽然前面已经给 `DetailsInteractor.observeScrobblingInfo(...)` 补过一层：

- 某个 `Scrobbler.observeScrobblingInfo(...)` 返回 `null` 时退化成 `flowOf(null)`

但 `DetailsViewModel.observedScrobblingInfo` 自己这条组合流仍然直接在：

- `activeMangaId != null -> interactor.observeScrobblingInfo(activeMangaId)`

上游结果上做 `flatMapLatest`。

在迁移期构造窗口里，如果上游被异常实现或竞态污染成空 `Flow` 引用，
这里仍可能把空对象直接交给 `flatMapLatest`，从而在详情页打开时触发：

- `Flow.collect(...) on a null object reference`

这轮把这一层也改成显式防护：

- `interactor.observeScrobblingInfo(activeMangaId) ?: flowOf(emptyList())`

这样即使出现异常空返回，details 构造链也只会退化成“当前没有 scrobbling 信息”，
而不是把整个页面拉崩。

#### b) details 残余 `file://` 图片入口继续补齐

前面 `5.40` 已经处理过主 header / source option / panorama 主入口，
但这次运行期日志说明仍有少量 details 子入口会继续把失效本地封面送给 Coil。

这轮继续补到：

- `AnimatedPanoramaBackdrop`
- `DetailsBindingCard`
- `DetailsScreen` 里的 entity relation card cover

统一在 `AsyncImage` / `ImageRequest` 之前先走：

- `takeIfUsableImageUri()`

这样即使旧缓存、旧 seed 或旧 relation 数据里还残留已经删除的
`file:///data/user/0/.../cache/...jpg`，这些 details 子入口也会直接退空，
不再反复打印 `ENOENT`。

这一步依旧只是 details 运行时稳态修复，不改变当前迁移阶段判断，
更不代表全局封面消费链已经完全完成 Work / Projection 语义清洗。

### 5.41 Recent / Shelf widget 点击 payload 显式携带 representative content

这轮继续收了 widget 这条高频共享入口。

此前：

- `RecentListFactory`
- `ShelfListFactory`

虽然上游数据源已经越来越偏向 representative local projection，
但点击 `fillInIntent` 仍然只传：

- `AppRouter.KEY_ID = item.id`

这意味着 widget 点击后进入 reader 仍然主要依赖：

- 下游再次根据 id 解析当前内容；
- 或沿用旧 projection id 做 fallback。

这条链的问题不是“完全打不开”，而是：

- widget 明明已经拿到了当前 representative content；
- 但 payload 没把这个事实显式传下去；
- 于是 representative 选择又退回成下游推断，而不是入口明示。

这轮改成：

- 保留 `KEY_ID`
- 同时补 `KEY_MANGA = ParcelableContent(item)`

这样 `ReaderActivity / ReaderViewModel` 在消费 `ContentIntent` 时，
就能直接拿到当前 widget 列表上实际展示的 representative content，
而不是只拿一个 id 再去猜。

这一步的价值在于：

- recent / shelf widget 的点击入口开始和 shortcut / preview / details /
  video player 等已收口链路一样，显式传递 representative projection；
- 旧 `KEY_ID` 仍保留，兼容现有下游解析；
- 进一步缩小 “入口显示的是当前 representative，但 payload 又退回旧 projection id”
  这种 shared runtime 语义撕裂。

这一步仍然只是 widget payload 收口，不代表所有 widget 展示、排序、封面加载、
以及无密码/锁屏等边缘分支都已完成最终审计。

### 5.42 Tracker 通知入口切到 representative local projection

这轮继续收了 updates / tracker 的一个高频外显入口：新章节通知。

此前 `TrackerNotificationHelper.createNotification(...)` 直接使用：

- `mangaUpdates.manga`

来生成：

- 通知标题
- 通知封面
- 详情页跳转 intent
- shortcut id

问题在于 `mangaUpdates.manga` 更接近 tracker worker 当次检查使用的执行 projection，
它不一定等于当前 work 的 preferred local projection。

这会导致 source switch 之后仍可能出现：

- 更新计数已经按 work 聚合；
- 但通知展示和点击跳转又退回到旧 projection。

这轮改成：

- 先 `findPreferredLocalContentById(manga.id, withChapters = false)`
- 找到则用当前 representative local projection
- 找不到再 fallback 原始 `manga`

然后通知的：

- title
- cover
- details intent
- shortcut id
- nsfw visibility 判定

都统一围绕这个 representative content 生成。

这一步的意义是：

- tracker counter / updates 列表之外，连通知入口也开始对齐当前 work 的 preferred projection；
- 用户从系统通知回到 app 时，不会再因为 worker 当次检查用的是旧 projection，
  就把详情页重新锚到旧 projection；
- Phase 3 关于 “shared runtime / updates / tracker 入口 representative 化” 的证据链又补了一段。

这一步仍然只覆盖 tracker 通知外显入口，不代表 tracker worker、
download strategy、以及所有更新相关后台副作用都已经完全结束 projection 语义审计。

### 5.43 WebDAV restore/upload 的代际拒写闭环已接到现行 auto-sync 主链

前面对 `Phase 4` 的判断里，已经确认了：

- `WebDavAutoRestoreService`
- `BackupWebDavRestoreCoordinator`
- `BackupFlowPolicy`

之间存在 restore 之后的语义版本判断与 upload gate。

这轮继续往前看当前真正还在使用的 auto-sync 运行时主链，确认：

- `DataSyncManager.scheduleUpload()`
- `DataSyncManager.uploadNow()`

两处在真正发起自动上传之前，都会先走：

- `backupFlowPolicy.autoSyncUploadDecision()`

而这个 decision 现在明确会在以下场景拒绝自动写回：

- `isBackupWebDavAutoUploadBlockedByLegacyRestore == true`
- `isWorkMigrationSyncWriteBlocked == true`
- WebDAV 配置不完整
- auto-sync / upload 功能关闭

同时 restore coordinator 的当前语义也已经不是“只记录不参与控制”：

- `BackupWebDavRestoreCoordinator.applySemanticRestoreState(...)`
  会依据 `transportGeneration` 与 `semanticSchemaVersion`
  判断是否为 authoritative work restore；
- 若不是 authoritative work restore，则会设置：
  - `settings.isWorkMigrationSyncWriteBlocked = true`
  - `settings.requiresWorkMigrationNormalization = true`
- 只有 authoritative work restore 或后续 `uploadAndCommit(...)`
  生成新的 V3 authoritative payload 后，才会清掉 write block。

这意味着当前工作树里，restore / upload 的代际隔离已经不再只停留在：

- 手动 restore 结果提示
- auto restore 日志记录

而是已经实际接入现行 WebDAV 自动同步上传主链。

更准确地说，`Phase 4` 现在已经具备：

- restore 后根据 payload 语义版本设置 write block；
- auto-sync 在调度前与真正上传前双重检查 write block；
- 只有新的 authoritative V3 upload commit 才会解除 write block。

仍然不能过度宣称的部分是：

- 这条闭环目前只覆盖现行 backup / restore / WebDAV 主链；
- 不代表仓库里所有历史同步实现、旧源码保留目录、或任何废弃 sync 代码
  都已经做了同等隔离；
- 结合用户此前说明，`kotatsu sync` 相关旧服务源码本就不是当前 authoritative sync 主链，
  因此这里的完成度判断仍应限定在 backup / restore / WebDAV 范围内。

### 5.44 TrackWorker 自动下载仍受执行 projection 章节 id 约束，暂不能粗暴 representative 化

这轮专门复核了 `TrackWorker.processDownload(...)`，
目的是确认 tracker 后台自动下载这条链，是否可以像通知、详情、shortcut 一样，
直接统一切到当前 work 的 representative / preferred local projection。

结论是：**当前不能粗暴切换 `DownloadTask.mangaId` 到 representative local projection。**

原因来自下载链的真实执行约束：

- `CheckNewChaptersUseCase.invoke(track)` 返回的 `MangaUpdates.Success.manga`
  是当次检查使用的完整 details content；
- `mangaUpdates.newChapters` 也是直接从这份 `details.chapters` 中切出来的；
- `TrackWorker.processDownload(...)` 当前会把：
  - `mangaId = mangaUpdates.manga.id`
  - `chaptersIds = mangaUpdates.newChapters.ids()`
  一起塞进 `DownloadTask`；
- `DownloadWorker.getChapters(manga, task)` 会在执行时做严格校验：
  `task.chaptersIds` 中请求的章节 id 必须全部存在于 `task.mangaId`
  对应解析出来的那份 `manga.chapters` 里，否则直接失败。

因此如果此时只把：

- `task.mangaId`

粗暴替换成当前 work 的 representative local projection，
但仍沿用检查时执行 projection 上的：

- `newChapters.ids()`

就会出现章节 id 集合与下载执行内容不匹配的问题，
最终触发 `DownloadWorker` 的 requested chapters not found 校验失败。

这也说明这条后台副作用链和以下入口并不相同：

- details
- shortcut
- widget click payload
- tracker notification

这些入口可以安全做 representative 化，是因为它们主要影响：

- 展示内容
- 跳转锚点
- 恢复入口

而不会改变“章节 id 必须与哪份具体内容明细匹配”这个执行约束。

当前阶段更稳妥的判断应是：

- `TrackWorker.processDownload(...)` 仍是 `Phase 3` / `tracker side effects`
  里的残余切口；
- 不能在没有重新建模“更新检查 projection”和“下载执行 projection”
  关系之前，直接把这条链改成 representative local projection first；
- 如果后续要继续推进，需要先设计：
  - 更新检查结果如何携带稳定 chapter identity；
  - 或下载任务如何区分执行 source anchor 与 work-owned display / jump anchor。

### 5.45 短链解析 / AutoFix / details 绑定后的当前内容选择继续切到 representative local projection

这轮继续收了几条还在直接使用原始 `mangaId -> findContentById(...)`
的共享运行时入口，但都刻意限制在“不改变章节执行语义”的范围内。

#### a) App 内短链解析

`ContentLinkResolver.resolveAppLink(...)` 之前在解析：

- `kototoro://.../manga?id=...`

这类 app 内短链时，会直接：

- `findContentById(mangaId, withChapters = false)`

这会导致：

- 同一个 work 已经切换 preferred local projection；
- 但短链仍优先把详情或后续入口锚回旧 projection。

现在改成：

- 先 `findPreferredLocalContentById(mangaId, withChapters = false)`
- 再 fallback `findContentById(...)`

这样短链入口也开始跟随当前 work 的 representative local projection。

#### b) AutoFix 的种子内容解析

`AutoFixUseCase.invoke(mangaId)` 之前直接把传入的 `mangaId`
解析成种子 content，再以此判断：

- 当前内容是否健康；
- 备选替换源如何比较。

在 work 已经切换 preferred local projection 的情况下，
旧 projection id 会让 auto-fix 继续围绕旧执行锚点做诊断。

现在这条种子解析改成：

- 先 `findPreferredLocalContentById(mangaId, withChapters = true)`
- 再 fallback `findContentById(...)`

这意味着 auto-fix 至少会优先围绕当前 representative local projection
做健康检查与替换决策。

#### c) details 内部 reading candidate bind / tracking auto-link 后的当前内容选择

`DetailsViewModel` 里还有两处内部 helper，
此前在完成绑定或迁移后，会重新通过：

- `findContentById(content.id, ...)`

把当前详情锚回数据库内容。

这在 work 发生 source switch 之后不够稳，因为：

- 迁移/绑定操作虽然已经修改了 entity/work 关系；
- 但重新取当前 content 时仍可能先拿回旧 projection。

这轮改成：

- `bindReadingCandidateToTracking(...)`
  在迁移完成后，先 `findPreferredLocalContentById(content.id, false)`，
  再 fallback `findContentById(...)`
- `autoLinkTrackingServiceIfAuthorized(...)`
  在 link tracking 前解析当前 manga 时，也先走 representative local projection

这一步的价值是：

- details 内部 “绑定成功后重新落到哪份当前内容” 的选择，
  开始和外部 shortcut / widget / notification / prefetch 一致；
- source switch 之后，details 页不容易再次被旧 projection 抢回。

边界仍然要说清楚：

- 这里收的是运行时锚点选择，不是下载执行语义；
- `DownloadWorker` / `TrackWorker.processDownload(...)`
  依旧因为章节 id 与执行 projection 强绑定而未改；
- 因此这轮是 `Phase 3` 收尾推进，不是“tracker/download 全部结束”。

### 5.46 ScrobblerConfig 绑定后的当前内容解析跟随 representative local projection

这轮继续补了一个配置页入口：

- `ScrobblerConfigViewModel.bindContent(...)`

此前这里在用户把 tracking 条目绑定到某个在线内容后，会：

- `storeContent(pickedContent, replaceExisting = false)`
- 然后直接 `findContentById(mangaId, withChapters = true)`

把数据库里的当前内容重新取出来，再继续做：

- scrobbling 记录 rebinding
- 后续 UI 刷新

问题和 details 内部的绑定后重取当前内容是同一类：

- work 已经完成 source switch；
- 但绑定完成后仍可能先拿回旧 projection；
- 于是配置页后续显示和行为锚点继续滞留在旧 projection。

现在这里改成：

- 先 `findPreferredLocalContentById(mangaId, withChapters = true)`
- 再 fallback `findContentById(...)`
- 最后再 fallback `pickedContent`

这样 scrobbler 配置页在绑定完成后，也开始优先围绕当前 work 的 representative local projection
继续后续状态流转。

同时，这轮也再次确认了两类**暂时不该动**的入口：

- `PreviewReadingSourceMigrationUseCase.findExistingProjection(...)`
  这里的目标是“按 source 精确找已有 projection”，不是找当前 representative；
- `DownloadsViewModel` / `DownloadWorker` / `TrackWorker.processDownload(...)`
  仍受下载执行内容与章节 id 精确匹配约束。

这意味着当前剩余的 `Phase 3` 工作，不是机械地把所有 `findContentById(...)`
都替换成 representative first，而是继续区分：

- 展示/跳转/恢复锚点
- 精确 projection 身份
- 下载/章节执行语义

### 5.47 Download/Favorite/Stats/LocalInfo 等 UI 初始化入口继续切到 representative local projection

这轮继续收了一批典型的 UI / sheet 初始化入口。

这些入口的共同模式是：

- 从导航 seed 或 `KEY_ID` 拿到一个旧 `mangaId`
- 然后在 `init` 阶段重新 `findContentById(...)`
- 用查回来的内容初始化当前页面状态

这类逻辑本身不承担：

- 下载执行
- 章节精确匹配
- projection 身份判定

所以适合继续 representative first。

本轮覆盖：

- `DownloadDialogViewModel`
- `FavoriteDialogViewModel`
- `ContentStatsViewModel`
- `LocalInfoViewModel`

它们现在都统一改成：

- 先 `findPreferredLocalContentById(id, ...)`
- 再 fallback `findContentById(id, ...)`
- 最后才 fallback 导航 seed

意义很直接：

- 下载弹窗不会因为入口传来的是旧 projection id，
  就继续围绕旧 projection 初始化内容状态；
- 收藏分类弹窗拿到的当前内容开始和 work 当前 preferred local projection 对齐；
- 统计面板、本地信息面板在 source switch 后，
  不会再优先拿旧 projection 做展示初始化。

同样要明确边界：

- 这里收的是 UI 初始化与展示锚点；
- 不代表 `DownloadsViewModel` 列表项本身、
  `DownloadWorker` 执行链、
  或 `SourceMigrationWorker` 这类需要精确 projection 身份的链路
  已经完成 representative 化；
- 这些后者仍需要继续按语义分桶处理，而不是机械替换。

### 5.48 Scrobbling selector 初始化入口继续切到 representative local projection

这轮继续补了一个典型 selector 初始化入口：

- `ScrobblingSelectorViewModel.resolveCurrentContent()`

此前这里会在启动时：

- 从 `KEY_MANGA` 拿到 seed
- 再按 `initialManga.id` 直接 `findContentById(id, withChapters = true)`

这属于和 details / stats / favorite dialog 同一类问题：

- selector 本身只是要拿“当前内容”初始化界面和已有绑定状态；
- 并不要求保留某个旧 projection 的精确身份；
- source switch 之后继续优先取旧 projection，只会让 selector
  初始化时和当前 work 的 preferred local projection 语义脱节。

现在这里改成：

- 先 `findPreferredLocalContentById(id, withChapters = true)`
- 再 fallback `findContentById(...)`
- 最后再 fallback 导航 seed

这样 scrobbling selector 也开始和其它已收口的 sheet / dialog /
details 内部 helper 一样，围绕当前 representative local projection 初始化。

同时，这轮也再次确认两条**不该机械 representative 化**的链：

- `AttachReadingSourceToEntityUseCase`
  最终返回的是刚刚设置好的 `preferredProjectionId` 对应内容，
  这里需要的是“明确的 preferred projection 结果”，不是再做 representative 推断；
- `SourceMigrationWorker`
  依赖 preview plan 里接受的 `targetContentId`，
  这里承载的是迁移计划明确挑选出的目标 projection 身份，
  不能在执行时再自动漂移到别的 representative。

这进一步说明当前 `Phase 3` 的剩余工作，核心不是搜全仓替换，
而是继续把：

- selector / sheet / dialog / shortcut / widget / notification
  这类“当前内容初始化与展示锚点”

和：

- migration plan target
- explicit preferred projection result
- download / chapter execution source

严格分开。

### 5.49 details 当前本地内容回退链与 video 本地 file 回退链继续切到 representative local projection

这轮继续补了两条容易被忽略的“回退链”。

#### a) DetailsViewModel 当前本地内容解析

`DetailsViewModel.resolveCurrentLocalContent()` 之前在已经拿到：

- `resolveCurrentLocalMangaId()`

之后，会直接：

- `findContentById(localMangaId, withChapters = false)`

虽然这里表面上已经是“本地 id”场景，但在 work/source switch 过程中，
如果当前 entity prefs 已经把 preferred local projection 切走，
这里继续只按 raw id 取内容，仍然可能把 details 后续逻辑拉回旧 projection。

现在这里也改成：

- 先 `findPreferredLocalContentById(localMangaId, false)`
- 再 fallback `findContentById(...)`

这样 details 内部当前本地内容的回退链，也开始和外部 representative 解析策略保持一致。

#### b) VideoPlayerActivity 对本地 file:// seed 的详情补载

`VideoPlayerActivity` 里还有一条本地文件特殊回退：

- 当 `mangaSeed.chapters` 为空且 `mangaSeed.url` 是 `file://`
- 不能再交给在线 source 解析器
- 于是会回到数据库里按 `mangaSeed.id` 查详情

此前这一步直接：

- `findContentById(mangaSeed.id, withChapters = true)`

现在也改成：

- 先 `findPreferredLocalContentById(mangaSeed.id, withChapters = true)`
- 再 fallback `findContentById(...)`

这样即使播放器入口带进来的是旧 projection seed，
本地 file 回退补载时也会优先尝试当前 representative local projection。

边界依然明确：

- 这里收的是 details/video 的内容恢复与回退链；
- 不涉及下载任务、章节 id 精确匹配、或 migration plan target；
- 因此它仍属于 `Phase 3` 的 representative 锚点收尾，不改变 `Phase 3` 尚未结束的判断。

### 5.50 DownloadsViewModel 列表展示/跳转锚点与下载执行锚点开始拆分

这轮继续推进 `Phase 3` 剩余桶里最核心的一条：

- `DownloadsViewModel`

此前下载列表里，同一个 `task.mangaId` 同时承担了三层语义：

1. 下载执行的 projection identity
2. 列表展示的封面与标题
3. 点击项后打开详情页的跳转锚点

这会导致一个典型问题：

- `DownloadWorker` / `DownloadTask` 仍然必须围绕执行 projection 的章节集工作；
- 但下载列表 UI 和详情跳转却会被旧 projection id 一起拖回去，
  无法跟随当前 Work 的 representative local projection。

这轮先不碰 worker/task 执行语义，而是在列表模型上显式拆成两套 content：

- `executionManga`
  - 继续对应 `task.mangaId`
  - 用于 `observeChapters(...)`
  - 用于 `retryWork(...)`
- `displayManga`
  - 通过 `findPreferredLocalContentById(task.mangaId, false)` 解析
  - 用于列表封面、标题与 `openDetails(...)`

具体落点：

- `DownloadItemModel`
  - 新增 `executionManga` / `displayManga`
- `DownloadsViewModel`
  - `toUiModel(...)` 里分别解析 execution / display content
  - `observeChapters(...)` 继续围绕 execution content
  - `retryWork(...)` 继续围绕 execution content 与原 `task.mangaId`
- `DownloadsScreen`
  - 列表封面改为 `displayManga.coverUrl`
  - 标题优先 `displayManga.title`
  - 点击详情页改为 `appRouter.openDetails(displayManga, rootView)`

这样当前下载列表开始具备一个更清晰的分层：

- UI 展示和详情跳转跟随 Work 当前 representative local projection
- 下载执行和章节匹配仍保持原有 projection 精确语义

边界仍然要说清：

- 这一步只解决了 `DownloadsViewModel` / `DownloadsScreen`
  这一层的展示与跳转锚点；
- `DownloadWorker` 本身仍然围绕 `task.mangaId + chaptersIds` 工作；
- `TrackWorker.processDownload(...)` 也仍然受执行 projection 章节 id 约束。

因此这轮属于 `Phase 3` 剩余桶的实质推进，
但还不能据此宣称 download / tracker side effects 已全部结束。

### 5.51 下载链数据面开始承载独立 display anchor

在 `5.50` 之后，下载列表虽然已经可以自己解析 representative local projection，
但下载链的数据面本身仍然只有一份：

- `mangaId`

这意味着：

- `DownloadsViewModel` 只能在展示时自行再推断一次 display content；
- `TrackWorker.processDownload(...)` 即使已经知道当前 Work 的 preferred local projection，
  也没有地方把这个信息传给下载任务；
- worker / progress / outputData / 后续消费方都只能继续从 execution `mangaId`
  反推展示锚点。

这轮继续把 display anchor 正式接入下载链的数据模型：

- `DownloadTask`
  - 新增 `displayMangaId`
- `DownloadState`
  - 新增 `displayMangaId`
  - 并写入 `toWorkData()`
- `DownloadWorker`
  - 启动时把 `task.displayMangaId` 带入初始 `DownloadState`
- `DownloadsViewModel`
  - 优先从 `DownloadState.getDisplayContentId(...)` 读取 display anchor
- `TrackWorker.processDownload(...)`
  - 在保持 `mangaId = execution projection` 不变的前提下，
    为自动下载任务额外写入当前 preferred local projection 作为 `displayMangaId`

这样现在下载链至少有了一个明确的分层事实：

- execution anchor:
  - `task.mangaId`
  - 继续服务章节匹配与下载执行
- display anchor:
  - `task.displayMangaId`
  - 用于把列表、详情跳转等 UI 消费方对齐到当前 Work representative

这一步的价值在于：

- 不再要求每个消费方都自行重做 representative 推断；
- 自动追更下载也终于能把“执行 projection”和“当前展示锚点”同时传下去；
- 后续如果要继续收 `notification` / `worker output consumer`
  这类链路，已经有正式字段可接，不需要再发明旁路协议。

但边界同样非常明确：

- `DownloadWorker` 仍然按 `task.mangaId + chaptersIds` 执行；
- `TrackWorker.processDownload(...)` 只是把 display anchor 传下去，
  还没有重构“执行 projection details 如何和 Work-owned 语义协作”；
- 因此这仍是 `Phase 3` 的继续推进，不是 download side effects 完成信号。

### 5.52 DownloadNotificationFactory 开始显式消费 display anchor

在 `5.51` 之后，下载链数据面虽然已经能携带：

- `displayMangaId`

但通知链自身还在直接消费：

- `state.manga`

这意味着：

- 进行中的通知标题、封面、NSFW 可见性判断
- 下载完成后的详情页跳转

仍然天然偏向 execution projection，而不是当前 Work 的 representative local projection。

这轮继续把 `DownloadNotificationFactory` 接到 display anchor 上：

- `create(state)` 内先解析 `state.displayMangaId`
- 通知标题、封面和可见性优先使用 display content
- 完成态点击行为优先：
  - `displayManga`
  - 再 fallback `localContent.manga`
  - 再 fallback execution `state.manga`

这样下载链现在至少在这些消费方上已经形成一致的 Work-aware 表现：

- 下载列表
- 任务数据面
- 自动追更下载任务下发
- 下载通知标题 / 封面 / 完成态详情跳转

边界仍然不变：

- 这一步解决的是 worker output / notification consumer 的展示与跳转语义；
- `DownloadWorker` 的真正执行输入仍然是 `task.mangaId + chaptersIds`；
- 章节匹配、下载 source、重试时的执行 identity 仍未脱离 projection-first。

因此 `Phase 3` 现在确实更接近收尾了，
但还不能据此宣称下载执行语义本身已经完成 Work-first 改造。

### 5.53 下载任务入队与执行启动开始自动补全 display anchor

在 `5.51` / `5.52` 之后，下载链虽然已经拥有：

- `DownloadTask.displayMangaId`
- `DownloadState.displayMangaId`
- 通知与列表消费者也开始显式消费 display anchor

但仍然有一个真实遗留口：

- 旧任务数据里可能根本没有 `displayMangaId`
- 某些新入口如果暂时没显式传这个字段，也会继续退回 execution projection 视角

这会导致：

- 老任务恢复、重试、通知刷新时
  仍然可能只围绕 `task.mangaId`
  去表达标题、封面与详情跳转锚点；
- 即使当前 Work 已经切换了 representative local projection，
  这些旧任务也不会自动跟上。

这轮继续把“display anchor 自恢复”前移到下载调度与 worker 启动：

- `DownloadWorker.Scheduler.schedule(...)`
  - 在入队前会先标准化任务数据：
    - 若 `task.displayMangaId` 已存在则保留；
    - 否则按 `findPreferredLocalContentById(task.mangaId, false)`
      自动解析 representative local projection；
    - 再 fallback 到 execution `mangaId`
  - 最终写入 WorkRequest 的 input data
    不再保留“缺失 display anchor”的模糊状态；
- `DownloadWorker`
  - 启动时也会再次做同样的 display anchor 解析；
  - 因此即使是旧任务、旧 input data 或历史残留 work spec，
    首次 publish 的 `DownloadState` 也会带上恢复后的 `displayMangaId`

这样当前下载链进一步形成了一个更稳定的事实：

- execution anchor
  - 仍然是 `task.mangaId`
  - 继续负责章节匹配、source 执行与本地落盘
- display anchor
  - 不再只是“入口愿不愿意传”的可选字段
  - 而是会在调度与执行启动时自动补全

这一步的价值主要在两个方面：

1. 旧任务不会因为缺字段而继续把通知/列表/恢复后的 UI 视角拖回旧 projection
2. 新入口即使暂时没有完全补齐 display anchor，
   也能先被调度器标准化到当前 Work representative

边界仍然明确：

- 这一步没有改变 `DownloadWorker.getChapters(manga, task)` 的严格校验语义；
- `task.mangaId + chaptersIds` 仍然是实际执行 identity；
- 因此它继续属于 `Phase 3` 的 runtime anchor 收尾，
  不是下载执行模型已经 Work-native 的证据。

### 5.54 DownloadWorker 内部开始显式区分 execution context 与 display anchor

在 `5.53` 之后，下载链的数据面和任务入队已经开始自动补全：

- `displayMangaId`

但 `DownloadWorker` 内部本身仍然有一个容易继续放大误导的结构问题：

- 方法参数和局部变量里普遍只传一个 `Content`
- 这个对象在语义上同时承载：
  - execution projection
  - 首次状态发布的标题上下文
  - display anchor 的 fallback 参照物

这虽然暂时不一定直接造成功能错误，
但会让后续继续拆执行模型时很难判断：

- 哪些逻辑是“必须围绕 execution projection”
- 哪些逻辑只是“需要带着当前 Work representative 的展示语义”

这轮先不改变章节校验或下载 source 的执行规则，
而是在 `DownloadWorker` 入口层显式引入：

- `DownloadExecutionContext`
  - `executionManga`
  - `displayMangaId`

并把 `doWork()` 的初始解析统一收口到这个上下文：

- 先按 `task.mangaId` 读取 execution manga
- 再解析 / 恢复 display anchor
- 首次 `DownloadState` 发布与启动日志统一从这份结构读取

这一步的作用很具体：

1. `DownloadWorker` 入口不再隐式把“一个 Content 对象”同时当成执行与展示语义
2. 后续如果要继续拆：
   - execution descriptor
   - work/display representative
   - retry / chapter mapping / local restore
   会有明确的承接点，而不是继续在裸 `manga` 变量上打补丁

边界同样明确：

- 这一步仍然没有修改 `getChapters(manga, task)` 的执行规则
- `downloadContentImpl(...)` / `prepareContentImpl(...)`
  仍以 execution projection details 为主
- 因此它只是把 worker 内部语义边界先立清，
  不是下载执行模型已经完成 Work-first 的证据

### 5.57 DownloadWorker 开始显式区分 execution manga 与 execution details

在 `5.54` 之后，`DownloadWorker` 入口已经开始显式携带：

- execution projection
- display anchor

但 worker 真正进入下载正文之后，内部仍然存在另一层混淆：

- `downloadContentImpl(...)` 一开始接收一个 `Content`
- 后续又在同一个局部变量上：
  - 可能把 local content 替换成 remote content
  - 可能再把 remote content 替换成 fetched details
- 最终：
  - 输出目录选择
  - repository 解析
  - 章节校验
  - metadata/cover 落盘
  都混在一个 `manga` / `mangaDetails` 语义团里

这会持续放大两个问题：

1. 后续很难判断“这里依赖的是 execution projection 还是 execution details”
2. display / execution / local-to-remote 语义容易继续在 worker 内部漂移

这轮先不改变下载行为，只把这层术语和解析阶段显式化：

- 新增 `DownloadResolvedContent`
  - `executionManga`
  - `executionDetails`
- 新增 `resolveExecutionContent(...)`
  - 负责把 local execution content 提升回 remote execution manga
  - 再按需要加载 execution details

并把 `downloadContentImpl(...)` 改成显式围绕这两个对象工作：

- `executionManga`
  - 只承载执行 source / repository 所属 projection
- `executionDetails`
  - 承载章节、封面、描述等真正进入下载落盘的 details 快照

这一步的价值不是“行为已经 Work-first”，而是：

1. worker 内部终于不再把 local/remote/details 三层 execution 语义混在一个变量里
2. 后续如果继续推进：
   - chapter mapping
   - local restore
   - completed state / notification / retry
   的 representative 收口时，会有稳定边界可依赖

边界仍然必须写清：

- `task.mangaId + chaptersIds`
  仍然保持 execution projection identity
- `getChapters(executionDetails, task)`
  仍然严格按 execution details 校验
- display anchor
  仍然只负责展示面，不参与本轮下载执行 identity 重写

### 5.58 DownloadWorker 运行中状态快照开始提升到 execution details

在 `5.57` 之后，`DownloadWorker` 内部已经能显式拿到：

- `executionManga`
- `executionDetails`

但运行中对外发布的 `DownloadState.manga` 仍然存在一层滞后：

- worker 初始启动时先按 `task.mangaId` 发布 execution seed；
- 即使后续已经解析出更完整的 `executionDetails`，
  进度通知、失败态、暂停态、WorkManager progress data
  仍可能继续携带较粗的 execution seed。

这会带来一个不必要的语义落差：

- 执行正文已经围绕 execution details 运行；
- 但对外暴露的运行时状态还停在“未提升前的 execution manga”。

这轮继续把状态快照往前收一步：

- `DownloadWorker`
  - 新增 `publishExecutionDetailsState(...)`
  - 在 `resolveExecutionContent(...)` 成功后，
    立即把 `currentState.manga`
    升级为 `executionDetails`

这样做的收益很具体：

1. 运行中的通知标题、失败态 fallback、进度快照
   不再继续背着过粗的 execution seed；
2. `DownloadState`
   至少能和当前真实执行所依赖的 details 快照保持一致；
3. 后续如果继续处理完成态 / local restore / representative 展示边界，
   不需要再先解决“state.manga 究竟是 seed 还是 details”这个歧义。

边界仍然不变：

- 这一步只提升 runtime state snapshot；
- 没有改变 `displayMangaId` 的 representative 角色；
- 也没有改变 `task.mangaId + chaptersIds`
  作为 execution projection identity 的事实。

### 5.59 DownloadWorker 解析出的 execution details 开始回存到 execution projection 记录

在 `5.58` 之后，`DownloadWorker` 运行中对外发布的 `DownloadState.manga`
已经会提升到：

- `executionDetails`

但数据库里的 execution projection 记录仍然有一个明显滞后：

- worker 解析出的 `executionDetails`
  只存在于当前执行内存态；
- 如果下载列表、通知重建、失败恢复、后续 retry
  再回头从本地数据库读取 execution projection，
  仍可能读到旧的 execution seed / 旧 metadata。

这意味着下载链内部会出现一种自我回退：

- 执行时拿到的是新 details；
- 但一旦离开当前内存态，又会回退到旧 execution record。

这轮继续把这层断裂补上：

- `DownloadWorker.downloadContentImpl(...)`
  - 在 `resolveExecutionContent(...)` 成功后，
    先执行
    `mangaDataRepository.storeContent(executionDetails, replaceExisting = true)`
  - 再发布提升后的 runtime state

这样做的收益是：

1. execution projection 这条本地记录开始与当前真实执行所依赖的 details 对齐；
2. 下载列表、通知恢复、失败后重建等回读 execution projection 的入口，
   不会继续轻易退回到旧 execution seed；
3. metadata mirror 污染面进一步缩小：
   下载链至少不再自己制造“运行中是新 details、落库后还是旧 details”的分裂。

边界仍然必须保留：

- 这一步更新的是 execution projection record，
  不是把 metadata authority 重新下放回 projection；
- `displayMangaId`
  的 representative 语义不变；
- `task.mangaId + chaptersIds`
  也仍然保持 execution projection identity。

### 5.60 DownloadWorker 的 DOWNLOAD 分支开始集中 execution content 解析时序

在 `5.59` 之后，`DownloadWorker` 已经能做到：

- 解析出 `executionDetails`
- 回存 execution projection record
- 提升 runtime `DownloadState.manga`

但这些动作原本仍然分散在 `downloadContentImpl(...)` 内部：

- `doWork()` 先创建 execution context
- `DOWNLOAD` 分支先算 `downloadedIds`
- 然后进入 `downloadContentImpl(...)`
- 再在里面二次解析 execution content、二次提升状态、二次决定 details 快照

这带来的问题不是功能错误，而是时序和语义边界仍然发散：

1. `DOWNLOAD` 分支入口看起来还像只处理 execution seed；
2. execution details 的解析/回存/状态提升和章节去重并不在同一层；
3. 后续如果要继续推进 execution identity 与 representative 的真正分离，
   入口层仍然会显得混乱。

这轮继续把 `DOWNLOAD` 分支的关键时序前移并集中：

- `doWork()` 的 `DownloadTaskKind.DOWNLOAD` 分支现在会：
  - 先 `resolveExecutionContent(executionContext.executionManga)`
  - 再回存 `executionDetails`
  - 再提升 runtime state
  - 再基于这份 `executionDetails` 计算 `getDoneChapters(...)`
  - 最后把 `resolvedContent` 显式传给 `downloadContentImpl(...)`

同时：

- `downloadContentImpl(...)`
  不再自己二次解析 `executionContent`
  而是只消费已经准备好的 `DownloadResolvedContent`

这样做的价值是：

1. `DOWNLOAD` 分支入口终于显式拥有完整 execution content 生命周期；
2. execution details 的解析、回存、状态提升、章节去重开始位于同一时序层；
3. `downloadContentImpl(...)` 更接近“纯执行正文”，
   为后续继续剥离 representative / completion / restore 边界减少噪音。

边界依旧没有改变：

- 章节 identity 仍由 `task.mangaId + chaptersIds` 决定；
- `getDoneChapters(...)`
  仍然只是围绕 execution details 的本地去重；
- 这一步只是把 execution content 的准备阶段集中化，
  不是下载执行模型已经 Work-owned。

### 5.61 TrackWorker 自动下载入口开始对齐 execution / display 双锚模型

在前面的下载链收口之后，自动追更下载入口还残留一层更旧的直推语义：

- `TrackWorker.processDownload(...)`
  仍然直接拿 `mangaUpdates.manga`
  作为下载执行 seed；
- 同时只额外补一个 `displayMangaId`
  给通知/列表展示使用。

这会带来一个不必要的入口分裂：

- 下载 worker 内部已经开始显式区分
  execution projection 与 representative display anchor；
- 但 tracker 自动下载入口仍然以更旧的 projection 直推方式入队。

这轮继续把入口收紧到与下载主链一致的模型：

- `TrackWorker`
  - 新增 `AutoDownloadSeed(executionManga, displayManga)`
  - 新增 `resolveAutoDownloadSeed(mangaUpdates)`
  - `processDownload(...)`
    现在会先：
    - 解析 execution seed
    - 解析 representative display manga
    - 用 display manga 判定是否已有本地内容
    - 回存 execution / display 两侧快照
    - 再以 `executionManga + DownloadTask(displayMangaId=...)`
      调度下载

这一步带来的效果很具体：

1. tracker 自动下载入口不再停留在更旧的“projection 直接入队”模式；
2. 自动下载与 `DownloadWorker` 的 execution / display 双锚约定开始一致；
3. 执行种子与展示锚点在入队前就被显式分开，
   减少后续 worker 启动时再做二次修正的噪音。

边界同样必须说清：

- 这一步没有改变
  `mangaUpdates.newChapters.ids()`
  仍然来自 execution projection 章节 identity；
- 也没有把 `DownloadTask.mangaId`
  迁成 Work-owned identity；
- 它只是把自动下载入口对齐到当前下载链已经采用的
  execution/display 双锚模型，
  不是下载执行模型已经完成 Work 化。

### 5.62 DownloadsViewModel 开始把 `displayMangaId` 作为一等展示锚点消费

在 `5.61` 之后，下载列表主面上虽然已经有：

- `executionManga`
- `displayManga`
- `displayMangaId`

但 `DownloadsViewModel` 里仍有两处旧习惯：

1. `toUiModel(...)`
   在有 `displayMangaId` 的情况下，
   仍然优先把它退化成“再用 execution id 猜 representative”；
2. `retryWork(...)`
   在重试时也仍然先按 `task.mangaId`
   去重新推断 display anchor。

这会导致一个语义噪音：

- `displayMangaId`
  已经作为 worker runtime / task data 的显式展示锚点存在；
- 但列表与重试入口仍把它当“可选提示”，
  而不是第一优先级输入。

这轮继续把下载展示层往前收一步：

- `DownloadsViewModel`
  - `toUiModel(...)`
    现在在存在 `displayMangaId` 时，
    会优先：
    - `getDisplayContent(displayMangaId)`
    - fallback `getContent(displayMangaId)`
  - `getDisplayContent(...)`
    补上 raw `findContentById(...)` fallback，
    避免显式 display anchor 解析失败时直接丢失展示侧内容
  - `retryWork(...)`
    现在会优先沿 `task.displayMangaId`
    还原 display anchor，
    再退回 execution id

收益很直接：

1. 下载列表开始把 `displayMangaId`
   当作真正的一等 display anchor；
2. 重试时 display 侧语义不再过度依赖
   “从 execution id 重新猜 representative”；
3. `displayMangaId`
   从“附属字段”更接近升级为下载展示面的稳定约定。

边界依旧保持：

- 这一步仍然只影响下载展示 / 重试层；
- `DownloadTask.mangaId + chaptersIds`
  仍然没有变化；
- execution 章节 identity
  也仍然没有完成 Work-owned 迁移。

### 5.63 DownloadWorker.Scheduler 开始显式按 `task.displayMangaId` 解析 display 侧内容

在 `5.62` 之后，下载展示层已经开始把：

- `displayMangaId`

当作一等 display anchor 使用，但调度层仍残留一个旧习惯：

- `DownloadWorker.Scheduler.schedule(...)`
  在任务里已经存在 `task.displayMangaId`
  的情况下，
  仍然默认先按 `task.mangaId`
  去反推 representative display content。

这会造成一个不必要的入口歧义：

- 上层已经显式给出了 display anchor；
- 调度层却仍然优先相信 execution id 推断。

这轮继续把调度入口对齐到同一约定：

- `DownloadWorker.Scheduler.schedule(...)`
  现在会先：
  - 按 `task.displayMangaId`
    尝试 `findPreferredLocalContentById(...)`
  - fallback 同 id 的 `findContentById(...)`
  - 再退回 `task.mangaId` 的 representative 推断
- display 侧回存也改为直接基于
  已解析出的 `displayManga`
  处理，而不是再二次按 id 回查

收益是：

1. 下载调度层与 tracker 自动下载入口、下载列表
   对 `displayMangaId`
   的处理开始一致；
2. 一旦上游已经显式传入 display anchor，
   调度入口不再优先回到 execution id 猜 representative；
3. display 侧内容回存路径更直接，
   少一层按 id 二次查找的噪音。

边界依旧不变：

- 这一步仍然只影响 task 规范化 / display side caching；
- `DownloadTask.mangaId`
  仍然是 execution projection identity；
- `chaptersIds`
  也仍然是 execution projection 章节集合。

### 5.64 display 锚点解析开始下沉为 `ContentDataRepository.findDisplayContentById(...)`

在 `5.63` 之后，下载链上对于 display 侧内容的读取约定已经越来越清晰：

- 先 representative / preferred local
- 再 raw projection fallback

但这一约定仍然散落在多处手写：

- `DownloadNotificationFactory`
- `DownloadsViewModel`
- `DownloadDialogViewModel`
- `TrackWorker`
- `DownloadWorker`

这会带来一个典型的维护风险：

- display 锚点读取语义已经存在；
- 但没有被 repository 层收口成稳定 helper；
- 于是每个调用点都可能各写各的 fallback。

这轮继续把这层语义下沉：

- `ContentDataRepository`
  - 新增 `findDisplayContentById(mangaId, withChapters)`
  - 语义固定为：
    - `findPreferredLocalContentById(...)`
    - fallback `findContentById(...)`

并且已经接到：

- `DownloadNotificationFactory.resolveDisplayContent(...)`
- `DownloadsViewModel.getDisplayContent(...)`
- `DownloadDialogViewModel`
- `TrackWorker.resolveAutoDownloadSeed(...)`
- `DownloadWorker.resolveExecutionContext(...)`
- `DownloadWorker.Scheduler.schedule(...)`

收益是：

1. display 侧内容解析终于有了 repository 级统一入口；
2. 下载链与 tracker 自动下载入口对 display anchor 的读取语义开始固定；
3. 后续继续做 execution/display 边界审计时，
   可以直接以 `findDisplayContentById(...)`
   作为“展示侧读取”基准，而不是继续追多处手写 fallback。

边界同样保持不变：

- 这一步只统一了 display 侧读取 helper；
- 没有改变 execution identity；
- `DownloadTask.mangaId + chaptersIds`
  仍然没有 Work-owned 化。

### 5.65 下载链开始把 execution identity 从隐含约定提升为显式命名

在前面的收口之后，下载链仍保留一个明显的可维护性问题：

- `DownloadTask.mangaId`
- `DownloadState.getContentId(...)`

这两个名字都太泛，
但它们在当前真实语义下实际承载的是：

- execution projection identity

这会持续制造认知噪音：

- 阅读代码时很容易把它误会成“当前作品 id”
  或“统一内容 id”；
- 进而在后续改造里继续把 execution 语义和 display / work 语义混在一起。

这轮先不做结构迁移，
只把这层语义提升为显式命名：

- `DownloadTask`
  - 新增只读别名 `executionMangaId`
- `DownloadState`
  - 新增只读 helper `getExecutionContentId(...)`

并且已经切到下载主链关键位置：

- `DownloadWorker.doWork()`
- `DownloadWorker.Scheduler.schedule(...)`
- `DownloadsViewModel.toUiModel(...)`
- `DownloadsViewModel.retryWork(...)`

收益很具体：

1. execution projection identity 不再只靠读者脑补；
2. 后续继续推进 download execution model 时，
   可以在不立刻改存储字段名的前提下，
   先把代码层语义稳定下来；
3. 这能减少把 execution id 误读成 work/display id 的机会。

边界需要再次强调：

- 这一步没有改变任何持久化字段名；
- `mangaId`
  仍然存在，兼容性不变；
- 也没有把下载执行 identity 迁成 Work-owned，
  只是把它明确标注为 execution projection id。

### 5.66 下载链开始把 execution chapter set 从隐含约定提升为显式命名

在 `5.65` 之后，execution projection id 的语义已经开始显式化，
但还有另一层同样重要的 execution 语义仍然被模糊命名掩盖：

- `DownloadTask.chaptersIds`

当前真实语义并不是：

- “作品级章节集合”

而是：

- execution projection chapter id set

如果继续保持这个泛名，
后续改造里仍然容易把它误读成：

- display 章节集合
- work 章节集合
- 可跨 projection 通用的章节 identity

这轮继续做同样的显式化处理，
仍然不碰持久化兼容：

- `DownloadTask`
  - 新增只读别名 `executionChapterIds`

并切到下载链关键位置：

- `DownloadWorker`
  - task 章节日志
  - `getChapters(...)`
  - 调度重建 task
- `DownloadsViewModel`
  - 章节观察
  - retry task 重建

收益是：

1. execution chapter identity 不再继续藏在泛化字段名里；
2. 下载链内部更难再把它误读成 work/display 章节集合；
3. 后续如果真的要继续推进章节 identity 迁移，
   代码层已经先把当前真实语义钉住了。

边界依旧不变：

- 持久化字段仍然叫 `chaptersIds`；
- 下载行为没有变化；
- 这一步不是章节 identity 的 Work-owned 迁移，
  只是把它明确标成 execution projection chapter set。

### 5.67 下载任务创建侧开始收口到 `DownloadTask.createExecutionTask(...)`

在 `5.65` / `5.66` 之后，
下载链内部虽然已经有了：

- `executionMangaId`
- `executionChapterIds`

但任务创建点仍然容易退回旧写法：

- `DownloadTask(mangaId = ..., chaptersIds = ...)`

这会让创建侧继续散发旧语义：

- 看起来像在创建“普通漫画下载任务”；
- 实际上却仍然是在创建
  execution projection task。

这轮继续把“创建任务”这一步也收口到显式 execution 语义：

- `DownloadTask`
  - 新增工厂：
    `createExecutionTask(...)`

并切到核心入口：

- `DownloadDialogViewModel`
- `ChaptersPagesViewModel`
- `DownloadsViewModel.retryWork(...)`
- `TrackWorker.processDownload(...)`

收益很直接：

1. 新任务创建点不再继续传播模糊的 `mangaId/chaptersIds` 语义；
2. 下载执行 task 的创建入口开始统一；
3. 后续继续推进 execution / display / work 边界时，
   创建侧已经先站到了正确语义上。

边界同样不变：

- 这只是创建侧工厂化；
- 持久化字段、协议字段都没有变；
- 也没有把下载执行 identity 迁成 Work-owned。

## 剩余 `findContentById(...)` 调用分桶审计

为了避免最后阶段继续凭感觉推进，这里把当前工作树中仍保留的
`findContentById(...)` 调用按语义分成三类。

### A. 合理保留的 fallback / helper 调用

这些点当前已经是：

- `findPreferredLocalContentById(...)`
- 然后 fallback `findContentById(...)`

或者本身就是 repository helper 的底层实现，不构成新的旧语义主链：

- `VideoPlayerActivity.resolveLaunchContent()`
- `VideoPlayerActivity` 本地 `file://` seed 详情补载
- `AutoFixUseCase`
- `DownloadDialogViewModel`
- `LocalInfoViewModel`
- `CoverRestoreInterceptor`
- `ContentLinkResolver`
- `ContentPrefetchService`
- `DetailsViewModel` 若干内部 helper
- `ContentStatsViewModel`
- `AppShortcutManager`
- `ScrobblerConfigViewModel`
- `ScrobblingSelectorViewModel`
- `FavoriteDialogViewModel`
- `ContentDataRepository.findPreferredLocalContentById(...)`
- `ContentDataRepository.resolveIntent(...)` 内部原始 fallback

这些调用现在更多承担：

- representative first 之后的 fallback；
- 或 helper 内部对 raw projection id 的最终兜底。

它们不应再被误判为“尚未收口”的主证据。

### B. 明确要求精确 projection 身份的链路

这些点当前不该机械改成 representative first，因为它们承载的就是：

- 明确的目标 projection；
- source 级精确匹配；
- 或迁移计划显式选中的 target identity。

当前已确认属于这一类的有：

- `PreviewReadingSourceMigrationUseCase.findExistingProjection(...)`
  - 目标是按 source 找已有 projection
- `AttachReadingSourceToEntityUseCase`
  - 返回刚设置好的 `preferredProjectionId` 对应内容
- `SourceMigrationWorker`
  - 消费 preview plan 接受的 `targetContentId`

对这些链路，如果后续要变更，也必须先证明“精确 projection 身份”
已经不再是业务要求；否则直接 representative 化会损坏语义。

### C. 仍未完成的执行链 / 副作用链

这是当前真正剩下、且不能靠简单替换收口的桶。

1. `DownloadWorker`
   - `task.mangaId` 与 `task.chaptersIds` 精确绑定
   - 运行时必须围绕下载执行 source / details 内容工作

2. `TrackWorker.processDownload(...)`
   - `MangaUpdates.Success.manga` 与 `newChapters.ids()`
     来自同一份执行 projection details
   - 不能粗暴替换 `DownloadTask.mangaId`

3. `DownloadsViewModel`
   - 列表展示与详情跳转锚点已从执行 `mangaId` 拆出
   - 下载链数据面也已开始承载独立 `displayMangaId`
   - 通知链也已开始显式消费 display anchor
   - 章节映射已开始同时观察 representative local projection 与 execution projection
   - `retryWork()` 也会重新解析 representative display anchor
   - 但下载执行 identity 本身仍围绕执行 projection

### 5.55 TrackWorker 自动下载“本地已存在”判断开始优先 representative local projection

在 `5.54` 之后，`TrackWorker.processDownload(...)` 虽然已经能把：

- execution projection 继续作为下载执行身份；
- representative local projection 作为 `displayMangaId`

一起传给下载链，但它在进入自动下载前的这层判断仍然过于旧语义：

- 只检查 `localRepository.findSavedContent(mangaUpdates.manga)`；
- 也就是只看 execution projection 是否已经存在本地保存内容；
- 如果同一 Work 的 representative local projection 已经存在本地内容，
  但 execution projection 本身没有本地副本，
  自动下载仍会被误判为“不属于已下载作品”。

这轮继续把这层 side effect gate 收窄到 work-aware representative 语义：

- `TrackWorker.processDownload(...)`
  - 先用 `contentDataRepository.findPreferredLocalContentById(...)`
    解析 representative local projection；
  - 再优先对 representative local projection 做
    `localRepository.findSavedContent(...)`；
  - 只有 representative 缺失时，才回退 execution projection。

这一步的边界仍然明确：

- `DownloadTask.mangaId`
  依然保持 execution projection；
- `mangaUpdates.newChapters.ids()`
  依然绑定 execution projection details；
- 改动的只是
  “这条 Work 是否已经具备本地内容，所以允许自动下载” 的判定语义。

### 5.56 DownloadsViewModel.retryWork 开始重算 representative display anchor

此前 `Scheduler.schedule(...)` 虽然已经会在入队时自动补全：

- `displayMangaId`

但 `DownloadsViewModel.retryWork(...)` 仍然只是机械复用旧任务快照里的：

- `task.displayMangaId`

这会保留一层不必要的历史锚点漂移：

- 如果 representative local projection 这期间已经切换；
- 或旧任务本身来自更早期、仍带着过时的 display anchor；
- retry 语义虽然最终会被 `Scheduler.schedule(...)` 再标准化一次，
  但 ViewModel 侧仍在传递旧锚点。

这轮继续把 retry 入口也显式收口：

- `DownloadsViewModel.retryWork(...)`
  - 重试时先重新解析 `getDisplayContent(task.mangaId)`；
  - 优先使用新的 representative local projection id；
  - 再回退旧 `task.displayMangaId` 和 execution `manga.id`。

这一步同样没有改变下载执行身份：

- retry 的 `mangaId`
  仍然保持 execution projection；
- 只是把 display anchor 的解析前移到 ViewModel retry 入口，
  避免继续把过时 representative 快照向下游传递。

这三条才是当前 `Phase 3` 真正还没结束的核心剩余项。

换句话说，到了现在，剩余工作已经不再是：

- “全仓库还有没有 `findContentById(...)`”

而是已经收缩成：

- “下载/更新后台副作用链，何时以及如何从 projection 执行语义
  过渡到 Work-owned identity + execution anchor 分离”

只要这个问题还没有被重新建模并落地，就不能宣称“所有计划结束”。

### 5.68 details 构造期空 Flow / 空 StateFlow 止血继续收口，失效本地封面路径直接降级

这轮继续处理了一批已经开始影响真实打开详情页稳定性的细节问题。

日志里暴露出的两类症状分别是：

1. `DetailsViewModel` 初始化阶段仍有链路会遇到
   `Flow.collect(...) on a null object reference`
   或 `StateFlow.getValue() on a null object reference`；
2. 详情页某些封面来源仍会把已经失效的本地 `file://...jpg`
   路径继续传给 Coil，最终在 `RealImageLoader` 里报 `ENOENT`。

这轮继续把止血点压到详情主链内部：

- `DetailsViewModel.observeTrackingLinksByWork(...)`
  - 对 `observeLinksByWorkOrMangaCandidates(...)`
    再包一层 `flowOrFallback(emptyList())`
  - 不再信任底层一定返回非空 `Flow`
- `TrackingItem` 初始化路径里那条旧的
  `db.getTrackingSiteDao().observeLinks(...).collect`
  - 也统一改成 `flowOrFallback(emptyList())`
- `refreshTranslateActionVisibility(...)`
  - 改成安全读取 `mangaDetails`
  - 避免构造期直接读同步 `.value`
- `normalizedImageUrl()`
  - 不再只是 `isNotBlank()`
  - 对 `file://` 路径会额外检查本地文件是否存在；
  - 不存在则直接返回 `null`
    让 UI 回退到其它封面来源或占位，而不是继续把失效路径喂给图片加载器

这一步的边界需要写清楚：

- 它是 details 初始化稳定性的继续止血；
- 不是“详情页所有时序问题已经彻底解决”；
- 当前只有代码与编译证据，没有真机复验闭环。

### 5.69 DownloadWorker.Scheduler 的任务标准化继续统一到 `createExecutionTask(...)`

在 `5.67` 之后，下载任务创建侧大部分入口已经开始统一走：

- `DownloadTask.createExecutionTask(...)`

但 `DownloadWorker.Scheduler.schedule(...)` 内部在把任务送入 WorkManager 前，
仍然保留了一处手写的：

- `DownloadTask(...)`

这不会改变底层执行身份仍是 projection 的事实，
但会继续保留一层“execution/display 双锚语义靠调用方自己拼字段”的噪音。

这轮继续把这处也统一掉：

- `DownloadWorker.Scheduler.schedule(...)`
  - 改为调用 `DownloadTask.createExecutionTask(...)`
  - 显式传入：
    - `executionMangaId`
    - `displayMangaId`
    - `executionChapterIds`
    - 其余下载参数

这一步的意义很具体：

1. 下载调度层不再额外保留一套手写任务组装语义；
2. execution/display 约定继续收口到统一工厂；
3. 后续如果继续演进 `DownloadTask` 的 execution model，
   需要改动的创建入口更集中。

同样要明确边界：

- 这只是“下载任务创建语义继续统一”；
- 不是“下载执行 identity 已经 Work-owned”；
- `task.mangaId` / `task.chaptersIds`
  目前底层仍然是 execution projection id / chapter ids。

### 5.70 details 运行复验已尝试接线，但当前环境仍缺 Android 运行时证据

在 `5.68` 之后，这轮没有停在“编译通过即视为完成”，
而是继续尝试把 details 真正跑起来验证。

实际检查结果是：

- `adb devices` 当前为空；
- 本机 PATH 中没有 `emulator`；
- 本机 PATH 中也没有 `avdmanager`；
- 技能目录里现成的 `emu_health_check.sh`
  在当前仓库环境中也不存在，仅有 `ps1` 版本与依赖 `emulator` 的 Python 脚本。

这意味着截至当前工作树，我们能证明的是：

- details 空 Flow / 空 StateFlow 止血代码已经落地；
- 失效本地 `file://` 封面路径过滤已经落地；
- Kotlin 编译链通过。

但我们还**不能**证明：

- 真实设备或模拟器上打开详情页时已经不再崩溃；
- Coil 对失效本地封面的回退行为已经在运行态符合预期。

因此 details 这一桶当前状态应当写成：

- 代码级止血：已完成；
- 构建验证：已完成；
- 运行验证：缺环境证据，仍待补。

### 5.71 tracks 锚点开始拒绝写入不存在的 local projection

本轮又暴露出一类更贴近 Work 化主线的数据一致性问题：

- feed / updates 主链触发 `TrackingRepository.gc()` 时，
  `syncTrackAnchors()` 会尝试把当前 Work 解析出的 preferred projection
  写回 `tracks.manga_id`；
- 但 `tracks.manga_id` 仍然对 `manga.manga_id` 持有外键；
- 当 `entity_preferences.preferred_local_manga_id`
  或 local binding 里残留了已经被清理的 projection id 时，
  Room `upsert(tracks)` 会直接触发
  `SQLiteConstraintException: FOREIGN KEY constraint failed`。

这一轮的收口不是回退 Work-first 方向，
而是给 projection-anchor 存储补上最基本的物理存在性约束：

- `resolveTrackAnchorMangaId(...)`
  现在不会再盲信 entity preferred projection；
- 只有当前 `manga` 表里仍然存在的 local projection id
  才允许被选为 track anchor；
- preferred id 失效时，会继续回退到仍存在的 local binding；
- `syncTrackAnchors()` 在实际写入 `tracks` 前，
  也会再次过滤一遍不存在于 `manga` 表中的候选 id。

这一步的意义很明确：

1. Work-owned favourite / history / preferred projection
   继续保留为锚点解析真相；
2. 但 projection-anchor 的物理存储层
   不再允许被失效 projection id 直接污染；
3. `tracks` 仍未完成 Work-owned 存储迁移的前提下，
   feed / updates 至少不会因为过期 projection anchor
   直接炸在外键约束上。

边界同样要明确：

- 这不是 `tracks` 存储模型的最终统一；
- 只是把“可写入的 projection anchor”
  收紧为“数据库中真实存在的 projection”；
- 因此它仍然属于 **Phase 3 的继续推进项**，不是完成项。

### 5.72 主界面 suggestion / Home / Downloads 的默认详情入口继续从 projection-first 收口

除了前面已经推进过的：

- 首页 resume
- 多源搜索结果页
- 源内列表内部详情中转
- 通用 Compose 列表默认详情跳转

这轮又继续收了几条仍然保留旧 fallback 语义的高频入口：

- `MainActivity`
  - `onContentSuggestionClick`
  - `onLocalEntitySuggestionClick` 的 entity 缺失 fallback
- `HomeFragment`
  - `viewModel.onOpenContent` 的 entity 缺失 fallback
- `DownloadsScreen`
  - 下载列表点击 `displayManga` 打开详情

这些入口之前的共同问题是：

- 一旦上游事件没有直接携带 `entityId`，
  就会立刻退回 `router.openDetails(content)`；
- 这会让同一个 Work 在主界面 suggestion / 首页卡片 /
  下载列表这些高频入口里继续表现为 projection-first；
- 与已经 Work-first 化的 `AppNavGraph.navigateToDetailsWithContent(...)`
  和列表详情中转形成语义分裂。

本轮的处理方式保持克制，没有去重写底层 `AppRouter`，
而是统一复用：

- `MainActivity.resolveDetailsOriginForContent(...)`

也就是：

- 当前处于 `MainActivity` 场景时，
  先解析 `DetailsOrigin.EntityGraph`
  或 `DetailsOrigin.LocalMangaContent`；
- 命中 entity 就直接打开 entity details；
- 只有无法解析 entity 时才回退旧的 local content details。

这一步的意义：

1. 主界面高频入口开始继续向同一条 Work-first 详情中转收敛；
2. 不需要扩散到底层 router 语义重写，就能减少入口分裂；
3. 下载列表里的 `displayManga`
   现在也不再天然等价于“直接打开 projection details”。

边界仍需明确：

- 这不是“所有详情入口都统一完成”；
- 旧 Fragment / Activity、统计页、预览页、
  以及若干非主界面调用方仍有残余直接 `openDetails(content)`；
- 因此它仍然属于 **Phase 3 的继续推进项**。

### 5.73 预览页与统计弹层也开始在主容器场景下优先解析 entity details

在 `5.72` 之后，还剩两类“看上去不是主链，但用户实际经常点开”的轻量入口：

- `PreviewFragment` 的 `button_open`
- `ContentStatsSheet` 的 `onOpenDetails`

这两处此前的问题相同：

- 它们都已经运行在主应用内部；
- 但点击“打开详情”时仍然直接
  `router.openDetails(manga)`；
- 这意味着用户从预览卡片或统计弹层进入详情时，
  仍可能绕过已经铺好的 Work-first 详情中转。

这轮没有引入新的全局 router 语义，
仍然只做一件事：

- 如果当前宿主是 `MainActivity`，
  就先复用 `resolveDetailsOriginForContent(...)`
  解析 entity / preferred local projection；
- 只有拿不到 `MainActivity`
  或解析不到 entity 时，才回退旧 `openDetails(manga)`。

这一步的意义：

1. 预览页、统计弹层这类“辅助详情入口”
   不再天然是 projection-first；
2. 主容器内的详情打开语义继续收口到同一条解析链；
3. 不需要为独立 Activity / Sheet
   引入额外的跨层依赖。

边界：

- 这仍然只覆盖“主容器场景下可轻量复用解析器”的入口；
- `AlternativesActivity/Sheet`、部分旧 Fragment
  和纯 `AppRouter` 调用方依然还没统一；
- 因此仍是 **Phase 3 的继续推进项**。

### 5.74 Bookmarks 与旧 ContentListFragment 详情入口继续脱离直接 projection details

除了主界面和辅助详情入口，仍然有两类旧列表栈会频繁把用户重新带回
`openDetails(content)`：

- `AppBookmarksRoute`
  - `ListHeader.payload as Content` 的点击打开
- `ContentListFragment`
  - Compose 列表项点击后的默认详情跳转

这两处的问题在于：

- 它们都属于应用内部高频列表入口；
- 但之前仍然把“点开条目”
  直接等价为“打开当前 projection details”；
- 会和已经 Work-first 化的主链形成割裂。

这轮同样没有扩散到底层 router，
而是继续复用：

- `MainActivity.resolveDetailsOriginForContent(...)`

行为变成：

- 若当前宿主在 `MainActivity` 容器内，
  就先解析 entity / preferred local projection；
- 命中 entity 时直接打开 entity details；
- 否则保持旧 `openDetails(...)` fallback。

这一步的意义：

1. Bookmarks 与旧列表栈不再天然绕回 projection-first；
2. 老 UI 容器里的默认详情入口继续向同一条 Work-first 解析链靠拢；
3. “新 Compose 列表已收口、旧 Fragment 列表未收口”的裂缝进一步缩小。

边界：

- 这仍然没有覆盖所有独立 Activity / Sheet；
- 一些完全脱离 `MainActivity` 容器的老页面
  仍保留直接 `openDetails(content)`；
- 因此仍属于 **Phase 3 的继续推进项**。

### 5.75 details 页内部的阅读源迁移不再重新打开 projection details

除了外部入口，details 自己内部此前也保留了一处很容易反向污染语义的旧路径：

- `ReadingSourceSheet.onMigrateResult`

旧行为是：

1. 先调用 `bindReadingCandidateToTracking(candidate)`
2. 然后在完成回调里再执行 `appRouter.openDetails(candidate)`

这和当前 `DetailsViewModel.bindReadingCandidateToTracking(...)`
的真实行为是冲突的，因为后者已经会：

- 绑定 candidate 到当前 entity
- 持久化 preferred local source
- 切换 `activeMangaIdFlow`
- 刷新当前 details 内容

也就是说，后面的 `openDetails(candidate)` 其实是多余且错误的：

- 它会把用户重新带回一个 projection-first 的详情打开链；
- 会破坏“当前 Work 上下文内切换阅读源”的语义；
- 等于把 details 内部的 source switch
  又重新降级成“开一个新的 local details”。

这轮已经把这一层多余跳转去掉：

- 绑定完成后只关闭阅读源弹层；
- 详情页继续停留在当前 entity/work 上下文内刷新；
- 不再重新 `openDetails(candidate)`。

这一步的意义：

1. details 内部的 reading source migration
   终于和 Work-first 详情模型一致；
2. 用户切换/迁移阅读源时，
   不再从 entity 详情被踢回 projection 详情；
3. 这是从“入口 Work-first”
   继续推进到“详情内部行为也 Work-first”的一个关键收口点。

边界：

### 5.76 `AppRouter` 已补上通用 Work-aware 详情入口，独立页面开始纳入统一中转

在前几轮收口里，一个明确的剩余问题是：

- `MainActivity` 容器内的大多数高频入口，
  已经可以复用 `resolveDetailsOriginForContent(...)`；
- 但脱离主容器的独立 `Activity` / `Sheet`
  仍然只能直接 `router.openDetails(content)`；
- 这使得 `AlternativesActivity`、`AlternativesSheet`、
  `TrackerDebugActivity` 这类老页面继续保留 projection-first 详情入口。

本轮不再继续把解析逻辑散落在调用方，而是直接在导航层补上公共能力：

- `AppRouterEntryPoint`
  现在注入：
  - `EntityGraphRepository`
  - `ContentDataRepository`
- `AppRouter`
  新增：
  - `openResolvedDetails(manga, anchor, sharedElementKey)`
  - 内部统一执行
    “先查 entity，再取 preferred local projection，
    最后决定打开 `EntityGraph` 还是 fallback `LocalMangaContent`”

随后第一批独立页面已经切到这条公共链：

- `AlternativesActivity`
- `AlternativesSheet`
- `TrackerDebugActivity`

这一步的意义比单点修页面更大：

1. Work-first 详情解析第一次从“主容器局部复用”
   上升为“router 级公共入口”；
2. 独立页面不再需要各自复制
   `entityGraphRepository + contentDataRepository`
   的拼装逻辑；
3. 之后继续清理老页面时，
   可以直接把 `openDetails(content)` 替换为
   `openResolvedDetails(content)`，
   收口成本明显下降。

这轮已经通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

边界也要明确：

- 这还不是“所有详情入口都已统一”；
- `AppRouter.openDetails(content)` 这个 projection-first 旧入口仍然保留，
  只是现在终于有了可复用的 Work-aware 替代路径；
- `ScrobblerConfigActivity`、部分 suggestion/listener、
  以及更多历史页面仍值得继续替换。

### 5.77 详情入口开始从“调用方各自解析”收缩到 `openResolvedDetails(...)`

在 `5.76` 之后，系统里虽然已经有了 router 级别的公共 Work-aware 入口，
但仍残留一批“逻辑上已经 Work-first、实现上还在各自手写解析”的页面：

- `SearchActivity`
- `SearchSuggestionListenerImpl`
- `HomeFragment`
- `ContentListFragment`
- `PreviewFragment`
- `FavoritesListScreen`
- `AppContentListRoute`
- `SearchContentListScreen`
- `AppBookmarksRoute`
- `DownloadsScreen`
- `ContentStatsSheet`

这些入口此前的共同问题不是方向错了，而是实现形态还不够收敛：

- 一部分页面自己注入 `EntityGraphRepository + ContentDataRepository`
  去拼 `entityId / preferredLocalMangaId`；
- 一部分页面在 `MainActivity.resolveDetailsOriginForContent(...)`
  之后继续手写 `when (origin)` fallback；
- 一旦后续想继续改 Work-first 详情解析，
  就仍然要在多个 UI 页面里重复改动。

本轮继续做了两件事：

1. `SearchActivity` 与 `SearchSuggestionListenerImpl`
   直接删除本地拼装逻辑，统一改走
   `router.openResolvedDetails(...)`
2. 其余已经在 UI 层手写 fallback 的页面，
   将 fallback 统一收口到
   `openResolvedDetails(...)`

当前效果可以明确写成：

- 绝大多数“只有 `Content`、没有现成 `entityId`”
  的详情跳转，已经不再需要页面自己判断
  `DetailsOrigin`；
- `router.openDetails(content)` 在代码搜索里，
  基本已经不再作为普通内容入口的默认跳转存在；
- 剩余搜索结果主要是：
  - `openDetails(uri)` 这类 URL 详情打开
  - `openDetails(mangaId)` 这类 ID 直开入口
  - 以及 `AppRouter` 内部的旧 API 实现

这一步的意义：

1. Work-first 详情打开从“局部页面遵守约定”
   进一步变成“默认走 router 公共语义”；
2. 后续继续清理老页面时，
   替换成本从“复制一段 entity 解析逻辑”
   降到“改一个方法名”；
3. 这让 Phase 3 的剩余问题
   更集中地暴露在 tracker / updates / download
   等仍带 projection-anchor 物理语义的链路上，
   而不是继续被页面级入口噪音稀释。

本轮同样已经通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.78 `updates` 展示代表内容开始摆脱对 `tracks` 原始 anchor 的直接信任

在入口层基本收口之后，Phase 3 的核心矛盾就更清楚了：

- `tracks` 表本身仍然是 projection-anchor 存储；
- `UpdatesViewModel` 虽然已经会按 entity 聚合，
  并解析 `preferredLocalMangaId` / metadata selection；
- 但在进入 ViewModel 之前，
  `TrackingRepository.observeUpdatedContent()` 仍然先把
  `tracks.manga_id` 对应的 `manga` 直接映射成
  `ContentTracking.manga`。

这会带来一个很具体的问题：

- 即使同一个 Work 的当前 representative / preferred projection
  已经切换；
- 只要旧 `tracks.manga_id` 还在作为物理锚点保留，
  updates 展示链就仍可能先把旧 projection 带进 UI 聚合层；
- 之后 ViewModel 再按 entity 聚合时，
  代表内容选择其实已经被旧 anchor 预先污染了一轮。

这一轮没有激进地重做 `tracks` 的 Room relation 结构，
而是先在 repository 输出层收紧语义：

- `TrackingRepository.observeUpdatedContent(...)`
- `TrackingRepository.getTracks(...)`
- `TrackingRepository.getTrackOrNull(...)`

现在都会先调用：

- `ContentDataRepository.findDisplayContentById(anchorMangaId, withChapters = false)`

也就是：

- 优先把 track anchor 解析成当前 display / preferred local projection；
- 只有解析不到时，才回退到原始 anchor 对应的本地内容。

这一步的意义要准确表述：

1. **没有改变 `tracks` 的物理存储模型**
   - `tracks.manga_id` 仍然存在
   - 外键与 GC 规则也仍然保留
2. **先改变了 repository 输出给 UI 的代表内容语义**
   - updates / getTracks / getTrackOrNull
     不再盲信旧 anchor 对应的 `manga`
   - display / preferred projection
     开始成为展示层的一等解析结果
3. **这为后续继续改 DAO / relation 结构争取了更干净的上层行为**
   - UI 聚合链先脱离旧 projection 污染
   - 底层表结构仍可后续分步演进

边界同样要明确：

- 这不是 `tracks` 表已经 Work-owned；
- `ContentWithTrack` / `TrackWithContent`
  仍然直接把 `tracks.manga_id`
  关系到 `manga.manga_id`；
- 因此这一步仍属于 **Phase 3 的推进项**，
  只是把“展示层继续被旧 anchor 放大”的问题先压了下去。

本轮同样已经通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

- 这只是 details 内部一个高价值回退口的修正；
- 详情页周边仍可能存在其它历史 fallback；
- 因此仍属于 **Phase 3 的继续推进项**。

### 5.76 tracker / history / stats 的 representative anchor 开始拒绝盲信失效 preferred projection

在前面已经修过：

- `TrackingRepository.syncTrackAnchors()`
  不再把不存在的 projection id 写回 `tracks`

之后，这轮又继续把同类问题往 SQL 读取层推进了一步。

此前仍存在一个共享风险：

- `TracksDao`
- `HistoryDao`
- `WorkStatsDao`

这些链路在做 representative manga / preferred track anchor 解析时，
很多地方仍然直接 `COALESCE(preferred_local_manga_id, fallback)`；
如果 `entity_preferences.preferred_local_manga_id`
已经指向一个被清理掉的 projection，
展示层与统计层虽然不一定立刻崩溃，
但会继续被过期 projection anchor 污染。

这轮的收口方式是统一加上“preferred projection 必须仍存在于 `manga` 表”的约束：

- `TracksDao`
  - representative local manga 解析
  - `gc()` 中 favourites track anchor 解析
- `HistoryDao`
  - preferred track anchor 解析
- `WorkStatsDao`
  - representative manga 解析

都不再盲信 `preferred_local_manga_id`，
而是先通过 `INNER JOIN manga`
确认该 projection 仍真实存在。

这一步的意义：

1. Work-owned preferred projection
   不再自动等价于“任何时候都可安全代表展示层/统计层”；
2. tracker / history / stats 三条共享读链
   对过期 projection 的容忍方式开始统一；
3. 后续继续把这些链路彻底 Work-owned 化之前，
   至少不会持续被失效 projection id 拖偏。

边界：

- 这依然不是 `tracks/history/stats` 存储模型的最终统一；
- 只是把 representative / anchor 读取侧
  从“盲信偏好”收紧到“只认当前存在的 projection”；
- 因此仍属于 **Phase 3 的继续推进项**。

### 5.79 `ContentTracking` 开始显式区分展示 representative 与 tracker 执行 anchor

这轮继续往 tracker 领域模型本身收口，而不是只修入口和通知。

此前已经做过：

- `TrackingRepository.observeUpdatedContent(...)`
- `getTracks(...)`
- `getTrackOrNull(...)`

优先返回当前 display / preferred projection 作为 `ContentTracking.manga`。

这对 UI 是对的，但又引入了另一层语义混叠：

- `CheckNewChaptersUseCase`
- `TrackWorker`

后续仍直接拿 `ContentTracking.manga`
去做详情拉取、章节比对、track 写回和自动下载执行。

结果就是：

- 展示层越 Work-aware，
- tracker 执行层反而越可能偏离真实的 `tracks.manga_id`
  以及当前真正的 track anchor。

当前已落地的调整：

1. `ContentTracking` 新增 `anchorMangaId`
2. `TrackingRepository` 构造 tracking 输出时，明确分开：
   - `anchorMangaId`：当前 `tracks` 行真实锚点
   - `manga`：当前 display / preferred projection 展示内容
3. `CheckNewChaptersUseCase.invoke(track)`
   先按 `anchorMangaId` 解析 execution content，
   再去拉详情和比章节
4. `TrackingRepository.mergeWith(tracking)`
   写回 track 时，不再依赖 `tracking.manga.id`
   反推锚点，而是优先使用 `tracking.anchorMangaId`

这一步的意义：

- updates / feed / notification
  可以继续展示当前 representative content；
- tracker 后台执行和章节检测
  重新围绕真实 anchor 运转；
- Work-aware 展示层不再继续反向污染 tracker 执行语义。

边界：

- `tracks` 物理表仍然是 projection-anchor 存储；
- `TrackWithContent / ContentWithTrack`
  仍然直接挂在 `tracks.manga_id -> manga.manga_id`；
- 因此这一步是 **tracker 领域模型继续收口**，
  不是 tracker 存储层已经完成 Work-owned。

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.88 scrobblings 新增 entity-first 复合索引，并让 entity 查询排序稳定化

这轮开始进入真正的 schema 铺垫层，但仍然刻意避免直接改主键。

当前已落地的调整：

1. `ScrobblingEntity`
   新增复合索引：
   - `("scrobbler", "entity_id", "target_id", "media_type")`
2. 数据库版本：
   - `62 -> 63`
3. 新增 migration：
   - [Migration62To63.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/db/migrations/Migration62To63.kt)
   - 内容只做一件事：
     为 `scrobblings` 创建上述 entity-first 复合索引
4. `ScrobblingDao`
   的 entity 查询不再无序返回任意一行：
   - `findByEntity(...)`
   - `observeByEntity(...)`
   - `findByEntityIds(...)`
   - `observeByEntityIds(...)`
   已补上稳定排序
5. `findByLocalMangaIds(...)`
   / `observeByLocalMangaIds(...)`
   也补了显式排序，
   让过渡期合并逻辑不再依赖底层“碰巧的返回顺序”

这一步的意义：

- 在不改主键的前提下，
  `scrobblings` 已开始具备更明确的 entity-first 查询支撑；
- 对于同一 `entity_id` 下存在多条记录的过渡期数据，
  DAO 层不再无序返回任意一条；
- 后续如果继续推进：
  - entity-first 去重
  - 过渡性唯一约束
  - 最终主键迁移
  当前这层索引与排序已经是必要前置条件。

边界：

- 这一步没有引入 entity-first 唯一约束
- 没有删除 projection-first 主键
- 没有消灭 legacy `manga_id` 查询接口
- 因此这里只能算 **schema/query 铺垫**，
  不是 physical identity 已经完成迁移

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.87 ScrobblingDao 的 projection-anchor 接口开始显式命名为 local manga

在继续评估 `ScrobblingEntity` 主键迁移之前，
这轮先做了一个更稳、但对后续非常必要的结构层语义收口：

- 不先改 schema
- 不先改主键
- 先把 DAO 层最容易继续误导开发者的接口名改清楚

此前 `ScrobblingDao` 中最模糊的一组接口是：

- `find(...)`
- `observe(...)`
- `findByMangaIds(...)`
- `observeByMangaIds(...)`
- `delete(...)`

这些名字的问题是：

- 调用点很容易继续把它们理解成“作品主模型查询”
- 但实际上它们一直只是：
  - **按 local projection anchor 查询**

当前已落地的调整：

1. `ScrobblingDao`
   将这组接口显式重命名为：
   - `findByLocalManga(...)`
   - `observeByLocalManga(...)`
   - `findByLocalMangaIds(...)`
   - `observeByLocalMangaIds(...)`
   - `deleteByLocalManga(...)`
2. `ScrobblingOwnership.kt`
   已同步改用新名字，
   Work-aware helper 语义也因此更清晰：
   - `entity_id` 查询是 work/entity owner
   - `local manga` 查询只是 projection-anchor fallback

这一步的意义：

- 即使当前表结构还没迁移，
  DAO 层的语义已经开始强制把
  “local projection anchor”
  和
  “work/entity owner”
  区分开；
- 后续如果继续推进主键迁移或删除 legacy DAO 接口，
  现在的调用点已经不会再被模糊命名掩盖。

边界：

- 这一步没有修改 `scrobblings` 表结构
- 没有引入新的 Room migration
- `ScrobblingEntity` 主键仍然保持 projection-first
- 因此它属于 **结构层命名收口与迁移铺垫**，
  不是 physical identity 已迁移完成

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.86 Simkl / MangaUpdates 写回链也开始复用统一的 Work-aware upsert helper

在前一轮把 `Kitsu` / `Bangumi` 的部分写回链接入 `upsertScrobblingForManga(...)`
之后，这轮继续把 `Simkl` 与 `MangaUpdates`
里最直接的本地写回路径也收进去。

当前已落地的调整：

1. `SimklRepository.saveRate(...)`
   不再直接：
   - 构造 `ScrobblingEntity`
   - `attachEntityOwnership(...)`
   - `upsert(...)`
   而是改为统一走：
   - `db.upsertScrobblingForManga(...)`
2. `MangaUpdatesRepository`
   以下路径已切到同一 helper：
   - `createRate(...)`
   - `updateRate(..., chapter)`
   - `updateRate(..., rating/status/comment)`

这一步的意义：

- `Simkl / MangaUpdates / Kitsu / Bangumi`
  这四条主要 provider 写回链，
  已经开始共享同一套 owner 规范化入口；
- 本地 `entityId` / `mangaId` 的正规化不再散落在每个 repository 里重复写；
- 后续如果还要继续调整 Work-owned owner 规则，
  需要逐个 provider 改动的范围会继续缩小。

边界：

- 这还没有处理 `MangaUpdatesRepository`
  里更深层的 remote preview / remote cover 补写路径；
- `ScrobblingDao` 和 `ScrobblingEntity` 的结构层问题仍然未动；
- 因此这里仍属于 **repository 写回链收口**，
  不是 `scrobblings` physical identity 已完成迁移。

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.85 repository 写回链开始复用统一的 Work-aware upsert helper

在上一轮把手动绑定的 rebind 逻辑收进公共 helper 之后，
这轮继续处理 repository 内部“局部字段更新后直接自行 upsert”的路径。

此前的问题是：

- 不同 provider repository
  会在本地写回时各自做：
  - `entity.copy(...)`
  - `db.attachEntityOwnership(...)`
  - 或直接 `upsert(...)`
- 这意味着：
  - owner 解析规则分散
  - `mangaId` / `entityId` 正规化分散
  - 后续继续改 owner 规则时，需要逐个 provider 追

当前已落地的调整：

1. `ScrobblingOwnership.kt`
   新增：
   - `upsertScrobblingForManga(...)`
2. 该 helper 统一负责：
   - 以指定 `mangaId` 重新解析 work/entity owner
   - 规范化 `entityId` 与 `mangaId`
   - 完成最终 upsert
3. 已接入的写回路径：
   - `KitsuRepository.saveRate(...)`
   - `BangumiRepository.updateRate(..., chapter)`
   - `BangumiRepository.updateRate(..., rating/status/comment)`

这一步的意义：

- repository 写回链开始减少重复的 `attachEntityOwnership(...)`
  和手写 owner 规范化；
- 后续如果 `ScrobblingEntity` 的 owner 写入规则继续变化，
  至少这批高频 provider 写回链不需要各自重复调整；
- 它属于 **写回链的 owner 规则收口**，
  还不是 `scrobblings` physical identity 完成迁移。

边界：

- `SimklRepository`、`MangaUpdatesRepository`
  仍保留若干直接 `findScrobblingByWorkOrManga(...)` 后写回的旧路径
- `ScrobblingEntity` 主键与 DAO 结构仍未迁移

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.84 scrobbling 手动绑定时的“删旧行再重建”逻辑开始下沉到公共 helper

这轮继续处理的是 `ScrobblerConfigViewModel.bindContent(...)`
这类仍然直接操作 row identity 的旧路径。

此前这里的问题是：

- UI 层自己：
  - 先 `findScrobblingByWorkOrManga(...)`
  - 再 `delete(currentEntity)`
  - 最后 `copy(entityId = ownerEntityId, mangaId = mangaId)` 重建
- 这等于把“如何迁移 owner / 何时需要删旧行 / 新行如何带上 work owner”
  这些结构性决策散落在 ViewModel 中

这会带来两个风险：

1. UI 层继续知道过多的 scrobbling row identity 细节
2. 后续如果 `scrobblings` 主键或 owner 迁移规则再变，
   这些手写 delete + rebuild 分支会继续成为分叉点

当前已落地的调整：

1. `ScrobblingOwnership.kt`
   新增公共 helper：
   - `rebindScrobblingToManga(...)`
2. 该 helper 负责：
   - 读取当前 work/manga 对应的 scrobbling 记录
   - 解析目标 `targetMangaId` 的 `entityId`
   - 统一生成 rebound entity
   - 在确有必要时删除旧 row
   - 最终完成 upsert
3. `ScrobblerConfigViewModel.bindContent(...)`
   不再自己 `delete(currentEntity)` 再手写重建；
   改为直接调用 `rebindScrobblingToManga(...)`

这一步的意义：

- scrobbling 手动绑定场景里的 ownership 迁移规则，
  开始从 UI 层回收到公共数据 helper；
- 后续继续改 `ScrobblingEntity` 主键或 rebind 规则时，
  至少这一条高频手动绑定链不会再各写一套；
- 它属于 **更新/重绑路径收口**，
  不是 physical identity 已经迁移完成。

边界：

- 目前只有 `ScrobblerConfigViewModel` 这条手动绑定链已经切到新 helper
- 其他 repository 内部仍有若干直接按 `mangaId` 构造 / 更新 `ScrobblingEntity`
  的分支，后续还需要继续下钻

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.83 scrobbling 的 Work-aware 候选读取不再因 `entityId` 存在而漏掉 legacy `manga_id` 记录

这轮没有直接修改 `ScrobblingEntity` 主键，而是先修了一个更靠前、也更危险的运行时语义漏洞。

此前 `ScrobblingOwnership.kt` 中：

- `findByWorkOrMangaCandidates(...)`
- `observeByWorkOrMangaCandidates(...)`

的逻辑是：

- 只要存在 `entityId`
- 就只查 `entity_id`
- 完全忽略同一个 work 下仍然只绑定在 `manga_id` 的 legacy 记录

这会导致一个很现实的问题：

- 当某个作品已经进入 Work-aware 路径，
  但旧 provider / 旧同步残留记录还没有补上 `entity_id` 时，
  `Scrobbler` 运行时读取会直接把这些旧记录“看不见”；
- 这不是 physical identity 已迁移完成，
  而是读取层过早假设迁移已完成。

当前已落地的调整：

1. `observeByWorkOrMangaCandidates(...)`
   在 `entityId` 和 `mangaIds` 同时存在时，
   不再只读 `entity_id`；
   而是把：
   - `observeByEntityIds(...)`
   - `observeByMangaIds(...)`
   结果合并后返回
2. `findByWorkOrMangaCandidates(...)`
   同样改成：
   - 读取 `entity_id`
   - 再读取 candidate `mangaIds`
   - 最后做去重合并
3. 合并逻辑使用 `scrobbler + id + entityId + mangaId + mediaType`
   作为 row key 去重，
   避免 entity-owned 与 manga-anchored 双路返回时把同一行重复放大

这一步的意义：

- Work-aware 读取语义开始真正兼容“过渡期双栈数据”；
- 只要 legacy `manga_id` 记录还存在，
  就不会因为当前作品已经解析出 `entityId`
  而被运行时读取直接漏掉；
- 这为后续继续推进 physical identity 迁移争取了更稳定的过渡层。

边界：

- 这仍然没有移除 `findByMangaIds(...)` / `observeByMangaIds(...)`
  这类 legacy DAO 接口
- `ScrobblingEntity` 主键仍然保持
  `["scrobbler", "id", "manga_id", "media_type"]`
- 因此它属于 **Work-aware 读取层修正**，
  不是 `scrobblings` 结构迁移已经完成

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.82 scrobblings 同 targetId 的代表记录选择开始统一到公共 ownership helper

这轮继续沿着 `scrobblings` 主线收口，但仍然没有直接改表主键。

此前的主要问题是：

- `SimklRepository`
- `KitsuRepository`
- `BangumiRepository`
- `MALRepository`

都在各自维护“同一个 `targetId` 存在多条本地 scrobbling 记录时，到底选哪条”的私有规则。

这会带来两个直接问题：

1. 不同 provider 的保留策略不一致
2. 后续做 Work-native physical identity 迁移之前，
   上层 merge/select 语义已经先分叉

当前已落地的调整：

1. `ScrobblingOwnership.kt`
   新增公共 helper：
   - `preferredScrobblingEntity()`
   - `preferredScrobblingByTargetId()`
   - `preferredMangaMappingByTargetId()`
2. 公共选择规则不再只是“谁先有 `mangaId` 就选谁”，
   而是统一按 ownership / payload 打分：
   - `entity_id`
   - 有效 local projection anchor
   - `rating`
   - `comment`
   - `chapter/progress`
   - remote preview
   - `media_type`
3. `SimklRepository`
   的 `existingByTargetId`
   改为直接复用公共 helper 构建，
   删除了 repository 内部私有 `preferredScrobblingEntity()`
4. `KitsuRepository`
   的 `oldMappings`
   改为复用公共 `preferredMangaMappingByTargetId()`
5. `BangumiRepository`
   不再在 repository 内重复维护一套打分权重来选主记录
6. `MALRepository`
   的 `buildOldMappings()`
   改为先按 targetId 选出公共 preferred entity，
   再构建 endpoint -> manga 映射

这一步的意义：

- 同一个远端 `targetId` 的多条本地记录，
  “谁才是当前代表记录”开始有统一规则；
- repository 级别的 owner/select 语义先收敛，
  为后续继续改 `ScrobblingEntity` 主键和迁移脚本降低分叉成本；
- `entity-first read` 不再只体现在 DAO 排序上，
  也开始体现在上层 old mapping / merge / preview retain 逻辑上。

边界：

- `ScrobblingEntity` 主键仍然是
  `["scrobbler", "id", "manga_id", "media_type"]`
- `scrobblings` 仍未完成 Work-native physical identity 迁移
- 当时仍未收口的 `AniListRepository` / `ShikimoriRepository`
  旧映射逻辑，已在后续同主线补齐：
  两者的 `oldMappings`
  现在也统一复用 `preferredMangaMappingByTargetId()`

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.84 scrobbling 公共 anchor resolver 开始拒绝失效 local projection

这轮继续清的是 `scrobbling` 领域里一个公共但隐蔽的旧语义口：

- [Scrobbler.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/scrobbling/common/domain/Scrobbler.kt)

此前这里虽然已经有：

- `entityId`
- `anchorMangaId`
- `candidateMangaIds`

也已经能按 work/entity 查找 `ScrobblingEntity`，
但 `resolveScrobblingAnchor(...)` 里对本地锚点的解析仍然有两个问题：

1. `preferred_local_manga_id`
   会被直接当成 scrobbling anchor，
   即使它对应的 local projection 已经不存在；
2. `candidateMangaIds`
   也会把这类失效 local id 带进去，
   继续影响：
   - `observeScrobblingInfo(...)`
   - `resolveScrobblingEntity(...)`
   - `selectScrobblingEntity(...)`

结果就是：

- 即使同步、备份、restore 已经开始拒绝失效 preferred projection；
- scrobbling 这条公共读取/写回链
  仍然可能继续围绕一个过期 projection 选择实体、回填状态和解析详情。

当前已落地的收口：

1. `resolveScrobblingAnchor(...)`
   在收集 entity 本地 binding 时，
   只保留当前仍然存在于 `manga` 表的 local projection id
2. `preferred_local_manga_id`
   只有在对应 local projection 仍真实存在时，
   才会被接受为 `preferredLocalMangaId`
3. `anchorMangaId`
   的解析顺序改成：
   - 有效 preferred local projection
   - 仍存在的 local binding 集合首项
   - 请求入参 `mangaId`
4. `candidateMangaIds`
   因此也不再继续携带失效 local projection id

这一步的意义：

- `scrobbling` 的公共 owner/anchor 解析，
  开始和 `tracker / sync / backup` 的 representative / anchor 规则对齐；
- work/entity 已经建立后，
  scrobbling 不会再因为一个已失效的 preferred projection
  持续把旧 anchor 放大到观察和写回链；
- 公共 resolver 收紧后，
  各具体站点 repository 即使仍保留 `mangaId` 兼容字段，
  也更少被脏 anchor 拖偏。

边界：

- `ScrobblingEntity` 的物理主键仍然包含 `manga_id`
- scrobbling 存储本身还没有完成 Work-native 主 identity 迁移
- 当前只是先把公共 anchor resolver 从“盲信 preferred id”
  收紧到“只认当前存在的 local projection”

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.83 sync / backup 的 work anchor 解析开始拒绝失效 preferred projection

这轮继续清理的是一类不容易马上炸，但会持续制造数据漂移的旁路：

- `SyncHelper`
- `BackupRepository`
- `ExternalBackupRepository`

此前这几条链在导出、同步、恢复和 legacy 归一化时，
虽然已经开始携带 `entityId` / `anchorMangaId`，
但 anchor 解析仍然普遍是：

1. 先读 `entity_preferences.preferred_local_manga_id`
2. 没有就 fallback 到本地 binding
3. 再不行就退回旧 `mangaId`

问题在于：

- `preferred_local_manga_id`
  可能已经指向一个被清理、迁移或失效的 projection；
- 这类旁路不像详情页那样立刻崩，
  但会在导出、同步回写、restore normalize、
  external import 等链路里继续把失效 anchor 当真；
- 结果就是主链已经 Work-first，
  旁路又把过期 projection 重新扩散回存储层。

当前已落地的收口：

1. `SyncHelper.resolveSyncMangaIdForEntity(...)`
   不再盲信 `preferred_local_manga_id`；
   现在会先确认该 local projection 仍存在于 `manga` 表，
   否则才继续从活跃本地 binding 中找可用 anchor
2. `SyncHelper.upsertWorkHistory(...)`
   写回 `WorkHistoryEntity.anchorMangaId`
   时同样复用“仅认当前存在 projection”的解析
3. `BackupRepository`
   中以下 legacy -> work 归一化路径：
   - `upsertWorkHistoryFromLegacy(...)`
   - `upsertWorkStatsFromLegacy(...)`
   - `resolvePreferredScrobblingAnchor(...)`
   都改成先验证 preferred projection 仍存在，
   否则 fallback 到活跃 binding / legacy anchor
4. `ExternalBackupRepository`
   在外部备份导入历史后回填 `work_history`
   时，也不再直接把失效 `preferred_local_manga_id`
   写成新的 work anchor

对应代码落点：

- [SyncHelper.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/sync/domain/SyncHelper.kt)
- [BackupRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/data/BackupRepository.kt)
- [ExternalBackupRepository.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/backups/external/ExternalBackupRepository.kt)

这一步的意义：

- Work-owned owner 状态导出/同步/恢复时，
  不再自动把“曾经被偏好过，但现在已经失效”的 projection
  重新写成权威 anchor；
- sync / backup 旁路开始和 tracker / history / stats
  的 representative 读取约束对齐；
- Work-first 主链减少被旧 anchor 漂移反向污染的机会。

边界：

- 这仍然没有消灭 `anchor_manga_id` 这个字段；
- 同步与备份链目前仍然需要保留 local projection anchor
  作为 transport / compatibility payload；
- 当前只是把它从“盲信 preferred id”
  收紧为“只认当前真实存在的 projection”，
  因此仍属于 **Work 化迁移中的旁路止血与对齐**，
  不是最终模型已经统一。

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.82 `favourites / history / category updates` 的 tracker 聚合 SQL 开始优先读取 `tracks.entity_id`

这一轮继续往 `tracks / track_logs` 的底层消费侧推进，但仍然控制在
“表达式收口”而不是“主键改造”。

此前还剩三类很典型的 projection-centric 读取：

1. `FavouritesDao.trackFieldExpr(...)`
   仍然直接：
   - 先算 preferred anchor manga
   - 再 `SELECT field FROM tracks WHERE tracks.manga_id = ...`
2. `HistoryDao.trackFieldExpr(...)`
   仍然沿用同样的 `tracks.manga_id` owner 假设
3. `FavouriteCategoriesDao.getMostUpdatedCategories(...)`
   虽然已经接入 `work_favourites`，
   但求和入口仍然是 representative anchor manga 集合，
   不是 `tracks.entity_id`

这意味着：

- favourites / history 列表的 `NEW_CHAPTERS` / `UPDATED` 排序与过滤，
  在底层仍然更相信单个 anchor projection 的 `tracks` 行；
- 分类页“最近更新最多”的统计，
  仍然在把 work 集合先压回 representative manga 集合再求和；
- `tracks` 表虽然已经有 `entity_id`，
  但跨模块 SQL 还没有真正把它当 owner 入口。

当前已落地的收口：

1. `FavouritesDao.trackFieldExpr(...)`
   改成：
   - 先按 `entity_binding` 解析当前 local manga 对应 `entity_id`
   - 优先读取 `tracks.entity_id = 当前 entity`
   - 只有拿不到时才 fallback 到 preferred anchor `tracks.manga_id`
2. `HistoryDao.trackFieldExpr(...)`
   同步改成 `tracks.entity_id` first、preferred anchor fallback
3. `FavouriteCategoriesDao.getMostUpdatedCategories(...)`
   改成两段求和：
   - 对已进入 `work_favourites` ownership 的分类项，
     直接按 `tracks.entity_id IN (work entity set)` 求和
   - 只有尚未进入 entity/work ownership 的 legacy favourites，
     才继续按 `tracks.manga_id` 求和
4. `TrackLogsDao.gc()`
   不再只用
   `track_logs.manga_id IN tracks.manga_id`
   判断保活；
   现在会额外保留：
   - anchor manga 已失效
   - 但 `track_logs.entity_id` 仍然能在 `tracks.entity_id`
     找到对应 owner
   的日志行

对应代码落点：

- [FavouritesDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/data/FavouritesDao.kt)
- [HistoryDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/history/data/HistoryDao.kt)
- [FavouriteCategoriesDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/favourites/data/FavouriteCategoriesDao.kt)
- [TrackLogsDao.kt](/d1/chuxiong/code/Kototoro/app/src/main/kotlin/org/skepsun/kototoro/core/db/dao/TrackLogsDao.kt)

这一步的意义：

- `tracks.entity_id` 不再只是表结构里“已经存在但没人真正消费”的 owner 列；
- favourites / history / categories 这些高频聚合链，
  开始把 `tracks` 当成“Work-owned tracker snapshot + anchor fallback”来读取；
- `track_logs` 的 GC 也开始具备最低限度的 owner 保活能力，
  不再因为 anchor projection 漂移就立即误删日志。

边界仍然很明确：

- `tracks` 的主键仍然是 `manga_id`
- `track_logs` 的物理锚点仍然是 `manga_id`
- `tracks.entity_id` 当前仍然不是唯一约束，也还不是主 identity
- 因此这仍然属于 **Work-owned 存储迁移的中段推进**，
  不能据此宣称 tracker 存储层已经完成统一

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.80 feed 顶部 updated carousel 开始按 Work/entity 聚合，而不再只是平铺 projection representative

这轮继续收了 `tracker` 首页顶部的 updated content 轮播。

此前这里虽然已经消费了 `TrackingRepository.observeUpdatedContent(10, ...)`
返回的 Work-aware representative content，
但仍然有两层明显残留：

1. `UpdatedContentHeader`
   只是平铺一组 `ContentListModel`
2. 点击详情与 shared element key
   仍然沿用单个 `ContentListModel` 的 projection 视角

这意味着：

- 同一个 Work 的多个 tracking item
  即使在 repository 层已接近聚合，
  feed 顶部轮播仍然没有显式携带 group/entity 语义；
- UI key、点击导航和角标统计
  仍然容易退回到 projection 视角。

当前已落地的调整：

1. `UpdatedContentHeader` 不再只保存 `List<ContentListModel>`
2. 新增 `UpdatedContentHeaderItem`，显式携带：
   - `groupKey`
   - `entityId`
   - `preferredLocalMangaId`
   - `totalNewChapters`
   - 以及用于渲染的 `ContentListModel`
3. `FeedViewModel.observeHeader()`
   现在会先按 entity / preferred local projection
   聚合 updated items，再构造 header items
4. `UpdatedContentCarousel`
   的 item key / shared element key
   开始基于 `groupKey`
5. `AppNavGraph`
   点击轮播卡片时，如果命中 `entityId`
   会直接构造 `DetailsOrigin.EntityGraph(...)`
   进入 Work-aware 详情页；
   否则才 fallback 到 `navigateToDetailsWithContent(...)`

这一步的意义：

- feed 顶部轮播不再只是“把 representative content 摆出来”；
- 它开始和 history / updates 列表一样，
  真正携带 entity group 语义；
- 点击详情与过渡动画 key
  也开始对齐 Work-first 入口。

边界：

- 这仍然没有改掉 `tracks` 的物理 projection-anchor 表结构；
- `TrackWithContent / ContentWithTrack`
  仍然保留 Room relation 直连 `manga_id`；
- 因此这是 **feed/header 展示链的继续收口**，
  不是 tracker 存储统一已经完成。

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.81 `track_logs` 展示与过滤开始优先跟随 representative local projection

这轮继续把和 `tracks` 类似的问题往 `track_logs` 链收。

此前这里仍有两个明显的旧语义残留：

1. `TrackLogsDao`
   的 `favorite / tag / nsfw / pinned` 过滤与排序
   仍然直接围绕 `track_logs.manga_id`
2. `TrackingRepository.observeTrackingLog(...)`
   输出给 feed 的 `TrackingLogItem.manga`
   仍然是 `TrackLogWithContent` relation 直接拿到的 anchor projection

结果就是：

- `track_logs` 虽然物理上仍然锚定旧 projection；
- 但 feed 展示层和过滤层
  也被迫继续盯住旧 projection；
- source switch 之后，
  更新日志流仍然会把过期 representative 放大。

当前已落地的调整：

1. `TrackLogsDao`
   引入 `representativeLocalMangaIdExpr(...)`
2. 以下过滤/排序已开始优先解析 representative local projection：
   - `Tag`
   - `NSFW`
   - `favorite`
   - `pinned`
3. `TrackingLogItem` 新增 `anchorMangaId`
4. `TrackingRepository.observeTrackingLog(...)`
   不再使用旧的 `TrackLogWithContent.toTrackingLogItem()`
   直接映射；
   而是先按 `anchorMangaId`
   调 `resolveDisplayTrackingContent(...)`
   解析当前 display / preferred projection，
   再输出给 feed
5. 旧的 `tracker/data/EntityMapping.kt`
   已删除，避免未来误用回原始 anchor 映射路径

这一步的意义：

- `track_logs` 物理表仍然保持 projection-anchor 存储，
  但展示与过滤层已经开始优先跟随当前 representative；
- feed 里“更新日志”和“顶部 updated carousel”
  的 representative 选择开始更一致；
- Work-aware 展示链进一步摆脱对旧 `track_logs.manga_id`
  的直接信任。

边界：

- 这还没有改掉 `track_logs` 的表结构和 `@Relation` 直连；
- `TrackLogWithContent`
  仍然是 `track_logs.manga_id -> manga.manga_id`；
- 因此这里只能算 **track_logs 展示/过滤链继续收口**，
  不是 track log 存储层已经完成 Work-owned。

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.87 搜索/内容列表点击入口全面改用 `initialProjectionLocalMangaId`

此前所有"用户点击内容 X 进入详情页"的导航路径，都在构造 `DetailsOrigin.EntityGraph` 时把
`getEntityPreferredLocalMangaId(entityId)` 的结果写入 `preferredLocalMangaId`。

问题在于 `applyEntityContext` 的优先级顺序：

1. `initialProjectionLocalMangaId`（最高）
2. `persistedPreferredLocalId`（DB 里已持久化的首选）
3. `preferredLocalMangaId`（参数）

因此传入 `preferredLocalMangaId = B` 时，只要 DB 里的首选也是 B，用户点击 A 仍然显示 B。

本轮已统一修复：

**origin 构造点**（三处）：

- `ContentListActivity.openContentDetails(...)`
- `AppRouter.resolveDetailsOriginForContent(...)`
- `MainActivity.resolveDetailsOriginForContent(...)`

全部改为：

```kotlin
DetailsOrigin.EntityGraph(
    entityId = entityId,
    initialProjectionLocalMangaId = content.id,
)
```

**origin 消费点**（消费 `origin.preferredLocalMangaId` 的调用，全部改为 `origin.initialProjectionLocalMangaId`）：

- `MainActivity`（search suggestion 两处）
- `SearchContentListScreen` fallback handler
- `AppContentListRoute`
- `FavoritesListScreen`
- `AppBookmarksRoute`
- `DownloadsScreen`
- `ContentStatsSheet`
- `AppRouter.openResolvedDetails`

**通知点击**（直接构造 origin，不经过 `resolveDetailsOriginForContent`）：

- `TrackerNotificationHelper.resolveDetailsIntent(...)`
- `DownloadNotificationFactory.resolveDetailsIntent(...)`

两处均改为 `initialProjectionLocalMangaId = content.id`。

这一步的意义：

- "用户点击了什么就打开什么"的语义，在导航层得到统一保证；
- `preferredLocalMangaId` 回归其本来语义：系统主动打开实体时的偏好参考，而非用户点击意图的表达。

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.88 移除投影（Remove Projection）改用 `splitLocalWorkProjection`，避免重绑

此前 `DetailsViewModel.removeActiveLocalSource(mangaId)` 调用
`entityGraphRepository.removeLocalReadingBinding(mangaId)` 彻底删除 A 的 binding。

问题链路：

1. 用户移除投影 A 的 binding
2. 再次从搜索列表点击 A → `findEntityIdsByLocalMangaIds(A)` 返回 null
3. `DetailsViewModel` 走 `LocalMangaContent` origin → `ensureLocalWorkEntity(A)`
4. `findEntityByLocalMangaId(A)` = null → `resolveOrCreateEntity` → `pickCandidate` 按标题匹配到 B 的实体 → AUTO_BIND
5. A 重新绑回 B，用户操作被静默撤销

本轮修复：

```kotlin
// before
entityGraphRepository.removeLocalReadingBinding(mangaId)

// after
entityGraphRepository.splitLocalWorkProjection(mangaId)
```

`splitLocalWorkProjection` 在解除旧绑定的同时为 A 创建独立实体，
下次打开 A 时直接命中自己的实体，不再走 `pickCandidate` 模糊匹配。

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.89 AppRouter / AppRouterEntryPoint 清理孤立的 `contentDataRepository` 依赖

`AppRouter` 在 §5.87 之后已不再使用 `contentDataRepository`，
但仍然保留了通过 `AppRouterEntryPoint` 懒加载的声明。

本轮删除：

- `AppRouter.kt` 的 `contentDataRepository` 懒加载属性
- `AppRouterEntryPoint` 接口中的 `contentDataRepository` 声明

后续如有需要，可通过调用方自行注入，而不是在全局路由层兜底。

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`

### 5.90 continue reading 入口开始走 Work preferred projection 解析

此前 `MainViewModel.openLastReader()` 和 `HistoryListViewModel.openLastReader()`
直接把 `historyRepository.getLastOrNull()` 的返回值传给 reader，
不经过 entity/work preference 解析。

结果是：source 切换后，continue reading 仍然打开旧投影，
与 `HomeViewModel.resumeStateFlow` 已有的 Work-aware 解析不一致。

本轮修复：

两处 `openLastReader()` 均加入 entity 解析逻辑：

```kotlin
val rawContent = historyRepository.getLastOrNull() ?: throw EmptyHistoryException()
val entityId = entityGraphRepository.findEntityIdsByLocalMangaIds(setOf(rawContent.id))[rawContent.id]
val preferredLocalMangaId = entityId?.let { contentDataRepository.getEntityPreferredLocalMangaId(it) }
val resolvedBase = preferredLocalMangaId
    ?.takeIf { it != rawContent.id }
    ?.let { contentDataRepository.findDisplayContentById(it, withChapters = false) }
    ?: rawContent
```

- `MainViewModel` 新增注入 `ContentDataRepository` + `EntityGraphRepository`
- `HistoryListViewModel` 已有 `dataRepository`，新增注入 `EntityGraphRepository`

同步修复 `AppShortcutManager.buildShortcutInfo()`：

此前直接用 `manga.id` 拉取 display content，source 切换后快捷方式
的封面/标题/打开目标仍然对应旧投影。

本轮加入：

```kotlin
val entityId = entityGraphRepository.findEntityIdsByLocalMangaIds(setOf(manga.id))[manga.id]
val preferredLocalMangaId = entityId?.let { mangaRepository.getEntityPreferredLocalMangaId(it) }
val resolvedId = preferredLocalMangaId ?: manga.id
val currentManga = mangaRepository.findDisplayContentById(resolvedId, withChapters = true) ?: ...
```

新增注入 `EntityGraphRepository`。

这一步的意义：

- Phase 3 的主要高价值共享入口（continue reading、快捷方式）
  开始跟随 work preferred projection；
- `HomeViewModel.resumeStateFlow` 的解析模式得到复用和推广；
- widget factory 路径（`LocalMangaContent` → `ensureLocalWorkEntity`）
  已经在 Details 层处理，不需要额外修改。

边界：

- shortcut 的 Android 层物理 shortcut id 仍然是 `currentManga.id`（local manga id）；
- widget 点击仍然通过 `ParcelableContent` 进入 Details，由 Details 层负责 entity 解析；
- 因此这里属于 **Phase 3 共享入口继续收口**，不是所有入口的最终统一。

本轮已通过：

- `./gradlew :app:compileDebugKotlin --no-daemon`
