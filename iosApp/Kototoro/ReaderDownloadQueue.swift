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
    /// "NOVEL" for novel chapters, "VIDEO" for episodes; absent in queues saved before novels could be downloaded.
    var mediaKind: String? = nil
    /// The stream an episode is taken from; nil downloads the source's preferred one.
    var streamURL: String? = nil

    var isNovel: Bool { mediaKind == "NOVEL" }
    var isVideo: Bool { mediaKind == "VIDEO" }

    var storageMetadata: ReaderStorageMetadata {
        ReaderStorageMetadata(version: 1, source: source, contentID: contentID, chapterID: chapterID,
                              title: title, chapterTitle: chapterTitle, mediaKind: mediaKind ?? "MANGA")
    }

    var id: String {
        let identity = [source, contentID, chapterID].joined(separator: "\u{0}")
        return SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

struct ReaderDownloadEntry: Codable, Identifiable, Sendable {
    enum State: String, Codable, Sendable { case queued, running, paused, failed, completed }
    let request: ReaderDownloadRequest
    var state: State = .queued
    var completedPages = 0
    var totalPages = 0
    var failure: String?
    var id: String { request.id }
    /// Novel progress counts the text and its pictures, not pages; an episode counts percent.
    private var unit: String { request.isNovel ? "" : String(localized: " 页") }

    var status: String {
        if request.isVideo { return videoStatus }
        switch state {
        case .queued: return String(localized: "等待下载")
        case .running: return String(localized: "正在下载 \(completedPages)/\(totalPages)\(unit)")
        case .paused: return String(localized: "已暂停 · \(completedPages)/\(totalPages)\(unit)")
        case .failed: return failure ?? String(localized: "下载失败")
        case .completed: return request.isNovel ? String(localized: "已下载")
            : String(localized: "已下载 · \(totalPages) 页")
        }
    }

    /// An episode has no pages to count: its progress is the share of the file already on disk.
    private var videoStatus: String {
        switch state {
        case .queued: return String(localized: "等待下载")
        case .running: return String(localized: "正在下载 \(completedPages)%")
        case .paused: return String(localized: "已暂停 · \(completedPages)%")
        case .failed: return failure ?? String(localized: "下载失败")
        case .completed: return String(localized: "已下载")
        }
    }
}

/// The finished-download marks alone. The queue publishes on every downloaded page; lists that draw only the
/// "downloaded" badge observe this instead, so a running download does not re-evaluate every visible card.
@MainActor
final class DownloadedContent: ObservableObject {
    @Published private(set) var keys: Set<String> = []
    /// Bumped whenever [keys] changes, for caches keyed by the set.
    private(set) var revision = 0

    fileprivate func update(_ next: Set<String>) {
        guard next != keys else { return }
        keys = next
        revision += 1
    }
}

/// What keeps the queue running while the app is away from the screen (the app's continued processing), from the
/// first download the queue starts until it runs dry.
struct ReaderDownloadLease {
    let id = UUID()
    /// Overall progress of this drain and the line under the title.
    let progress: @MainActor (_ completed: Int, _ total: Int, _ subtitle: String) -> Void
    let end: @MainActor () -> Void
}

/// One app-owned worker. A canceled JNI call keeps its worker slot until it actually returns.
@MainActor
final class ReaderDownloadQueue: ObservableObject {
    typealias Progress = @MainActor (Int, Int) async throws -> Void
    typealias Operation = @MainActor (ReaderDownloadRequest, @escaping Progress) async throws -> Void
    /// Starts a lease: [userInitiated] when a tap queued the work. [ended] is called if the system takes the time
    /// back before the queue runs dry; [resumable] is false when the user stopped it from the system's progress.
    typealias LeaseFactory = @MainActor (_ userInitiated: Bool,
                                         _ ended: @escaping @MainActor (_ resumable: Bool) -> Void) -> ReaderDownloadLease

    @Published private(set) var entries: [ReaderDownloadEntry] = [] {
        didSet {
            downloaded.update(Set(entries.compactMap {
                $0.state == .completed ? "\($0.request.source)|\($0.request.contentID)" : nil
            }))
        }
    }
    /// Works with a finished download; observed on its own by the work lists, which only show the mark.
    let downloaded = DownloadedContent()
    var completedContentKeys: Set<String> { downloaded.keys }
    @Published private(set) var error: String?
    @Published private(set) var storageFailure: String?
    @Published private(set) var isLoading = true
    private let store: ReaderDownloadQueueStore
    private var pendingWrite: Task<Void, Error>?
    private var prepared = false
    private var operation: Operation?
    private var worker: Task<Void, Never>?
    @Published private var activeID: String?
    private var foreground = true
    private var loadFailed = false
    private var maintainingStorage = false
    /// Without one (tests), leaving the screen pauses the queue as before.
    var leaseFactory: LeaseFactory?
    /// The user paused, removed or stopped this entry: work its operation handed to the system (an episode's
    /// background transfer) must stop too. Running out of background time does not call it; that work goes on.
    var onUserStop: (@MainActor (ReaderDownloadRequest) -> Void)?
    private var lease: ReaderDownloadLease?
    private var leaseUserInitiated = false
    /// A tap queued or resumed work since the last lease began.
    private var userStarted = false
    /// Entries finished during the current lease, for its overall progress.
    private var leaseFinished = 0
    /// Paused because the background time ran out; queued again when the app returns.
    private var pausedForTime: Set<String> = []
    /// Downloads go on while the app is on screen or a lease still covers them.
    private var canRun: Bool { foreground || lease != nil }

    init(file: URL) {
        store = ReaderDownloadQueueStore(file: file)
    }

    /// Loading is explicit so SwiftUI initialization never reads or rewrites the queue on the main thread.
    func prepare() async {
        do { try await write() }
        catch { self.error = error.localizedDescription }
    }

    func attach(operation: @escaping Operation) {
        guard self.operation == nil else { return }
        self.operation = operation
        Task { await prepare(); pump() }
    }

    func enqueue(_ requests: [ReaderDownloadRequest]) async throws {
        guard !maintainingStorage else { throw ReaderStorageError.busy }
        try await write { next in
            for request in requests {
                if let index = next.firstIndex(where: { $0.id == request.id }) {
                    if next[index].state == .paused || next[index].state == .failed || next[index].state == .completed {
                        next[index].state = .queued
                        next[index].failure = nil
                    }
                } else { next.append(ReaderDownloadEntry(request: request)) }
            }
        }
        userStarted = true
        pump()
    }

    func pause(_ id: String) async throws {
        guard !maintainingStorage else { throw ReaderStorageError.busy }
        let running = activeID == id ? worker : nil
        try await update(id) { entry in
            if entry.state == .queued || entry.state == .running { entry.state = .paused }
        }
        running?.cancel()
        stopped(id)
    }

    func resume(_ id: String) async throws {
        guard !maintainingStorage else { throw ReaderStorageError.busy }
        try await update(id) { entry in
            if entry.state == .paused || entry.state == .failed {
                entry.state = .queued
                entry.failure = nil
            }
        }
        userStarted = true
        pump()
    }

    /// Remove only the task record; downloaded files remain available for offline reading.
    func remove(_ id: String) async throws {
        guard !maintainingStorage else { throw ReaderStorageError.busy }
        let running = activeID == id ? worker : nil
        let request = entries.first { $0.id == id }?.request
        try await write { $0.removeAll { $0.id == id } }
        running?.cancel()
        if let request { onUserStop?(request) }
    }

    private func stopped(_ id: String) {
        if let request = entries.first(where: { $0.id == id })?.request { onUserStop?(request) }
    }

    func isFinishing(_ id: String) -> Bool { activeID == id }

    var hasActiveWorker: Bool { worker != nil }

    func setStorageFailure(_ message: String?) { storageFailure = message; pump() }

    /// Block new queue writers while files are staged and queue persistence is committed or rolled back.
    func withStorageMaintenance<T>(_ body: () async throws -> T) async throws -> T {
        guard worker == nil, !maintainingStorage else { throw ReaderStorageError.busy }
        maintainingStorage = true
        // Earlier accepted writes finish before the file/queue transaction takes its snapshot.
        await prepare()
        let result: Result<T, Error>
        do { result = .success(try await body()) }
        catch { result = .failure(error) }
        maintainingStorage = false
        if foreground { pump() } else { await setForeground(false) }
        return try result.get()
    }

    func removeStoredDirectory(_ id: String, committing: () async throws -> Void) async throws {
        guard maintainingStorage else { throw ReaderStorageError.busy }
        let previous = entries
        try await write { $0.removeAll { $0.request.storageMetadata.directoryID == id } }
        do { try await committing() }
        catch { try await write { $0 = previous }; throw error }
    }

    func setForeground(_ value: Bool) async {
        foreground = value
        if value {
            let resumable = pausedForTime
            pausedForTime = []
            if !resumable.isEmpty && !maintainingStorage {
                do {
                    try await write { next in
                        for index in next.indices where resumable.contains(next[index].id) && next[index].state == .paused {
                            next[index].state = .queued
                        }
                    }
                } catch { self.error = error.localizedDescription }
            }
            if !maintainingStorage { pump() }
            return
        }
        // A lease keeps the running download going; without one the queue stops as the app leaves.
        if lease != nil { return }
        await stopAll(resumable: false)
    }

    /// The lease ended before the queue ran dry: stop the download in flight and pause what is left. Entries paused
    /// because the background time ran out are queued again when the app returns.
    private func leaseEnded(resumable: Bool) {
        lease = nil
        leaseFinished = 0
        guard !foreground else { pump(); return }
        Task { await stopAll(resumable: resumable) }
    }

    private func stopAll(resumable: Bool) async {
        worker?.cancel()
        if !resumable, let activeID { stopped(activeID) }
        if maintainingStorage { return }
        do {
            try await write { next in
                guard !self.foreground else { return }
                for index in next.indices where next[index].state == .running || next[index].state == .queued {
                    next[index].state = .paused
                    if resumable { self.pausedForTime.insert(next[index].id) }
                }
            }
        } catch { self.error = error.localizedDescription }
    }

    /// A lease covers the queue from its first download until it runs dry. One started by the app itself (resuming
    /// a saved queue on launch) gives way to one a tap asks for, which the system may let run longer.
    private func beginLease() {
        guard let leaseFactory else { return }
        if lease != nil && (leaseUserInitiated || !userStarted) { return }
        guard foreground else { return }
        lease?.end()
        let userInitiated = userStarted
        userStarted = false
        leaseUserInitiated = userInitiated
        leaseFinished = 0
        var current: ReaderDownloadLease?
        current = leaseFactory(userInitiated) { [weak self] resumable in
            // A lease replaced since must not stop the queue.
            guard let self, let current, self.lease?.id == current.id else { return }
            self.leaseEnded(resumable: resumable)
        }
        lease = current
    }

    private func endLease() {
        lease?.end()
        lease = nil
        leaseFinished = 0
    }

    /// The drain so far in hundredths of an entry; the subtitle names the chapter in progress.
    private func reportLease(_ request: ReaderDownloadRequest, completed: Int, total: Int) {
        guard let lease else { return }
        let remaining = entries.count(where: { $0.state == .queued || $0.state == .running })
        let all = leaseFinished + max(remaining, 1)
        let share = total > 0 ? min(completed * 100 / total, 100) : 0
        lease.progress(leaseFinished * 100 + share, all * 100,
                       "\(leaseFinished + 1)/\(all) · \(request.title) \(request.chapterTitle)")
    }

    private func update(_ id: String, change: @escaping (inout ReaderDownloadEntry) -> Void) async throws {
        try await write { next in
            guard let index = next.firstIndex(where: { $0.id == id }) else { return }
            change(&next[index])
        }
    }

    /// The chain covers computation, persistence and publication. Actor reentrancy cannot lose a newer mutation.
    private func write(_ change: (@MainActor (inout [ReaderDownloadEntry]) -> Void)? = nil) async throws {
        let previous = pendingWrite
        let task = Task { @MainActor [self] in
            _ = try? await previous?.value
            guard !loadFailed else { throw QueueError.invalidArchive }
            if !prepared {
                do {
                    entries = try await store.restore()
                    prepared = true
                    isLoading = false
                } catch {
                    loadFailed = true
                    isLoading = false
                    throw error
                }
            }
            guard let change else { return }
            var next = entries
            change(&next)
            try await store.commit(next)
            entries = next
            error = nil
        }
        pendingWrite = task
        try await task.value
    }

    private func pump() {
        guard worker == nil else { return }
        guard prepared, canRun, !maintainingStorage, storageFailure == nil, error == nil, let operation,
              let entry = entries.first(where: { $0.state == .queued }) else {
            endLease()
            return
        }
        beginLease()
        activeID = entry.id
        worker = Task {
            defer {
                worker = nil; activeID = nil
                leaseFinished += 1
                pump()
                Task { await ReaderStorageAccess.shared.requestIdleAction() }
            }
            do {
                try Task.checkCancellation()
                try await update(entry.id) {
                    if $0.state == .queued { $0.state = .running }
                }
                try Task.checkCancellation()
                guard canRun, entries.first(where: { $0.id == entry.id })?.state == .running else { return }
                reportLease(entry.request, completed: 0, total: 0)
                try await operation(entry.request) { completed, total in
                    try Task.checkCancellation()
                    try await self.update(entry.id) {
                        $0.completedPages = completed
                        $0.totalPages = total
                    }
                    self.reportLease(entry.request, completed: completed, total: total)
                }
                try Task.checkCancellation()
                try await update(entry.id) {
                    if $0.state == .running { $0.state = .completed; $0.failure = nil }
                }
            } catch {
                let canceled = Task.isCancelled || error is CancellationError
                do {
                    try await update(entry.id) {
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
}

/// Every method completes its synchronous file work without suspending on this background actor.
private actor ReaderDownloadQueueStore {
    private let file: URL
    private static let maximumBytes = 32 * 1024 * 1024
    private static let maximumEntries = 500
    private struct Snapshot: Codable {
        let version: Int
        let entries: [ReaderDownloadEntry]
    }

    init(file: URL) { self.file = file }

    func restore() throws -> [ReaderDownloadEntry] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [] }
        let bytes = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard bytes <= Self.maximumBytes else { throw QueueError.invalidArchive }
        let snapshot = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: file))
        guard snapshot.version == 1, snapshot.entries.count <= Self.maximumEntries,
              Set(snapshot.entries.map(\.id)).count == snapshot.entries.count,
              snapshot.entries.allSatisfy({ $0.completedPages >= 0 && $0.totalPages >= $0.completedPages })
        else { throw QueueError.invalidArchive }
        let entries = snapshot.entries.map { row in
            var entry = row
            if entry.state == .running { entry.state = .paused }
            return entry
        }
        try commit(entries)
        return entries
    }

    func commit(_ entries: [ReaderDownloadEntry]) throws {
        guard entries.count <= Self.maximumEntries else { throw QueueError.tooManyTasks }
        let data = try JSONEncoder().encode(Snapshot(version: 1, entries: entries))
        guard data.count <= Self.maximumBytes else { throw QueueError.tooManyTasks }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: file, options: .atomic)
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
