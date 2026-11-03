import Combine
import CryptoKit
import Foundation

/// iOS compares the IPA's own marketing/build numbers, including new nightly builds of the same version.
struct AppReleaseVersion: Comparable {
    let number: String
    let build: String
    private let numbers: [UInt64]
    private let builds: [UInt64]

    init?(number: String, build: String) {
        guard let numbers = Self.components(number), let builds = Self.components(build) else { return nil }
        self.number = number
        self.build = build
        self.numbers = numbers
        self.builds = builds
    }

    var description: String { "\(number) (\(build))" }

    static func == (lhs: Self, rhs: Self) -> Bool {
        compare(lhs.numbers, rhs.numbers) == .orderedSame && compare(lhs.builds, rhs.builds) == .orderedSame
    }

    static func < (lhs: Self, rhs: Self) -> Bool {
        let version = compare(lhs.numbers, rhs.numbers)
        return version == .orderedSame
            ? compare(lhs.builds, rhs.builds) == .orderedAscending : version == .orderedAscending
    }

    static func components(_ text: String) -> [UInt64]? {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...3).contains(parts.count),
              parts.allSatisfy({ !$0.isEmpty && $0.utf8.allSatisfy { (48...57).contains($0) } }) else { return nil }
        let numbers = parts.compactMap { UInt64($0) }
        return numbers.count == parts.count ? numbers : nil
    }

    static func compare(_ lhs: [UInt64], _ rhs: [UInt64]) -> ComparisonResult {
        for index in 0..<max(lhs.count, rhs.count) {
            let left = index < lhs.count ? lhs[index] : 0
            let right = index < rhs.count ? rhs[index] : 0
            if left != right { return left < right ? .orderedAscending : .orderedDescending }
        }
        return .orderedSame
    }
}

enum AppUpdateChannel: String, CaseIterable, Identifiable {
    case release, nightly
    var id: String { rawValue }
    var title: String { self == .release ? String(localized: "正式版") : "Nightly" }
    var sourceURL: URL {
        URL(string: "https://kototoro-app.github.io/Kototoro/altstore/\(rawValue).json")!
    }
    var releasesURL: URL {
        let repository = self == .release ? "Kototoro" : "Kototoro-Nightly"
        return URL(string: "https://github.com/Kototoro-app/\(repository)/releases")!
    }

    func storeURL(scheme: String) -> URL {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "source"
        components.queryItems = [URLQueryItem(name: "url", value: sourceURL.absoluteString)]
        return components.url!
    }
}

struct AppBuildInfo {
    static let current = AppBuildInfo(info: Bundle.main.infoDictionary ?? [:])
    let version: AppReleaseVersion
    let channel: AppUpdateChannel
    let isDebug: Bool

    init(info: [String: Any]) {
        version = AppReleaseVersion(number: info["CFBundleShortVersionString"] as? String ?? "",
                                    build: info["CFBundleVersion"] as? String ?? "")
            ?? AppReleaseVersion(number: "0.0.0", build: "0")!
        channel = AppUpdateChannel(rawValue: info["KototoroUpdateChannel"] as? String ?? "") ?? .release
        isDebug = info["KototoroBuildConfiguration"] as? String == "Debug"
    }

    var description: String { version.description + (isDebug ? " · Debug" : "") }
}

struct AppRelease: Decodable {
    let version: String
    let buildVersion: String
    let downloadURL: URL
    let size: Int64
    let sha256: String
    let minOSVersion: String
    let localizedDescription: String

    var identity: AppReleaseVersion? { AppReleaseVersion(number: version, build: buildVersion) }
    var description: String { "\(version) (\(buildVersion))" }
    var isValid: Bool {
        identity != nil && AppReleaseVersion.components(minOSVersion) != nil && size > 0 &&
            downloadURL.scheme == "https" && downloadURL.host != nil &&
            downloadURL.pathExtension.lowercased() == "ipa" && sha256.utf8.count == 64 &&
            sha256.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) || (65...70).contains($0) }
    }
}

/// The existing AltStore feed already contains the exact IPA version, build, size and checksum written by CI.
struct AppUpdateFeed: Decodable {
    struct App: Decodable {
        let bundleIdentifier: String
        let versions: [AppRelease]
    }
    let apps: [App]

    func latest(osVersion: String) throws -> AppRelease {
        // Sideloading tools can change the installed bundle ID when signing; the feed keeps the original identity.
        guard let app = apps.first(where: { $0.bundleIdentifier == "org.skepsun.kototoro.ios" }) else {
            throw AppUpdateError.noRelease
        }
        let valid = app.versions.filter(\.isValid)
        guard !valid.isEmpty else { throw AppUpdateError.noRelease }
        guard let os = AppReleaseVersion.components(osVersion),
              let release = valid.filter({
                  AppReleaseVersion.compare(AppReleaseVersion.components($0.minOSVersion)!, os) != .orderedDescending
              }).max(by: { $0.identity! < $1.identity! }) else { throw AppUpdateError.incompatibleOS }
        return release
    }
}

enum AppUpdateError: LocalizedError {
    case noRelease, incompatibleOS, invalidResponse, invalidPackage
    var errorDescription: String? {
        switch self {
        case .noRelease: String(localized: "此渠道尚未发布可用的 iOS 安装包。")
        case .incompatibleOS: String(localized: "此渠道的安装包需要更新的 iOS 版本。")
        case .invalidResponse: String(localized: "更新服务器返回了无效响应，请稍后重试。")
        case .invalidPackage: String(localized: "安装包校验失败，请重新下载。")
        }
    }
}

struct AppUpdateClient {
    var session: URLSession = .shared

    func latest(channel: AppUpdateChannel) async throws -> AppRelease {
        var request = URLRequest(url: channel.sourceURL, cachePolicy: .reloadIgnoringLocalCacheData,
                                 timeoutInterval: 30)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        try Self.validate(response)
        let feed = try JSONDecoder().decode(AppUpdateFeed.self, from: data)
        let os = ProcessInfo.processInfo.operatingSystemVersion
        return try feed.latest(osVersion: "\(os.majorVersion).\(os.minorVersion).\(os.patchVersion)")
    }

    /// URLSession writes directly to disk; hashing is streamed on a utility task rather than loading the IPA into RAM.
    func download(_ release: AppRelease, from url: URL) async throws -> URL {
        guard release.isValid else { throw AppUpdateError.invalidPackage }
        let request = URLRequest(url: url, timeoutInterval: 300)
        let (temporary, response) = try await session.download(for: request)
        defer { try? FileManager.default.removeItem(at: temporary) }
        try Self.validate(response)
        try Task.checkCancellation()
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("app-update-\(UUID().uuidString)")
        let destination = directory
            .appendingPathComponent("Kototoro-\(release.version)-\(release.buildVersion)-ios.ipa")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        do {
            try FileManager.default.moveItem(at: temporary, to: destination)
            let verification = Task.detached(priority: .utility) { try Self.verify(destination, release: release) }
            try await withTaskCancellationHandler {
                try await verification.value
            } onCancel: { verification.cancel() }
            try Task.checkCancellation()
            return destination
        } catch {
            try? FileManager.default.removeItem(at: directory)
            throw error
        }
    }

    static func validate(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw AppUpdateError.invalidResponse
        }
    }

    static func verify(_ file: URL, release: AppRelease) throws {
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize
        guard size.map(Int64.init) == release.size else { throw AppUpdateError.invalidPackage }
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var digest = SHA256()
        while let bytes = try handle.read(upToCount: 1024 * 1024), !bytes.isEmpty {
            try Task.checkCancellation()
            digest.update(data: bytes)
        }
        let hash = digest.finalize().map { String(format: "%02x", $0) }.joined()
        guard hash.caseInsensitiveCompare(release.sha256) == .orderedSame else { throw AppUpdateError.invalidPackage }
    }
}

@MainActor
final class AppUpdateModel: ObservableObject {
    @Published private(set) var release: AppRelease?
    @Published private(set) var checking = false
    @Published private(set) var downloading = false
    @Published private var file: AppUpdateFile?
    @Published private(set) var error: String?
    private let client: AppUpdateClient
    private var request = UUID()
    private var downloadTask: Task<Void, Never>?
    var downloadedFile: URL? { file?.url }

    init(client: AppUpdateClient = AppUpdateClient()) { self.client = client }

    func check(channel: AppUpdateChannel) async {
        guard !downloading else { return }
        let token = UUID()
        request = token
        checking = true
        release = nil
        error = nil
        discardFile()
        defer { if request == token { checking = false } }
        do {
            let latest = try await client.latest(channel: channel)
            guard !Task.isCancelled, request == token else { return }
            release = latest
        } catch {
            if !Task.isCancelled, request == token { self.error = error.localizedDescription }
        }
    }

    func download(from url: URL) {
        guard let release, !checking, !downloading else { return }
        downloading = true
        error = nil
        discardFile()
        downloadTask = Task {
            defer { downloading = false; downloadTask = nil }
            do {
                let downloaded = AppUpdateFile(url: try await client.download(release, from: url))
                guard !Task.isCancelled else { return }
                file = downloaded
            }
            catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
    }

    func cancelDownload() { downloadTask?.cancel() }

    private func discardFile() {
        file = nil
    }
}

/// Owns only the app-created temporary directory, including a result cancelled before it reaches the UI.
private final class AppUpdateFile {
    let url: URL
    init(url: URL) { self.url = url }
    deinit {
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
    }
}
