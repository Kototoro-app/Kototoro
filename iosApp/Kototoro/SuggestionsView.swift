import KototoroShared
import SwiftUI

struct SuggestionsView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @AppStorage("suggestions") private var enabled = false
    @AppStorage("suggestions_exclude_nsfw") private var excludeAdult = false
    @AppStorage("suggestions_language") private var recommendationLanguage = "SYSTEM"
    @AppStorage("suggestions_language_only") private var recommendationLanguageOnly = false
    @AppStorage("suggestions_preferred_tags") private var preferredTags = ""
    @AppStorage("suggestions_exclude_tags") private var excludedTags = ""
    @AppStorage("suggestions_preferred_sources") private var preferredSources = ""
    @AppStorage("suggestions_excluded_sources") private var excludedSources = ""
    @AppStorage("sources.showAdult") private var showAdult = false
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true
    @AppStorage("suggestions.kind") private var pageKind = ""
    @AppStorage("community_enabled") private var communityEnabled = false
    @AppStorage("listMode.suggestions") private var mode = ContentListMode.grid
    @AppStorage("list.gridSize.suggestions") private var gridSize = ContentGridSize.legacyDefault
    @AppStorage("suggestions.updatedAt") private var updatedAt = 0.0
    @AppStorage("suggestions.configuration") private var savedConfiguration = ""
    @State private var items: [IosSourceContent] = []
    @State private var loading = false
    @State private var error: String?
    @State private var editing = false
    @State private var favouriteRequest: FavouriteCategoryRequest?
    @State private var request: UUID?
    @State private var cancellation: IosCancellation?
    @State private var direction = "ALL"

    private var candidates: [IosSource] {
        sources.sources.filter { $0.kind != "local" && sources.isEnabled($0) }
    }
    private var settings: IosSuggestionSettings {
        IosSuggestionSettings(excludeNsfw: !showAdult || excludeAdult, preferredTags: preferredTags,
                              excludedTags: excludedTags,
                              preferredSources: SourceFilterOption.decode(preferredSources).sorted(),
                              excludedSources: SourceFilterOption.decode(excludedSources).sorted(),
                              language: recommendationLanguage, languageOnly: recommendationLanguageOnly,
                              systemLanguage: Locale.current.language.languageCode?.identifier ?? "en", communityEnabled: communityEnabled)
    }
    private var configuration: String {
        // JSON avoids collisions between user-entered tags and source identities; the timestamp is persistent.
        let values = [String(!showAdult || excludeAdult), preferredTags, excludedTags, preferredSources, excludedSources,
                      recommendationLanguage, String(recommendationLanguageOnly), String(communityEnabled)]
            + candidates.map { "\($0.name)|\($0.contentType)" }.sorted()
        return String(decoding: (try? JSONEncoder().encode(values)) ?? Data(), as: UTF8.self)
    }
    private var taskIdentity: String {
        "\(enabled)|\(sources.isLoading)|\(configuration)|\(library.recordsRevision)|" +
            "\(library.library != nil)"
    }
    private var availableKinds: Set<String> {
        let excludeNsfw = !showAdult || excludeAdult
        return Set(candidates.filter { !excludeNsfw || !$0.isAdult }.map(\.mediaKind))
    }
    private var selectedKind: String? {
        // A fixed space does not need to enumerate the source catalog to choose a media type.
        if spacesEnabled { return ContentSpace.kind(space) }
        return ContentSpace.filter(enabled: spacesEnabled, space: space, page: pageKind, available: availableKinds)
    }
    private var visible: [ContentItem] {
        // Resolving a page's type can scan the source catalog; do it once for the whole list.
        let kind = selectedKind
        let selected = direction == "COMMUNITY" && !items.contains(where: { $0.suggestionDirection == "COMMUNITY" })
            ? "ALL" : direction
        return items.filter {
            (kind == nil || $0.mediaKind == kind) &&
                (selected == "ALL" || $0.suggestionDirection == selected)
        }.map { content in
            let reason = content.suggestionDirection == "COMMUNITY"
                ? content.suggestionReason.map { String(localized: "因为你喜欢《\($0)》") } ?? String(localized: "社区推荐")
                : nil
            return ContentItem(content: content, subtitle: reason)
        }
    }

    var body: some View {
        page
            .task(id: taskIdentity) { await load(force: false) }
            .onChange(of: communityEnabled) { if !communityEnabled && direction == "COMMUNITY" { direction = "ALL" } }
            .onDisappear { cancellation?.cancel(); request = nil; loading = false }
            .sheet(isPresented: $editing) { SuggestionPreferencesView() }
            .sheet(item: $favouriteRequest) { request in FavouriteCategoriesView(contents: request.contents) }
    }

    private var page: some View {
        let visible = self.visible
        let settings = self.settings
        return ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                ScrollView(.horizontal) {
                    HStack {
                        directionButton("ALL", title: "为你推荐")
                        directionButton("TASTE", title: "常读题材")
                        directionButton("POPULAR", title: "热门榜")
                        directionButton("NEW", title: "新作与更新")
                        directionButton("EXPLORE", title: "换换口味")
                        if communityEnabled && items.contains(where: { $0.suggestionDirection == "COMMUNITY" }) {
                            directionButton("COMMUNITY", title: "社区")
                        }
                    }.padding(.horizontal)
                }.scrollIndicators(.hidden)
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
                                          identifierPrefix: "suggestion.", gridSize: gridSize) { item in
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
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                ContentListModeMenu(mode: $mode, gridSize: $gridSize)
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
            guard !sources.isLoading,
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
                                                       settings: settings, cancellation: cancellation, manual: force)
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

    private func directionButton(_ value: String, title: LocalizedStringKey) -> some View {
        Button { direction = value } label: { Text(title) }
            .buttonStyle(.bordered)
            .tint(direction == value ? .accentColor : .secondary)
            .accessibilityAddTraits(direction == value ? .isSelected : [])
            .accessibilityIdentifier("suggestion-direction:\(value)")
    }
}

/// Edits a draft so changing several fields triggers one rebuild when the sheet is saved.
private struct SuggestionPreferencesView: View {
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.dismiss) private var dismiss
    @AppStorage("suggestions") private var enabled = false
    @AppStorage("community_enabled") private var communityEnabled = false
    @AppStorage("suggestions_exclude_nsfw") private var excludeAdult = false
    @AppStorage("suggestions_language") private var recommendationLanguage = "SYSTEM"
    @AppStorage("suggestions_language_only") private var recommendationLanguageOnly = false
    @AppStorage("suggestions_preferred_tags") private var preferredTags = ""
    @AppStorage("suggestions_exclude_tags") private var excludedTags = ""
    @AppStorage("suggestions_preferred_sources") private var preferredSources = ""
    @AppStorage("suggestions_excluded_sources") private var excludedSources = ""
    @State private var draftCommunity = false
    @State private var draftEnabled = false
    @State private var draftExcludeAdult = false
    @State private var draftLanguage = "SYSTEM"
    @State private var draftLanguageOnly = false
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
                    Toggle("加入社区推荐", isOn: $draftCommunity).accessibilityIdentifier("suggestions.community")
                } footer: {
                    Text("进入推荐时先显示缓存，超过六小时或偏好、可用来源变化时重新生成。可在推荐页下拉刷新。社区推荐读取公开索引，书库匹配在本机完成；匹配来源时仅搜索推荐作品标题。")
                }
                Section {
                    Picker("优先语言", selection: $draftLanguage) {
                        Text("系统语言").tag("SYSTEM")
                        Text("中文").tag("CHINESE")
                        Text("英语").tag("ENGLISH")
                        Text("日语").tag("JAPANESE")
                        Text("不设优先").tag("ALL")
                    }.accessibilityIdentifier("suggestions.language")
                    Toggle("仅显示所选语言", isOn: $draftLanguageOnly).disabled(draftLanguage == "ALL")
                        .accessibilityIdentifier("suggestions.languageOnly")
                } header: { Text("推荐语言") } footer: {
                    Text("默认混合推荐，优先系统语言；也可仅显示所选语言。按来源声明的语言判断。")
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
                        communityEnabled = draftCommunity
                        recommendationLanguage = draftLanguage
                        recommendationLanguageOnly = draftLanguageOnly && draftLanguage != "ALL"
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
                draftCommunity = communityEnabled
                draftLanguage = recommendationLanguage; draftLanguageOnly = recommendationLanguageOnly
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
