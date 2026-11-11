import KototoroShared
import SwiftUI
import UIKit

/// Android's details "panorama cover" presets: blur, fade range and top opacity (percent), per layout.
enum PanoramaPreset: String, CaseIterable, Identifiable {
    case clear, balanced, soft
    var id: String { rawValue }

    var title: String {
        switch self {
        case .clear: String(localized: "清晰")
        case .balanced: String(localized: "均衡")
        case .soft: String(localized: "柔和")
        }
    }

    func values(_ layout: PanoramaLayout) -> (blur: Double, range: Double, opacity: Double) {
        switch (self, layout) {
        case (.clear, .fullScreen): (10, 55, 90)
        case (.clear, .top): (10, 40, 90)
        case (.balanced, .fullScreen): (35, 100, 90)
        case (.balanced, .top): (30, 75, 90)
        case (.soft, .fullScreen): (60, 100, 90)
        case (.soft, .top): (55, 100, 90)
        }
    }
}

/// Top banner behind the header (Android's half-screen mode) or the whole page faded into the background.
enum PanoramaLayout: String, CaseIterable, Identifiable {
    case top, fullScreen
    var id: String { rawValue }
    var title: String { self == .top ? String(localized: "顶部横幅") : String(localized: "全屏背景") }
}

enum PanoramaKeys {
    static let enabled = "details.panorama"
    static let preset = "details.panorama.preset"
    static let layout = "details.panorama.layout"
    static let animated = "details.panorama.animated"
    static let scrollLinked = "details.panorama.scrollLinked"
}

struct DetailsCoverBoundsKey: PreferenceKey {
    static let defaultValue: Anchor<CGRect>? = nil

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = nextValue() ?? value
    }
}

/// Per-frame scroll state belongs to the decoration, keeping chapter and metadata views out of its update path.
struct DetailsBackdropModifier: ViewModifier {
    let runtime: IosSources
    let work: IosSourceContent
    @AppStorage(PanoramaKeys.enabled) private var panorama = true
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var scrollOffset: CGFloat = 0
    @State private var dominantColor: UIColor?

    private var isVideoHero: Bool { work.mediaKind == "VIDEO" }

    func body(content: Content) -> some View {
        let titleThreshold: CGFloat = isVideoHero ? 240 : 170
        let baseBackground = CoverDominantColor.pageBackground(
            for: dominantColor,
            colorScheme: colorScheme,
            increasedContrast: contrast == .increased
        )
        content
            .onScrollGeometryChange(for: CGFloat.self, of: { $0.contentOffset.y + $0.contentInsets.top }) { _, offset in
                scrollOffset = offset
            }
            .backgroundPreferenceValue(DetailsCoverBoundsKey.self) { bounds in
                GeometryReader { geometry in
                    backdrop(
                        coverBottom: bounds.map { geometry[$0].maxY + scrollOffset },
                        baseBackground: baseBackground
                    )
                }
                .ignoresSafeArea()
            }
            .navigationTitle(panorama && scrollOffset < titleThreshold ? "" : work.title)
            .task(id: CoverCache.identity(work)) {
                if let cached = CoverDominantColor.cached(for: work) {
                    dominantColor = cached
                    return
                }
                if let extracted = await CoverDominantColor.extract(runtime: runtime, content: work) {
                    guard !Task.isCancelled else { return }
                    withAnimation(.easeInOut(duration: 0.35)) {
                        dominantColor = extracted
                    }
                } else if !Task.isCancelled {
                    dominantColor = nil
                }
            }
    }

    private func backdrop(coverBottom: CGFloat?, baseBackground: Color) -> some View {
        ZStack(alignment: .top) {
            baseBackground
            if panorama && isVideoHero {
                VideoHeroBackdrop(
                    runtime: runtime,
                    content: work,
                    scrollOffset: scrollOffset,
                    coverBottom: coverBottom
                )
            } else if panorama {
                DetailsPanorama(runtime: runtime, content: work, scrollOffset: scrollOffset, coverBottom: coverBottom)
            } else {
                CoverImage(runtime: runtime, content: work, plain: true)
                    .frame(height: 420).frame(maxWidth: .infinity)
                    .blur(radius: 50).opacity(0.5)
                    .mask(LinearGradient(colors: [.black, .black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom))
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Crisp top poster that softly diffuses and dissolves into the page background using the panorama fade curve.
private struct VideoHeroBackdrop: View {
    let runtime: IosSources
    let content: IosSourceContent
    let scrollOffset: CGFloat
    let coverBottom: CGFloat?
    @State private var lastHeroBottom: CGFloat?

    var body: some View {
        GeometryReader { geometry in
            let bottom = coverBottom ?? lastHeroBottom ?? 440
            let base = max(bottom + 24, 420) / 0.96
            let stretch = max(-scrollOffset, 0)
            let height = base + stretch
            let scrollAlpha = max(0.0, min(1.0, 1.0 - (max(scrollOffset, 0) / 220.0)))
            let blurRadius: CGFloat = 14
            let blurPadding = ceil(blurRadius * 3) + 1

            ZStack(alignment: .top) {
                // Soft diffused layer extending down into the panorama fade behind the title and action controls.
                CoverImage(runtime: runtime, content: content, plain: true)
                    .frame(width: geometry.size.width + 2 * blurPadding, height: height + 2 * blurPadding)
                    .blur(radius: blurRadius, opaque: false)
                    .frame(width: geometry.size.width, height: height)
                    .clipped()
                    .mask(DetailsPanorama.fade(range: 0.85))

                // Crisp top artwork fading out earlier so the transition from sharp image to diffused wash is seamless.
                CoverImage(runtime: runtime, content: content, plain: true)
                    .frame(width: geometry.size.width, height: height)
                    .clipped()
                    .mask(
                        LinearGradient(
                            stops: [
                                .init(color: .black, location: 0),
                                .init(color: .black, location: 0.24),
                                .init(color: .black.opacity(0.65), location: 0.40),
                                .init(color: .clear, location: 0.58),
                            ],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
            }
            .overlay(alignment: .top) {
                LinearGradient(
                    colors: [.black.opacity(0.20), .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: 100)
            }
            .opacity(scrollAlpha)
            .offset(y: -max(scrollOffset, 0))
        }
        .onChange(of: coverBottom, initial: true) { _, bottom in
            if let bottom, bottom > 0 { lastHeroBottom = bottom }
        }
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

/// Extracts and caches a cover's dominant hue so details pages tint their background without hurting contrast.
private enum CoverDominantColor {
    private static let cache: NSCache<NSString, UIColor> = {
        let cache = NSCache<NSString, UIColor>()
        cache.countLimit = 200
        return cache
    }()

    static func cached(for content: IosSourceContent) -> UIColor? {
        cache.object(forKey: CoverCache.identity(content) as NSString)
    }

    @MainActor
    static func extract(runtime: IosSources, content: IosSourceContent) async -> UIColor? {
        let key = CoverCache.identity(content) as NSString
        if let hit = cache.object(forKey: key) { return hit }
        guard let image = try? await CoverImage.image(runtime: runtime, content: content),
              !Task.isCancelled
        else { return nil }
        let extracted = await Task.detached(priority: .utility) {
            dominantColor(from: image)
        }.value
        if let extracted {
            cache.setObject(extracted, forKey: key)
        }
        return extracted
    }

    static func pageBackground(for dominant: UIColor?, colorScheme: ColorScheme, increasedContrast: Bool) -> Color {
        let systemBase = Color(uiColor: .systemGroupedBackground)
        guard let dominant, !increasedContrast else { return systemBase }

        var hue: CGFloat = 0
        var saturation: CGFloat = 0
        var brightness: CGFloat = 0
        var alpha: CGFloat = 0
        guard dominant.getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha),
              saturation > 0.04
        else { return systemBase }

        if colorScheme == .dark {
            let accent = UIColor(
                hue: hue,
                saturation: min(max(saturation, 0.30), 0.65),
                brightness: 0.40,
                alpha: 1
            )
            let darkBase = UIColor.systemGroupedBackground.resolvedColor(with: UITraitCollection(userInterfaceStyle: .dark))
            return Color(uiColor: blend(darkBase, toward: accent, fraction: 0.28))
        } else {
            let accent = UIColor(
                hue: hue,
                saturation: min(max(saturation, 0.32), 0.72),
                brightness: 0.90,
                alpha: 1
            )
            let lightBase = UIColor.systemGroupedBackground.resolvedColor(with: UITraitCollection(userInterfaceStyle: .light))
            return Color(uiColor: blend(lightBase, toward: accent, fraction: 0.16))
        }
    }

    private static func blend(_ base: UIColor, toward accent: UIColor, fraction: CGFloat) -> UIColor {
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        guard base.getRed(&r1, green: &g1, blue: &b1, alpha: &a1),
              accent.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        else { return base }
        let t = min(max(fraction, 0), 1)
        return UIColor(
            red: r1 + (r2 - r1) * t,
            green: g1 + (g2 - g1) * t,
            blue: b1 + (b2 - b1) * t,
            alpha: 1
        )
    }

    private static func dominantColor(from image: UIImage) -> UIColor? {
        guard let cgImage = image.cgImage else { return nil }
        let side = 24
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: &pixels,
            width: side,
            height: side,
            bitsPerComponent: 8,
            bytesPerRow: side * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        ) else { return nil }
        context.interpolationQuality = .medium
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: side, height: side))

        let binCount = 24
        var binWeight = [CGFloat](repeating: 0, count: binCount)
        var binHueX = [CGFloat](repeating: 0, count: binCount)
        var binHueY = [CGFloat](repeating: 0, count: binCount)
        var binSat = [CGFloat](repeating: 0, count: binCount)
        var binBri = [CGFloat](repeating: 0, count: binCount)

        for y in 0..<side {
            // CoreGraphics origin is bottom-left; favor the lower portion of the cover that meets the page fade.
            let regionWeight: CGFloat = y < side / 2 ? 1.8 : 1.0
            for x in 0..<side {
                let offset = (y * side + x) * 4
                let a = pixels[offset + 3]
                guard a >= 128 else { continue }
                let scale = CGFloat(a)
                let r = CGFloat(pixels[offset]) / scale
                let g = CGFloat(pixels[offset + 1]) / scale
                let b = CGFloat(pixels[offset + 2]) / scale

                var h: CGFloat = 0, s: CGFloat = 0, v: CGFloat = 0
                UIColor(red: r, green: g, blue: b, alpha: 1).getHue(&h, saturation: &s, brightness: &v, alpha: nil)

                guard s > 0.14, v > 0.08, !(v > 0.96 && s < 0.18) else { continue }
                let weight = regionWeight * (0.35 + s * 1.4)
                let bin = min(Int(h * CGFloat(binCount)), binCount - 1)
                let angle = h * 2 * .pi
                binWeight[bin] += weight
                binHueX[bin] += cos(angle) * weight
                binHueY[bin] += sin(angle) * weight
                binSat[bin] += s * weight
                binBri[bin] += v * weight
            }
        }

        guard let best = (0..<binCount).max(by: { binWeight[$0] < binWeight[$1] }),
              binWeight[best] > 0
        else { return nil }

        let total = binWeight[best]
        var avgAngle = atan2(binHueY[best], binHueX[best])
        if avgAngle < 0 { avgAngle += 2 * .pi }
        return UIColor(
            hue: avgAngle / (2 * .pi),
            saturation: binSat[best] / total,
            brightness: binBri[best] / total,
            alpha: 1
        )
    }
}

/// The cover as a wide panorama behind the details header. [scrollOffset] is the list's offset from its resting
/// position: positive scrolls the banner away with the content, negative (pull to refresh) stretches it.
struct DetailsPanorama: View {
    let runtime: IosSources
    let content: IosSourceContent
    let scrollOffset: CGFloat
    let coverBottom: CGFloat?
    @AppStorage(PanoramaKeys.preset) private var preset = PanoramaPreset.balanced
    @AppStorage(PanoramaKeys.layout) private var layout = PanoramaLayout.top
    @AppStorage(PanoramaKeys.animated) private var animated = true
    @AppStorage(PanoramaKeys.scrollLinked) private var scrollLinked = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drifting = false
    @State private var lastCoverBottom: CGFloat?
    private static let fadeEnd = 0.96
    private static let horizontalTravel: CGFloat = 14

    var body: some View {
        GeometryReader { geometry in
            let values = preset.values(layout)
            let screen = geometry.size.height
            // Resolve the fade's endpoint against the card, retaining its height when List recycles the header.
            let bottom = coverBottom ?? lastCoverBottom ?? 0
            let base = layout == .top ? max(bottom, 0) / Self.fadeEnd : screen
            let stretch = max(-scrollOffset, 0)
            let follows = layout == .top && scrollLinked
            let motion = animated && !reduceMotion
            let blurRadius = CGFloat(values.blur / 100 * 18)
            // Reserve the full travel plus the blur kernel before clipping, including at the smallest zoom.
            let blurPadding = ceil(blurRadius * 3) + 1
            let fadeDistance: CGFloat = 200.0
            let scrollAlpha = max(0.0, min(1.0, 1.0 - (max(scrollOffset, 0) / fadeDistance)))
            CoverImage(runtime: runtime, content: content, plain: true)
                .frame(width: geometry.size.width + 2 * (Self.horizontalTravel + blurPadding),
                       height: base + stretch + 2 * blurPadding)
                .scaleEffect(motion ? (drifting ? 1.06 : 1) : 1, anchor: .center)
                .offset(x: motion ? (drifting ? Self.horizontalTravel : -Self.horizontalTravel) : 0)
                .blur(radius: blurRadius, opaque: false)
                .frame(width: geometry.size.width, height: base + stretch)
                .clipped()
                .opacity((values.opacity / 100) * scrollAlpha)
                .mask(Self.fade(range: values.range / 100))
                .offset(y: follows ? -max(scrollOffset, 0) : 0)
        }
        .onAppear { startDrift() }
        .onChange(of: coverBottom, initial: true) { _, bottom in
            if let bottom, bottom > 0 { lastCoverBottom = bottom }
        }
        .onChange(of: animated) { _, _ in startDrift() }
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }

    private func startDrift() {
        drifting = false
        guard animated && !reduceMotion else { return }
        withAnimation(.linear(duration: 22).repeatForever(autoreverses: true)) { drifting = true }
    }

    /// Opaque at the top, dissolving into the page below; a wider range starts the fade earlier.
    static func fade(range: Double) -> LinearGradient {
        let start = 0.82 - (0.82 - 0.18) * min(max(range, 0), 1)
        let length = fadeEnd - start
        return LinearGradient(stops: [
            .init(color: .black, location: 0),
            .init(color: .black, location: start),
            .init(color: .black.opacity(0.82), location: start + length * 0.35),
            .init(color: .black.opacity(0.28), location: start + length * 0.70),
            .init(color: .clear, location: fadeEnd),
            .init(color: .clear, location: 1),
        ], startPoint: .top, endPoint: .bottom)
    }
}

/// Settings for the details panorama, mirroring Android's appearance options.
struct PanoramaSettingsSection: View {
    @AppStorage(PanoramaKeys.enabled) private var enabled = true
    @AppStorage(PanoramaKeys.preset) private var preset = PanoramaPreset.balanced
    @AppStorage(PanoramaKeys.layout) private var layout = PanoramaLayout.top
    @AppStorage(PanoramaKeys.animated) private var animated = true
    @AppStorage(PanoramaKeys.scrollLinked) private var scrollLinked = true

    var body: some View {
        Section {
            Toggle("全景封面", isOn: $enabled).accessibilityIdentifier("settings.panorama")
            if enabled {
                Picker("布局", selection: $layout) {
                    ForEach(PanoramaLayout.allCases) { Text($0.title).tag($0) }
                }
                Picker("效果", selection: $preset) {
                    ForEach(PanoramaPreset.allCases) { Text($0.title).tag($0) }
                }
                Toggle("封面动画", isOn: $animated)
                if layout == .top { Toggle("跟随滚动", isOn: $scrollLinked) }
            }
        } footer: {
            Text(enabled ? "在详情页顶部标题后方显示横幅式的封面背景图；开启“减弱动态效果”时不播放动画。"
                 : "关闭后使用模糊封面作为页面背景。")
        }
    }
}
