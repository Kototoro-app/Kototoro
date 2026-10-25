import KototoroShared
import SwiftUI

/// Android's GitHub mirror choice (same key and ids) applied to extension downloads.
enum GitHubMirror {
    static let key = "github_mirror"

    static var selected: String { UserDefaults.standard.string(forKey: key) ?? IosGitHubMirror.shared.nativeId }

    static func rewrite(_ url: URL) -> URL {
        URL(string: IosGitHubMirror.shared.rewrite(url: url.absoluteString, id: selected)) ?? url
    }
}

/// The mirror picker with Android's reachability test: each mirror fetches the same index, fastest marked.
struct GitHubMirrorView: View {
    @AppStorage(GitHubMirror.key) private var selected = IosGitHubMirror.shared.nativeId
    @State private var results: [String: Int?] = [:]
    @State private var probing = false
    private let mirrors = IosGitHubMirror.shared.mirrors

    private var fastest: String? {
        results.compactMap { id, value in value.map { (id, $0) } }.min { $0.1 < $1.1 }?.0
    }

    var body: some View {
        Form {
            Section {
                ForEach(mirrors, id: \.id) { mirror in
                    Button { selected = mirror.id } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(mirror.id == IosGitHubMirror.shared.nativeId ? String(localized: "直连（默认）") : mirror.name)
                                    .foregroundStyle(.primary)
                                if let result = results[mirror.id] {
                                    Text(result.map { "\($0) ms" + (mirror.id == fastest ? String(localized: " · 最快") : "") }
                                         ?? String(localized: "超时"))
                                        .font(.caption).foregroundStyle(result == nil ? Color.red : .secondary)
                                }
                            }
                            Spacer()
                            if selected == mirror.id { Image(systemName: "checkmark").foregroundStyle(.tint) }
                        }
                    }
                    .accessibilityAddTraits(selected == mirror.id ? .isSelected : [])
                    .accessibilityIdentifier("mirror.\(mirror.id)")
                }
            } footer: {
                Text("用于下载扩展安装包。仓库索引地址保持直连，以免相对路径解析到镜像站。")
            }
            Section {
                Button(probing ? "测速中…" : "测速", systemImage: "speedometer") { Task { await probe() } }
                    .disabled(probing)
                    .accessibilityIdentifier("mirror.probe")
                if let fastest, fastest != selected {
                    Button("使用最快的镜像") { selected = fastest }
                }
            }
        }
        .navigationTitle("GitHub 镜像")
    }

    /// Fetches the probe index through every mirror at once, 6 s each; the headers arriving counts as reachable.
    private func probe() async {
        probing = true
        results = [:]
        let base = IosGitHubMirror.shared.probeUrl
        await withTaskGroup(of: (String, Int?).self) { group in
            for mirror in mirrors {
                let address = IosGitHubMirror.shared.rewrite(url: base, id: mirror.id)
                group.addTask {
                    guard let url = URL(string: address) else { return (mirror.id, nil) }
                    var request = URLRequest(url: url)
                    request.timeoutInterval = 6
                    request.cachePolicy = .reloadIgnoringLocalCacheData
                    let start = Date()
                    guard let (_, response) = try? await URLSession.shared.bytes(for: request),
                          let http = response as? HTTPURLResponse, (200..<400).contains(http.statusCode) else {
                        return (mirror.id, nil)
                    }
                    return (mirror.id, Int(Date().timeIntervalSince(start) * 1000))
                }
            }
            for await (id, value) in group { results[id] = value }
        }
        probing = false
    }
}
