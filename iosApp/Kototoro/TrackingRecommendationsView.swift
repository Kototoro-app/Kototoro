import KototoroShared
import SwiftUI

/// ISO weekdays select the current local week; each API receives the actual start/end instants of that day.
struct TrackingScheduleWindow {
    let start: Int64
    let end: Int64
    let date: Date

    init(weekday: Int, now: Date = Date()) {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
        let current = (calendar.component(.weekday, from: today) + 5) % 7 + 1
        let selected = (1...7).contains(weekday) ? weekday : current
        date = calendar.date(byAdding: .day, value: selected - current, to: today) ?? today
        start = Int64(date.timeIntervalSince1970)
        end = Int64((calendar.date(byAdding: .day, value: 1, to: date) ?? date.addingTimeInterval(86400))
            .timeIntervalSince1970)
    }
}

/// A compact chart shelf complements the personal artwork carousel; each space keeps a valid media scope.
struct TrackingRecommendationsView: View {
    let spaceKind: String?
    let onPreview: (IosTrackingItem) -> Void
    var compact = false
    var selectedFeed: Binding<String> = .constant("website")
    var collapsed: Binding<Bool> = .constant(false)
    @EnvironmentObject private var library: LibraryModel
    @AppStorage("browse.tracking.site") private var site = 5
    @AppStorage("browse.tracking.category") private var category = "calendar"
    @AppStorage("browse.tracking.weekday") private var weekday = 0
    @AppStorage("tracking.bangumi.apiBase") private var bangumiApiBase = "https://api.bgm.tv"
    @State private var items: [IosTrackingItem] = []
    @State private var loadedIdentity = ""
    @State private var loading = true
    @State private var error: String?
    @State private var request: UUID?
    @State private var loadedAt = Date.distantPast

    private var mediaKind: String {
        spaceKind ?? (availableSites.first { Int($0.id) == selectedSite }?.supports(mediaKind: "VIDEO") == true ?
            "VIDEO" : "MANGA")
    }
    private var availableSites: [IosTrackingSite] {
        let sites = library.library?.tracking.sites ?? []
        guard let spaceKind else { return sites }
        return sites.filter { $0.supports(mediaKind: spaceKind) }
    }
    private var selectedSite: Int { availableSites.contains { Int($0.id) == site } ? site : 5 }
    private var sections: [IosTrackingSection] {
        library.library?.tracking.recommendationSections(service: Int32(selectedSite), mediaKind: mediaKind) ?? []
    }
    private var selectedCategory: String {
        sections.contains { $0.id == category } ? category : "popular"
    }
    private var selectedWeekday: Int {
        (1...7).contains(weekday) ? weekday : (Calendar.current.component(.weekday, from: Date()) + 5) % 7 + 1
    }
    private var identity: String {
        "\(selectedSite)|\(spaceKind ?? "ALL")|\(selectedCategory)|\(TrackingScheduleWindow(weekday: selectedWeekday).start)|" +
            "\(bangumiApiBase)|\(library.library != nil)|\(collapsed.wrappedValue)"
    }
    private var visible: [IosTrackingItem] {
        loadedIdentity == identity ? Array(items.filter { spaceKind == nil || $0.mediaKind == spaceKind }.prefix(10)) : []
    }
    private var siteTitle: String { availableSites.first { Int($0.id) == selectedSite }?.title ?? "Bangumi" }
    private var categoryTitle: String {
        sections.first { $0.id == selectedCategory }?.title ?? String(localized: "热门作品")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 8 : 12) {
            HStack(alignment: .firstTextBaseline) {
                NavigationLink {
                    TrackingSitesView(initialMediaKind: mediaKind, initialService: selectedSite,
                                      initialCategory: selectedCategory, initialWeekday: selectedWeekday, followsSpace: true)
                        .navigationLinkIndicatorVisibility(.automatic)
                } label: {
                    HStack(spacing: 6) {
                        Text("网站推荐").font(.title3.bold())
                        Image(systemName: "chevron.right").font(.caption.bold()).foregroundStyle(.secondary)
                    }.foregroundStyle(.primary)
                }.accessibilityIdentifier("sources.tracking")
                Spacer(minLength: 6)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(siteTitle).lineLimit(1)
                    Text(selectionSubtitle).foregroundStyle(.secondary).lineLimit(1)
                }.font(.caption)
                if loading && !collapsed.wrappedValue { ProgressView().controlSize(.small) }
                selectionMenu
            }
            if collapsed.wrappedValue {
                EmptyView()
            } else if !visible.isEmpty {
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(visible, id: \.key) { item in
                            Button { onPreview(item) } label: {
                                RecommendationPoster(title: item.title, subtitle: subtitle(item), compact: compact) {
                                    AsyncImage(url: item.coverUrl.flatMap(URL.init(string:))) { image in
                                        image.resizable().scaledToFill()
                                    } placeholder: {
                                        Rectangle().fill(.quaternary).overlay { Image(systemName: "book.closed") }
                                    }
                                }
                            }.buttonStyle(.plain).accessibilityIdentifier("tracking.preview.\(item.remoteId)")
                        }
                    }.scrollTargetLayout()
                }
                .scrollIndicators(.hidden).scrollTargetBehavior(.viewAligned)
                .accessibilityIdentifier("tracking.shelf")
                if let error {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundStyle(.secondary)
                }
            } else if loading {
                ProgressView("正在读取网站推荐…").frame(maxWidth: .infinity, minHeight: 70, alignment: .leading)
            } else {
                HStack(alignment: .top) {
                    Text(error ?? String(localized: "此分区暂无作品"))
                        .font(.subheadline).foregroundStyle(.secondary)
                    Spacer()
                    Button("重试") { Task { await load(force: true) } }.font(.subheadline)
                }.accessibilityIdentifier("tracking.shelf.status")
            }
        }
        .buttonStyle(.plain)
        .navigationLinkIndicatorVisibility(.hidden)
        .task(id: identity) { await load() }
        .onDisappear { request = nil }
    }

    private var selectionMenu: some View {
        Menu {
            Button { Task { await load(force: true) } } label: {
                Label("刷新推荐", systemImage: "arrow.clockwise")
            }
            .disabled(loading || collapsed.wrappedValue)
            .accessibilityIdentifier("recommendations.refresh")
            Divider()
            Picker("追踪网站", selection: Binding(get: { selectedSite }, set: { site = $0 })) {
                ForEach(availableSites, id: \.id) { Text($0.title).tag(Int($0.id)) }
            }.pickerStyle(.menu)
            Picker("分区", selection: Binding(get: { selectedCategory }, set: { category = $0 })) {
                ForEach(sections, id: \.id) { Text($0.title).tag($0.id) }
            }.pickerStyle(.menu)
            if selectedCategory.hasPrefix("calendar") {
                Picker("放送日", selection: $weekday) {
                    Text("今天").tag(0)
                    ForEach(1...7, id: \.self) { Text(Self.weekdayTitle($0)).tag($0) }
                }.pickerStyle(.menu)
            }
            if compact {
                Divider()
                BrowseRecommendationActions(selectedFeed: selectedFeed, collapsed: collapsed)
            }
        } label: {
            Label("推荐选项", systemImage: "ellipsis").labelStyle(.iconOnly)
                .frame(minWidth: 32, minHeight: 32).contentShape(Rectangle())
        }.accessibilityIdentifier("recommendations.more")
    }

    private var selectionSubtitle: String {
        let title = selectedCategory.hasPrefix("calendar") ?
            "\(categoryTitle) · \(Self.weekdayTitle(selectedWeekday))" : categoryTitle
        return title
    }

    static func weekdayTitle(_ day: Int) -> String {
        let names = [String(localized: "周一"), String(localized: "周二"), String(localized: "周三"),
                     String(localized: "周四"), String(localized: "周五"), String(localized: "周六"), String(localized: "周日")]
        return names[min(max(day, 1), 7) - 1]
    }

    private func subtitle(_ item: IosTrackingItem) -> String {
        if let score = item.score { return String(format: "★ %.1f", score.floatValue / item.scoreMax * 10) }
        return siteTitle
    }

    @MainActor private func load(force: Bool = false) async {
        let token = UUID()
        request = token
        let key = identity
        if !force && loadedIdentity == key && Date().timeIntervalSince(loadedAt) < 15 * 60 { return }
        loading = true; error = nil
        defer { if request == token { loading = false } }
        guard !collapsed.wrappedValue, let tracking = library.library?.tracking else { return }
        do {
            let result = try await requestItems(tracking: tracking)
            guard !Task.isCancelled, request == token, identity == key else { return }
            items = result
            loadedIdentity = key
            loadedAt = Date()
        } catch {
            guard !Task.isCancelled, request == token, identity == key else { return }
            self.error = error.localizedDescription
        }
    }

    @MainActor private func requestItems(tracking: IosTracking) async throws -> [IosTrackingItem] {
        let service = Int32(selectedSite)
        let category = selectedCategory
        let day = Int32(selectedWeekday)
        let window = TrackingScheduleWindow(weekday: selectedWeekday)
        let kinds: [String]
        if let spaceKind { kinds = [spaceKind] }
        else if category.hasPrefix("calendar") { kinds = ["VIDEO"] }
        else {
            let site = availableSites.first { $0.id == service }
            kinds = ["MANGA", "NOVEL", "VIDEO"].filter { site?.supports(mediaKind: $0) == true }
        }
        var batches: [Int: [IosTrackingItem]] = [:]
        var firstError: Error?
        await withTaskGroup(of: (Int, Result<[IosTrackingItem], Error>).self) { group in
            for (index, kind) in kinds.enumerated() {
                group.addTask {
                    do {
                        let items = try await tracking.recommendations(service: service, mediaKind: kind,
                            category: category, weekday: day, page: 1, dayStart: window.start, dayEnd: window.end)
                        return (index, .success(items.filter { $0.mediaKind == kind }))
                    } catch { return (index, .failure(error)) }
                }
            }
            for await (index, result) in group {
                switch result {
                case .success(let items): batches[index] = items
                case .failure(let error): if firstError == nil { firstError = error }
                }
            }
        }
        if batches.isEmpty, let firstError { throw firstError }
        // All-space charts interleave supported kinds instead of adding a second media-type switch.
        var result: [IosTrackingItem] = []
        var keys = Set<String>()
        let count = batches.values.map(\.count).max() ?? 0
        for offset in 0..<count {
            for index in kinds.indices {
                if let batch = batches[index], offset < batch.count, keys.insert(batch[offset].key).inserted {
                    result.append(batch[offset])
                }
            }
        }
        return result
    }
}

/// One compact, optional discovery strip keeps source navigation as Browse's primary content.
struct BrowseRecommendationsView: View {
    let spaceKind: String?
    let onPreview: (IosTrackingItem) -> Void
    @AppStorage("browse.recommendations.feed") private var selectedFeed = "personal"
    @AppStorage("browse.recommendations.collapsed") private var collapsed = false

    var body: some View {
        Group {
            if selectedFeed == "website" {
                TrackingRecommendationsView(spaceKind: spaceKind, onPreview: onPreview, compact: true,
                    selectedFeed: $selectedFeed, collapsed: $collapsed)
            } else {
                SuggestionsView(previewLimit: 6, previewKind: spaceKind, compact: true,
                    selectedFeed: $selectedFeed, collapsed: $collapsed)
            }
        }
    }
}

struct BrowseRecommendationActions: View {
    @Binding var selectedFeed: String
    @Binding var collapsed: Bool

    var body: some View {
        Picker("推荐内容", selection: $selectedFeed) {
            Text("为你推荐").tag("personal")
            Text("网站推荐").tag("website")
        }.pickerStyle(.menu)
        Button { collapsed.toggle() } label: {
            Label(collapsed ? "展开推荐" : "收起推荐", systemImage: collapsed ? "chevron.down" : "chevron.up")
        }
        .accessibilityIdentifier("recommendations.collapse")
    }
}
