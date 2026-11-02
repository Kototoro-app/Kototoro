# iOS 追踪网站支持增量（2026-10-17）

本轮将原有三个网站的公开检索扩展到 Android 的七个追踪网站，并接入原生账号、网站列表和进度编辑流程。
这里分别记录已实现功能与验证范围，不把协议测试或编译通过视为真实账号验收。

## 用户入口

- **浏览 → 网站发现**：按当前空间进入漫画、小说或视频目录，支持网站切换、榜单、搜索、分页与详情。
- **设置 → 追踪网站**：“发现作品”和“我的账号”两项；浏览公开目录无需登录。
- **作品详情 → 追踪与资料**：搜索预填作品标题；使用网站资料、恢复源资料、切换已关联资料、管理追踪进度。
- **网站作品详情 → 关联作品库中的作品**：从收藏和历史选择同类型作品，避免必须先回到来源详情。
- **我的账号 → 网站 → 我的网站列表**：按作品类型与状态查看远端列表，逐项关联到本地作品。

网站按钮按能力筛选；SIMKL 仅出现在视频中，另提供动画／剧集／电影选择，MangaUpdates 不出现在视频中。
自动同步默认关闭，在每个网站账号页明确启用；偏好按站点和远端账号 ID 保存。
Bangumi 的连接设置提供官方、与 Android 一致的备用接口和自定义 HTTPS 地址。
切换仅影响检索、榜单及公开补充资料，不向备用地址发送账号令牌；账号授权和进度同步始终走官方。
第三方接口可用性取决于网络和提供方，默认仍使用官方地址，不自动降级到备用地址。

## 当前能力

| 网站 | 检索、详情、榜单 | 连接账号 | 远端列表、状态、评分、进度 | 备注 | 可选补充资料 |
| --- | --- | --- | --- | --- | --- |
| Bangumi | 是 | 浏览器 OAuth／个人令牌 | 已装配 | 是 | 角色、声优、制作人员、关联作品 |
| AniList | 是，小说按 NOVEL 筛选 | 浏览器 OAuth／访问令牌 | 已装配，含重读／重看 | 是 | 角色、声优、制作人员、关联与推荐作品 |
| MyAnimeList | 是，复用 Android 应用 client ID | 浏览器 OAuth，PKCE／访问令牌 | 已装配 | 是 | 关联与推荐作品 |
| Kitsu | 是，小说 subtype 筛选 | 账号密码／访问令牌 | 已装配 | 是 | 关联作品 |
| Shikimori | 是，小说 kind 筛选 | 浏览器 OAuth／访问令牌 | 已装配，含重读／重看 | 是 | 角色、制作人员、关联作品 |
| MangaUpdates | 是，热门使用已验证的 score 排序 | 账号密码／会话令牌 | 已装配 | 隐藏 | 作者等主详情字段 |
| SIMKL | 动画、剧集、电影分开检索和读取详情 | 浏览器 OAuth／访问令牌 | 已装配，剧集进度当前只增不减 | 隐藏 | 主详情字段 |

Bangumi 将漫画和小说统一在书籍域，无法仅凭公共类型字段严格区分两者。
SIMKL 的榜单未提供“高分”切换，避免将同一排序伪装成不同榜单。
MyAnimeList 等站点在检索结果中可能混入另一种书籍类型，最终关联前校验详情类型；
Kitsu、MangaUpdates、Shikimori 和 MAL 的明确类型优先于当前搜索入口，避免把漫画误关联为小说。

## 共享代码与存储

- `core-net/tracking/discovery`：站点 ID／类型能力、目录协议、资料清洗、可选详情、OAuth／刷新、账号身份、
  列表读取、状态和评分转换，以及手动更新与自动进度更新。HTTP 引擎由平台提供。
- `core-domain/tracking/domain/AbsoluteChapterResolver.kt`：从 Android 提取章节方向、翻译分支与分季编号规则。
  Android 保留原函数入口并委托共享实现，iOS 使用相同规则计算网站进度。
- `core-library/SourceTracking`：继续复用共享资料缓存与关联表；账号追踪关联可不切换资料来源。
  替换当前资料使用的同站条目时恢复源资料，避免资料指向已解除的旧条目。
- `ios-shared/IosTrackingAccounts`：会话装配、401 后刷新重试、源记录身份检查，以及原有 `scrobblings` 表的写入。
  没有新增数据库表或修改 schema。
- Swift `TrackingAccountsModel`：会话 JSON 保存到本机 Keychain，不保留密码与授权码；刷新后重新持久化。
  OAuth 校验 state 和回调 host，授权链接不进入仓库导入流程。
- OAuth 配置在构建时从现有 Android `constants.xml` 生成到 `ios-shared/build/generated`，不复制到 Swift、
  不新建第二份凭据资源，也不增加生产依赖。

Shikimori 使用当前的 `shikimori.io` 主机，避免旧域名跳转时丢失认证请求头。
模拟器不会自动继承终端代理。在线回归可显式传入 `TEST_RUNNER_KOTOTORO_TRACKING_TEST_PROXY`；
该入口只在 Debug 二进制接受 loopback HTTP 代理，Release 忽略此环境变量，不改变系统网络设置。

资料展示始终保留源作品 ID、URL、opaque 字段、章节、书签和阅读位置。关联网站资料不自动改动网站账号列表；
只有“开始追踪／保存并同步”执行账号写入。“停止此作品的追踪”移除本地追踪记录，保留网站列表和资料关联。
解除网站关联同时停止本地追踪；断开账号不删除远端收藏，也不宣称撤销网站服务器上的授权。

现有资料缓存以 `(service, remote_id)` 为主键。MAL、Kitsu、Shikimori 动画和漫画可能重复编号，
当前明确拒绝覆盖另一作品域的同编号缓存，保留旧关联；完整支持这种双重绑定仍需要后续独立的数据格式设计。

## 自动同步与失败处理

漫画、小说和视频在退出时先持久化本地位置与阅读会话，再执行已开启站点的同步。
只有明确开始追踪的作品参与同步；没有关联的作品不会被自动加入网站列表。

自动请求只增加远端进度，不提交评分、状态、备注；MangaUpdates 的进度更新保留远端实际列表 ID。
服务端对“标记已看”的固有状态变化仍由服务端决定。手动编辑会先读取远端现有记录，再使用用户确认的字段更新；
AniList 使用 `scoreRaw`，不依赖账号自己的评分显示格式。

失败不推进本地同步记录，也不回退阅读进度。详情页显示失败原因和“重试同步”；失败提示保存到普通应用偏好，
其中不含令牌。网站返回 HTTP 200 但包含 SIMKL `not_found` 等逻辑错误时，不视为成功。
自动同步不执行远端进度回写本地阅读位置，不在系统后台持续运行，也没有新增后台任务调度。

## 验证

- 共享协议测试覆盖 OAuth state／PKCE、密码中特殊字符、令牌轮换、AniList 评分与备注转义、缺失记录与 401 区分、
  MangaUpdates 列表响应和评分、SIMKL 逻辑失败、各站自动请求不改评分和备注、64 位站点 ID、视频域上下文与类型检查。
- Native 测试覆盖资料绑定、重开与源字段保留、不切换资料的账号关联、停止追踪与阅读位置保留、跨作品域编号冲突保护。
- XCTest 覆盖七站账号发现、连接页、媒体能力筛选、搜索清除、详情页入口与源章节保留。
- 接口隔离测试覆盖 Bangumi 搜索、榜单、详情与补充资料的端点切换，以及账号请求仍使用官方地址。
- 已探测 Bangumi、MAL、Kitsu、Shikimori、SIMKL、MangaUpdates 的公开 API；
  MangaUpdates 的 `popularity` 排序返回 400，`score`／`rating` 返回 200，已按实测调整 iOS 共享实现。
- 当前自动化均不使用真实用户凭据，不向真实账号提交进度、评分或备注。

2026-10-17 的共享回归：`:core-net:jvmTest` 90 项、`:core-domain:jvmTest` 161 项通过；
`:app:compileDebugKotlin` 通过；`:ios-shared:iosSimulatorArm64Test` 88 项报告通过，其中
87 项为业务测试，1 项网络探针默认未启用。

原生 UI 的五项用例均已通过：七站账号入口、媒体筛选与搜索清除、自定义接口拒绝非 HTTPS、
作品详情入口、真实 Bangumi 资料关联／重启保留／恢复源资料。最后两项在最新构建上重新执行，
并断言重启后搜索预填名称与当前网站资料标题一致。
在线验收仅写入本地测试作品的资料关联，不连接真实用户账号。

可复用的共享验证命令：

```sh
./gradlew :core-net:jvmTest :core-domain:jvmTest :app:compileDebugKotlin :ios-shared:iosSimulatorArm64Test --no-daemon
```

本轮记录：`kototoro-tracking-kind-green.log` 为类型修正后的共享验证，
`kototoro-tracking-ui-final.log` 含三项入口测试及修正前的标题预填失败，
`kototoro-tracking-ui-acceptance.log` 为修正后两项资料流程的成功复测。

独立 Native 网络探针开启 `KOTOTORO_TRACKING_ONLINE=1` 后，在当前模拟器的官方及备用域名均出现
`NSURLErrorDomain -1202` 证书信任失败。实际 App 经 Debug loopback 代理已返回官方搜索结果，
因此独立探针结果不用于判断 App 的网络是否可用；实际 App 的完整公开资料流程已通过。
没有关闭 TLS 校验或修改设备信任设置。
真实网站端到端测试必须显式传入 `TEST_RUNNER_KOTOTORO_TRACKING_ONLINE=1`。
开发日志与截图位于 ignored `iosApp/build/tracking-support` 目录，不提交。

## 尚未完成的 Android 能力与验收

- 七站真实账号授权、令牌过期、完整远端列表分页及写入回读的人工验收；尤其需要验证服务端注册的回调是否接受 iOS。
- 真机 Keychain、浏览器返回、弱网与系统终止场景；MangaUpdates 的仅 Cookie 会话回退。
- 全量远端库导入、站点专属分类／高级筛选、每日放送／季节目录、评论／长评和完整人物实体页。
- SIMKL 的备注编辑、观看历史减量／删除，以及全部增量同步活动语义；MAL 的重读／重看状态编辑。
- 当前只在访问作品或阅读退出后进行本地业务流程，不宣称完成 Android 所有后台追踪能力。

参考：[Bangumi 官方接口](https://bangumi.github.io/api/)、[AniList 官方接口](https://docs.anilist.co/)、
[MyAnimeList 官方接口](https://myanimelist.net/apiconfig/references/api/v2)、[SIMKL 官方接口](https://simkl.docs.apiary.io/)。
