# 社区评分、评论与推荐：Nostr + 静态索引方案（2026-11）

目标：在不运营任何服务器的前提下，提供与 Kotatsu-Redo 社区功能（`community.kotatsuredo.app`）相近的
评分、评论、回复通知、排行榜与个性推荐，三端（Android / 桌面 / iOS）共用协议与规则。

一句话架构：**Nostr 负责写入与身份，GitHub Actions 充当无状态的批处理服务器并发布静态快照，个性化在本地计算。**

```
 客户端 ──签名事件──▶ 公共 Nostr 中继 ×4–6 ◀──定时拉取── GitHub Actions（community-indexer）
   ▲   ◀──实时订阅（本作品新评论 / 回复我）──┘                         │ 归档原始事件 + 计算
   │                                                                   ▼
   └──────────── 经 GitHubMirrorCatalog 下载静态快照 ◀──── GitHub Pages（community-index 仓库）
```

## 1. 范围

第一版包含：

- 每人每部作品一个评分（1–10），可修改、可撤回。
- 作品级评论与楼中楼回复，可选章节标记、剧透标记、语言标记；作者可删除自己的评论。
- 回复通知。
- 举报、个人屏蔽、维护者屏蔽列表、被多人举报后自动折叠。
- 排行榜（周 / 月 / 全部 × 内容类型）、相似作品、本地个性推荐。

第一版不做：评论投票、昵称审核、来源健康探针、推送通知。

## 2. 身份

- 首次开启社区功能时生成 secp256k1 密钥对，与用户的其他身份无关。
- 私钥导出为 NIP-19 `nsec1…`，作为“恢复密钥”，界面沿用 Kotatsu-Redo 的引导：生成、抄写、确认。
  也允许导入已有 `nsec`。
- 存储：Android 用 Keystore 包裹的 AES 密钥加密后存进偏好；桌面存数据根目录，权限仅当前用户；
  iOS 存 Keychain。私钥不进备份 ZIP（备份只记录公钥，便于提示用户“恢复密钥未备份”）。
- 昵称与头像发布为 kind 0 元数据；客户端只显示昵称，不加载远程头像（隐私与流量）。

## 3. 作品标识

没有服务器帮忙归一作品，所以每条事件都带上该作品所有能确定的标识。
标识统一用 NIP-73 外部内容 ID 的形式，放在单字母标签里，中继可以直接按标签索引。

### 3.1 强标识：外部站点的规范 URL（NIP-73 的 `web` 类型）

来源依次为：`scrobblings` 表中已绑定的追踪条目，作品详情里解析器给出的外部 ID，
以及 `MALSyncMappingApi` 的跨站映射。

| 服务 | 规范形式 |
|---|---|
| AniList | `https://anilist.co/{manga|anime}/{id}` |
| MAL | `https://myanimelist.net/{manga|anime}/{id}` |
| Kitsu | `https://kitsu.app/{manga|anime}/{id}` |
| Shikimori | `https://shikimori.one/{mangas|animes}/{id}` |
| Bangumi | `https://bgm.tv/subject/{id}` |
| MangaUpdates | `https://www.mangaupdates.com/series/{id}` |

规则放在 `core-domain`：`CommunityWorkKeys.strongKeys(...)`。小说、视频按各站的媒体路径区分；
TMDB、Simkl 等后续再加。

### 3.2 弱标识：标准化标题

`kototoro:t:{medium}:{folded}`，实现是 `core-domain` 的 `CommunityWorkKeys.titleKey`：

- `medium` 是 `CommunityMedium`（comic / novel / video / other），按大类合并内容类型：MANHWA 与 MANGA、
  成人与非成人同属 comic，避免不同来源对同一作品的分类不一致导致拆分。
- `folded` 的处理顺序：先去掉结尾重复的来源名，再由平台折叠（NFKC、小写、繁转简），最后只保留字母、数字和代理对；
  最长 160 个字符，纯数字或不足 2 个字符的标题不生成键。
- 主标题和别名各生成一个，同一事件最多 8 个。
- **不做哈希**：可读的键让索引器以后可以改进匹配规则（例如去掉 `raw`、`official` 之类的后缀，
  或接入跨语言别名），并对已发布的事件重新归一；哈希会把客户端当时的规则永久固定下来。

### 3.3 标签写法（以评分事件为例）

```
["i", "https://anilist.co/manga/30013"],
["i", "https://myanimelist.net/manga/2"],
["i", "kototoro:t:comic:onepiece"],
["k", "MANGA"]                 // Kototoro 内容类型；NSFW 来源另加 ["content-warning", "nsfw"]
```

### 3.4 归一（在索引器中完成）

实现是 `core-domain` 的 `CommunityWorkGroups.build`，结果与事件顺序无关：

1. 同一事件上的强标识两两相连，按“同时出现的事件数”从多到少处理。如果某次合并会让一部作品在同一站点上
   有两个 ID，就拒绝合并并计入 `conflicts`（多数意见胜出，错误绑定被孤立）。
2. 标题键并入与它同时出现次数最多的那个强标识组；如果多个组并列最多，就不并入任何组，计入 `ambiguousTitleKeys`，
   并且在后续步骤中也不再参与合并。
3. 没有强标识的作品，其标题键彼此合并；标题键永远不能把两个都有强标识的组合并在一起。
4. 组 ID 取组内最小的强标识，没有就取最小的标题键；客户端不需要知道组 ID。

### 3.5 P0 实测（2026-11-20，`-PcommunityEvalBackup`）

用作者 2026-09-14 的真实备份（1441 部作品：comic 911、video 410、novel 38、other 82；成人 778）测得：

- **带强标识的作品：0**。没有绑定追踪站点，也没有设置元数据来源。没有键的作品只有 2 部。
- 每部作品平均 1.26 个标题键（只有 344 部带别名）。
- 同一书库内，跨来源被标题键合并的组有 28 个，抽查全部正确：英文译名、罗马音、中文名在各自语种内都能对上。
- 同一来源内被合并的组有 5 个，是同一来源的重复条目（生肉与翻译版、同名多季），算不上错误合并。
- 冲突 0，有歧义的标题 0。

结论：

- **准确率没有问题，召回率是瓶颈**。标题键只能匹配同一语言、同一译名；例如「魔都精兵的奴隶」和
  “Mato Seihei no Slave”永远对不上。实际的跨设备匹配率主要取决于有多少作品带强标识，而绝大多数用户不会去绑定追踪站点。
- 因此要新增 **P0.5：自动解析外部 ID**。客户端在用户发布评分或评论时，用作品的标题去 AniList（GraphQL，免认证）
  和 Bangumi（`api.bgm.tv/v0/search/subjects`，需要 UA）搜索，满足以下条件才采用：
  - 对方的某个标题、同义词或原名折叠后与本地某个标题键完全相等；
  - 媒介一致；
  - 候选唯一。
  结果永久缓存在本地（与 Kotatsu-Redo 的 `WorkRefEntity` 同理），不确定时宁可不填。
  这样同一部作品的中文、日文、英文读者都会落到同一个 AniList 或 Bangumi ID 上。
- 键规则的后续改进（去掉 `raw`、`official` 后缀等）放进索引器，不需要客户端发版。

### 3.6 P0.5 实测（2026-11-20，`-PcommunityResolveBackup`）

实现：`core-net` 的 `CommunityExternalSearchApi`（AniList GraphQL 与 Bangumi `v0/search/subjects`，免认证；
被拒绝时抛 `CommunityExternalSearchException`，不能当作“无匹配”缓存），`core-domain` 的 `CommunityExternalMatch`
（媒介一致 + 名称折叠后与本地标题键完全相等 + 该站点唯一；纯拉丁字母且少于 5 个字符的键不参与）。

从同一备份中按固定种子抽取 80 部未绑定的作品，每部最多用 2 个标题搜索（AniList 间隔 2.1 s，Bangumi 间隔 1 s）：

| 分类 | 绑定 / 样本 |
|---|---|
| 一般漫画 | 16 / 29 |
| 成人漫画 | 11 / 26 |
| 一般视频 | 1 / 5 |
| 成人视频（真人） | 0 / 17 |
| 小说（网文） | 0 / 2 |
| 合计 | 28 / 80（AniList 24，Bangumi 8；两站都绑定的有 4 部） |

- **准确率**：28 条绑定全部人工核对，没有错配。
- 成人真人视频和网文本来就不在这两个站点上，不绑定是正确的；这类作品只能靠标题键。
- 未绑定的一般漫画，多数是本地标题本身的问题，可以改进：
  - 多个别名挤在一个字段里、用逗号分隔（RAWKUMA）；
  - 标题里夹带汉化组或来源的噪音，例如 `[Greed Scans]`、`Other name (s) :`、`＠ＣＯＭＩＣ`；
  - 罗马音写法不同，`o` 与 `wo`、`Skill` 与 `Sukiru`；
  - 标题被来源截断。
- Bangumi 有 8 次搜索失败，原因待查（限流或超时）。客户端正式解析时要按失败重试，不能缓存结果。

**标题清理后重测**（同一样本、同一种子）：

- 本次改动：
  - 不带空格的逗号、带空格的 ` / `、` | ` 都按别名列表拆开（带空格的逗号、`、`、`，` 属于标题本身，不拆）；
  - 去掉开头的 `Other name(s):` 标签、首尾的方括号标签、结尾的 `@COMIC` 和 `raw` / `official`；
  - 独立出现的罗马音 `wo` 一律按 `o` 处理。
- 本地匹配：跨来源合并的组从 28 个增加到 37 个，抽查全部正确；同来源重复条目仍为 5 个，冲突和歧义仍为 0。
- 在线解析：一般漫画从 16/29 提高到 18/29，合计从 28/80 提高到 30/80。
  新增的两条绑定都正确：`[Greed Scans]` 去掉后，「Fun Territory Defense」匹配上了 AniList；
  「凡骨新兵のモンスターライフ＠ＣＯＭＩＣ」也匹配上了。
- Bangumi 的 9 次失败都是 `HttpRequestTimeoutException`（20 s 超时），不是限流。客户端要把超时放宽，并在失败时重试。
- 剩下没匹配上的一般漫画，主要是长标题的写法与站点不同（站点没收录某个副标题变体，或者标题被来源截断）。
  继续改规则收益很低，留给索引器之后批量处理。

## 4. 事件定义

| 用途 | kind | 关键标签 | 说明 |
|---|---|---|---|
| 元数据 | 0 | — | 昵称 |
| 评分 | **34259**（可寻址；沿用通用评分提案 [nips#1914](https://github.com/nostr-protocol/nips/pull/1914)，2026-11 尚未合并） | `d`=`<medium>:<首个强标识或首个标题键>`；`m`=medium；`rating`=星级÷10（`"0.8"`）；`i`×n；`k` | 同一作者同一 `d` 只保留最新；撤回用 NIP-09 删除，按 `a`=`34259:<pubkey>:<d>` 指定 |
| 评论 | 1111（NIP-22） | 根：`I`×n + `K`=`web` 或 `kototoro`；父级：顶层评论同根，回复用 `e`+`k`=`1111`+`p` | `content` ≤ 4000 字；`["chapter","12.5"]` 可选；NIP-36 `content-warning` 表示剧透；NIP-32 `L`/`l` 语言 |
| 删除 | 5（NIP-09） | `e` | 中继不一定执行；索引器与客户端都要遵守 |
| 举报 | 1984（NIP-56） | `e` + `p` + 类型 | 类型取 `spam`、`illegal`、`nudity`、`profanity`、`other` |
| 维护者屏蔽 | 10000（NIP-51，由维护者的审核专用密钥签名） | `p`、`e`、`word` | 公钥写死在客户端里，也写在快照的 manifest 中 |

`d` 标签的问题：用户先用弱标识评了分，后来绑定了 AniList，`d` 就变了，会留下两条评分。
处理办法：发布新评分时，客户端同时对旧 `d` 发一条 kind 5 删除；索引器按“同一作者、同一组”只保留 `created_at` 最新的一条。

工作量证明（NIP-13）：评分 16 位，评论 20 位，难度由快照下发，可以随时调整。
挖矿在 `Dispatchers.Default` 上运行，可取消，超过 10 s 仍未完成时提示用户。

## 5. 中继

- 默认 5 个公共免费中继。候选列表在实现阶段实测“写入后能否按 `#i` / `#I` 查到”再确定，结果写进快照的
  manifest，客户端用快照里的列表覆盖内置列表。
- 写入：同时发给全部中继，收到 ≥2 个 `OK` 即视为成功；失败的事件留在本地发件箱，按指数退避重试。
- 读取：实时订阅只连 2–3 个中继，按事件 ID 去重。
- 用户可以在设置里增删中继。

### 5.1 中国大陆网络实测（2026-11-20，真机，未开 VPN）

| 问题 | 实测 | 对策 |
|---|---|---|
| DNS 污染 | 中继域名被解析到 Twitter、Facebook 的地址；国际 DoH（Cloudflare、Google、AdGuard）连不上；AliDNS 的 DoH 也返回污染结果 | 中继连接单独用 `CommunityRelayDns`：先问 DNSPod（`doh.pub`，结果与 Cloudflare 一致），再问 Cloudflare 和 Google，最后才用系统 DNS；缓存 10 分钟 |
| SNI 阻断 | nos.lol、relay.damus.io、relay.primal.net、nostr.mom 等约 20 个主流中继，即使 IP 正确，TLS 握手也会按 SNI 被重置；同一 IP 换成中性 SNI 则可以连上 | 客户端无解；这些中继留给境外用户和开 VPN 的用户 |
| 可直连且开放写入 | 只有 relay.nostr.wirednet.jp（拒绝带 `nonce` 标签的事件）；yabu.me 能连上但返回 `Country CN not allowed`；einundzwanzig、nostrzh、vectorapp 限制写入；nostr.moe、lang.relays.land 需要白名单或认证 | 工作量证明改用自有标签 `kototoro-nonce`（`CommunityEventSchema.POW_TAG`），索引器按同一标签校验；发件箱只要 1 个中继接受就算发布成功，持久性由索引器的 `archive` 负责 |

默认中继（应用与索引器一致）：`yabu.me`、`relay.nostr.wirednet.jp`、`nos.lol`、`relay.primal.net`、`nostr.mom`、`relay.damus.io`。
应用使用这份列表与快照 manifest 中列表的并集，确保 manifest 不会挤掉内置的可直连中继。

风险：大陆用户免 VPN 写入目前只依赖 wirednet 这一个由个人运营的中继。备选是在 Cloudflare Workers 上自建中继（例如 nosflare），
但必须绑定自有域名，因为 `workers.dev` 在大陆同样被阻断。

## 6. 索引器（community-indexer）

### 6.1 位置

- 代码放在本仓库的新 JVM 模块 `community-indexer/`，直接依赖 `core-domain` 的标识与归一规则，保证与客户端完全一致。
- 新建仓库 `Kototoro-app/community-index`：
  - `archive` 分支：按月归档原始事件，格式是 `events/YYYY-MM.jsonl.gz`。
  - `gh-pages` 分支：存放快照。
- 本仓库的 workflow `community-index.yml`，用 cron 每 6 小时运行一次，并支持手动触发；通过 deploy key 推送到上面那个仓库。

### 6.2 每次运行

1. 读取 `archive` 中的最大 `created_at`，减去 2 小时作为 `since`；向全部中继请求相关 kind 中
   `#k` 或 `#K` 匹配 Kototoro 的事件。
2. 验证签名与 PoW，去重，追加到归档。归档本身就是持久存储：哪个中继清理了旧数据都不影响；
   必要时还可以把归档重新广播给中继。
3. 归一作品，然后处理评分：
   - 维护者屏蔽的作者和事件直接剔除。
   - 计算账号权重：首次出现 < 7 天为 0.3，< 30 天为 0.7，其余为 1.0。
   - 单个作者在 24 小时内评分超过 200 条的，这段时间的评分全部作废。
   - 贝叶斯平均 `(v·R + m·C)/(v+m)`，其中 `m=10`，`C` 为该内容类型的全局均值；同时给出 1–10 的分布和有效样本数。
4. 排行榜：周 / 月 / 全部 × 内容类型，各取前 100。门槛是有效样本数 ≥5；NSFW 单独成表。
5. 相似作品：对去均值后的评分做 item-item 余弦相似度，要求共同评分人数 ≥3，每部作品保留前 20。
   评分稀疏时，用“同一用户都打了 ≥8 分”的共现数作为补充信号。
6. 举报处理：同一事件被 ≥3 个权重为 1.0 的账号举报，就在快照中标为 `hidden`。
   新增举报汇总进一个固定的 GitHub issue（审核队列），维护者确认后用本地 CLI 更新 kind 10000 屏蔽列表。
7. 生成快照并强制推送 `gh-pages`（只保留最新版本，不保留历史）。

### 6.3 快照格式（`v1/`）

```
v1/manifest.json        生成时间、版本、中继列表、PoW 难度、维护者公钥、各分片的 hash
v1/keys/{00..ff}.json   标识 → 组 ID（按 sha256(标识) 的首字节分片）
v1/works/{00..ff}.json  组 ID → { 评分均值, 分布, 样本数, 评论数, 相似作品[] , hidden 评论 ID[] }
v1/charts/{window}-{type}[-nsfw].json
v1/moderation.json      屏蔽的公钥、事件、关键词
```

客户端先下载 `manifest.json`（带 ETag），再按需下载分片并缓存。分片 hash 没变就不重新下载。

容量预期：一万部作品、十万条评分时，整个快照约几 MB，远低于 GitHub Pages 1 GB 的站点上限。
如果流量接近 Pages 的软限制（每月 100 GB），再迁到 Cloudflare Pages；客户端只需修改快照根地址。

## 7. 客户端

### 7.1 模块划分

| 位置 | 内容 |
|---|---|
| `core-net` 的 `community/nostr/` | 事件模型、规范化序列化、id 计算、BIP-340 签名与验证（`fr.acinq.secp256k1:secp256k1-kmp`，JVM 另需 `secp256k1-kmp-jni-jvm`）、NIP-19 bech32、NIP-13 挖矿、基于 Ktor WebSocket 的多中继连接池（`REQ` / `EVENT` / `CLOSE` / `EOSE` / `OK`）、快照下载 |
| `core-domain` 的 `community/` | `CommunityWorkKeys`（标识生成）、各事件的构造与解析、评论树组装、本地过滤（屏蔽 / hidden / 关键词）、本地推荐打分 |
| `core-db` | 迁移 89→90：新增 `community_outbox`（已签名但未确认的事件）和 `community_ratings`（manga_id、评分、d、事件 id、状态） |
| `app` 的 `community/` | Hilt 装配、密钥存储、WorkManager（发件箱重试、回复轮询、快照刷新）、界面 |
| `desktop-app` / iOS | 第二阶段跟进，复用上面三层 |

### 7.2 界面（Android 先行）

- **详情页**：评分行显示均值、样本数和“我的评分”，点击打开 1–10 的评分弹窗；相似作品放在一个轮播里；
  评论入口显示评论数。
- **评论页**：一个 sheet，支持排序（最新 / 最热，最热按回复数）、语言筛选，剧透默认折叠，
  长按菜单提供回复、举报、屏蔽作者、删除（仅自己的评论）。
  打开时先显示快照里的评论数，再通过实时订阅 `#I` 拉取全部评论。
- **阅读器章末**：显示“本章评论”入口，即带 `chapter` 标签筛选的评论列表。
- **推荐页**：新增“社区推荐”和“排行榜”两个分区。
- **设置 → 服务 → 社区**：总开关（默认关）、恢复密钥、昵称、中继、“NSFW 作品允许评分”（默认关）、
  屏蔽列表、清除本地社区数据。

### 7.3 个性推荐（纯本地）

```
候选 = ∪ 相似作品(w)，w ∈ 我的评分 ≥7 的作品 ∪ 收藏 ∪ 读完的历史
score(c) = Σ_w sim(w,c) × affinity(w)   // affinity：我的评分减 5；只收藏的记 2；读完的记 1.5
         × (1 + 0.3 × 标签口味重合度)    // 复用现有 suggestions 的口味画像
过滤：已在库中、NSFW 设置、全局标签黑名单、维护者屏蔽
```

候选只有组 ID 和标题。点开时，先用标题在已启用的来源中搜索，再用 `RelatedContentRules` / `RelatedWorkRules`
挑出对应作品；这和现有相关作品的搜索回退是同一条路径。

### 7.4 回复通知

WorkManager 每 30 分钟运行一次（iOS 用 `BGAppRefreshTask`，系统调度），向 2 个中继请求
`{"kinds":[1111], "#p":[我的公钥], "since":上次检查时间}`，过滤掉已屏蔽的作者后发出本地通知。

## 8. 隐私与合规

- 功能默认关闭，开启前用引导页说明三点：评分和评论公开且永久、无法真正删除、评分会公开阅读口味。
- NSFW 来源的作品默认不允许评分和评论。
- 只发布作品标识，不发布阅读进度、收藏和历史。
- iOS 审核规则 1.2 要求的过滤、举报、屏蔽、联系方式，第一版必须齐备。

## 9. 分期与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0（已完成） | `CommunityWorkKeys` 与 `CommunityWorkGroups`，加测试 | 见 §3.5：准确率达标，召回率受限于强标识覆盖率 |
| P0.5（规则与实测已完成） | 外部 ID 自动解析（AniList / Bangumi 标题搜索，严格匹配，本地永久缓存） | 见 §3.6：一般漫画 16/29，28 条绑定无误配；客户端缓存与调度放到 P3 一起做 |
| P1（已完成） | `core-net` 的 Nostr 栈，见 §10 | 对照 NIP-01 测试向量验证签名；在 2 个真实中继上完成写入、按 `#i` 查询、删除 |
| P2（已部署） | 索引器与快照，先跑 `workflow_dispatch`，见 §10.1 | 用种子事件生成快照；归档能增量追加；重复运行结果一致 |
| P3 | Android 评分、评论、举报、屏蔽 | 详情页、评论页、发件箱离线重试可用；被屏蔽的内容不显示 |
| P4 | 回复通知、本地推荐、排行榜 | 推荐过滤正确，不推荐库内已有作品 |
| P5 | 桌面与 iOS | 复用 P0–P2，各端只写界面 |

## 10. P1 实现记录（2026-11-20）

`core-net` 的 `org.skepsun.kototoro.community.nostr` 包：

| 文件 | 内容 |
|---|---|
| `NostrEvent.kt` | 事件模型、NIP-01 id 序列化（只转义规定的 7 个字符，其余控制字符原样写出）、JSON 读写 |
| `NostrKeys.kt` | `NostrKeyPair`（`CryptoRand` 生成、x-only 公钥）、BIP-340 签名与验证 |
| `NostrBech32.kt` | NIP-19 `npub` / `nsec`，恢复密钥可带空格与换行粘贴，校验和错误即拒绝 |
| `NostrPow.kt` | NIP-13：前导零位数、`nonce` 挖矿（每 4096 次检查取消）、只认承诺过的难度 |
| `NostrRelayMessages.kt` | 过滤器（含与中继一致的本地匹配）、客户端与中继消息 |
| `NostrRelayClient.kt` | 单中继的发布（等待对应 OK）、查询（到 EOSE 为止）、订阅；连接、读取、关闭都有超时；签名不对或不符合过滤器的事件一律丢弃 |
| `NostrRelayPool.kt` | 多中继并发写入（达到 `minAccepted` 才算发布成功）、合并去重的查询与订阅 |

依赖：
- `fr.acinq.secp256k1:secp256k1-kmp` **0.15.0**：这是 JVM JNI 产物最后一个面向 Java 11 的版本（0.16 起要求 Java 17，0.17 起要求 21），覆盖 JVM、Android 和 iOS 两个架构；
- `org.kotlincrypto.hash:sha2` 0.8.0；
- `org.kotlincrypto.random:crypto-rand` 0.6.0。

Android 和桌面分别通过 `androidMain` / `jvmMain` 引入对应的 JNI 产物。平台的 `HttpClient` 需要安装 Ktor 的 `WebSockets` 插件。

验证：
- 12 个单元测试，用内存中的假中继，覆盖：
  - BIP-340 测试向量 0 的公钥、NIP-19 规范中的示例；
  - 篡改内容、签名或公钥后验证失败；
  - 无响应和不肯关闭的中继只耗费一次超时。
- 只读的线上测试（`-PnostrLiveRelays=...`）：nos.lol、relay.primal.net、nostr.mom 各返回 20 条由其他客户端签名的事件，
  全部通过我们的 id 与签名校验；nos.lol 和 primal 支持按 `#I`（NIP-73 外部内容）查询 kind 1111。
  relay.damus.io 时好时坏（一次正常，一次握手失败），relay.nostr.band 无响应。
  **默认中继候选：nos.lol、relay.primal.net、nostr.mom，外加 relay.damus.io**；最终名单由快照 manifest 下发。
- 发布测试（`-PnostrLivePublish=true`，作者已确认，2026-11-20）：用一次性密钥向 nos.lol 与 relay.primal.net 发布一条
  明确标注为测试的 kind 1111 评论（`I` = `kototoro:test:p1`，PoW 8 位，实际 9 位），id 为
  `004360ab7743a7500504be7baf14d9e0ee6fb1b98f4cadf6f95b26621ff2c874`。结果：
  - 两个中继都接受了评论，且都能按 `#I` 读回，读回的事件签名校验通过；
  - 随后发布的 NIP-09 删除请求两个中继都接受了，再按 id 查询时两边都已删除。
- 由此确认：带 NIP-13 `nonce` 标签的事件、NIP-22 外部内容评论、NIP-09 删除，在这两个中继上都能完整走通。

### 10.1 P2 实现记录（2026-11-20）

- **事件格式**：`core-net` 的 `CommunityEventSchema`，客户端与索引器共用，负责生成与解析评分、评论、删除和举报。
  - 所有事件都带 NIP-32 标签 `["L","org.kototoro"]`、`["l","community/v1","org.kototoro"]`，
    索引器用一条 `#L` 查询就能取齐；不带该标签的事件一律不计入。
  - 评论与评分额外带 `title` 标签，用于排行榜显示；带 `content-warning` 标签表示剧透或 NSFW。
- **快照格式**：`core-domain` 的 `CommunitySnapshot.kt`，即清单、作品、排行榜、审核名单这几个数据类。
  分片取 32 位 FNV-1a 哈希的低字节，客户端算分片不需要引入加密库。
- **索引器模块 `community-indexer`**（JVM，`application`）：
  - `CommunityAggregator`：纯函数，同样的输入一定得到同样的输出；
  - `IndexerArchive`：按月存成 gzip 压缩的 JSONL，`seen` 记首次入档时间，作者无法伪造；加载时重新验签；
  - `RelayFetcher`：用 `until` 往前翻页，取维护者的 kind 10000 屏蔽列表；
  - `SnapshotWriter`：重建前清空 `v1/`，清单里给每个文件记 SHA-256；
  - `CommunityIndexerCli`：命令行入口，支持 `--full` / `--offline` / `--maintainer`。
- **聚合规则**（9 个测试覆盖）：
  - 作者可以按 id 删除自己的事件，或按评分地址撤回评分；别人发的删除无效。
  - 同一读者对同一部作品只计最新的一条评分，即使用了不同的标识。
  - 账号年龄按 `seen` 计算：7 天以内权重 0.3，30 天以内 0.7。
  - 工作量证明不达标的评分和评论不计入。
  - 一个账号在任意 24 小时内评分超过 200 条，这段时间的评分全部作废。
  - 评论被 3 个可信账号（权重 1.0）举报、包含屏蔽词、或作者被维护者屏蔽时，计入 `hidden_comments`，不计入评论数。
  - 排行榜分周、月、全部三个时间窗，再按 4 种媒介、是否 NSFW 拆开，共 24 个文件。
  - 相似作品：以每个读者自己的平均分为基准做余弦相似度，共同评分人数 ≥ 3，再乘 `co/(co+5)` 收缩。
- **线上试跑**（2026-11-20）：nos.lol、nostr.mom 正常返回；primal 连接超时，damus 握手失败，都只记作单个中继失败。
  目前没有 Kototoro 事件，生成了 25 个文件（24 张空排行榜加审核名单）以及清单。
- **部署**：workflow 模板在 `community-indexer/deploy/build-index.yml`，已通过 actionlint 检查；
  需要复制到 `community-index` 仓库的 `.github/workflows/`。
  它构建的是 Kototoro 的 `devel` 分支（可用 `kototoro-ref` 参数指定其他分支），所以本模块必须先合入。
- **已部署**（2026-11-20）：
  - `Kototoro-app/community-index`（公开）的 `main` 分支放 workflow 和 README，每 6 小时运行一次（cron `17 */6 * * *`）。
  - 首次手动运行（run 44733308571）全部步骤成功，索引器耗时 2 分 40 秒；当时还没有事件，`archive` 分支会在第一次有新事件时创建。
  - Pages 从 `gh-pages` 分支提供服务。**快照根地址：`https://kototoro-app.github.io/community-index/`**，
    客户端先读 `v1/manifest.json`。
  - 维护者屏蔽列表尚未配置：在该仓库设置变量 `MAINTAINER_NPUB` 后生效。

## 11. 待定

- ~~`KIND_RATING` 的具体编号~~：定为 34259（2026-11-20）。备选方案对比：
  - nips#879（kind 31985 书评，`d` 必须是 ISBN）：语义只限书籍；
  - nips#2115（kind 30016，1–5 星 JSON）：面向商品和商家。
  - 选 #1914 是因为它面向任意实体（包括书、影视等外部内容），其他实现了该提案的客户端也能读到 Kototoro 的评分。
  - 该提案的定义一节写“0 最低、1 最高”，必填标签一节却写“严格介于 0 与 1 之间”，两处矛盾；我们按定义一节，10 星存为 `1.0`。
  - 提案将来如有改动，只需同步 kind 和 `d` 的格式；作品归一依赖的是我们自己的 `i` / `k` 标签。
- 索引器部署（2026-11-20 决定）：workflow 放在 `Kototoro-app/community-index` 仓库内，检出公开的 Kototoro 仓库来构建
  `community-indexer`，用自带的 `GITHUB_TOKEN`（`contents: write`）推送到本仓库的 `archive` 和 `gh-pages` 分支，不需要 deploy key。
  该仓库必须公开：免费账户的 GitHub Pages 只支持公开仓库，公开仓库的 Actions 也不限分钟数；归档的事件本来就公开在中继上。
- 默认中继名单（P1 实测后确定）。
- 维护者审核密钥的保管方式（建议离线保存，只在本地 CLI 中签名）。
- 是否给 Kotatsu-Redo 用户提供数据导入（他们的服务器数据无法获取，大概率不做）。
