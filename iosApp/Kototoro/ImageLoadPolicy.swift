import Foundation
import KototoroShared
import Network
import SwiftUI
import os

/// Bounded request concurrency with priorities, the iOS counterpart of Android's `PriorityPermitPool`: a freed slot
/// goes to the highest-priority waiter (first come within a priority), and a waiter whose task is cancelled leaves the
/// queue without ever holding a slot.
actor RequestGate {
    /// Android's Coil loader: `fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))`.
    static let covers = RequestGate(limit: 8)
    /// Dedicated gate for source and extension icons, isolated from covers so background favicons never stall content covers.
    static let icons = RequestGate(limit: 4)
    /// Android's Coil loader: `decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))`.
    static let coverDecoding = RequestGate(limit: 3)
    /// Shared by reader overviews and tiled bases: network concurrency does not bound native decode memory.
    static let readerDecoding = RequestGate(limit: 2)

    private struct Waiter {
        let ticket: Int
        var priority: Int
        let continuation: CheckedContinuation<Bool, Never>
    }

    private var limit: Int
    private var running = 0
    private var waiters: [Waiter] = []
    private static let tickets = OSAllocatedUnfairLock(initialState: 0)

    init(limit: Int) { self.limit = max(1, limit) }

    /// Identifies a request so [promote] can raise it while it waits (a prefetch the reader now needs).
    nonisolated static func makeTicket() -> Int {
        tickets.withLock { $0 += 1; return $0 }
    }

    /// Returns false when the calling task was cancelled before it got a slot; the slot is not held then.
    func enter(priority: Int = 0, ticket: Int? = nil) async -> Bool {
        if Task.isCancelled { return false }
        if running < limit && waiters.isEmpty {
            running += 1
            return true
        }
        let ticket = ticket ?? Self.makeTicket()
        let granted = await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                // A cancellation that arrived before this waiter was queued found nothing to drop.
                if Task.isCancelled { continuation.resume(returning: false); return }
                waiters.append(Waiter(ticket: ticket, priority: priority, continuation: continuation))
            }
        } onCancel: {
            Task { await self.drop(ticket) }
        }
        if granted && Task.isCancelled {
            leave()
            return false
        }
        return granted
    }

    func promote(_ ticket: Int, to priority: Int) {
        guard let index = waiters.firstIndex(where: { $0.ticket == ticket }), waiters[index].priority < priority else { return }
        waiters[index].priority = priority
    }

    func leave() {
        if running > limit {
            running -= 1
            return
        }
        // Waiters are in arrival order, so the first of the highest priority is the oldest one.
        guard let top = waiters.map(\.priority).max(), let best = waiters.firstIndex(where: { $0.priority == top }) else {
            running -= 1
            return
        }
        waiters.remove(at: best).continuation.resume(returning: true)  // hand the slot over directly
    }

    /// Settings changes apply to later requests; running ones finish.
    func setLimit(_ value: Int) {
        limit = max(1, value)
        while running < limit, !waiters.isEmpty {
            running += 1
            leave()
        }
    }

    private func drop(_ ticket: Int) {
        guard let index = waiters.firstIndex(where: { $0.ticket == ticket }) else { return }
        waiters.remove(at: index).continuation.resume(returning: false)
    }
}

extension IosSources {
    /// A page image whose download stops when the calling task is cancelled. Kotlin/Native's async export does not
    /// forward Swift task cancellation, so the plain `image(page:)` keeps downloading (and holding its reader-thread
    /// slot) after the reader has moved on; enough of those stalled and every page waited forever.
    func cancellableImage(page: IosSourcePage) async throws -> IosSourceImage {
        let cancellation = IosCancellation()
        return try await withTaskCancellationHandler {
            try await image(page: page, cancellation: cancellation)
        } onCancel: {
            cancellation.cancel()
        }
    }
}

/// Reader page loading settings under Android's preference keys and defaults (`AppSettings.readerThreads`,
/// `readerPrefetchLimit`, `pagesPreloadPolicy`), with Android's prefetch rules.
enum ReaderLoading {
    static let threadsKey = "reader_threads"
    static let prefetchLimitKey = "reader_prefetch_limit"
    static let preloadKey = "pages_preload"

    /// `PageLoader` priorities: prefetches wait behind pages on screen, a retry goes first.
    static let prefetchPriority = 0
    static let visiblePriority = 1
    /// `ReaderViewModel.PREFETCH_LIMIT`: pages after the visible ones offered to the prefetch queue.
    static let prefetchWindow = 10

    static var threads: Int { (UserDefaults.standard.object(forKey: threadsKey) as? Int ?? 3).clamped(1...10) }
    static var prefetchLimit: Int { (UserDefaults.standard.object(forKey: prefetchLimitKey) as? Int ?? 6).clamped(1...20) }

    enum Preload: String, CaseIterable {
        case always = "1", unmetered = "2", never = "0"

        var title: String {
            switch self {
            case .always: String(localized: "总是")
            case .unmetered: String(localized: "仅连接 Wi-Fi 时")
            case .never: String(localized: "从不")
            }
        }
    }

    /// Android stores the policy as a string; an unknown or missing value means Wi-Fi only.
    static var preload: Preload { Preload(rawValue: UserDefaults.standard.string(forKey: preloadKey) ?? "") ?? .unmetered }

    /// `PageLoader.isPrefetchApplicable`: the policy allows the current network, the system is not saving data (Low
    /// Data Mode, Android's background data restriction) and not saving power.
    static var isPrefetchApplicable: Bool {
        if ProcessInfo.processInfo.isLowPowerModeEnabled { return false }
        let network = NetworkState.shared.current
        if network.constrained { return false }
        switch preload {
        case .always: return true
        case .unmetered: return !network.expensive
        case .never: return false
        }
    }
}

/// The current path's metering, kept by one monitor for the whole app.
final class NetworkState: Sendable {
    struct Snapshot: Sendable { var expensive = false; var constrained = false }

    static let shared = NetworkState()
    private let monitor = NWPathMonitor()
    private let state = OSAllocatedUnfairLock(initialState: Snapshot())

    private init() {
        monitor.pathUpdateHandler = { [state] path in
            state.withLock { $0 = Snapshot(expensive: path.isExpensive, constrained: path.isConstrained) }
        }
        monitor.start(queue: DispatchQueue(label: "kototoro.network-state"))
    }

    var current: Snapshot { state.withLock { $0 } }
}

/// Android's reader settings group for page loading.
struct ReaderLoadingSettingsSection: View {
    @AppStorage(ReaderLoading.preloadKey) private var preload = ReaderLoading.Preload.unmetered.rawValue
    @AppStorage(ReaderLoading.threadsKey) private var threads = 3
    @AppStorage(ReaderLoading.prefetchLimitKey) private var prefetchLimit = 6

    var body: some View {
        Section {
            Picker("页面预加载", selection: $preload) {
                ForEach(ReaderLoading.Preload.allCases, id: \.rawValue) { Text($0.title).tag($0.rawValue) }
            }
            .accessibilityIdentifier("settings.pagesPreload")
            Stepper(value: $threads, in: 1...10) {
                LabeledContent("阅读器线程数", value: "\(threads)")
            }
            .accessibilityIdentifier("settings.readerThreads")
            Stepper(value: $prefetchLimit, in: 1...20) {
                LabeledContent("预读页数", value: "\(prefetchLimit)")
            }
            .accessibilityIdentifier("settings.prefetchLimit")
        } header: {
            Text("阅读器加载")
        } footer: {
            Text("线程数为阅读时同时加载的页面数量；预读页数为提前加载的页面数量。省电模式与低数据模式下不预加载。")
        }
    }
}

private extension Comparable {
    func clamped(_ range: ClosedRange<Self>) -> Self { min(max(self, range.lowerBound), range.upperBound) }
}
