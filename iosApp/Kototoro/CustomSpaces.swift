import KototoroShared
import SwiftUI

/// Android's custom Spaces (shared `space_definitions` table and rule). The app-wide `space.kind` holds a custom
/// Space's id; pages that filter by media kind use the Space's kind, and source/work lists also apply its language
/// and source-kind rule.
@MainActor
final class CustomSpaceStore: ObservableObject {
    static let shared = CustomSpaceStore()
    @Published private(set) var spaces: [IosCustomSpace] = []
    private weak var library: IosLibrary?

    func load(_ library: IosLibrary?) async {
        self.library = library
        spaces = (try? await library?.spaces.all()) ?? []
    }

    func reload() async { await load(library) }

    func space(_ id: String) -> IosCustomSpace? { spaces.first { $0.id == id } }

    var enabledSpaces: [IosCustomSpace] { spaces.filter(\.enabled) }

    var database: IosLibrary? { library }
}

extension ContentSpace {
    static func isCustom(_ space: String) -> Bool { space.hasPrefix("custom:") }

    /// The media kind pages filter by: the built-in kind, a custom Space's single kind, or nil for all kinds.
    @MainActor
    static func kind(_ space: String) -> String? {
        guard !space.isEmpty else { return nil }
        guard isCustom(space) else { return space }
        return CustomSpaceStore.shared.space(space)?.mediaKind
    }

    /// Whether the current custom Space admits [source]; built-in Spaces admit every source of their kind.
    @MainActor
    static func admits(_ source: IosSource) -> Bool {
        let space = UserDefaults.standard.string(forKey: key) ?? ""
        guard UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true, isCustom(space),
              let custom = CustomSpaceStore.shared.space(space) else { return true }
        return custom.admits(source: source)
    }

    /// Whether the current custom Space admits a work, by its source; works of unknown sources stay visible.
    @MainActor
    static func admits(_ content: IosSourceContent, sources: [IosSource]) -> Bool {
        let space = UserDefaults.standard.string(forKey: key) ?? ""
        guard isCustom(space), let source = sources.first(where: { $0.name == content.sourceName }) else { return true }
        return admits(source)
    }
}

/// Managing custom Spaces: list, add, edit, enable and delete (built-ins stay as they are).
struct CustomSpacesView: View {
    @ObservedObject private var store = CustomSpaceStore.shared
    @AppStorage(ContentSpace.key) private var current = ""
    @State private var editing: EditedSpace?
    @State private var message: String?

    struct EditedSpace: Identifiable {
        let id = UUID()
        let space: IosCustomSpace?
    }

    var body: some View {
        List {
            Section {
                ForEach(store.spaces, id: \.id) { space in
                    Button { editing = EditedSpace(space: space) } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            HStack {
                                Text(space.title).foregroundStyle(space.enabled ? Color.primary : .secondary)
                                if !space.enabled { Text("已停用").font(.caption).foregroundStyle(.secondary) }
                            }
                            Text(CustomSpaceText.summary(space)).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                    }
                    .accessibilityIdentifier("spaces.custom.\(space.title)")
                    .swipeActions {
                        Button("删除", role: .destructive) {
                            Task {
                                try? await store.database?.spaces.delete(space: space)
                                if current == space.id { current = "" }
                                await store.reload()
                            }
                        }
                    }
                }
                Button("添加空间", systemImage: "plus") { editing = EditedSpace(space: nil) }
                    .disabled(store.spaces.count >= 12)
                    .accessibilityIdentifier("spaces.add")
            } footer: {
                Text("自定义空间按内容类型、来源语言与来源类型划定范围（各项之间为“且”，同一项内为“或”，留空不限），与 Android 共用同一份数据。")
            }
        }
        .navigationTitle("自定义空间")
        .sheet(item: $editing) { edited in CustomSpaceEditor(space: edited.space) }
        .task { await store.reload() }
    }
}

enum CustomSpaceText {
    static let types: [(id: String, title: String)] = [
        ("MANGA", String(localized: "漫画")), ("MANHWA", String(localized: "韩漫")), ("MANHUA", String(localized: "国漫")),
        ("COMICS", String(localized: "美漫")), ("NOVEL", String(localized: "小说")), ("VIDEO", String(localized: "视频")),
    ]
    static let kinds: [(id: String, title: String)] = [
        ("MIHON", "Mihon"), ("ANIYOMI", "Aniyomi"), ("TSUNDOKU", "Tsundoku"), ("EXTERNAL", String(localized: "JAR 解析器")),
        ("JSON_TVBOX", String(localized: "影视站点")), ("NATIVE", String(localized: "本地")),
    ]

    static func summary(_ space: IosCustomSpace) -> String {
        let types = space.contentTypes.map { id in Self.types.first { $0.id == id }?.title ?? id }.joined(separator: "、")
        let languages = space.languages.isEmpty ? String(localized: "全部语言") : space.languages.map(SourceLanguage.title).joined(separator: "、")
        let kinds = space.sourceKinds.isEmpty ? String(localized: "全部来源") : space.sourceKinds.map { id in Self.kinds.first { $0.id == id }?.title ?? id }.joined(separator: "、")
        return "\(types) · \(languages) · \(kinds)"
    }
}

private struct CustomSpaceEditor: View {
    let space: IosCustomSpace?
    @ObservedObject private var store = CustomSpaceStore.shared
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var types: Set<String> = []
    @State private var languages: Set<String> = []
    @State private var kinds: Set<String> = []
    @State private var enabled = true

    private var availableLanguages: [String] {
        Set(sources.sources.map { $0.locale.lowercased().split(separator: "-").first.map(String.init) ?? "" })
            .filter { !$0.isEmpty }.sorted()
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("名称") {
                    TextField("例如：日漫", text: $title).accessibilityIdentifier("spaces.title")
                    if space != nil { Toggle("启用", isOn: $enabled) }
                }
                Section("内容类型（至少一项）") { checkRows(CustomSpaceText.types, selection: $types, prefix: "type") }
                Section {
                    NavigationLink {
                        LanguagePicker(languages: availableLanguages, selection: $languages)
                    } label: {
                        LabeledContent("来源语言", value: languages.isEmpty ? String(localized: "不限")
                                       : languages.sorted().map(SourceLanguage.title).joined(separator: "、"))
                    }
                    .accessibilityIdentifier("spaces.languages")
                }
                Section("来源类型（留空不限）") { checkRows(CustomSpaceText.kinds, selection: $kinds, prefix: "kind") }
            }
            .navigationTitle(space == nil ? "添加空间" : "编辑空间")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { Task { await save() } }
                        .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || types.isEmpty)
                        .accessibilityIdentifier("spaces.save")
                }
            }
        }
        .onAppear {
            guard let space else { return }
            title = space.title; types = Set(space.contentTypes); languages = Set(space.languages)
            kinds = Set(space.sourceKinds); enabled = space.enabled
        }
    }

    private func checkRows(_ options: [(id: String, title: String)], selection: Binding<Set<String>>, prefix: String) -> some View {
        ForEach(options, id: \.id) { option in
            CheckRow(title: option.title, selected: selection.wrappedValue.contains(option.id)) {
                if selection.wrappedValue.contains(option.id) { selection.wrappedValue.remove(option.id) }
                else { selection.wrappedValue.insert(option.id) }
            }
            .accessibilityIdentifier("spaces.\(prefix).\(option.id)")
        }
    }

    private func save() async {
        guard let spaces = store.database?.spaces else { return }
        // Android's types include the adult variants of what is chosen, as its built-in Spaces do.
        let stored = types.flatMap { ["MANGA", "NOVEL", "VIDEO"].contains($0) ? [$0, "HENTAI_" + $0] : [$0] }
        if let space {
            try? await spaces.update(space: space, title: title, contentTypes: stored, languages: Array(languages),
                                     sourceKinds: Array(kinds), enabled: enabled)
        } else {
            _ = try? await spaces.create(title: title, contentTypes: stored, languages: Array(languages), sourceKinds: Array(kinds))
        }
        await store.reload()
        dismiss()
    }
}

/// Source languages for a Space, searchable, chosen with checkmarks.
private struct LanguagePicker: View {
    let languages: [String]
    @Binding var selection: Set<String>
    @State private var query = ""

    var body: some View {
        List {
            Section {
                ForEach(languages.filter { query.isEmpty || SourceLanguage.title($0).localizedCaseInsensitiveContains(query) },
                        id: \.self) { code in
                    CheckRow(title: SourceLanguage.title(code), selected: selection.contains(code)) {
                        if selection.contains(code) { selection.remove(code) } else { selection.insert(code) }
                    }
                }
            } footer: { Text("不选即不限语言。") }
        }
        .searchable(text: $query, prompt: "搜索语言")
        .navigationTitle("来源语言")
    }
}
