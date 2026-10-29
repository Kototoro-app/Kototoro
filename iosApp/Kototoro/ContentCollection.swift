import KototoroShared
import SwiftUI

/// Android's work-list display modes (`ListMode`), including the 16:9 movie grids.
enum ContentListMode: String, CaseIterable, Identifiable {
    case list, detailed, grid, compactGrid, movieGrid, compactMovieGrid
    var id: String { rawValue }

    var title: String {
        switch self {
        case .list: String(localized: "列表")
        case .detailed: String(localized: "详细列表")
        case .grid: String(localized: "网格")
        case .compactGrid: String(localized: "紧凑网格")
        case .movieGrid: String(localized: "电影网格")
        case .compactMovieGrid: String(localized: "紧凑电影网格")
        }
    }

    var icon: String {
        switch self {
        case .list: "list.bullet"
        case .detailed: "list.bullet.rectangle.portrait"
        case .grid: "square.grid.2x2"
        case .compactGrid: "square.grid.3x3"
        case .movieGrid: "film"
        case .compactMovieGrid: "film.stack"
        }
    }

    var isGrid: Bool { self != .list && self != .detailed }
    var isMovie: Bool { self == .movieGrid || self == .compactMovieGrid }
    /// Titles drawn over the cover instead of below it.
    var hasTitleOverlay: Bool { self == .compactGrid || self == .compactMovieGrid }

    /// Android's shared card geometry: poster cards target 96pt and movie cards 176pt (160–264) at 100%.
    func targetWidth(scale: Double) -> CGFloat {
        let scale = min(max(scale, 0.5), 1.5)
        return isMovie ? min(max(176 * scale, 160), 264) : min(max(96 * scale, 48), 134)
    }
}

/// Grid size shared by all work lists, as Android's `gridSize` (50–150%).
enum ContentGridSize {
    static let key = "list.gridSize"
    static let range = 50.0...150.0
}

/// One work in a list, with what the screen knows about it beyond the source's own fields.
struct ContentItem: Identifiable {
    let content: IosSourceContent
    let identity: String
    var progress: Float?
    /// Short emphasis on the cover, such as the number of new chapters.
    var badge: String?
    /// Replaces the default secondary line (source and kind).
    var subtitle: String?
    /// Filled by [ContentCollection] from the library and downloads.
    var marks = ContentMarks()
    var id: String { content.id }

    init(
        content: IosSourceContent,
        progress: Float? = nil,
        badge: String? = nil,
        subtitle: String? = nil,
        marks: ContentMarks = ContentMarks()
    ) {
        self.content = content
        self.identity = ContentMarks.key(content)
        self.progress = progress
        self.badge = badge
        self.subtitle = subtitle
        self.marks = marks
    }

    func withMarks(_ marks: ContentMarks) -> ContentItem {
        var copy = self
        copy.marks = marks
        return copy
    }
}

/// Android's card badges (`ContentCardRenderModel`), the ones that help when choosing what to read: new chapters,
/// finished, in the library, downloaded and adult content.
struct ContentMarks {
    var newChapters = 0
    var favourite = false
    var downloaded = false
    var adult = false

    /// Works are matched by source and id, as the shared records key them.
    static func key(_ content: IosSourceContent) -> String { content.contentKey }
}

extension IosSourceContent {
    var contentKey: String { "\(sourceName)|\(id)" }
}

private extension ContentItem {
    var isFinished: Bool { (progress ?? 0) >= 0.995 }
    /// The cover's count: an explicit badge (the updates list) or the library's new chapters.
    var counter: String? { badge ?? (marks.newChapters > 0 ? "\(marks.newChapters)" : nil) }
}

/// Toolbar button opening the display options: mode tiles like Android's display sheet, the grid size slider
/// and any screen-specific options (the library's sort order).
struct ContentListModeMenu<Extra: View>: View {
    @Binding var mode: ContentListMode
    @ViewBuilder var extra: () -> Extra
    @State private var presented = false

    init(mode: Binding<ContentListMode>, @ViewBuilder extra: @escaping () -> Extra) {
        _mode = mode
        self.extra = extra
    }

    var body: some View {
        Button { presented = true } label: { Label("显示", systemImage: mode.icon) }
            .accessibilityIdentifier("list.mode")
            .popover(isPresented: $presented) {
                ContentDisplayOptions(mode: $mode, extra: extra) { presented = false }
                    .presentationCompactAdaptation(.popover)
            }
    }
}

extension ContentListModeMenu where Extra == EmptyView {
    init(mode: Binding<ContentListMode>) { self.init(mode: mode) { EmptyView() } }
}

private struct ContentDisplayOptions<Extra: View>: View {
    @Binding var mode: ContentListMode
    let extra: () -> Extra
    let done: () -> Void
    @AppStorage(ContentGridSize.key) private var gridSize = 100.0

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("显示方式").font(.headline)
                Spacer()
                Button("完成", action: done).fontWeight(.semibold).accessibilityIdentifier("list.options.done")
            }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                ForEach(ContentListMode.allCases) { option in
                    let selected = option == mode
                    Button { withAnimation(.snappy) { mode = option } } label: {
                        Label(option.title, systemImage: option.icon)
                            .font(.subheadline.weight(selected ? .semibold : .regular))
                            .lineLimit(1).minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12).padding(.vertical, 10)
                            .foregroundStyle(selected ? Color.white : Color.primary)
                            .background(selected ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.quaternary),
                                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("网格尺寸")
                    Spacer()
                    Text("\(Int(gridSize))%").monospacedDigit().foregroundStyle(.secondary)
                }
                .font(.subheadline)
                Slider(value: $gridSize, in: ContentGridSize.range, step: 10) {
                    Text("网格尺寸")
                } minimumValueLabel: {
                    Image(systemName: "square.grid.3x3")
                } maximumValueLabel: {
                    Image(systemName: "square.grid.2x2")
                }
                .accessibilityIdentifier("list.gridSize")
                .accessibilityValue("\(Int(gridSize))%")
            }
            .disabled(!mode.isGrid)
            .opacity(mode.isGrid ? 1 : 0.45)
            extra()
        }
        .padding(18)
        .frame(width: 330)
    }
}

/// The shared work list: lazy grid or rows inside the caller's scroll view, one style per [ContentListMode].
struct ContentCollection<Destination: View, Actions: View>: View {
    let runtime: IosSources
    let items: [ContentItem]
    let mode: ContentListMode
    /// Accessibility identifier prefix for each item (`favourite.`, `history.`, `source.content.`).
    let identifierPrefix: String
    var onItemAppear: ((ContentItem) -> Void)?
    /// The library itself does not mark its own works as favourites.
    var marksFavourites = true
    /// A non-nil set replaces navigation with selection, using the same source/id keys as library marks.
    var selectedKeys: Set<String>? = nil
    var onSelect: ((ContentItem) -> Void)? = nil
    @ViewBuilder let destination: (ContentItem) -> Destination
    @ViewBuilder let actions: (ContentItem) -> Actions

    @AppStorage(ContentGridSize.key) private var gridSize = 100.0
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var downloads: ReaderDownloadQueue
    @ObservedObject private var tagFilter = TagFilterStore.shared
    @ObservedObject private var customSpaces = CustomSpaceStore.shared
    @EnvironmentObject private var sourcesModel: SourcesModel
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(AdultContentFilter.Target.favourites.rawValue) private var hideAdultFavourites = false
    @AppStorage(AdultContentFilter.Target.suggestions.rawValue) private var hideAdultSuggestions = false

    /// Android's adult filter target of this list, by its identifier prefix.
    private var adultHidden: Bool {
        switch identifierPrefix {
        case "favourite.": hideAdultFavourites
        case "suggestion.": hideAdultSuggestions
        default: false
        }
    }

    private var gridColumns: [GridItem] {
        let compact = mode.hasTitleOverlay
        let spacing: CGFloat = compact ? 8 : 14
        return [GridItem(.adaptive(minimum: mode.targetWidth(scale: gridSize / 100)), spacing: spacing, alignment: .top)]
    }

    private struct MarksLookup {
        let favourites: Set<String>
        let updates: [String: Int]
        let downloaded: Set<String>

        func marks(for content: IosSourceContent, key: String? = nil) -> ContentMarks {
            let itemKey = key ?? ContentMarks.key(content)
            return ContentMarks(
                newChapters: updates[itemKey] ?? 0,
                favourite: favourites.contains(itemKey),
                downloaded: downloaded.contains(itemKey),
                adult: content.isNsfw
            )
        }
    }

    private var lookup: MarksLookup {
        let favourites = marksFavourites ? library.favouriteKeys : []
        let updates = library.updateChapterCounts
        let downloaded = downloads.completedContentKeys
        return MarksLookup(favourites: favourites, updates: updates, downloaded: downloaded)
    }

    var body: some View {
        let adult = adultHidden
        // A custom Space also narrows works by their sources' language and kind.
        let custom = ContentSpace.isCustom(space)
        let shown = tagFilter.blacklist.isEmpty && !adult && !custom ? items
            : items.filter { !tagFilter.hides($0.content) && !(adult && $0.content.isNsfw)
                && (!custom || ContentSpace.admits($0.content, sources: sourcesModel.sources)) }
        VStack(spacing: 12) {
            collection(shown)
            if shown.count < items.count {
                Label("已按标签黑名单隐藏 \(items.count - shown.count) 部", systemImage: "eye.slash")
                    .font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("\(identifierPrefix)hiddenByTags")
                    // Paging asks for more when the caller's last work appears; when that one is hidden (or a whole
                    // page is), reaching the end of what is shown stands in for it.
                    .onAppear { if shown.last?.identity != items.last?.identity, let last = items.last { onItemAppear?(last) } }
                    .id(items.count)
            }
        }
    }

    @ViewBuilder private func collection(_ items: [ContentItem]) -> some View {
        let lookup = self.lookup
        if mode.isGrid {
            let compact = mode.hasTitleOverlay
            LazyVGrid(columns: gridColumns, spacing: compact ? 8 : 16) {
                ForEach(items, id: \.identity) { rawItem in
                    let item = rawItem.withMarks(lookup.marks(for: rawItem.content, key: rawItem.identity))
                    link(item) {
                        if compact { CompactCard(runtime: runtime, item: item, movie: mode.isMovie) }
                        else { GridCard(runtime: runtime, item: item, movie: mode.isMovie) }
                    }
                }
            }
            .padding(.horizontal, compact ? 12 : 16)
        } else {
            let lastIdentity = items.last?.identity
            LazyVStack(spacing: 0) {
                // Keyed by the work, as the grid is: an index would rebind a row's cover, menu and tasks to
                // whichever work moved into that position after a refresh, filter or page.
                ForEach(items, id: \.identity) { rawItem in
                    let item = rawItem.withMarks(lookup.marks(for: rawItem.content, key: rawItem.identity))
                    link(item) {
                        ContentRow(runtime: runtime, item: item, detailed: mode == .detailed)
                            .padding(.horizontal, 16).padding(.vertical, mode == .detailed ? 12 : 9)
                    }
                    if rawItem.identity != lastIdentity {
                        Divider().padding(.leading, mode == .detailed ? 120 : 76)
                    }
                }
            }
            .background(.background.secondary, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .padding(.horizontal, 16)
        }
    }

    private func link(_ item: ContentItem, @ViewBuilder label: () -> some View) -> some View {
        Group {
            if let selectedKeys {
                let selected = selectedKeys.contains(ContentMarks.key(item.content))
                Button { onSelect?(item) } label: {
                    label()
                        .overlay {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(selected ? Color.accentColor : .clear, lineWidth: 3)
                        }
                        .overlay(alignment: .topTrailing) {
                            Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                                .font(.title2)
                                .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                                .background(.background, in: .circle)
                                .padding(6)
                        }
                        .contentShape(Rectangle())
                }
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityValue(selected ? "已选中" : "未选中")
            } else {
                NavigationLink { destination(item) } label: { label().contentShape(Rectangle()) }
            }
        }
            .buttonStyle(.plain)
            .accessibilityIdentifier("\(identifierPrefix)\(item.id)")
            .contextMenu { if selectedKeys == nil { actions(item) } }
            // A new layout needs a new menu interaction rather than the previous cell's UIKit registration.
            .id("\(identifierPrefix)\(item.identity).\(mode.rawValue)")
            .onAppear { onItemAppear?(item) }
    }
}

// MARK: - Cells

private struct GridCard: View {
    let runtime: IosSources
    let item: ContentItem
    var movie = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            CoverFrame(runtime: runtime, item: item, cornerRadius: 12, aspect: movie ? 16 / 9 : 2 / 3)
            Text(item.content.title)
                .font(.footnote.weight(.semibold))
                .lineLimit(2, reservesSpace: true)
            if let line = item.subtitle ?? item.content.summary.map(ContentText.firstLine) {
                Text(line).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
        }
    }
}

/// Title over the cover (Android's compact grid), so more works fit per row.
private struct CompactCard: View {
    let runtime: IosSources
    let item: ContentItem
    var movie = false

    var body: some View {
        CoverFrame(runtime: runtime, item: item, cornerRadius: 10, aspect: movie ? 16 / 9 : 2 / 3)
            .overlay(alignment: .bottomLeading) {
                Text(item.content.title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .padding(.horizontal, 7).padding(.top, 18)
                    .padding(.bottom, item.progress == nil ? 7 : 11)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(LinearGradient(colors: [.clear, .black.opacity(0.72)], startPoint: .top, endPoint: .bottom))
                    .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 10, bottomTrailingRadius: 10, style: .continuous))
            }
    }
}

private struct ContentRow: View {
    let runtime: IosSources
    let item: ContentItem
    let detailed: Bool
    @EnvironmentObject private var sources: SourcesModel

    var body: some View {
        let content = item.content
        HStack(alignment: detailed ? .top : .center, spacing: 12) {
            CoverFrame(runtime: runtime, item: item, cornerRadius: detailed ? 10 : 7, showsProgress: false, showsMarks: false)
                .frame(width: detailed ? 92 : 48)
            VStack(alignment: .leading, spacing: detailed ? 5 : 3) {
                Text(content.title)
                    .font(detailed ? .headline : .body)
                    .lineLimit(2)
                    .foregroundStyle(.primary)
                if detailed, !content.authors.isEmpty {
                    Text(content.authors.joined(separator: "、")).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Text(item.subtitle ?? sourceLine).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if detailed {
                    if !content.tags.isEmpty {
                        Text(content.tags.prefix(4).joined(separator: " · ")).font(.caption).foregroundStyle(.tertiary).lineLimit(1)
                    }
                    if let summary = content.summary.map(ContentText.plain), !summary.isEmpty {
                        Text(summary).font(.footnote).foregroundStyle(.secondary).lineLimit(3)
                    }
                }
                ContentStatusLine(item: item).padding(.top, 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var sourceLine: String {
        let source = sources.sources.first { $0.name == item.content.sourceName }?.title ?? item.content.sourceName
        return "\(source) · \(SourcesView.kindName(item.content.mediaKind))"
    }
}

private struct CardProgressBar: View {
    let progress: Float
    var body: some View {
        GeometryReader { geometry in
            Capsule().fill(.white.opacity(0.35))
                .overlay(alignment: .leading) {
                    Capsule().fill(Color.accentColor)
                        .frame(width: max(0, geometry.size.width * CGFloat(min(max(progress, 0), 1))))
                }
        }
        .frame(height: 3)
    }
}

/// Cover in a fixed frame (2:3 poster or 16:9 movie, cropped to fill), drawn like Android's poster: a hairline rim, a
/// soft shadow and a book spine on posters, with the reading progress along the bottom and the card badges.
private struct CoverFrame: View {
    let runtime: IosSources
    let item: ContentItem
    let cornerRadius: CGFloat
    var aspect: CGFloat = 2 / 3
    var showsProgress = true
    /// Compact cells (list thumbnails) keep only the counter; the row spells out the rest.
    var showsMarks = true
    @ScaledMetric(relativeTo: .caption2) private var adultMark: CGFloat = 9
    private var adultMarkSize: CGFloat { min(adultMark, 14) }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        Color.clear
            .aspectRatio(aspect, contentMode: .fit)
            .overlay { CoverImage(runtime: runtime, content: item.content) }
            .overlay(alignment: .leading) {
                // Android's spine: a shaded strip on the binding edge of book-shaped covers.
                if aspect < 1 {
                    LinearGradient(colors: [.black.opacity(0.22), .black.opacity(0.05), .clear],
                                   startPoint: .leading, endPoint: .trailing)
                        .frame(width: 5)
                        .allowsHitTesting(false)
                }
            }
            .overlay(alignment: .bottom) {
                if showsProgress, let progress = item.progress, !item.isFinished {
                    CardProgressBar(progress: progress)
                        .padding(.horizontal, 6).padding(.bottom, 5)
                        .accessibilityLabel(String(localized: "已读 \(Int(progress * 100))%"))
                }
            }
            .overlay(alignment: .topTrailing) {
                if let counter = item.counter {
                    BadgeView(text: counter).padding(5).accessibilityLabel("\(counter) 个新章节")
                } else if showsMarks && item.isFinished {
                    MarkIcon(symbol: "checkmark", tint: .green).padding(5).accessibilityLabel("已读完")
                }
            }
            .overlay(alignment: .topLeading) {
                if showsMarks && (item.marks.favourite || item.marks.downloaded) {
                    VStack(spacing: 4) {
                        if item.marks.favourite { MarkIcon(symbol: "heart.fill", tint: .pink).accessibilityLabel("已收藏") }
                        if item.marks.downloaded {
                            MarkIcon(symbol: "arrow.down", tint: .blue).accessibilityLabel("已下载")
                        }
                    }
                    .padding(5)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if showsMarks && item.marks.adult {
                    Text("18+")
                        .font(.system(size: adultMarkSize, weight: .heavy))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 4).padding(.vertical, 1.5)
                        .background(Color.red.opacity(0.85), in: .rect(cornerRadius: 4, style: .continuous))
                        .padding(.trailing, 5)
                        .padding(.bottom, showsProgress && item.progress != nil && !item.isFinished ? 12 : 5)
                        .accessibilityLabel("成人内容")
                }
            }
            .clipShape(shape)
            .overlay { shape.strokeBorder(.primary.opacity(0.12), lineWidth: 0.5) }
            .background {
                shape.fill(Color(uiColor: .secondarySystemBackground))
                    .shadow(color: .black.opacity(0.12), radius: 2.5, y: 1)
            }
    }
}

/// A small mark on the cover: a glyph on a tinted circle that stays legible over any artwork.
private struct MarkIcon: View {
    let symbol: String
    let tint: Color
    /// Grows with Dynamic Type, but stays a corner mark that leaves the cover visible.
    @ScaledMetric(relativeTo: .caption2) private var side: CGFloat = 18
    var body: some View {
        let side = min(self.side, 28)
        Image(systemName: symbol)
            .font(.system(size: side / 2, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: side, height: side)
            .background(tint.gradient, in: .circle)
            .overlay { Circle().strokeBorder(.white.opacity(0.7), lineWidth: 1) }
    }
}

private struct BadgeView: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.caption2.weight(.bold).monospacedDigit())
            .foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Color.red.gradient, in: .capsule)
            .overlay { Capsule().strokeBorder(.white.opacity(0.7), lineWidth: 1) }
    }
}

/// Android's status line of list rows: a tonal "N new chapters" pill, then the reading state and the other marks
/// spelled out.
private struct ContentStatusLine: View {
    let item: ContentItem
    var body: some View {
        let parts = [
            item.isFinished ? String(localized: "已读完") : item.progress.map { String(localized: "已读 \(Int($0 * 100))%") },
            item.marks.favourite ? String(localized: "已收藏") : nil,
            item.marks.downloaded ? String(localized: "已下载") : nil,
        ].compactMap { $0 }
        // An explicit badge (the updates list) already shows the count on the cover.
        let newChapters = item.badge == nil ? item.marks.newChapters : 0
        if newChapters > 0 || !parts.isEmpty || item.marks.adult {
            HStack(spacing: 6) {
                if newChapters > 0 {
                    Text("\(newChapters) 个新章节")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Color.accentColor)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.14), in: .capsule)
                }
                if item.marks.adult {
                    Text("18+").font(.caption2.weight(.heavy)).foregroundStyle(.red)
                }
                if !parts.isEmpty {
                    Text(parts.joined(separator: " · ")).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
    }
}

enum ContentText {
    /// Source summaries are often HTML; lists show plain text.
    static func plain(_ text: String) -> String {
        text.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func firstLine(_ text: String) -> String {
        plain(text.components(separatedBy: .newlines).first ?? text)
    }
}
