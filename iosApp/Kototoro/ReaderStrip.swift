import Combine
import KototoroShared
import SwiftUI
import UIKit

/// What the reader model drives on the continuous strip (see `SourceReaderModel.strip`).
@MainActor
protocol ReaderStripHost: AnyObject {
    /// The strip's offset. The strip has no insets, so this is the scene's viewport origin.
    var offset: CGPoint { get }
    /// Lays the strip out from the model's slots and moves it to [offset], or leaves it where it is when nil.
    func layoutStrip(offset: CGPoint?, animated: Bool)
    /// Moves the strip along its reading axis, keeping the cross-axis pan of a zoomed strip.
    func layoutStrip(origin: CGFloat, animated: Bool)
}

/// The continuous reading modes (vertical webtoon and horizontal strip) as one `UIScrollView` laid out from the shared
/// scene, the way Android's `ComposeSceneWebtoonReader` draws `VerticalReaderScene` with its own scroll state: the
/// scene's coordinates are the scroll view's, pages are placed at their scene bounds, and every geometry change (a
/// chapter joining or leaving the window, a page's real size replacing its estimate, zoom) moves the content and its
/// offset in the same main-thread turn. A SwiftUI lazy stack estimated the size of pages it had not built, so its
/// offsets drifted from the scene's and restores landed in the wrong chapter.
///
/// Like Aidoku's webtoon reader, the scroll view ignores the safe area (`contentInsetAdjustmentBehavior = .never`), so
/// pages run under the status bar and the home indicator and toggling the controls never moves them.
struct ReaderStrip: UIViewControllerRepresentable {
    @ObservedObject var model: SourceReaderModel
    @ObservedObject var settings: ReaderSettings
    let onTap: (CGPoint) -> Void

    func makeUIViewController(context: Context) -> ReaderStripController {
        ReaderStripController(model: model, settings: settings)
    }

    func updateUIViewController(_ controller: ReaderStripController, context: Context) {
        controller.onTap = onTap
        controller.modelChanged()
    }

    static func dismantleUIViewController(_ controller: ReaderStripController, coordinator: ()) {
        controller.detach()
    }
}

/// One page of the strip in plain UIKit, as Aidoku's and Suwatte's webtoon pages are an image view in a light node:
/// the image, the tiles of a page too large to decode whole, and a spinner while it loads. The shared SwiftUI
/// `ReaderPageTile` is hosted only for what plain views do not draw: a failure with its retry and verification
/// buttons, and the colour adjustments (brightness, contrast, grayscale, invert, book shadow).
@MainActor
final class ReaderStripPageView: UIView {
    struct Configuration: Equatable {
        let index: Int
        let size: CGSize
        /// Where a tiled page resolves its tiles for: follows the scroll in steps and when it settles.
        let viewport: CGPoint
        let adjusted: Bool
    }

    private let model: SourceReaderModel
    private let settings: ReaderSettings
    private weak var owner: UIViewController?
    private let imageView = UIImageView()
    private let tiles = ReaderStripTileView()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private var host: UIHostingController<ReaderPageTile>?
    private(set) var configuration: Configuration?
    private var state: ReaderPageState?
    private var subscription: AnyCancellable?
    private var loadTask: Task<Void, Never>?
    private var loadedRevision: Int?

    init(model: SourceReaderModel, settings: ReaderSettings, owner: UIViewController) {
        self.model = model
        self.settings = settings
        self.owner = owner
        super.init(frame: .zero)
        backgroundColor = .clear
        imageView.contentMode = .scaleAspectFit
        imageView.accessibilityIdentifier = "reader.image"
        imageView.accessibilityTraits = .image
        tiles.isUserInteractionEnabled = false
        spinner.color = .gray
        spinner.hidesWhenStopped = true
        addSubview(imageView)
        addSubview(tiles)
        addSubview(spinner)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    /// The page shows its error, whose retry and verification buttons keep their taps from the reader.
    var showsFailure: Bool {
        guard let configuration, !isHidden else { return false }
        return model.pageFailures[configuration.index] != nil
    }

    func configure(_ configuration: Configuration) {
        guard configuration != self.configuration else { return }
        let previous = self.configuration
        self.configuration = configuration
        if previous?.index != configuration.index { bind(configuration.index) }
        refresh()
        if previous?.size != configuration.size {
            setNeedsLayout()
            layoutIfNeeded()
        }
    }

    /// Back to the spare pool: the page lets go of its image and tiles at once, as Aidoku's page leaves the display
    /// range, rather than holding them while it waits to be reused.
    func reset() {
        subscription = nil
        state = nil
        configuration = nil
        loadTask?.cancel()
        loadTask = nil
        loadedRevision = nil
        imageView.image = nil
        imageView.isAccessibilityElement = false
        tiles.reset()
        spinner.stopAnimating()
        removeHost()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        imageView.frame = bounds
        tiles.frame = bounds
        spinner.center = CGPoint(x: bounds.midX, y: bounds.midY)
        host?.view.frame = bounds
    }

    private func bind(_ index: Int) {
        loadTask?.cancel()
        loadTask = nil
        loadedRevision = nil
        tiles.reset()
        let state = model.pageState(at: index)
        self.state = state
        subscription = state.didChange.sink { [weak self] in self?.refresh() }
    }

    private func refresh() {
        guard let configuration, let state else { return }
        // A page that appears, or whose scene was reloaded, asks for its image (`ReaderPageTile`'s `.task`).
        if loadedRevision != state.revision, configuration.index >= 0 {
            loadedRevision = state.revision
            loadTask?.cancel()
            let model = model, index = configuration.index
            loadTask = Task { await model.loadImage(at: index) }
        }
        let viewport = state.artifact?.tiled == true ? configuration.viewport : .zero
        if configuration.adjusted || (state.image == nil && state.failure != nil) {
            imageView.image = nil
            imageView.isHidden = true
            imageView.isAccessibilityElement = false
            tiles.reset()
            spinner.stopAnimating()
            showHost(ReaderPageTile(model: model, settings: settings, index: configuration.index,
                                    displaySize: configuration.size, viewportOffset: viewport))
            return
        }
        removeHost()
        if imageView.image !== state.image { imageView.image = state.image }
        let shown = state.image != nil
        imageView.isHidden = !shown
        imageView.isAccessibilityElement = shown
        imageView.accessibilityLabel = state.description
        if shown { spinner.stopAnimating() } else { spinner.startAnimating() }
        if shown, let artifact = state.artifact, artifact.tiled,
           configuration.size.width > 1, configuration.size.height > 1 {
            let scale = traitCollection.displayScale > 0 ? traitCollection.displayScale : 3
            let visible = model.engine.visibleTiles(
                x: Float(viewport.x), y: Float(viewport.y), index: Int32(configuration.index),
                encodedWidth: Int32(artifact.encodedWidth), encodedHeight: Int32(artifact.encodedHeight),
                displayScale: Float(scale), zoomScale: 1
            )
            tiles.isHidden = false
            tiles.update(artifact: artifact, tiles: visible, displaySize: configuration.size)
        } else {
            tiles.reset()
            tiles.isHidden = true
        }
    }

    private func showHost(_ page: ReaderPageTile) {
        if let host {
            host.rootView = page
            return
        }
        let host = UIHostingController(rootView: page)
        host.safeAreaRegions = []
        host.sizingOptions = []
        host.view.backgroundColor = .clear
        owner?.addChild(host)
        host.view.frame = bounds
        addSubview(host.view)
        host.didMove(toParent: owner)
        self.host = host
    }

    private func removeHost() {
        guard let host else { return }
        host.willMove(toParent: nil)
        host.view.removeFromSuperview()
        host.removeFromParent()
        self.host = nil
    }
}

/// The tiles of a strip page too large to decode whole, over its base image (`ReaderTiledOverlay` is the same for
/// paged slots): the visible region decoded again at the sample size the strip needs. Tiles of the previous viewport
/// stay drawn (coarser ones underneath) until the new set is ready.
@MainActor
final class ReaderStripTileView: UIView {
    private struct DecodedTile {
        let tile: IosReaderTile
        let clip: UIView
        let image: UIImageView
    }

    private var artifact: ReaderImageArtifact?
    private var decoded: [String: DecodedTile] = [:]
    private var signature = ""
    private var displaySize = CGSize.zero
    private var task: Task<Void, Never>?

    private static func key(_ tile: IosReaderTile) -> String {
        "\(tile.decodeLeft):\(tile.decodeTop):\(tile.decodeWidth):\(tile.decodeHeight):\(tile.sampleSize)"
    }

    func reset() {
        task?.cancel()
        task = nil
        for tile in decoded.values { tile.clip.removeFromSuperview() }
        decoded = [:]
        signature = ""
        artifact = nil
    }

    func update(artifact: ReaderImageArtifact, tiles: [IosReaderTile], displaySize: CGSize) {
        if self.artifact != artifact {
            reset()
            self.artifact = artifact
        }
        if self.displaySize != displaySize {
            self.displaySize = displaySize
            for tile in decoded.values { place(tile) }
        }
        let signature = tiles.map(Self.key).joined(separator: "|")
        guard signature != self.signature else { return }
        self.signature = signature
        task?.cancel()
        let active = Set(tiles.map(Self.key))
        // Bounded: a long pan leaves at most one screen of stale tiles behind.
        if decoded.count > active.count * 2 { drop(except: active) }
        task = Task { [weak self] in
            for tile in tiles {
                guard !Task.isCancelled, let self else { return }
                let key = Self.key(tile)
                guard self.decoded[key] == nil else { continue }
                guard let image = try? await artifact.decodeTile(tile) else { continue }
                guard !Task.isCancelled else { return }
                self.insert(key: key, tile: tile, image: image)
            }
            guard !Task.isCancelled else { return }
            self?.drop(except: active)
        }
    }

    private func insert(key: String, tile: IosReaderTile, image: UIImage) {
        let clip = UIView()
        clip.clipsToBounds = true
        clip.isUserInteractionEnabled = false
        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleToFill
        clip.addSubview(imageView)
        addSubview(clip)
        let decodedTile = DecodedTile(tile: tile, clip: clip, image: imageView)
        decoded[key] = decodedTile
        place(decodedTile)
        // Coarse samples first, so the finer tiles of the current viewport draw on top.
        for (_, tile) in decoded.sorted(by: {
            $0.value.tile.sampleSize != $1.value.tile.sampleSize
                ? $0.value.tile.sampleSize > $1.value.tile.sampleSize : $0.key < $1.key
        }) {
            bringSubviewToFront(tile.clip)
        }
    }

    private func drop(except active: Set<String>) {
        for (key, tile) in decoded where !active.contains(key) {
            tile.clip.removeFromSuperview()
            decoded[key] = nil
        }
    }

    private func place(_ decodedTile: DecodedTile) {
        guard let artifact else { return }
        let tile = decodedTile.tile
        let layout = SourceImageFile.tileLayout(
            logical: CGRect(x: Int(tile.logicalLeft), y: Int(tile.logicalTop),
                            width: Int(tile.logicalWidth), height: Int(tile.logicalHeight)),
            decoded: CGRect(x: Int(tile.decodeLeft), y: Int(tile.decodeTop),
                            width: Int(tile.decodeWidth), height: Int(tile.decodeHeight)),
            sourceSize: CGSize(width: artifact.encodedWidth, height: artifact.encodedHeight),
            displaySize: displaySize
        )
        decodedTile.clip.frame = layout.frame
        decodedTile.image.frame = CGRect(origin: CGPoint(x: layout.decodedOffset.width, y: layout.decodedOffset.height),
                                         size: layout.decodedSize)
    }
}

final class ReaderStripController: UIViewController, UIScrollViewDelegate, UIGestureRecognizerDelegate,
    ReaderStripHost {
    private let model: SourceReaderModel
    private let settings: ReaderSettings
    var onTap: (CGPoint) -> Void = { _ in }

    private let scrollView = ReaderStripScrollView()
    private let content = UIView()
    /// Page views by slot identity (chapter-local page), so a page keeps its view while the window changes around it.
    private var cells: [String: ReaderStripPageView] = [:]
    private var spare: [ReaderStripPageView] = []
    /// The slots' scene frames (before cross-axis padding) in reading-axis order and the scene's extent, read from the
    /// shared slots once per new slot list instead of on every scroll frame.
    private struct StripFrame {
        let id: String
        let index: Int
        let frame: CGRect
    }
    private var frames: [StripFrame] = []
    private var sceneExtent = CGSize.zero
    private var framesKey: (revision: Int, vertical: Bool)?
    private var tileViewport = CGPoint.zero
    /// A programmatic layout is moving the strip: its scroll callbacks are not the reader's motion.
    private var applying = false
    private var attached = false
    private var sample: (offset: CGPoint, time: CFTimeInterval)?
    private var velocity = CGPoint.zero
    /// UIKit owns the camera during a pinch or an animated double tap. Scene layout resumes on completion.
    private var zooming = false
    private var rebasingZoom = false
    private var zoomLocation: CGPoint?
    private struct ZoomAnimation {
        let link: CADisplayLink
        let start: CFTimeInterval
        let from: CGFloat, to: CGFloat
        let focus: CGPoint, anchor: CGPoint
    }
    private var zoomAnimation: ZoomAnimation?
    /// A double tap's frame is setting the scale: UIKit's zoom callbacks are not a gesture.
    private var steppingZoom = false
    private static let zoomDuration: CFTimeInterval = 0.3
    private var zoomActionName = ""

    init(model: SourceReaderModel, settings: ReaderSettings) {
        self.model = model
        self.settings = settings
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    var offset: CGPoint { scrollView.contentOffset }
    private var vertical: Bool { model.stripIsVertical }
    private var doubleTapZoom: CGFloat { abs(model.stripZoom - 1) < 0.001 ? 2 : 1 }
    private var crossAxisPadding: CGFloat {
        let extent = vertical ? view.bounds.width : view.bounds.height
        return max(0, extent - floor(extent * model.stripZoom)) / 2
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        scrollView.frame = view.bounds
        scrollView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.automaticallyAdjustsScrollIndicatorInsets = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.scrollsToTop = false
        scrollView.isDirectionalLockEnabled = true
        scrollView.alwaysBounceVertical = vertical
        scrollView.alwaysBounceHorizontal = !vertical
        scrollView.backgroundColor = .clear
        scrollView.delegate = self
        scrollView.bouncesZoom = false
        updateZoomLimits()
        scrollView.accessibilityIdentifier = "reader.canvas"
        content.backgroundColor = .clear
        scrollView.addSubview(content)
        view.addSubview(scrollView)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        doubleTap.numberOfTapsRequired = 2
        doubleTap.delegate = self
        let singleTap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        singleTap.require(toFail: doubleTap)
        singleTap.delegate = self
        for gesture in [doubleTap, singleTap] { scrollView.addGestureRecognizer(gesture) }
        model.strip = self
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        model.trimDecodedImages()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard view.bounds.width > 1, view.bounds.height > 1, !zooming, !rebasingZoom, !applying else { return }
        if !attached {
            attached = true
            model.strip = self
            layoutStrip(offset: nil, animated: false)
            // A strip created for a loaded chapter (a mode switch) starts at the reading position.
            model.stripAttached()
        } else {
            layoutStrip(offset: nil, animated: false)
        }
    }

    /// The SwiftUI side changed (slots, images, settings): the content follows without moving the strip.
    func modelChanged() {
        guard isViewLoaded, attached else { return }
        layoutStrip(offset: nil, animated: false)
        scrollView.accessibilityValue = model.readingPositionDescription
        let name = doubleTapZoom == 1 ? String(localized: "还原缩放") : String(localized: "放大")
        if name != zoomActionName {
            zoomActionName = name
            scrollView.accessibilityCustomActions = [UIAccessibilityCustomAction(name: name) { [weak self] _ in
                guard let self else { return false }
                self.animateZoom(self.doubleTapZoom,
                                 at: CGPoint(x: self.view.bounds.midX, y: self.view.bounds.midY))
                return true
            }]
        }
    }

    func detach() {
        zoomAnimation?.link.invalidate()
        zoomAnimation = nil
        if model.strip === self { model.strip = nil }
        if zooming {
            zooming = false
            model.endStripZoom()
        }
    }

    // MARK: - Layout

    func layoutStrip(origin: CGFloat, animated: Bool) {
        var point = scrollView.contentOffset
        if vertical { point.y = origin } else { point.x = origin }
        layoutStrip(offset: point, animated: animated)
    }

    func layoutStrip(offset target: CGPoint?, animated: Bool) {
        guard isViewLoaded, !zooming, !rebasingZoom, !applying else { return }
        let size = contentSize()
        applying = true
        UIView.performWithoutAnimation {
            if scrollView.contentSize != size {
                content.frame = CGRect(origin: .zero, size: size)
                scrollView.contentSize = size
            }
            if let target, !animated {
                let point = clampedCrossAxis(target, content: size)
                // Assigned, not set with `setContentOffset`: a fling in progress keeps its momentum across the move.
                if point != scrollView.contentOffset { scrollView.contentOffset = point }
                sample = (point, CACurrentMediaTime())
            }
            layoutCells()
        }
        applying = false
        guard let target else { return }
        if animated {
            scrollView.setContentOffset(clampedCrossAxis(target, content: size), animated: true)
        } else {
            tileViewport = scrollView.contentOffset
            layoutCells()
            report()
        }
    }

    /// The scene's extent: page bounds are scene coordinates, at least the screen so a short strip does not scroll.
    private func contentSize() -> CGSize {
        refreshFrames()
        return CGSize(width: max(view.bounds.width, sceneExtent.width), height: max(view.bounds.height, sceneExtent.height))
    }

    private func refreshFrames() {
        if let framesKey, framesKey.revision == model.slotRevision, framesKey.vertical == vertical { return }
        framesKey = (model.slotRevision, vertical)
        var result: [StripFrame] = []
        result.reserveCapacity(model.identifiedSlots.count)
        var width: CGFloat = 0, height: CGFloat = 0
        for item in model.identifiedSlots {
            let placements = item.slot.placements
            for placement in placements {
                width = max(width, CGFloat(placement.x + placement.width))
                height = max(height, CGFloat(placement.y + placement.height))
            }
            guard let placement = placements.first else { continue }
            result.append(StripFrame(id: item.id, index: Int(placement.index), frame: CGRect(
                x: CGFloat(placement.x), y: CGFloat(placement.y),
                width: CGFloat(placement.width), height: CGFloat(placement.height))))
        }
        let vertical = vertical
        result.sort { vertical ? $0.frame.minY < $1.frame.minY : $0.frame.minX < $1.frame.minX }
        frames = result
        sceneExtent = CGSize(width: width, height: height)
    }

    private func clampedCrossAxis(_ point: CGPoint, content: CGSize) -> CGPoint {
        let bounds = scrollView.bounds.size
        if vertical {
            return CGPoint(x: min(max(0, point.x), max(0, content.width - bounds.width)), y: point.y)
        }
        return CGPoint(x: point.x, y: min(max(0, point.y), max(0, content.height - bounds.height)))
    }

    /// Builds the pages on screen and one screen on each side; pages that left that band give their views back.
    private func layoutCells() {
        let bounds = content.convert(scrollView.bounds, from: scrollView)
        guard bounds.width > 1, bounds.height > 1 else { return }
        let extent = vertical ? bounds.height : bounds.width
        let start = (vertical ? bounds.minY : bounds.minX) - extent
        let end = (vertical ? bounds.maxY : bounds.maxX) + extent
        refreshFrames()
        // Strip pages do not overlap, so in reading-axis order their ends increase too: the first page in the band is
        // found by bisection and the band's pages follow it.
        let vertical = vertical
        func lower(_ frame: CGRect) -> CGFloat { vertical ? frame.minY : frame.minX }
        func upper(_ frame: CGRect) -> CGFloat { vertical ? frame.maxY : frame.maxX }
        var low = 0, high = frames.count
        while low < high {
            let middle = (low + high) / 2
            if upper(frames[middle].frame) > start { high = middle } else { low = middle + 1 }
        }
        let padding = crossAxisPadding
        var wanted: [String: (index: Int, frame: CGRect)] = [:]
        for entry in frames[low...] {
            guard lower(entry.frame) < end else { break }
            guard upper(entry.frame) > start else { continue }
            wanted[entry.id] = (entry.index, entry.frame.offsetBy(dx: vertical ? padding : 0, dy: vertical ? 0 : padding))
        }
        for (id, cell) in cells where wanted[id] == nil {
            cells[id] = nil
            recycle(cell)
        }
        let adjusted = settings.value.hasColorAdjustments
        for (id, entry) in wanted {
            let cell = cells[id] ?? dequeue()
            cells[id] = cell
            if cell.frame != entry.frame { cell.frame = entry.frame }
            cell.isHidden = false
            // Only tiled pages resolve tiles for the viewport; the others keep theirs fixed so a scroll step leaves
            // them alone.
            let viewport = model.artifacts[entry.index]?.tiled == true ? tileViewport : .zero
            cell.configure(.init(index: entry.index, size: entry.frame.size, viewport: viewport, adjusted: adjusted))
        }
    }

    private func dequeue() -> ReaderStripPageView {
        if let cell = spare.popLast() { return cell }
        let cell = ReaderStripPageView(model: model, settings: settings, owner: self)
        content.addSubview(cell)
        return cell
    }

    private func recycle(_ cell: ReaderStripPageView) {
        cell.isHidden = true
        cell.reset()
        if spare.count < 12 {
            spare.append(cell)
        } else {
            cell.removeFromSuperview()
        }
    }

    // MARK: - Scrolling

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        // Updating contentSize can synchronously scroll with the OLD offset. Only lay cells out after the matching
        // compensated offset is installed, or a zoom rebase can recycle the very pages still on screen.
        guard !rebasingZoom, !applying else { return }
        if zooming { layoutCells(); return }
        let point = scrollView.contentOffset
        let bounds = scrollView.bounds
        if abs(point.x - tileViewport.x) > bounds.width / 4 || abs(point.y - tileViewport.y) > bounds.height / 4 {
            tileViewport = point
        }
        layoutCells()
        let now = CACurrentMediaTime()
        if let sample, now - sample.time > 0.001 {
            let elapsed = CGFloat(now - sample.time)
            let measured = CGPoint(x: (point.x - sample.offset.x) / elapsed, y: (point.y - sample.offset.y) / elapsed)
            velocity = CGPoint(x: velocity.x * 0.5 + measured.x * 0.5, y: velocity.y * 0.5 + measured.y * 0.5)
        }
        sample = (point, now)
        report()
    }

    private func report() {
        guard !zooming, !rebasingZoom else { return }
        let point = scrollView.contentOffset
        let moving = scrollView.isTracking || scrollView.isDecelerating
        model.continuousPosition(x: point.x, y: point.y, velocity: moving ? velocity : .zero,
                                 dragging: scrollView.isTracking)
        // A drag or fling describes where it comes to rest (`settle` reports again), not every frame on the way.
        if !moving || UIAccessibility.isVoiceOverRunning {
            scrollView.accessibilityValue = model.readingPositionDescription
        }
    }

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        model.userScrolled()
        model.isUserPaging = true
        sample = (scrollView.contentOffset, CACurrentMediaTime())
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate { settle() }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { settle() }

    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) { settle() }

    /// The strip came to rest: tiles resolve for where it is, every wanted page that is neither shown nor loading
    /// starts again, and the position is saved.
    private func settle() {
        guard !zooming, !rebasingZoom else { return }
        velocity = .zero
        sample = nil
        model.isUserPaging = false
        if tileViewport != scrollView.contentOffset {
            tileViewport = scrollView.contentOffset
            layoutCells()
        }
        report()
        model.refreshResources()
        if !model.autoScroll { Task { await model.persist() } }
    }

    // MARK: - Gestures

    /// A page showing its error keeps its taps for the retry and verification buttons.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard gestureRecognizer is UITapGestureRecognizer else { return true }
        let point = touch.location(in: content)
        return !cells.values.contains { cell in cell.frame.contains(point) && cell.showsFailure }
    }

    @objc private func tapped(_ gesture: UITapGestureRecognizer) {
        onTap(gesture.location(in: view))
    }

    @objc private func doubleTapped(_ gesture: UITapGestureRecognizer) {
        animateZoom(doubleTapZoom, at: gesture.location(in: view))
    }

    // MARK: - Zoom

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { content }

    func scrollViewWillBeginZooming(_ scrollView: UIScrollView, with view: UIView?) {
        guard !rebasingZoom, !steppingZoom else { return }
        // A pinch that interrupts a double tap's zoom takes the camera from where that zoom has got to.
        if let animation = zoomAnimation {
            animation.link.invalidate()
            zoomAnimation = nil
            zoomLocation = nil
        }
        beginZoom()
    }

    private func beginZoom() {
        zooming = true
        model.beginStripZoom()
        model.userScrolled()
        model.isUserPaging = true
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        guard !rebasingZoom else { return }
        centerZoomedContent()
        // A zoom out exposes more of the strip than the old viewport's neighbour band.
        layoutCells()
    }

    func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
        guard !rebasingZoom, !steppingZoom, zoomAnimation == nil else { return }
        finishZoom(scale)
    }

    /// Bakes the scroll view's temporary [scale] into the scene at the new zoom, keeping the content under
    /// [zoomLocation] (the screen centre for a pinch) where it is.
    private func finishZoom(_ scale: CGFloat) {
        // Native zoom offsets include the temporary scale. Resolve the visible centre in the OLD scene first;
        // estimates above it need not scale, so multiplying the old reading-axis offset is not sufficient.
        let location = zoomLocation ?? CGPoint(x: self.view.bounds.midX, y: self.view.bounds.midY)
        zoomLocation = nil
        let current = scrollView.contentOffset
        let oldPadding = crossAxisPadding
        let contentOrigin = content.frame.origin
        let anchor = model.stripAnchor(at: CGPoint(x: current.x / scale, y: current.y / scale),
                                       location: CGPoint(x: location.x / scale, y: location.y / scale))
        rebasingZoom = true
        // Bake the camera into the scene and page sizes in one turn. The viewport never transforms and no cropped
        // screenshot is laid over a second, already-resized copy of the page.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        defer { CATransaction.commit() }
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            UIView.performWithoutAnimation {
                // Publish the scene in the same transaction as the camera reset so hosted pages never see a mix
                // of the previous transform and the resized scene (or the reset transform and the old scene).
                model.setStripZoom(model.stripZoom * scale)
                let origin = model.stripOrigin(of: anchor)
                let along = min(max(0, origin - (vertical ? location.y : location.x)),
                                CGFloat(model.engine.scrollLimit()))
                let cross = (vertical ? current.x - contentOrigin.x : current.y - contentOrigin.y)
                    + crossAxisPadding - oldPadding * scale
                let target = vertical ? CGPoint(x: cross, y: along) : CGPoint(x: along, y: cross)
                scrollView.minimumZoomScale = min(1, scrollView.minimumZoomScale)
                scrollView.maximumZoomScale = max(1, scrollView.maximumZoomScale)
                scrollView.setZoomScale(1, animated: false)
                content.frame.origin = .zero
                zooming = false
                rebasingZoom = false
                tileViewport = target
                layoutStrip(offset: target, animated: false)
                updateZoomLimits()
            }
        }
        settle()
        model.endStripZoom()
    }

    /// Center a temporarily shrunken native camera without changing the scene's reading-axis coordinates/insets.
    private func centerZoomedContent() {
        var center = content.center
        if vertical { center.x = max(scrollView.bounds.width, content.bounds.width * scrollView.zoomScale) / 2 }
        else { center.y = max(scrollView.bounds.height, content.bounds.height * scrollView.zoomScale) / 2 }
        if content.center != center { content.center = center }
    }

    private func updateZoomLimits() {
        scrollView.minimumZoomScale = CGFloat(IosReader.companion.MINIMUM_ZOOM) / model.stripZoom
        scrollView.maximumZoomScale = CGFloat(IosReader.companion.MAXIMUM_ZOOM) / model.stripZoom
    }

    /// A double tap's zoom, stepped on the main thread every frame. `zoom(to:animated:)` leaves the move to the render
    /// server, which drew the hosted pages blank for most of a zoom out on device; each step here lays the cells out
    /// for the frame actually shown.
    private func animateZoom(_ zoom: CGFloat, at location: CGPoint) {
        guard !zooming, !rebasingZoom else { return }
        let from = scrollView.zoomScale
        let to = min(max(zoom / model.stripZoom, scrollView.minimumZoomScale), scrollView.maximumZoomScale)
        guard abs(to - from) > 0.001 else { return }
        beginZoom()
        zoomLocation = location
        let start = scrollView.contentOffset
        // The content under the tap stays under it, unless that would show past the scaled content's edges.
        let point = CGPoint(x: (start.x + location.x) / from, y: (start.y + location.y) / from)
        let size = CGSize(width: scrollView.contentSize.width / from * to,
                          height: scrollView.contentSize.height / from * to)
        var end = CGPoint(x: min(max(0, point.x * to - location.x), max(0, size.width - view.bounds.width)),
                          y: min(max(0, point.y * to - location.y), max(0, size.height - view.bounds.height)))
        // A strip baked below fit contains side padding. Scale that padding along with the native content, then
        // constrain the target to the page's edges so baking the padding away does not move the last frame.
        let extent = vertical ? view.bounds.width : view.bounds.height
        let nativeExtent = vertical ? size.width : size.height
        let pageExtent = extent * model.stripZoom * to
        let lower = crossAxisPadding * to + max(0, extent - nativeExtent) / 2 - max(0, extent - pageExtent) / 2
        let upper = lower + max(0, pageExtent - extent)
        if vertical { end.x = min(max(lower, end.x), upper) }
        else { end.y = min(max(lower, end.y), upper) }
        // Content point c stays at screen point a while offset = c × scale − a. The c and a that give both the start
        // and the end offset make one zoom about a fixed point, so no step needs clamping.
        let focus = CGPoint(x: (end.x - start.x) / (to - from), y: (end.y - start.y) / (to - from))
        let anchor = CGPoint(x: focus.x * from - start.x, y: focus.y * from - start.y)
        guard !UIAccessibility.isReduceMotionEnabled else {
            stepZoom(to, focus: focus, anchor: anchor)
            finishZoom(to)
            return
        }
        let link = CADisplayLink(target: self, selector: #selector(zoomFrame(_:)))
        zoomAnimation = ZoomAnimation(link: link, start: CACurrentMediaTime(), from: from, to: to,
                                      focus: focus, anchor: anchor)
        link.add(to: .main, forMode: .common)
    }

    @objc private func zoomFrame(_ link: CADisplayLink) {
        guard let animation = zoomAnimation else { return link.invalidate() }
        let progress = min(1, (link.targetTimestamp - animation.start) / Self.zoomDuration)
        let eased = CGFloat(1 - pow(1 - progress, 3))
        stepZoom(animation.from + (animation.to - animation.from) * eased, focus: animation.focus,
                 anchor: animation.anchor)
        guard progress >= 1 else { return }
        link.invalidate()
        zoomAnimation = nil
        finishZoom(animation.to)
    }

    private func stepZoom(_ scale: CGFloat, focus: CGPoint, anchor: CGPoint) {
        steppingZoom = true
        defer { steppingZoom = false }
        scrollView.setZoomScale(scale, animated: false)
        scrollView.contentOffset = CGPoint(x: focus.x * scale - anchor.x, y: focus.y * scale - anchor.y)
    }
}
