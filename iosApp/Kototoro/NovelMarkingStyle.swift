import KototoroShared
import SwiftUI
import UIKit

/// Android's highlight colors (`NovelMarkingColor`), by the id stored in `novel_markings.color`.
enum NovelMarkingColor: Int, CaseIterable, Identifiable {
    case yellow = 0, green, blue, pink, orange

    var id: Int { rawValue }

    static func of(_ id: Int32) -> NovelMarkingColor { NovelMarkingColor(rawValue: Int(id)) ?? .yellow }

    var title: String {
        switch self {
        case .yellow: String(localized: "经典黄")
        case .green: String(localized: "清新绿")
        case .blue: String(localized: "雅致蓝")
        case .pink: String(localized: "淡雅粉")
        case .orange: String(localized: "暖阳橙")
        }
    }

    /// The line drawn under underlined and wavy highlights (Android's `lineHex`).
    var line: UIColor {
        switch self {
        case .yellow: UIColor(argb: 0xFFF59E0B)
        case .green: UIColor(argb: 0xFF10B981)
        case .blue: UIColor(argb: 0xFF3B82F6)
        case .pink: UIColor(argb: 0xFFEC4899)
        case .orange: UIColor(argb: 0xFFF97316)
        }
    }

    /// The marker fill behind highlighted text (Android's `bgHex`, translucent).
    var fill: UIColor {
        switch self {
        case .yellow: UIColor(argb: 0x59FDE68A)
        case .green: UIColor(argb: 0x59A7F3D0)
        case .blue: UIColor(argb: 0x59BFDBFE)
        case .pink: UIColor(argb: 0x59FBCFE8)
        case .orange: UIColor(argb: 0x59FED7AA)
        }
    }

    /// A swatch for menus: a filled circle, checked when [selected].
    func swatch(selected: Bool) -> UIImage? {
        UIImage(systemName: selected ? "checkmark.circle.fill" : "circle.fill")?
            .withTintColor(line, renderingMode: .alwaysOriginal)
    }
}

/// Android's highlight shapes (`NovelMarkingStyle`), by the id stored in `novel_markings.style`.
enum NovelMarkingLine: Int, CaseIterable, Identifiable {
    case underline = 0, wavy, marker

    var id: Int { rawValue }

    static func of(_ id: Int32) -> NovelMarkingLine { NovelMarkingLine(rawValue: Int(id)) ?? .underline }

    var title: String {
        switch self {
        case .underline: String(localized: "直线")
        case .wavy: String(localized: "波浪线")
        case .marker: String(localized: "马克笔")
        }
    }

    var systemImage: String {
        switch self {
        case .underline: "underline"
        case .wavy: "scribble"
        case .marker: "highlighter"
        }
    }
}

/// The color and shape new highlights get; the last ones chosen, as on Android.
enum NovelMarkingDefaults {
    static let colorKey = "novel.reader.markingColor"
    static let lineKey = "novel.reader.markingLine"

    static var color: NovelMarkingColor {
        get { NovelMarkingColor(rawValue: UserDefaults.standard.integer(forKey: colorKey)) ?? .yellow }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: colorKey) }
    }

    static var line: NovelMarkingLine {
        get { NovelMarkingLine(rawValue: UserDefaults.standard.integer(forKey: lineKey)) ?? .underline }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: lineKey) }
    }
}

/// A saved highlight placed in a piece of text: where it lies there and how it is drawn.
struct NovelHighlight {
    let id: Int64
    let start: Int
    let end: Int
    let color: NovelMarkingColor
    let line: NovelMarkingLine

    func shifted(by offset: Int) -> NovelHighlight {
        NovelHighlight(id: id, start: start + offset, end: end + offset, color: color, line: line)
    }
}

extension NSAttributedString.Key {
    /// The id (`NSNumber`) of the saved highlight covering these characters.
    static let novelMarking = NSAttributedString.Key("org.skepsun.kototoro.novelMarking")
    /// The color of a wavy line drawn under these characters (`NovelMarkingLayoutFragment`).
    static let novelWavyLine = NSAttributedString.Key("org.skepsun.kototoro.novelWavyLine")
}

extension NSMutableAttributedString {
    /// Draws [highlights] the way Android does: a colored underline, a wavy line, or a marker fill.
    func addNovelHighlights(_ highlights: [NovelHighlight]) {
        for highlight in highlights {
            let start = min(max(0, highlight.start), length)
            let end = min(max(start, highlight.end), length)
            guard end > start else { continue }
            let range = NSRange(location: start, length: end - start)
            addAttribute(.novelMarking, value: NSNumber(value: highlight.id), range: range)
            switch highlight.line {
            case .underline:
                addAttribute(.underlineStyle, value: NSUnderlineStyle.thick.rawValue, range: range)
                addAttribute(.underlineColor, value: highlight.color.line, range: range)
            case .wavy:
                addAttribute(.novelWavyLine, value: highlight.color.line, range: range)
            case .marker:
                addAttribute(.backgroundColor, value: highlight.color.fill, range: range)
            }
        }
    }
}

/// A TextKit 2 paragraph that also draws wavy lines under `.novelWavyLine` runs (TextKit has no wavy underline).
final class NovelMarkingLayoutFragment: NSTextLayoutFragment {
    override var renderingSurfaceBounds: CGRect {
        super.renderingSurfaceBounds.insetBy(dx: 0, dy: -6)
    }

    override func draw(at point: CGPoint, in context: CGContext) {
        super.draw(at: point, in: context)
        for line in textLineFragments {
            let string = line.attributedString
            let range = line.characterRange
            guard range.location + range.length <= string.length else { continue }
            string.enumerateAttribute(.novelWavyLine, in: range) { value, run, _ in
                guard let color = value as? UIColor else { return }
                var end = NSMaxRange(run)
                let text = string.string as NSString
                while end > run.location, text.character(at: end - 1) == 0x0A { end -= 1 }
                guard end > run.location else { return }
                let bounds = line.typographicBounds
                let from = point.x + bounds.minX + line.locationForCharacter(at: run.location).x
                let to = point.x + bounds.minX + line.locationForCharacter(at: end).x
                let y = point.y + bounds.minY + line.glyphOrigin.y + 3
                Self.wave(from: from, to: to, y: y, color: color, in: context)
            }
        }
    }

    private static func wave(from: CGFloat, to: CGFloat, y: CGFloat, color: UIColor, in context: CGContext) {
        guard to > from else { return }
        let amplitude: CGFloat = 1.6
        let half: CGFloat = 3
        let path = CGMutablePath()
        path.move(to: CGPoint(x: from, y: y))
        var x = from
        var up = true
        while x < to {
            let next = min(x + half, to)
            path.addQuadCurve(to: CGPoint(x: next, y: y),
                              control: CGPoint(x: (x + next) / 2, y: y + (up ? -amplitude : amplitude) * 2))
            x = next
            up.toggle()
        }
        context.saveGState()
        context.addPath(path)
        context.setStrokeColor(color.cgColor)
        context.setLineWidth(1.3)
        context.setLineCap(.round)
        context.strokePath()
        context.restoreGState()
    }
}

/// Writes or edits the note of a highlight, under its quoted text.
struct NovelMarkingNoteSheet: View {
    let marking: IosNovelMarking
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var draft: String
    @FocusState private var focused: Bool

    init(marking: IosNovelMarking, onSave: @escaping (String) -> Void) {
        self.marking = marking
        self.onSave = onSave
        _draft = State(initialValue: marking.note ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(marking.text)
                        .font(.callout)
                        .lineLimit(6)
                        .padding(.leading, 10)
                        .overlay(alignment: .leading) {
                            Capsule().fill(Color(uiColor: NovelMarkingColor.of(marking.color).line)).frame(width: 3)
                        }
                        .accessibilityIdentifier("novel.marking.note.quote")
                }
                Section("批注") {
                    TextField("写下你的想法", text: $draft, axis: .vertical)
                        .lineLimit(4...12)
                        .focused($focused)
                        .accessibilityIdentifier("novel.marking.note.field")
                }
            }
            .navigationTitle(marking.note?.isEmpty == false ? "编辑批注" : "添加批注")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { onSave(draft); dismiss() }
                        .accessibilityIdentifier("novel.marking.note.save")
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium, .large])
    }
}

extension IosNovelMarking: @retroactive Identifiable {}
