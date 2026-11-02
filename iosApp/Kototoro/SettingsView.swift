import KototoroShared
import SwiftUI

/// The Settings tab: only entries, grouped by subject, as the system Settings app (and Android's settings root) do;
/// every control lives on the page an entry opens.
struct SettingsView: View {
    @ObservedObject var library: LibraryModel
    let extensionUpdates: Int

    var body: some View {
        List {
            Section {
                NavigationLink { RepositoriesView() } label: {
                    Label("仓库与扩展", systemImage: "shippingbox")
                }
                // The tab already carries the count; the row repeats it with the system badge only.
                .badge(extensionUpdates)
                .accessibilityIdentifier("settings.extensions")
            }
            Section("阅读") {
                NavigationLink { ReaderSettingsPage() } label: { Label("阅读器", systemImage: "book") }
                    .accessibilityIdentifier("settings.reader")
                NavigationLink { TranslationSettingsView() } label: { Label("翻译", systemImage: "translate") }
                    .accessibilityIdentifier("settings.translation")
                if let rules = library.library?.replaceRules {
                    NavigationLink { ReplaceRulesView(rules: rules) } label: {
                        Label("文本替换规则", systemImage: "text.badge.checkmark")
                    }
                    .accessibilityIdentifier("settings.replaceRules")
                }
            }
            Section("外观") {
                NavigationLink { ContentSpaceSettingsPage() } label: {
                    Label("内容空间", systemImage: "square.stack.3d.up")
                }
                .accessibilityIdentifier("settings.contentSpaces")
                NavigationLink { DetailsSettingsPage() } label: { Label("详情页", systemImage: "photo.on.rectangle") }
                    .accessibilityIdentifier("settings.details")
            }
            Section("数据") {
                // The queue and its settings are one page: speed and retries sit under the queue there.
                NavigationLink { ReaderDownloadsView() } label: { Label("下载", systemImage: "arrow.down.circle") }
                    .accessibilityIdentifier("settings.downloads")
                NavigationLink { ReaderStorageView() } label: { Label("存储管理", systemImage: "internaldrive") }
                    .accessibilityIdentifier("settings.storage")
                NavigationLink { BackupView() } label: {
                    Label("备份与同步", systemImage: "arrow.triangle.2.circlepath.icloud")
                }
                .accessibilityIdentifier("settings.backup")
                NavigationLink { TrackingSitesView() } label: { Label("追踪网站", systemImage: "link") }
                    .accessibilityIdentifier("settings.tracking")
            }
            Section {
                NavigationLink { PrivacySettingsPage() } label: { Label("隐私与安全", systemImage: "hand.raised") }
                    .accessibilityIdentifier("settings.privacy")
                NavigationLink { GeneralSettingsPage() } label: { Label("通用", systemImage: "gearshape") }
                    .accessibilityIdentifier("settings.general")
                NavigationLink { AboutSettingsPage(library: library) } label: {
                    Label("关于", systemImage: "info.circle")
                }
                .accessibilityIdentifier("settings.about")
            }
        }
        .navigationTitle("设置")
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
    var body: some View {
        Form {
            Section {
                BackgroundUpdateSettingsRows()
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
                LabeledContent("版本", value: String(localized: "iOS 开发版"))
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
