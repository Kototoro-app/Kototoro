import XCTest

final class BrowserVerificationStateTests: XCTestCase {
    func testExistingCookieNeverCompletesASpinningChallenge() {
        for status in [200, 403, 503] {
            let state = BrowserVerificationState(status: status, challengePresent: true)
            XCTAssertTrue(state.challengeActive)
            XCTAssertFalse(state.verified(hasClearance: true))
        }
    }

    func testCookieBeforeNavigationOrScriptCompletionIsNotSuccess() {
        XCTAssertFalse(BrowserVerificationState(status: nil, challengePresent: nil).verified(hasClearance: true))
        XCTAssertFalse(BrowserVerificationState(status: 200, challengePresent: nil).verified(hasClearance: true))
        XCTAssertFalse(BrowserVerificationState(status: 403, challengePresent: false).verified(hasClearance: true))
        XCTAssertFalse(BrowserVerificationState(status: 503, challengePresent: false).verified(hasClearance: true))
    }

    func testOnlyANormalDocumentWithClearanceIsVerified() {
        let state = BrowserVerificationState(status: 200, challengePresent: false)
        XCTAssertFalse(state.challengeActive)
        XCTAssertFalse(state.verified(hasClearance: false))
        XCTAssertTrue(state.verified(hasClearance: true))
        XCTAssertFalse(BrowserVerificationState(status: 500, challengePresent: false).verified(hasClearance: true))
    }
}
