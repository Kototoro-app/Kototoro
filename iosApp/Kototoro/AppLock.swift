import LocalAuthentication
import SwiftUI

/// Android's app protection, done with the device's own authentication (Face ID, Touch ID or the passcode) instead
/// of a separate app password, so there is no password for the app to store. The app locks on launch and whenever it
/// goes to the background, and its content is covered in the app switcher.
enum AppLock {
    static let key = "app_lock_enabled"

    static var isAvailable: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    static func authenticate(reason: String) async -> Bool {
        let context = LAContext()
        context.localizedCancelTitle = String(localized: "取消")
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}

struct AppLockGate: ViewModifier {
    @AppStorage(AppLock.key) private var enabled = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var locked = UserDefaults.standard.bool(forKey: AppLock.key)
    @State private var authenticating = false

    func body(content: Content) -> some View {
        content
            .overlay {
                if enabled && (locked || scenePhase != .active) {
                    lockScreen.transition(.opacity)
                }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .background, enabled { locked = true }
                if phase == .active, locked, enabled { Task { await unlock() } }
            }
            .onChange(of: enabled) { _, value in if !value { locked = false } }
            .task { if locked && enabled { await unlock() } }
    }

    private var lockScreen: some View {
        ZStack {
            Rectangle().fill(.regularMaterial).ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: "lock.fill").font(.system(size: 44)).foregroundStyle(.secondary)
                Text("Kototoro 已锁定").font(.title3.weight(.semibold))
                if locked {
                    Button("解锁") { Task { await unlock() } }
                        .buttonStyle(.glassProminent).controlSize(.large)
                        .disabled(authenticating)
                        .accessibilityIdentifier("appLock.unlock")
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("appLock.screen")
    }

    @MainActor
    private func unlock() async {
        guard !authenticating else { return }
        authenticating = true
        defer { authenticating = false }
        if await AppLock.authenticate(reason: String(localized: "解锁 Kototoro")) {
            withAnimation { locked = false }
        }
    }
}

/// The settings switch: turning protection on (or off) first asks for the device authentication, so nobody can lock
/// the app without being able to unlock it.
struct AppLockToggle: View {
    @AppStorage(AppLock.key) private var enabled = false
    @State private var unavailable = false

    var body: some View {
        Toggle("应用锁", isOn: Binding(get: { enabled }, set: { value in
            Task {
                guard AppLock.isAvailable else { unavailable = true; return }
                let reason = value ? String(localized: "开启应用锁") : String(localized: "关闭应用锁")
                if await AppLock.authenticate(reason: reason) { enabled = value }
            }
        }))
        .accessibilityIdentifier("settings.appLock")
        .alert("无法开启应用锁", isPresented: $unavailable) {
            Button("好") {}
        } message: {
            Text("请先在系统设置中设置面容 ID、触控 ID 或设备密码。")
        }
    }
}
