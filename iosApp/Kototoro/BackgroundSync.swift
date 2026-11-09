import BackgroundTasks
import Foundation

/// WebDAV auto backups and Google Drive syncs while the app is away, at the intervals their pages set (Android's
/// periodic work). Both only need the database and the network, never the extension runtime, so a cold background
/// launch does them too: the library opens on its own. The system runs the processing task when it sees fit, usually
/// while the phone is idle on a network, so an interval is the earliest gap, never a promise.
@MainActor
final class BackgroundSync {
    static let shared = BackgroundSync()
    /// Listed in `BGTaskSchedulerPermittedIdentifiers`.
    static let identifier = permittedTaskIdentifier("sync")
    /// When the last scheduled Drive sync started; a failed one waits half an hour before the next.
    private static let driveAttemptKey = "googleDrive.backgroundAttempt"

    private var registered = false

    /// Must run before the app finishes launching, as the scheduler requires.
    func register() {
        guard !registered else { return }
        registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.identifier, using: .main) { task in
            MainActor.assumeIsolated { runBackgroundTask(task) { await BackgroundSync.shared.run() } }
        }
    }

    /// Asks for the next run at the soonest due sync, or cancels the request when nothing is set up.
    func schedule() {
        guard let begin = BackgroundSyncSchedule.request([webDavDue(), driveDue(LibraryModel.shared.googleDrive)],
                                                         now: Date()) else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.identifier)
            return
        }
        let request = BGProcessingTaskRequest(identifier: Self.identifier)
        request.requiresNetworkConnectivity = true
        request.earliestBeginDate = begin
        try? BGTaskScheduler.shared.submit(request)
    }

    /// Each sync that is due, then the next request.
    func run() async {
        defer { schedule() }
        let model = LibraryModel.shared
        do { try model.open() } catch { return }
        if webDavDue() != nil { await WebDavConfig.backUpIfDue(model.library) }
        if !Task.isCancelled, let due = driveDue(model.googleDrive), due <= Date() {
            UserDefaults.standard.set(Date(), forKey: Self.driveAttemptKey)
            await model.googleDrive.sync(model, trigger: .schedule)
        }
    }

    private func webDavDue() -> Date? {
        let defaults = UserDefaults.standard
        return BackgroundSyncSchedule.webDavDue(
            intervalHours: defaults.integer(forKey: WebDavConfig.intervalKey),
            server: defaults.string(forKey: WebDavConfig.serverKey) ?? "",
            lastUpload: defaults.double(forKey: WebDavConfig.lastUploadKey),
            lastFailure: defaults.double(forKey: WebDavConfig.lastFailureKey))
    }

    private func driveDue(_ drive: GoogleDriveSyncModel) -> Date? {
        BackgroundSyncSchedule.driveDue(
            intervalHours: UserDefaults.standard.integer(forKey: GoogleDriveSyncModel.backgroundIntervalKey),
            connected: drive.connected, lastSync: drive.lastSync,
            lastAttempt: UserDefaults.standard.object(forKey: Self.driveAttemptKey) as? Date)
    }
}

/// The scheduler identifier `BGTaskSchedulerPermittedIdentifiers` lists under [suffix], such as `sync` or `continued.*`.
/// The system only accepts identifiers the installed Info.plist lists, and a sideloading tool that re-signs the app
/// under another bundle identifier rewrites the listed ones to match (`org.skepsun.kototoro.ios.<team>.sync`), so the
/// names are looked up instead of written out. An entry under the running bundle identifier wins.
func permittedTaskIdentifier(_ suffix: String, excluding excluded: String? = nil) -> String {
    let bundle = Bundle.main.bundleIdentifier ?? "org.skepsun.kototoro.ios"
    let listed = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? []
    let matches = listed.filter { $0.hasSuffix("." + suffix) && $0 != excluded }
    return matches.first { $0.hasPrefix(bundle + ".") } ?? matches.first ?? bundle + "." + suffix
}

/// Runs [work] for a background task the system launched. Kotlin calls do not stop for Swift cancellation, so when
/// the system takes the time back the task is completed at once (as it requires) and the app is suspended mid-call;
/// the call goes on the next time the app runs, and work cut off here is picked up at its next turn.
@MainActor
func runBackgroundTask(_ task: BGTask, _ work: @escaping @MainActor () async -> Void) {
    var completed = false
    func complete(_ success: Bool) {
        guard !completed else { return }
        completed = true
        task.setTaskCompleted(success: success)
    }
    let running = Task { @MainActor in
        await work()
        complete(!Task.isCancelled)
    }
    task.expirationHandler = {
        DispatchQueue.main.async {
            running.cancel()
            complete(false)
        }
    }
}
