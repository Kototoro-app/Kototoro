import Combine
import CryptoKit
import AVFoundation
import KototoroShared
import SwiftUI
import ImageIO
import UniformTypeIdentifiers

/// One source's listing: paged poster grid, categories and search. Paging follows the source's own mode.
@MainActor
final class SourceBrowseModel: ObservableObject {
    @Published private(set) var items: [IosSourceContent] = []
    @Published private(set) var contentItems: [ContentItem] = []
    @Published private(set) var itemsRevision = 0
    @Published private(set) var categories: [IosSourceTag] = []
    @Published private(set) var selectedCategory: IosSourceTag?
    @Published private(set) var isLoading = false
    @Published private(set) var hasMore = true
    @Published private(set) var error: String?
    /// The last failure was a browser challenge (Cloudflare): offer the in-app browser instead of a bare retry.
    @Published private(set) var challenge: IosChallenge?
    @Published private(set) var panel: IosFilterPanel?
    /// Why the filter panel could not be described; the chip stays and its sheet offers a retry.
    @Published private(set) var panelError: String?
    @Published private(set) var filter = BrowseFilter()
    @Published var query = ""
    let runtime: IosSources
    let source: IosSource
    private var page: Int32 = 0
    /// Offset-based sources count every returned entry, including overlapping and repeated entries.
    private var loadedCount: Int32 = 0
    private var activeQuery: String?
    private var generation = UUID()
    private var panelRequest: UUID?

    var allCategoriesSelected: Bool {
        if let panel, panel.isDynamic {
            return categories.allSatisfy { tag in
                guard let control = panel.control(tagKey: tag.key) else { return true }
                let value = filter.dynamic[control.id] ?? control.initial
                return control.kind == "tristate" ? value.tri == 0 : !value.toggle
            }
        }
        return filter.included.isEmpty && filter.excluded.isEmpty
    }

    init(runtime: IosSources, source: IosSource, initialQuery: String? = nil) {
        self.runtime = runtime
        self.source = source
        // Opened from global search ("更多"): start with that query applied.
        if let text = initialQuery?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
            query = text
            activeQuery = text
        }
    }

    func start() async {
        guard items.isEmpty, !isLoading else { return }
        await loadMore()
        Task { await loadCategories() }
    }

    func select(_ category: IosSourceTag?) async {
        var next = filter
        if let panel, panel.isDynamic {
            // Extension sources: the bar edits the same native checkbox/tri-state as the filter sheet.
            for tag in categories {
                guard let control = panel.control(tagKey: tag.key) else { continue }
                let neutral = control.kind == "tristate" ? control.initial.with(tri: 0) : control.initial.with(toggle: false)
                next.dynamic[control.id] = neutral == control.initial ? nil : neutral
            }
            if let category, let control = panel.control(tagKey: category.key) {
                let selected = control.kind == "tristate" ? control.initial.with(tri: 1) : control.initial.with(toggle: true)
                next.dynamic[control.id] = selected == control.initial ? nil : selected
            }
        } else {
            next.included = category.map { [$0.key] } ?? []
            next.excluded = []
        }
        await apply(next)
    }

    /// Applies the filter panel; the quick category bar mirrors a single included tag.
    func apply(_ next: BrowseFilter) async {
        guard next != filter else { return }
        filter = next
        synchronizeCategory()
        await reload()
    }

    private func synchronizeCategory() {
        if let panel, panel.isDynamic {
            var excluded = false
            let included = categories.filter { tag in
                guard let control = panel.control(tagKey: tag.key) else { return false }
                let value = filter.dynamic[control.id] ?? control.initial
                if control.kind == "tristate" && value.tri == 2 { excluded = true }
                return control.kind == "tristate" ? value.tri == 1 : value.toggle
            }
            selectedCategory = included.count == 1 && !excluded ? included[0] : nil
        } else {
            selectedCategory = filter.included.count == 1 && filter.excluded.isEmpty
                ? categories.first { filter.included.contains($0.key) } : nil
        }
    }

    func submitSearch() async {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        activeQuery = text.isEmpty ? nil : text
        await reload()
    }

    func clearSearch() async {
        guard activeQuery != nil else { return }
        activeQuery = nil
        await reload()
    }

    func reload(refreshPanel: Bool = false) async {
        generation = UUID()
        let request = generation
        items = []
        contentItems = []
        itemsRevision += 1
        page = 0
        loadedCount = 0
        hasMore = true
        error = nil; challenge = nil
        isLoading = refreshPanel
        if refreshPanel {
            await loadCategories()
            guard generation == request else { return }
            isLoading = false
        }
        guard !Task.isCancelled else { return }
        await loadMore()
    }

    func retry() async {
        await loadMore()
        if categories.isEmpty {
            Task { await loadCategories() }
        }
    }

    func loadMore() async {
        guard hasMore, !isLoading else { return }
        let request = generation
        isLoading = true
        error = nil; challenge = nil
        defer { if generation == request { isLoading = false } }
        do {
            let result = try await runtime.filteredPage(sourceName: source.name, page: page, loadedCount: loadedCount,
                                                        query: activeQuery, selection: filter.selection)
            guard !Task.isCancelled, generation == request else { return }
            var known = Set(items.map(ContentMarks.key))
            let fresh = result.filter { known.insert(ContentMarks.key($0)).inserted }
            items.append(contentsOf: fresh)
            contentItems.append(contentsOf: fresh.map { ContentItem(content: $0) })
            itemsRevision += 1
            loadedCount += Int32(result.count)
            page += 1
            hasMore = !fresh.isEmpty
        } catch {
            if !Task.isCancelled, generation == request {
                self.error = SourceErrorText.describe(error)
                self.challenge = SourceErrorText.challenge(error)
            }
        }
    }

    func loadCategories() async {
        let request = UUID()
        panelRequest = request
        panelError = nil
        do {
            let loaded = try await runtime.filterPanel(sourceName: source.name)
            guard !Task.isCancelled, panelRequest == request else { return }
            panel = loaded
            categories = loaded.quickTags
            filter = filter.reconciled(with: loaded)
            synchronizeCategory()
        } catch {
            if !Task.isCancelled, panelRequest == request {
                panel = nil
                categories = []
                panelError = SourceErrorText.describe(error)
            }
        }
    }
}

struct SourceBrowseView: View {
    @StateObject private var model: SourceBrowseModel
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.isSearching) private var isSearching
    @State private var editingFilter = false
    @State private var settingsOpen = false
    @State private var settingsNeedReload = false
    @State private var importingFiles = false
    @State private var messageTitle = ""
    @State private var message: String?
    @State private var searchPresented = false
    @State private var selecting = false
    @State private var selectedKeys: Set<String> = []
    @State private var favouriteRequest: FavouriteCategoryRequest?
    private let onPick: ((IosSourceContent) -> Void)?

    /// Imported files (本地漫画 / 本地小说): an import button instead of source settings.
    private var isLocal: Bool { model.source.ecosystem == "LOCAL" }

    init(runtime: IosSources, source: IosSource, initialQuery: String? = nil,
         onPick: ((IosSourceContent) -> Void)? = nil) {
        _model = StateObject(wrappedValue: SourceBrowseModel(runtime: runtime, source: source, initialQuery: initialQuery))
        self.onPick = onPick
    }

    @AppStorage("listMode.browse") private var mode = ContentListMode.grid

    var body: some View {
        ScrollView {
            categoryBar.disabled(selecting)
            ContentCollection(runtime: model.runtime, items: model.contentItems, mode: mode,
                              identifierPrefix: "source.content.",
                              onItemAppear: { item in
                                  if item.id == model.items.last?.id, model.error == nil { Task { await model.loadMore() } }
                              }, selectedKeys: onPick != nil ? [] : (selecting ? selectedKeys : nil),
                              onSelect: { item in
                                  if let onPick { onPick(item.content); return }
                                  let key = ContentMarks.key(item.content)
                                  if selectedKeys.contains(key) { selectedKeys.remove(key) }
                                  else { selectedKeys.insert(key) }
                              }) { item in
                SourceDetailsView(runtime: model.runtime, content: item.content)
            } actions: { item in
                let favourited = item.marks.favourite
                Button(favourited ? "整理收藏" : "收藏", systemImage: favourited ? "heart.fill" : "heart") {
                    favouriteRequest = FavouriteCategoryRequest(contents: [item.content])
                }
                .accessibilityIdentifier("browse.favourite")
                Button("选择多个", systemImage: "checkmark.circle") {
                    searchPresented = false
                    selectedKeys = [ContentMarks.key(item.content)]
                    selecting = true
                }
                .accessibilityIdentifier("browse.select")
                if favourited {
                    Button("取消收藏", systemImage: "heart.slash", role: .destructive) {
                        Task {
                            do { try await library.setFavourite(item.content, favourite: false) }
                            catch { showMessage(String(localized: "收藏失败"), SourceErrorText.describe(error)) }
                        }
                    }
                }
                if let url = URL(string: item.content.publicUrl), url.scheme?.hasPrefix("http") == true {
                    ShareLink(item: url) { Label("分享", systemImage: "square.and.arrow.up") }
                }
                if isLocal {
                    Button("删除文件", systemImage: "trash", role: .destructive) {
                        Task {
                            do {
                                try await model.runtime.removeLocal(content: item.content)
                                await model.reload()
                            } catch {
                                if !Task.isCancelled { showMessage(String(localized: "删除本地文件失败"), SourceErrorText.describe(error)) }
                            }
                        }
                    }
                }
            }
            footer
        }
        .navigationTitle(selecting ? String(localized: "已选择 \(selectedKeys.count) 部") : model.source.title)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(selecting)
        // The system search field under the title, as the main pages place theirs: pulling down reveals it and
        // scrolling the works hides it again.
        .searchable(text: $model.query, isPresented: $searchPresented,
                    placement: .navigationBarDrawer(displayMode: .automatic),
                    prompt: "在\(model.source.title)中搜索")
        .onSubmit(of: .search) { Task { await model.submitSearch() } }
        .onChange(of: model.query) { _, value in
            if value.isEmpty { Task { await model.clearSearch() } }
        }
        .refreshable { await model.reload() }
        .onChange(of: model.itemsRevision) { _, _ in
            let keys = Set(model.items.map(ContentMarks.key))
            selectedKeys.formIntersection(keys)
        }
        .task {
            await sources.recordUsage(sourceName: model.source.name)
            await model.start()
        }
        .toolbar {
            if selecting {
                ToolbarItem(placement: .topBarLeading) {
                    Button("退出多选", systemImage: "xmark") { clearSelection() }
                        .accessibilityIdentifier("browse.selection.cancel")
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button(selectedKeys.count == model.items.count ? "取消全选" : "全选") {
                        selectedKeys = selectedKeys.count == model.items.count ? [] : Set(model.items.map(ContentMarks.key))
                    }
                    .accessibilityIdentifier("browse.selection.all")
                    Button("收藏分类", systemImage: "heart") {
                        favouriteRequest = FavouriteCategoryRequest(
                            contents: model.items.filter { selectedKeys.contains(ContentMarks.key($0)) })
                    }
                    .disabled(selectedKeys.isEmpty)
                    .accessibilityIdentifier("browse.selection.favourite")
                }
            } else {
                ToolbarItem(placement: .topBarTrailing) { ContentListModeMenu(mode: $mode) }
                // Filtering stays on the chip at the start of the category row; the toolbar opens the source's settings.
                ToolbarItem(placement: .topBarTrailing) {
                    if isLocal {
                        Button("导入文件", systemImage: "plus") { importingFiles = true }
                            .accessibilityIdentifier("browse.importLocal")
                    } else {
                        Button("来源设置", systemImage: "gearshape") { settingsOpen = true }
                            .accessibilityIdentifier("browse.settings")
                    }
                }
            }
        }
        .sheet(item: $favouriteRequest) { request in
            FavouriteCategoriesView(contents: request.contents, onSaved: { clearSelection() })
        }
        .fileImporter(isPresented: $importingFiles, allowedContentTypes: LocalImport.contentTypes,
                      allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            Task {
                if let result = await LocalImport.run(urls, runtime: model.runtime) {
                    showMessage(String(localized: "导入本地文件"), result)
                }
                await model.reload()
            }
        }
        .alert(messageTitle, isPresented: Binding(get: { message != nil }, set: {
            if !$0 { message = nil }
        })) {
            Button("好") { message = nil }
        } message: { Text(message ?? "") }
        .sheet(isPresented: $settingsOpen, onDismiss: {
            if settingsNeedReload {
                settingsNeedReload = false
                Task { await model.reload(refreshPanel: true) }
            }
        }) {
            SourceSettingsView(runtime: model.runtime, source: model.source) { settingsNeedReload = true }
        }
        .sheet(isPresented: $editingFilter) {
            if let panel = model.panel {
                SourceFilterSheet(panel: panel, initial: model.filter) { next in Task { await model.apply(next) } }
            } else {
                FilterPanelPlaceholder(error: model.panelError) { await model.loadCategories() }
            }
        }
    }

    private func clearSelection() {
        selecting = false
        selectedKeys.removeAll()
    }

    private func showMessage(_ title: String, _ text: String) {
        messageTitle = title
        message = text
    }


    private var categoryBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    // Every source has the entry: an empty panel explains itself, a failed one can be retried.
                    filterChip
                    if !model.categories.isEmpty {
                    chip(String(localized: "全部"), selected: model.allCategoriesSelected) { await model.select(nil) }
                    ForEach(model.categories, id: \.key) { tag in
                        chip(tag.title, selected: model.selectedCategory?.key == tag.key) { await model.select(tag) }
                    }
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 6)
            }
        }
    }

    @ViewBuilder
    private var filterChip: some View {
        let button = Button { editingFilter = true } label: {
            Label(model.filter.count > 0 ? "筛选 \(model.filter.count)" : "筛选", systemImage: "line.3.horizontal.decrease")
        }
        .accessibilityIdentifier("browse.filterChip")
        if model.panel.map(model.filter.isActive) ?? false {
            button.buttonStyle(.glassProminent)
        } else {
            button.buttonStyle(.glass)
        }
    }

    @ViewBuilder
    private func chip(_ title: String, selected: Bool, action: @escaping () async -> Void) -> some View {
        let button = Button(title) { Task { await action() } }.accessibilityIdentifier("category.\(title)")
        if selected {
            button.buttonStyle(.glassProminent)
        } else {
            button.buttonStyle(.glass)
        }
    }

    @ViewBuilder
    private var footer: some View {
        if let error = model.error {
            VStack(spacing: 8) {
                Label(error, systemImage: model.challenge != nil ? "shield.lefthalf.filled" : "exclamationmark.triangle")
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("sources.error")
                if let challenge = model.challenge {
                    ChallengeVerifyButton(challenge: challenge, runtime: model.runtime, sourceName: model.source.name) {
                        await model.retry()
                    }
                }
                Button("重试") { Task { await model.retry() } }
                    .buttonStyle(.glass)
                    .accessibilityIdentifier("browse.retry")
            }
            .padding()
        } else if model.isLoading {
            ProgressView().padding()
        } else if model.items.isEmpty {
            ContentUnavailableView("没有内容", systemImage: isLocal ? "folder" : "square.grid.2x2",
                                   description: Text(!model.query.isEmpty ? "没有找到相关结果。"
                                                     : isLocal ? "点右上角“+”导入 CBZ/ZIP 漫画，或 EPUB、TXT 小说。" : "此源暂未返回内容。"))
                .accessibilityIdentifier("browse.empty")
        }
    }
}

/// 2:3 poster with the title underneath. Extension covers go through the source's client; others are plain URLs.
/// Fixed 2:3 frame: the image fills an overlay, so a cover's own aspect ratio can never resize the grid cell.
struct CoverImage: View {
    let runtime: IosSources
    let content: IosSourceContent
    /// Transparent use (panorama or preview): no placeholder fill or icon while loading or when there is no cover.
    var plain = false
    var contentMode: ContentMode = .fill
    @State private var image: UIImage?
    @State private var failed = false

    init(runtime: IosSources, content: IosSourceContent, plain: Bool = false, contentMode: ContentMode = .fill) {
        self.runtime = runtime
        self.content = content
        self.plain = plain
        self.contentMode = contentMode
        // Scrolling back re-creates lazy cells; start from the decoded cover instead of loading again.
        _image = State(initialValue: CoverCache.shared.image(for: content))
    }

    private var currentImage: UIImage? {
        image ?? CoverCache.shared.image(for: content)
    }

    var body: some View {
        let current = currentImage
        Rectangle().fill(plain ? AnyShapeStyle(.clear) : AnyShapeStyle(.quaternary))
            .overlay {
                if let current {
                    // Original colours: bars and accessories would otherwise draw the cover as a template tint.
                    Image(uiImage: current).renderingMode(.original).resizable().aspectRatio(contentMode: contentMode)
                } else if !plain && (failed || !content.hasCover) {
                    placeholder
                }
            }
            .clipped()
            .accessibilityValue(current == nil ? (failed ? "失败" : "加载中") : "已加载")
            .task(id: CoverCache.identity(content)) {
                if currentImage != nil { return }
                if let cached = CoverCache.shared.image(for: content) {
                    self.image = cached
                    return
                }
                failed = false
                let fetches = runtime.fetchesCovers(content: content)
                guard fetches ? content.hasCover : content.coverUrl.flatMap(URL.init(string:)) != nil else {
                    failed = true; return
                }
                do {
                    let decoded = try await Self.load(runtime: runtime, content: content)
                    try Task.checkCancellation()
                    store(decoded)
                } catch {
                    if !Task.isCancelled { failed = true }
                }
            }
    }

    /// One request per cover even when several views show it at once (details thumbnail and panorama). The shared
    /// request is cancelled only when every view waiting for it has gone, so scrolled-away grid cells still drop out
    /// of the cover queue.
    @MainActor private static var loading: [String: (task: Task<UIImage, Error>, waiters: Int)] = [:]

    /// The cover through the shared cache and request queue, for views that cannot run their own loading task.
    @MainActor
    static func image(runtime: IosSources, content: IosSourceContent) async throws -> UIImage {
        if let cached = CoverCache.shared.image(for: content) { return cached }
        let image = try await load(runtime: runtime, content: content)
        CoverCache.shared.store(image, for: content)
        return image
    }

    @MainActor
    private static func load(runtime: IosSources, content: IosSourceContent) async throws -> UIImage {
        if let cached = CoverCache.shared.image(for: content) { return cached }
        let key = CoverCache.identity(content)
        let task: Task<UIImage, Error>
        if let entry = loading[key] {
            task = entry.task
            loading[key]?.waiters += 1
        } else {
            task = Task.detached(priority: .userInitiated) {
                try await withReaderStorage {
                    // Check local disk thumbnail before acquiring concurrency gates or calling network/Kotlin
                    if let diskImage = CoverCache.shared.imageFromDisk(for: content) {
                        return diskImage
                    }
                    // Android's cover loader: at most eight downloads and three decodes at a time across the app.
                    // Extension covers use the source's own client in the JVM, outside its per-source call order.
                    if runtime.fetchesCovers(content: content) {
                        let artifact = try await gated(RequestGate.covers, priority: 1) { try await runtime.cover(content: content) }
                        return try await gated(RequestGate.coverDecoding, priority: 1) { try await artifact.thumbnailImage(maxPixelSize: 600) }
                    }
                    guard let url = content.coverUrl.flatMap(URL.init(string:)) else { throw URLError(.badURL) }
                    let data = try await gated(RequestGate.covers, priority: 1) { try await CoverCache.loadData(url) }
                    return try await gated(RequestGate.coverDecoding, priority: 1) { try await CoverCache.decode(data) }
                }
            }
            loading[key] = (task, 1)
        }
        let waiter = CoverWaiter()
        return try await withTaskCancellationHandler {
            defer { release(key, task, waiter) }
            return try await task.value
        } onCancel: {
            Task { @MainActor in release(key, task, waiter) }
        }
    }

    @MainActor
    private static func release(_ key: String, _ task: Task<UIImage, Error>, _ waiter: CoverWaiter) {
        guard !waiter.released, let entry = loading[key], entry.task == task else { return }
        waiter.released = true
        if entry.waiters <= 1 {
            loading[key] = nil
            task.cancel()
        } else {
            loading[key]?.waiters -= 1
        }
    }

    private func store(_ decoded: UIImage) {
        CoverCache.shared.store(decoded, for: content)
        image = decoded
    }

    private var placeholder: some View {
        Image(systemName: content.mediaKind == "VIDEO" ? "film" : content.mediaKind == "NOVEL" ? "text.book.closed" : "book.closed")
            .font(.title)
            .foregroundStyle(.secondary)
    }
}

@MainActor private final class CoverWaiter { var released = false }

/// Runs [body] while holding one of [gate]'s slots; a task cancelled while waiting never runs it.
func gated<T>(_ gate: RequestGate, priority: Int = 0, ticket: Int? = nil,
              _ body: () async throws -> T) async throws -> T {
    guard await gate.enter(priority: priority, ticket: ticket) else { throw CancellationError() }
    defer { Task { await gate.leave() } }
    try Task.checkCancellation()
    return try await body()
}

/// Decoded, downsampled covers shared by grids, lists and details.
/// Features a two-tier cache: fast in-memory NSCache (L1) and persistent JPEG disk cache (L2).
final class CoverCache: @unchecked Sendable {
    static let shared = CoverCache()
    private let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = 256 * 1024 * 1024
        cache.countLimit = 500
        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: nil
        ) { [weak cache] _ in
            cache?.removeAllObjects()
        }
        return cache
    }()

    private static let diskCacheDirectory: URL? = {
        let urls = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
        guard let base = urls.first else { return nil }
        let dir = base.appendingPathComponent("covers", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private static func hexDigest(data: Data) -> String {
        let digest = SHA256.hash(data: data)
        let hexDigits: [UInt8] = Array("0123456789abcdef".utf8)
        return String(unsafeUninitializedCapacity: 64) { buffer in
            var offset = 0
            for byte in digest {
                buffer[offset] = hexDigits[Int(byte >> 4)]
                buffer[offset + 1] = hexDigits[Int(byte & 0x0F)]
                offset += 2
            }
            return 64
        }
    }

    private static func diskFile(for url: URL) -> URL? {
        guard let dir = diskCacheDirectory else { return nil }
        let hash = hexDigest(data: Data(url.absoluteString.utf8))
        return dir.appendingPathComponent("\(hash).img")
    }

    private static func thumbnailFile(for content: IosSourceContent) -> URL? {
        guard let dir = diskCacheDirectory else { return nil }
        let id = identity(content)
        let hash = hexDigest(data: Data(id.utf8))
        return dir.appendingPathComponent("thumb_\(hash).jpg")
    }

    static func cachedData(for url: URL) -> Data? {
        guard let file = diskFile(for: url), FileManager.default.fileExists(atPath: file.path) else { return nil }
        return try? Data(contentsOf: file, options: .mappedIfSafe)
    }

    static func saveToDisk(_ data: Data, for url: URL) {
        guard let file = diskFile(for: url) else { return }
        try? data.write(to: file, options: .atomic)
    }

    static func identity(_ content: IosSourceContent) -> String {
        "\(content.sourceName)|\(content.id)|\(content.usesTrackingCover)|\(content.coverUrl ?? "")"
    }
    private func key(_ content: IosSourceContent) -> NSString { Self.identity(content) as NSString }

    func imageFromDisk(for content: IosSourceContent) -> UIImage? {
        guard let file = Self.thumbnailFile(for: content),
              FileManager.default.fileExists(atPath: file.path),
              let data = try? Data(contentsOf: file, options: .mappedIfSafe),
              let image = UIImage(data: data) else {
            return nil
        }
        return image
    }

    func image(for content: IosSourceContent) -> UIImage? {
        if let memoryImage = cache.object(forKey: key(content)) {
            return memoryImage
        }
        if let diskImage = imageFromDisk(for: content) {
            let cost = diskImage.cgImage.map { $0.bytesPerRow * $0.height } ?? Int(diskImage.size.width * diskImage.size.height * 4)
            cache.setObject(diskImage, forKey: key(content), cost: cost)
            return diskImage
        }
        return nil
    }

    func clear() {
        cache.removeAllObjects()
        if let dir = Self.diskCacheDirectory {
            try? FileManager.default.removeItem(at: dir)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
    }

    func store(_ image: UIImage, for content: IosSourceContent) {
        let cost = image.cgImage.map { $0.bytesPerRow * $0.height } ?? Int(image.size.width * image.size.height * 4)
        cache.setObject(image, forKey: key(content), cost: cost)
        guard let file = Self.thumbnailFile(for: content) else { return }
        Task.detached(priority: .utility) {
            guard !FileManager.default.fileExists(atPath: file.path) else { return }
            if let jpeg = image.jpegData(compressionQuality: 0.85) {
                try? jpeg.write(to: file, options: .atomic)
            }
        }
    }

    /// Plain-URL covers (native CMS sources) with persistent disk cache; decoding is bounded and off the main actor.
    static func loadData(_ url: URL) async throws -> Data {
        if let data = cachedData(for: url) { return data }
        let request = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad, timeoutInterval: 20)
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        saveToDisk(data, for: url)
        return data
    }

    static func decode(_ data: Data) async throws -> UIImage {
        try await Task.detached(priority: .userInitiated) {
            guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: 600,
                  ] as CFDictionary) else { throw URLError(.cannotDecodeContentData) }
            return UIImage(cgImage: image)
        }.value
    }
}

/// The filter sheet before the source described its filters: loading, or the failure with a retry.
private struct FilterPanelPlaceholder: View {
    let error: String?
    let retry: () async -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let error {
                    ContentUnavailableView {
                        Label("无法读取筛选条件", systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(error)
                    } actions: {
                        Button("重试") { Task { await retry() } }.buttonStyle(.glassProminent)
                            .accessibilityIdentifier("filter.retry")
                    }
                } else {
                    ProgressView("正在读取筛选条件…")
                }
            }
            .navigationTitle("筛选")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } }
            }
        }
        .presentationDetents([.medium])
    }
}

/// Copies picked files into the app as local works; returns a summary when something needs saying.
enum LocalImport {
    static let contentTypes: [UTType] = [
        .zip, .epub, .plainText, .movie, UTType(filenameExtension: "cbz") ?? .data, .data
    ]

    @MainActor
    static func run(_ urls: [URL], runtime: IosSources) async -> String? {
        var imported: [String] = []
        var failed: [String] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                if runtime.localFileContentType(fileName: url.lastPathComponent) == "VIDEO" {
                    try await validateVideo(url)
                }
                let content = try await runtime.importLocal(path: url.path, fileName: url.lastPathComponent)
                imported.append(content.title)
            } catch {
                failed.append("\(url.lastPathComponent)：\(SourceErrorText.describe(error))")
            }
        }
        if failed.isEmpty { return imported.count > 1 ? String(localized: "已导入 \(imported.count) 个文件") : nil }
        return (imported.isEmpty ? "" : String(localized: "已导入 \(imported.count) 个文件。\n")) + String(localized: "未能导入：\n") + failed.joined(separator: "\n")
    }

    /// Container names alone do not establish codec support. Keep an unplayable file out of the local library.
    static func validateVideo(_ url: URL) async throws {
        let asset = AVURLAsset(url: url)
        do {
            let playable = try await asset.load(.isPlayable)
            let tracks = try await asset.loadTracks(withMediaType: .video)
            try Task.checkCancellation()
            guard playable, !tracks.isEmpty else { throw URLError(.cannotDecodeContentData) }
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw NSError(domain: "org.skepsun.kototoro.local", code: 1, userInfo: [
                NSLocalizedDescriptionKey: String(localized: "系统播放器无法读取此视频。请使用支持的视频编码（例如 H.264 的 MP4/MOV 文件）。")
            ])
        }
    }
}
