import Foundation
import XCTest

@MainActor
final class ReaderDownloadQueueTests: XCTestCase {
    private func request(_ chapter: String, source: String = "MIHON_9007199254740993") -> ReaderDownloadRequest {
        ReaderDownloadRequest(source: source, contentID: "-9223372036854775808", chapterID: chapter,
                              title: "漫画", chapterTitle: "章节 \(chapter)", payload: "opaque \(chapter)")
    }

    private func file() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory.appendingPathComponent("queue.json")
    }

    private func eventually(_ predicate: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async throws {
        for _ in 0..<200 {
            if predicate() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Queue did not reach the expected state", file: file, line: line)
    }

    func testPauseAndImmediateResumeWaitForUninterruptibleRequest() async throws {
        let queue = ReaderDownloadQueue(file: try file())
        let first = request("9223372036854775807")
        let second = request("2")
        var calls: [String] = []
        var unfinished: CheckedContinuation<Void, Never>?
        queue.attach { request, progress in
            calls.append(request.chapterID)
            if calls.count == 1 {
                try await progress(1, 2)
                // A JNI/network callback does not necessarily react to Swift Task cancellation.
                await withCheckedContinuation { unfinished = $0 }
            }
            try await progress(2, 2)
        }
        try await queue.enqueue([first, first, second])
        try await eventually { unfinished != nil }
        XCTAssertEqual(queue.entries.count, 2)
        try await queue.pause(first.id)
        try await queue.resume(first.id)
        await Task.yield()
        XCTAssertEqual(calls, [first.chapterID])
        XCTAssertTrue(queue.isFinishing(first.id))
        unfinished?.resume()
        try await eventually { queue.entries.allSatisfy { $0.state == .completed } }
        XCTAssertEqual(calls, [first.chapterID, first.chapterID, second.chapterID])
        XCTAssertEqual(queue.entries.first?.completedPages, 2)
    }

    func testRestartPreservesPausedProgressAndCompleteRequest() async throws {
        let location = try file()
        let queue = ReaderDownloadQueue(file: location)
        let original = request("9223372036854775807")
        var unfinished: CheckedContinuation<Void, Never>?
        queue.attach { _, progress in
            try await progress(1, 3)
            await withCheckedContinuation { unfinished = $0 }
        }
        try await queue.enqueue([original])
        try await eventually { unfinished != nil }
        let restarted = ReaderDownloadQueue(file: location)
        await restarted.prepare()
        XCTAssertEqual(restarted.entries.single?.request, original)
        XCTAssertEqual(restarted.entries.single?.state, .paused)
        XCTAssertEqual(restarted.entries.single?.completedPages, 1)
        XCTAssertEqual(restarted.entries.single?.totalPages, 3)
        try await queue.pause(original.id)
        unfinished?.resume()
        try await eventually { !queue.isFinishing(original.id) }
        restarted.attach { request, progress in
            XCTAssertEqual(request, original)
            try await progress(3, 3)
        }
        await Task.yield()
        XCTAssertEqual(restarted.entries.single?.state, .paused)
        try await restarted.resume(original.id)
        try await eventually { restarted.entries.single?.state == .completed }
        let final = ReaderDownloadQueue(file: location)
        await final.prepare()
        XCTAssertEqual(final.entries.single?.state, .completed)
        XCTAssertEqual(final.entries.single?.completedPages, 3)
    }

    func testFailureDoesNotBlockNextChapterAndRetryKeepsSingleEntry() async throws {
        let queue = ReaderDownloadQueue(file: try file())
        let first = request("1")
        let second = request("2")
        var fail = true
        var calls: [String] = []
        queue.attach { request, progress in
            calls.append(request.chapterID)
            if request.chapterID == "1" && fail { throw URLError(.timedOut) }
            try await progress(2, 2)
        }
        try await queue.enqueue([first, second])
        try await eventually { queue.entries.last?.state == .completed }
        XCTAssertEqual(queue.entries.first?.state, .failed)
        XCTAssertNotNil(queue.entries.first?.failure)
        fail = false
        try await queue.enqueue([first])
        try await eventually { queue.entries.first?.state == .completed }
        XCTAssertEqual(queue.entries.count, 2)
        XCTAssertEqual(calls, ["1", "2", "1"])
    }

    func testBackgroundPausesPendingAndLateRemovedWorkerCannotReinsertTask() async throws {
        let queue = ReaderDownloadQueue(file: try file())
        let first = request("1")
        let second = request("2")
        var unfinished: CheckedContinuation<Void, Never>?
        var calls: [String] = []
        queue.attach { request, progress in
            calls.append(request.chapterID)
            if request.chapterID == "1" { await withCheckedContinuation { unfinished = $0 } }
            try await progress(1, 1)
        }
        try await queue.enqueue([first, second])
        try await eventually { unfinished != nil }
        await queue.setForeground(false)
        XCTAssertTrue(queue.entries.allSatisfy { $0.state == .paused })
        try await queue.remove(first.id)
        await queue.setForeground(true)
        await Task.yield()
        XCTAssertEqual(calls, ["1"])
        unfinished?.resume()
        try await eventually { !queue.isFinishing(first.id) }
        XCTAssertEqual(queue.entries.count, 1)
        XCTAssertEqual(queue.entries.single?.state, .paused)
        try await queue.resume(second.id)
        try await eventually { queue.entries.single?.state == .completed }
        XCTAssertEqual(calls, ["1", "2"])
    }

    func testCorruptArchiveAndFailedPersistenceDoNotOverwriteUserState() async throws {
        let location = try file()
        let corrupt = Data("broken archive".utf8)
        try corrupt.write(to: location)
        let unreadable = ReaderDownloadQueue(file: location)
        await unreadable.prepare()
        XCTAssertNotNil(unreadable.error)
        do { try await unreadable.enqueue([request("1")]); XCTFail("A corrupt archive must be preserved") }
        catch { }
        XCTAssertEqual(try Data(contentsOf: location), corrupt)

        let blocker = location.deletingLastPathComponent().appendingPathComponent("blocker")
        try Data().write(to: blocker)
        let unwritable = ReaderDownloadQueue(file: blocker.appendingPathComponent("queue.json"))
        do { try await unwritable.enqueue([request("1")]); XCTFail("A failed write must not publish its entries") }
        catch { }
        XCTAssertTrue(unwritable.entries.isEmpty)
        XCTAssertNotEqual(request("1").id, request("1", source: "OTHER").id)
    }

    func testConcurrentMutationsUseTheLatestCommittedSnapshot() async throws {
        let location = try file()
        let queue = ReaderDownloadQueue(file: location)
        let requests = (0..<40).map { request(String($0)) }
        let enqueues = requests.map { item in Task { try await queue.enqueue([item]) } }
        for task in enqueues { try await task.value }
        XCTAssertEqual(Set(queue.entries.map(\.request.chapterID)), Set(requests.map(\.chapterID)))

        let removed = requests.prefix(20)
        let edits = removed.map { item in Task { try await queue.remove(item.id) } }
            + (40..<60).map { chapter in Task { try await queue.enqueue([request(String(chapter))]) } }
        for task in edits { try await task.value }
        let reopened = ReaderDownloadQueue(file: location)
        await reopened.prepare()
        XCTAssertEqual(Set(reopened.entries.map(\.request.chapterID)), Set((20..<60).map(String.init)))
    }

    func testFailedWriteDoesNotPoisonLaterWritesOrPublishUncommittedEntries() async throws {
        let location = try file()
        let queue = ReaderDownloadQueue(file: location)
        try await queue.enqueue([request("1")])
        try FileManager.default.removeItem(at: location)
        try FileManager.default.createDirectory(at: location, withIntermediateDirectories: true)
        do { try await queue.enqueue([request("2")]); XCTFail("Writing over a directory must fail") }
        catch { }
        XCTAssertEqual(queue.entries.map(\.request.chapterID), ["1"])
        try FileManager.default.removeItem(at: location)
        try await queue.enqueue([request("3")])
        let reopened = ReaderDownloadQueue(file: location)
        await reopened.prepare()
        XCTAssertEqual(reopened.entries.map(\.request.chapterID), ["1", "3"])
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
