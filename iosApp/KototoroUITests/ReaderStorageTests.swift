import Foundation
import XCTest

@MainActor
final class ReaderStorageTests: XCTestCase {
    func testAutomaticCacheScanWaitsForItsIntervalAndHonorsBudgetChanges() {
        var schedule = ReaderCacheTrimSchedule()
        let now = ContinuousClock().now
        XCTAssertFalse(schedule.isDue(at: now, budget: 0))
        XCTAssertTrue(schedule.isDue(at: now, budget: 512))
        schedule.recordAttempt(at: now, budget: 512)
        XCTAssertFalse(schedule.isDue(at: now.advanced(by: .seconds(59)), budget: 512))
        XCTAssertTrue(schedule.isDue(at: now.advanced(by: .seconds(60)), budget: 512))
        XCTAssertTrue(schedule.isDue(at: now.advanced(by: .seconds(1)), budget: 256))
    }

    private struct Fixture {
        let root: URL
        let downloads: URL
        let images: URL
        let store: ReaderStorageStore
    }

    private func fixture(files: FileManager = .default) throws -> Fixture {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let downloads = root.appendingPathComponent("downloads")
        let images = root.appendingPathComponent("images")
        try FileManager.default.createDirectory(at: images, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        return Fixture(root: root, downloads: downloads, images: images, store: ReaderStorageStore(
            downloads: downloads,
            caches: [ReaderCacheLocation(id: "images", title: "图像", directory: images)], files: files
        ))
    }

    private func request(_ chapter: String = "1") -> ReaderDownloadRequest {
        ReaderDownloadRequest(source: "LOCAL", contentID: "9007199254740993", chapterID: chapter,
                              title: "作品", chapterTitle: "第 \(chapter) 章", payload: "opaque context")
    }

    private func completed(_ fixture: Fixture, request: ReaderDownloadRequest) async throws -> URL {
        try await fixture.store.record(request.storageMetadata)
        let folder = fixture.downloads.appendingPathComponent(request.storageMetadata.directoryID)
        try Data("offline picture".utf8).write(to: folder.appendingPathComponent("page.img"))
        try Data("{\"pages\":\"opaque\",\"images\":[]}".utf8).write(to: folder.appendingPathComponent("chapter.json"))
        return folder
    }

    func testInventoryNamesChaptersWithoutQueueRecordsAndIncludesPartialAndLegacyFiles() async throws {
        let fixture = try fixture()
        _ = try await completed(fixture, request: request())
        let partial = request("2")
        try await fixture.store.record(partial.storageMetadata)
        try Data("part".utf8).write(to: fixture.downloads.appendingPathComponent(
            partial.storageMetadata.directoryID + "/partial.json"
        ))
        let legacy = request("3")
        let legacyFolder = fixture.downloads.appendingPathComponent(legacy.storageMetadata.directoryID)
        try FileManager.default.createDirectory(at: legacyFolder, withIntermediateDirectories: true)
        try Data("novel".utf8).write(to: legacyFolder.appendingPathComponent("novel.json"))
        try Data(repeating: 1, count: 7).write(to: fixture.images.appendingPathComponent("cached.img"))
        let snapshot = try await fixture.store.snapshot(known: [legacy.storageMetadata])
        XCTAssertEqual(snapshot.items.count, 3)
        XCTAssertEqual(snapshot.cacheBytes, 7)
        XCTAssertGreaterThan(snapshot.offlineBytes, 7)
        XCTAssertEqual(snapshot.items.first { $0.id == request().storageMetadata.directoryID }?.metadata?.title, "作品")
        XCTAssertEqual(snapshot.items.first { $0.id == partial.storageMetadata.directoryID }?.status, "未完成的下载")
        XCTAssertEqual(snapshot.items.first { $0.id == legacy.storageMetadata.directoryID }?.metadata?.chapterTitle,
                       "第 3 章")
        let reopened = ReaderStorageStore(downloads: fixture.downloads, caches: [])
        let withoutQueue = try await reopened.snapshot(known: [])
        XCTAssertNotNil(withoutQueue.items.first { $0.id == request().storageMetadata.directoryID }?.metadata)
        XCTAssertNil(withoutQueue.items.first { $0.id == legacy.storageMetadata.directoryID }?.metadata)
    }

    func testClearOnlyRemovesCacheFilesAndNeverFollowsSymbolicLinks() async throws {
        let fixture = try fixture()
        let chapter = try await completed(fixture, request: request())
        let local = fixture.root.appendingPathComponent("local.cbz")
        let database = fixture.root.appendingPathComponent("library.db")
        let outside = fixture.root.appendingPathComponent("outside.img")
        for file in [local, database, outside] { try Data("keep".utf8).write(to: file) }
        try Data("cache".utf8).write(to: fixture.images.appendingPathComponent("page.img"))
        try FileManager.default.createSymbolicLink(at: fixture.images.appendingPathComponent("link.img"),
                                                  withDestinationURL: outside)
        let before = try await fixture.store.snapshot(known: [])
        XCTAssertEqual(before.cacheBytes, 5)
        try await fixture.store.clearCaches()
        for file in [local, database, outside, chapter.appendingPathComponent("page.img")] {
            XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        }
        let after = try await fixture.store.snapshot(known: [])
        XCTAssertEqual(after.cacheBytes, 0)
        XCTAssertEqual(after.offlineBytes, before.offlineBytes)
        let linkedRoot = fixture.root.appendingPathComponent("linked-cache")
        try FileManager.default.createSymbolicLink(at: linkedRoot, withDestinationURL: fixture.images)
        let unsafe = ReaderStorageStore(downloads: fixture.downloads, caches: [
            ReaderCacheLocation(id: "linked", title: "无效路径", directory: linkedRoot)
        ])
        do { try await unsafe.clearCaches(); XCTFail("A symlink root must be refused") }
        catch { XCTAssertEqual(error as? ReaderStorageError, .invalidPath) }
    }

    func testCacheBudgetEvictsOldestFilesAndLeavesOfflineData() async throws {
        let fixture = try fixture()
        let chapter = try await completed(fixture, request: request())
        for index in 1...3 {
            let file = fixture.images.appendingPathComponent("\(index).img")
            try Data(repeating: UInt8(index), count: 6).write(to: file)
            try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: Double(index))],
                                                   ofItemAtPath: file.path)
        }
        try await fixture.store.trimCaches(to: 7)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.images.appendingPathComponent("1.img").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.images.appendingPathComponent("2.img").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixture.images.appendingPathComponent("3.img").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: chapter.appendingPathComponent("page.img").path))
        let snapshot = try await fixture.store.snapshot(known: [])
        XCTAssertEqual(snapshot.cacheBytes, 6)
        try await fixture.store.trimCaches(to: 0)
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixture.images.appendingPathComponent("3.img").path))
    }

    func testInterruptedUncommittedRemovalRestoresTheWholeChapterOnReopen() async throws {
        let fixture = try fixture()
        let chapter = try await completed(fixture, request: request())
        let before = try await fixture.store.snapshot(known: [])
        _ = try await fixture.store.stageRemoval(request().storageMetadata.directoryID)
        XCTAssertFalse(FileManager.default.fileExists(atPath: chapter.path))
        let staged = try await fixture.store.snapshot(known: [])
        XCTAssertTrue(staged.items.isEmpty)
        XCTAssertGreaterThan(staged.pendingDeletionBytes, 0)
        let reopened = ReaderStorageStore(downloads: fixture.downloads, caches: [])
        try await reopened.recoverRemovals()
        XCTAssertEqual(try Data(contentsOf: chapter.appendingPathComponent("page.img")), Data("offline picture".utf8))
        let restored = try await reopened.snapshot(known: [])
        XCTAssertEqual(restored.offlineBytes, before.offlineBytes)
        XCTAssertEqual(restored.pendingDeletionBytes, 0)
    }

    func testCommittedRemovalIsRetriedWithoutRevivingTheChapterAfterCleanupFailure() async throws {
        let files = CleanupFailureFileManager()
        let fixture = try fixture(files: files)
        let chapter = try await completed(fixture, request: request())
        let token = try await fixture.store.stageRemoval(request().storageMetadata.directoryID)
        let staged = try XCTUnwrap(token)
        files.refuseCleanup = true
        try await fixture.store.commitRemoval(staged)
        let pending = try await fixture.store.snapshot(known: [])
        XCTAssertTrue(pending.items.isEmpty)
        XCTAssertNotNil(pending.cleanupFailure)
        XCTAssertGreaterThan(pending.pendingDeletionBytes, 0)
        files.refuseCleanup = false
        let reopened = ReaderStorageStore(downloads: fixture.downloads, caches: [], files: files)
        try await reopened.recoverRemovals()
        let recovered = try await reopened.snapshot(known: [])
        XCTAssertEqual(recovered.pendingDeletionBytes, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: chapter.path))
    }

    func testQueueWriteFailureRollsBackStagedFilesAndKeepsItsRequest() async throws {
        let fixture = try fixture()
        let request = request()
        let chapter = try await completed(fixture, request: request)
        let location = fixture.root.appendingPathComponent("queue.json")
        let queue = ReaderDownloadQueue(file: location)
        try await queue.enqueue([request])
        try FileManager.default.removeItem(at: location)
        try FileManager.default.createDirectory(at: location, withIntermediateDirectories: true)
        let token = try await fixture.store.stageRemoval(request.storageMetadata.directoryID)
        let staged = try XCTUnwrap(token)
        do {
            try await queue.withStorageMaintenance {
                try await queue.removeStoredDirectory(request.storageMetadata.directoryID) {
                    try await fixture.store.commitRemoval(staged)
                }
            }
            XCTFail("Queue persistence must fail")
        } catch { try await fixture.store.rollback(staged) }
        XCTAssertEqual(queue.entries.map(\.request), [request])
        XCTAssertTrue(FileManager.default.fileExists(atPath: chapter.appendingPathComponent("chapter.json").path))
        let snapshot = try await fixture.store.snapshot(known: [])
        XCTAssertEqual(snapshot.pendingDeletionBytes, 0)
    }

    func testSuccessfulRemovalPersistsQueueRemovalAndDoesNotTouchOtherChapters() async throws {
        let fixture = try fixture()
        let first = request()
        let second = request("2")
        let chapter = try await completed(fixture, request: first)
        let other = try await completed(fixture, request: second)
        let location = fixture.root.appendingPathComponent("queue.json")
        let queue = ReaderDownloadQueue(file: location)
        try await queue.enqueue([first, second])
        try await queue.withStorageMaintenance {
            let token = try await fixture.store.stageRemoval(first.storageMetadata.directoryID)
            let staged = try XCTUnwrap(token)
            try await queue.removeStoredDirectory(first.storageMetadata.directoryID) {
                try await fixture.store.commitRemoval(staged)
            }
        }
        let reopened = ReaderDownloadQueue(file: location)
        await reopened.prepare()
        XCTAssertEqual(reopened.entries.map(\.request), [second])
        XCTAssertFalse(FileManager.default.fileExists(atPath: chapter.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: other.appendingPathComponent("page.img").path))
    }

    func testAccessWaiterCancellationAndLateRequestsRespectMaintenance() async throws {
        let access = ReaderStorageAccess()
        let entered = await access.enter()
        XCTAssertTrue(entered)
        do { try await access.beginMaintenance(); XCTFail("Active files must remain protected") }
        catch { XCTAssertEqual(error as? ReaderStorageError, .busy) }
        await access.leave()
        try await access.beginMaintenance()
        let canceled = Task { await access.enter() }
        canceled.cancel()
        let granted = Task { await access.enter() }
        await Task.yield()
        await access.endMaintenance()
        let canceledValue = await canceled.value
        let grantedValue = await granted.value
        XCTAssertFalse(canceledValue)
        XCTAssertTrue(grantedValue)
        await access.leave()
        try await access.beginMaintenance()
        await access.endMaintenance()
    }

    func testRemovedWorkerStillBlocksMaintenanceUntilItsLateCallbackReturns() async throws {
        let fixture = try fixture()
        let queue = ReaderDownloadQueue(file: fixture.root.appendingPathComponent("queue.json"))
        let request = request()
        var unfinished: CheckedContinuation<Void, Never>?
        queue.attach { _, _ in await withCheckedContinuation { unfinished = $0 } }
        try await queue.enqueue([request])
        for _ in 0..<200 where unfinished == nil { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertNotNil(unfinished)
        try await queue.remove(request.id)
        XCTAssertTrue(queue.entries.isEmpty)
        do {
            try await queue.withStorageMaintenance { try await fixture.store.clearCaches() }
            XCTFail("A canceled native request still owns its worker slot")
        } catch { XCTAssertEqual(error as? ReaderStorageError, .busy) }
        unfinished?.resume()
        for _ in 0..<200 where queue.hasActiveWorker { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertFalse(queue.hasActiveWorker)
        try await queue.withStorageMaintenance { try await fixture.store.clearCaches() }
    }

    func testRemovalRejectsTraversalWithoutTouchingAnUnrelatedFolder() async throws {
        let fixture = try fixture()
        let outside = fixture.root.appendingPathComponent("local")
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        try Data("book".utf8).write(to: outside.appendingPathComponent("book.epub"))
        do { _ = try await fixture.store.stageRemoval("../local"); XCTFail("Traversal must not become a storage path") }
        catch { XCTAssertEqual(error as? ReaderStorageError, .invalidPath) }
        XCTAssertTrue(FileManager.default.fileExists(atPath: outside.appendingPathComponent("book.epub").path))
    }

    func testNovelTextAndIllustrationMetadataSurviveRemovingTheQueueRecord() async throws {
        let fixture = try fixture()
        let novel = ReaderDownloadRequest(source: "TSUNDOKU_9007199254740993", contentID: "-9223372036854775808",
                                          chapterID: "9223372036854775807", title: "小说", chapterTitle: "正文",
                                          payload: "opaque novel context", mediaKind: "NOVEL")
        let queue = ReaderDownloadQueue(file: fixture.root.appendingPathComponent("queue.json"))
        try await queue.enqueue([novel])
        try await fixture.store.record(novel.storageMetadata)
        let folder = fixture.downloads.appendingPathComponent(novel.storageMetadata.directoryID)
        try Data("chapter text".utf8).write(to: folder.appendingPathComponent("text.json"))
        try Data("{\"images\":{\"https://example.org/picture.png\":\"image-fixture\"}}".utf8)
            .write(to: folder.appendingPathComponent("novel.json"))
        let illustration = Data("offline illustration".utf8)
        try illustration.write(to: folder.appendingPathComponent("image-fixture"))
        try await queue.remove(novel.id)
        try await fixture.store.clearCaches()
        let reopened = ReaderStorageStore(downloads: fixture.downloads, caches: [])
        let snapshot = try await reopened.snapshot(known: [])
        XCTAssertEqual(snapshot.items.count, 1)
        XCTAssertEqual(snapshot.items.first?.metadata?.mediaKind, "NOVEL")
        XCTAssertEqual(snapshot.items.first?.metadata?.contentID, "-9223372036854775808")
        XCTAssertEqual(snapshot.items.first?.metadata?.chapterID, "9223372036854775807")
        XCTAssertGreaterThan(snapshot.offlineBytes, 0)
        XCTAssertEqual(try Data(contentsOf: folder.appendingPathComponent("text.json")), Data("chapter text".utf8))
        XCTAssertEqual(try Data(contentsOf: folder.appendingPathComponent("image-fixture")), illustration)
    }
}

private final class CleanupFailureFileManager: FileManager {
    var refuseCleanup = false
    override func removeItem(at url: URL) throws {
        if refuseCleanup, url.deletingLastPathComponent().lastPathComponent == ".trash", url.pathExtension != "json" {
            throw CocoaError(.fileWriteNoPermission)
        }
        try super.removeItem(at: url)
    }
}
