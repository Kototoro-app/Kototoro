import KototoroShared
import SwiftUI

/// The library page's filter groups (Android's list filters; the rule is core-domain's `ShelfFilter`).
struct LibraryShelfSelection: Equatable, Hashable {
    var sources: Set<String> = []
    var tags: Set<String> = []
    var states: Set<String> = []
    var reading = "ALL"
    var download = "ALL"
    var macros: Set<String> = []

    var count: Int {
        sources.count + tags.count + states.count + macros.count + (reading == "ALL" ? 0 : 1) + (download == "ALL" ? 0 : 1)
    }

    var filter: LibraryShelfFilter {
        IosShelf.shared.filter(sources: sources.sorted(), tags: tags.sorted(), states: states.sorted(),
                               reading: reading, download: download, macros: macros.sorted())
    }
}

typealias LibraryShelfFilter = Core_domainShelfFilter

struct LibraryFilterSheet: View {
    @Binding var selection: LibraryShelfSelection
    let entries: [IosLibraryEntry]
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.dismiss) private var dismiss

    private static let readings: [(String, String)] = [("ALL", String(localized: "全部")), ("UNREAD", String(localized: "未读")),
                                                        ("READING", String(localized: "在读")), ("FINISHED", String(localized: "读完"))]
    private static let downloads: [(String, String)] = [("ALL", String(localized: "全部")), ("DOWNLOADED", String(localized: "已下载")),
                                                         ("MISSING", String(localized: "未下载"))]
    private static let macros: [(String, String)] = [("NEW_CHAPTERS", String(localized: "有新章节")), ("SFW", String(localized: "全年龄")),
                                                      ("NSFW", String(localized: "18+"))]
    private static let stateTitles = ["ONGOING": String(localized: "连载中"), "FINISHED": String(localized: "已完结"),
                                      "ABANDONED": String(localized: "已放弃"), "PAUSED": String(localized: "暂停"),
                                      "UPCOMING": String(localized: "即将推出"), "RESTRICTED": String(localized: "受限")]

    /// Only what this library actually holds, most common first.
    private var sourceOptions: [(String, String)] {
        let counts = Dictionary(grouping: entries, by: { $0.content.sourceName }).mapValues(\.count)
        return counts.sorted { $0.value > $1.value }.map { name, count in
            ((name), "\(sources.sources.first { $0.name == name }?.title ?? name) · \(count)")
        }
    }

    private var stateOptions: [(String, String)] {
        Set(entries.compactMap(\.content.state)).sorted().map { ($0, Self.stateTitles[$0] ?? $0) }
    }

    var body: some View {
        NavigationStack {
            Form {
                // Single choices are pickers, multiple choices are rows with checkmarks (iOS form conventions).
                Section("阅读进度") {
                    Picker("阅读进度", selection: $selection.reading) {
                        ForEach(Self.readings, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("library.filter.reading")
                }
                Section("下载") {
                    Picker("下载", selection: $selection.download) {
                        ForEach(Self.downloads, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("library.filter.download")
                }
                Section("快捷") { checkRows(Self.macros, selection: $selection.macros, prefix: "macro") }
                if !stateOptions.isEmpty {
                    Section("出版状态") { checkRows(stateOptions, selection: $selection.states, prefix: "state") }
                }
                if sourceOptions.count > 1 {
                    Section("来源") { checkRows(sourceOptions, selection: $selection.sources, prefix: "source") }
                }
                if !entries.allSatisfy({ $0.content.tags.isEmpty }) {
                    Section {
                        NavigationLink {
                            LibraryTagPicker(entries: entries, selection: $selection.tags)
                        } label: {
                            LabeledContent("标签", value: selection.tags.isEmpty ? String(localized: "不限")
                                           : selection.tags.sorted().joined(separator: "、"))
                        }
                        .accessibilityIdentifier("library.filter.tags")
                    } footer: { Text("同一组内满足任一项即可，不同组需同时满足。") }
                }
            }
            .navigationTitle("筛选")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("清除") { selection = LibraryShelfSelection() }.disabled(selection.count == 0)
                        .accessibilityIdentifier("library.filter.clear")
                }
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func checkRows(_ options: [(String, String)], selection: Binding<Set<String>>, prefix: String) -> some View {
        ForEach(options, id: \.0) { option in
            CheckRow(title: option.1, selected: selection.wrappedValue.contains(option.0)) {
                if selection.wrappedValue.contains(option.0) { selection.wrappedValue.remove(option.0) }
                else { selection.wrappedValue.insert(option.0) }
            }
            .accessibilityIdentifier("library.filter.\(prefix).\(option.0)")
        }
    }
}

/// A form row toggled by tapping, with the system checkmark when chosen.
struct CheckRow: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                if selected { Image(systemName: "checkmark").foregroundStyle(.tint).fontWeight(.semibold) }
            }
            .contentShape(Rectangle())
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// The library's tags with counts, searchable, chosen with checkmarks.
private struct LibraryTagPicker: View {
    let entries: [IosLibraryEntry]
    @Binding var selection: Set<String>
    @State private var query = ""

    private var tags: [(String, Int)] {
        var counts: [String: Int] = [:]
        for entry in entries { for tag in entry.content.tags { counts[tag, default: 0] += 1 } }
        let text = query.trimmingCharacters(in: .whitespaces)
        return counts.filter { text.isEmpty || $0.key.localizedCaseInsensitiveContains(text) }
            .sorted { ($0.value, $1.key) > ($1.value, $0.key) }
    }

    var body: some View {
        List {
            ForEach(tags, id: \.0) { tag, count in
                CheckRow(title: "\(tag) (\(count))", selected: selection.contains(tag)) {
                    if selection.contains(tag) { selection.remove(tag) } else { selection.insert(tag) }
                }
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索标签")
        .navigationTitle("标签")
        .toolbar {
            ToolbarItem(placement: .primaryAction) { Button("清除") { selection = [] }.disabled(selection.isEmpty) }
        }
    }
}
