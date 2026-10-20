import Combine
import SwiftUI

enum ReaderStorageBudget {
    static let key = "reader.storage.cache_limit_mb"
    static let defaultMB = 512
    static var bytes: Int64 {
        let value = UserDefaults.standard.object(forKey: key) as? Int ?? defaultMB
        return Int64([0, 256, 512, 1024, 2048].contains(value) ? value : defaultMB) * 1024 * 1024
    }
}

@MainActor
final class ReaderStorageModel: ObservableObject {
    @Published private(set) var snapshot: ReaderStorageSnapshot?
    @Published private(set) var error: String?
    @Published private(set) var isWorking = false
    private(set) var store: ReaderStorageStore?
    private weak var queue: ReaderDownloadQueue?

    private static let backingStore: Result<ReaderStorageStore, Error> = Result {
        let data = try NativeSourceTransport.dataDirectory
        return ReaderStorageStore(downloads: data.appendingPathComponent("downloads"), caches: [
            ReaderCacheLocation(id: "images", title: String(localized: "页面与大图"), directory: data.appendingPathComponent("images")),
            ReaderCacheLocation(id: "covers", title: String(localized: "封面缓存"),
                                directory: URL.cachesDirectory.appendingPathComponent("covers")),
            ReaderCacheLocation(id: "source-icons", title: String(localized: "来源图标"),
                                directory: URL.cachesDirectory.appendingPathComponent("source-icons")),
            ReaderCacheLocation(id: "extension-icons", title: String(localized: "扩展图标"),
                                directory: URL.cachesDirectory.appendingPathComponent("extension-icons"))
        ])
    }

    static func downloadStore() throws -> ReaderStorageStore { try backingStore.get() }

    init() {
        do {
            store = try Self.downloadStore()
        } catch { self.error = error.localizedDescription }
    }

    /// Run before attaching the worker: uncommitted removals are restored before a queued chapter can write again.
    func prepare(queue: ReaderDownloadQueue) async {
        self.queue = queue
        guard let store else { queue.setStorageFailure(error); return }
        do {
            try await maintenance { try await store.recoverRemovals() }
            queue.setStorageFailure(nil)
        } catch {
            self.error = error.localizedDescription
            queue.setStorageFailure(error.localizedDescription)
        }
        await ReaderStorageAccess.shared.setIdleAction { [weak self] in await self?.trimAutomatically() }
    }

    func refresh() async {
        guard let store else { return }
        do {
            snapshot = try await store.snapshot(known: queue?.entries.map { $0.request.storageMetadata } ?? [])
            error = nil
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }

    func retryRecovery() async {
        guard let queue else { return }
        await prepare(queue: queue)
        if queue.storageFailure == nil { await refresh() }
    }

    func remove(_ item: ReaderStorageItem) async {
        guard !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        do {
            try await removeDirectory(item.id)
            await refresh()
        } catch {
            await refresh()
            self.error = error.localizedDescription
        }
    }

    func removeDirectory(_ id: String) async throws {
        guard let store, let queue, !ReaderPresence.shared.protects(id) else { throw ReaderStorageError.busy }
        try await maintenance(allowReading: true) {
            guard !ReaderPresence.shared.protects(id) else { throw ReaderStorageError.busy }
            let staged = try await store.stageRemoval(id)
            do {
                guard !ReaderPresence.shared.protects(id) else { throw ReaderStorageError.busy }
                try await queue.removeStoredDirectory(id) {
                    if let staged { try await store.commitRemoval(staged) }
                }
            } catch {
                if let staged { try await store.rollback(staged) }
                throw error
            }
            await ReaderDownloadStore.shared.invalidate(
                try NativeSourceTransport.dataDirectory.appendingPathComponent("downloads/\(id)")
            )
        }
    }

    func clearCaches() async {
        guard let store, !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        do {
            try await maintenance {
                try await store.clearCaches()
                CoverCache.shared.clear()
                SourceIconStore.shared.clearMemory()
                await ReaderTileBaseCache.shared.clear()
            }
            await refresh()
        } catch {
            await refresh()
            self.error = error.localizedDescription
        }
    }

    func trimAutomatically() async {
        guard let store, !isWorking, !ReaderPresence.shared.isReading, ReaderStorageBudget.bytes > 0,
              queue?.storageFailure == nil else { return }
        do { try await maintenance { try await store.trimCaches(to: ReaderStorageBudget.bytes) } }
        catch ReaderStorageError.busy { return }
        catch { self.error = error.localizedDescription }
    }

    private func maintenance<T>(allowReading: Bool = false, _ body: () async throws -> T) async throws -> T {
        guard allowReading || !ReaderPresence.shared.isReading, let queue else { throw ReaderStorageError.busy }
        return try await queue.withStorageMaintenance {
            try await ReaderStorageAccess.shared.beginMaintenance()
            do {
                let value = try await body()
                await ReaderStorageAccess.shared.endMaintenance()
                return value
            } catch {
                await ReaderStorageAccess.shared.endMaintenance()
                throw error
            }
        }
    }
}

struct ReaderStorageView: View {
    @EnvironmentObject private var model: ReaderStorageModel
    @EnvironmentObject private var queue: ReaderDownloadQueue
    @State private var selected: ReaderStorageItem?
    @State private var confirmingCacheClear = false
    @AppStorage(ReaderStorageBudget.key) private var limit = ReaderStorageBudget.defaultMB

    private var busy: Bool { model.isWorking || queue.hasActiveWorker }

    var body: some View {
        List {
            if let error = queue.storageFailure ?? model.error {
                Section {
                    Text(error).foregroundStyle(.red).accessibilityIdentifier("storage.error")
                    if queue.storageFailure != nil {
                        Button("重试存储恢复") { Task { await model.retryRecovery() } }
                    }
                }
            }
            if let snapshot = model.snapshot {
                Section("占用") {
                    LabeledContent("离线章节", value: bytes(snapshot.offlineBytes))
                        .accessibilityElement(children: .combine).accessibilityValue("\(snapshot.offlineBytes) 字节")
                        .accessibilityIdentifier("storage.offlineBytes")
                    LabeledContent("图像缓存", value: bytes(snapshot.cacheBytes))
                        .accessibilityElement(children: .combine).accessibilityValue("\(snapshot.cacheBytes) 字节")
                        .accessibilityIdentifier("storage.cacheBytes")
                    if snapshot.pendingDeletionBytes > 0 {
                        LabeledContent("待释放文件", value: bytes(snapshot.pendingDeletionBytes))
                        if let failure = snapshot.cleanupFailure {
                            Text(failure).font(.caption).foregroundStyle(.secondary)
                        }
                        Button("重试释放空间") { Task { await model.retryRecovery() } }.disabled(busy)
                    }
                }
                Section {
                    ForEach(snapshot.caches) { cache in
                        LabeledContent(cache.title, value: bytes(cache.bytes))
                    }
                    Picker("缓存上限", selection: $limit) {
                        Text("256 MB").tag(256)
                        Text("512 MB").tag(512)
                        Text("1 GB").tag(1024)
                        Text("2 GB").tag(2048)
                        Text("不限").tag(0)
                    }.accessibilityIdentifier("storage.cacheLimit")
                    Button("清除图像缓存", systemImage: "trash", role: .destructive) { confirmingCacheClear = true }
                        .disabled(busy || ReaderPresence.shared.isReading || snapshot.cacheBytes == 0)
                        .accessibilityIdentifier("storage.clearCache")
                } header: { Text("缓存") } footer: {
                    Text("清除缓存保留离线章节和本地导入作品。缓存超过上限后，会在文件空闲时清理较旧的缓存。")
                }
                Section {
                    if snapshot.items.isEmpty { Text("暂无离线文件").foregroundStyle(.secondary) }
                    ForEach(snapshot.items) { item in
                        HStack {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(item.metadata?.title ?? String(localized: "未关联的离线章节")).font(.headline)
                                if let metadata = item.metadata {
                                    Text(metadata.chapterTitle).foregroundStyle(.secondary)
                                } else if let modified = item.modified {
                                    Text(modified, format: .dateTime.year().month().day()).foregroundStyle(.secondary)
                                }
                                Text("\(item.status) · \(bytes(item.bytes))").font(.caption)
                            }
                            Spacer()
                            Button("删除离线文件", systemImage: "trash", role: .destructive) { selected = item }
                                .labelStyle(.iconOnly).buttonStyle(.borderless)
                                .disabled(busy || ReaderPresence.shared.protects(item.id))
                                .accessibilityIdentifier("storage.delete.\(item.id)")
                        }.accessibilityElement(children: .contain)
                            .accessibilityLabel(
                                "\(item.metadata?.title ?? String(localized: "离线章节")) · \(item.metadata?.chapterTitle ?? "")"
                            )
                            .accessibilityIdentifier("storage.chapter.\(item.id)")
                    }
                } header: { Text("离线章节") } footer: {
                    Text("包含已移除队列记录的章节和未完成的下载。删除文件保留作品库、收藏和阅读进度。")
                }
            } else if model.error == nil { ProgressView("正在统计存储…") }
            if busy || ReaderPresence.shared.isReading {
                Text(model.isWorking ? "正在处理文件…" : "文件正在使用；退出阅读器并等待下载请求结束后可清理。")
                    .font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("storage.busy")
            }
        }
        .navigationTitle("存储管理")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.refresh() }
        .refreshable { await model.refresh() }
        .onChange(of: limit) { _, _ in Task { await model.trimAutomatically(); await model.refresh() } }
        .alert(confirmingCacheClear ? "清除图像缓存？" : "删除离线文件？", isPresented: Binding(
            get: { confirmingCacheClear || selected != nil },
            set: { if !$0 { confirmingCacheClear = false; selected = nil } }
        )) {
            if confirmingCacheClear {
                Button("清除缓存", role: .destructive) { Task { await model.clearCaches() } }
            } else if let item = selected {
                Button("删除文件", role: .destructive) { Task { await model.remove(item) } }
            }
            Button("取消", role: .cancel) { confirmingCacheClear = false; selected = nil }
        } message: {
            if confirmingCacheClear {
                Text("页面、封面和图标需要重新加载。离线章节和本地导入作品会保留。")
            } else {
                Text("\(selected?.metadata?.title ?? String(localized: "离线章节")) · \(selected?.metadata?.chapterTitle ?? "")\n"
                     + String(localized: "删除后需重新下载。收藏和阅读进度会保留。"))
            }
        }
    }

    private func bytes(_ value: Int64) -> String { ByteCountFormatter.string(fromByteCount: value, countStyle: .file) }
}
