import KototoroShared
import Security
import SwiftUI

struct CommunityDetailsSection: View {
    let community: IosCommunityDetails
    let content: IosSourceContent
    @State private var overview: IosCommunityOverview?
    @State private var unavailable = false
    @State private var loading = true
    @State private var showingComments = false
    @State private var showingRating = false
    @State private var myRating: Int32 = 0

    var body: some View {
        Section("社区") {
            if loading {
                ProgressView("正在读取社区评分…")
            } else if unavailable {
                Label("暂时无法读取社区评分", systemImage: "exclamationmark.circle")
                    .foregroundStyle(.secondary)
            } else if let overview {
                CommunityRatingSummaryRow(overview: overview)
                    .accessibilityIdentifier("details.community.rating")
                Button {
                    showingRating = true
                } label: {
                    HStack {
                        Text("我的评分").foregroundStyle(Color.primary)
                        Spacer()
                        if myRating > 0 {
                            CommunityStars(value: Double(myRating) / 2, size: 15)
                            Text(verbatim: "\(myRating)").monospacedDigit().foregroundStyle(.secondary)
                        } else {
                            Text("点按评分")
                        }
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("details.community.rate")
                Button {
                    showingComments = true
                } label: {
                    Label("社区评论", systemImage: "bubble.left.and.bubble.right")
                }
                .badge(Int(overview.commentCount))
                .accessibilityIdentifier("details.community.comments")
            }
        }
        .task(id: content.contentKey + "|" + content.title) {
            loading = true
            unavailable = false
            overview = nil
            do {
                let result = try await community.overview(content: content)
                guard !Task.isCancelled else { return }
                overview = result
                myRating = (try? await community.myRating(content: content))?.int32Value ?? 0
            } catch {
                guard !Task.isCancelled else { return }
                unavailable = true
            }
            loading = false
        }
        .sheet(isPresented: $showingComments) {
            CommunityCommentsView(community: community, content: content)
        }
        .sheet(isPresented: $showingRating, onDismiss: {
            Task { myRating = (try? await community.myRating(content: content))?.int32Value ?? 0 }
        }) {
            CommunityRatingView(community: community, content: content, stars: myRating > 0 ? myRating : 0)
        }
    }
}

/// The score, its stars and count beside how the ratings spread over one to five stars, as in the App Store.
private struct CommunityRatingSummaryRow: View {
    let overview: IosCommunityOverview

    var body: some View {
        let score = overview.ratingCount > 0 ? overview.score?.doubleValue : nil
        HStack(alignment: .center, spacing: 16) {
            VStack(spacing: 2) {
                Text(score.map { $0.formatted(.number.precision(.fractionLength(1))) } ?? "–")
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .foregroundStyle(score == nil ? .secondary : .primary)
                CommunityStars(value: (score ?? 0) / 2, size: 11)
                Text(score == nil ? String(localized: "暂无评分") : String(localized: "\(overview.ratingCount) 人评分"))
                    .font(.caption2).foregroundStyle(.secondary)
            }
            .frame(minWidth: 84)
            if score == nil {
                Text("来做第一个评分的人")
                    .font(.subheadline).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                let counts = overview.starCounts.map(\.intValue)
                let total = max(counts.reduce(0, +), 1)
                VStack(spacing: 3) {
                    ForEach((1...5).reversed(), id: \.self) { star in
                        HStack(spacing: 6) {
                            Text(verbatim: "\(star)").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                                .frame(width: 10)
                            ProgressView(value: Double(counts[star - 1]), total: Double(total))
                                .tint(.orange)
                        }
                    }
                }
                .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

/// Five stars filled to `value` out of five, halves included.
struct CommunityStars: View {
    let value: Double
    var size: CGFloat = 12

    var body: some View {
        HStack(spacing: size / 6) {
            ForEach(0..<5, id: \.self) { index in
                let fill = min(max(value - Double(index), 0), 1)
                Image(systemName: fill >= 0.75 ? "star.fill" : fill >= 0.25 ? "star.leadinghalf.filled" : "star")
            }
        }
        .font(.system(size: size))
        .foregroundStyle(.orange)
        .accessibilityHidden(true)
    }
}

/// Five large stars for choosing 1–10: a touch on a star's leading half gives half of it; dragging across adjusts.
private struct CommunityStarPicker: View {
    @Binding var stars: Int32

    var body: some View {
        GeometryReader { proxy in
            CommunityStars(value: Double(stars) / 2, size: 34)
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                    let width = proxy.size.width
                    let starsWidth = min(width, 34 * 5 + 34 / 6 * 4)
                    let x = value.location.x - (width - starsWidth) / 2
                    stars = Int32(min(max((x / starsWidth * 10).rounded(.up), 1), 10))
                })
        }
        .frame(height: 44)
        .accessibilityElement()
        .accessibilityLabel("我的评分")
        .accessibilityValue(stars > 0 ? "\(stars) / 10" : String(localized: "未评分"))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: stars = min(stars + 1, 10)
            case .decrement: stars = max(stars - 1, 1)
            @unknown default: break
            }
        }
    }
}

private func communityRatingLevel(_ stars: Int32) -> LocalizedStringKey {
    switch (stars + 1) / 2 {
    case 1: "很差"
    case 2: "较差"
    case 3: "还行"
    case 4: "推荐"
    default: "力荐"
    }
}

private struct CommunityCommentsView: View {
    @Environment(\.dismiss) private var dismiss
    let community: IosCommunityDetails
    let content: IosSourceContent
    @State private var comments: [IosCommunityComment] = []
    @State private var loading = true
    @State private var failed = false
    @State private var request = 0
    @State private var revealed: Set<String> = []
    @State private var composing = false

    var body: some View {
        NavigationStack {
            List {
                if loading && comments.isEmpty {
                    ProgressView("正在读取社区评论…")
                } else if failed && comments.isEmpty {
                    ContentUnavailableView {
                        Label("暂时无法读取评论", systemImage: "exclamationmark.bubble")
                    } actions: {
                        Button("重试") { request += 1 }
                    }
                } else if comments.isEmpty {
                    ContentUnavailableView("暂无社区评论", systemImage: "bubble.left")
                } else {
                    ForEach(comments, id: \.id) { comment in
                        HStack(alignment: .top, spacing: 10) {
                            CommunityAvatar(name: comment.author, size: comment.isReply ? 26 : 34)
                            VStack(alignment: .leading, spacing: 4) {
                                HStack(spacing: 6) {
                                    Text(verbatim: comment.author).font(.subheadline.weight(.semibold)).lineLimit(1)
                                    if comment.isPending {
                                        Text("待发送").font(.caption).foregroundStyle(.tint)
                                    } else {
                                        Text(Date(timeIntervalSince1970: TimeInterval(comment.createdAt)), style: .relative)
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                                if comment.spoiler && !revealed.contains(comment.id) {
                                    Button { revealed.insert(comment.id) } label: {
                                        Label("显示剧透内容", systemImage: "eye.slash").font(.subheadline)
                                    }
                                    .buttonStyle(.bordered).controlSize(.small)
                                } else {
                                    Text(verbatim: comment.content)
                                        .textSelection(.enabled)
                                }
                            }
                        }
                        .padding(.leading, comment.isReply ? 44 : 0)
                        .padding(.vertical, 2)
                        .accessibilityIdentifier("details.community.comment.\(comment.id)")
                    }
                }
            }
            .navigationTitle("社区评论")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button("写评论", systemImage: "square.and.pencil") { composing = true }
                        .accessibilityIdentifier("details.community.compose")
                }
            }
            .sheet(isPresented: $composing, onDismiss: { request += 1 }) {
                CommunityCommentComposer(community: community, content: content)
            }
            .task(id: request) {
                loading = true
                failed = false
                do {
                    let result = try await community.comments(content: content)
                    guard !Task.isCancelled else { return }
                    comments = result
                } catch {
                    guard !Task.isCancelled else { return }
                    failed = true
                }
                loading = false
            }
        }
    }
}

/// A round initial in a colour picked from the author's name, so one author looks the same in every thread.
private struct CommunityAvatar: View {
    let name: String
    let size: CGFloat
    private static let palette: [Color] = [.blue, .teal, .indigo, .orange, .pink, .green, .purple, .brown]

    var body: some View {
        let hash = name.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0x7fffffff }
        Circle()
            .fill(Self.palette[hash % Self.palette.count].gradient)
            .frame(width: size, height: size)
            .overlay {
                Text(verbatim: String(name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
                    .font(.system(size: size * 0.45, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
            }
            .accessibilityHidden(true)
    }
}

private struct CommunityRatingView: View {
    let community: IosCommunityDetails
    let content: IosSourceContent
    @State var stars: Int32

    var body: some View {
        CommunityPublishingForm(
            title: "作品评分", community: community, publishTitle: "公开发布评分",
            publishIdentifier: "details.community.publishRating", canPublish: stars > 0,
            operation: { try await community.rate(content: content, stars: stars, identity: $0) }
        ) {
            VStack(spacing: 6) {
                CommunityStarPicker(stars: $stars)
                if stars > 0 {
                    (Text(verbatim: "\(stars) / 10 · ") + Text(communityRatingLevel(stars)))
                        .font(.headline)
                } else {
                    Text("点按星星评分，点左半边为半星").font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
        }
    }
}

private struct CommunityCommentComposer: View {
    let community: IosCommunityDetails
    let content: IosSourceContent
    @State private var text = ""
    @State private var spoiler = false

    var body: some View {
        CommunityPublishingForm(
            title: "写社区评论", community: community, publishTitle: "公开发布评论",
            publishIdentifier: "details.community.publishComment",
            canPublish: !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                text.utf16.count <= Int(community.maxCommentLength),
            operation: { identity in
                let result = try await community.postComment(content: content, text: text, spoiler: spoiler, identity: identity)
                text = ""
                return result
            }
        ) {
            TextEditor(text: $text).frame(minHeight: 100)
                .accessibilityLabel("评论内容")
            Toggle("包含剧透", isOn: $spoiler)
        }
    }
}

struct CommunityIdentitySettings: View {
    let community: IosCommunityDetails

    var body: some View {
        CommunityPublishingForm(
            title: "社区身份", community: community, publishTitle: "", publishIdentifier: "",
            canPublish: false, operation: nil
        ) { EmptyView() }
    }
}

private struct CommunityPublishingForm<Controls: View>: View {
    typealias Operation = (IosCommunityIdentity) async throws -> IosCommunityPublication
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let title: LocalizedStringKey
    let community: IosCommunityDetails
    let publishTitle: LocalizedStringKey
    let publishIdentifier: String
    let canPublish: Bool
    let operation: Operation?
    @ViewBuilder let controls: () -> Controls
    @State private var identity: IosCommunityIdentity?
    @State private var recoveryKey = ""
    @State private var nickname = ""
    @State private var savedNickname = ""
    @State private var loadingNickname = false
    @State private var showingBackup = false
    @State private var busy = false
    @State private var pending: Int32 = 0
    @State private var message: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let identity {
                        Text(verbatim: identity.publicKey).font(.caption.monospaced())
                            .textSelection(.enabled)
                        Button(showingBackup ? "隐藏恢复密钥" : "显示恢复密钥") { showingBackup.toggle() }
                        if showingBackup {
                            Text(verbatim: identity.recoveryKey()).font(.caption.monospaced())
                                .textSelection(.enabled)
                        }
                    } else {
                        Button("创建社区身份") { install(IosCommunityIdentity.companion.create()) }
                        SecureField("已有恢复密钥（nsec）", text: $recoveryKey)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Button("恢复社区身份") {
                            guard let restored = IosCommunityIdentity.companion.restore(recoveryKey: recoveryKey) else {
                                message = String(localized: "恢复密钥无效")
                                return
                            }
                            install(restored)
                        }.disabled(recoveryKey.isEmpty)
                    }
                } header: {
                    Text("社区身份")
                } footer: {
                    Text("恢复密钥只保存在本机钥匙串，不随备份同步。请勿分享恢复密钥。")
                }
                if let identity {
                    Section {
                        TextField("社区昵称", text: $nickname)
                            .disabled(loadingNickname)
                            .accessibilityIdentifier("community.nickname")
                        Button("公开保存昵称") { saveNickname(identity) }
                            .disabled(loadingNickname || trimmedNickname.isEmpty || trimmedNickname == savedNickname ||
                                trimmedNickname.utf16.count > Int(community.maxNicknameLength))
                    } footer: {
                        Text("昵称会与公钥一起发布到公共中继，并显示在评论旁。")
                    }
                    .task(id: identity.publicKey) {
                        loadingNickname = true
                        defer { loadingNickname = false }
                        do {
                            let value = try await community.nickname(identity: identity) ?? ""
                            guard !Task.isCancelled else { return }
                            nickname = value
                            savedNickname = value
                        } catch {
                            guard !Task.isCancelled else { return }
                            message = error.localizedDescription
                        }
                    }
                    if operation != nil {
                        Section {
                            controls()
                            Button(publishTitle) { start() }
                                .disabled(!canPublish)
                                .accessibilityIdentifier(publishIdentifier)
                        } footer: {
                            Text("发布内容和公钥将发送到公共中继，可能被复制或归档。")
                        }
                    }
                    if pending > 0 {
                        Section {
                            Text("待发送：\(pending) 条")
                            Button("重试待发送内容") { retry() }
                        }
                    }
                }
                if busy { ProgressView("正在准备签名或发送…") }
                if let message { Text(message).foregroundStyle(.secondary) }
            }
            .disabled(busy)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() }.disabled(busy) }
            }
            .interactiveDismissDisabled(busy)
            .onChange(of: scenePhase) { if scenePhase != .active { showingBackup = false } }
            .task {
                do {
                    if let key = try CommunityKeychain.read() {
                        identity = IosCommunityIdentity.companion.restore(recoveryKey: key)
                    }
                    pending = try await community.pendingCount().int32Value
                } catch { message = String(localized: "无法读取本地社区身份或待发送内容") }
            }
        }
    }

    private func install(_ newIdentity: IosCommunityIdentity) {
        do {
            try CommunityKeychain.save(newIdentity.recoveryKey())
            identity = newIdentity
            recoveryKey = ""
            message = nil
        } catch { message = String(localized: "无法保存社区身份，未覆盖已有密钥") }
    }

    private var trimmedNickname: String { nickname.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func saveNickname(_ identity: IosCommunityIdentity) {
        let name = trimmedNickname
        submit {
            let result = try await community.setNickname(nickname: name, identity: identity)
            nickname = name
            savedNickname = name
            return result
        }
    }

    private func start() {
        guard let identity, let operation else { return }
        submit { try await operation(identity) }
    }

    private func submit(_ operation: @escaping () async throws -> IosCommunityPublication) {
        guard !busy else { return }
        busy = true
        showingBackup = false
        message = nil
        Task {
            defer { busy = false }
            do {
                let result = try await operation()
                pending = result.pendingCount
                message = result.published ? String(localized: "中继已接收") : String(localized: "已保存到待发送队列")
            } catch { message = error.localizedDescription }
        }
    }

    private func retry() {
        guard let identity, !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                pending = try await community.retryPending(identity: identity).int32Value
                message = pending == 0 ? String(localized: "待发送内容已清空") : String(localized: "仍有内容待发送")
            } catch { message = error.localizedDescription }
        }
    }
}

private enum CommunityKeychain {
    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "org.skepsun.kototoro.community",
         kSecAttrAccount as String: "nostr"]
    }

    static func read() throws -> String? {
        var query = query
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data, let secret = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadUnknown)
        }
        return secret
    }

    static func save(_ secret: String) throws {
        var attributes = query
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        attributes[kSecValueData as String] = Data(secret.utf8)
        guard SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess else { throw CocoaError(.fileWriteUnknown) }
    }
}
