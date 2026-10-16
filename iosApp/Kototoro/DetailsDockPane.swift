import SwiftUI
import UIKit

enum DetailsPaneAnchor: CaseIterable {
    case collapsed, hovered, expanded
}

/// The dock and panel share one travel distance, including while a gesture is still in progress.
struct DetailsPaneGeometry {
    let height: CGFloat
    let bottomInset: CGFloat

    // Reserve the 50-point dock, its 12-point panel gap, and 12 points above it at maximum expansion.
    var maximumReveal: CGFloat { max(0, height - 74) }
    var hoveredHeight: CGFloat { min(max(360, height * 0.52), max(0, maximumReveal - 120)) }

    func reveal(at anchor: DetailsPaneAnchor) -> CGFloat {
        switch anchor {
        case .collapsed: return 0
        case .hovered: return hoveredHeight
        case .expanded: return maximumReveal
        }
    }

    func reveal(from anchor: DetailsPaneAnchor, translation: CGFloat) -> CGFloat {
        min(maximumReveal, max(0, reveal(at: anchor) - translation))
    }

    func dockBottomPadding(reveal: CGFloat) -> CGFloat {
        let restingPadding = bottomInset + 6
        return restingPadding + (12 - restingPadding) * min(1, max(0, reveal / 80))
    }

    func target(from anchor: DetailsPaneAnchor, translation: CGFloat, predictedTranslation: CGFloat) -> DetailsPaneAnchor {
        let current = reveal(from: anchor, translation: translation)
        // Limit momentum so a short flick opens the hovered panel rather than skipping straight to maximum height.
        let momentum = min(120, max(-120, predictedTranslation - translation))
        let projected = min(maximumReveal, max(0, current - momentum))
        return DetailsPaneAnchor.allCases.min { abs(reveal(at: $0) - projected) < abs(reveal(at: $1) - projected) }
            ?? anchor
    }
}

/// Consume the distance back to the list's top before moving an expanded panel.
/// Ownership stays with the panel after handoff, including when the finger reverses direction.
struct DetailsPaneContentDrag {
    let anchor: DetailsPaneAnchor
    private let scrollDistance: CGFloat
    private(set) var ownsPanel: Bool
    private(set) var translation: CGFloat = 0

    init(anchor: DetailsPaneAnchor, scrollOffset: CGFloat) {
        self.anchor = anchor
        scrollDistance = anchor == .expanded ? max(0, scrollOffset) : 0
        ownsPanel = anchor != .expanded
    }

    mutating func update(translation: CGFloat) {
        guard translation.isFinite else { return }
        let remaining = translation - scrollDistance
        if anchor == .expanded {
            if remaining > 0 { ownsPanel = true }
            self.translation = ownsPanel ? max(0, remaining) : 0
        } else {
            self.translation = translation
        }
    }
}

struct DetailsDockPane<Panel: View, Dock: View>: View {
    @Binding var anchor: DetailsPaneAnchor
    let onDragBegan: () -> Void
    @ViewBuilder var panel: () -> Panel
    @ViewBuilder var dock: () -> Dock
    @GestureState(resetTransaction: Transaction(animation: .spring(response: 0.38, dampingFraction: 0.9)))
    private var dragTranslation: CGFloat = 0
    @State private var contentTranslation: CGFloat = 0

    var body: some View {
        // The safe-area inset must be read outside `ignoresSafeArea`, which zeroes it for the inner reader.
        GeometryReader { outer in
            let bottomInset = outer.safeAreaInsets.bottom
            GeometryReader { proxy in
            let geometry = DetailsPaneGeometry(height: proxy.size.height, bottomInset: bottomInset)
            let reveal = geometry.reveal(from: anchor, translation: dragTranslation + contentTranslation)
            ZStack(alignment: .top) {
                Color.black.opacity(0.16 * min(1, reveal / max(1, geometry.hoveredHeight)))
                    .contentShape(Rectangle())
                    .onTapGesture { anchor = .collapsed }
                    .allowsHitTesting(reveal > 0)
                    .accessibilityHidden(true)

                VStack(spacing: 0) {
                    Button {
                        anchor = anchor == .expanded ? .hovered : .expanded
                    } label: {
                        Capsule().fill(.secondary.opacity(0.5)).frame(width: 36, height: 5)
                            .frame(maxWidth: .infinity).frame(height: 24)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(anchor == .expanded ? "半屏显示" : "展开面板")
                    .accessibilityIdentifier("details.panel.handle")
                    .highPriorityGesture(drag(geometry))

                    panel()
                        // Keep one layout height during the drag and reserve the portion below the screen.
                        .safeAreaPadding(.bottom, max(0, geometry.maximumReveal - geometry.reveal(at: anchor)))
                        .gesture(DetailsPaneContentPan(anchor: anchor) { translation in
                            var transaction = Transaction()
                            transaction.disablesAnimations = true
                            withTransaction(transaction) { contentTranslation = translation }
                            onDragBegan()
                        } onEnded: { initialAnchor, translation, velocity in
                            anchor = geometry.target(from: initialAnchor, translation: translation,
                                                     predictedTranslation: translation + velocity * 0.2)
                            contentTranslation = 0
                        } onCancelled: {
                            withAnimation(.spring(response: 0.38, dampingFraction: 0.9)) {
                                contentTranslation = 0
                            }
                        })
                }
                .frame(height: max(1, geometry.maximumReveal), alignment: .top)
                .background(.regularMaterial)
                .clipShape(UnevenRoundedRectangle(topLeadingRadius: 24, topTrailingRadius: 24))
                .offset(y: geometry.height - reveal)
                .allowsHitTesting(reveal > 0)
                .accessibilityHidden(reveal == 0)

                dock()
                    // A drag consumes the control's touch; releasing must not also activate reading or a tab.
                    // Attached before the full-height frame so the empty area above the dock stays untouchable.
                    .highPriorityGesture(drag(geometry))
                    .padding(.bottom, geometry.dockBottomPadding(reveal: reveal))
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .offset(y: -reveal)
                    .allowsHitTesting(true)
            }
            .clipped()
            .animation(.spring(response: 0.38, dampingFraction: 0.9), value: anchor)
            .onChange(of: anchor) { _, _ in contentTranslation = 0 }
            }
            .ignoresSafeArea(edges: .bottom)
        }
    }

    private func drag(_ geometry: DetailsPaneGeometry) -> some Gesture {
        // Global coordinates keep the gesture stable while its dock/handle moves with the finger.
        DragGesture(minimumDistance: 8, coordinateSpace: .global)
            .updating($dragTranslation) { value, translation, transaction in
                guard abs(value.translation.height) > abs(value.translation.width) else { return }
                transaction.animation = nil
                translation = value.translation.height
            }
            .onChanged { value in
                guard abs(value.translation.height) > abs(value.translation.width) else { return }
                onDragBegan()
            }
            .onEnded { value in
                guard abs(value.translation.height) > abs(value.translation.width) else { return }
                anchor = geometry.target(from: anchor, translation: value.translation.height,
                                         predictedTranslation: value.predictedEndTranslation.height)
            }
    }
}

/// Share only the touched vertical scroll view's pan. UIKit cancels row taps as soon as a drag begins;
/// the scroll view keeps its own delegate, recognizer, inertia and horizontal swipe actions.
private struct DetailsPaneContentPan: UIGestureRecognizerRepresentable {
    let anchor: DetailsPaneAnchor
    let onChanged: (CGFloat) -> Void
    let onEnded: (DetailsPaneAnchor, CGFloat, CGFloat) -> Void
    let onCancelled: () -> Void

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.maximumNumberOfTouches = 1
        pan.delegate = context.coordinator
        return pan
    }

    func handleUIGestureRecognizerAction(_ pan: UIPanGestureRecognizer, context: Context) {
        let coordinator = context.coordinator
        switch pan.state {
        case .began:
            coordinator.drag = DetailsPaneContentDrag(anchor: anchor, scrollOffset: coordinator.initialScrollOffset)
            coordinator.trackScroll()
            fallthrough
        case .changed:
            // Window coordinates remain stable as both the panel and the touched list move.
            coordinator.drag?.update(translation: pan.translation(in: nil).y)
            if let drag = coordinator.drag, drag.ownsPanel {
                coordinator.pinScrollToTop()
                onChanged(drag.translation)
            }
        case .ended:
            if var drag = coordinator.drag, drag.ownsPanel {
                drag.update(translation: pan.translation(in: nil).y)
                coordinator.pinScrollToTop()
                onEnded(drag.anchor, drag.translation, pan.velocity(in: nil).y)
            }
            coordinator.reset()
        case .cancelled, .failed:
            if coordinator.drag?.ownsPanel == true { onCancelled() }
            coordinator.reset()
        default: break
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        weak var scrollView: UIScrollView?
        var initialScrollOffset: CGFloat = 0
        var drag: DetailsPaneContentDrag?
        private var scrollObservation: NSKeyValueObservation?

        func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard recognizer.numberOfTouches == 0 else { return false }
            reset()
            var view = touch.view
            while let current = view, current !== recognizer.view {
                if let scroll = current as? UIScrollView,
                   scroll.alwaysBounceVertical || scroll.contentSize.height > scroll.bounds.height + 1 {
                    scrollView = scroll
                    initialScrollOffset = max(0, scroll.contentOffset.y + scroll.adjustedContentInset.top)
                    break
                }
                view = current.superview
            }
            return true
        }

        func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
            guard let pan = recognizer as? UIPanGestureRecognizer else { return false }
            var motion = pan.translation(in: nil)
            if motion == .zero { motion = pan.velocity(in: nil) }
            return abs(motion.y) > abs(motion.x) * 1.2
        }

        func gestureRecognizer(_ recognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            other === scrollView?.panGestureRecognizer
        }

        func trackScroll() {
            scrollObservation = scrollView?.observe(\.contentOffset, options: [.new]) { [weak self] _, _ in
                self?.pinScrollToTop()
            }
        }

        func pinScrollToTop() {
            guard drag?.ownsPanel == true, let scrollView else { return }
            let top = -scrollView.adjustedContentInset.top
            if abs(scrollView.contentOffset.y - top) > 0.5 {
                scrollView.setContentOffset(CGPoint(x: scrollView.contentOffset.x, y: top), animated: false)
            }
        }

        func reset() {
            scrollObservation = nil
            scrollView = nil
            initialScrollOffset = 0
            drag = nil
        }
    }
}
