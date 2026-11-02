import KototoroShared
import SwiftUI

/// Pictures of notes (page crops, video frames), kept in Application Support so they survive cache cleanup.
enum NoteImages {
    static let directory: URL = {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "notes", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    /// Saves [image] and returns its path, or nil when it cannot be written.
    static func save(_ image: UIImage) -> String? {
        guard let data = image.jpegData(compressionQuality: 0.88) else { return nil }
        let url = directory.appending(path: "\(UUID().uuidString).jpg")
        do { try data.write(to: url, options: .atomic); return url.path } catch { return nil }
    }

    static func remove(_ path: String?) {
        guard let path, path.hasPrefix(directory.path) else { return }
        try? FileManager.default.removeItem(atPath: path)
    }
}

/// Android's notes overview: every work with notes or bookmarks, with what it holds.
struct NotesOverviewView: View {
    let notes: IosNotes
    @State private var summaries: [IosNoteSummary] = []
    @State private var loaded = false

    var body: some View {
        List {
            if loaded && summaries.isEmpty {
                ContentUnavailableView("还没有笔记", systemImage: "note.text",
                                       description: Text("在漫画阅读器里裁切页面，或在视频播放时记下时间点，都会保存在这里；书签也一并列出。"))
            }
            ForEach(summaries, id: \.content.id) { summary in
                NavigationLink { WorkNotesView(notes: notes, content: summary.content) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(summary.content.title).font(.headline).lineLimit(2)
                        Text(counts(summary)).font(.caption).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                }
                .accessibilityIdentifier("notes.work")
            }
        }
        .navigationTitle("笔记")
        .task { await reload() }
        .refreshable { await reload() }
    }

    private func counts(_ summary: IosNoteSummary) -> String {
        var parts: [String] = []
        if summary.highlights > 0 { parts.append(String(localized: "摘录 \(Int(summary.highlights))")) }
        if summary.thoughts > 0 { parts.append(String(localized: "想法 \(Int(summary.thoughts))")) }
        if summary.bookmarks > 0 { parts.append(String(localized: "书签 \(Int(summary.bookmarks))")) }
        return parts.joined(separator: " · ")
    }

    private func reload() async {
        summaries = ((try? await notes.summaries()) ?? []).filter { !AdultContentFilter.hides($0.content, in: .bookmarks) }
        loaded = true
    }
}

/// One work's page crops and video time points: the picture, where it is, and the written thought.
struct WorkNotesView: View {
    let notes: IosNotes
    let content: IosSourceContent
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var library: LibraryModel
    @State private var items: [IosMediaNote] = []
    @State private var markings: [IosNovelMarking] = []
    @State private var editingMarking: IosNovelMarking?
    /// The work with its stored chapters (the overview lists works without them).
    @State private var details: IosSourceContent?
    @State private var editing: IosMediaNote?
    @State private var draft = ""
    @State private var reading: PlaybackRequest?

    var body: some View {
        List {
            if !markings.isEmpty {
                Section("划线") {
                    ForEach(markings, id: \.id) { marking in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(marking.text).lineLimit(4)
                                .padding(.leading, 8)
                                .overlay(alignment: .leading) { Rectangle().fill(.tint).frame(width: 3) }
                            Text((details ?? content).chapters.first { $0.id == marking.chapterId }?.title
                                 ?? String(localized: "第 \(Int(marking.chapterIndex) + 1) 章"))
                                .font(.caption).foregroundStyle(.secondary)
                            if let note = marking.note, !note.isEmpty { Text(note).font(.callout) }
                        }
                        .swipeActions {
                            Button("删除", role: .destructive) {
                                Task { try? await library.library?.markings.delete(id: marking.id); await reload() }
                            }
                            Button("想法") { draft = marking.note ?? ""; editingMarking = marking }.tint(.orange)
                        }
                        .accessibilityIdentifier("notes.marking")
                    }
                }
            }
            if items.isEmpty && markings.isEmpty {
                ContentUnavailableView("没有划线或页面笔记", systemImage: "photo.on.rectangle",
                                       description: Text("这部作品的书签可在详情页查看。"))
            }
            ForEach(items, id: \.id) { note in
                // A button, so the row highlights when pressed and VoiceOver can open it.
                Button { open(note) } label: {
                    VStack(alignment: .leading, spacing: 8) {
                        if let path = note.imagePath { NoteSnapshot(path: path) }
                        Text(place(note)).font(.caption).foregroundStyle(.secondary)
                        if let text = note.note, !text.isEmpty { Text(text).foregroundStyle(.primary) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .swipeActions {
                    Button("删除", role: .destructive) { Task { await delete(note) } }
                    Button("编辑") { draft = note.note ?? ""; editing = note }.tint(.orange)
                }
                .accessibilityIdentifier("notes.item")
            }
        }
        .navigationTitle(content.title)
        .navigationDestination(item: $reading) { request in
            if let runtime = sources.runtime { DetailsReaderDestination(runtime: runtime, request: request) }
        }
        .alert("编辑想法", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            TextField("写下想法", text: $draft)
            Button("保存") {
                if let note = editing { Task { try? await notes.updateNote(id: note.id, note: draft); await reload() } }
                editing = nil
            }
            Button("取消", role: .cancel) { editing = nil }
        }
        .alert("写下想法", isPresented: Binding(get: { editingMarking != nil }, set: { if !$0 { editingMarking = nil } })) {
            TextField("想法", text: $draft)
            Button("保存") {
                if let marking = editingMarking {
                    Task { try? await library.library?.markings.updateNote(id: marking.id, note: draft); await reload() }
                }
                editingMarking = nil
            }
            Button("取消", role: .cancel) { editingMarking = nil }
        }
        .task { await reload() }
    }

    private func place(_ note: IosMediaNote) -> String {
        let chapter = (details ?? content).chapters.first { $0.id == note.chapterId }?.title
            ?? String(localized: "第 \(Int(note.chapterIndex) + 1) 章")
        if note.isVideo {
            let seconds = Int(note.positionMs / 1000)
            return "\(chapter) · \(String(format: "%d:%02d", seconds / 60, seconds % 60))"
        }
        return String(localized: "\(chapter) · 第 \(Int(note.page) + 1) 页")
    }

    /// A page crop opens the reader on its page; a video note's place is shown in the list.
    private func open(_ note: IosMediaNote) {
        let work = details ?? content
        guard !note.isVideo, let chapter = work.chapters.first(where: { $0.id == note.chapterId }) else { return }
        reading = PlaybackRequest(content: work, chapter: chapter, initialPage: Int(note.page))
    }

    private func delete(_ note: IosMediaNote) async {
        NoteImages.remove(try? await notes.delete(id: note.id))
        await reload()
    }

    private func reload() async {
        if details == nil { details = try? await library.library?.storedDetails(content: content) }
        items = (try? await notes.mediaNotes(content: content)) ?? []
        markings = (try? await library.library?.markings.forWork(content: content)) ?? []
    }
}

/// A note's page snapshot, read and downsampled off the main thread: the list scrolls while snapshots decode, and a
/// full-resolution screenshot per row would hold the main thread and its memory.
private struct NoteSnapshot: View {
    let path: String
    @State private var image: UIImage?
    private static let cache = NSCache<NSString, UIImage>()

    var body: some View {
        Group {
            if let image = image ?? Self.cache.object(forKey: path as NSString) {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(maxHeight: 220).clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
        .task(id: path) {
            guard Self.cache.object(forKey: path as NSString) == nil else { return }
            let path = path
            // 220 pt high at 3x, with room for wide screenshots.
            let decoded = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                guard let data = FileManager.default.contents(atPath: path) else { return nil }
                return try? SourceImageFile.decode(data: data, maxPixelSize: 1000)
            }.value
            guard let decoded, !Task.isCancelled else { return }
            Self.cache.setObject(decoded, forKey: path as NSString)
            image = decoded
        }
    }
}
