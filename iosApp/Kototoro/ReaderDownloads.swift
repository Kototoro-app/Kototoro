import CryptoKit
import Foundation
import ImageIO
import KototoroShared
import UIKit

enum ReaderDownloads {
    private typealias ImageRecord = ReaderDownloadStore.ImageRecord
    private typealias Manifest = ReaderDownloadStore.Manifest
    static func directory(content: IosSourceContent, chapter: IosSourceChapter) throws -> URL {
        // A downloaded book's chapters live in the download they are listed from.
        let chapter = content.bookOf(chapter: chapter) ?? chapter
        let root = try NativeSourceTransport.dataDirectory.appendingPathComponent("downloads", isDirectory: true)
        let hash = ReaderStorageMetadata.directoryID(contentID: content.id, chapterID: chapter.id)
        return root.appendingPathComponent(hash, isDirectory: true)
    }
    static func isDownloaded(content: IosSourceContent, chapter: IosSourceChapter) -> Bool {
        if content.mediaKind == "VIDEO" { return VideoDownloads.isDownloaded(content: content, chapter: chapter) }
        guard let dir = try? directory(content: content, chapter: chapter) else { return false }
        let manifest = content.mediaKind == "NOVEL" ? NovelDownloads.manifestName : DownloadManifests.chapter
        if content.mediaKind != "NOVEL" && ReaderDownloadStore.isKnownInvalid(in: dir) { return false }
        return FileManager.default.fileExists(atPath: dir.appendingPathComponent(manifest).path)
    }
    static func pages(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) async throws -> [IosSourcePage] {
        let dir = try directory(content: content, chapter: chapter)
        do {
            if let manifest = try await ReaderDownloadStore.shared.read(in: dir) {
                let pages = try IosPageArchive().decode(json: manifest.pages)
                guard !pages.isEmpty, pages.count == manifest.images.count else {
                    throw ReaderDownloadStore.Failure.invalidManifest
                }
                return pages
            }
        } catch {
            try Task.checkCancellation()
            await ReaderDownloadStore.shared.invalidate(dir)
        }
        return try await runtime.pages(chapter: chapter)
    }
    static func loadedPage(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter,
                           page: IosSourcePage, index: Int, cropMargins: Bool = false) async throws -> ReaderLoadedPage {
        let dir = try directory(content: content, chapter: chapter)
        let manifest = try? await ReaderDownloadStore.shared.read(in: dir)
        try Task.checkCancellation()
        if let manifest, manifest.images.indices.contains(index) {
            let record = manifest.images[index]
            do {
                return try await ReaderImageArtifact.load(directory: dir, relativePath: record.path,
                                                         sha256: record.hash, byteSize: record.bytes,
                                                         cropMargins: cropMargins)
            } catch {
                try Task.checkCancellation()
                // Only this page is re-fetched; keep all other verified offline files and the original request context.
                let fetched = try await runtime.image(page: page)
                let loaded = try await fetched.loadedPage(cropMargins: cropMargins)
                let replacement = ImageRecord(path: fetched.relativePath, hash: fetched.sha256, bytes: fetched.byteSize)
                if try await ReaderDownloadStore.shared.repairImage(
                    in: dir, index: index, expected: manifest, replacement: replacement,
                    sourceDirectory: loaded.artifact.directory
                ) {
                    let artifact = loaded.artifact
                    return ReaderLoadedPage(image: loaded.image, artifact: ReaderImageArtifact(
                        directory: dir, relativePath: artifact.relativePath, sha256: artifact.sha256,
                        byteSize: artifact.byteSize, encodedWidth: artifact.encodedWidth,
                        encodedHeight: artifact.encodedHeight, tiled: artifact.tiled
                    ))
                }
                return loaded
            }
        }
        return try await runtime.image(page: page).loadedPage(cropMargins: cropMargins)
    }
    static func image(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter,
                      page: IosSourcePage, index: Int, cropMargins: Bool = false) async throws -> UIImage {
        try await loadedPage(runtime: runtime, content: content, chapter: chapter, page: page,
                             index: index, cropMargins: cropMargins).image
    }

    /// Thumbnails use the same page context and offline files without decoding a full reader bitmap.
    static func thumbnailImage(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter,
                               page: IosSourcePage, index: Int) async throws -> UIImage {
        try await withReaderStorage {
            let dir = try directory(content: content, chapter: chapter)
            if let manifest = try? await ReaderDownloadStore.shared.read(in: dir), manifest.images.indices.contains(index) {
                let record = manifest.images[index]
                do {
                    return try await ReaderImageArtifact.thumbnailImage(directory: dir, relativePath: record.path,
                                                                        sha256: record.hash, byteSize: record.bytes)
                } catch {
                    try Task.checkCancellation()
                }
            }
            return try await runtime.image(page: page).thumbnailImage()
        }
    }
    @MainActor
    static func download(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter,
                         progress: ReaderDownloadQueue.Progress = { _, _ in }) async throws {
        if content.mediaKind == "NOVEL" {
            try await NovelDownloads.download(runtime: runtime, content: content, chapter: chapter, progress: progress)
            return
        }
        let dir = try directory(content: content, chapter: chapter)
        let partial = dir.appendingPathComponent("partial.json")
        var saved: Manifest?
        if let complete = try? await ReaderDownloadStore.shared.read(in: dir) {
            saved = complete
        } else if FileManager.default.fileExists(atPath: partial.path) {
            saved = try? JSONDecoder().decode(Manifest.self, from: Data(contentsOf: partial))
        } else { saved = nil }
        let pages: [IosSourcePage]
        if let checkpoint = saved, let restored = try? IosPageArchive().decode(json: checkpoint.pages),
           !restored.isEmpty, checkpoint.images.count <= restored.count {
            pages = restored
        } else {
            saved = nil
            pages = try await runtime.pages(chapter: chapter)
        }
        try Task.checkCancellation()
        guard !pages.isEmpty else { throw URLError(.zeroByteResource) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = try NativeSourceTransport.dataDirectory.appendingPathComponent("images", isDirectory: true)
        let archive = IosPageArchive().encode(pages: pages)
        if saved == nil {
            try await ReaderDownloadStore.shared.write(Manifest(pages: archive, images: []), in: dir, name: "partial.json")
        }
        var records: [ImageRecord] = []
        try progress(0, pages.count)
        // Sequential requests bound decode and extension memory on the interpreter-backed JVM.
        for (index, page) in pages.enumerated() {
            try Task.checkCancellation()
            var record: ImageRecord?
            if let saved, saved.images.indices.contains(index) {
                let existing = saved.images[index]
                if (try? await ReaderImageArtifact.load(directory: dir, relativePath: existing.path,
                                                       sha256: existing.hash, byteSize: existing.bytes)) != nil {
                    record = existing
                }
            }
            try Task.checkCancellation()
            if record == nil {
                let artifact = try await runtime.image(page: page)
                try Task.checkCancellation()
                _ = try await artifact.decodedImage()
                try Task.checkCancellation()
                let target = dir.appendingPathComponent(artifact.relativePath)
                // Atomic replacement also repairs an equal-length, corrupted previous download.
                try await Task.detached(priority: .utility) {
                    try Data(contentsOf: store.appendingPathComponent(artifact.relativePath))
                        .write(to: target, options: .atomic)
                }.value
                record = ImageRecord(path: artifact.relativePath, hash: artifact.sha256, bytes: artifact.byteSize)
            }
            guard let record else { throw URLError(.cannotDecodeContentData) }
            records.append(record)
            // Keep the saved suffix until it is revalidated, so another interruption does not lose its checkpoint.
            let checkpointRecords = records + (saved.map { Array($0.images.dropFirst(records.count)) } ?? [])
            try await ReaderDownloadStore.shared.write(Manifest(pages: archive, images: checkpointRecords),
                                                       in: dir, name: "partial.json")
            try Task.checkCancellation()
            try progress(records.count, pages.count)
        }
        try Task.checkCancellation()
        let value = Manifest(pages: archive, images: records)
        try await ReaderDownloadStore.shared.write(value, in: dir)
    }
}

extension ReaderDownloadQueue {
    func isFinishing(content: IosSourceContent, chapter: IosSourceChapter) -> Bool {
        let chapter = content.bookOf(chapter: chapter) ?? chapter
        return isFinishing(ReaderDownloadRequest(source: content.sourceName, contentID: content.id, chapterID: chapter.id,
                                         title: "", chapterTitle: "", payload: "").id)
    }

    func attach(runtime: IosSources) {
        attach { request, progress in
            let decoded = try IosDownloadArchive().decode(json: request.payload)
            guard decoded.content.sourceName == request.source, decoded.content.id == request.contentID,
                  decoded.chapter.id == request.chapterID else { throw URLError(.cannotParseResponse) }
            try await withReaderStorage {
                let store = try ReaderStorageModel.downloadStore()
                try await store.record(request.storageMetadata)
                try await store.keep(payload: request.payload, for: request.storageMetadata)
                if request.isVideo {
                    try await VideoDownloads.download(runtime: runtime, content: decoded.content,
                                                      chapter: decoded.chapter, streamURL: request.streamURL,
                                                      progress: progress)
                } else {
                    try await ReaderDownloads.download(runtime: runtime, content: decoded.content,
                                                       chapter: decoded.chapter, progress: progress)
                }
            }
        }
    }

    func enqueue(content: IosSourceContent, chapters: [IosSourceChapter], streamURL: String? = nil) throws {
        let codec = IosDownloadArchive()
        // A downloaded book's chapters download as the book, once.
        var seen = Set<String>()
        let chapters = chapters.map { content.bookOf(chapter: $0) ?? $0 }.filter { seen.insert($0.id).inserted }
        try enqueue(chapters.map { chapter in
            ReaderDownloadRequest(source: content.sourceName, contentID: content.id, chapterID: chapter.id,
                                  title: content.title, chapterTitle: chapter.title,
                                  payload: try codec.encode(content: content, chapter: chapter),
                                  mediaKind: content.mediaKind, streamURL: streamURL)
        })
    }

    func entry(content: IosSourceContent, chapter: IosSourceChapter) -> ReaderDownloadEntry? {
        let chapter = content.bookOf(chapter: chapter) ?? chapter
        return entries.first {
            $0.request.source == content.sourceName && $0.request.contentID == content.id && $0.request.chapterID == chapter.id
        }
    }
}

/// Novel chapters offline: the shared chapter text (HTML, pictures and their headers) plus each picture as a file.
/// Text and finished pictures are kept as checkpoints, so pausing or a failed picture resumes where it stopped.
enum NovelDownloads {
    static let manifestName = DownloadManifests.novel
    private static let textName = "text.json"
    /// The chapter titles of a downloaded book (an EPUB), which then lists it by its chapters.
    private static let bookName = "book.json"
    private static let maximumImageBytes = 32 * 1024 * 1024

    private struct Manifest: Codable {
        /// Picture URL → file name in the chapter directory.
        let images: [String: String]
    }

    /// The chapter text and its local pictures when downloaded, otherwise nil; a book's chapter gets its own text.
    static func offline(content: IosSourceContent, chapter: IosSourceChapter) throws -> (IosChapterText, [String: URL])? {
        let dir = try ReaderDownloads.directory(content: content, chapter: chapter)
        let manifestFile = dir.appendingPathComponent(manifestName)
        guard FileManager.default.fileExists(atPath: manifestFile.path) else { return nil }
        let manifest = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: manifestFile))
        var text = try IosChapterTextArchive().decode(json: String(decoding: Data(contentsOf: dir.appendingPathComponent(textName)),
                                                                   as: UTF8.self))
        // A downloaded book's chapter reads its part of the book.
        let index = content.bookIndex(chapter: chapter)
        if index >= 0 {
            guard let section = text.section(index: index) else { return nil }
            text = section
        }
        return (text, manifest.images.mapValues { dir.appendingPathComponent($0) })
    }

    @MainActor
    static func download(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter,
                         progress: ReaderDownloadQueue.Progress) async throws {
        let dir = try ReaderDownloads.directory(content: content, chapter: chapter)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let textFile = dir.appendingPathComponent(textName)
        let archive = IosChapterTextArchive()
        let text: IosChapterText
        if let saved = try? Data(contentsOf: textFile),
           let restored = try? archive.decode(json: String(decoding: saved, as: UTF8.self)) {
            text = restored
        } else {
            guard let fetched = try await runtime.chapterText(chapter: chapter) else { throw URLError(.zeroByteResource) }
            try Task.checkCancellation()
            try Data(archive.encode(text: fetched).utf8).write(to: textFile, options: .atomic)
            text = fetched
        }
        // Pictures named in the text as well as those the source lists, each once.
        var urls: [String] = []
        for url in text.images + NovelBlock.blocks(from: text).compactMap({ block -> String? in
            if case .image(let url) = block { return url.absoluteString } else { return nil }
        }) where !urls.contains(url) { urls.append(url) }
        let total = urls.count + 1
        try progress(1, total)
        var images: [String: String] = [:]
        for (index, address) in urls.enumerated() {
            try Task.checkCancellation()
            let name = "image-" + SHA256.hash(data: Data(address.utf8)).map { String(format: "%02x", $0) }.joined()
            let target = dir.appendingPathComponent(name)
            if !isValidImage(target) {
                guard let url = URL(string: address) else { throw URLError(.badURL) }
                let data = try await NovelImageData.load(url, headers: text.headers(imageUrl: address))
                try Task.checkCancellation()
                guard data.count <= maximumImageBytes,
                      CGImageSourceCreateWithData(data as CFData, nil).map({ CGImageSourceGetCount($0) > 0 }) == true
                else { throw URLError(.cannotDecodeContentData) }
                try await Task.detached(priority: .utility) { try data.write(to: target, options: .atomic) }.value
            }
            images[address] = name
            try progress(index + 2, total)
        }
        try Task.checkCancellation()
        let book = dir.appendingPathComponent(bookName)
        if text.sections.isEmpty { try? FileManager.default.removeItem(at: book) }
        else { try JSONEncoder().encode(text.sections).write(to: book, options: .atomic) }
        try JSONEncoder().encode(Manifest(images: images)).write(to: dir.appendingPathComponent(manifestName), options: .atomic)
    }

    /// The work with each finished book download listed by its chapters, as Android lists an EPUB download.
    static func listingBooks(_ content: IosSourceContent) -> IosSourceContent {
        guard content.mediaKind == "NOVEL", !content.isLocal else { return content }
        var books: [String: [String]] = [:]
        for chapter in content.sourceChapters {
            guard let dir = try? ReaderDownloads.directory(content: content, chapter: chapter),
                  FileManager.default.fileExists(atPath: dir.appendingPathComponent(manifestName).path),
                  let data = try? Data(contentsOf: dir.appendingPathComponent(bookName)),
                  let titles = try? JSONDecoder().decode([String].self, from: data), !titles.isEmpty
            else { continue }
            books[chapter.id] = titles
        }
        return content.withBooks(books: books)
    }

    private static func isValidImage(_ file: URL) -> Bool {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil) else { return false }
        return CGImageSourceGetCount(source) > 0
    }
}

/// EPUB illustrations already live in the app's cache; remote resources retain their source headers.
enum NovelImageData {
    static func load(_ url: URL, headers: [String: String]) async throws -> Data {
        let limit = 32 * 1024 * 1024
        if url.isFileURL {
            return try await Task.detached(priority: .utility) {
                guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= limit else {
                    throw URLError(.dataLengthExceedsMaximum)
                }
                return try Data(contentsOf: url)
            }.value
        }
        var request = URLRequest(url: url, timeoutInterval: 60)
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? true,
              data.count <= limit else { throw URLError(.badServerResponse) }
        return data
    }
}

