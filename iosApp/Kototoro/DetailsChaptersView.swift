import KototoroShared
import SwiftUI

/// A full directory owns its search, selection and navigation independently of the details preview.
struct DetailsChaptersView: View {
    @ObservedObject var model: SourceDetailsModel
    @Binding var resume: IosReadingPosition?
    let refresh: () async -> Void
    let refreshRecords: () async -> Void
    @EnvironmentObject private var downloads: ReaderDownloadQueue
    @AppStorage("details.newestFirst") private var newestFirst = false
    @AppStorage("details.chapterOrder") private var chapterOrder = "source"
    @State private var query = ""
    @State private var editMode = EditMode.inactive
    @State private var selectedIDs = Set<String>()
    @State private var showingDownloads = false
    @State private var downloadError: String?
    @State private var downloadStatus: String?
    @State private var reading: PlaybackRequest?
    @State private var playing: PlaybackRequest?
    @State private var preview: PlaybackRequest?
    @State private var positioned = false
    /// Downloaded books whose chapters are folded away; books open expanded, as on Android.
    @State private var collapsedBooks = Set<String>()

    private var content: IosSourceContent { model.details ?? model.content }
    private var chapters: [IosSourceChapter] {
        let list = model.isVideo ? model.visibleChapters : model.chapterList(query: "", newestFirst: false)
        let ordered = newestFirst ? Array(list.reversed()) : list
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? ordered : ordered.filter { $0.title.localizedCaseInsensitiveContains(text) }
    }
    private var selecting: Bool { editMode.isEditing }

    var body: some View {
        ScrollViewReader { proxy in
            directory
                .onChange(of: chapters.map(\.id), initial: true) { _, _ in
                    guard !positioned, !chapters.isEmpty else { return }
                    positioned = true
                    if let id = resume?.chapterId, chapters.contains(where: { $0.id == id }) {
                        proxy.scrollTo(id, anchor: .center)
                    }
                }
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        if !model.isVideo {
                            Button(selecting ? "完成" : "选择") {
                                if selecting { finishSelection() } else { editMode = .active }
                            }
                            .disabled(chapters.isEmpty && !selecting)
                            .accessibilityIdentifier("details.chapters.select")
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu("章节选项", systemImage: "ellipsis") {
                            if model.isVideo {
                                if model.lines.count > 1 {
                                    Picker("线路", selection: $model.selectedLine) {
                                        ForEach(model.lines, id: \.self) { Text($0 ?? String(localized: "默认")).tag($0) }
                                    }
                                }
                            } else if model.branches.count > 1 {
                                Picker("分支", selection: $model.selectedBranch) {
                                    ForEach(model.branches, id: \.self) { Text($0 ?? String(localized: "默认")).tag($0) }
                                }
                            }
                            Section("排序") { DetailsChapterSortOptions(order: $chapterOrder, newestFirst: $newestFirst) }
                            if let id = resume?.chapterId {
                                Button("定位当前章", systemImage: "location") {
                                    query = ""
                                    if let chapter = model.chapters.first(where: { $0.id == id }) {
                                        model.selectedBranch = chapter.branch
                                        model.selectedLine = chapter.line
                                    }
                                    // Wait for the search and branch changes to lay out the target row.
                                    Task { @MainActor in
                                        await Task.yield()
                                        withAnimation { proxy.scrollTo(id, anchor: .center) }
                                    }
                                }
                                .accessibilityIdentifier("details.chapters.current")
                            }
                            Button("下载队列", systemImage: "arrow.down.circle") { showingDownloads = true }
                                .accessibilityIdentifier("details.chapters.downloads")
                            Button(model.isVideo ? "刷新剧集" : "刷新章节", systemImage: "arrow.clockwise") {
                                Task { await refresh() }
                            }
                            .disabled(model.isLoading)
                            .accessibilityIdentifier("details.chapters.refresh")
                        }
                        .accessibilityIdentifier("details.chapters.options")
                    }
                    if selecting {
                        ToolbarItem(placement: .bottomBar) {
                            Button(Set(chapters.map(\.id)).isSubset(of: selectedIDs) ? "取消全选" : "全选") {
                                selectedIDs = BatchSelection.toggling(visible: Set(chapters.map(\.id)), in: selectedIDs)
                            }
                            .disabled(chapters.isEmpty)
                            .accessibilityIdentifier("details.chapters.selectAll")
                        }
                        ToolbarSpacer(.flexible, placement: .bottomBar)
                        ToolbarItem(placement: .bottomBar) {
                            Button("下载所选（\(selectedIDs.count)）", systemImage: "arrow.down.circle") {
                                enqueue(model.chapterList(query: "", newestFirst: newestFirst)
                                    .filter { selectedIDs.contains($0.id) })
                            }
                            .disabled(selectedIDs.isEmpty)
                            .buttonStyle(.borderedProminent)
                            .accessibilityIdentifier("details.chapters.downloadSelected")
                        }
                    }
                }
        }
        .navigationTitle(model.isVideo ? "全部剧集" : "全部章节")
        .detailsPresence()
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: model.isVideo ? "搜索剧集" : "搜索章节")
        .environment(\.editMode, $editMode)
        .toolbar(selecting ? .visible : .hidden, for: .bottomBar)
        .toolbar(.hidden, for: .tabBar)
        .onChange(of: model.selectedBranch) { _, _ in finishSelection() }
        .onChange(of: chapterOrder) { _, value in model.orderByNumber = value == "number" }
        .onChange(of: model.chapters.map(\.id)) { _, ids in selectedIDs.formIntersection(ids) }
        .navigationDestination(item: $reading) { DetailsReaderDestination(runtime: model.runtime, request: $0) }
        .navigationDestination(item: $preview) {
            DetailsPagePreviewView(model: model, chapter: $0.chapter, resume: resume, refreshRecords: refreshRecords)
        }
        .fullScreenCover(item: $playing, onDismiss: { Task { await refreshRecords() } }) {
            VideoPlayerDestination(runtime: model.runtime, request: $0)
        }
        .onChange(of: reading == nil) { _, closed in if closed { Task { await refreshRecords() } } }
        .sheet(isPresented: $showingDownloads) { ReaderDownloadsSheet() }
        .alert("无法下载", isPresented: Binding(get: { downloadError != nil },
                                            set: { if !$0 { downloadError = nil } })) {
            Button("好") { downloadError = nil }
        } message: { Text(downloadError ?? "") }
    }

    private var directory: some View {
        let list = chapters
        let statuses = Dictionary(downloads.entries.filter {
            $0.request.source == content.sourceName && $0.request.contentID == content.id
        }.map { ($0.request.chapterID, $0.status) }, uniquingKeysWith: { _, latest in latest })
        return List(selection: $selectedIDs) {
            if let downloadStatus { Text(downloadStatus).font(.footnote).foregroundStyle(.secondary) }
            if model.isLoading { ProgressView("正在更新章节…") }
            if let error = model.error { Text(error).foregroundStyle(.red) }
            if list.isEmpty {
                ContentUnavailableView(query.isEmpty ? "暂无章节" : "没有匹配的章节", systemImage: "list.bullet")
            }
            // Searching and selecting list chapters alone; otherwise a downloaded book gathers its chapters.
            ForEach(query.isEmpty && !selecting ? ChapterRow.rows(list, in: content) : list.map(ChapterRow.chapter)) { row in
                switch row {
                case .chapter(let chapter): chapterRow(chapter, statuses: statuses)
                case .book(let book, let chapters):
                    DisclosureGroup(isExpanded: Binding(get: { !collapsedBooks.contains(book.id) },
                                                        set: { if $0 { collapsedBooks.remove(book.id) } else { collapsedBooks.insert(book.id) } })) {
                        ForEach(chapters, id: \.id) { chapterRow($0, statuses: statuses) }
                    } label: {
                        ChapterBookLabel(book: book, count: chapters.count, status: statuses[book.id])
                    }
                    .accessibilityIdentifier("details.book.\(book.id)")
                }
            }
        }
        .listStyle(.plain)
        .accessibilityIdentifier("details.chapters.list")
    }

    private func chapterRow(_ chapter: IosSourceChapter, statuses: [String: String]) -> some View {
        // A book's chapters are already downloaded with it; an episode is always a single download.
        let downloadable = model.isVideo || content.bookOf(chapter: chapter) == nil
        return Group {
            if selecting {
                chapterLabel(chapter, status: statuses[chapter.id])
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("chapter.\(chapter.id)")
            } else {
                HStack(spacing: 12) {
                    Button {
                        let request = PlaybackRequest(content: content, chapter: chapter)
                        if model.isVideo { playing = request } else { reading = request }
                    } label: { chapterLabel(chapter, status: statuses[chapter.id]) }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("chapter.\(chapter.id)")
                    if !model.isVideo && !model.isNovel {
                        Button { preview = PlaybackRequest(content: content, chapter: chapter) } label: {
                            Image(systemName: "square.grid.2x2").frame(minWidth: 44, minHeight: 44)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("浏览\(chapter.title)的页面")
                        .accessibilityIdentifier("details.chapter.preview.\(chapter.id)")
                    }
                }
            }
        }
        .tag(chapter.id)
        .id(chapter.id)
        .listRowBackground(chapter.id == resume?.chapterId ? Color.accentColor.opacity(0.1) : nil)
        .contextMenu {
            if downloadable {
                Button(model.isVideo ? "下载本集" : "下载本章", systemImage: "arrow.down.circle") { enqueue([chapter]) }
            }
            Button(model.isVideo ? "选择剧集" : "选择章节", systemImage: "checkmark.circle") {
                selectedIDs = [chapter.id]
                editMode = .active
            }
            if !model.isVideo && !model.isNovel {
                Button("浏览本章页面", systemImage: "square.grid.2x2") {
                    preview = PlaybackRequest(content: content, chapter: chapter)
                }
            }
        }
        .swipeActions(edge: .trailing) {
            if downloadable && !selecting {
                Button("下载", systemImage: "arrow.down.circle") { enqueue([chapter]) }.tint(.accentColor)
            }
        }
    }

    private func chapterLabel(_ chapter: IosSourceChapter, status: String?) -> some View {
        // A book's chapters are marked once, on the book they are grouped under.
        DetailsChapterLabel(chapter: chapter, resume: resume, isRead: model.isBeforeResume(chapter, resume: resume),
                            isVideo: model.isVideo, downloadStatus: status,
                            offline: content.bookOf(chapter: chapter) == nil ? ChapterOfflineMark.of(content, chapter) : nil)
    }

    private func finishSelection() {
        editMode = .inactive
        selectedIDs.removeAll()
    }

    private func enqueue(_ chapters: [IosSourceChapter]) {
        guard !chapters.isEmpty else { return }
        Task {
            do {
                try await downloads.enqueue(content: content, chapters: chapters)
                downloadStatus = model.isVideo ? String(localized: "已加入下载队列：\(chapters.count) 集")
                    : String(localized: "已加入下载队列：\(chapters.count) 章")
                finishSelection()
            } catch { downloadError = error.localizedDescription }
        }
    }
}

struct DetailsChapterSortOptions: View {
    @Binding var order: String
    @Binding var newestFirst: Bool

    var body: some View {
        Picker("排序依据", selection: $order) {
            Text("源顺序").tag("source")
            Text("章节号").tag("number")
        }
        Picker("方向", selection: $newestFirst) {
            Text("最早在前").tag(false)
            Text("最新在前").tag(true)
        }
    }
}

struct DetailsChapterLabel: View {
    let chapter: IosSourceChapter
    let resume: IosReadingPosition?
    let isRead: Bool
    var isVideo = false
    var downloadStatus: String? = nil
    /// The downloaded book a chapter belongs to, where rows are not grouped under it.
    var book: String? = nil
    /// Readable offline: downloaded, or part of an imported work.
    var offline: ChapterOfflineMark? = nil

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(isVideo ? SourceDetailsView.episodeTitle(chapter) : chapter.title)
                    .font(chapter.id == resume?.chapterId ? .body.weight(.semibold) : .body)
                    .foregroundStyle(isRead ? .secondary : .primary)
                    .lineLimit(2)
                if let subtitle = book ?? SourceDetailsView.chapterSubtitle(chapter) {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                if let downloadStatus { Text(downloadStatus).font(.caption).foregroundStyle(.secondary) }
            }
            Spacer(minLength: 4)
            if let offline {
                Image(systemName: offline.icon).font(.caption).foregroundStyle(.secondary)
                    .accessibilityLabel(offline.label)
            }
            if chapter.id == resume?.chapterId, let resume {
                Text("\(Int(resume.progress * 100))%")
                    .font(.caption.monospacedDigit()).foregroundStyle(.tint)
            } else if isRead {
                Image(systemName: "checkmark").font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .contentShape(Rectangle())
    }
}

struct DetailsPagePreviewView: View {
    @ObservedObject var model: SourceDetailsModel
    let chapter: IosSourceChapter
    let resume: IosReadingPosition?
    let refreshRecords: () async -> Void
    @State private var reading: PlaybackRequest?

    var body: some View {
        ChapterThumbnailsView(runtime: model.runtime, details: model.details ?? model.content,
                              chapters: model.chapterList(query: "", newestFirst: false), initialChapter: chapter,
                              resume: resume) { selected, page in
            reading = PlaybackRequest(content: model.details ?? model.content, chapter: selected, initialPage: page)
        }
        .navigationTitle("页面预览")
        .detailsPresence()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .bottomBar, .tabBar)
        .navigationDestination(item: $reading) { DetailsReaderDestination(runtime: model.runtime, request: $0) }
        .onChange(of: reading == nil) { _, closed in if closed { Task { await refreshRecords() } } }
    }
}

struct DetailsBookmarksView: View {
    @ObservedObject var model: SourceDetailsModel
    @Binding var bookmarks: [IosBookmark]
    let refreshRecords: () async -> Void
    @EnvironmentObject private var library: LibraryModel
    @State private var reading: PlaybackRequest?
    @State private var error: String?

    var body: some View {
        List {
            if bookmarks.isEmpty { ContentUnavailableView("暂无书签", systemImage: "bookmark") }
            ForEach(bookmarks, id: \.id) { bookmark in
                Button {
                    guard let chapter = model.chapters.first(where: { $0.id == bookmark.chapterId }) else {
                        error = String(localized: "该书签对应的章节已不存在，请刷新章节或检查内容源。")
                        return
                    }
                    reading = PlaybackRequest(content: model.details ?? model.content, chapter: chapter,
                                              initialBookmark: bookmark)
                } label: {
                    BookmarkPreviewLabel(runtime: model.runtime, content: model.details ?? model.content,
                                         bookmark: bookmark, chapter: model.chapters.first { $0.id == bookmark.chapterId })
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("details.bookmark.\(bookmark.id)")
                .swipeActions {
                    Button("删除", systemImage: "trash", role: .destructive) {
                        Task {
                            do {
                                guard let database = library.library else { return }
                                try await database.removeBookmark(content: model.details ?? model.content,
                                                                  bookmark: bookmark)
                                if !model.isNovel {
                                    try? await BookmarkImages.remove(content: model.details ?? model.content, bookmark: bookmark)
                                }
                                await refreshRecords()
                            } catch { self.error = error.localizedDescription }
                        }
                    }
                }
            }
        }
        .navigationTitle("书签")
        .toolbar(.visible, for: .navigationBar)
        .detailsPresence()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .bottomBar, .tabBar)
        .navigationDestination(item: $reading) { DetailsReaderDestination(runtime: model.runtime, request: $0) }
        .onChange(of: reading == nil) { _, closed in if closed { Task { await refreshRecords() } } }
        .alert("无法操作书签", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("好") { error = nil }
        } message: { Text(error ?? "") }
    }
}

/// The player behind the incognito decision, like [DetailsReaderDestination].
struct VideoPlayerDestination: View {
    let runtime: IosSources
    let request: PlaybackRequest

    var body: some View {
        IncognitoGate(content: request.content) { incognito in
            VideoPlayerView(runtime: runtime, content: request.content, chapter: request.chapter, incognito: incognito)
        }
        .id(request.id)
    }
}

struct DetailsReaderDestination: View {
    let runtime: IosSources
    let request: PlaybackRequest

    var body: some View {
        IncognitoGate(content: request.content) { incognito in
            if request.content.mediaKind == "NOVEL" {
                NovelReaderView(runtime: runtime, content: request.content, chapter: request.chapter,
                                initialBookmark: request.initialBookmark, initialIndex: request.initialPage,
                                incognito: incognito)
            } else {
                SourceReaderView(runtime: runtime, content: request.content, chapter: request.chapter,
                                 initialPage: request.initialPage, initialBookmark: request.initialBookmark,
                                 incognito: incognito)
            }
        }
        .id(request.id)
    }
}

/// A chapter list row: a chapter, or a downloaded book gathering its own chapters (Android's EPUB volume groups).
enum ChapterRow: Identifiable {
    case chapter(IosSourceChapter)
    case book(IosSourceChapter, [IosSourceChapter])

    var id: String {
        switch self {
        case .chapter(let chapter): chapter.id
        case .book(let book, _): "book.\(book.id)"
        }
    }

    /// Consecutive chapters of one downloaded book become its group; everything else stays a plain row.
    static func rows(_ chapters: [IosSourceChapter], in content: IosSourceContent) -> [ChapterRow] {
        guard content.hasBooks else { return chapters.map(ChapterRow.chapter) }
        var rows: [ChapterRow] = []
        for chapter in chapters {
            guard let book = content.bookOf(chapter: chapter) else { rows.append(.chapter(chapter)); continue }
            if case .book(let last, let members)? = rows.last, last.id == book.id {
                rows[rows.count - 1] = .book(last, members + [chapter])
            } else {
                rows.append(.book(book, [chapter]))
            }
        }
        return rows
    }
}

struct ChapterBookLabel: View {
    let book: IosSourceChapter
    let count: Int
    var status: String? = nil

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "book.closed").foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 3) {
                Text(book.title).font(.body.weight(.semibold)).lineLimit(2)
                if let status { Text(status).font(.caption).foregroundStyle(.secondary) }
            }
            Spacer(minLength: 4)
            Image(systemName: ChapterOfflineMark.downloaded.icon).font(.caption).foregroundStyle(.secondary)
                .accessibilityLabel(ChapterOfflineMark.downloaded.label)
            Text("\(count) 章").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
        .frame(minHeight: 44)
    }
}

/// How a chapter reads without the network, shown beside it in chapter lists.
struct ChapterOfflineMark: Equatable {
    let icon: String
    let label: String

    static let downloaded = ChapterOfflineMark(icon: "arrow.down.circle.fill", label: String(localized: "已下载"))
    static let local = ChapterOfflineMark(icon: "internaldrive", label: String(localized: "本地"))

    /// Every chapter of an imported work is local; a source's chapter is marked once its download finished.
    static func of(_ content: IosSourceContent, _ chapter: IosSourceChapter) -> ChapterOfflineMark? {
        if content.isLocal { return .local }
        return ReaderDownloads.isDownloaded(content: content, chapter: chapter) ? .downloaded : nil
    }
}
