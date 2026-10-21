import KototoroShared
import SwiftUI

/// Recommendations are a destination inside Browse, leaving the four main tabs at their current width.
struct SuggestionsView: View {
    var previewLimit: Int? = nil
    var previewKind: String? = nil
    var compact = false
    var selectedFeed: Binding<String> = .constant("personal")
    var collapsed: Binding<Bool> = .constant(false)
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @AppStorage("suggestions") private var enabled = false
    @AppStorage("suggestions_exclude_nsfw") private var excludeAdult = false
    @AppStorage("suggestions_preferred_tags") private var preferredTags = ""
    @AppStorage("suggestions_exclude_tags") private var excludedTags = ""
    @AppStorage("suggestions_preferred_sources") private var preferredSources = ""
    @AppStorage("suggestions_excluded_sources") private var excludedSources = ""
    @AppStorage("sources.showAdult") private var showAdult = false
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true
    @AppStorage("suggestions.kind") private var pageKind = ""
    @AppStorage("listMode.suggestions") private var mode = ContentListMode.grid
    @AppStorage("suggestions.updatedAt") private var updatedAt = 0.0
    @AppStorage("suggestions.configuration") private var savedConfiguration = ""
    @State private var items: [IosSourceContent] = []
    @State private var loading = false
    @State private var error: String?
    @State private var editing = false
    @State private var favouriteRequest: FavouriteCategoryRequest?
    @State private var request: UUID?
    @State private var cancellation: IosCancellation?

    private var candidates: [IosSource] {
        sources.sources.filter { $0.kind != "local" && sources.isEnabled($0) }
    }
    private var settings: IosSuggestionSettings {
        IosSuggestionSettings(excludeNsfw: !showAdult || excludeAdult, preferredTags: preferredTags,
                              excludedTags: excludedTags,
                              preferredSources: SourceFilterOption.decode(preferredSources).sorted(),
                              excludedSources: SourceFilterOption.decode(excludedSources).sorted())
    }
    private var configuration: String {
        // JSON avoids collisions between user-entered tags and source identities; the timestamp is persistent.
        let values = [String(!showAdult || excludeAdult), preferredTags, excludedTags, preferredSources, excludedSources]
            + candidates.map { "\($0.name)|\($0.contentType)" }.sorted()
        return String(decoding: (try? JSONEncoder().encode(values)) ?? Data(), as: UTF8.self)
    }
    private var taskIdentity: String {
        "\(enabled)|\(sources.isLoading)|\(configuration)|\(library.recordsRevision)|" +
            "\(library.library != nil)|\(collapsed.wrappedValue)"
    }
    private var hasSeed: Bool {
        !library.favourites.isEmpty || !library.history.isEmpty ||
            preferredTags.split(separator: ",").contains { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }
    private var availableKinds: Set<String> {
        let excludeNsfw = !showAdult || excludeAdult
        return Set(candidates.filter { !excludeNsfw || !$0.isAdult }.map(\.mediaKind))
    }
    private var selectedKind: String? {
        if previewLimit != nil { return previewKind }
        // A fixed space does not need to enumerate the source catalog to choose a media type.
        if spacesEnabled { return space.isEmpty ? nil : space }
        return ContentSpace.filter(enabled: spacesEnabled, space: space, page: pageKind, available: availableKinds)
    }
    private var visible: [ContentItem] {
        // Resolving a page's type can scan the source catalog; do it once for the whole list.
        let kind = selectedKind
        return items.filter { kind == nil || $0.mediaKind == kind }.map { ContentItem(content: $0) }
    }

    var body: some View {
        Group {
            if let previewLimit { preview(limit: previewLimit) }
            else { page }
        }
        .task(id: taskIdentity) { await load(force: false) }
        .onDisappear { cancellation?.cancel(); request = nil; loading = false }
        .sheet(isPresented: $editing) { SuggestionPreferencesView() }
        .sheet(item: $favouriteRequest) { request in FavouriteCategoriesView(contents: request.contents) }
    }

    private func preview(limit: Int) -> some View {
        let visible = collapsed.wrappedValue ? [] : self.visible
        return VStack(alignment: .leading, spacing: compact ? 8 : 12) {
            HStack {
                NavigationLink { SuggestionsView().navigationLinkIndicatorVisibility(.automatic) } label: {
                    HStack(spacing: 6) {
                        Text("为你推荐").font(.title3.bold())
                        Image(systemName: "chevron.right").font(.caption.bold()).foregroundStyle(.secondary)
                    }.foregroundStyle(.primary)
                }.accessibilityIdentifier("sources.suggestions")
                Spacer()
                if loading { ProgressView().controlSize(.small) }
                Menu {
                    Button { Task { await load(force: true) } } label: {
                        Label("刷新推荐", systemImage: "arrow.clockwise")
                    }
                    .disabled(loading || !enabled || !hasSeed || sources.isLoading || collapsed.wrappedValue)
                    .accessibilityIdentifier("recommendations.refresh")
                    Button { editing = true } label: { Label("推荐偏好", systemImage: "slider.horizontal.3") }
                        .accessibilityIdentifier("suggestions.shelf.settings")
                    if compact {
                        Divider()
                        BrowseRecommendationActions(selectedFeed: selectedFeed, collapsed: collapsed)
                    }
                } label: {
                    Label("推荐选项", systemImage: "ellipsis").labelStyle(.iconOnly)
                        .frame(minWidth: 32, minHeight: 32).contentShape(Rectangle())
                }.accessibilityIdentifier("recommendations.more")
            }
            if collapsed.wrappedValue {
                EmptyView()
            } else if !enabled {
                HStack {
                    Text("根据收藏与阅读偏好，发现更多作品").font(.subheadline).foregroundStyle(.secondary)
                    Spacer()
                    Button("开启推荐") { enabled = true }.buttonStyle(.bordered)
                }
            } else if !visible.isEmpty, let runtime = sources.runtime {
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(Array(visible.prefix(limit)), id: \.identity) { item in
                            NavigationLink { SourceDetailsView(runtime: runtime, content: item.content) } label: {
                                SuggestionFeaturedCard(runtime: runtime, content: item.content,
                                    sourceTitle: sources.sources.first { $0.name == item.content.sourceName }?.title ?? "",
                                    compact: compact)
                            }.buttonStyle(.plain).accessibilityIdentifier("suggestions.preview.\(item.id)")
                        }
                    }.scrollTargetLayout()
                }
                .scrollIndicators(.hidden).scrollTargetBehavior(.viewAligned)
                .accessibilityIdentifier("suggestions.shelf")
                if let error {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundStyle(.secondary)
                }
            } else if loading || sources.isLoading {
                ProgressView("正在寻找推荐作品…").frame(maxWidth: .infinity, minHeight: 70, alignment: .leading)
            } else {
                HStack {
                    Text(error ?? String(localized: hasSeed ? "当前空间暂无推荐作品" :
                        "收藏或阅读作品，或设置偏好标签"))
                        .font(.subheadline).foregroundStyle(.secondary)
                    Spacer()
                    Button(error == nil ? "设置偏好" : "重试") {
                        if error == nil { editing = true } else { Task { await load(force: true) } }
                    }.font(.subheadline)
                }
            }
        }.buttonStyle(.plain).navigationLinkIndicatorVisibility(.hidden)
    }

    private var page: some View {
        let visible = self.visible
        let settings = self.settings
        return ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if !spacesEnabled && ContentKindPicker.isShown(availableKinds) {
                    ContentKindPicker(selection: $pageKind, available: availableKinds).padding(.horizontal)
                }
                if !enabled {
                    ContentUnavailableView {
                        Label("为你推荐", systemImage: "sparkles")
                    } description: {
                        Text("根据收藏、阅读记录和偏好标签，从已启用的来源寻找作品。")
                    } actions: {
                        Button("开启推荐") { enabled = true }.buttonStyle(.borderedProminent)
                            .accessibilityIdentifier("suggestions.enable")
                    }
                } else if !hasSeed {
                    ContentUnavailableView {
                        Label("先告诉我们你喜欢什么", systemImage: "heart.text.clipboard")
                    } description: {
                        Text("收藏或阅读一些作品，也可以在推荐偏好中填写喜欢的标签。")
                    } actions: {
                        Button("设置偏好") { editing = true }.buttonStyle(.bordered)
                    }
                } else if candidates.filter({ !settings.excludedSources.contains($0.name) &&
                    (!settings.excludeNsfw || !$0.isAdult) }).isEmpty && !sources.isLoading {
                    ContentUnavailableView("没有可用来源", systemImage: "shippingbox",
                                           description: Text("请先在浏览页添加并启用来源，或调整推荐偏好。"))
                } else {
                    if loading {
                        HStack { ProgressView(); Text("正在寻找推荐作品…").font(.subheadline).foregroundStyle(.secondary) }
                            .padding(.horizontal).accessibilityIdentifier("suggestions.loading")
                    }
                    if let error {
                        VStack(alignment: .leading, spacing: 8) {
                            Label(error, systemImage: "exclamationmark.triangle").font(.footnote)
                            Button("重试") { Task { await load(force: true) } }.disabled(loading)
                                .accessibilityIdentifier("suggestions.retry")
                        }.padding(.horizontal).foregroundStyle(.secondary)
                    }
                    if !visible.isEmpty, let runtime = sources.runtime {
                        HStack {
                            Text("根据你的阅读偏好").font(.subheadline).foregroundStyle(.secondary)
                            Spacer()
                            if updatedAt > 0 {
                                Text(Date(timeIntervalSince1970: updatedAt), style: .relative)
                                    .font(.caption).foregroundStyle(.tertiary)
                            }
                        }.padding(.horizontal)
                        ContentCollection(runtime: runtime, items: visible, mode: mode,
                                          identifierPrefix: "suggestion.") { item in
                            SourceDetailsView(runtime: runtime, content: item.content)
                        } actions: { item in
                            Button(item.marks.favourite ? "编辑收藏分类" : "加入收藏", systemImage: "heart") {
                                favouriteRequest = FavouriteCategoryRequest(contents: [item.content])
                            }
                        }
                    } else if !loading && error == nil && !sources.isLoading {
                        ContentUnavailableView("暂无推荐作品", systemImage: "sparkles",
                                               description: Text("试试其他内容空间、调整偏好，或下拉刷新。"))
                    }
                }
            }.padding(.vertical, 16)
        }
        .navigationTitle("为你推荐")
        .navigationBarTitleDisplayMode(.inline)
        .spaceSubtitle(spacesEnabled ? ContentSpace.subtitle(space) : "")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if spacesEnabled { SpaceMenu() }
                ContentListModeMenu(mode: $mode)
                Button { editing = true } label: { Label("推荐偏好", systemImage: "slider.horizontal.3") }
                    .accessibilityIdentifier("suggestions.settings")
            }
        }
        .refreshable { await load(force: true) }
    }

    @MainActor
    private func load(force: Bool) async {
        let token = UUID()
        cancellation?.cancel()
        cancellation = nil
        request = token
        defer { if request == token { loading = false } }
        error = nil
        guard enabled, let database = library.library else { items = []; return }
        do {
            let cached = try await database.suggestions.items(sources: candidates, settings: settings)
            guard !Task.isCancelled, request == token else { return }
            items = cached
            guard hasSeed, !sources.isLoading, !collapsed.wrappedValue,
                  let runtime = sources.runtime else { return }
            let allowed = candidates.filter { !settings.excludedSources.contains($0.name) &&
                (!settings.excludeNsfw || !$0.isAdult) }
            guard !allowed.isEmpty else { return }
            let config = configuration
            let expired = Date().timeIntervalSince1970 - updatedAt > 6 * 60 * 60
            guard force || config != savedConfiguration || expired else { return }
            loading = true
            let cancellation = IosCancellation()
            self.cancellation = cancellation
            _ = try await withTaskCancellationHandler {
                try await database.suggestions.refresh(runtime: runtime, sources: allowed,
                                                       settings: settings, cancellation: cancellation)
            } onCancel: { cancellation.cancel() }
            guard !Task.isCancelled, request == token else { return }
            items = try await database.suggestions.items(sources: candidates, settings: settings)
            updatedAt = Date().timeIntervalSince1970
            savedConfiguration = config
        } catch {
            guard !Task.isCancelled, request == token else { return }
            self.error = error.localizedDescription
        }
    }
}

/// Personal recommendations are featured artwork cards; website charts use the denser poster shelf below.
private struct SuggestionFeaturedCard: View {
    let runtime: IosSources
    let content: IosSourceContent
    let sourceTitle: String
    var compact = false

    var body: some View {
        ZStack(alignment: .leading) {
            Color(.secondarySystemBackground)
            CoverImage(runtime: runtime, content: content)
                .frame(width: compact ? 272 : 304, height: compact ? 100 : 190).blur(radius: 18)
                .overlay(Color.black.opacity(0.6))
            HStack(alignment: .center, spacing: 14) {
                CoverImage(runtime: runtime, content: content)
                    .frame(width: compact ? 48 : 88, height: compact ? 72 : 132)
                    .clipShape(.rect(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 9) {
                    Text(content.title).font(compact ? .subheadline.bold() : .headline).lineLimit(compact ? 2 : 3)
                    if !compact && !content.tags.isEmpty {
                        Text(content.tags.prefix(3).joined(separator: " · "))
                            .font(.caption).foregroundStyle(.white.opacity(0.85)).lineLimit(2)
                    }
                    Text(sourceTitle).font(.caption2).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                }
                Spacer(minLength: 0)
            }.padding(compact ? 14 : 18).foregroundStyle(.white)
        }
        .frame(width: compact ? 272 : 304, height: compact ? 100 : 190)
        .clipShape(.rect(cornerRadius: compact ? 14 : 20))
        .accessibilityElement(children: .combine)
    }
}

/// A fixed poster footprint keeps both Browse feeds aligned while covers load or titles wrap.
struct RecommendationPoster<Cover: View>: View {
    let title: String
    let subtitle: String
    var compact = false
    @ViewBuilder let cover: () -> Cover

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Color.clear.frame(width: compact ? 64 : 112, height: compact ? 88 : 168)
                .overlay { cover().frame(width: compact ? 64 : 112, height: compact ? 88 : 168).clipped() }
                .clipShape(.rect(cornerRadius: compact ? 8 : 12))
            Text(title).font(compact ? .caption : .subheadline.weight(.medium)).foregroundStyle(.primary)
                .lineLimit(compact ? 1 : 2).frame(height: compact ? 16 : 40, alignment: .topLeading)
            if !compact { Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
        }
        .frame(width: compact ? 64 : 112, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// Edits a draft so changing several fields triggers one rebuild when the sheet is saved.
private struct SuggestionPreferencesView: View {
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.dismiss) private var dismiss
    @AppStorage("suggestions") private var enabled = false
    @AppStorage("suggestions_exclude_nsfw") private var excludeAdult = false
    @AppStorage("suggestions_preferred_tags") private var preferredTags = ""
    @AppStorage("suggestions_exclude_tags") private var excludedTags = ""
    @AppStorage("suggestions_preferred_sources") private var preferredSources = ""
    @AppStorage("suggestions_excluded_sources") private var excludedSources = ""
    @State private var draftEnabled = false
    @State private var draftExcludeAdult = false
    @State private var preferred = ""
    @State private var excluded = ""
    @State private var sourceChoices: [String: String] = [:]
    @State private var initialized = false

    private var eligible: [IosSource] {
        sources.sources.filter { $0.kind != "local" && sources.isEnabled($0) }
    }

    private func count(_ choice: String) -> Int {
        eligible.filter { sourceChoices[$0.name] == choice }.count
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("开启推荐", isOn: $draftEnabled).accessibilityIdentifier("suggestions.enabled")
                    Toggle("排除成人内容", isOn: $draftExcludeAdult)
                } footer: {
                    Text("进入推荐时先显示缓存，超过六小时或偏好、可用来源变化时重新生成。可在推荐页下拉刷新，或在浏览页推荐菜单中手动刷新；关闭后停止获取推荐。")
                }
                Section {
                    TextField("偏好标签", text: $preferred).accessibilityIdentifier("suggestions.preferredTags")
                    TextField("排除标签", text: $excluded).accessibilityIdentifier("suggestions.excludedTags")
                } header: { Text("标签偏好") } footer: { Text("使用来源中的标签名称，多个标签以英文逗号分隔。") }
                Section {
                    NavigationLink {
                        SuggestionSourcesView(choices: $sourceChoices)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("管理推荐来源")
                            Text("\(eligible.count) 个来源 · \(count("preferred")) 个优先 · \(count("excluded")) 个不推荐")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }.accessibilityIdentifier("suggestions.sources")
                } header: { Text("推荐来源") } footer: { Text("仅从已启用的在线来源生成推荐，并均衡不同来源的作品数量。") }
            }
            .navigationTitle("推荐偏好")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        enabled = draftEnabled; excludeAdult = draftExcludeAdult
                        preferredTags = preferred; excludedTags = excluded
                        let preferred = Set(sourceChoices.filter { $0.value == "preferred" }.keys)
                        let excluded = Set(sourceChoices.filter { $0.value == "excluded" }.keys)
                        preferredSources = SourceFilterOption.encode(preferred)
                        excludedSources = SourceFilterOption.encode(excluded)
                        dismiss()
                    }.accessibilityIdentifier("suggestions.save")
                }
            }
            .onAppear {
                guard !initialized else { return }
                initialized = true
                draftEnabled = enabled; draftExcludeAdult = excludeAdult
                preferred = preferredTags; excluded = excludedTags
                for name in SourceFilterOption.decode(preferredSources) { sourceChoices[name] = "preferred" }
                for name in SourceFilterOption.decode(excludedSources) { sourceChoices[name] = "excluded" }
            }
        }
    }
}

/// Filtering only changes what is shown; the parent keeps every source choice until Save or Cancel.
private struct SuggestionSourcesView: View {
    @Binding var choices: [String: String]
    @EnvironmentObject private var sources: SourcesModel
    @State private var query = ""
    @State private var mediaKind = ""
    @State private var ecosystem = ""
    @State private var language = ""
    @State private var choice = ""

    private var eligible: [IosSource] {
        sources.sources.filter { $0.kind != "local" && sources.isEnabled($0) }
    }
    private var filtered: [IosSource] {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return eligible.filter {
            (mediaKind.isEmpty || $0.mediaKind == mediaKind) &&
                (ecosystem.isEmpty || SourceFilterOption.ecosystemKey($0) == ecosystem) &&
                (language.isEmpty || languageKey($0) == language) &&
                (choice.isEmpty || (choices[$0.name] ?? "normal") == choice) &&
                (term.isEmpty || $0.title.localizedStandardContains(term) || $0.name.localizedStandardContains(term))
        }.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }
    private var ecosystemKeys: [String] {
        let keys = Set(eligible.map(SourceFilterOption.ecosystemKey))
        let known = SourcesModel.ecosystems.map(\.id).filter(keys.contains)
        return known + keys.subtracting(known).sorted()
    }
    private var languages: [String] {
        Set(eligible.map(languageKey)).sorted {
            SourceLanguage.title($0).localizedStandardCompare(SourceLanguage.title($1)) == .orderedAscending
        }
    }
    private var hasFilters: Bool {
        !mediaKind.isEmpty || !ecosystem.isEmpty || !language.isEmpty || !choice.isEmpty
    }

    var body: some View {
        let matches = filtered
        List {
            Section {
                HStack {
                    Text("\(matches.count) / \(eligible.count) 个来源")
                        .font(.subheadline).foregroundStyle(.secondary)
                        .accessibilityIdentifier("suggestions.sources.count")
                    Spacer()
                    filtersMenu
                }
                if hasFilters {
                    Button("清除筛选") { clearFilters() }
                        .accessibilityIdentifier("suggestions.sources.clearFilters")
                }
            } footer: {
                Text("管理所有空间中已启用的在线来源。搜索与筛选不会改变已选偏好，返回后点击“保存”生效。")
            }
            ForEach(ecosystemKeys, id: \.self) { key in
                let group = matches.filter { SourceFilterOption.ecosystemKey($0) == key }
                if !group.isEmpty {
                    Section(ecosystemTitle(key)) {
                        ForEach(group, id: \.name) { source in sourceRow(source) }
                    }
                }
            }
        }
        .overlay {
            if matches.isEmpty {
                ContentUnavailableView("没有匹配的来源", systemImage: "magnifyingglass",
                    description: Text("尝试其他关键词或清除筛选；新来源需先在浏览页启用。"))
                    .allowsHitTesting(false)
            }
        }
        .navigationTitle("推荐来源")
        .navigationBarTitleDisplayMode(.inline)
        .pageSearch(text: $query, prompt: String(localized: "搜索来源名称或标识"))
    }

    // The navigation toolbar hides during search, so keep filters in the results header.
    private var filtersMenu: some View {
        Menu {
            Picker("内容类型", selection: $mediaKind) {
                Text("全部类型").tag("")
                ForEach(SourceFilterOption.kinds, id: \.id) { Text($0.title).tag($0.id) }
            }.pickerStyle(.menu)
            Picker("来源类型", selection: $ecosystem) {
                Text("全部来源类型").tag("")
                ForEach(ecosystemKeys, id: \.self) { Text(ecosystemTitle($0)).tag($0) }
            }.pickerStyle(.menu)
            Picker("语言", selection: $language) {
                Text("全部语言").tag("")
                ForEach(languages, id: \.self) { Text(SourceLanguage.title($0)).tag($0) }
            }.pickerStyle(.menu)
            Picker("推荐状态", selection: $choice) {
                Text("全部状态").tag("")
                choiceOptions
            }.pickerStyle(.menu)
            if hasFilters { Button("清除筛选") { clearFilters() } }
        } label: {
            Label("筛选", systemImage: hasFilters ?
                "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease")
        }
        .buttonStyle(.borderless)
        .accessibilityIdentifier("suggestions.sources.filter")
    }

    private func sourceRow(_ source: IosSource) -> some View {
        Picker(selection: Binding(get: { choices[source.name] ?? "normal" }, set: { choices[source.name] = $0 })) {
            choiceOptions
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(source.title).lineLimit(2)
                Text("\(ContentSpace.title(source.mediaKind)) · \(SourceLanguage.title(source.locale))")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .pickerStyle(.menu)
        .accessibilityIdentifier("suggestions.source.\(source.name)")
    }

    private var choiceOptions: some View {
        Group {
            Text("正常推荐").tag("normal")
            Text("优先推荐").tag("preferred")
            Text("不推荐").tag("excluded")
        }
    }

    private func ecosystemTitle(_ key: String) -> String {
        SourcesModel.ecosystems.first { $0.id == key }?.title ?? key
    }

    private func languageKey(_ source: IosSource) -> String {
        source.locale.isEmpty ? "all" : source.locale
    }

    private func clearFilters() {
        mediaKind = ""; ecosystem = ""; language = ""; choice = ""
    }
}
