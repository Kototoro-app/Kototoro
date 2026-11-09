import AuthenticationServices
import Combine
import KototoroShared
import SwiftUI
import UserNotifications

@MainActor
final class GoogleDriveSyncModel: ObservableObject {
    @Published private(set) var email: String?
    @Published private(set) var busy = false
    @Published private(set) var message: String?
    @Published private(set) var lastSync: Date?
    @Published private(set) var needsAuthorization = false
    private let authorization = GoogleDriveAuthorization()
    private var credential: GoogleDriveCredential?
    private var lastAttempt = Date.distantPast
    private let defaults = UserDefaults.standard
    static let onStartKey = "googleDrive.syncOnStart"
    /// Hours between background syncs (Android's periodic sync); 0 turns them off.
    static let backgroundIntervalKey = "googleDrive.backgroundIntervalHours"

    /// Who asked: a tap may ask for continued processing; the launch sync follows its switch; the scheduled one
    /// runs in the system's background time and tells the user when it needs them.
    enum Trigger { case manual, launch, schedule }

    var connected: Bool { credential != nil && !needsAuthorization }

    var configured: Bool { GoogleDriveOAuth.configured != nil }

    init() {
        do {
            let saved = try GoogleDriveKeychain.read()
            if saved?.clientID == GoogleDriveOAuth.configured?.clientID {
                credential = saved
                email = saved?.email
                lastSync = defaults.object(forKey: "googleDrive.lastSync") as? Date
            }
        } catch { message = error.localizedDescription }
    }

    func connect() async {
        guard !busy, let configuration = GoogleDriveOAuth.configured else { return }
        busy = true
        message = nil
        defer { busy = false }
        do {
            let saved = try await authorization.connect(configuration)
            try GoogleDriveKeychain.write(saved)
            credential = saved
            email = saved.email
            needsAuthorization = false
            lastSync = nil
            defaults.removeObject(forKey: "googleDrive.lastSync")
            message = String(localized: "已连接。点击“立即同步”合并此设备与云端的数据。")
            BackgroundSync.shared.schedule()
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
        } catch is CancellationError {
        } catch { message = error.localizedDescription }
    }

    func disconnect() {
        guard !busy else { return }
        do {
            try GoogleDriveKeychain.write(nil)
            credential = nil
            email = nil
            lastSync = nil
            needsAuthorization = false
            defaults.removeObject(forKey: "googleDrive.lastSync")
            defaults.set(false, forKey: Self.onStartKey)
            BackgroundSync.shared.schedule()
            message = String(localized: "已断开连接，本地与云端数据均保留")
        } catch { message = error.localizedDescription }
    }

    func sync(_ model: LibraryModel, trigger: Trigger = .manual) async {
        guard !busy, credential != nil, !needsAuthorization, let library = model.library else { return }
        if trigger == .launch && (!defaults.bool(forKey: Self.onStartKey) || Date().timeIntervalSince(lastAttempt) < 60) { return }
        busy = true
        lastAttempt = Date()
        message = String(localized: "正在合并 Google Drive 数据…")
        defer { busy = false }
        do {
            // Sync on launch is not a tap: only a tap may ask for continued processing.
            let result = try await ContinuedWork.run("google-drive-sync", title: String(localized: "正在同步 Google Drive"),
                                                     userInitiated: trigger == .manual) { _ in
                var token = try await accessToken(forceRefresh: false)
                var result = try await library.googleDriveSync.sync(accessToken: token, deviceId: deviceID)
                if result.authorizationRequired {
                    token = try await accessToken(forceRefresh: true)
                    result = try await library.googleDriveSync.sync(accessToken: token, deviceId: deviceID)
                    if result.authorizationRequired { throw GoogleDriveError.authorizationRequired }
                }
                return result
            }
            message = result.message
            // Non-authorization failures (e.g. missing Drive scope) must not advance the success timestamp.
            if result.success {
                lastSync = Date()
                defaults.set(lastSync, forKey: "googleDrive.lastSync")
            }
        } catch GoogleDriveError.authorizationRequired {
            needsAuthorization = true
            message = GoogleDriveError.authorizationRequired.localizedDescription
            // Nobody is looking at the page: say so once, since background syncs stop until the account reconnects.
            if trigger == .schedule { await Self.notifyAuthorizationNeeded() }
        } catch is CancellationError {
            message = String(localized: "同步已取消，可稍后重试")
        } catch { message = error.localizedDescription }
        // Downloads may already have been applied even if the subsequent upload failed.
        await model.refreshAfterMerge()
    }

    private static func notifyAuthorizationNeeded() async {
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Google Drive 同步已暂停")
        content.body = String(localized: "Google 授权已失效，打开 Kototoro 重新连接账号后继续同步。")
        try? await UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "googleDrive.authorization", content: content, trigger: nil))
    }

    private var deviceID: String {
        if let value = defaults.string(forKey: "googleDrive.deviceID") { return value }
        let value = "ios-" + UUID().uuidString
        defaults.set(value, forKey: "googleDrive.deviceID")
        return value
    }

    private func accessToken(forceRefresh: Bool) async throws -> String {
        guard let saved = credential else { throw GoogleDriveError.authorizationRequired }
        if !forceRefresh && saved.expiresAt.timeIntervalSinceNow > 60 { return saved.accessToken }
        let refreshed = try await authorization.refresh(saved)
        try GoogleDriveKeychain.write(refreshed)
        credential = refreshed
        return refreshed.accessToken
    }
}

struct GoogleDriveSyncView: View {
    @ObservedObject var model: GoogleDriveSyncModel
    @EnvironmentObject private var library: LibraryModel
    @AppStorage(GoogleDriveSyncModel.onStartKey) private var syncOnStart = false
    @AppStorage(GoogleDriveSyncModel.backgroundIntervalKey) private var backgroundInterval = 0

    var body: some View {
        Form {
            Section {
                if let email = model.email { LabeledContent("Google 账号", value: email) }
                if model.email == nil || model.needsAuthorization {
                    Button(model.needsAuthorization ? "重新连接 Google 账号" : "连接 Google 账号") {
                        Task { await model.connect() }
                    }.disabled(!model.configured || model.busy)
                        .accessibilityIdentifier("googleDrive.connect")
                }
                if model.email != nil {
                    Button("立即同步") { Task { await model.sync(library) } }
                        .disabled(model.busy || model.needsAuthorization || library.library == nil)
                        .accessibilityIdentifier("googleDrive.sync")
                    Toggle("打开应用时同步", isOn: $syncOnStart).disabled(model.busy)
                    Picker("后台定期同步", selection: Binding(get: { backgroundInterval }, set: { value in
                        backgroundInterval = value
                        // Only a failed authorization is announced, and only if the user allows notifications.
                        if value > 0 {
                            Task { _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) }
                        }
                        BackgroundSync.shared.schedule()
                    })) {
                        Text("关闭").tag(0)
                        Text("每 6 小时").tag(6)
                        Text("每 12 小时").tag(12)
                        Text("每天").tag(24)
                    }
                    .disabled(model.busy)
                    .accessibilityIdentifier("googleDrive.backgroundInterval")
                    Button("断开连接", role: .destructive) { model.disconnect() }.disabled(model.busy)
                }
            } footer: {
                Text("使用与安卓相同的 Google 账号，双向合并收藏、分类、阅读进度、阅读统计和更新记录。首次同步会合并已有数据。后台定期同步的时机由系统决定，通常在设备空闲且联网时进行，间隔只是最短间隔。")
            }
            Section {
                if model.busy { ProgressView("正在处理…") }
                if let date = model.lastSync { LabeledContent("上次同步") { Text(date, format: .dateTime) } }
                if let message = model.message { Text(message).accessibilityIdentifier("googleDrive.status") }
                if !model.configured {
                    Text("此构建尚未配置 Google 登录，请使用已配置 Google Drive 的版本。")
                        .accessibilityIdentifier("googleDrive.unconfigured")
                }
            }
            Section {
                Text("作品来源未安装时仍会保留收藏和进度，可在“缺失来源”中处理。")
                Text("本轮同步不安装扩展、不同步应用设置或本地媒体文件；云端已有的安卓源配置和扩展数据会保留。")
            }
        }
        .navigationTitle("Google Drive 同步")
    }
}
