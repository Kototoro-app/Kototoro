import KototoroShared
import SwiftUI

/// Capture the selected item and service once so parent refreshes do not replace an open preview's navigation.
struct TrackingPreviewSelection: Identifiable {
    let item: IosTrackingItem
    let tracking: IosTracking
    var id: String { item.key }
}

/// Public catalog search and the metadata authority of a source work share the same picker.
struct TrackingSitesView: View {
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.dismiss) private var dismiss
    var content: IosSourceContent? = nil
    var initialMediaKind = "MANGA"
    var initialService: Int? = nil
    var initialCategory: String? = nil
    var initialWeekday: Int? = nil
    var followsSpace = false
    var onChange: () -> Void = {}
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true
    @AppStorage("tracking.preferredSite") private var service = 5
    @AppStorage("tracking.bangumi.apiBase") private var bangumiApiBase = "https://api.bgm.tv"
    @State private var mediaKind = "MANGA"
    @State private var query = ""
    @FocusState private var queryFocused: Bool
    @State private var submittedQuery = ""
    @State private var results: [IosTrackingItem] = []
    @State private var bindings: [IosTrackingItem] = []
    @State private var selectedKey: String?
    @State private var preview: TrackingPreviewSelection?
    @State private var loading = false
    @State private var mutating = false
    @State private var usingKey: String?
    @State private var error: String?
    @State private var page: Int32 = 0
    @State private var hasMore = false
    @State private var request: UUID?
    @State private var section = "discover"
    @State private var category = "popular"
    @State private var weekday = 0
    @State private var videoType = "anime"

    private var searchIdentity: String {
        "\(service)|\(mediaKind)|\(submittedQuery)|\(effectiveCategory)|" +
            "\(TrackingScheduleWindow(weekday: weekday).start)|\(section)|\(videoType)|\(bangumiApiBase)"
    }
    private var availableSites: [IosTrackingSite] {
        library.library?.tracking.sites.filter { $0.supports(mediaKind: mediaKind) } ?? []
    }
    private var catalogSections: [IosTrackingSection] {
        library.library?.tracking.recommendationSections(service: Int32(service), mediaKind: mediaKind) ?? []
    }
    private var effectiveCategory: String { catalogSections.contains { $0.id == category } ? category : "popular" }

    var body: some View {
        List {
            if content != nil { currentMetadata }
            if content == nil {
                Section {
                    Picker("追踪网站", selection: $section) {
                        Text("发现作品").tag("discover")
                        Text("我的账号").tag("accounts")
                    }.pickerStyle(.segmented).accessibilityIdentifier("tracking.section")
                } footer: { Text(section == "discover" ? "浏览资料无需登录，找到作品后可关联到本地作品。" : "连接账号后，在作品详情开启追踪与进度同步。") }
            }
            if section == "accounts", content == nil {
                TrackingAccountRows(model: library.trackingAccounts, sites: library.library?.tracking.sites ?? [])
            } else {
                Section {
                    if content == nil && !(followsSpace && spacesEnabled && !space.isEmpty) {
                        Picker("作品类型", selection: $mediaKind) {
                            Text("漫画").tag("MANGA")
                            Text("小说").tag("NOVEL")
                            Text("动画 / 影视").tag("VIDEO")
                        }
                        .accessibilityIdentifier("tracking.mediaKind")
                    }
                    ScrollView(.horizontal) {
                        HStack(spacing: 8) {
                            ForEach(availableSites, id: \.id) { site in
                                Button { service = Int(site.id) } label: {
                                    Text(site.title).font(.subheadline.weight(.medium))
                                        .padding(.horizontal, 14).padding(.vertical, 8)
                                        .background(service == Int(site.id) ? Color.accentColor.opacity(0.15) : Color(.secondarySystemFill), in: .capsule)
                                        .foregroundStyle(service == Int(site.id) ? Color.accentColor : .primary)
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("tracking.site.\(site.id)")
                                .accessibilityAddTraits(service == Int(site.id) ? .isSelected : [])
                            }
                        }.padding(.vertical, 3)
                    }
                    .scrollIndicators(.hidden)
                    if service == 7 && (!effectiveCategory.hasPrefix("calendar") || !submittedQuery.isEmpty) {
                        Picker("影视类型", selection: $videoType) {
                            Text("动画").tag("anime")
                            Text("剧集").tag("tv")
                            Text("电影").tag("movies")
                        }.pickerStyle(.segmented).accessibilityIdentifier("tracking.videoType")
                    }
                    HStack {
                        TextField("搜索作品名称", text: $query)
                            .focused($queryFocused)
                            .submitLabel(.search)
                            .onSubmit { submit() }
                            .accessibilityIdentifier("tracking.query")
                        Button("搜索") { submit() }
                            .disabled(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityIdentifier("tracking.search")
                    }
                    if submittedQuery.isEmpty {
                        Picker("榜单", selection: Binding(get: { effectiveCategory }, set: { category = $0 })) {
                            ForEach(catalogSections, id: \.id) { Text($0.title).tag($0.id) }
                        }.pickerStyle(.segmented).accessibilityIdentifier("tracking.ranking")
                        if effectiveCategory.hasPrefix("calendar") {
                            Picker("放送日", selection: $weekday) {
                                ForEach(1...7, id: \.self) { Text(TrackingRecommendationsView.weekdayTitle($0)).tag($0) }
                            }.accessibilityIdentifier("tracking.weekday")
                            if service != 5 {
                                Text(TrackingScheduleWindow(weekday: weekday).date,
                                     format: .dateTime.year().month().day())
                                    .font(.caption).foregroundStyle(.secondary)
                                Text("按本地时区查看本周排期").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    } else if !submittedQuery.isEmpty {
                        Button("清除搜索，浏览榜单", systemImage: "xmark.circle") {
                            query = ""; submittedQuery = ""
                        }.font(.caption)
                    }
                } header: { Text(content == nil ? "检索追踪网站" : "换一份作品资料") }
                if service == 5 {
                    Section {
                        NavigationLink { TrackingConnectionView() } label: {
                            Label("Bangumi 连接设置", systemImage: "network")
                        }.accessibilityIdentifier("tracking.connection")
                    }
                }
                if let error {
                    Section {
                        Text(error).foregroundStyle(.red).accessibilityIdentifier("tracking.error")
                        Button("重试") { Task { await search(reset: results.isEmpty) } }
                    }
                }
                Section {
                    ForEach(results, id: \.key) { item in
                        HStack(spacing: 10) {
                            Button {
                                if let tracking = library.library?.tracking {
                                    preview = TrackingPreviewSelection(item: item, tracking: tracking)
                                }
                            } label: { TrackingItemRow(item: item) }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("tracking.result.\(item.remoteId)")
                            if let content, item.mediaKind == content.mediaKind {
                                Button {
                                    Task { await use(item) }
                                } label: {
                                    if usingKey == item.key { ProgressView() }
                                    else { Text(selectedKey == item.key ? "使用中" : "使用").font(.subheadline.weight(.semibold)) }
                                }
                                .buttonStyle(.borderedProminent)
                                .disabled(mutating || selectedKey == item.key)
                                .accessibilityIdentifier("tracking.use.\(item.remoteId)")
                            }
                        }
                    }
                    if loading { ProgressView(submittedQuery.isEmpty ? "正在读取榜单…" : "正在检索…").frame(maxWidth: .infinity) }
                    else if !submittedQuery.isEmpty && results.isEmpty && error == nil {
                        ContentUnavailableView.search(text: submittedQuery)
                    } else if hasMore {
                        Button("加载更多") { Task { await search(reset: false) } }
                            .accessibilityIdentifier("tracking.more")
                    }
                }
                if content == nil {
                    Section {
                        Text("点开作品可查看介绍，通过全局搜索或作品库选择对应作品，再使用网站资料或开启进度追踪。")
                    }
                    .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(content == nil ? "追踪网站" : "追踪与资料")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if content != nil {
                ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() }.disabled(mutating) }
            }
        }
        .interactiveDismissDisabled(mutating)
        .refreshable { await search(reset: true); await loadBindings() }
        .task {
            guard let tracking = library.library?.tracking else { return }
            mediaKind = content?.mediaKind ??
                (followsSpace && spacesEnabled && !space.isEmpty ? space : initialMediaKind)
            if let initialService { service = initialService }
            category = initialCategory ?? "popular"
            weekday = initialWeekday ?? (Calendar.current.component(.weekday, from: Date()) + 5) % 7 + 1
            ensureSite()
            await loadBindings()
            if let content {
                query = bindings.first(where: { $0.key == selectedKey })?.title ?? content.title
                submit()
            }
            await library.trackingAccounts.prepare(tracking)
        }
        .onChange(of: mediaKind) { _, _ in ensureSite() }
        .onChange(of: effectiveCategory) { _, value in
            if service == 7 && value.hasPrefix("calendar") { videoType = value == "calendar_tv" ? "tv" : "anime" }
        }
        .onChange(of: space) { _, kind in
            if followsSpace && spacesEnabled && !kind.isEmpty { mediaKind = kind }
        }
        .task(id: searchIdentity) { await search(reset: true) }
        .sheet(item: $preview, onDismiss: { Task { await library.refresh() } }) { selection in
                TrackingItemPreview(item: selection.item, tracking: selection.tracking, content: content,
                    onBound: { Task { await loadBindings(); await library.refresh(); onChange() } },
                    onTracked: { Task { await loadBindings(); await library.refresh() } })
        }
    }

    private var currentMetadata: some View {
        Section {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("当前资料 · \(bindings.first(where: { $0.key == selectedKey })?.siteTitle ?? String(localized: "内容源"))")
                        .font(.subheadline.weight(.medium))
                    Text(bindings.first(where: { $0.key == selectedKey })?.title ?? content?.title ?? "")
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                if selectedKey != nil {
                    Button("恢复源信息") { Task { await select(nil) } }
                        .font(.caption).accessibilityIdentifier("tracking.useSource")
                }
                if !bindings.isEmpty {
                    Menu("已关联资料", systemImage: "ellipsis.circle") {
                        ForEach(bindings, id: \.key) { item in
                            Button("使用 \(item.siteTitle) 资料") { Task { await select(item) } }
                            Button("解除 \(item.siteTitle) 关联", role: .destructive) { Task { await unbind(item) } }
                        }
                    }
                }
            }
            .disabled(mutating)
            if let content, let tracking = library.library?.tracking {
                ForEach(bindings, id: \.key) { item in
                    NavigationLink {
                        TrackingProgressView(content: content, item: item, tracking: tracking, model: library.trackingAccounts) {
                            Task { await loadBindings(); await library.refresh() }
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Label(item.siteTitle, systemImage: "chart.line.uptrend.xyaxis")
                            Text(item.title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    .accessibilityIdentifier("tracking.manage.\(item.service)")
                }
                TrackingSyncBanner(content: content, tracking: tracking, model: library.trackingAccounts)
            }
        } footer: { Text("资料决定封面与介绍；点开已关联网站管理账号追踪。") }
    }

    private func ensureSite() {
        if !availableSites.contains(where: { Int($0.id) == service }), let first = availableSites.first {
            service = Int(first.id)
        }
    }

    private func submit() {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        queryFocused = false
        if text == submittedQuery { Task { await search(reset: true) } }
        else { submittedQuery = text }
    }

    @MainActor private func search(reset: Bool) async {
        guard section == "discover", let tracking = library.library?.tracking, reset || !loading else { return }
        let token = UUID()
        let identity = searchIdentity
        request = token
        loading = true; error = nil
        if reset { results = []; page = 0; hasMore = false }
        let next = page + 1
        defer { if request == token { loading = false } }
        do {
            let items: [IosTrackingItem]
            if service == 7 && (!submittedQuery.isEmpty || !effectiveCategory.hasPrefix("calendar")) {
                items = try await tracking.videos(query: submittedQuery, remoteType: videoType, page: next)
            } else if submittedQuery.isEmpty {
                let window = TrackingScheduleWindow(weekday: weekday)
                items = try await tracking.recommendations(service: Int32(service), mediaKind: mediaKind,
                    category: effectiveCategory, weekday: Int32(weekday), page: next,
                    dayStart: window.start, dayEnd: window.end)
            } else {
                items = try await tracking.search(service: Int32(service), query: submittedQuery, mediaKind: mediaKind, page: next)
            }
            guard !Task.isCancelled, request == token, identity == searchIdentity else { return }
            var keys = Set(results.map(\.key))
            results += items.filter { keys.insert($0.key).inserted }
            page = next
            hasMore = !(submittedQuery.isEmpty && effectiveCategory.hasPrefix("calendar")) &&
                items.count >= Int(availableSites.first(where: { Int($0.id) == service })?.pageSize ?? 20)
        } catch {
            if !Task.isCancelled, request == token, identity == searchIdentity {
                self.error = error.localizedDescription
            }
        }
    }

    @MainActor private func loadBindings() async {
        guard let content, let tracking = library.library?.tracking else { return }
        do {
            bindings = try await tracking.bindings(content: content)
            selectedKey = try await tracking.selected(content: content)?.key
        } catch { self.error = error.localizedDescription }
    }

    @MainActor private func select(_ item: IosTrackingItem?) async {
        guard selectedKey != item?.key else { return }
        await mutate { tracking, content in try await tracking.select(content: content, item: item) }
    }

    @MainActor private func use(_ item: IosTrackingItem) async {
        usingKey = item.key
        defer { usingKey = nil }
        await mutate { tracking, content in try await tracking.bind(content: content, item: item) }
    }

    @MainActor private func unbind(_ item: IosTrackingItem) async {
        await mutate { tracking, content in try await tracking.unbind(content: content, service: item.service) }
    }

    @MainActor private func mutate(_ body: (IosTracking, IosSourceContent) async throws -> Void) async {
        guard !mutating, let tracking = library.library?.tracking, let content else { return }
        mutating = true; error = nil
        defer { mutating = false }
        do {
            try await body(tracking, content)
            await loadBindings(); await library.refresh(); onChange()
        } catch { self.error = error.localizedDescription }
    }
}

private struct TrackingItemRow: View {
    let item: IosTrackingItem
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AsyncImage(url: item.coverUrl.flatMap(URL.init(string:))) { image in
                image.resizable().scaledToFill()
            } placeholder: { Rectangle().fill(.quaternary).overlay { Image(systemName: "book.closed") } }
                .frame(width: 54, height: 78).clipShape(.rect(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title).font(.headline)
                if let alternate = item.alternateTitles.first { Text(alternate).font(.caption).foregroundStyle(.secondary) }
                Text("\(item.siteTitle) · #\(item.remoteId)").font(.caption).foregroundStyle(.secondary)
                HStack {
                    if let year = item.year { Text(String(year.intValue)) }
                    if let score = item.score { Label(String(format: "%.1f", score.floatValue / item.scoreMax * 10), systemImage: "star.fill") }
                    if item.mediaKind == "NOVEL" { Text("小说") }
                }.font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct TrackingArtwork: View {
    let url: String?

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { image in image.resizable().scaledToFill() } placeholder: {
            Rectangle().fill(Color.gray.opacity(0.3)).overlay { Image(systemName: "book.closed").foregroundStyle(.secondary) }
        }
    }
}

private struct TrackingPreviewHero: View {
    let item: IosTrackingItem

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            TrackingArtwork(url: item.coverUrl).frame(width: 104, height: 156)
                .clipShape(.rect(cornerRadius: 12)).shadow(color: .black.opacity(0.3), radius: 12, y: 6)
            VStack(alignment: .leading, spacing: 10) {
                if let url = URL(string: item.url) {
                    Link(destination: url) {
                        Label(item.siteTitle, systemImage: "arrow.up.right")
                            .font(.caption.weight(.semibold)).padding(.horizontal, 10).padding(.vertical, 6)
                            .background(.white.opacity(0.16), in: .capsule)
                    }.foregroundStyle(.white.opacity(0.9))
                }
                Text(item.title).font(.title3.bold()).lineLimit(4).textSelection(.enabled)
                    .accessibilityIdentifier("tracking.preview.title")
                HStack(spacing: 8) {
                    Text(ContentSpace.title(item.mediaKind))
                    if let year = item.year { Text(String(year.intValue)) }
                }.font(.caption).foregroundStyle(.white.opacity(0.75))
                if let score = item.score, item.scoreMax > 0 {
                    HStack(alignment: .firstTextBaseline, spacing: 5) {
                        Image(systemName: "star.fill").font(.caption).foregroundStyle(.yellow)
                        Text(String(format: "%.1f", score.floatValue / item.scoreMax * 10))
                            .font(.title3.bold().monospacedDigit())
                        Text("/ 10").font(.caption).foregroundStyle(.white.opacity(0.65))
                    }
                }
                if let total = item.totalChapters, total.intValue > 0 {
                    Text(item.mediaKind == "VIDEO" ? String(localized: "共 \(total.intValue) 集") :
                        String(localized: "共 \(total.intValue) 章"))
                        .font(.caption).foregroundStyle(.white.opacity(0.75))
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(20).frame(maxWidth: .infinity, minHeight: 216, alignment: .leading)
        .foregroundStyle(.white)
        .background {
            GeometryReader { geometry in
                TrackingArtwork(url: item.coverUrl).frame(width: geometry.size.width, height: geometry.size.height)
                    .blur(radius: 24).overlay(Color.black.opacity(0.65))
            }
        }
        .background(Color(.secondarySystemBackground))
        .clipShape(.rect(cornerRadius: 22))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("tracking.preview.hero")
    }
}

struct TrackingItemPreview: View {
    let item: IosTrackingItem
    let tracking: IosTracking
    let content: IosSourceContent?
    let onBound: () -> Void
    var onTracked: () -> Void = {}
    @Environment(\.dismiss) private var dismiss
    @State private var details: IosTrackingItem?
    @State private var error: String?
    @State private var loading = true
    @State private var saving = false
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @State private var choosingWork = false
    @State private var searchingWork = false
    @State private var sourceQuery = ""
    @State private var chosenWork: IosSourceContent?
    @State private var supplement: IosTrackingSupplement?
    @State private var supplementError: String?
    @State private var relatedPreview: TrackingPreviewSelection?
    @State private var expandedSummary = false
    @State private var openingWork = false
    @State private var bound = false
    private var target: IosSourceContent? { content ?? chosenWork }
    private var displayed: IosTrackingItem { details ?? item }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TrackingPreviewHero(item: displayed)
                        .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                }
                if loading { ProgressView("正在读取详情…") }
                if let error {
                    Text(error).foregroundStyle(.red)
                    if details == nil { Button("重试") { Task { await load() } } }
                }
                association
                if let details {
                    if let summary = details.summary, !summary.isEmpty {
                        Section("简介") {
                            Text(summary).font(.body).lineSpacing(5)
                                .lineLimit(expandedSummary ? nil : 6).textSelection(.enabled)
                            if summary.count > 100 || summary.components(separatedBy: .newlines).count > 4 {
                                Button(expandedSummary ? "收起简介" : "展开简介") { expandedSummary.toggle() }
                                    .font(.subheadline).accessibilityIdentifier("tracking.summary.expand")
                            }
                        }
                    }
                    if !details.authors.isEmpty || !details.tags.isEmpty || !details.alternateTitles.isEmpty {
                        Section("作品信息") {
                            if !details.alternateTitles.isEmpty {
                                LabeledContent("其他名称", value: details.alternateTitles.joined(separator: "\n"))
                            }
                            if !details.authors.isEmpty {
                                LabeledContent("作者 / 制作", value: details.authors.joined(separator: "、"))
                            }
                            if !details.tags.isEmpty {
                                FlowLayout(spacing: 8) {
                                    ForEach(Array(details.tags.prefix(12).enumerated()), id: \.offset) { _, tag in
                                        Text(tag).font(.caption).padding(.horizontal, 10).padding(.vertical, 6)
                                            .background(.quaternary, in: .capsule)
                                    }
                                }.padding(.vertical, 4)
                            }
                        }.font(.subheadline)
                    }
                    if let supplement {
                        people(supplement.characters, title: String(localized: "角色与声优"))
                        people(supplement.staff, title: String(localized: "创作者与制作人员"))
                        related(supplement.related, title: String(localized: "关联作品"))
                        related(supplement.recommendations, title: String(localized: "你可能也喜欢"))
                    }
                    if let supplementError {
                        Section {
                            Text(supplementError).font(.caption).foregroundStyle(.secondary)
                            Button("重试补充资料") { Task { await loadSupplement() } }
                        }
                    }
                }
            }
            .navigationTitle("作品资料")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() }.disabled(saving) } }
            .interactiveDismissDisabled(saving)
            .task { await load() }
            .task { await loadSupplement() }
            .navigationDestination(isPresented: $openingWork) {
                if let target, let runtime = sources.runtime {
                    SourceDetailsView(runtime: runtime, content: target)
                }
            }
            .sheet(item: $relatedPreview) { selection in
                TrackingItemPreview(item: selection.item, tracking: selection.tracking,
                                    content: nil, onBound: onBound, onTracked: onTracked)
            }
            .sheet(isPresented: $choosingWork) {
                NavigationStack {
                    TrackingLocalWorkPicker(mediaKind: (details ?? item).mediaKind) {
                        chosenWork = $0; bound = false; choosingWork = false
                    }
                }
            }
            .navigationDestination(isPresented: $searchingWork) {
                GlobalSearchView(query: $sourceQuery, selectionMediaKind: (details ?? item).mediaKind) {
                    chosenWork = $0
                    bound = false
                    searchingWork = false
                }
            }
        }
    }

    private var association: some View {
        Section {
            if let target, target.mediaKind == displayed.mediaKind {
                HStack(spacing: 12) {
                    if let runtime = sources.runtime {
                        CoverImage(runtime: runtime, content: target).frame(width: 42, height: 62)
                            .clipShape(.rect(cornerRadius: 6))
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(target.title).font(.headline).lineLimit(2)
                        Text(sources.sources.first { $0.name == target.sourceName }?.title ?? target.sourceName)
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }.accessibilityIdentifier("tracking.chosenWork")
                Button {
                    Task { await bind(target) }
                } label: {
                    HStack {
                        if saving { ProgressView() }
                        Label(bound ? "已关联此作品" : "绑定并使用此作品的信息",
                              systemImage: bound ? "checkmark.circle.fill" : "link")
                    }.frame(maxWidth: .infinity).padding(.vertical, 5)
                }
                .buttonStyle(.borderedProminent).disabled(saving || loading || details == nil || bound)
                .accessibilityIdentifier("tracking.bind")
                if content == nil {
                    Button("重新选择作品", systemImage: "arrow.triangle.2.circlepath") {
                        if sourceQuery.isEmpty { sourceQuery = displayed.title }
                        searchingWork = true
                    }.disabled(saving)
                }
                NavigationLink {
                    TrackingProgressView(content: target, item: displayed, tracking: tracking,
                        model: library.trackingAccounts, onSaved: onTracked)
                } label: { Label("管理追踪进度", systemImage: "chart.line.uptrend.xyaxis") }
                    .disabled(saving || loading || details == nil).accessibilityIdentifier("tracking.track")
            } else if target == nil {
                Button {
                    sourceQuery = displayed.title
                    searchingWork = true
                } label: {
                    Label("搜索作品并关联", systemImage: "magnifyingglass")
                        .frame(maxWidth: .infinity).padding(.vertical, 5)
                }.buttonStyle(.borderedProminent).accessibilityIdentifier("tracking.searchWork")
                Button("关联作品库中的作品", systemImage: "books.vertical") { choosingWork = true }
                    .accessibilityIdentifier("tracking.chooseLocalWork")
            } else {
                Text("作品类型不同，请选择相同类型的条目。").font(.caption).foregroundStyle(.secondary)
            }
        } header: { Text(target == nil ? "找到可阅读或播放的作品" : "关联到本地作品") }
          footer: { Text("关联后使用网站封面与简介；账号进度同步可在关联后单独开启。") }
    }

    @MainActor private func bind(_ target: IosSourceContent) async {
        guard let details else { return }
        saving = true; error = nil
        defer { saving = false }
        do {
            try await tracking.bind(content: target, item: details)
            bound = true
            if content == nil && sources.runtime != nil {
                // Refresh the parent after dismissal; publishing while pushing can replace the preview's state.
                openingWork = true
            } else {
                onBound()
                dismiss()
            }
        } catch { self.error = error.localizedDescription }
    }

    @ViewBuilder private func people(_ values: [IosTrackingPerson], title: String) -> some View {
        if !values.isEmpty {
            Section(title) {
                ForEach(Array(values.enumerated()), id: \.offset) { _, person in
                    HStack(spacing: 12) {
                        AsyncImage(url: person.image.flatMap(URL.init(string:))) { image in
                            image.resizable().scaledToFill()
                        } placeholder: { Circle().fill(.quaternary).overlay { Image(systemName: "person") } }
                            .frame(width: 42, height: 42).clipShape(.circle)
                        VStack(alignment: .leading, spacing: 3) {
                            if let url = person.url.flatMap(URL.init(string:)) { Link(person.name, destination: url) }
                            else { Text(person.name) }
                            if let role = person.role { Text(role).font(.caption).foregroundStyle(.secondary) }
                            ForEach(Array(person.voiceActors.enumerated()), id: \.offset) { _, actor in
                                if let url = actor.url.flatMap(URL.init(string:)) {
                                    Link("声优 · \(actor.name)", destination: url).font(.caption)
                                } else { Text("声优 · \(actor.name)").font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder private func related(_ values: [IosTrackingRelated], title: String) -> some View {
        if !values.isEmpty {
            Section(title) {
                ForEach(Array(values.enumerated()), id: \.offset) { _, entry in
                    Button { relatedPreview = TrackingPreviewSelection(item: entry.item, tracking: tracking) } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            TrackingItemRow(item: entry.item)
                            if !entry.relation.isEmpty { Text(entry.relation).font(.caption).foregroundStyle(.secondary) }
                        }
                    }.buttonStyle(.plain)
                }
            }
        }
    }

    @MainActor private func loadSupplement() async {
        supplementError = nil
        do {
            let value = try await tracking.supplement(item: item)
            if !Task.isCancelled { supplement = value }
        } catch { if !Task.isCancelled { supplementError = String(localized: "补充资料暂时无法读取，作品信息和关联功能仍可使用。") } }
    }

    @MainActor private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            let result = try await tracking.details(item: item, mediaKind: item.mediaKind)
            if !Task.isCancelled { details = result }
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}

private struct TrackingAccountRows: View {
    @ObservedObject var model: TrackingAccountsModel
    let sites: [IosTrackingSite]
    var body: some View {
        Section {
            ForEach(sites, id: \.id) { site in
                NavigationLink {
                    TrackingAccountView(site: site, model: model)
                } label: {
                    HStack(spacing: 12) {
                        Text(String(site.title.prefix(1))).font(.title3.bold())
                            .frame(width: 40, height: 40).background(.tint.opacity(0.12), in: .rect(cornerRadius: 12))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(site.title).font(.headline)
                            Text(model.accounts[site.id] ?? String(localized: "连接账号以同步进度"))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if model.accounts[site.id] != nil { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                    }
                }
                .accessibilityIdentifier("tracking.account.\(site.id)")
            }
        } footer: { Text("浏览公开作品资料无需登录。连接账号后，在作品详情中明确选择要追踪的条目。") }
    }
}

struct TrackingConnectionView: View {
    @AppStorage("tracking.bangumi.apiBase") private var base = "https://api.bgm.tv"
    @State private var mode = "official"
    @State private var custom = ""
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                Picker("公开资料接口", selection: $mode) {
                    Text("Bangumi 官方").tag("official")
                    Text("备用 · bangumi.pro").tag("mirror")
                    Text("自定义").tag("custom")
                }.accessibilityIdentifier("tracking.bangumi.endpoint")
                if mode == "custom" {
                    TextField("https://api.example.com", text: $custom)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("tracking.bangumi.customEndpoint")
                    Button("使用此接口") { saveCustom() }
                }
                if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
            } footer: {
                Text("只切换作品检索、榜单和公开资料。备用接口由第三方提供；账号授权和进度同步始终使用 Bangumi 官方接口。")
            }
        }
        .navigationTitle("Bangumi 连接").navigationBarTitleDisplayMode(.inline)
        .onAppear {
            custom = base
            mode = base == "https://api.bgm.tv" ? "official" : base == "https://api.bangumi.pro" ? "mirror" : "custom"
        }
        .onChange(of: mode) { _, value in
            message = nil
            if value == "official" { base = "https://api.bgm.tv" }
            if value == "mirror" { base = "https://api.bangumi.pro" }
        }
    }

    private func saveCustom() {
        let value = custom.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URLComponents(string: value), url.scheme?.lowercased() == "https",
              url.host?.isEmpty == false, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil else {
            message = String(localized: "请输入完整的 HTTPS 接口地址。")
            return
        }
        base = value.hasSuffix("/") ? String(value.dropLast()) : value
        message = String(localized: "已保存，返回后会重新读取作品资料。")
    }
}

private struct TrackingLocalWorkPicker: View {
    let mediaKind: String
    let onSelect: (IosSourceContent) -> Void
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var works: [IosSourceContent] {
        var seen = Set<String>()
        return (library.favourites + library.history).map(\.content).filter {
            $0.mediaKind == mediaKind && seen.insert($0.id).inserted &&
                (query.isEmpty || $0.title.localizedCaseInsensitiveContains(query))
        }
    }

    var body: some View {
        List {
            if works.isEmpty {
                ContentUnavailableView("还没有可关联的作品", systemImage: "books.vertical",
                                       description: Text("先从内容源打开或收藏作品，再选择对应的网站条目。"))
            }
            ForEach(works, id: \.id) { work in
                Button { onSelect(work) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(work.title).foregroundStyle(.primary)
                        Text(work.sourceName).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .searchable(text: $query, prompt: "搜索收藏和最近阅读")
        .navigationTitle("选择本地作品").navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
    }
}
