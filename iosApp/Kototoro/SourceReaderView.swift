import Combine
import KototoroShared
import SwiftUI
import UniformTypeIdentifiers

struct ReaderWindowSlot: Identifiable {
    let id: String
    let slot: IosReaderSlot
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
        }
    }
    /// Window positions by page identity, for the resource window's lookups.
    private var windowPositions: [String: Int] = [:]
    var storageDirectories: Set<String> {
        Set((window.map(\.chapterId) + [chapter.id]).map {
            ReaderStorageMetadata.directoryID(contentID: content.id, chapterID: $0)
        })
    }
    private var windowPages: [IosSourcePage] = []
    private var chapterPages: [String: [IosSourcePage]] = [:]
    private var chapterPreloads: [String: ChapterPreload] = [:]
    private var pendingWindow = false
    @Published private(set) var images: [Int: UIImage] = [:]
    @Published private(set) var artifacts: [Int: ReaderImageArtifact] = [:]
    @Published private(set) var error: String?
    /// The last failure was a browser challenge (Cloudflare): the error offers the in-app browser.
    @Published private(set) var challenge: IosChallenge?
    struct PageFailure {
        let message: String
        let challenge: IosChallenge?
    }
    @Published private(set) var pageFailures: [Int: PageFailure] = [:]
    @Published private(set) var isLoadingPages = false
    @Published private(set) var bookmarks: [IosBookmark] = []
    private var changingBookmark = false
    @Published private(set) var slots: [IosReaderSlot] = [] {
        didSet { identifiedSlots = slots.map { ReaderWindowSlot(id: slotIdentity($0), slot: $0) } }
    }
    private(set) var identifiedSlots: [ReaderWindowSlot] = []
    @Published private(set) var loaded = false
    @Published private(set) var pageRevision = 0
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
    let runtime: IosSources
    let content: IosSourceContent
    private let chapterByID: [String: IosSourceChapter]
    let engine = IosReader()
    var library: IosLibrary?
    private var request: UUID?
    /// One load (download and decode) per page, keyed by the page's identity and owned by the model, not by the view
    /// that asked: a page view that goes away or a window that is rebuilt never strands a load half-way, and a second
    /// caller waits for the running one. Loads stop only when their page leaves the resource window (see
    /// [updateResources]) or the scene changes.
    private var pageLoads: [String: (serial: Int, task: Task<Void, Never>)] = [:]
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
    private var viewAnchor: (page: IosReaderPage, offset: Float)?
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
    private var initialBookmark: IosBookmark?

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter, initialPage: Int? = nil,
         initialBookmark: IosBookmark? = nil) {
        self.runtime = runtime; self.content = content; self.chapter = chapter
        self.initialPage = initialPage
        self.initialBookmark = initialBookmark
        chapterByID = Dictionary(content.chapters.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
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
            slots = engine.slots()
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
        do {
            let loadedPage: ReaderLoadedPage
            if ReaderDownloads.isDownloaded(content: content, chapter: owner) {
                loadedPage = try await ReaderDownloads.loadedPage(
                    runtime: runtime, content: content, chapter: owner,
                    page: page, index: Int(key.index), cropMargins: cropMargins
                )
            } else {
                loadedPage = try await pageArtifact(key: key, page: page).loadedPage(cropMargins: cropMargins)
            }
            try Task.checkCancellation()
            // The window may have been rebuilt meanwhile: the page goes where it is now, if it is still shown.
            guard request == token, let target = window.firstIndex(of: key) else { return }
            images[target] = loadedPage.image
            artifacts[target] = loadedPage.artifact
            pageFailures[target] = nil
            // A reading page held ahead of the first visible one (a short page at the strip's end) is what the reader
            // is on: it, not the first visible page the scene anchors to, stays where it is on screen.
            let held = preferences.mode >= 3 && stripPositionValid && !awaitingRestore
                && windowIndex != Int(engine.progress(x: Float(viewportOffset.x), y: Float(viewportOffset.y)).index)
            let heldStart = held ? readingPageStart() : nil
            let compensated = engine.setImageSize(
                index: Int32(target),
                width: Int32(loadedPage.artifact.encodedWidth),
                height: Int32(loadedPage.artifact.encodedHeight),
                viewportX: Float(viewportOffset.x), viewportY: Float(viewportOffset.y)
            )
            slots = engine.slots()
            if preferences.mode >= 3 {
                if awaitingRestore || !stripPositionValid {
                    // Keep the semantic target while its estimated size becomes exact, including long-page bookmarks.
                    requestScroll(to: currentOrigin)
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
            if !slots.contains(where: { Int($0.index) == slotIndex && $0.placements.contains { Int($0.index) == windowIndex } }) {
                slotIndex = Int(slots.first { $0.placements.contains { Int($0.index) == windowIndex } }?.index ?? 0)
            }
            evictImages()
            if slots.first(where: { $0.placements.contains { Int($0.index) == windowIndex } })?
                .placements.contains(where: { Int($0.index) == target }) == true {
                updateResources()
                await persist()
            }
        } catch {
            if !Task.isCancelled && request == token, let target = window.firstIndex(of: key) {
                pageFailures[target] = PageFailure(message: SourceErrorText.describe(error),
                                                   challenge: SourceErrorText.challenge(error))
            }
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
        if loaded && (isLoadingPages || isPageZoomed || (!continuous && isUserPaging)) { pendingWindow = true; return }
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
            slots = engine.slots()
            slotIndex = Int(slots.first { $0.anchor == Int32(windowIndex) }?.index ?? 0)
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
        preloadAdjacentChapters()
        if preferences.mode >= 3 {
            guard let planned = engine.resourceWindow(
                x: Float(viewportOffset.x), y: Float(viewportOffset.y),
                velocityX: Float(stripVelocity.x), velocityY: Float(stripVelocity.y),
                dragging: stripDragging, force: force || needsReplan
            ) else { return }
            let requests = planned.filter { window.indices.contains(Int($0.index)) }
                .sorted { $0.priority < $1.priority }
            let unzoomed = unzoomedStripPages()
            let decoded = requests.filter(\.decode).map { Int($0.index) } + unzoomed
            decodeWindow = Set(decoded.map { pageIdentity(window[$0]) })
            downloadWindow = requests.filter { !$0.decode }.map { pageIdentity(window[Int($0.index)]) }
            // Visible and near pages load at once, nearest priority first.
            for request in requests where request.decode { startWanted(Int(request.index)) }
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
        evictImages()
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
        return slots.flatMap(\.placements).compactMap { placement in
            let start = CGFloat(vertical ? placement.y : placement.x)
            let end = start + CGFloat(vertical ? placement.height : placement.width)
            let index = Int(placement.index)
            return end > lower && start < upper && window.indices.contains(index) ? index : nil
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
        for id in downloadWindow where started < ReaderLoading.prefetchLimit {
            guard fetches[id] == nil, let position = windowPositions[id], images[position] == nil,
                  let owner = chapterByID[window[position].chapterId],
                  !ReaderDownloads.isDownloaded(content: content, chapter: owner) else { continue }
            startFetch(window[position], page: windowPages[position], isPrefetch: true)
            started += 1
        }
    }

    /// Decoded images stay bounded to the planned decode window and the pages on screen; immutable on-disk artifacts
    /// survive eviction and are decoded again when their page returns.
    private func evictImages() {
        var keep = Set(decodeWindow.compactMap { windowPositions[$0] })
        if preferences.mode >= 3 {
            let x = Float(viewportOffset.x), y = Float(viewportOffset.y)
            let first = Int(engine.progress(x: x, y: y).index), last = Int(engine.lastVisible(x: x, y: y))
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

    func displayImage(for target: Int, segment: Int) -> UIImage? {
        guard let base = images[target] else { return nil }
        return segment == 1 || segment == 2 ? SourceImageFile.splitPage(base, segment: segment) : base
    }

    func pageDescription(at target: Int) -> String {
        guard window.indices.contains(target) else { return String(localized: "漫画页") }
        let key = window[target]
        let chapter = chapterByID[key.chapterId]?.title ?? String(localized: "章节")
        return String(localized: "\(chapter)，第 \(key.index + 1) 页")
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
        let progress = engine.progress(x: Float(viewportOffset.x), y: Float(viewportOffset.y))
        let first = min(max(0, Int(progress.index)), max(0, windowPages.count - 1))
        let last = Int(engine.lastVisible(x: Float(viewportOffset.x), y: Float(viewportOffset.y)))
        // Motion is compared by page identity, so the coordinate shift of a rebuilt window is not a move.
        let movedBack: Bool = {
            guard let previous = viewAnchor, let before = window.firstIndex(of: previous.page) else { return false }
            return first < before || first == before && progress.offset < previous.offset - 0.5
        }()
        if window.indices.contains(first) { viewAnchor = (window[first], progress.offset) }
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
        let nextSlot = Int(slots.first { $0.anchor == Int32(windowIndex) }?.index ?? 0)
        if slotIndex != nextSlot { slotIndex = nextSlot }
        updateResources()
    }

    func setStripZoom(_ value: CGFloat) {
        guard preferences.mode >= 3 else { return }
        let zoom = min(max(value, CGFloat(IosReader.companion.MINIMUM_ZOOM)), CGFloat(IosReader.companion.MAXIMUM_ZOOM))
        guard zoom != stripZoom else { return }
        stripZoom = zoom
        engine.setZoom(zoom: Float(zoom))
        slots = engine.slots()
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
        fetches = [:]
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
         initialBookmark: IosBookmark? = nil) {
        _model = StateObject(wrappedValue: SourceReaderModel(runtime: runtime, content: content, chapter: chapter,
                                                           initialPage: initialPage, initialBookmark: initialBookmark))
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
            await model.loadImage(at: model.windowIndex); await model.persist()
        }
        .task(id: model.autoScroll) {
            var elapsed = 0
            var landedOnEdge = false
            while model.autoScroll && !Task.isCancelled {
                if scenePhase != .active || model.error != nil || model.hasVisiblePageFailure ||
                    (model.loaded && model.pages.isEmpty) {
                    model.autoScroll = false; break
                }
                if !model.isReadyForAutoScroll {
                    try? await Task.sleep(for: .milliseconds(16))
                    continue
                }
                if settings.value.mode >= 3 {
                    try? await Task.sleep(for: .milliseconds(16))
                    guard !Task.isCancelled, model.autoScroll else { break }
                    // A window rebuilt during the sleep moves the strip: wait for its restore, not the old offset.
                    guard model.isReadyForAutoScroll else { continue }
                    let current = settings.value.mode == 3 ? model.stripOffset.y : model.stripOffset.x
                    let direction = settings.value.continuousForwardSign
                    let next = current + direction * CGFloat(model.engine.autoScrollDistance(speed: Float(settings.value.autoSpeed), elapsedMillis: 16))
                    let range = model.continuousScrollRange
                    if !range.contains(next) {
                        // Land on the edge first: the strip's end reads its last visible page (and its chapter).
                        let edge = direction > 0 ? range.upperBound : range.lowerBound
                        if abs(current - edge) > 0.5 && !landedOnEdge {
                            landedOnEdge = true; model.scrollStrip(to: edge, animated: false); continue
                        }
                        // The next chapter joins the strip once its pages arrive; the scroll then simply continues.
                        if model.isLoadingNextChapter { continue }
                        if await model.advanceChapterAutomatically() { elapsed = 0; continue }
                        model.autoScroll = false; await model.persist(); break
                    }
                    landedOnEdge = false
                    model.scrollStrip(to: next, animated: false)
                    elapsed += 16
                    if elapsed >= 5000 { elapsed = 0; await model.persist() }
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
                await library.trackingAccounts.syncAfterReading(model.content, tracking: library.library?.tracking)
            }
        }
        .readerPresence(directories: model.storageDirectories)
        .sheet(isPresented: $showingDownloads) { ReaderDownloadsSheet() }
        .fileExporter(isPresented: $exporting, document: export, contentType: .png,
                      defaultFilename: "\(model.content.title)-\(model.index + 1)") { result in
            if case .failure(let error) = result { downloadStatus = error.localizedDescription }
        }
        .sheet(isPresented: $cropping, onDismiss: {
            if let image = croppedImage { croppedImage = nil; save(image) }
        }) {
            if let image = model.image { ReaderCropView(image: image) { croppedImage = $0 } }
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
        case "crop": if model.image != nil { closePanel(); cropping = true }
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
/// and hands the previous delegate back when it leaves.
struct EdgeSwipeBack: UIViewControllerRepresentable {
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

        /// The page scroll view and the reader's drags wait for the edge swipe, which fails at once for any touch that
        /// does not start at the screen edge, so turning pages is unaffected.
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
    @ObservedObject var model: SourceReaderModel
    @ObservedObject var settings: ReaderSettings
    @Environment(\.displayScale) private var displayScale
    let index: Int
    var segment = 0
    var displaySize = CGSize.zero
    var viewportOffset = CGPoint.zero
    var zoomScale: CGFloat = 1
    var body: some View {
        Group {
            if let image = model.displayImage(for: index, segment: segment) {
                ZStack(alignment: .topLeading) {
                    Image(uiImage: image).resizable().scaledToFit()
                        .frame(width: displaySize.width > 0 ? displaySize.width : nil,
                               height: displaySize.height > 0 ? displaySize.height : nil)
                    if segment == 0, let artifact = model.artifacts[index], artifact.tiled,
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
                .accessibilityLabel(model.pageDescription(at: index))
            } else if let failure = model.pageFailures[index] {
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
        .task(id: "\(model.pageRevision)-\(index)") { await model.loadImage(at: index) }
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
    @ObservedObject var model: SourceReaderModel
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
            ZStack(alignment: .topLeading) {
                ForEach(slot.placements.indices, id: \.self) { offset in
                    let placement = slot.placements[offset]
                    ReaderPageTile(
                        model: model,
                        settings: settings,
                        index: Int(placement.index),
                        segment: Int(placement.segment),
                        displaySize: CGSize(width: CGFloat(placement.width), height: CGFloat(placement.height)),
                        viewportOffset: camera.offset,
                        zoomScale: camera.scale
                    )
                        .frame(width: CGFloat(placement.width), height: CGFloat(placement.height))
                        .offset(x: CGFloat(placement.x) - bounds.minX, y: CGFloat(placement.y) - bounds.minY)
                }
            }
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
        view.isDirectionalLockEnabled = true
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
