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
                if let score = overview.score?.doubleValue, overview.ratingCount > 0 {
                    let formatted = score.formatted(.number.precision(.fractionLength(1)))
                    Label("社区评分 \(formatted) / 10 · \(overview.ratingCount) 人", systemImage: "star.fill")
                        .accessibilityIdentifier("details.community.rating")
                } else {
                    Label("暂无社区评分", systemImage: "star")
                        .accessibilityIdentifier("details.community.rating")
                }
                Button {
                    showingRating = true
                } label: {
                    if myRating > 0 {
                        Label("我的评分：\(myRating) / 10", systemImage: "star.bubble")
                    } else {
                        Label("给作品打分", systemImage: "star.bubble")
                    }
                }
                .accessibilityIdentifier("details.community.rate")
                Button {
                    showingComments = true
                } label: {
                    if overview.commentCount > 0 {
                        Label("查看社区评论（\(overview.commentCount)）", systemImage: "bubble.left.and.bubble.right")
                    } else {
                        Label("查看社区评论", systemImage: "bubble.left.and.bubble.right")
                    }
                }
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
            CommunityRatingView(community: community, content: content, stars: myRating > 0 ? myRating : 8)
        }
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
                if loading {
                    ProgressView("正在读取社区评论…")
                } else if failed {
                    ContentUnavailableView {
                        Label("暂时无法读取评论", systemImage: "exclamationmark.bubble")
                    } actions: {
                        Button("重试") { request += 1 }
                    }
                } else if comments.isEmpty {
                    ContentUnavailableView("暂无社区评论", systemImage: "bubble.left")
                } else {
                    ForEach(comments, id: \.id) { comment in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(verbatim: comment.author)
                                Spacer()
                                Text(Date(timeIntervalSince1970: TimeInterval(comment.createdAt)), style: .date)
                            }
                            .font(.caption).foregroundStyle(.secondary)
                            if comment.spoiler && !revealed.contains(comment.id) {
                                Button("显示剧透内容") { revealed.insert(comment.id) }
                            } else {
                                Text(verbatim: comment.content)
                                    .textSelection(.enabled)
                            }
                        }
                        .padding(.leading, comment.isReply ? 20 : 0)
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

private struct CommunityRatingView: View {
    let community: IosCommunityDetails
    let content: IosSourceContent
    @State var stars: Int32

    var body: some View {
        CommunityPublishingForm(
            title: "作品评分", community: community, publishTitle: "公开发布评分",
            publishIdentifier: "details.community.publishRating", canPublish: true,
            operation: { try await community.rate(content: content, stars: stars, identity: $0) }
        ) {
            Stepper("我的评分：\(stars) / 10", value: $stars, in: 1...10)
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

private struct CommunityPublishingForm<Controls: View>: View {
    typealias Operation = (IosCommunityIdentity) async throws -> IosCommunityPublication
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let title: LocalizedStringKey
    let community: IosCommunityDetails
    let publishTitle: LocalizedStringKey
    let publishIdentifier: String
    let canPublish: Bool
    let operation: Operation
    @ViewBuilder let controls: () -> Controls
    @State private var identity: IosCommunityIdentity?
    @State private var recoveryKey = ""
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
                if identity != nil {
                    Section {
                        controls()
                        Button(publishTitle) { start() }
                            .disabled(!canPublish)
                            .accessibilityIdentifier(publishIdentifier)
                    } footer: {
                        Text("发布内容和公钥将发送到公共中继，可能被复制或归档。")
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

    private func start() {
        guard let identity, !busy else { return }
        busy = true
        showingBackup = false
        message = nil
        Task {
            defer { busy = false }
            do {
                let result = try await operation(identity)
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
