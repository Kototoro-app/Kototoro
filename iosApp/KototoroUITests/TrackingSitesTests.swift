import XCTest

final class TrackingSitesTests: XCTestCase {
    @MainActor
    func testBangumiCustomConnectionRejectsInsecureAddresses() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-tracking.preferredSite", "5"]
        app.launch()
        app.mainTab("设置").tap()
        app.buttons["settings.tracking"].tap()
        let connection = app.buttons["tracking.connection"]
        XCTAssertTrue(connection.waitForExistence(timeout: 20))
        if !connection.isHittable { app.swipeUp() }
        connection.tap()
        app.buttons["tracking.bangumi.endpoint"].tap()
        app.buttons["自定义"].tap()
        let address = app.textFields["tracking.bangumi.customEndpoint"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        address.tap()
        let current = address.value as? String ?? ""
        address.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
        address.typeText("http://insecure.invalid")
        app.buttons["使用此接口"].tap()
        XCTAssertTrue(app.staticTexts["请输入完整的 HTTPS 接口地址。"].waitForExistence(timeout: 5))
        app.buttons["tracking.bangumi.endpoint"].tap()
        app.buttons["Bangumi 官方"].tap()
    }

    @MainActor
    func testSevenAccountsAreDiscoverableAndConnectionDoesNotRequireCredentialsForBrowsing() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        app.mainTab("浏览").tap()
        let entry = app.buttons["sources.tracking"]
        XCTAssertTrue(entry.waitForExistence(timeout: 20))
        entry.tap()
        let tabs = app.segmentedControls["tracking.section"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 20))
        XCTAssertTrue(app.textFields["tracking.query"].exists)
        tabs.buttons["我的账号"].tap()
        for id in [5, 2, 3, 4, 1, 6, 7] {
            let account = app.buttons["tracking.account.\(id)"]
            if !account.isHittable { app.swipeUp() }
            XCTAssertTrue(account.exists, "Missing site \(id)")
        }
        app.swipeDown()
        let bangumi = app.buttons["tracking.account.5"]
        if !bangumi.isHittable { app.swipeDown() }
        bangumi.tap()
        XCTAssertTrue(app.navigationBars["Bangumi"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["tracking.connect"].exists)
        app.buttons["使用访问令牌连接"].tap()
        XCTAssertTrue(app.secureTextFields["tracking.token"].waitForExistence(timeout: 5))
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "追踪账号连接"; image.lifetime = .keepAlways; add(image)
    }

    @MainActor
    func testCatalogFiltersWebsitesByMediaTypeAndKeepsSearchVisible() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        app.mainTab("设置").tap()
        app.buttons["settings.tracking"].tap()
        XCTAssertTrue(app.buttons["tracking.mediaKind"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["tracking.site.7"].exists, "SIMKL cannot search manga")
        app.buttons["tracking.mediaKind"].tap()
        app.buttons["动画 / 影视"].tap()
        XCTAssertFalse(app.buttons["tracking.site.6"].exists, "MangaUpdates cannot search video")
        let query = app.textFields["tracking.query"]
        XCTAssertTrue(query.exists)
        query.tap(); query.typeText("test-search")
        app.buttons["tracking.search"].tap()
        XCTAssertTrue(app.buttons["清除搜索，浏览榜单"].waitForExistence(timeout: 5))
        app.buttons["清除搜索，浏览榜单"].tap()
        XCTAssertTrue(app.segmentedControls["tracking.ranking"].waitForExistence(timeout: 5))
        XCTAssertEqual(query.value as? String, "搜索作品名称")
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "追踪网站发现与类型筛选"; image.lifetime = .keepAlways; add(image)
    }
}
