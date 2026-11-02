import KototoroShared
import SwiftUI

/// Browse filter selection by stable keys; the shared layer resolves them to the source's own tag objects.
struct BrowseFilter: Equatable {
    var order: String?
    var included: Set<String> = []
    var excluded: Set<String> = []
    var states: Set<String> = []
    var ratings: Set<String> = []
    var types: Set<String> = []
    var demographics: Set<String> = []
    var locale: String?
    var originalLocale: String?
    var year = 0
    var yearFrom = 0
    var yearTo = 0
    var author = ""
    /// Extension controls moved away from their defaults, by native control id.
    var dynamic: [String: IosFilterValue] = [:]

    var count: Int {
        included.count + excluded.count + states.count + ratings.count + types.count + demographics.count +
            (locale == nil ? 0 : 1) + (originalLocale == nil ? 0 : 1) + (year > 0 ? 1 : 0) +
            (yearFrom > 0 || yearTo > 0 ? 1 : 0) + (author.trimmingCharacters(in: .whitespaces).isEmpty ? 0 : 1) +
            dynamic.count
    }

    func isActive(_ panel: IosFilterPanel) -> Bool { count > 0 || (order != nil && order != panel.defaultSortOrder) }

    /// Source settings may change the available tags and choices while the browse screen remains open.
    func reconciled(with panel: IosFilterPanel) -> BrowseFilter {
        var next = self
        let tags = Set(panel.groups.flatMap { $0.tags.map(\.key) })
        next.included.formIntersection(tags)
        next.excluded = panel.excludeTags ? excluded.intersection(tags) : []
        if !panel.multipleTags { next.included = Set(next.included.sorted().prefix(1)) }
        for group in panel.groups where group.isExclusive {
            let chosen = next.included.intersection(group.tags.map(\.key)).sorted()
            if chosen.count > 1 { next.included.subtract(chosen.dropFirst()) }
        }
        next.states.formIntersection(panel.states)
        next.ratings.formIntersection(panel.contentRatings)
        next.types.formIntersection(panel.types)
        next.demographics.formIntersection(panel.demographics)
        if let order, !panel.sortOrders.contains(order) { next.order = nil }
        if let locale, !panel.locales.contains(locale) { next.locale = nil }
        if !panel.originalLocale { next.originalLocale = nil }
        else if let originalLocale, !panel.locales.contains(originalLocale) { next.originalLocale = nil }
        if !panel.year { next.year = 0 }
        if !panel.yearRange { next.yearFrom = 0; next.yearTo = 0 }
        if !panel.author { next.author = "" }
        next.dynamic = dynamic.filter { id, value in
            guard let control = panel.controlById(id: id) else { return false }
            return control.accepts(value: value) && value != control.initial
        }
        return next
    }

    var selection: IosFilterSelection? {
        guard count > 0 || order != nil else { return nil }
        let author = author.trimmingCharacters(in: .whitespaces)
        return IosFilterSelection(order: order, tagKeys: included.sorted(), excludedTagKeys: excluded.sorted(),
                                  states: states.sorted(), contentRatings: ratings.sorted(), types: types.sorted(),
                                  demographics: demographics.sorted(), locale: locale, originalLocale: originalLocale,
                                  year: Int32(year), yearFrom: Int32(yearFrom), yearTo: Int32(yearTo),
                                  author: author.isEmpty ? nil : author,
                                  dynamic: dynamic.sorted { $0.key < $1.key }.map { IosFilterChange(id: $0.key, value: $0.value) })
    }
}

extension IosFilterValue {
    static let checked = IosFilterValue(toggle: true, tri: 0, index: 0, ascending: false, text: "")
    static let included = IosFilterValue(toggle: false, tri: 1, index: 0, ascending: false, text: "")

    func with(toggle: Bool? = nil, tri: Int32? = nil, index: Int32? = nil, ascending: Bool? = nil,
              text: String? = nil) -> IosFilterValue {
        IosFilterValue(toggle: toggle ?? self.toggle, tri: tri ?? self.tri, index: index ?? self.index,
                       ascending: ascending ?? self.ascending, text: text ?? self.text)
    }
}

/// Android's filter sheet in native form: sort order, tag groups (tap to include, tap again to exclude), and
/// the source's state / rating / type / demographic / language options.
struct SourceFilterSheet: View {
    let panel: IosFilterPanel
    /// The source whose saved filters the sheet offers; none without one.
    var sourceName: String? = nil
    let onApply: (BrowseFilter) -> Void
    @State private var draft: BrowseFilter
    @State private var tagQuery = ""
    @State private var expandedGroups: Set<String> = []
    @Environment(\.dismiss) private var dismiss

    init(panel: IosFilterPanel, initial: BrowseFilter, sourceName: String? = nil,
         onApply: @escaping (BrowseFilter) -> Void) {
        self.panel = panel
        self.sourceName = sourceName
        self.onApply = onApply
        _draft = State(initialValue: initial)
    }

    var body: some View {
        NavigationStack {
            Form {
                if let sourceName, !panel.isEmpty {
                    SavedFiltersSection(source: sourceName, panel: panel, draft: $draft)
                }
                if panel.sortOrders.count > 1 {
                    Section("排序") {
                        Picker("排序方式", selection: Binding(get: { draft.order ?? panel.defaultSortOrder },
                                                          set: { draft.order = $0 })) {
                            ForEach(panel.sortOrders, id: \.self) { Text(Self.orderTitle($0)).tag($0) }
                        }
                        .accessibilityIdentifier("filter.order")
                    }
                } else if let only = panel.sortOrders.first {
                    Section("排序") { LabeledContent("排序方式", value: Self.orderTitle(only)) }
                }
                if panel.isEmpty {
                    // Every source gets the panel; one without a FilterList says so instead of hiding the entry.
                    Section {
                        Label("该来源没有提供分类或筛选条件，可以用搜索查找作品。", systemImage: "info.circle")
                            .foregroundStyle(.secondary)
                            .accessibilityIdentifier("filter.none")
                    }
                }
                if panel.isDynamic {
                    ForEach(Array(dynamicSections.enumerated()), id: \.offset) { _, section in
                        Section(section.title) {
                            ForEach(section.controls, id: \.id) { control in controlRow(control) }
                        }
                    }
                } else {
                    ForEach(Array(panel.groups.enumerated()), id: \.offset) { _, group in
                        let tags = group.tags.filter { tagQuery.isEmpty || $0.title.localizedCaseInsensitiveContains(tagQuery) }
                        if !tags.isEmpty {
                            Section {
                                FlowLayout(spacing: 8) {
                                    ForEach(tags, id: \.key) { tag in tagChip(tag, exclusive: group.isExclusive, group: group) }
                                }
                                .padding(.vertical, 4)
                            } header: {
                                Text(panel.groups.count == 1 && group.title == "Tags" ? String(localized: "标签") : group.title)
                            } footer: {
                                if panel.excludeTags && !group.isExclusive { Text("再次点击排除该标签。") }
                            }
                        }
                    }
                }
                if panel.author {
                    Section("作者") {
                        TextField("作者名称", text: $draft.author)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .accessibilityIdentifier("filter.author")
                    }
                }
                options(String(localized: "连载状态"), panel.states, \.states)
                options(String(localized: "内容分级"), panel.contentRatings, \.ratings)
                options(String(localized: "类型"), panel.types, \.types)
                options(String(localized: "受众"), panel.demographics, \.demographics)
                if !panel.locales.isEmpty {
                    Section("语言") {
                        Picker("语言", selection: $draft.locale) {
                            Text("全部").tag(String?.none)
                            ForEach(panel.locales, id: \.self) { code in
                                Text(Locale.current.localizedString(forIdentifier: code) ?? code).tag(Optional(code))
                            }
                        }
                        if panel.originalLocale {
                            Picker("原作语言", selection: $draft.originalLocale) {
                                Text("全部").tag(String?.none)
                                ForEach(panel.locales, id: \.self) { code in
                                    Text(Locale.current.localizedString(forIdentifier: code) ?? code).tag(Optional(code))
                                }
                            }
                        }
                    }
                }
                if panel.yearRange {
                    Section("年份范围") {
                        yearStepper(String(localized: "起始"), $draft.yearFrom, upper: draft.yearTo == 0 ? nil : draft.yearTo)
                        yearStepper(String(localized: "截止"), $draft.yearTo, lower: draft.yearFrom)
                    }
                }
                if panel.year {
                    Section("年份") {
                        Stepper(draft.year == 0 ? "不限" : "\(draft.year) 年", value: $draft.year,
                                in: 0...Calendar.current.component(.year, from: .now))
                            .onChange(of: draft.year) { old, new in if old == 0 && new == 1 { draft.year = 2000 } }
                    }
                }
            }
            .searchable(text: $tagQuery, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "搜索标签")
            .navigationTitle("筛选")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .bottomBar) {
                    Button("重置", role: .destructive) { draft = BrowseFilter() }
                        .disabled(!draft.isActive(panel)).accessibilityIdentifier("filter.reset")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("应用") { onApply(draft); dismiss() }.accessibilityIdentifier("filter.apply")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func yearStepper(_ title: String, _ value: Binding<Int>, lower: Int = 0, upper: Int? = nil) -> some View {
        let maximum = upper ?? Calendar.current.component(.year, from: .now)
        return Stepper(value: value, in: 0...maximum) {
            LabeledContent(title, value: value.wrappedValue == 0 ? String(localized: "不限") : String(localized: "\(value.wrappedValue) 年"))
        }
        .onChange(of: value.wrappedValue) { old, new in
            // Stepping up from "no limit" starts at a useful year rather than year 1.
            if old == 0 && new == 1 { value.wrappedValue = min(maximum, max(lower, 2000)) }
            else if new > 0 && new < lower { value.wrappedValue = old > new ? 0 : lower }
        }
    }

    // MARK: Extension (Mihon / Aniyomi) controls

    /// Top-level controls split at the extension's headers, each run becoming one form section.
    private var dynamicSections: [(title: String, controls: [IosFilterControl])] {
        var result: [(title: String, controls: [IosFilterControl])] = []
        var title = String(localized: "来源筛选")
        var current: [IosFilterControl] = []
        for control in panel.controls {
            switch control.kind {
            case "header":
                if !current.isEmpty { result.append((title, current)); current = [] }
                if !control.name.isEmpty { title = control.name }
            case "separator": continue
            default: current.append(control)
            }
        }
        if !current.isEmpty { result.append((title, current)) }
        return result
    }

    private func value(_ control: IosFilterControl) -> IosFilterValue { draft.dynamic[control.id] ?? control.initial }

    private func set(_ control: IosFilterControl, _ value: IosFilterValue) {
        draft.dynamic[control.id] = value == control.initial ? nil : value
    }

    private func binding<T>(_ control: IosFilterControl, get: @escaping (IosFilterValue) -> T,
                            set update: @escaping (IosFilterValue, T) -> IosFilterValue) -> Binding<T> {
        Binding(get: { get(value(control)) }, set: { set(control, update(value(control), $0)) })
    }

    private func controlRow(_ control: IosFilterControl) -> AnyView {
        switch control.kind {
        case "checkbox":
            return AnyView(Toggle(control.name, isOn: binding(control, get: \.toggle) { $0.with(toggle: $1) })
                .accessibilityIdentifier("filter.control.\(control.name)"))
        case "tristate":
            return AnyView(Picker(control.name, selection: binding(control, get: \.tri) { $0.with(tri: $1) }) {
                Text("不限").tag(Int32(0)); Text("包含").tag(Int32(1)); Text("排除").tag(Int32(2))
            }
            .pickerStyle(.menu)
            .accessibilityIdentifier("filter.control.\(control.name)"))
        case "select":
            return AnyView(Picker(control.name, selection: binding(control, get: \.index) { $0.with(index: $1) }) {
                ForEach(Array(control.values.enumerated()), id: \.offset) { index, label in Text(label).tag(Int32(index)) }
            }
            .pickerStyle(.menu)
            .accessibilityIdentifier("filter.control.\(control.name)"))
        case "sort":
            let current = value(control)
            return AnyView(Group {
                Picker(control.name, selection: binding(control, get: \.index) { $0.with(index: $1) }) {
                    Text("默认").tag(Int32(-1))
                    ForEach(Array(control.values.enumerated()), id: \.offset) { index, label in Text(label).tag(Int32(index)) }
                }
                .pickerStyle(.menu)
                .accessibilityIdentifier("filter.control.\(control.name)")
                if current.index >= 0 {
                    Toggle("升序", isOn: binding(control, get: \.ascending) { $0.with(ascending: $1) })
                        .accessibilityIdentifier("filter.control.\(control.name).ascending")
                }
            })
        case "text":
            return AnyView(LabeledContent(control.name) {
                TextField("输入\(control.name)", text: binding(control, get: \.text) { $0.with(text: $1) })
                    .multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("filter.control.\(control.name)")
            })
        case "group":
            return AnyView(groupRow(control))
        case "header":
            return AnyView(Text(control.name).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary))
        case "separator":
            return AnyView(EmptyView())
        default:
            return AnyView(LabeledContent(control.name, value: String(localized: "暂不支持")).foregroundStyle(.secondary))
        }
    }

    /// Genre-style groups (only check boxes / tri-states) render as chips; mixed groups nest their rows.
    @ViewBuilder
    private func groupRow(_ group: IosFilterControl) -> some View {
        let chips = !group.children.isEmpty && group.children.allSatisfy { $0.kind == "checkbox" || $0.kind == "tristate" }
        let modified = group.children.filter { draft.dynamic[$0.id] != nil }.count
        let expanded = Binding(get: { !tagQuery.isEmpty || expandedGroups.contains(group.id) },
                               set: { if $0 { expandedGroups.insert(group.id) } else { expandedGroups.remove(group.id) } })
        DisclosureGroup(isExpanded: expanded) {
            if chips {
                let visible = group.children.filter { tagQuery.isEmpty || $0.name.localizedCaseInsensitiveContains(tagQuery) }
                FlowLayout(spacing: 8) {
                    ForEach(visible, id: \.id) { child in controlChip(child) }
                }
                .padding(.vertical, 4)
            } else {
                ForEach(group.children, id: \.id) { child in controlRow(child) }
            }
        } label: {
            HStack {
                Text(group.name)
                Spacer()
                if modified > 0 { Text("\(modified)").font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
            }
            // On the label only: an identifier on the group itself would also name nested groups.
            .accessibilityIdentifier("filter.group.\(group.name)")
        }
    }

    /// Tap cycles a check box on/off and a tri-state through include → exclude → ignore.
    private func controlChip(_ control: IosFilterControl) -> some View {
        let current = value(control)
        let included = control.kind == "tristate" ? current.tri == 1 : current.toggle
        let excluded = control.kind == "tristate" && current.tri == 2
        return Button {
            if control.kind == "tristate" { set(control, current.with(tri: (current.tri + 1) % 3)) }
            else { set(control, current.with(toggle: !current.toggle)) }
        } label: {
            chipLabel(control.name, included: included, excluded: excluded)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("filter.control.\(control.name)")
        .accessibilityValue(included ? "已包含" : excluded ? "已排除" : "未选择")
    }

    private func chipLabel(_ title: String, included: Bool, excluded: Bool) -> some View {
        Label(title, systemImage: excluded ? "minus" : "checkmark")
            .labelStyle(ChipLabelStyle(showsIcon: included || excluded))
            .font(.subheadline)
            .strikethrough(excluded)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .foregroundStyle(included ? Color.white : excluded ? Color.red : Color.primary)
            .background(included ? AnyShapeStyle(Color.accentColor) : excluded ? AnyShapeStyle(Color.red.opacity(0.15))
                        : AnyShapeStyle(.quaternary), in: .capsule)
    }

    private func tagChip(_ tag: IosSourceTag, exclusive: Bool, group: IosFilterGroup) -> some View {
        let included = draft.included.contains(tag.key)
        let excluded = draft.excluded.contains(tag.key)
        return Button {
            if included {
                draft.included.remove(tag.key)
                if panel.excludeTags && !exclusive { draft.excluded.insert(tag.key) }
            } else if excluded {
                draft.excluded.remove(tag.key)
            } else {
                // Single-choice groups (and sources without multi-tag support) replace the previous choice.
                if exclusive { group.tags.forEach { draft.included.remove($0.key) } }
                if !panel.multipleTags { draft.included.removeAll() }
                draft.included.insert(tag.key)
            }
        } label: {
            chipLabel(tag.title, included: included, excluded: excluded)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("filter.tag.\(tag.key)")
        .accessibilityValue(included ? "已包含" : excluded ? "已排除" : "未选择")
    }

    @ViewBuilder
    private func options(_ title: String, _ values: [String], _ path: WritableKeyPath<BrowseFilter, Set<String>>) -> some View {
        if !values.isEmpty {
            Section(title) {
                FlowLayout(spacing: 8) {
                    ForEach(values, id: \.self) { value in
                        let selected = draft[keyPath: path].contains(value)
                        Button {
                            if selected { draft[keyPath: path].remove(value) } else { draft[keyPath: path].insert(value) }
                        } label: {
                            Text(Self.optionTitle(value)).font(.subheadline)
                                .padding(.horizontal, 12).padding(.vertical, 7)
                                .foregroundStyle(selected ? Color.white : Color.primary)
                                .background(selected ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.quaternary), in: .capsule)
                        }
                        .buttonStyle(.plain)
                        .accessibilityValue(selected ? "已选择" : "未选择")
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    static func orderTitle(_ order: String) -> String {
        switch order {
        case "UPDATED": String(localized: "最近更新")
        case "UPDATED_ASC": String(localized: "最早更新")
        case "POPULARITY": String(localized: "人气")
        case "POPULARITY_ASC": String(localized: "人气（升序）")
        case "POPULARITY_TODAY": String(localized: "今日人气")
        case "POPULARITY_WEEK": String(localized: "本周人气")
        case "POPULARITY_MONTH": String(localized: "本月人气")
        case "POPULARITY_YEAR": String(localized: "年度人气")
        case "RATING": String(localized: "评分")
        case "RATING_ASC": String(localized: "评分（升序）")
        case "NEWEST": String(localized: "最新")
        case "NEWEST_ASC": String(localized: "最早")
        case "ALPHABETICAL": String(localized: "名称 A–Z")
        case "ALPHABETICAL_DESC": String(localized: "名称 Z–A")
        case "ADDED": String(localized: "最近收录")
        case "ADDED_ASC": String(localized: "最早收录")
        case "RELEVANCE": String(localized: "相关度")
        default: order
        }
    }

    static func optionTitle(_ value: String) -> String {
        switch value {
        case "ONGOING": String(localized: "连载中")
        case "FINISHED": String(localized: "已完结")
        case "ABANDONED": String(localized: "已放弃")
        case "PAUSED": String(localized: "暂停")
        case "UPCOMING": String(localized: "即将推出")
        case "RESTRICTED": String(localized: "受限")
        case "SAFE": String(localized: "全年龄")
        case "SUGGESTIVE": String(localized: "轻度")
        case "ADULT": String(localized: "成人")
        case "MANGA": String(localized: "日漫")
        case "MANHWA": String(localized: "韩漫")
        case "MANHUA": String(localized: "国漫")
        case "COMICS": String(localized: "美漫")
        case "NOVEL": String(localized: "小说")
        case "ONE_SHOT": String(localized: "短篇")
        case "DOUJINSHI": String(localized: "同人")
        case "IMAGE_SET": String(localized: "图集")
        case "ARTIST_CG": "CG"
        case "GAME_CG": String(localized: "游戏 CG")
        case "OTHER": String(localized: "其他")
        case "SHOUNEN": String(localized: "少年")
        case "SHOUJO": String(localized: "少女")
        case "SEINEN": String(localized: "青年")
        case "JOSEI": String(localized: "女性")
        case "KODOMO": String(localized: "儿童")
        case "NONE": String(localized: "无")
        default: value
        }
    }
}

private struct ChipLabelStyle: LabelStyle {
    let showsIcon: Bool
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            if showsIcon { configuration.icon.imageScale(.small) }
            configuration.title
        }
    }
}

/// Wrapping row layout for chips.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let width = rows.map { $0.width }.max() ?? 0
        let height = rows.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(0, rows.count - 1))
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row { var indices: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = [Row()]
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = rows[rows.count - 1].indices.isEmpty ? size.width : rows[rows.count - 1].width + spacing + size.width
            if needed > width && !rows[rows.count - 1].indices.isEmpty { rows.append(Row()) }
            let last = rows.count - 1
            rows[last].width = rows[last].indices.isEmpty ? size.width : rows[last].width + spacing + size.width
            rows[last].height = max(rows[last].height, size.height)
            rows[last].indices.append(index)
        }
        return rows.filter { !$0.indices.isEmpty }
    }
}
