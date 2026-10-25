import Combine
import KototoroShared
import SwiftUI

/// Owns the routed source runtime for the app's lifetime: Apple CMS sites run natively, extensions use the JVM.
@MainActor
final class SourcesModel: ObservableObject {
    @Published private(set) var sources: [IosSource] = []
    @Published private(set) var catalogRevision = 0
    @Published private(set) var extensionError: String?
    @Published private(set) var error: String?
    @Published private(set) var isLoading = false
    @Published private(set) var runtime: IosSources?
    /// Shared source switches, pins, manual positions and usage metrics; absent rows use defaults.
    @Published private(set) var settings: [String: IosSourceSetting] = [:]
    nonisolated(unsafe) private var ownedRuntime: IosSources?
    private var videoSources: IosVideoSources?
    private var library: IosLibrary?
    private let appVersion = max(
        1, Int32(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1") ?? 1
    )
    let previewLabel: String? = {
        guard let url = Bundle.main.resourceURL?.appendingPathComponent("jvm/sources/preview.json"),
              let data = try? Data(contentsOf: url),
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return nil }
        return value["label"]
    }()

    var videoSourceList: [IosSource] { sources.filter { $0.kind == "video" } }
    var extensionSourceList: [IosSource] { sources.filter { $0.kind == "extension" } }

    static let ecosystems: [(id: String, title: String, icon: String)] = [
        ("LOCAL", String(localized: "本地作品"), "folder"),
        ("MIHON", String(localized: "Mihon 漫画"), "book.closed"),
        ("TSUNDOKU", String(localized: "Tsundoku 小说"), "text.book.closed"),
        ("ANIYOMI", String(localized: "Aniyomi 动漫"), "play.rectangle"),
        ("JAR", String(localized: "解析器插件（kototoro / kotatsu / UMA）"), "shippingbox"),
        ("TVBOX", String(localized: "苹果 CMS / TVBox 影视"), "play.tv"),
    ]

    func isEnabled(_ source: IosSource) -> Bool { settings[source.name]?.enabled ?? Self.defaultEnabled(source) }
    func isPinned(_ source: IosSource) -> Bool { settings[source.name]?.pinned ?? false }
    func isNewSource(_ source: IosSource) -> Bool { settings[source.name]?.addedIn == appVersion }

    /// The unsaved browse order groups ecosystems before preserving each catalog's own positions.
    private static func catalogOrder(_ list: [IosSource]) -> [IosSource] {
        let browse = ecosystems.flatMap { ecosystem in
            list.filter { SourceFilterOption.ecosystemKey($0) == ecosystem.id }
        }
        let known = Set(browse.map(\.name))
        return browse + list.filter { !known.contains($0.name) }
    }

    /// Manual order: saved positions first, then the catalog's own order for sources never arranged.
    func ordered(_ list: [IosSource]) -> [IosSource] {
        list.enumerated().sorted { lhs, rhs in
            let left = settings[lhs.element.name]?.sortKey ?? Int32.max
            let right = settings[rhs.element.name]?.sortKey ?? Int32.max
            return left != right ? left < right : lhs.offset < rhs.offset
        }.map(\.element)
    }

    /// Ties retain manual order; sections keep pinned sources first independently of the selected sort.
    func ordered(_ list: [IosSource], by sort: SourceSort) -> [IosSource] {
        guard !list.isEmpty else { return [] }
        if sort == .manual { return ordered(list) }
        return list.enumerated().sorted { lhs, rhs in
            switch sort {
            case .manual:
                let left = settings[lhs.element.name]?.sortKey ?? Int32.max
                let right = settings[rhs.element.name]?.sortKey ?? Int32.max
                return left != right ? left < right : lhs.offset < rhs.offset
            case .title:
                let comparison = lhs.element.title.localizedStandardCompare(rhs.element.title)
                if comparison != .orderedSame { return comparison == .orderedAscending }
                let left = settings[lhs.element.name]?.sortKey ?? Int32.max
                let right = settings[rhs.element.name]?.sortKey ?? Int32.max
                return left != right ? left < right : lhs.offset < rhs.offset
            case .lastUsed:
                let left = settings[lhs.element.name]?.lastUsedAt ?? 0
                let right = settings[rhs.element.name]?.lastUsedAt ?? 0
                if left != right { return left > right }
                let leftKey = settings[lhs.element.name]?.sortKey ?? Int32.max
                let rightKey = settings[rhs.element.name]?.sortKey ?? Int32.max
                return leftKey != rightKey ? leftKey < rightKey : lhs.offset < rhs.offset
            case .popularity:
                let left = settings[lhs.element.name]?.storedContentCount ?? 0
                let right = settings[rhs.element.name]?.storedContentCount ?? 0
                if left != right { return left > right }
                let leftKey = settings[lhs.element.name]?.sortKey ?? Int32.max
                let rightKey = settings[rhs.element.name]?.sortKey ?? Int32.max
                return leftKey != rightKey ? leftKey < rightKey : lhs.offset < rhs.offset
            }
        }.map(\.element)
    }

    func videoSiteSettings(_ id: String) async throws -> IosVideoSiteSettings {
        guard let videoSources else { throw CancellationError() }
        return try await videoSources.settings(id: id)
    }

    /// Saves a CMS site's address and User-Agent; the runtime drops its cached site state to pick them up.
    func updateVideoSite(_ id: String, api: String, userAgent: String) async throws {
        guard let videoSources else { throw CancellationError() }
        try await videoSources.updateSettings(id: id, api: api, userAgent: userAgent)
        try await runtime?.reloadVideoSites()
    }

    func recordUsage(sourceName: String) async {
        guard let library, let source = sources.first(where: { $0.name == sourceName }) else { return }
        do {
            if settings[sourceName] == nil {
                // Materialize effective positions before inserting a usage row, preserving the catalog's manual order.
                let manual = ordered(Self.catalogOrder(sources))
                try await library.setSourceOrder(names: manual.map(\.name),
                    enabled: manual.map { KotlinBoolean(value: isEnabled($0)) })
            }
            try await library.recordSourceUsage(name: sourceName, enabled: isEnabled(source))
            try await reloadSettings()
        } catch { self.error = error.localizedDescription }
    }

    func refreshSettings() async {
        do { try await reloadSettings() }
        catch { self.error = error.localizedDescription }
    }

    func setPinned(_ source: IosSource, _ pinned: Bool) async {
        guard let library else { return }
        do {
            try await library.setSourcePinned(name: source.name, pinned: pinned)
            try await reloadSettings()
        } catch { self.error = error.localizedDescription }
    }

    /// Saves [list] as the manual order, keeping every source's current on/off state.
    func saveOrder(_ list: [IosSource]) async {
        guard let library, !list.isEmpty else { return }
        do {
            try await library.setSourceOrder(names: list.map(\.name),
                                             enabled: list.map { KotlinBoolean(value: isEnabled($0)) })
            try await reloadSettings()
        } catch { self.error = error.localizedDescription }
    }

    private func reloadSettings() async throws {
        guard let rows = try await library?.sourceSettings() else { return }
        settings = Dictionary(rows.map { ($0.name, $0) }, uniquingKeysWith: { first, _ in first })
    }

    /// Like Android's first-run selection: an extension or plugin covering several languages starts with only the
    /// user's own (and language-agnostic) sources; single-language extensions and native sites are all enabled.
    static func defaultEnabled(_ source: IosSource) -> Bool {
        guard source.kind == "extension", source.groupLanguages > 1 else { return true }
        return SourceLanguage.isUserLanguage(source.locale)
    }

    func setEnabled(_ list: [IosSource], _ enabled: Bool) async {
        guard let library, !list.isEmpty else { return }
        do {
            try await library.setSourcesEnabled(names: list.map(\.name), enabled: enabled)
            try await reloadSettings()
        } catch { self.error = error.localizedDescription }
    }

    /// Sources of one ecosystem (TVBOX: the native Apple CMS / TVBox sites), filtered by search and adult setting;
    /// disabled sources are left out unless [includeDisabled].
    func sources(_ ecosystem: String, query: String, showAdult: Bool, includeDisabled: Bool = false) -> [IosSource] {
        let text = query.trimmingCharacters(in: .whitespaces)
        let pool = switch ecosystem {
        case "TVBOX": videoSourceList
        case "LOCAL": sources.filter { $0.kind == "local" }
        default: extensionSourceList.filter { $0.ecosystem == ecosystem }
        }
        return pool.filter { source in
            (includeDisabled || isEnabled(source)) && (showAdult || !source.isAdult) &&
                (text.isEmpty || source.title.localizedCaseInsensitiveContains(text) ||
                 source.name.localizedCaseInsensitiveContains(text))
        }
    }

    deinit { ownedRuntime?.close() }

    func attach(_ library: IosLibrary) {
        guard runtime == nil else { return }
        self.library = library
        let store = IosVideoSources(library: library)
        videoSources = store
        // Imported files live next to the reader's picture store, so local pages use the same `<sha256>.img` files.
        let data = try? NativeSourceTransport.dataDirectory
        runtime = IosSources(transport: NativeSourceTransport.isAvailable ? NativeSourceTransport() : nil,
                             videoSources: store,
                             localDirectory: data?.appendingPathComponent("local", isDirectory: true).path,
                             imageDirectory: data?.appendingPathComponent("images", isDirectory: true).path)
        ownedRuntime = runtime
    }

    func load() async {
        guard let runtime, !isLoading else { return }
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            let catalog = try await runtime.catalog()
            var rows = try await library?.sourceSettings() ?? []
            if let library {
                let existing = Dictionary(rows.map { ($0.name, $0.enabled) }, uniquingKeysWith: { first, _ in first })
                let ordered = Self.catalogOrder(catalog.sources)
                let defaults = UserDefaults.standard
                try await library.registerSourceCatalog(names: ordered.map(\.name),
                    enabled: ordered.map { KotlinBoolean(value: existing[$0.name] ?? Self.defaultEnabled($0)) },
                    appVersion: appVersion, baseline: defaults.integer(forKey: "sources.catalogVersion") == 0)
                // A failed or empty startup must not mark a partial catalog as the initial baseline.
                if catalog.extensionError == nil && !catalog.sources.isEmpty {
                    defaults.set(Int(appVersion), forKey: "sources.catalogVersion")
                }
                rows = try await library.sourceSettings()
                try await library.recovery.remember(sources: catalog.sources)
            }
            settings = Dictionary(rows.map { ($0.name, $0) }, uniquingKeysWith: { first, _ in first })
            sources = catalog.sources
            catalogRevision += 1
            library?.updateSources(sources: catalog.sources)
            extensionError = catalog.extensionError
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Returns a short summary for the import sheet, or throws with the reason nothing was imported.
    func importSource(url: String, title: String) async throws -> String {
        guard let videoSources, let runtime else { throw CancellationError() }
        let summary = try await videoSources.importFrom(url: url.trimmingCharacters(in: .whitespacesAndNewlines),
                                                        title: title)
        try await runtime.sourcesChanged()
        await load()
        var text = String(localized: "已导入 \(summary.imported) 个站点")
        if summary.skippedSpiderSites > 0 { text += String(localized: "，跳过 \(summary.skippedSpiderSites) 个需要爬虫运行时的站点") }
        return text
    }

    func delete(_ source: IosSource) async {
        guard let videoSources, let runtime else { return }
        do {
            try await videoSources.delete(id: source.name)
            try await runtime.sourcesChanged()
            sources.removeAll { $0.name == source.name }
            catalogRevision += 1
        } catch {
            self.error = error.localizedDescription
        }
    }
}

enum SourceListLayout: String { case list, grid }

/// How the browse list groups sources: by where they come from, by what they offer, or not at all.
enum SourceGrouping: String, CaseIterable, Identifiable {
    case ecosystem, content, none
    var id: String { rawValue }
    var title: String {
        switch self {
        case .ecosystem: String(localized: "按来源类型")
        case .content: String(localized: "按内容类型")
        case .none: String(localized: "不分组")
        }
    }
}

enum SourceSort: String, CaseIterable, Identifiable {
    case manual, title, lastUsed, popularity
    var id: String { rawValue }
    var title: String {
        switch self {
        case .manual: String(localized: "手动顺序")
        case .title: String(localized: "名称")
        case .lastUsed: String(localized: "最近使用")
        case .popularity: String(localized: "使用频率")
        }
    }
}

struct SourcesView: View {
    @EnvironmentObject private var library: LibraryModel
    @ObservedObject var model: SourcesModel
    @State private var query = ""
    @AppStorage("sources.showAdult") private var showAdult = false
    @AppStorage("sources.layout") private var layout = SourceListLayout.list
    /// Content types hidden by the former display-menu toggles; migrated once into the content space.
    @AppStorage("sources.hiddenKinds") private var hiddenKinds = ""
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true
    @AppStorage("sources.kind") private var pageKind = ""
    @AppStorage("sources.hiddenEcosystems") private var hiddenEcosystems = ""
    @AppStorage("sources.grouping") private var grouping = SourceGrouping.ecosystem
    @AppStorage("sources.sort") private var sort = SourceSort.manual
    @AppStorage("sources.newOnly") private var newOnly = false
    @State private var reordering = false
    @State private var migration: LibraryMigrationRequest?
    @State private var trackingPreview: TrackingPreviewSelection?
    @Environment(\.openGlobalSearch) private var openGlobalSearch

    init(library: LibraryModel? = nil, model: SourcesModel) {
        self.model = model
    }

    private struct DisplaySources {
        let sections: [(id: String, title: String, sources: [IosSource])]
        let newSourceCount: Int
    }

    /// Content types among the enabled sources, independent of the search text so the switch stays put.
    private var availableKinds: Set<String> {
        var kinds = Set<String>()
        for source in model.sources {
            if model.isEnabled(source) && (showAdult || !source.isAdult) {
                kinds.insert(source.mediaKind)
            }
        }
        return kinds
    }

    /// The content space, or this page's own switch while spaces are off.
    private var kind: String? {
        if spacesEnabled { return ContentSpace.kind(space) }
        return ContentKindPicker.effective(pageKind, available: availableKinds)
    }

    private var displaySources: DisplaySources {
        let hidden = SourceFilterOption.decode(hiddenEcosystems)
        let selectedKind = kind
        let text = query.trimmingCharacters(in: .whitespaces)

        let filtered = model.sources.filter { source in
            guard model.isEnabled(source) else { return false }
            if !showAdult && source.isAdult { return false }
            if let selectedKind, source.mediaKind != selectedKind { return false }
            if spacesEnabled && !ContentSpace.admits(source) { return false }
            let eco = SourceFilterOption.ecosystemKey(source)
            if hidden.contains(eco) { return false }
            if !text.isEmpty {
                let matchesTitle = source.title.localizedCaseInsensitiveContains(text)
                let matchesName = source.name.localizedCaseInsensitiveContains(text)
                if !matchesTitle && !matchesName { return false }
            }
            return true
        }

        let newCount = filtered.reduce(0) { model.isNewSource($1) ? $0 + 1 : $0 }
        let visible = model.ordered(filtered.filter { !newOnly || model.isNewSource($0) }, by: sort)
        var pinned: [IosSource] = []
        var rest: [IosSource] = []
        pinned.reserveCapacity(min(visible.count, 8))
        rest.reserveCapacity(visible.count)
        for source in visible {
            if model.isPinned(source) { pinned.append(source) }
            else { rest.append(source) }
        }
        var result: [(id: String, title: String, sources: [IosSource])] = []
        if !pinned.isEmpty { result.append(("pinned", String(localized: "置顶"), pinned)) }
        switch effectiveGrouping {
        case .ecosystem:
            let grouped = Dictionary(grouping: rest, by: { SourceFilterOption.ecosystemKey($0) })
            for ecosystem in SourcesModel.ecosystems {
                if let members = grouped[ecosystem.id], !members.isEmpty {
                    result.append((ecosystem.id, ecosystem.title, members))
                }
            }
        case .content:
            let grouped = Dictionary(grouping: rest, by: \.mediaKind)
            for kind in SourceFilterOption.kinds {
                if let members = grouped[kind.id], !members.isEmpty {
                    result.append((kind.id, kind.title, members))
                }
            }
        case .none:
            if !rest.isEmpty { result.append(("all", pinned.isEmpty ? String(localized: "全部来源") : String(localized: "其他来源"), rest)) }
        }
        return DisplaySources(sections: result, newSourceCount: newCount)
    }

    private var disabledCount: Int { model.sources.reduce(0) { !model.isEnabled($1) ? $0 + 1 : $0 } }
    private var isKindSelected: Bool { kind != nil }

    /// Grouping by content type only means something while every kind is shown; a single kind groups by source
    /// type and the stored choice returns with “全部”.
    private var effectiveGrouping: SourceGrouping {
        isKindSelected && grouping == .content ? .ecosystem : grouping
    }

    private var filtersActive: Bool {
        newOnly || !hiddenEcosystems.isEmpty
    }

    var body: some View {
        let display = displaySources
        NavigationStack {
            Group {
                if layout == .grid { grid(display) } else { list(display) }
            }
            .overlay {
                if model.isLoading && model.sources.isEmpty { ProgressView("正在读取源…") }
            }
            .navigationTitle("浏览")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: SourceRoute.self) { route in
                if let runtime = model.runtime {
                    SourceBrowseView(runtime: runtime, source: route.source)
                }
            }
            // The system search field under the title; the search tab stays the app-wide search.
            .pageSearch(text: $query, prompt: String(localized: "搜索源"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if spacesEnabled { SpaceMenu() }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    NavigationLink { RepositoriesView() } label: {
                        Label("仓库与扩展", systemImage: "shippingbox")
                    }.accessibilityIdentifier("sources.repositories")
                    displayMenu
                }
            }
            .refreshable { await model.load() }
            .onAppear(perform: migrateHiddenKinds)
            .sheet(isPresented: $reordering) { SourceOrderSheet() }
            .sheet(item: $migration) { request in
                NavigationStack { BatchMigrationView(contents: request.contents) }
            }
        }
        // Presentation belongs to the screen, not a List row that can be recreated as source usage changes.
        .sheet(item: $trackingPreview, onDismiss: { Task { await library.refresh() } }) { selection in
            TrackingItemPreview(item: selection.item, tracking: selection.tracking, content: nil, onBound: {})
        }
    }

    private func previewTracking(_ item: IosTrackingItem) {
        guard let tracking = library.library?.tracking else { return }
        trackingPreview = TrackingPreviewSelection(item: item, tracking: tracking)
    }

    /// The former menu hid kinds; one remaining kind becomes the switch's selection, anything else shows all.
    private func migrateHiddenKinds() {
        guard !hiddenKinds.isEmpty else { return }
        let visible = Set(SourceFilterOption.kinds.map(\.id)).subtracting(SourceFilterOption.decode(hiddenKinds))
        if visible.count == 1, let only = visible.first { pageKind = only }
        hiddenKinds = ""
    }

    // MARK: Layouts

    private func list(_ display: DisplaySources) -> some View {
        List {
            if !spacesEnabled && ContentKindPicker.isShown(availableKinds) {
                Section { ContentKindPicker(selection: $pageKind, available: availableKinds) }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
            }
            if query.isEmpty && !newOnly {
                Section {
                    BrowseRecommendationsView(spaceKind: kind, onPreview: previewTracking)
                        .listRowBackground(Color.clear).listRowSeparator(.hidden)
                        // Transparent List rows still clip their contents to the cell's rounded corners.
                        .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
                }
            }
            statusSections
            if display.newSourceCount > 0 { Section { newSourcesNotice(count: display.newSourceCount) } }
            ForEach(display.sections, id: \.id) { section in
                Section("\(section.title) · \(section.sources.count)") {
                    ForEach(section.sources, id: \.name) { source in
                        sourceLink(source)
                            .contextMenu { sourceActions(source) }
                            .swipeActions(edge: .leading) {
                                let pinned = model.isPinned(source)
                                Button(pinned ? "取消置顶" : "置顶", systemImage: pinned ? "pin.slash" : "pin") {
                                    Task { await model.setPinned(source, !pinned) }
                                }
                                .tint(.orange)
                            }
                            .swipeActions {
                                if source.kind == "video" {
                                    Button("删除", systemImage: "trash", role: .destructive) { Task { await model.delete(source) } }
                                } else {
                                    Button("停用", systemImage: "eye.slash") { Task { await model.setEnabled([source], false) } }
                                }
                            }
                    }
                }
            }
            emptyState(hasSections: !display.sections.isEmpty)
            if disabledCount > 0 { Section { disabledFooter } }
        }
    }

    private func grid(_ display: DisplaySources) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if !spacesEnabled && ContentKindPicker.isShown(availableKinds) {
                    ContentKindPicker(selection: $pageKind, available: availableKinds).padding(.horizontal, 16)
                }
                if query.isEmpty && !newOnly {
                    BrowseRecommendationsView(spaceKind: kind, onPreview: previewTracking).padding(.horizontal, 16)
                }
                if let error = model.error {
                    Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red).padding(.horizontal)
                        .accessibilityIdentifier("sources.error")
                }
                if NativeSourceTransport.isAvailable {
                    if let error = model.extensionError {
                        Text(error).foregroundStyle(.red).padding(.horizontal).accessibilityIdentifier("sources.extensionError")
                    }
                    runtimeLabel.padding(.horizontal, 20)
                }
                if display.newSourceCount > 0 { newSourcesNotice(count: display.newSourceCount).padding(.horizontal, 20) }
                ForEach(display.sections, id: \.id) { section in
                    VStack(alignment: .leading, spacing: 8) {
                        Text("\(section.title) · \(section.sources.count)")
                            .font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                            .padding(.horizontal, 20)
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 10)], spacing: 10) {
                            ForEach(section.sources, id: \.name) { source in
                                NavigationLink(value: SourceRoute(source: source)) { SourceTile(source: source) }
                                    .buttonStyle(.plain)
                                    .accessibilityIdentifier("source.\(source.name)")
                                    .contextMenu { sourceActions(source) }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }
                emptyState(hasSections: !display.sections.isEmpty).padding(.top, 40)
                if disabledCount > 0 { disabledFooter.padding(.horizontal, 20) }
            }
            .padding(.vertical, 8)
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }

    @ViewBuilder
    private func sourceActions(_ source: IosSource) -> some View {
        if source.ecosystem != "LOCAL" {
            Button("迁移此来源的收藏与历史", systemImage: "arrow.triangle.swap") {
                migration = LibraryMigrationRequest(contents: (library.favourites + library.history)
                    .map(\.content).filter { $0.sourceName == source.name })
            }
        }
        let pinned = model.isPinned(source)
        Button(pinned ? "取消置顶" : "置顶", systemImage: pinned ? "pin.slash" : "pin") {
            Task { await model.setPinned(source, !pinned) }
        }
        if source.kind == "video" {
            Button("删除站点", systemImage: "trash", role: .destructive) { Task { await model.delete(source) } }
        } else {
            Button("停用此来源", systemImage: "eye.slash") { Task { await model.setEnabled([source], false) } }
        }
    }

    // MARK: Parts

    @ViewBuilder
    private var statusSections: some View {
        if let error = model.error {
            Section {
                Label(error, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("sources.error")
                Button("重试") { Task { await model.load() } }.disabled(model.isLoading)
            }
        }
        if NativeSourceTransport.isAvailable {
            Section {
                if let error = model.extensionError {
                    Text(error).foregroundStyle(.red).accessibilityIdentifier("sources.extensionError")
                }
            } footer: {
                runtimeLabel
            }
        }
    }

    /// Shown only with the embedded extension runtime (preview builds name their profile here).
    private var runtimeLabel: some View {
        Text(model.previewLabel ?? String(localized: "扩展运行时已启用"))
            .font(.footnote).foregroundStyle(.secondary)
            .accessibilityIdentifier("sources.extensionRuntime")
    }

    private func newSourcesNotice(count: Int) -> some View {
        Button {
            newOnly.toggle()
        } label: {
            Label(newOnly ? "正在显示本版本新增的 \(count) 个来源 · 显示全部"
                          : "本版本新增 \(count) 个来源 · 查看", systemImage: "sparkles")
                .font(.footnote)
        }
        .accessibilityIdentifier("sources.newNotice")
    }

    @ViewBuilder
    private func emptyState(hasSections: Bool) -> some View {
        if model.sources.isEmpty && !model.isLoading && model.error == nil {
            ContentUnavailableView {
                Label("还没有来源", systemImage: "safari")
            } description: {
                Text("在“仓库与扩展”中添加扩展仓库，或苹果 CMS / TVBox 影视站点。")
            } actions: {
                NavigationLink { RepositoriesView(addKind: NativeSourceTransport.isAvailable ? nil : "TVBOX") } label: {
                    Text("添加来源")
                }
                .buttonStyle(.glassProminent)
                .accessibilityIdentifier("sources.addVideo.inline")
            }
            .listRowBackground(Color.clear)
        } else if !model.sources.isEmpty && !hasSections {
            if newOnly || query.trimmingCharacters(in: .whitespaces).isEmpty {
                ContentUnavailableView {
                    Label("没有符合条件的来源", systemImage: "line.3.horizontal.decrease")
                } description: {
                    Text(newOnly ? "本版本没有符合当前条件的新来源。" : "切换内容空间，或调整显示菜单中的来源类型。")
                } actions: {
                    if filtersActive || isKindSelected {
                        Button("显示全部") { space = ""; pageKind = ""; hiddenEcosystems = ""; newOnly = false }
                            .buttonStyle(.glass).accessibilityIdentifier("sources.showAll")
                    }
                }
                .listRowBackground(Color.clear)
            } else {
                ContentUnavailableView.search(text: query).listRowBackground(Color.clear)
            }
        }
    }

    private var disabledFooter: some View {
        NavigationLink { RepositoriesView(startTab: 2) } label: {
            Label("\(disabledCount) 个来源未启用 · 管理来源", systemImage: "slider.horizontal.3")
                .font(.footnote)
        }
        .accessibilityIdentifier("sources.manage")
    }

    /// Layout and filters in one menu that stays open while toggling.
    private var displayMenu: some View {
        Menu {
            Picker("布局", selection: $layout) {
                Label("列表", systemImage: "list.bullet").tag(SourceListLayout.list)
                Label("网格", systemImage: "square.grid.2x2").tag(SourceListLayout.grid)
            }
            .pickerStyle(.inline)
            Picker("分组", systemImage: "rectangle.3.group",
                   selection: Binding(get: { effectiveGrouping }, set: { grouping = $0 })) {
                ForEach(SourceGrouping.allCases.filter { !isKindSelected || $0 != .content }) { Text($0.title).tag($0) }
            }
            .pickerStyle(.menu)
            Picker("排序", systemImage: "arrow.up.arrow.down", selection: $sort) {
                ForEach(SourceSort.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.menu)
            Button("调整顺序…", systemImage: "line.3.horizontal") { sort = .manual; reordering = true }
                .accessibilityIdentifier("sources.reorder")
            Section("来源类型") {
                ForEach(SourcesModel.ecosystems.filter { eco in model.sources.contains { SourceFilterOption.ecosystemKey($0) == eco.id } },
                        id: \.id) { ecosystem in
                    Toggle(isOn: hiddenBinding(ecosystem.id, in: $hiddenEcosystems)) {
                        Label(ecosystem.title, systemImage: ecosystem.icon)
                    }
                }
            }
            Toggle("显示成人内容源", isOn: $showAdult)
            Toggle("仅显示本版本新来源", isOn: $newOnly).accessibilityIdentifier("sources.newOnly")
            if filtersActive {
                Button("显示全部类型", systemImage: "arrow.counterclockwise") {
                    hiddenEcosystems = ""; newOnly = false
                }
            }
        } label: {
            Label("显示", systemImage: filtersActive ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease")
        }
        .menuActionDismissBehavior(.disabled)
        .accessibilityIdentifier("sources.filter")
    }

    /// A toggle that is on while the key is not in the stored hidden set.
    private func hiddenBinding(_ key: String, in storage: Binding<String>) -> Binding<Bool> {
        Binding(get: { !SourceFilterOption.decode(storage.wrappedValue).contains(key) },
                set: { visible in
                    var hidden = SourceFilterOption.decode(storage.wrappedValue)
                    if visible { hidden.remove(key) } else { hidden.insert(key) }
                    storage.wrappedValue = SourceFilterOption.encode(hidden)
                })
    }

    private func sourceLink(_ source: IosSource) -> some View {
        NavigationLink(value: SourceRoute(source: source)) {
            HStack(spacing: 12) {
                SourceIcon(runtime: model.runtime, source: source)
                VStack(alignment: .leading, spacing: 2) {
                    Text(source.title)
                    if model.isNewSource(source) { SourceNewBadge() }
                    Text(source.kind == "video" ? String(localized: "影视站点")
                         : source.kind == "local" ? String(localized: "导入的\(Self.kindName(source.mediaKind))文件")
                         : [SourceLanguage.title(source.locale), Self.kindName(source.mediaKind)].joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                }
                if model.isPinned(source) {
                    Spacer()
                    Image(systemName: "pin.fill").font(.caption).foregroundStyle(.orange).accessibilityLabel("已置顶")
                }
            }
        }
        .accessibilityIdentifier("source.\(source.name)")
    }
}

extension SourcesView {
    static func kindName(_ mediaKind: String) -> String {
        switch mediaKind {
        case "VIDEO": String(localized: "视频")
        case "NOVEL": String(localized: "小说")
        default: String(localized: "漫画")
        }
    }
}

struct SourceRoute: Hashable {
    let source: IosSource
    static func == (lhs: SourceRoute, rhs: SourceRoute) -> Bool { lhs.source.name == rhs.source.name }
    func hash(into hasher: inout Hasher) { hasher.combine(source.name) }
}
