# Browser Transport 实施计划

更新时间：2026-08-12（2026-08-18 归档，2026-08-18 升级为三策略选择）

> ## 归档状态（重要，已升级为 Cloudflare 策略选择器）
>
> **WebView transport（Browser Transport / 自动 Cloudflare 求解传输）已归档，不再是唯一自动路径。**
>
> 原布尔开关已替换为三选一策略 `AppSettings.cloudflareStrategy`（枚举
> `org.skepsun.kototoro.core.prefs.CloudflareStrategy`，持久键 `cloudflare_strategy`，**默认 MIHON**）：
>
> - **MIHON（默认，Mihon/Komikku 风格）**：应用层 OkHttp 拦截器（`CloudFlareInterceptor` 共享 client 与
>   `KotoNetworkHelper` Mihon client）收到挑战后，用 `WebViewClearanceSolver`（移植自 komikku
>   `CloudflareInterceptor`/`WebViewInterceptor` 约 240 行）在离屏 WebView 加载同一 URL 求解，等到新
>   `cf_clearance` 写入共享 CookieManager 后**重试原请求**；解不动时降级抛异常走人工浏览器。
> - **MANUAL（Kotatsu-Redo/上游 Kotatsu 风格）**：无自动求解。检测到挑战后抛
>   `CloudFlareProtectedException`，由错误卡/协调器打开内置浏览器（`BrowserActivity`）人工验证。
> - **TRANSPORT（旧 WebView transport，已归档）**：`fetchWithBrowserContext` /
>   `resolveCaptchaAutomatically` / `tryResolveCaptcha` 路径，仅在该策略被显式选择时启用。
>
> 各调用点的策略语义：
> - `CloudFlareInterceptor`（共享 OkHttp client，覆盖 Kotatsu/JAR、Legado、TVBox、LNReader）：
>   MIHON → 拦截器内求解+重试；TRANSPORT → transport；MANUAL → 直接抛异常。
> - `KotoNetworkHelper`（Mihon/Kagane 扩展）：同上按策略分发（`resolveByStrategy`）。
> - `CloudstreamCloudflareInterceptor` / `CloudstreamContentRepository`：仅 TRANSPORT 时启用
>   transport 兜底与自动求解；MIHON/MANUAL 依赖共享拦截器与人工路径。
> - `CaptchaAutoResolveCoordinator`：仅 TRANSPORT 时进入自动求解阶段；MIHON 求解失败也由此
>   降级到人工浏览器。
>
> - 本文件描述的 transport 代码、配套单测与实现状态**全部保留**；`WebViewExecutor`
>   相关方法保持 `@Deprecated` 标记。
> - 不受影响：解析器侧 WebView API（`evaluateJs` / `loadPageHtml` / `loadHtml` / `sniff*` /
>   `loginAndCheck` / `WebViewRequestInterceptorExecutor`）、Cloudstream 插件的原生
>   `WebViewResolver` 优先路径、手动验证 UI（`browser/cloudflare/*`）。

## 目标

为 Cloudflare、DDoS-Guard 和依赖浏览器 JavaScript 状态的来源提供浏览器网络传输路径：

```text
OkHttp 请求
  -> challenge
  -> WebView 求解
  -> 同一个 Chromium session 内执行 fetch
  -> Kotlin 解析响应
```

关键目标不是复制 Cookie，而是让求解和业务请求共享 Chromium 的 Cookie、origin storage、
网络栈以及浏览器管理的请求特征，避免人为切换到 OkHttp 所产生的 TLS/HTTP 协议栈和客户端特征断层。
在需要时也可访问真实页面的 JavaScript 状态。页面 JavaScript
状态仅在 page world 执行时共享；isolated execution world 与页面的 global object 和闭包保持隔离。

## 边界

- OkHttp 仍然是默认 transport；Browser Transport 只用于确认属于 browser-bound 的请求。
- 当前第一阶段只支持文本响应（JSON、HTML），图片和其他大二进制资源继续走 OkHttp/Coil。
- 请求只能访问调用方声明的目标 origin，网页传入的 URL 不得成为 native 网络能力的授权来源。
- 不使用 `addJavascriptInterface` 暴露万能 native API。
- WebMessage 能力不可用时保留 `evaluateJavascript` 轮询降级，保证旧版 WebView 可用。

## 当前实现

`WebViewExecutor.fetchWithBrowserContext()` 已经是现有 Browser Transport 入口。第一阶段改造为：

1. 在真实同源页面导航前注册 `WebViewCompat.addWebMessageListener()`，确保 bridge 注入新 document。
2. 通过严格的 HTTPS origin allowlist 注入 `KototoroTransport`。
3. Native 生成 `requestId` 和 `navigationEpoch`，通过 JavaScript 启动 `fetch()`。
4. JS 只回传带有相同 `requestId`/`navigationEpoch` 的响应；Native 校验来源 frame、origin 和响应 URL。
5. 超时、取消、旧页面迟到响应均不能完成当前请求。

`rendererEpoch` 在 renderer 重建或失效时递增；`navigationEpoch` 在 document 导航时递增。
两者与 `requestId` 独立，避免 request、navigation 与 renderer 的 generation 淆义。

实现细节：WebMessage listener 必须在真实 origin 页面导航前注册，WebView 才会向新 document 注入 bridge
对象。RPC 等待最多 8 秒，未收到消息时降级为 JavaScript polling，避免 WebView provider 的 bridge
差异耗尽整个 30 秒请求时限。

目标执行能力按 WebView provider 能力降级：先通过
`WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)` 探测能力，
再优先使用指定 JavaScript execution world 中的脚本和
WebMessage listener；不可用时使用 `DOCUMENT_START_SCRIPT` 捕获的原生 `fetch`；再不可用时使用
page-world WebMessage RPC，最终才回退到 `evaluateJavascript` polling。isolated world 在 document start
通过独立的 `KototoroIsolatedTransport` listener 登记 `JavaScriptReplyProxy`，Native 使用
`executeJavaScript()` 在该 world 内执行固定的 fetch executor；page world 使用不同 bridge 名和 callback，
不能伪造 isolated-ready 取得 executor 身份。reply proxy 同当前 `rendererEpoch`/`navigationEpoch` 绑定，
document 或 renderer 改变后立即失效。`DOCUMENT_START_SCRIPT` 只能防止页面后续替换已捕获的
`window.fetch` 引用，不能替代 isolated world 的执行隔离。

bridge 现在随 `BrowserSessionRecord` 创建和销毁：listener、document-start fetch 捕获器以及 isolated
reply proxy 在 session 生命周期内复用，复用既有 document 时不会因错过 ready 事件而失去 isolated executor。
单次 request 仍只在 pending entry 中保存自己的 origin policy；额外 origin 不会写回 session。高频请求的
`WebMessagePortCompat` 长连接协议仍属于 P2，当前实现仍是 session 级 WebMessage listener + RPC。

Cloudflare fallback 已接入两条实际网络路径：

- Mihon/Kagane：`KotoNetworkHelper` 在 GET/POST 请求收到 challenge 后，将可重复的文本请求交给 Browser Transport，再包装回 Mihon 的 `Response`。
- 共享 `ContentHttpClient`：`CloudFlareInterceptor` 覆盖 Kotatsu/原生 parser、Cloudstream 以外的共享 OkHttp 使用方，以及继承该 client 的 Legado、TVBox/JSON source。带有特殊 `CloudFlareHandlingPolicy` 的 Cloudstream `loadLinks` 流程仍保留原有 challenge 回传语义。

列表错误态也兼容嵌套异常：`SearchContentListScreen` 和通用 `ErrorState` 会从异常链中提取
`CloudFlareProtectedException` 的 URL，点击后打开 Kototoro 内置 `BrowserActivity`，而不是系统外部浏览器。

## 路由策略

目标状态为 `AUTO`：

```text
OkHttp
  -> 200：完成
  -> Cloudflare：BrowserSession 求解
  -> OkHttp probe 成功：继续 OkHttp
  -> probe 仍被 challenge：Browser Transport
```

后续按以下顺序扩展：

- `BrowserSessionManager`：按 origin 重用 WebView，限制活动 session 数量并处理 renderer crash。
- `RoutePolicy`：将 `/api/**`、`/images/**` 等路径分别路由到 Browser 或 OkHttp。
- `WebMessagePortCompat`：高频请求使用长连接 RPC，减少重复 listener 注册。
- `ArrayBuffer`：仅对明确需要的二进制响应启用，避免 Base64 放大和多次内存复制。
- 设备级测试：验证 WebView 更新、进程重启、Cookie 持久化、挑战后 API 请求和 renderer 回收。

## 安全模型

- request policy 至少包含 `https://<target-host>`，必要时由 source 显式声明受信任的 API 子域名；权限分为
  `documentOrigins`、`fetchOrigins` 和 `redirectOrigins`，允许 fetch 不等于允许导航或 redirect。
- request policy 仅代表 Kototoro 授权，不绕过 Chromium CORS，也不得合并到持久 BrowserSession。
- callback 必须验证 `isMainFrame`、`sourceOrigin`、`requestId`、`rendererEpoch`、
  `navigationEpoch` 和目标 host。
- 在发送前拒绝 localhost、私有网段、未声明的跨站 origin 以及 `javascript:`/`file:` URL。
- redirect 必须校验最终 URL；但 Chromium 自动跟随 redirect 时，请求可能已在 native 校验前发出。
  在具备发送前拦截能力前，不宣称绝对阻止所有未声明的 redirect 网络请求。
- 请求头过滤由 Kotlin 控制；网页不能自行提升权限或覆盖 Cookie、Origin、Referer 等浏览器管理字段。

## 参考项目

- AndroidX WebKit：[`WebViewCompat.addWebMessageListener`](https://developer.android.com/reference/kotlin/androidx/webkit/WebViewCompat)
- AndroidX WebKit：[`JavaScriptExecutionWorld`](https://developer.android.com/reference/androidx/webkit/JavaScriptExecutionWorld)
- Cloudstream3：项目内的 `WebViewResolver` 和 `CloudflareKiller`
- Mihon：source 网络抽象、Cloudflare 错误传播和 OkHttp 重试边界
- NoveLA：按 host 管理 WebView challenge session 的思路
- SimpleAndroidBridge：request id、Promise 和 timeout 的 RPC 设计
- react-native-webview：WebView 内 fetch/blob 与 native 消息传输案例

这些项目只作为设计参考；Kototoro 不直接引入第三方 JS bridge 依赖。

## Issue 研究补充

近期公开 issue 对方案边界提供了几个重要验证：

- WebView 求解后将请求交给 OkHttp 可能重新暴露不同的 TLS、Client Hints、Cookie 和 HTTP/2
  特征；Cookie/UA bridge 不能视为根治方案。
- `data:`、`file:` 和 opaque origin 会使 `fetch()` 受 CORS 限制，因此 BrowserSession 必须先加载
  真实 HTTPS origin。
- BrowserRequestPolicy 的授权不绕过 Chromium CORS。跨 origin API 若不允许页面 origin 读取响应，
  当前 session 返回 `BrowserTransportError.CrossOriginReadBlocked(targetOrigin)`；
  `BrowserSessionManager`/`TransportRouter` 校验当前 request policy 后获取目标 API origin 的 session，
  在真实 HTTPS document context 中重新执行 same-origin fetch。BrowserSession 本身不发现或路由其他 session。
- `shouldInterceptRequest` 中用 OkHttp 重放 WebView 请求会再次制造网络栈断层；BrowserTransport 不应采用这种实现。
- Blob 和图片等大二进制数据经过 Base64 bridge 会产生约 33% 放大和额外内存复制，第一阶段只承载 JSON/HTML。
- resolver 不能只等待 `onPageFinished`；主 frame DNS/TLS 错误、`onReceivedError`、renderer crash 和取消都必须快速结束请求。
- bridge 必须是受限 RPC，而不是开放代理；目标 host、响应 URL、`requestId`、`rendererEpoch` 和
  `navigationEpoch` 都需要校验。

当前实现已经覆盖真实 HTTPS 页面、`credentials: include`、origin/frame/host 校验、request id、navigation generation
以及超时降级；仍未覆盖以下“理想根治”条件：

1. 按 origin 的持久 session，而非单个共享 WebView 加全局 Mutex。
2. 所有 WebViewExecutor 用途统一的 renderer 生命周期恢复和错误指标。
3. 独立/隔离 JavaScript execution world，提供真正的执行隔离；当前 `DOCUMENT_START_SCRIPT` 仅是
   anti-monkey-patch hardening。
4. source 声明的 API 子域名 allowlist，而不是当前单 host 策略。

### Kagane API challenge 的当前边界

Kagane 的首页 challenge 和 `/api/v2/search/series` 响应不能仅凭 URL 或已有 Cookie 推断为同一次
Cloudflare 验证。BrowserTransport 会先在真实 origin WebView 中执行 API `fetch()`，避免 WebView 到
OkHttp 的 TLS/指纹切换；如果业务请求仍返回 challenge，Mihon/JAR 路径不会把短生命周期
BrowserSession 挂载成可见 overlay，也不会把 POST API 当作 GET 页面导航。

当前人工恢复路径刻意复现已在设备上验证有效的正常浏览流程：

```text
API fetch 返回 challenge
        -> 来源列表显示错误
        -> 用户打开普通内置浏览器的源首页
        -> 用户等待首页及其 API 请求完整加载
        -> 用户主动返回
        -> 列表重试原始 GET/POST
```

普通内置浏览器没有 success-cookie target，因此不会根据页面标题、已有 `cf_clearance` 或
`onPageFinished` 自动关闭。底层 `BrowserSession` 仍保留 challenge context、原请求重试和交互事件协议，
供能够可靠验证原请求的调用方使用；Mihon/Kagane 暂不启用该 overlay，避免旧 Cookie 或首页状态造成
“checkbox 尚未出现就成功”的误判。

设备日志进一步确认：修复 bridge 后，Kagane POST API 已在约 2 秒内返回真实 403，随后 Turnstile
iframe 被加载；之前的 30 秒等待问题已经消失。新的失败点不是 API 本身丢失 source，而是
Mihon 扩展的 OkHttp interceptor 在线程池线程执行，`ThreadLocal`/协程上下文无法传播，导致
`MihonRequestContext.currentSource()` 为空。目标是让 `Request.tag(SourceRequestContext)` 成为唯一
authoritative identity；host 映射只能作为 legacy 恢复提示，不能参与授权。上下文至少包含 source
id/key、声明的 origins 和 transport policy。

## 实施状态

- [x] 为现有浏览器请求增加 origin 限制的 WebMessage RPC 路径。
- [x] 保留旧 WebView 的 JavaScript 轮询降级。
- [x] 增加 request id、navigation generation 和来源校验；目标字段名为 `requestId`/`navigationEpoch`。
- [x] 主 frame 网络/SSL 错误和 renderer 退出快速失败；损坏 WebView 从缓存移除。
- [x] BrowserTransport 文本响应限制为 8 MiB，并将协程取消传递到浏览器 `AbortController`。
- [x] 基础按 `scheme://host:port` 的 BrowserSession LRU（最多 3 个，保护使用中 session）。
- [x] 当前 `BrowserOriginPolicy`：默认同源，额外 API/redirect origin 必须显式声明并经过统一校验。
- [x] 支持 `DOCUMENT_START_SCRIPT` 时捕获原生 `fetch`；页面后续替换 `window.fetch` 不影响已捕获引用，
  但这不是 JavaScript execution isolation。
- [x] Mihon 请求在 Cloudflare 响应后可将 GET/POST 文本 API 转交 BrowserTransport，再包装回 OkHttp `Response`。
- [x] Mihon BrowserTransport 检测到 API challenge 后返回真实 challenge 响应，不挂载当前 BrowserSession 的交互
  overlay，也不返回 `null` 触发旧 resolver 循环；人工恢复统一由来源列表打开普通内置浏览器首页。
- [x] CloudFlareActivity 恢复“在浏览器中打开”人工兜底入口。
- [x] Mihon POST challenge 保留 method/body/content-type，人工入口不再使用 `postUrl()` 重放 JSON。
- [x] 底层 BrowserSession 交互 resolver 只有在同一 execution 重试原始 API 成功后才产生成功结果；Mihon/JAR
  当前禁用该 overlay，人工浏览器仅在用户主动返回后触发来源列表重试，不提前宣称 API 已成功。
- [x] 对同一 API method+URL 增加 30 秒 resolver cooldown，防止 API 仍被拒绝时反复打开主页/验证页。
- [x] SearchContentList 错误态恢复嵌套 Cloudflare 异常的内置浏览器入口，并修复按钮点击回调。
- [x] 共享 `ContentHttpClient` 增加 GET/POST 文本 Browser Transport fallback；无法安全转交时继续抛出原 Cloudflare 异常。
- [x] 共享网络栈缺少权威 `ContentSource` request tag 时禁止 BrowserTransport；来源图标/favicon 等装饰性请求即使
  携带用于错误归属的 `X-Content-Source` header，也只能正常失败并显示回退图标，不能创建 BrowserSession 或弹出
  人工 challenge。HTTP header 不是 transport 授权凭据。
- [x] Browser RPC 超时后的 document snapshot 仅用于诊断，不再以 `status=0` 伪装成业务响应；所有 transport
  边界只接受 `100..599` 的合法 HTTP 状态。execution 失败后销毁 session，避免复用停留在 challenge 页且 bridge
  已失联的 document，形成连续 30 秒超时。JS polling fallback 也使用 requestId 隔离的结果槽和
  `AbortController`，取消或超时时清理，防止迟到结果污染后续 execution。
- [~] P0.1：Mihon/Anıyomi 基类已在 `tagRequest() -> newCall()` 的 adapter 边界将 source 与其声明的
  `baseUrl` 固化为不可变 `Request.tag(SourceRequestContext)`；后续 challenge、BrowserTransport allowlist 和日志
  只读取该 tag。网络 interceptor 不再使用 ThreadLocal 或全扩展扫描；对绕过基类的旧扩展，仅使用 adapter 已注册的
  host hint 恢复 source，再强制校验 `SourceRequestContext` 的声明 origin，不能跨 host 扩权。没有 tag/合法 legacy
  hint 或目标 URL 不属于 source 声明 HTTPS origin 时 BrowserTransport 会拒绝执行。Kagane 标准
  `searchMangaRequest` POST API 与主页同 origin，走此可靠路径。完全绕过基类自行提交请求的
  扩展目前不会获得 BrowserTransport 权限；独立 API 子域名仍缺少 source 显式声明机制。
- [x] P0.2：同一 BrowserSession 内 transport execution 由 `BrowserSessionRecord.executionMutex` 串行化；不同
  origin session 可并行，避免 challenge navigation 破坏并发 operation。session 淘汰时 mutex 随 record 一起
  释放；设备级并发和 renderer 回收回归仍待补充。
- [x] P0.3：BrowserTransport 已具备在同一 WebView 中检测 API challenge并重试原始 GET/POST 的底层能力；
  Mihon/JAR 禁用可见交互分支，challenge 返回真实 403，不再由 transport 挂载 overlay。独立
  `BrowserExecution` operation 持有
  `executionId`、原始 request identity、challenge context、确定终态和受校验的状态转换，覆盖
  `FETCHING -> CHALLENGE_DETECTED -> RESOLVING_AUTOMATIC -> WAITING_FOR_USER -> VALIDATING ->
  RETRYING_REQUEST` 及 `FAILED`/`CANCELLED`；同时引入 `BrowserChallengeContext` 和
  `BrowserResolutionEvidence`。context 只长期保留最多 64 KiB 的 HTML 诊断片段；POST 不暴露 navigation URL，
  禁止把原始 POST challenge 退化成 GET 导航。session 只持有当前 `activeExecution`，operation 终结后清除；
  renderer poison 会主动失败未终结 operation，LRU 不会淘汰仍有 active execution 的 session。
- [x] P0.4：已建立 singleton `SharedFlow<BrowserInteractiveChallenge>` UI 协议，事件包含 `sessionId`、唯一
  `challengeId`、origin、原始 request URL、method、display URL，并具有
  `PENDING -> ATTACHED -> RESOLVED/CANCELLED/FAILED` 的受校验生命周期。宿主通过
  `challengeId + sessionId` ack/cancel，旧事件不能取消新的 operation；返回键会立即取消 resolver 等待，不再只依赖
  timeout。独立 `BrowserSessionManager` 负责 `sessionId -> WebView` 的 UI identity、register/unregister 和幂等
  attach/detach。普通 Compose 宿主和全屏 Compose 宿主都只在 RESUMED 生命周期消费 PENDING 事件，终态事件不会
  重新挂载 WebView；resolver 仍是验证结果和最终 detach 的唯一权威方。该协议目前不由 Mihon/JAR transport 发出。
- [x] API 403 challenge 的上下文始终绑定原始 API URL；POST challenge 现在导航到真实 HTTPS API URL
  触发 Cloudflare 的浏览器验证，但绝不以导航重放原始 POST。验证完成后仅在同一 renderer/session 中重试原始
  method/body。这样避免 `loadDataWithBaseURL` 产生 `about:blank` document，导致 Turnstile 的 origin handshake
  失败；完整响应 HTML 仍只保留为有限诊断片段。
- [x] 人工 challenge 的成功判定不再只依赖已有 `cf_clearance` 或 `onCheckPassed()`：必须观察到
  `INTERACTIVE -> OK + clearance`、可见 resolver 中真实 `__cf_chl_*` token 导航后回到 `OK + clearance`，或
  `OK + 新 clearance`，才产生 `ResolutionEvidence` 并重试原始请求。
  初始 `WAIT -> OK + 旧 clearance` 不算通过，首页遗留 cookie 不会导致 checkbox 尚未出现/完成时自动关闭。
- [x] Kagane session 验证：API challenge 处理不再删除已有 `cf_clearance`。首页验证签发的 clearance
  必须在同一 BrowserSession 中保留并复用；API 交互验证接受已有有效 clearance，不要求每个 API 请求生成新 cookie。
- [x] Mihon challenge 进入 BrowserTransport 后不再回退 `CaptchaAutoResolveCoordinator`；同 Session retry
  仍失败时返回真实 challenge 响应，彻底切断 API 403 到旧主页 resolver 的循环。
- [x] 缓存 BrowserSession 请求结束时保留同源 document、storage 和 renderer；后续请求在 origin 匹配时
  直接复用当前页面，不再导航空白页或无条件重载主页。
- [x] 首次 origin bootstrap 不再以 `onPageFinished + 固定延时` 作为就绪信号；必须连续满足
  `CF=OK`、`document.readyState=complete`、非 `__cf_chl_*` URL 且 resource 计数进入安静窗口，才允许注入
  Mihon API 请求。这样等待主页 CF 跳转、页面初始化及首批 API 资源稳定，同时避免永久等待后台长轮询。
- [x] OkHttp 请求转交 BrowserTransport 时补齐由 `RequestBody.contentType()` 隐式提供的 `Content-Type`；不能只
  复制 `Request.headers`，因为 OkHttp BridgeInterceptor 尚未执行时 JSON POST 的 media type 不在 header 集合中。
- [x] Browser fetch 不再复制 `Origin`、`Referer`、`User-Agent`、`Sec-Fetch-*`、`Sec-CH-UA*`、Cookie、Host、
  Content-Length 等浏览器管理型 header，由 Chromium 根据真实 document origin 和 fetch context 生成；业务 header
  如 `Accept`、`Content-Type`、`Accept-Language`、`X-Requested-With` 继续保留。该策略为纯函数并有 JVM 单测，避免
  Mihon/OkHttp 请求头伪造出与 WebView 实际执行环境矛盾的浏览器指纹。
- [x] Kagane POST 增加 execution-world 对照路径：默认先走 isolated-world fetch；仅当响应被确认是 Cloudflare
  challenge 时，才在同一 BrowserSession、同一真实 HTTPS document 中用 document-start 捕获的 page-world 原生
  `fetch` 再执行一次。page-world 成功则直接返回；仍被 challenge 才进入人工 resolver，验证后的原请求也使用
  page-world 原生 fetch 重试。这不是 hook 页面 fetch，也不向页面暴露 native HTTP 能力。
- [x] Browser RPC 增加安全诊断：只记录 execution world、method、header 名称、Content-Type、body 长度、HTTP
  status、`cf-mitigated`、CF 判定和 response URL；不记录 header 值、request body 或 response body。该诊断用于
  区分请求语义差异、真实 Managed Challenge 与 RPC/桥接故障。
- [x] BrowserSession bootstrap 增加被动 API 请求观测：仅通过 `WebViewClient.shouldInterceptRequest()` 观察当前
  request origin policy 允许且路径为 `/api/**` 的 Chromium 请求，并通过 `onReceivedHttpError()` 记录失败状态。
  观测器不返回 `WebResourceResponse`、不使用 OkHttp 重放，也不改写页面 `fetch/XHR`；日志只包含 method、URL、
  main-frame 标记、header 名称和 Content-Type，不记录 header 值或 body。
- [x] BrowserSession 采用最小干预的 Cloudflare WebView profile：保留 WebView provider 原生默认 UA，不再用
  `UserAgentProvider` 删除 `; wv`/`Version/4.0` 后覆盖 Chromium UA；不屏蔽网络图片；保留 JavaScript、DOM
  Storage、默认缓存和第三方 Cookie。Mihon 原请求 UA 仍只用于 OkHttp 路径，不进入 BrowserSession。
- [x] 移除 `App.getPackageName()` 中针对 Chromium `BuildInfo/ApkInfo` 的全局宿主包名伪装。TVBox 动态运行时的
  `HOST_IDENTITY` 兼容桥保持不变；BrowserSession 现在向 Chromium报告应用真实包名，避免 UA、Client Hints 与宿主
  identity 互相矛盾。该身份在 Chromium首次初始化时可能被进程缓存，因此设备验证必须使用冷启动的新进程。
- [x] BrowserSession Chromium profile 使用全局独立版本号持久化迁移状态。定向 tombstone 无法可靠删除设备上的
  HttpOnly/Secure `cf_clearance`，因此 profile 版本首次升级时通过共享 `MutableCookieJar.clear()` 清理并等待完成；其
  `AndroidCookieJar` 实现负责将 `CookieManager.removeAllCookies()` 调度到主 Looper。只有全局 CookieManager 和触发
  origin 均确认不再携带 Cookie 后才记录迁移完成。该破坏性清理每个 profile 版本全局只执行一次，
  不能按 origin 重复执行，否则访问新来源会不断清除其他来源的新浏览器会话。
- [x] 对启用 BrowserSession 交互能力的调用方，`OK + cf_clearance` 只作为原请求验证的触发条件，不直接关闭页面
  或标记成功。原始 API method/body/headers 必须在同一个仍可见的 BrowserSession 中重试且不再返回 challenge，
  resolver 才进入 RESOLVED；验证失败时页面保持打开，避免旧 clearance 或页面状态误判造成自动关闭。
- [x] BrowserSession 的 POST 交互 resolver 导航真实 origin 首页而非 API URL，复现“主页完成 challenge、页面发起
  API”的正常浏览路径。观察到交互组件或 `__cf_chl_*` 导航后，页面回到 `OK` 即可触发原 POST 探测，不要求站点
  一定签发 `cf_clearance`；Cookie 只作为诊断。Mihon/JAR 当前禁用该交互分支。
- [x] Mihon/JAR Browser Transport 不再自行挂载短生命周期的交互 challenge overlay。浏览器 RPC 仍可自动尝试；若
  仍为 challenge，则将错误交还来源列表。列表“开始验证”和自动流程的人工 fallback 统一打开普通内置浏览器的源首页，
  浏览器不会根据页面标题或 Cookie 自动关闭；用户完成首页加载并返回后，列表请求再重试。错误卡片的“在浏览器中打开”
  也使用同一首页 URL 并携带真实 source id，修复原 Elvis 表达式仅求值 URL、未实际执行跳转的问题。
- [x] 同一人工交互策略已下沉到共享网络栈：Kotatsu、Legado、TVBox/JSON、LNReader、IReader 等使用
  `ContentHttpClient` 的来源允许 BrowserTransport 自动处理文本请求，但不会由 transport 挂载交互 overlay；最终人工
  入口统一为普通内置浏览器首页。Cloudstream 请求通过 `CloudFlareHandlingPolicy` 保留来源 solver 优先级，先执行其
  `WebViewResolver`/插件兼容流程；来源 solver 和无交互 BrowserTransport 均失败后，应用级人工入口仍使用同一普通浏览器。
- [x] LNReader QuickJS native fetch 在 repository 边界附加权威 `ContentSource` request tag，使共享 BrowserTransport
  可以按来源授权；不使用 plugin id 或 host 反查扩大 origin 权限。二进制 fetch 仍不交给文本 BrowserTransport。
- [x] Kotatsu/JAR 的 `OkHttpWebClient` 已携带权威 `MangaSource` tag；共享 CF interceptor 在第一次请求进入时直接
  将其包装为 `KotatsuParserSource` 并附加 `ContentSource` tag，不再等到排在 CF 检测之后的通用 header interceptor，
  也不通过 host 反查。通用 header interceptor 同时保留该 tag，供后续 retry、图片和诊断链路使用。
- [x] 人工浏览器、全局 `AndroidCookieJar` 和 Mihon 请求日志使用相同的 SHA-256 截断指纹与长度记录
  `cf_clearance`，同时记录 WebView/OkHttp UA 指纹；不输出 Cookie 原文。人工 resolver 返回日志附带 source 和
  challenge URL，可据此确认浏览器前后 clearance 是否变化、Mihon 是否读取同一枚 Cookie，以及返回后是否真正 retry。
- [x] CaptchaHandler 通知、自动流程的人工 fallback 和错误页不再创建新的 CloudFlareActivity 人工会话；统一
  `AppRouter.cloudFlareResolveIntent()` 到普通 BrowserActivity 首页并携带 source/UA。旧 CloudFlareActivity 仅保留兼容入口。
- [x] Mihon 裸请求补齐 source context 时按“请求显式 tag、当前扩展执行上下文、已注册 host hint”的顺序恢复；host
  反查仅保留为旧扩展兼容兜底，避免 API host 与主页 host 不一致时将异常来源退化成 `UNKNOWN`。
- [x] P1：BrowserSession 现在由 `BrowserSessionRecord` 统一持有 origin、sessionId、WebView、状态、pending
  operation、UI attach、renderer/navigation epoch 和最近使用时间；renderer 退出时 poison 并主动失败 pending RPC，
  同时记录 WebView provider 包名/版本，并记录 `onRenderProcessUnresponsive/Responsive` 持续时间。
- [x] P1：AndroidX WebKit 已从 1.14.0 升级到最新稳定版 1.16.0，并 capability-detect
  `JS_INJECTION_IN_FRAME_AND_WORLD`。BrowserSession 创建时注册独立 world listener + document-start
  script，新 document 和复用 document 都通过 `JavaScriptReplyProxy.executeJavaScript()` 执行
  isolated-world fetch；reply proxy 绑定 renderer/navigation epoch，page/world bridge 名和 callback
  隔离且在 session 销毁时清理。高频请求的 `WebMessagePortCompat` 长连接协议仍属于 P2。
- [~] P1：当前 origin policy 已拒绝非 HTTPS、localhost、loopback、私有 IPv4 和常见 IPv6 链路本地/ULA
  目标，并覆盖 JVM 单测；权限已拆分为 `documentOrigins`/`fetchOrigins`/`redirectOrigins`：附加 source origin
  只扩展 fetch，不自动取得 document 导航或 redirect 权限。跨 origin/CORS 的受控 session 切换仍未完成。
- [ ] P2：route-level transport policy 和 `WebMessagePortCompat` persistent RPC。
- [x] BrowserSession 对主 frame 网络/SSL 错误和 renderer crash 快速失败并清理缓存；WebView provider 更新后的设备级验证仍待补充。
- [ ] source 级 API 子域名 allowlist 和 redirect policy。
- [ ] P3：ArrayBuffer 二进制传输；设备级回归测试按对应 P0/P1 能力同步补充。

状态标记说明：`[~]` 表示已有可运行的局部实现，但尚未满足目标模型的完整契约；不能据此视为
对应 P0 已完成。

## BrowserSession 目标模型

`BrowserTransport`、challenge resolver 和 UI 不应通过层层 callback 互相寻找 session。目标是由
`BrowserSessionManager` 按 origin 管理 `BrowserSession`，并由单一入口完成：

```text
session.execute(request)
  -> fetch
  -> challenge detection
  -> automatic/interactive resolve
  -> retry original request
  -> Success / TransportFailure
```

BrowserSession identity = origin + WebView instance。Session 只拥有 WebView、browser state、`rendererEpoch`、
`navigationEpoch`、RPC pending state、challenge state 和 lifecycle；不拥有 source identity 或 request policy。
当前操作封装为 `BrowserRequest`，携带 HTTP request、`SourceRequestContext`、`BrowserRequestPolicy`、
retry policy 和 `requestId`，并通过 `session.execute(browserRequest)` 执行。授权只在本次执行中
校验，不能随 session reuse 累积。`onRenderProcessGone()` 后 session 进入 poisoned 状态，递增
`rendererEpoch` 和 `navigationEpoch`、
失败所有 pending RPC 并从 pool 移除；下一个请求创建新 session。诊断信息记录 session id、origin、
WebView provider package/version、创建和最近使用时间；renderer PID 在平台可提供时记录。

多个 WebView 可能共享同一个 renderer process。每个收到 `onRenderProcessGone()` 的 session 独立 poison、
移除并销毁自己的 WebView；不根据 renderer identity 主动清理其他 session，平台会通知其他受影响的
WebView。P1 同时记录 `onRenderProcessUnresponsive()`/`onRenderProcessResponsive()`、持续时间、pending
RPC 数和 challenge state；第一版只诊断，不自动终止 renderer。

Session LRU 仅淘汰 `pendingOperations == 0`、无 challenge 且未 attach 到 UI 的 session。`FETCHING`、
`RESOLVING_AUTOMATIC`、`WAITING_FOR_USER`、`VALIDATING` 和 `RETRYING_REQUEST` 都是 pinned 状态。

### BrowserSession 与 BrowserExecution 状态机

Session 生命周期为 `CREATING -> READY -> BUSY -> POISONED -> DESTROYED`；以下是单次
`BrowserExecution` 状态，不代表 session 生命周期：

```text
READY -> FETCHING
FETCHING -> READY                 (success)
FETCHING -> CANCELLED -> READY    (request cancelled)
FETCHING -> CHALLENGE_DETECTED
CHALLENGE_DETECTED -> RESOLVING_AUTOMATIC
RESOLVING_AUTOMATIC -> VALIDATING (resolution evidence)
RESOLVING_AUTOMATIC -> WAITING_FOR_USER
WAITING_FOR_USER -> CANCELLED -> READY
WAITING_FOR_USER -> VALIDATING   (resolution evidence)
VALIDATING -> RETRYING_REQUEST
RETRYING_REQUEST -> READY         (success)
RETRYING_REQUEST -> CHALLENGE_DETECTED / FAILED
ANY SESSION STATE -> POISONED     (renderer gone / fatal WebView state)
```

challenge resolution 成功不等于原始 request 成功；必须重试原始 GET/POST 并检查实际响应。
单次 execution 的 `FAILED`/`CANCELLED` 是 operation 终态，清理 pending state 后 session 回到 `READY`；
只有 `POISONED` 会终止整个 session，并最终进入 `DESTROYED`。
UI 只接收 `InteractiveChallengeRequired(sessionId, challengeId, displayMetadata)`，通过 session id
临时 attach 同一 WebView；验证完成后 detach，session 继续执行并重试，不向 UI 转移 WebView 所有权。

## Kagane 集成测试矩阵

- BrowserTransport 的 GET/POST API 未遇 challenge -> 直接成功。
- API challenge -> 来源列表错误态 -> 普通内置浏览器打开源首页 -> 用户主动返回 -> retry GET/POST -> success。
- 首页出现 checkbox 或仍在加载时，BrowserActivity 不得因旧 Cookie、标题变化或 `onPageFinished` 自动关闭。
- 用户从普通浏览器主动返回 -> resolver 只触发一次列表重试；若仍失败，后续只能由明确的用户操作再次打开，
  不能形成自动弹窗循环。
- 非 Mihon 调用方启用交互 challenge 时取消 -> AbortController、pending RPC 和 session 状态确定性收敛。
- challenge 期间 renderer crash -> 所有请求失败、session 移除、下次请求重建。
- API host 已声明时允许；未声明 host、跨 origin redirect 时拒绝。
- 迟到消息必须同时匹配 `sessionId`、`rendererEpoch`、`navigationEpoch` 和 `requestId`，任一不匹配即忽略。
- 同 origin、不同 source context 复用同一 BrowserSession 时，A 的额外 fetch origin 不得泄漏给 B。
- 页面 origin 不允许 CORS 读取 API 响应时，cross-origin fetch 明确失败并切换到 API origin BrowserSession。

响应大小限制是 transport acceptance limit：有 `Content-Length` 时应在 `response.text()` 前拒绝超过
8 MiB 的响应；无该头时仍可能先在 renderer 中形成完整 body，不能将 8 MiB 限制表述为绝对 OOM 防护。
