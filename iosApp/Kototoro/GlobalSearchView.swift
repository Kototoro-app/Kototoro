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
        for (name, status) in batch {
            if let index = rowIndex[name] {
                rows[index].status = status
            }
        }
        completed += batch.count
    }

    private func update(_ name: String, _ status: Status) {
        guard let index = rowIndex[name] else { return }
        rows[index].status = status
    }
}

struct GlobalSearchView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @StateObject private var model = GlobalSearchModel()
    @Binding var query: String
    var selectionMediaKind: String? = nil
    var onSelect: ((IosSourceContent) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var libraryMatches: [IosSourceContent] = []
    @State private var historyMatches: [IosSourceContent] = []
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
            if onSelect != nil, !sources.isLoading, model.submitted.isEmpty { submit(query) }
        }
        .onChange(of: [hiddenKinds, String(pinnedOnly), String(showAdult)] + targets.map(\.name)) { _, _ in
            if !model.submitted.isEmpty { submit(model.submitted) }
        }
        .onDisappear { if onSelect != nil { model.cancel() } }
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
            LazyVStack(alignment: .leading, spacing: 18) {
                HStack {
                    Text(selectionMediaKind.map(ContentSpace.title) ?? String(localized: "搜索范围"))
                        .font(.subheadline).foregroundStyle(.secondary)
                    Spacer()
                    filterMenu
                }.padding(.horizontal, 20)
                if model.submitted.isEmpty { suggestions } else { results }
            }
            .padding(.vertical, 12)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                    prompt: "搜索全部来源中的作品")
        .navigationTitle(onSelect == nil ? "搜索" : "搜索并选择作品")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if onSelect != nil {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
            }
        }
    }

    /// Enabled sources in browse order (pinned first, then manual order), after the search filters.
    private var targets: [IosSource] {
        let kinds = SourceFilterOption.decode(hiddenKinds)
        let enabled = sources.sources.filter { source in
            sources.isEnabled(source) && (showAdult || !source.isAdult) &&
                (selectionMediaKind.map { source.mediaKind == $0 } ?? !kinds.contains(source.mediaKind)) &&
                (!pinnedOnly || sources.isPinned(source))
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
                (showAdult || (!item.contentType.hasPrefix("HENTAI_") && item.contentRating != "ADULT")) &&
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

    @ViewBuilder
    private var suggestions: some View {
        if recent.isEmpty {
            ContentUnavailableView("搜索作品", systemImage: "magnifyingglass",
                                   description: Text("同时搜索作品库、历史和所有已启用的来源（\(targets.count) 个）。"))
                .padding(.top, 60)
        } else {
            HStack {
                Text("最近搜索").font(.headline)
                Spacer()
                Button("清除") { storedRecent = "" }.font(.subheadline)
                    .accessibilityIdentifier("search.recent.clear")
            }
            .padding(.horizontal, 20)
            FlowLayout(spacing: 8) {
                ForEach(recent, id: \.self) { text in
                    Button(text) { submit(text) }
                        .buttonStyle(.glass)
                        .accessibilityIdentifier("search.recent.\(text)")
                }
            }
            .padding(.horizontal, 16)
        }
    }

    // MARK: Results

    @ViewBuilder
    private var results: some View {
        HStack(spacing: 8) {
            if model.isSearching { ProgressView().controlSize(.small) }
            Text(model.isSearching ? "正在搜索 \(model.completed)/\(model.rows.count) 个来源"
                 : "已搜索 \(model.rows.count) 个来源")
                .font(.footnote).foregroundStyle(.secondary)
                .accessibilityIdentifier("search.progress")
            Spacer()
            let failed = model.rows.filter { if case .failed = $0.status { true } else { false } }.count
            if failed > 0, !model.isSearching, let runtime = sources.runtime {
                Button("重试 \(failed) 个失败的来源") { model.retryFailed(runtime: runtime) }.font(.footnote)
                    .accessibilityIdentifier("search.retry")
            }
        }
        .padding(.horizontal, 20)
        if let runtime = sources.runtime {
            localSection(String(localized: "作品库"), identifier: "search.library", matches: libraryMatches, runtime: runtime)
            localSection(String(localized: "历史"), identifier: "search.history", matches: historyMatches, runtime: runtime)
            ForEach(model.rows) { row in
                switch row.status {
                case .loading:
                    sourceHeader(row.source, count: nil, runtime: runtime)
                    ProgressView().frame(maxWidth: .infinity, minHeight: 60)
                case .failed(let message):
                    sourceHeader(row.source, count: nil, runtime: runtime)
                    Label(message, systemImage: "exclamationmark.triangle")
                        .font(.footnote).foregroundStyle(.orange).lineLimit(2)
                        .padding(.horizontal, 20)
                case .done(let found) where !found.isEmpty:
                    sourceHeader(row.source, count: found.count, runtime: runtime)
                    carousel(found, runtime: runtime)
                case .done:
                    EmptyView()
                }
            }
            let empty = model.rows.filter { if case .done(let found) = $0.status { found.isEmpty } else { false } }
            if !empty.isEmpty {
                Text("\(empty.count) 个来源没有结果").font(.footnote).foregroundStyle(.secondary)
                    .padding(.horizontal, 20)
                    .accessibilityIdentifier("search.emptySources")
            }
        }
    }

    @ViewBuilder
    private func localSection(_ title: String, identifier: String, matches: [IosSourceContent],
                              runtime: IosSources) -> some View {
        if !matches.isEmpty {
            HStack(spacing: 8) {
                Image(systemName: identifier == "search.library" ? "books.vertical" : "clock")
                    .foregroundStyle(.tint).frame(width: 24)
                Text(title).font(.headline)
                Text("\(matches.count)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 20)
            .accessibilityIdentifier(identifier)
            carousel(matches, runtime: runtime)
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
                Text("更多").font(.subheadline)
            }
            .accessibilityIdentifier("search.more.\(source.name)")
        }
        .padding(.horizontal, 20)
    }

    private func carousel(_ items: [IosSourceContent], runtime: IosSources) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(items, id: \.contentKey) { item in
                    Group {
                        if let onSelect {
                            Button { onSelect(item) } label: { resultCard(item, runtime: runtime) }
                        } else {
                            NavigationLink {
                                SourceDetailsView(runtime: runtime, content: item)
                            } label: { resultCard(item, runtime: runtime) }
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("search.result.\(item.sourceName).\(item.id)")
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private func resultCard(_ item: IosSourceContent, runtime: IosSources) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Color.clear.frame(width: 100, height: 150)
                .overlay { CoverImage(runtime: runtime, content: item) }
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            Text(item.title).font(.caption.weight(.medium)).lineLimit(2, reservesSpace: true)
                .fontDesign(.default).foregroundStyle(.primary)
        }.frame(width: 100)
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
