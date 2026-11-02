/// Show a bounded window around the current chapter while preserving the user's ordering and branch filter.
enum DetailsChapterPreview {
    static func items<Element>(_ chapters: [Element], currentID: String?, id: (Element) -> String,
                               limit: Int = 5) -> ArraySlice<Element> {
        guard limit > 0 else { return chapters[0..<0] }
        let count = min(limit, chapters.count)
        let current = currentID.flatMap { target in chapters.firstIndex { id($0) == target } } ?? 0
        let start = min(max(0, current - count / 2), chapters.count - count)
        return chapters[start..<(start + count)]
    }
}
