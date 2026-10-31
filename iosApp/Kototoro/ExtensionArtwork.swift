import Compression
import Foundation

/// APK artwork of an installed extension, read straight from its JAR so the browse page never waits on the embedded
/// JVM. Mirrors the host's `MihonJarRegistry.icon` and `ApkIcon.read`: the normalized entry the APK converter writes
/// first, then the launcher resource the manifest names, resolved through `resources.arsc` when it is a reference.
enum ExtensionArtwork {
    private static let entry = "META-INF/kototoro/extension-icon"
    private static let maxBytes = 4 * 1024 * 1024

    /// Blocking file IO: call it off the main thread.
    static func icon(packageName: String) -> Data? {
        guard let artifact = ExtensionArtifacts.extension(packageName),
              let archive = ZipArchive(url: artifact.url) else { return nil }
        if archive.contains(entry) { return archive.data(entry, limit: maxBytes) }
        return launcherIcon(archive)
    }

    /// Repository JARs can carry the APK resources with a text manifest instead of the normalized entry.
    private static func launcherIcon(_ archive: ZipArchive) -> Data? {
        guard let manifest = archive.data("AndroidManifest.xml", limit: maxBytes).map({ String(decoding: $0, as: UTF8.self) }),
              let pattern = try? Regex(#"<application\b[^>]*\bandroid:icon\s*=\s*["']([^"']+)["']"#),
              let match = manifest.firstMatch(of: pattern), let captured = match.output[1].substring else { return nil }
        let reference = String(captured)
        let paths: [String]
        if reference.hasPrefix("@") {
            guard let table = archive.data("resources.arsc", limit: maxBytes),
                  let parsed = try? ResourceTable([UInt8](table)) else { return nil }
            paths = parsed.paths(reference)
        } else {
            paths = [reference]
        }
        for path in paths where path.hasPrefix("res/") {
            let ext = (path as NSString).pathExtension.lowercased()
            guard ["png", "webp", "jpg", "jpeg"].contains(ext) else { continue }
            if let bytes = archive.data(path, limit: maxBytes) { return bytes }
        }
        return nil
    }
}

/// An installed extension or parser plugin JAR; [sha256] names this exact build, so caches derived from it are keyed
/// by it and an update starts fresh.
struct ExtensionArtifact: Equatable {
    let url: URL
    let sha256: String
}

/// The host's install record read without the JVM: `installed.json` over the bundled seed, as the host loads them.
/// Parsed once and re-read only when the install record changes.
enum ExtensionArtifacts {
    private struct Records {
        var stamp: Date?
        var extensions: [String: ExtensionArtifact] = [:]
        var plugins: [String: ExtensionArtifact] = [:]
    }
    private static let lock = NSLock()
    nonisolated(unsafe) private static var records: Records?

    static func `extension`(_ packageName: String) -> ExtensionArtifact? { current().extensions[packageName] }
    static func plugin(_ id: String) -> ExtensionArtifact? { current().plugins[id] }

    private static func current() -> Records {
        let data = try? NativeSourceTransport.dataDirectory
        let record = data?.appendingPathComponent("installed.json")
        let stamp = record.flatMap { try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate }
        lock.lock()
        defer { lock.unlock() }
        if let records, records.stamp == stamp { return records }
        var next = Records(stamp: stamp)
        if let seed = Bundle.main.resourceURL?.appendingPathComponent("jvm/sources", isDirectory: true) {
            read(seed.appendingPathComponent("config.json"), into: &next) { path in
                path.hasPrefix("/") ? URL(fileURLWithPath: path) : seed.appendingPathComponent(path)
            }
        }
        if let data, let record {
            read(record, into: &next) { resolveInstalled($0, directory: data) }
        }
        records = next
        return next
    }

    private static func read(_ config: URL, into records: inout Records, resolve: (String) -> URL) {
        guard let bytes = try? Data(contentsOf: config),
              let root = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { return }
        for jar in root["jars"] as? [[String: Any]] ?? [] {
            guard let path = jar["path"] as? String, let identity = jar["identity"] as? [String: Any],
                  let packageName = identity["packageName"] as? String else { continue }
            records.extensions[packageName] = ExtensionArtifact(url: resolve(path),
                                                                sha256: identity["sha256"] as? String ?? path)
        }
        for plugin in root["plugins"] as? [[String: Any]] ?? [] {
            guard let path = plugin["path"] as? String, let id = plugin["id"] as? String else { continue }
            records.plugins[id] = ExtensionArtifact(url: resolve(path), sha256: plugin["sha256"] as? String ?? path)
        }
    }

    /// `SourceHostManagement.resolveInstalledPath`: relative to the data directory, and an absolute path left behind
    /// by a moved container falls back to the same file name under `extensions`.
    private static func resolveInstalled(_ path: String, directory: URL) -> URL {
        guard path.hasPrefix("/") else { return directory.appendingPathComponent(path).standardizedFileURL }
        let absolute = URL(fileURLWithPath: path)
        if FileManager.default.fileExists(atPath: absolute.path) { return absolute }
        let relocated = directory.appendingPathComponent("extensions").appendingPathComponent(absolute.lastPathComponent)
        return FileManager.default.fileExists(atPath: relocated.path) ? relocated : absolute
    }
}

/// Site addresses of a parser plugin's sources, read from its class files instead of constructing parsers in the
/// interpreted JVM (a `describe` there class-loads the whole plugin and its thousand-source enum just for a favicon).
///
/// Every kototoro/kotatsu parser class passes its own `*ParserSource` constant to the base constructor, and its
/// default domain is the first host-like string literal in its constant pool (`ConfigKey.Domain("…")` or the
/// template parser's domain argument). Built once per plugin build and kept under Caches; a source the scan cannot
/// place simply has no static homepage.
actor ParserSiteIndex {
    static let shared = ParserSiteIndex()
    private static let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
        .appendingPathComponent("parser-sites-v2", isDirectory: true)
    private var indexes: [String: Task<[String: String], Never>] = [:]

    func homepage(plugin id: String, source name: String) async -> String? {
        guard let artifact = ExtensionArtifacts.plugin(id) else { return nil }
        return await index(artifact)[name].map { "https://" + $0 }
    }

    /// Builds the index right after an install, so the first browse finds it on disk.
    func prepare(plugin id: String) async {
        guard let artifact = ExtensionArtifacts.plugin(id) else { return }
        _ = await index(artifact)
    }

    private func index(_ artifact: ExtensionArtifact) async -> [String: String] {
        let key = Self.safe(artifact.sha256)
        if let task = indexes[key] { return await task.value }
        let task = Task.detached(priority: .utility) { () -> [String: String] in
            let file = Self.directory?.appendingPathComponent(key + ".json")
            if let file, let bytes = try? Data(contentsOf: file),
               let cached = try? JSONDecoder().decode([String: String].self, from: bytes) { return cached }
            let built = Self.scan(artifact.url)
            if let file, let built, let bytes = try? JSONEncoder().encode(built) {
                try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? bytes.write(to: file, options: .atomic)
            }
            return built ?? [:]
        }
        indexes[key] = task
        return await task.value
    }

    private static func safe(_ value: String) -> String {
        String(value.map { $0.isLetter || $0.isNumber ? $0 : "-" }.prefix(80))
    }

    /// Nil when the JAR cannot be read, so a missing file is not cached as an empty index.
    private nonisolated static func scan(_ jar: URL) -> [String: String]? {
        guard let archive = ZipArchive(url: jar) else { return nil }
        var sites: [String: String] = [:]
        var guessed: [String: String] = [:]
        for name in archive.names where name.hasSuffix(".class") && name.contains("/site/") && !name.contains("$") {
            guard let bytes = archive.data(name, limit: 4 * 1024 * 1024),
                  let found = ParserClassFile.site([UInt8](bytes)), let domain = found.domain else { continue }
            for source in found.sources {
                if found.referenced { sites[source] = sites[source] ?? domain } else { guessed[source] = guessed[source] ?? domain }
            }
        }
        // A class that looks its source up by name only gets a source no class references directly.
        return sites.merging(guessed) { referenced, _ in referenced }
    }
}

/// Reads the constant pool of one parser class: the single `*ParserSource` enum constant it references and the
/// first literal that looks like a site host (else the host of its first URL literal). Classes referencing several
/// sources (factories, shared bases) are skipped rather than guessed. A parser that passes its source by name
/// (`ContentParserSource.valueOf("NOVELCOOL")`) references none; its upper-case literals are then the candidates.
private enum ParserClassFile {
    private static let markupNames: Set<String> = [
        "a", "abbr", "article", "aside", "b", "body", "button", "dd", "div", "dl", "dt", "em", "figure", "footer",
        "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "i", "iframe", "img", "input", "label", "li", "link",
        "main", "meta", "nav", "noscript", "ol", "option", "p", "picture", "script", "section", "select", "small",
        "source", "span", "strong", "table", "tbody", "td", "th", "thead", "time", "tr", "u", "ul", "video",
        "window", "document", "location", "this", "self",
    ]
    private static let fileSuffixes: Set<String> = [
        "php", "html", "htm", "js", "json", "css", "xml", "jpg", "jpeg", "png", "webp", "gif", "svg", "txt", "asp",
        "aspx", "jsp", "do", "cgi", "content", "title", "replace", "thumb", "card", "duration", "name", "text", "href",
        "src", "value", "id", "class", "item", "list", "page", "date", "info",
    ]

    static func site(_ bytes: [UInt8]) -> (sources: [String], referenced: Bool, domain: String?)? {
        guard bytes.count > 10, bytes.u32be(0) == 0xCAFE_BABE else { return nil }
        let count = bytes.u16be(8)
        var utf8: [Int: Range<Int>] = [:]
        var classes: [Int: Int] = [:]
        var strings: [Int] = []
        var fields: [(owner: Int, nameAndType: Int)] = []
        var nameAndTypes: [Int: Int] = [:]
        var at = 10
        var index = 1
        while index < count {
            guard at < bytes.count else { return nil }
            let tag = bytes[at]
            switch tag {
            case 1:
                guard at + 3 <= bytes.count else { return nil }
                let length = bytes.u16be(at + 1)
                guard at + 3 + length <= bytes.count else { return nil }
                utf8[index] = (at + 3)..<(at + 3 + length)
                at += 3 + length
            case 7: classes[index] = bytes.u16be(at + 1); at += 3
            case 8: strings.append(bytes.u16be(at + 1)); at += 3
            case 16, 19, 20: at += 3
            case 3, 4: at += 5
            case 5, 6: at += 9; index += 1
            case 9:
                fields.append((bytes.u16be(at + 1), bytes.u16be(at + 3))); at += 5
            case 12: nameAndTypes[index] = bytes.u16be(at + 1); at += 5
            case 10, 11, 17, 18: at += 5
            case 15: at += 4
            default: return nil
            }
            index += 1
        }
        func text(_ index: Int) -> String? {
            utf8[index].map { String(decoding: bytes[$0], as: UTF8.self) }
        }
        var sources = Set<String>()
        for field in fields {
            guard let owner = classes[field.owner].flatMap(text), owner.hasSuffix("ParserSource"),
                  let name = nameAndTypes[field.nameAndType].flatMap(text) else { continue }
            sources.insert(name)
            if sources.count > 1 { return nil }
        }
        let literals = strings.compactMap(text)
        let domain = literals.first(where: isHost) ?? literals.lazy.compactMap(urlHost).first
        if let source = sources.first { return ([source], true, domain) }
        let named = literals.filter(isSourceName)
        return named.isEmpty ? nil : (named, false, domain)
    }

    /// `ContentParserSource` constant names: upper-case letters, digits and underscores.
    private static func isSourceName(_ value: String) -> Bool {
        value.count >= 2 && value.count <= 64 && value.first.map { ("A"..."Z").contains($0) } == true &&
            value.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) || ("0"..."9").contains($0) || $0 == "_" }
    }

    /// The host of an `http(s)://` literal, which may be a template (`…/search.html?wd={keyword}`).
    private static func urlHost(_ value: String) -> String? {
        guard let scheme = value.range(of: "://"), value.hasPrefix("http") else { return nil }
        let rest = value[scheme.upperBound...]
        let host = rest.prefix { !"/?#:".contains($0) }.lowercased()
        return isHost(host) ? host : nil
    }

    static func isHost(_ value: String) -> Bool {
        let labels = value.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count >= 2, value.count <= 253, let first = labels.first, let last = labels.last,
              !markupNames.contains(String(first)), !fileSuffixes.contains(String(last)),
              (2...12).contains(last.count), last.first?.isLetter == true else { return false }
        return labels.allSatisfy { label in
            !label.isEmpty && label.first != "-" && label.last != "-" &&
                label.unicodeScalars.allSatisfy { ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "-" }
        }
    }
}

/// Just enough of the ZIP format to pull single entries out of an extension JAR without loading the whole file.
private struct ZipArchive {
    private struct Entry { let method: Int; let compressed: Int; let size: Int; let offset: Int }
    private let handle: FileHandle
    private let entries: [String: Entry]

    init?(url: URL) {
        guard let handle = try? FileHandle(forReadingFrom: url),
              let length = try? handle.seekToEnd(), length >= 22 else { return nil }
        self.handle = handle
        // End of central directory: 22 bytes plus a comment of up to 64 KiB.
        let tailLength = Int(min(length, 22 + 65_535))
        guard let tail = Self.read(handle, at: Int(length) - tailLength, count: tailLength),
              let eocd = stride(from: tail.count - 22, through: 0, by: -1).first(where: { tail.u32($0) == 0x0605_4b50 })
        else { return nil }
        let count = tail.u16(eocd + 10), directorySize = tail.u32(eocd + 12), directoryOffset = tail.u32(eocd + 16)
        // ZIP64 archives are not produced by the converter or the extension repositories.
        guard count != 0xffff, directorySize != 0xffff_ffff, directoryOffset != 0xffff_ffff,
              let directory = Self.read(handle, at: directoryOffset, count: directorySize) else { return nil }
        var entries: [String: Entry] = [:]
        var at = 0
        for _ in 0..<count {
            guard at + 46 <= directory.count, directory.u32(at) == 0x0201_4b50 else { return nil }
            let nameLength = directory.u16(at + 28)
            let next = at + 46 + nameLength + directory.u16(at + 30) + directory.u16(at + 32)
            guard next <= directory.count else { return nil }
            let name = String(decoding: directory[(at + 46)..<(at + 46 + nameLength)], as: UTF8.self)
            entries[name] = Entry(method: directory.u16(at + 10), compressed: directory.u32(at + 20),
                                  size: directory.u32(at + 24), offset: directory.u32(at + 42))
            at = next
        }
        self.entries = entries
    }

    func contains(_ name: String) -> Bool { entries[name] != nil }

    var names: some Collection<String> { entries.keys }

    /// Stored or deflated entries no larger than [limit]; anything else reads as missing.
    func data(_ name: String, limit: Int) -> Data? {
        guard let entry = entries[name], entry.size <= limit, entry.compressed <= limit,
              let header = Self.read(handle, at: entry.offset, count: 30), header.u32(0) == 0x0403_4b50,
              let body = Self.read(handle, at: entry.offset + 30 + header.u16(26) + header.u16(28),
                                   count: entry.compressed) else { return nil }
        switch entry.method {
        case 0:
            return body.count == entry.size ? Data(body) : nil
        case 8:
            if entry.size == 0 { return Data() }
            var output = [UInt8](repeating: 0, count: entry.size)
            // COMPRESSION_ZLIB is raw DEFLATE (RFC 1951), which is what ZIP stores.
            let written = body.withUnsafeBufferPointer { source in
                output.withUnsafeMutableBufferPointer { target in
                    compression_decode_buffer(target.baseAddress!, entry.size, source.baseAddress!, source.count,
                                              nil, COMPRESSION_ZLIB)
                }
            }
            return written == entry.size ? Data(output) : nil
        default:
            return nil
        }
    }

    private static func read(_ handle: FileHandle, at offset: Int, count: Int) -> [UInt8]? {
        guard offset >= 0, count >= 0, (try? handle.seek(toOffset: UInt64(offset))) != nil,
              let data = try? handle.read(upToCount: count), data.count == count else { return count == 0 ? [] : nil }
        return [UInt8](data)
    }
}

private extension [UInt8] {
    func u16(_ at: Int) -> Int { Int(self[at]) | Int(self[at + 1]) << 8 }
    func u32(_ at: Int) -> Int { u16(at) | u16(at + 2) << 16 }
    /// Class files are big-endian; out-of-range reads give 0 so a truncated pool fails the tag check instead of trapping.
    func u16be(_ at: Int) -> Int { at >= 0 && at + 2 <= count ? Int(self[at]) << 8 | Int(self[at + 1]) : 0 }
    func u32be(_ at: Int) -> Int { u16be(at) << 16 | u16be(at + 2) }
}

/// Port of `ApkIcon.Table`: resolves a resource reference to its file paths, highest density first.
private struct ResourceTable {
    private struct Malformed: Error {}
    private struct Value { let density: Int; let type: Int; let data: Int }
    private let bytes: [UInt8]
    private var strings: [String] = []
    private var values: [Int: [Value]] = [:]
    private var names: [String: Int] = [:]

    init(_ bytes: [UInt8]) throws {
        self.bytes = bytes
        guard try u16(0) == 0x0002 else { throw Malformed() }
        var pool: [String] = []
        for at in try chunks(0) {
            switch try u16(at) {
            case 0x0001: pool = try readStrings(at)
            case 0x0200: try readPackage(at)
            default: break
            }
        }
        strings = pool
    }

    func paths(_ reference: String) -> [String] {
        let id: Int?
        if reference.hasPrefix("@0x") {
            id = Int(reference.dropFirst(3), radix: 16)
        } else {
            let name = reference.dropFirst()
            id = names[name.firstIndex(of: ":").map { String(name[name.index(after: $0)...]) } ?? String(name)]
        }
        return id.map { paths($0, seen: []) } ?? []
    }

    private func paths(_ id: Int, seen: Set<Int>) -> [String] {
        guard !seen.contains(id), seen.count < 16 else { return [] }
        return (values[id] ?? []).sorted { $0.density > $1.density }.flatMap { value -> [String] in
            switch value.type {
            case 0x03: value.data < strings.count ? [strings[value.data]] : []
            case 0x01: paths(value.data, seen: seen.union([id]))
            default: []
            }
        }
    }

    private mutating func readPackage(_ at: Int) throws {
        let packageId = try u32(at + 8)
        let typeOffset = try u16(at + 2) >= 288 ? u32(at + 284) : 0
        let types = try readStrings(at + u32(at + 268))
        let keys = try readStrings(at + u32(at + 276))
        for type in try chunks(at) {
            guard try u16(type) == 0x0201 else { continue }
            let typeId = try byte(type + 8) + typeOffset
            let flags = try byte(type + 9)
            let count = try u32(type + 12)
            let offsets = try type + u16(type + 2)
            let entries = try type + u32(type + 16)
            let end = try type + u32(type + 4)
            let density = try u16(type + 2) >= 36 ? u16(type + 34) : 0
            guard count <= 65_536 else { throw Malformed() }
            for index in 0..<count {
                let entryIndex: Int
                let offset: Int
                if flags & 1 != 0 {
                    guard offsets + index * 4 + 4 <= entries else { throw Malformed() }
                    entryIndex = try u16(offsets + index * 4)
                    offset = try u16(offsets + index * 4 + 2) * 4
                } else if flags & 2 != 0 {
                    guard offsets + index * 2 + 2 <= entries else { throw Malformed() }
                    entryIndex = index
                    let value = try u16(offsets + index * 2)
                    offset = value == 0xffff ? -1 : value * 4
                } else {
                    guard offsets + index * 4 + 4 <= entries else { throw Malformed() }
                    entryIndex = index
                    let value = try u32(offsets + index * 4)
                    offset = value == 0xffff_ffff ? -1 : value
                }
                if offset == -1 { continue }
                let entry = entries + offset
                guard entry + 8 <= end else { throw Malformed() }
                let entryFlags = try u16(entry + 2)
                if entryFlags & 1 != 0 { continue }
                let compact = entryFlags & 8 != 0
                let value = try entry + u16(entry)
                if !compact, value + 8 > end { throw Malformed() }
                let dataType = try compact ? entryFlags >> 8 : byte(value + 3)
                let data = try u32(compact ? entry + 4 : value + 4)
                let id = packageId << 24 | typeId << 16 | entryIndex
                let key = try compact ? u16(entry) : u32(entry + 4)
                let typeIndex = typeId - typeOffset - 1
                if types.indices.contains(typeIndex), keys.indices.contains(key) {
                    names["\(types[typeIndex])/\(keys[key])"] = id
                }
                values[id, default: []].append(Value(density: density, type: dataType, data: data))
            }
        }
    }

    /// Offsets of the child chunks, validated like `ApkIcon.Table.chunks`.
    private func chunks(_ parent: Int) throws -> [Int] {
        let end = try parent + u32(parent + 4)
        guard end <= bytes.count, end >= parent + 8 else { throw Malformed() }
        var at = try parent + u16(parent + 2)
        guard at >= parent + 8 else { throw Malformed() }
        var children: [Int] = []
        while at + 8 <= end {
            let size = try u32(at + 4)
            let header = try u16(at + 2)
            guard size >= 8, (8...size).contains(header), at + size <= end else { throw Malformed() }
            children.append(at)
            at += size
        }
        return children
    }

    /// `ApkManifestReader.readResourceStrings`.
    private func readStrings(_ chunk: Int) throws -> [String] {
        let count = try u32(chunk + 8)
        let utf8 = try u32(chunk + 16) & 0x100 != 0
        let start = try u32(chunk + 20)
        let header = try u16(chunk + 2)
        guard count <= 1_000_000 else { throw Malformed() }
        return try (0..<count).map { index in
            let at = try chunk + start + u32(chunk + header + index * 4)
            return try utf8 ? utf8String(at) : utf16String(at)
        }
    }

    private func utf8String(_ start: Int) throws -> String {
        let prefix = try byte(start) & 0x80 != 0 ? 2 : 1
        var at = start + prefix
        let first = try byte(at)
        let length: Int
        if first & 0x80 != 0 { length = try (first & 0x7f) << 8 | byte(at + 1); at += 2 } else { length = first; at += 1 }
        guard at + length <= bytes.count else { throw Malformed() }
        return String(decoding: bytes[at..<(at + length)], as: UTF8.self)
    }

    private func utf16String(_ start: Int) throws -> String {
        var length = try u16(start)
        var at = start + 2
        if length & 0x8000 != 0 { length = try (length & 0x7fff) << 16 | u16(at); at += 2 }
        guard at + length * 2 <= bytes.count else { throw Malformed() }
        let units = (0..<length).map { UInt16(bytes[at + $0 * 2]) | UInt16(bytes[at + $0 * 2 + 1]) << 8 }
        return String(decoding: units, as: UTF16.self)
    }

    private func byte(_ at: Int) throws -> Int {
        guard at >= 0, at < bytes.count else { throw Malformed() }
        return Int(bytes[at])
    }

    private func u16(_ at: Int) throws -> Int { try byte(at) | byte(at + 1) << 8 }
    private func u32(_ at: Int) throws -> Int { try u16(at) | u16(at + 2) << 16 }
}
