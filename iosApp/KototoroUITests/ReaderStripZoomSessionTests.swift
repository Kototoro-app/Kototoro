import XCTest

@MainActor
final class ReaderStripZoomSessionTests: XCTestCase {
    func testLateImagesCannotPublishGeometryUntilTheFinalViewportIsInstalled() async {
        let session = ReaderStripZoomSession()
        var events: [String] = []
        session.begin()
        let waiting = expectation(description: "image completion reached the zoom barrier")
        let image = Task {
            waiting.fulfill()
            await session.waitForLayout()
            events.append("image geometry")
        }
        await fulfillment(of: [waiting], timeout: 1)
        await Task.yield()
        XCTAssertTrue(events.isEmpty)
        events.append("final viewport")
        session.end()
        await image.value
        XCTAssertEqual(events, ["final viewport", "image geometry"])
    }

    func testAnInterruptingPinchKeepsTheSameSessionAndDetachReleasesAllWaiters() async {
        let session = ReaderStripZoomSession()
        session.begin()
        let entered = expectation(description: "both image loads reached the zoom barrier")
        entered.expectedFulfillmentCount = 2
        var released = 0
        let first = Task {
            entered.fulfill()
            await session.waitForLayout()
            released += 1
        }
        session.begin()
        let second = Task {
            entered.fulfill()
            await session.waitForLayout()
            released += 1
        }
        await fulfillment(of: [entered], timeout: 1)
        await Task.yield()
        XCTAssertTrue(session.isActive)
        XCTAssertEqual(released, 0)
        session.end()
        await first.value
        await second.value
        XCTAssertEqual(released, 2)
        XCTAssertFalse(session.isActive)
        await session.waitForLayout()
    }

    func testCancellingAnImageLoadDoesNotWaitForTheGestureToFinish() async {
        let session = ReaderStripZoomSession()
        session.begin()
        let entered = expectation(description: "image load entered")
        let cancelled = expectation(description: "cancelled load released")
        let image = Task {
            entered.fulfill()
            await session.waitForLayout()
            XCTAssertTrue(Task.isCancelled)
            cancelled.fulfill()
        }
        await fulfillment(of: [entered], timeout: 1)
        image.cancel()
        await fulfillment(of: [cancelled], timeout: 1)
        session.end()
        await image.value
    }
}
