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
        _image = State(initialValue: SourceIconStore.shared.cached(source.name))
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
    private var inFlight: [String: Task<UIImage?, Never>] = [:]
    private let directory: URL? = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
        .appendingPathComponent("source-icons-v2", isDirectory: true)
    /// Failed website lookups are retried after a week; local artwork can become available after an install.
    private static let retryInterval: TimeInterval = 7 * 24 * 3600
    func clearMemory() { memory.removeAll() }

    func cached(_ name: String) -> UIImage? {
        if let image = memory[name] { return image }
        guard let file = file(name), let data = try? Data(contentsOf: file), let image = UIImage(data: data) else { return nil }
        memory[name] = image
        return image
    }

    func load(_ source: IosSource, runtime: IosSources) async -> UIImage? {
        let name = source.name
        let packagedIcon = ["MIHON", "ANIYOMI", "TSUNDOKU"].contains(source.ecosystem)
        if let image = cached(name) { return image }
        let failedKey = "source.icon.v2.failed.\(name)"
        let failedAt = UserDefaults.standard.double(forKey: failedKey)
        if !packagedIcon, failedAt > 0, Date().timeIntervalSince1970 - failedAt < Self.retryInterval { return nil }
        if let running = inFlight[name] { return await running.value }
        let targetFile = file(name)
        let task = Task.detached(priority: .utility) { () -> UIImage? in
            try? await withReaderStorage { () async throws -> UIImage? in
                guard await RequestGate.icons.enter() else { return nil }
                defer { Task { await RequestGate.icons.leave() } }
                let image: UIImage?
                if packagedIcon {
                    let request = try JSONSerialization.data(withJSONObject: [
                        "managementVersion": 1, "operation": "icon", "packageName": source.group,
                    ])
                    let response = try await runtime.manage(requestJson: String(decoding: request, as: UTF8.self))
                    let result = try JSONSerialization.jsonObject(with: Data(response.utf8)) as? [String: Any]
                    image = (result?["icon"] as? String).flatMap { Data(base64Encoded: $0) }.flatMap { UIImage(data: $0) }
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
        }
        inFlight[name] = task
        let image = await task.value
        inFlight[name] = nil
        if let image { memory[name] = image; UserDefaults.standard.removeObject(forKey: failedKey) }
        else if !packagedIcon, !Task.isCancelled { UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: failedKey) }
        return image
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
                  let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                      kCGImageSourceCreateThumbnailFromImageAlways: true,
                      kCGImageSourceThumbnailMaxPixelSize: 96,
                  ] as CFDictionary),
                  image.width >= 16 else { continue }
            return UIImage(cgImage: image)
        }
        return nil
    }
}
