import AVFoundation
import Foundation
import KototoroShared

/// An episode kept for offline playback: the stream's media (a single file, or the package the system builds for an
/// HLS playlist) plus its side-loaded subtitles, described by `video.json` in the chapter's download directory.
/// The player reads the same manifest, so a finished episode plays with no network and no re-resolved stream.
enum VideoDownloads {
    static let manifestName = DownloadManifests.video
    /// Written before `video.json`, so an interrupted download is never mistaken for a finished one.
    private static let partialName = "video.download"
    private static let maximumSubtitleTracks = 20
    private static let maximumSubtitleBytes = 8 * 1024 * 1024

    struct Subtitle: Codable, Equatable, Sendable {
        let lang: String
        /// File name inside the download directory.
        let file: String
        /// Where it came from, kept so the player's track list reads the same offline.
        let url: String
    }

    struct Manifest: Codable, Equatable, Sendable {
        var version = 1
        /// Media file name inside the download directory: `video.mp4`, or `video.movpkg` for HLS.
        var file: String
        /// "progressive" for a single media file, "hls" for a system-managed offline package.
        var kind: String
        var bytes: Int64
        /// The source address it was taken from.
        var url: String
        var label: String?
        var quality: Int
        var subtitles: [Subtitle]
    }

    /// A finished download, ready to hand to AVPlayer.
    struct Offline {
        let url: URL
        let manifest: Manifest
        let subtitles: [SubtitleTrack]
    }

    static func directory(content: IosSourceContent, chapter: IosSourceChapter) throws -> URL {
        // Pictures and episodes of one work never share a directory: the chapter id keeps them apart.
        try ReaderDownloads.directory(content: content, chapter: chapter)
    }

    static func isDownloaded(content: IosSourceContent, chapter: IosSourceChapter) -> Bool {
        offline(content: content, chapter: chapter) != nil
    }

    /// The episode on disk, when its manifest and media file are both still there. Small synchronous reads only:
    /// the player asks this before it resolves anything over the network.
    static func offline(content: IosSourceContent, chapter: IosSourceChapter) -> Offline? {
        guard let dir = try? directory(content: content, chapter: chapter) else { return nil }
        let manifestFile = dir.appendingPathComponent(manifestName)
        guard let data = try? Data(contentsOf: manifestFile),
              let manifest = try? JSONDecoder().decode(Manifest.self, from: data),
              manifest.version == 1, manifest.file.hasPrefix("video.") else { return nil }
        let url = dir.appendingPathComponent(manifest.file)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let subtitles = manifest.subtitles.map {
            SubtitleTrack(url: dir.appendingPathComponent($0.file).absoluteString, lang: $0.lang, headers: [:])
        }
        return Offline(url: url, manifest: manifest, subtitles: subtitles)
    }

    /// Progress counts percent, the unit the download queue shows for an episode.
    @MainActor
    static func download(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter,
                         streamURL: String? = nil, progress: @escaping ReaderDownloadQueue.Progress) async throws {
        let dir = try directory(content: content, chapter: chapter)
        let pages = try await runtime.pages(chapter: chapter)
        try Task.checkCancellation()
        // The address the queue recorded wins, so a resumed task downloads what it was asked for; otherwise the
        // source's preferred stream, which is the one the player opens first.
        guard let stream = pages.first(where: { $0.url == streamURL }) ?? pages.first,
              URL(string: stream.url) != nil else {
            throw VideoDownloadError.noStream
        }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try await progress(0, 100)
        // Media takes the first 90% of the bar; subtitle files are small and finish it.
        let media = try await MediaFile.fetch(stream, into: dir) { percent in
            try await progress(Int(Double(percent) * 0.9), 100)
        }
        try Task.checkCancellation()
        let subtitles = await SubtitleFiles.fetch(stream, into: dir)
        try Task.checkCancellation()
        let manifest = Manifest(file: media.file, kind: media.kind, bytes: media.bytes, url: stream.url,
                                label: stream.label, quality: Int(stream.quality), subtitles: subtitles)
        try JSONEncoder().encode(manifest).write(to: dir.appendingPathComponent(manifestName), options: .atomic)
        try await progress(100, 100)
    }

    /// Drop media left over from an earlier attempt; `video.json` is the only marker of a usable episode.
    private static func discardMedia(in directory: URL) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for name in names where name.hasPrefix("video.") && name != manifestName {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    private struct MediaFile {
        let file: String
        let kind: String
        let bytes: Int64

        /// Fetch the stream into `directory`, choosing a single file or the HLS package from the address, and for an
        /// address that does not say, from the first bytes of the body.
        @MainActor
        static func fetch(_ stream: IosSourcePage, into directory: URL,
                          progress: @escaping (Int) async throws -> Void) async throws -> MediaFile {
            guard let url = URL(string: stream.url) else { throw URLError(.badURL) }
            discardMedia(in: directory)
            if url.isFileURL {
                return try await copyLocal(url, into: directory)
            }
            if url.absoluteString.range(of: ".m3u8", options: .caseInsensitive) != nil {
                return try await downloadHLS(url, headers: stream.headers, into: directory, progress: progress)
            }
            let partial = directory.appendingPathComponent(partialName)
            do {
                let transfer = VideoFileDownloader().fetch(url: url, headers: stream.headers, to: partial)
                for try await percent in transfer {
                    try Task.checkCancellation()
                    try await progress(percent)
                }
                let downloaded = partial
                // A playlist served under a neutral address must not be saved as if it were the video.
                if try looksLikePlaylist(downloaded) {
                    try? FileManager.default.removeItem(at: downloaded)
                    return try await downloadHLS(url, headers: stream.headers, into: directory, progress: progress)
                }
                let name = "video" + mediaExtension(url)
                let target = directory.appendingPathComponent(name)
                try replace(downloaded, with: target)
                let bytes = (try? target.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                guard bytes > 0 else { throw URLError(.zeroByteResource) }
                return MediaFile(file: name, kind: "progressive", bytes: Int64(bytes))
            } catch {
                try? FileManager.default.removeItem(at: partial)
                throw error
            }
        }

        private static func copyLocal(_ url: URL, into directory: URL) async throws -> MediaFile {
            // An HLS import is a playlist plus segments; offline HLS goes through the system downloader instead.
            if url.pathExtension.lowercased() == "m3u8" { throw URLError(.unsupportedURL) }
            let name = "video" + mediaExtension(url)
            let target = directory.appendingPathComponent(name)
            let bytes = try await Task.detached(priority: .utility) { () -> Int64 in
                guard let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 0 else {
                    throw URLError(.zeroByteResource)
                }
                if FileManager.default.fileExists(atPath: target.path) {
                    try FileManager.default.removeItem(at: target)
                }
                try FileManager.default.copyItem(at: url, to: target)
                return Int64(size)
            }.value
            return MediaFile(file: name, kind: "progressive", bytes: bytes)
        }

        @MainActor
        private static func downloadHLS(_ url: URL, headers: [String: String], into directory: URL,
                                        progress: @escaping (Int) async throws -> Void) async throws -> MediaFile {
            let partial = directory.appendingPathComponent(partialName + ".movpkg")
            try? FileManager.default.removeItem(at: partial)
            let transfer = VideoHLSDownloader().fetch(url: url, headers: headers, to: partial)
            for try await percent in transfer {
                try Task.checkCancellation()
                try await progress(percent)
            }
            let downloaded = partial
            let name = "video.movpkg"
            let target = directory.appendingPathComponent(name)
            try replace(downloaded, with: target)
            return MediaFile(file: name, kind: "hls", bytes: directorySize(target))
        }

        private static func looksLikePlaylist(_ file: URL) throws -> Bool {
            guard let handle = try? FileHandle(forReadingFrom: file) else { return false }
            defer { try? handle.close() }
            let head = try handle.read(upToCount: 16) ?? Data()
            return String(decoding: head, as: UTF8.self).hasPrefix("#EXTM3U")
        }

        private static func mediaExtension(_ url: URL) -> String {
            let known = ["mp4", "m4v", "mov", "mkv", "webm", "ts", "avi", "flv"]
            let ext = url.pathExtension.lowercased()
            return known.contains(ext) ? "." + ext : ".mp4"
        }

        private static func replace(_ source: URL, with target: URL) throws {
            if FileManager.default.fileExists(atPath: target.path) {
                try FileManager.default.removeItem(at: target)
            }
            try FileManager.default.moveItem(at: source, to: target)
        }
    }

    /// Side-loaded subtitles travel with the episode. A track that fails is skipped: it must not fail the download.
    private enum SubtitleFiles {
        @MainActor
        static func fetch(_ stream: IosSourcePage, into directory: URL) async -> [Subtitle] {
            guard !stream.subtitles.isEmpty else { return [] }
            var result: [Subtitle] = []
            for (index, track) in stream.subtitles.prefix(maximumSubtitleTracks).enumerated() {
                if Task.isCancelled { break }
                do {
                    let text = try await SubtitleFile.download(SubtitleTrack(track), streamHeaders: stream.headers)
                    guard !text.isEmpty, text.utf8.count <= maximumSubtitleBytes else { continue }
                    let file = "subtitle-\(index)" + subtitleExtension(track.url)
                    try Data(text.utf8).write(to: directory.appendingPathComponent(file), options: .atomic)
                    result.append(Subtitle(lang: track.lang, file: file, url: track.url))
                } catch {
                    continue
                }
            }
            return result
        }

        private static func subtitleExtension(_ url: String) -> String {
            let known = ["vtt", "srt", "ass", "ssa"]
            let ext = URL(string: url)?.pathExtension.lowercased() ?? ""
            return known.contains(ext) ? "." + ext : ".vtt"
        }
    }

    private static func directorySize(_ url: URL) -> Int64 {
        let names = (try? FileManager.default.subpathsOfDirectory(atPath: url.path)) ?? []
        return names.reduce(0) { total, name in
            let values = try? url.appendingPathComponent(name).resourceValues(forKeys: [.fileSizeKey])
            return total + Int64(values?.fileSize ?? 0)
        }
    }
}

enum VideoDownloadError: LocalizedError {
    case noStream

    var errorDescription: String? {
        switch self {
        case .noStream: String(localized: "此剧集没有可下载的地址")
        }
    }
}

/// One file per stream, straight to disk. Progress arrives as a stream of percents, so the queue stays on the main
/// actor while the transfer runs on URLSession's own queue; cancelling the stream cancels the transfer.
final class VideoFileDownloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var transfers: [Int: AsyncThrowingStream<Int, Error>.Continuation] = [:]
    private var destinations: [Int: URL] = [:]
    private var reported: [Int: Int] = [:]
    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)

    func fetch(url: URL, headers: [String: String], to destination: URL) -> AsyncThrowingStream<Int, Error> {
        var request = URLRequest(url: url, timeoutInterval: 60)
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let task = session.downloadTask(with: request)
        let id = task.taskIdentifier
        return AsyncThrowingStream { continuation in
            self.lock.lock()
            self.transfers[id] = continuation
            self.destinations[id] = destination
            self.reported[id] = 0
            self.lock.unlock()
            continuation.onTermination = { [session = self.session] termination in
                if case .finished = termination { return }
                task.cancel()
                session.invalidateAndCancel()
            }
            task.resume()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        guard totalBytesExpectedToWrite > 0 else { return }
        let percent = min(99, Int(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) * 100))
        lock.lock()
        defer { lock.unlock() }
        guard let continuation = transfers[downloadTask.taskIdentifier], reported[downloadTask.taskIdentifier] != percent
        else { return }
        reported[downloadTask.taskIdentifier] = percent
        continuation.yield(percent)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        // The delegate must move the temporary file before it returns.
        lock.lock()
        let destination = destinations[downloadTask.taskIdentifier]
        lock.unlock()
        guard let destination else { return }
        do {
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.moveItem(at: location, to: destination)
        } catch {
            finish(downloadTask.taskIdentifier, with: .failure(error))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let destination = destinations[task.taskIdentifier]
        if let error { finish(task.taskIdentifier, with: .failure(error)); return }
        guard let destination, FileManager.default.fileExists(atPath: destination.path) else {
            finish(task.taskIdentifier, with: .failure(URLError(.cannotCreateFile)))
            return
        }
        finish(task.taskIdentifier, with: .success(()))
    }

    private func finish(_ id: Int, with result: Result<Void, Error>) {
        // Nothing else uses this one-shot transfer: retire the session so its delegate cycle ends.
        session.finishTasksAndInvalidate()
        lock.lock()
        let continuation = transfers.removeValue(forKey: id)
        destinations.removeValue(forKey: id)
        reported.removeValue(forKey: id)
        lock.unlock()
        continuation?.finish(throwing: result.failureValue)
    }
}

/// Offline HLS through AVAssetDownloadURLSession: the system fetches the playlist, its segments and any keys, and
/// hands back a package the player reopens later with no network. Progress is the share of the running time loaded.
final class VideoHLSDownloader: NSObject, AVAssetDownloadDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var transfers: [Int: AsyncThrowingStream<Int, Error>.Continuation] = [:]
    private var destinations: [Int: URL] = [:]
    private var reported: [Int: Int] = [:]
    private lazy var session = AVAssetDownloadURLSession(configuration: .default, assetDownloadDelegate: self,
                                                         delegateQueue: nil)

    func fetch(url: URL, headers: [String: String], to destination: URL) -> AsyncThrowingStream<Int, Error> {
        let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
        guard let task = session.makeAssetDownloadTask(asset: asset, assetTitle: "video", assetArtworkData: nil,
                                                       options: nil) else {
            return AsyncThrowingStream { $0.finish(throwing: URLError(.unsupportedURL)) }
        }
        let id = task.taskIdentifier
        return AsyncThrowingStream { continuation in
            self.lock.lock()
            self.transfers[id] = continuation
            self.destinations[id] = destination
            self.reported[id] = 0
            self.lock.unlock()
            continuation.onTermination = { [session = self.session] termination in
                if case .finished = termination { return }
                task.cancel()
                session.invalidateAndCancel()
            }
            task.resume()
        }
    }

    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask,
                    didLoad timeRange: CMTimeRange, totalTimeRangesLoaded loaded: [NSValue],
                    timeRangeExpectedToLoad: CMTimeRange) {
        let expected = timeRangeExpectedToLoad.duration.seconds
        guard expected.isFinite, expected > 0 else { return }
        let done = loaded.reduce(0.0) { $0 + $1.timeRangeValue.duration.seconds }
        let percent = min(99, Int(min(1, done / expected) * 100))
        lock.lock()
        defer { lock.unlock() }
        let id = assetDownloadTask.taskIdentifier
        guard let continuation = transfers[id], reported[id] != percent else { return }
        reported[id] = percent
        continuation.yield(percent)
    }

    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask,
                    didFinishDownloadingTo location: URL) {
        lock.lock()
        let destination = destinations[assetDownloadTask.taskIdentifier]
        lock.unlock()
        guard let destination else { return }
        do {
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            // The package is a directory; the player reopens it by this path, with no session involved.
            try FileManager.default.moveItem(at: location, to: destination)
            finish(assetDownloadTask.taskIdentifier, with: .success(()))
        } catch {
            finish(assetDownloadTask.taskIdentifier, with: .failure(error))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        finish(task.taskIdentifier, with: .failure(error))
    }

    private func finish(_ id: Int, with result: Result<Void, Error>) {
        // Nothing else uses this one-shot transfer: retire the session so its delegate cycle ends.
        session.finishTasksAndInvalidate()
        lock.lock()
        let continuation = transfers.removeValue(forKey: id)
        destinations.removeValue(forKey: id)
        reported.removeValue(forKey: id)
        lock.unlock()
        continuation?.finish(throwing: result.failureValue)
    }
}

private extension Result {
    var failureValue: Failure? {
        if case .failure(let error) = self { return error }
        return nil
    }
}
