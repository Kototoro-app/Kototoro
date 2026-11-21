import KototoroShared
import SwiftUI

/// The community chart on the suggestions page. While the index is too small for any chart it lists every work
/// readers rated, so the community shows from its first ratings. The index names works by title only, so a card
/// searches the reader's own sources for it.
struct CommunityHighlightsSection: View {
    let community: IosCommunityDetails
    @State private var window = "week"
    @State private var highlights: IosCommunityHighlights?
    @State private var searching: CommunityTitle?

    var body: some View {
        Group {
            if let highlights, !(highlights.isFallback && highlights.entries.isEmpty) {
                content(highlights)
            }
        }
        .task(id: window) {
            let result = try? await community.highlights(window: window)
            guard !Task.isCancelled else { return }
            highlights = result
        }
        .sheet(item: $searching) { CommunityTitleSearch(query: $0.title) }
    }

    private func content(_ highlights: IosCommunityHighlights) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(highlights.isFallback ? "读者评分" : "社区高分").font(.headline)
                    Spacer()
                    if !highlights.isFallback {
                        Picker("时间范围", selection: $window) {
                            Text("本周").tag("week")
                            Text("本月").tag("month")
                            Text("全部").tag("all")
                        }
                        .pickerStyle(.segmented).fixedSize()
                        .accessibilityIdentifier("suggestions.community.window")
                    }
                }
                if highlights.isFallback {
                    Text("评分人数还不够上榜，这里列出读者评过分的全部作品")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal)
            if highlights.entries.isEmpty {
                Text("这段时间暂无上榜作品").font(.subheadline).foregroundStyle(.secondary).padding(.horizontal)
            } else {
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 10) {
                        ForEach(Array(highlights.entries.enumerated()), id: \.element.id) { index, entry in
                            Button { searching = CommunityTitle(title: entry.title) } label: {
                                card(entry, rank: highlights.isFallback ? nil : index + 1)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("suggestions.community.work.\(index)")
                        }
                    }
                    .padding(.horizontal)
                }
                .scrollIndicators(.hidden)
            }
        }
        .accessibilityIdentifier("suggestions.community")
    }

    private func card(_ entry: IosCommunityChartEntry, rank: Int?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                if let rank {
                    Text(verbatim: "\(rank)")
                        .font(.caption2.weight(.bold).monospacedDigit())
                        .foregroundStyle(rank <= 3 ? Color.white : Color.secondary)
                        .frame(width: 20, height: 20)
                        .background(Circle().fill(rank <= 3 ? AnyShapeStyle(Color.orange.gradient) : AnyShapeStyle(.quaternary)))
                }
                Spacer()
                Image(systemName: "star.fill").font(.caption2).foregroundStyle(.orange)
                Text(entry.score.formatted(.number.precision(.fractionLength(1))))
                    .font(.caption.weight(.semibold).monospacedDigit())
            }
            Text(verbatim: entry.title).font(.subheadline.weight(.medium)).lineLimit(2)
                .multilineTextAlignment(.leading)
            Spacer(minLength: 0)
            Text("\(entry.ratingCount) 人评分").font(.caption2).foregroundStyle(.secondary)
        }
        .padding(12)
        .frame(width: 150, height: 108, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(uiColor: .secondarySystemBackground)))
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct CommunityTitle: Identifiable {
    let title: String
    var id: String { title }
}

private struct CommunityTitleSearch: View {
    @State var query: String

    var body: some View {
        GlobalSearchView(query: $query, autoSubmit: true)
    }
}
