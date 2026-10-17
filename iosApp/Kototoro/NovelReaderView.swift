import Combine
import CoreText
import ImageIO
import KototoroShared
import SwiftUI

/// One block of a chapter: a paragraph of text or an illustration.
enum NovelBlock: Hashable {
    case text(String)
    case image(URL)
}

extension NovelBlock {
    /// The chapter's lines and illustrations, converted from its HTML by the shared reader-novel module.
    static func blocks(from text: IosChapterText) -> [NovelBlock] { blocks(from: text.document()) }

    /// One block per document line, so indices stay those the shared position rules use.
    static func blocks(from document: IosNovelDocument) -> [NovelBlock] {
        document.blocks.map { block in
            if let url = block.imageUrl.flatMap({ URL(string: $0) }) { return .image(url) }
            return .text(block.text ?? "")
        }
    }
}

/// A position the reader is asked to show; each request is distinct even when it repeats a position.
struct NovelReadingTarget: Equatable {
    let id = UUID()
    let block: Int
    /// History value with the position within the line; 0 when only [block] is known.
    let scroll: Float
    let atEnd: Bool
}

@MainActor
final class NovelReaderModel: ObservableObject {
    private lazy var activity = ReadingActivityRecorder(content: content)

    func setReadingActive(_ value: Bool) {
        activity.setActive(value)
        if !value { Task { await activity.flush() } }
    }

    func finishReadingActivity() async {
        activity.setActive(false)
        await activity.flush()
    }
    @Published private(set) var chapter: IosSourceChapter
    @Published private(set) var blocks: [NovelBlock] = []
    /// The converted chapter; maps blocks to the chapter progress Android also reads and writes.
    private var document: IosNovelDocument?
    @Published private(set) var bookmarks: [IosBookmark] = []
    private(set) var text: IosChapterText?
    /// Downloaded pictures of the current chapter, by their original URL.
    private(set) var localImages: [String: URL] = [:]
    /// The chapter on screen was read from its download.
    @Published private(set) var isOffline = false
    @Published private(set) var error: String?
    /// The last failure was a browser challenge (Cloudflare): the error offers the in-app browser.
    @Published private(set) var challenge: IosChallenge?
    @Published private(set) var isLoading = false
    let runtime: IosSources
    let content: IosSourceContent
    private var request: UUID?
    var library: IosLibrary?
    @Published private(set) var resumeIndex = 0
    @Published private(set) var visibleIndex = 0
    /// Where the reader should go: the restored position after a load, a bookmark, or the end of the previous chapter.
    @Published private(set) var target = NovelReadingTarget(block: 0, scroll: 0, atEnd: false)
    /// Increases whenever a chapter finishes loading, so the paged reader lays the new text out.
    @Published private(set) var revision = 0
    private var visibleIndices: Set<Int> = []
    private var saveTask: Task<Void, Never>?
    /// The paged reader's position within a line; the scrolling reader saves line starts.
    private var pagedScroll: Float?
    private var openAtEnd = false
    private var pageCache: (indent: Bool, spacing: Int, bookStyles: Bool, revision: Int, pages: IosNovelPages)?
    private var lineStyleCache: [Int: (runs: [NovelRun], layout: NovelLineLayout?)] = [:]

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        self.runtime = runtime
        self.content = content
        self.chapter = chapter
    }

    var previous: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: false) }
    var next: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: true) }
    var isBookmarked: Bool { bookmarks.contains { $0.chapterId == chapter.id && Int($0.index) == visibleIndex } }

    /// Shared history stores progress in 1/10,000 units; the controls display a normalized fraction.
    var progress: Double {
        let units = pagedScroll ?? document?.scroll(index: Int32(visibleIndex)) ?? 0
        return units.isFinite ? min(1, max(0, Double(units) / 10_000)) : 0
    }

    func load() async {
        activity.pause()
        await activity.flush()
        let request = UUID()
        self.request = request
        let selected = chapter
        isLoading = true
        visibleIndices = []
        error = nil; challenge = nil
        defer { if self.request == request { isLoading = false } }
        do {
            // A downloaded chapter reads offline; a damaged download falls back to the source.
            let offline = try? NovelDownloads.offline(content: content, chapter: selected)
            let text = if let offline { offline.0 } else { try await runtime.chapterText(chapter: selected) }
            try Task.checkCancellation()
            guard self.request == request else { return }
            // Parsing large chapters off the main actor keeps scrolling responsive.
            self.text = text
            localImages = offline?.1 ?? [:]
            isOffline = offline != nil
            let document: IosNovelDocument? = if let text {
                await Task.detached(priority: .userInitiated) { text.document() }.value
            } else { nil }
            guard self.request == request else { return }
            self.document = document
            lineStyleCache.removeAll(keepingCapacity: true)
            blocks = document.map(NovelBlock.blocks(from:)) ?? []
            var scroll: Float = 0
            if let library, let position = try await library.position(content: content), position.chapterId == selected.id {
                // The stored chapter progress also places positions saved by Android; the line index is the fallback.
                resumeIndex = Int(document?.index(scroll: position.scroll, index: position.index) ?? 0)
                scroll = position.scroll
            } else { resumeIndex = openAtEnd ? max(0, blocks.count - 1) : 0 }
            visibleIndex = resumeIndex
            pagedScroll = scroll > 0 ? scroll : nil
            target = NovelReadingTarget(block: resumeIndex, scroll: scroll, atEnd: openAtEnd)
            openAtEnd = false
            revision += 1
            await persist()
            await refreshBookmarks()
        } catch {
            if !Task.isCancelled, self.request == request {
                self.error = SourceErrorText.describe(error)
                challenge = SourceErrorText.challenge(error)
            }
        }
    }

    /// Opens another chapter at its start, or at its end when going back a page from the first one.
    func move(to target: IosSourceChapter, atEnd: Bool = false) async {
        await persist()
        chapter = target
        lineStyleCache.removeAll()
        blocks = []
        document = nil
        openAtEnd = atEnd
        await load()
    }

    func jump(to target: Int) async {
        guard blocks.indices.contains(target) else { return }
        seek(to: target)
        saveTask?.cancel()
        await persist()
    }

    /// Update the reading target immediately, including accessibility slider adjustments; debounce disk writes.
    func seek(to target: Int) {
        guard blocks.indices.contains(target) else { return }
        resumeIndex = target
        visibleIndex = target
        visibleIndices = []
        pagedScroll = nil
        self.target = NovelReadingTarget(block: target, scroll: 0, atEnd: false)
        saveTask?.cancel()
        saveTask = Task { try? await Task.sleep(for: .milliseconds(600)); if !Task.isCancelled { await persist() } }
    }

    /// The shared document locates the paragraph at the requested chapter percentage.
    func seek(progress: Double) {
        guard let document else { return }
        let units = Float(min(1, max(0, progress)) * 10_000)
        seek(to: Int(document.index(scroll: units, index: 0)))
    }

    /// The chapter laid out for the paged reader with the current paragraph settings.
    func pages(indent: Bool, spacing: Int, bookStyles: Bool) -> IosNovelPages? {
        guard let document else { return nil }
        if let cache = pageCache, cache.indent == indent, cache.spacing == spacing, cache.bookStyles == bookStyles,
           cache.revision == revision {
            return cache.pages
        }
        let pages = document.pages(indent: indent, spacingLines: Int32(spacing), bookStyles: bookStyles)
        pageCache = (indent, spacing, bookStyles, revision, pages)
        return pages
    }

    /// The book's styles of line [index] for the scrolling reader.
    func lineStyle(_ index: Int) -> (runs: [NovelRun], layout: NovelLineLayout?) {
        if let cached = lineStyleCache[index] { return cached }
        guard let document else { return ([], nil) }
        let value = (document.lineStyles(index: Int32(index)).map(NovelRun.init),
                     document.lineParagraph(index: Int32(index)).map(NovelLineLayout.init))
        lineStyleCache[index] = value
        return value
    }

    /// The paged reader turned to a page starting in [block]; [scroll] is the history value of the page start.
    func showPage(block: Int, scroll: Float) {
        guard !isLoading, blocks.indices.contains(block) else { return }
        guard visibleIndex != block || pagedScroll != scroll else { return }
        visibleIndex = block
        pagedScroll = scroll
        saveTask?.cancel()
        saveTask = Task { try? await Task.sleep(for: .milliseconds(600)); if !Task.isCancelled { await persist() } }
    }

    func visible(_ index: Int, isVisible: Bool) {
        guard blocks.indices.contains(index) else { return }
        if isVisible { visibleIndices.insert(index) } else { visibleIndices.remove(index) }
        guard !isLoading, let first = visibleIndices.min(), visibleIndex != first else { return }
        visibleIndex = first
        pagedScroll = nil
        saveTask?.cancel()
        saveTask = Task { try? await Task.sleep(for: .milliseconds(600)); if !Task.isCancelled { await persist() } }
    }

    func persist() async {
        guard !blocks.isEmpty, let library else { return }
        activity.update(library: library, chapter: chapter, index: visibleIndex, count: blocks.count,
                        scroll: pagedScroll ?? document?.scroll(index: Int32(visibleIndex)) ?? 0)
        await activity.flush()
        do { try await library.recordPosition(content: content, chapter: chapter, index: Int32(visibleIndex),
                                              count: Int32(blocks.count),
                                              scroll: pagedScroll ?? document?.scroll(index: Int32(visibleIndex)) ?? 0) }
        catch { self.error = error.localizedDescription }
    }

    func refreshBookmarks() async {
        do { bookmarks = try await library?.bookmarks(content: content) ?? [] }
        catch { self.error = error.localizedDescription }
    }

    func toggleBookmark() async {
        guard let library, blocks.indices.contains(visibleIndex) else { return }
        let preview: String
        switch blocks[visibleIndex] {
        case .text(let value): preview = value
        case .image(let url): preview = url.lastPathComponent.isEmpty ? chapter.title : url.lastPathComponent
        }
        do {
            try await library.toggleNovelBookmark(content: content, chapter: chapter,
                                                  index: Int32(visibleIndex), count: Int32(blocks.count), preview: preview)
            await refreshBookmarks()
        } catch { self.error = error.localizedDescription }
    }

    /// The line a bookmark of the current chapter points at, located by its excerpt as on Android.
    func index(of bookmark: IosBookmark) -> Int {
        Int(document?.bookmarkIndex(bookmark: bookmark, content: content) ?? 0)
    }

    func removeBookmark(_ bookmark: IosBookmark) async {
        do { try await library?.removeBookmark(content: content, bookmark: bookmark); await refreshBookmarks() }
        catch { self.error = error.localizedDescription }
    }
}

private enum NovelReaderAction: String, CaseIterable, Identifiable {
    case bookmark, chapters, typography, downloads
    var id: String { rawValue }
    var title: String {
        switch self {
        case .bookmark: "书签"
        case .chapters: "目录与书签"
        case .typography: "阅读设置"
        case .downloads: "下载队列"
        }
    }
    var symbol: String {
        switch self {
        case .bookmark: "bookmark"
        case .chapters: "list.bullet"
        case .typography: "textformat.size"
        case .downloads: "arrow.down.circle"
        }
    }
    static func selected(_ raw: String) -> [Self] {
        let ids = Set(raw.split(separator: ",").map(String.init))
        return allCases.filter { ids.contains($0.rawValue) }
    }
}

struct NovelReaderView: View {
    private enum ReaderSheet: String, Identifiable {
        case chapters, settings, downloads
        var id: String { rawValue }
    }
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var downloads: ReaderDownloadQueue
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model: NovelReaderModel
    @AppStorage(NovelStyle.Key.mode) private var mode = NovelStyle.Default.mode
    @AppStorage(NovelStyle.Key.theme) private var theme = NovelStyle.Default.theme
    @AppStorage(NovelStyle.Key.font) private var font = NovelStyle.Default.font
    @AppStorage(NovelStyle.Key.fontSize) private var fontSize = NovelStyle.Default.fontSize
    @AppStorage(NovelStyle.Key.lineSpacing) private var lineSpacing = NovelStyle.Default.lineSpacing
    @AppStorage(NovelStyle.Key.paragraphSpacing) private var paragraphSpacing = 0
    @AppStorage(NovelStyle.Key.indent) private var indent = true
    @AppStorage(NovelStyle.Key.marginHorizontal) private var marginHorizontal = NovelStyle.Default.margin
    @AppStorage(NovelStyle.Key.marginVertical) private var marginVertical = NovelStyle.Default.margin
    @AppStorage(NovelStyle.Key.bookStyles) private var bookStyles = true
    @AppStorage(NovelStyle.Key.pageTurn) private var pageTurn = "SLIDE"
    @AppStorage(NovelStyle.Key.floatingActions) private var floatingActions = "bookmark"
    @State private var activeSheet: ReaderSheet?
    @State private var sheetTab = 0
    @State private var downloadError: String?
    @State private var chromeHidden = false
    @State private var showingMore = false
    @State private var moreTab = 0
    @State private var scrubPosition: Double?
    @State private var isScrubbing = false

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        _model = StateObject(wrappedValue: NovelReaderModel(runtime: runtime, content: content, chapter: chapter))
    }

    private var style: NovelStyle {
        NovelStyle(fontSize: fontSize, lineSpacing: lineSpacing, paragraphSpacing: paragraphSpacing, indent: indent,
                   marginHorizontal: Double(marginHorizontal), marginVertical: Double(marginVertical), font: font,
                   colors: NovelColors(theme: theme, dark: colorScheme == .dark), bookStyles: bookStyles,
                   pageTurn: pageTurn)
    }

    private var isPaged: Bool { mode == "PAGED" }

    var body: some View {
        let style = style
        GeometryReader { geometry in
            ZStack {
                if isPaged && !model.blocks.isEmpty && model.error == nil {
                    NovelPagedReader(model: model, style: style, onToggleChrome: toggleChrome)
                } else {
                    scrollReader(style)
                        .ignoresSafeArea(edges: .bottom)
                        .simultaneousGesture(SpatialTapGesture().onEnded { tap in
                            if tap.location.x > geometry.size.width / 3,
                               tap.location.x < geometry.size.width * 2 / 3,
                               tap.location.y > geometry.size.height / 3,
                               tap.location.y < geometry.size.height * 2 / 3 { toggleChrome() }
                        })
                }
                if !chromeHidden { chrome(height: geometry.size.height).transition(.opacity) }
            }
        }
        .background(style.colors.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar, .tabBar)
        .statusBarHidden(chromeHidden)
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .chapters: navigationSheet
            case .settings: NovelReaderSettingsSheet()
            case .downloads: ReaderDownloadsSheet()
            }
        }
        .alert("无法下载", isPresented: Binding(get: { downloadError != nil }, set: { if !$0 { downloadError = nil } })) {
            Button("好") { downloadError = nil }
        } message: { Text(downloadError ?? "") }
        .task {
            model.library = library.library
            await model.load()
        }
        .onAppear { model.setReadingActive(scenePhase == .active) }
        .onChange(of: scenePhase) { _, phase in
            model.setReadingActive(phase == .active)
            Task { await model.persist() }
        }
        .onDisappear {
            model.setReadingActive(false)
            Task {
                await model.persist()
                await model.finishReadingActivity()
                await library.refresh()
                await library.trackingAccounts.syncAfterReading(model.content, tracking: library.library?.tracking)
            }
        }
        .onChange(of: model.chapter.id) { _, _ in showingMore = false; scrubPosition = nil; isScrubbing = false }
        .readerPresence(directories: [
            ReaderStorageMetadata.directoryID(contentID: model.content.id, chapterID: model.chapter.id)
        ])
    }

    private func toggleChrome() {
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.18)) {
            chromeHidden.toggle()
            showingMore = false
        }
    }

    private func perform(_ action: NovelReaderAction) {
        switch action {
        case .bookmark: Task { await model.toggleBookmark() }
        case .chapters: showingMore = false; activeSheet = .chapters
        case .typography: showingMore = false; activeSheet = .settings
        case .downloads: showingMore = false; activeSheet = .downloads
        }
    }

    private func chrome(height: CGFloat) -> some View {
        GlassEffectContainer(spacing: 8) {
            VStack(spacing: 12) {
                if showingMore {
                    morePanel.frame(maxHeight: min(400, max(100, height - 88)))
                } else {
                    HStack {
                        Button("返回", systemImage: "chevron.left") { dismiss() }
                            .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
                            .frame(width: 44, height: 44).accessibilityIdentifier("novel.back")
                        Spacer(minLength: 16)
                        Button { perform(.chapters) } label: {
                            Label(model.chapter.title, systemImage: "list.bullet").font(.subheadline).lineLimit(1)
                                .padding(.horizontal, 14).padding(.vertical, 12)
                        }.buttonStyle(.plain).glassEffect(.regular.interactive(), in: .capsule)
                            .accessibilityIdentifier("novel.chapters")
                        Spacer(minLength: 16)
                        Button { moreTab = 0; showingMore = true } label: {
                            Image(systemName: "ellipsis").frame(width: 44, height: 44)
                        }.buttonStyle(.plain).glassEffect(.regular.interactive(), in: .circle)
                            .accessibilityLabel("更多").accessibilityIdentifier("novel.more")
                    }
                }
                Spacer(minLength: 0)
                let actions = NovelReaderAction.selected(floatingActions)
                if !showingMore && !actions.isEmpty {
                    HStack(spacing: 12) {
                        Spacer()
                        ForEach(actions) { action in
                            Button { perform(action) } label: {
                                Image(systemName: action == .bookmark && model.isBookmarked ? "bookmark.fill" : action.symbol)
                                    .frame(width: 44, height: 44)
                            }.buttonStyle(.plain).glassEffect(.regular.tint(.accentColor).interactive(), in: .circle)
                                .disabled(action == .bookmark && (model.blocks.isEmpty || model.isLoading))
                                .accessibilityLabel(action.title)
                                .accessibilityValue(action == .bookmark ? (model.isBookmarked ? "已添加" : "未添加") : "")
                                .accessibilityIdentifier("novel.floating.\(action.rawValue)")
                        }
                    }
                }
                progressControl
            }.padding(12)
        }
    }

    private var progressControl: some View {
        HStack(spacing: 20) {
            Button("上一章", systemImage: "backward.end.fill") {
                if let previous = model.previous { Task { await model.move(to: previous) } }
            }.labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
                .frame(width: 44, height: 44).disabled(model.previous == nil || model.isLoading)
                .accessibilityIdentifier("novel.previousChapter")
            HStack(spacing: 8) {
                let position = scrubPosition ?? model.progress
                Text("\(Int((position * 100).rounded()))%")
                    .font(.caption.monospacedDigit()).fixedSize()
                Slider(value: Binding(
                    get: { isScrubbing ? (scrubPosition ?? model.progress) : model.progress },
                    set: { value in
                        if isScrubbing { scrubPosition = value }
                        model.seek(progress: value)
                    }
                ), in: 0...1, onEditingChanged: { editing in
                    isScrubbing = editing
                    if !editing {
                        scrubPosition = nil
                        Task { await model.persist() }
                    }
                }).disabled(model.blocks.count <= 1 || model.isLoading)
                    .accessibilityLabel("章节进度")
                    .accessibilityValue("\(Int((position * 100).rounded()))%")
                    .accessibilityIdentifier("novel.progress")
            }.padding(.horizontal, 14).frame(height: 44).glassEffect(.regular, in: .capsule)
            Button("下一章", systemImage: "forward.end.fill") {
                if let next = model.next { Task { await model.move(to: next) } }
            }.labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
                .frame(width: 44, height: 44).disabled(model.next == nil || model.isLoading)
                .accessibilityIdentifier("novel.nextChapter")
        }
    }

    private var morePanel: some View {
        VStack(spacing: 0) {
            HStack {
                Text("更多").font(.headline)
                Spacer()
                Button("收起", systemImage: "xmark") { showingMore = false }
                    .labelStyle(.iconOnly).buttonStyle(.borderless).accessibilityIdentifier("novel.panel.close")
            }.padding(16)
            Divider()
            Picker("内容", selection: $moreTab) {
                Text("快捷").tag(0); Text("控件").tag(1)
            }.pickerStyle(.segmented).padding(12)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if moreTab == 0 {
                        Text(model.content.title).font(.subheadline).foregroundStyle(.secondary)
                        Button(model.isBookmarked ? "取消书签" : "添加书签",
                               systemImage: model.isBookmarked ? "bookmark.fill" : "bookmark") { perform(.bookmark) }
                            .disabled(model.blocks.isEmpty || model.isLoading)
                            .accessibilityIdentifier("novel.bookmark")
                        Button("阅读设置", systemImage: "textformat.size") { perform(.typography) }
                            .accessibilityIdentifier("novel.typography")
                        // Imported files are already on the device.
                        if !model.runtime.isLocal(content: model.content) {
                            downloadMenu
                            if let status = downloadStatus { Text(status).font(.caption).foregroundStyle(.secondary) }
                        }
                        Button("下载队列", systemImage: "arrow.down.circle") { perform(.downloads) }
                            .accessibilityIdentifier("novel.downloads")
                    } else {
                        ForEach(NovelReaderAction.allCases) { action in
                            Toggle(action.title, isOn: Binding(
                                get: { NovelReaderAction.selected(floatingActions).contains(action) },
                                set: { enabled in
                                    var selected = Set(NovelReaderAction.selected(floatingActions))
                                    if enabled { selected.insert(action) } else { selected.remove(action) }
                                    floatingActions = NovelReaderAction.allCases.filter { selected.contains($0) }
                                        .map(\.rawValue).joined(separator: ",")
                                }
                            )).accessibilityIdentifier("novel.floating.option.\(action.rawValue)")
                        }
                        Text("所选悬浮按钮显示在进度条上方，随阅读控件一起收起。可全部关闭，设置自动保存。")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            }
        }.glassEffect(.regular, in: .rect(cornerRadius: 28))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("novel.morePanel")
    }

    /// One line of the scrolling reader, with the book's alignment, heading size and emphasis when enabled.
    private func scrollLine(_ text: String, index: Int, style: NovelStyle) -> some View {
        let book: (runs: [NovelRun], layout: NovelLineLayout?) = style.bookStyles ? model.lineStyle(index) : ([], nil)
        let indented = style.indent && !IosNovelStyle.shared.isTitleLine(line: text) && (book.layout?.indent ?? true)
        let prefix = indented ? "\u{3000}\u{3000}" : ""
        let align: TextAlignment = switch book.layout?.align {
        case .center: .center
        case .right: .trailing
        default: .leading
        }
        let frame: Alignment = switch align {
        case .center: .center
        case .trailing: .trailing
        default: .leading
        }
        return Text(style.styledLine(prefix + text, runs: book.runs, shift: (prefix as NSString).length))
            .lineSpacing(style.lineGap * (book.layout?.scale ?? 1))
            .foregroundStyle(style.colors.text)
            .multilineTextAlignment(align)
            .frame(maxWidth: .infinity, alignment: frame)
            .textSelection(.enabled)
    }

    private func scrollReader(_ style: NovelStyle) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: style.paragraphGap) {
                    Text(model.chapter.title)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(style.colors.text)
                        .padding(.bottom, model.isOffline ? 0 : 8)
                        .id("top")
                    if model.isOffline {
                        Label("已下载 · 离线阅读", systemImage: "arrow.down.circle.fill")
                            .font(.caption).foregroundStyle(style.colors.secondaryText)
                            .padding(.bottom, 8)
                            .accessibilityIdentifier("novel.offline")
                    }
                    if model.isLoading { ProgressView().frame(maxWidth: .infinity).padding(.top, 40) }
                    if let error = model.error {
                        Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                            .accessibilityIdentifier("novel.error")
                        if let challenge = model.challenge {
                            ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                                  sourceName: model.content.sourceName) { await model.load() }
                        }
                        Button("重试") { Task { await model.load() } }.buttonStyle(.glass)
                    }
                    if !model.isLoading && model.error == nil && model.blocks.isEmpty {
                        ContentUnavailableView("本章没有正文", systemImage: "text.page.slash")
                    }
                    ForEach(model.blocks.indices, id: \.self) { index in
                        let block = model.blocks[index]
                        Group {
                        switch block {
                        case .text(let text):
                            scrollLine(text, index: index, style: style)
                                .accessibilityIdentifier(index == 0 ? "novel.firstParagraph" : "novel.paragraph")
                        case .image(let url):
                            HeaderImage(url: model.localImages[url.absoluteString] ?? url,
                                        headers: model.text?.headers(imageUrl: url.absoluteString) ?? [:])
                                .frame(maxWidth: .infinity)
                        }
                        }
                        .id(index)
                        .onScrollVisibilityChange(threshold: 0.5) { visible in
                            model.visible(index, isVisible: visible)
                        }
                    }
                }
                .padding(.horizontal, style.marginHorizontal)
                .padding(.vertical, style.marginVertical)
            }
            .onChange(of: model.target) { _, target in
                if target.block == 0 && !target.atEnd { proxy.scrollTo("top", anchor: .top) }
                else { proxy.scrollTo(target.block, anchor: .top) }
            }
        }
    }

    /// Android's novel download choices: this chapter, the next ones, or every chapter not yet saved.
    private var downloadMenu: some View {
        Menu {
            Section(downloadStatus ?? "") {
                Button("下载本章", systemImage: "arrow.down.circle") { enqueue([model.chapter]) }
                    .disabled(ReaderDownloads.isDownloaded(content: model.content, chapter: model.chapter))
                    .accessibilityIdentifier("novel.download.current")
                Button("下载后续 10 章", systemImage: "arrow.down.to.line") { enqueue(followingChapters(10)) }
                    .disabled(followingChapters(10).isEmpty)
                    .accessibilityIdentifier("novel.download.next")
                Button("下载全部未下载章节", systemImage: "square.and.arrow.down.on.square") {
                    enqueue(model.content.chapters.filter { !ReaderDownloads.isDownloaded(content: model.content, chapter: $0) })
                }
                .accessibilityIdentifier("novel.download.all")
            }
        } label: {
            Label("下载", systemImage: "arrow.down.circle")
        }
        .accessibilityIdentifier("novel.download")
    }

    private var downloadStatus: String? {
        if ReaderDownloads.isDownloaded(content: model.content, chapter: model.chapter) { return "本章已下载，可离线阅读" }
        return downloads.entry(content: model.content, chapter: model.chapter)?.status
    }

    private func followingChapters(_ count: Int) -> [IosSourceChapter] {
        var result: [IosSourceChapter] = []
        var current = model.chapter
        while result.count < count, let next = model.content.adjacentChapter(chapter: current, forward: true) {
            if !ReaderDownloads.isDownloaded(content: model.content, chapter: next) { result.append(next) }
            current = next
        }
        return result
    }

    private func enqueue(_ chapters: [IosSourceChapter]) {
        guard !chapters.isEmpty else { return }
        do { try downloads.enqueue(content: model.content, chapters: chapters) }
        catch { downloadError = error.localizedDescription }
    }

    private var navigationSheet: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("内容", selection: $sheetTab) {
                    Text("章节").tag(0)
                    Text("书签").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(12)
                if sheetTab == 0 {
                    List(model.content.chapters, id: \.id) { item in
                        Button {
                            activeSheet = nil
                            Task { await model.move(to: item) }
                        } label: {
                            HStack {
                                Text(item.title)
                                Spacer()
                                if ReaderDownloads.isDownloaded(content: model.content, chapter: item) {
                                    Image(systemName: "arrow.down.circle.fill").foregroundStyle(.secondary)
                                        .accessibilityLabel("已下载")
                                }
                                if item.id == model.chapter.id { Image(systemName: "book.fill") }
                            }
                        }
                        .accessibilityIdentifier("novel.chapter.\(item.id)")
                        .swipeActions {
                            if !model.runtime.isLocal(content: model.content),
                               !ReaderDownloads.isDownloaded(content: model.content, chapter: item) {
                                Button("下载", systemImage: "arrow.down.circle") { enqueue([item]) }.tint(.accentColor)
                            }
                        }
                    }
                } else if model.bookmarks.isEmpty {
                    ContentUnavailableView("暂无书签", systemImage: "bookmark")
                } else {
                    List {
                        ForEach(model.bookmarks, id: \.id) { bookmark in
                            Button {
                                activeSheet = nil
                                Task {
                                    if bookmark.chapterId != model.chapter.id,
                                       let target = model.content.chapters.first(where: { $0.id == bookmark.chapterId }) {
                                        await model.move(to: target)
                                    }
                                    await model.jump(to: model.index(of: bookmark))
                                }
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(model.content.chapters.first { $0.id == bookmark.chapterId }?.title ?? "章节")
                                        .font(.subheadline.weight(.medium))
                                    Text("第 \(bookmark.index + 1) 段 · \(bookmark.preview)")
                                        .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                }
                            }
                            .swipeActions {
                                Button("删除", role: .destructive) { Task { await model.removeBookmark(bookmark) } }
                            }
                        }
                    }
                }
            }
            .navigationTitle("目录与书签")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { activeSheet = nil } } }
        }
        .presentationDetents([.medium, .large])
    }

}

/// An illustration loaded with the headers the source requires (most novel sites check the Referer).
struct HeaderImage: View {
    let url: URL
    let headers: [String: String]
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                RoundedRectangle(cornerRadius: 8).fill(.quaternary).frame(height: 160)
                    .overlay { if failed { Image(systemName: "photo.badge.exclamationmark").foregroundStyle(.secondary) } }
            }
        }
        .task(id: url) {
            do {
                let data = try await NovelImageData.load(url, headers: headers)
                // Downsample off the main actor; illustrations can be very large.
                image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                          let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                              kCGImageSourceCreateThumbnailFromImageAlways: true,
                              kCGImageSourceThumbnailMaxPixelSize: 2048,
                              kCGImageSourceCreateThumbnailWithTransform: true,
                          ] as CFDictionary) else { return nil }
                    return UIImage(cgImage: cgImage)
                }.value
                failed = image == nil
            } catch {
                if !Task.isCancelled { failed = true }
            }
        }
    }
}

// MARK: - Reading style

/// Theme colors from the palette shared with Android.
struct NovelColors: Equatable {
    let background: Color
    let text: Color
    let secondaryText: Color
    let uiText: UIColor
    let uiBackground: UIColor

    init(theme: String, dark: Bool) {
        let colors = IosNovelStyle.shared.colors(theme: theme, dark: dark)
        background = Color(uiColor: UIColor(argb: colors.background))
        text = Color(uiColor: UIColor(argb: colors.text))
        secondaryText = Color(uiColor: UIColor(argb: colors.secondaryText))
        uiText = UIColor(argb: colors.text)
        uiBackground = UIColor(argb: colors.background)
    }
}

extension UIColor {
    convenience init(argb: Int64) {
        let value = UInt32(truncatingIfNeeded: argb)
        self.init(red: CGFloat((value >> 16) & 0xFF) / 255, green: CGFloat((value >> 8) & 0xFF) / 255,
                  blue: CGFloat(value & 0xFF) / 255, alpha: CGFloat((value >> 24) & 0xFF) / 255)
    }
}

/// Android's novel reader settings: the line height is the font size times [lineSpacing], paragraphs are separated
/// by [paragraphSpacing] blank lines and indented by two full-width spaces except title lines.
struct NovelStyle: Equatable {
    enum Key {
        static let mode = "novel.reader.mode"
        static let theme = "novel.reader.theme"
        static let font = "novel.reader.font"
        static let fontSize = "novel.reader.fontSize"
        static let lineSpacing = "novel.reader.lineSpacing"
        static let paragraphSpacing = "novel.reader.paragraphSpacing"
        static let indent = "novel.reader.indent"
        static let marginHorizontal = "novel.reader.marginHorizontal"
        static let marginVertical = "novel.reader.marginVertical"
        static let bookStyles = "novel.reader.bookStyles"
        static let pageTurn = "novel.reader.pageTurn"
        static let floatingActions = "novel.reader.floatingActions"
    }

    enum Default {
        static let mode = "PAGED"
        static let theme = "PAPER"
        static let font = "SYSTEM_SERIF"
        static let fontSize = Double(IosNovelStyle.shared.defaultFontSize)
        static let lineSpacing = Double(IosNovelStyle.shared.defaultLineSpacing)
        static let margin = Int(IosNovelStyle.shared.defaultMargin)
    }

    var fontSize: Double
    var lineSpacing: Double
    var paragraphSpacing: Int
    var indent: Bool
    var marginHorizontal: Double
    var marginVertical: Double
    var font: String
    var colors: NovelColors
    /// Apply the book's own headings, alignment, emphasis and ruby (EPUB and styled source HTML).
    var bookStyles: Bool
    /// Android's page turn animation: "SLIDE" or "SIMULATION" (a page curl).
    var pageTurn: String

    var lineHeight: CGFloat { CGFloat(fontSize * lineSpacing) }

    var uiFont: UIFont {
        let size = CGFloat(fontSize)
        switch font {
        case "SYSTEM_SANS": return .systemFont(ofSize: size)
        case "SYSTEM_MONOSPACE": return .monospacedSystemFont(ofSize: size, weight: .regular)
        default:
            // Songti is not installed by default; the serif design then falls back to the system's CJK fonts.
            if let songti = UIFont(name: "STSongti-SC-Regular", size: size) { return songti }
            let descriptor = UIFont.systemFont(ofSize: size).fontDescriptor.withDesign(.serif)
            return descriptor.map { UIFont(descriptor: $0, size: size) } ?? .systemFont(ofSize: size)
        }
    }

    /// Extra space between lines for SwiftUI text, matching [lineHeight].
    var lineGap: CGFloat { max(0, lineHeight - uiFont.lineHeight) }

    /// Space between paragraphs in the scrolling reader: one line gap plus the blank lines.
    var paragraphGap: CGFloat { lineGap + CGFloat(paragraphSpacing) * lineHeight }

    func attributes() -> [NSAttributedString.Key: Any] {
        let font = uiFont
        let paragraph = NSMutableParagraphStyle()
        paragraph.minimumLineHeight = lineHeight
        paragraph.maximumLineHeight = lineHeight
        paragraph.lineBreakStrategy = .standard
        return [.font: font, .foregroundColor: colors.uiText, .paragraphStyle: paragraph,
                .baselineOffset: max(0, (lineHeight - font.lineHeight) / 4)]
    }

    /// [text] with the reader's attributes and the book's line layouts and inline styles laid over them.
    func attributed(_ text: String, runs: [NovelRun], lines: [NovelLineLayout]) -> NSAttributedString {
        let result = NSMutableAttributedString(string: text, attributes: attributes())
        let length = result.length
        func clamp(_ range: NSRange) -> NSRange? {
            let start = min(max(0, range.location), length)
            let end = min(max(start, range.location + range.length), length)
            return end > start ? NSRange(location: start, length: end - start) : nil
        }
        for line in lines {
            guard let range = clamp(line.range) else { continue }
            let height = lineHeight * line.scale
            let paragraph = NSMutableParagraphStyle()
            paragraph.lineBreakStrategy = .standard
            paragraph.minimumLineHeight = height
            // Readings sit above the line; let such lines grow rather than clip them.
            paragraph.maximumLineHeight = line.ruby ? 0 : height
            paragraph.alignment = line.align
            result.addAttribute(.paragraphStyle, value: paragraph, range: range)
            result.addAttribute(.baselineOffset, value: max(0, (height - uiFont.lineHeight * line.scale) / 4), range: range)
        }
        for run in runs {
            guard let range = clamp(run.range) else { continue }
            if let ruby = run.ruby {
                let annotation = CTRubyAnnotationCreateWithAttributes(.auto, .auto, .before, ruby as CFString,
                    [kCTRubyAnnotationSizeFactorAttributeName: 0.5] as CFDictionary)
                result.addAttribute(NSAttributedString.Key(kCTRubyAnnotationAttributeName as String), value: annotation,
                                    range: range)
                continue
            }
            let font = styledFont(run)
            result.addAttribute(.font, value: font, range: range)
            // A font without a bold or italic face gets a stroked outline or a slant instead. Even with an italic
            // face, Chinese and Japanese characters come from fallback fonts that have none, so they are slanted.
            let ownFace = font.fontName != uiFont.withSize(font.pointSize).fontName
            let traits = font.fontDescriptor.symbolicTraits
            if run.bold && !(ownFace && traits.contains(.traitBold)) {
                result.addAttribute(.strokeWidth, value: -3, range: range)
                result.addAttribute(.strokeColor, value: colors.uiText, range: range)
            }
            if run.italic {
                let slanted = ownFace && traits.contains(.traitItalic)
                    ? Self.cjkRanges(in: result.string as NSString, range: range) : [range]
                slanted.forEach { result.addAttribute(.obliqueness, value: 0.2, range: $0) }
            }
            if run.underline { result.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: range) }
            if run.strike { result.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: range) }
            if run.vertical != 0 {
                result.addAttribute(.baselineOffset, value: CGFloat(run.vertical) * CGFloat(fontSize) * 0.35, range: range)
            }
        }
        return result
    }

    /// Runs of Chinese, Japanese and Korean characters (and full-width forms) within [range].
    static func cjkRanges(in string: NSString, range: NSRange) -> [NSRange] {
        var result: [NSRange] = []
        string.enumerateSubstrings(in: range, options: .byComposedCharacterSequences) { character, subrange, _, _ in
            guard let value = character?.unicodeScalars.first?.value else { return }
            switch value {
            case 0x2E80...0x9FFF, 0xAC00...0xD7AF, 0xF900...0xFAFF, 0xFE30...0xFE4F, 0xFF00...0xFFEF, 0x20000...0x3FFFF:
                if let last = result.last, NSMaxRange(last) == subrange.location {
                    result[result.count - 1] = NSRange(location: last.location, length: last.length + subrange.length)
                } else {
                    result.append(subrange)
                }
            default: break
            }
        }
        return result
    }

    /// The reader font at the run's size, with bold or italic faces when the font has them.
    func styledFont(_ run: NovelRun) -> UIFont {
        let base = uiFont.withSize(CGFloat(fontSize) * run.scale)
        var traits = base.fontDescriptor.symbolicTraits
        if run.bold { traits.insert(.traitBold) }
        if run.italic { traits.insert(.traitItalic) }
        guard traits != base.fontDescriptor.symbolicTraits,
              let descriptor = base.fontDescriptor.withSymbolicTraits(traits) else { return base }
        return UIFont(descriptor: descriptor, size: base.pointSize)
    }

    /// A line of the scrolling reader with the book's inline styles (readings are left out there).
    func styledLine(_ text: String, runs: [NovelRun], shift: Int) -> AttributedString {
        var result = AttributedString(text)
        result.font = Font(uiFont)
        guard !runs.isEmpty else { return result }
        let length = (text as NSString).length
        for run in runs where run.ruby == nil {
            let start = min(max(0, run.range.location + shift), length)
            let end = min(max(start, run.range.location + run.range.length + shift), length)
            guard end > start, let stringRange = Range(NSRange(location: start, length: end - start), in: text),
                  let range = Range(stringRange, in: result) else { continue }
            result[range].font = Font(styledFont(run))
            if run.underline { result[range].underlineStyle = .single }
            if run.strike { result[range].strikethroughStyle = .single }
            if run.vertical != 0 { result[range].baselineOffset = CGFloat(run.vertical) * CGFloat(fontSize) * 0.35 }
        }
        return result
    }
}

/// An inline style or ruby reading of the book, in UTF-16 offsets of the laid-out text.
struct NovelRun {
    let range: NSRange
    let bold: Bool
    let italic: Bool
    let underline: Bool
    let strike: Bool
    let vertical: Int
    let scale: CGFloat
    let ruby: String?

    init(_ run: IosNovelStyleRun) {
        range = NSRange(location: Int(run.start), length: Int(run.end - run.start))
        bold = run.bold; italic = run.italic; underline = run.underline; strike = run.strike
        vertical = Int(run.vertical); scale = CGFloat(run.scale); ruby = run.ruby
    }
}

/// The book's layout of one line: alignment, the largest font scale on it, whether it carries readings.
struct NovelLineLayout {
    let range: NSRange
    let align: NSTextAlignment
    let scale: CGFloat
    let ruby: Bool
    let heading: Int
    let indent: Bool

    init(_ line: IosNovelParagraphRun) {
        range = NSRange(location: Int(line.start), length: Int(line.end - line.start))
        align = [NSTextAlignment.natural, .center, .right, .justified][min(3, max(0, Int(line.align)))]
        scale = CGFloat(line.scale); ruby = line.ruby; heading = Int(line.heading); indent = line.indent
    }
}

// MARK: - Paged reader

/// One laid-out page: a range of a text segment, or a picture.
struct NovelLaidOutPage: Equatable {
    let segment: Int
    let start: Int
    let end: Int
    let text: NSAttributedString?
    let imageUrl: String?
}

/// One segment of the chapter as handed to the paginator: styled text, or a picture.
struct NovelLayoutSegment {
    let text: String?
    let imageUrl: String?
    let runs: [NovelRun]
    let lines: [NovelLineLayout]
}

/// Pages laid out with TextKit 2 and split into whole lines by the rule Android's paged reader uses.
enum NovelPaginator {
    static func layout(segments: [NovelLayoutSegment], style: NovelStyle, size: CGSize) -> [NovelLaidOutPage] {
        guard size.width > 1, size.height > 1 else { return [] }
        var pages: [NovelLaidOutPage] = []
        for (index, segment) in segments.enumerated() {
            if let text = segment.text {
                let attributed = style.attributed(text, runs: segment.runs, lines: segment.lines)
                let starts = lineStarts(attributed, size: size)
                let string = attributed.string as NSString
                for (page, start) in starts.enumerated() {
                    let end = page + 1 < starts.count ? starts[page + 1] : string.length
                    // A page does not begin or end with the blank lines between paragraphs.
                    var first = start
                    while first < end, string.character(at: first) == 0x0A { first += 1 }
                    var last = end
                    while last > first, string.character(at: last - 1) == 0x0A { last -= 1 }
                    guard last > first else { continue }
                    let body = attributed.attributedSubstring(from: NSRange(location: first, length: last - first))
                    pages.append(NovelLaidOutPage(segment: index, start: start, end: end, text: body, imageUrl: nil))
                }
            } else if let url = segment.imageUrl {
                pages.append(NovelLaidOutPage(segment: index, start: 0, end: 0, text: nil, imageUrl: url))
            }
        }
        return pages
    }

    /// UTF-16 offset of the first line of each page.
    private static func lineStarts(_ text: NSAttributedString, size: CGSize) -> [Int] {
        let storage = NSTextContentStorage()
        storage.attributedString = text
        let layout = NSTextLayoutManager()
        storage.addTextLayoutManager(layout)
        let container = NSTextContainer(size: CGSize(width: size.width, height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        layout.textContainer = container
        layout.ensureLayout(for: layout.documentRange)
        var tops: [Float] = []
        var bottoms: [Float] = []
        var starts: [Int] = []
        layout.enumerateTextLayoutFragments(from: layout.documentRange.location, options: [.ensuresLayout]) { fragment in
            let origin = fragment.layoutFragmentFrame.origin
            let paragraph = storage.offset(from: storage.documentRange.location, to: fragment.rangeInElement.location)
            for line in fragment.textLineFragments {
                let bounds = line.typographicBounds
                tops.append(Float(origin.y + bounds.minY))
                bottoms.append(Float(origin.y + bounds.maxY))
                starts.append(paragraph + line.characterRange.location)
            }
            return true
        }
        guard !starts.isEmpty else { return [0] }
        let first = IosNovelStyle.shared.pageStarts(tops: floats(tops), bottoms: floats(bottoms),
                                                     pageHeight: Int32(size.height.rounded(.down)))
        return (0..<Int(first.size)).map { starts[Int(first.get(index: Int32($0)))] }
    }

    private static func floats(_ values: [Float]) -> KotlinFloatArray {
        let array = KotlinFloatArray(size: Int32(values.count))
        for (index, value) in values.enumerated() { array.set(index: Int32(index), value: value) }
        return array
    }
}

/// Horizontal pages of the current chapter. Tapping the left or right third turns the page (into the neighbouring
/// chapter at either end), the middle toggles the reader chrome.
struct NovelPagedReader: View {
    @ObservedObject var model: NovelReaderModel
    let style: NovelStyle
    let onToggleChrome: () -> Void
    @State private var pages: [NovelLaidOutPage] = []
    @State private var current: Int?
    @State private var appliedTarget: UUID?
    /// The chapter revision [pages] were laid out for; targets wait for the matching layout.
    @State private var laidOutRevision = -1
    /// Space kept for the sensor housing; refreshed on rotation, since turning between the two landscape
    /// orientations moves it to the other side without changing the size.
    @State private var cutout = UIEdgeInsets.zero
    /// Increases with every new layout, so the pager drops pages it built from the previous one.
    @State private var layoutVersion = 0
    private struct LayoutKey: Equatable {
        let revision: Int
        let style: NovelStyle
        let size: CGSize
    }

    var body: some View {
        GeometryReader { geometry in
            let insets = cutout
            let content = CGSize(width: geometry.size.width - insets.left - insets.right - 2 * style.marginHorizontal,
                                 height: geometry.size.height - insets.top - 2 * style.marginVertical)
            NovelPager(count: pages.count, current: $current, curl: style.pageTurn == "SIMULATION",
                       version: layoutVersion, background: style.colors.uiBackground,
                       page: { index in
                           page(pages[index], index: index, content: content)
                               .padding(.top, insets.top)
                               .padding(.leading, insets.left)
                               .padding(.trailing, insets.right)
                               .frame(width: geometry.size.width, height: geometry.size.height)
                               .background(style.colors.background)
                       },
                       onEdge: crossChapter, onCenterTap: onToggleChrome)
            // The turn style is fixed when a page view controller is made.
            .id(style.pageTurn)
            .task(id: LayoutKey(revision: model.revision, style: style, size: content)) {
                await relayout(size: content)
            }
            .onChange(of: current) { _, index in report(index) }
            .onChange(of: model.target) { _, _ in applyTarget() }
        }
        .onAppear { cutout = Self.cutoutInsets }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
            // The interface turns after the device; read the scene once it has.
            Task { try? await Task.sleep(for: .milliseconds(350)); cutout = Self.cutoutInsets }
        }
        // Pages fill the screen and the chrome floats over them, as on Android. Text only keeps clear of the
        // sensor housing and status bar, which the chrome does not change, so toggling it never reflows the chapter;
        // the home indicator is no cutout and the footer may sit over its area.
        .ignoresSafeArea()
    }

    /// The window's safe area on the side of the sensor housing only: the top in portrait, one side in landscape
    /// (the system reports both sides there, but the housing is on one).
    private static var cutoutInsets: UIEdgeInsets {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              let insets = scene.windows.first(where: \.isKeyWindow)?.safeAreaInsets else { return .zero }
        switch scene.effectiveGeometry.interfaceOrientation {
        case .landscapeRight: return UIEdgeInsets(top: 0, left: insets.left, bottom: 0, right: 0)
        case .landscapeLeft: return UIEdgeInsets(top: 0, left: 0, bottom: 0, right: insets.right)
        default: return UIEdgeInsets(top: insets.top, left: 0, bottom: 0, right: 0)
        }
    }

    @ViewBuilder
    private func page(_ page: NovelLaidOutPage, index: Int, content: CGSize) -> some View {
        VStack(spacing: 0) {
            Group {
                if let text = page.text {
                    NovelPageText(text: text)
                        .frame(width: content.width, height: content.height, alignment: .topLeading)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(text.string)
                        .accessibilityAddTraits(.isStaticText)
                        .accessibilityIdentifier(index == 0 ? "novel.firstParagraph" : "novel.page")
                } else if let address = page.imageUrl, let url = URL(string: address) {
                    HeaderImage(url: model.localImages[url.absoluteString] ?? url,
                                headers: model.text?.headers(imageUrl: url.absoluteString) ?? [:])
                        .frame(width: content.width, height: content.height)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, style.marginVertical)
        .overlay(alignment: .bottom) {
            // The footer belongs to the configured lower margin, not a second inset taken from the text.
            // Small margins keep a smaller footer instead of overlapping the last line.
            HStack {
                Text(model.chapter.title).lineLimit(1)
                Spacer()
                Text("\(index + 1) / \(pages.count)").monospacedDigit()
            }
            .font(.system(size: min(11, style.marginVertical * 0.75)))
            .foregroundStyle(style.colors.secondaryText)
            .frame(height: style.marginVertical)
            .padding(.horizontal, style.marginHorizontal)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("novel.footer")
        }
    }

    private func relayout(size: CGSize) async {
        let revision = model.revision
        guard let source = model.pages(indent: style.indent, spacing: style.paragraphSpacing, bookStyles: style.bookStyles)
        else {
            pages = []
            return
        }
        // Keep the page start in view across a new font size or orientation.
        let anchor = current.flatMap { pages.indices.contains($0) ? pages[$0] : nil }
        let segments = (0..<Int(source.count)).map { index in
            let segment = Int32(index)
            return NovelLayoutSegment(text: source.text(segment: segment), imageUrl: source.imageUrl(segment: segment),
                                      runs: source.styles(segment: segment).map(NovelRun.init),
                                      lines: source.paragraphs(segment: segment).map(NovelLineLayout.init))
        }
        let style = style
        let laidOut = await Task.detached(priority: .userInitiated) {
            NovelPaginator.layout(segments: segments, style: style, size: size)
        }.value
        guard !Task.isCancelled, revision == model.revision else { return }
        let sameChapter = laidOutRevision == revision
        pages = laidOut
        layoutVersion += 1
        laidOutRevision = revision
        if appliedTarget != model.target.id {
            applyTarget()
        } else if sameChapter, let anchor {
            current = pageIndex(segment: anchor.segment, offset: anchor.start)
        }
    }

    private func applyTarget() {
        guard !pages.isEmpty, laidOutRevision == model.revision,
              let source = model.pages(indent: style.indent, spacing: style.paragraphSpacing, bookStyles: style.bookStyles)
        else { return }
        let target = model.target
        appliedTarget = target.id
        if target.atEnd {
            current = pages.count - 1
            return
        }
        let position = source.position(scroll: target.scroll, index: Int32(target.block))
        current = pageIndex(segment: Int(position.segment), offset: Int(position.offset))
    }

    /// The page of [segment] holding [offset].
    private func pageIndex(segment: Int, offset: Int) -> Int {
        var found: Int?
        for (index, page) in pages.enumerated() {
            if page.segment < segment || (page.segment == segment && page.start <= offset) { found = index }
            else { break }
        }
        return found ?? 0
    }

    private func report(_ index: Int?) {
        guard let index, pages.indices.contains(index),
              let source = model.pages(indent: style.indent, spacing: style.paragraphSpacing, bookStyles: style.bookStyles)
        else { return }
        let page = pages[index]
        let segment = Int32(page.segment)
        model.showPage(block: Int(source.block(segment: segment, offset: Int32(page.start))),
                       scroll: source.scroll(segment: segment, offset: Int32(page.start)))
    }

    /// A tap past the first or last page opens the neighbouring chapter.
    private func crossChapter(forward: Bool) {
        if forward, let next = model.next { Task { await model.move(to: next) } }
        else if !forward, let previous = model.previous { Task { await model.move(to: previous, atEnd: true) } }
    }
}

/// Pages in a `UIPageViewController`: sliding pages, or the system page curl for Android's simulation turn.
/// Taps on the outer thirds turn the page (animated, so they curl too), the middle third toggles the chrome; a tap
/// while text is selected only clears the selection. Page text stays selectable by a long press.
struct NovelPager<Page: View>: UIViewControllerRepresentable {
    let count: Int
    @Binding var current: Int?
    let curl: Bool
    let version: Int
    let background: UIColor
    let page: (Int) -> Page
    let onEdge: (_ forward: Bool) -> Void
    let onCenterTap: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIViewController(context: Context) -> UIPageViewController {
        let controller = UIPageViewController(
            transitionStyle: curl ? .pageCurl : .scroll, navigationOrientation: .horizontal,
            options: curl ? [.spineLocation: UIPageViewController.SpineLocation.min.rawValue] : [.interPageSpacing: 0])
        controller.dataSource = context.coordinator
        controller.delegate = context.coordinator
        controller.view.backgroundColor = background
        // The curl's own edge taps would race ours; turning by tap is ours alone.
        controller.gestureRecognizers.filter { $0 is UITapGestureRecognizer }.forEach { $0.isEnabled = false }
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.delegate = context.coordinator
        controller.view.addGestureRecognizer(tap)
        context.coordinator.controller = controller
        return controller
    }

    func updateUIViewController(_ controller: UIPageViewController, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        controller.view.backgroundColor = background
        guard count > 0 else { return }
        let target = min(max(0, current ?? 0), count - 1)
        if coordinator.version != version || coordinator.shownIndex != target {
            let direction: UIPageViewController.NavigationDirection =
                coordinator.version == version && target < (coordinator.shownIndex ?? 0) ? .reverse : .forward
            coordinator.version = version
            coordinator.show(target, direction: direction, animated: false)
        } else {
            coordinator.refreshShown()
        }
    }

    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate,
        UIGestureRecognizerDelegate {
        var parent: NovelPager
        weak var controller: UIPageViewController?
        var version = -1

        init(_ parent: NovelPager) { self.parent = parent }

        final class PageHost: UIHostingController<AnyView> {
            let index: Int
            init(index: Int, view: AnyView, background: UIColor) {
                self.index = index
                super.init(rootView: view)
                safeAreaRegions = []
                self.view.backgroundColor = background
            }
            @MainActor required dynamic init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
        }

        var shownIndex: Int? { (controller?.viewControllers?.first as? PageHost)?.index }

        func host(_ index: Int) -> PageHost? {
            guard (0..<parent.count).contains(index) else { return nil }
            return PageHost(index: index, view: AnyView(parent.page(index)), background: parent.background)
        }

        func show(_ index: Int, direction: UIPageViewController.NavigationDirection, animated: Bool,
                  completion: (() -> Void)? = nil) {
            guard let controller, let page = host(index) else { return }
            controller.setViewControllers([page], direction: direction, animated: animated) { _ in completion?() }
        }

        /// New settings or a new chapter title reach the page on screen without rebuilding it.
        func refreshShown() {
            guard let page = controller?.viewControllers?.first as? PageHost,
                  (0..<parent.count).contains(page.index) else { return }
            page.rootView = AnyView(parent.page(page.index))
            page.view.backgroundColor = parent.background
        }

        func pageViewController(_ pageViewController: UIPageViewController,
                                viewControllerBefore viewController: UIViewController) -> UIViewController? {
            (viewController as? PageHost).flatMap { host($0.index - 1) }
        }

        func pageViewController(_ pageViewController: UIPageViewController,
                                viewControllerAfter viewController: UIViewController) -> UIViewController? {
            (viewController as? PageHost).flatMap { host($0.index + 1) }
        }

        func pageViewController(_ pageViewController: UIPageViewController, didFinishAnimating finished: Bool,
                                previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
            if completed, let index = shownIndex { parent.current = index }
        }

        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard let view = gesture.view else { return }
            if let selected = Self.selectedText(in: view) {
                selected.selectedTextRange = nil
                return
            }
            let x = gesture.location(in: view).x
            let width = view.bounds.width
            let index = shownIndex ?? 0
            if x < width / 3 {
                if index > 0 { turn(to: index - 1, direction: .reverse) } else { parent.onEdge(false) }
            } else if x > width * 2 / 3 {
                if index + 1 < parent.count { turn(to: index + 1, direction: .forward) } else { parent.onEdge(true) }
            } else {
                parent.onCenterTap()
            }
        }

        /// The binding moves first: SwiftUI updates during the animation then see the page already shown.
        private func turn(to index: Int, direction: UIPageViewController.NavigationDirection) {
            parent.current = index
            show(index, direction: direction, animated: true)
        }

        /// A page text view holding a selection, if any.
        private static func selectedText(in view: UIView) -> UITextView? {
            if let text = view as? UITextView, text.selectedRange.length > 0 { return text }
            for child in view.subviews { if let found = selectedText(in: child) { return found } }
            return nil
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }
}

/// Page text drawn by TextKit 2 with the attributes it was measured with, so every line the paginator counted fits.
/// It is selectable; links are not followed.
struct NovelPageText: UIViewRepresentable {
    let text: NSAttributedString

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView(usingTextLayoutManager: true)
        view.isEditable = false
        // Long press selects text for the system menu (copy, look up, translate, share); taps reach the pager.
        view.isSelectable = true
        view.isScrollEnabled = false
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        if view.attributedText != text { view.attributedText = text }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 0, height: proposal.height ?? 0)
    }
}

// MARK: - Settings

/// The Android reader's typography and theme settings, stored per device.
struct NovelReaderSettingsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @AppStorage(NovelStyle.Key.mode) private var mode = NovelStyle.Default.mode
    @AppStorage(NovelStyle.Key.theme) private var theme = NovelStyle.Default.theme
    @AppStorage(NovelStyle.Key.font) private var font = NovelStyle.Default.font
    @AppStorage(NovelStyle.Key.fontSize) private var fontSize = NovelStyle.Default.fontSize
    @AppStorage(NovelStyle.Key.lineSpacing) private var lineSpacing = NovelStyle.Default.lineSpacing
    @AppStorage(NovelStyle.Key.paragraphSpacing) private var paragraphSpacing = 0
    @AppStorage(NovelStyle.Key.indent) private var indent = true
    @AppStorage(NovelStyle.Key.marginHorizontal) private var marginHorizontal = NovelStyle.Default.margin
    @AppStorage(NovelStyle.Key.marginVertical) private var marginVertical = NovelStyle.Default.margin
    @AppStorage(NovelStyle.Key.bookStyles) private var bookStyles = true
    @AppStorage(NovelStyle.Key.pageTurn) private var pageTurn = "SLIDE"

    private let limits = IosNovelStyle.shared
    private static let themeNames = ["PAPER": "纸张", "SEPIA": "旧书", "MOSS": "苔绿", "SLATE": "石板"]

    var body: some View {
        NavigationStack {
            Form {
                Section("阅读模式") {
                    Picker("模式", selection: $mode) {
                        Text("分页").tag("PAGED")
                        Text("滚动").tag("SCROLL")
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("novel.settings.mode")
                    if mode == "PAGED" {
                        Picker("翻页效果", selection: $pageTurn) {
                            Text("滑动").tag("SLIDE")
                            Text("仿真").tag("SIMULATION")
                        }
                        .accessibilityIdentifier("novel.settings.pageTurn")
                    }
                }
                Section("主题") {
                    HStack(spacing: 12) {
                        ForEach(limits.themes, id: \.self) { name in
                            let colors = NovelColors(theme: name, dark: colorScheme == .dark)
                            Button { theme = name } label: {
                                VStack(spacing: 4) {
                                    Circle().fill(colors.background)
                                        .overlay { Text("文").foregroundStyle(colors.text) }
                                        .overlay { Circle().stroke(theme == name ? Color.accentColor : .secondary.opacity(0.3),
                                                                   lineWidth: theme == name ? 3 : 1) }
                                        .frame(width: 44, height: 44)
                                    Text(Self.themeNames[name] ?? name).font(.caption)
                                }
                            }
                            .buttonStyle(.plain)
                            .frame(maxWidth: .infinity)
                        }
                    }
                }
                Section {
                    Picker("字体", selection: $font) {
                        Text("衬线").tag("SYSTEM_SERIF")
                        Text("无衬线").tag("SYSTEM_SANS")
                        Text("等宽").tag("SYSTEM_MONOSPACE")
                    }
                    slider("字号", value: $fontSize, range: Double(limits.minFontSize)...Double(limits.maxFontSize),
                           step: Double(limits.fontSizeStep), format: "%.1f")
                    slider("行距", value: $lineSpacing, range: Double(limits.minLineSpacing)...Double(limits.maxLineSpacing),
                           step: Double(limits.lineSpacingStep), format: "%.1f")
                    Stepper("段间空行 \(paragraphSpacing)", value: $paragraphSpacing, in: 0...Int(limits.maxParagraphSpacing))
                    Toggle("段首缩进", isOn: $indent)
                    Toggle("书籍排版", isOn: $bookStyles)
                        .accessibilityIdentifier("novel.settings.bookStyles")
                } header: {
                    Text("文字")
                } footer: {
                    Text("书籍排版：使用书籍自带的标题、对齐、粗斜体与注音（EPUB 与带样式的正文）。")
                }
                Section("边距") {
                    Stepper("左右 \(marginHorizontal)", value: $marginHorizontal,
                            in: Int(limits.minMargin)...Int(limits.maxMargin), step: Int(limits.marginStep))
                    Stepper("上下 \(marginVertical)", value: $marginVertical,
                            in: Int(limits.minMargin)...Int(limits.maxMargin), step: Int(limits.marginStep))
                }
            }
            .navigationTitle("阅读设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func slider(_ title: String, value: Binding<Double>, range: ClosedRange<Double>, step: Double,
                        format: String) -> some View {
        VStack(alignment: .leading) {
            Text("\(title) \(String(format: format, value.wrappedValue))")
            Slider(value: value, in: range, step: step)
        }
    }
}
