import XCTest

final class LibraryCollectionIndexTests: XCTestCase {
    private struct Entry {
        let id: Int
        let kind: String
        let categories: [Int32]
    }

    func testSixThousandWorksAreInspectedOnceAcrossRepeatedSpaceAndCategoryQueries() {
        let entries = (0..<6000).map {
            Entry(id: $0, kind: $0 < 5991 ? "MANGA" : "VIDEO", categories: [Int32($0 % 70)])
        }
        var kindReads = 0
        var membershipReads = 0
        let index = LibraryCollectionIndex(entries: entries, mediaKind: {
            kindReads += 1
            return $0.kind
        }, categoryIDs: {
            membershipReads += 1
            return $0.categories
        })

        // Category bars can be evaluated many times while the same favourites snapshot is displayed.
        for _ in 0..<100 {
            let video = index.slice(mediaKind: "VIDEO")
            XCTAssertEqual(video.entries.map(\.id), Array(5991..<6000))
            XCTAssertEqual((0..<70).reduce(0) { $0 + (video.categoryCounts[Int32($1)] ?? 0) }, 9)
            XCTAssertEqual(index.slice(mediaKind: nil).entries.count, 6000)
            XCTAssertEqual(index.availableKinds, ["MANGA", "VIDEO"])
        }
        XCTAssertEqual(kindReads, 6000)
        XCTAssertEqual(membershipReads, 6000)
    }

    func testCountsRespectSpaceAndDeduplicateMembershipsWithoutReorderingWorks() {
        let index = makeIndex([
            Entry(id: 3, kind: "VIDEO", categories: [1, 1, 2]),
            Entry(id: 1, kind: "MANGA", categories: [1, 3]),
            Entry(id: 2, kind: "VIDEO", categories: [2]),
        ])
        XCTAssertEqual(index.slice(mediaKind: nil).entries.map(\.id), [3, 1, 2])
        XCTAssertEqual(index.slice(mediaKind: nil).categoryCounts, [1: 2, 2: 2, 3: 1])
        XCTAssertEqual(index.slice(mediaKind: "VIDEO").entries.map(\.id), [3, 2])
        XCTAssertEqual(index.slice(mediaKind: "VIDEO").categoryCounts, [1: 1, 2: 2])
        XCTAssertTrue(index.slice(mediaKind: "NOVEL").entries.isEmpty)
        XCTAssertTrue(index.slice(mediaKind: "NOVEL").categoryCounts.isEmpty)
    }

    func testReplacingSnapshotRemovesOldMembershipsAndKeepsPreviouslyReturnedSliceStable() {
        var index = makeIndex([Entry(id: 1, kind: "VIDEO", categories: [1])])
        let previous = index.slice(mediaKind: "VIDEO")
        index = makeIndex([Entry(id: 2, kind: "MANGA", categories: [2])])
        XCTAssertEqual(previous.entries.map(\.id), [1])
        XCTAssertTrue(index.slice(mediaKind: "VIDEO").entries.isEmpty)
        XCTAssertEqual(index.availableKinds, ["MANGA"])
        XCTAssertEqual(index.slice(mediaKind: nil).categoryCounts, [2: 1])
    }

    private func makeIndex(_ entries: [Entry]) -> LibraryCollectionIndex<Entry> {
        LibraryCollectionIndex(entries: entries, mediaKind: { $0.kind }, categoryIDs: { $0.categories })
    }
}
