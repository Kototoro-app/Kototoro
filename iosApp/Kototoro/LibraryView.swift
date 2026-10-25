import KototoroShared
import SwiftUI

/// Library ordering, as offered by Android's favourites list.
enum LibrarySort: String, CaseIterable, Identifiable {
    case updated, title, progress
    var id: String { rawValue }
    var title: String {
        switch self {
        case .updated: String(localized: "最近更新")
        case .title: String(localized: "标题")
        case .progress: String(localized: "阅读进度")
        }
    }
}

struct CategoryContent: Identifiable {
    let content: IosSourceContent
    var id: String { content.id }
}

/// New category (id nil) or rename of an existing one.
private struct CategoryEditor: Identifiable {
    let id = UUID()
    var category: Int32?
    var title: String
}

/// The library tab: favourites in the shared work layouts, with categories as a compact chip bar.
struct LibraryView: View {
    @ObservedObject var library: LibraryModel
    @ObservedObject var sources: SourcesModel
    @AppStorage("listMode.library") private var mode = ContentListMode.grid
    @AppStorage("library.sort") private var sort = LibrarySort.updated
    @AppStorage("library.kind") private var pageKind = ""
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true
    @State private var selectedCategory: Int32?
    @State private var query = ""
    @State private var editor: CategoryEditor?
    @State private var deleting: IosCategory?
    @State private var categoryContent: CategoryContent?
    @State private var managingCategories = false
    @State private var migrating = false
    @State private var findingDuplicates = false
    @State private var filtering = false
    @State private var shelf = LibraryShelfSelection()
    @EnvironmentObject private var downloads: ReaderDownloadQueue
    /// Batch selection: keys of the works chosen, kept across searches and categories until done.
    @State private var selecting = false
    @State private var selected: Set<String> = []
    @State private var migrationSubset: [IosSourceContent]?
    @State private var confirmingUnfavourite = false
    @State private var deletingWithWorks: IosCategory?
    /// Where the next category's works come in from: the trailing edge when moving forward.
    @State private var swipeEdge: Edge = .trailing
    private final class ItemCache {
        var key: String = ""
        var items: [ContentItem] = []
    }
    @State private var itemCache = ItemCache()

    private var visibleCategories: [IosCategory] {
        // Keep an emptied selection reachable until the user leaves it; space changes return to All.
        library.categories.filter {
            $0.isVisibleInLibrary && (selectedKind == nil || count($0.id) > 0 || $0.id == selectedCategory)
        }
    }
    private var availableKinds: Set<String> { library.favouriteIndex.availableKinds }
    private var selectedKind: String? {
        if spacesEnabled { return ContentSpace.kind(space) }
        return ContentKindPicker.effective(pageKind, available: availableKinds)
    }
    private var collection: LibraryCollectionIndex<IosLibraryEntry>.Slice {
        library.favouriteIndex.slice(mediaKind: selectedKind)
    }

    /// Favourites of the content space (or this page's switch); categories count and list only these.
    private var kindFavourites: [IosLibraryEntry] {
        collection.entries
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    // The categories are the first row of the page and scroll away with the works, as Mail and Music
                    // place theirs. A top `safeAreaBar` also moves the top inset, and the navigation bar's large title
                    // and search drawer then expand and collapse against it at the end of a fling: the page shook and
                    // the search field never came out.
                    if library.schemaVersion != nil { categoryBar }
                    MissingSourcesNotice().padding(.horizontal)
                    if !spacesEnabled && ContentKindPicker.isShown(availableKinds) {
                        ContentKindPicker(selection: $pageKind, available: availableKinds).padding(.horizontal)
                    }
                    if let error = library.error, !library.favourites.isEmpty {
                        Label(error, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.red)
                            .padding(.horizontal)
                    }
                    content
                        .id(selectedCategory)
                        .transition(.asymmetric(insertion: .move(edge: swipeEdge), removal: .opacity))
                }
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            // Swiping sideways over the works moves to the previous / next category, like the chips do.
            .gesture(HorizontalSwipeGesture(onEnded: swipe))
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle(selecting ? String(localized: "已选择 \(selected.count) 部") : String(localized: "作品库"))
            .navigationBarTitleDisplayMode(.inline)
            // The system search field under the title; the search tab stays the app-wide search.
            .pageSearch(text: $query, prompt: String(localized: "搜索作品库"))
            .refreshable { await library.refresh() }
            .toolbar {
                if selecting {
                    selectionToolbar
                } else {
                    ToolbarItem(placement: .topBarLeading) {
                        if spacesEnabled { SpaceMenu() }
                    }
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button { filtering = true } label: {
                            Label(shelf.count > 0 ? "筛选 \(shelf.count)" : "筛选",
                                  systemImage: shelf.count > 0 ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease")
                        }
                        .accessibilityIdentifier("library.filter")
                        ContentListModeMenu(mode: $mode) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("排序").font(.subheadline)
                                Picker("排序", selection: $sort) {
                                    ForEach(LibrarySort.allCases) { Text($0.title).tag($0) }
                                }
                                .pickerStyle(.segmented)
                                .accessibilityIdentifier("library.sort")
                            }
                        }
                        Menu {
                            Button("换源 / 迁移", systemImage: "arrow.triangle.swap") {
                                migrationSubset = nil
                                migrating = true
                            }
                            .accessibilityIdentifier("library.migrate")
                            Button("查找重复", systemImage: "square.on.square") { findingDuplicates = true }
                                .accessibilityIdentifier("library.duplicates")
                        } label: {
                            Label("更多", systemImage: "ellipsis")
                        }
                        .accessibilityIdentifier("library.more")
                    }
                }
            }
            .navigationDestination(isPresented: $findingDuplicates) { LibraryDuplicatesView() }
            .navigationDestination(isPresented: $migrating) {
                LibraryMigrationView(favourites: migrationSubset ?? kindFavourites.map(\.content))
            }
            .sheet(item: $editor) { CategoryEditorSheet(editor: $0) }
            .sheet(isPresented: $filtering) { LibraryFilterSheet(selection: $shelf, entries: kindFavourites) }
            .sheet(item: $categoryContent) { FavouriteCategoriesView(content: $0.content) }
            .sheet(isPresented: $managingCategories) { CategoryManagementSheet() }
            .onChange(of: library.categories) { _, _ in
                if let selectedCategory, !visibleCategories.contains(where: { $0.id == selectedCategory }) {
                    self.selectedCategory = nil
                }
            }
            .onChange(of: selectedKind) { _, _ in selectedCategory = nil }
            .confirmationDialog("删除分类？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                                titleVisibility: .visible, presenting: deleting) { category in
                Button("仅删除分类", role: .destructive) {
                    Task {
                        if await library.removeCategory(category.id), selectedCategory == category.id { selectedCategory = nil }
                    }
                }
                Button("删除分类并取消收藏其中作品", role: .destructive) { deletingWithWorks = category }
            } message: { category in
                let shared = sharedWorkCount(category.id)
                Text(String(localized: "仅删除分类：作品从这个分类移除，只在此分类中的作品将取消收藏。")
                     + (shared > 0 ? String(localized: "其中 \(shared) 部还在其他分类，会保留。") : ""))
            }
            .confirmationDialog("取消收藏分类中的全部作品？",
                                isPresented: Binding(get: { deletingWithWorks != nil }, set: { if !$0 { deletingWithWorks = nil } }),
                                titleVisibility: .visible, presenting: deletingWithWorks) { category in
                Button("删除“\(category.title)”及 \(count(category.id)) 部作品", role: .destructive) {
                    Task {
                        if await library.removeCategoryWithWorks(category.id), selectedCategory == category.id { selectedCategory = nil }
                    }
                }
            } message: { category in
                let shared = sharedWorkCount(category.id)
                Text(String(localized: "这些作品会从所有分类取消收藏") + (shared > 0 ? String(localized: "，包括其中 \(shared) 部也在其他分类的作品") : "")
                     + String(localized: "。阅读历史不受影响；删除会同步到 Google Drive。"))
            }
            .confirmationDialog("取消收藏所选作品？", isPresented: $confirmingUnfavourite, titleVisibility: .visible) {
                Button("取消收藏 \(selected.count) 部作品", role: .destructive) {
                    let contents = selectedContents
                    Task { if await library.removeFavourites(contents) { endSelection() } }
                }
            } message: {
                Text("作品会从所有分类移除，阅读历史不受影响；删除会同步到 Google Drive。")
            }
        }
    }

    // MARK: Batch selection

    private var selectedContents: [IosSourceContent] {
        kindFavourites.map(\.content).filter { selected.contains(ContentMarks.key($0)) }
    }

    private func endSelection() {
        selecting = false
        selected.removeAll()
    }

    /// Works in the category that also belong to another one, which a works-deleting removal takes out of those too.
    private func sharedWorkCount(_ category: Int32) -> Int {
        (collection.byCategory[category] ?? []).filter { $0.categoryIds.contains { $0.int32Value != category } }.count
    }

    /// Selection temporarily replaces the top bar's buttons, as a source's list does.
    @ToolbarContentBuilder
    private var selectionToolbar: some ToolbarContent {
        let visibleKeys = Set(items.map { ContentMarks.key($0.content) })
        let allSelected = !visibleKeys.isEmpty && visibleKeys.isSubset(of: selected)
        ToolbarItem(placement: .topBarLeading) {
            Button("退出多选", systemImage: "xmark") { endSelection() }
                .accessibilityIdentifier("library.selectDone")
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button(allSelected ? String(localized: "取消全选") : String(localized: "全选")) {
                selected = BatchSelection.toggling(visible: visibleKeys, in: selected)
            }
            .disabled(visibleKeys.isEmpty)
            .accessibilityIdentifier("library.selectAll")
            Button("换源", systemImage: "arrow.triangle.swap") {
                migrationSubset = selectedContents
                migrating = true
            }
            .disabled(selected.isEmpty)
            .accessibilityIdentifier("library.selection.migrate")
            if let category = selectedCategory {
                Button("移出分类", systemImage: "folder.badge.minus") {
                    let contents = selectedContents
                    Task {
                        try? await library.updateCategories(contents, changes: [category: false])
                        endSelection()
                    }
                }
                .disabled(selected.isEmpty)
                .accessibilityIdentifier("library.selection.removeFromCategory")
            }
            Button("取消收藏", systemImage: "heart.slash", role: .destructive) { confirmingUnfavourite = true }
                .disabled(selected.isEmpty)
                .accessibilityIdentifier("library.selection.unfavourite")
        }
    }

    // MARK: Categories

    /// 全部 followed by the visible categories, in chip order.
    private var categoryOrder: [Int32?] { [nil] + visibleCategories.map { Optional($0.id) } }

    private func swipe(_ dx: CGFloat) {
        guard abs(dx) > 60, let index = categoryOrder.firstIndex(of: selectedCategory) else { return }
        let next = index + (dx < 0 ? 1 : -1)
        guard categoryOrder.indices.contains(next) else { return }
        withAnimation(.snappy) { select(categoryOrder[next]) }
    }

    /// Works slide in from the side the category lies on, so chips and swipes read the same way.
    private func select(_ category: Int32?) {
        let from = categoryOrder.firstIndex(of: selectedCategory) ?? 0
        let to = categoryOrder.firstIndex(of: category) ?? 0
        swipeEdge = to >= from ? .trailing : .leading
        selectedCategory = category
    }

    /// Visible categories in one scrollable row with counts; long-press a chip to rename or delete it.
    private var categoryBar: some View {
        HStack(spacing: 8) {
            // Each glass container stays inside its own bounds: one spanning the scroll view would draw the chips'
            // glass past the scroll edge and merge it with the actions.
            ScrollViewReader { chips in
            ScrollView(.horizontal, showsIndicators: false) {
                GlassEffectContainer(spacing: 8) {
                    HStack(spacing: 8) {
                        chip(String(localized: "全部"), count: kindFavourites.count, selected: selectedCategory == nil) { select(nil) }
                            .accessibilityIdentifier("category.all")
                            .id(Int32?.none)
                        ForEach(visibleCategories, id: \.id) { category in
                            chip(category.title, count: count(category.id), selected: selectedCategory == category.id) {
                                select(selectedCategory == category.id ? nil : category.id)
                            }
                            .accessibilityIdentifier("category.\(category.id)")
                            .id(Optional(category.id))
                            .contextMenu {
                                Button("重命名", systemImage: "pencil") { editor = CategoryEditor(category: category.id, title: category.title) }
                                Button("删除分类", systemImage: "trash", role: .destructive) { deleting = category }
                            }
                        }
                    }
                    .padding(.leading)
                    .padding(.vertical, 4)
                }
            }
            .onChange(of: selectedCategory) { _, selected in
                withAnimation(.snappy) { chips.scrollTo(selected, anchor: .center) }
            }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // Category actions stay at the trailing end, beside the categories rather than in the toolbar.
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    Button("添加分类", systemImage: "plus") { editor = CategoryEditor(title: "") }
                        .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle)
                        .disabled(library.isLoading)
                        .accessibilityIdentifier("category.add")
                    Button("管理分类", systemImage: "slider.horizontal.3") { managingCategories = true }
                        .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle)
                        .disabled(library.isLoading)
                        .accessibilityIdentifier("category.manage")
                }
            }
            .padding(.trailing)
        }
    }

    @ViewBuilder
    private func chip(_ title: String, count: Int, selected: Bool, action: @escaping () -> Void) -> some View {
        let button = Button(action: { withAnimation(.snappy) { action() } }) {
            HStack(spacing: 5) {
                Text(title).lineLimit(1)
                Text("\(count)").font(.caption.monospacedDigit()).foregroundStyle(selected ? .white.opacity(0.8) : .secondary)
            }
        }
        if selected {
            button.buttonStyle(.glassProminent).accessibilityAddTraits(.isSelected)
        } else {
            button.buttonStyle(.glass)
        }
    }

    private func count(_ category: Int32) -> Int {
        collection.categoryCounts[category] ?? 0
    }

    // MARK: Works

    private var items: [ContentItem] {
        let key = "\(library.recordsRevision)|\(selectedKind ?? "")|\(selectedCategory ?? -1)|\(sort.rawValue)|\(query)|\(shelf.hashValue)|\(downloads.completedContentKeys.count)"
        if itemCache.key == key {
            return itemCache.items
        }
        let text = query.trimmingCharacters(in: .whitespaces)
        var baseEntries = selectedCategory.flatMap { collection.byCategory[$0] } ?? kindFavourites
        if shelf.count > 0 {
            // Android's filter groups (shared rule): progress, downloads, chips, state, source, tags.
            let filter = shelf.filter
            let downloaded = downloads.completedContentKeys
            let updates = library.updateChapterCounts
            baseEntries = baseEntries.filter { entry in
                let key = ContentMarks.key(entry.content)
                return IosShelf.shared.accepts(filter: filter, content: entry.content,
                                               progress: entry.hasProgress ? KotlinFloat(value: entry.progress) : nil,
                                               onDevice: downloaded.contains(key), newChapters: Int32(updates[key] ?? 0))
            }
        }
        let entries: [IosLibraryEntry]
        if text.isEmpty {
            entries = baseEntries
        } else {
            entries = baseEntries.filter { entry in
                entry.content.title.localizedCaseInsensitiveContains(text) ||
                entry.content.authors.contains { $0.localizedCaseInsensitiveContains(text) }
            }
        }
        let sorted: [IosLibraryEntry] = switch sort {
        case .updated: entries.sorted { $0.updatedAt > $1.updatedAt }
        case .title: entries.sorted { $0.content.title.localizedStandardCompare($1.content.title) == .orderedAscending }
        case .progress: entries.sorted { ($0.hasProgress ? $0.progress : -1) > ($1.hasProgress ? $1.progress : -1) }
        }
        let computed = sorted.map { ContentItem(content: $0.content, progress: $0.hasProgress ? $0.progress : nil) }
        itemCache.key = key
        itemCache.items = computed
        return computed
    }

    @ViewBuilder
    private var content: some View {
        if library.isLoading && library.favourites.isEmpty {
            ProgressView("正在读取作品库…").frame(maxWidth: .infinity).padding(.top, 80)
        } else if let error = library.error, library.favourites.isEmpty {
            ContentUnavailableView {
                Label("作品库暂时不可用", systemImage: "exclamationmark.triangle")
            } description: {
                Text(error)
            } actions: {
                Button("重试") { Task { await library.load() } }
            }
        } else if library.favourites.isEmpty {
            ContentUnavailableView("作品库是空的", systemImage: "books.vertical",
                                   description: Text("在作品详情页点击收藏，作品会出现在这里；可以用右上角按钮新建分类来整理。"))
                .padding(.top, 60)
                .accessibilityIdentifier("library.empty")
        } else if let runtime = sources.runtime {
            let shown = items
            if shown.isEmpty {
                if kindFavourites.isEmpty {
                    SpaceEmptyView(what: String(localized: "收藏")).padding(.top, 40)
                } else if query.trimmingCharacters(in: .whitespaces).isEmpty {
                    ContentUnavailableView("这个分类还没有作品", systemImage: "folder",
                                           description: Text("在来源列表长按作品，或在详情页点收藏时选择分类。"))
                        .padding(.top, 40)
                } else {
                    ContentUnavailableView.search(text: query).padding(.top, 40)
                }
            } else {
                ContentCollection(runtime: runtime, items: shown, mode: mode, identifierPrefix: "favourite.",
                                  marksFavourites: false, selectedKeys: selecting ? selected : nil,
                                  onSelect: { item in
                                      let key = ContentMarks.key(item.content)
                                      if !selected.insert(key).inserted { selected.remove(key) }
                                  }) { item in
                    SourceDetailsView(runtime: runtime, content: item.content)
                } actions: { item in
                    Button("整理分类", systemImage: "folder") { categoryContent = CategoryContent(content: item.content) }
                    Button("选择多个", systemImage: "checkmark.circle") {
                        selected = [ContentMarks.key(item.content)]
                        selecting = true
                    }
                    .accessibilityIdentifier("library.select")
                    Button("取消收藏", systemImage: "heart.slash", role: .destructive) {
                        Task { try? await library.setFavourite(item.content, favourite: false) }
                    }
                }
            }
        } else {
            ProgressView().frame(maxWidth: .infinity).padding(.top, 80)
        }
    }
}

/// Edit a local snapshot so cancelling never writes ordering or visibility changes.
private struct CategoryManagementSheet: View {
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.dismiss) private var dismiss
    @State private var ordered: [IosCategory] = []
    @State private var visible: Set<Int32> = []
    @State private var saving = false
    @State private var reordering = false
    @State private var deleting: IosCategory?
    @State private var deletingWithWorks: IosCategory?

    /// Active favourites of a category in every space; deleting acts on all of them.
    private func works(in category: Int32) -> [IosLibraryEntry] {
        library.favourites.filter { $0.categoryIds.contains { $0.int32Value == category } }
    }
    private func sharedCount(_ category: Int32) -> Int {
        works(in: category).filter { $0.categoryIds.contains { $0.int32Value != category } }.count
    }
    private func forget(_ category: Int32) {
        ordered.removeAll { $0.id == category }
        visible.remove(category)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(ordered, id: \.id) { category in
                        HStack(spacing: 12) {
                        Button { deleting = category } label: {
                            Image(systemName: "minus.circle.fill").foregroundStyle(.red).imageScale(.large)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("删除分类 \(category.title)")
                        .accessibilityIdentifier("category.delete.\(category.id)")
                        Toggle(isOn: Binding(get: { visible.contains(category.id) }, set: { shown in
                            if shown { visible.insert(category.id) } else { visible.remove(category.id) }
                        })) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(category.title)
                                Text("在书架显示").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .accessibilityIdentifier("category.visibility.\(category.id)")
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button("删除", systemImage: "trash", role: .destructive) { deleting = category }
                        }
                    }
                    .onMove { ordered.move(fromOffsets: $0, toOffset: $1) }
                } header: {
                    HStack {
                        Text("分类")
                        Spacer()
                        Button(reordering ? "完成排序" : "调整顺序") { withAnimation { reordering.toggle() } }
                            .font(.subheadline).textCase(nil)
                            .accessibilityIdentifier("category.reorder")
                    }
                } footer: {
                    Text("点左侧红色按钮或左滑分类可删除；点“调整顺序”后拖动右侧手柄排序。隐藏的分类仍可在整理分类中使用，作品仍出现在“全部”中。")
                }
                if ordered.isEmpty { Text("还没有分类，请先添加分类。").foregroundStyle(.secondary) }
                if let error = library.error { Text(error).foregroundStyle(.red) }
            }
            .environment(\.editMode, .constant(reordering ? .active : .inactive))
            .disabled(saving)
            .navigationTitle("管理分类")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }.disabled(saving)
                        .accessibilityIdentifier("category.manage.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        saving = true
                        Task {
                            let saved = await library.saveCategoryLayout(ordered, visible: visible)
                            saving = false
                            if saved { dismiss() }
                        }
                    }
                    .disabled(saving)
                    .accessibilityIdentifier("category.manage.save")
                }
            }
            .onAppear {
                ordered = library.categories
                visible = Set(ordered.filter(\.isVisibleInLibrary).map(\.id))
            }
            .interactiveDismissDisabled(saving)
            .confirmationDialog("删除分类？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                                titleVisibility: .visible, presenting: deleting) { category in
                Button("仅删除分类", role: .destructive) {
                    Task { if await library.removeCategory(category.id) { forget(category.id) } }
                }
                Button("删除分类并取消收藏其中作品", role: .destructive) { deletingWithWorks = category }
            } message: { category in
                let shared = sharedCount(category.id)
                Text(String(localized: "仅删除分类：作品从这个分类移除，只在此分类中的作品将取消收藏。")
                     + (shared > 0 ? String(localized: "其中 \(shared) 部还在其他分类，会保留。") : "")
                     + String(localized: "删除会立即生效，不受“取消”影响。"))
            }
            .confirmationDialog("取消收藏分类中的全部作品？",
                                isPresented: Binding(get: { deletingWithWorks != nil }, set: { if !$0 { deletingWithWorks = nil } }),
                                titleVisibility: .visible, presenting: deletingWithWorks) { category in
                Button("删除“\(category.title)”及 \(works(in: category.id).count) 部作品", role: .destructive) {
                    Task { if await library.removeCategoryWithWorks(category.id) { forget(category.id) } }
                }
            } message: { category in
                let shared = sharedCount(category.id)
                Text(String(localized: "这些作品会从所有分类取消收藏") + (shared > 0 ? String(localized: "，包括其中 \(shared) 部也在其他分类的作品") : "")
                     + String(localized: "。阅读历史不受影响；删除会同步到 Google Drive。"))
            }
        }
    }
}

private struct CategoryEditorSheet: View {
    @State var editor: CategoryEditor
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.dismiss) private var dismiss
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("分类名称", text: $editor.title)
                    .accessibilityIdentifier("category.title")
                if let error = library.error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle(editor.category == nil ? "新建分类" : "重命名分类")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        saving = true
                        Task {
                            let saved = if let id = editor.category { await library.renameCategory(id, to: editor.title) }
                                else { await library.addCategory(editor.title) }
                            saving = false
                            if saved { dismiss() }
                        }
                    }
                    .disabled(editor.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || saving)
                    .accessibilityIdentifier("category.save")
                }
            }
            .interactiveDismissDisabled(saving)
        }
        .presentationDetents([.medium])
    }
}

/// A horizontal page swipe that wins over the works' taps. A SwiftUI drag runs beside the cells' navigation links,
/// so lifting the finger at the end of a swipe still opened the work under it; a UIKit pan cancels the touches it
/// takes, so the links never see that touch end. Vertical drags and the chips' own horizontal scroll are left alone.
struct HorizontalSwipeGesture: UIGestureRecognizerRepresentable {
    /// Receives the horizontal translation once the swipe ends.
    let onEnded: (CGFloat) -> Void

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.delegate = context.coordinator
        return pan
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        if recognizer.state == .ended { onEnded(recognizer.translation(in: recognizer.view).x) }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
            guard let pan = recognizer as? UIPanGestureRecognizer else { return false }
            // The pan begins after a few points of movement; that movement says more than an instantaneous velocity.
            var motion = pan.translation(in: pan.view)
            if motion == .zero { motion = pan.velocity(in: pan.view) }
            return abs(motion.x) > abs(motion.y) * 1.5
        }

        /// Touches in a horizontally scrolling view (the category chips) scroll that view instead.
        func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            var view = touch.view
            while let current = view {
                if let scroll = current as? UIScrollView,
                   scroll.contentSize.width > scroll.bounds.width + 1 { return false }
                view = current.superview
            }
            return true
        }

        /// The page's vertical scroll keeps running; any other gesture (a cell's tap) gives way.
        func gestureRecognizer(_ recognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            other.view is UIScrollView && other is UIPanGestureRecognizer
        }
    }
}
