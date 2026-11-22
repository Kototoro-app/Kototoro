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

/// Loaded charts by selection. Browse builds this view anew whenever its segment or the space changes, so a cache in
/// the view's own state lasted only until the reader looked at another segment and every return reloaded the chart.
@MainActor private enum TrackingRecommendationsCache {
    static var entries: [String: (items: [IosTrackingItem], loadedAt: Date)] = [:]
}

/// Website charts in Browse, scoped to the current content space.
struct TrackingRecommendationsView: View {
    let spaceKind: String?
    let onPreview: (IosTrackingItem) -> Void
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
            "\(bangumiApiBase)|\(library.library != nil)"
    }
    private var visible: [IosTrackingItem] {
        loadedIdentity == identity ? Array(items.filter { spaceKind == nil || $0.mediaKind == spaceKind }) : []
    }
    private var siteTitle: String { availableSites.first { Int($0.id) == selectedSite }?.title ?? "Bangumi" }
    private var categoryTitle: String {
        sections.first { $0.id == selectedCategory }?.title ?? String(localized: "热门作品")
    }

    @AppStorage("listMode.websiteRecommendations") private var mode = ContentListMode.grid
    @AppStorage("list.gridSize.websiteRecommendations") private var gridSize = ContentGridSize.legacyDefault

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("\(siteTitle) · \(selectionSubtitle)").font(.subheadline).foregroundStyle(.secondary)
                    Spacer()
                    NavigationLink {
                        TrackingSitesView(initialMediaKind: mediaKind, initialService: selectedSite,
                                          initialCategory: selectedCategory, initialWeekday: selectedWeekday, followsSpace: true)
                    } label: { Label("追踪网站", systemImage: "chevron.right") }
                        .accessibilityIdentifier("sources.tracking")
                }
                if loading { ProgressView("正在读取网站推荐…") }
                if let error { Label(error, systemImage: "exclamationmark.triangle").font(.footnote) }
                if visible.isEmpty && !loading && error == nil {
                    ContentUnavailableView("此分区暂无作品", systemImage: "chart.bar")
                }
                if mode.isGrid {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: mode.targetWidth(scale: gridSize / 100)))]) {
                        ForEach(visible, id: \.key) { item in
                            Button { onPreview(item) } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    Color.clear.aspectRatio(mode.isMovie ? 16.0 / 9 : 2.0 / 3, contentMode: .fit)
                                        .overlay {
                                            AsyncImage(url: item.coverUrl.flatMap(URL.init(string:))) { image in
                                                image.resizable().scaledToFill()
                                            } placeholder: {
                                                Rectangle().fill(.quaternary).overlay { Image(systemName: "book.closed") }
                                            }
                                        }.clipped().clipShape(.rect(cornerRadius: 8))
                                    Text(item.title).font(.subheadline).lineLimit(2)
                                    if !mode.hasTitleOverlay {
                                        Text(subtitle(item)).font(.caption).foregroundStyle(.secondary)
                                    }
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            }.buttonStyle(.plain).accessibilityIdentifier("tracking.preview.\(item.remoteId)")
                        }
                    }
                } else {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(visible, id: \.key) { item in
                            Button { onPreview(item) } label: { TrackingItemRow(item: item) }
                                .buttonStyle(.plain).accessibilityIdentifier("tracking.preview.\(item.remoteId)")
                        }
                    }
                }
            }.padding()
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                ContentListModeMenu(mode: $mode, gridSize: $gridSize, modes: [.list, .grid, .movieGrid])
                selectionMenu
            }
        }
        .refreshable { await load(force: true) }
        .task(id: identity) { await load() }
        .onDisappear { request = nil; loading = false }
    }

    private var selectionMenu: some View {
        Menu {
            Button { Task { await load(force: true) } } label: {
                Label("刷新推荐", systemImage: "arrow.clockwise")
            }
            .disabled(loading)
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

    /// How long a loaded chart is shown again without asking the site; pull to refresh always asks.
    private static let freshness: TimeInterval = 15 * 60

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
        if !force && loadedIdentity == key && Date().timeIntervalSince(loadedAt) < Self.freshness { return }
        // A chart this selection loaded a little earlier, before the view was rebuilt: shown as it was.
        if !force, let cached = TrackingRecommendationsCache.entries[key],
           Date().timeIntervalSince(cached.loadedAt) < Self.freshness {
            items = cached.items
            loadedIdentity = key
            loadedAt = cached.loadedAt
            error = nil
            loading = false
            return
        }
        loading = true; error = nil
        defer { if request == token { loading = false } }
        guard let tracking = library.library?.tracking else { return }
        do {
            let result = try await requestItems(tracking: tracking)
            guard !Task.isCancelled, request == token, identity == key else { return }
            items = result
            loadedIdentity = key
            loadedAt = Date()
            TrackingRecommendationsCache.entries[key] = (result, loadedAt)
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
