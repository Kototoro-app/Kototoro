import XCTest

final class BackgroundSyncScheduleTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_000_000)

    func testWebDavNeedsServerAndIntervalAndWaitsAfterUploadOrFailure() {
        XCTAssertNil(BackgroundSyncSchedule.webDavDue(intervalHours: 0, server: "https://dav", lastUpload: 0, lastFailure: 0))
        XCTAssertNil(BackgroundSyncSchedule.webDavDue(intervalHours: 6, server: "", lastUpload: 0, lastFailure: 0))
        // Never uploaded: due at once.
        XCTAssertEqual(BackgroundSyncSchedule.webDavDue(intervalHours: 6, server: "https://dav", lastUpload: 0, lastFailure: 0),
                       Date(timeIntervalSince1970: 0))
        let last = now.timeIntervalSince1970
        XCTAssertEqual(BackgroundSyncSchedule.webDavDue(intervalHours: 24, server: "https://dav", lastUpload: last, lastFailure: 0),
                       now.addingTimeInterval(86_400))
        // A failure after a long-ago upload waits ten minutes, not a whole interval.
        XCTAssertEqual(BackgroundSyncSchedule.webDavDue(intervalHours: 6, server: "https://dav", lastUpload: 1, lastFailure: last),
                       now.addingTimeInterval(600))
    }

    func testDriveNeedsAConnectedAccountAndBacksOffAfterAnAttempt() {
        XCTAssertNil(BackgroundSyncSchedule.driveDue(intervalHours: 6, connected: false, lastSync: nil, lastAttempt: nil))
        XCTAssertNil(BackgroundSyncSchedule.driveDue(intervalHours: 0, connected: true, lastSync: nil, lastAttempt: nil))
        XCTAssertEqual(BackgroundSyncSchedule.driveDue(intervalHours: 6, connected: true, lastSync: now, lastAttempt: now),
                       now.addingTimeInterval(6 * 3600))
        // The sync failed (no new success) at the last attempt: half an hour later.
        XCTAssertEqual(BackgroundSyncSchedule.driveDue(intervalHours: 6, connected: true,
                                                       lastSync: now.addingTimeInterval(-86_400), lastAttempt: now),
                       now.addingTimeInterval(1800))
    }

    func testRequestTakesTheSoonestAtLeastAMinuteAway() {
        XCTAssertNil(BackgroundSyncSchedule.request([nil, nil], now: now))
        XCTAssertEqual(BackgroundSyncSchedule.request([now.addingTimeInterval(7200), now.addingTimeInterval(3600)], now: now),
                       now.addingTimeInterval(3600))
        XCTAssertEqual(BackgroundSyncSchedule.request([Date(timeIntervalSince1970: 0), nil], now: now),
                       now.addingTimeInterval(60))
    }
}
