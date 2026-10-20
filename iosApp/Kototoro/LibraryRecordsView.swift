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

private enum DateGroup: CaseIterable, Comparable {
    case today, yesterday, thisWeek, earlier

    var title: String {
        switch self {
        case .today: String(localized: "今天")
        case .yesterday: String(localized: "昨天")
        case .thisWeek: String(localized: "本周")
        case .earlier: String(localized: "更早")
        }
    }

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

/// History, update and download records in date-grouped layouts and a unified timeline.
struct LibraryRecordsView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var downloadQueue: ReaderDownloadQueue
    @StateObject private var downloaded = DownloadedWorksModel()
    /// 0 history, 1 updates, 2 downloads, 3 timeline.
    @State private var selection = 0
    @AppStorage("listMode.history") private var historyMode = ContentListMode.list
    @AppStorage("listMode.updates") private var updatesMode = ContentListMode.list
    @AppStorage("listMode.downloads") private var downloadsMode = ContentListMode.list
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

    /// History and the timeline read the reading statistics.
    private var showsStats: Bool { selection == 0 || selection == 3 }
    /// Updates and the timeline show what update checks found.
    private var showsUpdates: Bool { selection == 1 || selection == 3 }
    private var pageTitle: String {
        [String(localized: "历史"), String(localized: "更新"), String(localized: "下载"), String(localized: "时间轴")][selection]
    }
    private var searchPrompt: String {
        [String(localized: "搜索历史"), String(localized: "搜索更新"), String(localized: "搜索下载"),
         String(localized: "搜索时间轴")][selection]
    }
    /// Finished downloads changed: their records are read again.
    private var finishedDownloads: Int { downloadQueue.entries.filter { $0.state == .completed }.count }

    private var statsRequest: String {
        "\(library.recordsRevision)|\(range.rawValue)|\(effectiveKind)|\(query)"
    }

    private final class KindsCache {
        var revision: Int = -1
        var downloads = -1
        var snapshot: AnyObject?
        var kinds: Set<String> = []
    }
    @State private var kindsCache = KindsCache()

    private var availableKinds: Set<String> {
        if kindsCache.revision == library.recordsRevision && kindsCache.snapshot === stats.snapshot &&
            kindsCache.downloads == downloaded.works.count {
            return kindsCache.kinds
        }
        var kinds = Set<String>()
        for entry in library.history { kinds.insert(entry.content.mediaKind) }
        for update in library.updates { kinds.insert(update.content.mediaKind) }
        for work in downloaded.works { kinds.insert(work.content.mediaKind) }
        if let snapshot = stats.snapshot {
            for work in snapshot.works { kinds.insert(work.content.mediaKind) }
            for event in snapshot.timeline { kinds.insert(event.content.mediaKind) }
        }
        kindsCache.revision = library.recordsRevision
        kindsCache.snapshot = stats.snapshot
        kindsCache.downloads = downloaded.works.count
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
                    if showsStats {
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
                        switch selection {
                        case 0: history(runtime)
                        case 1: updates(runtime)
                        case 2: downloads(runtime)
                        default: timeline(runtime)
                        }
                    }
                }
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle(pageTitle)
            .navigationBarTitleDisplayMode(.inline)
            .pageSearch(text: $query, prompt: searchPrompt)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    // Longer translations fall back to icons rather than truncated titles.
                    ViewThatFits(in: .horizontal) {
                        recordsPicker(icons: false).fixedSize()
                        recordsPicker(icons: true).frame(maxWidth: 240)
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if spacesEnabled { SpaceMenu() }
                    if selection != 3 {
                        ContentListModeMenu(mode: selection == 0 ? $historyMode : selection == 1 ? $updatesMode : $downloadsMode)
                    }
                    if showsUpdates, let runtime = sources.runtime {
                        Button("检查更新", systemImage: "arrow.clockwise") { Task { await library.checkUpdates(runtime) } }
                            .disabled(library.isCheckingUpdates)
                            .accessibilityIdentifier("updates.check")
                    }
                }
            }
            .refreshable {
                if selection == 2 { await downloaded.load(queue: downloadQueue) }
                else if showsUpdates, let runtime = sources.runtime { await library.checkUpdates(runtime) }
                else {
                    await library.refresh()
                    await stats.load(library.library, range: range, kind: effectiveKind, query: query)
                    lastStatsRequest = statsRequest
                }
            }
            .task(id: statsRequest) {
                guard showsStats else { return }
                guard lastStatsRequest != statsRequest else { return }
                if !query.isEmpty {
                    do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
                }
                await stats.load(library.library, range: range, kind: effectiveKind, query: query)
                lastStatsRequest = statsRequest
            }
            .task(id: finishedDownloads) { await downloaded.load(queue: downloadQueue) }
            .onChange(of: range) { _, _ in selectedDay = nil }
            .onChange(of: effectiveKind) { _, _ in selectedDay = nil }
            .onChange(of: selection) { _, _ in
                selectedDay = nil
                if showsStats, lastStatsRequest != statsRequest {
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
                if !query.isEmpty { filteredEmpty(String(localized: "阅读记录")) }
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
                            Text(selectedDay ?? group.title)
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
                filteredEmpty(String(localized: "更新"))
            } else {
                let thresholds = DateThresholds.current()
                let grouped = Dictionary(grouping: matched) { update in
                    thresholds.group(for: update.checkedAt)
                }
                ForEach(DateGroup.allCases, id: \.self) { group in
                    if let updatesList = grouped[group], !updatesList.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(group.title)
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

    private func recordsPicker(icons: Bool) -> some View {
        Picker("记录", selection: $selection) {
            ForEach(Array(zip([String(localized: "历史"), String(localized: "更新"), String(localized: "下载"),
                               String(localized: "时间轴")],
                              ["clock", "arrow.triangle.2.circlepath", "arrow.down.circle", "timeline.selection"]).enumerated()),
                    id: \.offset) { index, item in
                if icons { Image(systemName: item.1).accessibilityLabel(item.0).tag(index) }
                else { Text(item.0).tag(index) }
            }
        }
        .pickerStyle(.segmented)
    }

    /// Works with finished downloads, by when their latest chapter finished; they open the work's details.
    @ViewBuilder
    private func downloads(_ runtime: IosSources) -> some View {
        NavigationLink { ReaderDownloadsView() } label: {
            Label("下载队列", systemImage: "arrow.down.circle")
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16).frame(minHeight: 44)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .padding(.horizontal)
        .accessibilityIdentifier("records.downloads.queue")
        if !downloaded.loaded {
            ProgressView().frame(maxWidth: .infinity).padding()
        } else if downloaded.works.isEmpty {
            ContentUnavailableView("暂无下载", systemImage: "arrow.down.circle",
                                   description: Text("下载完成的章节会按作品列在这里，可离线阅读。"))
                .padding(.top, 40)
        } else {
            let matched = downloaded.works.filter { matches($0.content) }
            if matched.isEmpty {
                filteredEmpty(String(localized: "下载"))
            } else {
                let thresholds = DateThresholds.current()
                let grouped = Dictionary(grouping: matched) {
                    thresholds.group(for: Int64($0.latest.timeIntervalSince1970 * 1000))
                }
                ForEach(DateGroup.allCases, id: \.self) { group in
                    if let works = grouped[group], !works.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(group.title)
                                .font(.headline.weight(.semibold)).foregroundStyle(.secondary)
                                .padding(.horizontal).padding(.top, 6)
                            let items = works.map {
                                ContentItem(content: $0.content, subtitle: String(localized: "已下载 \($0.chapterCount) 章"))
                            }
                            ContentCollection(runtime: runtime, items: items, mode: downloadsMode,
                                              identifierPrefix: "downloaded.") { item in
                                // Without the downloaded subset, so the details list the work's saved or fetched chapters.
                                SourceDetailsView(runtime: runtime, content: item.content.withoutChapters)
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
            RecordsTimeline(events: snapshot.timeline, downloads: downloaded.works, runtime: runtime)
        } else if stats.loading {
            ProgressView().frame(maxWidth: .infinity).padding()
        }
    }
}

/// Dates share the center line; each lane orders its own events without forcing unrelated times into pairs.
/// Reading and downloads share the left lane (what the reader did), found updates the right.
private struct RecordsTimeline: View {
    let events: [IosTimelineEvent]
    let downloads: [DownloadedWork]
    let runtime: IosSources
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var selected: TimelineItem?

    /// A reading or update event, or the chapters of one work that finished downloading on one day.
    private struct TimelineItem: Identifiable {
        enum Kind {
            case event(IosTimelineEvent)
            case download(count: Int)
        }
        let id: String
        let content: IosSourceContent
        let timestamp: Int64
        let kind: Kind

        var event: IosTimelineEvent? { if case .event(let event) = kind { event } else { nil } }
        var isUpdate: Bool { event?.isUpdate == true }
        var downloadCount: Int? { if case .download(let count) = kind { count } else { nil } }
    }
    private struct TimelineDayGroup {
        let date: String
        let all: [TimelineItem]
        let history: [TimelineItem]
        let updates: [TimelineItem]
    }

    /// One item per work and day, at the day's last finished chapter.
    private var downloadItems: [TimelineItem] {
        downloads.flatMap { work in
            Dictionary(grouping: work.completions) { ReadingStatsFormat.day($0) }.map { day, dates in
                let latest = dates.max() ?? work.latest
                return TimelineItem(id: "download.\(work.id).\(day)", content: work.content,
                                    timestamp: Int64(latest.timeIntervalSince1970 * 1000), kind: .download(count: dates.count))
            }
        }
    }

    var body: some View {
        let items = (events.map { TimelineItem(id: $0.id, content: $0.content, timestamp: $0.timestamp, kind: .event($0)) }
            + downloadItems).sorted { $0.timestamp > $1.timestamp }
        if items.isEmpty {
            ContentUnavailableView("暂无足迹", systemImage: "timeline.selection",
                description: Text("阅读、下载与发现更新的记录会按日期保留在这里。"))
                .padding(.top, 32)
        } else {
            let grouped = Dictionary(grouping: items) { ReadingStatsFormat.day(ReadingStatsFormat.date($0.timestamp)) }
            let dates = grouped.keys.sorted(by: >)
            let dayGroups: [TimelineDayGroup] = dates.compactMap { date in
                guard let dayItems = grouped[date] else { return nil }
                return TimelineDayGroup(date: date, all: dayItems, history: dayItems.filter { !$0.isUpdate },
                                        updates: dayItems.filter(\.isUpdate))
            }
            LazyVStack(spacing: 20) {
                HStack {
                    Text("历史 & 下载").frame(maxWidth: .infinity)
                    Color.clear.frame(width: 24, height: 1)
                    Text("发现更新").frame(maxWidth: .infinity)
                }
                .font(.caption).foregroundStyle(.secondary)
                .padding(.vertical, 4)
                .background(Color(uiColor: .systemGroupedBackground))
                ForEach(dayGroups, id: \.date) { group in
                    VStack(spacing: 12) {
                        Text(dayTitle(group.date, items: group.all))
                            .font(.caption.weight(.medium)).padding(.horizontal, 10).padding(.vertical, 4)
                            .background(Color(uiColor: .systemGroupedBackground), in: Capsule())
                        if dynamicTypeSize.isAccessibilitySize {
                            VStack(alignment: .leading, spacing: 10) {
                                ForEach(group.all) { item in
                                    eventRow(item, alignment: .leading, showsKind: true)
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
                            Text(item.content.title).font(.headline)
                            LabeledContent(timeTitle(item),
                                value: ReadingStatsFormat.date(item.timestamp).formatted(date: .abbreviated, time: .shortened))
                            if let event = item.event {
                                if event.duration > 0 {
                                    LabeledContent("阅读时长", value: ReadingStatsFormat.duration(event.duration))
                                }
                                if !event.isUpdate && event.count > 0 {
                                    LabeledContent("阅读次数", value: "\(event.count)")
                                }
                                Text(event.detail).font(.subheadline)
                            } else if let count = item.downloadCount {
                                LabeledContent("下载完成", value: String(localized: "\(count) 章"))
                            }
                        }
                        NavigationLink("打开作品") {
                            SourceDetailsView(runtime: runtime,
                                              content: item.downloadCount == nil ? item.content : item.content.withoutChapters)
                        }
                    }
                    .navigationTitle(recordTitle(item))
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { selected = nil } } }
                }
                .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            }
        }
    }

    private func kindTitle(_ item: TimelineItem) -> String {
        item.downloadCount != nil ? String(localized: "下载") : item.isUpdate ? String(localized: "发现更新")
            : String(localized: "阅读")
    }

    private func timeTitle(_ item: TimelineItem) -> String {
        item.downloadCount != nil ? String(localized: "下载时间") : item.isUpdate ? String(localized: "发现更新时间")
            : String(localized: "阅读时间")
    }

    private func recordTitle(_ item: TimelineItem) -> String {
        item.downloadCount != nil ? String(localized: "下载记录") : item.isUpdate ? String(localized: "更新记录")
            : String(localized: "阅读记录")
    }

    private func lane(_ items: [TimelineItem], alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 10) {
            ForEach(items) { item in eventRow(item, alignment: alignment, showsKind: false) }
        }
        .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .trailing)
    }

    private func eventRow(_ item: TimelineItem, alignment: HorizontalAlignment, showsKind: Bool) -> some View {
        Button { selected = item } label: {
            let cover = CoverImage(runtime: runtime, content: item.content)
                .frame(width: 32, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                .accessibilityHidden(true)
            HStack(alignment: .top, spacing: 8) {
                if alignment == .leading { cover }
                VStack(alignment: alignment, spacing: 4) {
                    Text(item.content.title).font(.subheadline.weight(.medium)).lineLimit(2)
                        .multilineTextAlignment(alignment == .leading ? .leading : .trailing)
                    if showsKind { Text(kindTitle(item)).font(.caption).foregroundStyle(.secondary) }
                    if let count = item.downloadCount {
                        Label("下载 \(count) 章", systemImage: "arrow.down.circle")
                            .font(.caption).foregroundStyle(.tint).lineLimit(1)
                    } else if let event = item.event {
                        Text(event.isUpdate ? String(localized: "新增 \(event.count) 章节") : event.detail)
                            .font(.caption).foregroundStyle(event.isUpdate ? Color.orange : .secondary).lineLimit(1)
                    }
                    HStack(spacing: 4) {
                        Text(ReadingStatsFormat.date(item.timestamp), format: .dateTime.hour().minute())
                        if let duration = item.event?.duration, duration > 0 {
                            Text("· " + ReadingStatsFormat.duration(duration))
                        }
                    }.font(.caption2).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : .trailing)
                if alignment == .trailing { cover }
            }
            .frame(maxWidth: .infinity, minHeight: 56, alignment: alignment == .leading ? .leading : .trailing)
            .padding(.vertical, 3).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("timeline.\(item.id)")
    }

    private func dayTitle(_ date: String, items: [TimelineItem]) -> String {
        guard let item = items.first else { return date }
        let day = ReadingStatsFormat.date(item.timestamp)
        guard Calendar.current.isDateInToday(day) || Calendar.current.isDateInYesterday(day) else { return date }
        return String(localized: "\(ReadingStatsFormat.dayTitle(item.timestamp)) · \(date)")
    }
}
