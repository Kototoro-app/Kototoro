# P2 每场景超时 + 真机验证记录（弱网/VPN 抖动发热）

更新时间：2026-08-26

设备：`ecd4369c`（product=warsaw，model=M332BF，arm64-v8a，Android 16 / API 36，MIUI）。
构建：`:app:assembleDebug`（arm64-v8a debug，`org.skepsun.kototoro.debug`）。

本文件记录 P2 批次在真机上完成的验证、发现并修复的问题、以及留待实验室环境完成的项目。

## 场景 A — 正常网络（封面加载与耗时）

- 测量方式：新增 `ContentCoverFetcher` 的耗时插桩（tag=`ContentCoverFetcher`，
  `ok source=DISK|NETWORK ms=…` / `cancelled ms=…` / `error=… ms=…`），配合 Coil 自带
  `RealImageLoader` 日志（💾 DISK / ☁️ NETWORK / 🧠 MEMORY_CACHE / 🏗 Cancelled / 🚨 Failed）。
- 清空 Coil 磁盘缓存（`/sdcard/Android/data/org.skepsun.kototoro.debug/cache/image_cache`，
  实测约 39MB / 3 个源封面）后重启，封面全部从网络拉取成功：
  `☁️ Successful (NETWORK)`，首页 BAOZIMH 2 张 + RAWKUMA 1 张。
- 从日志时间戳粗测：baozimh 封面 ≈ 1.2s（含 home 并发复用），rawkuma ≈ 0.5s；
  冷启动 + 清缓存情况下 8 秒内全部完成，无卡顿。
- 复用：重启后同一批封面 `💾 Successful (DISK)` / `🧠 MEMORY_CACHE`，磁盘/内存缓存生效。

## 关键发现 1 — ContentCoverFetcher 是死代码（已修复）

- **现象**：即使工厂在 Coil 组件里注册在 `OkHttpNetworkFetcherFactory` 之前，清空磁盘缓存后
  封面仍全部走 `OkHttpNetworkFetcher`（`☁️ Successful (NETWORK)`），`ContentCoverFetcher`
  一行日志都没有。
- **根因**：Coil 默认组件（`coil3.RealImageLoaderKt`）无条件注册 `coil3.map.StringMapper`
  （`String → coil3.Uri`）。组件解析顺序是 **先映射、后按类型解析 fetcher 工厂**
  （`ComponentRegistry.newFetcher` 按注册顺序 `isInstance` → 首个非空获胜）。请求的 String
  封面 URL 在工厂解析前已被映射成 `coil3.Uri`，因此 `Fetcher.Factory<String>` 永远不会被调用。
- **佐证**：代码库中其他能正常工作的工厂全部使用 `coil3.Uri`（FaviconFetcher、CbzFetcher）
  或自定义 model（ContentPageFetcher、TVBoxSearchCoverFetcher），只有 ContentCoverFetcher
  用了 `String`。
- **修复**：`ContentCoverFetcher.Factory` 改为 `Fetcher.Factory<CoilUri>`，`create` 按
  `data.scheme` 过滤 http/https，`imageUrl = data.toString()`。
- **回归测试**：`ContentCoverFetcherFactoryTest`（3 个用例）——http CoilUri+封面 extras
  被工厂接管；非 http 拒绝；无 `mangaSourceKey` extras 拒绝（保证其他源仍回落到默认 fetcher）。
- **修复后验证**：真机上工厂开始被调用（`factory reject: no imageClient for …` 出现），确认
  之前的 `create()` 完全未被调用。
- **重要边界**：只有 `ContentRepository.getImageClient()` 有覆盖实现的源
  （`MihonMangaRepository`、`AniyomiAnimeRepository`、`TsundokuNovelRepository`）真正走
  ContentCoverFetcher（单飞/磁盘/取消逻辑）；Js/Kotatsu/Tsuki/Legado/IReader/TVBox 等源的封面
  走 Coil 默认 fetcher（其单飞/磁盘由 Coil 内置保证），但批次 2 的失败抑制与 host 冷却拦截器
  在组件最前、对所有源生效。

## 关键发现 2 — Log.isLoggable 守卫吞掉插桩（已修复）

- `Log.isLoggable(tag, Log.DEBUG)` 默认返回 false（Android tag 默认级别是 INFO），
  用在插桩守卫里会把调试日志在几乎所有设备上静默关掉。已去掉该守卫，直接 `Log.d`。

## 场景 C — 完全断网 → 恢复

- 造离线：`svc data disable` + 手动关闭 WiFi 后 `Active default network: none`。
  （注意：本设备同时有移动数据 + WiFi，只关一个另一个会顶上来；MIUI 关闭 WiFi 还会自动补开
  移动数据，需逐项核对 `dumpsys connectivity`。）
- **离线、有磁盘缓存**：重启后封面 `💾 Successful (DISK)` 正常显示——缓存内容离线可用，进程稳定。
- **离线、清空缓存**：封面请求实际发起（经残留 VPN 隧道 TLS 失败 `SSLHandshakeException:
  connection closed`，约 2.4s 快速失败），无长挂起、无崩溃、无重试风暴。
- **恢复**：`svc wifi enable` + `svc data enable` → default 229 上线 → 重启 app，
  封面立即 `☁️ Successful (NETWORK)`——**无 10 分钟空白**（批次 2 已移除 URL 级 5xx 负缓存）。

## VPN 突断（传输层仍在）— 设备上恰好有真实 VPN

- 设备装有 `com.follow.yunniao`（Clash 类 VPN，tun0，代理 `127.0.0.1:17890`，带排除列表），
  全程 `VALIDATED`。
- 测试：`am force-stop com.follow.yunniao` → VPN 代理消失（CONNECTED extra: VPN 计数 1→0），
  default 保持 229（WiFi/移动兜底），app 存活，封面从内存缓存继续服务，**零失败、零重取风暴**。
- 这验证了 `NetworkState` 语义：VPN 丢失但存在 VALIDATED 兜底网络时 `allowAutomaticTraffic`
  保持 true，自动流量平滑延续。
- **注意**：暴力 force-stop 后重启 `com.follow.yunniao` 未自动重连隧道，需要用户在 VPN App 里
  手动点连接。设备网络（WiFi/移动数据）已恢复。

## 每场景超时（P2 代码交付）

- 现状：Coil 的 `OkHttpNetworkFetcherFactory` 共享全局 OkHttp 客户端，callTimeout=300s。
  弱网/VPN 黑洞时封面请求可长时间占住 8 路图片并发槽位 → 发热/卡顿放大。
- 真机测量：正常封面 0.5–2.6s。
- 实现：新增 `core/image/ImageNetworkClient.kt` 的 `buildImageNetworkClient(base, …)`，
  给 Coil 图片客户端单独加 `callTimeout = 20s`（`IMAGE_NETWORK_CALL_TIMEOUT_MS`），
  一次性构建后作为 `OkHttpNetworkFetcherFactory.callFactory`。Mihon 路径的
  `repo.getImageClient()` 由源自有客户端管理，未改动。
- 注意：拦截器里改 `chain.call().timeout()` **不生效**（OkHttp 的调用级 AsyncTimeout 在调用
  创建时绑定客户端配置），必须用客户端级 `callTimeout`。
- 回归测试：`ImageNetworkClientTest`（3 用例）——`setHeadersDelay(5s)` 慢响应在 300ms 内被
  取消（用 `setBodyDelay` 无效，因为 `execute()` 只等 header）；默认 20s；快响应不受影响。

## 遗留（需实验室/根权限环境）

- **未验证 VPN 陷阱**：隧道在但 `VALIDATED` 缺失（典型『坏 VPN』）→ 自动流量应停止、用户操作
  仍放行。本机 VPN 始终 VALIDATED，无法无根模拟；需实验室 VPN 或 root + `tc netem`。
- **Perfetto**：`adb shell perfetto -o /data/misc/perfetto-traces/trace.pb -t 60s sched freq idle_at_rails`
  采集任务/频率数据，结合 logcat（`ContentCoverFetcher`、`Coil`、`CloudflareHostCooldown`）对照
  封面耗时与并发。
- **WebView 会话设备级仪器测试**（`unified-cloudflare-solver-plan-2026-08.md` 未完成项）：
  建议用 `connectedDebugAndroidTest`（应用内 instrumentation 具备 UI 自动化权限，MIUI 屏蔽了
  `adb shell input`），覆盖真实窗口挂载与 Cookie 同步；这同时是 MIUI 设备上驱动 UI 的可靠途径。
- 封面/图片超时数值（20s）与 CF host 冷却（30s）可在真实弱网数据后再微调。
