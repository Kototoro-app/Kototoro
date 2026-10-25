import KototoroShared
import SwiftUI

/// Android's saved filters: named filter selections per source, one of which may apply itself when the source
/// opens. Kept by exact name (Android keys them by the name's hash, so two names can collide); restoring always goes
/// through `BrowseFilter.reconciled(with:)`, so tags or extension controls the source no longer offers are dropped.
struct SavedBrowseFilter: Codable, Identifiable, Equatable {
    var name: String
    var autoEnabled = false
    var order: String?
    var included: [String] = []
    var excluded: [String] = []
    var states: [String] = []
    var ratings: [String] = []
    var types: [String] = []
    var demographics: [String] = []
    var locale: String?
    var originalLocale: String?
    var year = 0
    var yearFrom = 0
    var yearTo = 0
    var author = ""
    var dynamic: [String: StoredValue] = [:]

    var id: String { name }

    struct StoredValue: Codable, Equatable {
        var toggle: Bool, tri: Int32, index: Int32, ascending: Bool, text: String
        init(_ value: IosFilterValue) {
            toggle = value.toggle; tri = value.tri; index = value.index; ascending = value.ascending; text = value.text
        }
        var value: IosFilterValue { IosFilterValue(toggle: toggle, tri: tri, index: index, ascending: ascending, text: text) }
    }

    init(name: String, filter: BrowseFilter) {
        self.name = name
        order = filter.order
        included = filter.included.sorted(); excluded = filter.excluded.sorted(); states = filter.states.sorted()
        ratings = filter.ratings.sorted(); types = filter.types.sorted(); demographics = filter.demographics.sorted()
        locale = filter.locale; originalLocale = filter.originalLocale
        year = filter.year; yearFrom = filter.yearFrom; yearTo = filter.yearTo; author = filter.author
        dynamic = filter.dynamic.mapValues(StoredValue.init)
    }

    var filter: BrowseFilter {
        var filter = BrowseFilter()
        filter.order = order
        filter.included = Set(included); filter.excluded = Set(excluded); filter.states = Set(states)
        filter.ratings = Set(ratings); filter.types = Set(types); filter.demographics = Set(demographics)
        filter.locale = locale; filter.originalLocale = originalLocale
        filter.year = year; filter.yearFrom = yearFrom; filter.yearTo = yearTo; filter.author = author
        filter.dynamic = dynamic.mapValues(\.value)
        return filter
    }
}

enum SavedFilterStore {
    private static func key(_ source: String) -> String { "savedFilters.\(source)" }

    static func all(source: String) -> [SavedBrowseFilter] {
        guard let data = UserDefaults.standard.data(forKey: key(source)),
              let filters = try? JSONDecoder().decode([SavedBrowseFilter].self, from: data) else { return [] }
        return filters
    }

    private static func store(_ filters: [SavedBrowseFilter], source: String) {
        if let data = try? JSONEncoder().encode(filters) { UserDefaults.standard.set(data, forKey: key(source)) }
    }

    /// Saves [filter] as [name], replacing a filter of that name (and keeping its auto-apply choice).
    static func save(_ filter: BrowseFilter, name: String, source: String) {
        var filters = all(source: source)
        var saved = SavedBrowseFilter(name: name, filter: filter)
        if let index = filters.firstIndex(where: { $0.name == name }) {
            saved.autoEnabled = filters[index].autoEnabled
            filters[index] = saved
        } else {
            filters.append(saved)
        }
        store(filters, source: source)
    }

    static func rename(_ name: String, to newName: String, source: String) {
        var filters = all(source: source)
        guard name != newName, filters.contains(where: { $0.name == name }) else { return }
        filters.removeAll { $0.name == newName }
        for index in filters.indices where filters[index].name == name { filters[index].name = newName }
        store(filters, source: source)
    }

    static func delete(_ name: String, source: String) {
        store(all(source: source).filter { $0.name != name }, source: source)
    }

    /// At most one filter applies itself when the source opens.
    static func setAuto(_ name: String?, source: String) {
        store(all(source: source).map { var filter = $0; filter.autoEnabled = $0.name == name; return filter },
              source: source)
    }

    static func auto(source: String) -> SavedBrowseFilter? { all(source: source).first(where: \.autoEnabled) }
}

/// The saved filters at the top of the filter sheet: tap to load one into the sheet, long press to rename, delete
/// or make it apply itself; and saving the sheet's current selection under a name.
struct SavedFiltersSection: View {
    let source: String
    let panel: IosFilterPanel
    @Binding var draft: BrowseFilter
    @State private var saved: [SavedBrowseFilter] = []
    @State private var naming = false
    @State private var renaming: SavedBrowseFilter?
    @State private var name = ""

    var body: some View {
        Section {
            ForEach(saved) { filter in
                let current = filter.filter.reconciled(with: panel) == draft
                Button { draft = filter.filter.reconciled(with: panel) } label: {
                    HStack {
                        Label(filter.name, systemImage: filter.autoEnabled ? "bolt.fill" : "line.3.horizontal.decrease")
                            .foregroundStyle(.primary)
                        Spacer()
                        if current { Image(systemName: "checkmark").foregroundStyle(.tint).fontWeight(.semibold) }
                    }
                    .contentShape(Rectangle())
                }
                .accessibilityIdentifier("filter.saved.\(filter.name)")
                .accessibilityValue(current ? String(localized: "已应用") : "")
                .swipeActions {
                    Button("删除", role: .destructive) { SavedFilterStore.delete(filter.name, source: source); reload() }
                    Button("重命名") { name = filter.name; renaming = filter }.tint(.orange)
                }
                .contextMenu {
                    Button(filter.autoEnabled ? "不再自动应用" : "打开来源时自动应用", systemImage: "bolt") {
                        SavedFilterStore.setAuto(filter.autoEnabled ? nil : filter.name, source: source); reload()
                    }
                    Button("重命名", systemImage: "pencil") { name = filter.name; renaming = filter }
                    Button("删除", systemImage: "trash", role: .destructive) {
                        SavedFilterStore.delete(filter.name, source: source); reload()
                    }
                }
            }
            Button("保存当前筛选", systemImage: "square.and.arrow.down") { name = ""; naming = true }
                .disabled(!draft.isActive(panel))
                .accessibilityIdentifier("filter.saveCurrent")
                // On the row itself: alerts attached to a Form section may never be presented.
                .alert("保存筛选", isPresented: $naming) {
                    TextField("名称", text: $name).accessibilityIdentifier("filter.saveName")
                    Button("保存") {
                        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty else { return }
                        SavedFilterStore.save(draft, name: String(trimmed.prefix(24)), source: source); reload()
                    }
                    Button("取消", role: .cancel) {}
                }
                .alert("重命名", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                    TextField("名称", text: $name)
                    Button("保存") {
                        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        if let filter = renaming, !trimmed.isEmpty {
                            SavedFilterStore.rename(filter.name, to: String(trimmed.prefix(24)), source: source); reload()
                        }
                        renaming = nil
                    }
                    Button("取消", role: .cancel) { renaming = nil }
                }
        } header: {
            Text("已保存的筛选")
        } footer: {
            if !saved.isEmpty { Text("左滑可重命名或删除；长按可设为打开来源时自动应用。") }
        }
        .onAppear(perform: reload)
    }

    private func reload() { saved = SavedFilterStore.all(source: source) }
}
