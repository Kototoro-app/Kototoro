import AuthenticationServices
import Combine
import KototoroShared
import Security
import SwiftUI

/// One account owner for all screens; refreshed credentials are saved after every account operation.
@MainActor
final class TrackingAccountsModel: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    static func isAuthorizationCallback(_ url: URL) -> Bool {
        url.scheme == "kototoro" && ["shikimori-auth", "anilist-auth", "mal-auth", "bangumi-auth", "simkl-auth"].contains(url.host ?? "")
    }
    @Published private(set) var accounts: [Int32: String] = [:]
    @Published private(set) var syncMessages: [String: String] = UserDefaults.standard.dictionary(forKey: "tracking.syncMessages") as? [String: String] ?? [:]
    @Published private(set) var syncFailures: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "tracking.syncFailures") ?? [])
    @Published private(set) var revision = 0
    private var api: IosTrackingAccounts?
    private(set) var tracking: IosTracking?
    private var authentication: ASWebAuthenticationSession?
    private var preparing: Task<Void, Never>?
    private var syncing: Set<String> = []
    private var accountIdentities: [Int32: String] = [:]

    func prepare(_ tracking: IosTracking?) async {
        if let preparing { await preparing.value; return }
        guard api == nil, let tracking else { return }
        self.tracking = tracking
        api = tracking.accounts
        let task = Task {
            for site in tracking.sites {
                do {
                    if let data = try TrackingKeychain.read(site.id), let value = String(data: data, encoding: .utf8) {
                        try await tracking.accounts.restore(serialized: value)
                    }
                } catch { syncMessages["account.\(site.id)"] = "无法读取账号，请重新连接：\(error.localizedDescription)" }
            }
            await refresh()
        }
        preparing = task
        await task.value
        preparing = nil
    }

    private func refresh() async {
        guard let api else { return }
        if let values = try? await api.accounts() {
            accounts = Dictionary(uniqueKeysWithValues: values.map { ($0.service, $0.username) })
            accountIdentities = Dictionary(uniqueKeysWithValues: values.map { ($0.service, $0.userId) })
            revision += 1
        }
    }

    func connect(_ site: IosTrackingSite, username: String, password: String, token: String) async throws {
        guard let api else { throw TrackingError.message("正在准备账号，请稍后重试") }
        if !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            try await api.connectToken(service: site.id, token: token)
        } else if site.id == 4 || site.id == 6 {
            try await api.login(service: site.id, username: username.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
        } else {
            let state = UUID().uuidString
            let verifier = UUID().uuidString.replacingOccurrences(of: "-", with: "") + UUID().uuidString.replacingOccurrences(of: "-", with: "")
            let address = try api.authorizationUrl(service: site.id, state: state, verifier: verifier)
            guard let url = URL(string: address) else { throw TrackingError.message("授权地址无效") }
            let callback = try await authenticate(url)
            let components = URLComponents(url: callback, resolvingAgainstBaseURL: false)
            let fields = components?.queryItems ?? []
            guard callback.scheme == "kototoro", callback.host == URLComponents(string: address)?.queryItems?
                .first(where: { $0.name == "redirect_uri" }).flatMap({ $0.value }).flatMap(URL.init(string:))?.host,
                  fields.first(where: { $0.name == "state" })?.value == state else {
                throw TrackingError.message("授权回调无法验证，请重新连接")
            }
            guard let code = fields.first(where: { $0.name == "code" })?.value, !code.isEmpty else {
                throw TrackingError.message("网站未完成授权，请重试")
            }
            try await api.authorize(service: site.id, code: code, verifier: verifier)
        }
        do { try await persist(site.id) }
        catch { try? await api.disconnect(service: site.id); await refresh(); throw error }
        await refresh()
    }

    func disconnect(_ service: Int32) async throws {
        try TrackingKeychain.write(nil, service)
        try await api?.disconnect(service: service)
        await refresh()
    }

    func automatic(_ service: Int32) -> Bool {
        guard let userID = accountIdentities[service] else { return false }
        return UserDefaults.standard.bool(forKey: "tracking.auto.\(service).\(userID)")
    }

    func setAutomatic(_ value: Bool, service: Int32) {
        guard let userID = accountIdentities[service] else { return }
        UserDefaults.standard.set(value, forKey: "tracking.auto.\(service).\(userID)")
        revision += 1
    }

    func progress(content: IosSourceContent, item: IosTrackingItem) async throws -> IosTrackingProgress {
        guard let api else { throw TrackingError.message("请先连接账号") }
        do {
            let result = try await api.progress(content: content, item: item)
            try await persist(item.service)
            return result
        } catch { try? await persist(item.service); throw error }
    }

    func update(content: IosSourceContent, item: IosTrackingItem, status: String, progress: Int32, score: Float, note: String) async throws {
        guard let api else { throw TrackingError.message("请先连接账号") }
        do {
            _ = try await api.update(content: content, item: item, status: status, progress: progress, score: score, note: note)
            try await persist(item.service)
            syncMessages[content.id] = "已同步到 \(item.siteTitle)"
            syncFailures.remove(content.id)
            saveSyncState()
            revision += 1
        } catch { try? await persist(item.service); throw error }
    }

    func collection(service: Int32, kind: String, status: String, page: Int32) async throws -> [IosTrackingCollectionItem] {
        guard let api else { throw TrackingError.message("请先连接账号") }
        do {
            let result = try await api.collection(service: service, mediaKind: kind, status: status, page: page)
            try await persist(service)
            return result
        } catch { try? await persist(service); throw error }
    }

    func syncAfterReading(_ content: IosSourceContent, tracking: IosTracking?) async {
        await prepare(tracking)
        guard let api, !syncing.contains(content.id) else { return }
        let services = accounts.keys.filter { automatic($0) }
        guard !services.isEmpty else { return }
        syncing.insert(content.id)
        defer { syncing.remove(content.id) }
        var completed: [String] = []
        var failed: [String] = []
        for service in services {
            do {
                if try await api.syncProgress(content: content, service: service).boolValue {
                    completed.append(tracking?.sites.first(where: { $0.id == service })?.title ?? "网站")
                }
                try await persist(service)
            } catch {
                try? await persist(service)
                failed.append("\(tracking?.sites.first(where: { $0.id == service })?.title ?? "网站")：\(error.localizedDescription)")
            }
        }
        if !failed.isEmpty {
            syncMessages[content.id] = "进度尚未同步。" + failed.joined(separator: "；")
            syncFailures.insert(content.id)
        } else {
            syncFailures.remove(content.id)
            if !completed.isEmpty { syncMessages[content.id] = "进度已同步到 " + completed.joined(separator: "、") }
        }
        saveSyncState()
    }

    private func saveSyncState() {
        UserDefaults.standard.set(syncMessages, forKey: "tracking.syncMessages")
        UserDefaults.standard.set(Array(syncFailures), forKey: "tracking.syncFailures")
    }

    private func persist(_ service: Int32) async throws {
        guard let api else { return }
        let value = try await api.serialized(service: service)
        try TrackingKeychain.write(value?.data(using: .utf8), service)
    }

    private func authenticate(_ url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "kototoro") { [weak self] callback, error in
                Task { @MainActor in self?.authentication = nil }
                if let callback { continuation.resume(returning: callback) }
                else { continuation.resume(throwing: error ?? TrackingError.message("授权未完成")) }
            }
            session.presentationContextProvider = self
            authentication = session
            if !session.start() {
                authentication = nil
                continuation.resume(throwing: TrackingError.message("无法打开授权页面"))
            }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}

private enum TrackingError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let value) = self { value } else { nil } }
}

private enum TrackingKeychain {
    private static func query(_ service: Int32) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "org.skepsun.kototoro.tracking",
         kSecAttrAccount as String: String(service)]
    }

    static func read(_ service: Int32) throws -> Data? {
        var fields = query(service)
        fields[kSecReturnData as String] = true
        fields[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(fields as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw TrackingError.message("无法访问安全存储（\(status)）") }
        return result as? Data
    }

    static func write(_ data: Data?, _ service: Int32) throws {
        let fields = query(service)
        guard let data else {
            let status = SecItemDelete(fields as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw TrackingError.message("无法移除安全凭据（\(status)）") }
            return
        }
        let values: [String: Any] = [kSecValueData as String: data,
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(fields as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(fields.merging(values) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { throw TrackingError.message("无法保存安全凭据（\(status)）") }
    }
}

struct TrackingAccountView: View {
    let site: IosTrackingSite
    @ObservedObject var model: TrackingAccountsModel
    @State private var username = ""
    @State private var password = ""
    @State private var token = ""
    @State private var advanced = false
    @State private var busy = false
    @State private var error: String?
    @State private var disconnecting = false

    var body: some View {
        Form {
            Section {
                Label(site.title, systemImage: "person.crop.circle.badge.checkmark").font(.title2.weight(.semibold))
                Text("连接后，可在作品详情关联条目、同步阅读或观看进度，并管理状态和评分。")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            if let name = model.accounts[site.id] {
                Section {
                    LabeledContent("账号", value: name)
                    NavigationLink {
                        TrackingCollectionView(site: site, model: model)
                    } label: { Label("我的网站列表", systemImage: "list.bullet.rectangle") }
                        .accessibilityIdentifier("tracking.collection")
                    Toggle("阅读后自动同步进度", isOn: Binding(get: { model.automatic(site.id) },
                                                          set: { model.setAutomatic($0, service: site.id) }))
                        .accessibilityIdentifier("tracking.automatic")
                } header: { Text("已连接") }
                footer: { Text("仅同步已明确开启追踪的作品。网站状态、评分与短评保持原有设置；失败时在作品详情显示重试入口。") }
                Section {
                    Button("断开账号连接", role: .destructive) { disconnecting = true }.disabled(busy)
                }
            } else {
                if site.id == 4 || site.id == 6 {
                    Section {
                        TextField(site.id == 4 ? "邮箱或用户名" : "用户名", text: $username)
                            .textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField("密码", text: $password).textContentType(.password)
                    } header: { Text("登录 \(site.title)") }
                    footer: { Text("登录请求直接发送到网站，应用仅将授权凭据保存在此设备的钥匙串中。") }
                }
                Section {
                    Button {
                        Task { await connect() }
                    } label: {
                        HStack { Text(busy ? "正在连接…" : (site.id == 4 || site.id == 6 ? "登录" : "在浏览器中授权")); Spacer(); if busy { ProgressView() } }
                    }
                    .disabled(busy || ((site.id == 4 || site.id == 6) && (username.isEmpty || password.isEmpty) && token.isEmpty))
                    .accessibilityIdentifier("tracking.connect")
                }
                Section {
                    DisclosureGroup("使用访问令牌连接", isExpanded: $advanced) {
                        SecureField("访问令牌", text: $token).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .accessibilityIdentifier("tracking.token")
                        Text("已有个人访问令牌时可直接连接。Bangumi 可在个人设置中创建；其他网站需使用有效的 API 访问令牌。")
                            .font(.caption).foregroundStyle(.secondary)
                        if site.id == 5 {
                            Link("创建 Bangumi 个人访问令牌", destination: URL(string: "https://next.bgm.tv/demo/access-token")!)
                        }
                        Button("验证并连接") { Task { await connect() } }.disabled(token.isEmpty || busy)
                    }
                }
            }
            if let error { Section { Text(error).foregroundStyle(.red).textSelection(.enabled) } }
        }
        .navigationTitle(site.title).navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(busy)
        .confirmationDialog("断开 \(site.title) 账号？", isPresented: $disconnecting, titleVisibility: .visible) {
            Button("断开连接", role: .destructive) {
                Task {
                    busy = true; defer { busy = false }
                    do { try await model.disconnect(site.id) } catch { self.error = error.localizedDescription }
                }
            }
        } message: { Text("网站收藏和本地关联资料会保留。重新连接后可继续追踪。") }
        .onDisappear { password = ""; token = "" }
    }

    private func connect() async {
        busy = true; error = nil
        defer { busy = false; password = ""; token = "" }
        do { try await model.connect(site, username: username, password: password, token: token) }
        catch let authError as ASWebAuthenticationSessionError where authError.code == .canceledLogin { }
        catch { self.error = error.localizedDescription }
    }
}

private struct TrackingCollectionView: View {
    let site: IosTrackingSite
    @ObservedObject var model: TrackingAccountsModel
    @State private var kind = "MANGA"
    @State private var status = "READING"
    @State private var results: [IosTrackingCollectionItem] = []
    @State private var loading = false
    @State private var page: Int32 = 0
    @State private var hasMore = false
    @State private var error: String?
    @State private var preview: IosTrackingItem?
    @State private var request: UUID?
    private var identity: String { "\(kind)|\(status)" }

    var body: some View {
        List {
            Section {
                if site.id != 7 && site.id != 6 {
                    Picker("作品类型", selection: $kind) {
                        Text("漫画 / 小说").tag("MANGA")
                        Text("动画").tag("VIDEO")
                    }
                }
                if site.id == 7 {
                    Picker("影视类型", selection: $kind) {
                        Text("动画").tag("VIDEO")
                        Text("剧集").tag("VIDEO_TV")
                        Text("电影").tag("VIDEO_MOVIE")
                    }
                }
                Picker("状态", selection: $status) {
                    Text("想读 / 想看").tag("PLANNED")
                    Text("在读 / 在看").tag("READING")
                    Text("已完成").tag("COMPLETED")
                    Text("搁置").tag("ON_HOLD")
                    Text("放弃").tag("DROPPED")
                    if site.id == 1 || site.id == 2 { Text("重读 / 重看").tag("RE_READING") }
                }
            }
            Section {
                ForEach(results, id: \.item.key) { entry in
                    Button { preview = entry.item } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(entry.item.title).foregroundStyle(.primary)
                            Text("已\(kind == "VIDEO" ? "看" : "读") \(entry.progress) \(kind == "VIDEO" ? "集" : "章")")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if loading { ProgressView("正在读取网站列表…") }
                else if let error {
                    Text(error).foregroundStyle(.red)
                    Button("重试") { Task { await load(reset: page == 0) } }
                } else if results.isEmpty {
                    ContentUnavailableView("这个列表还是空的", systemImage: "list.bullet.rectangle",
                                           description: Text("切换状态查看其他作品，或在作品详情中开始追踪。"))
                } else if hasMore { Button("加载更多") { Task { await load(reset: false) } } }
            } footer: { Text("列表来自 \(site.title)。点开条目可关联到本地收藏或最近阅读的作品。") }
        }
        .navigationTitle("我的 \(site.title) 列表").navigationBarTitleDisplayMode(.inline)
        .task { if site.id == 7 { kind = "VIDEO" } }
        .task(id: identity) { await load(reset: true) }
        .refreshable { await load(reset: true) }
        .sheet(isPresented: Binding(get: { preview != nil }, set: { if !$0 { preview = nil } })) {
            if let preview, let tracking = model.tracking {
                TrackingItemPreview(item: preview, mediaKind: preview.mediaKind, tracking: tracking, content: nil, onBound: {})
            }
        }
    }

    private func load(reset: Bool) async {
        guard site.supports(mediaKind: kind.hasPrefix("VIDEO") ? "VIDEO" : kind), reset || !loading else { return }
        let token = UUID()
        request = token; loading = true; error = nil
        if reset { results = []; page = 0; hasMore = false }
        defer { if request == token { loading = false } }
        do {
            let next = page + 1
            let items = try await model.collection(service: site.id, kind: kind, status: status, page: next)
            guard !Task.isCancelled, request == token else { return }
            var keys = Set(results.map { $0.item.key })
            results += items.filter { keys.insert($0.item.key).inserted }
            page = next; hasMore = items.count >= 20
        } catch { if !Task.isCancelled, request == token { self.error = error.localizedDescription } }
    }
}

struct TrackingProgressView: View {
    let content: IosSourceContent
    let item: IosTrackingItem
    let tracking: IosTracking
    @ObservedObject var model: TrackingAccountsModel
    var onSaved: () -> Void = {}
    @Environment(\.dismiss) private var dismiss
    @State private var status = "PLANNED"
    @State private var progress = "0"
    @State private var score: Double = 0
    @State private var note = ""
    @State private var loading = true
    @State private var loaded = false
    @State private var exists = false
    @State private var tracked = false
    @State private var stopping = false
    @State private var saving = false
    @State private var error: String?
    @State private var local: Int32 = 0

    private var site: IosTrackingSite? { tracking.sites.first { $0.id == item.service } }
    private var scoreStep: Double { item.service == 2 ? 0.1 : item.service == 4 ? 0.5 : 1 }
    private var isMovie: Bool { item.service == 7 && item.remoteType == "movies" }
    private var validProgress: Int32? {
        if isMovie { return status == "COMPLETED" ? 1 : 0 }
        return Int32(progress).flatMap { (0...100_000).contains($0) ? $0 : nil }
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("网站", value: item.siteTitle)
                Text(item.title).font(.headline)
                if loading { ProgressView("正在读取网站记录…") }
            }
            if model.accounts[item.service] == nil, let site {
                Section {
                    NavigationLink { TrackingAccountView(site: site, model: model) } label: { Label("连接 \(item.siteTitle) 账号", systemImage: "person.badge.key") }
                } footer: { Text("关联作品资料无需登录；同步进度需要连接账号。") }
            } else if loaded {
                Section {
                    Picker("状态", selection: $status) {
                        Text(content.mediaKind == "VIDEO" ? "想看" : "想读").tag("PLANNED")
                        Text(content.mediaKind == "VIDEO" ? "在看" : "在读").tag("READING")
                        Text(content.mediaKind == "VIDEO" ? "看完" : "读完").tag("COMPLETED")
                        Text("搁置").tag("ON_HOLD")
                        Text("放弃").tag("DROPPED")
                        if item.service == 1 || item.service == 2 { Text("重读 / 重看").tag("RE_READING") }
                    }
                    if !isMovie { HStack {
                        Text(content.mediaKind == "VIDEO" ? "已看集数" : "已读章节")
                        TextField("0", text: $progress).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                            .accessibilityIdentifier("tracking.progress")
                    } }
                    if local > 0 && !isMovie {
                        Button("使用本地进度 · 第 \(local) \(content.mediaKind == "VIDEO" ? "集" : "章")") { progress = String(local) }
                            .accessibilityIdentifier("tracking.useLocalProgress")
                    }
                    Stepper(value: $score, in: 0...10, step: scoreStep) {
                        LabeledContent("我的评分", value: score == 0 ? "未评分" :
                            "\(score.formatted(.number.precision(.fractionLength(0...1)))) / 10")
                    }
                    if site?.supportsNotes == true { TextField("短评 / 备注", text: $note, axis: .vertical).lineLimit(3...6) }
                    if exists && !tracked {
                        Text("已读取网站上的现有记录。开始追踪后，将此记录关联到当前本地作品。")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } header: { Text("网站追踪记录") }
                footer: { Text("保存后写入 \(item.siteTitle) 的账号列表。\(item.service == 7 ? "SIMKL 当前仅支持增加已看集数。" : "")") }
                Section {
                    Button(saving ? "正在同步…" : (tracked ? "保存并同步" : "开始追踪")) { Task { await save() } }
                        .disabled(saving || validProgress == nil)
                        .accessibilityIdentifier("tracking.saveProgress")
                }
                if tracked {
                    Section {
                        Button("停止此作品的追踪", role: .destructive) { stopping = true }.disabled(saving)
                    } footer: { Text("停止后保留网站列表与本地作品资料，不再自动同步此作品。") }
                }
            }
            if let error {
                Section {
                    Text(error).foregroundStyle(.red)
                    if !loaded { Button("重新读取") { Task { await load() } } }
                }
            }
        }
        .navigationTitle("追踪进度").navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(saving)
        .task(id: model.accounts[item.service]) { await load() }
        .interactiveDismissDisabled(saving)
        .confirmationDialog("停止此作品的追踪？", isPresented: $stopping, titleVisibility: .visible) {
            Button("停止追踪", role: .destructive) {
                Task {
                    saving = true; defer { saving = false }
                    do { try await tracking.accounts.stopTracking(content: content, service: item.service); onSaved(); dismiss() }
                    catch { self.error = error.localizedDescription }
                }
            }
        }
    }

    private func load() async {
        guard model.accounts[item.service] != nil else { loading = false; return }
        loading = true; loaded = false; error = nil
        defer { loading = false }
        do {
            let value = try await model.progress(content: content, item: item)
            guard !Task.isCancelled else { return }
            status = value.status; progress = String(value.progress); score = Double(value.score); note = value.note
            exists = value.exists; loaded = true
            tracked = (try? await tracking.accounts.isTracked(content: content, item: item))?.boolValue ?? false
            local = (try? await tracking.accounts.localProgress(content: content))?.int32Value ?? 0
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }

    private func save() async {
        guard let progress = validProgress else { return }
        saving = true; error = nil
        defer { saving = false }
        do {
            try await model.update(content: content, item: item, status: status, progress: progress, score: Float(score), note: note)
            onSaved(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

struct TrackingSyncBanner: View {
    let content: IosSourceContent
    let tracking: IosTracking?
    @ObservedObject var model: TrackingAccountsModel
    var body: some View {
        if let message = model.syncMessages[content.id] {
            VStack(alignment: .leading, spacing: 6) {
                Label(message, systemImage: model.syncFailures.contains(content.id) ? "exclamationmark.arrow.trianglehead.2.clockwise.rotate.90" : "checkmark.icloud")
                    .font(.caption).foregroundStyle(.secondary)
                if model.syncFailures.contains(content.id) {
                    Button("重试同步") { Task { await model.syncAfterReading(content, tracking: tracking) } }.font(.caption)
                }
            }
            .accessibilityIdentifier("tracking.syncStatus")
        }
    }
}
