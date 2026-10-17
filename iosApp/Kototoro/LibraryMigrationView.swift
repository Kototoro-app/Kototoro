import Combine
import KototoroShared
import SwiftUI

struct LibraryMigrationRequest: Identifiable {
    let id = UUID()
    let contents: [IosSourceContent]
}

/// Both entry points take a snapshot: a refreshed library must not change a batch being reviewed.
struct LibraryMigrationView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    let favourites: [IosSourceContent]
    @State private var mode = 0
    @State private var selected: Set<String> = []
    @State private var query = ""
    @State private var cachedSourceGroups: [String: [IosSourceContent]]?

    private var sourceGroups: [String: [IosSourceContent]] {
        if let cached = cachedSourceGroups { return cached }
        var seen = Set<String>()
        let works = (library.favourites + library.history).map(\.content).filter {
            !$0.isLocal && seen.insert(ContentMarks.key($0)).inserted
        }
        return Dictionary(grouping: works, by: \.sourceName)
    }

    private var allWorks: [IosSourceContent] {
        sourceGroups.values.flatMap { $0 }
    }
    private var selectable: [IosSourceContent] {
        favourites.filter { !$0.isLocal }
    }
    private var visible: [IosSourceContent] {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return selectable.filter { text.isEmpty || $0.title.localizedCaseInsensitiveContains(text) }
    }
    private func title(_ key: String) -> String {
        sources.sources.first { $0.name == key }?.title ?? library.missingSources.first { $0.key == key }?.title ?? key
    }

    private var selectedContents: [IosSourceContent] {
        selectable.filter { selected.contains(ContentMarks.key($0)) }
    }

    var body: some View {
        List {
            Section {
                Picker("选择方式", selection: $mode) {
                    Text("选择收藏").tag(0)
                    Text("按原来源").tag(1)
                }.pickerStyle(.segmented).accessibilityIdentifier("migration.scope")
                Text(mode == 0 ? "从当前分类和筛选结果中选择需要换源的收藏。"
                     : "选择原来源，迁移它在全部收藏和历史中关联的作品；相同作品只处理一次。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if mode == 0 {
                Section {
                    let visibleKeys = Set(visible.map(ContentMarks.key))
                    HStack {
                        Button(!visibleKeys.isEmpty && visibleKeys.isSubset(of: selected)
                               ? "取消选择当前结果" : "全选当前结果（\(visible.count)）") {
                            selected = BatchSelection.toggling(visible: visibleKeys, in: selected)
                        }.disabled(visible.isEmpty).accessibilityIdentifier("migration.selectVisible")
                        if !selected.isEmpty {
                            Spacer()
                            Button("清空已选") {
                                selected.removeAll()
                            }
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        }
                    }
                    Text("已选 \(selected.count) 部；当前搜索结果 \(visible.count) 部。其他搜索下的选择会保留。")
                        .font(.caption).foregroundStyle(.secondary)
                    ForEach(visible, id: \.id) { content in
                        Button {
                            let key = ContentMarks.key(content)
                            if !selected.insert(key).inserted { selected.remove(key) }
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(content.title).foregroundStyle(.primary)
                                    Text(title(content.sourceName)).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: selected.contains(ContentMarks.key(content))
                                      ? "checkmark.circle.fill" : "circle")
                            }
                        }.accessibilityIdentifier("migration.select.\(content.id)")
                            .accessibilityAddTraits(selected.contains(ContentMarks.key(content)) ? .isSelected : [])
                    }
                }
                if visible.isEmpty && !selectable.isEmpty {
                    Section {
                        ContentUnavailableView.search(text: query)
                    }
                }
            } else {
                let groups = sourceGroups
                Section("原来源（含缺失来源）") {
                    ForEach(groups.keys.sorted().filter { query.isEmpty || title($0).localizedCaseInsensitiveContains(query) },
                            id: \.self) { key in
                        NavigationLink {
                            BatchMigrationView(contents: groups[key] ?? [])
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(title(key))
                                Text("\(groups[key]?.count ?? 0) 部作品" +
                                     (library.missingSources.contains { $0.key == key } ? " · 来源缺失" : ""))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }.accessibilityIdentifier("migration.source.\(key)")
                    }
                }
            }
            if (mode == 0 ? selectable.isEmpty : sourceGroups.isEmpty) {
                ContentUnavailableView("没有可迁移的作品", systemImage: "arrow.triangle.swap",
                                       description: Text("本地导入作品无需换源。"))
            }
        }
        .searchable(text: $query, prompt: mode == 0 ? "搜索收藏" : "搜索原来源")
        .navigationTitle("批量换源")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if mode == 0 && !selectable.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink {
                        BatchMigrationView(contents: selectedContents)
                    } label: {
                        Text(selected.isEmpty ? "下一步" : "下一步(\(selected.count))")
                            .fontWeight(.semibold)
                    }
                    .disabled(selected.isEmpty)
                    .accessibilityIdentifier("migration.next.top")
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if mode == 0 && !selectable.isEmpty {
                nextBar
            }
        }
        .onAppear { cachedSourceGroups = sourceGroups }
        .onChange(of: library.recordsRevision) { _, _ in cachedSourceGroups = sourceGroups }
    }

    private var nextBar: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("已选 \(selected.count) 部作品")
                    .font(.subheadline.weight(.medium))
                Text(selected.isEmpty ? "请在上方勾选要换源的作品" : "已准备就绪")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            NavigationLink {
                BatchMigrationView(contents: selectedContents)
            } label: {
                HStack(spacing: 4) {
                    Text(selected.isEmpty ? "下一步" : "下一步（\(selected.count)）")
                        .fontWeight(.semibold)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.bold))
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(selected.isEmpty)
            .accessibilityIdentifier("migration.next")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }
}

@MainActor
final class BatchMigrationModel: ObservableObject {
    struct Row: Identifiable {
        let original: IosSourceContent
        var candidates: [IosSourceContent] = []
        var target: IosSourceContent?
        var message = "等待搜索"
        var completed = false
        var skipped = false
        var id: String { ContentMarks.key(original) }
    }
    @Published private(set) var rows: [Row]
    @Published private(set) var running = false
    @Published private(set) var progress = ""
    private var stopRequested = false

    init(contents: [IosSourceContent]) {
        var seen = Set<String>()
        rows = contents.filter { seen.insert(ContentMarks.key($0)).inserted }.map { Row(original: $0) }
    }
    var readyCount: Int { rows.filter { !$0.completed && !$0.skipped && $0.target != nil }.count }
    var completedCount: Int { rows.filter(\.completed).count }
    var skippedCount: Int { rows.filter(\.skipped).count }
    var unselectedCount: Int { rows.filter { !$0.completed && !$0.skipped && $0.target == nil }.count }
    func stop() { stopRequested = true; progress = "正在结束当前作品，随后停止…" }
    func choose(_ target: IosSourceContent, for id: String) {
        guard !running, let index = rows.firstIndex(where: { $0.id == id }), !rows[index].completed else { return }
        rows[index].target = target
        rows[index].skipped = false
        rows[index].message = "已选择，等待迁移"
    }
    func skip(_ id: String) {
        guard !running, let index = rows.firstIndex(where: { $0.id == id }), !rows[index].completed else { return }
        rows[index].target = nil
        rows[index].skipped = true
        rows[index].message = "已跳过，不参与批量搜索与迁移"
    }
    func include(_ id: String) {
        guard !running, let index = rows.firstIndex(where: { $0.id == id }), !rows[index].completed else { return }
        rows[index].skipped = false
        rows[index].message = "等待搜索"
    }
    func selectUnambiguous() {
        guard !running else { return }
        var reserved = Set(rows.map(\.id) + rows.compactMap { $0.target.map(ContentMarks.key) })
        for index in rows.indices where !rows[index].completed && !rows[index].skipped && rows[index].target == nil {
            guard let match = IosMigrationMatching.shared.uniqueExactMatch(original: rows[index].original,
                candidates: rows[index].candidates), reserved.insert(ContentMarks.key(match)).inserted else { continue }
            rows[index].target = match
            rows[index].message = "已采用唯一同名候选，请核对作者与版本"
        }
    }
    private func begin() { running = true; stopRequested = false }

    func search(runtime: IosSources, sources: [IosSource], allowAdult: Bool,
                only id: String? = nil, query: String? = nil) async {
        guard !running else { return }
        begin()
        defer { running = false }
        for index in rows.indices where !rows[index].completed &&
            (id == nil ? !rows[index].skipped && rows[index].target == nil : rows[index].id == id) {
            if stopRequested || Task.isCancelled { break }
            let original = rows[index].original
            var found: [IosSourceContent] = []
            var failures: [String] = []
            let eligible = sources.filter {
                $0.name != original.sourceName && $0.mediaKind == original.mediaKind &&
                    (allowAdult || !$0.isAdult || original.contentType.hasPrefix("HENTAI_"))
            }
            for (offset, source) in eligible.enumerated() {
                if stopRequested || Task.isCancelled { break }
                progress = "\(original.title) · 正在搜索 \(source.title)（\(offset + 1)/\(eligible.count)）"
                do {
                    let results = try await runtime.filteredPage(sourceName: source.name, page: 0, loadedCount: 0,
                                                               query: query ?? original.title, selection: nil)
                    found += results
                } catch { failures.append("\(source.title)：\(SourceErrorText.describe(error))") }
            }
            rows[index].candidates = IosMigrationMatching.shared.rank(original: original, candidates: found)
            rows[index].message = "找到 \(rows[index].candidates.count) 个候选，请选择" +
                (failures.isEmpty ? "" : "；\(failures.count) 个来源失败：\(failures.joined(separator: "；"))")
            if eligible.isEmpty { rows[index].message = "没有可搜索的同类型来源，请先安装或启用来源" }
        }
        progress = stopRequested ? "已停止搜索，可继续搜索剩余作品" : "搜索完成，请逐部核对候选"
    }

    func migrate(runtime: IosSources, library: LibraryModel, keepOriginal: Bool) async {
        guard !running, let database = library.library, readyCount > 0 else { return }
        begin()
        defer { running = false }
        let originals = Set(rows.map(\.id))
        var usedTargets = Set(rows.filter(\.completed).compactMap { $0.target.map(ContentMarks.key) })
        for index in rows.indices where !rows[index].completed && !rows[index].skipped {
            if stopRequested || Task.isCancelled { break }
            guard let candidate = rows[index].target else { continue }
            let key = ContentMarks.key(candidate)
            guard !originals.contains(key), !usedTargets.contains(key) else {
                rows[index].message = "目标与本批其他作品重复，请重新选择，避免合并不同作品"
                continue
            }
            progress = "正在迁移 \(rows[index].original.title)（已完成 \(completedCount)/\(rows.count)）"
            do {
                let details = try await runtime.details(content: candidate)
                guard ContentMarks.key(details) == key else {
                    rows[index].message = "目标作品身份已变化，未迁移；请重新搜索并选择"
                    continue
                }
                // Stop before writing if the user stopped while waiting for the source.
                if stopRequested || Task.isCancelled { break }
                try await database.changeSource(content: rows[index].original, target: details, keepOriginal: keepOriginal)
                rows[index].completed = true
                usedTargets.insert(key)
                rows[index].message = "迁移成功"
            } catch { rows[index].message = "迁移失败：\(SourceErrorText.describe(error))。原记录保留，可重试。" }
        }
        await library.refresh()
        progress = "\(stopRequested ? "已停止" : "本轮完成")：\(completedCount) 部成功，\(readyCount) 部待处理，" +
            "\(unselectedCount) 部未选择，\(skippedCount) 部已跳过"
    }
}

struct BatchMigrationView: View {
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @AppStorage("sources.showAdult") private var showAdult = false
    @StateObject private var model: BatchMigrationModel
    @State private var selectedSources: Set<String> = []
    @State private var keepOriginal = true
    @State private var confirming = false
    @State private var selecting: String?
    @State private var customQuery = ""
    @State private var choosingSources = false
    @State private var sourceQuery = ""
    @FocusState private var sourceSearchFocused: Bool

    init(contents: [IosSourceContent]) { _model = StateObject(wrappedValue: BatchMigrationModel(contents: contents)) }

    private var eligible: [IosSource] {
        let kinds = Set(model.rows.map { $0.original.mediaKind })
        return sources.ordered(sources.sources.filter {
            $0.ecosystem != "LOCAL" && kinds.contains($0.mediaKind) && sources.isEnabled($0) &&
                (showAdult || !$0.isAdult || model.rows.contains { $0.original.contentType.hasPrefix("HENTAI_") })
        })
    }
    private var active: [IosSource] { eligible.filter { selectedSources.contains($0.name) } }
    private func sourceTitle(_ key: String) -> String {
        sources.sources.first { $0.name == key }?.title ?? library.missingSources.first { $0.key == key }?.title ?? key
    }
    private var visibleSources: [IosSource] {
        let text = sourceQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        return eligible.filter { text.isEmpty || $0.title.localizedCaseInsensitiveContains(text) }
    }

    var body: some View {
        List {
            Section("1 · 选择搜索的新来源") {
                Text("共 \(model.rows.count) 部作品。只搜索勾选的来源；候选按名称相似度排序，需要逐部核对。")
                    .font(.footnote).foregroundStyle(.secondary)
                Button("选择新来源（已选 \(active.count) 个）") { choosingSources = true }
                    .disabled(model.running).accessibilityIdentifier("migration.sources")
                if !active.isEmpty {
                    Text(active.map(\.title).joined(separator: "、")).font(.caption).foregroundStyle(.secondary)
                        .lineLimit(3)
                }
                if eligible.isEmpty {
                    NavigationLink("安装更多来源") { RepositoriesView() }
                }
                Button("搜索待选择的作品（\(active.count) 个来源）") { search() }
                    .disabled(model.running || active.isEmpty || sources.runtime == nil || model.unselectedCount == 0)
                    .accessibilityIdentifier("migration.search")
            }
            Section("2 · 核对目标作品") {
                Button("采用唯一同名候选") { model.selectUnambiguous() }
                    .disabled(model.running || !model.rows.contains {
                        !$0.completed && !$0.skipped && $0.target == nil && !$0.candidates.isEmpty
                    })
                    .accessibilityIdentifier("migration.useExact")
                Text("只为待选择作品填入唯一的同名／别名结果；多个同名结果保持待选择。名称一致仍需核对作者与版本。")
                    .font(.footnote).foregroundStyle(.secondary)
                ForEach(model.rows) { row in
                    if row.completed {
                        workLabel(row).accessibilityIdentifier("migration.completed.\(row.original.id)")
                    } else {
                        Button {
                            selecting = row.id
                            customQuery = row.original.title
                        } label: {
                            workLabel(row)
                        }
                        .disabled(model.running)
                        .accessibilityIdentifier("migration.work.\(row.original.id)")
                        .accessibilityValue(row.skipped ? "已跳过" : (row.target == nil ? "待选择" : "已选择"))
                    }
                }
            }
            Section("3 · 执行迁移") {
                Toggle("保留原作品", isOn: $keepOriginal).disabled(model.running || model.completedCount > 0)
                    .accessibilityIdentifier("migration.keepOriginal")
                Text(keepOriginal ? "复制收藏分类和阅读进度，保留原收藏和历史。"
                     : "迁移成功后移除原收藏和历史，失败的作品保持原样。")
                    .font(.footnote).foregroundStyle(.secondary)
                Text("章节按卷号、章节号或位置匹配，可能存在偏差。原离线文件和书签仍留在原作品下。已有目标作品会合并分类并保留较新的进度。")
                    .font(.footnote).foregroundStyle(.secondary)
                Text("执行前会读取目标章节；没有可用章节的作品不会迁移。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("核对与迁移")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom, spacing: 0) { executionBar }
        .navigationBarBackButtonHidden(model.running)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("完成") { dismiss() }.disabled(model.running).accessibilityIdentifier("migration.done")
            }
        }
        .interactiveDismissDisabled(model.running)
        .onChange(of: scenePhase) { _, phase in if phase == .background && model.running { model.stop() } }
        .confirmationDialog("\(keepOriginal ? "复制" : "迁移") \(model.readyCount) 部作品？", isPresented: $confirming,
                            titleVisibility: .visible) {
            Button(keepOriginal ? "确认复制" : "确认迁移并移除原收藏和历史", role: keepOriginal ? nil : .destructive) {
                guard let runtime = sources.runtime else { return }
                Task { await model.migrate(runtime: runtime, library: library, keepOriginal: keepOriginal) }
            }
        } message: { Text("仅处理已选择目标的作品。可以停止后续任务，已完成的结果会保留。") }
        .sheet(isPresented: Binding(get: { selecting != nil }, set: { if !$0 { selecting = nil } })) { candidatePicker }
        .sheet(isPresented: $choosingSources) { sourcePicker }
    }

    private func search() {
        guard let runtime = sources.runtime else { return }
        Task { await model.search(runtime: runtime, sources: active, allowAdult: showAdult) }
    }

    private func workLabel(_ row: BatchMigrationModel.Row) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(row.original.title).font(.headline).foregroundStyle(.primary)
            Text("原来源：\(sourceTitle(row.original.sourceName))").font(.caption).foregroundStyle(.secondary)
            if let target = row.target {
                Text("→ \(target.title) · \(sourceTitle(target.sourceName))").font(.subheadline)
                    .foregroundStyle(.primary)
            }
            Label(row.message, systemImage: row.completed ? "checkmark.circle.fill" : row.skipped ? "forward.end" : "info.circle")
                .font(.caption).foregroundStyle(row.completed ? Color.green : Color.secondary)
        }
    }

    private var executionBar: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("\(model.completedCount) 部成功 · \(model.readyCount) 部待执行 · \(model.unselectedCount) 部待选择 · \(model.skippedCount) 部跳过")
                .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            if !model.progress.isEmpty {
                Text(model.progress).font(.footnote).lineLimit(2).accessibilityIdentifier("migration.progress")
            }
            if model.running {
                HStack {
                    ProgressView().frame(maxWidth: .infinity, alignment: .leading)
                    Button("停止后续任务", role: .cancel) { model.stop() }
                }
            } else if model.completedCount == model.rows.count && !model.rows.isEmpty {
                Label("全部迁移完成", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
            } else {
                Button("\(model.completedCount > 0 ? "继续／重试" : "迁移")已选择的 \(model.readyCount) 部作品") { confirming = true }
                    .buttonStyle(.borderedProminent).frame(maxWidth: .infinity, alignment: .trailing)
                    .disabled(model.readyCount == 0 || library.library == nil || sources.runtime == nil)
                    .accessibilityIdentifier("migration.execute")
            }
        }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.bar)
    }

    private var sourcePicker: some View {
        NavigationStack {
            List {
                Section {
                    TextField("搜索新来源", text: $sourceQuery)
                        .focused($sourceSearchFocused).submitLabel(.search)
                        .onSubmit { sourceSearchFocused = false }
                        .accessibilityIdentifier("migration.sources.query")
                    let keys = Set(visibleSources.map(\.name))
                    Button(!keys.isEmpty && keys.isSubset(of: selectedSources) ? "取消选择当前结果" : "全选搜索结果") {
                        sourceSearchFocused = false
                        selectedSources = BatchSelection.toggling(visible: keys, in: selectedSources)
                    }.disabled(keys.isEmpty)
                    Button("清空选择") { selectedSources.removeAll() }
                }
                Section("已启用的同类型来源") {
                    ForEach(visibleSources, id: \.name) { source in
                        Button {
                            sourceSearchFocused = false
                            if !selectedSources.insert(source.name).inserted { selectedSources.remove(source.name) }
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(source.title).foregroundStyle(.primary)
                                    Text("\(ContentSpace.title(source.mediaKind)) · \(SourceLanguage.title(source.locale))")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: selectedSources.contains(source.name) ? "checkmark.circle.fill" : "circle")
                            }
                            .contentShape(Rectangle())
                        }
                        .accessibilityAddTraits(selectedSources.contains(source.name) ? .isSelected : [])
                        .accessibilityIdentifier("migration.targetSource.\(source.name)")
                    }
                }
            }
            .navigationTitle("选择新来源")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成（\(active.count)）") { choosingSources = false }
                        .accessibilityIdentifier("migration.sources.done")
                }
            }
        }
    }

    private var candidatePicker: some View {
        NavigationStack {
            List {
                if let row = model.rows.first(where: { $0.id == selecting }) {
                    Section {
                        Text(row.original.title).font(.headline)
                        TextField("调整搜索关键词", text: $customQuery)
                        Button("重新搜索此作品") {
                            guard let runtime = sources.runtime else { return }
                            Task { await model.search(runtime: runtime, sources: active, allowAdult: showAdult,
                                                      only: row.id, query: customQuery) }
                        }.disabled(model.running || active.isEmpty || customQuery.trimmingCharacters(in: .whitespaces).isEmpty)
                        Text(row.message).font(.footnote).foregroundStyle(.secondary)
                        if model.running {
                            ProgressView(model.progress)
                            Button("停止搜索") { model.stop() }
                        }
                        if row.skipped {
                            Button("重新加入待处理") { model.include(row.id); selecting = nil }.disabled(model.running)
                        } else {
                            Button("跳过此作品") { model.skip(row.id); selecting = nil }.disabled(model.running)
                                .accessibilityIdentifier("migration.skip")
                        }
                    }
                    ForEach(row.candidates, id: \.id) { candidate in
                        Button {
                            model.choose(candidate, for: row.id)
                            selecting = nil
                        } label: {
                            HStack {
                                if let runtime = sources.runtime {
                                    CoverImage(runtime: runtime, content: candidate).frame(width: 44, height: 66).clipped()
                                }
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(candidate.title)
                                    Text(sourceTitle(candidate.sourceName)).font(.caption).foregroundStyle(.secondary)
                                    Text(candidate.authors.joined(separator: "、")).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }.disabled(model.running).accessibilityIdentifier("migration.candidate.\(candidate.id)")
                    }
                }
            }
            .navigationTitle("选择目标作品")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { selecting = nil }.disabled(model.running) } }
            .interactiveDismissDisabled(model.running)
        }
    }
}
