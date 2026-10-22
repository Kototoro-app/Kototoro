import XCTest

final class SourceRuntimeTests: XCTestCase {
    /// Online check with MangaK's repository JAR staged in the real JVM preview profile.
    @MainActor
    func testMangaKBrowsingThroughPackagedRuntime() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["KOTOTORO_SOURCE_DIAGNOSTICS"] = "1"
        app.launchArguments += ["-space.enabled", "YES", "-space.kind", "MANGA", "-sources.showAdult", "YES"]
        app.launch()
        app.mainTab("浏览").tap()
        XCTAssertTrue(app.staticTexts["sources.extensionRuntime"].waitForExistence(timeout: 30), app.debugDescription)
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 30), app.debugDescription)
        search.tap()
        search.typeText("MIHON_5020395055978987501")
        let source = app.buttons["source.MIHON_5020395055978987501"]
        XCTAssertTrue(source.waitForExistence(timeout: 90), app.debugDescription)
        source.tap()
        let item = app.descendants(matching: .any).matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")
        ).firstMatch
        wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            item.exists || app.staticTexts["sources.error"].exists
        }, object: nil)], timeout: 90)
        let browse = XCTAttachment(screenshot: app.screenshot())
        browse.name = "MangaK listing through packaged JVM"
        browse.lifetime = .keepAlways
        add(browse)
        XCTAssertTrue(item.exists, app.debugDescription)
        item.tap()
        let chapter = chapterButton(in: app)
        wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            chapter.exists || app.staticTexts["details.error"].exists
        }, object: nil)], timeout: 90)
        XCTAssertTrue(chapter.exists, app.debugDescription)
        chapter.tap()
        let image = app.images["reader.image"]
        wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            image.exists || app.staticTexts["reader.error"].exists
        }, object: nil)], timeout: 90)
        let reader = XCTAttachment(screenshot: app.screenshot())
        reader.name = "MangaK first chapter through packaged JVM"
        reader.lifetime = .keepAlways
        add(reader)
        XCTAssertTrue(image.exists, app.debugDescription)
    }

    /// Also runs with the empty device Release profile: local sources appear after the extension catalog settles.
    @MainActor
    func testPackagedExtensionRuntimeStartsWithoutLinkageErrors() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-space.enabled", "YES", "-space.kind", "MANGA"]
        app.launch()
        app.mainTab("浏览").tap()
        XCTAssertTrue(app.staticTexts["sources.extensionRuntime"].waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(app.buttons["source.LOCAL"].waitForExistence(timeout: 90), app.debugDescription)
        XCTAssertFalse(app.staticTexts["sources.extensionError"].exists, app.debugDescription)
        XCTAssertFalse(app.staticTexts["sources.error"].exists, app.debugDescription)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Packaged extension runtime startup"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testFixtureRuntimeExplainsAndBlocksRealExtensionInstallation() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if !app.staticTexts["独立测试源，仅用于运行时验证"].waitForExistence(timeout: 10) {
            throw XCTSkip("Select the independent fixture preview profile to run this test")
        }
        XCTAssertTrue(app.buttons["source.MIHON_9007199254740993"].waitForExistence(timeout: 90))
        app.buttons["sources.repositories"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["repository.fixtureRuntime"].waitForExistence(timeout: 10))
        app.buttons["repository.actions"].tap()
        let install = app.buttons["repository.importFile"]
        XCTAssertTrue(install.waitForExistence(timeout: 5))
        XCTAssertFalse(install.isEnabled)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Fixture runtime installation explanation"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testPagedImageIsCenteredInViewport() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if !app.staticTexts["独立测试源，仅用于运行时验证"].waitForExistence(timeout: 10) {
            throw XCTSkip("Select the independent fixture preview profile to run this test")
        }
        let source = app.buttons["source.MIHON_9007199254740993"]
        XCTAssertTrue(source.waitForExistence(timeout: 90), app.debugDescription)
        source.tap()
        XCTAssertTrue(item(app, "popular 1").waitForExistence(timeout: 30))
        item(app, "popular 1").tap()
        let chapter = chapterButton(in: app)
        XCTAssertTrue(chapter.waitForExistence(timeout: 30))
        chapter.tap()
        let image = app.images["reader.image"].firstMatch
        XCTAssertTrue(image.waitForExistence(timeout: 30), app.debugDescription)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["布局"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "缩放基准")).firstMatch.tap()
        app.buttons["适应页面"].tap()
        app.buttons["reader.panel.close"].tap()
        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft] {
            XCUIDevice.shared.orientation = orientation
            for mode in ["从右到左", "默认（从左到右）", "从上到下"] {
                selectReaderMode(mode, in: app)
                assertReaderImageCentered(app)
                let screenshot = XCTAttachment(screenshot: app.screenshot())
                screenshot.name = "Paged centering \(orientation.rawValue) \(mode)"
                screenshot.lifetime = .keepAlways; add(screenshot)
            }
        }
        XCUIDevice.shared.orientation = .portrait
        // Rotation animates; tapping at once still uses the landscape frame of the toolbar button.
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.windows.firstMatch.frame.height > app.windows.firstMatch.frame.width && app.buttons["reader.more"].isHittable
        }, object: nil)
        wait(for: [settled], timeout: 10)
        sleep(1)
        selectReaderMode("从右到左", in: app)
    }

    private func selectReaderMode(_ mode: String, in app: XCUIApplication) {
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["布局"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "阅读模式")).firstMatch.tap()
        app.buttons[mode].tap()
        app.buttons["reader.panel.close"].tap()
        XCTAssertTrue(app.buttons["reader.more"].waitForExistence(timeout: 5))
    }

    private func assertReaderImageCentered(_ app: XCUIApplication,
                                          file: StaticString = #filePath, line: UInt = #line) {
        let images = app.images.matching(identifier: "reader.image")
        XCTAssertTrue(images.firstMatch.waitForExistence(timeout: 10), file: file, line: line)
        let window = app.windows.firstMatch.frame
        guard let image = images.allElementsBoundByIndex.first(where: { $0.frame.intersects(window) }) else {
            XCTFail("No visible reader image: \(app.debugDescription)", file: file, line: line)
            return
        }
        // The equally sized top and bottom controls share the reader's safe-area viewport.
        // UIScrollView's accessibility frame includes system insets and is larger than that viewport.
        let centerY = (app.buttons["reader.back"].frame.midY + app.buttons["reader.nextChapter"].frame.midY) / 2
        XCTAssertEqual(image.frame.midY, centerY, accuracy: 2,
                       "Image \(image.frame) must be centered at \(centerY)", file: file, line: line)
        XCTAssertEqual(image.frame.midX, window.midX, accuracy: 2,
                       "Image \(image.frame) must be horizontally centered", file: file, line: line)
    }

    func testIndependentSourceRunsThroughEmbeddedJVM() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if !app.staticTexts["sources.extensionRuntime"].waitForExistence(timeout: 10) {
            throw XCTSkip("Enable the optional simulator JVM preview to run this test")
        }
        if app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "真实扩展兼容性验证")).firstMatch.exists {
            throw XCTSkip("Select the independent fixture preview profile to run this test")
        }
        let source = app.buttons["source.MIHON_9007199254740993"]
        XCTAssertTrue(source.waitForExistence(timeout: 90), "Embedded VM failed: \(app.debugDescription)")
        source.tap()
        XCTAssertTrue(item(app, "popular 1").waitForExistence(timeout: 30))
        app.terminate()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(source.waitForExistence(timeout: 90))
        source.tap()
        XCTAssertTrue(item(app, "popular 1").waitForExistence(timeout: 30))
        let gridShot = XCTAttachment(screenshot: app.screenshot())
        gridShot.name = "Browse grid"; gridShot.lifetime = .keepAlways; add(gridShot)
        item(app, "popular 1").tap()
        let firstChapter = chapterButton(in: app)
        XCTAssertTrue(firstChapter.waitForExistence(timeout: 30), "Details failed: \(app.debugDescription)")
        XCTAssertTrue(app.buttons["details.favourite"].exists)
        let detailsShot = XCTAttachment(screenshot: app.screenshot())
        detailsShot.name = "Details page"; detailsShot.lifetime = .keepAlways; add(detailsShot)
        firstChapter.tap()
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30), "Image failed: \(app.debugDescription)")
        let progress = app.sliders["reader.progress"]
        XCTAssertTrue(progress.waitForExistence(timeout: 5))
        // Reader preferences persist across tests; the edge-tap assertions below require right-to-left mode.
        selectReaderMode("从右到左", in: app)
        progress.adjust(toNormalizedSliderPosition: 0)
        assertPosition(app, page: 1, total: 2)
        // Tap zones: the default right-to-left mode turns forward on the left edge; the centre toggles controls.
        let surface = app.windows.firstMatch
        surface.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["reader.more"].waitForNonExistence(timeout: 5))
        surface.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["reader.more"].waitForExistence(timeout: 5))
        assertPosition(app, page: 2, total: 2)
        surface.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        // Two quick taps would form the zoom double-tap; leave the recognizer's interval between them.
        _ = XCTWaiter.wait(for: [XCTestExpectation(description: "double-tap interval")], timeout: 1)
        surface.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["reader.more"].waitForExistence(timeout: 5))
        assertPosition(app, page: 1, total: 2)
        XCTAssertFalse(app.buttons["reader.previousPage"].exists)
        XCTAssertFalse(app.buttons["reader.nextPage"].exists)
        XCTAssertLessThan(app.buttons["reader.bookmark"].frame.maxY, app.buttons["reader.nextChapter"].frame.minY)
        XCTAssertEqual(app.buttons["reader.bookmark"].frame.midX,
                       app.buttons["reader.nextChapter"].frame.midX, accuracy: 2)
        XCTAssertEqual(app.buttons["reader.bookmark"].frame.width,
                       app.buttons["reader.nextChapter"].frame.width, accuracy: 1)
        XCTAssertEqual(app.buttons["reader.bookmark"].frame.height,
                       app.buttons["reader.nextChapter"].frame.height, accuracy: 1)
        XCTAssertEqual(app.descendants(matching: .any)["reader.progressControl"].frame.height,
                       app.buttons["reader.nextChapter"].frame.height, accuracy: 1)
        XCTAssertEqual(app.buttons["reader.back"].frame.width, app.buttons["reader.back"].frame.height, accuracy: 1)
        let footerScreenshot = XCTAttachment(screenshot: app.screenshot())
        footerScreenshot.name = "Native chapter progress bar and floating bookmark"
        footerScreenshot.lifetime = .keepAlways; add(footerScreenshot)
        progress.adjust(toNormalizedSliderPosition: 1)
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        assertPosition(app, page: 2, total: 2)
        if app.buttons["reader.bookmark"].value as? String != "已添加" { app.buttons["reader.bookmark"].tap() }
        app.buttons["reader.chapters"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["reader.chapterPanel"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["reader.more"].exists)
        app.segmentedControls.buttons["书签"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "第 2 页")).firstMatch.waitForExistence(timeout: 10))
        let chaptersScreenshot = XCTAttachment(screenshot: app.screenshot())
        chaptersScreenshot.name = "Native chapter panel and bookmarks"; chaptersScreenshot.lifetime = .keepAlways; add(chaptersScreenshot)
        app.buttons["reader.panel.close"].tap()
        app.buttons["reader.more"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["reader.morePanel"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["reader.chapters"].exists)
        app.buttons["下载本章"].tap()
        XCTAssertTrue(app.staticTexts["本章已下载，可离线阅读"].waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["reader.downloads"].tap()
        XCTAssertTrue(app.staticTexts["已下载 · 2 页"].waitForExistence(timeout: 10), app.debugDescription)
        let queueScreenshot = XCTAttachment(screenshot: app.screenshot())
        queueScreenshot.name = "Persistent chapter download queue"
        queueScreenshot.lifetime = .keepAlways; add(queueScreenshot)
        app.buttons["downloads.close"].tap()
        let moreScreenshot = XCTAttachment(screenshot: app.screenshot())
        moreScreenshot.name = "Native more panel and chapter download"; moreScreenshot.lifetime = .keepAlways; add(moreScreenshot)
        app.buttons["reader.panel.close"].tap()
        progress.adjust(toNormalizedSliderPosition: 0)
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        assertPosition(app, page: 1, total: 2)
        app.buttons["reader.nextChapter"].tap()
        XCTAssertTrue(app.buttons["reader.chapters"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        assertPosition(app, page: 1, total: 2)
        XCTAssertFalse(app.buttons["reader.nextChapter"].isEnabled)
        progress.adjust(toNormalizedSliderPosition: 1)
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        assertPosition(app, page: 2, total: 2)
        app.buttons["reader.back"].tap()
        XCTAssertTrue(chapterButton(in: app).waitForExistence(timeout: 5))
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 10))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(item(app, "popular 1").waitForExistence(timeout: 5))
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(source.waitForExistence(timeout: 90)); source.tap()
        XCTAssertTrue(item(app, "popular 1").waitForExistence(timeout: 30)); item(app, "popular 1").tap()
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 30)); app.detailsReadButton().tap()
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30), app.debugDescription)
        assertPosition(app, page: 2, total: 2)
        XCTAssertFalse(app.buttons["reader.nextChapter"].isEnabled)
        app.buttons["reader.more"].tap()
        app.buttons["reader.downloads"].tap()
        XCTAssertTrue(app.staticTexts["已下载 · 2 页"].waitForExistence(timeout: 10), app.debugDescription)
    }

    /// The fixture extension's own FilterList: check box, tri-state, select, sort, text and nested groups
    /// reach the native controls; its search reports the resulting native state as the item title.
    func testExtensionFilterControlsReachNativeFilterList() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if !app.staticTexts["独立测试源，仅用于运行时验证"].waitForExistence(timeout: 10) {
            throw XCTSkip("Select the independent fixture preview profile to run this test")
        }
        let source = app.buttons["source.MIHON_9007199254740993"]
        XCTAssertTrue(source.waitForExistence(timeout: 90), app.debugDescription)
        source.tap()
        XCTAssertTrue(item(app, "popular 1").waitForExistence(timeout: 30))
        // Only real genres reach the quick bar; select/sort/text projections stay in the sheet.
        XCTAssertTrue(app.buttons["category.Genre"].waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(app.buttons["category.📝 Author"].exists)
        let search = app.pageSearchField()
        if !search.exists { app.navigationBars.buttons["搜索"].tap() }
        XCTAssertTrue(search.waitForExistence(timeout: 10), app.debugDescription)
        search.tap()
        search.typeText("filters\n")
        XCTAssertTrue(item(app, "true/0/0/0:false/default/extra/false").waitForExistence(timeout: 30),
                      app.debugDescription)

        // While searching the toolbar is replaced by the search field; the chip in the bar stays.
        let filter = app.buttons["browse.filterChip"]
        XCTAssertTrue(filter.waitForExistence(timeout: 10), app.debugDescription)
        filter.tap()
        let flag = app.switches["filter.control.Flag"]
        XCTAssertTrue(flag.waitForExistence(timeout: 10), app.debugDescription)
        // Expand the sheet so menus near the medium detent's edge open fully on screen.
        app.navigationBars["筛选"].swipeUp()
        flag.switches.firstMatch.tap()
        choose(app, "filter.control.Genre", option: "包含")
        choose(app, "filter.control.Choice", option: "Second")
        choose(app, "filter.control.Order", option: "Date")
        let ascending = app.switches["filter.control.Order.ascending"]
        XCTAssertTrue(reveal(app, ascending), app.debugDescription)
        ascending.switches.firstMatch.tap()
        let author = app.textFields["filter.control.Author"]
        XCTAssertTrue(reveal(app, author), app.debugDescription)
        author.tap()
        author.typeText("x")
        let group = app.buttons["filter.group.Group"]
        XCTAssertTrue(reveal(app, group), app.debugDescription)
        group.tap()
        let nested = app.buttons["filter.group.Nested"]
        XCTAssertTrue(nested.waitForExistence(timeout: 5), app.debugDescription)
        nested.tap()
        let nestedFlag = app.buttons["filter.control.NestedFlag"]
        XCTAssertTrue(reveal(app, nestedFlag), app.debugDescription)
        nestedFlag.tap()
        XCTAssertEqual(nestedFlag.value as? String, "已包含")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "暂不支持")).firstMatch.exists,
                      "Unknown extension controls should be listed as unsupported")
        let sheetShot = XCTAttachment(screenshot: app.screenshot())
        sheetShot.name = "Extension filter controls"; sheetShot.lifetime = .keepAlways; add(sheetShot)
        app.buttons["filter.apply"].tap()
        XCTAssertTrue(item(app, "false/1/2/1:true/xdefault/extra/true").waitForExistence(timeout: 30),
                      app.debugDescription)

        // Reopening shows the applied state; reset restores the extension's defaults.
        filter.tap()
        XCTAssertTrue(app.switches["filter.control.Flag"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.switches["filter.control.Flag"].value as? String, "0")
        app.buttons["filter.reset"].tap()
        app.buttons["filter.apply"].tap()
        XCTAssertTrue(item(app, "true/0/0/0:false/default/extra/false").waitForExistence(timeout: 30),
                      app.debugDescription)
    }

    private func choose(_ app: XCUIApplication, _ identifier: String, option: String) {
        let picker = app.buttons[identifier]
        XCTAssertTrue(reveal(app, picker), app.debugDescription)
        picker.tap()
        let entry = app.buttons[option].firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 5), "\(identifier) menu: \(app.debugDescription)")
        entry.tap()
    }

    /// Form rows are lazy; scroll the sheet until the element is on screen.
    private func reveal(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        for _ in 0..<8 {
            if element.exists && element.isHittable { return true }
            app.collectionViews.firstMatch.swipeUp(velocity: .slow)
        }
        return element.exists && element.isHittable
    }

    func testConvertedRealMihonApkRunsOnSimulator() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if !app.staticTexts["sources.extensionRuntime"].waitForExistence(timeout: 10) ||
            app.staticTexts["独立测试源，仅用于运行时验证"].exists {
            throw XCTSkip("Select the real APK preview profile to run this test")
        }
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 30))
        search.tap()
        search.typeText("MIHON_524579092615598717")
        let source = app.buttons["source.MIHON_524579092615598717"]
        XCTAssertTrue(source.waitForExistence(timeout: 90), "Real APK failed: \(app.debugDescription)")
        source.tap()
        let firstContent = app.descendants(matching: .any).matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")
        ).firstMatch
        let completed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            firstContent.exists || app.staticTexts["sources.error"].exists
        }, object: nil)
        wait(for: [completed], timeout: 60)
        XCTAssertTrue(firstContent.exists, "Browse failed: \(app.debugDescription)")
        firstContent.tap()
        let firstChapter = chapterButton(in: app)
        let detailsCompleted = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            firstChapter.exists || app.staticTexts["details.error"].exists
        }, object: nil)
        wait(for: [detailsCompleted], timeout: 60)
        XCTAssertTrue(firstChapter.exists, "Details failed: \(app.debugDescription)")
        let detailsScreenshot = XCTAttachment(screenshot: app.screenshot())
        detailsScreenshot.name = "Real APK details and chapters"
        detailsScreenshot.lifetime = .keepAlways
        add(detailsScreenshot)
        firstChapter.tap()
        let image = app.images["reader.image"]
        let imageCompleted = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            image.exists || app.staticTexts["reader.error"].exists
        }, object: nil)
        wait(for: [imageCompleted], timeout: 90)
        XCTAssertTrue(image.exists, "Reader failed: \(app.debugDescription)")
        let progress = app.sliders["reader.progress"]
        XCTAssertTrue(progress.exists)
        progress.adjust(toNormalizedSliderPosition: 0)
        let readerScreenshot = XCTAttachment(screenshot: app.screenshot())
        readerScreenshot.name = "Real APK decoded first manga page"
        readerScreenshot.lifetime = .keepAlways
        add(readerScreenshot)
        let pageCount = Int(app.staticTexts["reader.totalPages"].value as? String ?? "0") ?? 0
        if pageCount > 1 {
            progress.adjust(toNormalizedSliderPosition: 1 / CGFloat(pageCount - 1))
            let nextImageCompleted = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                image.exists || app.staticTexts["reader.error"].exists
            }, object: nil)
            wait(for: [nextImageCompleted], timeout: 90)
            XCTAssertTrue(image.exists, "Second page failed: \(app.debugDescription)")
            assertPosition(app, page: 2, total: pageCount)
        }
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    /// AllNovelFull lists ~2,000 chapters through dozens of sequential page requests (about a minute on the
    /// interpreter-only JVM). Reopening the work, in this session or after a relaunch, shows the saved list at once
    /// while the source is asked again.
    @MainActor
    func testReopenedLongChapterListShowsAtOnce() throws {
        let app = XCUIApplication()
        let source = "TSUNDOKU_3273374795580060030"
        func openFirstWork() -> XCUIElement {
            app.tabBars.buttons["浏览"].tap()
            let search = app.pageSearchField()
            XCTAssertTrue(search.waitForExistence(timeout: 30))
            search.tap()
            search.typeText(source)
            let row = app.buttons["source.\(source)"]
            XCTAssertTrue(row.waitForExistence(timeout: 90), "Source missing: \(app.debugDescription)")
            row.tap()
            let work = app.descendants(matching: .any).matching(
                NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
            XCTAssertTrue(work.waitForExistence(timeout: 90), "Browse failed: \(app.debugDescription)")
            work.tap()
            return work
        }
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if !app.staticTexts["sources.extensionRuntime"].waitForExistence(timeout: 15) ||
            app.staticTexts["独立测试源，仅用于运行时验证"].exists {
            throw XCTSkip("Select the real extension preview profile to run this test")
        }
        let work = openFirstWork()
        XCTAssertTrue(revealChapter(in: app, timeout: 180).exists, "First load failed: \(app.debugDescription)")
        XCTAssertTrue(app.descendants(matching: .any)["details.updating"].waitForNonExistence(timeout: 180))

        // Back and in again: this session's details, while the source is asked again.
        app.navigationBars.buttons["BackButton"].firstMatch.tap()
        XCTAssertTrue(work.waitForExistence(timeout: 10))
        work.tap()
        XCTAssertTrue(revealChapter(in: app, timeout: 10).exists, "Session cache: \(app.debugDescription)")
        XCTAssertTrue(app.descendants(matching: .any)["details.updating"].exists, "The refresh should still run")
        let shown = XCTAttachment(screenshot: app.screenshot())
        shown.name = "Saved chapters while updating"
        shown.lifetime = .keepAlways
        add(shown)

        // After a relaunch: the details saved when the work was last opened.
        app.terminate()
        app.launch()
        _ = openFirstWork()
        XCTAssertTrue(revealChapter(in: app, timeout: 15).exists, "Saved details: \(app.debugDescription)")
    }

    /// Chapters follow the source's order by default; numbers declared by the extension are an opt-in order.
    @MainActor
    func testChapterOrderMenuDefaultsToSourceOrder() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if !app.staticTexts["sources.extensionRuntime"].waitForExistence(timeout: 15) ||
            app.staticTexts["独立测试源，仅用于运行时验证"].exists {
            throw XCTSkip("Select the real extension preview profile to run this test")
        }
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 30))
        search.tap()
        search.typeText("MIHON_524579092615598717")
        let source = app.buttons["source.MIHON_524579092615598717"]
        XCTAssertTrue(source.waitForExistence(timeout: 90))
        source.tap()
        let work = app.descendants(matching: .any).matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        XCTAssertTrue(work.waitForExistence(timeout: 60))
        work.tap()
        XCTAssertTrue(revealChapter(in: app, timeout: 60).exists)
        let menu = app.buttons["details.sort"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        let resetOrder = menu.label.contains("章节号")
        if resetOrder { menu.tap(); app.buttons["源顺序"].tap() }
        XCTAssertFalse(menu.label.contains("章节号"), "Source order is the default: \(menu.label)")
        menu.tap()
        for option in ["源顺序", "章节号", "最早在前", "最新在前"] {
            XCTAssertTrue(app.buttons[option].waitForExistence(timeout: 5), "Missing \(option): \(app.debugDescription)")
        }
        app.buttons["章节号"].tap()
        XCTAssertTrue(menu.label.contains("章节号"), menu.label)
        menu.tap()
        app.buttons["源顺序"].tap()
        XCTAssertFalse(menu.label.contains("章节号"), menu.label)
        XCTAssertTrue(revealChapter(in: app, timeout: 10).exists)
    }

    /// Browse results are poster cards (buttons); match by label regardless of element type.
    private func item(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
    }

    private func chapterButton(in app: XCUIApplication) -> XCUIElement { revealChapter(in: app, timeout: 30) }

    private func assertPosition(_ app: XCUIApplication, page: Int, total: Int,
                                file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, String(page), file: file, line: line)
        XCTAssertEqual(app.staticTexts["reader.totalPages"].value as? String, String(total), file: file, line: line)
    }
}

/// Details open with the panorama header, so chapter rows start below the fold and, being lazy, do not exist for
/// XCUITest until scrolled to. Once details have loaded (the primary action carries its identifier), scroll until
/// the first chapter row appears; returns that row (check `exists`) or stops at a details error.
func revealChapter(in app: XCUIApplication, timeout: TimeInterval = 60) -> XCUIElement {
    let chapter = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).firstMatch
    let error = app.staticTexts["details.error"]
    let deadline = Date().addingTimeInterval(timeout)
    while !chapter.exists && !error.exists && Date() < deadline {
        if app.buttons["details.header.read"].exists || app.buttons["details.read"].exists {
            app.collectionViews.firstMatch.swipeUp(velocity: .slow)
        } else {
            _ = chapter.waitForExistence(timeout: 1)
        }
    }
    // The tab bar and its continue-reading accessory float over the list's end: keep the row clear of them.
    let overlays = [app.buttons["details.read"], app.buttons["continueReading"], app.tabBars.firstMatch]
    for _ in 0..<4 where chapter.exists {
        let covered = overlays.contains { $0.exists && $0.frame.minY < chapter.frame.maxY + 8 && $0.frame.height > 0 }
        guard covered else { break }
        app.collectionViews.firstMatch.swipeUp(velocity: .slow)
    }
    return chapter
}
