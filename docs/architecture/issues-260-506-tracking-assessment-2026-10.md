# Issue #260 / #506 追踪服务范围核实

核实日期：2026-10-21（Asia/Shanghai）。当前工作区基线：`94715a61cabd0b2280be720b6e7025c3cbd0d384`。

## 初始调查结论（实施前）

- [#260](https://github.com/Kototoro-app/Kototoro/issues/260)：MangaBaka 已用于元数据与跨站匹配，但还没有作为独立账号追踪服务接入。授权和个人书库接口验证见 [MangaBaka 接口调查](./mangabaka-api-feasibility-2026-10.md)。不能因存在离线数据支持而关闭此需求。
- [#506](https://github.com/Kototoro-app/Kototoro/issues/506)：保留为跨内容类型的需求汇总。将站点评分展示、个人记录同步和记录工具交互参考分别评估；MangaBaka 需求继续由 #260 承载。
- Android 的 SIMKL 电影、电视剧追踪代码已经包含在正式版 v2.3.0 中。但代码审查不能证明用户已成功授权、数据写入正确或发布 APK 的端到端体验无误。

## SIMKL 发布证据

GitHub Releases API 于核实日返回最新正式版为 [v2.3.0](https://github.com/Kototoro-app/Kototoro/releases/tag/v2.3.0)，发布时间为 `2026-10-20T15:21:03Z`，即北京时间 2026-10-20 23:21。`draft=false`、`prerelease=false`。

本地 `v2.3.0^{}` 指向 `e3870137ac071f91f0875cfafb2a105d485ca23c`。通过 GitHub tag ref 与 annotated tag API 交叉核对。以下实现位于该发布标签，SIMKL 目录和用户账号入口从该标签到当前 HEAD 没有差异。

| 能力 | 代码证据 | 核实结果 |
| --- | --- | --- |
| 影视类型 | `SimklRepository.kt` 的 `SimklEndpoint`、`SimklSyncType` | 包含 anime、TV、movies |
| 搜索与详情 | `findContent`、`getContentInfo` | 搜索三类端点，按类型读取详情 |
| 发现入口 | `DefaultTrackingSiteDiscoveryService.kt` 的 SIMKL 能力分支 | 动画、电视剧、电影分类，声明支持搜索、详情、手动绑定、状态同步 |
| 登录 | `oauthUrl`、`authorize`、`AndroidManifest.xml` 的 `simkl-auth` | 存在 OAuth 回调与令牌交换代码 |
| 账号入口 | `settings/UsersSettingsRoute.kt` 的 `TRACKING_SERVICES` | 已列入 SIMKL，账号摘要有剧集和电影统计 |
| 状态 | `SimklScrobbler.kt`、`postWatchlistUpdate` | 待看、观看中、完成、暂停、弃看映射及写入 |
| 评分 | `updateRate`、`postRatingUpdate`、`removeRating` | 存在设置和清除评分代码 |
| 进度 | `updateRate(...chapter)`、`postHistoryUpdate` | 电影按已看处理，剧集提交集数观看记录 |
| 远端读取 | `syncLibraryFromRemote` | 存在首次读取及基于 activities 的增量读取 |
| 解除关联 | `unregister` | 只删除本地关联，不删除远端记录 |

来源文件均位于 `app/src/main/kotlin/org/skepsun/kototoro/`，公开发布源码可从以下链接核对：

- [SIMKL Repository（v2.3.0）](https://github.com/Kototoro-app/Kototoro/blob/v2.3.0/app/src/main/kotlin/org/skepsun/kototoro/scrobbling/simkl/data/SimklRepository.kt)
- [SIMKL Scrobbler（v2.3.0）](https://github.com/Kototoro-app/Kototoro/blob/v2.3.0/app/src/main/kotlin/org/skepsun/kototoro/scrobbling/simkl/domain/SimklScrobbler.kt)
- [账号入口（v2.3.0）](https://github.com/Kototoro-app/Kototoro/blob/v2.3.0/app/src/main/kotlin/org/skepsun/kototoro/settings/UsersSettingsRoute.kt)
- [发现与能力（v2.3.0）](https://github.com/Kototoro-app/Kototoro/blob/v2.3.0/app/src/main/kotlin/org/skepsun/kototoro/tracking/discovery/data/DefaultTrackingSiteDiscoveryService.kt)

## SIMKL 验证边界

本轮未使用真实账号、未修改远端数据、未安装发布 APK。不能将“已包含实现”表述为“所有同步场景已验证”。特别需要后续覆盖：

1. 真实账号 OAuth、电影和电视剧搜索绑定、评分与状态写入后从远端读回。
2. 多季剧的集数映射、跳集与进度回退；当前代码提交 `episodes.number`，不能仅由本地进度数字推定远端所有历史都正确。
3. HTTP 成功但业务内容包含未找到等失败的处理。当前写入使用 `parseRaw()`，不能由静态接口存在推出业务成功已验证。
4. 解除本地绑定与删除远端书库记录的语义区分。当前 `unregister` 明确只操作本地数据库。

## 建议处理顺序与验收

1. #260 已具备完整追踪接入的接口基础：官方 OpenAPI 包含 profile、书库读取和单条创建/局部更新/删除，官方应用注册界面提供 Native/Public 与 PKCE。首选维护者注册公开原生 OAuth 应用并验证回调，PAT 可作为备选。尚未进行真实授权和写入读回，元数据安装、搜索不应替代账号同步的验收。
2. #506 先说明已有 SIMKL 影视支持，保留图书、网文及指定评分站点诉求。不能因支持 SIMKL 就关闭用户对豆瓣、IMDb 等具体站点的需求。
3. 候选站点按“内容类型、评分读取、账号授权、书库读取、进度/状态/评分写入、维护成本”列出能力；确定实现的站点再拆子任务。
4. IMDb、豆瓣等尚未在本轮逐项调查，不将其可行性或优先级写成已确认事实。Episodes、SeriesGuide、已阅先保留为产品参考，数据互通另行评估。

初始调查仅新增文档。随后用户授权推进 #260，实施情况见下文；实现沿用 discovery / matcher / scrobbling 分层。

### MangaBaka 首版具体边界

- 接入搜索、详情、手动绑定、OAuth 登录、远端书库读取，以及本地状态/章节/评分写入；先按明确方向完成同步，避免首版自行引入自动双向覆盖策略。
- 用 PATCH 更新应用实际拥有的字段，避免 PUT 覆盖远端备注、隐私、日期或卷数。评分按远端 0–100 与本地 0–1 转换；API 的 0 分等同未评分。
- 当前 `ScrobblerRepository` 进度接口为整数章节，`ScrobblingInfo` 也没有独立卷数。远端支持卷数不代表应用首版即可同步卷数；优先保留远端卷数，不为此扩张公共模型。
- `considering`、`rereading` 等远端状态没有简单的一一映射时，要明确显示/保存策略，不能读取后自动写回成其他状态。
- OAuth 注册与回调、令牌刷新、状态和评分清除、增减章节、失败重试、分页书库，以及写入后读回应作为后续端到端验收项。解除本地绑定不应默认删除远端条目。

这些接口契约来自 [官方 OpenAPI](https://mangabaka.org/api.json)，注册界面证据和只读探测记录见配套 MangaBaka 调查。

## #260 Android 实施记录

已接入 MangaBaka 独立服务（稳定 service ID 为 8），复用现有账号、发现搜索、详情、手动绑定和阅读进度链路。没有数据库 schema 变更或新增依赖。

- 入口为“设置 → 用户 → MangaBaka”，支持浏览器 OAuth 登录及 PAT 备选。PAT 先验证 profile，再替换本地凭据。令牌不进入隐式 Intent、实例保存状态或调试 cURL 日志。
- OAuth + PKCE、state、授权回调和 refresh token 已接入。用户已注册 Native/Public 应用，并提供公开 client ID `vCGYJtdnDzunRzShmuaKlkjwVKkMIwcA`，现作为默认构建配置启用浏览器登录。回调为 `kototoro://mangabaka-auth`；仍可通过 Gradle 属性 `mangabakaClientId` 或本地 `local.properties` 的 `mangabaka_client_id` 覆盖。显式设为空时隐藏 OAuth 按钮，不在 APK 中保存 client secret。
- 搜索保留漫画与小说；不支持视频、热门榜和人物实体。仅声明实际接入的搜索、详情、绑定和同步能力。
- 读取 `/v2/my/library` 的明确 entry/series 模型，逐页取完后在事务内应用全量快照。新条目保留远端预览；已有本地绑定保留其内容 ID；中途失败不应用半份书库。不跟随分页响应中的任意 URL 发送凭据。
- 绑定先读取已有远端条目，存在时保留个人记录；不存在时 POST 添加并 GET 读回。更新使用 `/v1/my/library/{series_id}`，不误用个人书库 entry ID。
- 绑定策略贯穿手动选择、详情自动关联和来源迁移，防止通用初始化逻辑在绑定后立即写入默认评分/本地进度。绑定后清理同条目的未绑定预览；账号书库选择绑定按远端 ID 与媒体类型定位，避免多个 `mangaId=0` 条目之间串绑。
- 自动进度更新仅 PATCH 章节；状态、评分、备注只发送实际修改的字段。0/null 清除语义和评分步长已处理；评分更新后读回服务端记录。远端 `considering` 在未明确更改状态时保留；支持 `rereading`。
- 远端失败不更新本地记录；401 的 OAuth refresh 仅重试一次。解除绑定只解除本地关联，不删除远端书库。

当前公共进度模型是整数章节，首版不提供独立卷数、分数章节、阅读日期或隐私编辑；局部更新保留这些远端数据。用户随后已在真实账号验证浏览器登录、远端收藏拉取、本地绑定远端作品，以及评分和阅读进度写入。token 刷新、其他状态边界及正式发布仍待验证。

### 公共作品评分显示修复

用户实测发现封面卡片把 MangaBaka 的 70 分显示为 0.7。根因是公共详情适配错误地将百分制除以 100；`ScrobblerContentInfo.score` / `TrackingSiteItemDetails.score` 在当前详情链路中采用 10 分制，之后才由 `DetailsViewModel` 除以 10 转为 `Content.rating`。修复将公共详情转换改为除以 10，70 分对应封面 7.0 分。个人书库评分仍按 0–1 保存和同步，未改变。

回归测试通过真实 HTTP 解析覆盖 70→7、5→0.5、100→10，以及个人 70→0.7；修复前公共评分断言失败。

用户继续反馈仍显示 0.6 后，在设备的 `tracking_site_items` 中确认该条目仍存着旧评分 `0.645`；详情优先读取持久化缓存，使上次仅修接口无法修复存量记录。补充回归测试复现“旧缓存仍返回 0.645”和“百分制搜索缓存未转为十分制详情”。现在 MangaBaka 详情缓存带有明确的量表与版本标记，旧数据作为缓存未命中重新获取；保存详情时设置十分制，读取百分制列表缓存时按其 `scoreMax` 转换。没有删除收藏、关联或个人阅读记录，也不按数值大小猜测量表。

该作品公开 API 实际返回 64.5。为避免二进制浮点让 6.45 格式化成 6.4，MangaBaka 公共详情与缓存转换按百分制整数四舍五入后展示为一位小数的十分制，即 64.5→65→6.5；个人评分同步不变。

最终 22 项 MangaBaka 测试和 Debug APK 构建通过；已在同一连接设备、同一作品页面确认封面评分从 0.6 变为 6.5，个人评分仍显示 7、进度仍为 2/3。修复后的设备截图保留在会话临时目录，未加入仓库。

### 本地验证结果

- `./gradlew :app:compileDebugKotlin --console=plain`：通过。
- `./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.scrobbling.mangabaka.data.*" :app:assembleDebug --no-daemon --console=plain`：最终代码通过，18 项针对性 JVM 测试全部成功，并产出 Debug APK。
- 测试覆盖标准 PKCE 向量、state/一次性 verifier、原生无 secret 的 token 请求、v1/v2 预览解析、PAT/Bearer 请求头、分页、业务失败/HTTP 失败、评分步长与清除、PATCH 字段保留、绑定保留远端记录、共享本地 ID 0 的条目定位，以及失败时不更新本地。
- 用 Android CLI 将最终 APK 安装至已连接设备，目标为 `MangaBakaAuthActivity`，安装与启动命令成功。设备处于图案锁屏，UI 检查只得到系统锁屏页面，因此没有宣称完成登录页视觉验收，也不将锁屏截图作为功能截图。
- 未使用真实账号或生产写入，未发布 issue 回复、提交代码或创建分支。OAuth 应用由用户随后注册并提供 client ID；后续仍需用户在设备上完成浏览器登录，验证授权回调与书库读写。

### 追踪站品牌图标

原先临时使用通用书本图标，现替换为官网首页实际引用的[官方彩色图标](https://mangabaka.org/images/logo.png)，原样保存在 `app/src/main/res/drawable-nodpi/ic_mangabaka.png`（128×128，11,243 字节）。账号列表、书库、绑定选择、追踪面板和详情来源标记保留原色，避免被 Compose Icon 默认 tint 覆盖成单色。素材 SHA-256：`d27ea6002a1f8b7dc4e8e91f856a65dd2db3f288601657d9f916654a9d629699`。

随后按用户要求恢复其他追踪站的品牌原色：AniList、MAL、Kitsu 和 Shikimori 复用本地 `../mihon/app/src/main/res/drawable/brand_*.xml` 的品牌矢量，统一固有尺寸为 24dp；SIMKL 复用本地 `../aniyomi/app/src/main/res/drawable-nodpi/ic_tracker_simkl.webp`；Bangumi、MangaUpdates 使用本项目已有彩色矢量。SIMKL 和 Shikimori 保留原始黑白/灰白风格。所有追踪站品牌资源禁用主题 tint；普通功能图标继续沿用主题色。站点选择、发现页、详情、账号和授权入口统一处理，位图显式限制图标尺寸，防止固有像素尺寸扩大布局。

### Mihon 参考

按用户提供的线索检查了 `../mihon`：本地当前分支及已有 `origin/main` 未包含 MangaBaka；因此进一步读取 [Mihon 官方 API 实现](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/MangaBakaApi.kt)、[Tracker 实现](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/MangaBaka.kt)及本地 DropSauce 实现，对照 PKCE、0/null、绑定保留记录和更新语义。没有复用 Mihon 的 client ID、回调或排除小说条件；个人书库读写以当前官方 OpenAPI 为准。

## Issue 回复草稿（尚未发布）

### #260

An Android MangaBaka integration is in progress, covering browser OAuth sign-in, PAT sign-in, search, manual binding, library import, reading progress, status, and ratings. A Kototoro public client is now configured. Real-account end-to-end verification and release are still pending, so this issue should remain open.

### #506

当前 Android 正式版 v2.3.0 已包含 SIMKL 的电影、电视剧及动画追踪实现，可在设置的用户账号页面找到 SIMKL。豆瓣、IMDb 等指定站点，以及图书、网文追踪仍是独立诉求，本 issue 会保留为需求汇总。后续将分别评估站点评分展示与个人收藏、评分、进度同步，按接口可行性逐个推进。MangaBaka 接入由 #260 单独跟踪。
