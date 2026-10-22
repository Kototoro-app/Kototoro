import SwiftUI

@main
struct KototoroApp: App {
    @StateObject private var library = LibraryModel()

    var body: some Scene {
        WindowGroup {
            ContentView(library: library)
                .scrollEdgeEffectStyle(.soft, for: .top)
                .task { await library.load() }
        }
    }
}
