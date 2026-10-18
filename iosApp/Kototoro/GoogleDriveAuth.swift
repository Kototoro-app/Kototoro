import AuthenticationServices
import CryptoKit
import Foundation
import Security
import UIKit

enum GoogleDriveError: LocalizedError {
    case message(String)
    case authorizationRequired

    var errorDescription: String? {
        switch self {
        case .message(let text): text
        case .authorizationRequired: "Google 授权已失效，请重新连接账号"
        }
    }
}

struct GoogleDriveOAuth {
    static let scope = "https://www.googleapis.com/auth/drive.appdata"
    let clientID: String
    var scheme: String { clientID.split(separator: ".").reversed().joined(separator: ".") }
    var redirectURI: String { "\(scheme):/oauth2redirect" }

    static var configured: GoogleDriveOAuth? {
        guard let client = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String,
              client.hasSuffix(".apps.googleusercontent.com"), !client.contains("$(") else { return nil }
        let configuration = GoogleDriveOAuth(clientID: client)
        let types = Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []
        guard types.contains(where: { ($0["CFBundleURLSchemes"] as? [String])?.contains(configuration.scheme) == true })
        else { return nil }
        return configuration
    }

    static func challenge(_ verifier: String) -> String {
        Data(SHA256.hash(data: Data(verifier.utf8))).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    func authorizationURL(state: String, verifier: String) -> URL {
        var url = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        url.queryItems = ["client_id": clientID, "redirect_uri": redirectURI, "response_type": "code",
                          "scope": "openid email \(Self.scope)", "state": state,
                          "code_challenge": Self.challenge(verifier), "code_challenge_method": "S256",
                          "access_type": "offline", "prompt": "consent select_account"].map {
            URLQueryItem(name: $0.key, value: $0.value)
        }
        return url.url!
    }

    func code(from callback: URL, state: String) throws -> String {
        let parts = URLComponents(url: callback, resolvingAgainstBaseURL: false)
        let items = parts?.queryItems ?? []
        func value(_ name: String) -> String? {
            let matches = items.filter { $0.name == name }
            return matches.count == 1 ? matches[0].value : nil
        }
        guard callback.scheme == scheme, callback.host == nil, callback.path == "/oauth2redirect",
              value("state") == state else { throw GoogleDriveError.message("Google 授权回调校验失败") }
        guard !items.contains(where: { $0.name == "error" }), let code = value("code"), !code.isEmpty else {
            throw GoogleDriveError.message("未获得 Google Drive 授权")
        }
        return code
    }

    static func form(_ values: [String: String]) -> Data {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        return Data(values.sorted { $0.key < $1.key }.map {
            "\($0.key.addingPercentEncoding(withAllowedCharacters: allowed)!)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed)!)"
        }.joined(separator: "&").utf8)
    }
}

struct GoogleDriveCredential: Codable {
    let clientID: String
    let subject: String
    let email: String
    let refreshToken: String
    let accessToken: String
    let expiresAt: Date
}

enum GoogleDriveKeychain {
    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "org.skepsun.kototoro.google-drive",
        kSecAttrAccount as String: "oauth"
    ]

    static func read() throws -> GoogleDriveCredential? {
        var fields = query
        fields[kSecReturnData as String] = true
        fields[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(fields as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw GoogleDriveError.message("无法读取 Google 安全凭据（\(status)）")
        }
        return try JSONDecoder().decode(GoogleDriveCredential.self, from: data)
    }

    static func write(_ credential: GoogleDriveCredential?) throws {
        guard let credential else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw GoogleDriveError.message("无法移除 Google 安全凭据（\(status)）")
            }
            return
        }
        let values: [String: Any] = [kSecValueData as String: try JSONEncoder().encode(credential),
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(values) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw GoogleDriveError.message("无法保存 Google 安全凭据（\(status)）") }
    }
}

@MainActor
final class GoogleDriveAuthorization: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?
    private var pending: CheckedContinuation<URL, Error>?

    private struct TokenResponse: Decodable {
        let access_token: String
        let expires_in: Double
        let refresh_token: String?
        let scope: String?
    }

    func connect(_ configuration: GoogleDriveOAuth) async throws -> GoogleDriveCredential {
        let state = UUID().uuidString
        let verifier = UUID().uuidString + UUID().uuidString
        let callback = try await authenticate(configuration.authorizationURL(state: state, verifier: verifier),
                                               scheme: configuration.scheme)
        let code = try configuration.code(from: callback, state: state)
        let token = try await exchange(["client_id": configuration.clientID, "code": code,
                                       "code_verifier": verifier, "redirect_uri": configuration.redirectURI,
                                       "grant_type": "authorization_code"])
        guard let refresh = token.refresh_token, !refresh.isEmpty,
              token.scope?.split(separator: " ").contains(Substring(GoogleDriveOAuth.scope)) == true else {
            throw GoogleDriveError.message("请允许访问应用在 Google Drive 中的数据后重新连接")
        }
        var request = URLRequest(url: URL(string: "https://openidconnect.googleapis.com/v1/userinfo")!)
        request.setValue("Bearer \(token.access_token)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw GoogleDriveError.authorizationRequired }
        struct Account: Decodable { let sub: String; let email: String? }
        let account = try JSONDecoder().decode(Account.self, from: data)
        return GoogleDriveCredential(clientID: configuration.clientID, subject: account.sub,
                                     email: account.email ?? "Google Drive", refreshToken: refresh,
                                     accessToken: token.access_token, expiresAt: Date().addingTimeInterval(token.expires_in))
    }

    func refresh(_ credential: GoogleDriveCredential) async throws -> GoogleDriveCredential {
        let token = try await exchange(["client_id": credential.clientID, "refresh_token": credential.refreshToken,
                                       "grant_type": "refresh_token"])
        if let scope = token.scope, !scope.split(separator: " ").contains(Substring(GoogleDriveOAuth.scope)) {
            throw GoogleDriveError.authorizationRequired
        }
        return GoogleDriveCredential(clientID: credential.clientID, subject: credential.subject, email: credential.email,
                                     refreshToken: token.refresh_token ?? credential.refreshToken,
                                     accessToken: token.access_token, expiresAt: Date().addingTimeInterval(token.expires_in))
    }

    private func exchange(_ values: [String: String]) async throws -> TokenResponse {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = GoogleDriveOAuth.form(values)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw GoogleDriveError.message("Google 授权服务无响应") }
        guard http.statusCode == 200 else {
            struct Failure: Decodable { let error: String }
            if (try? JSONDecoder().decode(Failure.self, from: data).error) == "invalid_grant" {
                throw GoogleDriveError.authorizationRequired
            }
            // Never display response bodies: they may contain credentials or authorization codes.
            throw GoogleDriveError.message("Google 授权失败（HTTP \(http.statusCode)），请检查网络及客户端配置")
        }
        let token = try JSONDecoder().decode(TokenResponse.self, from: data)
        guard !token.access_token.isEmpty, token.expires_in > 0 else { throw GoogleDriveError.authorizationRequired }
        return token
    }

    private func authenticate(_ url: URL, scheme: String) async throws -> URL {
        try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                pending = continuation
                let authentication = ASWebAuthenticationSession(url: url, callbackURLScheme: scheme) { [weak self] url, error in
                    Task { @MainActor in
                        if let url { self?.finish(.success(url)) }
                        else { self?.finish(.failure(error ?? GoogleDriveError.message("授权未完成"))) }
                    }
                }
                authentication.presentationContextProvider = self
                session = authentication
                if !authentication.start() { finish(.failure(GoogleDriveError.message("无法打开 Google 授权页面"))) }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.session?.cancel()
                self?.finish(.failure(CancellationError()))
            }
        }
    }

    private func finish(_ result: Result<URL, Error>) {
        let continuation = pending
        pending = nil
        session = nil
        continuation?.resume(with: result)
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
