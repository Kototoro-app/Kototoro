import Combine
import KototoroShared
import SwiftUI

/// Details fetched this session, so reopening a work, or going back to it, shows its chapters at once while the
/// source is asked again. Long lists come from many sequential page requests (a 2,000-chapter novel takes about a
/// minute on the interpreter-only JVM), so waiting on every open is not acceptable.
@MainActor
enum DetailsCache {
    private static var entries: [String: IosSourceContent] = [:]
    private static var order: [String] = []
    private static let limit = 24

    private static func key(_ content: IosSourceContent) -> String { content.sourceName + "\n" + content.url }

    static func get(_ content: IosSourceContent) -> IosSourceContent? { entries[key(content)] }

    static func put(_ content: IosSourceContent) {
        let key = key(content)
        order.removeAll { $0 == key }
        order.append(key)
        entries[key] = content
        while order.count > limit { entries[order.removeFirst()] = nil }
    }
}

/// Both child screens borrow the browsing session; leaving a screen must not close the process VM.
@MainActor
final class SourceDetailsModel: ObservableObject {
    @Published private(set) var details: IosSourceContent? {
        didSet {
            chapters = details?.chapters ?? []
            rebuildChapterMetadata()
            rebuildVisibleChapters()
        }
    }
    /// Bridged once per details: every `IosSourceContent.chapters` read maps the whole list again.
    private(set) var chapters: [IosSourceChapter] = []
    private(set) var chapterIndices: [String: Int] = [:]
    private(set) var branches: [String?] = []
    private(set) var lines: [String?] = []

    private func rebuildChapterMetadata() {
        var indices: [String: Int] = [:]
        var seenBranches = Set<String?>()
        var branchList: [String?] = []
        var seenLines = Set<String?>()
        var lineList: [String?] = []

        for (idx, ch) in chapters.enumerated() {
            indices[ch.id] = idx
            if seenBranches.insert(ch.branch).inserted { branchList.append(ch.branch) }
            if seenLines.insert(ch.line).inserted { lineList.append(ch.line) }
        }
        chapterIndices = indices
        branches = branchList
        lines = lineList
    }
    @Published private(set) var error: String?
    /// The last failure was a browser challenge (Cloudflare): the error offers the in-app browser.
    @Published private(set) var challenge: IosChallenge?
    @Published private(set) var isLoading = false
    @Published var selectedLine: String? {
        didSet { rebuildVisibleChapters() }
    }
    @Published private(set) var visibleChapters: [IosSourceChapter] = []
    @Published private(set) var related: [IosSourceContent] = []
    let runtime: IosSources
    @Published private(set) var content: IosSourceContent
    private var detailsRequest: UUID?
    private var relatedRequest: UUID?
    /// The shown details came from the source in this screen, not from the cache or the saved record.
    private var fetched = false

    func loadRelated() async {
        guard related.isEmpty, relatedRequest == nil else { return }
        let request = UUID()
        relatedRequest = request
        defer { if relatedRequest == request { relatedRequest = nil } }
        let current = details ?? content
        let identity = ContentMarks.key(current)
        func suggestions(_ list: [IosSourceContent]) -> [IosSourceContent] {
            var seen: Set<String> = [identity]
            return list.filter { candidate in
                !(candidate.sourceName == current.sourceName && candidate.url == current.url) &&
                    seen.insert(ContentMarks.key(candidate)).inserted
            }
        }
        if let list = try? await runtime.related(content: current), !list.isEmpty {
            guard relatedIsCurrent(request, identity: identity) else { return }
            let filtered = suggestions(list)
            if !filtered.isEmpty {
                related = filtered
                return
            }
        }
        guard relatedIsCurrent(request, identity: identity) else { return }
        // Fallback: search works by the same author in the same source
        if let author = current.authors.first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) {
            if let list = try? await runtime.page(sourceName: current.sourceName, page: 0, loadedCount: 0, query: author, tag: nil) {
                guard relatedIsCurrent(request, identity: identity) else { return }
                let filtered = suggestions(list)
                if !filtered.isEmpty {
                    related = filtered
                    return
                }
            }
        }
        guard relatedIsCurrent(request, identity: identity) else { return }
        // Fallback: search works with the primary tag in the same source
        if let tag = current.tags.first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) {
            if let list = try? await runtime.page(sourceName: current.sourceName, page: 0, loadedCount: 0, query: tag, tag: nil) {
                guard relatedIsCurrent(request, identity: identity) else { return }
                let filtered = suggestions(list)
                if !filtered.isEmpty {
                    related = filtered
                    return
                }
            }
        }
    }

    private func relatedIsCurrent(_ request: UUID, identity: String) -> Bool {
        !Task.isCancelled && relatedRequest == request && ContentMarks.key(content) == identity
    }

    init(runtime: IosSources, content: IosSourceContent) {
        self.runtime = runtime
        self.content = content
    }

    @Published var selectedBranch: String?

    /// List chapters by the numbers the extension declared instead of the source's own order (the default, as Mihon
    /// lists them): some extensions misparse numbers from titles, which would scramble a number-sorted list.
    @Published var orderByNumber = false {
        didSet { rebuildVisibleChapters() }
    }

    /// Stable: chapters with equal numbers keep the source order.
    private func ordered(_ list: [IosSourceChapter]) -> [IosSourceChapter] {
        guard orderByNumber else { return list }
        return list.enumerated()
            .sorted { ($0.element.number, $0.offset) < ($1.element.number, $1.offset) }
            .map(\.element)
    }

    func chapterList(query: String, newestFirst: Bool) -> [IosSourceChapter] {
        var chapters = ordered(self.chapters)
        if branches.count > 1 { chapters = chapters.filter { $0.branch == selectedBranch } }
        let text = query.trimmingCharacters(in: .whitespaces)
        if !text.isEmpty { chapters = chapters.filter { $0.title.localizedCaseInsensitiveContains(text) } }
        return newestFirst ? chapters.reversed() : chapters
    }

    func isBeforeResume(_ chapter: IosSourceChapter, resume: IosReadingPosition?) -> Bool {
        guard let resume, let currentIndex = chapterIndices[resume.chapterId],
              let index = chapterIndices[chapter.id] else { return false }
        let current = chapters[currentIndex]
        guard chapter.branch == current.branch, chapter.line == current.line else { return false }
        return index < currentIndex
    }

    func refresh() async {
        let request = UUID()
        detailsRequest = request
        isLoading = true
        defer { if detailsRequest == request { isLoading = false } }
        let identity = ContentMarks.key(content)
        do {
            let fetchedResult = try await runtime.refreshDetails(content: details ?? content)
            guard !Task.isCancelled, detailsRequest == request, identity == ContentMarks.key(content) else { return }
            error = nil; challenge = nil
            let result = await settled(fetchedResult)
            guard detailsRequest == request else { return }
            apply(result); fetched = true
            if fetchedResult.chapterCount > 0 { DetailsCache.put(result) }
            await loadRelated()
        } catch {
            if !Task.isCancelled, detailsRequest == request, identity == ContentMarks.key(content) {
                fail(error)
                await showDownloadedChapters()
            }
        }
    }

    /// Invalidate the previous source's in-flight result before replacing the displayed work.
    func changeContent(_ target: IosSourceContent) {
        detailsRequest = nil
        relatedRequest = nil
        related = []
        isLoading = false
        content = target
        apply(target)
        fetched = true
        error = nil; challenge = nil
        DetailsCache.put(target)
        Task { await loadRelated() }
    }

    /// The queue whose finished downloads stand in for the chapter list when there is no other.
    weak var downloads: ReaderDownloadQueue?

    /**
     * Some sources answer a failed request with the work and no chapters. That keeps the list already shown (cached
     * or saved), or else lists the downloaded chapters, instead of emptying the directory.
     */
    private func settled(_ result: IosSourceContent) async -> IosSourceContent {
        guard result.chapterCount == 0 else { return result }
        if let shown = details, shown.chapterCount > 0 {
            error = String(localized: "来源没有返回章节，显示的是上次保存的目录")
            return result.withChaptersOf(other: shown)
        }
        guard let downloads, let offline = await DownloadedWorksModel.offlineContent(for: result, queue: downloads)
        else { return result }
        error = String(localized: "来源没有返回章节，仅显示已下载的章节")
        return result.withChaptersOf(other: offline)
    }

    /// Nothing listed and the source failed: the downloaded chapters stay readable.
    func showDownloadedChapters() async {
        guard (details?.chapterCount ?? 0) == 0, let downloads,
              let offline = await DownloadedWorksModel.offlineContent(for: details ?? content, queue: downloads),
              (details?.chapterCount ?? 0) == 0 else { return }
        apply((details ?? offline).withChaptersOf(other: offline))
    }

    /// Readable text, and whether the site asked for browser verification.
    private func fail(_ error: Error) {
        self.error = SourceErrorText.describe(error)
        challenge = SourceErrorText.challenge(error)
    }

    /// A book download finished or was removed: list it by its chapters, or as the download again.
    func relistBooks() {
        guard isNovel, let details else { return }
        let listed = NovelDownloads.listingBooks(details)
        if listed !== details { apply(listed) }
    }

    var isVideo: Bool { content.mediaKind == "VIDEO" }
    var isNovel: Bool { content.mediaKind == "NOVEL" }

    func rebuildVisibleChapters() {
        guard isVideo, lines.count > 1 else {
            visibleChapters = ordered(chapters)
            return
        }
        let line = lines.contains(selectedLine) ? selectedLine : lines.first ?? nil
        visibleChapters = ordered(chapters.filter { $0.line == line })
    }

    /// Shows this session's details, the record the screen was opened with (library, history) or the details saved
    /// when the work was last opened, before [load] asks the source; the fresh list replaces it when it arrives.
    func showCached(stored library: IosLibrary?) async {
        guard details == nil else { return }
        if let cached = DetailsCache.get(content) ?? (content.chapterCount > 0 ? content : nil) {
            apply(cached)
            Task { await loadRelated() }
        } else if let library, let saved = try? await library.storedDetails(content: content), details == nil {
            apply(saved)
            Task { await loadRelated() }
        } else {
            await showDownloadedChapters()
        }
    }

    /// Keeps the chosen line and branch when they still exist.
    private func apply(_ result: IosSourceContent) {
        details = NovelDownloads.listingBooks(result)
        if !lines.contains(selectedLine) { selectedLine = lines.first ?? nil }
        if !branches.contains(selectedBranch) { selectedBranch = branches.first ?? nil }
        rebuildVisibleChapters()
    }

    func load() async {
        guard !isLoading, !fetched || error != nil else { return }
        let request = UUID()
        detailsRequest = request
        isLoading = true
        error = nil; challenge = nil
        defer { if detailsRequest == request { isLoading = false } }
        do {
            let fetchedResult = try await runtime.details(content: content)
            try Task.checkCancellation()
            guard detailsRequest == request else { return }
            let result = await settled(fetchedResult)
            if detailsRequest == request {
                apply(result)
                fetched = true
                if fetchedResult.chapterCount > 0 { DetailsCache.put(result) }
                isLoading = false
                await loadRelated()
            }
        } catch {
            // A shown cached or saved list stays (offline reading); the error offers a retry above it.
            if !Task.isCancelled, detailsRequest == request {
                fail(error)
                await showDownloadedChapters()
            }
        }
    }
}

/// Android's details header folds a long title at three lines and opens it no further than six
/// (`DetailsHeader.detailsTitleMaxLines`), so a pathological title lengthens the header by three lines at most
/// instead of pushing the read button down the page. The rendered text uses Android's character budget as well;
/// the copy action still receives the whole title.
enum DetailsTitlePolicy {
    static let collapsedLines = 3
    static let expandedLines = 6
    /// Android keeps alternative titles at two lines, whatever the main title is doing.
    static let alternateTitleLines = 2
    static let renderedCharacterLimit = 1_000

    static func lineLimit(isExpanded: Bool) -> Int {
        isExpanded ? expandedLines : collapsedLines
    }

    static func rendered(_ title: String) -> String {
        String(title.prefix(renderedCharacterLimit))
    }
}

struct SourceDetailsView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var downloads: ReaderDownloadQueue
    @StateObject private var model: SourceDetailsModel
    @State private var playing: PlaybackRequest?
    @State private var reading: PlaybackRequest?
    @State private var isTitleExpanded = false
    @State private var isSummaryExpanded = false
    @State private var previewingCover = false
    @State private var toastMessage: String?
    @State private var destinationBrowse: IosSource?
    @State private var destinationDetails: IosSourceContent?
    @State private var browseInitialQuery: String?
    @State private var destinationGlobalSearchQuery: String?
    @State private var isFavourite = false
    @State private var savingFavourite = false
    @State private var recordError: String?
    @State private var resume: IosReadingPosition?
    @State private var editingCategories = false
    @State private var changingSource = false
    @State private var managingTracking = false
    @State private var showingReadingStats = false
    @State private var metadataContent: IosSourceContent?
    @State private var metadataRequest: UUID?
    private var shownContent: IosSourceContent { metadataContent ?? model.details ?? model.content }
    @State private var showingAllChapters = false
    @State private var timeLeft: String?
    @State private var showingBookmarks = false
    @State private var bookmarks: [IosBookmark] = []
    @AppStorage("details.newestFirst") private var newestFirst = false
    /// "source" (default) or "number"; see SourceDetailsModel.orderByNumber.
    @AppStorage("details.chapterOrder") private var chapterOrder = "source"
    @AppStorage(PanoramaKeys.enabled) private var panorama = true
    @AppStorage(TagFilterStore.warningsKey) private var tagWarnings = true
    @AppStorage(PanoramaKeys.layout) private var panoramaLayout = PanoramaLayout.top
    @State private var scrollOffset: CGFloat = 0
    @State private var headerReadVisible = true
    /// Opened from "continue reading": go straight on with the chapter being read, once.
    private let autoResume: Bool
    @State private var resumed = false

    init(runtime: IosSources, content: IosSourceContent, autoResume: Bool = false) {
        _model = StateObject(wrappedValue: SourceDetailsModel(runtime: runtime, content: content))
        self.autoResume = autoResume
    }

    private func resumeIfAsked() {
        guard autoResume, !resumed, let details = model.details,
              let target = resumeChapter() ?? model.chapters.first else { return }
        resumed = true
        if model.isVideo { playing = PlaybackRequest(content: details, chapter: target) }
        else { reading = PlaybackRequest(content: details, chapter: target) }
    }

    var body: some View {
        List {
            header
            if let recordError { Text(recordError).foregroundStyle(.red) }
            if model.isLoading && model.details == nil {
                ProgressView("正在读取详情…").frame(maxWidth: .infinity).listRowBackground(Color.clear)
                    .accessibilityIdentifier("details.loading")
            } else if model.isLoading {
                // A cached or saved list is already shown and usable; the source is being asked again.
                Label { Text("正在更新章节…") } icon: { ProgressView() }
                    .font(.footnote).foregroundStyle(.secondary)
                    .accessibilityIdentifier("details.updating")
            }
            if let error = model.error {
                Section {
                    Text(error).foregroundStyle(.red).accessibilityIdentifier("details.error")
                    if let challenge = model.challenge {
                        ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                              sourceName: model.content.sourceName) { await model.load() }
                    }
                    Button("重试") { Task { await model.load() } }.disabled(model.isLoading)
                }
            }
            if let details = model.details {
                chapterPreview(details)
                if !model.isVideo && !bookmarks.isEmpty { bookmarkPreview(details) }
                relatedSection
            }
        }
        .listStyle(.plain)
        .contentMargins(.horizontal, 16, for: .scrollContent)
        .listSectionSpacing(.compact)
        .scrollContentBackground(.hidden)
        .toolbar(.hidden, for: .tabBar)
        .toolbar(.visible, for: .navigationBar)
        .toolbar(headerReadVisible ? .hidden : .visible, for: .bottomBar)
        .detailsPresence()
        .onScrollGeometryChange(for: CGFloat.self, of: { $0.contentOffset.y + $0.contentInsets.top }) { _, offset in
            scrollOffset = offset
        }
        .refreshable { await refreshDetails() }
        .overlay(alignment: .top) {
            if let toast = toastMessage {
                Text(toast)
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.top, 10)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            withAnimation { toastMessage = nil }
                        }
                    }
            }
        }
        .backgroundPreferenceValue(DetailsCoverBoundsKey.self) { bounds in
            GeometryReader { geometry in
                backdrop(coverBottom: bounds.map { geometry[$0].maxY + scrollOffset })
            }
            .ignoresSafeArea()
        }
        // Over the panorama the bar stays clear; the title appears once the header has scrolled away.
        .navigationTitle(panorama && scrollOffset < 170 ? "" : shownContent.title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            model.downloads = downloads
            await refreshMetadata()
            // Continuing from the library or history needs no fresh list: resume from the saved one at once.
            await model.showCached(stored: library.library)
            if model.details != nil {
                await refreshRecords()
                resumeIfAsked()
            }
            await sources.recordUsage(sourceName: model.content.sourceName)
            await model.load()
            await refreshRecords()
            await sources.refreshSettings()
            resumeIfAsked()
        }
        .onAppear {
            library.recordSpaceVisit(shownContent)
            model.orderByNumber = chapterOrder == "number"
            // Downloads may have been removed in storage management meanwhile.
            model.relistBooks()
        }
        .sheet(isPresented: $showingReadingStats) {
            ContentReadingStatsSheet(content: shownContent, runtime: model.runtime)
        }
        .onChange(of: chapterOrder) { _, order in withAnimation { model.orderByNumber = order == "number" } }
        // A finished or removed book download lists the book by its chapters, or as the download again.
        .onChange(of: finishedDownloads) { _, _ in model.relistBooks() }
        .toolbar {
            ToolbarItem(placement: .bottomBar) {
                Button(model.isVideo ? "剧集" : "章节", systemImage: "list.bullet") { showingAllChapters = true }
                    .disabled(model.chapters.isEmpty)
                    .accessibilityIdentifier("details.chapters.all")
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                primaryReadButton(identifier: "details.read")
                    .buttonStyle(.glassProminent)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    showingReadingStats = true
                } label: {
                    Label("阅读统计", systemImage: "chart.bar.xaxis")
                }
                .accessibilityIdentifier("details.readingStats")
                if let url = URL(string: model.content.publicUrl), url.scheme?.hasPrefix("http") == true {
                    ShareLink(item: url) { Label("分享", systemImage: "square.and.arrow.up") }
                }
                Menu("更多", systemImage: "ellipsis") {
                    Button("阅读统计", systemImage: "chart.bar.xaxis") { showingReadingStats = true }
                    Button("追踪与资料", systemImage: "link") { managingTracking = true }
                        .disabled(library.library == nil)
                        .accessibilityIdentifier("details.tracking")
                    Button("换源", systemImage: "arrow.triangle.swap") { changingSource = true }
                        .disabled(library.library == nil || (model.details == nil && model.isLoading))
                        .accessibilityIdentifier("details.changeSource")
                    if let url = URL(string: model.content.publicUrl), url.scheme?.hasPrefix("http") == true {
                        Button("在浏览器中打开", systemImage: "safari") { UIApplication.shared.open(url) }
                    }
                    if isFavourite { Button("整理分类", systemImage: "folder") { editingCategories = true } }
                }
                .accessibilityIdentifier("details.more")
            }
        }
        .sheet(isPresented: $changingSource) {
            SourceChangeView(runtime: model.runtime, content: model.details ?? model.content) { target in
                model.changeContent(target)
                metadataContent = nil
                recordError = nil
                changingSource = false
                Task { await refreshRecords() }
            }
        }
        .sheet(isPresented: $managingTracking) {
            NavigationStack {
                TrackingSitesView(content: model.details ?? model.content) {
                    Task {
                        await refreshMetadata()
                        managingTracking = false
                        toastMessage = shownContent.metadataSiteTitle.map { String(localized: "已使用 \($0) 资料") } ?? String(localized: "已恢复内容源资料")
                    }
                }
            }
        }
        .sheet(isPresented: $editingCategories, onDismiss: { Task { await refreshRecords() } }) {
            FavouriteCategoriesView(content: model.details ?? model.content)
        }
        .sheet(isPresented: $previewingCover) {
            CoverPreviewSheet(runtime: model.runtime, content: shownContent)
        }
        .navigationDestination(item: $destinationBrowse) { src in
            SourceBrowseView(runtime: model.runtime, source: src, initialQuery: browseInitialQuery)
        }
        .navigationDestination(item: $destinationDetails) { target in
            SourceDetailsView(runtime: model.runtime, content: target)
        }
        .navigationDestination(item: $destinationGlobalSearchQuery) { q in
            GlobalSearchView(query: .constant(q))
        }
        .onChange(of: reading == nil) { _, closed in if closed { Task { await refreshRecords() } } }
        .navigationDestination(isPresented: $showingAllChapters) {
            DetailsChaptersView(model: model, resume: $resume, refresh: refreshDetails, refreshRecords: refreshRecords)
        }
        .navigationDestination(isPresented: $showingBookmarks) {
            DetailsBookmarksView(model: model, bookmarks: $bookmarks, refreshRecords: refreshRecords)
        }
        .navigationDestination(item: $reading) { DetailsReaderDestination(runtime: model.runtime, request: $0) }
        .task(id: "\(model.details?.chapterCount ?? 0)|\(resume?.chapterId ?? "")|\(reading == nil)") { await loadTimeLeft() }
        .fullScreenCover(item: $playing, onDismiss: { Task { await refreshRecords() } }) { request in
            VideoPlayerDestination(runtime: model.runtime, request: request)
        }
    }

    private func removeFavourite() {
        Task {
            savingFavourite = true
            defer { savingFavourite = false }
            do {
                try await library.setFavourite(model.details ?? model.content, favourite: false)
                isFavourite = false
            } catch { recordError = error.localizedDescription }
        }
    }

    private func refreshDetails() async {
        await model.refresh()
        await refreshRecords()
        await sources.refreshSettings()
    }

    private var finishedDownloads: [String] {
        guard model.isNovel else { return [] }
        return downloads.entries.filter {
            $0.state == .completed && $0.request.source == model.content.sourceName && $0.request.contentID == model.content.id
        }.map(\.id)
    }

    private func refreshRecords() async {
        await refreshMetadata()
        guard let content = model.details, let database = library.library else { return }
        do {
            try await database.saveDetails(content: content)
            if !Task.isCancelled { library.recordSpaceVisit(shownContent) }
            isFavourite = try await database.isFavourite(content: content).boolValue
            resume = try await database.position(content: content)
            bookmarks = try await database.bookmarks(content: content)
            // Open the chapter list on the branch being read.
            if let chapter = resumeChapter(), model.branches.contains(chapter.branch) { model.selectedBranch = chapter.branch }
        } catch { recordError = error.localizedDescription }
    }

    private func refreshMetadata() async {
        guard let tracking = library.library?.tracking else { return }
        let request = UUID()
        metadataRequest = request
        let original = model.details ?? model.content
        do {
            let result = try await tracking.decorate(content: original)
            guard original.id == model.content.id, metadataRequest == request else { return }
            metadataContent = result
        } catch { recordError = error.localizedDescription }
    }

    private var header: some View {
        let shown = shownContent
        return Section {
            VStack(alignment: .leading, spacing: 14) {
                coverRow(shown)
                GlassEffectContainer(spacing: 10) {
                    HStack(spacing: 10) {
                        primaryReadButton(identifier: "details.header.read")
                            .buttonStyle(.glassProminent)
                            .controlSize(.large)
                            .frame(maxWidth: .infinity)
                        if resumeChapter() != nil {
                            Menu {
                                if let details = model.details,
                                   let first = model.isVideo ? model.visibleChapters.first : model.chapters.first {
                                    Button(model.isVideo ? "从第一集开始播放" : "从第一章开始阅读") {
                                        openChapter(first, from: details, initialPage: 0)
                                    }
                                }
                            } label: {
                                Image(systemName: "chevron.down")
                                    .font(.body.weight(.semibold))
                                    .frame(minWidth: 24, minHeight: 24)
                            }
                            .accessibilityLabel("阅读选项")
                            .accessibilityIdentifier("details.readOptions")
                            .menuIndicator(.hidden)
                            .buttonStyle(.glass)
                            .buttonBorderShape(.circle)
                            .controlSize(.large)
                            .disabled(model.chapters.isEmpty)
                        }
                    }
                }
                .onScrollVisibilityChange(threshold: 0.01) { headerReadVisible = $0 }
                infoCard(shown)
                summaryCard(shown)
                TrackingSyncBanner(content: model.details ?? model.content, tracking: library.library?.tracking,
                                   model: library.trackingAccounts)
            }
            .padding(.vertical, 6)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
        }
        .listRowSeparator(.hidden)
    }

    private func coverRow(_ shown: IosSourceContent) -> some View {
        HStack(alignment: .top, spacing: 14) {
            CoverImage(runtime: model.runtime, content: shown)
                .frame(width: 112, height: 168)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.18), lineWidth: 0.5)
                )
                .shadow(color: .black.opacity(0.22), radius: 8, y: 3)
                .accessibilityLabel("封面")
                .accessibilityIdentifier("details.cover")
                .anchorPreference(key: DetailsCoverBoundsKey.self, value: .bounds) { $0 }
                .onTapGesture { previewingCover = true }

            VStack(alignment: .leading, spacing: 8) {
                // Tapping toggles between the folded three lines and at most six, like Android; a title that needs
                // more stays ellipsized in both states, so it can never expand the header without bound. The text is
                // not selectable: `textSelection` installs the text interaction that swallows this tap on a device,
                // and the copy button already hands over the whole title. The stretched frame keeps the empty space
                // to the right of a short last line tappable.
                let title = DetailsTitlePolicy.rendered(shown.title)
                Text(title)
                    .font(.title3.weight(.bold))
                    .lineSpacing(2)
                    .lineLimit(DetailsTitlePolicy.lineLimit(isExpanded: isTitleExpanded))
                    // Take the height the wrapped text needs at the proposed width. Without this the header lays the
                    // text out on one line and ellipsizes it, so a two- or three-line title could never be read.
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(.rect)
                    .onTapGesture {
                        withAnimation(.snappy) { isTitleExpanded.toggle() }
                    }
                    .accessibilityHint(isTitleExpanded ? Text("收起") : Text("展开"))
                    .accessibilityIdentifier("details.title")
                    // Applying tracking metadata replaces the title; the new one starts folded, as in Android.
                    .onChange(of: title) { _, _ in isTitleExpanded = false }

                if let alt = shown.altTitles.first, !alt.isEmpty {
                    Text(alt)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(DetailsTitlePolicy.alternateTitleLines)
                        // Same reason as the main title: the header column would otherwise keep one line only.
                        .fixedSize(horizontal: false, vertical: true)
                }

                if let src = sources.sources.first(where: { $0.name == shown.sourceName }) {
                    Button {
                        browseInitialQuery = nil
                        destinationBrowse = src
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "globe")
                                .font(.caption2)
                            Text(src.title)
                                .font(.caption.weight(.medium))
                            Image(systemName: "chevron.right")
                                .font(.system(size: 8, weight: .bold))
                        }
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(Color(.secondarySystemFill), in: .capsule)
                        .foregroundStyle(.primary)
                    }
                    .buttonStyle(.plain)
                }

                Button { managingTracking = true } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "text.book.closed").font(.caption2)
                        Text(shown.metadataSiteTitle.map { "追踪 · \($0)" } ?? "追踪与资料").font(.caption.weight(.medium))
                        Image(systemName: "chevron.right").font(.system(size: 8, weight: .bold))
                    }
                    .padding(.horizontal, 9).padding(.vertical, 4)
                    .background(Color.accentColor.opacity(0.1), in: .capsule)
                }
                .buttonStyle(.plain)
                .disabled(library.library == nil)
                .accessibilityIdentifier("details.metadata")

                // Quick actions below the source and metadata labels.
                GlassEffectContainer(spacing: 8) {
                    HStack(spacing: 8) {
                        Button("复制标题", systemImage: "doc.on.doc") {
                            UIPasteboard.general.string = shown.title
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            toastMessage = String(localized: "已复制标题")
                        }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.glass)
                        .buttonBorderShape(.circle)
                        .tint(.primary)

                        Button(isFavourite ? "已收藏" : "收藏", systemImage: isFavourite ? "heart.fill" : "heart") {
                            if isFavourite { removeFavourite() }
                            else { editingCategories = true }
                        }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.glass)
                        .buttonBorderShape(.circle)
                        .tint(isFavourite ? .pink : .primary)
                        .contextMenu {
                            Button("收藏分类", systemImage: "folder") { editingCategories = true }
                            if isFavourite {
                                Button("取消收藏", systemImage: "heart.slash", role: .destructive, action: removeFavourite)
                            }
                        }
                        .accessibilityIdentifier("details.favourite")

                        if shown.rating > 0 {
                            Label(String(format: "%.1f", Double(shown.rating) * 5), systemImage: "star.fill")
                                .font(.caption.weight(.semibold)).foregroundStyle(.orange)
                                .padding(.horizontal, 8).padding(.vertical, 5)
                                .background(Color.orange.opacity(0.12), in: .capsule)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func authorItem(_ shown: IosSourceContent) -> some View {
        Group {
            if !shown.authors.isEmpty {
                Menu {
                    ForEach(shown.authors, id: \.self) { author in
                        Section(author) {
                            Button("在当前源搜索【\(author)】") {
                                if let src = sources.sources.first(where: { $0.name == shown.sourceName }) {
                                    destinationBrowse = src
                                    browseInitialQuery = author
                                }
                            }
                            Button("在全部源搜索【\(author)】") {
                                destinationGlobalSearchQuery = author
                            }
                        }
                    }
                } label: {
                    Label(shown.authors.joined(separator: "、"), systemImage: "person.circle")
                        .lineLimit(1)
                }
                .buttonStyle(.plain)
            } else {
                Label("佚名", systemImage: "person.circle").foregroundStyle(.secondary)
            }
        }
    }

    /// Android's info items keep the state row when the source has none and dim it as unknown; the panel keeps the
    /// same shape here instead of dropping rows the source cannot fill.
    private func stateItem(_ shown: IosSourceContent) -> some View {
        Group {
            if let state = shown.state {
                Label {
                    Text(Self.stateTitle(state))
                } icon: {
                    Image(systemName: Self.stateSymbol(state)).foregroundStyle(Self.stateTint(state))
                }
            } else {
                Label("状态未知", systemImage: "questionmark.circle").foregroundStyle(.secondary)
            }
        }
    }

    /// Author, state, kind and chapters in two columns; every item is a `DetailsInfoLabelStyle` label, so icons share
    /// one size, column width and rendering.
    private func infoCard(_ shown: IosSourceContent) -> some View {
        VStack(spacing: 10) {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 10) {
                GridRow {
                    authorItem(shown).frame(maxWidth: .infinity, alignment: .leading)
                    stateItem(shown).frame(maxWidth: .infinity, alignment: .leading)
                }
                GridRow {
                    Label(Self.mediaKindTitle(shownContent.mediaKind), systemImage: Self.mediaKindSymbol(shownContent.mediaKind))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    chapterInfo.frame(maxWidth: .infinity, alignment: .leading)
                }
                if let timeLeft {
                    GridRow {
                        // Android's estimated time left (shared rule; manga only).
                        Label(timeLeft, systemImage: "clock")
                            .foregroundStyle(.secondary)
                            .gridCellColumns(2)
                            .accessibilityIdentifier("details.timeLeft")
                    }
                }
            }
            .labelStyle(DetailsInfoLabelStyle())
            .font(.subheadline)

            if let resume = resume, resume.progress > 0 {
                HStack(spacing: 8) {
                    ProgressView(value: Double(resume.progress))
                        .tint(Color.accentColor)
                    Text("\(Int(resume.progress * 100))%")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 2)
            }
        }
        .padding(16)
        .modifier(DetailsContentPanel())
    }

    /// Android's header shows the same reading progress as `currentChapter + 1 / total` (`0/N` before the first
    /// chapter), and always includes it, video included. The count follows the listed chapters: the chosen line for
    /// video, all chapters otherwise, so the value matches the directory heading below.
    /// "约 3 小时 20 分钟" left, from the shared estimate; updated with the chapters and the reading position.
    private func loadTimeLeft() async {
        guard let library = library.library, model.details != nil else { timeLeft = nil; return }
        let content = model.details ?? model.content
        let progress = (try? await library.position(content: content)).map { $0.progress }
        guard let seconds = try? await library.readingTimeLeft(content: content, progress: progress.map { KotlinFloat(value: $0) })
        else { timeLeft = nil; return }
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = seconds.int64Value >= 3600 ? [.hour, .minute] : [.minute]
        formatter.unitsStyle = .short
        timeLeft = formatter.string(from: TimeInterval(seconds.int64Value)).map { String(localized: "剩余约 \($0)") }
    }

    private var chapterInfo: some View {
        let listed = model.isVideo ? model.visibleChapters : model.chapters
        let totalChapters = listed.count
        let readChapter = resume.flatMap { position in
            listed.firstIndex { $0.id == position.chapterId }
        }.map { $0 + 1 } ?? 0
        let progressText = totalChapters > 0 ? "\(readChapter)/\(totalChapters)" : "-"
        return Group {
            if totalChapters > 0 {
                Button {
                    showingAllChapters = true
                } label: {
                    Label {
                        HStack(spacing: 4) {
                            Text(progressText).monospacedDigit()
                            Image(systemName: "chevron.right")
                                .font(.caption2.weight(.semibold)).foregroundStyle(.tertiary)
                        }
                    } icon: {
                        Image(systemName: "list.bullet.circle")
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("全部\(model.isVideo ? "剧集" : "章节")，\(progressText)")
            } else {
                Label(progressText, systemImage: "list.bullet.circle").foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func summaryCard(_ shown: IosSourceContent) -> some View {
        let summary = shown.summary.map(Self.plainText) ?? ""
        let hasSummary = !summary.isEmpty
        if hasSummary || !shown.tags.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("简介")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.primary)

                if hasSummary {
                    Text(summary)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineSpacing(3)
                        .lineLimit(isSummaryExpanded ? nil : 3)
                        .textSelection(.enabled)
                        .onTapGesture { withAnimation { isSummaryExpanded.toggle() } }
                        .accessibilityIdentifier("details.summary")

                    if summary.count > 100 || summary.filter({ $0 == "\n" }).count >= 3 {
                        Button(isSummaryExpanded ? "收起" : "展开") {
                            withAnimation { isSummaryExpanded.toggle() }
                        }
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.accentColor)
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("details.summary.toggle")
                    }
                }

                if !shown.tags.isEmpty {
                    FlowLayout(spacing: 6) {
                        ForEach(shown.tags, id: \.self) { tag in
                            // Android's sensitive-category highlight; a long press hides works with the tag.
                            let warned = tagWarnings && IosTags.shared.isWarned(title: tag)
                            Text(tag)
                                .font(.caption)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(warned ? AnyShapeStyle(Color.orange.opacity(0.18))
                                                   : AnyShapeStyle(Color(.tertiarySystemFill)), in: .capsule)
                                .foregroundStyle(warned ? Color.orange : Color.primary)
                                .accessibilityIdentifier(warned ? "details.tag.warning" : "details.tag")
                                .contextMenu {
                                    Button("加入标签黑名单", systemImage: "eye.slash") {
                                        TagFilterStore.shared.add(title: tag)
                                    }
                                }
                        }
                    }
                    .padding(.top, 2)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .modifier(DetailsContentPanel())
        }
    }

    /// Android's panorama cover at the top (or the softly blurred cover when it is turned off), faded into the
    /// grouped background.
    private func backdrop(coverBottom: CGFloat?) -> some View {
        ZStack(alignment: .top) {
            Color(uiColor: .systemGroupedBackground)
            if panorama {
                DetailsPanorama(runtime: model.runtime, content: shownContent,
                                scrollOffset: scrollOffset, coverBottom: coverBottom)
            } else {
                CoverImage(runtime: model.runtime, content: shownContent, plain: true)
                    .frame(height: 420).frame(maxWidth: .infinity)
                    .blur(radius: 50).opacity(0.5)
                    .mask(LinearGradient(colors: [.black, .black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom))
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var relatedSection: some View {
        if !model.related.isEmpty {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Text("相关推荐")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.primary)

                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 12) {
                            ForEach(model.related, id: \.id) { item in
                                Button {
                                    destinationDetails = item
                                } label: {
                                    VStack(alignment: .leading, spacing: 6) {
                                        CoverImage(runtime: model.runtime, content: item)
                                            .frame(width: 96, height: 140)
                                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                            .overlay(
                                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                                    .strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5)
                                            )
                                            .shadow(color: .black.opacity(0.12), radius: 4, y: 2)

                                        Text(item.title)
                                            .font(.caption)
                                            .lineLimit(2, reservesSpace: true)
                                            .frame(width: 96, alignment: .topLeading)
                                            .foregroundStyle(.primary)
                                    }
                                    .frame(width: 96, alignment: .top)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
                .padding(.vertical, 6)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
            .listRowSeparator(.hidden)
        }
    }

    private var primaryTarget: IosSourceChapter? {
        resumeChapter() ?? (model.isVideo ? model.visibleChapters.first : model.chapters.first)
    }

    private func primaryReadButton(identifier: String) -> some View {
        let title = resumeChapter() != nil ? (model.isVideo ? String(localized: "继续播放") : String(localized: "继续阅读"))
            : (model.isVideo ? String(localized: "开始播放") : String(localized: "开始阅读"))
        return Button {
            if let details = model.details, let target = primaryTarget { openChapter(target, from: details) }
        } label: {
            if identifier == "details.header.read" {
                // One line: stacking the chapter under the title made this button taller than the
                // options circle beside it, so the pair read as two different thicknesses. The
                // chapter keeps its place inline and truncates instead of wrapping.
                HStack(spacing: 6) {
                    Text(title).font(.headline)
                    if let chapter = resumeChapter() {
                        Text(chapter.title).font(.caption).opacity(0.85)
                    }
                }
                .lineLimit(1)
                .minimumScaleFactor(0.85)
                .frame(maxWidth: .infinity, minHeight: 24, alignment: .center)
            } else {
                Label(title, systemImage: model.isVideo ? "play.fill" : "book.fill")
                    .labelStyle(.iconOnly)
            }
        }
        .disabled(model.details == nil || primaryTarget == nil)
        .accessibilityIdentifier(identifier)
    }

    private func openChapter(_ chapter: IosSourceChapter, from details: IosSourceContent, initialPage: Int? = nil) {
        if model.isVideo { playing = PlaybackRequest(content: details, chapter: chapter) }
        else { reading = PlaybackRequest(content: details, chapter: chapter, initialPage: initialPage) }
    }

    private func chapterPreview(_ details: IosSourceContent) -> some View {
        let all = model.isVideo ? (newestFirst ? Array(model.visibleChapters.reversed()) : model.visibleChapters)
            : model.chapterList(query: "", newestFirst: newestFirst)
        let preview = DetailsChapterPreview.items(all, currentID: resume?.chapterId, id: { $0.id },
                                                 limit: model.isVideo ? 8 : 5)
        return Section {
            VStack(alignment: .leading, spacing: model.isVideo ? 8 : 12) {
                HStack(spacing: 12) {
                    Text(model.isVideo ? "剧集" : "章节").font(.headline)
                    Spacer(minLength: 0)
                    if model.isVideo && model.lines.count > 1 {
                        Picker("线路", selection: $model.selectedLine) {
                            ForEach(model.lines, id: \.self) { Text($0 ?? String(localized: "默认")).tag($0) }
                        }
                        .labelsHidden()
                        .font(.subheadline.weight(.medium))
                        .accessibilityIdentifier("details.line")
                    } else if !model.isVideo && model.branches.count > 1 {
                        Picker("分支", selection: $model.selectedBranch) {
                            ForEach(model.branches, id: \.self) { Text($0 ?? String(localized: "默认")).tag($0) }
                        }
                    }
                    Menu("排序", systemImage: "arrow.up.arrow.down") {
                        DetailsChapterSortOptions(order: $chapterOrder, newestFirst: $newestFirst)
                    }
                    .labelStyle(.iconOnly)
                    .accessibilityIdentifier("details.sort")
                }
                .buttonStyle(.borderless)
                .pickerStyle(.menu)
                .tint(model.isVideo ? .primary : .accentColor)

                if preview.isEmpty {
                    Text("暂无章节").foregroundStyle(.secondary).accessibilityIdentifier("details.empty")
                } else if model.isVideo && !Self.hasLongTitles(all) {
                    ForEach(Self.rows(Array(preview)), id: \.first?.id) { row in
                        HStack(spacing: 10) {
                            ForEach(row, id: \.id) { chapter in
                                episodeButton(chapter, from: details)
                            }
                            ForEach(row.count..<Self.rowSize, id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
                        }
                    }
                } else {
                    VStack(spacing: 0) {
                        ForEach(preview, id: \.id) { chapter in
                            if chapter.id != preview.first?.id { Divider() }
                            Button { openChapter(chapter, from: details) } label: {
                                DetailsChapterLabel(chapter: chapter, resume: resume,
                                                    isRead: model.isBeforeResume(chapter, resume: resume), isVideo: model.isVideo,
                                                    book: details.bookOf(chapter: chapter)?.title,
                                                    offline: model.isVideo ? nil : ChapterOfflineMark.of(details, chapter))
                                    .padding(.vertical, 4)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("chapter.\(chapter.id)")
                        }
                    }
                }
                Divider()
                Button { showingAllChapters = true } label: {
                    HStack {
                        Text(model.isVideo ? "全部 \(all.count) 集" : "全部 \(all.count) 章")
                            .font(.subheadline.weight(model.isVideo ? .medium : .semibold))
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .tint(model.isVideo ? .primary : .accentColor)
                .accessibilityIdentifier("details.chapters.showAll")
            }
            .padding(16)
            .modifier(DetailsContentPanel())
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 10, trailing: 0))
        }
        .listRowSeparator(.hidden)
    }

    private func episodeButton(_ chapter: IosSourceChapter, from details: IosSourceContent) -> some View {
        let current = chapter.id == resume?.chapterId
        return Button { openChapter(chapter, from: details) } label: {
            Text(Self.episodeTitle(chapter))
                .font(.subheadline.weight(current ? .semibold : .medium))
                .monospacedDigit()
                .lineLimit(1)
                .foregroundStyle(current ? Color.accentColor : .primary)
                .frame(maxWidth: .infinity, minHeight: 36)
                .background(current ? Color.accentColor.opacity(0.12) : Color(.tertiarySystemFill).opacity(0.6),
                            in: .rect(cornerRadius: 10))
                .overlay {
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(current ? Color.accentColor.opacity(0.45) : Color(.separator).opacity(0.15),
                                      lineWidth: 0.5)
                }
                .frame(minHeight: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(current ? .isSelected : [])
        .accessibilityIdentifier("chapter.\(chapter.id)")
    }

    private func bookmarkPreview(_ details: IosSourceContent) -> some View {
        Section {
            ScrollView(.horizontal) {
                HStack(spacing: 12) {
                    ForEach(bookmarks.prefix(5), id: \.id) { bookmark in
                        Button {
                            if let chapter = model.chapters.first(where: { $0.id == bookmark.chapterId }) {
                                reading = PlaybackRequest(content: details, chapter: chapter, initialBookmark: bookmark)
                            } else {
                                toastMessage = String(localized: "书签对应的章节已不存在，请刷新章节或检查内容源。")
                            }
                        } label: {
                            BookmarkPreviewLabel(runtime: model.runtime, content: details, bookmark: bookmark,
                                                 chapter: model.chapters.first { $0.id == bookmark.chapterId })
                                .frame(width: 220, alignment: .leading)
                                .padding(12)
                                .modifier(DetailsContentPanel(cornerRadius: 16))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("details.bookmark.\(bookmark.id)")
                    }
                }
            }
            .scrollIndicators(.hidden)
            .accessibilityIdentifier("details.bookmarks.preview")
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
            .listRowSeparator(.hidden)
        } header: {
            HStack {
                Text("书签（\(bookmarks.count)）")
                Spacer()
                Button("全部", systemImage: "chevron.right") { showingBookmarks = true }
                    .accessibilityIdentifier("details.bookmarks.all")
            }
            .textCase(nil)
        }
    }

    private func pill(_ text: String, tint: Color) -> some View {
        Text(text).font(.caption2.weight(.semibold))
            .padding(.horizontal, 8).padding(.vertical, 4)
            .foregroundStyle(tint)
            .background(tint.opacity(0.15), in: .capsule)
    }

    private func resumeChapter() -> IosSourceChapter? {
        guard let resume else { return nil }
        return model.chapters.first { $0.id == resume.chapterId }
    }

    static func mediaKindTitle(_ kind: String) -> String {
        switch kind {
        case "MANGA": String(localized: "漫画")
        case "NOVEL": String(localized: "小说")
        case "VIDEO": String(localized: "视频")
        default: kind
        }
    }

    static func stateTitle(_ state: String) -> String {
        switch state {
        case "ONGOING": String(localized: "连载中")
        case "FINISHED": String(localized: "已完结")
        case "ABANDONED": String(localized: "已放弃")
        case "PAUSED": String(localized: "暂停更新")
        case "UPCOMING": String(localized: "即将推出")
        case "RESTRICTED": String(localized: "受限")
        default: state
        }
    }

    static func stateSymbol(_ state: String) -> String {
        switch state {
        case "ONGOING": "play.circle"
        case "FINISHED": "checkmark.circle"
        case "ABANDONED": "xmark.circle"
        case "PAUSED": "pause.circle"
        case "UPCOMING": "calendar.circle"
        case "RESTRICTED": "lock.circle"
        default: "questionmark.circle"
        }
    }

    static func mediaKindSymbol(_ kind: String) -> String {
        switch kind {
        case "NOVEL": "book.closed.circle"
        case "VIDEO": "tv.circle"
        default: "book.circle"
        }
    }

    static func stateTint(_ state: String) -> Color {
        switch state {
        case "ONGOING": .green
        case "FINISHED": .blue
        case "ABANDONED", "RESTRICTED": .red
        default: .orange
        }
    }

    static func chapterSubtitle(_ chapter: IosSourceChapter) -> String? {
        var parts: [String] = []
        if let scanlator = chapter.scanlator { parts.append(scanlator) }
        if chapter.uploadDate > 0 {
            parts.append(Date(timeIntervalSince1970: Double(chapter.uploadDate) / 1000).formatted(date: .abbreviated, time: .omitted))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    static let rowSize = 4

    static func hasLongTitles(_ chapters: [IosSourceChapter]) -> Bool {
        chapters.prefix(50).contains { episodeTitle($0).count > 7 }
    }

    static func rows(_ chapters: [IosSourceChapter]) -> [ArraySlice<IosSourceChapter>] {
        stride(from: 0, to: chapters.count, by: rowSize).map { chapters[$0..<min($0 + rowSize, chapters.count)] }
    }

    /// Episode titles repeat the line name ("线路A - 第1集"); the picker already shows it.
    static func episodeTitle(_ chapter: IosSourceChapter) -> String {
        guard let line = chapter.line, chapter.title.hasPrefix("\(line) - ") else { return chapter.title }
        return String(chapter.title.dropFirst(line.count + 3))
    }

    /// CMS descriptions are often HTML fragments.
    static func plainText(_ html: String) -> String {
        guard html.contains("<") || html.contains("&") else {
            return html.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return html.replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: .regularExpression)
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Content stays on standard material; Liquid Glass is reserved for the controls above it.
private struct DetailsContentPanel: ViewModifier {
    var cornerRadius: CGFloat = 22
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        content
            .background {
                if reduceTransparency {
                    shape.fill(Color(uiColor: .secondarySystemGroupedBackground))
                } else {
                    shape.fill(.regularMaterial)
                }
            }
            .overlay {
                shape.strokeBorder(Color.primary.opacity(contrast == .increased ? 0.2 : 0.06), lineWidth: 0.5)
            }
    }
}

/// An item of the details info card: the icon at the text's size in a fixed column, secondary unless the item colors
/// it (the state), so every item's icon has the same size, alignment and weight.
struct DetailsInfoLabelStyle: LabelStyle {
    @ScaledMetric(relativeTo: .subheadline) private var iconWidth: CGFloat = 20

    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.icon
                .imageScale(.medium)
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(.secondary)
                .frame(width: iconWidth)
            configuration.title
                .fontWeight(.medium)
        }
    }
}

/// One opening of a reader or player. Each opening is its own identity: reopening the chapter just closed must build
/// a new reader, which reads the saved position again, rather than get back the previous reader's state.
struct PlaybackRequest: Identifiable, Hashable {
    let content: IosSourceContent
    let chapter: IosSourceChapter
    var initialPage: Int? = nil
    var initialBookmark: IosBookmark? = nil
    let id = UUID()
    static func == (lhs: Self, rhs: Self) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

struct CoverPreviewSheet: View {
    let runtime: IosSources
    let content: IosSourceContent
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ZStack {
                    Color.black.ignoresSafeArea()
                    CoverImage(runtime: runtime, content: content, plain: true, contentMode: .fit)
                        .accessibilityIdentifier("details.coverPreview.image")
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .scaleEffect(scale)
                        .gesture(
                            MagnificationGesture()
                                .onChanged { value in
                                    scale = max(1.0, lastScale * value)
                                }
                                .onEnded { _ in
                                    lastScale = scale
                                }
                        )
                        .onTapGesture(count: 2) {
                            withAnimation(.spring()) {
                                scale = scale > 1.5 ? 1.0 : 2.5
                                lastScale = scale
                            }
                        }
                }
            }
            .navigationTitle("封面")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("完成") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if let url = URL(string: content.publicUrl) {
                        ShareLink(item: url) {
                            Image(systemName: "square.and.arrow.up")
                        }
                    }
                }
            }
        }
    }
}

@MainActor
final class PageThumbnailStore {
    static let shared = PageThumbnailStore()
    private var cache = NSCache<NSString, UIImage>()

    init() {
        cache.countLimit = 150
        cache.totalCostLimit = 80 * 1024 * 1024
    }

    func cachedImage(key: String) -> UIImage? {
        cache.object(forKey: key as NSString)
    }

    func putImage(_ image: UIImage, key: String) {
        let cost = Int(image.size.width * image.size.height * 4)
        cache.setObject(image, forKey: key as NSString, cost: cost)
    }
}

struct PageThumbnailCard: View {
    let runtime: IosSources
    let content: IosSourceContent
    let chapter: IosSourceChapter
    let page: IosSourcePage
    let index: Int
    let isCurrent: Bool
    var initialImage: UIImage? = nil
    let onTap: () -> Void

    @State private var image: UIImage?
    @State private var isLoading = false
    @State private var loadFailed = false

    private var cacheKey: String { "\(chapter.id)_\(index)" }

    init(
        runtime: IosSources,
        content: IosSourceContent,
        chapter: IosSourceChapter,
        page: IosSourcePage,
        index: Int,
        isCurrent: Bool,
        initialImage: UIImage? = nil,
        onTap: @escaping () -> Void
    ) {
        self.runtime = runtime
        self.content = content
        self.chapter = chapter
        self.page = page
        self.index = index
        self.isCurrent = isCurrent
        self.initialImage = initialImage
        self.onTap = onTap
        if let initialImage {
            _image = State(initialValue: initialImage)
        } else if let cached = PageThumbnailStore.shared.cachedImage(key: "\(chapter.id)_\(index)") {
            _image = State(initialValue: cached)
        }
    }

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .bottomTrailing) {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color(.secondarySystemFill))
                    .aspectRatio(0.7, contentMode: .fit)

                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .clipped()
                } else if isLoading {
                    ProgressView()
                        .scaleEffect(0.8)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if loadFailed {
                    VStack(spacing: 4) {
                        Image(systemName: "photo")
                            .font(.subheadline)
                            .foregroundStyle(.tertiary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }

                Text("\(index + 1)")
                    .font(.system(size: 10, weight: .bold))
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
                    .foregroundStyle(.primary)
                    .padding(5)

                if isCurrent {
                    VStack {
                        HStack {
                            Image(systemName: "bookmark.fill")
                                .font(.system(size: 10))
                                .foregroundStyle(.white)
                                .padding(4)
                                .background(Color.accentColor, in: Circle())
                                .padding(5)
                            Spacer()
                        }
                        Spacer()
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .strokeBorder(isCurrent ? Color.accentColor : Color.primary.opacity(0.08), lineWidth: isCurrent ? 2.5 : 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("details.pages.page.\(index)")
        .accessibilityLabel("第 \(index + 1) 页")
        .task(id: page.url) {
            guard image == nil else { return }
            if let cached = PageThumbnailStore.shared.cachedImage(key: cacheKey) {
                self.image = cached
                return
            }
            isLoading = true
            loadFailed = false
            do {
                let img = try await ReaderDownloads.image(runtime: runtime, content: content, chapter: chapter, page: page, index: index, cropMargins: false)
                PageThumbnailStore.shared.putImage(img, key: cacheKey)
                self.image = img
                self.isLoading = false
            } catch {
                if !Task.isCancelled {
                    self.isLoading = false
                    self.loadFailed = true
                }
            }
        }
    }
}

struct ChapterThumbnailsView: View {
    let runtime: IosSources
    let details: IosSourceContent
    let chapters: [IosSourceChapter]
    let initialChapter: IosSourceChapter?
    var initialPages: [IosSourcePage]? = nil
    var currentReadingPage: Int? = nil
    var memoryImages: [Int: UIImage]? = nil
    let resume: IosReadingPosition?
    let onSelectPage: (IosSourceChapter, Int) -> Void

    @State private var selectedChapter: IosSourceChapter?
    @State private var pages: [IosSourcePage] = []
    @State private var isLoading = false
    @State private var errorText: String?
    @State private var taskToken = UUID()

    private var currentChapterIndex: Int {
        guard let selected = selectedChapter else { return 0 }
        return chapters.firstIndex(where: { $0.id == selected.id }) ?? 0
    }

    var body: some View {
        VStack(spacing: 0) {
            chapterSelectorBar
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemGroupedBackground))

            Divider()

            if isLoading {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("正在加载页面缩略图…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let errorText {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.title2)
                        .foregroundStyle(.orange)
                    Text(errorText)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                    Button("重试") {
                        if let selected = selectedChapter {
                            loadPages(for: selected)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if pages.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "photo.on.rectangle")
                        .font(.system(size: 32))
                        .foregroundStyle(.tertiary)
                    Text("本章暂无页面")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVGrid(
                        columns: [
                            GridItem(.flexible(), spacing: 10),
                            GridItem(.flexible(), spacing: 10),
                            GridItem(.flexible(), spacing: 10)
                        ],
                        spacing: 10
                    ) {
                        ForEach(pages.indices, id: \.self) { index in
                            let page = pages[index]
                            if let chapter = selectedChapter {
                                let isCurrent = currentReadingPage != nil
                                    ? (chapter.id == initialChapter?.id && index == currentReadingPage)
                                    : (resume?.chapterId == chapter.id && resume?.index == Int32(index))
                                PageThumbnailCard(
                                    runtime: runtime,
                                    content: details,
                                    chapter: chapter,
                                    page: page,
                                    index: index,
                                    isCurrent: isCurrent,
                                    initialImage: chapter.id == initialChapter?.id ? memoryImages?[index] : nil,
                                    onTap: {
                                        onSelectPage(chapter, index)
                                    }
                                )
                            }
                        }
                    }
                    .padding(14)

                    HStack(spacing: 16) {
                        Button {
                            selectAdjacentChapter(forward: false)
                        } label: {
                            Label("上一章", systemImage: "chevron.left")
                                .font(.footnote.weight(.medium))
                        }
                        .disabled(currentChapterIndex <= 0)

                        Spacer()

                        Text("\(pages.count) 页")
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Spacer()

                        Button {
                            selectAdjacentChapter(forward: true)
                        } label: {
                            HStack(spacing: 4) {
                                Text("下一章")
                                Image(systemName: "chevron.right")
                            }
                            .font(.footnote.weight(.medium))
                        }
                        .disabled(currentChapterIndex >= chapters.count - 1)
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 16)
                }
            }
        }
        .onAppear {
            if selectedChapter == nil {
                let initial = initialChapter ?? chapters.first
                selectedChapter = initial
                if let initial {
                    loadPages(for: initial)
                }
            }
        }
        .onChange(of: selectedChapter?.id) { _, _ in
            if let chapter = selectedChapter {
                loadPages(for: chapter)
            }
        }
    }

    private var chapterSelectorBar: some View {
        HStack(spacing: 8) {
            Button {
                selectAdjacentChapter(forward: false)
            } label: {
                Image(systemName: "chevron.left")
                    .font(.footnote.weight(.semibold))
                    .frame(width: 30, height: 30)
                    .background(Color(.tertiarySystemFill), in: Circle())
            }
            .buttonStyle(.plain)
            .disabled(currentChapterIndex <= 0)

            Spacer()

            Menu {
                ForEach(chapters, id: \.id) { ch in
                    Button {
                        selectedChapter = ch
                    } label: {
                        HStack {
                            Text(ch.title)
                            if ch.id == selectedChapter?.id {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            } label: {
                HStack(spacing: 5) {
                    Text(selectedChapter?.title ?? String(localized: "选择章节"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Color(.tertiarySystemFill), in: Capsule())
            }
            .buttonStyle(.plain)

            Spacer()

            Button {
                selectAdjacentChapter(forward: true)
            } label: {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .frame(width: 30, height: 30)
                    .background(Color(.tertiarySystemFill), in: Circle())
            }
            .buttonStyle(.plain)
            .disabled(currentChapterIndex >= chapters.count - 1)
        }
    }

    private func selectAdjacentChapter(forward: Bool) {
        let nextIndex = forward ? currentChapterIndex + 1 : currentChapterIndex - 1
        guard chapters.indices.contains(nextIndex) else { return }
        selectedChapter = chapters[nextIndex]
    }

    private func loadPages(for chapter: IosSourceChapter) {
        if let initialChapter, chapter.id == initialChapter.id, let initialPages, !initialPages.isEmpty {
            self.pages = initialPages
            self.isLoading = false
            self.errorText = nil
            return
        }
        let token = UUID()
        taskToken = token
        isLoading = true
        errorText = nil
        pages = []
        Task {
            do {
                let result = try await ReaderDownloads.pages(runtime: runtime, content: details, chapter: chapter)
                guard taskToken == token else { return }
                self.pages = result
                self.isLoading = false
            } catch {
                guard taskToken == token else { return }
                self.errorText = SourceErrorText.describe(error)
                self.isLoading = false
            }
        }
    }
}

