import BackgroundTasks
import KototoroShared
import os
import SwiftUI
import UIKit

/// One long operation the user started, kept running when the app leaves the screen. A user-started operation asks
/// the system for a continued processing task, whose progress the system draws itself (its live activity, with the
/// user's own stop button); when that is refused, or for automatic work the system does not allow to ask, only the
/// short background-time lease covers it. The phone does not lock on its own while the operation runs on screen.
///
/// Kotlin calls do not observe Swift cancellation, so a stop from the system's progress takes effect at the
/// operation's next checkpoint ([isStopped], [onStop]); the task itself is completed at once, as the system requires.
@MainActor
final class ContinuedWork {
    /// The wildcard entry in `BGTaskSchedulerPermittedIdentifiers`, without its `*`.
    static let identifierPrefix = String(permittedTaskIdentifier("continued.*").dropLast())
    /// Leases still running; the idle timer comes back only when the last one ends.
    private static var running = 0
    private static var idleTimerWasDisabled = false
    /// Whether the system took each request: its progress shows nowhere else when it does not.
    private static let log = Logger(subsystem: "org.skepsun.kototoro.ios", category: "ContinuedWork")

    private let identifier: String
    private var title: String
    private var subtitle: String
    private var completed: Int64 = 0
    private var total: Int64 = 1
    private var task: BGContinuedProcessingTask?
    private var submitted = false
    private var background = UIBackgroundTaskIdentifier.invalid
    private var finished = false
    private var succeeded = false

    /// The system ended the task: the user pressed stop, or the system needed the time back.
    private(set) var isStopped = false
    /// Called once when [isStopped] becomes true.
    var onStop: (() -> Void)?
    /// Called when the short background-time lease runs out with no continued processing task covering the work:
    /// the app is about to be suspended. Must do its part synchronously.
    var onTimeUp: (() -> Void)?

    /// Runs [work] under one lease. [userInitiated] is false for work the app starts by itself (on launch or on a
    /// timer): the system only grants continued processing to an action the user just took.
    static func run<T>(
        _ kind: String,
        title: String,
        subtitle: String = "",
        userInitiated: Bool = true,
        _ work: (ContinuedWork) async throws -> T
    ) async rethrows -> T {
        let lease = ContinuedWork(kind: kind, title: title, subtitle: subtitle)
        lease.start(continued: userInitiated)
        defer { lease.finish() }
        let value = try await work(lease)
        lease.succeeded = true
        return value
    }

    /// A lease with no single scope, such as a queue that drains for a while; it lasts until [end].
    static func begin(_ kind: String, title: String, subtitle: String = "", userInitiated: Bool) -> ContinuedWork {
        let lease = ContinuedWork(kind: kind, title: title, subtitle: subtitle)
        lease.start(continued: userInitiated)
        return lease
    }

    func end(succeeded: Bool) {
        guard !finished else { return }
        self.succeeded = succeeded
        finish()
    }

    private init(kind: String, title: String, subtitle: String) {
        identifier = Self.identifierPrefix + kind + "." + UUID().uuidString.prefix(8)
        self.title = title
        self.subtitle = subtitle
    }

    /// Determinate progress; [subtitle] replaces the line under the title when given.
    func progress(_ completed: some BinaryInteger, of total: some BinaryInteger, subtitle: String? = nil) {
        self.total = max(Int64(total), 1)
        self.completed = min(max(Int64(completed), 0), self.total)
        if let subtitle { self.subtitle = subtitle }
        publish(text: subtitle != nil)
    }

    /// What the operation reports, for the page that started it; [fraction] is nil while the size is unknown.
    struct Status: Equatable {
        var fraction: Double?
        var text: String
    }

    /// The shared library's progress for this operation: drives the system's progress and, through [show], the page.
    /// Reports that arrive after the operation ended are dropped.
    func listener(_ show: (@MainActor (Status) -> Void)? = nil) -> IosProgressListener {
        MainThreadProgress { [weak self] stage, completed, total in
            guard let self, !self.finished else { return }
            let text = Self.text(stage: stage, completed: completed, total: total)
            // Without a total the bar keeps moving towards an end it never reaches, which still counts as progress.
            self.progress(completed, of: total > 0 ? total : completed + 1, subtitle: text)
            show?(Status(fraction: total > 0 ? Double(completed) / Double(total) : nil, text: text))
        }
    }

    private static func text(stage: String, completed: Int64, total: Int64) -> String {
        func bytes(_ value: Int64) -> String { ByteCountFormatter.string(fromByteCount: value, countStyle: .file) }
        switch stage {
        case "upload": return String(localized: "已上传 \(bytes(completed)) / \(bytes(total))")
        case "download":
            return total > 0 ? String(localized: "已下载 \(bytes(completed)) / \(bytes(total))")
                : String(localized: "已下载 \(bytes(completed))")
        default:
            // A backup section: written one after another, or merged row by row against the archive's count.
            let section = BackupView.title(stage)
            return total > 0 ? "\(section) · \(completed)/\(total)" : section
        }
    }

    /// A new line under the title without moving the progress.
    func update(subtitle: String) {
        self.subtitle = subtitle
        publish(text: true)
    }

    private func start(continued: Bool) {
        if Self.running == 0 { Self.idleTimerWasDisabled = UIApplication.shared.isIdleTimerDisabled }
        Self.running += 1
        UIApplication.shared.isIdleTimerDisabled = true
        background = UIApplication.shared.beginBackgroundTask(withName: identifier) { [weak self] in
            guard let self else { return }
            if self.task == nil && !self.finished && !self.isStopped {
                self.onTimeUp?()
                self.onTimeUp = nil
            }
            self.endBackground()
        }
        guard continued else { return }
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { [weak self] task in
            MainActor.assumeIsolated {
                guard let task = task as? BGContinuedProcessingTask else {
                    task.setTaskCompleted(success: false)
                    return
                }
                guard let self, !self.finished else {
                    task.setTaskCompleted(success: self?.succeeded ?? false)
                    return
                }
                self.attach(task)
            }
        }
        guard registered else {
            Self.log.error("\(self.identifier, privacy: .public): launch handler not registered")
            return
        }
        let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: title, subtitle: subtitle)
        // The work starts now either way; a queued request would only show the progress after it is over.
        request.strategy = .fail
        do {
            try BGTaskScheduler.shared.submit(request)
            submitted = true
            Self.log.info("\(self.identifier, privacy: .public): submitted")
        } catch {
            Self.log.error("\(self.identifier, privacy: .public): refused: \(String(describing: error), privacy: .public)")
            // Refused (busy system, too many tasks, simulator): the background-time lease still covers a short switch.
        }
    }

    private func attach(_ task: BGContinuedProcessingTask) {
        self.task = task
        Self.log.info("\(self.identifier, privacy: .public): launched")
        task.expirationHandler = { [weak self] in
            DispatchQueue.main.async { self?.stop() }
        }
        publish(text: true)
        // The task covers the work from here; holding the short lease as well past its 30 seconds risks the app
        // being terminated in the background.
        endBackground()
    }

    private func publish(text: Bool) {
        guard let task else { return }
        task.progress.totalUnitCount = total
        task.progress.completedUnitCount = completed
        if text { task.updateTitle(title, subtitle: subtitle) }
    }

    private func stop() {
        guard let task else { return }
        Self.log.info("\(self.identifier, privacy: .public): expired")
        self.task = nil
        task.setTaskCompleted(success: false)
        isStopped = true
        onStop?()
        onStop = nil
    }

    private func finish() {
        finished = true
        onTimeUp = nil
        Self.running -= 1
        if Self.running == 0 { UIApplication.shared.isIdleTimerDisabled = Self.idleTimerWasDisabled }
        onStop = nil
        if let task {
            self.task = nil
            if succeeded { task.progress.completedUnitCount = task.progress.totalUnitCount }
            task.setTaskCompleted(success: succeeded)
        } else if submitted {
            // Not launched yet: the request must not start after the work is over.
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        }
        endBackground()
    }

    private func endBackground() {
        guard background != .invalid else { return }
        UIApplication.shared.endBackgroundTask(background)
        background = .invalid
    }
}

/// Hands the shared library's progress, reported on a background thread, to the main actor in order.
final class MainThreadProgress: IosProgressListener {
    private let handler: @MainActor (String, Int64, Int64) -> Void

    init(_ handler: @escaping @MainActor (String, Int64, Int64) -> Void) { self.handler = handler }

    func onProgress(stage: String, completed: Int64, total: Int64) {
        DispatchQueue.main.async { MainActor.assumeIsolated { self.handler(stage, completed, total) } }
    }
}

/// The favourite update check's running counts, handed to the main actor in order.
final class MainThreadUpdateProgress: IosUpdateProgressListener {
    private let handler: @MainActor (IosUpdateProgress) -> Void

    init(_ handler: @escaping @MainActor (IosUpdateProgress) -> Void) { self.handler = handler }

    func onUpdateProgress(progress: IosUpdateProgress) {
        DispatchQueue.main.async { MainActor.assumeIsolated { self.handler(progress) } }
    }
}

/// A page's own line for the operation it started: a spinner until the operation reports, then a bar with its text.
struct ContinuedWorkProgress: View {
    let status: ContinuedWork.Status?

    var body: some View {
        if let status {
            ProgressView(value: status.fraction) {
                Text(status.text).font(.footnote).foregroundStyle(.secondary).monospacedDigit()
            }
        } else {
            ProgressView().frame(maxWidth: .infinity)
        }
    }
}
