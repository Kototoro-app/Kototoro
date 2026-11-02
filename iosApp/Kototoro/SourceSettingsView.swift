import KototoroShared
import SwiftUI

/// Common browser User-Agents offered next to every source's User-Agent field (Android's source settings offer the
/// same kind of list). Sites behind Cloudflare tie their clearance to the User-Agent the challenge was passed with.
enum UserAgentPresets {
    static let all: [(title: String, value: String)] = [
        ("iPhone Safari", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1"),
        ("iPad Safari", "Mozilla/5.0 (iPad; CPU OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1"),
        ("Android Chrome", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"),
        ("Windows Chrome", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"),
        ("Windows Edge", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0"),
        ("macOS Safari", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15"),
        ("Windows Firefox", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:142.0) Gecko/20100101 Firefox/142.0"),
    ]

    static func title(for value: String) -> String? { all.first { $0.value == value }?.title }
}

/// A source's own settings, opened from its browse page: the extension's preferences or the parser's config keys
/// (domain, User-Agent, ...) as the runtime declares them, or a CMS site's API address and User-Agent; plus the
/// site in the in-app browser.
struct SourceSettingsView: View {
    let runtime: IosSources
    let source: IosSource
    var onSettingsChanged: () -> Void = {}
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.dismiss) private var dismiss
    @State private var screen: IosPreferenceScreen?
    @State private var unsupported = false
    @State private var error: String?
    @State private var notice: String?
    @State private var editing: TextEdit?
    @State private var browsing: BrowserTarget?
    @State private var homepage: String?
    @State private var site: IosVideoSiteSettings?
    @State private var updatingNodeId: String?
    @State private var loading = false
    @State private var openingBrowser = false

    private var isCms: Bool { source.kind == "video" }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("来源", value: source.title)
                    LabeledContent("类型", value: "\(source.ecosystem) · \(ContentSpace.title(source.mediaKind))")
                    if let address = isCms ? site?.api : homepage, let url = webURL(address) {
                        Button { Task { await openBrowser(url) } } label: {
                            Label("在内置浏览器中打开", systemImage: "safari")
                        }
                        .accessibilityIdentifier("sourceSettings.browser")
                        .disabled(openingBrowser || loading || updatingNodeId != nil)
                    }
                }
                if isCms { cmsSections } else { preferenceSections }
                if let error {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                        Button("重试") { Task { await load() } }.disabled(loading)
                    }
                }
            }
            .navigationTitle("来源设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }.accessibilityIdentifier("sourceSettings.done")
                }
            }
            .task { await load() }
            .refreshable { await load() }
            .sheet(item: $editing) { edit in
                TextEditSheet(edit: edit) { value in
                    if await save(edit, value) { return nil }
                    let message = notice ?? String(localized: "设置未能保存，请重试。")
                    notice = nil
                    return message
                }
            }
            .sheet(item: $browsing, onDismiss: { Task { await load() } }) { target in
                SourceWebBrowser(target: target, runtime: runtime, sourceName: source.name) { count in
                    if count > 0 { onSettingsChanged() }
                }
            }
            .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
                Button("好") { notice = nil }
            }
        }
        .accessibilityIdentifier("sourceSettings")
    }

    // MARK: Extension and parser sources

    @ViewBuilder
    private var preferenceSections: some View {
        if let screen {
            let rows = screen.nodes.filter { $0.kind != "GROUP" }
            let groups = screen.nodes.filter { $0.kind == "GROUP" }
            if !rows.isEmpty {
                Section("设置") { ForEach(rows, id: \.id) { row($0) } }
                    .disabled(updatingNodeId != nil || loading || openingBrowser)
            }
            ForEach(groups, id: \.id) { group in
                Section(group.title) { ForEach(flatten(group.children), id: \.id) { row($0) } }
                    .disabled(!group.enabled || updatingNodeId != nil || loading || openingBrowser)
            }
            if rows.isEmpty && groups.isEmpty {
                Section {
                    Text("此来源没有可调整的设置。").foregroundStyle(.secondary)
                }
            }
        } else if unsupported {
            Section {
                Text("此来源没有可调整的设置。").foregroundStyle(.secondary)
            }
        } else if error == nil {
            Section { ProgressView().frame(maxWidth: .infinity) }
        }
    }

    @ViewBuilder
    private func row(_ node: IosPreferenceNode) -> some View {
        switch node.kind {
        case "TOGGLE":
            Toggle(isOn: Binding(get: { node.isOn }, set: { on in Task { await update(node) { try await runtime.updateToggle(screen: $0, node: node, on: on) } } })) {
                titled(node)
            }
            .disabled(!node.enabled)
            .accessibilityIdentifier("sourceSettings.node.\(node.id)")
        case "CHOICE":
            Picker(selection: Binding(get: { node.selected.first ?? "" }, set: { value in
                Task { await update(node) { try await runtime.updateChoice(screen: $0, node: node, choice: value) } }
            })) {
                ForEach(Array(zip(node.choiceTitles, node.choiceValues)), id: \.1) { title, value in Text(title).tag(value) }
                if let selected = node.selected.first, !node.choiceValues.contains(selected) {
                    Text(selected).tag(selected)
                }
            } label: { titled(node) }
            .disabled(!node.enabled)
            .accessibilityIdentifier("sourceSettings.node.\(node.id)")
            if node.allowsCustomValue {
                Button("自定义\(node.title)") { editText(node) }
                    .disabled(!node.enabled)
                    .accessibilityIdentifier("sourceSettings.custom.\(node.id)")
            }
        case "MULTI_CHOICE":
            NavigationLink {
                MultiChoiceList(node: node) { values in
                    await update(node) { try await runtime.updateChoices(screen: $0, node: node, choices: values) }
                }
            } label: {
                LabeledContent { Text("\(node.selected.count) 项") } label: { titled(node) }
            }
            .disabled(!node.enabled)
        case "ACTION":
            Button {
                if let address = node.actionUrl, let url = webURL(address) {
                    Task { await openBrowser(url) }
                } else {
                    Task { await update(node) { try await runtime.triggerAction(screen: $0, node: node) } }
                }
            } label: {
                HStack {
                    titled(node)
                    Spacer()
                    if updatingNodeId == node.id {
                        ProgressView()
                    }
                }
            }
            .disabled(!node.enabled || updatingNodeId != nil || openingBrowser)
            .accessibilityIdentifier("sourceSettings.node.\(node.id)")
        case "TEXT":
            Button { editText(node) } label: {
                LabeledContent {
                    Text(textSummary(node)).lineLimit(1).truncationMode(.middle)
                } label: { titled(node) }
            }
            .foregroundStyle(.primary)
            .disabled(!node.enabled)
            .accessibilityIdentifier("sourceSettings.node.\(node.id)")
        case "INFO":
            titled(node)
        case "GROUP":
            titled(node).font(.headline)
        default:
            titled(node).foregroundStyle(.secondary)
        }
    }

    private func flatten(_ nodes: [IosPreferenceNode]) -> [IosPreferenceNode] {
        nodes.flatMap { [$0] + flatten($0.children) }
    }

    private func webURL(_ address: String) -> URL? {
        guard let url = URL(string: address), let scheme = url.scheme?.lowercased(),
              ["http", "https"].contains(scheme), url.host != nil else { return nil }
        return url
    }

    private func editText(_ node: IosPreferenceNode) {
        editing = TextEdit(id: node.id, title: node.title, summary: node.summary, value: node.text,
                           placeholder: node.defaultText, isUserAgent: node.isUserAgent, isSecret: node.isSecret)
    }

    private func openBrowser(_ url: URL) async {
        guard !openingBrowser, !loading, updatingNodeId == nil else { return }
        openingBrowser = true
        defer { openingBrowser = false }
        let session = try? await runtime.browserSession(sourceName: source.name)
        browsing = BrowserTarget(url: url, userAgent: session?.userAgent ?? currentUserAgent)
    }

    private func titled(_ node: IosPreferenceNode) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(node.title)
            if !node.summary.isEmpty && node.kind != "TEXT" {
                Text(node.summary).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func textSummary(_ node: IosPreferenceNode) -> String {
        if node.isSecret {
            return node.text.isEmpty ? String(localized: "未设置") : "••••••••"
        }
        if node.id == "auth:username" { return node.text.isEmpty ? String(localized: "未设置") : node.text }
        // Parser keys store their default as a value (a full User-Agent), extensions as an empty string.
        if node.text.isEmpty || node.text == node.defaultText { return String(localized: "默认") }
        return node.isUserAgent ? (UserAgentPresets.title(for: node.text) ?? String(localized: "自定义")) : node.text
    }

    private var currentUserAgent: String? {
        if isCms { return site?.userAgent.isEmpty == false ? site?.userAgent : nil }
        let node = flatten(screen?.nodes ?? []).first { $0.isUserAgent }
        return node.flatMap { $0.text.isEmpty ? nil : $0.text }
    }

    // MARK: CMS sites

    @ViewBuilder
    private var cmsSections: some View {
        if let site {
            Section {
                Button {
                    editing = TextEdit(id: "cms:api", title: String(localized: "API 地址"), summary: String(localized: "站点的苹果 CMS 接口地址；更换域名时修改这里。"),
                                       value: site.api, placeholder: "https://", isUserAgent: false)
                } label: {
                    LabeledContent("API 地址") { Text(site.api).lineLimit(1).truncationMode(.middle) }
                }
                .foregroundStyle(.primary)
                .accessibilityIdentifier("sourceSettings.cms.api")
                Button {
                    editing = TextEdit(id: "cms:ua", title: "User-Agent", summary: String(localized: "留空使用默认值。"),
                                       value: site.userAgent, placeholder: "", isUserAgent: true)
                } label: {
                    LabeledContent("User-Agent") {
                        Text(site.userAgent.isEmpty ? String(localized: "默认") : (UserAgentPresets.title(for: site.userAgent) ?? String(localized: "自定义")))
                    }
                }
                .foregroundStyle(.primary)
                .accessibilityIdentifier("sourceSettings.cms.ua")
            } header: { Text("网络") }
        } else if error == nil {
            Section { ProgressView().frame(maxWidth: .infinity) }
        }
    }

    // MARK: Loading and saving

    private func load() async {
        guard !loading, updatingNodeId == nil else { return }
        loading = true
        defer { loading = false }
        error = nil
        unsupported = false
        homepage = try? await runtime.homepage(sourceName: source.name)
        do {
            if isCms {
                site = try await sources.videoSiteSettings(source.name)
            } else {
                screen = try await runtime.preferences(sourceName: source.name)
            }
        } catch {
            // Sources without settings answer "unsupported"; anything else is worth showing.
            if "\(error)".contains("UNSUPPORTED") || "\(error)".localizedCaseInsensitiveContains("unsupported") {
                unsupported = true
            } else {
                self.error = SourceErrorText.describe(error)
            }
        }
    }

    private func save(_ edit: TextEdit, _ value: String) async -> Bool {
        if edit.id.hasPrefix("cms:"), let site {
            let api = edit.id == "cms:api" ? value : site.api
            let agent = edit.id == "cms:ua" ? value : site.userAgent
            do {
                try await sources.updateVideoSite(source.name, api: api, userAgent: agent)
                onSettingsChanged()
                self.site = try await sources.videoSiteSettings(source.name)
                return true
            } catch {
                notice = error.localizedDescription
                return false
            }
        }
        guard let node = flatten(screen?.nodes ?? []).first(where: { $0.id == edit.id }) else { return false }
        return await update(node) { try await runtime.updateText(screen: $0, node: node, text: value) }
    }

    @discardableResult
    private func update(_ node: IosPreferenceNode,
                        _ change: (IosPreferenceScreen) async throws -> IosPreferenceResult) async -> Bool {
        guard let screen, updatingNodeId == nil, !loading, !openingBrowser else { return false }
        updatingNodeId = node.id
        // A native listener may change another setting even when it rejects or fails the submitted edit.
        onSettingsChanged()
        error = nil
        defer { updatingNodeId = nil }
        do {
            let result = try await change(screen)
            self.screen = result.screen
            homepage = try? await runtime.homepage(sourceName: source.name)
            switch result.status {
            case "ACCEPTED": return true
            case "PERSISTENCE_FAILED": notice = String(localized: "设置未能保存，请重试。")
            default: notice = String(localized: "“\(node.title)”的值不被此来源接受。")
            }
        } catch {
            notice = SourceErrorText.describe(error)
            // A stale screen (another change in between) is reloaded rather than edited further.
            do {
                self.screen = try await runtime.preferences(sourceName: source.name)
            } catch {
                self.screen = nil
                self.error = SourceErrorText.describe(error)
            }
        }
        return false
    }
}

struct TextEdit: Identifiable {
    let id: String
    let title: String
    let summary: String
    let value: String
    let placeholder: String
    let isUserAgent: Bool
    var isSecret: Bool = false
}

/// One text setting; User-Agent fields add the presets and a reset to the source's default.
private struct TextEditSheet: View {
    let edit: TextEdit
    /// Nil means success; failures stay visible inside the presented editor.
    let save: (String) async -> String?
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if edit.isSecret {
                        SecureField(edit.placeholder.isEmpty ? edit.title : edit.placeholder, text: $text)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .accessibilityIdentifier("sourceSettings.text")
                    } else {
                        TextField(edit.placeholder.isEmpty ? edit.title : edit.placeholder, text: $text, axis: .vertical)
                            .lineLimit(1...6)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .keyboardType(.default)
                            .accessibilityIdentifier("sourceSettings.text")
                    }
                } footer: {
                    if !edit.summary.isEmpty { Text(edit.summary) }
                }
                if let error {
                    Section { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
                }
                if edit.isUserAgent {
                    Section("常用 User-Agent") {
                        ForEach(UserAgentPresets.all, id: \.title) { preset in
                            Button {
                                text = preset.value
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(preset.title).foregroundStyle(.primary)
                                        Text(preset.value).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                    Spacer()
                                    if text == preset.value { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                                }
                            }
                            .accessibilityIdentifier("sourceSettings.uaPreset.\(preset.title)")
                        }
                        Button("恢复来源默认值", role: .destructive) { text = "" }
                    }
                } else if !edit.isSecret && text != edit.placeholder {
                    // Domains and other texts: back to what the source itself uses.
                    Section { Button("恢复来源默认值", role: .destructive) { text = edit.placeholder } }
                }
            }
            .navigationTitle(edit.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(saving) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        saving = true
                        error = nil
                        Task {
                            let valueToSave = edit.isSecret ? text : text.trimmingCharacters(in: .whitespacesAndNewlines)
                            if let message = await save(valueToSave) { error = message } else { dismiss() }
                            saving = false
                        }
                    }
                    .disabled(saving)
                    .accessibilityIdentifier("sourceSettings.save")
                }
            }
            .onAppear { text = edit.value }
        }
        .presentationDetents(edit.isUserAgent ? [.large] : [.medium, .large])
        .interactiveDismissDisabled(saving)
    }
}

private struct MultiChoiceList: View {
    let node: IosPreferenceNode
    let save: (Set<String>) async -> Bool
    @State private var selected: Set<String> = []
    @State private var saving = false

    var body: some View {
        List {
            ForEach(Array(zip(node.choiceTitles, node.choiceValues)), id: \.1) { title, value in
                Button {
                    var next = selected
                    if next.contains(value) { next.remove(value) } else { next.insert(value) }
                    saving = true
                    Task {
                        if await save(next) { selected = next }
                        saving = false
                    }
                } label: {
                    HStack {
                        Text(title).foregroundStyle(.primary)
                        Spacer()
                        if selected.contains(value) { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                    }
                }
                .disabled(saving || !node.enabled)
            }
        }
        .navigationTitle(node.title)
        .onAppear { selected = node.selected }
    }
}
