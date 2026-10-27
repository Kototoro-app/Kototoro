import XCTest
import UIKit

/// Local works through the document picker: `Scripts/stage-picker-fixtures.py` places a two-page CBZ and a GB18030
/// TXT novel and H.264/AAC video in "On My iPhone"; imports open in the readers or native player.
final class LocalImportTests: XCTestCase {
    private var app: XCUIApplication!

    @MainActor
    func testBottomAccessorySwitchesSpacesAndResumes() throws {
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 20))
        app.detailsReadButton().tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        app.terminate()
        app.launch()
        app.mainTab("作品库").tap()
        let space = app.buttons["continueReading.space"]
        XCTAssertTrue(space.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(space.label.contains("漫画"))
        let reading = app.buttons["continueReading"]
        XCTAssertTrue(reading.exists)
        attach("底部空间与续读栏")
        space.tap()
        XCTAssertTrue(app.buttons["space.select.NOVEL"].waitForExistence(timeout: 5))
        app.buttons["space.select.NOVEL"].tap()
        XCTAssertTrue(app.buttons["space.close"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(space.label.contains("小说"))
        XCTAssertTrue(app.buttons["space.menu"].label.contains("小说"))
        // The shared sheet must finish dismissing before it opens a reader and hides the accessory.
        space.tap()
        let resume = app.buttons["space.resume.MANGA"]
        XCTAssertTrue(resume.waitForExistence(timeout: 5))
        resume.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(app.buttons["space.close"].exists)
        app.buttons["reader.back"].tap()
        app.buttons["continueReading.close"].tap()
        XCTAssertTrue(space.waitForExistence(timeout: 10))
        XCTAssertTrue(space.label.contains("漫画"))
        // The separate resume target retains its original one-tap action.
        app.buttons["continueReading"].tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["reader.back"].tap()
        app.buttons["continueReading.close"].tap()
    }

    @MainActor
    func testBottomSpaceAccessoryRemainsUsableWhenTabBarMinimizes() throws {
        app.mainTab("记录").tap()
        let space = app.buttons["continueReading.space"]
        XCTAssertTrue(space.waitForExistence(timeout: 10), app.debugDescription)
        // Use the full timeline so a previously selected space cannot leave too little content to scroll.
        space.tap()
        app.buttons["space.select.ALL"].tap()
        XCTAssertTrue(app.buttons["space.close"].waitForNonExistence(timeout: 5))
        let collapsed = app.tabBars.buttons.matching(NSPredicate(format: "value == %@", "已折叠")).firstMatch
        for _ in 0..<3 where !collapsed.exists {
            app.scrollViews.firstMatch.swipeUp()
        }
        XCTAssertTrue(collapsed.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(space.isHittable)
        let resume = app.buttons["continueReading"]
        XCTAssertTrue(resume.exists)
        XCTAssertLessThanOrEqual(space.frame.width, 44)
        XCTAssertGreaterThan(resume.frame.width, space.frame.width * 2)
        XCTAssertLessThan(space.frame.maxX, resume.frame.midX)
        attach("收缩后的空间与续读入口")
        space.tap()
        XCTAssertTrue(app.buttons["space.select.VIDEO"].waitForExistence(timeout: 5))
        app.buttons["space.select.VIDEO"].tap()
        XCTAssertTrue(app.buttons["space.close"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(space.label.contains("视频"))
    }

    @MainActor
    func testSpaceSelectorSwitchesAndDismisses() throws {
        app.mainTab("作品库").tap()
        app.buttons["space.menu"].tap()
        let novel = app.buttons["space.select.NOVEL"]
        XCTAssertTrue(novel.waitForExistence(timeout: 5), app.debugDescription)
        novel.tap()
        XCTAssertTrue(app.buttons["space.close"].waitForNonExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(app.buttons["space.menu"].label.contains("小说"))
        app.mainTab("浏览").tap()
        app.buttons["space.menu"].tap()
        XCTAssertTrue(app.buttons["space.select.NOVEL"].isSelected)
        app.buttons["space.select.ALL"].tap()
        XCTAssertTrue(app.buttons["space.close"].waitForNonExistence(timeout: 5))
    }

    /// Browsing details changes the space artwork without requiring a reading record, and survives relaunch.
    @MainActor
    func testSpaceArtworkFollowsBrowsedDetailsAndSurvivesRestart() throws {
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 20), app.debugDescription)
        // Ensure the cover is decoded before leaving; the panel reuses the same cached source image.
        let cover = app.descendants(matching: .any)["details.cover"]
        XCTAssertTrue(cover.waitForExistence(timeout: 5), app.debugDescription)
        let loaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "已加载"), object: cover)
        XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 15), .completed)
        app.terminate()
        app.launch()
        app.mainTab("作品库").tap()
        let menu = app.buttons["space.menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(menu.label.contains("漫画"))
        menu.tap()
        let manga = app.buttons["space.select.MANGA"]
        XCTAssertTrue(manga.waitForExistence(timeout: 5))
        XCTAssertTrue(manga.isSelected)
        let artwork = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.fixturePagePixelFraction(manga, dimmed: true) > 0.2
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [artwork], timeout: 15), .completed,
                       "The browsed comic's red cover should fill the space row after relaunch")
        attach("空间最近浏览封面背景")
        app.buttons["space.select.NOVEL"].tap()
        XCTAssertTrue(app.buttons["space.close"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(menu.label.contains("小说"))
        app.mainTab("浏览").tap()
        app.buttons["space.menu"].tap()
        XCTAssertTrue(app.buttons["space.select.NOVEL"].isSelected)
        app.buttons["space.select.ALL"].tap()
    }

    @MainActor
    func testReadingStatisticsPersistAndAppearInHistoryAndTheCenteredTimeline() throws {
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        let read = app.detailsReadButton()
        XCTAssertTrue(read.waitForExistence(timeout: 20), app.debugDescription)
        read.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        Thread.sleep(forTimeInterval: 6)
        if !app.buttons["reader.back"].isHittable {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)).tap()
        }
        app.buttons["reader.back"].tap()
        openWorkStatistics()
        attach("单作品阅读统计")
        app.navigationBars.buttons["完成"].tap()

        app.terminate()
        app.launch()
        app.mainTab("记录").tap()
        app.selectRecordsFilter("reading")
        let duration = app.staticTexts["stats.duration"]
        XCTAssertTrue(duration.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertNotEqual(duration.label, "0 分钟")
        attach("历史页紧凑阅读概览")
        app.buttons["stats.expand"].tap()
        XCTAssertTrue(app.staticTexts["阅读时段"].waitForExistence(timeout: 5))
        app.buttons["stats.expand"].tap()
        app.selectRecordsFilter("all")
        XCTAssertTrue(app.staticTexts["历史、书签与下载"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["发现更新"].exists)
        let event = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
            "timeline.read-", "fixture_comic")).firstMatch
        XCTAssertTrue(event.waitForExistence(timeout: 10), app.debugDescription)
        let coverLoaded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.fixturePagePixelFraction(event) > 0.025
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [coverLoaded], timeout: 10), .completed,
                       "The timeline must display the fixture's red cover, not just the event text")
        attach("日期居中双列时间轴")
        event.tap()
        XCTAssertTrue(app.staticTexts["阅读时长"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(app.buttons["打开作品"].exists)
    }

    /// The comic fixture has a red cover/page 1 and a blue page 2; inspect pixels rather than placeholder presence.
    @MainActor
    private func fixturePagePixelFraction(
        _ event: XCUIElement, blue: Bool = false, dimmed: Bool = false
    ) -> Double {
        guard let image = event.screenshot().image.cgImage else { return 0 }
        let width = 120
        let height = max(1, image.height * width / image.width)
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: width, height: height,
                                          bitsPerComponent: 8, bytesPerRow: width * 4,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                                            | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return 0 }
        var coverPixels = 0
        for index in stride(from: 0, to: pixels.count, by: 4) {
            let red = Int(pixels[index]), green = Int(pixels[index + 1])
            let dominant = blue ? Int(pixels[index + 2]) : red
            let other = blue ? red : Int(pixels[index + 2])
            let matches = dominant > (dimmed ? 40 : 120) && green < 110 && other < 110 &&
                dominant - green > (dimmed ? 20 : 60)
            if matches { coverPixels += 1 }
        }
        return Double(coverPixels) / Double(width * height)
    }

    @MainActor
    func testNovelReadingCollectsStatistics() throws {
        importFile(source: "LOCAL_NOVEL", file: "fixture_novel", reuse: true)
        let read = app.detailsReadButton()
        XCTAssertTrue(read.waitForExistence(timeout: 20), app.debugDescription)
        read.tap()
        XCTAssertTrue(app.staticTexts["novel.firstParagraph"].waitForExistence(timeout: 30))
        Thread.sleep(forTimeInterval: 6)
        app.buttons["novel.back"].tap()
        openWorkStatistics()
        attach("小说阅读统计")
    }

    @MainActor
    func testVideoPlaybackCollectsStatistics() throws {
        importFile(source: "LOCAL_VIDEO", file: "fixture_video", reuse: true)
        let episodes = app.buttons.matching(NSPredicate(format: "label MATCHES %@", "全部剧集，[0-9]+/[0-9]+"))
            .firstMatch
        XCTAssertTrue(episodes.waitForExistence(timeout: 20),
                      "Video details keep the episode progress row above the summary")
        XCTAssertTrue(app.staticTexts["佚名"].exists, "Video details keep the unknown-author placeholder")
        XCTAssertTrue(app.staticTexts["状态未知"].exists, "Video details keep the unknown-state placeholder")
        attach("视频资料卡")
        let read = app.detailsReadButton()
        XCTAssertTrue(read.waitForExistence(timeout: 20), app.debugDescription)
        read.tap()
        let playing = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.playedSeconds >= 6 }, object: nil)
        wait(for: [playing], timeout: 30)
        showVideoControls()
        attach("播放器液态玻璃控件")
        app.sliders["player.scrubber"].adjust(toNormalizedSliderPosition: 0.05)
        let play = app.buttons["player.playPause"]
        if play.label == "播放" { play.tap() }
        // A resumed position can already be beyond six seconds; only elapsed playback is reading time.
        Thread.sleep(forTimeInterval: 7)
        showVideoControls()
        app.buttons["player.close"].tap()
        openWorkStatistics()
        attach("视频播放统计")
    }

    @MainActor
    func testVideoDownloadPlaysBackOffline() throws {
        importFile(source: "LOCAL_VIDEO", file: "fixture_video", reuse: true)
        let read = app.detailsReadButton()
        XCTAssertTrue(read.waitForExistence(timeout: 20), app.debugDescription)
        read.tap()
        let download = app.buttons["player.download"]
        XCTAssertTrue(download.waitForExistence(timeout: 30), app.debugDescription)
        // Pausing keeps the controls on screen; they fade about 3.5 s into playback, faster than this sequence.
        showVideoControls()
        let pause = app.buttons["player.playPause"]
        XCTAssertTrue(pause.waitForExistence(timeout: 5), app.debugDescription)
        pause.tap()
        showVideoControls()
        // An earlier run on this simulator may already hold the episode, in which case there is nothing to enqueue.
        if download.exists, download.label != "本集已下载" {
            download.tap()
            let copied = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "本集已下载"),
                                                   object: download)
            wait(for: [copied], timeout: 60)
        }
        attach("播放器下载本集")
        closePlayer()
        // A finished episode shows up in the queue with the video wording: an episode counts percent, never pages.
        app.openDetailsChapters()
        XCTAssertTrue(app.navigationBars["全部剧集"].waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["details.chapters.options"].tap()
        let queue = app.buttons["details.chapters.downloads"]
        XCTAssertTrue(queue.waitForExistence(timeout: 10), app.debugDescription)
        queue.tap()
        // The queue keeps tasks from earlier runs, so find this episode's row by its work title.
        let entry = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH 'download.entry.' AND label BEGINSWITH 'fixture_video'")).firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 10), app.debugDescription)
        let status = entry.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'download.status.'")).firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 10), app.debugDescription)
        let finished = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "已下载"),
                                                 object: status)
        wait(for: [finished], timeout: 60)
        attach("下载队列中的剧集")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        // Opening the episode again plays the copy on disk instead of resolving the stream again.
        let again = app.detailsReadButton()
        XCTAssertTrue(again.waitForExistence(timeout: 20), app.debugDescription)
        again.tap()
        let offline = app.descendants(matching: .any)["player.offline"].firstMatch
        XCTAssertTrue(offline.waitForExistence(timeout: 30), app.debugDescription)
        let playing = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.playedSeconds >= 3 }, object: nil)
        wait(for: [playing], timeout: 30)
        attach("离线播放已下载剧集")
    }

    @MainActor
    private func openWorkStatistics() {
        let button = app.buttons["details.readingStats"]
        XCTAssertTrue(button.waitForExistence(timeout: 15), app.debugDescription)
        for _ in 0..<5 where !button.isHittable { app.collectionViews.firstMatch.swipeUp() }
        XCTAssertTrue(button.isHittable, app.debugDescription)
        button.tap()
        let duration = app.staticTexts["stats.duration"]
        XCTAssertTrue(duration.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertNotEqual(duration.label, "0 分钟")
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        if let proxy = ProcessInfo.processInfo.environment["KOTOTORO_TRACKING_TEST_PROXY"] {
            app.launchEnvironment["KOTOTORO_TRACKING_TEST_PROXY"] = proxy
        }
        if let base = ProcessInfo.processInfo.environment["KOTOTORO_TRACKING_BANGUMI_API_BASE"] {
            app.launchArguments += ["-tracking.bangumi.apiBase", base]
        }
        app.launch()
    }

    @MainActor
    func testDetailsShowsChapterPreviewAndPushesTheFullDirectory() throws {
        importFile(source: "LOCAL", file: "fixture_details_layout", reuse: true)
        let headerRead = app.buttons["details.header.read"]
        XCTAssertTrue(headerRead.waitForExistence(timeout: 20))
        XCTAssertTrue(headerRead.isHittable)
        XCTAssertFalse(app.buttons["details.read"].isHittable)
        XCTAssertFalse(app.buttons["details.readOptions"].exists)
        // Android keeps the author and state items when the source has neither, dimmed as unknown.
        XCTAssertTrue(app.staticTexts["佚名"].exists)
        XCTAssertTrue(app.staticTexts["状态未知"].exists)
        let chapter = revealChapter(in: app, timeout: 30)
        XCTAssertTrue(chapter.isHittable, app.debugDescription)
        XCTAssertLessThanOrEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).count, 5)
        XCTAssertFalse(app.buttons["details.panel.handle"].exists)
        XCTAssertFalse(app.buttons["details.dock.thumbnails"].exists)
        attach("详情页内章节预览")
        app.buttons["details.summary.toggle"].tap()
        let scroll = app.collectionViews.firstMatch
        for _ in 0..<6 where headerRead.isHittable { scroll.swipeUp(velocity: .slow) }
        let toolbarRead = app.buttons["details.read"]
        XCTAssertTrue(toolbarRead.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(toolbarRead.isHittable)
        XCTAssertFalse(headerRead.isHittable)
        attach("头部移出后的系统阅读工具栏")
        app.openDetailsChapters()
        XCTAssertTrue(app.navigationBars["全部章节"].waitForExistence(timeout: 10))
        let list = app.descendants(matching: .any)["details.chapters.list"].firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 5))
        list.swipeUp()
        XCTAssertTrue(app.navigationBars["全部章节"].exists)
        XCTAssertFalse(app.scrollViews["reader.canvas"].exists)
        attach("完整章节页与原生导航")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        for _ in 0..<8 where !headerRead.isHittable { scroll.swipeDown(velocity: .slow) }
        XCTAssertTrue(headerRead.isHittable)
        let toolbarHidden = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !toolbarRead.isHittable
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [toolbarHidden], timeout: 5), .completed)
        attach("返回头部后隐藏重复阅读入口")
    }

    @MainActor
    func testDetailsChapterInfoRowShowsUnreadProgressAndOpensTheDirectory() throws {
        // Android's header reads `current chapter + 1 / total` and starts at 0/N; the same row opens the directory,
        // video included.
        importFile(source: "LOCAL", file: "fixture_details_title", expectImport: false)
        let work = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
            "source.content.", "超长标题折叠测试")).firstMatch
        XCTAssertTrue(work.waitForExistence(timeout: 30), app.debugDescription)
        work.tap()

        let row = app.buttons["全部章节，0/1"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), app.debugDescription)
        attach("资料卡未读进度")
        row.tap()
        XCTAssertTrue(app.navigationBars["全部章节"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.descendants(matching: .any)["details.chapters.list"].firstMatch.waitForExistence(timeout: 5))
    }

    @MainActor
    func testLongDetailsTitleExpandsAndCollapsesWithinTheAndroidLineBudget() throws {
        // The fixture title needs far more than six lines at a phone width; Android folds it at three and opens it
        // to six, never to the whole paragraph, so the read button stays on screen.
        importFile(source: "LOCAL", file: "fixture_details_title", expectImport: false)
        let work = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
            "source.content.", "超长标题折叠测试")).firstMatch
        XCTAssertTrue(work.waitForExistence(timeout: 30), app.debugDescription)
        work.tap()

        let title = app.staticTexts["details.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 30), app.debugDescription)
        let folded = title.frame.height
        XCTAssertGreaterThan(folded, 30, app.debugDescription)
        // The row stretches over the header text column, so a tap past the end of the last line still toggles.
        XCTAssertGreaterThan(title.frame.width, app.windows.firstMatch.frame.width * 0.5,
                             "The whole title row, not only its glyphs, must be tappable")
        attach("标题折叠三行")

        title.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        let opened = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in title.frame.height > folded + 8 }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [opened], timeout: 5), .completed, app.debugDescription)
        let openHeight = title.frame.height
        XCTAssertGreaterThan(openHeight, folded * 1.5, "Tapping the title must show more of it")
        XCTAssertLessThan(openHeight, folded * 2.6, "An open title stops at six lines, like Android")
        attach("标题展开六行")

        title.tap()
        let closed = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in title.frame.height < openHeight - 8 }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 5), .completed, app.debugDescription)
        XCTAssertEqual(title.frame.height, folded, accuracy: 1.5, "The second tap folds the title again")
        attach("标题再次折叠")
    }

    @MainActor
    func testFoldedTitlesKeepEveryLineTheyNeed() throws {
        // Android folds at three lines and only enables the tap when the folded title overflows, so a title that
        // fits must show all of its lines; a title without break opportunities must wrap instead of truncating.
        let shortHeight = foldedTitleHeight(file: "fixture_title_short", marker: "短标题", shot: "单行标题")
        let tokenHeight = foldedTitleHeight(file: "fixture_title_token", marker: "LonelyToken", shot: "无空格长单词标题")
        let twoHeight = foldedTitleHeight(file: "fixture_title_two_lines", marker: "两行标题回归", shot: "两行标题")

        let three = openTitle(file: "fixture_title_three_lines", marker: "三行标题回归")
        _ = three.frame
        attach("三行标题折叠状态")
        let threeHeight = three.frame.height
        let heights = "short=\(shortHeight) token=\(tokenHeight) two=\(twoHeight) three=\(threeHeight)"
        XCTAssertGreaterThan(twoHeight, shortHeight * 1.5, "Two-line title keeps both lines while folded (\(heights))")
        XCTAssertGreaterThan(tokenHeight, shortHeight * 1.5, "Long token wraps instead of one line (\(heights))")
        XCTAssertGreaterThan(threeHeight, twoHeight * 1.25, "Three-line title keeps all lines (\(heights))")
        XCTAssertLessThan(threeHeight, shortHeight * 3.6, "The fold still stops at three lines (\(heights))")
        // Nothing overflows at three lines, so the toggle leaves the height alone.
        three.tap()
        XCTAssertEqual(three.frame.height, threeHeight, accuracy: 1.5)
        attach("三行标题点按后不变")
    }

    @MainActor
    private func foldedTitleHeight(file: String, marker: String, shot: String) -> CGFloat {
        let title = openTitle(file: file, marker: marker)
        _ = title.frame
        attach(shot)
        let height = title.frame.height
        // Details pushes over the tab bar, so return to the list before the next import.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        return height
    }

    @MainActor
    private func openTitle(file: String, marker: String) -> XCUIElement {
        importFile(source: "LOCAL", file: file, expectImport: false)
        let work = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "source.content.", marker)).firstMatch
        XCTAssertTrue(work.waitForExistence(timeout: 30), app.debugDescription)
        work.tap()
        let title = app.staticTexts["details.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 30), app.debugDescription)
        return title
    }

    @MainActor
    func testChapterPagePreviewOpensAnExactPageAndReturnsToTheDirectory() throws {
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        app.openDetailsChapters()
        XCTAssertTrue(app.navigationBars["全部章节"].waitForExistence(timeout: 10))
        let preview = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "details.chapter.preview.")).firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 10), app.debugDescription)
        preview.tap()
        XCTAssertTrue(app.navigationBars["页面预览"].waitForExistence(timeout: 10))
        let page = app.buttons["details.pages.page.1"]
        XCTAssertTrue(page.waitForExistence(timeout: 15), app.debugDescription)
        attach("章节内页面预览")
        page.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 20))
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "2")
        app.buttons["reader.back"].tap()
        XCTAssertTrue(app.navigationBars["页面预览"].waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["全部章节"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testFullChapterDirectorySearchAndScrollingDoNotOpenReader() throws {
        importFile(source: "LOCAL", file: "fixture_reader_directory", reuse: true)
        app.openDetailsChapters()
        XCTAssertTrue(app.navigationBars["全部章节"].waitForExistence(timeout: 10))
        let list = app.descendants(matching: .any)["details.chapters.list"].firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 5))
        let first = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).firstMatch
        let firstID = first.identifier
        list.swipeUp()
        XCTAssertFalse(app.buttons[firstID].isHittable)
        list.swipeDown()
        list.swipeDown()
        XCTAssertTrue(app.navigationBars["全部章节"].exists)
        XCTAssertFalse(app.scrollViews["reader.canvas"].exists)
        let field = app.pageSearchField()
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("not-a-chapter")
        XCTAssertTrue(app.staticTexts["没有匹配的章节"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).count, 0)
        attach("系统章节搜索空结果")
    }

    @MainActor
    func testDetailsBookmarkRestoresPageInsteadOfTheLatestReadingPosition() throws {
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        app.detailsReadButton().tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 20))
        selectReaderMode("默认（从左到右）")
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        waitForPage("1")
        let bookmark = app.buttons["reader.bookmark"]
        if bookmark.value as? String == "已添加" { bookmark.tap() }
        bookmark.tap()
        let saved = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "已添加"), object: bookmark)
        XCTAssertEqual(XCTWaiter.wait(for: [saved], timeout: 10), .completed)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        waitForPage("2")
        app.buttons["reader.back"].tap()
        XCTAssertTrue(app.buttons["details.readOptions"].waitForExistence(timeout: 10))
        attach("详情页继续阅读按钮")
        let all = app.buttons["details.bookmarks.all"]
        for _ in 0..<5 where !all.isHittable { app.collectionViews.firstMatch.swipeUp() }
        XCTAssertTrue(all.waitForExistence(timeout: 10), app.debugDescription)
        all.tap()
        XCTAssertTrue(app.navigationBars["书签"].waitForExistence(timeout: 10))
        let savedPage = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                         "details.bookmark.", "第 1 页")).firstMatch
        XCTAssertTrue(savedPage.waitForExistence(timeout: 10))
        savedPage.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 20))
        waitForPage("1")
        app.buttons["reader.back"].tap()
        XCTAssertTrue(app.navigationBars["书签"].waitForExistence(timeout: 10))
        attach("书签精确恢复页面")
    }

    @MainActor
    func testReaderEdgeSwipeGoesBackOnlyFromTheEdgeBand() throws {
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        let window = app.windows.firstMatch
        func swipe(from x: CGFloat) {
            window.coordinate(withNormalizedOffset: CGVector(dx: x, dy: 0.5)).press(forDuration: 0.1,
                thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)),
                withVelocity: .slow, thenHoldForDuration: 0.1)
        }
        // Right to left reads forward with the same rightward drag as the swipe back: only the edge band goes back.
        app.detailsReadButton().tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 20))
        selectReaderMode("从右到左")
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        waitForPage("1")
        swipe(from: 0.15)
        waitForPage("2")
        XCTAssertTrue(app.images["reader.image"].firstMatch.exists, "a drag away from the edge left the reader")
        swipe(from: 0.005)
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 10), app.debugDescription)

        app.detailsReadButton().tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 20))
        selectReaderMode("条漫")
        swipe(from: 0.005)
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 10), app.debugDescription)
    }

    @MainActor
    func testContinuousStripScrollsAfterTheSameContactMovesSideways() throws {
        importFile(source: "LOCAL", file: "fixture_reader_long", reuse: true)
        openFirstChapter(title: "fixture_reader_long")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        hideReaderControls(canvas)
        let frame = app.windows.firstMatch.frame
        let page = app.images["reader.image"].firstMatch
        let before = page.frame.minY
        // The same contact first shifts horizontally, then moves vertically without lifting.
        var moves: [(CGPoint, Double)] = [(CGPoint(x: frame.width * 0.65, y: frame.height * 0.7), 0.1)]
        for step in 1...12 {
            moves.append((CGPoint(x: frame.width * 0.65, y: frame.height * (0.7 - 0.03 * Double(step))),
                          0.1 + Double(step) * 0.05))
        }
        let end = CGPoint(x: frame.width * 0.65, y: frame.height * 0.34)
        moves.append((CGPoint(x: end.x, y: end.y + 1), 0.9))
        moves.append((end, 1.0))
        synthesizeTouches([SyntheticTouch(down: CGPoint(x: frame.width * 0.35, y: frame.height * 0.7),
                                          at: 0, moves: moves, liftAt: 1.2)])
        let scrolled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            before - page.frame.minY > frame.height * 0.25
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [scrolled], timeout: 8), .completed,
                       "A horizontal contact shift prevented subsequent vertical scrolling")
    }

    @MainActor
    func testMangaBookmarkShowsItsPagePreviewAfterRestart() throws {
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        app.detailsReadButton().tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 20))
        selectReaderMode("默认（从左到右）")
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        waitForPage("2")
        let bookmark = app.buttons["reader.bookmark"]
        if bookmark.value as? String == "已添加" { bookmark.tap() }
        bookmark.tap()
        let added = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "已添加"), object: bookmark)
        XCTAssertEqual(XCTWaiter.wait(for: [added], timeout: 15), .completed)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        waitForPage("1")
        app.buttons["reader.back"].tap()
        app.terminate(); app.launch()
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        let preview = app.scrollViews["details.bookmarks.preview"]
        for _ in 0..<5 where !preview.isHittable { app.collectionViews.firstMatch.swipeUp() }
        XCTAssertTrue(preview.isHittable, app.debugDescription)
        app.collectionViews.firstMatch.swipeUp(velocity: .slow)
        preview.swipeLeft()
        let saved = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                     "details.bookmark.", "第 2 页")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 10))
        let inlineImage = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.fixturePagePixelFraction(saved, blue: true) > 0.025
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [inlineImage], timeout: 15), .completed,
                       "The bookmark must show blue page 2, not the red work cover or current page")
        attach("漫画书签页面预览卡片")
        app.buttons["details.bookmarks.all"].tap()
        XCTAssertTrue(app.navigationBars["书签"].waitForExistence(timeout: 10))
        let listedImage = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.fixturePagePixelFraction(saved, blue: true) > 0.025
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [listedImage], timeout: 15), .completed)
        attach("漫画全部书签页面缩略图")
        saved.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 20))
        waitForPage("2")
    }

    @MainActor
    func testDetailsNovelBookmarkRestoresTheSavedParagraph() throws {
        openNovelControls(mode: "SCROLL")
        app.sliders["novel.progress"].adjust(toNormalizedSliderPosition: 0.3)
        let savedPercent = Int((app.sliders["novel.progress"].value as? String ?? "")
            .replacingOccurrences(of: "%", with: "")) ?? -1
        XCTAssertGreaterThan(savedPercent, 0)
        app.buttons["novel.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        let bookmark = app.buttons["novel.bookmark"]
        if bookmark.label == "取消书签" { bookmark.tap() }
        bookmark.tap()
        XCTAssertTrue(app.buttons["novel.bookmark"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["novel.bookmark"].label, "取消书签")
        app.buttons["novel.panel.close"].tap()
        app.sliders["novel.progress"].adjust(toNormalizedSliderPosition: 0.8)
        app.buttons["novel.back"].tap()
        let all = app.buttons["details.bookmarks.all"]
        for _ in 0..<6 where !all.isHittable { app.collectionViews.firstMatch.swipeUp() }
        XCTAssertTrue(all.waitForExistence(timeout: 10), app.debugDescription)
        all.tap()
        XCTAssertTrue(app.navigationBars["书签"].waitForExistence(timeout: 10), app.debugDescription)
        let saved = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "details.bookmark.")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 10))
        let excerpt = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "bookmark.excerpt.")).firstMatch
        XCTAssertTrue(excerpt.waitForExistence(timeout: 10))
        XCTAssertTrue(excerpt.label.contains("晨光落在书页上"), excerpt.label)
        attach("小说书签内容预览")
        saved.tap()
        let restored = app.sliders["novel.progress"]
        XCTAssertTrue(restored.waitForExistence(timeout: 20))
        let atBookmark = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let percent = Int((restored.value as? String ?? "").replacingOccurrences(of: "%", with: "")) ?? -1
            return abs(percent - savedPercent) <= 3
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [atBookmark], timeout: 10), .completed,
                       "Restored progress: \(restored.value ?? "nil")")
        attach("详情小说书签恢复段落")
    }

    /// A saved bookmark is listed under the records page's bookmark filter and opens the reader at it.
    @MainActor
    func testRecordsListBookmarksAndOpenThem() throws {
        openNovelControls(mode: "SCROLL")
        app.buttons["novel.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        let bookmark = app.buttons["novel.bookmark"]
        XCTAssertTrue(bookmark.waitForExistence(timeout: 10), app.debugDescription)
        if bookmark.label == "取消书签" { bookmark.tap() }
        bookmark.tap()
        XCTAssertEqual(app.buttons["novel.bookmark"].label, "取消书签")
        app.buttons["novel.panel.close"].tap()
        app.buttons["novel.back"].tap()
        // Back at the tab root: the details page hides the tab bar.
        for _ in 0..<4 where !app.tabBars.firstMatch.exists {
            let back = app.navigationBars.buttons["BackButton"]
            guard back.waitForExistence(timeout: 3) else { break }
            back.tap()
        }

        app.mainTab("记录").tap()
        let filter = app.buttons["timeline.filter.bookmarks"]
        XCTAssertTrue(filter.waitForExistence(timeout: 10), app.debugDescription)
        filter.tap()
        XCTAssertTrue(app.staticTexts["书签概览"].waitForExistence(timeout: 10), app.debugDescription)
        let item = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                    "timeline.bookmark.", "fixture_novel_controls")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(item.label.contains("晨光落在书页上"), item.label)
        attach("记录中的书签")
        item.tap()
        XCTAssertTrue(app.sliders["novel.progress"].waitForExistence(timeout: 20), app.debugDescription)
        app.buttons["novel.back"].tap()

        // Removing it from the timeline's menu takes it off the list.
        XCTAssertTrue(item.waitForExistence(timeout: 10), app.debugDescription)
        let saved = app.buttons[item.identifier]
        saved.press(forDuration: 1)
        let delete = app.buttons["删除书签"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5), app.debugDescription)
        delete.tap()
        XCTAssertTrue(saved.waitForNonExistence(timeout: 10), app.debugDescription)
    }

    @MainActor
    func testDetailsChapterSelectionDownloadsWithoutOpeningReader() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        app.openDetailsChapters()
        let select = app.buttons["details.chapters.select"]
        XCTAssertTrue(select.waitForExistence(timeout: 5), "完整章节页必须提供多选入口")
        select.tap()
        let rows = app.cells.containing(NSPredicate(format: "identifier BEGINSWITH %@", "chapter."))
        XCTAssertEqual(rows.count, 2, app.debugDescription)
        rows.element(boundBy: 0).tap()
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.buttons["details.chapters.downloadSelected"].label.contains("2"))
        XCTAssertFalse(app.images["reader.image"].exists)
        let selectAll = app.buttons["details.chapters.selectAll"]
        XCTAssertFalse(app.buttons["continueReading"].exists, "全局继续阅读条不能覆盖目录工具栏")
        XCTAssertTrue(selectAll.isHittable, app.debugDescription)
        selectAll.tap()
        XCTAssertFalse(app.buttons["details.chapters.downloadSelected"].isEnabled)
        app.buttons["details.chapters.selectAll"].tap()
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.buttons["details.chapters.downloadSelected"].label.contains("1"))
        app.buttons["details.chapters.downloadSelected"].tap()
        XCTAssertTrue(select.waitForExistence(timeout: 5))
        app.buttons["details.chapters.options"].tap()
        app.buttons["details.chapters.downloads"].tap()
        XCTAssertTrue(app.buttons["downloads.close"].waitForExistence(timeout: 10), app.debugDescription)
        let first = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "download.entry.", "fixture_reader_chapters"
        )).firstMatch
        for _ in 0..<8 where !first.exists { app.collectionViews.firstMatch.swipeUp() }
        XCTAssertTrue(first.waitForExistence(timeout: 10), app.debugDescription)
        attach("详情章节选择及下载队列")
        // A record shows its work's cover and opens that work's details.
        let open = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "download.open.")).firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 5), app.debugDescription)
        open.tap()
        XCTAssertTrue(app.buttons["details.chapters.showAll"].waitForExistence(timeout: 10), app.debugDescription)
        attach("下载记录打开详情")
        // A finished download remains available through the filtered and combined timeline.
        app.terminate()
        app.launch()
        app.mainTab("记录").tap()
        app.selectRecordsFilter("downloads")
        app.buttons["space.menu"].tap()
        let videoSpace = app.buttons["space.select.VIDEO"]
        for _ in 0..<5 where !videoSpace.isHittable { app.scrollViews.firstMatch.swipeUp() }
        videoSpace.tap()
        let showAll = app.buttons["space.showAll"]
        XCTAssertTrue(showAll.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.staticTexts["其他内容空间里还有下载记录。"].exists)
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "timeline.download.")).firstMatch.exists)
        showAll.tap()
        let work = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "timeline.download.", "fixture_reader_chapters")).firstMatch
        XCTAssertTrue(work.waitForExistence(timeout: 15), app.debugDescription)
        attach("记录下载筛选")
        app.selectRecordsFilter("all")
        XCTAssertTrue(app.staticTexts["历史、书签与下载"].waitForExistence(timeout: 10))
        let download = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
            "timeline.download.", "fixture_reader_chapters")).firstMatch
        XCTAssertTrue(download.waitForExistence(timeout: 10), app.debugDescription)
        attach("时间轴历史与下载")
        app.selectRecordsFilter("downloads")
        work.tap()
        app.buttons["打开作品"].tap()
        app.openDetailsChapters()
        let local = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
            "chapter.", "本地")).firstMatch
        XCTAssertTrue(local.waitForExistence(timeout: 10), "导入作品的章节应标为本地：\(app.debugDescription)")
        attach("章节离线标识")
    }

    @MainActor
    func testPagedWindowChangesChapterAndStopsAtTheBookEnds() throws {
        openChapterWindowFixture()
        selectReaderMode("默认（从左到右）")
        selectReaderAnimation("禁用")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 2)
        XCTAssertTrue(app.buttons["reader.chapters"].label.contains("01"))
        swipeAcrossChapter(canvas, forward: true)
        waitForReaderChapter("02")
        swipeAcrossChapter(canvas, forward: true)
        XCTAssertTrue(app.buttons["reader.chapters"].label.contains("02"))
        swipeAcrossChapter(canvas, forward: false)
        waitForReaderChapter("01")
        swipeAcrossChapter(canvas, forward: false)
        XCTAssertTrue(app.buttons["reader.chapters"].label.contains("01"))
        attach("普通分页跨章与首末章停止")
    }

    private func openChapterWindowFixture() {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        let first = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"
        )).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 10))
        first.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
    }

    @MainActor
    func testPagedCrossChapterBookmarkKeepsChapterLocalPage() throws {
        openChapterWindowFixture()
        selectReaderMode("默认（从左到右）")
        selectReaderAnimation("禁用")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 2)
        swipeAcrossChapter(canvas, forward: true)
        waitForReaderChapter("02")
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "1")
        XCTAssertEqual(app.staticTexts["reader.totalPages"].value as? String, "1")
        let bookmark = app.buttons["reader.bookmark"]
        if bookmark.value as? String == "已添加" { bookmark.tap() }
        bookmark.tap()
        let saved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            bookmark.value as? String == "已添加"
        }, object: nil)
        wait(for: [saved], timeout: 10)
        swipeAcrossChapter(canvas, forward: false)
        waitForReaderChapter("01")
        app.buttons["reader.chapters"].tap()
        app.segmentedControls.buttons["书签"].tap()
        let entry = app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "02", "第 1 页"))
            .firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 10), app.debugDescription)
        entry.tap()
        waitForReaderChapter("02")
        app.buttons["reader.back"].tap()
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 15))
        app.terminate()
    }

    /// Run after the bookmark case: its completed navigation saves chapter 02 to the real library database.
    @MainActor
    func testPagedCrossChapterHistoryReopensWithTheMatchingImage() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 15))
        app.detailsReadButton().tap()
        waitForReaderChapter("02")
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "1")
        attach("跨章重启续读的实际图片和章内页码")
    }

    @MainActor
    func testRtlPagedAndAnimatedWindowsChangeChapter() throws {
        openChapterWindowFixture()
        for animation in ["禁用", "高级", "仿真"] {
            selectReaderMode("从右到左")
            selectReaderAnimation(animation)
            let canvas = animation == "禁用" ? app.scrollViews["reader.canvas"]
                : app.descendants(matching: .any)["reader.turnPager"].firstMatch
            XCTAssertTrue(canvas.waitForExistence(timeout: 10))
            waitForChapterWindow(canvas, count: 2)
            swipeAcrossChapter(canvas, forward: true, reversed: true)
            waitForReaderChapter("02")
            swipeAcrossChapter(canvas, forward: false, reversed: true)
            waitForReaderChapter("01")
        }
        attach("RTL 原生高级仿真分页跨章")
    }

    @MainActor
    func testVerticalPagedWindowChangesChapterWithTheMatchingImage() throws {
        openChapterWindowFixture()
        selectReaderMode("从上到下")
        selectReaderAnimation("禁用")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 2)
        swipeAcrossChapter(canvas, forward: true, vertical: true)
        waitForReaderChapter("02")
        swipeAcrossChapter(canvas, forward: false, vertical: true)
        waitForReaderChapter("01")
        attach("纵向分页普通跨章与实际图片")
    }

    @MainActor
    func testPagedWindowTrimsNeighborsAndKeepsTheDisplayedChapter() throws {
        importFile(source: "LOCAL", file: "fixture_reader_window", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("默认（从左到右）")
        selectReaderAnimation("禁用")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 5)
        for chapter in ["02", "03"] {
            app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
            let lastPage = app.images.matching(NSPredicate(
                format: "identifier == %@ AND label CONTAINS %@", "reader.image", "第 3 页"
            )).firstMatch
            let atLastPage = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                self.app.staticTexts["reader.currentPage"].value as? String == "3" && lastPage.isHittable
            }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [atLastPage], timeout: 10), .completed,
                           "\(canvas.value ?? "no position")\n\(canvas.debugDescription)")
            swipeAcrossChapter(canvas, forward: true)
            waitForReaderChapter(chapter)
            XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "1")
            XCTAssertEqual(app.staticTexts["reader.totalPages"].value as? String, "3")
        }
        swipeAcrossChapter(canvas, forward: false)
        waitForReaderChapter("02")
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "3")
        attach("三章窗口裁剪保留实际画面和章内页码")
    }

    /// Page-by-page drags through a longer chapter enter the next chapter at its first page (not wherever the old
    /// window's scroll offset lands once the window is rebuilt around the new chapter), and dragging back returns to
    /// the previous chapter's last page.
    @MainActor
    func testPagedDragsAcrossLongChaptersKeepTheChapterLocalPage() throws {
        importFile(source: "LOCAL", file: "fixture_reader_paged", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("默认（从左到右）")
        selectReaderAnimation("禁用")
        let canvas = app.scrollViews["reader.canvas"]
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        waitForPage("1")
        for page in 2...8 {
            dragPage(canvas, forward: true)
            waitForPage("\(page)")
        }
        dragPage(canvas, forward: true)
        waitForReaderChapter("02")
        waitForPage("1")
        dragPage(canvas, forward: true)
        waitForPage("2")
        dragPage(canvas, forward: false)
        waitForPage("1")
        dragPage(canvas, forward: false)
        waitForReaderChapter("01")
        waitForPage("8")
        attach("长章节逐页拖动跨章保持章内页码")
    }

    /// Switching a paged reader in the middle of a chapter to the vertical strip restores the reading page exactly:
    /// the new strip is laid out from the scene and starts at the reading page, in the same chapter, flush with the
    /// screen edge (the strip has no safe-area insets).
    @MainActor
    func testPagedToContinuousSwitchRestoresTheReadingPage() throws {
        importFile(source: "LOCAL", file: "fixture_reader_webtoon", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "02"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("默认（从左到右）")
        let pager = app.scrollViews["reader.canvas"]
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        waitForPage("1")
        dragPage(pager, forward: true)
        waitForPage("2")
        dragPage(pager, forward: true)
        waitForPage("3")
        let chapter = app.buttons["reader.chapters"].label
        selectReaderMode("条漫")
        let page = app.images.matching(NSPredicate(
            format: "identifier == %@ AND label CONTAINS %@", "reader.image", "第 3 页"
        )).firstMatch
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            page.exists && page.isHittable
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 15), .completed,
                       "page 3 at \(page.frame), canvas \(app.scrollViews["reader.canvas"].value ?? "")")
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "3")
        XCTAssertEqual(app.buttons["reader.chapters"].label, chapter)
        XCTAssertEqual(page.frame.minX, app.frame.minX, accuracy: 1)
        attach("分页切换条漫恢复阅读页")
        let canvas = app.scrollViews["reader.canvas"]
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.7)).press(forDuration: 0.05,
            thenDragTo: canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)), withVelocity: .slow,
            thenHoldForDuration: 0.3)
    }

    /// A paged double tap zooms about the tapped point (the page is red/green/blue thirds: tapping the blue third
    /// brings blue to the centre, where a centre zoom would keep green), a zoomed page pans instead of turning, and
    /// back at fit the same drag turns the page.
    @MainActor
    func testPagedDoubleTapZoomsAtTheTapAndPansBeforeTurning() throws {
        importFile(source: "LOCAL", file: "fixture_reader_zoom", reuse: true)
        openFirstChapter(title: "fixture_reader_zoom")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("默认（从左到右）")
        selectReaderAnimation("禁用")
        let canvas = app.scrollViews["reader.canvas"]
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        waitForPage("1")
        if app.buttons["reader.more"].exists { canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        XCTAssertTrue(waitForCentre("green"), "unzoomed centre")
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).doubleTap()
        XCTAssertTrue(waitForCentre("blue"), "double tap did not zoom about the tapped point")
        attach("双击按点放大")
        // Zoomed: a drag pans the page (back towards its middle) and the page stays.
        let from = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5))
        from.press(forDuration: 0.05, thenDragTo: canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)),
                   withVelocity: .slow, thenHoldForDuration: 0.05)
        XCTAssertTrue(waitForCentre("green"), "a zoomed drag did not pan the page")
        XCTAssertTrue((canvas.value as? String)?.hasPrefix("第 1 页，") == true, "\(canvas.value ?? "")")
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).doubleTap()
        XCTAssertTrue(waitForCentre("green"))
        dragPage(canvas, forward: true)
        waitForPage("2")
    }

    /// The vertical strip's double tap keeps the tapped point in place as the strip is laid out wider.
    @MainActor
    func testContinuousDoubleTapZoomsAtTheTap() throws {
        importFile(source: "LOCAL", file: "fixture_reader_zoom", reuse: true)
        openFirstChapter(title: "fixture_reader_zoom")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        waitForPage("1")
        if app.buttons["reader.more"].exists { canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        XCTAssertTrue(waitForCentre("green"), "unzoomed centre")
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.3)).doubleTap()
        XCTAssertTrue(waitForCentre("blue"), "double tap did not zoom the strip about the tapped point")
        attach("条漫双击按点放大")
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).doubleTap()
        XCTAssertTrue(waitForCentre("green"))
    }

    @MainActor
    func testContinuousLongPageRepeatedZoomRestoresTheViewport() throws {
        importFile(source: "LOCAL", file: "fixture_reader_long", reuse: true)
        openFirstChapter(title: "fixture_reader_long")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        // Reused long-page fixtures can resume within page 1; changing the selected page clears that old offset.
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        // A tiled page exposes individual image tiles to accessibility, whose centres need not match the screen.
        XCTAssertEqual(readingPage(canvas), "1")
        XCTAssertTrue(waitForCentre("red"))
        hideReaderControls(canvas)
        canvas.swipeUp()
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > 300
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [moved], timeout: 10), .completed, "\(canvas.value ?? "")")
        let saved = readingOffset(canvas)
        let tap = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.3))
        // The 800 × 12000 fixture exceeds the base decode budget and exercises the tiled long-page path.
        for _ in 0..<10 {
            tap.doubleTap()
            XCTAssertTrue(waitForZoom(canvas, "200%"))
            tap.doubleTap()
            XCTAssertTrue(waitForZoom(canvas, "100%"))
            XCTAssertEqual(readingPage(canvas), "1")
            XCTAssertEqual(readingOffset(canvas), saved, accuracy: 5)
            XCTAssertTrue(waitForCentre("red"))
        }
        attach("长图分块连续双击恢复视口")
    }

    @MainActor
    func testContinuousMixedPageZoomRestoresVisibleNeighbours() throws {
        importFile(source: "LOCAL", file: "fixture_reader_zoom_neighbours", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        // Adjacent chapters load only within ten pages of the end of this eighteen-page chapter.
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0.5)
        waitForChapterWindow(canvas, count: 36)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0.35)
        hideReaderControls(canvas)
        let loaded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.fixturePagePixelFraction(canvas) > 0.9
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 15), .completed)
        let visiblePages = app.images.matching(identifier: "reader.image").allElementsBoundByIndex.filter {
            $0.frame.intersection(canvas.frame).height > 20
        }
        XCTAssertGreaterThanOrEqual(Set(visiblePages.map(\.label)).count, 2,
                                   "The fixture must show multiple pages before exercising neighbour retention")
        let page = readingPage(canvas), offset = readingOffset(canvas)
        let tap = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.3))
        for _ in 0..<10 {
            tap.doubleTap()
            XCTAssertTrue(waitForZoom(canvas, "200%"))
            tap.doubleTap()
            XCTAssertTrue(waitForZoom(canvas, "100%"))
            XCTAssertEqual(readingPage(canvas), page)
            XCTAssertEqual(readingOffset(canvas), offset, accuracy: 5)
            XCTAssertGreaterThan(fixturePagePixelFraction(canvas), 0.9)
        }
        attach("多页条漫连续双击恢复可见邻页")
    }

    @MainActor
    func testContinuousRepeatedZoomAndPinchBelowFit() throws {
        importFile(source: "LOCAL", file: "fixture_reader_zoom", reuse: true)
        openFirstChapter(title: "fixture_reader_zoom")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        waitForPage("1")
        hideReaderControls(canvas)
        let tap = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.3))
        for _ in 0..<10 {
            tap.doubleTap()
            XCTAssertTrue(waitForZoom(canvas, "200%"))
            tap.doubleTap()
            XCTAssertTrue(waitForZoom(canvas, "100%"))
            XCTAssertTrue(waitForCentre("green"))
        }
        canvas.pinch(withScale: 0.5, velocity: -1)
        XCTAssertLessThan(readingZoom(canvas), 100, "Pinching in must expose more of the strip than the initial fit")
        XCTAssertTrue(waitForCentre("green", y: 0.2), "A narrower strip must remain centered")
        attach("条漫缩小至初始视图以下")
        tap.doubleTap()
        XCTAssertTrue(waitForZoom(canvas, "100%"), "Double tapping a shrunken strip must restore normal size first")
        XCTAssertTrue(waitForCentre("green"))
        tap.doubleTap()
        XCTAssertTrue(waitForZoom(canvas, "200%"))
        tap.doubleTap()
        XCTAssertTrue(waitForZoom(canvas, "100%"))
        XCTAssertTrue(waitForCentre("green"))
    }

    /// Native pinch and double tap share the same camera. Rebasing after each gesture must not accumulate scale
    /// or move the viewport; the accompanying video check covers the intermediate frames, not only this end state.
    @MainActor
    func testContinuousPinchThenDoubleTapRestoresFit() throws {
        importFile(source: "LOCAL", file: "fixture_reader_zoom", reuse: true)
        openFirstChapter(title: "fixture_reader_zoom")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        waitForPage("1")
        hideReaderControls(canvas)
        let viewport = canvas.frame
        for _ in 0..<2 {
            canvas.pinch(withScale: 2, velocity: 1)
            let magnified = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                let description = canvas.value as? String ?? ""
                return description.contains("缩放") && !description.contains("缩放 100%")
            }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [magnified], timeout: 10), .completed)
            XCTAssertEqual(canvas.frame, viewport)
            canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).doubleTap()
            XCTAssertTrue(waitForZoom(canvas, "100%"))
            XCTAssertTrue(waitForCentre("green"))
            XCTAssertEqual(readingPage(canvas), "1")
            XCTAssertEqual(canvas.frame, viewport)
        }
        attach("条漫捏合与双击连续还原")
    }

    /// The strip is laid out again for a zoom rather than magnified, and a page whose real size is still unknown keeps
    /// its one-screen estimate, so the scene offsets above it do not follow the zoom. A jump measures only the page it
    /// lands on: zooming and restoring around that page must keep the reader there instead of throwing it ahead.
    @MainActor
    func testContinuousDoubleTapKeepsThePageAfterAJump() throws {
        importFile(source: "LOCAL", file: "fixture_reader_webtoon", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 16)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0.5)
        let jumped = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingPage(canvas) != "1"
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [jumped], timeout: 10), .completed, "\(canvas.value ?? "no position")")
        let page = readingPage(canvas)
        waitForPage(page)
        hideReaderControls(canvas)
        // The page the jump measured sits under the tap; the ones above it never left their estimate.
        let tap = CGVector(dx: 0.5, dy: 0.25)
        for _ in 0..<3 {
            canvas.coordinate(withNormalizedOffset: tap).doubleTap()
            XCTAssertTrue(waitForZoom(canvas, "200%"), "\(canvas.value ?? "no position")")
            XCTAssertEqual(readingPage(canvas), page, "\(canvas.value ?? "no position")")
            canvas.coordinate(withNormalizedOffset: tap).doubleTap()
            XCTAssertTrue(waitForZoom(canvas, "100%"), "\(canvas.value ?? "no position")")
            XCTAssertEqual(readingPage(canvas), page, "\(canvas.value ?? "no position")")
        }
        attach("条漫跳转后连续双击保持阅读页")
    }

    /// The controls fade in and out: a tap made while they move belongs to them, not to the page read next.
    private func hideReaderControls(_ canvas: XCUIElement) {
        guard app.buttons["reader.more"].exists else { return }
        canvas.tap()
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !self.app.buttons["reader.more"].exists
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 5), .completed, "the reader controls stayed visible")
    }

    /// The reading page from the strip's own description, which stays readable while the controls are hidden.
    private func readingPage(_ canvas: XCUIElement) -> String {
        let tail = (canvas.value as? String ?? "").components(separatedBy: "第 ").dropFirst().first ?? ""
        return String(tail.prefix { $0.isNumber })
    }

    private func waitForZoom(_ canvas: XCUIElement, _ zoom: String) -> Bool {
        let zoomed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (canvas.value as? String)?.contains("缩放 \(zoom)") == true
        }, object: nil)
        return XCTWaiter.wait(for: [zoomed], timeout: 10) == .completed
    }

    private func waitForCentre(_ colour: String, y: CGFloat = 0.5) -> Bool {
        let matches = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard let pixel = self.screenPixel(x: 0.5, y: y) else { return false }
            let (red, green, blue) = (Int(pixel[0]), Int(pixel[1]), Int(pixel[2]))
            switch colour {
            case "red": return red > green + 80 && red > blue + 80
            case "green": return green > red + 80 && green > blue + 80
            default: return blue > red + 80 && blue > green + 80
            }
        }, object: nil)
        return XCTWaiter.wait(for: [matches], timeout: 10) == .completed
    }

    private func screenPixel(x: CGFloat, y: CGFloat) -> [UInt8]? {
        autoreleasepool {
            guard let image = app.screenshot().image.cgImage,
                  let pixel = image.cropping(to: CGRect(x: Int(CGFloat(image.width) * x),
                                                        y: Int(CGFloat(image.height) * y), width: 1, height: 1)) else {
                return nil
            }
            var bytes = [UInt8](repeating: 0, count: 4)
            let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
                guard let context = CGContext(data: buffer.baseAddress, width: 1, height: 1, bitsPerComponent: 8,
                                              bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
                context.draw(pixel, in: CGRect(x: 0, y: 0, width: 1, height: 1))
                return true
            }
            return drawn ? bytes : nil
        }
    }

    /// A finger drag with a hold at its end, like a reader turning one page, rather than a fling.
    private func dragPage(_ canvas: XCUIElement, forward: Bool) {
        let start = canvas.coordinate(withNormalizedOffset: CGVector(dx: forward ? 0.8 : 0.2, dy: 0.5))
        let end = canvas.coordinate(withNormalizedOffset: CGVector(dx: forward ? 0.2 : 0.8, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.05)
        if !app.buttons["reader.more"].exists { canvas.tap() }
    }

    private func waitForPage(_ page: String) {
        let current = app.staticTexts["reader.currentPage"]
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            current.value as? String == page
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 10), .completed,
                       "expected page \(page), got \(current.value ?? "nil") in \(app.buttons["reader.chapters"].label)")
        let image = app.images.matching(NSPredicate(
            format: "identifier == %@ AND label CONTAINS %@", "reader.image", "第 \(page) 页"
        )).firstMatch
        let shown = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            image.exists && abs(image.frame.midX - self.app.frame.midX) < 4
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [shown], timeout: 10), .completed, "page \(page) is not on screen")
    }

    /// Continuous strips join complete neighbor chapters: plain scrolling enters 02 and 03 and returns to 02 without
    /// an explicit chapter switch, and the window stays bounded to three chapters.
    @MainActor
    func testContinuousStripScrollsIntoNeighborChapters() throws {
        importFile(source: "LOCAL", file: "fixture_reader_window", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 6)
        for chapter in ["02", "03"] {
            scrollStrip(canvas, until: chapter, forward: true)
            waitForReaderChapter(chapter)
            XCTAssertEqual(app.staticTexts["reader.totalPages"].value as? String, "3")
        }
        waitForChapterWindow(canvas, count: 6)
        scrollStrip(canvas, until: "02", forward: false)
        // The first visible page reads: 02's last page at the top while 03 still fills the middle of the screen.
        XCTAssertTrue(app.buttons["reader.chapters"].label.contains("02"))
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "3")
        waitForChapterWindow(canvas, count: 9)
        dragStrip(canvas, forward: false)
        dragStrip(canvas, forward: false)
        waitForReaderChapter("02")
        attach("连续条漫普通滚动跨章")
    }

    /// Hitches while flinging the continuous strip across chapters, from XCTest's scrolling and deceleration signposts
    /// (hitch time ratio, frame rate). Compare runs on one device to catch a regression; simulator numbers are only
    /// relative to each other.
    @MainActor
    func testContinuousStripScrollPerformance() throws {
        importFile(source: "LOCAL", file: "fixture_reader_window", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 6)
        measure(metrics: [XCTOSSignpostMetric.scrollingAndDecelerationMetric]) {
            canvas.swipeUp(velocity: .fast)
            canvas.swipeDown(velocity: .fast)
        }
        XCTAssertTrue(app.images["reader.image"].firstMatch.exists, app.debugDescription)
    }

    /// 02 is shorter than the screen, so it never becomes the first visible page: scrolling to the strip's end reads it.
    @MainActor
    func testContinuousStripEndEntersAShortNextChapter() throws {
        importFile(source: "LOCAL", file: "fixture_reader_pull_long", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"))
            .firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        waitForChapterWindow(canvas, count: 3)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        let lastPage = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.staticTexts["reader.currentPage"].value as? String == "2"
        }, object: nil)
        wait(for: [lastPage], timeout: 10)
        for _ in 0..<12 where !app.buttons["reader.chapters"].label.contains("02") {
            canvas.swipeUp(velocity: .fast)
            if !app.buttons["reader.more"].exists { canvas.tap() }
        }
        XCTAssertTrue(app.buttons["reader.chapters"].label.contains("02"), "\(canvas.value ?? "no position")")
        waitForReaderChapter("02")
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "1")
        XCTAssertFalse(app.buttons["reader.nextChapter"].isEnabled)
        attach("条漫末端进入短邻章")
    }

    private func scrollStrip(_ canvas: XCUIElement, until chapter: String, forward: Bool) {
        for _ in 0..<12 where !app.buttons["reader.chapters"].label.contains(chapter) { dragStrip(canvas, forward: forward) }
    }

    private func dragStrip(_ canvas: XCUIElement, forward: Bool) {
        let start = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: forward ? 0.7 : 0.3))
        let end = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: forward ? 0.3 : 0.7))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.1)
        if !app.buttons["reader.more"].exists { canvas.tap() }
    }

    @MainActor
    func testPreviousChapterButtonOpensTheChapterStart() throws {
        importFile(source: "LOCAL", file: "fixture_reader_pull_long", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "02"
        )).firstMatch.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        app.buttons["reader.previousChapter"].tap()
        waitForReaderChapter("01")
        let tail = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas)) < 5 && self.app.staticTexts["reader.currentPage"].value as? String == "1"
                && self.app.images.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@",
                                                       "reader.image", "第 1 页")).firstMatch.isHittable
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [tail], timeout: 15), .completed,
                       "\(canvas.value ?? "no position")\n\(canvas.debugDescription)")
        attach("上一章按钮与 Android 一致打开章首")
        app.buttons["reader.nextChapter"].tap()
        waitForReaderChapter("02")
    }

    private func swipeAcrossChapter(_ canvas: XCUIElement, forward: Bool, vertical: Bool = false, reversed: Bool = false) {
        if vertical {
            if forward { canvas.swipeUp() } else { canvas.swipeDown() }
        } else if forward != reversed { canvas.swipeLeft() }
        else { canvas.swipeRight() }
        XCTAssertTrue(canvas.waitForExistence(timeout: 30))
        if !app.buttons["reader.more"].exists { canvas.tap() }
    }

    private func waitForChapterWindow(_ canvas: XCUIElement, count: Int) {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (canvas.value as? String)?.contains("窗口 \(count) 页") == true
        }, object: nil)
        wait(for: [ready], timeout: 30)
        XCTAssertFalse(app.staticTexts["reader.chapterPull"].exists)
    }

    private func waitForReaderChapter(_ chapter: String) {
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.buttons["reader.chapters"].exists && self.app.buttons["reader.chapters"].label.contains(chapter)
        }, object: nil)
        wait(for: [changed], timeout: 15)
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        let correctImage = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readerImageMatchesChapter(chapter)
        }, object: nil)
        wait(for: [correctImage], timeout: 10)
    }

    /// These immutable CBZ fixtures use red for chapter 01 and blue for chapter 02.
    /// Verifies displayed pixels as well as metadata, including when a lazy pager restores an offscreen slot.
    private func readerImageMatchesChapter(_ chapter: String) -> Bool {
        return autoreleasepool {
            guard let image = app.screenshot().image.cgImage,
                  let pixel = image.cropping(to: CGRect(x: image.width / 2, y: image.height / 2, width: 1, height: 1)) else {
                return false
            }
            var bytes = [UInt8](repeating: 0, count: 4)
            let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
                guard let context = CGContext(data: buffer.baseAddress, width: 1, height: 1, bitsPerComponent: 8,
                                              bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
                context.draw(pixel, in: CGRect(x: 0, y: 0, width: 1, height: 1))
                return true
            }
            guard drawn else { return false }
            if chapter == "03" { return Int(bytes[1]) > Int(bytes[0]) + 50 && Int(bytes[1]) > Int(bytes[2]) + 50 }
            return chapter == "02" ? Int(bytes[2]) > Int(bytes[0]) + 50 : Int(bytes[0]) > Int(bytes[2]) + 50
        }
    }

    private func selectReaderAnimation(_ animation: String) {
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["布局"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "翻页效果")).firstMatch.tap()
        app.buttons[animation].tap()
        app.buttons["reader.panel.close"].tap()
    }

    @MainActor
    func testImportedComicOpensInTheReader() throws {
        importFile(source: "LOCAL", file: "fixture_comic")
        openFirstChapter(title: "fixture_comic")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        attach("本地漫画阅读")
    }

    /// A uniform cover should stay uniform at both viewport edges throughout the blur and drift animation.
    @MainActor
    func testPanoramaAnimationKeepsBothEdgesCovered() throws {
        app.terminate()
        app.launchArguments += ["-details.panorama", "YES", "-details.panorama.preset", "soft",
                                "-details.panorama.layout", "fullScreen", "-details.panorama.animated", "YES",
                                "-details.panorama.scrollLinked", "NO"]
        app.launch()
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        let cover = app.descendants(matching: .any)["details.cover"]
        expectation(for: NSPredicate(format: "value == %@", "已加载"), evaluatedWith: cover)
        waitForExpectations(timeout: 30)
        for phase in 0..<3 {
            if phase > 0 { Thread.sleep(forTimeInterval: 11) }
            let screenshot = app.screenshot()
            let image = try XCTUnwrap(screenshot.image.cgImage)
            let y = Int(130 * CGFloat(image.height) / app.frame.height)
            func pixel(_ x: Int) throws -> [UInt8] {
                let sample = try XCTUnwrap(image.cropping(to: CGRect(x: x, y: y, width: 1, height: 1)))
                var rgba = [UInt8](repeating: 0, count: 4)
                let rendered = rgba.withUnsafeMutableBytes { bytes -> Bool in
                    guard let context = CGContext(data: bytes.baseAddress, width: 1, height: 1,
                                                  bitsPerComponent: 8, bytesPerRow: 4,
                                                  space: CGColorSpaceCreateDeviceRGB(),
                                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                                                    | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
                    context.draw(sample, in: CGRect(x: 0, y: 0, width: 1, height: 1))
                    return true
                }
                XCTAssertTrue(rendered)
                return rgba
            }
            let left = try pixel(0), right = try pixel(image.width - 1)
            for channel in 0..<3 {
                XCTAssertLessThanOrEqual(abs(Int(left[channel]) - Int(right[channel])), 8,
                                         "Panorama exposes the background at phase \(phase): \(left) / \(right)")
            }
            attach("全景动画边缘 \(phase)")
        }
    }

    /// Fixture cover dimensions differ from the preview viewport; visible pixels must retain the entire aspect.
    @MainActor
    func testCoverPreviewShowsTheWholePortraitAndLandscapeImage() throws {
        for (fixture, ratio) in [("fixture_reader_zoom", 1.5), ("fixture_reader_horizontal", 1.0 / 3.0)] {
            if fixture != "fixture_reader_zoom" { app.terminate(); app.launch() }
            importFile(source: "LOCAL", file: fixture, reuse: true)
            let cover = app.descendants(matching: .any)["details.cover"]
            XCTAssertTrue(cover.waitForExistence(timeout: 30), app.debugDescription)
            cover.tap()
            XCTAssertTrue(app.navigationBars["封面"].waitForExistence(timeout: 5), app.debugDescription)
            let preview = app.descendants(matching: .any)["details.coverPreview.image"]
            expectation(for: NSPredicate(format: "value == %@", "已加载"), evaluatedWith: preview)
            waitForExpectations(timeout: 30)
            attach("完整封面 \(fixture)")
            XCTAssertEqual(coverPreviewPixelRatio(), ratio, accuracy: 0.03, "The preview cropped \(fixture)")
            app.navigationBars["封面"].buttons["完成"].tap()
        }
    }

    /// The fixtures contain saturated red/green/blue; black letterboxing and navigation chrome are excluded.
    private func coverPreviewPixelRatio() -> Double {
        guard let image = app.screenshot().image.cgImage else { return 0 }
        let width = 240
        let height = image.height * width / image.width
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: width, height: height,
                                          bitsPerComponent: 8, bytesPerRow: width * 4,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                                            | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return 0 }
        func isCover(_ x: Int, _ y: Int) -> Bool {
            let i = (y * width + x) * 4
            let rgb = [Int(pixels[i]), Int(pixels[i + 1]), Int(pixels[i + 2])]
            return rgb.max()! > 120 && rgb.min()! < 100 && rgb.max()! - rgb.min()! > 80
        }
        // The presenting details panorama can remain visible above the sheet; measure only the central image.
        guard isCover(width / 2, height / 2) else { return 0 }
        var left = width / 2, right = width / 2, top = height / 2, bottom = height / 2
        while left > 0 && isCover(left - 1, height / 2) { left -= 1 }
        while right + 1 < width && isCover(right + 1, height / 2) { right += 1 }
        while top > 0 && isCover(width / 2, top - 1) { top -= 1 }
        while bottom + 1 < height && isCover(width / 2, bottom + 1) { bottom += 1 }
        return Double(bottom - top + 1) / Double(right - left + 1)
    }

    @MainActor
    func testMetadataIsVisibleAndSearchStartsWithTheWorkTitle() throws {
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        let title = app.staticTexts["details.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 30))
        let original = title.label
        attach("详情页统一边距")
        let metadata = app.buttons["details.metadata"]
        XCTAssertTrue(metadata.isHittable, "Metadata should be discoverable without opening More")
        metadata.tap()
        XCTAssertTrue(app.navigationBars["追踪与资料"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.textFields["tracking.query"].value as? String, original)
        for site in [2, 4, 5] { XCTAssertTrue(app.buttons["tracking.site.\(site)"].exists) }
        attach("轻量作品资料选择")
        app.navigationBars["追踪与资料"].buttons["完成"].tap()
        XCTAssertTrue(metadata.waitForExistence(timeout: 10))
        XCTAssertEqual(title.label, original)
        assertMetadataChapterIsAvailable()
    }

    @MainActor
    func testOnlineMetadataCanBeUsedInOneTapAndRestoredAfterRestart() throws {
        guard ProcessInfo.processInfo.environment["KOTOTORO_TRACKING_ONLINE"] == "1" else {
            throw XCTSkip("Set TEST_RUNNER_KOTOTORO_TRACKING_ONLINE=1 for public Bangumi acceptance")
        }
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        XCTAssertTrue(app.buttons["details.metadata"].waitForExistence(timeout: 30))
        app.buttons["details.metadata"].tap()
        let restore = app.buttons["tracking.useSource"]
        if restore.waitForExistence(timeout: 2) {
            restore.tap()
            XCTAssertTrue(app.navigationBars["追踪与资料"].waitForNonExistence(timeout: 30))
            XCTAssertTrue(app.buttons["details.metadata"].waitForExistence(timeout: 10))
            app.buttons["details.metadata"].tap()
        }
        app.buttons["tracking.site.5"].tap()
        let query = app.textFields["tracking.query"]
        XCTAssertTrue(query.waitForExistence(timeout: 10))
        query.tap()
        let current = query.value as? String ?? ""
        query.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
        query.typeText("葬送的芙莉莲")
        app.buttons["tracking.search"].tap()
        let use = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "tracking.use.")).firstMatch
        XCTAssertTrue(use.waitForExistence(timeout: 60), app.debugDescription)
        use.tap()
        XCTAssertTrue(app.navigationBars["追踪与资料"].waitForNonExistence(timeout: 60), app.debugDescription)
        let metadata = app.buttons["details.metadata"]
        XCTAssertTrue(metadata.waitForExistence(timeout: 60), app.debugDescription)
        XCTAssertTrue(metadata.label.contains("Bangumi"))
        XCTAssertTrue(app.staticTexts["details.title"].label.contains("芙莉莲"))
        assertMetadataChapterIsAvailable()
        attach("一键使用 Bangumi 资料")

        app.terminate(); app.launch()
        importFile(source: "LOCAL", file: "fixture_comic", reuse: true)
        XCTAssertTrue(metadata.waitForExistence(timeout: 30))
        XCTAssertTrue(metadata.label.contains("Bangumi"))
        let restoredTitle = app.staticTexts["details.title"].label
        metadata.tap()
        XCTAssertTrue(restore.waitForExistence(timeout: 10))
        XCTAssertEqual(app.textFields["tracking.query"].value as? String, restoredTitle)
        restore.tap()
        XCTAssertTrue(app.navigationBars["追踪与资料"].waitForNonExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(metadata.waitForExistence(timeout: 10))
        XCTAssertTrue(metadata.label.contains("追踪与资料"))
        XCTAssertTrue(app.staticTexts["details.title"].label.contains("fixture_comic"))
        assertMetadataChapterIsAvailable()
        attach("恢复源资料并保留章节")
    }

    @MainActor
    private func assertMetadataChapterIsAvailable() {
        app.openDetailsChapters()
        XCTAssertTrue(app.navigationBars["全部章节"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).firstMatch.exists)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 10))
    }

    @MainActor
    func testPrepareDownloadedReaderFixture() throws {
        importFile(source: "LOCAL", file: "fixture_reader_download", reuse: true)
        openFirstChapter(title: "fixture_reader_download")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["下载本章"].tap()
        XCTAssertTrue(app.staticTexts["本章已下载，可离线阅读"].waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["reader.panel.close"].tap()
        app.buttons["reader.back"].tap()
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 10))
        app.terminate()
    }

    @MainActor
    func testStorageCacheClearKeepsOfflineComicAndDeletingFilesKeepsResumeHistory() throws {
        importFile(source: "LOCAL", file: "fixture_storage_comic", reuse: true)
        openFirstChapter(title: "fixture_storage_comic")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["下载本章"].tap()
        XCTAssertTrue(app.staticTexts["本章已下载，可离线阅读"].waitForExistence(timeout: 30))
        app.buttons["reader.panel.close"].tap()
        app.buttons["reader.back"].tap()
        openStorageSettings()
        let row = storedChapter(title: "fixture_storage_comic")
        scrollStorageTo(row)
        XCTAssertTrue(row.exists, app.debugDescription)
        let delete = row.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "storage.delete.")).firstMatch
        let identifier = delete.identifier
        XCTAssertFalse(identifier.isEmpty)
        attach("漫画离线章节与缓存占用")
        app.swipeDown(); app.swipeDown()
        let clear = app.buttons["storage.clearCache"]
        XCTAssertTrue(clear.waitForExistence(timeout: 10))
        XCTAssertTrue(clear.isEnabled, app.debugDescription)
        clear.tap()
        XCTAssertTrue(app.buttons["清除缓存"].waitForExistence(timeout: 5))
        app.buttons["取消"].tap()
        XCTAssertTrue(app.buttons[identifier].exists)
        clear.tap()
        app.buttons["清除缓存"].tap()
        let cleared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let value = self.app.descendants(matching: .any)["storage.cacheBytes"].firstMatch.value as? String ?? ""
            return value == "0 字节"
        }, object: nil)
        wait(for: [cleared], timeout: 20)
        XCTAssertTrue(app.buttons[identifier].exists)
        attach("清理缓存后离线章节保留")
        app.terminate(); app.launch()
        importFile(source: "LOCAL", file: "fixture_storage_comic", reuse: true)
        app.detailsReadButton().tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        app.buttons["reader.more"].tap()
        XCTAssertTrue(app.staticTexts["本章已下载，可离线阅读"].waitForExistence(timeout: 10))
        app.buttons["reader.panel.close"].tap()
        app.buttons["reader.back"].tap()
        openStorageSettings()
        scrollStorageTo(app.buttons[identifier])
        app.buttons[identifier].tap()
        XCTAssertTrue(app.buttons["删除文件"].waitForExistence(timeout: 5))
        app.buttons["取消"].tap()
        XCTAssertTrue(app.buttons[identifier].exists)
        app.buttons[identifier].tap()
        app.buttons["删除文件"].tap()
        XCTAssertTrue(app.buttons[identifier].waitForNonExistence(timeout: 15), app.debugDescription)
        attach("删除离线章节后存储列表更新")
        app.terminate(); app.launch()
        importFile(source: "LOCAL", file: "fixture_storage_comic", reuse: true)
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 20))
        app.detailsReadButton().tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        app.buttons["reader.more"].tap()
        XCTAssertFalse(app.staticTexts["本章已下载，可离线阅读"].exists)
        app.buttons["reader.panel.close"].tap()
        app.buttons["reader.back"].tap()
    }

    @MainActor
    func testStorageProtectsTheOpenChapterAndPersistsTheCacheLimit() throws {
        importFile(source: "LOCAL", file: "fixture_storage_comic", reuse: true)
        openFirstChapter(title: "fixture_storage_comic")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["下载本章"].tap()
        XCTAssertTrue(app.staticTexts["本章已下载，可离线阅读"].waitForExistence(timeout: 30))
        app.buttons["reader.downloads"].tap()
        XCTAssertTrue(app.buttons["downloads.storage"].waitForExistence(timeout: 15), app.debugDescription)
        app.buttons["downloads.storage"].tap()
        let row = storedChapter(title: "fixture_storage_comic")
        scrollStorageTo(row)
        let delete = row.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "storage.delete.")).firstMatch
        XCTAssertTrue(delete.exists)
        XCTAssertFalse(delete.isEnabled, app.debugDescription)
        app.swipeDown(); app.swipeDown()
        XCTAssertFalse(app.buttons["storage.clearCache"].isEnabled)
        attach("正在阅读的章节和缓存清理保护")
        app.terminate(); app.launch()
        openStorageSettings()
        let picker = app.descendants(matching: .any)["storage.cacheLimit"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        picker.tap()
        app.buttons["256 MB"].tap()
        app.terminate(); app.launch()
        openStorageSettings()
        let persisted = app.descendants(matching: .any)["storage.cacheLimit"].firstMatch
        XCTAssertTrue(persisted.label.contains("256 MB") || (persisted.value as? String)?.contains("256 MB") == true,
                      app.debugDescription)
        attach("缓存容量设置重启恢复")
        persisted.tap()
        app.buttons["512 MB"].tap()
    }

    @MainActor
    func testStorageListsChapterAfterRemovingItsTaskAndRestarting() throws {
        importFile(source: "LOCAL", file: "fixture_storage_tasks", reuse: true)
        openFirstChapter(title: "fixture_storage_tasks")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["下载本章"].tap()
        app.buttons["reader.downloads"].tap()
        let task = app.otherElements.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "download.entry.", "fixture_storage_tasks"
        )).firstMatch
        XCTAssertTrue(task.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertTrue(task.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "已下载"))
            .firstMatch.waitForExistence(timeout: 30), task.debugDescription)
        let remove = task.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "download.remove.")).firstMatch
        XCTAssertTrue(remove.exists, task.debugDescription)
        remove.tap()
        app.terminate(); app.launch()
        importFile(source: "LOCAL", file: "fixture_storage_tasks", reuse: true)
        openFirstChapter(title: "fixture_storage_tasks")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        app.buttons["reader.more"].tap()
        XCTAssertTrue(app.staticTexts["本章已下载，可离线阅读"].waitForExistence(timeout: 10))
        app.buttons["reader.panel.close"].tap()
        app.buttons["reader.back"].tap()
        openStorageSettings()
        let row = storedChapter(title: "fixture_storage_tasks")
        scrollStorageTo(row)
        XCTAssertTrue(row.exists, app.debugDescription)
        attach("移除任务并重启后离线文件仍可管理")
        row.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "storage.delete.")).firstMatch.tap()
        app.buttons["删除文件"].tap()
        XCTAssertTrue(row.waitForNonExistence(timeout: 15))
    }

    private func openStorageSettings() {
        app.tabBars.buttons["设置"].tap()
        let entry = app.buttons["settings.storage"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), app.debugDescription)
        entry.tap()
        XCTAssertTrue(app.descendants(matching: .any)["storage.offlineBytes"].firstMatch.waitForExistence(timeout: 20))
    }

    private func storedChapter(title: String) -> XCUIElement {
        app.otherElements.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "storage.chapter.", title
        )).firstMatch
    }

    private func scrollStorageTo(_ item: XCUIElement) {
        for _ in 0..<10 where !item.exists || !item.isHittable { app.swipeUp() }
        XCTAssertTrue(item.exists, app.debugDescription)
    }

    /// The host may corrupt this fixture's offline copy between tests; the original imported CBZ remains intact.
    @MainActor
    func testDownloadedReaderFixtureReopensAfterImageRepair() throws {
        importFile(source: "LOCAL", file: "fixture_reader_download", reuse: true)
        openFirstChapter(title: "fixture_reader_download")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(app.staticTexts["reader.pageError.0"].exists)
        app.buttons["reader.back"].tap()
        app.terminate()
    }

    @MainActor
    func testContinuousAutoScrollAdvancesToTheNextChapter() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        let first = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"
        )).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 10), app.debugDescription)
        first.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["自动翻页"].tap()
        app.buttons["reader.panel.close"].tap()
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.buttons["reader.chapters"].label.contains("02")
        }, object: nil)
        wait(for: [advanced], timeout: 15)
        XCTAssertTrue(app.images["reader.image"].firstMatch.exists)
        XCTAssertFalse(app.buttons["reader.nextChapter"].isEnabled)
        attach("条漫自动跨章")
    }

    @MainActor
    func testDisplayPreferenceAndBackgroundPreserveTheLongPagePosition() throws {
        importFile(source: "LOCAL", file: "fixture_reader_long", reuse: true)
        openFirstChapter(title: "fixture_reader_long")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        // A swipe while the strip replaces the paged view can land on neither; swipe again until the strip moves.
        for _ in 0..<3 where readingOffset(canvas) <= 300 {
            canvas.swipeUp()
            let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.readingOffset(canvas) > 300 },
                                                  object: nil)
            _ = XCTWaiter.wait(for: [moved], timeout: 5)
        }
        XCTAssertGreaterThan(readingOffset(canvas), 300)
        let saved = readingOffset(canvas)
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["控件"].tap()
        let keepScreenOn = app.switches["reader.option.keepScreenOn"]
        XCTAssertTrue(keepScreenOn.waitForExistence(timeout: 10), app.debugDescription)
        keepScreenOn.tap()
        app.buttons["reader.panel.close"].tap()
        XCTAssertEqual(readingOffset(canvas), saved, accuracy: 5)
        XCUIDevice.shared.press(.home)
        // Allow the background save to complete; terminate checks persistence rather than retained view state.
        Thread.sleep(forTimeInterval: 1)
        app.terminate(); app.launch()
        importFile(source: "LOCAL", file: "fixture_reader_long", reuse: true)
        let resume = app.detailsReadButton()
        XCTAssertTrue(resume.waitForExistence(timeout: 30), app.debugDescription)
        resume.tap()
        XCTAssertTrue(canvas.waitForExistence(timeout: 30), app.debugDescription)
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas) - saved) < 5
        }, object: nil)
        wait(for: [restored], timeout: 15)
        attach("显示设置与后台续读保留长页位置")
    }

    @MainActor
    func testHorizontalContinuousDirectionZoomAndBookmarkRestore() throws {
        importFile(source: "LOCAL", file: "fixture_reader_horizontal", reuse: true)
        openFirstChapter(title: "fixture_reader_horizontal")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("横向条漫（连续）")
        setHorizontalReversed(false)
        // Reused fixtures may already be halfway through page 1; selecting its unchanged slider value is a no-op.
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "2")
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        let canvas = app.scrollViews["reader.canvas"]
        let start = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas)) < 5
        }, object: nil)
        wait(for: [start], timeout: 10)
        canvas.swipeLeft()
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > 300
        }, object: nil)
        wait(for: [moved], timeout: 10)
        let beforeDirection = readingOffset(canvas)
        setHorizontalReversed(true)
        let preserved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas) - beforeDirection) < 5
        }, object: nil)
        wait(for: [preserved], timeout: 10)
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5)).tap()
        let advancedByTap = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > beforeDirection + 100
        }, object: nil)
        wait(for: [advancedByTap], timeout: 10)
        // The page turn hides chrome; keep both pinch contacts inside the reading surface.
        canvas.pinch(withScale: 2, velocity: 1)
        let zoomed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingZoom(canvas) > 120
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [zoomed], timeout: 10), .completed, canvas.debugDescription)
        let top = app.images["reader.image"].firstMatch.frame.minY
        canvas.swipeUp()
        let panned = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.images["reader.image"].firstMatch.frame.minY < top - 100
        }, object: nil)
        wait(for: [panned], timeout: 10)
        let saved = readingOffset(canvas)
        canvas.tap()
        let bookmark = app.buttons["reader.bookmark"]
        if bookmark.value as? String == "已添加" { bookmark.tap() }
        bookmark.tap()
        canvas.swipeRight()
        let farther = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > saved + 100
        }, object: nil)
        wait(for: [farther], timeout: 10)
        app.buttons["reader.chapters"].tap()
        app.segmentedControls.buttons["书签"].tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "第 1 页")).firstMatch.tap()
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas) - saved) < 5
        }, object: nil)
        wait(for: [restored], timeout: 10)
        attach("横向反向缩放与书签恢复")
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["切换方向"].tap()
        app.segmentedControls.buttons["布局"].tap()
        let direction = app.switches["reader.option.horizontalReversed"]
        XCTAssertTrue(direction.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(direction.value as? String, "0")
        app.buttons["reader.panel.close"].tap()
        let keptPosition = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas) - saved) < 5 && self.readingZoom(canvas) > 120
        }, object: nil)
        wait(for: [keptPosition], timeout: 10)
        attach("横向快捷方向保留连续模式与缩放")
    }

    @MainActor
    func testHorizontalReverseAutoScrollAdvancesToTheNextChapter() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        _ = revealChapter(in: app, timeout: 30)
        let first = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "chapter.", "01"
        )).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 10))
        first.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("横向条漫（连续）")
        setHorizontalReversed(true)
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.sliders.firstMatch.adjust(toNormalizedSliderPosition: 1)
        app.buttons["自动翻页"].tap()
        app.buttons["reader.panel.close"].tap()
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.buttons["reader.chapters"].label.contains("02")
        }, object: nil)
        wait(for: [advanced], timeout: 20)
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        attach("横向反向自动跨章")
    }

    private func setHorizontalReversed(_ enabled: Bool) {
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["布局"].tap()
        let toggle = app.switches["reader.option.horizontalReversed"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5), app.debugDescription)
        if (toggle.value as? String == "1") != enabled { toggle.tap() }
        app.buttons["reader.panel.close"].tap()
    }

    private func readingZoom(_ canvas: XCUIElement) -> Int {
        let value = canvas.value as? String ?? ""
        return Int(value.components(separatedBy: "缩放 ").last?.components(separatedBy: "%").first ?? "") ?? -1
    }

    @MainActor
    func testNeighbourFailureDoesNotHideReadablePageAndRetriesTheFailedPage() throws {
        importFile(source: "LOCAL", file: "fixture_reader_error", reuse: true)
        openFirstChapter(title: "fixture_reader_error")
        let image = app.images["reader.image"].firstMatch
        XCTAssertTrue(image.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(image.isHittable)
        XCTAssertFalse(app.staticTexts["reader.error"].exists)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        let retry = app.buttons["reader.pageRetry.1"]
        XCTAssertTrue(retry.waitForExistence(timeout: 30), app.debugDescription)
        retry.tap()
        XCTAssertTrue(retry.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(app.buttons["reader.pageRetry.0"].exists)
        XCTAssertFalse(app.staticTexts["reader.error"].exists)
        attach("页级失败与定向重试")
    }

    @MainActor
    func testLongPageBookmarkRestoresItsOffset() throws {
        importFile(source: "LOCAL", file: "fixture_reader_long", reuse: true)
        openFirstChapter(title: "fixture_reader_long")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30))
        selectReaderMode("条漫")
        let canvas = app.scrollViews["reader.canvas"]
        XCTAssertTrue(canvas.waitForExistence(timeout: 10), app.debugDescription)
        // The reader resumes the history of earlier runs; start from page 1 (an unchanged slider value is a no-op).
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 1)
        app.sliders["reader.progress"].adjust(toNormalizedSliderPosition: 0)
        let start = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.staticTexts["reader.currentPage"].value as? String == "1" && abs(self.readingOffset(canvas)) < 5
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [start], timeout: 10), .completed, "\(canvas.value ?? "")")
        canvas.swipeUp()
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > 300
        }, object: nil)
        wait(for: [moved], timeout: 10)
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, "1")
        let saved = readingOffset(canvas)
        let bookmark = app.buttons["reader.bookmark"]
        // Repeated test runs may already have a bookmark on this page.
        if bookmark.value as? String == "已添加" { bookmark.tap() }
        bookmark.tap()
        canvas.swipeUp()
        let farther = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.readingOffset(canvas) > saved + 200
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [farther], timeout: 10), .completed, "saved \(saved): \(canvas.value ?? "")")
        app.buttons["reader.chapters"].tap()
        app.segmentedControls.buttons["书签"].tap()
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "第 1 页")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription)
        row.tap()
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            abs(self.readingOffset(canvas) - saved) < 5
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 10), .completed, "saved \(saved): \(canvas.value ?? "")")
        attach("长页书签恢复中段")
    }

    private func readingOffset(_ canvas: XCUIElement) -> Int {
        let value = canvas.value as? String ?? ""
        // The offset can contain localized grouping separators; stop before the window size, then read its digits.
        let tail = value.components(separatedBy: "页内偏移 ").dropFirst().first ?? ""
        let number = tail.components(separatedBy: "，").first ?? ""
        return Int(number.filter { $0 == "-" || $0.isNumber }) ?? -1
    }

    private func selectReaderMode(_ mode: String) {
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["布局"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "阅读模式")).firstMatch.tap()
        app.buttons[mode == "默认（从左到右）" ? "从左到右" : mode].tap()
        app.buttons["reader.panel.close"].tap()
    }

    @MainActor
    func testImportedTextNovelSplitsChapters() throws {
        importFile(source: "LOCAL_NOVEL", file: "fixture_novel")
        openFirstChapter(title: "fixture_novel")
        let paragraph = app.staticTexts["novel.firstParagraph"]
        XCTAssertTrue(paragraph.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(paragraph.label.contains("简介"), paragraph.label)
        attach("本地小说阅读")
    }

    /// A page crop saved as a note appears in Settings → Notes with its thought, opens its page, and can be deleted;
    /// a video note keeps its time point and frame.
    @MainActor
    func testCropAndVideoNotesAppearInTheNotesOverview() throws {
        importFile(source: "LOCAL", file: "fixture_reader_text", reuse: true)
        openFirstChapter(title: "fixture_reader_text")
        let page = app.images["reader.image"].firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["reader.action.crop"].tap()
        let note = app.textFields["crop.note"]
        XCTAssertTrue(note.waitForExistence(timeout: 10), app.debugDescription)
        let canvas = app.windows.firstMatch
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.25))
            .press(forDuration: 0.1, thenDragTo: canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.4)))
        note.tap()
        note.typeText("第一格的对白")
        app.buttons["crop.saveNote"].tap()
        XCTAssertTrue(note.waitForNonExistence(timeout: 10), app.debugDescription)
        // The reader hides the tab bar; start the video from the tab root.
        app.terminate()
        app.launch()
        importFile(source: "LOCAL_VIDEO", file: "fixture_video", reuse: true)
        let read = app.detailsReadButton()
        XCTAssertTrue(read.waitForExistence(timeout: 20), app.debugDescription)
        read.tap()
        let playing = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.playedSeconds >= 3 }, object: nil)
        wait(for: [playing], timeout: 30)
        showVideoControls()
        app.buttons["player.note"].tap()
        let videoNote = app.textFields["写下想法（可选）"]
        XCTAssertTrue(videoNote.waitForExistence(timeout: 10), app.debugDescription)
        videoNote.typeText("片头")
        app.buttons["保存"].tap()
        XCTAssertTrue(app.staticTexts["已存为笔记"].waitForExistence(timeout: 15), app.debugDescription)
        app.buttons["好"].tap()
        showVideoControls()
        app.buttons["player.close"].tap()

        app.terminate()
        app.launch()
        app.openNotes()
        let comic = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@",
                                                     "notes.work", "fixture_reader_text")).firstMatch
        XCTAssertTrue(comic.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@",
                                                       "notes.work", "fixture_video")).firstMatch.exists)
        attach("笔记总览")
        comic.tap()
        let item = app.descendants(matching: .any).matching(identifier: "notes.item").firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.staticTexts["第一格的对白"].exists, app.debugDescription)
        attach("作品笔记")
        item.tap()
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["reader.back"].tap()
        for _ in 0..<5 where item.waitForExistence(timeout: 3) {
            item.swipeLeft()
            app.buttons["删除"].tap()
        }
        XCTAssertFalse(item.exists, app.debugDescription)
    }

    /// A replacement rule added in Settings rewrites the novel text; deleting it restores the original.
    @MainActor
    func testReplaceRuleRewritesNovelText() throws {
        app.mainTab("设置").tap()
        let entry = app.buttons["settings.replaceRules"]
        for _ in 0..<6 where !entry.exists { app.swipeUp() }
        XCTAssertTrue(entry.waitForExistence(timeout: 10), app.debugDescription)
        entry.tap()
        app.buttons["replaceRules.add"].tap()
        let pattern = app.textFields["replaceRules.pattern"]
        XCTAssertTrue(pattern.waitForExistence(timeout: 10), app.debugDescription)
        pattern.tap()
        pattern.typeText("晨光")
        let replacement = app.textFields["replaceRules.replacement"]
        replacement.tap()
        replacement.typeText("夕阳")
        app.buttons["replaceRules.save"].tap()
        let row = app.descendants(matching: .any).matching(identifier: "replaceRules.row").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription)
        attach("替换规则")

        importFile(source: "LOCAL_NOVEL", file: "fixture_novel_controls", reuse: true)
        openFirstChapter(title: "fixture_novel_controls")
        let rewritten = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "夕阳落在书页上")).firstMatch
        XCTAssertTrue(rewritten.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "晨光")).firstMatch.exists)
        // Back at the tab root (the details page hides the tab bar) to remove the rule.
        app.terminate()
        app.launch()
        app.mainTab("设置").tap()
        for _ in 0..<6 where !entry.exists { app.swipeUp() }
        entry.tap()
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        // Every rule goes, so no other test reads rewritten text.
        for _ in 0..<5 where row.exists {
            row.swipeLeft()
            app.buttons.matching(NSPredicate(format: "label IN %@", ["删除", "Delete"])).firstMatch.tap()
            Thread.sleep(forTimeInterval: 1)
        }
        XCTAssertTrue(row.waitForNonExistence(timeout: 10), app.debugDescription)
    }

    /// A folder of videos imports as one work with an episode per file.
    @MainActor
    func testVideoFolderImportsAsEpisodes() throws {
        app.mainTab("浏览").tap()
        let space = app.buttons["space.menu"]
        if space.waitForExistence(timeout: 3) {
            space.tap()
            app.buttons["space.select.VIDEO"].tap()
        }
        let field = app.pageSearchField()
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        if let text = field.value as? String, text != field.placeholderValue, !text.isEmpty {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: text.count + 2))
        }
        field.typeText("本地\n")
        let source = app.buttons["source.LOCAL_VIDEO"]
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription)
        source.tap()
        let folderButton = app.buttons["browse.importFolder"]
        XCTAssertTrue(folderButton.waitForExistence(timeout: 10), app.debugDescription)
        folderButton.tap()
        for title in ["我的iPhone", "我的 iPhone", "On My iPhone"] {
            let location = app.cells.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
            if location.waitForExistence(timeout: 5) && location.isHittable { location.tap(); break }
        }
        let folder = app.cells.matching(NSPredicate(format: "label CONTAINS %@", "fixture_show")).firstMatch
        // The picker lists files alphabetically and loads rows as they scroll in.
        for _ in 0..<8 where !(folder.exists && folder.isHittable) {
            (app.collectionViews.firstMatch.exists ? app.collectionViews.firstMatch : app.tables.firstMatch).swipeUp()
        }
        XCTAssertTrue(folder.waitForExistence(timeout: 15), app.debugDescription)
        folder.tap()
        let open = app.buttons.matching(NSPredicate(format: "label IN %@", ["打开", "Open"])).firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 10), app.debugDescription)
        open.tap()
        let done = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "共 2 集")).firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 60), app.debugDescription)
        attach("视频文件夹导入")
    }

    /// A PDF imports as a comic: its pages open in the reader.
    @MainActor
    func testPdfImportsAsAComic() throws {
        importFile(source: "LOCAL", file: "fixture_pdf")
        openFirstChapter(title: "fixture_pdf")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        attach("PDF 导入")
    }

    /// The library filter sheet counts chosen groups on its button and clears them.
    @MainActor
    func testLibraryFilterCountsAndClears() throws {
        app.mainTab("作品库").tap()
        let filter = app.buttons["library.filter"]
        XCTAssertTrue(filter.waitForExistence(timeout: 15), app.debugDescription)
        filter.tap()
        let reading = app.segmentedControls["library.filter.reading"]
        XCTAssertTrue(reading.waitForExistence(timeout: 10), app.debugDescription)
        reading.buttons["读完"].tap()
        app.buttons["library.filter.macro.SFW"].tap()
        attach("作品库筛选")
        app.buttons["完成"].tap()
        XCTAssertTrue(filter.waitForExistence(timeout: 5))
        XCTAssertTrue(filter.label.contains("2"), filter.label)
        filter.tap()
        app.buttons["library.filter.clear"].tap()
        app.buttons["完成"].tap()
        XCTAssertFalse(filter.label.contains("2"), filter.label)
    }

    /// A custom Space made in the switcher appears as a card, becomes the app-wide space, and deleting it returns
    /// to all content.
    @MainActor
    func testCustomSpaceCanBeCreatedSelectedAndDeleted() throws {
        app.terminate()
        app.launchArguments += ["-space.enabled", "YES"]
        app.launch()
        app.mainTab("浏览").tap()
        let menu = app.buttons["space.menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15), app.debugDescription)
        menu.tap()
        let manage = app.buttons["space.manage"]
        for _ in 0..<4 where !manage.isHittable { app.swipeUp() }
        manage.tap()
        let leftover = app.buttons["spaces.custom.测试空间"]
        if leftover.waitForExistence(timeout: 2) { leftover.swipeLeft(); app.buttons["删除"].tap() }
        app.buttons["spaces.add"].tap()
        let title = app.textFields["spaces.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 5), app.debugDescription)
        title.tap(); title.typeText("测试空间")
        app.buttons["spaces.type.MANGA"].tap()
        let native = app.buttons["spaces.kind.NATIVE"]
        for _ in 0..<4 where !(native.exists && native.isHittable) { app.swipeUp() }
        native.tap()
        app.buttons["spaces.save"].tap()
        let row = app.buttons["spaces.custom.测试空间"]
        XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let card = app.buttons["space.select.CUSTOM.测试空间"]
        for _ in 0..<4 where !card.isHittable { app.swipeUp() }
        XCTAssertTrue(card.waitForExistence(timeout: 10), app.debugDescription)
        card.tap()
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        XCTAssertTrue(menu.label.contains("测试空间"), menu.label)
        attach("自定义空间")

        menu.tap()
        for _ in 0..<4 where !manage.isHittable { app.swipeUp() }
        manage.tap()
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.swipeLeft()
        app.buttons["删除"].tap()
        XCTAssertTrue(row.waitForNonExistence(timeout: 10), app.debugDescription)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["space.close"].tap()
        XCTAssertTrue(menu.label.contains("全部"), menu.label)
    }

    /// The estimated time left shows for a comic and not for a novel, whose "pages" are lines.
    @MainActor
    func testTimeLeftShowsForComicsOnly() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30), app.debugDescription)
        let left = app.staticTexts["details.timeLeft"]
        XCTAssertTrue(left.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertTrue(left.label.hasPrefix("剩余约"), left.label)
        attach("剩余时间")
        app.terminate()
        app.launch()
        importFile(source: "LOCAL_NOVEL", file: "fixture_novel_controls", reuse: true)
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(left.waitForExistence(timeout: 5), "Novels get no page-based estimate")
    }

    /// "划线当前段" highlights the paragraph being read, it shows in the work's notes, and removing it works.
    @MainActor
    func testNovelParagraphHighlightAppearsInNotes() throws {
        openNovelControls(mode: "SCROLL")
        app.buttons["novel.more"].tap()
        let mark = app.buttons["novel.markParagraph"]
        XCTAssertTrue(mark.waitForExistence(timeout: 10), app.debugDescription)
        let wasMarked = mark.label.contains("取消")
        if wasMarked { mark.tap(); XCTAssertTrue(app.buttons["划线当前段"].waitForExistence(timeout: 10)) }
        app.buttons["novel.markParagraph"].tap()
        XCTAssertTrue(app.buttons["取消本段划线"].waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["novel.panel.close"].tap()
        attach("小说划线")
        app.buttons["novel.back"].tap()

        app.terminate()
        app.launch()
        app.openNotes()
        let work = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@",
                                                    "notes.work", "fixture_novel_controls")).firstMatch
        XCTAssertTrue(work.waitForExistence(timeout: 10), app.debugDescription)
        work.tap()
        let marking = app.descendants(matching: .any).matching(identifier: "notes.marking").firstMatch
        XCTAssertTrue(marking.waitForExistence(timeout: 10), app.debugDescription)
        attach("笔记中的划线")
        for _ in 0..<5 where marking.waitForExistence(timeout: 2) {
            marking.swipeLeft()
            app.buttons["删除"].tap()
        }
        XCTAssertFalse(marking.exists, app.debugDescription)
    }

    /// Tapping a highlight selects it and offers its color, shape, note and removal, in both reading modes.
    @MainActor
    func testNovelHighlightTapOffersItsActions() throws {
        for mode in ["SCROLL", "PAGED"] {
            openNovelControls(mode: mode)
            app.buttons["novel.more"].tap()
            let mark = app.buttons["novel.markParagraph"]
            XCTAssertTrue(mark.waitForExistence(timeout: 10), app.debugDescription)
            if mark.label.contains("取消") { mark.tap(); XCTAssertTrue(app.buttons["划线当前段"].waitForExistence(timeout: 10)) }
            app.buttons["novel.markParagraph"].tap()
            XCTAssertTrue(app.buttons["取消本段划线"].waitForExistence(timeout: 10), app.debugDescription)
            app.buttons["novel.panel.close"].tap()

            let paragraph = app.staticTexts["novel.firstParagraph"]
            // The third line: the first may lie under the floating chapter title.
            let firstLine = paragraph.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0)).withOffset(CGVector(dx: 0, dy: 65))
            openHighlightMenu(firstLine)
            XCTAssertTrue(menuItem("添加批注").waitForExistence(timeout: 10), "\(mode): \(app.debugDescription)")
            attach("划线菜单 \(mode)")
            XCTAssertTrue(revealMenuItem("删除划线").exists, app.debugDescription)
            openHighlightMenu(firstLine)
            menuItem("样式").tap()
            XCTAssertTrue(menuItem("波浪线").waitForExistence(timeout: 5), app.debugDescription)
            menuItem("波浪线").tap()
            openHighlightMenu(firstLine)
            XCTAssertTrue(menuItem("颜色").waitForExistence(timeout: 10), app.debugDescription)
            menuItem("颜色").tap()
            XCTAssertTrue(menuItem("雅致蓝").waitForExistence(timeout: 5), app.debugDescription)
            menuItem("雅致蓝").tap()
            attach("蓝色波浪线 \(mode)")

            openHighlightMenu(firstLine)
            XCTAssertTrue(menuItem("添加批注").waitForExistence(timeout: 10), app.debugDescription)
            menuItem("添加批注").tap()
            let field = app.textFields["novel.marking.note.field"].exists
                ? app.textFields["novel.marking.note.field"] : app.textViews["novel.marking.note.field"]
            XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
            field.typeText("note \(mode)")
            app.buttons["novel.marking.note.save"].tap()

            openHighlightMenu(firstLine)
            XCTAssertTrue(menuItem("编辑批注").waitForExistence(timeout: 10), "The note is kept: \(app.debugDescription)")
            revealMenuItem("删除划线").tap()
            app.buttons["novel.more"].tap()
            XCTAssertTrue(app.buttons["划线当前段"].waitForExistence(timeout: 10), "The highlight is gone: \(app.debugDescription)")
            app.buttons["novel.panel.close"].tap()
            // A tap in the middle of plain text still toggles the reading controls.
            let center = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            center.tap()
            XCTAssertTrue(app.buttons["novel.back"].waitForNonExistence(timeout: 5), "\(mode): \(app.debugDescription)")
            center.tap()
            XCTAssertTrue(app.buttons["novel.back"].waitForExistence(timeout: 5), app.debugDescription)
            app.buttons["novel.back"].tap()
        }
    }

    /// Taps a highlight until its menu shows (a tap while another menu is open only closes that menu).
    private func openHighlightMenu(_ point: XCUICoordinate) {
        for _ in 0..<3 {
            point.tap()
            if menuItem("颜色").waitForExistence(timeout: 3) { return }
        }
    }

    /// An edit menu entry, paging the menu forward until it shows.
    private func revealMenuItem(_ title: String) -> XCUIElement {
        let item = menuItem(title)
        for _ in 0..<4 where !(item.exists && item.isHittable) {
            let next = app.buttons["下一页"]
            guard next.exists else { break }
            next.tap()
            _ = item.waitForExistence(timeout: 1)
        }
        return item
    }

    /// An edit menu entry: a menu item or, in the compact edit menu, a button.
    private func menuItem(_ title: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(
            format: "label == %@ AND (elementType == %d OR elementType == %d)", title,
            XCUIElement.ElementType.menuItem.rawValue, XCUIElement.ElementType.button.rawValue)).firstMatch
    }

    /// Searching the chapter lists matching lines and jumps to the chosen one.
    @MainActor
    func testNovelChapterSearchJumpsToTheMatch() throws {
        openNovelControls(mode: "PAGED")
        app.buttons["novel.more"].tap()
        let search = app.buttons["novel.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 10), app.debugDescription)
        search.tap()
        let field = app.searchFields["搜索本章正文"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        field.tap()
        field.typeText("第30段")
        let result = app.buttons.matching(identifier: "novel.search.result").firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(app.buttons.matching(identifier: "novel.search.result").count, 1)
        result.tap()
        let page = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "第30段")).firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 10), app.debugDescription)
        attach("本章搜索跳转")
    }

    /// Reading aloud shows its bar, the reader follows the spoken line, and stopping removes the bar.
    @MainActor
    func testNovelReadAloudFollowsTheSpokenLine() throws {
        // Independent of where an earlier test left the chapter: only the change of position counts.
        app.terminate()
        app.launchArguments += ["-novel.reader.mode", "SCROLL"]
        app.launch()
        importFile(source: "LOCAL_NOVEL", file: "fixture_novel_controls", reuse: true)
        openFirstChapter(title: "fixture_novel_controls")
        let progress = app.sliders["novel.progress"]
        wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in progress.exists && progress.isEnabled },
                                             object: nil)], timeout: 30)
        progress.adjust(toNormalizedSliderPosition: 0.2)
        Thread.sleep(forTimeInterval: 1)
        let start = progress.value as? String
        app.buttons["novel.more"].tap()
        let speak = app.buttons["novel.speak"]
        XCTAssertTrue(speak.waitForExistence(timeout: 10), app.debugDescription)
        speak.tap()
        let bar = app.descendants(matching: .any)["novel.speechBar"]
        XCTAssertTrue(bar.waitForExistence(timeout: 10), app.debugDescription)
        let next = app.buttons["novel.speech.next"]
        next.tap()
        next.tap()
        next.tap()
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (progress.value as? String).map { $0 != start } ?? false
        }, object: nil)
        wait(for: [moved], timeout: 15)
        attach("小说朗读")
        app.buttons["novel.speech.stop"].tap()
        XCTAssertTrue(bar.waitForNonExistence(timeout: 5), app.debugDescription)
    }

    /// Page translation recognizes the bubbles with Vision and paints the translation over them; the fixture
    /// translator stands in for the system one, which the simulator does not have.
    @MainActor
    func testMangaPageTranslationRecognizesAndReplacesBubbles() throws {
        app.launchEnvironment["KOTOTORO_TRANSLATION_FIXTURE"] = "1"
        app.launchArguments += ["-novel.translation.target", "zh"]
        app.terminate()
        app.launch()
        importFile(source: "LOCAL", file: "fixture_reader_text", reuse: true)
        openFirstChapter(title: "fixture_reader_text")
        let page = app.images["reader.image"].firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 30), app.debugDescription)
        attach("页面翻译前")
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        let translate = app.buttons["reader.action.translate"]
        XCTAssertTrue(translate.waitForExistence(timeout: 10), app.debugDescription)
        translate.tap()
        let translated = app.images.matching(NSPredicate(format: "label CONTAINS %@", "[zh] Where are you going")).firstMatch
        XCTAssertTrue(translated.waitForExistence(timeout: 60), app.debugDescription)
        XCTAssertTrue(translated.label.contains("[zh] The train leaves"), translated.label)
        attach("页面翻译后")
    }

    /// The translation tab of the options panel tunes recognition; a changed option translates the pages again
    /// with it, and the defaults come back with one button.
    @MainActor
    func testMangaPageTranslationOptionsRetranslatePages() throws {
        app.launchEnvironment["KOTOTORO_TRANSLATION_FIXTURE"] = "1"
        app.launchArguments += ["-novel.translation.target", "zh", "-manga.translation.language", "en-US"]
        app.terminate()
        app.launch()
        importFile(source: "LOCAL", file: "fixture_reader_text", reuse: true)
        openFirstChapter(title: "fixture_reader_text")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["快捷"].tap()
        app.buttons["reader.action.translate"].tap()
        let translated = app.images.matching(NSPredicate(format: "label CONTAINS %@", "[zh] Where are you going")).firstMatch
        XCTAssertTrue(translated.waitForExistence(timeout: 60), app.debugDescription)

        if !app.segmentedControls.buttons["翻译"].exists { app.buttons["reader.more"].tap() }
        app.segmentedControls.buttons["翻译"].tap()
        let language = app.buttons["reader.translation.language"]
        XCTAssertTrue(language.waitForExistence(timeout: 10), app.debugDescription)
        // The stored "en-US" matches the picker's English entry, so the chosen language shows.
        XCTAssertTrue(language.label.contains("英语"), language.label)
        XCTAssertTrue(app.sliders["reader.translation.confidence"].exists)
        XCTAssertTrue(app.sliders["reader.translation.spacing"].exists)
        let correction = app.switches["reader.translation.correction"]
        // Start from the defaults whatever an earlier run left behind.
        app.buttons["reader.translation.reset"].tap()
        XCTAssertEqual(correction.value as? String, "1")
        correction.switches.firstMatch.tap()
        XCTAssertEqual(correction.value as? String, "0")
        attach("翻译选项")
        // The original shows while the page is recognized again, then the new translation replaces it.
        XCTAssertTrue(translated.waitForExistence(timeout: 60), app.debugDescription)

        app.buttons["reader.translation.reset"].tap()
        XCTAssertEqual(correction.value as? String, "1")
        XCTAssertTrue(translated.waitForExistence(timeout: 60), app.debugDescription)
    }

    /// Incognito reading says so and leaves the saved position alone; normal reading saves it again.
    @MainActor
    func testIncognitoReadingKeepsTheSavedPosition() throws {
        openNovelControls(mode: "SCROLL")
        app.buttons["novel.back"].tap()

        app.terminate()
        app.launchArguments += ["-incognito", "YES"]
        app.launch()
        importFile(source: "LOCAL_NOVEL", file: "fixture_novel_controls", reuse: true)
        openFirstChapter(title: "fixture_novel_controls")
        XCTAssertTrue(app.descendants(matching: .any)["reader.incognitoNotice"].waitForExistence(timeout: 10),
                      app.debugDescription)
        let progress = app.sliders["novel.progress"]
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in progress.exists && progress.isEnabled },
                                              object: nil)
        wait(for: [ready], timeout: 30)
        progress.adjust(toNormalizedSliderPosition: 0.65)
        Thread.sleep(forTimeInterval: 1.5)
        attach("无痕阅读")
        app.buttons["novel.back"].tap()
        openFirstChapter(title: "fixture_novel_controls")
        XCTAssertTrue(progress.waitForExistence(timeout: 30))
        wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in progress.isEnabled }, object: nil)],
             timeout: 30)
        XCTAssertEqual(progress.value as? String, "0%", "Incognito reading must not move the saved position")
    }

    /// Translated lines replace or (bilingual) follow the original on the pages; the original returns on demand.
    /// The simulator has no system translator, so the fixture translator tags each line.
    @MainActor
    func testNovelTranslationLaysOutBilingualPages() throws {
        app.launchEnvironment["KOTOTORO_TRANSLATION_FIXTURE"] = "1"
        app.launchArguments += ["-novel.translation.target", "en", "-novel.translation.bilingual", "YES"]
        openNovelControls(mode: "PAGED")
        let page = app.staticTexts["novel.firstParagraph"]
        XCTAssertFalse(page.label.contains("[en]"), page.label)
        app.buttons["novel.more"].tap()
        let translate = app.buttons["novel.translate"]
        XCTAssertTrue(translate.waitForExistence(timeout: 10), app.debugDescription)
        translate.tap()
        XCTAssertTrue(translate.label.contains("原文"), app.debugDescription)
        app.buttons["novel.panel.close"].tap()
        let translated = NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "[en] 第1段", "　　第1段")
        XCTAssertTrue(app.staticTexts.matching(translated).firstMatch.waitForExistence(timeout: 10), app.debugDescription)
        attach("小说双语翻译分页")
        app.buttons["novel.more"].tap()
        app.buttons["novel.translate"].tap()
        app.buttons["novel.panel.close"].tap()
        let restored = NSPredicate { _, _ in !page.label.contains("[en]") && page.label.contains("第1段") }
        wait(for: [XCTNSPredicateExpectation(predicate: restored, object: nil)], timeout: 10)
    }

    /// Settings → Translation holds the shared target language, the novels' options and the manga page options; the
    /// original language chosen there is the one novels translate from.
    @MainActor
    func testTranslationSettingsPageSetsTheNovelSourceLanguage() throws {
        app.launchEnvironment["KOTOTORO_TRANSLATION_FIXTURE"] = "1"
        app.launchArguments += ["-novel.translation.target", "en", "-novel.translation.source", "ja",
                                "-novel.translation.bilingual", "NO"]
        app.terminate()
        app.launch()
        app.openSettings("settings.translation")
        let target = app.buttons["settings.translation.target"]
        XCTAssertTrue(target.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(target.label.contains("英语"), target.label)
        let source = app.buttons["novel.settings.translationSource"]
        XCTAssertTrue(source.label.contains("日语"), source.label)
        XCTAssertTrue(app.buttons["reader.translation.language"].exists, app.debugDescription)
        XCTAssertTrue(app.switches["reader.translation.correction"].exists, app.debugDescription)
        attach("翻译设置页")
        // The recognition sliders sit one level down.
        let tuning = app.buttons["settings.translation.tuning"]
        for _ in 0..<4 where !(tuning.exists && tuning.isHittable) { app.swipeUp() }
        tuning.tap()
        XCTAssertTrue(app.sliders["reader.translation.confidence"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.sliders["reader.translation.spacing"].exists, app.debugDescription)
        XCTAssertTrue(app.buttons["reader.translation.reset"].exists, app.debugDescription)
        attach("识别调整")

        openNovelControls(mode: "PAGED")
        app.buttons["novel.more"].tap()
        let translate = app.buttons["novel.translate"]
        XCTAssertTrue(translate.waitForExistence(timeout: 10), app.debugDescription)
        translate.tap()
        app.buttons["novel.panel.close"].tap()
        let translated = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "[ja→en] ")).firstMatch
        XCTAssertTrue(translated.waitForExistence(timeout: 10), app.debugDescription)
    }

    @MainActor
    func testNovelFloatingActionsPersistAndOperateFromTheMorePanel() throws {
        openNovelControls(mode: "SCROLL")
        XCTAssertTrue(app.buttons["novel.back"].exists)
        XCTAssertTrue(app.buttons["novel.chapters"].exists)
        XCTAssertTrue(app.buttons["novel.more"].exists)
        XCTAssertFalse(app.buttons["novel.typography"].exists)
        app.sliders["novel.progress"].adjust(toNormalizedSliderPosition: 0.65)
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let value = self.app.sliders["novel.progress"].value as? String ?? ""
            guard let percent = Double(value.replacingOccurrences(of: "%", with: "")) else { return false }
            return percent > 0 && percent <= 100
        }, object: nil)
        wait(for: [advanced], timeout: 10)
        app.buttons["novel.more"].tap()
        app.segmentedControls.buttons["控件"].tap()
        setNovelFloating("bookmark", enabled: true)
        setNovelFloating("chapters", enabled: true)
        setNovelFloating("typography", enabled: true)
        setNovelFloating("downloads", enabled: true)
        attach("小说悬浮按钮配置")
        app.buttons["novel.panel.close"].tap()
        XCTAssertTrue(app.buttons["novel.floating.bookmark"].exists)
        XCTAssertTrue(app.buttons["novel.floating.chapters"].exists)
        XCTAssertTrue(app.buttons["novel.floating.typography"].exists)
        XCTAssertTrue(app.buttons["novel.floating.downloads"].exists)
        let bookmark = app.buttons["novel.floating.bookmark"]
        let before = bookmark.value as? String
        bookmark.tap()
        let toggled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            bookmark.value as? String != before
        }, object: nil)
        wait(for: [toggled], timeout: 10)
        attach("小说底部进度与悬浮按钮")
        app.buttons["novel.floating.chapters"].tap()
        XCTAssertTrue(app.navigationBars["目录与书签"].waitForExistence(timeout: 10))
        app.buttons["关闭"].firstMatch.tap()
        app.buttons["novel.floating.typography"].tap()
        XCTAssertTrue(app.navigationBars["阅读设置"].waitForExistence(timeout: 10))
        app.buttons["完成"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["阅读设置"].waitForNonExistence(timeout: 10))
        app.buttons["novel.floating.downloads"].tap()
        attach("小说悬浮按钮打开下载队列")
        XCTAssertTrue(app.navigationBars["下载"].waitForExistence(timeout: 15), app.debugDescription)
        let storage = app.buttons["downloads.storage"]
        for _ in 0..<4 where !storage.exists { app.swipeUp() }
        XCTAssertTrue(storage.exists, app.debugDescription)
        app.buttons["downloads.close"].tap()
        app.buttons["novel.nextChapter"].tap()
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.buttons["novel.chapters"].label.replacingOccurrences(of: " ", with: "").contains("第2章")
        }, object: nil)
        wait(for: [changed], timeout: 15)
        app.buttons["novel.back"].tap()
        app.terminate(); app.launch()
        importFile(source: "LOCAL_NOVEL", file: "fixture_novel_controls", reuse: true)
        openFirstChapter(title: "fixture_novel_controls")
        XCTAssertTrue(app.buttons["novel.floating.typography"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["novel.floating.chapters"].exists)
        XCTAssertTrue(app.buttons["novel.floating.downloads"].exists)
        app.buttons["novel.more"].tap()
        app.segmentedControls.buttons["控件"].tap()
        for action in ["bookmark", "chapters", "typography", "downloads"] { setNovelFloating(action, enabled: false) }
        app.buttons["novel.panel.close"].tap()
        XCTAssertFalse(app.buttons["novel.floating.bookmark"].exists)
        XCTAssertFalse(app.buttons["novel.floating.chapters"].exists)
        XCTAssertFalse(app.buttons["novel.floating.typography"].exists)
        attach("小说悬浮按钮可全部关闭")
        app.buttons["novel.more"].tap()
        app.segmentedControls.buttons["控件"].tap()
        setNovelFloating("bookmark", enabled: true)
        app.buttons["novel.panel.close"].tap()
    }

    @MainActor
    func testNovelPagedChromeHidesWithoutChangingTheDisplayedText() throws {
        openNovelControls(mode: "PAGED")
        let text = app.staticTexts["novel.firstParagraph"].label
        attach("小说分页控件")
        let center = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        center.tap()
        XCTAssertTrue(app.buttons["novel.more"].waitForNonExistence(timeout: 10))
        XCTAssertFalse(app.sliders["novel.progress"].exists)
        XCTAssertFalse(app.buttons["novel.floating.bookmark"].exists)
        XCTAssertEqual(app.staticTexts["novel.firstParagraph"].label, text)
        attach("小说分页正文沉浸阅读")
        center.tap()
        XCTAssertTrue(app.buttons["novel.more"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["novel.firstParagraph"].label, text)
        app.buttons["novel.more"].tap()
        app.buttons["novel.typography"].tap()
        XCTAssertTrue(app.navigationBars["阅读设置"].waitForExistence(timeout: 10))
        app.buttons["完成"].firstMatch.tap()
        app.buttons["novel.back"].tap()
    }

    @MainActor
    func testNovelPagedTextMarginsStartAtCutoutAndFooter() throws {
        for margin: CGFloat in [60, 12] {
            app.launchArguments = ["-novel.reader.marginVertical", String(Int(margin))]
            openNovelControls(mode: "PAGED")
            let text = app.staticTexts["novel.firstParagraph"]
            let bounds = text.frame
            let footer = app.descendants(matching: .any)["novel.footer"].firstMatch
            XCTAssertTrue(footer.exists)
            XCTAssertEqual(footer.frame.minY - bounds.maxY, margin, accuracy: 1, app.debugDescription)
            // XCTest does not expose the system status-bar element on this simulator. The top controls are inset
            // 12 points from the same safe-area edge; use their measured bounds, not a hard-coded notch size.
            let safeTop = app.buttons["novel.back"].frame.minY - 12
            XCTAssertEqual(bounds.minY - safeTop, margin, accuracy: 1,
                           "The top margin excludes the sensor/status-bar safe area")
            XCTAssertGreaterThanOrEqual(footer.frame.minY, bounds.maxY - 1)
            XCTAssertLessThanOrEqual(footer.frame.maxY, app.frame.maxY + 1)
            attach("小说正文上下\(Int(margin))点边距与页脚位置")
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            XCTAssertTrue(app.buttons["novel.more"].waitForNonExistence(timeout: 10))
            XCTAssertEqual(text.frame.minY, bounds.minY, accuracy: 1)
            XCTAssertEqual(text.frame.maxY, bounds.maxY, accuracy: 1)
            XCTAssertLessThan(firstTextInkOffset(text), 40, "TextKit must not add a second top safe-area inset")
            attach("小说\(Int(margin))点边距的沉浸正文")
        }
    }

    @MainActor
    private func firstTextInkOffset(_ text: XCUIElement) -> CGFloat {
        guard let image = text.screenshot().image.cgImage else { return .infinity }
        let width = image.width
        let height = image.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return .infinity }
        let scale = CGFloat(width) / text.frame.width
        for y in 0..<height {
            var ink = 0
            for x in 0..<width {
                let offset = (y * width + x) * 4
                let contrast = (0..<3).reduce(0) { $0 + abs(Int(pixels[offset + $1]) - Int(pixels[$1])) }
                if contrast > 120 { ink += 1 }
            }
            if ink > 5 { return CGFloat(y) / scale }
        }
        return .infinity
    }

    @MainActor
    func testContinueReadingDetailsDismissesWithEdgeSwipe() throws {
        openNovelControls(mode: "PAGED")
        app.buttons["novel.back"].tap()
        for _ in 0..<4 where !app.mainTab("作品库").exists {
            app.navigationBars.buttons["BackButton"].firstMatch.tap()
        }
        app.mainTab("作品库").tap()
        let accessory = app.buttons["continueReading"]
        XCTAssertTrue(accessory.waitForExistence(timeout: 10), app.debugDescription)
        accessory.tap()
        XCTAssertTrue(app.buttons["novel.back"].waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["novel.back"].tap()
        let close = app.buttons["continueReading.close"]
        XCTAssertTrue(close.waitForExistence(timeout: 10), app.debugDescription)
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.005, dy: 0.5)).press(forDuration: 0.1,
            thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)))
        XCTAssertTrue(close.waitForNonExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(accessory.waitForExistence(timeout: 10), app.debugDescription)
    }

    private func openNovelControls(mode: String) {
        app.terminate()
        app.launchArguments += ["-novel.reader.mode", mode]
        app.launch()
        importFile(source: "LOCAL_NOVEL", file: "fixture_novel_controls", reuse: true)
        openFirstChapter(title: "fixture_novel_controls")
        let progress = app.sliders["novel.progress"]
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            progress.exists && progress.isEnabled
        }, object: nil)
        wait(for: [ready], timeout: 30)
        progress.adjust(toNormalizedSliderPosition: 0)
        XCTAssertTrue(app.staticTexts["novel.firstParagraph"].waitForExistence(timeout: 30), app.debugDescription)
    }

    private func setNovelFloating(_ action: String, enabled: Bool) {
        let toggle = app.switches["novel.floating.option.\(action)"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        if (toggle.value as? String == "1") != enabled { toggle.tap() }
    }

    @MainActor
    func testImportedVideoPlaysAndReimportAfterRestartKeepsItsPosition() throws {
        importFile(source: "LOCAL_VIDEO", file: "fixture_video")
        openFirstChapter(title: "fixture_video")
        XCTAssertTrue(app.staticTexts["player.position"].waitForExistence(timeout: 30), app.debugDescription)
        let playing = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.playedSeconds >= 1 }, object: nil)
        wait(for: [playing], timeout: 20)
        let progress = app.sliders["player.scrubber"]
        showVideoControls()
        XCTAssertTrue(progress.waitForExistence(timeout: 10), app.debugDescription)
        progress.adjust(toNormalizedSliderPosition: 0.35)
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.playedSeconds >= 10
        }, object: nil)
        wait(for: [advanced], timeout: 15)
        XCTAssertFalse(app.staticTexts["player.error"].exists)
        let saved = playedSeconds
        attach("本地视频实际播放进度")
        showVideoControls()
        app.buttons["player.close"].tap()
        XCTAssertTrue(app.detailsReadButton().waitForExistence(timeout: 15), app.debugDescription)
        app.terminate(); app.launch()
        // Import again through the picker, proving that duplicate imports keep the original work/history identity.
        importFile(source: "LOCAL_VIDEO", file: "fixture_video")
        let resume = app.detailsReadButton()
        XCTAssertTrue(resume.waitForExistence(timeout: 15), app.debugDescription)
        resume.tap()
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.playedSeconds >= saved && self.playedSeconds < saved + 8
        }, object: nil)
        wait(for: [restored], timeout: 15)
        XCTAssertFalse(app.staticTexts["player.error"].exists)
        attach("本地视频重启与重复导入续播")
        showVideoControls()
        app.buttons["player.close"].tap()
    }

    @MainActor
    func testBrokenVideoIsRejectedBeforeItEntersTheLocalLibrary() throws {
        importFile(source: "LOCAL_VIDEO", file: "fixture_broken_video", expectImport: false)
        XCTAssertTrue(app.alerts.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "系统播放器无法读取此视频"))
            .firstMatch.waitForExistence(timeout: 15), app.debugDescription)
        attach("损坏视频导入被拒绝")
        app.alerts.buttons["好"].tap()
        XCTAssertFalse(app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "source.content.", "fixture_broken_video"
        )).firstMatch.exists)
    }

    private var playedSeconds: Int {
        Int(app.staticTexts["player.position"].label.replacingOccurrences(of: "已播放 ", with: "")
            .replacingOccurrences(of: " 秒", with: "")) ?? -1
    }

    private func showVideoControls() {
        if !app.buttons["player.close"].isHittable {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)).tap()
        }
    }

    /// Closes a paused player: paused playback keeps the controls on screen, so the tap below can land.
    private func closePlayer(file: StaticString = #filePath, line: UInt = #line) {
        showVideoControls()
        let close = app.buttons["player.close"]
        guard close.waitForExistence(timeout: 3) else {
            XCTFail("The player's close button must stay reachable", file: file, line: line)
            return
        }
        close.tap()
    }

    private func importFile(source name: String, file: String, reuse: Bool = false, expectImport: Bool = true) {
        // The tab bar may be collapsed after scrolling; `mainTab` expands it first.
        app.mainTab("浏览").tap()
        let kind = name == "LOCAL_NOVEL" ? "NOVEL" : name == "LOCAL_VIDEO" ? "VIDEO" : "MANGA"
        if app.navigationBars.buttons["关闭"].exists { app.navigationBars.buttons["关闭"].tap() }
        let space = app.buttons["space.menu"]
        if space.waitForExistence(timeout: 3) {
            space.tap()
            let choice = app.buttons["space.select.\(kind)"]
            XCTAssertTrue(choice.waitForExistence(timeout: 5))
            choice.tap()
        } else if app.segmentedControls["contentKind"].exists {
            app.segmentedControls["contentKind"].buttons[kind == "NOVEL" ? "小说" : kind == "VIDEO" ? "视频" : "漫画"].tap()
        }
        let field = app.pageSearchField()
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        if let text = field.value as? String, text != field.placeholderValue, !text.isEmpty {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: text.count + 2))
        }
        field.typeText("本地\n")
        let source = app.buttons["source.\(name)"]
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription)
        source.tap()
        // Match a complete fixture name: fixture_novel must not select fixture_novel_controls.
        let fileLabel = NSRegularExpression.escapedPattern(for: file)
            + #"(?:\.[^ ,，、\n]+)?(?:[ ,，、\n][\s\S]*)?"#
        let item = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label MATCHES %@", "source.content.", "(?:已下载、)?" + fileLabel)).firstMatch
        // Reader regressions may reuse their immutable fixtures; import tests still exercise the picker every run.
        if reuse && revealLocalItem(item, timeout: 3) { item.tap(); return }
        let add = app.buttons["browse.importLocal"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), app.debugDescription)
        add.tap()
        // Wait for the remote document UI; the underlying app has a Browse tab too.
        let pickerReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.app.collectionViews.firstMatch.exists || self.app.tables.firstMatch.exists
                || self.app.staticTexts["最近项目"].exists || self.app.navigationBars["我的iPhone"].exists
        }, object: nil)
        wait(for: [pickerReady], timeout: 30)
        attach("文件选择器打开后")
        let browseTabs = app.tabBars.buttons.matching(NSPredicate(format: "label == %@", "浏览")).allElementsBoundByIndex
        if let browse = browseTabs.last(where: { $0.isHittable }) { browse.tap() }
        for title in ["我的iPhone", "我的 iPhone", "On My iPhone"] {
            let location = app.cells.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
            if location.exists && location.isHittable { location.tap(); break }
        }
        let pickerList: XCUIElement
        if app.collectionViews.firstMatch.exists { pickerList = app.collectionViews.firstMatch }
        else if app.tables.firstMatch.exists { pickerList = app.tables.firstMatch }
        else {
            let visibleList = app.scrollViews.allElementsBoundByIndex.first {
                $0.isHittable && $0.frame.height > 200 && $0.frame.minY > 100
                    && $0.frame.maxY < app.frame.maxY - 60
            }
            XCTAssertNotNil(visibleList, app.debugDescription)
            pickerList = visibleList ?? app.scrollViews.firstMatch
        }
        // A previously imported work remains in the app beneath the picker and can have the same label.
        // Restrict selection to the document provider, so reimport tests actually choose the requested file.
        let picked = pickerList.cells.matching(NSPredicate(format: "label MATCHES %@", fileLabel)).firstMatch
        if !picked.waitForExistence(timeout: 3) {
            attach("新夹具所在文件目录")
            for _ in 0..<6 {
                pickerList.swipeUp()
                if picked.exists { break }
            }
            if !picked.exists {
                for _ in 0..<12 {
                    pickerList.swipeDown()
                    if picked.exists { break }
                }
            }
        }
        XCTAssertTrue(picked.waitForExistence(timeout: 3), "Stage the fixtures first: \(app.debugDescription)")
        for _ in 0..<6 where picked.frame.midY > app.frame.maxY - 90 || picked.frame.midY < 110 {
            if picked.frame.midY > app.frame.maxY - 90 { pickerList.swipeUp() } else { pickerList.swipeDown() }
        }
        // The document provider may retain an earlier selection. Use the selectable cell, rather than its text
        // preview, and keep an already selected requested file selected.
        for cell in pickerList.cells.allElementsBoundByIndex where cell.isSelected && cell.label != picked.label {
            if cell.isHittable { cell.tap() }
        }
        // A tap lands on a cell the grid just scrolled only sometimes; ask again before failing the import.
        var opened = false
        for _ in 0..<2 where !opened {
            if !picked.isSelected { picked.tap() }
            // Multiple selection keeps the picker open until 打开 is pressed.
            for label in ["打开", "Open"] where app.buttons[label].waitForExistence(timeout: 3) {
                let open = app.buttons[label]
                XCTAssertTrue(open.isEnabled, app.debugDescription)
                open.tap()
                opened = true
                break
            }
        }
        XCTAssertTrue(pickerList.waitForNonExistence(timeout: 15), app.debugDescription)
        if !expectImport { return }
        XCTAssertTrue(revealLocalItem(item, timeout: 30), app.debugDescription)
        attach("\(name) 导入后")
        item.tap()
    }

    /// The local list is newest first and lazy: an older work (a fixture imported on an earlier day, or a reimport
    /// the library recognised as that work) is only in the hierarchy once the list is scrolled to it.
    private func revealLocalItem(_ item: XCUIElement, timeout: TimeInterval) -> Bool {
        if item.waitForExistence(timeout: timeout) { return true }
        let list = app.scrollViews.firstMatch
        for _ in 0..<10 {
            list.swipeUp()
            if item.waitForExistence(timeout: 1) { return true }
        }
        return false
    }

    private func openFirstChapter(title: String) {
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30), app.debugDescription)
        let chapter = revealChapter(in: app, timeout: 30)
        XCTAssertTrue(chapter.exists, app.debugDescription)
        chapter.tap()
    }

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
