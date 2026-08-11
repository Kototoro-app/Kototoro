# 统一 Cloudflare Solver 实施任务

更新时间：2026-08-11

## 目标

将应用内分散的 Cloudflare 自动验证收敛为一条可预测的宿主流程，同时保留 Cloudstream 等插件生态的原生处理能力。

```text
插件原生处理
  -> 宿主检测到 Cloudflare challenge
  -> 统一 Turnstile WebView 会话
  -> 调用方重试真实请求并重新检测
  -> 可见浏览器人工验证
```

## 设计边界

- Cloudstream 的 `WebViewResolver` 和插件交互优先，统一 solver 只作为无可播放链接时的兜底。
- JAR、Kotatsu、Tsuki、LNReader、Legado、TVBox 等来源通过通用 CF 异常进入统一 solver。
- IReader 复用宿主 HTTP 客户端；SDK 主动 `CloudflareBypassHandler` 也桥接到统一 solver。
- Mihon 与 Aniyomi 仅保留挑战检测、请求上下文适配和上层业务重试；自动及人工求解统一进入宿主 resolver。
- 自动求解不得直接放入基础 `ContentHttpClient` 拦截器，避免网络客户端、仓库工厂和 WebView 执行器形成依赖环。
- 后台任务不得弹出可见 Activity；前台自动求解失败后才进入人工验证。

## 成功条件

自动 WebView 会话满足任一条件即视为可重试：

1. `cf_clearance` 相对会话开始时发生变化。
2. 页面完成加载且不再包含 Cloudflare/Turnstile challenge 状态。

求解成功只表示调用方可以重试，不代表原业务请求已经成功。Cloudstream 在插件/网络层复验响应；
Mihon、Aniyomi 及其他来源由上层页面或仓库操作重新执行原业务请求。

## 覆盖矩阵

| 来源 | 插件原生优先 | 统一 Turnstile | 真实请求复验 | 人工兜底 |
| --- | --- | --- | --- | --- |
| Cloudstream | 是 | 无播放链接时 | `loadLinks` 重试 | 全局异常流程 |
| Mihon / Aniyomi | 兼容拦截器仅检测 | 是 | 上层业务请求重试 | `CloudFlareActivity` |
| JAR / Kotatsu / Tsuki | 插件可请求浏览器 | 是 | 页面/仓库重载 | `CloudFlareActivity` |
| LNReader | 否 | 是 | QuickJS 操作重载 | `CloudFlareActivity` |
| Legado / TVBox | 规则 WebView 优先 | 是 | 页面/仓库重载 | `CloudFlareActivity` |
| IReader | SDK 主动 bypass 优先 | 是 | 页面/仓库重载 | `CloudFlareActivity` |

## 实施状态

- [x] Cloudstream 插件原生处理优先，Turnstile 作为无链接兜底。
- [x] Cloudstream 插件 Activity 桥接对齐原宿主：暂停时保留兼容引用，销毁时安全清理。
- [x] Cloudstream 分流解析期间保持播放器窗口常亮，避免插件延迟弹窗前宿主失效。
- [x] 通用自动协调器改为 Turnstile 优先，不再重复启动隐藏 CF Activity。
- [x] 自动失败后仅在前台启动可见人工验证。
- [x] Turnstile 会话继承触发请求的 UA、Referer、Origin 和安全自定义头。
- [x] Mihon/Aniyomi 移除专用 WebView/人工求解状态机，统一抛出宿主 CF 异常。
- [x] 自动与人工流程共用真实页面状态判定，普通主页的 CF 后台脚本不再被误判为 challenge。
- [x] 自动 resolver 按 host 串行、不同 host 可并行；所有可见人工验证全局排队，避免窗口和完成信号串扰。
- [x] 自动成功后再次挑战会升级人工验证；人工成功后仍再次挑战则对 host 冷却 2 分钟并快速失败。
- [x] 列表错误态的“开始验证”直接进入人工 resolver，不再重复隐藏自动阶段。
- [x] 视频播放器允许统一自动求解后重试播放解析。
- [x] Legado 请求改用通用 `CloudFlareHelper`，二进制请求也能识别 challenge。
- [x] 修复 LNReader `fetchBinary/fetchProto` 的 CF 致命异常隧道。
- [x] IReader 请求注入来源 tag，主动 bypass 接入统一 solver，并保留 CF/交互异常隧道。
- [ ] 为 WebView 会话增加设备级仪器测试，覆盖真实窗口挂载与 Cookie 同步。
- [ ] 增加求解成功后通用请求复验接口，逐步替代页面级粗粒度重载。

## 风险与验证

- Mihon 兼容请求使用宿主运行时 WebView UA，保证请求的 `User-Agent`、Chromium Client Hints
  与生成 `cf_clearance` 的浏览器实例一致；固定的旧版 UA 会使有效 Cookie 被 Cloudflare 拒绝。

- Turnstile 对 WebView 可见性和真实 Surface 敏感，必须保留前台 Activity 挂载。
- UA、Cookie、Referer 或 Origin 任一不一致都可能导致 clearance 被真实请求拒绝。
- 自动求解全局串行，并在 host 失败后冷却，避免多个来源同时创建 WebView。
- JVM 单元测试覆盖安全请求头筛选；真实 Turnstile 必须在设备上验证。

## 2026-08-11 Anichi 实机验证

- `ForegroundActivityHolderTest` 覆盖 pause 后兼容引用保留、destroy 后清理及 Activity 切换顺序。
- Cloudstream 请求上下文、内容仓库和 WebView 请求头定向测试通过。
- `:app:assembleDebug` 通过并部署到 arm64 实机。
- Anichi 在约 39 秒网络回退后成功展示插件自带的 `AnichiTurnstileDialog`，不再立即返回零链接。
- Dialog 显示期间系统状态为 `mWakefulness=Awake`、`mHoldScreenWindow=VideoPlayerActivity`，解析期常亮生效。
- 人工完成一次 Turnstile 后，插件拦截到 10082 字节剧集 API 响应并返回 12 条播放链接
  （4 条 M3U8、8 条 VIDEO），播放器成功开始播放。
- 后续验证复用已有 Cloudflare 状态，Dialog 展示后约 6 秒自动完成。

### 重启后的 Anichi Dialog

- 应用重启后，Anichi Dialog 打开的页面可直接进入 `mkissa.to` 真实站点，证明 WebView Cookie 和
  `cf_clearance` 已持久化；此时再次显示 Dialog 不是统一 solver 重复要求 Cloudflare 验证。
- Anichi v24 将 `mkissaBuildId`、key mask、bootstrap AES key、epoch 和 WebView 捕获的 AES key
  保存为插件进程内静态字段。进程重启后这些字段全部丢失，但 Cookie 仍然存在。
- 插件把两种职责合并在同一个 `AnichiTurnstileDialog` 中：存在挑战时供用户完成 Turnstile；不存在挑战时
  仍需运行剧集页面 JavaScript，截获 AES key 及包含 `sourceUrls` 的 Fetch/XHR 响应。因此真实网站页面
  也会短暂可见，并在截获响应后自动关闭。
- Cloudstream 原宿主同样会在新进程中重新调用插件的 `loadLinks`，不会替插件持久化上述加密状态或分流。
  Kototoro 不通过反射持久化插件私有密钥；该做法与插件版本强耦合，也可能在服务端轮换 epoch/key 后制造
  难以恢复的陈旧状态。
- 正确的插件侧改进是把流程拆为“后台页面取数”和“检测到交互式 challenge 后切换到可见 Dialog”。宿主侧
  短时持久化最终播放地址只能优化重启后播放同一集，不能替代新剧集所需的页面脚本执行。

## Novela 对照与浏览器兜底

Novela 的 Cloudflare 实现仍使用应用内 WebView：网络拦截器启动 `WebViewActivity`，通过
`CloudflareBypassSignal` 按 host 等待终态，并同时轮询 `cf_clearance` 后发起真实请求探测。仅出现
Cookie 不会立即判定成功；探测通过后才广播完成，目录、全局搜索和阅读会话据此刷新。这个设计说明
“验证完成”与“业务请求成功”必须分开处理。

Kototoro 的列表错误卡现已补齐第二操作：当异常携带来源 URL（尤其是 `CloudFlareProtectedException`）时，
在“开始验证/重试”按钮下显示“在浏览器中打开”，并启动应用内 `BrowserActivity`。该入口不会被统一
CF Activity 的自动完成信号关闭，用户可以在浏览器页完成验证后返回并手动刷新列表。系统外部浏览器的
Cookie 通常不与应用 WebView 共享，因此不把 `ACTION_VIEW` 作为 Cookie 回传或自动成功的保证。

统一协调器采用与 NoveLA 相同的关键状态约束：同一 host 的 resolver 请求串行，不同 host 的自动 WebView
可并行；可见人工验证使用独立全局队列。自动阶段得到新 clearance 后由原业务调用方重试确认；若 challenge
立即重复则只升级一次人工验证，人工验证后仍重复则进入 2 分钟 host cooldown。这样即使 WebView 与 OkHttp
的 TLS/浏览器指纹无法兼容，也不会无限弹出验证窗口。
