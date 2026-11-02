# Nostr 作为应用数据同步后端的协议调研（2026-10）

配套文档：[nostr-sync-feasibility-evaluation-2026-10.md](nostr-sync-feasibility-evaluation-2026-10.md)（结合本仓库代码的可行性判断）。
本文只记录**协议与 relay 实现层面的事实**，每条尽量回到规范或实现源码本身，便于日后复核。

调研时间：2026-10-20。所有规范引用自 `nostr-protocol/nips` 仓库 master 分支。

## 1. 事件模型基础（NIP-01）

kind 分区（NIP-01「Kinds」段）：

| 区间 | 类别 | relay 义务 |
| --- | --- | --- |
| `1000<=n<10000`、`4<=n<45`、`n=1,2` | regular | 全部存储 |
| `10000<=n<20000`、`n=0,3` | replaceable（按 pubkey+kind 唯一） | 只 MUST 存最新，旧版 MAY 丢弃 |
| `20000<=n<30000` | ephemeral | 不存 |
| `30000<=n<40000` | **addressable**（按 kind+pubkey+`d` 唯一） | 只 MUST 存最新，旧版 MAY 丢弃 |

两条对同步后端至关重要的规则：

- **同一 timestamp 时保留 id 字典序最小者，另一个丢弃**（"the event with the lowest id … should be retained"）；
- **"latest" 由 `created_at` 决定**，客户端 `REQ` 时 relay SHOULD 只返回最新一版。

结论：`kind 30078` 是**一个只保留最新值的 last-write-wins 寄存器，没有历史、没有 compare-and-set**。
写入方拿到成功响应也不代表状态被长期保住（relay 只保证"最新那版被存"，而"最新"按 `created_at` 判）。

## 2. NIP-78：应用数据

规范自述（原文）：目标是 enable [remoteStorage](https://remotestorage.io/)-like capabilities for custom
applications that do not care about interoperability，即 "bring your own database"，用户自己填一个 relay 当存储。

- 状态标记：**`draft` `optional` `relay`**（非 final）。
- `kind 30078`：addressable，`d` 标签是"某个应用名与上下文的引用——或任意字符串"，`content` 与其它 tags 格式不限。
- `kind 78`：regular event，供需要存多条同类数据的应用使用，建议加唯一 tag（连 `d` 也可）做分组。
- **AUTH**：因为数据私有，relay **SHOULD** 要求 NIP-42 AUTH、且只把事件发给以同一 pubkey 认证的所有者。

要点：NIP-78 只规定"这个 kind 可以用来放应用私有数据"，**完全没有规定草案里"每作品一事件"这类逐条语义**；
那属于应用自己的约定。同时 AUTH 只是 SHOULD —— 不实现 AUTH 的 relay 会把 30078 事件当普通事件公开可读，
此时**只有 `content` 被加密保护，`tags`/`created_at`/规模与频率都是公开的**。

## 3. NIP-55：Android 外部签名器

应用不需要接触私钥，把签名/加解密委托给签名器 App（Amber 是常见实现，已确认其作为 NIP-55 签名器存在）。

两种传输：

- **Intent**：启动签名器界面，用户确认；
- **Content Resolver**：`content://<package>.<TYPE>`（如 `SIGN_EVENT` / `NIP44_ENCRYPT`），
  可在**后台**响应，但**仅限用户此前选择"记住"的权限**。

方法表（规范原文，节选）：

| 方法 | 载荷 | 额外参数 | 结果 |
| --- | --- | --- | --- |
| `get_public_key` | 空 | `permissions` | `user-pubkey` |
| `sign_event` | event JSON | `current_user` | 签名 + 签名后事件 |
| `nip44_encrypt` | 明文 | `pubkey`、`current_user` | 密文 |
| `nip44_decrypt` | 密文 | `pubkey`、`current_user` | 明文 |
| `nip04_encrypt` / `nip04_decrypt` | 明文/密文 | 同上 | 密文/明文 |
| `decrypt_zap_event` | event JSON | `current_user` | 解密后事件 |

权限可以按方法预授权，`sign_event` 还能限定 `kind`，例如
`[{"type":"sign_event","kind":22242},{"type":"nip44_decrypt"}]`。

**关键限制：方法表里没有任何派生（derive）类方法。** 客户端只能拿到 `user-pubkey` 并让签名器用
**用户主身份**签名；不能要求签名器派生"本 App 专用子密钥"。因此用 NIP-55 就意味着事件全部挂在用户主 npub 下，
可与其 Nostr 社交身份直接关联。

另外：每个事件若都要经签名器，就是每个事件 **2 次 IPC**（`nip44_encrypt` + `sign_event`）。

## 4. NIP-44：加密载荷

- **版本 2** 的组合：`secp256k1 ECDH → HKDF-SHA256 → 自定义 padding → ChaCha20 → HMAC-SHA256 → base64`。
- 会话密钥：ECDH 结果为 IKM，`salt = "nip44-v2"` 做 HKDF-extract；再用 `nonce` 做 HKDF-expand 取 76 字节
  切成 `chacha_key`(0..32) / `chacha_nonce`(32..44) / `hmac_key`(44..76)。
- **Padding**：与 2 的幂相关，最小 padding 后长度 32 字节；明文长度前缀 ≤65535 时 2 字节（u16），更大时用 6 字节扩展格式。
- 设计取舍（规范自述）：ChaCha 而非 AES 是为速度；HMAC-SHA256 而非 Poly1305 是"多项式 MAC 太易伪造"；
  自定义 padding 而非 padmé 是为了小消息的泄露更小。

要点：**v2 必须做 secp256k1 上的 ECDH**。JVM/Android 平台 provider 的 `KeyAgreement` 只覆盖 NIST 曲线，
不含 secp256k1，因此"应用自己加密"这条路线必然引入额外密码学实现（BouncyCastle 级别或原生库）。

## 5. NIP-46：跨平台远程签名

NIP-46（Nostr Remote Signing，bunker）是 NIP-55 之外、可用于 iOS/桌面的方案：客户端通过 relay
与"bunker"/签名器做 2-way 通信。但它要求：

- 客户端自己持有一对 **`client-keypair`**；
- 用该密钥对 `p`-tag 并**加密**发给 `remote-signer-pubkey`，签名器同样加密回包；
- `remote-signer-pubkey` 与 `user-pubkey` 分离，连接后要 `get_public_key`。

要点：**NIP-46 客户端自身仍需要 NIP-44 加解密能力**。所以"用 NIP-55 就能零密码学依赖"这个收益是 Android 独占的；
一旦要覆盖 iOS/桌面，密码学依赖照样回到客户端。

## 6. Relay 现实：限额、时钟、费率

### 6.1 单事件体积

| 来源 | 参数 | 值 |
| --- | --- | --- |
| nostr-rs-relay `config.toml` `[limits]` | `max_event_bytes`（注释：默认 128 KB） | 131072 |
| 同上 | `max_ws_message_bytes`（默认 128 KB） | 131072 |
| strfry `strfry.conf` | `maxWebsocketPayloadSize` | 131072 |
| NIP-11 `limitation` 字段**示例值** | `max_message_length` | 16384 |
| NIP-11 `limitation` 字段**示例值** | `max_content_length` | 8196 |

注意最后两行是**规范里给出的示例**（说明这些字段可能存在、且可能很小），不是默认值；
但它说明"8196 字节 content 上限"这种配置在现实中是存在的。综合看：**128 KB 是常见上界，16 KB 也不罕见**。

### 6.2 时钟

- nostr-rs-relay：`reject_future_seconds = 1800`（默认值，注释建议 30 分钟）——**拒绝时间戳过于超前的写入**。
- NIP-11 `limitation` 提供 `created_at_lower_limit` / `created_at_upper_limit` 字段，说明 relay 普遍会对历史/未来时间做限制。

含义：设备时钟误差超过 30 分钟即**写入被拒**；时钟偏慢则写入会被已有更新版本压掉且不可见。

### 6.3 写入速率与拉取上限

- nostr-rs-relay：`messages_per_sec`（注释：public relay 强烈建议设置，示例 5）、`subscriptions_per_min`（建议 10）。
- strfry：`maxFilterLimit = 500`（单个 filter 一次最多返回 500 事件）。
- NIP-11 `limitation`：`max_subscriptions`、`max_limit`、`default_limit`、`max_event_tags` 等字段。

含义：批量写入会被限速、批量拉取要分页；"写 5000 条事件"在这些约束下是分钟级操作。

### 6.4 反滥用：PoW / 付费 / 限制写入

- NIP-13 Proof of Work：`draft` `optional` `relay`，`difficulty` = NIP-01 id 的前导零位数，用于 anti-spam。
- NIP-11 `limitation` 提供 `min_pow_difficulty`、`payment_required`、`auth_required`、`restricted_writes`。

含义：relay 可能要求为每个事件挖 PoW 或要求付费/认证才能写。移动端为每条记录挖 PoW 在电量与时间上都不可行。

### 6.5 删除

- NIP-09 Event Deletion Request：`draft` `optional` `relay`，`kind 5`，用 `e`/`a` tag 列出要删的事件，
  **是"请求"**；规范未承诺 relay 必然执行。

含义：addressable 事件本身不需要删除旧版本（旧版本来就被丢弃），但"删除某条同步数据"只能靠写一个更新的版本表达，
能力上够用，代价是没有审计轨迹。

## 7. 生态与先例（GitHub 检索，2026-10-20）

| 项目 | 星 | 与本提案的关系 |
| --- | --- | --- |
| `ShinoharaTa/nostr-key-value` | 9 | NIP-78 的 JS/TS KV 封装库（"NIP-78 类似 remoteStorage"） |
| `chr15m/watch-later` | 3 | YouTube 观看列表 + 播放进度加密同步到 relay（PWA 单文件），**要求用户自行拷贝 nsec** |
| `codeswot/ZapBook` | 2 | "Nostr-native social reading"，社交读书而非书库同步 |

未检索到任何漫画/动画阅读器（Mihon/Tachiyomi/Komikku/Kotatsu 等）实现或提案 Nostr 同步。

## 8. 未能验证 / 存疑

- **Amber 对 `kind 30078` 的具体授权交互**：仅确认 Amber 是 NIP-55 签名器的常见实现，
  未逐条核对其对 `sign_event` 带 `kind` 限制、以及 Content Resolver 后台响应的实际行为。
- **各主流公共 relay 是否默认开启 NIP-42 AUTH-gated 读取**：规范只是 SHOULD。
  这点直接决定 `tags` 元数据是否公开，需要在目标 relay 上实测。
- **ChaCha20 在 Android 平台 `Cipher` 的最低 API 级别**：与本文主线无关（NIP-55 路线不需要），
  但若要自行持钥实现 NIP-44，需要在项目 minSdk 26 上实测确认。
- 没有在真实 relay 上做写入/限速/时钟偏差的实测；上述限额均来自实现源码默认值与规范示例。

## 9. 来源

规范（`nostr-protocol/nips` master）：

- [NIP-01 基础协议与 kind 区间](https://github.com/nostr-protocol/nips/blob/master/01.md)
- [NIP-09 删除请求](https://github.com/nostr-protocol/nips/blob/master/09.md)
- [NIP-11 relay 信息文档与 limitation 字段](https://github.com/nostr-protocol/nips/blob/master/11.md)
- [NIP-13 工作量证明](https://github.com/nostr-protocol/nips/blob/master/13.md)
- [NIP-44 加密载荷](https://github.com/nostr-protocol/nips/blob/master/44.md)
- [NIP-46 远程签名](https://github.com/nostr-protocol/nips/blob/master/46.md)
- [NIP-55 Android 签名器](https://github.com/nostr-protocol/nips/blob/master/55.md)
- [NIP-78 应用数据](https://github.com/nostr-protocol/nips/blob/master/78.md)

relay 实现：

- [nostr-rs-relay config.toml](https://github.com/scsibug/nostr-rs-relay/blob/master/config.toml)
- [strfry strfry.conf](https://github.com/hoytech/strfry/blob/master/strfry.conf)

先例：

- [ShinoharaTa/nostr-key-value](https://github.com/ShinoharaTa/nostr-key-value)
- [chr15m/watch-later](https://github.com/chr15m/watch-later)
