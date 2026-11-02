# iOS JAR 源 Cloudflare 验证诊断（2026-10-21）

## 已确认的问题

- 使用 iOS 预览同一套真实插件请求 WENKU8：
  `https://www.wenku8.net/modules/article/articlelist.php` 返回
  `403 / Server: cloudflare / cf-mitigated: challenge`。这是实际挑战，不是仅凭站点使用 Cloudflare 而误判。
- 修复前 browserSession 返回 Android 移动版 Chrome UA，实际请求却携带 SDK 的桌面 Chrome 120 UA。
  ParserRequestInterceptor 原本追加在 SDK UserAgentInterceptor 后，默认 UA 已被抢先填入。
- SDK 的 CloudflareInterceptor 属于 Suwayomi 服务端流程，iOS 没有 FlareSolverr，遇到响应直接抛出
  “Cloudflare bypass currently disabled”，没有提供原始响应 URL。
- UI 的 challenge 只传递 origin，浏览器原本打开首页，而不是实际被拦截的目录页。
  首页正常不代表目录路径没有挑战。
- 自动 solver 和手动浏览器原本只检查 cf_clearance 是否存在。
  陈旧 Cookie 会导致网页仍在转圈时提前显示“已通过验证”。
- 自动成功后的 retry 原本在 ChallengeVerifyButton 的 SwiftUI .task 中直接 await。
  retry 清除 challenge 后错误视图被移除，该 .task 被取消，SourceBrowseModel 随即丢弃请求结果和错误，
  表现为“没有内容”。响应探针确认该状态出现时底层仍有 CF 403，并非已返回空目录。

## 修复边界

1. ParserRequestInterceptor 排在平台默认头之前；显式请求头继续优先。
2. iOS 使用真实 WKWebView 的 navigator.userAgent 作为解析器默认值；源自己的 UA 配置继续优先。
   无原生桥的 JVM 探测保留既有默认值。
3. 将固定 SDK 的服务端 CF 拦截器替换为 iOS 交接实现，复用 parser-api 的 CloudFlareHelper。
   普通 403 不自动变成浏览器挑战；保留实际挑战 URL、UA 和响应资源关闭。
4. ParserPlatform 的 browserSession 提供默认实现。iOS 可返回最近实际被拦截的路径，
   Swift 仅在 origin 匹配时使用它，保留跨域挑战的 origin 优先级。
5. 自动 solver 移除 WebKit 的旧 clearance，等待新 Cookie、正常主文档响应和无挑战 DOM。
   同站点/UA 并发调用共享一次求解；30 秒超时、120 秒节流。
6. 手动浏览器清除导航前的成功状态；403/503、挑战 DOM 或检查未完成时禁用“完成”。
   Cookie 更新会重新检查当前页面，导航代次检查避免旧回调覆盖新页面。
7. 自动求解后的 retry 使用独立 Task，避免错误视图移除时取消自身发起的请求。

## 与 Android / Aidoku 的比较

| 项目 | iOS 修复前 | Android Mihon 风格 | Aidoku main（本次查阅） |
| --- | --- | --- | --- |
| 自动验证入口 | UI 错误按钮的 task | OkHttp 拦截器 | 原生网络挑战处理器 |
| 浏览器 | WKWebView | Android WebView | WKWebView |
| 新 clearance 判断 | 仅存在即成功 | 移除旧值并判断新值 | 比较新旧 Cookie 值 |
| 页面状态 | 没有检查 | HTTP/页面事件与挑战状态 | HTTP 状态与验证码 DOM |
| 并发 | 后来的请求直接被节流 | 同 host 协调求解 | 同 host 共享任务、全局串行 |
| 超时 | 20 秒 | 30 秒 | 静默阶段 12 秒，显示交互窗口后停止超时 |
| 交互回退 | 用户点按钮打开另一个浏览器 | 原有错误恢复流程 | 将同一 WebView 移入弹窗 |
| 验证后请求 | 导入 Cookie 后 UI 重试 | 同一拦截器内重试一次 | 修改原请求并经 URLSession 重试 |

iOS 仍由 UI 触发自动求解，并没有完全迁移 Android 的拦截器级重试或 Aidoku 的同一 WebView 弹窗交接。
真实交互挑战不会因为这些修复就保证自动通过。

## 验证与限制

- UA 差异回归先失败，调整拦截器顺序后通过。
- parser-host 全套 JVM 测试通过；需要外部参数的在线/真实 APK 用例按既有规则跳过。
- mihon-ios-compat 全套 8 项测试通过，包含普通 403、实际路径/UA 保留与正常页面背景 CF 脚本。
- BrowserVerificationStateTests（3 项，模拟器通过）覆盖旧 Cookie + 挑战 DOM、403/503、导航/JS 未完成和正常页成功。
- 在线 WENKU8 回归：旧版与第一轮修复上均失败（打开浏览器后仍未恢复列表），本批修复后 **iOS 侧已恢复**，
  2026-10-21 由用户在 iOS 上确认，浏览列表可正常加载。
- 失败轮次留下的判据仍然有效：修复成功提示或 Cookie 导入本身不能当作在线源已恢复的证明，
  唯一判据是列表真实加载。

## 上游参考

- [Aidoku CloudflareHandler](https://github.com/Aidoku/Aidoku/blob/main/Aidoku/Core/Sources/Cloudflare/CloudflareHandler.swift)
- [Aidoku #1034](https://github.com/Aidoku/Aidoku/issues/1034) 报告相同 UA/Cookie 下 WebKit 成功、
  URLSession 仍受挑战的情况。这是上游用户报告，不能据此直接认定本次 WENKU8 也是相同根因。
