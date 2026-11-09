import Foundation

/// When each background sync is next due, from its settings alone (no library, no Keychain), so the tests can check
/// the rules. Intervals are earliest gaps; a failure backs off before the next try.
enum BackgroundSyncSchedule {
    /// The WebDAV auto backup: [intervalHours] after the last upload, and ten minutes after a failure, as the check
    /// on returning to the app. Never uploaded is due at once; no server or interval means no background backup.
    static func webDavDue(intervalHours: Int, server: String, lastUpload: Double, lastFailure: Double) -> Date? {
        guard intervalHours > 0, !server.isEmpty else { return nil }
        let due = lastUpload > 0 ? lastUpload + Double(intervalHours) * 3600 : 0
        return Date(timeIntervalSince1970: max(due, lastFailure > 0 ? lastFailure + 600 : 0))
    }

    /// Google Drive: [intervalHours] after the last successful sync, and half an hour after the last scheduled try,
    /// so a failing sync does not run at every chance. Only a connected account syncs.
    static func driveDue(intervalHours: Int, connected: Bool, lastSync: Date?, lastAttempt: Date?) -> Date? {
        guard intervalHours > 0, connected else { return nil }
        let due = (lastSync ?? .distantPast).addingTimeInterval(Double(intervalHours) * 3600)
        return max(due, (lastAttempt ?? .distantPast).addingTimeInterval(1800))
    }

    /// The earliest begin date to ask for: the soonest due sync, at least a minute away; nil when nothing is set up.
    static func request(_ dues: [Date?], now: Date) -> Date? {
        dues.compactMap { $0 }.min().map { max($0, now.addingTimeInterval(60)) }
    }
}
