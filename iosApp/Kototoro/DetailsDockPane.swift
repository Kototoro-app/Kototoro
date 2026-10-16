import SwiftUI

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

struct DetailsDockPane<Panel: View, Dock: View>: View {
    @Binding var anchor: DetailsPaneAnchor
    let onDragBegan: () -> Void
    @ViewBuilder var panel: () -> Panel
    @ViewBuilder var dock: () -> Dock
    @GestureState(resetTransaction: Transaction(animation: .spring(response: 0.38, dampingFraction: 0.9)))
    private var dragTranslation: CGFloat = 0

    var body: some View {
        // The safe-area inset must be read outside `ignoresSafeArea`, which zeroes it for the inner reader.
        GeometryReader { outer in
            let bottomInset = outer.safeAreaInsets.bottom
            GeometryReader { proxy in
            let geometry = DetailsPaneGeometry(height: proxy.size.height, bottomInset: bottomInset)
            let reveal = geometry.reveal(from: anchor, translation: dragTranslation)
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
                        // The panel keeps one fixed layout height while it is dragged, so its content does not
                        // reflow (and visibly jitter); this padding keeps the last rows above the visible bottom.
                        .safeAreaPadding(.bottom, max(0, geometry.maximumReveal - geometry.reveal(at: anchor)))
                        .scrollDisabled(anchor != .expanded || dragTranslation != 0)
                        .simultaneousGesture(drag(geometry), isEnabled: anchor != .expanded)
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
