/// A cookie alone is insufficient: the current main document must have finished without a challenge.
struct BrowserVerificationState {
    let status: Int?
    let challengePresent: Bool?

    var challengeActive: Bool {
        status == 403 || status == 503 || challengePresent == true
    }

    func verified(hasClearance: Bool) -> Bool {
        guard let status, (200..<400).contains(status), challengePresent == false else { return false }
        return hasClearance && !challengeActive
    }
}
