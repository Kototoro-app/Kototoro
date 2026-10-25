import KototoroShared
import SwiftUI

/// The user's languages for default source selection: multilingual extensions start with only these enabled.
enum SourceLanguage {
    static let preferred: Set<String> = Set(Locale.preferredLanguages.prefix(3).compactMap { code(of: $0) })

    /// Language-agnostic ("all"/"multi"/empty) and the user's own languages.
    static func isUserLanguage(_ locale: String) -> Bool {
        let value = locale.lowercased()
        if value.isEmpty || value == "all" || value == "multi" || value == "other" { return true }
        return code(of: value).map(preferred.contains) ?? false
    }

    static func code(of identifier: String) -> String? {
        let normalized = identifier.replacingOccurrences(of: "_", with: "-")
        return Locale(identifier: normalized).language.languageCode?.identifier.lowercased()
            ?? normalized.split(separator: "-").first.map { String($0).lowercased() }
    }

    static func title(_ locale: String) -> String {
        let value = locale.lowercased()
        if value.isEmpty || value == "all" || value == "multi" { return String(localized: "多语言") }
        return Locale.current.localizedString(forIdentifier: locale.replacingOccurrences(of: "_", with: "-")) ?? locale
    }
}

/// Browse source kinds (content) and ecosystems (where a source comes from), for filtering the source list.
enum SourceFilterOption {
    static let kinds: [(id: String, title: String, icon: String)] = [
        ("MANGA", String(localized: "漫画"), "book.closed"), ("NOVEL", String(localized: "小说"), "text.book.closed"), ("VIDEO", String(localized: "视频"), "play.rectangle"),
    ]

    static func ecosystemKey(_ source: IosSource) -> String { source.kind == "video" ? "TVBOX" : source.ecosystem }

    /// Stored as comma-separated keys of what is hidden, so new kinds and ecosystems show by default.
    static func decode(_ stored: String) -> Set<String> { Set(stored.split(separator: ",").map(String.init)) }
    static func encode(_ hidden: Set<String>) -> String { hidden.sorted().joined(separator: ",") }
}

/// Per-page content-type switch (全部 · 漫画 · 小说 · 视频), used while content spaces are turned off. Pages show it
/// only while they hold more than one kind, and a stored kind that is no longer present filters nothing.
struct ContentKindPicker: View {
    @Binding var selection: String
    let available: Set<String>

    var body: some View {
        Picker("内容类型", selection: $selection) {
            Text("全部").tag("")
            ForEach(SourceFilterOption.kinds.filter { available.contains($0.id) }, id: \.id) { kind in
                Text(kind.title).tag(kind.id)
            }
        }
        .pickerStyle(.segmented)
        .accessibilityIdentifier("contentKind")
    }

    static func isShown(_ available: Set<String>) -> Bool { available.count > 1 }

    /// The kind to filter by, or nil for every kind.
    static func effective(_ selection: String, available: Set<String>) -> String? {
        isShown(available) && available.contains(selection) ? selection : nil
    }
}

/// Android's media spaces, built in: one app-wide choice of what the browse, library and history pages show.
enum ContentSpace {
    static let key = "space.kind"
    /// Spaces can be turned off; pages then switch content types on their own ([ContentKindPicker]).
    static let enabledKey = "space.enabled"

    /// What a page filters by: the app-wide space while spaces are on, otherwise the page's own switch.
    @MainActor
    static func filter(enabled: Bool, space: String, page: String, available: Set<String>) -> String? {
        enabled ? kind(space) : ContentKindPicker.effective(page, available: available)
    }

    @MainActor
    static func title(_ kind: String) -> String {
        if isCustom(kind) { return CustomSpaceStore.shared.space(kind)?.title ?? String(localized: "全部") }
        return SourceFilterOption.kinds.first { $0.id == kind }?.title ?? String(localized: "全部")
    }

    @MainActor
    static func icon(_ kind: String) -> String {
        if isCustom(kind) { return "square.grid.2x2" }
        return SourceFilterOption.kinds.first { $0.id == kind }?.icon ?? "square.stack"
    }

    /// Whether a work of [mediaKind] belongs to Space [kind] (built-in kind or custom Space id).
    @MainActor
    static func matches(_ kind: String, _ mediaKind: String) -> Bool {
        guard let wanted = self.kind(kind) else { return true }
        return wanted == mediaKind
    }

    /// The latest history entry the app-wide space admits; every kind while spaces are off.
    @MainActor
    static func latest(in history: [IosLibraryEntry], enabled: Bool, space: String) -> IosLibraryEntry? {
        history.first { !enabled || matches(space, $0.content.mediaKind) }
    }

}

/// The same selector is available in the toolbar and the tab bar accessory.
struct SpaceMenu: View {
    var accessibilityID = "space.menu"
    var compact = false
    @AppStorage(ContentSpace.key) private var kind = ""
    @Environment(\.openSpaceSwitcher) private var open

    var body: some View {
        Button(action: open) {
            HStack(spacing: compact ? 3 : 5) {
                if compact {
                    Image(systemName: ContentSpace.icon(kind))
                } else {
                    Text(ContentSpace.title(kind))
                }
                if !compact {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                }
            }
            .font(.subheadline.weight(.semibold))
            .frame(minWidth: compact ? 44 : nil, minHeight: 44)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("当前：\(ContentSpace.title(kind))，切换内容空间")
        .accessibilityIdentifier(accessibilityID)
    }
}

/// The tab container owns presentation: the accessory can disappear when a resumed reader opens.
private struct SpaceSwitcherPresentation: ViewModifier {
    let resume: (CategoryContent) -> Void
    @AppStorage(ContentSpace.key) private var kind = ""
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @State private var presented = false
    @State private var pendingResume: CategoryContent?

    func body(content: Content) -> some View {
        content
            .environment(\.openSpaceSwitcher) { presented = true }
            .sheet(isPresented: $presented, onDismiss: {
                if let pendingResume {
                    self.pendingResume = nil
                    resume(pendingResume)
                }
            }) {
                ContentSpacePanel(selection: $kind) { entry in
                    pendingResume = CategoryContent(content: entry.content)
                    presented = false
                }
                .environmentObject(library)
                .environmentObject(sources)
                .presentationDetents([.height(540), .large])
                .presentationDragIndicator(.visible)
            }
    }
}

private struct OpenSpaceSwitcherKey: EnvironmentKey {
    static let defaultValue: () -> Void = {}
}

extension EnvironmentValues {
    var openSpaceSwitcher: () -> Void {
        get { self[OpenSpaceSwitcherKey.self] }
        set { self[OpenSpaceSwitcherKey.self] = newValue }
    }
}

extension View {
    func spaceSwitcherPresentation(resume: @escaping (CategoryContent) -> Void) -> some View {
        modifier(SpaceSwitcherPresentation(resume: resume))
    }
}

private struct ContentSpacePanel: View {
    @Binding var selection: String
    @ObservedObject private var customSpaces = CustomSpaceStore.shared
    let resume: (IosLibraryEntry) -> Void
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 10) {
                    card("")
                    ForEach(SourceFilterOption.kinds, id: \.id) { kind in card(kind.id) }
                    ForEach(customSpaces.enabledSpaces, id: \.id) { space in card(space.id) }
                    NavigationLink { CustomSpacesView() } label: {
                        Label("管理自定义空间", systemImage: "slider.horizontal.3").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.glass)
                    .accessibilityIdentifier("space.manage")
                }
                .padding(16)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("内容空间")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭", systemImage: "xmark") { dismiss() }
                        .accessibilityIdentifier("space.close")
                }
            }
        }
        .accessibilityIdentifier("space.panel")
    }

    private func card(_ kind: String) -> some View {
        let selected = selection == kind
        let latest = library.history.first { ContentSpace.matches(kind, $0.content.mediaKind) }
        let artwork = library.spaceArtwork[kind] ?? latest?.content
        let id = kind.isEmpty ? "ALL" : ContentSpace.isCustom(kind) ? "CUSTOM.\(ContentSpace.title(kind))" : kind
        return VStack(alignment: .leading, spacing: 0) {
            Button {
                selection = kind
                dismiss()
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: ContentSpace.icon(kind))
                        .font(.title3)
                        .frame(width: 28)
                    Text(kind.isEmpty ? String(localized: "全部内容") : ContentSpace.title(kind))
                        .font(.headline)
                    Spacer(minLength: 8)
                    if selected {
                        Image(systemName: "checkmark.circle.fill")
                            .accessibilityLabel("当前空间")
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("space.select.\(id)")
            .accessibilityAddTraits(selected ? .isSelected : [])

            if !kind.isEmpty, let latest {
                HStack(alignment: .bottom, spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(latest.content.title).font(.subheadline.weight(.medium)).lineLimit(1)
                        Text(ContinueReadingDetail.text(latest)).font(.caption).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Button {
                        selection = kind
                        resume(latest)
                    } label: {
                        Label(kind == "VIDEO" ? "继续播放" : "继续阅读",
                              systemImage: kind == "VIDEO" ? "play.fill" : "book.fill")
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 8)
                            .background(.white.opacity(0.16), in: Capsule())
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(sources.runtime == nil)
                    .accessibilityLabel(
                        "\(kind == "VIDEO" ? String(localized: "继续播放") : String(localized: "继续阅读"))：\(latest.content.title)，\(ContinueReadingDetail.text(latest))"
                    )
                    .accessibilityIdentifier("space.resume.\(id)")
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
            } else {
                Text(kind.isEmpty ? "汇总查看漫画、小说与视频" : ContentSpace.isCustom(kind)
                     ? (CustomSpaceStore.shared.space(kind).map(CustomSpaceText.summary) ?? "") : "浏览来源与作品库")
                    .font(.caption)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 14)
                    .allowsHitTesting(false)
            }
        }
        .foregroundStyle(.white)
        .background {
            Color(white: 0.16)
                .overlay {
                    if !reduceTransparency, let artwork, let runtime = sources.runtime {
                        CoverImage(runtime: runtime, content: artwork, plain: true)
                            .id(CoverCache.identity(artwork))
                            .blur(radius: 3, opaque: true)
                            .overlay {
                                LinearGradient(
                                    colors: [.black.opacity(0.80), .black.opacity(0.64), .black.opacity(0.38)],
                                    startPoint: .leading, endPoint: .trailing
                                )
                            }
                            .overlay(.black.opacity(contrast == .increased ? 0.3 : 0.12))
                    }
                }
                .accessibilityHidden(true)
                .allowsHitTesting(false)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay {
            RoundedRectangle(cornerRadius: 18)
                .strokeBorder(.white.opacity(selected ? 0.95 : 0.18), lineWidth: selected ? 2 : 0.5)
                .allowsHitTesting(false)
        }
    }
}

/// Settings switch between app-wide spaces and per-page content-type switches.
struct ContentSpaceSettingsSection: View {
    @AppStorage(ContentSpace.enabledKey) private var enabled = true

    var body: some View {
        Section {
            Toggle("内容空间", isOn: $enabled).accessibilityIdentifier("settings.spaces")
            if enabled {
                NavigationLink { CustomSpacesView() } label: { Text("自定义空间") }
                    .accessibilityIdentifier("settings.customSpaces")
            }
        } footer: {
            Text(enabled ? "浏览、作品库与记录共用导航栏中的空间选择。"
                         : "各页面在顶部分别切换内容类型。")
        }
    }
}

/// Shown when the active space leaves a page empty while other spaces have content.
struct SpaceEmptyView: View {
    @AppStorage(ContentSpace.key) private var kind = ""
    let what: String

    var body: some View {
        ContentUnavailableView {
            Label("\(ContentSpace.title(kind))空间没有\(what)", systemImage: ContentSpace.icon(kind))
        } description: {
            Text("其他内容空间里还有\(what)。")
        } actions: {
            Button("显示全部") { kind = "" }.buttonStyle(.glass).accessibilityIdentifier("space.showAll")
        }
    }
}

/// One source with its on/off switch, used by the management tab and extension details.
struct SourceToggleRow: View {
    let source: IosSource
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var library: LibraryModel
    @State private var migration: LibraryMigrationRequest?

    var body: some View {
        Toggle(isOn: Binding(get: { sources.isEnabled(source) },
                             set: { value in Task { await sources.setEnabled([source], value) } })) {
            HStack(spacing: 10) {
                SourceIcon(runtime: sources.runtime, source: source, size: 28)
                VStack(alignment: .leading, spacing: 1) {
                    Text(source.title).lineLimit(1)
                    Text([source.kind == "video" ? String(localized: "影视站点") : SourceLanguage.title(source.locale),
                          SourcesView.kindName(source.mediaKind)].joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .accessibilityIdentifier("source.toggle.\(source.name)")
        .contextMenu {
            if source.ecosystem != "LOCAL" {
                Button("迁移此来源的收藏与历史", systemImage: "arrow.triangle.swap") {
                    migration = LibraryMigrationRequest(contents: (library.favourites + library.history)
                        .map(\.content).filter { $0.sourceName == source.name })
                }
            }
        }
        .sheet(item: $migration) { request in
            NavigationStack { BatchMigrationView(contents: request.contents) }
        }
    }
}

/// Section header with the group's enabled count and bulk actions.
struct SourceGroupHeader: View {
    let title: String
    let members: [IosSource]
    @EnvironmentObject private var sources: SourcesModel

    var body: some View {
        let enabled = members.filter(sources.isEnabled).count
        HStack {
            Text(title).lineLimit(1)
            Text("\(enabled)/\(members.count)").monospacedDigit().foregroundStyle(.secondary)
            Spacer()
            Menu {
                Button("全部开启", systemImage: "checkmark.circle") { Task { await sources.setEnabled(members, true) } }
                Button("全部关闭", systemImage: "circle") { Task { await sources.setEnabled(members, false) } }
                Button("仅开启我的语言", systemImage: "globe") {
                    Task {
                        let mine = members.filter { SourceLanguage.isUserLanguage($0.locale) }
                        await sources.setEnabled(mine, true)
                        await sources.setEnabled(members.filter { !SourceLanguage.isUserLanguage($0.locale) }, false)
                    }
                }
            } label: {
                Image(systemName: "ellipsis.circle").imageScale(.large)
            }
            .textCase(nil)
            .accessibilityIdentifier("source.group.\(title)")
        }
    }
}

/// Compact tile for the browse grid layout.
struct SourceTile: View {
    let source: IosSource
    @EnvironmentObject private var sources: SourcesModel

    var body: some View {
        VStack(spacing: 4) {
            ZStack(alignment: .topTrailing) {
                SourceIcon(runtime: sources.runtime, source: source, size: 40)
                if sources.isNewSource(source) {
                    SourceNewBadge()
                        .offset(x: 10, y: -4)
                }
            }
            .padding(.top, 2)

            Text(source.title)
                .font(.caption.weight(.medium))
                .lineLimit(1)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 2)

            Text(source.kind == "video" ? String(localized: "影视") : SourceLanguage.title(source.locale))
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .padding(.horizontal, 6)
                .padding(.vertical, 1)
                .background(Color(.tertiarySystemFill), in: Capsule())
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8).padding(.horizontal, 6)
        .background(.background.secondary, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

struct SourceNewBadge: View {
    var body: some View {
        Text("新")
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(.white)
            .padding(.horizontal, 4).padding(.vertical, 1)
            .background(Color.red, in: Capsule())
            .accessibilityLabel("本版本新来源")
    }
}

/// Drag to arrange the browse order (all enabled sources; pinned ones stay in their own section on top).
struct SourceOrderSheet: View {
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.dismiss) private var dismiss
    @State private var pinned: [IosSource] = []
    @State private var others: [IosSource] = []
    @State private var saving = false

    var body: some View {
        NavigationStack {
            List {
                if !pinned.isEmpty {
                    Section("置顶") {
                        ForEach(pinned, id: \.name) { row($0) }
                            .onMove { pinned.move(fromOffsets: $0, toOffset: $1) }
                    }
                }
                Section {
                    ForEach(others, id: \.name) { row($0) }
                        .onMove { others.move(fromOffsets: $0, toOffset: $1) }
                } footer: {
                    Text("拖动调整浏览页中的来源顺序；分组时各组内保持这个顺序。")
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("调整来源顺序")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") {
                        saving = true
                        Task {
                            await sources.saveOrder(pinned + others)
                            saving = false
                            dismiss()
                        }
                    }
                    .disabled(saving)
                    .accessibilityIdentifier("sources.reorder.done")
                }
            }
            .onAppear {
                let enabled = sources.ordered(sources.sources.filter(sources.isEnabled))
                pinned = enabled.filter(sources.isPinned)
                others = enabled.filter { !sources.isPinned($0) }
            }
        }
    }

    private func row(_ source: IosSource) -> some View {
        HStack(spacing: 10) {
            SourceIcon(runtime: sources.runtime, source: source, size: 28)
            VStack(alignment: .leading, spacing: 1) {
                Text(source.title).lineLimit(1)
                Text(source.kind == "video" ? String(localized: "影视站点") : SourceLanguage.title(source.locale))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityIdentifier("sources.order.\(source.name)")
    }
}
