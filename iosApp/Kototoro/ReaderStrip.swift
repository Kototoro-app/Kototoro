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

/// One page of the strip, hosted in its own `UIHostingController` so pages share the paged reader's page view.
struct ReaderStripPage: View, Equatable {
    let model: SourceReaderModel
    let settings: ReaderSettings
    let index: Int
    let size: CGSize
    /// Where the strip's tiles are resolved for: follows the scroll in steps and when it settles.
    let viewport: CGPoint

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.index == rhs.index && lhs.size == rhs.size && lhs.viewport == rhs.viewport
    }

    var body: some View {
        ReaderPageTile(model: model, settings: settings, index: index, displaySize: size, viewportOffset: viewport)
            .frame(width: size.width, height: size.height)
    }
}

final class ReaderStripController: UIViewController, UIScrollViewDelegate, UIGestureRecognizerDelegate,
    ReaderStripHost {
    private let model: SourceReaderModel
    private let settings: ReaderSettings
    var onTap: (CGPoint) -> Void = { _ in }

    private let scrollView = ReaderStripScrollView()
    private let content = UIView()
    private final class Cell {
        let host: UIHostingController<ReaderStripPage>
        var page: ReaderStripPage
        init(host: UIHostingController<ReaderStripPage>, page: ReaderStripPage) { self.host = host; self.page = page }
    }
    /// Page views by slot identity (chapter-local page), so a page keeps its view while the window changes around it.
    private var cells: [String: Cell] = [:]
    private var spare: [Cell] = []
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
        var width = view.bounds.width, height = view.bounds.height
        for slot in model.slots {
            for placement in slot.placements {
                width = max(width, CGFloat(placement.x + placement.width))
                height = max(height, CGFloat(placement.y + placement.height))
            }
        }
        return CGSize(width: width, height: height)
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
        var wanted: [String: (index: Int, frame: CGRect)] = [:]
        for item in model.identifiedSlots {
            guard let placement = item.slot.placements.first else { continue }
            let frame = CGRect(x: CGFloat(placement.x) + (vertical ? crossAxisPadding : 0),
                               y: CGFloat(placement.y) + (vertical ? 0 : crossAxisPadding),
                               width: CGFloat(placement.width), height: CGFloat(placement.height))
            let lower = vertical ? frame.minY : frame.minX, upper = vertical ? frame.maxY : frame.maxX
            if upper > start && lower < end { wanted[item.id] = (Int(placement.index), frame) }
        }
        for (id, cell) in cells where wanted[id] == nil {
            cells[id] = nil
            recycle(cell)
        }
        for (id, entry) in wanted {
            let cell = cells[id] ?? dequeue()
            cells[id] = cell
            if cell.host.view.frame != entry.frame { cell.host.view.frame = entry.frame }
            cell.host.view.isHidden = false
            let page = ReaderStripPage(model: model, settings: settings, index: entry.index, size: entry.frame.size,
                                       viewport: tileViewport)
            if page != cell.page {
                cell.page = page
                cell.host.rootView = page
                cell.host.view.layoutIfNeeded()
            }
        }
    }

    private func blankPage() -> ReaderStripPage {
        ReaderStripPage(model: model, settings: settings, index: -1, size: .zero, viewport: .zero)
    }

    private func dequeue() -> Cell {
        if let cell = spare.popLast() { return cell }
        let page = blankPage()
        let host = UIHostingController(rootView: page)
        host.safeAreaRegions = []
        host.sizingOptions = []
        host.view.backgroundColor = .clear
        addChild(host)
        content.addSubview(host.view)
        host.didMove(toParent: self)
        return Cell(host: host, page: page)
    }

    private func recycle(_ cell: Cell) {
        cell.host.view.isHidden = true
        cell.page = blankPage()
        cell.host.rootView = cell.page
        if spare.count < 4 {
            spare.append(cell)
        } else {
            cell.host.willMove(toParent: nil)
            cell.host.view.removeFromSuperview()
            cell.host.removeFromParent()
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
        scrollView.accessibilityValue = model.readingPositionDescription
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
        return !cells.values.contains { cell in
            !cell.host.view.isHidden && cell.host.view.frame.contains(point) && model.pageFailures[cell.page.index] != nil
        }
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
