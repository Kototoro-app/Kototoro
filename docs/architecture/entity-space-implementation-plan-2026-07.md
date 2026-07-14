# Entity Space 实施计划（2026-07）

## 目的

本文档把 Kototoro 的 Entity Space 产品设想收敛为一份可执行的 Android 架构、数据、导航和 UI 迁移计划。

Entity Space 的目标不是把漫画、小说和动画拆成三套应用，也不是重新建立三套收藏、历史和进度数据。目标是在现有 Work-first 身份体系之上，为不同媒介提供独立、可恢复、可快速切换的使用环境。

核心结论：

> Space 隔离会话，Work 统一状态，Projection 承载执行，Relation 连接媒介。

最终体验：

- 用户可以在漫画、小说和动画 Space 之间快速切换。
- 每个 Space 保留自己的导航栈、当前页面、筛选、布局和最近上下文。
- 收藏、历史、统计、追踪等作品级用户状态继续以 `entity_id` 为唯一 owner。
- 章节加载、阅读、播放、下载继续通过具体 Projection 和本地 `manga_id` 执行。
- 漫画原作、小说原作、动画改编等跨媒介作品通过 Entity Relation 连接，不强行合并为同一个 Work。

## 规范优先级

本文档严格继承以下主规范：

- [实体身份迁移收敛计划（2026-06）](./entity-identity-migration-consolidation-plan-2026-06.md)
- [Work Ownership Matrix（2026-06）](./work-ownership-matrix-2026-06.md)
- [Entity Graph Source Boundary 审计（2026-06）](./entity-graph-source-boundary-audit-2026-06.md)

发生冲突时，以 `entity-identity-migration-consolidation-plan-2026-06.md` 为准。

Entity Space 不得修改以下身份不变量：

```text
entity_id = 本地作品级用户状态键
sync_id = Work 跨设备稳定身份键
manga_id = 本地 Projection / 执行锚点
entity_binding = Projection 与 Work 之间的身份证据
preferred_local_manga_id = Work 当前默认 Projection
space_id = 本地体验会话键，不是作品身份键
```

## 产品级决策

### 1. 首批只提供三个内置 Space

```text
MANGA_SPACE
NOVEL_SPACE
ANIME_SPACE
```

首批不开放：

- 用户创建任意 Space
- 同类型多个 Space
- Space 删除、排序和共享
- Space 跨设备同步
- 插件自定义 Space 类型

三个固定 Space 已足以验证核心交互。提前开放任意 Space 会立即引入空间身份、同步、删除 tombstone、排序冲突和配置继承问题，不符合 YAGNI。

### 2. Space 不是内容类型筛选器

当前顶栏 `SwipeableFilterChip` 由各页面回调控制，仅改变当前列表筛选。Space 切换必须改变全局 active session，并恢复目标 Space 自己的导航和 UI 状态。

因此：

```text
旧行为：当前页面 -> 修改 ContentType filter
新行为：SpaceHost -> 保存当前 Space -> 激活目标 Space -> 恢复目标会话
```

页面不得自行维护 authoritative `activeSpaceId`。

### 3. 跨媒介作品不得合并为同一个 Work

漫画、小说和动画通常具有不同的章节体系、追踪目标、进度和更新生命周期。它们必须是独立 `WORK`，再通过关系连接。

正确示例：

```text
WORK #101：进击的巨人（漫画）
  -> Manga Projection A
  -> Manga Projection B
  -> WorkHistory：Chapter 80

WORK #202：进击的巨人（动画）
  -> Anime Projection A
  -> Anime Projection B
  -> WorkHistory：Episode 32

WORK #202 -- ADAPTATION_OF --> WORK #101
```

禁止：

- 因标题、别名、封面相同而自动跨媒介合并。
- 为了让同一标题出现在多个 Space 中而复制 Work。
- 给 `work_history`、`work_stats` 或 `work_favourites` 增加 `space_id` owner。
- 把 Space 当作绕过 `WorkResolver` 的身份解析入口。

首批关系仍可使用现有 `RELATED_TO`。只有当详情页展示和跨 Space 跳转都需要区分语义时，才新增 `ADAPTATION_OF`、`SEASON_OF`、`ALTERNATE_VERSION_OF` 等类型。

### 4. “媒体宇宙”不是第四个 Space

现有内容类型筛选支持 `All`。迁移后不创建持久化的 `ALL_SPACE`，避免出现一个与三个媒介 Space 重叠的第四套会话。

统一内容入口改为：

- Space Switcher 中的“媒体宇宙”命令。
- 搜索页中的“跨空间搜索”命令。
- Entity Details 中的跨媒介关系区域。

这些入口可以聚合读取所有 Work，但不拥有独立作品状态，也不参与三个 Space 的导航恢复语义。

## 当前实现基线

### 身份与聚合

当前仓库已经具备：

- `WorkResolver`：统一解析 `mangaId -> entityId -> preferred projection`。
- `WorkIdentity`：表达 Work、请求 Projection、默认 Projection 和 review 状态。
- `WorkAggregateRepository`：聚合收藏、历史、统计和追踪。
- `work_favourites`、`work_history`、`work_stats`：Work-owned 用户状态。
- `entity_binding`：来源 Projection 与 Work 的绑定证据。
- `entity_preferences`：Work 默认 Projection 和 metadata authority。

Space 必须消费这些门面，不得直接复制或重新实现身份 fallback。

### 导航

当前主界面包含：

- 一个 root `NavHostController`。
- 一个 `MainNavState`。
- 每个顶层导航项独立的 Navigation3 back stack。
- `rememberSaveable` 保存当前顶层项和 Navigation3 state。
- `DetailsOrigin.EntityGraph(entityId, preferredLocalMangaId, initialProjectionLocalMangaId)`。

限制：

- `DetailsNavKey` 当前不携带可持久化的 Work / Projection 参数。
- root `NavHostController` 只有一份，无法天然表达三个 Space 的完整 root stack。
- Compose saved state 适合进程恢复，但不能替代版本化的长期 Space session snapshot。

### 全局 Chrome 与过滤器

当前内容类型切换位于 `KototoroTopBar` 的 `SwipeableFilterChip` 中，由 `MainActivity` 把操作分发给当前页面的 `SearchBarFilterViewController.Callback`。

当前继续阅读操作：

- 手机竖屏在 History 页使用 `ExtendedFloatingActionButton`。
- 横屏 / 大屏作为 Navigation Rail action。

Entity Space 的全局切换控件必须处理这一 FAB 冲突。

### 沉浸式页面和独立 Activity

当前以下页面不共享同一个 Compose 导航宿主：

- 主界面和 Compose Details 运行在 `MainActivity`。
- `ReaderActivity` 是独立全屏 Activity。
- `NovelReaderActivity` 是独立全屏 Activity。
- `VideoPlayerActivity` 是独立全屏 Activity。
- 部分 legacy details 仍可运行在 `DetailsActivity`。

因此，“所有界面可切换”不能通过一个系统悬浮窗实现，必须由共享 coordinator 和不同 surface adapter 协作。

## 核心概念

### SpaceKind

```kotlin
enum class SpaceKind {
    MANGA,
    NOVEL,
    ANIME,
}
```

`SpaceKind` 是体验分类，不是 `ContentType` 的重命名，也不是 Entity 属性。

### SpaceId

首批使用稳定的内置字符串键：

```kotlin
@JvmInline
value class SpaceId(val value: String)

object BuiltInSpaces {
    val Manga = SpaceId("builtin:manga")
    val Novel = SpaceId("builtin:novel")
    val Anime = SpaceId("builtin:anime")
}
```

禁止使用 enum ordinal 作为持久化值。

### SpaceContext

```kotlin
data class SpaceContext(
    val id: SpaceId,
    val kind: SpaceKind,
    val allowedContentTypes: Set<ContentType>,
)
```

页面通过只读 `SpaceContext` 获得当前体验范围，不直接读取全局 preference。

### ContentType 映射

首批映射必须集中定义并测试：

| Space | ContentType |
| --- | --- |
| Manga | `MANGA`, `MANHWA`, `MANHUA`, `COMICS`, `HENTAI_MANGA`, `ONE_SHOT`, `DOUJINSHI`, `IMAGE_SET`, `ARTIST_CG`, `GAME_CG` |
| Novel | `NOVEL`, `HENTAI_NOVEL` |
| Anime | `VIDEO`, `HENTAI_VIDEO` |

`OTHER` 不自动归入任意 Space：

- 可以出现在媒体宇宙和 migration review 中。
- 用户或可靠来源信息确认后再归类。
- 不因标题或 URL 猜测而写入 authoritative 类型。

本映射只负责体验路由，不参与 Entity identity 合并。

## 状态所有权矩阵

| 状态 | Owner | 持久化位置 | Space 是否可覆盖 |
| --- | --- | --- | --- |
| 收藏、分类 | Work | `work_favourites` | 否 |
| 阅读 / 播放历史 | Work + Projection anchor | `work_history` | 否 |
| 统计 | Work + Projection anchor | `work_stats` | 否 |
| Tracking binding | Work | `entity_binding` / track | 否 |
| 默认来源 | Work | `entity_preferences` | 否 |
| 来源章节、播放 URL、下载 | Projection | manga / chapter / download data | 否 |
| Reader mode、单作品滤镜 | Projection override | `MangaPrefsEntity` | 否 |
| 当前 Space | App session | app preference | 是，应用级选择 |
| 当前顶层页面 | Space | `space_session` | 是 |
| 导航栈 | Space | memory + `space_navigation_entry` | 是 |
| 列表布局、Space 筛选 | Space / route | `space_route_preferences` | 是 |
| 全局主题、无障碍、减少动画 | App | `AppSettings` | 否 |
| 最近 Work / Projection 上下文 | Space | `space_session` | 是 |

Review gate：

- 任意新表如果同时出现 `space_id` 和作品进度字段，必须说明为什么它不是第二套 Work owner。
- Space session 只能引用 Work / Projection，不能拥有收藏、历史或追踪真相。
- 删除 Space session 不得删除任何 Entity、Projection 或用户状态。

## 数据模型

### MVP 存储策略

首批 Space 固定为三个内置定义，不新增 `space` 主表。

使用：

- `AppSettings.KEY_ACTIVE_SPACE` 保存当前 SpaceId。
- Room 保存可迁移、可校验的 session、navigation 和 route preferences。
- `rememberSaveable` 保存进程内细粒度 Compose 状态。

避免为三个固定枚举引入无意义的 CRUD 和同步模型。

### SpaceSessionEntity

建议结构：

```kotlin
@Entity(tableName = "space_session")
data class SpaceSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "selected_top_level") val selectedTopLevel: String,
    @ColumnInfo(name = "resume_kind") val resumeKind: String,
    @ColumnInfo(name = "resume_entity_id") val resumeEntityId: Long?,
    @ColumnInfo(name = "resume_projection_id") val resumeProjectionId: Long?,
    @ColumnInfo(name = "resume_route") val resumeRoute: String?,
    @ColumnInfo(name = "route_schema_version") val routeSchemaVersion: Int,
    @ColumnInfo(name = "last_accessed") val lastAccessed: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
```

约束：

- `resume_entity_id` 和 `resume_projection_id` 是本地恢复缓存，不建立阻塞删除的强 FK。
- 恢复时必须通过 `WorkResolver` 和 Projection 查询重新验证。
- Entity 不存在时回退到 Space 顶层页。
- Entity 存在但 Projection 失效时回退到 Entity Details。
- 来源卸载时回退到 Explore 或 Work Details，不恢复 broken source 页面。

### SpaceNavigationEntryEntity

完整持久化导航在第二阶段引入：

```kotlin
@Entity(
    tableName = "space_navigation_entry",
    primaryKeys = ["space_id", "stack_key", "position"],
)
data class SpaceNavigationEntryEntity(
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "stack_key") val stackKey: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "route_kind") val routeKind: String,
    @ColumnInfo(name = "route_payload") val routePayload: String?,
    @ColumnInfo(name = "route_schema_version") val routeSchemaVersion: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
```

规则：

- `route_payload` 必须由 kotlinx.serialization 的明确 DTO 生成，禁止拼接字符串协议。
- route snapshot 必须带 schema version。
- 写入以单个 Space 为事务边界，整体替换同一 stack snapshot。
- 只在导航事件、`onStop` 或 debounce 后写入，不随滚动帧高频写库。
- 最多保存明确上限，例如每个 stack 20 个 entry。
- transient dialog、sheet、selection mode、键盘状态不进入 durable stack。

### SpaceRoutePreferencesEntity

列表布局和筛选属于 Space 内的 route，而不是整个 App：

```kotlin
@Entity(
    tableName = "space_route_preferences",
    primaryKeys = ["space_id", "route_key"],
)
data class SpaceRoutePreferencesEntity(
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "route_key") val routeKey: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
```

每个 route 使用专用序列化 DTO，例如：

```kotlin
@Serializable
data class SpaceListPreferences(
    val listMode: String,
    val gridSize: Int,
    val sortOrder: String?,
    val sourceTags: Set<String>,
)
```

不把全局主题、减少动画、动态颜色、语言或账号信息写入 Space preferences。

### Projection ContentType 快照

当前 `ContentSource` 提供 `contentType`，但 `MangaEntity` 没有持久化该字段。仅靠运行时 source registry 无法稳定支持 Room 分页和来源卸载后的 Space 归类。

建议在 Projection 层增加 nullable `content_type` 快照：

```text
manga.content_type TEXT NULL
```

规则：

- 新存储的 Content 必须写入 `source.contentType.name`。
- 旧行通过可解析 source 逐步 backfill。
- 无法解析的旧行保留 null，进入媒体宇宙 / review，不默认写 `MANGA`。
- 不给 `entity` 或 `entity_binding` 增加 authoritative `space_kind`。
- 同一 Work 出现互不兼容的 active Projection 类型时进入 identity review，不自动拆分或重绑。

## Domain API

### SpaceRepository

```kotlin
interface SpaceRepository {
    val activeSpace: StateFlow<SpaceId>
    fun observeSessions(): Flow<Map<SpaceId, SpaceSession>>
    suspend fun activate(spaceId: SpaceId)
    suspend fun updateSession(session: SpaceSession)
    suspend fun replaceNavigationSnapshot(snapshot: SpaceNavigationSnapshot)
    suspend fun updateRoutePreferences(preferences: SpaceRoutePreferences)
}
```

`activate()` 只更新 active session，不读取或写入 Work 用户状态。

### SpaceContentPolicy

```kotlin
interface SpaceContentPolicy {
    fun allowedTypes(spaceId: SpaceId): Set<ContentType>
    fun spaceFor(contentType: ContentType): SpaceId?
    fun accepts(spaceId: SpaceId, contentType: ContentType): Boolean
}
```

所有首页、收藏、历史、更新、发现和搜索的 Space 过滤复用同一 policy。

### SpaceSwitchCoordinator

```kotlin
interface SpaceSwitchCoordinator {
    val state: StateFlow<SpaceSwitchState>
    suspend fun requestSwitch(
        target: SpaceId,
        origin: SpaceSwitchOrigin,
    ): SpaceSwitchResult
}
```

职责：

1. 判断当前 surface 是否允许切换。
2. 请求当前 consumer flush progress。
3. 保存当前 Space session 和导航 snapshot。
4. 激活目标 Space。
5. 验证并恢复目标 resume target。
6. 只发布一次 UDF state transition。

禁止 Activity、Composable 和 ViewModel 分别手写切换顺序。

### SpaceSwitchPolicy

```kotlin
enum class SpaceSwitchAvailability {
    AVAILABLE,
    SAVE_AND_SWITCH,
    CONFIRM_REQUIRED,
    UNAVAILABLE,
}
```

典型映射：

| Surface | Policy |
| --- | --- |
| 首页、收藏、历史、发现 | `AVAILABLE` |
| Details、ContentList | `AVAILABLE` |
| Reader、NovelReader、VideoPlayer | `SAVE_AND_SWITCH` |
| 未保存编辑表单 | `CONFIRM_REQUIRED` |
| 实体 merge / split 事务执行中 | `UNAVAILABLE` |
| 系统文件选择器、权限页 | 不显示入口 |
| Player 锁屏 | `UNAVAILABLE` |

## UDF 状态流

建议使用单一 `SpaceViewModel` 或 application-scoped coordinator state：

```kotlin
data class SpaceUiState(
    val activeSpaceId: SpaceId,
    val sessions: Map<SpaceId, SpaceSession>,
    val switcherVisible: Boolean,
    val switchInProgress: Boolean,
    val switchAvailability: SpaceSwitchAvailability,
)

sealed interface SpaceAction {
    data object OpenSwitcher : SpaceAction
    data object DismissSwitcher : SpaceAction
    data class SelectSpace(val spaceId: SpaceId) : SpaceAction
    data class ResumeSpace(val spaceId: SpaceId) : SpaceAction
    data object OpenMediaUniverse : SpaceAction
}
```

Compose 只渲染 `SpaceUiState` 并发送 `SpaceAction`。切换事务、恢复校验和数据库写入不进入 Composable。

## 全局 Space Switcher UX

### 手机主界面

主界面使用 `ExtendedFloatingActionButton`：

```text
[当前 Space 图标] [漫画空间]
```

行为：

- 静止或列表顶部：显示 icon + label。
- 向下滚动：收缩为 icon-only FAB。
- selection mode、modal sheet、输入法关键交互期间隐藏。
- 位于 bottom-end，避开 navigation bar 和系统 inset。
- 图标、文本和 content description 都表达当前 Space。

该控件属于 `KototoroApp` 的 global chrome，不属于具体页面。

### Space Switcher Sheet

点击 FAB 打开 Material3 bottom sheet：

```text
漫画空间    最近：进击的巨人 · 第80话     [继续]
小说空间    最近：无职转生 · 第12卷        [继续]
动画空间    最近：进击的巨人 · 第32集      [继续]

跨空间搜索
媒体宇宙
```

规则：

- 点击整行切换并恢复该 Space 的当前页面。
- 点击尾部继续按钮直接进入该 Space 最近的 Reader / Player target。
- 当前 Space 显示 selected indicator。
- 最近上下文来自 WorkAggregate / SpaceSession read model，不复制进度。
- 无有效历史时不显示继续按钮。
- 第一期不依赖长按或拖拽手势。

后续增强可支持按住 FAB 后向上拖到目标 Space，但点击路径必须始终完整可用。

### 大屏、横屏和折叠屏

使用 Navigation Rail 时：

- 不在内容区叠加 FAB。
- Space Switcher 放在 Rail 顶部。
- 点击后使用 anchored popup 或适配宽度的 modal sheet。
- Rail 上只显示当前 Space 图标；展开状态可显示 label。

### Continue Reading FAB 迁移

全局 Space FAB 与 History 页 Continue Reading FAB 不能同时占据 bottom-end。

迁移策略：

1. Space Switcher Sheet 的每个 Space 行提供 continue action。
2. History 页首批保留 header 级“继续”入口作为可发现性补充。
3. 删除 History 页独立 Extended FAB。
4. 横屏 Rail 的 Continue Reading action 迁移到 Space Switcher popup。

同一 surface 只保留一个高强调浮动操作。

### 内容类型 Filter 迁移

删除全局顶栏 Manga / Novel / Video `SwipeableFilterChip` 后：

- 当前 Space 自动限定主内容类型。
- source tag、排序、列表布局继续作为 route-local filter。
- Manga 内的漫画细分类别可以保留为局部筛选，不提升为 Space。
- Search 默认搜索当前 Space，并显式提供“跨空间搜索”。
- `BrowseGroupTab.All` 不再表示隐式第四 Space。

## 不同界面的入口策略

“所有界面可切换”定义为所有用户内容浏览和消费 surface 都能稳定到达 Space Switcher，而不是永久在每个窗口上覆盖悬浮控件。

### Main Shell

- 使用全局 Extended FAB / Rail action。
- Space FAB 与顶栏、底栏保持同一 chrome 生命周期。
- Space content 切换时 global chrome 不随页面平移。

### Compose Details 和 ContentList

当前这些 route 会隐藏主 chrome。处理方式：

- 在 route 自己的 top app bar 中增加 Space icon action。
- 点击打开相同 Space Switcher Sheet。
- 不在封面、章节列表或底部阅读按钮上方叠加第二个 FAB。

### Search

- 搜索框附近显示当前 Space scope。
- Space 切换后恢复目标 Space 自己的查询和结果状态。
- “跨空间搜索”是显式命令，不因 Space 切换自动混合结果。

### ReaderActivity

- 不常驻显示 Space FAB。
- 用户点击页面显示 reader controls 后，在 toolbar 中提供 Space action。
- 切换前 flush `HistoryRepository`、reading record 和当前 ReaderState。
- flush 失败时保持当前 Space 并展示错误，不静默丢进度。

### NovelReaderActivity

- 与 ReaderActivity 使用同一 coordinator contract。
- 切换前完成当前章节、页码、scroll / percent 写入。
- TTS 运行时先停止或暂停 session，再执行切换。

### VideoPlayerActivity

- 在 player controls 或 overflow menu 中提供 Space action。
- Player 锁屏时隐藏或禁用。
- 切换前保存 WorkHistory、当前 episode 和播放位置 cache。
- 停止播放、释放焦点后再恢复目标 Space。

### Settings、Import、Organize 和编辑页

- 默认不显示 Space Switcher。
- Space 不影响设置页内容时，不制造无意义的全局入口。
- 未保存表单需要 confirm policy。
- Entity merge / split 等事务进行时禁止切换。

### Legacy Activity

对仍使用独立 Activity 的 details / list 页面，通过共享 `SpaceSwitcherDelegate` 接入 toolbar action，而不是复制 bottom sheet 和切换逻辑。

## 导航架构

### 目标结构

```text
KototoroApp
  -> SpaceHost
       -> SpaceSwitcherHost
       -> GlobalChrome
       -> AnimatedContent(activeSpaceId)
            -> SpaceNavigationHost
                 -> Root NavHostController
                 -> MainNavState
                 -> Top-level stacks
```

三个 Space 各自拥有：

- root navigation stack
- `MainNavState`
- selected top-level destination
- top-level child stacks
- route saveable state
- session resume target

页面实现和 ViewModel 类型继续复用，不复制三套 feature graph。

### 进程内导航保存

首批固定三个 Space，因此可以在 `SpaceHost` 的稳定 composition position 中分别创建三份导航 state，并通过 `SaveableStateHolder` 以 `spaceId` 隔离。

要求：

- inactive Space 不清空其 ViewModelStore 和 saveable state。
- Space 切换不触发目标首页重新拉取已经存在的数据。
- `activeSpaceId` 改变只决定当前渲染哪份 navigation state。
- back 只作用于当前 Space。
- 系统 Back 不跨 Space 自动跳转。

### Durable route

当前无参数 `DetailsNavKey` 不足以恢复 Work Details。应逐步引入可序列化、可验证的 route key：

```kotlin
@Serializable
sealed interface SpaceRouteSnapshot {
    @Serializable
    data class TopLevel(val key: String) : SpaceRouteSnapshot

    @Serializable
    data class WorkDetails(
        val entityId: Long,
        val requestedProjectionId: Long?,
    ) : SpaceRouteSnapshot

    @Serializable
    data class ContentList(val sourceName: String) : SpaceRouteSnapshot
}
```

禁止把整个 `Content`、Bitmap、Parcelable tracking payload 或 View state 写入 Room route payload。

### 冷启动恢复顺序

```text
读取 activeSpaceId
-> 读取目标 SpaceSession
-> 解码 route schema
-> WorkResolver 校验 entity / projection
-> source registry 校验来源
-> 重建有效 stack
-> 无效 entry 从栈顶逐项丢弃
-> 全部无效则回到 Space 默认首页
```

恢复是 cache-like 行为，失败不能修改 Work identity。

### 独立 Activity 切换

Reader / Player 不尝试持有另一 Space 的 Activity 实例。

建议流程：

```text
requestSwitch(targetSpace)
-> prepareForSpaceSwitch()
-> flush progress
-> persist current Space resume target
-> activate targetSpace
-> finish current consumer Activity
-> reorder MainActivity to front
-> MainActivity restores target Space
-> 用户选择 Resume 时再打开目标 consumer Activity
```

首批不自动从一个 Reader 直接启动另一个 Player，避免双 Activity 动画、资源竞争和错误恢复。目标 Space Sheet 的“继续”按钮负责显式恢复消费界面。

## Motion 规范

### Space 内容切换

Space 是平级环境，不是前进 / 返回导航。使用 fade-through，不使用整页方向滑动。

推荐参数：

```text
旧 Space：fadeOut 90ms + scale 1.00 -> 0.98
新 Space：delay 70ms + fadeIn 190ms + scale 0.98 -> 1.00
总时长：约 260ms
```

实现建议：

- `AnimatedContent(targetState = activeSpaceId)`。
- `ContentTransform` 使用 fade-through 和轻微 scale。
- `contentKey` 使用稳定 `spaceId`。
- global chrome 放在 AnimatedContent 外部。
- 不跨 Space 执行 shared element transition。
- 不等待网络刷新完成才开始动画。

### Switcher 打开和关闭

- 手机使用 Material3 `ModalBottomSheet` 标准 motion。
- 大屏 anchored popup 使用 fade + scale from anchor。
- FAB label 展开 / 收缩使用 `animateContentSize` 或 `ExtendedFloatingActionButton(expanded)`。
- FAB 当前 Space icon 使用短 crossfade，不旋转整个控件。
- 切换确认时执行一次 selection haptic。

### Chrome 变化

- FAB / Rail action 的 icon、label 与 active Space 同步变化。
- container color 可在约 220ms 内轻微过渡。
- 不让整个应用颜色方案发生大幅闪变。
- Space 不只靠颜色表达，必须有 icon、label 和 content description。

### 独立 Activity

- 当前 consumer Activity 淡出约 150ms。
- MainActivity / 目标 Space 淡入约 180ms。
- 不执行跨 Activity hero transition。
- 不在 Player release 完成前启动新的媒体 consumer。

### Reduced Motion

当 `KEY_REDUCED_VISUAL_EFFECTS` 启用时：

- 仅使用约 120ms crossfade。
- 取消 scale、container transform 和弹性 motion。
- sheet 使用平台 / Material reduced motion 行为。
- animation scale 为 0 时切换必须立即完成且状态一致。

### 禁止的动画

- 全屏圆形揭露。
- 3D 翻页或卡片旋转。
- 大幅 zoom in / out。
- 根据 Manga / Novel / Anime 固定顺序做整页左右滑动。
- 模糊整棵 Compose hierarchy。
- 为动画同时重新加载三个 Space 的全部列表。

## 性能约束

- 切换目标必须使用已有 navigation / ViewModel state，不重新创建 feature graph。
- inactive Space 不持续 collect 不可见页面的高频动画或播放器 state。
- `AnimatedContent` 过渡期最多同时绘制 source 和 target 两个 Space。
- Space 列表查询必须在 repository / DAO 层按 content type 过滤，不对全库结果内存 `filter`。
- 收藏、历史、更新保持 WorkAggregate 分页策略。
- projection content type backfill 分批执行，不能在冷启动主线程扫描全库。
- Space session 写入不得由 scroll offset 的每帧变化驱动。
- 切换过程中不创建新的 Work、不调用 `ensureForProjection()`。

性能目标：

```text
warm Space switch：用户输入后 100ms 内出现视觉响应
Space transition：约 260ms 完成
无额外空白帧
无主线程 Room 查询
无重复网络刷新
```

## 无障碍与输入

- FAB content description 使用“当前：漫画空间，切换空间”。
- Sheet row 使用明确文本，不只显示图标。
- selected Space 暴露 selected semantics。
- trailing resume action 有独立语义，不能与整行点击冲突。
- 支持 TalkBack 顺序：当前 Space、其它 Space、跨空间搜索、媒体宇宙。
- 触控目标不小于 48dp。
- 键盘 / 遥控器可聚焦所有 Space row。
- Gamepad 可用 bumper 或明确菜单打开，不把隐藏组合键作为唯一入口。
- 字体放大后 label 允许换行或切换 icon-only，不截断最长 Space 名称。

## 备份与同步边界

首批 Space session 是设备本地 UI 状态，不进入 work-centric backup / sync authoritative payload。

原因：

- Space 使用本地 `entity_id`、`manga_id` 和 navigation route。
- 不同设备安装的来源和有效 Projection 可能不同。
- 导航栈和滚动位置不是高价值作品状态。

未来如同步 Space：

- Work 引用必须导出 `sync_id`，不能直接导出远端 `entity_id` 作为本地主键。
- Projection 引用必须导出 source-scoped projection key，不能跨设备复用 `manga_id`。
- restore 必须通过现有 Work identity 映射建立本地引用。
- Space sync 使用独立 semantic schema 和 device revision。
- session 冲突不允许覆盖收藏、历史、统计或 tracking。

## 分阶段实施

### Phase 0：架构 Gate 和观测

交付：

- 本文档。
- Space ownership review checklist。
- 记录当前 content type filter、Continue FAB、导航恢复和 Activity 切换行为。
- feature flags 和诊断日志规范。

验收：

- 团队确认 Space 不成为用户状态 owner。
- 团队确认跨媒介内容保持独立 Work。
- 明确首批三个内置 Space 和非目标。

### Phase 1：Domain、类型快照和只读 Space Context

交付：

- `SpaceId`、`SpaceKind`、`SpaceContext`。
- `SpaceContentPolicy`。
- Projection `content_type` nullable 快照和增量 backfill。
- WorkAggregate 查询增加 Space-aware filter。
- 单元测试和 DAO 测试。

验收：

- 三个 Space 返回正确媒介类型。
- `OTHER` 和 unresolved projection 不被静默归类。
- mixed-type Work 进入 review，而不是自动改变 binding。
- 不新增 `space_id` 到 Work-owned 状态表。

### Phase 2：全局 Switcher 和旧 Filter 替换

交付：

- `SpaceRepository`、`SpaceViewModel`、`SpaceSwitcherHost`。
- 手机 Extended FAB。
- 大屏 Navigation Rail action。
- Space Switcher Sheet。
- top bar `SwipeableFilterChip` 下线或 feature-gated fallback。
- Search 当前 Space scope 和跨空间搜索。

验收：

- 主界面所有顶层 route 使用同一个 active Space。
- 切换 Space 不修改 Work / Projection 数据。
- 当前页面 filter 不再伪装成 Space 状态。
- TalkBack 和键盘可以完成切换。

### Phase 3：独立导航栈和本地持久化

交付：

- 每个 Space 独立 root nav state 和 `MainNavState`。
- `space_session`。
- 版本化 route snapshot。
- `space_navigation_entry`。
- 冷启动恢复和无效 route fallback。

验收：

- Manga Details -> Novel History -> Anime Explore 往返后，各自恢复原页面。
- 进程重建后恢复 active Space 和有效 stack。
- 来源卸载、Entity 删除、Projection 失效不会导致崩溃。
- Back 只操作当前 Space stack。

### Phase 4：Motion 和 Chrome 收敛

交付：

- `SpaceMotion` 常量。
- fade-through 内容切换。
- FAB / Rail icon 和 label motion。
- reduced motion fallback。
- Continue Reading FAB 迁移进 Space Switcher。
- Details / ContentList route-owned Space action。

验收：

- warm switch 无白屏、布局跳动和 chrome 重叠。
- History 页没有双 FAB。
- reduced visual effects 下无 scale motion。
- 大屏不在 Rail 外叠加 FAB。

### Phase 5：Reader、NovelReader、VideoPlayer

交付：

- `SpaceSwitchCoordinator`。
- shared `SpaceSwitcherDelegate` / toolbar adapter。
- Reader / Novel / Player 的 progress flush contract。
- Player lock、TTS 和 unsaved state policy。
- MainActivity reorder / restore intent contract。

验收：

- 阅读或播放中切换不会丢失 WorkHistory。
- Player 锁定时不能误触切换。
- flush 失败不切换并明确提示。
- 不出现两个媒体 Activity 同时播放。

### Phase 6：Space Route Preferences 和媒体宇宙

交付：

- `space_route_preferences`。
- 每个 Space 独立列表模式、排序和 source tag。
- 媒体宇宙聚合入口。
- Entity Details 跨媒介关系跳转。

验收：

- Manga grid 与 Novel list 可以独立保存。
- 切换 Space 不污染其它 Space 的筛选条件。
- 媒体宇宙不写入第四套用户状态。
- 跨媒介跳转使用关系和强 binding evidence，不按标题自动确认。

### Phase 7：可选同步和自定义 Space 评估

只有前六阶段稳定至少一个发布周期后才评估：

- Space session 跨设备同步。
- 自定义 Space。
- 同类型多个 Space。
- 插件 Space。
- Space 名称、图标和排序自定义。

这不是当前 Definition of Done 的一部分。

## 推荐 PR 顺序

```text
PR-1 Space domain model + ContentType mapping tests
  -> PR-2 Projection content_type snapshot + backfill
  -> PR-3 Space-aware WorkAggregate queries
  -> PR-4 SpaceRepository + feature flags + diagnostics
  -> PR-5 Global switcher FAB / Rail / Sheet
  -> PR-6 Remove top-bar content type switch behavior
  -> PR-7 Per-Space MainNavState and root navigation state
  -> PR-8 Durable session / navigation snapshot
  -> PR-9 Fade-through motion + reduced motion
  -> PR-10 Continue Reading integration
  -> PR-11 Details / ContentList integration
  -> PR-12 Reader / NovelReader / VideoPlayer integration
  -> PR-13 Route preferences + Media Universe
```

依赖关系：

- PR-1 到 PR-3 阻塞所有 UI Space 过滤。
- PR-4 阻塞 authoritative active Space 状态。
- PR-7 阻塞“独立导航栈”的产品承诺。
- PR-8 阻塞长期持久化承诺。
- PR-12 必须在 WorkHistory 写入稳定后启用。

## Feature Flags

建议开关：

```text
entitySpaceEnabled
spaceSwitcherEnabled
spacePersistentNavigationEnabled
spaceImmersiveSwitchEnabled
spaceRoutePreferencesEnabled
```

启用顺序：

1. Debug / internal 开启 `entitySpaceEnabled` 只读过滤。
2. Switcher UI 稳定后开启 `spaceSwitcherEnabled`。
3. 导航 snapshot 测试通过后开启 `spacePersistentNavigationEnabled`。
4. Reader / Player 集成测试通过后开启 `spaceImmersiveSwitchEnabled`。
5. 最后启用 route preferences。

任一 gate 关闭时：

- 旧 content type filter 可以作为临时 fallback。
- 不删除 Room Space session。
- 不回写或转换 Work-owned 状态。
- 诊断日志记录 gate 和 active Space。

## 测试计划

### 单元测试

- `SpaceContentPolicy` 的全部 ContentType 映射。
- `OTHER` / null content type 行为。
- Space switch state machine。
- `SAVE_AND_SWITCH`、`CONFIRM_REQUIRED`、`UNAVAILABLE` policy。
- route snapshot encode / decode / schema upgrade。
- invalid entity、projection 和 source fallback。
- reduced motion transform selection。

### DAO / Repository 测试

- 按 Space 查询 WorkAggregate。
- 同 Work 多个同类 Projection 只显示一行。
- mixed-type Work 不被静默聚合到错误 Space。
- session snapshot 事务替换。
- navigation entry 上限和顺序。
- route preferences 按 `space_id + route_key` 隔离。
- content type backfill 幂等。

### Compose UI 测试

- FAB 展开 / 收缩与当前 Space label。
- Switcher Sheet 三个 Space 行和 selected semantics。
- 点击 Space 后 active state 和内容切换。
- trailing resume action 不触发错误 row action。
- selection mode / modal 状态隐藏 FAB。
- Navigation Rail 使用 rail action 而不是 FAB。
- 大字体和 TalkBack semantics。

### 集成测试

- 三个 Space 的独立 top-level 和 details stack 往返。
- 进程重建和冷启动恢复。
- 删除 Entity 后恢复 fallback。
- 卸载 source 后 ContentList fallback。
- Reader flush 后切换 Space。
- VideoPlayer 保存进度、释放播放并切换。
- TTS 运行中切换 Novel Space。
- Entity organize 事务中禁止切换。

### 性能测试

- 三个 warm Space 连续切换 30 次。
- 每个 Space 5000 个 Work 的首屏和分页。
- 各 Space stack 含 20 个 entry 的恢复时间。
- 图片列表之间 fade-through 的帧时间和内存峰值。
- Reader / Player 返回 MainActivity 的启动时间。

建议指标：

- Macrobenchmark 记录 switch input 到 target first frame。
- JankStats 记录 transition jank。
- Compose compiler metrics 检查 SpaceUiState 稳定性。
- 不因保留三个 Space state 导致不可接受的 ViewModel / bitmap 内存增长。

## 风险与缓解

### 风险 1：Space 退化为全局 Filter

表现：页面仍各自保存 `selectedContentType`，Space FAB 只调用旧 callback。

缓解：

- `activeSpaceId` 只能由 SpaceRepository 持有。
- 页面只接收 SpaceContext。
- 架构测试禁止主页面 ViewModel 写全局 active Space。

### 风险 2：Space 形成第二套 Work owner

表现：新增 `space_history`、`space_favourites` 或 `(entity_id, space_id)` 进度。

缓解：

- ownership matrix 作为 code review gate。
- 新用户状态写入必须继续经过 WorkResolver。
- Space 表只允许保存引用和 UI session。

### 风险 3：跨媒介 Work 误合并

表现：漫画和动画共享 `work_history`，进度互相覆盖。

缓解：

- merge / organize 保持 ContentType compatibility gate。
- 跨媒介仅创建 relation candidate。
- 标题相似度不能产生 confirmed binding。

### 风险 4：三份导航状态导致内存增长

缓解：

- inactive Space 停止高频 collection 和动画。
- bitmap / player 等重资源不进入 durable nav state。
- 必要时允许 inactive Space 释放页面 subtree，但保留 route snapshot 和 ViewModel read state。

### 风险 5：Activity 边界造成双重动画和资源竞争

缓解：

- consumer Activity 先 flush 和 finish。
- 首批只恢复目标 Space main session，不自动链式打开另一个 consumer。
- 禁止跨 Activity shared element。

### 风险 6：恢复旧 route 崩溃

缓解：

- route schema version。
- WorkResolver 和 source registry 校验。
- 栈顶逐项降级。
- route snapshot 视为可删除 cache。

### 风险 7：全局 FAB 与现有控件重叠

缓解：

- Continue Reading 合并到 Space Sheet。
- details / reader / player 使用 toolbar action。
- selection mode 和 sheet 展开时隐藏 FAB。
- window inset 和 bottom chrome 统一计算。

## 回滚策略

- 所有数据库修改保持 additive。
- Space session 和 navigation snapshot 可整体清空，不影响 Work / Projection。
- feature flag 关闭后恢复旧顶栏 content type filter。
- Projection `content_type` 快照保留但不参与主查询。
- 不删除旧 AppSettings filter 值，至少保留一个稳定版本周期。
- Reader / Player Space action 可以独立关闭，不影响进度写入。
- 不通过回滚脚本修改 entity binding、sync_id 或 Work-owned 状态。

## Enforcement

至少加入以下检查：

- Code review checklist：Space 不得成为作品状态 owner。
- 架构测试：主页面通过 SpaceContext / SpaceRepository 获取 Space，不读取散落 preference。
- `rg` / CI：禁止新增 `space_history`、`space_favourites` 等双主状态表。
- Repository 测试：Space switch 不调用 `ensureForProjection()`。
- UI 测试：每个 surface 只有一个高强调 FAB。
- Sync 测试：首批 work-centric payload 不导出 Space session。

## Definition of Done

首轮 Entity Space 完成必须同时满足：

- Manga、Novel、Anime 三个 Space 可在主内容界面全局切换。
- 顶栏 Manga / Novel / Video filter 已退出主 Space 切换职责。
- 三个 Space 分别保留独立导航栈和 route preferences。
- 冷启动能恢复 active Space 和有效页面。
- Details、ContentList、Reader、NovelReader、VideoPlayer 都有符合各自 surface 的 Space 入口。
- 阅读 / 播放中切换不会丢失 WorkHistory。
- Continue Reading 不与 Space FAB 冲突。
- Space 切换使用 fade-through，并支持 reduced motion。
- Space 查询在 repository / DAO 层过滤，保持分页。
- 收藏、历史、统计、追踪仍以 `entity_id` 为 owner。
- `manga_id` 只作为 Projection / execution anchor。
- 跨媒介内容通过 Relation 连接，不合并为一个 Work。
- Space session 删除、回滚或损坏不会影响任何作品数据。

## 非目标

本计划不做：

- 把应用拆成 Manga / Novel / Anime 三个模块副本。
- 为每个 Space 创建独立数据库。
- 复制收藏、历史、统计或 tracking。
- 把 SpaceId 写入 Work identity 或 entity binding key。
- 一次性重命名 `Entity -> Work` 或 `Manga -> Projection`。
- 通过标题相似度自动关联跨媒介作品。
- 第一阶段同步导航栈和滚动位置。
- 第一阶段开放任意自定义 Space。
- 使用系统悬浮窗覆盖所有 Activity。
- 为 Space 切换引入新的动画或导航依赖。

