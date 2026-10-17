import Foundation
import SwiftUI
import XCTest

final class ReaderSettingsTests: XCTestCase {
    func testDefaultPagedReaderIsRightToLeftAndSavedChoicesAreRetained() throws {
        XCTAssertEqual(ReaderPreferences().mode, 1)
        XCTAssertTrue(ReaderPreferences().readsRightToLeft)
        XCTAssertFalse(ReaderPreferences().doublePage)
        let missing = try JSONDecoder().decode(ReaderPreferences.self, from: Data("{}".utf8))
        XCTAssertEqual(missing.mode, 1)
        XCTAssertFalse(missing.doublePage)
        let saved = try JSONDecoder().decode(ReaderPreferences.self,
            from: Data("{\"mode\":0,\"doublePage\":false}".utf8))
        XCTAssertEqual(saved.mode, 0)
        XCTAssertFalse(saved.doublePage)
        let enabled = try JSONDecoder().decode(ReaderPreferences.self,
            from: Data("{\"mode\":1,\"doublePage\":true}".utf8))
        XCTAssertTrue(enabled.doublePage)
        XCTAssertTrue(enabled.layout(for: CGSize(width: 844, height: 390), wideWindow: false).doublePage)
        XCTAssertFalse(enabled.layout(for: CGSize(width: 390, height: 844), wideWindow: false).doublePage)
    }

    func testAutomaticSpreadsFollowAvailableWindowAndKeepSavedPreference() {
        let portrait = CGSize(width: 390, height: 844)
        let landscape = CGSize(width: 844, height: 390)
        let expanded = CGSize(width: 744, height: 1000)
        var preferences = ReaderPreferences()
        preferences.doublePage = true
        XCTAssertFalse(preferences.layout(for: portrait, wideWindow: false).doublePage)
        XCTAssertTrue(preferences.layout(for: landscape, wideWindow: false).doublePage)
        XCTAssertTrue(preferences.layout(for: expanded, wideWindow: true).doublePage)
        XCTAssertFalse(preferences.layout(for: expanded, wideWindow: false).doublePage)
        XCTAssertTrue(preferences.doublePage, "Portrait must not overwrite the saved automatic-spread preference")
        for mode in [3, 4] {
            preferences.mode = mode
            XCTAssertFalse(preferences.layout(for: landscape, wideWindow: true).doublePage)
        }
        preferences.mode = 1
        preferences.doublePage = false
        XCTAssertFalse(preferences.layout(for: landscape, wideWindow: true).doublePage)
    }

    func testNewHorizontalReaderDefaultsToLeftToRight() {
        var preferences = ReaderPreferences()
        preferences.mode = 4
        XCTAssertFalse(preferences.horizontalReversed)
        XCTAssertFalse(preferences.readsRightToLeft)
    }

    func testLegacyHorizontalPreferencesKeepTheirPreviousDirection() throws {
        let horizontal = try JSONDecoder().decode(ReaderPreferences.self, from: Data("{\"mode\":4}".utf8))
        XCTAssertTrue(horizontal.horizontalReversed)
        XCTAssertTrue(horizontal.readsRightToLeft)
        let vertical = try JSONDecoder().decode(ReaderPreferences.self, from: Data("{\"mode\":3}".utf8))
        XCTAssertFalse(vertical.horizontalReversed)
    }

    func testExplicitHorizontalDirectionSurvivesRestart() throws {
        var preferences = ReaderPreferences()
        preferences.mode = 4
        for reversed in [false, true] {
            preferences.horizontalReversed = reversed
            let saved = try JSONEncoder().encode(preferences)
            XCTAssertEqual(try JSONDecoder().decode(ReaderPreferences.self, from: saved), preferences)
        }
    }

    func testOnlyAnActiveDirectionChangeRequiresLayout() {
        var before = ReaderPreferences()
        before.mode = 3
        var after = before
        after.horizontalReversed = true
        XCTAssertTrue(before.hasSameLayout(as: after))
        before.mode = 4
        after.mode = 4
        XCTAssertFalse(before.hasSameLayout(as: after))
        XCTAssertEqual(before.continuousForwardSign, 1)
        XCTAssertEqual(after.continuousForwardSign, -1)
    }

    func testLandscapeDoublePagePreservesReadingDirection() {
        let landscape = CGSize(width: 844, height: 390)
        var preferences = ReaderPreferences()
        preferences.doublePage = true
        preferences.mode = 1 // Right to left
        let layout = preferences.layout(for: landscape, wideWindow: false)
        XCTAssertTrue(layout.doublePage)
        XCTAssertTrue(layout.readsRightToLeft)
        XCTAssertEqual(layout.mode, 1)
    }
}
