import XCTest

final class BatchSelectionTests: XCTestCase {
    func testSelectingSearchResultsPreservesChoicesOutsideTheFilter() {
        XCTAssertEqual(BatchSelection.toggling(visible: ["visible-a", "visible-b"], in: ["hidden", "visible-a"]),
                       ["hidden", "visible-a", "visible-b"])
    }

    func testDeselectingSearchResultsLeavesHiddenChoicesSelected() {
        XCTAssertEqual(BatchSelection.toggling(visible: ["visible-a", "visible-b"],
                                             in: ["hidden", "visible-a", "visible-b"]), ["hidden"])
    }

    func testEmptySearchResultsNeverSelectOrClearHiddenWorks() {
        XCTAssertEqual(BatchSelection.toggling(visible: [], in: ["hidden"]), ["hidden"])
        XCTAssertEqual(BatchSelection.toggling(visible: [], in: []), [])
    }
}
