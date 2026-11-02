import KototoroShared
import SwiftUI

struct ReaderDownloadsSheet: View {
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ReaderDownloadsView()
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { dismiss() }.accessibilityIdentifier("downloads.close")
                    }
                }
        }
    }
}

struct ReaderDownloadsView: View {
    @EnvironmentObject private var queue: ReaderDownloadQueue
    @EnvironmentObject private var sources: SourcesModel
    @State private var actionError: String?
    @State private var works = DownloadedWorks()

    var body: some View {
        List {
            if let error = actionError ?? queue.storageFailure ?? queue.error {
                Text(error).foregroundStyle(.red).accessibilityIdentifier("downloads.error")
            }
            ForEach(queue.entries) { entry in
                // A record opens its work's details, as Android's download list does.
                Group {
                    if let runtime = sources.runtime, let content = works.content(of: entry) {
                        NavigationLink {
                            SourceDetailsView(runtime: runtime, content: content)
                        } label: { row(entry, runtime: runtime, content: content) }
                        .accessibilityIdentifier("download.open.\(entry.id)")
                    } else {
                        row(entry, runtime: nil, content: nil)
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel("\(entry.request.title) · \(entry.request.chapterTitle)")
                .accessibilityIdentifier("download.entry.\(entry.id)")
            }
            Section {
                NavigationLink { DownloadSettingsView() } label: {
                    Label("下载设置", systemImage: "slider.horizontal.3")
                }
                .accessibilityIdentifier("downloads.settings")
                NavigationLink { ReaderStorageView() } label: {
                    Label("存储管理", systemImage: "internaldrive")
                }
                .accessibilityIdentifier("downloads.storage")
            } footer: {
                Text("仅在应用前台下载，进入后台会暂停。继续时校验已保存的内容并从未完成处恢复；移除任务保留已下载文件。")
            }
        }
        .overlay {
            if queue.isLoading {
                ProgressView("正在读取下载队列…")
            } else if queue.entries.isEmpty && queue.error == nil && queue.storageFailure == nil {
                ContentUnavailableView("暂无下载任务", systemImage: "arrow.down.circle",
                                       description: Text("在漫画、小说或视频播放器中下载章节与剧集。已保存文件可从“存储管理”查看。"))
                    .allowsHitTesting(false)
            }
        }
        .navigationTitle("下载")
        .navigationBarTitleDisplayMode(.inline)
        .task { await queue.prepare() }
    }

    private func row(_ entry: ReaderDownloadEntry, runtime: IosSources?, content: IosSourceContent?) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Group {
                if let runtime, let content { CoverImage(runtime: runtime, content: content) }
                else { Rectangle().fill(.quaternary) }
            }
            .frame(width: 52, height: 78)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 8) {
                Text(entry.request.title).font(.headline)
                Text(entry.request.chapterTitle).foregroundStyle(.secondary)
                Text(entry.status).font(.caption).accessibilityIdentifier("download.status.\(entry.id)")
                if entry.state == .paused && queue.isFinishing(entry.id) {
                    Text("正在等待当前源请求结束…").font(.caption).foregroundStyle(.secondary)
                }
                if entry.totalPages > 0 {
                    ProgressView(value: Double(entry.completedPages), total: Double(entry.totalPages))
                }
                HStack {
                    switch entry.state {
                    case .queued, .running:
                        Button("暂停", systemImage: "pause") { perform { try await queue.pause(entry.id) } }
                            .accessibilityIdentifier("download.pause.\(entry.id)")
                    case .paused, .failed:
                        Button(entry.state == .failed ? "重试" : "继续", systemImage: "arrow.clockwise") {
                            perform { try await queue.resume(entry.id) }
                        }.accessibilityIdentifier("download.resume.\(entry.id)")
                    case .completed: EmptyView()
                    }
                    Spacer()
                    Button("移除任务", systemImage: "xmark", role: .destructive) {
                        perform { try await queue.remove(entry.id) }
                    }.accessibilityIdentifier("download.remove.\(entry.id)")
                }.buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 4)
    }

    private func perform(_ action: @escaping () async throws -> Void) {
        Task {
            do { try await action(); actionError = nil }
            catch { actionError = error.localizedDescription }
        }
    }
}

/// The work each download record was queued for, decoded once from its payload (which keeps the source's fields).
final class DownloadedWorks {
    private var decoded: [String: IosSourceContent?] = [:]

    func content(of entry: ReaderDownloadEntry) -> IosSourceContent? {
        if let known = decoded[entry.id] { return known }
        let content = try? IosDownloadArchive().decode(json: entry.request.payload).content
        decoded[entry.id] = content
        return content
    }
}

/// A work with finished offline chapters, as the download records list it.
struct DownloadedWork: Identifiable {
    let id: String
    /// The work with its downloaded chapters (see `IosDownloadArchive.offlineContent`).
    let content: IosSourceContent
    let chapterCount: Int
    let latest: Date
    /// When each chapter finished, newest first.
    let completions: [Date]
}

/// Finished downloads grouped by work, read from the offline files (they outlive their queue tasks).
@MainActor
final class DownloadedWorksModel: ObservableObject {
    @Published private(set) var works: [DownloadedWork] = []
    @Published private(set) var loaded = false

    func load(queue: ReaderDownloadQueue) async {
        guard let records = try? await Self.records(queue: queue) else { loaded = true; return }
        let archive = IosDownloadArchive()
        var grouped: [String: [ReaderDownloadRecord]] = [:]
        for record in records { grouped[Self.key(record.metadata.source, record.metadata.contentID), default: []].append(record) }
        works = grouped.compactMap { key, records in
            guard let content = archive.offlineContent(payloads: records.compactMap(\.payload)) else { return nil }
            let completions = records.map(\.completedAt).sorted(by: >)
            return DownloadedWork(id: key, content: content, chapterCount: records.count,
                                  latest: completions.first ?? .distantPast, completions: completions)
        }.sorted { $0.latest > $1.latest }
        loaded = true
    }

    /// The work as its downloads list it, when the source cannot list its chapters and none were saved.
    static func offlineContent(for content: IosSourceContent, queue: ReaderDownloadQueue) async -> IosSourceContent? {
        guard let records = try? await records(queue: queue) else { return nil }
        let payloads = records.filter { $0.metadata.source == content.sourceName && $0.metadata.contentID == content.id }
            .compactMap(\.payload)
        return IosDownloadArchive().offlineContent(payloads: payloads)
    }

    /// Finished chapters with their requests; downloads made before requests were kept borrow their queue task's.
    private static func records(queue: ReaderDownloadQueue) async throws -> [ReaderDownloadRecord] {
        let store = try ReaderStorageModel.downloadStore()
        let records = try await store.downloadRecords()
        let tasks = Dictionary(queue.entries.map { ($0.request.storageMetadata.directoryID, $0.request.payload) },
                               uniquingKeysWith: { first, _ in first })
        var result: [ReaderDownloadRecord] = []
        for record in records {
            guard record.payload == nil, let payload = tasks[record.metadata.directoryID] else { result.append(record); continue }
            try? await store.keep(payload: payload, for: record.metadata)
            result.append(ReaderDownloadRecord(metadata: record.metadata, payload: payload, completedAt: record.completedAt))
        }
        return result
    }

    private static func key(_ source: String, _ contentID: String) -> String { source + "|" + contentID }
}
