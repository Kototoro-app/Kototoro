import XCTest
import UIKit

/// Local works through the document picker: `Scripts/stage-picker-fixtures.py` places a two-page CBZ and a GB18030
/// TXT novel and H.264/AAC video in "On My iPhone"; imports open in the readers or native player.
final class LocalImportTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    /// A collapsed panel stays in the accessibility tree but is moved below the screen.
    @MainActor
    private func panelIsClosed(_ handle: XCUIElement) -> Bool {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if !handle.exists || handle.frame.minY >= app.frame.maxY - 2 { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
        return false
    }

    @MainActor
    func testDetailsDockContinuouslyRevealsPanelAndStaysAboveIt() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        let chapters = app.buttons["details.dock.chapters"]
        XCTAssertTrue(chapters.waitForExistence(timeout: 15), app.debugDescription)
        let read = app.buttons["details.dock.read"]
        XCTAssertTrue(read.waitForExistence(timeout: 15))
        let readingButton = read.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        readingButton.press(forDuration: 0.05,
                            thenDragTo: readingButton.withOffset(CGVector(dx: 0, dy: 28)),
                            withVelocity: .slow, thenHoldForDuration: 0.5)
        XCTAssertTrue(read.isHittable, "阅读按钮向下拖拽松手后必须停留在详情页")
        XCTAssertFalse(app.scrollViews["reader.canvas"].exists)
        let closedY = chapters.frame.midY
        let start = read.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -300)),
                    withVelocity: .slow, thenHoldForDuration: 0.5)
        let handle = app.buttons["details.panel.handle"]
        XCTAssertTrue(handle.waitForExistence(timeout: 5))
        XCTAssertTrue(chapters.isHittable)
        XCTAssertLessThan(chapters.frame.midY, closedY - 200)
        XCTAssertLessThan(chapters.frame.maxY, handle.frame.minY)
        attach("拖拽半展开：dock 位于面板上方")

        app.buttons["details.dock.bookmarks"].tap()
        XCTAssertTrue(app.buttons["details.dock.bookmarks"].isSelected)
        XCTAssertTrue(app.segmentedControls.buttons["书签"].isSelected)
        XCTAssertTrue(chapters.isHittable)
        app.buttons["details.dock.thumbnails"].tap()
        XCTAssertTrue(handle.exists)
        chapters.tap()

        let lifted = chapters.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        lifted.press(forDuration: 0.05, thenDragTo: lifted.withOffset(CGVector(dx: 0, dy: -250)),
                     withVelocity: .slow, thenHoldForDuration: 0.5)
        XCTAssertTrue(handle.waitForExistence(timeout: 5))
        XCTAssertTrue(chapters.isHittable, "最大展开时 dock 仍应显示并可拖拽")
        XCTAssertLessThan(chapters.frame.maxY, handle.frame.minY)
        XCTAssertTrue(app.buttons["完成"].isHittable)
        attach("最大展开时仍保留 dock")

        let maximizedHandleY = handle.frame.minY
        let top = read.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        top.press(forDuration: 0.05, thenDragTo: top.withOffset(CGVector(dx: 0, dy: 250)),
                  withVelocity: .slow, thenHoldForDuration: 0.5)
        XCTAssertTrue(chapters.isHittable)
        XCTAssertLessThan(chapters.frame.maxY, handle.frame.minY)
        XCTAssertGreaterThan(handle.frame.minY, maximizedHandleY + 150, "最大展开时从 dock 下拖应回到半屏")
        let hovered = read.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        hovered.press(forDuration: 0.05, thenDragTo: hovered.withOffset(CGVector(dx: 0, dy: 350)),
                      withVelocity: .slow, thenHoldForDuration: 0.5)
        XCTAssertTrue(panelIsClosed(handle), "面板应收起")
        XCTAssertFalse(app.scrollViews["reader.canvas"].exists, "半展开状态下拖阅读按钮也不能触发阅读")
        XCTAssertEqual(chapters.frame.midY, closedY, accuracy: 2)
        chapters.tap()
        XCTAssertTrue(handle.waitForExistence(timeout: 5) && !panelIsClosed(handle), "收起后点击仍可打开")
        app.buttons["完成"].tap()
        XCTAssertTrue(panelIsClosed(handle))
        read.tap()
        XCTAssertTrue(app.scrollViews["reader.canvas"].waitForExistence(timeout: 15), "普通点击仍应打开阅读器")
        app.buttons["reader.back"].tap()
        XCTAssertTrue(read.waitForExistence(timeout: 10))
    }

    @MainActor
    func testDetailsChapterSelectionDownloadsWithoutOpeningReader() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        app.buttons["details.dock.chapters"].tap()
        let select = app.buttons["details.chapters.select"]
        XCTAssertTrue(select.waitForExistence(timeout: 5), "章节面板必须提供多选入口")
        select.tap()
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter."))
        XCTAssertEqual(rows.count, 2)
        rows.element(boundBy: 0).tap()
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.buttons["details.chapters.downloadSelected"].label.contains("2"))
        XCTAssertFalse(app.images["reader.image"].exists)
        app.buttons["details.chapters.selectAll"].tap()
        XCTAssertFalse(app.buttons["details.chapters.downloadSelected"].isEnabled)
        app.buttons["details.chapters.selectAll"].tap()
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.buttons["details.chapters.downloadSelected"].label.contains("1"))
        app.buttons["details.chapters.downloadSelected"].tap()
        XCTAssertTrue(select.waitForExistence(timeout: 5))
        app.buttons["details.chapters.downloads"].tap()
        let first = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "fixture_reader_chapters")).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 10))
        attach("详情章节选择及下载队列")
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
        XCTAssertTrue(app.buttons["details.resume"].waitForExistence(timeout: 15))
        app.terminate()
    }

    /// Run after the bookmark case: its completed navigation saves chapter 02 to the real library database.
    @MainActor
    func testPagedCrossChapterHistoryReopensWithTheMatchingImage() throws {
        importFile(source: "LOCAL", file: "fixture_reader_chapters", reuse: true)
        XCTAssertTrue(app.buttons["details.resume"].waitForExistence(timeout: 15))
        app.buttons["details.resume"].tap()
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

    private func waitForCentre(_ colour: String) -> Bool {
        let matches = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard let pixel = self.screenPixel(x: 0.5, y: 0.5) else { return false }
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
        waitForReaderChapter("02")
        attach("连续条漫普通滚动跨章")
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
        XCTAssertTrue(app.navigationBars["作品资料"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.textFields["tracking.query"].value as? String, original)
        for site in [2, 4, 5] { XCTAssertTrue(app.buttons["tracking.site.\(site)"].exists) }
        attach("轻量作品资料选择")
        app.navigationBars["作品资料"].buttons["完成"].tap()
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
        metadata.tap()
        XCTAssertTrue(restore.waitForExistence(timeout: 10))
        restore.tap()
        XCTAssertTrue(metadata.waitForExistence(timeout: 10))
        XCTAssertTrue(metadata.label.contains("内容源"))
        XCTAssertTrue(app.staticTexts["details.title"].label.contains("fixture_comic"))
        assertMetadataChapterIsAvailable()
        attach("恢复源资料并保留章节")
    }

    @MainActor
    private func assertMetadataChapterIsAvailable() {
        let chapters = app.buttons["章节"].firstMatch
        XCTAssertTrue(chapters.waitForExistence(timeout: 10))
        chapters.tap()
        let chapter = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).firstMatch
        XCTAssertTrue(chapter.waitForExistence(timeout: 10), app.debugDescription)
        app.navigationBars.buttons["完成"].tap()
        XCTAssertTrue(chapter.waitForNonExistence(timeout: 10))
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
        XCTAssertTrue(app.buttons["details.resume"].waitForExistence(timeout: 10))
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
        app.buttons["details.resume"].tap()
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
        XCTAssertTrue(app.buttons["details.resume"].waitForExistence(timeout: 20))
        app.buttons["details.resume"].tap()
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
        canvas.swipeUp()
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.readingOffset(canvas) > 300 },
                                              object: nil)
        wait(for: [moved], timeout: 10)
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
        let resume = app.buttons["details.resume"]
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
        // The description continues after the offset (window size), so only its number is read.
        let tail = value.components(separatedBy: "页内偏移 ").dropFirst().first ?? ""
        return Int(tail.prefix { $0 == "-" || $0.isNumber }) ?? -1
    }

    private func selectReaderMode(_ mode: String) {
        app.buttons["reader.more"].tap()
        app.segmentedControls.buttons["布局"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "阅读模式")).firstMatch.tap()
        app.buttons[mode].tap()
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
    func testNovelPagedTextMarginsAreSymmetricAndContainTheFooter() throws {
        for margin: CGFloat in [60, 12] {
            app.launchArguments = ["-novel.reader.marginVertical", String(Int(margin))]
            openNovelControls(mode: "PAGED")
            let text = app.staticTexts["novel.firstParagraph"]
            let bounds = text.frame
            let bottom = app.frame.maxY - bounds.maxY
            XCTAssertEqual(bottom, margin, accuracy: 1, app.debugDescription)
            // XCTest does not expose the system status-bar element on this simulator. The top controls are inset
            // 12 points from the same safe-area edge; use their measured bounds, not a hard-coded notch size.
            let safeTop = app.buttons["novel.back"].frame.minY - 12
            XCTAssertEqual(bounds.minY - safeTop, margin, accuracy: 1,
                           "The top margin excludes the sensor/status-bar safe area")
            let footer = app.descendants(matching: .any)["novel.footer"].firstMatch
            XCTAssertTrue(footer.exists)
            XCTAssertGreaterThanOrEqual(footer.frame.minY, bounds.maxY - 1)
            XCTAssertLessThanOrEqual(footer.frame.maxY, app.frame.maxY + 1)
            attach("小说正文上下\(Int(margin))点边距与页脚位置")
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            XCTAssertTrue(app.buttons["novel.more"].waitForNonExistence(timeout: 10))
            XCTAssertEqual(text.frame.minY, bounds.minY, accuracy: 1)
            XCTAssertEqual(text.frame.maxY, bounds.maxY, accuracy: 1)
            attach("小说\(Int(margin))点边距的沉浸正文")
        }
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
        XCTAssertTrue(app.buttons["details.resume"].waitForExistence(timeout: 15), app.debugDescription)
        app.terminate(); app.launch()
        // Import again through the picker, proving that duplicate imports keep the original work/history identity.
        importFile(source: "LOCAL_VIDEO", file: "fixture_video")
        let resume = app.buttons["details.resume"]
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

    private func importFile(source name: String, file: String, reuse: Bool = false, expectImport: Bool = true) {
        app.tabBars.buttons["浏览"].tap()
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
            format: "identifier BEGINSWITH %@ AND label MATCHES %@", "source.content.", fileLabel)).firstMatch
        // Reader regressions may reuse their immutable fixtures; import tests still exercise the picker every run.
        if reuse && item.waitForExistence(timeout: 3) { item.tap(); return }
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
        if !picked.isSelected { picked.tap() }
        // Multiple selection keeps the picker open until 打开 is pressed.
        for label in ["打开", "Open"] where app.buttons[label].waitForExistence(timeout: 3) {
            let open = app.buttons[label]
            XCTAssertTrue(open.isEnabled, app.debugDescription)
            open.tap(); break
        }
        XCTAssertTrue(pickerList.waitForNonExistence(timeout: 15), app.debugDescription)
        if !expectImport { return }
        XCTAssertTrue(item.waitForExistence(timeout: 30), app.debugDescription)
        attach("\(name) 导入后")
        item.tap()
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
