import Foundation

enum ExtensionEcosystem: String, CaseIterable, Identifiable {
    case mihon = "MIHON", aniyomi = "ANIYOMI", tsundoku = "TSUNDOKU", jar = "JAR"
    var id: String { rawValue }
    var title: String {
        switch self {
        case .mihon: "Mihon"
        case .aniyomi: "Aniyomi"
        case .tsundoku: "Tsundoku"
        case .jar: String(localized: "解析器插件")
        }
    }
    var symbol: String {
        switch self {
        case .mihon: "book.closed"
        case .aniyomi: "play.rectangle"
        case .tsundoku: "text.book.closed"
        case .jar: "shippingbox"
        }
    }
    var description: String {
        switch self {
        case .mihon: String(localized: "漫画来源")
        case .aniyomi: String(localized: "动漫与视频来源")
        case .tsundoku: String(localized: "小说来源")
        case .jar: String(localized: "漫画、小说与视频解析器")
        }
    }
}

struct InstalledExtension: Decodable, Identifiable {
    let packageName: String
    let versionCode: Int64
    let sha256: String
    let ecosystem: String
    let bundled: Bool
    // Optional metadata keeps older embedded hosts compatible.
    var displayName: String? = nil
    var versionName: String? = nil
    var sourceNames: [String]? = nil
    var languages: [String]? = nil
    var isAdult: Bool? = nil
    /// Wire names of the provided sources (newer hosts), for per-source switches.
    var sourceIds: [String]? = nil
    var id: String { Self.identity(ecosystem: ecosystem, package: packageName) }
    static func identity(ecosystem: String, package: String) -> String { ecosystem + ":" + package }
}

struct CatalogExtension {
    let packageName: String
    let ecosystem: String
    let title: String
    let version: String
    let versionCode: Int64
    let languages: [String]
    let sourceNames: [String]
    let isAdult: Bool
    let repositoryID: String
    let repositoryTitle: String
    let iconURL: URL?
    var sourceKeys: [String] = []
    var id: String { InstalledExtension.identity(ecosystem: ecosystem, package: packageName) }
}

struct ExtensionDisplayMetadata: Codable, Equatable {
    let title: String
    let languages: [String]
    let sourceNames: [String]
    let iconURL: URL?
}

struct ExtensionSummary: Identifiable {
    enum State { case available, installed, update, installing }
    let id: String
    let packageName: String
    let ecosystem: String
    let title: String
    let version: String
    let languages: [String]
    let sourceNames: [String]
    let iconURL: URL?
    let isAdult: Bool
    let installed: InstalledExtension?
    let available: CatalogExtension?
    let state: State
    var installedVersion: String? {
        guard let installed else { return nil }
        return installed.versionName?.nilIfBlank ?? String(localized: "构建 \(installed.versionCode)")
    }
    var permitsInstallation: Bool {
        available != nil
    }
    var symbol: String { ExtensionEcosystem(rawValue: ecosystem)?.symbol ?? "shippingbox" }
    var ecosystemTitle: String { ExtensionEcosystem(rawValue: ecosystem)?.title ?? ecosystem }
    var languageDescription: String { languages.map(ExtensionLanguage.title).joined(separator: "、") }
    var isMultilingual: Bool { languages.count > 1 || languages.contains("all") || languages.contains("multi") }
    var stateTitle: String {
        switch state {
        case .available: String(localized: "安装")
        case .installed: String(localized: "已安装")
        case .update: String(localized: "更新")
        case .installing: String(localized: "安装中")
        }
    }
}

enum ExtensionScope: String, CaseIterable, Identifiable {
    case all, installed, available, updates
    var id: String { rawValue }
    var title: String {
        switch self {
        case .all: String(localized: "全部")
        case .installed: String(localized: "已安装")
        case .available: String(localized: "可安装")
        case .updates: String(localized: "更新")
        }
    }
}

struct ExtensionFilter {
    var query = ""
    var ecosystem = ""
    var languages: Set<String> = []
    var scope: ExtensionScope = .all
    var showAdult = false

    func matches(_ row: ExtensionSummary) -> Bool {
        let multilingual = row.isMultilingual
        // Language-agnostic packages ("all"/"multi": JAR parser plugins, multi-source extensions) serve every
        // language, so a language filter must not hide them; otherwise a plugin repository looks empty.
        let universal = row.languages.contains("all") || row.languages.contains("multi")
        let languageMatches = languages.isEmpty || universal || languages.contains { language in
            row.languages.contains(language) || ((language == "all" || language == "multi") && multilingual)
        }
        // Adult sources remain manageable after installation, even with discovery filtering enabled.
        guard showAdult || !row.isAdult || row.installed != nil,
              ecosystem.isEmpty || row.ecosystem == ecosystem,
              languageMatches else { return false }
        switch scope {
        case .all: break
        case .installed: if row.installed == nil { return false }
        case .available: if row.installed != nil { return false }
        case .updates: if row.state != .update { return false }
        }
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty || ([row.title, row.packageName, row.ecosystemTitle, row.languageDescription,
                                 row.available?.repositoryTitle ?? "", multilingual ? String(localized: "多语言") : ""] + row.sourceNames)
            .contains { $0.localizedCaseInsensitiveContains(text) }
    }
}

enum ExtensionLanguage {
    static func title(_ code: String) -> String {
        if code == "all" || code == "multi" { return String(localized: "多语言") }
        if code == "other" { return String(localized: "其他语言") }
        if code.isEmpty { return String(localized: "未指定语言") }
        return Locale(identifier: "zh_Hans").localizedString(forIdentifier: code) ?? code.uppercased()
    }
}

enum ExtensionIndex {
    /// Merge by ecosystem and package. Never confuse equal package names from different runtimes.
    static func summaries(installed: [InstalledExtension], available: [CatalogExtension],
                          activeID: String?, metadata: [String: ExtensionDisplayMetadata] = [:]) -> [ExtensionSummary] {
        var candidates: [String: CatalogExtension] = [:]
        for candidate in available.sorted(by: { $0.repositoryID < $1.repositoryID }) {
            if let previous = candidates[candidate.id], previous.versionCode >= candidate.versionCode { continue }
            candidates[candidate.id] = candidate
        }
        let installedByID = Dictionary(installed.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return Set(candidates.keys).union(installedByID.keys).compactMap { id -> ExtensionSummary? in
            let local = installedByID[id]
            let candidate = candidates[id]
            let cached = metadata[id]
            guard let package = local?.packageName ?? candidate?.packageName,
                  let ecosystem = local?.ecosystem ?? candidate?.ecosystem else { return nil }
            let state: ExtensionSummary.State
            if activeID == id { state = .installing }
            else if let local, let candidate, candidate.versionCode > local.versionCode { state = .update }
            else { state = local == nil ? .available : .installed }
            let sourceNames = local?.sourceNames?.nonEmpty ?? candidate?.sourceNames.nonEmpty ?? cached?.sourceNames ?? []
            let titles = [local?.displayName, candidate?.title, cached?.title].compactMap { $0 }
                .compactMap { readableTitle($0, package: package) }
            let packageStem = package.components(separatedBy: ".").last ?? package
            let title = titles.first { $0.caseInsensitiveCompare(packageStem) != .orderedSame } ??
                sourceNames.compactMap { readableTitle($0, package: package) }.first ??
                titles.first ?? packageStem
                    .replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
                    .localizedCapitalized
            return ExtensionSummary(
                id: id, packageName: package, ecosystem: ecosystem,
                title: title, version: local?.versionName?.nilIfBlank ?? candidate?.version.nilIfBlank ??
                    String(localized: "构建 \(local?.versionCode ?? 0)"),
                languages: Array(Set(local?.languages?.nonEmpty ?? candidate?.languages.nonEmpty ??
                                     cached?.languages ?? [])).sorted(),
                sourceNames: sourceNames, iconURL: candidate?.iconURL ?? cached?.iconURL,
                isAdult: local?.isAdult ?? candidate?.isAdult ?? false,
                installed: local, available: candidate, state: state
            )
        }.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    private static func readableTitle(_ text: String, package: String) -> String? {
        guard var title = text.nilIfBlank, title != package else { return nil }
        for prefix in ["Tachiyomi: ", "Mihon: ", "Aniyomi: ", "Tsundoku: "] {
            if title.hasPrefix(prefix) { title.removeFirst(prefix.count) }
        }
        if title.hasSuffix(".jar") { title.removeLast(4) }
        return title.nilIfBlank
    }
}

private extension String {
    var nilIfBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}

private extension Array {
    var nonEmpty: Self? { isEmpty ? nil : self }
}
