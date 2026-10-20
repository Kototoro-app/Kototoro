import Combine

/// Keeps asynchronous image/layout changes out of the native strip camera's temporary coordinate system.
@MainActor
final class ReaderStripZoomSession {
    @Published private(set) var isActive = false

    func begin() { isActive = true }

    func end() { isActive = false }

    func waitForLayout() async {
        guard isActive else { return }
        for await active in $isActive.values {
            if !active { return }
        }
    }
}
