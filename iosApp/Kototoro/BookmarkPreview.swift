import KototoroShared
import SwiftUI

@MainActor
enum BookmarkImages {
    private static var storage: BookmarkImageStore?
    private static let rebuilding = RequestGate(limit: 2)

    private static func store() throws -> BookmarkImageStore {
        if let storage { return storage }
        let directory = try NativeSourceTransport.dataDirectory.appendingPathComponent("bookmarks", isDirectory: true)
        let value = BookmarkImageStore(directory: directory)
        storage = value
        return value
    }

    static func key(content: IosSourceContent, bookmark: IosBookmark) -> String {
        "\(content.contentKey)\n\(bookmark.chapterId)\n\(bookmark.id)\n\(bookmark.createdAt)"
    }

    static func save(_ image: UIImage, content: IosSourceContent, bookmark: IosBookmark) async throws {
        try await store().save(image, for: key(content: content, bookmark: bookmark))
    }

    static func remove(content: IosSourceContent, bookmark: IosBookmark) async throws {
        try await store().remove(for: key(content: content, bookmark: bookmark))
    }

    /// Legacy or restored bookmarks rebuild their snapshot through the original source context and offline store.
    static func image(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter?,
                      bookmark: IosBookmark) async throws -> UIImage {
        let storage = try store()
        let key = key(content: content, bookmark: bookmark)
        if let image = try? await storage.image(for: key) { return image }
        try Task.checkCancellation()
        guard let chapter else { throw URLError(.resourceUnavailable) }
        return try await gated(rebuilding) {
            if let image = try? await storage.image(for: key) { return image }
            let pages = try await ReaderDownloads.pages(runtime: runtime, content: content, chapter: chapter)
            let index = Int(bookmark.index)
            guard pages.indices.contains(index) else { throw URLError(.resourceUnavailable) }
            let image = try await ReaderDownloads.thumbnailImage(runtime: runtime, content: content, chapter: chapter,
                                                                 page: pages[index], index: index)
            try Task.checkCancellation()
            try await storage.save(image, for: key)
            return image
        }
    }
}

/// The same page thumbnail or text excerpt in details cards, the complete list and the reader's navigation.
struct BookmarkPreviewLabel: View {
    let runtime: IosSources
    let content: IosSourceContent
    let bookmark: IosBookmark
    let chapter: IosSourceChapter?

    private var isNovel: Bool { content.mediaKind == "NOVEL" }
    private var progressPercent: Int {
        let value = bookmark.percent
        return value.isFinite ? Int(min(max(value, 0), 1) * 100) : 0
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            if !isNovel {
                BookmarkPageImage(runtime: runtime, content: content, bookmark: bookmark, chapter: chapter)
            }
            VStack(alignment: .leading, spacing: 7) {
                Text(chapter?.title ?? String(localized: "章节已失效"))
                    .font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(2)
                if isNovel {
                    Text(bookmark.textPreview.isEmpty ? String(localized: "暂无文字预览") : bookmark.textPreview)
                        .font(.subheadline).foregroundStyle(bookmark.textPreview.isEmpty ? .secondary : .primary)
                        .lineSpacing(3).lineLimit(4)
                        .accessibilityIdentifier("bookmark.excerpt.\(bookmark.id)")
                }
                HStack(spacing: 8) {
                    Text(isNovel ? "第 \(bookmark.index + 1) 段" : "第 \(bookmark.index + 1) 页")
                    Spacer(minLength: 0)
                    Text("\(progressPercent)%")
                        .monospacedDigit()
                }
                .font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }
}

private struct BookmarkPageImage: View {
    let runtime: IosSources
    let content: IosSourceContent
    let bookmark: IosBookmark
    let chapter: IosSourceChapter?
    @State private var image: UIImage?
    @State private var loading = true

    var body: some View {
        Color.clear
            .frame(width: 72, height: 96)
            .background(.quaternary)
            .overlay {
                if let image {
                    Image(uiImage: image).renderingMode(.original).resizable().scaledToFill()
                } else if loading {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "photo").foregroundStyle(.secondary)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .accessibilityHidden(true)
            .task(id: BookmarkImages.key(content: content, bookmark: bookmark)) {
                image = nil
                loading = true
                do {
                    let result = try await BookmarkImages.image(runtime: runtime, content: content,
                                                                chapter: chapter, bookmark: bookmark)
                    try Task.checkCancellation()
                    image = result
                } catch {
                    if Task.isCancelled { return }
                }
                loading = false
            }
    }
}
