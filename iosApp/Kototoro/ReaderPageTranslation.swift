import NaturalLanguage
import SwiftUI
import Translation
import Vision

/// A run of recognized text on a page: one speech bubble or caption, in normalized top-left coordinates.
struct PageTextBlock: Equatable {
    var rect: CGRect
    var text: String
    /// Recognized as columns (vertical Japanese/Chinese), whose reading order runs right to left.
    var vertical: Bool
}

/// The recognition options of page translation, shared by every work. Each value is read when translation starts or
/// the options change, so the reader re-recognizes its pages with the new ones.
struct PageTranslationOptions: Equatable {
    /// A `Locale.Language` identifier for Vision; empty detects the language on each page.
    static let languageKey = "manga.translation.language"
    static let correctionKey = "manga.translation.correction"
    /// Lines recognized with less confidence (0...1) are left as they are.
    static let confidenceKey = "manga.translation.confidence"
    /// How far apart two lines may sit and still form one bubble, in line thicknesses.
    static let spacingKey = "manga.translation.spacing"
    static let spacingRange = 0.3...1.8
    static let defaultSpacing = 0.9
    static let confidenceRange = 0.0...0.9

    var language: Locale.Language?
    var correction = true
    var confidence: Float = 0
    var spacing: CGFloat = CGFloat(Self.defaultSpacing)

    static var current: PageTranslationOptions {
        let defaults = UserDefaults.standard
        let language = defaults.string(forKey: languageKey) ?? ""
        return PageTranslationOptions(
            language: language.isEmpty ? nil : Locale.Language(identifier: language),
            correction: defaults.object(forKey: correctionKey) as? Bool ?? true,
            confidence: Float(min(max(defaults.double(forKey: confidenceKey), confidenceRange.lowerBound),
                                  confidenceRange.upperBound)),
            spacing: CGFloat(min(max(defaults.object(forKey: spacingKey) as? Double ?? defaultSpacing,
                                     spacingRange.lowerBound), spacingRange.upperBound)))
    }

    /// The languages Vision recognizes at the accuracy translation uses.
    static var supportedLanguages: [Locale.Language] {
        var request = RecognizeTextRequest()
        request.recognitionLevel = .accurate
        return request.supportedRecognitionLanguages
    }
}

/// Android's manga OCR + translation done with the system: Vision finds the text, the Translation framework
/// translates each bubble, and the translation is painted over the original at the same place. The rendered page
/// keeps the original's size, so the reader's layout does not change when it is swapped in.
enum PageTextRecognizer {
    /// Lines found on [image], merged into bubbles.
    static func blocks(in image: UIImage, options: PageTranslationOptions) async throws -> [PageTextBlock] {
        guard let cgImage = image.cgImage else { return [] }
        var request = RecognizeTextRequest()
        request.recognitionLevel = .accurate
        if let language = options.language {
            request.automaticallyDetectsLanguage = false
            request.recognitionLanguages = [language]
        } else {
            request.automaticallyDetectsLanguage = true
        }
        request.usesLanguageCorrection = options.correction
        let observations = try await request.perform(on: cgImage, orientation: .up)
        let lines = observations.compactMap { observation -> PageTextBlock? in
            guard let candidate = observation.topCandidates(1).first, candidate.confidence >= options.confidence,
                  candidate.string.contains(where: \.isLetter) else { return nil }
            let text = candidate.string
            let box = observation.boundingBox.cgRect
            let rect = CGRect(x: box.minX, y: 1 - box.maxY, width: box.width, height: box.height)
            // Vision reports a vertical column as a tall, narrow line.
            let aspect = rect.height * CGFloat(cgImage.height) / max(1, rect.width * CGFloat(cgImage.width))
            return PageTextBlock(rect: rect, text: text, vertical: aspect > 2.2 && text.count > 1)
        }
        return merge(lines, aspect: CGFloat(cgImage.width) / max(1, CGFloat(cgImage.height)), spacing: options.spacing)
    }

    /// Lines close to each other (within [spacing] lines, about one by default) belong to one bubble. [aspect] is the
    /// page's width over its height, so distances compare in the same units horizontally and vertically.
    static func merge(_ lines: [PageTextBlock], aspect: CGFloat,
                      spacing: CGFloat = CGFloat(PageTranslationOptions.defaultSpacing)) -> [PageTextBlock] {
        /// Whether two lines of one direction sit within about one line of each other. The thicker line sets the
        /// distance: a line without ascenders ("at noon.") gets a much shorter box than the line above it.
        func near(_ a: PageTextBlock, _ b: PageTextBlock) -> Bool {
            guard a.vertical == b.vertical else { return false }
            let thickness = a.vertical ? max(a.rect.width, b.rect.width) : max(a.rect.height, b.rect.height)
            let pad = thickness * spacing
            let padX = a.vertical ? pad : pad / max(aspect, 0.01)
            let padY = a.vertical ? pad * max(aspect, 0.01) : pad
            return a.rect.insetBy(dx: -padX / 2, dy: -padY / 2).intersects(b.rect.insetBy(dx: -padX / 2, dy: -padY / 2))
        }
        var groups: [[PageTextBlock]] = []
        for line in lines {
            var joined: [Int] = []
            for (index, group) in groups.enumerated() where group.contains(where: { near($0, line) }) {
                joined.append(index)
            }
            var group = [line]
            for index in joined.reversed() { group += groups.remove(at: index) }
            groups.append(group)
        }
        return groups.map { group in
            let vertical = group[0].vertical
            // Columns read right to left, rows top to bottom.
            let ordered = vertical ? group.sorted { $0.rect.midX > $1.rect.midX }
                                   : group.sorted { $0.rect.minY < $1.rect.minY }
            let rect = ordered.dropFirst().reduce(ordered[0].rect) { $0.union($1.rect) }
            let joiner = ordered.contains { $0.text.unicodeScalars.contains { $0.properties.isIdeographic } } ? "" : " "
            return PageTextBlock(rect: rect, text: ordered.map(\.text).joined(separator: joiner), vertical: vertical)
        }
        .sorted { ($0.rect.minY, -$0.rect.minX) < ($1.rect.minY, -$1.rect.minX) }
    }
}

enum PageTranslationRenderer {
    /// [image] with each block covered in its surrounding color and its translation fitted inside.
    static func render(_ image: UIImage, blocks: [PageTextBlock], translations: [String]) -> UIImage {
        let size = image.size
        let sampler = ColorSampler(image)
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        format.opaque = false
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(at: .zero)
            for (block, translation) in zip(blocks, translations) where !translation.isEmpty {
                var rect = CGRect(x: block.rect.minX * size.width, y: block.rect.minY * size.height,
                                  width: block.rect.width * size.width, height: block.rect.height * size.height)
                rect = rect.insetBy(dx: -max(3, rect.width * 0.05), dy: -max(3, rect.height * 0.05))
                let background = sampler.color(around: rect, in: size)
                // Translated text runs across; a column bubble gets the width its text needs, within the page.
                if block.vertical, rect.width < rect.height * 0.75 {
                    let width = min(size.width, rect.height * 0.75)
                    rect = CGRect(x: rect.midX - width / 2, y: rect.minY, width: width, height: rect.height)
                    rect.origin.x = min(max(0, rect.minX), size.width - rect.width)
                }
                background.setFill()
                UIBezierPath(roundedRect: rect, cornerRadius: min(rect.width, rect.height) * 0.2).fill()
                draw(translation, in: rect.insetBy(dx: rect.width * 0.04, dy: rect.height * 0.04),
                     color: background.isLight ? .black : .white)
            }
        }
    }

    /// The largest font (up to the original text height) at which [text] fits [rect], centred.
    private static func draw(_ text: String, in rect: CGRect, color: UIColor) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        paragraph.lineBreakMode = .byWordWrapping
        func attributes(_ size: CGFloat) -> [NSAttributedString.Key: Any] {
            [.font: UIFont.systemFont(ofSize: size, weight: .semibold), .foregroundColor: color, .paragraphStyle: paragraph]
        }
        func height(_ size: CGFloat) -> CGFloat {
            (text as NSString).boundingRect(with: CGSize(width: rect.width, height: .greatestFiniteMagnitude),
                                            options: [.usesLineFragmentOrigin], attributes: attributes(size), context: nil).height
        }
        var low: CGFloat = 4, high = max(4, min(rect.height, 72))
        for _ in 0..<12 {
            let middle = (low + high) / 2
            if height(middle) <= rect.height { low = middle } else { high = middle }
        }
        let used = height(low)
        let box = CGRect(x: rect.minX, y: rect.midY - used / 2, width: rect.width, height: used)
        (text as NSString).draw(with: box, options: [.usesLineFragmentOrigin], attributes: attributes(low), context: nil)
    }

    /// Reads the page's colors from a small copy, so a bubble is covered in the paper around its text.
    private struct ColorSampler {
        private let pixels: [UInt8]
        private let width: Int
        private let height: Int

        init(_ image: UIImage) {
            let side = 256.0
            let scale = min(1, side / max(image.size.width, image.size.height))
            width = max(1, Int(image.size.width * scale))
            height = max(1, Int(image.size.height * scale))
            var buffer = [UInt8](repeating: 255, count: width * height * 4)
            if let cgImage = image.cgImage,
               let context = CGContext(data: &buffer, width: width, height: height, bitsPerComponent: 8,
                                       bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                       bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
                context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
            }
            pixels = buffer
        }

        /// The brightest common color on the rectangle's edge: bubble paper rather than text or outline.
        func color(around rect: CGRect, in size: CGSize) -> UIColor {
            var samples: [(CGFloat, CGFloat, CGFloat)] = []
            for step in 0...8 {
                let t = CGFloat(step) / 8
                for point in [CGPoint(x: rect.minX + rect.width * t, y: rect.minY),
                              CGPoint(x: rect.minX + rect.width * t, y: rect.maxY),
                              CGPoint(x: rect.minX, y: rect.minY + rect.height * t),
                              CGPoint(x: rect.maxX, y: rect.minY + rect.height * t)] {
                    let x = min(width - 1, max(0, Int(point.x / max(1, size.width) * CGFloat(width))))
                    // A bitmap context's memory rows run top-down, like the page coordinates.
                    let y = min(height - 1, max(0, Int(point.y / max(1, size.height) * CGFloat(height))))
                    let offset = (y * width + x) * 4
                    samples.append((CGFloat(pixels[offset]) / 255, CGFloat(pixels[offset + 1]) / 255,
                                    CGFloat(pixels[offset + 2]) / 255))
                }
            }
            // The lighter half's average: robust to the outline and text strokes the edge crosses.
            let sorted = samples.sorted { ($0.0 + $0.1 + $0.2) > ($1.0 + $1.1 + $1.2) }
            let kept = sorted.prefix(max(1, sorted.count / 2))
            let count = CGFloat(kept.count)
            return UIColor(red: kept.map(\.0).reduce(0, +) / count, green: kept.map(\.1).reduce(0, +) / count,
                           blue: kept.map(\.2).reduce(0, +) / count, alpha: 1)
        }
    }
}

private extension UIColor {
    var isLight: Bool {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return 0.299 * red + 0.587 * green + 0.114 * blue > 0.55
    }
}

/// The reader's page translation: pages are recognized as they load, translated in order by the view's
/// translation session, and their rendered copies handed to the reader by page identity.
@MainActor
final class ReaderPageTranslator: ObservableObject {
    @Published private(set) var enabled = false
    @Published var configuration: TranslationSession.Configuration?
    @Published private(set) var status = NovelTranslationStatus.idle
    private(set) var rendered: [String: UIImage] = [:]
    /// Each translated page's text, read by VoiceOver in place of the original lettering.
    private(set) var texts: [String: [String]] = [:]
    /// Called when a page's translated copy is ready.
    var onRendered: (() -> Void)?

    private var sources: [String: UIImage] = [:]
    private var order: [String] = []
    private var recognized: [String: [PageTextBlock]] = [:]
    private var finished: Set<String> = []
    private var recognizing = false
    private var wake: AsyncStream<Void>.Continuation?
    private var target = NovelTranslationSettings.target(
        UserDefaults.standard.string(forKey: NovelTranslationSettings.targetKey) ?? "")
    private var options = PageTranslationOptions.current
    /// Bumped when the options change, so recognition and translation started before are dropped.
    private var generation = 0
    private static let fixture = ProcessInfo.processInfo.environment["KOTOTORO_TRANSLATION_FIXTURE"] == "1"

    func toggle() {
        enabled.toggle()
        if !enabled {
            configuration = nil
            status = .idle
        } else {
            target = NovelTranslationSettings.target(
                UserDefaults.standard.string(forKey: NovelTranslationSettings.targetKey) ?? "")
            options = .current
        }
    }

    /// Re-reads the target language and recognition options; when they changed, the loaded pages are recognized and
    /// translated again with them (the originals show meanwhile).
    func reloadOptions() {
        let target = NovelTranslationSettings.target(
            UserDefaults.standard.string(forKey: NovelTranslationSettings.targetKey) ?? "")
        let options = PageTranslationOptions.current
        guard target != self.target || options != self.options else { return }
        self.target = target
        self.options = options
        guard enabled else { return }
        generation += 1
        recognized = [:]
        finished = []
        rendered = [:]
        texts = [:]
        recognizing = false
        configuration = nil
        status = .idle
        onRendered?()
        updateStatus()
        recognizeNext()
    }

    /// Asks for page [key] (its loaded [image]) to be translated; repeated requests are ignored.
    func enqueue(key: String, image: UIImage) {
        guard enabled, sources[key] == nil, rendered[key] == nil else { return }
        sources[key] = image
        order.append(key)
        // Called while the reader builds its page state; publish after that update.
        Task { @MainActor in self.updateStatus(); self.recognizeNext() }
    }

    private func updateStatus() {
        let total = order.count
        let done = finished.count
        if case .failed = status { return }
        status = done < total ? .translating(done: done, total: total) : .idle
    }

    /// Recognizes queued pages one at a time; recognition needs no session.
    private func recognizeNext() {
        guard enabled, !recognizing,
              let key = order.first(where: { recognized[$0] == nil && !finished.contains($0) }),
              let image = sources[key] else { return }
        recognizing = true
        let generation = generation
        let options = options
        Task {
            let blocks = (try? await PageTextRecognizer.blocks(in: image, options: options)) ?? []
            guard generation == self.generation else { return }
            recognized[key] = blocks
            recognizing = false
            if blocks.isEmpty {
                finish(key, image: nil)
            } else if Self.fixture {
                let texts = blocks.map { "[\(target.minimalIdentifier)] \($0.text)" }
                finish(key, image: PageTranslationRenderer.render(image, blocks: blocks, translations: texts), texts: texts)
            } else {
                startSession(sample: blocks.map(\.text))
                wake?.yield()
            }
            recognizeNext()
        }
    }

    /// The first page with text tells the source language the session is prepared for.
    private func startSession(sample: [String]) {
        guard configuration == nil else { return }
        let source = options.language ?? NovelTranslationSettings.source(of: sample)
        if let source, NovelTranslationSettings.matches(source, target) {
            status = .sameLanguage
            return
        }
        configuration = TranslationSession.Configuration(source: source, target: target)
    }

    private func finish(_ key: String, image: UIImage?, texts: [String] = []) {
        finished.insert(key)
        if let image { rendered[key] = image; self.texts[key] = texts; onRendered?() }
        updateStatus()
    }

    /// Translates recognized pages until the session ends (translation turned off or the reader closed).
    func run(_ session: TranslationSession) async {
        let (stream, continuation) = AsyncStream<Void>.makeStream(bufferingPolicy: .bufferingNewest(1))
        wake = continuation
        defer { continuation.finish() }
        continuation.yield()
        do {
            try await session.prepareTranslation()
            for await _ in stream {
                while enabled, let key = order.first(where: { recognized[$0]?.isEmpty == false && !finished.contains($0) }),
                      let image = sources[key], let blocks = recognized[key] {
                    let generation = generation
                    let requests = blocks.enumerated().map {
                        TranslationSession.Request(sourceText: $0.element.text, clientIdentifier: String($0.offset))
                    }
                    let responses = try await session.translations(from: requests)
                    var texts = Array(repeating: "", count: blocks.count)
                    for response in responses {
                        if let index = response.clientIdentifier.flatMap(Int.init), texts.indices.contains(index) {
                            texts[index] = response.targetText
                        }
                    }
                    let page = await Task.detached(priority: .userInitiated) {
                        PageTranslationRenderer.render(image, blocks: blocks, translations: texts)
                    }.value
                    guard generation == self.generation else { break }
                    finish(key, image: page, texts: texts)
                }
            }
        } catch is CancellationError {
        } catch {
            status = .failed(String(localized: "翻译失败：\(error.localizedDescription)"))
        }
    }
}

/// Vision's recognition language for manga pages, or "" to detect it on each page. Tagged by the full identifier:
/// zh-Hans and zh-Hant stay distinct, and a stored "en-US" matches the English entry.
struct PageRecognitionLanguagePicker: View {
    @AppStorage(PageTranslationOptions.languageKey) private var language = ""
    @State private var languages: [Locale.Language] = []

    var body: some View {
        Picker("原文语言", selection: Binding(
            get: { language.isEmpty ? "" : Locale.Language(identifier: language).maximalIdentifier },
            set: { language = $0 })) {
            Text("自动识别").tag("")
            ForEach(languages, id: \.maximalIdentifier) { language in
                Text(NovelTranslationSettings.title(language)).tag(language.maximalIdentifier)
            }
        }
        .task {
            languages = PageTranslationOptions.supportedLanguages
                .sorted { NovelTranslationSettings.title($0) < NovelTranslationSettings.title($1) }
        }
    }
}

/// A slider that shows its value while dragged and stores it only when released, so translated pages are redone
/// once per change.
struct CommittingSlider: View {
    let title: LocalizedStringKey
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double
    let text: (Double) -> String
    @State private var draft: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            LabeledContent(title) { Text(text(draft ?? value)).monospacedDigit() }
            Slider(value: Binding(get: { draft ?? value }, set: { draft = $0 }), in: range, step: step) { editing in
                if !editing, let committed = draft {
                    draft = nil
                    value = committed
                }
            }
            .accessibilityValue(text(draft ?? value))
        }
    }
}

/// The stored recognition options of manga page translation, with the controls both settings surfaces show.
struct PageTranslationOptionControls: DynamicProperty {
    @AppStorage(PageTranslationOptions.languageKey) var language = ""
    @AppStorage(PageTranslationOptions.correctionKey) var correction = true
    @AppStorage(PageTranslationOptions.confidenceKey) var confidence = 0.0
    @AppStorage(PageTranslationOptions.spacingKey) var spacing = PageTranslationOptions.defaultSpacing

    static let languageNote: LocalizedStringKey = "指定原文语言后识别更准，也作为翻译的源语言；日文漫画选择日语可避免被识别成中文。"
    static let correctionNote: LocalizedStringKey = "按词典修正识别结果；拟声词或艺术字被改错时可以关闭。"
    static let confidenceNote: LocalizedStringKey = "低于该置信度的文字不翻译，用于去掉背景纹理、水印等误识别。"
    static let spacingNote: LocalizedStringKey = "相距在该行距以内的文字合并为一个气泡翻译。相邻气泡被合在一起时调小，同一气泡被拆开时调大。"

    /// A value that changes when any option does.
    var signature: String { "\(language)|\(correction)|\(confidence)|\(spacing)" }

    var correctionToggle: some View {
        Toggle("语言纠错", isOn: $correction).accessibilityIdentifier("reader.translation.correction")
    }

    var confidenceSlider: some View {
        CommittingSlider(title: "最低置信度", value: $confidence, range: PageTranslationOptions.confidenceRange, step: 0.05) {
            $0 == 0 ? String(localized: "不过滤") : $0.formatted(.percent.precision(.fractionLength(0)))
        }
        .accessibilityIdentifier("reader.translation.confidence")
    }

    var spacingSlider: some View {
        CommittingSlider(title: "气泡合并距离", value: $spacing, range: PageTranslationOptions.spacingRange, step: 0.1) {
            String(localized: "\($0.formatted(.number.precision(.fractionLength(1)))) 行")
        }
        .accessibilityIdentifier("reader.translation.spacing")
    }

    var resetButton: some View {
        Button("恢复默认") {
            language = ""
            correction = true
            confidence = 0
            spacing = PageTranslationOptions.defaultSpacing
        }
        .accessibilityIdentifier("reader.translation.reset")
    }

    /// Resets only the sliders (the language and correction sit on the page above).
    var tuningResetButton: some View {
        Button("恢复默认") {
            confidence = 0
            spacing = PageTranslationOptions.defaultSpacing
        }
        .accessibilityIdentifier("reader.translation.reset")
    }
}

/// The translation page of the reader's options panel: the target language (shared with novels) and how Vision reads
/// the page. [onChange] runs after a value is committed.
struct PageTranslationSettingsView: View {
    var onChange: () -> Void
    @AppStorage(NovelTranslationSettings.targetKey) private var target = ""
    private let options = PageTranslationOptionControls()

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            LabeledContent("目标语言") {
                TranslationTargetPicker().labelsHidden().accessibilityIdentifier("reader.translation.target")
            }
            LabeledContent("原文语言") {
                PageRecognitionLanguagePicker().labelsHidden().accessibilityIdentifier("reader.translation.language")
            }
            note(PageTranslationOptionControls.languageNote)
            options.correctionToggle
            note(PageTranslationOptionControls.correctionNote)
            options.confidenceSlider
            note(PageTranslationOptionControls.confidenceNote)
            options.spacingSlider
            note(PageTranslationOptionControls.spacingNote)
            options.resetButton.buttonStyle(.glass)
        }
        .onChange(of: "\(target)|\(options.signature)") { onChange() }
    }

    private func note(_ text: LocalizedStringKey) -> some View {
        Text(text).font(.caption).foregroundStyle(.secondary)
    }
}

/// Settings → Translation: the shared target language, each kind's original language, then the cache. The page
/// recognition sliders sit one level down, where only someone fixing a page goes.
struct TranslationSettingsView: View {
    @AppStorage(NovelTranslationSettings.bilingualKey) private var bilingual = false
    @AppStorage(NovelTranslationSettings.sourceKey) private var novelSource = ""
    private let options = PageTranslationOptionControls()
    @State private var cleared = false

    var body: some View {
        Form {
            Section {
                TranslationTargetPicker().accessibilityIdentifier("settings.translation.target")
            } footer: {
                Text("小说与漫画共用目标语言。")
            }
            Section("小说") {
                TranslationLanguagePicker(title: "原文语言", selection: $novelSource,
                                          automatic: String(localized: "自动识别"))
                    .accessibilityIdentifier("novel.settings.translationSource")
                Picker("显示方式", selection: $bilingual) {
                    Text("仅译文").tag(false)
                    Text("双语对照").tag(true)
                }
                .accessibilityIdentifier("novel.settings.translationMode")
            }
            Section {
                PageRecognitionLanguagePicker().accessibilityIdentifier("reader.translation.language")
                options.correctionToggle
                NavigationLink { PageRecognitionTuningPage() } label: { Text("识别调整") }
                    .accessibilityIdentifier("settings.translation.tuning")
            } header: {
                Text("漫画")
            } footer: {
                Text("原文语言识别不准时手动指定；拟声词被改错时可关闭纠错。")
            }
            Section {
                Button(cleared ? "已清除翻译缓存" : "清除翻译缓存", role: .destructive) {
                    NovelTranslationCache.clear()
                    cleared = true
                }
                .disabled(cleared)
            } footer: {
                Text("使用 iOS 系统翻译，语言包下载后可离线使用。小说译文按章节缓存在本机。")
            }
        }
        .navigationTitle("翻译")
    }
}

/// How Vision's lines become bubbles and which of them are kept.
struct PageRecognitionTuningPage: View {
    private let options = PageTranslationOptionControls()

    var body: some View {
        Form {
            Section {
                options.confidenceSlider
            } footer: {
                Text(PageTranslationOptionControls.confidenceNote)
            }
            Section {
                options.spacingSlider
            } footer: {
                Text(PageTranslationOptionControls.spacingNote)
            }
            Section {
                options.tuningResetButton
            }
        }
        .navigationTitle("识别调整")
    }
}

/// Holds the reader's translation session and shows its progress over the page.
struct ReaderTranslationHost: View {
    @ObservedObject var translator: ReaderPageTranslator

    var body: some View {
        Group {
            if translator.enabled, let text = translator.status.text {
                Label(text, systemImage: "translate")
                    .font(.footnote.weight(.medium))
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .glassEffect(.regular, in: .capsule)
                    .padding(.top, 64)
                    .accessibilityIdentifier("reader.translationStatus")
            } else {
                Color.clear.frame(width: 1, height: 1)
            }
        }
        .allowsHitTesting(false)
        .translationTask(translator.configuration) { session in await translator.run(session) }
    }
}
