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

/// Android's manga OCR + translation done with the system: Vision finds the text, the Translation framework
/// translates each bubble, and the translation is painted over the original at the same place. The rendered page
/// keeps the original's size, so the reader's layout does not change when it is swapped in.
enum PageTextRecognizer {
    /// Lines found on [image], merged into bubbles.
    static func blocks(in image: UIImage) async throws -> [PageTextBlock] {
        guard let cgImage = image.cgImage else { return [] }
        var request = RecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.automaticallyDetectsLanguage = true
        request.usesLanguageCorrection = true
        let observations = try await request.perform(on: cgImage, orientation: .up)
        let lines = observations.compactMap { observation -> PageTextBlock? in
            guard let text = observation.topCandidates(1).first?.string,
                  text.contains(where: \.isLetter) else { return nil }
            let box = observation.boundingBox.cgRect
            let rect = CGRect(x: box.minX, y: 1 - box.maxY, width: box.width, height: box.height)
            // Vision reports a vertical column as a tall, narrow line.
            let aspect = rect.height * CGFloat(cgImage.height) / max(1, rect.width * CGFloat(cgImage.width))
            return PageTextBlock(rect: rect, text: text, vertical: aspect > 2.2 && text.count > 1)
        }
        return merge(lines, aspect: CGFloat(cgImage.width) / max(1, CGFloat(cgImage.height)))
    }

    /// Lines close to each other (within about half a line) belong to one bubble. [aspect] is the page's width over
    /// its height, so distances compare in the same units horizontally and vertically.
    static func merge(_ lines: [PageTextBlock], aspect: CGFloat) -> [PageTextBlock] {
        /// Whether two lines of one direction sit within about one line of each other. The thicker line sets the
        /// distance: a line without ascenders ("at noon.") gets a much shorter box than the line above it.
        func near(_ a: PageTextBlock, _ b: PageTextBlock) -> Bool {
            guard a.vertical == b.vertical else { return false }
            let thickness = a.vertical ? max(a.rect.width, b.rect.width) : max(a.rect.height, b.rect.height)
            let pad = thickness * 0.9
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
    private static let fixture = ProcessInfo.processInfo.environment["KOTOTORO_TRANSLATION_FIXTURE"] == "1"

    func toggle() {
        enabled.toggle()
        if !enabled {
            configuration = nil
            status = .idle
        } else {
            target = NovelTranslationSettings.target(
                UserDefaults.standard.string(forKey: NovelTranslationSettings.targetKey) ?? "")
        }
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
        Task {
            let blocks = (try? await PageTextRecognizer.blocks(in: image)) ?? []
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
        let source = NovelTranslationSettings.source(of: sample)
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
                    finish(key, image: page, texts: texts)
                }
            }
        } catch is CancellationError {
        } catch {
            status = .failed(String(localized: "翻译失败：\(error.localizedDescription)"))
        }
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
