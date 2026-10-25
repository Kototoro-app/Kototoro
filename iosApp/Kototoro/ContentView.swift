import SwiftUI
import KototoroShared

struct ContentView: View {
    private enum MainTab: Hashable { case library, browse, records, settings, search }
    @ObservedObject var library: LibraryModel
    @StateObject private var sources = SourcesModel()
    @StateObject private var storage = ReaderStorageModel()
    @StateObject private var downloads = ReaderDownloadQueue(
        file: NativeSourceTransport.downloadQueueFile
    )
    @StateObject private var repositories = RepositoriesModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var incomingLink: IncomingRepositoryLink?
    @State private var openedFileMessage: String?
    @State private var globalSearchQuery = ""
    @State private var selectedTab = MainTab.library
    @State private var resumed: CategoryContent?
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true

    private var resumeLatest: IosLibraryEntry? {
        ContentSpace.latest(in: library.history, enabled: spacesEnabled, space: space)
    }

    var body: some View {
        let latest = resumeLatest
        TabView(selection: $selectedTab) {
            Tab("作品库", systemImage: "books.vertical", value: MainTab.library) {
                LibraryView(library: library, sources: sources)
            }
            Tab("浏览", systemImage: "safari", value: MainTab.browse) {
                SourcesView(model: sources)
            }
            Tab("记录", systemImage: "clock.arrow.circlepath", value: MainTab.records) {
                LibraryRecordsView()
            }
            Tab("设置", systemImage: "gearshape", value: MainTab.settings) {
            NavigationStack {
                List {
                    Section {
                        NavigationLink { RepositoriesView() } label: {
                            Label("仓库与扩展", systemImage: "shippingbox")
                        }
                        // The tab already carries the count; the row repeats it with the system badge only.
                        .badge(repositories.updatesCount)
                        NavigationLink { ReaderDownloadsView() } label: { Label("下载", systemImage: "arrow.down.circle") }
                            .accessibilityIdentifier("settings.downloads")
                        NavigationLink { DownloadSettingsView() } label: { Label("下载设置", systemImage: "slider.horizontal.3") }
                            .accessibilityIdentifier("settings.downloadSettings")
                        NavigationLink { BackupView() } label: { Label("备份与恢复", systemImage: "externaldrive") }
                            .accessibilityIdentifier("settings.backup")
                        NavigationLink { GoogleDriveSyncView(model: library.googleDrive) } label: {
                            Label("Google Drive 同步", systemImage: "arrow.triangle.2.circlepath.icloud")
                        }.accessibilityIdentifier("settings.googleDrive")
                        NavigationLink { WebDavSyncView() } label: {
                            Label("WebDAV 备份", systemImage: "externaldrive.connected.to.line.below")
                        }.accessibilityIdentifier("settings.webdav")
                        NavigationLink { TrackingSitesView() } label: { Label("追踪网站", systemImage: "link") }
                            .accessibilityIdentifier("settings.tracking")
                    }
                    Section("更新") { BackgroundUpdateSettingsRows() }
                    Section("网络") {
                        NavigationLink { GitHubMirrorView() } label: { Label("GitHub 镜像", systemImage: "arrow.triangle.branch") }
                            .accessibilityIdentifier("settings.githubMirror")
                    }
                    Section("本地存储") {
                        NavigationLink { ReaderStorageView() } label: { Label("存储管理", systemImage: "internaldrive") }
                            .accessibilityIdentifier("settings.storage")
                        if let version = library.schemaVersion {
                            LabeledContent("数据库版本", value: "\(version)")
                                .accessibilityIdentifier("database.ready")
                        } else {
                            Text("正在打开数据库…")
                        }
                    }
                    Section("内容过滤") {
                        NavigationLink { TagBlacklistView() } label: {
                            Label("标签黑名单", systemImage: "tag.slash")
                        }
                        .badge(TagFilterStore.shared.selected.count)
                        .accessibilityIdentifier("settings.tagBlacklist")
                        ReadingIncognitoSettingsRows()
                        AppLockToggle()
                        NavigationLink { AdultContentFilterView() } label: {
                            Label("成人内容过滤", systemImage: "eye.slash")
                        }
                        .accessibilityIdentifier("settings.adultFilter")
                    }
                    if let rules = library.library?.replaceRules, let notes = library.library?.notes {
                        Section("阅读") {
                            NavigationLink { NotesOverviewView(notes: notes) } label: {
                                Label("笔记", systemImage: "note.text")
                            }
                            .accessibilityIdentifier("settings.notes")
                            NavigationLink { ReplaceRulesView(rules: rules) } label: {
                                Label("文本替换规则", systemImage: "text.badge.checkmark")
                            }
                            .accessibilityIdentifier("settings.replaceRules")
                        }
                    }
                    ContentSpaceSettingsSection()
                    PanoramaSettingsSection()
                    ReaderLoadingSettingsSection()
                    Section("关于") {
                        LabeledContent("应用", value: "Kototoro")
                        LabeledContent("版本", value: String(localized: "iOS 开发版"))
                    }
                }
                .navigationTitle("设置")
            }
            }
            .badge(repositories.updatesCount)
            // The system's separate search button at the trailing end of the tab bar (iOS 26).
            Tab(value: MainTab.search, role: .search) {
                GlobalSearchView(query: $globalSearchQuery)
            }
        }
        // The global search belongs to its own tab: `.searchable` on the TabView is inherited by every tab's
        // navigation bar and showed a second search field under the library and browse titles.
        .tabViewSearchActivation(.searchTabSelection)
        .tabViewStyle(.sidebarAdaptable)
        .modifier(MainTabPlacement())
        .tabBarMinimizeBehavior(.onScrollDown)
        .continueReadingAccessory(
            enabled: !ReaderPresence.shared.shouldSuppressAccessory && (spacesEnabled || latest != nil)
        )
        .spaceSwitcherPresentation { resumed = $0 }
        .continueReadingPresentation(opened: $resumed)
        .environment(\.openContinueReading) { resumed = $0 }
        .task(id: "\(library.recordsRevision)|\(library.history.count)|\(sources.runtime != nil)") {
            await WidgetSnapshot.write(history: library.history, runtime: sources.runtime)
        }
        .task(id: "\(latest?.content.sourceName ?? "")|\(latest?.content.id ?? "")|\(sources.runtime != nil)") {
            ContinueReadingCover.shared.image = nil
            guard let latest, let runtime = sources.runtime else { return }
            ContinueReadingCover.shared.image = try? await CoverImage.image(runtime: runtime, content: latest.content)
        }
        .environment(\.openGlobalSearch) { selectedTab = .search }
        .environmentObject(library)
        .environmentObject(sources)
        .environmentObject(downloads)
        .environmentObject(storage)
        .environmentObject(repositories)
        .task(id: library.library != nil) {
            await library.googleDrive.sync(library, automatic: true)
        }
        .task(id: "\(library.recordsRevision)|\(sources.isLoading)|\(sources.catalogRevision)") {
            guard sources.runtime != nil, !sources.isLoading else { return }
            await library.refreshMissingSources(installed: sources.sources.map(\.name))
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { downloads.setForeground(false); BackgroundUpdates.shared.schedule() }
            else if phase == .active {
                downloads.setForeground(true)
                Task { await repositories.checkForUpdates(force: false) }
                Task { await library.googleDrive.sync(library, automatic: true) }
                Task { await WebDavConfig.backUpIfDue(library.library) }
            }
        }
        .onOpenURL { url in
            // ASWebAuthenticationSession owns these callbacks; they must never open the repository importer.
            guard !TrackingAccountsModel.isAuthorizationCallback(url) else { return }
            guard url.scheme != GoogleDriveOAuth.configured?.scheme else { return }
            // The home-screen widget opens a work of the history (kototoro://continue?key=…).
            if url.scheme == "kototoro", url.host == "continue" {
                let key = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "key" }?.value
                if let entry = library.history.first(where: { ContentMarks.key($0.content) == key }) {
                    resumed = CategoryContent(content: entry.content)
                }
                return
            }
            // Files shared to the app ("用 Kototoro 打开") become local works; links add repositories.
            guard url.isFileURL else { incomingLink = IncomingRepositoryLink(address: url.absoluteString); return }
            guard let runtime = sources.runtime else { return }
            Task {
                openedFileMessage = await LocalImport.run([url], runtime: runtime)
                    ?? String(localized: "已导入“\(url.deletingPathExtension().lastPathComponent)”，可在“浏览 · 本地作品”中查看")
            }
        }
        .alert("导入本地文件", isPresented: Binding(get: { openedFileMessage != nil },
                                                   set: { if !$0 { openedFileMessage = nil } })) {
            Button("好") { openedFileMessage = nil }
        } message: {
            Text(openedFileMessage ?? "")
        }
        .sheet(item: $incomingLink) { link in
            NavigationStack {
                RepositoriesView(incoming: link.address)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { incomingLink = nil } } }
            }
            // A sheet on this modifier chain sits outside the injected environment; pass the models explicitly.
            .environmentObject(library)
            .environmentObject(sources)
            .environmentObject(downloads)
            .environmentObject(storage)
            .environmentObject(repositories)
        }
        .task(id: library.library == nil) {
            guard let database = library.library else { return }
            await storage.prepare(queue: downloads)
            sources.attach(database)
            repositories.attach(library: database, sources: sources)
            if let runtime = sources.runtime { downloads.attach(runtime: runtime) }
            await sources.load()
            await library.refresh()
            await storage.trimAutomatically()
            await CustomSpaceStore.shared.load(database)
            BackgroundUpdates.shared.library = library
            BackgroundUpdates.shared.sources = sources
            if ProcessInfo.processInfo.environment["KOTOTORO_RUN_UPDATE_REFRESH"] == "1" { await BackgroundUpdates.shared.run() }
            await repositories.checkForUpdates(force: false)
        }
    }
}

private struct MainTabPlacement: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 27.0, *) {
            content.defaultTabBarPlacement(.sidebar)
        } else {
            content.defaultAdaptableTabBarPlacement(.sidebar)
        }
    }
}

struct FavouriteCategoryRequest: Identifiable {
    let id = UUID()
    let contents: [IosSourceContent]
}

struct FavouriteCategoriesView: View {
    let contents: [IosSourceContent]
    let onSaved: (() -> Void)?
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.dismiss) private var dismiss
    @State private var memberships: [String: Set<Int32>] = [:]
    @State private var changes: [Int32: Bool] = [:]
    @State private var loading = true
    @State private var error: String?
    @State private var saving = false
    @State private var addingCategory = false
    @State private var categoryTitle = ""

    init(content: IosSourceContent) { contents = [content]; onSaved = nil }
    init(contents: [IosSourceContent], onSaved: (() -> Void)? = nil) {
        self.contents = contents
        self.onSaved = onSaved
    }

    private enum CheckState { case none, partial, all }

    private func checkState(_ id: Int32) -> CheckState {
        if let changed = changes[id] { return changed ? .all : .none }
        let count = contents.filter { memberships[ContentMarks.key($0)]?.contains(id) == true }.count
        return count == 0 ? .none : count == contents.count ? .all : .partial
    }

    private func toggle(_ id: Int32) {
        let checked = checkState(id) != .all
        let count = contents.filter { memberships[ContentMarks.key($0)]?.contains(id) == true }.count
        if (checked && count == contents.count) || (!checked && count == 0) { changes.removeValue(forKey: id) }
        else { changes[id] = checked }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(contents.count == 1 ? contents[0].title : String(localized: "已选择 \(contents.count) 部作品"))
                        .font(.headline)
                    if loading { ProgressView("正在读取分类…") }
                    else {
                        if library.categories.isEmpty { Text("暂无分类，请先新建分类。") .foregroundStyle(.secondary) }
                        ForEach(library.categories, id: \.id) { category in
                            let state = checkState(category.id)
                            Button { toggle(category.id) } label: {
                                HStack {
                                    Text(category.title).foregroundStyle(.primary)
                                    Spacer()
                                    Image(systemName: state == .all ? "checkmark.circle.fill"
                                          : state == .partial ? "minus.circle.fill" : "circle")
                                        .foregroundStyle(state == .none ? Color.secondary : Color.accentColor)
                                        .accessibilityHidden(true)
                                }
                                .contentShape(Rectangle())
                            }
                            .accessibilityIdentifier("favourite.category.\(category.id)")
                            .accessibilityValue(state == .all ? "已选中" : state == .partial ? "部分选中" : "未选中")
                            .accessibilityAddTraits(state == .all ? .isSelected : [])
                        }
                    }
                }
                Button("新建分类", systemImage: "folder.badge.plus") { categoryTitle = ""; addingCategory = true }
                    .accessibilityIdentifier("favourite.category.add")
                    .disabled(loading || saving)
                if let error { Text(error).foregroundStyle(.red) }
                Text("可选择多个分类。未操作的分类保持原样；移除作品的全部分类会取消收藏。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            .disabled(saving)
            .navigationTitle("收藏分类")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }.disabled(saving).accessibilityIdentifier("favourite.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        saving = true
                        Task {
                            do {
                                try await library.updateCategories(contents, changes: changes)
                                onSaved?()
                                dismiss()
                            } catch { self.error = error.localizedDescription }
                            saving = false
                        }
                    }.disabled(saving || loading || changes.isEmpty)
                        .accessibilityIdentifier("favourite.save")
                }
            }
            .task {
                do {
                    guard let database = library.library else { throw CancellationError() }
                    let entries = try await database.favourites()
                    memberships = Dictionary(entries.map { (ContentMarks.key($0.content), Set($0.categoryIds.map(\.int32Value))) },
                                             uniquingKeysWith: { first, _ in first })
                    loading = false
                }
                catch { self.error = error.localizedDescription }
            }
            .interactiveDismissDisabled(saving)
            .alert("新建分类", isPresented: $addingCategory) {
                TextField("分类名称", text: $categoryTitle).accessibilityIdentifier("favourite.category.title")
                Button("取消", role: .cancel) {}
                Button("创建") {
                    saving = true
                    Task {
                        let known = Set(library.categories.map(\.id))
                        if await library.addCategory(categoryTitle) {
                            if let category = library.categories.first(where: { !known.contains($0.id) }) {
                                changes[category.id] = true
                            }
                        } else { error = library.error }
                        saving = false
                    }
                }.disabled(categoryTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }
}

private struct IncomingRepositoryLink: Identifiable {
    let id = UUID()
    let address: String
}
