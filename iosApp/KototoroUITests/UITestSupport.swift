import XCTest

extension XCUIApplication {
    /// Opens Settings and follows [path], the identifiers of the entries to tap, scrolling each page to its entry.
    func openSettings(_ path: String..., file: StaticString = #filePath, line: UInt = #line) {
        mainTab("设置").tap()
        for id in path {
            let entry = buttons[id]
            for _ in 0..<6 where !(entry.exists && entry.isHittable) { swipeUp() }
            XCTAssertTrue(entry.waitForExistence(timeout: 10), debugDescription, file: file, line: line)
            entry.tap()
        }
    }

    /// Records → Bookmarks → the notes overview of every work.
    func openNotes(file: StaticString = #filePath, line: UInt = #line) {
        mainTab("记录").tap()
        let filter = buttons["timeline.filter.bookmarks"]
        XCTAssertTrue(filter.waitForExistence(timeout: 15), debugDescription, file: file, line: line)
        filter.tap()
        let entry = buttons["records.notes"]
        XCTAssertTrue(entry.waitForExistence(timeout: 10), debugDescription, file: file, line: line)
        entry.tap()
    }

    /// All record kinds stay on the same timeline.
    func selectRecordsFilter(_ filter: String) {
        buttons["timeline.filter.\(filter)"].tap()
    }

    /// Reading stays in the header until it scrolls away, then moves to the native toolbar.
    func detailsReadButton() -> XCUIElement {
        let toolbar = buttons["details.read"]
        return toolbar.isHittable ? toolbar : buttons["details.header.read"]
    }

    /// The inline directory link remains available when the bottom toolbar is hidden.
    func openDetailsChapters(file: StaticString = #filePath, line: UInt = #line) {
        let toolbar = buttons["details.chapters.all"]
        let inline = buttons["details.chapters.showAll"]
        for _ in 0..<8 {
            if toolbar.isHittable { toolbar.tap(); return }
            if inline.isHittable { inline.tap(); return }
            collectionViews.firstMatch.swipeUp(velocity: .slow)
        }
        XCTFail("The details directory must remain reachable", file: file, line: line)
    }

    /// iOS can collapse the tab bar while scrolling; tapping the remaining selected tab expands it again.
    func mainTab(_ title: String) -> XCUIElement {
        let tab = tabBars.buttons[title]
        if !tab.exists {
            let collapsed = tabBars.buttons.matching(NSPredicate(format: "value == %@", "已折叠")).firstMatch
            if collapsed.exists {
                collapsed.tap()
                _ = tab.waitForExistence(timeout: 5)
            }
        }
        return tab
    }

    /// The page's own system search field. Hidden tabs keep their views (and fields) alive, so only a hittable
    /// field belongs to this page; a field tucked under the title is pulled back into view, and a page that keeps
    /// search in its toolbar opens it from there.
    func pageSearchField(timeout: TimeInterval = 10) -> XCUIElement {
        func visible() -> XCUIElement? { searchFields.allElementsBoundByIndex.first { $0.exists && $0.isHittable } }
        _ = searchFields.firstMatch.waitForExistence(timeout: 2)
        if let field = visible() { return field }
        let scroll = scrollViews.allElementsBoundByIndex.first { $0.isHittable } ?? collectionViews.firstMatch
        if scroll.exists { scroll.swipeDown() }
        if let field = visible() { return field }
        let button = navigationBars.buttons["搜索"].firstMatch
        if button.waitForExistence(timeout: timeout) { button.tap() }
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let field = visible() { return field }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return searchFields.firstMatch
    }
}


/// One finger of a synthesized multi-touch event, in screen points and seconds from the event start.
struct SyntheticTouch {
    let down: CGPoint
    let at: Double
    var moves: [(CGPoint, Double)] = []
    let liftAt: Double
}

/// XCUICoordinate drives one straight drag. Paths that turn mid-contact, or several fingers at once, go through
/// XCTest's own event synthesis, the private API WebDriverAgent uses for multi-touch actions.
func synthesizeTouches(_ touches: [SyntheticTouch], file: StaticString = #filePath, line: UInt = #line) {
    typealias Alloc = @convention(c) (AnyClass, Selector) -> Unmanaged<AnyObject>
    typealias InitPath = @convention(c) (AnyObject, Selector, CGPoint, Double) -> Unmanaged<AnyObject>
    typealias Move = @convention(c) (AnyObject, Selector, CGPoint, Double) -> Void
    typealias Lift = @convention(c) (AnyObject, Selector, Double) -> Void
    typealias InitRecord = @convention(c) (AnyObject, Selector, NSString, Int) -> Unmanaged<AnyObject>
    typealias Add = @convention(c) (AnyObject, Selector, AnyObject) -> Void
    typealias Synthesize = @convention(c) (AnyObject, Selector, UnsafeMutablePointer<NSError?>?) -> Bool
    guard let pathClass = NSClassFromString("XCPointerEventPath"),
          let recordClass = NSClassFromString("XCSynthesizedEventRecord") else {
        return XCTFail("XCTest event synthesis is unavailable", file: file, line: line)
    }
    /// An instance method of [cls], or a class method when [meta] is set.
    func method<T>(_ cls: AnyClass, _ name: String, as type: T.Type, meta: Bool = false) -> (T, Selector) {
        let selector = NSSelectorFromString(name)
        let owner: AnyClass = meta ? object_getClass(cls)! : cls
        return (unsafeBitCast(class_getMethodImplementation(owner, selector), to: type), selector)
    }
    func alloc(_ cls: AnyClass) -> AnyObject {
        let (fn, sel) = method(cls, "alloc", as: Alloc.self, meta: true)
        return fn(cls, sel).takeUnretainedValue()
    }
    let (initRecord, initRecordSel) = method(recordClass, "initWithName:interfaceOrientation:", as: InitRecord.self)
    let record = initRecord(alloc(recordClass), initRecordSel, "multi-touch", 1).takeRetainedValue()
    let (initPath, initPathSel) = method(pathClass, "initForTouchAtPoint:offset:", as: InitPath.self)
    let (move, moveSel) = method(pathClass, "moveToPoint:atOffset:", as: Move.self)
    let (lift, liftSel) = method(pathClass, "liftUpAtOffset:", as: Lift.self)
    let (add, addSel) = method(recordClass, "addPointerEventPath:", as: Add.self)
    // Paths in landing order: a later path listed first lands a phantom touch at its start point at offset zero.
    for touch in touches.sorted(by: { $0.at < $1.at }) {
        let path = initPath(alloc(pathClass), initPathSel, touch.down, touch.at).takeRetainedValue()
        for (point, offset) in touch.moves { move(path, moveSel, point, offset) }
        lift(path, liftSel, touch.liftAt)
        add(record, addSel, path)
    }
    var error: NSError?
    let (synthesize, synthesizeSel) = method(recordClass, "synthesizeWithError:", as: Synthesize.self)
    XCTAssertTrue(synthesize(record, synthesizeSel, &error), "\(error.map(String.init(describing:)) ?? "")",
                  file: file, line: line)
}
