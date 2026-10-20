import Foundation
import KototoroShared

/// Owns the JNI session. The JVM and its static libraries live until process exit. Exchanges run concurrently on
/// their callers' threads (the bridge attaches each thread); the condition only guards opening and closing.
final class NativeSourceTransport: IosSourceTransport {
    private let condition = NSCondition()
    private var session: OpaquePointer?
    private var closed = false
    private var opening = false
    private var active = 0

    static var isAvailable: Bool { ktr_vm_available() != 0 }
    static let isFixtureRuntime: Bool = {
        guard let file = Bundle.main.resourceURL?.appendingPathComponent("jvm/sources/config.json"),
              let bytes = try? Data(contentsOf: file),
              let config = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              (config["platformClass"] as? String)?.hasPrefix("fixture.") == true else { return false }
        return true
    }()
    private static let storageName = isFixtureRuntime ? "sources-fixture" : "sources"

    static let fixtureInstallationMessage = String(localized: "此构建仅包含独立测试源，无法安装真实扩展。请使用包含真实扩展运行时的构建。")

    static var dataDirectory: URL {
        get throws {
            try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                        appropriateFor: nil, create: true)
                .appendingPathComponent("Kototoro/\(storageName)", isDirectory: true)
        }
    }

    static var downloadQueueFile: URL {
        URL.applicationSupportDirectory.appendingPathComponent("Kototoro/\(storageName)/downloads/queue.json")
    }

    func exchange(requestJson: String) throws -> String {
        let session = try acquire()
        defer { release() }
        let input = Array(requestJson.utf16)
        var output: UnsafeMutablePointer<UInt16>?
        var length = 0
        var error: UnsafeMutablePointer<CChar>?
        let status = input.withUnsafeBufferPointer {
            ktr_session_exchange(session, $0.baseAddress, $0.count, &output, &length, &error)
        }
        defer { ktr_free(error); ktr_free(output) }
        guard status == 0, let output else { throw nativeFailure(error) }
        return String(decoding: UnsafeBufferPointer(start: output, count: length), as: UTF16.self)
    }

    /// Opens the session once; concurrent first callers wait for it instead of starting a second VM session.
    private func acquire() throws -> OpaquePointer? {
        condition.lock()
        defer { condition.unlock() }
        while opening { condition.wait() }
        guard !closed else { throw failure(String(localized: "源会话已关闭")) }
        if session == nil {
            opening = true
            condition.unlock()
            var openError: Error?
            do { try open() } catch { openError = error }
            condition.lock()
            opening = false
            condition.broadcast()
            if let openError { throw openError }
        }
        active += 1
        return session
    }

    private func release() {
        condition.lock()
        active -= 1
        if active == 0 { condition.broadcast() }
        condition.unlock()
    }

    func close() {
        condition.lock()
        defer { condition.unlock() }
        guard !closed else { return }
        closed = true
        // Never free the native session while another thread is still inside an exchange.
        while active > 0 || opening { condition.wait() }
        if let session {
            var error: UnsafeMutablePointer<CChar>?
            _ = ktr_session_close(session, &error)
            ktr_free(error)
            self.session = nil
        }
    }

    deinit { close() }

    private func open() throws {
        guard Self.isAvailable, let resources = Bundle.main.resourceURL else {
            throw failure(String(localized: "此构建未包含扩展运行时"))
        }
        let runtime = resources.appendingPathComponent("jvm", isDirectory: true)
        let home = runtime.appendingPathComponent("java_bundle", isDirectory: true)
        let lib = runtime.appendingPathComponent("lib", isDirectory: true)
        let jars = try FileManager.default.contentsOfDirectory(at: lib, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "jar" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        let data = try Self.dataDirectory
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        var options = ["-Djava.io.tmpdir=\(FileManager.default.temporaryDirectory.path)",
                       "-Duser.home=\(data.path)",
                       "-Xmx\(Self.heapMegabytes)m"]
        // Development: host-side stack traces of failed source calls, which the protocol does not carry.
        if ProcessInfo.processInfo.environment["KOTOTORO_SOURCE_DIAGNOSTICS"] != nil {
            options.append("-Dkototoro.source.diagnostics=file:\(data.appendingPathComponent("source-failures.log").path)")
        }
        let optionStrings = options.map { strdup($0) }
        defer { optionStrings.forEach { free($0) } }
        let pointers = optionStrings.map { $0.map { UnsafePointer<CChar>($0) } }
        var error: UnsafeMutablePointer<CChar>?
        let status = home.path.withCString { homePath in
            jars.map(\.path).joined(separator: ":").withCString { classpath in
                pointers.withUnsafeBufferPointer {
                    ktr_vm_start(homePath, classpath, $0.baseAddress, $0.count, &error)
                }
            }
        }
        defer { ktr_free(error) }
        guard status == 0 else { throw nativeFailure(error) }
        // Extensions' Android WebViews run on WKWebView; the platform adapter installs the Java side on open.
        ExtensionWebKit.install()

        let configDirectory = runtime.appendingPathComponent("sources", isDirectory: true)
        let configURL = configDirectory.appendingPathComponent("config.json")
        guard var config = try JSONSerialization.jsonObject(with: Data(contentsOf: configURL)) as? [String: Any],
              var extensions = config["jars"] as? [[String: Any]] else {
            throw failure(String(localized: "扩展运行时配置无效"))
        }
        for index in extensions.indices {
            guard let path = extensions[index]["path"] as? String else { throw failure(String(localized: "扩展路径缺失")) }
            extensions[index]["path"] = configDirectory.appendingPathComponent(path).path
        }
        config["jars"] = extensions
        // Parser plugin JARs ship next to the extensions; the session resolves relative paths against the data
        // directory, so make them absolute like the extension JARs.
        if var plugins = config["plugins"] as? [[String: Any]] {
            for index in plugins.indices {
                guard let path = plugins[index]["path"] as? String else { throw failure(String(localized: "插件路径缺失")) }
                plugins[index]["path"] = configDirectory.appendingPathComponent(path).path
            }
            config["plugins"] = plugins
        }
        // Swift and the host share one image directory, irrespective of a preview's relative config path.
        config["imageDirectory"] = "images"
        // Start-up registers verified extensions from their cached source lists; each is constructed on first use,
        // so switched-off sources never run their extension.
        config["catalogDirectory"] = "catalogs/extensions"
        let json = String(decoding: try JSONSerialization.data(withJSONObject: config), as: UTF8.self)
        let configuration = Array(json.utf16)
        let directory = Array(data.path.utf16)
        var openError: UnsafeMutablePointer<CChar>?
        session = configuration.withUnsafeBufferPointer { configText in
            directory.withUnsafeBufferPointer {
                ktr_session_open(configText.baseAddress, configText.count, $0.baseAddress, $0.count, &openError)
            }
        }
        defer { ktr_free(openError) }
        guard session != nil else { throw nativeFailure(openError) }
    }

    /// Parser plugins register every site eagerly (kototoro-parsers alone has ~1,000), so two of them overflow the
    /// native default of 128 MB. An eighth of physical memory, 256–768 MB, stays well inside the app's memory limit.
    /// (A later -Xmx overrides the bridge's default.)
    static var heapMegabytes: Int {
        let physical = Int(ProcessInfo.processInfo.physicalMemory / 1_048_576)
        return min(max(physical / 8, 256), 768)
    }

    private func nativeFailure(_ error: UnsafeMutablePointer<CChar>?) -> NSError {
        failure(error.map { String(cString: $0) } ?? String(localized: "扩展运行时调用失败"))
    }

    private func failure(_ message: String) -> NSError {
        NSError(domain: "org.skepsun.kototoro.source", code: 1,
                userInfo: [NSLocalizedDescriptionKey: message])
    }
}
