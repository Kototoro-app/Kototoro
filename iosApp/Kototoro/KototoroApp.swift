import SwiftUI

@main
struct KototoroApp: App {
    @StateObject private var library = LibraryModel()

    init() {
        ReaderHitchMetrics.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView(library: library)
                .scrollEdgeEffectStyle(.soft, for: .top)
                .modifier(AppLockGate())
                .task { await library.load() }
        }
        .backgroundTask(.appRefresh(BackgroundUpdates.identifier)) { await BackgroundUpdates.shared.run() }
    }
}
