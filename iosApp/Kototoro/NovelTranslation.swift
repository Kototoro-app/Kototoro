import CryptoKit
import Foundation
import NaturalLanguage
import SwiftUI
import Translation

/// Novel translation with the system's Translation framework: Android's translate-chapter action, shown as the
/// translation alone or below the dimmed original. Language packs download once and then translate offline.
enum NovelTranslationSettings {
    static let bilingualKey = "novel.translation.bilingual"
    /// A `Locale.Language` identifier; empty follows the system language.
    static let targetKey = "novel.translation.target"

    static func target(_ stored: String) -> Locale.Language {
        stored.isEmpty ? Locale.current.language : Locale.Language(identifier: stored)
    }

    /// The chapter's language, recognized from its first lines; the system translator needs a known source to
    /// prepare its language pack.
    static func source(of lines: [String]) -> Locale.Language? {
        var sample = ""
        for line in lines where sample.count < 2_000 { sample += line + "\n" }
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(sample)
        guard let language = recognizer.dominantLanguage, language != .undetermined else { return nil }
        return Locale.Language(identifier: language.rawValue)
    }

    /// Whether [source] needs no translation into [target] (the same language, any script or region).
    static func matches(_ source: Locale.Language, _ target: Locale.Language) -> Bool {
        source.languageCode == target.languageCode && (source.script == nil || target.script == nil
            || source.script == target.script)
    }

    static func title(_ language: Locale.Language) -> String {
        Locale.current.localizedString(forIdentifier: language.minimalIdentifier) ?? language.minimalIdentifier
    }
}

enum NovelTranslationStatus: Equatable {
    case idle
    case translating(done: Int, total: Int)
    case failed(String)
    /// The chapter is already in the target language.
    case sameLanguage

    var text: String? {
        switch self {
        case .idle: nil
        case .translating(let done, let total): String(localized: "正在翻译 \(done)/\(total)")
        case .failed(let message): message
        case .sameLanguage: String(localized: "正文已是目标语言，无需翻译")
        }
    }
}

/// Translations of a chapter's lines (by block index) per target language, kept in the caches directory so a
/// chapter read again is not translated again.
enum NovelTranslationCache {
    private static let directory: URL = {
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appending(path: "novel-translations", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    private static func file(_ key: String) -> URL {
        let digest = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appending(path: "\(digest).json")
    }

    static func key(content: String, chapter: String, target: Locale.Language) -> String {
        "\(content)|\(chapter)|\(target.minimalIdentifier)"
    }

    static func load(_ key: String) -> [Int: String] {
        guard let data = try? Data(contentsOf: file(key)),
              let stored = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return Dictionary(uniqueKeysWithValues: stored.compactMap { key, value in Int(key).map { ($0, value) } })
    }

    static func save(_ key: String, _ translations: [Int: String]) {
        let stored = Dictionary(uniqueKeysWithValues: translations.map { (String($0.key), $0.value) })
        guard let data = try? JSONEncoder().encode(stored) else { return }
        try? data.write(to: file(key), options: .atomic)
    }

    static func clear() {
        try? FileManager.default.removeItem(at: directory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
}

extension NovelReaderModel {
    /// Lines per request: large enough to translate quickly, small enough to show progress while reading.
    private static let batchSize = 24

    /// Translates the chapter's remaining lines with [session], applying each batch as it arrives.
    func translate(using session: TranslationSession) async {
        guard translationEnabled, let document = sourceDocument else { return }
        let chapterID = chapter.id
        let key = translationKey
        let pending = document.translatableTexts
            .compactMap { entry -> (Int, String)? in
                let index = entry.key.intValue
                return translations[index] == nil ? (index, entry.value) : nil
            }
            .sorted { $0.0 < $1.0 }
        guard !pending.isEmpty else { translationStatus = .idle; return }
        let total = pending.count
        var done = 0
        translationStatus = .translating(done: 0, total: total)
        do {
            try await session.prepareTranslation()
            for start in stride(from: 0, to: pending.count, by: Self.batchSize) {
                let batch = pending[start..<min(pending.count, start + Self.batchSize)]
                let requests = batch.map { TranslationSession.Request(sourceText: $0.1, clientIdentifier: String($0.0)) }
                let responses = try await session.translations(from: requests)
                try Task.checkCancellation()
                guard translationEnabled, chapter.id == chapterID, translationKey == key else { return }
                var update: [Int: String] = [:]
                for response in responses {
                    if let index = response.clientIdentifier.flatMap(Int.init) { update[index] = response.targetText }
                }
                done += batch.count
                applyTranslations(update)
                NovelTranslationCache.save(key, translations)
                translationStatus = done < total ? .translating(done: done, total: total) : .idle
            }
        } catch is CancellationError {
            if translationStatus != .idle { translationStatus = .idle }
        } catch {
            guard chapter.id == chapterID else { return }
            translationStatus = .failed(Self.describe(error))
        }
    }

    private static func describe(_ error: Error) -> String {
        if TranslationError.unsupportedLanguagePairing ~= error || TranslationError.unsupportedSourceLanguage ~= error
            || TranslationError.unsupportedTargetLanguage ~= error {
            return String(localized: "系统翻译不支持这一语言组合")
        }
        if TranslationError.unableToIdentifyLanguage ~= error { return String(localized: "无法识别正文语言") }
        return String(localized: "翻译失败：\(error.localizedDescription)")
    }
}

/// Translation settings of the reader's settings sheet: how translations are shown and the target language.
struct NovelTranslationSettingsSection: View {
    @AppStorage(NovelTranslationSettings.bilingualKey) private var bilingual = false
    @AppStorage(NovelTranslationSettings.targetKey) private var target = ""
    @State private var languages: [Locale.Language] = []
    @State private var cleared = false

    var body: some View {
        Section {
            Picker("显示方式", selection: $bilingual) {
                Text("仅译文").tag(false)
                Text("双语对照").tag(true)
            }
            .accessibilityIdentifier("novel.settings.translationMode")
            Picker("目标语言", selection: $target) {
                Text("跟随系统（\(NovelTranslationSettings.title(Locale.current.language))）").tag("")
                ForEach(languages, id: \.minimalIdentifier) { language in
                    Text(NovelTranslationSettings.title(language)).tag(language.minimalIdentifier)
                }
            }
            .accessibilityIdentifier("novel.settings.translationTarget")
            Button(cleared ? "已清除翻译缓存" : "清除翻译缓存", role: .destructive) {
                NovelTranslationCache.clear()
                cleared = true
            }
            .disabled(cleared)
        } header: {
            Text("翻译")
        } footer: {
            Text("使用 iOS 系统翻译。首次翻译某种语言时会提示下载语言包，之后可离线翻译；译文按章节缓存在本机。")
        }
        .task {
            languages = await LanguageAvailability().supportedLanguages
                .sorted { NovelTranslationSettings.title($0) < NovelTranslationSettings.title($1) }
        }
    }
}
