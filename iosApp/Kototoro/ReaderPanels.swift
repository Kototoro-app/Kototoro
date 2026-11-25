import KototoroShared
import SwiftUI

struct ReaderChapterPanel: View {
    @ObservedObject var model: SourceReaderModel
    @EnvironmentObject private var downloads: ReaderDownloadQueue
    @EnvironmentObject private var storage: ReaderStorageModel
    let onJump: (Int) -> Void
    let onChapter: (IosSourceChapter) -> Void
    let onBookmark: (IosBookmark) -> Void
    var onSelectPage: ((IosSourceChapter, Int) -> Void)? = nil
    @State private var tab = 0
    @State private var query = ""
    @State private var branch: String?
    @State private var reverse = false
    @State private var grid = false
    @State private var downloadedOnly = false
    @State private var hideRead = false
    @State private var mergeDuplicates = false
    @State private var selecting = false
    @State private var selection: Set<String> = []
    @State private var status: String?
    @State private var busy = false
    @State private var expandedVolumes: Set<Int32> = []
    @State private var showingDownloads = false

    init(
        model: SourceReaderModel,
        initialTab: Int = 0,
        onJump: @escaping (Int) -> Void,
        onChapter: @escaping (IosSourceChapter) -> Void,
        onBookmark: @escaping (IosBookmark) -> Void,
        onSelectPage: ((IosSourceChapter, Int) -> Void)? = nil
    ) {
        self.model = model
        _tab = State(initialValue: initialTab)
        self.onJump = onJump
        self.onChapter = onChapter
        self.onBookmark = onBookmark
        self.onSelectPage = onSelectPage
    }

    private var branches: [String] { Array(Set(model.content.chapters.compactMap(\.branch))).sorted() }
    private var chapters: [IosSourceChapter] {
        var rows = model.content.chapters.filter {
            (branch == nil || $0.branch == branch) &&
            (query.isEmpty || $0.title.localizedCaseInsensitiveContains(query)) &&
            (!downloadedOnly || ReaderDownloads.isDownloaded(content: model.content, chapter: $0))
        }
        if hideRead, let current = rows.firstIndex(where: { $0.id == model.chapter.id }) { rows = Array(rows[current...]) }
        if mergeDuplicates {
            var keys: Set<String> = []
            rows = rows.filter { keys.insert($0.number > 0 ? "\($0.volume)-\($0.number)" : $0.title).inserted }
        }
        return reverse ? rows.reversed() : rows
    }

    var body: some View {
        VStack(spacing: 10) {
            Picker("内容", selection: $tab) {
                Text("章节").tag(0)
                Text("缩略图").tag(1)
                Text("书签").tag(2)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 12)

            if tab == 0 {
                HStack(spacing: 8) {
                    if branches.count > 1 {
                        Menu(branch ?? String(localized: "全部分支")) {
                            Button("全部分支") { branch = nil }
                            ForEach(branches, id: \.self) { value in Button(value) { branch = value } }
                        }
                        .font(.subheadline)
                    }
                    PanelSearchField(prompt: "搜索章节", text: $query)
                    // Filters, selection and the queue share one menu, so the list keeps the panel's height.
                    Menu {
                        Toggle("倒序", isOn: $reverse)
                        Toggle("网格", isOn: $grid)
                        Toggle("隐藏当前章之前的章节", isOn: $hideRead)
                        Toggle("仅已下载", isOn: $downloadedOnly)
                        Toggle("合并重复章节", isOn: $mergeDuplicates)
                        Divider()
                        Button(selecting ? "完成" : "多选", systemImage: "checkmark.circle") {
                            selecting.toggle(); selection = []
                        }
                        Button("下载队列", systemImage: "arrow.down.circle") { showingDownloads = true }
                            .accessibilityIdentifier("reader.chapters.downloads")
                    } label: {
                        Label("筛选", systemImage: filtering ? "line.3.horizontal.decrease.circle.fill" : "ellipsis.circle")
                            .labelStyle(.iconOnly).font(.title3).frame(width: 44, height: 44)
                    }
                    .accessibilityIdentifier("reader.chapters.options")
                }.padding(.leading, 12).padding(.trailing, 4)

                ScrollViewReader { proxy in ScrollView {
                    let read = readChapterIDs
                    if grid {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 100))], spacing: 8) {
                            ForEach(chapters, id: \.id) { chapter in chapterTile(chapter, read: read) }
                        }.padding(12)
                    } else {
                        let volumes = Array(Set(chapters.map(\.volume))).sorted()
                        LazyVStack(alignment: .leading, spacing: 0) {
                            ForEach(volumes, id: \.self) { volume in
                                // One volume reads as a plain list; several fold under their headers.
                                if volumes.count > 1 { volumeHeader(volume) }
                                if volumes.count == 1 || expandedVolumes.contains(volume) {
                                    let rows = chapters.filter { $0.volume == volume }
                                    ForEach(Array(rows.enumerated()), id: \.element.id) { index, chapter in
                                        chapterRow(chapter, read: read)
                                        // No rule against the tinted current row, whose fill already separates it.
                                        if index + 1 < rows.count, chapter.id != model.chapter.id,
                                           rows[index + 1].id != model.chapter.id {
                                            Divider().padding(.horizontal, 20)
                                        }
                                    }
                                }
                            }
                        }.padding(.horizontal, 8).padding(.bottom, 12)
                    }
                }
                // Opens on the chapter being read, like the details directory; the volume holding it is expanded first.
                .task {
                    expandedVolumes.insert(model.chapter.volume)
                    await Task.yield()
                    proxy.scrollTo(model.chapter.id, anchor: .center)
                }
                }

                if let status { Text(status).font(.caption).foregroundStyle(.secondary).padding(.horizontal, 12) }
                if selecting {
                    HStack(spacing: 4) {
                        Button("全选", systemImage: "checklist") { selection = Set(chapters.map(\.id)) }
                        Button("区间", systemImage: "arrow.up.and.down.text.horizontal") {
                            let indices = chapters.indices.filter { selection.contains(chapters[$0].id) }
                            if let low = indices.min(), let high = indices.max() { selection = Set(chapters[low...high].map(\.id)) }
                        }.disabled(selection.count < 2)
                        Spacer()
                        Text("已选 \(selection.count) 章").font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                        Spacer()
                        Button("下载", systemImage: "arrow.down.circle") { batch(download: true) }
                            .disabled(selection.isEmpty)
                        Button("移除下载", systemImage: "trash") { batch(download: false) }
                            .disabled(selection.isEmpty)
                    }
                    .labelStyle(.iconOnly).font(.title3).buttonStyle(.borderless)
                    .padding(.horizontal, 12).padding(.bottom, 10).disabled(busy)
                }
            } else if tab == 1 {
                ChapterThumbnailsView(
                    runtime: model.runtime,
                    details: model.content,
                    chapters: chapters,
                    initialChapter: model.chapter,
                    initialPages: model.pages,
                    currentReadingPage: model.index,
                    memoryImages: model.images,
                    resume: nil,
                    onSelectPage: { chapter, pageIndex in
                        if let onSelectPage {
                            onSelectPage(chapter, pageIndex)
                        } else if chapter.id == model.chapter.id {
                            onJump(pageIndex)
                        } else {
                            onChapter(chapter)
                        }
                    }
                )
            } else {
                PanelSearchField(prompt: "搜索书签", text: $query).padding(.horizontal, 12)

                ScrollView {
                    if model.bookmarks.isEmpty {
                        ContentUnavailableView("暂无书签", systemImage: "bookmark")
                    } else {
                        LazyVStack(spacing: 12) {
                            ForEach(model.bookmarks.filter { bookmark in
                                query.isEmpty || (model.content.chapters.first { $0.id == bookmark.chapterId }?.title ?? "")
                                    .localizedCaseInsensitiveContains(query)
                            }, id: \.id) { bookmark in
                                HStack {
                                    Button { onBookmark(bookmark) } label: {
                                        BookmarkPreviewLabel(runtime: model.runtime, content: model.content, bookmark: bookmark,
                                                             chapter: model.content.chapters.first { $0.id == bookmark.chapterId })
                                    }
                                    .buttonStyle(.plain)
                                    Spacer()
                                    Button("删除", systemImage: "trash") { Task { await model.removeBookmark(bookmark) } }
                                        .labelStyle(.iconOnly)
                                }
                            }
                        }.padding(12)
                    }
                }
            }
        }
        .task { branch = model.chapter.branch; await model.refreshBookmarks() }
        .onChange(of: tab) { _, _ in query = ""; selecting = false; selection = [] }
        .sheet(isPresented: $showingDownloads) { ReaderDownloadsSheet() }
    }

    /// The details directory's rule (`SourceDetailsModel.isBeforeResume`): chapters before the one being read, in source
    /// order and on its branch and line.
    private var readChapterIDs: Set<String> {
        let all = model.content.chapters, current = model.chapter
        guard let end = all.firstIndex(where: { $0.id == current.id }) else { return [] }
        return Set(all[..<end].filter { $0.branch == current.branch && $0.line == current.line }.map(\.id))
    }

    private var filtering: Bool { reverse || grid || hideRead || downloadedOnly || mergeDuplicates }

    private func volumeHeader(_ volume: Int32) -> some View {
        let expanded = expandedVolumes.contains(volume)
        return Button {
            withAnimation(.snappy) {
                if expanded { expandedVolumes.remove(volume) } else { expandedVolumes.insert(volume) }
            }
        } label: {
            HStack(spacing: 4) {
                Text(volume > 0 ? "第 \(volume) 卷" : "章节")
                Image(systemName: "chevron.down").font(.caption2.weight(.bold))
                    .rotationEffect(.degrees(expanded ? 0 : -90))
                Spacer()
            }
            .font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
            .padding(.horizontal, 8).frame(minHeight: 36).contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(expanded ? .isSelected : [])
    }

    private func toggleSelection(_ chapter: IosSourceChapter) {
        if selection.contains(chapter.id) { selection.remove(chapter.id) } else { selection.insert(chapter.id) }
    }

    /// A list row: the chapter being read is tinted and says where in it the reader is.
    private func chapterRow(_ chapter: IosSourceChapter, read: Set<String>) -> some View {
        let current = chapter.id == model.chapter.id
        let isRead = read.contains(chapter.id)
        let offline = ChapterOfflineMark.of(model.content, chapter)
        return Button {
            if selecting { toggleSelection(chapter) } else { onChapter(chapter) }
        } label: {
            HStack(spacing: 10) {
                if selecting {
                    Image(systemName: selection.contains(chapter.id) ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(selection.contains(chapter.id) ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(chapter.title)
                        .font(current ? .body.weight(.semibold) : .body)
                        .foregroundStyle(isRead && !current ? .secondary : .primary)
                        .lineLimit(2)
                    if current {
                        Text(model.pages.isEmpty ? String(localized: "正在阅读")
                             : String(localized: "正在阅读 · 第 \(model.index + 1)/\(model.pages.count) 页"))
                            .font(.caption).foregroundStyle(.tint)
                    } else if let subtitle = SourceDetailsView.chapterSubtitle(chapter) {
                        Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                if let offline {
                    Image(systemName: offline.icon).font(.caption).foregroundStyle(.secondary)
                        .accessibilityLabel(offline.label)
                }
                if isRead && !current {
                    Image(systemName: "checkmark").font(.caption).foregroundStyle(.secondary).accessibilityLabel("已读")
                }
            }
            .padding(.horizontal, 12).frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .background(current ? AnyShapeStyle(Color.accentColor.opacity(0.18)) : AnyShapeStyle(Color.clear),
                        in: .rect(cornerRadius: 12, style: .continuous))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .id(chapter.id)
        .accessibilityAddTraits(current ? .isSelected : [])
        .accessibilityIdentifier("reader.chapter.\(chapter.id)")
    }

    /// The grid layout keeps one tile per chapter.
    private func chapterTile(_ chapter: IosSourceChapter, read: Set<String>) -> some View {
        Button {
            if selecting { toggleSelection(chapter) } else { onChapter(chapter) }
        } label: {
            HStack {
                if selecting { Image(systemName: selection.contains(chapter.id) ? "checkmark.circle.fill" : "circle") }
                DetailsChapterLabel(chapter: chapter, resume: nil, isRead: read.contains(chapter.id),
                                    offline: ChapterOfflineMark.of(model.content, chapter))
            }.padding(10).frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .background(chapter.id == model.chapter.id ? AnyShapeStyle(Color.accentColor.opacity(0.18)) : AnyShapeStyle(.quaternary.opacity(0.5)),
                    in: .rect(cornerRadius: 12))
        .id(chapter.id)
        .accessibilityIdentifier("reader.chapter.\(chapter.id)")
    }

    private func batch(download: Bool) {
        let selected = chapters.filter { selection.contains($0.id) }
        if download {
            busy = true
            Task {
                defer { busy = false }
                do {
                    try await downloads.enqueue(content: model.content, chapters: selected)
                    status = String(localized: "已加入下载队列，共 \(selected.count) 章")
                } catch { status = error.localizedDescription }
            }
            return
        }
        busy = true
        Task {
            var done = 0
            do {
                for chapter in selected {
                    status = download ? String(localized: "下载 \(done + 1)/\(selected.count)") : String(localized: "移除 \(done + 1)/\(selected.count)")
                    // A removed task can still own a finishing JNI request; do not delete its destination yet.
                    guard !downloads.isFinishing(content: model.content, chapter: chapter) else {
                        if let entry = downloads.entry(content: model.content, chapter: chapter) {
                            try await downloads.pause(entry.id)
                        }
                        status = String(localized: "已暂停下载，请待当前请求结束后移除下载")
                        busy = false
                        return
                    }
                    try await storage.removeDirectory(
                        ReaderStorageMetadata.directoryID(contentID: model.content.id, chapterID: chapter.id)
                    )
                    done += 1
                }
                status = String(localized: "完成 \(done) 章")
            } catch { status = String(localized: "已完成 \(done) 章；\(error.localizedDescription)") }
            busy = false
        }
    }
}

struct ReaderMorePanel: View {
    @ObservedObject var settings: ReaderSettings
    @Binding var autoScroll: Bool
    let isBookmarked: Bool
    var translating = false
    let status: String?
    let downloading: Bool
    let onAction: (String) -> Void
    /// Called when a page translation option changes, so translated pages are redone with it.
    var onTranslationOptions: () -> Void = {}
    @State private var section = 0
    @State private var savedDefault = false

    static let actions: [(String, String, String)] = [
        ("chapters", String(localized: "章节列表"), "list.bullet"), ("thumbnails", String(localized: "缩略图"), "square.grid.2x2"),
        ("bookmark", String(localized: "添加书签"), "bookmark"),
        ("save", String(localized: "保存页面"), "square.and.arrow.down"),
        ("share", String(localized: "分享页面"), "square.and.arrow.up"), ("crop", String(localized: "裁切批注"), "crop"),
        ("translate", String(localized: "翻译页面"), "translate"),
        ("auto", String(localized: "自动翻页"), "play.circle"), ("direction", String(localized: "切换方向"), "arrow.left.arrow.right"),
        ("download", String(localized: "下载本章"), "arrow.down.circle"), ("browser", String(localized: "浏览器打开"), "safari"),
    ]
    static func actionTitle(_ id: String) -> String { actions.first { $0.0 == id }?.1 ?? id }
    static func actionSymbol(_ id: String) -> String { actions.first { $0.0 == id }?.2 ?? "circle" }
    /// The quick tab's switches; every other action is a one-shot button below them.
    private static let switches: Set<String> = ["bookmark", "auto", "translate"]
    /// Reading mode has its own control on the quick tab, so the one-shot direction flip stays a floating action.
    private static let quickActions = ["chapters", "thumbnails", "save", "share", "crop", "download", "browser"]

    private var floatingCount: Int {
        settings.value.floatingActions.filter { $0 != "bookmark" }.count + (settings.value.bookmarkButton ? 1 : 0)
    }

    var body: some View {
        VStack {
            Picker("选项", selection: $section) {
                Text("快捷").tag(0); Text("布局").tag(1); Text("显示").tag(2); Text("控件").tag(3)
                Text("翻译").tag(4)
            }.pickerStyle(.segmented).padding(.horizontal, 12)
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if section == 0 {
                        quickTab
                    } else if section == 1 {
                        layoutTab
                    } else if section == 2 {
                        displayTab
                    } else if section == 4 {
                        PageTranslationSettingsView(onChange: onTranslationOptions)
                    } else {
                        controlsTab
                    }
                }.padding(.horizontal, 12).padding(.vertical, 14)
            }
        }
    }

    @ViewBuilder private var quickTab: some View {
        HStack(spacing: 8) {
            PanelToggleTile(title: isBookmarked ? String(localized: "取消书签") : String(localized: "添加书签"),
                            symbol: isBookmarked ? "bookmark.fill" : "bookmark", isOn: isBookmarked) { onAction("bookmark") }
                .accessibilityIdentifier("reader.action.bookmark")
            PanelToggleTile(title: autoScroll ? String(localized: "停止自动翻页") : String(localized: "自动翻页"),
                            symbol: autoScroll ? "pause.circle" : "play.circle", isOn: autoScroll) { autoScroll.toggle() }
                .accessibilityIdentifier("reader.action.auto")
            PanelToggleTile(title: translating ? String(localized: "显示原文") : String(localized: "翻译页面"),
                            symbol: "translate", isOn: translating) { onAction("translate") }
                .accessibilityIdentifier("reader.action.translate")
        }
        if autoScroll {
            PanelGroup {
                Slider(value: $settings.value.autoSpeed, in: 0.01...1) {
                    Text("自动翻页速度")
                } minimumValueLabel: {
                    Image(systemName: "tortoise").accessibilityLabel("慢")
                } maximumValueLabel: {
                    Image(systemName: "hare").accessibilityLabel("快")
                }
            }
        }
        ReaderModeControl(mode: $settings.value.mode, horizontalReversed: $settings.value.horizontalReversed)
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 4), spacing: 14) {
            ForEach(Self.quickActions, id: \.self) { id in
                PanelActionButton(title: Self.actionTitle(id), symbol: Self.actionSymbol(id),
                                  status: id == "download" ? status : nil) { onAction(id) }
                    .disabled(id == "download" && downloading)
                    .accessibilityIdentifier("reader.action.\(id)")
            }
            PanelActionButton(title: String(localized: "下载队列"), symbol: "list.bullet.rectangle") { onAction("downloads") }
                .accessibilityIdentifier("reader.downloads")
        }
    }

    @ViewBuilder private var layoutTab: some View {
        PanelGroup("阅读方式") {
            PanelMenuRow(title: "阅读模式", value: ReaderModeControl.title(settings.value.mode)) {
                Picker("阅读模式", selection: $settings.value.mode) {
                    // Android's reader mode names: STANDARD, REVERSED, VERTICAL, WEBTOON, CONTINUOUS_HORIZONTAL.
                    ForEach(ReaderModeControl.modes, id: \.id) { Text($0.title).tag($0.id) }
                }
            }
            if settings.value.mode == 4 {
                Toggle("横向连续从右到左", isOn: $settings.value.horizontalReversed)
                    .accessibilityIdentifier("reader.option.horizontalReversed")
            }
            PanelMenuRow(title: "缩放基准", value: Self.fitTitles[settings.value.fit] ?? "") {
                Picker("缩放基准", selection: $settings.value.fit) {
                    ForEach(Self.fitTitles.keys.sorted(), id: \.self) { Text(Self.fitTitles[$0] ?? "").tag($0) }
                }
            }
            PanelMenuRow(title: "翻页效果", value: Self.animationTitles[settings.value.animation] ?? "") {
                Picker("翻页效果", selection: Binding(get: { settings.value.animation }, set: { settings.value.animation = $0 })) {
                    // Stored values stay stable: Android's ADVANCED (cover) and SIMULATION (curl) follow the
                    // fade and scale effects iOS already had.
                    ForEach(Self.animationTitles.keys.sorted(), id: \.self) { Text(Self.animationTitles[$0] ?? "").tag($0) }
                }
            }
            .disabled(settings.value.mode >= 3)
        }
        PanelGroup("双页", footer: "横屏或宽窗口时生效；条漫模式下不可用。") {
            Toggle("自动双页", isOn: $settings.value.doublePage).disabled(settings.value.mode >= 3)
            Toggle("封面单独显示", isOn: $settings.value.cover).disabled(!settings.value.doublePage)
            Toggle("宽页左右拆分", isOn: $settings.value.splitWidePages).disabled(settings.value.mode >= 3)
        }
        PanelGroup("页面") {
            Toggle("自动裁切白边", isOn: $settings.value.cropMargins)
        }
    }

    @ViewBuilder private var displayTab: some View {
        PanelGroup("背景") {
            Picker("背景", selection: $settings.value.background) {
                Text("黑色").tag(0); Text("白色").tag(1); Text("纸色").tag(2)
            }
            .pickerStyle(.segmented)
        }
        PanelGroup("画面") {
            Toggle("反色", isOn: $settings.value.inverted)
            Toggle("灰度", isOn: $settings.value.grayscale)
            Toggle("书页阴影", isOn: $settings.value.bookEffect)
        }
        PanelGroup("亮度与对比度") {
            Slider(value: $settings.value.brightness, in: -0.5...0.5) {
                Text("亮度")
            } minimumValueLabel: {
                Image(systemName: "sun.min")
            } maximumValueLabel: {
                Image(systemName: "sun.max")
            }
            Slider(value: $settings.value.contrast, in: 0.5...2) {
                Text("对比度")
            } minimumValueLabel: {
                Image(systemName: "circle.lefthalf.filled").opacity(0.5)
            } maximumValueLabel: {
                Image(systemName: "circle.lefthalf.filled").opacity(1)
            }
        }
    }

    @ViewBuilder private var controlsTab: some View {
        PanelGroup {
            Toggle("阅读时保持屏幕常亮", isOn: $settings.value.keepScreenOn)
                .accessibilityIdentifier("reader.option.keepScreenOn")
            Toggle("从底部展开面板", isOn: $settings.value.bottomPanels)
        }
        PanelGroup("悬浮按钮 · \(floatingCount)/4", footer: "最多四个，显示在进度条上方。") {
            Toggle("书签", isOn: $settings.value.bookmarkButton)
                .disabled(!settings.value.bookmarkButton && floatingCount >= 4)
            ForEach(Self.actions.filter { $0.0 != "bookmark" }, id: \.0) { action in
                Toggle(action.1, isOn: Binding(
                    get: { settings.value.floatingActions.contains(action.0) },
                    set: { enabled in
                        if enabled { settings.value.floatingActions.append(action.0) }
                        else { settings.value.floatingActions.removeAll { $0 == action.0 } }
                    }
                )).disabled(!settings.value.floatingActions.contains(action.0) && floatingCount >= 4)
            }
        }
        PanelGroup("手势", footer: "左右两侧或上下边缘翻页，中间显示或隐藏控件；双击缩放，双指捏合缩放。关闭边缘滑动返回后，可从屏幕边缘滑动翻页，点按画面显示控件后用返回按钮退出。") {
            Toggle("点击屏幕边缘翻页", isOn: $settings.value.tapZones)
                .accessibilityIdentifier("reader.option.tapZones")
            Toggle("反转点击区域", isOn: $settings.value.reverseTaps).disabled(!settings.value.tapZones)
            Toggle("边缘滑动返回", isOn: $settings.value.edgeSwipeBack)
                .accessibilityIdentifier("reader.option.edgeSwipeBack")
        }
        VStack(alignment: .leading, spacing: 6) {
            Button(savedDefault ? "已设为全局默认" : "将当前设置设为全局默认") {
                settings.saveAsDefault(); savedDefault = true
            }
            .buttonStyle(.glass).frame(maxWidth: .infinity)
            Text("其他设置自动保存在本作品。").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 16)
        }
    }

    private static let fitTitles: [Int: String] = [
        0: String(localized: "适应页面"), 1: String(localized: "适应宽度"), 2: String(localized: "适应高度"),
        3: String(localized: "保持起始位置"),
    ]
    private static let animationTitles: [Int: String] = [
        0: String(localized: "禁用"), 1: String(localized: "默认"), 2: String(localized: "淡入淡出"), 3: String(localized: "缩放"),
        4: String(localized: "高级"), 5: String(localized: "仿真"),
    ]
}

// MARK: - Panel parts shared by the manga and novel readers

/// A search field for the glass panels: the system's filled look, which a bordered text field does not have.
struct PanelSearchField: View {
    let prompt: LocalizedStringKey
    @Binding var text: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField(prompt, text: $text).textFieldStyle(.plain).submitLabel(.search)
            if !text.isEmpty {
                Button("清除", systemImage: "xmark.circle.fill") { text = "" }
                    .labelStyle(.iconOnly).foregroundStyle(.secondary).buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 10).frame(minHeight: 36)
        .background(.fill.tertiary, in: .rect(cornerRadius: 10, style: .continuous))
    }
}

/// An inset group of rows inside a glass panel, drawn as a grouped list draws its sections; the panel keeps scrolling
/// as one view, so it stays a scroll view rather than a nested Form.
struct PanelGroup<Content: View>: View {
    let title: LocalizedStringKey?
    let footer: LocalizedStringKey?
    @ViewBuilder let content: Content

    init(_ title: LocalizedStringKey? = nil, footer: LocalizedStringKey? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.footer = footer
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let title {
                Text(title).font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 16)
            }
            VStack(spacing: 0) {
                Group(subviews: content) { rows in
                    ForEach(rows) { row in
                        row.padding(.horizontal, 14).padding(.vertical, 6).frame(minHeight: 44)
                        if row.id != rows.last?.id { Divider().padding(.leading, 14) }
                    }
                }
            }
            .background(.fill.quaternary, in: .rect(cornerRadius: 14, style: .continuous))
            if let footer {
                Text(footer).font(.caption).foregroundStyle(.secondary).padding(.horizontal, 16)
            }
        }
    }
}

/// A row that names a setting, shows its value and opens the choices as a menu.
struct PanelMenuRow<Choices: View>: View {
    let title: LocalizedStringKey
    let value: String
    @ViewBuilder let choices: Choices

    var body: some View {
        Menu {
            choices
        } label: {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                Text(value).foregroundStyle(.secondary)
                Image(systemName: "chevron.up.chevron.down").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// A switch on a quick tab, after Control Center: the accent fills it while it is on.
struct PanelToggleTile: View {
    let title: String
    let symbol: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: symbol).font(.title3)
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.8)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .foregroundStyle(isOn ? Color.white : Color.primary)
            .background(isOn ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.fill.tertiary),
                        in: .rect(cornerRadius: 16, style: .continuous))
            .contentShape(.rect(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

/// A one-shot action on a quick tab: a glyph in a filled circle over its name, and a short state when there is one.
struct PanelActionLabel: View {
    let title: String
    let symbol: String
    var status: String? = nil

    var body: some View {
        VStack(spacing: 5) {
            Image(systemName: symbol).font(.title3)
                .frame(width: 50, height: 50)
                .background(.fill.tertiary, in: .circle)
            Text(title).font(.caption).lineLimit(1).minimumScaleFactor(0.75)
            if let status {
                Text(status).font(.caption2).foregroundStyle(.secondary).lineLimit(2).multilineTextAlignment(.center)
            }
        }
        .foregroundStyle(.primary)
        .frame(maxWidth: .infinity)
        .contentShape(.rect)
    }
}

struct PanelActionButton: View {
    let title: String
    let symbol: String
    var status: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) { PanelActionLabel(title: title, symbol: symbol, status: status) }
            .buttonStyle(.plain)
            .accessibilityLabel(title)
            .accessibilityValue(status ?? "")
    }
}

/// The quick tab's reading mode: five glyphs on one track, the chosen one lifted onto the accent and named above.
struct ReaderModeControl: View {
    @Binding var mode: Int
    @Binding var horizontalReversed: Bool
    @Namespace private var selection

    static let modes: [(id: Int, title: String, symbol: String)] = [
        (0, String(localized: "从左到右"), "arrow.right.square"),
        (1, String(localized: "从右到左"), "arrow.left.square"),
        (2, String(localized: "从上到下"), "arrow.down.square"),
        (3, String(localized: "条漫"), "rectangle.grid.1x2"),
        (4, String(localized: "横向条漫（连续）"), "rectangle.split.3x1"),
    ]

    static func title(_ mode: Int) -> String { modes.first { $0.id == mode }?.title ?? "" }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("翻页方式").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                Spacer()
                Text(Self.title(mode)).font(.footnote).foregroundStyle(.secondary)
                    .contentTransition(.opacity)
            }
            .padding(.horizontal, 4)
            HStack(spacing: 2) {
                ForEach(Self.modes, id: \.id) { item in
                    let chosen = item.id == mode
                    Button {
                        withAnimation(.snappy(duration: 0.25)) { mode = item.id }
                    } label: {
                        Image(systemName: item.symbol).font(.title3)
                            .foregroundStyle(chosen ? Color.white : Color.primary)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background {
                                if chosen {
                                    Capsule().fill(Color.accentColor).matchedGeometryEffect(id: "mode", in: selection)
                                }
                            }
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(item.title)
                    .accessibilityAddTraits(chosen ? .isSelected : [])
                    .accessibilityIdentifier("reader.quickMode.\(item.id)")
                }
            }
            .padding(4)
            .background(.fill.tertiary, in: Capsule())
            .sensoryFeedback(.selection, trigger: mode)
            if mode == 4 {
                Toggle("横向连续从右到左", isOn: $horizontalReversed).font(.subheadline).padding(.horizontal, 4)
                    .accessibilityIdentifier("reader.quick.horizontalReversed")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("翻页方式")
    }
}

struct ReaderCropView: View {
    let image: UIImage
    let onSave: (UIImage) -> Void
    /// Saves the crop as a note: the picture, where it sits on the page (0–1) and the written thought.
    var onNote: ((UIImage, CGRect, String) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var note = ""
    @State private var start: CGPoint?
    @State private var rect = CGRect.zero
    @State private var strokes: [[CGPoint]] = []
    @State private var annotate = false
    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                let fit = min(geometry.size.width / image.size.width, geometry.size.height / image.size.height)
                let size = CGSize(width: image.size.width * fit, height: image.size.height * fit)
                ZStack(alignment: .topLeading) {
                    Image(uiImage: image).resizable().frame(width: size.width, height: size.height)
                    Canvas { context, _ in
                        for points in strokes where !points.isEmpty {
                            var path = Path(); path.addLines(points); context.stroke(path, with: .color(.red), lineWidth: 3)
                        }
                    }
                    if !rect.isEmpty { Rectangle().stroke(.yellow, style: StrokeStyle(lineWidth: 2, dash: [6])).frame(width: rect.width, height: rect.height).offset(x: rect.minX, y: rect.minY) }
                }.frame(width: size.width, height: size.height).clipped()
                    .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                        if annotate {
                            if start == nil { strokes.append([]); start = value.startLocation }
                            strokes[strokes.count - 1].append(value.location)
                        } else {
                            start = value.startLocation
                            rect = CGRect(x: min(value.startLocation.x, value.location.x), y: min(value.startLocation.y, value.location.y),
                                          width: abs(value.translation.width), height: abs(value.translation.height)).intersection(CGRect(origin: .zero, size: size))
                        }
                    }.onEnded { _ in start = nil })
                    .toolbar {
                        Toggle("批注", isOn: $annotate)
                        Button("重置") { rect = .zero; strokes = [] }
                        Button("导出") { onSave(render(fit: fit).image); dismiss() }
                    }
                    .safeAreaInset(edge: .bottom) {
                        if let onNote {
                            HStack {
                                TextField("写下想法（可选）", text: $note)
                                    .textFieldStyle(.roundedBorder)
                                    .accessibilityIdentifier("crop.note")
                                Button("存为笔记") {
                                    let result = render(fit: fit)
                                    let unit = CGRect(x: result.rect.minX / size.width, y: result.rect.minY / size.height,
                                                      width: result.rect.width / size.width, height: result.rect.height / size.height)
                                    onNote(result.image, unit, note)
                                    dismiss()
                                }
                                .buttonStyle(.glassProminent)
                                .accessibilityIdentifier("crop.saveNote")
                            }
                            .padding(.vertical, 8)
                        }
                    }
            }.padding()
                .navigationTitle("裁切与批注")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
        }
    }
    /// The marked page cut to the selection (the whole page without one) and the selection in view points.
    private func render(fit: CGFloat) -> (image: UIImage, rect: CGRect) {
        let renderer = UIGraphicsImageRenderer(size: image.size)
        let marked = renderer.image { context in
            image.draw(at: .zero)
            context.cgContext.setStrokeColor(UIColor.red.cgColor); context.cgContext.setLineWidth(3 / fit)
            for points in strokes where !points.isEmpty {
                context.cgContext.beginPath()
                context.cgContext.move(to: CGPoint(x: points[0].x / fit, y: points[0].y / fit))
                for point in points.dropFirst() { context.cgContext.addLine(to: CGPoint(x: point.x / fit, y: point.y / fit)) }
                context.cgContext.strokePath()
            }
        }
        let whole = CGRect(origin: .zero, size: CGSize(width: image.size.width * fit, height: image.size.height * fit))
        guard !rect.isEmpty, let cg = marked.cgImage,
              let crop = cg.cropping(to: CGRect(x: rect.minX / fit * marked.scale, y: rect.minY / fit * marked.scale,
                                                width: rect.width / fit * marked.scale, height: rect.height / fit * marked.scale)) else {
            return (marked, whole)
        }
        return (UIImage(cgImage: crop), rect)
    }
}
