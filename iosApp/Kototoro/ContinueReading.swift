import KototoroShared
import SwiftUI

/// Android's "continue reading" button, as the tab bar's bottom accessory (the system place for an ongoing activity,
/// like Music's now playing): the latest work of the active space from the history, opened straight at the chapter
/// being read.
struct ContinueReadingAccessory: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true
    @Environment(\.openContinueReading) private var open

    private var latest: IosLibraryEntry? {
        ContentSpace.latest(in: library.history, enabled: spacesEnabled, space: space)
    }

    var body: some View {
        Button {
            if let latest { open(CategoryContent(content: latest.content)) }
        } label: {
            HStack(spacing: 10) {
                if let latest, sources.runtime != nil {
                    // Loaded by the tab container: the system-hosted accessory does not run its views' tasks.
                    Group {
                        if let cover = ContinueReadingCover.shared.image { Image(uiImage: cover).renderingMode(.original).resizable().scaledToFill() }
                        else { Rectangle().fill(.quaternary) }
                    }
                    .frame(width: 24, height: 34)
                    .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                    .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(latest.content.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                        if placement != .inline {
                            Text(ContinueReadingDetail.text(latest))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 4)
                    Image(systemName: latest.content.mediaKind == "VIDEO" ? "play.fill" : "book.fill")
                        .font(.body.weight(.semibold))
                } else {
                    Image(systemName: "book")
                    Text("开始阅读后可在这里继续").font(.subheadline).foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                }
            }
            .padding(.horizontal, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(latest == nil || sources.runtime == nil)
        .accessibilityLabel(latest.map { "继续阅读：\($0.content.title)" } ?? "继续阅读")
        .accessibilityIdentifier("continueReading")
    }
}

enum ContinueReadingDetail {
    /// "继续阅读 · 第 12 话 · 35%": the chapter read last, then the overall progress when known.
    static func text(_ entry: IosLibraryEntry) -> String {
        let action = entry.content.mediaKind == "VIDEO" ? String(localized: "继续播放") : String(localized: "继续阅读")
        var parts = [action]
        if let chapter = entry.lastChapterTitle { parts.append(chapter) }
        if entry.hasProgress && entry.progress >= 0.01 { parts.append("\(Int(entry.progress * 100))%") }
        return parts.joined(separator: " · ")
    }
}

private struct ContinueReadingPresentation: ViewModifier {
    @Binding var opened: CategoryContent?
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var downloads: ReaderDownloadQueue

    func body(content: Content) -> some View {
        content.fullScreenCover(item: $opened) { item in
            if let runtime = sources.runtime {
                NavigationStack {
                    SourceDetailsView(runtime: runtime, content: item.content, autoResume: true)
                        .background(ContinueReadingEdgeDismiss { opened = nil })
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) {
                                Button("关闭", systemImage: "xmark") { opened = nil }
                                    .accessibilityIdentifier("continueReading.close")
                            }
                        }
                }
                // A cover sits outside the injected environment; pass the models explicitly.
                .environmentObject(library)
                .environmentObject(sources)
                .environmentObject(downloads)
            }
        }
    }
}

/// The resumed details are the cover's navigation root, so UIKit has no previous page to pop to.
private struct ContinueReadingEdgeDismiss: UIViewControllerRepresentable {
    let dismiss: () -> Void

    func makeUIViewController(context: Context) -> Controller { Controller(dismiss: dismiss) }
    func updateUIViewController(_ controller: Controller, context: Context) { controller.dismissCover = dismiss }

    final class Controller: UIViewController, UIGestureRecognizerDelegate {
        var dismissCover: () -> Void
        private weak var installedView: UIView?
        private lazy var edge = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(swiped(_:)))

        init(dismiss: @escaping () -> Void) {
            dismissCover = dismiss
            super.init(nibName: nil, bundle: nil)
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard let navigationView = navigationController?.view else { return }
            edge.edges = .left
            edge.delegate = self
            navigationView.addGestureRecognizer(edge)
            installedView = navigationView
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            installedView?.removeGestureRecognizer(edge)
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            navigationController?.viewControllers.count == 1
        }

        @objc private func swiped(_ gesture: UIScreenEdgePanGestureRecognizer) {
            guard gesture.state == .ended, let view = gesture.view else { return }
            let distance = gesture.translation(in: view).x
            if distance > view.bounds.width * 0.25 || (distance > 0 && gesture.velocity(in: view).x > 500) {
                dismissCover()
            }
        }
    }
}

/// Pushed readers cover the screen, so the tab bar's continue-reading accessory steps aside while one is shown.
@MainActor @Observable
final class ReaderPresence {
    static let shared = ReaderPresence()
    private(set) var visibleReaders = 0
    private(set) var visibleDetails = 0
    private var protectedDirectories: [UUID: Set<String>] = [:]
    var isReading: Bool { visibleReaders > 0 }
    var shouldSuppressAccessory: Bool { visibleReaders > 0 || visibleDetails > 0 }
    func appear() { visibleReaders += 1 }
    func disappear() {
        visibleReaders = max(0, visibleReaders - 1)
        Task { await ReaderStorageAccess.shared.requestIdleAction() }
    }
    func detailAppear() { visibleDetails += 1 }
    func detailDisappear() { visibleDetails = max(0, visibleDetails - 1) }
    func protect(_ directories: Set<String>, owner: UUID) { protectedDirectories[owner] = directories }
    func release(_ owner: UUID) { protectedDirectories[owner] = nil }
    func protects(_ directory: String) -> Bool { protectedDirectories.values.contains { $0.contains(directory) } }
}

private struct ReaderPresenceModifier: ViewModifier {
    let directories: Set<String>
    @State private var owner = UUID()
    func body(content: Content) -> some View {
        content
            .onAppear { ReaderPresence.shared.appear(); ReaderPresence.shared.protect(directories, owner: owner) }
            .onChange(of: directories) { _, value in ReaderPresence.shared.protect(value, owner: owner) }
            .onDisappear { ReaderPresence.shared.disappear(); ReaderPresence.shared.release(owner) }
    }
}

private struct DetailsPresenceModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .onAppear { ReaderPresence.shared.detailAppear() }
            .onDisappear { ReaderPresence.shared.detailDisappear() }
    }
}

extension View {
    /// Details and their pushed directories replace the tab container's global resume controls.
    func detailsPresence() -> some View {
        modifier(DetailsPresenceModifier())
    }

    /// Marks a full-screen reading surface (manga, novel or video) while it is on screen.
    func readerPresence(directories: Set<String> = []) -> some View {
        modifier(ReaderPresenceModifier(directories: directories))
    }

    /// One resume presentation shared by the tab accessory and the space panel.
    func continueReadingPresentation(opened: Binding<CategoryContent?>) -> some View {
        modifier(ContinueReadingPresentation(opened: opened))
    }

    /// The continue-reading accessory, shown only while there is something to continue where the system allows it.
    func continueReadingAccessory(enabled: Bool) -> some View {
        modifier(ContinueReadingAccessoryModifier(enabled: enabled))
    }
}

private struct ContinueReadingAccessoryModifier: ViewModifier {
    let enabled: Bool
    func body(content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: enabled) {
                if enabled {
                    ContinueReadingAccessory()
                }
            }
        } else {
            content.tabViewBottomAccessory {
                if enabled {
                    ContinueReadingAccessory()
                }
            }
        }
    }
}

/// Presents a resumed work from the tab container rather than the accessory, which hides while a reader is shown
/// and must not take its own presentation down with it.
/// The accessory's cover, loaded by the tab container. Observable rather than an environment value: the
/// system-hosted accessory keeps the environment it was created with, but follows observation.
@MainActor @Observable
final class ContinueReadingCover {
    static let shared = ContinueReadingCover()
    var image: UIImage?
}

private struct OpenContinueReadingKey: EnvironmentKey {
    static let defaultValue: (CategoryContent) -> Void = { _ in }
}

extension EnvironmentValues {
    var openContinueReading: (CategoryContent) -> Void {
        get { self[OpenContinueReadingKey.self] }
        set { self[OpenContinueReadingKey.self] = newValue }
    }
}

/// Opens the app-wide search (the search tab) from a page's toolbar.
private struct OpenGlobalSearchKey: EnvironmentKey {
    static let defaultValue: () -> Void = {}
}

extension EnvironmentValues {
    var openGlobalSearch: () -> Void {
        get { self[OpenGlobalSearchKey.self] }
        set { self[OpenGlobalSearchKey.self] = newValue }
    }
}

/// In-place page search (library, browse, records): the system field under the title, as Mail and Notes place it
/// (`navigationBarDrawer`), shown at the top and tucked away while the content scrolls. The search tab stays the
/// app-wide search; the minimized toolbar search could not be closed inside this TabView, which this placement avoids.
extension View {
    func pageSearch(text: Binding<String>, prompt: String) -> some View {
        searchable(text: text, placement: .navigationBarDrawer(displayMode: .automatic), prompt: prompt)
    }
}
