import KototoroShared
import Security
import SwiftUI

/// Android's WebDAV backup settings on iOS. Keys are Android's; the password goes to the Keychain instead of plain
/// preferences, since anyone with the device backup could otherwise read it.
enum WebDavConfig {
    static let serverKey = "webdav_server_url"
    static let pathKey = "webdav_remote_path"
    static let userKey = "webdav_username"
    static let maxCountKey = "webdav_remote_max_count"
    static let intervalKey = "webdav_auto_interval_hours"
    static let lastUploadKey = "webdav_last_upload"
    static let lastFailureKey = "webdav_last_failure"

    private static let keychain: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "org.skepsun.kototoro.webdav",
        kSecAttrAccount as String: "password",
    ]

    static var password: String {
        get {
            var query = keychain
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: CFTypeRef?
            guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
                  let data = result as? Data else { return "" }
            return String(decoding: data, as: UTF8.self)
        }
        set {
            SecItemDelete(keychain as CFDictionary)
            guard !newValue.isEmpty else { return }
            var item = keychain
            item[kSecValueData as String] = Data(newValue.utf8)
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(item as CFDictionary, nil)
        }
    }

    static var current: Core_libraryWebDavSettings {
        let defaults = UserDefaults.standard
        return IosWebDav.companion.settings(
            serverUrl: defaults.string(forKey: serverKey) ?? "", remotePath: defaults.string(forKey: pathKey) ?? "",
            username: defaults.string(forKey: userKey) ?? "", password: password,
            maxCount: Int32(defaults.object(forKey: maxCountKey) as? Int ?? 3),
            intervalHours: Int32(defaults.integer(forKey: intervalKey)))
    }

    static func timestamp(_ date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: date)
    }

    /// Uploads when the interval since the last successful upload has passed; a failure waits ten minutes before
    /// trying again, so an unreachable server is not hit on every return to the app.
    @MainActor
    static func backUpIfDue(_ library: IosLibrary?) async {
        guard let library else { return }
        let settings = current
        let now = Date().timeIntervalSince1970
        let last = UserDefaults.standard.object(forKey: lastUploadKey) as? Double
        let failed = UserDefaults.standard.double(forKey: lastFailureKey)
        guard settings.isDue(lastUploadAt: last.map { KotlinLong(value: Int64($0 * 1000)) }, now: Int64(now * 1000)),
              now - failed > 600 else { return }
        do {
            _ = try await library.webDav.upload(settings: settings, timestamp: timestamp())
            UserDefaults.standard.set(now, forKey: lastUploadKey)
        } catch {
            UserDefaults.standard.set(now, forKey: lastFailureKey)
        }
    }
}

struct WebDavSyncView: View {
    @EnvironmentObject private var library: LibraryModel
    @AppStorage(WebDavConfig.serverKey) private var server = ""
    @AppStorage(WebDavConfig.pathKey) private var path = ""
    @AppStorage(WebDavConfig.userKey) private var user = ""
    @AppStorage(WebDavConfig.maxCountKey) private var maxCount = 3
    @AppStorage(WebDavConfig.intervalKey) private var interval = 0
    @AppStorage(WebDavConfig.lastUploadKey) private var lastUpload = 0.0
    @State private var password = WebDavConfig.password
    @State private var files: [IosWebDavFile] = []
    @State private var busy = false
    @State private var message: String?
    @State private var error: String?
    @State private var preview: IosBackupPreview?

    private var webDav: IosWebDav? { library.library?.webDav }

    var body: some View {
        Form {
            Section {
                TextField("服务器地址", text: $server, prompt: Text("https://dav.example.com"))
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    .accessibilityIdentifier("webdav.server")
                TextField("远程目录", text: $path, prompt: Text("kototoro"))
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("webdav.path")
                TextField("用户名", text: $user)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("webdav.user")
                SecureField("密码", text: $password)
                    .onChange(of: password) { _, value in WebDavConfig.password = value }
                    .accessibilityIdentifier("webdav.password")
            } header: { Text("服务器") } footer: { Text("密码保存在钥匙串中。备份文件名与 Android、Windows 相同，可互相恢复。") }
            Section("备份") {
                Stepper("云端保留 \(maxCount == 0 ? String(localized: "全部") : "\(maxCount)") 份", value: $maxCount, in: 0...20)
                Picker("自动备份", selection: $interval) {
                    Text("关闭").tag(0)
                    Text("每 6 小时").tag(6)
                    Text("每天").tag(24)
                    Text("每周").tag(168)
                }
                .accessibilityIdentifier("webdav.interval")
                if lastUpload > 0 {
                    LabeledContent("上次上传", value: Date(timeIntervalSince1970: lastUpload).formatted(date: .abbreviated, time: .shortened))
                }
                Button("测试连接", systemImage: "network") { Task { await test() } }
                    .disabled(server.isEmpty || busy)
                    .accessibilityIdentifier("webdav.test")
                Button("立即上传", systemImage: "icloud.and.arrow.up") { Task { await upload() } }
                .disabled(server.isEmpty || busy)
                .accessibilityIdentifier("webdav.upload")
            }
            if let message { Text(message).accessibilityIdentifier("webdav.message") }
            if let error { Text(error).foregroundStyle(.red).accessibilityIdentifier("webdav.error") }
            if busy { ProgressView().frame(maxWidth: .infinity) }
            if let pending = preview {
                Section {
                    Text("“\(pending.filename)”：\(pending.counts.map { "\($0.count)" }.joined(separator: " / ")) 条记录，以合并方式恢复。")
                        .font(.footnote)
                    Button("合并恢复", systemImage: "arrow.triangle.merge") { Task { await merge(pending) } }
                    .accessibilityIdentifier("webdav.restore")
                    Button("取消", role: .cancel) { preview = nil }
                }
            }
            Section {
                ForEach(files, id: \.name) { file in
                    Button { Task { await open(file) } } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(file.name).font(.footnote.monospaced()).foregroundStyle(.primary)
                            Text("\(Date(timeIntervalSince1970: Double(file.modifiedAt) / 1000).formatted(date: .abbreviated, time: .shortened)) · \(ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("webdav.file")
                }
                Button("刷新列表", systemImage: "arrow.clockwise") { Task { await refresh() } }
                .disabled(server.isEmpty || busy)
            } header: { Text("云端备份") } footer: { Text("点选一份备份查看内容并合并恢复。") }
        }
        .navigationTitle("WebDAV 备份")
    }

    private func begin() -> IosWebDav? {
        guard let webDav else { return nil }
        busy = true; error = nil; message = nil
        return webDav
    }

    private func test() async {
        guard let webDav = begin() else { return }
        defer { busy = false }
        do {
            try await webDav.probe(settings: WebDavConfig.current)
            message = String(localized: "连接成功")
        } catch { self.error = error.localizedDescription }
    }

    private func upload() async {
        guard let webDav = begin() else { return }
        defer { busy = false }
        do {
            let result = try await webDav.upload(settings: WebDavConfig.current, timestamp: WebDavConfig.timestamp())
            lastUpload = Date().timeIntervalSince1970
            message = String(localized: "已上传 \(result.name)")
            files = try await webDav.list(settings: WebDavConfig.current)
        } catch { self.error = error.localizedDescription }
    }

    private func refresh() async {
        guard let webDav = begin() else { return }
        defer { busy = false }
        do { files = try await webDav.list(settings: WebDavConfig.current) }
        catch { self.error = error.localizedDescription }
    }

    /// Downloads a backup and previews it; the preview keeps the archive in memory, so the file goes at once.
    private func open(_ file: IosWebDavFile) async {
        guard let webDav = begin(), let database = library.library else { busy = false; return }
        defer { busy = false }
        do {
            let path = try await webDav.download(settings: WebDavConfig.current, name: file.name)
            defer { try? FileManager.default.removeItem(atPath: path) }
            preview = try await database.previewBackup(path: path)
        } catch { self.error = error.localizedDescription }
    }

    private func merge(_ archive: IosBackupPreview) async {
        guard begin() != nil, let database = library.library else { busy = false; return }
        defer { busy = false }
        do {
            let restored = try await database.restoreBackup(preview: archive)
            preview = nil
            message = String(localized: "已合并 \(restored) 条记录")
            await library.refresh()
        } catch { self.error = error.localizedDescription }
    }
}
