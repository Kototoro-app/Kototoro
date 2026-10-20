import Combine
import CryptoKit
import Foundation

struct ReaderDownloadRequest: Codable, Equatable, Sendable {
    let source: String
    let contentID: String
    let chapterID: String
    let title: String
    let chapterTitle: String
    let payload: String
    /// "NOVEL" for novel chapters; absent in queues saved before novels could be downloaded (all manga).
    var mediaKind: String? = nil

    var isNovel: Bool { mediaKind == "NOVEL" }

    var storageMetadata: ReaderStorageMetadata {
        ReaderStorageMetadata(version: 1, source: source, contentID: contentID, chapterID: chapterID,
                              title: title, chapterTitle: chapterTitle, mediaKind: mediaKind ?? "MANGA")
    }

    var id: String {
        let identity = [source, contentID, chapterID].joined(separator: "\u{0}")
        return SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

struct ReaderDownloadEntry: Codable, Identifiable {
    enum State: String, Codable { case queued, running, paused, failed, completed }
    let request: ReaderDownloadRequest
    var state: State = .queued
    var completedPages = 0
    var totalPages = 0
    var failure: String?
    var id: String { request.id }
    /// Novel progress counts the text and its pictures, not pages.
    private var unit: String { request.isNovel ? "" : String(localized: " 页") }

    var status: String {
        switch state {
        case .queued: String(localized: "等待下载")
        case .running: String(localized: "正在下载 \(completedPages)/\(totalPages)\(unit)")
        case .paused: String(localized: "已暂停 · \(completedPages)/\(totalPages)\(unit)")
        case .failed: failure ?? String(localized: "下载失败")
        case .completed: request.isNovel ? String(localized: "已下载") : String(localized: "已下载 · \(totalPages) 页")
        }
    }
}

/// One app-owned worker. A canceled JNI call keeps its worker slot until it actually returns.
@MainActor
final class ReaderDownloadQueue: ObservableObject {
    typealias Progress = @MainActor (Int, Int) throws -> Void
    typealias Operation = @MainActor (ReaderDownloadRequest, @escaping Progress) async throws -> Void

    @Published private(set) var entries: [ReaderDownloadEntry] = [] {
        didSet {
            completedContentKeys = Set(entries.compactMap { $0.state == .completed ? "\($0.request.source)|\($0.request.contentID)" : nil })
        }
    }
    private(set) var completedContentKeys: Set<String> = []
    @Published private(set) var error: String?
    @Published private(set) var storageFailure: String?
    private let file: URL
    private var operation: Operation?
    private var worker: Task<Void, Never>?
    @Published private var activeID: String?
    private var foreground = true
    private var loadFailed = false
    private var maintainingStorage = false
    private static let maximumBytes = 32 * 1024 * 1024
    private static let maximumEntries = 500

    private struct Snapshot: Codable {
        let version: Int
        let entries: [ReaderDownloadEntry]
    }

    init(file: URL) {
        self.file = file
        do {
            if FileManager.default.fileExists(atPath: file.path) {
                let bytes = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard bytes <= Self.maximumBytes else { throw QueueError.invalidArchive }
                let snapshot = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: file))
                guard snapshot.version == 1, snapshot.entries.count <= Self.maximumEntries,
                      Set(snapshot.entries.map(\.id)).count == snapshot.entries.count,
                      snapshot.entries.allSatisfy({ $0.completedPages >= 0 && $0.totalPages >= $0.completedPages })
                else { throw QueueError.invalidArchive }
                // Restart never silently resumes a request that was interrupted by process termination.
                entries = snapshot.entries.map { row in
                    var entry = row
                    if entry.state == .running { entry.state = .paused }
                    return entry
                }
                try commit(entries)
            }
        } catch {
            self.error = error.localizedDescription
            loadFailed = true
        }
    }

    func attach(operation: @escaping Operation) {
        guard self.operation == nil else { return }
        self.operation = operation
        pump()
    }

    func enqueue(_ requests: [ReaderDownloadRequest]) throws {
        guard !maintainingStorage else { throw ReaderStorageError.busy }
        var next = entries
        for request in requests {
            if let index = next.firstIndex(where: { $0.id == request.id }) {
                if next[index].state == .paused || next[index].state == .failed || next[index].state == .completed {
                    next[index].state = .queued
                    next[index].failure = nil
                }
            } else { next.append(ReaderDownloadEntry(request: request)) }
        }
        try commit(next)
        pump()
    }

    func pause(_ id: String) throws {
        guard !maintainingStorage else { throw ReaderStorageError.busy }
        try update(id) { entry in
            if entry.state == .queued || entry.state == .running { entry.state = .paused }
        }
        if activeID == id { worker?.cancel() }
    }

    func resume(_ id: String) throws {
        guard !maintainingStorage else { throw ReaderStorageError.busy }
        try update(id) { entry in
            if entry.state == .paused || entry.state == .failed {
                entry.state = .queued
                entry.failure = nil
            }
        }
        pump()
    }

    /// Remove only the task record; downloaded files remain available for offline reading.
    func remove(_ id: String) throws {
        guard !maintainingStorage else { throw ReaderStorageError.busy }
        try commit(entries.filter { $0.id != id })
        if activeID == id { worker?.cancel() }
    }

    func isFinishing(_ id: String) -> Bool { activeID == id }

    var hasActiveWorker: Bool { worker != nil }

    func setStorageFailure(_ message: String?) { storageFailure = message; pump() }

    /// Block new queue writers while files are staged and queue persistence is committed or rolled back.
    func withStorageMaintenance<T>(_ body: () async throws -> T) async throws -> T {
        guard worker == nil, !maintainingStorage else { throw ReaderStorageError.busy }
        maintainingStorage = true
        defer {
            maintainingStorage = false
            if foreground { pump() } else { setForeground(false) }
        }
        return try await body()
    }

    func removeStoredDirectory(_ id: String, committing: () async throws -> Void) async throws {
        guard maintainingStorage else { throw ReaderStorageError.busy }
        let previous = entries
        try commit(entries.filter { $0.request.storageMetadata.directoryID != id })
        do { try await committing() }
        catch { try commit(previous); throw error }
    }

    func setForeground(_ value: Bool) {
        foreground = value
        if maintainingStorage { return }
        if value { pump(); return }
        do {
            var next = entries
            for index in next.indices where next[index].state == .running || next[index].state == .queued {
                next[index].state = .paused
            }
            try commit(next)
        } catch { self.error = error.localizedDescription }
        worker?.cancel()
    }

    private func update(_ id: String, change: (inout ReaderDownloadEntry) -> Void) throws {
        var next = entries
        guard let index = next.firstIndex(where: { $0.id == id }) else { return }
        change(&next[index])
        try commit(next)
    }

    private func commit(_ next: [ReaderDownloadEntry]) throws {
        guard !loadFailed else { throw QueueError.invalidArchive }
        guard next.count <= Self.maximumEntries else { throw QueueError.tooManyTasks }
        let data = try JSONEncoder().encode(Snapshot(version: 1, entries: next))
        guard data.count <= Self.maximumBytes else { throw QueueError.tooManyTasks }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: file, options: .atomic)
        entries = next
        error = nil
    }

    private func pump() {
        guard foreground, !maintainingStorage, storageFailure == nil, worker == nil, error == nil, let operation,
              let entry = entries.first(where: { $0.state == .queued }) else { return }
        do { try update(entry.id) { $0.state = .running } }
        catch { self.error = error.localizedDescription; return }
        activeID = entry.id
        worker = Task {
            defer {
                worker = nil; activeID = nil; pump()
                Task { await ReaderStorageAccess.shared.requestIdleAction() }
            }
            do {
                try Task.checkCancellation()
                try await operation(entry.request) { completed, total in
                    try Task.checkCancellation()
                    try self.update(entry.id) {
                        $0.completedPages = completed
                        $0.totalPages = total
                    }
                }
                try Task.checkCancellation()
                try update(entry.id) { $0.state = .completed; $0.failure = nil }
            } catch {
                let canceled = Task.isCancelled || error is CancellationError
                do {
                    try update(entry.id) {
                        // Pause → immediate resume must keep the newly queued request.
                        if $0.state == .running {
                            $0.state = canceled ? .paused : .failed
                            $0.failure = canceled ? nil : error.localizedDescription
                        }
                    }
                } catch { self.error = error.localizedDescription }
            }
        }
    }

    private enum QueueError: LocalizedError {
        case invalidArchive, tooManyTasks
        var errorDescription: String? {
            switch self {
            case .invalidArchive: String(localized: "下载队列无法读取，已保留原文件。")
            case .tooManyTasks: String(localized: "下载队列已满，请先移除已完成的任务。")
            }
        }
    }
}
