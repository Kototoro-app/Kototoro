import SwiftUI
import WidgetKit

/// Android's recent-works widget, following Apple's widget guidelines: glanceable (title, where you are, progress),
/// works offline from a snapshot the app writes into the shared App Group, every work deep-links to itself, and the
/// Home Screen sizes come with Lock Screen accessories. System fonts and materials keep it right in light, dark and
/// tinted appearances.
struct WidgetWork: Codable, Identifiable {
    let id: String
    let title: String
    let detail: String
    let progress: Double?
    let cover: String?
    var link: URL? { URL(string: "kototoro://continue?key=" + (id.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")) }
}

private let groupID = "group.org.skepsun.kototoro.ios"

private func container() -> URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupID) }

struct RecentEntry: TimelineEntry {
    let date: Date
    let works: [WidgetWork]
}

struct RecentProvider: TimelineProvider {
    func placeholder(in context: Context) -> RecentEntry { RecentEntry(date: .now, works: Self.sample) }

    func getSnapshot(in context: Context, completion: @escaping (RecentEntry) -> Void) {
        let entry = load()
        // The widget gallery shows sample content until there is reading history.
        completion(context.isPreview && entry.works.isEmpty ? placeholder(in: context) : entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<RecentEntry>) -> Void) {
        // The app reloads the timeline whenever the history changes; nothing here changes on its own.
        completion(Timeline(entries: [load()], policy: .never))
    }

    private func load() -> RecentEntry {
        guard let url = container()?.appending(path: "recent.json"), let data = try? Data(contentsOf: url),
              let works = try? JSONDecoder().decode([WidgetWork].self, from: data) else {
            return RecentEntry(date: .now, works: [])
        }
        return RecentEntry(date: .now, works: works)
    }

    static let sample = [
        WidgetWork(id: "sample.1", title: "葬送的芙莉莲", detail: "第 120 话", progress: 0.62, cover: nil),
        WidgetWork(id: "sample.2", title: "三体", detail: "第 12 章", progress: 0.3, cover: nil),
        WidgetWork(id: "sample.3", title: "间谍过家家", detail: "第 8 集", progress: 0.85, cover: nil),
    ]
}

struct RecentWidgetView: View {
    let entry: RecentEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        content.containerBackground(.fill.tertiary, for: .widget)
    }

    @ViewBuilder private var content: some View {
        if let first = entry.works.first {
            switch family {
            case .accessoryInline:
                Label(first.title, systemImage: "book").widgetURL(first.link)
            case .accessoryCircular:
                Gauge(value: first.progress ?? 0) { Image(systemName: "book.fill") }
                    .gaugeStyle(.accessoryCircularCapacity)
                    .widgetURL(first.link)
                    .accessibilityLabel(Text("\(first.title)，\(first.detail)"))
            case .accessoryRectangular:
                VStack(alignment: .leading, spacing: 2) {
                    Text(first.title).font(.headline).widgetAccentable().lineLimit(1)
                    Text(first.detail).font(.caption).lineLimit(1)
                    if let progress = first.progress { ProgressView(value: progress) }
                }
                .widgetURL(first.link)
            case .systemSmall:
                small(first)
            default:
                medium
            }
        } else {
            empty
        }
    }

    private var empty: some View {
        VStack(spacing: 4) {
            Image(systemName: "book.closed").font(.title3)
            if family != .accessoryCircular && family != .accessoryInline {
                Text("开始阅读后显示在这里").font(.caption2).multilineTextAlignment(.center)
            }
        }
        .foregroundStyle(.secondary)
    }

    private func small(_ work: WidgetWork) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            cover(work).frame(maxWidth: .infinity, maxHeight: .infinity)
            Text(work.title).font(.footnote.weight(.semibold)).lineLimit(1)
            details(work)
        }
        .widgetURL(work.link)
    }

    private var medium: some View {
        HStack(alignment: .top, spacing: 12) {
            ForEach(entry.works.prefix(3)) { work in
                // Each work opens itself (Link), rather than the whole widget opening one.
                Link(destination: work.link ?? URL(string: "kototoro://")!) {
                    VStack(alignment: .leading, spacing: 4) {
                        cover(work).frame(maxWidth: .infinity).frame(height: 92)
                        Text(work.title).font(.caption.weight(.semibold)).lineLimit(1)
                        details(work)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .topLeading)
            }
        }
    }

    @ViewBuilder private func cover(_ work: WidgetWork) -> some View {
        if let name = work.cover, let url = container()?.appending(path: "covers/\(name)"),
           let image = UIImage(contentsOfFile: url.path) {
            Image(uiImage: image)
                .resizable()
                .widgetAccentedRenderingMode(.desaturated)
                .scaledToFill()
                .clipShape(ContainerRelativeShape())
                .accessibilityHidden(true)
        } else {
            ContainerRelativeShape().fill(.quaternary)
                .overlay { Image(systemName: "book").foregroundStyle(.secondary) }
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder private func details(_ work: WidgetWork) -> some View {
        if let progress = work.progress {
            ProgressView(value: progress).accessibilityLabel(Text(work.detail))
        } else {
            Text(work.detail).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

struct RecentWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "recent", provider: RecentProvider()) { RecentWidgetView(entry: $0) }
            .configurationDisplayName("继续阅读")
            .description("最近阅读的作品与进度，轻点继续。")
            .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryCircular, .accessoryInline])
    }
}

@main
struct KototoroWidgets: WidgetBundle {
    var body: some Widget { RecentWidget() }
}

#Preview(as: .systemMedium) {
    RecentWidget()
} timeline: {
    RecentEntry(date: .now, works: RecentProvider.sample)
    RecentEntry(date: .now, works: [])
}

#Preview(as: .accessoryRectangular) {
    RecentWidget()
} timeline: {
    RecentEntry(date: .now, works: RecentProvider.sample)
}
