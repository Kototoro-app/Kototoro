import SwiftUI

@main
struct KototoroApp: App {
    @StateObject private var library = LibraryModel.shared

    init() {
        ReaderHitchMetrics.shared.start()
        BackgroundSync.shared.register()
        BackgroundUpdates.shared.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView(library: library)
                .scrollEdgeEffectStyle(.soft, for: .top)
                .modifier(AppLockGate())
                .task { await library.load() }
        }
        // A refresh asked for before checks moved to a processing task: ask for that one instead.
        .backgroundTask(.appRefresh(BackgroundUpdates.legacyIdentifier)) { await BackgroundUpdates.shared.schedule() }
    }
}
