import KototoroShared
import SwiftUI

/// The Settings tab: only entries, grouped by subject, as the system Settings app (and Android's settings root) do;
/// every control lives on the page an entry opens.
struct SettingsView: View {
    @ObservedObject var library: LibraryModel
    let extensionUpdates: Int
    @AppStorage("community_enabled") private var communityEnabled = false
    @State private var showingCommunityIdentity = false

    var body: some View {
        List {
            Section {
                NavigationLink { RepositoriesView() } label: {
                    SettingsEntry("仓库与扩展", symbol: "shippingbox.fill", color: .indigo)
                }
                // The tab already carries the count; the row repeats it with the system badge only.
                .badge(extensionUpdates)
                .accessibilityIdentifier("settings.extensions")
            }
            Section("阅读") {
                NavigationLink { ReaderSettingsPage() } label: { SettingsEntry("阅读器", symbol: "book.fill", color: .orange) }
                    .accessibilityIdentifier("settings.reader")
                NavigationLink { TranslationSettingsView() } label: { SettingsEntry("翻译", symbol: "translate", color: .blue) }
                    .accessibilityIdentifier("settings.translation")
                if let rules = library.library?.replaceRules {
                    NavigationLink { ReplaceRulesView(rules: rules) } label: {
                        SettingsEntry("文本替换规则", symbol: "text.badge.checkmark", color: .green)
                    }
                    .accessibilityIdentifier("settings.replaceRules")
                }
            }
            Section("外观") {
                NavigationLink { ContentSpaceSettingsPage() } label: {
                    SettingsEntry("内容空间", symbol: "square.stack.3d.up.fill", color: .purple) { ContentSpaceValue() }
                }
                .accessibilityIdentifier("settings.contentSpaces")
                NavigationLink { DetailsSettingsPage() } label: {
                    SettingsEntry("详情页", symbol: "photo.on.rectangle", color: .pink)
                }
                .accessibilityIdentifier("settings.details")
            }
            Section("数据") {
                // The queue and its settings are one page: speed and retries sit under the queue there.
                NavigationLink { ReaderDownloadsView() } label: {
                    SettingsEntry("下载", symbol: "arrow.down", color: .teal) { DownloadsValue() }
                }
                .accessibilityIdentifier("settings.downloads")
                NavigationLink { ReaderStorageView() } label: {
                    SettingsEntry("存储管理", symbol: "internaldrive.fill", color: .gray)
                }
                .accessibilityIdentifier("settings.storage")
                NavigationLink { BackupView() } label: {
                    SettingsEntry("备份与同步", symbol: "arrow.triangle.2.circlepath", color: .cyan) {
                        BackupValue(drive: library.googleDrive)
                    }
                }
                .accessibilityIdentifier("settings.backup")
                NavigationLink { TrackingSitesView() } label: { SettingsEntry("追踪网站", symbol: "link", color: .red) }
                    .accessibilityIdentifier("settings.tracking")
                if communityEnabled, library.library != nil {
                    Button { showingCommunityIdentity = true } label: {
                        SettingsEntry("社区身份", symbol: "person.fill", color: .indigo)
                    }
                    .foregroundStyle(.primary)
                    .accessibilityIdentifier("settings.communityIdentity")
                }
            }
            Section {
                NavigationLink { PrivacySettingsPage() } label: {
                    SettingsEntry("隐私与安全", symbol: "hand.raised.fill", color: .blue)
                }
                .accessibilityIdentifier("settings.privacy")
                NavigationLink { GeneralSettingsPage(library: library) } label: {
                    SettingsEntry("通用", symbol: "gearshape.fill", color: .gray)
                }
                .accessibilityIdentifier("settings.general")
                NavigationLink { AboutSettingsPage(library: library) } label: {
                    SettingsEntry("关于", symbol: "info", color: .gray)
                }
                .accessibilityIdentifier("settings.about")
            }
        }
        .navigationTitle("设置")
        .sheet(isPresented: $showingCommunityIdentity) {
            if let community = library.library?.community {
                CommunityIdentitySettings(community: community)
            }
        }
    }
}

/// A settings root row as the system Settings app draws it: a white symbol on a coloured square, the title, and an
/// optional state on the trailing side. The state only reads what is already in memory; the root never computes.
private struct SettingsEntry<Value: View>: View {
    let title: LocalizedStringKey
    let symbol: String
    let color: Color
    @ViewBuilder let value: () -> Value
    @ScaledMetric(relativeTo: .body) private var side: CGFloat = 29

    init(_ title: LocalizedStringKey, symbol: String, color: Color, @ViewBuilder value: @escaping () -> Value) {
        self.title = title
        self.symbol = symbol
        self.color = color
        self.value = value
    }

    var body: some View {
        LabeledContent {
            value()
        } label: {
            Label {
                Text(title)
            } icon: {
                Image(systemName: symbol)
                    .font(.system(size: side * 0.55, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: side, height: side)
                    .background(color.gradient, in: .rect(cornerRadius: side * 0.23, style: .continuous))
                    .accessibilityHidden(true)
            }
        }
    }
}

private extension SettingsEntry where Value == EmptyView {
    init(_ title: LocalizedStringKey, symbol: String, color: Color) {
        self.init(title, symbol: symbol, color: color) { EmptyView() }
    }
}

/// The content space in effect; nothing while spaces are off.
private struct ContentSpaceValue: View {
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var enabled = true
    var body: some View {
        if enabled { Text(ContentSpace.title(space)) }
    }
}

/// Downloads waiting or running. Its own view, so the queue's per-page progress redraws only this text.
private struct DownloadsValue: View {
    @EnvironmentObject private var queue: ReaderDownloadQueue
    var body: some View {
        let active = queue.entries.count { $0.state == .queued || $0.state == .running }
        if active > 0 { Text("\(active) 个进行中") }
    }
}

/// When the library last left the device: the later of the Google Drive sync and the WebDAV upload.
private struct BackupValue: View {
    @ObservedObject var drive: GoogleDriveSyncModel
    @AppStorage(WebDavConfig.lastUploadKey) private var webDavUpload = 0.0
    var body: some View {
        let webDav = webDavUpload > 0 ? Date(timeIntervalSince1970: webDavUpload) : nil
        if let last = [drive.lastSync, webDav].compactMap({ $0 }).max() {
            Text(last, format: .relative(presentation: .named))
        }
    }
}

/// How the readers load pages.
struct ReaderSettingsPage: View {
    var body: some View {
        Form { ReaderLoadingSettingsSection() }
            .navigationTitle("阅读器")
    }
}

struct ContentSpaceSettingsPage: View {
    var body: some View {
        Form { ContentSpaceSettingsSection() }
            .navigationTitle("内容空间")
    }
}

/// The details page's panorama cover.
struct DetailsSettingsPage: View {
    var body: some View {
        Form { PanoramaSettingsSection() }
            .navigationTitle("详情页")
    }
}

/// The app lock, incognito reading and what the lists leave out.
struct PrivacySettingsPage: View {
    var body: some View {
        Form {
            Section {
                AppLockToggle()
            } footer: {
                Text("打开应用或从后台返回时需要面容 ID、触控 ID 或设备密码。")
            }
            Section {
                ReadingIncognitoSettingsRows()
            } header: {
                Text("无痕阅读")
            } footer: {
                Text("无痕阅读时不记录历史、进度、统计与追踪；书签和下载不受影响。")
            }
            Section("内容过滤") {
                NavigationLink { TagBlacklistView() } label: { Text("标签黑名单") }
                    .badge(TagFilterStore.shared.selected.count)
                    .accessibilityIdentifier("settings.tagBlacklist")
                NavigationLink { AdultContentFilterView() } label: { Text("成人内容过滤") }
                    .accessibilityIdentifier("settings.adultFilter")
            }
        }
        .navigationTitle("隐私与安全")
    }
}

/// Background update checks and the GitHub mirror.
struct GeneralSettingsPage: View {
    @ObservedObject var library: LibraryModel
    var body: some View {
        Form {
            Section {
                BackgroundUpdateSettingsRows(library: library)
            } header: {
                Text("作品更新")
            } footer: {
                Text("在后台检查收藏作品的新章节并发送通知。检查时机由系统决定，不保证准时。")
            }
            Section {
                NavigationLink { GitHubMirrorView() } label: { Text("GitHub 镜像") }
                    .accessibilityIdentifier("settings.githubMirror")
            } header: {
                Text("网络")
            } footer: {
                Text("下载扩展时使用的 GitHub 加速地址。")
            }
        }
        .navigationTitle("通用")
    }
}

/// The app's version and what helps diagnose it.
struct AboutSettingsPage: View {
    @ObservedObject var library: LibraryModel
    @AppStorage(ReaderHitchMetrics.ratioKey) private var hitchRatio = -1.0

    var body: some View {
        Form {
            Section {
                LabeledContent("应用", value: "Kototoro")
                LabeledContent("版本", value: AppBuildInfo.current.description)
                    .accessibilityIdentifier("settings.appVersion")
                LabeledContent("构建渠道", value: AppBuildInfo.current.channel.title)
                NavigationLink { AppUpdateView() } label: {
                    Label("检查更新", systemImage: "arrow.clockwise")
                }
                .accessibilityIdentifier("settings.appUpdates")
            }
            Section {
                if let version = library.schemaVersion {
                    LabeledContent("数据库版本", value: "\(version)")
                        .accessibilityIdentifier("database.ready")
                } else {
                    Text("正在打开数据库…")
                }
                LabeledContent("滚动卡顿") {
                    if hitchRatio >= 0 {
                        Text(verbatim: hitchRatio.formatted(.number.precision(.fractionLength(1))) + " ms/s")
                    } else {
                        Text("暂无数据")
                    }
                }
                .accessibilityIdentifier("settings.scrollHitches")
            } header: {
                Text("诊断")
            } footer: {
                Text("滚动卡顿为系统统计的本机前一天每秒滚动中卡顿的毫秒数，5 以下为流畅。")
            }
        }
        .navigationTitle("关于")
    }
}
