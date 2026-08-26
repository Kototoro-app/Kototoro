# Komikku 网络、图片与 Cloudflare 机制研究

> 研究基线：本地 `../komikku`，提交 `936e25bf99af29e059f06c4ad613ab62df9ae53e`。  
> 目的：为 Kototoro 的弱网/VPN 卡顿、发热，以及主页轮播封面与全景封面并发加载问题提供对照。  
> 前置结论见 [weak-network-vpn-jank-heat-research-2026-08.md](./weak-network-vpn-jank-heat-research-2026-08.md)。

## 结论摘要

Komikku 没有采用“同 URL 已在加载时让后续请求失败”的抑制方式。它主要依靠四层约束降低图片加载在弱网下的放大效应：稳定的模型缓存键、由自定义 Fetcher 显式管理 Coil 磁盘缓存、可取消的 OkHttp 异步调用，以及 ImageLoader fetch/decode 并发上限。

这套机制能避免 Kototoro 曾出现的“轮播封面失败、全景封面成功”型竞争错误，但仍不是完整的同请求合并方案：Komikku 使用 Coil 3.5.0，却未配置 `DeDupeConcurrentRequestStrategy`，所以首次缓存未命中时，同一资源的并发请求仍可能重复下载，只是并发规模被限制在 8。

网络状态方面，Komikku 的持续监听比 Kototoro 当前实现更适合不稳定 VPN：它监听默认网络并要求 `NET_CAPABILITY_VALIDATED`。但项目中的一次性 `Context.isOnline()` 仍只判断传输类型，形成两套不一致语义。Cloudflare WebView 解题器同样会让请求线程最多等待 30 秒，并没有按 host 合并请求或失败冷却，因此不应原样照搬。

## 图片加载机制

### 1. 有界并发

ImageLoader 在 [App.kt](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/tachiyomi/App.kt:298) 中明确设置：

- Fetcher：`Dispatchers.IO.limitedParallelism(8)`；
- Decoder：`Dispatchers.IO.limitedParallelism(3)`。

这不是同 URL 去重，但能为弱网、VPN 抖动和大量封面同时进入视口时建立明确的资源上限，避免请求、解码和重组压力无限扩散。

### 2. 自定义 Fetcher 自行负责磁盘缓存

[MangaCoverFetcher.kt](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt:146) 在发起网络请求前显式读取 `ImageLoader.diskCache`；网络成功后再通过 `DiskCache.Editor` 写入并提交快照。网络入口位于同文件约 [215 行](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt:215)，磁盘读取位于约 [291 行](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt:291)。

这点对 Kototoro 尤其重要：自定义 Fetcher 不能假定 Coil 会自动替它完成磁盘缓存。显式读写可让轮播封面与全景封面复用已落盘内容，并使缓存职责清晰可测。

### 3. 网络调用可取消

Fetcher 使用 `Call.await()`，其实现基于 `suspendCancellableCoroutine` 和 OkHttp `enqueue`；协程取消时调用 `call.cancel()`。因此组件离开组合、请求被替换或生命周期结束时，底层连接可以及时释放，而不是让同步 `execute()` 持续占用线程到超时。

这对弱网发热有直接意义：取消信号能传递到底层 I/O，减少已经没有 UI 消费者的无效请求。

### 4. 稳定模型键，而不是竞争失败

Komikku 为 `Manga` / `MangaCover` 注册稳定 Keyer，键由 URL 或实体 ID 与 `lastModified` 组成。封面变更时缓存自然失效，未变更时不同 UI 分支共享相同模型身份。

Book 与 Panorama 只是布局/展示分支，复用同一模型；代码没有把“同 URL 已在途”解释成第二个请求的错误。因此，它不会通过主动返回失败来换取请求抑制，也就不会制造“一个封面显示、另一个封面永久空白”的竞争结果。

### 5. 仍存在首次 miss 的重复下载窗口

依赖清单 [libs.versions.toml](/Users/sunchuxiong/kotatsu_demo/komikku/gradle/libs.versions.toml:48) 使用 Coil 3.5.0，但 ImageLoader 未启用 `DeDupeConcurrentRequestStrategy`。当两个相同缓存键的请求几乎同时读到磁盘 miss 时，二者仍可能各自请求网络。

所以 Komikku 的方案应理解为“稳定键 + 显式缓存 + 有界并发 + 可取消”，而不是严格 single-flight。

## 网络可用性与超时

[NetworkStateTracker.kt](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/tachiyomi/util/system/NetworkStateTracker.kt:23) 使用 `NET_CAPABILITY_VALIDATED` 判断网络是否真正通过系统验证，并在约 [38 行](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/tachiyomi/util/system/NetworkStateTracker.kt:38) 注册默认网络回调。这能感知 VPN 节点仍连接、但实际上无法访问互联网的状态变化。

不过 [NetworkExtensions.kt](/Users/sunchuxiong/kotatsu_demo/komikku/app/src/main/java/eu/kanade/tachiyomi/util/system/NetworkExtensions.kt:16) 中的 `Context.isOnline()` 仍只根据当前网络传输能力判断在线。持续状态流与一次性查询具有不同语义，是 Komikku 自身需要规避的设计缺口，也不适合复制到 Kototoro。

[NetworkHelper.kt](/Users/sunchuxiong/kotatsu_demo/komikku/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt:42) 的默认超时为：

- connect：30 秒；
- read：30 秒；
- call：120 秒。

相较 Kototoro 已研究到的 300 秒总调用超时，120 秒缩短了坏节点长期占用资源的上界，但对首页封面这类即时 UI 内容仍然偏长。超时应按交互类型分层，而不是直接照搬一个全局值。

## Cloudflare WebView 机制

[CloudflareInterceptor.kt](/Users/sunchuxiong/kotatsu_demo/komikku/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt:74) 为挑战处理创建 `CountDownLatch`，并在约 [127 行](/Users/sunchuxiong/kotatsu_demo/komikku/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt:127) 等待 WebView 结果。[WebViewInterceptor.kt](/Users/sunchuxiong/kotatsu_demo/komikku/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/WebViewInterceptor.kt:81) 将等待上限定为 30 秒。

该实现没有按 host 的 single-flight，也没有失败冷却。同一站点的多个封面在坏 VPN 节点下同时命中挑战时，仍可能重复创建/驱动解题流程，并让多个调用线程等待。这是 Komikku 机制中最不应直接照搬的部分。

## 与 Kototoro 的直接对照

| 维度 | Komikku | Kototoro 当前问题 | 判断 |
| --- | --- | --- | --- |
| 同资源多展示位 | 同模型、稳定 Keyer，不让第二请求失败 | 轮播与全景使用不同内存键；旧抑制逻辑会让竞争者失败 | 应保留展示变体，但禁止以错误结果完成竞争请求 |
| 自定义 Fetcher 磁盘缓存 | 显式读取、写入 Coil DiskCache | 曾假定 Coil 自动缓存自定义 Fetcher | 应采用 Komikku 的显式缓存职责 |
| 调用方式 | `enqueue` + cancellable `await` | 同步 `execute()` | 应改为可取消挂起调用 |
| 图片并发 | fetch 8、decode 3 | 缺少同层面的明确上限 | 可先引入有界并发，再按基准调参 |
| 同请求合并 | 未启用 Coil 去重，首次 miss 可重复 | 自定义 Fetcher 也绕开网络 Fetcher 的自动去重 | 需要额外设计 single-flight，不能只复制 Komikku |
| 网络状态 | 默认网络 + `VALIDATED`，但旧扩展不一致 | 传输存在即视为在线 | 应统一为可验证、单一语义的状态源 |
| Cloudflare | 30 秒阻塞等待，无 host 合并/冷却 | 同类弱网放大风险 | 需要在 Komikku 基础上补 host single-flight 与失败冷却 |

## 建议

### 可直接借鉴

1. 将 `ContentCoverFetcher` 的网络调用改为可取消的挂起式 OkHttp 调用，并确保取消传递到 `Call.cancel()`。
2. 由自定义 Fetcher 显式负责 Coil DiskCache 的读、写与提交，统一封面展示位的磁盘缓存键。
3. 为图片 Fetcher 和 Decoder 分别设置有界并发；初始值可参考 8/3，但最终应以低端机、弱 VPN 和首页批量封面基准确定。
4. 以默认网络回调和 `NET_CAPABILITY_VALIDATED` 作为在线状态核心，同时避免保留 transport-only 的旁路判断。
5. 保留 Book/Panorama 的 UI 差异，但让它们共享资源身份和成功结果，不把并发请求转换成业务错误。

### 不可照搬

1. 不要认为并发限制等于同 URL 去重；仍应为首次 miss 增加按磁盘缓存键的 single-flight。
2. 不要直接使用全局 120 秒超时处理首页封面；前台图片需要更短、可取消且按场景分层的预算。
3. 不要复制 Cloudflare 的“每请求最多阻塞 30 秒”模型；至少需要按 host 合并挑战、失败冷却和明确的取消传播。
4. 不要同时保留 `VALIDATED` 状态流与 transport-only `isOnline()` 两种在线定义，否则弱 VPN 下仍会出现错误重试和 UI 状态抖动。

## 推荐实施顺序

1. 先移除“竞争者失败”语义，保证轮播和全景都能收到同一资源的最终结果。
2. 再完成可取消网络调用与自定义 Fetcher 显式磁盘缓存，消除无消费者请求和重复落盘缺口。
3. 加入 fetch/decode 有界并发，控制弱网下线程、CPU 与解码峰值。
4. 按磁盘缓存键实现请求 single-flight，并补充并发首次 miss、取消一个订阅者和失败重试测试。
5. 最后统一网络有效性判断，并为 Cloudflare 增加按 host 合并与失败冷却。

该顺序保持 KISS/YAGNI：先修复已确认的正确性和资源泄漏路径，再加入最小必要的合并机制，不引入与当前问题无关的网络框架重构。
