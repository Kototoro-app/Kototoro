import Foundation
import WebKit

/// A bounded, read-only fallback for requests Cloudflare rejects over URLSession despite valid browser cookies.
@MainActor
final class WebKitHttpRequest: NSObject, WKNavigationDelegate {
    private var view: WKWebView?
    private var timer: Task<Void, Never>?
    private var completion: ((String?) -> Void)?
    private let payload: [String: Any]
    private let url: URL

    init?(payload: [String: Any], completion: @escaping (String?) -> Void) {
        guard let address = payload["url"] as? String, let url = URL(string: address),
              ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
              url.user == nil, url.password == nil,
              let method = payload["method"] as? String, ["GET", "HEAD"].contains(method) else { return nil }
        self.url = url
        self.payload = payload
        self.completion = completion
    }

    func start() {
        timer = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(24)) } catch { return }
            self?.finish(nil)
        }
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        let view = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: configuration)
        self.view = view
        let headers = payload["headers"] as? [String: String] ?? [:]
        view.customUserAgent = headers.first { $0.key.lowercased() == "user-agent" }?.value
        view.navigationDelegate = self
        let store = configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { [weak self] existing in
            guard let self, self.completion != nil else { return }
            // Restore native-persisted login cookies after an app restart, without replacing fresh WebKit clearance.
            let cookies = (self.payload["cookies"] as? [String] ?? []).flatMap {
                HTTPCookie.cookies(withResponseHeaderFields: ["Set-Cookie": $0], for: self.url)
            }.filter { cookie in
                !existing.contains {
                    $0.name == cookie.name && $0.path == cookie.path
                        && $0.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
                            == cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
                }
            }
            let group = DispatchGroup()
            for cookie in cookies {
                group.enter()
                store.setCookie(cookie) { group.leave() }
            }
            group.notify(queue: .main) { [weak self] in
                guard let self, self.completion != nil else { return }
                view.loadHTMLString("<!doctype html><html><body></body></html>", baseURL: self.url)
            }
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let supplied = payload["headers"] as? [String: String] ?? [:]
        let headers = supplied.filter { Self.allowsHeader($0.key) }
        let arguments: [String: Any] = [
            "address": url.absoluteString, "method": payload["method"] as? String ?? "GET", "headers": headers,
        ]
        webView.callAsyncJavaScript(Self.fetchScript, arguments: arguments, in: nil, in: .page) { [weak self] result in
            guard let self, self.completion != nil else { return }
            guard case .success(let value) = result, var response = value as? [String: Any] else {
                self.finish(nil)
                return
            }
            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
                guard let self else { return }
                response["cookies"] = cookies.filter { ExtensionWebKit.matches($0, self.url) }.map(ExtensionWebKit.header)
                let encoded = try? JSONSerialization.data(withJSONObject: response)
                self.finish(encoded.flatMap { String(data: $0, encoding: .utf8) })
            }
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { finish(nil) }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        finish(nil)
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { finish(nil) }

    private func finish(_ value: String?) {
        guard let done = completion else { return }
        completion = nil
        timer?.cancel()
        timer = nil
        view?.navigationDelegate = nil
        view?.stopLoading()
        view = nil
        done(value)
    }

    static func allowsHeader(_ name: String) -> Bool {
        let name = name.lowercased()
        let forbidden: Set<String> = [
            "accept-charset", "accept-encoding", "access-control-request-headers", "access-control-request-method",
            "connection", "content-length", "cookie", "cookie2", "date", "dnt", "expect", "host", "keep-alive",
            "origin", "referer", "set-cookie", "te", "trailer", "transfer-encoding", "upgrade", "user-agent", "via",
        ]
        return !forbidden.contains(name) && !name.hasPrefix("sec-") && !name.hasPrefix("proxy-")
    }

    // Preserve raw bytes: Wenku8 and other parsers explicitly decode GBK, not the rendered document's UTF-8 DOM.
    private static let fetchScript = """
    const response = await fetch(address, {
        method, headers, credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(18000)
    });
    const chunks = [];
    let total = 0;
    if (response.body) {
        const reader = response.body.getReader();
        while (true) {
            const {value, done} = await reader.read();
            if (done) break;
            total += value.length;
            if (total > 8 * 1024 * 1024) { await reader.cancel(); throw new Error('Response too large'); }
            for (let i = 0; i < value.length; i += 16384) {
                chunks.push(String.fromCharCode(...value.subarray(i, i + 16384)));
            }
        }
    }
    return {status: response.status, headers: Object.fromEntries(response.headers), body: btoa(chunks.join(''))};
    """
}
