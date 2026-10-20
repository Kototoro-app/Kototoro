import KototoroShared
import SwiftUI

/// Android's duplicate finder: favourites that are one work (same title or alt title, 繁/简 folded) are listed in
/// groups; the user picks which entry stays and merges the rest into it, or dismisses the group as not duplicates.
struct LibraryDuplicatesView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    /// Groups dismissed as "not duplicates", one sorted id list per line.
    @AppStorage("library.ignoredDuplicates") private var ignoredRaw = ""
    @State private var groups: [IosDuplicateGroup]?
    @State private var loadError: String?
    /// Group key → id of the entry to keep; the recommended entry until the user picks another.
    @State private var keep: [String: String] = [:]
    @State private var merging: Set<String> = []
    @State private var errors: [String: String] = [:]
    @State private var mergedCount = 0
    @State private var confirmingMergeAll = false
    @State private var mergingAll = false
    @State private var opened: IosSourceContent?
    @AppStorage("library.duplicates.fuzzy") private var fuzzy = false
    /// Minimum title similarity, 0.5…1, for fuzzy matching.
    @AppStorage("library.duplicates.threshold") private var threshold = 0.8
    /// The slider's value while it is dragged; the search reruns once it is released.
    @State private var draftThreshold: Double?
    /// Discards a search that a newer one (other settings) has replaced.
    @State private var generation = 0

    private var ignored: [String] { ignoredRaw.split(separator: "\n").map(String.init) }

    var body: some View {
        content
            .navigationTitle("查找重复")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if let groups, groups.count > 1 {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("全部合并") { confirmingMergeAll = true }
                            .disabled(mergingAll || !merging.isEmpty)
                            .accessibilityIdentifier("duplicates.mergeAll")
                    }
                }
            }
            .confirmationDialog("合并全部 \(groups?.count ?? 0) 组重复？", isPresented: $confirmingMergeAll,
                                titleVisibility: .visible) {
                Button("全部合并") { Task { await mergeAll() } }
            } message: {
                Text(String(localized: "每组都会合并到选中的那一部。其余条目会移出收藏和历史，已下载的内容和书签会保留。")
                     + ((groups ?? []).contains(where: \.fuzzy) ? String(localized: "其中有模糊匹配的组，请先逐组确认。") : ""))
            }
            .navigationDestination(item: $opened) { content in
                if let runtime = sources.runtime { SourceDetailsView(runtime: runtime, content: content) }
            }
            .task { if groups == nil { await load() } }
            .refreshable { await load() }
    }

    private var content: some View {
        List {
            matchingSection
            if let groups {
                if groups.isEmpty {
                    ContentUnavailableView {
                        Label("没有发现重复的收藏", systemImage: "checkmark.circle")
                    } description: {
                        Text(mergedCount > 0 ? String(localized: "已合并 \(mergedCount) 组。")
                             : fuzzy ? String(localized: "可以调低相似度阈值，找出写法差异更大的标题。")
                             : String(localized: "标题或别名相同（含繁简体）的同类收藏会出现在这里。"))
                    }
                    .listRowBackground(Color.clear)
                    .accessibilityIdentifier("duplicates.empty")
                } else {
                    Section {
                        Text("点选要保留的那一部。合并会把收藏分类、笔记、统计和追踪并入保留的作品，并保留读得最远的进度。")
                            .font(.footnote).foregroundStyle(.secondary)
                        if mergedCount > 0 {
                            Label("已合并 \(mergedCount) 组", systemImage: "checkmark.circle.fill")
                                .font(.footnote).foregroundStyle(.green)
                        }
                    }
                    ForEach(groups, id: \.key) { group in
                        Section {
                            ForEach(group.entries, id: \.id) { entry in row(entry, in: group) }
                        } header: {
                            header(group)
                        } footer: {
                            footer(group)
                        }
                    }
                }
            } else if let loadError {
                ContentUnavailableView {
                    Label("无法查找重复", systemImage: "exclamationmark.triangle")
                } description: { Text(loadError) } actions: {
                    Button("重试") { Task { await load() } }
                }
                .listRowBackground(Color.clear)
            } else {
                ProgressView("正在查找重复…").frame(maxWidth: .infinity).padding(.vertical, 40)
                    .listRowBackground(Color.clear)
            }
        }
        .disabled(mergingAll)
    }

    /// Exact matching by default; fuzzy matching adds near titles above the chosen similarity.
    private var matchingSection: some View {
        Section {
            Toggle("模糊匹配", isOn: $fuzzy)
                .accessibilityIdentifier("duplicates.fuzzy")
            if fuzzy {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("相似度阈值")
                        Spacer()
                        Text("\(Int(((draftThreshold ?? threshold) * 100).rounded()))%")
                            .monospacedDigit().foregroundStyle(.secondary)
                    }
                    Slider(value: Binding(get: { draftThreshold ?? threshold }, set: { draftThreshold = $0 }),
                           in: 0.5...0.95, step: 0.05) {
                        Text("相似度阈值")
                    } minimumValueLabel: {
                        Text("宽松").font(.caption)
                    } maximumValueLabel: {
                        Text("严格").font(.caption)
                    } onEditingChanged: { editing in
                        guard !editing, let value = draftThreshold else { return }
                        draftThreshold = nil
                        if value != threshold { threshold = value }
                    }
                    .accessibilityIdentifier("duplicates.threshold")
                }
            }
        } header: {
            Text("匹配方式")
        } footer: {
            Text(fuzzy ? "还会把相似度达到阈值的标题、去掉括号副标题后相同的标题，以及自动关联到同一追踪站条目的作品算作重复。数字（季数、卷数）不同，或只是在另一个标题后追加内容（多为续作、外传）的不会模糊匹配。阈值越低结果越多，也越容易误判。"
                 : "标题或别名相同（忽略大小写、标点和繁简体），或在追踪站手动关联到同一条目的作品会当作重复。")
        }
        .onChange(of: fuzzy) { _, _ in Task { await load() } }
        .onChange(of: threshold) { _, _ in Task { await load() } }
    }

    private func header(_ group: IosDuplicateGroup) -> some View {
        HStack(spacing: 6) {
            Text("\(group.entries.count) 部 · \(group.entries.first?.content.title ?? "")")
            if group.fuzzy {
                Text("模糊").font(.caption2.weight(.semibold))
                    .padding(.horizontal, 6).padding(.vertical, 1)
                    .background(Color.orange.opacity(0.18), in: Capsule())
                    .foregroundStyle(.orange)
            }
        }
        .textCase(nil)
    }

    private func row(_ entry: IosDuplicateEntry, in group: IosDuplicateGroup) -> some View {
        let kept = keptId(group) == entry.id
        return Button {
            keep[group.key] = entry.id
            errors[group.key] = nil
        } label: {
            HStack(spacing: 12) {
                Image(systemName: kept ? "checkmark.circle.fill" : "circle")
                    .imageScale(.large)
                    .foregroundStyle(kept ? Color.accentColor : Color.secondary)
                if let runtime = sources.runtime {
                    CoverImage(runtime: runtime, content: entry.content)
                        .frame(width: 44, height: 66)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(entry.content.title).foregroundStyle(.primary).lineLimit(2)
                    HStack(spacing: 6) {
                        Text(sourceTitle(entry.content.sourceName))
                        if !entry.sourceAvailable {
                            Text("来源不可用").foregroundStyle(.red)
                        }
                    }
                    .font(.caption).foregroundStyle(.secondary)
                    Text(details(entry)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                if entry.id == group.recommendedId {
                    Text("推荐").font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.15), in: Capsule())
                        .foregroundStyle(Color.accentColor)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(kept ? .isSelected : [])
        .accessibilityIdentifier("duplicates.entry.\(entry.id)")
        .contextMenu {
            Button("查看详情", systemImage: "info.circle") { opened = entry.content }
        }
    }

    private func footer(_ group: IosDuplicateGroup) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if group.fuzzy {
                Text("标题只是相近，请确认是同一部作品再合并。").foregroundStyle(.orange)
            }
            if let error = errors[group.key] {
                Text(error).foregroundStyle(.red)
            }
            HStack(spacing: 10) {
                Button("不是重复") { ignore(group) }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("duplicates.ignore")
                Spacer()
                if merging.contains(group.key) { ProgressView() }
                Button("合并到选中作品") { Task { await merge(group) } }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("duplicates.merge")
            }
            .disabled(merging.contains(group.key))
            .font(.subheadline)
            .textCase(nil)
        }
        .padding(.top, 6)
    }

    private func keptId(_ group: IosDuplicateGroup) -> String { keep[group.key] ?? group.recommendedId }

    private func sourceTitle(_ name: String) -> String {
        sources.sources.first { $0.name == name }?.title ?? library.missingSources.first { $0.key == name }?.title ?? name
    }

    private func details(_ entry: IosDuplicateEntry) -> String {
        var parts = [String(localized: "\(entry.chapterCount) 话")]
        if let chapter = entry.readChapter {
            parts.append(String(localized: "读到第 \(chapter) 话"))
        } else if let progress = entry.progress?.floatValue {
            parts.append(String(localized: "已读 \(Int((progress * 100).rounded()))%"))
        } else {
            parts.append(String(localized: "未读"))
        }
        return parts.joined(separator: " · ")
    }

    // MARK: Actions

    private func load() async {
        guard let database = library.library else { return }
        generation += 1
        let request = generation
        // A changed setting shows the search running instead of the previous setting's groups.
        groups = nil
        loadError = nil
        do {
            let found = try await database.duplicates.groups(ignoredKeys: ignored, fuzzy: fuzzy, threshold: threshold)
            guard request == generation else { return }
            groups = found
            loadError = nil
            let keys = Set(found.map(\.key))
            keep = keep.filter { keys.contains($0.key) }
            errors = errors.filter { keys.contains($0.key) }
        } catch {
            if !Task.isCancelled, request == generation { loadError = SourceErrorText.describe(error) }
        }
    }

    private func ignore(_ group: IosDuplicateGroup) {
        ignoredRaw = (ignored + [group.key]).joined(separator: "\n")
        withAnimation { groups?.removeAll { $0.key == group.key } }
    }

    private func mergeAll() async {
        mergingAll = true
        defer { mergingAll = false }
        for group in groups ?? [] {
            if Task.isCancelled { break }
            await merge(group, refresh: false)
        }
        await library.refresh()
    }

    private func merge(_ group: IosDuplicateGroup, refresh: Bool = true) async {
        guard let database = library.library, !merging.contains(group.key),
              let entry = group.entries.first(where: { $0.id == keptId(group) }) else { return }
        merging.insert(group.key)
        errors[group.key] = nil
        defer { merging.remove(group.key) }
        do {
            // Moving a further reading position needs the kept work's chapters: saved ones, else from its source.
            var details: IosSourceContent?
            if database.duplicates.needsChapters(group: group, keep: entry) {
                details = try await database.storedDetails(content: entry.content)
                if details == nil, let runtime = sources.runtime {
                    details = try await runtime.details(content: entry.content)
                }
            }
            try await database.duplicates.merge(group: group, keep: entry, details: details)
            withAnimation { groups?.removeAll { $0.key == group.key } }
            mergedCount += 1
            if refresh { await library.refresh() }
        } catch {
            if !Task.isCancelled { errors[group.key] = String(localized: "合并失败：\(SourceErrorText.describe(error))") }
        }
    }
}
