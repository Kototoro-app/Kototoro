import KototoroShared
import SwiftUI

private enum TimelineFilter: String, CaseIterable {
    case all, reading, bookmarks, updates, downloads

    var title: String {
        switch self {
        case .all: String(localized: "全部")
        case .reading: String(localized: "历史")
        case .bookmarks: String(localized: "书签")
        case .updates: String(localized: "更新")
        case .downloads: String(localized: "下载")
        }
    }
}

/// A saved reading position with its work (Android's bookmarks shelf), placed on the timeline when it was saved.
struct RecordBookmark: Identifiable {
    let content: IosSourceContent
    let bookmark: IosBookmark
    var id: String { "bookmark.\(content.id).\(bookmark.id)" }
}

/// Reading, bookmarks, updates and completed downloads share one filterable timeline.
struct LibraryRecordsView: View {
    var body: some View {
        NavigationStack { RecordsPage() }
    }
}

private struct RecordsPage: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var downloadQueue: ReaderDownloadQueue
    @StateObject private var downloaded = DownloadedWorksModel()
    @State private var timelineFilter = TimelineFilter.all
    @AppStorage("records.kind") private var pageKind = ""
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true
    @State private var query = ""
    @StateObject private var stats = ReadingStatsModel()
    @AppStorage("records.statsRange") private var range = ReadingStatsRange.month
    @State private var selectedDay: String?
    @State private var lastStatsRequest: String?
    @State private var bookmarkGroups: [IosBookmarkGroup] = []

    private var filterKind: String? {
        if spacesEnabled { return ContentSpace.kind(space) }
        return ContentKindPicker.effective(pageKind, available: availableKinds)
    }

    private var effectiveKind: String {
        filterKind ?? "ALL"
    }

    /// Finished downloads changed: their records are read again.
    private var finishedDownloads: Int { downloadQueue.entries.filter { $0.state == .completed }.count }

    private var statsRequest: String {
        "\(library.recordsRevision)|\(range.rawValue)|\(effectiveKind)|\(query)"
    }

    private final class KindsCache {
        var revision: Int = -1
        var downloads = -1
        var snapshot: AnyObject?
        var kinds: Set<String> = []
    }
    @State private var kindsCache = KindsCache()

    private var availableKinds: Set<String> {
        if kindsCache.revision == library.recordsRevision && kindsCache.snapshot === stats.snapshot &&
            kindsCache.downloads == downloaded.works.count {
            return kindsCache.kinds
        }
        var kinds = Set<String>()
        for entry in library.history { kinds.insert(entry.content.mediaKind) }
        for update in library.updates { kinds.insert(update.content.mediaKind) }
        for work in downloaded.works { kinds.insert(work.content.mediaKind) }
        if let snapshot = stats.snapshot {
            for work in snapshot.works { kinds.insert(work.content.mediaKind) }
            for event in snapshot.timeline { kinds.insert(event.content.mediaKind) }
        }
        kindsCache.revision = library.recordsRevision
        kindsCache.snapshot = stats.snapshot
        kindsCache.downloads = downloaded.works.count
        kindsCache.kinds = kinds
        return kinds
    }

    /// The content space (or this page's switch) and the in-place search over titles and authors.
    private func matches(_ content: IosSourceContent, ignoringKind: Bool = false) -> Bool {
        if !ignoringKind, let filterKind, content.mediaKind != filterKind { return false }
        if spacesEnabled && !ContentSpace.admits(content, sources: sources.sources) { return false }
        let text = query.trimmingCharacters(in: .whitespaces)
        return text.isEmpty || content.title.localizedCaseInsensitiveContains(text) ||
            content.authors.contains { $0.localizedCaseInsensitiveContains(text) }
    }

    /// Use the shared statistics window for completed downloads too, including per-chapter completion dates.
    private func scopedDownloads(_ snapshot: IosStatsDashboard, ignoringKind: Bool = false) -> [DownloadedWork] {
        downloaded.works.compactMap { work in
            guard matches(work.content, ignoringKind: ignoringKind) else { return nil }
            let completions = work.completions.filter {
                let timestamp = Int64($0.timeIntervalSince1970 * 1000)
                return timestamp >= snapshot.windowFrom && timestamp <= snapshot.windowUntil
            }
            guard let latest = completions.max() else { return nil }
            return DownloadedWork(id: work.id, content: work.content, chapterCount: completions.count,
                                  latest: latest, completions: completions)
        }
    }

    /// Bookmarks saved within the statistics window, in this page's scope, without works the adult filter hides from
    /// bookmarks.
    private func scopedBookmarks(_ snapshot: IosStatsDashboard) -> [RecordBookmark] {
        bookmarkGroups.flatMap { group -> [RecordBookmark] in
            guard matches(group.content), !AdultContentFilter.hides(group.content, in: .bookmarks) else { return [] }
            return group.bookmarks.filter { $0.createdAt >= snapshot.windowFrom && $0.createdAt <= snapshot.windowUntil }
                .map { RecordBookmark(content: group.content, bookmark: $0) }
        }
    }

    private func loadBookmarks() async {
        bookmarkGroups = (try? await library.library?.bookmarkGroups()) ?? []
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if !spacesEnabled && ContentKindPicker.isShown(availableKinds) {
                    ContentKindPicker(selection: $pageKind, available: availableKinds).padding(.horizontal)
                }
                if let snapshot = stats.snapshot {
                    if timelineFilter == .reading {
                        ReadingStatsOverview(snapshot: snapshot, selectedDay: $selectedDay, runtime: sources.runtime, compact: true)
                            .accessibilityElement(children: .contain)
                            .accessibilityIdentifier("records.readingOverview")
                            .padding(.horizontal)
                    } else {
                        RecordsOverview(snapshot: snapshot, downloads: scopedDownloads(snapshot),
                                        bookmarks: scopedBookmarks(snapshot), filter: timelineFilter)
                            .padding(.horizontal)
                    }
                }
                Group {
                    HStack {
                        Picker("统计周期", selection: $range) {
                            ForEach(ReadingStatsRange.allCases) { Text($0.title).tag($0) }
                        }
                        .pickerStyle(.menu)
                        .accessibilityIdentifier("stats.period")
                        Spacer()
                        Group {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 6) {
                                    ForEach(TimelineFilter.allCases, id: \.self) { filter in
                                        Button { timelineFilter = filter } label: {
                                            Text(filter.title)
                                                .font(.subheadline.weight(.medium))
                                                .padding(.horizontal, 10)
                                                .frame(minHeight: 44)
                                                .background(timelineFilter == filter
                                                    ? Color.accentColor.opacity(0.15)
                                                    : Color(uiColor: .secondarySystemGroupedBackground),
                                                    in: Capsule())
                                        }
                                        .buttonStyle(.plain)
                                        .foregroundStyle(timelineFilter == filter ? Color.accentColor : .secondary)
                                        .accessibilityAddTraits(timelineFilter == filter ? .isSelected : [])
                                        .accessibilityIdentifier("timeline.filter.\(filter.rawValue)")
                                    }
                                }
                            }
                            .fixedSize(horizontal: false, vertical: true)
                        }
                        if stats.loading { ProgressView().controlSize(.small) }
                    }.padding(.horizontal)
                }
                if timelineFilter == .downloads {
                    NavigationLink { ReaderDownloadsView() } label: {
                        entryCard("下载列表", symbol: "list.bullet", detail: "所有内容空间的下载任务")
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal)
                    .accessibilityIdentifier("records.downloads.queue")
                }
                if timelineFilter == .bookmarks, let notes = library.library?.notes {
                    NavigationLink { NotesOverviewView(notes: notes) } label: {
                        entryCard("书签与笔记", symbol: "note.text", detail: "按作品查看划线、页面笔记与书签")
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal)
                    .accessibilityIdentifier("records.notes")
                }
                if let error = stats.error {
                    Text(error).font(.footnote).foregroundStyle(.red).padding(.horizontal)
                }
                if let error = library.error {
                    Label(error, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.red)
                        .padding(.horizontal)
                }
                if let runtime = sources.runtime {
                    timeline(runtime)
                }
            }
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("记录")
        .navigationBarTitleDisplayMode(.inline)
        .pageSearch(text: $query, prompt: String(localized: "搜索记录"))
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                if spacesEnabled { SpaceMenu() }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                if let runtime = sources.runtime {
                    Button("检查更新", systemImage: "arrow.clockwise") { Task { await library.checkUpdates(runtime) } }
                        .disabled(library.isCheckingUpdates)
                        .accessibilityIdentifier("updates.check")
                }
            }
        }
        .refreshable {
            if let runtime = sources.runtime { await library.checkUpdates(runtime) }
            else { await library.refresh() }
            await downloaded.load(queue: downloadQueue)
            await loadBookmarks()
            await stats.load(library.library, range: range, kind: effectiveKind, query: query)
            lastStatsRequest = statsRequest
        }
        .task(id: library.recordsRevision) { await loadBookmarks() }
        .task(id: statsRequest) {
            guard lastStatsRequest != statsRequest else { return }
            if !query.isEmpty {
                do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
            }
            await stats.load(library.library, range: range, kind: effectiveKind, query: query)
            lastStatsRequest = statsRequest
        }
        .task(id: finishedDownloads) {
            await downloaded.load(queue: downloadQueue)
            await stats.load(library.library, range: range, kind: effectiveKind, query: query)
            lastStatsRequest = statsRequest
        }
        .onChange(of: range) { _, _ in selectedDay = nil }
        .onChange(of: effectiveKind) { _, _ in selectedDay = nil }
        .onChange(of: timelineFilter) { _, _ in selectedDay = nil }
    }

    /// A page this filter leads to, shown above its timeline.
    private func entryCard(_ title: LocalizedStringKey, symbol: String, detail: LocalizedStringKey) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Label(title, systemImage: symbol)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.caption.weight(.semibold))
        }
        .font(.subheadline)
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }

    @ViewBuilder
    private func timeline(_ runtime: IosSources) -> some View {
        if let snapshot = stats.snapshot {
            let downloads = scopedDownloads(snapshot)
            if timelineFilter == .downloads && downloads.isEmpty && !scopedDownloads(snapshot, ignoringKind: true).isEmpty {
                if spacesEnabled {
                    SpaceEmptyView(what: String(localized: "下载记录")).padding(.top, 32)
                } else {
                    ContentUnavailableView {
                        Label("当前内容类型没有下载记录", systemImage: "arrow.down.circle")
                    } description: {
                        Text("其他内容类型里还有下载记录。")
                    } actions: {
                        Button("显示全部类型") { pageKind = "" }
                            .buttonStyle(.glass).accessibilityIdentifier("records.downloads.showAllKinds")
                    }
                }
            } else {
                RecordsTimeline(events: snapshot.timeline.filter {
                    selectedDay == nil || ReadingStatsFormat.day(ReadingStatsFormat.date($0.timestamp)) == selectedDay
                }, downloads: downloads, bookmarks: scopedBookmarks(snapshot), filter: timelineFilter, runtime: runtime,
                   onBookmarksChanged: loadBookmarks)
            }
        } else if stats.loading {
            ProgressView().frame(maxWidth: .infinity).padding()
        }
    }
}

/// Dates share the center line; each lane orders its own events without forcing unrelated times into pairs.
/// Reading and downloads share the left lane (what the reader did), found updates the right.
private struct RecordsOverview: View {
    let snapshot: IosStatsDashboard
    let downloads: [DownloadedWork]
    let bookmarks: [RecordBookmark]
    let filter: TimelineFilter

    private var updates: [IosTimelineEvent] { snapshot.timeline.filter(\.isUpdate) }
    private var updateChapterCount: String {
        let known = updates.reduce(0) { $0 + max(Int($1.count), 0) }
        guard updates.contains(where: { $0.count < 0 }) else { return known.formatted() }
        return known == 0 ? String(localized: "未知") : String(localized: "至少 \(known)")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(filter == .all ? "本期概览" : filter == .updates ? "更新概览" : filter == .bookmarks ? "书签概览" : "下载概览")
                .font(.caption).foregroundStyle(.secondary)
            if filter == .all {
                HStack(alignment: .top, spacing: 12) {
                    metric(String(localized: "阅读"), symbol: "book", value: ReadingStatsFormat.duration(snapshot.totalDuration),
                           detail: String(localized: "\(Set(snapshot.timeline.filter { !$0.isUpdate }.map { $0.content.contentKey }).count) 部作品"))
                        .accessibilityIdentifier("records.summary.reading")
                    metric(String(localized: "更新"), symbol: "arrow.triangle.2.circlepath",
                           value: "\(updateChapterCount) \(String(localized: "章"))",
                           detail: String(localized: "\(Set(updates.map { $0.content.contentKey }).count) 部作品"))
                        .accessibilityIdentifier("records.summary.updates")
                    metric(String(localized: "下载"), symbol: "arrow.down.circle",
                           value: String(localized: "\(downloads.reduce(0) { $0 + $1.chapterCount }) 章"),
                           detail: String(localized: "\(downloads.count) 部作品"))
                        .accessibilityIdentifier("records.summary.downloads")
                }
            } else if filter == .bookmarks {
                HStack(spacing: 12) {
                    metric(String(localized: "书签"), symbol: "bookmark",
                           value: bookmarks.count.formatted(), detail: String(localized: "个位置"))
                        .accessibilityIdentifier("records.summary.bookmarks")
                    metric(String(localized: "涉及作品"), symbol: "books.vertical",
                           value: Set(bookmarks.map { $0.content.contentKey }).count.formatted(), detail: String(localized: "部作品"))
                }
            } else if filter == .updates {
                HStack(spacing: 12) {
                    metric(String(localized: "更新作品"), symbol: "books.vertical",
                           value: Set(updates.map { $0.content.contentKey }).count.formatted(), detail: String(localized: "部作品"))
                    metric(String(localized: "新增章节"), symbol: "plus.circle",
                           value: updateChapterCount, detail: String(localized: "章"))
                }
            } else {
                HStack(spacing: 12) {
                    metric(String(localized: "完成作品"), symbol: "books.vertical",
                           value: downloads.count.formatted(), detail: String(localized: "部作品"))
                    metric(String(localized: "完成章节"), symbol: "checkmark.circle",
                           value: downloads.reduce(0) { $0 + $1.chapterCount }.formatted(), detail: String(localized: "章"))
                }
            }
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
        .accessibilityIdentifier("records.overview")
    }

    private func metric(_ title: String, symbol: String, value: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: symbol).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.headline).monospacedDigit()
            Text(detail).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

private struct RecordUpdateDownloadButton: View {
    let event: IosTimelineEvent
    let runtime: IosSources
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var queue: ReaderDownloadQueue
    @State private var preparedEvent: IosTimelineEvent?
    @State private var offline = Set<String>()
    @State private var preparing = false
    @State private var error: String?

    private var content: IosSourceContent { preparedEvent?.content ?? event.content }
    private var chapters: [IosSourceChapter] { preparedEvent?.downloadChapters ?? event.downloadChapters }

    private var entries: [ReaderDownloadEntry] {
        let ids = Set(chapters.map { (content.bookOf(chapter: $0) ?? $0).id })
        return queue.entries.filter {
            $0.request.source == content.sourceName && $0.request.contentID == content.id && ids.contains($0.request.chapterID)
        }
    }

    private var active: Set<String> {
        Set(entries.filter { $0.state == .queued || $0.state == .running }.map(\.request.chapterID))
    }

    private var pending: [IosSourceChapter] {
        let active = active
        return chapters.filter {
            let id = (content.bookOf(chapter: $0) ?? $0).id
            return !offline.contains(id) && !active.contains(id)
        }
    }

    var body: some View {
        if content.mediaKind == "MANGA" || content.mediaKind == "NOVEL" {
            Button {
                preparing = true
                error = nil
                Task {
                    defer { preparing = false }
                    do {
                        try await withReaderStorage {
                            if chapters.isEmpty {
                                guard let database = library.library else { throw CancellationError() }
                                preparedEvent = try await database.prepareUpdateDownload(event: event, sources: runtime)
                                refreshOffline()
                            }
                            let remaining = pending.filter { !ReaderDownloads.isDownloaded(content: content, chapter: $0) }
                            if !remaining.isEmpty { try await queue.enqueue(content: content, chapters: remaining) }
                            refreshOffline()
                        }
                    } catch { self.error = error.localizedDescription }
                }
            } label: {
                // An icon on the row's time line; the state is spelled out for VoiceOver.
                let running = entries.contains(where: { $0.state == .running })
                Group {
                    if preparing || running {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: chapters.isEmpty || !pending.isEmpty ? "arrow.down.circle"
                              : !active.isEmpty ? "arrow.down.circle.dotted" : "checkmark.circle")
                    }
                }
                .font(.body)
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
                .accessibilityLabel(preparing ? String(localized: "正在加入队列")
                      : chapters.isEmpty || !pending.isEmpty ? String(localized: "下载更新")
                      : running ? String(localized: "正在下载")
                      : !active.isEmpty ? String(localized: "已排队") : String(localized: "已下载"))
            }
            .disabled(preparing || (!chapters.isEmpty && pending.isEmpty))
            .buttonStyle(.borderless)
            .accessibilityIdentifier("records.downloadUpdate.\(content.id)")
            .task(id: entries.filter { $0.state == .completed }.count) { refreshOffline() }
            .alert("下载更新失败", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("好") { error = nil }
            } message: { Text(error ?? "") }
        }
    }

    private func refreshOffline() {
        offline = Set(chapters.filter { ReaderDownloads.isDownloaded(content: content, chapter: $0) }
            .map { (content.bookOf(chapter: $0) ?? $0).id })
    }
}

private struct RecordsTimeline: View {
    let events: [IosTimelineEvent]
    let downloads: [DownloadedWork]
    let bookmarks: [RecordBookmark]
    let filter: TimelineFilter
    let runtime: IosSources
    let onBookmarksChanged: () async -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var selected: TimelineItem?
    /// A bookmark opened in its reader, or a work opened from a bookmark's menu.
    @State private var reading: PlaybackRequest?
    @State private var openedWork: IosSourceContent?
    @State private var bookmarkError: String?
    @EnvironmentObject private var library: LibraryModel
    @State private var seenEvents = Set<String>()

    /// A reading or update event, or the chapters of one work that finished downloading on one day.
    private struct TimelineItem: Identifiable, Hashable {
        enum Kind {
            case event(IosTimelineEvent)
            case download(count: Int)
            case bookmark(IosBookmark)
        }
        let id: String
        let content: IosSourceContent
        let timestamp: Int64
        let kind: Kind

        static func == (lhs: Self, rhs: Self) -> Bool { lhs.id == rhs.id }
        func hash(into hasher: inout Hasher) { hasher.combine(id) }

        var event: IosTimelineEvent? { if case .event(let event) = kind { event } else { nil } }
        var isUpdate: Bool { event?.isUpdate == true }
        var downloadCount: Int? { if case .download(let count) = kind { count } else { nil } }
        var bookmark: IosBookmark? { if case .bookmark(let bookmark) = kind { bookmark } else { nil } }
    }
    private struct TimelineDayGroup {
        let date: String
        let all: [TimelineItem]
        let history: [TimelineItem]
        let updates: [TimelineItem]
    }

    /// One item per work and day, at the day's last finished chapter.
    private var downloadItems: [TimelineItem] {
        downloads.flatMap { work in
            Dictionary(grouping: work.completions) { ReadingStatsFormat.day($0) }.map { day, dates in
                let latest = dates.max() ?? work.latest
                return TimelineItem(id: "download.\(work.id).\(day)", content: work.content,
                                    timestamp: Int64(latest.timeIntervalSince1970 * 1000), kind: .download(count: dates.count))
            }
        }
    }

    var body: some View {
        let items = (events.map { TimelineItem(id: $0.id, content: $0.content, timestamp: $0.timestamp, kind: .event($0)) }
            + downloadItems
            + bookmarks.map { TimelineItem(id: $0.id, content: $0.content, timestamp: $0.bookmark.createdAt, kind: .bookmark($0.bookmark)) })
            .filter { item in
                // Android's adult filter targets: reading history and the updates feed.
                if item.event != nil, AdultContentFilter.hides(item.content, in: item.isUpdate ? .feed : .history) {
                    return false
                }
                return switch filter {
                case .all: true
                case .reading: item.event != nil && !item.isUpdate
                case .bookmarks: item.bookmark != nil
                case .updates: item.isUpdate
                case .downloads: item.downloadCount != nil
                }
            }.sorted { $0.timestamp > $1.timestamp }
        if items.isEmpty {
            ContentUnavailableView(filter == .all ? String(localized: "暂无足迹")
                                   : String(localized: "暂无\(filter.title)记录"), systemImage: "timeline.selection",
                description: Text("可切换日期范围或记录类型查看其他足迹。"))
                .padding(.top, 32)
        } else {
            let grouped = Dictionary(grouping: items) { ReadingStatsFormat.day(ReadingStatsFormat.date($0.timestamp)) }
            let dates = grouped.keys.sorted(by: >)
            let dayGroups: [TimelineDayGroup] = dates.compactMap { date in
                guard let dayItems = grouped[date] else { return nil }
                return TimelineDayGroup(date: date, all: dayItems, history: dayItems.filter { !$0.isUpdate },
                                        updates: dayItems.filter(\.isUpdate))
            }
            LazyVStack(spacing: 14) {
                if filter == .all {
                    HStack {
                        Text("历史、书签与下载").frame(maxWidth: .infinity)
                        Color.clear.frame(width: 24, height: 1)
                        Text("发现更新").frame(maxWidth: .infinity)
                    }
                    .font(.caption).foregroundStyle(.secondary)
                    .padding(.vertical, 4)
                    .background(Color(uiColor: .systemGroupedBackground))
                }
                ForEach(dayGroups, id: \.date) { group in
                    VStack(alignment: filter == .all ? .center : .leading, spacing: 8) {
                        Text(dayTitle(group.date, items: group.all))
                            .font(.caption.weight(.medium)).padding(.horizontal, 10).padding(.vertical, 4)
                            .background(Color(uiColor: .systemGroupedBackground), in: Capsule())
                        if dynamicTypeSize.isAccessibilitySize || filter != .all {
                            VStack(alignment: .leading, spacing: 6) {
                                ForEach(group.all) { item in
                                    eventRow(item, alignment: .leading, showsKind: true)
                                }
                            }
                            .padding(.leading, filter == .all ? 0 : 16)
                            .background(Color(uiColor: .systemGroupedBackground))
                        } else {
                            HStack(alignment: .top, spacing: 20) {
                                lane(group.history, alignment: .trailing)
                                lane(group.updates, alignment: .leading)
                            }
                        }
                    }
                }
            }
            .background {
                Rectangle().fill(Color(uiColor: .separator)).frame(width: 1)
                    .frame(maxWidth: .infinity, alignment: filter == .all ? .center : .leading)
            }
            .padding(.horizontal)
            .accessibilityIdentifier("records.timeline")
            .navigationDestination(item: $reading) { DetailsReaderDestination(runtime: runtime, request: $0) }
            .navigationDestination(item: $openedWork) { SourceDetailsView(runtime: runtime, content: $0) }
            .alert("书签操作失败", isPresented: Binding(get: { bookmarkError != nil }, set: { if !$0 { bookmarkError = nil } })) {
                Button("好") { bookmarkError = nil }
            } message: { Text(bookmarkError ?? "") }
            .navigationDestination(item: $selected) { item in
                List {
                    Section {
                        Text(item.content.title).font(.headline)
                        LabeledContent(timeTitle(item),
                            value: ReadingStatsFormat.date(item.timestamp).formatted(date: .abbreviated, time: .shortened))
                        if let event = item.event {
                            if event.isUpdate {
                                LabeledContent("来源更新时间", value: sourceUpdateDate(event))
                            }
                            if event.duration > 0 {
                                LabeledContent(isVideo(item) ? "播放时长" : "阅读时长",
                                               value: ReadingStatsFormat.duration(event.duration))
                            }
                            if !event.isUpdate && event.count > 0 {
                                LabeledContent(isVideo(item) ? "播放次数" : "阅读次数", value: "\(event.count)")
                            }
                            Text(event.detail).font(.subheadline)
                        } else if let count = item.downloadCount {
                            LabeledContent("下载完成", value: String(localized: "\(count) 章"))
                        }
                    }
                    if let event = item.event, !event.updateChapters.isEmpty {
                        Section("更新章节") {
                            ForEach(event.updateChapters, id: \.key) { chapter in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(chapter.title)
                                    Text(chapter.sourceUpdatedAt > 0
                                         ? ReadingStatsFormat.date(chapter.sourceUpdatedAt).formatted(date: .abbreviated, time: .shortened)
                                         : String(localized: "来源更新时间未知"))
                                        .font(.caption).foregroundStyle(.secondary)
                                    Text(chapter.isRead ? String(localized: "已读完") : String(localized: "待读"))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    NavigationLink("打开作品") {
                        SourceDetailsView(runtime: runtime,
                                          content: item.downloadCount == nil ? item.content : item.content.withoutChapters)
                    }
                }
                .navigationTitle(recordTitle(item))
                .navigationBarTitleDisplayMode(.inline)
                .accessibilityIdentifier("records.detail")
            }
        }
    }

    private func kindTitle(_ item: TimelineItem) -> String {
        item.bookmark != nil ? String(localized: "书签")
            : item.downloadCount != nil ? String(localized: "下载") : item.isUpdate ? String(localized: "发现更新")
            : isVideo(item) ? String(localized: "播放") : String(localized: "阅读")
    }

    /// Videos are played, not read: their history uses the playback wording.
    private func isVideo(_ item: TimelineItem) -> Bool { item.content.mediaKind == "VIDEO" }

    /// Opens the reader at [bookmark], with the work's stored chapters (works on the timeline come without them).
    private func open(_ bookmark: IosBookmark, of content: IosSourceContent) {
        Task {
            let details = (try? await library.library?.storedDetails(content: content)) ?? content
            guard let chapter = details.chapters.first(where: { $0.id == bookmark.chapterId }) else {
                bookmarkError = String(localized: "书签对应的章节已不存在，请打开作品刷新章节。")
                return
            }
            reading = PlaybackRequest(content: details, chapter: chapter, initialBookmark: bookmark)
        }
    }

    private func remove(_ bookmark: IosBookmark, of content: IosSourceContent) {
        Task {
            guard let database = library.library else { return }
            do {
                try await database.removeBookmark(content: content, bookmark: bookmark)
                if content.mediaKind != "NOVEL" { try? await BookmarkImages.remove(content: content, bookmark: bookmark) }
                await onBookmarksChanged()
            } catch { bookmarkError = error.localizedDescription }
        }
    }

    /// The bookmark's place: chapter, page or paragraph, and how far into the chapter.
    private func bookmarkPlace(_ bookmark: IosBookmark, of content: IosSourceContent) -> String {
        let percent = bookmark.percent.isFinite ? Int(min(max(bookmark.percent, 0), 1) * 100) : 0
        let position = content.mediaKind == "NOVEL" ? String(localized: "第 \(bookmark.index + 1) 段")
            : String(localized: "第 \(bookmark.index + 1) 页")
        let chapter = bookmark.chapterTitle.isEmpty ? nil : bookmark.chapterTitle
        return ([chapter, position, "\(percent)%"] as [String?]).compactMap { $0 }.joined(separator: " · ")
    }

    private func timeTitle(_ item: TimelineItem) -> String {
        item.downloadCount != nil ? String(localized: "下载时间") : item.isUpdate ? String(localized: "发现更新时间")
            : isVideo(item) ? String(localized: "播放时间") : String(localized: "阅读时间")
    }

    private func recordTitle(_ item: TimelineItem) -> String {
        item.downloadCount != nil ? String(localized: "下载记录") : item.isUpdate ? String(localized: "更新记录")
            : isVideo(item) ? String(localized: "播放记录") : String(localized: "阅读记录")
    }

    private func lane(_ items: [TimelineItem], alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 6) {
            ForEach(items) { item in eventRow(item, alignment: alignment, showsKind: false) }
        }
        .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .trailing)
    }

    private func eventRow(_ item: TimelineItem, alignment: HorizontalAlignment, showsKind: Bool) -> some View {
        Button {
            if let bookmark = item.bookmark { open(bookmark, of: item.content); return }
            selected = item
            if let event = item.event, event.isUpdate {
                Task {
                    do {
                        try await library.library?.markUpdateSeen(event: event)
                        seenEvents.insert(event.id)
                    } catch { /* A later refresh can retry acknowledging this reminder. */ }
                }
            }
        } label: {
            let cover = CoverImage(runtime: runtime, content: item.content)
                .frame(width: 32, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                .accessibilityHidden(true)
            HStack(alignment: .top, spacing: 8) {
                if alignment == .leading { cover }
                VStack(alignment: alignment, spacing: 2) {
                    let unseen = item.event.map { $0.isUpdate && $0.isUnseen && !seenEvents.contains($0.id) } ?? false
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        if unseen {
                            Circle().fill(.red).frame(width: 6, height: 6)
                                .alignmentGuide(.firstTextBaseline) { $0[.bottom] }
                                .accessibilityLabel("未查看更新")
                        }
                        Text(item.content.title).font(.subheadline.weight(.medium)).lineLimit(2)
                            .multilineTextAlignment(alignment == .leading ? .leading : .trailing)
                    }
                    if let bookmark = item.bookmark {
                        Label(bookmarkPlace(bookmark, of: item.content), systemImage: "bookmark.fill")
                            .font(.caption).foregroundStyle(.tint).lineLimit(1)
                        if item.content.mediaKind == "NOVEL" && !bookmark.textPreview.isEmpty {
                            Text(bookmark.textPreview).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                .multilineTextAlignment(alignment == .leading ? .leading : .trailing)
                        }
                    } else if let count = item.downloadCount {
                        Label("下载 \(count) 章", systemImage: "arrow.down.circle")
                            .font(.caption).foregroundStyle(.tint).lineLimit(1)
                    } else if let event = item.event {
                        if event.isUpdate {
                            updateSummary(event).font(.caption).lineLimit(2)
                                .multilineTextAlignment(alignment == .leading ? .leading : .trailing)
                            Text(sourceUpdateDate(event)).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                        } else {
                            Text(event.detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            if event.hasProgress { progressLine(event) }
                        }
                    }
                    Text(metaLine(item, showsKind: showsKind)).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                        // Leaves room for the update's download icon at the row's far edge.
                        .padding(alignment == .leading ? .trailing : .leading, item.isUpdate ? 32 : 0)
                }
                .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .trailing)
                if alignment == .trailing { cover }
            }
            .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .trailing)
            .padding(.vertical, 2).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            if let bookmark = item.bookmark {
                Button("继续阅读", systemImage: "book") { open(bookmark, of: item.content) }
                Button("打开作品", systemImage: "info.circle") { openedWork = item.content }
                Button("删除书签", systemImage: "trash", role: .destructive) { remove(bookmark, of: item.content) }
            }
        }
        .accessibilityIdentifier("timeline.\(item.id)")
        // A sibling of the row button, aligned with its time line, so the two taps stay separate.
        .overlay(alignment: alignment == .leading ? .bottomTrailing : .bottomLeading) {
            if let event = item.event, event.isUpdate {
                RecordUpdateDownloadButton(event: event, runtime: runtime).offset(y: 6)
            }
        }
    }

    /// The update's new chapters and what is still unread, on one line.
    private func updateSummary(_ event: IosTimelineEvent) -> Text {
        let added = Text(event.count < 0 ? String(localized: "有章节更新") : String(localized: "新增 \(event.count) 章节"))
            .foregroundStyle(.orange)
        guard event.pendingCount >= 0 else { return added }
        let pending = Text(event.pendingCount == 0 ? String(localized: "已读完")
                           : String(localized: "待读 \(event.pendingCount) 章")).foregroundStyle(.secondary)
        return Text("\(added) · \(pending)")
    }

    /// A thin progress bar with its percentage beside it.
    private func progressLine(_ event: IosTimelineEvent) -> some View {
        let percent = Int(min(max(event.progress, 0), 1) * 100)
        return HStack(spacing: 6) {
            ProgressView(value: Double(percent) / 100).progressViewStyle(.linear)
            Text("\(percent)%").font(.caption2).monospacedDigit().foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(event.content.mediaKind == "VIDEO" ? String(localized: "本集进度 \(percent)%")
                            : String(localized: "本章进度 \(percent)%"))
        .accessibilityIdentifier("timeline.progress.\(event.id)")
    }

    /// Kind (when the lanes don't already say it), time and reading duration.
    private func metaLine(_ item: TimelineItem, showsKind: Bool) -> String {
        var parts = [ReadingStatsFormat.date(item.timestamp).formatted(.dateTime.hour().minute())]
        if showsKind { parts.insert(kindTitle(item), at: 0) }
        if let duration = item.event?.duration, duration > 0 { parts.append(ReadingStatsFormat.duration(duration)) }
        return parts.joined(separator: " · ")
    }

    private func dayTitle(_ date: String, items: [TimelineItem]) -> String {
        guard let item = items.first else { return date }
        let day = ReadingStatsFormat.date(item.timestamp)
        guard Calendar.current.isDateInToday(day) || Calendar.current.isDateInYesterday(day) else { return date }
        return String(localized: "\(ReadingStatsFormat.dayTitle(item.timestamp)) · \(date)")
    }

    private func sourceUpdateDate(_ event: IosTimelineEvent) -> String {
        guard event.publishedAt > 0 else { return String(localized: "来源更新时间未知") }
        let from = ReadingStatsFormat.date(event.publishedFrom)
        let until = ReadingStatsFormat.date(event.publishedAt)
        let range = Calendar.current.isDate(from, inSameDayAs: until)
            ? until.formatted(date: .abbreviated, time: .omitted)
            : "\(from.formatted(date: .abbreviated, time: .omitted)) — \(until.formatted(date: .abbreviated, time: .omitted))"
        return String(localized: "来源更新于 \(range)")
            + (event.unknownPublishedCount > 0 ? String(localized: " · 部分时间未知") : "")
    }
}
