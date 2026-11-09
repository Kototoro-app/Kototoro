import Foundation
import os

/// The App Group the app and its widget share. A sideloading store re-signs both under its own team and renames the
/// group after it (`group.org.skepsun.kototoro.ios.<team>`, the team also ending the app's bundle identifier), so the
/// group actually granted is looked for instead of assumed: the groups the signing profile grants, the groups the
/// store records in the Info.plist (`ALTAppGroups`), the original name with the bundle identifier's team, and last the
/// original name.
enum AppGroup {
    static let original = "group.org.skepsun.kototoro.ios"
    private static let originalBundle = "org.skepsun.kototoro.ios"
    private static let log = Logger(subsystem: "org.skepsun.kototoro.ios", category: "AppGroup")

    /// Nil when no group is granted at all; the widget then has nothing to show.
    static let container: URL? = {
        let names = candidates()
        for name in names {
            if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: name) {
                log.info("using \(name, privacy: .public)")
                return url
            }
        }
        log.error("no group granted among \(names.joined(separator: ", "), privacy: .public)")
        return nil
    }()

    private static func candidates() -> [String] {
        var bundles = [Bundle.main]
        // The widget runs from Kototoro.app/PlugIns/KototoroWidget.appex.
        if Bundle.main.bundleURL.pathExtension == "appex",
           let app = Bundle(url: Bundle.main.bundleURL.deletingLastPathComponent().deletingLastPathComponent()) {
            bundles.append(app)
        }
        var names = bundles.flatMap(profileGroups)
        names += bundles.flatMap { $0.object(forInfoDictionaryKey: "ALTAppGroups") as? [String] ?? [] }
        for bundle in bundles {
            guard let id = bundle.bundleIdentifier, id.hasPrefix(originalBundle + ".") else { continue }
            let team = id.dropFirst(originalBundle.count + 1).prefix { $0 != "." }
            if !team.isEmpty && team != "widget" { names.append(original + "." + team) }
        }
        names.append(original)
        var seen = Set<String>()
        return names.filter { seen.insert($0).inserted }
    }

    /// The groups `embedded.mobileprovision` grants: the property list inside the signed profile.
    private static func profileGroups(_ bundle: Bundle) -> [String] {
        guard let url = bundle.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
              let plist = try? PropertyListSerialization.propertyList(
                  from: data[start.lowerBound..<end.upperBound], format: nil) as? [String: Any],
              let entitlements = plist["Entitlements"] as? [String: Any] else { return [] }
        return entitlements["com.apple.security.application-groups"] as? [String] ?? []
    }
}
