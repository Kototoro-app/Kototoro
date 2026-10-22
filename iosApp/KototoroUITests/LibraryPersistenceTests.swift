import XCTest

final class LibraryPersistenceTests: XCTestCase {
    @MainActor
    func testRecordsNavigationTitleIsCentered() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        app.mainTab("记录").tap()
        let title = app.navigationBars.staticTexts["记录"]
        XCTAssertTrue(title.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(title.frame.midX, app.frame.midX, accuracy: 2)
        XCTAssertTrue(app.buttons["space.menu"].exists)
    }

    @MainActor
    func testRecordsFiltersCompactStatsAndDownloadList() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.mainTab("记录").waitForExistence(timeout: 30))
        app.mainTab("记录").tap()
        let title = app.navigationBars.staticTexts["记录"]
        XCTAssertTrue(title.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(title.frame.midX, app.frame.midX, accuracy: 2)
        XCTAssertFalse(app.buttons["records.more"].exists)
        XCTAssertTrue(app.staticTexts["本期概览"].waitForExistence(timeout: 20), app.debugDescription)
        let compactTop = app.buttons["timeline.filter.all"].frame.minY
        app.selectRecordsFilter("reading")
        XCTAssertTrue(app.staticTexts["stats.duration"].waitForExistence(timeout: 10))
        let chart = app.descendants(matching: .any)["stats.activity"]
        XCTAssertFalse(chart.exists)
        XCTAssertLessThanOrEqual(abs(app.buttons["timeline.filter.reading"].frame.minY - compactTop), 16)
        app.buttons["stats.expand"].tap()
        XCTAssertTrue(chart.waitForExistence(timeout: 5))
        app.buttons["stats.expand"].tap()
        XCTAssertTrue(chart.waitForNonExistence(timeout: 5))
        let reading = XCTAttachment(screenshot: app.screenshot())
        reading.name = "Compact reading statistics"
        reading.lifetime = .keepAlways
        add(reading)
        for filter in ["updates", "downloads", "all"] {
            app.selectRecordsFilter(filter)
            XCTAssertTrue(app.buttons["timeline.filter.\(filter)"].isSelected)
            XCTAssertTrue(title.exists)
            let excluded = filter == "updates" ? ["timeline.read-", "timeline.download."]
                : filter == "downloads" ? ["timeline.read-", "timeline.update-"] : []
            for prefix in excluded {
                XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch.exists)
            }
        }
        app.selectRecordsFilter("downloads")
        let entry = app.buttons["records.downloads.queue"]
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        let downloads = XCTAttachment(screenshot: app.screenshot())
        downloads.name = "Download timeline and task list entry"
        downloads.lifetime = .keepAlways
        add(downloads)
        entry.tap()
        XCTAssertTrue(app.navigationBars["下载"].waitForExistence(timeout: 10), app.debugDescription)
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(title.waitForExistence(timeout: 10), app.debugDescription)
    }

    @MainActor
    func testCategoryOrderAndVisibilitySurviveRestartAndCancelDoesNotWrite() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launch()
        let suffix = UUID().uuidString.prefix(6)
        let first = "待读 \(suffix)", second = "在读 \(suffix)"
        for title in [first, second] {
            let add = app.buttons["category.add"]
            XCTAssertTrue(add.waitForExistence(timeout: 30))
            expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: add)
            waitForExpectations(timeout: 30)
            add.tap()
            let field = app.textFields["category.title"]
            XCTAssertTrue(field.waitForExistence(timeout: 10))
            field.tap(); field.typeText(title)
            app.buttons["category.save"].tap()
            XCTAssertTrue(field.waitForNonExistence(timeout: 10))
        }

        func visibility(_ title: String) -> XCUIElement {
            app.switches.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@",
                                              "category.visibility.", title)).firstMatch
        }
        func openManager() {
            app.buttons["category.manage"].tap()
            XCTAssertTrue(app.navigationBars["管理分类"].waitForExistence(timeout: 10))
            for _ in 0..<20 {
                if visibility(second).isHittable { break }
                app.collectionViews.firstMatch.swipeUp()
            }
            XCTAssertTrue(visibility(first).isHittable && visibility(second).isHittable, app.debugDescription)
        }
        func toggleVisibility(_ title: String) {
            // The editable row's accessibility frame includes the label and reorder handle; tap the switch thumb.
            visibility(title).coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)).tap()
        }

        openManager()
        toggleVisibility(first)
        XCTAssertEqual(visibility(first).value as? String, "0")
        app.buttons["category.manage.cancel"].tap()
        XCTAssertTrue(app.navigationBars["管理分类"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(chip(first, in: app).exists)
        openManager()
        XCTAssertEqual(visibility(first).value as? String, "1", "Cancel must not write the hidden flag")
        let firstId = String(visibility(first).identifier.dropFirst("category.visibility.".count))
        let secondId = String(visibility(second).identifier.dropFirst("category.visibility.".count))
        let firstCell = app.cells.containing(.switch, identifier: "category.visibility.\(firstId)").firstMatch
        let secondCell = app.cells.containing(.switch, identifier: "category.visibility.\(secondId)").firstMatch
        XCTAssertTrue(firstCell.exists && secondCell.exists, app.debugDescription)
        app.buttons["category.reorder"].tap()
        secondCell.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).press(
            forDuration: 0.5, thenDragTo: firstCell.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.1)),
            withVelocity: .slow, thenHoldForDuration: 0.3)
        XCTAssertLessThan(visibility(second).frame.minY, visibility(first).frame.minY, "Dragging must reorder the rows")
        toggleVisibility(first)
        let edited = XCTAttachment(screenshot: app.screenshot()); edited.name = "Category order and shelf visibility"
        edited.lifetime = .keepAlways; add(edited)
        app.buttons["category.manage.save"].tap()
        XCTAssertTrue(app.navigationBars["管理分类"].waitForNonExistence(timeout: 10))
        XCTAssertFalse(chip(first, in: app).exists)
        XCTAssertTrue(chip(second, in: app).exists)

        app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["category.manage"].waitForExistence(timeout: 30))
        XCTAssertFalse(chip(first, in: app).exists)
        openManager()
        XCTAssertLessThan(visibility(second).frame.minY, visibility(first).frame.minY)
        XCTAssertEqual(visibility(first).value as? String, "0")
        XCTAssertEqual(visibility(second).value as? String, "1")
        // Restore the hidden category through the same UI; both chips should become available again.
        toggleVisibility(first)
        app.buttons["category.manage.save"].tap()
        XCTAssertTrue(app.navigationBars["管理分类"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(chip(first, in: app).exists)
    }

    /// The library has only its own search (no inherited global field under the title), the categories scroll with
    /// the works, and swiping sideways over the works moves between categories.
    @MainActor
    func testCategoriesScrollWithWorksAndSwipeBetweenCategories() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launch()
        let addButton = app.buttons["category.add"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 30))
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: addButton)
        waitForExpectations(timeout: 30)
        XCTAssertFalse(app.searchFields["搜索全部来源中的作品"].exists, "Global search leaked into the library")
        let title = "滑动 \(UUID().uuidString.prefix(6))"
        addButton.tap()
        let field = app.textFields["category.title"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap(); field.typeText(title)
        app.buttons["category.save"].tap()
        XCTAssertTrue(field.waitForNonExistence(timeout: 10))

        let all = app.buttons["category.all"]
        XCTAssertTrue(all.waitForExistence(timeout: 10))
        if !all.isSelected { all.tap() }
        XCTAssertTrue(all.isSelected)
        // The categories are the page's first row: they scroll away with the works and come back at the top.
        let area = app.windows.firstMatch
        area.swipeUp()
        XCTAssertFalse(all.isHittable, "Categories should scroll away with the works: \(app.debugDescription)")
        area.swipeDown()
        expectation(for: NSPredicate(format: "hittable == true"), evaluatedWith: all)
        waitForExpectations(timeout: 5)
        // From 全部 a left swipe selects the first category, a right swipe returns.
        area.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.6))
            .press(forDuration: 0.05, thenDragTo: area.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.6)))
        expectation(for: NSPredicate(format: "selected == false"), evaluatedWith: all)
        waitForExpectations(timeout: 5)
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Swiped to the next category"
        shot.lifetime = .keepAlways; add(shot)
        area.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.6))
            .press(forDuration: 0.05, thenDragTo: area.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.6)))
        expectation(for: NSPredicate(format: "selected == true"), evaluatedWith: all)
        waitForExpectations(timeout: 5)
    }

    @MainActor
    func testCategorySurvivesAppRestart() {
        let app = XCUIApplication()
        app.launch()
        let add = app.buttons["category.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        let ready = NSPredicate(format: "enabled == true")
        expectation(for: ready, evaluatedWith: add)
        waitForExpectations(timeout: 30)
        add.tap()
        let field = app.textFields["category.title"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["category.save"].isEnabled)
        let title = "iOS Persistence \(UUID().uuidString.prefix(8))"
        field.tap()
        field.typeText(title)
        app.buttons["category.save"].tap()
        XCTAssertTrue(field.waitForNonExistence(timeout: 10))
        XCTAssertTrue(reveal(chip(title, in: app), in: app), app.debugDescription)
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["category.add"].waitForExistence(timeout: 30))
        XCTAssertTrue(reveal(chip(title, in: app), in: app), app.debugDescription)
    }

    /// Category chips are labelled "title count".
    private func chip(_ title: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "category.", title)).firstMatch
    }

    /// Chips are not lazy: every category exists in the bar even when scrolled out of view.
    private func reveal(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        element.waitForExistence(timeout: 15)
    }
}
