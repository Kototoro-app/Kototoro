import Foundation
import os

/// Serializes offline metadata I/O and repairs without putting whole-chapter JSON work on the UI actor.
actor ReaderDownloadStore {
    static let shared = ReaderDownloadStore()

    struct ImageRecord: Codable, Equatable, Sendable {
        let path: String
        let hash: String
        let bytes: Int64
    }

    struct Manifest: Codable, Sendable {
        let pages: String
        var images: [ImageRecord]
    }

    enum Failure: LocalizedError {
        case invalidManifest
        var errorDescription: String? { String(localized: "离线章节清单无法读取，可重新下载。") }
    }

    private struct Snapshot {
        let file: URL
        let size: Int
        let modified: Date?
        let inode: UInt64?
        let manifest: Manifest
    }

    private var cached: Snapshot?
    private static let maximumManifestBytes = 32 * 1024 * 1024
    private nonisolated static let invalidDirectories = OSAllocatedUnfairLock(initialState: Set<URL>())

    nonisolated static func isKnownInvalid(in directory: URL) -> Bool {
        invalidDirectories.withLock { $0.contains(directory.standardizedFileURL) }
    }

    func invalidate(_ directory: URL) {
        Self.invalidDirectories.withLock { _ = $0.insert(directory.standardizedFileURL) }
        if cached?.file.deletingLastPathComponent() == directory.standardizedFileURL { cached = nil }
    }

    func read(in directory: URL) throws -> Manifest? {
        try Task.checkCancellation()
        let file = directory.standardizedFileURL.appendingPathComponent("chapter.json")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        do {
            let values = try file.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey,
                                                          .isRegularFileKey, .isSymbolicLinkKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true,
                  let size = values.fileSize, size > 0, size <= Self.maximumManifestBytes else {
                throw Failure.invalidManifest
            }
            let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
            let inode = (attributes[.systemFileNumber] as? NSNumber)?.uint64Value
            if let cached, cached.file == file, cached.size == size,
               cached.modified == values.contentModificationDate, cached.inode == inode {
                return cached.manifest
            }
            let manifest = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: file))
            try validate(manifest)
            cached = Snapshot(file: file, size: size, modified: values.contentModificationDate,
                              inode: inode, manifest: manifest)
            Self.invalidDirectories.withLock { _ = $0.remove(directory.standardizedFileURL) }
            return manifest
        } catch {
            invalidate(directory)
            throw error
        }
    }

    func write(_ manifest: Manifest, in directory: URL, name: String = "chapter.json") throws {
        try Task.checkCancellation()
        guard name == "chapter.json" || name == "partial.json" else { throw Failure.invalidManifest }
        try validate(manifest)
        let data = try JSONEncoder().encode(manifest)
        guard data.count <= Self.maximumManifestBytes else { throw Failure.invalidManifest }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: directory.appendingPathComponent(name), options: .atomic)
        cached = nil
        if name == "chapter.json" {
            Self.invalidDirectories.withLock { _ = $0.remove(directory.standardizedFileURL) }
        }
    }

    /// Verify and copy the replacement before publishing its record.
    /// A changed page list must never be repaired by index.
    func repairImage(in directory: URL, index: Int, expected: Manifest,
                     replacement: ImageRecord, sourceDirectory: URL) throws -> Bool {
        try Task.checkCancellation()
        guard var current = try read(in: directory), current.pages == expected.pages,
              current.images.indices.contains(index), expected.images.indices.contains(index),
              current.images[index] == expected.images[index] else { return false }
        let data = try SourceImageFile.validatedData(directory: sourceDirectory, relativePath: replacement.path,
                                                     sha256: replacement.hash, byteSize: replacement.bytes)
        try Task.checkCancellation()
        try data.write(to: directory.appendingPathComponent(replacement.path), options: .atomic)
        try Task.checkCancellation()
        current.images[index] = replacement
        try write(current, in: directory)
        return true
    }

    private func validate(_ manifest: Manifest) throws {
        guard !manifest.pages.isEmpty, manifest.images.allSatisfy({ record in
            record.hash.count == 64 && record.hash.allSatisfy { "0123456789abcdef".contains($0) } &&
                record.path == "\(record.hash).img" && record.bytes > 0 && record.bytes <= 64 * 1024 * 1024
        }) else { throw Failure.invalidManifest }
    }
}
