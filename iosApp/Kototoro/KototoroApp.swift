import SwiftUI

@main
struct KototoroApp: App {
    @UIApplicationDelegateAdaptor private var delegate: AppDelegate
    @StateObject private var library = LibraryModel.shared

    init() {
        ReaderHitchMetrics.shared.start()
        BackgroundSync.shared.register()
        BackgroundUpdates.shared.register()
        VideoTransfers.shared.reconnect()
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

/// Episode transfers finish in the system's background sessions; the system relaunches the app to hand them over.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        VideoTransfers.shared.handleEvents(identifier, done: completionHandler)
    }
}
