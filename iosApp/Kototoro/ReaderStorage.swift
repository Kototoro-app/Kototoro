import CryptoKit
import Foundation

enum ReaderStorageError: LocalizedError, Equatable {
    case busy, invalidPath, invalidMetadata, recoveryConflict
    var errorDescription: String? {
        switch self {
        case .busy: String(localized: "文件正在使用，请等待阅读或下载请求结束后再清理。")
        case .invalidPath: String(localized: "存储路径无法安全读取，原文件已保留。")
        case .invalidMetadata: String(localized: "离线文件信息无法读取，原文件已保留。")
        case .recoveryConflict: String(localized: "未完成的文件清理无法恢复，请先检查存储。")
        }
    }
}

/// Image requests keep their lease until the actual request/decode ends, including canceled JNI calls.
/// Maintenance refuses active users; requests arriving during maintenance wait without changing files.
actor ReaderStorageAccess {
    static let shared = ReaderStorageAccess()
    private var readers = 0
    private var maintenance = false
    private var waiting: [UUID: CheckedContinuation<Bool, Never>] = [:]
    private var idle: (@Sendable () async -> Void)?

    func setIdleAction(_ action: @escaping @Sendable () async -> Void) { idle = action }
    func requestIdleAction() {
        if readers == 0, !maintenance, let idle { Task { await idle() } }
    }

    func enter() async -> Bool {
        if Task.isCancelled { return false }
        if !maintenance { readers += 1; return true }
        let id = UUID()
        let granted = await withTaskCancellationHandler {
            await withCheckedContinuation { waiting[id] = $0 }
        } onCancel: { Task { await self.cancel(id) } }
        if granted && Task.isCancelled { leave(); return false }
        return granted
    }

    func leave() {
        readers -= 1
        assert(readers >= 0)
        requestIdleAction()
    }

    func beginMaintenance() throws {
        guard readers == 0, !maintenance else { throw ReaderStorageError.busy }
        maintenance = true
    }

    func endMaintenance() {
        assert(maintenance)
        maintenance = false
        let pending = Array(waiting.values)
        waiting.removeAll()
        readers += pending.count
        for continuation in pending { continuation.resume(returning: true) }
    }

    private func cancel(_ id: UUID) { waiting.removeValue(forKey: id)?.resume(returning: false) }
}

func withReaderStorage<T>(_ body: () async throws -> T) async throws -> T {
    guard await ReaderStorageAccess.shared.enter() else { throw CancellationError() }
    do {
        let value = try await body()
        await ReaderStorageAccess.shared.leave()
        return value
    } catch {
        await ReaderStorageAccess.shared.leave()
        throw error
    }
}

/// The manifest a finished download leaves in its directory, one per media kind. The listing lives in this file, so
/// the UI test target (which compiles it without the download engine) still agrees on the names.
enum DownloadManifests {
    static let chapter = "chapter.json"
    static let novel = "novel.json"
    static let video = "video.json"
    static let all = [chapter, novel, video]
}

struct ReaderStorageMetadata: Codable, Equatable, Sendable {
    let version: Int
    let source: String
    let contentID: String
    let chapterID: String
    let title: String
    let chapterTitle: String
    let mediaKind: String

    var directoryID: String { Self.directoryID(contentID: contentID, chapterID: chapterID) }
    static func directoryID(contentID: String, chapterID: String) -> String {
        SHA256.hash(data: Data((contentID + ":" + chapterID).utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// A finished offline chapter: what it is, the request it was downloaded with (the work's and chapter's source fields,
/// absent for downloads made before requests were kept) and when it finished.
struct ReaderDownloadRecord: Sendable {
    let metadata: ReaderStorageMetadata
    let payload: String?
    let completedAt: Date
}

struct ReaderStorageItem: Identifiable, Sendable {
    let id: String
    let metadata: ReaderStorageMetadata?
    let bytes: Int64
    let modified: Date?
    let status: String
}

struct ReaderCacheLocation: Identifiable, Sendable {
    let id: String
    let title: String
    let directory: URL
}

struct ReaderStorageSnapshot: Sendable {
    struct Cache: Identifiable, Sendable {
        let id: String
        let title: String
        let bytes: Int64
    }
    let items: [ReaderStorageItem]
    let caches: [Cache]
    let pendingDeletionBytes: Int64
    var cleanupFailure: String? = nil
    var offlineBytes: Int64 { items.reduce(0) { $0 + $1.bytes } }
    var cacheBytes: Int64 { caches.reduce(0) { $0 + $1.bytes } }
}

/// Platform-owned files only: offline chapters and regenerable caches. Library and local imports are separate owners.
/// A removal first stages the whole chapter next to its original folder, then commits after queue persistence.
actor ReaderStorageStore {
    struct Removal: Codable, Sendable {
        let version: Int
        let token: String
        let directoryID: String
        var committed: Bool
    }

    private struct FileRecord {
        let url: URL
        let bytes: Int64
        let modified: Date
    }

    private let downloads: URL
    private let caches: [ReaderCacheLocation]
    private let files: FileManager
    private var cleanupFailure: String?
    private static let keys: Set<URLResourceKey> = [
        .isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey, .contentModificationDateKey
    ]

    init(downloads: URL, caches: [ReaderCacheLocation], files: FileManager = .default) {
        self.downloads = downloads.standardizedFileURL
        self.caches = caches
        self.files = files
    }

    /// Keeps the download's request next to its files, so the work stays listed (and readable) once its task is gone.
    func keep(payload: String, for metadata: ReaderStorageMetadata) throws {
        let data = Data(payload.utf8)
        guard data.count <= Self.maximumRequestBytes else { throw ReaderStorageError.invalidMetadata }
        try data.write(to: try chapter(metadata.directoryID, create: true).appendingPathComponent(Self.requestName),
                       options: .atomic)
    }

    /// Every finished offline chapter with its request, newest first.
    func downloadRecords() throws -> [ReaderDownloadRecord] {
        var result: [ReaderDownloadRecord] = []
        for folder in try children(downloads) where Self.isDirectoryID(folder.lastPathComponent) {
            try Task.checkCancellation()
            let id = folder.lastPathComponent
            guard let metadata: ReaderStorageMetadata = try? read(folder.appendingPathComponent("download.json"),
                                                                 maximumBytes: 128 * 1024),
                  metadata.version == 1, metadata.directoryID == id else { continue }
            let manifest = DownloadManifests.all.map { folder.appendingPathComponent($0) }
                .first { files.fileExists(atPath: $0.path) }
            guard let manifest, let completed = try? manifest.resourceValues(forKeys: Self.keys).contentModificationDate
            else { continue }
            let request = folder.appendingPathComponent(Self.requestName)
            let size = (try? request.resourceValues(forKeys: Self.keys).fileSize) ?? 0
            let payload = size > 0 && size <= Self.maximumRequestBytes
                ? (try? Data(contentsOf: request)).map { String(decoding: $0, as: UTF8.self) } : nil
            result.append(ReaderDownloadRecord(metadata: metadata, payload: payload, completedAt: completed))
        }
        return result.sorted { $0.completedAt > $1.completedAt }
    }

    private static let requestName = "request.json"
    private static let maximumRequestBytes = 1024 * 1024

    func record(_ metadata: ReaderStorageMetadata) throws {
        guard metadata.version == 1, !metadata.source.isEmpty, !metadata.contentID.isEmpty,
              !metadata.chapterID.isEmpty else { throw ReaderStorageError.invalidMetadata }
        let folder = try chapter(metadata.directoryID, create: true)
        let target = folder.appendingPathComponent("download.json")
        if files.fileExists(atPath: target.path) {
            let old: ReaderStorageMetadata = try read(target, maximumBytes: 128 * 1024)
            guard old.source == metadata.source, old.contentID == metadata.contentID,
                  old.chapterID == metadata.chapterID else { throw ReaderStorageError.invalidMetadata }
        }
        let data = try JSONEncoder().encode(metadata)
        guard data.count <= 128 * 1024 else { throw ReaderStorageError.invalidMetadata }
        try data.write(to: target, options: .atomic)
    }

    func snapshot(known: [ReaderStorageMetadata]) throws -> ReaderStorageSnapshot {
        try Task.checkCancellation()
        let known = Dictionary(known.map { ($0.directoryID, $0) }, uniquingKeysWith: { first, _ in first })
        var items: [ReaderStorageItem] = []
        for folder in try children(downloads) where Self.isDirectoryID(folder.lastPathComponent) {
            let values = try folder.resourceValues(forKeys: Self.keys)
            guard values.isDirectory == true, values.isSymbolicLink != true else { continue }
            let id = folder.lastPathComponent
            let stored: ReaderStorageMetadata? = try? read(folder.appendingPathComponent("download.json"),
                                                          maximumBytes: 128 * 1024)
            let metadata = stored.flatMap { $0.version == 1 && $0.directoryID == id ? $0 : nil } ?? known[id]
            let completed = DownloadManifests.all.contains {
                files.fileExists(atPath: folder.appendingPathComponent($0).path)
            }
            let status = completed ? String(localized: "已下载") : String(localized: "未完成的下载")
            items.append(ReaderStorageItem(
                id: id, metadata: metadata, bytes: try records(folder).reduce(0) { $0 + $1.bytes },
                modified: values.contentModificationDate, status: status
            ))
        }
        let usage = try caches.map { cache in
            ReaderStorageSnapshot.Cache(id: cache.id, title: cache.title,
                                        bytes: try records(cache.directory).reduce(0) { $0 + $1.bytes })
        }
        let pending = try records(downloads.appendingPathComponent(".trash")).reduce(0) { $0 + $1.bytes }
        return ReaderStorageSnapshot(
            items: items.sorted { ($0.modified ?? .distantPast) > ($1.modified ?? .distantPast) },
            caches: usage, pendingDeletionBytes: pending, cleanupFailure: cleanupFailure
        )
    }

    func clearCaches() throws {
        for location in caches {
            for record in try records(location.directory) {
                try Task.checkCancellation()
                try files.removeItem(at: record.url)
            }
        }
    }

    /// A soft byte budget: leases protect reading/downloads; the caller retries when their last user finishes.
    func trimCaches(to limit: Int64) throws {
        guard limit > 0 else { return }
        let entries = try caches.flatMap { try records($0.directory) }.sorted { $0.modified < $1.modified }
        var total = entries.reduce(Int64(0)) { $0 + $1.bytes }
        for record in entries where total > limit {
            try Task.checkCancellation()
            try files.removeItem(at: record.url)
            total -= record.bytes
        }
    }

    func stageRemoval(_ id: String) throws -> Removal? {
        let original = try chapter(id)
        guard files.fileExists(atPath: original.path) else { return nil }
        let token = UUID().uuidString
        let removal = Removal(version: 1, token: token, directoryID: id, committed: false)
        let trash = try trashDirectory(create: true)
        try writeJournal(removal)
        do {
            try files.moveItem(at: original, to: trash.appendingPathComponent(token, isDirectory: true))
        } catch {
            try? files.removeItem(at: trash.appendingPathComponent(token + ".json"))
            throw error
        }
        return removal
    }

    func rollback(_ removal: Removal) throws {
        let removal = try validatedRemoval(removal)
        guard !removal.committed else { throw ReaderStorageError.recoveryConflict }
        let trash = try trashDirectory()
        let staged = trash.appendingPathComponent(removal.token, isDirectory: true)
        let original = downloads.appendingPathComponent(removal.directoryID, isDirectory: true)
        if files.fileExists(atPath: staged.path) {
            guard !files.fileExists(atPath: original.path) else { throw ReaderStorageError.recoveryConflict }
            try files.moveItem(at: staged, to: original)
        }
        try files.removeItem(at: trash.appendingPathComponent(removal.token + ".json"))
    }

    /// Durable commit precedes cleanup. Failed cleanup is retried on launch, never restored as a chapter.
    func commitRemoval(_ removal: Removal) throws {
        var removal = try validatedRemoval(removal)
        removal.committed = true
        try writeJournal(removal)
        do { try purge(removal); cleanupFailure = nil }
        catch { cleanupFailure = error.localizedDescription }
    }

    func recoverRemovals() throws {
        for file in try children(try trashDirectory()) where file.pathExtension == "json" {
            let record: Removal = try read(file, maximumBytes: 4096)
            let removal = try validatedRemoval(record)
            guard file.lastPathComponent == removal.token + ".json" else { throw ReaderStorageError.invalidPath }
            if removal.committed {
                do { try purge(removal); cleanupFailure = nil }
                catch { cleanupFailure = error.localizedDescription }
            } else { try rollback(removal) }
        }
    }

    private func purge(_ removal: Removal) throws {
        let trash = try trashDirectory()
        let staged = trash.appendingPathComponent(removal.token, isDirectory: true)
        if files.fileExists(atPath: staged.path) { try files.removeItem(at: staged) }
        try files.removeItem(at: trash.appendingPathComponent(removal.token + ".json"))
    }

    private func validatedRemoval(_ removal: Removal) throws -> Removal {
        guard removal.version == 1, UUID(uuidString: removal.token)?.uuidString == removal.token,
              Self.isDirectoryID(removal.directoryID) else { throw ReaderStorageError.invalidMetadata }
        let current: Removal = try read(try trashDirectory().appendingPathComponent(removal.token + ".json"),
                                         maximumBytes: 4096)
        guard current.version == 1, current.token == removal.token, current.directoryID == removal.directoryID
        else { throw ReaderStorageError.invalidMetadata }
        return current
    }

    private func writeJournal(_ removal: Removal) throws {
        let directory = try trashDirectory(create: true)
        try JSONEncoder().encode(removal).write(to: directory.appendingPathComponent(removal.token + ".json"),
                                               options: .atomic)
    }

    private func chapter(_ id: String, create: Bool = false) throws -> URL {
        guard Self.isDirectoryID(id) else { throw ReaderStorageError.invalidPath }
        try validateDirectory(downloads, create: create)
        let directory = downloads.appendingPathComponent(id, isDirectory: true)
        try validateDirectory(directory, create: create)
        return directory
    }

    private func trashDirectory(create: Bool = false) throws -> URL {
        try validateDirectory(downloads, create: create)
        let directory = downloads.appendingPathComponent(".trash", isDirectory: true)
        try validateDirectory(directory, create: create)
        return directory
    }

    private func validateDirectory(_ directory: URL, create: Bool = false) throws {
        if !files.fileExists(atPath: directory.path) {
            if create { try files.createDirectory(at: directory, withIntermediateDirectories: true) }
            else { return }
        }
        let values = try directory.resourceValues(forKeys: Self.keys)
        guard values.isDirectory == true, values.isSymbolicLink != true else { throw ReaderStorageError.invalidPath }
    }

    private func children(_ directory: URL) throws -> [URL] {
        try validateDirectory(directory)
        guard files.fileExists(atPath: directory.path) else { return [] }
        return try files.contentsOfDirectory(at: directory, includingPropertiesForKeys: Array(Self.keys))
    }

    private func records(_ directory: URL) throws -> [FileRecord] {
        try validateDirectory(directory)
        guard files.fileExists(atPath: directory.path) else { return [] }
        var failure: Error?
        guard let enumerator = files.enumerator(at: directory, includingPropertiesForKeys: Array(Self.keys),
                                                errorHandler: { _, error in failure = error; return false })
        else { throw ReaderStorageError.invalidPath }
        var result: [FileRecord] = []
        for case let file as URL in enumerator {
            try Task.checkCancellation()
            let values = try file.resourceValues(forKeys: Self.keys)
            guard values.isSymbolicLink != true else { enumerator.skipDescendants(); continue }
            if values.isRegularFile == true {
                result.append(FileRecord(url: file, bytes: Int64(values.fileSize ?? 0),
                                         modified: values.contentModificationDate ?? .distantPast))
            }
        }
        if let failure { throw failure }
        return result
    }

    private func read<T: Decodable>(_ file: URL, maximumBytes: Int) throws -> T {
        let values = try file.resourceValues(forKeys: Self.keys)
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              let size = values.fileSize, size > 0, size <= maximumBytes else {
            throw ReaderStorageError.invalidMetadata
        }
        return try JSONDecoder().decode(T.self, from: Data(contentsOf: file))
    }

    private static func isDirectoryID(_ id: String) -> Bool {
        id.count == 64 && id.allSatisfy { "0123456789abcdef".contains($0) }
    }
}
