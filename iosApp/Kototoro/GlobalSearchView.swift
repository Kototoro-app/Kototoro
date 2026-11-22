import KototoroShared
import SwiftUI

/// Android's multi-source search: every enabled source (pinned first, then the manual order) is queried with at most
/// bounded requests in flight; each source keeps its own row, state and retry, and the library and history are searched
/// locally first.
@MainActor
final class GlobalSearchModel: ObservableObject {
    enum Status {
        case loading
        case done([IosSourceContent])
        case failed(String)
    }

    struct Row: Identifiable {
        let source: IosSource
        var status: Status
        var id: String { source.name }
    }

    @Published private(set) var rows: [Row] = []
    /// Sources that answered with works, in the order they answered. Results are shown in this order so a source
    /// finishing later only adds below what is on screen; rows never move or collapse under the reader.
    @Published private(set) var arrived: [Row] = []
    @Published private(set) var completed = 0
    @Published private(set) var isSearching = false
    @Published private(set) var submitted = ""
    private var task: Task<Void, Never>?
    private static let parallelism = 6
    private var rowIndex: [String: Int] = [:]

    func search(_ query: String, sources: [IosSource], runtime: IosSources) {
        task?.cancel()
        submitted = query
        rows = sources.map { Row(source: $0, status: .loading) }
        arrived = []
        rowIndex = Dictionary(uniqueKeysWithValues: sources.enumerated().map { ($0.element.name, $0.offset) })
        completed = 0
        isSearching = !sources.isEmpty
        task = Task { await run(query, sources: sources, runtime: runtime) }
    }

    func retryFailed(runtime: IosSources) {
        let failed = rows.filter { if case .failed = $0.status { true } else { false } }.map(\.source)
        guard !failed.isEmpty, !isSearching else { return }
        for source in failed { update(source.name, .loading) }
        completed -= failed.count
        isSearching = true
        let query = submitted
        task = Task { await run(query, sources: failed, runtime: runtime) }
    }

    func cancel() {
        task?.cancel()
        task = nil
        submitted = ""
        rows = []
        arrived = []
        rowIndex = [:]
        completed = 0
        isSearching = false
    }

    private func run(_ query: String, sources: [IosSource], runtime: IosSources) async {
        await withTaskGroup(of: (String, Status).self) { group in
            var pending = sources[...]
            func startNext() {
                guard !Task.isCancelled, let source = pending.popFirst() else { return }
                group.addTask {
                    do {
                        let found = try await runtime.filteredPage(sourceName: source.name, page: 0, loadedCount: 0,
                                                                   query: query, selection: nil)
                        var seen = Set<String>()
                        return (source.name, .done(found.filter { seen.insert(ContentMarks.key($0)).inserted }))
                    } catch {
                        return (source.name, .failed(SourceErrorText.describe(error)))
                    }
                }
            }
            for _ in 0..<min(Self.parallelism, sources.count) {
                startNext()
            }
            var buffer: [(String, Status)] = []
            var lastFlush = Date()
            for await (name, status) in group {
                guard !Task.isCancelled else { break }
                buffer.append((name, status))
                let now = Date()
                if buffer.count >= 4 || now.timeIntervalSince(lastFlush) >= 0.15 {
                    applyBatch(buffer)
                    buffer.removeAll(keepingCapacity: true)
                    lastFlush = now
                }
                startNext()
            }
            if !Task.isCancelled && !buffer.isEmpty {
                applyBatch(buffer)
                buffer.removeAll()
            }
        }
        if !Task.isCancelled { isSearching = false }
    }

    private func applyBatch(_ batch: [(String, Status)]) {
        var found: [(index: Int, row: Row)] = []
        for (name, status) in batch {
            if let index = rowIndex[name] {
                rows[index].status = status
                if case .done(let works) = status, !works.isEmpty { found.append((index, rows[index])) }
            }
        }
        // One batch keeps the browse order (pinned first) among the sources that arrived together.
        arrived.append(contentsOf: found.sorted { $0.index < $1.index }.map(\.row))
        completed += batch.count
    }

    private func update(_ name: String, _ status: Status) {
        guard let index = rowIndex[name] else { return }
        rows[index].status = status
    }
}

struct GlobalSearchView: View {
    /// What the results show: everything grouped by where it was found, or one place as a full grid.
    private enum Scope: Hashable {
        case all, library, history
        case source(String)
    }

    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var downloads: DownloadedContent
    @StateObject private var model = GlobalSearchModel()
    @Binding var query: String
    var selectionMediaKind: String? = nil
    var onSelect: ((IosSourceContent) -> Void)? = nil
    /// Searches `query` as soon as the sources are ready, for a sheet opened on a known title.
    var autoSubmit = false
    @Environment(\.dismiss) private var dismiss
    @State private var libraryMatches: [IosSourceContent] = []
    @State private var historyMatches: [IosSourceContent] = []
    @State private var scope = Scope.all
    @State private var problemsExpanded = false
    @AppStorage("search.recent") private var storedRecent = ""
    @AppStorage("search.kinds") private var hiddenKinds = ""
    @AppStorage("search.pinnedOnly") private var pinnedOnly = false
    @AppStorage("sources.showAdult") private var showAdult = false

    private var recent: [String] { storedRecent.split(separator: "\n").map(String.init) }

    var body: some View {
        Group {
            if onSelect == nil { NavigationStack { page } }
            else { page }
        }
        .onSubmit(of: .search) { submit(query) }
        .task(id: "\(sources.isLoading)|\(sources.catalogRevision)|\(sources.runtime != nil)") {
            if onSelect != nil || autoSubmit, !sources.isLoading, model.submitted.isEmpty { submit(query) }
        }
        .onChange(of: [hiddenKinds, String(pinnedOnly), String(showAdult)] + targets.map(\.name)) { _, _ in
            if !model.submitted.isEmpty { submit(model.submitted) }
        }
        .onChange(of: model.submitted) { _, _ in
            scope = .all
            problemsExpanded = false
        }
        .onDisappear { if onSelect != nil || autoSubmit { model.cancel() } }
        .onChange(of: query) { _, value in
            if value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                model.cancel()
                libraryMatches = []
                historyMatches = []
            }
        }
    }

    private var page: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                if model.submitted.isEmpty { idle } else { results }
            }
            .padding(.vertical, 12)
            .animation(.snappy(duration: 0.25), value: scope)
        }
        .scrollDismissesKeyboard(.immediately)
        .background(Color(uiColor: .systemGroupedBackground))
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                    prompt: "搜索全部来源中的作品")
        // The filters stay in the navigation bar while the field is active instead of being hidden with it.
        .searchPresentationToolbarBehavior(.avoidHidingContent)
        // While typing, earlier searches that start the same way are offered, as in Music and App Store; one tap searches.
        .searchSuggestions {
            let typed = query.trimmingCharacters(in: .whitespaces)
            if !typed.isEmpty, model.submitted != typed {
                ForEach(recent.filter { $0 != typed && $0.localizedStandardRange(of: typed)?.lowerBound == $0.startIndex }
                    .prefix(5), id: \.self) { text in
                    Button { submit(text) } label: { Label(text, systemImage: "clock.arrow.circlepath") }
                        .accessibilityIdentifier("search.suggestion.\(text)")
                }
            }
        }
        .navigationTitle(onSelect == nil ? "搜索" : "搜索并选择作品")
        .navigationSubtitle(selectionMediaKind.map(ContentSpace.title) ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if onSelect != nil {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
            } else if autoSubmit {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
            ToolbarItem(placement: .topBarTrailing) { filterMenu }
        }
    }

    /// Enabled sources in browse order (pinned first, then manual order), after the search filters.
    private var targets: [IosSource] {
        let kinds = SourceFilterOption.decode(hiddenKinds)
        let custom = ContentSpace.activeCustom()
        let enabled = sources.sources.filter { source in
            sources.isEnabled(source) && (showAdult || !source.isAdult) &&
                (selectionMediaKind.map { source.mediaKind == $0 } ?? !kinds.contains(source.mediaKind)) &&
                (!pinnedOnly || sources.isPinned(source)) && (custom?.admits(source: source) ?? true)
        }
        let ordered = sources.ordered(enabled)
        return ordered.filter(sources.isPinned) + ordered.filter { !sources.isPinned($0) }
    }

    private func submit(_ text: String) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, let runtime = sources.runtime else { return }
        query = value
        // Association searches are transient: broadcasting recent-search defaults can recreate the sheet host.
        if onSelect == nil {
            storedRecent = ([value] + recent.filter { $0 != value }).prefix(12).joined(separator: "\n")
        }
        let kinds = SourceFilterOption.decode(hiddenKinds)
        let pinned = Set(sources.sources.filter(sources.isPinned).map(\.name))
        func matches(_ item: IosSourceContent) -> Bool {
            (selectionMediaKind.map { item.mediaKind == $0 } ?? !kinds.contains(item.mediaKind)) &&
                (showAdult || !item.isNsfw) &&
                (!pinnedOnly || pinned.contains(item.sourceName)) &&
                (item.title.localizedCaseInsensitiveContains(value) ||
                    item.altTitles.contains { $0.localizedCaseInsensitiveContains(value) })
        }
        libraryMatches = library.favourites.map(\.content).filter {
            matches($0)
        }
        historyMatches = library.history.map(\.content).filter {
            matches($0)
        }
        model.search(value, sources: targets, runtime: runtime)
    }

    // MARK: Before searching

    /// Pinned sources first, then the most recently used: the places a search here most often continues into.
    private var featuredSources: [IosSource] {
        let list = targets
        let pinned = list.filter(sources.isPinned)
        return Array((pinned + sources.ordered(list.filter { !sources.isPinned($0) }, by: .lastUsed)).prefix(8))
    }

    @ViewBuilder
    private var idle: some View {
        let featured = featuredSources
        if !recent.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                sectionTitle(String(localized: "最近搜索")) {
                    Button("清除") { storedRecent = "" }.font(.subheadline)
                        .accessibilityIdentifier("search.recent.clear")
                }
                FlowLayout(spacing: 8) {
                    ForEach(recent, id: \.self) { text in
                        Button { submit(text) } label: {
                            Label(text, systemImage: "clock.arrow.circlepath").lineLimit(1)
                        }
                        .buttonStyle(.glass)
                        .contextMenu {
                            Button("删除", systemImage: "trash", role: .destructive) {
                                storedRecent = recent.filter { $0 != text }.joined(separator: "\n")
                            }
                        }
                        .accessibilityIdentifier("search.recent.\(text)")
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        if let runtime = sources.runtime, !featured.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                sectionTitle(String(localized: "浏览来源")) { EmptyView() }
                Text("搜索会同时查找作品库、历史和 \(targets.count) 个已启用的来源。")
                    .font(.footnote).foregroundStyle(.secondary)
                    .padding(.horizontal, 20)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 12)], spacing: 12) {
                    ForEach(featured, id: \.name) { source in sourceTile(source, runtime: runtime) }
                }
                .padding(.horizontal, 16)
            }
        }
        if recent.isEmpty && featured.isEmpty {
            ContentUnavailableView("搜索作品", systemImage: "magnifyingglass",
                                   description: Text("同时搜索作品库、历史和所有已启用的来源（\(targets.count) 个）。"))
                .padding(.top, 60)
        }
    }

    /// A source as a tile: its artwork, blurred, tints the card the way category tiles in Music carry their art.
    private func sourceTile(_ source: IosSource, runtime: IosSources) -> some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        return NavigationLink {
            SourceBrowseView(runtime: runtime, source: source, onPick: onSelect)
        } label: {
            HStack(spacing: 10) {
                SourceIcon(runtime: runtime, source: source, size: 38)
                    .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
                VStack(alignment: .leading, spacing: 2) {
                    Text(source.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
                    Text(SourcesView.kindName(source.mediaKind)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                if sources.isPinned(source) {
                    Image(systemName: "pin.fill").font(.caption2).foregroundStyle(.secondary)
                        .accessibilityLabel("已置顶")
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 66, alignment: .leading)
            .background {
                SourceIcon(runtime: runtime, source: source, size: 120)
                    .scaleEffect(1.6)
                    .blur(radius: 30)
                    .opacity(0.4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .allowsHitTesting(false)
            }
            .background(.background.secondary)
            .clipShape(shape)
            .overlay { shape.strokeBorder(.primary.opacity(0.08), lineWidth: 0.5) }
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("search.browse.\(source.name)")
    }

    // MARK: Results

    private var failedRows: [(source: IosSource, message: String)] {
        model.rows.compactMap { if case .failed(let message) = $0.status { ($0.source, message) } else { nil } }
    }

    private var emptySourceCount: Int {
        model.rows.filter { if case .done(let found) = $0.status { found.isEmpty } else { false } }.count
    }

    private func found(_ row: GlobalSearchModel.Row) -> [IosSourceContent] {
        if case .done(let works) = row.status { works } else { [] }
    }

    private var totalFound: Int {
        libraryMatches.count + historyMatches.count + model.arrived.reduce(0) { $0 + found($1).count }
    }

    @ViewBuilder
    private var results: some View {
        if let runtime = sources.runtime {
            if totalFound > 0 { scopeBar(runtime: runtime) }
            statusLine
            switch scope {
            case .all: overview(runtime: runtime)
            case .library: grid(libraryMatches, runtime: runtime)
            case .history: grid(historyMatches, runtime: runtime)
            case .source(let name):
                if let row = model.arrived.first(where: { $0.source.name == name }) {
                    sourceHeader(row.source, count: nil, runtime: runtime)
                    grid(found(row), runtime: runtime)
                }
            }
        }
    }

    private var statusLine: some View {
        HStack(spacing: 8) {
            if model.isSearching {
                ProgressView(value: Double(model.completed), total: Double(max(model.rows.count, 1)))
                    .progressViewStyle(.circular).controlSize(.small)
            }
            Text(model.isSearching ? "正在搜索 \(model.completed)/\(model.rows.count) 个来源"
                 : "已搜索 \(model.rows.count) 个来源，找到 \(totalFound) 部")
                .font(.footnote).foregroundStyle(.secondary)
                .contentTransition(.numericText())
                .accessibilityIdentifier("search.progress")
            Spacer()
        }
        .padding(.horizontal, 20)
    }

    /// Where the works were found, as one row of chips: "All" groups everything, any other chip shows that place alone.
    private func scopeBar(runtime: IosSources) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    scopeChip(.all, title: String(localized: "全部"), count: totalFound, identifier: "search.scope.all") {
                        EmptyView()
                    }
                    if !libraryMatches.isEmpty {
                        scopeChip(.library, title: String(localized: "作品库"), count: libraryMatches.count,
                                  identifier: "search.scope.library") { Image(systemName: "books.vertical") }
                    }
                    if !historyMatches.isEmpty {
                        scopeChip(.history, title: String(localized: "历史"), count: historyMatches.count,
                                  identifier: "search.scope.history") { Image(systemName: "clock") }
                    }
                    ForEach(model.arrived) { row in
                        scopeChip(.source(row.source.name), title: row.source.title, count: found(row).count,
                                  identifier: "search.scope.\(row.source.name)") {
                            SourceIcon(runtime: runtime, source: row.source, size: 20)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 2)
            }
        }
        .scrollClipDisabled()
    }

    @ViewBuilder
    private func scopeChip(_ value: Scope, title: String, count: Int, identifier: String,
                           @ViewBuilder icon: () -> some View) -> some View {
        let button = Button { scope = value } label: {
            HStack(spacing: 6) {
                icon()
                Text(title).lineLimit(1)
                Text("\(count)").monospacedDigit().opacity(0.7)
            }
            .font(.subheadline.weight(.medium))
        }
        .accessibilityIdentifier(identifier)
        .accessibilityAddTraits(scope == value ? .isSelected : [])
        if scope == value { button.buttonStyle(.glassProminent) } else { button.buttonStyle(.glass) }
    }

    @ViewBuilder
    private func overview(runtime: IosSources) -> some View {
        localSection(String(localized: "作品库"), scope: .library, identifier: "search.library", icon: "books.vertical",
                     matches: libraryMatches, runtime: runtime)
        localSection(String(localized: "历史"), scope: .history, identifier: "search.history", icon: "clock",
                     matches: historyMatches, runtime: runtime)
        // Results append in arrival order, so a source answering later only adds below what is on screen.
        ForEach(model.arrived) { row in
            let works = found(row)
            VStack(alignment: .leading, spacing: 10) {
                sourceHeader(row.source, count: works.count, runtime: runtime)
                carousel(works, runtime: runtime)
            }
        }
        if model.isSearching && totalFound == 0 {
            ProgressView().frame(maxWidth: .infinity, minHeight: 120)
        } else if !model.isSearching && totalFound == 0 {
            ContentUnavailableView.search(text: model.submitted)
                .padding(.top, 24)
                .accessibilityIdentifier("search.noResults")
        }
        problems
    }

    /// Failed and empty sources, folded into one card below the results instead of a row each.
    @ViewBuilder
    private var problems: some View {
        let failed = failedRows
        let empty = emptySourceCount
        if !failed.isEmpty || empty > 0 {
            VStack(alignment: .leading, spacing: 0) {
                if !failed.isEmpty {
                    DisclosureGroup(isExpanded: $problemsExpanded) {
                        VStack(alignment: .leading, spacing: 10) {
                            ForEach(failed, id: \.source.name) { item in
                                HStack(alignment: .top, spacing: 10) {
                                    SourceIcon(runtime: sources.runtime, source: item.source, size: 22)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.source.title).font(.subheadline.weight(.medium))
                                            .accessibilityIdentifier("search.failed.\(item.source.name)")
                                        Text(item.message).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                    }
                                }
                            }
                        }
                        .padding(.top, 10)
                    } label: {
                        HStack {
                            Label("\(failed.count) 个来源搜索失败", systemImage: "exclamationmark.triangle.fill")
                                .font(.subheadline).foregroundStyle(.orange)
                            Spacer()
                            if !model.isSearching, let runtime = sources.runtime {
                                Button("重试") { model.retryFailed(runtime: runtime) }
                                    .font(.subheadline.weight(.semibold))
                                    .buttonStyle(.borderless)
                                    .accessibilityIdentifier("search.retry")
                            }
                        }
                    }
                    .tint(.secondary)
                }
                if !failed.isEmpty && empty > 0 { Divider().padding(.vertical, 10) }
                if empty > 0 {
                    Label("\(empty) 个来源没有结果", systemImage: "tray")
                        .font(.subheadline).foregroundStyle(.secondary)
                        .accessibilityIdentifier("search.emptySources")
                }
            }
            .padding(14)
            .background(.background.secondary, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .padding(.horizontal, 16)
        }
    }

    @ViewBuilder
    private func localSection(_ title: String, scope value: Scope, identifier: String, icon: String,
                              matches: [IosSourceContent], runtime: IosSources) -> some View {
        if !matches.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: icon).foregroundStyle(.tint).frame(width: 24)
                    Text(title).font(.headline)
                    Text("\(matches.count)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                    Spacer()
                    if matches.count > 3 { moreButton { scope = value } }
                }
                .padding(.horizontal, 20)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier(identifier)
                carousel(matches, runtime: runtime)
            }
        }
    }

    private func sourceHeader(_ source: IosSource, count: Int?, runtime: IosSources) -> some View {
        HStack(spacing: 8) {
            SourceIcon(runtime: runtime, source: source, size: 24)
            Text(source.title).font(.headline).lineLimit(1)
                .accessibilityIdentifier("search.source.\(source.name)")
            if let count { Text("\(count)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary) }
            Spacer()
            NavigationLink {
                SourceBrowseView(runtime: runtime, source: source, initialQuery: model.submitted, onPick: onSelect)
                    .submitScope()
            } label: {
                moreLabel
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("search.more.\(source.name)")
        }
        .padding(.horizontal, 20)
    }

    private var moreLabel: some View {
        HStack(spacing: 2) {
            Text("更多")
            Image(systemName: "chevron.forward").font(.caption.weight(.semibold))
        }
        .font(.subheadline)
        .foregroundStyle(.tint)
    }

    private func moreButton(_ action: @escaping () -> Void) -> some View {
        Button(action: action) { moreLabel }.buttonStyle(.plain)
    }

    private func carousel(_ items: [IosSourceContent], runtime: IosSources) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 14) {
                ForEach(items, id: \.contentKey) { item in
                    resultLink(item, runtime: runtime).frame(width: 108)
                }
            }
            .padding(.horizontal, 20)
        }
        .scrollClipDisabled()
    }

    private func grid(_ items: [IosSourceContent], runtime: IosSources) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 14, alignment: .top)], spacing: 18) {
            ForEach(items, id: \.contentKey) { item in resultLink(item, runtime: runtime) }
        }
        .padding(.horizontal, 16)
    }

    private func resultLink(_ item: IosSourceContent, runtime: IosSources) -> some View {
        let card = GridCard(runtime: runtime, item: ContentItem(content: item, subtitle: detailLine(item), marks: marks(item)))
        return Group {
            if let onSelect {
                Button { onSelect(item) } label: { card.contentShape(Rectangle()) }
            } else {
                NavigationLink { SourceDetailsView(runtime: runtime, content: item) } label: {
                    card.contentShape(Rectangle())
                }
            }
        }
        .buttonStyle(.plain)
        .contextMenu {
            if onSelect == nil, let url = URL(string: item.publicUrl), url.scheme?.hasPrefix("http") == true {
                ShareLink(item: url) { Label("分享", systemImage: "square.and.arrow.up") }
            }
        }
        .accessibilityIdentifier("search.result.\(item.sourceName).\(item.id)")
    }

    /// The rating (out of five, as the details page shows it) and the first author, when the source gives them.
    private func detailLine(_ item: IosSourceContent) -> String? {
        let rating = item.rating > 0 ? "★ " + String(format: "%.1f", Double(item.rating) * 5) : nil
        let parts = [rating, item.authors.first].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func marks(_ item: IosSourceContent) -> ContentMarks {
        let key = ContentMarks.key(item)
        return ContentMarks(newChapters: library.updateChapterCounts[key] ?? 0,
                            favourite: library.favouriteKeys.contains(key),
                            downloaded: downloads.keys.contains(key), adult: item.isNsfw)
    }

    private func sectionTitle(_ title: String, @ViewBuilder trailing: () -> some View) -> some View {
        HStack {
            Text(title).font(.title3.weight(.bold))
            Spacer()
            trailing()
        }
        .padding(.horizontal, 20)
    }

    private var filterMenu: some View {
        Menu {
            if selectionMediaKind == nil {
                Section("内容类型") {
                    ForEach(SourceFilterOption.kinds, id: \.id) { kind in
                        Toggle(isOn: Binding(get: { !SourceFilterOption.decode(hiddenKinds).contains(kind.id) }, set: { visible in
                            var hidden = SourceFilterOption.decode(hiddenKinds)
                            if visible { hidden.remove(kind.id) } else { hidden.insert(kind.id) }
                            hiddenKinds = SourceFilterOption.encode(hidden)
                        })) { Label(kind.title, systemImage: kind.icon) }
                    }
                }
            }
            Toggle("仅搜索置顶来源", systemImage: "pin", isOn: $pinnedOnly)
            Toggle("包含成人内容源", isOn: $showAdult)
        } label: {
            Label("筛选", systemImage: hiddenKinds.isEmpty && !pinnedOnly
                  ? "line.3.horizontal.decrease" : "line.3.horizontal.decrease.circle.fill")
        }
        .menuActionDismissBehavior(.disabled)
        .accessibilityIdentifier("search.filter")
    }
}
