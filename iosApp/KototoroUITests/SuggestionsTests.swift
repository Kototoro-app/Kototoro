import XCTest
import UIKit

final class SuggestionsTests: XCTestCase {
    @MainActor
    func testWebsiteSearchKeepsItsTitleAndSearchContext() async throws {
        let health = URLRequest(url: URL(string: "http://127.0.0.1:8765/health")!, timeoutInterval: 2)
        guard (try? await URLSession.shared.data(for: health)) != nil else {
            throw XCTSkip("Start serve-cms-fixture.py first")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-space.enabled", "YES", "-space.kind", "VIDEO",
            "-sources.layout", "list", "-browse.recommendations.feed", "website", "-browse.recommendations.collapsed", "NO",
            "-browse.tracking.site", "5", "-browse.tracking.category", "calendar", "-browse.tracking.weekday", "2",
            "-tracking.bangumi.apiBase", "http://127.0.0.1:8765/tracking"]
        app.launch()
        app.mainTab("浏览").tap()
        let work = app.buttons["tracking.preview.201"]
        XCTAssertTrue(work.waitForExistence(timeout: 20))
        work.tap()
        let searchWork = app.buttons["tracking.searchWork"]
        XCTAssertTrue(searchWork.waitForExistence(timeout: 10))
        searchWork.tap()
        let field = app.searchFields["搜索全部来源中的作品"]
        let found = field.waitForExistence(timeout: 5)
        attach(app, "网站关联搜索上下文")
        XCTAssertTrue(found, app.debugDescription)
        XCTAssertEqual(field.value as? String, "放送作品 201")
    }

    @MainActor
    func testGlobalSearchFiltersRemainVisibleWhileTyping() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        app.mainTab("搜索").tap()
        let filter = app.buttons["search.filter"]
        XCTAssertTrue(filter.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(filter.isHittable)
        let search = app.pageSearchField()
        search.tap(); search.typeText("测试")
        XCTAssertTrue(app.keyboards.firstMatch.exists)
        XCTAssertTrue(filter.isHittable, "Filters must remain reachable while the system search is active")
        filter.tap()
        XCTAssertTrue(app.switches["仅搜索置顶来源"].exists || app.buttons["仅搜索置顶来源"].exists)
        attach(app, "全局搜索可见筛选")
    }

    @MainActor
    func testWebsiteDetailsSearchesSourcesAndOpensTheBoundWork() async throws {
        let health = URLRequest(url: URL(string: "http://127.0.0.1:8765/health")!, timeoutInterval: 2)
        guard (try? await URLSession.shared.data(for: health)) != nil else {
            throw XCTSkip("Start serve-cms-fixture.py first")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-space.enabled", "YES", "-space.kind", "VIDEO", "-sources.layout", "list",
            "-search.pinnedOnly", "NO", "-browse.recommendations.feed", "website",
            "-browse.recommendations.collapsed", "NO", "-browse.tracking.site", "5",
            "-browse.tracking.category", "calendar", "-browse.tracking.weekday", "2",
            "-tracking.bangumi.apiBase", "http://127.0.0.1:8765/tracking"]
        app.launch()
        importVideoSite(app, url: "http://127.0.0.1:8765/binding/api.php/provide/vod/", title: "关联搜索测试源")
        app.terminate(); app.launch()
        app.mainTab("浏览").tap()
        let work = app.buttons["tracking.preview.201"]
        XCTAssertTrue(work.waitForExistence(timeout: 20))
        work.tap()
        let searchWork = app.buttons["tracking.searchWork"]
        XCTAssertTrue(searchWork.waitForExistence(timeout: 15), app.debugDescription)
        attach(app, "网站作品详情信息卡")
        searchWork.tap()
        let search = app.searchFields["搜索全部来源中的作品"]
        XCTAssertTrue(search.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(search.value as? String, "放送作品 201")
        search.tap()
        search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "放送作品 201".count))
        search.typeText("测试动画\n")
        let source = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@",
            "search.source.", "关联搜索测试源")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription)
        let sourceName = String(source.identifier.dropFirst("search.source.".count))
        let result = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
            "search.result.\(sourceName).", "测试动画")).firstMatch
        for _ in 0..<6 where !result.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(result.isHittable)
        attach(app, "从网站作品搜索来源")
        result.tap()
        let chosen = app.descendants(matching: .any)["tracking.chosenWork"].firstMatch
        XCTAssertTrue(chosen.waitForExistence(timeout: 10))
        let bind = app.buttons["tracking.bind"]
        XCTAssertTrue(bind.isEnabled)
        attach(app, "确认来源作品关联")
        bind.tap()
        let title = app.staticTexts["details.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertEqual(title.label, "放送作品 201", "The chosen source work must use the bound website metadata")
        attach(app, "关联后打开来源作品")
    }

    @MainActor
    func testManualWebsiteRefreshBypassesFreshCache() async throws {
        let health = URLRequest(url: URL(string: "http://127.0.0.1:8765/health")!, timeoutInterval: 2)
        func requestCount() async throws -> Int {
            let (data, _) = try await URLSession.shared.data(for: health)
            return try XCTUnwrap((JSONSerialization.jsonObject(with: data) as? [String: Any])?["calendarRequests"] as? Int)
        }
        guard (try? await requestCount()) != nil else {
            throw XCTSkip("Start the updated serve-cms-fixture.py first")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-space.enabled", "YES", "-space.kind", "VIDEO", "-sources.layout", "list",
            "-browse.recommendations.feed", "website", "-browse.recommendations.collapsed", "NO",
            "-browse.tracking.site", "5", "-browse.tracking.category", "calendar", "-browse.tracking.weekday", "2",
            "-tracking.bangumi.apiBase", "http://127.0.0.1:8765/tracking"]
        app.launch()
        app.mainTab("浏览").tap()
        XCTAssertTrue(app.buttons["tracking.preview.201"].waitForExistence(timeout: 20))
        let before = try await requestCount()
        app.buttons["recommendations.more"].tap()
        let refresh = app.buttons["recommendations.refresh"]
        XCTAssertTrue(refresh.isEnabled)
        refresh.tap()
        let deadline = Date().addingTimeInterval(10)
        var after = before
        while Date() < deadline && after == before {
            try await Task.sleep(for: .milliseconds(100))
            after = try await requestCount()
        }
        XCTAssertGreaterThan(after, before, "Manual refresh must reach the server inside the 15-minute cache window")
        XCTAssertTrue(app.buttons["tracking.preview.201"].exists, "Keep the shelf visible while refreshing")
    }

    @MainActor
    func testSourceSearchAndFiltersPreserveDraftChoicesUntilSave() async throws {
        let health = URLRequest(url: URL(string: "http://127.0.0.1:8765/health")!, timeoutInterval: 2)
        guard (try? await URLSession.shared.data(for: health)) != nil else {
            throw XCTSkip("Start serve-cms-fixture.py first")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-suggestions", "NO", "-sources.layout", "list",
            "-browse.recommendations.feed", "personal", "-browse.recommendations.collapsed", "NO"]
        app.launch()
        importVideoSite(app, url: "http://127.0.0.1:8765/api.php/provide/vod/", title: "推荐筛选测试")
        app.terminate()
        app.launch()
        app.mainTab("浏览").tap()
        app.buttons["recommendations.more"].tap()
        app.buttons["suggestions.shelf.settings"].tap()
        let tags = app.textFields["suggestions.preferredTags"]
        XCTAssertTrue(tags.waitForExistence(timeout: 10))
        tags.tap()
        if let text = tags.value as? String, text != tags.placeholderValue {
            tags.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: text.count))
        }
        tags.typeText("source-draft")
        app.buttons["suggestions.sources"].tap()
        let search = app.pageSearchField()
        XCTAssertTrue(search.exists)
        search.tap(); search.typeText("推荐筛选测试")
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
            "suggestions.source.", "推荐筛选测试")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription)
        let identifier = row.identifier
        row.tap(); app.buttons["优先推荐"].tap()
        func filterChoice(_ title: String) {
            app.buttons["suggestions.sources.filter"].tap()
            app.buttons["推荐状态"].tap()
            app.buttons[title].tap()
        }
        filterChoice("不推荐")
        XCTAssertFalse(app.buttons[identifier].exists, "Filtering must hide the preferred source")
        filterChoice("优先推荐")
        XCTAssertTrue(app.buttons[identifier].waitForExistence(timeout: 5), "Filtering must preserve the draft choice")
        attach(app, "推荐来源搜索与状态筛选")
        if !app.navigationBars.buttons["推荐偏好"].exists {
            let close = app.buttons["关闭"].firstMatch
            if close.exists { close.tap() } else { app.buttons["取消"].tap() }
        }
        app.navigationBars.buttons["推荐偏好"].tap()
        XCTAssertEqual(tags.value as? String, "source-draft", "Returning must not reload the unsaved draft")
        app.buttons["suggestions.save"].tap()
        app.buttons["recommendations.more"].tap()
        app.buttons["suggestions.shelf.settings"].tap()
        app.buttons["suggestions.sources"].tap()
        filterChoice("优先推荐")
        let saved = app.buttons[identifier]
        XCTAssertTrue(saved.waitForExistence(timeout: 10), "Saving must retain a choice hidden by other filters")
        saved.tap(); app.buttons["不推荐"].tap()
        app.navigationBars.buttons["推荐偏好"].tap()
        app.buttons["取消"].tap()
        app.buttons["recommendations.more"].tap()
        app.buttons["suggestions.shelf.settings"].tap()
        app.buttons["suggestions.sources"].tap()
        filterChoice("优先推荐")
        XCTAssertTrue(app.buttons[identifier].waitForExistence(timeout: 5), "Cancel must discard the new draft")
    }

    @MainActor
    func testLeadingWebsiteCaptionIsNotClippedByTheSourceList() async throws {
        let health = URLRequest(url: URL(string: "http://127.0.0.1:8765/health")!, timeoutInterval: 2)
        guard (try? await URLSession.shared.data(for: health)) != nil else {
            throw XCTSkip("Start serve-cms-fixture.py first")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-space.enabled", "YES", "-space.kind", "VIDEO", "-sources.layout", "list",
            "-browse.recommendations.feed", "website", "-browse.recommendations.collapsed", "NO",
            "-browse.tracking.site", "5", "-browse.tracking.category", "calendar", "-browse.tracking.weekday", "2",
            "-tracking.bangumi.apiBase", "http://127.0.0.1:8765/tracking"]
        app.launch()
        app.mainTab("浏览").tap()
        let first = app.buttons["tracking.preview.201"]
        let second = app.buttons["tracking.preview.202"]
        XCTAssertTrue(first.waitForExistence(timeout: 20))
        XCTAssertTrue(second.exists)
        let screenshot = app.screenshot()
        let pixels = screenshot.image.cgImage!
        let scale = CGFloat(pixels.width) / app.frame.width
        func leadingGlyph(_ frame: CGRect) -> Int {
            let rect = CGRect(x: frame.minX, y: frame.maxY - 16, width: 12, height: 16)
                .applying(CGAffineTransform(scaleX: scale, y: scale)).integral
            let glyph = pixels.cropping(to: rect)!
            let width = glyph.width, height = glyph.height
            var bytes = [UInt8](repeating: 0, count: width * height * 4)
            let context = CGContext(data: &bytes, width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.draw(glyph, in: CGRect(x: 0, y: 0, width: width, height: height))
            return stride(from: 0, to: bytes.count, by: 4).filter {
                max(bytes[$0], bytes[$0 + 1], bytes[$0 + 2]) < 100
            }.count
        }
        let firstInk = leadingGlyph(first.frame), secondInk = leadingGlyph(second.frame)
        XCTAssertGreaterThan(secondInk, 100, "The fixture's reference glyph must be visible")
        XCTAssertGreaterThanOrEqual(Double(firstInk), Double(secondInk) * 0.9,
            "The identical leading glyph is clipped: first=\(firstInk), second=\(secondInk)")
        attach(app, "首字裁切回归")
    }

    @MainActor
    func testWebsiteCalendarSelectionAndBothShelvesFollowSpace() async throws {
        let health = URLRequest(url: URL(string: "http://127.0.0.1:8765/health")!, timeoutInterval: 2)
        guard (try? await URLSession.shared.data(for: health)) != nil else {
            throw XCTSkip("Start serve-cms-fixture.py first")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        let common = ["-suggestions", "YES", "-suggestions_preferred_tags", "动画",
                      "-sources.layout", "list", "-space.enabled", "YES",
                      "-browse.tracking.site", "5", "-browse.tracking.category", "calendar",
                      "-browse.tracking.weekday", "2", "-tracking.bangumi.apiBase", "http://127.0.0.1:8765/tracking"]
        app.launchArguments = common + ["-space.kind", "VIDEO"]
        app.launch()
        app.mainTab("浏览").tap()
        func showWebsiteShelf() {
            if !app.buttons["sources.tracking"].exists {
                app.buttons["recommendations.more"].tap()
                app.buttons["推荐内容"].tap()
                app.buttons["网站推荐"].tap()
            }
            app.buttons["recommendations.more"].tap()
            let collapse = app.buttons["recommendations.collapse"]
            if collapse.label == "展开推荐" { collapse.tap() }
            else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.15)).tap() }
        }
        showWebsiteShelf()
        let calendarWork = app.buttons["tracking.preview.201"]
        XCTAssertTrue(calendarWork.waitForExistence(timeout: 25), app.debugDescription)
        let shelf = app.scrollViews["tracking.shelf"]
        for _ in 0..<4 { shelf.swipeLeft() }
        XCTAssertTrue(app.buttons["tracking.preview.210"].exists)
        XCTAssertFalse(app.buttons["tracking.preview.211"].exists, "The shelf is limited to ten works")
        for _ in 0..<4 { shelf.swipeRight() }
        attach(app, "视频空间网站放送与精选推荐")
        let firstSource = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.JSON_TVBOX_")).firstMatch
        let expandedSourceY = firstSource.exists ? firstSource.frame.minY : nil
        app.buttons["recommendations.more"].tap()
        app.buttons["recommendations.collapse"].tap()
        XCTAssertFalse(app.scrollViews["tracking.shelf"].exists)
        if let expandedSourceY {
            XCTAssertLessThan(firstSource.frame.minY, expandedSourceY, "Collapsing must free source space")
        }
        attach(app, "推荐收起后的来源区域")
        app.buttons["recommendations.more"].tap()
        XCTAssertEqual(app.buttons["recommendations.collapse"].label, "展开推荐")
        app.buttons["recommendations.collapse"].tap()
        app.buttons["sources.tracking"].tap()
        let calendar = app.segmentedControls["tracking.ranking"].buttons["每日放送"]
        XCTAssertTrue(calendar.waitForExistence(timeout: 10))
        XCTAssertTrue(calendar.isSelected)
        XCTAssertTrue(app.buttons["tracking.result.201"].waitForExistence(timeout: 15))
        app.terminate()

        app.launchArguments = common + ["-space.kind", "MANGA"]
        app.launch()
        app.mainTab("浏览").tap()
        showWebsiteShelf()
        let manga = app.buttons["tracking.preview.901"]
        XCTAssertTrue(manga.waitForExistence(timeout: 25), app.debugDescription)
        XCTAssertTrue(manga.label.contains("推荐漫画"))
        XCTAssertFalse(app.buttons["tracking.preview.201"].exists, "Video calendar must disappear in manga space")
        app.buttons["recommendations.more"].tap()
        app.buttons["推荐内容"].tap()
        app.buttons["为你推荐"].tap()
        XCTAssertTrue(app.buttons["sources.suggestions"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "suggestions.preview.", "测试动画")).firstMatch.exists)
        app.buttons["recommendations.more"].tap()
        app.buttons["推荐内容"].tap()
        app.buttons["网站推荐"].tap()
        app.buttons["recommendations.more"].tap()
        XCTAssertFalse(app.buttons["内容类型"].exists, "Media type is controlled by Space")
        app.buttons["分区"].tap()
        XCTAssertFalse(app.buttons["每日放送"].exists, "Calendar is unavailable outside video")
        attach(app, "漫画空间与网站分区选择")
        app.terminate()
        app.launchArguments = common.map { $0 == "calendar" ? "popular" : $0 } + ["-space.kind", ""]
        app.launch()
        app.mainTab("浏览").tap()
        showWebsiteShelf()
        for id in [901, 1901, 2901] {
            XCTAssertTrue(app.buttons["tracking.preview.\(id)"].waitForExistence(timeout: 20), app.debugDescription)
        }
        attach(app, "全部空间混合网站推荐")
    }

    @MainActor
    func testBrowseEntryPreservesMainTabsAndPreferencesCanBeSaved() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-suggestions", "NO", "-sources.layout", "list",
                               "-browse.recommendations.feed", "personal", "-browse.recommendations.collapsed", "NO"]
        app.launch()
        app.mainTab("浏览").tap()
        let entry = app.buttons["sources.suggestions"]
        XCTAssertTrue(entry.waitForExistence(timeout: 20), app.debugDescription)
        for title in ["作品库", "浏览", "记录", "设置"] { XCTAssertTrue(app.tabBars.buttons[title].exists) }
        XCTAssertFalse(app.tabBars.buttons["为你推荐"].exists)
        attach(app, "浏览页推荐入口")
        entry.tap()
        XCTAssertTrue(app.buttons["suggestions.enable"].waitForExistence(timeout: 10))
        app.buttons["suggestions.settings"].tap()
        let preferred = app.textFields["suggestions.preferredTags"]
        XCTAssertTrue(preferred.waitForExistence(timeout: 10))
        preferred.tap()
        if let text = preferred.value as? String, text != preferred.placeholderValue {
            preferred.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: text.count))
        }
        preferred.typeText("奇幻, 冒险")
        app.buttons["suggestions.save"].tap()
        app.buttons["suggestions.settings"].tap()
        XCTAssertEqual(app.textFields["suggestions.preferredTags"].value as? String, "奇幻, 冒险")
        attach(app, "推荐偏好")
        app.buttons["取消"].tap()
    }

    @MainActor
    func testRecommendationsFromNativeSourceOpenDetails() async throws {
        // The fixture also exercises the unfiltered fallback for sources with no matching preference tag.
        let health = URLRequest(url: URL(string: "http://127.0.0.1:8765/health")!, timeoutInterval: 2)
        guard (try? await URLSession.shared.data(for: health)) != nil else {
            throw XCTSkip("Start serve-cms-fixture.py first")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-suggestions", "YES", "-suggestions_preferred_tags", "动画",
                               "-suggestions_exclude_tags", "", "-suggestions.updatedAt", "0",
                               "-space.enabled", "YES", "-space.kind", "VIDEO", "-sources.layout", "list",
                               "-browse.recommendations.feed", "personal", "-browse.recommendations.collapsed", "NO"]
        app.launch()
        importVideoSite(app, url: "http://127.0.0.1:8765/api.php/provide/vod/", title: "推荐测试来源")
        app.terminate()
        app.launch()
        app.mainTab("浏览").tap()
        let entry = app.buttons["sources.suggestions"]
        XCTAssertTrue(entry.waitForExistence(timeout: 20))
        entry.tap()
        let work = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                    "suggestion.", "测试动画")).firstMatch
        XCTAssertTrue(work.waitForExistence(timeout: 90), app.debugDescription)
        attach(app, "为你推荐作品列表")
        work.tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 20))
        XCTAssertEqual(app.staticTexts["details.title"].label, "测试动画")
    }

    @MainActor private func attach(_ app: XCUIApplication, _ name: String) {
        let screenshot = app.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        // Keep local copies for visual QA alongside the XCTest attachments.
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("suggestions-\(name).png")
        try? screenshot.pngRepresentation.write(to: file)
    }
}
