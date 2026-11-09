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
        let media = try await MediaFile.fetch(stream, into: dir, title: "\(content.title) · \(chapter.title)") { percent in
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
        static func fetch(_ stream: IosSourcePage, into directory: URL, title: String,
                          progress: @escaping (Int) async throws -> Void) async throws -> MediaFile {
            guard let url = URL(string: stream.url) else { throw URLError(.badURL) }
            let partial = directory.appendingPathComponent(partialName)
            // A transfer that finished in the background while the app was away is used as it is.
            if VideoTransfers.shared.takeFinished(hlsPartial(directory)) {
                return try hlsResult(directory)
            }
            let finished = VideoTransfers.shared.takeFinished(partial)
            if !finished { discardMedia(in: directory) }
            if url.isFileURL {
                return try await copyLocal(url, into: directory)
            }
            if !finished && url.absoluteString.range(of: ".m3u8", options: .caseInsensitive) != nil {
                return try await downloadHLS(url, headers: stream.headers, into: directory, title: title, progress: progress)
            }
            do {
                if !finished {
                    let transfer = VideoTransfers.shared.fetch(url: url, headers: stream.headers, to: partial, hls: false,
                                                               title: title)
                    for try await percent in transfer {
                        try Task.checkCancellation()
                        try await progress(percent)
                    }
                    try Task.checkCancellation()
                    _ = VideoTransfers.shared.takeFinished(partial)
                }
                let downloaded = partial
                // A playlist served under a neutral address must not be saved as if it were the video.
                if try looksLikePlaylist(downloaded) {
                    try? FileManager.default.removeItem(at: downloaded)
                    return try await downloadHLS(url, headers: stream.headers, into: directory, title: title, progress: progress)
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
        private static func downloadHLS(_ url: URL, headers: [String: String], into directory: URL, title: String,
                                        progress: @escaping (Int) async throws -> Void) async throws -> MediaFile {
            let partial = hlsPartial(directory)
            try? FileManager.default.removeItem(at: partial)
            let transfer = VideoTransfers.shared.fetch(url: url, headers: headers, to: partial, hls: true, title: title)
            for try await percent in transfer {
                try Task.checkCancellation()
                try await progress(percent)
            }
            try Task.checkCancellation()
            _ = VideoTransfers.shared.takeFinished(partial)
            return try hlsResult(directory)
        }

        private static func hlsPartial(_ directory: URL) -> URL {
            directory.appendingPathComponent(partialName + ".movpkg")
        }

        /// The finished package, moved to the name the manifest records.
        private static func hlsResult(_ directory: URL) throws -> MediaFile {
            let name = "video.movpkg"
            let target = directory.appendingPathComponent(name)
            try replace(hlsPartial(directory), with: target)
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

/// Episode transfers in the system's background sessions: they go on while the app is suspended, and after the
/// system ends it, so a long episode finishes without the app on screen. Each transfer is labelled with where its
/// result goes (relative to Application Support, since the container path may change between launches). A transfer
/// that finishes with nobody listening (the app was suspended or relaunched in the background) is moved there with a
/// `.finished` marker next to it; the download picks it up from the marker the next time it runs instead of
/// starting over, and one still running is joined instead of started again.
final class VideoTransfers: NSObject, URLSessionDownloadDelegate, AVAssetDownloadDelegate, @unchecked Sendable {
    static let shared = VideoTransfers()
    static let fileSessionID = "org.skepsun.kototoro.ios.video.files"
    static let hlsSessionID = "org.skepsun.kototoro.ios.video.hls"
    private static let finishedSuffix = ".finished"

    private struct Listener {
        let token: UUID
        let continuation: AsyncThrowingStream<Int, Error>.Continuation
        var reported = -1
    }

    private let lock = NSLock()
    private var listeners: [String: Listener] = [:]
    private var eventHandlers: [String: () -> Void] = [:]

    private lazy var files = URLSession(configuration: Self.configuration(Self.fileSessionID), delegate: self,
                                        delegateQueue: nil)
    private lazy var hls = AVAssetDownloadURLSession(configuration: Self.configuration(Self.hlsSessionID),
                                                     assetDownloadDelegate: self, delegateQueue: nil)

    private static func configuration(_ identifier: String) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        return configuration
    }

    /// Reconnects both sessions at launch, so transfers that finished while the app was away are handed over.
    func reconnect() {
        _ = files
        _ = hls
    }

    /// The system relaunched the app for a session's events; [done] is called once they are all delivered.
    func handleEvents(_ identifier: String, done: @escaping () -> Void) {
        lock.withLock { eventHandlers[identifier] = done }
        reconnect()
    }

    /// Percent of the transfer into [destination]; ending the stream leaves the transfer to the system ([cancel]
    /// stops it). [hls] downloads a playlist into an offline package; the system shows its progress under [title].
    func fetch(url: URL, headers: [String: String], to destination: URL, hls: Bool,
               title: String) -> AsyncThrowingStream<Int, Error> {
        let key = Self.key(destination)
        let token = UUID()
        return AsyncThrowingStream { continuation in
            let previous = self.lock.withLock { () -> Listener? in
                defer { self.listeners[key] = Listener(token: token, continuation: continuation) }
                return self.listeners[key]
            }
            previous?.continuation.finish(throwing: CancellationError())
            continuation.onTermination = { [weak self] _ in
                self?.lock.withLock {
                    if self?.listeners[key]?.token == token { self?.listeners[key] = nil }
                }
            }
            self.tasks { tasks in
                if let running = tasks.first(where: { $0.taskDescription == key && $0.state != .completed }) {
                    running.resume()
                    return
                }
                let task: URLSessionTask?
                if hls {
                    let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
                    task = self.hls.makeAssetDownloadTask(asset: asset, assetTitle: title, assetArtworkData: nil,
                                                          options: nil)
                } else {
                    var request = URLRequest(url: url)
                    headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
                    task = self.files.downloadTask(with: request)
                }
                guard let task else { continuation.finish(throwing: URLError(.unsupportedURL)); return }
                task.taskDescription = key
                task.resume()
            }
        }
    }

    /// Whether a transfer into [destination] finished while nobody listened; the marker is consumed.
    func takeFinished(_ destination: URL) -> Bool {
        let marker = URL(fileURLWithPath: destination.path + Self.finishedSuffix)
        guard FileManager.default.fileExists(atPath: marker.path) else { return false }
        try? FileManager.default.removeItem(at: marker)
        return FileManager.default.fileExists(atPath: destination.path)
    }

    /// Stops every transfer into [directory] (the user paused or removed the episode).
    func cancel(directory: URL) {
        let prefix = Self.key(directory) + "/"
        tasks { tasks in
            for task in tasks where task.taskDescription?.hasPrefix(prefix) == true { task.cancel() }
        }
    }

    private func tasks(_ body: @escaping ([URLSessionTask]) -> Void) {
        files.getAllTasks { fileTasks in
            self.hls.getAllTasks { hlsTasks in body(fileTasks + hlsTasks) }
        }
    }

    private static var base: URL { URL.applicationSupportDirectory }

    private static func key(_ url: URL) -> String {
        let path = url.standardizedFileURL.path
        let root = base.standardizedFileURL.path + "/"
        return path.hasPrefix(root) ? String(path.dropFirst(root.count)) : path
    }

    private static func destination(_ key: String) -> URL {
        key.hasPrefix("/") ? URL(fileURLWithPath: key) : base.appendingPathComponent(key)
    }

    private func report(_ task: URLSessionTask, _ fraction: Double) {
        guard let key = task.taskDescription else { return }
        let percent = min(99, Int(min(1, fraction) * 100))
        lock.withLock {
            guard var listener = listeners[key], listener.reported != percent else { return }
            listener.reported = percent
            listeners[key] = listener
            listener.continuation.yield(percent)
        }
    }

    /// Moves the result into place before the delegate returns (the system deletes [location] afterwards) and marks it.
    private func deliver(_ task: URLSessionTask, from location: URL) {
        guard let key = task.taskDescription else { return }
        let target = Self.destination(key)
        do {
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
            try FileManager.default.moveItem(at: location, to: target)
            FileManager.default.createFile(atPath: target.path + Self.finishedSuffix, contents: nil)
        } catch {
            finish(key, throwing: error)
        }
    }

    private func finish(_ key: String, throwing error: Error?) {
        let listener = lock.withLock { listeners.removeValue(forKey: key) }
        listener?.continuation.finish(throwing: error)
    }

    // MARK: Files

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard totalBytesExpectedToWrite > 0 else { return }
        report(downloadTask, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        deliver(downloadTask, from: location)
    }

    // MARK: HLS

    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didLoad timeRange: CMTimeRange,
                    totalTimeRangesLoaded loaded: [NSValue], timeRangeExpectedToLoad: CMTimeRange) {
        let expected = timeRangeExpectedToLoad.duration.seconds
        guard expected.isFinite, expected > 0 else { return }
        report(assetDownloadTask, loaded.reduce(0.0) { $0 + $1.timeRangeValue.duration.seconds } / expected)
    }

    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingTo location: URL) {
        deliver(assetDownloadTask, from: location)
    }

    // MARK: Both

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let key = task.taskDescription else { return }
        if error == nil, !FileManager.default.fileExists(atPath: Self.destination(key).path) {
            finish(key, throwing: URLError(.cannotCreateFile))
        } else {
            finish(key, throwing: error)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        guard let identifier = session.configuration.identifier else { return }
        let done = lock.withLock { eventHandlers.removeValue(forKey: identifier) }
        if let done { DispatchQueue.main.async(execute: done) }
    }
}

private extension Result {
    var failureValue: Failure? {
        if case .failure(let error) = self { return error }
        return nil
    }
}
