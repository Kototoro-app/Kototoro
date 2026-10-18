import XCTest

final class DetailsChapterPreviewTests: XCTestCase {
    func testUnreadWorkStartsAtTheBeginningOfTheDisplayedOrder() {
        XCTAssertEqual(preview(current: nil), ["0", "1", "2", "3", "4"])
        let reversed = (0..<10).reversed().map(String.init)
        XCTAssertEqual(Array(DetailsChapterPreview.items(reversed, currentID: nil, id: { $0 })),
                       ["9", "8", "7", "6", "5"])
    }

    func testCurrentChapterStaysInTheMiddleAndAtTheBookEnds() {
        XCTAssertEqual(preview(current: "5"), ["3", "4", "5", "6", "7"])
        XCTAssertEqual(preview(current: "0"), ["0", "1", "2", "3", "4"])
        XCTAssertEqual(preview(current: "9"), ["5", "6", "7", "8", "9"])
    }

    func testMissingCurrentChapterUsesTheSelectedBranchWithoutAddingOtherChapters() {
        let branch = ["b1", "b2"]
        XCTAssertEqual(Array(DetailsChapterPreview.items(branch, currentID: "a1", id: { $0 })), branch)
        XCTAssertTrue(DetailsChapterPreview.items([String](), currentID: "a1", id: { $0 }).isEmpty)
    }

    func testVideoPreviewCanShowEightEpisodesAndRejectsEmptyLimits() {
        let episodes = (0..<100).map(String.init)
        XCTAssertEqual(Array(DetailsChapterPreview.items(episodes, currentID: "50", id: { $0 }, limit: 8)),
                       (46..<54).map(String.init))
        XCTAssertTrue(DetailsChapterPreview.items(episodes, currentID: nil, id: { $0 }, limit: 0).isEmpty)
    }

    private func preview(current: String?) -> [String] {
        Array(DetailsChapterPreview.items((0..<10).map(String.init), currentID: current, id: { $0 }))
    }
}
