import KototoroShared
import SwiftUI

/// Android's download speed and retry settings (same keys, shared clamps from core-domain's `DownloadPolicy`).
/// Threads and parallel works are left out on purpose: iOS downloads one page at a time to bound decode and
/// extension memory on the interpreted JVM, so those knobs would only add risk here.
enum DownloadSettings {
    static let requestDelayKey = "downloads_request_delay"
    static let retryCountKey = "downloads_retry_count"
    static let retryDelayKey = "downloads_retry_delay"

    /// Pause before each page request, in milliseconds (0 by default, as on Windows: Android's 1.6 s only applies
    /// to sources that ask to be slowed down, which the shared source contract does not mark).
    static var requestDelayMs: Int {
        Int(IosDownloadSettings.shared.requestDelayMs(value: Int32(UserDefaults.standard.integer(forKey: requestDelayKey))))
    }

    /// Total attempts per page: Android treats the stored count as attempts, not retries on top of one try.
    static var attempts: Int {
        let stored = UserDefaults.standard.object(forKey: retryCountKey) as? Int
        return Int(IosDownloadSettings.shared.attempts(value: Int32(stored ?? Int(IosDownloadSettings.shared.retryCountDefault))))
    }

    static var retryDelayMs: Int {
        let stored = UserDefaults.standard.object(forKey: retryDelayKey) as? Int
        return Int(IosDownloadSettings.shared.retryDelayMs(value: Int32(stored ?? Int(IosDownloadSettings.shared.retryDelayDefaultMs))))
    }

    /// Runs [body] up to [attempts] times, waiting the retry delay between failures; cancellation is not retried.
    static func withRetries<T>(_ body: () async throws -> T) async throws -> T {
        let attempts = attempts
        var failure: Error?
        for attempt in 0..<attempts {
            do { return try await body() } catch is CancellationError { throw CancellationError() } catch {
                failure = error
                if attempt + 1 < attempts { try await Task.sleep(for: .milliseconds(retryDelayMs)) }
            }
        }
        throw failure ?? URLError(.unknown)
    }
}

struct DownloadSettingsView: View {
    @AppStorage(DownloadSettings.requestDelayKey) private var requestDelay = 0
    @AppStorage(DownloadSettings.retryCountKey) private var retryCount = Int(IosDownloadSettings.shared.retryCountDefault)
    @AppStorage(DownloadSettings.retryDelayKey) private var retryDelay = Int(IosDownloadSettings.shared.retryDelayDefaultMs)
    private let policy = IosDownloadSettings.shared

    var body: some View {
        Form {
            Section {
                Stepper("请求间隔 \(requestDelay) ms", value: $requestDelay, in: 0...Int(policy.requestDelayMaxMs), step: 200)
                    .accessibilityIdentifier("downloads.requestDelay")
                Stepper("每页尝试 \(retryCount) 次", value: $retryCount,
                        in: Int(policy.retryCountMin)...Int(policy.retryCountMax))
                    .accessibilityIdentifier("downloads.retryCount")
                Stepper("重试间隔 \(String(format: "%.1f", Double(retryDelay) / 1000)) 秒", value: $retryDelay,
                        in: Int(policy.retryDelayMinMs)...Int(policy.retryDelayMaxMs), step: 500)
                    .accessibilityIdentifier("downloads.retryDelay")
            } header: {
                Text("速度与重试")
            } footer: {
                Text("请求间隔可减轻来源的访问压力；失败的页面按设定次数重试。下载在 iOS 上逐页进行，以控制扩展运行时的内存，因此没有线程数与同时下载作品数设置。")
            }
        }
        .navigationTitle("下载设置")
    }
}
