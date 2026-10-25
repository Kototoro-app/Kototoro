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
                HStack {
                    if branches.count > 1 {
                        Menu(branch ?? String(localized: "全部分支")) {
                            Button("全部分支") { branch = nil }
                            ForEach(branches, id: \.self) { value in Button(value) { branch = value } }
                        }
                    }
                    TextField("搜索章节", text: $query).textFieldStyle(.roundedBorder)
                    Menu("筛选", systemImage: "line.3.horizontal.decrease") {
                        Toggle("倒序", isOn: $reverse)
                        Toggle("网格", isOn: $grid)
                        Toggle("隐藏当前章之前的章节", isOn: $hideRead)
                        Toggle("仅已下载", isOn: $downloadedOnly)
                        Toggle("合并重复章节", isOn: $mergeDuplicates)
                    }.labelStyle(.iconOnly)
                    Button(selecting ? "完成" : "多选") { selecting.toggle(); selection = [] }
                }.padding(.horizontal, 12)

                ScrollViewReader { proxy in ScrollView {
                    let read = readChapterIDs
                    if grid {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 100))], spacing: 8) {
                            ForEach(chapters, id: \.id) { chapter in chapterButton(chapter, read: read) }
                        }.padding(12)
                    } else {
                        LazyVStack(alignment: .leading, spacing: 10) {
                            ForEach(Array(Set(chapters.map(\.volume))).sorted(), id: \.self) { volume in
                                DisclosureGroup(volume > 0 ? "第 \(volume) 卷" : "章节", isExpanded: Binding(
                                    get: { expandedVolumes.contains(volume) },
                                    set: { if $0 { expandedVolumes.insert(volume) } else { expandedVolumes.remove(volume) } }
                                )) {
                                    ForEach(chapters.filter { $0.volume == volume }, id: \.id) { chapter in chapterButton(chapter, read: read) }
                                }
                            }
                        }.padding(12)
                    }
                }
                // Opens on the chapter being read, like the details directory; the volume holding it is expanded first.
                .task {
                    expandedVolumes.insert(model.chapter.volume)
                    await Task.yield()
                    proxy.scrollTo(model.chapter.id, anchor: .center)
                }
                }

                if let status { Text(status).font(.caption).padding(.horizontal, 12) }
                Button("下载队列", systemImage: "arrow.down.circle") { showingDownloads = true }
                    .accessibilityIdentifier("reader.downloads")
                if selecting {
                    HStack {
                        Button("全选") { selection = Set(chapters.map(\.id)) }
                        Button("区间") {
                            let indices = chapters.indices.filter { selection.contains(chapters[$0].id) }
                            if let low = indices.min(), let high = indices.max() { selection = Set(chapters[low...high].map(\.id)) }
                        }.disabled(selection.count < 2)
                        Spacer()
                        Button("下载") { batch(download: true) }
                            .disabled(selection.isEmpty)
                        Button("移除下载") { batch(download: false) }
                            .disabled(selection.isEmpty)
                    }.padding(12).disabled(busy)
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
                HStack {
                    TextField("搜索书签", text: $query).textFieldStyle(.roundedBorder)
                }.padding(.horizontal, 12)

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

    private func chapterButton(_ chapter: IosSourceChapter, read: Set<String>) -> some View {
        Button {
            if selecting {
                if selection.contains(chapter.id) { selection.remove(chapter.id) } else { selection.insert(chapter.id) }
            } else { onChapter(chapter) }
        } label: {
            HStack {
                if selecting { Image(systemName: selection.contains(chapter.id) ? "checkmark.circle.fill" : "circle") }
                DetailsChapterLabel(chapter: chapter, resume: nil, isRead: read.contains(chapter.id),
                                    offline: ChapterOfflineMark.of(model.content, chapter))
                if chapter.id == model.chapter.id { Image(systemName: "book.fill") }
            }.padding(10).frame(maxWidth: .infinity, alignment: .leading)
        }.buttonStyle(.plain).background(.quaternary.opacity(0.5), in: .rect(cornerRadius: 12))
            .accessibilityIdentifier("reader.chapter.\(chapter.id)")
    }

    private func batch(download: Bool) {
        let selected = chapters.filter { selection.contains($0.id) }
        if download {
            do {
                try downloads.enqueue(content: model.content, chapters: selected)
                status = String(localized: "已加入下载队列，共 \(selected.count) 章")
            } catch { status = error.localizedDescription }
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
                        if let entry = downloads.entry(content: model.content, chapter: chapter) { try downloads.pause(entry.id) }
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
    var body: some View {
        VStack {
            Picker("选项", selection: $section) {
                Text("快捷").tag(0); Text("布局").tag(1); Text("显示").tag(2); Text("控件").tag(3)
            }.pickerStyle(.segmented).padding(.horizontal, 12)
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if section == 0 {
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                            ForEach(Self.actions, id: \.0) { action in
                                Button {
                                    if action.0 == "auto" { autoScroll.toggle() } else { onAction(action.0) }
                                } label: {
                                    Label(action.0 == "auto" && autoScroll ? String(localized: "停止自动翻页") :
                                          action.0 == "bookmark" && isBookmarked ? String(localized: "取消书签") :
                                          action.0 == "translate" && translating ? String(localized: "显示原文") : action.1,
                                          systemImage: action.0 == "bookmark" && isBookmarked ? "bookmark.fill" : action.2)
                                        .font(.subheadline).frame(maxWidth: .infinity, minHeight: 52)
                                }.buttonStyle(.glass).disabled(action.0 == "download" && downloading)
                                    .accessibilityIdentifier("reader.action.\(action.0)")
                            }
                        }
                        if let status { Text(status).font(.caption).foregroundStyle(.secondary) }
                        Button("下载队列", systemImage: "arrow.down.circle") { onAction("downloads") }
                            .accessibilityIdentifier("reader.downloads")
                        LabeledContent("自动翻页速度") { Slider(value: $settings.value.autoSpeed, in: 0.01...1).frame(width: 140) }
                    } else if section == 1 {
                        Picker("阅读模式", selection: $settings.value.mode) {
                            // Android's reader mode names: STANDARD, REVERSED, VERTICAL, WEBTOON, CONTINUOUS_HORIZONTAL.
                            Text("从左到右").tag(0); Text("从右到左").tag(1); Text("从上到下").tag(2)
                            Text("条漫").tag(3); Text("横向条漫（连续）").tag(4)
                        }
                        if settings.value.mode == 4 {
                            Toggle("横向连续从右到左", isOn: $settings.value.horizontalReversed)
                                .accessibilityIdentifier("reader.option.horizontalReversed")
                        }
                        Picker("缩放基准", selection: $settings.value.fit) {
                            Text("适应页面").tag(0); Text("适应宽度").tag(1); Text("适应高度").tag(2); Text("保持起始位置").tag(3)
                        }
                        Toggle("自动双页（横屏或宽窗口）", isOn: $settings.value.doublePage).disabled(settings.value.mode >= 3)
                        Toggle("封面单独显示", isOn: $settings.value.cover).disabled(!settings.value.doublePage)
                        Toggle("宽页左右拆分", isOn: $settings.value.splitWidePages)
                            .disabled(settings.value.mode >= 3)
                        Toggle("自动裁切白边", isOn: $settings.value.cropMargins)
                        Picker("翻页效果", selection: Binding(get: { settings.value.animation }, set: { settings.value.animation = $0 })) {
                            // Stored values stay stable: Android's ADVANCED (cover) and SIMULATION (curl) follow the
                            // fade and scale effects iOS already had.
                            Text("禁用").tag(0); Text("默认").tag(1); Text("淡入淡出").tag(2); Text("缩放").tag(3)
                            Text("高级").tag(4); Text("仿真").tag(5)
                        }.disabled(settings.value.mode >= 3)
                    } else if section == 2 {
                        Picker("背景", selection: $settings.value.background) {
                            Text("黑色").tag(0); Text("白色").tag(1); Text("纸色").tag(2)
                        }
                        Toggle("反色", isOn: $settings.value.inverted)
                        Toggle("灰度", isOn: $settings.value.grayscale)
                        Toggle("书页阴影", isOn: $settings.value.bookEffect)
                        Text("亮度"); Slider(value: $settings.value.brightness, in: -0.5...0.5)
                        Text("对比度"); Slider(value: $settings.value.contrast, in: 0.5...2)
                    } else {
                        Toggle("阅读时保持屏幕常亮", isOn: $settings.value.keepScreenOn)
                            .accessibilityIdentifier("reader.option.keepScreenOn")
                        Toggle("右下角显示书签浮动按钮", isOn: $settings.value.bookmarkButton)
                            .disabled(!settings.value.bookmarkButton && settings.value.floatingActions.filter { $0 != "bookmark" }.count >= 4)
                        Text("浮动按钮最多四个").font(.caption).foregroundStyle(.secondary)
                        ForEach(Self.actions.filter { $0.0 != "bookmark" }, id: \.0) { action in
                            Toggle(action.1, isOn: Binding(
                                get: { settings.value.floatingActions.contains(action.0) },
                                set: { enabled in
                                    if enabled { settings.value.floatingActions.append(action.0) }
                                    else { settings.value.floatingActions.removeAll { $0 == action.0 } }
                                }
                            )).disabled(!settings.value.floatingActions.contains(action.0) &&
                                        settings.value.floatingActions.filter { $0 != "bookmark" }.count + (settings.value.bookmarkButton ? 1 : 0) >= 4)
                        }
                        Toggle("从底部展开面板", isOn: $settings.value.bottomPanels)
                        Toggle("点击屏幕边缘翻页", isOn: $settings.value.tapZones)
                            .accessibilityIdentifier("reader.option.tapZones")
                        Toggle("反转点击区域", isOn: $settings.value.reverseTaps).disabled(!settings.value.tapZones)
                        Text("左右两侧或上下边缘翻页，中间显示或隐藏控件；双击缩放，双指捏合缩放。")
                            .font(.caption).foregroundStyle(.secondary)
                        Button(savedDefault ? "已设为全局默认" : "将当前设置设为全局默认") {
                            settings.saveAsDefault(); savedDefault = true
                        }.buttonStyle(.glass)
                        Text("其他设置自动保存在本作品。").font(.caption).foregroundStyle(.secondary)
                    }
                }.padding(16)
            }
        }
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
