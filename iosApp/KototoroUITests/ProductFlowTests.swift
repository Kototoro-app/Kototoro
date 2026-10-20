import XCTest

final class ProductFlowTests: XCTestCase {
    /// Needs at least one favourite. Selection starts from a work's menu and borrows the top bar; the library's
    /// other tools sit in its more menu; a source's listing searches with the field under its title.
    @MainActor
    func testLibrarySelectionFromMenuMoreMenuAndSourceSearchDrawer() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-space.enabled", "NO"]
        app.launch()
        func shot(_ name: String) {
            let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name
            attachment.lifetime = .keepAlways; add(attachment)
        }
        app.mainTab("作品库").tap()
        let work = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "favourite.")).firstMatch
        XCTAssertTrue(work.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(app.buttons["library.select"].exists, "Selection has no toolbar button of its own")
        work.press(forDuration: 0.8)
        XCTAssertTrue(app.buttons["library.select"].waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["library.select"].tap()
        let done = app.navigationBars.buttons["library.selectDone"]
        XCTAssertTrue(done.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(work.isSelected)
        XCTAssertTrue(app.navigationBars.buttons["library.selection.unfavourite"].isEnabled)
        XCTAssertTrue(app.navigationBars.buttons["library.selection.migrate"].exists)
        XCTAssertFalse(app.buttons["library.more"].exists, "Selection replaces the toolbar's buttons")
        shot("Library selection toolbar")
        done.tap()
        XCTAssertTrue(app.buttons["library.more"].waitForExistence(timeout: 5))
        XCTAssertFalse(work.isSelected)

        app.buttons["library.more"].tap()
        XCTAssertTrue(app.buttons["library.migrate"].waitForExistence(timeout: 5))
        shot("Library more menu")
        app.buttons["library.duplicates"].tap()
        let empty = app.descendants(matching: .any)["duplicates.empty"]
        let merge = app.buttons["duplicates.merge"].firstMatch
        let found = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in empty.exists || merge.exists }, object: nil)
        XCTAssertEqual(XCTWaiter().wait(for: [found], timeout: 30), .completed, app.debugDescription)
        shot("Find duplicates")
        // Fuzzy matching reveals its threshold; a released slider searches again.
        let fuzzy = app.switches["duplicates.fuzzy"]
        XCTAssertTrue(fuzzy.waitForExistence(timeout: 5), app.debugDescription)
        if fuzzy.value as? String == "1" { fuzzy.switches.firstMatch.tap() }
        XCTAssertFalse(app.sliders["duplicates.threshold"].exists)
        fuzzy.switches.firstMatch.tap()
        let slider = app.sliders["duplicates.threshold"]
        XCTAssertTrue(slider.waitForExistence(timeout: 5), app.debugDescription)
        slider.adjust(toNormalizedSliderPosition: 0.2)
        XCTAssertEqual(XCTWaiter().wait(for: [XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in empty.exists || merge.exists }, object: nil)], timeout: 30), .completed,
            app.debugDescription)
        shot("Find duplicates fuzzy")
        fuzzy.switches.firstMatch.tap()
        XCTAssertTrue(slider.waitForNonExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.mainTab("浏览").tap()
        let local = app.buttons["source.LOCAL"]
        for _ in 0..<5 where !local.waitForExistence(timeout: 3) { app.swipeUp() }
        XCTAssertTrue(local.exists, app.debugDescription)
        local.tap()
        XCTAssertFalse(app.navigationBars.buttons["搜索"].exists, "The listing has no toolbar search button")
        let field = app.pageSearchField()
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(field.placeholderValue?.hasSuffix("中搜索"), true)
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "The field should take focus")
        field.typeText("abc")
        shot("Source search drawer")
        // The drawer's close button: "关闭" beside the field on current systems, "取消" on earlier ones.
        let cancel = app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", ["关闭", "取消"])).firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 5), app.debugDescription)
        cancel.tap()
        // Closed, and still closed a moment later: the old toolbar search refocused itself right away.
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 3), "Cancel closes the search")
        sleep(1)
        XCTAssertFalse(app.keyboards.firstMatch.exists, "The search reopened")
        shot("Source search closed")
    }

    @MainActor
    func testBatchMigrationReviewsTwoWorksAndMissingSourceRecoverySurvivesRestart() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-space.enabled", "NO", "-listMode.browse", "grid"]
        app.launch()
        let tag = String(UUID().uuidString.prefix(6))
        let categoryName = "迁移回归 \(tag)"
        let firstSource = "使用排序\(tag)甲"
        let secondSource = "使用排序\(tag)乙"
        app.mainTab("作品库").tap()
        let add = app.buttons["category.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: add)
        waitForExpectations(timeout: 30)
        add.tap()
        let field = app.textFields["category.title"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText(categoryName)
        app.buttons["category.save"].tap()
        XCTAssertTrue(field.waitForNonExistence(timeout: 10))
        importVideoSite(app, url: "http://127.0.0.1:8765/usage-sites.json?run=\(tag)", title: nil)
        let search = app.pageSearchField()
        search.tap(); search.typeText(firstSource)
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                       "source.JSON_TVBOX_", firstSource)).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 15)); source.tap()
        for title in ["测试动画", "测试电影"] {
            let work = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                         "source.content.", title)).firstMatch
            XCTAssertTrue(work.waitForExistence(timeout: 15))
            work.press(forDuration: 0.8)
            app.buttons["browse.favourite"].tap()
            XCTAssertTrue(app.buttons["favourite.save"].waitForExistence(timeout: 10))
            let category = app.buttons.matching(NSPredicate(format: "identifier MATCHES %@", #"favourite\.category\.[0-9]+"#))
                .allElementsBoundByIndex.first { $0.isHittable }
            XCTAssertNotNil(category, app.debugDescription)
            category?.tap(); app.buttons["favourite.save"].tap()
            XCTAssertTrue(app.buttons["favourite.save"].waitForNonExistence(timeout: 10))
        }
        app.mainTab("作品库").tap()
        app.buttons["library.more"].tap()
        app.buttons["library.migrate"].tap()
        XCTAssertTrue(app.segmentedControls["migration.scope"].waitForExistence(timeout: 10))
        let emptySearch = app.pageSearchField()
        emptySearch.tap(); emptySearch.typeText("no-migration-match-\(tag)")
        XCTAssertFalse(app.buttons["migration.selectVisible"].isEnabled,
                       "An empty search must not select invisible favourites")
        app.buttons["清除文本"].firstMatch.tap()
        let closeLibrarySearch = app.navigationBars.buttons["关闭"].firstMatch
        if closeLibrarySearch.exists { closeLibrarySearch.tap() }
        app.segmentedControls["migration.scope"].buttons["按原来源"].tap()
        let origin = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                       "migration.source.", firstSource)).firstMatch
        let originSearch = app.pageSearchField()
        originSearch.tap(); originSearch.typeText(firstSource)
        XCTAssertTrue(origin.waitForExistence(timeout: 10)); origin.tap()
        app.buttons["migration.sources"].tap()
        let targetSearch = app.textFields["migration.sources.query"]
        XCTAssertTrue(targetSearch.waitForExistence(timeout: 10))
        targetSearch.tap(); targetSearch.typeText(secondSource)
        targetSearch.typeText("\n")
        let targetSource = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                             "migration.targetSource.", secondSource)).firstMatch
        XCTAssertTrue(targetSource.waitForExistence(timeout: 10)); targetSource.tap()
        XCTAssertTrue(targetSource.isSelected, app.debugDescription)
        app.buttons["migration.sources.done"].tap()
        XCTAssertTrue(app.buttons["migration.search"].isEnabled, app.debugDescription)
        app.buttons["migration.search"].tap()
        let workRows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "migration.work."))
        XCTAssertTrue(workRows.firstMatch.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: workRows.firstMatch)
        waitForExpectations(timeout: 30)
        XCTAssertEqual(workRows.count, 2)
        let skipped = workRows.firstMatch
        skipped.tap()
        app.buttons["migration.skip"].tap()
        app.buttons["migration.useExact"].tap()
        XCTAssertEqual(skipped.value as? String, "已跳过", "Bulk matching must respect an explicit skip")
        XCTAssertTrue(app.buttons["migration.execute"].label.contains("1 部"))
        skipped.tap()
        let candidate = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "migration.candidate.")).firstMatch
        XCTAssertTrue(candidate.waitForExistence(timeout: 10), app.debugDescription)
        candidate.tap()
        let execute = app.buttons["migration.execute"]
        for _ in 0..<5 where !execute.isHittable { app.swipeUp() }
        XCTAssertTrue(execute.isEnabled)
        // Selection alone never mutates the library; confirm explicitly, using the safer copy default.
        execute.tap()
        app.buttons["确认复制"].tap()
        let completed = NSPredicate(format: "label CONTAINS %@", "2 部成功")
        expectation(for: completed, evaluatedWith: app.staticTexts["migration.progress"])
        waitForExpectations(timeout: 30)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "Batch migration completed with two reviewed targets"; shot.lifetime = .keepAlways; self.add(shot)
        app.terminate(); app.launch()
        app.mainTab("浏览").tap()
        let browseSearch = app.pageSearchField()
        browseSearch.tap(); browseSearch.typeText(secondSource)
        let installedTarget = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                                "source.JSON_TVBOX_", secondSource)).firstMatch
        XCTAssertTrue(installedTarget.waitForExistence(timeout: 10))
        installedTarget.press(forDuration: 0.8)
        app.buttons["删除站点"].tap()
        XCTAssertTrue(installedTarget.waitForNonExistence(timeout: 10))
        // Native search owns keyboard focus and intercepts tab hit-testing until explicitly closed.
        let closeSearch = app.navigationBars.buttons["关闭"].firstMatch
        if closeSearch.waitForExistence(timeout: 3) { closeSearch.tap() }
        app.mainTab("作品库").tap()
        XCTAssertTrue(app.navigationBars["作品库"].waitForExistence(timeout: 10))
        let notice = app.buttons["recovery.notice"]
        XCTAssertTrue(notice.waitForExistence(timeout: 15), app.debugDescription); notice.tap()
        XCTAssertTrue(app.navigationBars["补齐缺失来源"].waitForExistence(timeout: 10))
        let recoverySearch = app.pageSearchField()
        recoverySearch.tap(); recoverySearch.typeText(secondSource)
        let missing = app.staticTexts[secondSource]
        for _ in 0..<8 {
            if missing.waitForExistence(timeout: 1) { break }
            app.swipeUp()
        }
        XCTAssertTrue(missing.waitForExistence(timeout: 10), app.debugDescription)
        let recovery = XCTAttachment(screenshot: app.screenshot())
        recovery.name = "Missing source shows affected library works and migration action"; recovery.lifetime = .keepAlways; self.add(recovery)
        app.terminate(); app.launch()
        app.mainTab("作品库").tap()
        XCTAssertTrue(app.buttons["recovery.notice"].waitForExistence(timeout: 15))
    }

    @MainActor
    func testBrowseAndDetailsShareMultiCategoryFavouritesAndBulkSelection() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-space.enabled", "NO", "-listMode.browse", "grid"]
        app.launch()
        let run = String(UUID().uuidString.prefix(6))
        let names = ["待读 \(run)", "在读 \(run)", "合集 \(run)"]
        app.mainTab("作品库").tap()
        for name in names {
            let addCategory = app.buttons["category.add"]
            XCTAssertTrue(addCategory.waitForExistence(timeout: 30))
            expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: addCategory)
            waitForExpectations(timeout: 30)
            addCategory.tap()
            let field = app.textFields["category.title"]
            XCTAssertTrue(field.waitForExistence(timeout: 5))
            field.tap(); field.typeText(name)
            app.buttons["category.save"].tap()
            XCTAssertTrue(field.waitForNonExistence(timeout: 10))
        }
        let siteName = "收藏操作 \(run)"
        importVideoSite(app, url: "http://127.0.0.1:8765/api.php/provide/vod/?favourites=\(run)", title: siteName)
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap(); search.typeText(siteName)
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", siteName)).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 20)); source.tap()
        func work(_ title: String) -> XCUIElement {
            app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                             "source.content.", title)).firstMatch
        }
        /// The dialog's list is lazy and test devices keep earlier runs' categories, so scroll the row into view.
        func category(_ title: String) -> XCUIElement {
            let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@",
                                                       "favourite.category.", title)).firstMatch
            let any = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "favourite.category.")).firstMatch
            guard any.waitForExistence(timeout: 10) else { return row }
            // The new categories are the last rows: drag the list upward until the row can be tapped.
            for _ in 0..<20 where !row.isHittable {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
                    .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)))
            }
            return row
        }
        func openCategories(_ title: String) {
            let item = work(title)
            XCTAssertTrue(item.waitForExistence(timeout: 20), app.debugDescription)
            item.press(forDuration: 0.8)
            XCTAssertTrue(app.buttons["browse.favourite"].waitForExistence(timeout: 5))
            app.buttons["browse.favourite"].tap()
            XCTAssertTrue(category(names[0]).waitForExistence(timeout: 10))
        }
        func save() {
            XCTAssertTrue(app.buttons["favourite.save"].isEnabled, app.debugDescription)
            app.buttons["favourite.save"].tap()
            XCTAssertTrue(app.buttons["favourite.save"].waitForNonExistence(timeout: 10))
        }
        openCategories("测试动画")
        XCTAssertEqual(category(names[0]).value as? String, "未选中")
        category(names[0]).tap()
        app.buttons["favourite.cancel"].tap()
        XCTAssertTrue(app.buttons["favourite.cancel"].waitForNonExistence(timeout: 5))
        openCategories("测试动画")
        XCTAssertEqual(category(names[0]).value as? String, "未选中", "Cancel must not favourite the work")
        category(names[0]).tap(); category(names[1]).tap(); save()

        // Exercise the same selection controls in rows after adding from the grid's context menu.
        app.buttons["list.mode"].tap()
        tapMenuItem(app, "列表")
        app.buttons["list.options.done"].tap()
        work("测试动画").press(forDuration: 0.8)
        XCTAssertTrue(app.buttons["browse.select"].waitForExistence(timeout: 5))
        app.buttons["browse.select"].tap()
        XCTAssertTrue(app.buttons["browse.selection.cancel"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["list.mode"].exists)
        XCTAssertFalse(app.buttons["browse.settings"].exists)
        work("测试电影").tap()
        XCTAssertTrue(work("测试动画").isSelected && work("测试电影").isSelected, app.debugDescription)
        let actionBar = XCTAttachment(screenshot: app.screenshot()); actionBar.name = "Browse bulk selection action bar"
        actionBar.lifetime = .keepAlways; add(actionBar)
        app.buttons["browse.selection.favourite"].tap()
        XCTAssertTrue(category(names[0]).waitForExistence(timeout: 10))
        XCTAssertEqual(category(names[0]).value as? String, "部分选中")
        XCTAssertEqual(category(names[1]).value as? String, "部分选中")
        category(names[2]).tap()
        let dialog = XCTAttachment(screenshot: app.screenshot()); dialog.name = "Mixed memberships and multi-category selection"
        dialog.lifetime = .keepAlways; add(dialog)
        save()
        XCTAssertFalse(app.buttons["browse.selection.cancel"].exists)

        work("测试动画").tap()
        XCTAssertTrue(app.buttons["details.favourite"].waitForExistence(timeout: 20))
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: app.buttons["details.favourite"])
        waitForExpectations(timeout: 20)
        app.buttons["details.favourite"].tap()
        XCTAssertTrue(category(names[0]).waitForExistence(timeout: 10))
        for name in names { XCTAssertEqual(category(name).value as? String, "已选中") }
        category(names[1]).tap(); save()
        app.buttons["details.favourite"].tap()
        XCTAssertTrue(category(names[0]).waitForExistence(timeout: 10))
        XCTAssertEqual(category(names[0]).value as? String, "已选中")
        XCTAssertEqual(category(names[1]).value as? String, "未选中")
        XCTAssertEqual(category(names[2]).value as? String, "已选中")
        app.buttons["favourite.cancel"].tap()
        XCTAssertTrue(app.buttons["favourite.cancel"].waitForNonExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        openCategories("测试电影")
        XCTAssertEqual(category(names[0]).value as? String, "未选中")
        XCTAssertEqual(category(names[1]).value as? String, "未选中")
        XCTAssertEqual(category(names[2]).value as? String, "已选中")
        app.buttons["favourite.cancel"].tap()
        XCTAssertTrue(app.buttons["favourite.cancel"].waitForNonExistence(timeout: 5))

        // Swiping sideways across a library work switches category without opening that work.
        app.mainTab("作品库").tap()
        let all = app.buttons["category.all"]
        XCTAssertTrue(all.waitForExistence(timeout: 10))
        if !all.isSelected { all.tap() }
        let favourite = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                         "favourite.", "测试动画")).firstMatch
        XCTAssertTrue(favourite.waitForExistence(timeout: 10), app.debugDescription)
        let before = XCTAttachment(screenshot: app.screenshot()); before.name = "Library before swipe"
        before.lifetime = .keepAlways; add(before)
        let start = favourite.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: -220, dy: 0)))
        let after = XCTAttachment(screenshot: app.screenshot()); after.name = "Library after swipe"
        after.lifetime = .keepAlways; add(after)
        expectation(for: NSPredicate(format: "selected == false"), evaluatedWith: all)
        waitForExpectations(timeout: 5)
        XCTAssertFalse(app.buttons["details.favourite"].waitForExistence(timeout: 2),
                       "A swipe over a work must not open it: \(app.debugDescription)")
        XCTAssertTrue(all.isHittable)
        // A plain tap still opens the work.
        all.tap()
        XCTAssertTrue(favourite.waitForExistence(timeout: 10))
        favourite.tap()
        XCTAssertTrue(app.buttons["details.favourite"].waitForExistence(timeout: 20))
    }

    /// A repo hub "导入" link opens the app from outside, imports the repository and installs an APK whose manifest
    /// has no library marker (third-party repositories; Mihon reads the library from the version name).
    /// VomicMH is used because CopyManga is NSFW-flagged and hidden by default.
    @MainActor
    func testExternalRepositoryLinkImportsAndInstallsMarkerlessApk() throws {
        try installRepositoryExtension(packageName: "eu.kanade.tachiyomi.extension.zh.vomicmh")
    }

    @MainActor
    func testCopyMangaInitializesAndPersistsInstallation() throws {
        try installRepositoryExtension(packageName: "eu.kanade.tachiyomi.extension.zh.copymanga")
    }

    @MainActor
    func testMh1234InitializesAndPersistsInstallation() throws {
        try installRepositoryExtension(packageName: "eu.kanade.tachiyomi.extension.zh.mh1234",
            index: "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb", repositoryQuery: "Keiyoushi")
    }

    /// Tsundoku (novel) extension from the NovelSourcery repository: installs, survives relaunch and lists works.
    @MainActor
    func testTsundokuRepositoryInstallsAndBrowses() throws {
        // A Chinese source: only the system's languages are enabled by default, so an English one is not listed.
        try installRepositoryExtension(packageName: "eu.kanade.tachiyomi.novelextension.zh.qimao",
            // The app stores GitHub "raw" links under their raw.githubusercontent.com address.
            index: "https://raw.githubusercontent.com/NovelSourcery/extensions/repo/index.pb", repositoryQuery: "NovelSourcery",
            kind: "TSUNDOKU", sourceTitle: "Qimao")
    }

    /// Aniyomi (video) extension from the yuzono repository: installs, survives relaunch and lists works.
    @MainActor
    func testAniyomiRepositoryInstallsAndBrowses() throws {
        try installRepositoryExtension(packageName: "eu.kanade.tachiyomi.animeextension.zh.xfani",
            index: "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json", repositoryQuery: "anime-repo",
            kind: "ANIYOMI", sourceTitle: "稀饭动漫")
    }

    /// Opens an installed source from the browse list by its title and waits for its first works.
    private func assertSourceLists(_ app: XCUIApplication, ecosystem: String, title: String) {
        // A fresh launch starts on the source list itself rather than the extension management pushed over it.
        // Host stack traces of failed calls go to <data>/source-failures.log.
        app.terminate()
        app.launchEnvironment["KOTOTORO_SOURCE_DIAGNOSTICS"] = "1"
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90))
        let search = app.pageSearchField()
        for _ in 0..<4 where !search.waitForExistence(timeout: 3) { app.collectionViews.firstMatch.swipeDown(velocity: .fast) }
        XCTAssertTrue(search.exists, app.debugDescription)
        search.tap(); search.typeText(title)
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS[c] %@",
                                                      "source.\(ecosystem)_", title)).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 30), "\(title) missing from browse: \(app.debugDescription)")
        source.tap()
        let first = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        let error = app.descendants(matching: .any)["sources.error"]
        _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in first.exists || error.exists },
                                                             object: nil)], timeout: 180)
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "\(title) listing"
        shot.lifetime = .keepAlways; add(shot)
        XCTAssertTrue(first.exists, "\(title) listing failed: \(error.exists ? error.label : app.debugDescription)")
    }

    @MainActor
    private func installRepositoryExtension(packageName: String,
        index: String = "https://raw.githubusercontent.com/LittleSurvival/copymanga-copy20/repo/index.min.json",
        repositoryQuery: String = "copymanga-copy20", kind: String = "MIHON", sourceTitle: String? = nil) throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        guard app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90) else {
            throw XCTSkip("Requires the real API JVM profile (online)")
        }
        let encoded = index.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        app.open(URL(string: "kototoro://add-repo?url=\(encoded)&kind=\(kind)")!)
        let tabs = app.segmentedControls["repository.tabs"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 30), "The link should open repositories: \(app.debugDescription)")
        let imported = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已添加")) || app.descendants(matching: .any)["repository.error"].exists
        }, object: nil)
        wait(for: [imported], timeout: 90)
        XCTAssertFalse(app.descendants(matching: .any)["repository.error"].exists, app.debugDescription)
        tabs.buttons["仓库"].tap()
        let repositorySearch = app.pageSearchField()
        XCTAssertTrue(repositorySearch.waitForExistence(timeout: 10))
        repositorySearch.tap(); repositorySearch.typeText(repositoryQuery + "\n")
        let repository = app.buttons["repository.row.\(kind).\(index)"]
        XCTAssertTrue(repository.waitForExistence(timeout: 30), app.debugDescription)
        repository.tap()
        let extensionSearch = app.searchFields["搜索此仓库的扩展"]
        for _ in 0..<3 where !extensionSearch.exists { app.collectionViews.firstMatch.swipeDown() }
        XCTAssertTrue(extensionSearch.waitForExistence(timeout: 10))
        extensionSearch.tap(); extensionSearch.typeText(packageName + "\n")
        let install = app.buttons["extension.install.\(packageName)"]
        let showAdult = app.buttons["repository.showAdult"]
        if !install.waitForExistence(timeout: 5), showAdult.exists { showAdult.tap() }
        XCTAssertTrue(install.waitForExistence(timeout: 30), app.debugDescription)
        install.tap()
        let reinstall = app.buttons["重新安装"]
        if reinstall.waitForExistence(timeout: 3) { reinstall.tap() }
        let installed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已安装")) || app.descendants(matching: .any)["repository.error"].exists
        }, object: nil)
        wait(for: [installed], timeout: 300)
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.lifetime = .keepAlways; add(screenshot)
        XCTAssertFalse(app.descendants(matching: .any)["repository.error"].exists, app.debugDescription)
        XCTAssertTrue(app.staticTexts["repository.status"].label.contains("已安装"))
        XCTAssertTrue(app.buttons["extension.details.\(kind):\(packageName)"].exists, app.debugDescription)
        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90))
        app.buttons["sources.repositories"].tap()
        // Another test may have left the persisted ecosystem filter on a different runtime.
        let ecosystemFilter = app.buttons["extension.ecosystem.clear"]
        if ecosystemFilter.waitForExistence(timeout: 5) { ecosystemFilter.tap() }
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 30))
        search.tap(); search.typeText(packageName + "\n")
        XCTAssertTrue(app.buttons["extension.details.\(kind):\(packageName)"].waitForExistence(timeout: 30),
                      "Installed extension should survive relaunch: \(app.debugDescription)")
        if let sourceTitle { assertSourceLists(app, ecosystem: kind, title: sourceTitle) }
    }

    /// JAR parser repositories from the repo hub: legacy JSON indexes with a plugin.jar entry, and a GitHub
    /// release (UMA). Each shows a readable name and installs from the repository page.
    @MainActor
    func testJarRepositoryShowsNameAndInstallsPlugin() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        let started = Date()
        guard app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 300) else {
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Extension runtime not ready"
            shot.lifetime = .keepAlways; add(shot)
            throw XCTSkip("Requires the real API JVM profile (online)")
        }
        print("Extension runtime ready after \(Int(Date().timeIntervalSince(started))) s")
        for (index, name) in [
            ("https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/index.min.json", "skepsun/kototoro-parsers"),
            ("https://raw.githubusercontent.com/skepsun/k-parsers-r/repo/index.min.json", "skepsun/k-parsers-r"),
            ("https://github.com/InvalidDavid/UMA", nil),
        ] {
            installJarRepository(app, index: index, name: name)
            app.terminate(); app.launch()
        }
    }

    private func installJarRepository(_ app: XCUIApplication, index: String, name: String?) {
        let encoded = index.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        app.open(URL(string: "kototoro://add-repo?url=\(encoded)&kind=JAR")!)
        let tabs = app.segmentedControls["repository.tabs"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 30), app.debugDescription)
        let imported = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已添加")) || app.descendants(matching: .any)["repository.error"].exists
        }, object: nil)
        wait(for: [imported], timeout: 90)
        XCTAssertFalse(app.descendants(matching: .any)["repository.error"].exists, "\(index): \(app.debugDescription)")
        if let name { XCTAssertTrue(app.staticTexts["repository.status"].label.contains(name), app.staticTexts["repository.status"].label) }
        tabs.buttons["仓库"].tap()
        let repository = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "repository.row.JAR.\(index)")).firstMatch
        XCTAssertTrue(repository.waitForExistence(timeout: 30), "\(index): \(app.debugDescription)")
        repository.tap()
        let install = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "extension.install.")).firstMatch
        // Adult plugins (k-parsers-r) are hidden by default; the repository page offers to show them.
        let showAdult = app.buttons["repository.showAdult"]
        if !install.waitForExistence(timeout: 15), showAdult.exists { showAdult.tap() }
        XCTAssertTrue(install.waitForExistence(timeout: 30), "\(index) should be installable: \(app.debugDescription)")
        install.tap()
        let installStarted = Date()
        let reinstall = app.buttons["重新安装"]
        if reinstall.waitForExistence(timeout: 3) { reinstall.tap() }
        let installed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已安装")) || app.descendants(matching: .any)["repository.error"].exists
        }, object: nil)
        // DEX-only plugins are converted by the interpreter-only embedded JVM (download, conversion and loading).
        wait(for: [installed], timeout: 900)
        print("JAR install \(index) finished after \(Int(Date().timeIntervalSince(installStarted))) s")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "JAR install \(index)"
        shot.lifetime = .keepAlways; add(shot)
        let error = app.staticTexts["repository.error"]
        XCTAssertFalse(error.exists, "\(index) install failed: \(error.exists ? error.label : "")")
    }

    @MainActor
    func testRepositoryLinkConvertsApkOnDeviceAndPersistsInstallation() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        guard app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90) else {
            throw XCTSkip("Requires the real API JVM profile and the local repository fixture")
        }
        app.buttons["sources.repositories"].tap()
        XCTAssertTrue(app.segmentedControls["repository.tabs"].waitForExistence(timeout: 30))
        let refreshing = app.descendants(matching: .any)["repository.refreshing"]
        let refreshed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !refreshing.exists }, object: nil)
        wait(for: [refreshed], timeout: 90)
        app.buttons["repository.actions"].tap()
        app.buttons["repository.add"].tap()
        let address = app.textFields["repository.address"]
        XCTAssertTrue(address.waitForExistence(timeout: 30))
        address.tap()
        address.typeText("kototoro://add-repo?url=http%3A%2F%2F127.0.0.1%3A8766%2Findex.min.json&kind=MIHON")
        app.buttons["repository.import"].tap()
        let added = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !address.exists }, object: nil)
        wait(for: [added], timeout: 30)
        app.segmentedControls["repository.tabs"].buttons["仓库"].tap()
        let repository = app.buttons["repository.row.MIHON.http://127.0.0.1:8766/index.min.json"]
        XCTAssertTrue(repository.waitForExistence(timeout: 30), app.debugDescription)
        repository.tap()
        let install = app.buttons["extension.install.eu.kanade.tachiyomi.extension.zh.zaimanhua"]
        XCTAssertTrue(install.waitForExistence(timeout: 30), app.debugDescription)
        install.tap()
        let reinstall = app.buttons["重新安装"]
        if reinstall.waitForExistence(timeout: 3) { reinstall.tap() }
        let installed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已安装")) || app.descendants(matching: .any)["repository.error"].exists
        }, object: nil)
        wait(for: [installed], timeout: 300)
        XCTAssertFalse(app.descendants(matching: .any)["repository.error"].exists, app.debugDescription)
        XCTAssertTrue(app.staticTexts["repository.status"].label.contains("已安装"))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.lifetime = .keepAlways; add(screenshot)
        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90), app.debugDescription)
        app.buttons["sources.repositories"].tap()
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 30))
        search.tap()
        search.typeText("eu.kanade.tachiyomi.extension.zh.zaimanhua\n")
        XCTAssertTrue(app.buttons["extension.details.MIHON:eu.kanade.tachiyomi.extension.zh.zaimanhua"]
            .waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["extension.details.MIHON:eu.kanade.tachiyomi.extension.zh.zaimanhua"].tap()
        XCTAssertTrue(app.staticTexts["再漫画"].firstMatch.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.buttons["extension.close"].exists)
    }

    @MainActor
    func testCmsFavouritesHistoryAndPlaybackResume() throws {
        let app = XCUIApplication(); app.launch()
        importVideoSite(app, url: "http://127.0.0.1:8765/api.php/provide/vod/", title: "记录闭环测试")
        // The browse list is long with the real extensions installed; find the new site like a user would.
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 10), app.debugDescription)
        search.tap(); search.typeText("记录闭环测试")
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", "记录闭环测试")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription); source.tap()
        let item = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 30)); item.tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        let favourite = app.buttons["details.favourite"]
        XCTAssertTrue(favourite.waitForExistence(timeout: 10))
        if favourite.label == "收藏" {
            favourite.tap()
            let category = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "favourite.category.")).firstMatch
            XCTAssertTrue(category.waitForExistence(timeout: 10))
            category.tap()
            app.buttons["favourite.save"].tap()
            XCTAssertTrue(app.buttons["favourite.save"].waitForNonExistence(timeout: 10))
        }
        let chapter = revealChapter(in: app, timeout: 30)
        XCTAssertTrue(chapter.exists); chapter.tap()
        let position = app.staticTexts["player.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            Int(position.label.components(separatedBy: " ").dropFirst().first ?? "") ?? 0 >= 5
        }, object: nil)
        wait(for: [advanced], timeout: 30)
        playerControl(app, "player.close").tap()
        app.terminate(); app.launch()
        app.tabBars.buttons["作品库"].tap()
        // Other tests favourite the same fixture title on other sites; this run's copy is the one with progress.
        let card = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@ AND label CONTAINS %@", "favourite.", "测试动画", "已读"))
            .firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 30)); card.tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        let resume = app.detailsReadButton()
        XCTAssertTrue(resume.waitForExistence(timeout: 30), app.debugDescription)
        resume.tap()
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let resumed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            position.exists && position.label != "已播放 0 秒"
        }, object: nil)
        wait(for: [resumed], timeout: 15)
        playerControl(app, "player.close").tap()
        app.navigationBars.buttons.firstMatch.tap()
        app.tabBars.buttons["记录"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "history.")).firstMatch.waitForExistence(timeout: 30))
    }

    /// Resuming from the same details page that just played must continue, not restart (no relaunch in between).
    @MainActor
    func testCmsResumeFromSameDetailsAfterPlayback() throws {
        let app = XCUIApplication(); app.launch()
        importVideoSite(app, url: "http://127.0.0.1:8765/api.php/provide/vod/", title: "同页续播测试")
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 10), app.debugDescription)
        search.tap(); search.typeText("同页续播测试")
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", "同页续播测试")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription); source.tap()
        let item = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 30)); item.tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        let chapter = revealChapter(in: app, timeout: 30)
        XCTAssertTrue(chapter.exists); chapter.tap()
        let position = app.staticTexts["player.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            Int(position.label.components(separatedBy: " ").dropFirst().first ?? "") ?? 0 >= 6
        }, object: nil)
        wait(for: [advanced], timeout: 30)
        playerControl(app, "player.close").tap()
        let resume = app.detailsReadButton()
        XCTAssertTrue(resume.waitForExistence(timeout: 30), app.debugDescription)
        resume.tap()
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let resumed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (Int(position.label.components(separatedBy: " ").dropFirst().first ?? "") ?? 0) >= 4
        }, object: nil)
        wait(for: [resumed], timeout: 3)
        playerControl(app, "player.close").tap()
    }
}
