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

    private let scrollView = UIScrollView()
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
    private var pinchAnchor = CGPoint.zero
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
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:)))
        pinch.delegate = self
        for gesture in [doubleTap, singleTap, pinch] { scrollView.addGestureRecognizer(gesture) }
        model.strip = self
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard view.bounds.width > 1, view.bounds.height > 1, scrollView.transform.isIdentity else { return }
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
        let name = model.stripZoom > 1 ? "还原缩放" : "放大"
        if name != zoomActionName {
            zoomActionName = name
            scrollView.accessibilityCustomActions = [UIAccessibilityCustomAction(name: name) { [weak self] _ in
                guard let self else { return false }
                self.commitZoom(self.model.stripZoom > 1 ? 1 : 2,
                                at: CGPoint(x: self.view.bounds.midX, y: self.view.bounds.midY))
                return true
            }]
        }
    }

    func detach() {
        if model.strip === self { model.strip = nil }
    }

    // MARK: - Layout

    func layoutStrip(origin: CGFloat, animated: Bool) {
        var point = scrollView.contentOffset
        if vertical { point.y = origin } else { point.x = origin }
        layoutStrip(offset: point, animated: animated)
    }

    func layoutStrip(offset target: CGPoint?, animated: Bool) {
        guard isViewLoaded else { return }
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
        let bounds = scrollView.bounds
        guard bounds.width > 1, bounds.height > 1 else { return }
        let extent = vertical ? bounds.height : bounds.width
        let start = (vertical ? bounds.minY : bounds.minX) - extent
        let end = (vertical ? bounds.maxY : bounds.maxX) + extent
        var wanted: [String: (index: Int, frame: CGRect)] = [:]
        for item in model.identifiedSlots {
            guard let placement = item.slot.placements.first else { continue }
            let frame = CGRect(x: CGFloat(placement.x), y: CGFloat(placement.y),
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
        let point = scrollView.contentOffset
        let bounds = scrollView.bounds
        if abs(point.x - tileViewport.x) > bounds.width / 4 || abs(point.y - tileViewport.y) > bounds.height / 4 {
            tileViewport = point
        }
        layoutCells()
        guard !applying else { return }
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

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        gestureRecognizer is UIPinchGestureRecognizer
    }

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
        commitZoom(model.stripZoom > 1 ? 1 : 2, at: gesture.location(in: view))
    }

    /// The pinch previews as a transform about where it started; on release the strip is laid out again at the new
    /// zoom (`SourceReaderModel.setStripZoom`), so pages decode and tile at the size shown.
    @objc private func pinched(_ gesture: UIPinchGestureRecognizer) {
        let zoom = model.stripZoom
        let minimum = CGFloat(IosReader.companion.MINIMUM_ZOOM) / zoom
        let maximum = CGFloat(IosReader.companion.MAXIMUM_ZOOM) / zoom
        let scale = min(max(gesture.scale, minimum), maximum)
        switch gesture.state {
        case .began:
            pinchAnchor = gesture.location(in: view)
            scrollView.transform = preview(scale)
        case .changed:
            scrollView.transform = preview(scale)
        case .ended:
            scrollView.transform = .identity
            scrollView.frame = view.bounds
            commitZoom(zoom * scale, at: pinchAnchor)
        default:
            scrollView.transform = .identity
            scrollView.frame = view.bounds
        }
    }

    private func preview(_ scale: CGFloat) -> CGAffineTransform {
        let dx = pinchAnchor.x - view.bounds.midX, dy = pinchAnchor.y - view.bounds.midY
        return CGAffineTransform(translationX: dx, y: dy).scaledBy(x: scale, y: scale).translatedBy(x: -dx, y: -dy)
    }

    /// Lays the strip out at [zoom] and scrolls so the content under [location] (a point on screen) stays put.
    private func commitZoom(_ zoom: CGFloat, at location: CGPoint) {
        model.userScrolled()
        let previous = model.stripZoom
        model.setStripZoom(zoom)
        let ratio = model.stripZoom / previous
        guard ratio != 1 else { return }
        let current = scrollView.contentOffset
        let x = (current.x + location.x) * ratio - location.x
        let y = (current.y + location.y) * ratio - location.y
        let limit = CGFloat(model.engine.scrollLimit())
        let size = view.bounds.size
        let target = vertical
            ? CGPoint(x: min(max(0, x), size.width * (model.stripZoom - 1)), y: min(max(0, y), limit))
            : CGPoint(x: min(max(0, x), limit), y: min(max(0, y), size.height * (model.stripZoom - 1)))
        tileViewport = target
        layoutStrip(offset: target, animated: false)
    }
}
