import Foundation
import KototoroShared
import UIKit

/// The main actor supplies reader visibility and actual playback state; shared Kotlin owns session boundaries.
@MainActor
final class ReadingActivityRecorder {
    private let content: IosSourceContent
    private let tracker: IosReadingTracker
    private var pending: [String: IosReadingSample] = [:]
    private var heartbeat: Task<Void, Never>?
    private var writeTask: Task<Void, Never>?
    private var library: IosLibrary?
    private var active = false
    private(set) var error: String?

    init(content: IosSourceContent) {
        self.content = content
        tracker = IosReadingTracker(content: content)
    }

    func setActive(_ value: Bool) {
        active = value
        if !value { pause() }
    }

    func update(library: IosLibrary, chapter: IosSourceChapter, index: Int, count: Int,
                lastVisible: Int? = nil, scroll: Float) {
        self.library = library
        enqueue(tracker.update(chapter: chapter, index: Int32(index), count: Int32(count),
                               lastVisible: Int32(lastVisible ?? index), scroll: scroll, active: active))
        if active {
            enqueue(tracker.checkpoint())
            if heartbeat == nil {
                heartbeat = Task { [weak self] in
                    while !Task.isCancelled {
                        do { try await Task.sleep(for: .seconds(15)) } catch { return }
                        guard let self else { return }
                        self.enqueue(self.tracker.checkpoint())
                        await self.flush()
                    }
                }
            }
        }
    }

    /// A loading interval ends the current session but preserves the owner's foreground/playback intent.
    func pause() {
        enqueue(tracker.pause())
        heartbeat?.cancel()
        heartbeat = nil
    }

    func flush() async {
        guard let library else { return }
        let previous = writeTask
        let task = Task { [self] in
            await previous?.value
            let background = UIApplication.shared.beginBackgroundTask(withName: "Save reading activity")
            defer { if background != .invalid { UIApplication.shared.endBackgroundTask(background) } }
            while let key = pending.keys.sorted().first, let sample = pending[key] {
                do {
                    try await library.statistics.record(content: content, sample: sample)
                    if pending[key] === sample { pending.removeValue(forKey: key) }
                    error = nil
                } catch {
                    self.error = error.localizedDescription
                    return
                }
            }
        }
        writeTask = task
        await task.value
        // Do not retain a completed chain of tasks throughout a long reading session.
        if pending.isEmpty { writeTask = nil }
    }

    private func enqueue(_ sample: IosReadingSample?) {
        guard let sample else { return }
        pending[sample.key] = sample
    }
}
