import Foundation
import Combine
import KototoroShared

@MainActor
final class LibraryModel: ObservableObject {
    let trackingAccounts = TrackingAccountsModel()
    let googleDrive = GoogleDriveSyncModel()
    @Published private(set) var categories: [IosCategory] = []
    @Published private(set) var isLoading = true
    @Published private(set) var error: String?
    @Published private(set) var schemaVersion: Int32?
    /// Shared with the browse tab once the database is open; nil until then.
    @Published private(set) var library: IosLibrary?
    @Published private(set) var favourites: [IosLibraryEntry] = [] {
        didSet {
            favouriteIndex = LibraryCollectionIndex(entries: favourites, mediaKind: { $0.content.mediaKind },
                                                     categoryIDs: { $0.categoryIds.map(\.int32Value) })
            favouriteKeys = Set(favourites.map { ContentMarks.key($0.content) })
        }
    }
    /// Rebuilt only when favourites are replaced, not when a view, progress indicator or toolbar updates.
    private(set) var favouriteIndex = LibraryCollectionIndex<IosLibraryEntry>(
        mediaKind: { $0.content.mediaKind }, categoryIDs: { $0.categoryIds.map(\.int32Value) }
    )
    private(set) var favouriteKeys: Set<String> = []
    @Published private(set) var history: [IosLibraryEntry] = []
    /// Last opened details per space, independent of the work offered for continued reading.
    @Published private(set) var spaceArtwork: [String: IosSourceContent] = [:]
    private static let spaceArtworkKey = "space.artwork.ids"

    func recordSpaceVisit(_ content: IosSourceContent) {
        let content = content.withoutChapters
        spaceArtwork[content.mediaKind] = content
        spaceArtwork[""] = content
        var ids = UserDefaults.standard.dictionary(forKey: Self.spaceArtworkKey) as? [String: String] ?? [:]
        ids[content.mediaKind] = content.id
        ids[""] = content.id
        UserDefaults.standard.set(ids, forKey: Self.spaceArtworkKey)
    }

    private func restoreSpaceArtwork() async {
        guard let library else { return }
        let ids = UserDefaults.standard.dictionary(forKey: Self.spaceArtworkKey) as? [String: String] ?? [:]
        for (kind, id) in ids where spaceArtwork[kind] == nil {
            if let content = try? await library.storedContent(id: id), spaceArtwork[kind] == nil {
                spaceArtwork[kind] = content
            }
        }
    }

    @Published private(set) var updates: [IosUpdate] = [] {
        didSet {
            var counts: [String: Int] = [:]
            for update in updates { counts[ContentMarks.key(update.content)] = Int(update.newChapters) }
            updateChapterCounts = counts
        }
    }
    private(set) var updateChapterCounts: [String: Int] = [:]
    @Published private(set) var isCheckingUpdates = false
    @Published private(set) var updateSummary: String?
    @Published private(set) var recordsRevision = 0
    @Published private(set) var missingSources: [IosMissingSource] = []

    func refreshMissingSources(installed: [String]) async {
        guard let library else { return }
        do {
            let result = try await library.recovery.missing(installedSources: installed)
            if missingSources != result {
                missingSources = result
            }
        }
        catch { self.error = error.localizedDescription }
    }

    func load() async {
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            if library == nil {
                let directory = try FileManager.default.url(
                    for: .applicationSupportDirectory,
                    in: .userDomainMask,
                    appropriateFor: nil,
                    create: true
                ).appendingPathComponent("Kototoro", isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                library = try IosLibrary(databasePath: directory.appendingPathComponent("library.db").path)
            }
            guard let library else { return }
            categories = try await library.categories()
            schemaVersion = library.schemaVersion
            try await refreshRecords()
            await restoreSpaceArtwork()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func addCategory(_ title: String) async -> Bool {
        guard let library, !isLoading else { return false }
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            try await library.addCategory(title: title)
            categories = try await library.categories()
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    func renameCategory(_ id: Int32, to title: String) async -> Bool {
        await editCategories { try await $0.renameCategory(id: id, title: title) }
    }

    func removeCategory(_ id: Int32) async -> Bool {
        await editCategories { try await $0.removeCategory(id: id) }
    }

    /// Deletes the category and unfavourites its works everywhere, including their other categories.
    func removeCategoryWithWorks(_ id: Int32) async -> Bool {
        await editCategories { try await $0.removeCategoryWithWorks(id: id) }
    }

    func removeFavourites(_ contents: [IosSourceContent]) async -> Bool {
        guard !contents.isEmpty else { return true }
        return await editCategories { try await $0.removeFavourites(contents: contents) }
    }

    func saveCategoryLayout(_ ordered: [IosCategory], visible: Set<Int32>) async -> Bool {
        await editCategories {
            try await $0.saveCategoryLayout(orderedIds: ordered.map { KotlinInt(value: $0.id) },
                                            visibleIds: visible.map { KotlinInt(value: $0) })
        }
    }

    private func editCategories(_ edit: (IosLibrary) async throws -> Void) async -> Bool {
        guard let library else { return false }
        error = nil
        do {
            try await edit(library)
            try await refreshRecords()
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    func refresh() async {
        do { try await refreshRecords() }
        catch { self.error = error.localizedDescription }
    }

    private func refreshRecords() async throws {
        guard let library else { return }
        favourites = try await library.favourites()
        history = try await library.history()
        updates = try await library.updates()
        categories = try await library.categories()
        recordsRevision += 1
    }

    func setFavourite(_ content: IosSourceContent, favourite: Bool) async throws {
        guard let library else { throw CancellationError() }
        try await library.setFavourite(content: content, favourite: favourite)
        try await refreshRecords()
    }

    func updateCategories(_ contents: [IosSourceContent], changes: [Int32: Bool]) async throws {
        guard let library else { throw CancellationError() }
        try await library.updateCategories(contents: contents,
            included: changes.filter { $0.value }.keys.map { KotlinInt(value: $0) },
            excluded: changes.filter { !$0.value }.keys.map { KotlinInt(value: $0) })
        try await refreshRecords()
    }

    func removeHistory(_ content: IosSourceContent) async {
        do { try await library?.removeHistory(content: content); try await refreshRecords() }
        catch { self.error = error.localizedDescription }
    }

    func checkUpdates(_ sources: IosSources) async {
        _ = await checkUpdatesReport(sources)
    }

    /// The check with its report, for the background refresh's notification; nil while one runs or on failure.
    func checkUpdatesReport(_ sources: IosSources) async -> IosUpdateReport? {
        guard let library, !isCheckingUpdates else { return nil }
        isCheckingUpdates = true
        defer { isCheckingUpdates = false }
        do {
            let report = try await library.checkUpdates(sources: sources)
            updateSummary = String(localized: "已检查 \(report.checked) 部，\(report.withUpdates) 部有更新，\(report.failed) 部失败")
            try await refreshRecords()
            return report
        } catch { self.error = error.localizedDescription; return nil }
    }
}
