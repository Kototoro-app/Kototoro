import KototoroShared
import SwiftUI

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
