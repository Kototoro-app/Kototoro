import Charts
import Combine
import KototoroShared
import SwiftUI

enum ReadingStatsRange: String, CaseIterable, Identifiable {
    case day = "DAY", week = "WEEK", month = "MONTH", quarter = "MONTHS_3", all = "ALL"
    var id: String { rawValue }
    var title: String {
        switch self {
        case .day: "今天"
        case .week: "近 7 天"
        case .month: "近 30 天"
        case .quarter: "近 90 天"
        case .all: "全部"
        }
    }
}

enum ReadingStatsFormat {
    static func duration(_ milliseconds: Int64) -> String {
        let minutes = max(0, milliseconds) / 60_000
        if milliseconds > 0 && minutes == 0 { return "不足 1 分钟" }
        if minutes < 60 { return "\(minutes) 分钟" }
        return minutes % 60 == 0 ? "\(minutes / 60) 小时" : "\(minutes / 60) 小时 \(minutes % 60) 分"
    }

    static func date(_ milliseconds: Int64) -> Date { Date(timeIntervalSince1970: Double(milliseconds) / 1000) }

    static func day(_ date: Date) -> String {
        let parts = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    static func dayTitle(_ milliseconds: Int64) -> String {
        let date = date(milliseconds)
        if Calendar.current.isDateInToday(date) { return "今天" }
        if Calendar.current.isDateInYesterday(date) { return "昨天" }
        return date.formatted(.dateTime.month().day())
    }
}

@MainActor
final class ReadingStatsModel: ObservableObject {
    @Published private(set) var snapshot: IosStatsDashboard?
    @Published private(set) var loading = false
    @Published private(set) var error: String?
    private var request: UUID?

    func load(_ library: IosLibrary?, range: ReadingStatsRange, kind: String,
              query: String = "", contentId: String? = nil) async {
        guard let library else { return }
        let token = UUID()
        request = token
        loading = true
        defer { if request == token { loading = false } }
        do {
            let value = try await library.statistics.snapshot(period: range.rawValue, kind: kind,
                                                              query: query, contentId: contentId)
            try Task.checkCancellation()
            guard request == token else { return }
            snapshot = value
            error = nil
        } catch {
            if request == token && !Task.isCancelled { self.error = error.localizedDescription }
        }
    }
}

/// The history header and work sheet use the same metrics and explicitly dated duration chart.
struct ReadingStatsOverview: View {
    let snapshot: IosStatsDashboard
    @Binding var selectedDay: String?
    var runtime: IosSources?
    var showsRanking = true
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("阅读概览").font(.caption).foregroundStyle(.secondary)
                    Text(ReadingStatsFormat.duration(snapshot.totalDuration))
                        .font(.title2.weight(.semibold)).monospacedDigit()
                        .accessibilityIdentifier("stats.duration")
                }
                Spacer(minLength: 4)
                Button {
                    withAnimation(.snappy) { expanded.toggle() }
                } label: {
                    Image(systemName: expanded ? "chevron.up" : "chart.bar.xaxis")
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(expanded ? "收起详细统计" : "展开详细统计")
                .accessibilityIdentifier("stats.expand")
            }
            HStack(spacing: 0) {
                metric("作品", snapshot.workCount)
                metric("活跃天", snapshot.activeDays)
                metric("阅读次数", snapshot.sessionCount)
                metric("连续天", snapshot.currentStreak)
            }
            Text(snapshot.dailyActivity.count == 1 ? "今日活动" : "近 \(snapshot.dailyActivity.count) 天活动")
                .font(.caption2).foregroundStyle(.secondary)
            activityChart
            if let selectedDay {
                HStack {
                    Text("\(selectedDay) · \(ReadingStatsFormat.duration(snapshot.dailyActivity.first { $0.date == selectedDay }?.duration ?? 0))")
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                    Button("全部日期") { self.selectedDay = nil }.font(.caption)
                }
                .accessibilityIdentifier("stats.selectedDay")
            }
            if expanded { expandedStats }
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
    }

    private func metric(_ label: String, _ value: Int32) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value.formatted()).font(.subheadline.weight(.medium)).monospacedDigit()
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var activityChart: some View {
        Chart(snapshot.dailyActivity, id: \.date) { day in
            BarMark(x: .value("日期", ReadingStatsFormat.date(day.startedAt), unit: .day),
                    y: .value("分钟", Double(day.duration) / 60_000))
                .foregroundStyle(selectedDay == nil || selectedDay == day.date ? Color.accentColor : Color.secondary.opacity(0.2))
                .cornerRadius(2)
                .accessibilityLabel(day.date)
                .accessibilityValue(ReadingStatsFormat.duration(day.duration))
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartYScale(domain: 0...max(1.0 / 60, Double(snapshot.dailyActivity.max(by: { $0.duration < $1.duration })?.duration ?? 0) / 60_000))
        .chartOverlay { proxy in
            GeometryReader { geometry in
                Rectangle().fill(.clear).contentShape(Rectangle())
                    .onTapGesture { location in
                        guard let frame = proxy.plotFrame else { return }
                        let x = location.x - geometry[frame].origin.x
                        guard x >= 0, x <= geometry[frame].width,
                              let date: Date = proxy.value(atX: x) else { return }
                        let day = ReadingStatsFormat.day(date)
                        selectedDay = selectedDay == day ? nil : day
                    }
            }
        }
        .frame(height: 58)
        .accessibilityIdentifier("stats.activity")
    }

    private var expandedStats: some View {
        VStack(alignment: .leading, spacing: 14) {
            Divider()
            if snapshot.hourlyActivity.contains(where: { $0.duration > 0 }) {
                Text("阅读时段").font(.subheadline.weight(.medium))
                Chart(snapshot.hourlyActivity, id: \.hour) { item in
                    BarMark(x: .value("小时", item.hour), y: .value("分钟", Double(item.duration) / 60_000))
                        .foregroundStyle(Color.accentColor).cornerRadius(2)
                }
                .chartXAxis { AxisMarks(values: [0, 6, 12, 18, 23]) }
                .chartYAxis(.hidden)
                .frame(height: 90)
            }
            let kinds = Dictionary(grouping: snapshot.works.filter(\.hasTimedStats), by: \.content.mediaKind)
            if kinds.count > 1 {
                HStack {
                    ForEach(["MANGA", "NOVEL", "VIDEO"], id: \.self) { kind in
                        if let works = kinds[kind] {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(kind == "MANGA" ? "漫画" : kind == "NOVEL" ? "小说" : "视频")
                                    .font(.caption).foregroundStyle(.secondary)
                                Text(ReadingStatsFormat.duration(works.reduce(Int64(0)) { $0 + $1.duration }))
                                    .font(.caption.weight(.medium))
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
            if showsRanking, let runtime {
                Text("阅读最多").font(.subheadline.weight(.medium))
                ForEach(Array(snapshot.works.filter(\.hasTimedStats).sorted { $0.duration > $1.duration }.prefix(5)), id: \.content.id) { work in
                    NavigationLink {
                        SourceDetailsView(runtime: runtime, content: work.content)
                    } label: {
                        HStack {
                            Text(work.content.title).lineLimit(1)
                            Spacer(minLength: 6)
                            Text(ReadingStatsFormat.duration(work.duration)).foregroundStyle(.secondary)
                        }.font(.caption).padding(.vertical, 7)
                    }.buttonStyle(.plain)
                }
            }
        }
    }
}

struct ContentReadingStatsSheet: View {
    let content: IosSourceContent
    let runtime: IosSources
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = ReadingStatsModel()
    @State private var selectedDay: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(content.title).font(.headline)
                    if let snapshot = model.snapshot {
                        ReadingStatsOverview(snapshot: snapshot, selectedDay: $selectedDay,
                                             runtime: runtime, showsRanking: false)
                        if let work = snapshot.works.first, work.hasTimedStats {
                            let unit = content.mediaKind == "MANGA" ? "浏览页数" : content.mediaKind == "NOVEL" ? "涉及章节" : "涉及集数"
                            HStack {
                                Label("\(work.units) \(unit)", systemImage: "book.pages")
                                Spacer()
                                if work.hasProgress { Text("当前进度 \(Int(work.progress * 100))%") }
                            }.font(.caption).foregroundStyle(.secondary)
                            Text("首次阅读 · " + ReadingStatsFormat.date(work.firstActivityAt).formatted(date: .abbreviated, time: .omitted))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        let events = snapshot.timeline.filter { selectedDay == nil || ReadingStatsFormat.day(ReadingStatsFormat.date($0.timestamp)) == selectedDay }
                        Text("阅读记录").font(.headline)
                        if events.isEmpty {
                            ContentUnavailableView("暂无阅读统计", systemImage: "chart.bar",
                                description: Text("阅读漫画、小说或播放视频后，时长会自动记录。历史进度无法还原过去的时长。"))
                        }
                        ForEach(events, id: \.id) { event in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(ReadingStatsFormat.dayTitle(event.timestamp))
                                    Spacer()
                                    Text(ReadingStatsFormat.duration(event.duration)).foregroundStyle(.secondary)
                                }
                                Text(event.detail).font(.caption).foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 6)
                        }
                    } else if model.loading { ProgressView().frame(maxWidth: .infinity) }
                    if let error = model.error { Text(error).foregroundStyle(.red).font(.footnote) }
                }.padding()
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("阅读统计").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .task { await model.load(library.library, range: .all, kind: "ALL", contentId: content.id) }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
