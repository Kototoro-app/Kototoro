import UIKit
import XCTest

@MainActor
final class BookmarkImageStoreTests: XCTestCase {
    private func image(_ color: UIColor, size: CGSize = CGSize(width: 300, height: 420)) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            color.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
    }

    func testSnapshotSurvivesReopeningWithoutTheOriginalImage() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = try XCTUnwrap(image(.red, size: CGSize(width: 900, height: 1400)).pngData())
        let thumbnail = try SourceImageFile.decode(data: source, maxPixelSize: 600)
        let store = BookmarkImageStore(directory: root)
        try await store.save(thumbnail, for: "source|work|chapter|page|created")
        let reopened = BookmarkImageStore(directory: root)
        let saved = try await reopened.image(for: "source|work|chapter|page|created")
        XCTAssertEqual(saved?.size, thumbnail.size)
        XCTAssertLessThanOrEqual(max(saved?.size.width ?? 0, saved?.size.height ?? 0), 600)
    }

    func testRemovingOneBookmarkKeepsOtherSnapshots() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = BookmarkImageStore(directory: root)
        try await store.save(image(.red), for: "work|chapter|page|100")
        try await store.save(image(.blue), for: "work|chapter|page|200")
        try await store.remove(for: "work|chapter|page|100")
        let removed = try await store.image(for: "work|chapter|page|100")
        let remaining = try await store.image(for: "work|chapter|page|200")
        XCTAssertNil(removed)
        XCTAssertNotNil(remaining)
    }

    func testCorruptSnapshotCanBeRebuilt() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = BookmarkImageStore(directory: root)
        try await store.save(image(.red), for: "bookmark")
        let file = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: root,
            includingPropertiesForKeys: nil).first)
        try Data("not an image".utf8).write(to: file)
        do {
            _ = try await store.image(for: "bookmark")
            XCTFail("Corrupt snapshots must fail so the caller can rebuild them")
        } catch { }
        try await store.save(image(.blue), for: "bookmark")
        let restored = try await store.image(for: "bookmark")
        XCTAssertNotNil(restored)
    }

    func testSourceIdentityNeverBecomesAFilePath() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = BookmarkImageStore(directory: root)
        let key = "../source/path?chapter=1\nwork\npage"
        try await store.save(image(.red), for: key)
        let files = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        XCTAssertEqual(files.count, 1)
        XCTAssertEqual(files.first?.pathExtension, "jpg")
        let saved = try await store.image(for: key)
        XCTAssertNotNil(saved)
    }
}
