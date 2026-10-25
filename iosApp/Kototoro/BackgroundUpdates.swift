import BackgroundTasks
import KototoroShared
import SwiftUI
import UserNotifications

/// Android's scheduled favourite update checks, as far as iOS allows: when the app goes to the background an app
/// refresh is requested no earlier than the chosen interval; when the system grants it (its own schedule, never
/// guaranteed) and the app's state is still alive, favourites are checked and new chapters announced with a local
/// notification. A cold background launch has no extension runtime, so it only schedules the next request.
@MainActor
final class BackgroundUpdates {
    static let shared = BackgroundUpdates()
    static let identifier = "org.skepsun.kototoro.updates"
    static let enabledKey = "updates_background_enabled"
    static let intervalKey = "updates_background_interval_hours"

    weak var library: LibraryModel?
    weak var sources: SourcesModel?

    static var enabled: Bool { UserDefaults.standard.bool(forKey: enabledKey) }
    static var intervalHours: Int { max(1, UserDefaults.standard.object(forKey: intervalKey) as? Int ?? 6) }

    func schedule() {
        guard Self.enabled else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.identifier)
            return
        }
        let request = BGAppRefreshTaskRequest(identifier: Self.identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: Double(Self.intervalHours) * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// The refresh itself; also run by the UI tests through `KOTOTORO_RUN_UPDATE_REFRESH`.
    func run() async {
        schedule()
        guard Self.enabled, let library, let runtime = sources?.runtime,
              let report = await library.checkUpdatesReport(runtime), report.newChapters > 0 else { return }
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
    @AppStorage(BackgroundUpdates.enabledKey) private var enabled = false
    @AppStorage(BackgroundUpdates.intervalKey) private var interval = 6

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
    }
}
