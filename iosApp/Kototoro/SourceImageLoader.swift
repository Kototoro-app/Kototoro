import Foundation
import KototoroShared
import UIKit

struct ReaderImageArtifact: Equatable, Sendable {
    let directory: URL
    let relativePath: String
    let sha256: String
    let byteSize: Int64
    let encodedWidth: Int
    let encodedHeight: Int
    let tiled: Bool

    func thumbnailImage() async throws -> UIImage {
        try await Self.thumbnailImage(directory: directory, relativePath: relativePath,
                                      sha256: sha256, byteSize: byteSize)
    }

    static func thumbnailImage(directory: URL, relativePath: String, sha256: String,
                               byteSize: Int64, maxPixelSize: Int = 600) async throws -> UIImage {
        let decode = Task.detached(priority: .userInitiated) {
            try await withReaderStorage {
                try await gated(RequestGate.readerDecoding) {
                    try Task.checkCancellation()
                    return try SourceImageFile.decode(directory: directory, relativePath: relativePath,
                                                      sha256: sha256, byteSize: byteSize, maxPixelSize: maxPixelSize)
                }
            }
        }
        let image = try await withTaskCancellationHandler { try await decode.value } onCancel: { decode.cancel() }
        try Task.checkCancellation()
        return image
    }

    func decodeTile(_ tile: IosReaderTile) async throws -> UIImage {
        try Task.checkCancellation()
        let dir = directory; let path = relativePath; let hash = sha256; let length = byteSize
        let rect = CGRect(x: Int(tile.decodeLeft), y: Int(tile.decodeTop),
                          width: Int(tile.decodeWidth), height: Int(tile.decodeHeight))
        let sample = max(1, Int(tile.sampleSize))
        if let base = try await ReaderTileBaseCache.shared.base(for: self, sample: sample) {
            try Task.checkCancellation()
            // The base is this page decoded at 1/sample, so the tile is a crop of it (no copy, no decode).
            let scale = CGFloat(base.width) / CGFloat(max(1, encodedWidth))
            let region = CGRect(x: rect.minX * scale, y: rect.minY * scale,
                                width: rect.width * scale, height: rect.height * scale)
                .integral.intersection(CGRect(x: 0, y: 0, width: base.width, height: base.height))
            guard !region.isEmpty, let cropped = base.cropping(to: region) else {
                throw NSError(domain: "org.skepsun.kototoro.image", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "页面区域越界"])
            }
            return UIImage(cgImage: cropped)
        }
        // A base too large for the cache: decode just this tile.
        let decode = Task.detached(priority: .userInitiated) {
            try await withReaderStorage {
                try await gated(RequestGate.readerDecoding) {
                    try SourceImageFile.decodeRegion(directory: dir, relativePath: path, sha256: hash,
                                                     byteSize: length, rect: rect, sampleSize: sample)
                }
            }
        }
        let image = try await withTaskCancellationHandler { try await decode.value } onCancel: { decode.cancel() }
        try Task.checkCancellation()
        return image
    }

    static func load(directory: URL, relativePath: String, sha256: String,
                     byteSize: Int64, cropMargins: Bool = false) async throws -> ReaderLoadedPage {
        try Task.checkCancellation()
        let decode = Task.detached(priority: .userInitiated) {
            try await withReaderStorage {
                try await gated(RequestGate.readerDecoding) { () throws -> ReaderLoadedPage in
                    // One read and hash for both the size and the decode.
                    let data = try SourceImageFile.validatedData(directory: directory, relativePath: relativePath,
                                                                 sha256: sha256, byteSize: byteSize)
                    let source = try SourceImageFile.createSource(data: data)
                    let dims = try SourceImageFile.dimensions(source: source)
                    let reader = IosReader()
                    let resolved = reader.decodeSize(width: Int32(dims.width), height: Int32(dims.height))
                    let maxPixelSize = Int(max(resolved.width, resolved.height))
                    var image = try SourceImageFile.decode(source: source, maxPixelSize: maxPixelSize)
                    let tiled = reader.isTiled(width: Int32(dims.width), height: Int32(dims.height))
                    if cropMargins && !tiled, let cgImage = image.cgImage, cgImage.width >= 100, cgImage.height >= 100 {
                        let sample = Int(reader.edgeSampleSize(width: Int32(cgImage.width), height: Int32(cgImage.height)))
                        if let sampled = SourceImageFile.samplePixels(image, sampleSize: sample) {
                            let count = sampled.pixels.count
                            let kotlinPixels = KotlinIntArray(size: Int32(count))
                            for i in 0..<count {
                                kotlinPixels.set(index: Int32(i), value: sampled.pixels[i])
                            }
                            if let bounds = reader.contentBounds(
                                width: Int32(cgImage.width),
                                height: Int32(cgImage.height),
                                sampleSize: Int32(sample),
                                bitmapWidth: Int32(sampled.width),
                                bitmapHeight: Int32(sampled.height),
                                pixels: kotlinPixels
                            ) {
                                image = SourceImageFile.crop(image, rect: CGRect(
                                    x: Int(bounds.left), y: Int(bounds.top),
                                    width: Int(bounds.width), height: Int(bounds.height)
                                ))
                            }
                        }
                    }
                    let width = tiled ? dims.width : Int(image.size.width)
                    let height = tiled ? dims.height : Int(image.size.height)
                    return ReaderLoadedPage(
                        image: image,
                        artifact: ReaderImageArtifact(
                            directory: directory,
                            relativePath: relativePath,
                            sha256: sha256,
                            byteSize: byteSize,
                            encodedWidth: max(1, width),
                            encodedHeight: max(1, height),
                            tiled: tiled
                        )
                    )
                }
            }
        }
        let loaded = try await withTaskCancellationHandler { try await decode.value } onCancel: { decode.cancel() }
        try Task.checkCancellation()
        return loaded
    }
}

/// Tiled pages decoded once per sample size, the tiles on screen cropped from them: decoding each tile on its own
/// read and hashed the whole file and decoded the whole page again, for every tile and every scroll step. Bases are
/// bounded by bytes (least recently used go first) and dropped on memory warnings.
actor ReaderTileBaseCache {
    static let shared: ReaderTileBaseCache = {
        let cache = ReaderTileBaseCache()
        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: nil
        ) { _ in Task { await cache.clear() } }
        return cache
    }()

    /// Bytes of decoded bases kept; a single base above it is not cached and its tiles decode directly.
    static let budget = 192 * 1024 * 1024

    private struct Key: Hashable { let sha256: String; let sample: Int }

    private var entries: [Key: CGImage] = [:]
    private var order: [Key] = []
    private var bytes = 0
    private var pending: [Key: Task<CGImage, Error>] = [:]
    private var generation = 0

    /// The page decoded at 1/[sample] with its orientation applied, or nil when it would exceed the budget. A decode
    /// already under way is shared, and it finishes for the cache even if this caller is cancelled.
    func base(for artifact: ReaderImageArtifact, sample: Int) async throws -> CGImage? {
        let key = Key(sha256: artifact.sha256, sample: sample)
        let token = generation
        if let image = entries[key] {
            order.removeAll { $0 == key }; order.append(key)
            return image
        }
        let width = (artifact.encodedWidth + sample - 1) / sample
        let height = (artifact.encodedHeight + sample - 1) / sample
        guard width * height * 4 <= Self.budget else { return nil }
        let task: Task<CGImage, Error>
        if let running = pending[key] {
            task = running
        } else {
            let directory = artifact.directory, path = artifact.relativePath
            let hash = artifact.sha256, length = artifact.byteSize
            let maxPixelSize = max(width, height)
            task = Task.detached(priority: .userInitiated) {
                try await withReaderStorage {
                    try await gated(RequestGate.readerDecoding) {
                        let image = try SourceImageFile.decode(directory: directory, relativePath: path, sha256: hash,
                                                               byteSize: length, maxPixelSize: maxPixelSize)
                        guard let cgImage = image.cgImage else {
                            throw NSError(domain: "org.skepsun.kototoro.image", code: 1,
                                          userInfo: [NSLocalizedDescriptionKey: "无法解码此图片格式"])
                        }
                        return cgImage
                    }
                }
            }
            pending[key] = task
        }
        let image: CGImage
        do {
            image = try await task.value
        } catch {
            if pending[key] == task { pending[key] = nil }
            throw error
        }
        if pending[key] == task { pending[key] = nil }
        guard generation == token else { throw CancellationError() }
        if entries[key] == nil {
            entries[key] = image
            order.append(key)
            bytes += image.bytesPerRow * image.height
            while bytes > Self.budget, order.count > 1, let oldest = order.first {
                order.removeFirst()
                if let evicted = entries.removeValue(forKey: oldest) { bytes -= evicted.bytesPerRow * evicted.height }
            }
        }
        try Task.checkCancellation()
        return entries[key] ?? image
    }

    func clear() {
        generation += 1
        for task in pending.values { task.cancel() }
        pending = [:]
        entries = [:]; order = []; bytes = 0
    }
}

struct ReaderLoadedPage {
    let image: UIImage
    let artifact: ReaderImageArtifact
}

extension IosSourceImage {
    /// Hashing and bounded ImageIO decoding must not run on the main actor.
    func decodedImage(cropMargins: Bool = false) async throws -> UIImage {
        try await loadedPage(cropMargins: cropMargins).image
    }

    /// Fast thumbnail decoding directly via ImageIO downsampled to thumbnail size (e.g. 600px).
    /// Does not allocate full reader page bitmaps or run edge detection.
    func thumbnailImage(maxPixelSize: Int = 600) async throws -> UIImage {
        let directory = try NativeSourceTransport.dataDirectory.appendingPathComponent("images", isDirectory: true)
        return try await ReaderImageArtifact.thumbnailImage(directory: directory, relativePath: relativePath,
                                                            sha256: sha256, byteSize: byteSize,
                                                            maxPixelSize: maxPixelSize)
    }

    func loadedPage(cropMargins: Bool = false) async throws -> ReaderLoadedPage {
        let directory = try NativeSourceTransport.dataDirectory.appendingPathComponent("images", isDirectory: true)
        return try await ReaderImageArtifact.load(directory: directory, relativePath: relativePath,
                                                  sha256: sha256, byteSize: byteSize, cropMargins: cropMargins)
    }
}
