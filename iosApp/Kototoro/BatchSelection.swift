import Foundation

/// Bulk selection changes only the visible scope, preserving choices made under other filters.
enum BatchSelection {
    static func toggling(visible: Set<String>, in selected: Set<String>) -> Set<String> {
        guard !visible.isEmpty else { return selected }
        return visible.isSubset(of: selected) ? selected.subtracting(visible) : selected.union(visible)
    }
}
