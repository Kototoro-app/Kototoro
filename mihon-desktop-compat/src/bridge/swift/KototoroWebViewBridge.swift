import AppKit
import WebKit

// The JVM owns this process. stdout is reserved for the existing newline-delimited bridge protocol.
final class BrowserBridge: NSObject, WKNavigationDelegate, NSWindowDelegate, WKScriptMessageHandler {
    private var webView: WKWebView!
    private var window: NSWindow!
    private var navigation: (id: Any, allowHttpError: Bool, token: WKNavigation?)?
    private var observingRequests = false
    // Frame navigations reach the delegate and the parent's resource timing alike; report each request once.
    private var reportedRequests = Set<String>()
    private static let requestHandler = "kototoroRequest"
    // WKWebView reports frame navigations to its delegate but no subresources, so pages report their own:
    // fetch and XMLHttpRequest with the method and headers the page set, everything else from resource timing.
    private static let requestScript = """
    (() => {
      if (window.__kototoroRequests) return; window.__kototoroRequests = true;
      const seen = new Set();
      const post = (url, method, headers) => {
        try {
          const href = new URL(url, location.href).href, verb = (method || 'GET').toUpperCase();
          if (!/^https?:/.test(href) || seen.has(verb + ' ' + href)) return;
          seen.add(verb + ' ' + href);
          window.webkit.messageHandlers.\(requestHandler).postMessage({url: href, method: verb, headers: headers || {}});
        } catch (e) {}
      };
      const fetch0 = window.fetch;
      if (fetch0) window.fetch = function (input, init) {
        try {
          const req = input instanceof Request ? input : null, headers = {};
          const source = (init && init.headers) || (req && req.headers);
          if (source) new Headers(source).forEach((v, k) => { headers[k] = v; });
          post(req ? req.url : String(input), (init && init.method) || (req && req.method), headers);
        } catch (e) {}
        return fetch0.apply(this, arguments);
      };
      const xhr = XMLHttpRequest.prototype, open0 = xhr.open, header0 = xhr.setRequestHeader, send0 = xhr.send;
      xhr.open = function (method, url) { this.__kototoro = {method, url, headers: {}}; return open0.apply(this, arguments); };
      xhr.setRequestHeader = function (k, v) { if (this.__kototoro) this.__kototoro.headers[k] = v; return header0.apply(this, arguments); };
      xhr.send = function () { const r = this.__kototoro; if (r) post(r.url, r.method, r.headers); return send0.apply(this, arguments); };
      // fetch and XHR were reported above with their real method and headers; timing adds what nothing else saw.
      try { new PerformanceObserver(list => list.getEntries()
              .filter(e => e.initiatorType !== 'fetch' && e.initiatorType !== 'xmlhttprequest')
              .forEach(e => post(e.name, 'GET', {})))
              .observe({type: 'resource', buffered: true}); } catch (e) {}
    })();
    """

    private func emitRequest(url: String, method: String, headers: [String: String], mainFrame: Bool) {
        guard reportedRequests.insert(method.uppercased() + " " + url).inserted else { return }
        let event: [String: Any] = ["event": "request", "url": url, "method": method, "headers": headers,
                                    "mainFrame": mainFrame]
        if let data = try? JSONSerialization.data(withJSONObject: event) {
            FileHandle.standardOutput.write(data + Data([10]))
        }
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard observingRequests, let body = message.body as? [String: Any], let url = body["url"] as? String else { return }
        let headers = (body["headers"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
        emitRequest(url: url, method: body["method"] as? String ?? "GET", headers: headers,
                    mainFrame: false)
    }

    func reply(_ id: Any, _ result: [String: Any] = [:], error: String? = nil) {
        let payload: [String: Any] = error.map { ["id": id, "error": ["message": $0]] }
            ?? ["id": id, "result": result]
        if let data = try? JSONSerialization.data(withJSONObject: payload, options: [.fragmentsAllowed]) {
            FileHandle.standardOutput.write(data + Data([10]))
        }
    }

    private func state() -> [String: Any] {
        ["visible": window.isVisible, "width": Int(window.contentLayoutRect.width),
         "height": Int(window.contentLayoutRect.height), "title": webView.title ?? "Kototoro"]
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        sender.orderOut(nil)
        return false
    }

    func handle(_ request: [String: Any]) {
        let id = request["id"] ?? NSNull()
        let p = request["params"] as? [String: Any] ?? [:]
        let method = request["method"] as? String ?? ""
        if method == "close" { NSApp.terminate(nil); return }
        if method == "init" {
            guard webView == nil else { reply(id, error: "Already initialized"); return }
            let config = WKWebViewConfiguration()
            // The SDK cookie jar is the persistent authority; every bridge instance starts with an isolated store.
            config.websiteDataStore = .nonPersistent()
            webView = WKWebView(frame: NSRect(x: 0, y: 0, width: 960, height: 700), configuration: config)
            webView.customUserAgent = p["userAgent"] as? String
            webView.navigationDelegate = self
            window = NSWindow(contentRect: webView.frame, styleMask: [.titled, .closable, .resizable, .miniaturizable],
                              backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.title = "Kototoro Browser"
            window.contentView = webView
            window.delegate = self
            window.center()
            reply(id, ["browserVersion": "WKWebView / " + ProcessInfo.processInfo.operatingSystemVersionString])
            return
        }
        guard webView != nil else { reply(id, error: "Browser not initialized"); return }
        switch method {
        case "navigate", "loadHtml":
            guard navigation == nil else { reply(id, error: "Navigation in progress"); return }
            reportedRequests.removeAll()
            if method == "navigate" {
                guard let text = p["url"] as? String, let url = URL(string: text),
                      ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil else {
                    reply(id, error: "Expected an HTTP(S) URL"); return
                }
                var req = URLRequest(url: url)
                req.httpMethod = p["httpMethod"] as? String ?? "GET"
                for (name, value) in p["headers"] as? [String: String] ?? [:] {
                    guard !name.isEmpty, !name.contains(":"), !name.contains("\r"), !name.contains("\n"),
                          !value.contains("\r"), !value.contains("\n") else {
                        reply(id, error: "Invalid HTTP header"); return
                    }
                    if name.lowercased() == "user-agent" { webView.customUserAgent = value }
                    req.setValue(value, forHTTPHeaderField: name)
                }
                if let encoded = p["bodyBase64"] as? String {
                    guard let body = Data(base64Encoded: encoded) else { reply(id, error: "Invalid bodyBase64"); return }
                    req.httpBody = body
                }
                navigation = (id, p["allowHttpErrorResponse"] as? Bool ?? false, webView.load(req))
            } else {
                let base = (p["baseUrl"] as? String).flatMap(URL.init(string:))
                let token = webView.load(p["html"].map { Data(String(describing: $0).utf8) } ?? Data(),
                             mimeType: p["mimeType"] as? String ?? "text/html",
                             characterEncodingName: "utf-8", baseURL: base ?? URL(string: "about:blank")!)
                navigation = (id, true, token)
            }
        case "observeRequests":
            let enabled = p["enabled"] as? Bool ?? false
            let controller = webView.configuration.userContentController
            if enabled && !observingRequests {
                controller.add(self, name: Self.requestHandler)
                controller.addUserScript(WKUserScript(source: Self.requestScript, injectionTime: .atDocumentStart,
                                                      forMainFrameOnly: false))
            } else if !enabled && observingRequests {
                controller.removeScriptMessageHandler(forName: Self.requestHandler)
                controller.removeAllUserScripts()
            }
            observingRequests = enabled
            reply(id, ["status": "ok"])
        case "stop":
            webView.stopLoading()
            finish(error: "Navigation stopped")
            reply(id, ["status": "ok"])
        case "document": reply(id, ["url": webView.url?.absoluteString ?? "", "title": webView.title ?? ""])
        case "evaluateJs":
            // Match WebView2 ExecuteScriptAsync's JSON string result, including objects and undefined.
            let script = p["script"] as? String ?? ""
            let literal = String(data: try! JSONSerialization.data(withJSONObject: [script]), encoding: .utf8)!
            webView.evaluateJavaScript("JSON.stringify((0,eval)(\(literal)[0])) ?? 'null'") { value, error in
                self.reply(id, ["value": value as? String ?? "null"], error: error?.localizedDescription)
            }
        case "settings":
            webView.configuration.defaultWebpagePreferences.allowsContentJavaScript = p["javaScript"] as? Bool ?? true
            webView.customUserAgent = p["userAgent"] as? String
            reply(id, ["status": "ok"])
        case "visibility":
            if let width = p["width"] as? Int, let height = p["height"] as? Int {
                window.setContentSize(NSSize(width: min(4096, max(320, width)), height: min(4096, max(240, height))))
            }
            if p["visible"] as? Bool == true {
                NSApp.activate(ignoringOtherApps: true)
                window.makeKeyAndOrderFront(nil)
            } else { window.orderOut(nil) }
            reply(id, state())
        case "window": reply(id, state())
        case "dismissWindow": window.performClose(nil); reply(id, state())
        case "capturePreview":
            webView.takeSnapshot(with: nil) { image, error in
                guard let image = image, let rep = NSBitmapImageRep(bitmapDataPlanes: nil,
                      pixelsWide: Int(self.webView.bounds.width), pixelsHigh: Int(self.webView.bounds.height),
                      bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                      colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
                      let context = NSGraphicsContext(bitmapImageRep: rep) else {
                    self.reply(id, error: error?.localizedDescription ?? "Snapshot unavailable"); return
                }
                NSGraphicsContext.saveGraphicsState()
                NSGraphicsContext.current = context
                image.draw(in: self.webView.bounds)
                NSGraphicsContext.restoreGraphicsState()
                guard let png = rep.representation(using: .png, properties: [:]) else {
                    self.reply(id, error: "PNG encoding failed"); return
                }
                self.reply(id, ["pngBase64": png.base64EncodedString()])
            }
        case "getCookies":
            let text = p["url"] as? String ?? ""
            let url = URL(string: text)
            if !text.isEmpty && url?.host == nil { reply(id, error: "Invalid cookie URL"); return }
            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
                let matching = cookies.filter { cookie in
                    guard let url = url, let host = url.host else { return true }
                    let domain = cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: ".")).lowercased()
                    let path = url.path.isEmpty ? "/" : url.path
                    let pathMatches = path == cookie.path || (path.hasPrefix(cookie.path) &&
                        (cookie.path.hasSuffix("/") || path.dropFirst(cookie.path.count).hasPrefix("/")))
                    return (host.lowercased() == domain || (cookie.domain.hasPrefix(".") && host.lowercased().hasSuffix("." + domain)))
                        && pathMatches && (!cookie.isSecure || url.scheme == "https")
                        && (cookie.expiresDate == nil || cookie.expiresDate! > Date())
                }
                self.reply(id, ["cookies": matching.map { cookie -> [String: Any] in
                    var row: [String: Any] = ["name": cookie.name, "value": cookie.value, "domain": cookie.domain,
                        "path": cookie.path, "isHttpOnly": cookie.isHTTPOnly, "isSecure": cookie.isSecure,
                        "isSession": cookie.isSessionOnly]
                    if let expiry = cookie.expiresDate { row["expiresEpochMillis"] = Int64(expiry.timeIntervalSince1970 * 1000) }
                    return row
                }])
            }
        case "setCookie":
            guard let text = p["url"] as? String, let url = URL(string: text), let host = url.host,
                  let name = p["name"] as? String, let value = p["value"] as? String else {
                reply(id, error: "Invalid cookie"); return
            }
            var properties: [HTTPCookiePropertyKey: Any] = [.name: name, .value: value,
                .domain: p["domain"] as? String ?? host, .path: p["path"] as? String ?? "/", .originURL: url]
            if p["isSecure"] as? Bool == true { properties[.secure] = "TRUE" }
            if p["isHttpOnly"] as? Bool == true { properties[HTTPCookiePropertyKey("HttpOnly")] = "TRUE" }
            if let ms = p["expiresEpochMillis"] as? Double { properties[.expires] = Date(timeIntervalSince1970: ms / 1000) }
            guard let cookie = HTTPCookie(properties: properties) else { reply(id, error: "Invalid cookie properties"); return }
            webView.configuration.websiteDataStore.httpCookieStore.setCookie(cookie) { self.reply(id, ["status": "ok"]) }
        case "deleteCookie":
            let store = webView.configuration.websiteDataStore.httpCookieStore
            store.getAllCookies { cookies in
                let group = DispatchGroup()
                for cookie in cookies where cookie.name == p["name"] as? String && cookie.domain == p["domain"] as? String
                    && cookie.path == p["path"] as? String {
                    group.enter()
                    store.delete(cookie) { group.leave() }
                }
                group.notify(queue: .main) { self.reply(id, ["status": "ok"]) }
            }
        default: reply(id, error: "Unknown method: " + method)
        }
    }

    private func finish(error: String? = nil) {
        guard let active = navigation else { return }
        navigation = nil
        reply(active.id, ["status": "ok"], error: error)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if navigation === self.navigation?.token { finish() }
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        if navigation === self.navigation?.token { finish(error: error.localizedDescription) }
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if navigation === self.navigation?.token { finish(error: error.localizedDescription) }
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { finish(error: "Web content process terminated") }
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if response.isForMainFrame, let http = response.response as? HTTPURLResponse,
           http.statusCode >= 400, navigation?.allowHttpError == false {
            finish(error: "HTTP \(http.statusCode)")
            decisionHandler(.cancel)
        } else { decisionHandler(.allow) }
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let allowed = ["http", "https", "about", "data", "blob"].contains(action.request.url?.scheme?.lowercased() ?? "")
        if observingRequests, allowed, let url = action.request.url, ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
            emitRequest(url: url.absoluteString, method: action.request.httpMethod ?? "GET",
                        headers: action.request.allHTTPHeaderFields ?? [:], mainFrame: action.targetFrame?.isMainFrame ?? true)
        }
        decisionHandler(allowed ? .allow : .cancel)
        if !allowed { finish(error: "Unsupported navigation scheme") }
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let bridge = BrowserBridge()
DispatchQueue.global(qos: .userInitiated).async {
    while let line = readLine() {
        guard let data = line.data(using: .utf8),
              let request = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { continue }
        DispatchQueue.main.async { bridge.handle(request) }
    }
    DispatchQueue.main.async { app.terminate(nil) }
}
app.run()
