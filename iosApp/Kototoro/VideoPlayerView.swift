import AVFoundation
import AVKit
import Combine
import KototoroShared
import SwiftUI

/// Resolves an episode through the shared runtime for AVPlayerLayer and the app's controls, with system AirPlay
/// and Picture in Picture support. Advances to the next episode of the same line at the end.
@MainActor
final class VideoPlayerModel: ObservableObject {
    private lazy var activity = ReadingActivityRecorder(content: content)

    func finishReadingActivity() async {
        activity.setActive(false)
        await activity.flush()
    }
    @Published private(set) var chapter: IosSourceChapter
    @Published private(set) var player: AVPlayer?
    @Published private(set) var error: String?
    @Published private(set) var isLoading = false
    @Published private(set) var position: Double = 0
    /// Every playable stream of the episode (Aniyomi qualities/hosters, CMS single stream); the preferred one first.
    @Published private(set) var streams: [IosSourcePage] = []
    @Published private(set) var streamIndex = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = false
    @Published private(set) var rate: Float = 1
    /// Audio and subtitle tracks of the current stream (HLS renditions, embedded tracks).
    @Published private(set) var audioOptions: [AVMediaSelectionOption] = []
    @Published private(set) var subtitleOptions: [AVMediaSelectionOption] = []
    @Published private(set) var selectedAudio: AVMediaSelectionOption?
    @Published private(set) var selectedSubtitle: AVMediaSelectionOption?
    private var audioGroup: AVMediaSelectionGroup?
    private var subtitleGroup: AVMediaSelectionGroup?
    /// Side-loaded subtitles of the current stream (Aniyomi `subtitleTracks`). AVPlayer cannot add a subtitle file to
    /// an HLS or progressive asset, so their cues are drawn over the video as [subtitleText].
    @Published private(set) var externalSubtitles: [SubtitleTrack] = []
    @Published private(set) var selectedExternal: Int?
    @Published private(set) var failedExternal: Set<Int> = []
    @Published private(set) var subtitleText: String?
    private var cues: IosSubtitles?
    private var cueObserver: Any?
    private var subtitleTask: Task<Void, Never>?
    /// Playing the copy on disk, not a stream; the viewer is told so the source does not look broken.
    @Published private(set) var isOffline = false
    /// The episode is already downloaded (by this player or from the episode list).
    @Published private(set) var isDownloaded = false
    /// The viewer's last choice, kept across streams and episodes of this session.
    private var subtitlesOff = false
    private var preferredSubtitle: String?
    let runtime: IosSources
    let content: IosSourceContent
    /// Bridge the full episode list once; opening the picker must not repeatedly map Kotlin objects.
    let chapters: [IosSourceChapter]
    let lines: [String?]
    private var request: UUID?
    private var timeObserver: Any?
    private var observers: [AnyCancellable] = []
    var library: IosLibrary?
    /// An incognito session (`IncognitoGate`) records no history, progress, statistics or tracker updates.
    var incognito = false
    private var lastSavedAt = 0.0

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        self.runtime = runtime
        self.content = content
        self.chapter = chapter
        let episodes = content.chapters
        chapters = episodes
        var seen = Set<String?>()
        lines = episodes.map(\.line).filter { seen.insert($0).inserted }
    }

    func episodes(on line: String?) -> [IosSourceChapter] { chapters.filter { $0.line == line } }

    var previous: IosSourceChapter? { adjacent(forward: false) }
    var next: IosSourceChapter? { adjacent(forward: true) }

    /// Shared navigation keeps the source/branch rules; episodes additionally stay on the current playback line.
    private func adjacent(forward: Bool) -> IosSourceChapter? {
        var candidate = content.adjacentChapter(chapter: chapter, forward: forward)
        while let current = candidate, current.line != chapter.line {
            candidate = content.adjacentChapter(chapter: current, forward: forward)
        }
        return candidate
    }

    func load() async {
        let request = UUID()
        self.request = request
        let selected = chapter
        isLoading = true
        error = nil
        defer { if self.request == request { isLoading = false } }
        isDownloaded = VideoDownloads.isDownloaded(content: content, chapter: selected)
        do {
            // A finished download plays from disk: no source request, no stale hoster, no subtitles to fetch.
            if let offline = VideoDownloads.offline(content: content, chapter: selected) {
                if position == 0, let saved = try await library?.position(content: content), saved.chapterId == selected.id {
                    position = Double(saved.scroll)
                }
                try Task.checkCancellation()
                guard self.request == request else { return }
                play(offline, from: position)
                return
            }
            let pages = try await runtime.pages(chapter: selected)
            try Task.checkCancellation()
            guard self.request == request else { return }
            guard !pages.isEmpty else {
                throw NSError(domain: "org.skepsun.kototoro.video", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: String(localized: "此剧集没有可播放的地址")])
            }
            streams = pages
            if position == 0, let saved = try await library?.position(content: content), saved.chapterId == selected.id {
                position = Double(saved.scroll)
            }
            select(stream: 0)
        } catch {
            if !Task.isCancelled, self.request == request { self.error = error.localizedDescription }
        }
    }

    /// Play the downloaded episode: its own subtitles, and no headers because nothing leaves the device.
    private func play(_ offline: VideoDownloads.Offline, from start: Double) {
        error = nil
        isOffline = true
        streams = []
        streamIndex = 0
        externalSubtitles = offline.subtitles
        play(url: offline.url, headers: [:], from: start)
    }

    /// Re-read the download state after a download finishes while the player is open.
    func refreshDownloadState() {
        isDownloaded = VideoDownloads.isDownloaded(content: content, chapter: chapter)
    }

    /// Switches stream and resumes from the current position.
    func select(stream index: Int) {
        guard streams.indices.contains(index) else { return }
        let resume = position
        stop(keepStreams: true)
        streamIndex = index
        guard let url = URL(string: streams[index].url) else {
            error = String(localized: "无效的播放地址")
            return
        }
        error = nil
        externalSubtitles = streams[index].subtitles.map(SubtitleTrack.init)
        play(url: url, headers: streams[index].headers, from: resume)
    }

    func move(to target: IosSourceChapter) async {
        await persist()
        stop()
        chapter = target
        await load()
    }

    func stop(keepStreams: Bool = false) {
        activity.setActive(false)
        isOffline = false
        Task { await activity.flush() }
        if !keepStreams {
            streams = []
            streamIndex = 0
        }
        if let timeObserver, let player { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        clearExternalSubtitle()
        observers.removeAll()
        player?.pause()
        player = nil
        position = 0
        duration = 0
        isPlaying = false
        lastSavedAt = 0
        audioOptions = []; subtitleOptions = []; selectedAudio = nil; selectedSubtitle = nil
        audioGroup = nil; subtitleGroup = nil
        externalSubtitles = []
        failedExternal = []
    }

    func togglePlay() {
        guard let player else { return }
        if player.timeControlStatus == .paused { player.playImmediately(atRate: rate) } else { player.pause() }
    }

    func seek(to seconds: Double) {
        guard let player else { return }
        let target = max(0, duration > 0 ? min(seconds, duration - 0.5) : seconds)
        position = target
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func skip(by seconds: Double) { seek(to: position + seconds) }

    func setRate(_ value: Float) {
        rate = value
        if let player, player.timeControlStatus != .paused { player.rate = value }
    }

    func selectAudio(_ option: AVMediaSelectionOption?) {
        guard let group = audioGroup, let item = player?.currentItem else { return }
        item.select(option, in: group)
        selectedAudio = option
    }

    func selectSubtitle(_ option: AVMediaSelectionOption?) {
        subtitlesOff = option == nil
        if option != nil { clearExternalSubtitle() }
        guard let group = subtitleGroup, let item = player?.currentItem else { return }
        item.select(option, in: group)
        selectedSubtitle = option
    }

    func selectExternalSubtitle(_ index: Int) {
        guard externalSubtitles.indices.contains(index) else { return }
        subtitlesOff = false
        preferredSubtitle = externalSubtitles[index].lang
        if let group = subtitleGroup, let item = player?.currentItem { item.select(nil, in: group) }
        selectedSubtitle = nil
        loadExternalSubtitle(index)
    }

    /// Embedded subtitles the stream already turns on win; otherwise the viewer's last language, then theirs.
    private func selectDefaultExternalSubtitle() {
        guard !subtitlesOff, selectedSubtitle == nil, selectedExternal == nil,
              let index = SubtitleLanguage.preferredIndex(externalSubtitles.map(\.lang), last: preferredSubtitle)
        else { return }
        loadExternalSubtitle(index)
    }

    private func loadExternalSubtitle(_ index: Int) {
        clearExternalSubtitle()
        selectedExternal = index
        failedExternal.remove(index)
        let track = externalSubtitles[index]
        let streamHeaders = streams.indices.contains(streamIndex) ? streams[streamIndex].headers : [:]
        subtitleTask = Task { [weak self] in
            do {
                let text = try await SubtitleFile.download(track, streamHeaders: streamHeaders)
                let parsed = IosSubtitles.companion.parse(text: text, name: track.url)
                try Task.checkCancellation()
                guard let self, self.selectedExternal == index else { return }
                guard !parsed.isEmpty else { throw URLError(.cannotParseResponse) }
                self.cues = parsed
                self.observeCues()
            } catch {
                guard !Task.isCancelled, let self, self.selectedExternal == index else { return }
                self.failedExternal.insert(index)
                self.selectedExternal = nil
            }
        }
    }

    private func observeCues() {
        guard let player, cueObserver == nil else { return }
        // Finer than the progress observer, and publishing only when the shown text changes.
        cueObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) {
            [weak self] time in
            MainActor.assumeIsolated {
                guard let self, let cues = self.cues, time.seconds.isFinite else { return }
                let text = cues.textAt(ms: Int64(time.seconds * 1000))
                if text != self.subtitleText { self.subtitleText = text }
            }
        }
    }

    private func clearExternalSubtitle() {
        subtitleTask?.cancel()
        subtitleTask = nil
        if let cueObserver, let player { player.removeTimeObserver(cueObserver) }
        cueObserver = nil
        cues = nil
        selectedExternal = nil
        subtitleText = nil
    }

    private func loadTracks(_ item: AVPlayerItem) async {
        let asset = item.asset
        let audio = try? await asset.loadMediaSelectionGroup(for: .audible)
        let legible = try? await asset.loadMediaSelectionGroup(for: .legible)
        guard player?.currentItem === item else { return }
        audioGroup = audio
        subtitleGroup = legible
        audioOptions = audio?.options ?? []
        subtitleOptions = legible?.options.filter { !$0.hasMediaCharacteristic(.containsOnlyForcedSubtitles) } ?? []
        selectedAudio = audio.flatMap { item.currentMediaSelection.selectedMediaOption(in: $0) }
        selectedSubtitle = legible.flatMap { item.currentMediaSelection.selectedMediaOption(in: $0) }
        selectDefaultExternalSubtitle()
    }

    /// Saves the current moment as a note (Android's video time-point notes) with its frame when the media
    /// allows one (streams such as HLS give no frame); returns what to tell the viewer.
    func saveNote(_ text: String) async -> String {
        guard let library else { return String(localized: "无法保存笔记") }
        let duration = player?.currentItem?.duration.seconds ?? 0
        var path: String?
        if let asset = player?.currentItem?.asset {
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 1280, height: 1280)
            if let frame = try? await generator.image(at: CMTime(seconds: position, preferredTimescale: 600)).image {
                path = NoteImages.save(UIImage(cgImage: frame))
            }
        }
        do {
            _ = try await library.notes.saveVideo(content: content, chapter: chapter, positionMs: Int64(position * 1000),
                durationMs: duration.isFinite ? Int64(duration * 1000) : 0, imagePath: path, note: text)
            return String(localized: "已存为笔记")
        } catch {
            NoteImages.remove(path)
            return error.localizedDescription
        }
    }

    func persist() async {
        guard !incognito else { return }
        guard position.isFinite, position > 0, let library else { return }
        let duration = player?.currentItem?.duration.seconds ?? 0
        let progress = duration.isFinite && duration > 0 ? min(999, Int(position / duration * 1000)) : 0
        activity.update(library: library, chapter: chapter, index: progress, count: 1000, scroll: Float(position))
        await activity.flush()
        do { try await library.recordPosition(content: content, chapter: chapter, index: Int32(progress),
                                              count: 1000, scroll: Float(position)) }
        catch { self.error = error.localizedDescription }
    }

    private func play(url: URL, headers: [String: String], from start: Double = 0) {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        // Sites commonly require their Referer / User-Agent on every playlist and segment request.
        let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let item = AVPlayerItem(asset: asset)
        let player = AVPlayer(playerItem: item)
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
                                                      queue: .main) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self, time.seconds.isFinite else { return }
                self.position = time.seconds
                if let seconds = player.currentItem?.duration.seconds, seconds.isFinite, seconds > 0 { self.duration = seconds }
                if abs(self.position - self.lastSavedAt) >= 5 {
                    self.lastSavedAt = self.position
                    Task { await self.persist() }
                }
            }
        }
        NotificationCenter.default.publisher(for: AVPlayerItem.didPlayToEndTimeNotification, object: item)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self, let next = self.next else { return }
                Task { await self.move(to: next) }
            }
            .store(in: &observers)
        player.publisher(for: \.timeControlStatus)
            .receive(on: RunLoop.main)
            .sink { [weak self] status in
                self?.isPlaying = status != .paused
                self?.isBuffering = status == .waitingToPlayAtSpecifiedRate
                self?.activity.setActive(status == .playing)
                Task { await self?.persist() }
            }
            .store(in: &observers)
        item.publisher(for: \.status)
            .receive(on: RunLoop.main)
            .sink { [weak self] status in
                if status == .readyToPlay { Task { await self?.loadTracks(item) } }
                guard let self, status == .failed else { return }
                // Hosters go stale independently; try the episode's next stream before reporting a failure.
                if self.streamIndex + 1 < self.streams.count {
                    self.select(stream: self.streamIndex + 1)
                } else {
                    self.error = item.error?.localizedDescription ?? String(localized: "无法播放此剧集")
                }
            }
            .store(in: &observers)
        self.player = player
        if start > 1 { player.seek(to: CMTime(seconds: start, preferredTimescale: 600)) }
        player.playImmediately(atRate: rate)
    }
}

/// One set of controls over the video (no system control bar to collide with): shown on tap, hidden after a few
/// seconds of playback, always shown while paused. PiP and AirPlay use the system controllers.
struct VideoPlayerView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var downloads: ReaderDownloadQueue
    @StateObject private var model: VideoPlayerModel
    @Environment(\.dismiss) private var dismiss
    @State private var controlsVisible = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrubbing: Double?
    @State private var fill = false
    @State private var pip = PictureInPicture()
    @State private var showingEpisodes = false
    @State private var downloadError: String?
    @State private var noting = false
    @State private var noteText = ""
    @State private var noteMessage: String?

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter, incognito: Bool = false) {
        let model = VideoPlayerModel(runtime: runtime, content: content, chapter: chapter)
        model.incognito = incognito
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let player = model.player {
                PlayerLayerView(player: player, fill: fill, pip: pip)
                    .ignoresSafeArea()
                    .accessibilityIdentifier("player.view")
            }
            // Taps anywhere toggle the controls; double taps switch between fit and fill.
            Color.clear
                .contentShape(Rectangle())
                .ignoresSafeArea()
                .onTapGesture(count: 2) { withAnimation(.snappy) { fill.toggle() } }
                .onTapGesture { setControls(!controlsVisible) }
            if model.isLoading || model.isBuffering { ProgressView().tint(.white).controlSize(.large).allowsHitTesting(false) }
            if let error = model.error {
                VStack(spacing: 12) {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("player.error")
                    Button("重试") { Task { await model.move(to: model.chapter) } }.buttonStyle(.glass)
                }
                .padding()
            }
            if controlsVisible {
                controls.transition(.opacity)
            }
            // Above the controls' gradient, which would otherwise dim it; it never takes touches.
            if let text = model.subtitleText {
                SubtitleOverlay(text: text, raised: controlsVisible)
            }
            // Playback progress for VoiceOver and UI tests, independent of whether the controls are shown.
            Text("已播放 \(Int(model.position)) 秒")
                .font(.caption2).opacity(0.01).allowsHitTesting(false)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .accessibilityIdentifier("player.position")
        }
        .statusBarHidden(!controlsVisible)
        .persistentSystemOverlays(controlsVisible ? .automatic : .hidden)
        .task { model.library = library.library; await model.load() }
        .onChange(of: model.isPlaying) { _, playing in if playing { scheduleHide() } else { setControls(true) } }
        .onChange(of: downloads.completedContentKeys) { _, _ in model.refreshDownloadState() }
        .sheet(isPresented: $showingEpisodes, onDismiss: { setControls(true) }) {
            VideoEpisodesView(model: model)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .alert("无法下载", isPresented: Binding(get: { downloadError != nil },
                                            set: { if !$0 { downloadError = nil } })) {
            Button("好") { downloadError = nil }
        } message: { Text(downloadError ?? "") }
        .alert("记下这一刻", isPresented: $noting) {
            TextField("写下想法（可选）", text: $noteText)
            Button("保存") { Task { noteMessage = await model.saveNote(noteText) } }
            Button("取消", role: .cancel) {}
        }
        .alert(noteMessage ?? "", isPresented: Binding(get: { noteMessage != nil }, set: { if !$0 { noteMessage = nil } })) {
            Button("好") { noteMessage = nil }
        }
        .onDisappear {
            Task {
                await model.persist()
                await model.finishReadingActivity()
                model.stop()
                await library.refresh()
                if !model.incognito {
                    await library.trackingAccounts.syncAfterReading(model.content, tracking: library.library?.tracking)
                }
            }
        }
    }

    // MARK: Controls

    private var controls: some View {
        GlassEffectContainer(spacing: 12) {
            ZStack {
                // Lighter than the scrim the flat controls needed: the glass carries most of the
                // separation now, and a heavy gradient would flatten it back into a dark bar.
                LinearGradient(colors: [.black.opacity(0.34), .clear, .clear, .black.opacity(0.46)],
                               startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                VStack(spacing: 0) {
                    topBar
                    Spacer(minLength: 0)
                    centerBar
                    Spacer(minLength: 0)
                    bottomBar
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .foregroundStyle(.white)
        }
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            Button("关闭", systemImage: "xmark") { dismiss() }
                .labelStyle(.iconOnly)
                .font(.title3.weight(.semibold))
                .frame(width: 44, height: 44)
                .contentShape(Circle())
                .glassEffect(.regular.interactive(), in: .circle)
                .accessibilityIdentifier("player.close")
            VStack(alignment: .leading, spacing: 1) {
                Text(model.content.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                HStack(spacing: 6) {
                    Text(SourceDetailsView.episodeTitle(model.chapter)).lineLimit(1)
                        .accessibilityIdentifier("player.title")
                    if model.isOffline {
                        Label("离线", systemImage: "arrow.down.circle.fill")
                            .labelStyle(.titleAndIcon)
                            .accessibilityIdentifier("player.offline")
                    }
                }
                .font(.caption).foregroundStyle(.white.opacity(0.8))
            }
            Spacer(minLength: 0)
            Button("记笔记", systemImage: "note.text.badge.plus") {
                hideTask?.cancel()
                model.player?.pause()
                noteText = ""
                noting = true
            }
            .labelStyle(.iconOnly).font(.title3).frame(width: 44, height: 44)
            .glassEffect(.regular.interactive(), in: .circle)
            .accessibilityIdentifier("player.note")
            if model.chapters.count > 1 {
                Button("选集", systemImage: "list.bullet") {
                    hideTask?.cancel()
                    showingEpisodes = true
                }
                .labelStyle(.iconOnly).font(.title3).frame(width: 44, height: 44)
                .glassEffect(.regular.interactive(), in: .circle)
                .accessibilityIdentifier("player.episodes")
            }
            // The route picker draws its own glyph; it only borrows the circle so the row stays level.
            AirPlayButton().frame(width: 44, height: 44)
                .glassEffect(.regular, in: .circle)
            if pip.isPossible {
                Button("画中画", systemImage: "pip.enter") { pip.start() }
                    .labelStyle(.iconOnly).font(.title3).frame(width: 44, height: 44)
                    .glassEffect(.regular.interactive(), in: .circle)
                    .accessibilityIdentifier("player.pip")
            }
            if model.streams.count > 1 {
                Menu {
                    Picker("线路", selection: Binding(get: { model.streamIndex }, set: { model.select(stream: $0) })) {
                        ForEach(Array(model.streams.enumerated()), id: \.offset) { index, stream in
                            Text(Self.streamName(stream, index)).tag(index)
                        }
                    }
                } label: {
                    Label("线路", systemImage: "antenna.radiowaves.left.and.right").labelStyle(.iconOnly)
                        .font(.title3).frame(width: 44, height: 44)
                }
                .glassEffect(.regular.interactive(), in: .circle)
                .accessibilityIdentifier("player.streams")
            }
        }
        .buttonStyle(.plain)
    }

    private var centerBar: some View {
        HStack(spacing: 16) {
            if let previous = model.previous {
                circleButton(String(localized: "上一集"), "backward.end.fill", size: 22) { Task { await model.move(to: previous) } }
                    .accessibilityIdentifier("player.previous")
            }
            circleButton(String(localized: "后退 10 秒"), "gobackward.10", size: 26) { model.skip(by: -10) }
            circleButton(model.isPlaying ? String(localized: "暂停") : String(localized: "播放"),
                         model.isPlaying ? "pause.fill" : "play.fill", size: 40, tinted: true) {
                model.togglePlay()
            }
            .accessibilityIdentifier("player.playPause")
            circleButton(String(localized: "前进 10 秒"), "goforward.10", size: 26) { model.skip(by: 10) }
            if let next = model.next {
                circleButton(String(localized: "下一集"), "forward.end.fill", size: 22) { Task { await model.move(to: next) } }
                    .accessibilityIdentifier("player.next")
            }
        }
    }

    /// Circle glass buttons: the diameter follows the glyph but never drops below the 44 pt touch target.
    private func circleButton(_ title: String, _ icon: String, size: CGFloat, tinted: Bool = false,
                              action: @escaping () -> Void) -> some View {
        Button { action(); scheduleHide() } label: {
            Image(systemName: icon).font(.system(size: size, weight: .semibold))
                .frame(width: max(44, size * 1.6), height: max(44, size * 1.6))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .glassEffect(tinted ? .regular.tint(.accentColor).interactive() : .regular.interactive(), in: .circle)
        .accessibilityLabel(title)
    }

    private var bottomBar: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Text(Self.time(scrubbing ?? model.position)).font(.caption.monospacedDigit()).fixedSize()
                Slider(value: Binding(get: { scrubbing ?? model.position },
                                      set: { scrubbing = $0 }),
                       in: 0...max(model.duration, 1)) { editing in
                    if !editing, let target = scrubbing { model.seek(to: target); scrubbing = nil; scheduleHide() }
                    if editing { hideTask?.cancel() }
                }
                .tint(.white)
                .disabled(model.duration <= 0)
                .accessibilityIdentifier("player.scrubber")
                Text(Self.time(model.duration)).font(.caption.monospacedDigit()).fixedSize().opacity(0.75)
            }
            .padding(.horizontal, 14)
            .frame(height: 44)
            .glassEffect(.regular, in: .capsule)
            HStack(spacing: 10) {
                if let status = downloadStatus { Text(status).font(.caption).opacity(0.8) }
                Spacer(minLength: 0)
                Menu {
                    Picker("播放速度", selection: Binding(get: { model.rate }, set: { model.setRate($0) })) {
                        ForEach([0.5, 0.75, 1, 1.25, 1.5, 2] as [Float], id: \.self) { Text(Self.rateTitle($0)).tag($0) }
                    }
                } label: {
                    Text(Self.rateTitle(model.rate)).font(.footnote.weight(.semibold))
                        .frame(height: 44).padding(.horizontal, 12)
                }
                .glassEffect(.regular.interactive(), in: .capsule)
                .accessibilityIdentifier("player.rate")
                if !model.audioOptions.isEmpty || !model.subtitleOptions.isEmpty || !model.externalSubtitles.isEmpty {
                    Menu {
                        if model.audioOptions.count > 1 {
                            Picker("音轨", selection: Binding(get: { model.selectedAudio }, set: { model.selectAudio($0) })) {
                                ForEach(model.audioOptions, id: \.self) { Text($0.displayName).tag(Optional($0)) }
                            }
                        }
                        if !model.subtitleOptions.isEmpty || !model.externalSubtitles.isEmpty {
                            Picker("字幕", selection: subtitleChoice) {
                                Text("关闭").tag(SubtitleChoice.off)
                                ForEach(model.subtitleOptions, id: \.self) { Text($0.displayName).tag(SubtitleChoice.embedded($0)) }
                                ForEach(Array(model.externalSubtitles.enumerated()), id: \.offset) { index, track in
                                    Text(Self.subtitleName(track, index, failed: model.failedExternal.contains(index)))
                                        .tag(SubtitleChoice.external(index))
                                }
                            }
                        }
                    } label: {
                        Image(systemName: "captions.bubble").font(.title3).frame(width: 44, height: 44)
                    }
                    .glassEffect(.regular.interactive(), in: .circle)
                    .accessibilityIdentifier("player.tracks")
                }
                Button(fill ? "适应屏幕" : "填充屏幕",
                       systemImage: fill ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right") {
                    withAnimation(.snappy) { fill.toggle() }
                }
                .labelStyle(.iconOnly).font(.title3).frame(width: 44, height: 44)
                .glassEffect(.regular.interactive(), in: .circle)
                downloadButton
            }
            .font(.caption)
        }
        .buttonStyle(.plain)
    }

    /// The progress the download queue shows for this episode, once it has a task.
    private var downloadStatus: String? {
        guard let entry = downloads.entry(content: model.content, chapter: model.chapter),
              entry.state != .completed else { return nil }
        return entry.status
    }

    private var downloadButton: some View {
        let entry = downloads.entry(content: model.content, chapter: model.chapter)
        let done = model.isDownloaded || entry?.state == .completed
        let busy = entry?.state == .queued || entry?.state == .running
        return Button {
            do {
                try downloads.enqueue(content: model.content, chapters: [model.chapter],
                                      streamURL: model.streams.indices.contains(model.streamIndex)
                                          ? model.streams[model.streamIndex].url : nil)
                setControls(true)
            } catch { downloadError = error.localizedDescription }
        } label: {
            Image(systemName: done ? "checkmark.circle.fill" : (busy ? "arrow.down.circle.dotted" : "arrow.down.circle"))
                .font(.title3).frame(width: 44, height: 44)
        }
        .glassEffect(.regular.interactive(), in: .circle)
        .disabled(done || busy)
        .accessibilityLabel(done ? String(localized: "本集已下载") : String(localized: "下载本集"))
        .accessibilityIdentifier("player.download")
    }

    private var subtitleChoice: Binding<SubtitleChoice> {
        Binding(
            get: {
                if let index = model.selectedExternal { return .external(index) }
                return model.selectedSubtitle.map(SubtitleChoice.embedded) ?? .off
            },
            set: { choice in
                switch choice {
                case .off: model.selectSubtitle(nil)
                case .embedded(let option): model.selectSubtitle(option)
                case .external(let index): model.selectExternalSubtitle(index)
                }
            }
        )
    }

    // MARK: Visibility

    private func setControls(_ visible: Bool) {
        withAnimation(.easeInOut(duration: 0.2)) { controlsVisible = visible }
        if visible { scheduleHide() } else { hideTask?.cancel() }
    }

    /// Controls fade out after a few seconds of playback; paused playback keeps them on screen.
    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(3.5))
            guard !Task.isCancelled, model.isPlaying, scrubbing == nil, !showingEpisodes else { return }
            withAnimation(.easeInOut(duration: 0.3)) { controlsVisible = false }
        }
    }

    static func time(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds > 0 else { return "0:00" }
        let total = Int(seconds)
        return total >= 3600 ? String(format: "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
            : String(format: "%d:%02d", total / 60, total % 60)
    }

    static func rateTitle(_ rate: Float) -> String {
        rate == 1 ? "1×" : String(format: "%g×", rate)
    }
}

/// Episode groups use chapter metadata; the player's stream menu handles the selected episode's hosters/qualities.
private struct VideoEpisodesView: View {
    @ObservedObject var model: VideoPlayerModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedLine: String?

    init(model: VideoPlayerModel) {
        self.model = model
        _selectedLine = State(initialValue: model.chapter.line)
    }

    var body: some View {
        NavigationStack {
            List {
                if model.lines.count > 1 {
                    Section {
                        Picker("线路", selection: $selectedLine) {
                            ForEach(model.lines, id: \.self) { line in
                                Text(line ?? String(localized: "默认")).tag(line)
                            }
                        }
                        .pickerStyle(.menu)
                        .accessibilityIdentifier("player.episodeLine")
                    }
                }
                Section("剧集") {
                    ForEach(model.episodes(on: selectedLine), id: \.id) { chapter in
                        Button {
                            dismiss()
                            if chapter.id != model.chapter.id { Task { await model.move(to: chapter) } }
                        } label: {
                            HStack {
                                Text(SourceDetailsView.episodeTitle(chapter))
                                Spacer()
                                if chapter.id == model.chapter.id { Image(systemName: "checkmark") }
                            }
                        }
                        .disabled(model.isLoading)
                        .accessibilityIdentifier("player.episode.\(chapter.id)")
                        .accessibilityValue(chapter.id == model.chapter.id ? "正在播放" : "")
                    }
                }
            }
            .navigationTitle("选集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }
}

extension VideoPlayerView {
    static func streamName(_ stream: IosSourcePage, _ index: Int) -> String {
        stream.label?.trimmingCharacters(in: .whitespaces).nilIfEmpty ?? String(localized: "线路 \(index + 1)")
    }

    static func subtitleName(_ track: SubtitleTrack, _ index: Int, failed: Bool) -> String {
        let name = SubtitleLanguage.title(track.lang) ?? String(localized: "外挂字幕 \(index + 1)")
        return failed ? String(localized: "\(name)（加载失败）") : name
    }
}

private enum SubtitleChoice: Hashable {
    case off
    case embedded(AVMediaSelectionOption)
    case external(Int)
}

/// External subtitle text over the video, above the controls' bottom bar while they are shown.
private struct SubtitleOverlay: View {
    let text: String
    let raised: Bool

    var body: some View {
        Text(text)
            .font(.system(.title3, weight: .semibold))
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .shadow(color: .black.opacity(0.9), radius: 2)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(.black.opacity(0.4), in: RoundedRectangle(cornerRadius: 6))
            .padding(.horizontal, 24)
            .padding(.bottom, raised ? 96 : 20)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .allowsHitTesting(false)
            .animation(.easeInOut(duration: 0.2), value: raised)
            .accessibilityIdentifier("player.subtitle")
    }
}

/// Source subtitle labels are codes ("en", "pt-BR") or names ("English", "Español (España)").
enum SubtitleLanguage {
    static func title(_ lang: String) -> String? {
        let value = lang.trimmingCharacters(in: .whitespaces)
        guard !value.isEmpty else { return nil }
        if value.count <= 8, let code = SourceLanguage.code(of: value),
           let name = Locale.current.localizedString(forLanguageCode: code), name.lowercased() != code {
            return value.contains("-") || value.contains("_") ? SourceLanguage.title(value) : name
        }
        return value
    }

    /// The track to turn on: the viewer's last choice, then one of their languages, then the first one.
    static func preferredIndex(_ labels: [String], last: String?) -> Int? {
        guard !labels.isEmpty else { return nil }
        if let last, let index = labels.firstIndex(of: last) { return index }
        return labels.firstIndex { label in
            let lowered = label.lowercased()
            return SourceLanguage.preferred.contains { code in
                if SourceLanguage.code(of: lowered) == code && lowered.count <= 8 { return true }
                // Names in English, the language itself, or the device language.
                return [Locale(identifier: "en"), Locale(identifier: code), Locale.current].contains { locale in
                    locale.localizedString(forLanguageCode: code).map { lowered.hasPrefix($0.lowercased()) } ?? false
                }
            }
        } ?? 0
    }
}

/// A side-loaded subtitle of the current stream, copied out of the shared page model.
struct SubtitleTrack: Hashable {
    let url: String
    let lang: String
    let headers: [String: String]
}

extension SubtitleTrack {
    init(_ track: IosExternalTrack) { self.init(url: track.url, lang: track.lang, headers: track.headers) }
}

/// Downloads a subtitle file with the stream's headers (sites check Referer on every request) plus the track's own.
enum SubtitleFile {
    static func download(_ track: SubtitleTrack, streamHeaders: [String: String]) async throws -> String {
        guard let url = URL(string: track.url) else { throw URLError(.badURL) }
        // A downloaded episode keeps its subtitles beside it: read the file instead of asking the network.
        if url.isFileURL {
            return decode(try await Task.detached(priority: .userInitiated) { try Data(contentsOf: url) }.value)
        }
        var request = URLRequest(url: url, timeoutInterval: 30)
        for (name, value) in streamHeaders.merging(track.headers, uniquingKeysWith: { _, own in own }) {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        return decode(data)
    }

    /// UTF-8 (with or without BOM), UTF-16 with BOM, then GB 18030, common for Chinese SubRip files.
    static func decode(_ data: Data) -> String {
        if data.starts(with: [0xFF, 0xFE]) || data.starts(with: [0xFE, 0xFF]),
           let text = String(data: data, encoding: .utf16) { return text }
        if let text = String(data: data, encoding: .utf8) { return text }
        let gb18030 = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        return String(data: data, encoding: gb18030) ?? String(decoding: data, as: UTF8.self)
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// System Picture in Picture for the player layer; observable so the button appears only when PiP is possible.
@Observable
final class PictureInPicture: NSObject, AVPictureInPictureControllerDelegate {
    private(set) var isPossible = false
    private var controller: AVPictureInPictureController?
    private var observation: NSKeyValueObservation?

    func attach(_ layer: AVPlayerLayer) {
        guard controller?.playerLayer !== layer, AVPictureInPictureController.isPictureInPictureSupported() else { return }
        let controller = AVPictureInPictureController(playerLayer: layer)
        controller?.canStartPictureInPictureAutomaticallyFromInline = true
        observation = controller?.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] pip, _ in
            let possible = pip.isPictureInPicturePossible
            DispatchQueue.main.async { self?.isPossible = possible }
        }
        self.controller = controller
    }

    func start() { controller?.startPictureInPicture() }
}

/// AVPlayerLayer host: aspect fit, or fill when toggled.
private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    let fill: Bool
    let pip: PictureInPicture

    final class LayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.backgroundColor = .black
        view.playerLayer.player = player
        pip.attach(view.playerLayer)
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {
        if view.playerLayer.player !== player { view.playerLayer.player = player }
        view.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
        pip.attach(view.playerLayer)
    }
}

/// The system AirPlay route button.
private struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = .white
        view.activeTintColor = .systemBlue
        view.prioritizesVideoDevices = true
        return view
    }

    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}
