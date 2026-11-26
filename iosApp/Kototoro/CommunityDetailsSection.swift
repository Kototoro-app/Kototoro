import KototoroShared
import Security
import SwiftUI

/// The details page's one rating section, drawn without a panel: the community's score while the community is on, the
/// source's own score, then the reader's single rating. That rating goes to the community and to every tracker the
/// work is bound to unless the reader turns one off under 同步到; trackers take the same 0–10 scale as the stars.
struct DetailsRatingSection: View {
    /// Nil while the community is off or the work is adult; the section then rates on trackers only.
    let community: IosCommunityDetails?
    let content: IosSourceContent
    let sourceTitle: String?
    /// The source's score on a 0–10 scale.
    let sourceScore: Double?
    let tracking: IosTracking?
    @ObservedObject var trackingModel: TrackingAccountsModel
    /// The community's mean (0–10) once read, nil while unread, without ratings or unavailable; the header shows it.
    var onCommunityScore: (Double?) -> Void = { _ in }
    @State private var overview: IosCommunityOverview?
    @State private var unavailable = false
    @State private var loading = true
    @State private var showingComments = false
    @State private var sheet: RatingSheet?
    @State private var communityStars: Int32 = 0
    @State private var trackers: [IosTrackingRatingTarget] = []
    @State private var deselected: Set<String> = []
    @State private var failure: String?

    private enum RatingSheet: Identifiable {
        case community, trackers
        var id: Self { self }
    }

    private struct Target: Identifiable {
        let id: String
        let title: String
    }

    private static let communityKey = "community"

    private var targets: [Target] {
        (community != nil ? [Target(id: Self.communityKey, title: String(localized: "社区"))] : [])
            + trackers.map { Target(id: String($0.service), title: $0.title) }
    }
    private var selectedTrackers: [IosTrackingRatingTarget] {
        trackers.filter { !deselected.contains(String($0.service)) }
    }
    private var sendsToCommunity: Bool {
        community != nil && overview != nil && !deselected.contains(Self.communityKey)
    }
    private var canRate: Bool { sendsToCommunity || !selectedTrackers.isEmpty }
    /// The community rating when there is one, else the first bound tracker's score.
    private var myRating: Int32 {
        communityStars > 0 ? communityStars : Int32((trackers.first { $0.score > 0 }?.score ?? 0).rounded())
    }

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    Text("评分").font(.title3.weight(.bold))
                    Spacer(minLength: 0)
                    if let overview { commentsButton(count: Int(overview.commentCount)) }
                }
                if community != nil {
                    if loading {
                        Label { Text("正在读取社区评分…") } icon: { ProgressView() }
                            .font(.subheadline).foregroundStyle(.secondary)
                    } else if unavailable {
                        Label("暂时无法读取社区评分", systemImage: "exclamationmark.circle")
                            .font(.subheadline).foregroundStyle(.secondary)
                    } else if let overview {
                        CommunityRatingSummaryRow(overview: overview)
                            .accessibilityIdentifier("details.community.rating")
                    }
                }
                if let sourceTitle, let sourceScore {
                    LabeledContent {
                        Text(sourceScore.formatted(.number.precision(.fractionLength(1))))
                            .font(.subheadline.weight(.semibold)).monospacedDigit().foregroundStyle(.primary)
                    } label: {
                        Text(verbatim: sourceTitle).font(.subheadline)
                    }
                    .accessibilityIdentifier("details.rating.source")
                }
                Divider()
                myRatingRow
                if targets.isEmpty {
                    Group {
                        // Adult works never go to the community, so turning it on would not help.
                        if content.isNsfw {
                            Text("成人作品不参与社区评分，绑定追踪平台后即可评分")
                        } else {
                            Text("开启社区或绑定追踪平台后即可评分")
                        }
                    }
                    .font(.footnote).foregroundStyle(.secondary)
                } else {
                    syncRow
                }
                if let failure {
                    Text(failure).font(.footnote).foregroundStyle(.red)
                }
            }
            .padding(.vertical, 6)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 10, trailing: 0))
        }
        .listRowSeparator(.hidden)
        .task(id: content.contentKey + "|" + content.title) { await load() }
        .sheet(isPresented: $showingComments) {
            if let community { CommunityCommentsView(community: community, content: content) }
        }
        .sheet(item: $sheet, onDismiss: { Task { await reloadMine() } }) { sheet in
            switch sheet {
            case .community:
                if let community {
                    CommunityRatingView(community: community, content: content, stars: myRating) { stars in
                        await sendToTrackers(stars)
                    }
                }
            case .trackers:
                TrackerRatingView(stars: myRating) { stars in await sendToTrackers(stars) }
            }
        }
    }

    private func load() async {
        loading = true
        unavailable = false
        overview = nil
        onCommunityScore(nil)
        failure = nil
        trackers = await trackingModel.ratingTargets(content: content, tracking: tracking)
        guard let community else { loading = false; return }
        do {
            let result = try await community.overview(content: content)
            guard !Task.isCancelled else { return }
            overview = result
            onCommunityScore(result.flatMap { $0.ratingCount > 0 ? $0.score?.doubleValue : nil })
            communityStars = (try? await community.myRating(content: content))?.int32Value ?? 0
        } catch {
            guard !Task.isCancelled else { return }
            unavailable = true
        }
        loading = false
    }

    private func reloadMine() async {
        if let community { communityStars = (try? await community.myRating(content: content))?.int32Value ?? 0 }
        trackers = await trackingModel.ratingTargets(content: content, tracking: tracking)
    }

    /// Sends the score to each selected tracker; a failure is reported under the section, the rest still go out.
    private func sendToTrackers(_ stars: Int32) async {
        var failed: [String] = []
        for target in selectedTrackers {
            do {
                try await trackingModel.rate(content: content, service: target.service, score: Float(stars))
            } catch {
                failed.append("\(target.title)：\(error.localizedDescription)")
            }
        }
        failure = failed.isEmpty ? nil : String(localized: "评分未同步。") + failed.joined(separator: "；")
    }

    /// The panel's trailing action, styled like the chapter section's “全部 N 集”.
    private func commentsButton(count: Int) -> some View {
        Button { showingComments = true } label: {
            HStack(spacing: 4) {
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.caption.weight(.semibold))
                Text(count > 0 ? String(localized: "评论 \(count)") : String(localized: "评论"))
                    .font(.subheadline.weight(.medium))
                    .monospacedDigit()
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
        }
        .buttonStyle(.borderless)
        .tint(.primary)
        .accessibilityLabel("社区评论")
        .accessibilityValue(count > 0 ? String(localized: "\(count) 条评论") : "")
        .accessibilityIdentifier("details.community.comments")
    }

    /// The reader's one rating, and the capsule that opens the picker.
    private var myRatingRow: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text("我的评分").font(.subheadline).foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    CommunityStars(value: Double(myRating) / 2, size: 17)
                    if myRating > 0 {
                        Text(verbatim: "\(myRating) / 10").font(.subheadline.weight(.semibold)).monospacedDigit()
                        Text(communityRatingLevel(myRating)).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
            Spacer(minLength: 0)
            Button(myRating > 0 ? "修改" : "评分") { sheet = sendsToCommunity ? .community : .trackers }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .controlSize(.small)
                .disabled(!canRate)
                .accessibilityIdentifier("details.community.rate")
        }
    }

    /// Where the rating goes: every target starts on; a menu of toggles turns one off for this visit.
    private var syncRow: some View {
        let chosen = targets.filter { !deselected.contains($0.id) }.map(\.title)
        return Menu {
            ForEach(targets) { target in
                Toggle(target.title, isOn: Binding(
                    get: { !deselected.contains(target.id) },
                    set: { on in if on { deselected.remove(target.id) } else { deselected.insert(target.id) } }
                ))
            }
        } label: {
            LabeledContent {
                HStack(spacing: 4) {
                    Text(chosen.isEmpty ? String(localized: "不同步") : chosen.joined(separator: String(localized: "、")))
                        .lineLimit(1)
                    Image(systemName: "chevron.up.chevron.down").font(.caption2.weight(.semibold))
                }
                .foregroundStyle(.secondary)
            } label: {
                Text("同步到").foregroundStyle(.primary)
            }
            .font(.subheadline)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("details.rating.syncTargets")
    }
}

/// The rating picker when the score goes to trackers only: no community identity is involved, so a plain sheet.
private struct TrackerRatingView: View {
    @Environment(\.dismiss) private var dismiss
    @State var stars: Int32
    let save: (Int32) async -> Void
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 6) {
                        CommunityStarPicker(stars: $stars)
                        if stars > 0 {
                            HStack(spacing: 0) {
                                Text(verbatim: "\(stars) / 10 · ")
                                Text(communityRatingLevel(stars))
                            }
                            .font(.headline)
                        } else {
                            Text("点按星星评分，点左半边为半星").font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                }
            }
            .navigationTitle("作品评分").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        Task {
                            saving = true
                            await save(stars)
                            saving = false
                            dismiss()
                        }
                    }
                    .disabled(stars == 0 || saving)
                    .accessibilityIdentifier("details.rating.save")
                }
            }
            .interactiveDismissDisabled(saving)
        }
        .presentationDetents([.medium])
    }
}

/// The score, its stars and count beside how the ratings spread over one to five stars, as in the App Store. Without
/// any rating it says so in a line instead of drawing an empty score and a row of empty stars.
private struct CommunityRatingSummaryRow: View {
    let overview: IosCommunityOverview

    var body: some View {
        let score = overview.ratingCount > 0 ? overview.score?.doubleValue : nil
        if let score {
            HStack(alignment: .center, spacing: 16) {
                VStack(spacing: 2) {
                    Text(score.formatted(.number.precision(.fractionLength(1))))
                        .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    CommunityStars(value: score / 2, size: 11)
                    Text("\(overview.ratingCount) 人评分")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                .frame(minWidth: 84)
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
            .accessibilityElement(children: .combine)
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Text("暂无评分").font(.title3.weight(.semibold))
                Text("来做第一个评分的人").font(.subheadline).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }
}

/// Five stars filled to `value` out of five, halves included: the filled part in orange, the rest in a quiet gray, as
/// the system draws ratings.
struct CommunityStars: View {
    let value: Double
    var size: CGFloat = 12

    var body: some View {
        HStack(spacing: size / 6) {
            ForEach(0..<5, id: \.self) { index in
                let fill = min(max(value - Double(index), 0), 1)
                ZStack {
                    Image(systemName: "star.fill").foregroundStyle(Color(uiColor: .tertiaryLabel))
                    if fill >= 0.25 {
                        // Half a star is the leading half of a filled one, so no outline sits on the gray star.
                        let shown = fill >= 0.75 ? 1.0 : 0.5
                        Image(systemName: "star.fill").foregroundStyle(.orange)
                            .mask(alignment: .leading) {
                                GeometryReader { proxy in Rectangle().frame(width: proxy.size.width * shown) }
                            }
                    }
                }
            }
        }
        .font(.system(size: size))
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
    /// After the community took the rating: the same score goes to the selected trackers.
    var onPublished: (Int32) async -> Void = { _ in }

    var body: some View {
        CommunityPublishingForm(
            title: "作品评分", community: community, publishTitle: "公开发布评分",
            publishIdentifier: "details.community.publishRating", canPublish: stars > 0,
            operation: { identity in
                let result = try await community.rate(content: content, stars: stars, identity: identity)
                await onPublished(stars)
                return result
            }
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
    /** Read by the shared engine as `community_share_sources` (IosCommunityDetails). */
    @AppStorage("community_share_sources") private var shareSources = true

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
                    Section {
                        Toggle("发布时附带来源", isOn: $shareSources)
                            .accessibilityIdentifier("community.shareSources")
                    } footer: {
                        Text("评分和评论会公开你在哪个来源阅读这部作品，其他读者可以在同一来源直接打开。本地、自定义和成人来源不会附带。")
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
