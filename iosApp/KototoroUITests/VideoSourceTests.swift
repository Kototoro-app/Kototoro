import XCTest

/// Runs against `Scripts/serve-cms-fixture.py` on the host (the simulator shares its network). Default build, no JVM.
final class VideoSourceTests: XCTestCase {
    private let api = "http://127.0.0.1:8765/api.php/provide/vod/"

    @MainActor
    func testRelatedRecommendationsAlignAndOpenDetails() throws {
        try requireFixture()
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-space.enabled", "YES", "-space.kind", "VIDEO"]
        app.launch()
        app.mainTab("浏览").tap()
        func searchForSite() {
            let field = app.pageSearchField()
            field.tap(); field.typeText("相关推荐裁剪验证")
        }
        searchForSite()
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", "相关推荐裁剪验证")).firstMatch
        if !source.waitForExistence(timeout: 5) {
            app.terminate(); app.launch()
            importVideoSite(app, url: "http://127.0.0.1:8765/related/api.php/provide/vod/", title: "相关推荐裁剪验证")
            searchForSite()
        }
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription)
        source.tap()
        let work = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                    "source.content.", "测试动画")).firstMatch
        XCTAssertTrue(work.waitForExistence(timeout: 30), app.debugDescription)
        work.tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30), app.debugDescription)
        let title = app.staticTexts["相关推荐"]
        for _ in 0..<4 where !title.exists || !title.isHittable {
            app.collectionViews.firstMatch.swipeUp(velocity: .slow)
        }
        XCTAssertTrue(title.isHittable, app.debugDescription)
        let recommendation = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "测试电影")).firstMatch
        for _ in 0..<4 where !recommendation.isHittable || recommendation.frame.maxY > app.frame.maxY - 110 {
            app.collectionViews.firstMatch.swipeUp(velocity: .slow)
        }
        XCTAssertEqual(title.frame.minX, 16, accuracy: 1)
        XCTAssertEqual(recommendation.frame.minX, title.frame.minX, accuracy: 1)
        attach(app, "相关推荐标题与首张卡片")
        XCTAssertTrue(recommendation.isHittable, app.debugDescription)
        recommendation.tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["details.title"].label, "测试电影")
    }

    /// A saved filter loads back into the sheet, applies itself on opening once marked so, and can be deleted.
    @MainActor
    func testSavedFilterRestoresAndAutoApplies() throws {
        try requireFixture()
        continueAfterFailure = false
        let app = XCUIApplication()
        // The CMS site is a video source: browse in the video space whatever an earlier test left selected.
        app.launchArguments = ["-space.enabled", "YES", "-space.kind", "VIDEO"]
        app.launch()
        importVideoSite(app, url: api, title: "本地测试影视")
        func openSource() -> String {
            app.mainTab("浏览").tap()
            resetBrowseFilters(app)
            let field = app.pageSearchField()
            XCTAssertTrue(field.waitForExistence(timeout: 10))
            field.tap()
            // A search left from an earlier run stays in the field.
            if let text = field.value as? String, text != field.placeholderValue, !text.isEmpty {
                field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: text.count + 2))
            }
            field.typeText("本地测试影视")
            let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                          "source.JSON_TVBOX_", "本地测试影视")).firstMatch
            XCTAssertTrue(source.waitForExistence(timeout: 10), app.debugDescription)
            let name = String(source.identifier.dropFirst("source.".count))
            source.tap()
            return name
        }
        let sourceName = openSource()
        let first = element(app, "source.content.\(contentId(101, sourceName))")
        let movie = element(app, "source.content.\(contentId(102, sourceName))")
        XCTAssertTrue(first.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(movie.exists)
        let filter = app.buttons["browse.filterChip"]
        filter.tap()
        // A saved filter left by an earlier run would sit above the tags; remove it first.
        let leftover = app.buttons["filter.saved.只看动画"]
        if leftover.waitForExistence(timeout: 3) {
            leftover.press(forDuration: 1)
            app.buttons["删除"].tap()
            XCTAssertTrue(leftover.waitForNonExistence(timeout: 5))
        }
        let animation = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@",
                                                         "filter.tag.", "动画")).firstMatch
        for _ in 0..<4 where !animation.exists { app.collectionViews.firstMatch.swipeUp() }
        XCTAssertTrue(animation.waitForExistence(timeout: 10), app.debugDescription)
        animation.tap()
        let saveCurrent = app.buttons["filter.saveCurrent"]
        for _ in 0..<4 where !saveCurrent.isHittable { app.collectionViews.firstMatch.swipeDown() }
        saveCurrent.tap()
        let nameField = app.alerts.firstMatch.textFields.firstMatch
        XCTAssertTrue(nameField.waitForExistence(timeout: 5), app.debugDescription)
        nameField.typeText("只看动画")
        app.buttons["保存"].tap()
        let saved = app.buttons["filter.saved.只看动画"]
        XCTAssertTrue(saved.waitForExistence(timeout: 5), app.debugDescription)
        attach(app, "已保存的筛选")
        XCTAssertEqual(saved.value as? String, "已应用", "The sheet's selection is the saved filter")
        app.buttons["filter.reset"].tap()
        XCTAssertNotEqual(saved.value as? String, "已应用")
        saved.tap()
        XCTAssertEqual(saved.value as? String, "已应用", "Choosing the saved filter loads it back into the sheet")
        saved.press(forDuration: 1)
        app.buttons["打开来源时自动应用"].tap()
        app.buttons["filter.reset"].tap()
        app.buttons["filter.apply"].tap()
        XCTAssertTrue(movie.waitForExistence(timeout: 15), app.debugDescription)

        app.terminate()
        app.launch()
        _ = openSource()
        XCTAssertTrue(first.waitForExistence(timeout: 30), app.debugDescription)
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: movie)
        waitForExpectations(timeout: 15)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "筛选 1")).firstMatch.exists,
                      app.debugDescription)
        filter.tap()
        XCTAssertTrue(saved.waitForExistence(timeout: 10))
        saved.press(forDuration: 1)
        app.buttons["删除"].tap()
        XCTAssertTrue(saved.waitForNonExistence(timeout: 5), app.debugDescription)
        app.buttons["filter.reset"].tap()
        app.buttons["filter.apply"].tap()
    }

    @MainActor
    func testAppleCmsSourceImportsBrowsesAndPlaysNatively() throws {
        try requireFixture()
        let app = XCUIApplication()
        app.launch()
        importVideoSite(app, url: api, title: "本地测试影视")
        resetBrowseFilters(app)
        let sourceSearch = app.pageSearchField()
        XCTAssertTrue(sourceSearch.waitForExistence(timeout: 10))
        sourceSearch.tap(); sourceSearch.typeText("本地测试影视")

        // Other imported sites may already exist on the device; pick the fixture by name.
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", "本地测试影视")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 10))
        let sourceName = String(source.identifier.dropFirst("source.".count))
        controlFixture("/fail-page-2")
        source.tap()
        let first = element(app, "source.content.\(contentId(101, sourceName))")
        let second = element(app, "source.content.\(contentId(102, sourceName))")
        let third = element(app, "source.content.\(contentId(103, sourceName))")
        XCTAssertTrue(first.waitForExistence(timeout: 30), "Browse failed: \(app.debugDescription)")
        XCTAssertTrue(second.exists)
        let retry = app.buttons["browse.retry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 15), "Transient page-2 error should surface retry: \(app.debugDescription)")
        XCTAssertTrue(first.exists && second.exists, "Page-1 items must remain visible when page-2 fails")
        controlFixture("/recover-page-2")
        retry.tap()
        XCTAssertTrue(third.waitForExistence(timeout: 15), "Retry should append page-2 items without clearing page 1: \(app.debugDescription)")
        XCTAssertTrue(first.exists && second.exists)
        // Categories come from the CMS `class` list and filter on the server.
        let animation = app.buttons["category.动画"]
        XCTAssertTrue(animation.waitForExistence(timeout: 10))
        animation.tap()
        let hiddenMovie = NSPredicate(format: "exists == false")
        expectation(for: hiddenMovie, evaluatedWith: second)
        waitForExpectations(timeout: 15)
        attach(app, "Apple CMS browse grid")
        // The filter sheet mirrors the quick category, and resetting it restores the full listing.
        let filter = app.buttons["browse.filter"]
        XCTAssertTrue(filter.waitForExistence(timeout: 10), app.debugDescription)
        filter.tap()
        let included = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND value == %@",
                                                        "filter.tag.", "已包含")).firstMatch
        XCTAssertTrue(included.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(included.label, "动画")
        attach(app, "Browse filter sheet")
        app.buttons["filter.reset"].tap()
        app.buttons["filter.apply"].tap()
        XCTAssertTrue(second.waitForExistence(timeout: 15), "Reset filter should list every category again")
        animation.tap()
        expectation(for: hiddenMovie, evaluatedWith: second)
        waitForExpectations(timeout: 15)
        first.tap()

        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        let episode = revealChapter(in: app, timeout: 30)
        XCTAssertTrue(episode.exists, "Details failed: \(app.debugDescription)")
        XCTAssertTrue(element(app, "details.line").exists, "Two named lines should offer a picker")
        attach(app, "Apple CMS details and episodes")
        episode.tap()

        let position = app.staticTexts["player.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 20), "Player failed: \(app.debugDescription)")
        let advanced = NSPredicate(format: "label != %@ AND label != %@", "已播放 0 秒", "")
        expectation(for: advanced, evaluatedWith: position)
        waitForExpectations(timeout: 30)
        XCTAssertFalse(app.staticTexts["player.error"].exists)
        // Pause after proving playback advances, so automatic hiding cannot race the control assertions.
        playerControl(app, "player.playPause").tap()
        _ = playerControl(app, "player.close")
        XCTAssertEqual(app.buttons.matching(identifier: "player.close").count, 1)
        XCTAssertTrue(app.sliders["player.scrubber"].exists)
        XCTAssertEqual(app.staticTexts["player.title"].label, "第1集")
        attach(app, "Native HLS playback")

        playerControl(app, "player.episodes").tap()
        let choices = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "player.episode."))
        XCTAssertTrue(choices.firstMatch.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(choices.count, 2, "Only episodes of the chosen line belong in the picker")
        element(app, "player.episodeLine").tap()
        tapMenuItem(app, "备用")
        XCTAssertEqual(choices.count, 1, "The backup line has one episode, not a flattened list")
        element(app, "player.episodeLine").tap()
        tapMenuItem(app, "高清")
        XCTAssertEqual(choices.count, 2)
        attach(app, "Player episode and line picker")
        choices.matching(NSPredicate(format: "label BEGINSWITH %@", "第1集")).firstMatch.tap()
        XCTAssertTrue(app.navigationBars["选集"].waitForNonExistence(timeout: 5))

        playerControl(app, "player.next").tap()
        // The controls may fade while the next episode starts; reveal them to read its title.
        var title = ""
        for _ in 0..<15 where title != "第2集" {
            _ = playerControl(app, "player.close")
            title = app.staticTexts["player.title"].exists ? app.staticTexts["player.title"].label : ""
            if title != "第2集" { sleep(1) }
        }
        XCTAssertEqual(title, "第2集")
        // The second episode is the last on its line: no cross-line "next".
        XCTAssertTrue(playerControl(app, "player.close").exists)
        XCTAssertFalse(app.buttons["player.next"].exists)
        playerControl(app, "player.episodes").tap()
        let firstEpisode = choices.matching(NSPredicate(format: "label BEGINSWITH %@", "第1集")).firstMatch
        XCTAssertTrue(firstEpisode.waitForExistence(timeout: 5))
        firstEpisode.tap()
        _ = playerControl(app, "player.close")
        let firstTitle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "第1集"),
                                                   object: app.staticTexts["player.title"])
        XCTAssertEqual(XCTWaiter.wait(for: [firstTitle], timeout: 10), .completed)
        playerControl(app, "player.close").tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 10))

        app.terminate()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        let restoredSearch = app.pageSearchField()
        XCTAssertTrue(restoredSearch.waitForExistence(timeout: 10))
        restoredSearch.tap(); restoredSearch.typeText("本地测试影视")
        XCTAssertTrue(source.waitForExistence(timeout: 30), "Imported source did not persist")
    }

    /// Native search covers local favourites, pinned sources, toolbar search and query handoff to More.
    @MainActor
    func testGlobalAndPerSourceSearchReturnFixtureResults() throws {
        continueAfterFailure = false
        try requireFixture()
        let app = XCUIApplication()
        app.launchArguments += ["-search.pinnedOnly", "YES", "-search.kinds", ""]
        app.launch()
        importVideoSite(app, url: api, title: "全局搜索测试")
        resetBrowseFilters(app)
        let field = app.pageSearchField()
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap(); field.typeText("全局搜索测试")
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", "全局搜索测试")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 30))
        let name = String(source.identifier.dropFirst("source.".count))
        if !source.label.contains("已置顶") { source.swipeRight(); tapMenuItem(app, "置顶") }
        source.tap()
        let first = element(app, "source.content.\(contentId(101, name))")
        let movie = element(app, "source.content.\(contentId(102, name))")
        XCTAssertTrue(first.waitForExistence(timeout: 30))
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap(); search.typeText("测试动画\n")
        XCTAssertTrue(movie.waitForNonExistence(timeout: 15))
        XCTAssertTrue(first.exists)
        attach(app, "Per-source toolbar search")
        first.tap()
        let favourite = app.buttons["details.favourite"]
        XCTAssertTrue(favourite.waitForExistence(timeout: 30))
        if favourite.label == "收藏" { favourite.tap() }
        app.tabBars.buttons["搜索"].tap()
        let global = app.pageSearchField()
        XCTAssertTrue(global.waitForExistence(timeout: 10))
        global.tap(); global.typeText("测试动画\n")
        XCTAssertTrue(element(app, "search.library").waitForExistence(timeout: 15), app.debugDescription)
        let result = app.buttons.matching(identifier: "search.result.\(contentId(101, name))").firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 30), app.debugDescription)
        let more = app.buttons["search.more.\(name)"]
        XCTAssertTrue(more.waitForExistence(timeout: 30), app.debugDescription)
        attach(app, "Global local and source results")
        global.tap()
        let clear = global.buttons.firstMatch
        XCTAssertTrue(clear.waitForExistence(timeout: 5), app.debugDescription)
        clear.tap()
        let recent = app.buttons["search.recent.测试动画"]
        XCTAssertTrue(recent.waitForExistence(timeout: 10), "Clearing search must restore recent searches: \(app.debugDescription)")
        XCTAssertFalse(element(app, "search.progress").exists)
        XCTAssertFalse(element(app, "search.library").exists)
        attach(app, "Cleared search returns to recent queries")
        recent.tap()
        XCTAssertTrue(more.waitForExistence(timeout: 30), app.debugDescription)
        more.tap()
        XCTAssertTrue(first.waitForExistence(timeout: 30))
        XCTAssertFalse(movie.exists, "More must keep the submitted query")
    }

    /// The first catalog is a baseline; later additions get version badges in list and grid layouts.
    @MainActor
    func testNewSourceFilterAndRestartPreserveBaseline() throws {
        continueAfterFailure = false
        try requireFixture()
        let app = XCUIApplication()
        app.launchArguments += ["-sources.layout", "list", "-sources.grouping", "none",
                                "-sources.hiddenKinds", "", "-sources.hiddenEcosystems", "",
                                "-sources.catalogVersion", "0", "-sources.newOnly", "NO"]
        app.launch()
        let tag = String(UUID().uuidString.prefix(6))
        let prefix = "使用排序\(tag)"
        let oldTitle = "\(prefix)baseline甲"
        importVideoSite(app, url: "http://127.0.0.1:8765/usage-sites.json?run=\(tag)baseline", title: oldTitle)
        func source(_ title: String) -> XCUIElement {
            app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                            "source.JSON_TVBOX_", title)).firstMatch
        }
        let baselineSearch = app.pageSearchField()
        XCTAssertTrue(baselineSearch.waitForExistence(timeout: 10))
        baselineSearch.tap(); baselineSearch.typeText("\(prefix)\n")
        let old = source(oldTitle)
        XCTAssertTrue(old.waitForExistence(timeout: 30))
        app.terminate()
        app.launchArguments = ["-sources.grouping", "none",
                               "-sources.hiddenKinds", "", "-sources.hiddenEcosystems", ""]
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        openBrowseMenu(app)
        tapMenuItem(app, "列表")
        dismissMenu(app)
        importVideoSite(app, url: "http://127.0.0.1:8765/usage-sites.json?run=\(tag)", title: "\(prefix)甲")
        func searchSources() {
            let field = app.pageSearchField()
            XCTAssertTrue(field.waitForExistence(timeout: 10))
            field.tap()
            if field.buttons.firstMatch.exists { field.buttons.firstMatch.tap() }
            field.typeText("\(prefix)\n")
        }
        searchSources()
        let fresh = source("\(prefix)甲")
        XCTAssertTrue(fresh.waitForExistence(timeout: 30))
        XCTAssertTrue(old.exists)
        let notice = app.buttons["sources.newNotice"]
        XCTAssertTrue(notice.waitForExistence(timeout: 10))
        XCTAssertTrue(notice.label.contains("2 个来源"), app.debugDescription)
        notice.tap()
        XCTAssertTrue(old.waitForNonExistence(timeout: 10))
        XCTAssertTrue(fresh.exists)
        attach(app, "New-only filtered list")
        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        searchSources()
        XCTAssertTrue(fresh.waitForExistence(timeout: 30))
        XCTAssertFalse(old.exists, "The new-only filter must persist")
        let close = app.navigationBars.buttons["关闭"].firstMatch
        if close.exists { close.tap() }
        openBrowseMenu(app)
        tapMenuItem(app, "网格")
        dismissMenu(app)
        searchSources()
        XCTAssertTrue(fresh.waitForExistence(timeout: 10))
        XCTAssertLessThan(fresh.frame.width, app.frame.width / 2, "The source must be a grid tile")
        attach(app, "New-only filter in grid")
        let field = app.pageSearchField()
        field.tap(); field.buttons.firstMatch.tap(); field.typeText("\(oldTitle)\n")
        let reset = app.buttons["sources.showAll"]
        XCTAssertTrue(reset.waitForExistence(timeout: 10), "An empty new-only search must offer recovery")
        XCTAssertFalse(old.exists)
        attach(app, "Empty new-only search can show existing sources")
        reset.tap()
        XCTAssertTrue(old.waitForExistence(timeout: 10))
    }

    /// Shared source usage metrics drive recent and popularity ordering while keeping the pinned section first.
    @MainActor
    func testSourceUsageOrderingPersistsAndKeepsPinsFirst() throws {
        continueAfterFailure = false
        try requireFixture()
        let app = XCUIApplication()
        app.launchArguments += ["-sources.layout", "list", "-sources.grouping", "none",
                                "-sources.hiddenKinds", "", "-sources.hiddenEcosystems", ""]
        app.launch()
        let tag = String(UUID().uuidString.prefix(6))
        importVideoSite(app, url: "http://127.0.0.1:8765/usage-sites.json?run=\(tag)", title: "使用排序\(tag)甲")
        func source(_ title: String) -> XCUIElement {
            app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                "source.JSON_TVBOX_", title.replacingOccurrences(of: "使用排序", with: "使用排序\(tag)"))).firstMatch
        }
        func searchSources() {
            let field = app.pageSearchField()
            XCTAssertTrue(field.waitForExistence(timeout: 10))
            field.tap()
            let clear = field.buttons.firstMatch
            if clear.exists { clear.tap() }
            field.typeText("使用排序\(tag)\n")
            XCTAssertTrue(source("使用排序甲").waitForExistence(timeout: 30))
            XCTAssertTrue(source("使用排序乙").exists)
        }
        func chooseSort(_ title: String) {
            let close = app.navigationBars.buttons["关闭"].firstMatch
            if close.exists { close.tap() }
            openBrowseMenu(app)
            tapMenuItem(app, "排序")
            tapMenuItem(app, title)
            dismissMenu(app)
            searchSources()
        }
        func expectFirst(_ title: String, before other: String) {
            XCTAssertLessThan(source(title).frame.minY, source(other).frame.minY, app.debugDescription)
        }
        searchSources()
        for title in ["使用排序甲", "使用排序乙"] where source(title).label.contains("已置顶") {
            source(title).swipeRight(); tapMenuItem(app, "取消置顶")
        }
        chooseSort("手动顺序")
        let initiallyFirst = source("使用排序甲").frame.minY < source("使用排序乙").frame.minY
        let lower = source(initiallyFirst ? "使用排序乙" : "使用排序甲")
        lower.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@", "source.content.")).firstMatch.waitForExistence(timeout: 30))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertEqual(source("使用排序甲").frame.minY < source("使用排序乙").frame.minY, initiallyFirst,
                       "Visiting a source must not change the manual order")
        for (title, works) in [("使用排序甲", [101, 102]), ("使用排序乙", [101])] {
            let row = source(title)
            let name = String(row.identifier.dropFirst("source.".count))
            row.tap()
            for work in works {
                let item = element(app, "source.content.\(contentId(work, name))")
                XCTAssertTrue(item.waitForExistence(timeout: 30))
                item.tap()
                XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
                app.navigationBars.buttons.firstMatch.tap()
            }
            app.navigationBars.buttons.firstMatch.tap()
            XCTAssertTrue(source(title).waitForExistence(timeout: 15))
        }
        chooseSort("最近使用")
        expectFirst("使用排序乙", before: "使用排序甲")
        attach(app, "Recently used source ordering")
        chooseSort("使用频率")
        expectFirst("使用排序甲", before: "使用排序乙")
        attach(app, "Stored-work popularity ordering")
        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        searchSources()
        expectFirst("使用排序甲", before: "使用排序乙")
        chooseSort("最近使用")
        expectFirst("使用排序乙", before: "使用排序甲")
        chooseSort("使用频率")
        source("使用排序乙").swipeRight(); tapMenuItem(app, "置顶")
        expectFirst("使用排序乙", before: "使用排序甲")
        attach(app, "Pinned source remains first despite lower popularity")
        source("使用排序乙").swipeRight(); tapMenuItem(app, "取消置顶")
        expectFirst("使用排序甲", before: "使用排序乙")
    }

    /// Browse grouping (source type / content type / none), pinning to a top section and the reorder sheet.
    @MainActor
    func testBrowseGroupingPinningAndOrder() throws {
        try requireFixture()
        let app = XCUIApplication()
        app.launch()
        importVideoSite(app, url: api, title: "分组置顶测试")
        resetBrowseFilters(app)
        let site = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                    "source.JSON_TVBOX_", "分组置顶测试")).firstMatch
        func header(_ prefix: String) -> XCUIElement {
            app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
        }
        func choose(_ picker: String, _ option: String) {
            openBrowseMenu(app)
            tapMenuItem(app, picker)
            tapMenuItem(app, option)
            dismissMenu(app)
        }
        func closeSearch() {
            for label in ["关闭", "取消", "Cancel"] where app.buttons[label].firstMatch.exists {
                app.buttons[label].firstMatch.tap(); return
            }
        }
        // Many sites may exist on the device: find this one by search, then pin it from the row's leading swipe.
        let search = app.pageSearchField()
        for _ in 0..<4 where !search.waitForExistence(timeout: 3) { app.collectionViews.firstMatch.swipeDown(velocity: .fast) }
        XCTAssertTrue(search.exists, app.debugDescription)
        search.tap()
        search.typeText("分组置顶测试")
        XCTAssertTrue(site.waitForExistence(timeout: 30), app.debugDescription)
        // Pins persist; an interrupted earlier run may have left this site pinned.
        if !site.label.contains("已置顶") {
            site.swipeRight()
            tapMenuItem(app, "置顶")
        }
        closeSearch()
        XCTAssertTrue(header("置顶 · ").waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(site.waitForExistence(timeout: 10) && site.label.contains("已置顶"), app.debugDescription)
        choose("分组", "按内容类型")
        XCTAssertTrue(header("视频 · ").waitForExistence(timeout: 10), app.debugDescription)
        choose("分组", "不分组")
        XCTAssertTrue(header("其他来源 · ").waitForExistence(timeout: 10), app.debugDescription)
        attach(app, "Pinned source, ungrouped")
        // The reorder sheet lists every enabled source with the pinned ones first.
        openBrowseMenu(app)
        tapMenuItem(app, "sources.reorder")
        XCTAssertTrue(app.navigationBars["调整来源顺序"].waitForExistence(timeout: 10), app.debugDescription)
        attach(app, "Reorder sources")
        app.buttons["sources.reorder.done"].tap()
        XCTAssertTrue(app.navigationBars["调整来源顺序"].waitForNonExistence(timeout: 10))
        // Restore: unpin and group by source type again.
        site.swipeRight()
        tapMenuItem(app, "取消置顶")
        XCTAssertTrue(header("置顶 · ").waitForNonExistence(timeout: 10))
        choose("分组", "按来源类型")
        XCTAssertTrue(header("苹果 CMS / TVBox 影视 · ").waitForExistence(timeout: 10), app.debugDescription)
    }

    @MainActor
    func testRealAppleCmsSitePlaysOnSimulator() throws {
        guard let api = ProcessInfo.processInfo.environment["KOTOTORO_REAL_CMS"], !api.isEmpty else {
            throw XCTSkip("Set TEST_RUNNER_KOTOTORO_REAL_CMS to a real Apple CMS API URL")
        }
        let app = XCUIApplication()
        app.launch()
        importVideoSite(app, url: api, title: nil, timeout: 60)

        let host = URL(string: api)?.host ?? api
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", host)).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 10))
        source.tap()
        let first = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        let listed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            first.exists || app.descendants(matching: .any)["sources.error"].exists
        }, object: nil)
        wait(for: [listed], timeout: 90)
        XCTAssertTrue(first.exists, "Browse failed: \(app.debugDescription)")
        sleep(3) // let poster images arrive for the screenshot
        attach(app, "Real CMS grid")
        first.tap()

        let episode = revealChapter(in: app, timeout: 90)
        let detailed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            episode.exists || app.staticTexts["details.error"].exists
        }, object: nil)
        wait(for: [detailed], timeout: 90)
        XCTAssertTrue(episode.exists, "Details failed: \(app.debugDescription)")
        attach(app, "Real CMS details")
        episode.tap()
        let position = app.staticTexts["player.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let started = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (position.exists && position.label != "已播放 0 秒") || app.staticTexts["player.error"].exists
        }, object: nil)
        wait(for: [started], timeout: 90)
        XCTAssertFalse(app.staticTexts["player.error"].exists, "Playback failed: \(app.debugDescription)")
        attach(app, "Real CMS playback")
    }

    /// Same Java String hash rule as the shared runtime: positiveHash("item|cms:<id>").
    private func contentId(_ vodId: Int, _ sourceName: String) -> String {
        var hash: Int64 = 1125899906842597
        for unit in "\(sourceName)|item|cms:\(vodId)".utf16 { hash = hash &* 31 &+ Int64(unit) }
        return String(hash & Int64.max)
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    private func controlFixture(_ path: String) {
        let done = expectation(description: "fixture \(path)")
        var ok = false
        URLSession.shared.dataTask(with: URL(string: "http://127.0.0.1:8765\(path)")!) { _, response, _ in
            ok = (response as? HTTPURLResponse)?.statusCode == 200
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 5)
        XCTAssertTrue(ok, "Fixture control \(path) failed")
    }

    private func requireFixture() throws {
        let done = expectation(description: "fixture health")
        var reachable = false
        URLSession.shared.dataTask(with: URL(string: "http://127.0.0.1:8765/health")!) { _, response, _ in
            reachable = (response as? HTTPURLResponse)?.statusCode == 200
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 5)
        if !reachable { throw XCTSkip("Start iosApp/Scripts/serve-cms-fixture.py to run this test") }
    }
}

/// Apple CMS / TVBox sites are added and listed in extension management (仓库与扩展 → 添加仓库, type 苹果 CMS /
/// TVBox), then the test returns to the browse list.
func importVideoSite(_ app: XCUIApplication, url: String, title: String?, timeout: TimeInterval = 30) {
    app.tabBars.buttons["浏览"].tap()
    let repositories = app.buttons["sources.repositories"]
    XCTAssertTrue(repositories.waitForExistence(timeout: 30))
    repositories.tap()
    let actions = app.buttons["repository.actions"]
    XCTAssertTrue(actions.waitForExistence(timeout: 30))
    _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: actions)],
                         timeout: 90)
    actions.tap()
    app.buttons["repository.add"].tap()
    let address = app.textFields["repository.address"]
    XCTAssertTrue(address.waitForExistence(timeout: 10))
    // Pick the type before typing: the keyboard would otherwise cover the picker row.
    let kind = app.buttons["repository.kind"]
    XCTAssertTrue(kind.waitForExistence(timeout: 5))
    kind.tap()
    let tvbox = app.buttons["苹果 CMS / TVBox"].firstMatch
    XCTAssertTrue(tvbox.waitForExistence(timeout: 5), app.debugDescription)
    tvbox.tap()
    address.tap()
    address.typeText(url)
    if let title {
        let field = app.textFields["repository.title"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), app.debugDescription)
        field.tap()
        // The first tap can land while the address field still holds focus; tap again until this one takes it.
        for _ in 0..<3 where (field.value(forKey: "hasKeyboardFocus") as? Bool) != true { sleep(1); field.tap() }
        field.typeText(title)
    }
    let importButton = app.buttons["repository.import"]
    importButton.tap()
    // Retry a missed toolbar tap only while the import has not started.
    if !address.waitForNonExistence(timeout: 3), importButton.exists, importButton.isEnabled {
        importButton.tap()
    }
    XCTAssertTrue(address.waitForNonExistence(timeout: timeout), "Import failed: \(app.debugDescription)")
    let status = app.staticTexts["repository.status"]
    XCTAssertTrue(status.waitForExistence(timeout: 5) && status.label.contains("已导入"), app.debugDescription)
    if let title {
        // Earlier runs leave many sites and repositories; search the management list like a user would.
        let search = app.pageSearchField()
        if search.waitForExistence(timeout: 5) { search.tap(); search.typeText(title) }
        let site = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                                        "site.", title)).firstMatch
        XCTAssertTrue(site.waitForExistence(timeout: 10), "Imported site should be listed: \(app.debugDescription)")
        for label in ["关闭", "取消", "Cancel"] where app.buttons[label].firstMatch.exists {
            app.buttons[label].firstMatch.tap(); break
        }
    }
    app.navigationBars.buttons.element(boundBy: 0).tap()
}

/// Menus that stay open while toggling are closed by tapping outside them: the empty leading part of the
/// navigation bar never hits a row (the status bar does not dismiss a menu).
func dismissMenu(_ app: XCUIApplication) {
    app.navigationBars.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: 0.15)).tap()
    _ = app.buttons["sources.filter"].waitForExistence(timeout: 3)
}

func openBrowseMenu(_ app: XCUIApplication) {
    let menu = app.buttons["sources.filter"]
    XCTAssertTrue(menu.waitForExistence(timeout: 10), app.debugDescription)
    menu.tap()
}

/// Menu items appear with an animation; tapping before they settle can hit the row below.
func tapMenuItem(_ app: XCUIApplication, _ title: String) {
    let item = app.buttons[title].firstMatch
    XCTAssertTrue(item.waitForExistence(timeout: 5), "\(title): \(app.debugDescription)")
    _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: item)],
                         timeout: 3)
    item.tap()
}

/// Content/source-type filters persist across runs; start each browse test with every type shown.
func resetBrowseFilters(_ app: XCUIApplication) {
    openBrowseMenu(app)
    let reset = app.buttons["显示全部类型"].firstMatch
    if reset.waitForExistence(timeout: 2) { reset.tap() }
    dismissMenu(app)
}

/// Player controls hide during playback; a tap on the video brings them back before using one.
func playerControl(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
    let control = app.buttons[identifier]
    for _ in 0..<3 {
        if control.exists && control.isHittable { return control }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        _ = control.waitForExistence(timeout: 3)
    }
    return control
}
