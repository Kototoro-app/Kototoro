import Foundation
import SwiftUI
import XCTest

final class ImageLoadPolicyTests: XCTestCase {
    /// Lets queued tasks reach the gate; it exposes no state, so tests give them a moment.
    private func settle() async { try? await Task.sleep(for: .milliseconds(50)) }

    @MainActor
    func testSourceIconCompletionsDoNotRebuildBrowsePreferences() async throws {
        let counter = BrowseBodyCounter()
        let host = UIHostingController(rootView: BrowsePreferencesProbe(counter: counter))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        await settle()
        let baseline = counter.updates
        XCTAssertGreaterThan(baseline, 0)

        let key = "source.icon.v2.failed.\(UUID().uuidString)"
        let failures = SourceIconRetry.failures
        defer { failures.removeObject(forKey: key) }
        // Cold website failures and successful disk hits both used to write the browse preferences' domain.
        failures.set(Date().timeIntervalSince1970, forKey: key)
        await settle()
        XCTAssertGreaterThan(failures.double(forKey: key), 0)
        failures.removeObject(forKey: key)
        await settle()
        failures.removeObject(forKey: key)
        await settle()
        XCTAssertEqual(counter.updates, baseline, "An icon completion must only update its own row")
    }

    func testFreedSlotGoesToHighestPriorityThenArrivalOrder() async throws {
        let gate = RequestGate(limit: 1)
        let held = await gate.enter()
        XCTAssertTrue(held)
        let order = OrderLog()
        let prefetchA = Task { if await gate.enter(priority: 0) { await order.add("prefetch A"); await gate.leave() } }
        await settle()
        let prefetchB = Task { if await gate.enter(priority: 0) { await order.add("prefetch B"); await gate.leave() } }
        await settle()
        let visible = Task { if await gate.enter(priority: 1) { await order.add("visible"); await gate.leave() } }
        await settle()
        await gate.leave()
        _ = await (prefetchA.value, prefetchB.value, visible.value)
        let entries = await order.entries
        XCTAssertEqual(entries, ["visible", "prefetch A", "prefetch B"])
    }

    func testPromotedPrefetchOvertakesOlderPrefetch() async throws {
        let gate = RequestGate(limit: 1)
        _ = await gate.enter()
        let order = OrderLog()
        let older = Task { if await gate.enter(priority: 0) { await order.add("older"); await gate.leave() } }
        await settle()
        let ticket = RequestGate.makeTicket()
        let promoted = Task { if await gate.enter(priority: 0, ticket: ticket) { await order.add("promoted"); await gate.leave() } }
        await settle()
        await gate.promote(ticket, to: 1)
        await gate.leave()
        _ = await (older.value, promoted.value)
        let entries = await order.entries
        XCTAssertEqual(entries, ["promoted", "older"])
    }

    func testCancelledWaiterLeavesWithoutTakingTheSlot() async throws {
        let gate = RequestGate(limit: 1)
        _ = await gate.enter()
        let cancelled = Task { await gate.enter() }
        await settle()
        cancelled.cancel()
        let enteredAfterCancel = await cancelled.value
        XCTAssertFalse(enteredAfterCancel)
        await gate.leave()
        // The only slot is free again rather than handed to the cancelled waiter.
        let next = await gate.enter()
        XCTAssertTrue(next)
        await gate.leave()
    }

    func testAndroidDefaultsAndRanges() {
        let defaults = UserDefaults.standard
        let keys = [ReaderLoading.threadsKey, ReaderLoading.prefetchLimitKey, ReaderLoading.preloadKey]
        let saved = keys.map { defaults.object(forKey: $0) }
        defer { for (key, value) in zip(keys, saved) { defaults.set(value, forKey: key) } }
        keys.forEach(defaults.removeObject(forKey:))
        XCTAssertEqual(ReaderLoading.threads, 3)
        XCTAssertEqual(ReaderLoading.prefetchLimit, 6)
        XCTAssertEqual(ReaderLoading.preload, .unmetered)
        defaults.set(40, forKey: ReaderLoading.threadsKey)
        defaults.set(0, forKey: ReaderLoading.prefetchLimitKey)
        defaults.set("1", forKey: ReaderLoading.preloadKey)
        XCTAssertEqual(ReaderLoading.threads, 10)
        XCTAssertEqual(ReaderLoading.prefetchLimit, 1)
        XCTAssertEqual(ReaderLoading.preload, .always)
    }
}

@MainActor
private final class BrowseBodyCounter {
    var updates = 0
}

private struct BrowsePreferencesProbe: View {
    @AppStorage("sources.layout") private var layout = "list"
    let counter: BrowseBodyCounter

    var body: some View {
        let _ = { counter.updates += 1 }()
        Text(layout)
    }
}

private actor OrderLog {
    private(set) var entries: [String] = []
    func add(_ entry: String) { entries.append(entry) }
}
