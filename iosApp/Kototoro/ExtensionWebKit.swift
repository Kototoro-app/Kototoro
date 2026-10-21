import Foundation
import UIKit
import WebKit

/// The WKWebView side of extensions' Android WebViews (`IosWebKit` in the iOS platform adapter). Kotlin sends
/// operations with a JSON payload through `IosWebKit.nativeCommand`; page events go back to `IosWebKit.onNativeEvent`
/// as JSON. Comix, for one, runs the site in such a WebView to read its decoded API data; Cloudflare handling inside
/// extensions uses it too.
///
/// Threading: operations arrive on Java threads and hop to the main thread synchronously; nothing on the main thread
/// ever waits for Java, so the two cannot deadlock. Events leave the main thread before they call into Java.
final class ExtensionWebKit: NSObject {
    static let shared = ExtensionWebKit()
    private var views: [Int64: BridgedWebView] = [:]
    private var nextView: Int64 = 1
    private var httpRequests: [UUID: WebKitHttpRequest] = [:]
    /// Ordered delivery of notifications (page started before finished, progress, console...).
    fileprivate let events = DispatchQueue(label: "org.skepsun.kototoro.webkit.events")
    /// Calls whose answer something waits for (navigation decisions, intercepted requests, interface results).
    fileprivate let replies = DispatchQueue(label: "org.skepsun.kototoro.webkit.replies", attributes: .concurrent)
    private static var installed = false

    /// Registers the native side; call once after the JVM started and before the source session opens.
    static func install() {
        guard !installed else { return }
        var error: UnsafeMutablePointer<CChar>?
        let status = ktr_webkit_install(webKitHandler, &error)
        defer { ktr_free(error) }
        if status == 0 {
            installed = true
        } else {
            NSLog("Kototoro WebKit bridge unavailable: %@", error.map { String(cString: $0) } ?? "unknown")
        }
    }

    // MARK: Operations (Java threads)

    fileprivate func perform(_ operation: String, _ view: Int64, _ payload: [String: Any]) -> String? {
        Self.log("op \(view) \(operation) \(String(describing: payload["url"] ?? payload["baseUrl"] ?? ""))")
        switch operation {
        case "http.fetch":
            return wait { (done: @escaping (String?) -> Void) in
                let id = UUID()
                guard let request = WebKitHttpRequest(payload: payload, completion: { [weak self] response in
                    self?.httpRequests.removeValue(forKey: id)
                    done(response)
                }) else { done(nil); return }
                self.httpRequests[id] = request
                request.start()
            }
        case "userAgent.default":
            return wait { (done: @escaping (String?) -> Void) in
                let view = WKWebView(frame: .zero)
                view.evaluateJavaScript("navigator.userAgent") { [view] value, _ in
                    _ = view
                    done(value as? String)
                }
            }
        case "create":
            return onMain { () -> String in
                let id = self.nextView
                self.nextView += 1
                self.views[id] = BridgedWebView(id: id)
                return String(id)
            }
        case "destroy":
            onMain { self.views.removeValue(forKey: view)?.close() }
            return nil
        case let name where name.hasPrefix("cookies."):
            return cookies(name, payload)
        default:
            guard let target = onMain({ self.views[view] }) else { return "unavailable:WebView \(view) is closed" }
            return target.perform(operation, payload)
        }
    }

    // MARK: Cookies (WebKit's store, which the in-app browser and the Cloudflare pass share)

    private func cookies(_ operation: String, _ payload: [String: Any]) -> String? {
        let url = (payload["url"] as? String).flatMap(Self.url)
        switch operation {
        case "cookies.set":
            guard let url, let header = payload["header"] as? String else { return nil }
            let cookies = HTTPCookie.cookies(withResponseHeaderFields: ["Set-Cookie": header], for: url)
            _ = wait { (done: @escaping (Bool?) -> Void) in
                let store = WKWebsiteDataStore.default().httpCookieStore
                let group = DispatchGroup()
                for cookie in cookies { group.enter(); store.setCookie(cookie) { group.leave() } }
                group.notify(queue: .main) { done(true) }
            }
            return nil
        case "cookies.list":
            let listed = allCookies().filter { url == nil || Self.matches($0, url!) }.map { cookie -> [String: Any] in
                let host = cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
                return ["header": Self.header(cookie), "url": "https://\(host)\(cookie.path)"]
            }
            return Self.json(listed)
        case "cookies.clear":
            let sessionOnly = payload["sessionOnly"] as? Bool ?? false
            let doomed = allCookies().filter { !sessionOnly || $0.isSessionOnly }
            _ = wait { (done: @escaping (Bool?) -> Void) in
                let store = WKWebsiteDataStore.default().httpCookieStore
                let group = DispatchGroup()
                for cookie in doomed { group.enter(); store.delete(cookie) { group.leave() } }
                group.notify(queue: .main) { done(true) }
            }
            return "true"
        default:
            return "unavailable:\(operation)"
        }
    }

    private func allCookies() -> [HTTPCookie] {
        wait { (done: @escaping ([HTTPCookie]?) -> Void) in
            WKWebsiteDataStore.default().httpCookieStore.getAllCookies { done($0) }
        } ?? []
    }

    static func matches(_ cookie: HTTPCookie, _ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        let domain = cookie.domain.lowercased()
        let hostMatches = domain.hasPrefix(".") ? (host == String(domain.dropFirst()) || host.hasSuffix(domain))
            : host == domain || host.hasSuffix("." + domain)
        let path = url.path.isEmpty ? "/" : url.path
        if let expires = cookie.expiresDate, expires < Date() { return false }
        return hostMatches && path.hasPrefix(cookie.path) && (!cookie.isSecure || url.scheme == "https")
    }

    /// A Set-Cookie header with the cookie's scope, so the Java store keeps domain, path and lifetime.
    static func header(_ cookie: HTTPCookie) -> String {
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

    // MARK: Helpers

    /// Runs [body] on the main thread and waits; the main thread never waits for Java, so this cannot deadlock.
    fileprivate func onMain<T>(_ body: () -> T) -> T {
        Thread.isMainThread ? body() : DispatchQueue.main.sync(execute: body)
    }

    /// Starts [start] on the main thread and waits (on this Java thread) until it calls back, at most 30 seconds.
    fileprivate func wait<T>(_ start: @escaping @MainActor (@escaping (T?) -> Void) -> Void) -> T? {
        if Thread.isMainThread { return nil }
        let semaphore = DispatchSemaphore(value: 0)
        var value: T?
        DispatchQueue.main.async {
            start { result in value = result; semaphore.signal() }
        }
        _ = semaphore.wait(timeout: .now() + 30)
        return value
    }

    /// Delivers an event to Kotlin and returns its answer ("" when none).
    fileprivate static func send(_ view: Int64, _ event: String, _ payload: Any = [String: Any]()) -> String {
        let name = Array(event.utf16), body = Array(json(payload).utf16)
        var result: UnsafeMutablePointer<UInt16>?
        var length = 0
        var error: UnsafeMutablePointer<CChar>?
        let status = name.withUnsafeBufferPointer { n in
            body.withUnsafeBufferPointer { b in
                ktr_webkit_dispatch(view, n.baseAddress, n.count, b.baseAddress, b.count, &result, &length, &error)
            }
        }
        defer { ktr_free(result); ktr_free(error) }
        guard status == 0, let result else { return "" }
        return String(decoding: UnsafeBufferPointer(start: result, count: length), as: UTF16.self)
    }

    static func json(_ value: Any) -> String {
        (try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]))
            .map { String(decoding: $0, as: UTF8.self) } ?? "null"
    }

    static func object(_ text: String) -> [String: Any]? {
        text.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
    }

    static func url(_ address: String) -> URL? { URL(string: address.contains("://") ? address : "https://\(address)") }

    /// Development log of extension WebViews (operations, console, page errors, requests), with
    /// KOTOTORO_SOURCE_DIAGNOSTICS; written to `webkit.log` in the source data directory.
    private static let diagnostics: FileHandle? = {
        guard ProcessInfo.processInfo.environment["KOTOTORO_SOURCE_DIAGNOSTICS"] != nil,
              let directory = try? NativeSourceTransport.dataDirectory else { return nil }
        let file = directory.appendingPathComponent("webkit.log")
        FileManager.default.createFile(atPath: file.path, contents: nil)
        return try? FileHandle(forWritingTo: file)
    }()
    private static let diagnosticsQueue = DispatchQueue(label: "org.skepsun.kototoro.webkit.log")

    static func log(_ text: @autoclosure () -> String) {
        guard let diagnostics else { return }
        let line = "\(Date()) \(text())\n"
        diagnosticsQueue.async { diagnostics.seekToEndOfFile(); diagnostics.write(Data(line.utf8)) }
    }
}

/// C entry point for `IosWebKit.nativeCommand`.
private let webKitHandler: KTRWebKitHandler = { operation, operationLength, view, payload, payloadLength, resultLength in
    let name = operation.map { String(decoding: UnsafeBufferPointer(start: $0, count: operationLength), as: UTF16.self) } ?? ""
    let body = payload.map { String(decoding: UnsafeBufferPointer(start: $0, count: payloadLength), as: UTF16.self) } ?? ""
    guard let answer = ExtensionWebKit.shared.perform(name, view, ExtensionWebKit.object(body) ?? [:]) else {
        resultLength?.pointee = 0
        return nil
    }
    let units = Array(answer.utf16)
    guard let buffer = ktr_malloc(max(units.count, 1) * MemoryLayout<UInt16>.size)?
        .bindMemory(to: UInt16.self, capacity: max(units.count, 1)) else {
        resultLength?.pointee = 0
        return nil
    }
    units.withUnsafeBufferPointer { if let base = $0.baseAddress { buffer.update(from: base, count: units.count) } }
    resultLength?.pointee = units.count
    return buffer
}

/// One extension WebView: an invisible WKWebView kept in the window so its scripts and timers run at full speed.
private final class BridgedWebView: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler,
    WKScriptMessageHandlerWithReply {
    let id: Int64
    let webView: WKWebView
    /// JavaScript interfaces: name -> methods, each with whether it returns a value.
    private var interfaces: [String: [(name: String, returns: Bool)]] = [:]
    /// Navigations the extension started itself; Android does not ask shouldOverrideUrlLoading for those.
    private var programmaticLoads = 0
    /// The next main-frame navigation shows a response shouldInterceptRequest supplied; do not ask again.
    private var showingIntercepted = false
    private var observations: [NSKeyValueObservation] = []
    private var bridge: ExtensionWebKit { .shared }

    init(id: Int64) {
        self.id = id
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: configuration)
        super.init()
        let content = configuration.userContentController
        content.add(WeakHandler(self), name: "ktrCall")
        content.add(WeakHandler(self), name: "ktrConsole")
        content.addScriptMessageHandler(WeakReplyHandler(self), contentWorld: .page, name: "ktrRequest")
        installScripts()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.alpha = 0.01
        webView.isUserInteractionEnabled = false
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?
            .insertSubview(webView, at: 0)
        observations = [
            webView.observe(\.title) { [id] view, _ in
                let title = view.title ?? ""
                ExtensionWebKit.shared.events.async { _ = ExtensionWebKit.send(id, "title", ["value": title]) }
            },
            webView.observe(\.estimatedProgress) { [id] view, _ in
                let progress = Int(view.estimatedProgress * 100)
                ExtensionWebKit.shared.events.async { _ = ExtensionWebKit.send(id, "progress", ["value": progress]) }
            },
        ]
    }

    func close() {
        observations = []
        webView.stopLoading()
        webView.configuration.userContentController.removeAllScriptMessageHandlers()
        webView.removeFromSuperview()
    }

    // MARK: Operations

    func perform(_ operation: String, _ payload: [String: Any]) -> String? {
        switch operation {
        case "load":
            guard let url = (payload["url"] as? String).flatMap(URL.init(string:)) else { return nil }
            var request = URLRequest(url: url)
            for (key, value) in payload["headers"] as? [String: String] ?? [:] { request.setValue(value, forHTTPHeaderField: key) }
            bridge.onMain { programmaticLoads += 1; webView.load(request) }
        case "post":
            guard let url = (payload["url"] as? String).flatMap(URL.init(string:)) else { return nil }
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.httpBody = Data(base64Encoded: payload["body"] as? String ?? "")
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            bridge.onMain { programmaticLoads += 1; webView.load(request) }
        case "html":
            let html = payload["html"] as? String ?? ""
            let base = (payload["baseUrl"] as? String).flatMap(URL.init(string:))
            bridge.onMain { programmaticLoads += 1; webView.loadHTMLString(html, baseURL: base) }
        case "evaluate":
            return evaluate(payload["script"] as? String ?? "")
        case "interfaces":
            let described = payload["interfaces"] as? [String: [[String: Any]]] ?? [:]
            bridge.onMain {
                interfaces = described.mapValues { methods in
                    methods.compactMap { method in
                        (method["name"] as? String).map { ($0, method["returns"] as? Bool ?? false) }
                    }
                }
                installScripts()
                for (name, methods) in interfaces { webView.evaluateJavaScript(Self.interfaceScript(name, methods)) }
            }
        case "configure":
            let agent = payload["userAgent"] as? String ?? ""
            let javaScript = payload["javaScript"] as? Bool ?? true
            bridge.onMain {
                webView.customUserAgent = agent.isEmpty ? nil : agent
                webView.configuration.defaultWebpagePreferences.allowsContentJavaScript = javaScript
            }
        case "stop": bridge.onMain { webView.stopLoading() }
        case "reload": bridge.onMain { _ = webView.reload() }
        case "back": bridge.onMain { _ = webView.goBack() }
        case "forward": bridge.onMain { _ = webView.goForward() }
        case "go":
            let steps = payload["steps"] as? Int ?? 0
            bridge.onMain { if let item = webView.backForwardList.item(at: steps) { webView.go(to: item) } }
        case "canGo":
            let steps = payload["steps"] as? Int ?? 0
            return bridge.onMain { webView.backForwardList.item(at: steps) != nil ? "true" : "false" }
        case "state":
            return bridge.onMain { () -> String in
                var state: [String: Any] = [
                    "progress": Int(webView.estimatedProgress * 100),
                    "canGoBack": webView.canGoBack,
                    "canGoForward": webView.canGoForward,
                    "contentWidth": Int(webView.scrollView.contentSize.width),
                    "contentHeight": Int(webView.scrollView.contentSize.height),
                ]
                state["url"] = webView.url?.absoluteString
                state["originalUrl"] = webView.backForwardList.currentItem?.initialURL.absoluteString
                state["title"] = webView.title
                return ExtensionWebKit.json(state)
            }
        case "clearCache":
            _ = bridge.wait { (done: @escaping (Bool?) -> Void) in
                WKWebsiteDataStore.default().removeData(ofTypes: [WKWebsiteDataTypeMemoryCache, WKWebsiteDataTypeDiskCache],
                                                        modifiedSince: .distantPast) { done(true) }
            }
        default:
            return "unavailable:\(operation)"
        }
        return nil
    }

    /// Android's evaluateJavascript: global-scope evaluation, the result as JSON ("null" for undefined or errors).
    private func evaluate(_ script: String) -> String {
        let wrapped = "(function(){var r=(0,eval)(\(ExtensionWebKit.json(script)));if(r===undefined)return \"null\";" +
            "var j=JSON.stringify(r);return j===undefined?\"null\":j;})()"
        return bridge.wait { (done: @escaping (String?) -> Void) in
            self.webView.evaluateJavaScript(wrapped) { value, error in
                if let error { ExtensionWebKit.log("evaluate \(self.id) failed: \(error.localizedDescription)") }
                done(value as? String)
            }
        } ?? "null"
    }

    // MARK: Scripts

    private func installScripts() {
        let content = webView.configuration.userContentController
        content.removeAllUserScripts()
        for source in [Self.consoleScript, Self.requestScript] + interfaces.map({ Self.interfaceScript($0.key, $0.value) }) {
            content.addUserScript(WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        }
    }

    /// `window[name]` with the interface's methods. Methods without a result post a message (Android runs them off
    /// the page's thread); the others block in prompt() until Kotlin answers, the one synchronous path WebKit has.
    static func interfaceScript(_ name: String, _ methods: [(name: String, returns: Bool)]) -> String {
        let members = methods.map { method -> String in
            let key = ExtensionWebKit.json(method.name)
            let body = method.returns
                ? "var r=prompt(\"__ktr_call__\"+JSON.stringify({interface:n,method:\(key),args:a}));" +
                  "try{return JSON.parse(r);}catch(x){return r;}"
                : "window.webkit.messageHandlers.ktrCall.postMessage({interface:n,method:\(key),args:a});"
            return "\(key):function(){var a=Array.prototype.slice.call(arguments);\(body)}"
        }.joined(separator: ",")
        return "(function(){var n=\(ExtensionWebKit.json(name));window[n]={\(members)};})();"
    }

    static let consoleScript = """
    (function(){var send=function(l,m){try{window.webkit.messageHandlers.ktrConsole.postMessage({level:l,message:m});}\
    catch(x){}};var wrap=function(l,o){return function(){send(l,Array.prototype.map.call(arguments,String).join(" "));\
    return o&&o.apply(console,arguments);};};console.log=wrap("LOG",console.log);console.info=wrap("LOG",console.info);\
    console.warn=wrap("WARNING",console.warn);console.error=wrap("ERROR",console.error);\
    console.debug=wrap("DEBUG",console.debug);window.addEventListener("error",function(e){\
    send("ERROR","Uncaught "+(e.message||e)+" @"+(e.filename||"")+":"+(e.lineno||0));});\
    window.addEventListener("unhandledrejection",function(e){send("ERROR","Unhandled rejection "+String(e.reason));});})();
    """

    /**
     * shouldInterceptRequest for the page's own requests, which WebKit does not expose for http(s). fetch() starts at
     * once, as in a browser (routers drop a navigation whose request did not start before the next one), and asks
     * Kotlin alongside; a response Kotlin supplies replaces the network's. XMLHttpRequest asks first and then sends.
     * Extensions count or block requests this way.
     */
    static let requestScript = """
    (function(){if(window.__ktrRequest)return;window.__ktrRequest=true;\
    var ask=function(r){try{return window.webkit.messageHandlers.ktrRequest.postMessage(r);}catch(e){return Promise.resolve(null);}};\
    var bytes=function(b){var s=atob(b),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i);return a;};\
    if(window.fetch){var f=window.fetch.bind(window);window.fetch=function(input,init){var q;\
    try{q=new Request(input,init);}catch(e){return f(input,init);}var h={};q.headers.forEach(function(v,k){h[k]=v;});\
    var net=f(input,init);net.catch(function(){});\
    return ask({url:q.url,method:q.method,headers:h}).then(function(r){if(r&&r.body!==undefined)\
    return new Response(bytes(r.body),{status:r.status||200,statusText:r.reason||"OK",headers:r.headers||{}});\
    return net;},function(){return net;});};}\
    var X=XMLHttpRequest.prototype,open=X.open,send=X.send,header=X.setRequestHeader;\
    X.open=function(m,u){try{this.__ktr={method:String(m||"GET").toUpperCase(),url:new URL(u,location.href).href,headers:{}};}\
    catch(e){this.__ktr=null;}return open.apply(this,arguments);};\
    X.setRequestHeader=function(k,v){if(this.__ktr)this.__ktr.headers[k]=v;return header.apply(this,arguments);};\
    X.send=function(){var x=this,k=this.__ktr,args=arguments;if(!k)return send.apply(x,args);\
    ask(k).then(function(r){if(!r||r.body===undefined)return send.apply(x,args);\
    var data=bytes(r.body),text=new TextDecoder(r.encoding||"utf-8").decode(data),type=x.responseType,body=text;\
    if(type==="json"){try{body=JSON.parse(text);}catch(e){body=null;}}else if(type==="arraybuffer")body=data.buffer;\
    else if(type==="blob")body=new Blob([data],{type:r.mimeType||""});var hs=r.headers||{};\
    var d=function(n,v){Object.defineProperty(x,n,{configurable:true,get:function(){return v;}});};\
    d("readyState",4);d("status",r.status||200);d("statusText",r.reason||"OK");d("responseURL",k.url);\
    d("response",body);if(!type||type==="text")d("responseText",text);\
    x.getResponseHeader=function(n){n=String(n).toLowerCase();for(var key in hs)if(key.toLowerCase()===n)return hs[key];return null;};\
    x.getAllResponseHeaders=function(){return Object.keys(hs).map(function(key){return key+": "+hs[key];}).join("\\r\\n");};\
    ["readystatechange","load","loadend"].forEach(function(e){var c=x["on"+e];if(typeof c==="function")c.call(x,new Event(e));\
    x.dispatchEvent(new Event(e));});},function(){send.apply(x,args);});};})();
    """

    /// The request as WebViewClient sees it.
    private static func request(_ url: URL, method: String = "GET", mainFrame: Bool, gesture: Bool = false,
                                headers: [String: String] = [:]) -> [String: Any] {
        ["url": url.absoluteString, "method": method, "mainFrame": mainFrame, "redirect": false, "gesture": gesture,
         "headers": headers]
    }

    private struct Supplied {
        let status: Int, reason: String, mimeType: String, encoding: String, headers: [String: String], body: Data
    }

    /// A response shouldInterceptRequest returned, or nil when the request should go to the network.
    private static func supplied(_ answer: String) -> Supplied? {
        guard let value = ExtensionWebKit.object(answer) else { return nil }
        return Supplied(status: value["status"] as? Int ?? 200, reason: value["reason"] as? String ?? "OK",
                        mimeType: value["mimeType"] as? String ?? "", encoding: value["encoding"] as? String ?? "",
                        headers: value["headers"] as? [String: String] ?? [:],
                        body: Data(base64Encoded: value["body"] as? String ?? "") ?? Data())
    }

    // MARK: Page messages

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any] else { return }
        let id = id
        if message.name == "ktrCall" {
            ExtensionWebKit.log("call \(id) \(body["interface"] ?? "").\(body["method"] ?? "")")
            var payload = body
            payload["returns"] = false
            bridge.events.async { _ = ExtensionWebKit.send(id, "call", payload) }
        } else if message.name == "ktrConsole" {
            let level = body["level"] as? String ?? "LOG", text = body["message"] as? String ?? ""
            ExtensionWebKit.log("console \(id) \(level) \(text.prefix(500))")
            let source = webView.url?.absoluteString ?? ""
            bridge.events.async {
                _ = ExtensionWebKit.send(id, "console", ["level": level, "message": text, "source": source, "line": 0])
            }
        }
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        guard let body = message.body as? [String: Any], let address = body["url"] as? String,
              let url = URL(string: address), ["http", "https"].contains(url.scheme ?? "") else {
            replyHandler(nil, nil)
            return
        }
        let headers = (body["headers"] as? [String: Any] ?? [:]).mapValues { String(describing: $0) }
        let request = Self.request(url, method: body["method"] as? String ?? "GET", mainFrame: false, headers: headers)
        let id = id
        bridge.replies.async {
            let supplied = Self.supplied(ExtensionWebKit.send(id, "request", ["request": request]))
            ExtensionWebKit.log("request \(id) \(address) -> \(supplied.map { "\($0.status) \($0.body.count)B" } ?? "network")")
            DispatchQueue.main.async {
                guard let supplied else { replyHandler(nil, nil); return }
                replyHandler(["status": supplied.status, "reason": supplied.reason, "mimeType": supplied.mimeType,
                              "encoding": supplied.encoding, "headers": supplied.headers,
                              "body": supplied.body.base64EncodedString()], nil)
            }
        }
    }

    // MARK: WKUIDelegate

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        guard prompt.hasPrefix("__ktr_call__"), var payload = ExtensionWebKit.object(String(prompt.dropFirst(12))) else {
            completionHandler(nil)
            return
        }
        payload["returns"] = true
        let id = id
        bridge.replies.async {
            let answer = ExtensionWebKit.send(id, "call", payload)
            DispatchQueue.main.async { completionHandler(answer) }
        }
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        completionHandler()
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        completionHandler(false)
    }

    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let mainFrame = action.targetFrame?.isMainFrame ?? true
        if mainFrame && showingIntercepted {
            showingIntercepted = false
            decisionHandler(.allow)
            return
        }
        // Android asks shouldOverrideUrlLoading only for navigations the page starts, not for loadUrl and friends.
        let programmatic = mainFrame && programmaticLoads > 0
        if programmatic { programmaticLoads -= 1 }
        guard let url = action.request.url, ["http", "https"].contains(url.scheme ?? "") else {
            decisionHandler(.allow)
            return
        }
        let request = Self.request(url, method: action.request.httpMethod ?? "GET", mainFrame: mainFrame,
                                   gesture: action.navigationType == .linkActivated,
                                   headers: action.request.allHTTPHeaderFields ?? [:])
        let id = id
        bridge.replies.async {
            if !programmatic && ExtensionWebKit.send(id, "navigate", ["request": request]) == "true" {
                DispatchQueue.main.async { decisionHandler(.cancel) }
                return
            }
            // shouldInterceptRequest also sees documents; a supplied response is shown in place of the request.
            let supplied = mainFrame ? Self.supplied(ExtensionWebKit.send(id, "request", ["request": request])) : nil
            DispatchQueue.main.async {
                guard let supplied else { decisionHandler(.allow); return }
                decisionHandler(.cancel)
                self.showingIntercepted = true
                self.webView.load(supplied.body, mimeType: supplied.mimeType.isEmpty ? "text/html" : supplied.mimeType,
                                  characterEncodingName: supplied.encoding.isEmpty ? "utf-8" : supplied.encoding,
                                  baseURL: url)
            }
        }
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        let id = id, url = webView.url?.absoluteString ?? "about:blank"
        bridge.events.async { _ = ExtensionWebKit.send(id, "started", ["url": url]) }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let id = id, url = webView.url?.absoluteString ?? "about:blank"
        bridge.events.async { _ = ExtensionWebKit.send(id, "finished", ["url": url]) }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        report(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        report(error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        let id = id
        bridge.events.async { _ = ExtensionWebKit.send(id, "crashed") }
    }

    private func report(_ error: Error) {
        let nsError = error as NSError
        ExtensionWebKit.log("navigation error \(id) \(nsError.domain) \(nsError.code) \(nsError.localizedDescription)")
        // A navigation the page or the extension replaced is not a failure on Android either.
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled { return }
        let failing = (nsError.userInfo[NSURLErrorFailingURLErrorKey] as? URL) ?? webView.url
        let payload: [String: Any] = ["url": failing?.absoluteString ?? "about:blank", "mainFrame": true,
                                      "description": nsError.localizedDescription]
        let id = id
        bridge.events.async { _ = ExtensionWebKit.send(id, "failed", payload) }
    }
}

/// WKUserContentController retains its handlers; these break the cycle with the bridged view.
private final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(controller, didReceive: message)
    }
}

private final class WeakReplyHandler: NSObject, WKScriptMessageHandlerWithReply {
    weak var target: WKScriptMessageHandlerWithReply?
    init(_ target: WKScriptMessageHandlerWithReply) { self.target = target }
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        guard let target else { replyHandler(nil, nil); return }
        target.userContentController(controller, didReceive: message, replyHandler: replyHandler)
    }
}
