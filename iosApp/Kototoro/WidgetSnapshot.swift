import KototoroShared
import SwiftUI
import WidgetKit

/// What the "继续阅读" home-screen widget shows: the latest works of the history with their progress and a small
/// cover, written into the App Group the widget extension reads (Android's recent-works widget).
enum WidgetSnapshot {
    static let groupID = "group.org.skepsun.kototoro.ios"

    static var container: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupID) }
    private static let store = WidgetSnapshotStore()
    @MainActor private static var revision: UInt64 = 0

    @MainActor
    static func write(history: [IosLibraryEntry], runtime: IosSources?) async {
        revision += 1
        let request = revision
        await store.begin(request)
        var works: [WidgetSnapshotStore.Input] = []
        for entry in history.prefix(3) {
            guard !Task.isCancelled, request == revision else { return }
            let image: UIImage?
            if let runtime { image = try? await CoverImage.image(runtime: runtime, content: entry.content) }
            else { image = nil }
            works.append(WidgetSnapshotStore.Input(id: ContentMarks.key(entry.content), title: entry.content.title,
                detail: ContinueReadingDetail.text(entry), progress: entry.hasProgress ? Double(entry.progress) : nil,
                image: image))
        }
        guard !Task.isCancelled, request == revision else { return }
        if (try? await store.write(works, revision: request, container: container)) == true {
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}
