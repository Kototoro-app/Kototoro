import KototoroShared
import SwiftUI

/// Android's incognito reading: the global switch (`incognito`) and the adult-work choice (`incognito_nsfw`,
/// enabled / ask / disabled, ASK by default). An incognito session records no history, progress, statistics or
/// tracker updates; bookmarks and downloads stay explicit actions.
enum ReadingIncognito {
    static let globalKey = "incognito"
    static let nsfwKey = "incognito_nsfw"

    enum Choice: String, CaseIterable, Identifiable {
        case enabled = "ENABLED", ask = "ASK", disabled = "DISABLED"
        var id: String { rawValue }
        var title: String {
            switch self {
            case .enabled: String(localized: "启用")
            case .ask: String(localized: "每次询问")
            case .disabled: String(localized: "禁用")
            }
        }
    }

    static var nsfwChoice: Choice {
        get { UserDefaults.standard.string(forKey: nsfwKey).flatMap(Choice.init) ?? .ask }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: nsfwKey) }
    }

    /// What opening [content] does: Android's `DetailsInteractor.observeIncognitoMode` rule.
    static func choice(for content: IosSourceContent) -> Choice {
        if UserDefaults.standard.bool(forKey: globalKey) { return .enabled }
        return content.isNsfw ? nsfwChoice : .disabled
    }
}

/// Settings rows for both switches, shown in the content-filter section.
struct ReadingIncognitoSettingsRows: View {
    @AppStorage(ReadingIncognito.globalKey) private var global = false
    @AppStorage(ReadingIncognito.nsfwKey) private var nsfw = ReadingIncognito.Choice.ask.rawValue

    var body: some View {
        Toggle("无痕模式", isOn: $global)
            .accessibilityIdentifier("settings.incognito")
        Picker("成人内容无痕阅读", selection: $nsfw) {
            ForEach(ReadingIncognito.Choice.allCases) { Text($0.title).tag($0.rawValue) }
        }
        .disabled(global)
        .accessibilityIdentifier("settings.incognitoNsfw")
    }
}

/// Decides incognito before the reader exists, so not even its opening save is recorded; asks first when the
/// adult-work choice says so (Windows asks at the same point, Android inside its reader).
struct IncognitoGate<Reader: View>: View {
    let content: IosSourceContent
    @ViewBuilder let reader: (Bool) -> Reader
    @Environment(\.dismiss) private var dismiss
    @State private var decision: Bool?
    @State private var dontAskAgain = false
    @State private var showsNotice = false

    init(content: IosSourceContent, @ViewBuilder reader: @escaping (Bool) -> Reader) {
        self.content = content
        self.reader = reader
        let choice = ReadingIncognito.choice(for: content)
        _decision = State(initialValue: choice == .ask ? nil : choice == .enabled)
    }

    var body: some View {
        if let decision {
            reader(decision)
                .overlay(alignment: .top) {
                    if showsNotice {
                        Label("无痕模式：不记录历史、进度与统计", systemImage: "eye.slash")
                            .font(.footnote.weight(.medium))
                            .padding(.horizontal, 14).padding(.vertical, 10)
                            .glassEffect(.regular, in: .capsule)
                            .padding(.top, 64)
                            .transition(.move(edge: .top).combined(with: .opacity))
                            .accessibilityIdentifier("reader.incognitoNotice")
                    }
                }
                .task {
                    guard decision else { return }
                    withAnimation { showsNotice = true }
                    try? await Task.sleep(for: .seconds(2.5))
                    withAnimation { showsNotice = false }
                }
        } else {
            prompt
        }
    }

    private var prompt: some View {
        VStack(spacing: 20) {
            Image(systemName: "eye.slash.circle.fill").font(.system(size: 56)).foregroundStyle(.tint)
            VStack(spacing: 8) {
                Text("以无痕模式阅读？").font(.title3.weight(.semibold))
                Text("“\(content.title)”是成人内容。无痕模式不会记录阅读历史、进度、统计，也不会同步到追踪网站。")
                    .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            Toggle("不再询问", isOn: $dontAskAgain)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .accessibilityIdentifier("incognito.dontAsk")
            VStack(spacing: 10) {
                Button { choose(true) } label: { Text("无痕阅读").frame(maxWidth: .infinity) }
                    .buttonStyle(.glassProminent).controlSize(.large)
                    .accessibilityIdentifier("incognito.enable")
                Button { choose(false) } label: { Text("正常阅读").frame(maxWidth: .infinity) }
                    .buttonStyle(.glass).controlSize(.large)
                    .accessibilityIdentifier("incognito.disable")
                Button("取消") { dismiss() }
                    .padding(.top, 4)
                    .accessibilityIdentifier("incognito.cancel")
            }
        }
        .padding(28)
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("incognito.prompt")
    }

    private func choose(_ incognito: Bool) {
        if dontAskAgain { ReadingIncognito.nsfwChoice = incognito ? .enabled : .disabled }
        decision = incognito
    }
}

/// Android's adult-content filter targets (same keys): where adult works (the shared `isNsfw` rule) are hidden.
/// Sources and browsing keep their own "include adult sources" switch (Android's `no_nsfw`).
enum AdultContentFilter {
    enum Target: String, CaseIterable, Identifiable {
        case favourites = "favourites_exclude_nsfw"
        case history = "history_exclude_nsfw"
        case feed = "feed_exclude_nsfw"
        case suggestions = "suggestions_exclude_nsfw"
        case bookmarks = "bookmarks_exclude_nsfw"
        var id: String { rawValue }
        var title: String {
            switch self {
            case .favourites: String(localized: "作品库")
            case .history: String(localized: "阅读记录")
            case .feed: String(localized: "更新记录")
            case .suggestions: String(localized: "推荐")
            case .bookmarks: String(localized: "笔记与书签")
            }
        }
    }

    static func hides(_ content: IosSourceContent, in target: Target) -> Bool {
        content.isNsfw && UserDefaults.standard.bool(forKey: target.rawValue)
    }
}

/// Settings page for the targets, with the sources switch the browse pages already use.
struct AdultContentFilterView: View {
    @AppStorage("sources.showAdult") private var showAdult = false
    @AppStorage(AdultContentFilter.Target.favourites.rawValue) private var favourites = false
    @AppStorage(AdultContentFilter.Target.history.rawValue) private var history = false
    @AppStorage(AdultContentFilter.Target.feed.rawValue) private var feed = false
    @AppStorage(AdultContentFilter.Target.suggestions.rawValue) private var suggestions = false
    @AppStorage(AdultContentFilter.Target.bookmarks.rawValue) private var bookmarks = false

    var body: some View {
        Form {
            Section {
                Toggle("隐藏成人来源（浏览与搜索）", isOn: Binding(get: { !showAdult }, set: { showAdult = !$0 }))
                    .accessibilityIdentifier("adult.sources")
            }
            Section {
                Toggle(AdultContentFilter.Target.favourites.title, isOn: $favourites).accessibilityIdentifier("adult.favourites")
                Toggle(AdultContentFilter.Target.history.title, isOn: $history).accessibilityIdentifier("adult.history")
                Toggle(AdultContentFilter.Target.feed.title, isOn: $feed).accessibilityIdentifier("adult.feed")
                Toggle(AdultContentFilter.Target.suggestions.title, isOn: $suggestions).accessibilityIdentifier("adult.suggestions")
                Toggle(AdultContentFilter.Target.bookmarks.title, isOn: $bookmarks).accessibilityIdentifier("adult.bookmarks")
            } header: {
                Text("在这些地方隐藏成人作品")
            } footer: {
                Text("成人作品指成人分级或成人类型来源的作品。隐藏不会删除收藏、记录或笔记。")
            }
        }
        .navigationTitle("成人内容过滤")
    }
}
