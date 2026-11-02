import Foundation
import UIKit
import XCTest

final class WidgetSnapshotStoreTests: XCTestCase {
    private func directory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory
    }

    func testOlderSlowSnapshotCannotOverwriteTheLatestHistory() async throws {
        let directory = try directory()
        let store = WidgetSnapshotStore()
        await store.begin(1)
        await store.begin(2)
        let latest = WidgetSnapshotStore.Input(id: "latest", title: "最新", detail: "第 2 话", progress: 0.5, image: nil)
        let published = try await store.write([latest], revision: 2, container: directory)
        XCTAssertTrue(published)
        await store.begin(1)
        let stale = try await store.write([], revision: 1, container: directory)
        XCTAssertFalse(stale)
        let works = try JSONDecoder().decode([WidgetSnapshotStore.Work].self,
            from: Data(contentsOf: directory.appendingPathComponent("recent.json")))
        XCTAssertEqual(works.map(\.id), ["latest"])
    }

    func testPreparedCoverIsBoundedInPixelsAndReferencedByTheCommittedSnapshot() async throws {
        let directory = try directory()
        let image = UIGraphicsImageRenderer(size: CGSize(width: 600, height: 900)).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 600, height: 900))
        }
        let store = WidgetSnapshotStore()
        await store.begin(1)
        let input = WidgetSnapshotStore.Input(id: "cover", title: "封面", detail: "继续阅读", progress: nil, image: image)
        _ = try await store.write([input], revision: 1, container: directory)
        let works = try JSONDecoder().decode([WidgetSnapshotStore.Work].self,
            from: Data(contentsOf: directory.appendingPathComponent("recent.json")))
        let name = try XCTUnwrap(works.first?.cover)
        let data = try Data(contentsOf: directory.appendingPathComponent("covers/\(name)"))
        let cover = try XCTUnwrap(UIImage(data: data)?.cgImage)
        XCTAssertLessThanOrEqual(max(cover.width, cover.height), 240)
    }
}
