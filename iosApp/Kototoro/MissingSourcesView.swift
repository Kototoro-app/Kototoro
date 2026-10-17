import KototoroShared
import SwiftUI

/// Recovery stays visible beside the affected collection rather than only inside extension settings.
struct MissingSourcesNotice: View {
    @EnvironmentObject private var library: LibraryModel
    var historyOnly = false
    private var missing: [IosMissingSource] {
        library.missingSources.filter { historyOnly ? $0.history > 0 : $0.favourites > 0 }
    }
    var body: some View {
        if !missing.isEmpty {
            NavigationLink { MissingSourcesView() } label: {
                Label {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(missing.count) 个来源缺失，\(missing.reduce(0) { $0 + Int(historyOnly ? $1.history : $1.favourites) }) 部作品受影响")
                            .font(.subheadline.weight(.medium))
                        Text("补齐来源或批量换源，继续阅读").font(.caption).foregroundStyle(.secondary)
                    }
                } icon: { Image(systemName: "shippingbox.and.arrow.backward").foregroundStyle(.orange) }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(12)
                    .background(Color.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
            }.buttonStyle(.plain).accessibilityIdentifier("recovery.notice")
        }
    }
}

struct MissingSourcesView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var repositories: RepositoriesModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var selected: Set<String> = []
    @State private var selectionInitialized = false
    @State private var query = ""
    @State private var scope = 0

    private var packages: [ExtensionSummary] {
        let keys = Set(library.missingSources.map(\.key))
        let providers = Set(library.missingSources.compactMap(\.packageKey))
        return repositories.rows.filter {
            $0.installed == nil && $0.permitsInstallation &&
                (providers.contains($0.id) || $0.available?.sourceKeys.contains(where: keys.contains) == true)
        }
    }
    private func affectedSources(_ row: ExtensionSummary) -> [IosMissingSource] {
        library.missingSources.filter { $0.packageKey == row.id || row.available?.sourceKeys.contains($0.key) == true }
    }
    private var covered: Set<String> { Set(packages.flatMap { affectedSources($0).map(\.key) }) }
    private var selectedPackages: [ExtensionSummary] { packages.filter { selected.contains($0.id) } }
    private var selectedCoverage: [IosMissingSource] {
        let keys = Set(selectedPackages.flatMap { affectedSources($0).map(\.key) })
        return library.missingSources.filter { keys.contains($0.key) }
    }
    private func matches(_ missing: IosMissingSource) -> Bool {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty || missing.title.localizedCaseInsensitiveContains(text) ||
            missing.key.localizedCaseInsensitiveContains(text)
    }
    private var visibleMissing: [IosMissingSource] {
        let available = covered
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let matchingRows = text.isEmpty ? [] : repositories.rows.filter {
            $0.title.localizedCaseInsensitiveContains(text) || $0.packageName.localizedCaseInsensitiveContains(text) ||
                $0.sourceNames.contains { $0.localizedCaseInsensitiveContains(text) }
        }
        let providerIDs = Set(matchingRows.map(\.id))
        var matchingPackages = Set(matchingRows.flatMap {
            ($0.available?.sourceKeys ?? []) + ($0.installed?.sourceIds ?? [])
        })
        matchingPackages.formUnion(library.missingSources.filter {
            $0.packageKey.map(providerIDs.contains) == true
        }.map(\.key))
        return library.missingSources.filter {
            (matches($0) || matchingPackages.contains($0.key)) &&
                (scope == 0 || (scope == 1 ? available.contains($0.key) : !available.contains($0.key)))
        }
    }
    private var visiblePackages: [ExtensionSummary] {
        guard scope != 2 else { return [] }
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return packages.filter { row in
            text.isEmpty || row.title.localizedCaseInsensitiveContains(text) ||
                row.packageName.localizedCaseInsensitiveContains(text) || affectedSources(row).contains(where: matches)
        }
    }
    private var selectionRequest: String { packages.map(\.id).sorted().joined(separator: "|") }
    private func title(_ missing: IosMissingSource) -> String {
        guard missing.title == missing.key else { return missing.title }
        return packages.first { affectedSources($0).contains { $0.key == missing.key } }?.title ?? missing.title
    }

    var body: some View {
        let coveredKeys = covered
        List {
            Section {
                Text("\(library.missingSources.count) 个缺失来源 · \(library.missingSources.reduce(0) { $0 + Int($1.works) }) 部作品")
                    .font(.headline).accessibilityIdentifier("recovery.summary")
                Text("检查全部收藏和历史，按来源身份精确匹配已添加仓库中的扩展。安装后会重新检查实际可用的来源。")
                    .font(.footnote).foregroundStyle(.secondary)
                if sources.isLoading || repositories.isRefreshing { ProgressView("正在检查来源与仓库…") }
                if let error = sources.extensionError { Text(error).font(.footnote).foregroundStyle(.red) }
                if !NativeSourceTransport.isAvailable {
                    Text("当前构建未包含扩展运行时。可以添加原 CMS／TVBox 配置，或将作品迁移到可用来源。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                RepositoryStatusView(model: repositories)
            }
            if library.missingSources.isEmpty {
                ContentUnavailableView("来源已齐全", systemImage: "checkmark.seal",
                                       description: Text("收藏和历史中的作品均能找到对应来源。"))
            } else {
                Section {
                    Picker("来源状态", selection: $scope) {
                        Text("全部").tag(0)
                        Text("可安装（\(coveredKeys.count)）").tag(1)
                        Text("需处理（\(library.missingSources.count - coveredKeys.count)）").tag(2)
                    }.pickerStyle(.segmented).accessibilityIdentifier("recovery.scope")
                }
                if !visiblePackages.isEmpty {
                    Section("可补齐的扩展（\(visiblePackages.count)）") {
                        HStack {
                            let keys = Set(visiblePackages.map(\.id))
                            Button(keys.isSubset(of: selected) ? "取消选择当前结果" : "全选当前结果") {
                                selected = BatchSelection.toggling(visible: keys, in: selected)
                            }
                            Spacer()
                            Button("推荐组合") { applyRecommendedSelection() }
                        }.buttonStyle(.borderless).disabled(repositories.busy)
                        ForEach(visiblePackages) { row in
                            Button {
                                if !selected.insert(row.id).inserted { selected.remove(row.id) }
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(row.title).foregroundStyle(.primary)
                                        Text("\(row.ecosystemTitle) · \(row.available?.repositoryTitle ?? "")")
                                            .font(.caption).foregroundStyle(.secondary)
                                        let affected = affectedSources(row)
                                        Text("可补齐 \(affected.count) 个来源，关联 \(affected.reduce(0) { $0 + Int($1.works) }) 部作品")
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: selected.contains(row.id) ? "checkmark.circle.fill" : "circle")
                                }.contentShape(Rectangle())
                            }.disabled(repositories.busy)
                                .accessibilityIdentifier("recovery.package.\(row.id)")
                                .accessibilityAddTraits(selected.contains(row.id) ? .isSelected : [])
                        }
                    }
                }
                if visibleMissing.isEmpty && visiblePackages.isEmpty {
                    if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        ContentUnavailableView("此状态没有缺失来源", systemImage: "checkmark.circle")
                    } else {
                        ContentUnavailableView.search(text: query)
                    }
                }
                Section("受影响的来源（\(visibleMissing.count)）") {
                    ForEach(visibleMissing, id: \.key) { missing in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(title(missing)).font(.headline)
                            Text("\(missing.works) 部作品 · \(missing.favourites) 部收藏 · \(missing.history) 部历史")
                                .font(.caption).foregroundStyle(.secondary)
                            if !coveredKeys.contains(missing.key) {
                                if repositories.rows.contains(where: {
                                    $0.installed != nil && affectedSources($0).contains { $0.key == missing.key }
                                }) {
                                    Label("扩展已安装，来源未加载", systemImage: "exclamationmark.triangle")
                                        .font(.footnote).foregroundStyle(.orange)
                                    NavigationLink("检查已安装扩展") { RepositoriesView() }
                                } else {
                                    Label("需添加原仓库或站点配置", systemImage: "link")
                                        .font(.footnote).foregroundStyle(.secondary)
                                }
                            } else {
                                Label("有匹配扩展可安装", systemImage: "shippingbox")
                                    .font(.footnote).foregroundStyle(.secondary)
                            }
                            NavigationLink("迁移此来源的作品") {
                                BatchMigrationView(contents: works(for: missing.key))
                            }.disabled(repositories.busy)
                            DisclosureGroup("来源详情") {
                                Text(missing.key).font(.caption).textSelection(.enabled)
                                Text("来源 ID 用于精确匹配。安装后仍未恢复时，请检查运行时错误或扩展版本；也可以迁移这些作品。")
                                    .font(.footnote).foregroundStyle(.secondary)
                            }.font(.caption)
                        }.accessibilityIdentifier("recovery.source.\(missing.key)")
                    }
                }
                Section {
                    NavigationLink("添加仓库或站点配置") { RepositoriesView(startTab: 1) }
                    ForEach(repositories.repositoryErrors.keys.sorted(), id: \.self) { key in
                        Text("仓库读取失败：\(repositories.repositoryErrors[key] ?? "")")
                            .font(.footnote).foregroundStyle(.red)
                    }
                }
            }
            if !repositories.batchResults.isEmpty {
                Section("最近批量安装／更新结果") {
                    ForEach(repositories.batchResults) { result in
                        Label {
                            VStack(alignment: .leading) {
                                Text(result.title)
                                Text(result.error ?? "扩展已安装；来源是否恢复以上方检查结果为准")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: result.error == nil ? "checkmark.circle.fill" : "exclamationmark.triangle")
                                .foregroundStyle(result.error == nil ? Color.green : Color.orange)
                        }
                    }
                    Text("失败和未处理的扩展仍可在上方选择并重试，已恢复来源不会重复安装。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("补齐缺失来源")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "搜索来源、扩展或来源 ID")
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !packages.isEmpty || repositories.batchProgress != nil { installationBar }
        }
        .navigationBarBackButtonHidden(repositories.batchProgress != nil)
        .interactiveDismissDisabled(repositories.batchProgress != nil)
        .onChange(of: scenePhase) { _, phase in if phase == .background { repositories.cancelBatchUpdate() } }
        .task {
            if repositories.catalogs.isEmpty { await repositories.load() }
            await library.refreshMissingSources(installed: sources.sources.map(\.name))
            initializeSelection()
        }
        .onChange(of: selectionRequest) { _, _ in initializeSelection() }
        .onChange(of: repositories.isRefreshing) { _, refreshing in if !refreshing { initializeSelection() } }
        .refreshable {
            await repositories.load()
            await library.refreshMissingSources(installed: sources.sources.map(\.name))
        }
    }

    private func initializeSelection() {
        guard !repositories.isRefreshing, !selectionInitialized, !packages.isEmpty, let database = library.library else { return }
        applyRecommendedSelection(database)
        selectionInitialized = true
    }

    private func applyRecommendedSelection(_ database: IosLibrary? = nil) {
        guard let database = database ?? library.library else { return }
        selected = Set(database.recovery.recommended(missing: library.missingSources,
            packages: packages.map { IosRecoveryPackage(key: $0.id, sourceKeys: affectedSources($0).map(\.key)) }))
    }

    private var installationBar: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let progress = repositories.batchProgress {
                ProgressView(value: Double(progress.completed), total: Double(max(1, progress.total)))
                Text("正在处理 \(progress.currentTitle) · \(progress.completed)/\(progress.total)").font(.footnote)
                Button("停止后续安装", role: .cancel) { repositories.cancelBatchUpdate() }
            } else {
                Text("已选 \(selectedPackages.count) 个扩展 · 涉及 \(selectedCoverage.count) 个来源、\(selectedCoverage.reduce(0) { $0 + Int($1.works) }) 部作品")
                    .font(.caption).foregroundStyle(.secondary)
                Text("安装包含其他搜索和筛选下保留的选择。")
                    .font(.caption2).foregroundStyle(.secondary)
                Button("一键安装全部所选 \(selectedPackages.count) 个扩展") {
                    let requested = selectedPackages
                    Task {
                        await repositories.installMissing(requested)
                        await library.refreshMissingSources(installed: sources.sources.map(\.name))
                    }
                }
                .buttonStyle(.borderedProminent).frame(maxWidth: .infinity, alignment: .trailing)
                .disabled(repositories.busy || repositories.isRefreshing || !NativeSourceTransport.isAvailable ||
                          selectedPackages.isEmpty)
                .accessibilityIdentifier("recovery.install")
            }
        }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.bar)
    }

    private func works(for key: String) -> [IosSourceContent] {
        var seen = Set<String>()
        return (library.favourites + library.history).map(\.content).filter {
            $0.sourceName == key && seen.insert(ContentMarks.key($0)).inserted
        }
    }
}
