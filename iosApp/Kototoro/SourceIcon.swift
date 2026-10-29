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
    private let directory: URL? = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
        .appendingPathComponent("source-icons-v2", isDirectory: true)
    /// Failed website lookups are retried after a week; local artwork can become available after an install.
    private static let retryInterval: TimeInterval = 7 * 24 * 3600
    /// The JNI call blocks its thread, so it runs here at utility QoS instead of on a cooperative or Kotlin IO thread.
    private nonisolated static let hostQueue = DispatchQueue(label: "kototoro.source-icons", qos: .utility)

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
        let transport = runtime.hostTransport
        let failedKey = "source.icon.v2.failed.\(key)"
        let failedAt = UserDefaults.standard.double(forKey: failedKey)
        let recentlyFailed = !packaged && failedAt > 0 && Date().timeIntervalSince1970 - failedAt < Self.retryInterval
        let targetFile = file(key)
        request.task = Task.detached(priority: .utility) { () -> UIImage? in
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
                let gate = packaged ? RequestGate.hostIcons : RequestGate.icons
                guard await gate.enter() else { return nil }
                defer { Task { await gate.leave() } }
                let image: UIImage?
                if packaged {
                    guard let transport else { return nil }
                    image = try await Self.packagedIcon(group, transport: transport)
                } else {
                    guard let homepage = try? await runtime.homepage(sourceName: name),
                          let base = URL(string: homepage), base.host() != nil else { return nil }
                    image = await Self.fetch(base)
                }
                if let image, let data = image.pngData(), let file = targetFile {
                    try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try? data.write(to: file, options: .atomic)
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

    private func finish(_ key: String, _ token: UUID, image: UIImage?, failedKey: String?) {
        if inFlight[key]?.token == token { inFlight[key] = nil }
        if let image {
            memory[key] = image
            UserDefaults.standard.removeObject(forKey: "source.icon.v2.failed.\(key)")
        } else if let failedKey {
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: failedKey)
        }
    }

    /// The host writes the artwork to a file and answers with its path, so the bytes never pass through the
    /// interpreted JVM's Base64/JSON or the Kotlin heap.
    private nonisolated static func packagedIcon(_ packageName: String, transport: IosSourceTransport) async throws -> UIImage? {
        let request = String(decoding: try JSONSerialization.data(withJSONObject: [
            "managementVersion": 1, "operation": "iconFile", "packageName": packageName,
        ]), as: UTF8.self)
        // The JNI transport is documented as safe to call from any thread.
        nonisolated(unsafe) let transport = transport
        let response: String = try await withCheckedThrowingContinuation { continuation in
            hostQueue.async { continuation.resume(with: Result { try transport.exchange(requestJson: request) }) }
        }
        let result = try JSONSerialization.jsonObject(with: Data(response.utf8)) as? [String: Any]
        guard let path = result?["iconPath"] as? String else { return nil }
        let url = URL(fileURLWithPath: path)
        defer { try? FileManager.default.removeItem(at: url) }
        return (try? Data(contentsOf: url)).flatMap { decode($0) }
    }

    private func file(_ name: String) -> URL? {
        let safe = name.map { $0.isLetter || $0.isNumber || $0 == "_" ? $0 : "-" }
        return directory?.appendingPathComponent(String(safe) + ".png")
    }

    private nonisolated static func fetch(_ base: URL) async -> UIImage? {
        var root = URLComponents()
        root.scheme = base.scheme ?? "https"; root.host = base.host(); root.port = base.port
        guard let origin = root.url else { return nil }
        for path in ["apple-touch-icon.png", "favicon.ico"] {
            var request = URLRequest(url: origin.appendingPathComponent(path), timeoutInterval: 5)
            request.setValue("image/*", forHTTPHeaderField: "Accept")
            guard let (data, response) = try? await URLSession.shared.data(for: request),
                  let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  let image = decode(data), image.size.width >= 16 else { continue }
            return image
        }
        return nil
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
