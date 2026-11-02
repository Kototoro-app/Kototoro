import KototoroShared
import SwiftUI

/// Android's global tag blacklist and sensitive-category highlight. The selection keeps Android's stored form
/// (catalog IDs or `raw:` keys under the same key), matched by the shared rule in core-domain.
@MainActor
final class TagFilterStore: ObservableObject {
    static let shared = TagFilterStore()
    static let blacklistKey = "global_tag_blacklist"
    /// Android's "highlight sensitive categories" switch, on by default.
    static let warningsKey = "tags_warnings"

    @Published private(set) var selected: Set<String>
    private(set) var blacklist: IosTagBlacklist

    private init() {
        let stored = Set(UserDefaults.standard.stringArray(forKey: Self.blacklistKey) ?? [])
        selected = stored
        blacklist = IosTags.shared.blacklist(selected: Array(stored))
    }

    static var language: String { Locale.current.language.languageCode?.identifier == "zh" ? "zh" : "en" }

    func hides(_ content: IosSourceContent) -> Bool {
        !blacklist.isEmpty && blacklist.hides(titles: content.tags)
    }

    func contains(_ id: String) -> Bool { selected.contains(id) }

    func toggle(_ id: String) {
        var next = selected
        if next.remove(id) == nil { next.insert(id) }
        save(next)
    }

    /// Hides works carrying the tag [title]: its catalog tag, or the title itself when the catalog does not know it.
    func add(title: String) {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        save(selected.union([IosTags.shared.selection(title: trimmed)]))
    }

    func clear() { save([]) }

    private func save(_ next: Set<String>) {
        guard next != selected else { return }
        UserDefaults.standard.set(next.sorted(), forKey: Self.blacklistKey)
        blacklist = IosTags.shared.blacklist(selected: Array(next))
        selected = next
    }
}

/// Android's tag blacklist page: the catalog by category with search, tap to hide works with a tag, long press for
/// its other names; titles the catalog does not know can be added as they are.
struct TagBlacklistView: View {
    @ObservedObject private var store = TagFilterStore.shared
    @AppStorage(TagFilterStore.warningsKey) private var warnings = true
    @State private var query = ""
    @State private var categories: [IosTagCategory] = []
    @State private var confirmClear = false

    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var shown: [(IosTagCategory, [IosTag])] {
        categories.compactMap { category in
            let tags = trimmed.isEmpty ? category.tags
                : category.tags.filter { IosTags.shared.matches(id: $0.id, query: trimmed) }
            return tags.isEmpty ? nil : (category, tags)
        }
    }

    /// Selections outside the catalog (raw keys and aliases), listed so they can be removed.
    private var custom: [String] {
        let known = Set(categories.flatMap { $0.tags.map(\.id) })
        return store.selected.filter { !known.contains($0) }.sorted()
    }

    var body: some View {
        List {
            Section {
                Toggle("高亮敏感类别", isOn: $warnings)
                    .accessibilityIdentifier("tags.warnings")
            } footer: {
                Text("详情页中属于内容提示类的标签以醒目颜色显示。")
            }
            Section {
                LabeledContent("已屏蔽", value: "\(store.selected.count)")
                    .accessibilityIdentifier("tags.blacklist.count")
                if !trimmed.isEmpty, !shown.contains(where: { $0.1.contains {
                    $0.label.caseInsensitiveCompare(trimmed) == .orderedSame } }) {
                    Button("屏蔽“\(trimmed)”", systemImage: "plus.circle") {
                        store.add(title: trimmed)
                        query = ""
                    }
                    .accessibilityIdentifier("tags.blacklist.addRaw")
                }
            } footer: {
                Text("带有所选标签的作品不会出现在浏览、搜索、推荐和作品库中；来源中的同义标签会一并匹配。")
            }
            if !custom.isEmpty {
                Section("自定义") {
                    ForEach(custom, id: \.self) { id in
                        Text(IosTags.shared.label(selected: id, language: TagFilterStore.language))
                            .swipeActions { Button("移除", role: .destructive) { store.toggle(id) } }
                    }
                }
            }
            ForEach(shown, id: \.0.id) { category, tags in
                Section(category.label) {
                    ForEach(tags, id: \.id) { tag in
                        CheckRow(title: tag.label, selected: store.contains(tag.id)) { store.toggle(tag.id) }
                            .foregroundStyle(tag.warning ? Color.orange : Color.primary)
                            .accessibilityIdentifier("tags.blacklist.\(tag.id)")
                            // Other names and source aliases, as Android shows them on a long press.
                            .contextMenu {
                                Text(tag.secondaryLabel)
                                if !tag.aliases.isEmpty { Text(tag.aliases.prefix(12).joined(separator: "、")) }
                            }
                    }
                }
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索标签")
        .navigationTitle("标签黑名单")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("清空", role: .destructive) { confirmClear = true }.disabled(store.selected.isEmpty)
            }
        }
        .confirmationDialog("清空标签黑名单？", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("清空", role: .destructive) { store.clear() }
        }
        .task { categories = IosTags.shared.categories(language: TagFilterStore.language) }
    }
}
