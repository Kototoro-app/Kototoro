import XCTest
import Foundation

final class GoogleDriveSyncTests: XCTestCase {
    func testOAuthUsesPKCEAndRejectsForgedCallbacks() throws {
        let config = GoogleDriveOAuth(clientID: "123-ios.apps.googleusercontent.com")
        XCTAssertEqual(config.scheme, "com.googleusercontent.apps.123-ios")
        // RFC 7636 Appendix B vector.
        XCTAssertEqual(GoogleDriveOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
                       "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let url = config.authorizationURL(state: "expected", verifier: "verifier")
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(query.first { $0.name == "code_challenge_method" }?.value, "S256")
        XCTAssertTrue(query.first { $0.name == "scope" }!.value!.contains(GoogleDriveOAuth.scope))
        XCTAssertEqual(try config.code(from: URL(string: "\(config.redirectURI)?state=expected&code=ok")!, state: "expected"), "ok")
        for callback in ["\(config.redirectURI)?state=wrong&code=ok", "\(config.redirectURI)?code=ok",
                         "\(config.redirectURI)?state=expected&state=expected&code=ok",
                         "\(config.redirectURI)?state=expected&error=access_denied",
                         "evil:/oauth2redirect?state=expected&code=ok",
                         "\(config.scheme)://evil/oauth2redirect?state=expected&code=ok"] {
            XCTAssertThrowsError(try config.code(from: URL(string: callback)!, state: "expected"))
        }
    }

    func testFormEscapesTokenCharacters() {
        XCTAssertEqual(String(data: GoogleDriveOAuth.form(["code": "a+b&c=d /?"]), encoding: .utf8),
                       "code=a%2Bb%26c%3Dd%20%2F%3F")
    }

    @MainActor
    func testConfiguredBuildShowsEnabledConnectWithoutStartingAuthorization() {
        let app = XCUIApplication()
        app.launch()
        app.openSettings("settings.backup", "settings.googleDrive")
        XCTAssertTrue(app.buttons["googleDrive.connect"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["googleDrive.unconfigured"].exists)
        XCTAssertTrue(app.buttons["googleDrive.connect"].isEnabled)
    }

    @MainActor
    func testDeleteButtonRemovesCategory() {
        let app = XCUIApplication(); app.launch()
        let add = app.buttons["category.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: add)
        waitForExpectations(timeout: 30)
        add.tap()
        let field = app.textFields["category.title"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        let name = "删除\(UUID().uuidString.prefix(6))"
        field.tap(); field.typeText(name)
        app.buttons["category.save"].tap()
        XCTAssertTrue(field.waitForNonExistence(timeout: 10))
        app.buttons["category.manage"].tap()
        let row = app.switches.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        XCTAssertTrue(app.navigationBars["管理分类"].waitForExistence(timeout: 10))
        for _ in 0..<20 { if row.exists && row.isHittable { break }; app.collectionViews.firstMatch.swipeUp() }
        XCTAssertTrue(row.isHittable, app.debugDescription)
        let rowId = String(row.identifier.dropFirst("category.visibility.".count))
        let del = app.buttons["category.delete.\(rowId)"]
        XCTAssertTrue(del.waitForExistence(timeout: 5), app.debugDescription)
        del.tap()
        app.buttons["仅删除分类"].tap()
        XCTAssertTrue(row.waitForNonExistence(timeout: 10))
    }
}
