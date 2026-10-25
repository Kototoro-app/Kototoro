import KototoroShared
import SwiftUI
import WidgetKit

/// What the "继续阅读" home-screen widget shows: the latest works of the history with their progress and a small
/// cover, written into the App Group the widget extension reads (Android's recent-works widget).
enum WidgetSnapshot {
    static let groupID = "group.org.skepsun.kototoro.ios"

    private struct Work: Codable {
        let id: String
        let title: String
        let detail: String
        let progress: Double?
        let cover: String?
    }

    static var container: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupID) }

    @MainActor
    static func write(history: [IosLibraryEntry], runtime: IosSources?) async {
        guard let container else { return }
        let covers = container.appending(path: "covers", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: covers, withIntermediateDirectories: true)
        var works: [Work] = []
        for entry in history.prefix(3) {
            let name = ContentMarks.key(entry.content).data(using: .utf8)!.base64EncodedString()
                .replacingOccurrences(of: "/", with: "_") + ".jpg"
            var cover: String?
            if let runtime, let image = try? await CoverImage.image(runtime: runtime, content: entry.content),
               let data = thumbnail(image)?.jpegData(compressionQuality: 0.8),
               (try? data.write(to: covers.appending(path: name), options: .atomic)) != nil {
                cover = name
            }
            works.append(Work(id: ContentMarks.key(entry.content), title: entry.content.title,
                              detail: ContinueReadingDetail.text(entry),
                              progress: entry.hasProgress ? Double(entry.progress) : nil, cover: cover))
        }
        guard let data = try? JSONEncoder().encode(works) else { return }
        try? data.write(to: container.appending(path: "recent.json"), options: .atomic)
        WidgetCenter.shared.reloadAllTimelines()
    }

    private static func thumbnail(_ image: UIImage) -> UIImage? {
        let scale = min(1, 240 / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        return UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }
}
