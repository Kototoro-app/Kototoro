import ImageIO
import KototoroShared
import SwiftUI

/// APK artwork for Mihon-family sources, and site icons for other sources, with a monogram fallback.
struct SourceIcon: View {
    let runtime: IosSources?
    let source: IosSource
    var size: CGFloat = 30
    @State private var image: UIImage?

    init(runtime: IosSources?, source: IosSource, size: CGFloat = 30) {
        self.runtime = runtime
        self.source = source
        self.size = size
        _image = State(initialValue: SourceIconStore.shared.cached(source))
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
                    .padding(size * 0.08)
                    .background(.background.secondary)
            } else {
                Text(Self.monogram(source.title))
                    .font(.system(size: size * 0.48, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Self.tint(source.name).gradient)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.24, style: .continuous))
        .accessibilityHidden(true)
        .task(id: source.name) {
            guard image == nil, let runtime else { return }
            if let loaded = await SourceIconStore.shared.load(source, runtime: runtime) {
                withAnimation(.easeOut(duration: 0.2)) { image = loaded }
            }
        }
    }

    static func monogram(_ title: String) -> String {
        title.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "?"
    }

    static func tint(_ name: String) -> Color {
        let palette: [Color] = [.blue, .indigo, .purple, .pink, .red, .orange, .teal, .green, .cyan, .brown]
        let hash = name.unicodeScalars.reduce(UInt32(5381)) { ($0 &* 33) &+ $1.value }
        return palette[Int(hash % UInt32(palette.count))]
    }
}

@MainActor
final class SourceIconStore {
    static let shared = SourceIconStore()
    private var memory: [String: UIImage] = [:]
    private var inFlight: [String: Request] = [:]
    /// Package → build last seen in an install result, to tell which packages an install or update changed.
    private var knownBuilds: [String: String] = [:]
    private let directory: URL? = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
        .appendingPathComponent("source-icons-v2", isDirectory: true)
    /// Failed website lookups are retried after a week; local artwork can become available after an install.
    private static let retryInterval: TimeInterval = 7 * 24 * 3600

    /// One load shared by every row showing the same icon; cancelled once no row is waiting for it.
    private final class Request {
        let token = UUID()
        var task: Task<UIImage?, Never>?
        var waiters = 0
    }

    func clearMemory() { memory.removeAll() }

    /// View initialization only consults memory; a cold disk cache is loaded by the coalesced background task.
    func cached(_ source: IosSource) -> UIImage? { memory[Self.key(source)] }

    /// APK artwork belongs to the package, so all languages of one extension share a single entry and host call.
    private static func key(_ source: IosSource) -> String {
        isPackaged(source) ? "package.\(source.group)" : source.name
    }

    private static func isPackaged(_ source: IosSource) -> Bool {
        ["MIHON", "ANIYOMI", "TSUNDOKU"].contains(source.ecosystem) && !source.group.isEmpty
    }

    func load(_ source: IosSource, runtime: IosSources) async -> UIImage? {
        let key = Self.key(source)
        if let image = memory[key] { return image }
        let request = inFlight[key] ?? start(source, key: key, runtime: runtime)
        request.waiters += 1
        guard let task = request.task else { return nil }
        return await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            Task { @MainActor in self.abandon(key, request) }
        }
    }

    /// A row scrolled away: queued work for an icon nobody shows any more leaves the gate without running.
    private func abandon(_ key: String, _ request: Request) {
        request.waiters -= 1
        guard request.waiters <= 0, inFlight[key] === request else { return }
        inFlight[key] = nil
        request.task?.cancel()
    }

    private func start(_ source: IosSource, key: String, runtime: IosSources) -> Request {
        let request = Request()
        let token = request.token
        let name = source.name
        let packaged = Self.isPackaged(source)
        let group = source.group
        let ecosystem = source.ecosystem
        let failedKey = "source.icon.v2.failed.\(key)"
        let failedAt = SourceIconRetry.failures.double(forKey: failedKey)
        let recentlyFailed = !packaged && failedAt > 0 && Date().timeIntervalSince1970 - failedAt < Self.retryInterval
        let directory = directory
        request.task = Task.detached(priority: .utility) { () -> UIImage? in
            // APK artwork is cached per build of the package, so an update never shows the previous icon.
            let artifact = packaged ? ExtensionArtifacts.extension(group) : nil
            if packaged && artifact == nil {
                await self.finish(key, token, image: nil, failedKey: nil)
                return nil
            }
            let targetFile = Self.file(key, build: artifact?.sha256, in: directory)
            // Cached artwork neither waits behind site lookups nor takes the storage lease; a file removed meanwhile
            // just falls through to the lookup below.
            if let file = targetFile, let data = try? Data(contentsOf: file), let image = Self.decode(data) {
                await self.finish(key, token, image: image, failedKey: nil)
                return image
            }
            guard !recentlyFailed else {
                await self.finish(key, token, image: nil, failedKey: nil)
                return nil
            }
            let image = try? await withReaderStorage { () async throws -> UIImage? in
                let image: UIImage?
                if let artifact {
                    image = await Self.artwork(group, artifact: artifact, file: targetFile)
                } else {
                    // Parser plugins answer from their class files; only native sites (Apple CMS) ask the runtime,
                    // which never reaches the embedded JVM for them.
                    let homepage: String? = switch ecosystem {
                    case "JAR": group.isEmpty ? nil : await ParserSiteIndex.shared.homepage(plugin: group, source: name)
                    case "CMS": try? await runtime.homepage(sourceName: name)
                    default: nil
                    }
                    guard let homepage, let base = URL(string: homepage), base.host() != nil else { return nil }
                    guard await RequestGate.icons.enter() else { return nil }
                    defer { Task { await RequestGate.icons.leave() } }
                    image = await Self.fetch(base)
                    if let image, let file = targetFile { Self.store(image, at: file) }
                }
                return image
            }
            let failed = image == nil && !packaged && !Task.isCancelled
            await self.finish(key, token, image: image, failedKey: failed ? failedKey : nil)
            return image
        }
        inFlight[key] = request
        return request
    }

    /// Reads, downsamples and stores one package's artwork; older builds' files of the package are removed.
    private nonisolated static func artwork(_ packageName: String, artifact: ExtensionArtifact, file: URL?) async -> UIImage? {
        guard await RequestGate.extensionArtwork.enter() else { return nil }
        defer { Task { await RequestGate.extensionArtwork.leave() } }
        guard let image = ExtensionArtwork.icon(packageName: packageName).flatMap(decode) else { return nil }
        if let file {
            store(image, at: file)
            let prefix = file.deletingPathExtension().lastPathComponent.split(separator: "@").first.map(String.init)
            let siblings = (try? FileManager.default.contentsOfDirectory(at: file.deletingLastPathComponent(),
                                                                          includingPropertiesForKeys: nil)) ?? []
            for sibling in siblings where sibling != file && sibling.lastPathComponent.hasPrefix((prefix ?? "") + "@") {
                try? FileManager.default.removeItem(at: sibling)
            }
        }
        return image
    }

    /// After an install, update or removal: icons of changed packages are dropped from memory, and the new builds'
    /// artwork and parser site indexes are prepared in the background so the next browse reads them from disk.
    /// [prepare] is false for a plain listing, which only records the builds.
    func artifactsChanged(_ installed: [InstalledExtension], prepare: Bool) {
        var current: [String: String] = [:]
        for item in installed { current[item.packageName] = item.sha256 }
        for (packageName, build) in knownBuilds where current[packageName] != build {
            memory["package.\(packageName)"] = nil
        }
        let changed = installed.filter { knownBuilds[$0.packageName] != $0.sha256 }
        knownBuilds = current
        guard prepare, !changed.isEmpty else { return }
        let directory = directory
        Task.detached(priority: .utility) {
            for item in changed {
                if item.ecosystem == "JAR" {
                    await ParserSiteIndex.shared.prepare(plugin: item.packageName)
                    continue
                }
                let key = "package.\(item.packageName)"
                guard let artifact = ExtensionArtifacts.extension(item.packageName),
                      let file = Self.file(key, build: artifact.sha256, in: directory),
                      !FileManager.default.fileExists(atPath: file.path),
                      let image = await Self.artwork(item.packageName, artifact: artifact, file: file) else { continue }
                await MainActor.run { self.memory[key] = image }
            }
        }
    }

    private func finish(_ key: String, _ token: UUID, image: UIImage?, failedKey: String?) {
        if inFlight[key]?.token == token { inFlight[key] = nil }
        if let image {
            memory[key] = image
            SourceIconRetry.failures.removeObject(forKey: "source.icon.v2.failed.\(key)")
        } else if let failedKey {
            SourceIconRetry.failures.set(Date().timeIntervalSince1970, forKey: failedKey)
        }
    }

    /// `<key>@<build>.png` for package artwork, `<key>.png` for site icons.
    private nonisolated static func file(_ name: String, build: String?, in directory: URL?) -> URL? {
        let safe = String(name.map { $0.isLetter || $0.isNumber || $0 == "_" ? $0 : "-" })
        let suffix = build.map { "@" + String($0.filter { $0.isLetter || $0.isNumber }.prefix(16)) } ?? ""
        return directory?.appendingPathComponent(safe + suffix + ".png")
    }

    private nonisolated static func store(_ image: UIImage, at file: URL) {
        guard let data = image.pngData() else { return }
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: file, options: .atomic)
    }

    /// Some sites refuse the default CFNetwork agent outright (Cloudflare answers 403), so ask as Safari does.
    private static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    /// The conventional root icons and the home page's declared icons are requested together, then chosen by how
    /// well they suit a 30pt tile: touch icons (at the root, then declared), the root favicon, other declared icons.
    private nonisolated static func fetch(_ base: URL) async -> UIImage? {
        var root = URLComponents()
        root.scheme = base.scheme ?? "https"; root.host = base.host(); root.port = base.port
        guard let origin = root.url else { return nil }
        async let touch = image(origin.appendingPathComponent("apple-touch-icon.png"))
        async let favicon = image(origin.appendingPathComponent("favicon.ico"))
        async let declared = declaredIcons(origin)
        if let touch = await touch { return touch }
        let (touchLinks, plainLinks) = await declared
        for url in touchLinks {
            if Task.isCancelled { return nil }
            if let image = await image(url) { return image }
        }
        if let favicon = await favicon { return favicon }
        for url in plainLinks where url.path != "/favicon.ico" {
            if Task.isCancelled { return nil }
            if let image = await image(url) { return image }
        }
        return nil
    }

    private nonisolated static func image(_ url: URL) async -> UIImage? {
        var request = URLRequest(url: url, timeoutInterval: 5)
        request.setValue("image/*", forHTTPHeaderField: "Accept")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              let image = decode(data), image.size.width >= 16 else { return nil }
        return image
    }

    /// `<link rel="…icon…" href>` from the start of the home page, split into touch and other icons; SVG cannot be
    /// decoded here.
    private nonisolated static func declaredIcons(_ origin: URL) async -> (touch: [URL], plain: [URL]) {
        var request = URLRequest(url: origin, timeoutInterval: 5)
        request.setValue("text/html", forHTTPHeaderField: "Accept")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        guard let (bytes, response) = try? await URLSession.shared.bytes(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return ([], []) }
        var head = Data()
        do {
            for try await byte in bytes {
                head.append(byte)
                if head.count >= 64 * 1024 { break }
            }
        } catch { if head.isEmpty { return ([], []) } }
        let page = String(decoding: head, as: UTF8.self)
        let base = http.url ?? origin
        var touch: [URL] = []
        var plain: [URL] = []
        for tag in page.matches(of: /<link\b[^>]*>/.ignoresCase()) {
            let text = String(page[tag.range])
            guard let rel = attribute("rel", in: text)?.lowercased(), rel.contains("icon"),
                  let href = attribute("href", in: text), !href.lowercased().hasSuffix(".svg"),
                  let url = URL(string: href.trimmingCharacters(in: .whitespaces), relativeTo: base)?.absoluteURL,
                  url.scheme?.hasPrefix("http") == true else { continue }
            if rel.contains("apple-touch-icon") { touch.append(url) } else { plain.append(url) }
        }
        return (Array(touch.prefix(2)), Array(plain.prefix(2)))
    }

    private nonisolated static func attribute(_ name: String, in tag: String) -> String? {
        guard let match = tag.firstMatch(of: try! Regex("\\b\(name)\\s*=\\s*([\"'])(.*?)\\1").ignoresCase()),
              let value = match.output[2].substring else { return nil }
        return String(value)
    }

    private nonisolated static func decode(_ data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 96,
                kCGImageSourceShouldCacheImmediately: true,
              ] as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }
}
