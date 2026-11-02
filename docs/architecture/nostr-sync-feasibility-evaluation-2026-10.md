# Nostr 同步可行性评估（issue #535）

评估对象：[Kototoro-app/Kototoro#535](https://github.com/Kototoro-app/Kototoro/issues/535)
——「Optional Nostr-based library and reading progress sync」。提案内容：用 Amber（NIP-55 外部签名器）
登录已有 Nostr 身份，把书库/收藏/阅读进度加密后以 NIP-78 事件写到用户自选 relay，作为 WebDAV 之外的可选同步后端；
并且「每个作品一个 addressable 事件」以便多设备不互相覆盖。

协议侧事实（NIP-55/78/44/33/01、relay 限额等）见配套文档
[nostr-sync-research-2026-10.md](nostr-sync-research-2026-10.md)。本文只做**结合本仓库代码的可行性判断**。

## 结论

**动机成立，提案的实现形状与本仓库现有同步架构不匹配，不建议按 issue 原文实现。**

三点判断：

1. **「每个作品一个事件」是本提案最弱的一环**，不是最强的一环。提案把它当作解决冲突的手段，
   但本仓库的合并层**已经是逐条 LWW**（每个 manga 各自按 `updatedAt` 取新），冲突粒度从来不是问题；
   按作品拆事件带来的是 5000 条事件、5000 次签名 IPC、5000 次 relay 写入，而换来的增量收益可以用
   分片快照以低两个数量级以上的成本拿到。见下文「规模算术」。
2. **NIP-55「不导出私钥」与「隐私」目标互相拆台**。NIP-55 只提供签名/加解密/取公钥，**没有密钥派生能力**，
   因此应用只能用用户主身份（npub）发布——阅读清单会与用户的社交身份绑定，且 `d` 标签是明文。
   提案自己提出的「不要泄露阅读历史」在这个前提下只能靠 `d` 标签做 keyed hash 来部分缓解。
3. **relay 是尽力而为的存储，不是同步后端该有的持久性等级**。addressable 事件只保留最新版本
   （旧版 MAY 被丢弃）、公共 relay 常见 `messages_per_sec` 限速、`reject_future_seconds` 会直接拒绝
   时钟偏差设备写入、"OK" 不等于已持久化。而这批数据（书库+进度）是这个 App 里最不可重建的用户资产。

**建议**：不作为 WebDAV 的替代承诺，也不按 per-manga 事件做。若要做，先补两个**与后端无关**的能力
（传输接缝 + 增量脏集），再把 Nostr 作为 opt-in 的实验性 transport，用**分片快照事件**而非 per-item 事件。
详见下文「建议的落点」。

## 一、现状：本仓库现在到底怎么同步

三条**互不复用**同步路径（没有 `SyncBackend` 之类抽象，`grep "interface .*Sync"` 只有 ContentProvider 相关）：

| 路径 | 载体 | 触发 | 合并 |
| --- | --- | --- | --- |
| Google Drive 同步 | 单个 JSON 快照文件 `kototoro_sync_content_v3.json`，`uploadType=media` 整体 PATCH 覆盖 | 手动 / 打开 App（iOS 60s 冷却）/ WorkManager | 读远端 → 合并 → 整体回写 |
| WebDAV 备份/自动同步 | **ZIP 备份包** | Room `InvalidationTracker` 监听 history/favourites/categories/stats/preferences/bookmarks，30s 去抖 + 12h 最小间隔 | 备份包级别的合并恢复 |
| Android SyncAdapter | WebDAV 账号下的 ContentProvider（favourites/history） | 系统调度 | 逐表同步 |

关键代码事实：

- **载荷是单体快照**：`GoogleDriveSyncSnapshot` 一个类装下 content/categories/history/favourites/stats/feed/config/
  repositories/sourceStates/jsonSources/extensions（`core-domain/.../GoogleDriveSyncModels.kt:21`）。
- **合并已是逐条 LWW + 墓碑**：`GoogleDriveSyncMerger.compactSnapshot()` 按 `(mangaId)`、
  `(mangaId, categoryId)` 等分组后取 `maxOf(updatedAt, deletedAt)`（`GoogleDriveSyncMerger.kt:103-121`）；
  `history` 表本身就有 `updated_at` / `deleted_at` 列（`HistoryEntity.kt`），墓碑是持久化的。
- **身份键已经存在且与提案一致**：`ContentIdentityKeys.contentCompactKey(source, id, url, publicUrl)`
  产出 `projection:<source>:url:<url>`，正是 issue 说的「source identifier / canonical URL / content ID」。
- **快照里塞了扩展二进制**：APK/JAR/CS3 插件按 base64 内联，单个上限
  `MAX_SYNC_PACKAGE_SIZE_BYTES = 5MB`（`GoogleDriveSyncModels.kt:501`），且插件可有很多个。
  这是 Drive 同步能在新设备恢复扩展的能力来源，也意味着**这个载荷永远不可能整体搬到 Nostr**。
- **没有增量机制**：没有 dirty-set，没有 "since" 拉取，每次都是全量快照读-合并-写。
  这正是「用 Nostr 只发变化」这个想法真正诱人的地方——但它缺的层在**应用侧**，不在传输侧。

推论：issue 描述的「一个作品一个事件 → 一台设备不会覆盖另一台的无关改动」，在本仓库的合并层
**基本已经成立**。而快照方案里唯一真正的丢数据窗口，比直觉上要窄：两台设备都读到 R、各自写回
R+A' / R+B' 时，后写者会暂时覆盖前者；但 `mergeSnapshots()` 是**并集**合并（`content = local + remote`
再压缩），而每台设备的本地库始终保存着自己那份状态，所以 A 下一次同步会把 A' 重新合并回去——
**这个覆盖是暂时的、自愈的**。残余风险只有「A 在下次同步前卸载/丢失设备」这一种。
真正值得改善的是全量传输的带宽与耗时，而那用分片快照就能解决，不需要 per-item 事件。

## 二、提案逐条评估

| 提案要点 | 判断 | 依据 |
| --- | --- | --- |
| 外部签名器登录、不暴露私钥（NIP-55） | ✅ 技术上正确且是本提案最大亮点 | NIP-55 的 Content Resolver 路径可在后台响应已授权权限 |
| 每个作品一个 addressable 事件 | ⚠️ 过度设计 | 5000 收藏 → 5000 事件；合并粒度已经是逐条 LWW |
| NIP-78 作为应用数据存储 | ✅ 用途正确 | NIP-78 自述就是「bring your own database」/remoteStorage 类用途；但状态是 `draft` `optional` |
| 标题/URL 不进公开 tag、payload 加密 | ⚠️ 方向对，但 `d` 标签本身是明文 | 见下「硬约束与生态现实」 |
| 多 relay 冗余 | ⚠️ 双刃 | 冗余提升可用性，但每写一次是 N 份部分成功，且各 relay 可能保留**不同版本** |
| 作为 WebDAV 的替代 | ❌ 不建议承诺 | 数据范围（扩展/凭证）不同、三端可用性不同、持久性等级更低 |

## 三、规模算术（这是决定性的）

按本仓库自己的大规模假设 **5000+ 收藏**（`large-library-performance-handoff-2026-08.md:145`），
用 `SyncContent`/`SyncHistory`/`SyncFavourite` 的真实字段形状估算 JSON 体积：

| 粒度 | 单条体积 | 5000 作品总量 | 事件/请求数（每条一个事件） |
| --- | --- | --- | --- |
| 一个作品（content + history + favourite） | ≈ 843 B | ≈ 4.1 MiB | — |
| 100 / 500 / 2000 作品 | — | 85 KiB / 427 KiB / 1.7 MiB | 100 / 500 / 2000 |
| 事件 JSON 开销（id+pubkey+sig 十六进制 256 B、tags、created_at…） | +300~400 B/事件 | — | — |

对照 relay 的硬限额（主来源验证）：

- `nostr-rs-relay`：`max_event_bytes` 默认 **128 KB**，`max_ws_message_bytes` 默认 **128 KB**
  （`config.toml` 的 `[limits]` 段）。
- `strfry`：`maxWebsocketPayloadSize = 131072`（**128 KiB**），`maxFilterLimit = 500`（单次拉取最多 500 事件）。
- 公共 relay 建议开启 `messages_per_sec`（示例值 5）。

于是：

- **单体快照事件装不下**：500 作品已经 427 KiB > 128 KB。任何「一次一个 NIP-78 事件」的写法，
  在中型书库上就会被 relay 直接拒收。→ 必须分片。
- **per-manga 事件在 5000 收藏下是 5000 次写入**：按 `messages_per_sec = 5` 约 **17 分钟**纯写入；
  叠加 NIP-55 每事件 2 次 IPC（`nip44_encrypt` + `sign_event`）= **10000 次签名器往返**。
  这不是「网络慢」，是这个形状本身不可行。
- **分片快照只要十来个事件**：同一份 5000 作品数据实测 gzip 压缩比 **3.3×**（高熵随机 URL，属上界；
  同构域名/重复前缀多的真实库压缩比更高），4.0 MiB → 约 1.2 MiB，按 120 KiB/事件分片约 **11 个事件**。
  相比 per-manga 的 5000 个，少约 2.5 个数量级，而且**完全复用现有 `GoogleDriveSyncMerger`**
  （relay 不需要理解分片语义，客户端拼装后照常合并）。

  *测量方法*：用 `SyncContent`/`SyncHistory`/`SyncFavourite` 的真实字段名与取值长度生成 5000 条记录，
  `json.dumps(separators=(',',':'))` 后 `gzip -9`；随机串长度与真实标题/URL 相当，因此是保守估计。

结论：在这个 App 的数据规模下，**「每作品一事件」不是精度换成本，而是拿约两个半数量级的成本换一个
合并层已经提供的能力**。

## 四、硬约束与生态现实

### 1. NIP-55 没有密钥派生 → 隐私与「无账号」目标互斥

NIP-55 的权限类型是 `get_public_key` / `sign_event`（可带 `kind`）/ `nip44_encrypt` / `nip44_decrypt` 等，
**没有任何「派生」类权限**。这意味着：

- 应用无法从用户主身份派生出「Kototoro 专用子身份」→ 所有事件挂在用户**社交主 npub** 下；
- relay 和任何知道该 npub 的人都看得到「这个人向 kind 30078 写了 5000 条事件、什么时候写」，
  即使内容全加密，**行为与规模元数据仍然泄露**，且可与其 Nostr 社交活动直接关联；
- 想解除关联只能让用户单独导入一个 nsec 给本 App（放弃 NIP-55 的全部好处，并重新引入 secp256k1 依赖）。

若坚持 NIP-55，唯一可行的缓解是：`d` 标签用 `HMAC(用户密钥, identity)` 之类**keyed hash**，
让正文与事件一一对应但仍不可枚举；代价是 `d` 无法被其他客户端解释，且换密钥即全量失效。

**另一个隐性代价是依赖体积。** 走 NIP-55 时加解密与签名都在签名器里，App 侧不需要任何密码学库
（`sign_event` / `nip44_encrypt` / `nip44_decrypt` 三个权限解决）；但一旦为了「独立身份」改成自行持有 nsec，
就需要 secp256k1 上的 ECDH，而平台 provider 不提供这条曲线（`KeyAgreement` 只覆盖 NIST 曲线），
要么引入 BouncyCastle 级别的依赖，要么引入带 4 个 ABI 的原生库——这与 `CONTRIBUTING.md`
「默认不加依赖、APK 体积敏感」直接冲突。（NIP-44 的 ChaCha20 部分在平台 `Cipher` 上也需要较高 API 级别，
本项目 minSdk 26，低版本需另行确认。）

也就是说：**NIP-55 换来了零依赖，代价是把身份绑死在主 npub；自行持钥换来了可分离身份，代价是依赖与体积。
两者不能同时要。**

### 1b. 三端一致性：NIP-55 是 Android 专属，跨平台签名器又把密码学依赖带回来

NIP-55 是 Android 的外部签名器协议（Intent + Content Resolver）。iOS/桌面上对应物是 NIP-46「bunker」远程签名器，
但它要求**客户端自己持有一对 `client-keypair`**，并用 NIP-44 与签名器互相加密通信
（`remote-signer-pubkey` 与 `user-pubkey` 分离）。也就是说：Android 靠 NIP-55 省掉的加解密，
在 iOS/桌面靠 NIP-46 又会原样加回来——而 `core-net` 是 Android/iOS 共享的，现有 Drive 同步就是三端共用同一协议。
结果是：要么 Nostr 同步变成 Android 独有功能（产品上很难解释），要么三端都背密码学依赖。

### 2. `d` 标签是明文，addressable 事件的历史会被丢弃

`kind 30078` 属 addressable：同一 `(kind, pubkey, d)` **只保留最新版本，旧版 MAY 被丢弃**
（NIP-01 kind 段）。两个后果：

- **删除与撤销不可追溯**：墓碑只能靠「新版本覆盖」表达，relay 不留旧版本，出问题无法事后审计。
- **时钟即真相**：`nostr-rs-relay` 默认 `reject_future_seconds = 1800`，
  时钟快于 relay 半小时以上的设备写入会被**直接拒绝**；时钟慢的设备写入会被已有更新版本压掉。
  手机时钟漂移、恢复出厂、跨时区场景都不罕见，而后果是该设备的改动**无法传播**：
  写入被拒或被压掉后 relay 不留任何版本，其他设备永远看不到这次更新，
  除非本机在时钟修正后重新写入。本仓库现有方案是整体快照回写，
  合并是并集，失败一次只是这次上传没生效，下一轮自然重试——容错边界更宽。

### 3. relay 的持久性等级低于这批数据的价值

书库 + 阅读进度是本 App 最不可重建的资产（源站会关站、作品会下架）。现有后端里：
WebDAV = 用户自己的存储，能不能用取决于用户；Google Drive = 商业 SLA 的 `appDataFolder`。
公共 Nostr relay = 志愿者运营，可以随时下线、清理策略、限速、要求付费。**"多写几个 relay" 是概率上的
缓解，不是 SLA**——而用户对「同步」的期望是 SLA。

另外，多 relay 写入是逐事件部分成功：客户端要自己维护「哪些事件被哪些 relay 接受」的账本，
否则会出现「A relay 有新进度、B relay 停留在旧版本」，读侧拼装时才发现不一致。这套记账逻辑
本仓库现在完全没有。

### 4. 生态现状：NIP-78 几乎没有落地案例

GitHub 检索（2026-10-20）结果：

- NIP-78 做应用数据的库，公开可查的最多是 `ShinoharaTa/nostr-key-value`（★9）这类小工具；
- 最接近本提案的先例是 `chr15m/watch-later`（★3，单文件 PWA）：YouTube 观看列表 + 进度加密同步到 relay，
  **但它要求用户自己保管/拷贝 nsec**（README 明确「Your nsec key can be copied to sync with other devices」），
  而不是外部签名器；
- 没有任何漫画/动画阅读器实现过 Nostr 同步（Mihon/Tachiyomi/Komikku/Kotatsu 侧也未检索到相关提案）。

含义：NIP-78 是 `draft` 状态、落地极少，把用户的书库押在一个**尚未形成生态的草案**上，
长期维护风险高于「自己写一个 WebDAV/Drive 传输」。现有唯一能跑的先例选择了自行持钥——
这不支持「NIP-55 是主流做法」的假设。

## 五、建议的落点

如果确实想推进（不建议作为 issue 原文那样的大改，建议按下面顺序）：

1. **先抽传输接缝**（与 Nostr 无关，独立收益）。
   现有三条路径互不复用。抽一个「取回快照字节 / 写回快照字节 + 版本元数据」的最小接口，
   让 Drive、WebDAV、Nostr 都实现它。这一步让第 3 步的成本从「写一个新同步后端」降到「写一个传输实现」。
2. **再补增量脏集**（与 Nostr 无关，独立收益）。
   `history`/`favourites`/`stats` 已有 `updated_at`/`deleted_at`，可派生 `since` 查询。
   有了脏集，Drive 的全量上传问题也一起解决，而且不依赖任何 Nostr 语义。
3. **最后才是 Nostr transport**，且形状如下：
   - **分片快照事件**，不是 per-manga 事件：1 个 `d=kototoro.manifest` 事件描述分片列表与版本，
     N 个 `d=kototoro.shard.<i>` 事件装载压缩后的快照片段；
   - **只同步书库状态**，明确不含扩展二进制（扩展仍走 Drive/WebDAV），并在 UI 说清这个边界；
   - **`d` 标签 keyed hash**，避免公开阅读清单；
   - **不承诺 NIP-55-only**：Android 走 NIP-55；iOS/桌面要么用平台密钥库持有专用 nsec、
     要么走 NIP-46——注意后者会把 NIP-44 加解密带回客户端（见 1b），是明确的取舍而非免费选项。
     三端共享 `core-net` 的协议层（现有 Drive 同步已经是 Android/iOS 共用 `core-net` 的先例）；
   - 作为**实验性 opt-in** 上线，默认关闭，UI 文案不做「替代 WebDAV」的承诺。
4. **反向验证**：先写一个离线实验，把真实快照压缩分片、跑一遍「写 N relay → 读回 → 合并」的往返，
   量一下事件数/体积/耗时，再决定值不值得做。这个实验成本远低于实现，却能直接回答本次评估的核心问题。

不建议的部分（明确写清楚，避免后续反复）：per-manga addressable 事件、把扩展载荷放进 relay、
把 Nostr 声明为 WebDAV 的替代方案。

## 六、如果要回复 issue

建设性回复要点（简短版）：

- 认可动机：加密存储到第三方基础设施、无账号、无自建服务器，对这类内容（阅读癖好本身就是敏感元数据）
  确实是真需求，感谢提案。
- 指出关键约束：NIP-55 无派生权限 → 只能用主身份 + 明文 `d` 标签，隐私目标要靠 keyed hash 才对得上；
  128 KB/事件与 `maxFilterLimit = 500` 的 relay 现实决定了载荷必须分片，「每作品一事件」在 5000 收藏量级
  是 5000 次写入 + 10000 次签名 IPC。
- 说明现状：本项目已经做了逐条 LWW 合并与内容身份归并，per-item 事件的冲突优势在这里已经具备；
  真正缺的是增量传输层。
- 给出可接受的形状：分片快照 + 传输抽象，实验性 opt-in，不含扩展载荷。

## 来源

代码（本文所有代码事实）：

- `core-domain/src/commonMain/kotlin/org/skepsun/kototoro/sync/google/data/model/GoogleDriveSyncModels.kt`
  —— `GoogleDriveSyncSnapshot`、`SyncContent`、`MAX_SYNC_PACKAGE_SIZE_BYTES`
- `core-domain/src/commonMain/kotlin/org/skepsun/kototoro/sync/google/domain/GoogleDriveSyncMerger.kt` —— 逐条 LWW
- `core-domain/src/commonMain/kotlin/org/skepsun/kototoro/core/model/ContentIdentityKeys.kt` —— 内容身份键
- `core-net/src/commonMain/kotlin/org/skepsun/kototoro/sync/google/data/GoogleDriveSyncApi.kt` —— 单文件整体上传
- `app/src/main/kotlin/org/skepsun/kototoro/backups/ui/webdav/DataSyncManager.kt` —— Room 变更驱动 + 30s/12h
- `app/src/main/kotlin/org/skepsun/kototoro/sync/google/domain/GoogleDriveSyncRepository.kt` —— 扩展载荷 base64 内联
- `docs/architecture/large-library-performance-handoff-2026-08.md:145` —— 5000+ 收藏的大规模假设
- `docs/architecture/ios-google-drive-sync.md` —— iOS 复用 `core-net` 同一快照协议

协议与 relay（与 [nostr-sync-research-2026-10.md](nostr-sync-research-2026-10.md) 互见）：

- [NIP-01 事件与 kind 区间](https://github.com/nostr-protocol/nips/blob/master/01.md) —— addressable 只保留最新版本
- [NIP-55 Android 签名器](https://github.com/nostr-protocol/nips/blob/master/55.md) —— 权限类型清单（无派生）
- [NIP-78 应用数据](https://github.com/nostr-protocol/nips/blob/master/78.md) —— kind 30078、`draft`、AUTH 建议
- [nostr-rs-relay config.toml](https://github.com/scsibug/nostr-rs-relay/blob/master/config.toml) —— 128 KB、`reject_future_seconds = 1800`、`messages_per_sec`
- [strfry.conf](https://github.com/hoytech/strfry/blob/master/strfry.conf) —— `maxWebsocketPayloadSize = 131072`、`maxFilterLimit = 500`
