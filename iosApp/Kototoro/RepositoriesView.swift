import KototoroShared
import SwiftUI
import UniformTypeIdentifiers
import CryptoKit
import ImageIO

struct RepositoriesView: View {
    @EnvironmentObject private var model: RepositoriesModel
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @State private var tab = 0
    @State private var query = ""
    @State private var searching = false
    @State private var addingRepository = false
    @State private var importingFile = false
    @State private var selection: ExtensionSelection?
    @State private var removingRepository: IosRepository?
    @State private var bannerDismissed = false
    @AppStorage("extensions.filter.languages") private var storedLanguages = Data()
    @AppStorage("extensions.filter.ecosystem") private var ecosystem = ""
    @AppStorage("extensions.filter.scope") private var scope = ExtensionScope.all
    @AppStorage("sources.showAdult") private var showAdult = false
    @State private var removingSite: IosSource?
    let incoming: String?
    /// Opens the add sheet with this repository kind preselected (the browse tab's "add site" entry uses TVBOX).
    let addKind: String?

    @State private var sourceScope = 0
    init(incoming: String? = nil, addKind: String? = nil, startTab: Int = 0) {
        self.incoming = incoming; self.addKind = addKind
        _tab = State(initialValue: startTab)
    }

    private var filter: ExtensionFilter {
        ExtensionFilter(query: query, ecosystem: ecosystem, languages: selectedLanguages,
                        scope: scope, showAdult: showAdult)
    }
    private var visibleRows: [ExtensionSummary] { model.rows.filter(filter.matches) }

    var body: some View {
        List {
            if !library.missingSources.isEmpty {
                Section {
                    NavigationLink { MissingSourcesView() } label: {
                        Label("补齐缺失来源（\(library.missingSources.count)）", systemImage: "shippingbox.and.arrow.backward")
                    }.accessibilityIdentifier("repository.recovery")
                }
            }
            if NativeSourceTransport.isFixtureRuntime {
                Section {
                    Label(NativeSourceTransport.fixtureInstallationMessage, systemImage: "info.circle")
                        .font(.footnote).foregroundStyle(.secondary)
                        .accessibilityIdentifier("repository.fixtureRuntime")
                }
            }
            if model.error != nil || model.status != nil || model.busy {
                Section { RepositoryStatusView(model: model) }
            }
            if tab == 0 { extensionSections }
            else if tab == 2 { sourceSections }
            else { repositorySections }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("扩展管理")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, isPresented: $searching, placement: .navigationBarDrawer(displayMode: .always),
                    prompt: tab == 0 ? "搜索扩展、来源或语言" : tab == 2 ? "搜索来源" : "搜索仓库")
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 12) {
                Picker("管理内容", selection: $tab) {
                    Text("扩展").tag(0)
                    Text("来源").tag(2)
                    Text("仓库").tag(1)
                }.pickerStyle(.segmented).accessibilityIdentifier("repository.tabs")
                if tab != 1 {
                    if tab == 0 {
                        Picker("扩展状态", selection: $scope) {
                            ForEach(ExtensionScope.allCases) { scope in Text(scope.title).tag(scope) }
                        }.pickerStyle(.segmented).accessibilityIdentifier("extension.scope")
                    } else {
                        Picker("来源状态", selection: $sourceScope) {
                            Text("全部").tag(0); Text("已启用").tag(1); Text("未启用").tag(2)
                        }.pickerStyle(.segmented).accessibilityIdentifier("source.scope")
                    }
                    HStack {
                        Menu {
                            Button("全部语言") { setLanguages([]) }
                                .accessibilityIdentifier("extension.language.all")
                            Section("可多选，匹配任一种语言") {
                                ForEach(languages, id: \.self) { code in
                                    Toggle(ExtensionLanguage.title(code), isOn: languageBinding(code))
                                        .accessibilityIdentifier("extension.language.option.\(code)")
                                }
                            }
                        } label: {
                            Label(languageSelectionTitle, systemImage: "globe")
                        }.menuActionDismissBehavior(.disabled)
                            .buttonStyle(.glass).controlSize(.small)
                            .accessibilityLabel(String(localized: "筛选语言，当前") + (selectedLanguageTitles.isEmpty
                                ? String(localized: "全部语言") : selectedLanguageTitles.joined(separator: "、")))
                            .accessibilityIdentifier("extension.language")
                        if !selectedLanguages.isEmpty {
                            Button("重置") { setLanguages([]) }
                                .font(.subheadline).accessibilityIdentifier("extension.language.reset")
                        }
                        // The ecosystem choice lives in the toolbar menu and persists; show it here so a list
                        // narrowed to one ecosystem never looks like missing extensions.
                        if let selected = ExtensionEcosystem(rawValue: ecosystem) {
                            Button { ecosystem = "" } label: {
                                Label(selected.title, systemImage: "xmark.circle.fill")
                            }
                            .buttonStyle(.glass).controlSize(.small)
                            .accessibilityLabel("仅显示\(selected.title)，点按清除")
                            .accessibilityIdentifier("extension.ecosystem.clear")
                        }
                        Spacer()
                    }
                }
            }.padding(.horizontal, 20).padding(.vertical, 12).background(.bar)
        }
        .toolbar {
            if tab != 1 {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu("筛选", systemImage: hasFilters ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease") {
                        Picker("生态", selection: $ecosystem) {
                            Text("全部生态").tag("")
                            ForEach(ExtensionEcosystem.allCases) { Text($0.title).tag($0.rawValue) }
                        }
                        Toggle("显示成人内容", isOn: $showAdult)
                        if hasFilters {
                            Button("清除筛选") { resetFilter() }
                                .accessibilityIdentifier("extension.filters.reset")
                        }
                    }.accessibilityIdentifier("extension.filters")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu("添加", systemImage: "plus") {
                    Button("添加仓库", systemImage: "link.badge.plus") { addingRepository = true }
                        .accessibilityIdentifier("repository.add")
                    Button("从文件安装", systemImage: "doc.badge.plus") { importingFile = true }
                        .disabled(model.busy || NativeSourceTransport.isFixtureRuntime)
                        .accessibilityIdentifier("repository.importFile")
                    Link(destination: URL(string: "https://skepsun.github.io/kototoro-repo-hub/")!) {
                        Label("查找仓库", systemImage: "safari")
                    }
                }.disabled(library.library == nil || model.busy)
                    .accessibilityIdentifier("repository.actions")
            }
        }
        .sheet(isPresented: $addingRepository) {
            AddRepositoryView(model: model, initialAddress: incoming ?? "", initialKind: addKind ?? "MIHON") { kind in
                tab = kind == "TVBOX" ? 1 : 0; query = ""; searching = false
            }
        }
        .sheet(item: $selection) { selected in
            ExtensionDetailsView(model: model, extensionID: selected.id)
        }
        .confirmationDialog("移除仓库？", isPresented: Binding(
            get: { removingRepository != nil }, set: { if !$0 { removingRepository = nil } }
        ), titleVisibility: .visible, presenting: removingRepository) { repository in
            Button("移除仓库", role: .destructive) { Task { await model.remove(repository) } }
        } message: { repository in
            Text("移除“\(repository.title)”的仓库记录，已安装的扩展会保留。")
        }
        .fileImporter(isPresented: $importingFile,
                      allowedContentTypes: [UTType(filenameExtension: "apk") ?? .data,
                                            UTType(filenameExtension: "jar") ?? .archive, .archive, .data]) { result in
            switch result {
            case .success(let url): Task { await model.installFile(url) }
            case .failure(let error): model.reportError(error.localizedDescription)
            }
        }
        .task(id: "\(library.library == nil)-\(incoming ?? "")") {
            guard library.library != nil else { return }
            model.attach(library: library.library, sources: sources)
            if let incoming { await model.importLink(incoming, kind: "MIHON") }
            await model.load()
        }
        .refreshable { await model.load() }
        .onChange(of: tab) { _, _ in query = ""; searching = false }
        .onAppear { if addKind != nil && !addingRepository && model.status == nil { addingRepository = true } }
        .confirmationDialog("删除站点？", isPresented: Binding(get: { removingSite != nil }, set: { if !$0 { removingSite = nil } }),
                            titleVisibility: .visible, presenting: removingSite) { site in
            Button("删除“\(site.title)”", role: .destructive) { Task { await sources.delete(site) } }
        } message: { _ in
            Text("站点的收藏与阅读记录会保留，重新导入后可继续使用。")
        }
    }

    private var hasFilters: Bool { !ecosystem.isEmpty || !selectedLanguages.isEmpty || scope != .all }
    private var selectedLanguages: Set<String> {
        (try? JSONDecoder().decode(Set<String>.self, from: storedLanguages)) ?? []
    }
    private func setLanguages(_ codes: Set<String>) {
        if let data = try? JSONEncoder().encode(codes.sorted()) { storedLanguages = data }
    }
    private var selectedLanguageTitles: [String] {
        selectedLanguages.sorted().map(ExtensionLanguage.title)
    }
    private var languageSelectionTitle: String {
        if selectedLanguages.isEmpty { return String(localized: "全部语言") }
        if selectedLanguages.count > 2 { return String(localized: "已选 \(selectedLanguages.count) 种语言") }
        return selectedLanguageTitles.joined(separator: "、")
    }
    private func languageBinding(_ code: String) -> Binding<Bool> {
        Binding(get: { selectedLanguages.contains(code) }, set: { selected in
            var codes = selectedLanguages
            if selected { codes.insert(code) }
            else { codes.remove(code) }
            setLanguages(codes)
        })
    }
    private var languages: [String] {
        let codes = Set(model.availableLanguages).union(selectedLanguages)
        let preferred = ["zh", "en", "ja", "ko", "all"]
        return preferred.filter(codes.contains) + codes.subtracting(preferred).sorted {
            ExtensionLanguage.title($0).localizedStandardCompare(ExtensionLanguage.title($1)) == .orderedAscending
        }
    }
    private func resetFilter() {
        query = ""
        ecosystem = ""
        scope = .all
        setLanguages([])
    }

    private struct PartitionedRows {
        var updates: [ExtensionSummary] = []
        var installed: [ExtensionSummary] = []
        var availableGroups: [String: [ExtensionSummary]] = [:]
        var availableLanguages: [String] = []

        init(_ rows: [ExtensionSummary]) {
            for row in rows {
                if row.state == .update {
                    updates.append(row)
                } else if row.installed != nil {
                    installed.append(row)
                } else {
                    let lang = row.isMultilingual ? "all" : (row.languages.first ?? "")
                    availableGroups[lang, default: []].append(row)
                }
            }
            availableLanguages = availableGroups.keys.sorted { ExtensionLanguage.title($0) < ExtensionLanguage.title($1) }
        }
    }

    @ViewBuilder private var extensionSections: some View {
        let rows = visibleRows
        let partitioned = PartitionedRows(rows)
        let updates = partitioned.updates
        let installed = partitioned.installed
        let availableGroups = partitioned.availableGroups
        let availableLanguages = partitioned.availableLanguages
        if model.isRefreshing {
            Section { ProgressView("正在检查仓库…").accessibilityIdentifier("repository.refreshing") }
        }
        if !model.repositoryErrors.isEmpty {
            Section {
                Label("\(model.repositoryErrors.count) 个仓库暂时无法刷新", systemImage: "exclamationmark.arrow.triangle.2.circlepath")
                    .font(.subheadline).foregroundStyle(.orange)
                Button("查看仓库") { tab = 1 }
            }
        }
        if rows.isEmpty && !model.isRefreshing {
            Section {
                ContentUnavailableView {
                    Label(model.rows.isEmpty ? "发现更多内容" : "没有符合条件的扩展", systemImage: "shippingbox")
                } description: {
                    Text(model.rows.isEmpty ? "添加扩展仓库，获取漫画、小说和视频来源。" : "试试其他关键词，或调整语言与生态筛选。")
                } actions: {
                    if model.rows.isEmpty {
                        Button("添加仓库") { addingRepository = true }.buttonStyle(.glassProminent)
                    } else { Button("清除筛选") { resetFilter() }.buttonStyle(.glass) }
                }.listRowBackground(Color.clear)
            }
        }
        if !updates.isEmpty && !bannerDismissed && model.batchProgress == nil {
            Section {
                HStack(spacing: 12) {
                    Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.orange)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("发现 \(updates.count) 个扩展可更新")
                            .font(.subheadline.weight(.semibold))
                        Text("建议更新到最新版本，以获得最佳解析与稳定体验")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("全部更新") {
                        Task { await model.updateAll() }
                    }
                    .buttonStyle(.glassProminent)
                    .tint(.orange)
                    .controlSize(.small)
                    .disabled(model.busy || NativeSourceTransport.isFixtureRuntime)

                    Button {
                        withAnimation { bannerDismissed = true }
                    } label: {
                        Image(systemName: "xmark")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.secondary)
                            .padding(6)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("忽略提示")
                }
                .padding(.vertical, 4)
            }
        }
        if let progress = model.batchProgress {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        ProgressView()
                            .controlSize(.small)
                        Text("正在批量更新 (\(progress.completed + 1)/\(progress.total))：\(progress.currentTitle)")
                            .font(.subheadline.weight(.medium))
                            .lineLimit(1)
                        Spacer()
                        Button("取消") { model.cancelBatchUpdate() }
                            .font(.caption)
                            .buttonStyle(.glass)
                            .controlSize(.small)
                    }
                    ProgressView(value: Double(progress.completed), total: Double(progress.total))
                        .tint(.orange)
                }
                .padding(.vertical, 4)
            }
        }
        if !updates.isEmpty {
            Section {
                ForEach(updates) { extensionRow($0) }
            } header: {
                HStack {
                    Label("可更新 · \(updates.count)", systemImage: "arrow.triangle.2.circlepath")
                        .foregroundStyle(.orange)
                    Spacer()
                    if model.batchProgress == nil {
                        Button {
                            Task { await model.updateAll() }
                        } label: {
                            Label("全部更新", systemImage: "arrow.triangle.2.circlepath.circle.fill")
                                .font(.caption.bold())
                        }
                        .buttonStyle(.glassProminent)
                        .tint(.orange)
                        .controlSize(.small)
                        .disabled(model.busy || NativeSourceTransport.isFixtureRuntime)
                        .accessibilityIdentifier("extension.updateAll")
                    }
                }
            }
        }
        if !installed.isEmpty {
            Section("已安装 · \(installed.count)") { ForEach(installed) { extensionRow($0) } }
        }
        ForEach(availableLanguages, id: \.self) { language in
            let group = availableGroups[language] ?? []
            Section("\(ExtensionLanguage.title(language)) · \(group.count) 个可安装") {
                ForEach(group) { extensionRow($0) }
            }
        }
    }

    /// Every source with its switch, grouped by the extension or plugin providing it (native sites together),
    /// filtered by the same search, language, ecosystem and adult settings as the extension list.
    @ViewBuilder private var sourceSections: some View {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        // Installed rows carry the readable title (repository name for plugins, whose host reply has none).
        let titles = Dictionary(model.rows.filter { $0.installed != nil }.map { ($0.packageName, $0.title) },
                                uniquingKeysWith: { first, _ in first })
        let visible = sources.sources.filter { source in
            let key = SourceFilterOption.ecosystemKey(source)
            let languageMatches = selectedLanguages.isEmpty || source.kind == "video" ||
                selectedLanguages.contains { SourceLanguage.code(of: $0) == SourceLanguage.code(of: source.locale) } ||
                SourceLanguage.title(source.locale) == String(localized: "多语言")
            let state = sources.isEnabled(source)
            return (ecosystem.isEmpty || key == ecosystem || (ecosystem == "JAR" && key == "JAR")) &&
                (showAdult || !source.isAdult) && languageMatches &&
                (sourceScope == 0 || (sourceScope == 1) == state) &&
                (text.isEmpty || source.title.localizedCaseInsensitiveContains(text) ||
                 source.name.localizedCaseInsensitiveContains(text) ||
                 SourceLanguage.title(source.locale).localizedCaseInsensitiveContains(text))
        }
        let groups = Dictionary(grouping: visible) { source in
            source.kind == "video" ? "TVBOX" : source.group.isEmpty ? source.ecosystem : source.group
        }
        let keys = groups.keys.sorted { lhs, rhs in
            let left = lhs == "TVBOX" ? String(localized: "苹果 CMS / TVBox 站点") : titles[lhs] ?? lhs
            let right = rhs == "TVBOX" ? String(localized: "苹果 CMS / TVBox 站点") : titles[rhs] ?? rhs
            return left.localizedStandardCompare(right) == .orderedAscending
        }
        if sources.sources.isEmpty {
            ContentUnavailableView("还没有来源", systemImage: "list.bullet.rectangle",
                                   description: Text("安装扩展或添加影视站点后，可在这里逐个开启或关闭来源。"))
                .listRowBackground(Color.clear)
        } else if keys.isEmpty {
            ContentUnavailableView.search(text: text).listRowBackground(Color.clear)
        }
        ForEach(keys, id: \.self) { key in
            let members = (groups[key] ?? []).sorted {
                ($0.locale, $0.title.lowercased()) < ($1.locale, $1.title.lowercased())
            }
            Section {
                ForEach(members, id: \.name) { SourceToggleRow(source: $0) }
            } header: {
                SourceGroupHeader(title: key == "TVBOX" ? String(localized: "苹果 CMS / TVBox 站点") : titles[key] ?? key, members: members)
            }
        }
    }

    private func extensionRow(_ row: ExtensionSummary) -> some View {
        ExtensionRow(row: row, model: model) { selection = ExtensionSelection(id: row.id) }
    }

    @ViewBuilder private var repositorySections: some View {
        let query = filter.query.trimmingCharacters(in: .whitespacesAndNewlines)
        let repositories = model.saved.filter {
            query.isEmpty || [$0.title, $0.url, $0.kind].contains { $0.localizedCaseInsensitiveContains(query) }
        }
        let sites = sources.videoSourceList.filter {
            query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) || $0.name.localizedCaseInsensitiveContains(query)
        }
        if model.saved.isEmpty && sources.videoSourceList.isEmpty {
            ContentUnavailableView {
                Label("尚未添加仓库", systemImage: "shippingbox")
            } description: { Text("添加仓库地址，或使用 Repo Hub 中的添加链接。")
            } actions: { Button("添加仓库") { addingRepository = true }.buttonStyle(.glassProminent) }
                .listRowBackground(Color.clear)
        } else if repositories.isEmpty && sites.isEmpty {
            ContentUnavailableView.search(text: query).listRowBackground(Color.clear)
        }
        if !repositories.isEmpty { Section("扩展仓库 · \(repositories.count)") {
            ForEach(repositories, id: \.identity) { repository in
                NavigationLink {
                    RepositoryDetailsView(model: model, repository: repository)
                } label: {
                    HStack(spacing: 12) {
                        ExtensionIcon(ecosystem: repository.kind, title: repository.title)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(repository.title).font(.headline).foregroundStyle(.primary)
                            Text([ExtensionEcosystem(rawValue: repository.kind)?.title ?? repository.kind,
                                  URL(string: repository.url)?.host ?? repository.url].joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            if model.repositoryErrors[RepositoriesModel.key(repository)] != nil {
                                Label("刷新失败", systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(.orange)
                            } else if let count = model.catalogs[RepositoriesModel.key(repository)]?.extensions.count {
                                Text("\(count) 个扩展").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }.padding(.vertical, 4)
                }.accessibilityIdentifier("repository.row.\(repository.kind).\(repository.url)")
                    .swipeActions {
                        Button("移除", systemImage: "trash", role: .destructive) { removingRepository = repository }
                            .disabled(model.busy || model.isRefreshing)
                    }
                    .contextMenu {
                        Button("刷新", systemImage: "arrow.clockwise") { Task { await model.open(repository) } }
                            .disabled(model.busy || model.isRefreshing)
                    }
            }
        } }
        if !sites.isEmpty {
            Section {
                ForEach(sites, id: \.name) { site in
                    HStack(spacing: 12) {
                        ExtensionIcon(ecosystem: "TVBOX", title: site.title)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(site.title).font(.headline)
                            Text("苹果 CMS / TVBox · 原生运行").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                    .accessibilityIdentifier("site.\(site.name)")
                    .swipeActions {
                        Button("删除", systemImage: "trash", role: .destructive) { removingSite = site }
                    }
                    .contextMenu {
                        Button("删除站点", systemImage: "trash", role: .destructive) { removingSite = site }
                    }
                }
            } header: {
                Text("影视站点 · \(sites.count)")
            } footer: {
                Text("苹果 CMS（JSON/XML）接口与 TVBox 配置中的 type 0/1 站点，无需扩展运行时。")
            }
        }
    }
}

private struct ExtensionSelection: Identifiable {
    let id: String
    var repositoryID: String? = nil
}

private extension IosRepository {
    var identity: String { kind + ":" + url }
}

private struct ExtensionIcon: View {
    let ecosystem: String
    var title: String = ""
    var url: URL? = nil
    var size: CGFloat = 44
    @State private var image: UIImage?
    private var color: Color {
        switch ExtensionEcosystem(rawValue: ecosystem) {
        case .mihon: .blue
        case .aniyomi: .purple
        case .tsundoku: .orange
        case .jar, nil: .teal
        }
    }
    var body: some View {
        ZStack {
            if let image { Image(uiImage: image).resizable().scaledToFit().padding(4) }
            else if let initial = title.first {
                Text(String(initial).uppercased()).font(.system(size: size * 0.43, weight: .semibold))
                    .foregroundStyle(color)
            } else {
                Image(systemName: ExtensionEcosystem(rawValue: ecosystem)?.symbol ?? "shippingbox")
                    .font(.system(size: size * 0.43, weight: .medium)).foregroundStyle(color)
            }
        }.frame(width: size, height: size).background(color.opacity(0.1), in: .rect(cornerRadius: size * 0.25))
            .accessibilityHidden(true)
            .task(id: url) {
                image = nil
                guard let url else { return }
                let loaded = await ExtensionIconLoader.load(url)
                if !Task.isCancelled { image = loaded }
            }
    }
}

private enum ExtensionIconLoader {
    static func load(_ url: URL) async -> UIImage? {
        try? await withReaderStorage { await loadOwned(url) }
    }

    private static func loadOwned(_ url: URL) async -> UIImage? {
        let key = SHA256.hash(data: Data(url.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
        let directory = URL.cachesDirectory.appendingPathComponent("extension-icons", isDirectory: true)
        let file = directory.appendingPathComponent(key)
        if let data = try? Data(contentsOf: file), let image = thumbnail(data) { return image }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            try Task.checkCancellation()
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  data.count <= 4 * 1024 * 1024, let image = thumbnail(data) else { return nil }
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try? data.write(to: file, options: .atomic)
            return image
        } catch { return nil }
    }
    private static func thumbnail(_ data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 192,
                kCGImageSourceCreateThumbnailWithTransform: true,
              ] as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }
}

private struct ExtensionRow: View {
    let row: ExtensionSummary
    @ObservedObject var model: RepositoriesModel
    let details: () -> Void
    var body: some View {
        HStack(spacing: 12) {
            Button(action: details) {
                HStack(spacing: 12) {
                    ExtensionIcon(ecosystem: row.ecosystem, title: row.title, url: row.iconURL)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text(row.title).font(.headline).foregroundStyle(.primary)
                            if row.isAdult { Text("18+").font(.caption2.bold()).foregroundStyle(.orange) }
                        }
                        Text([row.ecosystemTitle, row.installedVersion ?? row.version,
                              row.languageDescription].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        if row.state == .update, let available = row.available {
                            Text("新版本 \(available.version)").font(.caption).foregroundStyle(.orange)
                        } else if row.sourceNames.count > 1 {
                            Text("包含 \(row.sourceNames.count) 个来源").font(.caption).foregroundStyle(.secondary)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.contentShape(.rect)
            }.buttonStyle(.plain).accessibilityIdentifier("extension.details.\(row.id)")
            if row.state == .installing {
                ProgressView().accessibilityLabel("正在安装 \(row.title)")
            } else if model.batchProgress != nil && row.state == .update && model.activeID != row.id {
                Text("等待中")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 44, minHeight: 44)
            } else if row.state == .installed {
                Menu {
                    Button("查看详情", systemImage: "info.circle", action: details)
                    if row.permitsInstallation {
                        Button("重新安装", systemImage: "arrow.clockwise") { Task { await model.install(row) } }
                            .disabled(model.busy || NativeSourceTransport.isFixtureRuntime)
                    }
                } label: { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).frame(minWidth: 44, minHeight: 44) }
                    .accessibilityLabel("\(row.title)，已安装")
                    .accessibilityIdentifier("extension.install.\(row.packageName)")
            } else {
                Button(row.stateTitle) { Task { await model.install(row) } }
                    .buttonStyle(.glass).controlSize(.small).frame(minHeight: 44)
                    .disabled(model.busy || NativeSourceTransport.isFixtureRuntime)
                    .accessibilityIdentifier("extension.install.\(row.packageName)")
            }
        }.padding(.vertical, 4).accessibilityElement(children: .contain)
            .accessibilityIdentifier("extension.row.\(row.id)")
    }
}

struct RepositoryStatusView: View {
    @ObservedObject var model: RepositoriesModel

    /// Host install failures arrive as "…SourceJarException: Source JAR failure: NAME"; explain the known ones.
    static func readable(_ message: String) -> String {
        guard let range = message.range(of: "Source JAR failure: ") else { return message }
        let code = message[range.upperBound...].prefix { $0.isLetter || $0 == "_" }
        let reason: String? = switch code {
        case "UNSUPPORTED_LIBRARY": String(localized: "扩展的库版本不受支持（Mihon/Tsundoku 支持 1.4–1.6，Aniyomi 支持 12–16）")
        case "UNSUPPORTED_BYTECODE": String(localized: "扩展的字节码版本过新，当前运行时无法加载")
        case "INVALID_ARCHIVE": String(localized: "安装包已损坏或不是有效的扩展文件")
        case "INVALID_MANIFEST": String(localized: "扩展清单无法识别")
        case "AMBIGUOUS_ECOSYSTEM": String(localized: "扩展同时声明了多种类型，无法确定由哪个运行时加载")
        case "HASH_MISMATCH": String(localized: "下载的文件校验失败，请重试")
        case "PACKAGE_MISMATCH", "VERSION_MISMATCH": String(localized: "安装包与仓库记录的包名或版本不一致")
        case "API_UNAVAILABLE": String(localized: "扩展依赖的接口在 iOS 兼容层中不可用")
        case "CONSTRUCTION_FAILED": String(localized: "扩展初始化失败")
        case "NO_SOURCES": String(localized: "扩展没有提供任何来源")
        case "DUPLICATE_SOURCE", "ALREADY_LOADED": String(localized: "已存在相同 ID 的来源")
        default: nil
        }
        return reason.map { "\($0)（\(code)）" } ?? message
    }
    var body: some View {
        if let error = model.error {
            let message = Self.readable(error)
            Label(message, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                .accessibilityIdentifier("repository.error")
            if message != error {
                DisclosureGroup("错误详情") {
                    Text(error).font(.caption).textSelection(.enabled)
                        .accessibilityIdentifier("repository.error.details")
                }
            }
        } else if model.busy {
            ProgressView(model.status ?? String(localized: "正在处理…")).accessibilityIdentifier("repository.progress")
        } else if let status = model.status {
            Label(status, systemImage: "checkmark.circle").font(.subheadline).foregroundStyle(.secondary)
                .accessibilityIdentifier("repository.status")
        }
    }
}

private struct AddRepositoryView: View {
    @ObservedObject var model: RepositoriesModel
    @Environment(\.dismiss) private var dismiss
    @State private var address: String
    @State private var kind: String
    @State private var title = ""
    let onAdded: (String) -> Void
    init(model: RepositoriesModel, initialAddress: String, initialKind: String, onAdded: @escaping (String) -> Void) {
        self.model = model; _address = State(initialValue: initialAddress); _kind = State(initialValue: initialKind)
        self.onAdded = onAdded
    }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("仓库地址或添加链接", text: $address)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("repository.address")
                    Picker("仓库类型", selection: $kind) {
                        ForEach(ExtensionEcosystem.allCases) { Text($0.title).tag($0.rawValue) }
                        Text("苹果 CMS / TVBox").tag("TVBOX")
                    }
                    .accessibilityIdentifier("repository.kind")
                    if kind == "TVBOX" {
                        TextField("站点名称（可选）", text: $title)
                            .accessibilityIdentifier("repository.title")
                    }
                } header: { Text("仓库信息") } footer: {
                    Text(kind == "TVBOX"
                         ? "支持苹果 CMS 接口（如 …/api.php/provide/vod/）、TVBox 配置或多仓地址；站点名称只用于单个苹果 CMS 接口。"
                         : "添加链接会自动识别类型；直接输入地址时，请选择对应的仓库类型。")
                }
                if model.error != nil || model.busy { Section { RepositoryStatusView(model: model) } }
                Section {
                    Link("在 Repo Hub 查找仓库", destination: URL(string: "https://skepsun.github.io/kototoro-repo-hub/")!)
                }
            }.navigationTitle("添加仓库").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(model.busy) }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("添加") {
                            Task {
                                await model.importLink(address, kind: kind, title: title)
                                if model.error == nil && model.status != nil { onAdded(kind); dismiss() }
                            }
                        }.disabled(model.busy || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityIdentifier("repository.import")
                    }
                }.interactiveDismissDisabled(model.busy)
                .onAppear { model.clearMessage() }
        }.presentationDetents([.medium, .large])
    }
}

private struct RepositoryDetailsView: View {
    @ObservedObject var model: RepositoriesModel
    let repository: IosRepository
    @State private var query = ""
    @State private var selection: ExtensionSelection?
    @AppStorage("sources.showAdult") private var showAdult = false
    private var repositoryID: String { RepositoriesModel.key(repository) }
    var body: some View {
        List {
            Section {
                HStack(spacing: 12) {
                    ExtensionIcon(ecosystem: repository.kind, size: 56)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(repository.title).font(.headline)
                        Text(ExtensionEcosystem(rawValue: repository.kind)?.description ?? repository.kind)
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                }.padding(.vertical, 6)
                if let url = URL(string: repository.url) {
                    Link(destination: url) { Label(url.host ?? repository.url, systemImage: "link") }
                }
            }
            if model.busy || model.error != nil || model.status != nil { Section { RepositoryStatusView(model: model) } }
            if let error = model.repositoryErrors[repositoryID] {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                        .accessibilityIdentifier("repository.error")
                    Button("重新刷新") { Task { await model.open(repository) } }.disabled(model.isRefreshing)
                }
            }
            if model.isRefreshing { ProgressView("正在刷新仓库…") }
            let filter = ExtensionFilter(query: query, showAdult: showAdult)
            let rows = (model.repositoryRows[repositoryID] ?? []).filter(filter.matches)
            let hiddenAdult = showAdult ? 0 : (model.repositoryRows[repositoryID] ?? [])
                .filter { $0.isAdult && $0.installed == nil && ExtensionFilter(query: query, showAdult: true).matches($0) }.count
            Section {
                ForEach(rows) { row in
                    ExtensionRow(row: row, model: model) {
                        selection = ExtensionSelection(id: row.id, repositoryID: repositoryID)
                    }
                }
            } header: {
                Text("扩展 · \(rows.count)")
            } footer: {
                // Hidden adult extensions would otherwise make a repository look as if it had nothing to install.
                if hiddenAdult > 0 {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("另有 \(hiddenAdult) 个成人内容扩展已隐藏。")
                        Button("显示成人内容扩展") { showAdult = true }
                            .font(.footnote.weight(.semibold))
                            .accessibilityIdentifier("repository.showAdult")
                    }
                }
            }
        }.navigationTitle(repository.title).navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "搜索此仓库的扩展")
            .refreshable { await model.open(repository) }
            .sheet(item: $selection) {
                ExtensionDetailsView(model: model, extensionID: $0.id, repositoryID: $0.repositoryID)
            }
    }
}

private struct ExtensionDetailsView: View {
    @ObservedObject var model: RepositoriesModel
    @EnvironmentObject private var sources: SourcesModel
    let extensionID: String
    var repositoryID: String? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var confirmingUninstall = false
    private var row: ExtensionSummary? {
        let rows = repositoryID.map { model.repositoryRows[$0] ?? [] } ?? model.rows
        return rows.first { $0.id == extensionID }
    }
    var body: some View {
        NavigationStack {
            Form {
                if let row {
                    Section {
                        HStack(spacing: 16) {
                            ExtensionIcon(ecosystem: row.ecosystem, title: row.title, url: row.iconURL, size: 64)
                            VStack(alignment: .leading, spacing: 6) {
                                Text(row.title).font(.title3.bold())
                                Text(row.ecosystemTitle).font(.subheadline).foregroundStyle(.secondary)
                                if row.installed?.bundled == true { Label("随应用提供", systemImage: "shippingbox.fill").font(.caption).foregroundStyle(.secondary) }
                            }
                        }.padding(.vertical, 8)
                        LabeledContent("版本", value: row.installedVersion ?? row.version)
                        if !row.languageDescription.isEmpty { LabeledContent("语言", value: row.languageDescription) }
                        if row.isAdult { Label("成人内容", systemImage: "18.circle").foregroundStyle(.orange) }
                        if let available = row.available {
                            LabeledContent("仓库", value: available.repositoryTitle)
                                .accessibilityIdentifier("extension.repository")
                        }
                    }
                    if model.busy || model.error != nil || model.status != nil { Section { RepositoryStatusView(model: model) } }
                    if let available = row.available {
                        Section {
                            if row.state == .update { LabeledContent("可更新至", value: available.version) }
                            Button(row.state == .installed ? String(localized: "重新安装") : row.stateTitle,
                                   systemImage: row.state == .update ? "arrow.triangle.2.circlepath" : "arrow.down.circle") {
                                Task { await model.install(row) }
                            }.disabled(model.busy || NativeSourceTransport.isFixtureRuntime)
                        }
                    }
                    let ids = Set(row.installed?.sourceIds ?? [])
                    let provided = sources.sources.filter { ids.contains($0.name) }
                    if !provided.isEmpty {
                        // Installed extensions: switch each language / site on or off, as Android's source settings.
                        Section {
                            ForEach(provided.sorted { ($0.locale, $0.title) < ($1.locale, $1.title) }, id: \.name) {
                                SourceToggleRow(source: $0)
                            }
                        } header: {
                            SourceGroupHeader(title: String(localized: "来源"), members: provided)
                        } footer: {
                            Text("关闭的来源不会出现在浏览页；多语言扩展默认只开启系统语言的来源。")
                        }
                    } else if !row.sourceNames.isEmpty {
                        Section("包含的来源 · \(row.sourceNames.count)") {
                            ForEach(Array(row.sourceNames.enumerated()), id: \.offset) { _, name in
                                Label(name, systemImage: row.symbol)
                            }
                        }
                    }
                    if let installed = row.installed, !installed.bundled {
                        Section {
                            Button("卸载扩展", systemImage: "trash", role: .destructive) { confirmingUninstall = true }
                                .disabled(model.busy)
                                .accessibilityIdentifier("extension.uninstall")
                        } footer: { Text("卸载后，此扩展提供的来源将不可用；收藏和阅读记录会保留。") }
                    }
                    Section {
                        DisclosureGroup("扩展信息") {
                            LabeledContent("包名") { Text(row.packageName).font(.caption.monospaced()).textSelection(.enabled) }
                            if let installed = row.installed {
                                LabeledContent("版本代码", value: String(installed.versionCode))
                                Text(installed.sha256).font(.caption2.monospaced()).textSelection(.enabled)
                            }
                        }
                    }
                } else { ContentUnavailableView("扩展已移除", systemImage: "shippingbox") }
            }.navigationTitle("扩展详情").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() }.accessibilityIdentifier("extension.close") } }
                .confirmationDialog("卸载此扩展？", isPresented: $confirmingUninstall, titleVisibility: .visible) {
                    if let installed = row?.installed {
                        Button("卸载", role: .destructive) {
                            Task { await model.uninstall(installed); if model.error == nil { dismiss() } }
                        }
                    }
                }
        }.presentationDetents([.large])
            .onAppear { model.clearMessage() }
    }
}
