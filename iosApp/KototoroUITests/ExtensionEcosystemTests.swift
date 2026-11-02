import XCTest

/// Real extensions through the embedded JVM (`stage-mihon-preview.py` profile): Tsundoku novels, Aniyomi video and
/// kototoro-parsers JAR sources. Online; site availability is reported, not hidden.
final class ExtensionEcosystemTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        // Builds without the embedded JVM have no extension section at all: skip at once instead of waiting.
        if !app.staticTexts["sources.extensionRuntime"].waitForExistence(timeout: 15) {
            throw XCTSkip("This build has no embedded JVM")
        }
        let profile = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "真实扩展兼容性验证")).firstMatch
        if !profile.waitForExistence(timeout: 90) {
            throw XCTSkip("Select the real extension preview profile (stage-mihon-preview.py) to run these tests")
        }
    }

    /// Content spaces: the native panel narrows browse (and the library and history) app-wide and survives a
    /// relaunch. With spaces turned off, the page's own content-type switch comes back.
    @MainActor
    func testContentSpacesAndPerPageSwitch() throws {
        let manga = app.buttons["source.MIHON_524579092615598717"]
        let novel = app.buttons["source.TSUNDOKU_3273374795580060030"]
        let video = app.buttons["source.ANIYOMI_2247987965040486818"]
        func chooseSpace(_ title: String) {
            let menu = app.buttons["space.menu"]
            XCTAssertTrue(menu.waitForExistence(timeout: 30), app.debugDescription)
            menu.tap()
            XCTAssertTrue(app.descendants(matching: .any)["space.panel"].waitForExistence(timeout: 5))
            let kind = ["全部": "ALL", "漫画": "MANGA", "小说": "NOVEL", "视频": "VIDEO"][title]!
            let entry = app.buttons["space.select.\(kind)"]
            XCTAssertTrue(entry.waitForExistence(timeout: 5), app.debugDescription)
            entry.tap()
            XCTAssertTrue(app.buttons["space.close"].waitForNonExistence(timeout: 5))
        }
        defer { if app.buttons["space.menu"].exists { chooseSpace("全部") } }
        XCTAssertFalse(app.segmentedControls["contentKind"].exists, "Spaces replace the per-page switch")
        chooseSpace("小说")
        XCTAssertTrue(novel.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(manga.exists)
        XCTAssertFalse(video.exists)
        attach("Novel space")

        // Every root page opens the same panel and marks the app-wide choice.
        for tab in ["作品库", "记录", "浏览"] {
            app.tabBars.buttons[tab].tap()
            app.buttons["space.menu"].tap()
            let selected = app.buttons["space.select.NOVEL"]
            XCTAssertTrue(selected.waitForExistence(timeout: 5))
            XCTAssertTrue(selected.isSelected, "\(tab) should share the selected space")
            XCTAssertTrue(app.buttons["space.select.MANGA"].exists)
            XCTAssertTrue(app.buttons["space.select.VIDEO"].exists)
            attach("Space panel \(tab)")
            app.buttons["space.close"].tap()
        }

        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(novel.waitForExistence(timeout: 90), "The space should survive relaunch: \(app.debugDescription)")
        XCTAssertFalse(manga.exists)
        chooseSpace("全部")
        XCTAssertTrue(manga.waitForExistence(timeout: 10))

        app.terminate()
        app.launchArguments += ["-space.enabled", "NO"]
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        let kinds = app.segmentedControls["contentKind"]
        XCTAssertTrue(kinds.waitForExistence(timeout: 90), app.debugDescription)
        XCTAssertFalse(app.buttons["space.menu"].exists)
        kinds.buttons["视频"].tap()
        XCTAssertTrue(video.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(novel.exists)
        attach("Per-page switch")
        kinds.buttons["全部"].tap()
    }

    /// Android's continue-reading button as the tab bar accessory: it opens the latest work at its chapter.
    @MainActor
    func testContinueReadingAccessoryResumes() throws {
        openFirstItem(source: "MANGADEX", search: "MANGADEX")
        openFirstChapter()
        let image = app.images["reader.image"]
        waitForEither(image, app.staticTexts["reader.error"], timeout: 90)
        XCTAssertTrue(image.exists, "Reader failed: \(app.debugDescription)")
        app.buttons["reader.back"].tap()
        for _ in 0..<3 where !app.mainTab("作品库").exists {
            let back = app.navigationBars.buttons["BackButton"].firstMatch
            guard back.waitForExistence(timeout: 3) else { break }
            back.tap()
        }
        let libraryTab = app.mainTab("作品库")
        XCTAssertTrue(libraryTab.waitForExistence(timeout: 5), app.debugDescription)
        libraryTab.tap()
        let accessory = app.buttons["continueReading"]
        XCTAssertTrue(accessory.waitForExistence(timeout: 10), app.debugDescription)
        attach("Continue reading accessory")
        accessory.tap()
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 90), "Accessory should resume reading: \(app.debugDescription)")
        app.buttons["reader.back"].tap()
        let close = app.buttons["continueReading.close"]
        XCTAssertTrue(close.waitForExistence(timeout: 10), app.debugDescription)
        close.tap()
        XCTAssertTrue(accessory.waitForExistence(timeout: 10))

        let mangaResume = accessory.label
        // Leave the app-wide space on everything even when an assertion stops the test midway.
        defer {
            if app.buttons["space.menu"].exists {
                app.buttons["space.menu"].tap()
                if app.buttons["space.select.ALL"].waitForExistence(timeout: 5) { app.buttons["space.select.ALL"].tap() }
            }
        }

        // Resume from a different selected space: the card must use its own latest history item, select that
        // space, dismiss the panel, and then present the same reader as the accessory.
        app.buttons["space.menu"].tap()
        app.buttons["space.select.NOVEL"].tap()
        // The accessory follows the space: it no longer offers the manga just read (the novel space may hold its own).
        XCTAssertFalse(accessory.exists && accessory.label == mangaResume,
                       "Accessory should follow the space: \(app.debugDescription)")
        app.buttons["space.menu"].tap()
        let resume = app.buttons["space.resume.MANGA"]
        XCTAssertTrue(resume.waitForExistence(timeout: 10), app.debugDescription)
        attach("Space panel resume")
        resume.tap()
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 90), app.debugDescription)
        XCTAssertFalse(app.buttons["space.close"].exists)
        app.buttons["reader.back"].tap()
        app.buttons["continueReading.close"].tap()
        app.buttons["space.menu"].tap()
        XCTAssertTrue(app.buttons["space.select.MANGA"].isSelected)
        app.buttons["space.select.ALL"].tap()
    }

    /// Android's webtoon zoom: pinching the strip lays it out wider (pages stay sharp), it pans across, and a
    /// double tap returns to fit.
    @MainActor
    func testWebtoonStripPinchZoomPansAndResets() throws {
        openFirstItem(source: "MIHON_524579092615598717", search: "MIHON_524579092615598717")
        openFirstChapter()
        let image = app.images["reader.image"].firstMatch
        waitForEither(image, element("reader.error"), timeout: 90)
        XCTAssertTrue(image.exists, "Reader failed: \(app.debugDescription)")
        selectReaderMode("条漫")
        defer { selectReaderMode("默认（从左到右）") }
        let window = app.windows.firstMatch.frame
        func visibleImage() -> XCUIElement? {
            app.images.matching(identifier: "reader.image").allElementsBoundByIndex
                .first { $0.frame.intersects(window) && $0.frame.height > 50 }
        }
        XCTAssertTrue(image.waitForExistence(timeout: 30))
        XCTAssertEqual(visibleImage()?.frame.width ?? 0, window.width, accuracy: 2, "The strip starts at fit width")
        sleep(1) // let the strip settle after the mode switch before pinching

        visibleImage()?.pinch(withScale: 2.5, velocity: 2)
        let widened = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (visibleImage()?.frame.width ?? 0) > window.width * 1.5
        }, object: nil)
        wait(for: [widened], timeout: 10)
        attach("Webtoon zoomed")
        let before = visibleImage()?.frame.minX ?? 0
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)))
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5)))
        let after = visibleImage()?.frame.minX ?? 0
        XCTAssertLessThan(after, before - 20, "A zoomed strip pans across")
        attach("Webtoon panned")

        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).doubleTap()
        let reset = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard let frame = visibleImage()?.frame else { return false }
            return abs(frame.width - window.width) < 2 && abs(frame.minX) < 2
        }, object: nil)
        wait(for: [reset], timeout: 10)
        attach("Webtoon reset")
    }

    @MainActor
    func testBrowseToolbarOpensGlobalSearch() throws {
        let search = app.buttons["sources.globalSearch"]
        XCTAssertTrue(search.waitForExistence(timeout: 10), app.debugDescription)
        search.tap()
        let query = app.searchFields["搜索全部来源中的作品"]
        XCTAssertTrue(query.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.keyboards.firstMatch.exists, "The global search entry should activate its query field")
        attach("Browse toolbar global search")
    }

    @MainActor
    func testTsundokuNovelChapterText() throws {
        openFirstItem(source: "TSUNDOKU_3273374795580060030")
        openFirstChapter()
        let paragraph = element("novel.firstParagraph")
        waitForEither(paragraph, element("novel.error"), timeout: 90)
        XCTAssertTrue(paragraph.exists, "Novel text failed: \(app.debugDescription)")
        attach("Tsundoku novel reader")
    }

    /// Novel chapters download through the shared queue and then read from the download.
    @MainActor
    func testTsundokuNovelChapterDownloadsAndReadsOffline() throws {
        try assertNovelDownloadReadsOffline(source: "TSUNDOKU_3273374795580060030", search: nil)
    }

    /// Parser plugin novels whose chapters carry illustrations: the pictures are saved with the text.
    @MainActor
    func testJarNovelChapterDownloadsAndReadsOffline() throws {
        try assertNovelDownloadReadsOffline(source: "BILINOVEL", search: "BILINOVEL")
    }

    private func assertNovelDownloadReadsOffline(source: String, search: String?) throws {
        if app.buttons["space.menu"].exists {
            app.buttons["space.menu"].tap()
            XCTAssertTrue(app.buttons["space.select.NOVEL"].waitForExistence(timeout: 10))
            app.buttons["space.select.NOVEL"].tap()
        }
        openFirstItem(source: source, search: search)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        let title = app.staticTexts["details.title"].label
        openFirstChapter()
        let paragraph = element("novel.firstParagraph")
        waitForEither(paragraph, element("novel.error"), timeout: 90)
        XCTAssertTrue(paragraph.exists, "Novel text failed: \(app.debugDescription)")
        app.buttons["novel.more"].tap()
        // Menus do not carry their identifier; find the download menu by its title.
        let menu = app.buttons["下载"]
        XCTAssertTrue(menu.waitForExistence(timeout: 5), app.debugDescription)
        menu.tap()
        let current = app.buttons["下载本章"]
        XCTAssertTrue(current.waitForExistence(timeout: 5), app.debugDescription)
        if current.isEnabled { current.tap() } else { menu.tap() } // already downloaded by an earlier run
        app.buttons["novel.panel.close"].tap()

        // The chapter list marks the chapter once its text and pictures are saved.
        let downloaded = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                          "novel.chapter.", "已下载")).firstMatch
        var marked = false
        for _ in 0..<12 where !marked {
            app.buttons["novel.chapters"].tap()
            marked = downloaded.waitForExistence(timeout: 5)
            if !marked {
                app.buttons["关闭"].firstMatch.tap()
                sleep(5)
            }
        }
        XCTAssertTrue(marked, "Download did not finish: \(app.debugDescription)")
        attach("\(source) novel chapter downloaded")
        app.buttons["关闭"].firstMatch.tap()

        // The reader covers the tab bar's continue-reading accessory.
        XCTAssertFalse(app.buttons["continueReading"].isHittable, "The accessory should hide in the reader")
        // Reopening the chapter reads the saved copy.
        app.buttons["novel.back"].tap()
        openFirstChapter()
        XCTAssertTrue(element("novel.offline").waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(element("novel.firstParagraph").exists)
        attach("\(source) novel read offline")
        app.buttons["novel.more"].tap()
        app.buttons["novel.downloads"].tap()
        XCTAssertTrue(app.buttons["downloads.storage"].waitForExistence(timeout: 15))
        app.buttons["downloads.storage"].tap()
        let stored = app.otherElements.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "storage.chapter.", title
        )).firstMatch
        for _ in 0..<8 where !stored.exists { app.swipeUp() }
        XCTAssertTrue(stored.exists, app.debugDescription)
        let delete = stored.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "storage.delete.")).firstMatch
        XCTAssertTrue(delete.exists)
        XCTAssertFalse(delete.isEnabled, "The open novel chapter must remain protected")
        attach("\(source) novel offline storage protected")
    }

    /// The root pages search in place with the system field under the title (not the search tab's global field):
    /// a submitted keyword stays in the field, and cancel clears it and closes the keyboard for good.
    @MainActor
    func testRootPageSearchClosesAndShowsKeyword() throws {
        for (tab, prompt) in [("作品库", "搜索作品库"), ("记录", "搜索历史"), ("浏览", "搜索源")] {
            app.mainTab(tab).tap()
            let field = app.pageSearchField()
            XCTAssertTrue(field.exists && field.isHittable, "\(tab): \(app.debugDescription)")
            XCTAssertEqual(field.placeholderValue, prompt, "\(tab): the page's own search, not the global one")
            field.tap()
            XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "\(tab): the field should take focus")
            field.typeText("zz\n")
            XCTAssertEqual(field.value as? String, "zz", "\(tab): a submitted keyword stays in the field")
            attach("\(tab) search keyword")
            let cancel = app.navigationBars.buttons["取消"].firstMatch
            XCTAssertTrue(cancel.waitForExistence(timeout: 5), "\(tab): \(app.debugDescription)")
            cancel.tap()
            // Closed, and still closed a moment later (the minimized toolbar field used to refocus right away).
            XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 3), "\(tab): cancel should close search")
            sleep(1)
            XCTAssertFalse(app.keyboards.firstMatch.exists, "\(tab): the field reopened")
            XCTAssertNotEqual(app.pageSearchField().value as? String, "zz", "\(tab): cancel clears the keyword")
        }
    }

    /// Exercise the real parser through the embedded JVM, including its wrapper and the packaged host JAR.
    @MainActor
    func testPicacgSettingsExposeCredentialLogin() throws {
        app.terminate()
        app.launchArguments += ["-sources.showAdult", "YES", "-space.kind", ""]
        app.launch()
        app.mainTab("浏览").tap()
        let field = searchField()
        field.tap()
        field.typeText("PICACG")
        let source = app.buttons["source.PICACG"]
        XCTAssertTrue(source.waitForExistence(timeout: 90), app.debugDescription)
        source.tap()
        let settings = app.buttons["browse.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30), app.debugDescription)
        settings.tap()
        let username = app.buttons["sourceSettings.node.auth:username"]
        for _ in 0..<4 where !username.waitForExistence(timeout: 3) {
            app.collectionViews.firstMatch.swipeUp(velocity: .slow)
        }
        XCTAssertTrue(username.exists, app.debugDescription)
        XCTAssertTrue(app.buttons["sourceSettings.node.auth:password"].exists, app.debugDescription)
        XCTAssertTrue(app.buttons["sourceSettings.node.auth:login"].exists, app.debugDescription)
        attach("PICACG credential settings")
        app.buttons["sourceSettings.node.auth:password"].tap()
        XCTAssertTrue(app.secureTextFields["sourceSettings.text"].waitForExistence(timeout: 5), app.debugDescription)
    }

    /// Each source's settings open from its browse toolbar: the extension's own rows plus the host's User-Agent row
    /// with presets, and the site in the in-app browser.
    @MainActor
    func testSourceSettingsUserAgentPresetsAndBrowser() throws {
        for (round, (source, search)) in [("MIHON_524579092615598717", "MIHON_524579092615598717"),
                                          ("MANGADEX", "MANGADEX")].enumerated() {
            if round > 0 {
                // A fresh browse page: the previous round's search text would otherwise be extended.
                app.terminate(); app.launch()
                app.mainTab("浏览").tap()
            }
            let field = searchField()
            field.tap()
            field.typeText(search)
            let row = app.buttons["source.\(source)"]
            XCTAssertTrue(row.waitForExistence(timeout: 30), app.debugDescription)
            row.tap()
            let settings = app.buttons["browse.settings"]
            XCTAssertTrue(settings.waitForExistence(timeout: 30), app.debugDescription)
            XCTAssertFalse(app.buttons["browse.filter"].exists, "The toolbar filter button duplicated the chip")
            settings.tap()
            let userAgent = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                             "sourceSettings.node.", "User-Agent")).firstMatch
            XCTAssertTrue(userAgent.waitForExistence(timeout: 30), "\(source) settings: \(app.debugDescription)")
            attach("\(source) settings")
            userAgent.tap()
            let preset = app.buttons["sourceSettings.uaPreset.Windows Chrome"]
            XCTAssertTrue(preset.waitForExistence(timeout: 5), app.debugDescription)
            preset.tap()
            app.buttons["sourceSettings.save"].tap()
            XCTAssertTrue(userAgent.waitForExistence(timeout: 10))
            let saved = NSPredicate(format: "label CONTAINS %@", "Windows Chrome")
            expectation(for: saved, evaluatedWith: userAgent)
            waitForExpectations(timeout: 10)
            attach("\(source) user agent preset")
            // Back to the source's default so other tests keep their requests unchanged.
            userAgent.tap()
            app.buttons["恢复来源默认值"].tap()
            app.buttons["sourceSettings.save"].tap()
            expectation(for: NSPredicate(format: "label CONTAINS %@", "默认"), evaluatedWith: userAgent)
            waitForExpectations(timeout: 10)

            // Every source has a domain row too: the parser's own, or the host's for Mihon extensions.
            let domain = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND (label CONTAINS %@ OR label CONTAINS[c] %@)",
                                                          "sourceSettings.node.", "域名", "domain")).firstMatch
            XCTAssertTrue(domain.waitForExistence(timeout: 10), "\(source) has no domain row: \(app.debugDescription)")
            if source.hasPrefix("MIHON_") {
                XCTAssertTrue(domain.isEnabled, domain.label)
                domain.tap()
                let text = app.textFields["sourceSettings.text"]
                XCTAssertTrue(text.waitForExistence(timeout: 5), app.debugDescription)
                text.tap(); text.typeText("mirror.example.org")
                app.buttons["sourceSettings.save"].tap()
                expectation(for: NSPredicate(format: "label CONTAINS %@", "mirror.example.org"), evaluatedWith: domain)
                waitForExpectations(timeout: 10)
                attach("\(source) domain override")
                domain.tap()
                app.buttons["恢复来源默认值"].tap()
                app.buttons["sourceSettings.save"].tap()
                expectation(for: NSPredicate(format: "label CONTAINS %@", "默认"), evaluatedWith: domain)
                waitForExpectations(timeout: 10)
            }

            let browser = app.buttons["sourceSettings.browser"]
            XCTAssertTrue(browser.waitForExistence(timeout: 5), app.debugDescription)
            browser.tap()
            XCTAssertTrue(element("browser.status").waitForExistence(timeout: 30), app.debugDescription)
            sleep(3)
            attach("\(source) in-app browser")
            app.buttons["browser.done"].tap()
            XCTAssertTrue(element("browser.status").waitForNonExistence(timeout: 15), app.debugDescription)
            app.buttons["sourceSettings.done"].tap()
        }
    }

    @MainActor
    func testAniyomiEpisodePlays() throws {
        openFirstItem(source: "ANIYOMI_2247987965040486818")
        openFirstChapter()
        assertPlaybackAdvances()
        attach("Aniyomi playback")
    }

    @MainActor
    func testJarMangaPagesRender() throws {
        openFirstItem(source: "MANGADEX", search: "MANGADEX")
        openFirstChapter()
        let image = app.images["reader.image"]
        waitForEither(image, app.staticTexts["reader.error"], timeout: 90)
        XCTAssertTrue(image.exists, "Reader failed: \(app.debugDescription)")
        attach("JAR manga reader")
    }

    /// ADVANCED/SIMULATION turns run in the reader's own pager. The drag holds half-way for a few seconds so the
    /// fold or cover can be captured from the host (`xcrun simctl io <device> screenshot`); the turn then completes.
    /// The reader's default animation comes from the profile (`reader.defaults`), which the caller prepares.
    @MainActor
    func testPageTurnDragAdvances() throws {
        openFirstItem(source: "MANGADEX", search: "MANGADEX")
        openFirstChapter()
        let image = app.images["reader.image"]
        waitForEither(image, app.staticTexts["reader.error"], timeout: 90)
        XCTAssertTrue(image.exists, "Reader failed: \(app.debugDescription)")
        XCTAssertTrue(element("reader.turnPager").waitForExistence(timeout: 10), app.debugDescription)
        sleep(4) // neighbouring pages load (prefetch) before the turn
        let page = element("reader.currentPage")
        // The reading position may be restored from an earlier run; the turn advances from wherever it is.
        if !page.waitForExistence(timeout: 2) { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        XCTAssertTrue(page.waitForExistence(timeout: 5), app.debugDescription)
        let before = Int(page.value as? String ?? "") ?? 0
        let mirrored = ProcessInfo.processInfo.environment["KOTOTORO_TURN_RTL"] == "1"
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: mirrored ? 0.12 : 0.88, dy: 0.62))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: mirrored ? 0.55 : 0.45, dy: 0.5))
        start.press(forDuration: 0.1, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 6)
        sleep(1)
        // The centre tap toggles the reader controls; show them if the turn left them hidden.
        if !page.waitForExistence(timeout: 2) { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        XCTAssertTrue(page.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(page.value as? String, "\(before + 1)", "The turn should land on the next page")
        attach("After page turn")
    }

    /// The reader hides the navigation bar's back button; the system left-edge swipe still goes back.
    @MainActor
    func testReaderLeftEdgeSwipeGoesBack() throws {
        openFirstItem(source: "MANGADEX", search: "MANGADEX")
        openFirstChapter()
        let image = app.images["reader.image"]
        waitForEither(image, app.staticTexts["reader.error"], timeout: 90)
        XCTAssertTrue(image.exists, "Reader failed: \(app.debugDescription)")
        let edge = app.coordinate(withNormalizedOffset: CGVector(dx: 0.0, dy: 0.5))
        edge.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)))
        XCTAssertTrue(element("details.title").waitForExistence(timeout: 10), "Edge swipe should return to details: \(app.debugDescription)")
        XCTAssertFalse(app.images["reader.image"].exists)
    }

    /// Repository-installed JAR plugins other than kototoro-parsers (`testJarRepositoryShowsNameAndInstallsPlugin`
    /// installs them): UMA (Tsuki) and k-parsers-r (kotatsu ABI) each read a chapter's first image. Candidates are
    /// tried in order so one unreachable site does not stand for the whole plugin; each must be enabled by the
    /// default language rules (sources only in other languages are not listed in browse).
    @MainActor
    func testUmaPluginMangaPagesRender() throws {
        try readFirstAvailable(["MANGADEXORG", "MANGAFOX", "MANGAKEKO", "MANGABAT"])
    }

    @MainActor
    func testKParsersRedoMangaPagesRender() throws {
        // Its own Chinese / multi-language sites are offline (DNS failures, a 500 from the Webtoon API, Cloudflare),
        // so English sources that only this plugin provides are switched on first: by default only the system's
        // languages are enabled.
        try readFirstAvailable(["MANGAKAKALOT", "MANGANATO", "MANGABUDDY", "COMICEXTRA"], enabling: true)
    }

    /// Switches sources on in extension management (来源 tab), as a user would for another language.
    private func enableSources(_ names: [String]) {
        app.buttons["sources.repositories"].tap()
        let tabs = app.segmentedControls["repository.tabs"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 30))
        tabs.buttons["来源"].tap()
        let clearEcosystem = app.buttons["extension.ecosystem.clear"]
        if clearEcosystem.exists { clearEcosystem.tap() }
        let field = app.pageSearchField()
        for name in names {
            XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
            field.tap()
            if let text = field.value as? String, !text.isEmpty, text != field.placeholderValue {
                field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: text.count + 2))
            }
            field.typeText(name)
            let toggle = app.switches["source.toggle.\(name)"]
            guard toggle.waitForExistence(timeout: 10) else { print("ENABLE \(name): not found"); continue }
            if toggle.value as? String == "0" { toggle.switches.firstMatch.exists ? toggle.switches.firstMatch.tap() : toggle.tap() }
            print("ENABLE \(name): \(toggle.value ?? "")")
        }
        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
    }

    private func readFirstAvailable(_ names: [String], enabling: Bool = false) throws {
        var failures: [String] = []
        if enabling { enableSources(names) }
        // Host stack traces of failed calls go to <data>/source-failures.log for this run.
        app.terminate()
        app.launchEnvironment["KOTOTORO_SOURCE_DIAGNOSTICS"] = "1"
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        for (index, name) in names.enumerated() {
            // A fresh launch per candidate: the browse search keeps the previous text (and its cursor) after going back.
            if index > 0 {
                app.terminate(); app.launch()
                app.tabBars.buttons["浏览"].tap()
            }
            let field = searchField()
            field.tap()
            field.typeText(name)
            let source = app.buttons["source.\(name)"]
            guard source.waitForExistence(timeout: 15) else { failures.append("\(name): not listed"); continue }
            source.tap()
            let first = app.descendants(matching: .any)
                .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
            waitForEither(first, element("sources.error"), timeout: 120)
            guard first.exists else {
                failures.append("\(name): \(element("sources.error").exists ? element("sources.error").label : "timeout")")
                attach("\(name) failed")
                continue
            }
            attach("\(name) browse")
            first.tap()
            openFirstChapter()
            let image = app.images["reader.image"]
            waitForEither(image, app.staticTexts["reader.error"], timeout: 120)
            attach("\(name) reader")
            XCTAssertTrue(image.exists, "\(name) reader failed: \(app.debugDescription)")
            print("PLUGIN SOURCE OK \(name); earlier failures: \(failures)")
            return
        }
        XCTFail("No candidate source worked: \(failures)")
    }

    @MainActor
    func testJarNovelChapterText() throws {
        openFirstItem(source: "BILINOVEL", search: "BILINOVEL")
        openFirstChapter()
        let paragraph = element("novel.firstParagraph")
        waitForEither(paragraph, element("novel.error"), timeout: 90)
        XCTAssertTrue(paragraph.exists, "Novel text failed: \(app.debugDescription)")
        attach("JAR novel reader")
    }

    @MainActor
    func testJarVideoPlays() throws {
        // AGEDM's default line needs a VIP account; FANTUAN serves plain HLS.
        openFirstItem(source: "FANTUAN", search: "FANTUAN")
        openFirstChapter()
        assertPlaybackAdvances()
        attach("JAR video playback")
    }

    /// Parser plugin sources show the plugin's readable title (not the enum name) and the site's own icon.
    @MainActor
    func testJarSourcesShowReadableTitlesAndIcons() throws {
        let field = searchField()
        field.tap()
        field.typeText("MANGADEX")
        let source = app.buttons["source.MANGADEX"]
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(source.label.contains("MangaDex"), "Unreadable title: \(source.label)")
        sleep(8) // the icon is described and fetched lazily for visible rows
        attach("JAR source titles and icons")
    }

    /// Every ecosystem's filter panel: Mihon/Tsundoku/Aniyomi show the extension's own FilterList controls, JAR
    /// parsers their standard options. One control is changed and the filtered listing must load without error.
    @MainActor
    func testFilterPanelsAcrossEcosystems() throws {
        for (search, prefix) in [
            ("MIHON_524579092615598717", "source.MIHON_524579092615598717"),
            ("TSUNDOKU_3273374795580060030", "source.TSUNDOKU_"),
            ("ANIYOMI_2247987965040486818", "source.ANIYOMI_"), ("MANGADEX", "source.MANGADEX"),
        ] {
            let field = app.pageSearchField()
            XCTAssertTrue(field.waitForExistence(timeout: 10))
            field.tap()
            field.typeText(search)
            let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
            XCTAssertTrue(source.waitForExistence(timeout: 30), "Source \(search) missing: \(app.debugDescription)")
            source.tap()
            let first = app.descendants(matching: .any)
                .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
            waitForEither(first, element("sources.error"), timeout: 90)
            XCTAssertTrue(first.exists, "\(search) browse failed: \(app.debugDescription)")
            let filter = app.buttons["browse.filterChip"]
            XCTAssertTrue(filter.waitForExistence(timeout: 30), "\(search) has no filter panel: \(app.debugDescription)")
            filter.tap()
            XCTAssertTrue(app.navigationBars["筛选"].waitForExistence(timeout: 10))
            app.navigationBars["筛选"].swipeUp()
            let controls = app.descendants(matching: .any).matching(NSPredicate(
                format: "identifier BEGINSWITH %@ OR identifier BEGINSWITH %@ OR identifier BEGINSWITH %@",
                "filter.control.", "filter.group.", "filter.tag."))
            XCTAssertTrue(controls.firstMatch.waitForExistence(timeout: 10), "\(search) sheet is empty: \(app.debugDescription)")
            attach("\(search) filter sheet")
            changeOneControl()
            XCTAssertTrue(app.buttons["filter.apply"].isEnabled)
            app.buttons["filter.apply"].tap()
            let empty = element("browse.empty")
            let done = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                first.exists || empty.exists || self.element("sources.error").exists
            }, object: nil)
            wait(for: [done], timeout: 90)
            XCTAssertFalse(element("sources.error").exists, "\(search) filtered listing failed: \(app.debugDescription)")
            XCTAssertTrue(filter.label.contains("筛选 "), "Badge should count the change: \(filter.label)")
            attach("\(search) filtered listing")
            // Start the next ecosystem from a clean source list.
            app.terminate()
            app.launch()
            app.tabBars.buttons["浏览"].tap()
            XCTAssertTrue(app.staticTexts["sources.extensionRuntime"].waitForExistence(timeout: 90))
        }
    }

    @MainActor
    func testWenku8VerificationNeverClaimsCompletionForAnUnresolvedChallenge() throws {
        try verifyWenku8(requireListing: false)
    }

    @MainActor
    func testWenku8LoggedInBrowserSessionLoadsTheSourceListing() throws {
        try verifyWenku8(requireListing: true)
    }

    @MainActor
    private func verifyWenku8(requireListing: Bool) throws {
        app.terminate()
        app.launchEnvironment["KOTOTORO_SOURCE_DIAGNOSTICS"] = "1"
        app.launch()
        app.mainTab("浏览").tap()
        let field = searchField()
        field.tap()
        field.typeText("轻小说文库")
        let source = app.buttons["source.WENKU8"]
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription)
        source.tap()
        let first = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        let verify = app.buttons["source.verify"]
        _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            first.exists || (verify.exists && !self.element("source.solving").exists)
                || self.app.staticTexts["没有内容"].exists
        }, object: nil)], timeout: 100)
        if !first.exists && verify.exists {
            verify.tap()
            XCTAssertTrue(app.buttons["browser.done"].waitForExistence(timeout: 15))
            sleep(10)
            attach("Wenku8 browser before returning")
            if !app.buttons["browser.done"].isEnabled {
                XCTAssertFalse(element("browser.status").label.contains("已通过验证"))
                app.buttons["browser.close"].tap()
                XCTAssertFalse(requireListing, "The site still has an interactive challenge")
                return
            }
            app.buttons["browser.done"].tap()
        }
        _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            first.exists || verify.exists
        }, object: nil)], timeout: 60)
        XCTAssertTrue(first.exists || verify.exists, "A failed verification must retain the challenge: \(app.debugDescription)")
        XCTAssertFalse(app.staticTexts["没有内容"].exists, "Cancelling the error view must not discard the retry")
        if requireListing { XCTAssertTrue(first.exists, "The logged-in browser session must load actual source content") }
        attach(first.exists ? "Wenku8 readable listing" : "Wenku8 unresolved challenge")
    }

    /// A pending Comix browse must settle and must not leave its source preferences waiting indefinitely.
    @MainActor
    func testComixBrowseAndPreferencesSettle() throws {
        app.terminate()
        app.launchArguments += ["-sources.showAdult", "YES", "-space.kind", "", "-sources.hiddenEcosystems", "",
                                "-sources.hiddenKinds", "", "-sources.newOnly", "NO"]
        app.launchEnvironment["KOTOTORO_SOURCE_DIAGNOSTICS"] = "1"
        app.launch()
        app.mainTab("浏览").tap()
        let field = searchField()
        field.tap()
        field.typeText("Comix")
        let source = app.buttons["source.MIHON_7537715367149829912"]
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription)
        source.tap()
        let content = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        let error = element("sources.error")
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            content.exists || error.exists || self.element("source.solving").exists
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 45), .completed,
                       "Comix browse kept spinning without content or an error")
        XCTAssertTrue(content.exists, "Comix listing failed: \(app.debugDescription)")
        app.buttons["browse.settings"].tap()
        let preference = app.descendants(matching: .any)["sourceSettings.node.pref:0"]
        XCTAssertTrue(preference.waitForExistence(timeout: 15), "Comix preferences kept loading: \(app.debugDescription)")
        attach("Comix browse and preferences")
    }

    /// Verifies fresh details even when a saved directory is displayed during the source refresh.
    @MainActor
    func testComixDetailsAndChaptersLoad() throws {
        app.terminate()
        app.launchArguments += ["-sources.showAdult", "YES", "-space.kind", "", "-sources.hiddenEcosystems", "",
                                "-sources.hiddenKinds", "", "-sources.newOnly", "NO"]
        app.launchEnvironment["KOTOTORO_SOURCE_DIAGNOSTICS"] = "1"
        app.launch()
        app.mainTab("浏览").tap()
        openFirstItem(source: "MIHON_7537715367149829912", search: "Comix")
        let chapter = revealChapter(in: app, timeout: 240)
        let error = element("details.error")
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            error.exists || (chapter.exists && !self.element("details.loading").exists &&
                !self.element("details.updating").exists)
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 240), .completed)
        XCTAssertFalse(error.exists, app.debugDescription)
        XCTAssertTrue(chapter.exists, app.debugDescription)
        attach("Comix fresh details and chapters")
    }

    /// A real Cloudflare-protected site (Comix, comix.to): a challenge on the listing is shown as such and cleared by
    /// the automatic hidden-browser pass, after which the listing, details and a chapter's pages load. Needs network.
    @MainActor
    func testCloudflareProtectedSourceLoads() throws {
        // Host stack traces of failed calls go to <data>/source-failures.log for this run.
        app.terminate()
        app.launchArguments += ["-sources.showAdult", "YES", "-space.kind", "", "-sources.hiddenEcosystems", "",
                                "-sources.hiddenKinds", "", "-sources.newOnly", "NO"]
        app.launchEnvironment["KOTOTORO_SOURCE_DIAGNOSTICS"] = "1"
        app.launch()
        app.mainTab("浏览").tap()
        let name = "MIHON_7537715367149829912"
        let field = searchField()
        field.tap()
        field.typeText("Comix")
        let source = app.buttons["source.\(name)"]
        XCTAssertTrue(source.waitForExistence(timeout: 30), "Comix missing: \(app.debugDescription)")
        source.tap()
        let first = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        let solving = element("source.solving")
        let verify = app.buttons["source.verify"]
        // Either the listing loads directly (clearance still valid) or the challenge is reported and solved.
        let error = element("sources.error")
        _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            first.exists || solving.exists || verify.exists || error.exists
        }, object: nil)], timeout: 120)
        print("CLOUDFLARE state: content=\(first.exists) solving=\(solving.exists) verify=\(verify.exists) " +
              "error=\(error.exists ? error.label : "-")")
        attach("Comix first state")
        if solving.exists {
            print("CLOUDFLARE challenge reported: \(error.exists ? error.label : "-")")
            attach("Comix challenge, solving")
        }
        _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            first.exists || (verify.exists && !solving.exists) || (error.exists && !solving.exists && !verify.exists)
        }, object: nil)], timeout: 120)
        if !first.exists {
            print("CLOUDFLARE after pass: error=\(error.exists ? error.label : "-") verify=\(verify.exists)")
            attach("Comix automatic pass failed")
        }
        XCTAssertTrue(first.exists, "Comix listing failed: \(app.debugDescription)")
        sleep(3)
        attach("Comix browse")
        first.tap()
        // Comix harvests the entire paginated directory through its browser before returning details.
        openFirstChapter(timeout: 240)
        let normalReading = app.buttons["正常阅读"]
        if normalReading.waitForExistence(timeout: 5) { normalReading.tap() }
        let readerError = element("reader.error"), pageError = element("reader.pageError.0")
        func visiblePage(_ number: Int) -> XCUIElement? {
            let images = app.images.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@",
                                                         "reader.image", "第 \(number) 页"))
            let window = app.windows.firstMatch.frame
            return images.allElementsBoundByIndex.first {
                $0.isHittable && $0.frame.intersection(window).height > 40
            }
        }
        let loaded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            visiblePage(1) != nil || readerError.exists || pageError.exists
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 240), .completed)
        XCTAssertFalse(readerError.exists, app.debugDescription)
        XCTAssertFalse(pageError.exists, app.debugDescription)
        XCTAssertNotNil(visiblePage(1), "The current page must display an image, not only preload a neighbour")
        // This chapter starts with a genuinely black spacer image. Verify another page before taking the screenshot.
        let progress = app.sliders["reader.progress"]
        if !progress.isHittable {
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        XCTAssertTrue(progress.waitForExistence(timeout: 5))
        progress.adjust(toNormalizedSliderPosition: 0.5)
        let currentPage = app.staticTexts["reader.currentPage"]
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (Int(currentPage.value as? String ?? "0") ?? 0) > 1
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [moved], timeout: 10), .completed, app.debugDescription)
        let number = Int(currentPage.value as? String ?? "0") ?? 0
        let targetError = element("reader.pageError.\(number - 1)")
        let nextLoaded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            visiblePage(number) != nil || readerError.exists || targetError.exists
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [nextLoaded], timeout: 120), .completed)
        XCTAssertFalse(readerError.exists, app.debugDescription)
        XCTAssertFalse(targetError.exists, app.debugDescription)
        XCTAssertNotNil(visiblePage(number))
        sleep(1) // Let the visible image reach the compositor before capturing it.
        attach("Comix reader")
    }

    /// Prefers a check box or tag chip, then a genre group's first chip, then a menu's second option.
    private func changeOneControl() {
        let toggle = app.switches.matching(NSPredicate(format: "identifier BEGINSWITH %@", "filter.control.")).firstMatch
        if toggle.exists && toggle.isHittable { toggle.switches.firstMatch.tap(); return }
        let tag = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "filter.tag.")).firstMatch
        if tag.exists && tag.isHittable { tag.tap(); return }
        let group = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "filter.group.")).firstMatch
        if group.exists && group.isHittable {
            group.tap()
            let chip = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND value == %@",
                                                        "filter.control.", "未选择")).firstMatch
            if chip.waitForExistence(timeout: 5) { chip.tap(); return }
        }
        let menu = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "filter.control.")).firstMatch
        XCTAssertTrue(menu.exists, "No changeable control: \(app.debugDescription)")
        menu.tap()
        let options = app.collectionViews.buttons
        XCTAssertTrue(options.element(boundBy: 1).waitForExistence(timeout: 5), app.debugDescription)
        options.element(boundBy: 1).tap()
    }

    /// The panorama cover sits behind the header, scrolls away with it and stretches on pull-down.
    @MainActor
    func testDetailsPanoramaHeader() throws {
        openFirstItem(source: "MANGADEX", search: "MANGADEX")
        let chapter = revealChapter(in: app, timeout: 90)
        XCTAssertTrue(chapter.exists || app.staticTexts["details.error"].exists)
        app.collectionViews.firstMatch.swipeDown(velocity: .fast) // back to the top for the shot
        XCTAssertTrue(app.staticTexts["details.title"].exists, app.debugDescription)
        // The details cover and the panorama share one request; wait for it like a user would.
        let cover = app.descendants(matching: .any)["details.cover"]
        let loaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value != %@", "加载中"), object: cover)
        wait(for: [loaded], timeout: 60)
        XCTAssertEqual(cover.value as? String, "已加载", app.debugDescription)
        sleep(1)
        attach("Panorama at rest")
        let list = app.collectionViews.firstMatch
        list.swipeUp(velocity: .slow)
        sleep(1)
        attach("Panorama scrolled")
        list.swipeDown(velocity: .fast)
        list.swipeDown(velocity: .fast)
        sleep(1)
        attach("Panorama back at top")
        XCTAssertTrue(app.staticTexts["details.title"].isHittable)
        app.openSettings("settings.details")
        XCTAssertTrue(app.switches["settings.panorama"].waitForExistence(timeout: 10))
        attach("Panorama settings")
    }

    /// Browse and library share Android's four display modes; each screen keeps its own choice.
    @MainActor
    func testDisplayModesInBrowseAndLibrary() throws {
        let field = searchField()
        field.tap()
        field.typeText("MANGADEX")
        app.buttons["source.MANGADEX"].tap()
        let first = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        waitForEither(first, element("sources.error"), timeout: 90)
        XCTAssertTrue(first.exists, app.debugDescription)
        for mode in ["列表", "详细列表", "紧凑网格", "电影网格", "紧凑电影网格", "网格"] {
            chooseMode(mode)
            XCTAssertTrue(first.waitForExistence(timeout: 10), "\(mode): \(app.debugDescription)")
            sleep(2)
            attach("Browse \(mode)")
        }
        // Grid size: smallest and largest, then back to the default.
        for (position, name) in [(0.0, "50%"), (1.0, "150%"), (0.5, "100%")] {
            app.buttons["list.mode"].firstMatch.tap()
            let slider = app.sliders["list.gridSize"]
            XCTAssertTrue(slider.waitForExistence(timeout: 5), app.debugDescription)
            // XCTest assumes a normalized percentage; this control exposes the actual 50–150% scale.
            // Locate the thumb from that scale and drag it, including to both track endpoints.
            for _ in 0..<3 where slider.value as? String != name {
                let value = Double((slider.value as? String ?? "").replacingOccurrences(of: "%", with: ""))
                let scale = try XCTUnwrap(value)
                let current = (scale - 50) / 100
                let thumb = slider.coordinate(withNormalizedOffset: CGVector(dx: 0.05 + 0.9 * current, dy: 0.5))
                // A long drag can stop one step short; correct from the actual thumb value, keeping strict assertions.
                let destination = position == 0 ? -0.1 : position == 1 ? 1.1 : position
                let target = slider.coordinate(withNormalizedOffset: CGVector(dx: destination, dy: 0.5))
                thumb.press(forDuration: 0.3, thenDragTo: target, withVelocity: .slow, thenHoldForDuration: 0.3)
            }
            attach("Grid size control \(name)")
            XCTAssertEqual(slider.value as? String, name)
            app.buttons["list.options.done"].tap()
            sleep(1)
            if name != "100%" { attach("Browse grid \(name)") }
        }
        first.tap()
        let favourite = app.buttons["details.favourite"]
        XCTAssertTrue(favourite.waitForExistence(timeout: 60))
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: favourite)
        wait(for: [enabled], timeout: 60)
        let title = app.staticTexts["details.title"].label
        if favourite.label == "收藏" { favourite.tap() }
        app.tabBars.buttons["作品库"].tap()
        let card = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                                        "favourite.", String(title.prefix(12)))).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(app.buttons["category.all"].exists)
        for mode in ["列表", "详细列表", "紧凑网格", "电影网格", "紧凑电影网格", "网格"] {
            chooseMode(mode)
            XCTAssertTrue(card.waitForExistence(timeout: 10), "\(mode): \(app.debugDescription)")
            sleep(1)
            attach("Library \(mode)")
        }
        // Leave the shared simulator library as it was for other tests.
        let added = app.buttons[card.identifier]
        added.press(forDuration: 1.2)
        let remove = app.buttons["取消收藏"]
        attach("Library context actions")
        XCTAssertTrue(remove.waitForExistence(timeout: 5), app.debugDescription)
        remove.tap()
        XCTAssertTrue(added.waitForNonExistence(timeout: 10), app.debugDescription)
    }

    private func chooseMode(_ title: String) {
        let menu = app.buttons["list.mode"].firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 10), app.debugDescription)
        menu.tap()
        let option = app.buttons[title].firstMatch
        XCTAssertTrue(option.waitForExistence(timeout: 5), "\(title) option: \(app.debugDescription)")
        option.tap()
        app.buttons["list.options.done"].tap()
    }

    /// Per-source switches (Android's source settings): a disabled source leaves the browse list, the management
    /// tab shows it, and the browse layout/filters work on what remains. Multilingual plugins start with only the
    /// system language's sources enabled.
    @MainActor
    func testSourceSwitchesLayoutAndFiltersInBrowse() throws {
        let browseSource = app.buttons["source.MANGADEX"]
        let toggle = app.switches["source.toggle.MANGADEX"]
        let tabs = app.segmentedControls["repository.tabs"]
        /// Opens the 来源 tab searched for MangaDex and sets its switch.
        func setMangaDex(_ enabled: Bool) {
            dismissSearch()
            app.buttons["sources.repositories"].tap()
            XCTAssertTrue(tabs.waitForExistence(timeout: 30))
            tabs.buttons["来源"].tap()
            let search = app.pageSearchField()
            search.tap()
            search.typeText("MANGADEX")
            XCTAssertTrue(toggle.waitForExistence(timeout: 10), app.debugDescription)
            if (toggle.value as? String == "1") != enabled { toggle.switches.firstMatch.tap() }
            XCTAssertEqual(toggle.value as? String, enabled ? "1" : "0")
            attach("Source switches \(enabled)")
            dismissSearch()
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        func searchBrowse() {
            let field = searchField()
            field.tap()
            field.typeText("MANGADEX")
        }
        setMangaDex(true)
        searchBrowse()
        XCTAssertTrue(browseSource.waitForExistence(timeout: 30), app.debugDescription)
        setMangaDex(false)
        searchBrowse()
        XCTAssertTrue(browseSource.waitForNonExistence(timeout: 10), "Disabled source should leave the browse list")
        XCTAssertTrue(app.buttons["sources.manage"].waitForExistence(timeout: 10), app.debugDescription)
        // A multilingual parser plugin starts with only some of its sources (system language) enabled.
        dismissSearch()
        app.buttons["sources.repositories"].tap()
        XCTAssertTrue(tabs.waitForExistence(timeout: 30))
        tabs.buttons["来源"].tap()
        let header = app.descendants(matching: .any).matching(NSPredicate(format: "label MATCHES %@", ".*[0-9]+/[0-9]+.*")).firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 10), app.debugDescription)
        attach("Source groups")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        setMangaDex(true)
        searchBrowse()
        XCTAssertTrue(browseSource.waitForExistence(timeout: 10), app.debugDescription)
        dismissSearch()
        // Grid layout and a content-type filter.
        resetBrowseFilters(app)
        openBrowseMenu(app)
        tapMenuItem(app, "网格")
        tapMenuItem(app, "漫画")
        dismissMenu(app)
        let anyManga = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.MIHON_")).firstMatch
        XCTAssertTrue(anyManga.waitForNonExistence(timeout: 10), "Hiding manga should hide manga sources")
        attach("Browse grid without manga")
        openBrowseMenu(app)
        tapMenuItem(app, "漫画")
        dismissMenu(app)
        XCTAssertTrue(anyManga.waitForExistence(timeout: 10), app.debugDescription)
        attach("Browse grid")
        openBrowseMenu(app)
        tapMenuItem(app, "列表")
        dismissMenu(app)
    }

    // MARK: - Helpers

    /// Filters the source list first: other sources (e.g. imported CMS sites) may push the row off screen, and
    /// lazy list rows that were never on screen do not exist for XCUITest.
    private func openFirstItem(source name: String, search: String? = nil) {
        let field = searchField()
        field.tap()
        field.typeText(search ?? name)
        let source = app.buttons["source.\(name)"]
        XCTAssertTrue(source.waitForExistence(timeout: 30), "Source \(name) missing: \(app.debugDescription)")
        source.tap()
        let first = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        waitForEither(first, element("sources.error"), timeout: 90)
        XCTAssertTrue(first.exists, "Browse failed: \(app.debugDescription)")
        sleep(2) // let covers arrive for the screenshot
        attach("\(name) browse")
        first.tap()
    }

    private func openFirstChapter(timeout: TimeInterval = 90) {
        let chapter = revealChapter(in: app, timeout: timeout)
        XCTAssertTrue(chapter.exists, "Details failed: \(app.debugDescription)")
        attach("details")
        chapter.tap()
    }

    private func assertPlaybackAdvances() {
        let position = app.staticTexts["player.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 60), "Player failed: \(app.debugDescription)")
        let started = XCTNSPredicateExpectation(predicate: NSPredicate { [app] _, _ in
            guard let app else { return false }
            return (position.exists && position.label != "已播放 0 秒") || app.staticTexts["player.error"].exists
        }, object: nil)
        wait(for: [started], timeout: 120)
        XCTAssertFalse(app.staticTexts["player.error"].exists, "Playback failed: \(app.debugDescription)")
        XCTAssertTrue(playerControl(app, "player.close").exists, app.debugDescription)
    }

    private func selectReaderMode(_ mode: String) {
        let more = app.buttons["reader.more"]
        if !more.exists { app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        XCTAssertTrue(more.waitForExistence(timeout: 5), app.debugDescription)
        more.tap()
        app.segmentedControls.buttons["布局"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "阅读模式")).firstMatch.tap()
        app.buttons[mode].tap()
        app.buttons["reader.panel.close"].tap()
        XCTAssertTrue(more.waitForExistence(timeout: 5))
    }

    private func dismissSearch() {
        for title in ["关闭", "取消", "Cancel"] where app.buttons[title].firstMatch.exists {
            app.buttons[title].firstMatch.tap(); return
        }
    }

    /// The source list's search drawer hides once the list has scrolled; pull it back into view.
    private func searchField() -> XCUIElement {
        let field = app.pageSearchField()
        for _ in 0..<4 where !field.waitForExistence(timeout: 3) {
            app.collectionViews.firstMatch.swipeDown(velocity: .fast)
        }
        XCTAssertTrue(field.exists, app.debugDescription)
        return field
    }

    private func element(_ identifier: String) -> XCUIElement { app.descendants(matching: .any)[identifier] }

    private func waitForEither(_ first: XCUIElement, _ second: XCUIElement, timeout: TimeInterval) {
        let done = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in first.exists || second.exists }, object: nil)
        wait(for: [done], timeout: timeout)
    }

    private func attach(_ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
