import AVFoundation
import MediaPlayer
import SwiftUI

/// Reading aloud with the system voices (Android's TTS): one utterance per line from the line being read, the
/// reader following the spoken line, continuing into the next chapter. Shows what the reader shows: a translation
/// when one replaces the line.
@MainActor
final class NovelSpeech: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    enum Key {
        static let rate = "novel.tts.rate"
        /// A voice identifier; empty picks the system voice for the chapter's language.
        static let voice = "novel.tts.voice"
    }

    @Published private(set) var isActive = false
    @Published private(set) var isPaused = false
    /// The line being spoken.
    @Published private(set) var current: Int?
    /// Called when a line starts, so the reader keeps it in view.
    var onLine: ((Int) -> Void)?
    /// Called after the chapter's last line; returns false when there is no next chapter.
    var onChapterEnd: (() async -> Bool)?

    private let synthesizer = AVSpeechSynthesizer()
    private var lines: [Int: AVSpeechUtterance] = [:]
    private var indices: [ObjectIdentifier: Int] = [:]
    private var last: Int?

    /// Shown on the Lock Screen and in Control Center while reading aloud, as iOS audio apps do.
    var nowPlayingTitle = ""
    var onRemoteSkip: ((Int) -> Void)?

    override init() {
        super.init()
        synthesizer.delegate = self
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in self?.resumeFromRemote() ?? .commandFailed }
        center.pauseCommand.addTarget { [weak self] _ in self?.pauseFromRemote() ?? .commandFailed }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self, self.isActive else { return .commandFailed }
            self.togglePause(); return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            guard let self, self.isActive else { return .commandFailed }
            self.onRemoteSkip?(1); return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            guard let self, self.isActive else { return .commandFailed }
            self.onRemoteSkip?(-1); return .success
        }
    }

    private func resumeFromRemote() -> MPRemoteCommandHandlerStatus {
        guard isActive, isPaused else { return .commandFailed }
        togglePause(); return .success
    }

    private func pauseFromRemote() -> MPRemoteCommandHandlerStatus {
        guard isActive, !isPaused else { return .commandFailed }
        togglePause(); return .success
    }

    private func publishNowPlaying(line: String?) {
        guard isActive else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: nowPlayingTitle,
            MPMediaItemPropertyArtist: line ?? "",
            MPNowPlayingInfoPropertyPlaybackRate: isPaused ? 0.0 : 1.0,
        ]
    }

    static var rate: Float {
        let stored = UserDefaults.standard.object(forKey: Key.rate) as? Double
        return Float(stored ?? Double(AVSpeechUtteranceDefaultSpeechRate))
    }

    /// Speaks [texts] (line index → text) from line [start]; [language] picks the voice when none is chosen.
    func start(_ texts: [(Int, String)], from start: Int, language: String?) {
        stop()
        let spoken = texts.filter { $0.0 >= start && $0.1.contains(where: \.isLetter) }
        guard !spoken.isEmpty else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
        let stored = UserDefaults.standard.string(forKey: Key.voice) ?? ""
        let voice = stored.isEmpty ? language.flatMap(AVSpeechSynthesisVoice.init(language:))
                                   : AVSpeechSynthesisVoice(identifier: stored)
        for (index, text) in spoken {
            let utterance = AVSpeechUtterance(string: text)
            utterance.rate = Self.rate
            utterance.voice = voice
            utterance.postUtteranceDelay = 0.15
            indices[ObjectIdentifier(utterance)] = index
            lines[index] = utterance
            synthesizer.speak(utterance)
        }
        last = spoken.last?.0
        isActive = true
        isPaused = false
        publishNowPlaying(line: nil)
    }

    func togglePause() {
        guard isActive else { return }
        if isPaused { synthesizer.continueSpeaking() } else { synthesizer.pauseSpeaking(at: .word) }
        isPaused.toggle()
        publishNowPlaying(line: nil)
    }

    func stop() {
        indices = [:]
        lines = [:]
        last = nil
        synthesizer.stopSpeaking(at: .immediate)
        isActive = false
        isPaused = false
        current = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in
            guard let index = self.indices[id] else { return }
            self.current = index
            self.onLine?(index)
            self.publishNowPlaying(line: utterance.speechString)
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        Task { @MainActor in
            guard let index = self.indices.removeValue(forKey: id), index == self.last else { return }
            // The chapter is done: the owner opens the next one and starts again, or speech ends.
            self.isActive = false
            self.current = nil
            if await self.onChapterEnd?() != true { self.stop() }
        }
    }
}

/// The reading-aloud bar above the progress control: previous/next line, pause and stop, with the speed.
struct NovelSpeechBar: View {
    @ObservedObject var speech: NovelSpeech
    let onSkip: (Int) -> Void

    var body: some View {
        HStack(spacing: 14) {
            Button("上一段", systemImage: "backward.fill") { onSkip(-1) }
                .accessibilityIdentifier("novel.speech.previous")
            Button(speech.isPaused ? "继续" : "暂停", systemImage: speech.isPaused ? "play.fill" : "pause.fill") {
                speech.togglePause()
            }
            .accessibilityIdentifier("novel.speech.pause")
            Button("下一段", systemImage: "forward.fill") { onSkip(1) }
                .accessibilityIdentifier("novel.speech.next")
            Button("停止朗读", systemImage: "stop.fill") { speech.stop() }
                .accessibilityIdentifier("novel.speech.stop")
        }
        .labelStyle(.iconOnly)
        .font(.title3)
        .buttonStyle(.plain)
        .padding(.horizontal, 18).padding(.vertical, 10)
        .glassEffect(.regular.interactive(), in: .capsule)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("novel.speechBar")
    }
}

/// Speed and voice of reading aloud, in the reader's settings sheet.
struct NovelSpeechSettingsSection: View {
    @AppStorage(NovelSpeech.Key.rate) private var rate = Double(AVSpeechUtteranceDefaultSpeechRate)
    @AppStorage(NovelSpeech.Key.voice) private var voice = ""
    private let voices = AVSpeechSynthesisVoice.speechVoices()
        .sorted { ($0.language, $0.name) < ($1.language, $1.name) }

    var body: some View {
        Section {
            VStack(alignment: .leading) {
                Text("语速 \(String(format: "%.2f", rate / Double(AVSpeechUtteranceDefaultSpeechRate)))×")
                Slider(value: $rate, in: Double(AVSpeechUtteranceMinimumSpeechRate)...Double(AVSpeechUtteranceMaximumSpeechRate))
                    .accessibilityIdentifier("novel.settings.speechRate")
            }
            Picker("声音", selection: $voice) {
                Text("自动（按正文语言）").tag("")
                ForEach(voices, id: \.identifier) { voice in
                    Text("\(voice.name) · \(Locale.current.localizedString(forIdentifier: voice.language) ?? voice.language)")
                        .tag(voice.identifier)
                }
            }
            .accessibilityIdentifier("novel.settings.speechVoice")
        } header: {
            Text("朗读")
        } footer: {
            Text("使用 iOS 系统语音；在“设置 → 辅助功能 → 朗读内容 → 声音”中可下载更自然的增强语音。新设置从下一段开始生效。")
        }
    }
}

/// The bar while reading aloud, nothing otherwise.
struct NovelSpeechBarIfActive: View {
    @ObservedObject var speech: NovelSpeech
    let onSkip: (Int) -> Void

    var body: some View {
        if speech.isActive { NovelSpeechBar(speech: speech, onSkip: onSkip) }
    }
}

/// Search within the open chapter (Android's in-text search): matching lines with their context; choosing one
/// jumps there in either reading mode.
struct NovelChapterSearchSheet: View {
    let lines: [(Int, String)]
    let onSelect: (Int) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private struct Match: Identifiable {
        let id: Int
        let snippet: AttributedString
    }

    private var matches: [Match] {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return [] }
        return lines.compactMap { index, line in
            guard let range = line.range(of: text, options: [.caseInsensitive, .diacriticInsensitive]) else { return nil }
            // About twenty characters either side of the first hit.
            let start = line.index(range.lowerBound, offsetBy: -20, limitedBy: line.startIndex) ?? line.startIndex
            let end = line.index(range.upperBound, offsetBy: 40, limitedBy: line.endIndex) ?? line.endIndex
            var snippet = AttributedString((start > line.startIndex ? "…" : "") + line[start..<range.lowerBound])
            var hit = AttributedString(line[range])
            hit.font = .body.bold()
            hit.foregroundColor = .accentColor
            snippet += hit
            snippet += AttributedString(line[range.upperBound..<end] + (end < line.endIndex ? "…" : ""))
            return Match(id: index, snippet: snippet)
        }
    }

    var body: some View {
        NavigationStack {
            let found = matches
            List {
                if !query.isEmpty {
                    Section(found.isEmpty ? String(localized: "没有找到") : String(localized: "\(found.count) 处")) {
                        ForEach(found) { match in
                            Button { onSelect(match.id) } label: {
                                Text(match.snippet).lineLimit(3).foregroundStyle(.primary)
                            }
                            .accessibilityIdentifier("novel.search.result")
                        }
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索本章正文")
            .navigationTitle("搜索本章")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}
