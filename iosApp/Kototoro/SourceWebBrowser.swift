import KototoroShared
import SwiftUI
import WebKit

struct BrowserTarget: Identifiable {
    let url: URL
    /// The User-Agent the source's requests carry: Cloudflare binds its clearance to it, so the page must use it too.
    let userAgent: String?
    var id: String { url.absoluteString }

    static func verification(origin: String?, session: IosBrowserSession?) -> BrowserTarget? {
        let challenged = origin.flatMap(URL.init(string:))
        let requested = session.flatMap { URL(string: $0.url) }
        let sameSite = challenged == nil || (challenged?.host == requested?.host
            && challenged?.scheme == requested?.scheme && challenged?.port == requested?.port)
        guard let url = sameSite ? (requested ?? challenged) : challenged,
              ["https", "http"].contains(url.scheme?.lowercased() ?? "") else { return nil }
        return BrowserTarget(url: url, userAgent: session?.userAgent)
    }
}

/// The in-app browser for a source's site: manual Cloudflare (or login) verification, the way Mihon opens a source
/// in a WebView. On 完成 the site's cookies go back to the source runtime, so its next requests carry the clearance.
struct SourceWebBrowser: View {
    let target: BrowserTarget
    let runtime: IosSources
    let sourceName: String
    /// Called after the cookies were handed back (with how many were kept), e.g. to retry the failed request.
    var onFinish: (Int) -> Void = { _ in }
    @Environment(\.dismiss) private var dismiss
    @State private var page = BrowserPage()
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            BrowserWebView(page: page, target: target)
                .ignoresSafeArea(edges: .bottom)
                .overlay(alignment: .top) {
                    if page.isLoading { ProgressView(value: page.progress).progressViewStyle(.linear) }
                }
                .safeAreaInset(edge: .bottom) { status }
                .navigationTitle(page.title.isEmpty ? (target.url.host() ?? String(localized: "浏览器")) : page.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("关闭", systemImage: "xmark") { dismiss() }.accessibilityIdentifier("browser.close")
                    }
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button("刷新", systemImage: "arrow.clockwise") { page.reload() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { Task { await finish() } }
                            .disabled(saving || !page.canFinish)
                            .accessibilityIdentifier("browser.done")
                    }
                }
        }
    }

    @ViewBuilder
    private var status: some View {
        let text: String? = if let error { error }
            else if page.hasClearance { String(localized: "已通过验证，点“完成”后返回重试") }
            else { String(localized: "如出现验证页面，请在此完成验证，然后点“完成”") }
        if let text {
            Label(text, systemImage: page.hasClearance ? "checkmark.shield" : "shield.lefthalf.filled")
                .font(.footnote)
                .foregroundStyle(error != nil ? .red : page.hasClearance ? .green : .secondary)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .glassEffect(in: .capsule)
                .padding(.bottom, 8)
                .accessibilityIdentifier("browser.status")
        }
    }

    private func finish() async {
        saving = true
        defer { saving = false }
        await page.refreshClearance(for: target.url)
        guard page.canFinish else { return }
        let cookies = await page.cookies(for: target.url)
        guard !cookies.isEmpty else { onFinish(0); dismiss(); return }
        do {
            let kept = try await runtime.importCookies(sourceName: sourceName, url: target.url.absoluteString, cookies: cookies)
            onFinish(Int(kept))
            dismiss()
        } catch {
            self.error = SourceErrorText.describe(error)
        }
    }
}

/// Page state shared between the SwiftUI chrome and the web view.
@MainActor @Observable
final class BrowserPage {
    var title = ""
    var isLoading = false
    var progress = 0.0
    var hasClearance = false
    var canFinish = false
    var mainFrameStatus: Int?
    var navigationRevision = 0
    weak var webView: WKWebView?

    func reload() { webView?.reload() }

    /// The site's cookies as `Set-Cookie` lines (its host and parent domains), for the source's cookie jar.
    func cookies(for url: URL) async -> [String] {
        guard let host = url.host()?.lowercased() else { return [] }
        let all = await WKWebsiteDataStore.default().httpCookieStore.allCookies()
        return all.filter { cookie in
            let domain = cookie.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
            return host == domain || host.hasSuffix("." + domain)
        }.map(Self.setCookieLine)
    }

    func refreshClearance(for url: URL) async {
        guard let host = url.host()?.lowercased() else { return }
        let revision = navigationRevision
        let challenge = (try? await webView?.evaluateJavaScript(ChallengeNavigation.challengeScript)) as? Bool
        guard revision == navigationRevision else { return }
        let state = BrowserVerificationState(status: mainFrameStatus, challengePresent: challenge)
        canFinish = state.verified(hasClearance: true)
        guard canFinish else {
            hasClearance = false
            return
        }
        let all = await WKWebsiteDataStore.default().httpCookieStore.allCookies()
        guard revision == navigationRevision else { return }
        hasClearance = all.contains { cookie in
            let domain = cookie.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
            return cookie.name == "cf_clearance" && (cookie.expiresDate == nil || cookie.expiresDate! > Date())
                && (host == domain || host.hasSuffix("." + domain))
        }
    }

    static func setCookieLine(_ cookie: HTTPCookie) -> String {
        var parts = ["\(cookie.name)=\(cookie.value)", "Domain=\(cookie.domain)", "Path=\(cookie.path)"]
        if let expires = cookie.expiresDate {
            let format = DateFormatter()
            format.locale = Locale(identifier: "en_US_POSIX")
            format.timeZone = TimeZone(identifier: "GMT")
            format.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
            parts.append("Expires=\(format.string(from: expires))")
        }
        if cookie.isSecure { parts.append("Secure") }
        if cookie.isHTTPOnly { parts.append("HttpOnly") }
        return parts.joined(separator: "; ")
    }
}

private struct BrowserWebView: UIViewRepresentable {
    let page: BrowserPage
    let target: BrowserTarget

    func makeCoordinator() -> Coordinator { Coordinator(page: page, target: target) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.customUserAgent = target.userAgent
        view.allowsBackForwardNavigationGestures = true
        view.navigationDelegate = context.coordinator
        context.coordinator.observe(view)
        page.webView = view
        view.load(URLRequest(url: target.url))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.configuration.websiteDataStore.httpCookieStore.remove(coordinator)
        view.navigationDelegate = nil
        view.stopLoading()
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKHTTPCookieStoreObserver {
        let page: BrowserPage
        let target: BrowserTarget
        private var observations: [NSKeyValueObservation] = []

        init(page: BrowserPage, target: BrowserTarget) {
            self.page = page
            self.target = target
        }

        func observe(_ view: WKWebView) {
            view.configuration.websiteDataStore.httpCookieStore.add(self)
            observations = [
                view.observe(\.title) { [page] view, _ in Task { @MainActor in page.title = view.title ?? "" } },
                view.observe(\.isLoading) { [page] view, _ in Task { @MainActor in page.isLoading = view.isLoading } },
                view.observe(\.estimatedProgress) { [page] view, _ in
                    Task { @MainActor in page.progress = view.estimatedProgress }
                },
            ]
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            page.hasClearance = false
            page.canFinish = false
            page.mainFrameStatus = nil
            page.navigationRevision += 1
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                     decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            if navigationResponse.isForMainFrame {
                page.mainFrameStatus = (navigationResponse.response as? HTTPURLResponse)?.statusCode
                if page.mainFrameStatus == 403 || page.mainFrameStatus == 503 {
                    page.hasClearance = false
                    page.canFinish = false
                }
            }
            decisionHandler(.allow)
        }

        func cookiesDidChange(in cookieStore: WKHTTPCookieStore) {
            Task { @MainActor in await page.refreshClearance(for: target.url) }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor in await page.refreshClearance(for: target.url) }
        }
    }
}

/// Readable text for any error shown on a source screen: the shared layer's wording for source protocol failures
/// (including browser challenges), the system description otherwise.
enum SourceErrorText {
    static func throwable(_ error: Error) -> KotlinThrowable? {
        (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }

    static func describe(_ error: Error) -> String {
        throwable(error).flatMap { IosSourceErrors.shared.message(error: $0) } ?? error.localizedDescription
    }

    static func challenge(_ error: Error) -> IosChallenge? {
        throwable(error).flatMap { IosSourceErrors.shared.challenge(error: $0) }
    }
}

/// Mihon's automatic Cloudflare pass, done the iOS way: an invisible WKWebView with the source's User-Agent loads the
/// challenged site, the managed challenge runs its script, and once `cf_clearance` appears the cookies go back to the
/// source runtime. Interactive challenges (a checkbox to tick) do not clear on their own; the caller then offers the
/// visible browser. One attempt per site per two minutes, so a site that keeps challenging cannot loop.
@MainActor
final class ChallengeSolver {
    static let shared = ChallengeSolver()
    private var attempts: [String: Date] = [:]
    private var active: [String: Task<[String]?, Never>] = [:]
    private static let timeout: Duration = .seconds(30)

    /// True when the clearance was obtained and handed to the runtime; the caller should retry its request.
    func solve(runtime: IosSources, sourceName: String, challenge: IosChallenge) async -> Bool {
        let session = try? await runtime.browserSession(sourceName: sourceName)
        guard let target = BrowserTarget.verification(origin: challenge.origin, session: session) else { return false }
        let key = (target.url.host() ?? target.url.absoluteString) + "|" + (target.userAgent ?? "")
        let task: Task<[String]?, Never>
        if let pending = active[key] {
            task = pending
        } else {
            if let last = attempts[key], Date().timeIntervalSince(last) < 120 { return false }
            attempts = attempts.filter { Date().timeIntervalSince($0.value) < 120 }
            attempts[key] = Date()
            task = Task { await self.obtainClearance(target: target) }
            active[key] = task
        }
        let cookies = await task.value
        active[key] = nil
        guard !Task.isCancelled, let cookies else { return false }
        let kept = try? await runtime.importCookies(sourceName: sourceName, url: target.url.absoluteString, cookies: cookies)
        return (kept?.intValue ?? 0) > 0
    }

    private func obtainClearance(target: BrowserTarget) async -> [String]? {
        guard let window = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first
        else { return nil }
        let store = WKWebsiteDataStore.default().httpCookieStore
        let previous = await store.allCookies().filter {
            $0.name == "cf_clearance" && ExtensionWebKit.matches($0, target.url)
        }
        // A stale clearance cannot count as success or prevent WebKit from obtaining a fresh one.
        for cookie in previous { await store.deleteCookie(cookie) }
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        if let ua = target.userAgent, ua.contains("iPhone") || ua.contains("iPad") || ua.contains("Android") {
            configuration.defaultWebpagePreferences.preferredContentMode = .mobile
        }
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: configuration)
        let navigation = ChallengeNavigation()
        webView.navigationDelegate = navigation
        webView.customUserAgent = target.userAgent
        // Scripts of a view outside any window are throttled; keep it in the window, transparent and untouchable.
        webView.alpha = 0.01
        webView.isUserInteractionEnabled = false
        window.insertSubview(webView, at: 0)
        defer { webView.stopLoading(); webView.navigationDelegate = nil; webView.removeFromSuperview() }
        webView.load(URLRequest(url: target.url))
        let deadline = ContinuousClock.now + Self.timeout
        let page = BrowserPage()
        while ContinuousClock.now < deadline {
            do { try await Task.sleep(for: .milliseconds(500)) } catch { return nil }
            if navigation.failed { return nil }
            guard navigation.finished else { continue }
            guard let challenged = navigation.challenged else { continue }
            let state = BrowserVerificationState(status: navigation.status, challengePresent: challenged)
            guard state.verified(hasClearance: true) else { continue }
            let fresh = await store.allCookies().contains { cookie in
                cookie.name == "cf_clearance" && ExtensionWebKit.matches(cookie, target.url)
                    && !previous.contains { $0.value == cookie.value }
            }
            guard fresh else { return nil }
            return await page.cookies(for: target.url)
        }
        return nil
    }
}

@MainActor
private final class ChallengeNavigation: NSObject, WKNavigationDelegate {
    var finished = false
    var failed = false
    var status: Int?
    var challenged: Bool?
    static let challengeScript = """
    document.title.toLowerCase().includes('just a moment') ||
    document.querySelector('#challenge-error-title, #challenge-error-text, #challenge-running, #challenge-form, '
        + 'input[name="cf-turnstile-response"], iframe[src*="challenges.cloudflare.com"]') !== null
    """

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        finished = false
        status = nil
        challenged = nil
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if navigationResponse.isForMainFrame {
            status = (navigationResponse.response as? HTTPURLResponse)?.statusCode
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        finished = true
        webView.evaluateJavaScript(Self.challengeScript) { [weak self] value, _ in
            self?.challenged = value as? Bool
        }
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { failed = true }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code != NSURLErrorCancelled { failed = true }
    }
}

/// "在浏览器中验证" for a source failure that was a browser challenge: first tries the automatic pass (as Mihon does),
/// then opens the challenged site (or the source's own) with the User-Agent the source's requests carry, and runs
/// [retry] once the cookies are back in the runtime.
struct ChallengeVerifyButton: View {
    let challenge: IosChallenge
    let runtime: IosSources
    let sourceName: String
    let retry: () async -> Void
    @State private var browsing: BrowserTarget?
    @State private var solving = false

    var body: some View {
        Group {
            if solving {
                ProgressView("正在自动通过验证…").accessibilityIdentifier("source.solving")
            } else {
                Button("在浏览器中验证", systemImage: "safari") { Task { await open() } }
                    .buttonStyle(.glassProminent)
                    .accessibilityIdentifier("source.verify")
            }
        }
        .sheet(item: $browsing) { target in
            SourceWebBrowser(target: target, runtime: runtime, sourceName: sourceName) { _ in Task { await retry() } }
        }
        .task(id: challenge.origin ?? sourceName) {
            solving = true
            let solved = await ChallengeSolver.shared.solve(runtime: runtime, sourceName: sourceName, challenge: challenge)
            solving = false
            // Retrying removes this error view; its SwiftUI task is then cancelled along with the view.
            if solved && !Task.isCancelled { Task { await retry() } }
        }
    }

    private func open() async {
        let session = try? await runtime.browserSession(sourceName: sourceName)
        browsing = BrowserTarget.verification(origin: challenge.origin, session: session)
    }
}
