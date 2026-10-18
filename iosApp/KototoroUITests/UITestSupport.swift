import XCTest

extension XCUIApplication {
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

