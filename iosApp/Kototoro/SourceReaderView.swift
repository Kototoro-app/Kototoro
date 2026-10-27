import Combine
import KototoroShared
import SwiftUI
import UIKit.UIGestureRecognizerSubclass
import UniformTypeIdentifiers

struct ReaderWindowSlot: Identifiable {
    let id: String
    let slot: IosReaderSlot
}

/// One slot as plain values (from `IosReader.slotTable`): read per scroll frame without a call into Kotlin.
struct ReaderSlotRow {
    let index: Int
    let anchor: Int
    let placementCount: Int
    /// The first placement's scene frame, page index and segment.
    let frame: CGRect
    let page: Int
    let segment: Int
}

/// One window position's display state. Every page of the strip is its own hosting controller: observing this rather
/// than the whole reader model, a page renders again when its own image, artifact or failure changes, not on every
/// page turn, slot rebuild or another page's image.
@MainActor
final class ReaderPageState: ObservableObject {
    @Published fileprivate(set) var image: UIImage?
    @Published fileprivate(set) var artifact: ReaderImageArtifact?
    @Published fileprivate(set) var failure: SourceReaderModel.PageFailure?
    @Published fileprivate(set) var revision = 0
    @Published fileprivate(set) var description = ""
    /// Sent after a change is complete (unlike `objectWillChange`), so a UIKit page reads the new state in the same
    /// main-thread turn as the model's layout change.
    let didChange = PassthroughSubject<Void, Never>()
}

@MainActor
final class SourceReaderModel: ObservableObject {
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
    @Published private(set) var pages: [IosSourcePage] = []
    @Published private(set) var index = 0
    @Published private(set) var windowIndex = 0
    @Published private(set) var windowRevision = 0
    @Published private(set) var sceneRevision = 0
    private var window: [IosReaderPage] = [] {
        didSet {
            windowPositions = Dictionary(window.enumerated().map { (pageIdentity($0.element), $0.offset) },
                                         uniquingKeysWith: { first, _ in first })
            slotIdentities = [:]
            // States are kept, not dropped with positions past the window: a page view may still hold one.
            for (target, state) in pageStates {
                let description = pageDescription(at: target)
                if state.description != description { state.description = description; state.didChange.send() }
            }
        }
    }
    /// Window positions by page identity, for the resource window's lookups.
    private var windowPositions: [String: Int] = [:]
    /// Read on every reader body update: hash the window's few chapters, not each of its pages.
    var storageDirectories: Set<String> {
        Set(Set(window.map(\.chapterId) + [chapter.id]).map {
            ReaderStorageMetadata.directoryID(contentID: content.id, chapterID: $0)
        })
    }
    private var windowPages: [IosSourcePage] = []
    private var chapterPages: [String: [IosSourcePage]] = [:]
    private var chapterPreloads: [String: ChapterPreload] = [:]
    private var pendingWindow = false
    /// Decoded pages, artifacts and failures by window position. Not published: page views follow them through their
    /// own `ReaderPageState`, so a page arriving does not re-render the whole reader. Anything else reads them on use.
    private(set) var images: [Int: UIImage] = [:] { didSet { syncPageStates() } }
    private(set) var artifacts: [Int: ReaderImageArtifact] = [:] { didSet { syncPageStates() } }
    @Published private(set) var error: String?
    /// The last failure was a browser challenge (Cloudflare): the error offers the in-app browser.
    @Published private(set) var challenge: IosChallenge?
    struct PageFailure: Equatable {
        let message: String
        let challenge: IosChallenge?
    }
    private(set) var pageFailures: [Int: PageFailure] = [:] { didSet { syncPageStates() } }
    @Published private(set) var isLoadingPages = false
    @Published private(set) var bookmarks: [IosBookmark] = []
    private var changingBookmark = false
    @Published private(set) var slots: [IosReaderSlot] = [] {
        didSet {
            if !refreshingSlots { slotsVersion = nil }
            slotRows = slots.isEmpty ? [] : readSlotRows()
            identifiedSlots = slots.indices.map { ReaderWindowSlot(id: cachedSlotIdentity(at: $0), slot: slots[$0]) }
            slotByAnchor = [:]
            for row in slotRows where slotByAnchor[row.anchor] == nil { slotByAnchor[row.anchor] = row.index }
            slotRevision &+= 1
        }
    }
    /// [slots] as plain rows, in the same order.
    private(set) var slotRows: [ReaderSlotRow] = []
    /// The engine layout [slots] were taken from (see [refreshSlots]).
    private var slotsVersion: (generation: Int64, revision: Int64)?
    private var refreshingSlots = false
    /// The first slot anchored at each window position, for the strip's per-frame reading slot.
    private var slotByAnchor: [Int: Int] = [:]
    private(set) var identifiedSlots: [ReaderWindowSlot] = []
    /// Changes with every new [slots], so the strip rebuilds its cached geometry only then.
    private(set) var slotRevision = 0
    /// Single-page slot identities by window position and segment, for the current window: an image size changes every
    /// slot's geometry but no slot's identity, so [slots] are rebuilt without building every identity string again.
    private var slotIdentities: [Int: String] = [:]
    /// Display states of the window positions a page view asked for (see [pageState]); at most one per position.
    private var pageStates: [Int: ReaderPageState] = [:]
    private let emptyPageState = ReaderPageState()
    @Published private(set) var loaded = false
    @Published private(set) var pageRevision = 0 { didSet { syncPageStates() } }
    @Published private(set) var scrollRevision = 0
    @Published var slotIndex: Int = 0
    @Published var autoScroll = false
    /// A page magnified past its fit: drags pan it rather than turning the page.
    var isPageZoomed = false {
        didSet { if !isPageZoomed && pendingWindow && !isUserPaging { publishWindow() } }
    }
    /// The vertical strip's zoom (Android's webtoon scaling frame): the strip is laid out wider rather than magnified,
    /// so pages decode and tile at the size shown. Session-only, back to fit when the reading mode changes.
    @Published private(set) var stripZoom: CGFloat = 1
    private let stripZoomSession = ReaderStripZoomSession()
    let runtime: IosSources
    let content: IosSourceContent
    private let chapterByID: [String: IosSourceChapter]
    let engine = IosReader()
    /// Page OCR + translation (Android's manga translation), swapped in by `sync` when enabled.
    let translator = ReaderPageTranslator()
    var library: IosLibrary?
    private var request: UUID?
    /// One load (download and decode) per page, keyed by the page's identity and owned by the model, not by the view
    /// that asked: a page view that goes away or a window that is rebuilt never strands a load half-way, and a second
    /// caller waits for the running one. Loads stop only when their page leaves the resource window (see
    /// [updateResources]) or the scene changes.
    private var pageLoads: [String: (serial: Int, task: Task<Void, Never>)] = [:]
    /// Pages whose size was read ahead of their decode (see [measureAhead]), by identity.
    private var measuredPages: Set<String> = []
    /// The display limit each image on hand was decoded under, by page identity; absent when it was not limited.
    private var decodedLimits: [String: ReaderDisplayLimit] = [:]
    private var loadSerial = 0
    /// One download per page, shared by the page on screen and the prefetcher (Android's `PageLoader` tasks), keyed
    /// by page identity. Finished downloads stay: the artifact on disk is decoded again when the page returns.
    private var fetches: [String: PageFetch] = [:]
    /// Android's reader threads: pages downloading at the same time, the page on screen before prefetches.
    private let pageGate = RequestGate(limit: ReaderLoading.threads)
    /// The pages to keep decoded and the pages to download ahead, by identity, from the last planned window: the
    /// shared webtoon prediction in continuous modes, the pager's neighbours and prefetch window in paged ones.
    private var decodeWindow: Set<String> = []
    private var downloadWindow: [String] = []
    /// The planned window was dropped (loads cancelled): the next plan starts from scratch even if the strip did not
    /// move, or nothing but the reading page would load again.
    private var needsReplan = true
    /// The UIKit strip showing a continuous mode (see `ReaderStrip`). Window and size changes are applied to it in the
    /// same main-thread turn, like Android's `scrollState.snapBy(compensation.deltaY)`.
    weak var strip: ReaderStripHost?
    /// The strip has reported where it is since the scene was last laid out: anchored corrections may use it.
    private var stripPositionValid = false
    private var stripVelocity = CGPoint.zero
    private var stripDragging = false
    /// The next chapter's page list and first downloads, started near this chapter's end (see [preloadNextChapter]).
    /// The page list of a preloaded chapter being opened, used by [load] instead of asking the source again.
    private var preloadedPages: Task<[IosSourcePage], Error>?

    private final class ChapterPreload {
        let chapterId: String
        let pages: Task<[IosSourcePage], Error>
        var fetches: [Int: PageFetch] = [:]
        init(chapterId: String, pages: Task<[IosSourcePage], Error>) { self.chapterId = chapterId; self.pages = pages }
        func cancel() {
            pages.cancel()
            for fetch in fetches.values where !fetch.finished { fetch.task.cancel() }
        }
    }

    private final class PageFetch {
        let task: Task<IosSourceImage, Error>
        let ticket: Int
        let isPrefetch: Bool
        var consumers = 0
        var finished = false
        var failed = false
        init(task: Task<IosSourceImage, Error>, ticket: Int, isPrefetch: Bool) {
            self.task = task; self.ticket = ticket; self.isPrefetch = isPrefetch
        }
    }
    private var viewport = CGSize(width: 1, height: 1)
    private var preferences = ReaderPreferences()
    private var offset: Float = 0
    private var viewportOffset = CGPoint.zero
    private var savedPosition: IosReadingPosition?
    private(set) var restoreOrigin: CGFloat = 0
    /// The continuous scroll origin of the position being read now.
    var currentOrigin: CGFloat { stripOrigin(of: IosReaderProgress(index: Int32(windowIndex), offset: offset)) }
    private var awaitingRestore = false
    /// The continuous viewport's first visible page and its offset, compared by identity to tell a move back.
    /// [position] is where [page] was in the window then: checked first, so a move is found without a window search.
    private var viewAnchor: (page: IosReaderPage, position: Int, offset: Float)?
    /// The paged scroll view is moving under the reader's finger (dragged or settling from a drag). Like Android's
    /// pager reports (target page while scrolling, settled page after) and Aidoku's finished page-turn gestures, only
    /// these moves report a page: its own first layout and moves the model made (taps, the slider, restores) do not.
    var isUserPaging = false {
        didSet { if !isUserPaging && pendingWindow { publishWindow() } }
    }
    var pagedWindowNeedsRestore = false
    /// The window a user drag of the paged scroll view started on (see `settlePaging`).
    var pagingWindowRevision: Int?
    var scrollPhase: ScrollPhase = .idle

    var selectedPage: IosSourcePage? { pages.indices.contains(index) ? pages[index] : nil }
    var image: UIImage? { images[windowIndex] }
    var previousChapter: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: false) }
    var nextChapter: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: true) }
    var isBookmarked: Bool { bookmarks.contains { $0.chapterId == chapter.id && Int($0.index) == index } }

    private var initialPage: Int?
    /// An incognito session (`IncognitoGate`) records no history, progress, statistics or tracker updates.
    var incognito = false
    private var initialBookmark: IosBookmark?

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter, initialPage: Int? = nil,
         initialBookmark: IosBookmark? = nil) {
        self.runtime = runtime; self.content = content; self.chapter = chapter
        self.initialPage = initialPage
        self.initialBookmark = initialBookmark
        chapterByID = Dictionary(content.chapters.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        translator.onRendered = { [weak self] in self?.syncPageStates() }
    }

    /// Saves a page crop as a note (Android's panel crop notes); returns what to tell the reader.
    func saveCropNote(_ image: UIImage, rect: CGRect, note: String) async -> String {
        guard let library, let path = NoteImages.save(image) else { return String(localized: "无法保存笔记") }
        do {
            _ = try await library.notes.saveCrop(content: content, chapter: chapter, page: Int32(index), imagePath: path,
                note: note, left: Float(rect.minX), top: Float(rect.minY), right: Float(rect.maxX), bottom: Float(rect.maxY))
            return String(localized: "已存为笔记")
        } catch {
            NoteImages.remove(path)
            return error.localizedDescription
        }
    }

    /// Turns page translation on (loaded pages are queued at once) or back to the original pages.
    func toggleTranslation() {
        translator.toggle()
        syncPageStates()
    }

    func configure(size: CGSize, preferences: ReaderPreferences) {
        guard size.width > 1 && size.height > 1 else { return }
        let cropChanged = loaded && self.preferences.cropMargins != preferences.cropMargins
        let modeChanged = self.preferences.mode != preferences.mode
        let layoutChanged = viewport != size || !self.preferences.hasSameLayout(as: preferences) || slots.isEmpty
        if preferences.mode != self.preferences.mode || preferences.mode < 3 { stripZoom = 1; engine.setZoom(zoom: 1) }
        viewport = size; self.preferences = preferences
        if layoutChanged {
            engine.configure(count: Int32(windowPages.count), width: Int32(size.width), height: Int32(size.height),
                             mode: Int32(preferences.mode), doublePage: preferences.doublePage,
                             cover: preferences.cover, fit: Int32(preferences.fit),
                             splitWide: preferences.splitWidePages, horizontalReversed: preferences.horizontalReversed)
            refreshSlots()
            if let current = slots.first(where: { Int($0.index) == slotIndex }),
               current.placements.contains(where: { Int($0.index) == windowIndex }) {
                slotIndex = Int(current.index)
            } else {
                slotIndex = Int(slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?.index ?? 0)
            }
            // A new layout puts every page elsewhere: the reading position, not the old offset, is restored.
            stripPositionValid = false
            if loaded && preferences.mode >= 3 { requestScroll(to: currentOrigin) }
            if preferences.mode < 3 { pagedWindowNeedsRestore = true }
        }
        if cropChanged {
            cancelLoads()
            request = UUID()
            images = [:]; artifacts = [:]; pageFailures = [:]; pageRevision += 1
            updateResources(force: true)
        }
        if loaded && modeChanged { Task { @MainActor [weak self] in self?.publishWindow() } }
        if layoutChanged && !cropChanged { redecodeLimitedImages() }
    }

    private var startAtEnd = false
    /// An explicit chapter switch opens its start or end, as Android's `switchChapterBy`; history only resumes the
    /// chapter the reader was opened with.
    private var ignoresSavedPosition = false
    private var failedChapter: (chapter: IosSourceChapter, atEnd: Bool)?

    func load() async {
        guard !loaded else { return }
        let token = UUID(); request = token
        let selected = chapter
        isLoadingPages = true; error = nil; challenge = nil
        pageFailures = [:]
        defer {
            if request == token {
                isLoadingPages = false
                if pendingWindow { publishWindow() }
            }
        }
        do {
            let preloaded = preloadedPages
            preloadedPages = nil
            // A preload that failed is asked again, so its error is the source's current one.
            let early = try? await preloaded?.value
            let result: [IosSourcePage]
            if let early {
                result = early
            } else {
                result = try await ReaderDownloads.pages(runtime: runtime, content: content, chapter: selected)
            }
            let saved = try await library?.position(content: content)
            try Task.checkCancellation()
            guard request == token else { return }
            pages = result
            chapterPages[selected.id] = result
            savedPosition = saved
            if startAtEnd {
                index = max(0, pages.count - 1); offset = 0
            } else if let bookmark = initialBookmark, bookmark.chapterId == selected.id {
                index = min(max(0, Int(bookmark.index)), max(0, pages.count - 1))
                offset = Float(bookmark.scroll)
                self.initialBookmark = nil
            } else if let page = initialPage {
                index = min(max(0, page), max(0, pages.count - 1))
                offset = 0
                self.initialPage = nil
            } else if !ignoresSavedPosition, let savedPosition, savedPosition.chapterId == selected.id {
                index = min(max(0, Int(savedPosition.index)), max(0, pages.count - 1))
                offset = savedPosition.scroll
            }
            startAtEnd = false; ignoresSavedPosition = false
            publishWindow()
            configure(size: viewport, preferences: preferences)
            loaded = true; pageRevision += 1
            stripPositionValid = false
            if preferences.mode >= 3 { requestScroll(to: currentOrigin) }
            updateResources(force: true)
            await refreshBookmarks()
        } catch { if !Task.isCancelled && request == token { fail(error) } }
    }

    /// Shows the page at window position [target]: joins the page's running load or starts one, and waits for it.
    /// The load belongs to the model, so a caller that is cancelled (a page view scrolled away) leaves it running for
    /// the next one. The reading page's arrival also plans what comes after it.
    func loadImage(at target: Int) async {
        guard loaded, window.indices.contains(target) else { return }
        if images[target] == nil {
            await startLoad(window[target]).value
        }
        if target == windowIndex { updateResources() }
    }

    @discardableResult
    private func startLoad(_ key: IosReaderPage) -> Task<Void, Never> {
        let id = pageIdentity(key)
        if let running = pageLoads[id] { return running.task }
        loadSerial += 1
        let serial = loadSerial, token = request
        let task = Task { [weak self] in
            await self?.performLoad(key, token: token)
            if self?.pageLoads[id]?.serial == serial { self?.pageLoads[id] = nil }
        }
        pageLoads[id] = (serial, task)
        return task
    }

    private func performLoad(_ key: IosReaderPage, token: UUID?) async {
        guard let position = window.firstIndex(of: key), let owner = chapterByID[key.chapterId] else { return }
        let page = windowPages[position]
        let cropMargins = preferences.cropMargins
        let displayLimit = stripDisplayLimit
        do {
            let loadedPage: ReaderLoadedPage
            if ReaderDownloads.isDownloaded(content: content, chapter: owner) {
                loadedPage = try await ReaderDownloads.loadedPage(
                    runtime: runtime, content: content, chapter: owner,
                    page: page, index: Int(key.index), cropMargins: cropMargins, displayLimit: displayLimit
                )
            } else {
                loadedPage = try await pageArtifact(key: key, page: page)
                    .loadedPage(cropMargins: cropMargins, displayLimit: displayLimit)
            }
            // UIKit still draws the old scene while pinching/double tapping. A late image must not resize that
            // scene or evict its displayed neighbours before the compensated viewport is installed.
            await stripZoomSession.waitForLayout()
            try Task.checkCancellation()
            // The window may have been rebuilt meanwhile: the page goes where it is now, if it is still shown.
            guard request == token, let target = window.firstIndex(of: key) else { return }
            decodedLimits[pageIdentity(key)] = loadedPage.limited ? displayLimit : nil
            images[target] = loadedPage.image
            artifacts[target] = loadedPage.artifact
            pageFailures[target] = nil
            // A strip lays uncropped pages out at their encoded proportions, as their headers gave them before the
            // decode (see [measureAhead]): a downsampled decode does not move the page again by a rounding pixel.
            let size = preferences.mode >= 3 && !cropMargins && loadedPage.sourceWidth > 0
                ? (loadedPage.sourceWidth, loadedPage.sourceHeight)
                : (loadedPage.artifact.encodedWidth, loadedPage.artifact.encodedHeight)
            applyImageSize(target: target, width: size.0, height: size.1)
            if !slots.contains(where: { Int($0.index) == slotIndex && $0.placements.contains { Int($0.index) == windowIndex } }) {
                slotIndex = Int(slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?.index ?? 0)
            }
            evictImages()
            if slots.first(where: { $0.placements.contains { Int($0.index) == windowIndex } })?
                .placements.contains(where: { Int($0.index) == target }) == true {
                updateResources()
                // A strip in motion saves when it settles (see `ReaderStrip.settle`), not on every page it passes.
                if !isStripMoving { await persist() }
            }
        } catch {
            if !Task.isCancelled && request == token, let target = window.firstIndex(of: key) {
                pageFailures[target] = PageFailure(message: SourceErrorText.describe(error),
                                                   challenge: SourceErrorText.challenge(error))
            }
        }
    }

    /// Gives the scene page [target]'s real size and keeps the strip where the reader is (see [performLoad]).
    private func applyImageSize(target: Int, width: Int, height: Int) {
        // A reading page held ahead of the first visible one (a short page at the strip's end) is what the reader
        // is on: it, not the first visible page the scene anchors to, stays where it is on screen.
        let held = preferences.mode >= 3 && stripPositionValid && !awaitingRestore
            && windowIndex != Int(engine.progress(x: Float(viewportOffset.x), y: Float(viewportOffset.y)).index)
        let heldStart = held ? readingPageStart() : nil
        let compensated = engine.setImageSize(
            index: Int32(target), width: Int32(width), height: Int32(height),
            viewportX: Float(viewportOffset.x), viewportY: Float(viewportOffset.y)
        )
        let version = slotsVersion
        refreshSlots()
        guard preferences.mode >= 3 else { return }
        if awaitingRestore || !stripPositionValid {
            // Keep the semantic target while its estimated size becomes exact, including long-page bookmarks. Also
            // when the size was known ahead: the restore ends on a report with the reading page's image shown.
            requestScroll(to: currentOrigin)
        } else if let version, let slotsVersion, version == slotsVersion {
            // The engine already had this size (measured ahead): nothing moved.
            return
        } else if let heldStart, let start = readingPageStart() {
            var point = viewportOffset
            if preferences.mode == 3 { point.y += start - heldStart } else { point.x += start - heldStart }
            moveStrip(to: IosReaderViewport(x: Float(point.x), y: Float(point.y)))
        } else if let compensated {
            // The scene's anchored correction (Android's `snapBy(compensation.deltaY)`), applied with the new
            // layout in the same turn so the page being read does not move.
            moveStrip(to: compensated)
        } else {
            strip?.layoutStrip(offset: nil, animated: false)
        }
    }

    /// The page's downloaded artifact. A prefetch already under way is joined and moved ahead of other prefetches;
    /// a download only the screen wanted is cancelled once no page load waits for it, as Android releases it.
    private func pageArtifact(key: IosReaderPage, page: IosSourcePage) async throws -> IosSourceImage {
        let id = pageIdentity(key)
        let fetch = fetches[id].flatMap { $0.failed ? nil : $0 } ?? startFetch(key, page: page, isPrefetch: false)
        fetch.consumers += 1
        await pageGate.promote(fetch.ticket, to: ReaderLoading.visiblePriority)
        var released = false
        let release = { [weak self] in
            guard !released else { return }
            released = true
            fetch.consumers -= 1
            if fetch.consumers == 0, !fetch.isPrefetch, !fetch.finished {
                fetch.task.cancel()
                if self?.fetches[id] === fetch { self?.fetches[id] = nil }
            }
        }
        defer { release() }
        return try await withTaskCancellationHandler {
            try await fetch.task.value
        } onCancel: {
            Task { @MainActor in release() }
        }
    }

    @discardableResult
    private func startFetch(_ key: IosReaderPage, page: IosSourcePage, isPrefetch: Bool) -> PageFetch {
        let prepared = chapterPreloads[key.chapterId]?.fetches[Int(key.index)]
        let fetch = prepared.flatMap { $0.failed ? nil : $0 } ?? makeFetch(page: page, isPrefetch: isPrefetch)
        fetches[pageIdentity(key)] = fetch
        return fetch
    }

    /// A download of [page], not yet tied to a page of the open chapter (a preloaded next chapter's page). Cancelling
    /// it reaches the source call itself (see `IosSources.cancellableImage`), so its reader-thread slot frees at once.
    private func makeFetch(page: IosSourcePage, isPrefetch: Bool) -> PageFetch {
        let runtime = runtime, gate = pageGate
        let ticket = RequestGate.makeTicket()
        let priority = isPrefetch ? ReaderLoading.prefetchPriority : ReaderLoading.visiblePriority
        let task = Task {
            try await withReaderStorage {
                try await gated(gate, priority: priority, ticket: ticket) { try await runtime.cancellableImage(page: page) }
            }
        }
        let fetch = PageFetch(task: task, ticket: ticket, isPrefetch: isPrefetch)
        Task { [weak self] in
            let succeeded = (try? await task.value) != nil
            if succeeded { fetch.finished = true; return }
            fetch.failed = true
            guard let self, let id = self.fetches.first(where: { $0.value === fetch })?.key else { return }
            self.fetches[id] = nil
        }
        return fetch
    }

    private func pageIdentity(_ key: IosReaderPage) -> String { "\(key.chapterId):\(key.index)" }

    /// Android's ChapterPages and this host consume the same reading-order window policy: paged modes show the
    /// neighbors' two boundary pages, continuous strips their complete chapters (Android's webtoon shows every loaded
    /// chapter). Metadata is bounded to three chapters; decoded images remain bounded to the resource window.
    ///
    /// A continuous strip is updated in place, like Android's webtoon window expansion: the scene keeps every page's
    /// identity, so the page under the viewport keeps its place on screen while chapters join or leave before it.
    private func publishWindow() {
        func keys(_ chapter: IosSourceChapter?) -> [IosReaderPage] {
            guard let chapter, let pages = chapterPages[chapter.id] else { return [] }
            return pages.indices.map { IosReaderPage(chapterId: chapter.id, index: Int32($0)) }
        }
        let continuous = preferences.mode >= 3
        let updated = engine.chapterWindow(previous: keys(previousChapter), current: keys(chapter),
                                           next: keys(nextChapter),
                                           adjacentPageCount: continuous ? Int32.max : 2)
        if loaded && (isLoadingPages || isPageZoomed || stripZoomSession.isActive || (!continuous && isUserPaging)) {
            pendingWindow = true
            return
        }
        pendingWindow = false
        guard updated != window else { return }
        let previous = window
        let positions = Dictionary(updated.enumerated().map { ($0.element, $0.offset) }, uniquingKeysWith: { first, _ in first })
        func remap<Value>(_ values: [Int: Value]) -> [Int: Value] {
            var result: [Int: Value] = [:]
            for (index, value) in values where previous.indices.contains(index) {
                if let position = positions[previous[index]] { result[position] = value }
            }
            return result
        }
        // Loads and downloads are keyed by page identity and keep running; only position-keyed state moves.
        images = remap(images); artifacts = remap(artifacts); pageFailures = remap(pageFailures)
        let segment = slots.first { Int($0.index) == slotIndex }?.placements.first { Int($0.index) == windowIndex }?.segment
        window = updated
        windowPages = updated.compactMap { chapterPages[$0.chapterId]?[Int($0.index)] }
        windowIndex = updated.firstIndex { $0.chapterId == chapter.id && Int($0.index) == index } ?? 0
        if continuous && loaded && !previous.isEmpty && stripPositionValid && !awaitingRestore {
            let moved = engine.setPages(pages: updated, viewportX: Float(viewportOffset.x),
                                        viewportY: Float(viewportOffset.y))
            refreshSlots()
            slotIndex = slotByAnchor[windowIndex] ?? 0
            windowRevision += 1
            if let moved { moveStrip(to: moved) } else { requestScroll(to: currentOrigin) }
        } else {
            engine.setPages(pages: updated)
            slots = []
            configure(size: viewport, preferences: preferences)
            slotIndex = Int(slots.first { $0.placements.contains {
                Int($0.index) == windowIndex && (segment == nil || $0.segment == segment)
            }}?.index ?? 0)
            windowRevision += 1
            if continuous { requestScroll(to: currentOrigin) }
        }
        retainAdjacentChapters()
        if loaded { updateResources(force: true) }
    }

    /// Moves the strip to a viewport the scene compensated (scene coordinates are the strip's own offsets).
    /// The reading page's physical start along the strip's axis (scene coordinates).
    private func readingPageStart() -> CGFloat? {
        guard slots.indices.contains(windowIndex), let placement = slots[windowIndex].placements.first else { return nil }
        return CGFloat(preferences.mode == 3 ? placement.y : placement.x)
    }

    /// The strip's end can clamp it (a short chapter after a long one), which changes the first visible page without the
    /// reader moving: the motion anchor restarts from where the strip lands, so that is not read as a move back.
    private func moveStrip(to viewport: IosReaderViewport) {
        let limit = continuousScrollRange.upperBound
        var point = CGPoint(x: CGFloat(viewport.x), y: CGFloat(viewport.y))
        if preferences.mode == 3 { point.y = min(max(0, point.y), limit) } else { point.x = min(max(0, point.x), limit) }
        viewportOffset = point
        restoreOrigin = preferences.mode == 3 ? point.y : point.x
        viewAnchor = nil
        strip?.layoutStrip(offset: point, animated: false)
    }

    private func retainAdjacentChapters() {
        let retained = Set([chapter.id, previousChapter?.id, nextChapter?.id].compactMap { $0 })
        chapterPages = chapterPages.filter { retained.contains($0.key) }
        for (id, preload) in chapterPreloads where !retained.contains(id) {
            preload.cancel(); chapterPreloads[id] = nil
        }
    }

    /// The strip's distance from its start along the reading direction, in screens.
    private var screensFromStripStart: CGFloat {
        let extent = max(1, preferences.mode == 3 ? viewport.height : viewport.width)
        let primary = preferences.mode == 3 ? viewportOffset.y : viewportOffset.x
        let fromStart = preferences.continuousForwardSign > 0 ? primary : CGFloat(engine.scrollLimit()) - primary
        return max(0, fromStart) / extent
    }

    /// Paged modes load both neighbours near the chapter's edges. A continuous strip loads the next chapter ten pages
    /// before its end (Android's webtoon) but the previous one only once the reader is within three screens of the
    /// strip's start, as Aidoku prepends only at the top: a chapter opened in the middle does not grow backwards.
    private func preloadAdjacentChapters() {
        guard loaded else { return }
        let continuous = preferences.mode >= 3
        let nearStart = continuous
            ? stripPositionValid && !awaitingRestore && (screensFromStripStart < 3 || index <= 1)
            : index <= 2
        let nearEnd = index >= pages.count - 1 - (continuous ? 10 : 2)
        let targets = [nearStart ? previousChapter : nil, nearEnd ? nextChapter : nil].compactMap { $0 }
        for target in targets where chapterPages[target.id] == nil && chapterPreloads[target.id] == nil {
            let runtime = runtime, content = content
            let preload = ChapterPreload(chapterId: target.id, pages: Task {
                try await ReaderDownloads.pages(runtime: runtime, content: content, chapter: target)
            })
            chapterPreloads[target.id] = preload
            Task { [weak self] in
                do {
                    let result = try await preload.pages.value
                    try Task.checkCancellation()
                    guard !result.isEmpty else { throw URLError(.zeroByteResource) }
                    guard let self, self.chapterPreloads[target.id] === preload else { return }
                    self.chapterPages[target.id] = result
                    if ReaderLoading.isPrefetchApplicable,
                       !ReaderDownloads.isDownloaded(content: self.content, chapter: target) {
                        let range = target.id == self.previousChapter?.id
                            ? max(0, result.count - 2)..<result.count
                            : 0..<min(result.count, ReaderLoading.prefetchLimit)
                        for index in range { preload.fetches[index] = self.makeFetch(page: result[index], isPrefetch: true) }
                    }
                    self.publishWindow()
                } catch {
                    guard let self, self.chapterPreloads[target.id] === preload else { return }
                    self.chapterPreloads[target.id] = nil
                    // A neighbor's metadata failure leaves the active chapter readable and can be retried.
                }
            }
        }
    }

    private func selectWindowPage(_ target: Int) {
        guard window.indices.contains(target) else { return }
        let key = window[target]
        if windowIndex != target { windowIndex = target }
        if key.chapterId != chapter.id,
           let owner = chapterByID[key.chapterId],
           let ownerPages = chapterPages[key.chapterId] {
            chapter = owner; pages = ownerPages; pendingWindow = true
        }
        if index != Int(key.index) { index = Int(key.index) }
        preloadAdjacentChapters()
        // A strip takes the new window at once (its anchor keeps the screen still); a pager waits for the drag.
        if pendingWindow && (preferences.mode >= 3 || !isUserPaging) {
            Task { @MainActor [weak self] in self?.publishWindow() }
        }
    }

    /// Plans the pages to decode and to download ahead, then starts what is missing and stops what is no longer
    /// wanted. Continuous strips use the shared webtoon prediction (`IosReader.resourceWindow`, Android's
    /// `ReaderPrediction`), which only replans when the visible pages, the scene or the motion class change, unless
    /// [force]d; paged modes keep Android's pager neighbours and its ten-page prefetch window.
    private func updateResources(force: Bool = false) {
        guard loaded, !window.isEmpty else { return }
        guard !stripZoomSession.isActive else { needsReplan = true; return }
        preloadAdjacentChapters()
        if preferences.mode >= 3 {
            // Low Power Mode plans Android's reduced window; switching plans again from scratch.
            if engine.setPreloadReduction(enabled: ProcessInfo.processInfo.isLowPowerModeEnabled) { needsReplan = true }
            guard let planned = engine.resourceWindow(
                x: Float(viewportOffset.x), y: Float(viewportOffset.y),
                velocityX: Float(stripVelocity.x), velocityY: Float(stripVelocity.y),
                dragging: stripDragging, force: force || needsReplan
            ) else { return }
            let requests = planned.filter { window.indices.contains(Int($0.index)) }
                .sorted { $0.priority < $1.priority }
            // Decoded pages fit a byte budget, as Aidoku bounds its decoded page cache: the pages on screen always,
            // then the planned ones by priority and nearest the screen first. Past the budget, pages only download.
            var budget = ReaderLoading.decodedImageBudget
            var decodeTargets: [Int] = [], downloadOnly: [Int] = []
            let ordered = requests.filter(\.decode).sorted {
                ($0.priority, stripDistance(Int($0.index))) < ($1.priority, stripDistance(Int($1.index)))
            }
            for request in ordered {
                let target = Int(request.index), cost = decodedCost(target)
                if request.priority > 0 && (cost > budget || !downloadOnly.isEmpty) {
                    downloadOnly.append(target)
                } else {
                    budget -= cost
                    decodeTargets.append(target)
                }
            }
            let unzoomed = unzoomedStripPages()
            decodeWindow = Set((decodeTargets + unzoomed).map { pageIdentity(window[$0]) })
            downloadWindow = (downloadOnly + requests.filter { !$0.decode }.map { Int($0.index) })
                .map { pageIdentity(window[$0]) }
            // Visible and near pages load at once, nearest first.
            for target in decodeTargets { startWanted(target) }
            for target in unzoomed { startWanted(target) }
        } else {
            let neighbors = neighborPages()
            decodeWindow = Set(neighbors.map { pageIdentity(window[$0]) })
            let upper = slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?
                .placements.map { Int($0.index) }.max() ?? windowIndex
            let ahead = (upper + 1)..<min(window.count, upper + 1 + ReaderLoading.prefetchWindow)
            downloadWindow = ahead.map { pageIdentity(window[$0]) }
            for target in neighbors.sorted() { startWanted(target) }
        }
        needsReplan = false
        for (id, load) in pageLoads where !decodeWindow.contains(id) {
            load.task.cancel(); pageLoads[id] = nil
        }
        prefetch()
        measureAhead()
        evictImages()
    }

    /// The planned pages get their real size from their file's header as soon as it is on disk, before they are
    /// decoded: the strip lays them out while they are still off screen instead of resizing as they scroll in over
    /// estimated heights. Only uncropped strips: a cropped page's size is known after its decode.
    private func measureAhead() {
        guard preferences.mode >= 3, !preferences.cropMargins else { return }
        let token = request
        for id in decodeWindow.union(downloadWindow) where !measuredPages.contains(id) {
            guard let position = windowPositions[id], images[position] == nil,
                  let owner = chapterByID[window[position].chapterId] else { continue }
            let key = window[position]
            let size: () async -> (width: Int, height: Int)?
            if ReaderDownloads.isDownloaded(content: content, chapter: owner) {
                let content = content
                size = { await ReaderDownloads.sourceSize(content: content, chapter: owner, index: Int(key.index)) }
            } else if let fetch = fetches[id], !fetch.failed {
                size = {
                    guard let image = try? await fetch.task.value else { return nil }
                    return await image.sourceSize()
                }
            } else {
                continue // Not downloading yet: the next plan measures it.
            }
            measuredPages.insert(id)
            Task { [weak self] in
                guard let measured = await size() else { return }
                guard let self, self.request == token, !self.stripZoomSession.isActive,
                      let target = self.windowPositions[id], self.images[target] == nil else { return }
                self.applyImageSize(target: target, width: measured.width, height: measured.height)
            }
        }
    }

    /// The bytes window position [target] takes decoded: its bitmap's once decoded, else its strip extent at the
    /// screen's scale (what a display-limited decode makes), at most one 64 MB single-bitmap page.
    private func decodedCost(_ target: Int) -> Int {
        if let image = images[target]?.cgImage { return image.bytesPerRow * image.height }
        guard slotRows.indices.contains(target), slotRows[target].page == target else { return 16 << 20 }
        let frame = slotRows[target].frame, scale = screenScale
        let bytes = Double(frame.width * scale) * Double(frame.height * scale) * 4
        return Int(min(bytes, Double(64 << 20)))
    }

    /// How far window position [target]'s page is from the strip's screen along its axis (0 when on it).
    private func stripDistance(_ target: Int) -> CGFloat {
        guard slotRows.indices.contains(target), slotRows[target].page == target else { return .greatestFiniteMagnitude }
        let vertical = stripIsVertical, frame = slotRows[target].frame
        let lower = vertical ? viewportOffset.y : viewportOffset.x
        let upper = lower + (vertical ? viewport.height : viewport.width)
        let start = vertical ? frame.minY : frame.minX, end = vertical ? frame.maxY : frame.maxX
        return max(0, start - upper, lower - end)
    }

    private var screenScale: CGFloat {
        (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.screen.scale ?? 3
    }

    /// Plans again from scratch, starting every wanted page that is neither shown nor loading.
    func refreshResources() { updateResources(force: true) }

    /// The pages a zoomed strip shows once it zooms back out to fit about any point on screen. The prediction plans
    /// for the zoomed viewport, so a zoom out would otherwise reveal pages it evicted and draw them blank until the
    /// zoom ends; this keeps what the unzoomed strip itself kept.
    private func unzoomedStripPages() -> [Int] {
        guard stripZoom > 1 else { return [] }
        let vertical = stripIsVertical
        let extent = vertical ? viewport.height : viewport.width
        let origin = vertical ? viewportOffset.y : viewportOffset.x
        let lower = origin - extent * (stripZoom - 1), upper = origin + extent * stripZoom
        return slotRows.compactMap { row in
            let start = vertical ? row.frame.minY : row.frame.minX
            let end = vertical ? row.frame.maxY : row.frame.maxX
            return end > lower && start < upper && window.indices.contains(row.page) ? row.page : nil
        }
    }

    private func startWanted(_ target: Int) {
        guard window.indices.contains(target), images[target] == nil, pageFailures[target] == nil else { return }
        startLoad(window[target])
    }

    /// Downloads the planned lookahead that is not decoded, nearest first and at most the prefetch limit at a time
    /// (Android's `PageLoader` queue); prefetches that dropped out of the window are cancelled. Downloaded chapters
    /// and saving modes need nothing.
    private func prefetch() {
        let wanted = Set(downloadWindow)
        for (id, fetch) in fetches where fetch.isPrefetch && !fetch.finished && fetch.consumers == 0
            && !wanted.contains(id) && !decodeWindow.contains(id) {
            fetch.task.cancel()
            fetches[id] = nil
        }
        guard ReaderLoading.isPrefetchApplicable else { return }
        var started = 0
        // One file check per chapter, not per planned page.
        var downloaded: [String: Bool] = [:]
        for id in downloadWindow where started < ReaderLoading.prefetchLimit {
            guard fetches[id] == nil, let position = windowPositions[id], images[position] == nil,
                  let owner = chapterByID[window[position].chapterId] else { continue }
            let isDownloaded = downloaded[owner.id] ?? ReaderDownloads.isDownloaded(content: content, chapter: owner)
            downloaded[owner.id] = isDownloaded
            guard !isDownloaded else { continue }
            startFetch(window[position], page: windowPages[position], isPrefetch: true)
            started += 1
        }
    }

    /// Decoded images stay bounded to the planned decode window and the pages on screen; immutable on-disk artifacts
    /// survive eviction and are decoded again when their page returns.
    private func evictImages() {
        guard !stripZoomSession.isActive else { return }
        var keep = Set(decodeWindow.compactMap { windowPositions[$0] })
        if preferences.mode >= 3 {
            let x = Float(viewportOffset.x), y = Float(viewportOffset.y)
            let position = engine.position(x: x, y: y)
            let first = Int(position.index), last = Int(position.lastVisible)
            if first <= last { keep.formUnion(first...last) }
        } else {
            keep.formUnion(max(0, windowIndex - 2)...min(max(0, window.count - 1), windowIndex + 3))
        }
        keep.insert(windowIndex)
        // Unchanged dictionaries are not assigned again: every assignment re-renders every page view.
        if images.keys.contains(where: { !keep.contains($0) }) { images = images.filter { keep.contains($0.key) } }
        if artifacts.keys.contains(where: { !keep.contains($0) }) {
            artifacts = artifacts.filter { keep.contains($0.key) }
        }
    }

    /// A memory warning on the strip: decoded pages more than a screen from it go, the lookahead included, and their
    /// loads stop. Their files stay on disk and decode again when the strip plans its next window.
    func trimDecodedImages() {
        guard loaded, preferences.mode >= 3, !stripZoomSession.isActive else { return }
        let vertical = stripIsVertical
        let extent = vertical ? viewport.height : viewport.width
        let origin = vertical ? viewportOffset.y : viewportOffset.x
        let lower = origin - extent, upper = origin + 2 * extent
        var keep: Set<Int> = [windowIndex]
        for row in slotRows {
            let start = vertical ? row.frame.minY : row.frame.minX
            let end = vertical ? row.frame.maxY : row.frame.maxX
            if end > lower && start < upper { keep.insert(row.page) }
        }
        let kept = Set(keep.filter { window.indices.contains($0) }.map { pageIdentity(window[$0]) })
        decodeWindow.formIntersection(kept)
        for (id, load) in pageLoads where !kept.contains(id) {
            load.task.cancel(); pageLoads[id] = nil
        }
        if images.keys.contains(where: { !keep.contains($0) }) { images = images.filter { keep.contains($0.key) } }
        if artifacts.keys.contains(where: { !keep.contains($0) }) {
            artifacts = artifacts.filter { keep.contains($0.key) }
        }
    }

    /// Android's pager composes one slot on each side of the screen (`beyondViewportPageCount = 1`); Aidoku keeps two
    /// pages ahead decoded (`Reader.pagesToPreload`). Those pages load at visible priority before they turn in.
    private func neighborPages() -> Set<Int> {
        guard let current = slots.firstIndex(where: { $0.placements.contains { Int($0.index) == windowIndex } }) else {
            return []
        }
        var result = Set<Int>()
        for position in [current - 1, current, current + 1, current + 2] where slots.indices.contains(position) {
            for placement in slots[position].placements { result.insert(Int(placement.index)) }
        }
        return result
    }

    /// The strip's [ReaderDisplayLimit] now: its cross-axis extent at the current zoom, in screen pixels. Paged modes
    /// have none (a page zooms far past its fit there).
    private var stripDisplayLimit: ReaderDisplayLimit? {
        guard preferences.mode >= 3 else { return nil }
        let vertical = stripIsVertical
        let extent = (vertical ? viewport.width : viewport.height) * max(1, stripZoom) * screenScale
        return ReaderDisplayLimit(pixels: max(1, Int(extent.rounded(.up))), alongWidth: vertical)
    }

    /// Pages decoded for a smaller strip (before a zoom in or a rotation, or for a strip left for a paged mode) decode
    /// again at the size shown now. Their current image stays until the sharper one replaces it.
    private func redecodeLimitedImages() {
        guard loaded, !decodedLimits.isEmpty else { return }
        let needed = stripDisplayLimit
        for target in images.keys.sorted() where window.indices.contains(target) {
            let key = window[target]
            guard let decoded = decodedLimits[pageIdentity(key)] else { continue }
            if let needed, needed.alongWidth == decoded.alongWidth, needed.pixels <= decoded.pixels { continue }
            startLoad(key)
        }
    }

    /// Leaving the reader: nothing ahead of the screen is wanted any more.
    func stopPreloading() {
        cancelLoads()
        for preload in chapterPreloads.values { preload.cancel() }; chapterPreloads = [:]
        fetches = fetches.filter { $0.value.finished }
        request = UUID()
        isLoadingPages = false
    }

    /// Stops every page load and unfinished download (a new scene, a crop change or leaving the reader).
    private func cancelLoads() {
        for load in pageLoads.values { load.task.cancel() }
        pageLoads = [:]
        for fetch in fetches.values where !fetch.finished { fetch.task.cancel() }
        fetches = fetches.filter { $0.value.finished }
        decodeWindow = []; downloadWindow = []
        needsReplan = true
    }

    /// The display state of window position [target], kept in step with [images], [artifacts], [pageFailures],
    /// [pageRevision] and the window.
    func pageState(at target: Int) -> ReaderPageState {
        guard target >= 0 else { return emptyPageState }
        if let state = pageStates[target] { return state }
        let state = ReaderPageState()
        pageStates[target] = state
        sync(state, at: target)
        state.description = pageDescription(at: target)
        return state
    }

    private func syncPageStates() {
        for (target, state) in pageStates { sync(state, at: target) }
    }

    /// Assigns only what changed: each assignment re-renders that page.
    private func sync(_ state: ReaderPageState, at target: Int) {
        var changed = false
        var image = images[target]
        var artifact = artifacts[target]
        // A translated page replaces the original at the same size; its tiles would show the original text.
        if translator.enabled, let base = image, window.indices.contains(target) {
            let key = pageIdentity(window[target])
            if let translated = translator.rendered[key] {
                image = translated
                artifact = nil
                let description = pageDescription(at: target) + "：" + (translator.texts[key] ?? []).joined(separator: " ")
                if state.description != description { state.description = description; changed = true }
            } else {
                translator.enqueue(key: key, image: base)
            }
        }
        if state.image !== image { state.image = image; changed = true }
        if state.artifact != artifact { state.artifact = artifact; changed = true }
        let failure = pageFailures[target]
        if state.failure != failure { state.failure = failure; changed = true }
        if state.revision != pageRevision { state.revision = pageRevision; changed = true }
        if changed { state.didChange.send() }
    }

    func pageDescription(at target: Int) -> String {
        guard window.indices.contains(target) else { return String(localized: "漫画页") }
        let key = window[target]
        let chapter = chapterByID[key.chapterId]?.title ?? String(localized: "章节")
        return String(localized: "\(chapter)，第 \(key.index + 1) 页")
    }

    private func cachedSlotIdentity(at position: Int) -> String {
        guard slotRows.indices.contains(position) else { return slotIdentity(slots[position]) }
        let row = slotRows[position]
        guard row.placementCount == 1, window.indices.contains(row.page) else { return slotIdentity(slots[position]) }
        let key = row.page * 4 + row.segment
        if let identity = slotIdentities[key] { return identity }
        let identity = slotIdentity(slots[position])
        slotIdentities[key] = identity
        return identity
    }

    /// Takes the engine's slots, unless its layout is the one they were last taken from (an image size it already
    /// had, a window that did not change the scene): every new slot list rebuilds the strip's geometry.
    private func refreshSlots() {
        let version = (generation: engine.layoutGeneration, revision: engine.sceneRevision)
        if let slotsVersion, slotsVersion == version { return }
        refreshingSlots = true
        defer { refreshingSlots = false }
        slotsVersion = version
        slots = engine.slots()
    }

    /// The engine's slot table, one buffer for every slot; empty when it does not match [slots].
    private func readSlotRows() -> [ReaderSlotRow] {
        let data = engine.slotTable()
        let columns = Int(IosReader.companion.SLOT_TABLE_COLUMNS)
        let rows = data.count / (columns * MemoryLayout<Float>.size)
        guard rows == slots.count else { return [] }
        return data.withUnsafeBytes { raw -> [ReaderSlotRow] in
            let values = raw.bindMemory(to: Float.self)
            return (0..<rows).map { row in
                let base = row * columns
                func value(_ column: Int) -> Float { values[base + column] }
                return ReaderSlotRow(
                    index: Int(value(0)), anchor: Int(value(1)), placementCount: Int(value(2)),
                    frame: CGRect(x: CGFloat(value(3)), y: CGFloat(value(4)),
                                  width: CGFloat(value(5)), height: CGFloat(value(6))),
                    page: Int(value(7)), segment: Int(value(8))
                )
            }
        }
    }

    func slotIdentity(_ slot: IosReaderSlot) -> String {
        let identity = slot.placements.compactMap { placement -> String? in
            guard window.indices.contains(Int(placement.index)) else { return nil }
            return "\(pageIdentity(window[Int(placement.index)])):\(placement.segment)"
        }.joined(separator: "/")
        return identity.isEmpty ? "slot-\(slot.index)" : identity
    }

    /// A slot the paged scroll view settled on under the reader's finger.
    func scrolledToSlot(_ selected: Int) {
        guard isUserPaging, let slot = slots.first(where: { Int($0.index) == selected }),
              slotIndex != selected || windowIndex != Int(slot.anchor) else { return }
        if slotIndex != selected { slotIndex = selected }
        selectSlot(selected)
    }

    func selectSlot(_ selected: Int) {
        guard let slot = slots.first(where: { Int($0.index) == selected }) else { return }
        selectWindowPage(Int(slot.anchor)); offset = 0
        Task { await persist() }
    }

    /// The strip's reachable offsets along the reading axis: it shows the scene with no insets, so they are the
    /// scene's own.
    var continuousScrollRange: ClosedRange<CGFloat> { 0...CGFloat(engine.scrollLimit()) }

    /// The vertical webtoon scrolls down; the horizontal strip (mode 4) sideways.
    var stripIsVertical: Bool { preferences.mode != 4 }

    /// Where the strip shows now (its native offset is the scene's), for taps, auto-scroll and zoom.
    var stripOffset: CGPoint { strip?.offset ?? viewportOffset }

    /// The strip moved to ([x], [y]) (scene coordinates), at [velocity] px/s, with a finger on it when [dragging].
    func continuousPosition(x: CGFloat, y: CGFloat, velocity: CGPoint = .zero, dragging: Bool = false) {
        guard loaded, !windowPages.isEmpty else { return }
        stripVelocity = velocity; stripDragging = dragging
        let range = continuousScrollRange
        let limit = range.upperBound
        let raw = preferences.mode == 3 ? y : x
        let primary = min(max(0, raw), limit)
        viewportOffset = CGPoint(x: preferences.mode == 3 ? x : primary,
                                 y: preferences.mode == 3 ? primary : y)
        if awaitingRestore {
            // A target past what the strip can reach rests at its edge.
            let target = min(max(restoreOrigin, range.lowerBound), range.upperBound)
            // Until the reading page's real size is known, its estimated height can clamp the target short of a long
            // page's saved offset: the restore holds the reading page and offset, not where the strip landed.
            guard images[windowIndex] != nil, abs(raw - target) < 2 else { return }
            awaitingRestore = false
        }
        stripPositionValid = true
        // One call for the reading page, its offset and the last visible page (the engine resolves the frame once).
        let progress = engine.position(x: Float(viewportOffset.x), y: Float(viewportOffset.y))
        let first = min(max(0, Int(progress.index)), max(0, windowPages.count - 1))
        let last = Int(progress.lastVisible)
        // Motion is compared by page identity, so the coordinate shift of a rebuilt window is not a move.
        let movedBack: Bool = {
            guard let previous = viewAnchor else { return false }
            let before = window.indices.contains(previous.position) && window[previous.position] == previous.page
                ? previous.position : windowPositions[pageIdentity(previous.page)]
            guard let before else { return false }
            return first < before || first == before && progress.offset < previous.offset - 0.5
        }()
        if window.indices.contains(first) { viewAnchor = (window[first], first, progress.offset) }
        // The first visible page is the reading page, but pages shorter than the screen at the strip's end never
        // become first: scrolling to the end reads its last visible page, so a short next chapter is entered (and the
        // one after it loaded) and the book's last pages are recorded. A reading page ahead of the first visible one
        // (from that end, or a restore the strip end clamped) stays while it is shown, until the reader goes back;
        // a strip that fits on screen cannot scroll, so there it only keeps the page it was given.
        let forward = preferences.continuousForwardSign
        let scrollable = range.upperBound - range.lowerBound > 1
        let atEnd = scrollable && (forward > 0 ? raw >= range.upperBound - 1 : raw <= range.lowerBound + 1)
        var nextIndex = first
        if atEnd && (isUserPaging || autoScroll) && windowPages.indices.contains(last) && last > first {
            nextIndex = last
        } else if last >= first && windowIndex > first && windowIndex <= last && (!movedBack || atEnd || !scrollable) {
            nextIndex = windowIndex
        }
        let nextOffset = nextIndex == first ? progress.offset : 0
        if windowIndex != nextIndex { selectWindowPage(nextIndex) }
        offset = nextOffset
        let nextSlot = slotByAnchor[windowIndex] ?? 0
        if slotIndex != nextSlot { slotIndex = nextSlot }
        updateResources()
    }

    func beginStripZoom() {
        stripZoomSession.begin()
        needsReplan = true
    }

    /// Called after the native camera has rebased and reported its final viewport, or when its host detaches.
    func endStripZoom() {
        guard stripZoomSession.isActive else { return }
        stripZoomSession.end()
        if pendingWindow { publishWindow() }
        updateResources()
        redecodeLimitedImages()
    }

    func setStripZoom(_ value: CGFloat) {
        guard preferences.mode >= 3 else { return }
        let zoom = min(max(value, CGFloat(IosReader.companion.MINIMUM_ZOOM)), CGFloat(IosReader.companion.MAXIMUM_ZOOM))
        guard zoom != stripZoom else { return }
        stripZoom = zoom
        engine.setZoom(zoom: Float(zoom))
        refreshSlots()
        // The strip reports its compensated viewport immediately after laying out these slots. Planning here would
        // use the previous scene's offset and could evict the page that is still under the zoom anchor.
        needsReplan = true
    }

    /// A point on the strip's screen as the shared scene reads it: the page under [location] (a point on screen while
    /// the strip is at [offset]) and the offset into that page in unzoomed pixels. Both are what a zoom leaves
    /// unchanged, like a saved position, so laying the strip out at another zoom can put the same content back where
    /// the finger is.
    func stripAnchor(at offset: CGPoint, location: CGPoint) -> IosReaderProgress {
        engine.progress(x: Float(offset.x + (stripIsVertical ? 0 : location.x)),
                        y: Float(offset.y + (stripIsVertical ? location.y : 0)))
    }

    /// Where the strip must scroll for [anchor]'s content point of a strip laid out again at its current zoom.
    func stripOrigin(of anchor: IosReaderProgress) -> CGFloat {
        CGFloat(engine.scrollOrigin(index: anchor.index, offset: anchor.offset))
    }

    func userScrolled() { awaitingRestore = false; autoScroll = false }

    /// The continuous strip is being dragged, flung or auto-scrolled: position saves wait for it to come to rest (or
    /// for auto-scroll's own periodic save).
    var isStripMoving: Bool { preferences.mode >= 3 && (isUserPaging || autoScroll) }

    /// Restores the strip to [origin] along the reading axis. The strip moves there at once when it is shown, or
    /// when it is next laid out (`ReaderStrip` reads [restoreOrigin]); until it reports that position, the reading page
    /// and offset stay the target, so late image sizes re-resolve it.
    private func requestScroll(to origin: CGFloat) {
        restoreOrigin = origin
        awaitingRestore = true
        viewAnchor = nil
        scrollRevision += 1
        strip?.layoutStrip(origin: origin, animated: false)
    }

    /// Scrolls the strip along the reading axis (taps, keys, auto-scroll), keeping its cross-axis pan.
    func scrollStrip(to origin: CGFloat, animated: Bool) {
        strip?.layoutStrip(origin: origin, animated: animated)
    }

    /// The strip was created (a mode switch or a new chapter scene) and laid out: it starts at the reading position.
    func stripAttached() {
        guard loaded, preferences.mode >= 3 else { return }
        stripPositionValid = false
        // The reading page and its offset are the position: during a restore they are its target.
        requestScroll(to: currentOrigin)
    }

    var readingPositionDescription: String {
        String(localized: "第 \(index + 1) 页，缩放 \(Int(stripZoom * 100))%，页内偏移 \(Int(offset))，窗口 \(window.count) 页")
    }

    func jump(to page: Int, scroll: Float = 0) {
        guard pages.indices.contains(page) else { return }
        index = page; offset = scroll.isFinite ? max(0, scroll) : 0
        windowIndex = window.firstIndex { $0.chapterId == chapter.id && Int($0.index) == page } ?? 0
        slotIndex = Int(slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?.index ?? 0)
        if preferences.mode < 3 { pagedWindowNeedsRestore = true }
        preloadAdjacentChapters()
        if preferences.mode >= 3 { requestScroll(to: currentOrigin) }
    }

    func openBookmark(_ bookmark: IosBookmark) async -> Bool {
        guard let target = content.chapters.first(where: { $0.id == bookmark.chapterId }) else { return false }
        userScrolled()
        if loaded, let position = window.firstIndex(where: {
            $0.chapterId == bookmark.chapterId && $0.index == bookmark.index
        }) {
            selectWindowPage(position)
            jump(to: Int(bookmark.index), scroll: Float(bookmark.scroll))
            pagedWindowNeedsRestore = preferences.mode < 3
            return true
        }
        if chapter.id != target.id { await moveChapter(target) }
        guard loaded, chapter.id == target.id, pages.indices.contains(Int(bookmark.index)) else { return false }
        jump(to: Int(bookmark.index), scroll: Float(bookmark.scroll))
        return true
    }

    func movePage(forward: Bool) {
        let next = slotIndex + (forward ? 1 : -1)
        guard slots.indices.contains(next) else {
            if let target = forward ? nextChapter : previousChapter {
                Task { await moveChapter(target, atEnd: !forward) }
            }
            return
        }
        slotIndex = next; selectSlot(next)
    }

    func moveChapter(_ target: IosSourceChapter, atEnd: Bool = false, initialPage: Int? = nil) async {
        guard !isLoadingPages else { return }
        self.initialPage = initialPage
        let token = request
        isLoadingPages = true; error = nil; challenge = nil; failedChapter = nil
        defer {
            if request == token {
                isLoadingPages = false
                if pendingWindow { publishWindow() }
            }
        }
        let adopted = chapterPreloads[target.id]
        let prepared: [IosSourcePage]
        do {
            let early: [IosSourcePage]?
            if let cached = chapterPages[target.id] { early = cached }
            else { early = try? await adopted?.pages.value }
            try Task.checkCancellation()
            if let early { prepared = early }
            else { prepared = try await ReaderDownloads.pages(runtime: runtime, content: content, chapter: target) }
            guard !prepared.isEmpty else { throw URLError(.zeroByteResource) }
            try Task.checkCancellation()
            guard request == token else { return }
            await persist()
            try Task.checkCancellation()
            guard request == token else { return }
        } catch {
            if !Task.isCancelled && request == token {
                failedChapter = (target, atEnd)
                fail(error)
            }
            return
        }
        startAtEnd = atEnd; ignoresSavedPosition = true
        request = UUID(); chapter = target; loaded = false; sceneRevision += 1
        pendingWindow = false
        isUserPaging = false; isPageZoomed = false
        // Explicit jumps start a new scene; normal boundary paging promotes the existing window instead.
        for preload in chapterPreloads.values where preload !== adopted { preload.cancel() }
        chapterPreloads = adopted.map { [target.id: $0] } ?? [:]
        cancelLoads()
        fetches = [:]; decodedLimits = [:]; measuredPages = []
        preloadedPages = Task { prepared }
        pages = []; window = []; windowPages = []; slots = []; images = [:]; artifacts = [:]; pageFailures = [:]
        chapterPages = [target.id: prepared]
        index = 0; windowIndex = 0; slotIndex = 0; offset = 0; viewportOffset = .zero; viewAnchor = nil; engine.reset()
        stripPositionValid = false
        await load()
    }

    var hasVisiblePageFailure: Bool {
        if preferences.mode >= 3 {
            let bounds = CGRect(origin: viewportOffset, size: viewport)
            return pageFailures.keys.contains { page in
                guard slots.indices.contains(page), let placement = slots[page].placements.first else { return false }
                return bounds.intersects(CGRect(x: CGFloat(placement.x), y: CGFloat(placement.y),
                                                 width: CGFloat(placement.width), height: CGFloat(placement.height)))
            }
        }
        return slots.indices.contains(slotIndex) && slots[slotIndex].placements.contains {
            pageFailures[Int($0.index)] != nil
        }
    }

    var isReadyForAutoScroll: Bool {
        loaded && !isLoadingPages && (preferences.mode < 3 || !awaitingRestore) && slots.indices.contains(slotIndex) &&
            slots[slotIndex].placements.allSatisfy { images[Int($0.index)] != nil }
    }

    /// The next chapter's page list is on its way into the continuous strip (see [preloadAdjacentChapters]).
    var isLoadingNextChapter: Bool {
        guard preferences.mode >= 3, let next = nextChapter else { return false }
        return chapterPreloads[next.id] != nil && chapterPages[next.id] == nil
    }

    func advanceChapterAutomatically() async -> Bool {
        guard let next = nextChapter else { return false }
        await moveChapter(next)
        return loaded && chapter.id == next.id && error == nil
    }

    func persist() async {
        guard !incognito else { return }
        guard let library else { return }
        guard pages.indices.contains(index), images[windowIndex] != nil else {
            activity.pause()
            await activity.flush()
            return
        }
        let lastVisible = slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?
            .placements.compactMap { placement -> Int? in
                guard window.indices.contains(Int(placement.index)) else { return nil }
                let key = window[Int(placement.index)]
                return key.chapterId == chapter.id ? Int(key.index) : nil
            }.max() ?? index
        activity.update(library: library, chapter: chapter, index: index, count: pages.count,
                        lastVisible: max(index, lastVisible), scroll: offset)
        await activity.flush()
        do { try await library.recordPosition(content: content, chapter: chapter, index: Int32(index),
                                              count: Int32(pages.count), lastVisible: Int32(max(index, lastVisible)), scroll: offset) }
        catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }

    func refreshBookmarks() async {
        do { bookmarks = try await library?.bookmarks(content: content) ?? [] }
        catch { self.error = error.localizedDescription }
    }

    func toggleBookmark() async {
        guard !changingBookmark, let library, let page = selectedPage else { return }
        changingBookmark = true
        defer { changingBookmark = false }
        let markedChapter = chapter, markedIndex = index, pageCount = pages.count, markedScroll = offset
        let artifact = artifacts[windowIndex]
        let previous = bookmarks.first { $0.chapterId == markedChapter.id && Int($0.index) == markedIndex }
        do {
            try await library.toggleBookmark(content: content, chapter: markedChapter, page: page,
                                              index: Int32(markedIndex), count: Int32(pageCount), scroll: markedScroll)
            let updated = try await library.bookmarks(content: content)
            if let previous {
                try? await BookmarkImages.remove(content: content, bookmark: previous)
            } else if let saved = updated.first(where: {
                $0.chapterId == markedChapter.id && Int($0.index) == markedIndex
            }) {
                // Snapshot failure never discards the bookmark; its preview can rebuild through the source later.
                if let image = try? await artifact?.thumbnailImage() {
                    try? await BookmarkImages.save(image, content: content, bookmark: saved)
                } else if let image = try? await ReaderDownloads.thumbnailImage(
                    runtime: runtime, content: content, chapter: markedChapter, page: page, index: markedIndex
                ) {
                    try? await BookmarkImages.save(image, content: content, bookmark: saved)
                }
            }
            bookmarks = updated
        } catch { self.error = error.localizedDescription }
    }

    func removeBookmark(_ bookmark: IosBookmark) async {
        do {
            guard let library else { return }
            try await library.removeBookmark(content: content, bookmark: bookmark)
            try? await BookmarkImages.remove(content: content, bookmark: bookmark)
            await refreshBookmarks()
        }
        catch { self.error = error.localizedDescription }
    }

    private func fail(_ error: Error) {
        self.error = SourceErrorText.describe(error)
        challenge = SourceErrorText.challenge(error)
    }

    func retry() async {
        error = nil; challenge = nil
        if let failedChapter { await moveChapter(failedChapter.chapter, atEnd: failedChapter.atEnd) }
        else if loaded { await loadImage(at: windowIndex) } else { await load() }
    }

    func retryPage(at target: Int) async {
        guard window.indices.contains(target) else { return }
        let id = pageIdentity(window[target])
        pageFailures[target] = nil
        if pageLoads[id] == nil, let fetch = fetches[id] {
            fetch.task.cancel()
            fetches[id] = nil
        }
        await loadImage(at: target)
    }
}

struct ReaderPNG: FileDocument {
    static var readableContentTypes: [UTType] { [.png] }
    let data: Data
    init(image: UIImage) { data = image.pngData() ?? Data() }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct SourceReaderView: View {
    private let controlDiameter: CGFloat = 44
    @StateObject private var model: SourceReaderModel
    @StateObject private var settings: ReaderSettings
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var downloads: ReaderDownloadQueue
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Namespace private var chrome
    @State private var panel: ReaderPanel?
    @State private var controls = true
    @State private var export: ReaderPNG?
    @State private var exporting = false
    @State private var cropping = false
    @State private var sharing: SharedPage?
    @State private var croppedImage: UIImage?
    /// The paged scroll view's offset. Not view state: it changes every frame of a scroll.
    @State private var scroll = ReaderScrollTracker()
    @State private var downloadStatus: String?
    @State private var showingDownloads = false
    @State private var isReaderVisible = false
    @State private var savedIdleTimerDisabled: Bool?
    private var downloadEntry: ReaderDownloadEntry? { downloads.entry(content: model.content, chapter: model.chapter) }
    private var downloading: Bool { downloadEntry?.state == .running || downloadEntry?.state == .queued }
    private var currentDownloadStatus: String? {
        if let downloadStatus { return downloadStatus }
        if ReaderDownloads.isDownloaded(content: model.content, chapter: model.chapter) {
            return String(localized: "本章已下载，可离线阅读")
        }
        if downloadEntry?.state == .completed {
            return String(localized: "下载文件不完整，可重新下载本章")
        }
        return downloadEntry?.status
    }

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter, initialPage: Int? = nil,
         initialBookmark: IosBookmark? = nil, incognito: Bool = false) {
        let model = SourceReaderModel(runtime: runtime, content: content, chapter: chapter,
                                      initialPage: initialPage, initialBookmark: initialBookmark)
        model.incognito = incognito
        _model = StateObject(wrappedValue: model)
        _settings = StateObject(wrappedValue: ReaderSettings(contentID: content.id))
    }

    var body: some View {
        ZStack {
            // The pages fill the whole screen, under the status bar and the home indicator, as Aidoku's reader does
            // (`contentInsetAdjustmentBehavior = .never`); only the controls keep to the safe area. Showing or hiding
            // the controls (and the status bar with them) therefore never resizes or moves the pages.
            GeometryReader { geometry in
                readingSurface(size: geometry.size)
                    // Paged slots (`ReaderPageZoom`) and the strip (`ReaderStrip`) take their own taps, where a double
                    // tap must win over a single.
                    .gesture(SpatialTapGesture(coordinateSpace: .local).onEnded { tap(at: $0.location, size: geometry.size) },
                             including: settings.value.mode < 3 && model.slots.isEmpty ? .all : .subviews)
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .clipped()
                    .onChange(of: geometry.size, initial: true) { _, size in
                        model.configure(size: size, preferences: settings.value.layout(for: size,
                            wideWindow: horizontalSizeClass == .regular))
                    }
                    .onChange(of: settings.value) { _, value in
                        model.configure(size: geometry.size, preferences: value.layout(for: geometry.size,
                            wideWindow: horizontalSizeClass == .regular))
                    }
                    .onChange(of: horizontalSizeClass) { _, sizeClass in
                        model.configure(size: geometry.size, preferences: settings.value.layout(for: geometry.size,
                            wideWindow: sizeClass == .regular))
                    }
            }
            .ignoresSafeArea()
            GeometryReader { geometry in
            ZStack {
                if model.isLoadingPages { ProgressView("正在读取漫画页…").tint(.gray) }
                if let error = model.error {
                    VStack {
                        Text(error).foregroundStyle(.red).accessibilityIdentifier("reader.error")
                        if let challenge = model.challenge {
                            ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                                  sourceName: model.content.sourceName) { await model.retry() }
                        }
                        Button("重试") { Task { await model.retry() } }.buttonStyle(.glass)
                    }.padding().background(.regularMaterial, in: .rect(cornerRadius: 16))
                }
                if model.loaded && model.pages.isEmpty && model.error == nil {
                    ContentUnavailableView("暂无漫画页", systemImage: "doc").foregroundStyle(.gray)
                }
                if controls { chromeView(height: geometry.size.height) }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            }
        }
        .background(settings.background.ignoresSafeArea())
        .background(EdgeSwipeBack())
        .toolbar(.hidden, for: .navigationBar, .tabBar)
        .statusBarHidden(!controls)
        .onAppear { isReaderVisible = true; model.setReadingActive(scenePhase == .active); updateIdleTimer() }
        .onChange(of: settings.value.keepScreenOn) { _, _ in updateIdleTimer() }
        .onChange(of: scenePhase) { previous, phase in
            model.setReadingActive(phase == .active && isReaderVisible)
            updateIdleTimer()
            if phase == .active {
                Task { await model.load(); await model.loadImage(at: model.windowIndex); await model.persist() }
            } else if previous == .active {
                model.autoScroll = false
                model.stopPreloading()
                // Give the already-authorized position write a chance to finish before iOS suspends the app.
                let save = ReaderBackgroundSave()
                save.begin()
                Task {
                    defer { save.end() }
                    await model.persist()
                }
            }
        }
        .task {
            // A reader shown again keeps its model: it continues where it is rather than where this model began.
            let shownAgain = model.loaded
            model.library = library.library; await model.load()
            if shownAgain && settings.value.mode >= 3 { model.stripAttached() }
        }
        .task(id: "\(model.windowRevision)-\(model.windowIndex)") {
            await model.loadImage(at: model.windowIndex)
            if !model.isStripMoving { await model.persist() }
        }
        .task(id: model.autoScroll) {
            // The strip moves once per screen frame by the time that frame took, not by a fixed 16 ms per sleep.
            let clock = ReaderFrameClock()
            defer { clock.stop() }
            var sinceSave: CFTimeInterval = 0
            var landedOnEdge = false
            while model.autoScroll && !Task.isCancelled {
                if scenePhase != .active || model.error != nil || model.hasVisiblePageFailure ||
                    (model.loaded && model.pages.isEmpty) {
                    model.autoScroll = false; break
                }
                if !model.isReadyForAutoScroll {
                    _ = await clock.nextFrame()
                    continue
                }
                if settings.value.mode >= 3 {
                    // A frame after a stall (or the first one) moves no further than a few frames would have.
                    let frame = min(await clock.nextFrame(), 0.05)
                    guard !Task.isCancelled, model.autoScroll else { break }
                    // A window rebuilt during the wait moves the strip: wait for its restore, not the old offset.
                    guard model.isReadyForAutoScroll else { continue }
                    let current = settings.value.mode == 3 ? model.stripOffset.y : model.stripOffset.x
                    let direction = settings.value.continuousForwardSign
                    // The shared distance is linear in time: its one-second distance scaled to this frame.
                    let perSecond = CGFloat(model.engine.autoScrollDistance(speed: Float(settings.value.autoSpeed),
                                                                             elapsedMillis: 1000))
                    let next = current + direction * perSecond * CGFloat(frame)
                    let range = model.continuousScrollRange
                    if !range.contains(next) {
                        // Land on the edge first: the strip's end reads its last visible page (and its chapter).
                        let edge = direction > 0 ? range.upperBound : range.lowerBound
                        if abs(current - edge) > 0.5 && !landedOnEdge {
                            landedOnEdge = true; model.scrollStrip(to: edge, animated: false); continue
                        }
                        // The next chapter joins the strip once its pages arrive; the scroll then simply continues.
                        if model.isLoadingNextChapter { continue }
                        if await model.advanceChapterAutomatically() { sinceSave = 0; continue }
                        model.autoScroll = false; await model.persist(); break
                    }
                    landedOnEdge = false
                    model.scrollStrip(to: next, animated: false)
                    sinceSave += frame
                    if sinceSave >= 5 { sinceSave = 0; await model.persist() }
                    continue
                }
                try? await Task.sleep(for: .milliseconds(Int64(model.engine.autoScrollDelay(speed: Float(settings.value.autoSpeed)))))
                guard !Task.isCancelled && model.autoScroll else { break }
                if model.slotIndex + 1 < model.slots.count {
                    withAnimation(pageAnimation) { model.movePage(forward: true) }
                } else if let next = model.nextChapter { await model.moveChapter(next) }
                else { model.autoScroll = false }
            }
        }
        .onDisappear {
            isReaderVisible = false
            model.setReadingActive(false)
            updateIdleTimer()
            model.autoScroll = false
            model.stopPreloading()
            Task {
                await model.persist()
                await model.finishReadingActivity()
                await library.refresh()
                await ReaderTileBaseCache.shared.clear()
                if !model.incognito {
                    await library.trackingAccounts.syncAfterReading(model.content, tracking: library.library?.tracking)
                }
            }
        }
        .overlay(alignment: .top) { ReaderTranslationHost(translator: model.translator) }
        .readerPresence(directories: model.storageDirectories)
        .sheet(isPresented: $showingDownloads) { ReaderDownloadsSheet() }
        .fileExporter(isPresented: $exporting, document: export, contentType: .png,
                      defaultFilename: "\(model.content.title)-\(model.index + 1)") { result in
            if case .failure(let error) = result { downloadStatus = error.localizedDescription }
        }
        .sheet(item: $sharing) { page in
            ActivitySheet(items: [page.image, String(localized: "\(model.content.title) · 第 \(model.index + 1) 页")])
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $cropping, onDismiss: {
            if let image = croppedImage { croppedImage = nil; save(image) }
        }) {
            if let image = model.image {
                ReaderCropView(image: image, onSave: { croppedImage = $0 }, onNote: { crop, rect, note in
                    Task { downloadStatus = await model.saveCropNote(crop, rect: rect, note: note) }
                })
            }
        }
    }

    /// Android's ADVANCED and SIMULATION turns (stored as 4 and 5) need a pager that owns its travel
    /// (see `ReaderTurnPager`); the value passed on is Android's `ReaderAnimation` ordinal.
    private var turnStyle: Int? {
        let style = settings.value.animation
        return settings.value.mode < 3 && !reduceMotion && (style == 4 || style == 5) ? style - 2 : nil
    }

    @ViewBuilder
    private func readingSurface(size: CGSize) -> some View {
        let sceneRevision = model.sceneRevision
        if let style = turnStyle {
            ReaderTurnPager(
                count: model.slots.count,
                revision: model.windowRevision,
                current: Binding(get: { model.slotIndex }, set: {
                    if model.sceneRevision == sceneRevision { model.slotIndex = $0 }
                }),
                style: style, vertical: settings.value.mode == 2, mirrored: settings.value.mode == 1,
                engine: model.engine, size: size,
                isLocked: { model.isPageZoomed },
                onDragStart: { model.userScrolled(); model.isUserPaging = true },
                onSettle: { slot in
                    if model.sceneRevision == sceneRevision {
                        model.selectSlot(slot); model.isUserPaging = false
                    }
                }
            ) { slot in
                if let spread = model.slots.first(where: { Int($0.index) == slot }) {
                    // Opaque like Android's slot background: the next page waits pinned underneath.
                    ReaderSpread(model: model, settings: settings, slot: spread, size: size) { tap(at: $0, size: size) }
                        .id(model.slotIdentity(spread))
                        .background(settings.background)
                }
            }
            .id("\(settings.value.mode)-\(sceneRevision)")
            .accessibilityValue(model.readingPositionDescription)
        } else {
            scrollingSurface(size: size)
        }
    }

    @ViewBuilder
    private func scrollingSurface(size: CGSize) -> some View {
        if settings.value.mode >= 3 {
            // Continuous strips are a UIKit scroll view laid out from the shared scene, as Android's webtoon draws the
            // scene itself: one coordinate system, window changes and size corrections applied in the same turn.
            ReaderStrip(model: model, settings: settings) { tap(at: $0, size: size) }
                .id("\(settings.value.mode)-\(model.sceneRevision)")
        } else if model.loaded {
            // Android creates its pager with the reading page as `initialPage` once the pages are known (Aidoku sets
            // its page view controller to the reading page). A paged scroll view built before that would start on the
            // first slot and have to be moved there afterwards, which a lazy stack that has not laid out yet drops.
            scrollView(size: size)
        }
    }

    @ViewBuilder
    private func scrollView(size: CGSize) -> some View {
        let vertical = settings.value.mode == 2
        let windowRevision = model.windowRevision
        let sceneRevision = model.sceneRevision
        let mode = settings.value.mode
        ScrollViewReader { proxy in
        ScrollView(vertical ? .vertical : .horizontal) {
            if vertical {
                LazyVStack(spacing: 0) { slots(size: size) }.scrollTargetLayout()
            } else {
                LazyHStack(spacing: 0) { slots(size: size) }.scrollTargetLayout()
            }
        }
        .scrollIndicators(.hidden)
        .accessibilityIdentifier("reader.canvas")
        .accessibilityValue(model.readingPositionDescription)
        .scrollBounceBehavior(.always, axes: vertical ? .vertical : .horizontal)
        .scrollTargetBehavior(.viewAligned)
        // Paged scroll views are only moved through the proxy and only report the slot they settled on: a position
        // binding is a second writer that moves the pages under the finger and re-applies stale targets after the
        // window is rebuilt around a new chapter.
        // All modes use physical scroll coordinates; RTL reverses slot order rather than the scroll coordinate system.
        .environment(\.layoutDirection, .leftToRight)
        .id("\(mode)-\(model.sceneRevision)")
        .onScrollGeometryChange(for: CGPoint.self) {
            CGPoint(x: $0.contentOffset.x + $0.contentInsets.leading, y: $0.contentOffset.y + $0.contentInsets.top)
        }
        action: { _, point in
            guard model.windowRevision == windowRevision, model.sceneRevision == sceneRevision,
                  settings.value.mode == mode else { return }
            scroll.pagedOffset = point
            if model.pagedWindowNeedsRestore, !model.isUserPaging, !model.slots.isEmpty {
                let extent = vertical ? size.height : size.width
                let physical = settings.value.readsRightToLeft ? model.slots.count - 1 - model.slotIndex : model.slotIndex
                let target = CGFloat(physical) * extent
                if abs((vertical ? point.y : point.x) - target) < 2 {
                    model.pagedWindowNeedsRestore = false
                } else {
                    scrollToReadingSlot(proxy)
                }
            }
        }
        .onScrollPhaseChange { previous, phase, context in
            guard model.windowRevision == windowRevision, model.sceneRevision == sceneRevision,
                  settings.value.mode == mode else { return }
            model.scrollPhase = phase
            if phase == .interacting {
                model.pagedWindowNeedsRestore = false
                model.userScrolled()
                // A drag belongs to the window it started on; one carried over a rebuild reports nothing.
                model.pagingWindowRevision = model.windowRevision
            }
            switch phase {
            case .tracking: break
            case .interacting, .decelerating: model.isUserPaging = true
            case .idle:
                settlePaging(context.geometry, vertical: vertical, size: size, proxy: proxy)
            case .animating:
                // The settling report can arrive in the same update as the phase change.
                Task { @MainActor in
                    await Task.yield()
                    if model.windowRevision == windowRevision, model.sceneRevision == sceneRevision {
                        model.isUserPaging = false
                    }
                }
            @unknown default: break
            }
            if phase == .idle && !model.autoScroll { Task { await model.persist() } }
        }
        .onChange(of: model.windowRevision) { _, _ in
            // The rebuilt window keeps the old pixel offset, which now shows another page: move to the reading slot
            // before anything reads the offset (the restore in the geometry action repeats it if needed).
            if !model.isUserPaging { scrollToReadingSlot(proxy) }
            Task { @MainActor in
                await Task.yield()
                if !model.isUserPaging { scrollToReadingSlot(proxy) }
            }
        }
        .onChange(of: model.slotIndex) { previous, current in
            guard !model.isUserPaging else { return }
            // Taps, keys and auto-scroll turn one page with the page animation; the slider and restores jump.
            let animation = abs(current - previous) == 1 ? pageAnimation : nil
            withAnimation(animation) { scrollToReadingSlot(proxy) }
        }
        .onChange(of: size) { _, _ in
            guard !model.isUserPaging else { return }
            scrollToReadingSlot(proxy)
            Task { @MainActor in
                await Task.yield()
                if !model.isUserPaging { scrollToReadingSlot(proxy) }
            }
        }
        // As Android's pager applies its initial page once the pages are laid out, it is moved there once after the
        // first layout. Recreated when the reading mode or page-turn style changes, it keeps the page being read.
        .onAppear {
            Task { @MainActor in
                await Task.yield()
                if !model.isUserPaging { scrollToReadingSlot(proxy) }
            }
        }
        }
    }

    @ViewBuilder private func slots(size: CGSize) -> some View {
        if settings.value.readsRightToLeft {
            slotViews(model.identifiedSlots.reversed(), size: size)
        } else {
            slotViews(model.identifiedSlots, size: size)
        }
    }

    @ViewBuilder private func slotViews<Slots: RandomAccessCollection>(
        _ ordered: Slots, size: CGSize
    ) -> some View where Slots.Element == ReaderWindowSlot {
        ForEach(ordered) { item in
            let animation = settings.value.animation
            let mode = settings.value.mode
            ReaderSpread(model: model, settings: settings, slot: item.slot, size: size) { tap(at: $0, size: size) }
                .modifier(ReaderPageTransition(animation: animation, vertical: mode == 2, reduceMotion: reduceMotion))
                .id(item.id)
        }
    }

    private func chromeView(height: CGFloat) -> some View {
        GlassEffectContainer(spacing: 8) {
            VStack(spacing: 12) {
                if !settings.value.bottomPanels { topChrome }
                Spacer(minLength: 0)
                if settings.value.bottomPanels { topChrome }
                if !floatingActions.isEmpty {
                    HStack(spacing: 12) {
                        Spacer()
                        ForEach(floatingActions, id: \.self) { action in
                            Button {
                                if action == "auto" { model.autoScroll.toggle() } else { moreAction(action) }
                            } label: {
                                Image(systemName: action == "bookmark" && model.isBookmarked ? "bookmark.fill" : ReaderMorePanel.actionSymbol(action))
                                    .frame(width: controlDiameter, height: controlDiameter)
                            }
                                .buttonStyle(.plain)
                                .glassEffect(.regular.tint(.accentColor).interactive(), in: .circle)
                                .accessibilityLabel(ReaderMorePanel.actionTitle(action))
                                .accessibilityIdentifier("reader.\(action)")
                                .accessibilityValue(action == "bookmark" ? (model.isBookmarked ? "已添加" : "未添加") : "")
                        }
                    }
                }
                progressBar
            }
            .padding(12)
            .foregroundStyle(.primary)
        }
    }

    private var progressBar: some View {
        HStack(spacing: 20) {
            Button("上一章", systemImage: "backward.end.fill") {
                if let chapter = model.previousChapter { Task { await model.moveChapter(chapter) } }
            }
            .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
            .frame(width: controlDiameter, height: controlDiameter)
            .disabled(model.previousChapter == nil || model.isLoadingPages)
            .accessibilityIdentifier("reader.previousChapter")
            HStack(spacing: 8) {
                Text("\(model.pages.isEmpty ? 0 : model.index + 1)")
                    .font(.caption.monospacedDigit()).fixedSize()
                    .accessibilityLabel("当前页码")
                    .accessibilityValue("\(model.pages.isEmpty ? 0 : model.index + 1)")
                    .accessibilityIdentifier("reader.currentPage")
                Slider(value: Binding(
                    get: { Double(model.index) },
                    set: { value in
                        model.userScrolled()
                        model.jump(to: Int(value.rounded()))
                    }), in: 0...Double(max(1, model.pages.count - 1)), step: 1,
                    onEditingChanged: { editing in if !editing { Task { await model.persist() } } })
                    .disabled(model.pages.count <= 1 || model.isLoadingPages)
                    .accessibilityLabel("阅读进度")
                    .accessibilityValue("第 \(model.pages.isEmpty ? 0 : model.index + 1) / \(model.pages.count) 页")
                    .accessibilityIdentifier("reader.progress")
                Text("\(model.pages.count)")
                    .font(.caption.monospacedDigit()).fixedSize()
                    .accessibilityLabel("总页码")
                    .accessibilityValue("\(model.pages.count)")
                    .accessibilityIdentifier("reader.totalPages")
            }
            .padding(.horizontal, 14)
            .frame(height: controlDiameter)
            .glassEffect(.regular, in: .capsule)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("reader.progressControl")
            Button("下一章", systemImage: "forward.end.fill") {
                if let chapter = model.nextChapter { Task { await model.moveChapter(chapter) } }
            }
            .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
            .frame(width: controlDiameter, height: controlDiameter)
            .disabled(model.nextChapter == nil || model.isLoadingPages)
            .accessibilityIdentifier("reader.nextChapter")
        }
        .font(.subheadline)
    }

    @ViewBuilder private var topChrome: some View {
        if let panel {
            VStack(spacing: 0) {
                HStack {
                    Text(panel == .more ? String(localized: "更多") : (panel == .thumbnails ? String(localized: "缩略图") : model.chapter.title)).font(.headline).lineLimit(1)
                    Spacer()
                    Button("收起", systemImage: "xmark") { withAnimation(chromeAnimation) { self.panel = nil } }
                        .labelStyle(.iconOnly).buttonStyle(.borderless).accessibilityIdentifier("reader.panel.close")
                }.padding(16)
                Divider()
                if panel == .chapters || panel == .thumbnails {
                    ReaderChapterPanel(
                        model: model,
                        initialTab: panel == .thumbnails ? 1 : 0,
                        onJump: { page in model.jump(to: page); closePanel() },
                        onChapter: { chapter in Task {
                            await model.moveChapter(chapter); closePanel()
                        } },
                        onBookmark: { bookmark in Task {
                            if await model.openBookmark(bookmark) { closePanel() }
                        } },
                        onSelectPage: { chapter, page in
                            if chapter.id == model.chapter.id {
                                model.jump(to: page)
                                closePanel()
                            } else {
                                Task {
                                    await model.moveChapter(chapter, initialPage: page)
                                    closePanel()
                                }
                            }
                        }
                    )
                } else {
                    ReaderMorePanel(settings: settings, autoScroll: $model.autoScroll,
                                    isBookmarked: model.isBookmarked,
                                    translating: model.translator.enabled,
                                    status: currentDownloadStatus, downloading: downloading,
                                    onAction: moreAction)
                }
            }
            .frame(maxHeight: 540)
            .glassEffect(.regular, in: .rect(cornerRadius: 28))
            .glassEffectID(panel == .more ? "more" : (panel == .thumbnails ? "thumbnails" : "chapters"), in: chrome)
            .glassEffectTransition(.matchedGeometry)
            // Grow out of the control that opened the panel: the centred chapter capsule or the trailing more button.
            .transition(.opacity.combined(with: .scale(scale: 0.96, anchor: panelAnchor(panel))))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(panel == .more ? "reader.morePanel" : (panel == .thumbnails ? "reader.thumbnailsPanel" : "reader.chapterPanel"))
        } else {
            HStack {
                Button("返回", systemImage: "chevron.left") { dismiss() }
                    .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
                    .frame(width: controlDiameter, height: controlDiameter).accessibilityIdentifier("reader.back")
                // Wider than the glass container's merge spacing: neighbouring controls must stay separate shapes,
                // or the panel morphs out of their union instead of out of the button that was tapped.
                Spacer(minLength: 16)
                Button { withAnimation(chromeAnimation) { panel = .chapters } } label: {
                    Label(model.chapter.title, systemImage: "list.bullet").font(.subheadline).lineLimit(1)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                }
                .buttonStyle(.plain).glassEffect(.regular.interactive(), in: .capsule)
                .glassEffectID("chapters", in: chrome).accessibilityIdentifier("reader.chapters")
                Spacer(minLength: 16)
                Button { withAnimation(chromeAnimation) { panel = .more } } label: {
                    Image(systemName: "ellipsis").frame(width: controlDiameter, height: controlDiameter)
                }.buttonStyle(.plain).glassEffect(.regular.interactive(), in: .circle)
                    .glassEffectID("more", in: chrome).accessibilityIdentifier("reader.more")
            }
        }
    }

    private func closePanel() { withAnimation(chromeAnimation) { panel = nil } }

    private func scrollToReadingSlot(_ proxy: ScrollViewProxy) {
        guard let slot = model.slots.first(where: { Int($0.index) == model.slotIndex }) else { return }
        proxy.scrollTo(model.slotIdentity(slot), anchor: .topLeading)
    }

    /// A paged scroll came to rest. SwiftUI can report idle while the native paging snap is still finishing, so the
    /// settled slot is committed (and a deferred window rebuilt) only once the offset has stayed on a page boundary;
    /// a drag that began before the current window was built never commits.
    private func settlePaging(_ geometry: ScrollGeometry, vertical: Bool, size: CGSize, proxy: ScrollViewProxy) {
        let windowRevision = model.windowRevision, sceneRevision = model.sceneRevision
        let extent = vertical ? size.height : size.width
        Task { @MainActor in
            var last = CGFloat.nan
            for _ in 0..<30 {
                try? await Task.sleep(for: .milliseconds(16))
                guard model.scrollPhase == .idle, model.windowRevision == windowRevision,
                      model.sceneRevision == sceneRevision else { return }
                let offset = vertical ? scroll.pagedOffset.y : scroll.pagedOffset.x
                let aligned = extent > 0 && abs(offset / extent - (offset / extent).rounded()) * extent < 1
                if aligned && offset == last { break }
                last = offset
            }
            guard model.scrollPhase == .idle, model.windowRevision == windowRevision,
                  model.sceneRevision == sceneRevision else { return }
            if model.isUserPaging && model.pagingWindowRevision == windowRevision {
                reportPagedOffset(last.isNaN ? 0 : last, extent: extent)
            }
            model.pagingWindowRevision = nil
            model.isUserPaging = false
            if model.pagedWindowNeedsRestore || model.windowRevision != windowRevision { scrollToReadingSlot(proxy) }
        }
    }

    private func reportPagedOffset(_ offset: CGFloat, extent: CGFloat) {
        guard extent > 0, offset.isFinite, !model.slots.isEmpty else { return }
        let physical = min(max(0, Int((offset / extent).rounded())), model.slots.count - 1)
        let selected = settings.value.readsRightToLeft ? model.slots.count - 1 - physical : physical
        model.scrolledToSlot(selected)
    }

    private func updateIdleTimer() {
        let enabled = isReaderVisible && scenePhase == .active && settings.value.keepScreenOn
        let app = UIApplication.shared
        if enabled {
            if savedIdleTimerDisabled == nil { savedIdleTimerDisabled = app.isIdleTimerDisabled }
            app.isIdleTimerDisabled = true
        } else if let previous = savedIdleTimerDisabled {
            app.isIdleTimerDisabled = previous
            savedIdleTimerDisabled = nil
        }
    }

    private func panelAnchor(_ panel: ReaderPanel) -> UnitPoint {
        let bottom = settings.value.bottomPanels
        return panel == .more ? (bottom ? .bottomTrailing : .topTrailing) : (bottom ? .bottom : .top)
    }

    /// Android-style tap grid: edges turn pages (mirrored for right-to-left), the centre toggles the controls.
    private func tap(at location: CGPoint, size: CGSize) {
        if panel != nil { closePanel(); return }
        guard settings.value.tapZones, size.width > 0, size.height > 0 else {
            withAnimation(chromeAnimation) { controls.toggle() }; return
        }
        let x = location.x / size.width, y = location.y / size.height
        var forward: Bool?
        if x < 0.3 { forward = false } else if x > 0.7 { forward = true }
        else if y < 0.2 { forward = false } else if y > 0.8 { forward = true }
        guard var direction = forward else { withAnimation(chromeAnimation) { controls.toggle() }; return }
        let rtl = settings.value.readsRightToLeft
        if rtl && x < 0.3 || rtl && x > 0.7 { direction.toggle() }
        if settings.value.reverseTaps { direction.toggle() }
        if controls { withAnimation(chromeAnimation) { controls = false } }
        turn(forward: direction, size: size)
    }

    private func turn(forward: Bool, size: CGSize) {
        model.userScrolled()
        if settings.value.mode >= 3 {
            let vertical = settings.value.mode == 3
            let current = vertical ? model.stripOffset.y : model.stripOffset.x
            let direction = settings.value.continuousForwardSign
            let step = (vertical ? size.height : size.width) * 0.85 * (forward ? 1 : -1) * direction
            let range = model.continuousScrollRange
            let lower = range.lowerBound, upper = range.upperBound
            if forward && (direction > 0 ? current >= upper - 1 : current <= lower + 1), let next = model.nextChapter {
                if !model.isLoadingNextChapter { Task { await model.moveChapter(next) } }
                return
            }
            if !forward && (direction > 0 ? current <= lower + 1 : current >= upper - 1),
               let previous = model.previousChapter {
                Task { await model.moveChapter(previous, atEnd: true) }; return
            }
            model.scrollStrip(to: min(max(lower, current + step), upper), animated: !reduceMotion)
            return
        }
        let target = model.slotIndex + (forward ? 1 : -1)
        if model.slots.indices.contains(target) {
            withAnimation(pageAnimation) { model.movePage(forward: forward) }
        } else if let chapter = forward ? model.nextChapter : model.previousChapter {
            Task { await model.moveChapter(chapter, atEnd: !forward) }
        }
    }
    private var chromeAnimation: Animation? { reduceMotion ? nil : .spring }
    private var pageAnimation: Animation? {
        reduceMotion || settings.value.animation == 0 ? nil : .snappy(duration: 0.22)
    }
    private var floatingActions: [String] {
        var actions = settings.value.floatingActions.filter { $0 != "bookmark" }
        if settings.value.bookmarkButton { actions.append("bookmark") }
        return Array(actions.suffix(4))
    }
    private func save(_ image: UIImage) { export = ReaderPNG(image: image); exporting = true }
    private func moreAction(_ action: String) {
        switch action {
        case "chapters": withAnimation(chromeAnimation) { panel = .chapters }
        case "thumbnails": withAnimation(chromeAnimation) { panel = .thumbnails }
        case "bookmark": Task { await model.toggleBookmark() }
        case "save": if let image = model.image { closePanel(); save(image) }
        case "share": if let image = model.image { closePanel(); sharing = SharedPage(image: image) }
        case "crop": if model.image != nil { closePanel(); cropping = true }
        case "translate": closePanel(); model.toggleTranslation()
        case "direction":
            if settings.value.mode == 4 { settings.value.horizontalReversed.toggle() }
            else { settings.value.mode = settings.value.mode == 1 ? 0 : 1 }
        case "browser": if let url = URL(string: model.content.publicUrl) { UIApplication.shared.open(url) }
        case "downloads": showingDownloads = true
        case "download":
            guard !downloading else { return }
            do { try downloads.enqueue(content: model.content, chapters: [model.chapter]); downloadStatus = nil }
            catch { downloadStatus = error.localizedDescription }
        default: break
        }
    }
}

enum ReaderPanel: Equatable { case chapters, thumbnails, more }

private final class ReaderScrollTracker {
    /// The paged scroll view's physical offset.
    var pagedOffset = CGPoint.zero
}

/// A short position write owns its background-time lease, including the system's expiration path.
@MainActor
private final class ReaderBackgroundSave {
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    func begin() {
        identifier = UIApplication.shared.beginBackgroundTask(withName: "reader-save-position") { [weak self] in
            self?.end()
        }
    }

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}

/// Hiding the navigation bar also turns off the navigation controller's left-edge swipe back. While the reader
/// is on screen this restores that edge gesture (not the full-width content swipe, which would fight page turns),
/// and hands the previous delegate back when it leaves. Only touches that land in a narrow band at the leading edge
/// reach it, so a broad thumb further in never holds the reader's drags waiting for the swipe back to fail.
struct EdgeSwipeBack: UIViewControllerRepresentable {
    static let band: CGFloat = 20

    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {}

    final class Controller: UIViewController, UIGestureRecognizerDelegate {
        private weak var previous: UIGestureRecognizerDelegate?
        private weak var recognizer: UIGestureRecognizer?
        /// SwiftUI turns the gesture off again while its bar is hidden; keep it on while the reader shows.
        private var enabledObservation: NSKeyValueObservation?

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard let gesture = navigationController?.interactivePopGestureRecognizer, gesture.delegate !== self else { return }
            previous = gesture.delegate
            recognizer = gesture
            gesture.delegate = self
            gesture.isEnabled = true
            enabledObservation = gesture.observe(\.isEnabled, options: [.new]) { gesture, change in
                if change.newValue == false { DispatchQueue.main.async { gesture.isEnabled = true } }
            }
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            enabledObservation = nil
            if let recognizer, recognizer.delegate === self { recognizer.delegate = previous }
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            (navigationController?.viewControllers.count ?? 0) > 1
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard let window = touch.window else { return false }
            let x = touch.location(in: window).x
            return window.effectiveUserInterfaceLayoutDirection == .rightToLeft
                ? x >= window.bounds.width - EdgeSwipeBack.band
                : x <= EdgeSwipeBack.band
        }

        /// A drag that starts in the edge band waits for the swipe back, so a page turn cannot take it first. Touches
        /// elsewhere never reach the swipe back, and the reader's gestures start without waiting.
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool { other !== gestureRecognizer }
    }
}

private struct ReaderPageTransition: ViewModifier {
    let animation: Int
    let vertical: Bool
    let reduceMotion: Bool

    func body(content: Content) -> some View {
        if !reduceMotion && (animation == 2 || animation == 3) {
            content.scrollTransition(.interactive, axis: vertical ? .vertical : .horizontal) { content, phase in
                content.opacity(animation == 2 && !phase.isIdentity ? 0 : 1)
                    .scaleEffect(animation == 3 && !phase.isIdentity ? 0.85 : 1)
            }
        } else {
            content
        }
    }
}

struct ReaderPageTile: View {
    /// Not observed: the page renders again for its own [page] state, not for every change of the reader.
    let model: SourceReaderModel
    @ObservedObject private var page: ReaderPageState
    @ObservedObject var settings: ReaderSettings
    @Environment(\.displayScale) private var displayScale
    let index: Int
    var segment = 0
    var displaySize = CGSize.zero
    var viewportOffset = CGPoint.zero
    var zoomScale: CGFloat = 1

    init(model: SourceReaderModel, settings: ReaderSettings, index: Int, segment: Int = 0,
         displaySize: CGSize = .zero, viewportOffset: CGPoint = .zero, zoomScale: CGFloat = 1) {
        self.model = model
        _page = ObservedObject(wrappedValue: model.pageState(at: index))
        self.settings = settings
        self.index = index
        self.segment = segment
        self.displaySize = displaySize
        self.viewportOffset = viewportOffset
        self.zoomScale = zoomScale
    }

    private var displayImage: UIImage? {
        guard let base = page.image else { return nil }
        return segment == 1 || segment == 2 ? SourceImageFile.splitPage(base, segment: segment) : base
    }

    var body: some View {
        Group {
            if let image = displayImage {
                ZStack(alignment: .topLeading) {
                    Image(uiImage: image).resizable().scaledToFit()
                        .frame(width: displaySize.width > 0 ? displaySize.width : nil,
                               height: displaySize.height > 0 ? displaySize.height : nil)
                    if segment == 0, let artifact = page.artifact, artifact.tiled,
                       displaySize.width > 1, displaySize.height > 1 {
                        let tiles = model.engine.visibleTiles(
                            x: Float(viewportOffset.x),
                            y: Float(viewportOffset.y),
                            index: Int32(index),
                            encodedWidth: Int32(artifact.encodedWidth),
                            encodedHeight: Int32(artifact.encodedHeight),
                            displayScale: Float(displayScale),
                            zoomScale: Float(max(1, zoomScale))
                        )
                        if !tiles.isEmpty {
                            ReaderTiledOverlay(artifact: artifact, tiles: tiles, displaySize: displaySize)
                        }
                    }
                }
                .brightness(settings.value.brightness).contrast(settings.value.contrast)
                .saturation(settings.value.grayscale ? 0 : 1)
                .modifier(ReaderInvert(enabled: settings.value.inverted))
                .shadow(color: .black.opacity(settings.value.bookEffect ? 0.4 : 0), radius: 8)
                .accessibilityIdentifier("reader.image")
                .accessibilityLabel(page.description)
            } else if let failure = page.failure {
                VStack(spacing: 12) {
                    Text(failure.message).foregroundStyle(.red).accessibilityIdentifier("reader.pageError.\(index)")
                    if let challenge = failure.challenge {
                        ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                              sourceName: model.content.sourceName) { await model.retryPage(at: index) }
                    }
                    Button("重试") { Task { await model.retryPage(at: index) } }
                        .buttonStyle(.glass).accessibilityIdentifier("reader.pageRetry.\(index)")
                }.padding()
            } else { ProgressView().tint(.gray) }
        }
        .task(id: "\(page.revision)-\(index)") { await model.loadImage(at: index) }
    }
}

/// Subsampling tiles over a page's base image, like Android's SSIV: the base is the whole page decoded within the
/// pixel budget, and the visible region is decoded again at the sample size the current camera needs. Tiles of the
/// previous camera stay drawn (coarser ones underneath) until the new set is ready, so zooming and panning never fall
/// back to the blurred base in between.
private struct ReaderTiledOverlay: View {
    let artifact: ReaderImageArtifact
    let tiles: [IosReaderTile]
    let displaySize: CGSize
    @State private var decodedTiles: [String: DecodedTile] = [:]

    private struct DecodedTile {
        let tile: IosReaderTile
        let image: UIImage
    }

    private static func key(_ tile: IosReaderTile) -> String {
        "\(tile.decodeLeft):\(tile.decodeTop):\(tile.decodeWidth):\(tile.decodeHeight):\(tile.sampleSize)"
    }

    private var tileSignature: String {
        tiles.map(Self.key).joined(separator: "|")
    }

    var body: some View {
        // Coarse samples first, so the finer tiles of the current camera draw on top.
        let drawn = decodedTiles.sorted {
            $0.value.tile.sampleSize != $1.value.tile.sampleSize
                ? $0.value.tile.sampleSize > $1.value.tile.sampleSize : $0.key < $1.key
        }
        ZStack(alignment: .topLeading) {
            ForEach(drawn, id: \.key) { _, decoded in
                let tile = decoded.tile
                let layout = SourceImageFile.tileLayout(
                    logical: CGRect(x: Int(tile.logicalLeft), y: Int(tile.logicalTop),
                                    width: Int(tile.logicalWidth), height: Int(tile.logicalHeight)),
                    decoded: CGRect(x: Int(tile.decodeLeft), y: Int(tile.decodeTop),
                                    width: Int(tile.decodeWidth), height: Int(tile.decodeHeight)),
                    sourceSize: CGSize(width: artifact.encodedWidth, height: artifact.encodedHeight),
                    displaySize: displaySize
                )
                Image(uiImage: decoded.image).resizable()
                    .frame(width: layout.decodedSize.width, height: layout.decodedSize.height)
                    .offset(layout.decodedOffset)
                    .frame(width: layout.frame.width, height: layout.frame.height, alignment: .topLeading)
                    .clipped()
                    .offset(x: layout.frame.minX, y: layout.frame.minY)
            }
        }
        .allowsHitTesting(false)
        .task(id: tileSignature) {
            let activeKeys = Set(tiles.map(Self.key))
            // Bounded: a long pan leaves at most one screen of stale tiles behind.
            if decodedTiles.count > activeKeys.count * 2 {
                decodedTiles = decodedTiles.filter { activeKeys.contains($0.key) }
            }
            for tile in tiles {
                guard !Task.isCancelled else { return }
                let key = Self.key(tile)
                guard decodedTiles[key] == nil else { continue }
                if let image = try? await artifact.decodeTile(tile) {
                    guard !Task.isCancelled else { return }
                    decodedTiles[key] = DecodedTile(tile: tile, image: image)
                }
            }
            guard !Task.isCancelled else { return }
            decodedTiles = decodedTiles.filter { activeKeys.contains($0.key) }
        }
    }
}

private struct ReaderInvert: ViewModifier {
    let enabled: Bool
    func body(content: Content) -> some View { if enabled { content.colorInvert() } else { content } }
}

/// One paged slot. Zoom and pan are a native `UIScrollView` ([ReaderPageZoom]), like Android's Telephoto page: pinch
/// and double-tap zoom about the touch, panning decelerates and bounces natively, and a zoomed page at its edge hands
/// the drag on to the pager. Tiles follow the settled camera only, so a pan does not resample every frame.
private struct ReaderSpread: View {
    /// Not observed: each page view follows its own page state (see `ReaderSpreadPages`).
    let model: SourceReaderModel
    @ObservedObject var settings: ReaderSettings
    let slot: IosReaderSlot
    let size: CGSize
    let onTap: (CGPoint) -> Void
    @State private var camera = ReaderPageCamera()

    var body: some View {
        let bounds = contentBounds
        ReaderPageZoom(
            size: size, content: bounds, readsRightToLeft: settings.value.readsRightToLeft,
            maximumScale: CGFloat(IosReader.companion.MAXIMUM_PAGE_ZOOM),
            doubleTapScale: CGFloat(IosReader.companion.DOUBLE_TAP_ZOOM),
            onTap: onTap,
            onZoomed: { model.isPageZoomed = $0 },
            onCamera: { if camera != $0 { camera = $0 } }
        ) {
            ReaderSpreadPages(
                model: model, settings: settings,
                pages: slot.placements.map { placement in
                    ReaderSpreadPages.Page(
                        index: Int(placement.index), segment: Int(placement.segment),
                        frame: CGRect(x: CGFloat(placement.x) - bounds.minX, y: CGFloat(placement.y) - bounds.minY,
                                      width: CGFloat(placement.width), height: CGFloat(placement.height))
                    )
                },
                camera: camera, adjusted: settings.value.hasColorAdjustments, shadow: settings.value.bookEffect
            )
            .frame(width: bounds.width, height: bounds.height, alignment: .topLeading)
            .environment(\.layoutDirection, .leftToRight)
        }
        .frame(width: size.width, height: size.height)
    }

    /// The pages' union inside the viewport (placements already include the fit's centering); an empty slot, still
    /// waiting for its pages, covers the viewport so it still takes taps.
    private var contentBounds: CGRect {
        guard let first = slot.placements.first else { return CGRect(origin: .zero, size: size) }
        var minX = CGFloat(first.x)
        var minY = CGFloat(first.y)
        var maxX = CGFloat(first.x + first.width)
        var maxY = CGFloat(first.y + first.height)
        for placement in slot.placements.dropFirst() {
            minX = min(minX, CGFloat(placement.x))
            minY = min(minY, CGFloat(placement.y))
            maxX = max(maxX, CGFloat(placement.x + placement.width))
            maxY = max(maxY, CGFloat(placement.y + placement.height))
        }
        return CGRect(x: minX, y: minY, width: max(1, maxX - minX), height: max(1, maxY - minY))
    }
}

/// The settled camera of a paged slot in reader-core's terms (`PagedCameraTransform`): scale and the centre-anchored
/// offset `PagedSlot.contentViewport` resolves the visible region from.
struct ReaderPageCamera: Equatable {
    var scale: CGFloat = 1
    var offset: CGPoint = .zero
}

/// A zooming `UIScrollView` around one slot's SwiftUI pages. Unzoomed content that fits does not scroll at all, so the
/// enclosing pager (scroll view or turn pager) owns every drag; zoomed or overflowing content pans natively and, once
/// at its edge, leaves the drag to the pager as nested UIKit scroll views do.
private struct ReaderPageZoom<Content: View>: UIViewRepresentable {
    let size: CGSize
    let content: CGRect
    let readsRightToLeft: Bool
    let maximumScale: CGFloat
    let doubleTapScale: CGFloat
    let onTap: (CGPoint) -> Void
    let onZoomed: (Bool) -> Void
    let onCamera: (ReaderPageCamera) -> Void
    @ViewBuilder let page: () -> Content

    func makeCoordinator() -> Coordinator { Coordinator(host: UIHostingController(rootView: page())) }

    func makeUIView(context: Context) -> ReaderZoomScrollView {
        let coordinator = context.coordinator
        coordinator.parent = self
        let view = ReaderZoomScrollView()
        view.host = coordinator.host
        view.delegate = coordinator
        view.minimumZoomScale = 1
        view.maximumZoomScale = maximumScale
        view.bouncesZoom = true
        view.showsVerticalScrollIndicator = false
        view.showsHorizontalScrollIndicator = false
        view.contentInsetAdjustmentBehavior = .never
        view.scrollsToTop = false
        view.backgroundColor = .clear
        view.clipsToBounds = true
        let host = coordinator.host
        host.view.backgroundColor = .clear
        host.safeAreaRegions = []
        host.sizingOptions = []
        view.addSubview(host.view)
        let double = UITapGestureRecognizer(target: coordinator, action: #selector(Coordinator.doubleTap(_:)))
        double.numberOfTapsRequired = 2
        let single = UITapGestureRecognizer(target: coordinator, action: #selector(Coordinator.singleTap(_:)))
        single.require(toFail: double)
        view.addGestureRecognizer(double)
        view.addGestureRecognizer(single)
        coordinator.scroll = view
        return view
    }

    func updateUIView(_ view: ReaderZoomScrollView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        view.maximumZoomScale = maximumScale
        coordinator.host.rootView = page()
        coordinator.layout()
    }

    static func dismantleUIView(_ view: ReaderZoomScrollView, coordinator: Coordinator) {
        if coordinator.zoomed { coordinator.parent?.onZoomed(false) }
    }

    @MainActor
    final class Coordinator: NSObject, UIScrollViewDelegate {
        var parent: ReaderPageZoom?
        let host: UIHostingController<Content>
        weak var scroll: ReaderZoomScrollView?
        private var laidOut: (size: CGSize, content: CGRect)?
        private(set) var zoomed = false
        private var reportedOffset = CGPoint.zero

        init(host: UIHostingController<Content>) { self.host = host }

        /// Lays the pages out unzoomed whenever the viewport or the pages' fitted bounds change (a page's real size
        /// arriving moves them); overflow starts at the reading edge, as `PagedCameraTransform.initial`.
        func layout() {
            guard let scroll, let parent, parent.size.width > 1, parent.size.height > 1 else { return }
            if let laidOut, laidOut.size == parent.size, laidOut.content == parent.content { return }
            laidOut = (parent.size, parent.content)
            scroll.setZoomScale(1, animated: false)
            host.view.transform = .identity
            host.view.frame = CGRect(origin: .zero, size: parent.content.size)
            scroll.contentSize = parent.content.size
            updateInsets()
            let insets = scroll.contentInset
            let overflowX = parent.content.width - parent.size.width
            scroll.contentOffset = CGPoint(x: parent.readsRightToLeft && overflowX > 0 ? overflowX : -insets.left,
                                           y: -insets.top)
            setZoomed(false)
            report()
        }

        /// Content smaller than the viewport keeps its fitted place (centred, or wherever the fit put it) and moves
        /// towards the centre of what it leaves free as it grows, so panning never shows past the pages.
        private func updateInsets() {
            guard let scroll, let parent else { return }
            let scale = scroll.zoomScale
            func leading(_ origin: CGFloat, _ extent: CGFloat, _ viewport: CGFloat) -> CGFloat {
                let scaled = extent * scale
                guard scaled < viewport else { return 0 }
                let free = viewport - extent
                let ratio = free > 0.5 ? min(max(origin / free, 0), 1) : 0.5
                return (viewport - scaled) * ratio
            }
            let content = parent.content, size = parent.size
            let left = leading(content.minX, content.width, size.width)
            let top = leading(content.minY, content.height, size.height)
            scroll.contentInset = UIEdgeInsets(
                top: top, left: left,
                bottom: max(0, size.height - content.height * scale - top),
                right: max(0, size.width - content.width * scale - left)
            )
            // Content that fits does not scroll, so the pager behind gets the drag from its first touch.
            scroll.isScrollEnabled = scale > 1.001 || content.width > size.width + 0.5 || content.height > size.height + 0.5
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { host.view }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            updateInsets()
            setZoomed(scrollView.zoomScale > 1.01)
        }

        func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) { report() }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            // Long pans refresh the tiles on the way; short moves wait for the pan to settle.
            guard !scrollView.isZooming, let parent else { return }
            let moved = hypot(scrollView.contentOffset.x - reportedOffset.x, scrollView.contentOffset.y - reportedOffset.y)
            if moved > min(parent.size.width, parent.size.height) / 3 { report() }
        }

        func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
            if !decelerate { report() }
        }

        func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { report() }
        func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) { report() }

        private func setZoomed(_ value: Bool) {
            guard zoomed != value else { return }
            zoomed = value
            parent?.onZoomed(value)
        }

        /// The visible part of the slot as reader-core's camera: `contentViewport(scale, offset)` gives it back.
        private func report() {
            guard let scroll, let parent else { return }
            reportedOffset = scroll.contentOffset
            let scale = scroll.zoomScale
            let visible = CGPoint(x: parent.content.minX + scroll.contentOffset.x / scale,
                                  y: parent.content.minY + scroll.contentOffset.y / scale)
            let center = CGPoint(x: parent.size.width / 2, y: parent.size.height / 2)
            parent.onCamera(ReaderPageCamera(
                scale: scale,
                offset: CGPoint(x: -center.x - (visible.x - center.x) * scale, y: -center.y - (visible.y - center.y) * scale)
            ))
        }

        @objc func singleTap(_ gesture: UITapGestureRecognizer) {
            guard let scroll else { return }
            let point = gesture.location(in: scroll)
            parent?.onTap(CGPoint(x: point.x - scroll.contentOffset.x, y: point.y - scroll.contentOffset.y))
        }

        /// Android's double tap: back to fit when zoomed, otherwise zoom in keeping the tapped point under the finger.
        @objc func doubleTap(_ gesture: UITapGestureRecognizer) {
            guard let scroll, let parent else { return }
            if scroll.zoomScale > 1.01 { scroll.setZoomScale(1, animated: true); return }
            let scale = min(parent.doubleTapScale, scroll.maximumZoomScale)
            let anchor = gesture.location(in: host.view)
            let touch = gesture.location(in: scroll)
            let screen = CGPoint(x: touch.x - scroll.contentOffset.x, y: touch.y - scroll.contentOffset.y)
            UIView.animate(withDuration: 0.25, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                scroll.zoomScale = scale
                let insets = scroll.contentInset
                let maxX = parent.content.width * scale - parent.size.width + insets.right
                let maxY = parent.content.height * scale - parent.size.height + insets.bottom
                scroll.contentOffset = CGPoint(
                    x: min(max(anchor.x * scale - screen.x, -insets.left), max(-insets.left, maxX)),
                    y: min(max(anchor.y * scale - screen.y, -insets.top), max(-insets.top, maxY))
                )
            } completion: { _ in self.report() }
        }
    }
}

/// Keeps the hosted pages a child of the reader's view controller, so their sheets (a challenge's browser) present.
final class ReaderZoomScrollView: UIScrollView {
    weak var host: UIViewController?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard let host else { return }
        if window == nil {
            if host.parent != nil { host.willMove(toParent: nil); host.removeFromParent() }
            return
        }
        guard host.parent == nil else { return }
        var responder: UIResponder? = next
        while let current = responder, !(current is UIViewController) { responder = current.next }
        guard let controller = responder as? UIViewController else { return }
        controller.addChild(host)
        host.didMove(toParent: controller)
    }
}

/// A page handed to the system share sheet (Android's "share page").
struct SharedPage: Identifiable {
    let id = UUID()
    let image: UIImage
}

struct ActivitySheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
