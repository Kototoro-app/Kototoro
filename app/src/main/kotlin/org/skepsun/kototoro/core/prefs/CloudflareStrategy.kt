package org.skepsun.kototoro.core.prefs

/**
 * Cloudflare 自动验证策略（替代原 WebView transport 布尔开关）。
 *
 * 取值与语义：
 * - MIHON：Mihon/Komikku 风格，**默认策略**。应用层 OkHttp 拦截器在收到挑战后，用离屏 WebView
 *   加载同一 URL 求解，等到新 cf_clearance 写入共享 CookieManager（WebViewClearanceSolver），
 *   然后重试原请求；解不动时抛异常转人工浏览器。
 * - MANUAL：不做自动求解。检测到挑战后抛 CloudFlareProtectedException，由错误卡/协调器打开内置
 *   浏览器（BrowserActivity）人工验证。这是 Kotatsu-Redo / 上游 Kotatsu 的策略。
 * - TRANSPORT：Kototoro 自研的旧 WebView transport（Browser Transport，fetchWithBrowserContext）。
 *   已归档保留，仅在显式选择时启用。
 *
 * 解析器侧 WebView API（evaluateJs / loadPageHtml / sniff* / loginAndCheck）不受任何策略影响。
 */
enum class CloudflareStrategy {
    MANUAL,
    MIHON,
    TRANSPORT,
}
