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
        didSet { chapters = details?.chapters ?? [] }
    }
    /// Bridged once per details: every `IosSourceContent.chapters` read maps the whole list again.
    private(set) var chapters: [IosSourceChapter] = []
    @Published private(set) var error: String?
    /// The last failure was a browser challenge (Cloudflare): the error offers the in-app browser.
    @Published private(set) var challenge: IosChallenge?
    @Published private(set) var isLoading = false
    @Published var selectedLine: String?
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

    /// Translation branches in source order (manga/novels); a single unnamed branch is one nil entry.
    var branches: [String?] {
        var seen = Set<String?>()
        return chapters.map(\.branch).filter { seen.insert($0).inserted }
    }

    /// List chapters by the numbers the extension declared instead of the source's own order (the default, as Mihon
    /// lists them): some extensions misparse numbers from titles, which would scramble a number-sorted list.
    @Published var orderByNumber = false

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

    func refresh() async {
        let request = UUID()
        detailsRequest = request
        isLoading = true
        defer { if detailsRequest == request { isLoading = false } }
        let identity = ContentMarks.key(content)
        do {
            let result = try await runtime.refreshDetails(content: details ?? content)
            guard !Task.isCancelled, detailsRequest == request, identity == ContentMarks.key(content) else { return }
            apply(result); fetched = true; error = nil; challenge = nil
            DetailsCache.put(result)
            await loadRelated()
        } catch {
            if !Task.isCancelled, detailsRequest == request, identity == ContentMarks.key(content) { fail(error) }
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

    /// Readable text, and whether the site asked for browser verification.
    private func fail(_ error: Error) {
        self.error = SourceErrorText.describe(error)
        challenge = SourceErrorText.challenge(error)
    }

    var isVideo: Bool { content.mediaKind == "VIDEO" }
    var isNovel: Bool { content.mediaKind == "NOVEL" }

    /// Episode groups in source order, from parser/Aniyomi branches or CMS lines.
    var lines: [String?] {
        var seen = Set<String?>()
        return chapters.map(\.line).filter { seen.insert($0).inserted }
    }

    var visibleChapters: [IosSourceChapter] {
        guard isVideo, lines.count > 1 else { return ordered(chapters) }
        let line = lines.contains(selectedLine) ? selectedLine : lines.first ?? nil
        return ordered(chapters.filter { $0.line == line })
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
        }
    }

    /// Keeps the chosen line and branch when they still exist.
    private func apply(_ result: IosSourceContent) {
        details = result
        if !lines.contains(selectedLine) { selectedLine = lines.first ?? nil }
        if !branches.contains(selectedBranch) { selectedBranch = branches.first ?? nil }
    }

    func load() async {
        guard !isLoading, !fetched || error != nil else { return }
        let request = UUID()
        detailsRequest = request
        isLoading = true
        error = nil; challenge = nil
        defer { if detailsRequest == request { isLoading = false } }
        do {
            let result = try await runtime.details(content: content)
            try Task.checkCancellation()
            if detailsRequest == request {
                apply(result)
                fetched = true
                DetailsCache.put(result)
                await loadRelated()
            }
        } catch {
            // A shown cached or saved list stays (offline reading); the error offers a retry above it.
            if !Task.isCancelled, detailsRequest == request { fail(error) }
        }
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
    @StateObject private var readingStats = ReadingStatsModel()
    @State private var metadataContent: IosSourceContent?
    @State private var metadataRequest: UUID?
    private var shownContent: IosSourceContent { metadataContent ?? model.details ?? model.content }
    @State private var paneAnchor = DetailsPaneAnchor.collapsed
    private var showingChaptersSheet: Bool {
        get { paneAnchor != .collapsed }
        nonmutating set {
            withAnimation(.spring(response: 0.38, dampingFraction: 0.9)) {
                paneAnchor = newValue ? (paneAnchor == .expanded ? .expanded : .hovered) : .collapsed
                if newValue { isDockCompact = false }
            }
        }
    }
    @State private var selectingChapters = false
    @State private var selectedChapterIDs = Set<String>()
    @State private var showingChapterDownloads = false
    @State private var chapterDownloadError: String?
    @State private var chapterDownloadStatus: String?
    private enum DetailsTab: Int, CaseIterable {
        case chapters = 0
        case thumbnails = 1
        case bookmarks = 2
    }

    @State private var selectedTab = DetailsTab.chapters
    @State private var bookmarks: [IosBookmark] = []
    @State private var chapterQuery = ""
    @AppStorage("details.newestFirst") private var newestFirst = false
    /// "source" (default) or "number"; see SourceDetailsModel.orderByNumber.
    @AppStorage("details.chapterOrder") private var chapterOrder = "source"
    @AppStorage(PanoramaKeys.enabled) private var panorama = true
    @AppStorage(PanoramaKeys.layout) private var panoramaLayout = PanoramaLayout.top
    @State private var scrollOffset: CGFloat = 0
    @State private var isDockCompact = false
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
            Section {
                Button { showingReadingStats = true } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "chart.bar.xaxis").foregroundStyle(.secondary)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("阅读统计").font(.subheadline.weight(.medium))
                            if let work = readingStats.snapshot?.works.first, work.hasTimedStats {
                                Text("\(ReadingStatsFormat.duration(work.duration)) · \(work.activeDays) 天 · \(work.sessions) 次")
                                    .font(.caption).foregroundStyle(.secondary)
                            } else {
                                Text("查看阅读时长与活动记录").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                    }.padding(.vertical, 4)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("details.readingStats")
            }
            if let recordError { Text(recordError).foregroundStyle(.red) }
            if model.isLoading && model.details == nil {
                ProgressView("正在读取详情…").frame(maxWidth: .infinity).listRowBackground(Color.clear)
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
            if model.details != nil {
                relatedSection
            }
            Section {
                Spacer().frame(height: 70)
            }
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        }
        .listStyle(.plain)
        .contentMargins(.horizontal, 16, for: .scrollContent)
        .listSectionSpacing(.compact)
        .scrollContentBackground(.hidden)
        .toolbar(.hidden, for: .tabBar)
        .onAppear {
            ReaderPresence.shared.detailAppear()
        }
        .onDisappear {
            ReaderPresence.shared.detailDisappear()
        }
        .onChange(of: selectedTab) { _, tab in
            finishChapterSelection()
            if tab == .bookmarks {
                Task { await refreshBookmarks() }
            }
        }
        .onScrollGeometryChange(for: CGFloat.self, of: { $0.contentOffset.y + $0.contentInsets.top }) { oldOffset, newOffset in
            updateDockOnScroll(from: oldOffset, to: newOffset)
        }
        // Keep the refresh action inside the main list, outside the chapter panel's environment.
        .refreshable { await refreshDetails() }
        .overlay(alignment: .bottom) {
            DetailsDockPane(anchor: $paneAnchor, onDragBegan: { isDockCompact = false }) {
                if let details = model.details ?? (model.content.chapterCount > 0 ? model.content : nil) {
                    chaptersSheet(details)
                } else {
                    ContentUnavailableView("暂无章节", systemImage: "list.bullet")
                }
            } dock: {
                floatingActionBar
            }
        }
        .onChange(of: paneAnchor) { _, anchor in
            if anchor == .collapsed { finishChapterSelection() }
            else { isDockCompact = false }
        }
        .overlay(alignment: .top) {
            if let toast = toastMessage {
                Text(toast)
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(.ultraThinMaterial, in: Capsule())
                    .shadow(radius: 6)
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
        .onAppear { model.orderByNumber = chapterOrder == "number" }
        .task(id: library.recordsRevision) {
            await readingStats.load(library.library, range: .all, kind: "ALL", contentId: model.content.id)
        }
        .sheet(isPresented: $showingReadingStats) {
            ContentReadingStatsSheet(content: shownContent, runtime: model.runtime)
        }
        .onChange(of: chapterOrder) { _, order in withAnimation { model.orderByNumber = order == "number" } }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if let url = URL(string: model.content.publicUrl), url.scheme?.hasPrefix("http") == true {
                    ShareLink(item: url) { Label("分享", systemImage: "square.and.arrow.up") }
                }
                Menu("更多", systemImage: "ellipsis") {
                    Button("作品资料", systemImage: "link") { managingTracking = true }
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
                        toastMessage = shownContent.metadataSiteTitle.map { "已使用 \($0) 资料" } ?? "已恢复内容源资料"
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
        .navigationDestination(item: $reading) { request in
            Group {
                if model.isNovel { NovelReaderView(runtime: model.runtime, content: request.content, chapter: request.chapter) }
                else { SourceReaderView(runtime: model.runtime, content: request.content, chapter: request.chapter, initialPage: request.initialPage) }
            }
            .id(request.id)
        }
        .fullScreenCover(item: $playing, onDismiss: { Task { await refreshRecords() } }) { request in
            VideoPlayerView(runtime: model.runtime, content: request.content, chapter: request.chapter)
                .id(request.id)
        }
    }

    private func updateDockOnScroll(from oldOffset: CGFloat, to newOffset: CGFloat) {
        scrollOffset = newOffset
        guard !showingChaptersSheet, abs(newOffset - oldOffset) > 0.5 else { return }
        let compact = newOffset > 10 && newOffset > oldOffset
        guard compact != isDockCompact else { return }
        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { isDockCompact = compact }
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

    private func refreshRecords() async {
        await refreshMetadata()
        guard let content = model.details, let database = library.library else { return }
        do {
            try await database.saveDetails(content: content)
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

    private func refreshBookmarks() async {
        guard let content = model.details ?? model.content as IosSourceContent?, let database = library.library else { return }
        bookmarks = (try? await database.bookmarks(content: content)) ?? []
    }

    private var header: some View {
        let shown = shownContent
        return Section {
            VStack(alignment: .leading, spacing: 14) {
                coverRow(shown)
                infoCard(shown)
                summaryCard(shown)
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
                Text(shown.title)
                    .font(.title3.weight(.bold))
                    .lineSpacing(2)
                    .lineLimit(isTitleExpanded ? nil : 3)
                    .textSelection(.enabled)
                    .onTapGesture { withAnimation { isTitleExpanded.toggle() } }
                    .accessibilityIdentifier("details.title")

                if let alt = shown.altTitles.first, !alt.isEmpty {
                    Text(alt)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(isTitleExpanded ? nil : 2)
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
                        Text("资料 · \(shown.metadataSiteTitle ?? "内容源")").font(.caption.weight(.medium))
                        Image(systemName: "chevron.right").font(.system(size: 8, weight: .bold))
                    }
                    .padding(.horizontal, 9).padding(.vertical, 4)
                    .background(Color.accentColor.opacity(0.1), in: .capsule)
                }
                .buttonStyle(.plain)
                .disabled(library.library == nil)
                .accessibilityIdentifier("details.metadata")

                // Quick actions below the source and metadata labels.
                HStack(spacing: 8) {
                    Button {
                        UIPasteboard.general.string = shown.title
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        toastMessage = "已复制标题"
                    } label: {
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 14, weight: .medium))
                            .frame(width: 36, height: 36)
                            .background(Color(.secondarySystemFill), in: .circle)
                            .foregroundStyle(.primary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("复制标题")

                    Button {
                        if isFavourite {
                            removeFavourite()
                        } else {
                            editingCategories = true
                        }
                    } label: {
                        Image(systemName: isFavourite ? "heart.fill" : "heart")
                            .font(.system(size: 15, weight: .medium))
                            .frame(width: 36, height: 36)
                            .background(isFavourite ? Color.pink.opacity(0.18) : Color(.secondarySystemFill), in: .circle)
                            .foregroundStyle(isFavourite ? Color.pink : .primary)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button("收藏分类", systemImage: "folder") { editingCategories = true }
                        if isFavourite {
                            Button("取消收藏", systemImage: "heart.slash", role: .destructive, action: removeFavourite)
                        }
                    }
                    .accessibilityLabel(isFavourite ? "已收藏" : "收藏")

                    if shown.rating > 0 {
                        HStack(spacing: 3) {
                            Image(systemName: "star.fill")
                                .font(.system(size: 10))
                                .foregroundStyle(.orange)
                            Text(String(format: "%.1f", Double(shown.rating) * 5))
                                .font(.caption.weight(.semibold))
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .background(Color.orange.opacity(0.12), in: .capsule)
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
                    HStack(spacing: 6) {
                        Image(systemName: "person.circle")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(shown.authors.joined(separator: "、"))
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
            } else {
                HStack(spacing: 6) {
                    Image(systemName: "person.circle")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("佚名")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func infoCard(_ shown: IosSourceContent) -> some View {
        VStack(spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                // Left Column: Author & Media Kind
                VStack(alignment: .leading, spacing: 8) {
                    authorItem(shown)

                    HStack(spacing: 6) {
                        Image(systemName: "character.book.closed")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(Self.mediaKindTitle(shown.mediaKind))
                            .font(.subheadline)
                            .foregroundStyle(.primary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                // Right Column: State & Chapters
                VStack(alignment: .leading, spacing: 8) {
                    if let state = shown.state {
                        HStack(spacing: 6) {
                            Image(systemName: state == "ONGOING" ? "play.circle" : "checkmark.circle")
                                .font(.caption)
                                .foregroundStyle(Self.stateTint(state))
                            Text(Self.stateTitle(state))
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(Self.stateTint(state))
                        }
                    } else {
                        HStack(spacing: 6) {
                            Image(systemName: "questionmark.circle")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text("状态未知")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }

                    let totalChapters = model.isVideo ? model.visibleChapters.count : model.chapters.count
                    if totalChapters > 0 {
                        let progressText: String = {
                            if let resume = resume, resume.progress > 0 {
                                let readCount = model.chapters.firstIndex(where: { $0.id == resume.chapterId }).map { $0 + 1 } ?? 0
                                return "\(readCount)/\(totalChapters)"
                            } else {
                                return model.isVideo ? "\(totalChapters) 集" : "\(totalChapters) 章"
                            }
                        }()
                        Button {
                            selectedTab = .chapters
                            showingChaptersSheet = true
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "book.pages")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Text(progressText)
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 8, weight: .bold))
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

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
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 1)
        )
    }

    @ViewBuilder
    private func summaryCard(_ shown: IosSourceContent) -> some View {
        if let summary = shown.summary.map(Self.plainText), !summary.isEmpty || !shown.tags.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("简介")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.primary)

                if let summary = shown.summary.map(Self.plainText), !summary.isEmpty {
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
                    }
                }

                if !shown.tags.isEmpty {
                    FlowLayout(spacing: 6) {
                        ForEach(shown.tags, id: \.self) { tag in
                            Text(tag)
                                .font(.caption)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(Color(.tertiarySystemFill), in: .capsule)
                                .foregroundStyle(.primary)
                        }
                    }
                    .padding(.top, 2)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.06), lineWidth: 1)
            )
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

    @ViewBuilder
    private var dockTabsCapsule: some View {
        HStack(spacing: 2) {
            // 章节 (Chapters)
            if !isDockCompact || selectedTab == .chapters {
                Button {
                    toggleDockTab(.chapters)
                } label: {
                    Image(systemName: model.isVideo ? "play.rectangle.on.rectangle" : "list.bullet")
                        .font(.system(size: 15, weight: .medium))
                        .frame(width: 38, height: 38)
                        .foregroundStyle(selectedTab == .chapters ? Color.accentColor : Color.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(model.isVideo ? "剧集" : "章节")
                .accessibilityIdentifier("details.dock.chapters")
                .accessibilityAddTraits(selectedTab == .chapters ? .isSelected : [])
            }

            // 缩略图 (Thumbnails / Grid) - Manga only
            if !model.isNovel && !model.isVideo && (!isDockCompact || selectedTab == .thumbnails) {
                Button {
                    toggleDockTab(.thumbnails)
                } label: {
                    Image(systemName: "square.grid.2x2")
                        .font(.system(size: 14, weight: .medium))
                        .frame(width: 38, height: 38)
                        .foregroundStyle(selectedTab == .thumbnails ? Color.accentColor : Color.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("缩略图")
                .accessibilityIdentifier("details.dock.thumbnails")
                .accessibilityAddTraits(selectedTab == .thumbnails ? .isSelected : [])
            }

            // 书签 (Bookmarks) - Manga & Novel
            if !model.isVideo && (!isDockCompact || selectedTab == .bookmarks) {
                Button {
                    toggleDockTab(.bookmarks)
                } label: {
                    Image(systemName: "bookmark")
                        .font(.system(size: 14, weight: .medium))
                        .frame(width: 38, height: 38)
                        .foregroundStyle(selectedTab == .bookmarks ? Color.accentColor : Color.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("书签")
                .accessibilityIdentifier("details.dock.bookmarks")
                .accessibilityAddTraits(selectedTab == .bookmarks ? .isSelected : [])
            }
        }
        .padding(.horizontal, 6)
        .frame(height: 50)
        .background(.regularMaterial, in: .capsule)
        .overlay(Capsule().strokeBorder(Color.primary.opacity(0.08), lineWidth: 1))
    }

    private func toggleDockTab(_ tab: DetailsTab) {
        let closing = showingChaptersSheet && selectedTab == tab
        selectedTab = tab
        showingChaptersSheet = !closing
        if isDockCompact {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { isDockCompact = false }
        }
    }

    @ViewBuilder
    private var dockPrimaryActionCapsule: some View {
        if let details = model.details,
           let target = resumeChapter() ?? (model.isVideo ? model.visibleChapters.first : model.chapters.first) {
            let resuming = resumeChapter() != nil
            let title = resuming ? (model.isVideo ? "继续播放" : "继续") : (model.isVideo ? "开始播放" : "开始阅读")

            HStack(spacing: 0) {
                Button {
                    showingChaptersSheet = false
                    if model.isVideo {
                        playing = PlaybackRequest(content: details, chapter: target)
                    } else {
                        reading = PlaybackRequest(content: details, chapter: target)
                    }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: model.isVideo ? "play.fill" : "book.fill")
                            .font(.system(size: 16, weight: .semibold))
                        if !isDockCompact {
                            Text(title)
                                .font(.subheadline.weight(.bold))
                                .lineLimit(1)
                        }
                    }
                    .padding(.horizontal, isDockCompact ? 14 : 18)
                    .frame(maxWidth: isDockCompact ? nil : .infinity)
                    .frame(maxHeight: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("details.dock.read")

                Divider()
                    .frame(height: 20)
                    .overlay(Color.white.opacity(0.35))

                Menu {
                    Button("换源…", systemImage: "arrow.triangle.swap") {
                        changingSource = true
                    }
                    .disabled(library.library == nil || (model.details == nil && model.isLoading))

                    if model.isVideo && model.lines.count > 1 {
                        Section("切换线路") {
                            ForEach(model.lines, id: \.self) { line in
                                Button(line ?? "默认") { model.selectedLine = line }
                            }
                        }
                    } else if !model.isVideo && model.branches.count > 1 {
                        Section("切换线路/分支") {
                            ForEach(model.branches, id: \.self) { branch in
                                Button(branch ?? "默认") {
                                    model.selectedBranch = branch
                                }
                            }
                        }
                    }
                    if let first = model.isVideo ? model.visibleChapters.first : model.chapters.first {
                        Button(model.isVideo ? "从第一集开始播放" : "从第一话开始阅读") {
                            if model.isVideo {
                                playing = PlaybackRequest(content: details, chapter: first)
                            } else {
                                reading = PlaybackRequest(content: details, chapter: first)
                            }
                        }
                    }
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 12, weight: .bold))
                        .frame(width: 34, height: 50)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .frame(height: 50)
            .foregroundStyle(.white)
            .background(Color.accentColor, in: .capsule)
        } else {
            HStack(spacing: 8) {
                ProgressView().tint(.white)
                if !isDockCompact {
                    Text("正在准备…").font(.subheadline.weight(.semibold))
                }
            }
            .padding(.horizontal, isDockCompact ? 14 : 20)
            .frame(maxWidth: isDockCompact ? nil : .infinity)
            .frame(height: 50)
            .foregroundStyle(.white)
            .background(Color.accentColor.opacity(0.6), in: .capsule)
        }
    }

    @ViewBuilder
    private var floatingActionBar: some View {
        HStack(spacing: 8) {
            dockTabsCapsule
            Spacer(minLength: 8)
            dockPrimaryActionCapsule
        }
        .shadow(color: .black.opacity(0.12), radius: 12, y: 5)
        .padding(.horizontal, 16)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: isDockCompact)
    }

    private func openChapter(_ chapter: IosSourceChapter, from details: IosSourceContent) {
        showingChaptersSheet = false
        if model.isVideo {
            playing = PlaybackRequest(content: details, chapter: chapter)
        } else {
            reading = PlaybackRequest(content: details, chapter: chapter)
        }
    }

    private var chapterRefreshButton: some View {
        Button(model.isVideo ? "刷新剧集" : "刷新章节", systemImage: "arrow.clockwise") {
            Task { await refreshDetails() }
        }
        .labelStyle(.iconOnly)
        .disabled(model.isLoading)
        .accessibilityIdentifier("details.chapters.refresh")
    }

    private func chaptersSheet(_ details: IosSourceContent) -> some View {
        VStack(spacing: 0) {
            Group {
                if model.isVideo {
                    List {
                        episodes(details)
                    }
                    .listStyle(.insetGrouped)
                    .scrollDisabled(paneAnchor != .expanded)
                } else {
                    // A scrolling page stack keeps the lists at the top while the panel changes height.
                    // UIPageViewController-backed TabView recenters its pages as its safe area changes.
                    GeometryReader { page in
                        ScrollView(.horizontal) {
                            HStack(spacing: 0) {
                                List {
                                    chapters(details)
                                }
                                .listStyle(.insetGrouped)
                                .scrollDisabled(paneAnchor != .expanded)
                                .accessibilityIdentifier("details.chapters.list")
                                .frame(width: page.size.width, height: page.size.height)
                                .id(DetailsTab.chapters)

                                if !model.isNovel {
                                    ChapterThumbnailsView(
                                        runtime: model.runtime,
                                        details: details,
                                        chapters: model.chapterList(query: chapterQuery, newestFirst: newestFirst),
                                        initialChapter: resumeChapter() ?? model.chapters.first,
                                        resume: resume,
                                        onSelectPage: { chapter, pageIndex in
                                            showingChaptersSheet = false
                                            reading = PlaybackRequest(content: details, chapter: chapter,
                                                                      initialPage: pageIndex)
                                        }
                                    )
                                    .scrollDisabled(paneAnchor != .expanded)
                                    .frame(width: page.size.width, height: page.size.height)
                                    .id(DetailsTab.thumbnails)
                                }

                                List {
                                    bookmarksList(details)
                                }
                                .listStyle(.insetGrouped)
                                .scrollDisabled(paneAnchor != .expanded)
                                .frame(width: page.size.width, height: page.size.height)
                                .id(DetailsTab.bookmarks)
                            }
                            .scrollTargetLayout()
                        }
                        .scrollIndicators(.hidden)
                        .scrollTargetBehavior(.paging)
                        .scrollPosition(id: Binding<DetailsTab?>(
                            get: { selectedTab },
                            set: { if let tab = $0 { selectedTab = tab } }
                        ))
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if selectingChapters && selectedTab == .chapters {
                    Button {
                        enqueueChapters(details, chapters: model.chapterList(query: chapterQuery, newestFirst: newestFirst)
                            .filter { selectedChapterIDs.contains($0.id) })
                    } label: {
                        Label("下载所选（\(selectedChapterIDs.count)）", systemImage: "arrow.down.circle")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(selectedChapterIDs.isEmpty)
                    .accessibilityIdentifier("details.chapters.downloadSelected")
                    .padding().background(.regularMaterial)
                }
            }
            .onChange(of: chapterQuery) { _, _ in selectedChapterIDs.removeAll() }
            .onChange(of: model.selectedBranch) { _, _ in selectedChapterIDs.removeAll() }
        }
        .sheet(isPresented: $showingChapterDownloads) { ReaderDownloadsSheet() }
        .alert("无法下载", isPresented: Binding(get: { chapterDownloadError != nil },
                                            set: { if !$0 { chapterDownloadError = nil } })) {
            Button("好") { chapterDownloadError = nil }
        } message: { Text(chapterDownloadError ?? "") }
    }

    private func finishChapterSelection() {
        selectingChapters = false
        selectedChapterIDs.removeAll()
    }

    private func toggleChapterSelection(_ chapter: IosSourceChapter) {
        selectingChapters = true
        if !selectedChapterIDs.insert(chapter.id).inserted { selectedChapterIDs.remove(chapter.id) }
    }

    private func enqueueChapters(_ details: IosSourceContent, chapters: [IosSourceChapter]) {
        guard !chapters.isEmpty else { return }
        do {
            try downloads.enqueue(content: details, chapters: chapters)
            chapterDownloadStatus = "已加入下载队列：\(chapters.count) 章"
            finishChapterSelection()
        } catch { chapterDownloadError = error.localizedDescription }
    }

    private func bookmarksList(_ details: IosSourceContent) -> some View {
        Section("书签 (\(bookmarks.count))") {
            if bookmarks.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "bookmark")
                        .font(.system(size: 32))
                        .foregroundStyle(.tertiary)
                    Text("暂无书签")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                    Text("在阅读界面点击书签按钮即可在此处快速定位")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 32)
                .listRowBackground(Color.clear)
            } else {
                ForEach(bookmarks, id: \.id) { bookmark in
                    let chapter = model.chapters.first { $0.id == bookmark.chapterId }
                    Button {
                        if let chapter = chapter ?? model.chapters.first {
                            openChapter(chapter, from: details)
                        }
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "bookmark.fill")
                                .font(.subheadline)
                                .foregroundStyle(Color.accentColor)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(chapter?.title ?? "第 \(bookmark.index + 1) 页")
                                    .font(.subheadline.weight(.medium))
                                if !bookmark.preview.isEmpty {
                                    Text(bookmark.preview)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                            }
                            Spacer()
                            Text("第 \(bookmark.index + 1) 页")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            Task {
                                try? await library.library?.removeBookmark(content: details, bookmark: bookmark)
                                await refreshBookmarks()
                            }
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
                }
            }
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
        case "MANGA": "漫画"
        case "NOVEL": "小说"
        case "VIDEO": "视频"
        default: kind
        }
    }

    static func stateTitle(_ state: String) -> String {
        switch state {
        case "ONGOING": "连载中"
        case "FINISHED": "已完结"
        case "ABANDONED": "已放弃"
        case "PAUSED": "暂停更新"
        case "UPCOMING": "即将推出"
        case "RESTRICTED": "受限"
        default: state
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

    @ViewBuilder
    private func episodes(_ details: IosSourceContent) -> some View {
        Section {
            if model.chapters.isEmpty { Text("暂无可播放的剧集").accessibilityIdentifier("details.empty") }
            if model.lines.count > 1 {
                if model.lines.count <= 3 {
                    linePicker.pickerStyle(.segmented)
                } else {
                    linePicker.pickerStyle(.menu)
                }
            }
            if Self.hasLongTitles(model.visibleChapters) {
                // Long episode names (e.g. "Episode 1 - I'm Luffy!") read better one per row.
                ForEach(model.visibleChapters, id: \.id) { chapter in
                    Button {
                        openChapter(chapter, from: details)
                    } label: {
                        Label(Self.episodeTitle(chapter), systemImage: "play.circle")
                    }
                    .accessibilityIdentifier("chapter.\(chapter.id)")
                }
            } else {
            // Rows of four keep long series (1000+ episodes) lazy: a grid inside one List row would build every button.
            ForEach(Self.rows(model.visibleChapters), id: \.first?.id) { row in
                HStack(spacing: 10) {
                    ForEach(row, id: \.id) { chapter in
                        Button {
                            openChapter(chapter, from: details)
                        } label: {
                            Text(Self.episodeTitle(chapter))
                                .font(.subheadline)
                                .lineLimit(1)
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.glass)
                        .accessibilityIdentifier("chapter.\(chapter.id)")
                    }
                    ForEach(row.count..<Self.rowSize, id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
                }
                .listRowSeparator(.hidden)
            }
            }
        } header: {
            HStack {
                Text("剧集")
                Spacer()
                chapterRefreshButton
            }
        }
    }

    private var linePicker: some View {
        Picker("线路", selection: $model.selectedLine) {
            ForEach(model.lines, id: \.self) { line in
                Text(line ?? "默认").tag(line)
            }
        }
        .accessibilityIdentifier("details.line")
    }

    @ViewBuilder
    private func chapters(_ details: IosSourceContent) -> some View {
        let branches = model.branches
        let visible = model.chapterList(query: chapterQuery, newestFirst: newestFirst)
        let current = resumeChapter()
        let currentIndex = current.flatMap { chapter in model.chapters.firstIndex { $0.id == chapter.id } }
        Section {
            HStack {
                if selectingChapters {
                    Button(selectedChapterIDs.count == visible.count ? "取消全选" : "全选") {
                        selectedChapterIDs = selectedChapterIDs.count == visible.count ? [] : Set(visible.map(\.id))
                    }
                    .accessibilityIdentifier("details.chapters.selectAll")
                    Spacer()
                    Text("已选 \(selectedChapterIDs.count) 章").font(.caption).foregroundStyle(.secondary)
                    Button("取消") { finishChapterSelection() }
                } else {
                    Button("选择", systemImage: "checkmark.circle") { selectingChapters = true }
                        .disabled(visible.isEmpty)
                        .accessibilityIdentifier("details.chapters.select")
                    Spacer()
                    Button("下载队列", systemImage: "arrow.down.circle") { showingChapterDownloads = true }
                        .accessibilityIdentifier("details.chapters.downloads")
                    chapterRefreshButton
                }
            }
            .buttonStyle(.borderless)
            if let chapterDownloadStatus {
                Text(chapterDownloadStatus).font(.caption).foregroundStyle(.secondary)
            }
            if model.chapters.count > 20 {
                TextField("搜索章节", text: $chapterQuery).textFieldStyle(.roundedBorder)
                    .listRowSeparator(.hidden)
            }
            if branches.count > 1 {
                Picker("分支", selection: $model.selectedBranch) {
                    ForEach(branches, id: \.self) { Text($0 ?? "默认").tag($0) }
                }
                .pickerStyle(.menu)
            }
            if visible.isEmpty { Text("暂无章节").foregroundStyle(.secondary).accessibilityIdentifier("details.empty") }
            ForEach(visible, id: \.id) { chapter in
                let index = model.chapters.firstIndex { $0.id == chapter.id }
                let isCurrent = chapter.id == current?.id
                let isRead = !isCurrent && index != nil && currentIndex != nil && index! < currentIndex!
                Button {
                    if selectingChapters { toggleChapterSelection(chapter) }
                    else { openChapter(chapter, from: details) }
                } label: {
                    HStack(spacing: 10) {
                        if selectingChapters {
                            Image(systemName: selectedChapterIDs.contains(chapter.id) ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(selectedChapterIDs.contains(chapter.id) ? Color.accentColor : .secondary)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(chapter.title).font(isCurrent ? .body.weight(.semibold) : .body)
                                .foregroundStyle(isRead ? .secondary : .primary)
                                .lineLimit(2)
                            if let subtitle = Self.chapterSubtitle(chapter) {
                                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                            if let entry = downloads.entry(content: details, chapter: chapter) {
                                Text(entry.status).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                        Spacer(minLength: 0)
                        if isCurrent, let resume {
                            Text("\(Int(resume.progress * 100))%").font(.caption.monospacedDigit())
                                .foregroundStyle(.tint)
                        }
                    }
                    // Plain buttons only hit their drawn content; make the whole row tappable.
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .listRowBackground(isCurrent ? Color.accentColor.opacity(0.12) : nil)
                .accessibilityIdentifier("chapter.\(chapter.id)")
                .accessibilityValue(selectingChapters ? (selectedChapterIDs.contains(chapter.id) ? "已选择" : "未选择") : "")
                .contextMenu {
                    Button("下载本章", systemImage: "arrow.down.circle") { enqueueChapters(details, chapters: [chapter]) }
                    Button("选择章节", systemImage: "checkmark.circle") { toggleChapterSelection(chapter) }
                }
                .swipeActions(edge: .trailing) {
                    Button("下载", systemImage: "arrow.down.circle") { enqueueChapters(details, chapters: [chapter]) }
                        .tint(.accentColor)
                }
            }
        } header: {
            HStack {
                Text("章节（\(model.chapters.count)）")
                Spacer()
                Menu {
                    Picker("排序依据", selection: $chapterOrder) {
                        Text("源顺序").tag("source")
                        Text("章节号").tag("number")
                    }
                    .pickerStyle(.inline)
                    Picker("方向", selection: $newestFirst) {
                        Text("最早在前").tag(false)
                        Text("最新在前").tag(true)
                    }
                    .pickerStyle(.inline)
                } label: {
                    Label((chapterOrder == "number" ? "章节号 · " : "") + (newestFirst ? "最新在前" : "最早在前"),
                          systemImage: "arrow.up.arrow.down")
                }
                .labelStyle(.titleAndIcon).font(.caption).textCase(nil)
                .accessibilityIdentifier("details.sort")
            }
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

    static func rows(_ chapters: [IosSourceChapter]) -> [[IosSourceChapter]] {
        stride(from: 0, to: chapters.count, by: rowSize).map { Array(chapters[$0..<min($0 + rowSize, chapters.count)]) }
    }

    /// Episode titles repeat the line name ("线路A - 第1集"); the picker already shows it.
    static func episodeTitle(_ chapter: IosSourceChapter) -> String {
        guard let line = chapter.line, chapter.title.hasPrefix("\(line) - ") else { return chapter.title }
        return String(chapter.title.dropFirst(line.count + 3))
    }

    /// CMS descriptions are often HTML fragments.
    static func plainText(_ html: String) -> String {
        html.replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: .regularExpression)
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// One opening of a reader or player. Each opening is its own identity: reopening the chapter just closed must build
/// a new reader, which reads the saved position again, rather than get back the previous reader's state.
struct PlaybackRequest: Identifiable, Hashable {
    let content: IosSourceContent
    let chapter: IosSourceChapter
    var initialPage: Int? = nil
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
                        ForEach(Array(pages.enumerated()), id: \.offset) { index, page in
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
                    Text(selectedChapter?.title ?? "选择章节")
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

