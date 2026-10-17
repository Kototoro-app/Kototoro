/// Derived from one favourites snapshot. Space switches and category counts never inspect the entries again.
struct LibraryCollectionIndex<Entry> {
    struct Slice {
        fileprivate(set) var entries: [Entry] = []
        fileprivate(set) var categoryCounts: [Int32: Int] = [:]
        fileprivate(set) var byCategory: [Int32: [Entry]] = [:]
    }

    let availableKinds: Set<String>
    private let all: Slice
    private let byKind: [String: Slice]

    init(entries: [Entry] = [], mediaKind: (Entry) -> String, categoryIDs: (Entry) -> [Int32]) {
        var allCounts: [Int32: Int] = [:]
        var allByCategory: [Int32: [Entry]] = [:]
        var grouped: [String: Slice] = [:]
        for entry in entries {
            let kind = mediaKind(entry)
            grouped[kind, default: Slice()].entries.append(entry)
            // One work counts once per category, even if a restored record repeats a membership.
            for category in Set(categoryIDs(entry)) {
                allCounts[category, default: 0] += 1
                allByCategory[category, default: []].append(entry)
                grouped[kind, default: Slice()].categoryCounts[category, default: 0] += 1
                grouped[kind, default: Slice()].byCategory[category, default: []].append(entry)
            }
        }
        all = Slice(entries: entries, categoryCounts: allCounts, byCategory: allByCategory)
        byKind = grouped
        availableKinds = Set(grouped.keys)
    }

    func slice(mediaKind: String?) -> Slice {
        guard let mediaKind else { return all }
        return byKind[mediaKind] ?? Slice()
    }
}
