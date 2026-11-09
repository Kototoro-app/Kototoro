import BackgroundTasks
import KototoroShared
import SwiftUI
import UserNotifications

/// Android's scheduled favourite update checks, as far as iOS allows: when the app goes to the background a processing
/// task is requested no earlier than the chosen interval; when the system grants it (its own schedule, usually while
/// the phone is idle on a network, never guaranteed) favourites due at their release pace are checked and new
/// chapters announced with a local notification. A cold background launch opens the library and starts the extension
/// runtime on its own, without the UI.
@MainActor
final class BackgroundUpdates {
    static let shared = BackgroundUpdates()
    /// The app refresh earlier versions asked for: about thirty seconds, too short to start the runtime and check.
    /// A request left from before an update still launches it, so it only moves the check to the processing task.
    static let legacyIdentifier = "org.skepsun.kototoro.updates"
    /// The processing task; listed in `BGTaskSchedulerPermittedIdentifiers`.
    static let identifier = permittedTaskIdentifier("updates", excluding: legacyIdentifier)
    static let enabledKey = "updates_background_enabled"
    static let intervalKey = "updates_background_interval_hours"

    static var enabled: Bool { UserDefaults.standard.bool(forKey: enabledKey) }
    static var intervalHours: Int { max(1, UserDefaults.standard.object(forKey: intervalKey) as? Int ?? 6) }

    private var registered = false

    /// Must run before the app finishes launching, as the scheduler requires.
    func register() {
        guard !registered else { return }
        registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.identifier, using: .main) { task in
            MainActor.assumeIsolated { runBackgroundTask(task) { await BackgroundUpdates.shared.run() } }
        }
    }

    func schedule() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.legacyIdentifier)
        guard Self.enabled else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.identifier)
            return
        }
        let request = BGProcessingTaskRequest(identifier: Self.identifier)
        request.requiresNetworkConnectivity = true
        request.earliestBeginDate = Date(timeIntervalSinceNow: Double(Self.intervalHours) * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// The check itself; also run by the UI tests through `KOTOTORO_RUN_UPDATE_REFRESH`.
    func run() async {
        defer { schedule() }
        guard Self.enabled else { return }
        let library = LibraryModel.shared
        do { try library.open() } catch { return }
        guard let database = library.library, let runtime = await SourcesModel.shared.prepared(database),
              !Task.isCancelled,
              let report = await library.checkUpdatesReport(runtime, respectSchedule: true), report.newChapters > 0
        else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "作品有更新")
        content.body = String(localized: "\(report.withUpdates) 部收藏作品共有 \(report.newChapters) 个新章节")
        content.sound = .default
        try? await UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "updates.\(Int(Date().timeIntervalSince1970))", content: content, trigger: nil))
    }
}

/// Settings rows: the switch asks for notification permission, the interval is the earliest gap between checks.
struct BackgroundUpdateSettingsRows: View {
    @ObservedObject var library: LibraryModel
    @AppStorage(BackgroundUpdates.enabledKey) private var enabled = false
    @AppStorage(BackgroundUpdates.intervalKey) private var interval = 6
    @AppStorage("tracker_skip_completed") private var skipCompleted = false
    @AppStorage("tracker_mark_duplicates_read") private var markDuplicatesRead = false

    var body: some View {
        Toggle("后台检查更新", isOn: Binding(get: { enabled }, set: { value in
            enabled = value
            if value {
                Task { _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) }
            }
            BackgroundUpdates.shared.schedule()
        }))
        .accessibilityIdentifier("settings.backgroundUpdates")
        if enabled {
            Picker("最短间隔", selection: $interval) {
                Text("6 小时").tag(6)
                Text("12 小时").tag(12)
                Text("每天").tag(24)
            }
            .onChange(of: interval) { _, _ in BackgroundUpdates.shared.schedule() }
        }
        Toggle("跳过已完结作品", isOn: $skipCompleted)
            .accessibilityIdentifier("settings.trackerSkipCompleted")
        Toggle("重复章节标为已读", isOn: $markDuplicatesRead)
            .onChange(of: markDuplicatesRead) { _, value in
                library.library?.setMarkDuplicateChaptersRead(enabled: value)
            }
            .accessibilityIdentifier("settings.trackerMarkDuplicatesRead")
    }
}
