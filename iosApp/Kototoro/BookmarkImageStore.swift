import CryptoKit
import Foundation
import UIKit

/// Bookmark snapshots are durable attachments, separate from the disposable reader image cache.
actor BookmarkImageStore {
    private let directory: URL

    init(directory: URL) { self.directory = directory }

    private func file(for key: String) -> URL {
        let digest = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(digest + ".jpg")
    }

    func image(for key: String) throws -> UIImage? {
        let url = file(for: key)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= 2 * 1024 * 1024 else { throw URLError(.cannotDecodeContentData) }
        return try SourceImageFile.decode(data: Data(contentsOf: url), maxPixelSize: 600)
    }

    /// Callers supply a bounded thumbnail decoded from the verified original page.
    func save(_ image: UIImage, for key: String) throws {
        guard let data = image.jpegData(compressionQuality: 0.85), data.count <= 2 * 1024 * 1024 else {
            throw CocoaError(.fileWriteUnknown)
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: file(for: key), options: .atomic)
    }

    func remove(for key: String) throws {
        let url = file(for: key)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
}
