# 弱网 / VPN 卡顿与发热一手资料研究（2026-08）

更新时间：2026-08-26

## 研究问题

最近版本出现两类看似冲突的反馈：正常网络下应用无明显卡顿；VPN 节点不稳定或网络较差时，卡顿与发热明显。
本文只核对 Android、Coil、OkHttp、Mihon/Komikku 的官方文档、源码和 issue，并将其与 Kototoro 当前实现对照；
不把尚未采集到的设备性能数据写成既定事实。

## 结论摘要

1. **“存在 Wi-Fi / 蜂窝 / VPN transport”不等于互联网可用。** Android 官方将
   `NET_CAPABILITY_VALIDATED` 定义为系统最近一次探测确认公共互联网可达的信号，并建议多数应用跟踪自身的
   default network。Kototoro 当前只检查 transport，VPN 隧道仍存在但节点已不可用时也会继续报告在线。
2. **首页两个展示请求本身不是错误。** 封面卡片和全景背景需要不同的内存缓存变体，但可以共享同一个
   `diskCacheKey`。恢复“第二请求直接失败”的互斥会再次造成二者只能显示一个。
3. **当前 `ContentCoverFetcher` 没有得到 Coil 3.4 的网络磁盘缓存、同飞合并与取消语义。** 它在自定义
   `Fetcher` 中直接同步调用 OkHttp `execute()`，然后返回普通流式 `SourceFetchResult`。请求虽然设置了
   `diskCacheKey`，但该 Fetcher 没有读写 Coil `DiskCache`；源码中传入的 `cacheDir` 也没有被使用。
4. **给 OkHttp `Dispatcher.maxRequests` 调小不能限制这些同步封面请求。** OkHttp 5.4 的并发上限只在异步
   `enqueue` 队列晋升时检查；同步 `execute()` 只登记到 `runningSyncCalls`，不会等待 Dispatcher 许可。
5. **Coil 3.4.0 已提供与本问题精确匹配的官方能力，但默认关闭。** `DeDupeConcurrentRequestStrategy` 按
   `diskCacheKey` 合并同飞网络请求。首页封面和全景请求正好共享该键；但当前自定义 Fetcher 绕过了
   `NetworkFetcher`，仅在 `OkHttpNetworkFetcherFactory` 上开启策略并不能覆盖它。
6. **默认启用的 MIHON Cloudflare 路径会放大弱网成本。** 该路径可在 OkHttp 拦截器内为每次挑战向主线程投递
   一个离屏 WebView，并阻塞调用线程最多 30 秒。Komikku 上游实现也有这一边界，类内没有按 host 的
   single-flight 或失败冷却；Kototoro 另有统一 resolver 的串行/冷却，不应与 MIHON 专用路径混为一谈。
7. Android 官方明确指出，频繁、零散或持续网络活动会保持无线电处于高功耗状态。因此“正常网络很快结束、
   VPN 抖动时大量重复/长尾请求重叠”是对卡顿发热反馈的高可信解释，但仍需 Perfetto、FrameTimeline 与
   OkHttp/Coil 事件计数完成设备侧因果验证。

## 1. Android 网络状态：应观察 default network 与 VALIDATED

Android 官方说明：所有应用都有系统选择的 default network；普通新建连接会使用它。VPN 既可能是应用的
default network，也可能叠加 Wi-Fi、蜂窝等 transport。transport 只是承载介质，不能代表实际联网能力；
`NET_CAPABILITY_VALIDATED` 表示系统探测确认网络能够访问公共互联网，是系统最接近“实际联网”的判断。
但官方也同时提醒，即使已验证，目标站点仍可能受 IP 过滤、差信号或突发断网影响，因此它不是单个业务请求
必然成功的保证。

来源：

- [Android：Read network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)
- [Android：NetworkCapabilities / NET_CAPABILITY_VALIDATED](https://developer.android.com/reference/android/net/NetworkCapabilities#NET_CAPABILITY_VALIDATED)
- [Android：registerDefaultNetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager#registerDefaultNetworkCallback(android.net.ConnectivityManager.NetworkCallback))

官方还区分了两类 callback：`registerDefaultNetworkCallback` 只跟踪当前应用实际使用的默认网络；带
`NetworkRequest` 的 `registerNetworkCallback` 会看到所有匹配网络。多数应用只需要前者。能力变化应通过
`onCapabilitiesChanged` 处理；仅监听 `onAvailable` / `onLost` 会错过“VPN 仍存在但失去验证”等状态变化。

Kototoro 当前 `NetworkState`：

- 用 Wi-Fi、蜂窝、以太网、VPN transport 构造普通 `NetworkRequest`；
- `isOnline` 只判断上述任一 transport 存在；
- callback 未实现 `onCapabilitiesChanged`；
- 因而 VPN transport 存在、节点却无法访问互联网时仍会继续允许网络加载。

适用边界：`VALIDATED` 应优先用于暂停或降级自动轮播预取、后台刷新等非关键流量；不宜把它作为所有用户主动
请求的绝对门禁，否则可能误伤“系统验证端点不可达、但目标站点可达”等场景。

## 2. 网络活动为何会发热

Android 官方将无线网络活动列为显著耗电来源。建立连接会使无线电进入高功耗状态，传输结束后还存在降功耗的
tail time；零散请求会反复唤醒或持续保持无线电活跃。官方建议减少重复下载、合批请求、在请求前判断连接、
复用连接，并缓存已经下载的数据。

来源：

- [Android：Optimize network access](https://developer.android.com/develop/connectivity/network-ops/network-access-optimization)
- [Android：Minimize the effect of regular updates](https://developer.android.com/develop/connectivity/minimize-effect-regular-updates)
- [Android：Avoid unoptimized downloads](https://developer.android.com/develop/connectivity/avoid-unoptimized-downloads)
- [Android：Troubleshoot network issues](https://developer.android.com/develop/connectivity/troubleshoot-network-issues)

据此可以作出有来源支持的**机制推论**：正常网络下，重复请求、TLS/代理握手和图片解码很快完成，用户可能无感；
VPN 抖动时，同样的请求会长时间占用线程、连接和无线电，重试与 Cloudflare WebView 又可能重叠，因此更容易
同时表现为卡顿和发热。该推论解释了反馈差异，但不是设备实测结果。

## 3. Coil 3.4：首页双展示应共享下载，而不是拒绝第二个请求

### 3.1 官方 issue 与 3.4.0 的解决方案

Coil 官方 issue #1461 的原始场景就是：同一屏幕出现多张相同图片时产生多个并行网络请求，希望后续请求等待
首个请求并复用结果。Coil 3.4.0 最终加入 `ConcurrentRequestStrategy`；实验性的
`DeDupeConcurrentRequestStrategy` 会按同一个 key 合并同飞请求：首个调用者执行下载，成功后等待者继续并从
缓存读取；失败或取消时只唤醒一个等待者重试。该能力默认仍为 `UNCOORDINATED`。

来源：

- [Coil issue #1461：Avoid multiple parallel network requests for the same URL](https://github.com/coil-kt/coil/issues/1461)
- [Coil 3.4.0 changelog](https://github.com/coil-kt/coil/blob/3.4.0/CHANGELOG.md#340---february-24-2026)
- [Coil PR #3326：ConcurrentRequestStrategy](https://github.com/coil-kt/coil/pull/3326)
- [Coil API：DeDupeConcurrentRequestStrategy](https://coil-kt.github.io/coil/api/coil-network-core/coil3.network/-de-dupe-concurrent-request-strategy/index.html)
- [Coil 3.4.0 `NetworkFetcher` 源码](https://github.com/coil-kt/coil/blob/3.4.0/coil-network-core/src/commonMain/kotlin/coil3/network/NetworkFetcher.kt)

`NetworkFetcher.fetch()` 以 `diskCacheKey` 进入并发策略，键值为显式 `options.diskCacheKey`，没有显式值时才退回
URL。Kototoro 首页封面卡片和全景背景使用不同 `memoryCacheKey`，但使用相同 `diskCacheKey`，这正是官方机制
希望支持的模型：**两个不同的解码/展示结果都成功，但原图只下载一次。**

### 3.2 自定义 Fetcher 不会自动得到 NetworkFetcher 的磁盘缓存

Coil 的扩展文档把 `Fetcher` 定义为负责把数据取回为 `ImageSource` 或 `Image` 的组件。Coil 3.4 的
磁盘缓存读写逻辑位于网络 `NetworkFetcher` 内部：它自行打开 snapshot、写 response body、提交 editor，随后
返回带 `diskCacheKey` 的文件型 `ImageSource`。通用 `EngineInterceptor` 负责内存缓存和解码，但不会把任意
自定义 Fetcher 返回的网络流自动写入 Coil 网络磁盘缓存。

来源：

- [Coil：Extending the image pipeline / Fetchers](https://coil-kt.github.io/coil/image_pipeline/#fetchers)
- [Coil Fetcher API](https://coil-kt.github.io/coil/api/coil-core/coil3.fetch/-fetcher/)
- [Coil 3.4.0 `EngineInterceptor` 源码](https://github.com/coil-kt/coil/blob/3.4.0/coil-core/src/commonMain/kotlin/coil3/intercept/EngineInterceptor.kt)
- [Coil issue #2430：仅设置 diskCacheKey 不会手工写入磁盘缓存](https://github.com/coil-kt/coil/issues/2430)

Kototoro 的 `ContentCoverFetcher` 直接返回基于 `response.body.source()` 的 `SourceFetchResult`，没有打开或写入
`imageLoader.diskCache`，构造参数 `cacheDir` 也完全未使用。因此其中“Coil manages its own disk caching”这条
注释与 Coil 3.4 实际源码不一致。其后果是：

- 首页请求设置的相同 `diskCacheKey` 没有在该 Fetcher 内形成磁盘命中；
- `DeDupeConcurrentRequestStrategy` 成功后的等待者也没有可读取的 snapshot；
- 仅给全局 `OkHttpNetworkFetcherFactory` 增加该策略不会生效，因为 `ContentCoverFetcher.Factory<String>` 在它
  之前接管了带内容源信息的字符串 URL。

### 3.3 取消：官方路径可取消，当前同步路径不可协作取消

Coil 3.4 的 OkHttp 网络实现通过 `enqueue` 与 `suspendCancellableCoroutine` 等待响应，并在协程取消时调用
`Call.cancel()`；这使滚出屏幕、Compose 子树离开或请求被替换时，底层网络调用也能尽快停止。

来源：

- [Coil 3.4.0 `CallFactoryNetworkClient`](https://github.com/coil-kt/coil/blob/3.4.0/coil-network-okhttp/src/commonMain/kotlin/coil3/network/okhttp/internal/CallFactoryNetworkClient.kt)
- [Coil 3.4.0 可取消 `Call.await()`](https://github.com/coil-kt/coil/blob/3.4.0/coil-network-okhttp/src/commonMain/kotlin/coil3/network/okhttp/internal/calls.kt)

当前 `ContentCoverFetcher.fetch()` 虽然是 `suspend`，内部却直接阻塞执行 `newCall(request).execute()`，没有把
协程取消注册为 `Call.cancel()`。取消 Coil 请求不会立刻中断该同步调用；弱网下它可能继续占用 I/O 线程直到
响应、异常或超时。

## 4. OkHttp 5.4：Dispatcher 上限不约束同步 execute

OkHttp 5.4 `Dispatcher` 的类注释明确描述的是异步请求执行策略。源码中：

- `maxRequests` 与 `maxRequestsPerHost` 只在 `readyAsyncCalls` 晋升到 `runningAsyncCalls` 时检查；
- 同步 `Call.execute()` 调用 `dispatcher.executed(call)` 时，仅把请求加入 `runningSyncCalls`；
- 该同步登记没有容量判断或等待逻辑。

来源：

- [OkHttp 5.4 `Dispatcher.kt`](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Dispatcher.kt)
- [OkHttp 5.4 `RealCall.kt`](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RealCall.kt)
- [OkHttp `Call` API](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-call/index.html)

因此，对现有 `imageClient.dispatcher.maxRequests` 或 `maxRequestsPerHost` 调参，不能治理
`ContentCoverFetcher` 的同步请求并发。有效方向只能是：改用异步可取消调用并进入 Dispatcher；或在自定义
Fetcher 外层增加共享、可取消的限流；更理想的是让它接入 Coil 网络 Fetcher 的缓存与同飞语义。

## 5. Komikku / Mihon Cloudflare WebView 的实现边界

### 5.1 上游真实行为

Komikku 当前 `CloudflareInterceptor` / `WebViewInterceptor` 的流程是：

1. 仅在响应符合 Cloudflare challenge 条件时进入求解；
2. 关闭原响应并移除旧 `cf_clearance`；
3. 向主线程 executor 投递 WebView 创建和加载；
4. 当前 OkHttp 拦截器线程通过 `CountDownLatch` 最多等待 30 秒；
5. 检测到新 clearance 后销毁 WebView并重试原请求，失败则抛异常。

来源（固定到 2026-08-26 查询到的提交 `936e25b`）：

- [Komikku `WebViewInterceptor.kt`](https://github.com/komikku-app/komikku/blob/936e25bf99af29e059f06c4ad613ab62df9ae53e/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/WebViewInterceptor.kt)
- [Komikku `CloudflareInterceptor.kt`](https://github.com/komikku-app/komikku/blob/936e25bf99af29e059f06c4ad613ab62df9ae53e/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt)

上游类内没有按 host 的 mutex/single-flight，也没有失败冷却。故并发请求同时命中挑战时，会各自投递 WebView
任务并各自阻塞网络线程；这是从源码得到的直接推论，不是上游 issue 已确认的性能缺陷。

30 秒等待是有意设计，而非偶然残留。Mihon PR #2200 修复了 challenge 检测过早结束的问题，改为通过
`onReceivedHttpError` 识别主文档 HTTP 403/503，使拦截器真正等待 WebView 尝试完成挑战；Komikku 随后
cherry-pick 了该修复。

- [Mihon PR #2200：fix CloudflareInterceptor not waiting for the challenge](https://github.com/mihonapp/mihon/pull/2200)
- [Komikku issue #514：clearance 反复失效的用户场景](https://github.com/komikku-app/komikku/issues/514)

issue #514 只能证明“某些来源会频繁重新要求 Cloudflare 验证”这一真实场景，不能单独证明本次发热由
Cloudflare 引起。

### 5.2 Kototoro 移植差异

Kototoro 于 2026-08-18 的 `f3126eb1b` 默认启用了 `CloudflareStrategy.MIHON`。当前
`WebViewClearanceSolver` 保留了上游的核心成本模型：主线程创建 WebView、调用线程最多等待 30 秒、获得新
clearance 后重试。它没有 host 级 single-flight 或冷却，并且还存在这些差异：

- 只设置 JavaScript、DOM storage、`LOAD_NO_CACHE` 与请求 UA，没有复用 Komikku 完整的
  `setDefaultSettings()`；
- 没有 Komikku 的 WebView 支持检查、预初始化兼容处理、WebView 过旧检测与提示；
- solver 返回布尔值，由 Kototoro 外层按策略转换为业务异常；
- Kototoro 已有另一套统一 `CaptchaAutoResolveCoordinator` / resolver 串行与冷却机制，但默认 MIHON
  拦截器路径直接使用 `WebViewClearanceSolver`，不能据统一 resolver 已治理就推定本路径也已治理。

这解释了为什么“默认策略变更”可能只在挑战频繁、VPN 抖动或节点切换时显著放大成本，而正常网络用户不一定
感知。

## 6. 证据强度与当前判断

| 判断 | 证据强度 | 说明 |
| --- | --- | --- |
| transport-only 在线判断会把不可用 VPN 视为在线 | 高 | Android 官方语义 + 本地源码直接对照 |
| 自定义封面 Fetcher 未使用 Coil 网络磁盘缓存 | 高 | Coil 3.4 源码 + 本地 `cacheDir` 未使用 |
| 同步 `execute()` 不受 Dispatcher 并发上限约束 | 高 | OkHttp 5.4 源码直接证明 |
| 首页两个展示可用同一 disk key 合并下载 | 高 | Coil 3.4 官方 API/PR + 本地请求键直接对照 |
| 当前封面请求取消不会立刻取消底层 Call | 高 | Coil 官方 await 与本地同步实现直接对照 |
| MIHON solver 可并发创建多个 WebView 并等待 30 秒 | 高 | Komikku 与 Kototoro 源码直接推论 |
| 上述链路就是用户设备卡顿发热的唯一原因 | 未证实 | 尚无设备 trace；玻璃效果、解码尺寸等仍可能是并行因素 |

## 7. 建议修复顺序

### P0：先修封面网络管线，保留双展示

1. 保留封面卡片与全景背景不同的 `memoryCacheKey`，继续共享同一 `diskCacheKey`。
2. 让 `ContentCoverFetcher` 真正接入 Coil `DiskCache`，或者重构为可复用 Coil `NetworkFetcher` 的来源感知网络
   适配层；不能只设置 request 的 `diskCacheKey`。
3. 使用一个 ImageLoader 级共享的 `DeDupeConcurrentRequestStrategy`，按相同 disk key 合并同飞请求。若仍
   保持自定义 Fetcher，则必须确保首请求成功后等待者确实能从磁盘读取，否则“唤醒后继续”仍会再次下载。
4. 把同步 `execute()` 改为 `enqueue` + 可取消挂起桥接，或直接复用 Coil 官方 OkHttp 网络实现。
5. 图片客户端使用与业务 API 分离的较短 `callTimeout`，避免继承当前通用客户端最长 300 秒的调用生命周期。

### P0：约束 MIHON Cloudflare 求解

1. 对同一 host 做 single-flight；同一时刻最多一个离屏 WebView，其他请求等待同一结果。
2. 对失败、超时和“新 clearance 后真实请求仍挑战”增加短期 host 冷却，避免 VPN 抖动时反复创建 WebView。
3. 不要让并发 solver 同时删除同一 host 的 clearance；cookie 更新与求解所有权应位于同一 host 状态机内。
4. 复用已有统一 resolver 的 host 协调组件，避免再维护一套平行的互斥和冷却语义。

### P1：修正连接状态语义

1. 改为 `registerDefaultNetworkCallback`，在 `onCapabilitiesChanged` 中跟踪当前 default network 的
   `INTERNET` 与 `VALIDATED`。
2. 将状态至少区分为“无网络”“存在但未验证”“已验证”，而不是单一 Boolean。
3. 未验证时停止首页自动预取、后台刷新等非必要工作；用户主动操作仍允许有限次数尝试并快速反馈错误。

### P1：设备侧验证

在同一设备、同一内容集上比较正常网络与抖动 VPN，并记录：

- 每个 `diskCacheKey` 的实际网络调用次数、在途峰值、取消后仍存活时长；
- OkHttp DNS、connect、TLS、response 与 call 总耗时；
- Cloudflare solver 每 host 启动次数、并发 WebView 数、30 秒超时数；
- Coil `DataSource` 分布（MEMORY / DISK / NETWORK）；
- Perfetto 的主线程、RenderThread、GC、CPU 频率、帧时间与网络活动；
- 修改前后耗电与温升趋势。

若修复网络管线后正常网络仍卡顿，再单独对玻璃效果、图片解码尺寸和 GPU overdraw 做 A/B；不要用弱网证据直接
排除独立的渲染回归。

## 最终判断

官方资料不支持恢复“重复请求直接返回 ErrorResult”的旧逻辑。更符合 Coil 3.4 官方设计的修复是：保留首页
两个展示请求，通过相同 `diskCacheKey` 在**原图下载层**做磁盘复用和 single-flight，并使用可取消异步调用；
同时为默认 MIHON Cloudflare WebView 路径补齐 host 级 single-flight 与失败冷却。Android 的
`NET_CAPABILITY_VALIDATED` 用于抑制弱网下的非关键自动流量，而不是粗暴禁止所有用户请求。
