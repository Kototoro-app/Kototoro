import KototoroShared
import SwiftUI

/// Short names for what provides a source, as the picker's chips and subtitles show them.
enum SourceProvider {
    /// The providers the picker offers, in the order `SourcesModel` groups the catalog by. Short names match
    /// Android's `source_type_*` labels, so the chips stay one row wide.
    static let order = ["MIHON", "TSUNDOKU", "ANIYOMI", "JAR", "TVBOX", "CMS"]

    static func title(_ key: String) -> String {
        switch key {
        case "LOCAL": return String(localized: "本地")
        case "CMS": return "CMS"
        default: return key.isEmpty ? String(localized: "其他") : key.capitalized
        }
    }
}

/// The source picker behind the details page's change-source search and the batch migration's search sources.
/// Android's unified source screen narrows a long catalog by content type and by what provides each source
/// (Mihon, Tsundoku, Aniyomi, JAR, Apple CMS / TVBox), so the same two chip rows sit above a searchable list,
/// and both selection controls only touch what the filters leave visible.
struct SourceSelectionSheet: View {
    let title: String
    let sources: [IosSource]
    let onDone: (Set<String>) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selection: Set<String>
    @State private var query = ""
    /// Empty means "every kind" / "every provider", like Android's leading 全部 chip.
    @State private var kinds: Set<String> = []
    @State private var providers: Set<String> = []

    init(title: String, sources: [IosSource], selected: Set<String>,
         onDone: @escaping (Set<String>) -> Void) {
        self.title = title
        self.sources = sources
        self.onDone = onDone
        // A catalog that changed while the picker was closed must not keep selecting names that are gone.
        _selection = State(initialValue: selected.intersection(sources.map(\.name)))
    }

    private var kindCatalog: [(id: String, title: String)] {
        SourceFilterOption.kinds.compactMap { kind in
            sources.contains { $0.mediaKind == kind.id } ? (kind.id, kind.title) : nil
        }
    }

    private var providerCatalog: [(id: String, title: String)] {
        let present = Set(sources.map(SourceFilterOption.ecosystemKey)).subtracting(["LOCAL"])
        let known = SourceProvider.order.filter(present.contains)
        return (known + present.subtracting(known).sorted()).map { ($0, SourceProvider.title($0)) }
    }

    /// Readings are always one of the built-in kinds, so the catalog answers without the space store.
    private func kindTitle(_ kind: String) -> String {
        SourceFilterOption.kinds.first { $0.id == kind }?.title ?? kind
    }

    private var visible: [IosSource] {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return sources.filter { source in
            (kinds.isEmpty || kinds.contains(source.mediaKind))
                && (providers.isEmpty || providers.contains(SourceFilterOption.ecosystemKey(source)))
                && (text.isEmpty || source.title.localizedCaseInsensitiveContains(text)
                    || source.name.localizedCaseInsensitiveContains(text))
        }
    }

    private var visibleNames: Set<String> { Set(visible.map(\.name)) }
    private var visibleAllSelected: Bool { !visibleNames.isEmpty && visibleNames.isSubset(of: selection) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("搜索来源", text: $query)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.done)
                            .accessibilityIdentifier("sourceSelection.query")
                        if !query.isEmpty {
                            Button {
                                query = ""
                            } label: {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("sourceSelection.query.clear")
                        }
                    }
                }
                if kindCatalog.count > 1 || providerCatalog.count > 1 {
                    Section {
                        if kindCatalog.count > 1 {
                            SourceFilterChips(label: String(localized: "内容类型"), options: kindCatalog,
                                              selected: $kinds) { "sourceSelection.kind.\($0)" }
                        }
                        if providerCatalog.count > 1 {
                            SourceFilterChips(label: String(localized: "来源类型"), options: providerCatalog,
                                              selected: $providers) { "sourceSelection.provider.\($0)" }
                        }
                    }
                }
                Section {
                    HStack {
                        Button(visibleAllSelected
                               ? String(localized: "取消选择当前结果")
                               : String(localized: "全选当前结果（\(visible.count)）")) {
                            selection = BatchSelection.toggling(visible: visibleNames, in: selection)
                        }
                        .disabled(visibleNames.isEmpty)
                        .accessibilityIdentifier("sourceSelection.selectVisible")
                        Spacer()
                        Button("清空选择") { selection.removeAll() }
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .disabled(selection.isEmpty)
                            .accessibilityIdentifier("sourceSelection.clear")
                    }
                    Text("已选 \(selection.count) 个来源；当前筛选 \(visible.count) 个。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } footer: {
                    if selection.isEmpty {
                        Text("至少选择一个来源。").foregroundStyle(.orange)
                    }
                }
                Section("可选来源（\(visible.count)）") {
                    ForEach(visible, id: \.name) { source in
                        row(source)
                    }
                }
                if visible.isEmpty {
                    Section {
                        if query.isEmpty {
                            ContentUnavailableView("没有符合条件的来源", systemImage: "line.3.horizontal.decrease.circle",
                                                   description: Text("试着放开内容类型或来源类型。"))
                        } else {
                            ContentUnavailableView.search(text: query)
                        }
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(selection.isEmpty ? String(localized: "完成")
                           : String(localized: "完成（\(selection.count)）")) {
                        onDone(selection)
                        dismiss()
                    }
                    .disabled(selection.isEmpty)
                    .accessibilityIdentifier("sourceSelection.done")
                }
            }
        }
    }

    private func row(_ source: IosSource) -> some View {
        let chosen = selection.contains(source.name)
        var details = ["\(kindTitle(source.mediaKind)) · \(SourceLanguage.title(source.locale))"]
        if providerCatalog.count > 1 {
            details.append(SourceProvider.title(SourceFilterOption.ecosystemKey(source)))
        }
        return Button {
            if !selection.insert(source.name).inserted { selection.remove(source.name) }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(source.title).foregroundStyle(.primary)
                    Text(details.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: chosen ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(chosen ? Color.accentColor : Color.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("sourceSelection.source.\(source.name)")
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }
}

/// A label plus 全部 and one chip per value; chips toggle, and 全部 clears the set.
private struct SourceFilterChips: View {
    let label: String
    let options: [(id: String, title: String)]
    @Binding var selected: Set<String>
    let identifier: (String) -> String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            FlowLayout(spacing: 6) {
                chip("全部", chosen: selected.isEmpty, identifier(identifierKey(""))) {
                    selected.removeAll()
                }
                ForEach(options, id: \.id) { option in
                    chip(option.title, chosen: selected.contains(option.id), identifier(identifierKey(option.id))) {
                        if !selected.insert(option.id).inserted { selected.remove(option.id) }
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }

    private func chip(_ title: String, chosen: Bool, _ id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .padding(.horizontal, 10).padding(.vertical, 5)
                .foregroundStyle(chosen ? Color.white : Color.primary)
                .background(chosen ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.quaternary), in: .capsule)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }

    private func identifierKey(_ value: String) -> String {
        identifier(value.isEmpty ? "all" : value)
    }
}
