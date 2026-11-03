import SwiftUI

/// Updates download an unsigned IPA; the system share sheet hands it to Files or the user's sideloading tool.
struct AppUpdateView: View {
    @StateObject private var model = AppUpdateModel()
    @AppStorage("app.update.channel") private var channel = AppBuildInfo.current.channel
    private let build = AppBuildInfo.current

    var body: some View {
        Form {
            Section {
                LabeledContent("当前版本", value: build.description)
                    .accessibilityIdentifier("appUpdate.currentVersion")
                Picker("更新渠道", selection: $channel) {
                    ForEach(AppUpdateChannel.allCases) { option in Text(option.title).tag(option) }
                }
                .disabled(model.downloading)
                .accessibilityIdentifier("appUpdate.channel")
                Button {
                    Task { await model.check(channel: channel) }
                } label: {
                    HStack {
                        Label("检查更新", systemImage: "arrow.clockwise")
                        if model.checking { Spacer(); ProgressView() }
                    }
                }
                .disabled(model.checking || model.downloading)
                .accessibilityIdentifier("appUpdate.check")
            }

            if let release = model.release {
                Section {
                    LabeledContent("最新版本", value: release.description)
                        .accessibilityIdentifier("appUpdate.latestVersion")
                    LabeledContent("安装包大小", value: ByteCountFormatter.string(fromByteCount: release.size,
                                                                                 countStyle: .file))
                    if let version = release.identity, version > build.version {
                        Label("发现新版本", systemImage: "arrow.up.circle.fill").foregroundStyle(.green)
                            .accessibilityIdentifier("appUpdate.available")
                    } else {
                        Label("当前版本已是最新", systemImage: "checkmark.circle")
                            .accessibilityIdentifier("appUpdate.upToDate")
                    }
                    if let file = model.downloadedFile {
                        ShareLink(item: file) { Label("保存或分享 IPA", systemImage: "square.and.arrow.up") }
                            .accessibilityIdentifier("appUpdate.share")
                    } else if model.downloading {
                        ProgressView("正在下载并校验 IPA…")
                            .accessibilityIdentifier("appUpdate.downloading")
                        Button("取消下载", role: .cancel) { model.cancelDownload() }
                            .accessibilityIdentifier("appUpdate.cancel")
                    } else {
                        Button("下载 IPA", systemImage: "arrow.down.circle") {
                            model.download(from: GitHubMirror.rewrite(release.downloadURL))
                        }
                        .accessibilityIdentifier("appUpdate.download")
                    }
                } footer: {
                    Text("下载后可保存到“文件”或分享给侧载工具，由 AltStore、SideStore 等工具完成签名安装。")
                }
                if !release.localizedDescription.isEmpty {
                    Section("更新说明") {
                        Text(release.localizedDescription).font(.callout).textSelection(.enabled)
                    }
                }
            }

            if let error = model.error {
                Section { Text(error).foregroundStyle(.red).accessibilityIdentifier("appUpdate.error") }
            }
            Section {
                Link("版本发布页", destination: channel.releasesURL)
                Link("添加到 AltStore", destination: channel.storeURL(scheme: "altstore"))
                Link("添加到 SideStore", destination: channel.storeURL(scheme: "sidestore"))
            } footer: {
                Text("添加发布源后，也可以在侧载工具中接收后续更新。")
            }
        }
        .navigationTitle("应用更新")
        .task(id: channel) { await model.check(channel: channel) }
        .onDisappear { model.cancelDownload() }
    }
}
