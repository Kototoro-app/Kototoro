# Cloudflare Resolver 改进任务

## 目标

统一 Mihon、JAR、Legado、LNReader、IReader 等宿主源的 Cloudflare 处理流程；Cloudstream 优先使用插件自身 solver，宿主 resolver 作为 fallback。

## 当前已完成

- 同 host 自动 resolver 使用互斥锁。
- 可见人工 WebView 全局串行化。
- 自动成功后再次遇到 challenge 时进入人工流程。
- 人工成功后重复 challenge 使用 cooldown，避免无限重复验证。
- 搜索列表、普通列表统一提供“开始验证”和“在浏览器中打开”。
- 搜索列表缺少 source 的 CF 异常会使用当前 source 补全。
- 自动 WebView 现在区分以下结果：
  - `SOLVED`
  - `INTERACTIVE_REQUIRED`
  - `HARD_BLOCKED`
  - `TIMED_OUT`
  - `COOLDOWN`
  - `FAILED`
- 检测到仍处于 challenge 且可见 Turnstile 控件时，自动流程提前转人工，不再固定等待完整超时。

## 设计边界

宿主不实现坐标点击、Accessibility 点击或第三方打码服务。Turnstile checkbox 位于跨域 iframe 中，点击成功还依赖 Cloudflare 的浏览器指纹、Cookie、UA 和网络环境。自动阶段只让 WebView 自己运行 challenge，并以真实 HTTP probe 作为最终成功条件。

## 后续任务

- [x] 将 `CloudFlarePageState` 扩展为 `NORMAL`、`LOADING`、`MANAGED_CHALLENGE`、`INTERACTIVE_CHALLENGE`、`HARD_BLOCK`。
- [x] 把 source、host、challenge URL、原始请求 URL、UA 和 headers 统一放入 CF 请求上下文（`CloudFlareRequestContext`）。
- [x] 人工验证后执行真实 probe：协调器在人工流程完成后用原始请求 URL 做真实 HTTP probe，仍被 challenge 时进入 cooldown，不再仅依据 Cookie 变化判定；自动求解（`SOLVED`）后同样用真实 probe 复验，Cookie 存在但真实请求仍失败时进入 cooldown。
- [x] 增加主文档错误与 Turnstile 子资源错误的区分：自动求解 WebView 主文档加载失败时快速失败（`FAILED`），Turnstile 子资源（`challenges.cloudflare.com`）错误仅记录诊断日志。
- [ ] 统一所有 parser、图片、下载、播放器和后台任务入口的 resolver 调用。
- [x] 为页面状态、自动结果、cooldown 和 CF 请求上下文补充单元测试。
- [x] 补充 source 缺失（`UnknownContentSource`）与多 host 并发状态隔离的单元测试。
- [x] 增加 `CaptchaResolver` 结构化日志，禁止记录完整 Cookie、token 和 Authorization（只记录 header 名称、body 长度与脱敏指纹）。

## 验收标准

1. 可自动通过的 challenge 无感恢复原始请求。
2. 需要勾选的 challenge 在几秒内打开可见验证页。
3. 验证完成后，真实 OkHttp 请求不再返回 Cloudflare challenge 才关闭页面。
4. Cookie 存在但真实请求仍失败时进入 cooldown，不循环弹出验证。
5. 不同 host 的自动流程互不串扰，同一 host 不出现多个验证窗口。
