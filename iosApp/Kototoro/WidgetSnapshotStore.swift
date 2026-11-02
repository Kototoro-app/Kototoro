import Foundation
import UIKit

/// Image preparation and publication are serialized off the main actor, including the latest-request check.
actor WidgetSnapshotStore {
    struct Input: Sendable {
        let id: String
        let title: String
        let detail: String
        let progress: Double?
        let image: UIImage?
    }

    struct Work: Codable, Sendable {
        let id: String
        let title: String
        let detail: String
        let progress: Double?
        let cover: String?
    }

    private var latest: UInt64 = 0

    func begin(_ revision: UInt64) { latest = max(latest, revision) }

    func write(_ inputs: [Input], revision: UInt64, container: URL?) throws -> Bool {
        guard revision == latest, let container else { return false }
        let covers = container.appendingPathComponent("covers", isDirectory: true)
        try FileManager.default.createDirectory(at: covers, withIntermediateDirectories: true)
        let works = inputs.map { input in
            let name = Data(input.id.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_")
                + ".jpg"
            var cover: String?
            if let image = input.image, let data = Self.thumbnail(image).jpegData(compressionQuality: 0.8),
               (try? data.write(to: covers.appendingPathComponent(name), options: .atomic)) != nil {
                cover = name
            }
            return Work(id: input.id, title: input.title, detail: input.detail, progress: input.progress, cover: cover)
        }
        let data = try JSONEncoder().encode(works)
        try data.write(to: container.appendingPathComponent("recent.json"), options: .atomic)
        return true
    }

    private static func thumbnail(_ image: UIImage) -> UIImage {
        let scale = min(1, 240 / max(max(image.size.width, image.size.height), 1))
        let size = CGSize(width: max(1, image.size.width * scale), height: max(1, image.size.height * scale))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
