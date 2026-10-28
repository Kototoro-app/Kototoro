import KototoroShared
import SwiftUI

/// Source selection and multi-source parallel search reuse the global search pipeline.
struct SourceChangeView: View {
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.dismiss) private var dismiss
    @AppStorage("sources.showAdult") private var showAdult = false
    let runtime: IosSources
    let content: IosSourceContent
    let onChanged: (IosSourceContent) -> Void

    @StateObject private var searchModel = GlobalSearchModel()
    @State private var query: String
    @State private var selectedSources: Set<String> = []
    /// Until the picker is answered every candidate is searched; an emptied answer never means "all".
    @State private var sourcesChosen = false
    @State private var showingSourceFilter = false

    init(runtime: IosSources, content: IosSourceContent, onChanged: @escaping (IosSourceContent) -> Void) {
        self.runtime = runtime
        self.content = content
        self.onChanged = onChanged
        _query = State(initialValue: content.title)
    }

    private var candidateSources: [IosSource] {
        sources.ordered(sources.sources.filter {
            $0.name != content.sourceName && $0.mediaKind == content.mediaKind && $0.ecosystem != "LOCAL" &&
                sources.isEnabled($0) && (showAdult || !$0.isAdult || content.contentType.hasPrefix("HENTAI_"))
        })
    }

    private var activeSources: [IosSource] {
        sourcesChosen ? candidateSources.filter { selectedSources.contains($0.name) } : candidateSources
    }

    private var summary: String {
        if activeSources.isEmpty { return "未选择检索源" }
        return "在 \(activeSources.count) 个来源中检索可替换的作品"
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(content.title).font(.headline)
                        Text(summary)
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }

                if activeSources.isEmpty {
                    Section {
                        Text(candidateSources.isEmpty ? "没有其他可用来源。先在扩展管理安装或启用同类型来源。"
                             : "在「筛选源」中至少选择一个来源后再搜索。")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                }

                if searchModel.isSearching {
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("正在换源搜索 (\(searchModel.completed)/\(activeSources.count))…")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }

                let successfulRows = searchModel.rows.filter { row in
                    if case .done(let items) = row.status, !items.isEmpty { return true }
                    return false
                }

                if !successfulRows.isEmpty {
                    ForEach(successfulRows) { row in
                        if case .done(let items) = row.status {
                            Section(row.source.title) {
                                ForEach(items.filter { $0.mediaKind == content.mediaKind && $0.sourceName != content.sourceName }, id: \.id) { item in
                                    NavigationLink {
                                        SourceChangeConfirmView(runtime: runtime, original: content, target: item, onChanged: onChanged)
                                    } label: {
                                        HStack(alignment: .top, spacing: 12) {
                                            CoverImage(runtime: runtime, content: item)
                                                .frame(width: 48, height: 72)
                                                .clipShape(RoundedRectangle(cornerRadius: 6))
                                            VStack(alignment: .leading, spacing: 4) {
                                                Text(item.title).font(.headline).lineLimit(2)
                                                if !item.authors.isEmpty {
                                                    Text(item.authors.joined(separator: "、"))
                                                        .font(.caption).foregroundStyle(.secondary)
                                                }
                                                HStack(spacing: 6) {
                                                    Text(row.source.title)
                                                        .font(.caption2)
                                                        .padding(.horizontal, 6).padding(.vertical, 2)
                                                        .background(Color(.tertiarySystemFill), in: Capsule())
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if !searchModel.isSearching && searchModel.completed > 0 {
                    Section {
                        Text("未在所选来源中找到相关作品，请尝试调整搜索关键词。")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .searchable(text: $query, prompt: "搜索替换作品")
            .onSubmit(of: .search) { triggerSearch(force: true) }
            .navigationTitle("换源")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showingSourceFilter = true
                    } label: {
                        Label(sourcesChosen ? "筛选源（\(activeSources.count)）" : "筛选源",
                              systemImage: sourcesChosen ? "line.3.horizontal.decrease.circle.fill"
                                                         : "line.3.horizontal.decrease.circle")
                    }
                    .accessibilityIdentifier("sourceChange.sources")
                }
            }
            .sheet(isPresented: $showingSourceFilter) {
                SourceSelectionSheet(
                    title: String(localized: "选择检索源"),
                    sources: candidateSources,
                    selected: sourcesChosen ? selectedSources : Set(candidateSources.map(\.name))
                ) { chosen in
                    sourcesChosen = true
                    selectedSources = chosen
                    triggerSearch(force: true)
                }
            }
            .task {
                triggerSearch(force: false)
            }
        }
    }

    private func triggerSearch(force: Bool = false) {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !activeSources.isEmpty else { return }
        if !force && searchModel.submitted == text && !searchModel.rows.isEmpty {
            return
        }
        searchModel.search(text, sources: activeSources, runtime: runtime)
    }
}

private struct SourceChangeConfirmView: View {
    @EnvironmentObject private var library: LibraryModel
    @StateObject private var model: SourceDetailsModel
    @State private var keepOriginal = false
    @State private var isSaving = false
    @State private var error: String?
    let original: IosSourceContent
    let onChanged: (IosSourceContent) -> Void

    init(runtime: IosSources, original: IosSourceContent, target: IosSourceContent,
         onChanged: @escaping (IosSourceContent) -> Void) {
        _model = StateObject(wrappedValue: SourceDetailsModel(runtime: runtime, content: target))
        self.original = original
        self.onChanged = onChanged
    }

    var body: some View {
        List {
            Section("目标作品") {
                let shown = model.details ?? model.content
                HStack(alignment: .top) {
                    CoverImage(runtime: model.runtime, content: shown).frame(width: 72, height: 108).clipped()
                    VStack(alignment: .leading, spacing: 8) {
                        Text(shown.title).font(.headline)
                        if !shown.authors.isEmpty {
                            Text(shown.authors.joined(separator: "、")).font(.subheadline).foregroundStyle(.secondary)
                        }
                        if model.isLoading {
                            Text("正在读取章节…").font(.subheadline).foregroundStyle(.secondary)
                        } else {
                            Text("\(model.chapters.count) 个章节").accessibilityIdentifier("sourceChange.chapterCount")
                        }
                    }
                }
                if let summary = shown.summary { Text(summary).font(.footnote).lineLimit(8) }
                if model.isLoading { ProgressView("正在读取目标章节…") }
                if let message = model.error {
                    Text(message).foregroundStyle(.red)
                    if let challenge = model.challenge {
                        ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                              sourceName: model.content.sourceName) { await model.load() }
                    }
                    Button("重试") { Task { await model.load() } }.disabled(model.isLoading)
                } else if !model.isLoading, model.details != nil, model.chapters.isEmpty {
                    Text("目标作品没有可用章节，无法换源。").foregroundStyle(.orange)
                }
            }
            Section {
                Toggle("保留原作品", isOn: $keepOriginal).accessibilityIdentifier("sourceChange.keepOriginal")
                Text(keepOriginal ? "复制收藏分类和阅读进度，保留原作品的收藏与历史。"
                     : "迁移收藏分类和阅读进度，移除原作品的收藏与历史。")
                    .font(.footnote).foregroundStyle(.secondary)
                Text("原有离线文件和书签保留在旧作品下。章节优先按卷号、章节号匹配，否则按章节位置匹配。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let error { Text(error).foregroundStyle(.red).accessibilityIdentifier("sourceChange.error") }
            Button {
                Task { await save() }
            } label: {
                HStack {
                    if isSaving { ProgressView() }
                    Text(keepOriginal ? "确认复制到此来源" : "确认换源")
                }.frame(maxWidth: .infinity)
            }
            .disabled(isSaving || model.isLoading || model.error != nil || model.chapters.isEmpty || library.library == nil)
            .accessibilityIdentifier("sourceChange.confirm")
        }
        .navigationTitle("确认换源")
        .navigationBarBackButtonHidden(isSaving)
        .interactiveDismissDisabled(isSaving)
        .disabled(isSaving)
        .task { await model.load() }
    }

    @MainActor
    private func save() async {
        guard !isSaving, let database = library.library, let target = model.details, !model.chapters.isEmpty else { return }
        isSaving = true
        defer { isSaving = false }
        error = nil
        do {
            try await database.changeSource(content: original, target: target, keepOriginal: keepOriginal)
            await library.refresh()
            onChanged(target)
        } catch { self.error = error.localizedDescription }
    }
}
