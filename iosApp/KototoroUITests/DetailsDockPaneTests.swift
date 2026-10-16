import XCTest

final class DetailsDockPaneTests: XCTestCase {
    private let geometry = DetailsPaneGeometry(height: 800, bottomInset: 34)

    func testDockDragRevealsPanelBeforeRelease() {
        XCTAssertEqual(geometry.reveal(from: .collapsed, translation: -80), 80)
        XCTAssertEqual(geometry.reveal(from: .collapsed, translation: -240), 240)
        XCTAssertEqual(geometry.reveal(from: .hovered, translation: -100), geometry.hoveredHeight + 100)
    }

    func testReversingDragRetractsPanelWithoutChangingAnchor() {
        XCTAssertEqual(geometry.reveal(from: .collapsed, translation: -150), 150)
        XCTAssertEqual(geometry.reveal(from: .collapsed, translation: -30), 30)
        XCTAssertEqual(geometry.target(from: .collapsed, translation: -30, predictedTranslation: -30), .collapsed)
    }

    func testMaximumExpansionLeavesRoomForDockAbovePanel() {
        let reveal = geometry.reveal(at: .expanded)
        let dockTop = geometry.height - reveal - geometry.dockBottomPadding(reveal: reveal) - 50
        XCTAssertEqual(dockTop, 12)
        XCTAssertGreaterThan(reveal, geometry.hoveredHeight)
    }

    func testDockClearanceInterpolatesFromSafeAreaToPanelGap() {
        XCTAssertEqual(geometry.dockBottomPadding(reveal: 0), 40)
        XCTAssertEqual(geometry.dockBottomPadding(reveal: 40), 26)
        XCTAssertEqual(geometry.dockBottomPadding(reveal: geometry.hoveredHeight), 12)
    }

    func testOneContinuousDragCanReachMaximumExpansion() {
        XCTAssertEqual(geometry.reveal(from: .collapsed, translation: -900), geometry.maximumReveal)
        XCTAssertEqual(geometry.target(from: .collapsed, translation: -740, predictedTranslation: -780), .expanded)
    }

    func testShortFlickCannotSkipHoveredAnchor() {
        XCTAssertEqual(geometry.target(from: .collapsed, translation: -180, predictedTranslation: -1800), .hovered)
        XCTAssertEqual(geometry.target(from: .hovered, translation: -210, predictedTranslation: -300), .expanded)
    }

    func testDownwardDragCanCollapseFromEitherOpenAnchor() {
        XCTAssertEqual(geometry.target(from: .hovered, translation: 350, predictedTranslation: 400), .collapsed)
        XCTAssertEqual(geometry.target(from: .expanded, translation: 680, predictedTranslation: 800), .collapsed)
        XCTAssertEqual(geometry.reveal(from: .expanded, translation: -100), geometry.maximumReveal)
        XCTAssertEqual(geometry.reveal(from: .collapsed, translation: 100), 0)
    }
}
