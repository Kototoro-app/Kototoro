import CryptoKit
import Foundation
import XCTest

final class AppUpdateTests: XCTestCase {
    private let asset = URL(string: "https://github.com/Kototoro-app/Kototoro/releases/download/v2.3.7/Kototoro.ipa")!

    private func release(_ version: String = "2.3.7", build: String = "7882", minimum: String = "26.0",
                         bytes: Data = Data("test IPA".utf8)) -> AppRelease {
        AppRelease(version: version, buildVersion: build, downloadURL: asset, size: Int64(bytes.count),
                   sha256: SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(),
                   minOSVersion: minimum, localizedDescription: "Test release")
    }

    private func feed(_ releases: [AppRelease], bundle: String = "org.skepsun.kototoro.ios") throws -> Data {
        let versions: [[String: Any]] = releases.map {
            ["version": $0.version, "buildVersion": $0.buildVersion, "downloadURL": $0.downloadURL.absoluteString,
             "size": $0.size, "sha256": $0.sha256, "minOSVersion": $0.minOSVersion,
             "localizedDescription": $0.localizedDescription]
        }
        return try JSONSerialization.data(withJSONObject:
            ["apps": [["bundleIdentifier": bundle, "versions": versions]]])
    }

    private func client(_ responses: [URL: UpdateResponse]) -> AppUpdateClient {
        UpdateURLProtocol.configure(responses)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UpdateURLProtocol.self]
        configuration.timeoutIntervalForResource = 5
        let session = URLSession(configuration: configuration)
        addTeardownBlock { session.invalidateAndCancel() }
        return AppUpdateClient(session: session)
    }

    func testReleaseAndNightlyDisplayTheActualBundleVersionWithoutDevelopmentLabel() {
        for channel in AppUpdateChannel.allCases {
            let build = AppBuildInfo(info: ["CFBundleShortVersionString": "2.3.7", "CFBundleVersion": "7882",
                "KototoroBuildConfiguration": "Release", "KototoroUpdateChannel": channel.rawValue,
                "CFBundleIdentifier": "org.skepsun.kototoro.ios.resigned"])
            XCTAssertEqual(build.description, "2.3.7 (7882)")
            XCTAssertEqual(build.channel, channel)
            XCTAssertFalse(build.isDebug)
        }
        let debug = AppBuildInfo(info: ["CFBundleShortVersionString": "2.3.7", "CFBundleVersion": "7883",
                                        "KototoroBuildConfiguration": "Debug"])
        XCTAssertEqual(debug.description, "2.3.7 (7883) · Debug")
    }

    func testVersionOrderingUsesNumericMarketingVersionThenBuildNumber() throws {
        func version(_ number: String, _ build: String) throws -> AppReleaseVersion {
            try XCTUnwrap(AppReleaseVersion(number: number, build: build))
        }
        XCTAssertGreaterThan(try version("2.10.0", "1"), try version("2.9.9", "9999"))
        XCTAssertGreaterThan(try version("2.3.7", "100"), try version("2.3.7", "99"))
        XCTAssertEqual(try version("2.3", "01"), try version("2.3.0", "1.0"))
        for number in ["", "2..3", "2.3.7-beta", "-1.0", "1.2.3.4", "18446744073709551616"] {
            XCTAssertNil(AppReleaseVersion(number: number, build: "1"))
        }
    }

    func testFeedFindsTheNewestCompatibleIPARegardlessOfEntryOrder() throws {
        let bytes = try feed([release(build: "7881"), release("3.0.0", minimum: "99.0"),
                              release(), release("2.3.6", build: "9999")])
        let parsed = try JSONDecoder().decode(AppUpdateFeed.self, from: bytes)
        XCTAssertEqual(try parsed.latest(osVersion: "27.0").description, "2.3.7 (7882)")
    }

    func testMissingIOSPackageAndUnsupportedSystemDoNotReportUpToDate() throws {
        for bytes in [try feed([]), try feed([release()], bundle: "org.example.other")] {
            let parsed = try JSONDecoder().decode(AppUpdateFeed.self, from: bytes)
            XCTAssertThrowsError(try parsed.latest(osVersion: "27.0"))
        }
        let parsed = try JSONDecoder().decode(AppUpdateFeed.self, from: feed([release(minimum: "99.0")]))
        XCTAssertThrowsError(try parsed.latest(osVersion: "27.0"))
    }

    func testInvalidAssetMetadataCannotBecomeAnUpdate() throws {
        var object = try JSONSerialization.jsonObject(with: feed([release()])) as! [String: Any]
        let original = object["apps"] as! [[String: Any]]
        for change in [["sha256": "bad"], ["downloadURL": "https://example.com/app.apk"],
                       ["downloadURL": "http://example.com/app.ipa"], ["version": "not a version"]] {
            var apps = original
            var versions = apps[0]["versions"] as! [[String: Any]]
            for (key, value) in change { versions[0][key] = value }
            apps[0]["versions"] = versions
            object["apps"] = apps
            let parsed = try JSONDecoder().decode(AppUpdateFeed.self,
                from: JSONSerialization.data(withJSONObject: object))
            XCTAssertThrowsError(try parsed.latest(osVersion: "27.0"))
        }
    }

    func testCheckFetchesSelectedChannelAndRejectsHTTPFailures() async throws {
        let client = client([AppUpdateChannel.nightly.sourceURL: UpdateResponse(data: try feed([release()]))])
        let latest = try await client.latest(channel: .nightly)
        XCTAssertEqual(latest.description, "2.3.7 (7882)")
        UpdateURLProtocol.configure([AppUpdateChannel.release.sourceURL: UpdateResponse(status: 503, data: Data())])
        do {
            _ = try await client.latest(channel: .release)
            XCTFail("An HTTP error must not mean there is no update")
        } catch { XCTAssertTrue(error is AppUpdateError) }
    }

    @MainActor
    func testSlowPreviousChannelCannotReplaceTheNewSelection() async throws {
        let client = client([
            AppUpdateChannel.release.sourceURL: UpdateResponse(data: try feed([release("3.0.0")]), delay: 0.2),
            AppUpdateChannel.nightly.sourceURL: UpdateResponse(data: try feed([release()])),
        ])
        let model = AppUpdateModel(client: client)
        let old = Task { await model.check(channel: .release) }
        try await Task.sleep(for: .milliseconds(50))
        await model.check(channel: .nightly)
        await old.value
        XCTAssertEqual(model.release?.description, "2.3.7 (7882)")
        XCTAssertFalse(model.checking)
        XCTAssertNil(model.error)
    }

    func testPackageVerificationRejectsChangedOrTruncatedFiles() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("test.ipa")
        let bytes = Data(repeating: 42, count: 1024 * 1024 + 23)
        let package = release(bytes: bytes)
        try bytes.write(to: file)
        XCTAssertNoThrow(try AppUpdateClient.verify(file, release: package))
        var changed = bytes
        changed[changed.count - 1] ^= 1
        try changed.write(to: file)
        XCTAssertThrowsError(try AppUpdateClient.verify(file, release: package))
        try bytes.dropLast().write(to: file)
        XCTAssertThrowsError(try AppUpdateClient.verify(file, release: package))
    }

    func testDownloadProducesAVerifiedIPAFileForExport() async throws {
        let bytes = Data(repeating: 42, count: 64 * 1024)
        let package = release(bytes: bytes)
        let client = client([asset: UpdateResponse(data: bytes)])
        let downloaded = try await client.download(package, from: asset)
        defer { try? FileManager.default.removeItem(at: downloaded.deletingLastPathComponent()) }
        XCTAssertEqual(downloaded.pathExtension, "ipa")
        XCTAssertEqual(try Data(contentsOf: downloaded), bytes)
    }

    func testDownloadRejectsHTTPFailuresAndWrongChecksums() async throws {
        let expected = release()
        let client = client([asset: UpdateResponse(status: 503, data: Data())])
        do {
            _ = try await client.download(expected, from: asset)
            XCTFail("An HTTP error must not be exported as an IPA")
        } catch { XCTAssertTrue(error is AppUpdateError) }
        UpdateURLProtocol.configure([asset: UpdateResponse(data: Data("bad file".utf8))])
        do {
            _ = try await client.download(expected, from: asset)
            XCTFail("A changed IPA must not be exported")
        } catch { XCTAssertTrue(error is AppUpdateError) }
    }

    @MainActor
    func testCancellingADownloadDoesNotPublishAFileOrAnError() async throws {
        let package = release()
        let client = client([
            AppUpdateChannel.release.sourceURL: UpdateResponse(data: try feed([package])),
            asset: UpdateResponse(data: Data("test IPA".utf8), delay: 1),
        ])
        let model = AppUpdateModel(client: client)
        await model.check(channel: .release)
        model.download(from: asset)
        XCTAssertTrue(model.downloading)
        model.cancelDownload()
        for _ in 0..<50 where model.downloading { try await Task.sleep(for: .milliseconds(20)) }
        XCTAssertFalse(model.downloading)
        XCTAssertNil(model.downloadedFile)
        XCTAssertNil(model.error)
    }

    func testStoreLinksKeepTheCorrectReleaseFeed() throws {
        for channel in AppUpdateChannel.allCases {
            for scheme in ["altstore", "sidestore"] {
                let url = channel.storeURL(scheme: scheme)
                XCTAssertEqual(url.scheme, scheme)
                XCTAssertEqual(url.host, "source")
                let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
                XCTAssertEqual(components.queryItems?.first { $0.name == "url" }?.value,
                               channel.sourceURL.absoluteString)
            }
        }
    }

    @MainActor
    func testAboutShowsVersionAndOpensAppUpdates() {
        let app = XCUIApplication()
        app.launch()
        app.openSettings("settings.about")
        let version = app.descendants(matching: .any)["settings.appVersion"].firstMatch
        XCTAssertTrue(version.waitForExistence(timeout: 10))
        XCTAssertFalse(version.label.contains("开发版"))
        if let expected = ProcessInfo.processInfo.environment["KOTOTORO_EXPECT_APP_VERSION"] {
            XCTAssertTrue(app.staticTexts[expected].exists, app.debugDescription)
        }
        let about = XCTAttachment(screenshot: app.screenshot())
        about.name = "About with the installed bundle version"; about.lifetime = .keepAlways; add(about)
        app.buttons["settings.appUpdates"].tap()
        XCTAssertTrue(app.buttons["appUpdate.check"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.descendants(matching: .any)["appUpdate.channel"].firstMatch.exists)
        let updates = XCTAttachment(screenshot: app.screenshot())
        updates.name = "Native IPA update page"; updates.lifetime = .keepAlways; add(updates)
    }
}

private struct UpdateResponse {
    var status = 200
    let data: Data
    var delay: TimeInterval = 0
}

private final class UpdateURLProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var responses: [URL: UpdateResponse] = [:]
    private var work: DispatchWorkItem?

    static func configure(_ responses: [URL: UpdateResponse]) {
        lock.lock(); defer { lock.unlock() }
        self.responses = responses
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        let stub = request.url.flatMap { Self.responses[$0] } ?? UpdateResponse(status: 404, data: Data())
        Self.lock.unlock()
        let work = DispatchWorkItem { [self] in
            let response = HTTPURLResponse(url: request.url!, statusCode: stub.status, httpVersion: "HTTP/1.1",
                headerFields: ["Content-Length": "\(stub.data.count)", "Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: stub.data)
            client?.urlProtocolDidFinishLoading(self)
        }
        self.work = work
        DispatchQueue.global().asyncAfter(deadline: .now() + stub.delay, execute: work)
    }
    override func stopLoading() { work?.cancel() }
}
