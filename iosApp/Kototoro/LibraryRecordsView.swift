import KototoroShared
import SwiftUI

private struct DateThresholds {
    let startOfTodayMs: Int64
    let startOfYesterdayMs: Int64
    let startOfThisWeekMs: Int64

    static func current() -> DateThresholds {
        let calendar = Calendar.current
        let now = Date()
        let startOfToday = calendar.startOfDay(for: now)
        let startOfYesterday = calendar.date(byAdding: .day, value: -1, to: startOfToday) ?? startOfToday
        let startOfThisWeek = calendar.date(byAdding: .day, value: -7, to: now) ?? startOfToday
        return DateThresholds(
            startOfTodayMs: Int64(startOfToday.timeIntervalSince1970 * 1000),
            startOfYesterdayMs: Int64(startOfYesterday.timeIntervalSince1970 * 1000),
            startOfThisWeekMs: Int64(startOfThisWeek.timeIntervalSince1970 * 1000)
        )
    }

    func group(for timestampMs: Int64) -> DateGroup {
        if timestampMs >= startOfTodayMs { return .today }
        if timestampMs >= startOfYesterdayMs { return .yesterday }
        if timestampMs >= startOfThisWeekMs { return .thisWeek }
        return .earlier
    }
}

private enum DateGroup: String, CaseIterable, Comparable {
    case today = "今天"
    case yesterday = "昨天"
    case thisWeek = "本周"
    case earlier = "更早"

    private var sortOrder: Int {
        switch self {
        case .today: return 0
        case .yesterday: return 1
        case .thisWeek: return 2
        case .earlier: return 3
        }
    }

    static func < (lhs: DateGroup, rhs: DateGroup) -> Bool {
        lhs.sortOrder < rhs.sortOrder
    }
}

/// History and update records in date-grouped layouts and an optional unified timeline.
struct LibraryRecordsView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @State private var selection = 0
    @AppStorage("listMode.history") private var historyMode = ContentListMode.list
    @AppStorage("listMode.updates") private var updatesMode = ContentListMode.list
    @AppStorage("records.kind") private var pageKind = ""
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true
    @State private var query = ""
    @StateObject private var stats = ReadingStatsModel()
    @AppStorage("records.statsRange") private var range = ReadingStatsRange.month
    @State private var selectedDay: String?
    @State private var lastStatsRequest: String?

    private var filterKind: String? {
        if spacesEnabled { return space.isEmpty ? nil : space }
        return ContentKindPicker.effective(pageKind, available: availableKinds)
    }

    private var effectiveKind: String {
        filterKind ?? "ALL"
    }

    private var statsRequest: String {
        "\(library.recordsRevision)|\(range.rawValue)|\(effectiveKind)|\(query)"
    }

    private final class KindsCache {
        var revision: Int = -1
        var snapshot: AnyObject?
        var kinds: Set<String> = []
    }
    @State private var kindsCache = KindsCache()

    private var availableKinds: Set<String> {
        if kindsCache.revision == library.recordsRevision && kindsCache.snapshot === stats.snapshot {
            return kindsCache.kinds
        }
        var kinds = Set<String>()
        for entry in library.history { kinds.insert(entry.content.mediaKind) }
        for update in library.updates { kinds.insert(update.content.mediaKind) }
        if let snapshot = stats.snapshot {
            for work in snapshot.works { kinds.insert(work.content.mediaKind) }
            for event in snapshot.timeline { kinds.insert(event.content.mediaKind) }
        }
        kindsCache.revision = library.recordsRevision
        kindsCache.snapshot = stats.snapshot
        kindsCache.kinds = kinds
        return kinds
    }

    /// The content space (or this page's switch) and the in-place search over titles and authors.
    private func matches(_ content: IosSourceContent) -> Bool {
        if let filterKind, content.mediaKind != filterKind { return false }
        let text = query.trimmingCharacters(in: .whitespaces)
        return text.isEmpty || content.title.localizedCaseInsensitiveContains(text) ||
            content.authors.contains { $0.localizedCaseInsensitiveContains(text) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if selection == 0 { MissingSourcesNotice(historyOnly: true).padding(.horizontal) }

                    if !spacesEnabled && ContentKindPicker.isShown(availableKinds) {
                        ContentKindPicker(selection: $pageKind, available: availableKinds).padding(.horizontal)
                    }
                    if selection != 1 {
                        HStack {
                            Picker("统计周期", selection: $range) {
                                ForEach(ReadingStatsRange.allCases) { Text($0.title).tag($0) }
                            }
                            .pickerStyle(.menu)
                            .accessibilityIdentifier("stats.period")
                            Spacer()
                            if stats.loading { ProgressView().controlSize(.small) }
                        }.padding(.horizontal)
                    }
                    if selection == 0, let snapshot = stats.snapshot {
                        ReadingStatsOverview(snapshot: snapshot, selectedDay: $selectedDay, runtime: sources.runtime)
                            .padding(.horizontal)
                    }
                    if let error = stats.error {
                        Text(error).font(.footnote).foregroundStyle(.red).padding(.horizontal)
                    }
                    if let error = library.error {
                        Label(error, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.red)
                            .padding(.horizontal)
                    }
                    if let runtime = sources.runtime {
                        if selection == 0 {
                            history(runtime)
                        } else if selection == 1 {
                            updates(runtime)
                        } else {
                            timeline(runtime)
                        }
                    }
                }
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle(selection == 0 ? "历史" : (selection == 1 ? "更新" : "时间轴"))
            .navigationBarTitleDisplayMode(.inline)
            .pageSearch(text: $query, prompt: selection == 0 ? "搜索历史" : (selection == 1 ? "搜索更新" : "搜索时间轴"))
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Picker("记录", selection: $selection) {
                        Text("历史").tag(0)
                        Text("更新").tag(1)
                        Text("时间轴").tag(2)
                    }
                    .pickerStyle(.segmented)
                    .frame(maxWidth: 240)
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if spacesEnabled { SpaceMenu() }
                    if selection != 2 {
                        ContentListModeMenu(mode: selection == 0 ? $historyMode : $updatesMode)
                    }
                    if selection >= 1, let runtime = sources.runtime {
                        Button("检查更新", systemImage: "arrow.clockwise") { Task { await library.checkUpdates(runtime) } }
                            .disabled(library.isCheckingUpdates)
                            .accessibilityIdentifier("updates.check")
                    }
                }
            }
            .refreshable {
                if selection >= 1, let runtime = sources.runtime { await library.checkUpdates(runtime) }
                else {
                    await library.refresh()
                    await stats.load(library.library, range: range, kind: effectiveKind, query: query)
                    lastStatsRequest = statsRequest
                }
            }
            .task(id: statsRequest) {
                guard selection != 1 else { return }
                guard lastStatsRequest != statsRequest else { return }
                if !query.isEmpty {
                    do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
                }
                await stats.load(library.library, range: range, kind: effectiveKind, query: query)
                lastStatsRequest = statsRequest
            }
            .onChange(of: range) { _, _ in selectedDay = nil }
            .onChange(of: effectiveKind) { _, _ in selectedDay = nil }
            .onChange(of: selection) { _, newSelection in
                selectedDay = nil
                if newSelection != 1, lastStatsRequest != statsRequest {
                    Task {
                        await stats.load(library.library, range: range, kind: effectiveKind, query: query)
                        lastStatsRequest = statsRequest
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func history(_ runtime: IosSources) -> some View {
        if let snapshot = stats.snapshot {
            let matched = snapshot.works.filter { work in
                work.isHistoryVisible && (selectedDay == nil || work.dailyActivity.contains { $0.date == selectedDay })
            }
            if matched.isEmpty {
                if !query.isEmpty { filteredEmpty("阅读记录") }
                else {
                    ContentUnavailableView("此范围暂无阅读记录", systemImage: "clock",
                        description: Text("可切换统计周期查看更早的记录。阅读时长从启用此功能后开始记录。"))
                        .padding(.top, 24)
                }
            } else {
                let thresholds = DateThresholds.current()
                let grouped = Dictionary(grouping: matched) { thresholds.group(for: $0.updatedAt) }
                ForEach(DateGroup.allCases, id: \.self) { group in
                    if let entries = grouped[group], !entries.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(selectedDay ?? group.rawValue)
                                .font(.headline.weight(.semibold)).foregroundStyle(.secondary)
                                .padding(.horizontal).padding(.top, 6)
                            let items = entries.map { work in
                                let duration = selectedDay.flatMap { day in work.dailyActivity.first { $0.date == day }?.duration } ?? work.duration
                                let caption = [work.chapterTitle, work.hasTimedStats ? ReadingStatsFormat.duration(duration) : nil]
                                    .compactMap { $0 }.joined(separator: " · ")
                                return ContentItem(content: work.content, progress: work.hasProgress ? work.progress : nil,
                                                   subtitle: caption.isEmpty ? nil : caption)
                            }
                            ContentCollection(runtime: runtime, items: items, mode: historyMode, identifierPrefix: "history.") { item in
                                SourceDetailsView(runtime: runtime, content: item.content)
                            } actions: { item in
                                Button("删除记录", systemImage: "trash", role: .destructive) {
                                    Task { await library.removeHistory(item.content) }
                                }
                            }
                        }
                    }
                }
            }
        } else if stats.loading {
            ProgressView().frame(maxWidth: .infinity).padding()
        }
    }

    @ViewBuilder
    private func filteredEmpty(_ what: String) -> some View {
        if query.trimmingCharacters(in: .whitespaces).isEmpty {
            SpaceEmptyView(what: what).padding(.top, 40)
        } else {
            ContentUnavailableView.search(text: query).padding(.top, 40)
        }
    }

    @ViewBuilder
    private func updates(_ runtime: IosSources) -> some View {
        if library.isCheckingUpdates {
            HStack(spacing: 10) {
                ProgressView().scaleEffect(0.9)
                Text("正在检查收藏更新…")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal)
        } else if let summary = library.updateSummary {
            Text(summary).font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
        }

        if library.updates.isEmpty {
            ContentUnavailableView("暂无新章节", systemImage: "arrow.triangle.2.circlepath",
                                   description: Text("检查收藏作品，获取最新章节。"))
            .padding(.top, 60)
        } else {
            let matched = library.updates.filter { matches($0.content) }
            if matched.isEmpty {
                filteredEmpty("更新")
            } else {
                let thresholds = DateThresholds.current()
                let grouped = Dictionary(grouping: matched) { update in
                    thresholds.group(for: update.checkedAt)
                }
                ForEach(DateGroup.allCases, id: \.self) { group in
                    if let updatesList = grouped[group], !updatesList.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(group.rawValue)
                                .font(.headline.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .padding(.horizontal)
                                .padding(.top, 6)

                            // Clean badge on cover without redundant text subtitle
                            let items = updatesList.map {
                                ContentItem(content: $0.content, badge: "+\($0.newChapters)")
                            }
                            ContentCollection(runtime: runtime, items: items, mode: updatesMode, identifierPrefix: "update.") { item in
                                SourceDetailsView(runtime: runtime, content: item.content)
                                    .task {
                                        try? await library.library?.markUpdateRead(content: item.content)
                                        await library.refresh()
                                    }
                            } actions: { _ in EmptyView() }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func timeline(_ runtime: IosSources) -> some View {
        if let snapshot = stats.snapshot {
            RecordsTimeline(events: snapshot.timeline, runtime: runtime)
        } else if stats.loading {
            ProgressView().frame(maxWidth: .infinity).padding()
        }
    }
}

/// Dates share the center line; each lane orders its own events without forcing unrelated times into pairs.
private struct RecordsTimeline: View {
    let events: [IosTimelineEvent]
    let runtime: IosSources
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var selected: SelectedEvent?
    private struct SelectedEvent: Identifiable {
        let event: IosTimelineEvent
        var id: String { event.id }
    }
    private struct TimelineDayGroup {
        let date: String
        let all: [IosTimelineEvent]
        let history: [IosTimelineEvent]
        let updates: [IosTimelineEvent]
    }

    var body: some View {
        if events.isEmpty {
            ContentUnavailableView("暂无足迹", systemImage: "timeline.selection",
                description: Text("阅读足迹与发现更新的记录会按日期保留在这里。"))
                .padding(.top, 32)
        } else {
            let grouped = Dictionary(grouping: events) { ReadingStatsFormat.day(ReadingStatsFormat.date($0.timestamp)) }
            let dates = grouped.keys.sorted(by: >)
            let dayGroups: [TimelineDayGroup] = dates.compactMap { date in
                guard let dayEvents = grouped[date] else { return nil }
                var history: [IosTimelineEvent] = []
                var updates: [IosTimelineEvent] = []
                for event in dayEvents {
                    if event.isUpdate { updates.append(event) }
                    else { history.append(event) }
                }
                return TimelineDayGroup(date: date, all: dayEvents, history: history, updates: updates)
            }
            LazyVStack(spacing: 20) {
                HStack {
                    Text("阅读历史").frame(maxWidth: .infinity)
                    Color.clear.frame(width: 24, height: 1)
                    Text("发现更新").frame(maxWidth: .infinity)
                }
                .font(.caption).foregroundStyle(.secondary)
                .padding(.vertical, 4)
                .background(Color(uiColor: .systemGroupedBackground))
                ForEach(dayGroups, id: \.date) { group in
                    VStack(spacing: 12) {
                        Text(dayTitle(group.date, events: group.all))
                            .font(.caption.weight(.medium)).padding(.horizontal, 10).padding(.vertical, 4)
                            .background(Color(uiColor: .systemGroupedBackground), in: Capsule())
                        if dynamicTypeSize.isAccessibilitySize {
                            VStack(alignment: .leading, spacing: 10) {
                                ForEach(group.all, id: \.id) { event in
                                    eventRow(event, alignment: .leading, showsKind: true)
                                }
                            }.background(Color(uiColor: .systemGroupedBackground))
                        } else {
                            HStack(alignment: .top, spacing: 24) {
                                lane(group.history, alignment: .trailing)
                                lane(group.updates, alignment: .leading)
                            }
                        }
                    }
                }
            }
            .background { Rectangle().fill(Color(uiColor: .separator)).frame(width: 1) }
            .padding(.horizontal)
            .accessibilityIdentifier("records.timeline")
            .sheet(item: $selected) { item in
                NavigationStack {
                    List {
                        Section {
                            Text(item.event.content.title).font(.headline)
                            LabeledContent(item.event.isUpdate ? "发现更新时间" : "阅读日期",
                                value: ReadingStatsFormat.date(item.event.timestamp).formatted(date: .abbreviated, time: .shortened))
                            if item.event.duration > 0 {
                                LabeledContent("阅读时长", value: ReadingStatsFormat.duration(item.event.duration))
                            }
                            if !item.event.isUpdate && item.event.count > 0 {
                                LabeledContent("阅读次数", value: "\(item.event.count)")
                            }
                            Text(item.event.detail).font(.subheadline)
                        }
                        NavigationLink("打开作品") {
                            SourceDetailsView(runtime: runtime, content: item.event.content)
                        }
                    }
                    .navigationTitle(item.event.isUpdate ? "更新记录" : "阅读记录")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { selected = nil } } }
                }
                .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            }
        }
    }

    private func lane(_ events: [IosTimelineEvent], alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 10) {
            ForEach(events, id: \.id) { event in eventRow(event, alignment: alignment, showsKind: false) }
        }
        .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .trailing)
    }

    private func eventRow(_ event: IosTimelineEvent, alignment: HorizontalAlignment, showsKind: Bool) -> some View {
        Button { selected = SelectedEvent(event: event) } label: {
            VStack(alignment: alignment, spacing: 4) {
                Text(event.content.title).font(.subheadline.weight(.medium)).lineLimit(2)
                    .multilineTextAlignment(alignment == .leading ? .leading : .trailing)
                if showsKind { Text(event.isUpdate ? "发现更新" : "阅读").font(.caption).foregroundStyle(.secondary) }
                Text(event.isUpdate ? "新增 \(event.count) 章节" : event.detail)
                    .font(.caption).foregroundStyle(event.isUpdate ? Color.orange : .secondary).lineLimit(1)
                HStack(spacing: 4) {
                    Text(ReadingStatsFormat.date(event.timestamp), format: .dateTime.hour().minute())
                    if event.duration > 0 { Text("· " + ReadingStatsFormat.duration(event.duration)) }
                }.font(.caption2).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, minHeight: 56, alignment: alignment == .leading ? .leading : .trailing)
            .padding(.vertical, 3).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("timeline.\(event.id)")
    }

    private func dayTitle(_ date: String, events: [IosTimelineEvent]) -> String {
        guard let event = events.first else { return date }
        let title = ReadingStatsFormat.dayTitle(event.timestamp)
        return title == "今天" || title == "昨天" ? title + " · " + date : date
    }
}
