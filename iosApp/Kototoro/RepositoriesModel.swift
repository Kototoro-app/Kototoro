import UIKit
import Combine
import CryptoKit
import Foundation
import KototoroShared

private struct ManagementResult: Decodable { let managementVersion: Int; let installed: [InstalledExtension] }

struct BatchInstallResult: Identifiable {
    let id: String
    let title: String
    let error: String?
}

struct BatchUpdateProgress: Equatable {
    let completed: Int
    let total: Int
    let currentTitle: String
    let failedCount: Int
}

@MainActor
final class RepositoriesModel: ObservableObject {
    @Published private(set) var saved: [IosRepository] = []
    @Published private(set) var catalogs: [String: IosRepositoryCatalog] = [:] { didSet { rebuildRows() } }
    @Published private(set) var repositoryErrors: [String: String] = [:]
    @Published private(set) var isRefreshing = false
    @Published private(set) var activeID: String? { didSet { rebuildRows() } }
    @Published private(set) var error: String?
    @Published private(set) var status: String?
    @Published private(set) var busy = false
    @Published private(set) var installed: [InstalledExtension] = [] { didSet { rebuildRows() } }
    @Published private(set) var rows: [ExtensionSummary] = []
    @Published private(set) var updatesCount: Int = 0
    @Published private(set) var batchProgress: BatchUpdateProgress?
    @Published private(set) var batchResults: [BatchInstallResult] = []
    private(set) var repositoryRows: [String: [ExtensionSummary]] = [:]
    private var store: IosRepositories?
    private weak var sources: SourcesModel?
    private var displayMetadata = RepositoriesModel.readDisplayMetadata()
    private var hasInstalledSnapshot = false
    private var batchCancelled = false
    private static let lastCheckKey = "extensions.lastCheckTimestamp"
    private static let checkInterval: TimeInterval = 12 * 3600

    static func key(_ repository: IosRepository) -> String { repository.kind + ":" + repository.url }
    static func key(_ catalog: IosRepositoryCatalog) -> String { catalog.kind + ":" + catalog.url }
    private func rebuildRows() {
        var entries: [CatalogExtension] = []
        var byRepository: [String: [ExtensionSummary]] = [:]
        for (key, catalog) in catalogs {
            let available = catalog.extensions.map { item in
                CatalogExtension(packageName: item.id, ecosystem: catalog.kind, title: item.title,
                                 version: item.version, versionCode: item.versionCode, languages: item.languages,
                                 sourceNames: item.sourceNames, isAdult: item.isAdult,
                                 repositoryID: key, repositoryTitle: catalog.title,
                                 iconURL: Self.resourceURL(item.iconUrl, catalog: catalog),
                                 sourceKeys: item.sourceKeys(kind: catalog.kind))
            }
            entries += available
            byRepository[key] = ExtensionIndex.summaries(installed: installed, available: available,
                                                        activeID: activeID, metadata: displayMetadata)
                .filter { $0.available != nil }
        }
        repositoryRows = byRepository
        rows = ExtensionIndex.summaries(installed: installed, available: entries,
                                        activeID: activeID, metadata: displayMetadata)
        updatesCount = rows.filter { $0.state == .update }.count
        let next = Dictionary(uniqueKeysWithValues: rows.filter { $0.installed != nil }.map { row in
            (row.id, ExtensionDisplayMetadata(title: row.title, languages: row.languages,
                                               sourceNames: row.sourceNames, iconURL: row.iconURL))
        })
        if hasInstalledSnapshot && next != displayMetadata {
            displayMetadata = next
            if let file = try? Self.displayMetadataFile(), let data = try? JSONEncoder().encode(next) {
                try? data.write(to: file, options: .atomic)
            }
        }
    }

    private static func displayMetadataFile() throws -> URL {
        let directory = try NativeSourceTransport.dataDirectory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("extension-display.json")
    }
    private static func readDisplayMetadata() -> [String: ExtensionDisplayMetadata] {
        guard let file = try? displayMetadataFile(), let data = try? Data(contentsOf: file) else { return [:] }
        return (try? JSONDecoder().decode([String: ExtensionDisplayMetadata].self, from: data)) ?? [:]
    }

    private static func resourceURL(_ path: String, catalog: IosRepositoryCatalog) -> URL? {
        guard !path.isEmpty, let url = URL(string: catalog.resolve(path: path)),
              ["http", "https"].contains(url.scheme ?? "") else { return nil }
        return url
    }

    func attach(library: IosLibrary?, sources: SourcesModel) { if let library { store = IosRepositories(library: library) }; self.sources = sources }
    func reportError(_ message: String) { error = message }
    /// A request cancelled because its view went away (navigation, a repeated link) is not a failure to show.
    private func report(_ error: Error) {
        if !Self.isCancellation(error) { self.error = error.localizedDescription }
    }
    private static func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }
    func clearMessage() { error = nil; status = nil }
    func load() async {
        guard !isRefreshing, !busy else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        error = nil
        do {
            saved = try await store?.saved() ?? []
            if NativeSourceTransport.isAvailable { try await manage(["managementVersion": 1, "operation": "list"]) }
        } catch { report(error) }
        // Publish installed entries first; one unavailable repository must not hide the other catalogs.
        for repository in saved { await refresh(repository) }
    }
    /// [title] names an Apple CMS site; TVBox configurations carry their own site names.
    func importLink(_ text: String, kind: String, title: String = "") async {
        guard !busy else { return }
        busy = true; error = nil; status = nil
        defer { busy = false }
        do {
            let link = try RepositoryLink.parse(text, defaultKind: kind)
            if link.kind == "TVBOX" {
                status = try await sources?.importSource(url: link.url.absoluteString,
                                                         title: title.trimmingCharacters(in: .whitespacesAndNewlines))
                return
            }
            let fetched = try await fetch(link)
            guard let store else { throw CancellationError() }
            try await store.save(catalog: fetched, fingerprint: fingerprint(fetched))
            catalogs[Self.key(fetched)] = fetched
            repositoryErrors[Self.key(fetched)] = nil
            saved = try await store.saved()
            status = "已添加 \(fetched.title)，共 \(fetched.extensions.count) 个扩展"
        } catch { report(error) }
    }
    func open(_ repository: IosRepository) async {
        guard !busy, !isRefreshing else { return }
        clearMessage()
        isRefreshing = true
        defer { isRefreshing = false }
        await refresh(repository)
    }
    private func refresh(_ repository: IosRepository) async {
        let key = Self.key(repository)
        do {
            guard let store else { throw CancellationError() }
            let fetched = try await fetch(RepositoryLink.parse(repository.url, defaultKind: repository.kind))
            try await store.save(catalog: fetched, fingerprint: fingerprint(fetched))
            catalogs[key] = fetched
            repositoryErrors[key] = nil
            saved = try await store.saved()
        } catch let error where !Self.isCancellation(error) { repositoryErrors[key] = error.localizedDescription }
        catch {}
    }
    func remove(_ repository: IosRepository) async {
        guard !busy, !isRefreshing else { return }
        busy = true; error = nil; status = "正在移除仓库…"
        defer { busy = false }
        do {
            try await store?.remove(repository: repository)
            saved = try await store?.saved() ?? []
            catalogs[Self.key(repository)] = nil
            repositoryErrors[Self.key(repository)] = nil
            status = "已移除 \(repository.title)"
        }
        catch { report(error) }
    }
    func install(_ row: ExtensionSummary) async {
        guard row.permitsInstallation,
              let candidate = row.available, let catalog = catalogs[candidate.repositoryID],
              let item = catalog.extensions.first(where: { $0.id == row.packageName }) else { return }
        await install(item, catalog: catalog)
    }
    private func performInstall(_ extensionItem: IosExtension, catalog: IosRepositoryCatalog) async throws {
        guard let url = Self.resourceURL(extensionItem.url, catalog: catalog) else { throw URLError(.badURL) }
        status = "正在下载 \(extensionItem.title)…"
        // URLSession downloads to disk; do not buffer large APKs on the UI thread.
        let (temp, response) = try await URLSession.shared.download(from: url)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode
            throw InstallDownloadError(url: url, statusCode: code)
        }
        let size = try temp.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0 && size <= 128 * 1024 * 1024 else { throw URLError(.dataLengthExceedsMaximum) }
        let incoming = try NativeSourceTransport.dataDirectory.appendingPathComponent("incoming", isDirectory: true)
        try FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true)
        let file = incoming.appendingPathComponent(UUID().uuidString + ".archive")
        try FileManager.default.moveItem(at: temp, to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let hash = try await Task.detached { () throws -> String in
            SHA256.hash(data: try Data(contentsOf: file, options: .mappedIfSafe)).map { String(format: "%02x", $0) }.joined()
        }.value
        // Android builds (APK or DEX-only plugin JAR) are converted to JVM bytecode on the device by the
        // interpreter-only embedded JVM: large parser plugins take minutes, so say so and keep the app awake.
        let megabytes = max(1, size / 1_048_576)
        status = size > 1_048_576
            ? "正在转换并安装 \(extensionItem.title)（\(megabytes) MB，请保持应用在前台）…"
            : "正在转换、校验并安装 \(extensionItem.title)…"
        try await manage(["managementVersion": 1, "operation": "install", "path": file.path,
                          "sha256": hash, "packageName": extensionItem.id, "versionCode": extensionItem.versionCode,
                          "ecosystem": catalog.kind])
    }

    private func install(_ extensionItem: IosExtension, catalog: IosRepositoryCatalog) async {
        guard !NativeSourceTransport.isFixtureRuntime else {
            error = NativeSourceTransport.fixtureInstallationMessage
            return
        }
        guard !busy else { return }
        busy = true; error = nil
        activeID = InstalledExtension.identity(ecosystem: catalog.kind, package: extensionItem.id)
        defer { busy = false; activeID = nil }
        do {
            try await Self.keepingAwake {
                try await performInstall(extensionItem, catalog: catalog)
            }
            try await sources?.runtime?.sourcesChanged(); await sources?.load()
            status = "\(extensionItem.title) 已安装"
        } catch { report(error) }
    }

    func cancelBatchUpdate() {
        batchCancelled = true
    }

    func updateAll() async {
        await installBatch(rows.filter { $0.state == .update && $0.permitsInstallation }, operation: "更新")
    }

    /// One serialized pipeline owns installation, status, cancellation and per-package retry evidence.
    func installMissing(_ requested: [ExtensionSummary]) async {
        await installBatch(requested, operation: "安装")
    }

    private func installBatch(_ requested: [ExtensionSummary], operation: String) async {
        guard !busy && !isRefreshing && batchProgress == nil else { return }
        var seen = Set<String>()
        let pending = requested.filter { $0.permitsInstallation && seen.insert($0.id).inserted }
        guard !pending.isEmpty else { return }
        guard !NativeSourceTransport.isFixtureRuntime else {
            error = NativeSourceTransport.fixtureInstallationMessage
            return
        }
        guard NativeSourceTransport.isAvailable else {
            error = "当前构建未包含扩展运行时，无法安装扩展"
            return
        }
        busy = true
        error = nil
        batchCancelled = false
        batchResults = []
        defer { busy = false; activeID = nil; batchProgress = nil }
        await Self.keepingAwake {
            for row in pending {
                if batchCancelled || Task.isCancelled { break }
                batchProgress = BatchUpdateProgress(completed: batchResults.count, total: pending.count,
                    currentTitle: row.title, failedCount: batchResults.filter { $0.error != nil }.count)
                activeID = row.id
                do {
                    guard let candidate = row.available, let catalog = catalogs[candidate.repositoryID],
                          let item = catalog.extensions.first(where: { $0.id == row.packageName }) else {
                        throw URLError(.resourceUnavailable)
                    }
                    try await performInstall(item, catalog: catalog)
                    batchResults.append(BatchInstallResult(id: row.id, title: row.title, error: nil))
                } catch {
                    batchResults.append(BatchInstallResult(id: row.id, title: row.title,
                                                          error: error.localizedDescription))
                }
            }
        }
        // Re-enumerate even after a partial failure; an installed package alone does not prove source availability.
        do {
            try await manage(["managementVersion": 1, "operation": "list"])
            try await sources?.runtime?.sourcesChanged()
        }
        catch { report(error) }
        await sources?.load()
        let succeeded = batchResults.filter { $0.error == nil }.count
        let failed = batchResults.count - succeeded
        status = "\(batchCancelled || Task.isCancelled ? "已停止" : operation + "完成")：\(succeeded) 个成功，\(failed) 个失败，" +
            "\(pending.count - batchResults.count) 个未处理"
        UINotificationFeedbackGenerator().notificationOccurred(failed > 0 ? .warning : .success)
    }

    func checkForUpdates(force: Bool = false) async -> Int {
        guard !isRefreshing && !busy else { return updatesCount }
        let now = Date().timeIntervalSince1970
        let last = UserDefaults.standard.double(forKey: Self.lastCheckKey)
        if !force && last > 0 && (now - last) < Self.checkInterval {
            return updatesCount
        }
        await load()
        UserDefaults.standard.set(now, forKey: Self.lastCheckKey)
        return updatesCount
    }
    /// Long conversions must not be cut short by auto-lock; a background task covers a brief app switch.
    private static func keepingAwake<T>(_ work: () async throws -> T) async rethrows -> T {
        UIApplication.shared.isIdleTimerDisabled = true
        let task = UIApplication.shared.beginBackgroundTask(withName: "extension-install")
        defer {
            UIApplication.shared.isIdleTimerDisabled = false
            if task != .invalid { UIApplication.shared.endBackgroundTask(task) }
        }
        return try await work()
    }
    func installFile(_ url: URL) async {
        guard !NativeSourceTransport.isFixtureRuntime else {
            error = NativeSourceTransport.fixtureInstallationMessage
            return
        }
        guard !busy else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size > 0 && size <= 128 * 1024 * 1024 else { throw URLError(.dataLengthExceedsMaximum) }
            let incoming = try NativeSourceTransport.dataDirectory.appendingPathComponent("incoming", isDirectory: true)
            try FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true)
            let file = incoming.appendingPathComponent(UUID().uuidString + ".archive")
            try FileManager.default.copyItem(at: url, to: file)
            defer { try? FileManager.default.removeItem(at: file) }
            let hash = try await Task.detached { () throws -> String in
                SHA256.hash(data: try Data(contentsOf: file, options: .mappedIfSafe)).map { String(format: "%02x", $0) }.joined()
            }.value
            let stem = url.deletingPathExtension().lastPathComponent
                .replacingOccurrences(of: "[^A-Za-z0-9_.-]", with: "_", options: .regularExpression)
            let fallbackName = stem.isEmpty ? "local.plugin" : String(stem.prefix(200))
            status = "正在转换、校验并安装 \(url.lastPathComponent)…"
            try await Self.keepingAwake {
                try await manage(["managementVersion": 1, "operation": "install", "path": file.path,
                                  "sha256": hash, "packageName": fallbackName, "versionCode": 0, "ecosystem": ""])
            }
            try await sources?.runtime?.sourcesChanged(); await sources?.load()
            status = "\(url.lastPathComponent) 已安装"
        } catch { report(error) }
    }
    func uninstall(_ item: InstalledExtension) async {
        guard !busy, !item.bundled else { return }
        busy = true; error = nil; activeID = item.id
        status = "正在卸载扩展…"
        defer { busy = false; activeID = nil }
        do {
            try await manage(["managementVersion": 1, "operation": "uninstall",
                              "packageName": item.packageName, "ecosystem": item.ecosystem])
            try await sources?.runtime?.sourcesChanged(); await sources?.load()
            status = "已卸载扩展"
        } catch { report(error) }
    }
    private func manage(_ request: [String: Any]) async throws {
        guard let runtime = sources?.runtime else { throw CancellationError() }
        let json = String(decoding: try JSONSerialization.data(withJSONObject: request), as: UTF8.self)
        let reply = try await runtime.manage(requestJson: json)
        let result = try JSONDecoder().decode(ManagementResult.self, from: Data(reply.utf8))
        guard result.managementVersion == 1 else { throw URLError(.cannotParseResponse) }
        hasInstalledSnapshot = true
        installed = result.installed
    }

    private func fetch(_ link: RepositoryLink) async throws -> IosRepositoryCatalog {
        guard let store else { throw CancellationError() }
        let path = link.url.pathComponents.filter { $0 != "/" }
        if link.kind == "JAR" && link.url.host == "github.com" && path.count == 2 {
            let api = URL(string: "https://api.github.com/repos/\(path[0])/\(path[1])/releases/latest")!
            let (bytes, _) = try await indexData(api)
            return try store.release(json: String(decoding: bytes, as: UTF8.self), url: link.url.absoluteString)
        }
        let name = link.url.lastPathComponent.lowercased()
        let candidates = name.hasSuffix(".pb") || name.hasSuffix(".json") ? [link.url] :
            ["index.pb", "repo.json", "index.json", "index.min.json"].map { link.url.appendingPathComponent($0) }
        var last: Error = URLError(.fileDoesNotExist)
        for url in candidates {
            do {
                let (bytes, actual) = try await indexData(url)
                let normalizedUrl = Self.normalizeRepoURL(actual.absoluteString)
                var result = try store.decode(bytes: kotlinBytes(bytes), protobuf: url.pathExtension.lowercased() == "pb",
                                              url: normalizedUrl, kind: link.kind)
                if let external = result.externalList, let target = URL(string: external, relativeTo: actual)?.absoluteURL {
                    let (list, listURL) = try await indexData(target)
                    result = try store.external(catalog: result, bytes: kotlinBytes(list), protobuf: target.pathExtension.lowercased() == "pb",
                                                url: listURL.absoluteString)
                }
                return result
            } catch let error as RepositoryHTTPError where error.status == 404 { last = error }
        }
        throw last
    }
    private func indexData(_ url: URL) async throws -> (Data, URL) {
        guard ["https", "http"].contains(url.scheme ?? ""), url.user == nil, url.password == nil else { throw URLError(.badURL) }
        var request = URLRequest(url: url); request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        guard (200..<300).contains(http.statusCode) else { throw RepositoryHTTPError(status: http.statusCode) }
        guard data.count <= 20 * 1024 * 1024, let actual = response.url else { throw URLError(.dataLengthExceedsMaximum) }
        return (data, actual)
    }
    static func normalizeRepoURL(_ urlString: String) -> String {
        var clean = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        for suffix in ["/index.min.json", "/index.json", "/index.pb", "/repo.json"] {
            if clean.hasSuffix(suffix) {
                clean = String(clean.dropLast(suffix.count))
            }
        }
        while clean.hasSuffix("/") && clean.count > 8 {
            clean = String(clean.dropLast())
        }
        return clean
    }
    private func fingerprint(_ catalog: IosRepositoryCatalog) -> String {
        let normUrl = Self.normalizeRepoURL(catalog.url)
        let key = catalog.signingKey.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if key.count == 64 && key.allSatisfy({ $0.isHexDigit }) {
            return key
        }
        let fallbackKey = key.isEmpty ? normUrl : key
        return SHA256.hash(data: Data(fallbackKey.utf8))
            .map { String(format: "%02x", $0) }.joined()
    }
    private func kotlinBytes(_ data: Data) -> KotlinByteArray {
        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: byte)) }
        return bytes
    }
}
private struct RepositoryHTTPError: LocalizedError {
    let status: Int
    var errorDescription: String? { "仓库返回 HTTP \(status)" }
}


/// A non-2xx download keeps the URL and status visible instead of the opaque NSURLErrorDomain -1011.
struct InstallDownloadError: LocalizedError {
    let url: URL
    let statusCode: Int?
    var errorDescription: String? {
        "下载失败（HTTP \(statusCode.map(String.init) ?? "?")）：\(url.absoluteString)"
    }
}
