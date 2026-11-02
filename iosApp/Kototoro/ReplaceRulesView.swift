import KototoroShared
import SwiftUI
import UniformTypeIdentifiers

/// Bumped whenever the rules change, so an open novel reader applies them again.
enum ReplaceRulesRevision {
    static let key = "replaceRules.revision"
    static func bump() { UserDefaults.standard.set(UserDefaults.standard.integer(forKey: key) + 1, forKey: key) }
}

/// Android's text replacement rules page: rules by group with their switches, an editor with a live preview, and
/// Legado JSON import/export. The rules live in the shared `replace_rules` table (as on Windows).
struct ReplaceRulesView: View {
    let rules: IosReplaceRules
    @State private var items: [Core_dbReplaceRule] = []
    @State private var editing: Core_dbReplaceRule?
    @State private var importing = false
    @State private var exported: ExportedRules?
    @State private var message: String?

    private struct ExportedRules: Identifiable {
        let id = UUID()
        let url: URL
    }

    private var groups: [(String, [Core_dbReplaceRule])] {
        Dictionary(grouping: items) { $0.group ?? "" }
            .map { ($0.key, $0.value.sorted { $0.order < $1.order }) }
            .sorted { $0.0 < $1.0 }
    }

    var body: some View {
        List {
            Section {
                Text("替换规则会在阅读小说时作用于正文：按顺序执行，可限定在某些书或来源上。")
                    .font(.footnote).foregroundStyle(.secondary)
                HStack {
                    Button("全部启用") { update { try await rules.setAllEnabled(enabled: true) } }
                        .disabled(items.allSatisfy(\.isEnabled))
                    Spacer()
                    Button("全部停用") { update { try await rules.setAllEnabled(enabled: false) } }
                        .disabled(items.allSatisfy { !$0.isEnabled })
                }
                .buttonStyle(.borderless)
            }
            if items.isEmpty {
                ContentUnavailableView("没有替换规则", systemImage: "text.badge.checkmark",
                                       description: Text("添加规则，或导入 Legado 格式的规则文件。"))
            }
            ForEach(groups, id: \.0) { group, rows in
                Section(group.isEmpty ? String(localized: "默认分组") : group) {
                    ForEach(rows, id: \.id) { rule in
                        row(rule)
                    }
                    .onDelete { offsets in
                        let ids = offsets.map { rows[$0].id }
                        update { for id in ids { _ = try await rules.delete(id: id) }; return try await rules.all() }
                    }
                }
            }
        }
        .navigationTitle("文本替换规则")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("添加", systemImage: "plus") {
                    editing = IosReplaceRules.companion.rule(id: 0, name: "", group: nil, pattern: "", replacement: "",
                        isRegex: true, isEnabled: true, scopeTitle: false, scopeContent: true, scopeFilter: nil,
                        excludeScope: nil, order: Int32((items.map(\.order).max() ?? 0) + 1), timeoutMillisecond: 3000)
                }
                .accessibilityIdentifier("replaceRules.add")
            }
            ToolbarItem(placement: .secondaryAction) {
                Button("导入", systemImage: "square.and.arrow.down") { importing = true }
            }
            ToolbarItem(placement: .secondaryAction) {
                Button("导出", systemImage: "square.and.arrow.up") { Task { await export() } }.disabled(items.isEmpty)
            }
        }
        .sheet(item: Binding(get: { editing.map(EditedRule.init) }, set: { editing = $0?.rule })) { edited in
            ReplaceRuleEditor(rule: edited.rule) { rule in
                editing = nil
                update { try await rules.save(rule: rule) }
            }
        }
        .sheet(item: $exported) { file in ShareSheet(items: [file.url]) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText]) { result in
            guard case .success(let url) = result else { return }
            Task {
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                guard let text = try? String(contentsOf: url, encoding: .utf8) else {
                    message = String(localized: "无法读取文件"); return
                }
                let count = (try? await rules.importJson(json: text))?.intValue ?? 0
                message = count > 0 ? String(localized: "已导入 \(count) 条规则") : String(localized: "文件中没有可导入的规则")
                await reload()
                ReplaceRulesRevision.bump()
            }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("好") { message = nil }
        }
        .task { await reload() }
    }

    private func row(_ rule: Core_dbReplaceRule) -> some View {
        HStack {
            Button { editing = rule } label: {
                VStack(alignment: .leading, spacing: 3) {
                    Text(rule.name.isEmpty ? rule.pattern : rule.name).foregroundStyle(.primary)
                    Text("\(rule.isRegex ? String(localized: "正则") : String(localized: "文本")) · \(rule.pattern) → \(rule.replacement)")
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .buttonStyle(.plain)
            Spacer()
            Toggle("启用", isOn: Binding(get: { rule.isEnabled }, set: { enabled in
                update { try await rules.setEnabled(rule: rule, enabled: enabled) }
            }))
            .labelsHidden()
        }
        .accessibilityIdentifier("replaceRules.row")
    }

    private func update(_ change: @escaping () async throws -> [Core_dbReplaceRule]) {
        Task {
            do { items = try await change(); ReplaceRulesRevision.bump() }
            catch { message = error.localizedDescription }
        }
    }

    private func reload() async { items = (try? await rules.all()) ?? [] }

    private func export() async {
        guard let json = try? await rules.exportJson() else { return }
        let url = FileManager.default.temporaryDirectory.appending(path: "kototoro-replace-rules.json")
        do { try json.write(to: url, atomically: true, encoding: .utf8); exported = ExportedRules(url: url) }
        catch { message = error.localizedDescription }
    }

    private struct EditedRule: Identifiable {
        let rule: Core_dbReplaceRule
        var id: Int64 { rule.id }
    }
}

/// The rule editor with Android's fields and a preview of the rule on sample text.
private struct ReplaceRuleEditor: View {
    let rule: Core_dbReplaceRule
    let onSave: (Core_dbReplaceRule) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var group = ""
    @State private var pattern = ""
    @State private var replacement = ""
    @State private var isRegex = true
    @State private var isEnabled = true
    @State private var scopeContent = true
    @State private var scopeTitle = false
    @State private var scopeFilter = ""
    @State private var excludeScope = ""
    @State private var sample = ""

    private var candidate: Core_dbReplaceRule {
        IosReplaceRules.companion.rule(id: rule.id, name: name, group: group, pattern: pattern, replacement: replacement,
            isRegex: isRegex, isEnabled: isEnabled, scopeTitle: scopeTitle, scopeContent: scopeContent,
            scopeFilter: scopeFilter, excludeScope: excludeScope, order: rule.order,
            timeoutMillisecond: rule.timeoutMillisecond)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("名称", text: $name)
                    TextField("分组", text: $group)
                }
                Section("匹配") {
                    TextField("查找", text: $pattern).font(.body.monospaced())
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("replaceRules.pattern")
                    TextField("替换为", text: $replacement).font(.body.monospaced())
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("replaceRules.replacement")
                    Toggle("正则表达式", isOn: $isRegex)
                }
                Section {
                    Toggle("正文", isOn: $scopeContent)
                    Toggle("章节标题", isOn: $scopeTitle)
                    TextField("仅用于（书名或来源，逗号分隔）", text: $scopeFilter)
                    TextField("排除（书名或来源，逗号分隔）", text: $excludeScope)
                    Toggle("启用", isOn: $isEnabled)
                } header: { Text("范围") }
                Section("预览") {
                    TextField("输入示例文本", text: $sample, axis: .vertical)
                        .accessibilityIdentifier("replaceRules.sample")
                    if !sample.isEmpty {
                        if let result = IosReplaceRules.companion.preview(rule: candidate, sample: sample) {
                            Text(result).foregroundStyle(.secondary).accessibilityIdentifier("replaceRules.preview")
                        } else {
                            Text("规则无效").foregroundStyle(.red)
                        }
                    }
                }
            }
            .navigationTitle(rule.id == 0 ? "添加规则" : "编辑规则")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { onSave(candidate) }
                        .disabled(!candidate.isValid() || !(scopeContent || scopeTitle))
                        .accessibilityIdentifier("replaceRules.save")
                }
            }
        }
        .onAppear {
            name = rule.name; group = rule.group ?? ""; pattern = rule.pattern; replacement = rule.replacement
            isRegex = rule.isRegex; isEnabled = rule.isEnabled; scopeContent = rule.scopeContent
            scopeTitle = rule.scopeTitle; scopeFilter = rule.scopeFilter ?? ""; excludeScope = rule.excludeScope ?? ""
        }
    }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
