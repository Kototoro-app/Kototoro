import SwiftUI
import UniformTypeIdentifiers
import KototoroShared

/// Library backup in the Android/Kotatsu ZIP format. Restore merges; it never clears local records.
struct BackupView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @State private var importing = false
    @State private var exportFile: BackupFile?
    @State private var exporting = false
    @State private var preview: IosBackupPreview?
    /// Another app's backup (Mihon/Tachiyomi, Aniyomi, Venera), imported with Android's matching rules.
    @State private var external: IosExternalBackupPreview?
    @State private var report: IosExternalImportResult?
    @State private var busy = false
    @State private var message: String?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                Button("导出备份", systemImage: "square.and.arrow.up") { Task { await export() } }
                    .disabled(busy || library.library == nil)
                    .accessibilityIdentifier("backup.export")
                    .fileExporter(isPresented: $exporting, document: exportFile, contentType: .zip,
                                  defaultFilename: exportFile?.url.deletingPathExtension().lastPathComponent) { result in
                        switch result {
                        case .success: message = String(localized: "备份已保存")
                        case .failure(let failure as CocoaError) where failure.code == .userCancelled: break
                        case .failure(let failure): error = failure.localizedDescription
                        }
                        if let url = exportFile?.url { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
                        exportFile = nil
                    }
                Button("从文件恢复", systemImage: "square.and.arrow.down") { importing = true }
                    .disabled(busy || library.library == nil)
                    .accessibilityIdentifier("backup.import")
                    .fileImporter(isPresented: $importing, allowedContentTypes: [.zip, .data]) { result in
                        switch result {
                        case .success(let url): Task { await open(url) }
                        case .failure(let failure as CocoaError) where failure.code == .userCancelled: break
                        case .failure(let failure): error = failure.localizedDescription
                        }
                    }
            } footer: {
                Text(String(localized: "包含收藏分类、收藏、历史与阅读进度、书签、阅读统计和来源排序，与 Android 版的备份文件互通。")
                     + String(localized: "也可以导入 Mihon/Tachiyomi、Aniyomi（.tachibk）和 Venera 的备份。恢复为合并方式，不会清空现有记录。"))
            }
            if let message { Text(message).accessibilityIdentifier("backup.message") }
            if let error { Text(error).foregroundStyle(.red).accessibilityIdentifier("backup.error") }
            if let preview {
                Section {
                    Button("合并恢复", systemImage: "arrow.triangle.merge") { Task { await restore(preview) } }
                        .disabled(busy)
                        .accessibilityIdentifier("backup.restore")
                    Button("取消", role: .cancel) { self.preview = nil }.disabled(busy)
                }
                Section("备份内容") {
                    LabeledContent("文件", value: preview.filename)
                    LabeledContent("创建时间", value: Date(timeIntervalSince1970: Double(preview.createdAt) / 1000)
                        .formatted(date: .abbreviated, time: .shortened))
                    ForEach(preview.counts, id: \.section) { item in
                        LabeledContent(Self.title(item.section), value: "\(item.count)")
                            .accessibilityIdentifier("backup.count.\(item.section)")
                    }
                    if !preview.skippedSections.isEmpty {
                        Text("iOS 暂不恢复：\(preview.skippedSections.map(Self.title).joined(separator: "、"))")
                            .font(.caption).foregroundStyle(.secondary)
                            .accessibilityIdentifier("backup.skipped")
                    }
                }
            }
            if let external { externalSection(external) }
            if let report { reportSection(report) }
            if busy { ProgressView().frame(maxWidth: .infinity) }
        }
        .navigationTitle("备份与恢复")
    }

    private func export() async {
        guard let database = library.library else { return }
        busy = true; error = nil; message = nil
        defer { busy = false }
        do {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = "yyyyMMdd-HHmmss"
            let stamp = formatter.string(from: Date())
            let url = directory.appendingPathComponent("kototoro_\(stamp).bk.zip")
            try await database.exportBackup(path: url.path)
            exportFile = BackupFile(url: url)
            exporting = true
        } catch { self.error = error.localizedDescription }
    }

    private func open(_ url: URL) async {
        guard let database = library.library else { return }
        busy = true; error = nil; message = nil; preview = nil; external = nil; report = nil
        defer { busy = false }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            // One entry for every format: Kototoro/Kotatsu archives restore, other apps' backups import.
            if database.isKototoroBackup(path: url.path) {
                preview = try await database.previewBackup(path: url.path)
            } else {
                external = try await database.previewExternalBackup(path: url.path)
            }
        } catch { self.error = error.localizedDescription }
    }

    private func restore(_ archive: IosBackupPreview) async {
        guard let database = library.library else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            let restored = try await database.restoreBackup(preview: archive)
            preview = nil
            message = String(localized: "已合并 \(restored) 条记录")
            await library.refresh()
        } catch { self.error = error.localizedDescription }
    }

    @ViewBuilder
    private func externalSection(_ backup: IosExternalBackupPreview) -> some View {
        Section {
            Button("导入到作品库", systemImage: "square.and.arrow.down.on.square") { Task { await importExternal(backup) } }
                .disabled(busy)
                .accessibilityIdentifier("backup.importExternal")
            Button("取消", role: .cancel) { external = nil }.disabled(busy)
        }
        Section {
            LabeledContent("文件", value: backup.filename)
            LabeledContent("格式", value: backup.app).accessibilityIdentifier("backup.externalApp")
            LabeledContent("收藏", value: "\(backup.favourites)").accessibilityIdentifier("backup.externalFavourites")
            LabeledContent("历史", value: "\(backup.history)")
            LabeledContent("收藏分类", value: "\(backup.categories)")
            LabeledContent("来源", value: "\(backup.sources)")
        } header: {
            Text("\(backup.app) 备份")
        } footer: {
            Text("按 Android 版的规则导入：扩展来源保持原有标识，未安装的扩展在安装后即可继续阅读；Venera 的来源按名称匹配已有来源。")
        }
    }

    @ViewBuilder
    private func reportSection(_ result: IosExternalImportResult) -> some View {
        Section("导入结果") {
            LabeledContent("收藏", value: "\(result.favourites)")
            LabeledContent("历史", value: "\(result.history)")
            if !result.uninstalledSources.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("以下扩展尚未安装，作品已导入，安装扩展后即可打开：")
                    Text(result.uninstalledSources.joined(separator: "、")).foregroundStyle(.secondary)
                }
                .font(.footnote)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("backup.uninstalled")
            }
            if !result.missingSources.isEmpty {
                Text("需要的来源未安装：\(result.missingSources.joined(separator: "、"))").font(.footnote)
            }
            if !result.failedTitles.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(result.failedTitles.count) 部作品找不到对应来源，未导入：")
                    Text(result.failedTitles.prefix(20).joined(separator: "、")).foregroundStyle(.secondary)
                }
                .font(.footnote)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("backup.failed")
            }
        }
    }

    private func importExternal(_ backup: IosExternalBackupPreview) async {
        guard let database = library.library else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            report = try await database.importExternalBackup(preview: backup, sources: sources.sources)
            external = nil
            message = String(localized: "已导入 \(backup.app) 备份")
            await library.refresh()
        } catch { self.error = error.localizedDescription }
    }

    static func title(_ section: String) -> String {
        switch section {
        case "categories": String(localized: "收藏分类")
        case "favourites", "work_favourites": String(localized: "收藏")
        case "history", "work_history": String(localized: "历史")
        case "bookmarks": String(localized: "书签")
        case "statistics", "work_stats": String(localized: "阅读统计")
        case "sources": String(localized: "来源排序")
        case "projections": String(localized: "作品")
        case "settings": String(localized: "设置")
        case "reader_grid": String(localized: "阅读器点击区域")
        case "scrobbling": String(localized: "追踪账号")
        case "tracks", "track_logs": String(localized: "更新检测")
        case "saved_filters": String(localized: "保存的筛选")
        case "source_origins": String(localized: "来源分组")
        case "extension_repos": String(localized: "扩展仓库")
        case "auth": String(localized: "登录信息")
        default: section
        }
    }
}

/// Hands the already written archive to the system exporter without copying it into memory.
struct BackupFile: FileDocument {
    static let readableContentTypes: [UTType] = [.zip]
    let url: URL
    init(url: URL) { self.url = url }
    init(configuration: ReadConfiguration) throws { throw CocoaError(.fileReadUnsupportedScheme) }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { try FileWrapper(url: url) }
}
