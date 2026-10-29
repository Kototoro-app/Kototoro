import Combine
import CoreText
import ImageIO
import KototoroShared
import SwiftUI
import Translation

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
final class NovelReaderModel: NSObject, ObservableObject {
    private lazy var activity = ReadingActivityRecorder(content: content)

    func setReadingActive(_ value: Bool) {
        activity.setActive(value)
        if !value { Task { await activity.flush() } }
    }

    func finishReadingActivity() async {
        activity.setActive(false)
        await activity.flush()
    }
    @Published private(set) var chapter: IosSourceChapter {
        didSet { displayTitle = chapter.title }
    }
    /// The chapter title after the title replacement rules.
    @Published private(set) var displayTitle = ""
    @Published private(set) var blocks: [NovelBlock] = []
    /// The chapter shown: the converted chapter, or it with its translations; maps blocks to the chapter progress
    /// Android also reads and writes (a translated copy keeps the original's positions).
    private var document: IosNovelDocument?
    /// The converted chapter without translations.
    private(set) var sourceDocument: IosNovelDocument?
    /// The reader translates chapters as they open (Android's translate action, kept for the session).
    @Published private(set) var translationEnabled = false
    /// Translations of the current chapter's lines, by block index.
    @Published private(set) var translations: [Int: String] = [:]
    @Published var translationStatus = NovelTranslationStatus.idle
    /// Starts the view's translation task; a new value (or `invalidate()`) translates what is still missing.
    @Published var translationConfiguration: TranslationSession.Configuration?
    private(set) var translationBilingual = UserDefaults.standard.bool(forKey: NovelTranslationSettings.bilingualKey)
    private var translationTarget = NovelTranslationSettings.target(
        UserDefaults.standard.string(forKey: NovelTranslationSettings.targetKey) ?? "")
    /// The original language chosen in the settings; nil recognizes it from the chapter.
    private var translationSource = NovelTranslationSettings.chosenSource(
        UserDefaults.standard.string(forKey: NovelTranslationSettings.sourceKey) ?? "")
    var translationKey: String {
        NovelTranslationCache.key(content: content.id, chapter: chapter.id, target: translationTarget,
                                  source: translationSource)
    }
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
    /// An incognito session (`IncognitoGate`) records no history, progress, statistics or tracker updates.
    var incognito = false
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
    private var initialBookmark: IosBookmark?
    private var initialIndex: Int?
    private var pageCache: (indent: Bool, spacing: Int, bookStyles: Bool, revision: Int, pages: IosNovelPages)?
    private var lineStyleCache: [Int: (runs: [NovelRun], layout: NovelLineLayout?)] = [:]

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter,
         initialBookmark: IosBookmark? = nil, initialIndex: Int? = nil) {
        self.runtime = runtime
        // Downloaded books are read by their chapters; a download listed that way opens at the first of them.
        let content = NovelDownloads.listingBooks(content)
        self.content = content
        let listed = content.listedChapter(chapter: chapter)
        self.chapter = listed
        self.displayTitle = listed.title
        self.initialBookmark = initialBookmark
        self.initialIndex = initialIndex
        super.init()
        speech.onLine = { [weak self] index in self?.follow(index) }
        speech.onRemoteSkip = { [weak self] delta in self?.skipSpeech(delta) }
        speech.onChapterEnd = { [weak self] in
            guard let self, let next = self.next else { return false }
            await self.move(to: next, continueSpeech: true)
            self.startSpeech(from: 0)
            return true
        }
    }

    /// Reading aloud; follows the reader into the next chapter.
    let speech = NovelSpeech()

    /// Highlights of the current chapter (Android's novel markings), and where they lie in its text.
    @Published private(set) var markings: [IosNovelMarking] = []
    @Published private(set) var markingRanges: [IosTextRange] = []

    func loadMarkings() async {
        guard let library, let document = sourceDocument else { markings = []; markingRanges = []; return }
        markings = (try? await library.markings.forChapter(content: content, chapter: chapter)) ?? []
        markingRanges = document.markingRanges(markings: markings)
    }

    /// Highlights `start until end` of the chapter text, or removes the highlights the range touches.
    func toggleMarking(start: Int, end: Int) async {
        guard let library, end > start else { return }
        let touched = markingRanges.filter { $0.start < Int32(end) && Int32(start) < $0.end }
        if touched.isEmpty {
            _ = await addMarking(start: start, end: end)
            return
        }
        for range in touched { try? await library.markings.delete(id: range.id) }
        await refreshMarkings()
    }

    /// Highlights `start until end` with the current color and shape, opening the note editor when [withNote].
    @discardableResult
    func addMarking(start: Int, end: Int, withNote: Bool = false) async -> IosNovelMarking? {
        guard let library, let document = sourceDocument, end > start else { return nil }
        let text = (document.text(start: Int32(start), end: Int32(end)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        let exists = markings.contains { $0.start == Int32(start) && $0.end == Int32(end) }
        if !exists {
            _ = try? await library.markings.toggle(content: content, chapter: chapter, start: Int32(start), end: Int32(end),
                                                   text: text, color: Int32(NovelMarkingDefaults.color.rawValue),
                                                   style: Int32(NovelMarkingDefaults.line.rawValue))
        }
        await refreshMarkings()
        let added = markings.first { $0.start == Int32(start) && $0.end == Int32(end) }
        if withNote { editingMarking = added }
        return added
    }

    /// The saved highlight with [id] in this chapter.
    func marking(_ id: Int64) -> IosNovelMarking? { markings.first { $0.id == id } }

    /// A highlight being given a note (the reader shows its editor).
    @Published var editingMarking: IosNovelMarking?

    /// Changes a highlight's color and shape; new highlights take them too (Android keeps the last ones chosen).
    func restyleMarking(_ id: Int64, color: NovelMarkingColor, line: NovelMarkingLine) async {
        guard let library else { return }
        NovelMarkingDefaults.color = color
        NovelMarkingDefaults.line = line
        try? await library.markings.updateStyle(id: id, color: Int32(color.rawValue), style: Int32(line.rawValue))
        await refreshMarkings()
    }

    func saveMarkingNote(_ id: Int64, note: String) async {
        guard let library else { return }
        try? await library.markings.updateNote(id: id, note: note.trimmingCharacters(in: .whitespacesAndNewlines))
        await refreshMarkings()
    }

    func deleteMarking(_ id: Int64) async {
        guard let library else { return }
        try? await library.markings.delete(id: id)
        await refreshMarkings()
    }

    /// Reads the highlights again and redraws them where the reader is: highlights never change the layout, so the
    /// paged reader keeps its page and the scrolling reader its offset.
    private func refreshMarkings() async {
        await loadMarkings()
        revision += 1
    }

    /// [ranges] with each highlight's color and shape.
    func decorated(_ ranges: [IosTextRange]) -> [NovelHighlight] {
        guard !ranges.isEmpty else { return [] }
        return ranges.compactMap { range in
            guard let marking = marking(range.id) else { return nil }
            return NovelHighlight(id: range.id, start: Int(range.start), end: Int(range.end),
                                  color: .of(marking.color), line: .of(marking.style))
        }
    }

    /// Highlights within line [index] (the scrolling reader).
    func lineHighlights(_ index: Int) -> [IosTextRange] {
        guard !markingRanges.isEmpty, let document else { return [] }
        return document.lineHighlights(index: Int32(index), ranges: markingRanges)
    }

    /// The chapter offset of [offset] in line [index], or nil when the line cannot hold highlights (a translation or
    /// a line rewritten by a replacement rule).
    func chapterOffset(line index: Int, offset: Int) -> Int? {
        guard translation(index) == nil, let range = document?.lineRange(index: Int32(index)) else { return nil }
        return Int(range.start) + min(max(0, offset), Int(range.end - range.start))
    }

    /// Highlights or clears the whole of line [index] (the scrolling reader's long press).
    func toggleLineMarking(_ index: Int) async {
        guard let range = document?.lineRange(index: Int32(index)) else { return }
        await toggleMarking(start: Int(range.start), end: Int(range.end))
    }

    /// The lines as shown, for searching the chapter: a translated line is found by either text.
    var searchableLines: [(Int, String)] {
        blocks.indices.compactMap { index in
            guard case .text(let text) = blocks[index] else { return nil }
            return (index, translation(index).map { text + "\n" + $0 } ?? text)
        }
    }

    /// The lines as shown (translations replace their lines), for reading aloud.
    private var spokenTexts: [(Int, String)] {
        blocks.indices.compactMap { index in
            guard case .text(let text) = blocks[index] else { return nil }
            return (index, translation(index) ?? text)
        }
    }

    /// Starts reading aloud from line [index], in the chapter's language unless a voice is chosen.
    func startSpeech(from index: Int) {
        let texts = spokenTexts
        let language: Locale.Language? = translations.isEmpty || !translationEnabled
            ? translationSource ?? NovelTranslationSettings.source(of: texts.prefix(40).map(\.1)) : translationTarget
        speech.nowPlayingTitle = "\(content.title) · \(displayTitle)"
        speech.start(texts, from: index, language: language.flatMap(Self.speechLanguage))
    }

    /// Skips [delta] lines from the one being spoken.
    func skipSpeech(_ delta: Int) {
        let texts = spokenTexts.map(\.0)
        guard let current = speech.current ?? Optional(visibleIndex),
              let position = texts.firstIndex(where: { $0 >= current }) else { return }
        let target = texts[min(max(0, position + delta), texts.count - 1)]
        startSpeech(from: target)
    }

    /// An `AVSpeechSynthesisVoice` language tag ("zh-CN") for a recognized language.
    private static func speechLanguage(_ language: Locale.Language) -> String? {
        let maximal = Locale.Language(identifier: language.maximalIdentifier)
        guard let code = maximal.languageCode?.identifier else { return nil }
        return maximal.region.map { "\(code)-\($0.identifier)" } ?? code
    }

    /// Keeps the spoken line in view without a save per line beyond the usual debounce.
    private func follow(_ index: Int) {
        guard blocks.indices.contains(index), index != visibleIndex else { return }
        seek(to: index)
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
            let text = if let offline { offline.0 } else { try await onlineText(selected) }
            try Task.checkCancellation()
            guard self.request == request else { return }
            // Parsing large chapters off the main actor keeps scrolling responsive.
            self.text = text
            localImages = offline?.1 ?? [:]
            isOffline = offline != nil
            var document: IosNovelDocument? = if let text {
                await Task.detached(priority: .userInitiated) { text.document() }.value
            } else { nil }
            // Android's text replacement rules, on the lines and the chapter title.
            if let converted = document, let library {
                document = (try? await library.replaceRules.apply(document: converted, content: content)) ?? converted
                displayTitle = (try? await library.replaceRules.title(title: selected.title, content: content)) ?? selected.title
            }
            guard self.request == request else { return }
            self.sourceDocument = document
            self.document = document
            translations = [:]
            translationStatus = .idle
            if translationEnabled { restoreTranslations() }
            lineStyleCache.removeAll(keepingCapacity: true)
            blocks = document.map(NovelBlock.blocks(from:)) ?? []
            var scroll: Float = 0
            if let bookmark = initialBookmark, bookmark.chapterId == selected.id {
                resumeIndex = min(max(0, Int(document?.bookmarkIndex(bookmark: bookmark, content: content)
                    ?? bookmark.index)), max(0, blocks.count - 1))
                self.initialBookmark = nil
            } else if let initialIndex {
                resumeIndex = min(max(0, initialIndex), max(0, blocks.count - 1))
                self.initialIndex = nil
            } else if let library, let position = try await library.position(content: content), position.chapterId == selected.id {
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
            await loadMarkings()
        } catch {
            if !Task.isCancelled, self.request == request {
                self.error = SourceErrorText.describe(error)
                challenge = SourceErrorText.challenge(error)
            }
        }
    }

    /// A downloaded book's chapter whose download is gone reads its part of the book from the source.
    private func onlineText(_ chapter: IosSourceChapter) async throws -> IosChapterText? {
        guard let book = content.bookOf(chapter: chapter) else { return try await runtime.chapterText(chapter: chapter) }
        return try await runtime.chapterText(chapter: book)?.section(index: content.bookIndex(chapter: chapter))
    }

    /// Opens another chapter at its start, or at its end when going back a page from the first one.
    func move(to target: IosSourceChapter, atEnd: Bool = false, continueSpeech: Bool = false) async {
        if !continueSpeech { speech.stop() }
        await persist()
        chapter = target
        lineStyleCache.removeAll()
        blocks = []
        document = nil
        sourceDocument = nil
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

    /// Turns translation on (cached lines show at once, the rest are translated) or back to the original text.
    func toggleTranslation() {
        translationEnabled.toggle()
        if translationEnabled {
            restoreTranslations()
        } else {
            translationConfiguration = nil
            translationStatus = .idle
            translations = [:]
            showTranslations()
        }
    }

    /// The reader's translation settings changed: re-show (display mode) or re-translate (target or original
    /// language).
    func updateTranslationSettings(bilingual: Bool, target: String, source: String) {
        let language = NovelTranslationSettings.target(target)
        let original = NovelTranslationSettings.chosenSource(source)
        let retarget = language.minimalIdentifier != translationTarget.minimalIdentifier
            || original?.minimalIdentifier != translationSource?.minimalIdentifier
        translationBilingual = bilingual
        translationTarget = language
        translationSource = original
        guard translationEnabled else { return }
        if retarget {
            translationConfiguration = nil
            restoreTranslations()
        } else {
            showTranslations()
        }
    }

    /// Shows the current chapter's cached translations and asks the view's task to translate the rest.
    private func restoreTranslations() {
        guard sourceDocument != nil else { return }
        translations = NovelTranslationCache.load(translationKey)
        showTranslations()
        let lines = sourceDocument?.translatableTexts.sorted { $0.key.intValue < $1.key.intValue }.map(\.value) ?? []
        let source = translationSource ?? NovelTranslationSettings.source(of: lines)
        // UI tests: the system translator is unavailable in the simulator, so lines are "translated" by tagging them.
        if ProcessInfo.processInfo.environment["KOTOTORO_TRANSLATION_FIXTURE"] == "1" {
            let tag = "[" + (translationSource.map { "\($0.minimalIdentifier)→" } ?? "")
                + "\(translationTarget.minimalIdentifier)] "
            applyTranslations(Dictionary(uniqueKeysWithValues: (sourceDocument?.translatableTexts ?? [:])
                .map { ($0.key.intValue, tag + $0.value) }))
            return
        }
        if let source, NovelTranslationSettings.matches(source, translationTarget) {
            translationConfiguration = nil
            translationStatus = .sameLanguage
            return
        }
        if translationConfiguration?.source == source,
           translationConfiguration?.target?.minimalIdentifier == translationTarget.minimalIdentifier {
            translationConfiguration?.invalidate()
        } else {
            translationConfiguration = TranslationSession.Configuration(source: source, target: translationTarget)
        }
    }

    func applyTranslations(_ update: [Int: String]) {
        guard !update.isEmpty else { return }
        translations.merge(update) { _, new in new }
        showTranslations()
    }

    /// Re-lays the chapter out with [translations], keeping the line being read in place.
    private func showTranslations() {
        guard let sourceDocument else { return }
        let values = Dictionary(uniqueKeysWithValues: translations.map { (KotlinInt(int: Int32($0.key)), $0.value) })
        document = values.isEmpty ? sourceDocument : sourceDocument.translated(translations: values, bilingual: translationBilingual)
        lineStyleCache.removeAll(keepingCapacity: true)
        target = NovelReadingTarget(block: visibleIndex, scroll: pagedScroll ?? document?.scroll(index: Int32(visibleIndex)) ?? 0,
                                    atEnd: false)
        revision += 1
    }

    /// The translation of line [index] when shown, and whether the original stays above it.
    func translation(_ index: Int) -> String? { translationEnabled ? translations[index] : nil }

    func persist() async {
        guard !incognito else { return }
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
        case .image: preview = ""
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
    case bookmark, speak, translate, chapters, typography, downloads
    var id: String { rawValue }
    var title: String {
        switch self {
        case .bookmark: String(localized: "书签")
        case .translate: String(localized: "翻译")
        case .speak: String(localized: "朗读")
        case .chapters: String(localized: "目录与书签")
        case .typography: String(localized: "阅读设置")
        case .downloads: String(localized: "下载队列")
        }
    }
    var symbol: String {
        switch self {
        case .translate: "translate"
        case .speak: "speaker.wave.2"
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
        case chapters, settings, downloads, search
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
    @AppStorage(NovelTranslationSettings.bilingualKey) private var translationBilingual = false
    @AppStorage(NovelTranslationSettings.targetKey) private var translationTarget = ""
    @AppStorage(NovelTranslationSettings.sourceKey) private var translationSource = ""
    @AppStorage(ReplaceRulesRevision.key) private var replaceRulesRevision = 0
    @State private var activeSheet: ReaderSheet?
    @State private var sheetTab = 0
    /// Downloaded books folded away in the chapter list; books open expanded, as on Android.
    @State private var collapsedBooks = Set<String>()
    @State private var downloadError: String?
    @State private var chromeHidden = false
    @State private var showingMore = false
    @State private var moreTab = 0
    @State private var scrubPosition: Double?
    @State private var isScrubbing = false
    @State private var scrollParagraph: Int?

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter,
         initialBookmark: IosBookmark? = nil, initialIndex: Int? = nil, incognito: Bool = false) {
        let model = NovelReaderModel(runtime: runtime, content: content, chapter: chapter,
                                     initialBookmark: initialBookmark, initialIndex: initialIndex)
        model.incognito = incognito
        _model = StateObject(wrappedValue: model)
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
                               tap.location.y < geometry.size.height * 2 / 3 {
                                // A tap on a highlight opens its menu instead; the text view sees it in the same pass.
                                DispatchQueue.main.async { if !NovelTextView.tappedHighlightRecently { toggleChrome() } }
                            }
                        })
                }
                if !chromeHidden { chrome(height: geometry.size.height).transition(.opacity) }
                else {
                    // Reading aloud keeps its controls while the reading controls are hidden.
                    NovelSpeechBarIfActive(speech: model.speech) { model.skipSpeech($0) }
                        .frame(maxHeight: .infinity, alignment: .bottom).padding(.bottom, 24)
                }
            }
        }
        .background(style.colors.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar, .tabBar)
        .statusBarHidden(chromeHidden)
        .persistentSystemOverlays(chromeHidden ? .hidden : .automatic)
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .chapters: navigationSheet
            case .settings: NovelReaderSettingsSheet()
            case .downloads: ReaderDownloadsSheet()
            case .search:
                NovelChapterSearchSheet(lines: model.searchableLines) { index in
                    activeSheet = nil
                    Task { await model.jump(to: index) }
                }
            }
        }
        .sheet(item: $model.editingMarking) { marking in
            NovelMarkingNoteSheet(marking: marking) { note in
                Task { await model.saveMarkingNote(marking.id, note: note) }
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
            model.speech.stop()
            model.setReadingActive(false)
            Task {
                await model.persist()
                await model.finishReadingActivity()
                await library.refresh()
                if !model.incognito {
                    await library.trackingAccounts.syncAfterReading(model.content, tracking: library.library?.tracking)
                }
            }
        }
        .onChange(of: model.chapter.id) { _, _ in showingMore = false; scrubPosition = nil; isScrubbing = false }
        .translationTask(model.translationConfiguration) { session in await model.translate(using: session) }
        .onChange(of: replaceRulesRevision) { _, _ in
            // Edited rules apply to the open chapter at the line being read.
            Task { await model.persist(); await model.load() }
        }
        .onChange(of: "\(translationBilingual)|\(translationTarget)|\(translationSource)") { _, _ in
            model.updateTranslationSettings(bilingual: translationBilingual, target: translationTarget,
                                            source: translationSource)
        }
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
        case .translate: model.toggleTranslation()
        case .speak:
            showingMore = false
            if model.speech.isActive { model.speech.togglePause() } else { model.startSpeech(from: model.visibleIndex) }
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
                            Label(model.displayTitle, systemImage: "list.bullet").font(.subheadline).lineLimit(1)
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
                if !showingMore, model.translationEnabled, let status = model.translationStatus.text {
                    Label(status, systemImage: "translate")
                        .font(.caption).lineLimit(2)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .glassEffect(.regular, in: .capsule)
                        .accessibilityIdentifier("novel.translationStatus")
                }
                if !showingMore && !actions.isEmpty {
                    HStack(spacing: 12) {
                        Spacer()
                        ForEach(actions) { action in
                            Button { perform(action) } label: {
                                Image(systemName: action == .bookmark && model.isBookmarked ? "bookmark.fill" : action.symbol)
                                    .frame(width: 44, height: 44)
                                    .foregroundStyle(action == .translate && model.translationEnabled ? Color.white : Color.primary)
                            }.buttonStyle(.plain)
                                .glassEffect(.regular.tint(action == .translate && model.translationEnabled
                                                           ? Color.accentColor : Color.accentColor.opacity(0.35)).interactive(),
                                             in: .circle)
                                .disabled(action == .bookmark && (model.blocks.isEmpty || model.isLoading))
                                .accessibilityLabel(action.title)
                                .accessibilityValue(action == .bookmark ? (model.isBookmarked ? "已添加" : "未添加")
                                                    : action == .translate ? (model.translationEnabled ? "已开启" : "已关闭") : "")
                                .accessibilityIdentifier("novel.floating.\(action.rawValue)")
                        }
                    }
                }
                if !showingMore {
                    NovelSpeechBarIfActive(speech: model.speech) { model.skipSpeech($0) }
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
                        // A selection is highlighted from its menu; this marks the whole paragraph being read.
                        let marked = !model.lineHighlights(model.visibleIndex).isEmpty
                        Button(marked ? "取消本段划线" : "划线当前段", systemImage: "highlighter") {
                            Task { await model.toggleLineMarking(model.visibleIndex) }
                        }
                        .disabled(model.blocks.isEmpty || model.isLoading)
                        .accessibilityIdentifier("novel.markParagraph")
                        Button("搜索本章", systemImage: "magnifyingglass") { showingMore = false; activeSheet = .search }
                            .disabled(model.blocks.isEmpty || model.isLoading)
                            .accessibilityIdentifier("novel.search")
                        Button("朗读", systemImage: "speaker.wave.2") { perform(.speak) }
                            .disabled(model.blocks.isEmpty || model.isLoading)
                            .accessibilityIdentifier("novel.speak")
                        Button(model.translationEnabled ? "显示原文" : "翻译本章", systemImage: "translate") {
                            perform(.translate)
                        }
                        .disabled(model.blocks.isEmpty || model.isLoading)
                        .accessibilityIdentifier("novel.translate")
                        if model.translationEnabled, let status = model.translationStatus.text {
                            Text(status).font(.caption).foregroundStyle(.secondary)
                        }
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

    /// One line of the scrolling reader, with the book's alignment, heading size, emphasis and readings when enabled.
    /// [plain] lines (translations and the original above them) leave out the book's styles, which belong to the
    /// original, and hold no highlights. Text is selectable: a selection can be highlighted or noted, and tapping a
    /// highlight selects it and offers its color, shape, note and removal, as in the paged reader.
    private func scrollLine(_ text: String, index: Int, style: NovelStyle, plain: Bool = false,
                            secondary: Bool = false) -> some View {
        let book: (runs: [NovelRun], layout: NovelLineLayout?) = style.bookStyles && !plain ? model.lineStyle(index) : ([], nil)
        let indented = style.indent && !IosNovelStyle.shared.isTitleLine(line: text) && (book.layout?.indent ?? true)
        let prefix = indented ? "\u{3000}\u{3000}" : ""
        let shift = (prefix as NSString).length
        let attributed = style.line(text, prefix: prefix, runs: book.runs, layout: book.layout,
                                    highlights: plain ? [] : model.decorated(model.lineHighlights(index)),
                                    secondary: secondary)
        return NovelPageText(text: attributed, fitsWidth: true, markings: plain ? nil : model) { range, withNote in
            guard let start = model.chapterOffset(line: index, offset: range.location - shift),
                  let end = model.chapterOffset(line: index, offset: NSMaxRange(range) - shift) else { return }
            Task { await model.addMarking(start: start, end: end, withNote: withNote) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
        .accessibilityAddTraits(.isStaticText)
    }

    private func scrollReader(_ style: NovelStyle) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: style.paragraphGap) {
                Text(model.displayTitle)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(style.colors.text)
                    .padding(.bottom, model.isOffline ? 0 : 8)
                    .id(-1)
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
                        if let translated = model.translation(index) {
                            VStack(alignment: .leading, spacing: style.paragraphGap) {
                                if translationBilingual {
                                    scrollLine(text, index: index, style: style, plain: true, secondary: true)
                                }
                                scrollLine(translated, index: index, style: style, plain: true)
                            }
                            .accessibilityIdentifier(index == 0 ? "novel.firstParagraph" : "novel.paragraph")
                        } else {
                            scrollLine(text, index: index, style: style)
                                .accessibilityIdentifier(index == 0 ? "novel.firstParagraph" : "novel.paragraph")
                        }
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
            .foregroundStyle(style.colors.text)
            .padding(.horizontal, style.marginHorizontal)
            .padding(.vertical, style.marginVertical)
            .scrollTargetLayout()
        }
        .scrollPosition(id: $scrollParagraph, anchor: .top)
        .onChange(of: model.target, initial: true) { _, target in
            scrollParagraph = target.block == 0 && !target.atEnd ? -1 : target.block
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
        if ReaderDownloads.isDownloaded(content: model.content, chapter: model.chapter) { return String(localized: "本章已下载，可离线阅读") }
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
        Task {
            do { try await downloads.enqueue(content: model.content, chapters: chapters) }
            catch { downloadError = error.localizedDescription }
        }
    }

    private func chapterRow(_ item: IosSourceChapter) -> some View {
        Button {
            activeSheet = nil
            Task { await model.move(to: item) }
        } label: {
            HStack {
                Text(item.title)
                Spacer()
                // A book's chapters share their download, whose state the group shows.
                if model.content.bookOf(chapter: item) == nil, let offline = ChapterOfflineMark.of(model.content, item) {
                    Image(systemName: offline.icon).foregroundStyle(.secondary).accessibilityLabel(offline.label)
                }
                if item.id == model.chapter.id { Image(systemName: "book.fill") }
            }
        }
        .id(item.id)
        .accessibilityIdentifier("novel.chapter.\(item.id)")
        .swipeActions {
            if !model.runtime.isLocal(content: model.content),
               !ReaderDownloads.isDownloaded(content: model.content, chapter: item) {
                Button("下载", systemImage: "arrow.down.circle") { enqueue([item]) }.tint(.accentColor)
            }
        }
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
                    // A downloaded book gathers its chapters under the download, as Android groups EPUB volumes.
                    ScrollViewReader { proxy in
                        List {
                            ForEach(ChapterRow.rows(model.content.chapters, in: model.content)) { row in
                                switch row {
                                case .chapter(let item): chapterRow(item)
                                case .book(let book, let chapters):
                                    DisclosureGroup(isExpanded: Binding(
                                        get: { !collapsedBooks.contains(book.id) },
                                        set: { if $0 { collapsedBooks.remove(book.id) } else { collapsedBooks.insert(book.id) } }
                                    )) {
                                        ForEach(chapters, id: \.id) { chapterRow($0) }
                                    } label: {
                                        ChapterBookLabel(book: book, count: chapters.count)
                                    }
                                    .accessibilityIdentifier("novel.book.\(book.id)")
                                }
                            }
                        }
                        .task {
                            if let book = model.content.bookOf(chapter: model.chapter) { collapsedBooks.remove(book.id) }
                            await Task.yield()
                            proxy.scrollTo(model.chapter.id, anchor: .center)
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
                                BookmarkPreviewLabel(runtime: model.runtime, content: model.content, bookmark: bookmark,
                                                     chapter: model.content.chapters.first { $0.id == bookmark.chapterId })
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
    let uiSecondaryText: UIColor
    let uiBackground: UIColor

    init(theme: String, dark: Bool) {
        let colors = IosNovelStyle.shared.colors(theme: theme, dark: dark)
        background = Color(uiColor: UIColor(argb: colors.background))
        text = Color(uiColor: UIColor(argb: colors.text))
        secondaryText = Color(uiColor: UIColor(argb: colors.secondaryText))
        uiText = UIColor(argb: colors.text)
        uiSecondaryText = UIColor(argb: colors.secondaryText)
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

    /// Space between paragraphs in the scrolling reader: the blank lines, as the paged reader leaves them. Each line
    /// is already [lineHeight] tall there, its gap included.
    var paragraphGap: CGFloat { CGFloat(paragraphSpacing) * lineHeight }

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
    func attributed(_ text: String, runs: [NovelRun], lines: [NovelLineLayout],
                    highlights: [NovelHighlight] = []) -> NSAttributedString {
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
            if run.secondary {
                result.addAttribute(.foregroundColor, value: colors.uiSecondaryText, range: range)
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
        result.addNovelHighlights(highlights)
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

    /// A line of the scrolling reader: [text] after [prefix] (the indent), with the book's line layout and styles and
    /// the highlights in the line's own offsets. [secondary] draws it in the theme's secondary color.
    func line(_ text: String, prefix: String, runs: [NovelRun], layout: NovelLineLayout?,
              highlights: [NovelHighlight], secondary: Bool = false) -> NSAttributedString {
        let shift = (prefix as NSString).length
        let whole = prefix + text
        let lines = layout.map { [NovelLineLayout(range: NSRange(location: 0, length: (whole as NSString).length), from: $0)] } ?? []
        let result = NSMutableAttributedString(attributedString: attributed(
            whole, runs: runs.map { $0.shifted(by: shift) }, lines: lines, highlights: highlights.map { $0.shifted(by: shift) }))
        if secondary {
            result.addAttribute(.foregroundColor, value: colors.uiSecondaryText, range: NSRange(location: 0, length: result.length))
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
    /// The original line above a bilingual translation, in the theme's secondary color.
    let secondary: Bool

    init(_ run: IosNovelStyleRun) {
        range = NSRange(location: Int(run.start), length: Int(run.end - run.start))
        bold = run.bold; italic = run.italic; underline = run.underline; strike = run.strike
        vertical = Int(run.vertical); scale = CGFloat(run.scale); ruby = run.ruby; secondary = run.secondary
    }

    private init(_ run: NovelRun, range: NSRange) {
        self.range = range
        bold = run.bold; italic = run.italic; underline = run.underline; strike = run.strike
        vertical = run.vertical; scale = run.scale; ruby = run.ruby; secondary = run.secondary
    }

    func shifted(by offset: Int) -> NovelRun {
        NovelRun(self, range: NSRange(location: range.location + offset, length: range.length))
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

    /// [layout] over [range] instead of its own.
    init(range: NSRange, from layout: NovelLineLayout) {
        self.range = range
        align = layout.align; scale = layout.scale; ruby = layout.ruby; heading = layout.heading; indent = layout.indent
    }
}

// MARK: - Paged reader

/// One laid-out page: a range of a text segment, or a picture.
struct NovelLaidOutPage: Equatable {
    let segment: Int
    let start: Int
    let end: Int
    /// Where [text] begins in the segment ([start] includes the blank lines a page does not show).
    var textStart = 0
    let text: NSAttributedString?
    let imageUrl: String?
}

/// One segment of the chapter as handed to the paginator: styled text, or a picture.
struct NovelLayoutSegment {
    let text: String?
    let imageUrl: String?
    let runs: [NovelRun]
    let lines: [NovelLineLayout]
    var highlights: [NovelHighlight] = []
}

/// Pages laid out with TextKit 2 and split into whole lines by the rule Android's paged reader uses.
enum NovelPaginator {
    static func layout(segments: [NovelLayoutSegment], style: NovelStyle, size: CGSize) -> [NovelLaidOutPage] {
        guard size.width > 1, size.height > 1 else { return [] }
        var pages: [NovelLaidOutPage] = []
        for (index, segment) in segments.enumerated() {
            if let text = segment.text {
                let attributed = style.attributed(text, runs: segment.runs, lines: segment.lines, highlights: segment.highlights)
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
                    pages.append(NovelLaidOutPage(segment: index, start: start, end: end, textStart: first, text: body, imageUrl: nil))
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
    private let footerHeight: CGFloat = 22
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
                                 height: geometry.size.height - insets.top - 2 * style.marginVertical - footerHeight)
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
                    NovelPageText(text: text, markings: model) { range, withNote in mark(page, range: range, withNote: withNote) }
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
        .padding(.top, style.marginVertical)
        .padding(.bottom, style.marginVertical + footerHeight)
        .overlay(alignment: .bottom) {
            // The lower margin is the gap above the footer, just as the upper margin starts below the cutout.
            HStack {
                Text(model.displayTitle).lineLimit(1)
                Spacer()
                Text("\(index + 1) / \(pages.count)").monospacedDigit()
            }
            .font(.system(size: 11))
            .foregroundStyle(style.colors.secondaryText)
            .frame(height: footerHeight, alignment: .top)
            .padding(.horizontal, style.marginHorizontal)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("novel.footer")
        }
    }

    /// Highlights a selection on [page], opening the note editor when [withNote]; [range] is in the page's text.
    private func mark(_ page: NovelLaidOutPage, range: NSRange, withNote: Bool) {
        guard let source = model.pages(indent: style.indent, spacing: style.paragraphSpacing, bookStyles: style.bookStyles)
        else { return }
        let segment = Int32(page.segment)
        let start = source.sourceOffset(segment: segment, offset: Int32(page.textStart + range.location))
        let end = source.sourceOffset(segment: segment, offset: Int32(page.textStart + range.location + range.length))
        Task { await model.addMarking(start: Int(start), end: Int(end), withNote: withNote) }
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
                                      lines: source.paragraphs(segment: segment).map(NovelLineLayout.init),
                                      highlights: model.decorated(source.highlights(segment: segment, ranges: model.markingRanges)))
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
            // The page text opens the menu of a tapped highlight itself.
            if NovelTextView.highlight(at: gesture.location(in: view), in: view) { return }
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
/// It is selectable; links are not followed. A selection's menu offers highlighting it (or highlighting it with a
/// note); tapping a saved highlight selects it and offers its color, shape, note and removal, as Apple Books does.
/// [fitsWidth] sizes the view to its text at the proposed width (the scrolling reader's lines).
struct NovelPageText: UIViewRepresentable {
    let text: NSAttributedString
    var fitsWidth = false
    /// The reader holding the highlights; nil for text that takes none.
    var markings: NovelReaderModel? = nil
    /// Highlights the selected range, with a note when the flag is set.
    var onMark: ((NSRange, Bool) -> Void)? = nil

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UITextViewDelegate, NSTextLayoutManagerDelegate, UIEditMenuInteractionDelegate,
        UIGestureRecognizerDelegate {
        var parent: NovelPageText
        weak var textView: NovelTextView?
        /// The highlight whose menu the tap opened.
        private var menuMarking: (id: Int64, range: NSRange)?
        init(_ parent: NovelPageText) { self.parent = parent }

        func textView(_ textView: UITextView, editMenuForTextIn range: NSRange,
                      suggestedActions: [UIMenuElement]) -> UIMenu? {
            guard range.length > 0 else { return nil }
            if let id = Self.marking(exactly: range, in: textView.attributedText) {
                return markingMenu(id, suggested: suggestedActions)
            }
            guard let onMark = parent.onMark else { return nil }
            let clear = { [weak textView] in textView?.selectedRange = NSRange(location: range.location, length: 0) }
            let mark = UIAction(title: String(localized: "划线"), image: UIImage(systemName: "highlighter")) { _ in
                onMark(range, false)
                clear()
            }
            let note = UIAction(title: String(localized: "批注"), image: UIImage(systemName: "square.and.pencil")) { _ in
                onMark(range, true)
                clear()
            }
            return UIMenu(children: [mark, note] + suggestedActions)
        }

        /// The highlight covering exactly [range], if any.
        private static func marking(exactly range: NSRange, in text: NSAttributedString) -> Int64? {
            guard range.location < text.length else { return nil }
            var effective = NSRange()
            let value = text.attribute(.novelMarking, at: range.location, longestEffectiveRange: &effective,
                                       in: NSRange(location: 0, length: text.length)) as? NSNumber
            return effective == range ? value?.int64Value : nil
        }

        /// Color and shape submenus, the note and removal, then the system's own actions. The edit menu shows every
        /// entry as text, so the five colors live in a submenu instead of filling its first page.
        private func markingMenu(_ id: Int64, suggested: [UIMenuElement]) -> UIMenu? {
            guard let model = parent.markings, let marking = model.marking(id) else { return nil }
            let color = NovelMarkingColor.of(marking.color)
            let line = NovelMarkingLine.of(marking.style)
            let colors = UIMenu(title: String(localized: "颜色"), image: color.swatch(selected: false),
                                children: NovelMarkingColor.allCases.map { option in
                UIAction(title: option.title, image: option.swatch(selected: false),
                         state: option == color ? .on : .off) { [weak self] _ in
                    self?.dismissSelection()
                    Task { await model.restyleMarking(id, color: option, line: line) }
                }
            })
            let shapes = UIMenu(title: String(localized: "样式"), image: UIImage(systemName: line.systemImage),
                                children: NovelMarkingLine.allCases.map { option in
                UIAction(title: option.title, image: UIImage(systemName: option.systemImage),
                         state: option == line ? .on : .off) { [weak self] _ in
                    self?.dismissSelection()
                    Task { await model.restyleMarking(id, color: color, line: option) }
                }
            })
            let hasNote = !(marking.note ?? "").isEmpty
            let note = UIAction(title: hasNote ? String(localized: "编辑批注") : String(localized: "添加批注"),
                                image: UIImage(systemName: hasNote ? "text.bubble" : "square.and.pencil")) { [weak self] _ in
                self?.dismissSelection()
                model.editingMarking = marking
            }
            let delete = UIAction(title: String(localized: "删除划线"), image: UIImage(systemName: "trash"),
                                  attributes: .destructive) { [weak self] _ in
                self?.dismissSelection()
                Task { await model.deleteMarking(id) }
            }
            return UIMenu(children: [colors, shapes, note, delete] + suggested)
        }

        private func dismissSelection() {
            guard let textView else { return }
            textView.selectedRange = NSRange(location: textView.selectedRange.location, length: 0)
        }

        // MARK: Tapping a highlight

        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard let textView, gesture.state == .ended else { return }
            let point = gesture.location(in: textView)
            guard parent.markings != nil, let (id, range) = textView.highlight(at: point) else { return }
            NovelTextView.lastHighlightTap = Date()
            menuMarking = (id, range)
            if !textView.isFirstResponder { textView.becomeFirstResponder() }
            textView.selectedRange = range
            textView.menuInteraction.presentEditMenu(with: UIEditMenuConfiguration(identifier: nil, sourcePoint: point))
        }

        /// The highlight tap only begins on a highlight, so other taps stay the pager's and the reader's.
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard parent.markings != nil, let textView else { return false }
            return textView.highlight(at: gestureRecognizer.location(in: textView)) != nil
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

        func editMenuInteraction(_ interaction: UIEditMenuInteraction, menuFor configuration: UIEditMenuConfiguration,
                                 suggestedActions: [UIMenuElement]) -> UIMenu? {
            guard let textView, let (id, range) = menuMarking else { return nil }
            let copy = UIAction(title: String(localized: "拷贝"), image: UIImage(systemName: "doc.on.doc")) { [weak self] _ in
                UIPasteboard.general.string = (textView.attributedText.string as NSString).substring(with: range)
                self?.dismissSelection()
            }
            let share = UIAction(title: String(localized: "共享…"), image: UIImage(systemName: "square.and.arrow.up")) { _ in
                textView.share(range: range)
            }
            return markingMenu(id, suggested: [UIMenu(options: .displayInline, children: [copy, share])])
        }

        func editMenuInteraction(_ interaction: UIEditMenuInteraction,
                                 targetRectFor configuration: UIEditMenuConfiguration) -> CGRect {
            guard let textView, let (_, range) = menuMarking else { return .null }
            return textView.rect(of: range)
        }

        func editMenuInteraction(_ interaction: UIEditMenuInteraction, willDismissMenuFor configuration: UIEditMenuConfiguration,
                                 animator: any UIEditMenuInteractionAnimating) {
            menuMarking = nil
        }

        // MARK: Wavy lines

        func textLayoutManager(_ textLayoutManager: NSTextLayoutManager, textLayoutFragmentFor location: any NSTextLocation,
                               in textElement: NSTextElement) -> NSTextLayoutFragment {
            NovelMarkingLayoutFragment(textElement: textElement, range: textElement.elementRange)
        }
    }

    func makeUIView(context: Context) -> NovelTextView {
        let view = NovelTextView(usingTextLayoutManager: true)
        view.delegate = context.coordinator
        view.textLayoutManager?.delegate = context.coordinator
        view.isEditable = false
        // Long press selects text for the system menu (copy, look up, translate, share); taps reach the pager.
        view.isSelectable = true
        view.isScrollEnabled = false
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.delegate = context.coordinator
        view.addGestureRecognizer(tap)
        view.menuInteraction = UIEditMenuInteraction(delegate: context.coordinator)
        view.addInteraction(view.menuInteraction)
        context.coordinator.textView = view
        return view
    }

    func updateUIView(_ view: NovelTextView, context: Context) {
        context.coordinator.parent = self
        if view.attributedText != text { view.attributedText = text }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: NovelTextView, context: Context) -> CGSize? {
        guard fitsWidth else { return CGSize(width: proposal.width ?? 0, height: proposal.height ?? 0) }
        let width = proposal.width ?? uiView.window?.windowScene?.screen.bounds.width ?? 320
        if uiView.attributedText != text { uiView.attributedText = text }
        let height = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        return CGSize(width: width, height: ceil(height))
    }
}

/// The reader's text view: finds the saved highlight under a point and presents its menu.
final class NovelTextView: UITextView {
    var menuInteraction: UIEditMenuInteraction!

    /// When a tap last opened a highlight's menu; the scrolling reader then leaves its controls alone.
    static var lastHighlightTap: Date?

    static var tappedHighlightRecently: Bool {
        lastHighlightTap.map { Date().timeIntervalSince($0) < 0.4 } ?? false
    }

    /// The highlight (id and range) drawn under [point], if any.
    func highlight(at point: CGPoint) -> (Int64, NSRange)? {
        let length = attributedText.length
        guard length > 0, let position = closestPosition(to: point) else { return nil }
        // The closest caret position lies between two characters, and snaps to the text even from far past it: the
        // tap must be on the glyphs of one of them.
        let caret = offset(from: beginningOfDocument, to: position)
        guard let index = [caret, caret - 1].first(where: { index in
            index >= 0 && index < length &&
                rect(of: NSRange(location: index, length: 1)).insetBy(dx: -3, dy: -3).contains(point)
        }) else { return nil }
        var effective = NSRange()
        guard let id = attributedText.attribute(.novelMarking, at: index, longestEffectiveRange: &effective,
                                                in: NSRange(location: 0, length: attributedText.length)) as? NSNumber
        else { return nil }
        return (id.int64Value, effective)
    }

    /// Whether a tap at [point] in [container] lands on a highlight of a reader text view inside it.
    static func highlight(at point: CGPoint, in container: UIView) -> Bool {
        var view = container.hitTest(point, with: nil)
        while let current = view, current !== container {
            if let text = current as? NovelTextView {
                return text.highlight(at: container.convert(point, to: text)) != nil
            }
            view = current.superview
        }
        return false
    }

    /// The bounding box of [range]'s glyphs.
    func rect(of range: NSRange) -> CGRect {
        guard let start = position(from: beginningOfDocument, offset: range.location),
              let end = position(from: start, offset: range.length),
              let textRange = textRange(from: start, to: end) else { return .null }
        return selectionRects(for: textRange).map(\.rect).filter { !$0.isEmpty }.reduce(CGRect.null) { $0.union($1) }
    }

    func share(range: NSRange) {
        let text = (attributedText.string as NSString).substring(with: range)
        let controller = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        controller.popoverPresentationController?.sourceView = self
        controller.popoverPresentationController?.sourceRect = rect(of: range)
        var presenter = window?.rootViewController
        while let next = presenter?.presentedViewController { presenter = next }
        presenter?.present(controller, animated: true)
    }
}

// MARK: - Settings

/// The color and shape of new highlights (Android's marking palette); a highlight's own menu changes it later.
struct NovelMarkingSettingsSection: View {
    @AppStorage(NovelMarkingDefaults.colorKey) private var color = NovelMarkingColor.yellow.rawValue
    @AppStorage(NovelMarkingDefaults.lineKey) private var line = NovelMarkingLine.underline.rawValue

    var body: some View {
        Section {
            HStack(spacing: 0) {
                ForEach(NovelMarkingColor.allCases) { option in
                    Button { color = option.rawValue } label: {
                        Circle().fill(Color(uiColor: option.line))
                            .frame(width: 28, height: 28)
                            .overlay {
                                if color == option.rawValue {
                                    Image(systemName: "checkmark").font(.caption.weight(.bold)).foregroundStyle(.white)
                                }
                            }
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(option.title)
                    .accessibilityAddTraits(color == option.rawValue ? .isSelected : [])
                    .accessibilityIdentifier("novel.settings.markingColor.\(option.rawValue)")
                }
            }
            Picker("样式", selection: $line) {
                ForEach(NovelMarkingLine.allCases) { option in
                    Label(option.title, systemImage: option.systemImage).tag(option.rawValue)
                }
            }
            .accessibilityIdentifier("novel.settings.markingLine")
            NovelPageText(text: sample, fitsWidth: true)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("示例文字")
                .accessibilityIdentifier("novel.settings.markingPreview")
        } header: {
            Text("划线")
        } footer: {
            Text("长按选中文字后选择“划线”。轻点已有划线可选中它，并修改颜色、样式、批注或删除。")
        }
    }

    /// A sample of the chosen highlight, drawn as the reader draws it.
    private var sample: NSAttributedString {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineHeightMultiple = 1.5
        let text = NSMutableAttributedString(string: String(localized: "示例文字"), attributes: [
            .font: UIFont.preferredFont(forTextStyle: .body), .foregroundColor: UIColor.label, .paragraphStyle: paragraph,
        ])
        text.addNovelHighlights([NovelHighlight(id: 0, start: 0, end: text.length,
                                                color: NovelMarkingColor(rawValue: color) ?? .yellow,
                                                line: NovelMarkingLine(rawValue: line) ?? .underline)])
        return text
    }
}

/// The Android reader's typography and theme settings, stored per device.
struct NovelReaderSettingsSheet: View {
    @EnvironmentObject private var library: LibraryModel
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
    private static let themeNames = ["PAPER": String(localized: "纸张"), "SEPIA": String(localized: "旧书"), "MOSS": String(localized: "苔绿"), "SLATE": String(localized: "石板")]

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
                    slider(String(localized: "字号"), value: $fontSize, range: Double(limits.minFontSize)...Double(limits.maxFontSize),
                           step: Double(limits.fontSizeStep), format: "%.1f")
                    slider(String(localized: "行距"), value: $lineSpacing, range: Double(limits.minLineSpacing)...Double(limits.maxLineSpacing),
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
                NovelMarkingSettingsSection()
                if let rules = library.library?.replaceRules {
                    Section {
                        NavigationLink { ReplaceRulesView(rules: rules) } label: {
                            Label("文本替换规则", systemImage: "text.badge.checkmark")
                        }
                        .accessibilityIdentifier("novel.settings.replaceRules")
                    }
                }
                NovelSpeechSettingsSection()
                NovelTranslationSettingsSection()
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
